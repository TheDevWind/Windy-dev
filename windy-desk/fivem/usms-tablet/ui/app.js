// Page de la tablette : cadre, horloge, chargement de la console dans un cadre, et fermeture (bouton, Échap, ou Échap dans la console).
const root = document.getElementById('root'), frame = document.getElementById('app'), overlay = document.getElementById('overlay');
const msg = document.getElementById('msg'), retry = document.getElementById('retry'), clock = document.getElementById('clock');
let current = null, siteOrigin = null, timer = null, debugOn = false, t0 = 0, reach = '…', loaded = 'en cours';
const debug = document.getElementById('debug');
function paintDebug() {
  debug.hidden = !debugOn;
  if (!debugOn) return;
  debug.textContent = 'page : ' + location.origin + '\nancêtres : ' + (location.ancestorOrigins ? [...location.ancestorOrigins].join(', ') || '(aucun)' : '(non lisible)') +
    '\nsite : ' + (current || '—') + '\njoignable : ' + reach + '\ncadre : ' + loaded;
}
function probe(url) {   // le site répond-il depuis le jeu ? (indépendant de l'affichage dans le cadre)
  reach = '…'; paintDebug();
  fetch(url, { mode: 'no-cors', cache: 'no-store' }).then(() => { reach = 'oui'; paintDebug(); }, e => { reach = 'non (' + (e && e.message || 'erreur') + ')'; paintDebug(); });
}

const tick = () => { clock.textContent = new Date().toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' }); };
setInterval(tick, 20000); tick();

function load(url) {
  current = url;
  try { siteOrigin = new URL(url).origin; } catch { siteOrigin = null; }
  overlay.hidden = false; retry.hidden = true; msg.textContent = 'Connexion à la console…';
  clearTimeout(timer);
  timer = setTimeout(() => { msg.textContent = 'La console ne répond pas. Vérifie ta connexion, puis réessaie.'; retry.hidden = false; }, 20000);
  t0 = Date.now(); loaded = 'en cours';
  frame.onload = () => { clearTimeout(timer); overlay.hidden = true; loaded = 'événement load après ' + (Date.now() - t0) + ' ms (un cadre bloqué déclenche aussi load)'; paintDebug(); };
  frame.src = url;
  probe(url); paintDebug();
}

function open(url, withDebug) {
  debugOn = !!withDebug;
  root.hidden = false;
  if (url && url !== current) load(url);   // la console reste chargée entre deux ouvertures : la session est gardée
  document.getElementById('close').focus();
  paintDebug();
}
function close() { root.hidden = true; }

// Demande de fermeture au jeu (qui range la tablette et rend la main au joueur)
function requestClose() {
  fetch('https://' + (typeof GetParentResourceName === 'function' ? GetParentResourceName() : 'usms-tablet') + '/close', {
    method: 'POST', headers: { 'Content-Type': 'application/json; charset=UTF-8' }, body: '{}' }).catch(() => close());
}

window.addEventListener('message', ev => {
  const d = ev.data || {};
  if (ev.source === frame.contentWindow) {
    // la console n'a le droit de demander que sa fermeture, et seulement depuis sa propre origine
    if (d.type === 'tablet-close' && ev.origin === siteOrigin) requestClose();
    return;
  }
  if (d.action === 'open') open(d.url, d.debug);
  else if (d.action === 'close') close();
});
document.addEventListener('keydown', ev => { if (ev.key === 'Escape') requestClose(); });
document.getElementById('close').addEventListener('click', requestClose);
retry.addEventListener('click', () => load(current));
