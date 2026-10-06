# MCTier Linux Web 3.9.0

MCTier 的 Linux 浏览器版发行线，面向 Debian/Ubuntu 系 x86_64 桌面。基于 [MCTier 官方项目](https://github.com/pmh1314520/MCTier)，复用大厅、组网、聊天和 WebRTC 协议，Linux 专属实现位于 `MCTier-Linux-Web/`。

## 下载运行

从 [3.9.0 Release](https://github.com/Copper0SO4/MCTier_Linux_Web/releases/tag/3.9.0) 下载 ZIP 与 SHA-256 校验文件，完整解压并阅读包内中文 `README-Linux.txt`：

```bash
sha256sum -c mctier-linux-web-linux-x86_64-3.9.0.zip.sha256
unzip mctier-linux-web-linux-x86_64-3.9.0.zip
cd mctier-linux-web-linux-x86_64-3.9.0
./mctier-linux-web
```

启动器以普通用户启动本地服务，在服务就绪后尝试打开系统默认浏览器。请用 **Chrome/Chromium** 访问 `http://127.0.0.1:14700`，保持终端运行；Ctrl+C 停止服务。服务只监听回环地址，默认由用户手动加入大厅和开启麦克风/屏幕共享；可在软件设置中明确开启启动自动组网，详见下文。

只有随包 EasyTier 核心缺少 `cap_net_admin,cap_net_raw=ep` 时，启动器才申请 `pkexec setcap` 系统授权并复核。不要用 sudo/pkexec 以 root 启动整个应用或 EasyTier。

### 运行需求

发行二进制要求 **x86_64、glibc 2.39 或更新、OpenSSL 3**；例如 Ubuntu 24.04+ 或 Debian 13。旧版 Debian/Ubuntu 请在目标系统上从源码构建，不要手动替换系统 libc。

```bash
sudo apt install curl xdg-utils libcap2-bin policykit-1 libdbus-1-3 libsystemd0 zlib1g libgcc-s1 libssl3
```

使用 t64 包名的系统将 `libssl3` 替换为 `libssl3t64`；polkit 包名可能为 `polkitd pkexec`。另需桌面认证代理、用户 D-Bus/Secret Service 密钥环（例如 GNOME Keyring）、`/dev/net/tun` 和已安装的 Chrome/Chromium。发行包自带 EasyTier core/CLI；无需 Linux Tauri/WebKitGTK 媒体环境。

## 3.9.0 更新和验收范围

- 跟进官方稳定源码 3.10.0；创建/加入表单各保留一个对应按钮，重连接续沿用原版协议。
- 整理桌面布局、横排返回按钮和六标签软件设置；网络、游戏、邀请与大厅操作从功能状态说明迁到设置入口。
- 每个成员卡片直接提供独立的本地音量和静音；房主管理支持转让、语音禁言等原版信令操作。
- 补充 EasyTier 高级设置及参数校验，保留普通用户运行、TCP/UDP 双监听和 UFW/firewalld 预览授权流程。
- 新增默认关闭的服务启动自动组网及浏览器接续；配置密码采用本机密钥环加密，密码错误明确提示。
- 修复旧请求污染新会话、取消清理、传输边界和虚拟网络 HTTP 代理问题。
- 按用户决定全面移除 Linux Web Magic DNS，不自动修改或清理已有 hosts。

用户在当前开发版反馈“功能大概都正常”；此前明确反馈组网、文字/文件、双向麦克风与屏幕、邀请和房主管理可用，UFW 放行后重新组网可以 P2P。这是用户实测反馈，不代表每项高级参数、错误密码、多标签租约或长期重连均已专项验收。信令大厅在线、EasyTier 对端收发和 WebRTC 媒体需分别判断。

**Firefox 大厅入口仍封锁**：干净配置也曾出现信令 WebSocket 1006 持续断开，根因尚未确认。使用 Chrome/Chromium；广告拦截扩展也可能阻止信令。ICE/长期恢复、firewalld 实机规则、文件夹/工具跨端同步及高级参数各项仍需专项验收。远程输入操控不支持；系统音频、录屏、托盘等尚未开放。

## 功能与行为说明

网页返回按钮使用独立的横排样式，避免原版迷你窗口按钮尺寸导致文字换行。主页创建/加入入口进入各自的表单，表单只显示一个对应的提交按钮，按回车也保持当前入口的操作。创建大厅和加入大厅分别由信令服务器校验：创建要求名称未占用，加入要求大厅已存在；注册成功后的断线恢复沿用原版 auto 模式。旧私有信令服务器若未提供新版能力字段，会明确提示管理员升级。

新增 **软件设置 → 大厅与邀请 → 服务启动时自动加入组网**：默认关闭，填写目标/昵称/节点/信令并开启保存，下次启动服务使用该网络参数快照运行 EasyTier。密码使用原版 Secret Service 本机加密存储，配置文件0600，不保存明文密码。保存不会立刻入房；禁用保存会删除目标，不影响当前房间。

网页打开后会接续已有虚拟接口，并以原版“auto”模式由服务器决定加入已有大厅或创建新大厅，成功才弹出“已在房间内”。密码错误等不可重试注册拒绝会提示原因并停止自动组网，不会连接入大厅；临时网络/信令失败保留后台组网，可点击“接续自动组网”重试。浏览器未打开前，EasyTier自身不能验证大厅密码，密码校验在网页信令注册阶段由服务器完成。浏览器未打开时没有大厅成员同步、聊天、语音或屏幕共享；EasyTier组网在线不代表数据已送达。自动组网在关闭网页或浏览器租约过期后保留，明确退出/停止服务会停止。只在下一次服务启动尝试一次；不切换节点，创建/加入由原版auto模式决定，不自动提权，缺CAP/密钥环未解锁等错误明确显示。自动启动生命周期与错误密码边界仍需专项回归。

创建/加入页面的“高级选项”中新增 **EasyTier 高级设置** 入口，房间内也可从网络面板打开。按原版配置与参数映射新增 smoltcp、KCP/QUIC 输入控制、系统子网转发、作为出口节点、中继白名单/RPC/KCP/限速、私有模式、TUN 名称、默认协议、虚拟 IPv6、手动路由、公网映射监听器、TCP/UDP 白名单和 IPv4/IPv6 STUN。保存只影响下次手动加入，旧设置自动补齐新字段默认值；与 WebRTC 信令/ICE 设置分开。完整对照和限制见 [EasyTier 高级设置](ADVANCED-NETWORK.md)。

高级网络设置可填写“客户端上报版本”，支持 `3.10` 或 `3.10.0` 等数字格式。默认值启动时通过原版固定 Gitee 标签接口检测，沿用原版校验与版本排序；初始/检测失败使用当前源码基线 **3.10.0**，不切换查询来源。手填值优先，可点击“跟随上游默认版本”恢复。检测结果只影响下次手动进房，当前会话和重连保留原值；不会自动安装更新，也不改变实际协议能力。Linux 发行号、源码基线与上报版本分别记录。

2026-10-06 补充修复：旧诊断响应不会误退出新房间，文字消息发送结果绑定原会话；媒体诊断失败不再误报本地服务离线，刷新期间不暂时清零语音统计。

本次还修复连接状态同步、取消/退出清理和 UFW/firewalld 切换时的出站选项状态。2026-10-05 继续完善会话隔离、传输大小限制和取消清理：防火墙操作只重连预览对应的 EasyTier 实例，重连失败单独提示；退出会取消未完成的附件上传，关闭共享面板会取消其并发请求。虚拟网络聊天/文件传输直接访问已验证的对端地址，不继承系统 HTTP 代理、不跟随重定向；信令服务的浏览器连接方式不变。

2026-10-06 按用户决定全面移除 Linux Web 的 Magic DNS：没有域名输入框、功能卡、网络标签、服务接口或 hosts 写入助手；EasyTier 的系统 DNS 接管保持关闭。上游 Windows/Android 实现保留。升级不会自动修改 `/etc/hosts`；若曾应用旧功能，请手动核对，只删除 `# MCTier Magic DNS - LinuxWeb` 到对应 `# MCTier Magic DNS End` 的完整段，保留其它记录。标记异常时请先备份并逐行检查，不要批量删除。

房间主页左侧每个其他成员的信息下方直接显示 **0–100% 本地音量滑条和本地静音**，音量与静音并排紧凑显示，无需展开菜单，仅影响该成员在自己电脑上的语音播放；房主可发送转让与语音禁言/解除请求，以信令回报为准。语音禁言不禁止文字消息。

设置页整理为“外观与音频 / 个人资料 / 会话统计 / 网络与游戏 / 大厅与邀请 / 桌面集成”六个标签页。EasyTier 设置、游戏快连、防火墙修复、常用/最近大厅、公开广场、邀请导入/生成均有明确设置入口，功能状态页仅作能力说明；房主管理、文件夹共享与工具保留大厅入口。头像压缩至最长边 256 像素、JPEG，沿用原版加密头像消息同步；昵称下次进房生效。资料保存在本浏览器。统计展示本次页面消息、信令重连和当前语音 RTP，不包含屏幕/文件流量，不是 EasyTier 总流量。

自动化历史基线：22 个前端测试文件（120 用例）、123 个 Rust 测试通过。本轮新增高级参数、设置分类与启动接续已通过完整 Debug 构建，用户完成当前开发版总体验收；本次发行执行 TypeScript/Vite/Rust release 构建和包体检查，不把历史自动化数量当作本次新增功能覆盖。未进行新的真实联机或系统授权操作。

## 源码构建

需要 Node.js 20+、npm、Rust stable（本次用官方 1.90.0 验证）、C 工具链及 OpenSSL/D-Bus 开发头文件：

```bash
sudo apt install build-essential pkg-config libssl-dev libdbus-1-dev curl unzip zip
npm ci
./MCTier-Linux-Web/scripts/fetch-binaries.sh
./MCTier-Linux-Web/scripts/build-web-server.sh
```

构建脚本先检查上游版本和手工 Rust 适配模块的来源哈希，再准备前端补丁与共享模块，进行 TypeScript/Vite 构建并嵌入 566 项动画表情，最后构建 release 服务。补丁上下文缺失或匹配多处时停止，要求审查。运行资源只打包应用图标与构建资源，避免复制官网截图等整套 public 文件。EasyTier 下载脚本固定版本并核验哈希，不申请权限。

```bash
# 每次重建 debug 并前台启动；自行打开本地浏览器
./MCTier-Linux-Web/scripts/run-web-server.sh
# 每次重建优化版并前台启动
./MCTier-Linux-Web/scripts/run-web-server.sh --release
# 自动化检查（不执行真实防火墙或房间联机）
./MCTier-Linux-Web/scripts/test.sh
# 打包可执行程序、EasyTier 和中文使用说明
./MCTier-Linux-Web/scripts/package-release.sh 3.9.0
```

服务产物在 `MCTier-Linux-Web/server/target/release/`；ZIP 与校验文件在 `MCTier-Linux-Web/dist/`。同名压缩包已存在时拒绝覆盖。包内含启动脚本、Linux 服务二进制、EasyTier core/CLI、中文依赖说明及 UFW/P2P 排障手册。

## 上游和维护

本版基于已同步的官方稳定标签 **v3.10.0**（`d6af338`），Linux Web 发行号 **3.9.0** 与官方源码版本、客户端上报版本分别记录。共享 `package.json` 保持官方 3.10.0，不因发行号变化而降低协议版本。审查记录见 [项目审查](PROJECT-REVIEW-2026-10-04.md)。

以后先检查工作区，运行 `./MCTier-Linux-Web/scripts/sync-upstream.sh --stable` 获取并审查官方最新稳定标签；清晰保存本地修改后才组合 `--stable --merge` 合并。省略 `--stable` 则跟踪 master。不要覆盖 Linux 补丁或手动改坏 Windows/Android 的共享实现。

手工适配来源记录在 `MCTier-Linux-Web/upstream-sources.json`，上游更新导致检查失败时须先对照 Windows 源码审查，再更新记录，不可直接接受新哈希。架构边界与优化安排见 [架构维护说明](ARCHITECTURE-MAINTENANCE.md)。

接手文档：[`HANDOFF.md`](HANDOFF.md)；本地防火墙排障：[`UFW-P2P-TROUBLESHOOTING.md`](UFW-P2P-TROUBLESHOOTING.md)。每次维护同步更新文档，未测功能不得标记通过。
