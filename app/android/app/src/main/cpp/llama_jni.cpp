// llama.cpp 的最小 JNI 封装：加载模型、一次性生成。
//
// 只暴露三个函数（load / generate / free）：纪要是"一次性写完"的活儿，
// 流式回调会把 JNI 层搞得又长又难测，收益不大。
#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <string>
#include <vector>

#include "llama.h"

#define LOG_TAG "TingoLlama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct Session {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
    llama_sampler *sampler = nullptr;
    int n_ctx = 4096;
    /** 一次 llama_decode 的上限；预填充要按它切块，否则超长 prompt 会 abort */
    int n_batch = 512;
    /** 用户取消后置位：生成循环每步检查一次，让它能真的停下来 */
    std::atomic<bool> cancelled{false};
};

void free_session(Session *s) {
    if (s == nullptr) return;
    if (s->sampler) llama_sampler_free(s->sampler);
    if (s->ctx) llama_free(s->ctx);
    if (s->model) llama_model_free(s->model);
    delete s;
}

std::string jstring_to_utf8(JNIEnv *env, jstring value) {
    if (value == nullptr) return {};
    const char *chars = env->GetStringUTFChars(value, nullptr);
    std::string out(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(value, chars);
    return out;
}

}  // namespace


/**
 * 分块预填充。
 *
 * llama_decode 一次最多吃 n_batch 个 token，超了不是"返回错误"而是 GGML_ASSERT 直接 abort——
 * 实测把一份约 750 token 的转写稿喂进去，整个进程 SIGABRT（用户看到的是应用闪退）。
 * 真实会议转写稿动辄几千 token，所以必须按 n_batch 切块推进。
 */
static bool prefill_chunked(llama_context *ctx, llama_token *tokens, int n_tokens, int n_batch) {
    const int step = n_batch > 0 ? n_batch : 512;
    for (int offset = 0; offset < n_tokens; offset += step) {
        const int count = std::min(step, n_tokens - offset);
        llama_batch batch = llama_batch_get_one(tokens + offset, count);
        if (llama_decode(ctx, batch) != 0) {
            LOGE("预填充失败（offset=%d, count=%d）", offset, count);
            return false;
        }
    }
    return true;
}

