package com.moda.tv.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import com.moda.tv.BuildConfig
import com.moda.tv.R
import com.moda.tv.data.Repo
import com.moda.tv.data.local.CopyrightNoticeStoreImpl
import com.moda.tv.data.local.SearchHistoryStoreImpl
import com.moda.tv.model.LibraryEntry
import com.moda.tv.ui.components.ActionButton
import com.moda.tv.ui.components.ErrorState
import com.moda.tv.ui.components.appErrorText
import com.moda.tv.ui.components.LoadingState
import com.moda.tv.ui.components.TvFirstFocus
import com.moda.tv.ui.theme.ModaColors

/**
 * 全应用唯一入口：先过授权门，再按栈顶渲染。
 *
 * 三条分支是有意排成「先问授权态读没读到、再问授没授权」的，顺序不能换：
 * 盘上的会话读出来之前 session 也是 null，先问授权就会在已授权的机器上先闪一屏授权框。
 */
@Composable
fun AppRoot() {
    val context = LocalContext.current
    // ViewModel 必须建在这一层：门与内容互相切换时它不能跟着重建，
    // 否则切一次分支就等于清空路由栈、重读一遍盘上的会话。
    val vm: TvViewModel = viewModel { TvViewModel(context.applicationContext) }
    val session by vm.authSession.collectAsStateWithLifecycle()
    val exitApp: () -> Unit = { (context as? Activity)?.finish() }

    // —— 下面三样是「更新检查」的全部运行时依赖，故意挂在授权门之外 ——
    //
    // 它们原来长在 AuthorizedContent 里，只有已授权、界面真的上屏了才跑。
    // 可授权过期的那批设备恰恰是最需要新版的人：他们卡在授权门上，进不去
    //「设置 → 关于」，也就没有第二条手动检查的路，永远等不到升级。
    // 所以检查、进度、安装回流观察器都提到这一层：授权态一读出来就位，
    // 门开着还是关着都一样。
    //
    // 一次冷启动只能打一次 GitHub：下面这一份是全应用唯一的启动检查，
    // AuthorizedContent 里原来那份已经删掉。留两份 = 冷启动打两次 API，
    // 两次都可能改 updateUi、互相覆盖。
    LaunchedEffect(Unit) { vm.checkUpdate(manual = false) }

    // 下载进度是 StateFlow（回调在 IO 线程，见 TvViewModel.updateProgress），
    // 组合里 collectAsState 读，天然回到主线程。
    val updateProgress by vm.updateProgress.collectAsStateWithLifecycle()

    // 从系统「安装未知来源 / 安装确认」页回来要续装：那是一个独立的 Activity，
    // 回来时组合不会重建，只有 ON_RESUME 能告诉我们"用户已经回来了"。
    // 挂在门这一层是为了让未授权时弹出的更新提示也能走完安装回流。
    // onDispose 必须反注册：不摘的话每次重组都往 Activity 上多挂一个 observer，
    // 一路累加（表现为一次回到前台弹出好几个安装器）。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.onReturnedFromForeground()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    when {
        // a) 还不知授权态：只铺底色，什么都不画——不画门，也不画内容。
        // 这一段是冷启动的第一两帧，画门等于给已授权的机器看一眼「需要授权」。
        !vm.authReady -> {
            // 授权态还没定的这两帧里，返回键必须吞掉：不吞就走系统默认的 finish。
            // 已授权的机器开机按返回，用户看到的是「应用闪退」而不是他熟悉的「确认退出」，
            // 而这种只发生在两帧内的闪退事后极难对上号。窗口极短，再按一次就落到正常界面。
            BackHandler(enabled = true) { /* 授权态未定，先吞掉 */ }
            Box(Modifier.fillMaxSize().background(ModaColors.Bg))
        }

        // b) 确定未授权：整屏只有门。Dest 的分支一个都不进组合——
        // 不是「画出来再用遮罩盖住」：盖住的内容照样在组合里、照样会发请求。
        session == null -> AuthGateScreen(
            // 扫码是主路径：进屏自动取码、每 1.5s 轮询一次；授权码只在 need_code / 取码失败时补位
            onRequestQr = vm::startQr,
            onPollQr = vm::pollQr,
            onActivate = vm::activate,
            // 退出走 AppRoot 里已有的那一份拿法，不另造退出机制
            onExit = exitApp,
        )

        // c) 已授权：正常界面。授权成功时 session 变非空，分支自己切到这一条；
        // 首页由 TvViewModel.applyAuthorized() 里既有的 loadHome() 装载，这里不做任何跳转。
        else -> AuthorizedContent(vm)
    }

    // 授权门外的挂载点：a) 授权态未定、b) 确定未授权——这两条分支本来就不显示
    // 版权弹窗，所以 suppressed 恒为 false。已授权那条由 AuthorizedContent 自己挂
    // （它要按版权弹窗让位），两边条件互斥，同屏只会有一个 UpdateDialogHost。
    if (!vm.authReady || session == null) {
        UpdateDialogHost(vm = vm, progress = updateProgress, suppressed = false)
    }
}

