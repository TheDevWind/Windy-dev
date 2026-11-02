// Petit serveur local qui sert web/ comme Netlify (même repli vers index.html pour toutes les adresses).
// npm run serve  →  http://localhost:8080  (avec la vraie base Supabase de config.js)
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { extname, join, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = fileURLToPath(new URL('../web/', import.meta.url));
const TYPES = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.svg': 'image/svg+xml',
  '.woff2': 'font/woff2', '.txt': 'text/plain; charset=utf-8', '.png': 'image/png', '.json': 'application/json' };

export function serve(port = 8080, overrides = {}, headers = {}) {
  const server = createServer(async (req, res) => {
    const path = decodeURIComponent(new URL(req.url, 'http://x').pathname);
    if (overrides[path]) { res.writeHead(200, { ...headers, 'content-type': TYPES[extname(path)] || 'text/plain' }); return res.end(overrides[path]); }
    const file = normalize(join(ROOT, path));
    if (!file.startsWith(ROOT)) { res.writeHead(403); return res.end(); }
    try {
      const body = await readFile(file);
      res.writeHead(200, { ...headers, 'content-type': TYPES[extname(file)] || 'application/octet-stream' });
      res.end(body);
    } catch {
      res.writeHead(200, { ...headers, 'content-type': TYPES['.html'] });
      res.end(await readFile(join(ROOT, 'index.html')));
    }
  });
  return new Promise(ok => server.listen(port, '127.0.0.1', () => ok(server)));
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const port = Number(process.env.PORT) || 8080;
  const csp = (await readFile(new URL('../../netlify.toml', import.meta.url), 'utf8')).match(/Content-Security-Policy = "([^"]+)"/)[1];
  await serve(port, {}, { 'content-security-policy': csp });
  console.log('Windy Desk sur http://localhost:' + port);
}
