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
import com.moda.tv.R
import com.moda.tv.data.local.DecoderMode
import com.moda.tv.data.local.PlayerCore
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
                // 统一三态配色：焦点=3dp 白描边+提亮底 / 选中=蓝底+勾标 / 其余半透明
                val current = index == currentIndex
                val cs = chipStyle(focused = index == focusedIndex, selected = current)
                val chipShape = RoundedCornerShape(8.dp)
                Box(
                    Modifier
                        .clip(chipShape)
                        .background(cs.container)
                        .border(cs.borderWidth, cs.border, chipShape)
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    // 这里两态真的会分开：IJK 缺 so 时焦点停在"用户选的 IJK"上，
                    // 而实际跑的是 Exo——不把焦点和生效画成两套就分辨不出「选了但没生效」
                    ChipLabel(label, cs, current)
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
        title = stringResource(R.string.player_core_title),
        labels = PlayerCore.entries.map { stringResource(it.labelRes) },
        currentIndex = PlayerCore.entries.indexOf(playingCore),
        focusedIndex = focusedIndex,
        // 静默回退必须说出来：否则用户以为选了 IJK 就换了内核，
        // 实际仍是 ExoPlayer，排查解码问题时会被这条假信息带偏
        note = if (ijkUsable) {
            null
        } else {
            stringResource(R.string.player_core_ijk_missing)
        },
        footnote = stringResource(R.string.player_core_footnote),
    )
}

@Composable
fun DecoderPanel(mode: DecoderMode, focusedIndex: Int) {
    ChoicePanel(
        title = stringResource(R.string.player_decoder_title),
        labels = DecoderMode.entries.map { stringResource(it.labelRes) },
        currentIndex = DecoderMode.entries.indexOf(mode),
        focusedIndex = focusedIndex,
        note = null,
        footnote = stringResource(R.string.player_decoder_footnote),
    )
}
