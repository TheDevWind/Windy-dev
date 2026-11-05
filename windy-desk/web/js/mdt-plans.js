// ================================================================== Windy Desk · MDT : plans d'intervention
// Un plan = une carte (tuiles ou image du service) + des calques photo et des formes, rattaché à un mandat, un transfert ou un briefing SOG.
// Format stocké dans intervention_plans.data (versionné) : {"v":1,"layers":[…],"shapes":[…],"view":{"c":[lat,lng],"z":3}}.
// Lecture : tables (RLS) ; écriture : fonctions save_plan / delete_plan.
import { S, h, ico, modal, toast, run, table, pill, fchips, date, slug } from './ui.js';
import { rpc, rows } from './sb.js';
import { saveBlob } from './doc.js';
import { ms, field, input, select, foot } from './mdt-kit.js';
import { createMap, tilesMeta } from './map-view.js';
import { planEditor } from './map-plan.js';

const base = ctx => '/' + ctx.me.code;
const KINDS = { mandat: ['Mandat', 'mandats', 'marteau'], transfert: ['Transfert', 'transferts', 'camion'], sog: ['SOG', 'sog', 'bouclier'] };
const EMPTY = () => ({ v: 1, layers: [], shapes: [] });
const when = t => date(ms(t));
const card = (title, ...c) => h('section', { class: 'card' }, h('h2', {}, title), ...c);

// Pièces auxquelles un plan peut se rattacher, par type : [{ id, label }]
async function pieces(ctx, kind) {
  const o = q => q.eq('org_id', ctx.me.id);
  if (kind === 'mandat') return (await rows('warrants', q => o(q).order('created_at', { ascending: false }))).map(w => ({ id: w.id, label: w.num + ' · ' + w.target }));
  if (kind === 'transfert') return (await rows('transfers', q => o(q).order('depart_at', { ascending: false }))).map(t => ({ id: t.id, label: t.num + ' · ' + t.from_place + ' → ' + t.to_place }));
  return (await rows('sog_missions', q => o(q).order('activated_at', { ascending: false }))).map(m => ({ id: m.id, label: m.num + ' · ' + m.title }));
}
const kinds = ctx => Object.entries(KINDS).filter(([, v]) => ctx.can(v[1]));

// Création : titre, type (selon les capacités du grade) et pièce rattachée
function newPlan(ctx, preset) {
  const ks = kinds(ctx);
  if (!ks.length) return toast('Ton grade ne permet pas de créer un plan.');
  const title = input({ maxlength: 120, placeholder: 'Ex. Entrée par l’arrière de la banque', 'aria-label': 'Titre du plan', value: (preset && preset.title) || '' });
  const kind = select(ks.map(([k, v]) => [k, v[0]]), (preset && preset.kind) || ks[0][0], { 'aria-label': 'Type de plan' });
  const ref = select([['', 'Aucune pièce']], '', { 'aria-label': 'Pièce rattachée' });
  const fill = async () => { ref.replaceChildren(h('option', { value: '' }, 'Aucune pièce'), ...(await pieces(ctx, kind.value)).map(p => h('option', { value: p.id, selected: preset && preset.ref === p.id }, p.label))); };
  kind.addEventListener('change', () => fill().catch(ctx.fail)); fill().catch(ctx.fail);
  const ok = h('button', { class: 'btn primary' }, 'Créer le plan');
  const md = modal('Nouveau plan d’intervention', field('Titre', title), field('Type', kind), field('Rattaché à', ref), foot({ close: () => md.close() }, ok));
  ok.addEventListener('click', () => run(ok, async () => {
    const id = await rpc('save_plan', { p_org: ctx.me.id, p_id: null, p_title: title.value, p_kind: kind.value, p_ref: ref.value || null, p_data: EMPTY() });
    md.close(); ctx.go(base(ctx) + '/plans/' + id); return { message: 'Plan créé.' };
  }));
}

