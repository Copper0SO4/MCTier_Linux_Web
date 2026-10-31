# MCTier Web 3.10.0-1

面向 Debian 13 amd64 的 Linux Web 发行包，提供 `.deb` 和免安装 `.tar.gz`，均自带 EasyTier v2.5.0 核心与 CLI。

- 前台运行 `mctier-web`（免安装包运行 `./mctier-web`），手动访问 http://127.0.0.1:14700；Ctrl+C 停止，不主动打开浏览器。
- 启动时校验核心并检查组网权限，缺少权限时通过 pkexec 请求授权，仅对 EasyTier 核心设置 capability 并复核；授权取消或失败则退出。
- 默认不自动加入网络；用户此前明确启用启动自动组网时，该设置仍生效。Firefox 大厅入口仍不可用。
- 如果已安装此前 `3.10.0-2` 测试包，Debian 将此版本视为降级，请使用 `sudo apt install --allow-downgrades ./mctier-web_3.10.0-1_amd64.deb`。

基于 Debian 13 amd64 构建；其他发行版未验证。用户确认此前测试包在其环境中运行正常；本次还检查了软件包内容、模拟安装、前台启动与 Ctrl+C。未在本轮进行真实跨端联机专项测试。

SHA-256:

```
b5edd6a4a22e5aa5cd3c2d7eb452a6fcec0270b845c7ddcf1eb52bd35e8d060d  mctier-web_3.10.0-1_amd64.deb
993b086037623b19f0545cc14731ca5a243f214d233a146f36b5655fba726804  mctier-web_3.10.0-1_linux-amd64.tar.gz
```
