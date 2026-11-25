// Stockage local de la tablette (propre à ce poste de jeu) : réglages et notes en localStorage, photos en IndexedDB.
const P = 'usms.';
export const store = {
  get(key, fallback) { try { const v = localStorage.getItem(P + key); return v == null ? fallback : JSON.parse(v); } catch { return fallback; } },
  set(key, value) { try { localStorage.setItem(P + key, JSON.stringify(value)); } catch { /* quota ou stockage indisponible : la valeur vaut pour cette session */ } },
};

// Photos : un enregistrement par prise de vue {id, at, blob, w, h}, rangé du plus récent au plus ancien à la lecture.
let dbp = null;
const db = () => dbp || (dbp = new Promise((ok, ko) => {
  const r = indexedDB.open('usms-tablet', 1);
  r.onupgradeneeded = () => r.result.createObjectStore('photos', { keyPath: 'id', autoIncrement: true });
  r.onsuccess = () => ok(r.result); r.onerror = () => ko(r.error);
}));
const tx = async (mode, fn) => { const d = await db(); return new Promise((ok, ko) => { const t = d.transaction('photos', mode), out = fn(t.objectStore('photos')); t.oncomplete = () => ok(out && out.result !== undefined ? out.result : undefined); t.onerror = () => ko(t.error); }); };
export const photos = {
  all: () => tx('readonly', s => s.getAll()).then(a => (a || []).sort((x, y) => y.at - x.at)).catch(() => []),
  add: rec => tx('readwrite', s => s.add(rec)),
  remove: id => tx('readwrite', s => s.delete(id)),
};
