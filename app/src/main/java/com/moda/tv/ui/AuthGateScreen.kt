package com.moda.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moda.tv.R
import com.moda.tv.data.WxPoll
import com.moda.tv.data.WxQr
import com.moda.tv.ui.components.ActionButton
import com.moda.tv.ui.components.QrCodeImage
import com.moda.tv.ui.components.TvFirstFocus
import com.moda.tv.ui.theme.ModaColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 授权码固定 8 位：输入框、提交闸门、站点页面三处必须是同一个数 */
internal const val AUTH_CODE_LENGTH = 8

/**
 * 二维码原文模板 = 微信「在微信里确认登录」的地址前缀。
 *
 * 来源是实测，不是猜的：把服务端 wx_qr 回的 qr 图片（微信自己那张 JPEG）用 zxing 解码，
 * 得到的就是 https://open.weixin.qq.com/connect/confirm?uuid=&lt;uuid&gt;，其中 uuid 与同一次
 * wx_qr 响应里的 uuid 字段一致。所以本地拼这条原文就够了——既不用下载那张图，
 * 也就不受图片缩放/染色/暗色主题的影响（屏幕上的每一个模块都是我们自己画的）。
 */
internal const val WX_QR_TEXT_PREFIX = "https://open.weixin.qq.com/connect/confirm?uuid="

/**
 * 轮询间隔 1.5s。
 *
 * 为什么是这个数：微信侧从「已扫码」到「确认登录」通常 2~5 秒，1.5s 让状态行的变化
 * 看起来是实时的；再快（<1s）对结果没有帮助，只是把同一张码问得更勤；
 * 慢到 3s 时用户点完手机上的确认会盯着「已扫码」两秒以上，像卡住了。
 */
private const val POLL_INTERVAL_MS = 1500L

/**
 * 二维码画布边长。卡片是横向布局（码在左、说明在右），280dp 在 1080p 电视上两米外能扫。
 *
 * 为什么卡片里的间距都这么紧：1080p 电视的可用高度只有约 540dp（density 2.0），
 * 而按钮行在 Column 的最末尾——内容一旦顶出屏幕，被裁掉的正好是按钮，
 * 表现就是「有码可扫、却按不到任何东西」。所以正文行数用 maxLines 兜住，
 * 间距逐个抠过，宁可松紧不均也不要溢出。
 */
private val QR_BOX = 280.dp

/**
 * 二维码区白底内边距。4dp 只作"码不贴白框边缘"的收边：白框本身不能去掉——暗底卡片上
 * 二维码对深浅反转敏感，一部分扫码器会把图案判反；但白框一厚，码就被挤小，
 * 屏上就是用户看到的那一圈白边（静区 2 模块由 QrCode.kt 自己铺，不再靠这里补）。
 */
private val QR_PADDING = 4.dp

/**
 * 小写直接当大写显示：只改渲染、不动缓冲区，输入法合成区因此完全不受影响。
 * 长度不变，所以 OffsetMapping 用 Identity——光标位置不会错位。
 *
 * 这也是唯一稳的做法：onValueChange 里改写字符串会顶掉合成区（实测变成 JNALMA8N 之类），
 * 而靠输入法的 KeyboardCapitalization 并不总生效（不同输入法/设备表现不一）。
 */
internal object UppercaseTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText =
        TransformedText(AnnotatedString(text.text.uppercase()), OffsetMapping.Identity)
}

/**
 * 授权门的状态机。
 *
 * 为什么要有本地态（[LoadingQr] / [QrFailed]）：后端的五个 status 只在"手里已经有一张 sid"
 * 时才存在，而取码本身也会失败；把这两件事混进 pending 会让屏幕显示一张并不存在的码。
 *
 * 五个后端状态在屏幕上的样子见 [AuthGateScreen] 的 KDoc。
 */
private enum class GatePhase {
    /** 正在取码：白框是空的，状态行说「正在获取登录二维码…」 */
    LoadingQr,

    /** 取码失败：状态行 + 提示行说明原因，按钮行仍是刷新 / 退出（见 KDoc：这里给授权码也没用） */
    QrFailed,

