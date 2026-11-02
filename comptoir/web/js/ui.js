// ================================================================== Comptoir · boîte à outils de l'interface
// Reprise du panneau Windy Watch : mêmes composants, mêmes animations, même respect de « moins d'animations ».
export const $app = document.getElementById('app'), $tip = document.getElementById('tip'), $toasts = document.getElementById('toasts');
export const S = { prev: {}, shown: null, filters: {}, timer: null };
export const calm = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
/** Vrai quand on arrive sur une page (et pas quand elle se redessine) : c'est là que les entrées s'animent. */
export const arriving = () => S.shown !== location.pathname;
function h(tag, attrs, ...kids) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v === null || v === undefined || v === false) continue;
    if (k === 'class') el.className = v;
    else if (k.startsWith('on')) el.addEventListener(k.slice(2), v);
    else if (k === 'value') el.value = v;
    else if (v === true) el.setAttribute(k, '');
    else el.setAttribute(k, v);
  }
  for (const kid of kids.flat(Infinity)) {
    if (kid === null || kid === undefined || kid === false) continue;
    el.append(kid instanceof Node ? kid : document.createTextNode(String(kid)));
  }
  return el;
}

const pref = (k, v) => { try { if (v === undefined) return JSON.parse(localStorage.getItem('comptoir-' + k)); localStorage.setItem('comptoir-' + k, JSON.stringify(v)); } catch (e) { /* stockage indisponible */ } return v === undefined ? null : v; };

const root = document.documentElement;

const media = window.matchMedia('(prefers-color-scheme: dark)');

const theme = () => root.dataset.theme || (media.matches ? 'dark' : 'light');

function setTheme(t, ev) {
  // révélation en cercle depuis le bouton, là où le navigateur sait le faire
  if (ev && !calm && document.startViewTransition) {
    const x = ev.clientX || innerWidth / 2, y = ev.clientY || 0;
    root.style.setProperty('--vx', x + 'px');
    root.style.setProperty('--vy', y + 'px');
    root.style.setProperty('--vr', Math.hypot(Math.max(x, innerWidth - x), Math.max(y, innerHeight - y)) + 'px');
    document.startViewTransition(() => setTheme(t));
    return;
  }
  root.dataset.theme = t;
  try { localStorage.setItem('comptoir-theme', t); } catch (e) { /* stockage indisponible : le choix vaut pour cette visite */ }
  document.querySelectorAll('.switch:not(.mini)').forEach(b => b.setAttribute('aria-checked', String(t === 'dark')));
}

function themeSwitch() {
  const b = h('button', { class: 'switch', type: 'button', role: 'switch', 'aria-checked': String(theme() === 'dark'), 'aria-label': 'Mode sombre', title: 'Mode clair ou sombre' },
    ico('soleil', 16), h('i', {}), ico('lune', 16));
  b.addEventListener('click', ev => { ev.stopPropagation(); setTheme(theme() === 'dark' ? 'light' : 'dark', ev); });
  return b;
}

const NS = 'http://www.w3.org/2000/svg';

function s(tag, attrs, ...kids) {
  const el = document.createElementNS(NS, tag);
  for (const [k, v] of Object.entries(attrs || {})) if (v !== null && v !== undefined && v !== false) el.setAttribute(k, v);
  kids.flat(Infinity).forEach(k => { if (k) el.append(k); });
  return el;
}

const SQ = (x, y) => 'M' + x + ' ' + (y + 1) + 'a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v4a1 1 0 0 1-1 1h-4a1 1 0 0 1-1-1z';

function countUp(el, n, from) {
  const a = from || 0;
  if (calm || Math.abs(n - a) < 2 && !from) return;
  const t0 = performance.now(), last = el.textContent;
  const step = t => { const k = Math.min(1, (t - t0) / 650); el.textContent = k < 1 ? compact(Math.round(a + (n - a) * (1 - Math.pow(1 - k, 3)))) : last; if (k < 1) requestAnimationFrame(step); };
  requestAnimationFrame(step);
}

