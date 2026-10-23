package com.moda.tv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.moda.tv.data.local.DanmakuRegion
import com.moda.tv.data.local.DanmakuRegions
import com.moda.tv.data.local.DanmakuSizeOptions
import com.moda.tv.data.local.MarginCombinedMaxPercent
import com.moda.tv.data.local.MarginMaxPercent
import com.moda.tv.data.local.MarginStepPercent
import com.moda.tv.data.local.PlayerSettings
import com.moda.tv.ui.theme.ModaColors
import com.moda.tv.ui.theme.TvBody
import com.moda.tv.ui.theme.TvCaption

/** 弹幕面板里可上下切换的子项，顺序即遥控器上下移动顺序；确认键改当前项的值 */
enum class DanmakuItem(val label: String) {
    Enabled("弹幕开关"),
    Size("字号"),
    Region("位置"),
    TopMargin("上边距"),
    BottomMargin("下边距"),
}

val DanmakuItems: List<DanmakuItem> = DanmakuItem.entries

/** 弹幕面板高度上限：只放弹幕自己的三项，倍速不在里面 */
@Composable
fun DanmakuPanel(settings: PlayerSettings, focusedItem: Int) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier
            .clip(shape)
            .background(Color(0xD9111218))
            .border(1.dp, Color(0x33FFFFFF), shape)
            .padding(horizontal = 18.dp, vertical = 12.dp)
            .width(620.dp)
    ) {
        Text("弹幕设置", style = TvCaption)
        Spacer(Modifier.height(6.dp))
        DanmakuItems.forEachIndexed { index, item ->
            val focused = index == focusedItem
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (focused) ModaColors.Focus else Color(0x00000000))
                    .padding(horizontal = 12.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    item.label,
                    style = TvBody,
                    color = if (focused) Color(0xFF0B0B0F) else ModaColors.TextDim,
                )
                Text(
                    danmakuItemValue(item, settings),
                    style = TvBody,
                    color = if (focused) Color(0xFF0B0B0F) else ModaColors.Text,
                )
            }
            Spacer(Modifier.height(4.dp))
        }
        Spacer(Modifier.height(2.dp))
        // 位置档位一眼可见，不用确认键一档档试才知道有哪几档
        Text(DanmakuRegions.map { stringResource(it.labelRes) }.joinToString(" · "), style = TvCaption)
    }
}

@Composable
private fun danmakuItemValue(item: DanmakuItem, settings: PlayerSettings): String = when (item) {
    DanmakuItem.Enabled -> if (settings.danmakuEnabled) "开" else "关"
    DanmakuItem.Size -> stringResource(settings.sizeLabelRes)
    DanmakuItem.Region -> stringResource(settings.danmakuRegion.labelRes)
    DanmakuItem.TopMargin -> (settings.topRatio * 100).toInt().toString() + "%"
    DanmakuItem.BottomMargin -> (settings.bottomRatio * 100).toInt().toString() + "%"
}

/**
 * 调值：播放页里由确认键触发（纵向表，左右键在这层没有语义）。
 * 档位类取模循环，边距类按 5% 步进。
 */
fun adjustDanmaku(settings: PlayerSettings, item: DanmakuItem, delta: Int): PlayerSettings =
    when (item) {
        DanmakuItem.Enabled -> settings.copy(danmakuEnabled = !settings.danmakuEnabled)
        DanmakuItem.Size -> {
            val i = DanmakuSizeOptions.indexOfFirst { it.sizeSp == settings.danmakuSizeSp }
                .coerceAtLeast(0)
            val next = DanmakuSizeOptions[(i + delta).floorMod(DanmakuSizeOptions.size)]
            settings.copy(danmakuSizeSp = next.sizeSp)
        }
        DanmakuItem.Region -> {
            val i = DanmakuRegions.indexOf(settings.danmakuRegion).coerceAtLeast(0)
            val next = DanmakuRegions[(i + delta).floorMod(DanmakuRegions.size)]
            // 切到自定义档位时把当前生效的占比搬进可调边距，视觉上不跳变
            if (next == DanmakuRegion.Custom) {
                settings.copy(
                    danmakuRegion = next,
                    danmakuTopPercent = settings.topRatio * 100f,
                    danmakuBottomPercent = settings.bottomRatio * 100f,
                )
            } else {
                settings.copy(danmakuRegion = next)
            }
        }
        DanmakuItem.TopMargin -> settings.copy(
            danmakuRegion = DanmakuRegion.Custom,
            danmakuTopPercent = stepMargin(
                current = settings.danmakuTopPercent,
                other = settings.danmakuBottomPercent,
                delta = delta,
            ),
        )
        DanmakuItem.BottomMargin -> settings.copy(
            danmakuRegion = DanmakuRegion.Custom,
            danmakuBottomPercent = stepMargin(
                current = settings.danmakuBottomPercent,
                other = settings.danmakuTopPercent,
                delta = delta,
            ),
        )
    }

/** 上下边距合计不能吃满画面，至少留 30% 给弹幕 */
private fun Float.combinedCap(other: Float): Float =
    coerceAtMost((MarginCombinedMaxPercent - other).coerceAtLeast(0f))

/**
 * 边距步进，到达区间端点后绕回另一端。
 * 播放页只有一个方向可调（确认键 +1），若在边界夹住就再也回不来，
 * 用户会以为按键坏了；绕回让单键也能走遍整个区间。
 */
private fun stepMargin(current: Float, other: Float, delta: Int): Float {
    val cap = MarginMaxPercent.combinedCap(other)
    val step = delta * MarginStepPercent
    val next = current + step
    // 留一点容差，避免 69.99999 这种浮点值被判成"没到顶"而卡死
    return when {
        step > 0f && next > cap -> 0f
        step < 0f && next < 0f -> cap
        else -> next.coerceIn(0f, cap)
    }
}

private fun Int.floorMod(n: Int): Int = ((this % n) + n) % n