// Bouton « Plan » des mandats, transferts et briefings : ouvre le plan lié, ou le crée s'il n'existe pas encore.
export function planButton(ctx, kind, refId, title, cls) {
  return h('button', { class: 'btn small' + (cls ? ' ' + cls : ''), type: 'button', onclick: ev => { ev.stopPropagation(); run(ev.currentTarget, async () => {
    const [p] = await rows('intervention_plans', q => q.eq('org_id', ctx.me.id).eq('kind', kind).eq('ref_id', refId).order('updated_at', { ascending: false }).limit(1));
    if (p) { ctx.go(base(ctx) + '/plans/' + p.id); return null; }
    if (!ctx.can(KINDS[kind][1])) return { message: 'Aucun plan pour l’instant.' };
    const id = await rpc('save_plan', { p_org: ctx.me.id, p_id: null, p_title: 'Plan · ' + title, p_kind: kind, p_ref: refId, p_data: EMPTY() });
    ctx.go(base(ctx) + '/plans/' + id); return { message: 'Plan créé.' };
  }); } }, ico('carte', 16), 'Plan');
}

// Import d'un plan exporté (.json) : il devient un nouveau plan, sans pièce rattachée
async function importPlan(ctx, file) {
  let j; try { j = JSON.parse(await file.text()); } catch { throw new Error('Fichier illisible.'); }
  if (!j || j.format !== 'windy-desk-plan' || j.v !== 1 || !KINDS[j.kind] || !j.data) throw new Error('Ce fichier n’est pas un plan Windy Desk.');
  if (!ctx.can(KINDS[j.kind][1])) throw new Error('Ton grade ne permet pas d’importer un plan de ce type.');
  const id = await rpc('save_plan', { p_org: ctx.me.id, p_id: null, p_title: String(j.title || 'Plan importé').slice(0, 120), p_kind: j.kind, p_ref: null, p_data: j.data });
  ctx.go(base(ctx) + '/plans/' + id);
}

// ---------------------------------------------------------------- liste
export async function plans(ctx) {
  const { me } = ctx, f = ['mandat', 'transfert', 'sog'].includes(S.filters.planK) ? S.filters.planK : 'tous';
  const [list, w, t, m] = await Promise.all([rows('intervention_plans', q => q.eq('org_id', me.id).order('updated_at', { ascending: false })),
    rows('warrants', q => q.eq('org_id', me.id)), rows('transfers', q => q.eq('org_id', me.id)), rows('sog_missions', q => q.eq('org_id', me.id))]);
  const label = p => { const x = (p.kind === 'mandat' ? w : p.kind === 'transfert' ? t : m).find(y => y.id === p.ref_id); return x ? x.num : '—'; };
  const shown = list.filter(p => f === 'tous' || p.kind === f);
  const file = h('input', { type: 'file', accept: 'application/json,.json', hidden: true, 'aria-label': 'Fichier de plan à importer' });
  const imp = h('button', { class: 'btn', type: 'button', onclick: () => file.click() }, ico('telecharger', 18), 'Importer', file);
  file.addEventListener('change', () => { const f0 = file.files[0]; file.value = ''; if (f0) run(imp, () => importPlan(ctx, f0).then(() => ({ message: 'Plan importé.' }))); });
  ctx.show('plans', 'Plans d’intervention', list.length + ' plan(s)', kinds(ctx).length ? [imp, h('button', { class: 'btn primary', type: 'button', onclick: () => newPlan(ctx) }, ico('ajout', 18), 'Nouveau plan')] : null,
    h('div', { class: 'filters' }, fchips([['tous', 'Tous'], ['mandat', 'Mandats'], ['transfert', 'Transferts'], ['sog', 'SOG']], f, k => { S.filters.planK = k; plans(ctx).catch(ctx.fail); }, 'Type de plan')),
    card('Plans', table([['Plan', p => h('span', { class: 'art' }, h('b', {}, p.title), h('small', {}, 'par ' + p.by_name))], ['Type', p => pill(KINDS[p.kind][0], 'info')], ['Rattaché à', label],
      ['Modifié', p => when(p.updated_at), 'num', p => p.updated_at]], shown, p => ctx.go(base(ctx) + '/plans/' + p.id), 'Aucun plan pour l’instant.')));
}

// ---------------------------------------------------------------- un plan
export function planFile(p, refLabel) {
  return new Blob([JSON.stringify({ format: 'windy-desk-plan', v: 1, exported_at: new Date().toISOString(), title: p.title, kind: p.kind, ref: refLabel || null, data: p.data }, null, 1) + '\n'], { type: 'application/json' });
}