/** Chiffre rafraîchi : il glisse de l'ancienne valeur à la nouvelle et pulse une fois. */
function tween(el, key, n) {
  const before = S.prev[key];
  S.prev[key] = n;
  if (arriving()) { countUp(el, n); return; }
  if (before !== undefined && before !== n) { countUp(el, n, before); el.classList.add('bump'); }
}

const liveDot = text => h('span', { class: 'live' }, h('i', { 'aria-hidden': 'true' }), text || 'En direct');

/** Message en bas de l'écran ; une fine barre montre le temps qu'il reste. Cliquer le ferme. */
function toast(msg, bad, ms) {
  const life = ms || (bad ? 7000 : 4500);
  const t = h('div', { class: 'toast' + (bad ? ' bad' : ''), role: bad ? 'alert' : 'status', style: '--life:' + life + 'ms' }, msg, h('i', { class: 'life' }));
  const bye = () => { if (!t.isConnected || t.classList.contains('out')) return; t.classList.add('out'); setTimeout(() => t.remove(), calm ? 0 : 220); };
  t.addEventListener('click', ev => { if (!ev.target.closest('button, a')) bye(); });
  $toasts.append(t);
  while ($toasts.children.length > 4) $toasts.firstChild.remove();
  setTimeout(bye, life);
}

function modal(title, ...content) {
  const bg = h('div', { class: 'modal-bg' });
  const back = document.activeElement;
  const close = () => {
    document.removeEventListener('keydown', esc);
    if (calm) { bg.remove(); } else { bg.classList.add('out'); setTimeout(() => bg.remove(), 200); }
    if (back && back.focus) back.focus({ preventScroll: true });
  };
  const esc = ev => { if (ev.key === 'Escape') close(); };
  const box = h('div', { class: 'modal', role: 'dialog', 'aria-modal': 'true', 'aria-label': title }, h('i', { class: 'grab', 'aria-hidden': 'true' }), h('h2', {}, title), ...content);
  bg.append(box);
  // sur téléphone, la feuille se ferme en la tirant vers le bas
  let y0 = null, dy = 0;
  box.addEventListener('touchstart', ev => { if (box.scrollTop <= 0 && matchMedia('(max-width: 860px)').matches) { y0 = ev.touches[0].clientY; dy = 0; } }, { passive: true });
  box.addEventListener('touchmove', ev => { if (y0 === null) return; dy = Math.max(0, ev.touches[0].clientY - y0); box.style.transform = dy ? 'translateY(' + dy + 'px)' : ''; box.style.transition = 'none'; }, { passive: true });
  box.addEventListener('touchend', () => { if (y0 === null) return; box.style.transition = ''; if (dy > 90) close(); else box.style.transform = ''; y0 = null; });
  bg.addEventListener('mousedown', ev => { if (ev.target === bg) close(); });
  document.addEventListener('keydown', esc);
  document.body.append(bg);
  const first = box.querySelector('input, select, textarea');
  if (first) first.focus();
  return { close, box };
}

/** Bouton qui lance une action et montre le résultat ; désactivé pendant l'appel. */
async function run(btn, fn) {
  btn.disabled = true;
  btn.classList.add('busy');
  try {
    const r = await fn();
    if (r && r.message) toast(r.message);
    return true;
  } catch (e) {
    toast(e.message, true);
    return false;
  } finally {
    btn.disabled = false;
    btn.classList.remove('busy');
  }
}

const pad = n => String(n).padStart(2, '0');

function date(ms) {
  if (!ms) return '';
  const d = new Date(ms);
  return pad(d.getDate()) + '/' + pad(d.getMonth() + 1) + ' ' + pad(d.getHours()) + ':' + pad(d.getMinutes());
}

function ago(ms) {
  if (!ms) return '';
  const s = Math.max(0, (Date.now() - ms) / 1000);
  if (s < 60) return "à l'instant";
  if (s < 3600) return 'il y a ' + Math.floor(s / 60) + ' min';
  if (s < 86400) return 'il y a ' + Math.floor(s / 3600) + ' h';
  return 'il y a ' + Math.floor(s / 86400) + ' j';
}

