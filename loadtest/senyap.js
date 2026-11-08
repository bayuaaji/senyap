// Load test Senyap (build guide Fase 5, requirements §9 no. 6).
//
//   PROFILE=small (default): laptop. PROFILE=full: target staging 50.000 WS + 3.000 pesan/s + feed 2.000 rps.
//   k6 run -e API=http://localhost:8380 -e WS=ws://localhost:8390/v1/ws loadtest/senyap.js
//
// Setiap pasangan (A, B) punya percakapan Bisik aktif. A mengirim msg.send lewat WebSocket, B mengukur waktu
// sampai msg.new tiba (stempel waktu ada di isi pesan uji). Koneksi "diam" hanya ping tiap 25 dtk seperti app.
// Login memakai OTP mock (kode 000000) → hanya untuk local/staging dengan OTP mock. Untuk skala penuh, seed
// pasangan dulu (MODE=seed, lihat README) supaya setup() tidak membuat puluhan ribu akun di tengah test.
import http from 'k6/http';
import { check, fail } from 'k6';
import { WebSocket } from 'k6/websockets';
import { Counter, Rate, Trend } from 'k6/metrics';
import { SharedArray } from 'k6/data';
import exec from 'k6/execution';

const API = __ENV.API || 'http://localhost:8380';
const WS = __ENV.WS || 'ws://localhost:8390/v1/ws';
const PROFILE = __ENV.PROFILE || 'small';
const MODE = __ENV.MODE || 'run';

const PROFILES = {
  // Laptop: semua proses + infra di satu mesin; angka kecil, tujuannya membuktikan skrip + jalur bekerja.
  small: { pairs: 100, idle: 300, msgPerSec: 30, feedRps: 100, duration: '1m' },
  // Staging (requirements §9 no. 6): 25.000 pasangan × 2 = 50.000 koneksi, 3.000 pesan/s, feed 2.000 rps.
  // Pesan per pengirim 0,12/s (≈ 7/menit) di bawah rate limit akun baru 10/menit.
  full: { pairs: 25000, idle: 0, msgPerSec: 3000, feedRps: 2000, duration: '15m' },
};
const P = Object.assign({}, PROFILES[PROFILE], {
  pairs: Number(__ENV.PAIRS || PROFILES[PROFILE].pairs),
  idle: Number(__ENV.IDLE || PROFILES[PROFILE].idle),
  msgPerSec: Number(__ENV.MSG_RATE || PROFILES[PROFILE].msgPerSec),
  feedRps: Number(__ENV.FEED_RPS || PROFILES[PROFILE].feedRps),
  duration: __ENV.DURATION || PROFILES[PROFILE].duration,
});

const msgLatency = new Trend('senyap_msg_e2e_ms', true); // msg.send (A) → msg.new (B)
const ackLatency = new Trend('senyap_msg_ack_ms', true); // msg.send → msg.ack
const wsConnectFail = new Rate('senyap_ws_connect_failed');
const msgLost = new Counter('senyap_msg_without_ack');
const sent = new Counter('senyap_msg_sent');

const seeded = __ENV.PAIRS_FILE ? new SharedArray('pairs', () => JSON.parse(open(__ENV.PAIRS_FILE))) : null;

export const options = MODE === 'seed'
  ? { scenarios: { seed: { executor: 'shared-iterations', vus: 50, iterations: P.pairs, maxDuration: '2h' } } }
  : {
    setupTimeout: '20m',
    scenarios: {
      chat: { executor: 'constant-vus', vus: P.pairs, duration: P.duration, exec: 'chat', gracefulStop: '30s' },
      ...(P.idle > 0 ? { idle: { executor: 'constant-vus', vus: P.idle, duration: P.duration, exec: 'idle', gracefulStop: '30s' } } : {}),
      feed: {
        executor: 'constant-arrival-rate', rate: P.feedRps, timeUnit: '1s', duration: P.duration, exec: 'feed',
        preAllocatedVUs: Math.max(20, Math.ceil(P.feedRps / 10)), maxVUs: Math.max(50, P.feedRps),
      },
    },
    thresholds: {
      senyap_msg_e2e_ms: ['p(95)<500'], // NFR-10
      senyap_ws_connect_failed: ['rate<0.001'], // §9 no. 6: error < 0,1%
      'http_req_failed{scenario:feed}': ['rate<0.001'],
      'http_req_duration{scenario:feed}': ['p(95)<300'], // NFR-11
    },
  };

