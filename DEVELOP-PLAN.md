# Podara Develop 清单

> 生成日期：2026-10-18
> 对照基线：`AUDIT-2026-10.md`（2026-10-17）
> 当前版本：`packageVersion = "1.0.3"`
>
> **进度更新（2026-10-18 晚）**：第 0–5 批全部落地。
> 工作区编译通过，测试 **513 个全绿**（起点 434，+79）。下文保留原始分析，
> 已完成的项在标题后标注 ✅。

---

## 0. 先说结论

`AUDIT-2026-10.md` 里的 P0 全部修完了，P1 修掉了约 2/3。**这一轮不该再照着旧审计扫一遍**，而是三件事：

1. **工作区现在编译不过**（20 个 error）——快捷键改造停在半路，这是唯一的阻塞项。✅ 已修
2. **旧审计漏掉的类**：写入原子性（`TopChartsCache`）、UI 状态与真实状态不一致（语言不落盘、托盘菜单文案、选中集越界）。✅ 已修
3. **旧审计提过但没做完的**：`withoutFocusRing` 依然 51 处、下载进度节流只补了一半、Snackbar 只换了位置没解决。✅ 全部已修

以下按「能不能现在就动手」排序。

---

## 已完成批次总览

### 第 0 批 · 解锁 ✅
- P0-1 修复编译失败（20 error → 0）

### 第 1 批 · 小改动大收益 ✅
- P1-1 队列去重、P1-2 语言落盘、P1-3 退订选中集、P1-4 托盘文案
- P1-5 resume 节流、P1-6 Snackbar、P1-7 UA + charset、P1-8 newEpisodes
- P1-9 缓存原子写、P2-1/2 `stripHtml` 合并

### 第 2 批 · 体验与数据 ✅
- Downloads 页 N+1 → 批量 `getImageUrlsByIds`
- 主线程 stat → `Dispatchers.IO` + 按 `downloadVersion` 缓存
- 分组列表拍平，恢复 LazyColumn 懒加载（每行一个 item + `contentType`）
- 窗口最小尺寸 900×600dp（含 dp→px 换算，AWT 要设备像素）
- MiniPlayer 260dp 硬下限 → `widthIn(min = 232, max = 260)`
- Slider 拖动状态 → `collectIsDraggedAsState()`（手势被中止时也复位）
- 测试日志隔离（`-Dpodara.log.dir`），不再污染 `~/.podara`

### 第 3 批 · 体验与正确性 ✅
- Discover 组合期写 snapshot state → `LaunchedEffect(podcasts.size)`
- `showMore` 菜单按 `currentEpisodeId` 做 key（切集不再残留）
- 日期格式三份合并为 `util/DateFormat.kt`，并**跟随语言**（中文显示 `2025年12月30日`）
- `runBlocking` 退出路径：先隐藏窗口再写库，消除「未响应」观感
- `Strings` 未命中改为告警一次（不再静默显示 key 名）
- LazyColumn `contentType` 全量补齐（9 处）
- 下载按钮 `width(108.dp)` → `widthIn(108, 180)`；播放按钮禁用态加 0.45 alpha + 去掉手型光标
- 队列 scrim 语义：`invisibleToUser` 加在被遮挡的 Column 上（不是 scrim 本身）
- History 播放态判定与 Favorites 对齐（原先 `historyId` vs `episodeId` 不一致）

### 第 4 批 · 工程卫生 ✅
- 删除 19 条未用版本目录条目 + `sqldelight` 插件 + 6 个悬空 version
- JNA 5.6.0 本地 jar → Maven 坐标 5.14.0
- CI 删除重复的 `gui-tests` job（白名单已漂移，主 job 本就是全量）
- CHANGELOG 补 `1.0.3`（Added 6 / Changed 12 / Fixed 21 / Tests 3）

### 第 5 批 · 结构性（架构重写）✅
- **27 焦点改造**：39 处 `canFocus = false` 全部恢复可聚焦，改为「仅键盘导航时绘制自带指示器」
  - 新增 `LocalKeyboardNavigation`（Tab 进入键盘模式、任意指针按下退回鼠标模式）
  - 新增 `DesignTokens.FocusIndicator`（2dp / 8dp 圆角）
  - 旧函数名 `clickableWithoutIndicationOrFocusRing` 保留为别名，避免 39 处调用点大改
