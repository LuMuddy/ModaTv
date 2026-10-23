package com.moda.tv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.moda.tv.data.local.DecoderMode
import com.moda.tv.data.local.PlayerCore
import com.moda.tv.ui.theme.TvBody
import com.moda.tv.ui.theme.TvCaption

/**
 * 内核 / 解码两档选择面板。
 *
 * 做成横向档位条而不是纵向列表：与「倍速 / 比例」共用同一套左右键语义，
 * 用户不必为两个二选一开关再记一套上下导航。
 */
@Composable
private fun ChoicePanel(
    title: String,
    labels: List<String>,
    currentIndex: Int,
    focusedIndex: Int,
    note: String?,
    footnote: String,
) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier
            .width(IntrinsicSize.Max)
            .clip(shape)
            .background(Color(0xD9111218))
            .border(1.dp, Color(0x33FFFFFF), shape)
            .padding(horizontal = 18.dp, vertical = 14.dp)
    ) {
        Text(title, style = TvCaption)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            labels.forEachIndexed { index, label ->
                // 统一三态配色：焦点白底深字 / 选中蓝底白字 / 其余半透明
                val cs = chipStyle(focused = index == focusedIndex, selected = index == currentIndex)
                val chipShape = RoundedCornerShape(8.dp)
                Box(
                    Modifier
                        .clip(chipShape)
                        .background(cs.container)
                        .border(cs.borderWidth, cs.border, chipShape)
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(label, style = TvBody, color = cs.content)
                }
                Spacer(Modifier.width(8.dp))
            }
        }
        if (note != null) {
            Spacer(Modifier.height(6.dp))
            Text(note, style = TvCaption)
        }
        Spacer(Modifier.height(6.dp))
        Text(footnote, style = TvCaption)
    }
}

@Composable
fun CorePanel(playingCore: PlayerCore, focusedIndex: Int, ijkUsable: Boolean) {
    ChoicePanel(
        title = "播放内核",
        labels = PlayerCore.entries.map { stringResource(it.labelRes) },
        currentIndex = PlayerCore.entries.indexOf(playingCore),
        focusedIndex = focusedIndex,
        // 静默回退必须说出来：否则用户以为选了 IJK 就换了内核，
        // 实际仍是 ExoPlayer，排查解码问题时会被这条假信息带偏
        note = if (ijkUsable) {
            null
        } else {
            "本机没有 IJK 原生库（只打包 arm64-v8a / armeabi-v7a），选 IJK 实际仍按 ExoPlayer 播放"
        },
        footnote = "左右键切换，上下键离开；只记在本剧，选回与设置页相同即跟随默认",
    )
}

@Composable
fun DecoderPanel(mode: DecoderMode, focusedIndex: Int) {
    ChoicePanel(
        title = "解码方式",
        labels = DecoderMode.entries.map { stringResource(it.labelRes) },
        currentIndex = DecoderMode.entries.indexOf(mode),
        focusedIndex = focusedIndex,
        note = null,
        footnote = "硬解省电、4K 更稳；软解兼容性更好。只记在本剧，选回默认即跟随设置页",
    )
}
