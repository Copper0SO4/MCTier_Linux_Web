import test from 'node:test';
import assert from 'node:assert/strict';
import { build } from 'esbuild';

const bundled = await build({
  entryPoints: ['MCTier-Linux-Web/web/networkPanel.ts'],
  bundle: true,
  format: 'esm',
  write: false,
  plugins: [
    {
      name: 'local-api',
      setup(b) {
        b.onResolve({ filter: /localWeb$/ }, () => ({ path: 'local', namespace: 'mock' }));
        b.onLoad({ filter: /.*/, namespace: 'mock' }, () => ({
          contents: 'export const localInvoke=(...args)=>globalThis.networkApi(...args)',
        }));
      },
    },
  ],
});
const { setupNetworkPanel } = await import(
  `data:text/javascript,${encodeURIComponent(bundled.outputFiles[0].text)}`
);
class Element {
  constructor(tag, text = '') {
    this.tag = tag;
    this.textContent = text;
    this.children = [];
    this.events = new Map();
    this.open = false;
    this.value = '';
    this.disabled = false;
  }
  append(...nodes) {
    for (const n of nodes) {
      n.parent = this;
      this.children.push(n);
    }
  }
  replaceChildren(...nodes) {
    this.children = [];
    this.append(...nodes);
  }
  setAttribute(key, value) {
    this[key] = value;
  }
  addEventListener(name, fn) {
    this.events.set(name, fn);
  }
  querySelector(selector) {
    return selector === '.feature-card'
      ? this.children.find((c) => c.className === 'feature-card')
      : null;
  }
  showModal() {
    this.open = true;
  }
  close() {
    this.open = false;
    this.events.get('close')?.();
  }
  click() {
    if (!this.disabled) this.onclick?.();
  }
  focus() {
    document.activeElement = this;
  }
  remove() {
    this.parent.children = this.parent.children.filter((c) => c !== this);
  }
}
const all = (n) => [n, ...n.children.flatMap(all)];
const tick = () => new Promise((r) => setImmediate(r));
function confirm(f, text = '我确认更新本机域名映射') {
  const input = all(f.dialog).find((n) => n.tag === 'input' && n.placeholder?.startsWith('我确认'));
  input.value = text;
  input.oninput();
}
function fixture(saved, tools = {}, savedRules = [], restartNetwork) {
  const body = new Element('body'),
    cards = new Map(
      ['magic-dns', 'advanced-network', 'network-fix'].map((id) => {
        const p = new Element('div'),
          card = new Element('div');
        card.className = 'feature-card';
        p.append(card);
        return [id, p];
      })
    );
  globalThis.document = {
    body,
    activeElement: null,
    createElement: (tag) => new Element(tag),
    querySelector: () => null,
    querySelectorAll: (s) => [cards.get(s.match(/data-feature="([^"]+)"/)[1])],
    getElementById: (id) => all(body).find((n) => n.id === id),
  };
  const store = new Map(saved ? [['mctier-linux-web-network-v1', JSON.stringify(saved)]] : []);
  globalThis.localStorage = {
    getItem: (k) => store.get(k) || null,
    setItem: (k, v) => store.set(k, v),
    removeItem: (k) => store.delete(k),
  };
  const calls = [],
    notices = [];
  globalThis.networkApi = async (command, args) => {
    calls.push([command, args]);
    if (command === 'get_magic_dns_status')
      return { entries: [], installed: false, upToDate: false };
    if (command === 'get_firewall_status')
      return {
        tools: {
          ufw: true,
          firewalld: false,
          zones: [],
          defaultZone: '',
          overlayZone: '',
          ephemeralRange: [32768, 60999],
          ...tools,
        },
        savedRules,
      };
    if (command.startsWith('prepare_'))
      return {
        token: 'one-use-preview',
        confirmationText: command.includes('dns')
          ? '我确认更新本机域名映射'
          : args.pause
            ? '我确认暂停整个防火墙'
            : '我确认修改本机防火墙',
        title: '授权预览',
        lines: ['固定范围'],
        entries: [],
      };
    if (command === 'apply_network_operation') return { report: ['复核完成'] };
    if (command === 'validate_network_settings') return args;
    throw new Error(`unexpected ${command}`);
  };
  const panel = setupNetworkPanel({
    online: () => true,
    busy: () => false,
    players: () => [],
    restartNetwork,
    status: (...a) => notices.push(a),
  });
  const dialog = all(body).find((n) => n.tag === 'dialog');
  return {
    panel,
    calls,
    cards,
    dialog,
    find: (text) => all(dialog).find((n) => n.tag === 'button' && n.textContent === text),
    notices,
    body,
  };
}
test('Magic DNS has no settings action or tab and opening network settings performs no DNS operation', () => {
  const f = fixture();
  assert.equal(f.cards.get('magic-dns').children[0].children.length, 0);
  f.cards.get('advanced-network').children[0].children.at(-1).click();
  assert.equal(f.find('Magic DNS'), undefined);
  assert.equal(f.calls.length, 0);
});
test('firewall defaults to narrow rules, broad UDP is explicitly opt-in and cancellation makes no apply call', async () => {
  const f = fixture();
  f.cards.get('network-fix').children[0].children.at(-1).click();
  await tick();
  const check = all(f.dialog).find((n) => n.tag === 'input' && n.type === 'checkbox');
  assert.equal(check.checked, false);
  f.find('预览本次网络修复').click();
  await tick();
  assert.deepEqual(f.calls.at(-1), [
    'prepare_firewall_repair',
    { backend: 'ufw', zone: '', ephemeralUdp: false, allowOutgoing: false },
  ]);
  f.find('取消，返回网络面板').click();
  await tick();
  assert.equal(
    f.calls.some((c) => c[0] === 'apply_network_operation'),
    false
  );
});
test('network settings only save after validation and do not connect or change an active network', async () => {
  const f = fixture();
  f.cards.get('advanced-network').children[0].children.at(-1).click();
  assert.equal(f.calls.length, 0);
  f.find('保存，下次加入生效').click();
  await tick();
  assert.equal(f.calls.length, 1);
  assert.equal(f.calls[0][0], 'validate_network_settings');
  assert.equal(f.panel.settings().mtu, 1360);
  assert.equal(f.panel.settings().p2pMode, 'auto');
});

test('malformed saved settings fall back visibly without crashing the network panel', () => {
  for (const saved of [
    { exitNodes: null },
    { portForwards: [null] },
    { mtu: 'wrong' },
    { unknown: true },
  ]) {
    const f = fixture(saved);
    f.cards.get('advanced-network').children[0].children.at(-1).click();
    assert.equal(f.panel.settings().mtu, 1360);
    assert.ok(all(f.dialog).some((n) => n.textContent.includes('无法读取')));
    assert.equal(f.calls.length, 0);
  }
});
test('closing a dialog during authentication requests cancellation', async () => {
  const f = fixture();
  const oldApi = globalThis.networkApi;
  let complete;
  globalThis.networkApi = (command, args) => {
    if (command === 'apply_network_operation')
      return new Promise((resolve) => {
        complete = resolve;
      });
    if (command === 'cancel_network_operation') {
      f.calls.push([command, args]);
      return Promise.resolve(true);
    }
    return oldApi(command, args);
  };
  f.cards.get('network-fix').children[0].children.at(-1).click();
  await tick();
  f.find('预览本次网络修复').click();
  await tick();
  confirm(f, '我确认修改本机防火墙');
  f.find('确认并申请系统授权').click();
  await tick();
  f.dialog.close();
  await tick();
  assert.equal(f.calls.filter((c) => c[0] === 'cancel_network_operation').length, 1);
  complete({ report: ['mock finished'] });
  await tick();
});

test('firewall authorization needs the entire typed confirmation before any apply request', async () => {
  const f = fixture();
  f.cards.get('network-fix').children[0].children.at(-1).click();
  await tick();
  f.find('预览本次网络修复').click();
  await tick();
  const apply = f.find('确认并申请系统授权');
  assert.equal(apply.disabled, true);
  confirm(f, '我确认');
  apply.click();
  await tick();
  assert.equal(
    f.calls.some((c) => c[0] === 'apply_network_operation'),
    false
  );
  confirm(f, '我确认修改本机防火墙');
  assert.equal(apply.disabled, false);
  apply.click();
  await tick();
  assert.deepEqual(f.calls.at(-1), [
    'apply_network_operation',
    { token: 'one-use-preview', confirmationText: '我确认修改本机防火墙' },
  ]);
});

test('firewalld shows its interface zones and both installed backends require an explicit selection', async () => {
  const f = fixture(undefined, {
    firewalld: true,
    firewalldState: 'running',
    zones: ['public', 'trusted'],
    defaultZone: 'public',
    overlayZone: 'trusted',
    activeZones: [{ name: 'public', interfaces: ['wlan0'] }],
  });
  f.cards.get('network-fix').children[0].children.at(-1).click();
  await tick();
  const selects = all(f.dialog).filter((n) => n.tag === 'select');
  const preview = f.find('预览本次网络修复');
  assert.equal(selects[0].value, '');
  assert.equal(preview.disabled, true);
  selects[0].value = 'firewalld';
  selects[0].onchange();
  assert.equal(selects[1].parent.hidden, false);
  assert.equal(preview.disabled, false);
  assert.ok(all(f.dialog).some((n) => n.textContent.includes('wlan0')));
  preview.click();
  await tick();
  assert.deepEqual(f.calls.at(-1), [
    'prepare_firewall_repair',
    { backend: 'firewalld', zone: 'public', ephemeralUdp: false, allowOutgoing: false },
  ]);
});
test('stopped firewalld cannot proceed to authorization from the repair panel', async () => {
  const f = fixture(undefined, {
    ufw: false,
    firewalld: true,
    firewalldState: 'not-running',
    zones: ['public'],
    defaultZone: 'public',
    overlayZone: 'public',
  });
  f.cards.get('network-fix').children[0].children.at(-1).click();
  await tick();
  const preview = f.find('预览本次网络修复');
  assert.equal(preview.disabled, true);
  preview.click();
  assert.equal(
    f.calls.some((c) => c[0] === 'prepare_firewall_repair'),
    false
  );
});

test('undo is direct and does not require typed confirmation', async () => {
  const f = fixture(undefined, {}, [
    { token: 'rule-1', backend: 'ufw', zone: '', port: 31111, protocol: 'udp' },
  ]);
  f.cards.get('network-fix').children[0].children.at(-1).click();
  await tick();
  f.find('撤销本次更改').click();
  await tick();
  assert.deepEqual(
    f.calls.find((c) => c[0] === 'apply_network_operation'),
    ['apply_network_operation', { token: 'one-use-preview', confirmationText: '' }]
  );
});
test('manual leave can keep changes or cancel without applying system operations', async () => {
  const f = fixture(undefined, {}, [
    { token: 'rule-1', backend: 'ufw', zone: '', port: 31111, protocol: 'udp' },
  ]);
  const first = f.panel.beforeLeave();
  await tick();
  all(f.body)
    .find((n) => n.tag === 'button' && n.textContent === '取消退出')
    .click();
  assert.equal(await first, false);
  const next = f.panel.beforeLeave();
  await tick();
  all(f.body)
    .find((n) => n.tag === 'button' && n.textContent === '保留更改并退出')
    .click();
  assert.equal(await next, true);
  assert.equal(
    f.calls.some((c) => c[0] === 'apply_network_operation'),
    false
  );
});
test('manual leave restores recorded changes serially before allowing exit', async () => {
  const f = fixture(undefined, {}, [
    { token: 'rule-1', backend: 'ufw', zone: '', port: 31111, protocol: 'udp' },
    { token: 'pause-1', backend: 'firewalld', zone: '', pause: true },
  ]);
  const result = f.panel.beforeLeave();
  await tick();
  all(f.body)
    .find((n) => n.tag === 'button' && n.textContent === '撤销更改并退出')
    .click();
  await tick();
  assert.equal(await result, true);
  assert.equal(f.calls.filter((c) => c[0] === 'apply_network_operation').length, 2);
  assert.equal(
    f.calls
      .filter((c) => c[0] === 'apply_network_operation')
      .every((c) => c[1].confirmationText === ''),
    true
  );
});
test('pausing the whole firewall still requires the exact separate confirmation', async () => {
  const f = fixture();
  f.cards.get('network-fix').children[0].children.at(-1).click();
  await tick();
  f.find('预览暂停整个防火墙').click();
  await tick();
  assert.deepEqual(f.calls.at(-1), [
    'prepare_firewall_repair',
    { backend: 'ufw', zone: '', ephemeralUdp: false, pause: true },
  ]);
  assert.equal(f.find('确认并申请系统授权').disabled, true);
});

test('forced room cleanup closes a pending leave choice instead of leaving a stale modal', async () => {
  const f = fixture(undefined, {}, [
    { token: 'rule-1', backend: 'ufw', zone: '', port: 31111, protocol: 'udp' },
  ]);
  const result = f.panel.beforeLeave();
  await tick();
  f.panel.reset();
  assert.equal(await result, false);
});

test('network restart is manual, cancelable, and does not apply firewall changes', async () => {
  let restarts = 0, accept = false;
  globalThis.window = { confirm: () => accept };
  const f = fixture(undefined, {}, [], async () => { restarts++; });
  f.cards.get('network-fix').children[0].children.at(-1).click(); await tick();
  assert.equal(restarts, 0);
  f.find('重新组网').click(); await tick(); assert.equal(restarts, 0);
  accept = true; f.find('重新组网').click(); await tick(); assert.equal(restarts, 1);
  assert.equal(f.calls.some(([c]) => c === 'apply_network_operation'), false);
});
test('UFW egress repair is opt-in and never offered as firewalld ingress', async () => {
  const f = fixture();
  f.cards.get('network-fix').children[0].children.at(-1).click(); await tick();
  const checks = all(f.dialog).filter(n => n.tag === 'input' && n.type === 'checkbox');
  assert.equal(checks[1].checked, false); checks[1].checked = true;
  f.find('预览本次网络修复').click(); await tick();
  assert.equal(f.calls.at(-1)[1].allowOutgoing, true);
  const fw = fixture(undefined, {ufw: false, firewalld: true, firewalldState: 'running', zones: ['public'], defaultZone: 'public', overlayZone: 'public'});
  fw.cards.get('network-fix').children[0].children.at(-1).click(); await tick();
  assert.equal(all(fw.dialog).filter(n => n.tag === 'input' && n.type === 'checkbox')[1].disabled, true);
});
