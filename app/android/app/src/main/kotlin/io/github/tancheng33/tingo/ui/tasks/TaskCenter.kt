package io.github.tancheng33.tingo.ui.tasks

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 一件正在进行的长任务。 */
enum class TaskKind {
    TRANSCRIBE,
    MINUTES,
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
 */
class TaskCenter {

    private val _tasks = MutableStateFlow<List<RunningTask>>(emptyList())
    val tasks: StateFlow<List<RunningTask>> = _tasks.asStateFlow()

    /** 登记一件活；同一个 key 再登记就是更新（例如从"准备中"变成带进度的运行中）。 */
    fun start(key: String, meetingId: String, title: String, kind: TaskKind, fraction: Float? = null) {
        val task = RunningTask(key, meetingId, title, kind, fraction)
        _tasks.value = _tasks.value.filterNot { it.key == key } + task
    }

    fun progress(key: String, fraction: Float?) {
        _tasks.value = _tasks.value.map { if (it.key == key) it.copy(fraction = fraction) else it }
    }

    /** 活干完了（成功、失败、取消都一样）：从表里撤掉。 */
    fun finish(key: String) {
        _tasks.value = _tasks.value.filterNot { it.key == key }
    }

    /** 会议被删了：把它的活都撤掉，免得点进去是一场不存在的会议。 */
    fun forget(meetingId: String) {
        _tasks.value = _tasks.value.filterNot { it.meetingId == meetingId }
    }
}
