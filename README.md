# MCTier Linux Web 3.6.0

MCTier 的 Linux 浏览器版发行线，面向 Debian/Ubuntu 系 x86_64 桌面。基于 [MCTier 官方项目](https://github.com/pmh1314520/MCTier)，复用大厅、组网、聊天和 WebRTC 协议，Linux 专属实现位于 `MCTier-Linux-Web/`。

## 下载运行

从 [3.6.0 Release](https://github.com/Copper0SO4/MCTier_Linux_Web/releases/tag/3.6.0) 下载 ZIP 与 SHA-256 校验文件，完整解压并阅读包内中文 `README-Linux.txt`：

```bash
sha256sum -c mctier-linux-web-linux-x86_64-3.6.0.zip.sha256
unzip mctier-linux-web-linux-x86_64-3.6.0.zip
cd mctier-linux-web-linux-x86_64-3.6.0
./mctier-linux-web
```

启动器以普通用户启动本地服务，在服务就绪后尝试打开系统默认浏览器。请用 **Chrome/Chromium** 访问 `http://127.0.0.1:14700`，保持终端运行；Ctrl+C 停止服务。服务只监听回环地址，用户手动加入大厅和开启麦克风/屏幕共享。

只有随包 EasyTier 核心缺少 `cap_net_admin,cap_net_raw=ep` 时，启动器才申请 `pkexec setcap` 系统授权并复核。不要用 sudo/pkexec 以 root 启动整个应用或 EasyTier。

### 运行需求

发行二进制要求 **x86_64、glibc 2.39 或更新、OpenSSL 3**；例如 Ubuntu 24.04+ 或 Debian 13。旧版 Debian/Ubuntu 请在目标系统上从源码构建，不要手动替换系统 libc。

```bash
sudo apt install curl xdg-utils libcap2-bin policykit-1 libdbus-1-3 libsystemd0 zlib1g libgcc-s1 libssl3
```

使用 t64 包名的系统将 `libssl3` 替换为 `libssl3t64`；polkit 包名可能为 `polkitd pkexec`。另需桌面认证代理、用户 D-Bus/Secret Service 密钥环（例如 GNOME Keyring）、`/dev/net/tun` 和已安装的 Chrome/Chromium。发行包自带 EasyTier core/CLI；无需 Linux Tauri/WebKitGTK 媒体环境。

## 3.6.0 更新和验收范围

- 隐藏 Magic DNS 设置、标签页和功能卡片。底层实现保留，设置入口暂不开放；升级不会改写或自动清理已有 hosts 记录。
- 整理电脑端布局、房间内主页/功能状态导航、右侧滚动及弹窗；房间工具使用掷骰子、倒计时、协同待办三个横向标签页。
- 完善公告、人数上限、公开发布/撤销和成员移出等房主管理。
- 高级网络与游戏面板支持参数预览/保存、游戏地址复制和 Minecraft 查询；参数保存后下次手动加入生效。
- 普通 EasyTier 节点使用同端口 TCP/UDP 双监听，UFW/firewalld 按实际监听生成规则。变更成功复核后静默重启当前 EasyTier，保留节点、身份、虚拟 IP 和端口；重连失败明确提示。退房时批量撤销会跳过重连。
- 防火墙修改需预览并完整输入确认文字，授权由用户完成；撤销不要求额外文字。退出房间可选择撤销或保留本应用更改。备用暂停影响整个防火墙，须单独确认。

用户此前实测确认组网、文字/文件消息、双向麦克风和屏幕共享、邀请码及房主管理可用；本次反馈确认 UFW 放行后重新连接可以建立 P2P。自动重连新增流程尚待真实房间复测，这次反馈不代表所有 NAT 环境都能直连。信令大厅在线、EasyTier 对端收发和 WebRTC 媒体需分别判断。

**Firefox 大厅入口仍封锁**：干净配置也曾出现信令 WebSocket 1006 持续断开，根因尚未确认。推荐 Chrome/Chromium；广告拦截扩展也可能阻止信令连接。ICE/长时间断线恢复、firewalld 实机规则、文件夹共享/工具跨端同步和高级网络各选项仍需专项验收。远程输入操控不支持，系统音频/录像、托盘等尚未开放。

## 当前源码新增行为（尚未发行）

创建大厅和加入大厅分别由信令服务器校验：创建要求名称未占用，加入要求大厅已存在；注册成功后的断线恢复沿用原版 auto 模式。旧私有信令服务器若未提供新版能力字段，会明确提示管理员升级。

高级网络设置可填写“客户端上报版本”，支持 `3.10` 或 `3.10.0` 等数字格式。默认值启动时通过原版固定 Gitee 标签接口检测，沿用原版校验与版本排序；初始/检测失败使用当前源码基线 **3.10.0**，不切换查询来源。手填值优先，可点击“跟随上游默认版本”恢复。检测结果只影响下次手动进房，当前会话和重连保留原值；不会自动安装更新，也不改变实际协议能力。Linux 发行号、源码基线与上报版本分别记录。

2026-10-06 补充修复：旧诊断响应不会误退出新房间，文字消息发送结果绑定原会话；媒体诊断失败不再误报本地服务离线，刷新期间不暂时清零语音统计。

本次还修复连接状态同步、取消/退出清理和 UFW/firewalld 切换时的出站选项状态。2026-10-05 继续完善会话隔离、传输大小限制和取消清理：防火墙操作只重连预览对应的 EasyTier 实例，重连失败单独提示；退出会取消未完成的附件上传，关闭共享面板会取消其并发请求。虚拟网络聊天/文件传输直接访问已验证的对端地址，不继承系统 HTTP 代理、不跟随重定向；信令服务的浏览器连接方式不变。

新增 Magic DNS 独立标签页和功能入口，按原版身份派生 `.mct.net` 域名；预览并完整输入确认文字后，由用户完成系统授权，只更新 Linux Web 的 hosts 段。成员变化需手动更新，退出后记录保留，可在该页清理；遇到未闭合/重复标记时拒绝修改。

成员列表提供每人 **0–100% 本地音量和本地静音**，仅影响自己的语音播放；房主可发送转让与语音禁言/解除请求，以信令回报为准。语音禁言不禁止文字消息。

设置页整理为“外观与音频 / 个人资料 / 会话统计”标签页。头像压缩至最长边 256 像素、JPEG，沿用原版加密头像消息同步；昵称下次进房生效。资料保存在本浏览器。统计展示本次页面消息、信令重连和当前语音 RTP，不包含屏幕/文件流量，不是 EasyTier 总流量。

已通过 **22 个前端测试文件（120 个用例）、131 个 Rust 测试和完整 Debug 构建**。本轮未启动服务、真实创建/加入、跨端与长期恢复测试尚未进行；本地自动化结果不代表媒体/P2P 已验收。

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
./MCTier-Linux-Web/scripts/package-release.sh 3.6.0
```

服务产物在 `MCTier-Linux-Web/server/target/release/`；ZIP 与校验文件在 `MCTier-Linux-Web/dist/`。同名压缩包已存在时拒绝覆盖。包内含启动脚本、Linux 服务二进制、EasyTier core/CLI、中文依赖说明及 UFW/P2P 排障手册。

## 上游和维护

2026-10-06 重新获取并核对，已同步的官方最新稳定标签 **v3.10.0**（`d6af338`）。当前源码已适配新版创建/加入模式并通过构建与自动化检查，真实大厅仍待验收；已发布的 3.6.0 仍基于此前的 3.9.5 源码。审查结果见 [项目审查](MCTier-Linux-Web/PROJECT-REVIEW-2026-10-04.md)。Linux Web 版本 **3.6.0** 独立于官方 MCTier 的版本，不修改共享协议版本。

以后先检查工作区，运行 `./MCTier-Linux-Web/scripts/sync-upstream.sh --stable` 获取并审查官方最新稳定标签；清晰保存本地修改后才组合 `--stable --merge` 合并。省略 `--stable` 则跟踪 master。不要覆盖 Linux 补丁或手动改坏 Windows/Android 的共享实现。

手工适配来源记录在 `MCTier-Linux-Web/upstream-sources.json`，上游更新导致检查失败时须先对照 Windows 源码审查，再更新记录，不可直接接受新哈希。架构边界与优化安排见 [架构维护说明](MCTier-Linux-Web/ARCHITECTURE-MAINTENANCE.md)。

接手文档：[`HANDOFF.md`](MCTier-Linux-Web/HANDOFF.md)；本地防火墙排障：[`UFW-P2P-TROUBLESHOOTING.md`](MCTier-Linux-Web/UFW-P2P-TROUBLESHOOTING.md)。每次维护同步更新文档，未测功能不得标记通过。
