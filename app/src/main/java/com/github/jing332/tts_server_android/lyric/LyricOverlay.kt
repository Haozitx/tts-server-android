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
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 悬浮歌词条：把正在朗读的那一句浮在屏幕上层，像桌面歌词一样逐句走。
 *
 * 用系统窗口（WindowManager + TYPE_APPLICATION_OVERLAY）实现，不额外依赖第三方悬浮窗库、
 * 不额外起常驻服务：App 进程活着的时候随朗读数据出现，停读后自动移除。
 *
 * 三条约定：
 *  1. 什么时候换句由「这一句的音频长度」决定，不由数据到达决定——
 *     合成和写播放器缓冲都比实际播放快，按到达切必然抢拍。
 *  2. 每一句按自己的音频长度停留，读完之前不会消失。
 *  3. 条占满屏宽（这样一行能放下更多字），只能上下拖；左右由档位决定文字怎么排。
 */
object LyricOverlay {
    /** 拿不到时长时的兜底停留时间。 */
    private const val HIDE_DELAY_MS = 8_000L

    /** 按音频长度停留之外再宽限一会儿，免得最后一个字被切掉。 */
    private const val HIDE_EXTRA_MS = 1_200L

    private const val LONG_PRESS_MS = 500L
    private const val TOUCH_SLOP = 12f
    private const val BASE_SP = 18f
    private const val MAX_LINES = 4

    /** 条左右各留一点，免得字贴到屏幕边。 */
    private const val SIDE_PADDING_DP = 14

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var hideJob: Job? = null

    private var appContext: Context? = null
    private var windowManager: WindowManager? = null

    private var rootView: LinearLayout? = null
    private var prevView: TextView? = null
    private var currentView: TextView? = null
    private var nextView: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    /** 时间轴：这一轮朗读从哪个时刻起算，以及已经往下排了多少毫秒。 */
    private var timelineBase = 0L
    private var timelineCursor = 0L

