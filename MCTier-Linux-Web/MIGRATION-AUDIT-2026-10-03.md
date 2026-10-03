# Linux 浏览器架构迁移：首次源码审查

审查日期：2026-10-03（Asia/Shanghai）。这是从官方仓库重新克隆后生成的记录，不使用旧对话、旧交接文档或删除目录中的内容。

## 基线与本次范围

- 官方仓库：<https://github.com/pmh1314520/MCTier>，`master` 提交 `dbb7bbd8da1323f5414e921020a6f33dd1630381`，客户端版本 3.8.0。
- 工作副本：本报告所在仓库。开始时无用户源码改动。由于本机 Git 全局 URL 改写，另用独立 Git 配置直接克隆了 `../official-source`，两份 HEAD 相同。保留两份目录，没有重置或清理已有目录。
- Linux 目录目前包含 README、四个脚本、desktop 条目和 polkit 策略，业务实现在根目录 `src/`、`src-tauri/`，不是 README 所说的另一个 `MCTier桌面应用/` 子目录。
- 阅读 Linux 构建、获取依赖、启动、打包资产，追踪平台分支、网络启动、大厅协调、信令身份、聊天认证、音频采集、WebRTC 协商/重连/清理、屏幕采集与远控入口，并与 Android 对应实现对照。本报告不是逐行证明全部 Windows/Android 代码正确。
- 本次只进行了源码调查、依赖安装、构建检查和自动化测试。没有启动产品服务、浏览器、EasyTier、真实大厅或系统授权窗口。

官网本次读取仍将 Linux 标为实验性 Debian/Ubuntu 系 x86_64：组网已验证，语音、屏幕共享、远程协作受限。历史实测是原作者/报告者的记录，不属于本次验收结果。来源：<https://mctier.pmhs.top/#platforms>。

## 当前结构与迁移差距

| 能力 | 当前代码与证据 | 浏览器迁移需要处理 |
|---|---|---|
| Linux 入口 | `run-linux.sh` 启动桌面二进制、设置 GTK/WebKit 环境；`lib.rs` 创建窗口、托盘及媒体许可回调 | 独立前台服务入口，启动后只显示 `http://127.0.0.1:14700`；无浏览器启动、autostart 或隐式大厅加入 |
| 前端 | `App.tsx`、组件及服务直接使用 Tauri invoke/listen/window/asset 等 API | 明确的平台接口；Linux 构建使用 HTTP + 事件流，Windows 保留原来的接口实现。窗口操作不能假装成功 |
| 后端 | `AppCore`、`NetworkService`、`LobbyManager`、资源路径、事件及命令接口直接依赖 `AppHandle`/`State` | 把路径、配置快照、事件和生命周期从窗口句柄中抽离；HTTP 层调用同一业务服务，避免重写房间协议 |
| EasyTier | Linux 获取脚本固定 v2.5.0、整包及 core/cli SHA-256；运行时通常使用随包资源 | 继续使用验证过的随包 core/cli，不因失败改用 PATH 中其他版本；普通用户启动 |
| 能力预检 | `linux_platform.rs` 请求 `cap_net_admin,cap_net_raw+ep`，但检查函数仅搜索 `cap_net_admin` 子串 | 严格核对能力名称和 effective/permitted 集合、getcap 状态及错误；缺失时才提供明确的 setcap 授权操作并复核 |
| 进程清理 | `network_service.rs` 启动与失败退出路径包含 `pkill -9 -x easytier-core`；启动还扫描旧 `config_mctier-*` 目录 | 只管理本实例子进程和本实例目录。已有其他 EasyTier 实例不能被误杀、误清理 |
| 麦克风 | `nativeMicrophone.ts` 在非 Tauri 浏览器走 getUserMedia；Windows 用 WASAPI + worklet；原声音色绕过变声图 | 保留已有浏览器采集分支；拒绝、撤销、设备丢失、重复开关及页面关闭均需覆盖 |
| 语音协商 | `WebRTCClient.ts` 预建 audio transceiver、prepareAudioAnswer、replaceTrack、ICE 排队、会话代次、心跳和恢复 | 复用这些逻辑；分别验证先协商后开麦、先开麦后建连接、旧对端缺少 transceiver 的 addTrack 分支 |
| 聊天 | Rust `ChatService`/`chat_auth.rs` 承担身份私钥、令牌代次、签名及附件；前端有本机 SSE 读取 | 保留认证协议。浏览器通过本地同源接口读取事件/附件，避免让 API 接受任意 URL 或任意文件路径 |
| 屏幕共享 | 最新 `ScreenShareService.ts` 与 `RemoteControlService.ts` 直接调用 requestNativeScreen；非 Windows 后端返回不支持 | 新增浏览器 getDisplayMedia 采集适配，保留用户选择及停止行为。系统音频、分辨率/帧率只按真实能力报告 |
| 远控 | 信令同意、DataChannel 和输入授权已存在；Linux uinput 三设备，但实测与权限未知 | 保留会话同意及撤销；屏幕来源与坐标关系、设备权限、按键释放和断线清理必须验证。不能假定 logind 总会授予 /dev/uinput 访问 |
| 本机文件 | 现有命令有路径授权表、用户文件选择、链接拒绝和大小限制 | 不把任意 read/write/list 路径命令直接映射到 HTTP。使用服务持有的授权句柄及受限导入/导出接口 |
| CORS | Rust `http_cors.rs` 与 Android `LanCors.kt` 只放行 WebView 与开发来源 | 当前不允许 14700 来源；优先由本地后端复用原生 HTTP 客户端，避免为了兼容旧对端扩大全局 CORS |
| 自启动、hosts、防火墙 | XDG autostart、hosts helper、ufw 授权路径存在 | Linux Web 入口不自动启动/写系统配置；这些能力应由明确的用户操作触发，测试前逐项确认 |

