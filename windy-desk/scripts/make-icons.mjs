// Génère web/js/icons.js à partir de Tabler Icons (MIT), contour uniquement.
//   npm install --no-save @tabler/icons   puis   node scripts/make-icons.mjs [dossier node_modules]
import { readFileSync, writeFileSync } from 'node:fs';
const MAP = { accueil: 'layout-dashboard', joueurs: 'users', loupe: 'search', soleil: 'sun', lune: 'moon', chevron: 'chevron-down', coche: 'check', sortie: 'logout', plus: 'dots', ajout: 'plus',
  moins: 'minus', fleche: 'arrow-right', reglages: 'adjustments-horizontal', caisse: 'cash-register', boite: 'package', fiole: 'flask', epi: 'wheat', facture: 'receipt', coeur: 'heart', balance: 'scale',
  batiment: 'building', grille: 'layout-grid-add', graph: 'chart-bar', horloge: 'clock', alerte: 'alert-triangle', cadenas: 'lock', oeil: 'eye', journal: 'notes', badge: 'id', camion: 'truck',
  calendrier: 'calendar-event', telecharger: 'download', copier: 'copy', carburant: 'gas-station', cle2: 'key', bouclier: 'shield-check', diplome: 'school', carte: 'map', reseau: 'affiliate',
  rapport: 'file-text', scelle: 'archive', etoile: 'star', cible: 'target', dossier: 'folder', marteau: 'gavel', megaphone: 'speakerphone', trombone: 'paperclip', localisation: 'map-pin',
  discord: 'brand-discord', cloche: 'bell', plan: 'map-2', cube: 'cube' };
const root = (process.argv[2] || 'node_modules') + '/@tabler/icons/icons/outline/';
const out = Object.entries(MAP).map(([k, f]) => {
  const svg = readFileSync(root + f + '.svg', 'utf8');
  if (/<(circle|rect|line|polyline|ellipse)/.test(svg)) throw new Error(f + ' : forme non gérée');
  const d = [...svg.matchAll(/<path d="([^"]+)"/g)].map(m => m[1]);
  if (!d.length) throw new Error(f + ' : aucun tracé');
  return '  ' + k + ': ' + JSON.stringify(d).replace(/"/g, "'") + ',   // ' + f;
});
writeFileSync(new URL('../web/js/icons.js', import.meta.url), '// Icônes Tabler (MIT, https://tabler.io/icons), contour uniquement. Fichier généré par scripts/make-icons.mjs : ne pas modifier à la main.\nexport const ICONS = {\n' + out.join('\n') + '\n};\n');
console.log(out.length + ' icônes');
