package com.moda.tv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import com.moda.tv.data.local.AspectMode
import com.moda.tv.data.local.AspectModes
import com.moda.tv.ui.theme.TvCaption

/** 画面比例面板：左右键循环，选中即改 resizeMode，不重建播放器 */
@Composable
fun AspectPanel(mode: AspectMode, focusedIndex: Int) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier
            .clip(shape)
            .background(Color(0xD9111218))
            .border(1.dp, Color(0x33FFFFFF), shape)
            .padding(horizontal = 18.dp, vertical = 14.dp)
    ) {
        Text(stringResource(R.string.player_aspect_title), style = TvCaption)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            AspectModes.forEachIndexed { index, option ->
                val current = option == mode
                val focused = index == focusedIndex
                val chipShape = RoundedCornerShape(8.dp)
                // 统一三态配色：焦点=3dp 白描边+提亮底 / 选中=蓝底+勾标 / 其余半透明
                val cs = chipStyle(focused = focused, selected = current)
                Box(
                    Modifier
                        .clip(chipShape)
                        .background(cs.container)
                        .border(cs.borderWidth, cs.border, chipShape)
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    // 焦点（白框）与生效值（✓）是两个独立信号，见 ChipLabel 的注释
                    ChipLabel(stringResource(option.labelRes), cs, current)
                }
                Spacer(Modifier.width(8.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.player_panel_hint_lr), style = TvCaption)
    }
}
