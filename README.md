# ModaTv

一个为 Android TV / 电视盒子编写的腾讯视频聚合客户端：Kotlin + Jetpack Compose for TV，
遥控器全键位可操作，D-Pad 驱动的自绘播放器 OSD，支持清晰度切换、选集、倍速、弹幕与画面比例。

> 本项目是一个**非官方的第三方前端**，数据来自用户自行配置的后端代理接口与 v.qq.com 的公开页面。
> 仅用于学习与个人使用，请自行确认内容版权与所在地区法律法规。

---

## 功能

**首页**
- 顶部轮播：抓取 v.qq.com 首页的 `/x/cover/<cid>` 推荐位，再回后端补全竖版/横版海报
- 四行频道：电视剧 / 电影 / 综艺 / 动漫，直接解析 v.qq.com 官方频道页
- 逐行降级：某个频道页抓不到时，仅该行回退到关键词搜索；全部失败才整体回退
- 缓存：频道行与轮播分层缓存，任何异常都沿用过期的旧数据而不是让首页变空

**搜索**
- 腾讯热词接口（pbaccess）拉取热门词，10 分钟成功缓存 / 60 秒空结果缓存
- 本地搜索历史（DataStore 持久化）

**详情**
- 剧集元信息、全部集数（正片在前，预告片单独标记）
- 收藏 / 取消收藏（追剧语义，独立于播放历史）

**播放器**
- Media3 ExoPlayer，HLS（整片 m3u8）优先，失败回退 MP4 直链
- 自绘 OSD，遥控器焦点由单一索引维护，不与 `PlayerView` 抢方向键：
  `清晰度 / 选集 / 倍速 / 弹幕 / 画面比例` 五个槽位，上下切换槽位、确认展开面板
- 播放 / 暂停：确认键；快进快退：左右键（OSD 功能栏展开时仍可快进）
- 展开某个浮窗（清晰度/选集/倍速/弹幕/比例）期间，左右键不响应，避免与面板操作抢键
- 面板内移动：上下键；改值：确认键（倍速与画面比例是「选中即生效」，上下即改）
- 返回键分层退出：先关面板 → 再关 OSD → 再离开播放页
- 弹幕：Canvas 自绘轨道，滚动 + 顶部/底部固定三种模式，字号四档，六种显示区域，上下边距可分步微调
- 画面比例：原始比例 / 拉伸 / 铺满 / 16:9 / 4:3
- 档位容错：取不到 vkey 的档位进入 10 分钟黑名单自动隐藏，过期后自动恢复

**历史 / 收藏**
- 播放历史最多 60 条，按 `updatedAt` 倒序，点条目直接续播
- 收藏不设上限，点条目进详情

**设置**
- 应用名称 / 版本号 / 构建号 / Android 版本 / 分辨率
- 代理网址：遥控器直接输入，或**手机扫码在同局域网内配置**（免去电视上打长 URL）
- 数据清理：播放记录 / 收藏 / 搜索历史

---

## 技术栈

| 项目 | 版本 |
| --- | --- |
| Kotlin | 2.2.20（Compose Compiler Plugin） |
| Android Gradle Plugin | 8.9.3 |
| Gradle | 8.11.1 |
| compileSdk / targetSdk / minSdk | 35 / 34 / 23 |
| Java / Desugaring | 17 / desugar_jdk_libs 2.1.2 |
| Compose BOM | 2025.12.01 |
| media3 (ExoPlayer / HLS / UI / OkHttp) | 1.4.1 |
| androidx.tv:tv-material | 1.0.0 |
| OkHttp | 4.12.0 |
| DataStore Preferences | 1.1.7 |
| ZXing core | 3.5.3（仅生成二维码矩阵，不引 android-embedded） |

依赖全部显式写在 `app/build.gradle.kts`，未使用 version catalog。

---

## 构建与安装

前置：JDK 17、Android SDK（`local.properties` 中的 `sdk.dir` 指向本机 SDK）。

