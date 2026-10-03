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
  const show = (next: View) => {
    for (const candidate of views) element(`${candidate}-view`).hidden = candidate !== next;
    view = next;
    document.title = `MCTier · ${next === 'home' ? 'Linux Web' : next === 'lobby' ? '大厅' : element(`${next}-view`).getAttribute('aria-label')}`;
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
      if (!browser.canJoin) return;
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
    show(returnView());
  };
  const setPanel = (panel: Panel) => {
    for (const candidate of ['chat', 'screen', 'diagnostics'] as const)
      element(`${candidate}-panel`).hidden = panel !== candidate;
    for (const button of document.querySelectorAll<HTMLButtonElement>('[data-panel]'))
      button.setAttribute('aria-pressed', String(button.dataset.panel === panel));
  };
  for (const button of document.querySelectorAll<HTMLButtonElement>('[data-panel]'))
    button.onclick = () => setPanel(button.dataset.panel as Panel);
  document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape' && view !== 'lobby' && view !== 'home') {
      show(returnView());
    }
  });

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
    state.textContent = feature.state === 'blocked' ? '暂未开放' : '实验性 · 待验收';
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
    canJoin: browser.canJoin,
    blockedReason: browser.warning,
    setSessionState(next: typeof session) {
      const wasOnline = session === 'online';
      session = next;
      element('leave').hidden = next === 'idle';
      element<HTMLButtonElement>('copy-ip').disabled = next !== 'online';
      if (next === 'online') {
        setPanel('chat');
        show('lobby');
      } else if (next === 'orphaned') show('connect');
      else if (next === 'idle' && wasOnline) show('home');
    },
  };
}
