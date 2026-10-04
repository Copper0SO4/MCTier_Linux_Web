import QRCode from 'qrcode';
import {
  buildLobbyInviteLink,
  formatLobbyInviteText,
  parseLobbyInviteText,
  type LobbyInvite,
} from '../frontend-src/services/lobby/lobbyInvite';
import { fetchPublicLobbies } from '../frontend-src/services/lobby/publicLobbies';
import { localHeaders, localInvoke } from '../frontend-src/services/platform/localWeb';
import { useAppStore } from '../frontend-src/stores/appStore';
import { countdownService } from '../frontend-src/services/roomtools/countdownService';
import { MAX_TODO_ITEMS, sanitizeTodoItems } from '../frontend-src/security/trustBoundary';
import { FAVORITES_KEY, HISTORY_KEY, readSaved, saveLobby } from './lobbyData';
import type { Player } from '../frontend-src/types';

type Context = {
  online: () => boolean;
  session: () => unknown;
  busy: () => boolean;
  canJoin: () => boolean;
  invite: () => LobbyInvite;
  fill: (invite: LobbyInvite, playerName?: string) => void;
  playerId: () => string;
  playerName: () => string;
  players: () => Player[];
  status: (s: string, error?: boolean) => void;
  text: (s: string) => Promise<unknown>;
  isHost: () => boolean;
  publicState: () => boolean;
  publish: (enabled: boolean, description: string) => boolean;
  maxPlayers: () => number | null;
  setMaxPlayers: (max: number) => boolean;
  announcement: () => string;
  announce: (text: string) => Promise<{ delivered: number; total: number }>;
};
const node = <K extends keyof HTMLElementTagNameMap>(tag: K, text?: string) => {
  const n = document.createElement(tag);
  if (text !== undefined) n.textContent = text;
  return n;
};
const button = (text: string, action: () => unknown) => {
  const b = node('button', text);
  b.type = 'button';
  b.onclick = () => {
    void action();
  };
  return b;
};
const field = (title: string, type = 'text', value = '', max = 128) => {
  const label = node('label', title);
  const input = node('input');
  input.type = type;
  input.value = value;
  input.maxLength = max;
  label.append(input);
  return { label, input };
};
const message = (error: unknown) => (error instanceof Error ? error.message : String(error));
export function setupCommunity(ctx: Context) {
  const dialog = node('dialog');
  dialog.className = 'community-dialog';
  document.body.append(dialog);
  const heading = node('h2'),
    body = node('div'),
    close = button('关闭', () => dialog.close());
  const header = node('div');
  header.className = 'community-header';
  body.className = 'community-body';
  header.append(heading, close);
  dialog.append(header, body);
  let unsubscribe: (() => void) | null = null,
    generation = 0,
    controller: AbortController | null = null;
  const urls = new Set<string>();
  function dispose() {
    generation++;
    controller?.abort();
    controller = null;
    unsubscribe?.();
    unsubscribe = null;
    for (const url of urls) URL.revokeObjectURL(url);
    urls.clear();
    body.replaceChildren();
  }
  dialog.addEventListener('close', dispose);
  const open = (title: string) => {
    dispose();
    dialog.classList.remove('tool-dialog');
    heading.textContent = title;
    if (!dialog.open) dialog.showModal();
  };
  const run = async (action: () => Promise<unknown>) => {
    try {
      await action();
    } catch (error) {
      ctx.status(message(error), true);
    }
  };
  const active = () => {
    if (!ctx.online()) throw new Error('请先加入大厅');
  };
  const fill = (invite: LobbyInvite, playerName?: string) => {
    if (ctx.busy() || ctx.online()) throw new Error('请先退出当前大厅');
    if (!ctx.canJoin()) throw new Error('Firefox 大厅连接暂未开放，请使用 Chrome/Chromium');
    ctx.fill(invite, playerName);
    dialog.close();
    ctx.status(
      invite.serverNode && invite.signalingServer
        ? '大厅资料已填入，请核对节点和信令地址后手动加入。'
        : '此记录没有完整地址：仅填入已有字段，请手动填写并核对 EasyTier 节点和信令服务。'
    );
  };
  const append = (...nodes: Node[]) => body.append(...nodes);
  const hint = (s: string) => {
    const p = node('p', s);
    p.className = 'hint';
    return p;
  };
  function saved() {
    open('常用大厅 / 最近大厅');
    append(
      button('收藏当前表单', () =>
        run(async () => {
          await saveLobby(localStorage, FAVORITES_KEY, ctx.invite(), ctx.playerName());
          saved();
          ctx.status('已收藏；密码通过系统密钥环保护。');
        })
      ),
      button('查询公开广场', () => plaza())
    );
    for (const [key, title] of [
      [FAVORITES_KEY, '常用大厅'],
      [HISTORY_KEY, '最近加入'],
    ] as const) {
      append(node('h3', title));
      const list = readSaved(localStorage, key).sort(
        (a, b) => (b.lastUsedAt || 0) - (a.lastUsedAt || 0)
      );
      if (!list.length) append(hint('暂无记录。'));
      for (const item of list) {
        const row = node('div');
        row.className = 'community-row';
        row.append(
          node('strong', item.name),
          hint(`${item.serverNode || '未记录节点'} · ${item.signalingServer || '未记录信令地址'}`),
          button('填入', () =>
            run(async () => {
              fill(item, item.playerName);
              if (key === FAVORITES_KEY) {
                item.useCount++;
                item.lastUsedAt = Date.now();
                localStorage.setItem(key, JSON.stringify(list));
              }
            })
          ),
          button('删除', () => {
            localStorage.setItem(key, JSON.stringify(list.filter((v) => v.id !== item.id)));
            saved();
          })
        );
        append(row);
      }
    }
    append(
      hint(
        '收藏和最近记录仅保存在本浏览器；密码是系统密钥环加密值。密钥环不可用时明确报错，不保存明文。'
      )
    );
  }
  async function plaza() {
    open('公开广场');
    controller = new AbortController();
    const abort = controller,
      current = generation,
      signal = ctx.invite().signalingServer;
    append(
      hint(`信令服务：${signal}`),
      hint('仅查询公开大厅；填入后还需手动加入虚拟网络。查询不会更换当前服务器。'),
      button('刷新', () => plaza())
    );
    if (ctx.isHost()) append(button('房主管理 / 发布设置', hostPanel));
    const state = node('p', '查询中…');
    append(state);
    try {
      const lobbies = await fetchPublicLobbies(signal, 8000, abort.signal);
      if (current !== generation) return;
      state.textContent = lobbies.length ? '' : '暂无公开大厅。';
      for (const lobby of lobbies) {
        const row = node('div');
        row.className = 'community-row';
        row.append(
          node('strong', lobby.lobbyName),
          hint(
            `房主 ${lobby.hostName} · ${lobby.playerCount}${lobby.maxPlayers ? '/' + lobby.maxPlayers : ''} 人 · ${lobby.description}`
          )
        );
        const full = !!lobby.maxPlayers && lobby.playerCount >= lobby.maxPlayers;
        const join = button(lobby.serverNode ? '填入大厅' : '缺少节点，不能加入', () =>
          run(async () => {
            if (!lobby.serverNode)
              throw new Error('该公开大厅未提供 EasyTier 节点，请向房主索取邀请。');
            fill({
              name: lobby.lobbyName,
              password: '',
              serverNode: lobby.serverNode,
              signalingServer: signal,
            });
          })
        );
        join.disabled = full || !lobby.serverNode || ctx.busy() || ctx.online() || !ctx.canJoin();
        row.append(join);
        append(row);
      }
    } catch (error) {
      if (current === generation) state.textContent = `查询失败：${message(error)}`;
    }
  }
  function hostPanel() {
    active();
    if (!ctx.isHost()) throw new Error('只有当前房主可以管理大厅');
    open('房主管理');
    const max = field('人数上限（0 为不限）', 'number', String(ctx.maxPlayers() ?? 0), 6);
    max.input.min = '0'; max.input.max = '64'; max.input.step = '1';
    const maxStatus = hint(`当前 ${ctx.players().length} 人 · 信令确认上限 ${ctx.maxPlayers() ?? '不限'}`);
    const descriptionKey = `mctier_lobby_description_${ctx.invite().name}`;
    let savedDescription = '';
    try { savedDescription = localStorage.getItem(descriptionKey) || ''; } catch { /* 浏览器可能禁用本地存储 */ }
    const publicDescription = field('公开简介（最多 100 字）', 'text', savedDescription, 100);
    const announceDraft = node('textarea');
    announceDraft.value = ctx.announcement();
    announceDraft.maxLength = 200;
    announceDraft.rows = 3;
    const publicStatus = hint(ctx.publicState() ? '当前已在公开广场' : '当前未公开');
    const publicButton = button(ctx.publicState() ? '撤销公开' : '发布当前大厅', () => run(async () => {
      active(); if (!ctx.isHost()) throw new Error('房主身份已变更');
      const enabled = !ctx.publicState();
      if (enabled && ctx.invite().password) throw new Error('原版公开广场只支持无密码大厅');
      if (enabled && !window.confirm('公开后陌生人可以加入此大厅及虚拟网络。确认发布？')) return;
      if (!ctx.publish(enabled, publicDescription.input.value)) throw new Error('信令未连接，公开状态请求未发送');
      try { localStorage.setItem(descriptionKey, publicDescription.input.value); } catch { /* 仅影响表单预填 */ }
      ctx.status('公开状态请求已发送，等待信令服务确认。');
    }));
    const refreshState = () => {
      maxStatus.textContent = `当前 ${ctx.players().length} 人 · 信令确认上限 ${ctx.maxPlayers() ?? '不限'}`;
      publicStatus.textContent = ctx.publicState() ? '当前已在公开广场' : '当前未公开';
      publicButton.textContent = ctx.publicState() ? '撤销公开' : '发布当前大厅';
      publicButton.disabled = !ctx.isHost();
    };
    window.addEventListener('mctier-lobby-options', refreshState);
    unsubscribe = () => window.removeEventListener('mctier-lobby-options', refreshState);
    append(
      node('h3', '人数限制'), max.label, maxStatus,
      button('应用人数上限', () => run(async () => {
        active(); if (!ctx.isHost()) throw new Error('房主身份已变更');
        const value = max.input.value.trim() === '' ? NaN : Number(max.input.value);
        if (!Number.isInteger(value) || value < 0 || value > 64) throw new Error('人数上限须为 0–64');
        if (value !== 0 && value < ctx.players().length) throw new Error('人数上限不能低于当前在线人数');
        if (!ctx.setMaxPlayers(value)) throw new Error('信令未连接，人数上限请求未发送');
        ctx.status('人数上限请求已发送，等待信令服务确认。');
      })),
      node('h3', '公开广场'), publicDescription.label, publicStatus,
      publicButton,
      hint('人数限制和公开状态均使用原版 set-lobby-options；以信令服务回报的状态为准。密码大厅不能公开。'),
      node('h3', '大厅公告'),
      hint('公告经 EasyTier 聊天数据链路发送，新成员加入时房主补发。留空发布可清除公告。'),
      announceDraft,
      button('发布公告', () => run(async () => {
        active(); if (!ctx.isHost()) throw new Error('房主身份已变更');
        const receipt = await ctx.announce(announceDraft.value.trim());
        ctx.status(receipt.total && receipt.delivered < receipt.total
          ? `公告只送达 ${receipt.delivered}/${receipt.total}，请检查 EasyTier 对端链路。`
          : '公告已发出；对端显示仍需实际确认。', receipt.delivered < receipt.total);
      }))
    );
  }
  function importInvite() {
    open('导入大厅邀请');
    append(hint('粘贴原版邀请文字或 mctier://join 链接。导入只填表，不自动连接。'));
    const text = node('textarea');
    text.maxLength = 8192;
    text.rows = 7;
    append(
      text,
      button('解析并填入', () =>
        run(async () => {
          const invite = parseLobbyInviteText(text.value);
          if (!invite) throw new Error('邀请格式或地址无效');
          fill(invite);
        })
      ),
      hint('也可读取二维码图片（需要浏览器支持二维码识别）；摄像头扫描暂不开放。')
    );
    const image = node('input');
    image.type = 'file';
    image.accept = 'image/png,image/jpeg,image/webp';
    image.onchange = () =>
      run(async () => {
        const file = image.files?.[0];
        if (!file) return;
        if (file.size > 8 * 1024 * 1024) throw new Error('二维码图片不能超过 8 MiB');
        const Detector = (
          window as unknown as {
            BarcodeDetector?: new (options: { formats: string[] }) => {
              detect: (source: ImageBitmap) => Promise<{ rawValue: string }[]>;
            };
          }
        ).BarcodeDetector;
        if (!Detector) throw new Error('此浏览器不支持二维码图片识别，请粘贴邀请文字');
        const current = generation,
          bitmap = await createImageBitmap(file);
        try {
          const result = await new Detector({ formats: ['qr_code'] }).detect(bitmap);
          if (current !== generation) return;
          const invite = result.map((v) => parseLobbyInviteText(v.rawValue)).find(Boolean);
          if (!invite) throw new Error('图片中没有有效的 MCTier 邀请');
          fill(invite);
        } finally {
          bitmap.close();
        }
      });
    append(image);
  }
  async function exportInvite() {
    open('大厅二维码 / 邀请');
    const current = generation,
      invite = ctx.invite();
    append(hint('邀请包含加入大厅所需的凭据，只发给信任的人；生成和复制不连接服务器。'));
    try {
      const text = await formatLobbyInviteText(invite, 'zh');
      const link = await buildLobbyInviteLink(invite);
      const qr = await QRCode.toDataURL(link, { width: 320, errorCorrectionLevel: 'M' });
      if (current !== generation) return;
      const image = node('img');
      image.src = qr;
      image.alt = '大厅邀请二维码';
      image.className = 'invite-qr';
      const textarea = node('textarea');
      textarea.readOnly = true;
      textarea.rows = 8;
      textarea.value = text;
      append(
        image,
        textarea,
        button('复制邀请', () =>
          run(async () => {
            await navigator.clipboard.writeText(text);
            ctx.status('邀请已复制。');
          })
        )
      );
      const download = node('a', '保存二维码');
      download.href = qr;
      download.download = 'mctier-lobby-invite.png';
      append(download);
    } catch (error) {
      if (current === generation) append(hint(`生成失败：${message(error)}`));
    }
  }
  const synchronizeTodos = async (next: ReturnType<typeof sanitizeTodoItems>) => {
    active();
    const session = ctx.session();
    const safe = sanitizeTodoItems(next);
    if (next.length !== safe.length || next.length > MAX_TODO_ITEMS)
      throw new Error('待办内容超出限制');
    await localInvoke('send_p2p_chat_message', {
      playerId: ctx.playerId(),
      playerName: '',
      content: JSON.stringify(safe),
      messageType: 'todo',
      imageData: null,
      peerIps: ctx
        .players()
        .filter((p) => p.id !== ctx.playerId())
        .map((p) => p.virtualIp),
    }).then((receipt: unknown) => {
      const r = receipt as { delivered: number; total: number };
      if (!ctx.online() || ctx.session() !== session) return;
      useAppStore.getState().setTodos(safe);
      if (r.delivered < r.total)
        ctx.status(`待办只送达 ${r.delivered}/${r.total}，请核对对端链路。`, true);
    });
  };
  function tools() {
    open('房间工具');
    dialog.classList.add('tool-dialog');
    const tabs = node('div');
    tabs.className = 'tool-tabs';
    tabs.setAttribute('role', 'tablist');
    tabs.setAttribute('aria-label', '房间工具');
    append(tabs);
    const panels: HTMLElement[] = [], tabButtons: HTMLButtonElement[] = [];
    const selectTab = (index: number) => {
      panels.forEach((panel, i) => { panel.hidden = i !== index; });
      tabButtons.forEach((tab, i) => {
        tab.setAttribute('aria-selected', String(i === index));
        tab.tabIndex = i === index ? 0 : -1;
      });
    };
    tabs.onkeydown = event => {
      let index = tabButtons.indexOf(document.activeElement as HTMLButtonElement);
      if (index < 0) return;
      if (event.key === 'ArrowRight') index = (index + 1) % tabButtons.length;
      else if (event.key === 'ArrowLeft') index = (index + tabButtons.length - 1) % tabButtons.length;
      else if (event.key === 'Home') index = 0;
      else if (event.key === 'End') index = tabButtons.length - 1;
      else return;
      event.preventDefault(); selectTab(index); tabButtons[index].focus();
    };
    const section = (title: string) => {
      const index = panels.length;
      const card = node('section');
      card.className = 'community-tool-section';
      card.id = `room-tool-panel-${index}`;
      card.hidden = index !== 0;
      card.setAttribute('role', 'tabpanel');
      card.setAttribute('aria-labelledby', `room-tool-tab-${index}`);
      const tab = button(title, () => selectTab(index));
      tab.id = `room-tool-tab-${index}`;
      tab.setAttribute('role', 'tab');
      tab.setAttribute('aria-controls', card.id);
      tab.setAttribute('aria-selected', String(index === 0));
      tab.tabIndex = index === 0 ? 0 : -1;
      panels.push(card); tabButtons.push(tab); tabs.append(tab);
      card.append(node('h3', title));
      append(card);
      return card;
    };
    const dice = section('掷骰子');
    const count = field('数量', 'number', '1'),
      sides = field('面数', 'number', '6'),
      broadcast = node('input');
    count.input.min = '1';
    count.input.max = '10';
    sides.input.min = '2';
    sides.input.max = '100';
    broadcast.type = 'checkbox';
    const share = node('label', '发送到大厅');
    share.append(broadcast);
    const result = node('p');
    result.className = 'community-result';
    const diceFields = node('div');
    diceFields.className = 'community-tool-grid';
    diceFields.append(count.label, sides.label);
    const diceActions = node('div');
    diceActions.className = 'community-tool-actions';
    diceActions.append(
      share,
      button('掷骰子', () =>
        run(async () => {
          const n = Number(count.input.value),
            d = Number(sides.input.value);
          if (!Number.isInteger(n) || n < 1 || n > 10 || !Number.isInteger(d) || d < 2 || d > 100)
            throw new Error('数量 1–10，面数 2–100');
          const values = Array.from({ length: n }, () => 1 + Math.floor(Math.random() * d)),
            sum = values.reduce((a, b) => a + b, 0);
          result.textContent = `${values.join('、')} · 总和 ${sum}`;
          if (broadcast.checked) {
            active();
            await ctx.text(
              `🎲 ${ctx.playerName()} 掷出了 ${values.join('、')}（${n}d${d}）${n > 1 ? `，总和 ${sum}` : ''}`
            );
          }
        })
      )
    );
    dice.append(diceFields, diceActions, result);
    const timer = section('本地倒计时');
    const seconds = field('秒数', 'number', '300');
    seconds.input.min = '1';
    seconds.input.max = '604800';
    const remaining = node('output');
    remaining.className = 'community-result';
    const timerActions = node('div');
    timerActions.className = 'community-tool-actions';
    timerActions.append(
      button('开始', () =>
        run(async () => {
          const total = Number(seconds.input.value);
          if (!Number.isInteger(total) || total < 1 || total > 604800)
            throw new Error('倒计时为 1 秒至 7 天');
          countdownService.start(total);
        })
      ),
      button('停止', () => countdownService.stop()),
    );
    timer.append(seconds.label, timerActions, remaining,
      hint('倒计时只在本页面运行，关闭工具面板后继续；退出大厅时停止。'));
    const unsubTimer = countdownService.subscribe((n) => {
      remaining.textContent =
        n === null ? '未计时' : `${Math.floor(n / 60)}:${String(n % 60).padStart(2, '0')}`;
    });
    const todo = field('待办内容', 'text', '', 200),
      list = node('div');
    const todos = section('协同待办');
    list.className = 'community-todo-list';
    const sync = synchronizeTodos;
    const render = () => {
      list.replaceChildren();
      for (const item of useAppStore.getState().todos) {
        const row = node('div');
        row.className = 'community-row';
        const check = node('input');
        check.type = 'checkbox';
        check.checked = item.done;
        check.disabled = !ctx.online();
        check.onchange = () =>
          run(async () => {
            await sync(
              useAppStore
                .getState()
                .todos.map((v) => (v.id === item.id ? { ...v, done: check.checked } : v))
            );
          });
        row.append(
          check,
          node('span', item.text),
          button('删除', () =>
            run(() => sync(useAppStore.getState().todos.filter((v) => v.id !== item.id)))
          )
        );
        list.append(row);
      }
    };
    const todoActions = node('div');
    todoActions.className = 'community-tool-actions';
    todoActions.append(
      button('添加', () =>
        run(async () => {
          const value = todo.input.value.trim();
          if (!value) return;
          await sync([
            ...useAppStore.getState().todos,
            {
              id: `todo-${ctx.playerId()}-${Date.now()}`,
              text: value,
              done: false,
              assignee: '',
              creator: ctx.playerName(),
              ts: Date.now(),
            },
          ]);
          todo.input.value = '';
        })
      ),
      button('清除已完成', () =>
        run(() => sync(useAppStore.getState().todos.filter((v) => !v.done)))
      )
    );
    todos.append(
      todo.label, todoActions, list,
      hint(
        '待办沿用原版“后写覆盖”协议；同时修改可能覆盖，不保证离线补发。倒计时只在本机运行。'
      )
    );
    const unsubStore = useAppStore.subscribe(render);
    unsubscribe = () => {
      unsubTimer();
      unsubStore();
    };
    render();
  }
  function folderPanel() {
    open('文件夹共享');
    const current = generation;
    append(
      hint(
        '共享浏览器选择的目录快照；本地文件后续变化不会自动同步。最多 16 个快照、1024 个文件、总量 256 MiB，单文件 64 MiB。空目录不保留。退出大厅后删除。'
      )
    );
    const password = field('共享密码（可选，ASCII 字符）', 'password'),
      expiry = field('有效期（分钟，0 为本次大厅会话）', 'number', '60');
    expiry.input.min = '0';
    expiry.input.max = '10080';
    const chooser = node('input');
    chooser.type = 'file';
    chooser.multiple = true;
    chooser.setAttribute('webkitdirectory', '');
    const publish = button('选择目录并上传', () => {
      if (ctx.online()) chooser.click();
      else ctx.status('请先加入大厅', true);
    });
    const progress = node('p');
    let pending: string | null = null;
    const cancel = button('取消上传', () => {
      controller?.abort();
      if (pending) void run(() => localInvoke('remove_shared_folder', { id: pending }));
    });
    append(password.label, expiry.label, publish, cancel, progress);
    chooser.onchange = () =>
      run(async () => {
        active();
        const files = [...(chooser.files || [])];
        if (!files.length) return;
        const lifetime = Number(expiry.input.value);
        if (!Number.isInteger(lifetime) || lifetime < 0 || lifetime > 10080)
          throw new Error('有效期须为 0–10080 分钟');
        const total = files.reduce((n, f) => n + f.size, 0);
        if (
          files.length > 1024 ||
          total > 256 * 1024 * 1024 ||
          files.some((f) => f.size > 64 * 1024 * 1024)
        )
          throw new Error('所选目录超过文件数量或大小限制');
        const root = files[0].webkitRelativePath.split('/')[0];
        if (!root || files.some((f) => !f.webkitRelativePath.startsWith(root + '/')))
          throw new Error('请只选择一个目录');
        controller = new AbortController();
        const abort = controller;
        publish.disabled = true;
        try {
          const created = await localInvoke<{ id: string }>('create_folder_snapshot', {
            name: root,
            password: password.input.value || null,
            lifetimeMinutes: lifetime,
          });
          pending = created.id;
          if (current !== generation || abort.signal.aborted)
            throw new DOMException('已取消', 'AbortError');
          for (let i = 0; i < files.length; i++) {
            progress.textContent = `上传 ${i + 1}/${files.length}：${files[i].name}`;
            const params = new URLSearchParams({
              id: created.id,
              path: files[i].webkitRelativePath.slice(root.length + 1),
            });
            const response = await fetch(`/api/folders/upload?${params}`, {
              method: 'POST',
              credentials: 'same-origin',
              headers: await localHeaders(),
              body: files[i],
              signal: abort.signal,
            });
            if (!response.ok)
              throw new Error(
                (await response.json().catch(() => null))?.error ||
                  `上传失败 HTTP ${response.status}`
              );
          }
          if (current !== generation || abort.signal.aborted)
            throw new DOMException('已取消', 'AbortError');
          await localInvoke('publish_folder_snapshot', { id: created.id });
          pending = null;
          ctx.status('目录快照已发布，可由同大厅成员访问。');
          if (current === generation) folderPanel();
        } catch (error) {
          if (pending) {
            await localInvoke('remove_shared_folder', { id: pending }).catch(() => {});
            pending = null;
          }
          if (!abort.signal.aborted) throw error;
        } finally {
          if (current === generation) publish.disabled = !ctx.online();
          chooser.value = '';
        }
      });
    append(
      button('刷新目录列表', () => folderPanel()),
      node('h3', '本机共享')
    );
    void run(async () => {
      const shares = await localInvoke<Summary[]>('get_local_shares');
      if (current !== generation) return;
      for (const share of shares) {
        const row = node('div');
        row.className = 'community-row';
        row.append(
          node('strong', share.name),
          hint(
            `${share.has_password ? '密码保护 · ' : ''}${share.expire_time ? '到期 ' + new Date(share.expire_time * 1000).toLocaleString() : '本次大厅会话'}`
          ),
          button('撤销', () =>
            run(async () => {
              await localInvoke('remove_shared_folder', { id: share.id });
              folderPanel();
            })
          )
        );
        append(row);
      }
      if (!shares.length) append(hint('暂无本机共享。'));
    });
    append(node('h3', '对端共享'));
    for (const player of ctx.players().filter((v) => v.id !== ctx.playerId()))
      append(button(`浏览 ${player.name} 的共享`, () => remoteShares(player)));
    if (ctx.players().length < 2)
      append(hint('暂无对端成员；信令在线不等于 EasyTier 文件服务可达。'));
  }
  type Summary = { id: string; name: string; has_password: boolean; expire_time?: number };
  async function remoteShares(player: Player) {
    open(`${player.name} 的共享`);
    const current = generation;
    const status = node('p', '查询中…');
    append(status, button('返回共享', folderPanel));
    try {
      const data = await localInvoke<{ shares: Summary[] }>('remote_folder_shares', {
        playerId: player.id,
      });
      if (current !== generation) return;
      if (!Array.isArray(data.shares) || data.shares.length > 256)
        throw new Error('共享列表格式无效');
      status.textContent = data.shares.length ? '' : '该成员没有共享。';
      for (const share of data.shares) {
        const row = node('div');
        row.className = 'community-row';
        row.append(node('strong', String(share.name)));
        const password = field('共享密码', 'password');
        if (share.has_password) row.append(password.label);
        row.append(button('打开', () => remoteFiles(player, share, '', password.input.value)));
        append(row);
      }
    } catch (error) {
      if (current === generation) status.textContent = message(error);
    }
  }
  async function remoteFiles(player: Player, share: Summary, path: string, password: string) {
    open(`${player.name} · ${share.name}`);
    const current = generation;
    append(
      hint(path || '/'),
      button('共享列表', () => remoteShares(player))
    );
    if (path)
      append(
        button('上一级', () =>
          remoteFiles(player, share, path.split('/').slice(0, -1).join('/'), password)
        )
      );
    const status = node('p', '读取中…');
    append(status);
    try {
      const data = await localInvoke<{
        files: { name: string; path: string; size: number; is_dir: boolean }[];
      }>('remote_folder_files', { playerId: player.id, shareId: share.id, path, password });
      if (current !== generation) return;
      if (!Array.isArray(data.files) || data.files.length > 4096)
        throw new Error('目录列表超出限制');
      status.textContent = data.files.length ? '' : '目录为空。';
      for (const file of data.files) {
        const row = node('div');
        row.className = 'community-row';
        row.append(
          node(
            'span',
            `${file.is_dir ? '📁' : '📄'} ${file.name}${file.is_dir ? '' : ` · ${(file.size / 1024).toFixed(1)} KiB`}`
          )
        );
        const action = button(file.is_dir ? '打开' : '下载', () =>
          file.is_dir
            ? remoteFiles(player, share, file.path, password)
            : run(async () => {
                active();
                action.disabled = true;
                controller = new AbortController();
                const abort = controller;
                try {
                  const response = await fetch('/api/folders/download', {
                    method: 'POST',
                    credentials: 'same-origin',
                    headers: { 'Content-Type': 'application/json', ...(await localHeaders()) },
                    body: JSON.stringify({
                      playerId: player.id,
                      shareId: share.id,
                      path: file.path,
                      password,
                    }),
                    signal: abort.signal,
                  });
                  if (!response.ok)
                    throw new Error(
                      (await response.json().catch(() => null))?.error ||
                        `下载失败 HTTP ${response.status}`
                    );
                  const blob = await response.blob();
                  if (current !== generation || abort.signal.aborted) return;
                  const url = URL.createObjectURL(blob);
                  urls.add(url);
                  const link = node('a');
                  link.href = url;
                  link.download = String(file.name).replace(/[\\/\u0000-\u001f]/g, '_');
                  document.body.append(link);
                  link.click();
                  link.remove();
                  ctx.status('已交给浏览器保存。');
                } finally {
                  action.disabled = false;
                }
              })
        );
        action.disabled = !file.is_dir && file.size > 64 * 1024 * 1024;
        row.append(action);
        append(row);
      }
    } catch (error) {
      if (current === generation) status.textContent = message(error);
    }
  }
  for (const [id, name, action] of [
    ['lobby-management', '打开房主管理', hostPanel],
    ['lobby-history', '常用大厅 / 最近大厅', saved],
    ['invite', '生成大厅邀请', exportInvite],
    ['room-tools', '打开房间工具', tools],
    ['folder-share', '打开文件夹共享', folderPanel],
  ] as const) {
    for (const placeholder of document.querySelectorAll(`[data-feature="${id}"], #feature-matrix [data-feature-id="${id}"]`)) {
      const card = placeholder.querySelector('.feature-card') ?? placeholder;
      const actionButton = button(name, () =>
        run(async () => {
          await action();
        })
      );
      actionButton.className = 'feature-action';
      actionButton.title = card.querySelector('p')?.textContent || name;
      card.append(actionButton);
    }
  }
  document.querySelector('.sidebar-locks')?.append(button('公开广场 / 发布管理', plaza));
  const connect = document.querySelector('.form-locks');
  connect?.append(button('公开广场', plaza), button('导入邀请', importInvite));
  return {
    reset() {
      dialog.close();
      dispose();
      countdownService.stop();
      useAppStore.getState().setTodos([]);
    },
    async syncCurrentTodos() {
      if (ctx.isHost() && useAppStore.getState().todos.length)
        await synchronizeTodos(useAppStore.getState().todos);
    },
    async record() {
      await saveLobby(localStorage, HISTORY_KEY, ctx.invite(), ctx.playerName(), true);
    },
  };
}
