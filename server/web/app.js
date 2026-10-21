// LiteHelm web UI. Plain ES module, no build step, no third-party code.
// Talks to the same /api/v1 protocol as the Android app (API.md) on the same origin.
// All server data is inserted with textContent — never as HTML.
import { STRINGS } from './i18n.js';

// ---------------------------------------------------------------- storage / language
const store = {
  get(area, key) { try { return window[area].getItem(key); } catch { return null; } },
  set(area, key, value) { try { window[area].setItem(key, value); return true; } catch { return false; } },
  del(area, key) { try { window[area].removeItem(key); } catch { /* ignore */ } },
};
const LANGS = ['zh', 'zh-Hant', 'en'];
const LOCALE = { zh: 'zh-CN', 'zh-Hant': 'zh-TW', en: 'en' };
function detectLang() {
  for (const raw of navigator.languages || [navigator.language || 'en']) {
    const l = String(raw).toLowerCase();
    if (l.startsWith('zh')) return /hant|-tw|-hk|-mo/.test(l) ? 'zh-Hant' : 'zh';
    if (l.startsWith('en')) return 'en';
  }
  return 'en';
}
let langPref = store.get('localStorage', 'lh-lang') || 'system';
if (langPref !== 'system' && !LANGS.includes(langPref)) langPref = 'system';
let lang = langPref === 'system' ? detectLang() : langPref;
function t(key, ...args) {
  const s = STRINGS[lang]?.[key] ?? STRINGS.en[key] ?? key;
  return s.replace(/\{(\d)\}/g, (_, i) => String(args[i] ?? ''));
}
function setLang(pref) {
  langPref = pref;
  if (pref === 'system') store.del('localStorage', 'lh-lang'); else store.set('localStorage', 'lh-lang', pref);
  lang = pref === 'system' ? detectLang() : pref;
  document.documentElement.lang = LOCALE[lang];
  if (S.token) { shell = null; route(true); } else renderLogin();
}

// ---------------------------------------------------------------- DOM helpers
function h(tag, props, ...kids) {
  const el = document.createElement(tag);
  if (props) {
    for (const [k, v] of Object.entries(props)) {
      if (v === null || v === undefined || v === false) continue;
      if (k === 'class') el.className = v;
      else if (k === 'text') el.textContent = v;
      else if (k.startsWith('on') && typeof v === 'function') el.addEventListener(k.slice(2), v);
      else if (k === 'style') Object.assign(el.style, v);
      else if (k === 'value' || k === 'checked' || k === 'disabled' || k === 'selected') el[k] = v;
      else el.setAttribute(k, v === true ? '' : String(v));
    }
  }
  add(el, kids);
  return el;
}
// Like replaceChildren, but skips null/false and flattens arrays (native would print "null").
function fill(el, ...kids) { el.replaceChildren(); return add(el, kids); }
function add(el, kids) {
  for (const k of kids.flat(Infinity)) {
    if (k === null || k === undefined || k === false) continue;
    el.append(k instanceof Node ? k : document.createTextNode(String(k)));
  }
  return el;
}
const SVG = 'http://www.w3.org/2000/svg';
const ICONS = {
  overview: 'M3 12h4l3-8 4 16 3-8h4',
  processes: 'M7 7h10v10H7z|M10 3v4M14 3v4M10 17v4M14 17v4M3 10h4M3 14h4M17 10h4M17 14h4',
  services: 'M4 4.5h16v6H4z|M4 13.5h16v6H4z|M8 7.5h.01M8 16.5h.01',
  timers: 'M12 21a8 8 0 1 0 0-16 8 8 0 0 0 0 16z|M12 9v4l2.5 2.5|M9.5 2.5h5',
  ports: 'M9 3v5M15 3v5|M6 8h12v4a6 6 0 0 1-12 0z|M12 18v3',
  disks: 'M4 6c0-1.7 3.6-3 8-3s8 1.3 8 3-3.6 3-8 3-8-1.3-8-3z|M4 6v12c0 1.7 3.6 3 8 3s8-1.3 8-3V6|M4 12c0 1.7 3.6 3 8 3s8-1.3 8-3',
  jobs: 'M3.5 5h17v14h-17z|M7 10l3 2-3 2|M12 14.5h5',
  settings: 'M4 7h9M17 7h3M4 17h3M11 17h9|M15 5v4|M9 15v4',
  more: 'M4 6h16M4 12h16M4 18h16',
  refresh: 'M20 12a8 8 0 1 1-2.34-5.66|M20 4v5h-5',
  search: 'M11 18a7 7 0 1 0 0-14 7 7 0 0 0 0 14z|M20 20l-4-4',
  close: 'M6 6l12 12M18 6L6 18',
  star: 'M12 3.5l2.6 5.3 5.9.9-4.3 4.1 1 5.8-5.2-2.8-5.2 2.8 1-5.8-4.3-4.1 5.9-.9z',
  back: 'M15 5l-7 7 7 7',
  chevron: 'M9 5l7 7-7 7',
  eye: 'M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12z|M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z',
  eyeoff: 'M3 3l18 18|M10.6 6.1A10 10 0 0 1 12 6c6.5 0 10 6 10 6a17 17 0 0 1-3 3.6M6.6 6.6C3.7 8.4 2 12 2 12s3.5 7 10 7c1.9 0 3.5-.5 4.9-1.3|M9.9 9.9a3 3 0 0 0 4.2 4.2',
  down: 'M12 4v16M6 14l6 6 6-6',
  up: 'M12 20V4M6 10l6-6 6 6',
  clock: 'M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z|M12 7v5l3 2',
  power: 'M12 3v8|M6.3 6.3a8 8 0 1 0 11.4 0',
  lock: 'M6 11h12v9H6z|M8 11V8a4 4 0 0 1 8 0v3',
  copy: 'M8 8h11v11H8z|M5 16V5h11',
};
function icon(name) {
  const s = document.createElementNS(SVG, 'svg');
  s.setAttribute('viewBox', '0 0 24 24');
  s.setAttribute('aria-hidden', 'true');
  s.setAttribute('class', 'ic');
  for (const d of ICONS[name].split('|')) {
    const p = document.createElementNS(SVG, 'path');
    p.setAttribute('d', d);
    s.append(p);
  }
  return s;
}
function mark() { return h('div', { class: 'mark', 'aria-hidden': 'true' }, h('i'), h('i')); }
function iconBtn(name, label, onclick, cls = '') {
  return h('button', { class: 'icon-btn ' + cls, type: 'button', 'aria-label': label, title: label, onclick }, icon(name));
}
function refreshBtn(fn) {
  const b = iconBtn('refresh', t('refresh'), () => fn(true));
  b.busy = (v) => { b.disabled = v; b.classList.toggle('spin', v); };
  return b;
}
function head(title, sub, ...actions) {
  return h('div', { class: 'head' },
    h('div', {}, h('h1', { text: title }), sub instanceof Node ? sub : sub ? h('div', { class: 'sub', text: sub }) : null),
    actions.length ? h('div', { class: 'head-actions' }, actions) : null);
}
function searchBox(placeholder, onInput, value = '') {
  const input = h('input', { class: 'input', type: 'text', placeholder, 'aria-label': placeholder, value, autocomplete: 'off', spellcheck: 'false', 'data-search': '' });
  const clear = iconBtn('close', t('clear_search'), () => { input.value = ''; sync(); onInput(''); input.focus(); }, 'clear');
  const sync = () => clear.classList.toggle('hidden', !input.value);
  input.addEventListener('input', () => { sync(); onInput(input.value); });
  input.addEventListener('keydown', (e) => { if (e.key === 'Escape' && input.value) { e.preventDefault(); clear.click(); } });
  sync();
  return { el: h('div', { class: 'search' }, icon('search'), input, clear), input };
}
function segmented(options, value, onChange) {
  const btns = new Map();
  const el = h('div', { class: 'seg', role: 'group' });
  for (const [v, label] of options) {
    const count = h('span', { class: 'count num' });
    const b = h('button', { type: 'button', 'aria-pressed': String(v === value), onclick: () => { set(v); onChange(v); } }, label, count);
    b.count = count;
    btns.set(v, b);
    el.append(b);
  }
  function set(v) { value = v; for (const [k, b] of btns) b.setAttribute('aria-pressed', String(k === v)); }
  return { el, set, count(v, n) { const b = btns.get(v); if (b) b.count.textContent = n === null ? '' : String(n); } };
}
const fin = (n) => typeof n === 'number' && Number.isFinite(n);
const DASH = '—';
function bar(p, cls = '') {
  const i = h('i');
  i.style.width = (fin(p) ? Math.min(100, Math.max(0, p)) : 0) + '%';
  return h('div', { class: 'bar ' + cls, 'aria-hidden': 'true' }, i);
}
function bytes(n) {
  if (!fin(n) || n < 0) return DASH;
  const u = ['B', 'KiB', 'MiB', 'GiB', 'TiB', 'PiB'];
  let i = 0;
  while (n >= 1024 && i < u.length - 1) { n /= 1024; i++; }
  return (i === 0 ? String(Math.round(n)) : n.toFixed(n >= 100 ? 0 : 1)) + ' ' + u[i];
}
const ratio = (used, total) => (fin(used) && fin(total) && total > 0 ? (used / total) * 100 : NaN);
const pctText = (p) => (fin(p) ? p.toFixed(1) + ' %' : DASH);
function duration(s) {
  if (!fin(s) || s < 0) return DASH;
  const d = Math.floor(s / 86400), hr = Math.floor((s % 86400) / 3600), m = Math.floor((s % 3600) / 60);
  if (d > 0) return t('duration_dh', d, hr);
  if (hr > 0) return t('duration_hm', hr, m);
  if (m > 0) return t('duration_m', m);
  return t('duration_lt1');
}
const clock = (ms) => new Date(ms).toLocaleTimeString(LOCALE[lang], { hour12: false });
function stamp(sec) {
  if (!fin(sec) || sec <= 0) return DASH;
  return new Date(sec * 1000).toLocaleString(LOCALE[lang], { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false });
}
function timeoutLabel(s) {
  if (!fin(s)) return DASH;
  if (s % 3600 === 0) return t('n_hours', s / 3600);
  if (s % 60 === 0) return t('n_minutes', s / 60);
  return t('n_seconds', s);
}
const enc = encodeURIComponent;
const lower = (v) => String(v ?? '').toLowerCase();
function emptyBlock(text, action) { return h('div', { class: 'empty' }, h('div', { text }), action || null); }
function errorBlock(msg, retry) {
  return h('div', { class: 'empty' }, h('div', { class: 'danger', role: 'alert', text: msg }),
    retry ? h('button', { class: 'btn', type: 'button', text: t('retry'), onclick: () => retry() }) : null);
}
function staleBanner(msg) { return h('div', { class: 'banner warn', role: 'alert' }, t('error_stale', msg)); }
function tag(cls, text) { return h('span', { class: 'state-tag ' + cls }, h('span', { class: 'dot ' + cls }), text); }
async function copyText(text) {
  try { await navigator.clipboard.writeText(text); toast(t('copied')); } catch { toast(t('copy_failed'), true); }
}

