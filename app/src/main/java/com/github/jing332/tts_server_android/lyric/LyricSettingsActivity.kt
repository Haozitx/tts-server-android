package com.github.jing332.tts_server_android.lyric

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Opacity
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.github.jing332.tts_server_android.R
import com.github.jing332.tts_server_android.compose.settings.BasePreferenceWidget
import com.github.jing332.tts_server_android.compose.settings.DividerPreference
import com.github.jing332.tts_server_android.compose.settings.DropdownPreference
import com.github.jing332.tts_server_android.compose.settings.SliderPreference
import com.github.jing332.tts_server_android.compose.settings.SwitchPreference
import com.github.jing332.tts_server_android.compose.theme.AppTheme

/**
 * 悬浮文本设置页。入口：应用设置里的「悬浮文本设置」，或长按悬浮条。
 *
 * 用应用自带的那套设置组件搭（SwitchPreference／SliderPreference／DropdownPreference／
 * DividerPreference），并套上 AppTheme——之前这页是自己手写的 Material3 组件、
 * 自己配色，所以跟应用其他设置页不是一张脸。
 */
class LyricSettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "悬浮文本"
        setContent {
            AppTheme {
                LyricSettingsPage()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LyricSettingsPage() {
    val ctx = LocalContext.current

    var enabled by remember { mutableStateOf(LyricPrefs.isEnabled(ctx)) }
    var showPrev by remember { mutableStateOf(LyricPrefs.isShowPrev(ctx)) }
    var showNext by remember { mutableStateOf(LyricPrefs.isShowNext(ctx)) }
    var locked by remember { mutableStateOf(LyricPrefs.isLocked(ctx)) }
    var align by remember { mutableStateOf(LyricPrefs.align(ctx)) }
    var fontScale by remember { mutableStateOf(LyricPrefs.fontScale(ctx)) }
    var fontFamily by remember { mutableStateOf(LyricPrefs.fontFamily(ctx)) }
    var colorMode by remember { mutableStateOf(LyricPrefs.colorMode(ctx)) }
    var bgAlpha by remember { mutableStateOf(LyricPrefs.bgAlpha(ctx)) }
    var granted by remember { mutableStateOf(LyricOverlay.canDrawOverlay(ctx)) }

    var alignMenu by remember { mutableStateOf(false) }
    var fontMenu by remember { mutableStateOf(false) }
    var colorMenu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("悬浮文本") },
                navigationIcon = {
                    IconButton(onClick = { (ctx as? Activity)?.finish() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            stringResource(id = R.string.nav_back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            DividerPreference { Text("悬浮歌词") }

            SwitchPreference(
                title = { Text("启用悬浮歌词") },
                subTitle = { Text("朗读时把正在念的这一句浮在屏幕上层") },
                icon = { Icon(Icons.Default.TextFields, null) },
                checked = enabled,
                onCheckedChange = {
                    enabled = it
                    LyricPrefs.setEnabled(ctx, it)
                    LyricOverlay.refresh()
                }
            )

            SwitchPreference(
                title = { Text("显示上一句") },
                subTitle = { Text("本句读满自己的时长就换成上一句，不受下一句是否就绪影响") },
                icon = { Icon(Icons.Default.ArrowUpward, null) },
                checked = showPrev,
                onCheckedChange = {
                    showPrev = it
                    LyricPrefs.setShowPrev(ctx, it)
                    LyricOverlay.refresh()
                }
            )

            SwitchPreference(
                title = { Text("显示下一句") },
                subTitle = { Text("下一句从句子列表直接取，不等它合成出来") },
                icon = { Icon(Icons.Default.ArrowDownward, null) },
                checked = showNext,
                onCheckedChange = {
                    showNext = it
                    LyricPrefs.setShowNext(ctx, it)
                    LyricOverlay.refresh()
                }
            )

            SwitchPreference(
                title = { Text("钉住（锁定位置）") },
                subTitle = { Text("锁上后不能拖动也不能长按，触摸穿透到下层，同时不再自动隐藏。要解锁回本页关掉") },
                icon = { Icon(Icons.Default.Lock, null) },
                checked = locked,
                onCheckedChange = {
                    locked = it
                    LyricPrefs.setLocked(ctx, it)
                    LyricOverlay.refresh()
                }
            )

            DividerPreference { Text("显示") }

            DropdownPreference(
                expanded = alignMenu,
                onExpandedChange = { alignMenu = it },
                icon = { Icon(Icons.Default.FormatAlignCenter, null) },
                title = { Text("横向位置") },
                subTitle = {
                    Text(
                        when (align) {
                            LyricPrefs.ALIGN_LEFT -> "居左"
                            LyricPrefs.ALIGN_RIGHT -> "居右"
                            else -> "居中"
                        }
                    )
                }
            ) {
                DropdownMenuItem(
                    text = { Text("居左") },
                    onClick = {
                        alignMenu = false
                        align = LyricPrefs.ALIGN_LEFT
                        LyricPrefs.setAlign(ctx, align)
                        LyricOverlay.refresh()
                    }
                )
                DropdownMenuItem(
                    text = { Text("居中") },
                    onClick = {
                        alignMenu = false
                        align = LyricPrefs.ALIGN_CENTER
                        LyricPrefs.setAlign(ctx, align)
                        LyricOverlay.refresh()
                    }
                )
                DropdownMenuItem(
                    text = { Text("居右") },
                    onClick = {
                        alignMenu = false
                        align = LyricPrefs.ALIGN_RIGHT
                        LyricPrefs.setAlign(ctx, align)
                        LyricOverlay.refresh()
                    }
                )
            }

            SliderPreference(
                title = { Text("字号") },
                subTitle = { Text((fontScale * 100).toInt().toString() + "%") },
                icon = { Icon(Icons.Default.FormatSize, null) },
                value = fontScale,
                onValueChange = {
                    fontScale = it
                    LyricPrefs.setFontScale(ctx, it)
                    LyricOverlay.refresh()
                },
                valueRange = 0.6f..2.0f,
                steps = 13,
                label = (fontScale * 100).toInt().toString() + "%"
            )

            DropdownPreference(
                expanded = fontMenu,
                onExpandedChange = { fontMenu = it },
                icon = { Icon(Icons.Default.TextFields, null) },
                title = { Text("字体") },
                subTitle = {
                    Text(
                        when (fontFamily) {
                            LyricPrefs.FONT_SANS -> "无衬线"
                            LyricPrefs.FONT_SERIF -> "衬线"
                            LyricPrefs.FONT_MONO -> "等宽"
                            else -> "系统默认"
                        }
                    )
                }
            ) {
                DropdownMenuItem(
                    text = { Text("系统默认") },
                    onClick = {
                        fontMenu = false
                        fontFamily = LyricPrefs.FONT_DEFAULT
                        LyricPrefs.setFontFamily(ctx, fontFamily)
                        LyricOverlay.refresh()
                    }
                )
                DropdownMenuItem(
                    text = { Text("无衬线") },
                    onClick = {
                        fontMenu = false
                        fontFamily = LyricPrefs.FONT_SANS
                        LyricPrefs.setFontFamily(ctx, fontFamily)
                        LyricOverlay.refresh()
                    }
                )
                DropdownMenuItem(
                    text = { Text("衬线") },
                    onClick = {
                        fontMenu = false
                        fontFamily = LyricPrefs.FONT_SERIF
                        LyricPrefs.setFontFamily(ctx, fontFamily)
                        LyricOverlay.refresh()
                    }
                )
                DropdownMenuItem(
                    text = { Text("等宽") },
                    onClick = {
                        fontMenu = false
                        fontFamily = LyricPrefs.FONT_MONO
                        LyricPrefs.setFontFamily(ctx, fontFamily)
                        LyricOverlay.refresh()
                    }
                )
            }

            DropdownPreference(
                expanded = colorMenu,
                onExpandedChange = { colorMenu = it },
                icon = { Icon(Icons.Default.Palette, null) },
                title = { Text("文字颜色") },
                subTitle = {
                    Text(
                        when (colorMode) {
                            LyricPrefs.COLOR_WHITE -> "始终白色"
                            LyricPrefs.COLOR_BLACK -> "始终黑色"
                            else -> "跟随系统深色模式"
                        }
                    )
                }
            ) {
                DropdownMenuItem(
                    text = { Text("跟随系统深色模式") },
                    onClick = {
                        colorMenu = false
                        colorMode = LyricPrefs.COLOR_FOLLOW_SYSTEM
                        LyricPrefs.setColorMode(ctx, colorMode)
                        LyricOverlay.refresh()
                    }
                )
                DropdownMenuItem(
                    text = { Text("始终白色") },
                    onClick = {
                        colorMenu = false
                        colorMode = LyricPrefs.COLOR_WHITE
                        LyricPrefs.setColorMode(ctx, colorMode)
                        LyricOverlay.refresh()
                    }
                )
                DropdownMenuItem(
                    text = { Text("始终黑色") },
                    onClick = {
                        colorMenu = false
                        colorMode = LyricPrefs.COLOR_BLACK
                        LyricPrefs.setColorMode(ctx, colorMode)
                        LyricOverlay.refresh()
                    }
                )
            }

            SliderPreference(
                title = { Text("背景") },
                subTitle = {
                    Text(
                        if (bgAlpha <= 0) "纯透明"
                        else "不透明度 " + (bgAlpha * 100 / 255) + "%"
                    )
                },
                icon = { Icon(Icons.Default.Opacity, null) },
                value = bgAlpha.toFloat(),
                onValueChange = {
                    bgAlpha = it.toInt()
                    LyricPrefs.setBgAlpha(ctx, bgAlpha)
                    LyricOverlay.refresh()
                },
                valueRange = 0f..255f,
                label = if (bgAlpha <= 0) "纯透明" else (bgAlpha * 100 / 255).toString() + "%"
            )

            DividerPreference { Text("状态与测试") }

            BasePreferenceWidget(
                icon = { Icon(Icons.Default.Settings, null) },
                onClick = {
                    granted = LyricOverlay.canDrawOverlay(ctx)
                    if (!granted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        runCatching {
                            ctx.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:" + ctx.packageName)
                                )
                            )
                        }
                    }
                },
                title = { Text(if (granted) "悬浮窗权限已授予" else "授予悬浮窗权限") },
                subTitle = {
                    Text(
                        when {
                            !granted -> "状态：还没给权限，歌词不会显示"
                            !enabled -> "状态：权限已给，但悬浮歌词关着"
                            locked -> "状态：就绪（已钉住）。要拖动先解锁"
                            else -> "状态：就绪。上下拖动可移动，长按进本页"
                        }
                    )
                }
            )

            BasePreferenceWidget(
                icon = { Icon(Icons.Default.TextFields, null) },
                onClick = {
                    LyricOverlay.preview(
                        ctx,
                        "测试一句：御坂19009号把语音合成时的文本像歌词一样浮在屏幕上面。"
                    )
                },
                title = { Text("测试显示") }
            )

            BasePreferenceWidget(
                icon = { Icon(Icons.Default.TextFields, null) },
                onClick = { LyricOverlay.hide() },
                title = { Text("立即隐藏") }
            )

            Spacer(Modifier.navigationBarsPadding())
        }
    }
}
