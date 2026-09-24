package com.github.jing332.tts.lyric

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 朗读文本总线。
 *
 * 合成管线每念到一句就推到这里，App 侧的悬浮歌词条订阅它。
 * 放在 lib-tts 里，任何走合成器的入口（系统 TTS、HTTP 服务、转发器）都能被覆盖。
 *
 * 关于上报时机：合成比播放快得多（网络 TTS 一句要好几百毫秒到几秒），
 * 如果按「开始合成」上报，悬浮条会比声音超前一整句。所以改成一句的音频
 * 真正开始输出时上报，并带上这一句的预计时长供窗口决定最少停留多久。
 */
object LyricBus {

    /** 当前朗读状态：整段被切分出的片段列表 + 正在念第几片。 */
    data class LyricState(
        val segments: List<String> = emptyList(),
        val index: Int = -1,
        val text: String = "",
        val updatedAt: Long = 0L,
        /** 这一句预计朗读时长（毫秒）。0 表示未知，窗口按默认值兜底。 */
        val durationMs: Long = 0L,
    ) {
        val previous: String get() = segments.getOrNull(index - 1).orEmpty()
        val next: String get() = segments.getOrNull(index + 1).orEmpty()
    }

    private val _state = MutableStateFlow(LyricState())

    val state: StateFlow<LyricState> get() = _state

    private const val MS_PER_CHAR = 235L
    private const val MS_PER_PUNCT = 160L
    private const val MIN_DURATION_MS = 1200L
    private const val MAX_DURATION_MS = 30000L

    /**
     * 按字数粗估这一句要念多久。
     * 只用来决定「最少在屏幕上留多久」，所以宁可估长一点——估短了会读一半就消失。
     * 真正的换行由下一句上报驱动，这个方法不负责切换。
     */
    fun estimateDurationMs(text: String, speed: Float = 1f): Long {
        if (text.isBlank()) return 0L
        val chars = text.count { !it.isWhitespace() }
        val puncts = text.count { it in "。？！?!；;：:，,、…—" }
        val rawMs = chars.toLong() * MS_PER_CHAR + puncts.toLong() * MS_PER_PUNCT
        val s = if (speed <= 0.01f) 1f else speed
        return (rawMs / s).toLong().coerceIn(MIN_DURATION_MS, MAX_DURATION_MS)
    }

    /** 一段文本被切分成多个朗读片段。 */
    fun onSegments(segments: List<String>) {
        val list = segments.map { it.trim() }.filter { it.isNotEmpty() }
        if (list.isEmpty()) return
        _state.value = _state.value.copy(segments = list, index = -1)
    }

    /**
     * 开始朗读第 index 个片段。
     *
     * @param durationMs 这一句的预计时长，不传就按字数估。
     */
    fun onSegment(index: Int, text: String, durationMs: Long = 0L) {
        val t = text.trim()
        if (t.isEmpty()) return
        _state.value = _state.value.copy(
            index = index,
            text = t,
            updatedAt = System.currentTimeMillis(),
            durationMs = if (durationMs > 0L) durationMs else estimateDurationMs(t),
        )
    }

    /** 结束当前朗读（清空正在念的一句，窗口自己超时隐藏）。 */
    fun onIdle() {
        val cur = _state.value
        if (cur.text.isEmpty()) return
        _state.value = cur.copy(index = -1, text = "", updatedAt = System.currentTimeMillis())
    }
}
