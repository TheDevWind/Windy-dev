// Notes : liste à gauche, éditeur à droite, enregistrement automatique. Stockées sur ce poste de jeu.
import { html, esc, uid, when, debounce } from './kit.js';

export default {
  id: 'notes', name: 'Notes', icon: 'notes', grad: ['#ffd85c', '#f0a500'], bar: 'auto',
  mount(el, ctx) {
    const { store, glyph, bus } = ctx;
    let notes = store.get('notes', []), sel = store.get('notes.sel', null), q = '';
    el.append(html(`<div class="split">
      <aside class="side-pane"><header class="ph"><h1>Notes</h1><button class="icon-btn" id="n-new" type="button" aria-label="Nouvelle note" title="Nouvelle note">${glyph('pencil', 20)}</button></header>
        <label class="search">${glyph('search', 17)}<input id="n-q" type="search" placeholder="Rechercher" autocomplete="off" aria-label="Rechercher dans les notes"></label>
        <ul class="list" id="n-list"></ul></aside>
      <section class="main-pane"><header class="mh"><span class="meta" id="n-meta"></span><span class="sp"></span>
        <button class="icon-btn" id="n-pin" type="button" aria-label="Épingler" title="Épingler">${glyph('pin', 19)}</button>
        <button class="icon-btn danger" id="n-del" type="button" aria-label="Supprimer la note" title="Supprimer">${glyph('trash', 19)}</button></header>
        <textarea id="n-text" placeholder="Écris ta note…" aria-label="Contenu de la note" spellcheck="false"></textarea>
        <div class="blank" id="n-blank">${glyph('notes', 54, 1.2)}<p>Aucune note sélectionnée</p></div></section></div>`));
    const $ = s => el.querySelector(s), list = $('#n-list'), ta = $('#n-text');
    const cur = () => notes.find(n => n.id === sel);
    const title = n => (n.text.trim().split('\n')[0] || 'Nouvelle note').slice(0, 60);
    const preview = n => n.text.trim().split('\n').slice(1).join(' ').trim().slice(0, 80) || 'Pas d’autre texte';
    const save = debounce(() => { store.set('notes', notes); store.set('notes.sel', sel); bus.dispatchEvent(new CustomEvent('notes')); }, 250);
    function paintList(animate) {
      const vis = notes.filter(n => !q || n.text.toLowerCase().includes(q)).sort((a, b) => (b.pinned | 0) - (a.pinned | 0) || b.updated - a.updated);
      list.innerHTML = vis.map((n, i) => `<li><button type="button" class="row${n.id === sel ? ' on' : ''}${animate ? ' pop' : ''}" data-id="${n.id}" style="--i:${i}"><b>${n.pinned ? glyph('pin', 13, 2) : ''}${esc(title(n))}</b><span><i>${esc(when(n.updated))}</i> ${esc(preview(n))}</span></button></li>`).join('')
        || `<li class="none">${q ? 'Aucun résultat' : 'Aucune note. Touche le crayon pour commencer.'}</li>`;
    }
    function paintEditor(focus) {
      const n = cur();
      $('.main-pane').classList.toggle('empty', !n);
      if (!n) { ta.value = ''; return; }
      ta.value = n.text; $('#n-meta').textContent = 'Modifiée ' + when(n.updated).toLowerCase();
      $('#n-pin').classList.toggle('on', !!n.pinned);
      if (focus) ta.focus();
    }
    function dropEmpty() { const n = cur(); if (n && !n.text.trim()) notes = notes.filter(x => x !== n); }
    function select(id, focus) { dropEmpty(); sel = id; paintList(); paintEditor(focus); save(); }
    $('#n-new').addEventListener('click', () => { dropEmpty(); const n = { id: uid(), text: '', updated: Date.now(), pinned: false }; notes.push(n); sel = n.id; paintList(true); paintEditor(true); });
    list.addEventListener('click', e => { const b = e.target.closest('[data-id]'); if (b) select(b.dataset.id, true); });
    $('#n-q').addEventListener('input', e => { q = e.target.value.trim().toLowerCase(); paintList(); });
    ta.addEventListener('input', () => { const n = cur(); if (!n) return; n.text = ta.value; n.updated = Date.now(); $('#n-meta').textContent = 'Modifiée à l’instant'; paintList(); save(); });
    $('#n-pin').addEventListener('click', () => { const n = cur(); if (!n) return; n.pinned = !n.pinned; $('#n-pin').classList.toggle('on', n.pinned); paintList(); save(); });
    $('#n-del').addEventListener('click', () => { const n = cur(); if (!n) return; notes = notes.filter(x => x !== n); sel = null; paintList(); paintEditor(); save.flush(); ctx.toast('Note supprimée', 'trash'); });
    if (!cur()) sel = (notes.slice().sort((a, b) => b.updated - a.updated)[0] || {}).id || null;
    paintList(true); paintEditor();
    return () => { dropEmpty(); store.set('notes', notes); store.set('notes.sel', sel); bus.dispatchEvent(new CustomEvent('notes')); };
  },
};
