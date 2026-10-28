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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moda.tv.R
import com.moda.tv.ui.components.ActionButton
import com.moda.tv.ui.components.TvFirstFocus
import com.moda.tv.ui.theme.ModaColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 授权门：未授权时全应用唯一的界面，只有「输入框 + 授权 + 退出」三件东西。
 *
 * 为什么不用 Compose 的 [androidx.compose.ui.window.Dialog]：那是独立 window，
 * 会另起一套焦点与返回键分发，和「焦点只有一个来源」直接冲突（遥控器纪律：全应用只许有一条焦点链）。
 * 所以整屏遮罩 + 居中卡片都是自绘的普通组合：焦点在这一个组合树里，返回键也在这条链上。
 *
 * 门本身不做跳转：兑换成功后 [TvViewModel.authSession] 变非空，AppRoot 的分支自动从「门」
 * 换成正常界面，首页的装载由既有链路 applyAuthorized() 负责。
 *
 * @param onAuthorize 兑换入口；返回 null 表示已授权，否则是渲染好的失败原因
 * @param onExit 退出应用（点「退出」或按返回键都走它，不弹二次确认）
 */
/**
 * 小写直接当大写显示：只改渲染、不动缓冲区，输入法合成区因此完全不受影响。
 * 长度不变，所以 OffsetMapping 用 Identity——光标位置不会错位。
 *
 * 这也是唯一稳的做法：onValueChange 里改写字符串会顶掉合成区（实测变成 JNALMA8N 之类），
 * 而靠输入法的 KeyboardCapitalization 并不总生效（不同输入法/设备表现不一）。
 */
/** 授权码固定 8 位：输入框、提交闸门、站点页面三处必须是同一个数 */
internal const val AUTH_CODE_LENGTH = 8

internal object UppercaseTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText =
        TransformedText(AnnotatedString(text.text.uppercase()), OffsetMapping.Identity)
}

