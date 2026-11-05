// Carte : conversions de coordonnées (jeu ↔ image ↔ Leaflet) et plan de découpe des tuiles.
import test from 'node:test';
import assert from 'node:assert/strict';
import { SIDE, CALIB_DEFAUT, calibration, jeuVersImage, imageVersJeu, imageVersCarte, carteVersImage, jeuVersCarte, carteVersJeu, repereVersJeu, jeuVersRepere, carteVersRepere } from '../web/js/map-geo.js';
import { centre, translate, rotate, scale, angleOf, sizeOf, photoCorners, affine, resizeByCorner, metre, toGame, fromGame, planBounds, groundTiles, tipOf, checkShape, checkLegend, describe, colorOf, teamColor, teamName, VEHICLES, DEFAULT_COLORS } from '../web/js/plan-geo.js';
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

test('plans : rotation, échelle et coins d’une photo', () => {
  const c = photoCorners([-100, 100], 40, 2);   // 40 de large, 20 de haut, centrée
  assert.deepEqual(sizeOf(c), { w: 40, h: 20 }); near(angleOf(c), 0);
  const r = rotate(c, centre(c), 90);   // un quart de tour à gauche : le bord haut regarde vers le haut de l'écran
  near(angleOf(r), 90, 1e-9); near(sizeOf(r).w, 40); near(sizeOf(r).h, 20);
  const d = scale(c, centre(c), 2); near(sizeOf(d).w, 80);
  const t = translate(c, 5, -5); near(t[0][0], c[0][0] + 5); near(t[0][1], c[0][1] - 5);
  // un coin tiré vers l'extérieur agrandit en gardant les proportions et le coin opposé
  const big = resizeByCorner(c, 2, [c[2][0] - 10, c[2][1] + 20]);
  assert.deepEqual(big[0], c[0]); near(sizeOf(big).w / sizeOf(big).h, 2, 1e-9); assert.ok(sizeOf(big).w > 40);
  assert.ok(sizeOf(resizeByCorner(c, 2, [c[0][0], c[0][1]])).w > 0);   // jamais de taille nulle
});

test('plans : transformation CSS d’une image sur trois coins', () => {
  assert.deepEqual(affine([10, 20], [110, 20], [10, 70], 100, 50), [1, 0, 0, 1, 10, 20]);
  assert.deepEqual(affine([0, 0], [0, 100], [-50, 0], 100, 50), [0, 1, -1, 0, 0, 0]);   // tourné d'un quart de tour
});

test('plans : jeu ↔ carte et pas en mètres', () => {
  const pts = [[100, 200], [-50.5, 3000.25]];
  const back = toGame(fromGame(pts, C), C);
  near(back[0][0], 100, 0.01); near(back[1][1], 3000.25, 0.01);
  const m = metre(C); near(m.lng, SIDE / 8500); near(m.lat, SIDE / 12000);
});

test('plans : contrôle des formes comme save_plan', () => {
  assert.equal(checkShape({ type: 'fleche', pts: [[0, 0], [5, 5]] }), null);
  assert.match(checkShape({ type: 'zone', pts: [[0, 0], [5, 5]] }), /invalide/);
  assert.match(checkShape({ type: 'dragon', pts: [[0, 0]] }), /invalide/);
  assert.match(checkShape({ type: 'entree', pts: [[0, 0], [1, 1]] }), /invalide/);
  assert.match(checkShape({ type: 'ligne', pts: [[0, 'a'], [1, 1]] }), /invalide/);
  assert.match(checkShape({ type: 'etiquette', pts: [[0, 0]], label: '  ' }), /étiquette/);
  assert.match(checkShape({ type: 'etiquette', pts: [[0, 0]], label: 'x'.repeat(81) }), /trop long/);
  assert.equal(describe({ type: 'fleche', team: 'A', role: 'Brèche', label: 'Entrée' }), 'Flèche · équipe A · Brèche · Entrée');
});

