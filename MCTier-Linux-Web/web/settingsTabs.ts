/** Keep desktop settings focused instead of stacking every tool in one long page. */
export function setupSettingsTabs() {
  const settings = document.getElementById('settings-view')!;
  const profile = document.getElementById('profile-panel')!, statistics = document.getElementById('statistics-panel')!;
  const extra = [
    ['network-settings-panel', '网络与游戏'],
    ['lobby-settings-panel', '大厅与邀请'],
    ['desktop-settings-panel', '桌面集成'],
  ].map(([id, title]) => ({ panel: document.getElementById(id), title })).filter((item): item is { panel: HTMLElement; title: string } => !!item.panel);
  const basic = document.createElement('div'); basic.id = 'basic-settings-panel'; basic.className = 'settings-basic';
  for (const child of [...settings.children]) {
    if (child !== profile && child !== statistics && !extra.some(item => item.panel === child) && !child.classList.contains('utility-header')) basic.append(child);
  }
  const tabs = document.createElement('div'); tabs.className = 'tool-tabs settings-tabs'; tabs.setAttribute('role', 'tablist'); tabs.setAttribute('aria-label', '软件设置');
  const panels = [basic, profile, statistics, ...extra.map(item => item.panel)], buttons: HTMLButtonElement[] = [];
  function select(index: number) {
    panels.forEach((panel, at) => { panel.hidden = at !== index; buttons[at].setAttribute('aria-selected', String(at === index)); buttons[at].tabIndex = at === index ? 0 : -1; });
  }
  ['外观与音频', '个人资料', '会话统计', ...extra.map(item => item.title)].forEach((title, index) => {
    const button = document.createElement('button'); button.type = 'button'; button.textContent = title; button.id = `settings-tab-${index}`;
    button.setAttribute('role', 'tab'); button.setAttribute('aria-controls', panels[index].id);
    panels[index].setAttribute('role', 'tabpanel'); panels[index].setAttribute('aria-labelledby', button.id);
    button.onclick = () => select(index); buttons.push(button); tabs.append(button);
  });
  tabs.onkeydown = event => {
    let index = buttons.indexOf(document.activeElement as HTMLButtonElement); if (index < 0) return;
    if (event.key === 'ArrowRight') index = (index + 1) % buttons.length;
    else if (event.key === 'ArrowLeft') index = (index + buttons.length - 1) % buttons.length;
    else if (event.key === 'Home') index = 0;
    else if (event.key === 'End') index = buttons.length - 1;
    else return;
    event.preventDefault(); select(index); buttons[index].focus();
  };
  settings.insertBefore(basic, profile); settings.insertBefore(tabs, basic); select(0);
  return { profile: () => select(1), statistics: () => select(2) };
}
