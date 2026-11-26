import { defineConfig } from 'vite';
import preact from '@preact/preset-vite';

// base './' so the same build loads from the Go server at /, from Tauri's custom protocol, and
// from the Android WebView's asset loader path without rewriting URLs.
export default defineConfig({
  base: './',
  plugins: [preact()],
  build: { outDir: 'dist', emptyOutDir: true, target: 'es2020', assetsInlineLimit: 0 },
  server: { port: 5173 },
});