function span(ms) {
  const m = Math.floor(ms / 60000);
  return m < 60 ? m + ' min' : m < 1440 ? Math.floor(m / 60) + ' h ' + pad(m % 60) : Math.floor(m / 1440) + ' j ' + Math.floor((m % 1440) / 60) + ' h';
}

const compact = n => n >= 10000 ? Math.floor(n / 1000) + 'k' : String(n);

const clock = ms => { const d = new Date(ms); return pad(d.getHours()) + ':' + pad(d.getMinutes()); };

const clockS = ms => clock(ms) + ':' + pad(new Date(ms).getSeconds());

const dur = ms => ms > 0 ? span(ms) : '0 min';

function dayLabel(ms) {
  const d = new Date(ms), t = new Date();
  const k = x => x.getFullYear() * 400 + x.getMonth() * 31 + x.getDate();
  if (k(d) === k(t)) return 'Aujourd’hui';
  t.setDate(t.getDate() - 1);
  if (k(d) === k(t)) return 'Hier';
  return d.toLocaleDateString('fr-FR', { weekday: 'long', day: 'numeric', month: 'long' });
}

async function copyText(text, msg) {
  try { await navigator.clipboard.writeText(text); } catch (e) {
    const t = h('textarea', { style: 'position:fixed;opacity:0' }); t.value = text; document.body.append(t); t.select();
    try { document.execCommand('copy'); } catch (e2) { /* rien à faire */ } t.remove();
  }
  toast(msg || 'Copié.');
}

const collator = new Intl.Collator('fr', { numeric: true, sensitivity: 'base' });

const textOf = v => v === null || v === undefined || v === false ? '' : v instanceof Node ? v.textContent : Array.isArray(v) ? v.map(textOf).join(' ') : String(v);

/**
 * Colonnes : [titre, valeur, classe, clé de tri]. Un clic sur un titre trie la colonne (par la clé si elle existe, sinon par le texte affiché),
 * un second clic inverse l'ordre.
 */
function table(cols, rows, onClick, emptyText) {
  if (!rows.length) return h('div', { class: 'empty' }, emptyText || 'Rien à afficher.');
  let sortBy = -1, dir = 1;
  const body = h('tbody', {});
  const heads = cols.map((c, i) => {
    if (!c[0]) return h('th', {});
    const th = h('th', { class: 'sortable', tabindex: 0, 'aria-sort': 'none', title: 'Trier' }, c[0], h('i', { class: 'arrow', 'aria-hidden': 'true' }));
    const go = () => {
      dir = sortBy === i ? -dir : 1;
      sortBy = i;
      heads.forEach(x => x.setAttribute('aria-sort', 'none'));
      th.setAttribute('aria-sort', dir > 0 ? 'ascending' : 'descending');
      const key = c[3] || (r => textOf(c[1](r)));
      paint(rows.slice().sort((a, b) => { const x = key(a), y = key(b); return dir * (typeof x === 'number' && typeof y === 'number' ? x - y : collator.compare(String(x), String(y))); }));
    };
    th.addEventListener('click', go);
    th.addEventListener('keydown', ev => { if (ev.key === 'Enter' || ev.key === ' ') { ev.preventDefault(); go(); } });
    return th;
  });
  const cell = (c, r) => {
    const v = c[1](r);
    const none = v === null || v === undefined || v === false || v === '' || (Array.isArray(v) && !v.flat(Infinity).filter(x => x !== null && x !== undefined && x !== false).length);
    return h('td', { class: [c[2], none ? 'void' : null].filter(Boolean).join(' ') || null, 'data-label': c[0] || null }, none ? null : h('div', { class: 'v' }, v));
  };
  const paint = list => body.replaceChildren(...list.map(r => h('tr', { class: onClick ? 'click' : null, tabindex: onClick ? 0 : null, onclick: onClick ? () => onClick(r) : null,
    onkeydown: onClick ? ev => { if (ev.key === 'Enter' && ev.target === ev.currentTarget) onClick(r); } : null }, cols.map(c => cell(c, r)))));
  paint(rows);
  return h('div', { class: 'scroll' }, h('table', {}, h('thead', {}, h('tr', {}, heads)), body));
}

