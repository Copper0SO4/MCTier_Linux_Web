# MCTier Linux Web 3.6.0

Linux x86_64 正式发行，基于官方 MCTier 3.9.5，已同步 upstream/master 的依赖维护更新 e8d792d。Linux Web 使用独立版本号。

## 本版更新

- 隐藏 Magic DNS 设置、标签页及功能卡片，保留底层实现；不会自动改写已有 hosts。
- 整理电脑端 UI、房间内导航、右侧滚动和网络诊断；房间工具采用三个横向标签页。
- 完善房主管理、高级网络参数、游戏快连及 Minecraft 发现。
- EasyTier 普通节点补齐同端口 TCP/UDP 双监听，UFW/firewalld 修复规则对齐实际监听。
- 防火墙变更成功复核后静默重连当前 EasyTier，保持节点、身份、虚拟地址及端口；失败明确提示。退出房间时撤销规则不会重连。
- 防火墙操作保留预览/输入确认与系统认证；支持撤销、暂停/恢复及退出时的清理选择。

## 下载和运行

下载 ZIP 和 `.sha256`，完整解压，阅读中文 `README-Linux.txt` 后，以普通用户运行 `./mctier-linux-web`。包内有服务可执行文件、随包 EasyTier core/CLI、中文依赖说明及 UFW/P2P 排障手册。服务就绪后尝试打开默认浏览器；用 Chrome/Chromium 访问 `http://127.0.0.1:14700`。

要求 **x86_64、glibc >= 2.39、OpenSSL 3**（例如 Ubuntu 24.04+、Debian 13）。较旧系统可从源码构建。运行依赖：`curl xdg-utils libcap2-bin policykit-1 libdbus-1-3 libsystemd0 zlib1g libgcc-s1 libssl3`；t64 系统使用 `libssl3t64`，部分系统使用 `polkitd pkexec`。另需桌面认证代理、用户 D-Bus/Secret Service、TUN 和 Chrome/Chromium。

EasyTier 缺少网络 capability 时，启动器才请求 `pkexec setcap` 授权。不要以 root 启动整个应用。服务保持仅监听回环地址，加入房间与媒体采集由用户操作。

## 验收和已知限制

用户已反馈组网、聊天/文件收发、双向麦克风和屏幕共享、邀请码及房主管理正常，并确认 UFW 放行后重新连接可建立 P2P。新自动重连流程尚未在真实房间复测；P2P 成功不能推广到所有 NAT 环境。

Firefox 大厅入口仍封锁：信令 WebSocket 1006 持续断开，干净配置也曾复现，根因未确认。ICE、长期重连、firewalld 实机规则、文件夹共享及房间工具跨端同步、高级参数各项仍待专项验收。远程输入操控不支持；系统音频/录像和桌面托盘尚未开放。

构建/自动化检查：13 个前端测试文件及 125 项 Rust 测试通过，TypeScript/Vite/release Rust 构建及 ZIP 内容校验通过。真实房间、媒体、P2P 与系统授权不由自动化结果代替。
