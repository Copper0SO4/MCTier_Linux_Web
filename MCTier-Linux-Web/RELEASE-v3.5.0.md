# MCTier Linux Web v3.5.0

Linux 浏览器版首个正式版（Debian/Ubuntu x86_64）。本发行按用户对当前功能的实际验收反馈发布，不表示所有实验性功能都通过了完整兼容性矩阵。

## 已确认可用

- 用户确认：虚拟组网、消息收发、文件收发、双向麦克风、双向屏幕共享和大厅邀请码正常。
- 修复功能菜单卡片按钮重叠，整理大厅布局、窄屏显示和弹窗排版。
- 恢复并随包提供 566 项原版 v3 动画表情。
- 本地服务保持仅监听 `127.0.0.1:14700`；随包 EasyTier 以普通用户身份运行。

## 已知限制

- Firefox 大厅入口仍封锁：信令 WebSocket 曾持续以 `1006` 断开，根因尚未确认。
- ICE 诊断、长时间稳定性与断线重连仍需专项跨端验证。
- 文件夹共享、房间工具同步和公开广场仍为实验性功能。
- 远程操控、系统音频/屏幕录像、Magic DNS、完整房主管理和桌面集成功能尚未开放。

## 下载及依赖

下载本发行附带的 `mctier-linux-web-linux-x86_64-v3.5.0.zip` 与 `.sha256` 文件。完整解压并阅读包内中文 `README-Linux.txt`。运行依赖为 Debian/Ubuntu 系 x86_64、桌面浏览器（推荐 Chrome/Chromium）、用户 D-Bus/Secret Service、`/dev/net/tun`，以及运行库：

`xdg-utils libcap2-bin policykit-1 libdbus-1-3 libsystemd0 zlib1g libgcc-s1 libssl3`

Debian 13 可能使用 `libssl3t64`。启动器以普通用户启动服务并打开默认浏览器；只有 EasyTier 缺少 TUN capability 时才请求授权单独设置随包 core。切勿用 root 启动 MCTier。
