# MCTier Linux Web

> **维护者接手说明与当前状态：** [HANDOFF.md](./HANDOFF.md)（截至 2026-10-03；本目录内当前状态以该文档为准）。`MIGRATION-AUDIT-2026-10-03.md` 是历史调查记录，不是当前功能状态表。

MCTier Linux 的独立浏览器入口与本地服务端。当前推荐 Chromium/Chrome；Firefox 存在未解决的信令断连，界面暂时封锁其大厅入口。源码运行脚本需用户手动启动服务并访问 `http://127.0.0.1:14700`；发行压缩包的启动器会在服务就绪后打开默认浏览器。进入大厅及运行 EasyTier 仍需用户主动操作。

本目录位于你的 MCTier 分支仓库中。当前宿主代码已快进到分支 `master` 的 `6c73b564`（MCTier 3.9.0）；Linux 功能最初以官方 `dbb7bbd8`（3.8.0）为基线，已将共享前端补丁重新套到当前 3.9.0 源码并复核构建。它是供继续放在 MCTier 源码仓库中、单独向官方上游提交的 Linux 功能目录；构建时从仓库根目录读取共享的 `src/`、`src-tauri/src/modules/`、`package.json` 和锁文件。单独克隆本目录的 GitHub 仓库时，还需要相同版本的 MCTier 上游源码作为宿主。

## 当前进展

- 界面直接引用当前上游的 `MainWindow`、`LobbyForm`、`MiniWindow` 和 `ChatRoom` CSS：主界面恢复 Logo、创建/加入、设置与关于入口；大厅使用成员侧栏和独立聊天、屏幕、诊断页面。浏览器宽屏采用侧栏布局，未挂载原版 Tauri 窗口组件，不承诺窗口布局逐像素一致。
- `web/featureAvailability.ts` 集中管理功能牌。当前 22 项状态为 11 项实验性接入、11 项封锁；不可用入口使用禁用按钮并显示原因。文件夹共享和房间工具已接入但仍待真实跨端验收；远程操控、系统音频等仍封锁。Firefox 入口同时有提示、按钮禁用和连接处理器检查；这只是暂时封锁，不是根因修复。
- 独立 Rust/Axum 服务默认只监听 `127.0.0.1:14700`；验证 Host、Origin、CSRF、浏览器会话和请求范围，不提供任意本机路径接口。拒绝以 root 启动。
- 大厅、成员、MCTier 信令注册、聊天签名/加密、EasyTier 参数和 WebRTC 逻辑沿用 MCTier 现有协议及共享实现。信令服务负责大厅和 WebRTC 信令；EasyTier 节点负责虚拟组网。
- 用户点击后才启动指定 EasyTier 节点并注册信令。未加入房间不会触发网络连接。
- 浏览器麦克风、语音、变声、聊天、房间成员与 ICE/RTP 状态界面已接入。Firefox 曾反复出现信令 WebSocket 1006 断开；诊断面板曾显示单次存活约 1.4 秒、收包为 0、待处理 8、无待收心跳。用户在干净 Firefox 配置和关闭 AdBlock 后仍遇到断开。根因尚未确认，Chrome 曾表现较好也不能证明 Firefox 或 WebRTC 已修复。
- 聊天附件下载使用原版签名、成员认证和加密附件端点，单个附件上限为 64 MiB；新增文件上传并注册到原版取件服务，私聊附件只允许指定成员获取。会话内上传最多 128 个、共 256 MiB；退出清理已注册文件。文件夹共享以用户选定目录的临时快照接入原版 HTTP 服务，详见下文。
- 新增图片与表情发送：PNG/JPEG/GIF/WebP 不超过 2 MiB 时沿用图片消息，较大图片保留原始数据作为文件附件（不做原版桌面的本地重编码）；支持常用 Unicode 表情和选择图片表情，随包复用原版 566 项 v3 动画表情及标识，构建固定校验已审查资源包的 SHA-256；自定义分类及收藏管理尚未接入。表情资源增加约 107 MiB 的未压缩体积。
- 新增语音消息录制、取消与播放，限制 0.5–60 秒、2 MiB。复用原版录音设备选择和通话静音门控；待权限响应期间取消、退出、页面隐藏与超限均释放录音资源。选择收件人后录音，发送到开始录音时选定的会话目标。
- 屏幕共享/观看沿用原版信令和 WebRTC 屏幕协议，界面有共享列表刷新、播放恢复和连接诊断。手机对 Linux、Linux 对手机的画面均未在本轮真实联机验收。
- 新增目录快照共享、房间工具（掷骰子/本地倒计时/协同待办）、常用与最近大厅、公开广场查询/房主发布、原版 v3 邀请生成和导入；跨端验收待完成。
- 远程输入操控尚未接入。屏幕共享只采集视频，不采集系统音频。Magic DNS、快捷键与自动启动也未迁移。
- 构建和自动化测试通过；本轮已进行 Chromium 静态界面的导航与禁用状态检查，未连接真实房间。跨端聊天/附件、EasyTier 对端数据收发及 WebRTC 语音/屏幕仍未完成验收。

