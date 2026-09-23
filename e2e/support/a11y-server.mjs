// Serves the production web build with a mocked API, so the accessibility
// gate can render authenticated screens without a backend or database.
// usage: node support/a11y-server.mjs   (A11Y_PORT, WEB_DIST to override)
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { fixtures, me } from './a11y-fixtures.mjs';

const root = process.env.WEB_DIST
  ?? fileURLToPath(new URL('../../apps/web/dist/web/browser', import.meta.url));
const port = Number(process.env.A11Y_PORT ?? 4310);
const types = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.woff2': 'font/woff2', '.svg': 'image/svg+xml', '.json': 'application/json', '.ico': 'image/x-icon' };

createServer(async (request, response) => {
  const url = new URL(request.url ?? '/', 'http://localhost');
  if (url.pathname.startsWith('/api/')) {
    const key = url.pathname.replace(/^\/api\/v1/, '');
    const cursor = url.searchParams.get('cursor');
    const body = key === '/auth/me' ? me : (cursor && fixtures[`${key}#${cursor}`]) ?? fixtures[key];
    // Server-sent events and anything unmocked answer 404, as an absent endpoint would.
    response.writeHead(body === undefined ? 404 : 200, { 'content-type': 'application/json' });
    response.end(JSON.stringify(body ?? { title: 'Not mocked' }));
    return;
  }
  const file = path.join(root, path.normalize(url.pathname === '/' ? 'index.html' : url.pathname));
  let content;
  let type = types[path.extname(file)] ?? 'application/octet-stream';
  if (!file.startsWith(root)) {
    response.writeHead(403).end();
    return;
  }
  try {
    content = await readFile(file);
  } catch {
    // Client-side routes fall back to the application shell.
    content = await readFile(path.join(root, 'index.html'));
    type = 'text/html';
  }
  response.writeHead(200, { 'content-type': type }).end(content);
}).listen(port, '127.0.0.1', () => process.stdout.write(`a11y server on http://127.0.0.1:${port}\n`));