```bash
# 调试包
./gradlew assembleDebug

# 产物
app/build/outputs/apk/debug/app-debug.apk

# 安装到已连接的电视 / 盒子
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

应用仅注册 `LEANBACK_LAUNCHER`，固定横屏，需要 `android.software.leanback`。
minSdk 23 覆盖大多数电视盒子；Android TV / Google TV 模拟器可直接运行。

---

## 后端代理

应用不自带任何接口地址：所有业务请求都发往**用户自建的代理后端**，地址在
「设置 → 网络与数据 → 代理网址」里填写，或用手机扫码在同一局域网内配置；出厂默认为空。

接口契约（请求路径、动作名、请求头、响应包装）**不随仓库发布**——需要自建后端时按自己的
服务实现对接，仓库里只保留调用方。

> 🔐 后端地址、设备令牌、指纹以及热词接口凭据都**不在仓库里**：
> 它们写在 `local.properties`（已 gitignore），构建期由 `app/build.gradle.kts`
> 注入成 `BuildConfig` 字段。首次检出请复制 `local.properties.example` 为
> `local.properties` 并填入自己的值；缺省为空串，应用会直接报请求失败。
> 自建后端时优先使用 HTTPS。

---

## 项目结构

```
app/src/main/java/com/moda/tv/
├── ModaApp.kt              Application：启动前同步注入持久化的代理网址
├── MainActivity.kt         单 Activity，全屏沉浸 + Compose 入口
├── model/                  纯数据：VideoItem / Episode / Tier / LibraryEntry / HomeRow ...
├── data/
│   ├── Api.kt              后端 HTTP 契约（6 个 action + 统一错误）
│   ├── Repo.kt             业务门面：首页行、详情、播放计划、弹幕、热词
│   ├── Parser.kt           接口 JSON → 模型的解析
│   ├── CarouselSource.kt   v.qq.com 首页 → 轮播 cid
│   ├── ChannelSource.kt    v.qq.com 频道页 → 四行内容
│   ├── LanConfigServer.kt  局域网扫码配置的临时 HTTP 服务
│   └── local/              DataStore 存储 + 代理网址合法性校验
└── ui/
    ├── AppRoot.kt          路由栈渲染 + 退出确认
    ├── Destinations.kt     手写路由（不引 navigation-compose）
    ├── TvViewModel.kt      页面栈 + 各屏数据装载 + 播放器装配状态
    ├── *Screen.kt          Home / Search / Detail / Player / History / Favorites / Settings
    ├── components/         卡片、通用状态、最小 OkHttp 图片加载器（不引 Coil）
    ├── player/             OSD、选集、倍速、弹幕面板、Canvas 弹幕层
    └── theme/              颜色与字阶
```

分层原则：UI 只调 `Repo`（数据侧唯一门面），不直接碰 `Api`；所有网络与 IO 异常在
ViewModel 内落成状态，屏幕层拿不到 throw，因此不会白屏或崩溃。

---

## 本地存储

全部使用 DataStore Preferences，一个键缺失即代表「用户从未改过」，读不到时静默退回默认值：

| 文件 | 内容 |
| --- | --- |
| `moda_history` | 播放历史（最多 60 条） |
| `moda_favorites` | 收藏（不设上限） |
| `moda_search_history` | 搜索历史 |
| `moda_api_settings` | 代理网址（键缺失 = 用出厂默认，因此「恢复默认」只是删键） |
| `moda_player_settings` | 倍速、弹幕开关/字号/区域/边距、画面比例 |

---

## 局域网扫码配置

电视上敲 URL 是反人类的，所以设置页提供一次性的手机端配置入口：

1. 打开「扫码配置代理网址」，弹出二维码；
2. 服务只在弹窗打开期间监听（从 8088 起尝试 20 个端口），关闭立即归还端口；
3. 手机浏览器打开二维码中的地址，输入新地址并提交；
4. 所有路由校验一次性 token，缺失或不匹配一律 403；
5. 校验、探活、落盘都由应用侧负责，服务本身只处理协议。

没有前台服务、没有常驻单例、没有延时回收——对象被丢弃即彻底消失。

---

## 开发约定

- **遥控器优先**：新增交互先在 D-Pad 上想清楚四个方向 + 确认 + 返回的语义，
  避免与 `PlayerView` 等原生控件并存两套焦点。
- **播放不因附属功能崩溃**：弹幕、封面、热词、轮播的失败一律静默降级（空列表 / 占位图），
  绝不阻断起播或首页。
- **接口失败要有兜底**：频道行 → 搜索词回退 + 过期缓存兜底；档位 → TTL 黑名单。
- **不加不必要的依赖**：图片加载用 OkHttp 手搓、路由用字符串手写、二维码只用 ZXing core。

---

## 已知限制

- 依赖第三方后端与 v.qq.com 的页面结构，腾讯侧改版会导致轮播或频道行降级到搜索方案。
- 设备 token 有有效期，过期后所有业务接口都会返回 `ok=false`。
- 未配置单元测试与持续集成，验证方式为真机/模拟器手动走查。

## 贡献

提交前请阅读 [CONTRIBUTING.md](CONTRIBUTING.md)。提交信息遵循 Conventional Commits：
`<type>(<scope>): <中文描述>`，例如 `fix(player): 修复倍速切换后画面卡顿`。

首次克隆后启用提交信息钩子（只需一次）：

```bash
git config core.hooksPath .githooks
```
