import { savedReportedVersion, saveReportedVersion, validateReportedVersion, resetReportedVersion, defaultReportedVersion, reportedVersionIsCustom } from './clientVersion';
import { refreshReportedDefault, versionDetectionStatus } from './versionUpdater';
import { localInvoke } from '../frontend-src/services/platform/localWeb';
import type { Player } from '../frontend-src/types';

export type NetworkSettings = {
  listenerPort: number;
  ipv4: string;
  mtu: number;
  multiThread: boolean;
  threadCount: number;
  latencyFirst: boolean;
  bindDevice: boolean;
  compression: string;
  p2pMode: string;
  udpHolePunching: boolean;
  tcpHolePunching: boolean;
  symmetricHolePunching: boolean;
  kcp: boolean;
  quic: boolean;
  quicPort: number;
  disableIpv6: boolean;
  exitNodes: string[];
  proxyNetworks: string[];
  portForwards: { protocol: string; localPort: number; targetIp: string; targetPort: number }[];
};
const defaults = (): NetworkSettings => ({
  listenerPort: 0,
  ipv4: '',
  mtu: 1360,
  multiThread: true,
  threadCount: 2,
  latencyFirst: true,
  bindDevice: false,
  compression: 'none',
  p2pMode: 'auto',
  udpHolePunching: true,
  tcpHolePunching: true,
  symmetricHolePunching: true,
  kcp: false,
  quic: false,
  quicPort: 0,
  disableIpv6: false,
  exitNodes: [],
  proxyNetworks: [],
  portForwards: [],
});
const KEY = 'mctier-linux-web-network-v1';
const make = <K extends keyof HTMLElementTagNameMap>(tag: K, text?: string) => {
  const n = document.createElement(tag);
  if (text !== undefined) n.textContent = text;
  return n;
};
const hint = (text: string) => {
  const n = make('p', text);
  n.className = 'hint';
  return n;
};
const err = (e: unknown) => (e instanceof Error ? e.message : String(e));
type OperationResult = { report: string[]; restart?: { state: 'restarted' | 'failed' | 'skipped' | 'not-requested'; error?: string; reason?: string } };
const partialFailure = (result: OperationResult) => result.restart?.state === 'failed';
type Preview = {
  token: string;
  confirmationText: string;
  title: string;
  lines: string[];
  entries?: { playerName: string; domain: string; ip: string }[];
  commands?: string[][];
};
type Context = {
  online: () => boolean;
  busy: () => boolean;
  players: () => Player[];
  restartNetwork?: () => Promise<void>;
  status: (text: string, error?: boolean) => void;
};