- **28 播放器单线程所有权重构** ✅
  - `MpvAudioPlayerEngine` 重写为「一个 worker 线程独占 `mpvHandle`」，创建/使用/销毁全在该线程
  - 接口从「分散回调 + 公共可变字段」改为 `open() -> sessionId` + `setEventHandler` + 不可变 `PlayerSnapshot`
  - 删掉了 `@Volatile` 四处、`pollThread`、`shuttingDown` 等手工同步痕迹：不再需要，因为只有一个线程碰句柄
- **29 mpv 事件驱动替代轮询** ✅
  - 250ms 轮询 + 4 次字符串属性读取 → `mpv_observe_property` 原生格式监听（double / flag，**不再需要 `mpv_free`**）
  - `mpv_wait_event` 改为带超时阻塞，命令队列优先处理
  - **删除 `PlaybackRules.decideStopTransition` 与 3 秒墙钟启发式**：完成事件由 session id 判定，
    「短于 3 秒的片头 jingle 被误判为陈旧」这一类问题从设计上消失
  - EOF 用 `eofReported` 每 session 只报一次，避免 keep-open 下重复推进队列
- **30 FTS5 全文搜索**（见下）

### 本轮重写带来的两个真实退化，已修
1. **`keep-open` 选项丢失** —— 重写 `MPV_STARTUP_OPTIONS` 时漏掉。没有它 mpv 在流末尾卸载文件，
   `eof-reached` 观察不到，自动续播整个失效。测试 `keepOpenStaysEssential` 抓到。
2. **错误分类丢失 URL** —— `emitFailure` 里把 url 传成 `null`，所有失败都被判成 `FILE` 而非 `NETWORK`。
   已改成签名强制传 url，并补 `everyCategorySurvivesTheEngineBoundary` 锁住。

### 未做（明确记录）
- **引入 detekt/ktlint**：会与现有 39 处命名/风格产生大量冲突，需单独一次格式化提交
- **`SyncManager` 决策**：仍是完整实现但零实例化，接线还是删除是产品决策


---

## P0 · 阻塞项（先修这个，否则什么都验证不了）

### P0-1 工作区编译失败：快捷键接线半成品

实测 `.\gradlew.bat :desktop:compileKotlinDesktop` → **BUILD FAILED，20 个 error**。

缺失项逐条列清：

| 位置 | 问题 |
|---|---|
| `App.kt:866` | `KeyEvent` 未导入 |
| `App.kt:867` | `KeyEventType` 未导入 |
| `App.kt:868` | `shortcutFor` 未导入（`app.podara.platform.Shortcuts`） |
| `App.kt:869+` | `ShortcutAction` 未导入 |
| `App.kt:917` | `onPreviewKeyEvent` 未导入（`androidx.compose.ui.input.key`） |
| `App.kt:867` | **`isTypingInTextField` 全项目不存在** |
| `Shortcuts.kt:49` | `Key.Mute` 不存在，Compose 1.9.0 里叫 `Key.VolumeMute` |
| `PodaraSearchField.kt:70` | `LocalSetTextFieldFocus` 有读者无提供者，永远拿到空 lambda |

**关于 `isTypingInTextField`**：`Interactions.kt:139/149` 已定义 `LocalTextFieldFocus` 和 `LocalSetTextFieldFocus`，但 `App.kt` 根部**没有任何 `CompositionLocalProvider` 提供它们**。所以现在即便补上 import 让编译通过，快捷键接通后依然会在搜索框里打字时被空格键打断播放。

正确做法是在 `App.kt` 的 `PodaraTheme { }` 内、`Column` 外，用状态提供 writer：

```kotlin
var isTypingInTextField by remember { mutableStateOf(false) }
CompositionLocalProvider(
    LocalTextFieldFocus provides isTypingInTextField,
    LocalSetTextFieldFocus provides { isTypingInTextField = it }
) {
    Column(...) { ... }
}
```

**成本：极小（约 20 行）**

---

## P1 · 用户可见错误

### P1-1 队列重复加入同一集 → LazyColumn 重复 key 崩溃

`MediaPlayerState.kt:284` 无条件 `queue.add(...)`，`PlayerUI.kt:1364` 用 `key = { _, item -> item.episodeId ?: item.url }`。