新服务应绑定确定的 `127.0.0.1:14700`；端口占用明确失败，不能悄悄换端口或绑定 `0.0.0.0`。静态页面、API 和事件应同源，校验 Host/Origin，拒绝跨源变更请求；事件连接也需要访问约束。配置和私钥不能作为静态文件发布。静态目录限定为构建产物，处理路径穿越、编码路径和符号链接。上传、请求体、事件队列与并发必须有限额。认证会话与业务会话失效需要分别处理。

这只限制本地控制服务。EasyTier 的外部隧道监听和现有虚拟网卡上的聊天/文件服务是另外的地址边界，不能把它们都改成回环而破坏原版互通。

## 源码中的协议事实

| 组件 | 地址/协议 | 判定成功的依据 |
|---|---|---|
| MCTier 信令 | 默认 `wss://mctier.pmhs.top/signaling`；客户端要求 WSS | challenge → register-v3 → 验证 register-success、权威身份和会话代次 |
| 默认 EasyTier 节点 | `tcp://easytier.weiai.org.cn:11010` | 本实例实际 TUN/IP、peer/route 与对端数据收发 |
| 其他内置节点 | `tcp://225284.xyz:11010`、`udp://us01.225284.xyz:11010` | 用户选定节点；重试不应替换节点 |
| 虚拟网络 | 网络名称 `MCTier-` + 大厅名；network-secret 使用大厅密码 | 双端一致的配置和实际隧道链路 |
| 地址分配 | 当前 `lobby_address.rs` / Android `LobbyAddress.kt` 基于身份与大厅产生 `10.126.126.0/24` 静态候选，碰撞后按代次恢复 | 当前实际分配并被信令认可的地址；Linux README 的默认 DHCP 描述已经过时 |
| 聊天/文件 | 虚拟 IP 上的 14540 / 14539 | 令牌、签名、权限和实际内容传输；成员在线不能替代此结果 |
| 语音 ICE | 桌面语音默认空 ICE servers；Windows 原生采集使用本地 STUN 探测。Android 语音与桌面屏幕使用 QQ/小米 STUN | selected/nominated candidate pair 与双向 RTP 计数、真实听音；不能假设原版已经配置 TURN |

v3 注册包含 protocolVersion、playerName、virtualIp、lobbyName、lobbyPassword、clientVersion、useDomain、identityPublicKey、challengeSignature。权威身份是 P-256 公钥 DER 的完整 SHA-256 指纹，不能为 Linux 随意生成另一个 player ID。Rust 和 Android 签名规范均为领域标记、协议版本、challenge、大厅名、虚拟 IP，以换行连接；私钥留在后端。

register-success 后还要安装 chatToken/chatTokenEpoch、权威成员公钥与 sessionGeneration；重连需要拒绝旧连接/旧代次消息。房主管理、语音小队、屏幕路由和远控已有各自消息类型及信任校验，迁移应继续复用。仓库含一个虚拟网内 WebSocket 服务实现，但主前端当前使用配置的远程信令，不应根据旧 `creatorVirtualIp:8445` 字段猜测服务器入口。本次没有取得独立信令服务器的部署源码，也没有联机核验服务器实际响应；这里只陈述当前客户端源码规定的协议。

版本也存在文档偏差：Linux 下载 v2.5.0；Windows 为同版本去 Npcap 依赖的修改构建；Android 是以 v2.6.0 为基线附补丁的 JNI/FFI 构建，详见当前 `THIRD_PARTY_NOTICES.md`。三端并非同一份二进制或同一 Release，互通须实测。

## 死锁证据、假设与未知

