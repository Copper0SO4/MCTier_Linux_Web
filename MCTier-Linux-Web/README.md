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

用户真实反馈确认组网、消息、文件收发、双向麦克风/屏幕共享与大厅邀请码正常。Firefox 连续断开、ICE 与断线恢复细项、文件夹共享跨端访问及房间工具同步仍受限或待专项复核；远程操控、系统音频/录屏、Magic DNS 和完整房主管理未开放。功能状态菜单给出逐项说明。

每次维护阅读并更新 [`HANDOFF.md`](HANDOFF.md)；它记录架构、共享协议、上游对齐、自动化结果及尚未验证的功能。Linux Web 变更应限定在本目录和项目 README，不改 Windows/Android 原版行为。
