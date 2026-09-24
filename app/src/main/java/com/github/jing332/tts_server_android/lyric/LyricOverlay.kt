
package com.github.jing332.tts_server_android.lyric

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.github.jing332.tts.lyric.LyricBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 悬浮歌词条：把正在朗读的那一句浮在屏幕上层，像桌面歌词一样逐句走。
 *
 * 用系统窗口（WindowManager + TYPE_APPLICATION_OVERLAY）实现，不额外依赖第三方悬浮窗库、
 * 不额外起常驻服务：App 进程活着的时候随朗读数据出现，停止朗读约 8 秒后自动移除。
 */
object LyricOverlay {
    private const val HIDE_DELAY_MS = 8_000L
    private const val LONG_PRESS_MS = 500L
    private const val TOUCH_SLOP = 12f
    private const val BASE_SP = 18f

    /** 长句折行：文字最多占屏宽的九成，超了自动换行。 */
    private const val MAX_WIDTH_RATIO = 0.9f
    private const val MAX_LINES = 4

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var hideJob: Job? = null

    private var appContext: Context? = null
    private var windowManager: WindowManager? = null

    private var rootView: LinearLayout? = null
    private var prevView: TextView? = null
    private var currentView: TextView? = null
    private var nextView: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private var lastState = LyricBus.LyricState()

