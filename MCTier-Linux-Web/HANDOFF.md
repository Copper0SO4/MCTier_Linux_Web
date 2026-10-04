# MCTier Linux Web：维护者接手说明

> **最近维护：** 2026-10-04，已合入官方稳定版 v3.9.5；房主管理、滚动与上一轮 UI 已获用户验收；新增高级网络、游戏快连/发现、Magic DNS 与防火墙修复，待真实环境验收。当前改动未发行。房间/目录共享等协议功能基线为 `cc269302d3c20eb0c2b6e224643266c61270e1cb`；实际提交请用 `git log -1` 核对。本文件是 Linux Web 当前目的、状态和维护流程的权威说明；较早的 `MIGRATION-AUDIT-2026-10-03.md` 是历史调查日志，结论可能已过期。**每次维护都要同步更新本文件**，功能、限制、构建与验收记录须和当前代码一致；规则也保存在本目录 `AGENTS.md`。

## 项目目的

在保留 MCTier 原有组网、房间、聊天和 WebRTC 协议的前提下，为 Linux 提供独立的本地 Web 服务和浏览器界面。用户自己启动服务，再手动访问 `http://127.0.0.1:14700`。服务不自动启动、不开浏览器，也不依赖 Linux Tauri 窗口或 WebKitGTK 媒体管线。Linux 当前仍是实验性项目；目标主要是 Debian 系 x86_64。

官方平台说明：[MCTier 平台支持](https://mctier.pmhs.top/#platforms)（官网内容整理自 3.8.0 系列，最近标注更新于 2026-09-30）。官网已验证 Linux 组网，语音、屏幕共享和远程协作受 WebKit/WebRTC 限制。浏览器版是独立实验方案，不能据此声称官网限制已解决。Windows 和 Android 的实现应保持原样。

## 当前代码和分支

- 工作树在 MCTier 仓库内；当前维护分支为 `master`，跟踪远端 `origin/master`；新 Agent 接手时应重新核对本地提交与远端状态。`origin` 是用户仓库 `Copper0SO4/MCTier_Linux_Web`；`upstream` 配置为官方 `https://github.com/pmh1314520/MCTier.git`。
- 已知宿主源码曾同步到官方提交 `6c73b564`（MCTier 3.9.0）；Linux 适配最初以 `dbb7bbd8`（3.8.0）为基础，后来重套补丁并复核。2026-10-04 已从官方重新 fetch `upstream/master` 和最新非预发行 `v3.9.5`；二者均为 `5efda418ba4f5a10a1f2e3dcc0c224a1b7a0ddb4`，官方没有单独 stable 分支。本地合并提交为 `65750e8`，已包含官方稳定源码。昨天的 Linux Web 未提交改动全部保留。以后仍须先 fetch 再审查差异，不把此记录当作未来最新版本。
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
- 发行包启动器行为可能与源码运行入口不同：发行版可以在服务就绪后打开默认浏览器。修改启动流程时须在根 README 和发行说明中保持一致；不得自动加入房间或启动 EasyTier。

### EasyTier 权限

- 使用 `fetch-binaries.sh` 下载并核验随包 EasyTier v2.5.0；二进制不提交 Git。正常情况 MCTier 和 EasyTier 均以当前普通用户运行。
- 只有确认 core 缺少 TUN 所需 capability 后，才可由用户通过 `pkexec setcap cap_net_admin,cap_net_raw+ep <core路径>` 单独授权，并用 `getcap` 复核。绝不通过 pkexec/root 启动整个应用或 EasyTier；不代输密码、不改 PAM/系统认证配置。

## 功能矩阵

界面实际状态定义在 `web/featureAvailability.ts`。**22 项：16 项实验性接入，6 项封锁。**菜单徽标会另外注明有明确用户实测反馈的功能，不代表完整协议专项测试。

| 状态 | 功能 |
| --- | --- |
| 实验性 | 大厅与虚拟组网入口、文字聊天/附件下载、麦克风与变声、屏幕共享/观看、文件夹共享、发送文件、图片/表情、语音消息、房间工具、房主管理、二维码/邀请、常用/最近大厅与公开广场、主题切换（语言切换未开放）、Magic DNS、高级网络/游戏增强、预览式防火墙修复（后三项待真实验收） |
| 封锁 | Firefox 大厅连接、远程操控、系统音频/屏幕录制、托盘/快捷键/悬浮窗、头像/统计、自动加入/自动更新 |

房主管理接入了公告、人数限制、公开发布/撤销和移出成员；房主转让、成员禁言等原版操作未在 Linux Web 提供。封锁牌是产品提示，不是安全授权机制；服务端仍须独立验证每项敏感操作。远程输入操控按用户产品决定明确不支持，不列为待实现功能。

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
| `web/networkPanel.ts` | 高级参数（下次加入生效）、游戏快连、Magic DNS/防火墙预览确认 UI |
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

- 依据上游 Windows HostPanel.tsx、WebRTCClient.ts 和 P2PChatService.ts：Linux Web 房主面板复用 set-lobby-options 与 announce；人数上限、公开状态由信令回报更新，公告由 EasyTier 聊天控制消息发送并在新成员加入后补发。与原版差异：Linux Web 只提供 0–64 的人数输入，不提供房主转让/成员禁言；公告直接显示在浏览器大厅侧栏，发送状态展示本地服务的对端送达数。
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
