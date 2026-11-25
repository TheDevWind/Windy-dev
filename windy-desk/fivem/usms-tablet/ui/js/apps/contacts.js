// Contacts : un petit répertoire (nom, numéro, note), liste à gauche et fiche à droite.
import { html, esc, uid, debounce } from './kit.js';

export default {
  id: 'contacts', name: 'Contacts', icon: 'id', grad: ['#8a93a5', '#59627a'], ink: '#fff', bar: 'auto',
  mount(el, ctx) {
    const { store, glyph } = ctx;
    let list = store.get('contacts', []), sel = null, q = '';
    el.append(html(`<div class="split"><aside class="side-pane"><header class="ph"><h1>Contacts</h1><button class="icon-btn" id="k-new" type="button" aria-label="Nouveau contact" title="Nouveau contact">${glyph('plus', 20, 2)}</button></header>
      <label class="search">${glyph('search', 17)}<input id="k-q" type="search" placeholder="Rechercher" autocomplete="off" aria-label="Rechercher un contact"></label><ul class="list" id="k-list"></ul></aside>
      <section class="main-pane"><div class="card-form" id="k-form"><div class="avatar" id="k-av"></div>
        <label>Nom<input id="k-name" maxlength="60" placeholder="Prénom Nom" autocomplete="off"></label>
        <label>Numéro<input id="k-num" maxlength="30" placeholder="555-0100" inputmode="tel" autocomplete="off"></label>
        <label>Note<textarea id="k-note" maxlength="300" rows="4" placeholder="Poste, service, remarque…"></textarea></label>
        <button type="button" class="pill-btn danger" id="k-del">Supprimer le contact</button></div>
        <div class="blank" id="k-blank">${glyph('id', 54, 1.2)}<p>Aucun contact sélectionné</p></div></section></div>`));
    const $ = s => el.querySelector(s), ul = $('#k-list');
    const save = debounce(() => store.set('contacts', list), 250);
    const ini = n => (n.name.trim().split(/\s+/).map(w => w[0]).join('').slice(0, 2) || '?').toUpperCase();
    function paintList() {
      const vis = list.filter(c => !q || (c.name + c.number).toLowerCase().includes(q)).sort((a, b) => a.name.localeCompare(b.name, 'fr'));
      ul.innerHTML = vis.map(c => `<li><button type="button" class="row${c.id === sel ? ' on' : ''}" data-id="${c.id}"><b>${esc(c.name || 'Nouveau contact')}</b><span>${esc(c.number || 'Pas de numéro')}</span></button></li>`).join('') || `<li class="none">${q ? 'Aucun résultat' : 'Aucun contact'}</li>`;
    }
    function paintForm() {
      const c = list.find(x => x.id === sel);
      $('.main-pane').classList.toggle('empty', !c);
      if (!c) return;
      $('#k-name').value = c.name; $('#k-num').value = c.number; $('#k-note').value = c.note; $('#k-av').textContent = ini(c);
    }
    const dropEmpty = () => { const c = list.find(x => x.id === sel); if (c && !c.name.trim() && !c.number.trim()) list = list.filter(x => x !== c); };
    $('#k-new').addEventListener('click', () => { dropEmpty(); const c = { id: uid(), name: '', number: '', note: '' }; list.push(c); sel = c.id; paintList(); paintForm(); $('#k-name').focus(); });
    ul.addEventListener('click', e => { const b = e.target.closest('[data-id]'); if (b) { dropEmpty(); sel = b.dataset.id; paintList(); paintForm(); } });
    $('#k-q').addEventListener('input', e => { q = e.target.value.trim().toLowerCase(); paintList(); });
    for (const [id, key] of [['#k-name', 'name'], ['#k-num', 'number'], ['#k-note', 'note']]) $(id).addEventListener('input', e => { const c = list.find(x => x.id === sel); if (!c) return; c[key] = e.target.value; if (key === 'name') $('#k-av').textContent = ini(c); paintList(); save(); });
    $('#k-del').addEventListener('click', () => { list = list.filter(x => x.id !== sel); sel = null; paintList(); paintForm(); store.set('contacts', list); ctx.toast('Contact supprimé', 'trash'); });
    paintList(); paintForm();
    return () => { dropEmpty(); store.set('contacts', list); };
  },
};
