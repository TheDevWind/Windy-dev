// Console du service : le site, affiché dans un cadre. Reste chargée entre deux ouvertures pour garder la session.
import { html } from './kit.js';

export default {
  id: 'console', name: 'Console', image: 'img/seal.webp', icon: 'shield-check', grad: ['#2c4d94', '#14234d'], ink: '#fff', bar: 'light', bg: '#14213D', persistent: true,
  mount(el, ctx) {
    el.innerHTML = '<iframe title="Console du service" allow="clipboard-read; clipboard-write"></iframe>' +
      '<div class="c-load"><div class="spin" aria-hidden="true"></div><p id="c-msg">Connexion à la console…</p><button id="c-retry" type="button" class="pill-btn" hidden>Réessayer</button></div><pre class="c-dbg" hidden></pre>';
    const frame = el.querySelector('iframe'), load = el.querySelector('.c-load'), msg = el.querySelector('#c-msg'), retry = el.querySelector('#c-retry'), dbg = el.querySelector('.c-dbg');
    let src = null, origin = null, timer = null, t0 = 0, reach = '…', state = 'en cours';
    ctx.frameWindow = frame.contentWindow;
    const paint = () => {
      dbg.hidden = !ctx.debug; if (!ctx.debug) return;
      dbg.textContent = 'page : ' + location.origin + '\nancêtres : ' + (location.ancestorOrigins ? [...location.ancestorOrigins].join(', ') || '(aucun)' : '(non lisible)') + '\nsite : ' + (src || '—') + '\njoignable : ' + reach + '\ncadre : ' + state;
    };
    const go = () => {
      if (!ctx.url) return;
      src = ctx.url; try { origin = new URL(src).origin; } catch { origin = null; }
      load.hidden = false; retry.hidden = true; msg.textContent = 'Connexion à la console…'; state = 'en cours'; t0 = Date.now(); reach = '…';
      clearTimeout(timer); timer = setTimeout(() => { msg.textContent = 'La console ne répond pas. Vérifie ta connexion, puis réessaie.'; retry.hidden = false; }, 20000);
      frame.onload = () => { clearTimeout(timer); load.hidden = true; sendTheme(); state = 'chargé en ' + (Date.now() - t0) + ' ms'; paint(); };
      frame.src = src;
      fetch(src, { mode: 'no-cors', cache: 'no-store' }).then(() => { reach = 'oui'; paint(); }, e => { reach = 'non (' + (e && e.message || 'erreur') + ')'; paint(); });
      paint();
    };
    const sendTheme = () => { if (origin && frame.contentWindow) frame.contentWindow.postMessage({ type: 'theme', theme: ctx.theme() }, origin); };
    ctx.bus.addEventListener('settings', sendTheme);
    retry.addEventListener('click', go);
    ctx.bus.addEventListener('frame', e => { const { data, origin: o } = e.detail; if (o !== origin || !data) return; if (data.type === 'tablet-close') ctx.home(); else if (data.type === 'tablet-ready') sendTheme(); });   // Échap dans la console : retour à l'accueil
    this.reload = () => { if (ctx.url && ctx.url !== src) go(); };
    go();
  },
  show(ctx) { this.reload && this.reload(); },
};
