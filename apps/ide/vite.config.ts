import { fileURLToPath } from 'node:url'
import { defineConfig, type Plugin } from 'vite'

// The workbench injects VS Code CSS itself, so it must be imported as a string.
const vscodeCssAsString: Plugin = {
  name: 'load-vscode-css-as-string',
  enforce: 'pre',
  async resolveId(source, importer, options) {
    const resolved = await this.resolve(source, importer, options)
    if (resolved == null) {
      throw new Error(`Unable to resolve: ${source} from ${importer}`)
    }
    if (/node_modules\/(@codingame\/monaco-vscode|vscode|monaco-editor).*\.css$/.test(resolved.id)) {
      return { ...resolved, id: resolved.id + '?inline' }
    }
    return undefined
  }
}

export default defineConfig({
  base: process.env.BASE_PATH ?? './',
  build: {
    target: 'esnext',
    rollupOptions: {
      input: {
        main: fileURLToPath(new URL('index.html', import.meta.url)),
        sim: fileURLToPath(new URL('sim.html', import.meta.url))
      }
    }
  },
  worker: {
    format: 'es'
  },
  plugins: [vscodeCssAsString],
  optimizeDeps: {
    include: [
      '@codingame/monaco-vscode-api',
      '@codingame/monaco-vscode-api/extensions',
      '@codingame/monaco-vscode-api/monaco',
      'vscode/localExtensionHost'
    ]
  },
  resolve: {
    dedupe: ['vscode'],
    // just-bash's browser build imports node:zlib for gzip
    alias: { 'node:zlib': fileURLToPath(new URL('src/frc/terminal/zlibShim.ts', import.meta.url)) }
  }
})