test('plans : véhicules, couleurs, texte et légende', () => {
  assert.equal(checkShape({ type: 'vehicule', vkind: 'suv', angle: 90, pts: [[0, 0]], color: '#1c7ed6' }), null);
  assert.match(checkShape({ type: 'vehicule', pts: [[0, 0]] }), /véhicule/);
  assert.match(checkShape({ type: 'vehicule', vkind: 'tank', pts: [[0, 0]] }), /véhicule/);
  assert.match(checkShape({ type: 'zone', pts: [[0, 0], [1, 1], [2, 0]], color: 'rouge' }), /Couleur/);
  assert.match(checkShape({ type: 'texte', pts: [[0, 0]], label: '' }), /texte/i);
  assert.match(checkShape({ type: 'texte', pts: [[0, 0]], label: 'a', size: 99 }), /Taille/);
  assert.match(checkShape({ type: 'zone', pts: [[0, 0], [1, 1], [2, 0]], height: 99 }), /Hauteur/);
  assert.ok(Object.keys(VEHICLES).length === 6);
  // couleur : celle de la forme, sinon celle de l'équipe dans la légende, sinon le défaut
  const lg = { teams: { A: { name: 'Alpha', color: '#112233' }, B: { name: 'Bravo' } } };
  assert.equal(colorOf({ team: 'A', color: '#abcdef' }, lg), '#abcdef');
  assert.equal(colorOf({ team: 'A' }, lg), '#112233');
  assert.equal(colorOf({ team: 'B' }, lg), DEFAULT_COLORS.B);
  assert.equal(colorOf({ team: '' }, undefined), DEFAULT_COLORS['']);
  assert.equal(teamName(lg, 'A'), 'Alpha'); assert.equal(teamName(lg, 'C'), 'Équipe C'); assert.equal(teamName(lg, ''), 'Sans équipe');
  assert.equal(describe({ type: 'vehicule', vkind: 'suv', team: 'A' }, lg), 'SUV · Alpha');
  assert.equal(checkLegend(lg), null); assert.equal(checkLegend(undefined), null);
  assert.match(checkLegend({ teams: { Z: {} } }), /invalide/); assert.match(checkLegend({ teams: { A: { name: 'x'.repeat(31) } } }), /trop long/);
  assert.match(checkLegend({ teams: { A: { color: 'nope' } } }), /Couleur/); assert.match(checkLegend([]), /invalide/);
});

test('3D : emprise du plan et tuiles du sol', () => {
  const data = { shapes: [{ type: 'zone', pts: [[0, 0], [40, 0], [40, 30]] }], layers: [{ corners: [[-10, -5], [30, -5], [30, 20], [-10, 20]] }] };
  const b = planBounds(data, C);
  assert.ok(b.maxX - b.minX >= 120 && b.maxY - b.minY >= 120);                    // jamais plus petit que 120 m
  assert.ok(b.minX <= -10 && b.maxX >= 40 && b.minY <= -5 && b.maxY >= 30);       // contient tout le plan
  const vide = planBounds({ shapes: [], layers: [] }, C); assert.equal(vide.maxX - vide.minX, 120);
  const bord = planBounds({ shapes: [{ pts: [[4400, 7900], [4490, 7990]] }], layers: [] }, C); assert.ok(bord.maxX <= C.maxX && bord.maxY <= C.maxY);   // bornée par l'étalonnage
  // tuiles : au plus 25, au zoom le plus fin possible, et le rectangle rendu contient l'emprise demandée
  const g = groundTiles(b, C, 6);
  assert.ok((g.x1 - g.x0 + 1) * (g.y1 - g.y0 + 1) <= 25 && g.z <= 6);
  assert.ok(g.rect.minX <= b.minX + 1e-6 && g.rect.maxX >= b.maxX - 1e-6 && g.rect.minY <= b.minY + 1e-6 && g.rect.maxY >= b.maxY - 1e-6);
  const large = groundTiles({ minX: -3000, maxX: 3000, minY: -3000, maxY: 3000 }, C, 6); assert.ok(large.z < g.z);
  assert.equal(groundTiles({ minX: -3000, maxX: 3000, minY: -3000, maxY: 3000 }, C, 0).z, 0);
  assert.equal(tipOf({ type: 'vehicule', vkind: 'moto', team: 'B', role: 'Appui' }, { teams: { B: { name: 'Bravo' } } }), '[Bravo] Moto · Appui');
  assert.equal(tipOf({ type: 'zone', label: 'Hall' }), 'Hall');
});
