import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { defineConfig } from 'vite';

const here = dirname(fileURLToPath(import.meta.url));

// Dev shell that plays the workbench around the Sim GUI page, with a mock robot.
// The IDE's public folder supplies runtime/2026/wpilib/fieldImages.jar.
export default defineConfig({
  root: join(here, 'dev'),
  publicDir: join(here, '../../apps/ide/public'),
  server: { port: 5180 },
});
