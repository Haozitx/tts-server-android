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
 * 画面推进由时间表决定：
 *  - 每一句带 [LyricBus.Segment.offsetMs]（它之前已经播完的音频总长）。
 *  - 一段朗读的第一句到达时定下基准时刻，这一句立即显示——它也是声音开始的那一刻。
 *  - 后面的句子等到「基准 + offsetMs」再显示，因此文本与声音同源，不受 TTS 快慢影响。
 */
object LyricOverlay {
    /** 一句读完之后的宽限时间，用来吸收合成与播放之间的抖动。 */
    private const val HIDE_EXTRA_MS = 2_000L

    /** 拿不到时长时的兜底停留时间。 */
    private const val HIDE_DELAY_MS = 8_000L

    private const val LONG_PRESS_MS = 500L
    private const val TOUCH_SLOP = 12f
    private const val BASE_SP = 18f
    private const val MAX_LINES = 4
    private const val SIDE_PADDING_DP = 14

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var hideJob: Job? = null
    private var advanceJob: Job? = null

    /** 待显示的句子，先进先出。 */
    private val queue = ArrayDeque<LyricBus.Segment>()

    /** 这一段朗读的基准时刻（第一句到达的那一刻＝声音起点）。 */
    private var timelineBase = 0L

    private var prevText = ""
    private var curText = ""
    private var nextText = ""

    /** 上一次显示出去的正文。新一段的第一句没有上一句时，拿它接上。 */
    private var lastShown = ""

    private var shownDuration = 0L

    private var appContext: Context? = null
    private var windowManager: WindowManager? = null

    private var rootView: LinearLayout? = null
    private var prevView: TextView? = null
    private var currentView: TextView? = null
    private var nextView: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

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
                queue.addLast(seg)
                if (advanceJob?.isActive != true) advanceJob = scope.launch { runTimeline() }
            }
        }
    }

    /**
     * 按时间表往下推。
     * 队列空了就退出，画面停在最后一句上，等下一批数据到了再起来——期间不清空、不隐藏。
     */
    private suspend fun runTimeline() {
        while (true) {
            val seg = queue.firstOrNull() ?: break
            queue.removeFirst()
            val now = SystemClock.uptimeMillis()
            // 一段朗读的第一句到达时定基准，它也是声音开始的那一刻
            if (seg.index <= 0 || timelineBase == 0L) timelineBase = now
            val wait = timelineBase + seg.offsetMs - now
            if (wait > 0) delay(wait)
            show(seg)
        }
    }

    private fun show(seg: LyricBus.Segment) {
        val list = seg.list
        val hasPrev = seg.index > 0 && seg.index - 1 < list.size
        val hasNext = seg.index + 1 < list.size
        prevText = if (hasPrev) list[seg.index - 1] else lastShown
        nextText = if (hasNext) list[seg.index + 1] else ""
        curText = seg.text
        shownDuration = seg.durationMs
        lastShown = seg.text
        paint()
        scheduleHide()
    }

    /** 设置页的「测试显示」用，不走总线直接渲染一句。 */
    fun preview(context: Context, text: String) {
        init(context)
        prevText = ""
        nextText = ""
        curText = text
        shownDuration = LyricBus.estimateDurationMs(text)
        lastShown = text
        timelineBase = SystemClock.uptimeMillis()
        paint()
        scheduleHide()
    }

    /** 设置项改完以后立即重画，不用等下一句。 */
    fun refresh() {
        val ctx = appContext ?: return
        if (!LyricPrefs.isEnabled(ctx)) {
            hide()
            return
        }
        if (curText.isNotBlank()) paint()
    }

    fun hide() {
        hideJob?.cancel()
        hideJob = null
        advanceJob?.cancel()
        advanceJob = null
        queue.clear()
        val view = rootView
        if (view != null) {
            runCatching {
                if (view.isAttachedToWindow) windowManager?.removeView(view)
            }
        }
        rootView = null
        prevView = null
        currentView = null
        nextView = null
        layoutParams = null
        curText = ""
        prevText = ""
        nextText = ""
        lastShown = ""
        shownDuration = 0L
        timelineBase = 0L
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

    private fun paint() {
        val ctx = appContext ?: return
        if (!canDrawOverlay(ctx)) return

        val scale = LyricPrefs.fontScale(ctx)
        val tf = typefaceOf(LyricPrefs.fontFamily(ctx))
        val textColor = resolveTextColor(ctx, LyricPrefs.colorMode(ctx))
        // 投影一律用黑色。黑字配黑边等于没有白边——浅色模式下发虚的白边正是之前看不清的原因
        val shadowColor = Color.BLACK
        val bgAlpha = LyricPrefs.bgAlpha(ctx)
        val showPrev = LyricPrefs.isShowPrev(ctx)
        val showNext = LyricPrefs.isShowNext(ctx)
        val align = LyricPrefs.align(ctx)

        val view = ensureView(ctx)
        val density = ctx.resources.displayMetrics.density
        val screenWidth = ctx.resources.displayMetrics.widthPixels
        val maxW = screenWidth - (SIDE_PADDING_DP * 2 * density).toInt()
        val mainSp = BASE_SP * scale
        val sideSp = mainSp * 0.7f

        currentView?.let { tv ->
            tv.text = curText
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
            // 顶格：宽度固定成整屏，不再由文字长短决定
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

    private fun scheduleHide() {
        hideJob?.cancel()
        hideJob = null
        val ctx = appContext ?: return
        // 钉住的时候不自动隐藏，想看多久就看多久
        if (LyricPrefs.isLocked(ctx)) return
        val hold = if (shownDuration > 0L) shownDuration + HIDE_EXTRA_MS else HIDE_DELAY_MS
        hideJob = scope.launch {
            delay(hold)
            // 后面还有排队的句子就不隐藏，免得中间闪一下
            if (queue.isEmpty() && !isLockedNow()) hide()
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
