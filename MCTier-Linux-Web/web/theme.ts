import {
  isThemePreference,
  persistThemePreference,
  readThemePreference,
  resolveTheme,
  THEME_CHANGED_EVENT,
  THEME_STORAGE_KEY,
  type ThemePreference,
} from '../frontend-src/theme/themePreference';

// Reuse upstream preference values/key/events without mounting the Tauri App.
export function setupTheme() {
  const select = document.getElementById('theme-preference') as HTMLSelectElement;
  const status = document.getElementById('theme-status')!;
  const system = window.matchMedia('(prefers-color-scheme: dark)');
  let preference: ThemePreference = 'system';
  let saved = true;
  try {
    preference = readThemePreference();
  } catch {
    saved = false;
  }

  const apply = () => {
    document.documentElement.dataset.theme = resolveTheme(preference, system.matches);
    select.value = preference;
    status.textContent = saved
      ? '主题在本浏览器保存；跟随系统会自动响应系统外观变化。界面语言暂不支持切换。'
      : '浏览器未允许保存主题；当前选择仅在此页面生效。界面语言暂不支持切换。';
  };
  const change = () => {
    if (!isThemePreference(select.value)) {
      apply();
      return;
    }
    preference = select.value;
    try {
      persistThemePreference(preference);
      saved = true;
    } catch {
      saved = false;
    }
    apply();
  };
  const changed = (event: Event) => {
    const value = (event as CustomEvent<unknown>).detail;
    if (isThemePreference(value)) {
      preference = value;
      apply();
    }
  };
  const storage = (event: StorageEvent) => {
    if (event.key !== THEME_STORAGE_KEY && event.key !== null) return;
    // sessionStorage uses a separate preference scope.
    try {
      if (event.storageArea && event.storageArea !== localStorage) return;
    } catch {
      return;
    }
    preference = isThemePreference(event.newValue) ? event.newValue : 'system';
    saved = true;
    apply();
  };
  select.addEventListener('change', change);
  system.addEventListener('change', apply);
  window.addEventListener(THEME_CHANGED_EVENT, changed);
  window.addEventListener('storage', storage);
  apply();
  return () => {
    select.removeEventListener('change', change);
    system.removeEventListener('change', apply);
    window.removeEventListener(THEME_CHANGED_EVENT, changed);
    window.removeEventListener('storage', storage);
  };
}
