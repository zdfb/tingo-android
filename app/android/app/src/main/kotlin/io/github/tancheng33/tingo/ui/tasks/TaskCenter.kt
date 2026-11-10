package io.github.tancheng33.tingo.ui.tasks

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 一件正在进行的长任务。 */
enum class TaskKind {
    TRANSCRIBE,
    MINUTES,

    /** 导入：解码 + 落库 + 转写整条链路，几分钟起步，必须能离开页面还看得见 */
    IMPORT,
}

/**
 * @param key 同一件活的唯一标识（种类 + 会议），重复登记只会更新同一条
 * @param fraction null 表示"在跑但没法量化进度"（例如纪要只有"生成中"）
 */
data class RunningTask(
    val key: String,
    val meetingId: String,
    val title: String,
    val kind: TaskKind,
    val fraction: Float? = null,
) {
    companion object {
        fun keyOf(kind: TaskKind, meetingId: String): String = "${kind.name}:$meetingId"

        /**
         * 会议还没建出来的任务（导入）：先按一个临时令牌登记，目录建好再 [TaskCenter.attach]。
         * 令牌用调用方给的不重复值，别拿字段拼。
         */
        fun pendingKey(kind: TaskKind, token: String): String = "${kind.name}:pending:$token"
    }
}

/**
 * "进行中任务"的唯一出处。
 *
 * 手机上没有桌面侧栏那块地方，但没有它的话，用户点开转写再切回首页就**看不到任何进展**——
 * 转写要跑几分钟，这正是最需要"它在动"的时刻。各页的 ViewModel 只负责登记/更新/注销，
 * 显示留给首页那块"进行中"区域。
 *
 * 这里不持有 Job、不负责取消：任务的生命周期由它的 ViewModel 管，这里只做一张登记表。
 *
 * 所有改动都走 `update`（CAS 重试），因为这个表**会被多个线程同时改**：
 * 转写进度来自 IO 协程，删会议的 `forget` 来自主线程。裸的
 * `_tasks.value = _tasks.value.xxx` 是读-改-写，两边撞上会丢一次写——表现正好是本类
 * 想避免的那种"一件活永远挂在首页上"。
 */
class TaskCenter {

    private val _tasks = MutableStateFlow<List<RunningTask>>(emptyList())
    val tasks: StateFlow<List<RunningTask>> = _tasks.asStateFlow()

    /** 登记一件活；同一个 key 再登记就是更新（例如从"准备中"变成带进度的运行中）。 */
    fun start(key: String, meetingId: String, title: String, kind: TaskKind, fraction: Float? = null) {
        val task = RunningTask(key, meetingId, title, kind, fraction)
        _tasks.update { tasks -> tasks.filterNot { it.key == key } + task }
    }

    /**
     * 任务"落了地"：把临时登记换成真实会议，**位置不变**。
     *
     * 不能"注销再登记"——那会让这一行在列表里跳到末尾，用户会以为刚又多了一件活。
     */
    fun attach(key: String, meetingId: String, title: String, fraction: Float?) {
        _tasks.update { tasks ->
            tasks.map { if (it.key == key) it.copy(meetingId = meetingId, title = title, fraction = fraction) else it }
        }
    }

    fun progress(key: String, fraction: Float?) {
        _tasks.update { tasks -> tasks.map { if (it.key == key) it.copy(fraction = fraction) else it } }
    }

    /** 活干完了（成功、失败、取消都一样）：从表里撤掉。 */
    fun finish(key: String) {
        _tasks.update { tasks -> tasks.filterNot { it.key == key } }
    }

    /** 会议被删了：把它的活都撤掉，免得点进去是一场不存在的会议。 */
    /** 正在跑的任务涉及的会议 id（删除/清理要跳过它们，桌面的 `meeting_busy` 是同一件事）。 */
    fun busyMeetingIds(): Set<String> =
        _tasks.value.mapNotNull { it.meetingId.takeIf { id -> id.isNotBlank() } }.toSet()

    fun forget(meetingId: String) {
        _tasks.update { tasks -> tasks.filterNot { it.meetingId == meetingId } }
    }
}
