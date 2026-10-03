import { localBootstrap } from '../frontend-src/services/platform/localWeb';
export async function getVersion(): Promise<string> { return (await localBootstrap()).defaults.version; }
