package com.github.jing332.tts.lyric

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 朗读文本总线。
 *
 * 合成管线把每一句的显示文本、这一句在音频里的真实长度、这一句前面已播完的音频总长、
 * 以及这一句所在那一段的全文推到这里，悬浮条订阅它。
 * 放在 lib-tts 里，任何走合成器的入口（系统 TTS、HTTP 服务、转发器）都能被覆盖。
 *
 * [Segment.offsetMs] 是这一版的关键：它把「这一句该什么时候显示」变成与声音同源的时间，
 * 而不是「数据什么时候到就什么时候显示」。
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

    /**
     * 一句朗读。
     *
     * @param offsetMs 这一句之前已经播完的音频总长。显示时刻 = 本段第一句的起点 + offsetMs。
     * @param list 这一句所在那一段的全文，窗口拿它直接取上一句／下一句。
     */
    data class Segment(
        val index: Int,
        val text: String,
        val durationMs: Long,
        val offsetMs: Long = 0L,
        val list: List<String> = emptyList(),
        val updatedAt: Long = 0L,
    )

    private val _state = MutableStateFlow(LyricState())

    val state: StateFlow<LyricState> get() = _state

    private val _segments = Channel<Segment>(Channel.UNLIMITED)

    /** 逐句事件流。队列无上限，只进不丢。 */
    val segments: ReceiveChannel<Segment> get() = _segments

    /** 当前这一段（一次合成请求）切分出来的全部句子。 */
    private var currentList: List<String> = emptyList()

    /** 一段文本被切分出来：列表换新，已经排队的句子不动。 */
    fun onSegments(displays: List<String>) {
        val list = displays.map { it.trim() }.filter { it.isNotEmpty() }
        if (list.isEmpty()) return
        currentList = list
        _state.value = LyricState(segments = list, index = -1)
    }

    /**
     * 第 index 句的音频开始产出（第一个字节）时调用。
     *
     * @param durationMs 这一句音频长度；第一字节时还不知道就传 0，按字数估。
     */
    fun onSegment(
        index: Int,
        text: String,
        durationMs: Long = 0L,
        offsetMs: Long = 0L,
    ) {
        val t = text.trim()
        if (t.isEmpty()) return
        val dur = if (durationMs > 0L) durationMs else estimateDurationMs(t)
        _state.value = _state.value.copy(
            index = index,
            text = t,
            updatedAt = System.currentTimeMillis(),
            durationMs = dur,
        )
        _segments.trySend(
            Segment(
                index = index,
                text = t,
                durationMs = dur,
                offsetMs = offsetMs,
                list = currentList,
                updatedAt = System.currentTimeMillis(),
            )
        )
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
