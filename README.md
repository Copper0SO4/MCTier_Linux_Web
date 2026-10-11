# MCTier Linux Web 3.10.0

MCTier 的 Linux 浏览器版发行线，面向 Debian/Ubuntu 系 x86_64 桌面。基于 [MCTier 官方项目](https://github.com/pmh1314520/MCTier)，复用大厅、组网、聊天和 WebRTC 协议，Linux 专属实现位于 `MCTier-Linux-Web/`。

## 下载运行

从 [3.10.0 Release](https://github.com/Copper0SO4/MCTier_Linux_Web/releases/tag/3.10.0) 下载 ZIP 与 SHA-256 校验文件，完整解压并阅读包内中文 `README-Linux.txt`：

```bash
sha256sum -c mctier-linux-web-linux-x86_64-3.10.0.zip.sha256
unzip mctier-linux-web-linux-x86_64-3.10.0.zip
cd mctier-linux-web-linux-x86_64-3.10.0
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

## 3.10.0 更新和验收范围

- 2026-10-11 重新拉取官方仓库并核对 latest 非预发行版，仍为 v3.10.0（`d6af338`）；保留已合并的官方 master 维护提交 `61cb9f5`。没有新的稳定源码需要合入。
- 修复此前共享信令协议 JSON 和设置入口变化造成的自动化测试失配；信令/语音测试直接使用当前官方协议清单。
- ZIP 发布构建固定 Rust/C/C++ 的通用 x86_64 目标，避免继承构建机 `native` CPU 优化；打包前校验发行号与 Cargo 版本一致，拒绝覆盖已有 ZIP 或校验文件。
- 启动器的本地健康检查明确绕过 HTTP 代理，保持前台运行和 `--stop` 退出；启动提示与 Firefox 封锁状态一致。
- 聊天身份/密码密钥环错误提供中文解锁与 Secret Service 排障提示；继续使用原版身份签名和凭据存储，不自动重置身份。
- ZIP 补齐本项目和 EasyTier 许可证。现有大厅、网络、房主管理、成员音量、文件和媒体能力保留；Magic DNS 和远程输入的产品决定不变。

用户在当前开发版反馈“功能大概都正常”；此前明确反馈组网、文字/文件、双向麦克风与屏幕、邀请和房主管理可用，UFW 放行后重新组网可以 P2P。这是用户实测反馈，不代表每项高级参数、错误密码、多标签租约或长期重连均已专项验收。信令大厅在线、EasyTier 对端收发和 WebRTC 媒体需分别判断。

**Firefox 大厅入口仍封锁**：干净配置也曾出现信令 WebSocket 1006 持续断开，根因尚未确认。使用 Chrome/Chromium；广告拦截扩展也可能阻止信令。ICE/长期恢复、firewalld 实机规则、文件夹/工具跨端同步及高级参数各项仍需专项验收。远程输入操控不支持；系统音频、录屏、托盘等尚未开放。

## 功能与行为说明

网页返回按钮使用独立的横排样式，避免原版迷你窗口按钮尺寸导致文字换行。主页创建/加入入口进入各自的表单，表单只显示一个对应的提交按钮，按回车也保持当前入口的操作。创建大厅和加入大厅分别由信令服务器校验：创建要求名称未占用，加入要求大厅已存在；注册成功后的断线恢复沿用原版 auto 模式。旧私有信令服务器若未提供新版能力字段，会明确提示管理员升级。

新增 **软件设置 → 大厅与邀请 → 服务启动时自动加入组网**：默认关闭，填写目标/昵称/节点/信令并开启保存，下次启动服务使用该网络参数快照运行 EasyTier。密码使用原版 Secret Service 本机加密存储，配置文件0600，不保存明文密码。保存不会立刻入房；禁用保存会删除目标，不影响当前房间。

网页打开后会接续已有虚拟接口，并以原版“auto”模式由服务器决定加入已有大厅或创建新大厅，成功才弹出“已在房间内”。密码错误等不可重试注册拒绝会提示原因并停止自动组网，不会连接入大厅；临时网络/信令失败保留后台组网，可点击“接续自动组网”重试。浏览器未打开前，EasyTier自身不能验证大厅密码，密码校验在网页信令注册阶段由服务器完成。浏览器未打开时没有大厅成员同步、聊天、语音或屏幕共享；EasyTier组网在线不代表数据已送达。自动组网在关闭网页或浏览器租约过期后保留，明确退出/停止服务会停止。只在下一次服务启动尝试一次；不切换节点，创建/加入由原版auto模式决定，不自动提权，缺CAP/密钥环未解锁等错误明确显示。自动启动生命周期与错误密码边界仍需专项回归。

创建/加入页面的“高级选项”中新增 **EasyTier 高级设置** 入口，房间内也可从网络面板打开。按原版配置与参数映射新增 smoltcp、KCP/QUIC 输入控制、系统子网转发、作为出口节点、中继白名单/RPC/KCP/限速、私有模式、TUN 名称、默认协议、虚拟 IPv6、手动路由、公网映射监听器、TCP/UDP 白名单和 IPv4/IPv6 STUN。保存只影响下次手动加入，旧设置自动补齐新字段默认值；与 WebRTC 信令/ICE 设置分开。完整对照和限制见 [EasyTier 高级设置](MCTier-Linux-Web/ADVANCED-NETWORK.md)。

高级网络设置可填写“客户端上报版本”，支持 `3.10` 或 `3.10.0` 等数字格式。默认值启动时通过原版固定 Gitee 标签接口检测，沿用原版校验与版本排序；初始/检测失败使用当前源码基线 **3.10.0**，不切换查询来源。手填值优先，可点击“跟随上游默认版本”恢复。检测结果只影响下次手动进房，当前会话和重连保留原值；不会自动安装更新，也不改变实际协议能力。Linux 发行号、源码基线与上报版本分别记录。

2026-10-06 补充修复：旧诊断响应不会误退出新房间，文字消息发送结果绑定原会话；媒体诊断失败不再误报本地服务离线，刷新期间不暂时清零语音统计。

本次还修复连接状态同步、取消/退出清理和 UFW/firewalld 切换时的出站选项状态。2026-10-05 继续完善会话隔离、传输大小限制和取消清理：防火墙操作只重连预览对应的 EasyTier 实例，重连失败单独提示；退出会取消未完成的附件上传，关闭共享面板会取消其并发请求。虚拟网络聊天/文件传输直接访问已验证的对端地址，不继承系统 HTTP 代理、不跟随重定向；信令服务的浏览器连接方式不变。

2026-10-06 按用户决定全面移除 Linux Web 的 Magic DNS：没有域名输入框、功能卡、网络标签、服务接口或 hosts 写入助手；EasyTier 的系统 DNS 接管保持关闭。上游 Windows/Android 实现保留。升级不会自动修改 `/etc/hosts`；若曾应用旧功能，请手动核对，只删除 `# MCTier Magic DNS - LinuxWeb` 到对应 `# MCTier Magic DNS End` 的完整段，保留其它记录。标记异常时请先备份并逐行检查，不要批量删除。

房间主页左侧每个其他成员的信息下方直接显示 **0–100% 本地音量滑条和本地静音**，音量与静音并排紧凑显示，无需展开菜单，仅影响该成员在自己电脑上的语音播放；房主可发送转让与语音禁言/解除请求，以信令回报为准。语音禁言不禁止文字消息。

设置页整理为“外观与音频 / 个人资料 / 会话统计 / 网络与游戏 / 大厅与邀请 / 桌面集成”六个标签页。EasyTier 设置、游戏快连、防火墙修复、常用/最近大厅、公开广场、邀请导入/生成均有明确设置入口，功能状态页仅作能力说明；房主管理、文件夹共享与工具保留大厅入口。头像压缩至最长边 256 像素、JPEG，沿用原版加密头像消息同步；昵称下次进房生效。资料保存在本浏览器。统计展示本次页面消息、信令重连和当前语音 RTP，不包含屏幕/文件流量，不是 EasyTier 总流量。

本轮 22 个前端测试文件、127 个 Rust 测试通过；TypeScript/Vite/Rust Release 构建、ZIP 内容/权限/校验及隔离配置下的回环监听、安全边界、SIGTERM 退出检查通过。Rust 网络测试需在允许临时回环绑定的环境运行。本轮没有新的真实房间联机、媒体采集、系统授权或实际软件包安装；历史用户验收与本轮自动化分别记录。

## 密钥环报错排查

如果创建/加入提示“本机密钥环已锁定或不可用”或 `Chat identity credential is locked or unavailable`，请在当前用户桌面会话启动 Secret Service（例如 GNOME Keyring），使用“密码和密钥”等密钥环管理器解锁登录密钥环，再重试。终端必须使用同一普通用户的会话 D-Bus，避免用 sudo 启动。自动登录或修改登录密码后，密钥环密码可能与登录密码不同，需要手动解锁；不要删除原有 `MCTier/chat-identity-p256-v1` 身份条目来绕过错误。

## Arch Linux 安装

Arch打包仓库：[mctier-linux-web-git](https://github.com/Copper0SO4/mctier-linux-web-git)。使用普通用户构建，安装后在终端使用 **`mctier`** 启动，按 Ctrl+C 停止服务；不提供桌面菜单入口：

```bash
sudo pacman -S --needed base-devel git rust nodejs npm
git clone https://github.com/Copper0SO4/mctier-linux-web-git.git
cd mctier-linux-web-git
makepkg -si
mctier
```

这是跟踪本Linux Web仓库master的源码构建包，与固定发行ZIP分别维护，尚未宣称发布到AUR。PKGBUILD声明运行/编译依赖并固定校验随包EasyTier，不在安装阶段启动服务或修改防火墙；第一次启动仅在缺CAP时请求原有setcap授权。详细依赖、升级和权限说明见打包仓库README。

## 源码构建

需要 Node.js 22.12+（Vite 也支持 20.x 的 20.19+）、npm、Rust stable（本轮系统 Rust/Cargo 1.99.0）、C 工具链及 OpenSSL/D-Bus 开发头文件：

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
./MCTier-Linux-Web/scripts/package-release.sh 3.10.0
```

发行打包会固定通用 x86_64 CPU 编译目标；日常源码构建可使用自己的编译参数。运行库需求仍以发行检查结果为准。

服务产物在 `MCTier-Linux-Web/server/target/release/`；ZIP 与校验文件在 `MCTier-Linux-Web/dist/`。同名压缩包已存在时拒绝覆盖。包内含启动脚本、Linux 服务二进制、EasyTier core/CLI、中文依赖说明及 UFW/P2P 排障手册。

## 上游和维护

本版基于已同步的官方稳定标签 **v3.10.0**（`d6af338`），Linux Web 发行号 **3.10.0** 与官方源码版本、客户端上报版本分别记录。共享 `package.json` 保持官方 3.10.0，不因发行号变化而降低协议版本。审查记录见 [项目审查](MCTier-Linux-Web/PROJECT-REVIEW-2026-10-04.md)。

以后先检查工作区，运行 `./MCTier-Linux-Web/scripts/sync-upstream.sh --stable` 获取并审查官方最新稳定标签；清晰保存本地修改后才组合 `--stable --merge` 合并。省略 `--stable` 则跟踪 master。不要覆盖 Linux 补丁或手动改坏 Windows/Android 的共享实现。

手工适配来源记录在 `MCTier-Linux-Web/upstream-sources.json`，上游更新导致检查失败时须先对照 Windows 源码审查，再更新记录，不可直接接受新哈希。架构边界与优化安排见 [架构维护说明](MCTier-Linux-Web/ARCHITECTURE-MAINTENANCE.md)。

接手文档：[`HANDOFF.md`](MCTier-Linux-Web/HANDOFF.md)；本地防火墙排障：[`UFW-P2P-TROUBLESHOOTING.md`](MCTier-Linux-Web/UFW-P2P-TROUBLESHOOTING.md)。每次维护同步更新文档，未测功能不得标记通过。

## 上游 master 同步说明（2026-10-06）

已合并官方 master 的6个后续维护提交至 `61cb9f5`，包括桌面重构、共享信令协议清单及CI配置；官方版本仍为3.10.0。Linux Web改动和首页说明保留。CI检查范围沿用上游，**不包含Linux Web完整构建或真实媒体验收**，详见 [上游架构说明](docs/architecture.md) 与 [CI配置](.github/workflows/ci.yml)。Linux Web仍使用上文独立构建流程。此段记录 2026-10-06 的历史 master 同步；本轮发行见上文。
