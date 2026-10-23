// Copies the production build into the backend so a single jar serves both API and web app.
import { cpSync, rmSync, mkdirSync, existsSync } from 'node:fs';
import { resolve } from 'node:path';

const from = resolve('dist/web/browser');
const to = resolve('../backend/src/main/resources/static');
if (!existsSync(from)) throw new Error(`Build output not found: ${from}`);
rmSync(to, { recursive: true, force: true });
mkdirSync(to, { recursive: true });
cpSync(from, to, { recursive: true });
console.log(`Embedded ${from} -> ${to}`);
