# AGENTS.md

给在本仓库里写代码的人 / AI 编码代理看的**强制约定**。产品介绍看 [README.md](README.md)，
提交信息规范看 [CONTRIBUTING.md](CONTRIBUTING.md)。本章不描述功能，只描述「不许怎么做」。

---

## 分层与依赖方向

```
ui/*Screen.kt ──▶ TvViewModel ──▶ Repo ──▶ Api / CarouselSource / ChannelSource
                                   └─────▶ data/local/*Store  (DataStore)
```

- **屏幕层不碰 `Api`、不碰 `OkHttp`、不碰 DataStore**。要数据只调 `Repo` 的既有方法；
  `Repo` 是数据侧唯一门面，它的公开签名是跨模块契约——实现可以改，签名不要改。
- **异常在 ViewModel 内落成 UI 状态**（`Loading / Ready / Empty / Error`）。屏幕层拿不到 throw，
  所以任何新页面都不会因为网络失败白屏。不要为了「拿到原始异常」在屏幕层加 try/catch。
- **路由是手写的字符串对象**（`ui/Destinations.kt` 里的 `Dest`），不引 navigation-compose。
  新增页面 = 加一个 `Dest` 子类型 + 在 `AppRoot` 的 `when` 里加一个分支，
  不要偷偷引入路由库，也不要让两个页面共用一个路由靠参数 if-else 区分
  （`Dest.Completed` 与 `Dest.History` 分开就是这个原因：来源、删除粒度、点击行为都不同）。
- **`data/local` 里的 Store 只管读写，不含业务判断**。带阈值的判断要么是纯函数
  （如 `IntroOutroRules`），要么在 `Repo` / ViewModel 里，Store 里不出现「如果超过 95% 就……」。
- **`ui/player/PlayerEngine` 只认 Boolean / String / 原生类型**，不 import `data.local`
  的 `PlayerCore` / `DecoderMode`。内核层与设置层各自独立演进，映射留在上层做。

## 遥控器纪律

- 新增交互先在 D-Pad 上写清 **上 / 下 / 左 / 右 / 确认 / 返回** 六个语义，再动手画 UI。
- **焦点只有一个来源**。自绘 OSD 的焦点由 `OsdSlot` 单一索引维护；
  不要把 `PlayerView`、原生 `Button` 等自带焦点的控件塞进同一层，两套焦点必然互相抢键。
- **按键按「最上层接管」分层**：浮窗展开后它就是最上层，按键先归它，不穿透到底下的控制条。
  浮窗左右键按导航轴分派（横向档位条换值，纵向列表在左右键上静默），
  不要用「所有浮窗一律忽略左右键」糊过去——用户看到一排按钮却推不动。
- **返回键分层退出**：先关面板 → 再关 OSD → 再离开播放页 → 再退出应用。
  任何一层新增弹窗都要插进这条链，不能直接 finish。
- 首焦点靠「数据规模」触发的 `LaunchedEffect` + 多帧重试抢（见 `HomeScreen`），
  不要用布尔 key 的 `LaunchedEffect` —— key 一变协程就被取消，焦点会永久留在工具栏。

## 降级与兜底

- **附属功能失败不阻断主流程**：弹幕、封面、热词、演职员信息的失败一律静默降级
  （空列表 / 占位图 / 不显示），绝不阻断起播或首页。
- **首页只有 v.qq.com 一个数据源**，没有搜索兜底。抓不到就是这一屏空，由持久化缓存
  （`HomeCacheStore`）顶上。不要为了「让首页看起来有内容」把搜索结果塞进首页——
  这是有意的取舍：宁可空，也不拿别处的数据冒充。
- **档位失败进 TTL 黑名单**（10 分钟），不要改成立即永久隐藏：绝大多数失败是网络抖动，
  永久拉黑会让一个档位在整个进程生命周期内消失。
- **拉黑、缓存、重试都要带时间**。任何「永久」状态都要能回答：它什么时候恢复？

## 依赖与自造轮子

- **不加不必要的依赖**：图片加载用 OkHttp 手搓（`components/RemoteImage.kt`）、
  路由用字符串手写、二维码只用 ZXing core。引入新依赖前先回答「现有手写实现缺什么」。
- 依赖全部显式写在 `app/build.gradle.kts`，**不用 version catalog**。
- IJK 内核用 `xyz.doikki.android.dkplayer:player-ijk`（官方 0.8.8 只在已停服的 jcenter 上），
  native 库按 ABI 裁剪到 `arm64-v8a` + `armeabi-v7a`。加 ABI 前先算 APK 体积代价。

## 存储约定

- 全部 DataStore Preferences，一个键缺失 = 「用户从未改过」，读不到时**静默退回默认值**，
  不要抛异常、不要写「首次运行迁移」逻辑。
- 一个存储域一个 DataStore 文件（`moda_history` / `moda_favorites` / `moda_completed` /
  `moda_home_cache` / `moda_intro_outro` / `moda_player_settings` / `moda_api_settings` /
  `moda_search_history` / `moda_copyright_notice`）。新增域 = 新增文件，
  不要往别人的文件里塞键。
- 枚举落盘用 `enum.name`，不存中文 label——改文案不能让旧数据失联。
- 「恢复默认」= 删键，不是写一个和默认值相同的值。

## 播放器与阈值

- 片头片尾的判定全部是 `IntroOutroRules` 里的**纯函数**（无 Context / DataStore / 协程），
  可以在 JVM 里直接验证边界。改阈值之前先读那里每条常量上的说明：
  1% 误触线、8%/6% 兜底、90s 片头封顶、5 分钟以下不兜底，每一条都对应一个真实故障。
- 完成线是片长 95%（`COMPLETE_PERCENT`），**只有 `TvViewModel.markCompleted` 一个入口**。
  已看完的条目从历史移出、进 `moda_completed`，粒度是 `(cid, episodeIndex)` 不是 cid。

## 注释

- 注释写**为什么**，不写**是什么**。阈值、时序、按键分层这类「看代码看不出来理由」的地方必须有注释；
  `i++` 旁边不需要。
- 踩过的坑要留在注释里（例如「用布尔 key 的 LaunchedEffect 会导致焦点永久留在工具栏」）。

## 提交与验证

- 提交信息遵循 Conventional Commits，scope 见 CONTRIBUTING.md 的登记表：
  `<type>(<scope>): <中文描述>`，由 `.githooks/commit-msg` 强制校验
  （首次克隆执行 `git config core.hooksPath .githooks`）。
- 一次提交只做一件事；格式化与逻辑改动不混在同一个提交里。
- 改动后至少跑通 `./gradlew assembleDebug`，再在真机 / 模拟器上手动走查涉及的那条遥控器路径。
  项目没有单元测试与 CI，手动走查就是唯一验收手段——所以别只编译不点。
- **不要提交** `local.properties`、`.idea/`、`build/` 或任何真实 token / 指纹 / appKey；
  敏感值只放 `local.properties`，由 `app/build.gradle.kts` 注入成 `BuildConfig`。
  `.githooks/pre-commit` 会在提交前扫公网 IP、凭据键值与长密钥串，命中直接拒绝——
  被它拦下时先确认是真泄露还是误报，不要习惯性地 `--no-verify`。
- **截图属于代码**：仓库是公开的，图里的代理地址、搜索词一样会泄露。
  截图前把地址改成示例值，或提交前打码。
