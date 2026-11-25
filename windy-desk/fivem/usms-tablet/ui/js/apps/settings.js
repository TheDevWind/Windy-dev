// Réglages : fond d'écran, apparence, luminosité, informations sur la tablette.
import { html } from './kit.js';
import { photos } from '../store.js';

const WP = [['marine', 'Marine'], ['graphite', 'Graphite'], ['aurore', 'Aurore'], ['dune', 'Dune']];

export default {
  id: 'settings', name: 'Réglages', icon: 'settings', grad: ['#a3a8b5', '#5a606e'], bar: 'auto',
  mount(el, ctx) {
    const { settings, glyph, store } = ctx;
    el.append(html(`<div class="single narrow"><header class="ph big"><div><h1>Réglages</h1><p class="sub">Tablette du U.S. Marshals Service</p></div></header>
      <section class="grp"><h2>Fond d’écran</h2><div class="wps" id="s-wp">${WP.map(([k, n]) => `<button type="button" class="wpt${settings.wallpaper === k ? ' on' : ''}" data-k="${k}" aria-label="Fond ${n}"><span class="wallpaper" data-wp="${k}"></span><i>${n}</i></button>`).join('')}</div></section>
      <section class="grp"><h2>Apparence</h2><div class="seg wide" id="s-ap" role="radiogroup">${[['sombre', 'Sombre'], ['clair', 'Clair'], ['auto', 'Automatique']].map(([k, n]) => `<button type="button" role="radio" aria-checked="${settings.appearance === k}" data-k="${k}" class="${settings.appearance === k ? 'on' : ''}">${n}</button>`).join('')}</div>
        <p class="hint">Automatique suit l’heure du jeu : clair de jour, sombre la nuit.</p></section>
      <section class="grp"><h2>Luminosité</h2><div class="slide">${glyph('brightness-half', 18)}<input id="s-br" type="range" min="20" max="100" value="${settings.brightness}" aria-label="Luminosité"><b id="s-bv">${settings.brightness} %</b></div></section>
      <section class="grp"><h2>À propos</h2><ul class="kv"><li><span>Modèle</span><b>Tablette de service</b></li><li><span>Console</span><b id="s-host">—</b></li><li><span>Notes et contacts</span><b id="s-data">—</b></li><li><span>Photos</span><b id="s-ph">—</b></li></ul>
        <button type="button" class="pill-btn danger" id="s-reset">Réinitialiser les réglages</button></section></div>`));
    const $ = s => el.querySelector(s);
    $('#s-wp').addEventListener('click', e => { const b = e.target.closest('[data-k]'); if (!b) return; settings.wallpaper = b.dataset.k; el.querySelectorAll('.wpt').forEach(x => x.classList.toggle('on', x === b)); ctx.applySettings(); });
    $('#s-ap').addEventListener('click', e => { const b = e.target.closest('[data-k]'); if (!b) return; settings.appearance = b.dataset.k; el.querySelectorAll('#s-ap button').forEach(x => { x.classList.toggle('on', x === b); x.setAttribute('aria-checked', x === b); }); ctx.applySettings(); });
    $('#s-br').addEventListener('input', e => { settings.brightness = +e.target.value; $('#s-bv').textContent = settings.brightness + ' %'; ctx.applySettings(); });
    $('#s-reset').addEventListener('click', () => { Object.assign(settings, { wallpaper: 'marine', appearance: 'sombre', brightness: 100 }); ctx.applySettings(); ctx.home(); ctx.toast('Réglages réinitialisés', 'refresh'); });
    try { $('#s-host').textContent = ctx.url ? new URL(ctx.url).host : 'non configurée'; } catch { /* adresse invalide */ }
    $('#s-data').textContent = store.get('notes', []).length + ' notes · ' + store.get('contacts', []).length + ' contacts · ' + store.get('reminders', []).length + ' rappels';
    photos.all().then(a => { $('#s-ph').textContent = a.length + (a.length > 1 ? ' photos' : ' photo'); });
  },
};
