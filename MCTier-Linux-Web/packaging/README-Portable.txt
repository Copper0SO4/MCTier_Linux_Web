MCTier Web 3.10.0-1 — Debian 13 amd64 免安装包

解压后在此目录以普通用户运行 ./mctier-web，手动访问 http://127.0.0.1:14700。
服务在当前终端前台运行，按 Ctrl+C 正常退出；不会主动打开浏览器。

启动器校验随包 EasyTier v2.5.0 核心并检查网络权限；缺少权限时通过 pkexec 请求系统授权，只对该核心设置 cap_net_admin,cap_net_raw+ep 并复核。授权取消或失败时不启动服务。请勿用 sudo 启动整个应用；移动或替换核心后可能需要重新授权。

Debian 13 运行依赖：libc6、libdbus-1-3、libgcc-s1、libssl3t64、libcap2-bin、pkexec。打包构建基于 Debian 13 amd64，其他系统未验证。
默认不自动加入网络；若先前在设置中明确启用启动自动组网，服务启动时会尝试恢复。信令入房需要打开网页后接续。Firefox 大厅入口仍不可用。
