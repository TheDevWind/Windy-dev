// Calculatrice : boutons ronds, opérateur en attente mis en évidence, clavier, historique des opérations.
import { html, esc } from './kit.js';
import { createCalc, pretty } from '../calc-core.js';

const KEYS = [['C', 'fn'], ['±', 'fn'], ['%', 'fn'], ['÷', 'op'], ['7'], ['8'], ['9'], ['×', 'op'], ['4'], ['5'], ['6'], ['−', 'op'], ['1'], ['2'], ['3'], ['+', 'op'], ['0', 'zero'], ['.', ''], ['=', 'op']];
const LABEL = { '.': ',', C: 'AC' };

export default {
  id: 'calculator', name: 'Calculatrice', icon: 'calculator', grad: ['#2d313d', '#171a22'], ink: '#d6b469', bar: 'light', bg: '#101216',
  mount(el, ctx) {
    const { store, glyph } = ctx, calc = createCalc();
    let hist = store.get('calc.history', []);
    el.append(html(`<div class="calc"><div class="calc-main"><div class="c-disp" id="c-disp" role="status" aria-live="polite">0</div>
      <div class="c-keys">${KEYS.map(([k, c]) => `<button type="button" class="ck ${c || ''}" data-k="${k}" aria-label="${k === 'C' ? 'Effacer' : k === '±' ? 'Changer le signe' : k === '.' ? 'Virgule' : k}">${LABEL[k] || k}</button>`).join('')}</div></div>
      <aside class="c-hist"><header><h2>Historique</h2><button type="button" class="icon-btn" id="c-clr" aria-label="Effacer l’historique" title="Effacer l’historique">${glyph('trash', 18)}</button></header><ul id="c-list"></ul></aside></div>`));
    const disp = el.querySelector('#c-disp'), list = el.querySelector('#c-list');
    const keyEl = k => el.querySelector(`[data-k="${CSS.escape(k)}"]`);
    function paint() {
      const st = calc.state(), txt = pretty(st.display);
      disp.textContent = txt; disp.style.fontSize = txt.length > 11 ? '56px' : txt.length > 8 ? '74px' : '96px';
      keyEl('C').textContent = st.clearLabel;
      el.querySelectorAll('.ck.op').forEach(b => b.classList.toggle('active', b.dataset.k === st.op));
      list.innerHTML = hist.length ? hist.map(h => `<li><span>${esc(h.expr)}</span><b>${esc(h.res)}</b></li>`).join('') : '<li class="none">Aucune opération</li>';
    }
    function press(k) {
      if (/^[0-9.]$/.test(k)) calc.digit(k);
      else if ('+−×÷'.includes(k)) calc.operator(k);
      else if (k === '=') {
        const p = calc.peek(); calc.equals();
        if (p.op) { const res = pretty(calc.state().display); hist = [{ expr: pretty(String(p.acc)) + ' ' + p.op + ' ' + pretty(p.b), res }, ...hist].slice(0, 30); store.set('calc.history', hist); }
      } else if (k === '%') calc.percent(); else if (k === '±') calc.negate(); else if (k === 'C') calc.clear();
      paint();
    }
    el.addEventListener('click', e => { const b = e.target.closest('.ck'); if (b) press(b.dataset.k); });
    el.querySelector('#c-clr').addEventListener('click', () => { hist = []; store.set('calc.history', hist); paint(); });
    list.addEventListener('click', e => { const li = e.target.closest('li:not(.none)'); if (li) { calc.clear(); calc.clear(); [...li.querySelector('b').textContent.replace(/ /g, '').replace('−', '-').replace(',', '.')].forEach(c => c === '-' ? calc.negate() : calc.digit(c)); paint(); } });
    // clavier : chiffres, opérateurs, Entrée, Retour arrière
    const MAP = { '*': '×', '/': '÷', '-': '−', ',': '.', Enter: '=', '=': '=', Delete: 'C', c: 'C', C: 'C' };
    const onKey = e => {
      if (e.ctrlKey || e.metaKey || e.altKey) return;
      const k = MAP[e.key] || e.key;
      if (e.key === 'Backspace') { calc.backspace(); paint(); flash('C'); e.preventDefault(); return; }
      if (/^[0-9.]$/.test(k) || '+−×÷=C%'.includes(k) && k.length === 1) { press(k); flash(k); e.preventDefault(); }
    };
    function flash(k) { const b = keyEl(k); if (!b) return; b.classList.add('hit'); setTimeout(() => b.classList.remove('hit'), 140); }
    document.addEventListener('keydown', onKey);
    paint();
    return () => document.removeEventListener('keydown', onKey);
  },
};
