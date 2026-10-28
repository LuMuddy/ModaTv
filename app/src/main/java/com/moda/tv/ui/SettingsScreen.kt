package com.moda.tv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import com.moda.tv.BuildConfig
import com.moda.tv.R
import com.moda.tv.data.local.AuthSession
import com.moda.tv.data.local.PlayerSettings
import com.moda.tv.ui.components.TvColumnFocusScroll
import com.moda.tv.ui.components.TvFirstFocus
import com.moda.tv.ui.theme.ModaColors
import com.moda.tv.ui.theme.TvBody
import com.moda.tv.ui.theme.TvCaption
import com.moda.tv.ui.theme.TvHeading
import com.moda.tv.ui.theme.TvTitle

/**
 * 设置页：菜单，只有三张分组入口卡。
 *
 * 从「一屏平铺 + 滚动」改成分组子页，是因为平铺时版本号、分辨率这种只能看的行
 * 和「清空收藏」这种一按就删的行混在同一列里，用户扫读时分不出哪些能按；
 * 而且整页长度早就超出电视的可视高度，最该被看见的项要靠滚动才找得到。
 *
 * 现在的分工：本页只回答「想改哪一类」，细节全在 [SettingsGroup] 对应的子页里
 * （PlaybackSettingsSection / NetworkSettingsSection / AboutSettingsSection）。
 * 每张卡右侧的摘要是**当前真实值**，所以光看菜单就知道内核、授权状态、版本是什么，
 * 大多数时候连子页都不用进。
 */
@Composable
fun SettingsScreen(
    /**
     * 当前授权会话；null == 未授权。由 ViewModel 的 Flow 驱动，兑换后立刻变，不需要重启。
     *
     * 兑换的入口不在这里：它属于「网络与数据」子页（见 NetworkSettingsSection），
     * 菜单页只显示摘要——多一个入口就是同一个动作的第二种说法。
     */
    session: AuthSession?,
    onClearHistory: () -> Unit,
    onClearFavorites: () -> Unit,
    onClearSearchHistory: () -> Unit,
    /** 点分组卡：参数就是该进哪一页 */
    onOpenGroup: (SettingsSectionId) -> Unit,
    versionName: String = BuildConfig.VERSION_NAME,
    /**
     * 播放设置落盘的出口。默认 null 表示「直接写 store」；
     * AppRoot 把它接到 vm.updatePlayerSettings，写操作才跑在 viewModelScope 上，
     * 用户改完立刻按返回也不会把这次写取消掉。
     */
    onUpdatePlayerSettings: ((PlayerSettings) -> Unit)? = null,
) {
    // 摘要要显示当前播放设置，所以状态提在这里；子页用的是同一份（见 SettingsGroupRoute）
    val holder = rememberPlayerSettingsHolder(onUpdatePlayerSettings)
    val settings = holder.value
    // 首焦点落在第一张分组卡上。不请求的话，从首页工具栏按进本页时焦点是空的：
    // 上一页那个图标节点已经离开组合，屏幕上没有任何白圈，用户得先乱按一下方向键找光标。
    // 抢焦点统一交给 TvFirstFocus（等两帧、失败不抛），不再在这里手写重试。
    val firstFocus = remember { FocusRequester() }
    TvFirstFocus(firstFocus)

    Column(
        Modifier
            .fillMaxSize()
            .background(ModaColors.Bg)
            .padding(start = 48.dp, end = 48.dp, top = 32.dp, bottom = 24.dp),
    ) {
        // 不放「返回」按钮：遥控器返回键已经能退出本页，
        // 屏幕上再占一个可聚焦格位只会和下面的行抢焦点。
        Text(stringResource(R.string.settings_title), style = TvTitle, color = ModaColors.Text)
        Text(
            stringResource(R.string.settings_subtitle),
            style = TvCaption,
            color = ModaColors.TextDim,
            modifier = Modifier.padding(top = 6.dp),
        )
        // 菜单页要在 1080p 上一屏同时装下三张卡与版权声明行，头部间距比子页紧一档：
        // 这里没有需要逐字读的长说明，密度换来的是一眼全见。
        Spacer(Modifier.height(10.dp))

        // 滚动状态 hoist 到组合里复用：写在 Modifier 链里会让每次重组都新建 ScrollState，
        // 滚动位置立刻被重置。三张卡在 1080p 上一屏就放得下，这里保留滚动只是兜住
        // 更小的分辨率与将来加第四组的情况。
        val scrollState = rememberScrollState()
        // 非懒容器的跟焦（见 Common.kt 的 TvColumnFocusScroll）：每一行自己上报位置，
        // 缺多少补多少、scrollBy 立即到位。
        //
        // 为什么不靠 Compose 默认的 bring-into-view：它只把目标滚到"刚好露出来"。
        // 实测故障——焦点在 A（第一张卡）时顶部正确，按下到 B 整页下移，再按上回 A，
        // A 停在"刚露出"的位置，顶部留白再也回不来。这里按坐标双向算差值，
        // A 的 top 越过 comfort 就给出负 delta，正好把滚动量退回 0。
        val columnFocus = TvColumnFocusScroll(scrollState)
        Column(
            Modifier
                .fillMaxWidth()
                // 关键：weight(1f) 才拿得到真实剩余高度。没有它时 Column 以无限高为上限测量，
                // 滚动视口比可见区域高一截，最后一张卡连同底部留白永远滚不出来。
                // 也不能换成 fillMaxSize()——那会把高度钉死成内容高度，反而没有滚动余量。
                .weight(1f)
                // 视口修饰符必须写在 .verticalScroll **之前**：写在后面量到的是跟随滚动的
                // 内层内容，视口上下沿会跟着一起跑，等于没量。
                .then(columnFocus.viewportModifier)
                .verticalScroll(scrollState)
                // 顶部 16dp 与 TvColumnFocusScroll 的 comfort 取同一个值：
                // 第一行聚焦时差值正好为 0，页面停在 offset 0，与刚进页面时像素一致。
                // 底部 40dp 属于滚动内容：最后一张卡聚焦时要画 3dp 白圈，贴底会被裁掉。
                .padding(top = 16.dp, bottom = 40.dp),
            // 卡片缝隙已由每张卡外侧的 RingRoom 提供（2×8dp=16dp），这里不再叠加，
            // 否则三张卡之间会比卡片内行距宽出两倍。
        ) {
            SettingsEntryCard(
                title = stringResource(SettingsSectionId.Playback.titleRes),
                desc = stringResource(SettingsSectionId.Playback.descRes),
                focusRequester = firstFocus,
                // 摘要由三个各自独立的档位文案拼成，分隔符是纯标点，不进资源表
                summary = stringResource(settings.playerCore.labelRes) + " · " +
                    stringResource(settings.decoderMode.labelRes) + " · " +
                    speedLabelOf(settings.defaultSpeed),
                onClick = { onOpenGroup(SettingsSectionId.Playback) },
                modifier = Modifier.then(columnFocus.itemModifier(0)),
            )
            SettingsEntryCard(
                title = stringResource(SettingsSectionId.Network.titleRes),
                desc = stringResource(SettingsSectionId.Network.descRes),
                // 摘要只说「已授权 / 未授权」：这是唯一影响用户能不能用的一句话，
                // 到期时间、绑定 IP 这类凭据细节连子页都不显示（过期由后台判定，客户端自己回门）
                summary = authStatusText(session),
                onClick = { onOpenGroup(SettingsSectionId.Network) },
                modifier = Modifier.then(columnFocus.itemModifier(1)),
            )
            SettingsEntryCard(
                title = stringResource(SettingsSectionId.About.titleRes),
                desc = stringResource(SettingsSectionId.About.descRes),
                summary = "ModaTV " + versionName,
                onClick = { onOpenGroup(SettingsSectionId.About) },
                modifier = Modifier.then(columnFocus.itemModifier(2)),
            )
            // 版权声明入口不在这一页，而在「关于」子页的最后一行：
            // 启动弹窗一闪而过，用户想回看时第一反应是去「关于」里找版本号旁边。
            // 菜单页只保留三张「进去改东西」的分组卡，与本页职责（回答「想改哪一类」）一致。
        }
    }
}

