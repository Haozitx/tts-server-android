
package com.github.jing332.tts_server_android.lyric

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.github.jing332.tts_server_android.compose.MainActivity

/** 悬浮歌词条的设置页。入口：长按歌词条，或 `am start` 直接拉起。 */
class LyricSettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "悬浮歌词设置"
        setContent {
            MaterialTheme(
                colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            ) {
                LyricSettingsScreen()
            }
        }
    }
}

@Composable
private fun LyricSettingsScreen() {
    val ctx = LocalContext.current

    var enabled by remember { mutableStateOf(LyricPrefs.isEnabled(ctx)) }
    var showPrev by remember { mutableStateOf(LyricPrefs.isShowPrev(ctx)) }
    var showNext by remember { mutableStateOf(LyricPrefs.isShowNext(ctx)) }
    var locked by remember { mutableStateOf(LyricPrefs.isLocked(ctx)) }
    var fontScale by remember { mutableStateOf(LyricPrefs.fontScale(ctx)) }
    var fontFamily by remember { mutableStateOf(LyricPrefs.fontFamily(ctx)) }
    var colorMode by remember { mutableStateOf(LyricPrefs.colorMode(ctx)) }
    var bgAlpha by remember { mutableStateOf(LyricPrefs.bgAlpha(ctx)) }
    var granted by remember { mutableStateOf(LyricOverlay.canDrawOverlay(ctx)) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text("悬浮歌词", style = MaterialTheme.typography.headlineSmall)
        Text(
            "朗读时把正在念的这一句浮在屏幕上层。需要「显示在其他应用上层」权限。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(8.dp))
        HorizontalDivider()

        SwitchRow("启用悬浮歌词", null, enabled) {
            enabled = it
            LyricPrefs.setEnabled(ctx, it)
            LyricOverlay.refresh()
        }
        SwitchRow("显示上一句", "只多显示一行文字，不影响朗读内容", showPrev) {
            showPrev = it
            LyricPrefs.setShowPrev(ctx, it)
            LyricOverlay.refresh()
        }
        SwitchRow("显示下一句", null, showNext) {
            showNext = it
            LyricPrefs.setShowNext(ctx, it)
            LyricOverlay.refresh()
        }
        SwitchRow("锁定位置", "锁定后无法拖动和长按，触摸穿透到下层；要解锁回本页关掉", locked) {
            locked = it
            LyricPrefs.setLocked(ctx, it)
            LyricOverlay.refresh()
        }

        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        SectionTitle("字号　" + (fontScale * 100).toInt() + "%")
        Slider(
            value = fontScale,
            onValueChange = {
                fontScale = it
                LyricPrefs.setFontScale(ctx, it)
            },
            onValueChangeFinished = { LyricOverlay.refresh() },
            valueRange = 0.6f..2.0f,
            steps = 13
        )

        SectionTitle("字体")
        RadioRow("系统默认", fontFamily == LyricPrefs.FONT_DEFAULT) {
            fontFamily = LyricPrefs.FONT_DEFAULT
            LyricPrefs.setFontFamily(ctx, fontFamily)
            LyricOverlay.refresh()
        }
        RadioRow("无衬线", fontFamily == LyricPrefs.FONT_SANS) {
            fontFamily = LyricPrefs.FONT_SANS
            LyricPrefs.setFontFamily(ctx, fontFamily)
            LyricOverlay.refresh()
        }
        RadioRow("衬线", fontFamily == LyricPrefs.FONT_SERIF) {
            fontFamily = LyricPrefs.FONT_SERIF
            LyricPrefs.setFontFamily(ctx, fontFamily)
            LyricOverlay.refresh()
        }
        RadioRow("等宽", fontFamily == LyricPrefs.FONT_MONO) {
            fontFamily = LyricPrefs.FONT_MONO
            LyricPrefs.setFontFamily(ctx, fontFamily)
            LyricOverlay.refresh()
        }

        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        SectionTitle("文字颜色")
        RadioRow("跟随系统深色模式（深色＝白字，浅色＝黑字）", colorMode == LyricPrefs.COLOR_FOLLOW_SYSTEM) {
            colorMode = LyricPrefs.COLOR_FOLLOW_SYSTEM
            LyricPrefs.setColorMode(ctx, colorMode)
            LyricOverlay.refresh()
        }
        RadioRow("始终白色", colorMode == LyricPrefs.COLOR_WHITE) {
            colorMode = LyricPrefs.COLOR_WHITE
            LyricPrefs.setColorMode(ctx, colorMode)
            LyricOverlay.refresh()
        }
        RadioRow("始终黑色", colorMode == LyricPrefs.COLOR_BLACK) {
            colorMode = LyricPrefs.COLOR_BLACK
            LyricPrefs.setColorMode(ctx, colorMode)
            LyricOverlay.refresh()
        }

        SectionTitle(if (bgAlpha == 0) "背景　纯透明" else "背景　不透明度 " + (bgAlpha * 100 / 255) + "%")
        Slider(
            value = bgAlpha.toFloat(),
            onValueChange = {
                bgAlpha = it.toInt()
                LyricPrefs.setBgAlpha(ctx, bgAlpha)
            },
            onValueChangeFinished = { LyricOverlay.refresh() },
            valueRange = 0f..255f,
            steps = 0
        )

        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        SectionTitle(
            when {
                !granted -> "状态：还没给悬浮窗权限，歌词不会显示"
                !enabled -> "状态：权限已给，但悬浮歌词关着"
                else -> "状态：就绪。拖动可移动，长按进本页"
            }
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { granted = LyricOverlay.canDrawOverlay(ctx) }) {
                Text("重新检测")
            }
            if (!granted) {
                Button(onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        runCatching {
                            ctx.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:" + ctx.packageName)
                                )
                            )
                        }
                    }
                }) {
                    Text("授予悬浮窗权限")
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                LyricOverlay.preview(ctx, "测试一句：御坂10032号把语音合成时的文本像歌词一样浮在屏幕上面。")
            }) {
                Text("测试显示")
            }
            OutlinedButton(onClick = { LyricOverlay.hide() }) {
                Text("立即隐藏")
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Spacer(Modifier.height(10.dp))
    Text(text, style = MaterialTheme.typography.titleSmall)
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun RadioRow(title: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(title, style = MaterialTheme.typography.bodyMedium)
    }
}
