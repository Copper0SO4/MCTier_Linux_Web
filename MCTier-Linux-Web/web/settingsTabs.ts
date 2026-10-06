/** Keep desktop settings focused instead of stacking every tool in one long page. */
export function setupSettingsTabs() {
  const settings = document.getElementById('settings-view')!;
  const profile = document.getElementById('profile-panel')!, statistics = document.getElementById('statistics-panel')!;
  const basic = document.createElement('div'); basic.id = 'basic-settings-panel'; basic.className = 'settings-basic';
  for (const child of [...settings.children]) {
    if (child !== profile && child !== statistics && !child.classList.contains('utility-header')) basic.append(child);
  }
  const tabs = document.createElement('div'); tabs.className = 'tool-tabs settings-tabs'; tabs.setAttribute('role', 'tablist'); tabs.setAttribute('aria-label', '软件设置');
  const panels = [basic, profile, statistics], buttons: HTMLButtonElement[] = [];
  function select(index: number) {
    panels.forEach((panel, at) => { panel.hidden = at !== index; buttons[at].setAttribute('aria-selected', String(at === index)); buttons[at].tabIndex = at === index ? 0 : -1; });
  }
  ['外观与音频', '个人资料', '会话统计'].forEach((title, index) => {
    const button = document.createElement('button'); button.type = 'button'; button.textContent = title; button.id = `settings-tab-${index}`;
    button.setAttribute('role', 'tab'); button.setAttribute('aria-controls', panels[index].id);
    panels[index].setAttribute('role', 'tabpanel'); panels[index].setAttribute('aria-labelledby', button.id);
    button.onclick = () => select(index); buttons.push(button); tabs.append(button);
  });
  tabs.onkeydown = event => {
    let index = buttons.indexOf(document.activeElement as HTMLButtonElement); if (index < 0) return;
    if (event.key === 'ArrowRight') index = (index + 1) % 3;
    else if (event.key === 'ArrowLeft') index = (index + 2) % 3;
    else if (event.key === 'Home') index = 0;
    else if (event.key === 'End') index = 2;
    else return;
    event.preventDefault(); select(index); buttons[index].focus();
  };
  settings.insertBefore(basic, profile); settings.insertBefore(tabs, basic); select(0);
  return { profile: () => select(1), statistics: () => select(2) };
}
