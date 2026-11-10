//! tingo 引擎的 UniFFI 边界层。
//!
//! 这里只有两件事：把引擎能力以「领域类型 + 用例函数」暴露给 Kotlin，以及保证任何
//! Rust 侧 panic 都被转换成一个可展示的错误，而不是让宿主进程崩溃。
//!
//! 边界刻意保持窄：ASR 在 Android 上由 Kotlin 侧承担，因此这里不引入 sherpa 相关模块。

use std::panic::{self, AssertUnwindSafe};
use std::path::{Path, PathBuf};
use std::sync::Arc;

use tingo_engine::fishbone as engine_fishbone;
use tingo_engine::i18n as engine_i18n;
use tingo_engine::meetings;
use tingo_engine::minutes as engine_minutes;
use tingo_engine::minutes_history;
use tingo_engine::paths::EnginePaths;
use tingo_engine::transcript as engine_transcript;

uniffi::setup_scaffolding!();

/// 桥接层的错误类别。Kotlin 侧据类别选文案，不直接展示原始信息里的英文细节。
#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum EngineError {
    /// 调用方传入的参数不合法（空 id、含路径分隔符的 id 等）。
    #[error("input rejected: {detail}")]
    InvalidInput { detail: String },
    /// 需要的文件或目录不存在。
    #[error("resource missing: {detail}")]
    Missing { detail: String },
    /// 文件存在但内容无法解析。
    #[error("malformed content: {detail}")]
    Malformed { detail: String },
    /// 平台权限不足（如目录不可读写）。
    #[error("permission denied: {detail}")]
    PermissionDenied { detail: String },
    /// 引擎内部错误，含 panic 兜底。
    #[error("internal error: {detail}")]
    Internal { detail: String },
}

/// 引擎能力矩阵：界面据此决定哪些入口可见。
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct Capabilities {
    pub meeting_storage: bool,
    pub transcript_parse: bool,
    pub minutes_parse: bool,
    pub fishbone_parse: bool,
    pub speaker_naming: bool,
    pub terms_extract: bool,
}

/// 会议列表项。字段与引擎的 `meetings::Summary` 一一对应，界面不再二次拼装。
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct MeetingSummary {
    pub id: String,
    pub title: String,
    /// `YYYY-MM-DD`
    pub date: String,
    /// `HH:MM`，目录名里没时间时为空串
    pub time: String,
    /// 纪要里「一句话总结」那句话
    pub one_liner: String,
    pub has_minutes: bool,
    pub has_transcript: bool,
    /// `transcript.json` 损坏、目录不可读。Kotlin 侧为此条目显示「不可读」标记。
    pub unreadable: bool,
    pub has_audio: bool,
    /// 转写时长，`mm:ss` 或 `hh:mm:ss`
    pub duration: String,
    pub speakers: Vec<String>,
    pub tags: Vec<String>,
    pub archived: bool,
    pub size_bytes: u64,
}

/// 转写里的一句。说话人用编号表达，别名另存，界面按需替换。
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct Sentence {
    pub speaker: u32,
    pub start_ms: u64,
    pub end_ms: u64,
    pub text: String,
}

/// 一位说话人的别名。
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct SpeakerAlias {
    pub speaker: u32,
    pub name: String,
}

/// 合并后的发言段：同一说话人连续句子合成一段，仍保留边界时间与逐句内容。
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct Turn {
    /// 已解析的显示名（有别名用别名，否则「说话人N」）
    pub speaker_name: String,
    pub start_ms: u64,
    pub end_ms: u64,
    pub text: String,
    pub sentences: Vec<Sentence>,
}

/// 一份转写。
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct Transcript {
    pub source: String,
    pub duration_ms: u64,
    pub aliases: Vec<SpeakerAlias>,
    pub turns: Vec<Turn>,
}

/// 鱼骨图分支：`refs` 是形如 `12:34` 的原文引用。
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct FishboneBranch {
    pub title: String,
    pub detail: String,
    pub refs: Vec<String>,
}

/// 鱼骨图。
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct Fishbone {
    pub problem: String,
    pub branches: Vec<FishboneBranch>,
}


/// 纪要章节种类。中英标题在引擎侧已归一到这里，界面不必按语言匹配标题。
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum SectionKind {
    OneLiner,
    Discussion,
    Decisions,
    Actions,
    OpenQuestions,
    RootCause,
    /// 手改或新增的章节，`heading` 里带原标题
    Other,
}

/// 纪要的一个章节。`body` 是原样正文（含子标题、表格、代码块），不做渲染。
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct MinutesSection {
    pub kind: SectionKind,
    pub heading: String,
    pub body: String,
}

/// 一份解析后的纪要。
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct MinutesDoc {
    /// H1 标题；没有 H1 时为空串
    pub title: String,
    pub sections: Vec<MinutesSection>,
}

/// 一个会议的全部可读内容。缺哪个文件，对应字段就是 `None`，不视为损坏。
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct MeetingDetail {
    pub summary: MeetingSummary,
    pub transcript: Option<Transcript>,
    /// 纪要原文（Markdown）。分章节解析由 `parse_minutes` 负责。
    pub minutes_text: Option<String>,
    pub fishbone: Option<Fishbone>,
}


/// 一条搜索命中：界面直接渲染，不需要再读文件。
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct SearchHit {
    pub meeting_id: String,
    pub title: String,
    pub date: String,
    /// 纪要正文里也命中了（此时 [Self::snippet] 为空）
    pub in_minutes: bool,
    pub snippet: String,
    pub start_ms: u64,
    pub speaker: String,
}

/// 搜索结果的完整形态：`expanded` 让界面能说明"我是按这些词找的"。
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct SearchResults {
    pub query: String,
    pub expanded: Vec<String>,
    pub hits: Vec<SearchHit>,
}

/// 一场会议占多少空间。
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct MeetingUsage {
    pub id: String,
    pub title: String,
    pub date: String,
    pub bytes: u64,
    pub audio_bytes: u64,
    pub archived: bool,
}

/// 会议库的存储统计；`leftover_bytes` 是可以清理的残留文件。
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct StorageStats {
    pub count: u32,
    pub total_bytes: u64,
    pub audio_bytes: u64,
    pub leftover_bytes: u64,
    /// 占空间最大的若干场（引擎取前 10）
    pub largest: Vec<MeetingUsage>,
}

/// 清理旧音频的结果：删了多少、几场会议、有多少没删掉。
///
/// `failed` 不为 0 时界面必须说出来（例如文件正被占用），不能只报"已清理"。
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct AudioCleanup {
    pub freed_bytes: u64,
    pub meetings: u32,
    pub failed: u32,
}

/// 批量操作里单个会议的结果：失败不中断其余会议。
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct BatchOutcome {
    pub id: String,
    pub ok: bool,
    /// 失败原因（成功时为空串）
    pub error: String,
}

/// 回收站条目。
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct TrashItem {
    pub id: String,
    pub bytes: u64,
}






/// 热词归一化：去空白、去重、限数。空表是合法的（用户清空）。
fn normalize_hotwords(words: Vec<String>) -> Vec<String> {
    let mut out: Vec<String> = Vec::new();
    for word in words {
        let word = word.trim().to_string();
        if word.is_empty() || out.contains(&word) {
            continue;
        }
        out.push(word);
        if out.len() >= MAX_HOTWORDS {
            break;
        }
    }
    out
}

/// 列表用的版本摘要：只取前 200 字，避免把整篇纪要搬过 FFI 边界。
fn version_info(v: &minutes_history::Version) -> MinutesVersionInfo {
    MinutesVersionInfo {
        id: v.id.clone(),
        saved_at: v.saved_at,
        preview: v.text.trim().chars().take(200).collect(),
    }
}


/// 读会议目录里的转写；没有转写时报 `Missing`（界面据此提示"先生成转写"）。
fn load_transcript(dir: &Path) -> Result<engine_transcript::Transcript, EngineError> {
    let path = dir.join("transcript.json");
    if !path.is_file() {
        return Err(EngineError::Missing { detail: "还没有转写".to_string() });
    }
    engine_transcript::Transcript::load(&path).map_err(|e| io_error(e, "load_transcript"))
}

/// 写回转写（`meetings::save_transcript` 会顺带更新 meta 里的说话人信息）。
fn save_transcript(tr: &engine_transcript::Transcript, dir: &Path) -> Result<(), EngineError> {
    meetings::save_transcript(tr, dir).map_err(|e| io_error(e, "save_transcript"))
}

/// 说话人列表：按首次发言顺序，带上 AI 建议的位置（解析后再填）。
fn speaker_infos(tr: &engine_transcript::Transcript) -> Vec<SpeakerInfo> {
    tr.speakers_in_order()
        .into_iter()
        .enumerate()
        .map(|(index, speaker)| SpeakerInfo {
            index: index as u32,
            name: tr.name_of(speaker),
            suggested: String::new(),
            evidence: String::new(),
        })
        .collect()
}
/// 清音频时的天数上限（与桌面 `/api/storage/cleanup` 的 0–3650 一致）。
const MAX_CLEANUP_DAYS: u32 = 3650;

/// 回收站目录名。
///
/// 引擎判定"是不是会议目录"看的是内容（有没有纪要/录音/转写），不是名字，
/// 所以 `.trash/<id>` 只在里面直接放着这些文件时才可能被误认——回收站结构的
/// 单测（`trash_never_shows_up_in_library`）盯着这条不变量。
const TRASH_DIR: &str = ".trash";

/// 导入结果：会议 id 与它的目录。
///
/// 连目录一起返回，宿主就不必知道"会议库布局是 <root>/meetings/<id>"——
/// 这条约定只该有一个地方知道。
#[derive(uniffi::Record, Clone, Debug)]
pub struct ImportedMeeting {
    pub id: String,
    pub dir: String,
}

/// 配置文件名字（`<config>/config.toml`），与桌面 `config::default_path` 一致。
const CONFIG_FILE: &str = "config.toml";

/// 热词上限：手机提示词比桌面短，填太多会把转写挤掉。
const MAX_HOTWORDS: usize = 200;

/// 批量操作的动作白名单，与桌面 `/api/meetings/batch` 一致。
const BATCH_ACTIONS: [&str; 6] = ["archive", "unarchive", "tag_add", "tag_remove", "delete_audio", "delete"];

/// 批量操作里单个会议的执行体。返回可展示的错误信息。
fn apply_batch(root: &Path, id: &str, action: &str, tag: &str) -> Result<(), EngineError> {
    let dir = meeting_dir(root, id)?;
    match action {
        "archive" | "unarchive" => {
            let mut meta = meetings::read_meta(&dir);
            meta.archived = action == "archive";
            meetings::write_meta(&dir, &meta).map_err(|e| io_error(e, "batch"))
        }
        "tag_add" => {
            let mut meta = meetings::read_meta(&dir);
            let mut tags = meta.tags.clone();
            tags.push(tag.to_string());
            meta.tags = meetings::normalize_tags(&tags);
            meetings::write_meta(&dir, &meta).map_err(|e| io_error(e, "batch"))
        }
        "tag_remove" => {
            let mut meta = meetings::read_meta(&dir);
            let target = meetings::normalize_tag(tag);
            meta.tags = meta.tags.iter().filter(|t| **t != target).cloned().collect();
            meetings::write_meta(&dir, &meta).map_err(|e| io_error(e, "batch"))
        }
        "delete_audio" => {
            // 与桌面 batch 同一条路径：录音移到回收站，转写与纪要留下。直接 remove_file 会
            // 让"批量只删录音"变成不可恢复，和界面上说的"可从废纸篓恢复"对不上。
            let trash = root.join(TRASH_DIR).join(id);
            std::fs::create_dir_all(&trash).map_err(|e| io_error(e, "batch"))?;
            for file in meetings::audio_files(&dir) {
                let name = file.file_name().map(|n| n.to_owned()).unwrap_or_default();
                std::fs::rename(&file, trash.join(&name)).map_err(|e| io_error(e, "batch"))?;
            }
            Ok(())
        }
        "delete" => {
            // 与单条删除同一语义：先进回收站，用户能反悔
            let trash = root.join(TRASH_DIR).join(id);
            if trash.exists() {
                return Err(EngineError::InvalidInput {
                    detail: format!("回收站里已有同名会议：{id}"),
                });
            }
            std::fs::create_dir_all(root.join(TRASH_DIR)).map_err(|e| io_error(e, "batch"))?;
            std::fs::rename(&dir, &trash).map_err(|e| io_error(e, "batch"))
        }
        other => Err(EngineError::InvalidInput {
            detail: format!("不认识的批量操作：{other}"),
        }),
    }
}

/// 把引擎的搜索结果 JSON 转成 typed Record；字段缺了就跳过那一条，不让整个搜索失败。
/// 界面语言的统一解析：认 "zh"/"en"/"zh-CN" 这类写法，认不出按中文
/// （界面语言只有两种，缺省必须落在"能看"的那边）。
fn lang_of(code: &str) -> engine_i18n::Lang {
    engine_i18n::Lang::parse(code).unwrap_or(engine_i18n::Lang::Zh)
}