`AddToQueueButton`（`EpisodeActionIconButton.kt:165`）没有去重也没有禁用态。对同一集连点两次「加入队列」→ 队列出现两个相同 `episodeId` → Compose 抛 `IllegalArgumentException: Key was already used` → 队列抽屉白屏。

**修复**：`addToQueue` 内按 `episodeId` 去重，并给用户「已在队列」的反馈。**成本：小**

### P1-2 语言切换不落盘

`SettingsScreen.kt:140` 的 `onLanguageSelected` 只调 `Strings.updateLanguage(selected)`（内存 snapshot state），持久化在 `:141` 的 `onChange = { Settings.setLanguage(...) }`——需要用户**再点一次**「Change」才写盘。

选完语言直接切走 → 当前会话中文，重启回英文。

**修复**：`onLanguageSelected` 里同时写 `Settings.setLanguage(it)`，删掉多余的 Change 按钮。**成本：极小**

### P1-3 批量退订会删掉用户看不见的选中项

`App.kt:1502` 全选判定用 `selectedPodcasts.size == filteredPodcasts.size`，但 `selectedPodcasts` 是**全量**集合，`filteredPodcasts` 是搜索过滤后的。

先勾 A、B → 搜索过滤掉 A、B → 点全选 → 再清空搜索 → 点删除：`App.kt:1803` 的 `selectedPodcasts.forEach { unsubscribe(it) }` 会把隐藏的 A、B 一起退订。确认框显示「删除 3 个」，实际删 5 个，不可撤销。

**修复**：删除时取 `selectedPodcasts intersect filteredPodcasts.map { it.origin }`。**成本：小**

### P1-4 托盘菜单播放/暂停文案永远是 "Play"

`SystemTrayManager.kt:135` 每次 `showMenu` 都用硬编码 `Strings["tray_play"]` 新建菜单项，从不看 `playerState.isPlaying`。`:98` 的 `updatePlayPauseLabel` 改的是**上一次**菜单的 `JMenuItem`，`hideMenu()` 后已 dispose。

正在播放时打开托盘菜单 → 显示 "Play"，点下去实际是暂停。

**修复**：`showMenu` 里 `mkItem(if (playerState.isPlaying) Strings["tray_pause"] else Strings["tray_play"], ...)`。**成本：极小**

### P1-5 恢复下载没有进度节流

`App.kt:603-613` 的 `startDownload` 用了 `ProgressThrottle`（注释明确解释了 100MB ≈ 12800 次回调的问题）；`App.kt:701-702` 的 `resumeDownload` 是裸的：

```kotlin
downloadProgress = downloadProgress + (episodeId to Pair(current, total))
```

断点续传正是大文件场景，重组风暴比正常下载更严重。**修复**：resume 也建一个 `ProgressThrottle()`。**成本：极小**

### P1-6 Snackbar 仍在左上角且永不消失

`SettingsScreen.kt:475-488` 是裸 `Snackbar(...)`，无 `Scaffold` 包裹，作为第二个根节点叠在内容上 → 出现在左上角。而且没有定时关闭，只有点「OK」才清 `showCopiedSnackbar`。

**修复**：加 `LaunchedEffect(showCopiedSnackbar) { delay(3000); showCopiedSnackbar = false }` + `Box(contentAlignment = BottomCenter)`。**成本：小**

### P1-7 RSS 请求不带 User-Agent + 强制 UTF-8 解码

两个都是旧审计提过、**至今未修**的：

- `FetchPodcastClient.kt:89-94` 只设了 `Accept`，无 `User-Agent`。Acast / Megaphone / Art19 对非浏览器 UA 会返 403。
- `FetchPodcastClient.kt:116` 是 `rssParser.parse(bytes.decodeToString())`，`decodeToString` 默认 UTF-8。GBK / ISO-8859-1 编码的中文或欧陆播客标题**必然乱码**。

**修复**：加 `header(HttpHeaders.UserAgent, "Podara/1.0.3 (+github.com/kazimics/podara)")`；解码改为从 XML 声明或 `Content-Type` 的 charset 判断，回退 UTF-8。**成本：小**

### P1-8 `newEpisodes` 列从未被 UPDATE → 「新剧集」徽章永不出现

