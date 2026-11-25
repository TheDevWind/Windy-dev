// Rappels : liste de choses à faire, avec cases animées. Stockés sur ce poste de jeu.
import { html, esc, uid } from './kit.js';

export default {
  id: 'reminders', name: 'Rappels', icon: 'checklist', grad: ['#5ac8fa', '#1f7be0'], bar: 'auto',
  mount(el, ctx) {
    const { store, glyph } = ctx;
    let items = store.get('reminders', []);
    el.append(html(`<div class="single"><header class="ph big"><div><h1>Rappels</h1><p class="sub" id="r-count"></p></div><button type="button" class="pill-btn" id="r-clear">Effacer les terminés</button></header>
      <form class="add" id="r-form" autocomplete="off"><span class="plus">${glyph('plus', 18, 2)}</span><input id="r-in" placeholder="Nouveau rappel" aria-label="Nouveau rappel" maxlength="120"></form>
      <ul class="rem" id="r-list"></ul></div>`));
    const $ = s => el.querySelector(s), list = $('#r-list');
    const save = () => store.set('reminders', items);
    function paint(addedId) {
      const todo = items.filter(i => !i.done).length;
      $('#r-count').textContent = items.length ? (todo ? todo + ' à faire' : 'Tout est fait') : 'Rien à faire pour le moment';
      $('#r-clear').hidden = !items.some(i => i.done);
      list.innerHTML = [...items.filter(i => !i.done), ...items.filter(i => i.done)].map(i => `<li class="${i.done ? 'done' : ''}${i.id === addedId ? ' pop' : ''}" data-id="${i.id}"><button type="button" class="chk" role="checkbox" aria-checked="${i.done}" aria-label="${esc(i.text)}">${glyph('check', 15, 3)}</button><span>${esc(i.text)}</span><button type="button" class="x" aria-label="Supprimer" title="Supprimer">${glyph('x', 16, 2)}</button></li>`).join('')
        || `<li class="none">${glyph('circle-check', 46, 1.2)}<p>Aucun rappel</p></li>`;
    }
    $('#r-form').addEventListener('submit', e => { e.preventDefault(); const v = $('#r-in').value.trim(); if (!v) return; const i = { id: uid(), text: v, done: false }; items.unshift(i); $('#r-in').value = ''; save(); paint(i.id); });
    list.addEventListener('click', e => {
      const li = e.target.closest('li[data-id]'); if (!li) return;
      const it = items.find(i => i.id === li.dataset.id);
      if (e.target.closest('.x')) { li.classList.add('out'); setTimeout(() => { items = items.filter(i => i !== it); save(); paint(); }, 220); }
      else if (e.target.closest('.chk, span')) { it.done = !it.done; save(); li.classList.toggle('done', it.done); li.querySelector('.chk').setAttribute('aria-checked', it.done); setTimeout(paint, 380); }
    });
    $('#r-clear').addEventListener('click', () => { items = items.filter(i => !i.done); save(); paint(); });
    paint();
  },
};