fn parse_search(query: &str, value: &serde_json::Value) -> SearchResults {
    let expanded: Vec<String> = value
        .get("expanded")
        .and_then(|v| v.as_array())
        .map(|a| a.iter().filter_map(|v| v.as_str().map(String::from)).collect())
        .unwrap_or_default();
    let mut hits = Vec::new();
    for meeting in value
        .get("results")
        .and_then(|v| v.as_array())
        .map(|a| a.as_slice())
        .unwrap_or(&[])
    {
        let id = meeting.get("id").and_then(|v| v.as_str()).unwrap_or_default();
        let title = meeting.get("title").and_then(|v| v.as_str()).unwrap_or_default();
        let date = meeting.get("date").and_then(|v| v.as_str()).unwrap_or_default();
        let in_minutes = meeting.get("minutes_hit").and_then(|v| v.as_bool()).unwrap_or(false);
        let rows = meeting
            .get("hits")
            .and_then(|v| v.as_array())
            .map(|a| a.as_slice())
            .unwrap_or(&[]);
        if rows.is_empty() {
            if in_minutes {
                hits.push(SearchHit {
                    meeting_id: id.to_string(),
                    title: title.to_string(),
                    date: date.to_string(),
                    in_minutes: true,
                    snippet: String::new(),
                    start_ms: 0,
                    speaker: String::new(),
                });
            }
            continue;
        }
        for row in rows {
            hits.push(SearchHit {
                meeting_id: id.to_string(),
                title: title.to_string(),
                date: date.to_string(),
                in_minutes,
                snippet: row.get("match").and_then(|v| v.as_str()).unwrap_or_default().to_string(),
                start_ms: row.get("ms").and_then(|v| v.as_u64()).unwrap_or(0),
                speaker: row.get("speaker").and_then(|v| v.as_str()).unwrap_or_default().to_string(),
            });
        }
    }
    SearchResults { query: query.to_string(), expanded, hits }
}

/// 把引擎的存储统计 JSON 转成 typed Record。
fn parse_storage(value: &serde_json::Value) -> StorageStats {
    let num = |key: &str| value.get(key).and_then(|v| v.as_u64()).unwrap_or(0);
    let largest = value
        .get("largest")
        .and_then(|v| v.as_array())
        .map(|a| a.as_slice())
        .unwrap_or(&[])
        .iter()
        .map(|row| MeetingUsage {
            id: row.get("id").and_then(|v| v.as_str()).unwrap_or_default().to_string(),
            title: row.get("title").and_then(|v| v.as_str()).unwrap_or_default().to_string(),
            date: row.get("date").and_then(|v| v.as_str()).unwrap_or_default().to_string(),
            bytes: row.get("bytes").and_then(|v| v.as_u64()).unwrap_or(0),
            audio_bytes: row.get("audio_bytes").and_then(|v| v.as_u64()).unwrap_or(0),
            archived: row.get("archived").and_then(|v| v.as_bool()).unwrap_or(false),
        })
        .collect();
    StorageStats {
        count: num("count").min(u32::MAX as u64) as u32,
        total_bytes: num("meetings_bytes"),
        audio_bytes: num("audio_bytes"),
        leftover_bytes: num("leftover_bytes"),
        largest,
    }
}

/// 一位说话人：编号（按首次发言顺序，0 起）、当前显示名、建议的名字（AI 猜的）。
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct SpeakerInfo {
    pub index: u32,
    pub name: String,
    /// AI 猜出来的名字；没有建议时为空串
    pub suggested: String,
    /// 支持判断的原话
    pub evidence: String,
}

/// 猜名字要发给模型的两段文本：system 与 user。
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct SpeakerPrompt {
    pub system: String,
    pub prompt: String,
    /// 转写里有几位说话人；解析结果时用来过滤越界编号
    pub speaker_count: u32,
}

/// 术语表里的一条：标准写法 + 可能的识别错写。
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct GlossaryTerm {
    pub term: String,
    pub variants: Vec<String>,
}

/// 纪要的一个历史版本（列表用，只带预览）。
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct MinutesVersionInfo {
    pub id: String,
    /// 保存时间（毫秒时间戳）
    pub saved_at: u64,
    /// 前 200 字，列表里当摘要用
    pub preview: String,
}
/// 引擎实例：持有注入的目录，所有用例函数都是它的方法。
#[derive(uniffi::Object)]
pub struct TingoEngine {
    paths: EnginePaths,
}

#[uniffi::export]
impl TingoEngine {
    /// 以 Android 沙箱根目录构造。会议、配置、缓存三个子目录由引擎按约定推导。
    #[uniffi::constructor]
    pub fn new(data_root: String) -> Arc<Self> {
        let paths = EnginePaths::android(&PathBuf::from(data_root));
        Arc::new(Self { paths })
    }

    /// 会议库根目录的绝对路径，供界面展示与排障。
    pub fn meetings_root(&self) -> String {
        self.paths.meetings_root.display().to_string()
    }

    /// 扫描会议库：按时间倒序返回。根目录不存在时返回空列表而不是报错。
    pub fn scan_library(&self) -> Result<Vec<MeetingSummary>, EngineError> {
        let root = self.paths.meetings_root.clone();
        guard("scan_library", move || Ok(meetings::list(&root).into_iter().map(to_summary).collect()))
    }

    /// 读取单个会议。`id` 必须是会议库根目录下的单层目录名。
    /// 读一场会议的全部内容。
    ///
    /// `language` 决定没改过名的说话人怎么显示（"说话人1" / "Speaker 1"）——与 [Self::search]
    /// 同一套规则，否则搜索页写"Speaker 1"、详情页写"说话人1"，看起来像两份数据。
    pub fn read_meeting(&self, id: String, language: String) -> Result<MeetingDetail, EngineError> {
        let dir = meeting_dir(&self.paths.meetings_root, &id)?;
        guard("read_meeting", move || {
            read_meeting_in(&dir, lang_of(&language))
        })
    }

    /// 解析纪要 Markdown 为章节。章节解析只在引擎侧实现一次，界面拿到的是结构化结果。
    pub fn parse_minutes(&self, text: String) -> Result<MinutesDoc, EngineError> {
        guard("parse_minutes", move || Ok(to_minutes(engine_minutes::parse(&text))))
    }

    /// 归档 / 取消归档。只改 meta.json 的 `archived` 字段，其它字段原样保留。
    pub fn set_archived(&self, id: String, archived: bool) -> Result<(), EngineError> {
        let dir = meeting_dir(&self.paths.meetings_root, &id)?;
        guard("set_archived", move || {
            let mut meta = meetings::read_meta(&dir);
            if meta.archived == archived {
                return Ok(());
            }
            meta.archived = archived;
            meetings::write_meta(&dir, &meta).map_err(|e| io_error(e, "set_archived"))
        })
    }

    /// 删除整场会议（含录音、转写、纪要），返回释放的字节数。
    ///
    /// 直接删而不是进废纸篓：Android 沙箱里没有系统废纸篓，界面必须先确认。
    pub fn delete_meeting(&self, id: String) -> Result<u64, EngineError> {
        let dir = meeting_dir(&self.paths.meetings_root, &id)?;
        guard("delete_meeting", move || {
            let freed = meetings::size_of(&dir);
            std::fs::remove_dir_all(&dir).map_err(|e| io_error(e, "delete_meeting"))?;
            Ok(freed)
        })
    }


    /// 改会议标题。空标题交给引擎归一化（回退到目录名推断的标题）。
    pub fn rename_meeting(&self, id: String, title: String) -> Result<(), EngineError> {
        let dir = meeting_dir(&self.paths.meetings_root, &id)?;
        guard("rename_meeting", move || {
            let mut meta = meetings::read_meta(&dir);
            meta.title = meetings::normalize_title(Some(&title));
            meetings::write_meta(&dir, &meta).map_err(|e| io_error(e, "rename_meeting"))
        })
    }

    /// 设置标签（整体替换）。返回归一化后的标签，界面用它刷新而不必重扫。
    pub fn set_tags(&self, id: String, tags: Vec<String>) -> Result<Vec<String>, EngineError> {
        let dir = meeting_dir(&self.paths.meetings_root, &id)?;
        guard("set_tags", move || {
            let mut meta = meetings::read_meta(&dir);
            meta.tags = meetings::normalize_tags(&tags);
            meetings::write_meta(&dir, &meta).map_err(|e| io_error(e, "set_tags"))?;
            Ok(meetings::read_meta(&dir).tags)
        })
    }

    /// 批量操作：`archive` / `unarchive` / `tag_add` / `tag_remove` / `delete_audio` / `delete`。
    ///
    /// 单个会议失败不中断其余会议——一批里有一场目录损坏，不该让另外九场白干。
    pub fn batch(&self, ids: Vec<String>, action: String, tag: String) -> Result<Vec<BatchOutcome>, EngineError> {
        let root = self.paths.meetings_root.clone();
        guard("batch", move || {
            // 动作名先整体校验：拼错了要一眼看出来，而不是伪装成"每场会议都坏了"
            if !BATCH_ACTIONS.contains(&action.as_str()) {
                return Err(EngineError::InvalidInput {
                    detail: format!("不认识的批量操作：{action}"),
                });
            }
            let mut out = Vec::with_capacity(ids.len());
            for id in ids {
                let result = apply_batch(&root, &id, &action, &tag);
                out.push(match result {
                    Ok(()) => BatchOutcome { id, ok: true, error: String::new() },
                    // 只报类别，不把 Rust 的 Debug 文本漏给宿主
                    Err(e) => BatchOutcome { id, ok: false, error: e.to_string() },
                });
            }
            Ok(out)
        })
    }

    /// 跨会议搜索：纪要正文 + 转写正文，含桌面版那套"术语扩展 + 差一个字"的模糊匹配。
    /// 跨会议搜索。
    ///
    /// `language` 是界面语言（"zh" / "en"）：没改过名的说话人在结果里写"说话人1"还是
    /// "Speaker 1" 由它决定。之前这里固定按中文渲染，英文界面搜出来满屏中文标签。
    pub fn search(&self, query: String, language: String) -> Result<SearchResults, EngineError> {
        let root = self.paths.meetings_root.clone();
        guard("search", move || {
            // 认不出就当中文：界面语言只有这两种，缺省值必须是"能看"的那个
            let lang = lang_of(&language);
            let value = tingo_engine::terms::search(&root, &query, lang);
            Ok(parse_search(&query, &value))
        })
    }

    /// 存储统计：每场体积、音频体积、可清理的残留文件。
    pub fn storage_stats(&self) -> Result<StorageStats, EngineError> {
        let root = self.paths.meetings_root.clone();
        guard("storage_stats", move || {
            Ok(parse_storage(&meetings::storage(&root, |_| false)))
        })
    }

    /// 删掉旧会议的**音频**（转写、纪要、导出都保留）。
    ///
    /// 与桌面 `/api/storage/cleanup` 的 `what: "audio"` 同一条语义：
    /// `older_than_days = 0` 表示"不限日期（全部）"，`archived_only` 只清已归档的会议。
    /// 会议本身还在库里，只是回听会失去声音——所以界面上必须先确认。
    pub fn cleanup_audio(
        &self,
        older_than_days: u32,
        archived_only: bool,
        skip_ids: Vec<String>,
    ) -> Result<AudioCleanup, EngineError> {
        if older_than_days > MAX_CLEANUP_DAYS {
            return Err(EngineError::InvalidInput {
                detail: format!("天数要是 0–{MAX_CLEANUP_DAYS} 的整数"),
            });
        }
        let root = self.paths.meetings_root.clone();
        guard("cleanup_audio", move || {
            let mut freed = 0u64;
            let mut meetings_touched = 0u32;
            let mut failed = 0u32;
            // 日期按目录名里的会议日算（与桌面一致）：用文件修改时间会把"补传的老会议"当成新的
            let cutoff = chrono::Local::now().date_naive() - chrono::Days::new(older_than_days as u64);
            for summary in meetings::list(&root) {
                // 有活正在跑（转写/纪要）的会议跳过：桌面用 meeting_busy 挡的是同一件事。
                // 删掉正在被读的录音，那一场转写会中途炸掉。
                if skip_ids.contains(&summary.id) {
                    continue;
                }
                let dir = root.join(&summary.id);
                let old = older_than_days == 0 || meetings::meeting_date(&dir) < cutoff;
                if !old || (archived_only && !summary.archived) {
                    continue;
                }
                let mut touched = false;
                for file in meetings::audio_files(&dir) {
                    let bytes = meetings::size_of(&file);
                    match std::fs::remove_file(&file) {
                        Ok(()) => {
                            freed += bytes;
                            touched = true;
                        }
                        Err(_) => failed += 1,
                    }
                }
                if touched {
                    meetings_touched += 1;
                }
            }
            Ok(AudioCleanup {
                freed_bytes: freed,
                meetings: meetings_touched,
                failed,
            })
        })
    }

