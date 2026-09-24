package com.github.jing332.tts.lyric

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 朗读文本总线。
 *
 * 合成管线每念到一句就推到这里，App 侧的悬浮歌词条订阅它。
 * 放在 lib-tts 里，任何走合成器的入口（系统 TTS、HTTP 服务、转发器）都能被覆盖。
 */
object LyricBus {

    /** 当前朗读状态：整段被切分出的片段列表 + 正在念第几片。 */
    data class LyricState(
        val segments: List<String> = emptyList(),
        val index: Int = -1,
        val text: String = "",
        val updatedAt: Long = 0L,
    ) {
        val previous: String get() = segments.getOrNull(index - 1).orEmpty()
        val next: String get() = segments.getOrNull(index + 1).orEmpty()
    }

    private val _state = MutableStateFlow(LyricState())

    val state: StateFlow<LyricState> get() = _state

    /** 一段文本被切分成多个朗读片段。 */
    fun onSegments(segments: List<String>) {
        val list = segments.map { it.trim() }.filter { it.isNotEmpty() }
        if (list.isEmpty()) return
        _state.value = _state.value.copy(segments = list, index = -1)
    }

    /** 开始朗读第 index 个片段。 */
    fun onSegment(index: Int, text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        _state.value = _state.value.copy(
            index = index,
            text = t,
            updatedAt = System.currentTimeMillis()
        )
    }

    /** 结束当前朗读（清空正在念的一句，窗口自己超时隐藏）。 */
    fun onIdle() {
        val cur = _state.value
        if (cur.text.isEmpty()) return
        _state.value = cur.copy(index = -1, text = "", updatedAt = System.currentTimeMillis())
    }
}