    /** pending：二维码在屏上，状态行说「请用微信扫一扫」 */
    WaitingScan,

    /** scanned：状态行说「已扫码，请在手机上点『确认登录』」 */
    Scanned,

    /** expired（自动重取过一次仍过期）：二维码撤下 + 「请点刷新」，等用户按「刷新二维码」 */
    Expired,

    /** need_code：账号未激活，授权码输入框展开，状态行说「账号未激活，请输入授权码」 */
    NeedCode,
}

/**
 * 授权门：未授权时全应用唯一的界面 —— 微信登录二维码 + 状态行 + 补位的授权码输入 + 退出。
 *
 * ## 为什么是「扫码为主、授权码补位」
 *
 * 服务端已经把设备授权路关掉了（实测旧的兑换/补信息动作一律回「服务端已关闭设备授权」或
 * authorized:false，旧的设备身份头也不再被认），也就是说**一张 8 位码单独兑换不出任何身份**。
 * 现在能建立会话的顺序只有一个：wx_qr 取码 → 手机微信扫一扫并确认 → wx_poll 回 ok。
 * 授权码只有一个入口：账号未激活（need_code）时才展开输入框。取码失败 / 二维码过期时
 * **不给**授权码入口——后端要求先有扫码会话，那两个状态下手里有码也只会换回
 * 「登录状态已失效，请重新扫码」，把输入框摆出来等于让用户白敲 8 位。
 * 所以视觉重心永远在二维码上，输入框默认收起、且默认按钮行里也没有它的入口。
 *
 * ## 状态机（status → 屏幕上显示什么 → 用户按什么能做什么）
 *
 * | 状态 | 屏幕 | 遥控器 |
 * |------|------|--------|
 * | pending      | 二维码 + 「请用微信扫一扫」                  | 左/右→刷新二维码 / 退出；确认→执行 |
 * | scanned      | 二维码 + 「已扫码，请在手机上点『确认登录』」  | 同上（此时唯一该做的是等手机） |
 * | expired      | 二维码撤下 + 「二维码已过期，请点刷新」       | 左/右→刷新二维码 / 退出 |
 * | ok           | 本屏不再渲染：会话已落盘，AppRoot 切到正常界面 | 无（门已经没了） |
 * | need_code    | 二维码 + 「账号未激活，请输入授权码」+ 授权码框 | 上→输入框；确认→拉输入法；回车/「激活」→提交；左右→激活 / 刷新二维码 / 退出 |
 * | 取码失败      | 「二维码获取失败，请点刷新」+ 红字原因        | 左/右→刷新二维码 / 退出 |
 *
 * ## 遥控器语义（焦点只有一个来源：这一棵组合树）
 * - 上 / 下：在「授权码输入框（展开时）/ 按钮行」之间移动。二维码区不可聚焦——
 *   它不是可操作项，多停一格只会让人以为那颗码能按。
 * - 左 / 右：按钮行内部换按钮（默认两颗：刷新二维码 / 退出；need_code 时三颗：
 *   激活 / 刷新二维码 / 退出）。交给 Compose 的 D-Pad 焦点搜索，不自己写第二套左右逻辑，
 *   两个来源同时改焦点会一次按键跳两格）。
 * - 确认：输入框里拉出输入法（不直接提交——八位码敲到一半误按确认只换来一次莫名其妙的失败）；
 *   按钮上执行该按钮。
 * - 返回：退出应用（门下面没有别的内容，未授权时 Dest 的内容根本不进组合），
 *   所以这里不弹二次确认，也不属于「返回逐层退出」那条链。
 *
 * ## 轮询的生命期
 * 轮询跑在 LaunchedEffect(ticket?.sid) 里：离开这一屏（扫码成功切界面 / 退出应用）或者
 * sid 变化时，Compose 会取消这个协程。没有任何手动 stop 标志——那种标志迟早漏一处，
 * 结果就是门关掉了、后端还在被每 1.5s 打一下，而屏幕上什么线索都没有。
 *
 * @param onRequestQr 取一张码；失败时带一条已渲染好的文案
 * @param onPollQr 轮询一次；ok 的那次会话已在数据层落盘，这里只做界面切换
 * @param onActivate 授权码激活；返回 null 表示已授权，否则是失败原因
 * @param onExit 退出应用（按返回键或点「退出」都走它）
 */
