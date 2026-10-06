// Linux release and upstream compatibility versions are separate values.
import { UPSTREAM_VERSION } from '../frontend-src/services/platform/linuxWebVersion';
export const DEFAULT_REPORTED_VERSION = UPSTREAM_VERSION;
const KEY = 'mctier-linux-web-reported-version-v1';
let sessionVersion: string | undefined;
let detectedDefault = DEFAULT_REPORTED_VERSION;
export function defaultReportedVersion(): string { return detectedDefault; }
export function setDefaultReportedVersion(value: string): void { detectedDefault = validateReportedVersion(value); }
export function validateReportedVersion(value: string): string {
  const version = value.trim();
  if (!/^\d{1,6}\.\d{1,6}(?:\.\d{1,6})?$/.test(version))
    throw new Error('客户端上报版本请填写数字版本，例如 3.10.0 或 3.10');
  return version;
}
export function savedReportedVersion(): string {
  try { return validateReportedVersion(localStorage.getItem(KEY) || detectedDefault); }
  catch { return detectedDefault; }
}
export function reportedVersionIsCustom(): boolean {
  try { validateReportedVersion(localStorage.getItem(KEY) || ''); return true; }
  catch { return false; }
}
export function saveReportedVersion(value: string): void {
  localStorage.setItem(KEY, validateReportedVersion(value));
}
export function resetReportedVersion(): void { localStorage.removeItem(KEY); }
export function beginReportedVersion(): string { return sessionVersion = savedReportedVersion(); }
export function endReportedVersion(): void { sessionVersion = undefined; }
export function reportedVersion(): string { return sessionVersion ?? savedReportedVersion(); }
