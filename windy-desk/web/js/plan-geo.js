// Géométrie des plans d'intervention : fonctions pures (aucun DOM), testées dans tests/map.test.mjs.
// Les points de calcul sont en « coordonnées carte » [lat, lng] (voir map-geo.js) ; le plan stocke des coordonnées de jeu [x, y].
import { SIDE, jeuVersCarte, carteVersJeu } from './map-geo.js';

export const TEAMS = ['A', 'B', 'C', 'D'];
export const TYPES = {   // type de forme → [libellé, points minimum, points maximum]
  fleche: ['Flèche', 2, 200], zone: ['Zone', 3, 200], perimetre: ['Périmètre', 3, 200], ligne: ['Ligne', 2, 200], entree: ['Point d’entrée', 1, 1], etiquette: ['Étiquette', 1, 1] };
export const LIMITS = { layers: 30, shapes: 500 };

const rad = d => d * Math.PI / 180;
export const centre = pts => [pts.reduce((s, p) => s + p[0], 0) / pts.length, pts.reduce((s, p) => s + p[1], 0) / pts.length];
export const translate = (pts, dLat, dLng) => pts.map(p => [p[0] + dLat, p[1] + dLng]);
// rotation d'un angle positif = sens inverse des aiguilles d'une montre à l'écran (lat vers le haut, lng vers la droite)
export const rotate = (pts, c, deg) => { const s = Math.sin(rad(deg)), k = Math.cos(rad(deg)); return pts.map(p => { const dy = p[0] - c[0], dx = p[1] - c[1]; return [c[0] + dx * s + dy * k, c[1] + dx * k - dy * s]; }); };
export const scale = (pts, c, k) => pts.map(p => [c[0] + (p[0] - c[0]) * k, c[1] + (p[1] - c[1]) * k]);
// coins d'une photo : [haut-gauche, haut-droit, bas-droit, bas-gauche]
export const angleOf = c => Math.atan2(c[1][0] - c[0][0], c[1][1] - c[0][1]) * 180 / Math.PI;
export const sizeOf = c => ({ w: Math.hypot(c[1][0] - c[0][0], c[1][1] - c[0][1]), h: Math.hypot(c[3][0] - c[0][0], c[3][1] - c[0][1]) });
export const photoCorners = (centerLL, width, aspect) => { const h = width / aspect, [la, ln] = centerLL; return [[la + h / 2, ln - width / 2], [la + h / 2, ln + width / 2], [la - h / 2, ln + width / 2], [la - h / 2, ln - width / 2]]; };
// transformation CSS matrix(a, b, c, d, e, f) qui place une image w×h sur trois coins en pixels (haut-gauche, haut-droit, bas-gauche)
export const affine = (tl, tr, bl, w, h) => [(tr[0] - tl[0]) / w, (tr[1] - tl[1]) / w, (bl[0] - tl[0]) / h, (bl[1] - tl[1]) / h, tl[0], tl[1]];
// agrandissement uniforme par un coin déplacé : l'angle opposé reste fixe
export const resizeByCorner = (corners, i, to) => {
  const o = corners[(i + 2) % 4], d = [corners[i][0] - o[0], corners[i][1] - o[1]], t = [to[0] - o[0], to[1] - o[1]];
  const k = Math.max(0.02, (t[0] * d[0] + t[1] * d[1]) / (d[0] * d[0] + d[1] * d[1]));
  return scale(corners, o, k);
};
// un mètre de jeu, exprimé en unités de la carte, sur chaque axe
export const metre = c => ({ lat: SIDE / (c.maxY - c.minY), lng: SIDE / (c.maxX - c.minX) });
export const round2 = v => Math.round(v * 100) / 100;
export const toGame = (llPts, c) => llPts.map(p => carteVersJeu(p, c).map(round2));
export const fromGame = (pts, c) => pts.map(p => jeuVersCarte(p, c));

export const newId = () => Math.random().toString(36).slice(2, 10);

// Même contrôle que save_plan côté SQL : sert à refuser tôt, avec un message clair
export function checkShape(s) {
  const t = TYPES[s && s.type];
  if (!t) return 'Forme invalide.';
  if (!Array.isArray(s.pts) || s.pts.length < t[1] || s.pts.length > t[2] || !s.pts.every(p => Array.isArray(p) && p.length === 2 && p.every(Number.isFinite))) return 'Forme invalide.';
  if (String(s.label || '').length > 80) return 'Texte trop long (80 caractères).';
  if (String(s.role || '').length > 40) return 'Rôle trop long (40 caractères).';
  if (s.team && !TEAMS.includes(s.team)) return 'Équipe inconnue.';
  if (s.type === 'etiquette' && !String(s.label || '').trim()) return 'Écris le texte de l’étiquette.';
  return null;
}
export const describe = s => TYPES[s.type][0] + (s.team ? ' · équipe ' + s.team : '') + (s.role ? ' · ' + s.role : '') + (s.label ? ' · ' + s.label : '');
