// Localisation : position, rue, quartier, cap, boussole, copie des coordonnées et repère GPS. Les données viennent du jeu.
import { html, copy } from './kit.js';

const CARD = ['N', 'NE', 'E', 'SE', 'S', 'SO', 'O', 'NO'];
const fix = (v, n = 2) => typeof v === 'number' ? v.toFixed(n) : '—';

export default {
  id: 'location', name: 'Localisation', icon: 'map-pin', grad: ['#4ade80', '#10975a'], bar: 'auto',
  mount(el, ctx) {
    const { glyph, bus, nui } = ctx; let pos = null;
    const ticks = Array.from({ length: 72 }, (_, i) => `<line x1="160" y1="${i % 9 ? 10 : 6}" x2="160" y2="${i % 9 ? 17 : 24}" transform="rotate(${i * 5} 160 160)" class="${i % 9 ? 't' : 'T'}"/>`).join('');
    el.append(html(`<div class="loc"><section class="compass"><div class="dial"><svg viewBox="0 0 320 320" id="l-ring" aria-hidden="true"><circle cx="160" cy="160" r="152" class="rim"/>${ticks}
        <text x="160" y="52" class="n">N</text><text x="272" y="170" class="c">E</text><text x="160" y="288" class="c">S</text><text x="48" y="170" class="c">O</text></svg><div class="needle" aria-hidden="true"></div><div class="hd"><b id="l-deg">—</b><span id="l-card"></span></div></div></section>
      <section class="info"><header class="ph big"><div><h1>Localisation</h1><p class="sub" id="l-state">En attente du signal…</p></div></header>
        <div class="cards"><div class="card"><label>Rue</label><b id="l-street">—</b><span id="l-cross"></span></div><div class="card"><label>Quartier</label><b id="l-zone">—</b></div>
          <div class="card wide"><label>Coordonnées</label><div class="xyz"><span>X <b id="l-x">—</b></span><span>Y <b id="l-y">—</b></span><span>Z <b id="l-z">—</b></span></div><button type="button" class="pill-btn" id="l-copy">${glyph('copy', 16, 2)} Copier</button></div></div>
        <form class="gps" id="l-gps" autocomplete="off"><h2>Repère sur la carte</h2><div class="gf"><label>X<input id="g-x" inputmode="decimal" placeholder="x"></label><label>Y<input id="g-y" inputmode="decimal" placeholder="y"></label>
          <button type="button" class="pill-btn" id="g-here">Ma position</button><button type="submit" class="pill-btn solid">${glyph('navigation', 16, 2)} Placer le repère</button></div></form></section></div>`));
    const $ = s => el.querySelector(s);
    function paint() {
      if (!pos) return;
      $('#l-state').textContent = 'Position en direct';
      $('#l-ring').style.transform = `rotate(${-pos.bearing}deg)`; $('#l-deg').textContent = Math.round(pos.bearing) + '°'; $('#l-card').textContent = CARD[Math.round(pos.bearing / 45) % 8];
      $('#l-street').textContent = pos.street || '—'; $('#l-cross').textContent = pos.crossing ? 'à l’angle de ' + pos.crossing : ''; $('#l-zone').textContent = pos.zone || '—';
      $('#l-x').textContent = fix(pos.x); $('#l-y').textContent = fix(pos.y); $('#l-z').textContent = fix(pos.z);
    }
    const on = e => { pos = e.detail; paint(); };
    bus.addEventListener('position', on);
    nui('locate', { on: true });
    $('#l-copy').addEventListener('click', () => { if (!pos) return ctx.toast('Pas encore de position', 'x', true); ctx.toast(copy(fix(pos.x) + ', ' + fix(pos.y) + ', ' + fix(pos.z)) ? 'Coordonnées copiées' : 'Copie impossible', 'copy'); });
    $('#g-here').addEventListener('click', () => { if (pos) { $('#g-x').value = fix(pos.x); $('#g-y').value = fix(pos.y); } });
    $('#l-gps').addEventListener('submit', e => {
      e.preventDefault();
      const x = parseFloat($('#g-x').value.replace(',', '.')), y = parseFloat($('#g-y').value.replace(',', '.'));
      if (!Number.isFinite(x) || !Number.isFinite(y)) return ctx.toast('Entre des coordonnées valides', 'x', true);
      nui('waypoint', { x, y }); ctx.toast('Repère placé sur la carte', 'navigation');
    });
    return () => { bus.removeEventListener('position', on); nui('locate', { on: false }); };
  },
};
