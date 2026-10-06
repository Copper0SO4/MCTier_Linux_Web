# MCTier Linux Web：维护者接手说明

> **当前产品决定（2026-10-06）：** Magic DNS 已全面移除 Linux Web UI、API 和 hosts 特权助手；下面旧章节为历史记录，不应据此恢复功能。每个远端成员的本地音量滑条直接显示在左侧成员信息下方；启动自动组网已按用户新要求改为可选功能，默认关闭。

> **上次维护：** 2026-10-06，准备 Linux Web 3.9.0 正式发行。基于官方稳定 v3.10.0（d6af338），纳入本轮成员音量/桌面布局、设置分类、高级网络、Magic DNS 移除与启动自动组网。用户反馈当前开发版功能大概正常；发行构建与发布结果见文末。历史测试结果不等同本轮新增功能覆盖。

## 项目目的

在保留 MCTier 原有组网、房间、聊天和 WebRTC 协议的前提下，为 Linux 提供独立的本地 Web 服务和浏览器界面。用户自己启动服务，再手动访问 `http://127.0.0.1:14700`。服务不会自行启动；源码启动入口不开浏览器，发行包启动器在服务就绪后尝试打开默认浏览器。实现不依赖 Linux Tauri 窗口或 WebKitGTK 媒体管线。Linux 当前仍是实验性项目；目标主要是 Debian 系 x86_64。

