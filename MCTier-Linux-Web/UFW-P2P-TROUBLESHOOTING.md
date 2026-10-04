# UFW 下 EasyTier P2P 排障与处理

更新：2026-10-04。本文记录 MCTier Linux Web 上 UFW 与 EasyTier P2P 的已知现象、实测结论和后续处理步骤。结论只适用于文中明确记录的测试条件；不要把“成员在线”“虚拟 IP 可 ping”或 WebRTC 通话状态当作 P2P 证据。

## 先区分两条连接

- **MCTier 信令服务**负责大厅、房间成员和 WebRTC 信令；WebSocket 显示在线，只说明信令侧连接正常。
- **EasyTier 节点/peer**负责虚拟网卡和组网数据。它可以通过 P2P 直连，也可以经节点中继。判断组网是否 P2P，要看目标 peer 的连接状态和路由跳数。
- **WebRTC 媒体**有独立的 ICE/音频/屏幕连接状态。EasyTier P2P 成功不代表 WebRTC 成功，反之亦然。

## 现象和已验证结果

报告的原始现象是：UFW 开启时，手机 peer 经 relay；暂停 UFW 并重新加入后出现 P2P。随后进行了一次独立 EasyTier 对照测试：

- 使用随包 EasyTier v2.5.0，网络名 `MCTier-Codex`、空密钥、`aes-256-gcm` 和当前默认节点；MCTier 房间服务没有参与。
- UFW 保持启用。经用户授权，在 UFW IPv4/IPv6 的 `before-input` 链顶端临时加入规则，只接受接口 `wlo1` 上 UDP 目的端口 `32768–60999` 且 conntrack 状态为 `INVALID` 的流量。
- 目标 peer `phone`（`10.126.126.52`）显示 `p2p`、隧道 `tcp`；路由为 `DIRECT`、`path_len=1`，延迟约 20 ms，确实不是 relay。
- 但手机与电脑当时在同一 Wi-Fi 局域网。核心日志显示对端 `192.168.10.108` 连接本机 `192.168.10.107:11010`；电脑原本已有 TCP/11010 入站放行。临时 UDP 规则在 IPv4 和 IPv6 的计数都为 0。
- 因此，本次 P2P 更符合**同一局域网内通过既有 TCP 监听端口直连**。不能归因于临时 UDP 规则，也没有验证公网 NAT 打洞或解决原有 WAN relay 问题。

测试后临时规则已按精确匹配删除；EasyTier 以 Ctrl+C 正常退出，临时虚拟网卡和进程均已消失。UFW 始终启用，没有写入持久 UFW 配置。

完整记录见 [`FIREWALL-AUDIT-2026-10-04.md`](FIREWALL-AUDIT-2026-10-04.md)。

## UFW 可能影响 P2P 的位置

1. UFW 默认入站策略可能拒绝没有匹配规则的新入站连接；出站通常允许，但应以本机 `ufw status verbose` 为准。
2. EasyTier 的 bootstrap 节点地址和本机监听地址/端口不是一回事。修正后的 MCTier Web 普通节点模式在同一本地主端口开启 TCP/UDP，WebSocket 节点继续使用 WS/TCP。不能因为节点地址是 `tcp://…:11010` 就推断本机端口也是 11010；请以网络面板的本次主端口为准。
3. EasyTier UDP/TCP 打洞会额外绑定随机本地端口。主监听端口的放行不代表这些打洞 socket 的包都能通过。Linux 动态范围可从 `/proc/sys/net/ipv4/ip_local_port_range` 读取；当前已记录的值为 `32768–60999`，以后应重新读取，不能硬编码为所有机器的范围。
4. UFW 的 `before-input` 规则在 `ufw-user-input` 用户规则之前运行。本机链中有 `conntrack INVALID` 丢弃规则；如果一个包确实先被判为 INVALID，普通 `ufw allow` 规则排在其后，不能覆盖前面的 DROP。但目前没有证据证明原 relay 测试中的 EasyTier 打洞包被标成 INVALID；本次临时例外规则计数为零。
5. 对称 NAT、运营商 CGNAT、路由器限制、对端防火墙或不同 EasyTier 配置也可能让打洞失败。UFW 允许端口不是 P2P 保证；仍可能正确退回 relay。

## 已实施的修正：补齐 TCP 主监听与放行

独立核心默认开启 TCP/11010；旧 Linux Web 为普通节点显式指定的主监听只有 UDP，修复功能也只为这个 UDP 主端口生成规则。刚才观测到的成功路径是 TCP 主监听直连，这暴露了两种启动方式的具体差异。

当前源码已补齐：普通节点在同一实际主端口配置 TCP/UDP 两个监听，加入时检查两个协议的端口可用性；用户指定固定端口若冲突会明确报错，自动端口则选择同时可用的端口。“重新组网”保留原端口并重启这两个监听。UFW/firewalld 修复预览同时包含同一主端口的 TCP 和 UDP；可选出站规则也与两种实际监听一致。动态打洞范围仍是另一个可选项。WebSocket 节点保留原 WS/TCP 监听。

使用修正后的源码时，先重启本地服务并手动重新加入，再重新预览/确认应用修复规则。只刷新旧服务页面或保留旧 UDP-only 规则都不会补出 TCP 监听/规则。旧规则账本按原计划撤销；新增 TCP 规则有独立标记，撤销仅删除本次拥有的规则，已有管理员 TCP 放行仍保留。必要时在高级设置选择固定主端口，避免完整退出再加入时随机端口变化；“重新组网”保持当前端口。

