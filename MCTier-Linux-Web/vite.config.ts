import { defineConfig } from 'vite';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';
const path = (relative: string) => fileURLToPath(new URL(relative, import.meta.url));
export default defineConfig({
  plugins: [
    {
      name: 'mctier-builtin-emoji',
      apply: 'build',
      closeBundle() {
        // Vite empties web-dist: every build must restore the authenticated pack.
        execFileSync(process.execPath, [path('./scripts/prepare-emoji.mjs')], { stdio: 'inherit' });
      },
    },
  ],
  root: path('./web/'),
  publicDir: path('../public/'),
  resolve: {
    alias: {
      '@tauri-apps/api/core': path('./web/tauriCore.ts'),
      '@tauri-apps/api/event': path('./web/tauriEvent.ts'),
      '@tauri-apps/api/app': path('./web/tauriApp.ts'),
    },
  },
  define: { 'import.meta.env.VITE_MCTIER_LOCAL_WEB': JSON.stringify('1') },
  esbuild: { pure: ['console.log', 'console.debug', 'console.info'], drop: ['debugger'] },
  build: { outDir: path('./web-dist/'), emptyOutDir: true, chunkSizeWarningLimit: 800 },
  base: '/',
});