官方平台说明：[MCTier 平台支持](https://mctier.pmhs.top/#platforms)（官网内容整理自 3.8.0 系列，最近标注更新于 2026-09-30）。官网已验证 Linux 组网，语音、屏幕共享和远程协作受 WebKit/WebRTC 限制。浏览器版是独立实验方案，不能据此声称官网限制已解决。Windows 和 Android 的实现应保持原样。

## 当前代码和分支

- 工作树在 MCTier 仓库内；当前维护分支为 `master`，跟踪远端 `origin/master`；新 Agent 接手时应重新核对本地提交与远端状态。`origin` 是用户仓库 `Copper0SO4/MCTier_Linux_Web`；`upstream` 配置为官方 `https://github.com/pmh1314520/MCTier.git`。
- 已知宿主源码曾同步到官方提交 `6c73b564`（MCTier 3.9.0）；Linux 适配最初以 `dbb7bbd8`（3.8.0）为基础，后来重套补丁并复核。2026-10-04 已从官方重新 fetch `upstream/master` 和最新非预发行 `v3.9.5`；当时二者均为 `5efda418ba4f5a10a1f2e3dcc0c224a1b7a0ddb4`。当前已更新到 3.10.0，详见顶部和本轮审查，旧版本分支不作为最新稳定依据。3.9.5 的本地合并提交为 `65750e8`；3.10.0 的合并提交为 `41d6904`。昨天的 Linux Web 未提交改动全部保留。以后仍须先 fetch 再审查差异，不把此记录当作未来最新版本。
- Linux Web 实现和 Linux 专属补丁位于 `MCTier-Linux-Web/`。共享宿主 `src/`、`src-tauri/` 属于 MCTier 原有代码；Linux 适配不得覆盖或直接改坏 Windows/Android 路径。
- 根目录 `README.md` 面向 GitHub 仓库首页；本文件面向维护者接手。`MIGRATION-AUDIT-2026-10-03.md` 只作历史背景。

## 架构及安全边界

### 两种网络必须分开判断

- **MCTier 信令服务**（默认来源配置为 `wss://mctier.pmhs.top/signaling`）处理大厅成员、控制消息以及 WebRTC 信令。
- **EasyTier 节点**负责虚拟组网和虚拟 IP 间的数据链路。
- 信令大厅显示成员在线，不代表 EasyTier 接口、对端数据收发或语音/屏幕媒体已成功。连接失败时不可静默改用另一信令地址或 EasyTier 节点。配置和协议字段以当前共享源码为准；仓库不包含官方信令服务端实现，服务器行为需实际核实。

### 本地服务

- Rust/Axum 静态站点和 API 默认只绑定 `127.0.0.1:14700`，拒绝 root 身份启动。源码运行入口不拉起浏览器，浏览器由用户手动打开。
- 服务校验 Host、同源 Origin、Fetch-Site、CSRF nonce 及浏览器 owner lease；本地 API 不提供任意路径读写或通用 URL 代理。新增端点必须保持这些边界，不能将监听地址扩为局域网/公网。
- 发行包启动器行为可能与源码运行入口不同：发行版可以在服务就绪后打开默认浏览器。修改启动流程时须在根 README 和发行说明中保持一致；默认不自动加入；用户明确开启启动组网配置后才启动 EasyTier 并由网页接续，详见文末。

### EasyTier 权限

- 使用 `fetch-binaries.sh` 下载并核验随包 EasyTier v2.5.0；二进制不提交 Git。正常情况 MCTier 和 EasyTier 均以当前普通用户运行。
- 只有确认 core 缺少 TUN 所需 capability 后，才可由用户通过 `pkexec setcap cap_net_admin,cap_net_raw+ep <core路径>` 单独授权，并用 `getcap` 复核。绝不通过 pkexec/root 启动整个应用或 EasyTier；不代输密码、不改 PAM/系统认证配置。

## 功能矩阵

界面实际状态定义在 `web/featureAvailability.ts`。**21 项：17 项实验性接入，4 项封锁。**菜单徽标会另外注明有明确用户实测反馈的功能，不代表完整协议专项测试。

| 状态 | 功能 |
| --- | --- |
| 实验性 | 大厅与虚拟组网入口、文字聊天/附件下载、麦克风与变声、屏幕共享/观看、文件夹共享、发送文件、图片/表情、语音消息、房间工具、房主管理、二维码/邀请、常用/最近大厅与公开广场、主题切换（语言切换未开放）、个人资料/头像/本次会话统计、高级网络/游戏增强、预览式防火墙修复（UFW 有用户 P2P 反馈，其它专项仍待验收） |
| 封锁 | Firefox 大厅连接、远程操控、系统音频/屏幕录制、托盘/快捷键/悬浮窗、自动加入/自动更新 |

房主管理接入了公告、人数限制、公开发布/撤销和移出成员；现已接入原版房主转让和语音禁言/解除接口；禁言仅限制麦克风，不限制聊天。封锁牌是产品提示，不是安全授权机制；服务端仍须独立验证每项敏感操作。远程输入操控按用户产品决定明确不支持，不列为待实现功能。

### 已接入功能及明确限制

- **聊天**：沿用原版签名/加密与 EasyTier 聊天传输，文字、文件、图片、566 项 v3 动画表情、语音消息可用；大文件作为附件传输。上传上限为每文件 64 MiB、每会话最多 128 项/总计 256 MiB。图片 PNG/JPEG/GIF/WebP 不超过 2 MiB 可用图片消息，较大图片按附件处理。语音消息 0.5–60 秒且不超过 2 MiB。自定义表情分类/收藏未接入。
- **文件夹共享**：浏览器由用户选择目录并上传为私有临时快照，不会持续监视本机目录变化。通过原版文件服务端口 `14539` 在 EasyTier 虚拟 IP 上提供访问；本地控制服务仍只监听 loopback。上限 16 个快照、1024 个文件、总计 256 MiB、单文件 64 MiB；不保留空目录，拒绝路径穿越和符号链接。正常退出及租约过期会清理；崩溃或强杀可能留下权限受限的临时目录。没有批量 ZIP 和断点续传 UI。
- **房间工具**：掷骰子、倒计时、协同待办沿用原版消息格式。工具分为掷骰子、本地倒计时、协同待办三个横向标签页，支持方向键切换；待办是后写覆盖语义，不保证并发合并或离线重放。游戏快连另在网络/游戏面板；录屏和 HUD 未开放。
- **公开广场/邀请**：查询、公开大厅发布、原版 v3 邀请、二维码生成/保存和文字导入已接入。导入只填表单，不会自动加入。摄像头扫描未开放；二维码图片读取依赖浏览器 `BarcodeDetector`。广场数据和发布结果仍要按真实信令端验收。
- **房主管理**：沿用上游 `set-lobby-options` 实现人数上限（0 为不限，界面限制 0–64）及公开发布/撤销；信令确认事件同步状态。公告沿用上游 `announce` 的 EasyTier 聊天控制消息，接收端用上游房主 ID 过滤，房主本地显示送达计数，成员加入后补发。移出成员沿用 `kick-player`。房主身份变化时按钮禁用；新功能尚未进行跨端真实验收，不能以本地状态代替信令与对端确认。
- **主题**：设置页支持浅色、深色、跟随系统。复用上游 `theme/themePreference.ts` 的偏好值、存储键和事件；监听系统颜色变化和其他标签页的本地存储变化。浏览器禁用存储时仍可临时选择，并提示无法保存。浅色使用原版中性色及绿色强调色；语言仍是中文。
- **屏幕**：复用原版 WebRTC 屏幕信令；当前只采集视频，不含系统音频。Linux Web 明确不支持远程输入操控。

## 浏览器与 WebRTC 未解决问题

Firefox 曾出现信令 WebSocket 1006 连续断开；一次诊断为单次存活约 1.4 秒、收包间隔 0、待处理 8、无待收心跳。干净 Firefox 配置并关闭 AdBlock 后仍曾复现。Chrome/Chromium 曾在用户测试中工作较好，但这不能证明 Firefox 根因已解决，也不能证明 WebRTC 已全面修复。Firefox 大厅入口目前明确封锁。

用户曾报告：组网、消息发送、远程桌面和麦克风收发正常；随后又出现消息延迟/部分缺失、语音无法建立或连接后反复重连等现象。用户曾报告手机能看电脑画面、电脑看手机方向未正常；最新一轮用户反馈双向屏幕和双向麦克风正常，早期方向故障不应继续作为当前验收结论。后续修复不可只根据一次短暂成功宣称稳定。

验证媒体链路时分别检查：浏览器麦克风权限、信令收发、offer/answer SDP、ICE gathering/selected pair、双向音频 RTP/播放、屏幕双向、断线重连和退出后的轨道/PeerConnection/计时器清理。必须记录浏览器及版本、对端是 Windows 还是 Android、网络类型与时间。Firefox 更换为 Chromium 只是一条测试路径，不是问题修复结论。

## 源码地图

| 路径 | 职责 |
| --- | --- |
| `MCTier-Linux-Web/web/main.ts` | 页面与大厅生命周期、功能事件入口 |
| `web/theme.ts`、`web/theme.css` | 原版主题偏好适配与浏览器浅色样式覆盖 |
| `web/shell.ts`、`web/featureAvailability.ts` | 页面导航、能力提示/封锁状态 |
| `web/community.ts`、`web/lobbyData.ts` | 房间工具、公开广场、收藏/最近大厅 |
| `web/networkPanel.ts` | 高级参数（下次加入生效）、游戏快连、防火墙预览确认 UI |
| `server/src/network_settings.rs` | 有界类型到原版 EasyTierAdvancedConfig 的映射与校验 |
| `server/src/network_operations.rs`、`firewall.rs` | 两分钟一次性预览、系统操作串行化、固定防火墙助手与撤销记录 |
| `scripts/prepare-network.mjs` | 从当前上游生成纯 Minecraft 发现模块，仅去除 Tauri command 注解 |
| `web/chatComposer.ts` | 附件、图片/表情、语音消息输入 |
| `MCTier-Linux-Web/server/src/` | Axum 服务、loopback API、目录快照/本地转发 |
| `server/shared/` | 从上游抽出的共享 Rust 协议/服务逻辑 |
| `patches/frontend/` | 针对上游共享前端的 Linux 小补丁 |
| `scripts/prepare-frontend.mjs` | 将宿主 `src/` 复制到生成目录后应用补丁，不改宿主文件 |
| `scripts/prepare-secrets.mjs` | 依据上游实现生成 secret store 适配模块，并检查上游标记 |
| `scripts/fetch-binaries.sh` | 下载并核验 EasyTier 资源，不授予 capability |
| `scripts/sync-upstream.sh` | 官方上游 fetch/差异审查；可选安全合并 |

`frontend-src/`、`web-dist/`、`server/target/`、`resources/binaries/` 是生成/下载内容，不要提交。更新上游后重新生成前端并审查所有补丁、共享 Rust 模块、配置默认值、信令/文件协议和加密边界；补丁冲突要按新上游逐项对齐，不要强行套用旧生成文件。

## 构建、启动与自动化验证

从宿主仓库根目录执行。目标是 Debian/Ubuntu 系 x86_64；其他发行版没有认证。开发机需要 Node.js 20+、npm、Rust stable、C 工具链及 OpenSSL/D-Bus 开发头文件（Debian 常见包：`build-essential pkg-config libssl-dev libdbus-1-dev`），以及 `curl`、`unzip`、`sha256sum`/`shasum` 用于获取核验 EasyTier。运行时 capability 检查通常需要 `libcap2-bin`；仅在 capability 缺失且用户确认时需要 `pkexec`。

```bash
npm ci
./MCTier-Linux-Web/scripts/fetch-binaries.sh
./MCTier-Linux-Web/scripts/build-web-server.sh
./MCTier-Linux-Web/scripts/run-web-server.sh
```

运行入口保持终端前台运行，用户再手动浏览到 `http://127.0.0.1:14700`。缺少可执行文件时会先构建；不自动启动浏览器。调试版本：

```bash
./MCTier-Linux-Web/scripts/build-web-server.sh --debug
./MCTier-Linux-Web/scripts/run-web-server.sh --debug
```

自动化命令为 `./MCTier-Linux-Web/scripts/test.sh`，包含前端准备、Node 测试和离线 Cargo 测试。也可以运行 TypeScript 检查：`npx tsc --project MCTier-Linux-Web/tsconfig.web.json`。此前维护中，前端 10 个测试文件与 Rust 92 项测试通过；不能自动算作当前改动的结果。正式 release 包以 v3.5.0 参数完成构建，ZIP SHA-256 为 `e4662a2b8f975314de6295c2e4a203e12264ca4f63f1e52b2a0a6ac7ab9f45ee`；该包不包含最新房主管理和桌面布局改动。归档检查确认启动器、服务、EasyTier core/CLI 及中文依赖说明齐全；没有在干净 Debian 系统实测安装依赖后的首次启动。

## 当前验收状态

### 当前未发行改动（房主管理与桌面布局）

- 依据上游 Windows HostPanel.tsx、WebRTCClient.ts 和 P2PChatService.ts：Linux Web 房主面板复用 set-lobby-options 与 announce；人数上限、公开状态由信令回报更新，公告由 EasyTier 聊天控制消息发送并在新成员加入后补发。与原版差异（该阶段）：Linux Web 只提供 0–64 的人数输入，当时未提供房主转让/成员禁言（2026-10-05 后续已接入）；公告直接显示在浏览器大厅侧栏，发送状态展示本地服务的对端送达数。
- CSS 将桌面大厅最大宽度由 1180 提至 1920 像素，侧栏高度适配视口并独立滚动，聊天室填充可用高度；900 像素以下保留纵向布局。没有改变组网、信令、媒体协议或 Windows/Android 源码。
- 用户随后反馈房主管理功能可用，但右侧聊天室/诊断等内容被裁剪，工具弹窗密集不协调。进一步将右侧容器改为纵向滚动，诊断及屏幕面板由内容决定高度；弹窗改为固定标题栏、独立内容滚动，掷骰子/倒计时/待办分区排列，并补充浅色主题样式。此轮只改 Linux Web 的页面结构和 CSS，不改原版协议。
- 本轮源码构建脚本完整运行成功（TypeScript、Vite、release Rust 服务）。在不连接房间的静态浏览器预览中，右侧诊断内容滚动到最后一项（scrollTop 达到最大值），工具弹窗内部高度 615px、内容高度 769px，滚动可用，视觉上三类工具分区清楚。当前服务查询无大厅会话后已重启应用新构建；真实 Chrome 房间页面仍待用户复测。没有运行自动化测试或新的跨端联机。下次发行使用不带 v 的版本号，不改写已发布的 v3.5.0。

### 有记录的自动化结果

- 本轮前端 10 个测试文件及 Rust 92 项测试通过；新增操作按钮必须位于功能卡片内的回归断言，以及全部 566 项表情嵌入/GIF 响应测试。
- TypeScript 检查和本地 debug 构建通过。
- 本轮使用 Chromium 内核的应用内浏览器在独立 loopback 静态模拟页检查 1440、900、390 像素宽度：无页面横向溢出，侧栏按钮在卡片内，房间工具可打开，390 像素弹窗无内部横向溢出，代表性动画表情加载成功。该预览不连接真实信令/节点、不采集媒体。

### 用户真实环境反馈

- 用户确认：组网、发送消息、远程桌面、麦克风收发在某次测试中正常。
- 用户确认：文件、图片/表情和语音消息新增功能验收正常。
- 2026-10-03 最新用户反馈：组网、消息、文件收发、麦克风和屏幕共享双向、大厅邀请码正常；截图记录侧栏操作按钮重叠和内置动画表情加载失败，本轮予以修复，修复后真实页面待用户复测。
- 这些反馈未逐项覆盖跨端传输限制、持续稳定性、Firefox、房间工具、目录共享、公开广场全部操作、二维码扫描、ICE/SDP 明细和 WebRTC 重连；不能把它们标为全矩阵通过。

### 尚缺真实测试

至少分别记录 Chrome/Chromium 手动访问与 loopback 监听、EasyTier 虚拟接口和对端收发、大厅/聊天、文件夹快照访问、Firefox 与 Windows/Android 的双向语音/ICE/断线重连/资源清理，以及屏幕互看。房主管理还须由一个 Windows/Android 对端核验人数限制、公告新成员补发、公开状态和成员移出。远程输入操控明确不支持；系统音频、桌面录屏等仍受限。真实加入房间、触发系统授权或操作真实目录之前，先具体告知用户操作与目标，等待确认；没有确认则只做静态/自动化工作。

## 上游维护与协作约定

1. 每次接手先查看 `git status`、分支和 remotes，读本文件，再查看当前源码；保留现有用户更改。不得用 `reset --hard`、`clean`、覆盖式 checkout 或删除工作目录来“恢复干净状态”。
2. 同步官方源码前先保证清晰了解工作区状态。`./MCTier-Linux-Web/scripts/sync-upstream.sh` 默认只 fetch 并展示变化；审核之后才考虑 `--merge`。脚本在脏工作区拒绝合并；冲突由维护者处理，不自动 stash/reset/clean。
3. 保持差异尽量局限在 `MCTier-Linux-Web/` 和面向 GitHub 的根 `README.md`。Linux 适配复用共享实现，不改 Windows/Android 行为；确需共享代码更改时必须说明兼容性依据。
4. **每次维护同步更新 `HANDOFF.md`**；用户可见行为或构建说明变化时，也更新本目录和根目录 `README.md`。每步记录改动依据、与原版差异和验证结果。自动化测试、构建、静态审查和真实环境测试要分开报告；没有测的功能明确写“未测/待验收”。
5. 用户曾授权在自己的仓库分支上提交并推送已验收变更；目标为 `origin` 用户仓库，不能推送官方 `upstream`。未经明确要求不要发布新发行版；不要 force push。
6. 信令行为、服务器返回和平台兼容性以源码/抓包/复现实测为证据。出现断连时保留诊断数据，分辨信令在线、EasyTier 数据链路与媒体连接，不自动换服务器或节点。


## 最近一次维护：主题切换

选择主题切换作为无需系统权限、无需改动房间协议的补全功能。新增 `web/theme.ts` 和 `web/theme.css`，设置页提供跟随系统/浅色/深色；状态从封锁改为实验性，并明确语言切换仍未接入。复用原版偏好逻辑，对上游 CSS 的色板作浏览器专属覆盖，避免加载原版 App 的全局选择/重置样式。其他上游源码未修改。

新增本目录 `AGENTS.md` 将同步维护文档设为明确规则。后续验收重点：用户实际 Chrome 设置页主题切换、刷新恢复，以及大厅、目录共享和工具面板的浅色可读性；真实联机仍须先取得用户确认。本轮未构建或发布发行压缩包。

## 最近一次维护：按钮布局与表情资源

依据用户截图定位两项直接原因：功能卡片的 `height: 100%` 和卡片外追加的操作按钮使按钮越界重叠；单独 Vite 重建清空产物后，旧运行二进制没有嵌入表情资源。操作按钮现放入卡片，卡片按内容高度排列，侧栏不再压缩子元素；聊天消息区使用有界独立滚动，发送栏采用响应式网格，900 像素及以下采用单列并保留功能入口，弹窗控件换行。保持原版 CSS/协议复用，未修改 Windows/Android 或信令、EasyTier、WebRTC 协议。

Linux Web Vite 构建钩子现在每次准备并校验原版 566 项 GIF，构建脚本移除重复准备步骤；Rust 嵌入构建会拒绝没有 GIF 的产物。资源异常时聊天与选择器显示文字提示，避免仅有破图。完整 debug 构建和单独 Vite 构建均已验证；单独构建后 GIF 数量为 566。前端 10 个测试文件、Rust 92 项通过，静态浏览器验证见上文。用户确认后已重启 debug 服务，页面与代表 GIF 均 HTTP 200，GIF MIME/文件签名正确，只监听 `127.0.0.1:14700`，未自动加入房间。未构建发行压缩包；真实目录共享、房间工具同步、Firefox 断连和媒体稳定性专项仍待验收。

## 最近一次维护：正式版菜单、界面和 v3.5.0 包

按用户验收反馈，功能菜单将组网/大厅、聊天、文件发送、双向麦克风、双向屏幕和邀请显示“已接入 · 用户已验收”；每项说明仍标出未验证的 ICE/重连或浏览器限制。文件夹共享、房间工具、图片/表情等保持“已接入 · 实验性”，未实现项目保留封锁牌。功能本身与上游协议逻辑未变。页面增加卡片层级、留白、悬停/焦点状态、减少动态效果偏好和窄屏适配。

根 `README.md` 已改为 Linux Web 下载/依赖/源码构建说明，子 README 与 `README-Linux.txt` 同步；包内中文文件说明 Debian/Ubuntu 运行库、浏览器、D-Bus/密钥环、TUN、浏览器启动与 capability 授权边界。`package-release.sh` 接受 `v` 前缀，生成 `mctier-linux-web-linux-x86_64-v3.5.0.zip` 及 SHA-256。ZIP 构建和内容校验通过。首次上传时发现校验清单含构建机绝对路径，随后已改为相对文件名并在 Release 附件中替换，下载后可直接运行 `sha256sum -c`。

用户真实验收范围来自本轮回复，不把自动化结果或尚未测试的目录共享、房间工具同步、Firefox、ICE/断线重连记作通过。上游历史里也有 `v3.5.0` 标签；已只读查询用户 `origin` 未发现 `v3.5.0` 标签；本地标签来自上游仓库的旧源码历史。可将本次分支 HEAD 作为用户 fork 独立仓库中的 `v3.5.0` 标签，不触碰上游。发行说明保存于 `RELEASE-v3.5.0.md`。

## 本轮：高级网络、Magic DNS、防火墙与工具标签页

上一轮右侧滚动和工具弹窗已获用户验收；本轮新功能未作真实联机/授权验收，不沿用此前结论。全部新增实现位于 Linux Web 目录，宿主 Windows/Android 源码未改。

- **高级网络**：复用现有 `EasyTierAdvancedConfig` 与 `network_arguments::apply_advanced_config`，保留 AES-256-GCM、TUN、固定虚拟网段、指定节点/信令和回环 RPC。配置在当前浏览器保存，下次手动加入时提交给服务校验；重试地址冲突仍走原版 `lobby_address`。支持 MTU、多线程/延迟优先、压缩、绑定设备、P2P/中继与打洞开关、KCP/QUIC、IPv6、私有子网共享、使用出口节点、回环端口转发。QUIC 启用需固定独立端口；不支持提供出口、SOCKS5、无 TUN 或禁用加密。子网只允许 RFC1918 且不能覆盖本应用网段；转发目标限虚拟 IP，绑定限 127.0.0.1，最多 16 条。清除继承的 ET_* 环境覆盖，避免地址受外部环境静默替换。
- **游戏增强**：端口模板对齐原版 GameQuickConnect；不启动游戏、不修改游戏进程。Minecraft Java 状态查询代码从当前上游生成，仅查当前已同步成员的虚拟 IP，单次查询并发受限，退出/会话变化丢弃结果。随机 LAN 端口需用户指定；LAN 广播桥、基岩版发现尚未接入。KCP/QUIC 属于 EasyTier 数据通道，不保证提升浏览器媒体。
- **Magic DNS**：直接编译原版 HostsManager::domain_for_identity、hosts_security、unix_hosts_helper。域名为身份前 32 位加 `.mct.net`；只使用原版身份及虚拟地址，不使用昵称派生可注入的域名。预览展示映射；确认后通过匿名管道调用同一可执行文件的固定 hosts 助手。root 助手只运行一次性写入分支，不启动服务/核心。保持其它 hosts 字节与 MCTier 段，限定 LinuxWeb 标记；检测其它记录冲突、预览/授权前 hosts 变化，写后复核。与原版成员事件自动更新的差异是**手动批量更新/清理**，不在成员进出或退房时自动弹窗。退房/停服务不自动清理，用户应在面板清理残留段。没有接管系统 DNS、没有启用 EasyTier accept-dns。
- **防火墙**：只运行系统所有、不可被普通用户写入的固定 ufw/firewall-cmd 路径；不经 shell，不接受任意命令。UI 选择正在使用的后端；按当前实际 UDP 或 WS/TCP 监听端口和配置的 QUIC 端口生成预览，加入虚拟网段到本机虚拟 IP 的入站规则（ufw 限 MCTier_Net）。firewalld 使用用户确认的物理入站区域和查到的虚拟接口区域，不改变区域。只在确认后 pkexec 授权；不启用/关闭/重置防火墙，不放行回环管理服务、不切换节点。UFW 规则带随机注释，保留管理员已有规则/注释，冲突 deny 拒绝覆盖；撤销只删除本次标记规则。firewalld rich rules 使用实例派生优先级并设置 3600 秒运行时有效期，不写 permanent。逐条复核，部分失败明确报告并保留撤销入口。
- **打洞限制**：常规状态跟踪、防火墙监听规则与 NAT/路由器不是同一层。对称 NAT 使用动态 UDP；额外放行 `/proc/sys/net/ipv4/ip_local_port_range` 必须独立勾选，再确认具体范围，会影响范围内其它程序。没有固定的万能端口列表，不承诺修复 NAT/运营商/Firefox。未配置固定 QUIC 时，其动态入站代理端口未逐项枚举；默认窄规则不能覆盖所有打洞情形。WebRTC 的 ICE/TURN 不是 EasyTier 监听端口。
- **操作边界**：`prepare_*` 生成单个两分钟预览，`apply_network_operation` 仅接受一次性 token，继承同源/CSRF/页面所有权；系统授权串行进行。授权由用户处理，不代输密码或更改认证。规则记录在用户数据根目录 `linux-web/firewall-rules.json`，用于退出/服务重启后撤销；权限 0600，拒绝符号链接写入。UFW 规则持久化，需手动撤销；firewalld 规则超时后可清理记录。

### 依据与后续验证

核对了随包 EasyTier `--help` 与原版高级参数、HostsManager、GameQuickConnect、Minecraft discovery。防火墙语法依据 [UFW 手册](https://manpages.ubuntu.com/manpages/focal/man8/ufw.8.html)、[firewall-cmd 手册](https://firewalld.org/documentation/man-pages/firewall-cmd.html)；NAT 限制参考 [EasyTier P2P 优化](https://easytier.rs/en/guide/network/p2p-optimize)。还核对本机 UFW parser/backend：添加仅注释/动作不同的相同条件规则可能替换旧规则，故新增前必须保留/拒绝对应管理员规则。

开发验证：TypeScript、Vite（含 566 项表情嵌入）及最新 debug Rust 可执行文件完整构建通过；前端 11 个测试文件和 Rust 107 项测试通过，git diff --check 无错误。测试覆盖参数/保留端口/私有 CIDR 校验、原版参数映射、系统预览/一次性 token/CSRF 边界、hosts 保留与冲突、UFW 既有规则所有权、firewalld 参数/超时生成，以及三个房间工具标签页切换。静态模拟页面检查了高级网络的四个入口和桌面面板布局：弹窗宽 1018px、无横向溢出，内部区域 615px、内容 1408px，可独立滚动；没有提交任何系统授权。真实测试仍需用户确认后分别验证：默认/修改参数的虚拟接口与游戏数据收发、域名 getent 解析/对端访问、授权取消/成功/部分失败、ufw/firewalld 实际规则和撤销、实际 P2P 路由及 NAT 类型。不可把计划生成或配置复核记为真实网络通过。

### 今日收尾 / 明早继续

用户要求今天停止推进。最新 debug 可执行文件已构建；14700 正式服务原本已停止，未启动。14701 临时静态预览及浏览器临时标签已关闭；未真实连接房间、未调用 pkexec、未写 hosts/防火墙。未 commit/push，也未创建新发行版，保留用户之前已验收的未提交变更。

明早从当前工作区继续，不重新克隆/覆盖。先确认用户愿意启动服务，再让用户测试工具三标签、高级参数保存/下次加入、游戏地址与发现。进行系统操作前逐项说明并确认具体 hosts 映射或 ufw/firewalld 端口/区域；优先测试授权取消、成功、撤销、已有管理员规则保护、部分失败后的恢复，以及服务停止/授权窗口仍打开时的生命周期。昨晚版本的助手等待认证期间不自动取消；该问题已在下方 2026-10-04 收尾中改进，实际桌面认证代理行为仍待验收。最后验证 getent 域名解析、虚拟接口/游戏数据及 EasyTier 直连/中继状态，再考虑正式构建、提交与发行。Firefox 1006、LAN 广播桥等原有边界仍在。

## 2026-10-04 官方稳定源码同步

- 官方分支清单中只有 master 与依赖机器人分支，没有名为 stable 的分支；GitHub latest release 为非预发行 [v3.9.5](https://github.com/pmh1314520/MCTier/releases/tag/v3.9.5)，tag 与 master 均指向 `5efda41`。因此采用这一稳定提交，而非依赖机器人分支。未改写用户发行线的本地标签，也未修改 origin/upstream 地址。
- 原工作区有 26 个修改/新增文件。采用临时独立工作区从旧 HEAD 合并上游，唯一冲突是仓库首页 README；保留 Linux Web 首页版本。完成合并后，当前分支仅快进到合并提交 `65750e8`，昨天的改动未加入提交。合并前后逐文件 SHA-256 核对，26 项内容全部不变；没有自动 stash、reset、clean 或覆盖用户工作区。临时工作区合并提交已在当前分支可达，随后移除临时工作区。
- 上游主要变化见 `CHANGELOG-3.9.5.md`：Android 公告补读及音频路由修复、桌面音频批量读取/短缓冲、公告与公开广场布局、Windows/Android 签名工具、移除原版防火墙检测/放行。Linux Web 的独立 firewall.rs 是用户要求的产品差异，仍保留预览/确认边界；上游删除原版功能不构成 Linux 修复已验收的证据。原版音频短缓冲针对原生采集管线，不能据此声称浏览器语音或 Firefox 故障已修复。
- `git diff upstream/master HEAD -- src src-tauri MCTier-Android` 为空，共享 Windows/Android 源码和本轮官方稳定版一致；未另行修改它们。Windows/Android 构建及真实测试本轮未执行。
- 更新后执行 `build-web-server.sh --debug` 成功：上游前端叠加补丁准备、secret/Minecraft 生成、TypeScript、Vite（566 项 GIF）、Rust debug 服务完整通过。`git diff --check` 通过；本轮未重跑自动化套件，上一轮 11 个前端测试文件/107 个 Rust 测试的结论不自动视为新基线测试通过。服务未启动，未连接房间或授权，未 push/release。接下来仍按上节验收清单检查昨日新增网络功能。

## 2026-10-04 网络功能收尾与提交

用户要求完善昨晚变更及上游更新后提交到自己的 origin；本轮不发版、不启动服务。同步基线仍为官方 v3.9.5 / `5efda41`，共享 Windows/Android 源码无适配改动。

- 新增 Linux 专属 `privileged.rs`：pkexec 认证后，固定 root 助手先输出就绪标记；父进程重新核对成员身份/hosts 快照或当前核心监听端口与虚拟 IP，再发送 JSON。认证期间匿名管道保持空白，取消/退房/服务正常关闭时关闭管道，助手无法取得有效请求，避免迟到授权应用过时房间配置。hosts 写入仍由当前上游 `unix_hosts_helper` 的锁定文件、摘要校验和内容校验执行；未修改上游文件。
- `cancel_network_operation` 继承 invoke 的页面所有权、同源和 CSRF 约束。关闭面板或取消按钮通知服务取消；退出大厅、租约失效及正常停服取消预览/等待。等待认证最多 180 秒，提交后结果最多 30 秒。不承诺关闭桌面代理窗口，也不承诺杀死已获得 root 权限的进程。**请求已提交后可能部分应用，取消/超时明确提示检查和撤销，不宣称回滚**；异常断电也不能保证回滚。
- 防火墙账本改为独占新建 0600 临时文件、写入/同步、原子重命名并同步目录，避免中途截断破坏撤销记录；读取 O_NOFOLLOW、普通文件和大小校验，拒绝符号链接。部分失败继续保留本次计划。
- 保存网络设置新增类型、数组和字段校验，损坏数据不再使面板因 join/map 崩溃；显式提示回到默认值，最终参数范围仍以服务端校验为准。恢复默认时存储异常走可见错误提示。

验证：TypeScript、Vite（566 项表情资源）、Rust debug 完整构建；前端 11 个测试文件、Rust 112 项自动化测试。新增普通用户模拟助手测试覆盖认证等待取消、认证后提交前取消、有效/无效握手；另测账本原子替换/0600/拒绝符号链接，以及浏览器损坏设置和关闭面板的取消通知。这些测试不执行 pkexec，不修改 /etc/hosts 或真实防火墙。未执行真实房间、Minecraft 查询、授权窗口、ufw/firewalld 规则或域名访问；新网络功能继续标记待真实验收。没有新发行包，服务保持关闭。

下一步由用户确认后启动服务：先测试工具三标签和设置保存/下次加入，再逐项确认并测试 DNS 预览/认证取消/应用/清理、防火墙现有规则保护/应用/撤销/部分失败、授权期间退出大厅，最后验证对端游戏流量与真实 P2P/中继状态。Firefox 1006 仍未修复；远程输入明确不支持。

## 2026-10-04 房间内导航及输入确认

用户要求停止服务、修复房间内主页/功能状态层级，并要求网络修复二步输入确认。已对当时本服务 PID 36244 发送 SIGTERM；服务保持关闭，没有启动浏览器、加入房间或调用真实授权。

确认两项 UI 原因：`controlState` 会重复调用 `setSessionState(online)`，原 shell 每次均显示大厅并选中聊天；文档级 Escape 处理未排除打开的 dialog，会导航被遮挡的底层页。修复后相同会话状态只更新控制状态，保留当前页面和聊天/屏幕/诊断选择；标题点击进入主页，连接中的页面有会话提示和“返回大厅/连接”按钮；连接过程中禁止首页另建/加入，退出后恢复。页面切换保存各自文档滚动位置，原有聊天内部滚动 DOM 保留。Escape 遇到模态窗口不执行 shell 返回。退房同时关闭网络面板，使已接入的取消生命周期生效。

Magic DNS、高级网络/游戏增强、限定 ufw/firewalld 修复及三工具横向标签本来已于 `6cd5d7a` 提交；本轮复用这些实现。新增输入确认覆盖所有系统写入/撤销：prepare 返回 action 对应的 confirmationText；输入框默认空，只有完全匹配时启用授权按钮；apply 参数必须有 confirmationText，服务端对当前预览 action 重新校验。错误文字不消耗预览，也不启动助手。确认文字分别为“我确认修改本机防火墙”“我确认撤销本机防火墙规则”“我确认更新本机域名映射”“我确认清理本机域名映射”。不接受泛化的“我确认...”替代具体动作。预览、串行认证、同源/CSRF、固定助手、规则范围、撤销/部分失败和超时边界均延续上一轮。

验证：完整 TypeScript/Vite/Rust debug 构建通过，566 项动画资源已嵌入；12 个前端测试文件、113 项 Rust 测试通过。新增导航测试覆盖重复在线刷新、功能页/首页返回大厅、诊断面板保留、模态 Escape 隔离、退房恢复控件及页面滚动恢复；前端测试确认不完整文字不能发送 apply，Rust 测试确认错误文字不消耗预览，且正确文字后仍继续检查 hosts 快照。测试不调用真实 pkexec，也不写 hosts/防火墙。真实 Chrome 房间导航、桌面认证、DNS/游戏流量和规则应用/撤销待验收。改动局限 Linux Web 与根 README；Windows/Android 共享实现未修改。本轮未提交/推送或发行，供下一轮用户测试。

## 2026-10-04 修复启动旧二进制导致的未接入提示

用户报告防火墙修复/诊断仍显示未接入。源码状态与按钮已经接入，实际原因是此前启动调用未传 --debug，run-web-server.sh 默认优先使用已存在的 release 二进制；当时 release 构建时间为 2026-10-03 20:30，debug 是 2026-10-04 11:05。只读 /proc/50577/exe 确认运行的是旧 release，该进程没有活动 EasyTier 子进程。本轮已终止旧服务并通过源码入口启动新服务；不把过期运行页面当成新源码验收。

源码启动入口改为默认 debug，每次启动都调用构建脚本准备/校验并嵌入当前前端、共享补丁与资源，保证已有可执行文件不会使本次修复被跳过。新增 --release 显式选择优化构建，同样先构建；原 --debug 继续有效，发行包启动器不受影响。新增启动器回归测试使用临时目录和假构建器/假程序，验证默认、--debug、--release 三种情况均替换旧 UI 程序；测试不启动真实服务或联网。测试通过；完整 debug 构建通过。上轮导航/输入确认自动化结果仍为 12 个前端测试文件/113 项 Rust，本轮新增启动器测试单独验证，未重复全套。系统授权和真实房间验收继续待用户操作；未调用 pkexec。当前更改未提交/推送或发行。

## 2026-10-04 整理 UI 与 firewalld 后端提示

用户主要目标为整理杂乱 UI，再统一设置网络。大厅侧栏保留所有操作，隐藏重复的状态徽标和长说明（功能总览仍完整显示），操作按钮通过 title 保留简要说明；侧栏用两列快捷操作加整行广场/网络入口。诊断拆成信令、媒体、EasyTier 和修复卡片；屏幕尚不支持的能力放进折叠说明，去掉“更多功能·暂未开放”的笼统导航措辞。网络面板保留四标签和独立滚动，常用性能参数先显示，协议/打洞、子网/出口、转发及扩展 UDP 范围按需展开，参数和默认安全边界不变。

firewalld 规则代码本已支持，本轮补运行状态和 --get-active-zones 的区域/接口只读结果。安装不等于运行；未知状态显示无法确认，明确未运行或虚拟区域不可确定时禁用预览。两种防火墙均安装时不给默认选择，避免把 ufw 安装状态当成实际使用后端；用户明确选择实际后端及物理入站区域。root 助手仍验证运行状态、区域存在、虚拟接口区域一致、参数及规则复核。无永久配置、自动安装/启用、切换后端或额外权限授权。

完整 debug 构建通过（TypeScript/Vite、566 表情、Rust）；13 个前端测试文件和 114 项 Rust 测试通过，随后新增并单独通过 firewalld UI 的明确选择/接口显示与未运行禁用测试。新增纯解析测试覆盖物理和虚拟区域接口分组，不把 sources 行当接口。未执行真实 firewalld/ufw 修改、pkexec 或房间测试。更新前只读确认旧服务 PID 52420 没有活动核心，再替换为最新 debug 服务，供用户统一设置前查看。界面实际桌面视觉、区域选择、认证/规则增删与游戏数据仍待验收。保持未提交的既有更改，未推送或发行。

## 2026-10-04 P2P 防火墙调查、直接撤销及退房选择

用户确认动态 UDP 选项已勾选，仍 relay；禁用 UFW 后 P2P。完整证据、假设及未知项见 FIREWALL-AUDIT-2026-10-04.md。只读查了随包 v2.5.0 精确 tag 的 TCP/UDP 打洞源码、UFW before/user 规则及内核丢包摘要。确认原扩展漏 TCP；INVALID 提前丢包只是待验证假设，用户这次故障根因尚未闭环。没有静默变更节点、信令、核心或路由。

Plan 增加默认 ephemeralTcp / pause 字段，兼容老账本。新增 firewall_pause.rs 固定 root 辅助分支：UFW --force disable/enable，firewalld systemctl stop/start firewalld.service。先写 root 所有 0600 持久原状态凭证，再停用，锁、nofollow、目录/文件所有者校验及同后端重入拒绝；恢复原来 inactive 时不擅自启用，缺凭证不猜测。凭证在 /var/lib/mctier-linux-web/firewall-pauses，跨服务重启/系统重启保留；没有后台自动恢复。firewalld stop/start 可能丢失其它 runtime-only 配置，不能承诺重建。该产品变化依据用户本轮明确要求，旧文档“不支持关闭”属于此前限制。

暂停有独立输入确认“我确认暂停整个防火墙”；规则删除/状态恢复不要求额外文字（仍使用 prepare token 和系统认证）。手动退房先询问账本更改是否撤销，包含旧残留，支持撤销并退出、保留退出和取消；失败保留窗口供处理。强制离开/断网/关闭浏览器等清理流程不被授权提示阻塞，残留由用户下次面板恢复。prepare/apply 继续串行、同源/CSRF/页面所有权。退房仍清理房间资源，权限助手从不以 root 跑核心或服务。

只返回核心 socket 端口号的 proc 诊断受 capability 权限限制时明确提示；没有暴露任意路径和地址。构建替换运行中 executable 时，privileged.rs 不启动无效路径的授权窗口，而提示重启。

自动化：13 个前端测试文件、119 项 Rust 测试通过。新增覆盖 UDP/TCP 规则区分与旧账本兼容、socket inode 过滤、暂停/重复暂停/原 inactive 恢复与凭证符号链接拒绝、直接撤销、退房保留/取消/逐项恢复。真实防火墙变更全部未执行；P2P 仍待用户按调查文档的同条件比较验收。维护文档和中文包说明同步，现有未提交改动保留，未提交/推送或发行。

收尾复核补测了暂停命令变更后复核失败的情形：原状态凭证保留，随后恢复能还原原启用状态；强制房间清理会关闭尚未完成的退出选择窗口，避免留下过期提示。完整 debug 构建与 git diff --check 通过。现有服务没有活动核心，更新到最新 debug 并仅在回环提供页面，未执行系统授权或真实联机。

## 2026-10-04 重新组网与出站修复

### 防火墙变更后自动重新连接（后续更新）

用户家宽验收确认：应用防火墙规则后，EasyTier 需要重新连接才能转为 P2P。现将成功应用、手动撤销或暂停/恢复防火墙后的流程改为静默重启当前受本服务管理的 EasyTier 核心；继续沿用当前房间身份、节点、虚拟 IP 和监听端口，不重启本地 Web 服务、不重建 MCTier 信令/聊天会话，也不申请额外权限。退出房间时批量撤销规则会跳过重启即将退出的核心。若防火墙变更成功但核心重连失败，会明确报告失败并保留手动“重新组网”入口。自动重连成功只表示核心和虚拟接口恢复，不代表 P2P 已建立；需查看相同对端的 EasyTier 路由状态。实现位于 `server/src/network_operations.rs`，面板提示位于 `web/networkPanel.ts`。

网络修复面板新增 **重新组网**：用户确认后仅重启本服务拥有的 EasyTier 核心，以普通用户运行，沿用当前房间身份、节点、虚拟 IP、主监听/RPC/QUIC 端口和已加入时的配置，不应用尚未重新加入的设置。信令连接、聊天身份和共享快照不主动重建；数据短暂中断，正在传输的文件可能失败，媒体能否自行恢复须实测。重启失败会清理当前房间资源并提示手动重新加入，不切换服务器、节点或端口。系统授权进行中不能重启；已有未提交预览会作废。成功只表示核心存活与虚拟接口就绪，不代表 P2P 成功。

用户反馈：暂停防火墙后重新加入才转为 P2P；常规规则修复仍 relay。v2.5.0 源码确认随机本地 UDP/TCP 端口来自系统分配，对称 NAT 的全范围随机端口是**对端目标端口**，不能据此开放本机全部入站。已有动态范围和 QUIC/虚拟网段规则保留；新增默认关闭的 **额外放行 UFW 出站**，按本地源端口放行到任意对端端口，并允许当前虚拟 IP 经 MCTier_Net 发往大厅网段。动态范围仍须单独勾选；出站扩展 TCP 范围也覆盖节点连接，不仅 TCP 打洞。按端口匹配会影响其它程序，不是按进程放行。同项管理员 deny/reject 规则不会被覆盖；其它更早规则、INVALID、转发策略、NAT 等仍可能拦截。firewalld 出站 policy 未接入，界面禁用该选项，不把区域入站规则当作出站放行。不开放本地 Web/RPC 到外网，不猜测额外端口或修改系统 conntrack 规则。

常规规则生效后，请先点击重新组网，再对照相同对端的 P2P/relay、数据和媒体恢复。若仍 relay，需要同一时段丢包和规则顺序证据；目前不能宣称防火墙根因已解决。

实现定位：runtime.rs 的 restart_network / launch_replacement 复用原 build_command 与 core_readers；invoke 保持 owner/Origin/CSRF 检查，Operations gate 串行隔离系统授权。重启前校验原核心哈希/capability，等待旧子进程退出后才启动替代进程；锁住运行会话使维护任务不会在中途误判退出。启动超时/退出不返回成功。Plan.allowOutgoing 默认 false，旧账本删除命令不变；UFW 删除保留本次唯一标记与逐项复核。UFW 自带 Python 解析器只读验证了源端口规则语法与 show added 的规范输出；未实例化防火墙后端、未执行真实规则。

工具链备注：本机 /usr/bin/rustc、rustfmt 因 LLVM_23.1 符号缺失无法启动。此轮从官方 static.rust-lang.org 稳定 manifest 下载并按 SHA-256 核验组件，在 /tmp/mctier-rust-toolchain/installed 临时安装 Rust 1.99.0；只为本轮检查/构建设置 PATH，未替换系统工具或更改认证配置。后续若系统仍有此问题，需修复本机 Rust/LLVM 安装，临时目录不是项目依赖。

本轮最终验证：TypeScript/Vite/Rust debug 完整构建通过；13 个前端测试文件共 99 项测试、122 项 Rust 测试通过。新增覆盖重新组网取消/确认、出站默认关闭与 firewalld 禁用、管理员出站策略保护/旧账本兼容、无房间/系统操作并发拒绝、替代子进程退出与启动失败检测。未执行真实 EasyTier 重启、真实授权或规则变更；虚拟接口、对端数据、P2P 和媒体恢复待用户测试。原版 Windows/Android 共享源码没有修改；未提交、推送或发行。

服务状态：只读确认旧服务无活动房间后更新到本轮 debug；127.0.0.1:14700 HTTP 200，页面包含重新组网与可选 UFW 出站入口。未自动加入房间或申请系统权限。


### 2026-10-04 独立 EasyTier 实测：MCTier-Codex

用户确认房间没有设置密码，按空 network secret 直接运行随包 EasyTier v2.5.0；完全未启动 MCTier 房间/信令流程，未改防火墙。节点使用本地 bootstrap 默认 `tcp://easytier.weiai.org.cn:11010`，房间标识 `MCTier-Codex`，临时地址 `10.126.126.57/24`，接口名 `mtierp2ptest`。核心普通用户运行，已有 TUN capability；创建接口成功。与 MCTier 参数对齐 `aes-256-gcm` 后，CLI 见 3 个 peer 项（包含本机/引导连接）；一个远端路由长度为 2，下一跳为 TCP peer。对该 overlay 地址发 2 个 ICMP，2/2 回复，证明组网数据经中继可达，**没有证明 P2P**；该路由仍为两跳，没有观察到目标 peer 直连。另一轮未对齐算法的初测出现 DecryptionFailed，不作为网络/密码故障证据。

EasyTier 本次 listener 列表实际为随机 `udp://0.0.0.0:52344` 与 `udp://[::]:58944`，另有 loopback RPC；下一次运行端口会变化，不能把这些数当固定防火墙端口。EasyTier v2.5.0 TCP/UDP hole punch 源码另会 bind `:0` 取动态本地端口。此次未观测到能佐证目标 peer 的成功 UDP/TCP P2P socket；ICMP 路由路径仍为 relay。未切换节点或协议、未停用/修改防火墙。结束时已 Ctrl-C 正常停核心；复核 `mtp2ptest` 接口与 `easytier-core` 进程均已消失，14700 Web 服务仍只监听 `127.0.0.1`。

结论：空密钥可以加入该引导网络并转发 overlay 数据；本次网络条件下 peer 仍 relay。启动 listener 端口和 hole-punch 随机源端口不是同一回事，单纯放行本次两个随机 listener 不保证 P2P。需要保留同一 peer、路由跳数/transport、接口收发和核心 socket 在有效会话内同步采样，再与防火墙暂停状态比较，才能定位具体阻断。


### UFW 前置规则放行测试结果（2026-10-04）

用户要求直接 EasyTier 测试时尝试防火墙放行，直到手机 peer 达到 P2P。只读检查显示 `/etc/ufw/ufw.conf` 为 ENABLED=yes 且 systemd 的 ufw.service active；firewalld inactive。`/etc/ufw/before.rules` 与 before6.rules 在用户规则前配置 `conntrack INVALID` 丢弃；常规 UFW `allow` 规则无法先于 before 链执行，因此若打洞包被标记 INVALID，已加的普通入站放行可能无效。授权前的初检环境无法读取内核实际规则计数（`ufw status` 需 root）；之后已按用户授权通过 pkexec 检查、应用并撤销临时规则，完整结果见下文。

用户授权后实际加入临时高优先级规则：IPv4/IPv6 的 UFW before-input 链最前面，仅匹配 wlo1 入站 UDP 目的端口 32768–60999 且 conntrack=INVALID；UFW 全程 active。运行随包 v2.5.0 核心，MCTier-Codex、空密钥、aes-256-gcm、默认 EasyTier 节点。13:27:13 启动后目标 phone（10.126.126.52）显示 peer 状态 p2p、tunnel=tcp；route 为 DIRECT、path_len=1，延迟约 20 ms，满足一跳直连而非 relay。EasyTier 日志显示对端 192.168.10.108:47926 连接本机 192.168.10.107 的 TCP/11010；两者处于同一 Wi-Fi 局域网，且测试前已有 UFW TCP/11010 放行。临时 IPv4/IPv6 UDP INVALID 规则计数在撤销前均为 0，因此不能把本次 P2P 归因于该 UDP 规则；证据更支持手机经已有 TCP 监听端口在同一局域网直连。本次证明观察到 phone 一跳 P2P，但没有证明互联网 NAT 打洞或解决原 relay 问题。之后用精确匹配项删除两条临时规则，复核已不存在；EasyTier 以 Ctrl-C 正常退出，TUN/进程消失，UFW 仍 active，14700 服务仍仅监听回环。未写入 UFW 持久配置、未更改其它系统防火墙设置。后续验收按用户要求采用家宽场景；本次局域网结果不代表跨家宽打洞已修复，本轮不再进行联网测试。 面向后续维护者的独立排障手册见 [`UFW-P2P-TROUBLESHOOTING.md`](UFW-P2P-TROUBLESHOOTING.md)。


## 2026-10-04 TCP 主监听与防火墙规则对齐

用户要求依据刚才 phone 的 TCP 直连现象修复防火墙功能，取消额外联网测试并限定后续场景为家宽。确认独立 EasyTier 默认有 TCP/11010，而 Linux Web 及当前上游 Windows 网络启动代码的普通模式显式主监听仅 UDP；已有 Linux 防火墙计划也只为主端口放行 UDP。此次 Linux 专属补充普通节点同端口 TCP/UDP 监听，加入时同时预留两种协议，随机端口只有两个协议均可绑定才使用；固定端口冲突拒绝且不改端口。重新组网保持原端口，旧核心退出后重新检查两种监听端口。WebSocket 模式继续使用 WS/TCP。节点、密钥、加密与共享房间协议不变；Windows/Android 未修改。

新计划 tcpListener=true 为当前主端口补 TCP 规则，UFW/firewalld/可选 UFW 出站均同步；serde 默认 false 保持旧账本撤销命令一致，TCP 项有独立标记，管理员已有规则与 deny/reject 保护仍生效。诊断显示配置监听协议，socket 快照保持独立且权限不足时不伪报验证成功。旧服务须重启后重新加入并预览/应用规则；单纯刷新页面无法更新核心或旧规则。独立 UFW 文档移除移动网络建议，记录已实施修正。

验证：TypeScript/Vite/Rust debug 完整构建通过，99 项前端测试、125 项 Rust 测试通过，git diff --check 通过。新增测试覆盖双监听 argv、普通 TCP/UDP 与 WS 模式隔离、TCP/UDP 固定端口冲突及失败释放、两后端规则/可选出站/旧账本撤销/管理员 deny 保护。端口释放测试对并行 fork/exec 的短暂描述符继承作有界等待，避免将尚未释放的 TCP 端口误判为 UDP 路径错误。未触发 pkexec、未修改真实 UFW/firewalld、未加入真实房间，不宣称原 relay 已完全修复；保持现有服务进程，使用新功能需下次重启服务并重新加入。未提交、推送或发行。


## 2026-10-04 Linux Web 3.6.0 发布

用户要求先隐藏 Magic DNS，再 pull 并发布正式版 3.6.0。本轮移除设置入口、网络标签、首页卡片和功能矩阵 Magic DNS 项；底层域名/hosts 代码保留但界面不可访问，不自动改变已有 hosts。DNS UI 测试改为入口不可见且不会发起 DNS 操作；认证取消流程继续用可见防火墙入口验证。

`git pull --ff-only origin master` 返回已经最新；fetch 官方 master 后合并 e8d792d，合并提交 d1d83b7。官方 API latest 非预发行仍 v3.9.5；master 后续仅修改 src-tauri/Cargo.lock。现有未提交 Linux Web 修改全部保留并纳入本次发行，Windows/Android 宿主源码与 upstream/master 一致。

本版包括此前导航、桌面 UI、房间工具标签、firewalld 状态、暂停/恢复与退出清理、TCP/UDP 双监听、防火墙变更后静默重连等修改。用户本次确认 UFW 规则应用后重连可建立 P2P；该反馈不能推广到所有 NAT。自动重连仍待真实房间复测。暂停整个防火墙仍有单独输入确认；普通授权或失败不会被自动化测试实际执行。

Linux 服务 Cargo 版本改为 3.6.0，共享 package.json 的官方 3.9.5 协议版本保持不变。根 README、本目录 README、包内中文说明及 RELEASE-3.6.0.md 更新到本版。包内额外附 UFW-P2P-TROUBLESHOOTING.md。二进制实际最高 GLIBC 符号版本为 2.39、链接 OpenSSL 3，运行需求注明 x86_64/glibc>=2.39（例如 Ubuntu 24.04+/Debian 13）；旧系统建议目标机源码构建。

验证：13 个前端测试文件通过；Rust 125 项离线测试通过。第一次 Rust 测试在沙箱中有 1 项 socket 绑定受限，允许本地临时 socket 后重跑 Rust 全套通过；无真实房间、系统认证或防火墙操作。TypeScript/Vite/release Rust 构建通过。ZIP 完整性、6 项必需文件、可执行权限、包内服务与构建产物一致性、运行库依赖及 git diff --check 通过。Firefox/ICE/长期恢复、firewalld 实机规则、游戏各参数、文件夹/工具跨端同步未在本轮实测。

发行文件：mctier-linux-web-linux-x86_64-3.6.0.zip 及相对文件名 SHA-256 清单。
ZIP SHA-256：88c8d119acf7ef56823bb7db2d62047c3077e3c605c9ec05e27100d2635c60e8。
目标：origin/master 与独立标签 3.6.0；发行页面 https://github.com/Copper0SO4/MCTier_Linux_Web/releases/tag/3.6.0 。当前运行中的本地服务未自动替换；需要下次重启才能加载本版界面。

发布结果：2026-10-04 14:31（Asia/Shanghai）已推送 origin/master 与标签 3.6.0，并在 GitHub 发布 Latest 正式发行。页面确认标签 3.6.0 对应提交 8598674，附件 ZIP 和 .sha256 已上传完成。发布页：https://github.com/Copper0SO4/MCTier_Linux_Web/releases/tag/3.6.0 。本段为发布后文档记录；发行标签保持原构建提交，不重打包或移动标签。

## 2026-10-04：3.10.0 兼容修复与维护优化（未发行）

- 按 Windows `LobbyForm.tsx` / `App.tsx` / `WebRTCClient.ts` 为主、Android SignalingClient 为辅核对协议。Linux 表单提供明确创建/加入按钮，发送 create/join；原版客户端接受注册后改为 auto，虚拟 IP 冲突恢复保留准入模式。拒绝旧服务器缺少 lobbyEntryModes 的握手，不猜测或切换服务器。
- 首页创建入口将创建按钮排列到表单首位，加入入口将加入按钮排列到首位；按钮名称始终明确。连接中、已连接、孤立会话均禁用两种进房按钮。
- 成功完成注册和本地初始化后再写入共享 store 的 in-lobby/connected；信令状态变化同步 store，终止注册失败时释放房间。退出清理分别尝试媒体和本地服务，单项失败不会跳过另一项；连接期租约失败会取消本次进房。禁用浏览器存储不再把已准入会话误判为连接失败。
- 高级网络新增“客户端上报版本”，默认由当前上游 package.json 生成（本轮 3.10.0），接受数字版本如 3.10 或 3.10.0。偏好仅存浏览器本地，在本次进房固定；之后保存的值下次进房生效，信令重连保留本次值。此项不改变协议或程序能力。Linux 发行版本仍 3.6.0；bootstrap 默认兼容版本由当前上游 package.json 在构建时生成，另返回 releaseVersion。
- 原版 appVersion 缓存仅在 Linux overlay 上调整为每次 initialize 重新读取已固定的会话版本；Windows/Android 源码未另修改。
- 修复防火墙后端 onchange 被覆盖的问题，统一刷新区域、UFW 出站选项和暂停按钮；切到 firewalld 时清空 UFW 专用勾选，切回 UFW 时重新启用。
- 握手检查校验新版能力字段；测试 fixture 与 Windows 源码对齐，新增真实注册构造的 create/join/auto 和旧服务拒绝用例、版本缓存/会话固定及防火墙后端切换回归。
- 同步脚本新增 --stable（获取官方 latest 非预发行标签），可以组合 --merge；默认仍审查 master，任何合并要求工作区干净。错误不会回退其他分支。
- 验证：14 个前端自动化测试文件通过；125 个 Rust 测试通过；TypeScript/Vite/Debug Rust 完整构建通过；脚本语法与 diff 检查通过。Rust 端口测试在沙箱禁止绑定时失败，允许回环绑定后全通过，未做防火墙或真实大厅操作。
- 本机系统 rustc 仍有 LLVM 动态库错误，本次使用 SHA-256 核验的官方 Rust 1.90.0，临时安装到 /tmp/mctier-rust-toolchain/installed。未改系统认证或系统工具链；重启后该临时路径可能消失。后续建议由用户持久安装 rustup 工具链。
- 未验证：真实创建/加入（同名、不存在、错误密码、取消）、重新组网后的媒体恢复、自定义上报版本的服务端响应及跨端长期稳定性。没有推送、发布或启动服务。


## 2026-10-05：稳定版核对与架构收尾

- 官方 latest release 仍为 v3.10.0，发布时间 2026-10-04T11:03:49Z，commit `d6af338e793941e15b777ec8abf6c9109d9591b1`。HEAD 已包含此标签；fetch 后 master 为 `61cb9f5`，包含未发行重构/CI 提交，本次不合并。
- 控制请求取得 owner + generation ticket；启动、退出、重新组网在实际持锁执行时复核，拒绝已经跨会话的排队请求。启动失败在同一 Runtime 锁内完成清理，避免旧清理误停新房间。此措施尚不等同于所有 API 都采用端到端会话 epoch，详见架构文档剩余项。
- 防火墙预览记录 EasyTier 实例 UUID；授权前后及重连时复核对应会话，操作锁保持到重连完成。返回结构化 restart 状态，UI 明确显示防火墙修改完成但重新组网失败，不自动回滚已应用规则，也不重连新房间。
- 虚拟网络 HTTP 统一 no_proxy + 禁止重定向，保留已验证对端地址/来源约束；没有改变浏览器信令、服务器地址或节点选择。
- 附件读取/上传绑定聊天 generation，退出立即取消慢上传；下载在读取后再复核对端身份元数据。二进制响应持有并发 permit 到流结束/丢弃。共享面板用 TransferScope 管理全部请求，下载使用 boundedBlob 校验声明及实际大小，不依赖 Content-Length 存在。
- Vite 不再整体复制 public，只输出应用图标和必要构建资源，保留 566 项官方 v3 动画表情。手工 Rust 适配四项来源记入 upstream-sources.json，构建/测试检查源文件哈希；前端补丁上下文不明确时停止。
- 验证：官方 SHA-256 校验的 Rust 1.90.0 临时工具链，TypeScript/Vite/Rust Debug 构建通过；16 个前端测试文件、129 个 Rust 测试全通过。新增测试使用模拟浏览器/本地回环，不修改防火墙、不接入真实大厅。共享 src/src-tauri/Android 无额外本地改动，diff whitespace 检查通过。
- 宿主 /usr/bin/rustc 仍因 LLVM_23.1 符号缺失不能运行。本次工具链安装于 /tmp/mctier-rust-toolchain/installed，重启后可能丢失，不应写成永久构建依赖。正常开发者应安装可用 Rust stable；后续应在 CI 固定工具链和 glibc 基线。
- 本轮不启动服务、不 commit/push/release；真实创建/加入/取消、跨端文件与媒体、UFW/firewalld 重连及长时间恢复需用户下一轮验收。当时 Firefox 封锁、Magic DNS 隐藏、远程输入不支持维持产品决定；Magic DNS 隐藏随后按用户新要求撤销，见下节。


## 2026-10-05：Magic DNS、成员管理与个人设置

- 用户变更了此前“Magic DNS 隐藏”的安排：重新开放独立标签、功能卡和成员域名列表；底层继续使用原版 HostsManager 身份规则与现有固定授权助手。只写 LinuxWeb 段，不自动授权、不接管 DNS 服务；变化后需预览再更新，退出不自动清理。新增未闭合/重复/嵌套段拒绝测试，状态页可显示此错误。真实解析和跨端游戏访问尚待用户确认后验证。
- Windows WebRTCClient 的 `setPlayerMuted` / `transferHost` 分别发送 `mute-player` / `transfer-host`。Linux 成员卡和房主面板复用方法；请求发出不乐观改状态，监听 `onMuteChanged` / `onHostChanged` 和注册元数据更新 store。转让需确认，旧房间的成员操作拒绝；房主语音禁言明确不禁止文字聊天。
- 每人音量沿用 store.setPlayerVolume/getPlayerVolume，范围 0–1，对应 UI 0–100%。本地静音使用 store.togglePlayerMute，与房主禁言分开。原版 applyCurrentAudioState 负责新连接/重连恢复当前 store 音量；偏好不跨房间持久保存。成员详情展开状态在刷新成员列表时保留。
- `web/profile.ts` 保存昵称/头像到当前浏览器；昵称下次进入房间生效。参照原版 Avatar/SettingsAvatarPicker 的 256 像素、120000×1.37 data URL 预算与 JPEG 质量处理，额外限制源文件 8 MiB 和 1600 万像素；只接受 PNG/JPEG/GIF/WebP，压缩会移除动画。收发使用原版 P2PChatService.sendAvatar/onAvatar，退房 reset 及重连后重新安装回调，新成员到来补发。没有个人资料云端、其它自定义资料字段或跨浏览器同步。
- `web/settingsTabs.ts` 将设置整理为外观与音频、个人资料、会话统计三个横向标签，支持键盘切换；profile 入口可直接定位。统计仅本次页面会话，页面消息含历史补收，RTP 仅当前语音连接，不含屏幕/文件/EasyTier 总流量；不上传统计、不跨会话保存。
- 上报默认版本使用原版 VersionCheckService 和 versionPolicy 的固定 Gitee tags 接口、严格版本/commit 字段过滤、语义版本排序。Linux 检测以源码基线作比较，避免手填两段上报版本影响检测；overlay 将获取元数据桥接到固定服务端命令，以保持同源 CSP 并避免 CORS 依赖；10 秒超时、256 KiB/100 标签上限、成功缓存 5 分钟，拒绝重定向，无任意 URL 入参。启动检测失败保留源码默认，不切换来源；手填覆盖优先，活动会话冻结，检测较旧不降级。此功能只更新上报默认，不能代替源码兼容跟进或升级安装。
- 验证：21 个前端测试文件（119 个用例）（新增成员本地操作/过期拒绝、资料校验、统计口径、版本默认/手填/会话冻结、原版检测桥接），131 个 Rust 测试及完整 Debug 构建通过。尚未运行真实服务/房间、pkexec、hosts 修改、头像跨端、转让/禁言、桌面外观验收；不会将本地模拟结果标成真实通过。
- 源码改动仅 Linux Web + 根 README，保留前轮未提交更改；本轮没有 commit/push/release。


## 2026-10-06：稳定版复核、会话诊断优化与提交

- 运行 sync-upstream.sh --stable 重新获取官方 latest 稳定标签：v3.10.0 / d6af338e793941e15b777ec8abf6c9109d9591b1；当前已包含，无新稳定源码需要合并。origin/master 也已 fetch，没有远端新增提交。所有前轮工作区修改保留，并纳入本轮提交；不重置或覆盖工作目录。
- refreshDiagnostics 开始时捕获会话 ticket；HTTP 响应及 WebRTC getStats 返回后复核。旧房间/未连接时发起的诊断不会误判新房间 EasyTier 停止并触发 leave，也不会覆盖新媒体状态。状态请求增加 8 秒超时；媒体诊断错误与本地 HTTP 不可达分开提示。在线统计等媒体数据返回后一次刷新，避免每轮先将 RTP 清零。
- 文字输入框改用已有 sendOutgoing，与文件/图片/语音共用发送结果的会话检查。迟到成功/失败结果不污染新房间消息和提示。新增 captureSession 及延迟结果模拟测试。
- 验证：22 个前端测试文件（120 个用例）、131 个 Rust 测试全通过；完整 TypeScript/Vite/Rust Debug 构建通过，566 项表情保留，源码空白检查通过（统一 diff 的空白上下文行须保留格式前缀，检查时排除 *.patch）。源代码额外变更仅 MCTier-Linux-Web 与根 README。系统 rustc 仍有 LLVM 符号问题，本轮重新下载并核验官方 Rust 1.90.0，在临时目录构建，没有修改系统工具链。
- 用户已授权 commit/push，目标为 origin/master（Copper0SO4/MCTier_Linux_Web），不是官方 upstream；此次不制作 Release、不启动服务、不触发系统授权。真实创建/加入、Magic DNS 解析、头像跨端、转让/禁言、音量和媒体专项验收保持待测。


## 2026-10-06：成员音量直接显示，移除 Magic DNS

- 用户要求每个成员的音量控件直接显示在房间主页左侧各成员下方。`memberControls.ts` 根节点从 details 改为 div，音量与本地静音始终显示；只将房主禁言/转让/移出保留在独立折叠区域。沿用原版 store 的逐成员音量、静音和 WebRTC 播放逻辑，0–100%，仅影响本机。过期房间及房主权限检查保留。
- 用户进一步说明想要进房前自定义域名。调查发现上游 `AdvancedNetworkConfig` 存在 virtualDomain 表单，但未找到它接入信令分发的逻辑；随后用户明确决定不实现并全面移除 Magic DNS。此最终决定覆盖以前隐藏/重开及本轮早期恢复入口的安排。
- 删除 Linux Web 功能项、首页入口、网络标签、DNS 表单、get_magic_dns_status/prepare_magic_dns 分派、域名预览/写入代码、hosts_manager/hosts_security/unix_hosts_helper 模块编译入口及 hosts 特权助手启动分支。通用授权助手仅供防火墙使用，请求上限改为16 KiB。原版 Windows/Android 文件不变；EasyTier accept_dns 继续 false。成员身份获取保留给 Minecraft 查询。
- 已有系统 hosts 不自动读写或清理。根 README、Linux README 和包内中文说明记录旧 LinuxWeb 段的手工清理范围。历史发行说明保留原样；上游来源及历史审计文档不代表当前产品能力。
- 自动化：22 个前端文件（120 用例）、123 个 Rust 测试与完整 Debug 构建通过。新增旧 DNS 命令返回501的回归检查；删除已移除功能的测试，不以数量减少视为覆盖退步。成员音量测试确认滑条不在折叠根节点中。首次沙箱内测试有两项因禁止回环端口绑定失败，已在允许本机端口的环境重跑通过。
- 本轮不创建/加入真实大厅、不触发 pkexec、不修改系统 hosts、不 commit/push/release。真实多人逐成员音量和桌面外观仍待用户验收。
- 构建后核对本地服务无活动大厅，停止旧进程并启动新版，仅监听 127.0.0.1:14700；用户需刷新页面。

## 2026-10-06：压缩成员卡片高度

- 用户反馈成员卡片过高。本轮只调整 Linux Web CSS：减少卡片与控件间距，将音量和本地静音排在同一行，房主管理独占下方折叠行。滑条覆盖通用 input 的10px内边距与边框，保留24px高的拖动区域；按钮保留28px最低高度。音量不隐藏，逐成员播放逻辑和上游代码不变。
- 此次不新增或运行测试；此前120前端/123 Rust结果属于前一轮。完整 Debug 构建与源码空白检查通过。确认无活动房间后重启本地服务加载新版；桌面外观待用户刷新验收，真实房间与媒体未测试。

## 2026-10-06：创建与加入表单只显示对应按钮

- 原 shell 仅调整两个提交按钮的顺序，创建页面仍出现加入按钮。本轮改为单个 entry-submit，由 shell 保存明确的 create/join 模式；标题、页面可访问名称、按钮及说明同步。提交从 shell 读取模式，不依赖 submitter，因此回车提交不会误走加入。邀请码/历史大厅沿用 showConnect 的加入模式；忙碌或已入房时不能更换入口，重连原版 auto 协议保持不变。
- 原导航用例同步改为单按钮与模式断言，此次不新增或运行测试。构建与桌面外观验收状态以下方补充为准；不自动创建/加入大厅，不触发系统授权。
- 完整 Debug 构建及源码空白检查通过；核对服务无活动房间后重启加载新版。页面交互和真实创建/加入待用户验收。

## 2026-10-06：返回按钮横排与样式隔离

- 用户截图显示创建/加入页面返回文字竖排。原因是共用原版 MiniWindow 的 back-button 类，继承固定26px宽高；原来的网页覆盖只调整内边距，未重置尺寸。所有网页返回按钮改为独立 web-back-button，箭头与文字分开，inline-flex横排、不换行、最小宽76px高36px；设置等页面一并修改。点击/键盘返回逻辑不变，未修改上游CSS。
- 此次不新增或运行测试。构建及服务状态以下方补充为准，实际桌面外观待用户验收。
- 完整 Debug 构建及源码空白检查通过；确认无活动房间后重启本地服务加载新版，用户刷新可验收。未 commit/push/release。

## 2026-10-06：补充原版 EasyTier 高级设置

- 对照原版GlobalAdvancedConfigPanel、LobbyAdvancedConfigModal、EasyTierAdvancedConfig与network_service参数映射，核对随包2.5.0核心--help（仅输出帮助，不启动节点）。补充20个字段：smoltcp、KCP/QUIC输入控制、系统子网转发、出口节点、中继RPC/KCP/白名单/限速、私有模式、TUN名称、默认协议、IPv6、手动路由、公网映射监听器、TCP/UDP白名单与两类STUN列表。
- 创建/加入高级选项新增明确入口，现有高级网络面板用折叠分类容纳新项；仍保存浏览器本地，旧对象通过默认值补全，不覆盖当前会话快照。Rust进行枚举、TUN15字符、IP/CIDR、端口范围、URL、STUN主机端口、列表长度、限速整数范围校验，复用共享apply_advanced_config。runtime避免重复传default-protocol。
- 不声称完全等同原版：无TUN、核心DHCP、任意/无监听器、SOCKS5和多大厅配置继承仍不开放；加密与网段遵循原版固定逻辑，Magic DNS遵循用户移除决定。完整对照见ADVANCED-NETWORK.md。系统转发/出口不会自动调整sysctl/NAT或申请授权；TCP白名单可能阻断应用端口，UI明确提示。
- 此次不新增或运行测试；之前120前端/123 Rust结果不包括新字段。新增参数真实代理/出口/IPv6/STUN和跨端表现待用户确认后实测。构建及服务状态以下方补充为准，不commit/push/release。

## 2026-10-06：软件设置分类与操作入口迁移

- 用户反馈功能藏在功能状态页。本轮设置页扩为六个标签：外观与音频、个人资料、会话统计、网络与游戏、大厅与邀请、桌面集成；键盘左右/Home/End按实际标签数导航，保留原音频/资料控件节点和状态。
- 软件设置新增EasyTier高级设置、游戏快连、网络诊断/防火墙、常用/最近大厅、公开广场、导入及生成邀请入口，分别复用networkPanel/community现有功能。不会因打开设置而访问真实大厅、读取本机目录或申请系统权限；广场由用户点击触发查询。房主管理、文件夹共享及工具继续放大厅左侧。
- community/networkPanel/profile不再向feature-matrix注入操作按钮；功能状态只展示能力与限制，未开放的桌面功能仍为封锁牌，不冒充实现。界面中新增独立data-network-open/data-community-open动作绑定，和原功能卡共用逻辑，维护时应同时检查这些入口。
- 完整TypeScript/Vite/Rust Debug构建及源码空白检查通过。此次未新增或运行测试，也未真实联机/授权；此前测试数量不代表本轮验证。确认无活动房间后重启加载新版设置供用户验收，不commit/push/release。

## 2026-10-06：可选启动组网与网页接续

- 用户明确要求服务启动先自动组网，再在浏览器访问后通知已在房间内。新增auto_join.rs、web/autoJoin.ts，设置→大厅与邀请提供独立目标/昵称/密码/节点/信令输入，使用已保存网络参数快照。默认关闭，保存只改变下一次服务启动；禁用保存删除配置中的目标。不会擅自开机启动服务或开启媒体。
- 配置位于app_paths::data_root()/linux-web/auto-join.json，0600原子替换、同用户文件/O_NOFOLLOW、64KiB上限、父目录不得为链接/其它用户/组或全局可写。密码复用本机Secret Service加密封装，状态接口不返回密码，受CSRF/owner约束的配置/接续接口返回本机加密值。启动解密并复用原start，缺CAP不触发pkexec；失败显示一次，不换节点/不循环重试。临时启动owner只清理自己拥有的失败会话，避免误停浏览器抢先开始的新房间。
- runtime增加auto_network与startup状态，成功后释放临时owner让首个合法浏览器接续。网页先等待启动状态，adopt_auto_network返回会话快照，复用原版v3 auto注册（用户后续要求：存在加入、不存在创建），不重复启动EasyTier、不自动改变已绑定虚拟IP。只在信令注册成功后弹出入房通知；临时连接失败detach保留核心且提供接续重试按钮；密码错误等RegistrationError.retryable=false永久拒绝则停止核心并弹窗提示。Firefox仍不可接续。
- 浏览器关闭使用detach_browser；自动会话停止聊天/目录服务、清理凭据及owner generation但保留EasyTier。租约过期同样释放浏览器控制而保留后台网络。普通手动房间仍按原规则停止。明确退出/服务终止停止核心，不因已保存设置立即自动重入；下一次服务启动才再尝试。网络后台没有信令成员在线、聊天与媒体能力，须分别报告状态。pagehide只执行preserve identity的浏览器媒体清理，避免迟到的stop_p2p_chat破坏下一页；stop_p2p_chat补control generation复核。
- UI自动加入功能牌改实验性，桌面集成不再显示旧封锁项；同步旧功能矩阵测试断言，未新增或运行测试。构建与加载状态见下文。不得把前120前端/123 Rust测试当作此次生命周期验收。真实启用、服务重启自动组网、页面刷新/多页争用/浏览器关闭/租约过期、空/非空密码、信令失败、对端数据仍待用户确认后测试。
- 用户后续明确要求错误密码不入房并提示、自动创建/加入不分房间是否存在。采用现有entryMode=auto；原版不可重试RegistrationError（含密码错误）立即停止后台核心并提示，不做换址或降级创建。浏览器未打开前没有服务器密码验证，此边界已说明并记入README。
- 完整Debug构建及源码空白检查通过。核对旧服务无房间且启动配置不存在（默认关闭）后重启加载新版；此次没有启动EasyTier/连接真实信令或授权。

## 2026-10-06：Linux Web 3.9.0 发行收尾

- 用户确认当前开发版“功能大概都正常”，授权提交、推送和正式 Release 3.9.0；不添加 v 前缀。发行汇总涵盖本轮成员音量/紧凑卡片、创建/加入单按钮、返回按钮、六标签设置、高级 EasyTier 参数、Magic DNS 全面移除及可选启动组网。
- Linux Cargo/Cargo.lock 同步为3.9.0，共享package.json保持官方3.10.0；官方稳定基线仍为已合入v3.10.0/d6af338，不因Linux发行号回退上报版本。
- 根/子README更新下载、构建和行为说明；包内中文依赖需求与发行说明更新，新增随包ADVANCED-NETWORK.md。历史发行与审计文档不重写。
- 用户总体反馈与专项测试分开记录。最近120前端/123 Rust通过属于历史基线；本轮未重跑全套自动化，新增高级参数及自动启动生命周期仍需针对性测试。Firefox封锁、远程输入不支持及未实现桌面功能保持现状，不把总体验收推广为全部能力已验证。
- 本次发行不停止或替换正在运行的开发服务，不触发pkexec、不另外接入真实大厅。构建、包体校验和发布结果随后追加。
- 发行校验结果：TypeScript/Vite/Rust release 构建通过（Rust 1.90.0）；ZIP CRC、7个必需文件、可执行权限及包内服务与构建产物一致性通过；源码空白及启动/打包脚本语法检查通过。ELF最高GLIBC符号为2.39，链接OpenSSL 3和D-Bus；中文运行要求据此保持glibc>=2.39。ZIP SHA-256：`8d3b41a37131879099b2bfd0b4b4a50a4c3f5e6905c68f0081dbad80f9d74a40`。
