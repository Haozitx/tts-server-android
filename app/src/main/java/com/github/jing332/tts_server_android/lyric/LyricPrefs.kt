
package com.github.jing332.tts_server_android.lyric

import android.content.Context

/** 悬浮歌词条的偏好设置。 */
object LyricPrefs {
    private const val NAME = "lyric_overlay"

    private const val KEY_ENABLED = "enabled"
    private const val KEY_SHOW_PREV = "show_prev"
    private const val KEY_SHOW_NEXT = "show_next"
    private const val KEY_FONT_SCALE = "font_scale"
    private const val KEY_FONT_FAMILY = "font_family"
    private const val KEY_COLOR_MODE = "color_mode"
    private const val KEY_BG_ALPHA = "bg_alpha"
    private const val KEY_LOCKED = "locked"
    private const val KEY_POS_X = "pos_x"
    private const val KEY_POS_Y = "pos_y"

    const val COLOR_FOLLOW_SYSTEM = 0
    const val COLOR_WHITE = 1
    const val COLOR_BLACK = 2

    const val FONT_DEFAULT = 0
    const val FONT_SANS = 1
    const val FONT_SERIF = 2
    const val FONT_MONO = 3

    private fun sp(ctx: Context) = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** 默认开启：装着就能用，不装也只是一次渲染的事。 */
    fun isEnabled(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_ENABLED, true)

    fun setEnabled(ctx: Context, value: Boolean) =
        sp(ctx).edit().putBoolean(KEY_ENABLED, value).apply()

    /** 显示上一句（只多显示一行文字，不影响朗读内容）。 */
    fun isShowPrev(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_SHOW_PREV, true)

    fun setShowPrev(ctx: Context, value: Boolean) =
        sp(ctx).edit().putBoolean(KEY_SHOW_PREV, value).apply()

    /** 显示下一句。 */
    fun isShowNext(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_SHOW_NEXT, true)

    fun setShowNext(ctx: Context, value: Boolean) =
        sp(ctx).edit().putBoolean(KEY_SHOW_NEXT, value).apply()

    /** 字号倍率，0.6 ～ 2.0。 */
    fun fontScale(ctx: Context): Float = sp(ctx).getFloat(KEY_FONT_SCALE, 1f)

    fun setFontScale(ctx: Context, value: Float) =
        sp(ctx).edit().putFloat(KEY_FONT_SCALE, value).apply()

    fun fontFamily(ctx: Context): Int = sp(ctx).getInt(KEY_FONT_FAMILY, FONT_DEFAULT)

    fun setFontFamily(ctx: Context, value: Int) =
        sp(ctx).edit().putInt(KEY_FONT_FAMILY, value).apply()

    fun colorMode(ctx: Context): Int = sp(ctx).getInt(KEY_COLOR_MODE, COLOR_FOLLOW_SYSTEM)

    fun setColorMode(ctx: Context, value: Int) =
        sp(ctx).edit().putInt(KEY_COLOR_MODE, value).apply()

    /** 背景黑度：0＝纯透明。 */
    fun bgAlpha(ctx: Context): Int = sp(ctx).getInt(KEY_BG_ALPHA, 0)

    fun setBgAlpha(ctx: Context, value: Int) =
        sp(ctx).edit().putInt(KEY_BG_ALPHA, value.coerceIn(0, 255)).apply()

    /** 锁定后不可拖动、不可点击，触摸直接穿透到下层应用。 */
    fun isLocked(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_LOCKED, false)

    fun setLocked(ctx: Context, value: Boolean) =
        sp(ctx).edit().putBoolean(KEY_LOCKED, value).apply()

    fun posX(ctx: Context): Int = sp(ctx).getInt(KEY_POS_X, Int.MIN_VALUE)

    fun posY(ctx: Context): Int = sp(ctx).getInt(KEY_POS_Y, Int.MIN_VALUE)

    fun hasPosition(ctx: Context): Boolean = posX(ctx) != Int.MIN_VALUE && posY(ctx) != Int.MIN_VALUE

    fun setPosition(ctx: Context, x: Int, y: Int) {
        sp(ctx).edit().putInt(KEY_POS_X, x).putInt(KEY_POS_Y, y).apply()
    }
}