/**
 * Courbe d'une mesure dans le temps. points : [{t, …}], key : la valeur à tracer.
 * opts : label, color, fmt (affichage d'une valeur), min, max, line (seuil dessiné en pointillé), from, to, step, unit.
 * Les trous (serveur éteint) coupent la courbe. Survol : ligne verticale et bulle avec la valeur.
 */
function chart(points, key, opts) {
  const o = Object.assign({ color: 'var(--accent)', fmt: v => String(v), min: null, max: null }, opts || {});
  const W = 600, H = 160, pts = points.filter(p => p[key] !== undefined && p[key] !== null);
  const box = h('div', { class: 'chart' + (arriving() ? ' fresh' : '') });
  const last = pts.length ? pts[pts.length - 1][key] : null;
  const headRow = h('div', { class: 'chart-head' }, h('span', { class: 'chart-label' }, h('i', { class: 'sw', style: 'background:' + o.color }), o.label),
    h('b', { class: 'chart-now' }, last === null ? '—' : o.fmt(last)));
  if (pts.length < 2) return h('div', { class: 'chart-card' }, headRow, h('div', { class: 'chart-empty' }, 'Pas encore assez de mesures. Une mesure est prise chaque minute.'));
  const from = o.from || pts[0].t, to = o.to || pts[pts.length - 1].t;
  const vals = pts.map(p => p[key]);
  let lo = o.min != null ? o.min : Math.min(...vals), hi = o.max != null ? o.max : Math.max(...vals);
  if (o.line !== undefined) { lo = Math.min(lo, o.line); hi = Math.max(hi, o.line); }
  if (hi - lo < 1e-9) { hi += 1; lo = Math.max(0, lo - 1); }
  if (o.max == null) hi += (hi - lo) * 0.12;
  const X = t => (t - from) / Math.max(1, to - from) * W, Y = v => H - (v - lo) / (hi - lo) * H;
  // segments : on coupe quand deux mesures sont trop éloignées
  const gap = (o.step || 60000) * 2.5, segs = [];
  let cur = [];
  pts.forEach((p, i) => { if (i && p.t - pts[i - 1].t > gap) { segs.push(cur); cur = []; } cur.push(p); });
  segs.push(cur);
  const d = seg => seg.map((p, i) => (i ? 'L' : 'M') + X(p.t).toFixed(1) + ' ' + Y(p[key]).toFixed(1)).join(' ');
  const svg = s('svg', { viewBox: '0 0 ' + W + ' ' + H, preserveAspectRatio: 'none', 'aria-hidden': 'true' },
    [0.25, 0.5, 0.75].map(k => s('line', { class: 'grid-l', x1: 0, x2: W, y1: H * k, y2: H * k })),
    o.line !== undefined ? s('line', { class: 'limit', x1: 0, x2: W, y1: Y(o.line), y2: Y(o.line) }) : null,
    segs.filter(g => g.length > 1).map(g => s('path', { class: 'area', d: d(g) + ' L' + X(g[g.length - 1].t).toFixed(1) + ' ' + H + ' L' + X(g[0].t).toFixed(1) + ' ' + H + ' Z', style: 'fill:' + o.color })),
    segs.map(g => g.length > 1 ? s('path', { class: 'stroke', d: d(g), pathLength: 1, style: 'stroke:' + o.color }) : s('circle', { class: 'stroke', cx: X(g[0].t), cy: Y(g[0][key]), r: 2, style: 'stroke:' + o.color })));
  const cursor = h('i', { class: 'cursor', hidden: true }), dot = h('i', { class: 'dot-c', hidden: true, style: 'background:' + o.color });
  const plot = h('div', { class: 'plot', role: 'img', 'aria-label': o.label + ' : dernière valeur ' + (last === null ? 'inconnue' : o.fmt(last)) }, svg, cursor, dot,
    h('span', { class: 'ylab top' }, o.fmt(Math.round(hi * 10) / 10)), h('span', { class: 'ylab bottom' }, o.fmt(Math.round(lo * 10) / 10)));
  const move = ev => {
    const r = plot.getBoundingClientRect(), t = from + (ev.clientX - r.left) / r.width * (to - from);
    let best = pts[0];
    pts.forEach(p => { if (Math.abs(p.t - t) < Math.abs(best.t - t)) best = p; });
    const px = X(best.t) / W * 100, py = Y(best[key]) / H * 100;
    cursor.hidden = dot.hidden = false;
    cursor.style.left = dot.style.left = px + '%';
    dot.style.top = py + '%';
    $tip.replaceChildren(h('b', {}, o.fmt(best[key])), h('div', {}, o.label + ' · ' + (to - from > 86400000 ? date(best.t) : clock(best.t))),
      o.extra ? h('div', { class: 'id' }, o.extra(best)) : null);
    $tip.hidden = false;
    moveTip(ev);
  };
  plot.addEventListener('pointermove', move);
  plot.addEventListener('pointerdown', move);
  plot.addEventListener('pointerleave', () => { cursor.hidden = dot.hidden = true; hideTip(); });
  const span2 = to - from;
  const tick = t => span2 > 86400000 * 1.5 ? new Date(t).toLocaleDateString('fr-FR', { day: 'numeric', month: 'short' }) : clock(t);
  box.append(plot, h('div', { class: 'xlab' }, [0, 0.5, 1].map(k => h('span', {}, tick(from + k * span2)))));
  return h('div', { class: 'chart-card' }, headRow, box);
}

