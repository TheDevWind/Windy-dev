// Carte : conversions de coordonnées (jeu ↔ image ↔ Leaflet) et plan de découpe des tuiles.
import test from 'node:test';
import assert from 'node:assert/strict';
import { SIDE, CALIB_DEFAUT, calibration, jeuVersImage, imageVersJeu, imageVersCarte, carteVersImage, jeuVersCarte, carteVersJeu, repereVersJeu, jeuVersRepere, carteVersRepere } from '../web/js/map-geo.js';
import { planLevels, tileCount, parseArgs } from '../scripts/make-tiles.mjs';

const C = { minX: -4000, maxX: 4500, minY: -4000, maxY: 8000 };
const near = (a, b, eps = 1e-9) => assert.ok(Math.abs(a - b) < eps, a + ' ≠ ' + b);

test('les coins de l’image tombent sur les coins de la carte Leaflet', () => {
  assert.deepEqual(imageVersCarte([0, 0]), [-0, 0]);
  assert.deepEqual(imageVersCarte([1, 1]), [-SIDE, SIDE]);
  assert.deepEqual(carteVersImage([-SIDE / 2, SIDE / 2]), [0.5, 0.5]);
});

test('jeu : le nord est en haut, l’est à droite', () => {
  assert.deepEqual(jeuVersImage([C.minX, C.maxY], C), [0, 0]);   // coin nord-ouest = haut gauche
  assert.deepEqual(jeuVersImage([C.maxX, C.minY], C), [1, 1]);   // coin sud-est = bas droite
  const [, ny] = jeuVersImage([0, 100], C), [, ny2] = jeuVersImage([0, -100], C);
  assert.ok(ny < ny2, 'plus au nord = plus haut');
});

test('aller-retour jeu → carte → jeu sans perte', () => {
  for (const p of [[0, 0], [-1234.5, 678.9], [4499, -3999], [215, 7000]]) {
    const q = carteVersJeu(jeuVersCarte(p, C), C);
    near(q[0], p[0], 1e-6); near(q[1], p[1], 1e-6);
  }
});

test('repères historiques (0–1) : passage vers le jeu et retour, bornés à l’image', () => {
  const [x, y] = repereVersJeu(0.4, 0.3, C);
  const [nx, ny] = jeuVersRepere(x, y, C);
  near(nx, 0.4, 1e-4); near(ny, 0.3, 1e-4);
  assert.deepEqual(jeuVersRepere(99999, -99999, C), [1, 1]);
  assert.deepEqual(repereVersJeu(-2, 5, C), imageVersJeu([0, 1], C).map(v => Math.round(v * 100) / 100));
  assert.deepEqual(carteVersRepere([-SIDE * 0.3, SIDE * 0.4]), [0.4, 0.3]);
  assert.deepEqual(carteVersRepere([50, -20]), [0, 0]);   // clic hors de l’image : ramené au bord
});

test('calibration : un étalonnage incomplet ou dégénéré revient au défaut', () => {
  assert.deepEqual(calibration(C), C);
  assert.deepEqual(calibration(null), CALIB_DEFAUT);
  assert.deepEqual(calibration({ minX: 5, maxX: 5, minY: 0, maxY: 1 }), CALIB_DEFAUT);
  assert.deepEqual(calibration({ minX: 'a', maxX: 1, minY: 0, maxY: 1 }), CALIB_DEFAUT);
  assert.deepEqual(calibration({ minX: null, maxX: 1, minY: 0, maxY: 1 }), CALIB_DEFAUT);
});

test('tuiles : 4^z tuiles au niveau z, 1365 au total jusqu’au zoom 5', () => {
  const l = planLevels(5);
  assert.deepEqual(l.map(x => x.tiles), [1, 4, 16, 64, 256, 1024]);
  assert.equal(l[5].size, 8192);
  assert.equal(tileCount(5), 1365);
  assert.throws(() => planLevels(9)); assert.throws(() => planLevels(-1)); assert.throws(() => planLevels(2.5));
});

test('make-tiles : lecture des options', () => {
  const o = parseArgs(['img.jpg', '--max-zoom', '4', '--calib', '-4000,4500,-4000,8000', '--out', 'x']);
  assert.deepEqual([o.src, o.maxZoom, o.out, o.calib], ['img.jpg', 4, 'x', C]);
  assert.throws(() => parseArgs([]), /Usage/);
  assert.throws(() => parseArgs(['a.jpg', '--calib', '1,0,0,1']), /calib/);
  assert.throws(() => parseArgs(['a.jpg', '--zzz']), /inconnue/);
});