    private var downRawY = 0f
    private var downY = 0
    private var downTime = 0L
    private var dragging = false

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            hide()
        }
    }

    val isShowing: Boolean get() = rootView?.isAttachedToWindow == true

    /** 由 LyricInitProvider 在进程启动时调用，开始订阅朗读数据。 */
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
            for (seg in LyricBus.segments) {
                if (!LyricPrefs.isEnabled(ctx)) continue
                // 已经换了一轮朗读，之前积压的句子丢掉
                if (seg.generation != LyricBus.currentGeneration()) continue
                showOnTimeline(seg)
            }
        }
    }

    /**
     * 按音频长度排队显示。
     *
     * 每一句什么时候出现，由前面几句音频长度的累加值决定，而不是「数据到了就切」。
     * 数据比声音跑得快得多，只有按时长排队才跟得上播放。
     */
    private suspend fun showOnTimeline(seg: LyricBus.Segment) {
        val now = SystemClock.uptimeMillis()
        if (seg.index <= 0 || timelineBase == 0L) {
            timelineBase = now
            timelineCursor = 0L
        }
        val wait = timelineBase + timelineCursor - now
        if (wait > 0) delay(wait)
        timelineCursor += seg.durationMs
        render(seg.index, seg.text, seg.durationMs)
    }

    /** 设置页的「测试显示」用，不走总线直接渲染一句。 */
    fun preview(context: Context, text: String) {
        init(context)
        val dur = LyricBus.estimateDurationMs(text)
        timelineBase = SystemClock.uptimeMillis()
        timelineCursor = dur
        render(0, text, dur)
    }

    /** 设置项改完以后立即重画，不用等下一句。 */
    fun refresh() {
        val ctx = appContext ?: return
        if (!LyricPrefs.isEnabled(ctx)) {
            hide()
            return
        }
        val st = LyricBus.state.value
        if (st.text.isNotBlank()) render(st.index, st.text, st.durationMs)
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
        timelineBase = 0L
        timelineCursor = 0L
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

    /** 文字在条内的水平排布：居左／居中／居右。 */
    private fun textGravity(align: Int): Int = when (align) {
        LyricPrefs.ALIGN_LEFT -> Gravity.START or Gravity.CENTER_VERTICAL
        LyricPrefs.ALIGN_RIGHT -> Gravity.END or Gravity.CENTER_VERTICAL
        else -> Gravity.CENTER
    }

    private fun render(index: Int, text: String, durationMs: Long) {
        val ctx = appContext ?: return
        if (!canDrawOverlay(ctx)) return

        val scale = LyricPrefs.fontScale(ctx)
        val tf = typefaceOf(LyricPrefs.fontFamily(ctx))
        val colorMode = LyricPrefs.colorMode(ctx)
        val textColor = resolveTextColor(ctx, colorMode)
        // 投影一律用黑色：白字靠它压住浅色背景，黑字配黑边就等于没有白边（浅色模式下发白边会发虚）
        val shadowColor = Color.BLACK
        val bgAlpha = LyricPrefs.bgAlpha(ctx)
        val locked = LyricPrefs.isLocked(ctx)
        val showPrev = LyricPrefs.isShowPrev(ctx)
        val showNext = LyricPrefs.isShowNext(ctx)
        val align = LyricPrefs.align(ctx)

        val view = ensureView(ctx)
        val st = LyricBus.state.value
        val prevText = st.segments.getOrNull(index - 1).orEmpty()
        val nextText = st.segments.getOrNull(index + 1).orEmpty()

        val density = ctx.resources.displayMetrics.density
        val screenWidth = ctx.resources.displayMetrics.widthPixels
        val maxW = screenWidth - (SIDE_PADDING_DP * 2 * density).toInt()
        val mainSp = BASE_SP * scale
        val sideSp = mainSp * 0.7f

        currentView?.let { tv ->
            tv.text = text
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, mainSp)
            tv.setTextColor(textColor)
            tv.typeface = tf
            tv.setShadowLayer(6f, 0f, 0f, shadowColor)
            tv.maxWidth = maxW
            tv.gravity = textGravity(align)
            tv.visibility = View.VISIBLE
        }

        prevView?.let { tv ->
            tv.text = prevText
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sideSp)
            tv.setTextColor(textColor)
            tv.typeface = tf
            tv.setShadowLayer(6f, 0f, 0f, shadowColor)
            tv.maxWidth = maxW
            tv.gravity = textGravity(align)
            tv.alpha = 0.6f
            tv.visibility = if (showPrev && prevText.isNotBlank()) View.VISIBLE else View.GONE
        }

        nextView?.let { tv ->
            tv.text = nextText
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sideSp)
            tv.setTextColor(textColor)
            tv.typeface = tf
            tv.setShadowLayer(6f, 0f, 0f, shadowColor)
            tv.maxWidth = maxW
            tv.gravity = textGravity(align)
            tv.alpha = 0.6f
            tv.visibility = if (showNext && nextText.isNotBlank()) View.VISIBLE else View.GONE
        }

        view.background = if (bgAlpha <= 0) null else GradientDrawable().apply {
            cornerRadius = 24f * density
            setColor(bgAlpha shl 24)
        }

        layoutParams?.flags = windowFlags(ctx)

        if (!view.isAttachedToWindow) attach(view) else updateLayout()
        scheduleHide(durationMs, locked)
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
            setPadding(dp(SIDE_PADDING_DP), dp(7), dp(SIDE_PADDING_DP), dp(7))
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
            // 顶格：宽度固定成整屏，不再由文字长短决定——文字短的时候条会缩得很窄，行数就多
            ctx.resources.displayMetrics.widthPixels,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            windowFlags(ctx),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = if (LyricPrefs.hasPosY(ctx)) LyricPrefs.posY(ctx)
            else (ctx.resources.displayMetrics.heightPixels * 0.72f).toInt()
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
        val wm = windowManager ?: return
        val params = layoutParams ?: return
        runCatching { wm.addView(view, params) }
    }

    private fun updateLayout() {
        val view = rootView ?: return
        val params = layoutParams ?: return
        runCatching { windowManager?.updateViewLayout(view, params) }
    }

    private fun scheduleHide(durationMs: Long, locked: Boolean) {
        hideJob?.cancel()
        hideJob = null
        // 钉住的时候不自动隐藏，想看多久就看多久
        if (locked) return
        val hold = if (durationMs > 0L) durationMs + HIDE_EXTRA_MS else HIDE_DELAY_MS
        hideJob = scope.launch {
            delay(hold)
            if (!isLockedNow()) hide()
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
                downRawY = event.rawY
                downY = layoutParams?.y ?: 0
                downTime = SystemClock.uptimeMillis()
                dragging = false
                true
            }

            MotionEvent.ACTION_MOVE -> {
                val dy = event.rawY - downRawY
                if (abs(dy) > TOUCH_SLOP) dragging = true
                val params = layoutParams
                if (dragging && params != null) {
                    // 只跟纵向：横向锁在档位上
                    params.y = (downY + dy).toInt()
                    runCatching { windowManager?.updateViewLayout(view, params) }
                }
                true
            }

            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    val params = layoutParams
                    if (params != null && ctx != null) LyricPrefs.setPosY(ctx, params.y)
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
