# MCTier Linux Web

**MCTier 的 Linux 浏览器版正式发行线** · Debian/Ubuntu 系 x86_64

本仓库基于 [MCTier 官方项目](https://github.com/pmh1314520/MCTier)，保留原版房间、组网、聊天与 WebRTC 协议，为 Linux 提供本地 Web 服务和浏览器界面。Windows 与 Android 原版实现保持在上游共享代码中。

> Linux 发行版 `v3.5.0`。此版本号按 Linux Web 发行线使用；上游 MCTier 有独立的版本和历史标签。

## 下载与运行

从 [Releases](https://github.com/Copper0SO4/MCTier_Linux_Web/releases) 下载 Linux x86_64 压缩包，完整解压后阅读包内 `README-Linux.txt`，运行解压目录中的 `mctier-linux-web`：

```bash
unzip mctier-linux-web-linux-x86_64-v3.5.0.zip
cd mctier-linux-web-linux-x86_64-v3.5.0
./mctier-linux-web
```

启动器会检查随包 EasyTier 核心完整性和 TUN capability，以普通用户启动本地服务；服务就绪后调用系统默认浏览器打开 `http://127.0.0.1:14700`。如果系统没有默认浏览器，手动用 Chromium/Chrome 访问该地址。退出终端或按 `Ctrl+C` 会停止服务。不要以 root、`sudo` 或 `pkexec` 启动 MCTier。

### 运行依赖

目标为 Debian/Ubuntu 系 x86_64 桌面系统。依赖包名随发行版版本略有差异：

```bash
sudo apt update
sudo apt install xdg-utils libcap2-bin policykit-1 libdbus-1-3 libsystemd0 zlib1g libgcc-s1 libssl3
```

Debian 13 若仓库使用 OpenSSL 3 t64 包，请将 `libssl3` 换成 `libssl3t64`。另外需要：

- 已安装 Chrome/Chromium 或其他可用桌面浏览器；Firefox 的 MCTier 信令曾持续以 WebSocket `1006` 断开，当前版本仍封锁 Firefox 加入大厅。
- 桌面会话、用户 D-Bus 与可用的 Secret Service 密钥环（GNOME Keyring 等）。
- `/dev/net/tun`。只有随包 EasyTier 核心缺少 `cap_net_admin,cap_net_raw` 时，启动器才请求 `pkexec` 授权单独设置该二进制 capability，并复核结果。可取消授权；不得用 root 运行整个程序。
- 网络连接到用户选择的 MCTier 信令服务与 EasyTier 节点。两者用途不同，应用不会在连接失败时静默切换服务器。

不需要 Tauri、WebKitGTK 或 GTK 应用窗口。浏览器麦克风和屏幕权限在用户点击对应功能时由浏览器询问。

## 已验收功能与限制

用户在实际环境确认：虚拟组网、消息收发、文件收发、双向麦克风、双向屏幕共享和大厅邀请码可用。内置动画表情已纳入发行构建。主界面的“功能状态”菜单逐项展示验收范围和当前限制。

Linux 版本仍有实验性边界：Firefox 大厅连接仍封锁；ICE/断线重连、文件夹共享和房间工具同步等需要更完整的跨设备验证；远程操控、系统音频/屏幕录像、完整房主管理、Magic DNS 和桌面托盘尚未开放。信令成员在线不代表 EasyTier 数据链路或媒体连接成功。

## 从源码构建

构建环境需要 Debian 系 x86_64、Node.js 20+、npm、Rust stable、C 编译器、`pkg-config`、OpenSSL 和 D-Bus 开发包：

```bash
sudo apt install build-essential pkg-config libssl-dev libdbus-1-dev curl unzip
npm ci
./MCTier-Linux-Web/scripts/fetch-binaries.sh
./MCTier-Linux-Web/scripts/build-web-server.sh
```

开发产物：`MCTier-Linux-Web/server/target/release/mctier-linux-web`。前台启动服务后，手动打开浏览器访问 `http://127.0.0.1:14700`：

```bash
./MCTier-Linux-Web/scripts/run-web-server.sh
```

构建完整发行压缩包（需要已下载并校验 EasyTier 二进制）：

```bash
./MCTier-Linux-Web/scripts/package-release.sh v3.5.0
```

产物及 SHA-256 校验文件位于 `MCTier-Linux-Web/dist/`。打包会重建 release 服务，压缩包含启动器、Linux 服务可执行文件、EasyTier core/CLI 和中文运行说明；已有同名压缩包不会覆盖。

运行自动化检查：

```bash
./MCTier-Linux-Web/scripts/test.sh
```

## 上游同步与维护

官方源码 remote 为 `upstream`，本仓库分支为 `origin`。先检查工作区，再运行 `./MCTier-Linux-Web/scripts/sync-upstream.sh` 获取并审查官方更新；审查通过后可用 `--merge` 合入。不要把 Linux Web 独有版本号当成上游版本，也不要覆盖 Windows/Android 共享实现。

维护者接手指南、协议边界、功能矩阵和测试记录见 [`MCTier-Linux-Web/HANDOFF.md`](MCTier-Linux-Web/HANDOFF.md)。Linux Web 实现、构建与打包脚本位于 [`MCTier-Linux-Web/`](MCTier-Linux-Web/)。
