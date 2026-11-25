// Tablette du U.S. Marshals Service : écran d'accueil, fenêtres d'applications et échanges avec le jeu.
import { glyph } from './icons.js';
import { store } from './store.js';
import { APPS, GRID, DOCK } from './apps/index.js';

const RES = typeof GetParentResourceName === 'function' ? GetParentResourceName() : 'usms-tablet';
const nui = (name, data = {}) => fetch('https://' + RES + '/' + name, { method: 'POST', headers: { 'Content-Type': 'application/json; charset=UTF-8' }, body: JSON.stringify(data) }).then(r => r.json()).catch(() => null);
const WALLPAPERS = { marine: 'dark', graphite: 'dark', ardoise: 'dark', sable: 'light' };
const WEATHER = { EXTRASUNNY: ['Grand soleil', 'sun'], CLEAR: ['Dégagé', 'sun'], CLOUDS: ['Nuageux', 'cloud'], OVERCAST: ['Couvert', 'cloud'], RAIN: ['Pluie', 'cloud-rain'], CLEARING: ['Éclaircies', 'cloud'],
  THUNDER: ['Orage', 'cloud-storm'], SMOG: ['Smog', 'mist'], FOGGY: ['Brouillard', 'mist'], SNOW: ['Neige', 'cloud-snow'], BLIZZARD: ['Tempête de neige', 'cloud-snow'], SNOWLIGHT: ['Neige légère', 'cloud-snow'],
  XMAS: ['Neige', 'cloud-snow'], NEUTRAL: ['Variable', 'cloud'] };
const pad = n => String(n).padStart(2, '0');
const cap = t => t.charAt(0).toUpperCase() + t.slice(1);

const root = document.getElementById('root'), viewfinder = document.getElementById('viewfinder');
const bus = new EventTarget();
const settings = { wallpaper: 'marine', appearance: 'sombre', brightness: 100, ...store.get('settings', {}) };
const world = { hour: null, minute: null, weather: null };
let url = null, debug = false, current = null, scale = 1, screenEl, homeEl, dockEl, windowsEl, toastsEl, dimEl;
const windows = new Map();   // applications gardées en mémoire (la console reste chargée entre deux ouvertures)

// ---------------------------------------------------------------- outils
const $ = (sel, r = document) => r.querySelector(sel);
const h = (html) => { const t = document.createElement('template'); t.innerHTML = html.trim(); return t.content.firstElementChild; };
const calm = () => matchMedia('(prefers-reduced-motion: reduce)').matches;
function toast(text, icon = 'circle-check', err = false) {
  const t = h(`<div class="toast${err ? ' err' : ''}" role="status">${glyph(err ? 'x' : icon, 18, 2)}<span></span></div>`);
  $('span', t).textContent = text; toastsEl.append(t);
  requestAnimationFrame(() => t.classList.add('on'));
  setTimeout(() => { t.classList.remove('on'); setTimeout(() => t.remove(), 400); }, 2600);
}
const requestClose = () => nui('close').then(r => { if (r === null) hide(); });
const ctx = { nui, bus, settings, world, store, toast, glyph, close: requestClose, home: () => closeApp(), open: id => openApp(id), weather: w => WEATHER[w] || null,
  note: text => { const n = Object.assign(document.createElement('div'), { className: 'vf-note', textContent: text }); viewfinder.append(n); setTimeout(() => n.remove(), 1800); },
  theme: () => (screenEl ? screenEl.dataset.theme : 'dark'),
  get url() { return url; }, get debug() { return debug; }, applySettings, scale: () => scale, setBar: b => { screenEl.dataset.bar = b; } };