extern "C" JNIEXPORT jlong JNICALL
Java_io_github_tancheng33_tingo_minutes_LlamaBridge_nativeLoad(
        JNIEnv *env, jclass, jstring model_path, jint n_threads, jint n_ctx) {
    const std::string path = jstring_to_utf8(env, model_path);

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;  // 手机上没有 CUDA/Metal，全 CPU
    llama_model *model = llama_model_load_from_file(path.c_str(), mparams);
    if (model == nullptr) {
        LOGE("模型加载失败: %s", path.c_str());
        return 0;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = n_ctx > 0 ? n_ctx : 4096;
    cparams.n_threads = n_threads > 0 ? n_threads : 4;
    cparams.n_batch = 512;
    llama_context *ctx = llama_init_from_model(model, cparams);
    if (ctx == nullptr) {
        LOGE("上下文创建失败");
        llama_model_free(model);
        return 0;
    }

    auto *session = new Session();
    session->model = model;
    session->ctx = ctx;
    session->vocab = llama_model_get_vocab(model);
    session->n_ctx = cparams.n_ctx;
    session->n_batch = cparams.n_batch;

    // 纪要是结构化输出，贪心解码最稳：同样的转写得到同样的纪要
    session->sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(session->sampler, llama_sampler_init_greedy());

    LOGI("模型已加载: %s (ctx=%d, threads=%d)", path.c_str(), cparams.n_ctx, cparams.n_threads);
    return reinterpret_cast<jlong>(session);
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_tancheng33_tingo_minutes_LlamaBridge_nativeGenerate(
        JNIEnv *env, jclass, jlong handle, jstring prompt, jint max_tokens) {
    auto *session = reinterpret_cast<Session *>(handle);
    if (session == nullptr) return env->NewStringUTF("");

    const std::string text = jstring_to_utf8(env, prompt);

    session->cancelled.store(false);
    // 清空 KV 缓存：每次生成都是独立的
    llama_memory_t memory = llama_get_memory(session->ctx);
    if (memory) llama_memory_clear(memory, true);
    llama_sampler_reset(session->sampler);

    int n_prompt = -llama_tokenize(
        session->vocab, text.c_str(), static_cast<int32_t>(text.size()),
        nullptr, 0, true, true);
    if (n_prompt <= 0) {
        LOGE("分词失败");
        return env->NewStringUTF("");
    }
    std::vector<llama_token> tokens(n_prompt);
    if (llama_tokenize(
            session->vocab, text.c_str(), static_cast<int32_t>(text.size()),
            tokens.data(), n_prompt, true, true) < 0) {
        LOGE("分词写入失败");
        return env->NewStringUTF("");
    }

    std::string output;
    if (!prefill_chunked(session->ctx, tokens.data(), n_prompt, session->n_batch)) {
        LOGE("prompt 解码失败");
        return env->NewStringUTF("");
    }

    const int limit = max_tokens > 0 ? max_tokens : 512;
    char piece[256];
    for (int produced = 0; produced < limit; ++produced) {
        if (session->cancelled.load()) {
            LOGI("用户取消，已生成 %d token", produced);
            break;
        }
        llama_token token = llama_sampler_sample(session->sampler, session->ctx, -1);
        if (llama_vocab_is_eog(session->vocab, token)) break;

        int n = llama_token_to_piece(session->vocab, token, piece, sizeof(piece), 0, true);
        if (n > 0) output.append(piece, n);

        llama_batch next = llama_batch_get_one(&token, 1);
        if (llama_decode(session->ctx, next) != 0) {
            LOGE("增量解码失败");
            break;
        }
    }

    LOGI("生成完成: %d 字符", static_cast<int>(output.size()));
    return env->NewStringUTF(output.c_str());
}


/**
 * 数一份文本会占多少 token。
 *
 * 提示词预算必须按 token 算而不是按字符算：中文大约 1 字 1 token，但标点、数字、
 * 英文混排的比值并不固定。按字符猜上限，长会议会直接超出上下文。
 */
extern "C" JNIEXPORT void JNICALL
Java_io_github_tancheng33_tingo_minutes_LlamaBridge_nativeCancel(JNIEnv *, jclass, jlong handle) {
    auto *session = reinterpret_cast<Session *>(handle);
    if (session != nullptr) session->cancelled.store(true);
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_tancheng33_tingo_minutes_LlamaBridge_nativeCountTokens(
        JNIEnv *env, jclass, jlong handle, jstring text) {
    auto *session = reinterpret_cast<Session *>(handle);
    if (session == nullptr) return 0;
    const std::string utf8 = jstring_to_utf8(env, text);
    const int count = -llama_tokenize(session->vocab, utf8.c_str(),
                                      static_cast<int32_t>(utf8.size()), nullptr, 0, true, true);
    return count > 0 ? count : 0;
}

/**
 * 纯测速：把 prompt 预填充与逐 token 解码分别计时，返回 "prefill_tokens,prefill_ms,decode_tokens,decode_ms"。
 *
 * 界面上的生成走 nativeGenerate；这个入口只给基准测试用，避免"用整体耗时反推 tok/s"
 * 这种把预填充和生成长度混在一起的算法。
 */
extern "C" JNIEXPORT jstring JNICALL
Java_io_github_tancheng33_tingo_minutes_LlamaBridge_nativeBench(
        JNIEnv *env, jclass, jlong handle, jstring prompt, jint max_tokens) {
    auto *session = reinterpret_cast<Session *>(handle);
    if (session == nullptr) return env->NewStringUTF("0,0,0,0");

    const std::string text = jstring_to_utf8(env, prompt);
    llama_memory_t memory = llama_get_memory(session->ctx);
    if (memory) llama_memory_clear(memory, true);
    llama_sampler_reset(session->sampler);

    int n_prompt = -llama_tokenize(
        session->vocab, text.c_str(), static_cast<int32_t>(text.size()),
        nullptr, 0, true, true);
    if (n_prompt <= 0) return env->NewStringUTF("0,0,0,0");
    std::vector<llama_token> tokens(n_prompt);
    llama_tokenize(session->vocab, text.c_str(), static_cast<int32_t>(text.size()),
                   tokens.data(), n_prompt, true, true);

    const auto t0 = std::chrono::steady_clock::now();
    if (!prefill_chunked(session->ctx, tokens.data(), n_prompt, session->n_batch)) {
        return env->NewStringUTF("0,0,0,0");
    }
    const auto t1 = std::chrono::steady_clock::now();

    const int limit = max_tokens > 0 ? max_tokens : 256;
    int produced = 0;
    for (int i = 0; i < limit; ++i) {
        llama_token token = llama_sampler_sample(session->sampler, session->ctx, -1);
        if (llama_vocab_is_eog(session->vocab, token)) break;
        produced++;
        llama_batch next = llama_batch_get_one(&token, 1);
        if (llama_decode(session->ctx, next) != 0) break;
    }
    const auto t2 = std::chrono::steady_clock::now();

    const auto prefill_ms = std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count();
    const auto decode_ms = std::chrono::duration_cast<std::chrono::milliseconds>(t2 - t1).count();
    char out[128];
    snprintf(out, sizeof(out), "%d,%lld,%d,%lld", n_prompt,
             static_cast<long long>(prefill_ms), produced, static_cast<long long>(decode_ms));
    LOGI("基准: prompt=%d 预填充=%lldms 生成=%d token 解码=%lldms", n_prompt,
         static_cast<long long>(prefill_ms), produced, static_cast<long long>(decode_ms));
    return env->NewStringUTF(out);
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_tancheng33_tingo_minutes_LlamaBridge_nativeFree(
        JNIEnv *, jclass, jlong handle) {
    free_session(reinterpret_cast<Session *>(handle));
}
