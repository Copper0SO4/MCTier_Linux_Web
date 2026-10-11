# MCTier Linux Web 3.10.0

2026-10-11 维护发行，Linux x86_64。官方 latest 非预发行仍为 **v3.10.0（d6af338）**；本仓库已包含该稳定标签及已审查的 master 维护提交 **61cb9f5**，此次重新 fetch 后没有新的稳定源码需要合入。Linux Web 与官方发行号分别维护。

## 本版更新

- 发布构建固定 Rust/C/C++ 通用 x86_64 CPU 目标，避免继承构建机 native 优化。降低旧CPU/虚拟机发生非法指令的风险，未在旧CPU实测，不把该风险修正视为全部崩溃根因已解决。
- 启动器本地健康检查绕过HTTP代理，保留前台监督、Ctrl+C和 `--stop`，防止重复启动误认旧实例就绪。
- 密钥环失败增加中文解锁/Secret Service提示，保留原版身份签名、存储及失败关闭行为，不重置原身份。
- 修复共享协议JSON和设置入口造成的测试fixture漂移；测试直接加载当前官方协议清单。
- 打包前核对Cargo版本并拒绝覆盖旧ZIP/校验文件；随包提供项目和EasyTier许可证。维护、构建和中文使用文档同步。

## 下载与运行需求

选择ZIP或Arch pacman包，下载对应SHA-256清单并校验。ZIP完整解压后，以普通用户执行 `./mctier-linux-web`；Arch安装后执行 `mctier`。启动器就绪后尝试打开系统默认浏览器，请用 **Chrome/Chromium** 访问 `http://127.0.0.1:14700`。保持终端前台运行；Ctrl+C停止，也可在另一终端使用 `mctier --stop`（ZIP用 `./mctier-linux-web --stop`）。不提供desktop入口，不自动开机启动。

要求 **x86_64、glibc >= 2.39、OpenSSL 3、用户D-Bus/Secret Service密钥环（例如GNOME Keyring）和 /dev/net/tun**。Ubuntu24.04+/Debian13等系统建议：

```bash
sudo apt install curl xdg-utils libcap2-bin policykit-1 libdbus-1-3 libsystemd0 zlib1g libgcc-s1 libssl3
```

t64系统将 `libssl3` 替换为 `libssl3t64`；有些系统polkit包名为 `polkitd pkexec`。还需Chrome/Chromium、桌面认证代理和coreutils/awk。较旧系统应从源码构建，不自行替换系统libc。EasyTier core/CLI随包且固定校验，不依赖Tauri窗口/WebKitGTK媒体管线。

只有随包核心缺CAP时才请求用户 `pkexec setcap` 授权并复核，程序和核心以普通用户运行。默认不入房；只有用户已保存并明确开启的启动组网配置才生效。媒体与防火墙需用户主动操作。

聊天身份错误请解锁当前用户的登录密钥环，确认Secret Service运行后重试；不要删除 `MCTier/chat-identity-p256-v1` 以绕过报错，不要用sudo运行。

## 验证和限制

- 本轮22个前端测试文件、127项Rust测试通过；TypeScript/Vite（566表情）/通用x86_64 Rust Release构建、ZIP CRC/内容/执行权限检查通过。使用系统Node26.11.1/npm12.2.0/Rust-Cargo1.99.0，没有使用旧临时工具链。
- 隔离临时配置下服务页面、healthz、CSP、外来Host/跨源请求拒绝、仅127.0.0.1:14700监听和SIGTERM正常退出通过。没有启动EasyTier、加入真实大厅或触发授权。
- 历史用户确认组网、聊天/文件、双向麦克风/屏幕、邀请码、房主管理和UFW修改后重连P2P可用；本轮没有重新执行跨端真实测试、CAP/防火墙或pacman安装验证。
- **Firefox大厅入口仍封锁**：曾在干净配置出现WebSocket1006持续断开，根因未确认。推荐Chrome/Chromium，广告拦截可能阻止信令。
- 长时间媒体/ICE/重连、firewalld真实规则、高级参数、工具/文件夹跨端同步、旧CPU及干净目标系统仍需专项测试。信令在线不等于EasyTier数据可达或媒体成功。
- Magic DNS已全面移除；远程输入不支持；系统音频、录屏、托盘/全局快捷键尚未开放。

构建：`npm ci` → `scripts/fetch-binaries.sh` → `scripts/build-web-server.sh`；以上脚本均位于 `MCTier-Linux-Web/`。自动化为 `scripts/test.sh`，ZIP打包为 `scripts/package-release.sh 3.10.0`。根README含完整依赖、源码维护和Arch安装说明。

## 发行资产和校验

```bash
# ZIP：下载ZIP和对应.sha256
sha256sum -c mctier-linux-web-linux-x86_64-3.10.0.zip.sha256
unzip mctier-linux-web-linux-x86_64-3.10.0.zip
cd mctier-linux-web-linux-x86_64-3.10.0
./mctier-linux-web

# Arch：下载软件包和对应.sha256，在下载目录执行
sha256sum -c mctier-linux-web-git-3.10.0.r384.gc3f6a14-1-x86_64.pkg.tar.zst.sha256
sudo pacman -U mctier-linux-web-git-3.10.0.r384.gc3f6a14-1-x86_64.pkg.tar.zst
mctier
```

| 文件 | SHA-256 |
| --- | --- |
| ZIP | `df1f778fb2ffe1db1ecbd4f231f36907cf77802a429dfcfb7752fa4fbca8c0b1` |
| Arch包 | `f3625a5ec8b830565b43ff3c3be736509fb9940e0dafab11204493086697ce8b` |

Arch包由系统Rust/Cargo通过独立PKGBUILD构建/封包，使用已经准备并验证的源码提交 `c3f6a14`（跳过重新提取/prepare，重新执行完整Linux构建和package）。包体的启动器、服务和核心与此次Arch构建产物逐字节一致；ZIP与Arch独立链接，嵌入的构建路径不同，不宣称两份服务二进制哈希相同。`pacman -Qip`、包体依赖/文件/无desktop校验通过。makepkg仍提示编译路径引用：仅为开发态资源fallback，不依赖临时目录正常运行。实际pacman安装和旧CPU仍待用户验证。
