// Appareil photo : passe le jeu en mode photo (la tablette se cache, un viseur reste) ; la photo prise par le jeu est réduite puis rangée dans Photos.
import { photos } from '../store.js';

async function shrink(dataUri) {
  const img = await new Promise((ok, ko) => { const i = new Image(); i.onload = () => ok(i); i.onerror = () => ko(new Error('image illisible')); i.src = dataUri; });
  const k = Math.min(1, 1280 / img.naturalWidth), w = Math.round(img.naturalWidth * k), h = Math.round(img.naturalHeight * k);
  const c = document.createElement('canvas'); c.width = w; c.height = h; c.getContext('2d').drawImage(img, 0, 0, w, h);
  const blob = await new Promise(ok => c.toBlob(ok, 'image/jpeg', .8));
  return { blob, w, h };
}

export default {
  id: 'camera', name: 'Appareil photo', icon: 'camera', grad: ['#8e96a6', '#3b4150'], bar: 'light', bg: '#000',
  mount(el, ctx) {
    const { bus, nui, glyph } = ctx; let active = false;
    el.innerHTML = `<div class="cam-wait">${glyph('camera', 64, 1.2)}<p>Ouverture de l’appareil photo…</p></div>`;
    nui('camera', { on: true }).then(r => {
      if (r && r.ok === true) { active = true; return; }
      ctx.toast(r && r.reason === 'missing' ? 'Appareil photo indisponible : la ressource screenshot-basic est absente' : 'Appareil photo indisponible', 'x', true);
      ctx.home();
    });
    const onCam = e => { if (e.detail.state === 'off') { active = false; ctx.home(); } };
    const onPhoto = async e => {
      try {
        const { blob, w, h } = await shrink(e.detail.data);
        await photos.add({ at: Date.now(), blob, w, h });
        ctx.note('Photo enregistrée'); ctx.toast('Photo enregistrée', 'photo');
      } catch { ctx.note('Photo non enregistrée'); ctx.toast('Photo non enregistrée', 'x', true); }
    };
    bus.addEventListener('camera', onCam); bus.addEventListener('photo', onPhoto);
    return () => { bus.removeEventListener('camera', onCam); bus.removeEventListener('photo', onPhoto); if (active) nui('camera', { on: false }); };
  },
};
