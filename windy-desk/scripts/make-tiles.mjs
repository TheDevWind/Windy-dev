// Découpe l’image satellite en tuiles WebP 256 px pour la carte (Leaflet, L.CRS.Simple, aucun serveur de tuiles).
//   npm install --no-save sharp        (outil de génération seulement : pas une dépendance du site)
//   node scripts/make-tiles.mjs assets-src/gta5-satellite.jpg [--out web/tiles/gta5] [--max-zoom 5]
//        [--calib minX,maxX,minY,maxY] [--quality 72]
// Sortie : <out>/{z}/{x}/{y}.webp et <out>/meta.json (taille, niveaux, étalonnage en mètres de jeu).
// L’image est complétée en carré (bord bas/droit transparent, jamais déformée) : l’étalonnage décrit le carré entier.
import { mkdir, writeFile, rm } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

export const TILE = 256;

// Plan des niveaux : au zoom z, l’image carrée est ramenée à 256·2^z pixels et coupée en (2^z)² tuiles.
export function planLevels(maxZoom) {
  if (!Number.isInteger(maxZoom) || maxZoom < 0 || maxZoom > 8) throw new Error('--max-zoom doit être un entier entre 0 et 8');
  return Array.from({ length: maxZoom + 1 }, (_, z) => ({ z, size: TILE << z, n: 1 << z, tiles: 4 ** z }));
}
export const tileCount = maxZoom => planLevels(maxZoom).reduce((s, l) => s + l.tiles, 0);

export function parseArgs(argv) {
  const o = { src: null, out: 'web/tiles/gta5', maxZoom: 5, quality: 72, calib: null };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--out') o.out = argv[++i];
    else if (a === '--max-zoom') o.maxZoom = Number(argv[++i]);
    else if (a === '--quality') o.quality = Number(argv[++i]);
    else if (a === '--calib') {
      const v = String(argv[++i]).split(',').map(Number);
      if (v.length !== 4 || !v.every(Number.isFinite) || v[1] <= v[0] || v[3] <= v[2]) throw new Error('--calib attend minX,maxX,minY,maxY (maxX > minX, maxY > minY)');
      o.calib = { minX: v[0], maxX: v[1], minY: v[2], maxY: v[3] };
    } else if (a.startsWith('--')) throw new Error('Option inconnue : ' + a);
    else o.src = a;
  }
  if (!o.src) throw new Error('Usage : node scripts/make-tiles.mjs <image> [--out dossier] [--max-zoom 5] [--calib minX,maxX,minY,maxY] [--quality 72]');
  return o;
}

async function main() {
  const o = parseArgs(process.argv.slice(2)), levels = planLevels(o.maxZoom);
  let sharp;
  try { sharp = (await import('sharp')).default; } catch { throw new Error('Il manque sharp : npm install --no-save sharp'); }
  const meta = await sharp(o.src, { limitInputPixels: false }).metadata();
  const side = Math.max(meta.width, meta.height), top = TILE << o.maxZoom;
  // 1. Image carrée à la résolution maximale, sans déformation.
  const square = await sharp(o.src, { limitInputPixels: false })
    .resize(top, top, { fit: 'contain', position: 'left top', background: { r: 0, g: 0, b: 0, alpha: 0 }, kernel: 'lanczos3', withoutEnlargement: false })
    .ensureAlpha().raw().toBuffer({ resolveWithObject: true });
  const out = resolve(o.out);
  await rm(out, { recursive: true, force: true });
  let count = 0, bytes = 0;
  for (const l of levels) {
    const lvl = sharp(square.data, { raw: square.info, limitInputPixels: false }).resize(l.size, l.size, { kernel: 'lanczos3' }).raw().toBuffer({ resolveWithObject: true });
    const { data, info } = await lvl;
    for (let x = 0; x < l.n; x++) {
      await mkdir(join(out, String(l.z), String(x)), { recursive: true });
      for (let y = 0; y < l.n; y++) {
        const buf = await sharp(data, { raw: info, limitInputPixels: false }).extract({ left: x * TILE, top: y * TILE, width: TILE, height: TILE }).webp({ quality: o.quality, effort: 5 }).toBuffer();
        await writeFile(join(out, String(l.z), String(x), y + '.webp'), buf); count++; bytes += buf.length;
      }
    }
    console.log(`zoom ${l.z} : ${l.tiles} tuile(s)`);
  }
  const doc = { v: 1, tile: TILE, minZoom: 0, maxZoom: o.maxZoom, ext: 'webp', source: { width: meta.width, height: meta.height, side }, calib: o.calib };
  await writeFile(join(out, 'meta.json'), JSON.stringify(doc, null, 1) + '\n');
  console.log(`${count} tuiles, ${(bytes / 1048576).toFixed(1)} Mo → ${out}${o.calib ? '' : '\n⚠ Pas d’étalonnage (--calib) : la page utilisera l’étalonnage provisoire de web/js/map-geo.js.'}`);
}

if (process.argv[1] === fileURLToPath(import.meta.url)) main().catch(e => { console.error(e.message); process.exit(1); });