1. 官方 `17848ec33ea7a6569f4a48248ea68e45f4755c12` 实现原声旁路，`dd6d8272309ae7aaa67e53e390cb8cfdda338e4d` 据报告者复测更新文档。当前 `voiceChangerService.process()` 在 none 预设直接返回 rawStream。这与用户描述的调查线索吻合，但本次消息没有可读取的截图，无法确认截图的准确提交和额外 issue。
2. [官方 #42 的复测回复](https://github.com/pmh1314520/MCTier/issues/42#issuecomment-5473308542) 称移除效果链后仍复现。它是报告者实测陈述，不是本次独立复现。
3. 本次读取了 fork 中的 `gst-deadlock-symbolized-threads.txt` 和全局串行补丁的线程栈，以及 `gst-issue-toolkit.md`。两条阻塞栈出现 futex/pthread mutex、gst_pad_send_event_unchecked、push_sticky/check_sticky 和 gst_pad_push_event，支持事件链锁竞争的假设；部分 WebKit 帧没有符号，摘要不足以独立证明完整锁持有关系或所有上游版本都有同一缺陷。一次全局锁补丁失败也不能证明所有应用规避或所有上游修复都不可能。
4. [WebKit #322955](https://bugs.webkit.org/show_bug.cgi?id=322955) 本次读取状态为 RESOLVED/MOVED；报告环境为 Debian 13、WebKitGTK 2.52.6（自建 WebRTC）、GStreamer 1.26.2、Plasma 6 Wayland。维护者于 2026-08-31 评论询问为何在 WebKit 报告，并称 GStreamer 报告已关闭。未看到接受根因或提供修复的结论。
5. [GStreamer #5282](https://gitlab.freedesktop.org/gstreamer/gstreamer/-/issues/5282) 网页访问失败，API 请求返回反机器人 HTML，不能当作 JSON 或 issue 内容。其关闭原因、讨论和当前状态仍未直接核实；上面的“已关闭”只是 WebKit 维护者的间接描述。
6. 当前浏览器采集已默认启用 echoCancellation/autoGainControl，并按设备决定 noiseSuppression；上游报告的复现约束是三者关闭。当前开麦主要使用 replaceTrack，报告描述是在活动连接 addTrack。两处差异需要验证，不能直接把八月报告当作十月 HEAD 的逐路径复现。

工作假设：外部 Firefox/Chromium 能避开 Linux Tauri 所使用的 WebKitGTK 媒体管线，同时暴露浏览器权限、ICE 网卡可见性、SDP 协商、自动播放及页面生命周期问题。迁移完成前只能称“改用浏览器媒体实现”，不能称“语音已修复”。

## 工具链与本次检查

本机为 Arch Linux，不能替代 Debian/Ubuntu 打包和验收环境。

| 工具/资产 | 检查结果 | 用途 |
|---|---|---|
| Node.js / npm | v26.10.0 / 12.2.0；依赖安装成功 | React/TypeScript/Vite 构建与前端自动化测试 |
| rustc / cargo | 1.98.1 | 共享后端与独立服务；实际最低版本需由锁文件验证，不能沿用 README 的 Rust 1.77 提示 |
| C/C++、CMake、pkg-config、OpenSSL | 已发现；CMake 4.4.3 | 图像优化、sherpa 原生依赖及 TLS 构建 |
| GTK3 / WebKitGTK | 3.24.52 / 2.52.6 开发包已发现 | 仅当前桌面基线检查；新服务应从构建和运行路径移除媒体窗口依赖 |
| Firefox | 已发现，未启动 | 后续由用户手动访问并接受媒体权限 |
| Chromium | 未在 PATH 发现 | 可选的第二浏览器验收 |
| getcap / setcap / pkexec | 已发现；经用户确认授予并复核 core capability | 只为随包 EasyTier 授予所需网络 capability |
| ip / ss、curl、unzip | 已发现 | 网卡/监听验证和下载校验 |
| dpkg-deb | 未在 PATH 发现 | 后续 Debian 包构建，Debian 对应包名 `dpkg` |

后续 Debian 构建需按依赖实际选择 `build-essential`、`cmake`、`pkg-config`、`libssl-dev`、`curl`、`unzip`、`libcap2-bin`、`pkexec`/`polkitd`、`iproute2`、`dpkg`。纯服务模式无需为了浏览器语音自建 WebKitGTK。屏幕权限由 Firefox/Chromium 与桌面 portal 协作，KDE/GNOME 对应 portal 后端需要在目标环境确认。系统回环音频若保留原生采集，还需单独评估 `pulseaudio-utils`/PipeWire，不能随语音默认宣称可用。

| 本次验证 | 结果与边界 |
|---|---|
| 前端依赖 | `npm ci --ignore-scripts` 成功，使用官方锁文件 |
| 基线前端自动化 | `npm test` 成功；Node 报告 60 个测试文件通过、0 失败；包含策略、静态源码检查和模拟对象，不能等同真实浏览器 |
| 基线前端构建 | `npm run build` 成功（含 TypeScript）；有 chunk 大小及静态/动态 import 警告 |
| Rust 离线依赖解析 | 缓存缺 cookie_store，离线失败；不是工具链缺失 |
| Linux 本地服务 | 新增独立 Axum 服务骨架并成功 release 构建；实际启动后 `GET /`、静态 CSS/JS、`/healthz` 和 `/api/status` 均返回 200 |
| 服务安全边界 | 实际监听检查显示 `127.0.0.1:14700`；错误 Host 和错误 Origin 均返回 403；无任意路径读取接口；没有自动打开浏览器 |
| EasyTier 资产 | 固定官方下载脚本成功；压缩包、`easytier-core`、`easytier-cli` SHA-256 均通过，放置于 `src-tauri/resources/binaries/linux/`。尚未执行 EasyTier 节点或 capability 测试 |
| 语音模型构建资产 | 保持 manifest 的 20,000,000 字节上限；将 gzip 构建级别设为 6 后重新生成压缩资产，3 项 bundled-speech 自动测试通过 |
| 真实环境验收 | Firefox 尚未手动打开；未加入真实房间、未授权系统 capability；组网、聊天、ICE、双向语音、重连、屏幕共享和远控均未执行 |

## 分阶段实施与验证门槛

1. **平台边界与服务基础。** 已有初始 Axum 服务构建入口、内嵌静态启动页、固定回环 14700 监听、Host/Origin 限制和状态端点；实测构建、启动、HTTP 和监听边界通过。仍需补上 Rust 自动化安全测试、端口冲突提示与生命周期管理，并抽离共享网络/大厅业务依赖的路径和事件接口。逐步复用现有 Rust 业务模块，不使用生产 MockRuntime 或隐藏 Tauri 窗口充当服务。
2. **现有前端与业务接入。** Linux 选择 HTTP/事件适配；复用原版大厅、注册 v3、聊天身份、配置及认证服务。解决路径授权、附件与本机 SSE 访问；服务端转发只能面向当前认证大厅成员及固定协议端点。网络进程改为实例所有权；capability 精确预检/复核；不注册 autostart，不自动加入大厅。
3. **媒体接入与恢复。** 保留原声旁路、变声、语音小队、transceiver/offer/answer/ICE 和现有恢复逻辑；补浏览器麦克风、自动播放失败提示、权限撤销及页面租约清理。屏幕使用 getDisplayMedia 适配；远控输入继续受现有同意/撤销约束。用独立的本地测试信令及模拟故障验证，不能改生产服务器协议来迎合 Linux。
4. **打包与真实互通。** 先完成自动化和构建门槛，再告知用户将使用的房间、信令地址、EasyTier 节点、对端平台及操作；收到确认后进行组网、媒体或授权。Linux 包携带已校验 EasyTier、静态资产和必要许可；安装脚本不启动服务或浏览器。

每一步记录修改依据、与原版差异、检查结果和未覆盖项。若需改共享接口，单独跑 Windows 构建/回归；当前 Linux 构建通过不能替代 Windows 构建。Android 尽量保持源码和现有协议实现，互通结果由真实对端验证。

## 真实验收清单（入口已确认，其余待执行）

| 验收项 | 必须保留的证据 |
|---|---|
| 手动 Firefox 访问 | 用户自行启动服务、手动打开精确 URL；版本、界面、权限操作记录 |
| 监听边界 | ss 显示 127.0.0.1:14700；外部接口不能访问；无新增自动启动项 |
| 普通用户与 capability | 服务/EasyTier UID；core/cli 版本及 SHA-256；缺能力/已具备能力/授权取消/复核失败分支 |
| 虚拟网络 | ip addr/route、EasyTier peer/route；与同大厅对端双向 ping 或 UDP/TCP 数据收发；不能只展示成员列表 |
| 大厅/聊天 | 双端成员变更、公开/私聊、认证后消息到达、离开后权限撤销；房主及小队行为 |
| 双向语音 | Firefox ↔ 一个 Windows/Android 对端；两端权限/采集、offer/answer、ICE selected pair、双向 RTP 增长、真实听音 |
| 重连 | 临时断信令、媒体/网络中断与恢复；旧代次消息拒绝；地址/令牌更新；无重复音轨或重连风暴 |
| 清理 | 关麦、撤销权限、退出大厅、刷新/关标签、停止服务；轨道/定时器/事件连接/自有子进程/网卡释放 |
| 屏幕与远控 | 双向观看、取消选择/停止、平台音频能力、分辨率/帧率实际值；主控/被控分别测试、会话撤销与按键释放 |

## 第二轮实现与复核（2026-10-03）

上面的“本次检查”及阶段描述记录初始基线；以下为后续迭代状态，以本节为准。

- 独立 Linux 服务直接包含共享配置类型、EasyTier 参数、聊天认证与传输模块；桌面命令改为委托相同共享实现。没有引入 Tauri/WebKit 依赖。Windows/Android 保留各自入口，但本机未执行 Windows/Android 完整构建。
- Linux 专用浏览器界面和构建别名复用原版 WebRTC/P2PChatService/小队/变声/注册及地址恢复服务。信令仍直连用户选定的 wss 地址；不模拟注册、不推测服务器行为、不静默更换节点。
- 本地服务为随包核心预检 SHA-256、精确 capability、TUN 和地址；以普通用户启动并仅管理自身子进程与实例目录。普通页面访问不初始化签名身份、不启动 EasyTier。用户确认后已授予 core `cap_net_admin,cap_net_raw=ep` 并复核，CLI 无 capability；实际组网尚未启动。
- 认证本地 SSE 替代浏览器跨虚拟 IP SSE，发送/历史继续使用共享成员认证、签名、加密及固定虚拟 IP 协议。回环 Host/Origin、CSRF、标签页所有权、请求限制和内嵌静态文件白名单形成本地安全边界。页面租约超时、退出和服务终止清理自有网络/聊天会话。
- 媒体保持原版原声旁路和协商/恢复逻辑；补充不含 IP/SDP/凭证的选中 ICE 对及音频 RTP 诊断、手动播放恢复、事件监听注销，以及首次 await 前停止采集轨道。Linux 屏幕改用用户手势触发的 getDisplayMedia，取消后停止晚到轨道，不采集系统音频。远程输入控制、附件下载/发送、共享文件夹、MagicDNS、热键、自启尚不支持。
- 语音模型维持原 hash 和 20 MB 上限，gzip 级别 6；对已验证但过大的旧压缩缓存可离线重压，测试确认解压后模型字节不变。

| 验证 | 当前结果与边界 |
|---|---|
| 前端自动化 | 61 个测试文件通过，0 失败；新增本地适配、浏览器采集取消、媒体诊断和同步清理测试。模拟测试不代表实际媒体成功 |
| 独立 Rust 自动化 | 68 项通过，0 失败；包含认证/加密、同源/CSRF/路径边界、独立标签页、租约及仅清理自有子进程 |
| 原桌面前端构建 | npm run build 通过；有原有 chunk/import 警告 |
| 共享 Rust 编译 | cargo check --locked --offline --lib 通过；未完成 Windows/Android 目标构建 |
| Linux 浏览器与服务构建 | 独立 TypeScript/Vite 和 Rust release 构建通过；没有 Tauri/WebKitGTK 媒体依赖 |
| 手动页面访问 | 用户确认页面可访问、服务在线；新业务界面需用户刷新后继续实际操作 |
| 新服务实际启动 | 已替换旧启动页；新 HTML/JS/CSS、health/status/bootstrap 返回 200；实际 ss 显示只监听 127.0.0.1:14700。错误 Host/Origin、缺少 CSRF 的控制请求返回 403，本机路径探测返回 404；CSP 已复核，重复实例因端口占用退出。服务状态显示没有网络子进程或聊天会话 |
| EasyTier | 官方 2.5.0 资产 hash/版本及已授权 core capability 复核通过；虚拟接口和对端收发仍待测 |
| 真实联机与媒体 | 未连接真实房间；大厅/聊天、麦克风权限、SDP、ICE、双向听音、重连及跨端屏幕共享全部待测 |

验证日志保存在 `verification/2026-10-03/`。尚未完成 Debian 包与 Debian 真机验证。
当前结论：初始入口已升级为复用原版业务的可操作实现；尚不能宣称语音已修复或 Linux 功能完整。截图原件及 GStreamer 工单原文仍是待补证据。

## 首轮加入失败调查

用户手动加入后报告浏览器在建立 WebSocket 阶段报错；没有收到 challenge 的证据，未完成信令注册。失败后的本地状态确认网络与聊天已停止。用户控制台同时出现扩展注入相关 script/style CSP 和 moz-extension 错误，但未报告信令 connect-src 违规，不能据此放宽 CSP 或认定扩展是根因。

本机对同一个 `wss://mctier.pmhs.top/signaling` 发送只读 WebSocket Upgrade（Origin 为本地入口），经正常环境和显式无代理路径均收到 101 与 v3 server-challenge；没有发送注册、房间名或密码。普通 HTTPS GET 返回 502，不能替代 WebSocket 握手结果。curl 超时是因为握手成功后保持连接等待后续数据，本次检查随后断开。Firefox 自身的网络/代理/TLS/服务边缘路径原因仍未定位，需其网络面板证据，不能宣称服务器整体故障或浏览器已修复。

补充手动“检查信令握手”按钮，只检查当前输入地址、不注册房间、不启动 EasyTier；测试覆盖 challenge、错误、超时、无效响应和禁止明文 WS，均验证关闭连接且不发送注册。补充表单无效反馈、签名身份/网络/信令阶段提示及连接期间输入锁定。独立前端与 release 服务重新构建通过，新按钮与资源 HTTP 检查通过；真实 Firefox 握手尚待用户执行。

后续用户执行独立握手检查同样失败，确认问题发生在浏览器信令传输建立阶段，与注册消息或 EasyTier 启动没有依赖关系。用户日志没有 connect-src 违规；script/style 违规和 moz-extension 错误不能作为放宽 CSP 的依据。只读检查当前 Firefox 活跃配置未发现显式关闭 WebSocket 的设置，存在多个活跃扩展；这不证明扩展导致失败。对同一地址发送 Firefox 常用 UA、Sec-Fetch 和 permessage-deflate 请求头的 HTTP/1.1 握手仍收到 101/v3 challenge，无注册。仍需 Firefox 网络面板的失败请求状态和错误以区分 DNS/TLS/代理/拦截/边缘路径。Mozilla 文档指出 WS 筛选仅显示 101 升级请求，采集失败请求应使用“全部”并搜索 signaling。没有更改浏览器代理、DNS、扩展或认证设置。

## 手机到 Linux 消息接收修复（2026-10-03）

用户纠正首轮结果：组网、电脑发送、麦克风收发正常；手机可以观看电脑共享画面，不能据此认定远程输入控制通过。手机发送的消息电脑看不到，双向聊天验收未通过。用户确认最初信令故障来自 AdBlock 拦截；未更改 CSP。

旧服务进程输出反复显示手机端“聊天历史 JSON 无效”，随后出现一次 401；该 401 的原因（例如会话撤销）未知。对照当前 Android `Models.kt` 和 `ChatHttpServer.handleMessages`：ChatWireMessage.messageType 默认 text，历史使用 MctierJson（encodeDefaults=false / explicitNulls=false）编码，因此正常文字消息会省略 message_type。Rust ChatMessage 的该字段原先没有缺省值，解析整份历史失败。这个协议差异有源码和运行日志证据，能解释历史恢复失效；不能单凭它证明所有实时消息丢失都同源。Android 当前发送使用 MctierWireJson（encodeDefaults=true），因此当前 APK 若与 HEAD 一致，实时 POST 的类型字段应存在；用户实际 APK 版本尚未核对。

修复将 Rust ChatMessage/SendMessageRequest 的缺省 message_type 对齐为 Text，只接纳源码定义的默认值，未知类型仍拒绝，签名、加密、重放、成员和收件人校验不变。没有修改 Android 源码或要求重装 APK。此共享兼容修复也供桌面命令使用；共享 Rust 编译和桌面前端构建通过，但未执行 Windows 目标完整构建。

同时修复浏览器入口的恢复缺口：共享服务在聊天认证同步失败时 reset 会清空接收回调/监听，界面原先只在首次加入时安装。Linux 界面现在在重新注册成功或状态检查发现认证已恢复时先安装回调、再启动监听；startPolling 幂等，避免重复定时器。Linux 增加同源、CSRF、控制标签页和有效聊天会话保护的本地历史读取，只返回受原版上限约束的当前会话历史，不访问任意路径或任意远端。即使 SSE 或远端历史不可用，本地已收到的消息也先交给原版去重/过滤逻辑。

界面显示后端远端记录数（包括控制记录）、页面聊天接收数与消息流状态/错误，后端状态不含消息内容、密钥或令牌；两项计数不必相等。

验证：61 个前端测试文件通过；聊天接收聚焦测试 14 项通过；独立 Rust 测试 70 项通过。新增用例覆盖 Android 缺省字段解析、未知类型拒绝、缺省类型请求经过实际加密/签名/接收广播、受认证保护的本地历史及退出后撤销、SSE/远端历史失败补取和 reset 后恢复/幂等监听。新服务 release、桌面前端、共享 Rust 编译通过；服务已更新，页面/静态资源/health/status 检查通过，仅监听 127.0.0.1:14700，无活动房间。日志位于 verification/2026-10-03/chat-receive-*。

真实手机消息接收复测尚未执行，不能标为修复验收通过。后续仍需双向公开/私聊、多条重复文字、手机离开重进/信令重连，以及退出/刷新资源清理验证。

## 屏幕共享阶段信令重连调查（2026-10-03）

用户复测确认聊天接收恢复，随后报告共享/观看屏幕期间电脑反复断开重连。源码中所有业务消息经过串行 Promise 队列，screen-share-offer/answer/ICE 会等待浏览器媒体协商；原 pong 也进入队列，心跳则在收到消息并实际执行处理函数后才清除 5 秒超时。这存在“pong 已到达，但被协商阻塞，客户端主动断开”的可复现缺口。新增测试人为阻塞业务队列，确认接收 pong 能直接清除定时器并保持 WebSocket 打开。此次缺少真实断开时的关闭码/日志，尚不能将该缺口认定为用户故障的唯一根因。

修复只让当前 socket 的合法 JSON pong 在接收处直接确认存活，不进入业务队列；注册认证和业务消息顺序、队列上限、原 15 秒 ping/5 秒超时均保留。旧超时先清除，超时回调仅关闭其捕获且仍为当前连接的 socket，防止关闭替换后的新连接；客户端主动心跳断开使用合法应用关闭码 4000、reason signaling-heartbeat-timeout。未放宽认证或关闭保活，也未改变服务器协议、切换服务地址。

独立前端及 release 服务构建通过；服务实际启动 health 200。真实屏幕共享重连复测仍待执行，应记录关闭码/原因，区分主动心跳超时、4009 队列超限、认证同步失败及远端关闭。

## HAR 与控制台补证、令牌发布顺序修复（2026-10-03）

用户反馈所有场景仍重连，并提供 Firefox HAR 和控制台记录。仅读取必要结构，未将原始记录复制到仓库或输出认证材料。HAR 有 7 个本地 /api/chat/stream 401（响应为“聊天令牌已失效”）、3 个未完成的流请求、2 次 /signaling 101，以及 3 次 get_local_shares 501。后端状态保持 interface-ready/chatRunning，远端已保存记录由 4 增至 10；本地组网与后端入站仍活动。7 次 401 均发生在随后 configure_p2p_chat 请求之前，新前端令牌与当时后端最后提交令牌不一致，支持发布顺序竞态。控制台重复关闭码 1006、空 reason，没有证明此前心跳假设就是实际重连原因。HAR 的流 status=0/未完成不能独立证明服务端关闭流。

根因代码：acceptChatToken 和 register-success 提前调用前端 setChatToken；该调用在监听状态下立即以新令牌发起 SSE，而 configureChatSession 后端提交尚未完成。修复移除提前发布，只在配置成功后的 refreshP2PChatFrontend 发布；没有绕过令牌校验、忽略 401 或延长心跳。新增阻塞后端提交的测试，证明提交前不发布令牌、提交后才发布。

本地 SSE 增加已认证 ready 注释以立即冲刷响应，客户端原解析器忽略注释；后端令牌变化后结束旧代次流，不能让失效认证流持续接收。未迁移的文件共享库存如实返回空数组，保留跨端列表协议，同时附件/文件共享操作仍不支持，不伪造可用状态。

验证：19 项信令聚焦测试、61 个前端测试文件和 70 项 Rust 测试通过；新独立入口、release 服务及桌面前端构建通过。无活动房间时验证精确 PID/程序路径/用户后只重启自有服务，新页面/资源/状态 HTTP 200，无自动联机。

边界：此修复处理有证据的消息流 401 竞态；真实信令 1006 异常中断原因尚未确定，不能宣称全部重连已修复。需要复测令牌轮换期间流是否稳定，以及记录信令实际关闭前的网络状态/服务器侧关闭信息。

## 后续消息/语音短时可用与异常重连补证（2026-10-03）

用户报告部分电脑消息手机收不到，开启麦克风后进入反复重连；连接成功的几秒内双向通话正常。新控制台统计到 33 次 code=1006、空 reason；没有 HTTP 401 或心跳超时文本。这只能说明该控制台未出现此前鉴权竞态错误，不能用缺失日志证明所有 401 都消失。所指定 Downloads HAR 经沙箱与主机只读复核均为 0 字节，无法取得最新网络时序和消息回执。此次没有再次调整超时、放宽认证/CSP 或切换服务器，也未执行真实联机。

当前必须区分浏览器配置/扩展路径与服务器/网络路径。下一步用户先退出原房间并关闭旧页面，用新建且不加载扩展的 Firefox 配置手动访问相同本地服务/相同信令与节点，做短时空闲+开麦复测；此为隔离测试建议，不会自动修改用户现有配置或启动浏览器。同时补充非空 HAR，并确认电脑 1006 时手机信令是否稳定。无最新有效网络记录或服务器侧关闭证据时，不能继续将 1006 归咎于心跳、消息认证、WebRTC 或已知某个扩展。

## 无扩展 Firefox / Chrome 对照（2026-10-03）

用户报告无扩展 Firefox 仍有问题、Chrome 没有同样问题。这缩小范围，但不能证明 Firefox 引擎、CDN、代理或应用中的单一根因。重新检查有效的旧 HAR，两个信令连接均为 HTTP/1.1 请求与响应、101/openresty，Firefox 请求 permessage-deflate 但服务器未协商该扩展；不能将这些样本归为 HTTP/2/3 或压缩连接失败。当前 Firefox --version 为 157.0；只读配置未发现显式覆盖所检查的代理类型/HTTP2 WebSockets/HTTP3/DNS HTTPS RR 偏好（缺省不代表关闭），没有改全局设置。源码没有针对 Firefox UA 的信令分支，媒体 API 差异仍需考虑。

查看 Mozilla #2054551（UNCONFIRMED）及 #2055521（RESOLVED WORKSFORME），它们分别涉及 DNS HTTPS RR/HTTP3 的即时失败、HTTP2 Origin 报告，与本样本已升级为 HTTP1.1 后反复断开的条件不完全吻合，不能作为根因或修复已验证的依据。官方 about:logging WebSockets 预设可在必要时提供更底层证据；不自动收集/上传含认证材料的网络日志。

新增不含候选地址、SDP、令牌的最后关闭诊断：关闭码、wasClean、连接时长、最后收包间隔、排队帧数和心跳等待状态。界面信令栏显示摘要，控制台保留详细对象；用于将传输异常与消息处理阻塞/保活等待区分。20 项信令聚焦测试、61 个前端测试文件和新服务构建通过；无活动房间时更新自有服务。真实 Firefox 关闭诊断仍待复现，重连问题尚未验收通过。


## 后续推进：目录共享、工具、广场与邀请

用户报告文件/图片/表情/语音消息验收正常，先记录并推送 `101c8b5`。本轮继续实施四组功能，全部改动位于 `MCTier-Linux-Web/` 与仓库根 README；没有修改 Windows、Android 或原始 Linux 的实现。

依据：完整对照 `src-tauri/src/modules/file_transfer.rs`、文件分享服务/类型、Android `FileShareHttpServer.kt`；公开广场对照桌面 `publicLobbies.ts` 与 Android `PublicLobbyClient.kt`；邀请使用桌面 `lobbyInvite.ts` 与当前 `secret_store.rs`；房间工具对照 `RoomTools.tsx`、倒计时单例、待办安全解析和 Android todo 消息处理。仓库未提供信令服务端源码，不推断服务器额外行为：发送既有查询/选项消息，界面等待现有确认事件，真实兼容性仍待验收。

主要差异：浏览器不能向服务任意指定本机路径，目录选择上传到服务端临时快照，发布后调用未改动的原版文件 HTTP 服务（EasyTier 虚拟 IP:14539，大厅令牌、共享密码与有效期保持原协议）。上传快照不自动同步源目录，不保留空目录；16 个共享、1024 个文件、总量 256 MiB、单文件/浏览器下载 64 MiB。对端访问只允许当前认证成员，固定端口、绑定本机虚拟 IP，禁止重定向、系统代理和任意 URL/IP。批量/续传 UI 未迁移。正常退出清理，强杀/崩溃后的临时残留不保证自动清理。

待办仍是后写覆盖；倒计时仅本地。收藏采用原版受保护密码，不写明文。邀请保留 v3 格式，二维码生成可保存，图片识别依赖浏览器支持，摄像头扫描未开放。公开列表缺少 EasyTier 节点时禁用加入；只允许房主显式发布/撤销无密码大厅，状态以信令服务确认结果为准。

验证分开记录：TypeScript、Vite、嵌入式 debug 服务编译通过；9 个前端测试文件、91 项 Rust 测试通过。新增测试包括本地目录 API 的同源/owner/CSRF、路径穿越和符号链接、重复名、文件/子目录权限、数量/总量限制、会话轮换和退出清理、摘要不泄露路径/密码、任意对端拒绝、收藏/邀请协议、广场取消/提前关闭/超限及工具/导入按钮流程。按需启用 Deflate ZIP 特性，未引入其他 ZIP 压缩格式的运行库依赖。

真实环境：本轮未加入真实大厅、访问对端目录、公开发布、读取用户实际目录、申请麦克风或触发系统授权。目录跨端传输、密码/到期/撤销/凭据轮换、待办同步、公开广场和手机扫码均待用户首轮测试。Firefox 1006 仍未修复，保持入口封锁。没有启动服务/浏览器，没有构建或发布发行版。