// ---------------------------------------------------------------- construction
function iconHtml(id, cls) {
  const a = APPS[id];
  return `<button class="appicon" type="button" data-app="${id}" aria-label="${a.name}"><span class="ico" style="background:linear-gradient(165deg,${a.grad[0]},${a.grad[1]});color:${a.ink || '#fff'}">${a.image ? `<img src="${a.image}" alt="">` : glyph(a.icon, 34, 1.6)}</span><span class="lbl">${a.name}</span></button>`;
}
function build() {
  root.replaceChildren(h(`<div class="stage"><div class="device"><i class="cam"></i>
    <div class="screen" data-theme="dark" data-tone="dark">
      <div class="wallpaper"></div>
      <header class="status"><span id="sb-time"></span><span id="sb-date" class="dt"></span><span class="sp"></span>
        ${glyph('wifi', 16)}${glyph('battery-4', 20)}
        <button class="pwr" type="button" aria-label="Ranger la tablette" title="Ranger la tablette">${glyph('power', 16)}</button></header>
      <div class="home">
        <div class="widgets">
          <div class="widget w-clock rise" style="--i:0"><div class="wl">Heure locale</div><div class="big" id="wc-time"></div><div class="date" id="wc-date"></div></div>
          <div class="widget w-world rise" style="--i:1"><div class="wl">Los Santos</div><div class="row"><span class="wi" id="ww-icon"></span><div><div class="cond" id="ww-cond">—</div><div class="sub" id="ww-sub">Heure du jeu : —</div></div></div></div>
          <button class="widget w-notes rise" style="--i:2" type="button" data-app="notes"><div class="wl">Notes</div><div class="nt" id="wn-title"></div><div class="nx" id="wn-text"></div></button>
        </div>
        <div class="grid">${GRID.map((id, i) => `<div class="rise" style="--i:${i + 3}">${iconHtml(id)}</div>`).join('')}</div>
      </div>
      <nav class="dock rise" style="--i:9" aria-label="Applications favorites">${DOCK.map(iconHtml).join('')}</nav>
      <div class="windows"></div>
      <button class="homebar" type="button" aria-label="Retour à l’accueil"></button>
      <div class="dim"></div><div class="toasts" aria-live="polite"></div>
    </div></div></div>`));
  screenEl = $('.screen'); homeEl = $('.home'); dockEl = $('.dock'); windowsEl = $('.windows'); toastsEl = $('.toasts'); dimEl = $('.dim');
  screenEl.addEventListener('click', ev => { const b = ev.target.closest('[data-app]'); if (b && !current) openApp(b.dataset.app, $('.ico', b) || b); });
  $('.pwr').addEventListener('click', requestClose);
  const hb = $('.homebar'); let y0 = null;
  hb.addEventListener('click', () => { if (current) closeApp(); });
  hb.addEventListener('pointerdown', ev => { y0 = ev.clientY; hb.setPointerCapture(ev.pointerId); });
  hb.addEventListener('pointerup', ev => { if (y0 !== null && y0 - ev.clientY > 30 / scale && current) closeApp(); y0 = null; });
  applySettings(); tick(); paintWorld(); paintNotes();
}

// ---------------------------------------------------------------- réglages, heure, widgets
function applySettings() {
  if (!screenEl) return;
  store.set('settings', settings);
  $('.wallpaper').dataset.wp = settings.wallpaper in WALLPAPERS ? settings.wallpaper : 'marine';
  screenEl.dataset.tone = WALLPAPERS[settings.wallpaper] || 'dark';
  const night = world.hour === null ? true : (world.hour >= 20 || world.hour < 6);
  screenEl.dataset.theme = settings.appearance === 'clair' ? 'light' : settings.appearance === 'auto' ? (night ? 'dark' : 'light') : 'dark';
  dimEl.style.opacity = String((100 - Math.min(100, Math.max(20, settings.brightness))) / 100 * 0.7);
  bus.dispatchEvent(new CustomEvent('settings'));
}
function tick() {
  if (!screenEl) return;
  const d = new Date();
  $('#sb-time').textContent = pad(d.getHours()) + ':' + pad(d.getMinutes());
  $('#sb-date').textContent = d.toLocaleDateString('fr-FR', { weekday: 'short', day: 'numeric', month: 'short' });
  $('#wc-time').textContent = pad(d.getHours()) + ':' + pad(d.getMinutes());
  $('#wc-date').textContent = cap(d.toLocaleDateString('fr-FR', { weekday: 'long', day: 'numeric', month: 'long' }));
}
function paintWorld() {
  if (!screenEl) return;
  const w = WEATHER[world.weather];
  $('#ww-icon').innerHTML = glyph(w ? w[1] : 'cloud', 46, 1.5);
  $('#ww-cond').textContent = w ? w[0] : '—';
  $('#ww-sub').textContent = 'Heure du jeu : ' + (world.hour === null ? '—' : pad(world.hour) + ':' + pad(world.minute));
  applySettings();
}
function paintNotes() {
  if (!screenEl) return;
  const notes = store.get('notes', []).filter(n => (n.text || '').trim()).sort((a, b) => b.updated - a.updated);
  const n = notes[0], lines = n ? n.text.trim().split('\n') : [];
  $('#wn-title').textContent = n ? lines[0] : 'Aucune note';
  $('#wn-text').textContent = n ? lines.slice(1).join(' ').trim() || ' ' : 'Touche pour écrire ta première note.';
}
bus.addEventListener('notes', paintNotes);
setInterval(tick, 1000);

