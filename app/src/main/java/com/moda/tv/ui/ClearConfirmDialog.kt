package com.moda.tv.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.moda.tv.R
import com.moda.tv.ui.components.ActionButton
import com.moda.tv.ui.components.TvFirstFocus
import com.moda.tv.ui.theme.ModaColors
import com.moda.tv.ui.theme.TvBody
import com.moda.tv.ui.theme.TvHeading

/**
 * 清空类操作（观看历史 / 收藏 / 搜索历史）的二次确认弹窗。
 *
 * 「清空全部」是一次不可撤销的批量删除，而电视遥控器上「确认」键没有任何撤销余地：
 * 焦点只要停在这个按钮上，多按一下就全没了。所以删除前统一拦一道弹窗，
 * 全应用共用这一个组件，避免三处各写一套不同口径的确认文案。
 *
 * 焦点默认落在「取消」而不是「确认」：弹窗一出现就把焦点抢到确认上，
 * 用户从「清空全部」按下的那一下很容易连击，连击直接落到删除上等于没有确认。
 * 「取消」在最左、最先被抢到，连击最多把弹窗关掉，代价是再打开一次。
 *
 * @param title 弹窗标题，例如「清空全部历史记录？」
 * @param message 说明将要删除什么、有多少条、以及不可恢复
 * @param confirmTextRes 确认按钮文案的资源 id：按钮文字要跟系统语言走
 * @param onConfirm 用户确认删除；调用方负责真正清空并关弹窗
 * @param onDismiss 取消、点弹窗外或按返回键
 */
@Composable
fun ClearConfirmDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    @StringRes confirmTextRes: Int = R.string.clear_confirm_button,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // 等两帧再抢（弹窗首帧布局未完成时 requestFocus 会抛），失败不抛：
        // 手写 LaunchedEffect + 单次 requestFocus 的写法一次失败就再也不重试
        val cancelFocus = remember { FocusRequester() }
        TvFirstFocus(cancelFocus)

        // 宽度跟屏幕走：电视上 540dp 高的横屏里写死宽度容易被窗口裁掉
        val config = LocalConfiguration.current
        val dialogWidth = minOf(620f, config.screenWidthDp * 0.72f).dp

        Column(
            Modifier
                .width(dialogWidth)
                .clip(RoundedCornerShape(18.dp))
                .background(ModaColors.Surface)
                .padding(horizontal = 36.dp, vertical = 28.dp),
        ) {
            Text(
                title,
                style = TvHeading.copy(fontSize = 28.sp),
                color = ModaColors.Text,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                message,
                style = TvBody.copy(fontSize = 17.sp, lineHeight = 25.sp),
                color = ModaColors.TextDim,
            )
            Spacer(Modifier.height(24.dp))

            // 取消在左、删除在右：删除是危险操作，放在需要用方向键多走一步的那一侧
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                ActionButton(
                    text = stringResource(R.string.common_cancel),
                    onClick = onDismiss,
                    modifier = Modifier.focusRequester(cancelFocus),
                )
                Spacer(Modifier.width(14.dp))
                ActionButton(
                    text = stringResource(confirmTextRes),
                    onClick = onConfirm,
                    primary = true,
                )
            }
        }
    }
}
