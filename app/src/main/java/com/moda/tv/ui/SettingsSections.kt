package com.moda.tv.ui

import androidx.annotation.StringRes
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import com.moda.tv.BuildConfig
import com.moda.tv.R
import com.moda.tv.data.local.AuthSession
import com.moda.tv.data.local.DecoderMode
import com.moda.tv.data.local.PlayerCore
import com.moda.tv.data.local.PlayerSettings
import com.moda.tv.data.local.PlayerSettingsStoreImpl
import com.moda.tv.data.local.PlayerSpeedOptions
import com.moda.tv.ui.components.TvColumnFocusScroll
import com.moda.tv.ui.components.TvColumnFocusScroller
import com.moda.tv.ui.components.TvFirstFocus
import com.moda.tv.ui.player.PlayerEngines
import com.moda.tv.ui.theme.ModaColors
import com.moda.tv.ui.theme.TvBody
import com.moda.tv.ui.theme.TvCaption
import com.moda.tv.ui.theme.TvTitle
import kotlinx.coroutines.launch

/**
 * 「清空」类操作的确认意图：标题、警示文案与完成提示跟着它走。
 * 存资源 id 而不是字符串：枚举一旦把文案固化在构造里，切换系统语言时这几句不会跟着变。
 */
internal enum class PendingClear(
    @param:StringRes val titleRes: Int,
    @param:StringRes val messageRes: Int,
    /** 完成提示；null = 确认之后整屏就换了（清除授权会直接退回授权门），没必要再说一句 */
    @param:StringRes val doneTextRes: Int?,
) {
    History(
        titleRes = R.string.clear_history_title,
        messageRes = R.string.clear_history_message,
        doneTextRes = R.string.clear_history_done,
    ),
    Favorites(
        titleRes = R.string.clear_favorites_title,
        messageRes = R.string.clear_favorites_message,
        doneTextRes = R.string.clear_favorites_done,
    ),
    SearchHistory(
        titleRes = R.string.clear_search_title,
        messageRes = R.string.clear_search_message,
        doneTextRes = R.string.clear_search_done,
    ),
    Completed(
        // 标题直接复用「已看完」页那一句（lib_done_clear_title，来自 strings_library）：
        // 同一个动作在两个入口各写一遍文案，早晚会有一个入口先改瞎
        titleRes = R.string.lib_done_clear_title,
        messageRes = R.string.clear_completed_message,
        doneTextRes = R.string.clear_completed_done,
    ),
    Auth(
        // 与「清空」共用一个弹窗：都是不可撤销、都要二次确认，另造一个弹窗只是多一套焦点栈
        titleRes = R.string.clear_auth_title,
        messageRes = R.string.clear_auth_message,
        doneTextRes = null,
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
    /**
     * 首焦点落点：子页的第一行。
     *
     * 不请求的话，从菜单页进子页时原节点已随上一个页面离开组合，焦点是空的——
     * 屏幕上没有任何白圈，用户只能先随便按一下方向键来"找回光标"。
     * 请求必须在首帧布局之后（两帧），否则目标还没上树，requestFocus 会抛。
     */
    firstFocus: FocusRequester? = null,
    /**
     * 内容拿到滚动器的跟焦入口：每一行都要挂 Modifier.then(scroller.itemModifier(行下标))。
     * 下标只需在本页内唯一——它只是行与行之间的身份，跟焦会把它和量到的坐标对上。
     */
    content: @Composable ColumnScope.(TvColumnFocusScroller) -> Unit,
) {
    // 首焦点统一走 TvFirstFocus：等两帧再抢、失败不抛，页面里不再各写一遍重试
    TvFirstFocus(firstFocus)
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
        // 非懒容器的跟焦（Common.kt 的 TvColumnFocusScroll）：行自己上报坐标，缺多少补多少。
        // 不能靠 Compose 默认的 bring-into-view —— 它只把目标滚到"刚好露出来"，
        // 从 A 按下到 B 再按回 A，A 永远停在"刚露出"，顶部留白回不到进页面时的位置。
        val columnFocus = TvColumnFocusScroll(scrollState)
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                // 视口修饰符必须在 .verticalScroll 之前：写在后面量到的是跟随滚动的内层内容
                .then(columnFocus.viewportModifier)
                .verticalScroll(scrollState)
                // 顶部 16dp 与跟焦的 comfort 同值：第一行聚焦时差值为 0，页面停在 offset 0。
                // 底部 40dp 属于滚动内容，最后一行聚焦时它的 3dp 白圈不会被裁。
                .padding(top = 16.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            content(columnFocus)
        }
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
    val firstFocus = remember { FocusRequester() }

    SettingsSectionScaffold(
        title = stringResource(SettingsSectionId.Playback.titleRes),
        firstFocus = firstFocus,
    ) { columnFocus ->
        SectionCard {
            // 内核：按 PlayerCore.entries 生成，而不是手写两行。
            // 枚举以后加档位时这里自动跟上，不用再回来改一遍布局。
            CyclingRow(
                label = stringResource(R.string.settings_playback_core),
                value = stringResource(settings.playerCore.labelRes),
                // 选了 IJK 但这台机器没有 so 时必须如实提示：
                // 工厂会静默回退 Exo，不说明用户会以为设置没生效
                warning = if (settings.playerCore == PlayerCore.Ijk && !ijkUsable) {
                    stringResource(R.string.settings_playback_core_warning)
                } else {
                    null
                },
                hint = stringResource(R.string.settings_playback_core_hint),
                focusRequester = firstFocus,
                modifier = Modifier.then(columnFocus.itemModifier(0)),
                onCycle = {
                    val entries = PlayerCore.entries
                    val i = entries.indexOf(settings.playerCore).coerceAtLeast(0)
                    holder.apply(
                        settings.copy(playerCore = entries[(i + 1) % entries.size]),
                    )
                },
            )

            CyclingRow(
                label = stringResource(R.string.settings_playback_decoder),
                value = stringResource(settings.decoderMode.labelRes),
                // 这段说明必须准确：ExoPlayer 没打包 ffmpeg 扩展，它的「软解」
                // 只能是挑系统自带的软件编解码器，系统没带就仍退回硬解；
                // 只有 IJK 的软解才是真正打包在 so 里的 ffmpeg。
                hint = stringResource(R.string.settings_playback_decoder_hint),
                modifier = Modifier.then(columnFocus.itemModifier(1)),
                onCycle = {
                    val entries = DecoderMode.entries
                    val i = entries.indexOf(settings.decoderMode).coerceAtLeast(0)
                    holder.apply(
                        settings.copy(decoderMode = entries[(i + 1) % entries.size]),
                    )
                },
            )

            CyclingRow(
                label = stringResource(R.string.settings_playback_default_speed),
                value = speedLabelOf(settings.defaultSpeed),
                hint = stringResource(R.string.settings_playback_default_speed_hint),
                modifier = Modifier.then(columnFocus.itemModifier(2)),
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
                label = stringResource(R.string.settings_playback_danmaku),
                value = onOff(settings.danmakuEnabled),
                hint = stringResource(R.string.settings_playback_danmaku_hint),
                modifier = Modifier.then(columnFocus.itemModifier(3)),
                onCycle = {
                    holder.apply(settings.copy(danmakuEnabled = !settings.danmakuEnabled))
                },
            )
        }
    }
}

// ==================== 二、网络与数据 ====================

/**
 * 授权状态只有一句话：已授权 / 未授权。
 *
 * 剩余时长、到期时刻都刻意不显示：它们只解释「这份凭据长什么样」，不决定用户能做什么；
 * 过期与否也不再需要用户自己盯——后台判死时客户端会自己回到授权门。
 * 屏幕上多一行术语，就多一次「这数字什么意思」的追问。
 */
@Composable
internal fun authStatusText(session: AuthSession?): String = if (session == null) {
    stringResource(R.string.settings_auth_unauthorized)
} else {
    stringResource(R.string.settings_auth_authorized)
}

/**
 * 登录账号那一行的值：有账号名时「已授权 · 账号名」，没有就只报「已授权」。
 *
 * 只挂账号名，不挂券号与到期时刻：账号名是唯一能和后台记录对上的信息，
 * 另两个只解释「这份凭据长什么样」，不决定用户能做什么，屏幕上一多就要解释一串术语。
 */
@Composable
private fun authAccountText(session: AuthSession?): String = when {
    session == null -> stringResource(R.string.settings_auth_unauthorized)
    // 服务端没给账号名（旧会话 / acc 缺失）时只报状态，不编一个空值出来
    session.accName.isBlank() -> stringResource(R.string.settings_auth_authorized)
    else -> stringResource(R.string.settings_auth_authorized_account, session.accName)
}

/**
 * 网络与数据子页：登录账号（只读）+ 四项数据清理 + 清除授权。
 *
 * 登录账号那一行**只显示、不可操作**：扫码 / 填码只有一个入口——启动时的授权门
 * （AuthGateScreen），本页再放一个入口就是同一个动作的第二种说法。代价是没有就地换号，
 * 凭据被服务端作废时用户只能下次启动重新扫码；这是产品定的取舍，不要再把入口加回来。
 *
 * 屏幕自身不碰网络也不碰凭据：授权态由上层回灌的 [session] 决定，本页只把状态渲染出来。
 *
 * 「清除授权」是这一页唯一的账号动作：真删（[TvViewModel.signOut] 走 Auth.signOut 清内存再清盘，
 * 然后会话置空、界面自己退回授权门），不是"本地装作未授权"——半截作废只会让下一次 refresh 把
 * token 复活（见 Auth.signOut 的顺序说明），那种状态下用户既进不去也换不了号。
 */
@Composable
internal fun NetworkSettingsSection(
    session: AuthSession?,
    onClearHistory: () -> Unit,
    onClearFavorites: () -> Unit,
    onClearSearchHistory: () -> Unit,
    onClearCompleted: () -> Unit,
    onClearAuth: () -> Unit,
) {
    // 五项（四项清数据 + 清除授权）都是不可撤销的，点一下先弹确认，确认后才真正执行
    var pendingClear by remember { mutableStateOf<PendingClear?>(null) }
    // 清空后的反馈：文案在弹窗确认那一刻就按当前语言取好（见 PendingClear.doneTextRes）
    var notice by remember { mutableStateOf<String?>(null) }

    // 首焦点落在这一屏第一行**可聚焦**的内容上：授权码行改成只读之后，它就是第一项清空。
    // 不给的话从菜单页进来屏幕上没有任何白圈，用户得先乱按一下方向键找光标。
    // 抢焦点统一交给 SettingsSectionScaffold 里的 TvFirstFocus，这里只准备 requester。
    val firstFocus = remember { FocusRequester() }

    SettingsSectionScaffold(
        title = stringResource(SettingsSectionId.Network.titleRes),
        subtitle = stringResource(R.string.settings_network_subtitle),
        firstFocus = firstFocus,
    ) { columnFocus ->
        // 反馈行挂在卡片之外、所有跟焦行之下（它不可聚焦，没有焦点事件能把视口带到它身上），
        // 所以内容一变就把视口推到尾部：否则"点完确认什么也没看见"。
        LaunchedEffect(notice) {
            if (notice != null) columnFocus.revealTail()
        }
        SectionCard {
            // 只读行：不给 onClick、不画箭头 —— SettingRow 在 onClick 为空时就是个裸 Box，
            // 遥控器不会停在它上面，也就不会再多出一行"按了没反应"的格子。
            SettingRow(
                label = stringResource(R.string.settings_auth_account),
                // 右侧 =「已授权 · 账号名」：账号名是唯一能和后台记录对上的信息；
                // 券号与到期时刻都不挂，它们只解释"凭据长什么样"，不决定用户能做什么
                value = authAccountText(session),
                modifier = Modifier.then(columnFocus.itemModifier(0)),
            )

            // 数据清理：四项都是不可撤销的批量删除，逐项二次确认
            SettingRow(
                label = stringResource(R.string.settings_network_clear_history),
                value = stringResource(R.string.common_clear),
                chevron = true,
                modifier = Modifier.then(columnFocus.itemModifier(1)),
                focusRequester = firstFocus,
                onClick = { pendingClear = PendingClear.History },
            )
            SettingRow(
                label = stringResource(R.string.settings_network_clear_favorites),
                value = stringResource(R.string.common_clear),
                chevron = true,
                modifier = Modifier.then(columnFocus.itemModifier(2)),
                onClick = { pendingClear = PendingClear.Favorites },
            )
            SettingRow(
                label = stringResource(R.string.settings_network_clear_search),
                value = stringResource(R.string.common_clear),
                chevron = true,
                modifier = Modifier.then(columnFocus.itemModifier(3)),
                onClick = { pendingClear = PendingClear.SearchHistory },
            )
            SettingRow(
                label = stringResource(R.string.settings_network_clear_completed),
                value = stringResource(R.string.common_clear),
                chevron = true,
                modifier = Modifier.then(columnFocus.itemModifier(4)),
                onClick = { pendingClear = PendingClear.Completed },
            )
            // 清除授权排在最末：它是全页唯一会把自己锁回门里的动作，
            // 与上面几行"清数据"混排时越靠后越不容易被连按误触（而且还要过一道确认）。
            // 只在真的已授权时出现：未授权时它没有任何东西可清，多一行就是多一次误按。
            if (session != null) {
                SettingRow(
                    label = stringResource(R.string.settings_network_clear_auth),
                    value = stringResource(R.string.common_clear),
                    chevron = true,
                    modifier = Modifier.then(columnFocus.itemModifier(5)),
                    onClick = { pendingClear = PendingClear.Auth },
                )
            }
        }

        // 操作反馈落在滚动内容的末尾：它可能来自这一页的任意一处，
        // 挂在某一行内部会让别处触发的消息跑到看不见的位置。
        notice?.let { text ->
            Text(
                text,
                style = TvBody.copy(fontSize = 18.sp),
                color = ModaColors.AccentSoft,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }

    // 清空确认：三项共用同一个弹窗，标题、文案与真正执行的动作都由 pendingClear 决定
    pendingClear?.let { pending ->
        // onConfirm 是普通 lambda，取不到 stringResource，所以在组合里先解析好
        val doneText = pending.doneTextRes?.let { stringResource(it) }
        ClearConfirmDialog(
            title = stringResource(pending.titleRes),
            message = stringResource(pending.messageRes),
            onConfirm = {
                pendingClear = null
                when (pending) {
                    PendingClear.History -> onClearHistory()
                    PendingClear.Favorites -> onClearFavorites()
                    PendingClear.SearchHistory -> onClearSearchHistory()
                    PendingClear.Completed -> onClearCompleted()
                    PendingClear.Auth -> onClearAuth()
                }
                if (doneText != null) notice = doneText
            },
            onDismiss = { pendingClear = null },
        )
    }
}

// ==================== 三、关于 ====================

/**
 * 关于子页：五行只读信息 + 一行「检查更新」+ 一行版权声明入口。
 *
 * 版权声明入口放在这里而不是顶层菜单页：菜单页那三张卡是"进去改东西"，
 * 而这一页本来就是"看看这软件是什么"，用户想回看声明时第一反应是来这里找版本号旁边。
 * 「检查更新」同理：它是"这个软件现在是什么版本"的延伸，放在版本号与构建号正下方。
 *
 * **本页从此有两行可聚焦**：上面五行纯展示行不带箭头也不可聚焦
 * （SettingRow 的 onClick 为 null 就是裸 Box），可聚焦的只有「检查更新」与版权声明。
 * 两行都挂 itemModifier 下标，遥控器上下键在它们之间直接跳，不在只读行之间空转。
 * 首焦点给「检查更新」——它是本页唯一有动作的一行（点一下就开始查），
 * 版权声明是"回头再看"的次要出口，留在最后一行。
 */
@Composable
internal fun AboutSettingsSection(
    /** 重看版权声明；真正的弹窗挂在 AppRoot 上，与启动时自动弹出的那一个是同一个 */
    onOpenCopyright: () -> Unit,
    /** 当前更新状态：这一行的值完全由它决定（检查中/发现 vX/已是最新…） */
    updateUi: UpdateUi,
    /** 点「检查更新」：manual = true，失败会在这个弹窗里说出来 */
    onCheckUpdate: () -> Unit,
    versionName: String = BuildConfig.VERSION_NAME,
    versionCode: Int = BuildConfig.VERSION_CODE,
) {
    // 首焦点给「检查更新」：与其它两个子页同一个理由
    // （不给的话从菜单页进来屏幕上一个白圈都没有，用户得先乱按一下方向键才找得到光标）。
    // 全页只有 firstFocus 一个 FocusRequester 实例、只挂在「检查更新」这一行上，
    // 所以不存在两个节点抢同一个请求：版权声明行不挂 requester，只靠方向键走到。
    val firstFocus = remember { FocusRequester() }
    SettingsSectionScaffold(
        title = stringResource(SettingsSectionId.About.titleRes),
        firstFocus = firstFocus,
    ) { columnFocus ->
        SectionCard {
            // 纯展示行不带箭头：让「能点」与「不能点」一眼可分。
            // 这五行不可聚焦，itemModifier 挂在行外壳上不会触发，
            // 但保持与其它两个子页同一套写法，将来某行变成可点时不必再回来补。
            SettingRow(
                stringResource(R.string.settings_about_app_name), "ModaTV",
                modifier = Modifier.then(columnFocus.itemModifier(0)),
            )
            SettingRow(
                stringResource(R.string.settings_about_version), versionName,
                modifier = Modifier.then(columnFocus.itemModifier(1)),
            )
            SettingRow(
                stringResource(R.string.settings_about_build), versionCode.toString(),
                modifier = Modifier.then(columnFocus.itemModifier(2)),
            )
            // 「检查更新」紧贴构建号下方：用户找版本信息时视线就在这一块，
            // 值直接显示当前状态（检查中… / 发现 vX / 已是最新），点一下才发请求。
            // 没有新版本时这里显示「已是最新」，但**不弹窗**——"没检测到就没有"。
            SettingRow(
                label = stringResource(R.string.update_row_title),
                value = updateRowValue(updateUi),
                chevron = true,
                focusRequester = firstFocus,
                onClick = onCheckUpdate,
                modifier = Modifier.then(columnFocus.itemModifier(3)),
            )
            SettingRow(
                stringResource(R.string.settings_about_android), androidVersion(),
                modifier = Modifier.then(columnFocus.itemModifier(4)),
            )
            SettingRow(
                stringResource(R.string.settings_about_resolution), resolutionText(),
                modifier = Modifier.then(columnFocus.itemModifier(5)),
            )
            SettingRow(
                label = stringResource(R.string.copyright_title),
                value = stringResource(R.string.settings_copyright_value),
                chevron = true,
                onClick = onOpenCopyright,
                modifier = Modifier.then(columnFocus.itemModifier(6)),
            )
        }
    }
}

/**
 * 「检查更新」那一行的当前值（这一行的值 = 更新状态机的读法）。
 *
 * - Latest 说「已是最新」：只有它代表检查真的跑完并确认过（含仓库还没有 Release）；
 * - Idle 说「点按检查」：它同时表示"还没查过""静默检查在飞""静默检查失败了"，
 *   拿它显示「已是最新」会让冷启动那几帧和联网失败后都在说谎——宁可空也不冒充；
 * - Failed 说「点按检查」：包没装成功但检查本身是成功的，说「已是最新」会与弹窗里的失败说明互相打脸，
 *   给一句邀请重试的文案更诚实；
 * - 检查中 / 已发现新版（下载中、待安装都算）各自给对应文案。
 *
 * 只做文案映射，不做任何判断：状态机在 TvViewModel，这一行不该有自己的第二套结论。
 */
@Composable
private fun updateRowValue(state: UpdateUi): String = when (state) {
    is UpdateUi.Checking -> stringResource(R.string.update_checking)
    is UpdateUi.Available -> stringResource(R.string.update_row_found, state.info.versionName)
    is UpdateUi.Downloading -> stringResource(R.string.update_row_found, state.info.versionName)
    is UpdateUi.Ready -> stringResource(R.string.update_row_found, state.info.versionName)
    is UpdateUi.Latest -> stringResource(R.string.update_row_latest)
    is UpdateUi.Idle -> stringResource(R.string.update_row_idle)
    is UpdateUi.Failed -> stringResource(R.string.update_row_idle)
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

@Composable
internal fun onOff(enabled: Boolean): String =
    stringResource(if (enabled) R.string.common_on else R.string.common_off)

/** 倍速浮点 → 档位文案；落不到任何档位时退回第一档，绝不显示原始浮点 */
internal fun speedLabelOf(value: Float): String =
    PlayerSpeedOptions.firstOrNull { it.value == value }?.label
        ?: PlayerSpeedOptions.first().label

/**
 * 一档一档循环的设置行（内核 / 软硬解 / 倍速 / 弹幕开关）。
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
    /** 首焦点落点用；空表示这一行不参与首焦点 */
    focusRequester: FocusRequester? = null,
    /** 挂 TvColumnFocusScroll 的 itemModifier（量「行 + 它的说明」这一整块外壳） */
    modifier: Modifier = Modifier,
) {
    SettingItem(
        label = label,
        value = value,
        chevron = true,
        onClick = onCycle,
        hint = hint,
        warning = warning,
        focusRequester = focusRequester,
        modifier = modifier,
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
    focusRequester: FocusRequester? = null,
    /**
     * 挂 TvColumnFocusScroll 的 itemModifier。量的是「行 + 它的说明」这一整块，
     * 不是行内某个小元素——说明文字属于这一行，跟焦移动时整块一起走。
     */
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        SettingRow(
            label = label,
            value = value,
            chevron = chevron,
            onClick = onClick,
            focusRequester = focusRequester,
        )
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
 * （2 × 8dp = 16dp，处处等宽）。两套间距叠加正是此前「授权码 → 扫码输入」
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
    /** 挂在 Card 自己身上（不是外层 Box）：焦点节点是 Card，挂错了 requestFocus 会静默失败 */
    focusRequester: FocusRequester? = null,
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
        //      遥控器直接跳过它，于是「授权码」这一行按不到、扫码弹窗也就打不开。
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
                    .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                    .onFocusChanged { focused = it.isFocused },
                shape = CardDefaults.shape(shape = RoundedCornerShape(10.dp)),
                // 白底与描边同样由本行的 focused 直接给，不交给 Card 内部的焦点态。
                // 实测的坑：从菜单页进子页时，焦点是随着新页面一起上树的那一帧就给到的，
                // tv-material 的 Card 有时收不到这次焦点 —— 于是 content 走的是聚焦配色
                // （近黑的字），卡片却仍是未聚焦的透明底，整行黑字贴黑底，屏幕上等于没字。
                // 底下的 border/focusedBorder 是同一份颜色，两处一致，画两遍也看不出差别。
                colors = CardDefaults.colors(
                    containerColor = if (focused) ModaColors.Focus else Color.Transparent,
                    contentColor = if (focused) ModaColors.Bg else ModaColors.Text,
                    focusedContainerColor = ModaColors.Focus,
                    focusedContentColor = ModaColors.Bg,
                    pressedContainerColor = ModaColors.AccentSoft,
                    pressedContentColor = Color.White,
                ),
                // 不放大：scale 只改绘制不改布局，会再多外扩 1%，和 ring 的预算打架。
                // 滚动容器里靠白圈表达焦点就够了。
                scale = CardDefaults.scale(scale = 1f, focusedScale = 1f, pressedScale = 1f),
                border = CardDefaults.border(
                    border = if (focused) {
                        Border(BorderStroke(3.dp, ModaColors.Focus), RingInset)
                    } else {
                        Border.None
                    },
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

@Composable
internal fun androidVersion(): String = stringResource(
    R.string.settings_about_android_value,
    android.os.Build.VERSION.RELEASE,
    android.os.Build.VERSION.SDK_INT,
)

/** 分辨率同时给像素与 dp：像素对上电视的实际面板，dp 才是布局真正用的那一套 */
@Composable
internal fun resolutionText(): String {
    val config = LocalConfiguration.current
    val metrics = LocalContext.current.resources.displayMetrics
    return stringResource(
        R.string.settings_about_resolution_value,
        metrics.widthPixels,
        metrics.heightPixels,
        config.screenWidthDp,
        config.screenHeightDp,
    )
}