## 目录安排与上游更新

- `web/`：本地浏览器界面。
- `web/shell.ts`：页面导航与可用性展示；不发起网络、麦克风或屏幕捕获。`web/main.ts` 继续负责已接入功能的实际生命周期。
- `server/`：独立本地服务、嵌入式静态资源和 Linux 服务测试。
- `patches/frontend/`：仅保存 Linux 适配对共享前端文件的小补丁；构建准备器不调用 Tauri，直接基于当前上游树应用补丁。
- `server/shared/`：服务端抽出的共享 Rust 实现。
- `resources/binaries/`：本地下载的 EasyTier v2.5.0；二进制不入 Git。
- `frontend-src/`、`web-dist/`、`server/target/`：构建生成目录，不提交。

`scripts/prepare-frontend.mjs` 每次都从宿主仓库当前 `src/` 生成新的构建副本，再应用 `patches/frontend/`。它不会覆盖上游工作树。上游调整了相同代码而导致补丁无法套用时，构建会明确失败；维护者检查冲突后更新小补丁，再运行自动化测试。更新上游后也要重新审查服务端复制的共享 Rust 模块及协议字段。文件夹 HTTP 服务直接编译当前 `src-tauri/src/modules/file_transfer.rs`；`scripts/prepare-secrets.mjs` 从当前原版生成仅替换 Tauri 宏和密码校验入口的密码模块，v3/本地 AES-GCM 格式保持一致，若上游校验标记改变会中止准备。

更新到官方上游新版本时，在宿主仓库根目录执行：

```bash
./MCTier-Linux-Web/scripts/sync-upstream.sh
# 审查官方更新后，再合并（工作区须干净）
./MCTier-Linux-Web/scripts/sync-upstream.sh --merge
```

`upstream` 使用官方地址 `https://github.com/pmh1314520/MCTier.git`，不会静默切换到代理或其他仓库。若本机 Git 对 GitHub 有宽泛的代理地址改写，脚本仅在这次命令中以精确地址覆盖改写，并复核实际地址；不修改全局配置。脚本默认只拉取和展示最新提交；`--merge` 合并官方 master 到当前分支，相当于先 fetch 再 merge 的 pull 流程。它拒绝在有未提交改动时合并；冲突保留给维护者处理，不执行 reset、clean 或自动 stash。之后重新审查协议和 Linux 补丁，再编译。若 Linux 补丁与上游同一段代码冲突，应先对照新上游改写对应补丁；不要直接拿旧的 `frontend-src/` 构建。共享 Rust 文件 `server/shared/chat_service.rs`、`chat_transport.rs`、`config_types.rs` 与 `network_arguments.rs` 也应对照更新后的 `src-tauri/src/modules/` 复核。测试文件、服务端协议及 Linux 实现均在本目录，适合作为单独目录加入 MCTier 上游 PR。

## 工具链

构建主机需有：

