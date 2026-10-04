# 防火墙与 EasyTier P2P 调查（2026-10-04）

用户报告：修复并勾选额外动态 UDP 后仍 relay，手动 disable ufw 后 P2P。本轮没有由 Agent 连接房间、触发 polkit 或修改真实防火墙。

## 已确认的源码及本机证据

1. 随包核心 v2.5.0：[UDP common](https://github.com/EasyTier/EasyTier/blob/v2.5.0/easytier/src/connector/udp_hole_punch/common.rs) 的 socket pool / 打洞路径用 `UdpSocket::bind("0.0.0.0:0")`；[TCP hole punch](https://github.com/EasyTier/EasyTier/blob/v2.5.0/easytier/src/connector/tcp_hole_punch.rs) 的 `select_local_port` 以端口 0 绑定再取 local_addr。因此主 listeners 端口不能描述全部 UDP/TCP 打洞端口。旧可选扩展只覆盖 UDP，当前补 TCP（仅配置启用 TCP 打洞且用户选择扩展时）。本机动态范围为 32768–60999。规则范围扩大对其它程序也有效。
2. 本机 /etc/ufw/before.rules 和 before6.rules 确有 conntrack INVALID 的 log/drop；它们在用户放行规则之前处理。尚未证实用户这次打洞包被判 INVALID，不能把文件内容当成包状态证据。
3. 只读内核日志 today 汇总有 293 项 UFW BLOCK，其中有物理接口的随机 UDP 目标端口，也有 MCTier_Net 上 TCP/14540 的 33 项。没有输出任何对端 IP。日志没有 CTSTATE，时间范围并非精确测试窗口，不能把所有包直接归于本轮失败。调查时账本为空，当前 user.rules 无本应用标记（UFW comment 存储需解码十六进制）；这可能是已撤销的状态，不能倒推测试期间规则不存在。
4. [UFW 手册](https://manpages.ubuntu.com/manpages/focal/man8/ufw.8.html) 的 application profile 是端口配置；[Netfilter owner 手册](https://www.man7.org/linux/man-pages/man8/iptables-extensions.8.html) 的 owner 匹配主要用于本地出站，不能以普通 UFW/firewalld 指令直接复制 Windows 入站按 executable 路径的策略。更复杂的 cgroup/BPF/标记方案需额外架构和验证，随包 v2.5.0 未采用这一方案。没有把端口范围标为“按进程”。

## 本轮更改

- 动态 UDP/TCP 预览、核心拥有的 socket 端口快照（只返回端口；proc 权限不足明确提示）。老账本新增字段默认值保持撤销命令可重建。
- 添加仍需动作文字确认；本应用规则撤销直接申请必要的系统认证。手动退房询问是否撤销全部账本更改，明确含以前残留项；失败可以继续处理或保留退出。
- 单独备用暂停整个防火墙：UFW disable / firewalld 服务 stop，固定 root 助手，不以 root 跑 MCTier/EasyTier。强提示整体影响并需“我确认暂停整个防火墙”。root 私有持久凭证先记录原状态，再变更；文件 nofollow、所有者/权限校验、独占锁与同后端重入检查，避免重复暂停覆盖原启用状态。撤销依据可信凭证恢复，原来 inactive 不擅自 enable；凭证缺失不猜测启用。UFW 持久关闭需手动恢复；firewalld 重启不能完整恢复其它临时规则。
- 服务构建替换了旧运行文件时，申请授权前提示需要重启，避免把已删除 executable 路径交给 pkexec。

## 待真实验证，不能宣称 P2P 已修复

用户确认后在同一对端、同一网络下分别比较：窄规则、动态 UDP/TCP、暂停防火墙。每次保留准确时间、EasyTier peer/route 的 direct/relay 状态和变化、两端 NAT 类型、核心 socket 端口、当时 UFW 规则及 UFW BLOCK/conntrack 证据（脱敏）。若扩展仍被提前丢弃，需对应具体包确认 INVALID 或其它 before/外部规则，不能盲目覆盖管理员规则。添加/撤销/原本未启用/部分失败/取消授权/手动退房恢复及重启后恢复均需实机测试。浏览器 WebRTC ICE 与此处 EasyTier 链路分别验收。

### 2026-10-04 重新组网与出站修复

网络修复面板新增 **重新组网**：用户确认后仅重启本服务拥有的 EasyTier 核心，以普通用户运行，沿用当前房间身份、节点、虚拟 IP、主监听/RPC/QUIC 端口和已加入时的配置，不应用尚未重新加入的设置。信令连接、聊天身份和共享快照不主动重建；数据短暂中断，正在传输的文件可能失败，媒体能否自行恢复须实测。重启失败会清理当前房间资源并提示手动重新加入，不切换服务器、节点或端口。系统授权进行中不能重启；已有未提交预览会作废。成功只表示核心存活与虚拟接口就绪，不代表 P2P 成功。

用户反馈：暂停防火墙后重新加入才转为 P2P；常规规则修复仍 relay。v2.5.0 源码确认随机本地 UDP/TCP 端口来自系统分配，对称 NAT 的全范围随机端口是**对端目标端口**，不能据此开放本机全部入站。已有动态范围和 QUIC/虚拟网段规则保留；新增默认关闭的 **额外放行 UFW 出站**，按本地源端口放行到任意对端端口，并允许当前虚拟 IP 经 MCTier_Net 发往大厅网段。动态范围仍须单独勾选；出站扩展 TCP 范围也覆盖节点连接，不仅 TCP 打洞。按端口匹配会影响其它程序，不是按进程放行。同项管理员 deny/reject 规则不会被覆盖；其它更早规则、INVALID、转发策略、NAT 等仍可能拦截。firewalld 出站 policy 未接入，界面禁用该选项，不把区域入站规则当作出站放行。不开放本地 Web/RPC 到外网，不猜测额外端口或修改系统 conntrack 规则。

常规规则生效后，请先点击重新组网，再对照相同对端的 P2P/relay、数据和媒体恢复。若仍 relay，需要同一时段丢包和规则顺序证据；目前不能宣称防火墙根因已解决。

进一步核对 [both_easy_sym.rs](https://github.com/EasyTier/EasyTier/blob/v2.5.0/easytier/src/connector/udp_hole_punch/both_easy_sym.rs)：new_ext(Some(port)) 重用的是 UdpSocketArray 已绑定的本地端口，并非扫描中的远端随机端口。common.rs 的 1..=65535 shuffled vector 与 sym_to_cone.rs 的目标扫描用于远端 NAT 映射探测，不能作为本机全端口入站授权依据。TCP select_local_port 同样 bind(:0)。当前资料仍没有证明本次 UFW 丢包属于 INPUT INVALID 或出站拒绝，新增出站选项是对现有功能缺口的补充，不是已定位的根因修复。

本轮最终验证：TypeScript/Vite/Rust debug 完整构建通过；13 个前端测试文件共 99 项测试、122 项 Rust 测试通过。新增覆盖重新组网取消/确认、出站默认关闭与 firewalld 禁用、管理员出站策略保护/旧账本兼容、无房间/系统操作并发拒绝、替代子进程退出与启动失败检测。未执行真实 EasyTier 重启、真实授权或规则变更；虚拟接口、对端数据、P2P 和媒体恢复待用户测试。原版 Windows/Android 共享源码没有修改；未提交、推送或发行。


### 2026-10-04 独立 EasyTier 实测：MCTier-Codex

用户确认房间没有设置密码，按空 network secret 直接运行随包 EasyTier v2.5.0；完全未启动 MCTier 房间/信令流程，未改防火墙。节点使用本地 bootstrap 默认 `tcp://easytier.weiai.org.cn:11010`，房间标识 `MCTier-Codex`，临时地址 `10.126.126.57/24`，接口名 `mtierp2ptest`。核心普通用户运行，已有 TUN capability；创建接口成功。与 MCTier 参数对齐 `aes-256-gcm` 后，CLI 见 3 个 peer 项（包含本机/引导连接）；一个远端路由长度为 2，下一跳为 TCP peer。对该 overlay 地址发 2 个 ICMP，2/2 回复，证明组网数据经中继可达，**没有证明 P2P**；该路由仍为两跳，没有观察到目标 peer 直连。另一轮未对齐算法的初测出现 DecryptionFailed，不作为网络/密码故障证据。

EasyTier 本次 listener 列表实际为随机 `udp://0.0.0.0:52344` 与 `udp://[::]:58944`，另有 loopback RPC；下一次运行端口会变化，不能把这些数当固定防火墙端口。EasyTier v2.5.0 TCP/UDP hole punch 源码另会 bind `:0` 取动态本地端口。此次未观测到能佐证目标 peer 的成功 UDP/TCP P2P socket；ICMP 路由路径仍为 relay。未切换节点或协议、未停用/修改防火墙。结束时已 Ctrl-C 正常停核心；复核 `mtp2ptest` 接口与 `easytier-core` 进程均已消失，14700 Web 服务仍只监听 `127.0.0.1`。

结论：空密钥可以加入该引导网络并转发 overlay 数据；本次网络条件下 peer 仍 relay。启动 listener 端口和 hole-punch 随机源端口不是同一回事，单纯放行本次两个随机 listener 不保证 P2P。需要保留同一 peer、路由跳数/transport、接口收发和核心 socket 在有效会话内同步采样，再与防火墙暂停状态比较，才能定位具体阻断。


### UFW 放行测试准备及结果索引（2026-10-04）

用户要求直接 EasyTier 测试时尝试防火墙放行，直到手机 peer 达到 P2P。只读检查显示 `/etc/ufw/ufw.conf` 为 ENABLED=yes 且 systemd 的 ufw.service active；firewalld inactive。`/etc/ufw/before.rules` 与 before6.rules 在用户规则前配置 `conntrack INVALID` 丢弃；常规 UFW `allow` 规则无法先于 before 链执行，因此若打洞包被标记 INVALID，已加的普通入站放行可能无效。授权前的初检环境无法读取内核实际规则计数（`ufw status` 需 root）；之后已按用户授权通过 pkexec 检查、应用并撤销临时规则，完整结果见下文。

随后用户明确授权并完成限时规则测试；规则计数为零，目标 phone 实际经同一 Wi-Fi 下原有 TCP/11010 规则形成一跳 TCP 直连。已撤销临时规则，完整证据见下方“临时放行测试结果”；面向仓库使用者的流程见 [`UFW-P2P-TROUBLESHOOTING.md`](UFW-P2P-TROUBLESHOOTING.md)。


## 临时放行测试结果（2026-10-04）

用户明确授权后，在 UFW 前置 INPUT 链加入 IPv4/IPv6 临时规则：接口 wlo1、UDP dport 32768–60999、conntrack INVALID 时 ACCEPT。UFW 保持 active。使用随包 EasyTier v2.5.0，MCTier-Codex、空密钥、aes-256-gcm 与默认节点。13:27:13 启动后 phone（10.126.126.52）在 peer 表显示 p2p/tcp，route 显示 DIRECT、path_len=1，约 20 ms。EasyTier 日志见对端 192.168.10.108:47926 连入本机 192.168.10.107:11010；设备处于同一 Wi-Fi 局域网，且原本已有 TCP/11010 放行。撤销前临时规则 IPv4 与 IPv6 命中数均为 0。故本次确实观察到 phone 一跳直连，但现有证据指向原有 TCP 监听规则下的局域网直连，不能归因于新增 UDP INVALID 规则，也不能证明公网 NAT 打洞或排除原 relay 问题。临时规则已用精确参数删除并复核不存在；核心正常退出，TUN/进程消失，UFW 保持启用，持久规则未改。后续验收按用户要求使用家宽；本轮不再进行联网测试。


### TCP 主监听与 UFW/firewalld 修复（2026-10-04）

依据 phone 成功的一跳 TCP 直连，普通节点模式现补充同一主端口的 TCP/UDP 监听，网络修复同步放行两种协议；WebSocket 模式保留 WS/TCP。加入前检查两种协议的端口冲突，重新组网保留端口；旧账本继续按原规则撤销，已有管理员规则保留。需要重启本地服务、重新加入，再预览/确认应用新的防火墙规则；旧服务和旧 UDP 规则不会随页面刷新更新。规则不默认绕过 INVALID 丢弃，动态打洞范围仍可选。后续验收采用家宽场景，本轮只构建/自动化检查，没有真实系统授权或 P2P 实测，实际效果待验收。操作指南见 UFW-P2P-TROUBLESHOOTING.md。

本次修正验证：完整 TypeScript/Vite/Rust debug 构建通过，99 项前端测试和 125 项 Rust 测试通过；没有进行真实系统授权或联网验收。

### 防火墙变更后静默重新连接（2026-10-04）

用户家宽验收确认：应用防火墙规则后需重新连接 EasyTier 才能建立 P2P。本次在防火墙规则成功应用/手动撤销或暂停/恢复后，自动静默重启当前受管理的 EasyTier 核心，沿用当前节点、身份、虚拟 IP 和端口；不重启 Web 服务、不重连 MCTier 信令、不额外请求授权。无活动 EasyTier 会话时跳过；退出房间时批量撤销规则则跳过重启即将退出的核心。防火墙变更成功而 EasyTier 重启失败时，结果明确分别报告两者状态，并保留手动重连入口。实现先释放系统操作互斥锁，再调用既有受保护的 `restart_network` 生命周期，避免锁重入。该行为尚未在真实房间复测；重连成功不等于 P2P 已建立。文档及面板提示已同步。