/**
 * 已授权后的正常界面：路由栈在 TvViewModel 里，这里只按栈顶渲染。
 *
 * 整段内容原样从旧的 AppRoot 搬过来（返回链、版权弹窗、退出确认一字未改），
 * 区别只在它现在挂在上面的第三条分支里：未授权时这些调用一次都不会发生。
 */
@Composable
private fun AuthorizedContent(vm: TvViewModel) {
    val context = LocalContext.current

    // 首页按返回不直接退出应用：电视上误触返回就闪退很恼人，先弹一次确认。
    // 非首页仍然直接退一层。
    var showExitDialog by remember { mutableStateOf(false) }
    BackHandler(enabled = true) {
        if (vm.stack.size > 1) {
            // 从播放页退出必须先补一次进度落盘，再出栈。
            //
            // 顺序不能反：pop() 会把 player 重置成 Loading，而 flushHistory() 是从当前
            // player 快照里取 cid/vid 的——先 pop 再 flush 等于什么都没写。
            // 此前只有 PlayerScreen 自己的返回按钮走 flush，遥控器返回键走的是这条全局通路，
            // 于是最后一段进度（5 秒节流窗口之后的那些）根本没落盘，
            // 首页「继续观看」看到的还是上一次写的旧位置——这就是「每次看完都得手动刷新」的根因。
            //
            // 三条退出路径现在都覆盖到了：
            //  1. 遥控器返回：这里；2. PlayerScreen 内返回按钮：它自己的 onBack 已 flush；
            //  3. 播完/切集：onEnded、selectEpisodeFromPlayer 在换掉快照之前就 writeHistory 了。
            // 重复 flush 是安全的：它内部有 updatedAt 与 completedVids 两道闸门，
            // 同一个位置写第二次只是把 updatedAt 刷新一下，不会产生重复记录。
            if (vm.current is Dest.Player) vm.flushHistory()
            vm.pop()
        } else {
            showExitDialog = true
        }
    }

    // 版权声明：每次版本升级后（含首次安装）启动时自动弹一次。
    // 判据是「上次确认过的 versionCode」，不是布尔开关——升级后版本号天然不同，
    // 不需要任何「记得重置标记」的额外逻辑。
    // 初始值 false：不能默认 true，否则首次安装的用户在读完 DataStore 之前就看到了弹窗闪烁；
    // 也不能默认 true 后再关，那会让弹窗一闪而过。宁可晚一帧出现。
    val noticeStore = remember(context) {
        CopyrightNoticeStoreImpl.create(context.applicationContext)
    }
    var showCopyright by remember { mutableStateOf(false) }
    // 设置页手动打开的那一次不受版本判定约束，两条来源共用同一个弹窗
    var manualCopyright by remember { mutableStateOf(false) }
    val noticeScope = rememberCoroutineScope()
    LaunchedEffect(noticeStore) {
        // 读失败（极少数 IO 异常）就当没确认过，宁可多弹一次也不漏掉声明
        val acked = runCatching { noticeStore.acknowledgedVersion() }.getOrDefault(0)
        showCopyright = acked != BuildConfig.VERSION_CODE
    }

    // 启动检查与安装回流观察器已经提到 AppRoot（授权门外），这里不再有第二份。
    // 下载进度还得自己收一份：AuthorizedContent 的签名保持 (vm: TvViewModel) 不变，
    // 版权状态留在本层 remember，不为了传一个进度值把 400 行既有逻辑牵上去。
    // StateFlow 多一个收集者只是各读各的当前值，不会重放历史、也不会互相覆盖。
    val updateProgress by vm.updateProgress.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize().background(ModaColors.Bg)) {
        when (val dest = vm.current) {
            is Dest.Home -> HomeRoute(vm)
            is Dest.Search -> SearchRoute(vm)
            is Dest.History -> HistoryRoute(vm)
            is Dest.Completed -> CompletedRoute(vm)
            is Dest.Favorites -> FavoritesRoute(vm)
            is Dest.Settings -> SettingsRoute(vm)
            // 版权声明的常驻入口在「关于」子页的最后一行，所以只有子页路由需要这个回调
            is Dest.SettingsGroup -> SettingsGroupRoute(
                vm = vm,
                group = dest.group,
                onOpenCopyright = { manualCopyright = true },
                // 「关于」页的「检查更新」行：manual = true，失败要在弹窗里说出来
                onCheckUpdate = { vm.checkUpdate(manual = true) },
            )
            is Dest.Detail -> DetailRoute(vm, dest)
            is Dest.Player -> PlayerRoute(vm)
        }
    }

    if (showCopyright || manualCopyright) {
        CopyrightNoticeDialog(
            onDismiss = {
                val wasUpgradePrompt = showCopyright && !manualCopyright
                showCopyright = false
                manualCopyright = false
                // 只有启动自动弹出的那次才记「已确认当前版本」；
                // 从设置页主动重看的，不该影响下次升级的提示。
                if (wasUpgradePrompt) {
                    noticeScope.launch {
                        runCatching { noticeStore.acknowledge(BuildConfig.VERSION_CODE) }
                    }
                }
            },
        )
    }

    // 渲染规则与授权门外那一份完全共用（见 UpdateDialogHost），
    // 唯一区别是这里要按版权弹窗让位。
    UpdateDialogHost(
        vm = vm,
        progress = updateProgress,
        suppressed = showCopyright || manualCopyright,
    )

    if (showExitDialog) {
        ExitConfirmDialog(
            onConfirm = {
                showExitDialog = false
                (context as? Activity)?.finish()
            },
            onDismiss = { showExitDialog = false },
        )
    }
}

