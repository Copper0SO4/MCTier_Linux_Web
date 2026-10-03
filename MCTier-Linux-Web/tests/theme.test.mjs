import test from 'node:test';
import assert from 'node:assert/strict';
import { build } from 'esbuild';

const bundle = await build({
  entryPoints: ['MCTier-Linux-Web/web/theme.ts'],
  bundle: true,
  format: 'esm',
  write: false,
});
const { setupTheme } = await import(
  `data:text/javascript,${encodeURIComponent(bundle.outputFiles[0].text)}`
);
const KEY = 'mctier-theme-preference';

function fixture({ value = null, dark = false, readBlocked = false, writeBlocked = false } = {}) {
  const select = new EventTarget(),
    media = new EventTarget(),
    win = new EventTarget();
  const root = { dataset: {} },
    status = { textContent: '' },
    data = new Map();
  if (value !== null) data.set(KEY, value);
  media.matches = dark;
  globalThis.localStorage = {
    getItem(key) {
      if (readBlocked) throw new Error('Storage blocked');
      return data.get(key) ?? null;
    },
    setItem(key, value) {
      if (writeBlocked) throw new Error('Storage blocked');
      data.set(key, value);
    },
  };
  win.matchMedia = (query) => {
    assert.equal(query, '(prefers-color-scheme: dark)');
    return media;
  };
  globalThis.window = win;
  globalThis.document = {
    documentElement: root,
    getElementById: (id) => (id === 'theme-preference' ? select : status),
  };
  const cleanup = setupTheme();
  return {
    root,
    select,
    status,
    data,
    cleanup,
    change(value) {
      select.value = value;
      select.dispatchEvent(new Event('change'));
    },
    system(dark) {
      media.matches = dark;
      media.dispatchEvent(new Event('change'));
    },
    storage(key, newValue, storageArea = globalThis.localStorage) {
      const event = Object.assign(new Event('storage'), { key, newValue, storageArea });
      win.dispatchEvent(event);
    },
    preference(value) {
      win.dispatchEvent(new CustomEvent('mctier-theme-preference-changed', { detail: value }));
    },
  };
}

test('first visit and invalid preferences follow the system, while explicit choices persist and survive reload', () => {
  for (const value of [null, 'unknown']) {
    const f = fixture({ value, dark: false });
    assert.equal(f.select.value, 'system');
    assert.equal(f.root.dataset.theme, 'light');
    f.system(true);
    assert.equal(f.root.dataset.theme, 'dark');
    f.change('light');
    assert.equal(f.data.get(KEY), 'light');
    f.system(true);
    assert.equal(f.root.dataset.theme, 'light');
    f.cleanup();
    const reload = fixture({ value: f.data.get(KEY), dark: true });
    assert.equal(reload.select.value, 'light');
    assert.equal(reload.root.dataset.theme, 'light');
    reload.change('dark');
    assert.equal(reload.root.dataset.theme, 'dark');
    assert.equal(reload.data.get(KEY), 'dark');
    reload.change('system');
    reload.system(false);
    assert.equal(reload.root.dataset.theme, 'light');
    assert.equal(reload.data.get(KEY), 'system');
    reload.cleanup();
  }
});

test('storage changes synchronize tabs, removals restore the system, and unrelated/invalid events are safe', () => {
  const f = fixture({ value: 'dark' });
  f.storage('unrelated', 'light');
  f.storage(KEY, 'light', {}); // sessionStorage does not alter this preference.
  f.preference('invalid');
  f.change('invalid');
  assert.equal(f.root.dataset.theme, 'dark');
  assert.equal(f.select.value, 'dark');
  f.storage(KEY, 'light');
  assert.equal(f.root.dataset.theme, 'light');
  assert.equal(f.select.value, 'light');
  f.preference('dark');
  assert.equal(f.root.dataset.theme, 'dark');
  f.storage(KEY, null);
  assert.equal(f.select.value, 'system');
  assert.equal(f.root.dataset.theme, 'light');
  f.storage(KEY, 'bad-value');
  assert.equal(f.select.value, 'system');
  f.storage(null, null); // localStorage.clear() in another tab.
  f.system(true);
  assert.equal(f.root.dataset.theme, 'dark');
  f.cleanup();
});

test('storage denied by browser leaves working in-memory themes and a truthful save warning', () => {
  const f = fixture({ readBlocked: true, writeBlocked: true });
  assert.equal(f.root.dataset.theme, 'light');
  assert.match(f.status.textContent, /未允许保存/);
  f.change('dark');
  f.system(false);
  assert.equal(f.root.dataset.theme, 'dark');
  assert.equal(f.data.has(KEY), false);
  assert.match(f.status.textContent, /仅在此页面/);
  f.cleanup();
});

test('disposing the controller removes select, media and window listeners', () => {
  const f = fixture({ value: 'light' });
  f.cleanup();
  f.change('dark');
  f.preference('dark');
  f.storage(KEY, 'dark');
  f.system(true);
  assert.equal(f.root.dataset.theme, 'light');
  assert.equal(f.data.get(KEY), 'light');
});
