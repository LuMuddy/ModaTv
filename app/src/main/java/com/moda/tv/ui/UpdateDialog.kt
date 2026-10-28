package com.moda.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.moda.tv.R
import com.moda.tv.model.AppError
import com.moda.tv.model.UpdateInfo
import com.moda.tv.ui.components.ActionButton
import com.moda.tv.ui.components.TvFirstFocus
import com.moda.tv.ui.theme.ModaColors
import com.moda.tv.ui.theme.TvBody
import com.moda.tv.ui.theme.TvCaption
import com.moda.tv.ui.theme.TvHeading
import com.moda.tv.ui.update.UpdateInstaller
import java.io.File
import kotlinx.coroutines.launch

/**
 * 版本更新弹窗的状态机，TvViewModel 是唯一写入口。
 *
 * 七种状态对应「检查 → 展示 → 下载 → 拉起安装器」的每一段，界面只看状态、不自己推断时序：
 * - [Idle]：没有可展示的东西。**不渲染弹窗**（"没检测到就没有"，不造"已是最新"弹窗）。
 *   它同时表示「还没查过」「静默检查正在飞」「静默检查失败了」——所以它不是"已是最新"。
 * - [Latest]：检查成功跑完、确认当前已是最新（或仓库还没有 Release）。**不渲染弹窗**，
 *   只用来让「关于」页那一行说实话。与 [Idle] 的区别就是"查成功了"与"没查/没查成"。
 * - [Checking]：手动检查在飞，只在「关于」页那一行显示「检查中…」，同样不弹窗。
 * - [Available]：查到了比本机新的版本，弹窗展示 [UpdateInfo.notes] 与三个出口。
 * - [Downloading]：下载中，按钮行只剩「取消下载」，进度由 [UpdateProgress] 走另一条通道。
 * - [Ready]：APK 已落在 cache 里；[apk] 就是待交给系统安装器的那一个文件。
 * - [Failed]：包已下好却没能交到安装器手里（下载中途失败，或设备上没有能接 APK 的应用）。
 *   检查阶段的失败**不在这里**——查不到就算没有更新，落 [Latest]。
 *
 * 为什么 [Failed] 带 [AppError] 却不带文案：文案只能由 UI 层用 stringResource 解析，
 * ViewModel 不持有任何语言（与 HomeRefreshNotice.messageRes 同一个理由）。
 */
sealed interface UpdateUi {
    data object Idle : UpdateUi
    /**
     * 检查成功跑完、确认当前已是最新（或仓库里还没有 Release）。
     *
     * 与 [Idle] 分开是因为 [Idle] 同时背着「还没查过」「静默检查正在飞」「静默检查失败了」三种含义：
     * 拿它去显示「已是最新」，冷启动那几帧和联网失败后都会变成一句谎。仓库的原则是宁可空也不冒充。
     */
    data object Latest : UpdateUi
    data object Checking : UpdateUi
    data class Available(val info: UpdateInfo) : UpdateUi
    data class Downloading(val info: UpdateInfo) : UpdateUi
    data class Ready(val info: UpdateInfo, val apk: File) : UpdateUi
    data class Failed(val info: UpdateInfo, val error: AppError) : UpdateUi
}

/**
 * 下载进度快照。
 *
 * 单独一条通道而不是塞进 [UpdateUi]：进度每秒要更新几十次，而状态机每次变化都要重排整排按钮；
 * 混在一起会让按钮行跟着进度一起重组。
 *
 * [percent] 为 -1 表示服务端没给 Content-Length（[total] = 0），此时只能显示不确定进度。
 */
data class UpdateProgress(val downloaded: Long = 0L, val total: Long = 0L) {
    val percent: Int
        get() = if (total > 0L) ((downloaded * 100L) / total).toInt().coerceIn(0, 100) else -1

    /** 进度条的确定值（0f..1f）；[percent] 为 -1（长度未知）时进度条走不确定态，这里给 0 */
    val fraction: Float
        get() = if (total > 0L) downloaded.toFloat() / total.toFloat() else 0f
}

