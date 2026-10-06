import test from 'node:test';
import assert from 'node:assert/strict';
import { build } from 'esbuild';
const output = await build({ entryPoints: ['MCTier-Linux-Web/web/settingsTabs.ts'], bundle: true, format: 'esm', write: false });
const { setupSettingsTabs } = await import(`data:text/javascript,${encodeURIComponent(output.outputFiles[0].text)}`);
class Element {
  constructor() { this.children = []; this.className = ''; this.classList = { contains: name => this.className.split(' ').includes(name) }; }
  append(child) { child.parent?.removeChild(child); child.parent = this; this.children.push(child); }
  removeChild(child) { this.children.splice(this.children.indexOf(child), 1); child.parent = null; }
  insertBefore(child, before) { child.parent?.removeChild(child); child.parent = this; this.children.splice(this.children.indexOf(before), 0, child); }
  setAttribute(key, value) { this[key] = value; }
  focus() { document.activeElement = this; }
}
test('settings tabs preserve existing controls and keyboard selection shows one panel', t => {
  const prior = globalThis.document;
  const settings = new Element(), profile = new Element(), stats = new Element(), header = new Element(), audio = new Element();
  profile.id = 'profile-panel'; stats.id = 'statistics-panel'; header.className = 'utility-header';
  for (const child of [header, audio, profile, stats]) settings.append(child);
  const nodes = { 'settings-view': settings, 'profile-panel': profile, 'statistics-panel': stats };
  globalThis.document = { getElementById: id => nodes[id], createElement: () => new Element() };
  t.after(() => { globalThis.document = prior; });
  const controller = setupSettingsTabs(), tabs = settings.children[1], basic = settings.children[2];
  assert.equal(basic.children[0], audio); assert.equal(profile.hidden, true); assert.equal(stats.hidden, true);
  controller.profile(); assert.equal(profile.hidden, false); assert.equal(basic.hidden, true);
  tabs.children[1].focus(); let prevented = false;
  tabs.onkeydown({ key: 'ArrowRight', preventDefault() { prevented = true; } });
  assert.ok(prevented); assert.equal(stats.hidden, false); assert.equal(profile.hidden, true); assert.equal(document.activeElement, tabs.children[2]);
  assert.equal(tabs.children[2]['aria-selected'], 'true');
});