/**
 * 更新弹窗的唯一渲染出口：授权门内外共用这一份。
 *
 * 为什么要有这个函数：门内门外都要弹，但门内还多一条「版权弹窗优先」的护栏。
 * 把渲染规则收在一处，两条挂载点就不会一边改了另一边忘了——「没检测到就没有」
 * 这条臂漏改一次，Latest 就会被 else 接住，凭空弹一个只剩「以后再说」的空弹窗。
 *
 * Idle / Latest / Checking 不渲染：没检测到就没有，也不造一个"已是最新"的弹窗
 * 来打断正在看剧的人。
 *
 * suppressed 承载的是「版权弹窗在屏时更新弹窗让位」：两个 Dialog 是两个窗口、
 * 两套焦点，同时上屏就是遥控器抢键（启动首帧恰好"升级提示"与"发现新版本"撞在一起）。
 * 版权弹窗关掉后 updateUi 还是 Available，那一帧自然就会补上，不需要任何排队逻辑。
 *
 * 「立即安装」也走 onDownload：VM 见到已下好的包会跳过下载直接再试一次安装，
 * 这样"重试安装"与"首次下载"共用同一个入口，UI 不必再记一个 VM 方法名。
 */
@Composable
private fun UpdateDialogHost(vm: TvViewModel, progress: UpdateProgress, suppressed: Boolean) {
    when (val update = vm.updateUi) {
        UpdateUi.Idle, UpdateUi.Latest, UpdateUi.Checking -> Unit
        else -> if (!suppressed) {
            UpdateDialog(
                state = update,
                progress = progress,
                onDownload = vm::downloadUpdate,
                onIgnore = vm::ignoreUpdate,
                onDismiss = vm::dismissUpdate,
            )
        }
    }
}

/**
 * 退出确认框。
 * 电视端的「退出」只该有一种结果：真的关掉应用，而不是回到某个后台栈。
 * 所以确认后直接 finish() 当前 Activity；取消则只是关掉弹窗，焦点回到首页。
 */