/** Petite courbe sans axes, pour les chiffres clés. */
function spark(values, color) {
  const v = values.filter(x => typeof x === 'number');
  if (v.length < 2) return null;
  const W = 120, H = 32, lo = Math.min(...v), hi = Math.max(...v, lo + 1);
  const P = v.map((x, i) => (i / (v.length - 1) * W).toFixed(1) + ' ' + (H - 2 - (x - lo) / (hi - lo) * (H - 4)).toFixed(1));
  return s('svg', { class: 'spark' + (arriving() ? ' fresh' : ''), viewBox: '0 0 ' + W + ' ' + H, preserveAspectRatio: 'none', 'aria-hidden': 'true' },
    s('path', { class: 'area', d: 'M' + P.join(' L') + ' L' + W + ' ' + H + ' L0 ' + H + ' Z', style: 'fill:' + (color || 'var(--accent)') }),
    s('path', { class: 'stroke', d: 'M' + P.join(' L'), pathLength: 1, style: 'stroke:' + (color || 'var(--accent)') }));
}

function moveTip(ev) {
  const w = $tip.offsetWidth, hh = $tip.offsetHeight;
  $tip.style.left = Math.min(window.innerWidth - w - 8, ev.clientX + 14) + 'px';
  $tip.style.top = Math.min(window.innerHeight - hh - 8, ev.clientY + 14) + 'px';
}

function hideTip() { $tip.hidden = true; }

/** Une case d'inventaire. */
function slot(it, onClick, small) {
  if (!it) return h('div', { class: 'slot' + (small ? ' small' : '') });
  const el = h('div', { class: 'slot has' + (small ? ' small' : '') + (it.special ? ' special' : ''), tabindex: onClick ? 0 : null,
    onmouseenter: ev => showTip(ev, it), onmousemove: moveTip, onmouseleave: hideTip },
    icon(it),
    it.n > 1 ? h('span', { class: 'n' }, it.n) : null,
    it.dur && it.dmg ? h('div', { class: 'bar' }, h('i', { style: 'width:' + Math.max(4, Math.round(100 * (it.dur - it.dmg) / it.dur)) + '%' })) : null);
  if (onClick) {
    el.addEventListener('click', () => { hideTip(); onClick(it); });
    el.addEventListener('keydown', ev => { if (ev.key === 'Enter') onClick(it); });
  }
  return el;
}