- Debian 系 Linux x86_64（目标平台）；其他发行版未确认。
- Node.js 20 或更高版本、npm。
- Rust stable 工具链（`rustc`、`cargo`）。
- C 编译器和 OpenSSL/pkg-config 开发依赖（如 Debian/Ubuntu 的 `build-essential`、`pkg-config`、`libssl-dev`、`libdbus-1-dev`）。
- 仓库根目录的 npm 依赖（由 `package-lock.json` 锁定）。
- 下载 EasyTier 时需 `curl`、`unzip`、`sha256sum`（或 `shasum`）。

Tauri/WebKitGTK、GTK 应用窗口与 Linux 原生媒体管线不是此本地服务的构建或运行依赖。

## 编译

从 MCTier 宿主仓库根目录操作（不是进入本目录）：

```bash
npm ci
./MCTier-Linux-Web/scripts/fetch-binaries.sh
./MCTier-Linux-Web/scripts/build-web-server.sh
```

`fetch-binaries.sh` 固定 EasyTier v2.5.0，并校验下载包、core、cli 的 SHA-256；它只下载和校验，不调用 `pkexec` 或改系统权限。core 需要 TUN 所需的 `cap_net_admin,cap_net_raw` 时，先检查：

```bash
getcap MCTier-Linux-Web/resources/binaries/easytier-core
```

仅缺少 capability 时，用户可以自行通过系统授权界面运行 `pkexec setcap cap_net_admin,cap_net_raw+ep MCTier-Linux-Web/resources/binaries/easytier-core`，然后再次用 `getcap` 复核。MCTier 和 EasyTier 始终以普通用户运行；不要用 pkexec 启动服务或 EasyTier。

构建产物：

```
MCTier-Linux-Web/server/target/release/mctier-linux-web
```

## 手动启动与访问

```bash
./MCTier-Linux-Web/scripts/run-web-server.sh
```