    private var downRawX = 0f
    private var downRawY = 0f
    private var downX = 0
    private var downY = 0
    private var downTime = 0L
    private var dragging = false

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            hide()
        }
    }

    val isShowing: Boolean get() = rootView?.isAttachedToWindow == true

    /** 由 LyricInitProvider 在进程启动时调用，开始订阅朗读文本。 */
    fun init(context: Context) {
        if (appContext != null) return
        val ctx = context.applicationContext
        appContext = ctx
        runCatching {
            ContextCompat.registerReceiver(
                ctx, screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }
        scope.launch {
            LyricBus.state.collectLatest { state ->
                lastState = state
                if (state.text.isBlank()) return@collectLatest
                if (!LyricPrefs.isEnabled(ctx)) return@collectLatest
                render(state)
            }
        }
    }

    /** 设置页的「测试显示」用，绕过总线直接渲染一句。 */
    fun preview(context: Context, text: String) {
        init(context)
        lastState = LyricBus.LyricState(
            segments = listOf(text),
            index = 0,
            text = text,
            updatedAt = System.currentTimeMillis()
        )
        render(lastState)
    }

    /** 设置项改完以后立即重画，不用等下一句。 */
    fun refresh() {
        val ctx = appContext ?: return
        if (!LyricPrefs.isEnabled(ctx)) {
            hide()
            return
        }
        if (lastState.text.isNotBlank()) render(lastState)
    }

    fun hide() {
        hideJob?.cancel()
        hideJob = null
        val view = rootView ?: return
        runCatching {
            if (view.isAttachedToWindow) windowManager?.removeView(view)
        }
        rootView = null
        prevView = null
        currentView = null
        nextView = null
        layoutParams = null
    }

    fun canDrawOverlay(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    private fun isNight(ctx: Context): Boolean =
        (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private fun resolveTextColor(ctx: Context, mode: Int): Int = when (mode) {
        LyricPrefs.COLOR_WHITE -> Color.WHITE
        LyricPrefs.COLOR_BLACK -> Color.BLACK
        else -> if (isNight(ctx)) Color.WHITE else Color.BLACK
    }

    private fun typefaceOf(family: Int): Typeface = when (family) {
        LyricPrefs.FONT_SANS -> Typeface.SANS_SERIF
        LyricPrefs.FONT_SERIF -> Typeface.SERIF
        LyricPrefs.FONT_MONO -> Typeface.MONOSPACE
        else -> Typeface.DEFAULT
    }

    private fun windowFlags(ctx: Context?): Int {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        if (ctx != null && LyricPrefs.isLocked(ctx))
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return flags
    }

    private fun render(state: LyricBus.LyricState) {
        val ctx = appContext ?: return
        if (!canDrawOverlay(ctx)) return

        val scale = LyricPrefs.fontScale(ctx)
        val tf = typefaceOf(LyricPrefs.fontFamily(ctx))
        val colorMode = LyricPrefs.colorMode(ctx)
        val textColor = resolveTextColor(ctx, colorMode)
        val shadowColor = if (textColor == Color.BLACK) Color.WHITE else Color.BLACK
        val bgAlpha = LyricPrefs.bgAlpha(ctx)
        val locked = LyricPrefs.isLocked(ctx)
        val showPrev = LyricPrefs.isShowPrev(ctx)
        val showNext = LyricPrefs.isShowNext(ctx)

        val view = ensureView(ctx)
        val maxW = (ctx.resources.displayMetrics.widthPixels * MAX_WIDTH_RATIO).toInt()
        val mainSp = BASE_SP * scale
        val sideSp = mainSp * 0.7f

        currentView?.let { tv ->
            tv.text = state.text
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, mainSp)
            tv.setTextColor(textColor)
            tv.typeface = tf
            tv.setShadowLayer(6f, 0f, 0f, shadowColor)
            tv.maxWidth = maxW
        }

        prevView?.let { tv ->
            tv.text = state.previous
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sideSp)
            tv.setTextColor(textColor)
            tv.typeface = tf
            tv.setShadowLayer(6f, 0f, 0f, shadowColor)
            tv.maxWidth = maxW
            tv.alpha = 0.6f
            tv.visibility = if (showPrev && state.previous.isNotBlank()) View.VISIBLE else View.GONE
        }

        nextView?.let { tv ->
            tv.text = state.next
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sideSp)
            tv.setTextColor(textColor)
            tv.typeface = tf
            tv.setShadowLayer(6f, 0f, 0f, shadowColor)
            tv.maxWidth = maxW
            tv.alpha = 0.6f
            tv.visibility = if (showNext && state.next.isNotBlank()) View.VISIBLE else View.GONE
        }

        view.background = if (bgAlpha <= 0) null else GradientDrawable().apply {
            cornerRadius = 24f * ctx.resources.displayMetrics.density
            setColor(bgAlpha shl 24)
        }

        layoutParams?.flags = windowFlags(ctx)

        if (!view.isAttachedToWindow) attach(view) else updateLayout()
        scheduleHide(locked)
    }

    private fun ensureView(ctx: Context): LinearLayout {
        rootView?.let { return it }

        val density = ctx.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        fun lyricTextView() = TextView(ctx).apply {
            setTextColor(Color.WHITE)
            setShadowLayer(6f, 0f, 0f, Color.BLACK)
            gravity = Gravity.CENTER
            maxLines = MAX_LINES
            ellipsize = null
            includeFontPadding = false
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(dp(6), dp(1), dp(6), dp(1))
        }

        val prev = lyricTextView()
        val current = lyricTextView()
        val next = lyricTextView()

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(7), dp(14), dp(7))
            addView(prev)
            addView(current)
            addView(next)
            setOnTouchListener(touchListener)
        }

        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            windowFlags(ctx),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (LyricPrefs.hasPosition(ctx)) {
                x = LyricPrefs.posX(ctx)
                y = LyricPrefs.posY(ctx)
            } else {
                x = 0
                y = (ctx.resources.displayMetrics.heightPixels * 0.72f).toInt()
            }
        }

        windowManager = ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager

        rootView = root
        prevView = prev
        currentView = current
        nextView = next
        layoutParams = params
        return root
    }

    private fun attach(view: LinearLayout) {
        val ctx = appContext ?: return
        val wm = windowManager ?: return
        val params = layoutParams ?: return
        runCatching { wm.addView(view, params) }.onFailure { return }

        if (LyricPrefs.hasPosition(ctx)) return
        view.post {
            val p = layoutParams ?: return@post
            val screenWidth = ctx.resources.displayMetrics.widthPixels
            p.x = ((screenWidth - view.width) / 2).coerceAtLeast(0)
            runCatching { wm.updateViewLayout(view, p) }
        }
    }

    private fun updateLayout() {
        val view = rootView ?: return
        val params = layoutParams ?: return
        runCatching { windowManager?.updateViewLayout(view, params) }
    }

    private fun scheduleHide(locked: Boolean) {
        hideJob?.cancel()
        hideJob = scope.launch {
            delay(HIDE_DELAY_MS)
            if (locked || !isLockedNow()) hide()
        }
    }

    private fun isLockedNow(): Boolean {
        val ctx = appContext ?: return false
        return LyricPrefs.isLocked(ctx)
    }

    private fun openSettings(ctx: Context) {
        runCatching {
            ctx.startActivity(
                Intent(ctx, LyricSettingsActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
        }
    }

    private val touchListener = View.OnTouchListener { view, event ->
        val ctx = appContext
        if (ctx != null && LyricPrefs.isLocked(ctx)) return@OnTouchListener false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                downX = layoutParams?.x ?: 0
                downY = layoutParams?.y ?: 0
                downTime = SystemClock.uptimeMillis()
                dragging = false
                true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (abs(dx) > TOUCH_SLOP || abs(dy) > TOUCH_SLOP) dragging = true
                val params = layoutParams
                if (dragging && params != null) {
                    params.x = downX + dx.toInt()
                    params.y = downY + dy.toInt()
                    runCatching { windowManager?.updateViewLayout(view, params) }
                }
                true
            }

            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    val params = layoutParams
                    if (params != null && ctx != null) LyricPrefs.setPosition(ctx, params.x, params.y)
                } else if (SystemClock.uptimeMillis() - downTime >= LONG_PRESS_MS) {
                    openSettings(view.context)
                }
                dragging = false
                true
            }

            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                true
            }

            else -> false
        }
    }
}
