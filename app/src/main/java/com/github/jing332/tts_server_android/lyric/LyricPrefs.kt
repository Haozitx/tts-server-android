package com.github.jing332.tts_server_android.lyric

import android.content.Context

/** 悬浮歌词条的偏好设置。 */
object LyricPrefs {
    private const val NAME = "lyric_overlay"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_THREE_LINE = "three_line"
    private const val KEY_FONT_SCALE = "font_scale"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** 默认开启：装着就能用，不装也只是一次渲染的事。 */
    fun isEnabled(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_ENABLED, true)

    fun setEnabled(ctx: Context, value: Boolean) =
        sp(ctx).edit().putBoolean(KEY_ENABLED, value).apply()

    /** 是否显示上一句 / 下一句。 */
    fun isThreeLine(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_THREE_LINE, true)

    fun setThreeLine(ctx: Context, value: Boolean) =
        sp(ctx).edit().putBoolean(KEY_THREE_LINE, value).apply()

    fun fontScale(ctx: Context): Float = sp(ctx).getFloat(KEY_FONT_SCALE, 1f)

    fun setFontScale(ctx: Context, value: Float) =
        sp(ctx).edit().putFloat(KEY_FONT_SCALE, value).apply()
}
