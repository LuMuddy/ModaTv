package com.moda.tv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import com.moda.tv.BuildConfig
import com.moda.tv.data.SaveOutcome
import com.moda.tv.data.local.DecoderMode
import com.moda.tv.data.local.PlayerCore
import com.moda.tv.data.local.PlayerSettings
import com.moda.tv.data.local.PlayerSettingsStoreImpl
import com.moda.tv.data.local.PlayerSpeedOptions
import com.moda.tv.ui.player.PlayerEngines
import com.moda.tv.ui.theme.ModaColors
import com.moda.tv.ui.theme.TvBody
import com.moda.tv.ui.theme.TvCaption
import com.moda.tv.ui.theme.TvTitle
import kotlinx.coroutines.launch

/** 子页的一条操作反馈：成功用普通色，失败用 Danger，肉眼可分辨 */
internal data class Notice(val text: String, val failed: Boolean)

/** 「清空」类操作的确认意图：标题、警示文案与完成提示跟着它走 */
internal enum class PendingClear(
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
 * 播放设置的读与写。
 *
 * 菜单页要用它显示「当前值摘要」，播放子页要改它，两边必须是同一份状态：
 * 各自 remember 一份的话，子页改完返回菜单，摘要要等重新读盘才更新，中间那一帧是旧值。
 */
internal class PlayerSettingsHolder(private val onWrite: (PlayerSettings) -> Unit) {
    var value by mutableStateOf(PlayerSettings())

    fun apply(next: PlayerSettings) {
        value = next
        onWrite(next)
    }
}

/**
 * @param onUpdatePlayerSettings 落盘出口。传 TvViewModel 的实现时写操作跑在 viewModelScope 上，
 *        即便用户按下返回立刻离开子页，写也不会随组合一起被取消。
 */
@Composable
internal fun rememberPlayerSettingsHolder(
    onUpdatePlayerSettings: ((PlayerSettings) -> Unit)? = null,
): PlayerSettingsHolder {
    val context = LocalContext.current
    val store = remember(context) { PlayerSettingsStoreImpl.create(context) }
    val scope = rememberCoroutineScope()
    val holder = remember(store) {
        PlayerSettingsHolder { next ->
            val sink = onUpdatePlayerSettings
            if (sink != null) {
                sink(next)
            } else {
                // 写失败只影响下次启动读到的值，不该让设置页卡住或报错
                scope.launch { runCatching { store.save(next) } }
            }
        }
    }
    LaunchedEffect(store) {
        holder.value = store.current()
    }
    return holder
}

/**
 * 子页共用的骨架：固定头部 + 唯一一个滚动区。
 *
 * 滚动区必须 weight(1f)。Column 对「没有 weight 的子项」的测量上限是无限高，
 * 滚动视口于是比真实可见区域高出一截，最后一行连同它的底部留白永远滚不出来
 * （白框被屏幕下沿切掉就是这个原因）。weight(1f) 拿到的是真实剩余高度。
 * 也不能改用 fillMaxSize()：那会把高度钉成内容高度，滚动余量反而消失。
 *
 * 底部那 40dp 是滚动内容的一部分（在 verticalScroll 之后、padding 之内），
 * 所以它真的能被滚出来，最后一行聚焦放大 + 3dp 描边时不会被裁。
 */
@Composable
internal fun SettingsSectionScaffold(
    title: String,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(ModaColors.Bg)
            .padding(start = 48.dp, end = 48.dp, top = 32.dp, bottom = 24.dp),
    ) {
        // 不放「返回」按钮：遥控器返回键已经能退出本页（AppRoot 的 BackHandler 全局接管），
        // 屏幕上再占一个可聚焦格位只会和下面的行抢焦点。
        Text(title, style = TvTitle, color = ModaColors.Text)
        if (subtitle != null) {
            Text(
                subtitle,
                style = TvCaption,
                color = ModaColors.TextDim,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Spacer(Modifier.height(20.dp))

        // 滚动状态必须 hoist 出来复用：写在 Modifier 链里会让每次重组都新建一个 ScrollState，
        // 滚动位置立刻被重置——表现就是「往下滑到一半，再往上就滑不动了」。
        val scrollState = rememberScrollState()
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(scrollState)
                .padding(bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

// ==================== 一、播放设置 ====================

/**
 * 播放设置子页。这一段全是「会影响下次起播」的开关，是用户进设置最常见的目的，
 * 所以放在菜单第一张卡。
 */
@Composable
internal fun PlaybackSettingsSection(holder: PlayerSettingsHolder) {
    val context = LocalContext.current
    /**
     * IJK 探测只做一次。
     * isIjkAvailable() 首次调用会真的 new 一个 IjkMediaPlayer 验 so，开销不小，
     * 放进 remember 避免每次重组都付一遍。
     */
    val ijkUsable = remember(context) { PlayerEngines.isIjkAvailable() }
    val settings = holder.value

    SettingsSectionScaffold(title = SettingsSectionId.Playback.title) {
        SectionCard {
            // 内核：按 PlayerCore.entries 生成，而不是手写两行。
            // 枚举以后加档位时这里自动跟上，不用再回来改一遍布局。
            CyclingRow(
                label = "播放器内核",
                value = settings.playerCore.label,
                // 选了 IJK 但这台机器没有 so 时必须如实提示：
                // 工厂会静默回退 Exo，不说明用户会以为设置没生效
                warning = if (settings.playerCore == PlayerCore.Ijk && !ijkUsable) {
                    "本机不可用，实际仍在使用 ExoPlayer"
                } else {
                    null
                },
                hint = "ExoPlayer 兼容性好、系统自带；IJK 打包了 ffmpeg 软解，老片与特殊编码更能播。" +
                    "这里是默认值，播放页里改的只作用于当前这部剧。",
                onCycle = {
                    val entries = PlayerCore.entries
                    val i = entries.indexOf(settings.playerCore).coerceAtLeast(0)
                    holder.apply(
                        settings.copy(playerCore = entries[(i + 1) % entries.size]),
                    )
                },
            )

            CyclingRow(
                label = "解码方式",
                value = settings.decoderMode.label,
                hint =
                    // 这段说明必须准确：ExoPlayer 没打包 ffmpeg 扩展，它的「软解」
                    // 只能是挑系统自带的软件编解码器，系统没带就仍退回硬解；
                    // 只有 IJK 的软解才是真正打包在 so 里的 ffmpeg。
                    "硬解省电、4K 更流畅；软解兼容性更好。注意：ExoPlayer 内核的软解只能挑" +
                        "系统自带的软件解码器，系统没带就仍走硬解；IJK 内核的软解才是内置 " +
                        "ffmpeg 的真软解，老片、特殊编码要靠它。播放页里改的只作用于当前这部剧。",
                onCycle = {
                    val entries = DecoderMode.entries
                    val i = entries.indexOf(settings.decoderMode).coerceAtLeast(0)
                    holder.apply(
                        settings.copy(decoderMode = entries[(i + 1) % entries.size]),
                    )
                },
            )

            CyclingRow(
                label = "默认播放倍速",
                value = speedLabelOf(settings.defaultSpeed),
                hint = "每次起播都用这个倍速；播放页里临时调的速度只影响当前这一集。",
                onCycle = {
                    val i = PlayerSpeedOptions
                        .indexOfFirst { it.value == settings.defaultSpeed }
                        .coerceAtLeast(0)
                    holder.apply(
                        settings.copy(
                            defaultSpeed = PlayerSpeedOptions[(i + 1) % PlayerSpeedOptions.size].value,
                        ),
                    )
                },
            )

            CyclingRow(
                label = "自动跳片头",
                value = onOff(settings.skipIntroEnabled),
                hint = "只对从来没播过的视频生效；看过的剧不会再把片头裁掉。",
                onCycle = {
                    holder.apply(settings.copy(skipIntroEnabled = !settings.skipIntroEnabled))
                },
            )

            CyclingRow(
                label = "自动跳片尾",
                value = onOff(settings.skipOutroEnabled),
                hint = "到片尾直接连播下一集。关闭后仍会在播到 95% 时记为已看完。",
                onCycle = {
                    holder.apply(settings.copy(skipOutroEnabled = !settings.skipOutroEnabled))
                },
            )
        }
    }
}

// ==================== 二、网络与数据 ====================

/**
 * 网络与数据子页：代理网址（含编辑态）/ 扫码配置 / 恢复默认地址 / 三项清空。
 *
 * 屏幕自身不碰网络：校验、探活、落盘全部由回调的实现方（SettingsGroupRoute）负责，
 * 这里只把结果渲染出来。
 */
@Composable
internal fun NetworkSettingsSection(
    apiBase: String,
    onSaveApiBase: suspend (String) -> String?,
    onCommitApiBase: suspend (String) -> SaveOutcome,
    onResetApiBase: suspend () -> Unit,
    onClearHistory: () -> Unit,
    onClearFavorites: () -> Unit,
    onClearSearchHistory: () -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    // 扫码弹窗的显隐。只有它为 true 时 LAN 服务才在监听，关闭即释放端口
    var lanConfig by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf(apiBase) }
    var checking by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<Notice?>(null) }
    // 三项「清空」都是不可撤销的批量删除，点一下先弹确认，确认后才真正清除
    var pendingClear by remember { mutableStateOf<PendingClear?>(null) }

    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val fieldFocus = remember { FocusRequester() }

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

    SettingsSectionScaffold(
        title = SettingsSectionId.Network.title,
        subtitle = "修改后立即生效，无需重启应用。保存前会先请求一次探活，不通则不保存。",
    ) {
        SectionCard {
            // 代理网址：点这一行直接进编辑框手动输入（电视上用系统输入法）。
            // 扫码配置是更省事的可选入口，但手动输入必须始终可用——
            // 手机不在手边、或局域网不通时，用户仍然要能改地址。
            SettingRow(
                label = "代理网址",
                value = apiBase,
                chevron = true,
                onClick = { startEdit() },
            )

            if (editing) {
                // 编辑区是「代理网址」这一行的下挂内容，紧跟它、不再额外吃行距，
                // 这样它和上面那行的关系看起来就是「展开」而不是「另起一段」。
                // 左右补 RingRoom：编辑区本身不聚焦，但它要和外层卡片的行左边缘对齐。
                Column(
                    Modifier.padding(horizontal = RingRoom),
                ) {
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
                            // 输入框底色比卡片再亮一档：它就在卡片内部，
                            // 用同一个 Surface 色会和背景糊成一片，看不出这里能输入
                            .background(ModaColors.SurfaceHigh)
                            .border(2.dp, ModaColors.AccentSoft, RoundedCornerShape(10.dp))
                            .padding(horizontal = 16.dp, vertical = 14.dp)
                            .focusRequester(fieldFocus)
                            .onPreviewKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown) {
                                    return@onPreviewKeyEvent false
                                }
                                when (event.key) {
                                    Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Up)
                                    Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Down)
                                    Key.DirectionCenter, Key.Enter -> { commit(); true }
                                    else -> false
                                }
                            },
                    )
                    Row {
                        SettingRow(
                            label = if (checking) "验证中…" else "保存并验证",
                            value = "确定",
                            chevron = true,
                            modifier = Modifier.weight(1f),
                            onClick = { if (!checking) commit() },
                        )
                        SettingRow(
                            label = "取消",
                            value = "放弃",
                            chevron = true,
                            modifier = Modifier.weight(1f),
                            onClick = { editing = false; draft = apiBase; notice = null },
                        )
                    }
                }
            }

            // 扫码配置作为可选入口：手机在手边时比遥控器打字快得多
            SettingRow(
                label = "扫码配置代理网址",
                value = "打开二维码",
                chevron = true,
                onClick = { lanConfig = true },
            )

            // 不再单列「修改代理网址」：上面的「代理网址」行本身就是入口
            // （带箭头、可点击 → 打开编辑框），再列一行是同一个动作的第二个入口。
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

            // 数据清理：三项都是不可撤销的批量删除，逐项二次确认
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
            HintText("清空操作不可撤销，每项都会先弹一次确认。")
        }

        // 操作反馈落在滚动内容的末尾：它可能来自这一页的任意一处，
        // 挂在某一行内部会让别处触发的消息跑到看不见的位置。
        notice?.let { n ->
            Text(
                n.text,
                style = TvBody.copy(fontSize = 18.sp),
                color = if (n.failed) ModaColors.Danger else ModaColors.AccentSoft,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }

    // 清空确认：三项共用同一个弹窗，标题、文案与真正执行的动作都由 pendingClear 决定
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

// ==================== 三、关于 ====================

/**
 * 关于子页：全是只读信息，没有一行可点。
 *
 * 版权声明入口不在这里，而在顶层菜单页：它和「播放/网络/关于」三个分组不是一回事
 * （那三张卡是"进去改东西"，它是一次性查看），混进关于子页会让"关于"既有可点又有不可点。
 */
@Composable
internal fun AboutSettingsSection(
    versionName: String = BuildConfig.VERSION_NAME,
    versionCode: Int = BuildConfig.VERSION_CODE,
) {
    SettingsSectionScaffold(title = SettingsSectionId.About.title) {
        SectionCard {
            // 纯展示行不带箭头：让「能点」与「不能点」一眼可分
            SettingRow("应用名称", "ModaTV")
            SettingRow("版本号", versionName)
            SettingRow("构建号", versionCode.toString())
            SettingRow("Android 版本", androidVersion())
            SettingRow("分辨率", resolutionText())
        }
    }
}

// ==================== 共用行组件 ====================

/**
 * 聚焦白圈离卡片的缝隙。
 *
 * tv-material 的 Border(inset) 是**向外扩**的（库里绘制时 inset 取负），
 * 所以白圈整个落在卡片布局边界之外；scroll/Lazy 容器又会 clip 到自身视口，
 * 贴着视口边的那一行就会被切掉一圈。缝隙给 4dp，圈和卡片才分得开、又足够小。
 */
internal val RingInset = 4.dp

/**
 * 每一行必须为白圈预留的透明空间（每边）。
 *
 * = 缝隙 4dp + 描边 3dp + 1dp 抗锯齿余量。这块 padding 属于**本行布局**，
 * 于是白圈画在 padding 里、行与行的缝隙也由它提供，视口边界永远切不到圈。
 */
internal val RingRoom = RingInset + 3.dp + 1.dp

/**
 * 段内说明文字。不可聚焦，只负责解释它上面那一行到底在改什么。
 *
 * 自带 RingRoom 的水平缩进：它要和上面那行卡片的左边对齐，
 * 而不是顶到 SectionCard 的内边距上。
 */
@Composable
internal fun HintText(text: String) {
    Text(
        text,
        style = TvCaption.copy(fontSize = 14.sp),
        color = ModaColors.TextDim,
        // 上下各 6dp：白圈真正的外沿在卡片盒子外 5.5dp（= RingInset 4dp + 描边 3dp 的一半），
        // 行内 RingRoom 8dp 扣掉它只剩 2.5dp —— 不够，说明文字会被白圈压住。
        // 补这 6dp 后圈到文案有 8.5dp，任何一行聚焦都不会碰到相邻文案。
        modifier = Modifier.padding(
            start = 14.dp + RingRoom,
            end = 14.dp + RingRoom,
            top = 6.dp,
            bottom = 6.dp,
        ),
    )
}

internal fun onOff(enabled: Boolean): String = if (enabled) "开" else "关"

/** 倍速浮点 → 档位文案；落不到任何档位时退回第一档，绝不显示原始浮点 */
internal fun speedLabelOf(value: Float): String =
    PlayerSpeedOptions.firstOrNull { it.value == value }?.label
        ?: PlayerSpeedOptions.first().label

/**
 * 一档一档循环的设置行（内核 / 软硬解 / 倍速 / 跳片头片尾）。
 *
 * 为什么用「按确认键循环」而不是弹一个选择面板：
 * 这几个项的候选只有 2~6 个，弹面板会让遥控器多两次按键、多一层焦点栈，
 * 而循环式在电视上是通行做法（每次按下立刻看到值变化，还省掉一个浮层）。
 *
 * 仍然复用 [SettingRow] 的视觉与焦点实现，不另造一套：
 * 焦点态由 tv-material Card 自带，选中态就是右侧那个值 —— 两者天然可区分。
 */
@Composable
internal fun CyclingRow(
    label: String,
    value: String,
    onCycle: () -> Unit,
    /** 非空即在这行下方紧贴一条说明：它属于这一行，不单独占一格行距 */
    hint: String? = null,
    /** IJK 选了但本机没有 so 之类的实况告警，用 Danger 色 */
    warning: String? = null,
) {
    SettingItem(
        label = label,
        value = value,
        chevron = true,
        onClick = onCycle,
        hint = hint,
        warning = warning,
    )
}

/**
 * 「一行 + 它的说明」作为一个整体。
 *
 * 说明必须紧跟在它所解释的那一行下面，中间不能再吃一次行距——
 * 否则每一行后面都跟一条说明时，行距看起来就是一行大一行小。
 * 所以这里把行与它的说明包进同一个 Column，行距只由整块的 RingRoom 决定。
 */
@Composable
internal fun SettingItem(
    label: String,
    value: String,
    hint: String? = null,
    warning: String? = null,
    chevron: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth()) {
        SettingRow(label = label, value = value, chevron = chevron, onClick = onClick)
        hint?.let { HintText(it) }
        warning?.let {
            Text(
                it,
                style = TvCaption.copy(fontSize = 14.sp),
                color = ModaColors.Danger,
                // 与 HintText 同一类相邻关系（红色告警同样紧邻其所属行），留白必须一致
                modifier = Modifier.padding(
                    start = 14.dp + RingRoom,
                    end = 14.dp + RingRoom,
                    top = 6.dp,
                    bottom = 6.dp,
                ),
            )
        }
    }
}