    /// 只删这场会议的录音：移到回收站（可恢复），转写与纪要留在原位。返回腾出的字节数。
    ///
    /// 与桌面 `server.rs::delete_audio` 同一语义：桌面界面的确认框写的是"录音会移到废纸篓"，
    /// 所以这里不能直接 `remove_file`，否则用户点错了就再也拿不回来。
    ///
    /// @param skip_ids 正在转写/写纪要的会议 id：删掉正在被读的录音会让那一场中途炸掉
    pub fn delete_audio(&self, id: String, skip_ids: Vec<String>) -> Result<u64, EngineError> {
        let root = self.paths.meetings_root.clone();
        guard("delete_audio", move || {
            let dir = meeting_dir(&root, &id)?;
            if skip_ids.contains(&id) {
                return Err(EngineError::InvalidInput {
                    detail: "这场会议还在处理中，等它跑完再删录音".to_string(),
                });
            }
            let files = meetings::audio_files(&dir);
            if files.is_empty() {
                return Err(EngineError::Missing {
                    detail: "这场会议没有录音文件".to_string(),
                });
            }
            let trash = root.join(TRASH_DIR).join(&id);
            std::fs::create_dir_all(&trash).map_err(|e| io_error(e, "delete_audio"))?;
            let mut freed = 0u64;
            for file in files {
                let bytes = meetings::size_of(&file);
                let name = file.file_name().map(|n| n.to_owned()).unwrap_or_default();
                let target = trash.join(&name);
                std::fs::rename(&file, &target).map_err(|e| io_error(e, "delete_audio"))?;
                freed += bytes;
            }
            Ok(freed)
        })
    }

    /// 删除到回收站（目录整体改名，瞬时且可恢复），返回释放的字节数。
    pub fn delete_to_trash(&self, id: String) -> Result<u64, EngineError> {
        let root = self.paths.meetings_root.clone();
        guard("delete_to_trash", move || {
            let dir = meeting_dir(&root, &id)?;
            let bytes = meetings::size_of(&dir);
            let trash = root.join(TRASH_DIR).join(&id);
            if trash.exists() {
                return Err(EngineError::InvalidInput {
                    detail: format!("回收站里已有同名会议：{id}"),
                });
            }
            std::fs::create_dir_all(root.join(TRASH_DIR))
                .map_err(|e| io_error(e, "delete_to_trash"))?;
            std::fs::rename(&dir, &trash).map_err(|e| io_error(e, "delete_to_trash"))?;
            Ok(bytes)
        })
    }

    /// 从回收站恢复。目标已存在时拒绝，绝不覆盖用户数据。
    pub fn restore_from_trash(&self, id: String) -> Result<(), EngineError> {
        let root = self.paths.meetings_root.clone();
        guard("restore_from_trash", move || {
            validate_id(&id)?;
            let source = root.join(TRASH_DIR).join(&id);
            if !source.is_dir() {
                return Err(EngineError::Missing {
                    detail: format!("回收站里没有 {id}"),
                });
            }
            let target = root.join(&id);
            if target.exists() {
                return Err(EngineError::InvalidInput {
                    detail: format!("会议库里已有同 id 的目录：{id}"),
                });
            }
            std::fs::rename(&source, &target).map_err(|e| io_error(e, "restore_from_trash"))
        })
    }

    /// 回收站内容，按 id 排序。
    pub fn list_trash(&self) -> Result<Vec<TrashItem>, EngineError> {
        let trash = self.paths.meetings_root.join(TRASH_DIR);
        guard("list_trash", move || {
            let Ok(rd) = std::fs::read_dir(&trash) else {
                return Ok(Vec::new());
            };
            let mut items: Vec<TrashItem> = rd
                .flatten()
                .map(|e| e.path())
                .filter(|p| p.is_dir())
                .filter_map(|p| {
                    p.file_name()
                        .and_then(|n| n.to_str())
                        .map(|n| TrashItem { id: n.to_string(), bytes: meetings::size_of(&p) })
                })
                .collect();
            items.sort_by(|a, b| a.id.cmp(&b.id));
            Ok(items)
        })
    }




    /// 当前配置的热词（专有名词、人名、项目代号）。
    pub fn hotwords(&self) -> Result<Vec<String>, EngineError> {
        let path = self.paths.config_dir.join(CONFIG_FILE);
        // 每次都读盘：设置页刚写完，缓存一份只会读到旧值；手改过的配置也要过一遍归一化
        guard("hotwords", move || Ok(normalize_hotwords(tingo_engine::config::load(&path).hotwords)))
    }

    /// 保存热词：去空白、去重，最多 200 条。
    ///
    /// 桌面没有硬上限（设置页只做去空白）；手机上提示词更短，设个上限防止整段被热词占满。
    pub fn set_hotwords(&self, words: Vec<String>) -> Result<Vec<String>, EngineError> {
        let path = self.paths.config_dir.join(CONFIG_FILE);
        let words = normalize_hotwords(words);
        guard("set_hotwords", move || {
            let mut config = tingo_engine::config::load(&path);
            config.hotwords = words.clone();
            tingo_engine::config::save(&path, &config).map_err(|e| io_error(e, "set_hotwords"))?;
            Ok(words)
        })
    }

    /// 提示词里"专有名词"那一段（不带编号；没有热词时为空串）。文案由引擎产出，两个平台共用。
    pub fn minutes_glossary(&self, language: String) -> Result<String, EngineError> {
        let path = self.paths.config_dir.join(CONFIG_FILE);
        guard("minutes_glossary", move || {
            let lang =
                tingo_engine::i18n::Lang::parse(&language).unwrap_or(tingo_engine::i18n::Lang::Zh);
            // 读的时候也归一化：手改过的 config 可能塞进远超上限的词，不能让提示词被它撑爆
            let words = normalize_hotwords(tingo_engine::config::load(&path).hotwords);
            Ok(tingo_engine::summarize::glossary_sentence(&words, lang))
        })
    }


    /// 把一段已经在本机解码好的 wav 导入会议库：建会议文件夹、写 `录音.wav`，返回会议 id。
    ///
    /// 桌面端是浏览器上传原文件、引擎自己调 `afconvert` 解码；手机上解码由宿主用
    /// `MediaCodec` 完成（引擎不带平台解码器），这里只负责"落成一场会议"。
    pub fn import_audio(
        &self,
        wav_path: String,
        title: String,
        modified_ms: i64,
    ) -> Result<ImportedMeeting, EngineError> {
        let source = PathBuf::from(&wav_path);
        guard("import_audio", move || {
            let bytes = std::fs::read(&source).map_err(|e| io_error(e, "import_audio"))?;
            if bytes.is_empty() {
                return Err(EngineError::InvalidInput {
                    detail: "录音是空的".to_string(),
                });
            }
            let when = tingo_engine::meetings::local_time_from_millis(modified_ms);
            let stem = source
                .file_stem()
                .and_then(|s| s.to_str())
                .unwrap_or("会议")
                .to_string();
            let wanted = if title.trim().is_empty() { stem } else { title };
            let dir = tingo_engine::meetings::new_folder(&self.paths.meetings_root, &wanted, when);
            std::fs::create_dir_all(&dir).map_err(|e| io_error(e, "import_audio"))?;
            let id = dir
                .file_name()
                .map(|n| n.to_string_lossy().into_owned())
                .ok_or_else(|| EngineError::Internal {
                    detail: "会议文件夹没有名字".to_string(),
                })?;
            // 文件名必须是 `录音.wav`：播放与转写都按这个名字找（与录制结束时合成的一致）
            std::fs::write(dir.join("录音.wav"), bytes).map_err(|e| io_error(e, "import_audio"))?;
            Ok(ImportedMeeting {
                id,
                dir: dir.to_string_lossy().into_owned(),
            })
        })
    }


    /// 把示例会议装进会议库（已经有了就直接返回那个），返回会议 id。
    ///
    /// 素材由宿主解出来（桌面在 App 资源里，Android 在 assets 里），
    /// "怎么装进库、叫什么名字"由引擎决定，两个平台不会各写一套。
    pub fn install_demo_meeting(&self, source_dir: String, english: bool) -> Result<ImportedMeeting, EngineError> {
        let source = PathBuf::from(&source_dir);
        guard("install_demo_meeting", move || {
            let dir = tingo_engine::meetings::install_demo(&self.paths.meetings_root, &source, english)
                .map_err(|e| io_error(e, "install_demo_meeting"))?;
            let id = dir
                .file_name()
                .map(|n| n.to_string_lossy().into_owned())
                .ok_or_else(|| EngineError::Internal {
                    detail: "示例会议文件夹没有名字".to_string(),
                })?;
            Ok(ImportedMeeting {
                id,
                dir: dir.to_string_lossy().into_owned(),
            })
        })
    }

    /// 纪要的历史版本，按保存时间从新到旧。
    pub fn minutes_history(&self, id: String) -> Result<Vec<MinutesVersionInfo>, EngineError> {
        let dir = meeting_dir(&self.paths.meetings_root, &id)?;
        guard("minutes_history", move || {
            minutes_history::list(&dir)
                .map_err(|e| io_error(e, "minutes_history"))
                .map(|versions| versions.iter().map(version_info).collect())
        })
    }

    /// 某个历史版本的全文。
    pub fn minutes_version(&self, id: String, version_id: String) -> Result<String, EngineError> {
        let dir = meeting_dir(&self.paths.meetings_root, &id)?;
        guard("minutes_version", move || {
            minutes_history::read(&dir, &version_id)
                .map(|v| v.text)
                .map_err(|e| io_error(e, "minutes_version"))
        })
    }

    /// 用某个历史版本覆盖当前纪要（当前版本会先被归档，所以"恢复"也能再退回去）。
    pub fn restore_minutes_version(
        &self,
        id: String,
        version_id: String,
    ) -> Result<(), EngineError> {
        let dir = meeting_dir(&self.paths.meetings_root, &id)?;
        guard("restore_minutes_version", move || {
            let version = minutes_history::read(&dir, &version_id)
                .map_err(|e| io_error(e, "restore_minutes_version"))?;
            minutes_history::save(&dir, &version.text).map_err(|e| io_error(e, "restore_minutes_version"))
        })
    }

    /// 删除一个历史版本。
    pub fn delete_minutes_version(&self, id: String, version_id: String) -> Result<(), EngineError> {
        let dir = meeting_dir(&self.paths.meetings_root, &id)?;
        guard("delete_minutes_version", move || {
            minutes_history::delete(&dir, &version_id).map_err(|e| io_error(e, "delete_minutes_version"))
        })
    }

    /// 手工编辑纪要：与模型生成走同一条保存路径，旧版本自动进历史。
    pub fn save_minutes(&self, id: String, text: String) -> Result<(), EngineError> {
        let dir = meeting_dir(&self.paths.meetings_root, &id)?;
        guard("save_minutes", move || {
            minutes_history::save(&dir, &meetings::minutes_with_trailing_newline(&text))
                .map_err(|e| io_error(e, "save_minutes"))
        })
    }


    /// 把纪要里的鱼骨图导出成 `.excalidraw`（可拖进 excalidraw.com 或 Obsidian 继续改），
    /// 返回写出的文件路径。画板文字与文件名跟纪要语言走，与桌面同一套实现。
    pub fn export_excalidraw(&self, id: String) -> Result<Vec<String>, EngineError> {
        let dir = meeting_dir(&self.paths.meetings_root, &id)?;
        guard("export_excalidraw", move || {
            let minutes = std::fs::read_to_string(dir.join(meetings::MINUTES)).map_err(|e| {
                io_error(e, "export_excalidraw")
            })?;
            let lang = tingo_engine::i18n::doc_lang(&minutes);
            let transcript = engine_transcript::Transcript::load(&dir.join("transcript.json"))
                .ok()
                .map(|t| t.localized(lang));
            let turns = transcript.as_ref().map(|t| t.turns()).unwrap_or_default();
            let duration = transcript.as_ref().map(|t| t.duration_ms).unwrap_or(u64::MAX);
            let specs: Vec<_> = engine_fishbone::blocks(&minutes)
                .into_iter()
                .filter_map(|(_, spec)| spec)
                .collect();
            if specs.is_empty() {
                return Err(EngineError::Missing {
                    detail: "这份纪要里没有鱼骨图".to_string(),
                });
            }
            let mut paths = Vec::new();
            for (index, spec) in specs.iter().enumerate() {
                let grounds: Vec<_> = spec
                    .branches
                    .iter()
                    .map(|branch| engine_fishbone::ground(branch, &turns, duration))
                    .collect();
                let path = dir.join(engine_fishbone::excalidraw_name(index, lang));
                let text = serde_json::to_string_pretty(
                    &engine_fishbone::excalidraw(spec, &grounds, lang),
                )
                .map_err(|e| EngineError::Internal { detail: e.to_string() })?;
                std::fs::write(&path, text).map_err(|e| io_error(e, "export_excalidraw"))?;
                paths.push(path.to_string_lossy().into_owned());
            }
            Ok(paths)
        })
    }

    /// 导出 SRT 字幕到会议目录，返回文件绝对路径。命名规则与桌面一致（纪要标题优先）。
    pub fn export_srt(&self, id: String, language: String) -> Result<String, EngineError> {
        let dir = meeting_dir(&self.paths.meetings_root, &id)?;
        guard("export_srt", move || {
            let tr = load_transcript(&dir)?;
            let lang = lang_of(&language);
            let stem = meetings::export_stem(&dir, &id);
            let out = dir.join(format!("{stem}.srt"));
            std::fs::write(&out, tr.to_srt(lang)).map_err(|e| io_error(e, "export_srt"))?;
            Ok(out.to_string_lossy().into_owned())
        })
    }

    /// 当前会议的说话人列表：编号、显示名、AI 建议（没猜过时为空）。
    pub fn speakers(&self, id: String, language: String) -> Result<Vec<SpeakerInfo>, EngineError> {
        let dir = meeting_dir(&self.paths.meetings_root, &id)?;
        guard("speakers", move || {
            let tr = load_transcript(&dir)?;
            Ok(speaker_infos(&tr.localized(lang_of(&language))))
        })
    }

