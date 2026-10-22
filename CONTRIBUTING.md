# 提交规范

本项目使用 [Conventional Commits](https://www.conventionalcommits.org/zh-hans/) 的子集：
**type / scope 用英文，描述用中文**。格式由 `.githooks/commit-msg` 强制校验。

## 格式

```
<type>(<scope>): <描述>
```

- `type`、`scope` 一律小写英文；`scope` 可省略。
- 冒号是**半角冒号 + 一个空格**。
- 描述用中文，写清楚做了什么，不要写“修改了一些东西”。
- 整行不超过 72 个字符，结尾不加句号。
- 破坏性变更：type 后加 `!`，并在正文写 `BREAKING CHANGE:` 说明。

## type 取值

| type | 用于 |
| --- | --- |
| `feat` | 新增功能 |
| `fix` | 修复缺陷 |
| `refactor` | 重构，不改变外部行为 |
| `perf` | 性能优化 |
| `docs` | 仅文档 / 注释 |
| `style` | 仅格式（空格、换行、import 排序），不改逻辑 |
| `test` | 测试相关 |
| `build` | 构建脚本 / 依赖 / Gradle 配置 |
| `ci` | CI 配置 |
| `chore` | 杂项：初始化、版本号、忽略文件等 |
| `revert` | 回滚某次提交 |

## scope 取值

取改动所在的层次，同一模块固定同一个词：

`home` 首页 · `search` 搜索 · `detail` 详情 · `player` 播放器 · `data` 网络/仓库/缓存 · `model` 数据模型 · `ui` 通用组件 · `build` 构建 · `deps` 依赖

新增模块时先在此处登记，再使用。

## 示例

```
feat(player): 支持长按方向键连续快进
fix(home): 频道页抓取失败时回退到关键词搜索
refactor(data): 抽出腾讯接口的公共请求封装
perf(player): 弹幕渲染改为复用节点池
docs: 补充后端代理接口的配置说明
build(deps): 升级 Media3 到 1.4.1
chore: 初始化 ModaTv Android TV 项目骨架
```

反例，钩子会直接拒绝：

```
update               # 没有 type
fix:xxx              # 冒号后缺少空格
Fix(Player): xxx     # type / scope 必须小写
fix(player):修复      # 冒号后缺少空格
修复播放器 bug        # 没有 type
docs: 补充说明。      # 结尾不加句号
```

## 提交粒度

- 一次提交只做一件事：说得清、能单独回滚。
- 格式化与逻辑改动不要混在同一个提交里。
- 不相关模块不要塞进同一个提交。

## 多行正文

需要说明动机或影响时，空一行后写正文，每行不超过 72 字符：

```
fix(player): 倍速切换后画面卡顿

ExoPlayer 在 setPlaybackSpeed 后会重置 surface，
改为在 STATE_READY 之后再应用倍速。
```

## 钩子

首次克隆仓库后执行一次：

```bash
git config core.hooksPath .githooks
```

之后 `git commit` 会校验提交信息，不合规直接拒绝。
`git commit --no-verify` 可跳过，仅限紧急情况。