// ---------------------------------------------------------------- ouverture et fermeture des applications
const EASE = 'cubic-bezier(.22,1,.36,1)';
function flipFrames(fromEl, opening) {
  const s = screenEl.getBoundingClientRect(), r = (fromEl && fromEl.isConnected ? fromEl : screenEl).getBoundingClientRect();
  const sx = Math.max(.02, r.width / s.width), sy = Math.max(.02, r.height / s.height);
  const dx = (r.left + r.width / 2 - (s.left + s.width / 2)) / scale, dy = (r.top + r.height / 2 - (s.top + s.height / 2)) / scale;
  const small = { transform: `translate(${dx}px,${dy}px) scale(${sx},${sy})`, borderRadius: `${18 / sx}px` }, full = { transform: 'none', borderRadius: '0px' };
  return opening ? [{ ...small, opacity: .15 }, { opacity: 1, offset: .3 }, { ...full, opacity: 1 }] : [{ ...full, opacity: 1 }, { opacity: 1, offset: .55 }, { ...small, opacity: 0 }];
}
async function openApp(id, fromEl) {
  const def = APPS[id];
  if (!def || current) return;
  let win = windows.get(id), cleanup = null;
  if (!win) {
    win = h('<section class="win"><div class="win-body"></div></section>'); win.dataset.app = id; win.style.background = def.bg || '';
    if (def.persistent) windows.set(id, win);
    windowsEl.append(win);
    cleanup = def.mount($('.win-body', win), ctx) || null;
  } else { windowsEl.append(win); win.hidden = false; def.show && def.show(ctx); }
  current = { id, def, win, cleanup, from: fromEl || null };
  screenEl.dataset.bar = def.bar || 'auto'; screenEl.classList.add('in-app');   // l'accueil s'estompe (transition CSS)
  win.getAnimations().forEach(a => a.cancel());
  win.animate(flipFrames(fromEl, true), { duration: calm() ? 1 : 460, easing: EASE, fill: 'both' });
  setTimeout(() => win.classList.add('shown'), calm() ? 0 : 80);
}
function closeApp(instant = false) {
  if (!current) return;
  const { id, def, win, cleanup, from } = current; current = null;
  const target = from && from.isConnected ? from : $(`.grid [data-app="${id}"] .ico, .dock [data-app="${id}"] .ico`);
  let finished = false;
  const done = () => {   // appelée une seule fois, à la fin de l'animation ou, au pire, par la minuterie de secours
    if (finished) return; finished = true;
    win.classList.remove('shown');
    if (def.persistent) { win.hidden = true; def.hide && def.hide(ctx); } else { cleanup && cleanup(); win.remove(); }
  };
  const t = instant || calm() ? 1 : 340;
  screenEl.classList.remove('in-app'); delete screenEl.dataset.bar;   // l'accueil revient tout de suite : rien ne peut rester grisé
  win.getAnimations().forEach(a => a.cancel());
  win.animate(flipFrames(target, false), { duration: t, easing: EASE, fill: 'forwards' }).onfinish = done;
  setTimeout(done, t + 80);
}

// ---------------------------------------------------------------- affichage de la tablette
function fit() { scale = Math.min(innerWidth * .95 / 1240, innerHeight * .93 / 800, 1.4); root.style.setProperty('--s', String(scale)); }
addEventListener('resize', fit); fit();
function show(msg) {
  url = msg.url || url; debug = !!msg.debug;
  if (!screenEl) build();
  root.hidden = false; root.classList.remove('photo'); viewfinder.hidden = true;
  const st = $('.stage'); st.classList.remove('leave'); st.classList.add('enter');
  if (current) closeApp(true);
  bus.dispatchEvent(new CustomEvent('open'));
}
function hide() {
  if (root.hidden) return;
  const st = $('.stage'); st && st.classList.replace('enter', 'leave');
  setTimeout(() => { root.hidden = true; if (current) closeApp(true); bus.dispatchEvent(new CustomEvent('close')); }, calm() ? 0 : 220);
}

window.addEventListener('message', ev => {
  const d = ev.data || {};
  if (ctx.frameWindow && ev.source === ctx.frameWindow) { bus.dispatchEvent(new CustomEvent('frame', { detail: { data: d, origin: ev.origin } })); return; }   // la console ne peut qu'envoyer ses propres demandes
  switch (d.action) {
    case 'open': show(d); break;
    case 'close': hide(); break;
    case 'world': world.hour = d.hour; world.minute = d.minute; world.weather = d.weather; paintWorld(); bus.dispatchEvent(new CustomEvent('world')); break;
    case 'position': bus.dispatchEvent(new CustomEvent('position', { detail: d })); break;
    case 'camera':
      if (d.state === 'hide') viewfinder.hidden = true;   // le jeu masque le viseur le temps de la capture
      else if (d.state === 'show') viewfinder.hidden = false;
      else { root.classList.toggle('photo', d.state === 'on'); viewfinder.hidden = d.state !== 'on'; bus.dispatchEvent(new CustomEvent('camera', { detail: d })); }
      break;
    case 'shutter': { const f = $('.vf-flash', viewfinder); f.classList.remove('go'); void f.offsetWidth; f.classList.add('go'); break; }
    case 'photo': bus.dispatchEvent(new CustomEvent('photo', { detail: d })); break;
  }
});
document.addEventListener('keydown', ev => {
  if (root.hidden || root.classList.contains('photo') || ev.key !== 'Escape') return;
  if (current && current.def.escape && current.def.escape(ctx) === false) return;
  current ? closeApp() : requestClose();
});