    /// 批量设置说话人名字：`names` 与 [Self::speakers] 的顺序一一对应，空字符串表示"不改"。
    pub fn set_speaker_names(&self, id: String, names: Vec<String>) -> Result<(), EngineError> {
        let dir = meeting_dir(&self.paths.meetings_root, &id)?;
        guard("set_speaker_names", move || {
            let mut tr = load_transcript(&dir)?;
            tr.speaker_names = tr
                .speakers_in_order()
                .into_iter()
                .zip(names)
                .filter(|(_, name)| !name.trim().is_empty())
                .map(|(speaker, name)| (speaker, name.trim().to_string()))
                .collect();
            // 与桌面 put_speakers 一致：直接覆盖 speaker_names，不做增量合并
            save_transcript(&tr, &dir)
        })
    }

    /// 猜名字用的提示词。模型调用由宿主完成（Android 走用户配置的 API），
    /// 引擎只负责"该怎么问"与"怎么读答案"。
    pub fn speaker_prompt(&self, id: String, language: String) -> Result<SpeakerPrompt, EngineError> {
        let dir = meeting_dir(&self.paths.meetings_root, &id)?;
        guard("speaker_prompt", move || {
            let tr = load_transcript(&dir)?;
            let lang = lang_of(&language);
            let (system, prompt) = tingo_engine::speakers::prompt(&tr, lang);
            Ok(SpeakerPrompt {
                system: system.to_string(),
                prompt,
                speaker_count: tr.speakers_in_order().len() as u32,
            })
        })
    }

    /// 解析模型返回的猜名字结果；不是合法 JSON 时返回 `Malformed`。
    pub fn parse_speaker_suggestions(
        &self,
        id: String,
        text: String,
    ) -> Result<Vec<SpeakerInfo>, EngineError> {
        let dir = meeting_dir(&self.paths.meetings_root, &id)?;
        guard("parse_speaker_suggestions", move || {
            let tr = load_transcript(&dir)?;
            let order = tr.speakers_in_order();
            let suggestions = tingo_engine::speakers::parse(&text, order.len()).ok_or_else(|| {
                EngineError::Malformed {
                    detail: "模型输出里没有可解析的 suggestions".to_string(),
                }
            })?;
            let mut infos = speaker_infos(&tr);
            for suggestion in suggestions {
                let Some(info) = infos.iter_mut().find(|i| i.index as usize == suggestion.index) else {
                    continue;
                };
                info.suggested = suggestion.name;
                info.evidence = suggestion.evidence;
            }
            Ok(infos)
        })
    }

    /// 跨会议术语表：从每场纪要的 ```terms 代码块里收集，按词条本身排序。
    pub fn glossary(&self) -> Result<Vec<GlossaryTerm>, EngineError> {
        let root = self.paths.meetings_root.clone();
        guard("glossary", move || {
            let mut merged: Vec<GlossaryTerm> = Vec::new();
            for summary in meetings::list(&root) {
                // 与 terms::search 用同一个文件名常量：两处各写一份"纪要.md"迟早漂移
                let path = root.join(&summary.id).join(meetings::MINUTES);
                let Ok(text) = std::fs::read_to_string(&path) else {
                    continue;
                };
                for term in tingo_engine::terms::parse(&text) {
                    // 按归一化形式合并：`历史工单` 与 `歷史工單` 是同一个词
                    let key = tingo_engine::terms::norm(&term.term);
                    match merged.iter_mut().find(|t| tingo_engine::terms::norm(&t.term) == key) {
                        Some(existing) => {
                            for variant in term.variants {
                                if !existing.variants.contains(&variant) {
                                    existing.variants.push(variant);
                                }
                            }
                        }
                        None => merged.push(GlossaryTerm {
                            term: term.term,
                            variants: term.variants,
                        }),
                    }
                }
            }
            merged.sort_by(|a, b| a.term.cmp(&b.term));
            Ok(merged)
        })
    }

    /// 清理残留文件（已合成录音后遗留的 system.wav / mic.wav、*.part、*.tmp），返回释放的字节数。
    ///
    /// 与回收站是两件事：这里的文件还在正常会议目录里，只是没人再用了。
    pub fn cleanup_leftovers(&self) -> Result<u64, EngineError> {
        let root = self.paths.meetings_root.clone();
        guard("cleanup_leftovers", move || {
            let mut freed = 0u64;
            let mut failed = false;
            for summary in meetings::list(&root) {
                let dir = root.join(&summary.id);
                for file in meetings::leftover_files(&dir) {
                    let bytes = meetings::size_of(&file);
                    match std::fs::remove_file(&file) {
                        Ok(()) => freed += bytes,
                        // 单个文件删不掉（被占用）不算整体失败，但要如实说明有没清掉的
                        Err(_) => failed = true,
                    }
                }
            }
            if failed && freed == 0 {
                return Err(EngineError::Malformed {
                    detail: "残留文件都没能删除（可能正被占用）".to_string(),
                });
            }
            Ok(freed)
        })
    }

    /// 彻底清除回收站里的一场会议；`id` 为空表示清空整个回收站。
    pub fn purge_trash(&self, id: String) -> Result<u64, EngineError> {
        let trash = self.paths.meetings_root.join(TRASH_DIR);
        guard("purge_trash", move || {
            if id.is_empty() {
                let freed = meetings::size_of(&trash);
                match std::fs::remove_dir_all(&trash) {
                    Ok(()) => return Ok(freed),
                    Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(0),
                    Err(e) => return Err(io_error(e, "purge_trash")),
                }
            }
            validate_id(&id)?;
            let dir = trash.join(&id);
            if !dir.is_dir() {
                return Err(EngineError::Missing { detail: format!("回收站里没有 {id}") });
            }
            let freed = meetings::size_of(&dir);
            std::fs::remove_dir_all(&dir).map_err(|e| io_error(e, "purge_trash"))?;
            Ok(freed)
        })
    }

    /// 解析纪要里的鱼骨图代码块。没有代码块返回 `None`；内容非法返回 `Malformed`。
    pub fn parse_fishbone(&self, minutes_text: String) -> Result<Option<Fishbone>, EngineError> {
        guard("parse_fishbone", move || {
            match engine_fishbone::blocks(&minutes_text).into_iter().next() {
                None => Ok(None),
                Some((_, None)) => Err(EngineError::Malformed {
                    detail: "fishbone block is not valid JSON".to_string(),
                }),
                Some((_, Some(spec))) => Ok(Some(to_fishbone(spec))),
            }
        })
    }
}

/// 引擎版本。取被链接的 `tingo-engine` crate 版本，避免两处各写一份。
#[uniffi::export]
pub fn engine_version() -> String {
    tingo_engine::VERSION.to_string()
}

/// 当前构建实提供的能力。Android 构建没有 ASR，但核心的会话存储与
/// 转写/纪要/鱼骨图解析、说话人命名、术语抽取都在。
#[uniffi::export]
pub fn capabilities() -> Capabilities {
    Capabilities {
        meeting_storage: true,
        transcript_parse: true,
        minutes_parse: true,
        fishbone_parse: true,
        speaker_naming: true,
        terms_extract: true,
    }
}

/// 导出函数的统一入口：panic 转成 `EngineError::Internal`，不穿透到宿主进程。
fn guard<T>(what: &str, f: impl FnOnce() -> Result<T, EngineError>) -> Result<T, EngineError> {
    match panic::catch_unwind(AssertUnwindSafe(f)) {
        Ok(result) => result,
        Err(_) => Err(EngineError::Internal {
            detail: format!("{what} panicked"),
        }),
    }
}

/// 会议 id 只能是单层目录名：挡掉 `../` 这类逃逸，避免宿主把 ID 当成任意路径。
/// 只校验 id 本身（单层目录名），不要求目录存在——回收站里的会议不在会议库根下。
fn validate_id(id: &str) -> Result<(), EngineError> {
    if id.trim().is_empty() || id.contains('/') || id.contains('\\') || id == "." || id == ".." {
        return Err(EngineError::InvalidInput {
            detail: format!("invalid meeting id: {id}"),
        });
    }
    Ok(())
}

fn meeting_dir(root: &Path, id: &str) -> Result<PathBuf, EngineError> {
    validate_id(id)?;
    let dir = root.join(id);
    if !dir.is_dir() {
        return Err(EngineError::Missing {
            detail: format!("meeting not found: {id}"),
        });
    }
    Ok(dir)
}

/// 读文件失败按原因分类：权限不足和内容损坏对用户是两件事。
fn io_error(err: std::io::Error, what: &str) -> EngineError {
    let detail = format!("{what}: {err}");
    match err.kind() {
        std::io::ErrorKind::PermissionDenied => EngineError::PermissionDenied { detail },
        std::io::ErrorKind::NotFound => EngineError::Missing { detail },
        // 引擎自己抛的 InvalidInput（版本号不合法、路径越界一类）属于调用方用错，
        // 不是"数据坏了"：界面能据此提示"参数不对"，而不是让用户以为文件损坏
        std::io::ErrorKind::InvalidInput => EngineError::InvalidInput { detail },
        _ => EngineError::Malformed { detail },
    }
}

fn read_meeting_in(dir: &Path, lang: engine_i18n::Lang) -> Result<MeetingDetail, EngineError> {
    let summary = to_summary(meetings::summary(dir));
    let transcript = match engine_transcript::Transcript::load(&dir.join("transcript.json")) {
        Ok(tr) => Some(to_transcript(tr.localized(lang))),
        Err(err) if err.kind() == std::io::ErrorKind::NotFound => None,
        Err(err) => return Err(io_error(err, "transcript.json")),
    };
    let minutes_text = std::fs::read_to_string(dir.join("纪要.md")).ok();
    let fishbone = minutes_text
        .as_deref()
        .and_then(|md| engine_fishbone::blocks(md).into_iter().find_map(|(_, spec)| spec))
        .map(to_fishbone);
    Ok(MeetingDetail {
        summary,
        transcript,
        minutes_text,
        fishbone,
    })
}

fn to_summary(s: meetings::Summary) -> MeetingSummary {
    MeetingSummary {
        id: s.id,
        title: s.title,
        date: s.date,
        time: s.time,
        one_liner: s.summary,
        has_minutes: s.has_minutes,
        has_transcript: s.has_transcript,
        unreadable: s.unreadable,
        has_audio: s.has_audio,
        duration: s.duration,
        speakers: s.speakers,
        tags: s.tags,
        archived: s.archived,
        size_bytes: s.size_bytes,
    }
}

fn to_transcript(tr: engine_transcript::Transcript) -> Transcript {
    let aliases: Vec<SpeakerAlias> = tr
        .speaker_names
        .iter()
        .map(|(speaker, name)| SpeakerAlias {
            speaker: *speaker as u32,
            name: name.clone(),
        })
        .collect();
    // 合并规则与引擎 `Transcript::turns` 一致（同一说话人连续句子合一段，
    // 文本直接相接、结束时间取最后一句），区别是这里额外保留逐句内容供界面定位。
    let mut turns: Vec<Turn> = Vec::new();
    for s in &tr.sentences {
        let name = tr.name_of(s.speaker);
        let sentence = Sentence {
            speaker: s.speaker as u32,
            start_ms: s.start_ms,
            end_ms: s.end_ms,
            text: s.text.clone(),
        };
        match turns.last_mut() {
            Some(turn) if turn.speaker_name == name => {
                turn.text.push_str(&s.text);
                turn.end_ms = s.end_ms;
                turn.sentences.push(sentence);
            }
            _ => turns.push(Turn {
                speaker_name: name,
                start_ms: s.start_ms,
                end_ms: s.end_ms,
                text: s.text.clone(),
                sentences: vec![sentence],
            }),
        }
    }
    Transcript {
        source: tr.source,
        duration_ms: tr.duration_ms,
        aliases,
        turns,
    }
}


fn to_minutes(doc: engine_minutes::MinutesDoc) -> MinutesDoc {
    MinutesDoc {
        title: doc.title,
        sections: doc
            .sections
            .into_iter()
            .map(|s| MinutesSection {
                kind: to_section_kind(&s.kind),
                heading: s.heading,
                body: s.body,
            })
            .collect(),
    }
}

fn to_section_kind(kind: &engine_minutes::SectionKind) -> SectionKind {
    use engine_minutes::SectionKind as K;
    match kind {
        K::OneLiner => SectionKind::OneLiner,
        K::Discussion => SectionKind::Discussion,
        K::Decisions => SectionKind::Decisions,
        K::Actions => SectionKind::Actions,
        K::OpenQuestions => SectionKind::OpenQuestions,
        K::RootCause => SectionKind::RootCause,
        K::Other(_) => SectionKind::Other,
    }
}