@Composable
private fun ExitConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // 弹窗一出现焦点必须已经在「取消」上：电视上没有鼠标，焦点空着的弹窗
        // 只能靠乱按方向键找光标，而且更容易连击落到「确认退出」上。
        // 抢焦点统一走 TvFirstFocus（等两帧、失败不抛），不在各弹窗里手写重试。
        val cancelFocus = remember { FocusRequester() }
        TvFirstFocus(cancelFocus)
        Column(
            Modifier
                .width(520.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(ModaColors.Surface)
                .padding(horizontal = 36.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(R.string.dialog_exit_title),
                style = TextStyle(fontSize = 30.sp),
                color = ModaColors.Text,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.dialog_exit_message),
                style = TextStyle(fontSize = 18.sp),
                color = ModaColors.TextDim,
            )
            Spacer(Modifier.height(28.dp))
            Row {
                ActionButton(
                    text = stringResource(R.string.common_cancel),
                    onClick = onDismiss,
                    modifier = Modifier.focusRequester(cancelFocus),
                    primary = true,
                )
                Spacer(Modifier.width(16.dp))
                ActionButton(text = stringResource(R.string.dialog_exit_confirm), onClick = onConfirm)
            }
        }
    }
}

@Composable
private fun HomeRoute(vm: TvViewModel) {
    // 每次回到首页判一次 30 分钟门禁。电视应用常年不关机，冷启动只判一次的话，
    // 一台开着几天的电视就再也不会自己刷新了。
    // 必须挂 Unit key：放进重组体里等于每帧判一次门禁，那不是懒加载是自杀。
    // 这里只会「判」不会「装载」——没缓存时仍旧交给冷启动那一轮，避免两个入口各打一遍网络。
    LaunchedEffect(Unit) { vm.checkHomeFreshness() }
    // 继续观看行直接读历史流：它有新记录就立刻出现在首页，
    // 不需要等下一次首页刷新，也不需要在这里加一个「装载」任务
    val history by vm.historyEntries.collectAsStateWithLifecycle()
    HomeScreen(
        loading = vm.homeLoading,
        // 首页屏幕收的是成品文案（它自己不认错误码），翻译这一步只在这里做一次
        error = vm.homeError?.let { appErrorText(it) },
        // 错误态重试 = 强制整页刷新：留着旧内容时用户点重试，等的就是真数据，
        // 而不是又一次被 30 分钟窗口挡回来的缓存
        onRetry = { vm.refreshAllHome("错误重试") },
        onOpenDetail = { cid, title -> vm.push(Dest.Detail(cid, title)) },
        onOpenSearch = { vm.push(Dest.Search) },
        onOpenPlayer = { cid, vid, title, index ->
            vm.push(Dest.Player(cid, vid, title, index))
        },
        onOpenHistory = { vm.push(Dest.History) },
        onOpenFavorites = { vm.push(Dest.Favorites) },
        onOpenSettings = { vm.push(Dest.Settings) },
        // ---- tab 化接线 ----
        tab = vm.homeTab,
        feeds = vm.homeFeeds,
        // 工具栏时间取整页统一的那一个：它与 30 分钟门禁判的是同一个值，
        // 不随切换 tab 跳动。按 tab 各算各的 fetchedAt 只会有两个结果对不上
        pageFetchedAt = vm.homeFetchedAt,
        onSelectTab = vm::selectHomeTab,
        // 刷新按钮：19 个 tab 整页重拉。与错误态重试走同一条通路，
        // 区别只在 homeError 的清理时机（refreshHome 会先把它清掉）
        onRefresh = { vm.refreshHome() },
        // 刷新在跑的 3~4 秒里内容可能与刚才完全一样（同一分钟内时间戳都不跳），
        // 图标自转是用户唯一能看到的「这一下按对了」
        refreshing = vm.homeRefreshing,
        // 刷新结果（成功/失败）浮一条提示：整页刷新成功时内容可能一模一样，
        // 没有这句话用户判断不出按钮到底有没有生效
        refreshNotice = vm.homeRefreshNotice,
        history = history,
        // 需求 10：点继续观看直接进播放器，不经过详情页
        onResumeHistory = vm::resumeFromHistory,
        onOpenCompleted = { vm.push(Dest.Completed) },
    )
}