// ---------------------------------------------------------------- akun + percakapan

const JSON_H = { 'Content-Type': 'application/json' };
const auth = (t) => ({ headers: Object.assign({ Authorization: `Bearer ${t}` }, JSON_H) });

function phone() {
  // 0812 + 8 digit acak: unik per run dengan probabilitas tinggi.
  return '0812' + String(Math.floor(Math.random() * 1e8)).padStart(8, '0');
}

function login() {
  const p = phone();
  http.post(`${API}/v1/auth/otp`, JSON.stringify({ phone: p }), { headers: JSON_H });
  const v = http.post(`${API}/v1/auth/verify`, JSON.stringify({
    phone: p, code: '000000', device: { platform: 'android' }, age_confirmed: true,
  }), { headers: JSON_H });
  if (v.status !== 200) fail(`verify ${v.status} ${v.body}`);
  const token = v.json('tokens.access_token');
  const s = http.post(`${API}/v1/me/alias/suggest`, '{}', auth(token));
  http.put(`${API}/v1/me/alias`, JSON.stringify({ suggestion_token: s.json('suggestion_token') }), auth(token));
  return token;
}

function makePair() {
  const a = login();
  const b = login();
  const bg = http.get(`${API}/v1/backgrounds?category=curhat`, auth(a)).json('items.0.id');
  const post = http.post(`${API}/v1/posts`, JSON.stringify({ body: 'load test', category: 'curhat', bg_media_id: bg }), auth(a));
  if (post.status !== 201) fail(`post ${post.status} ${post.body}`);
  const conv = http.post(`${API}/v1/conversations`, JSON.stringify({
    origin_type: 'POST', origin_id: post.json('id'), first_message: 'hai', client_msg_id: uuid(),
  }), auth(b));
  if (conv.status !== 201) fail(`bisik ${conv.status} ${conv.body}`);
  const id = conv.json('id');
  const acc = http.post(`${API}/v1/conversations/${id}/accept`, null, auth(a));
  if (acc.status !== 200) fail(`accept ${acc.status}`);
  return { a, b, conv: id };
}

export function setup() {
  if (MODE === 'seed' || seeded) return { pairs: [] };
  const pairs = [];
  for (let i = 0; i < P.pairs; i++) pairs.push(makePair());
  return { pairs };
}

// MODE=seed: tiap iterasi membuat satu pasangan dan mencetak baris `PAIR {...}` (lihat README untuk mengumpulkan
// jadi pairs.json). Token akses berumur pendek: seed tepat sebelum test.
export default function () {
  console.log(`PAIR ${JSON.stringify(makePair())}`);
}

function pairFor(data, i) {
  const list = seeded || data.pairs;
  return list[i % list.length];
}

// ---------------------------------------------------------------- skenario

function connect(token, onFrame) {
  const ws = new WebSocket(WS, null, { headers: { Authorization: `Bearer ${token}` } });
  let opened = false;
  ws.onopen = () => {
    opened = true;
    wsConnectFail.add(false);
    ws.ping = setInterval(() => ws.send(JSON.stringify({ t: 'ping', id: 'p', d: {} })), 25000);
  };
  ws.onerror = () => { if (!opened) wsConnectFail.add(true); };
  ws.onmessage = (e) => onFrame && onFrame(JSON.parse(e.data));
  ws.onclose = () => clearInterval(ws.ping);
  return ws;
}