// ---------------------------------------------------------------- toast / dialogs
const toastWrap = h('div', { class: 'toast-wrap', role: 'status', 'aria-live': 'polite' });
function toast(msg, err = false) {
  const el = h('div', { class: 'toast' + (err ? ' err' : ''), text: msg });
  toastWrap.append(el);
  setTimeout(() => el.remove(), err ? 6500 : 3500);
}
// body/foot receive ctx { close, error } and return nodes.
function dialog({ title, wide = false, body, foot }) {
  const d = h('dialog', { class: 'sheet' + (wide ? ' wide' : ''), 'aria-label': title });
  const err = h('p', { class: 'dlg-err', role: 'alert' });
  const ctx = { close: () => { if (d.open) d.close(); }, error: (m) => { err.textContent = m || ''; } };
  d.addEventListener('close', () => d.remove());
  d.addEventListener('mousedown', (e) => { if (e.target === d) ctx.close(); });
  d.append(h('div', { class: 'dlg' },
    h('div', { class: 'dlg-head' }, h('h2', { text: title }), iconBtn('close', t('close'), ctx.close)),
    h('div', { class: 'dlg-body' }, body(ctx), err),
    foot ? h('div', { class: 'dlg-foot' }, foot(ctx)) : null));
  document.body.append(d);
  d.showModal();
  return ctx;
}
function cancelBtn(ctx) { return h('button', { class: 'btn', type: 'button', text: t('cancel'), onclick: ctx.close }); }
function confirmDialog({ title, body, confirm, danger = false, wide = false, run }) {
  let btn;
  dialog({
    title, wide, body: () => body,
    foot: (ctx) => [cancelBtn(ctx), btn = h('button', {
      class: 'btn ' + (danger ? 'danger' : 'primary'), type: 'button', text: confirm,
      onclick: async () => {
        btn.disabled = true; btn.textContent = t('submitting'); ctx.error('');
        try { await run(); ctx.close(); } catch (e) { ctx.error(e.message); btn.disabled = false; btn.textContent = confirm; }
      },
    })],
  });
}

// ---------------------------------------------------------------- API client
const S = { token: '', remembered: false, sys: null, sysAt: 0, status: 'idle', writing: false };
class ApiError extends Error { constructor(message, status = 0) { super(message); this.status = status; } }
function timeoutSignal(ms) {
  if (AbortSignal.timeout) return AbortSignal.timeout(ms);
  const c = new AbortController();
  setTimeout(() => c.abort(new DOMException('timeout', 'TimeoutError')), ms);
  return c.signal;
}
// The server allows two concurrent requests and one write; serialize like the app does.
let chain = Promise.resolve();
function api(method, path, body, token) {
  const run = () => request(method, path, body, token ?? S.token);
  const p = chain.then(run, run);
  chain = p.then(() => {}, () => {});
  return p;
}
async function request(method, path, body, token) {
  const write = method === 'POST';
  const own = token === S.token;
  const headers = { Authorization: 'Bearer ' + token, Accept: 'application/json' };
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (own && S.status !== 'ok') setStatus('busy');
  let res, text;
  try {
    res = await fetch('api/v1/' + path, {
      method, headers, body: body === undefined ? undefined : JSON.stringify(body),
      cache: 'no-store', credentials: 'omit', redirect: 'error', referrerPolicy: 'no-referrer',
      signal: timeoutSignal(write ? 30000 : 20000),
    });
    text = await res.text();
  } catch (e) {
    const timeout = e && (e.name === 'TimeoutError' || e.name === 'AbortError');
    if (own) setStatus('fail');
    throw new ApiError(t(write ? (timeout ? 'err_write_timeout' : 'err_write_io') : (timeout ? 'err_timeout' : 'err_io')));
  }
  const type = res.headers.get('content-type') || '';
  const bad = (msg) => { if (own) setStatus('fail'); return new ApiError(msg, res.status); };
  if (!type.includes('application/json')) throw bad(type.includes('text/html') ? t('err_html') : t('err_http', res.status));
  if (text.length > 8 << 20) throw bad(t('err_too_large'));
  let data;
  try { data = JSON.parse(text); } catch { throw bad(t('err_json')); }
  if (res.status === 401) {
    if (own) { setStatus('fail'); if (S.token) setTimeout(() => signOut(t('err_auth_lost')), 0); }
    throw new ApiError(t('err_auth'), 401);
  }
  if (own) setStatus('ok');
  if (!res.ok) throw new ApiError(data && typeof data.error === 'string' && data.error ? data.error : t('err_http', res.status), res.status);
  if (!data || typeof data !== 'object' || Array.isArray(data)) throw new ApiError(t('err_data_format'));
  return data;
}
// Writes are single-flight and never retried automatically.
async function write(path, body, needOk = true) {
  if (S.writing) throw new ApiError(t('busy_existing'));
  S.writing = true;
  try {
    const d = await api('POST', path, body);
    if (needOk && d.ok !== true) throw new ApiError(t('err_not_confirmed'));
    return d;
  } finally { S.writing = false; }
}
function validSystem(d) { return d && typeof d.hostname === 'string' && d.hostname !== ''; }
function setSys(d) {
  S.sys = d; S.sysAt = Date.now();
  for (const el of document.querySelectorAll('[data-hostname]')) el.textContent = d.hostname;
  updateTitle();
}
const caps = () => (S.sys && S.sys.capabilities) || {};
function setStatus(s) {
  S.status = s;
  const key = { ok: 'status_connected', fail: 'status_failed', busy: 'status_connecting', idle: 'status_disconnected' }[s];
  for (const el of document.querySelectorAll('[data-status]')) {
    fill(el, h('span', { class: 'dot ' + s }), t(key));
  }
}
function statusEl() { const el = h('span', { class: 'status', 'data-status': '' }); queueMicrotask(() => setStatus(S.status)); return el; }

// ---------------------------------------------------------------- favorites (this origin = this host)
const favs = {
  list() {
    try {
      const a = JSON.parse(store.get('localStorage', 'lh-favs') || '[]');
      return Array.isArray(a) ? a.filter((x) => typeof x === 'string').slice(0, 12) : [];
    } catch { return []; }
  },
  has(name) { return this.list().includes(name); },
  toggle(name) {
    const a = this.list();
    const i = a.indexOf(name);
    if (i >= 0) a.splice(i, 1);
    else if (a.length >= 12) { toast(t('favorite_limit'), true); return false; } else a.push(name);
    if (!store.set('localStorage', 'lh-favs', JSON.stringify(a))) { toast(t('err_favorite'), true); return false; }
    return true;
  },
};

// ---------------------------------------------------------------- shell / routing
const root = document.getElementById('root');
const NAV = [
  ['overview', 'nav_overview'], ['processes', 'nav_processes'], ['services', 'nav_services'],
  ['timers', 'nav_timers'], ['ports', 'nav_ports'], ['disks', 'nav_disks'],
  ['jobs', 'nav_scripts'], ['settings', 'nav_settings'],
];
const ICON_OF = { jobs: 'jobs', overview: 'overview', processes: 'processes', services: 'services', timers: 'timers', ports: 'ports', disks: 'disks', settings: 'settings', more: 'more' };
const TABS = [['overview', 'nav_overview'], ['processes', 'nav_processes'], ['services', 'nav_services'], ['jobs', 'nav_scripts'], ['more', 'nav_more']];
const MORE_PAGES = ['timers', 'ports', 'disks', 'settings'];
let shell = null; // { page, links }
let current = null; // { page, ctl }
let seq = 0;