保持终端运行后，在 Chrome/Chromium 或 Firefox 手动打开 [http://127.0.0.1:14700](http://127.0.0.1:14700)。服务启动时不会启动浏览器或加入大厅。默认信令地址与 EasyTier 节点来自当前 MCTier 配置；两个地址在界面中单独显示，连接失败时不会静默切换。按 Ctrl+C 停止服务。

## 自动化验证

```bash
./MCTier-Linux-Web/scripts/test.sh
```

该脚本准备上游源码补丁、运行本目录 Node 自动化测试及 Rust 测试。也可单独运行：

```bash
node MCTier-Linux-Web/scripts/prepare-frontend.mjs
node --test MCTier-Linux-Web/tests/*.test.mjs
cargo test --locked --offline --manifest-path MCTier-Linux-Web/server/Cargo.toml
```

截至 2026-10-03，前端 9 个测试文件通过，服务端 91 项测试通过；TypeScript 检查及本地 debug 构建通过。本轮没有构建或发布发行包。新增上传测试验证鉴权、私聊绑定、大小限制、文件权限和退出清理；录音测试使用模拟媒体验证取消及延迟权限响应的清理。没有申请真实麦克风权限或联机。构建日志与测试记录位于忽略提交的 `MCTier-Linux-Web/verification/`。

以后迁移某功能时，应先接通原版实现、补齐权限和资源清理，再更新功能表及验收记录。功能表属于界面提示，不替代服务端的命令、文件访问或同源权限检查。Firefox 断连原因解决并完成真实测试后，才能解除其大厅封锁。

## 本轮新增功能（待跨端验收）

- `web/community.ts` 负责独立工具面板；`web/lobbyData.ts` 管理常用与最近大厅，采用原版收藏键和加密密码格式。最近记录只在大厅加入成功后写入，最多 20 条；常用大厅可删除、填入，重新收藏同名同地址记录即更新。系统密钥环不可用时不保存明文。
- 原版 `publicLobbies.ts` 查询 `public-lobby-list-request/response`；Linux 补丁仅增加取消、关闭失败和响应大小限制。关闭面板立即关闭查询连接。缺少节点的大厅禁止加入，无静默回退。房主公开状态使用原版 `set-lobby-options` 和确认事件，仅允许无密码大厅；其他房主管理仍封锁。仓库内未包含信令服务端实现，按桌面和 Android 客户端对齐消息格式；实际发布/查询行为仍待联机验证。
- 原版 `lobbyInvite.ts` 负责 v3 链接与邀请文字；复用 `qrcode` 生成二维码并可下载。文字/链接导入只填入表单，仍需手动核对后加入。二维码图片识别依赖 `BarcodeDetector`，摄像头扫描未开放。邀请是可转交的加入凭据，勿公开发送带密码的邀请。
- 骰子按原版范围（1–10 枚，2–100 面）；倒计时复用原版绝对截止时间单例，面板关闭后继续，退出大厅停止。待办使用原版 `todo` JSON/后写覆盖语义及安全解析，房主按原版延迟 1.6 秒补发新成员清单，退出时取消计时器并丢弃过期响应，发送结果展示未送达目标；并发编辑与离线重放没有额外保证。录屏、游戏快连、HUD 保持封锁。
- `server/src/folders.rs` 只接受浏览器选择文件的相对路径和服务器生成的共享编号，不能指定本机绝对路径。目录上传至权限 `0700` 的随机临时根，文件权限 `0600`；拒绝路径穿越、符号链接、重复文件名和已发布快照修改。选择目录后上传的是**快照**，不自动读取后续变化，空目录不保留。
- 发布后运行未经修改的原版 `FileTransferService`，只绑定 EasyTier 虚拟 IP 的 `14539`。本地控制服务继续只监听 `127.0.0.1:14700`。沿用 `x-mctier-lobby-token`、`x-share-password`、共享有效期和撤销协议；公开摘要不含磁盘路径或密码。大厅凭据轮换时同步文件服务，认证重置时撤销旧凭据。正常退出/浏览器租约过期清理快照，未完成上传 5 分钟无活动自动清理；进程被强杀或崩溃时可能留下权限受限的临时目录，不保证自动回收。
- 对端查询/下载在本地服务代理：仅接受当前认证成员编号并解析其虚拟 IP，固定 `14539`、绑定本机虚拟地址，不使用系统 HTTP 代理、不跟随重定向；不会接受任意 URL/IP。浏览器侧凭据不在下载 URL 中。沿用原版 HTTP over EasyTier，并未增加传输加密协议。
- 共享上限为 16 个快照、1024 个文件、总量 256 MiB，单文件与浏览器下载 64 MiB；目录列表响应 512 KiB，最多同时两个文件传输。共享密码（原版 HTTP 头仅支持 ASCII，最多 128 字节）和最多 7 天有效期可选。批量 ZIP、断点续传/多线程下载 UI 暂未开放，无法宣称与原版全部文件管理能力一致。

无需发行打包的开发构建/手动启动：

```bash
./MCTier-Linux-Web/scripts/build-web-server.sh --debug
./MCTier-Linux-Web/scripts/run-web-server.sh --debug
```

本轮自动化覆盖目录鉴权、路径/符号链接防护、权限、大小/数量预算、会话变化、清理和安全摘要；收藏保护格式、邀请兼容、广场请求/取消/关闭错误、工具和导入按钮流程。没有连接真实大厅、读取真实用户目录、申请麦克风或触发系统授权。

## 真实环境验收仍待完成

需要分开测试与记录：Chrome/Firefox 手动访问及 loopback 监听；EasyTier 虚拟接口和对端数据收发；大厅、公开/私聊及文件下载；浏览器与 Android/Windows 对端之间双向麦克风、信令/SDP/ICE、断线重连、屏幕互看及资源清理。远程操控仍未实现。未进行的项目不能标记为通过。

真实房间联机或触发系统授权窗口前，应先明确告知所用房间、节点和操作，获得用户确认。

2026-10-03 用户报告发送文件、图片/表情及语音消息新功能验收正常。该记录属于用户真实环境反馈；未提供对端平台、文件大小、私聊授权和断线恢复的逐项记录，因此这些专项验收仍未标记为通过。

上游当前表情 manifest 的 SHA-256 字段与其同提交的实际资源包不一致；Linux 构建固定实际 Git 跟踪资源包的 `2f538e09f787ab7dfac69120be1a7ddf42ff38c4d8521a62aa445a5dd91882f6`，并复核全部 566 个标识、v3 格式、GIF 头、长度和无尾随数据。上游更新资源包后需要先审查并更新这一固定校验值。
