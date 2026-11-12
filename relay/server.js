// Xcluice Blitz relay: pairs one uploader with one downloader per (room, channel) and pipes bytes
// straight through. Nothing is stored, and payloads are end-to-end encrypted by the apps.
const http = require('http');

const PORT = process.env.PORT || 10000;
const WAIT_MS = 90 * 1000;
const MAX_BODY = 40 * 1024 * 1024;
const MAX_WAITING = 4000;

const waiting = new Map(); // "room/ch" -> { type, req, res, timer }
const seen = new Map();    // room -> { s: ts, r: ts }

const RE_HI = /^\/hi\/([0-9a-f]{32})\/([sr])$/;
const RE_P = /^\/p\/([0-9a-f]{32})\/([A-Za-z0-9_-]{1,24})$/;

setInterval(() => {
  const cut = Date.now() - 60 * 1000;
  for (const [room, v] of seen) {
    if ((v.s || 0) < cut && (v.r || 0) < cut) seen.delete(room);
  }
}, 30 * 1000).unref();

function pipePair(post, get) {
  get.res.writeHead(200, {
    'Content-Type': 'application/octet-stream',
    'Cache-Control': 'no-store',
    'X-Accel-Buffering': 'no',
  });
  const kill = () => {
    try { post.req.destroy(); } catch (e) {}
    try { get.res.destroy(); } catch (e) {}
    try { post.res.destroy(); } catch (e) {}
  };
  post.req.pipe(get.res);
  post.req.on('end', () => {
    try { post.res.writeHead(200); post.res.end('ok'); } catch (e) {}
  });
  post.req.on('error', kill);
  post.req.on('aborted', kill);
  get.res.on('close', () => { if (!post.req.complete) kill(); });
}

const server = http.createServer((req, res) => {
  const url = req.url.split('?')[0];

  if (url === '/' || url === '/health') {
    res.writeHead(200, { 'Content-Type': 'text/plain' });
    res.end('Xcluice Blitz relay: ok\n');
    return;
  }

  let m = RE_HI.exec(url);
  if (m && req.method === 'POST') {
    const room = m[1];
    const role = m[2];
    const other = role === 's' ? 'r' : 's';
    const v = seen.get(room) || {};
    v[role] = Date.now();
    seen.set(room, v);
    const peer = !!v[other] && Date.now() - v[other] < 15000;
    res.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
    res.end(JSON.stringify({ peer }));
    req.resume();
    return;
  }

  m = RE_P.exec(url);
  if (m && (req.method === 'POST' || req.method === 'GET')) {
    const k = m[1] + '/' + m[2];
    const type = req.method;
    if (type === 'POST') {
      const len = parseInt(req.headers['content-length'] || '0', 10);
      if (len > MAX_BODY) { res.writeHead(413); res.end(); return; }
    }
    const entry = { type, req, res };
    const other = waiting.get(k);
    if (other && other.type !== type) {
      waiting.delete(k);
      clearTimeout(other.timer);
      const post = type === 'POST' ? entry : other;
      const get = type === 'GET' ? entry : other;
      pipePair(post, get);
      return;
    }
    if (other || waiting.size >= MAX_WAITING) {
      res.writeHead(409);
      res.end();
      return;
    }
    waiting.set(k, entry);
    entry.timer = setTimeout(() => {
      if (waiting.get(k) === entry) {
        waiting.delete(k);
        try { res.writeHead(408); res.end(); } catch (e) {}
      }
    }, WAIT_MS);
    res.on('close', () => {
      if (waiting.get(k) === entry) {
        waiting.delete(k);
        clearTimeout(entry.timer);
      }
    });
    return;
  }

  res.writeHead(404);
  res.end();
});

server.keepAliveTimeout = 120 * 1000;
server.headersTimeout = 125 * 1000;
server.requestTimeout = 0;
server.listen(PORT, () => console.log('relay listening on ' + PORT));