/** 历史页：条目流来自 viewModelScope 里的 ViewModel，屏幕本身无状态 */
@Composable
private fun HistoryRoute(vm: TvViewModel) {
    val entries by vm.historyEntries.collectAsStateWithLifecycle()
    HistoryScreen(
        entries = entries,
        onResume = vm::resumeFromHistory,
        onDelete = vm::removeHistory,
        onClear = vm::clearHistory,
    )
}

/**
 * 已看完页：条目流来自 viewModelScope 里的 ViewModel，屏幕本身无状态。
 *
 * 删除只删「这一集」而不是整部剧：这一页是按集记录的（一部剧可以看好几集），
 * 传整个 entry 而不是 cid，屏幕就不必知道这个区别。
 */
@Composable
private fun CompletedRoute(vm: TvViewModel) {
    val entries by vm.completedEntries.collectAsStateWithLifecycle()
    CompletedScreen(
        entries = entries,
        onResume = vm::resumeCompleted,
        onDelete = { entry -> vm.removeCompleted(entry.cid, entry.episodeIndex) },
        onClear = vm::clearCompleted,
    )
}

// === 收藏模块（勿删）===
/**
 * 收藏页：与本文件其它 Route 一样只做「流 → 屏幕」的搬运。
 * 点条目进详情而不是直接播放——收藏是追剧语义，没有"该看哪一集"的答案。
 */
@Composable
private fun FavoritesRoute(vm: TvViewModel) {
    val entries by vm.favoriteEntries.collectAsStateWithLifecycle()
    FavoritesScreen(
        entries = entries,
        onOpen = { entry -> vm.push(Dest.Detail(entry.cid, entry.title)) },
        onDelete = vm::removeFavorite,
        onClear = vm::clearFavorites,
    )
}

/**
 * 设置页：授权态来自 [TvViewModel.authSession] 这条 Flow，兑换后立刻回灌到屏幕，
 * 所以不需要重启应用——屏幕上显示的授权状态就是正在生效的那一份。
 */
@Composable
private fun SettingsRoute(vm: TvViewModel) {
    val session by vm.authSession.collectAsStateWithLifecycle()
    SettingsScreen(
        session = session,
        onClearHistory = vm::clearHistory,
        onClearFavorites = vm::clearFavorites,
        onClearSearchHistory = vm::clearSearchHistory,
        onOpenGroup = { group -> vm.push(Dest.SettingsGroup(group)) },
        // 播放设置改走 VM 的写入口：写操作跑在 viewModelScope 上，
        // 用户改完立刻按返回也不会把这次写连同组合一起取消掉
        onUpdatePlayerSettings = vm::updatePlayerSettings,
    )
}

/**
 * 设置子页：只按 group 选渲染哪一段，三段共享同一份授权态与播放设置。
 *
 * 每页进入时各自从 store 读一次当前值（见 rememberPlayerSettingsHolder），
 * 所以播放子页改完返回菜单，摘要拿到的就是刚写进去的新值。
 * 前提是那次写不能被组合销毁打断——这正是下面把写接到 vm.updatePlayerSettings 的原因。
 */
@Composable
private fun SettingsGroupRoute(
    vm: TvViewModel,
    group: SettingsSectionId,
    onOpenCopyright: () -> Unit,
    /** 「关于」页那一行的点按；真正的检查跑在 viewModelScope 上，屏幕层不碰 Repo */
    onCheckUpdate: () -> Unit,
) {
    val session by vm.authSession.collectAsStateWithLifecycle()
    when (group) {
        SettingsSectionId.Playback -> PlaybackSettingsSection(
            holder = rememberPlayerSettingsHolder(vm::updatePlayerSettings),
        )
        SettingsSectionId.Network -> NetworkSettingsSection(
            session = session,
            onClearHistory = vm::clearHistory,
            onClearFavorites = vm::clearFavorites,
            onClearSearchHistory = vm::clearSearchHistory,
            onClearCompleted = vm::clearCompleted,
            onClearAuth = vm::signOut,
        )
        // 关于子页有两行可点：检查更新与版权声明（首焦点给前者）
        SettingsSectionId.About -> AboutSettingsSection(
            onOpenCopyright = onOpenCopyright,
            updateUi = vm.updateUi,
            onCheckUpdate = onCheckUpdate,
        )
    }
}