`AppDatabase.kt` 里 `newEpisodes` 只有 INSERT 写死 0（`:839`）和两处读取（`:816`、`:831`），**没有任何 UPDATE**。而 `App.kt:1733` 的 `val newCount = sub?.newEpisodes ?: 0` 用它渲染徽章。

通知（`SubscriptionManager.kt:90`）和自动下载（`:80`）都已接线，唯独这个计数没写回。

**修复**：在 `updatePodcast` 的事务里加 `UPDATE podcastSubscription SET newEpisodes = ? WHERE origin = ?`。**成本：小**

### P1-9 `TopChartsCache` 写入非原子

`TopChartsCache.kt:69` 直接 `cacheFile.writeText(...)`。这与已经修好的 `Settings.kt:101-110`（temp + rename）是同一类问题，但漏了这个。

写入中途崩溃 → 文件截断 → 下次启动 `load()` 走 catch 返回 null。当前用户目录里的 `top-charts.json` 是好的（46382 字节），但日志里已有 7 次 `JsonDecodingException`。

**修复**：照抄 `Settings.save()` 的 temp + rename 模式。**成本：极小**

---

## P2 · 体验与性能

### 性能

| # | 问题 | 位置 | 说明 |
|---|---|---|---|
| 1 | **`stripHtml` 每次调用编译两个 Regex** | `App.kt:2483-2484`、`PlayerUI.kt:1191-1192` | 且在组合期被调用（`App.kt:2252` 每行 episode、`:1915` 播客简介）。提到文件级 `private val` 即可。**成本：极小** |
| 2 | **`stripHtml` 重复 2 份** | `App.kt:2482`、`PlayerUI.kt:1190` | 合并到一个 util。**成本：极小** |
| 3 | Downloads 页 N+1 查库 | `DownloadsScreen.kt:112-115` | 每个下载一次 `getById`。加批量 `getByIds`。**成本：小** |
| 4 | Downloads 页主线程磁盘 stat | `DownloadsScreen.kt:107` | `all.sumOf { File(it.filePath).length() }` 对 N 个文件 stat。**成本：小** |
| 5 | 下载行每次组合 2 次磁盘 IO | `DownloadsScreen.kt:876-878` | `File(...).exists()` + `length()` 在 composable body 里，滚动时每帧每行都触发。**成本：小** |
| 6 | 「已完成」分组破坏 LazyColumn 懒加载 | `DownloadsScreen.kt:390` + `810-824` | 每个 podcast 是一个 item，内部 `group.items.forEachIndexed` 一次性组合全部行。应拍平。**成本：中** |
| 7 | 全屏播放器 show notes 一次性解析 | `PlayerUI.kt:471-475` | `verticalScroll` + 整段 HTML 递归解析（`:994-1094`），20–80KB notes 会卡数百 ms。**成本：中** |
| 8 | LazyColumn 全部缺 `contentType` | `App.kt:1731`、`HistoryScreen.kt:256/267`、`DownloadsScreen.kt:274/286/378/390` | section header 与卡片混排无法复用槽位。**成本：小** |
| 9 | 关闭窗口在 UI 线程 `runBlocking` 写库 | `App.kt:568`、`:1290` | 队列 500 项时窗口冻结数百 ms，Windows 显示「未响应」。**成本：小** |
| 10 | `Logger` 每行 open/append/close | `Logger.kt:48` | 旧审计已提，未修。实测单个 `debug.log.3` 达 1 MB。**成本：小** |

### 正确性

