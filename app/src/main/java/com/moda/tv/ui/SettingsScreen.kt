package com.moda.tv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.focusable
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.size
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moda.tv.BuildConfig
import com.moda.tv.data.SaveOutcome
import com.moda.tv.ui.components.ActionButton
import com.moda.tv.ui.components.SectionTitle
import com.moda.tv.ui.theme.ModaColors
import com.moda.tv.ui.theme.TvBody
import com.moda.tv.ui.theme.TvCaption
import com.moda.tv.ui.theme.TvTitle
import kotlinx.coroutines.launch

/** 设置页的一条操作反馈：成功用普通色，失败用 Danger，肉眼可分辨 */
private data class Notice(val text: String, val failed: Boolean)

/** 设置页三行「清空」对应的一次确认：标题、警示文案与完成提示跟着它走 */
private enum class PendingClear(
    val title: String,
    val message: String,
    val doneText: String,
) {
    History(
        title = "清空全部播放记录？",
        message = "将清空所有观看记录与播放进度，删除后无法恢复。",
        doneText = "已清除播放记录",
    ),
    Favorites(
        title = "清空全部收藏？",
        message = "将清空所有收藏，删除后无法恢复。",
        doneText = "已清除收藏",
    ),
    SearchHistory(
        title = "清空全部搜索历史？",
        message = "将清空全部搜索历史，删除后无法恢复。",
        doneText = "已清除搜索历史",
    ),
}

/**
 * 设置页：只读信息 + 代理网址 + 数据清理。
 *
 * 屏幕自身不碰网络也不碰 DataStore——代理网址的校验/探活/落盘全部由
 * [onSaveApiBase] 的实现方（SettingsRoute）负责，这里只把结果渲染出来：
 * 成功才退出编辑态，失败留在输入框里并提示原因，让用户能直接改。
 */
