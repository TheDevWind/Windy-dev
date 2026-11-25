// Cœur de la calculatrice : calcul immédiat « à la iOS » (chaque opérateur résout le précédent). Aucun DOM, testé dans tests/tablet.test.mjs.
const MAX_DIGITS = 12;
const fmt = n => {
  if (!Number.isFinite(n)) return 'Erreur';
  let s = String(Number(n.toPrecision(10)));
  if (s.includes('e')) s = Number(n).toExponential(5).replace(/\.?0+e/, 'e');
  return s === '-0' ? '0' : s;
};
const apply = (a, op, b) => op === '+' ? a + b : op === '−' ? a - b : op === '×' ? a * b : op === '÷' ? (b === 0 ? NaN : a / b) : b;

export function createCalc() {
  let display = '0', acc = null, op = null, fresh = true, last = null, error = false;
  const num = () => Number(display);
  const show = n => { display = fmt(n); error = display === 'Erreur'; };
  const reset = () => { display = '0'; acc = null; op = null; fresh = true; last = null; error = false; };
  return {
    digit(d) {
      if (error) reset();
      if (fresh) { display = d === '.' ? '0.' : d; fresh = false; return; }
      if (d === '.' && display.includes('.')) return;
      if (display.replace(/[-.]/g, '').length >= MAX_DIGITS) return;
      display = display === '0' && d !== '.' ? d : display + d;
    },
    operator(o) {
      if (error) return;
      if (op && !fresh) { show(apply(acc, op, num())); if (error) { acc = null; op = null; return; } }
      acc = num(); op = o; fresh = true; last = null;
    },
    equals() {
      if (error) return;
      if (op) { const b = num(); last = { op, b }; show(apply(acc, op, b)); acc = null; op = null; fresh = true; }
      else if (last) { show(apply(num(), last.op, last.b)); fresh = true; }
    },
    percent() { if (error) return; show(op === '+' || op === '−' ? acc * num() / 100 : num() / 100); fresh = false; },
    negate() { if (error) return; display = display.startsWith('-') ? display.slice(1) : display === '0' ? display : '-' + display; },
    backspace() { if (error || fresh) return; display = display.length > 1 && !(display.length === 2 && display.startsWith('-')) ? display.slice(0, -1) : '0'; },
    clear() { if (display !== '0' && !fresh && !error) { display = '0'; fresh = true; } else reset(); },
    // opération en cours (avant « = »), pour l'historique : { acc, op, b }
    peek: () => ({ acc, op, b: display }),
    // état à afficher : valeur, opérateur en attente (mis en évidence) et libellé du bouton d'effacement
    state: () => ({ display, op: op && fresh ? op : null, clearLabel: display !== '0' && !fresh && !error ? 'C' : 'AC' }),
  };
}

// 1234567.5 → « 1 234 567,5 » (espace fine insécable, virgule décimale)
export function pretty(s) {
  if (s === 'Erreur' || s.includes('e')) return s.replace('.', ',');
  const neg = s.startsWith('-'), [i, f] = s.replace('-', '').split('.');
  return (neg ? '−' : '') + i.replace(/\B(?=(\d{3})+(?!\d))/g, ' ') + (f !== undefined ? ',' + f : '');
}