export function setupNetworkPanel(ctx: Context) {
  let settings = defaults(),
    savedError = '';
  try {
    const raw = localStorage.getItem(KEY);
    if (raw) {
      const stored: unknown = JSON.parse(raw);
      if (!stored || typeof stored !== 'object' || Array.isArray(stored))
        throw new Error('invalid settings');
      const base = defaults();
      for (const [key, value] of Object.entries(stored)) {
        if (!(key in base)) throw new Error('unknown setting');
        const defaultValue = base[key as keyof NetworkSettings];
        if (Array.isArray(defaultValue)) {
          if (!Array.isArray(value) || value.length > (key === 'exitNodes' ? 8 : 16))
            throw new Error('invalid list');
          if (key === 'portForwards') {
            for (const f of value) {
              if (
                !f ||
                typeof f !== 'object' ||
                Object.keys(f).length !== 4 ||
                typeof f.protocol !== 'string' ||
                typeof f.targetIp !== 'string' ||
                !Number.isInteger(f.localPort) ||
                !Number.isInteger(f.targetPort)
              )
                throw new Error('invalid forward');
            }
          } else if (!value.every((v) => typeof v === 'string'))
            throw new Error('invalid addresses');
        } else if (
          typeof value !== typeof defaultValue ||
          (typeof value === 'number' && !Number.isInteger(value))
        ) {
          throw new Error('invalid setting type');
        }
      }
      settings = { ...base, ...stored };
    }
  } catch {
    savedError = '已保存的网络设置无法读取；当前使用默认值，请检查并重新保存。';
  }
  const dialog = make('dialog');
  dialog.className = 'community-dialog network-dialog';
  document.body.append(dialog);
  const header = make('div'),
    heading = make('h2', '网络与游戏'),
    close = make('button', '关闭');
  close.type = 'button';
  close.onclick = () => dialog.close();
  const cancelAuthorization = () => {
    if (authorizing)
      void localInvoke('cancel_network_operation').catch((e) => ctx.status(err(e), true));
  };
  header.className = 'community-header';
  header.append(heading, close);
  const body = make('div');
  body.className = 'community-body';
  dialog.append(header, body);
  let generation = 0,
    authorizing = false;
  let leaveDialog: HTMLDialogElement | null = null;
  dialog.addEventListener('close', () => {
    cancelAuthorization();
    generation++;
    body.replaceChildren();
  });
  const run = async (fn: () => Promise<unknown>) => {
    try {
      await fn();
    } catch (e) {
      ctx.status(err(e), true);
      if (dialog.open) {
        const p = hint(err(e));
        p.setAttribute('role', 'alert');
        body.append(p);
      }
    }
  };
  const button = (title: string, fn: () => unknown) => {
    const b = make('button', title);
    b.type = 'button';
    b.onclick = () => void fn();
    return b;
  };
  const field = (title: string, value: string, type = 'text') => {
    const label = make('label', title),
      input = make('input');
    input.type = type;
    input.value = value;
    label.append(input);
    return { label, input };
  };
  const select = (title: string, value: string, options: [string, string][]) => {
    const label = make('label', title),
      input = make('select');
    for (const [id, name] of options) {
      const o = make('option', name);
      o.value = id;
      input.append(o);
    }
    input.value = value;
    label.append(input);
    return { label, input };
  };
  const checkbox = (title: string, checked: boolean) => {
    const label = make('label'),
      input = make('input');
    input.type = 'checkbox';
    input.checked = checked;
    label.className = 'network-check';
    label.append(input, make('span', title));
    return { label, input };
  };

  function open(tab: 'settings' | 'games' | 'dns' | 'repair') {
    if (authorizing) {
      ctx.status('系统授权仍在进行，请先完成或取消认证。', true);
      return;
    }
    generation++;
    body.replaceChildren();
    heading.textContent = '网络与游戏';
    if (!dialog.open) dialog.showModal();
    const tabs = make('div');
    tabs.className = 'tool-tabs';
    tabs.setAttribute('role', 'tablist');
    tabs.setAttribute('aria-label', '网络功能');
    const names = [
      ['settings', '高级网络'],
      ['games', '游戏快连'],
      ['dns', 'Magic DNS'],
      ['repair', '网络修复'],
    ] as const;
    const buttons = names.map(([key, title]) => {
      const b = button(title, () => open(key));
      b.setAttribute('role', 'tab');
      b.setAttribute('aria-selected', String(key === tab));
      b.tabIndex = key === tab ? 0 : -1;
      b.id = `network-tab-${key}`;
      b.setAttribute('aria-controls', 'network-pane');
      tabs.append(b);
      return b;
    });
    tabs.onkeydown = (e) => {
      let i = buttons.indexOf(document.activeElement as HTMLButtonElement);
      if (i < 0) return;
      if (e.key === 'ArrowRight') i = (i + 1) % buttons.length;
      else if (e.key === 'ArrowLeft') i = (i + buttons.length - 1) % buttons.length;
      else if (e.key === 'Home') i = 0;
      else if (e.key === 'End') i = buttons.length - 1;
      else return;
      e.preventDefault();
      open(names[i][0]);
      document.getElementById(`network-tab-${names[i][0]}`)?.focus();
    };
    body.append(tabs);
    const pane = make('div');
    pane.id = 'network-pane';
    pane.className = 'network-pane';
    pane.setAttribute('role', 'tabpanel');
    pane.setAttribute('aria-labelledby', `network-tab-${tab}`);
    body.append(pane);
    // Render into the pane while retaining the shared header/tabs and scroll region.
    target = pane;
    if (tab === 'settings') advanced();
    else if (tab === 'games') games();
    else if (tab === 'dns') void run(dns);
    else void run(repair);
  }
  let target: HTMLElement = body;
  const card = (title: string, collapsed = false) => {
    const s = make(collapsed ? 'details' : 'section');
    s.className = 'community-tool-section' + (collapsed ? ' network-disclosure' : '');
    s.append(make(collapsed ? 'summary' : 'h3', title));
    target.append(s);
    return s;
  };
  function advanced() {
    target.append(
      hint(
        '保存后，下次手动加入大厅时生效；不会自动重连或替换节点。修改后的性能与跨端兼容性需要实测。'
      )
    );
    if (savedError) target.append(hint(savedError));
    const controls = card('连接与性能'),
      grid = make('div');
    grid.className = 'network-grid';
    controls.append(grid);
    const reportVersion = field('客户端上报版本', savedReportedVersion());
    reportVersion.input.maxLength = 24; reportVersion.input.id = 'reported-version-input';
    reportVersion.input.oninput = () => reportVersion.input.setAttribute('data-edited', 'true');
    grid.append(reportVersion.label);
    const versionStatus = hint(versionDetectionStatus()); versionStatus.id = 'reported-version-status';
    controls.append(versionStatus, hint('默认值跟随原版版本检测；手填值优先。只改变信令上报，下次加入生效，不升级程序或协议。'), button('检测上游版本', () => run(async () => {
      const before = savedReportedVersion();
      versionStatus.textContent = '正在查询原版 Gitee 版本接口…';
      try { versionStatus.textContent = await refreshReportedDefault(); if (reportVersion.input.value === before && !reportVersion.input.hasAttribute('data-edited')) reportVersion.input.value = savedReportedVersion(); }
      catch (error) { versionStatus.textContent = err(error); }
    })), button('跟随上游默认版本', () => run(async () => { resetReportedVersion(); reportVersion.input.value = savedReportedVersion(); reportVersion.input.removeAttribute('data-edited'); ctx.status('已恢复跟随上游默认上报版本，下次加入生效。'); })));
    const listener = field(
        'EasyTier 监听端口（0 为每次随机）',
        String(settings.listenerPort),
        'number'
      ),
      ip = field('虚拟 IPv4（留空自动选址）', settings.ipv4),
      mtu = field('MTU', String(settings.mtu), 'number'),
      threads = field('工作线程（2–32）', String(settings.threadCount), 'number');
    listener.input.min = '0';
    listener.input.max = '65535';
    mtu.input.min = '576';
    mtu.input.max = '9000';
    threads.input.min = '2';
    threads.input.max = '32';
    ip.input.placeholder = '10.126.126.1–254';
    const compression = select('压缩', settings.compression, [
        ['none', '不压缩'],
        ['zstd', 'Zstandard'],
      ]),
      mode = select('路由模式', settings.p2pMode, [
        ['auto', '自动：P2P 与中继'],
        ['direct', '仅 P2P（打洞失败会断路）'],
        ['relay', '仅中继（禁用 P2P）'],
      ]);
    grid.append(listener.label, ip.label, mtu.label, threads.label, compression.label, mode.label);
    const flags = make('div');
    flags.className = 'network-grid';
    const protocols = make('details');
    protocols.className = 'network-disclosure';
    protocols.append(make('summary', '打洞与传输协议'), flags);
    controls.append(protocols);
    const checks = [
      ['multiThread', '多线程'],
      ['latencyFirst', '延迟优先'],
      ['bindDevice', '绑定物理设备（解决路由冲突）'],
      ['udpHolePunching', 'UDP 打洞'],
      ['tcpHolePunching', 'TCP 打洞'],
      ['symmetricHolePunching', '对称 NAT 打洞'],
      ['kcp', 'KCP 代理（有损链路 TCP 加速）'],
      ['quic', 'QUIC 代理（需要固定端口）'],
      ['disableIpv6', '禁用 IPv6'],
    ] as const;
    const toggles = checks.map(([key, title]) => {
      const c = checkbox(title, settings[key]);
      flags.append(c.label);
      return { key, input: c.input };
    });
    const quicPort = field('QUIC 固定 UDP 端口（启用时必填）', String(settings.quicPort), 'number');
    quicPort.input.min = '1024';
    quicPort.input.max = '65535';
    protocols.append(
      quicPort.label,
      hint(
        'KCP 和 QUIC 沿用原版 EasyTier 参数；不是浏览器 WebRTC 加速开关。保持 AES-256-GCM 加密，TUN 和虚拟网段固定。'
      )
    );
    const routing = card('子网与出口', true),
      exits = make('textarea'),
      proxies = make('textarea');
    exits.value = settings.exitNodes.join('\n');
    proxies.value = settings.proxyNetworks.join('\n');
    exits.rows = 3;
    proxies.rows = 3;
    exits.maxLength = 512;
    proxies.maxLength = 1024;
    const exitLabel = make('label', '出口节点虚拟 IP（一行一个，最多 8 个）'),
      proxyLabel = make('label', '共享本机私有子网 CIDR（一行一个，最多 16 个）');
    exitLabel.append(exits);
    proxyLabel.append(proxies);
    routing.append(
      exitLabel,
      proxyLabel,
      hint(
        '使用出口节点会改变本机流量路径，需对端已提供出口。子网共享会向大厅成员开放所填私有网段，请只填你希望共享的网段；不能覆盖 MCTier 网段。系统转发、提供出口节点和 SOCKS5 服务仍未开放。'
      )
    );
    const forwards = card('本地端口转发', true),
      rows = make('div');
    rows.className = 'network-forward-list';
    forwards.append(
      hint('仅绑定 127.0.0.1，将本地端口转发到大厅虚拟地址；不会向物理局域网开放代理。'),
      rows
    );
    const editors: {
      row: HTMLElement;
      proto: HTMLSelectElement;
      local: HTMLInputElement;
      ip: HTMLInputElement;
      port: HTMLInputElement;
    }[] = [];
    const addForward = (f: NetworkSettings['portForwards'][number]) => {
      if (editors.length >= 16) {
        ctx.status('最多 16 条转发规则', true);
        return;
      }
      const row = make('div');
      row.className = 'network-forward';
      const proto = select('协议', f.protocol, [
          ['tcp', 'TCP'],
          ['udp', 'UDP'],
        ]),
        local = field('本地端口', String(f.localPort), 'number'),
        host = field('目标虚拟 IP', f.targetIp),
        port = field('目标端口', String(f.targetPort), 'number');
      row.append(
        proto.label,
        local.label,
        host.label,
        port.label,
        button('删除', () => {
          row.remove();
          editors.splice(
            editors.findIndex((v) => v.row === row),
            1
          );
        })
      );
      rows.append(row);
      editors.push({
        row,
        proto: proto.input,
        local: local.input,
        ip: host.input,
        port: port.input,
      });
    };
    for (const f of settings.portForwards) addForward(f);
    forwards.append(
      button('添加转发', () =>
        addForward({ protocol: 'tcp', localPort: 25566, targetIp: '', targetPort: 25565 })
      )
    );
    const split = (s: string) =>
      s
        .split(/\r?\n/)
        .map((v) => v.trim())
        .filter(Boolean);
    const current = generation;
    const save = button('保存，下次加入生效', () =>
      run(async () => {
        const report = validateReportedVersion(reportVersion.input.value);
        const candidate: NetworkSettings = {
          ...settings,
          listenerPort: Number(listener.input.value),
          ipv4: ip.input.value.trim(),
          mtu: Number(mtu.input.value),
          threadCount: Number(threads.input.value),
          compression: compression.input.value,
          p2pMode: mode.input.value,
          quicPort: Number(quicPort.input.value),
          exitNodes: split(exits.value),
          proxyNetworks: split(proxies.value),
          portForwards: editors.map((f) => ({
            protocol: f.proto.value,
            localPort: Number(f.local.value),
            targetIp: f.ip.value.trim(),
            targetPort: Number(f.port.value),
          })),
        };
        for (const c of toggles) candidate[c.key] = c.input.checked;
        save.disabled = true;
        try {
          const validated = await localInvoke<NetworkSettings>(
            'validate_network_settings',
            candidate
          );
          if (current !== generation) return;
          localStorage.setItem(KEY, JSON.stringify(validated));
          if (report === defaultReportedVersion() && !reportedVersionIsCustom() && !reportVersion.input.hasAttribute('data-edited')) resetReportedVersion(); else saveReportedVersion(report);
          settings = validated;
          savedError = '';
          ctx.status('网络设置已保存；请在下次手动加入时使用。');
        } finally {
          save.disabled = false;
        }
      })
    );
    const actions = make('div');
    actions.className = 'community-tool-actions';
    actions.append(
      save,
      button('恢复默认设置', () =>
        run(async () => {
          localStorage.removeItem(KEY);
          resetReportedVersion();
          settings = defaults();
          savedError = '';
          open('settings');
          ctx.status('已恢复下次加入使用的默认设置。');
        })
      )
    );
    target.append(actions);
  }
  function games() {
    const quick = card('游戏快连'),
      preset = select('游戏', '25565', [
        ['25565', 'Minecraft Java'],
        ['19132', '我的世界 基岩版'],
        ['7777', '泰拉瑞亚'],
        ['10999', '饥荒联机版'],
        ['2456', 'Valheim 英灵神殿'],
        ['27015', 'CS / 起源引擎'],
        ['34197', '异星工厂'],
        ['custom', '自定义'],
      ]),
      port = field('实际开服端口', '25565', 'number'),
      list = make('div');
    port.input.min = '1024';
    port.input.max = '65535';
    quick.append(
      preset.label,
      port.label,
      hint(
        '使用原版常见游戏端口模板；房主仍需在游戏中开服。将地址粘贴进游戏直接连接，成员在线不等于游戏端口可达。'
      ),
      list
    );
    const refresh = () => {
      list.replaceChildren();
      const n = Number(port.input.value);
      if (!Number.isInteger(n) || n < 1024 || n > 65535) return;
      for (const p of ctx.players()) {
        const row = make('div');
        row.className = 'community-row';
        const address = `${p.virtualIp}:${n}`;
        row.append(
          make('strong', p.name),
          make('code', address),
          button('复制地址', () =>
            run(async () => {
              await navigator.clipboard.writeText(address);
              ctx.status('游戏地址已复制。');
            })
          )
        );
        list.append(row);
      }
      if (!ctx.online()) list.append(hint('请先加入大厅。'));
    };
    preset.input.onchange = () => {
      if (preset.input.value !== 'custom') port.input.value = preset.input.value;
      refresh();
    };
    port.input.oninput = refresh;
    refresh();
    const mc = card('Minecraft Java 世界发现'),
      scanPort = field('状态查询端口', '25565', 'number'),
      results = make('div');
    scanPort.input.min = '1024';
    scanPort.input.max = '65535';
    const current = generation,
      scan = button('查询当前大厅世界', () =>
        run(async () => {
          if (!ctx.online()) throw new Error('请先加入大厅');
          scan.disabled = true;
          results.replaceChildren(hint('正在查询大厅虚拟地址…'));
          try {
            const worlds = await localInvoke<
              {
                ip: string;
                port: number;
                motd: string;
                version: string;
                onlinePlayers: number;
                maxPlayers: number;
                latencyMs: number;
              }[]
            >('scan_minecraft_servers', { port: Number(scanPort.input.value) });
            if (current !== generation) return;
            results.replaceChildren();
            for (const w of worlds) {
              const row = make('div');
              row.className = 'community-row';
              row.append(
                make('strong', w.motd || 'Minecraft 世界'),
                hint(`${w.version} · ${w.onlinePlayers}/${w.maxPlayers} · ${w.latencyMs} ms`),
                make('code', `${w.ip}:${w.port}`),
                button('复制地址', () =>
                  run(async () => {
                    await navigator.clipboard.writeText(`${w.ip}:${w.port}`);
                  })
                )
              );
              results.append(row);
            }
            if (!worlds.length)
              results.append(hint('未发现响应的世界；请核对开服端口、组网和防火墙。'));
          } finally {
            scan.disabled = false;
          }
        })
      );
    mc.append(
      scanPort.label,
      scan,
      hint(
        '点击后只向已同步成员的虚拟 IP 发起原版 Minecraft 状态查询。随机 LAN 端口请手动填写；自动局域网广播桥和基岩版发现暂未接入。'
      ),
      results
    );
  }
  async function dns() {
    const current = generation,
      pane = target;
    pane.append(
      hint(
        '域名沿用原版身份派生规则：身份前 32 位 + .mct.net。一次性授权只更新 LinuxWeb 标记段，不接管系统 DNS。'
      )
    );
    const result = await localInvoke<{
      entries: { playerName: string; domain: string; ip: string }[];
      installed: boolean;
      upToDate: boolean;
      error?: string;
    }>('get_magic_dns_status');
    if (current !== generation) return;
    pane.append(
      hint(
        result.installed
          ? result.upToDate
            ? 'hosts 记录与当前成员一致。'
            : '存在 Linux Web 记录；成员或地址变动后请手动更新。'
          : '尚未写入 Linux Web hosts 记录。'
      )
    );
    if (result.error) pane.append(hint(result.error));
    for (const m of result.entries) {
      const row = make('div');
      row.className = 'community-row';
      row.append(
        make('strong', m.playerName),
        make('code', m.domain),
        hint(m.ip),
        button('复制域名', () =>
          run(async () => {
            await navigator.clipboard.writeText(m.domain);
          })
        )
      );
      pane.append(row);
    }
    const actions = make('div');
    actions.className = 'community-tool-actions';
    actions.append(
      button('预览更新域名', () => run(() => prepare('prepare_magic_dns', { remove: false }))),
      button('预览清理域名', () => run(() => prepare('prepare_magic_dns', { remove: true }))),
      button('刷新映射状态', () => open('dns'))
    );

    pane.append(
      actions,
      hint(
        '退出大厅或停服务后记录不会自动删除；可再次启动服务，进入此页清理。实际解析、对端游戏访问仍需验收。'
      )
    );
  }
  async function repair() {
    const current = generation,
      pane = target;
    pane.append(
      hint(
        '常规修复放行本次 EasyTier 主监听：普通节点同一端口 TCP + UDP，WebSocket 节点为 TCP。备用方式可单独确认暂停整个防火墙。14700 / RPC 保持回环监听，节点地址不变。'
      )
    );
    const result = await localInvoke<{
      tools: {
        ufw: boolean;
        firewalld: boolean;
        zones: string[];
        defaultZone: string;
        overlayZone: string;
        firewalldState?: string;
        activeZones?: { name: string; interfaces: string[] }[];
        ephemeralRange: [number, number] | null;
      };
      currentCore?: {
        listenerPort: number;
        configuredListenerProtocols?: string[];
        sockets: { udp?: number[]; tcp?: number[]; error?: string };
      } | null;
      savedRules: {
        token: string;
        backend: string;
        zone: string;
        port: number;
        protocol: string;
        tcpListener?: boolean;
        pause?: boolean;
      }[];
    }>('get_firewall_status');
    if (current !== generation) return;
    if (ctx.restartNetwork) {
      const restart = ctx.restartNetwork;
      const reconnect = button('重新组网', () => run(async () => {
        if (!ctx.online() || ctx.busy() || authorizing) throw new Error('请在大厅连接完成且系统操作结束后重试。');
        if (!window.confirm('重新启动当前 EasyTier 连接？节点、虚拟 IP 和主监听端口保持不变，数据传输会短暂中断，正在进行的文件传输可能失败；语音/屏幕可能需要重新连接。')) return;
        reconnect.disabled = true;
        try { await restart(); }
        finally { reconnect.disabled = !ctx.online() || ctx.busy(); }
      }));
      reconnect.disabled = !ctx.online() || ctx.busy();
      pane.append(reconnect, hint('成功应用、撤销或暂停/恢复防火墙更改后，会静默重新连接 EasyTier；若自动重连失败，这里会提示并保留手动重连入口。直连仍取决于网络与对端配置。'));
    }
    if (result.currentCore) {
      const snapshot = result.currentCore;
      const note = make('details');
      note.className = 'network-disclosure';
      note.append(
        make('summary', `核心端口诊断 · 配置主监听 ${snapshot.listenerPort}${snapshot.configuredListenerProtocols?.length ? ' · ' + snapshot.configuredListenerProtocols.join('/').toUpperCase() : ''}`),
        hint(
          snapshot.sockets.error ||
            `当前 UDP：${snapshot.sockets.udp?.join('、') || '无'}；TCP：${snapshot.sockets.tcp?.join('、') || '无'}。仅当前快照，打洞会新建 socket，固定主端口规则不能覆盖全部。`
        ),
        hint(
          'UFW 在用户规则前可能丢弃 conntrack INVALID；若扩展范围仍只走 relay，需要对照丢包日志判断，不能仅凭规则存在宣布 P2P 成功。'
        )
      );
      pane.append(note);
    }
    const options: [string, string][] = [];
    if (result.tools.ufw) options.push(['ufw', 'ufw']);
    if (result.tools.firewalld) options.push(['firewalld', 'firewalld']);
    if (!options.length)
      pane.append(
        hint(
          '未找到系统 ufw / firewall-cmd。若你使用其它防火墙，请手动配置，本功能不会安装或切换它。'
        )
      );
    const backend = select(
        '使用的防火墙',
        options.length === 1 ? options[0][0] : '',
        options.length > 1 ? [['', '请选择实际使用的防火墙'], ...options] : options
      ),
      zone = select(
        'firewalld 入站所属区域',
        result.tools.defaultZone,
        result.tools.zones.map((z) => [z, z])
      );
    const dynamic = checkbox('额外放行系统动态 UDP / TCP 范围（可选，影响其它程序）', false);
    zone.label.hidden = backend.input.value !== 'firewalld';
    const backendInfo = make('div');
    backendInfo.className = 'firewall-backend-info';
    const refreshBackend = () => {
      const firewalld = backend.input.value === 'firewalld';
      zone.label.hidden = !firewalld;
      backendInfo.hidden = !firewalld;
      preview.disabled =
        !backend.input.value ||
        (firewalld &&
          (result.tools.firewalldState === 'not-running' ||
            !result.tools.overlayZone ||
            !zone.input.value));
    };
    backend.input.onchange = refreshBackend;
    zone.input.onchange = refreshBackend;
    const selection = card('选择后端与区域');
    selection.className += ' firewall-selection';
    selection.append(backend.label, zone.label);
    pane.append(backendInfo);
    const punching = make('details');
    punching.className = 'network-disclosure';
    punching.append(
      make('summary', '可选：扩展 UDP / TCP 打洞范围'),
      dynamic.label,
      hint(
        `动态范围（EasyTier 打洞会另开随机端口，主监听端口放行并不足够）：${result.tools.ephemeralRange?.join('–') || '无法读取'}。普通 UDP 会话通常依靠状态跟踪；对称 NAT/严格防火墙可尝试此项，同时放行 TCP 打洞启用时的动态 TCP 入站；范围内其它程序也可能接收入站流量。它不能保证打洞成功，不能修复 NAT、运营商或浏览器信令问题。`
      )
    );
    const outgoing = checkbox('额外放行 UFW 出站（本机限制出站时使用）', false);
    outgoing.input.disabled = backend.input.value !== 'ufw';
    const oldBackendChange = backend.input.onchange;
    punching.append(outgoing.label, hint('出站规则限定本地源端口与虚拟网段，对端打洞目标端口由 NAT 决定。它仍影响匹配端口的其它程序。firewalld 出站策略暂不自动修改。'));
    const preview = button('预览本次网络修复' , () =>
      run(() =>
        prepare('prepare_firewall_repair', {
          backend: backend.input.value,
          zone: backend.input.value === 'firewalld' ? zone.input.value : '',
          ephemeralUdp: dynamic.input.checked,
          allowOutgoing: outgoing.input.checked,
        })
      )
    );
    pane.append(punching, preview);
    refreshBackend();
    if (result.tools.firewalld) {
      backendInfo.append(
        make(
          'strong',
          `firewalld：${result.tools.firewalldState === 'running' ? '正在运行' : result.tools.firewalldState === 'not-running' ? '尚未运行' : '状态无法确认'}`
        )
      );
      for (const active of result.tools.activeZones || [])
        backendInfo.append(
          hint(
            `${active.name} · ${active.interfaces.length ? active.interfaces.join('、') : '无接口绑定（可能由来源规则匹配）'}`
          )
        );
      backendInfo.append(
        hint(
          `请选择物理入站接口实际使用的区域；虚拟接口区域：${result.tools.overlayZone || '未能确定，不能申请修复'}。虚拟规则限定源虚拟网段和目标虚拟 IP；此版不会调整接口区域，复杂多区域配置需手动核对。firewalld 规则 1 小时后自动失效。`
        )
      );
    }
    const pausePanel = make('details');
    pausePanel.className = 'network-disclosure firewall-danger';
    const pause = button('预览暂停整个防火墙', () =>
      run(() =>
        prepare('prepare_firewall_repair', {
          backend: backend.input.value,
          zone: '',
          ephemeralUdp: false,
          pause: true,
        })
      )
    );
    pausePanel.append(
      make('summary', '备用方式：暂停防火墙'),
      hint(
        '此操作影响整个系统，不能按程序限定。需输入“我确认暂停整个防火墙”并由你完成系统授权。ufw 的禁用会保留到恢复；firewalld 仅停止服务，可能丢失其它临时规则。退出大厅时可选择恢复。'
      ),
      pause
    );
    pause.disabled = !backend.input.value;
    backend.input.onchange = event => {
      oldBackendChange?.call(backend.input, event);
      outgoing.input.disabled = backend.input.value !== 'ufw';
      if (outgoing.input.disabled) outgoing.input.checked = false;
      pause.disabled = !backend.input.value;
    };
    pane.append(pausePanel, make('h3', '已记录更改 / 撤销'));
    for (const p of result.savedRules) {
      const row = make('div');
      row.className = 'community-row';
      row.append(
        hint(
          p.pause
            ? `${p.backend} · 已申请暂停（以系统实际状态为准）`
            : `${p.backend}${p.zone ? ' · ' + p.zone : ''} · ${p.port}/${p.tcpListener ? 'udp+tcp' : p.protocol}`
        ),
        button(p.pause ? '恢复防火墙' : '撤销本次更改', () =>
          run(async () => {
            await undoFirewall(p);
            open('repair');
          })
        )
      );
      pane.append(row);
    }
    if (!result.savedRules.length)
      pane.append(
        hint(
          '没有本应用记录的规则。ufw 规则持久保存，退出后请在这里手动撤销；未成功授权的记录也可安全清理。'
        )
      );
  }
  async function undoFirewall(plan: { token: string; backend: string; zone: string }, reconnect = true) {
    if (authorizing) throw new Error('已有系统操作进行中，请先完成或取消。');
    const preview = await localInvoke<Preview>('prepare_firewall_repair', {
      backend: plan.backend,
      zone: plan.zone,
      removeToken: plan.token,
      ephemeralUdp: false,
      restartNetwork: reconnect,
    });
    authorizing = true;
    try {
      const result = await localInvoke<OperationResult>('apply_network_operation', {
        token: preview.token,
        confirmationText: '',
      });
      ctx.status(result.report.join('；'), partialFailure(result));
    } finally {
      authorizing = false;
    }
  }
  async function beforeLeave(): Promise<boolean> {
    if (authorizing) {
      ctx.status('请先完成或取消正在进行的系统操作，再退出大厅。', true);
      return false;
    }
    let plans: { token: string; backend: string; zone: string; pause?: boolean }[];
    try {
      plans = (await localInvoke<{ savedRules: typeof plans }>('get_firewall_status')).savedRules;
    } catch (e) {
      ctx.status(`防火墙更改检查失败：${err(e)}。请在网络面板检查残留更改。`, true);
      return true;
    }
    if (!plans.length) return true;
    if (dialog.open) dialog.close();
    return new Promise<boolean>((resolve) => {
      const question = make('dialog');
      leaveDialog = question;
      question.className = 'community-dialog';
      const head = make('header');
      head.className = 'community-header';
      head.append(make('h2', '是否撤销防火墙更改？'));
      const content = make('div');
      content.className = 'community-body';
      content.append(
        hint(
          `本应用记录了 ${plans.length} 组更改（含之前留下的）。撤销仅处理本应用规则或恢复暂停前的运行状态，系统认证仍由你完成。`
        )
      );
      for (const p of plans)
        content.append(hint(`${p.backend} · ${p.pause ? '恢复暂停前运行状态' : '移除本应用规则'}`));
      const output = make('p');
      output.setAttribute('role', 'status');
      let closed = false,
        finished = false;
      const finish = (value: boolean) => {
        if (finished) return;
        finished = true;
        resolve(value);
        question.close();
      };
      const revert = button(
        '撤销更改并退出',
        () =>
          void (async () => {
            revert.disabled = true;
            keep.disabled = true;
            output.textContent = '请处理系统认证窗口…';
            try {
              for (const p of plans) {
                if (closed) return;
                await undoFirewall(p, false);
              }
              if (!closed) finish(true);
            } catch (e) {
              output.textContent = err(e);
              ctx.status(err(e), true);
            } finally {
              revert.disabled = false;
              keep.disabled = false;
            }
          })()
      );
      const keep = button('保留更改并退出', () => finish(true));
      const cancel = button('取消退出', () => question.close());
      const actions = make('div');
      actions.className = 'community-tool-actions';
      actions.append(revert, keep, cancel);
      content.append(output, actions);
      question.append(head, content);
      document.body.append(question);
      question.addEventListener('close', () => {
        closed = true;
        if (authorizing) cancelAuthorization();
        if (!finished) {
          finished = true;
          resolve(false);
        }
        if (leaveDialog === question) leaveDialog = null;
        question.remove();
      });
      question.showModal();
    });
  }
  async function prepare(command: string, args: Record<string, unknown>) {
    const current = generation;
    const preview = await localInvoke<Preview>(command, args);
    if (current !== generation) return;
    generation++;
    body.replaceChildren();
    heading.textContent = preview.title;
    const summary = make('section');
    summary.className = 'community-tool-section';
    summary.append(
      hint(
        '第 1 步：核对下列范围与规则。第 2 步：在输入框完整输入确认文字，再申请系统授权。预览两分钟内有效；认证窗口由你自行处理。'
      )
    );
    for (const line of preview.lines) summary.append(hint(line));
    for (const m of preview.entries || []) summary.append(make('code', `${m.ip} → ${m.domain}`));
    if (preview.commands) {
      const details = make('details'),
        code = make('pre');
      details.append(make('summary', '查看固定操作参数'));
      code.className = 'network-command';
      code.textContent = preview.commands
        .map((args) => args.map((v) => JSON.stringify(v)).join(' '))
        .join('\n');
      details.append(code);
      summary.append(details);
    }
    const confirmation = field(`第 2 步：请输入“${preview.confirmationText}”`, '');
    confirmation.label.className = 'network-confirmation';
    confirmation.input.autocomplete = 'off';
    confirmation.input.spellcheck = false;
    confirmation.input.placeholder = preview.confirmationText;
    const output = make('p');
    output.setAttribute('role', 'status');
    const apply = button('确认并申请系统授权', () =>
      run(async () => {
        if (confirmation.input.value !== preview.confirmationText) {
          output.textContent = '请输入完整且一致的确认文字。';
          confirmation.input.focus();
          return;
        }
        authorizing = true;
        confirmation.input.disabled = true;
        apply.disabled = true;
        cancel.textContent = '取消系统操作';
        output.textContent = '等待系统授权，请处理认证窗口…';
        try {
          const r = await localInvoke<OperationResult>('apply_network_operation', {
            token: preview.token,
            confirmationText: confirmation.input.value,
          });
          output.textContent = r.report.join('\n');
          ctx.status(partialFailure(r) ? '防火墙已变更，但 EasyTier 重连未完成，请检查会话并重新加入。' : '系统操作已完成并复核；实际组网、解析和 P2P 仍需验证。', partialFailure(r));
        } catch (e) {
          output.textContent = err(e);
          throw e;
        } finally {
          authorizing = false;
          cancel.disabled = false;
          cancel.textContent = '返回网络面板';
        }
      })
    );
    apply.disabled = true;
    confirmation.input.oninput = () => {
      apply.disabled =
        authorizing ||
        confirmation.input.disabled ||
        confirmation.input.value !== preview.confirmationText;
    };
    const cancel = button('取消，返回网络面板', () => {
      if (authorizing) {
        cancelAuthorization();
        cancel.disabled = true;
        return;
      }
      open(command.includes('dns') ? 'dns' : 'repair');
    });
    const actions = make('div');
    actions.className = 'community-tool-actions';
    actions.append(apply, cancel);
    summary.append(confirmation.label, actions, output);
    body.append(summary);
  }
  for (const [id, title, tab] of [
    ['advanced-network', '高级网络 / 游戏快连', 'settings'],
    ['network-fix', '预览网络修复', 'repair'],
    ['magic-dns', '管理成员域名', 'dns'],
  ] as const) {
    for (const p of document.querySelectorAll(
      `[data-feature="${id}"], #feature-matrix [data-feature-id="${id}"]`
    )) {
      const container = p.querySelector('.feature-card') ?? p;
      const b = button(title, () => open(tab));
      b.className = 'feature-action';
      b.title = container.querySelector('p')?.textContent || title;
      container.append(b);
    }
  }
  document
    .querySelector('.sidebar-locks')
    ?.append(button('网络设置 / 游戏', () => open('games')));
  return {
    settings: () => structuredClone(settings),
    beforeLeave,
    reset() {
      if (leaveDialog?.open) leaveDialog.close();
      if (dialog.open) dialog.close();
    },
  };
}
