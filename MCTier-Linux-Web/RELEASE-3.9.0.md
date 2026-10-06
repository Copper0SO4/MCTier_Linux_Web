# MCTier Linux Web 3.9.0

Linux x86_64 正式发行。基于官方稳定 MCTier **3.10.0（d6af338）**，Linux Web 使用独立版本号，默认客户端上报版本仍为 3.10.0，并可跟随原版版本检测或由用户手填。

## 本版更新

- 创建/加入表单各保留一个对应按钮，遵循新版信令准入；修复返回按钮竖排，压缩成员卡片。
- 每个成员提供独立的本地音量与静音控件；房主管理支持房主转让、语音禁言等原版操作。
- 软件设置整理成外观与音频、个人资料、会话统计、网络与游戏、大厅与邀请、桌面集成六个标签。网络、游戏、广场和邀请操作有明确入口。
- 补充 EasyTier 高级参数及服务端校验，沿用原版参数映射；不开放的参数在说明中明确列出。
- 新增默认关闭的启动自动组网：保存目标后，下次启动服务先运行 EasyTier，打开浏览器再接续/创建大厅并显示通知。密码采用本机 Secret Service 加密；密码错误等永久注册拒绝停止后台网络并提示原因。浏览器未打开前不能验证大厅密码，也没有大厅同步、聊天或媒体能力。
- 加固会话隔离、取消/退出清理、附件大小限制及虚拟网络 HTTP 直连策略，防止旧请求影响新房间。
- 全面移除 Linux Web Magic DNS，不自动改写已有 hosts。保留 Windows/Android 原版实现。
- 保留 UFW/firewalld 规则预览、两步确认、授权复核和变更后静默重新组网；不以 root 启动 MCTier 或 EasyTier。

## 下载与运行

下载 ZIP 与 `.sha256`，校验并完整解压，以普通用户运行：

```bash
sha256sum -c mctier-linux-web-linux-x86_64-3.9.0.zip.sha256
unzip mctier-linux-web-linux-x86_64-3.9.0.zip
cd mctier-linux-web-linux-x86_64-3.9.0
./mctier-linux-web
```

包内含 Linux 服务可执行文件、启动器、EasyTier core/CLI、中文 `README-Linux.txt`、UFW/P2P 排障手册和高级网络设置说明。启动器在服务就绪后尝试打开默认浏览器；请用 **Chrome/Chromium** 访问 `http://127.0.0.1:14700`。保持终端运行，Ctrl+C 停止服务。

要求 **x86_64、glibc >= 2.39、OpenSSL 3**，例如 Ubuntu 24.04+ 或 Debian 13。旧系统建议在目标系统源码构建，不要自行替换系统 libc。

Debian/Ubuntu 运行依赖：

```bash
sudo apt install curl xdg-utils libcap2-bin policykit-1 libdbus-1-3 libsystemd0 zlib1g libgcc-s1 libssl3
```

t64 系统使用 `libssl3t64`，部分系统使用 `polkitd pkexec`。另需 Chrome/Chromium、桌面认证代理、用户 D-Bus/Secret Service（例如 GNOME Keyring）和 `/dev/net/tun`。无需 Linux Tauri/WebKitGTK 媒体环境。

仅在随包 EasyTier 缺少网络 capability 时，启动器请求 `pkexec setcap`，由用户输入系统密码。服务仅监听回环地址，不会擅自开机启动。自动组网默认关闭；浏览器媒体权限、采集和防火墙操作均需用户操作。

## 验收与限制

**真实环境反馈：** 用户已在当前开发版验收并反馈功能大概正常；此前明确确认组网、文字/文件消息、双向麦克风/屏幕共享、邀请码和房主管理正常，以及 UFW 放行后重新组网可建立 P2P。这不代表所有高级参数、错误密码、跨端管理与长期恢复均已专项测试，也不能保证所有 NAT 环境直连。

**构建与自动化：** TypeScript/Vite/Rust release 构建通过；ZIP CRC、必需文件、可执行权限、二进制一致性及运行库检查通过。历史基线为 22 个前端测试文件（120 用例）及 123 个 Rust 测试；本轮未重跑全套自动化，不把历史结果作为新增启动接续或高级参数的覆盖。没有执行新的真实房间或系统授权测试。

**Firefox 大厅入口仍封锁：** 信令 WebSocket 1006 持续断开，干净配置也曾复现，根因未确认。请使用 Chrome/Chromium，广告拦截也可能阻止信令。ICE、长期重连、firewalld 实机规则、文件夹/工具跨端同步、自动启动生命周期和高级参数各项仍需专项验收。

Linux Web 不支持远程输入操控；系统音频、录屏、托盘和全局快捷键尚未开放。升级不自动删除旧 Magic DNS hosts 段，请按包内说明核对后手工清理。MCTier 信令在线不等于 EasyTier 数据链路或 WebRTC 通话成功。

源码构建与维护方式见仓库 README：使用 Node.js 20+、npm、Rust stable、C 工具链及 OpenSSL/D-Bus 开发头文件；运行 `npm ci`、`scripts/fetch-binaries.sh` 后执行 Linux Web 构建脚本。本轮使用 Rust 1.90.0 构建。