/**
 * 版本更新弹窗。
 *
 * ## 遥控器六语义（先定这六条，再画 UI）
 * - **上**：按钮行 → 更新内容区。内容区自己可聚焦、自己吃上下键滚动。
 * - **下**：内容区 → 按钮行。内容已滚到底时下键不再被内容区吃掉，焦点自然落回按钮行；
 *   内容区不再可滚（不超高）时它根本不进焦点链，按下键也不会在这里空转。
 * - **左右**：按钮行内横向切换（立即更新 / 以后再说 / 忽略此版本）。
 * - **确认**：触发当前按钮。
 * - **返回**：等同「以后再说」→ [onDismiss]（下载中则取消下载，见 TvViewModel.dismissUpdate）。
 *
 * 三条与遥控器有关的硬约束：
 * 1. 首焦点给按钮行的第一个按钮，抢焦点统一走 [TvFirstFocus]，不在这里手写多帧重试。
 * 2. 内容区只在**真的能滚**时才 `.focusable()`：否则它会是按钮行上方唯一的焦点格，
 *    按下键把焦点吸走又什么都不动，用户以为遥控器坏了（版权声明弹窗的正文同一个处理）。
 * 3. 按键只在自己"有用"时吃掉：滚到头必须把键交还给焦点系统，否则焦点被锁死在正文里，
 *    再也走不到按钮行——那就是键盘陷阱。
 *
 * @param state 当前状态；[UpdateUi.Idle] / [UpdateUi.Latest] / [UpdateUi.Checking] 由调用方决定不渲染，这里不兜
 * @param progress 下载进度（collectAsState 读，见 TvViewModel 里为什么不能直接写 mutableStateOf）
 * @param onDownload 「立即更新」/ 下载失败重试 / 「立即安装」共用这一个入口：
 *        已下好的包会被直接复用（镜象不稳，重试不该从 0 再来一遍）
 * @param onIgnore 「忽略此版本」：记下一次版本号，该版本之后不再打扰
 * @param onDismiss 「以后再说」/ 返回键 / 点弹窗外
 */