@Composable
fun AuthGateScreen(
    onRequestQr: suspend () -> QrTicketResult,
    onPollQr: suspend (String) -> QrPollResult,
    onActivate: suspend (String, String) -> String?,
    onExit: () -> Unit,
) {
    // 门的下面没有任何一层，返回键无处可穿，直接结束应用（见 KDoc 的遥控器语义）
    BackHandler(enabled = true) { onExit() }

    var phase by remember { mutableStateOf(GatePhase.LoadingQr) }
    var ticket by remember { mutableStateOf<WxQr?>(null) }
    // need_code 那一次带回来的 token：激活要用它，但账号还没激活、它不是会话，所以只留在内存里
    var pendingToken by remember { mutableStateOf<String?>(null) }
    // 失败/提示的唯一一行：取码失败、轮询抖动、本地闸门、激活失败都写在这里。
    // 不弹窗——弹窗会把刚敲进去的码一起遮住，用户只能凭记忆重敲
    var notice by remember { mutableStateOf<String?>(null) }
    var codeBoxOpen by remember { mutableStateOf(false) }
    // 「过期后自动重取一次」的额度：每张码各有一份（手动刷新时重新领一份，见 refreshQr）
    var autoRetried by remember { mutableStateOf(false) }

    var draft by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val codeFocus = remember { FocusRequester() }
    val buttonFocus = remember { FocusRequester() }

    val failureFallback = stringResource(R.string.settings_auth_failed)
    // 提交前的本地闸门文案：8 位字母数字。放在这里而不是数据层，是因为它只解释「这一屏的输入」
    val invalidCodeHint = stringResource(R.string.gate_code_invalid)

    /**
     * 重新取一张码。失败只报原因 + 让用户再刷新一次，**不**展开授权码框：
     * 没有扫码会话时授权码换不到任何身份（后端回「登录状态已失效」），摆个框只会让人白敲。
     */
    suspend fun loadQr() {
        phase = GatePhase.LoadingQr
        notice = null
        when (val r = onRequestQr()) {
            is QrTicketResult.Ok -> {
                ticket = r.ticket
                pendingToken = null
                codeBoxOpen = false
                phase = GatePhase.WaitingScan
            }
            is QrTicketResult.Failed -> {
                ticket = null
                pendingToken = null
                codeBoxOpen = false
                phase = GatePhase.QrFailed
                notice = r.text
            }
        }
    }

    // 进这一屏先自动取一张码。key 用 Unit：只有「重新进入授权门」才重取——
    // 轮询带来的每次状态变化都不该换掉用户手里正在扫的那张
    LaunchedEffect(Unit) {
        // 启动时不主动拉起输入法：软键盘会把按钮行整个盖住（实测在带软键盘的设备上按不到按钮）。
        // 真要用键盘，确认键会走 keyboard?.show()
        keyboard?.hide()
        loadQr()
    }

    // 轮询：只在手里有 sid 时跑，随这一屏的组合同生共死（见 KDoc 的「轮询的生命期」）
    LaunchedEffect(ticket?.sid) {
        val sid = ticket?.sid ?: return@LaunchedEffect
        while (true) {
            when (val r = onPollQr(sid)) {
                // 抖一下不换码：码还有效，把原因写在提示行上，下个 tick 继续问。
                // 一次网络抖动就把用户踢回「再点一次刷新」是更差的取舍
                is QrPollResult.Failed -> notice = r.text
                is QrPollResult.Ok -> when (val p = r.poll) {
                    WxPoll.Pending -> {
                        notice = null
                        phase = GatePhase.WaitingScan
                    }
                    WxPoll.Scanned -> {
                        notice = null
                        phase = GatePhase.Scanned
                    }
                    WxPoll.Expired -> {
                        // 过期只自动重取一次：后端真坏了的时候无限重取等于无限打后端，
                        // 屏幕上的二维码也会闪个不停。第二张还过期就把「刷新二维码」交给用户按
                        if (!autoRetried) {
                            autoRetried = true
                            loadQr()
                        } else {
                            // 过期也不再给授权码入口：后端要求先有扫码会话（同 loadQr 的说明）
                            ticket = null
                            codeBoxOpen = false
                            phase = GatePhase.Expired
                        }
                        return@LaunchedEffect
                    }
                    is WxPoll.NeedCode -> {
                        pendingToken = p.token
                        codeBoxOpen = true
                        notice = null
                        phase = GatePhase.NeedCode
                        // 账号状态不会因为继续轮询而改变：激活之前问多少次都是 need_code
                        return@LaunchedEffect
                    }
                    // 会话已在数据层落盘、界面也已经被 applyAuthorized 切走：
                    // 这个协程随即随组合一起被取消，return 只是让它别再多问一次
                    is WxPoll.Authorized -> return@LaunchedEffect
                }
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    fun refreshQr() {
        // 手动刷新 = 重新领一份「自动重取」额度：这一张过期时仍该自动再试一次
        autoRetried = false
        scope.launch { loadQr() }
    }

    fun submitCode() {
        if (busy) return
        // 输入框只在 need_code 那一次之后出现（见轮询的 NeedCode 分支，这是唯一入口），
        // 所以这里必然有 token。保留一个非空断言式的返回只为了让类型收窄——
        // 不再配一句"请先刷新二维码"的提示：那条分支在没有入口之后永远走不到，
        // 留着就是一段没人能触发的死代码
        val token = pendingToken ?: return
        // 8 位闸门：一次注定失败的请求（多一个连字符、少一位）根本不该发出去。
        // 归一化口径与数据层 Auth.activate 一致（大写 + 丢非字母数字），区别只在这里多一道长度闸
        val code = draft.uppercase().filter { c -> c in 'A'..'Z' || c in '0'..'9' }
        if (code.length != AUTH_CODE_LENGTH) {
            notice = invalidCodeHint
            return
        }
        busy = true
        notice = null
        scope.launch {
            val text = try {
                onActivate(token, code)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failureFallback
            }
            busy = false
            // 失败时只写原因：draft 原样保留，用户就地改一个字重试，不用从八位从头敲
            if (text != null) notice = text
        }
    }

    // 首焦点：授权码框展开时落在框里，否则落在第一颗按钮上（二维码区不可聚焦，
    // 默认态下按钮行就是这一屏唯一的焦点来源）。key 里带上 busy：提交结束（成功或失败）
    // 必须重新把焦点送回去，否则屏幕上再没有白圈，用户按方向键毫无反应。
    // 抢焦点统一走 TvFirstFocus（等两帧、失败不抛），不在页面里手写重试
    TvFirstFocus(if (codeBoxOpen) codeFocus else buttonFocus, key = codeBoxOpen to busy)

    val statusText = when (phase) {
        GatePhase.LoadingQr -> stringResource(R.string.gate_qr_loading)
        GatePhase.QrFailed -> stringResource(R.string.gate_qr_failed)
        GatePhase.WaitingScan -> stringResource(R.string.gate_qr_hint)
        GatePhase.Scanned -> stringResource(R.string.gate_scanned)
        GatePhase.Expired -> stringResource(R.string.gate_expired)
        GatePhase.NeedCode -> stringResource(R.string.gate_need_code)
    }
    val statusColor = when (phase) {
        GatePhase.QrFailed, GatePhase.Expired -> ModaColors.Danger
        GatePhase.Scanned -> ModaColors.AccentSoft
        else -> ModaColors.TextDim
    }
    // 只在 uuid 变化时重算：轮询每 1.5s 改一次状态，二维码本身一个像素都不该重编码
    val qrText = ticket?.let { WX_QR_TEXT_PREFIX + it.uuid }

    Box(Modifier.fillMaxSize().background(ModaColors.Bg)) {
        // 整屏半透明遮罩：让底色沉下去，卡片才像「浮在最上面」
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)))

        Row(
            Modifier
                .align(Alignment.Center)
                .clip(RoundedCornerShape(28.dp))
                .background(ModaColors.Surface)
                // 一圈比卡片亮一档的细边：暗背景上一块纯色面板没有轮廓，看着像贴图没加载完
                .border(1.dp, ModaColors.SurfaceHigh, RoundedCornerShape(28.dp))
                .padding(horizontal = 40.dp, vertical = 30.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 二维码区：白底不是装饰——二维码对深浅反转敏感，暗色卡片上的图案
            // 会让一部分扫码器直接把黑白判反。白框在没码时退成暗色占位，
            // 尺寸不变，所以取码 / 过期时版面不会跳
            Box(
                Modifier
                    .size(QR_BOX)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (qrText != null) Color.White else ModaColors.SurfaceHigh)
                    .padding(QR_PADDING),
                contentAlignment = Alignment.Center,
            ) {
                if (qrText != null) {
                    QrCodeImage(text = qrText, size = QR_BOX - QR_PADDING * 2)
                }
            }

            Spacer(Modifier.width(36.dp))

            Column(Modifier.width(440.dp)) {
                Text(
                    stringResource(R.string.gate_title),
                    style = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.SemiBold),
                    color = ModaColors.Text,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.gate_message),
                    style = TextStyle(fontSize = 19.sp, lineHeight = 27.sp),
                    color = ModaColors.TextDim,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(12.dp))
                // 状态行：这一屏最该被读到的一句话，所以只随 status 变，不随失败原因变
                Text(
                    statusText,
                    style = TextStyle(fontSize = 22.sp, lineHeight = 30.sp),
                    color = statusColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                // 失败 / 本地闸门提示：就地显示，不换屏、不弹窗。
                // maxLines = 2 是版面保险：提示一长就把下面的按钮挤出屏幕（见 QR_BOX 的说明）
                notice?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        it,
                        modifier = Modifier.fillMaxWidth(),
                        style = TextStyle(fontSize = 18.sp, lineHeight = 26.sp),
                        color = ModaColors.Danger,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (codeBoxOpen) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        // 框只在 need_code 之后出现，所以这里只有一句说明，不存在第二种口径
                        stringResource(R.string.gate_code_hint),
                        style = TextStyle(fontSize = 17.sp, lineHeight = 24.sp),
                        color = ModaColors.TextDim,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(8.dp))
                    // 描边常亮而不是「聚焦才亮」：BasicTextField 的焦点落在它内部的 focusTarget 上，
                    // 外层 onFocusChanged 未必收得到（顺序敏感的坑）。焦点提示交给闪烁的光标
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(ModaColors.Bg)
                            .border(2.dp, ModaColors.AccentSoft, RoundedCornerShape(14.dp))
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                            .alpha(if (busy) 0.6f else 1f),
                    ) {
                        if (draft.isEmpty()) {
                            Text(
                                stringResource(R.string.gate_code_placeholder),
                                modifier = Modifier.fillMaxWidth(),
                                style = TextStyle(fontSize = 24.sp),
                                textAlign = TextAlign.Center,
                                color = ModaColors.TextDim,
                            )
                        }
                        BasicTextField(
                            value = draft,
                            // 缓冲区照收（不改写、不拒收——两种都实测过：改写顶掉 IME 合成区，
                            // 拒收会把合法字母一起吞掉）。合法性由按键拦截 + 提交前闸门两道把守
                            onValueChange = { draft = it },
                            visualTransformation = UppercaseTransformation,
                            enabled = !busy,
                            singleLine = true,
                            // 明文显示：授权码要一个字符一个字符核对，遮成圆点只会让人重敲
                            textStyle = TextStyle(
                                fontSize = 24.sp,
                                color = ModaColors.Text,
                                letterSpacing = 6.sp,
                                textAlign = TextAlign.Center,
                            ),
                            cursorBrush = SolidColor(ModaColors.AccentSoft),
                            keyboardOptions = KeyboardOptions(
                                imeAction = ImeAction.Done,
                                // 软键盘只给 ASCII；大写由 visualTransformation 兜底
                                keyboardType = KeyboardType.Ascii,
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(codeFocus)
                                .onPreviewKeyEvent { event ->
                                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                    when (event.key) {
                                        // 上下是焦点在「输入框 / 按钮行」之间移动，不能被文本框吃掉
                                        Key.DirectionUp -> {
                                            focusManager.moveFocus(FocusDirection.Up)
                                            true
                                        }
                                        Key.DirectionDown -> {
                                            focusManager.moveFocus(FocusDirection.Down)
                                            true
                                        }
                                        // 确认 = 开始编辑：拉出输入法。不在这里直接提交——
                                        // 八位码敲到一半误按确认就被提交掉，用户只会看到一次莫名其妙的失败
                                        Key.DirectionCenter -> {
                                            keyboard?.show()
                                            true
                                        }
                                        // 回车 = 提交（遥控器上的回车走这里）
                                        Key.Enter -> {
                                            submitCode()
                                            true
                                        }
                                        // 其它可打印字符：只放行字母与数字，符号就地吃掉。
                                        // 电视上码是系统输入法一个个按键敲进来的，在这一层拦最干净，
                                        // 而且完全不碰文本缓冲区——不影响任何输入法的合成状态。
                                        // 已经满 8 位时连字母数字一起吃掉：用户手上那张码永远是 8 位，
                                        // 第 9 个字符只可能是一次误触，留在框里就会在下一次提交时
                                        // 变成一条莫名其妙的长码失败。在按键层拒绝而不是改写缓冲区——
                                        // 后者会顶掉 IME 的合成区（见上方实测记录）
                                        else -> {
                                            val ch = event.nativeKeyEvent.unicodeChar.toChar()
                                            val printable = !ch.isISOControl() && ch != Char.MIN_VALUE
                                            printable && (
                                                draft.length >= AUTH_CODE_LENGTH ||
                                                    !(ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9')
                                                )
                                        }
                                    }
                                },
                        )
                    }
                }

                Spacer(Modifier.height(18.dp))
                // 按钮行：默认两颗（刷新二维码 / 退出），need_code 时三颗（激活 / 刷新二维码 / 退出）。
                // 导航轴是「上=输入框（展开时）/ 下=这一行 / 左右=在这一行里换按钮」；左右不自己接管，
                // 交给 Compose 的 D-Pad 焦点搜索（两个来源同时改焦点会一次按键跳两格）
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (codeBoxOpen) {
                        // 主操作占 2 份：视线先落在它身上，刷新与退出是「另一条路」而不是等价选项
                        ActionButton(
                            text = stringResource(if (busy) R.string.gate_activating else R.string.gate_activate),
                            onClick = { submitCode() },
                            enabled = !busy,
                            primary = true,
                            modifier = Modifier.weight(2f).height(58.dp),
                            labelSize = 22.sp,
                            centerLabel = true,
                            cornerRadius = 29.dp,
                        )
                        ActionButton(
                            text = stringResource(R.string.gate_refresh_qr),
                            onClick = { refreshQr() },
                            enabled = !busy,
                            modifier = Modifier.weight(2f).height(58.dp),
                            labelSize = 20.sp,
                            centerLabel = true,
                            cornerRadius = 29.dp,
                        )
                    } else {
                        ActionButton(
                            text = stringResource(R.string.gate_refresh_qr),
                            onClick = { refreshQr() },
                            modifier = Modifier
                                .weight(2f)
                                .height(58.dp)
                                .focusRequester(buttonFocus),
                            primary = true,
                            labelSize = 20.sp,
                            centerLabel = true,
                            cornerRadius = 29.dp,
                        )
                    }
                    ActionButton(
                        text = stringResource(R.string.gate_exit),
                        onClick = onExit,
                        enabled = !busy,
                        modifier = Modifier.weight(1.4f).height(58.dp),
                        labelSize = 20.sp,
                        centerLabel = true,
                        cornerRadius = 29.dp,
                    )
                }
            }
        }
    }
}
