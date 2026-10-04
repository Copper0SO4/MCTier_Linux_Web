# MCTier Linux Web v3.5.0

本目录提供 Debian/Ubuntu 系 x86_64 的 Linux 本地 Web 服务及浏览器界面。用户手动运行服务；发行包在服务就绪后打开默认浏览器到 `http://127.0.0.1:14700`。服务只监听 loopback，加入房间、启动 EasyTier、麦克风和屏幕采集均由用户操作。

## 下载运行

从 GitHub Releases 下载并完整解压 `mctier-linux-web-linux-x86_64-v3.5.0.zip`，查看压缩包内中文 [`README-Linux.txt`](packaging/README-Linux.txt)，运行解压目录中的 `mctier-linux-web`。发行包包括启动脚本、Linux 服务二进制、EasyTier core/CLI 与运行说明；使用普通用户权限。

运行环境：Debian/Ubuntu 系 x86_64 桌面、Chromium/Chrome、用户 D-Bus/Secret Service、`/dev/net/tun` 及运行库 `xdg-utils libcap2-bin policykit-1 libdbus-1-3 libsystemd0 zlib1g libgcc-s1 libssl3`（Debian 13 可能为 `libssl3t64`）。仅当 EasyTier core 缺少 TUN capability 时才通过系统授权窗口对该 core 设置 capability。程序本身不以 root 启动。Firefox 信令 1006 断连尚未修复，加入大厅入口封锁。具体限制见应用“功能状态”。

## 源码构建

构建工具：Node.js 20+、npm、Rust stable、C 工具链、`pkg-config`、OpenSSL/D-Bus 开发文件。Debian/Ubuntu：

```bash
sudo apt install build-essential pkg-config libssl-dev libdbus-1-dev curl unzip
npm ci
./MCTier-Linux-Web/scripts/fetch-binaries.sh
./MCTier-Linux-Web/scripts/build-web-server.sh
./MCTier-Linux-Web/scripts/run-web-server.sh
```

`fetch-binaries.sh` 固定下载并验证随包 EasyTier；它不授予系统权限。发行压缩包构建：

```bash
./MCTier-Linux-Web/scripts/package-release.sh v3.5.0
```

压缩包及 SHA-256 位于 `MCTier-Linux-Web/dist/`；重复同名归档会被拒绝，不会覆盖。运行自动化检查：`./MCTier-Linux-Web/scripts/test.sh`。

## 本次发布验收

用户真实反馈确认组网、消息、文件收发、双向麦克风/屏幕共享与大厅邀请码正常；本轮又确认房主管理功能可用。当前源码接入人数上限、公开发布/撤销、公告和成员移出，扩大电脑屏幕上的大厅工作区，并修复右侧内容滚动、重排房间工具等弹窗。上一轮滚动与菜单布局已获验收，改动尚未纳入 `v3.5.0` 发行包。Firefox 连续断开、ICE 与断线恢复细项、文件夹共享跨端访问及房间工具同步仍受限或待专项复核。Linux Web 明确不支持远程输入操控；屏幕观看与共享可用。系统音频/录屏尚未开放。功能状态菜单给出逐项说明。下次发行按用户约定使用不带 `v` 的版本号。

每次维护阅读并更新 [`HANDOFF.md`](HANDOFF.md)；它记录架构、共享协议、上游对齐、自动化结果及尚未验证的功能。Linux Web 变更应限定在本目录和项目 README，不改 Windows/Android 原版行为。

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
