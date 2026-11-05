// Géométrie de la carte : passages entre les trois systèmes de coordonnées. Aucun accès au DOM, testable seul.
//   jeu    : [x, y] en mètres (x vers l’est, y vers le nord), comme dans GTA V ;
//   image  : [nx, ny] entre 0 et 1, origine en haut à gauche (c’est le repère historique de public.map_marks) ;
//   carte  : [lat, lng] de Leaflet en L.CRS.Simple. L’image occupe lat ∈ [-256, 0] et lng ∈ [0, 256] :
//            c’est exactement la taille d’une tuile au zoom 0, donc la tuile z/x/y couvre sa zone sans calcul.
export const SIDE = 256;

// Étalonnage provisoire : l’image couvre un carré de 8192 m centré sur l’origine du jeu (1 pixel = 1 m).
// L’étalonnage réel est écrit par scripts/make-tiles.mjs dans web/tiles/gta5/meta.json (option --calib) et lu par la page.
export const CALIB_DEFAUT = Object.freeze({ minX: -4096, maxX: 4096, minY: -4096, maxY: 4096 });

const clamp01 = v => Math.min(1, Math.max(0, v));
const num = (v, d) => Number.isFinite(Number(v)) && v !== null && v !== '' ? Number(v) : d;

// Normalise un étalonnage reçu d’un fichier : revient au défaut s’il est incomplet ou dégénéré.
export function calibration(c) {
  const k = { minX: num(c && c.minX, NaN), maxX: num(c && c.maxX, NaN), minY: num(c && c.minY, NaN), maxY: num(c && c.maxY, NaN) };
  return Object.values(k).every(Number.isFinite) && k.maxX > k.minX && k.maxY > k.minY ? k : { ...CALIB_DEFAUT };
}

export const jeuVersImage = ([x, y], c = CALIB_DEFAUT) => [(x - c.minX) / (c.maxX - c.minX), (c.maxY - y) / (c.maxY - c.minY)];
export const imageVersJeu = ([nx, ny], c = CALIB_DEFAUT) => [c.minX + nx * (c.maxX - c.minX), c.maxY - ny * (c.maxY - c.minY)];
export const imageVersCarte = ([nx, ny]) => [-ny * SIDE, nx * SIDE];
export const carteVersImage = ([lat, lng]) => [lng / SIDE, -lat / SIDE];
export const jeuVersCarte = (p, c) => imageVersCarte(jeuVersImage(p, c));
export const carteVersJeu = (p, c) => imageVersJeu(carteVersImage(p), c);

// Repère historique (0–1, arrondi à 5 décimales comme save_mark) ↔ coordonnées de jeu stockées dans les plans.
export const repereVersJeu = (nx, ny, c) => imageVersJeu([clamp01(nx), clamp01(ny)], c).map(v => Math.round(v * 100) / 100);
export const jeuVersRepere = (x, y, c) => jeuVersImage([x, y], c).map(v => Math.round(clamp01(v) * 1e5) / 1e5);

// Point cliqué sur la carte → repère 0–1 borné à l’image.
export const carteVersRepere = latlng => carteVersImage(latlng).map(v => Math.round(clamp01(v) * 1e5) / 1e5);
