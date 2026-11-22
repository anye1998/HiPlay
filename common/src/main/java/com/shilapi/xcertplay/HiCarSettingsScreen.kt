package com.shilapi.xcertplay

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.shilapi.xcertplay.hicar.HiCarAudioSettings
import com.shilapi.xcertplay.hicar.HiCarFrameRateMode
import com.shilapi.xcertplay.hicar.HiCarSpeedVolumeSettings
import com.shilapi.xcertplay.hicar.HiCarVideoQuality
import com.shilapi.xcertplay.hicar.HiCarVideoSettings
import com.shilapi.xcertplay.hicar.HiCarWindowMode
import kotlin.math.roundToInt

/**
 * The receiver's settings page.
 *
 * Two things make this page different from the CarPlay-era one it replaces:
 *
 * 1. it is reachable **while the phone is connected** — the entry sits on the session overlay, and
 *    the page is drawn over the running picture instead of replacing the activity, so opening it
 *    does not drop the connection;
 * 2. every change is applied on the spot. The window mode re-lays out the picture around the bars,
 *    and the speed-volume values are pushed into the audio path of the session that is running.
 *
 * The connection and diagnostic blocks are slots: they hold state (the pin, the log) that belongs to
 * the activity, and copying it here would mean two places to keep in step.
 */
@Composable
internal fun HiCarSettingsScreen(
    windowMode: HiCarWindowMode,
    onWindowMode: (HiCarWindowMode) -> Unit,
    speed: HiCarSpeedVolumeSettings,
    onSpeed: (HiCarSpeedVolumeSettings) -> Unit,
    mediaKeys: Boolean,
    onMediaKeys: (Boolean) -> Unit,
    video: HiCarVideoSettings,
    onVideo: (HiCarVideoSettings) -> Unit,
    audio: HiCarAudioSettings,
    onAudio: (HiCarAudioSettings) -> Unit,
    onPreviewMedia: () -> Unit,
    onPreviewNavigation: () -> Unit,
    onClose: () -> Unit,
    connection: @Composable ColumnScope.() -> Unit,
    diagnostics: @Composable ColumnScope.() -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("设置", style = MaterialTheme.typography.headlineSmall)
                Button(onClick = onClose) { Text("返回") }
            }
            Text(
                "这里的改动立即生效：改显示方式，画面会重新缩放；改车速音量，正在播放的音乐马上按新参数处理，" +
                    "不需要断开重连。",
                style = MaterialTheme.typography.bodySmall,
            )

            Section("显示")
            for (mode in HiCarWindowMode.values()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onWindowMode(mode) }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = windowMode == mode, onClick = { onWindowMode(mode) })
                    Column {
                        Text(mode.title, style = MaterialTheme.typography.bodyLarge)
                        Text(mode.detail, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Text(
                "画面始终按手机的分辨率等比缩放，只在多出来的地方留黑边，不会被拉伸。" +
                    "选择保留系统栏时，画面会被挤小一点，但不会被系统栏盖住。",
                style = MaterialTheme.typography.bodySmall,
            )

            Section("画面质量")
            VideoQuality(video = video, onChange = onVideo)

            Section("声音")
            SpeedVolume(speed = speed, onChange = onSpeed)
            AudioChannels(audio = audio, onChange = onAudio, onPreviewMedia = onPreviewMedia, onPreviewNavigation = onPreviewNavigation)

            Section("按键")
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("方向盘按键转给手机", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "上一首、下一首、播放暂停按的是手机的音乐，不是车机自己的收音机",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = mediaKeys, onCheckedChange = onMediaKeys)
            }
            Text(
                "这条要手机配合：只有它在这次连接里声明了按键事件，车机才发得出去——" +
                    "日志里会有一行写手机选了什么，选不到时方向盘仍然归车机。关掉这个开关，按键立刻交还车机。",
                style = MaterialTheme.typography.bodySmall,
            )

            Section("连接")
            connection()

            Section("诊断")
            diagnostics()

            Section("关于")
            Text(
                "HiCar 接收端（实验）。无线链路（5 GHz 热点 + 蓝牙认证）已实车连通；方向盘按键、" +
                    "多指触控、随速音量、画面档位、扫码配对已加入。多指触控与有线连接还没有实车验证，" +
                    "相关设置标了「实验」。HUD 与仪表盘输出未实现。",
                style = MaterialTheme.typography.bodySmall,
            )
            Box(Modifier.height(16.dp))
        }
    }
}

