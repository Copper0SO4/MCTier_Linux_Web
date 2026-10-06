import test from 'node:test';
import assert from 'node:assert/strict';
import { build } from 'esbuild';
const bundle = await build({
  entryPoints: ['MCTier-Linux-Web/web/shell.ts'],
  bundle: true,
  format: 'esm',
  write: false,
  plugins: [
    {
      name: 'features',
      setup(b) {
        b.onResolve({ filter: /featureAvailability$/ }, () => ({
          path: 'features',
          namespace: 'mock',
        }));
        b.onLoad({ filter: /.*/, namespace: 'mock' }, () => ({
          contents:
            "export const FEATURES=[]; export const browserAvailability=()=>({canJoin:true,warning:''});",
        }));
      },
    },
  ],
});
const { setupShell } = await import(
  `data:text/javascript,${encodeURIComponent(bundle.outputFiles[0].text)}`
);
class Element {
  constructor() {
    this.children = [];
    this.hidden = false;
    this.disabled = false;
    this.dataset = {};
    this.attributes = {};
    this.textContent = '';
    this.heading = { focus() {}, tabIndex: 0 };
  }
  getAttribute(k) {
    return this.attributes[k];
  }
  setAttribute(k, v) {
    this.attributes[k] = v;
  }
  querySelector() {
    return this.heading;
  }
  focus() {}
  append() {}
  click() {
    if (!this.disabled) this.onclick?.({ preventDefault() {} });
  }
}
function fixture() {
  const elements = new Map(),
    events = new Map();
  for (const id of [
    'home-view',
    'connect-view',
    'lobby-view',
    'settings-view',
    'capabilities-view',
    'about-view',
    'return-room',
    'session-navigation',
    'form-title',
    'entry-submit',
    'entry-hint',
    'player-name',
    'home-link',
    'feature-matrix',
    'browser-warning',
    'audio-output',
    'output-support',
    'leave',
    'copy-ip',
    'chat-panel',
    'screen-panel',
    'diagnostics-panel',
  ]) {
    const n = new Element();
    n.attributes['aria-label'] = id;
    elements.set(id, n);
  }
  const connect = ['create', 'join'].map((v) => {
    const n = new Element();
    n.dataset.connect = v;
    return n;
  });
  const utility = ['capabilities', 'settings', 'about'].map((v) => {
    const n = new Element();
    n.dataset.view = v;
    return n;
  });
  const panels = ['chat', 'screen', 'diagnostics'].map((v) => {
    const n = new Element();
    n.dataset.panel = v;
    return n;
  });
  const back = new Element();
  let modal = false;
  Object.defineProperty(globalThis, 'navigator', {
    value: { userAgent: 'Chrome' },
    configurable: true,
  });
  globalThis.HTMLMediaElement = class {};
  globalThis.window = {
    scrollY: 0,
    scrollTo({ top }) {
      this.scrollY = top;
    },
  };
  globalThis.document = {
    title: '',
    getElementById: (id) => elements.get(id),
    querySelector: () => (modal ? {} : null),
    querySelectorAll: (s) =>
      s === '[data-connect]'
        ? connect
        : s === '[data-view]'
          ? utility
          : s === '[data-back]'
            ? [back]
            : s === '[data-panel]'
              ? panels
              : [],
    addEventListener: (k, fn) => events.set(k, fn),
  };
  const shell = setupShell();
  return {
    shell,
    elements,
    connect,
    utility,
    panels,
    back,
    modal(v) {
      modal = v;
    },
    escape() {
      events.get('keydown')({ key: 'Escape' });
    },
  };
}
test('room state refresh preserves utility page and selected panel; home provides a safe return', () => {
  const f = fixture();
  f.shell.setSessionState('online');
  f.panels[2].click();
  f.utility[0].click();
  f.shell.setSessionState('online');
  assert.equal(f.elements.get('capabilities-view').hidden, false);
  assert.equal(f.elements.get('diagnostics-panel').hidden, false);
  assert.equal(f.elements.get('return-room').hidden, false);
  f.elements.get('home-link').click();
  assert.equal(f.elements.get('home-view').hidden, false);
  assert.equal(
    f.connect.every((n) => n.disabled),
    true
  );
  f.elements.get('return-room').click();
  assert.equal(f.elements.get('lobby-view').hidden, false);
  assert.equal(f.elements.get('diagnostics-panel').hidden, false);
});
test('Escape in a modal never navigates the underlying page, and leaving restores join controls', () => {
  const f = fixture();
  f.shell.setSessionState('online');
  f.utility[1].click();
  f.modal(true);
  f.escape();
  assert.equal(f.elements.get('settings-view').hidden, false);
  f.modal(false);
  f.escape();
  assert.equal(f.elements.get('lobby-view').hidden, false);
  f.shell.setSessionState('idle');
  assert.equal(f.elements.get('home-view').hidden, false);
  assert.equal(
    f.connect.every((n) => !n.disabled),
    true
  );
  assert.equal(f.elements.get('return-room').hidden, true);
});
test('view changes restore each page scroll instead of carrying lobby position into feature status', () => {
  const f = fixture();
  f.shell.setSessionState('online');
  window.scrollY = 300;
  f.utility[0].click();
  assert.equal(window.scrollY, 0);
  window.scrollY = 150;
  f.elements.get('return-room').click();
  assert.equal(window.scrollY, 300);
  f.utility[0].click();
  assert.equal(window.scrollY, 150);
});

 test('home create and imported lobby join select the correct default submit action', () => {
  const f = fixture();
  f.connect[0].click();
  assert.equal(f.elements.get('form-title').textContent, '创建大厅');
  assert.equal(f.elements.get('entry-submit').textContent, '创建大厅');
  assert.equal(f.shell.entryMode, 'create');
  f.shell.showConnect();
  assert.equal(f.elements.get('form-title').textContent, '加入大厅');
  assert.equal(f.elements.get('entry-submit').textContent, '加入大厅');
  assert.equal(f.shell.entryMode, 'join');
});