const SQ2 = (x, y) => 'M' + x + ' ' + (y + 1) + 'a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v4a1 1 0 0 1-1 1h-4a1 1 0 0 1-1-1z';
const ICONS = {
  accueil: [SQ2(4, 4), SQ2(14, 4), SQ2(4, 14), SQ2(14, 14)],
  joueurs: ['M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8z', 'M4.5 20a7.5 7.5 0 0 1 15 0'],
  loupe: ['M11 18a7 7 0 1 0 0-14 7 7 0 0 0 0 14z', 'M20 20l-4-4'],
  soleil: ['M12 16a4 4 0 1 0 0-8 4 4 0 0 0 0 8z', 'M12 2v2', 'M12 20v2', 'M2 12h2', 'M20 12h2', 'M4.9 4.9l1.4 1.4', 'M17.7 17.7l1.4 1.4', 'M4.9 19.1l1.4-1.4', 'M17.7 6.3l1.4-1.4'],
  lune: ['M20 14.5A8 8 0 0 1 9.5 4a8 8 0 1 0 10.5 10.5z'],
  chevron: ['M6 9l6 6 6-6'], coche: ['M5 12.5l4.5 4.5L19 7.5'], sortie: ['M10 4H6a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h4', 'M15 8l4 4-4 4', 'M19 12H9'],
  plus: ['M5 13a1 1 0 1 0 0-2 1 1 0 0 0 0 2z', 'M12 13a1 1 0 1 0 0-2 1 1 0 0 0 0 2z', 'M19 13a1 1 0 1 0 0-2 1 1 0 0 0 0 2z'],
  ajout: ['M12 5v14', 'M5 12h14'], moins: ['M5 12h14'], fleche: ['M5 12h14', 'M13 6l6 6-6 6'],
  reglages: ['M4 6h8', 'M16 6h4', 'M4 12h2', 'M10 12h10', 'M4 18h10', 'M18 18h2', 'M14 8a2 2 0 1 0 0-4 2 2 0 0 0 0 4z', 'M8 14a2 2 0 1 0 0-4 2 2 0 0 0 0 4z', 'M16 20a2 2 0 1 0 0-4 2 2 0 0 0 0 4z'],
  caisse: ['M4 10h16v9a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1z', 'M6 10V5h8v5', 'M8 7.5h4', 'M8 14h.01', 'M12 14h.01', 'M16 14h.01', 'M8 17h8'],
  boite: ['M3.5 7.5L12 3l8.5 4.5v9L12 21l-8.5-4.5z', 'M3.5 7.5L12 12l8.5-4.5', 'M12 12v9'],
  fiole: ['M9 3h6', 'M10 3v6l-5 9a2 2 0 0 0 1.7 3h10.6a2 2 0 0 0 1.7-3l-5-9V3', 'M7.5 15h9'],
  epi: ['M12 21V9', 'M12 9c-2-1-3-3-3-5.5 2 1 3 3 3 5.5z', 'M12 9c2-1 3-3 3-5.5-2 1-3 3-3 5.5z', 'M12 14c-2-1-3.5-2.5-3.5-4.5 2 .5 3.5 2.5 3.5 4.5z', 'M12 14c2-1 3.5-2.5 3.5-4.5-2 .5-3.5 2.5-3.5 4.5z', 'M12 19c-2-1-3.5-2.5-3.5-4.5 2 .5 3.5 2.5 3.5 4.5z', 'M12 19c2-1 3.5-2.5 3.5-4.5-2 .5-3.5 2.5-3.5 4.5z'],
  facture: ['M6 3h12v18l-3-2-3 2-3-2-3 2z', 'M9 8h6', 'M9 12h6', 'M9 16h3'],
  coeur: ['M12 20s-7-4.5-7-10a4 4 0 0 1 7-2.6A4 4 0 0 1 19 10c0 5.5-7 10-7 10z'],
  balance: ['M12 4v16', 'M8 20h8', 'M5 7h14', 'M5 7l-3 6a3 3 0 0 0 6 0z', 'M19 7l-3 6a3 3 0 0 0 6 0z'],
  batiment: ['M4 21V5a1 1 0 0 1 1-1h9a1 1 0 0 1 1 1v16', 'M15 10h4a1 1 0 0 1 1 1v10', 'M3 21h18', 'M8 8h3', 'M8 12h3', 'M8 16h3'],
  grille: ['M4 4h7v7H4z', 'M13 4h7v7h-7z', 'M4 13h7v7H4z', 'M16.5 13v7', 'M13 16.5h7'],
  graph: ['M4 20V4', 'M4 20h16', 'M8 16v-5', 'M12 16V8', 'M16 16v-3'],
  horloge: ['M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z', 'M12 7v5l3 2'],
  alerte: ['M12 4l9 16H3z', 'M12 10v4', 'M12 17v.01'],
  cadenas: ['M6 11h12a1 1 0 0 1 1 1v7a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1v-7a1 1 0 0 1 1-1z', 'M8 11V8a4 4 0 0 1 8 0v3'],
  oeil: ['M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12z', 'M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z'],
  journal: ['M7 4h10a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z', 'M9 9h6', 'M9 13h6', 'M9 17h3']
};
Object.assign(ICONS, {
  badge: ['M8 4h8v3H8z', 'M5 7h14a1 1 0 0 1 1 1v11a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V8a1 1 0 0 1 1-1z', 'M9.5 14a2 2 0 1 0 0-4 2 2 0 0 0 0 4z', 'M6.5 17.5a3.2 3.2 0 0 1 6 0', 'M14.5 11h3', 'M14.5 14h3'],
  camion: ['M3 7h11v9H3z', 'M14 10h4l3 3v3h-7z', 'M7 19a2 2 0 1 0 0-4 2 2 0 0 0 0 4z', 'M17 19a2 2 0 1 0 0-4 2 2 0 0 0 0 4z'],
  calendrier: ['M5 5h14a1 1 0 0 1 1 1v13a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1z', 'M4 10h16', 'M8 3v4', 'M16 3v4', 'M8 14h3', 'M8 17h6'],
  telecharger: ['M12 4v11', 'M7 11l5 5 5-5', 'M5 20h14'], copier: ['M9 9h10v11H9z', 'M5 15V4h10'],
  carburant: ['M5 20V5a1 1 0 0 1 1-1h7a1 1 0 0 1 1 1v15', 'M4 20h11', 'M7 8h5', 'M14 9l3 3v5a1.5 1.5 0 0 0 3 0V9l-3-3'],
  cle2: ['M7.5 20a4.5 4.5 0 1 0 0-9 4.5 4.5 0 0 0 0 9z', 'M10.8 12.2L20 3', 'M17 6l2.5 2.5', 'M14 9l2 2'],
  discord: ['M7 7.5c3-1.3 7-1.3 10 0', 'M6 17c3.5 1.5 8.5 1.5 12 0', 'M6 17c-1.5-3-1.8-6.5-1-9.5l2-.5', 'M18 17c1.5-3 1.8-6.5 1-9.5l-2-.5', 'M9.5 13.5h.01', 'M14.5 13.5h.01']
});
function ico(name, size) {
  return s('svg', { class: 'ico', viewBox: '0 0 24 24', width: size || 22, height: size || 22, fill: 'none', stroke: 'currentColor', 'stroke-width': 1.8, 'stroke-linecap': 'round', 'stroke-linejoin': 'round', 'aria-hidden': 'true' },
    (ICONS[name] || ICONS.plus).map(d => s('path', { d })));
}