/**
 * Sharpness and frame rate.
 *
 * Both are answered once, during the RTSP handshake, so unlike everything else on this page they do
 * not take effect until the next connection — which the note under them says, because a setting that
 * silently waits is a setting the driver will press twice.
 */
@Composable
private fun VideoQuality(video: HiCarVideoSettings, onChange: (HiCarVideoSettings) -> Unit) {
    for (quality in HiCarVideoQuality.values()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onChange(video.copy(quality = quality)) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(
                selected = video.quality == quality,
                onClick = { onChange(video.copy(quality = quality)) },
            )
            Column {
                Text(quality.title, style = MaterialTheme.typography.bodyLarge)
                Text(quality.detail, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    Text("帧率", style = MaterialTheme.typography.bodyLarge)
    for (mode in HiCarFrameRateMode.values()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onChange(video.copy(frameRate = mode)) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(
                selected = video.frameRate == mode,
                onClick = { onChange(video.copy(frameRate = mode)) },
            )
            Column {
                Text(mode.title, style = MaterialTheme.typography.bodyMedium)
                Text(mode.detail, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    Text(
        "这两项在连接建立时一次性告诉手机，改动从下次连接开始生效（连接中改会让当前会话保持原样）。" +
            "清晰度降低分辨率与码率；帧率跟随屏幕时，60 Hz 屏要 60 帧，90/120 Hz 依次提高。",
        style = MaterialTheme.typography.bodySmall,
    )
}

/**
 * Which head unit channel music and guidance go to.
 *
 * Zero means "let the system pick by usage", which is what the receiver did before and what works on
 * a unit without special channels. A number is a legacy stream type: 3 is music, 5 notification, and
 * 11 and up are vendor additions where a car unit usually puts its own navigation speaker. The
 * preview exists because a number that "works" can still be the same speaker as another number on a
 * unit whose amplifier maps several streams onto one output.
 */
@Composable
private fun AudioChannels(
    audio: HiCarAudioSettings,
    onChange: (HiCarAudioSettings) -> Unit,
    onPreviewMedia: () -> Unit,
    onPreviewNavigation: () -> Unit,
) {
    Text(
        "车机自己的导航声道一般不在标准列表里（大多是 11 及以上的编号）。不确定就留在「自动」，" +
            "再用下面的试听确认这个编号到底从哪个喇叭出来。",
        style = MaterialTheme.typography.bodySmall,
    )
    ChannelRow(
        label = "音乐声道",
        value = audio.mediaStream,
        onChange = { onChange(audio.copy(mediaStream = it)) },
    )
    ChannelRow(
        label = "导航声道",
        value = audio.navigationStream,
        onChange = { onChange(audio.copy(navigationStream = it)) },
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onPreviewMedia) { Text("试听音乐声道") }
        TextButton(onClick = onPreviewNavigation) { Text("试听导航声道") }
    }
    Text(
        "导航播报时，音乐会被压低到约三分之一，而不是和播报抢焦点；播报结束后自动恢复。" +
            "这条只在手机把导航单独分出来时有效，混在音乐里的导航只能整条压低。",
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun ChannelRow(label: String, value: Int, onChange: (Int) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            "$label：${HiCarAudioSettings.label(value)}",
            style = MaterialTheme.typography.bodyMedium,
        )
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = 0f..HiCarAudioSettings.MAX_STREAM.toFloat(),
            // 21 steps: automatic plus the twenty stream numbers a unit might use.
            steps = HiCarAudioSettings.MAX_STREAM - 1,
        )
    }
}

/**
 * Music gain that follows the car's speed.
 *
 * The three numbers are the whole feature: below [HiCarSpeedVolumeSettings.startKmh] nothing is
 * added, the gain rises linearly to [HiCarSpeedVolumeSettings.maxGainDb] at
 * [HiCarSpeedVolumeSettings.fullKmh], and past that it stays there. The gain is applied to the music
 * channel's samples inside the receiver, so neither the head unit's nor the phone's volume is
 * touched — the driver's own volume setting keeps meaning what it says.
 */
@Composable
private fun SpeedVolume(
    speed: HiCarSpeedVolumeSettings,
    onChange: (HiCarSpeedVolumeSettings) -> Unit,
) {
    // Local state so a slider drag is smooth; every settle writes through to preferences.
    var local by remember(speed) { mutableStateOf(speed) }
    fun apply(next: HiCarSpeedVolumeSettings) {
        local = next
        onChange(next)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("音乐随车速增大", style = MaterialTheme.typography.bodyLarge)
            Text(
                "只调音乐通道的增益，导航与通话不跟着变",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(
            checked = local.enabled,
            onCheckedChange = { apply(local.copy(enabled = it)) },
        )
    }

    if (!local.enabled) {
        Text(
            "开启后：低速不增益，${local.startKmh.roundToInt()} km/h 起逐渐变响，" +
                "${local.fullKmh.roundToInt()} km/h 达到 +${local.maxGainDb.roundToInt()} dB。",
            style = MaterialTheme.typography.bodySmall,
        )
        return
    }

    SliderRow(
        label = "起始速度",
        value = local.startKmh.toFloat(),
        range = HiCarSpeedVolumeSettings.MIN_START_KMH.toFloat()..
            HiCarSpeedVolumeSettings.MAX_START_KMH.toFloat(),
        suffix = "km/h",
        detail = "低于这个速度不增益：等红灯、倒车、停车时音乐保持原样",
    ) { value ->
        val start = value.roundToInt().toDouble()
        // The ceiling has to stay above the start, or the curve's own check would throw.
        apply(local.copy(startKmh = start, fullKmh = maxOf(local.fullKmh, start + 10.0)))
    }

    SliderRow(
        label = "满档速度",
        value = local.fullKmh.toFloat(),
        range = HiCarSpeedVolumeSettings.MIN_FULL_KMH.toFloat()..
            HiCarSpeedVolumeSettings.MAX_FULL_KMH.toFloat(),
        suffix = "km/h",
        detail = "到这里增益达到上限，再快也不会更响",
    ) { value ->
        apply(local.copy(fullKmh = maxOf(value.roundToInt().toDouble(), local.startKmh + 10.0)))
    }

    SliderRow(
        label = "最大增益",
        value = local.maxGainDb.toFloat(),
        range = 0f..HiCarSpeedVolumeSettings.MAX_GAIN_DB.toFloat(),
        suffix = "dB",
        detail = "10 dB 大约是听感上响一倍；超过 12 dB 容易掩盖导航提示",
    ) { value ->
        apply(local.copy(maxGainDb = value.roundToInt().toDouble()))
    }

    Text("速度来源", style = MaterialTheme.typography.bodyLarge)
    val sources = listOf(
        HiCarSpeedVolumeSettings.Source.AUTO to "自动（优先车机总线，取不到就用定位）",
        HiCarSpeedVolumeSettings.Source.VEHICLE to "只用车辆总线（隧道里也有效）",
        HiCarSpeedVolumeSettings.Source.LOCATION to "只用定位（不需要 root 或 adb）",
    )
    for ((source, label) in sources) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { apply(local.copy(source = source)) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = local.source == source, onClick = { apply(local.copy(source = source)) })
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
    }
    Text(
        "定位在隧道、地下车库没有卫星信号。接收端会保持最后一次读到的速度约 45 秒，" +
            "之后按每秒 4 km/h 淡回不增益，而不是把速度当成 0 —— 否则一出隧道就会突兀地变响。",
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    suffix: String,
    detail: String,
    onChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("$label：${value.roundToInt()} $suffix", style = MaterialTheme.typography.bodyMedium)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
        )
        Text(detail, style = MaterialTheme.typography.bodySmall)
    }
}

/** A heading with a rule under it: the sections have no cards, so the rule is what separates them. */
@Composable
internal fun Section(title: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
    }
}

/** Monospaced text for the values a report has to quote verbatim. */
@Composable
internal fun MonoLine(text: String, color: Color = Color.Unspecified) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = color,
    )
}
