// Carte Leaflet (1.9, BSD-2, copiée dans web/vendor/leaflet/) : chargement à la demande, fond de tuiles auto-hébergées
// ou, à défaut, image de la carte du service. Rien d’externe : la CSP du site reste stricte.
import { SIDE, calibration, imageVersCarte } from './map-geo.js';

const TUILES = '/tiles/gta5/';
let chargement = null;

function inject(tag, attrs) {
  return new Promise((ok, ko) => {
    const el = document.createElement(tag);
    Object.assign(el, attrs); el.onload = ok; el.onerror = () => ko(new Error('Chargement impossible : ' + (attrs.src || attrs.href)));
    document.head.append(el);
  });
}
export function loadLeaflet() {
  if (window.L && window.L.map) return Promise.resolve(window.L);
  return chargement || (chargement = Promise.all([
    inject('link', { rel: 'stylesheet', href: '/vendor/leaflet/leaflet.css' }),
    inject('script', { src: '/vendor/leaflet/leaflet.js' })]).then(() => window.L, e => { chargement = null; throw e; }));
}

// meta.json des tuiles, ou null s’il n’y en a pas (le repli de l’hébergeur renvoie index.html : on exige du JSON valide).
export async function tilesMeta() {
  try {
    const r = await fetch(TUILES + 'meta.json', { cache: 'no-cache' });
    if (!r.ok) return null;
    const m = await r.json();
    return m && m.v === 1 && Number.isInteger(m.maxZoom) && m.maxZoom >= 0 ? { ...m, calib: calibration(m.calib) } : null;
  } catch { return null; }
}

// Monte la carte dans `el` (déjà dans le document). opts : meta (tilesMeta), imageUrl (carte du service), alt, view ({c:[lat,lng], z}).
export async function createMap(el, opts = {}) {
  const L = await loadLeaflet(), { meta, imageUrl, alt } = opts;
  const bounds = L.latLngBounds([[-SIDE, 0], [0, SIDE]]);
  const map = L.map(el, { crs: L.CRS.Simple, zoomControl: false, attributionControl: false, zoomSnap: 0.25, zoomDelta: 0.5, wheelPxPerZoomLevel: 90,
    minZoom: meta ? 0 : -3, maxZoom: meta ? meta.maxZoom + 1 : 3, maxBounds: bounds.pad(0.25), maxBoundsViscosity: 0.8 });
  L.control.zoom({ zoomInTitle: 'Zoom avant', zoomOutTitle: 'Zoom arrière' }).addTo(map);
  if (meta) L.tileLayer(TUILES + '{z}/{x}/{y}.' + (meta.ext || 'webp'), { tileSize: meta.tile || 256, minNativeZoom: 0, maxNativeZoom: meta.maxZoom, noWrap: true, bounds, keepBuffer: 2 }).addTo(map);
  else if (imageUrl) L.imageOverlay(imageUrl, bounds, { className: 'mapimg', alt: alt || 'Carte de la ville' }).addTo(map);
  const v = opts.view;
  if (v && Array.isArray(v.c) && Number.isFinite(v.z)) map.setView(v.c, v.z, { animate: false }); else map.fitBounds(bounds, { animate: false });
  return { L, map, bounds, calib: meta ? meta.calib : calibration(null), fond: meta ? 'tuiles' : imageUrl ? 'image' : 'aucun' };
}
export { imageVersCarte };