export async function plan(ctx, id) {
  const { me } = ctx;
  const [p] = await rows('intervention_plans', q => q.eq('id', id).eq('org_id', me.id));
  if (!p) { toast('Plan introuvable.'); return ctx.go(base(ctx) + '/plans', true); }
  const [all, meta] = await Promise.all([pieces(ctx, p.kind), tilesMeta()]);
  const ref = all.find(x => x.id === p.ref_id), cap = KINDS[p.kind][1];
  const editable = ctx.can(cap) && (p.created_by === me.member_id || ctx.can('dossiers'));
  const data = JSON.parse(JSON.stringify(p.data || EMPTY()));
  const title = input({ maxlength: 120, value: p.title, 'aria-label': 'Titre du plan', disabled: !editable });
  const sel = select([['', 'Aucune pièce']].concat(all.map(x => [x.id, x.label])), p.ref_id || '', { 'aria-label': 'Pièce rattachée', disabled: !editable });
  const box = h('div', { class: 'mapbox', role: 'region', 'aria-label': 'Carte du plan : glisser pour déplacer, molette ou boutons pour zoomer, flèches du clavier pour se déplacer' });
  const mark = h('span', { class: 'pill warn plan-dirty', hidden: true, role: 'status' }, 'Modifications non enregistrées');
  const dirty = () => { mark.hidden = false; };
  const save = h('button', { class: 'btn primary', type: 'button' }, ico('coche', 18), 'Enregistrer');
  save.addEventListener('click', () => run(save, async () => {
    await rpc('save_plan', { p_org: me.id, p_id: p.id, p_title: title.value, p_kind: p.kind, p_ref: sel.value || null, p_data: data });
    ctx.reload(); return { message: 'Plan enregistré.' };
  }));
  const exp = h('button', { class: 'btn', type: 'button', onclick: () => saveBlob(planFile({ ...p, title: title.value, data }, ref && ref.label), 'plan-' + (slug(title.value) || 'intervention') + '.json') }, ico('telecharger', 18), 'Exporter');
  const del = h('button', { class: 'btn danger', type: 'button', onclick: () => {
    const ok = h('button', { class: 'btn danger' }, 'Supprimer le plan'), md = modal('Supprimer ce plan ?', h('p', {}, '« ' + p.title + ' » sera supprimé définitivement. Pense à l’exporter avant si tu veux le garder.'), foot({ close: () => md.close() }, ok));
    ok.addEventListener('click', () => run(ok, async () => { await rpc('delete_plan', { p_id: p.id }); md.close(); ctx.go(base(ctx) + '/plans', true); return { message: 'Plan supprimé.' }; }));
  } }, 'Supprimer');
  const tools = h('div', { class: 'plan-tools-host' }), panel = h('div', { class: 'plan-panel-host' });
  ctx.show('plans', p.title, KINDS[p.kind][0] + (ref ? ' · ' + ref.label : '') + ' · modifié ' + when(p.updated_at), [h('a', { class: 'btn', href: base(ctx) + '/plans' }, 'Tous les plans'), exp],
    h('div', { class: 'row' }, field('Titre', title, 'grow'), field('Rattaché à', sel, 'grow')),
    tools, h('div', { class: 'mapwrap' }, box), panel,
    h('p', { class: 'muted small' }, editable ? 'La vue de la carte (position et zoom), les photos et les formes sont enregistrées avec le plan : pense à cliquer sur « Enregistrer ».' : 'Lecture seule : seul l’auteur ou un enquêteur modifie ce plan.'),
    editable ? h('div', { class: 'foot' }, mark, del, save) : null);
  if (S.map) { S.map.remove(); S.map = null; }
  const mp = await createMap(box, { meta, view: data.view });
  S.map = mp.map;
  if (mp.fond === 'aucun') box.append(h('div', { class: 'mapblank' }, h('span', {}, ico('localisation', 20), ' Aucune carte disponible.')));
  mp.map.on('moveend', () => { const c = mp.map.getCenter(); data.view = { c: [Math.round(c.lat * 100) / 100, Math.round(c.lng * 100) / 100], z: mp.map.getZoom() }; });
  if (S.planEditor) S.planEditor.destroy();
  const ed = S.planEditor = await planEditor(ctx, { L: mp.L, map: mp.map, calib: mp.calib, data, editable, onDirty: dirty });
  tools.replaceChildren(ed.toolbar); panel.replaceChildren(ed.panel);
}
