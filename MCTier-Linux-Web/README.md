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

## 源码构建

需要 Node.js 20+、npm、Rust stable、C 工具链及 OpenSSL/D-Bus 开发头文件：

```bash
sudo apt install build-essential pkg-config libssl-dev libdbus-1-dev curl unzip zip
npm ci
./MCTier-Linux-Web/scripts/fetch-binaries.sh
./MCTier-Linux-Web/scripts/build-web-server.sh
```

构建脚本准备当前上游前端补丁与共享模块，进行 TypeScript/Vite 构建并嵌入 566 项动画表情，再构建 release 服务。EasyTier 下载脚本固定版本并核验哈希，不申请权限。

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

本次已 pull 用户仓库 `origin/master`，并合入官方 `upstream/master` 的 `e8d792d`；官方最新非预发行版仍为 **3.9.5**，之后该分支仅追加 Rust Cargo.lock 依赖维护。Linux Web 版本 **3.6.0** 独立于官方 MCTier 的版本，不修改共享协议版本。

以后先检查工作区，运行 `./MCTier-Linux-Web/scripts/sync-upstream.sh` 获取并审查变更；清晰保存本地修改后才使用 `--merge`。不要覆盖 Linux 补丁或手动改坏 Windows/Android 的共享实现。

接手文档：[`HANDOFF.md`](HANDOFF.md)；本地防火墙排障：[`UFW-P2P-TROUBLESHOOTING.md`](UFW-P2P-TROUBLESHOOTING.md)。每次维护同步更新文档，未测功能不得标记通过。