| # | 问题 | 位置 |
|---|---|---|
| 11 | **Discover 在组合期写 snapshot state** | `DiscoverScreen.kt:346` `featuredCarousel.onListChanged(podcasts.size)` 直接写在 body 里，应包 `LaunchedEffect(podcasts.size)` |
| 12 | **全屏播放器 `showMore` 菜单跨集残留** | `PlayerUI.kt:615` `remember { mutableStateOf(false) }` 键是位置不是 episodeId → 菜单开着切集，复制链接拿到新集的 URL |
| 13 | **`isDragging` 可能永久为 true** | `PlayerUI.kt:689-690` 拖动中按 ESC 或切集，`onValueChangeFinished` 不触发 → 滑块锁死。MiniPlayer 用的是 `collectIsDraggedAsState()`，两边不一致 |
| 14 | **批量退订选中集越界** | 见 P1-3 |
| 15 | **语言切换不落盘** | 见 P1-2 |
| 16 | **托盘文案错** | 见 P1-4 |
| 17 | **`newEpisodes` 死列** | 见 P1-8 |
| 18 | **`Strings` 未命中静默返回 key 本身** | `Strings.kt:504-506` `map[key] ?: en[key] ?: key`。拼错 key 会安静显示 `"player_speed"`。建议 debug 构建下 `error()` 或至少 `Logger.w` |
| 19 | 日期格式化 3 份且格式不一致 | `App.kt:2490`（`yyyy-MM-dd`）、`PlayerUI.kt:1198`（`MMM dd, yyyy`）、`DownloadsScreen.kt:1057`（`yyyy-MM-dd`）。同一 app 内列表页和播放器日期格式不同，且都不随语言切换 |
| 20 | History / Favorites 播放态判定范式不一致 | `HistoryScreen.kt:274`（`historyId`）vs `FavoritesScreen.kt:238`（`episodeId \|\| url`）→ 同一集从 Downloads 播放时，Favorites 高亮、History 不高亮 |
| 21 | 详情页返回无条件 bump Discover 刷新键 | `App.kt:1015-1018` 从 Home 进详情返回也触发 Discover 重新拉榜单 |
| 22 | 组合期读非 snapshot 的 Settings | `SettingsScreen.kt:301` `when (Settings.getCloseAction())` 靠巧合生效 |
| 23 | 文件选择器在协程里同步阻塞 UI | `SettingsScreen.kt:190/221` → `:848-875` 的 `JFileChooser.showOpenDialog` 在 Main dispatcher |
| 24 | 默认下载路径判断重复 | `SettingsScreen.kt:234` 与 `Settings.kt:117-119` 是同一逻辑两份拷贝 |

### 布局与交互

| # | 问题 | 位置 |
|---|---|---|
| 25 | **窗口无最小尺寸** | `Main.kt:54-57` 只设了 size。`PlayerUI.kt:233` MiniPlayer 中间块硬编码 `width(260.dp)`，窗口拖窄后标题和时间被挤没。建议 `window.minimumSize = Dimension(900, 600)` |
| 26 | **下载按钮固定 108dp** | `PlayerUI.kt:914` `width(108.dp)`，"Downloaded" 已很紧，换长语言必然裁字。改 `widthIn(min = 108.dp)` |
| 27 | 播放按钮禁用态无视觉区分 | `PlayerUI.kt:293`、`:786` 无播放内容时不可点，但外观和手型光标都不变 |
| 28 | 队列 scrim 缺语义屏蔽 | `App.kt:1171-1178` 视觉模态，但没 `invisibleToUser()`（FullPlayerOverlay 做了）→ 读屏会同时读到被遮挡页面 |

### 一致性 / 可访问性

| # | 问题 | 数量 |
|---|---|---|
| 29 | **`withoutFocusRing` 仍 51 处** | `Interactions.kt:77` 的 `canFocus = false` 直接阻止聚焦。键盘 Tab 到不了任何控件。快捷键是唯一补救，且快捷键本身还没接完 |
| 30 | 硬编码 `sp` 字号 | 121 处 |
| 31 | 硬编码 `Color.Transparent` | 31 处 |
| 32 | `spring()` 使用 | **0 处**（36 处动画全是 tween + 默认缓动） |
| 33 | 主屏切换无过渡 | `AnimatedContent` / `Crossfade` 各仅 1 处（且疑为死 import） |
| 34 | 23 个 i18n key 定义后从未使用 | `nav_home`、`player_previous`、`episode_new` 等。中英已对齐（232/232），但冗余 |

---

## P3 · 维护性 / 工程

### 依赖与构建

