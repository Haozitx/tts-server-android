package com.github.jing332.tts.lyric

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 朗读文本总线。
 *
 * 合成管线把每一句的显示文本、以及这一句在音频里的真实长度推到这里，悬浮条订阅它。
 * 放在 lib-tts 里，任何走合成器的入口（系统 TTS、HTTP 服务、转发器）都能被覆盖。
 *
 * 为什么一定要带长度：合成和「写进播放器缓冲」都比实际播放快得多，一次能灌进好几句，
 * 所以「下一句到了就切换」必然抢拍。改成按音频长度排队推进，显示节奏才和播放同源。
 */
object LyricBus {

    /** 当前朗读状态：整段被切分出的片段列表 + 正在念第几片。 */
    data class LyricState(
        val segments: List<String> = emptyList(),
        val index: Int = -1,
        val text: String = "",
        val updatedAt: Long = 0L,
        /** 这一句朗读时长（毫秒）。0 表示未知，窗口按字数兜底。 */
        val durationMs: Long = 0L,
    ) {
        val previous: String get() = segments.getOrNull(index - 1).orEmpty()
        val next: String get() = segments.getOrNull(index + 1).orEmpty()
    }

    /** 一句朗读：显示文本 + 这一句在音频里的真实长度。 */
    data class Segment(
        val generation: Long,
        val index: Int,
        val text: String,
        val durationMs: Long,
    )

    private val _state = MutableStateFlow(LyricState())

    val state: StateFlow<LyricState> get() = _state

    private var generation = 0L

    private val _segments = Channel<Segment>(Channel.UNLIMITED)

    /** 逐句事件流。用队列而不是状态，避免连续多句到达时被覆盖掉。 */
    val segments: ReceiveChannel<Segment> get() = _segments

    fun currentGeneration(): Long = generation

    /**
     * 一段新文本被切分出来：更新列表供「上一句／下一句」用，并重开这一轮时间轴。
     * 上一段没来得及显示的句子一并丢掉，免得串到新的一段里。
     */
    fun onSegments(displays: List<String>) {
        val list = displays.map { it.trim() }.filter { it.isNotEmpty() }
        if (list.isEmpty()) return
        generation++
        _state.value = LyricState(segments = list, index = -1)
        while (_segments.tryReceive().isSuccess) {
            // 丢掉旧段落积压的事件
        }
    }

    /**
     * 第 index 句的音频已经全部产出。
     *
     * @param durationMs 这一句音频的真实长度；拿不到（直连播放等）就按字数估。
     */
    fun onSegment(index: Int, text: String, durationMs: Long = 0L) {
        val t = text.trim()
        if (t.isEmpty()) return
        val dur = if (durationMs > 0L) durationMs else estimateDurationMs(t)
        _state.value = _state.value.copy(
            index = index,
            text = t,
            updatedAt = System.currentTimeMillis(),
            durationMs = dur,
        )
        _segments.trySend(Segment(generation, index, t, dur))
    }

    /** 结束当前朗读（清空正在念的一句，窗口自己超时隐藏）。 */
    fun onIdle() {
        val cur = _state.value
        if (cur.text.isEmpty()) return
        _state.value = cur.copy(index = -1, text = "", updatedAt = System.currentTimeMillis())
    }

    private const val MS_PER_CHAR = 235L
    private const val MS_PER_PUNCT = 160L
    private const val MIN_DURATION_MS = 1200L
    private const val MAX_DURATION_MS = 30000L

    /**
     * 按字数粗估这一句要念多久。只在音频长度拿不到时兜底。
     * 宁可估长一点——估短了会读一半就消失。
     */
    fun estimateDurationMs(text: String, speed: Float = 1f): Long {
        if (text.isBlank()) return 0L
        val chars = text.count { !it.isWhitespace() }
        val puncts = text.count { it in "。？！?!；;：:，,、…—" }
        val rawMs = chars.toLong() * MS_PER_CHAR + puncts.toLong() * MS_PER_PUNCT
        val s = if (speed <= 0.01f) 1f else speed
        return (rawMs / s).toLong().coerceIn(MIN_DURATION_MS, MAX_DURATION_MS)
    }
}