export function chat(data) {
  const pair = pairFor(data, exec.scenario.iterationInTest);
  const pending = {};
  const interval = (1000 * P.pairs) / P.msgPerSec; // ms antar pesan per pengirim
  const receiver = connect(pair.b, (f) => {
    if (f.t === 'msg.new' && f.d.body && f.d.body.startsWith('lt:')) {
      msgLatency.add(Date.now() - Number(f.d.body.slice(3)));
    }
  });
  const sender = connect(pair.a, (f) => {
    if (f.t === 'msg.ack' && pending[f.id]) {
      ackLatency.add(Date.now() - pending[f.id]);
      delete pending[f.id];
    }
  });
  let n = 0;
  sender.addEventListener('open', () => {
    // Mulai acak supaya pengirim tidak serempak.
    setTimeout(() => {
      sender.loop = setInterval(() => {
        const id = `m${n++}`;
        pending[id] = Date.now();
        sent.add(1);
        sender.send(JSON.stringify({
          t: 'msg.send', id, d: { conversation_id: pair.conv, client_msg_id: uuid(), type: 'TEXT', body: `lt:${Date.now()}` },
        }));
      }, interval);
    }, Math.random() * interval);
  });
  // Tutup menjelang akhir durasi skenario.
  setTimeout(() => {
    clearInterval(sender.loop);
    setTimeout(() => {
      msgLost.add(Object.keys(pending).length);
      sender.close();
      receiver.close();
    }, 3000);
  }, durationMs(P.duration) - 5000);
}

export function idle(data) {
  const pair = pairFor(data, exec.vu.idInTest);
  const ws = connect(exec.vu.idInTest % 2 ? pair.a : pair.b);
  setTimeout(() => ws.close(), durationMs(P.duration) - 2000);
}

export function feed(data) {
  const pair = pairFor(data, exec.scenario.iterationInTest);
  const r = http.get(`${API}/v1/feed/latest`, Object.assign(auth(pair.a), { tags: { name: 'feed_latest' } }));
  check(r, { 'feed 200': (x) => x.status === 200 });
}

// ---------------------------------------------------------------- util

function durationMs(d) {
  const m = /^(\d+)(ms|s|m|h)$/.exec(d);
  return Number(m[1]) * { ms: 1, s: 1000, m: 60000, h: 3600000 }[m[2]];
}

function uuid() {
  const h = '0123456789abcdef';
  let s = '';
  for (let i = 0; i < 36; i++) {
    if ([8, 13, 18, 23].includes(i)) s += '-';
    else if (i === 14) s += '4';
    else if (i === 19) s += h[(Math.random() * 4) | 8];
    else s += h[(Math.random() * 16) | 0];
  }
  return s;
}

export function handleSummary(data) {
  const out = { stdout: textSummary(data) };
  if (__ENV.SUMMARY_OUT) out[__ENV.SUMMARY_OUT] = JSON.stringify(data, null, 1);
  return out;
}

function textSummary(data) {
  const m = data.metrics;
  const v = (k, f) => (m[k] ? m[k].values[f] : undefined);
  const fmt = (x) => (x === undefined ? '-' : Math.round(x * 10) / 10);
  return [
    `profil=${PROFILE} pasangan=${P.pairs} idle=${P.idle} target pesan/s=${P.msgPerSec} target feed rps=${P.feedRps} durasi=${P.duration}`,
    `koneksi WS gagal: ${fmt((v('senyap_ws_connect_failed', 'rate') || 0) * 100)}%`,
    `pesan terkirim: ${v('senyap_msg_sent', 'count')} (${fmt(v('senyap_msg_sent', 'rate'))}/s), tanpa ack: ${v('senyap_msg_without_ack', 'count') || 0}`,
    `msg.send→msg.new p50 ${fmt(v('senyap_msg_e2e_ms', 'med'))} ms, p95 ${fmt(v('senyap_msg_e2e_ms', 'p(95)'))} ms`,
    `msg.send→ack p50 ${fmt(v('senyap_msg_ack_ms', 'med'))} ms, p95 ${fmt(v('senyap_msg_ack_ms', 'p(95)'))} ms`,
    `feed: ${fmt(v('http_reqs', 'rate'))} req/s total, p95 ${fmt(v('http_req_duration', 'p(95)'))} ms, gagal ${fmt((v('http_req_failed', 'rate') || 0) * 100)}%`,
    '',
  ].join('\n');
}
