import { reportedVersion } from './clientVersion';
export async function getVersion(): Promise<string> { return reportedVersion(); }