@Composable
fun AuthGateScreen(
    onAuthorize: suspend (String) -> String?,
    onExit: () -> Unit,
) {
    // 门的下面没有任何一层：未授权时 Dest 的内容根本不进组合。
    // 所以返回键不该穿透（也无处可穿），直接结束应用——这里不是「返回逐层退出」的那条链。
    BackHandler(enabled = true) { onExit() }

    // 用 TextFieldState + InputTransformation，而不是 value + onValueChange：
    // 后者一旦在回调里改写字符串，IME 的合成态（composing region）就会被顶掉，
    // 实测「输入 zzzz000099，框里变成 JNALMA8N」。InputTransformation 是官方为此提供的入口，
    // 它在 IME 提交的那一刻改写缓冲区，合成过程本身不受影响。
    var draft by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    // 失败原因有两条来源：实现方给的是成品文案；逃出来的异常只留资源 id
    // （e.message 是排错原文，不许上屏）
    var failureText by remember { mutableStateOf<String?>(null) }
    var failureRes by remember { mutableStateOf<Int?>(null) }

    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val fieldFocus = remember { FocusRequester() }
    val failureFallback = stringResource(R.string.settings_auth_failed)
    // 提交前的本地闸门文案：8 位字母数字。放在这里而不是 data 层，是因为它只解释「这一屏的输入」，
    // 换个入口（设置页那一行）话术就不一样。
    val invalidCodeHint = stringResource(R.string.gate_code_invalid)

    // 首焦点必须落在输入框：门只有三个可聚焦点，焦点空着时遥控器按确认毫无反应。
    // 抢焦点统一走 TvFirstFocus（等两帧、失败不抛），不在页面里手写重试。
    TvFirstFocus(fieldFocus)

    // 兑换中三个控件一起禁用，焦点会从任何一个落点上掉下来；兑换一结束（尤其失败后要就地重试）
    // 必须把焦点送回输入框，否则这一次屏幕上再没有白圈，用户按方向键毫无反应。
    // 等两帧：禁用要先落到组合上，早一帧 requestFocus 会被拒。
    // 启动时不主动拉起输入法：输入框一拿到焦点，系统就会弹软键盘，把卡片下半截
    // （授权/退出两个按钮）整个盖住——实测在带软键盘的设备上根本按不到「授权」。
    // 电视上通常没有软键盘，这条是无害的；真要用键盘，按确认键会走 keyboard?.show()。
    LaunchedEffect(Unit) {
        withFrameNanos { }
        withFrameNanos { }
        keyboard?.hide()
    }

    LaunchedEffect(busy) {
        withFrameNanos { }
        withFrameNanos { }
        runCatching { fieldFocus.requestFocus() }
    }

    fun submit() {
        if (busy) return
        // 只允许大写字母与数字，且必须是 8 位——在提交前归一化并校验，一次注定失败的
        // 请求（多一个连字符、少一位）根本不该发出去：
        // 用户手上那张码永远是 8 位，输错了要立刻告诉他，而不是等后端回「授权码无效」。
        // 归一化口径与数据层 Auth.pair 一致（大写 + 丢非字母数字），区别只在这里多一道长度闸。
        val code = draft.uppercase()
            .filter { c -> c in 'A'..'Z' || c in '0'..'9' }
        if (code.length != AUTH_CODE_LENGTH) {
            failureText = invalidCodeHint
            failureRes = null
            return
        }
        busy = true
        failureText = null
        failureRes = null
        scope.launch {
            var text: String? = null
            var res: Int? = null
            try {
                text = onAuthorize(code)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                res = R.string.settings_auth_failed
            }
            busy = false
            // 失败时只写原因：draft 原样保留，用户就地改一个字重试，不用从八位从头敲
            if (text != null) failureText = text
            if (res != null) failureRes = res
        }
    }

    Box(Modifier.fillMaxSize().background(ModaColors.Bg)) {
        // 整屏半透明遮罩：让底色沉下去，卡片才像「浮在最上面」
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)))

        Column(
            Modifier
                .align(Alignment.Center)
                .width(760.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(ModaColors.Surface)
                // 一圈比卡片亮一档的细边：暗背景上一块纯色面板没有轮廓，看着像贴图没加载完
                .border(1.dp, ModaColors.SurfaceHigh, RoundedCornerShape(28.dp))
                .padding(horizontal = 52.dp, vertical = 46.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 卡片里每段文字都显式居中：Column 的 horizontalAlignment 只把「盒子」摆中间，
            // 盒子内部默认仍是左对齐——多行说明会因此变成右边参差的锯齿，看着像没对齐
            Text(
                stringResource(R.string.gate_title),
                modifier = Modifier.fillMaxWidth(),
                style = TextStyle(fontSize = 42.sp, fontWeight = FontWeight.SemiBold),
                color = ModaColors.Text,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(14.dp))
            Text(
                stringResource(R.string.gate_message),
                modifier = Modifier.fillMaxWidth(),
                style = TextStyle(fontSize = 23.sp, lineHeight = 36.sp),
                color = ModaColors.TextDim,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(28.dp))

            // 描边常亮而不是「聚焦才亮」：BasicTextField 的焦点落在它内部的 focusTarget 上，
            // 外层 onFocusChanged 未必收得到（顺序敏感的坑），与其画一个可能永不亮的外框，
            // 不如让这个框恒定表示「这里能输入」，焦点提示交给闪烁的光标。
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    // 反过来做：比卡片更暗一档的嵌入式输入井 + 一道强调色细边，
                    // 比「更亮的方块」更像输入区，也让卡片整体只有一个视觉重心
                    .background(ModaColors.Bg)
                    .border(2.dp, ModaColors.AccentSoft, RoundedCornerShape(16.dp))
                    .padding(horizontal = 22.dp, vertical = 20.dp)
                    .alpha(if (busy) 0.6f else 1f),
            ) {
                if (draft.isEmpty()) {
                    Text(
                        stringResource(R.string.gate_code_placeholder),
                        modifier = Modifier.fillMaxWidth(),
                        style = TextStyle(fontSize = 30.sp),
                        textAlign = TextAlign.Center,
                        color = ModaColors.TextDim,
                    )
                }
                BasicTextField(
                    value = draft,
                    // 缓冲区照收（不改写、不拒收——两种都实测过：改写顶掉 IME 合成区，
                    // 拒收会把合法字母一起吞掉）。合法性由上面的按键拦截 + 提交前闸门两道把守。
                    onValueChange = { draft = it },
                    visualTransformation = UppercaseTransformation,
                    // 只收大写字母与数字：小写自动变大写，其它字符（含空格、连字符）直接不进缓冲区。
                    // 上限 8 位：后端只认 8 位，多敲的字只会换来一次白跑的兑换请求。
                    // 刻意**不做输入变换**：在这个 Compose 版本上，无论整条重写还是逐段替换，
                    // 只要在缓冲区内改写，输入法的合成区就会被顶掉。实测：
                    //   整条 replace + 大写/过滤/截断 → 输入 zzzz000099 变成 JNALMA8N
                    //   去掉截断后                    → 变成 ZZ
                    // 同版本里逐段替换用到的 changes API 还是实验性的、签名对不上。
                    // 所以：大写交给输入法（下面 keyboardOptions 的 Characters + ASCII），
                    // 非法字符与长度由数据层 Auth.pair 在提交时统一剥离——它是唯一口径。
                    enabled = !busy,
                    singleLine = true,
                    // 明文显示：授权码要一个字符一个字符核对，遮成圆点只会让人重敲
                    textStyle = TextStyle(
                        fontSize = 30.sp,
                        color = ModaColors.Text,
                        letterSpacing = 6.sp,
                        textAlign = TextAlign.Center,
                    ),
                    cursorBrush = SolidColor(ModaColors.AccentSoft),
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Done,
                        // 软键盘只给 ASCII；大写由 visualTransformation 兜底
                        // （实测靠输入法 Capitalization 并不总生效，设备/输入法各异）
                        keyboardType = KeyboardType.Ascii,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(fieldFocus)
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (event.key) {
                                // 上下是焦点在本门三件东西之间移动，不能被文本框吃掉
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
                                    submit()
                                    true
                                }
                                // 其它可打印字符：只放行字母与数字，符号就地吃掉。
                                // 电视上码是从遥控器/系统输入法一个个按键敲进来的，在这一层拦最干净，
                                // 而且完全不碰文本缓冲区——不会影响任何输入法的合成状态。
                                // 已经满 8 位时连字母数字一起吃掉：用户手上那张码永远是 8 位，
                                // 第 9 个字符只可能是一次误触，留在框里就要在下一次提交时
                                // 变成一条莫名其妙的长码失败。在按键层拒绝而不是改写缓冲区——
                                // 后者会顶掉 IME 的合成区（见上方实测记录）。
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

            // 失败原因就地显示在输入框下方：换屏或弹窗都会把刚敲的内容一起带走
            val failure = failureText ?: failureRes?.let { failureFallback }
            if (failure != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    failure,
                    modifier = Modifier.fillMaxWidth(),
                    style = TextStyle(fontSize = 21.sp, lineHeight = 30.sp),
                    color = ModaColors.Danger,
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(Modifier.height(26.dp))
            // 两个按钮并排：门的导航轴因此变成「上=输入框 / 下=这一行 / 左右=在这一行里换按钮」。
            // 左右不再被吞掉（此前纵向排列时才需要吞），横向移动交给 Compose 的 D-Pad 焦点搜索，
            // 别自己再写一套左右键逻辑——那会和焦点系统抢键（两个来源同时改焦点，按一下跳两格）。
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ActionButton(
                    text = stringResource(if (busy) R.string.gate_authorizing else R.string.gate_submit),
                    onClick = { submit() },
                    enabled = !busy,
                    primary = true,
                    // 主操作占 2/3：视线先落在它身上，退出只是「另一条路」而不是等价选项
                    modifier = Modifier.weight(2f).height(68.dp),
                    labelSize = 27.sp,
                    centerLabel = true,
                    cornerRadius = 34.dp,
                )
                ActionButton(
                    text = stringResource(R.string.gate_exit),
                    onClick = onExit,
                    enabled = !busy,
                    modifier = Modifier.weight(1f).height(68.dp),
                    labelSize = 23.sp,
                    centerLabel = true,
                    cornerRadius = 34.dp,
                )
            }
        }
    }
}