@Composable
fun UpdateDialog(
    state: UpdateUi,
    progress: UpdateProgress,
    onDownload: () -> Unit,
    onIgnore: () -> Unit,
    onDismiss: () -> Unit,
) {
    // 只有这四种状态带版本号，也只有它们该被渲染（AppRoot 的 when 里 Idle/Latest/Checking
    // 直接 Unit）。真被误渲染时不画，而不是把入口名「检查更新」冒充成标题。
    val info: UpdateInfo = when (state) {
        is UpdateUi.Available -> state.info
        is UpdateUi.Downloading -> state.info
        is UpdateUi.Ready -> state.info
        is UpdateUi.Failed -> state.info
        UpdateUi.Idle, UpdateUi.Latest, UpdateUi.Checking -> return
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // 遥控器没有鼠标：弹窗一出现焦点必须已经在按钮上，否则「确认」要点两下才生效。
        // key 跟着「按钮行长什么样」变：点了「立即更新」之后整排按钮被换成「取消下载」，
        // 原来那个按钮离开组合、焦点随之丢失；不按 key 重抢一次，屏幕上会一个白框都没有。
        val firstFocus = remember { FocusRequester() }
        TvFirstFocus(firstFocus, key = state.buttonsRowKey())

        // 尺寸跟屏幕走（照抄版权声明弹窗）：电视横屏高度常常只有 400~1080dp，
        // 写死宽高会让弹窗被窗口裁掉，标题和按钮直接看不见。
        val config = LocalConfiguration.current
        val dialogWidth = minOf(880f, config.screenWidthDp * 0.82f).dp
        // 0.40 比版权声明的 0.52 更小：更新日志的长度完全不可控（可能几十行），
        // 而这一屏必须始终看得见按钮行——正文让位、按钮不让。
        val bodyMaxHeight = (config.screenHeightDp * 0.40f).dp

        val context = LocalContext.current
        // 只在 Ready 时问一次系统（PackageManager 查询不值得跟着进度重组每秒跑一遍）
        val installAllowed = remember(state) {
            state is UpdateUi.Ready && UpdateInstaller.canInstall(context)
        }

        val title = stringResource(R.string.update_title, info.versionName)
        // 状态行（进度 / 失败原因 / 授权提示）。null = 这一屏不显示状态行，
        // 但位置照样占着（见下面的固定高度 Box）：否则它出现/消失时下面的按钮会整排上下跳。
        val statusLine: String? = when (state) {
            is UpdateUi.Downloading ->
                if (progress.percent >= 0) {
                    stringResource(R.string.update_downloading, progress.percent)
                } else {
                    stringResource(R.string.update_downloading_unknown)
                }
            is UpdateUi.Failed ->
                if (state.error == AppError.Unknown) {
                    // 包下好了，失败点在"拉起安装器"。startInstall 用 AppError.Unknown 标记它：
                    // UpdateInstaller.install() 返回 false 的唯一成因是没有应用能接这个 MIME
                    // （ActivityNotFoundException），不是下载故障。包就在 cache 里，
                    // 说「下载失败，可重试」会让用户点一次又一次永远不成功。
                    stringResource(R.string.update_failed_install)
                } else {
                    stringResource(R.string.update_failed_download)
                }
            // 已下好包但没拿到「安装未知来源」授权：这句话就是下一步该做什么
            is UpdateUi.Ready -> if (!installAllowed) stringResource(R.string.update_install_hint) else null
            else -> null
        }

        Column(
            Modifier
                .width(dialogWidth)
                .clip(RoundedCornerShape(18.dp))
                .background(ModaColors.Surface)
                .padding(horizontal = 44.dp, vertical = 32.dp),
        ) {
            Text(title, style = TvHeading.copy(fontSize = 32.sp), color = ModaColors.Text)
            Spacer(Modifier.height(10.dp))

            Box(Modifier.fillMaxWidth().height(26.dp)) {
                if (statusLine != null) {
                    Text(
                        statusLine,
                        style = TvBody.copy(fontSize = 18.sp),
                        color = ModaColors.TextDim,
                    )
                }
            }
            // 进度条常驻占位（14dp）：只有下载中才画，但槽位一直在，
            // 否则它出现/消失时下面的正文与按钮会整块上下跳（与状态行同一个理由）。
            Box(Modifier.fillMaxWidth().height(14.dp), contentAlignment = Alignment.CenterStart) {
                if (state is UpdateUi.Downloading) {
                    val bar = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))
                    if (progress.percent >= 0) {
                        LinearProgressIndicator(
                            progress = { progress.fraction },
                            modifier = bar,
                            color = ModaColors.Accent,
                            trackColor = ModaColors.SurfaceHigh,
                            // 关掉 material3 默认的"终点小圆点 + 轨道缺口"：TV 上满屏就一条进度条，
                            // 那条缝和一个孤零零的圆点看着像渲染残渣，不像"还剩多少"
                            gapSize = 0.dp,
                            drawStopIndicator = {},
                        )
                    } else {
                        // 服务端没给 Content-Length：画不确定进度条。
                        // 绝不画一条永远停在 0% 的确定条——用户会以为卡死然后反复按取消重来。
                        LinearProgressIndicator(
                            modifier = bar,
                            color = ModaColors.Accent,
                            trackColor = ModaColors.SurfaceHigh,
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))

            Text(
                stringResource(R.string.update_notes_header),
                style = TvCaption.copy(fontSize = 15.sp),
                color = ModaColors.TextDim,
            )
            Spacer(Modifier.height(8.dp))

            // 正文自己滚，标题、状态行与按钮始终可见——用户永远看得见入口和出口。
            val bodyScroll = rememberScrollState()
            val scope = rememberCoroutineScope()
            // 「上」键进正文的落点：不靠几何遍历去猜（电视上正文与按钮行之间隔着状态行与提示行，
            // 实测按「上」进不去），改成显式 requestFocus
            val bodyFocus = remember { FocusRequester() }
            // 按键回调不在组合上下文里，dp→px 必须在组合期先算好
            val stepPx = with(LocalDensity.current) { 120.dp.toPx() }
            // focusable 自己不画焦点：补一圈 3dp 白描边（R1：不用 scale 表达焦点），
            // 描边挂在滚动容器之外，不跟着内容一起滚。
            var bodyFocused by remember { mutableStateOf(false) }
            // 只有能滚时才进焦点链：内容不超高时它不该抢到上键（那正是"焦点空转"）
            val scrollable = bodyScroll.maxValue > 0
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = bodyMaxHeight)
                    .border(
                        width = 3.dp,
                        color = if (bodyFocused) ModaColors.Focus else Color.Transparent,
                        shape = RoundedCornerShape(8.dp),
                    )
                    // 描边画在边界内侧，正文与它之间留 4dp，白框不会压住首末行的字
                    .padding(horizontal = 4.dp, vertical = 2.dp)
                    .verticalScroll(bodyScroll)
                    // onFocusChanged 必须排在 focusable **之前**：它观察的是链上更靠后的那个焦点节点，
                    // 放在后面就一个事件也收不到——焦点白框永不出现、提示文案永远停在"按「上」"，
                    // 于是"正文里那点白框"和"提示语"同时失效（就是用户说的「下箭头提示没用」）
                    .onFocusChanged { bodyFocused = it.isFocused }
                    .then(if (scrollable) Modifier.focusRequester(bodyFocus).focusable() else Modifier)
                    .onPreviewKeyEvent { ev ->
                        if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        // 只有"还能滚"时才吃键：到底之后必须把上下键交还给焦点系统，
                        // 否则焦点被锁在正文里，永远走不到按钮行——那就是键盘陷阱。
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
            ) {
                Text(
                    // notes 是数据层已剥过 HTML 的纯文本；空串时给一句说明，
                    // 不能让弹窗里出现一块空白让人以为没加载出来
                    text = info.notes.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.update_notes_empty),
                    style = TvBody.copy(fontSize = 17.sp, lineHeight = 25.sp),
                    color = ModaColors.Text,
                )
            }

            // 正文被高度上限截断时必须说出来：电视上没有滚动条，用户只会以为"就这些"。
            // 这一行常驻占位、高度固定：挂在 canScrollForward 上的话，滚到底那一帧它离开组合，
            // 下面的按钮行会整排往上跳（焦点框跟着位移，用户以为自己按错了）。
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(22.dp)) {
                if (bodyScroll.canScrollForward) {
                    Text(
                        // 提示必须说当前按得动的那一个键：焦点在按钮行时是「上」（进入正文），
                        // 已经在正文里时才是「下」。早先不分状态一律写「按「下」继续阅读」，
                        // 而焦点永远落在按钮行——那句提示于是成了空话，按「下」什么都不会动。
                        stringResource(
                            if (bodyFocused) R.string.update_scroll_hint_in_body
                            else R.string.update_scroll_hint,
                        ),
                        style = TvCaption.copy(fontSize = 14.sp),
                        color = ModaColors.TextDim,
                    )
                }
            }

            Spacer(Modifier.height(18.dp))
            // 按钮行：第一个按钮拿首焦点。单按钮状态下左右键无事可做，这是有意的——
            // 下载中/待安装都只有一个动作，"多给一个出口"只会让用户在两行之间犹豫。
            Row(
                Modifier
                    .fillMaxWidth()
                    // 焦点在按钮行按「上」= 进正文：这里显式接管，不等几何遍历。
                    // 正文不能滚时不吃键（false），让它照常交还给焦点系统——
                    // 否则「上」会变成一个什么都没发生的按键。
                    .onPreviewKeyEvent { ev ->
                        if (ev.type == KeyEventType.KeyDown &&
                            ev.key == Key.DirectionUp && scrollable
                        ) {
                            // 目标可能刚离开组合（内容变短后不再可滚）：和 TvFirstFocus 同一个理由，
                            // 抢不到就算了，绝不让一次按键变成崩溃
                            runCatching { bodyFocus.requestFocus() }
                            true
                        } else {
                            false
                        }
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.End),
            ) {
                when (state) {
                    is UpdateUi.Available -> {
                        ActionButton(
                            text = stringResource(R.string.update_btn_download),
                            onClick = onDownload,
                            modifier = Modifier.focusRequester(firstFocus),
                            primary = true,
                        )
                        ActionButton(
                            text = stringResource(R.string.update_btn_later),
                            onClick = onDismiss,
                        )
                        ActionButton(
                            text = stringResource(R.string.update_btn_ignore),
                            onClick = onIgnore,
                        )
                    }
                    is UpdateUi.Downloading -> ActionButton(
                        text = stringResource(R.string.update_btn_cancel),
                        onClick = onDismiss,
                        modifier = Modifier.focusRequester(firstFocus),
                    )
                    // 「立即安装」走 onDownload：TvViewModel.downloadUpdate 见到已下好的包
                    // 会直接跳过下载再试一次安装，于是这里不需要第二个 VM 入口。
                    // 用户从授权页回来还没授权成功时，按它等于再看一次系统设置页。
                    is UpdateUi.Ready -> ActionButton(
                        text = stringResource(R.string.update_btn_install),
                        onClick = onDownload,
                        modifier = Modifier.focusRequester(firstFocus),
                        primary = true,
                    )
                    // 包已经在 cache 里：按它重新走下载入口，VM 见到已下好的包会跳过下载，
                    // 直接再试一次安装，所以不需要第二个 VM 方法
                    is UpdateUi.Failed -> {
                        ActionButton(
                            text = stringResource(R.string.update_btn_download),
                            onClick = onDownload,
                            modifier = Modifier.focusRequester(firstFocus),
                            primary = true,
                        )
                        ActionButton(
                            text = stringResource(R.string.update_btn_later),
                            onClick = onDismiss,
                        )
                    }
                    // Idle / Checking 由调用方决定不渲染；真被渲染也只留一句"以后再说"，
                    // 不画一排没意义的按钮
                    else -> ActionButton(
                        text = stringResource(R.string.update_btn_later),
                        onClick = onDismiss,
                        modifier = Modifier.focusRequester(firstFocus),
                    )
                }
            }
        }
    }
}

/**
 * 首焦点的重抢锚点：按钮行的形状一变就换 key。
 *
 * 只按"按钮行有几个按钮"区分而不是按整个 [UpdateUi]：下载进度不会改 [state]，
 * 但 [UpdateUi.Available] → [UpdateUi.Downloading] 会换掉整排按钮，那时必须重新抢一次。
 */
private fun UpdateUi.buttonsRowKey(): String = when (this) {
    is UpdateUi.Available -> "available"
    is UpdateUi.Downloading -> "downloading"
    is UpdateUi.Ready -> "ready"
    is UpdateUi.Failed -> "failed"
    // 这三态都不渲染弹窗，共用同一个 key
    UpdateUi.Idle, UpdateUi.Latest, UpdateUi.Checking -> "idle"
}
