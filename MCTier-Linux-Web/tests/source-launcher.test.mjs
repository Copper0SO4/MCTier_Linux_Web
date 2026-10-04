import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync, copyFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
const execute = promisify(execFile);
// Simulate an old embedded UI and a builder that embeds the current one. No real service or network.
for (const [args, profile] of [
  [[], 'debug'],
  [['--debug'], 'debug'],
  [['--release'], 'release'],
]) {
  test(`source launcher rebuilds an existing stale ${profile} binary with args ${args}`, async () => {
    const root = mkdtempSync(join(tmpdir(), 'mctier-launcher-'));
    try {
      const scripts = join(root, 'MCTier-Linux-Web/scripts');
      mkdirSync(scripts, { recursive: true });
      const target = join(root, 'MCTier-Linux-Web/server/target', profile);
      mkdirSync(target, { recursive: true });
      const binary = join(target, 'mctier-linux-web');
      writeFileSync(binary, "#!/bin/sh\nprintf 'STALE UI\\n'\n", { mode: 0o700 });
      const launcher = join(scripts, 'run-web-server.sh');
      copyFileSync('MCTier-Linux-Web/scripts/run-web-server.sh', launcher);
      writeFileSync(
        join(scripts, 'build-web-server.sh'),
        `#!/bin/bash\nset -euo pipefail\n[[ "\${1:-}" == "${profile === 'debug' ? '--debug' : ''}" ]]\ncat > '${binary}' <<'BIN'\n#!/bin/sh\nprintf 'CURRENT UI\\n'\nBIN\nchmod 700 '${binary}'\n`,
        { mode: 0o700 }
      );
      const { stdout: output } = await execute('bash', [launcher, ...args], { encoding: 'utf8' });
      assert.ok(output.includes('CURRENT UI'));
      assert.ok(!output.includes('STALE UI'));
    } finally {
      rmSync(root, { recursive: true, force: true });
    }
  });
}
