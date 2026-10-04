import test from 'node:test';
import assert from 'node:assert/strict';
import { build } from 'esbuild';
const bundle = await build({
  entryPoints: ['MCTier-Linux-Web/web/community.ts'],
  bundle: true,
  format: 'esm',
  write: false,
  plugins: [
    {
      name: 'ui-fixtures',
      setup(b) {
        b.onResolve(
          {
            filter:
              /qrcode$|services\/platform\/localWeb$|stores\/appStore$|services\/roomtools\/countdownService$/,
          },
          (args) => ({ path: args.path, namespace: 'fixture' })
        );
        b.onLoad({ filter: /.*/, namespace: 'fixture' }, (args) => ({
          contents:
            args.path === 'qrcode'
              ? 'export default {toDataURL:async()=>"data:image/png;base64,AAAA"}'
              : args.path.endsWith('localWeb')
                ? 'export const localHeaders=async()=>({"x-mctier-csrf":"nonce","x-mctier-client":"client"});export const localInvoke=(...args)=>globalThis.localFixture(...args)'
                : args.path.endsWith('appStore')
                  ? 'export const useAppStore={getState:()=>globalThis.storeFixture,subscribe:(fn)=>{globalThis.storeListener=fn;return ()=>{globalThis.storeListener=null}}}'
                  : 'export const countdownService={subscribe:fn=>{fn(null);return ()=>{}},start:n=>{globalThis.countdown=n},stop:()=>{globalThis.countdown=null}}',
        }));
      },
    },
  ],
});
const { setupCommunity } = await import(
  `data:text/javascript,${encodeURIComponent(bundle.outputFiles[0].text)}`
);
class Element {
  constructor(tag) {
    this.tag = tag;
    this.children = [];
    this.events = new Map();
    this.value = '';
    this.files = [];
    this.open = false;
    this.classList = {
      add: value => { this.className = `${this.className || ''} ${value}`.trim(); },
      remove: value => { this.className = (this.className || '').split(' ').filter(v => v !== value).join(' '); },
    };
  }
  append(...children) {
    this.children.push(...children);
  }
  replaceChildren(...children) {
    this.children = [...children];
  }
  querySelector(selector) {
    return (
      this.children.find(
        (child) => selector === '.feature-card' && child.className === 'feature-card'
      ) ?? null
    );
  }
  setAttribute(key, value) {
    this[key] = value;
  }
  addEventListener(name, fn) {
    this.events.set(name, fn);
  }
  showModal() {
    this.open = true;
  }
  close() {
    if (this.open) {
      this.open = false;
      this.events.get('close')?.();
    }
  }
  remove() {}
  click() {
    this.onclick?.();
  }
}
function fixture() {
  const body = new Element('body'),
    cards = new Map(
      ['lobby-history', 'invite', 'room-tools', 'folder-share'].map((id) => {
        const placeholder = new Element('placeholder'),
          card = new Element('card');
        card.className = 'feature-card';
        placeholder.append(card);
        return [id, placeholder];
      })
    );
  const connect = new Element('connect'),
    sidebar = new Element('sidebar');
  globalThis.document = {
    body,
    createElement: (tag) => new Element(tag),
    querySelector: (selector) =>
      selector === '.form-locks' ? connect : selector === '.sidebar-locks' ? sidebar : null,
    querySelectorAll: (selector) => {
      const id = selector.match(/data-feature="([^"]+)"/)?.[1];
      return cards.has(id) ? [cards.get(id)] : [];
    },
  };
  globalThis.window = { confirm: () => true };
  const saved = new Map();
  globalThis.localStorage = {
    getItem: (key) => saved.get(key) || null,
    setItem: (key, value) => saved.set(key, value),
  };
  const state = {
    todos: [],
    setTodos(items) {
      this.todos = items;
      globalThis.storeListener?.();
    },
  };
  globalThis.storeFixture = state;
  const calls = [],
    status = [],
    filled = [];
  let online = false,
    host = false,
    session = 'test-room';
  globalThis.localFixture = async (command, args) => {
    calls.push([command, args]);
    return command === 'get_local_shares'
      ? []
      : command === 'send_p2p_chat_message'
        ? { delivered: 0, total: 0 }
        : undefined;
  };
  const context = {
    online: () => online,
    session: () => session,
    busy: () => false,
    canJoin: () => true,
    invite: () => ({
      name: 'Room123',
      password: '',
      serverNode: 'tcp://node.example:11010',
      signalingServer: 'wss://signal.example/signaling',
    }),
    fill: (...values) => filled.push(values),
    playerId: () => 'local',
    playerName: () => 'Local',
    players: () => [],
    status: (...args) => status.push(args),
    text: async (value) => calls.push(['text', value]),
    isHost: () => host,
    publicState: () => false,
    publish: () => false,
  };
  const ui = setupCommunity(context);
  const dialog = body.children[0];
  const all = (root) => [root, ...root.children.flatMap(all)];
  const find = (text, root = dialog) =>
    all(root).find((n) => n.tag === 'button' && n.textContent === text);
  return {
    ui,
    cards,
    connect,
    dialog,
    calls,
    status,
    filled,
    all,
    find,
    setHost(value) {
      host = value;
    },
    setSession(value) {
      session = value;
    },
    setOnline(value) {
      online = value;
    },
  };
}
const tick = () => new Promise((resolve) => setImmediate(resolve));
test('community controls render tools and folder actions without auto joining or reading native paths', async () => {
  const f = fixture();
  assert.equal(f.calls.length, 0);
  for (const placeholder of f.cards.values()) {
    assert.equal(
      placeholder.children.length,
      1,
      'actions stay inside the card, not outside its layout'
    );
    assert.equal(placeholder.querySelector('.feature-card').children.at(-1).tag, 'button');
  }
  f.cards.get('room-tools').querySelector('.feature-card').children.at(-1).click();
  assert.equal(f.dialog.open, true);
  assert.ok(f.find('掷骰子'));
  assert.ok(f.find('开始'));
  assert.ok(f.find('添加'));
  const tabs=f.all(f.dialog).filter(n=>n['role']==='tab');
  const panels=f.all(f.dialog).filter(n=>n['role']==='tabpanel');
  assert.equal(tabs.length,3);
  assert.deepEqual(panels.map(n=>n.hidden),[false,true,true]);
  tabs[1].click();
  assert.deepEqual(panels.map(n=>n.hidden),[true,false,true]);
  tabs[2].click();
  assert.deepEqual(panels.map(n=>n.hidden),[true,true,false]);
  assert.equal(globalThis.countdown, undefined);
  f.find('开始').click();
  await tick();
  assert.equal(globalThis.countdown, 300);
  f.setOnline(true);
  const label = f.all(f.dialog).find((n) => n.tag === 'label' && n.textContent === '待办内容');
  label.children[0].value = 'Test todo';
  f.find('添加').click();
  await tick();
  const sent = f.calls.find(([command]) => command === 'send_p2p_chat_message');
  assert.equal(sent[1].messageType, 'todo');
  assert.equal(JSON.parse(sent[1].content)[0].text, 'Test todo');
  assert.equal(globalThis.storeFixture.todos.length, 1);
  f.ui.reset();
  assert.equal(f.dialog.open, false);
  assert.equal(globalThis.countdown, null);
  assert.equal(globalThis.storeFixture.todos.length, 0);
  assert.equal(globalThis.storeListener, null);
  f.cards.get('folder-share').querySelector('.feature-card').children.at(-1).click();
  await tick();
  assert.ok(f.find('选择目录并上传'));
  assert.deepEqual(f.calls.at(-1), ['get_local_shares', undefined]);
  assert.ok(f.all(f.dialog).some((n) => n.textContent?.includes('快照')));
  f.ui.reset();
});
test('invitation import only fills a form, invalid invitations never connect', async () => {
  const f = fixture();
  f.find('导入邀请', f.connect).click();
  const text = f.all(f.dialog).find((n) => n.tag === 'textarea');
  text.value =
    'mctier://join?v=3&name=Room123&node=tcp%3A%2F%2Fnode.example%3A11010&signal=wss%3A%2F%2Fsignal.example%2Fsignaling';
  f.find('解析并填入').click();
  await tick();
  assert.equal(f.filled.length, 1);
  assert.equal(f.filled[0][0].serverNode, 'tcp://node.example:11010');
  assert.equal(f.calls.length, 0);
  assert.equal(f.dialog.open, false);
  f.find('导入邀请', f.connect).click();
  f.all(f.dialog).find((n) => n.tag === 'textarea').value = 'mctier://join?v=999&name=Room123';
  f.find('解析并填入').click();
  await tick();
  assert.equal(f.filled.length, 1);
  assert.equal(f.status.at(-1)[1], true);
  f.ui.reset();
});

test('host resends the existing todo payload and late replies cannot restore a previous room state', async () => {
  const f = fixture();
  f.setOnline(true);
  f.setHost(true);
  const item = {
    id: 'todo-local-1',
    text: 'Current task',
    done: false,
    assignee: '',
    creator: 'Local',
    ts: 1,
  };
  globalThis.storeFixture.setTodos([item]);
  await f.ui.syncCurrentTodos();
  assert.equal(JSON.parse(f.calls.at(-1)[1].content)[0].text, 'Current task');
  f.cards.get('room-tools').querySelector('.feature-card').children.at(-1).click();
  let reply;
  globalThis.localFixture = async () =>
    new Promise((resolve) => {
      reply = resolve;
    });
  const label = f.all(f.dialog).find((n) => n.tag === 'label' && n.textContent === '待办内容');
  label.children[0].value = 'Old room task';
  f.find('添加').click();
  await tick();
  f.ui.reset();
  f.setSession('another-room');
  reply({ delivered: 1, total: 1 });
  await tick();
  assert.deepEqual(globalThis.storeFixture.todos, []);
});