- **19 条版本目录条目从未使用**：`aboutlibraries-core`、`aboutlibraries-compose-m3`、`junit`、`ktor-client-serialization`、`ktor-client-serialization-json`、`ktor-client-logging`、`xmlutil-core`、`xmlutil-serialization`、`kotlinx-datetime`、`kotlinx-serialization-json-core`、`ktor-client-core-kmp`、`ktor-client-content-negotiation-kmp`、`ktor-client-logging-kmp`、`rssparser-kmp`、`multiplatform-settings-core`、`multiplatform-settings-coroutines`、`sqldelight-runtime`、`sqldelight-coroutines`、`sqldelight-sqlite-driver`；外加 `sqldelight` 插件。全套 KMP 遗留。**成本：极小**
- **JNA 用本地 jar 而非依赖坐标**：`desktop/build.gradle.kts:39-40` 是 `files("../libs/jna-5.6.0.jar")`。5.6.0 是 2020 年的版本，且 `MediaKeys.kt:10` 的注释说「JNA 5.6.0 的 WinUser 没有 VK_MEDIA_* 常量」——升到 `net.java.dev.jna:jna-platform:5.14+` 后这些手写常量可以删掉
- **`maxParallelForks = 1`**（`:63`）：测试串行，40 个测试类全跑一遍较慢
- **`--add-modules=java.sql` 在 jvmArgs**（`:71`）：jlink 已显式包含 `java.sql`（`:122`），这行可能是冗余的
- **`createRuntimeImage` 用 `doLast` 打补丁**（`:92-167`）：先让 Compose 生成完整 runtime，再删掉重建。如果 Compose 支持配置 `modules`，应直接用官方配置，而不是事后替换目录

### CI

- **`ci.yml:105` GUI 测试白名单漏了 2 个类**：实际有 `AppGUITest`、`DiscoverScreenTest`、`DownloadsScreenTest`、`FavoritesScreenTest`、`HistoryScreenTest`、`OverlayPixelTest`、`SettingsScreenTest` 共 7 个，白名单只列了 5 个（漏 `DownloadsScreenTest`、`OverlayPixelTest`）。而 `:52` 的主 job 已经跑全量，这个 job 其实是重复劳动
- **没有 lint / detekt / ktlint / spotless**：全仓零命中。`App.kt` 2376 行、`PlayerUI.kt` 1728 行这类大文件没有任何自动约束
- **CHANGELOG 缺 1.0.3 条目**：`packageVersion = "1.0.3"`，但 CHANGELOG 最新是 `[1.0.2] - 2026-07-28`，`[Unreleased]` 是空的。release.yml 会校验 tag 与 packageVersion 一致，但不管 CHANGELOG

### 死代码

- **`SyncManager` 完整实现但零实例化**：有模型、DAO、网络调用、序列化全套，`syncAction` 表从未写入，`sync()` 永远返回 `NoActions`。只有 `HttpClients.kt:15` 的注释提到它。**决策：接线或删除**
- `DiscoverScreen.kt:882-929` 整段 `PodcastCard` 被注释保留（40 行）
- `DiscoverScreen.kt:394-419` Trending 区块注释保留
- `SystemTrayManager.kt:234` `windowTitle()` 定义后从未调用
- `MpvApi.kt` 的 `mpv_observe_property` / `request_event` / `request_log_messages` 声明了但一个都没用，全靠 250ms 轮询
- `MpvAudioPlayerEngine.kt:62-70` 的 `mpv_create()` 和 5 个 `mpv_set_option_string` 返回值全丢弃 → 配错了也永远发现不了
- `App.kt:989-994` 六处导航回调是同一段状态重置的六份拷贝

### 测试

- **测试污染真实用户目录**：`Logger.kt:11` 硬编码 `File(System.getProperty("user.home"), ".podara")`。我看了实际的 `~/.podara/debug.log`：**842 KB / 9964 行**，里面能清楚看到 `MediaKeysTest.kt:121` 的 `RuntimeException: boom`、`MpvAudioPlayerEngineTest` 的 `podara-missing-*.mp3`。所有测试的 DB 都用了 `java.io.tmpdir`，**唯独 Logger 没有**。应在测试 setup 里覆写 `user.home`
- `Tests` 没有覆盖 `MediaPlayerState` 的队列去重、`SettingsScreen` 的语言持久化、`SystemTrayManager` 的菜单文案——正好是上面 P1 的几条

---

## 建议动手顺序

### 第 0 批：解锁（30 分钟）
1. **修编译**（P0-1）：补 4 个 import、`Key.Mute` → `Key.VolumeMute`、在 `App.kt` 根部提供 `LocalTextFieldFocus` / `LocalSetTextFieldFocus`
2. 跑通 `.\gradlew.bat :desktop:desktopTest`，确认 433 个测试仍全绿

