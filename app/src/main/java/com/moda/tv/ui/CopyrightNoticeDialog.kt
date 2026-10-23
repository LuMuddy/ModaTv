package com.moda.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.launch
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
import com.moda.tv.BuildConfig
import com.moda.tv.R
import com.moda.tv.ui.components.ActionButton
import com.moda.tv.ui.theme.ModaColors
import com.moda.tv.ui.theme.TvBody
import com.moda.tv.ui.theme.TvCaption
import com.moda.tv.ui.theme.TvHeading

/**
 * 版权声明正文（四个段落的字符串资源 id，顺序即展示顺序）。
 *
 * 独立成列表而不是一段长文本：电视上大段文字很难扫读，分条之后每条只表达一件事，
 * 用户扫一眼就能抓到「免费」「作者不管盗版」这两个要点。
 * 这里存资源 id 而不是字符串：正文要跟系统语言走，启动弹窗与设置页入口共用同一份文案。
 */
private val NoticeParagraphs: List<Int> = listOf(
    R.string.copyright_paragraph_1,
    R.string.copyright_paragraph_2,
    R.string.copyright_paragraph_3,
    R.string.copyright_paragraph_4,
)

/**
 * 版权声明弹窗。
 *
 * 会在每次版本升级后自动出现一次（判定逻辑在 AppRoot，见 CopyrightNoticeStore），
 * 也可以从设置页随时重新打开——同一条声明两种入口，内容完全一致。
 *
 * @param onDismiss 用户点「我已知悉」或按返回键；调用方负责把它记录成「当前版本已确认」
 */
@Composable
fun CopyrightNoticeDialog(
    onDismiss: () -> Unit,
    versionName: String = BuildConfig.VERSION_NAME,
    versionCode: Int = BuildConfig.VERSION_CODE,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // 遥控器没有鼠标，弹窗一出现焦点必须已经在按钮上，
        // 否则「确认」这个唯一出口要点两下才生效
        val confirmFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { confirmFocus.requestFocus() } }

        // 尺寸必须跟着屏幕走：电视横屏高度常常只有 400~1080dp，
        // 写死宽高会让弹窗在矮屏上被窗口裁掉，标题和按钮直接看不见。
        // 宽度取「880dp 与屏幕 82% 的较小值」，正文高度按屏幕高度给比例。
        val config = LocalConfiguration.current
        val dialogWidth = minOf(880f, config.screenWidthDp * 0.82f).dp
        // 0.52 是实测值：540dp 高的电视上刚好装下全部四段，且标题与按钮仍在屏内。
        // 再高会挤掉底部按钮，再低就得滚动才能看全。
        val bodyMaxHeight = (config.screenHeightDp * 0.52f).dp

        Column(
            Modifier
                .width(dialogWidth)
                .clip(RoundedCornerShape(18.dp))
                .background(ModaColors.Surface)
                .padding(horizontal = 44.dp, vertical = 34.dp),
        ) {
            Text(
                stringResource(R.string.copyright_title),
                style = TvHeading.copy(fontSize = 32.sp),
                color = ModaColors.Text,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                // 版本随构建变化，整句交给资源表，不在代码里拼句子
                stringResource(R.string.copyright_version_line, versionName, versionCode),
                style = TvCaption,
                color = ModaColors.TextDim,
            )
            Spacer(Modifier.height(16.dp))

            // 长文案在小屏电视上会顶破弹窗高度，所以正文自己滚：
            // 标题与按钮始终可见，用户永远看得见入口和出口。
            val bodyScroll = rememberScrollState()
            val scope = rememberCoroutineScope()
            // 按键回调里不是组合上下文，dp→px 必须先在组合期算好
            val stepPx = with(LocalDensity.current) { 120.dp.toPx() }
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = bodyMaxHeight)
                    .verticalScroll(bodyScroll)
                    // 电视上没有滚动条：正文必须自己可聚焦、自己吃上下键，否则这屏文字永远读不到底
                    // （焦点只在"我已知悉"上，按上下键什么也不会发生）
                    .focusable()
                    .onPreviewKeyEvent { ev ->
                        if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        // 只有"还能滚"时才吃键：到底之后必须把上下键交还给焦点系统，
                        // 否则焦点被锁在正文里，永远走不到「我已知悉」——那就是键盘陷阱。
                        when (ev.key) {
                            Key.DirectionDown -> if (bodyScroll.canScrollForward) {
                                scope.launch { bodyScroll.scrollBy(stepPx) }
                                true
                            } else {
                                false
                            }
                            Key.DirectionUp -> if (bodyScroll.canScrollBackward) {
                                scope.launch { bodyScroll.scrollBy(-stepPx) }
                                true
                            } else {
                                false
                            }
                            else -> false
                        }
                    },
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                NoticeParagraphs.forEach { paragraphRes ->
                    Text(
                        text = stringResource(paragraphRes),
                        style = TvBody.copy(fontSize = 17.sp, lineHeight = 25.sp),
                        color = ModaColors.Text,
                    )
                }
            }

            // 正文被高度上限截断时必须说出来：电视上没有滚动条，用户只会以为"就这些"，
            // 而版权声明恰恰是要求用户真的读过一遍才点"我已知悉"的。
            if (bodyScroll.canScrollForward) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.copyright_scroll_hint),
                    style = TvCaption.copy(fontSize = 14.sp),
                    color = ModaColors.TextDim,
                )
            }

            Spacer(Modifier.height(20.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                ActionButton(
                    text = stringResource(R.string.copyright_ack),
                    onClick = onDismiss,
                    modifier = Modifier.focusRequester(confirmFocus),
                    primary = true,
                )
            }
        }
    }
}