@Composable
fun SettingsScreen(
    /** 当前生效的代理网址；由 store 的 Flow 驱动，改完立刻变，不需要重启 */
    apiBase: String,
    /**
     * 校验 + 探活 + 保存。返回 null 表示已生效，否则是给用户看的失败原因。
     * 失败时实现方必须什么都没写，屏幕这边也只提示、不改 [apiBase]。
     */
    onSaveApiBase: suspend (String) -> String?,
    /**
     * 与 [onSaveApiBase] 同一套校验，但返回结构化结果：
     * 局域网弹窗要拿"规范化后的实际地址"来回显与刷新，光有 null 不够。
     */
    onCommitApiBase: suspend (String) -> SaveOutcome = { raw ->
        onSaveApiBase(raw)?.let { SaveOutcome.Fail(it) } ?: SaveOutcome.Ok(raw)
    },
    /** 恢复默认地址；成功后 [apiBase] 会随之变回默认值 */
    onResetApiBase: suspend () -> Unit,
    onClearHistory: () -> Unit,
    onClearFavorites: () -> Unit,
    onClearSearchHistory: () -> Unit,
    /** 重看版权声明；真正的弹窗挂在 AppRoot 上，和启动时自动弹出的那一个是同一个 */
    onOpenCopyright: () -> Unit = {},
    onBack: () -> Unit,
    versionName: String = BuildConfig.VERSION_NAME,
    versionCode: Int = BuildConfig.VERSION_CODE,
) {
    var editing by remember { mutableStateOf(false) }
    // 扫码弹窗的显隐。只有它为 true 时 LAN 服务才在监听，关闭即释放端口
    var lanConfig by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf(apiBase) }
    var checking by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<Notice?>(null) }
    // 三行的「清空」都是不可撤销的批量删除，点一下先弹确认，确认后才真正清除
    var pendingClear by remember { mutableStateOf<PendingClear?>(null) }

    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val backFocus = remember { FocusRequester() }
    val fieldFocus = remember { FocusRequester() }

    // 首帧布局完成前 requestFocus 会直接抛，等两帧再抢；入口焦点落在「返回」上，
    // 与历史/收藏页一致，用户第一下按方向键就能走到各项操作
    LaunchedEffect(Unit) {
        withFrameNanos { }
        withFrameNanos { }
        runCatching { backFocus.requestFocus() }
    }

    // 进入编辑才拉输入法：遥控器没有键盘，不显式 show() 的话模拟器上只是个空框
    LaunchedEffect(editing) {
        if (editing) {
            withFrameNanos { }
            runCatching { fieldFocus.requestFocus() }
            keyboard?.show()
        } else {
            keyboard?.hide()
        }
    }

    fun startEdit() {
        draft = apiBase
        notice = null
        editing = true
    }

    fun commit() {
        if (checking) return
        checking = true
        scope.launch {
            val error = try {
                onSaveApiBase(draft)
            } catch (e: Exception) {
                e.message ?: "校验失败"
            }
            checking = false
            if (error == null) {
                // 生效后才退出编辑态：失败留在框里，用户能就地改而不是从头再来
                editing = false
                notice = Notice("已生效：代理网址已更新，后续请求立即走新地址", failed = false)
            } else {
                notice = Notice(error, failed = true)
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(ModaColors.Bg)
            .padding(start = 48.dp, end = 48.dp, top = 32.dp, bottom = 24.dp),
    ) {
        // 不放「返回」按钮：遥控器返回键已经能退出本页，
        // 屏幕上再占一个可聚焦格位只会和下面的行抢焦点。
        Text("设置", style = TvTitle, color = ModaColors.Text)

        Spacer(Modifier.height(20.dp))

        // 滚动状态必须 hoist 到组合外再复用。
        // 直接写 rememberScrollState() 在 Modifier 链里时，每次重组都会新建一个 ScrollState，
        // 滚动位置立刻被重置 —— 表现就是「往下滑到一半，再往上就滑不动了」。
        val scrollState = rememberScrollState()
        val scope2 = rememberCoroutineScope()
        // 上下键专门滚页面，左右键在按钮之间移动焦点。
        //
        // 为什么必须这么做：上面的信息行（版本号/分辨率/代理网址）是纯文本不可聚焦，
        // 焦点系统只能通过「把焦点移到某元素」来触发滚动。一旦滚到底部、焦点落在按钮上，
        // 再按上键只会在按钮/返回之间跳，永远滚不回顶部——这就是「往下滑以后再往上就滑不动」的成因。
        Column(
            Modifier
                // 关键：这里不能用 fillMaxSize()。
                // fillMaxSize 会把 Column 的高度钉死成屏幕高度，于是「内容比屏幕长」这件事
                // 对它不成立，verticalScroll 就没有可滚动的余量——
                // 表现就是焦点走到最后一行时页面不再跟随滚动，那一行被挤在屏幕外看不见。
                // 只给宽度、高度随内容自然增长，滚动才真正生效。
                .fillMaxWidth()
                .verticalScroll(scrollState)
                // 底部留足空白：最后一行聚焦时会放大并画 3dp 描边，
                // 贴底会被裁掉一半，留 40dp 才完整。
                .padding(bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            // 一列到底，不分小类：电视上分组标题反而把有限的可视高度吃得更多，
            // 用户要的就是「扫一眼看到版本号、网址、清理入口」。
            // 纯展示行不带箭头：让「能点」与「不能点」一眼可分
            SettingRow("应用名称", "ModaTV")
            SettingRow("版本号", versionName)
            SettingRow("构建号", versionCode.toString())
            SettingRow("Android 版本", androidVersion())
            SettingRow("分辨率", resolutionText())
            // 代理网址：点这一行直接进编辑框手动输入（电视上用系统输入法）。
            // 扫码配置是更省事的可选入口，但手动输入必须始终可用——
            // 手机不在手边、或局域网不通时，用户仍然要能改地址。
            SettingRow(
                label = "代理网址",
                value = apiBase,
                chevron = true,
                onClick = {
                    draft = apiBase
                    editing = true
                    notice = null
                },
            )

            if (editing) {
                Spacer(Modifier.height(10.dp))
                val focusManager = LocalFocusManager.current
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 19.sp, color = ModaColors.Text),
                    cursorBrush = SolidColor(ModaColors.AccentSoft),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { commit() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(ModaColors.Surface)
                        .border(2.dp, ModaColors.AccentSoft, RoundedCornerShape(10.dp))
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                        .focusRequester(fieldFocus)
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (event.key) {
                                Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Up)
                                Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Down)
                                Key.DirectionCenter, Key.Enter -> { commit(); true }
                                else -> false
                            }
                        },
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SettingRow(
                        label = if (checking) "验证中…" else "保存并验证",
                        value = "确定",
                        chevron = true,
                        onClick = { if (!checking) commit() },
                    )
                    SettingRow(
                        label = "取消",
                        value = "放弃",
                        chevron = true,
                        onClick = { editing = false; draft = apiBase; notice = null },
                    )
                }
                Spacer(Modifier.height(8.dp))
            }

            // 扫码配置作为可选入口：手机在手边时比遥控器打字快得多
            SettingRow(
                label = "扫码配置代理网址",
                value = "打开二维码",
                chevron = true,
                onClick = { lanConfig = true },
            )

            Spacer(Modifier.height(8.dp))
            run {

                Spacer(Modifier.height(14.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // 不再单列「修改代理网址」：上面的「代理网址」行本身就是入口
                    // （带箭头、可点击 → 打开扫码配置），再列一行是同一个动作的第二个入口。
                    SettingRow(
                        label = "恢复默认地址",
                        value = if (checking) "处理中…" else "恢复",
                        chevron = true,
                        onClick = {
                            if (!checking) {
                                checking = true
                                scope.launch {
                                    try {
                                        onResetApiBase()
                                        notice = Notice("已恢复默认地址", failed = false)
                                    } catch (e: Exception) {
                                        notice = Notice(e.message ?: "恢复默认失败", failed = true)
                                    } finally {
                                        checking = false
                                    }
                                }
                            }
                        },
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "修改后立即生效，无需重启应用。保存前会先请求一次探活，不通则不保存。",
                    style = TvCaption.copy(fontSize = 15.sp),
                    color = ModaColors.TextDim,
                )
            }

            Spacer(Modifier.height(8.dp))
            run {
                run {
                    SettingRow(
                        label = "清除播放记录",
                        value = "清空",
                        chevron = true,
                        onClick = { pendingClear = PendingClear.History },
                    )
                    SettingRow(
                        label = "清除收藏",
                        value = "清空",
                        chevron = true,
                        onClick = { pendingClear = PendingClear.Favorites },
                    )
                    SettingRow(
                        label = "清除搜索历史",
                        value = "清空",
                        chevron = true,
                        onClick = { pendingClear = PendingClear.SearchHistory },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            // 版权声明常驻入口：启动弹窗一闪而过，用户想回看时得有地方能找。
            // 文案与启动弹窗完全同源（CopyrightNoticeDialog），不会出现两套说法。
            SettingRow(
                label = "版权声明",
                value = "免费软件 · 查看",
                chevron = true,
                onClick = onOpenCopyright,
            )

            notice?.let { n ->
                Text(
                    n.text,
                    style = TvBody.copy(fontSize = 18.sp),
                    color = if (n.failed) ModaColors.Danger else ModaColors.AccentSoft,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
            }
        }
    }

    // 清空确认：三行共用同一个弹窗，标题、文案与真正执行的动作都由 pendingClear 决定
    pendingClear?.let { pending ->
        ClearConfirmDialog(
            title = pending.title,
            message = pending.message,
            onConfirm = {
                pendingClear = null
                when (pending) {
                    PendingClear.History -> onClearHistory()
                    PendingClear.Favorites -> onClearFavorites()
                    PendingClear.SearchHistory -> onClearSearchHistory()
                }
                notice = Notice(pending.doneText, failed = false)
            },
            onDismiss = { pendingClear = null },
        )
    }

    // 扫码配置弹窗：服务在它进入组合时启动、离开组合时停止（见 LanConfigDialog 的 DisposableEffect）
    if (lanConfig) {
        LanConfigDialog(
            url = apiBase,
            save = onCommitApiBase,
            onDismiss = { lanConfig = false },
            onManualEdit = {
                // 降级路径：拿不到局域网 IP 时改为手动输入（系统输入法）
                lanConfig = false
                startEdit()
            },
        )
    }
}

@Composable
private fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(ModaColors.Surface)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        content = content,
    )
}

/**
 * 一行设置项：左标题、右内容。
 *
 * 布局照 LetongManage 的 item_text.xml：标题占 2 份宽、内容占 1 份宽、标题灰色（#999999），
 * 内容右侧可挂一个小箭头表示「这行还能点进去」。电视上左右分栏比上下堆叠更好扫读：
 * 眼睛只需要在同一水平线上左右移动。
 *
 * @param chevron 是否显示行尾箭头。只有「点进去还有下一级」的行才显示，
 *                纯展示的信息行（版本号等）不显示，否则会暗示点它有用。
 */
@Composable
private fun SettingRow(
    label: String,
    value: String,
    chevron: Boolean = false,
    /** 非空即可点击：chevron 只负责「看起来能进」，真正让它能按下去的是这个回调 */
    onClick: (() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }

    // 可点击行用 tv-material 的 Card 承载：它的 focusedContainerColor/ContentColor
    // 由组件内部驱动，聚焦必然可见（手搓 Modifier.focusable 实测不触发重组）。
    //
    // 关键：内容色必须在 Card 的**内部作用域**读取。
    // 在函数体顶部读 LocalContentColor 拿到的是外层颜色（近黑），
    // 再显式传给 Text 就绕过了 Card 的 focusedContentColor —— 这正是「整页黑乎乎」的原因。
    // 现在改成 Text 不传 color，完全继承 Card 提供的内容色。
    // 颜色由调用方显式传入，不走 LocalContentColor。
    // 原因：不可点击行走的是裸 Box 分支，没有 ContentColor 作用域，
    // LocalContentColor 在那里取到的是未初始化的近黑色 —— 整行发黑就是这么来的。
    val content: @Composable (Color, Color) -> Unit = { labelColor, valueColor ->
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = TvBody.copy(fontSize = 19.sp),
                color = labelColor,
                modifier = Modifier.weight(1f),
            )
            Row(
                Modifier.weight(1.5f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                Text(
                    text = value,
                    style = TvBody.copy(fontSize = 19.sp),
                    color = valueColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (chevron) {
                    Spacer(Modifier.width(10.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = valueColor,
                        modifier = Modifier.size(26.dp),
                    )
                }
            }
        }
    }

    if (onClick == null) {
        // 纯展示行：直接给最终颜色，不依赖任何 ContentColor 作用域
        Box(Modifier.fillMaxWidth()) {
            content(ModaColors.TextDim, ModaColors.Text)
        }
    } else {
        // 聚焦状态用 onFocusChanged 挂在 **Card 自己的 modifier** 上。
        // 之前两个错误都栽在这：
        //   ① 挂在内层 Box 上 → 焦点落在 Card，Box 收不到，focused 永远 false（白底白字）；
        //   ② 传 interactionSource 参数 → tv-material 的 Card 不吃这个组合，整行变得不可聚焦，
        //      遥控器直接跳过它，于是「代理网址」这一行按不到、扫码弹窗也就打不开。
        Card(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused },
            shape = CardDefaults.shape(shape = RoundedCornerShape(10.dp)),
            colors = CardDefaults.colors(
                containerColor = Color.Transparent,
                contentColor = ModaColors.Text,
                focusedContainerColor = ModaColors.Focus,
                focusedContentColor = ModaColors.Bg,
                pressedContainerColor = ModaColors.AccentSoft,
                pressedContentColor = Color.White,
            ),
            scale = CardDefaults.scale(scale = 1f, focusedScale = 1.02f, pressedScale = 1f),
            border = CardDefaults.border(
                border = Border.None,
                focusedBorder = Border(BorderStroke(3.dp, ModaColors.Focus), 10.dp),
            ),
        ) {
            Box(Modifier.fillMaxWidth()) {
                if (focused) {
                    content(ModaColors.Bg.copy(alpha = 0.78f), ModaColors.Bg)
                } else {
                    content(ModaColors.TextDim, ModaColors.Text)
                }
            }
        }
    }
}

private fun androidVersion(): String =
    "Android ${android.os.Build.VERSION.RELEASE}（API ${android.os.Build.VERSION.SDK_INT}）"

/** 分辨率同时给像素与 dp：像素对上电视的实际面板，dp 才是布局真正用的那一套 */
@Composable
private fun resolutionText(): String {
    val config = LocalConfiguration.current
    val metrics = LocalContext.current.resources.displayMetrics
    return "${metrics.widthPixels}×${metrics.heightPixels}（${config.screenWidthDp}×${config.screenHeightDp} dp）"
}