### 第 1 批：小改动大收益（半天）
3. 队列去重（P1-1）
4. 语言持久化（P1-2）
5. 批量退订选中集（P1-3）
6. 托盘菜单文案（P1-4）
7. resume 进度节流（P1-5）
8. `stripHtml` 提正则 + 合并两份（P2-1、P2-2）
9. `TopChartsCache` 原子写（P1-9）
10. Snackbar 定位 + 自动消失（P1-6）

### 第 2 批：网络与数据（一天）
11. User-Agent + charset 探测（P1-7）
12. `newEpisodes` 写回（P1-8）
13. Downloads 页 N+1 + 主线程 stat + 拍平分组（P2-3/4/6）
14. 测试隔离 `user.home`（P3）

### 第 3 批：体验（两天）
15. 窗口最小尺寸 + 下载按钮宽度（P2-25/26）
16. Discover 组合期写状态（P2-11）
17. `showMore` / `isDragging` 状态键（P2-12/13）
18. LazyColumn `contentType`（P2-8）
19. 日期格式化统一（P2-19）
20. `runBlocking` 改异步（P2-9）

### 第 4 批：工程卫生（一天）
21. 删 19 条未用依赖 + `sqldelight` 插件
22. JNA 换依赖坐标
23. CI GUI 白名单修正或合并 job
24. 补 CHANGELOG 1.0.3
25. `SyncManager` 决策（接线或删除）
26. 引入 detekt 或 ktlint

### 第 5 批：结构性（需专门排期）
27. `withoutFocusRing` 的 51 处改造——保留 focusable + 自定义 focus 指示器
28. 播放器单线程所有权重构（`AUDIT-2026-10.md` 的第一层架构演进）
29. mpv 事件驱动替代轮询
30. FTS5 全文搜索 + 分页

---

## 附：旧审计已确认修复的项（不必再查）

| 旧编号 | 内容 | 验证方式 |
|---|---|---|
| P0-1 | WAL / busy_timeout / user_version / 单实例锁 | `AppDatabase.kt:127-128`、`Main.kt:37` |
| P0-2 | mpv `release()` use-after-free | 提交 `3f66bac` |
| P1-2 | `stop()` 不设 `isPlaying=false` | `MediaPlayerState.kt` stop() 已补 |
| P1-3 | 单条删除无确认 | 提交 `ef53c4c` |
| P1-6 | `playFromQueue` 本地文件回退 | `MediaPlayerState.kt:264` |
| P1-7 | 业务索引 | `AppDatabase.kt` 5 个索引 |
| P1-8 | schema 版本 + 迁移框架 | `AppDatabase.kt:139-177` |
| P1-9 | `unsubscribe` 事务 | `SubscriptionManager.kt:41` |
| P1-10 | Settings 原子写 | `Settings.kt:101-110` |
| P1-13 | 下载 HTTP 状态 + Content-Type 校验 | `DownloadManager.kt:205-233` |
| P1-15 | jlink 瘦身 | `build.gradle.kts:112-131` 显式模块列表 |
| P2 | `items()` 缺 key | `App.kt:1731`、`:2242` 已补 |
| P2 | 队列 Column → LazyColumn | `PlayerUI.kt:1352` |
| P2 | `player_speed` / `discover_featured` 缺 key | `Strings.kt:103/153/342/402` |
| P2 | `TextMuted` 对比度 | `AppleLightPalette.kt:46` = `#6E6E73` |
| P2 | 时间格式化重复 4 份 | 提交 `df8288c`（时长部分已统一，日期仍 3 份） |
| P2 | 搜索框重复 4 份 | 提交 `df8288c` → `PodaraSearchField.kt` |
| P3 | `episode_progress` 表 | `AppDatabase.kt:337`，已接线 |
| P3 | 通知 / 自动下载接线 | `SubscriptionManager.kt:80-96` |

> 说明：`withoutFocusRing` 从旧审计的 51 处**没有减少**（我数了仍是 51 处 `clickableWithoutIndicationOrFocusRing` 调用），只是键盘快捷键这条路开始修了。
