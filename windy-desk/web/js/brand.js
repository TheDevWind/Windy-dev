// Profil de marque : le même logiciel sous deux habillages.
//   windy : le site Windy Desk tel que le connaissent les entreprises et services.
//   usms  : l'habillage « U.S. Marshals Service » pour la tablette en jeu : pas de nom de produit tiers, pas de vocabulaire de jeu,
//           ouverture directe sur le service, connexion par mot de passe seulement.
// Le profil se choisit par l'adresse (?marque=usms&service=<code>), se garde le temps de l'onglet, ou se fixe dans config.js (brand, brandService).
const PROFILS = {
  windy: { id: 'windy', name: 'Windy Desk', kiosk: false },
  usms: {
    id: 'usms', name: 'U.S. Marshals Service', product: 'Console opérationnelle', kiosk: true, logo: '/img/usms-seal.png', css: '/css/usms.css',
    title: 'U.S. Marshals Service · Console opérationnelle',
    // libellés qui parlaient du jeu : remplacés par un vocabulaire d'entreprise
    strings: { nameHint: 'Prénom Nom', gameId: 'Identifiant de session', gameIdShort: 'Session', chat: 'Messagerie', chatId: 'Identifiant de messagerie', chatThe: 'la messagerie', chatOnly: 'sur la messagerie', character: 'Nom complet', contactHint: 'Adresse e-mail ou numéro de téléphone', accountWord: 'compte', chooseLogin: 'son mot de passe', blacklistId: 'même identifiant de messagerie', noAccount: 'Pas encore d’accès ? Adresse-toi à ton responsable.', kind: 'Service fédéral', backToSite: null },
  },
};
const KEY = 'windy.brand';
function pick() {
  const q = new URLSearchParams(location.search);
  const cfg = window.WINDY_DESK_CONFIG || {};
  let id = q.get('marque'), service = q.get('service');
  try {
    if (id && PROFILS[id]) sessionStorage.setItem(KEY, JSON.stringify({ id, service }));
    else { const k = JSON.parse(sessionStorage.getItem(KEY) || 'null'); if (k && PROFILS[k.id]) { id = k.id; service = service || k.service; } }
  } catch { /* stockage indisponible : le profil vaut pour cette page seulement */ }
  if (!PROFILS[id]) id = PROFILS[cfg.brand] ? cfg.brand : 'windy';
  return { ...PROFILS[id], service: (service || cfg.brandService || '').toLowerCase().replace(/[^a-z0-9-]/g, '') || null };
}
export const brand = pick();
export const t = (key, fallback) => (brand.strings && key in brand.strings ? brand.strings[key] : fallback);

// habillage de la page : attribut de marque, feuille de style, titre et icône
document.documentElement.dataset.brand = brand.id;
if (brand.css) document.head.append(Object.assign(document.createElement('link'), { rel: 'stylesheet', href: brand.css }));
if (brand.title) {
  document.title = brand.title;
  const d = document.querySelector('meta[name="description"]'); if (d) d.content = brand.title;
  const ic = document.querySelector('link[rel="icon"]'); if (ic && brand.logo) { ic.href = brand.logo; ic.type = 'image/png'; }
}

// Dans la tablette (page affichée dans un cadre) : Échap, quand rien d'autre n'est ouvert, demande à la tablette de se ranger.
if (brand.kiosk && window.parent !== window) {
  // la tablette impose son thème clair ou sombre à la console (et le redemande à chaque chargement)
  window.addEventListener('message', ev => { if (ev.source === window.parent && ev.data && ev.data.type === 'theme' && (ev.data.theme === 'dark' || ev.data.theme === 'light')) document.documentElement.setAttribute('data-theme', ev.data.theme); });
  window.parent.postMessage({ type: 'tablet-ready' }, '*');
  document.addEventListener('keydown', ev => {
    if (ev.key === 'Escape' && !ev.defaultPrevented && !document.querySelector('.modal-bg, .menu')) window.parent.postMessage({ type: 'tablet-close' }, '*');
  });
}
