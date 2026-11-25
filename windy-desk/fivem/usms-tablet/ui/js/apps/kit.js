// Outils communs aux applications de la tablette.
export const html = s => { const t = document.createElement('template'); t.innerHTML = s.trim(); return t.content.firstElementChild; };
export const esc = s => String(s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
export const uid = () => Date.now().toString(36) + Math.random().toString(36).slice(2, 6);
export const pad = n => String(n).padStart(2, '0');
const DAY = 86400000;
// « Aujourd'hui 19:19 », « Hier », « 12 nov. »
export function when(ts) {
  const d = new Date(ts), now = new Date(), start = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
  if (ts >= start) return 'Aujourd’hui ' + pad(d.getHours()) + ':' + pad(d.getMinutes());
  if (ts >= start - DAY) return 'Hier';
  return d.toLocaleDateString('fr-FR', { day: 'numeric', month: 'short', year: d.getFullYear() === now.getFullYear() ? undefined : 'numeric' });
}
export const debounce = (fn, ms) => { let t; const f = (...a) => { clearTimeout(t); t = setTimeout(() => fn(...a), ms); }; f.flush = () => { clearTimeout(t); fn(); }; return f; };
// copie dans le presse-papiers (le navigateur intégré refuse parfois l'API moderne)
export function copy(text) {
  try { const t = document.createElement('textarea'); t.value = text; t.style.cssText = 'position:fixed;opacity:0'; document.body.append(t); t.select(); const ok = document.execCommand('copy'); t.remove(); return ok; } catch { return false; }
}
