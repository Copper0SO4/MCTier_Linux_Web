MCTier Linux Web v3.5.0
Debian / Ubuntu x86_64
======================

【安装运行依赖】

请按系统版本安装以下运行库和桌面工具：

  sudo apt update
  sudo apt install xdg-utils libcap2-bin policykit-1 libdbus-1-3 libsystemd0 zlib1g libgcc-s1 libssl3

Debian 13 若 OpenSSL 3 使用 t64 包，请将 libssl3 替换为 libssl3t64。

还需要：
- Debian/Ubuntu 系 x86_64 桌面环境和已安装的 Chrome/Chromium 浏览器。
- 用户 D-Bus 会话及 Secret Service 密钥环（例如 GNOME Keyring）。
- /dev/net/tun 设备及可连接互联网。
- Firefox 当前存在 MCTier 信令 WebSocket 1006 连续断连问题，本版仍封锁 Firefox 大厅入口。请使用 Chrome/Chromium。

不需要安装 Tauri、WebKitGTK 或 GTK WebView。服务使用浏览器本身处理语音/屏幕权限。

【启动】

完整解压本压缩包，在解压目录运行：

  ./mctier-linux-web

程序以当前普通用户启动。本地服务只监听 127.0.0.1:14700；服务就绪后尝试打开系统默认浏览器。若浏览器没有自动打开，请在 Chrome/Chromium 手动访问：

  http://127.0.0.1:14700

保持终端运行；按 Ctrl+C 或关闭终端停止服务。程序不会自动加入房间或连接节点。请在页面中检查并选择 MCTier 信令服务与 EasyTier 节点。信令服务用于大厅和 WebRTC 信令；EasyTier 节点用于虚拟组网。连接失败不会静默切换服务器。

【EasyTier 网络权限】

程序默认以普通用户身份运行。只有随包 easytier-core 缺少 cap_net_admin、cap_net_raw 时，启动器才会通过 pkexec 请求系统授权，并只对该 core 设置 capability 后复核。用户可以取消授权。不要通过 sudo/pkexec 以 root 启动 mctier-linux-web 或整个 MCTier。

【功能与已知限制】

用户真实环境反馈确认：虚拟组网、消息和文件收发、双向麦克风、双向屏幕共享及大厅邀请码正常。应用内“功能状态”页面标明用户已验收、实验性及未开放项目。Firefox 信令重连、ICE/断线恢复细项仍待专项测试；文件夹共享/房间工具等跨端行为以功能菜单状态为准。远程输入操控不支持，系统音频/录屏未开放；房主管理已接入。当前源码另有待验收的高级网络、游戏快连、Magic DNS 与防火墙修复，具体以本包功能菜单为准。

Linux Web 仍为实验性平台移植。遇到连接异常时，分别检查大厅信令状态、EasyTier 虚拟网卡/对端收发以及 WebRTC 媒体状态；成员显示在线并不等于网络或通话已建立。

【校验】

发布页提供 ZIP 的 SHA-256 文件。可使用：

  sha256sum -c mctier-linux-web-linux-x86_64-v3.5.0.zip.sha256

随包 EasyTier core 校验和：
  f1bd60be7a50da84f50732ed4b826b70284c84f05dadbd3fe448429dfe184322

【网络与游戏功能的额外依赖和权限】

若本包提供网络与游戏面板：高级参数保存后下次手动加入生效；游戏快连需用户自行开服。Magic DNS 只在确认后通过 pkexec 更新 /etc/hosts 的 LinuxWeb 段；成员变化需手动更新，退房后请清理。防火墙修复需要本机已安装并启用 ufw 或 firewalld（firewall-cmd），只选正在使用的一个，不自动安装或切换。需要 pkexec 及桌面认证代理。请预览具体端口、区域、虚拟网段和可选动态 UDP 范围后再授权；动态范围会影响其它程序，不能保证 P2P/NAT 打洞成功。授权助手只执行固定的一次性操作，不以 root 启动服务或核心。

ufw 规则持久保存，退出后可在面板撤销；firewalld 运行时规则 1 小时后失效，不写永久配置。不会关闭/重置防火墙，不开放本地管理服务 14700/RPC。新网络功能是否经真实环境验收，请看应用功能状态，不将已有语音/聊天验收等同于网络修复通过。

系统授权：认证完成后再次核对状态才提交操作。可关闭面板或取消操作；等待认证最多180秒。请求已提交后取消/超时可能部分应用，需检查系统状态及撤销入口，不保证回滚。新网络功能尚待真实系统验收，本说明用于下一次打包，本轮不生成发行包。