fn to_fishbone(spec: engine_fishbone::Spec) -> Fishbone {
    Fishbone {
        problem: spec.problem,
        branches: spec
            .branches
            .into_iter()
            .map(|b| FishboneBranch {
                title: b.title,
                detail: b.detail,
                refs: b.refs,
            })
            .collect(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn engine_version_is_not_empty() {
        assert!(!engine_version().trim().is_empty());
    }

    #[test]
    fn capabilities_expose_all_six_keys() {
        let caps = capabilities();
        assert!(caps.meeting_storage);
        assert!(caps.transcript_parse);
        assert!(caps.minutes_parse);
        assert!(caps.fishbone_parse);
        assert!(caps.speaker_naming);
        assert!(caps.terms_extract);
    }

    /// 造一个最小会议目录：只有纪要，引擎也认得。
    fn meeting(root: &Path, id: &str) -> PathBuf {
        // 引擎的会议库在 data_root/meetings 下
        let dir = root.join("meetings").join(id);
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(dir.join("纪要.md"), "# 标题\n\n## 决议\n- 先修首屏\n").unwrap();
        dir
    }

    /// 每个用例一个独立根目录：测试并行跑，共用一个目录会互相踩。
    fn temp_root(name: &str) -> PathBuf {
        let root = std::env::temp_dir().join(format!("tingo-ffi-{}-{name}", std::process::id()));
        let _ = std::fs::remove_dir_all(&root);
        std::fs::create_dir_all(&root).unwrap();
        root
    }

    #[test]
    fn set_archived_flips_meta_and_keeps_other_fields() {
        let root = temp_root("archive");
        let dir = meeting(&root, "2026-10-28_1430_复盘");
        let mut meta = meetings::read_meta(&dir);
        meta.title = Some("复盘".to_string());
        meta.tags = vec!["产品".to_string()];
        meetings::write_meta(&dir, &meta).unwrap();

        let engine = TingoEngine::new(root.display().to_string());
        engine.set_archived("2026-10-28_1430_复盘".to_string(), true).unwrap();

        let after = meetings::read_meta(&dir);
        assert!(after.archived);
        assert_eq!(after.title.as_deref(), Some("复盘"));
        assert_eq!(after.tags, vec!["产品".to_string()]);

        engine.set_archived("2026-10-28_1430_复盘".to_string(), false).unwrap();
        assert!(!meetings::read_meta(&dir).archived);
        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn delete_meeting_removes_folder_and_reports_freed_bytes() {
        let root = temp_root("delete");
        let dir = meeting(&root, "2026-10-28_1430_复盘");
        let engine = TingoEngine::new(root.display().to_string());

        let freed = engine.delete_meeting("2026-10-28_1430_复盘".to_string()).unwrap();

        assert!(!dir.exists());
        assert!(freed > 0);
        assert!(engine.scan_library().unwrap().is_empty());
        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn archive_rejects_path_traversal_ids() {
        let root = temp_root("traversal");
        let engine = TingoEngine::new(root.display().to_string());
        assert!(engine.set_archived("../etc".to_string(), true).is_err());
        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn panic_becomes_internal_error() {
        let result: Result<(), EngineError> = guard("boom", || panic!("boom"));
        match result {
            Err(EngineError::Internal { detail }) => assert!(detail.contains("boom")),
            other => panic!("expected internal error, got {other:?}"),
        }
    }

    #[test]
    fn empty_root_scans_to_empty_list() {
        let dir = tempfile::tempdir().unwrap();
        let engine = TingoEngine::new(dir.path().display().to_string());
        assert!(engine.scan_library().unwrap().is_empty());
    }

    #[test]
    fn rejects_ids_that_escape_root() {
        let dir = tempfile::tempdir().unwrap();
        let engine = TingoEngine::new(dir.path().display().to_string());
        for bad in ["", "..", "../etc", "a/b"] {
            match engine.read_meeting(bad.to_string(), "zh".to_string()) {
                Err(EngineError::InvalidInput { .. }) => {}
                other => panic!("expected InvalidInput for {bad:?}, got {other:?}"),
            }
        }
    }

    /// 建一个含指定文件的会议目录，返回 (临时根, 引擎)。
    fn engine_with_meeting(id: &str, files: &[(&str, &str)]) -> (tempfile::TempDir, Arc<TingoEngine>) {
        let dir = tempfile::tempdir().unwrap();
        let meeting = dir.path().join("meetings").join(id);
        std::fs::create_dir_all(&meeting).unwrap();
        for (name, body) in files {
            std::fs::write(meeting.join(name), body).unwrap();
        }
        let engine = TingoEngine::new(dir.path().display().to_string());
        (dir, engine)
    }

    const TRANSCRIPT_ZH: &str = r#"{
        "source": "/x/录音.m4a",
        "duration_ms": 3725000,
        "speaker_names": {"1": "张伟"},
        "sentences": [
            {"speaker": 0, "start_ms": 0, "end_ms": 1500, "text": "我们先过一下进度。"},
            {"speaker": 0, "start_ms": 1500, "end_ms": 3200, "text": "上周的工单清完了。"},
            {"speaker": 1, "start_ms": 3200, "end_ms": 5000, "text": "接口联调还有两天。"}
        ]
    }"#;

    const MINUTES_ZH: &str = "# 上线协调会\n\n## 一句话总结\n定了下周一上线。\n\n## 决议\n- 周一发版\n\n## 原因分析\n```fishbone\n{\"problem\": \"为什么延期\", \"branches\": [{\"title\": \"联调慢\", \"detail\": \"接口变动\", \"refs\": [\"00:05\"]}]}\n```\n";

    #[test]
    fn transcript_roundtrip_keeps_chinese_alias_and_ms() {
        let (_dir, engine) = engine_with_meeting("2026-03-05_1000_上线协调会", &[("transcript.json", TRANSCRIPT_ZH)]);
        let detail = engine.read_meeting("2026-03-05_1000_上线协调会".to_string(), "zh".to_string()).unwrap();
        let tr = detail.transcript.expect("transcript parsed");

        assert_eq!(tr.duration_ms, 3_725_000);
        // 别名优先：说话人 1 有别名，说话人 0 没有
        assert_eq!(tr.aliases.len(), 1);
        assert_eq!(tr.aliases[0].speaker, 1);
        assert_eq!(tr.aliases[0].name, "张伟");

        // 同一说话人的连续两句合并成一段，逐句内容保留
        assert_eq!(tr.turns.len(), 2);
        assert_eq!(tr.turns[0].speaker_name, "说话人1");
        assert_eq!(tr.turns[0].text, "我们先过一下进度。上周的工单清完了。");
        assert_eq!(tr.turns[0].end_ms, 3_200);
        assert_eq!(tr.turns[0].sentences.len(), 2);
        assert_eq!(tr.turns[1].speaker_name, "张伟");
        assert_eq!(tr.turns[1].sentences[0].start_ms, 3_200);
    }

    #[test]
    fn minutes_text_and_fishbone_come_from_disk() {
        let (_dir, engine) = engine_with_meeting(
            "2026-03-05_1000_上线协调会",
            &[("transcript.json", TRANSCRIPT_ZH), ("纪要.md", MINUTES_ZH)],
        );
        let detail = engine.read_meeting("2026-03-05_1000_上线协调会".to_string(), "zh".to_string()).unwrap();
        assert_eq!(detail.minutes_text.as_deref(), Some(MINUTES_ZH));

        let fishbone = detail.fishbone.expect("fishbone parsed");
        assert_eq!(fishbone.problem, "为什么延期");
        assert_eq!(fishbone.branches[0].title, "联调慢");
        assert_eq!(fishbone.branches[0].refs, vec!["00:05".to_string()]);
    }

    #[test]
    fn missing_minutes_is_not_an_error() {
        let (_dir, engine) = engine_with_meeting("2026-03-05_1000_无纪要", &[("transcript.json", TRANSCRIPT_ZH)]);
        let detail = engine.read_meeting("2026-03-05_1000_无纪要".to_string(), "zh".to_string()).unwrap();
        assert!(detail.minutes_text.is_none());
        assert!(detail.fishbone.is_none());
    }

    #[test]
    fn corrupted_transcript_is_malformed() {
        let (_dir, engine) = engine_with_meeting("2026-03-05_1000_损坏", &[("transcript.json", "{not json")]);
        match engine.read_meeting("2026-03-05_1000_损坏".to_string(), "zh".to_string()) {
            Err(EngineError::Malformed { .. }) => {}
            other => panic!("expected Malformed, got {other:?}"),
        }
    }

    /// 规格 meeting-library-read：坏目录仍进列表，条目带 unreadable 标记
    #[test]
    fn scan_library_flags_corrupted_transcript() {
        let (_dir, engine) = engine_with_meeting("2026-03-05_1000_损坏", &[("transcript.json", "{not json")]);
        let items = engine.scan_library().unwrap();
        assert_eq!(items.len(), 1);
        assert!(items[0].unreadable);
        assert!(!items[0].has_transcript);
    }

    #[test]
    fn invalid_fishbone_block_is_malformed() {
        let (_dir, engine) = engine_with_meeting("2026-03-05_1000_坏鱼骨", &[("纪要.md", "## 原因分析\n```fishbone\nnope\n```\n")]);
        match engine.parse_fishbone("## 原因分析\n```fishbone\nnope\n```\n".to_string()) {
            Err(EngineError::Malformed { .. }) => {}
            other => panic!("expected Malformed, got {other:?}"),
        }
    }

    #[test]
    fn minutes_are_parsed_into_typed_sections() {
        let (_dir, engine) = engine_with_meeting("2026-03-05_1000_纪要", &[("纪要.md", MINUTES_ZH)]);
        let doc = engine.parse_minutes(MINUTES_ZH.to_string()).unwrap();
        assert_eq!(doc.title, "上线协调会");
        let kinds: Vec<SectionKind> = doc.sections.iter().map(|s| s.kind.clone()).collect();
        assert_eq!(
            kinds,
            vec![SectionKind::OneLiner, SectionKind::Decisions, SectionKind::RootCause]
        );
        assert_eq!(doc.sections[0].body.trim(), "定了下周一上线。");
        // fishbone 代码块原样留在「原因分析」正文里，由 parse_fishbone 负责解析
        assert!(doc.sections[2].body.contains("```fishbone"));
    }

    #[test]
    fn unknown_section_is_preserved_in_order() {
        let md = "# 周会\n\n## 一句话总结\nok\n\n## 我手加的章节\n内容\n";
        let (_dir, engine) = engine_with_meeting("2026-03-05_1000_未知", &[("纪要.md", md)]);
        let doc = engine.parse_minutes(md.to_string()).unwrap();
        assert_eq!(doc.sections.len(), 2);
        assert_eq!(doc.sections[1].kind, SectionKind::Other);
        assert_eq!(doc.sections[1].heading, "我手加的章节");
    }

    #[test]
    fn empty_minutes_yield_empty_doc() {
        let (_dir, engine) = engine_with_meeting("2026-03-05_1000_空", &[]);
        let doc = engine.parse_minutes(String::new()).unwrap();
        assert_eq!(doc.title, "");
        assert!(doc.sections.is_empty());
    }

    // ---- 会议管理与搜索（与桌面端对齐的那一批） ----

    #[test]
    fn rename_writes_title_and_keeps_tags() {
        let root = temp_root("rename");
        let dir = meeting(&root, "2026-10-28_1430_复盘");
        let mut meta = meetings::read_meta(&dir);
        meta.tags = vec!["产品".to_string()];
        meetings::write_meta(&dir, &meta).unwrap();

        let engine = TingoEngine::new(root.display().to_string());
        engine.rename_meeting("2026-10-28_1430_复盘".to_string(), "  Q4 复盘  ".to_string()).unwrap();

        let after = meetings::read_meta(&dir);
        assert_eq!(after.title.as_deref(), Some("Q4 复盘"));
        assert_eq!(after.tags, vec!["产品".to_string()]);
        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn empty_title_falls_back_to_minutes_heading_then_folder() {
        let root = temp_root("rename-empty");
        let dir = meeting(&root, "2026-10-28_1430_复盘");
        let engine = TingoEngine::new(root.display().to_string());
        engine.rename_meeting("2026-10-28_1430_复盘".to_string(), "   ".to_string()).unwrap();

        // 标题归一化成"没有"；列表标题的回退顺序是 meta.title → 纪要里的一级标题 → 目录名
        assert_eq!(meetings::read_meta(&dir).title, None);
        let title = engine.scan_library().unwrap()[0].title.clone();
        assert_eq!(title, "标题", "有纪要时应取纪要标题，实际：{title}");
        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn set_tags_normalizes_and_dedupes() {
        let root = temp_root("tags");
        meeting(&root, "2026-10-28_1430_复盘");
        let engine = TingoEngine::new(root.display().to_string());

        let tags = engine
            .set_tags(
                "2026-10-28_1430_复盘".to_string(),
                vec!["  产品 ".to_string(), "产品".to_string(), "".to_string(), "周会".to_string()],
            )
            .unwrap();

        assert_eq!(tags, vec!["产品".to_string(), "周会".to_string()]);
        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn batch_reports_per_meeting_outcome_and_keeps_going() {
        let root = temp_root("batch");
        meeting(&root, "2026-10-28_1430_复盘");
        meeting(&root, "2026-10-29_1000_周会");
        let engine = TingoEngine::new(root.display().to_string());

        let outcomes = engine
            .batch(
                vec![
                    "2026-10-28_1430_复盘".to_string(),
                    "不存在的会议".to_string(),
                    "2026-10-29_1000_周会".to_string(),
                ],
                "archive".to_string(),
                String::new(),
            )
            .unwrap();

        assert_eq!(outcomes.len(), 3);
        assert!(outcomes[0].ok);
        assert!(!outcomes[1].ok, "坏的那场只影响它自己，实际：{outcomes:?}");
        assert!(outcomes[2].ok);
        assert!(engine.scan_library().unwrap().iter().all(|m| m.archived));
        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn batch_tag_add_and_remove() {
        let root = temp_root("batch-tags");
        meeting(&root, "2026-10-28_1430_复盘");
        let engine = TingoEngine::new(root.display().to_string());

        engine
            .batch(vec!["2026-10-28_1430_复盘".to_string()], "tag_add".to_string(), "产品".to_string())
            .unwrap();
        assert_eq!(engine.scan_library().unwrap()[0].tags, vec!["产品".to_string()]);

        engine
            .batch(vec!["2026-10-28_1430_复盘".to_string()], "tag_remove".to_string(), "产品".to_string())
            .unwrap();
        assert!(engine.scan_library().unwrap()[0].tags.is_empty());
        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn delete_to_trash_hides_meeting_and_restore_brings_it_back() {
        let root = temp_root("trash");
        let dir = meeting(&root, "2026-10-28_1430_复盘");
        let engine = TingoEngine::new(root.display().to_string());

        let freed = engine.delete_to_trash("2026-10-28_1430_复盘".to_string()).unwrap();
        assert!(freed > 0);
        assert!(!dir.exists());
        assert!(engine.scan_library().unwrap().is_empty(), "回收站里的会议不能出现在库里");
        assert_eq!(engine.list_trash().unwrap().len(), 1);

        engine.restore_from_trash("2026-10-28_1430_复盘".to_string()).unwrap();
        assert!(dir.is_dir());
        assert!(engine.list_trash().unwrap().is_empty());
        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn delete_audio_trashes_only_the_recording() {
        let root = temp_root("delete-audio");
        let dir = meeting(&root, "2026-10-28_1430_复盘");
        std::fs::write(dir.join("录音.wav"), vec![0u8; 4096]).unwrap();
        let engine = TingoEngine::new(root.display().to_string());

        let freed = engine
            .delete_audio("2026-10-28_1430_复盘".to_string(), vec![])
            .unwrap();

        assert_eq!(freed, 4096, "腾出的字节数要按被移走的录音算");
        assert!(!dir.join("录音.wav").exists(), "录音要离开会议目录");
        assert!(dir.join("纪要.md").is_file(), "转写与纪要必须留在原位");
        // 移到回收站而不是删掉：用户点错了还能找回来
        assert!(root.join("meetings").join(TRASH_DIR).join("2026-10-28_1430_复盘").join("录音.wav").is_file());
        // 会议本身还在库里（只是没有录音了）
        assert_eq!(engine.scan_library().unwrap().len(), 1);
        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn delete_audio_refuses_while_the_meeting_is_busy() {
        let root = temp_root("delete-audio-busy");
        let dir = meeting(&root, "2026-10-28_1430_复盘");
        std::fs::write(dir.join("录音.wav"), vec![0u8; 1024]).unwrap();
        let engine = TingoEngine::new(root.display().to_string());

        let result = engine.delete_audio(
            "2026-10-28_1430_复盘".to_string(),
            vec!["2026-10-28_1430_复盘".to_string()],
        );

        assert!(result.is_err(), "正在转写的会议不能删录音");
        assert!(dir.join("录音.wav").is_file(), "拒绝之后录音必须还在");
        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn delete_audio_without_a_recording_is_an_error() {
        let root = temp_root("delete-audio-empty");
        meeting(&root, "2026-10-28_1430_复盘");
        let engine = TingoEngine::new(root.display().to_string());

        assert!(engine.delete_audio("2026-10-28_1430_复盘".to_string(), vec![]).is_err());
        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn batch_delete_audio_also_trashes_the_recording() {
        let root = temp_root("batch-delete-audio");
        let dir = meeting(&root, "2026-10-28_1430_复盘");
        std::fs::write(dir.join("录音.wav"), vec![0u8; 2048]).unwrap();
        let engine = TingoEngine::new(root.display().to_string());

        engine
            .batch(
                vec!["2026-10-28_1430_复盘".to_string()],
                "delete_audio".to_string(),
                String::new(),
            )
            .unwrap();

        assert!(!dir.join("录音.wav").exists());
        assert!(dir.join("纪要.md").is_file());
        // 批量也一样进回收站：界面上说"可从废纸篓恢复"就必须真能恢复
        assert!(root.join("meetings").join(TRASH_DIR).join("2026-10-28_1430_复盘").join("录音.wav").is_file());
        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn restore_refuses_to_overwrite_existing_meeting() {
        let root = temp_root("trash-conflict");
        meeting(&root, "2026-10-28_1430_复盘");
        let engine = TingoEngine::new(root.display().to_string());
        engine.delete_to_trash("2026-10-28_1430_复盘".to_string()).unwrap();
        // 用户又录了一场同名会议
        meeting(&root, "2026-10-28_1430_复盘");

        assert!(engine.restore_from_trash("2026-10-28_1430_复盘".to_string()).is_err());
        // 两边都还在：库里的没被删，回收站里的也没丢
        assert!(engine.scan_library().unwrap().len() == 1);
        assert_eq!(engine.list_trash().unwrap().len(), 1);
        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn purge_trash_empties_it() {
        let root = temp_root("purge");
        meeting(&root, "2026-10-28_1430_复盘");
        let engine = TingoEngine::new(root.display().to_string());
        engine.delete_to_trash("2026-10-28_1430_复盘".to_string()).unwrap();

        let freed = engine.purge_trash(String::new()).unwrap();
        assert!(freed > 0);
        assert!(engine.list_trash().unwrap().is_empty());
        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn search_finds_transcript_text_and_minutes() {
        let root = temp_root("search");
        let dir = meeting(&root, "2026-10-28_1430_复盘");
        std::fs::write(
            dir.join("transcript.json"),
            r#"{"source":"录音.wav","duration_ms":6000,"sentences":[{"speaker":0,"start_ms":1000,"end_ms":2000,"text":"短信验正码延迟四秒"}]}"#,
        )
        .unwrap();
        std::fs::write(dir.join("纪要.md"), "# 复盘

## 决议
- 修短信验证码（[00:01]）
").unwrap();

        let engine = TingoEngine::new(root.display().to_string());
        let result = engine.search("验证码".to_string(), "zh".to_string()).unwrap();

        assert_eq!(result.query, "验证码");
        assert!(!result.hits.is_empty(), "应命中识别错字，实际：{result:?}");
        assert!(result.hits.iter().all(|h| h.meeting_id == "2026-10-28_1430_复盘"));
        assert!(result.hits.iter().any(|h| h.start_ms == 1000 || h.in_minutes));
        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn renamed_speakers_keep_their_names_in_both_languages() {
        let root = temp_root("search-named");
        let dir = meeting(&root, "2026-10-28_1430_周会");
        std::fs::write(
            dir.join("transcript.json"),
            r#"{"source":"录音.wav","duration_ms":6000,"speaker_names":{"0":"林舟"},"sentences":[{"speaker":0,"start_ms":500,"end_ms":1500,"text":"注册页改完了"}]}"#,
        )
        .unwrap();
        let engine = TingoEngine::new(root.display().to_string());

        // 改过名的说话人在两种界面语言下都得写"林舟"：不能被本地化覆盖
        for language in ["zh", "en", "en-US", ""] {
            let hits = engine.search("注册页".to_string(), language.to_string()).unwrap().hits;
            assert_eq!(hits.first().map(|h| h.speaker.clone()), Some("林舟".to_string()), "language={language}");
        }
        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn read_meeting_uses_the_ui_language_for_speaker_names() {
        let root = temp_root("read-lang");
        meeting_with_transcript(&root, "2026-11-02_1000_周会");
        let engine = TingoEngine::new(root.display().to_string());

        let zh = engine.read_meeting("2026-11-02_1000_周会".to_string(), "zh".to_string()).unwrap();
        let en = engine.read_meeting("2026-11-02_1000_周会".to_string(), "en".to_string()).unwrap();
        let zh_names: Vec<&str> = zh.transcript.as_ref().unwrap().turns.iter().map(|t| t.speaker_name.as_str()).collect();
        let en_names: Vec<&str> = en.transcript.as_ref().unwrap().turns.iter().map(|t| t.speaker_name.as_str()).collect();

        assert_eq!(zh_names, vec!["说话人1", "说话人2", "说话人1"]);
        assert_eq!(en_names, vec!["Speaker 1", "Speaker 2", "Speaker 1"]);
        // 说话人列表（重命名界面）也要跟着走，否则详情页写"说话人1"、改名弹窗写"Speaker 1"
        let en_speakers = engine.speakers("2026-11-02_1000_周会".to_string(), "en".to_string()).unwrap();
        assert_eq!(en_speakers.iter().map(|s| s.name.clone()).collect::<Vec<_>>(), vec!["Speaker 1", "Speaker 2"]);
        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn search_renders_speaker_names_in_the_ui_language() {
        let root = temp_root("search-lang");
        let dir = meeting(&root, "2026-10-28_1430_周会");
        std::fs::write(
            dir.join("transcript.json"),
            r#"{"source":"录音.wav","duration_ms":6000,"sentences":[{"speaker":0,"start_ms":500,"end_ms":1500,"text":"注册页改完了"}]}"#,
        )
        .unwrap();
        let engine = TingoEngine::new(root.display().to_string());

        let zh = engine.search("注册页".to_string(), "zh".to_string()).unwrap();
        let en = engine.search("注册页".to_string(), "en".to_string()).unwrap();

        assert_eq!(zh.hits.first().map(|h| h.speaker.clone()), Some("说话人1".to_string()));
        assert_eq!(
            en.hits.first().map(|h| h.speaker.clone()),
            Some("Speaker 1".to_string()),
            "英文界面下不该还写着「说话人1」"
        );
        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn storage_reports_totals_and_leftovers() {
        let root = temp_root("storage");
        let dir = meeting(&root, "2026-10-28_1430_复盘");
        std::fs::write(dir.join("录音.wav"), vec![0u8; 2048]).unwrap();
        // 已被合成录音取代的原始音轨：属于可清理的残留
        std::fs::write(dir.join("mic.wav"), vec![0u8; 1024]).unwrap();
        let engine = TingoEngine::new(root.display().to_string());

        let stats = engine.storage_stats().unwrap();
        assert_eq!(stats.count, 1);
        assert!(stats.total_bytes >= 3072);
        // 引擎把 mic.wav 也算"音频文件"（它就是一条音轨），所以音频体积含它；
        // 残留体积单独再算一份 mic.wav——这正是界面要提示"删掉能省 1 KB"的数
        assert_eq!(stats.audio_bytes, 3072);
        assert_eq!(stats.leftover_bytes, 1024);
        assert_eq!(stats.largest.len(), 1);
        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn batch_delete_goes_to_trash_not_oblivion() {
        let root = temp_root("batch-delete-trash");
        meeting(&root, "2026-10-28_1430_复盘");
        let engine = TingoEngine::new(root.display().to_string());

        let outcomes = engine.batch(vec!["2026-10-28_1430_复盘".to_string()], "delete".to_string(), String::new()).unwrap();
        assert!(outcomes[0].ok);
        assert!(engine.scan_library().unwrap().is_empty());
        // 关键：还能恢复，批量删除不等于彻底删除
        assert_eq!(engine.list_trash().unwrap().len(), 1);
        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn unknown_batch_action_is_rejected_as_a_whole() {
        let root = temp_root("batch-unknown");
        meeting(&root, "2026-10-28_1430_复盘");
        let engine = TingoEngine::new(root.display().to_string());

        // 拼错动作名要整体报错，而不是伪装成"每场会议都失败了"
        let result = engine.batch(vec!["2026-10-28_1430_复盘".to_string()], "archiv".to_string(), String::new());
        assert!(matches!(result, Err(EngineError::InvalidInput { .. })), "实际：{result:?}");
        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn trash_never_shows_up_in_library() {
        let root = temp_root("trash-hidden");
        meeting(&root, "2026-10-28_1430_复盘");
        let engine = TingoEngine::new(root.display().to_string());
        engine.delete_to_trash("2026-10-28_1430_复盘".to_string()).unwrap();

        // 回收站里那场会议自己带纪要和转写，但它是 `.trash/<id>` 下的子目录，
        // 不会成为库根下的一级目录，所以扫描时必须看不到
        assert!(engine.scan_library().unwrap().is_empty());
        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn cleanup_leftovers_frees_only_junk() {
        let root = temp_root("cleanup");
        let dir = meeting(&root, "2026-10-28_1430_复盘");
        std::fs::write(dir.join("录音.wav"), vec![0u8; 2048]).unwrap();
        std::fs::write(dir.join("mic.wav"), vec![0u8; 512]).unwrap();
        std::fs::write(dir.join("system.part"), vec![0u8; 128]).unwrap();
        let engine = TingoEngine::new(root.display().to_string());

        let freed = engine.cleanup_leftovers().unwrap();

        assert_eq!(freed, 640);
        assert!(dir.join("录音.wav").is_file(), "正式录音不能被清掉");
        assert!(!dir.join("mic.wav").exists());
        assert!(!dir.join("system.part").exists());
        assert_eq!(engine.storage_stats().unwrap().leftover_bytes, 0);
        let _ = std::fs::remove_dir_all(&root);
    }

    // ---- 清理旧音频 ----

    /// 造一场带音频的会议：`录音.wav` + 转写。
    fn meeting_with_audio(root: &Path, id: &str, audio_bytes: usize) -> PathBuf {
        let dir = root.join("meetings").join(id);
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(dir.join("录音.wav"), vec![0u8; audio_bytes]).unwrap();
        std::fs::write(dir.join("纪要.md"), "# 标题\n").unwrap();
        dir
    }

    #[test]
    fn cleanup_audio_removes_old_audio_but_keeps_minutes() {
        let root = temp_root("cleanup-audio");
        // 会议日来自目录名：2020 年的那场算"旧"，今天的不算
        let old = meeting_with_audio(&root, "2020-01-01_1000_旧会", 4096);
        let recent = meeting_with_audio(&root, "2026-11-10_1000_新会", 2048);
        let engine = TingoEngine::new(root.display().to_string());

        let report = engine.cleanup_audio(30, false, Vec::new()).unwrap();

        assert_eq!(report.freed_bytes, 4096);
        assert_eq!(report.meetings, 1);
        assert_eq!(report.failed, 0);
        assert!(!old.join("录音.wav").exists(), "旧会议的音频该被删掉");
        assert!(old.join("纪要.md").is_file(), "纪要必须留着");
        assert!(recent.join("录音.wav").is_file(), "30 天内的会议不能被清");
        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn cleanup_audio_zero_days_means_everything() {
        let root = temp_root("cleanup-audio-all");
        let dir = meeting_with_audio(&root, "2026-11-10_1000_今天的会", 1024);
        let engine = TingoEngine::new(root.display().to_string());

        let report = engine.cleanup_audio(0, false, Vec::new()).unwrap();

        assert_eq!(report.freed_bytes, 1024);
        assert!(!dir.join("录音.wav").exists());
        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn cleanup_audio_can_limit_itself_to_archived_meetings() {
        let root = temp_root("cleanup-audio-archived");
        let archived = meeting_with_audio(&root, "2020-01-01_1000_归档的", 512);
        let live = meeting_with_audio(&root, "2020-01-02_1000_没归档", 512);
        let engine = TingoEngine::new(root.display().to_string());
        engine.set_archived("2020-01-01_1000_归档的".to_string(), true).unwrap();

        let report = engine.cleanup_audio(0, true, Vec::new()).unwrap();

        assert_eq!(report.meetings, 1);
        assert!(!archived.join("录音.wav").exists());
        assert!(live.join("录音.wav").is_file(), "只清已归档的");
        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn cleanup_audio_skips_meetings_that_are_being_processed() {
        let root = temp_root("cleanup-audio-busy");
        let busy = meeting_with_audio(&root, "2020-01-01_1000_正在转写", 1024);
        let idle = meeting_with_audio(&root, "2020-01-02_1000_空闲", 1024);
        let engine = TingoEngine::new(root.display().to_string());

        let report = engine
            .cleanup_audio(0, false, vec!["2020-01-01_1000_正在转写".to_string()])
            .unwrap();

        assert_eq!(report.meetings, 1);
        assert!(busy.join("录音.wav").is_file(), "正在转写的会议不能删音频");
        assert!(!idle.join("录音.wav").exists());
        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn cleanup_audio_rejects_absurd_day_counts() {
        let root = temp_root("cleanup-audio-days");
        let engine = TingoEngine::new(root.display().to_string());

        let err = engine.cleanup_audio(99_999, false, Vec::new()).unwrap_err();

        assert!(matches!(err, EngineError::InvalidInput { .. }), "越界天数要报参数错误：{err:?}");
        let _ = std::fs::remove_dir_all(&root);
    }

    // ---- 说话人与术语表 ----

    /// 造一场带转写的会议：两位说话人。
    fn meeting_with_transcript(root: &Path, id: &str) -> PathBuf {
        let dir = root.join("meetings").join(id);
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(
            dir.join("transcript.json"),
            r#"{"source":"录音.wav","duration_ms":20000,"sentences":[
                {"speaker":0,"start_ms":0,"end_ms":3000,"text":"我是林舟，今天过一下进度"},
                {"speaker":1,"start_ms":4000,"end_ms":7000,"text":"林舟你说一下注册页"},
                {"speaker":0,"start_ms":8000,"end_ms":11000,"text":"注册页改完了"}
            ]}"#,
        )
        .unwrap();
        dir
    }

    #[test]
    fn speakers_lists_in_first_speech_order() {
        let root = temp_root("speakers");
        meeting_with_transcript(&root, "2026-11-02_1000_周会");
        let engine = TingoEngine::new(root.display().to_string());

        let speakers = engine.speakers("2026-11-02_1000_周会".to_string(), "zh".to_string()).unwrap();

        assert_eq!(speakers.len(), 2);
        assert_eq!(speakers[0].index, 0);
        assert_eq!(speakers[0].name, "说话人1");
        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn set_speaker_names_persists_and_skips_blanks() {
        let root = temp_root("speakers-set");
        let dir = meeting_with_transcript(&root, "2026-11-02_1000_周会");
        let engine = TingoEngine::new(root.display().to_string());

        engine
            .set_speaker_names("2026-11-02_1000_周会".to_string(), vec!["  林舟 ".to_string(), String::new()])
            .unwrap();

        let speakers = engine.speakers("2026-11-02_1000_周会".to_string(), "zh".to_string()).unwrap();
        assert_eq!(speakers[0].name, "林舟");
        assert_eq!(speakers[1].name, "说话人2", "空名字表示不改");
        // 真的落到 transcript.json 里了
        let text = std::fs::read_to_string(dir.join("transcript.json")).unwrap();
        assert!(text.contains("林舟"));
        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn speaker_prompt_mentions_every_speaker() {
        let root = temp_root("speakers-prompt");
        meeting_with_transcript(&root, "2026-11-02_1000_周会");
        let engine = TingoEngine::new(root.display().to_string());

        let prompt = engine.speaker_prompt("2026-11-02_1000_周会".to_string(), "zh".to_string()).unwrap();

        assert!(!prompt.system.trim().is_empty());
        assert!(prompt.prompt.contains("说话人1") && prompt.prompt.contains("说话人2"));
        assert_eq!(prompt.speaker_count, 2);
        assert!(prompt.prompt.contains("我是林舟"), "提示词要带上转写内容");
        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn parses_speaker_suggestions_and_ignores_out_of_range() {
        let root = temp_root("speakers-parse");
        meeting_with_transcript(&root, "2026-11-02_1000_周会");
        let engine = TingoEngine::new(root.display().to_string());

        let reply = r#"{"suggestions":[{"index":0,"name":"林舟","evidence":"[00:00] 说话人1：我是林舟"},{"index":9,"name":"幽灵","evidence":"x"}]}"#;
        let infos = engine
            .parse_speaker_suggestions("2026-11-02_1000_周会".to_string(), reply.to_string())
            .unwrap();

        assert_eq!(infos[0].suggested, "林舟");
        assert!(infos.iter().all(|i| i.suggested != "幽灵"), "越界编号应被丢弃");
        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn unparsable_speaker_reply_is_malformed() {
        let root = temp_root("speakers-bad");
        meeting_with_transcript(&root, "2026-11-02_1000_周会");
        let engine = TingoEngine::new(root.display().to_string());

        let result = engine.parse_speaker_suggestions("2026-11-02_1000_周会".to_string(), "抱歉，我不确定".to_string());
        assert!(matches!(result, Err(EngineError::Malformed { .. })), "实际：{result:?}");
        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn glossary_merges_case_and_space_variants() {
        let root = temp_root("glossary-norm");
        let a = meeting(&root, "2026-11-02_1000_周会");
        std::fs::write(
            a.join("纪要.md"),
            "# 周会\n\n```terms\n{\"terms\": [{\"term\": \"Work Order\", \"variants\": []}]}\n```\n",
        )
        .unwrap();
        let b = meeting(&root, "2026-11-03_1000_复盘");
        std::fs::write(
            b.join("纪要.md"),
            "# 复盘\n\n```terms\n{\"terms\": [{\"term\": \"workorder\", \"variants\": []}]}\n```\n",
        )
        .unwrap();
        let engine = TingoEngine::new(root.display().to_string());

        // 大小写与空白不同的写法是同一个词：搜索认得出，合并时也必须认出
        let glossary = engine.glossary().unwrap();
        assert_eq!(glossary.len(), 1, "实际：{glossary:?}");
        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn glossary_merges_terms_across_meetings() {
        let root = temp_root("glossary");
        let a = meeting(&root, "2026-11-02_1000_周会");
        std::fs::write(
            a.join("纪要.md"),
            "# 周会\n\n```terms\n{\"terms\": [{\"term\": \"历史工单\", \"variants\": [\"历史中单\"]}]}\n```\n",
        )
        .unwrap();
        let b = meeting(&root, "2026-11-03_1000_复盘");
        std::fs::write(
            b.join("纪要.md"),
            "# 复盘\n\n```terms\n{\"terms\": [{\"term\": \"历史工单\", \"variants\": [\"历史公单\"]}]}\n```\n",
        )
        .unwrap();
        let engine = TingoEngine::new(root.display().to_string());

        let glossary = engine.glossary().unwrap();

        assert_eq!(glossary.len(), 1, "同一个词要合并成一条：{glossary:?}");
        assert_eq!(glossary[0].term, "历史工单");
        assert_eq!(glossary[0].variants.len(), 2);
        let _ = std::fs::remove_dir_all(&root);
    }

    // ---- 纪要历史与导出 ----

    #[test]
    fn saving_minutes_archives_the_previous_version() {
        let root = temp_root("minutes-archive");
        let dir = meeting(&root, "2026-11-05_1000_周会");
        std::fs::write(dir.join("纪要.md"), "# 周会\n\n旧的内容\n").unwrap();
        let engine = TingoEngine::new(root.display().to_string());

        engine.save_minutes("2026-11-05_1000_周会".to_string(), "# 周会\n\n新的内容".to_string()).unwrap();

        // 当前文件是新内容，旧内容进了历史
        let current = std::fs::read_to_string(dir.join("纪要.md")).unwrap();
        assert!(current.contains("新的内容"));
        let history = engine.minutes_history("2026-11-05_1000_周会".to_string()).unwrap();
        assert_eq!(history.len(), 1);
        assert!(history[0].preview.contains("旧的内容"));
        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn restoring_a_version_also_archives_what_it_replaces() {
        let root = temp_root("minutes-restore");
        let dir = meeting(&root, "2026-11-05_1000_周会");
        std::fs::write(dir.join("纪要.md"), "# 周会\n\nv1\n").unwrap();
        let engine = TingoEngine::new(root.display().to_string());
        engine.save_minutes("2026-11-05_1000_周会".to_string(), "# 周会\n\nv2".to_string()).unwrap();

        let first = engine.minutes_history("2026-11-05_1000_周会".to_string()).unwrap()[0].id.clone();
        engine.restore_minutes_version("2026-11-05_1000_周会".to_string(), first).unwrap();

        // 恢复之后当前是 v1，而 v2 也进了历史——"恢复"本身也能再退回去
        assert!(std::fs::read_to_string(dir.join("纪要.md")).unwrap().contains("v1"));
        let versions = engine.minutes_history("2026-11-05_1000_周会".to_string()).unwrap();
        assert_eq!(versions.len(), 2);
        assert!(versions.iter().any(|v| v.preview.contains("v2")));
        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn every_version_can_be_read_and_deleted() {
        let root = temp_root("minutes-delete");
        let dir = meeting(&root, "2026-11-05_1000_周会");
        std::fs::write(dir.join("纪要.md"), "# 周会\n\n第一版\n").unwrap();
        let engine = TingoEngine::new(root.display().to_string());
        engine.save_minutes("2026-11-05_1000_周会".to_string(), "# 周会\n\n第二版".to_string()).unwrap();

        let id = engine.minutes_history("2026-11-05_1000_周会".to_string()).unwrap()[0].id.clone();
        assert!(engine.minutes_version("2026-11-05_1000_周会".to_string(), id.clone()).unwrap().contains("第一版"));

        engine.delete_minutes_version("2026-11-05_1000_周会".to_string(), id).unwrap();
        assert!(engine.minutes_history("2026-11-05_1000_周会".to_string()).unwrap().is_empty());
        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn history_rejects_foreign_version_ids() {
        let root = temp_root("minutes-badid");
        meeting(&root, "2026-11-05_1000_周会");
        let engine = TingoEngine::new(root.display().to_string());

        // 版本号只允许数字与连字符：`../../x` 这种必须拒绝，不能拿它当路径
        let result = engine.minutes_version("2026-11-05_1000_周会".to_string(), "../meta.json".to_string());
        assert!(matches!(result, Err(EngineError::InvalidInput { .. })), "实际：{result:?}");
        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn export_srt_writes_a_file_named_after_the_minutes_title() {
        let root = temp_root("export-srt");
        let dir = meeting_with_transcript(&root, "2026-11-05_1000_产品复盘");
        std::fs::write(dir.join("纪要.md"), "# 产品复盘会\n\n## 决议\n- 先修首屏\n").unwrap();
        let engine = TingoEngine::new(root.display().to_string());

        let path = engine.export_srt("2026-11-05_1000_产品复盘".to_string(), "zh".to_string()).unwrap();

        let text = std::fs::read_to_string(&path).unwrap();
        assert!(path.ends_with("产品复盘会.srt"), "实际：{path}");
        assert!(text.contains("1\n"), "SRT 应有序号：{text}");
        assert!(text.contains("-->"), "SRT 应有时间轴：{text}");
        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn export_srt_without_transcript_is_missing_not_empty_file() {
        let root = temp_root("export-srt-none");
        meeting(&root, "2026-11-05_1000_周会");
        let engine = TingoEngine::new(root.display().to_string());

        let result = engine.export_srt("2026-11-05_1000_周会".to_string(), "zh".to_string());
        assert!(matches!(result, Err(EngineError::Missing { .. })), "实际：{result:?}");
        let _ = std::fs::remove_dir_all(&root);
    }

    // ---- 导出 Excalidraw ----

    /// 纪要里嵌一块鱼骨图。格式与模型产出一致（```fishbone 代码块里放 JSON）。
    fn minutes_with_fishbone(dir: &Path) -> std::path::PathBuf {
        let spec = r#"{"problem":"注册转化率下降","branches":[{"title":"产品","detail":"首屏太慢"},{"title":"技术","detail":"埋点缺失"}]}"#;
        let md = format!("# 产品复盘\n\n## 一句话总结\n转化率下降。\n\n## 原因分析\n\n```fishbone\n{spec}\n```\n");
        std::fs::write(dir.join("纪要.md"), md).unwrap();
        dir.to_path_buf()
    }

    #[test]
    fn exports_one_excalidraw_file_per_fishbone() {
        let root = temp_root("excalidraw");
        let dir = meeting(&root, "2026-11-07_1000_产品复盘");
        minutes_with_fishbone(&dir);
        let engine = TingoEngine::new(root.display().to_string());

        let paths = engine.export_excalidraw("2026-11-07_1000_产品复盘".to_string()).unwrap();

        assert_eq!(paths.len(), 1);
        assert!(paths[0].ends_with("鱼骨图.excalidraw"), "实际：{paths:?}");
        let body = std::fs::read_to_string(&paths[0]).unwrap();
        // 是 Excalidraw 认的画板文档，而不是随手写的 JSON
        assert!(body.contains("\"type\": \"excalidraw\""), "缺少画板类型：{body:.120}");
        assert!(body.contains("注册转化率下降"), "问题文本没进画板");
        assert!(body.contains("首屏太慢"), "分支原因没进画板");
        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn excalidraw_export_without_fishbone_is_missing() {
        let root = temp_root("excalidraw-none");
        let dir = meeting(&root, "2026-11-07_1000_产品复盘");
        std::fs::write(dir.join("纪要.md"), "# 产品复盘\n\n## 一句话总结\n没有鱼骨图。\n").unwrap();
        let engine = TingoEngine::new(root.display().to_string());

        let result = engine.export_excalidraw("2026-11-07_1000_产品复盘".to_string());
        assert!(matches!(result, Err(EngineError::Missing { .. })), "实际：{result:?}");
        let _ = std::fs::remove_dir_all(&root);
    }

    // ---- 热词 ----

    #[test]
    fn hotwords_round_trip_and_normalize() {
        let root = temp_root("hotwords");
        let engine = TingoEngine::new(root.display().to_string());
        assert!(engine.hotwords().unwrap().is_empty());

        let saved = engine
            .set_hotwords(vec![" 星河 ".to_string(), "Kafka".to_string(), "星河".to_string(), String::new()])
            .unwrap();

        // 去空白、去重、丢空项；写进配置文件后要能读回来
        assert_eq!(saved, vec!["星河".to_string(), "Kafka".to_string()]);
        assert_eq!(engine.hotwords().unwrap(), saved);
        assert!(std::fs::read_to_string(root.join("config").join("config.toml")).unwrap().contains("星河"));
        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn glossary_snippet_mentions_every_hotword() {
        let root = temp_root("hotwords-glossary");
        let engine = TingoEngine::new(root.display().to_string());
        engine
            .set_hotwords(vec!["星河".to_string(), "Kafka".to_string()])
            .unwrap();

        let zh = engine.minutes_glossary("zh".to_string()).unwrap();
        let en = engine.minutes_glossary("en".to_string()).unwrap();

        assert!(zh.contains("星河") && zh.contains("Kafka"), "实际：{zh}");
        assert!(en.contains("星河") && en.contains("Kafka"), "实际：{en}");
        // 中英两版是同一段说明的不同语言，不是"英文界面漏了这段"
        assert_ne!(zh, en);
        // 编号由宿主排：这里只给句子，不带 "9."
        assert!(!zh.contains("9."), "不该自带编号：{zh}");
        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn empty_hotwords_produce_no_glossary_snippet() {
        let root = temp_root("hotwords-empty");
        let engine = TingoEngine::new(root.display().to_string());

        // 没填热词就不该往提示词里塞一段空说明
        assert!(engine.minutes_glossary("zh".to_string()).unwrap().is_empty());
        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn hand_edited_config_is_capped_when_read() {
        let root = temp_root("hotwords-cap");
        let engine = TingoEngine::new(root.display().to_string());
        engine.set_hotwords(vec!["星河".to_string()]).unwrap();

        // 直接改配置文件塞进 300 个词：读的时候也要受上限约束，否则提示词会被热词占满
        let path = root.join("config").join("config.toml");
        let words: Vec<String> = (0..300).map(|i| format!("\"词{i}\"")).collect();
        let text = std::fs::read_to_string(&path)
            .unwrap()
            .replace("hotwords = [\"星河\"]", &format!("hotwords = [{}]", words.join(", ")));
        std::fs::write(&path, text).unwrap();

        assert_eq!(engine.hotwords().unwrap().len(), MAX_HOTWORDS);
        let _ = std::fs::remove_dir_all(&root);
    }

    // ---- 导入录音 ----

    #[test]
    fn import_creates_a_meeting_with_the_standard_audio_name() {
        let root = temp_root("import");
        let engine = TingoEngine::new(root.display().to_string());
        let source = root.join("周会录音.wav").to_path_buf();
        let fake = vec![0u8; 4_000];
        std::fs::write(&source, &fake).unwrap();

        let imported = engine
            .import_audio(source.to_string_lossy().into_owned(), "产品周会".to_string(), 0)
            .unwrap();
        let id = imported.id;

        assert!(id.contains("产品周会"), "会议名应取用户给的标题：{id}");
        // 播放与转写都按 `录音.wav` 找音频，导入必须落成同一个名字
        let audio = root.join("meetings").join(&id).join("录音.wav");
        assert!(audio.is_file(), "缺录音.wav：{}", audio.display());
        assert_eq!(std::fs::read(&audio).unwrap().len(), 4_000);
        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn import_uses_the_file_mtime_for_the_folder_name() {
        let root = temp_root("import-mtime");
        let engine = TingoEngine::new(root.display().to_string());
        let source = root.join("旧录音.wav").to_path_buf();
        std::fs::write(&source, vec![1u8; 1_000]).unwrap();
        // 2026-11-05 14:30 本地时间（用引擎自己的换算反推出毫秒值，避免测试依赖时区实现）
        let when = chrono::NaiveDate::from_ymd_opt(2026, 11, 5)
            .unwrap()
            .and_hms_opt(14, 30, 0)
            .unwrap();
        let ms = when.and_local_timezone(chrono::Local).unwrap().timestamp_millis();

        let imported = engine
            .import_audio(source.to_string_lossy().into_owned(), String::new(), ms)
            .unwrap();

        // 导入的文件时间就是这场会议的时间：不能拿"今天"糊弄过去
        assert!(imported.id.starts_with("2026-11-05_1430_"), "实际：{}", imported.id);
        // 目录一起回来：宿主不该自己拼 <root>/meetings/<id>
        assert!(imported.dir.ends_with(&imported.id), "目录应与 id 对得上：{}", imported.dir);
        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn empty_import_is_rejected() {
        let root = temp_root("import-empty");
        let engine = TingoEngine::new(root.display().to_string());
        let source = root.join("空.wav").to_path_buf();
        std::fs::write(&source, b"").unwrap();

        let result = engine.import_audio(source.to_string_lossy().into_owned(), "空".to_string(), 0);
        assert!(matches!(result, Err(EngineError::InvalidInput { .. })), "实际：{result:?}");
        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn missing_import_source_is_missing_not_internal() {
        let root = temp_root("import-missing");
        let engine = TingoEngine::new(root.display().to_string());

        let result = engine.import_audio("/nonexistent/x.wav".to_string(), "x".to_string(), 0);
        assert!(matches!(result, Err(EngineError::Missing { .. })), "实际：{result:?}");
        let _ = std::fs::remove_dir_all(&root);
    }

    // ---- 示例会议 ----

    fn sample_dir(root: &Path, en: bool) -> PathBuf {
        let dir = root.join("sample");
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(
            dir.join("transcript.json"),
            r#"{"source":"录音.m4a","durationMs":120000,"sentences":[{"speaker":0,"startMs":0,"endMs":2000,"text":"今天复盘注册转化。"}],"speakerNames":{}}"#,
        )
        .unwrap();
        std::fs::write(dir.join("纪要.md"), "# 示例会议\n\n## 一句话总结\n转化率下降。\n").unwrap();
        if en {
            std::fs::write(dir.join("纪要.en.md"), "# Sample meeting\n\n## Summary\nConversion dropped.\n").unwrap();
        }
        std::fs::write(dir.join("录音.m4a"), vec![0u8; 128]).unwrap();
        dir
    }

    #[test]
    fn demo_meeting_lands_in_the_library_and_is_idempotent() {
        let root = temp_root("demo");
        let sample = sample_dir(&root, false);
        let engine = TingoEngine::new(root.display().to_string());

        let first = engine
            .install_demo_meeting(sample.to_string_lossy().into_owned(), false)
            .unwrap();
        let second = engine
            .install_demo_meeting(sample.to_string_lossy().into_owned(), false)
            .unwrap();

        // 装两次不该出现两个示例会议
        assert_eq!(first.id, second.id);
        assert_eq!(first.id, "2026-10-28_1400_示例·注册转化复盘");
        let dir = std::path::Path::new(&first.dir);
        assert!(dir.join("transcript.json").is_file());
        assert!(dir.join("纪要.md").is_file());
        assert!(dir.join("录音.m4a").is_file(), "示例的音频要一起装进去");
        // 扫库能看见（引导里点"看示例会议"直接进详情靠这个）
        assert!(engine.scan_library().unwrap().iter().any(|m| m.id == first.id));
        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn a_second_language_does_not_add_a_second_sample() {
        let root = temp_root("demo-two-langs");
        let sample = sample_dir(&root, true);
        let engine = TingoEngine::new(root.display().to_string());

        let zh = engine
            .install_demo_meeting(sample.to_string_lossy().into_owned(), false)
            .unwrap();
        let en = engine
            .install_demo_meeting(sample.to_string_lossy().into_owned(), true)
            .unwrap();

        // 切一次界面语言不该多出一场示例会议
        assert_eq!(zh.id, en.id);
        assert_eq!(engine.scan_library().unwrap().iter().filter(|m| m.id.contains("示例") || m.id.contains("Sample")).count(), 1);
        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn half_installed_demo_is_replaced_instead_of_returned() {
        let root = temp_root("demo-half");
        let sample = sample_dir(&root, false);
        let engine = TingoEngine::new(root.display().to_string());
        // 装到一半：目录在，但没有纪要（进程被杀就会这样）
        let broken = root.join("meetings").join(tingo_engine::meetings::demo_folder(false));
        std::fs::create_dir_all(&broken).unwrap();
        std::fs::write(broken.join("transcript.json"), "{}").unwrap();

        let demo = engine
            .install_demo_meeting(sample.to_string_lossy().into_owned(), false)
            .unwrap();

        // 半成品会出现在列表里但打不开，比没有更糟：应当被重新装一遍
        assert!(std::path::Path::new(&demo.dir).join("纪要.md").is_file());
        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn english_demo_uses_the_english_minutes() {
        let root = temp_root("demo-en");
        let sample = sample_dir(&root, true);
        let engine = TingoEngine::new(root.display().to_string());

        let demo = engine
            .install_demo_meeting(sample.to_string_lossy().into_owned(), true)
            .unwrap();

        let minutes = std::fs::read_to_string(std::path::Path::new(&demo.dir).join("纪要.md")).unwrap();
        assert!(minutes.contains("Summary"), "英文界面要装英文纪要：{minutes}");
        assert!(demo.id.contains("Sample"), "实际：{}", demo.id);
        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn incomplete_sample_is_reported_and_leaves_nothing_behind() {
        let root = temp_root("demo-incomplete");
        let sample = root.join("sample");
        std::fs::create_dir_all(&sample).unwrap();
        std::fs::write(sample.join("transcript.json"), "{}").unwrap();
        // 没有纪要：素材不全
        let engine = TingoEngine::new(root.display().to_string());

        let result = engine.install_demo_meeting(sample.to_string_lossy().into_owned(), false);

        assert!(matches!(result, Err(EngineError::Missing { .. })), "实际：{result:?}");
        // 半装出来的示例会议会出现在列表里但打不开，比没有更糟
        assert!(!root.join("meetings").join("2026-10-28_1400_示例·注册转化复盘").exists());
        let _ = std::fs::remove_dir_all(&root);
    }
}