export const money = n => (n < 0 ? '−' : '') + Math.abs(Math.round(n)).toLocaleString('fr-FR') + ' $';
export const money2 = n => Number(n).toLocaleString('fr-FR', { minimumFractionDigits: 2, maximumFractionDigits: 2 }) + ' $';
export const initials = n => String(n || '?').split(/\s+/).map(x => x[0]).join('').slice(0, 2).toUpperCase();
export const pill = (text, cls) => h('span', { class: 'pill' + (cls ? ' ' + cls : '') }, text);
export const strip = list => h('div', { class: 'strip' }, list.filter(Boolean).map(([l, v, cls]) => h('div', {}, h('b', { class: cls || null }, v), h('span', {}, l))));
export function fchips(list, current, onPick, label) {
  return h('div', { class: 'fchips', role: 'group', 'aria-label': label || 'Filtre' }, list.map(([k, l, n]) => h('button', { type: 'button', class: 'fchip' + (k === current ? ' on' : ''), 'aria-pressed': String(k === current), onclick: () => onPick(k) },
    l, n !== undefined ? h('span', { class: 'n' }, n) : null)));
}
/** Barres par jour (ou par semaine), avec une bulle au survol. */
export function dailyBars(values, fmt, color) {
  const max = Math.max(1, ...values.map(v => v.v));
  return h('div', { class: 'bars-wrap' }, h('div', { class: 'bars' + (arriving() ? ' fresh' : ''), role: 'img', 'aria-label': 'Montants' }, values.map((d, i) => {
    const col = h('div', { class: 'bar-col', style: '--d:' + (i * 22) + 'ms' }, h('div', { class: 'bar-stack', style: 'height:' + (d.v / max * 100) + '%' }, h('i', { style: 'flex:1;background:' + (d.today ? 'var(--accent)' : color || 'var(--text)') })));
    col.addEventListener('pointerenter', ev => { $tip.replaceChildren(h('b', {}, fmt(d.v)), h('div', {}, d.label || dayLabel(d.t))); $tip.hidden = false; moveTip(ev); });
    col.addEventListener('pointermove', moveTip);
    col.addEventListener('pointerleave', hideTip);
    return col;
  })), h('div', { class: 'xlab' }, h('span', {}, values.length ? (values[0].label || new Date(values[0].t).toLocaleDateString('fr-FR', { day: 'numeric', month: 'short' })) : ''), h('span', {}, values.length && values[values.length - 1].today ? 'aujourd’hui' : '')));
}
export const gauge = (label, v, icon) => h('div', { class: 'gauge' }, h('div', { class: 'g-top' }, h('span', {}, ico(icon, 14), label), h('b', {}, v + ' %')), h('span', { class: 'meter ' + (v < 25 ? 'bad' : v < 50 ? 'warn' : 'ok') }, h('i', { style: 'width:' + v + '%' })));
export function untilText(ms) {
  const s2 = Math.max(0, Math.floor(ms / 1000)), d = Math.floor(s2 / 86400), hh = Math.floor(s2 % 86400 / 3600), mm = Math.floor(s2 % 3600 / 60), ss = s2 % 60;
  return { d, hh, mm, ss, short: d ? d + ' j ' + hh + ' h' : hh ? hh + ' h ' + pad(mm) : mm + ' min' };
}
const DAY = 864e5, HOUR = 36e5;
const dayStart = ms => { const d = new Date(ms); d.setHours(0, 0, 0, 0); return d.getTime(); };
/** Prochain jour de la semaine (0 = lundi) à cette heure, au moins deux heures plus tard. */
export const nextAt = (dow, hour) => { const d = new Date(dayStart(Date.now())); for (let k = 0; k < 8; k++) { if ((d.getDay() + 6) % 7 === dow && d.getTime() + hour * HOUR > Date.now() + 2 * HOUR) break; d.setDate(d.getDate() + 1); } return d.getTime() + hour * HOUR; };
/** Fait défiler un chiffre jusqu'à sa nouvelle valeur. */
export const slug = t => String(t || '').normalize('NFD').replace(/\p{M}/gu, '').toLowerCase().replace(/œ/g, 'oe').replace(/æ/g, 'ae').replace(/ß/g, 'ss').replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '').slice(0, 40).replace(/-$/, '');

export { h, pref, theme, setTheme, themeSwitch, s, countUp, tween, liveDot, toast, modal, run, pad, date, ago, span, compact, clock, clockS, dur, dayLabel, copyText, table, chart, spark, moveTip, hideTip, ICONS, ico };