这项修正提供了已观测成功的 TCP 连接路径；本轮不进行真实房间、防火墙授权或跨家宽测试，不能据此宣布所有 relay 已解决。之前 UDP INVALID 规则零命中的证据不支持默认增加该例外，修复功能没有绕过 UFW 前置 INVALID DROP。

实现：[`runtime.rs`](server/src/runtime.rs) 的监听/端口分配、[`network_operations.rs`](server/src/network_operations.rs) 的预览、[`firewall.rs`](server/src/firewall.rs) 的两种协议规则与旧账本兼容。

## 建议的排查顺序

### 1. 固定同一个对端和网络条件

记录测试时间、手机是否和电脑处于同一局域网、目标 EasyTier peer 名称/IP、两端 EasyTier 版本及 NAT 状态。不要在测试中切换信令服务器、EasyTier 节点、网络名或密钥。信令大厅成员在线只用来确认大厅侧正常。

### 2. 确认是否真的是 relay

同时看目标 peer 和路由：

- 目标 peer 显示 `p2p`，且它的路由 `DIRECT` / `path_len=1`：一跳直连；记录隧道是 UDP 还是 TCP。
- 路由 `path_len>1` 或下一跳是另一个 peer：当前仍经中继。
- 只有接口在线、信令成员在线、聊天成功或 ICMP 可达，都不足以证明 P2P。

若使用独立 EasyTier CLI，查询命令形式为（RPC 端口必须是该核心实际设置的本机回环端口）：

```bash
easytier-cli --rpc-portal 127.0.0.1:<RPC端口> peer
easytier-cli --rpc-portal 127.0.0.1:<RPC端口> route
```

不要将 RPC 监听到 `0.0.0.0` 或防火墙放行到外网。MCTier Web 管理的核心使用本机随机 RPC 端口时，优先使用应用的连接诊断，而不要猜端口。

### 3. 核对当前 UFW 与 EasyTier 监听

只读检查：

```bash
sudo ufw status verbose
sudo ufw status numbered
sudo ss -lntup
cat /proc/sys/net/ipv4/ip_local_port_range
```

如果需要判断 `INVALID` 规则是否先于用户规则并查看计数：

```bash
sudo iptables -nvL ufw-before-input --line-numbers
sudo ip6tables -nvL ufw6-before-input --line-numbers
```

把结果和 EasyTier peer/route 快照放在同一时间窗口内。UFW 默认日志可能不含足以确认 conntrack 状态和目标 peer 的信息；单凭过去的 `UFW BLOCK` 记录不能断定是哪条 P2P 流量被拦。

### 4. 用 MCTier 网络修复面板测试精确规则

确认系统实际使用的是 UFW 后，在 MCTier 网络修复面板预览并核对本次规则：

- 普通节点的主规则现在同时放行当前主端口的 TCP 和 UDP，提供实测中成功的 TCP 直接连接路径；WebSocket 节点保持 TCP。不要照抄独立测试的 `11010`。
- 需要时才勾选额外动态 UDP；若启用了 TCP 打洞，预览还会涉及动态 TCP。该范围可能让其它程序的匹配流量也通过，不是按进程授权，默认保持关闭更安全。
- 只有本机确有出站限制时才考虑 UFW 出站选项；它同样按协议/本地端口匹配，并非只匹配 EasyTier 进程。
- 确认提示、范围和系统授权窗口后应用规则。然后点“重新组网”或退出并重新加入，让 EasyTier 重新协商，再检查同一目标 peer 的 route。

若使用设备自身显示为同一局域网的私网地址建立一跳连接，先确认是本地 LAN 直连；这不能替代公网打洞测试。

## 处理结果如何解读

- **同一 LAN 下能 P2P，跨家宽时仍 relay**：说明本地监听与 LAN 路径可直连，跨网络的 NAT/路由路径仍可能受限。保留两端 NAT 状态和同步时段的 UFW/peer/route 证据。
- **应用规则后仍 relay，规则计数为零**：这些规则没有匹配到观测流量，可能是协议/端口不符、包未到达本机、或流量不属于该规则范围；不能再盲目扩大端口。
- **观察到 `before-input` 的 INVALID DROP 计数/包证据对应本次打洞**：普通 `ufw allow` 不能覆盖更早的 drop。先确认包方向、接口、协议、来源与目标，再设计足够窄的 `before.rules` 例外并记录撤销办法。放行动态范围内的所有 INVALID UDP/TCP 会扩大对所有程序的影响；没有同一时段的包证据时不要添加。
- **规则生效但仍 relay**：可能是 NAT/CGNAT、对端限制、上游节点发现或网络策略问题。UFW 规则不能强制对称 NAT 建立直连；relay 可能是预期回退。

暂时没有已验证的“公网 relay 必然可被一条 UFW 规则修复”的方案。暂停整个 UFW 后偶然观察到 P2P，只能作为诊断线索；它会影响所有程序且降低主机防护，不能作为日常修复。若要比较 UFW 开关，需明确授权、限定短时间并恢复原状态。后续验收按用户要求采用家宽场景，本轮不进行额外联网测试。已确认的 TCP 监听与放行缺口直接按下节修正；跨家宽 P2P 的实际效果仍待以后验收。