/**
 * 分组卡片：底色、圆角，以及**行间距的唯一来源**。
 *
 * 这里不再额外给 spacedBy：行与行的缝隙由每行自己的 RingRoom 透明 padding 决定
 * （2 × 8dp = 16dp，处处等宽）。两套间距叠加正是此前「代理网址 → 扫码配置」
 * 出现双倍空隙的原因，所以行之间也不许再插 Spacer/空 Box。
 */
@Composable
internal fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(ModaColors.Surface)
            // 左右各减去 RingRoom：行内 padding 已经让出同样的量，这里再给 20dp
            // 会让正文比卡片边距缩进更多，视觉上偏内
            // 垂直不再减去 RingRoom：卡片自己就 clip 在 14dp 圆角里，
            // 留满 16dp 才能让第一/最后一行的白圈离卡片边缘也有 8dp。
            .padding(horizontal = 20.dp - RingRoom, vertical = 16.dp),
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
internal fun SettingRow(
    label: String,
    value: String,
    chevron: Boolean = false,
    modifier: Modifier = Modifier,
    /** 非空即可点击：chevron 只负责「看起来能进」，真正让它能按下去的是这个回调 */
    onClick: (() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }

    // 可点击行用 tv-material 的 Card 承载：它的 focusedContainerColor/ContentColor
    // 由组件内部驱动，聚焦必然可见（手搓 Modifier.focusable 实测不触发重组）。
    //
    // 颜色由调用方显式传入，不走 LocalContentColor：
    // 不可点击行走的是裸 Box 分支，没有 ContentColor 作用域，
    // LocalContentColor 在那里取到的是未初始化的近黑色 —— 整行发黑就是这么来的。
    val content: @Composable (Color, Color) -> Unit = { labelColor, valueColor ->
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 13.dp),
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
        // 纯展示行：直接给最终颜色，不依赖任何 ContentColor 作用域。
        // 同样套 RingRoom：不可点的行也要和可点行的左边、行距对齐，
        // 否则一张卡里两种行会错开 8dp。
        Box(
            modifier
                .fillMaxWidth()
                .padding(RingRoom),
        ) {
            content(ModaColors.TextDim, ModaColors.Text)
        }
    } else {
        // 聚焦状态用 onFocusChanged 挂在 **Card 自己的 modifier** 上。
        // 之前两个错误都栽在这：
        //   ① 挂在内层 Box 上 → 焦点落在 Card，Box 收不到，focused 永远 false（白底白字）；
        //   ② 传 interactionSource 参数 → tv-material 的 Card 不吃这个组合，整行变得不可聚焦，
        //      遥控器直接跳过它，于是「代理网址」这一行按不到、扫码弹窗也就打不开。
        // 白圈画在卡片**布局边界之外**（tv-material 的 Border.inset 是向外扩的），
        // 而滚动容器会 clip 到自身视口：贴着视口底边的最后一行，圈的下沿就会被切。
        // 解法是让这圈外扩量由本行自己的透明 padding 承担——padding 属于布局，
        // 圈画在 padding 里，视口边界就永远是完整的。
        Box(
            modifier
                .fillMaxWidth()
                .padding(RingRoom),
        ) {
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
                // 不放大：scale 只改绘制不改布局，会再多外扩 1%，和 ring 的预算打架。
                // 滚动容器里靠白圈表达焦点就够了。
                scale = CardDefaults.scale(scale = 1f, focusedScale = 1f, pressedScale = 1f),
                border = CardDefaults.border(
                    border = Border.None,
                    focusedBorder = Border(BorderStroke(3.dp, ModaColors.Focus), RingInset),
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
}

internal fun androidVersion(): String =
    "Android " + android.os.Build.VERSION.RELEASE +
        "（API " + android.os.Build.VERSION.SDK_INT + "）"

/** 分辨率同时给像素与 dp：像素对上电视的实际面板，dp 才是布局真正用的那一套 */
@Composable
internal fun resolutionText(): String {
    val config = LocalConfiguration.current
    val metrics = LocalContext.current.resources.displayMetrics
    return metrics.widthPixels.toString() + "×" + metrics.heightPixels.toString() +
        "（" + config.screenWidthDp + "×" + config.screenHeightDp + " dp）"
}
