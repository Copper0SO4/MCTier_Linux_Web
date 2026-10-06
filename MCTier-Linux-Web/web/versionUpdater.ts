import { versionCheckService } from '../frontend-src/services/version/VersionCheckService';
import { compareVersions } from '../frontend-src/services/version/versionPolicy';
import { DEFAULT_REPORTED_VERSION, defaultReportedVersion, setDefaultReportedVersion } from './clientVersion';
let active: Promise<string> | undefined;
let message = `默认上报 ${DEFAULT_REPORTED_VERSION}，尚未检测上游版本。`;
export function versionDetectionStatus(): string { return message; }
export function refreshReportedDefault(): Promise<string> {
  if (active) return active;
  active = (async () => {
    const result = await versionCheckService.fetchLatestVersion();
    if (!result) throw new Error('原版 Gitee 版本检测失败；保留现有默认值，不切换来源。');
    if (compareVersions(result.latestVersion, defaultReportedVersion()) > 0)
      setDefaultReportedVersion(result.latestVersion);
    message = `检测到上游 ${result.latestVersion} · 默认上报 ${defaultReportedVersion()} · 实际源码基线 ${DEFAULT_REPORTED_VERSION}`;
    window.dispatchEvent(new Event('mctier-version-default'));
    return message;
  })().catch(error => { message = error instanceof Error ? error.message : String(error); throw error; })
    .finally(() => { active = undefined; });
  return active;
}
