package com.github.jing332.tts_server_android.lyric

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/** 悬浮歌词条的设置页。入口：长按歌词条，或用 `am start` 直接拉起。 */
class LyricSettingsActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var permissionButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "悬浮歌词设置"

        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        fun sectionText(text: String) = TextView(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, dp(14), 0, dp(4))
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }

        root.addView(TextView(this).apply {
            text = "朗读时把正在念的这一句浮在屏幕上层（需要「显示在其他应用上层」权限）。"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        })

        root.addView(Switch(this).apply {
            text = "启用悬浮歌词"
            isChecked = LyricPrefs.isEnabled(this@LyricSettingsActivity)
            gravity = Gravity.START
            setOnCheckedChangeListener { _, checked ->
                LyricPrefs.setEnabled(this@LyricSettingsActivity, checked)
                if (!checked) LyricOverlay.hide()
                refreshState()
            }
        })

        root.addView(Switch(this).apply {
            text = "显示上一句 / 下一句"
            isChecked = LyricPrefs.isThreeLine(this@LyricSettingsActivity)
            gravity = Gravity.START
            setOnCheckedChangeListener { _, checked ->
                LyricPrefs.setThreeLine(this@LyricSettingsActivity, checked)
            }
        })

        val scaleLabel = sectionText(scaleText(LyricPrefs.fontScale(this)))
        root.addView(scaleLabel)

        root.addView(SeekBar(this).apply {
            max = 100
            progress = (((LyricPrefs.fontScale(this@LyricSettingsActivity) - 0.6f) / 1.4f) * 100f)
                .coerceIn(0f, 100f).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val scale = 0.6f + progress / 100f * 1.4f
                    LyricPrefs.setFontScale(this@LyricSettingsActivity, scale)
                    scaleLabel.text = scaleText(scale)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}

                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        })

        statusText = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, dp(16), 0, dp(4))
        }
        root.addView(statusText)

        permissionButton = Button(this).apply {
            text = "授予悬浮窗权限"
            setOnClickListener {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    runCatching {
                        startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:$packageName")
                            )
                        )
                    }
                }
            }
        }
        root.addView(permissionButton)

        root.addView(Button(this).apply {
            text = "测试显示一句"
            setOnClickListener { LyricOverlay.preview(this@LyricSettingsActivity, "御坂10032号测试悬浮歌词。") }
        })

        root.addView(Button(this).apply {
            text = "立即隐藏"
            setOnClickListener { LyricOverlay.hide() }
        })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        refreshState()
    }

    private fun scaleText(scale: Float) = "字号：${(scale * 100).toInt()}%"

    private fun refreshState() {
        val granted = LyricOverlay.canDrawOverlay(this)
        permissionButton.visibility = if (granted) View.GONE else View.VISIBLE
        statusText.text = when {
            !granted -> "状态：未授予悬浮窗权限，歌词不会显示。"
            !LyricPrefs.isEnabled(this) -> "状态：权限已授予，但悬浮歌词已关闭。"
            else -> "状态：就绪。拖动可移动，点一下切换单行／三行，长按回到本页。"
        }
    }
}
