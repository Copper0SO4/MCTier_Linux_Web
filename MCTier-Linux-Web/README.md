# MCTier Linux Web

MCTier Linux 的独立浏览器入口与本地服务端。它使用 Chromium/Chrome 或 Firefox 作为媒体运行环境：用户手动启动服务，再手动访问 `http://127.0.0.1:14700`。不会自动启动浏览器、加入房间或运行 EasyTier。

本目录位于你的 MCTier 分支仓库中。当前宿主代码已快进到分支 `master` 的 `6c73b564`（MCTier 3.9.0）；Linux 功能最初以官方 `dbb7bbd8`（3.8.0）为基线，已将共享前端补丁重新套到当前 3.9.0 源码并复核构建。它是供继续放在 MCTier 源码仓库中、单独向官方上游提交的 Linux 功能目录；构建时从仓库根目录读取共享的 `src/`、`src-tauri/src/modules/`、`package.json` 和锁文件。单独克隆本目录的 GitHub 仓库时，还需要相同版本的 MCTier 上游源码作为宿主。

## 当前进展

- 独立 Rust/Axum 服务默认只监听 `127.0.0.1:14700`；验证 Host、Origin、CSRF、浏览器会话和请求范围，不提供任意本机路径接口。拒绝以 root 启动。
- 大厅、成员、MCTier 信令注册、聊天签名/加密、EasyTier 参数和 WebRTC 逻辑沿用 MCTier 现有协议及共享实现。信令服务负责大厅和 WebRTC 信令；EasyTier 节点负责虚拟组网。
- 用户点击后才启动指定 EasyTier 节点并注册信令。未加入房间不会触发网络连接。
- 浏览器麦克风、语音、变声、聊天、房间成员与 ICE/RTP 状态界面已接入。Firefox 反复以 1006 断开的情况尚未查明或修复；不能因 Chrome 可用就认定 WebRTC 完整。
- 聊天附件下载使用原版签名、成员认证和加密附件端点，浏览器保存时限为 64 MiB；发送附件和共享文件夹暂未迁移。
- 屏幕共享/观看沿用原版信令和 WebRTC 屏幕协议，界面有共享列表刷新、播放恢复和连接诊断。手机对 Linux、Linux 对手机的画面均未在本轮真实联机验收。
- 远程输入操控尚未接入。屏幕共享只采集视频，不采集系统音频。Magic DNS、快捷键与自动启动也未迁移。
- 构建和自动化测试通过；尚无真实房间、Firefox/Chrome 人工测试、跨端聊天/附件、EasyTier 对端数据收发或 WebRTC 语音/屏幕验收。本目录不宣称这些能力已通过。

## 目录安排与上游更新

- `web/`：本地浏览器界面。
- `server/`：独立本地服务、嵌入式静态资源和 Linux 服务测试。
- `patches/frontend/`：仅保存 Linux 适配对共享前端文件的小补丁；构建准备器不调用 Tauri，直接基于当前上游树应用补丁。
- `server/shared/`：服务端抽出的共享 Rust 实现。
- `resources/binaries/`：本地下载的 EasyTier v2.5.0；二进制不入 Git。
- `frontend-src/`、`web-dist/`、`server/target/`：构建生成目录，不提交。

`scripts/prepare-frontend.mjs` 每次都从宿主仓库当前 `src/` 生成新的构建副本，再应用 `patches/frontend/`。它不会覆盖上游工作树。上游调整了相同代码而导致补丁无法套用时，构建会明确失败；维护者检查冲突后更新小补丁，再运行自动化测试。更新上游后也要重新审查服务端复制的共享 Rust 模块及协议字段。

更新到官方上游新版本时，在宿主仓库根目录执行：

```bash
git fetch upstream
git merge upstream/master
```

然后重新编译。若 Linux 补丁与上游同一段代码冲突，应先对照新上游改写对应补丁；不要直接拿旧的 `frontend-src/` 构建。共享 Rust 文件 `server/shared/chat_service.rs`、`chat_transport.rs`、`config_types.rs` 与 `network_arguments.rs` 也应对照更新后的 `src-tauri/src/modules/` 复核。测试文件、服务端协议及 Linux 实现均在本目录，适合作为单独目录加入 MCTier 上游 PR。

## 工具链

构建主机需有：

- Debian 系 Linux x86_64（目标平台）；其他发行版未确认。
- Node.js 20 或更高版本、npm。
- Rust stable 工具链（`rustc`、`cargo`）。
- C 编译器和 OpenSSL/pkg-config 开发依赖（如 Debian/Ubuntu 的 `build-essential`、`pkg-config`、`libssl-dev`）。
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

截至 2026-10-03，前端 5 个测试文件通过，服务端 71 项测试通过；TypeScript 检查及 release 构建通过。构建日志与测试记录位于忽略提交的 `MCTier-Linux-Web/verification/`。

## 真实环境验收仍待完成

需要分开测试与记录：Chrome/Firefox 手动访问及 loopback 监听；EasyTier 虚拟接口和对端数据收发；大厅、公开/私聊及文件下载；浏览器与 Android/Windows 对端之间双向麦克风、信令/SDP/ICE、断线重连、屏幕互看及资源清理。远程操控仍未实现。未进行的项目不能标记为通过。

真实房间联机或触发系统授权窗口前，应先明确告知所用房间、节点和操作，获得用户确认。
