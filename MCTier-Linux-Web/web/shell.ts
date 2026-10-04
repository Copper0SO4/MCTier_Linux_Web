import { FEATURES, browserAvailability } from './featureAvailability';

type View = 'home' | 'connect' | 'lobby' | 'settings' | 'capabilities' | 'about';
type Panel = 'chat' | 'screen' | 'diagnostics';
const element = <T extends HTMLElement = HTMLElement>(id: string) =>
  document.getElementById(id) as T;

export function setupShell() {
  const browser = browserAvailability(navigator.userAgent);
  let view: View = 'home',
    session: 'idle' | 'connecting' | 'online' | 'orphaned' = 'idle';
  let previous: View = 'home';
  const views: View[] = ['home', 'connect', 'lobby', 'settings', 'capabilities', 'about'];
  const scrollPositions = new Map<View, number>();
  const show = (next: View) => {
    scrollPositions.set(view, window.scrollY);
    for (const candidate of views) element(`${candidate}-view`).hidden = candidate !== next;
    view = next;
    element('return-room').hidden = session === 'idle' || next === returnView();
    element('session-navigation').hidden = session === 'idle' || next === returnView();
    element('session-navigation').textContent =
      session === 'online'
        ? '大厅连接保持中 · 查看其他页面不会退出大厅；可点击右上角返回大厅。'
        : '大厅连接处理中 · 可点击右上角返回连接页面查看进度。';
    for (const button of document.querySelectorAll<HTMLButtonElement>('[data-view]'))
      button.setAttribute('aria-current', button.dataset.view === next ? 'page' : 'false');
    document.title = `MCTier · ${next === 'home' ? 'Linux Web' : next === 'lobby' ? '大厅' : element(`${next}-view`).getAttribute('aria-label')}`;
    window.scrollTo({ top: scrollPositions.get(next) ?? 0, behavior: 'instant' });
    const heading = element(`${next}-view`).querySelector<HTMLElement>('h1,h2');
    if (heading) {
      heading.tabIndex = -1;
      heading.focus({ preventScroll: true });
    }
  };
  const returnView = () =>
    session === 'online'
      ? 'lobby'
      : session === 'connecting' || session === 'orphaned'
        ? 'connect'
        : 'home';
  const showUtility = (next: View) => {
    if (view === 'home' || view === 'connect' || view === 'lobby') previous = view;
    show(next);
  };
  for (const button of document.querySelectorAll<HTMLButtonElement>('[data-connect]')) {
    button.disabled = !browser.canJoin;
    button.title = browser.canJoin ? '' : browser.warning;
    button.onclick = () => {
      if (!browser.canJoin || session !== 'idle') return;
      const create = button.dataset.connect === 'create';
      element('form-title').textContent = create ? '创建大厅' : '加入大厅';
      element('join').textContent = create ? '创建 / 连接大厅' : '加入大厅';
      show('connect');
      element<HTMLInputElement>('player-name').focus();
    };
  }
  for (const button of document.querySelectorAll<HTMLButtonElement>('[data-view]'))
    button.onclick = () => showUtility(button.dataset.view as View);
  for (const button of document.querySelectorAll<HTMLButtonElement>('[data-back]'))
    button.onclick = () =>
      show(
        session !== 'idle'
          ? returnView()
          : view === 'connect'
            ? 'home'
            : previous === 'connect'
              ? 'connect'
              : 'home'
      );
  element('home-link').onclick = (event) => {
    event.preventDefault();
    show('home');
  };
  element('return-room').onclick = () => show(returnView());
  const setPanel = (panel: Panel) => {
    for (const candidate of ['chat', 'screen', 'diagnostics'] as const)
      element(`${candidate}-panel`).hidden = panel !== candidate;
    for (const button of document.querySelectorAll<HTMLButtonElement>('[data-panel]'))
      button.setAttribute('aria-pressed', String(button.dataset.panel === panel));
  };
  for (const button of document.querySelectorAll<HTMLButtonElement>('[data-panel]'))
    button.onclick = () => setPanel(button.dataset.panel as Panel);
  document.addEventListener('keydown', (event) => {
    if (event.defaultPrevented || event.isComposing || document.querySelector('dialog[open]'))
      return;
    if (event.key === 'Escape' && view !== 'lobby' && view !== 'home') {
      show(returnView());
    }
  });

  const userAccepted = new Set(['network', 'chat', 'voice', 'screen', 'send-file', 'invite']);
  function featureCard(id: string) {
    const feature = FEATURES.find((value) => value.id === id);
    if (!feature) throw new Error(`Unknown Linux feature: ${id}`);
    const card = document.createElement('div');
    card.className = `feature-card ${feature.state}`;
    const header = document.createElement('div');
    header.className = 'feature-heading';
    const name = document.createElement('strong');
    name.textContent = feature.name;
    const state = document.createElement('span');
    state.className = 'feature-state';
    state.textContent =
      feature.state === 'blocked'
        ? '暂未开放'
        : userAccepted.has(id)
          ? '已接入 · 用户已验收'
          : '已接入 · 实验性';
    if (userAccepted.has(id)) state.classList.add('user-accepted');
    header.append(name, state);
    const reason = document.createElement('p');
    reason.textContent = feature.reason;
    card.append(header, reason);
    if (feature.state === 'blocked') {
      const button = document.createElement('button');
      button.type = 'button';
      button.disabled = true;
      button.textContent = '暂不可用';
      button.title = feature.reason;
      button.setAttribute('aria-label', `${feature.name}：暂不可用，${feature.reason}`);
      card.append(button);
    }
    return card;
  }
  for (const placeholder of document.querySelectorAll<HTMLElement>('[data-feature]'))
    placeholder.replaceChildren(featureCard(placeholder.dataset.feature!));
  for (const feature of FEATURES) element('feature-matrix').append(featureCard(feature.id));
  for (const [index, card] of [...element('feature-matrix').children].entries())
    (card as HTMLElement).dataset.featureId = FEATURES[index].id;
  element('browser-warning').hidden = browser.canJoin;
  element('browser-warning').textContent = browser.canJoin
    ? ''
    : `Firefox 大厅入口暂未开放：${browser.warning}`;
  element<HTMLButtonElement>('join').disabled = !browser.canJoin;
  const outputSupported = 'setSinkId' in HTMLMediaElement.prototype;
  element<HTMLSelectElement>('audio-output').disabled = !outputSupported;
  element('output-support').textContent = outputSupported
    ? '当前浏览器支持切换输出设备。'
    : '当前浏览器未提供输出设备切换；暂不可用，使用系统默认。';

  return {
    showConnect() {
      show(session === 'idle' ? 'connect' : returnView());
    },
    canJoin: browser.canJoin,
    blockedReason: browser.warning,
    setSessionState(next: typeof session) {
      const prior = session;
      session = next;
      for (const button of document.querySelectorAll<HTMLButtonElement>('[data-connect]')) {
        button.disabled = !browser.canJoin || next !== 'idle';
        button.title = !browser.canJoin
          ? browser.warning
          : next !== 'idle'
            ? '请先退出当前大厅，再创建或加入另一个大厅。'
            : '';
      }
      element('return-room').textContent = next === 'online' ? '返回大厅' : '返回连接';
      element('return-room').hidden = next === 'idle' || view === returnView();
      element('session-navigation').hidden = next === 'idle' || view === returnView();
      element('leave').hidden = next === 'idle';
      element<HTMLButtonElement>('copy-ip').disabled = next !== 'online';
      if (next === prior) return;
      if (next === 'online') {
        setPanel('chat');
        show('lobby');
      } else if (next === 'orphaned') show('connect');
      else if (next === 'idle' && prior !== 'idle') show('home');
    },
  };
}
