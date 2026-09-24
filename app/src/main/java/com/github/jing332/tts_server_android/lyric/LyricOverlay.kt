package com.github.jing332.tts_server_android.lyric

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
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
    private var centered = false

    private var downRawX = 0f
    private var downRawY = 0f
    private var downX = 0
    private var downY = 0
    private var downTime = 0L
    private var dragging = false

    val isShowing: Boolean get() = rootView?.isAttachedToWindow == true

    /** 由 LyricInitProvider 在进程启动时调用，开始订阅朗读文本。 */
    fun init(context: Context) {
        if (appContext != null) return
        val ctx = context.applicationContext
        appContext = ctx
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

    private fun render(state: LyricBus.LyricState) {
        val ctx = appContext ?: return
        if (!canDrawOverlay(ctx)) return

        val scale = LyricPrefs.fontScale(ctx)
        val threeLine = LyricPrefs.isThreeLine(ctx)
        val view = ensureView(ctx)

        currentView?.apply {
            text = state.text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, BASE_SP * scale)
        }

        val sideSp = BASE_SP * scale * 0.72f
        prevView?.apply {
            text = state.previous
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sideSp)
            visibility = if (threeLine && state.previous.isNotBlank()) View.VISIBLE else View.GONE
        }
        nextView?.apply {
            text = state.next
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sideSp)
            visibility = if (threeLine && state.next.isNotBlank()) View.VISIBLE else View.GONE
        }

        if (!view.isAttachedToWindow) attach(view)
        scheduleHide()
    }

    private fun ensureView(ctx: Context): LinearLayout {
        rootView?.let { return it }

        val density = ctx.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        fun lyricTextView() = TextView(ctx).apply {
            setTextColor(Color.WHITE)
            setShadowLayer(6f, 0f, 0f, Color.BLACK)
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            setPadding(dp(4), dp(1), dp(4), dp(1))
        }

        val prev = lyricTextView()
        val current = lyricTextView()
        val next = lyricTextView()

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = 24f * density
                setColor(0x7A000000)
            }
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

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = (ctx.resources.displayMetrics.heightPixels * 0.72f).toInt()
        }

        windowManager = ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager

        rootView = root
        prevView = prev
        currentView = current
        nextView = next
        centered = false
        return root
    }

    private fun attach(view: LinearLayout) {
        val ctx = appContext ?: return
        val wm = windowManager ?: return
        val params = layoutParams ?: return
        runCatching { wm.addView(view, params) }.onFailure { return }

        if (centered) return
        view.post {
            val p = layoutParams ?: return@post
            val width = ctx.resources.displayMetrics.widthPixels
            p.x = ((width - view.width) / 2).coerceAtLeast(0)
            runCatching { wm.updateViewLayout(view, p) }
            centered = true
        }
    }

    private fun scheduleHide() {
        hideJob?.cancel()
        hideJob = scope.launch {
            delay(HIDE_DELAY_MS)
            hide()
        }
    }

    private fun toggleThreeLine(ctx: Context) {
        LyricPrefs.setThreeLine(ctx, !LyricPrefs.isThreeLine(ctx))
        if (lastState.text.isNotBlank()) render(lastState)
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
                if (!dragging) {
                    if (SystemClock.uptimeMillis() - downTime >= LONG_PRESS_MS)
                        openSettings(view.context)
                    else
                        toggleThreeLine(view.context)
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