function renderShell() {
  const sideLinks = NAV.map(([p, k], i) => [
    i === 3 ? h('div', { class: 'side-group', text: t('host_tools') }) : null,
    i === 7 ? h('div', { class: 'side-group', text: t('nav_settings') }) : null,
    h('a', { href: '#/' + p, 'data-page': p }, icon(ICON_OF[p]), t(k)),
  ]);
  const tabLinks = TABS.map(([p, k]) => h('a', { href: '#/' + p, 'data-page': p }, h('span', { class: 'pill' }, icon(ICON_OF[p])), t(k)));
  const page = h('div', { class: 'page', id: 'page' });
  const host = S.sys ? S.sys.hostname : DASH;
  fill(root, h('div', { class: 'shell' },
    h('aside', { class: 'side' },
      h('div', { class: 'brand' }, mark(), h('div', {}, h('b', { text: 'LiteHelm' }), h('span', { text: t('tagline') }))),
      h('nav', { class: 'side-nav', 'aria-label': 'LiteHelm' }, sideLinks),
      h('div', { class: 'side-foot' }, h('div', { class: 'host', 'data-hostname': '', text: host }), statusEl())),
    h('div', { class: 'main' },
      h('header', { class: 'top' }, h('div', { class: 'brand' }, mark(), h('div', {}, h('b', { text: 'LiteHelm' }), h('span', { 'data-hostname': '', text: host }))), statusEl()),
      h('main', {}, page)),
    h('nav', { class: 'tabs', 'aria-label': 'LiteHelm' }, tabLinks)), toastWrap);
  shell = { page, links: [...root.querySelectorAll('a[data-page]')] };
}
function parseHash() {
  const raw = location.hash.replace(/^#\/?/, '');
  const i = raw.indexOf('/');
  const page = i < 0 ? raw : raw.slice(0, i);
  let arg = '';
  if (i >= 0) { try { arg = decodeURIComponent(raw.slice(i + 1)); } catch { arg = ''; } }
  return { page: PAGES[page] ? page : 'overview', arg };
}
function updateTitle() {
  const p = current ? current.page : 'overview';
  const key = (NAV.find(([n]) => n === p) || TABS.find(([n]) => n === p) || [p, 'nav_overview'])[1];
  document.title = `${t(key)} · ${S.sys ? S.sys.hostname : 'LiteHelm'}`;
}
function route(force = false) {
  if (!S.token) { renderLogin(); return; }
  if (!shell) { renderShell(); force = true; }
  const r = parseHash();
  if (!force && current && current.page === r.page && current.ctl.update) {
    current.ctl.update(r.arg);
  } else {
    if (current && current.ctl.unmount) current.ctl.unmount();
    for (const d of document.querySelectorAll('dialog')) d.close();
    const my = ++seq;
    fill(shell.page);
    const ctx = { arg: r.arg, alive: () => seq === my };
    current = { page: r.page, ctl: PAGES[r.page](shell.page, ctx) || {} };
    window.scrollTo(0, 0);
  }
  const tabPage = TABS.some(([p]) => p === r.page) ? r.page : MORE_PAGES.includes(r.page) ? 'more' : r.page;
  for (const a of shell.links) {
    const p = a.dataset.page;
    const on = a.closest('.tabs') ? p === tabPage : p === r.page;
    if (on) a.setAttribute('aria-current', 'page'); else a.removeAttribute('aria-current');
  }
  updateTitle();
}
window.addEventListener('hashchange', () => route());
document.addEventListener('visibilitychange', () => { if (current && current.ctl.visibility) current.ctl.visibility(document.hidden); });
document.addEventListener('keydown', (e) => {
  if (e.key !== '/' || e.ctrlKey || e.metaKey || e.altKey || document.querySelector('dialog[open]')) return;
  const a = document.activeElement;
  if (a && (a.tagName === 'INPUT' || a.tagName === 'TEXTAREA' || a.tagName === 'SELECT' || a.isContentEditable)) return;
  const s = document.querySelector('#page [data-search]');
  if (s) { e.preventDefault(); s.focus(); }
});
function signOut(message) {
  store.del('localStorage', 'lh-token');
  store.del('sessionStorage', 'lh-token');
  if (current && current.ctl.unmount) current.ctl.unmount();
  for (const d of document.querySelectorAll('dialog')) d.close();
  seq++;
  current = null; shell = null;
  S.token = ''; S.sys = null; S.remembered = false;
  setStatus('idle');
  renderLogin(message);
}
function langSelect(cls = 'input') {
  return h('select', { class: cls, 'aria-label': t('language'), onchange: (e) => setLang(e.target.value) },
    [['system', t('language_system')], ['zh', '简体中文'], ['zh-Hant', '繁體中文'], ['en', 'English']]
      .map(([v, l]) => h('option', { value: v, selected: v === langPref, text: l })));
}

// ---------------------------------------------------------------- login
function renderLogin(message = '') {
  document.title = 'LiteHelm';
  let shown = false;
  const input = h('input', { class: 'input mono', type: 'password', id: 'token', name: 'token', autocomplete: 'current-password', spellcheck: 'false', autocapitalize: 'off', placeholder: t('hint_token'), required: true });
  const eye = iconBtn('eye', t('show_field', t('access_token')), () => {
    shown = !shown;
    input.type = shown ? 'text' : 'password';
    fill(eye, icon(shown ? 'eyeoff' : 'eye'));
    eye.setAttribute('aria-label', t(shown ? 'hide_field' : 'show_field', t('access_token')));
  });
  const remember = h('input', { type: 'checkbox' });
  const err = h('p', { class: 'dlg-err', role: 'alert', text: message });
  const btn = h('button', { class: 'btn primary block', type: 'submit', text: t('save_connect') });
  input.addEventListener('input', () => { err.textContent = ''; });
  const form = h('form', { class: 'panel', novalidate: true, onsubmit: async (e) => {
    e.preventDefault();
    const draft = input.value.trim();
    if (!draft) { err.textContent = t('err_token_required'); input.focus(); return; }
    if (/[\x00-\x1f\x7f]/.test(draft)) { err.textContent = t('err_control_chars'); return; }
    btn.disabled = true; btn.textContent = t('validating'); err.textContent = '';
    try {
      const d = await api('GET', 'system', undefined, draft);
      if (!validSystem(d)) throw new ApiError(t('err_host_info'));
      store.del('localStorage', 'lh-token'); store.del('sessionStorage', 'lh-token');
      S.remembered = remember.checked && store.set('localStorage', 'lh-token', draft);
      if (!S.remembered) store.set('sessionStorage', 'lh-token', draft);
      S.token = draft;
      setSys(d);
      setStatus('ok');
      shell = null;
      route(true);
      toast(t('connected_toast'));
    } catch (ex) {
      err.textContent = ex.message;
      btn.disabled = false; btn.textContent = t('save_connect');
    }
  } },
  h('label', { class: 'field', for: 'token' }, h('span', { text: t('access_token') }), h('div', { class: 'input-wrap' }, input, eye)),
  h('label', { class: 'check' }, remember, h('div', {}, t('remember_token'), h('div', { class: 'small muted', text: t('remember_hint') }))),
  err,
  h('div', { style: { marginTop: '16px' } }, btn));
  fill(root, h('div', { class: 'login' }, h('div', { class: 'login-card' },
    mark(),
    h('h1', { text: t('connect_server') }),
    h('p', { class: 'sub', text: t('login_intro', location.host) }),
    form,
    h('p', { class: 'note' }, t('token_where'), ' ', h('span', { class: 'code', text: 'sudo cat /etc/litehelm/token' })),
    h('div', { class: 'login-foot' }, h('span', { text: t('brand_tagline') }), langSelect('')))), toastWrap);
  input.focus();
}

// ---------------------------------------------------------------- overview
function overview(el, ctx) {
  let data = S.sys, at = S.sysAt, err = '', loading = false, timer = 0, prev = null, rates = null;
  const rb = refreshBtn(() => load());
  const hostEl = h('h1', { 'data-hostname': '', text: data ? data.hostname : DASH });
  const osEl = h('div', { class: 'sub' });
  const body = h('div', { 'aria-live': 'off' });
  add(el, [h('div', { class: 'head' },
    h('div', {}, h('div', { class: 'eyebrow', text: t('current_host') }), hostEl, osEl),
    h('div', { class: 'head-actions' }, rb)), body]);

  function render() {
    if (!data) {
      fill(body, err ? errorBlock(t('cannot_connect', err), load) : emptyBlock(t('loading_host')));
      return;
    }
    const d = data;
    osEl.textContent = [d.os, d.arch].filter(Boolean).join(' · ');
    const cpu = fin(d.cpu_percent) ? d.cpu_percent : NaN;
    const mem = d.memory || {}, disk = d.disk || {}, swap = d.swap || {}, net = d.network || {};
    const memP = ratio(mem.used, mem.total), diskP = ratio(disk.used, disk.total);
    const load = Array.isArray(d.load) && d.load.length === 3 && d.load.every(fin) ? d.load.map((x) => x.toFixed(2)).join(' / ') : DASH;
    const stale = !!err;
    const unit = (txt) => {
      const m = /^(.*?) (\S+)$/.exec(txt);
      return m ? [m[1], h('small', { text: m[2] })] : [txt];
    };
    const resources = h('div', { class: 'panel' },
      h('div', { class: 'panel-title' }, t('resources'), h('span', { class: 'small muted', text: fin(d.cpu_cores) ? t('n_cores', d.cpu_cores) : '' })),
      h('div', { class: 'cpu' },
        h('div', {}, h('div', { class: 'stat-label' }, 'CPU'), h('div', { class: 'big num ok' }, unit(pctText(cpu)))),
        h('div', { class: 'small sub', style: { textAlign: 'right' } }, t('load'), h('div', { class: 'num', text: load }), h('div', { class: 'muted', text: t('load_windows') }))),
      bar(cpu, 'thick'),
      h('hr', { class: 'divider' }),
      h('div', { class: 'cols sep' },
        h('div', {}, h('div', { class: 'stat-label' }, t('memory')), h('div', { class: 'mid num cy' }, unit(pctText(memP))),
          h('div', { class: 'small sub num', text: `${bytes(mem.used)} / ${bytes(mem.total)}` }), bar(memP, 'cy')),
        h('div', {}, h('div', { class: 'stat-label' }, t('disk'), ' /'), h('div', { class: 'mid num ok' }, unit(pctText(diskP))),
          h('div', { class: 'small sub num', text: `${bytes(disk.used)} / ${bytes(disk.total)}` }), bar(diskP))),
      h('hr', { class: 'divider' }),
      h('div', { class: 'kv-line' }, h('span', { text: 'Swap' }), h('b', { class: 'num', text: swap.total === 0 ? t('swap_off') : `${bytes(swap.used)} / ${bytes(swap.total)}` })));
    const rateCol = (ic, label, v, cls) => h('div', {},
      h('div', { class: 'stat-label' }, icon(ic), label),
      h('div', { class: 'mid num ' + cls }, rates ? unit(bytes(v) + '/s') : [DASH]));
    const network = h('div', { class: 'panel' },
      h('div', { class: 'panel-title' }, t('network_rate'), h('span', { class: 'small muted', text: t('current_sample') })),
      h('div', { class: 'cols sep' }, rateCol('down', t('receive'), rates && rates.rx, 'cy'), rateCol('up', t('send'), rates && rates.tx, 'ok')),
      h('p', { class: 'note', text: rates ? t('totals', bytes(net.rx_bytes), bytes(net.tx_bytes)) : t('waiting_sample') }),
      h('hr', { class: 'divider' }),
      h('div', { class: 'panel-title' }, t('quick_tools')),
      h('div', { class: 'quick' }, ['timers', 'ports', 'disks', 'jobs'].map((p) =>
        h('a', { href: '#/' + p }, icon(ICON_OF[p]), t((NAV.find(([n]) => n === p) || [])[1])))));
    const list = favs.list();
    const favorites = h('div', { class: 'panel' },
      h('div', { class: 'panel-title' }, t('favorites_title'), h('a', { class: 'small link', href: '#/services', text: t('manage') })),
      list.length
        ? [h('div', { class: 'fav-list' }, list.map((n) => h('a', { class: 'chip', href: '#/services/' + enc(n), title: n }, icon('star'), n))),
          h('p', { class: 'note', text: t('favorites_note') })]
        : h('p', { class: 'note', text: t('favorites_empty') }));
    fill(body,
      stale ? staleBanner(err) : null,
      h('div', { class: 'uptime' }, h('span', { class: 'stat-label' }, icon('clock'), t('uptime')), h('b', { class: 'num', text: duration(d.uptime_seconds) })),
      h('div', { class: 'grid2' + (stale ? ' stale' : '') }, resources, network),
      favorites,
      h('div', { class: 'statusline' },
        h('span', { class: 'status' }, h('span', { class: 'dot ' + (stale ? 'warn' : 'ok') }), t('refresh_note')),
        h('span', { class: stale ? 'warn' : '', text: stale ? t('stale_last_success', clock(at)) : t('updated_at', clock(at)) })));
  }
  function sample(d) {
    const n = d.network || {}, ts = d.timestamp;
    const ok = fin(ts) && fin(n.rx_bytes) && fin(n.tx_bytes);
    if (ok && prev && ts > prev.ts && n.rx_bytes >= prev.rx && n.tx_bytes >= prev.tx) {
      rates = { rx: (n.rx_bytes - prev.rx) / (ts - prev.ts), tx: (n.tx_bytes - prev.tx) / (ts - prev.ts) };
    } else if (!(ok && prev && ts === prev.ts)) rates = null;
    prev = ok ? { ts, rx: n.rx_bytes, tx: n.tx_bytes } : null;
  }
  async function load() {
    if (loading) return;
    loading = true; rb.busy(true); clearTimeout(timer);
    try {
      const d = await api('GET', 'system');
      if (!ctx.alive()) return;
      if (!validSystem(d)) throw new ApiError(t('err_host_info'));
      sample(d); setSys(d); data = d; at = S.sysAt; err = '';
    } catch (e) {
      if (!ctx.alive()) return;
      err = e.message; prev = null; rates = null;
    }
    loading = false; rb.busy(false);
    render();
    clearTimeout(timer);
    if (!document.hidden) timer = setTimeout(load, 5000);
  }
  render();
  load();
  return {
    unmount() { clearTimeout(timer); },
    visibility(hidden) { clearTimeout(timer); prev = null; rates = null; if (!hidden) load(); },
  };
}

// ---------------------------------------------------------------- processes
function processes(el, ctx) {
  let sort = 'memory', limit = 100, q = '', data = null, err = '', loading = false;
  const rb = refreshBtn(() => load());
  const sub = h('div', { class: 'sub' });
  const sortSeg = segmented([['memory', t('memory')], ['cpu', t('cpu_average')]], sort, (v) => { sort = v; data = null; load(); });
  const limitSeg = segmented([[100, t('top_n', 100)], [300, t('top_n', 300)]], limit, (v) => { limit = v; load(); });
  const search = searchBox(t('search_processes'), (v) => { q = v; renderList(); });
  const listEl = h('div');
  add(el, [head(t('nav_processes'), sub, rb),
    h('div', { class: 'toolbar' }, sortSeg.el, limitSeg.el, search.el),
    h('p', { class: 'note', style: { margin: '0 0 12px' }, text: t('cpu_note') }), listEl]);

  function metric(p) {
    const cpu = fin(p.cpu_percent) ? p.cpu_percent.toFixed(1) + ' %' : DASH;
    return sort === 'cpu' ? [cpu, bytes(p.memory_bytes)] : [bytes(p.memory_bytes), cpu];
  }
  function renderList() {
    sub.textContent = t(sort === 'cpu' ? 'top_cpu' : 'top_memory', limit);
    if (!data) { fill(listEl, err ? errorBlock(err, load) : emptyBlock(t('loading_processes'))); return; }
    const needle = lower(q.trim());
    const rows = needle ? data.filter((p) => lower(p.name).includes(needle) || String(p.pid).includes(needle) || lower(p.user).includes(needle)) : data;
    const sortHead = sort === 'cpu' ? [t('cpu_average'), t('memory')] : [t('memory'), t('cpu_average')];
    fill(listEl,
      err ? staleBanner(err) : null,
      !data.length ? emptyBlock(t('no_processes'))
        : !rows.length ? emptyBlock(t('no_matching_processes'), h('button', { class: 'btn', type: 'button', text: t('clear_search'), onclick: () => { search.input.value = ''; search.input.dispatchEvent(new Event('input')); } }))
          : h('div', { class: 'list' },
            h('div', { class: 'row th proc' }, h('div', { text: t('process') }), h('div', { text: 'PID' }), h('div', { text: t('user') }), h('div', { text: t('state') }),
              h('div', { class: 'cell r', text: sortHead[0] }), h('div', { class: 'cell r', text: sortHead[1] })),
            rows.map((p) => {
              const [m1, m2] = metric(p);
              return h('button', { class: 'row proc', type: 'button', onclick: () => signalDialog(p, load) },
                h('div', { class: 'cell' }, h('span', { class: 'name', text: p.name }), h('span', { class: 'l2 only-m mono', text: `PID ${p.pid} · ${p.user} · ${p.state}` })),
                h('div', { class: 'cell mono only-d', text: p.pid }),
                h('div', { class: 'cell only-d', text: p.user }),
                h('div', { class: 'cell mono only-d', text: p.state }),
                h('div', { class: 'cell r num only-d', text: m1 }),
                h('div', { class: 'cell r num only-d muted', text: m2 }),
                h('div', { class: 'cell r num only-m' }, m1, h('span', { class: 'l2', text: m2 })));
            })));
  }
  async function load() {
    if (loading) return;
    loading = true; rb.busy(true); renderList();
    try {
      const d = await api('GET', `processes?limit=${limit}${sort === 'cpu' ? '&sort=cpu' : ''}`);
      if (!ctx.alive()) return;
      if (!Array.isArray(d.processes)) throw new ApiError(t('err_data_format'));
      data = d.processes; err = '';
    } catch (e) { if (!ctx.alive()) return; err = e.message; }
    loading = false; rb.busy(false); renderList();
  }
  load();
  return {};
}
function signalDialog(p, after) {
  let sig = 'TERM', btn;
  const reason = !caps().process_signal ? t('no_process_control') : p.pid === 1 ? t('process_protected') : !p.start_time ? t('missing_start') : '';
  const choice = (val, title, desc) => h('label', { class: 'choice' },
    h('input', { type: 'radio', name: 'sig', value: val, checked: val === sig, onchange: () => { sig = val; sync(); } }),
    h('div', {}, h('b', { text: title }), h('span', { text: desc })));
  const cTerm = choice('TERM', t('term_title'), t('term_desc'));
  const cKill = choice('KILL', t('kill_title'), t('kill_desc'));
  function sync() {
    cTerm.className = 'choice' + (sig === 'TERM' ? ' sel' : '');
    cKill.className = 'choice danger' + (sig === 'KILL' ? ' sel' : '');
    btn.textContent = t(sig === 'TERM' ? 'send_term' : 'send_kill');
    btn.className = 'btn ' + (sig === 'KILL' ? 'danger' : 'primary');
  }
  dialog({
    title: p.name,
    body: () => [
      h('dl', { class: 'kv' }, h('dt', { text: 'PID' }), h('dd', { class: 'mono', text: p.pid }), h('dt', { text: t('user') }), h('dd', { text: p.user }),
        h('dt', { text: t('state') }), h('dd', { class: 'mono', text: p.state }), h('dt', { text: t('start_id') }), h('dd', { class: 'mono', text: p.start_time || DASH }),
        h('dt', { text: t('host') }), h('dd', { text: S.sys ? S.sys.hostname : DASH })),
      cTerm, cKill,
      reason ? h('div', { class: 'banner warn' }, icon('lock'), reason) : null],
    foot: (c) => [cancelBtn(c), btn = h('button', { type: 'button', disabled: !!reason, onclick: async () => {
      const chosen = sig;
      btn.disabled = true; btn.textContent = t('submitting'); c.error('');
      try {
        await write(`processes/${p.pid}/signal`, { signal: chosen, start_time: p.start_time });
        c.close(); toast(t('signal_sent', chosen)); after();
      } catch (e) { c.error(e.message); btn.disabled = false; sync(); }
    } })],
  });
  sync();
}

// ---------------------------------------------------------------- services
function svcState(s) {
  if (s.active_state === 'active') return s.sub_state === 'running' ? ['ok', t('status_running')] : ['ok', t('status_active', s.sub_state)];
  if (s.active_state === 'failed') return ['danger', t('status_failed_sub', s.sub_state)];
  if (s.active_state === 'inactive') return ['', t('status_inactive', s.sub_state)];
  return ['warn', `${s.active_state} · ${s.sub_state}`];
}
function services(el, ctx) {
  let data = null, supported = true, err = '', loading = false, q = '', filter = 'all', selected = ctx.arg;
  const logs = { name: '', text: '', truncated: false, loading: false, err: '', q: '' };
  const rb = refreshBtn(() => load());
  const sub = h('div', { class: 'sub', text: t('systemd_services') });
  const search = searchBox(t('search_services'), (v) => { q = v; renderList(); });
  const filters = segmented([['all', t('filter_all')], ['running', t('filter_running')], ['failed', t('filter_failed')], ['inactive', t('filter_inactive')]], filter, (v) => { filter = v; renderList(); });
  const listEl = h('div');
  const detail = h('div', { class: 'panel detail', 'aria-live': 'polite' });
  add(el, [head(t('nav_services'), sub, rb), h('div', { class: 'toolbar' }, search.el, filters.el), h('div', { class: 'split master' }, listEl, detail)]);

  const matchFilter = (s, f) => f === 'all' || (f === 'running' && s.active_state === 'active') || (f === 'failed' && s.active_state === 'failed') || (f === 'inactive' && s.active_state === 'inactive');
  function renderList() {
    if (!supported) { fill(listEl, emptyBlock(t('no_systemd'))); sub.textContent = t('systemd_services'); return; }
    if (!data) { fill(listEl, err ? errorBlock(err, load) : emptyBlock(t('loading_services'))); return; }
    sub.textContent = t('services_count', data.length);
    for (const f of ['all', 'running', 'failed', 'inactive']) filters.count(f, data.filter((s) => matchFilter(s, f)).length);
    const needle = lower(q.trim());
    const rows = data.filter((s) => matchFilter(s, filter) && (!needle || lower(s.name).includes(needle) || lower(s.description).includes(needle)));
    const fav = new Set(favs.list());
    fill(listEl,
      err ? staleBanner(err) : null,
      !data.length ? emptyBlock(t('no_services'))
        : !rows.length ? emptyBlock(t('no_matching_services'))
          : h('div', { class: 'list' }, rows.map((s) => {
            const [cls, text] = svcState(s);
            return h('button', { class: 'row svc', type: 'button', 'aria-selected': String(s.name === selected), onclick: () => { location.hash = '#/services/' + enc(s.name); } },
              h('span', { class: 'dot ' + cls }),
              h('div', { class: 'cell' }, h('span', { class: 'name mono' }, fav.has(s.name) ? '★ ' : '', s.name), h('span', { class: 'l2', text: s.description || DASH })),
              h('span', { class: 'state-tag ' + cls, text }));
          })));
  }
  function renderDetail() {
    detail.classList.toggle('open', !!selected);
    if (!selected) { fill(detail, h('div', { class: 'empty' }, icon('services'), h('div', { text: t('select_service') }))); return; }
    const back = iconBtn('back', t('back'), () => { location.hash = '#/services'; }, 'detail-back');
    const s = data && data.find((x) => x.name === selected);
    if (!s) {
      fill(detail, h('div', { class: 'head' }, h('div', { class: 'btn-row' }, back), null),
        data ? emptyBlock(t('service_not_found')) : emptyBlock(err || t('loading_service_state')));
      return;
    }
    const [cls, text] = svcState(s);
    const c = caps();
    const active = s.active_state === 'active', idle = s.active_state === 'inactive' || s.active_state === 'failed';
    const can = c.service_control !== false;
    const note = !can ? t('control_unavailable') : !active && !idle ? t('state_changing') : '';
    const star = iconBtn('star', favs.has(s.name) ? t('unfavorite') : t('favorite_add'), () => { if (favs.toggle(s.name)) { renderDetail(); renderList(); } }, 'star');
    star.setAttribute('aria-pressed', String(favs.has(s.name)));
    const actBtn = (a, cls2, enabled) => h('button', { class: 'btn ' + cls2, type: 'button', disabled: !enabled, text: t('action_' + a), onclick: () => serviceAction(s, a) });
    fill(detail,
      h('div', { class: 'head', style: { marginBottom: '8px' } },
        h('div', { style: { display: 'flex', gap: '8px', alignItems: 'flex-start', minWidth: 0 } }, back,
          h('div', { style: { minWidth: 0 } }, h('h2', { class: 'mono', style: { margin: 0, fontSize: '16px', wordBreak: 'break-all' }, text: s.name }),
            h('div', { class: 'sub small', text: s.description || DASH }))),
        h('div', { class: 'head-actions' }, star)),
      tag(cls, text),
      h('dl', { class: 'kv' }, h('dt', { text: 'load_state' }), h('dd', { class: 'mono', text: s.load_state }),
        h('dt', { text: 'active_state' }), h('dd', { class: 'mono', text: s.active_state }), h('dt', { text: 'sub_state' }), h('dd', { class: 'mono', text: s.sub_state })),
      h('div', { class: 'btn-row' }, actBtn('start', 'primary', can && idle), actBtn('restart', '', can && active), actBtn('stop', 'ghost-danger', can && active)),
      note ? h('p', { class: 'note', text: note }) : null,
      h('hr', { class: 'divider' }),
      logsView(s.name));
  }
  function logsView(name) {
    const wrap = h('div');
    const pre = h('pre', { class: 'log tall', tabindex: '0' });
    const info = h('span', { class: 'small muted' });
    const lsearch = searchBox(t('search_logs'), (v) => { logs.q = v; paint(); }, logs.q);
    const copyVisible = h('button', { class: 'btn small', type: 'button', text: t('copy_visible'), onclick: () => copyText(visible) });
    const copyAll = h('button', { class: 'btn small', type: 'button', text: t('copy_all'), onclick: () => copyText(logs.text) });
    let visible = '';
    function paint() {
      if (logs.name !== name || (logs.loading && !logs.text)) { fill(pre, t('loading_logs')); info.textContent = ''; return; }
      if (logs.err && !logs.text) { fill(pre, h('span', { class: 'danger', text: logs.err })); info.textContent = ''; return; }
      const needle = lower(logs.q.trim());
      const lines = logs.text.split('\n');
      if (lines.length && lines[lines.length - 1] === '') lines.pop();
      if (!lines.length) { fill(pre, t('no_logs')); info.textContent = ''; visible = ''; return; }
      if (!needle) {
        visible = lines.join('\n');
        fill(pre, visible);
        info.textContent = t('n_lines', lines.length);
      } else {
        let hits = 0;
        const shown = lines.filter((l) => lower(l).includes(needle));
        const frag = document.createDocumentFragment();
        shown.forEach((l, i) => {
          const low = lower(l);
          let at = 0, k;
          while ((k = low.indexOf(needle, at)) >= 0) {
            frag.append(l.slice(at, k), h('mark', { text: l.slice(k, k + needle.length) }));
            at = k + needle.length; hits++;
          }
          frag.append(l.slice(at) + (i < shown.length - 1 ? '\n' : ''));
        });
        visible = shown.join('\n');
        fill(pre, shown.length ? frag : t('no_matching_logs'));
        info.textContent = t('log_matches', hits, shown.length);
      }
      if (logs.err) info.textContent = logs.err;
      copyVisible.disabled = !visible; copyAll.disabled = !logs.text;
    }
    const lrb = h('button', { class: 'btn small', type: 'button', disabled: logs.loading, onclick: () => loadLogs(name, true) }, icon('refresh'), t('refresh'));
    add(wrap, [
      h('div', { class: 'panel-title' }, t('logs'), h('span', { class: 'small muted', text: t('logs_note') })),
      h('div', { class: 'log-tools' }, lsearch.el, lrb),
      h('div', { class: 'log-tools' }, info, h('span', { style: { flex: 1 } }), copyVisible, copyAll),
      logs.name === name && logs.truncated ? h('p', { class: 'note warn', text: t('logs_truncated') }) : null,
      pre]);
    paint();
    if (logs.name === name && !logs.loading && !logs.err) requestAnimationFrame(() => { pre.scrollTop = pre.scrollHeight; });
    return wrap;
  }
  async function loadLogs(name, force = false) {
    if (!force && logs.name === name && (logs.text || logs.loading)) return;
    if (logs.loading && logs.name === name) return;
    Object.assign(logs, { name, loading: true, err: '' });
    if (!force) logs.text = '';
    renderDetail();
    try {
      const d = await api('GET', `services/${enc(name)}/logs`);
      if (!ctx.alive() || logs.name !== name) return;
      if (typeof d.logs !== 'string') throw new ApiError(t('err_data_format'));
      logs.text = d.logs; logs.truncated = !!d.truncated;
    } catch (e) { if (!ctx.alive() || logs.name !== name) return; logs.err = e.message; }
    logs.loading = false;
    if (selected === name) renderDetail();
  }
  function serviceAction(s, action) {
    const label = t('action_' + action);
    const run = async () => { await write(`services/${enc(s.name)}/action`, { action }); toast(t('service_done')); load(); };
    if (action === 'start') {
      run().catch((e) => toast(e.message, true));
      return;
    }
    confirmDialog({
      title: t('confirm_service_title', label), danger: action === 'stop',
      body: [h('p', { class: 'mono', style: { color: 'var(--text)' }, text: s.name }), h('p', { text: t('will_interrupt') })],
      confirm: t('confirm_action', label), run,
    });
  }
  async function load() {
    if (loading) return;
    loading = true; rb.busy(true);
    try {
      const d = await api('GET', 'services');
      if (!ctx.alive()) return;
      if (!Array.isArray(d.services)) throw new ApiError(t('err_data_format'));
      supported = d.supported !== false; data = d.services; err = '';
    } catch (e) { if (!ctx.alive()) return; err = e.message; }
    loading = false; rb.busy(false);
    renderList(); renderDetail();
  }
  renderList(); renderDetail();
  load().then(() => { if (selected && ctx.alive()) loadLogs(selected); });
  return {
    update(arg) {
      selected = arg;
      renderList(); renderDetail();
      if (arg && data) loadLogs(arg);
      if (arg && window.matchMedia('(max-width: 1099px)').matches) window.scrollTo(0, 0);
    },
  };
}

// ---------------------------------------------------------------- tools: timers / ports / disks
function toolPage(el, ctx, { title, sub, path, placeholder, filter, render, after }) {
  let data = null, err = '', loading = false, q = '';
  const rb = refreshBtn(() => load());
  const search = placeholder ? searchBox(placeholder, (v) => { q = v; paint(); }) : null;
  const body = h('div');
  add(el, [head(title, sub, rb), search ? h('div', { class: 'toolbar' }, search.el) : null, body]);
  function paint() {
    if (!data) { fill(body, err ? errorBlock(err, load) : emptyBlock(t('reading'))); return; }
    const needle = lower(q.trim());
    fill(body, err ? staleBanner(err) : null, render(data, needle, load));
  }
  async function load() {
    if (loading) return;
    loading = true; rb.busy(true);
    try {
      const d = await api('GET', path);
      if (!ctx.alive()) return;
      data = d; err = '';
    } catch (e) {
      if (!ctx.alive()) return;
      err = e.status === 404 ? t('err_tool_unsupported') : e.message;
    }
    loading = false; rb.busy(false); paint();
    if (after) after(data);
  }
  paint(); load();
  return { reload: load };
}
function timers(el, ctx) {
  toolPage(el, ctx, {
    title: t('systemd_timers'), sub: t('tools_note'), path: 'timers', placeholder: t('search_timers'),
    render(d, needle, reload) {
      if (d.supported === false) return emptyBlock(t('timers_unsupported'));
      if (!Array.isArray(d.timers)) return errorBlock(t('err_tool_format'));
      const rows = d.timers.filter((x) => !needle || [x.name, x.description, x.unit].some((v) => lower(v).includes(needle)));
      const unitLink = (u) => (u && /\.service$/.test(u) ? h('a', { class: 'link mono', href: '#/services/' + enc(u), text: u }) : h('span', { class: 'mono', text: u || DASH }));
      const runBtn = (x) => (x.can_run
        ? h('button', { class: 'btn small', type: 'button', text: t('run_now'), onclick: () => confirmDialog({
          title: t('run_title'), confirm: t('confirm_run'),
          body: [h('p', { class: 'mono', style: { color: 'var(--text)' }, text: x.name }), h('p', { text: t('trigger_unit', x.unit || DASH) }), h('p', { text: t('run_warning') })],
          run: async () => { await write(`timers/${enc(x.name)}/run`, { confirm: x.name }); toast(t('run_accepted')); reload(); },
        }) })
        : h('span', { class: 'small muted', title: t('run_not_allowed'), text: DASH }));
      return [
        d.truncated ? h('div', { class: 'banner warn', text: t('tool_truncated') }) : null,
        !d.timers.length ? emptyBlock(t('no_data')) : !rows.length ? emptyBlock(t('no_results')) : h('div', { class: 'list' },
          h('div', { class: 'row th timer' }, ['timer', 'state', 'next_run', 'last_run', 'trigger', ''].map((k) => h('div', { class: 'cell', text: k ? t('col_' + k) : '' }))),
          rows.map((x) => {
            const cls = x.active_state === 'active' ? 'ok' : x.active_state === 'failed' ? 'danger' : x.active_state === 'inactive' ? '' : 'warn';
            return h('div', { class: 'row static timer' },
              h('div', { class: 'cell' }, h('span', { class: 'name mono', text: x.name }), h('span', { class: 'l2', text: x.description || DASH }),
                h('span', { class: 'l2 only-m', text: t('next_line', x.next_run || DASH) })),
              h('div', { class: 'cell only-d' }, tag(cls, `${x.active_state} · ${x.sub_state}`)),
              h('div', { class: 'cell wrap small only-d', text: x.next_run || DASH }),
              h('div', { class: 'cell wrap small only-d muted', text: x.last_run || DASH }),
              h('div', { class: 'cell only-d' }, unitLink(x.unit)),
              h('div', { class: 'cell r' }, h('span', { class: 'only-m', style: { marginBottom: '6px' } }, tag(cls, x.sub_state)), runBtn(x)));
          })),
        h('p', { class: 'note', text: t('timers_note') }),
      ];
    },
  });
}
function ports(el, ctx) {
  toolPage(el, ctx, {
    title: t('listening_ports'), sub: t('tools_note'), path: 'ports', placeholder: t('search_ports'),
    render(d, needle) {
      if (d.supported === false) return emptyBlock(t('ports_unsupported'));
      if (!Array.isArray(d.ports)) return errorBlock(t('err_tool_format'));
      const rows = d.ports.filter((x) => !needle || [x.protocol, x.address, x.process, x.service, x.pid ? String(x.pid) : ''].some((v) => lower(v).includes(needle)));
      const svc = (s) => (s ? h('a', { class: 'link mono', href: '#/services/' + enc(s), text: s }) : h('span', { class: 'muted', text: DASH }));
      return [
        d.truncated ? h('div', { class: 'banner warn', text: t('tool_truncated') }) : null,
        !d.ports.length ? emptyBlock(t('no_data')) : !rows.length ? emptyBlock(t('no_results')) : h('div', { class: 'list' },
          h('div', { class: 'row th port' }, ['protocol', 'address', 'pid', 'process', 'service'].map((k) => h('div', { class: 'cell', text: t('col_' + k) }))),
          rows.map((x) => h('div', { class: 'row static port' },
            h('div', { class: 'cell mono only-d', text: x.protocol }),
            h('div', { class: 'cell' }, h('span', { class: 'mono name', text: x.address }),
              h('span', { class: 'l2 only-m', text: `${x.protocol} · ${x.process || DASH}${x.pid ? ' · PID ' + x.pid : ''}` }),
              x.service ? h('span', { class: 'l2 only-m' }, svc(x.service)) : null),
            h('div', { class: 'cell mono only-d', text: x.pid || DASH }),
            h('div', { class: 'cell only-d', text: x.process || DASH }),
            h('div', { class: 'cell only-d' }, svc(x.service))))),
        h('p', { class: 'note', text: t('ports_note') }),
      ];
    },
  });
}
const SCAN_REASONS = {
  cancelled: 'reason_cancelled', 'time limit': 'reason_time', 'entry limit': 'reason_entries', 'depth limit': 'reason_depth',
  'permission or changed entry': 'reason_permission', 'changed entry': 'reason_changed', 'filesystem boundary': 'reason_boundary',
  'symlinks skipped': 'reason_symlinks', 'special files skipped': 'reason_special', 'directory read error': 'reason_read_error',
};
function disks(el, ctx) {
  let scan = null; // { path, state: 'running'|'done'|'error', result, err }
  const scanBox = h('div');
  function paintScan() {
    if (!scan) { fill(scanBox); return; }
    if (scan.state === 'running') { fill(scanBox, h('p', { class: 'note warn', text: t('scanning') })); return; }
    if (scan.state === 'error') { fill(scanBox, h('div', { class: 'banner danger', text: t('scan_failed', scan.err) })); return; }
    const r = scan.result;
    const reasons = String(r.reason || '').split(/,\s*/).filter(Boolean).map((x) => (SCAN_REASONS[x] ? t(SCAN_REASONS[x]) : x)).join(t('list_separator'));
    const entries = Array.isArray(r.entries) ? r.entries : [];
    const max = entries.reduce((m, e) => Math.max(m, fin(e.bytes) ? e.bytes : 0), 0);
    fill(scanBox,
      h('div', { class: 'panel-title', style: { marginTop: '16px' } }, h('span', { class: 'mono', text: t('scan_header', r.path) })),
      h('div', { class: 'banner ' + (r.partial ? 'warn' : ''), text: r.partial ? t('scan_partial', reasons) : t('scan_complete') }),
      h('p', { class: 'small sub', text: t('scan_summary', bytes(r.bytes), t('files_count', r.files), r.elapsed_ms) }),
      entries.length ? h('div', {}, h('div', { class: 'small muted', text: t('scan_entries') }), entries.map((e) => h('div', { class: 'entry' },
        h('span', { class: 'mono cell', text: e.name }), h('span', { class: 'num small', text: `${bytes(e.bytes)} · ${t('files_count', e.files)}` }),
        bar(max > 0 ? (e.bytes / max) * 100 : 0, 'cy')))) : null);
  }
  function startScan(path) {
    confirmDialog({
      title: t('scan_title'), confirm: t('confirm_scan'),
      body: [h('p', { class: 'mono', style: { color: 'var(--text)' }, text: path }), h('p', { text: t('scan_warning') })],
      run: async () => {
        scan = { path, state: 'running' }; paintScan();
        try {
          const r = await write('disk-scan', { path }, false);
          if (!ctx.alive()) return;
          if (typeof r.path !== 'string' || !fin(r.bytes) || (r.metric && r.metric !== 'logical_bytes')) throw new ApiError(t('err_scan_metric'));
          scan = { path, state: 'done', result: r };
        } catch (e) { if (ctx.alive()) scan = { path, state: 'error', err: e.message }; }
        if (ctx.alive()) paintScan();
      },
    });
  }
  toolPage(el, ctx, {
    title: t('disks_scan_title'), sub: t('tools_note'), path: 'disks', placeholder: t('search_name_path'),
    render(d, needle) {
      if (!Array.isArray(d.disks)) return errorBlock(t('err_tool_format'));
      const rows = d.disks.filter((x) => !needle || [x.mountpoint, x.device, x.filesystem].some((v) => lower(v).includes(needle)));
      const roots = Array.isArray(d.scan_roots) ? d.scan_roots : [];
      return [
        d.truncated ? h('div', { class: 'banner warn', text: t('tool_truncated') }) : null,
        !d.disks.length ? emptyBlock(t('no_data')) : !rows.length ? emptyBlock(t('no_results')) : h('div', { class: 'list' },
          h('div', { class: 'row th disk' }, ['mount', 'device', 'usage', 'available', 'inodes'].map((k) => h('div', { class: 'cell', text: t('col_' + k) }))),
          rows.map((x) => {
            const p = ratio(x.used, x.total);
            const ip = x.inodes_total > 0 ? ratio(x.inodes_used, x.inodes_total) : NaN;
            const cls = p >= 90 ? 'danger' : p >= 80 ? 'warn' : '';
            return h('div', { class: 'row static disk' },
              h('div', { class: 'cell' }, h('span', { class: 'name mono', text: x.mountpoint }), h('span', { class: 'l2 only-m mono', text: `${x.device} · ${x.filesystem}` }),
                h('div', { class: 'only-m' }, bar(p, cls))),
              h('div', { class: 'cell only-d' }, h('span', { class: 'mono', text: x.device }), h('span', { class: 'l2', text: x.filesystem })),
              h('div', { class: 'cell only-d' }, h('span', { class: 'num small', text: `${bytes(x.used)} / ${bytes(x.total)} · ${pctText(p)}` }), bar(p, cls)),
              h('div', { class: 'cell r num only-d', text: bytes(x.available) }),
              h('div', { class: 'cell r num only-d', text: fin(ip) ? pctText(ip) : t('unavailable') }),
              h('div', { class: 'cell r num only-m' }, pctText(p), h('span', { class: 'l2', text: t('free_line', bytes(x.available)) })));
          })),
        h('div', { class: 'panel', style: { marginTop: '16px' } },
          h('div', { class: 'panel-title' }, t('limited_scan')),
          h('p', { class: 'note', style: { marginTop: 0 }, text: t('scan_note') }),
          roots.length ? h('div', { class: 'btn-row', style: { marginTop: '12px' } }, roots.map((r) => h('button', { class: 'btn small mono', type: 'button', text: t('scan_path', r), onclick: () => startScan(r) })))
            : h('p', { class: 'note', text: t('scan_disabled') }),
          scanBox),
      ];
    },
    after: paintScan,
  });
}

// ---------------------------------------------------------------- jobs
const TIMEOUTS = [300, 1800, 7200, 86400];
const draft = { name: '', script: '', timeout: 1800 };
function jobState(j) {
  switch (j.state) {
    case 'running': return ['warn', t('job_state_running')];
    case 'succeeded': return ['ok', t('job_state_succeeded')];
    case 'failed': return ['danger', t('job_state_failed', j.exit_code)];
    case 'cancelled': return ['', t('job_state_cancelled')];
    case 'timeout': return ['danger', t('job_state_timeout')];
    case 'killed': return ['danger', t('job_state_killed', j.signal || '?')];
    case 'lost': return ['warn', t('job_state_lost')];
    default: return ['warn', t('job_state_unknown')];
  }
}
function jobs(el, ctx) {
  let info = null, err = '', loading = false, selected = ctx.arg;
  const det = { id: '', job: null, script: '', output: '', next: null, size: 0, trimmed: false, err: '', timer: 0, busy: false };
  const rb = refreshBtn(() => { load(); if (selected) poll(true); });
  const body = h('div');
  add(el, [head(t('nav_scripts'), t('scripts_subtitle'), rb), body]);
  let editor = null, right = null;

  function renderAll() {
    if (!info) { fill(body, err ? errorBlock(err, load) : emptyBlock(t('loading'))); return; }
    if (!info.supported) {
      fill(body, h('div', { class: 'panel' },
        h('div', { class: 'panel-title' }, h('span', { style: { display: 'flex', gap: '8px', alignItems: 'center' } }, icon('lock'), t('exec_disabled'))),
        info.message ? h('p', { class: 'sub small', text: info.message }) : null,
        h('p', { class: 'note' }, t('exec_enable_hint'), ' ', h('span', { class: 'code', text: 'sudo sh install.sh --enable-exec' }))));
      return;
    }
    if (!editor) editor = buildEditor();
    right = h('div');
    fill(body, err ? staleBanner(err) : null, h('div', { class: 'split wide-left' }, editor.el, right));
    editor.el.classList.toggle('hide-narrow', !!selected);
    if (selected) renderDetail(); else renderList();
  }
  function buildEditor() {
    const runnerText = info.runner === 'systemd' ? t('runner_systemd') : t('runner_direct');
    const name = h('input', { class: 'input', maxlength: '60', placeholder: t('hint_job_name'), value: draft.name, oninput: () => { draft.name = name.value; } });
    const script = h('textarea', { class: 'input', rows: '14', spellcheck: 'false', autocapitalize: 'off', autocomplete: 'off', placeholder: t('hint_script'), oninput: () => { draft.script = script.value; size(); } });
    script.value = draft.script;
    const opts = TIMEOUTS.includes(draft.timeout) ? TIMEOUTS : [...TIMEOUTS, draft.timeout].sort((a, b) => a - b);
    const timeout = h('select', { class: 'input', onchange: () => { draft.timeout = Number(timeout.value); } },
      opts.map((s) => h('option', { value: String(s), selected: s === draft.timeout, text: timeoutLabel(s) })));
    const sizeEl = h('span', { class: 'small muted num' });
    const size = () => { sizeEl.textContent = bytes(new TextEncoder().encode(script.value).length) + ' / 64 KiB'; };
    size();
    script.addEventListener('keydown', (e) => {
      if ((e.metaKey || e.ctrlKey) && e.key === 'Enter') { e.preventDefault(); submit(); }
    });
    function submit() {
      const text = script.value.replace(/\r\n?/g, '\n');
      const n = new TextEncoder().encode(text).length;
      if (!text.trim() || text.includes('\0') || n > 65536) { toast(t('err_script_invalid'), true); return; }
      const jobName = name.value.trim();
      const title = jobName || (text.split('\n').find((l) => l.trim() && !l.startsWith('#!')) || '').trim().slice(0, 60) || t('job_script');
      confirmDialog({
        title: t('confirm_script_title'), wide: true, danger: true, confirm: t('confirm_script_run', info.user),
        body: [
          h('p', { text: t('confirm_script_summary', title, timeoutLabel(draft.timeout), bytes(n)) }),
          h('p', { class: 'small', text: t('host_line', S.sys ? S.sys.hostname : DASH) + ' · ' + t('exec_identity', info.user, runnerText) }),
          h('pre', { class: 'log preview', text: text })],
        run: async () => {
          const d = await write('jobs', { name: jobName, script: text, timeout_seconds: draft.timeout });
          toast(t('job_started'));
          if (d.job && d.job.id) location.hash = '#/jobs/' + enc(d.job.id);
          load();
        },
      });
    }
    const el2 = h('div', { class: 'panel' },
      h('div', { class: 'panel-title' }, t('new_script'), h('span', { class: 'small muted', text: t('shortcut_run') })),
      h('p', { class: 'small sub', style: { margin: '0 0 12px' }, text: t('exec_identity', info.user, runnerText) }),
      h('div', { class: 'banner warn small' }, t('exec_warning', info.user)),
      h('label', { class: 'field' }, h('span', { text: t('job_name') }), name),
      h('label', { class: 'field' }, h('span', { text: t('job_script') }), script),
      h('label', { class: 'field' }, h('span', { text: t('job_timeout') }), timeout),
      h('div', { class: 'btn-row', style: { alignItems: 'center' } },
        h('button', { class: 'btn ghost', type: 'button', text: t('clear'), onclick: () => { draft.name = ''; draft.script = ''; name.value = ''; script.value = ''; size(); } }),
        h('span', { style: { flex: 1 } }, sizeEl),
        h('button', { class: 'btn primary', type: 'button', text: t('run_script'), onclick: submit })));
    return { el: el2 };
  }
  function renderList() {
    const list = info.jobs || [];
    fill(right, h('div', {},
      h('div', { class: 'panel-title', style: { marginTop: '4px' } }, t('recent_jobs'), h('span', { class: 'small muted', text: t('jobs_kept_note') })),
      !list.length ? h('div', { class: 'list' }, emptyBlock(t('jobs_empty'))) : h('div', { class: 'list' }, list.map((j) => {
        const [cls, text] = jobState(j);
        return h('button', { class: 'row jobrow', type: 'button', onclick: () => { location.hash = '#/jobs/' + enc(j.id); } },
          h('div', { class: 'cell' }, h('span', { class: 'name', text: j.name }), h('span', { class: 'l2 num', text: t('job_row_detail', stamp(j.started_at), timeoutLabel(j.timeout_seconds)) })),
          tag(cls, text));
      }))));
  }
  function renderDetail() {
    const back = h('a', { class: 'link', href: '#/jobs', text: t('back_to_jobs') });
    if (!det.job) {
      fill(right, h('div', { class: 'panel' }, back, det.err ? errorBlock(det.err, () => poll(true)) : emptyBlock(t('loading'))));
      return;
    }
    const j = det.job;
    const [cls, text] = jobState(j);
    const running = j.state === 'running';
    const pre = h('pre', { class: 'log tall', tabindex: '0', text: det.output || t('job_output_empty') });
    const actions = running
      ? [h('button', { class: 'btn ghost-danger', type: 'button', text: t('cancel_job'), onclick: () => confirmDialog({
        title: t('cancel_job_title'), danger: true, confirm: t('cancel_job'),
        body: [h('p', { text: j.name }), h('p', { text: t('cancel_job_warning') })],
        run: async () => { await write(`jobs/${enc(j.id)}/cancel`, {}); toast(t('cancel_requested')); poll(true); },
      }) })]
      : [
        h('button', { class: 'btn', type: 'button', disabled: !det.output, onclick: () => copyText(det.output) }, icon('copy'), t('copy_output')),
        h('button', { class: 'btn', type: 'button', disabled: !det.script, text: t('rerun_job'), onclick: () => {
          draft.name = j.name; draft.script = det.script; draft.timeout = j.timeout_seconds || 1800;
          editor = null; location.hash = '#/jobs'; toast(t('rerun_loaded'));
        } }),
        h('button', { class: 'btn ghost-danger', type: 'button', disabled: j.state === 'unknown', text: t('delete_job'), onclick: () => confirmDialog({
          title: t('delete_job_title'), danger: true, confirm: t('delete_job'),
          body: [h('p', { text: j.name }), h('p', { text: t('delete_job_warning') })],
          run: async () => { await write(`jobs/${enc(j.id)}/delete`, {}); toast(t('job_deleted')); location.hash = '#/jobs'; load(); },
        }) }),
      ];
    fill(right, h('div', { class: 'panel' },
      back,
      h('h2', { style: { margin: '12px 0 4px', fontSize: '18px', wordBreak: 'break-word' }, text: j.name }),
      h('div', { class: 'small muted mono', text: j.id }),
      h('div', { style: { margin: '12px 0' } }, tag(cls, text)),
      h('div', { class: 'job-state' },
        h('div', {}, t('col_started'), h('b', { class: 'num', text: stamp(j.started_at) })),
        h('div', {}, t('col_finished'), h('b', { class: 'num', text: running ? DASH : stamp(j.finished_at) })),
        h('div', {}, t('job_timeout'), h('b', { text: timeoutLabel(j.timeout_seconds) })),
        h('div', {}, t('col_runner'), h('b', { class: 'mono', text: j.runner || DASH }))),
      running ? h('p', { class: 'note', text: t('job_polling') }) : null,
      det.err ? h('div', { class: 'banner warn', text: t('error_refresh_check', det.err) }) : null,
      h('div', { class: 'btn-row', style: { margin: '12px 0 16px' } }, actions),
      h('div', { class: 'panel-title' }, t('job_output'), det.trimmed ? h('span', { class: 'small warn', text: t('job_output_trimmed') }) : null),
      pre,
      det.script ? h('details', { style: { marginTop: '12px' } }, h('summary', { class: 'small sub', style: { cursor: 'pointer' }, text: t('job_script') }), h('pre', { class: 'log preview', text: det.script })) : null));
    requestAnimationFrame(() => { if (det.stick !== false) pre.scrollTop = pre.scrollHeight; });
    pre.addEventListener('scroll', () => { det.stick = pre.scrollTop + pre.clientHeight >= pre.scrollHeight - 24; });
  }
  async function poll(reset = false) {
    clearTimeout(det.timer);
    const id = selected;
    if (!id || det.busy) return;
    if (reset || det.id !== id) Object.assign(det, { id, job: reset && det.id === id ? det.job : null, script: reset && det.id === id ? det.script : '', output: '', next: null, size: 0, trimmed: false, err: '', stick: true });
    det.busy = true;
    const offset = det.next;
    try {
      const d = await api('GET', `jobs/${enc(id)}${offset === null ? '' : '?offset=' + offset}`);
      if (!ctx.alive() || det.id !== id) return;
      if (!d.job || typeof d.output !== 'string') throw new ApiError(t('err_data_format'));
      det.job = d.job;
      if (typeof d.script === 'string') det.script = d.script;
      if (offset === null || d.offset !== offset) { det.output = d.output; det.trimmed = d.offset > 0; } else det.output += d.output;
      if (det.output.length > 131072) { det.output = det.output.slice(-131072); det.trimmed = true; }
      det.next = d.next_offset; det.size = d.size; det.err = '';
    } catch (e) {
      if (!ctx.alive() || det.id !== id) return;
      det.err = e.message;
    } finally { det.busy = false; }
    if (!ctx.alive() || selected !== id) return;
    renderDetail();
    const more = fin(det.next) && fin(det.size) && det.next < det.size;
    if (det.job && (det.job.state === 'running' || more) && !document.hidden) {
      det.timer = setTimeout(() => poll(), more && !det.err ? 300 : 2000);
    }
  }
  async function load() {
    if (loading) return;
    loading = true; rb.busy(true);
    try {
      const d = await api('GET', 'jobs');
      if (!ctx.alive()) return;
      if (!Array.isArray(d.jobs)) throw new ApiError(t('err_data_format'));
      const changed = !info || info.supported !== d.supported || info.user !== d.user || info.runner !== d.runner;
      info = d; err = '';
      if (changed) editor = null;
    } catch (e) { if (!ctx.alive()) return; err = e.status === 404 ? t('err_tool_unsupported') : e.message; }
    loading = false; rb.busy(false);
    if (!(selected && right && editor)) renderAll();
  }
  load().then(() => { if (selected && ctx.alive() && info && info.supported) poll(true); });
  return {
    update(arg) {
      selected = arg;
      clearTimeout(det.timer);
      renderAll();
      if (arg && info && info.supported) poll(true);
      if (!arg) load();
      window.scrollTo(0, 0);
    },
    unmount() { clearTimeout(det.timer); },
    visibility(hidden) { clearTimeout(det.timer); if (!hidden && selected) poll(); },
  };
}

// ---------------------------------------------------------------- settings / more
function settings(el, ctx) {
  if (!S.sys) {
    api('GET', 'system').then((d) => {
      if (validSystem(d) && ctx.alive()) { setSys(d); fill(el); settings(el, ctx); }
    }).catch(() => {});
  }
  const c = caps();
  const host = S.sys ? S.sys.hostname : '';
  const remember = h('input', { type: 'checkbox', checked: S.remembered, onchange: () => {
    store.del('localStorage', 'lh-token'); store.del('sessionStorage', 'lh-token');
    S.remembered = remember.checked && store.set('localStorage', 'lh-token', S.token);
    if (!S.remembered) store.set('sessionStorage', 'lh-token', S.token);
    remember.checked = S.remembered;
    toast(t(S.remembered ? 'token_remembered' : 'token_session'));
  } });
  const capRow = (on, label) => h('div', { class: 'cap' }, h('span', { class: 'dot ' + (on ? 'ok' : '') }), label,
    h('span', { class: 'state ' + (on ? 'ok' : 'muted'), text: on === undefined ? t('unknown') : on ? t('cap_on') : t('cap_off') }));
  const powerRow = (action, danger) => h('button', { class: 'btn ' + (danger ? 'ghost-danger' : ''), type: 'button', onclick: () => powerDialog(action) },
    icon('power'), t(action === 'reboot' ? 'reboot_server' : 'shutdown_server'));
  add(el, [
    head(t('nav_settings'), t('settings_subtitle')),
    h('div', { class: 'grid2' },
      h('div', { class: 'panel' },
        h('div', { class: 'panel-title' }, t('connection')),
        h('dl', { class: 'kv' }, h('dt', { text: t('server_address') }), h('dd', { class: 'mono', text: location.origin }),
          h('dt', { text: t('host') }), h('dd', { class: 'mono', 'data-hostname': '', text: host || DASH }),
          h('dt', { text: t('access_token') }), h('dd', { text: '••••••••' + S.token.slice(-4) })),
        h('label', { class: 'check', style: { marginBottom: '16px' } }, remember, h('div', {}, t('remember_token'), h('div', { class: 'small muted', text: t('remember_hint') }))),
        h('button', { class: 'btn ghost-danger', type: 'button', text: t('sign_out'), onclick: () => signOut() })),
      h('div', { class: 'panel' },
        h('div', { class: 'panel-title' }, t('language')),
        langSelect(),
        h('p', { class: 'note', text: t('language_note') }),
        h('hr', { class: 'divider' }),
        h('div', { class: 'panel-title' }, t('about')),
        h('p', { class: 'small sub', style: { margin: 0 }, text: t('about_text') }))),
    h('div', { class: 'panel' },
      h('div', { class: 'panel-title' }, t('capabilities')),
      h('div', { class: 'cap-list' },
        capRow(c.process_signal, t('cap_process')), capRow(c.service_control, t('cap_service')),
        capRow(c.power, t('cap_power')), capRow(c.exec, t('cap_exec')))),
    h('div', { class: 'panel' },
      h('div', { class: 'panel-title' }, t('host_control'), h('span', { class: 'small muted mono', 'data-hostname': '', text: host })),
      !S.sys ? h('p', { class: 'note', text: t('host_control_hint') })
        : !c.power ? h('div', { class: 'banner' }, icon('lock'), h('div', {}, h('b', { text: t('power_disabled') }), h('div', { class: 'small', text: t('power_enable_hint') })))
          : h('div', { class: 'btn-row' }, powerRow('reboot', false), powerRow('poweroff', true))),
    h('p', { class: 'note', style: { textAlign: 'center', marginTop: '24px' }, text: t('brand_tagline') })]);
  return {};
}
function powerDialog(action) {
  const host = S.sys && S.sys.hostname;
  if (!host) { toast(t('verify_permission_first'), true); return; }
  const reboot = action === 'reboot';
  let btn;
  const input = h('input', { class: 'input mono', autocomplete: 'off', autocapitalize: 'off', spellcheck: 'false', placeholder: t('hint_type_hostname'), oninput: () => { btn.disabled = input.value !== host; } });
  dialog({
    title: t(reboot ? 'reboot_title' : 'shutdown_title'),
    body: () => [h('p', { class: 'mono', style: { color: 'var(--text)' }, text: host }), h('p', { text: t(reboot ? 'reboot_warning' : 'shutdown_warning') }),
      h('label', { class: 'field' }, h('span', { text: t('hint_type_hostname') }), input)],
    foot: (c) => [cancelBtn(c), btn = h('button', { class: 'btn danger', type: 'button', disabled: true, text: t(reboot ? 'confirm_reboot' : 'confirm_shutdown'), onclick: async () => {
      btn.disabled = true; btn.textContent = t('submitting'); c.error('');
      try { await write('power', { action, confirm: host }); c.close(); toast(t(reboot ? 'reboot_scheduled' : 'shutdown_scheduled')); }
      catch (e) { c.error(e.message); btn.textContent = t(reboot ? 'confirm_reboot' : 'confirm_shutdown'); btn.disabled = input.value !== host; }
    } })],
  });
}
function more(el) {
  const desc = { timers: 'more_timers', ports: 'more_ports', disks: 'more_disks', settings: 'more_settings' };
  add(el, [head(t('nav_more'), t('tools_note')),
    h('div', { class: 'list more-list' }, MORE_PAGES.map((p) => h('a', { href: '#/' + p }, icon(ICON_OF[p]),
      h('div', {}, t((NAV.find(([n]) => n === p) || [])[1]), h('span', { text: t(desc[p]) })), icon('chevron'))))]);
  return {};
}
const PAGES = { overview, processes, services, timers, ports, disks, jobs, settings, more };

// ---------------------------------------------------------------- boot
document.documentElement.lang = LOCALE[lang];
S.token = store.get('sessionStorage', 'lh-token') || store.get('localStorage', 'lh-token') || '';
S.remembered = !!store.get('localStorage', 'lh-token');
route();
if (S.token && parseHash().page !== 'overview') {
  api('GET', 'system').then((d) => { if (validSystem(d)) setSys(d); }).catch(() => {});
}
