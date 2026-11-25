// Codes radio : aide-mémoire consultable hors ligne. Toucher une ligne copie le code et son sens.
import { html, esc, copy } from './kit.js';

const CODES = [['10-1', 'Mauvaise réception'], ['10-2', 'Bonne réception'], ['10-3', 'Silence radio'], ['10-4', 'Reçu, compris'], ['10-5', 'Pause'], ['10-6', 'Occupé'], ['10-7', 'Hors service'],
  ['10-8', 'En service, disponible'], ['10-9', 'Répétez'], ['10-10', 'Négatif'], ['10-11', 'Contrôle routier'], ['10-15', 'Suspect interpellé'], ['10-20', 'Position'], ['10-22', 'Annulez'],
  ['10-23', 'Arrivé sur les lieux'], ['10-28', 'Vérification de plaque'], ['10-29', 'Vérification d’identité'], ['10-31', 'Crime en cours'], ['10-32', 'Demande de renfort'], ['10-38', 'Contrôle d’un véhicule'],
  ['10-50', 'Accident de la route'], ['10-52', 'Ambulance demandée'], ['10-70', 'Poursuite à pied'], ['10-80', 'Poursuite en véhicule'], ['10-99', 'Agent en détresse, toutes les unités'],
  ['Code 2', 'Intervention urgente, sans sirène'], ['Code 3', 'Gyrophares et sirène'], ['Code 4', 'Situation sous contrôle']];

export default {
  id: 'codes', name: 'Codes radio', icon: 'antenna-bars-5', grad: ['#ff6b6b', '#c92a3a'], bar: 'auto',
  mount(el, ctx) {
    const { glyph } = ctx;
    el.append(html(`<div class="single"><header class="ph big"><div><h1>Codes radio</h1><p class="sub">Toucher une ligne copie le code</p></div></header>
      <label class="search wide">${glyph('search', 17)}<input id="k-q" type="search" placeholder="Rechercher un code ou un sens" autocomplete="off" aria-label="Rechercher un code"></label><ul class="codes" id="k-list"></ul></div>`));
    const list = el.querySelector('#k-list');
    const paint = q => { list.innerHTML = CODES.filter(([c, m]) => !q || (c + ' ' + m).toLowerCase().includes(q)).map(([c, m], i) => `<li><button type="button" data-c="${esc(c + ' · ' + m)}" style="--i:${i}"><b>${esc(c)}</b><span>${esc(m)}</span></button></li>`).join('') || '<li class="none">Aucun résultat</li>'; };
    el.querySelector('#k-q').addEventListener('input', e => paint(e.target.value.trim().toLowerCase()));
    list.addEventListener('click', e => { const b = e.target.closest('[data-c]'); if (b) ctx.toast(copy(b.dataset.c) ? 'Copié : ' + b.dataset.c : 'Copie impossible', 'copy'); });
    paint('');
  },
};
