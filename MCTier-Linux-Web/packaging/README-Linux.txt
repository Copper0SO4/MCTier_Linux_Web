MCTier Linux Web 3.9.0 — 中文使用说明
====================================

【系统和运行依赖】
x86_64 桌面，glibc 2.39 或更新、OpenSSL 3，例如 Ubuntu 24.04+、Debian 13。
较旧的系统请在本机从源码构建，不要自行替换系统 libc。
推荐 Chrome/Chromium。本版 Firefox 大厅入口仍封锁：信令 WebSocket
1006 持续断连曾在干净配置复现，根因未确认。广告拦截也可能阻止信令。

Debian/Ubuntu 按系统版本安装：
  sudo apt install curl xdg-utils libcap2-bin policykit-1 libdbus-1-3 libsystemd0 zlib1g libgcc-s1 libssl3
使用 t64 包名的系统将 libssl3 换成 libssl3t64；部分系统 polkit 包名为
polkitd pkexec。另需桌面认证代理、用户 D-Bus/Secret Service 密钥环
（例如 GNOME Keyring）、/dev/net/tun、coreutils/awk 和互联网连接。
随包已提供 EasyTier core/CLI，不需 Tauri/WebKitGTK 媒体环境。

【校验、解压、运行】
在下载目录执行：
  sha256sum -c mctier-linux-web-linux-x86_64-3.9.0.zip.sha256
  unzip mctier-linux-web-linux-x86_64-3.9.0.zip
  cd mctier-linux-web-linux-x86_64-3.9.0
  ./mctier-linux-web

启动器以普通用户启动本地服务，仅监听 127.0.0.1:14700。
就绪后尝试打开系统默认浏览器；请用 Chrome/Chromium 访问：
  http://127.0.0.1:14700
保持终端运行。Ctrl+C 或关闭终端停止服务。用户手动加入房间、打开
麦克风或共享屏幕；默认不连接房间；若在软件设置→大厅与邀请中明确启用启动自动组网，下一次启动服务连接已保存的EasyTier目标，打开网页后以auto模式接续/创建大厅。密码错误由信令服务器验证并提示，停止自动组网；浏览器未打开时不能验证大厅密码。不会自动启用媒体或防火墙操作。

【EasyTier 权限】
只有随包核心缺少 cap_net_admin,cap_net_raw=ep 时，才通过 pkexec setcap
请求系统授权并复核。由用户在认证窗口输入密码；可以取消。
不要用 sudo/pkexec 以 root 启动整个 MCTier 或 EasyTier。
core SHA-256：
  f1bd60be7a50da84f50732ed4b826b70284c84f05dadbd3fe448429dfe184322

【网络和防火墙】
MCTier 信令负责大厅/WebRTC；EasyTier 负责虚拟组网。大厅成员在线
不代表虚拟接口、对端数据或媒体成功。失败不会自动更换服务器/节点。
普通节点同一主端口监听 TCP/UDP；WS 节点保持 WS/TCP。
高级参数保存后下次手动加入生效；游戏快连需用户自行开服。

网络修复只操作已安装且使用中的 UFW 或 firewalld，不自动安装/切换。
先预览规则，再完整输入“我确认修改本机防火墙”，然后完成系统认证。
额外动态 UDP/TCP 范围及 UFW 出站扩展默认关闭，会影响匹配范围内
其它程序，不能视为按进程放行。14700 和 RPC 不向外网放行。
UFW 规则持久保存；firewalld 仅运行时规则，1 小时后失效。

变更成功复核后，会静默重启当前 EasyTier，沿用节点、身份、虚拟 IP
和主端口。数据会短暂中断，正在传输的文件/媒体可能需恢复。重连失败
明确提示；仍可使用“重新组网”手动重试。退出房间时批量撤销不重连。
撤销规则/恢复状态不要求额外文字，但仍需系统认证。手动退出房间会
询问撤销或保留本应用更改。崩溃/断电不能自动授权撤销，请下次检查。
备用暂停整个防火墙需输入“我确认暂停整个防火墙”，影响所有程序。
UFW 保持关闭直到恢复；firewalld 停止服务可能丢失其它运行时规则。
认证等待最多 180 秒；请求已提交后的取消/超时可能留下部分更改。
详细排障见随包 UFW-P2P-TROUBLESHOOTING.md。

【功能与本版限制】
用户已反馈组网、消息/文件收发、双向麦克风/屏幕、邀请码、房主管理
正常，并确认 UFW 放行后重新连接可以 P2P。当前开发版用户反馈功能大概正常；自动启动错误密码、长期恢复等
边界仍需专项回归；不能保证所有 NAT 环境直连。
Magic DNS 已从 Linux Web 移除，包括设置、服务接口及 hosts 写入助手；升级不自动改写/清理 hosts。
旧版若已写入 hosts，请自行核对 /etc/hosts，只清理 # MCTier Magic DNS - LinuxWeb 至对应 # MCTier Magic DNS End 的完整段，勿删除其它记录。
Firefox、ICE/长期断线恢复、firewalld 实机效果、高级参数各选项、
文件夹共享及房间工具跨端同步仍需专项验收。
Linux Web 不支持远程输入操控；系统音频、录屏、托盘尚未开放。

【发行和源码】
Linux Web 版本 3.9.0 独立于官方 MCTier；源码基于官方稳定版3.10.0
（d6af338）。客户端上报版本默认跟随原版检测，可手填；不代表升级软件。
软件设置采用六个标签页；每个成员卡片支持独立本地音量与静音。
EasyTier高级设置对照见随包ADVANCED-NETWORK.md。正式发行不表示
每项实验性功能已完成专项兼容验收。
源码和构建方式：https://github.com/Copper0SO4/MCTier_Linux_Web
