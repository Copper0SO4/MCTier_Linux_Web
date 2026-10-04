# MCTier Linux Web

**MCTier 的 Linux 浏览器版正式发行线** · Debian/Ubuntu 系 x86_64

本仓库基于 [MCTier 官方项目](https://github.com/pmh1314520/MCTier)，保留原版房间、组网、聊天与 WebRTC 协议，为 Linux 提供本地 Web 服务和浏览器界面。Windows 与 Android 原版实现保持在上游共享代码中。

> 已发布 Linux 发行版 `v3.5.0`。当前源码后续改动尚未重新发行；下次发行按用户约定使用不带 `v` 的版本号。上游 MCTier 有独立的版本和历史标签。

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

当前源码新增房主管理面板：人数上限、公开发布/撤销、公告以及成员移出沿用原版信令和聊天协议；用户已反馈这些功能可用。桌面大厅使用更宽的聊天区，侧栏及右侧内容可滚动，房间工具等弹窗按任务分组。上一轮滚动与菜单布局已获用户验收，当前改动尚未重新发行。Linux 版本仍有实验性边界：Firefox 大厅连接仍封锁；ICE/断线重连、房主管理跨端结果、文件夹共享和房间工具同步等需要更完整的跨设备验证。远程输入操控按当前产品决定不支持；可观看和共享屏幕。系统音频/屏幕录像和桌面托盘仍未开放。信令成员在线不代表 EasyTier 数据链路或媒体连接成功。

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

## 当前源码新增：网络与游戏（待真实验收）

- 高级网络：MTU、多线程/延迟优先、P2P/中继和打洞开关、KCP/QUIC、压缩、IPv6、使用出口节点、私有子网共享、回环端口转发；保存后**下次手动加入**生效。默认不切换节点、不关闭加密。QUIC 需要固定独立 UDP 端口。提供出口节点、SOCKS5 和 LAN 广播桥仍未开放。
- 游戏快连：原版常用端口模板和当前成员地址复制；Minecraft Java 发现只查询当前大厅虚拟 IP，需用户手动点击并填写实际开服端口。
- Magic DNS：原版身份域名（身份前 32 位 + `.mct.net`）预览后手动申请 pkexec 授权，批量更新 `/etc/hosts` 的 LinuxWeb 标记段；不接管系统 DNS。成员变动需手动更新，退房/停服务后请在面板清理残留记录。
- 网络修复：选择正在使用的 ufw/firewalld，预览实际监听端口及虚拟网段规则，再确认系统授权。只执行固定的一次性助手，MCTier 和 EasyTier 仍是普通用户。ufw 规则持久保存，可在面板撤销；firewalld 运行时规则 1 小时失效。额外动态 UDP 范围默认不勾选，会影响范围内其它程序，不能保证 NAT/P2P 成功。不会启用、关闭或重置防火墙，也不会开放 14700/RPC。
- 房间工具：掷骰子、倒计时、协同待办改为三个横向标签页；维持原版待办协议和本地倒计时语义。

授权操作需要已有 `pkexec`（Debian/Ubuntu 通常为 `policykit-1`）和桌面认证代理；防火墙修复只支持**已安装并启用的** `ufw` 或 `firewalld`（提供 `firewall-cmd`）。请按现有系统选择，不需要同时安装，也不会自动安装/切换。高级设置与游戏快连本身不需新增系统工具；本轮未触发真实授权。构建和测试脚本会先用 `prepare-network.mjs` 从当前上游生成 Minecraft 发现模块，仅移除 Tauri 注解；不需要 Linux Tauri/WebKitGTK 开发依赖。以上源码功能尚未打入已发布的 v3.5.0。

本轮开发检查：TypeScript/Vite/debug 服务构建通过，前端 11 个测试文件及 Rust 112 项测试通过。新网络功能的真实授权、域名解析、游戏联机和 P2P/撤销仍待验收；服务目前关闭，未创建新发行包。

### 当前上游基线

2026-10-04 已合入官方稳定版 [MCTier v3.9.5](https://github.com/pmh1314520/MCTier/releases/tag/v3.9.5)（官方 master/tag 同为 `5efda41`）。本仓库合并提交 `65750e8`；原有 Linux Web 修改及首页文档均保留。更新后完整 debug 构建与自动化套件通过；真实跨端测试本轮未执行。Linux Web 发行号仍独立，已发布的 v3.5.0 压缩包不随源码同步自动更新。

### 2026-10-04 授权与设置完善

系统助手先等待用户认证，认证完成后服务重新核对成员、hosts 或 EasyTier 实例，才通过匿名管道提交固定请求。关闭面板、点击取消、退出大厅或停止服务会取消等待中的操作；认证等待上限 180 秒，提交后等待结果上限 30 秒。如果请求已经提交，取消或超时可能留下部分改动，需检查系统状态并使用撤销入口，不能保证回滚。规则记录以 0600 权限原子替换，读取和写入均拒绝符号链接；损坏的浏览器设置会显示提醒并使用默认值。真实 polkit 授权及 ufw/firewalld、DNS 效果仍待逐项验收。
