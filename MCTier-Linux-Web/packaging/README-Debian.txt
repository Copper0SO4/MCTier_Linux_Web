MCTier Linux Web — Debian 13 amd64

安装：sudo apt install ./mctier-web_3.10.0-1_amd64.deb
如已安装此前测试包 3.10.0-2，3.10.0-1 在 Debian 版本序中更低；请使用 sudo apt install --allow-downgrades ./mctier-web_3.10.0-1_amd64.deb。
以普通用户运行：mctier-web
手动访问：http://127.0.0.1:14700
服务在当前终端前台运行，按 Ctrl+C 正常退出。不自动启动浏览器或系统服务。

默认不自动加入网络；如果之前在设置中明确启用启动自动组网，服务启动时会尝试恢复该网络。信令入房需要打开网页后接续。

软件包自带并校验 EasyTier v2.5.0。启动时检查核心的网络 capability；缺少权限时通过 pkexec 请求系统授权，仅对核心设置 cap_net_admin,cap_net_raw+ep，并在授权后复核。取消或授权失败则不启动服务。系统升级或重新安装软件包后可能需要重新授权。请勿用 sudo 或 pkexec 运行整个应用。
Firefox 大厅连接仍不可用；Chrome/Chromium 适合作为实验性浏览器入口。
