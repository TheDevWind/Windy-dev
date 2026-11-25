// Photos : galerie des prises de vue de l'appareil photo, visionneuse, suppression. Stockées sur ce poste de jeu (IndexedDB).
import { html, when } from './kit.js';
import { photos } from '../store.js';

let viewing = false;   // la visionneuse est ouverte : Échap la ferme avant de quitter l'application

export default {
  id: 'photos', name: 'Photos', icon: 'photo', grad: ['#9aa0ad', '#636977'], ink: '#fff', bar: 'auto',
  escape() { return viewing ? (document.dispatchEvent(new CustomEvent('photos:close')), false) : true; },
  mount(el, ctx) {
    const { glyph } = ctx; let items = [], urls = [], idx = -1;
    el.append(html(`<div class="single"><header class="ph big"><div><h1>Photos</h1><p class="sub" id="p-count"></p></div></header><div class="gallery" id="p-grid"></div>
      <div class="viewer" id="p-view" hidden><img id="p-img" alt=""><div class="vbar"><button type="button" class="icon-btn" id="p-back" aria-label="Fermer">${glyph('chevron-left', 22, 2)}</button><span id="p-when"></span><span class="sp"></span>
        <button type="button" class="icon-btn" id="p-dl" aria-label="Enregistrer l’image" title="Enregistrer l’image">${glyph('download', 20)}</button><button type="button" class="icon-btn danger" id="p-del" aria-label="Supprimer la photo" title="Supprimer">${glyph('trash', 20)}</button></div>
        <button type="button" class="nav prev" id="p-prev" aria-label="Photo précédente">${glyph('chevron-left', 28, 2)}</button><button type="button" class="nav next" id="p-next" aria-label="Photo suivante">${glyph('chevron-right', 28, 2)}</button></div></div>`));
    const $ = s => el.querySelector(s);
    async function load() {
      urls.forEach(URL.revokeObjectURL); items = await photos.all(); urls = items.map(p => URL.createObjectURL(p.blob));
      $('#p-count').textContent = items.length ? items.length + (items.length > 1 ? ' photos' : ' photo') : 'Aucune photo';
      $('#p-grid').innerHTML = items.length ? items.map((p, i) => `<button type="button" class="th" data-i="${i}" style="--i:${Math.min(i, 14)}" aria-label="Photo du ${when(p.at)}"><img src="${urls[i]}" alt=""></button>`).join('')
        : `<div class="blank in">${glyph('photo-off', 54, 1.2)}<p>Aucune photo</p><span>Ouvre l’appareil photo pour en prendre.</span></div>`;
    }
    function view(i) {
      if (i < 0 || i >= items.length) return;
      idx = i; viewing = true; $('#p-view').hidden = false; $('#p-img').src = urls[i]; $('#p-when').textContent = when(items[i].at);
      $('#p-prev').hidden = i === 0; $('#p-next').hidden = i === items.length - 1;
    }
    const close = () => { viewing = false; $('#p-view').hidden = true; };
    $('#p-grid').addEventListener('click', e => { const b = e.target.closest('[data-i]'); if (b) view(+b.dataset.i); });
    $('#p-back').addEventListener('click', close);
    $('#p-prev').addEventListener('click', () => view(idx - 1)); $('#p-next').addEventListener('click', () => view(idx + 1));
    $('#p-del').addEventListener('click', async () => { const p = items[idx]; close(); await photos.remove(p.id); await load(); ctx.toast('Photo supprimée', 'trash'); });
    $('#p-dl').addEventListener('click', () => { const a = document.createElement('a'); a.href = urls[idx]; a.download = 'photo-' + items[idx].at + '.jpg'; document.body.append(a); a.click(); a.remove(); });
    const onKey = e => { if (!viewing) return; if (e.key === 'ArrowLeft') view(idx - 1); else if (e.key === 'ArrowRight') view(idx + 1); };
    document.addEventListener('keydown', onKey); document.addEventListener('photos:close', close);
    load();
    return () => { viewing = false; document.removeEventListener('keydown', onKey); document.removeEventListener('photos:close', close); urls.forEach(URL.revokeObjectURL); };
  },
};