/**
 * 顶层分组入口卡：左列标题 + 说明，右列当前值摘要 + 箭头。
 *
 * 视觉语言与子页里的 SettingRow 完全一致（同一套 tv-material Card 焦点色与描边），
 * 这样「能按的东西长什么样」在两级页面之间不用重新学一遍。
 */
@Composable
private fun SettingsEntryCard(
    title: String,
    desc: String,
    summary: String,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
    /** 给外层 Box 用：挂 TvColumnFocusScroll 的 itemModifier（量整卡外壳） */
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }

    // 颜色必须显式传：Card 的 focusedContentColor 作用域在这里拿不到内层 Row 的 Text，
    // 而中途读 LocalContentColor 会读到外层颜色（近黑），于是聚焦时整卡发黑
    val labelColor = if (focused) ModaColors.Bg.copy(alpha = 0.78f) else ModaColors.Text
    val descColor = if (focused) ModaColors.Bg.copy(alpha = 0.7f) else ModaColors.TextDim
    val summaryColor = if (focused) ModaColors.Bg else ModaColors.TextDim

    // 与子页的 SettingRow 同一套「白圈算进本行布局」的做法：
    // 外层透明 padding 提供圈的外扩空间，圈画在 padding 里，
    // 滚动视口就切不到它（tv-material 的 Border.inset 是向外扩的）。
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
                // 聚焦状态挂 Card 自己的 modifier：挂内层 Box 收不到焦点事件，
                // focused 永远 false，白底白字就是这么来的
                .onFocusChanged { focused = it.isFocused },
            shape = CardDefaults.shape(shape = RoundedCornerShape(14.dp)),
            colors = CardDefaults.colors(
                containerColor = ModaColors.Surface,
                contentColor = ModaColors.Text,
                focusedContainerColor = ModaColors.Focus,
                focusedContentColor = ModaColors.Bg,
                pressedContainerColor = ModaColors.AccentSoft,
                pressedContentColor = ModaColors.Text,
            ),
            // 不放大：scale 只改绘制不改布局，放大会额外外扩，和圈的预算打架
            scale = CardDefaults.scale(scale = 1f, focusedScale = 1f, pressedScale = 1f),
            border = CardDefaults.border(
                border = Border.None,
                focusedBorder = Border(BorderStroke(3.dp, ModaColors.Focus), RingInset),
            ),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    // 菜单页要比子页紧一档：这一屏要同时装下三张卡与版权声明行，
                // 而每张卡还背着 RingRoom 的透明空间。下降的只是纯装饰留白，信息一个字没少。
                .padding(horizontal = 24.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = TvHeading, color = labelColor)
                    Text(
                        desc,
                        style = TvCaption.copy(fontSize = 14.sp),
                        color = descColor,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Spacer(Modifier.width(24.dp))
                Row(
                    // 摘要宽度封顶：地址再长也不能把标题列挤成一条缝
                    Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End,
                ) {
                    Text(
                        summary,
                        style = TvBody.copy(fontSize = 19.sp),
                        color = summaryColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(10.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = summaryColor,
                        modifier = Modifier.size(30.dp),
                    )
                }
            }
        }
    }
}
