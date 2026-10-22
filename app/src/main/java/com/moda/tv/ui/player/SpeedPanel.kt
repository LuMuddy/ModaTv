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
import androidx.compose.ui.unit.dp
import com.moda.tv.data.local.PlayerSpeedOptions
import com.moda.tv.ui.theme.ModaColors
import com.moda.tv.ui.theme.TvBody
import com.moda.tv.ui.theme.TvCaption

/**
 * 倍速面板：横向一档一档，左右键换值，选中即生效（与画面方向一致）。
 * 单独成面板而不是塞进综合设置，是为了让遥控器上少一层嵌套。
 */
@Composable
fun SpeedPanel(speed: Float, focusedIndex: Int) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier
            .clip(shape)
            .background(Color(0xD9111218))
            .border(1.dp, Color(0x33FFFFFF), shape)
            .padding(horizontal = 18.dp, vertical = 14.dp)
    ) {
        Text("倍速", style = TvCaption)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            PlayerSpeedOptions.forEachIndexed { index, option ->
                val current = option.value == speed
                val focused = index == focusedIndex
                val chipShape = RoundedCornerShape(8.dp)
                val cs = chipStyle(focused = focused, selected = current)
                Box(
                    Modifier
                        .clip(chipShape)
                        .background(cs.container)
                        .border(cs.borderWidth, cs.border, chipShape)
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(option.label, style = TvBody, color = cs.content)
                }
                Spacer(Modifier.width(8.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text("左右键切换，上下键离开；立即生效", style = TvCaption)
    }
}