@Composable
private fun SearchRoute(vm: TvViewModel) {
    // 搜索历史是搜索页私有的状态，不进 ViewModel：只有这一个屏幕读写，
    // 放进 VM 只会让 VM 多背一份与它其它职责无关的流。
    val context = LocalContext.current
    val historyStore = remember(context) { SearchHistoryStoreImpl.create(context.applicationContext) }
    val history by historyStore.entries.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()

    SearchScreen(
        onSearch = { query -> Repo.search(query) },
        onOpenDetail = { cid, title -> vm.push(Dest.Detail(cid, title)) },
        onBack = { vm.pop() },
        loadHotWords = { Repo.hotWords() },
        history = history,
        onRecordSearch = { word -> scope.launch { historyStore.add(word) } },
        onClearHistory = { scope.launch { historyStore.clear() } },
    )
}

@Composable
private fun DetailRoute(vm: TvViewModel, dest: Dest.Detail) {
    // detailCid 已在 push 时切到 dest.cid，所以从收藏页点进来的第一帧拿到的就是这部剧的收藏态
    val isFavorite by vm.detailIsFavorite.collectAsStateWithLifecycle()
    // 历史落点：看过这剧就把光标和选中都放到看过的那一集
    val resume by vm.detailResume.collectAsStateWithLifecycle()
    // 已看完的集号：选集网格右上角的「看完」角标靠它。跟着 detailCid 切流，
    // 从收藏页点进 A、返回、再点进 B 时会自动换成 B 的完成态，不会把 A 的角标带过去
    val completedIndexes by vm.detailCompletedIndexes.collectAsStateWithLifecycle()
    DetailScreen(
        detail = vm.detail,
        loading = vm.detailLoading,
        error = vm.detailError,
        onPlay = { episode ->
            vm.push(
                Dest.Player(
                    cid = dest.cid,
                    vid = episode.vid,
                    title = dest.title,
                    episodeIndex = episode.index,
                ),
            )
        },
        onTierSelect = vm::selectDetailTier,
        selectedTier = vm.detailTier,
        onBack = { vm.pop() },
        // 用接口返回的真实剧情简介；取不到就留空让详情页隐藏该行。
        // 绝不能再拿剧名顶替——那会和标题完全重复，一眼就看出来不对
        description = vm.detail?.description.orEmpty(),
        onRetry = vm::retryDetail,
        isFavorite = isFavorite,
        resume = resume,
        completedIndexes = completedIndexes,
        onToggleFavorite = {
            val series = vm.detail ?: return@DetailScreen
            vm.toggleFavorite(
                LibraryEntry(
                    cid = series.cid,
                    vid = series.episodes.firstOrNull()?.vid.orEmpty(),
                    title = series.title,
                    // 详情页宣传图（eps.meta.pic 原图）就是收藏卡最该用的图
                    cover = series.cover,
                    episodeIndex = 0,
                    positionMs = 0,
                    durationMs = 0,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        },
    )
}

@Composable
private fun PlayerRoute(vm: TvViewModel) {
    when (val state = vm.player) {
        is PlayerUi.Loading -> LoadingState(stringResource(R.string.core_player_preparing))
        // 错误码到文案的唯一出口是 appErrorText：这里不许再出现任何自己拼的提示
        is PlayerUi.Error -> ErrorState(appErrorText(state.error), onRetry = vm::retryPlayer)
        is PlayerUi.Ready -> PlayerScreen(
            url = state.url,
            title = state.title,
            cid = state.cid,
            episodeLabel = state.episodeLabel,
            tiers = state.tiers,
            selectedTier = state.selectedTier,
            // 弹幕只有 TvViewModel 一个来源：窗口是 Compose 状态，换到新一窗就自动重组
            danmaku = vm.danmakuWindow,
            startPositionMs = state.startPositionMs,
            onTierChange = vm::onTierChangeFromPlayer,
            onProgress = vm::onProgress,
            onEnded = vm::onEnded,
            onBack = {
                vm.flushHistory()
                vm.pop()
            },
            hlsUrl = state.hlsUrl,
            episodes = state.episodes,
            currentEpisodeIndex = state.episodeIndex,
            onSelectEpisode = { episode -> vm.selectEpisodeFromPlayer(state.cid, episode) },
        )
    }
}