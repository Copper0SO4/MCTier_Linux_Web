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
function fixture(saved) {
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
        },
        savedRules: [],
      };
    if (command.startsWith('prepare_'))
      return { token: 'one-use-preview', title: '授权预览', lines: ['固定范围'], entries: [] };
    if (command === 'apply_network_operation') return { report: ['复核完成'] };
    if (command === 'validate_network_settings') return args;
    throw new Error(`unexpected ${command}`);
  };
  const panel = setupNetworkPanel({
    online: () => true,
    busy: () => false,
    players: () => [],
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
  };
}
test('DNS viewing and previewing never request authorization; only explicit confirmation applies one preview', async () => {
  const f = fixture();
  assert.equal(f.calls.length, 0);
  f.cards.get('magic-dns').children[0].children.at(-1).click();
  await tick();
  assert.deepEqual(
    f.calls.map((c) => c[0]),
    ['get_magic_dns_status']
  );
  f.find('预览更新域名').click();
  await tick();
  assert.deepEqual(
    f.calls.map((c) => c[0]),
    ['get_magic_dns_status', 'prepare_magic_dns']
  );
  f.find('确认并申请系统授权').click();
  f.find('确认并申请系统授权').click();
  await tick();
  assert.deepEqual(f.calls.at(-1), ['apply_network_operation', { token: 'one-use-preview' }]);
  assert.equal(f.calls.filter((c) => c[0] === 'apply_network_operation').length, 1);
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
    { backend: 'ufw', zone: '', ephemeralUdp: false },
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
  f.cards.get('magic-dns').children[0].children.at(-1).click();
  await tick();
  f.find('预览更新域名').click();
  await tick();
  f.find('确认并申请系统授权').click();
  await tick();
  f.dialog.close();
  await tick();
  assert.equal(f.calls.filter((c) => c[0] === 'cancel_network_operation').length, 1);
  complete({ report: ['mock finished'] });
  await tick();
});
