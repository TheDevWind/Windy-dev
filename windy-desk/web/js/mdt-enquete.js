// ================================================================== Windy Desk · MDT, étape 2 : enquête
// Recherche globale, fichier des véhicules, organisations, rapports guidés (validation, document officiel PNG/PDF),
// code pénal avec calcul des peines, preuves et chaîne de possession.
// Ce module ne dépend que de mdt-kit.js ; mdt.js l'utilise pour enrichir les fiches individus et les dossiers.
import { S, h, ico, modal, toast, run, table, pill, strip, fchips, money, date, ago, slug, BRAND } from './ui.js';
import { rpc, rows, signedUrls } from './sb.js';
import { exportModal } from './doc.js';
import { DAY, ms, iso, day, field, input, area, select, foot, fullName, toLocal, tabs, search, match, STATUS, OPEN, face, personCell, feed, load, send, photoIn, sigEl, drawSig, sigFontsReady, seal, INK } from './mdt-kit.js';

export const KIND = { suspect: ['Suspect', 'warn'], civil: ['Civil', null], temoin: ['Témoin', 'info'], victime: ['Victime', 'info'] };
export const DANGER = { faible: ['Danger faible', null], moyenne: ['Danger moyen', 'warn'], elevee: ['Danger élevé', 'bad'], extreme: ['Danger extrême', 'bad'] };
const LINK = { famille: 'Famille', proche: 'Proche', complice: 'Complice', associe: 'Associé', rival: 'Rival' };
const PLATE_LOG = { creation: ['Fiche créée', null, 'camion'], proprietaire: ['Propriétaire', 'info', 'joueurs'], observation: ['Observation', 'warn', 'localisation'],
  vol: ['Vol', 'bad', 'alerte'], recherche: ['Recherche', 'bad', 'cible'], saisie: ['Saisie', null, 'scelle'], note: ['Note', null, 'journal'] };
const CAT = { contravention: 'Contravention', delit: 'Délit', crime: 'Crime' };
const EKIND = { arme: ['Arme', 'cible'], drogue: ['Drogue', 'fiole'], argent: ['Argent', 'balance'], vehicule: ['Véhicule', 'camion'], document: ['Document', 'facture'], autre: ['Autre', 'boite'] };
const EST = { stock: ['En stock', 'info'], tribunal: ['Transmise au tribunal', 'warn'], restituee: ['Restituée', 'ok'], detruite: ['Détruite', null] };
const EACT = { saisie: ['Saisie', 'info', 'scelle'], transfert: ['Transfert', null, 'fleche'], consultation: ['Consultation', null, 'oeil'], tribunal: ['Tribunal', 'warn', 'marteau'],
  restitution: ['Restitution', 'ok', 'coche'], destruction: ['Destruction', 'bad', 'alerte'], note: ['Note', null, 'journal'] };
export const RST = { brouillon: ['Brouillon', null], soumis: ['À valider', 'warn'], valide: ['Validé', 'ok'], a_corriger: ['À corriger', 'bad'] };
// les formulaires guidés : [clé, libellé, type, obligatoire] ; la base vérifie les mêmes champs à la soumission
export const RTYPES = {
  arrestation: { label: 'Rapport d’arrestation', icon: 'cadenas', person: 'Individu arrêté', needPerson: true, needPlace: true, charges: true, firearm: true,
    fields: [['rights', 'Droits lus à l’individu', 'check', true], ['search', 'Fouille et objets trouvés', 'area'], ['health', 'État de santé', 'text'], ['custody', 'Lieu de détention', 'text']] },
  sog: { label: 'Rapport d’intervention SOG', icon: 'cible', needPlace: true, firearm: true, participants: true,
    fields: [['objective', 'Objectif de l’opération', 'text', true], ['result', 'Résultat', 'text'], ['injuries', 'Blessés', 'text'], ['ammo', 'Munitions utilisées', 'text']] },
  transfert: { label: 'Rapport de transfert', icon: 'camion', person: 'Détenu transféré', needPerson: true, participants: true,
    fields: [['from', 'Départ', 'text', true], ['to', 'Arrivée', 'text', true], ['vehicle', 'Véhicule et plaque', 'text'], ['events', 'Incidents pendant le trajet', 'area']] },
  incident: { label: 'Rapport d’incident', icon: 'alerte', person: 'Personne impliquée', needPlace: true, firearm: true,
    fields: [['nature', 'Nature de l’incident', 'text', true], ['damage', 'Dégâts et blessés', 'text']] },
  force: { label: 'Rapport d’usage de la force', icon: 'alerte', person: 'Personne visée', needPlace: true, firearm: true,
    fields: [['force_type', 'Type de force', 'select:Physique|Matraque|Taser|Arme à feu', true], ['injuries', 'Blessures', 'text'], ['medical', 'Soins apportés', 'text']] },
  audience: { label: 'Rapport d’audience', icon: 'marteau', person: 'Prévenu', charges: true,
    fields: [['court', 'Tribunal et juge', 'text', true], ['verdict', 'Verdict', 'select:Coupable|Non coupable|Renvoyé|Abandon des poursuites', true], ['sentence', 'Peine prononcée', 'text']] },
  libre: { label: 'Rapport libre', icon: 'journal', person: 'Personne concernée', fields: [] },
};
const late = r => ['brouillon', 'a_corriger'].includes(r.status) && Date.now() - ms(r.reviewed_at || r.created_at) > DAY;
const statusPill = r => [pill(...RST[r.status]), late(r) ? pill('En retard', 'bad') : null];
const months = n => n ? n + ' mois' : '—';
const base = ctx => '/' + ctx.me.code;
const linkTo = (href, text) => h('a', { href }, text);

// ================================================================== recherche, véhicules, organisations
function plateModal(ctx, pl, people) {
  const v = pl || {};
  const plate = input({ maxlength: 10, value: v.plate || '', placeholder: 'Ex. 7ABC123', autocapitalize: 'characters' }), model = input({ maxlength: 40, value: v.model || '' }), color = input({ maxlength: 20, value: v.color || '' });
  const owner = select([['', 'Inconnu']].concat(people.map(p => [p.id, fullName(p) + (p.alias ? ' « ' + p.alias + ' »' : '')])), v.owner_id || '');
  const stolen = h('input', { type: 'checkbox', checked: !!v.stolen }), wanted = h('input', { type: 'checkbox', checked: !!v.wanted }), notes = area({ maxlength: 1000, value: v.notes || '' });
  const ok = h('button', { class: 'btn primary' }, pl ? 'Enregistrer' : 'Ficher le véhicule');
  const md = modal(pl ? 'Véhicule ' + pl.plate : 'Ficher un véhicule', h('div', { class: 'row' }, field('Plaque', plate), field('Modèle', model, 'grow'), field('Couleur', color, 'grow')), field('Propriétaire', owner),
    h('div', { class: 'row' }, h('label', { class: 'check' }, stolen, 'Signalé volé'), h('label', { class: 'check' }, wanted, 'Véhicule recherché')), field('Notes', notes), foot({ close: () => md.close() }, ok));
  ok.addEventListener('click', () => run(ok, async () => {
    const id = await rpc('save_plate', { p_org: ctx.me.id, p_id: pl ? pl.id : null, p_plate: plate.value, p_model: model.value, p_color: color.value, p_owner: owner.value || null, p_stolen: stolen.checked, p_wanted: wanted.checked, p_notes: notes.value });
    md.close(); if (pl) ctx.reload(); else ctx.go(base(ctx) + '/vehicules/' + id); return { message: pl ? 'Véhicule enregistré.' : 'Véhicule fiché.' };
  }));
}
function gangModal(ctx, g) {
  const v = g || {};
  const name = input({ maxlength: 60, value: v.name || '' }), color = h('input', { type: 'color', value: v.color || '#6458b0', class: 'swatchin' });
  const territory = input({ maxlength: 300, value: v.territory || '', placeholder: 'Ex. Grove Street, Davis' }), activities = area({ maxlength: 500, value: v.activities || '', placeholder: 'Trafic, braquages…' }), notes = area({ maxlength: 2000, value: v.notes || '' });
  const ok = h('button', { class: 'btn primary' }, g ? 'Enregistrer' : 'Créer la fiche');
  const md = modal(g ? g.name : 'Nouvelle organisation', h('div', { class: 'row' }, field('Nom', name, 'grow'), field('Couleur', color)), field('Territoire', territory), field('Activités', activities), field('Notes', notes), foot({ close: () => md.close() }, ok));
  ok.addEventListener('click', () => run(ok, async () => {
    const id = await rpc('save_gang', { p_org: ctx.me.id, p_id: g ? g.id : null, p_name: name.value, p_color: color.value, p_territory: territory.value, p_activities: activities.value, p_notes: notes.value });
    md.close(); if (g) ctx.reload(); else ctx.go(base(ctx) + '/organisations/' + id); return { message: 'Organisation enregistrée.' };
  }));
}
const plateFlags = v => h('span', { class: 'pills' }, v.stolen ? pill('Volé', 'bad') : null, v.wanted ? pill('Recherché', 'bad') : null);
const swatch = c => h('i', { class: 'swatch', style: 'background:' + (c || 'var(--chip)'), 'aria-hidden': 'true' });
const DOC = { dossier: ['Dossier', '/dossiers/'], mandat: ['Mandat', null], rapport: ['Rapport', '/rapports/'], preuve: ['Preuve', '/preuves/'] };

export async function recherche(ctx) {
  const { me, can } = ctx;
  const tab = S.filters.rech === 'organisations' ? 'organisations' : 'vehicules', f = S.filters.rechF || 'tous';
  const [plates, gangs, people] = await Promise.all([rows('plates', q => q.eq('org_id', me.id).order('plate')), rows('gangs', q => q.eq('org_id', me.id).order('name')), load.people(ctx)]);
  const pOf = id => people.find(p => p.id === id);
  const results = h('div', { class: 'results', 'aria-live': 'polite' });
  const registry = h('div', { class: 'stack' });
  let timer = null, seq = 0;
  const section = (title, n, ...items) => h('section', { class: 'card' }, h('h2', {}, title, h('span', { class: 'muted small' }, String(n))), ...items);
  const runSearch = async q => {
    S.filters.rechQ = q;
    if (q.trim().length < 2) { results.replaceChildren(); registry.hidden = false; return; }
    const my = ++seq;
    let r;
    try { r = await rpc('mdt_search', { p_org: me.id, p_q: q.trim() }); } catch (e) { ctx.fail(e); return; }
    if (my !== seq) return;
    const urls = await signedUrls(r.people.map(p => p.photo));
    registry.hidden = true;
    const total = r.people.length + r.plates.length + r.gangs.length + r.docs.length;
    results.replaceChildren(...(total ? [
      r.people.length ? section('Individus', r.people.length, h('ul', { class: 'people' }, r.people.map(p => h('li', {}, h('a', { class: 'person', href: base(ctx) + '/individus/' + p.id }, face(p.photo, urls, fullName(p), 'sm'),
        h('span', { class: 'art' }, h('b', {}, fullName(p)), h('small', {}, [p.alias ? '« ' + p.alias + ' »' : null, p.phone, p.gang].filter(Boolean).join(' · ') || KIND[p.kind][0]))), pill(...DANGER[p.danger]))))) : null,
      r.plates.length ? section('Véhicules', r.plates.length, h('ul', { class: 'people' }, r.plates.map(v => h('li', {}, h('a', { class: 'person', href: base(ctx) + '/vehicules/' + v.id }, h('span', { class: 'plate' }, v.plate),
        h('span', { class: 'art' }, h('b', {}, v.model || 'Modèle inconnu'), h('small', {}, [v.color, v.owner ? 'à ' + v.owner : 'propriétaire inconnu'].filter(Boolean).join(' · ')))), plateFlags(v))))) : null,
      r.gangs.length ? section('Organisations', r.gangs.length, h('ul', { class: 'people' }, r.gangs.map(g => h('li', {}, swatch(g.color), linkTo(base(ctx) + '/organisations/' + g.id, h('b', {}, g.name)))))) : null,
      r.docs.length ? section('Documents', r.docs.length, h('ul', { class: 'people' }, r.docs.map(d => h('li', {}, pill(DOC[d.type][0]),
        DOC[d.type][1] ? linkTo(base(ctx) + DOC[d.type][1] + d.id, h('b', {}, d.num)) : h('a', { href: base(ctx) + '/mandats', onclick: () => { S.filters.manQ = d.num; S.filters.manF = 'tous'; } }, h('b', {}, d.num)),
        h('span', { class: 'muted small' }, d.title))))) : null]
      : [h('section', { class: 'card' }, h('div', { class: 'empty' }, 'Aucun résultat pour « ' + q.trim() + ' ».'))]).filter(Boolean));
  };
  const box = search(S.filters.rechQ, 'Nom, surnom, plaque, téléphone, numéro de dossier, de mandat, de rapport ou de scellé', v => { clearTimeout(timer); timer = setTimeout(() => runSearch(v), 250); });
  const shown = plates.filter(v => f === 'tous' || (f === 'voles' ? v.stolen : v.wanted));
  registry.append(tabs([['vehicules', 'Véhicules (' + plates.length + ')'], ['organisations', 'Organisations (' + gangs.length + ')']], tab, k => { S.filters.rech = k; recherche(ctx).catch(ctx.fail); }),
    tab === 'vehicules'
      ? h('section', { class: 'card' }, h('div', { class: 'cardhead' }, fchips([['tous', 'Tous'], ['voles', 'Volés'], ['recherches', 'Recherchés']], f, k => { S.filters.rechF = k; recherche(ctx).catch(ctx.fail); }, 'Véhicules'),
        can('dossiers') ? h('button', { class: 'btn', type: 'button', onclick: () => plateModal(ctx, null, people) }, ico('ajout', 18), 'Ficher un véhicule') : null),
        table([['Plaque', v => h('span', { class: 'plate' }, v.plate), null, v => v.plate], ['Véhicule', v => [v.model, v.color].filter(Boolean).join(' · ') || '—'],
          ['Propriétaire', v => v.owner_id ? fullName(pOf(v.owner_id)) : h('span', { class: 'muted' }, 'Inconnu')], ['', v => plateFlags(v)], ['Mis à jour', v => day(v.updated_at), 'num', v => v.updated_at]],
        shown, v => ctx.go(base(ctx) + '/vehicules/' + v.id), 'Aucun véhicule fiché.'))
      : h('section', { class: 'card' }, h('div', { class: 'cardhead' }, h('p', { class: 'muted' }, 'Les organisations se créent aussi toutes seules depuis le champ « Gang » des fiches.'),
        can('dossiers') ? h('button', { class: 'btn', type: 'button', onclick: () => gangModal(ctx, null) }, ico('ajout', 18), 'Nouvelle organisation') : null),
        gangs.length ? h('div', { class: 'gangs' }, gangs.map(g => h('a', { class: 'gangcard', href: base(ctx) + '/organisations/' + g.id, style: '--gc:' + (g.color || 'var(--chip)') },
          h('b', {}, g.name), h('span', { class: 'muted small' }, people.filter(p => p.gang_id === g.id).length + ' membre(s) connu(s)'), g.territory ? h('span', { class: 'small' }, g.territory) : null)))
          : h('div', { class: 'empty' }, 'Aucune organisation.')));
  ctx.show('recherche', 'Recherche', 'Individus, véhicules, organisations et documents', null, h('section', { class: 'card searchcard' }, box), results, registry);
  const field0 = document.querySelector('.searchcard .find-in');
  if (field0 && !matchMedia('(max-width: 700px)').matches) field0.focus();
  if (S.filters.rechQ && S.filters.rechQ.trim().length >= 2) runSearch(S.filters.rechQ);
}

export async function vehicule(ctx, id) {
  const { me, can } = ctx;
  const [pls, log, people] = await Promise.all([rows('plates', q => q.eq('id', id)), rows('plate_log', q => q.eq('plate_id', id).order('at', { ascending: false })), load.people(ctx)]);
  const v = pls[0];
  if (!v) return ctx.go(base(ctx) + '/recherche', true);
  const owner = people.find(p => p.id === v.owner_id);
  const note = () => {
    const kind = select([['observation', 'Observation (vu à…)'], ['note', 'Note']], 'observation'), t = area({ maxlength: 500, placeholder: 'Lieu, heure, conducteur…' }), ok = h('button', { class: 'btn primary' }, 'Ajouter');
    const md = modal('Véhicule ' + v.plate, field('Type', kind), field('Texte', t), foot({ close: () => md.close() }, ok));
    ok.addEventListener('click', () => run(ok, async () => { await rpc('plate_note', { p_plate: v.id, p_kind: kind.value, p_text: t.value }); md.close(); ctx.reload(); return { message: 'Ajouté à l’historique.' }; }));
  };
  const info = (k, val) => [h('dt', {}, k), h('dd', {}, val)];
  ctx.show('recherche', v.plate + (v.model ? ' · ' + v.model : ''), [plateFlags(v), ' Fiché le ' + day(v.created_at)],
    [h('button', { class: 'btn', type: 'button', onclick: note }, ico('localisation', 18), 'Observation'), can('dossiers') ? h('button', { class: 'btn', type: 'button', onclick: () => plateModal(ctx, v, people) }, 'Modifier') : null,
      h('a', { class: 'btn ghost', href: base(ctx) + '/recherche' }, 'Recherche')],
    h('div', { class: 'grid cols2' },
      h('section', { class: 'card' }, h('h2', {}, 'Fiche'), h('div', { class: 'platebig' }, h('span', { class: 'plate' }, v.plate)),
        h('dl', { class: 'lines-dl rules' }, info('Modèle', v.model || '—'), info('Couleur', v.color || '—'), info('Propriétaire', owner ? linkTo(base(ctx) + '/individus/' + owner.id, fullName(owner)) : 'Inconnu'),
          info('Statut', v.stolen || v.wanted ? plateFlags(v) : 'Rien à signaler')), v.notes ? h('p', { class: 'prewrap' }, v.notes) : null),
      h('section', { class: 'card' }, h('h2', {}, 'Historique'), feed(log.map(l => [l.at, l.kind, l.text, l.by_name, PLATE_LOG])))));
}

export async function organisation(ctx, id) {
  const { can } = ctx;
  const [gs, people, cases] = await Promise.all([rows('gangs', q => q.eq('id', id)), load.people(ctx), rows('cases', q => q.eq('org_id', ctx.me.id))]);
  const g = gs[0];
  if (!g) return ctx.go(base(ctx) + '/recherche', true);
  const members = people.filter(p => p.gang_id === g.id), urls = await signedUrls(members.map(p => p.photo));
  const linked = cases.filter(c => OPEN.includes(c.status) && members.some(p => p.id === c.person_id));
  ctx.show('recherche', g.name, [swatch(g.color), ' ', members.length + ' membre(s) connu(s) · ' + linked.length + ' dossier(s) actif(s)'],
    [can('dossiers') ? h('button', { class: 'btn', type: 'button', onclick: () => gangModal(ctx, g) }, 'Modifier') : null, h('a', { class: 'btn ghost', href: base(ctx) + '/recherche' }, 'Recherche')],
    h('div', { class: 'grid cols2' },
      h('section', { class: 'card' }, h('h2', {}, 'Fiche'), h('dl', { class: 'lines-dl rules' }, h('dt', {}, 'Territoire'), h('dd', {}, g.territory || '—'), h('dt', {}, 'Activités'), h('dd', {}, g.activities || '—')),
        g.notes ? h('p', { class: 'prewrap' }, g.notes) : null),
      h('section', { class: 'card' }, h('h2', {}, 'Membres connus'), members.length ? h('ul', { class: 'people' }, members.map(p => h('li', {}, h('a', { class: 'person grow', href: base(ctx) + '/individus/' + p.id },
        face(p.photo, urls, fullName(p), 'sm'), h('span', { class: 'art' }, h('b', {}, fullName(p)), p.alias ? h('small', {}, '« ' + p.alias + ' »') : null)), pill(...DANGER[p.danger]))))
        : h('div', { class: 'empty' }, 'Aucun membre connu : renseigne le champ « Gang » des fiches.'))),
    h('section', { class: 'card' }, h('h2', {}, 'Dossiers actifs'), table([['N°', c => h('b', { class: 'nowrap' }, c.num)], ['Individu', c => fullName(people.find(p => p.id === c.person_id))], ['Titre', c => c.title],
      ['Statut', c => pill(...STATUS[c.status])]], linked, c => ctx.go(base(ctx) + '/dossiers/' + c.id), 'Aucun dossier actif.')));
}

// ---------------------------------------------------------------- fiche individu : entourage, casier, véhicules, rapports, preuves
export async function personCards(ctx, p, people) {
  const { me, has, can } = ctx;
  const [l1, l2, plates, reports, evidence] = await Promise.all([rows('person_links', q => q.eq('person_id', p.id)), rows('person_links', q => q.eq('other_id', p.id)),
    rows('plates', q => q.eq('owner_id', p.id)), has('rapports') ? rows('reports', q => q.eq('person_id', p.id).order('occurred_at', { ascending: false })) : [],
    has('preuves') ? rows('evidence', q => q.eq('person_id', p.id).order('seized_at', { ascending: false })) : []]);
  const links = l1.concat(l2).map(l => ({ ...l, other: people.find(x => x.id === (l.person_id === p.id ? l.other_id : l.person_id)) })).filter(l => l.other);
  const urls = await signedUrls(links.map(l => l.other.photo));
  const linkModal = () => {
    const others = people.filter(x => x.id !== p.id && !links.some(l => l.other.id === x.id));
    const who = select(others.map(x => [x.id, fullName(x) + (x.alias ? ' « ' + x.alias + ' »' : '')]), others[0] && others[0].id), kind = select(Object.entries(LINK), 'complice'), note = input({ maxlength: 160 });
    const ok = h('button', { class: 'btn primary', disabled: !others.length }, 'Lier');
    const md = modal('Entourage de ' + fullName(p), others.length ? field('Personne', who) : h('p', { class: 'muted' }, 'Crée d’abord la fiche de la personne.'), field('Lien', kind), field('Précision (facultatif)', note), foot({ close: () => md.close() }, ok));
    ok.addEventListener('click', () => run(ok, async () => { await rpc('link_people', { p_a: p.id, p_b: who.value, p_kind: kind.value, p_note: note.value }); md.close(); ctx.reload(); return { message: 'Lien ajouté.' }; }));
  };
  const arrests = reports.filter(r => r.kind === 'arrestation' && r.status === 'valide');
  const fine = arrests.reduce((a, r) => a + Number(r.total_fine), 0), jail = arrests.reduce((a, r) => a + Number(r.total_jail), 0);
  return [
    h('div', { class: 'grid cols2' },
      h('section', { class: 'card' }, h('h2', {}, 'Entourage', can('dossiers') ? h('span', { class: 'right' }, h('button', { class: 'btn small', type: 'button', onclick: linkModal }, ico('ajout', 16), 'Lier')) : null),
        links.length ? h('ul', { class: 'people' }, links.map(l => h('li', {}, h('a', { class: 'person grow', href: base(ctx) + '/individus/' + l.other.id }, face(l.other.photo, urls, fullName(l.other), 'sm'),
          h('span', { class: 'art' }, h('b', {}, fullName(l.other)), h('small', {}, LINK[l.kind] + (l.note ? ' · ' + l.note : '')))),
          can('dossiers') ? h('button', { class: 'btn small ghost', type: 'button', 'aria-label': 'Retirer le lien avec ' + fullName(l.other), onclick: ev => run(ev.currentTarget, async () => { await rpc('unlink_people', { p_a: p.id, p_b: l.other.id }); ctx.reload(); return { message: 'Lien retiré.' }; }) }, ico('moins', 16)) : null)))
          : h('div', { class: 'empty' }, 'Aucun proche ni complice connu.')),
      h('section', { class: 'card' }, h('h2', {}, 'Véhicules'), plates.length ? h('ul', { class: 'people' }, plates.map(v => h('li', {}, h('a', { class: 'person grow', href: base(ctx) + '/vehicules/' + v.id }, h('span', { class: 'plate' }, v.plate),
        h('span', { class: 'art' }, h('b', {}, v.model || 'Modèle inconnu'), v.color ? h('small', {}, v.color) : null)), plateFlags(v))))
        : h('div', { class: 'empty' }, 'Aucun véhicule connu.'))),
    has('rapports') ? h('section', { class: 'card' }, h('h2', {}, 'Casier judiciaire', h('span', { class: 'muted small' }, arrests.length + ' condamnation(s) enregistrée(s)')),
      arrests.length ? [strip([['Arrestations', String(arrests.length)], ['Amendes', money(fine)], ['Peines cumulées', months(jail)]]),
        table([['Date', r => day(r.occurred_at), 'num', r => r.occurred_at], ['Rapport', r => linkTo(base(ctx) + '/rapports/' + r.id, h('b', { class: 'nowrap' }, r.num))],
          ['Infractions', r => r.charges.map(c => c.code + (c.qty > 1 ? ' ×' + c.qty : '')).join(', ')], ['Amende', r => money(r.total_fine), 'num', r => Number(r.total_fine)], ['Peine', r => months(r.total_jail), 'num', r => r.total_jail]], arrests)]
        : h('div', { class: 'calm' }, h('span', { class: 'ic' }, ico('coche')), 'Casier vierge : aucune arrestation validée.')) : null,
    reports.length || evidence.length ? h('div', { class: 'grid cols2' },
      has('rapports') ? h('section', { class: 'card' }, h('h2', {}, 'Rapports'), reportsTable(ctx, reports, true)) : null,
      has('preuves') ? h('section', { class: 'card' }, h('h2', {}, 'Preuves'), evidenceTable(ctx, evidence, true)) : null) : null];
}

// ---------------------------------------------------------------- dossier : rapports et preuves liés
export async function caseCards(ctx, c) {
  const { has } = ctx;
  const [reports, evidence] = await Promise.all([has('rapports') ? rows('reports', q => q.eq('case_id', c.id).order('occurred_at', { ascending: false })) : [],
    has('preuves') ? rows('evidence', q => q.eq('case_id', c.id).order('seized_at', { ascending: false })) : []]);
  return {
    buttons: [has('rapports') ? h('button', { class: 'btn', type: 'button', onclick: () => newReport(ctx, { kind: 'arrestation', case: c.id, person: c.person_id }) }, ico('rapport', 18), 'Rédiger un rapport') : null,
      has('preuves') && ctx.can('dossiers') ? h('button', { class: 'btn', type: 'button', onclick: () => evidenceModal(ctx, { case_id: c.id, person_id: c.person_id }) }, ico('scelle', 18), 'Enregistrer une saisie') : null],
    cards: reports.length || evidence.length ? [h('div', { class: 'grid cols2' },
      has('rapports') ? h('section', { class: 'card' }, h('h2', {}, 'Rapports', h('span', { class: 'muted small' }, String(reports.length))), reportsTable(ctx, reports, true)) : null,
      has('preuves') ? h('section', { class: 'card' }, h('h2', {}, 'Preuves', h('span', { class: 'muted small' }, String(evidence.length))), evidenceTable(ctx, evidence, true)) : null)] : [],
  };
}

// ================================================================== rapports
export const newReport = (ctx, preset) => { S.reportDraft = Object.assign({ kind: 'arrestation' }, preset || {}); ctx.go(base(ctx) + '/rapports/nouveau'); };
function reportsTable(ctx, list, short) {
  return table([['N°', r => h('b', { class: 'nowrap' }, r.num), null, r => r.num], ['Type', r => RTYPES[r.kind].label.replace(/^Rapport d[’e]\s?/, '').replace(/^./, x => x.toUpperCase())],
    ['Titre', r => r.title], short ? null : ['Auteur', r => r.author_name], ['Statut', r => h('span', { class: 'pills' }, statusPill(r)), null, r => r.status], ['Date', r => day(r.occurred_at), 'num', r => r.occurred_at]].filter(Boolean),
  list, r => ctx.go(base(ctx) + '/rapports/' + r.id), 'Aucun rapport.');
}
// alerte du tableau de bord : brouillons de plus de 24 h, rapports à valider
export async function reportAlerts(ctx) {
  if (!ctx.has('rapports')) return { banner: null, pending: null };
  const list = await rows('reports', q => q.eq('org_id', ctx.me.id).order('created_at', { ascending: false }).limit(300));
  const mine = list.filter(r => r.author_id === ctx.me.member_id && late(r)), pending = list.filter(r => r.status === 'soumis' && r.author_id !== ctx.me.member_id);
  return {
    banner: mine.length ? h('div', { class: 'banner' }, ico('rapport', 20), h('div', {}, h('b', {}, mine.length + ' rapport(s) à rendre'), h('span', {}, 'En brouillon ou à corriger depuis plus de 24 heures : ' + mine.map(r => r.num).join(', ') + '.')),
      h('a', { class: 'btn small', href: base(ctx) + '/rapports' }, 'Mes rapports')) : null,
    pending: ctx.can('valider') ? pending.length : null,
  };
}

export async function rapports(ctx) {
  const { me, can } = ctx;
  const tab = ['mine', 'valider', 'tous', 'code'].includes(S.filters.rap) ? S.filters.rap : 'mine';
  const [list, penal] = await Promise.all([rows('reports', q => q.eq('org_id', me.id).order('created_at', { ascending: false }).limit(500)), rows('penal_code', q => q.eq('org_id', me.id).order('code'))]);
  const mine = list.filter(r => r.author_id === me.member_id), pending = list.filter(r => r.status === 'soumis' && r.author_id !== me.member_id), lateMine = mine.filter(late);
  const setTab = k => { S.filters.rap = k; rapports(ctx).catch(ctx.fail); };
  const box = h('div', {});
  const paintCode = () => {
    const q = S.filters.penQ;
    box.replaceChildren(table([['Code', x => h('b', { class: 'nowrap' }, x.code), null, x => x.code], ['Infraction', x => h('span', { class: 'art' }, x.title, x.archived ? h('small', {}, 'Retirée') : null)],
      ['Catégorie', x => pill(CAT[x.category], x.category === 'crime' ? 'bad' : x.category === 'delit' ? 'warn' : null), null, x => ['contravention', 'delit', 'crime'].indexOf(x.category)],
      ['Amende', x => money(x.fine), 'num', x => Number(x.fine)], ['Peine', x => months(x.jail), 'num', x => x.jail]],
    penal.filter(x => match(q, x.code, x.title, CAT[x.category])), can('equipe') ? x => penalModal(ctx, x) : null, 'Aucune infraction.'));
  };
  if (tab === 'code') paintCode();
  ctx.show('rapports', 'Rapports', mine.length + ' rapport(s) rédigé(s) · ' + pending.length + ' à valider',
    h('button', { class: 'btn primary', type: 'button', onclick: () => newReport(ctx, null) }, ico('ajout', 18), 'Nouveau rapport'),
    lateMine.length ? h('div', { class: 'banner bad' }, ico('alerte', 20), h('div', {}, h('b', {}, lateMine.length + ' rapport(s) non rendu(s) depuis plus de 24 heures'), h('span', {}, lateMine.map(r => r.num + ' · ' + r.title).join(' ; ')))) : null,
    h('section', { class: 'card' }, strip([['Mes brouillons', String(mine.filter(r => r.status === 'brouillon').length)], ['À corriger', String(mine.filter(r => r.status === 'a_corriger').length), mine.some(r => r.status === 'a_corriger') ? 'warn-t' : null],
      ['À valider', String(pending.length), can('valider') && pending.length ? 'warn-t' : null], ['Validés ce mois', String(list.filter(r => r.status === 'valide' && ms(r.reviewed_at) > Date.now() - 30 * DAY).length)]])),
    tabs([['mine', 'Mes rapports'], can('valider') ? ['valider', 'À valider (' + pending.length + ')'] : null, ['tous', 'Tous'], ['code', 'Code pénal']], tab, setTab),
    tab === 'code'
      ? h('section', { class: 'card' }, h('div', { class: 'cardhead' }, search(S.filters.penQ, 'Chercher une infraction', v => { S.filters.penQ = v; paintCode(); }),
        can('equipe') ? h('button', { class: 'btn', type: 'button', onclick: () => penalModal(ctx, null) }, ico('ajout', 18), 'Ajouter une infraction') : null), box,
        h('p', { class: 'note' }, 'Les rapports recopient l’amende et la peine au moment où ils sont enregistrés : changer le code pénal ne modifie pas les rapports déjà écrits.'))
      : h('section', { class: 'card' }, reportsTable(ctx, tab === 'mine' ? mine : tab === 'valider' ? pending : list.filter(r => r.status !== 'brouillon' || r.author_id === me.member_id), tab === 'mine')));
}
function penalModal(ctx, x) {
  const v = x || {};
  const code = input({ maxlength: 10, value: v.code || '', placeholder: 'D-14', autocapitalize: 'characters' }), title = input({ maxlength: 120, value: v.title || '' });
  const cat = select(Object.entries(CAT), v.category || 'delit'), fine = h('input', { type: 'number', min: 0, step: 50, value: v.fine === undefined ? 0 : Number(v.fine), class: 'short' }), jail = h('input', { type: 'number', min: 0, value: v.jail || 0, class: 'short' });
  const archived = x ? h('input', { type: 'checkbox', checked: !!x.archived }) : null, ok = h('button', { class: 'btn primary' }, 'Enregistrer');
  const md = modal(x ? x.code + ' · ' + x.title : 'Nouvelle infraction', h('div', { class: 'row' }, field('Code', code), field('Catégorie', cat, 'grow')), field('Intitulé', title),
    h('div', { class: 'row' }, field('Amende ($)', fine), field('Peine (mois)', jail)), archived ? h('label', { class: 'check' }, archived, 'Retirée du code (ne se choisit plus dans les rapports)') : null, foot({ close: () => md.close() }, ok));
  ok.addEventListener('click', () => run(ok, async () => {
    await rpc('save_penal', { p_org: ctx.me.id, p_id: x ? x.id : null, p_code: code.value, p_title: title.value, p_category: cat.value, p_fine: Number(fine.value) || 0, p_jail: Number(jail.value) || 0, p_archived: archived ? archived.checked : false });
    md.close(); ctx.reload(); return { message: 'Code pénal mis à jour.' };
  }));
}

// sélection des infractions : recherche dans le code pénal, quantités, totaux en direct
function chargesPicker(penal, chosen) {
  const q = input({ placeholder: 'Chercher une infraction : code ou intitulé', 'aria-label': 'Chercher une infraction', class: 'find-in' });
  const hits = h('ul', { class: 'penal-hits' }), sel = h('div', {}), tot = h('p', { class: 'charges-total', 'aria-live': 'polite' });
  const pOf = id => penal.find(x => x.id === id);
  const paintHits = () => {
    const s = q.value.trim().toLowerCase();
    hits.replaceChildren(...penal.filter(x => !x.archived && (!s || (x.code + ' ' + x.title).toLowerCase().includes(s))).slice(0, s ? 12 : 5).map(x => h('li', {},
      h('span', { class: 'art grow' }, h('b', {}, x.code + ' · ' + x.title), h('small', {}, CAT[x.category] + ' · ' + money(x.fine) + ' · ' + months(x.jail))),
      h('button', { class: 'btn small', type: 'button', 'aria-label': 'Ajouter ' + x.code, onclick: () => { const c = chosen.find(y => y.id === x.id); if (c) c.qty = Math.min(20, c.qty + 1); else chosen.push({ id: x.id, qty: 1 }); paintSel(); } }, ico('ajout', 16)))));
  };
  const paintSel = () => {
    const lines = chosen.map(c => [c, pOf(c.id)]).filter(([, x]) => x);
    sel.replaceChildren(lines.length ? h('div', { class: 'scroll' }, h('table', { class: 'charges-t' }, h('thead', {}, h('tr', {}, h('th', {}, 'Infraction'), h('th', { class: 'num' }, 'Nombre'), h('th', { class: 'num' }, 'Amende'), h('th', { class: 'num' }, 'Peine'), h('th', {}))),
      h('tbody', {}, lines.map(([c, x]) => {
        const n = h('input', { type: 'number', min: 1, max: 20, value: c.qty, class: 'tiny', 'aria-label': 'Nombre pour ' + x.code });
        n.addEventListener('change', () => { c.qty = Math.min(20, Math.max(1, Number(n.value) || 1)); paintSel(); });
        return h('tr', {}, h('td', {}, h('b', {}, x.code), ' ', x.title), h('td', { class: 'num' }, n), h('td', { class: 'num' }, money(x.fine * c.qty)), h('td', { class: 'num' }, months(x.jail * c.qty)),
          h('td', {}, h('button', { class: 'btn small ghost', type: 'button', 'aria-label': 'Retirer ' + x.code, onclick: () => { chosen.splice(chosen.indexOf(c), 1); paintSel(); } }, ico('moins', 16))));
      })))) : h('p', { class: 'muted small' }, 'Aucune infraction retenue.'));
    const f = lines.reduce((a, [c, x]) => a + Number(x.fine) * c.qty, 0), j = lines.reduce((a, [c, x]) => a + x.jail * c.qty, 0);
    tot.textContent = 'Amende totale : ' + money(f) + ' · Peine totale : ' + months(j);
  };
  q.addEventListener('input', paintHits);
  paintHits(); paintSel();
  return h('div', { class: 'charges' }, h('div', { class: 'findbar' }, ico('loupe', 18), q), hits, sel, tot);
}

export async function rapport(ctx, id) {
  const { me, can, has } = ctx;
  const isNew = id === 'nouveau';
  const r = isNew ? null : (await rows('reports', q => q.eq('id', id)))[0];
  if (!isNew && !r) return ctx.go(base(ctx) + '/rapports', true);
  const editable = isNew || (r.author_id === me.member_id && ['brouillon', 'a_corriger'].includes(r.status));
  const [people, cases, members, penal, templates] = await Promise.all([load.people(ctx), has('dossiers') ? rows('cases', q => q.eq('org_id', me.id).order('opened_at', { ascending: false })) : [], load.members(ctx),
    editable ? rows('penal_code', q => q.eq('org_id', me.id).order('code')) : [], isNew && 'ia' in me ? rows('doc_templates', q => q.eq('org_id', me.id)) : []]);
  return editable ? reportEditor(ctx, r, { people, cases, members, penal, templates }) : reportView(ctx, r, { people, cases, members });
}

function reportEditor(ctx, r, d) {
  const { me } = ctx;
  const draft = r ? null : Object.assign({ kind: 'arrestation' }, S.reportDraft || {});
  S.reportDraft = null;
  let kind = r ? r.kind : RTYPES[draft.kind] ? draft.kind : 'arrestation';
  const state = r ? { person: r.person_id, case: r.case_id, fields: r.fields || {}, charges: (r.charges || []).map(c => ({ id: c.id, qty: c.qty })), firearm: r.firearm, participants: r.participants || [] }
    : { person: draft.person || null, case: draft.case || null, fields: {}, charges: [], firearm: false, participants: [] };
  const title = input({ maxlength: 120, value: r ? r.title : '' });
  let titleTouched = !!r;
  title.addEventListener('input', () => { titleTouched = true; });
  const when = h('input', { type: 'datetime-local', value: toLocal(r ? ms(r.occurred_at) : Date.now()) }), place = input({ maxlength: 120, value: r && r.place ? r.place : '', placeholder: 'Ex. Sandy Shores, station-service' });
  const narrative = area({ maxlength: 6000, rows: 9, value: r ? r.narrative : '', placeholder: 'Le déroulé, dans l’ordre : qui, quoi, où, quand, comment.', 'aria-label': 'Récit' });
  const cas = select([['', 'Aucun']].concat(d.cases.filter(c => OPEN.includes(c.status) || c.id === state.case).map(c => [c.id, c.num + ' · ' + c.title])), state.case || '');
  const form = h('div', { class: 'stack' });
  let get = () => ({});
  const autoTitle = per => { if (!titleTouched) { const p = d.people.find(x => x.id === (per && per.value)); title.value = RTYPES[kind].label + (p ? ' · ' + fullName(p) : ''); } };
  // un nouveau rapport part du modèle de récit du service (Réglages → Modèles de documents), tant qu'on ne l'a pas réécrit
  let tpl = '';
  const useTemplate = () => { if (r || (narrative.value.trim() && narrative.value !== tpl)) return; const t = ((d.templates || []).find(x => x.kind === 'r_' + kind) || {}).body; tpl = (t && t.text) || ''; narrative.value = tpl; };
  const paint = () => {
    useTemplate();
    const T = RTYPES[kind], els = {};
    const per = T.person ? select([['', T.needPerson ? 'Choisir…' : 'Aucune']].concat(d.people.map(p => [p.id, fullName(p) + (p.alias ? ' « ' + p.alias + ' »' : '')])), state.person || '') : null;
    if (per) per.addEventListener('change', () => { state.person = per.value || null; autoTitle(per); });
    const fire = T.firearm ? h('input', { type: 'checkbox', checked: !!state.firearm }) : null;
    const ctl = ([k, label, type, req]) => {
      const lab = label + (req ? ' (obligatoire)' : '');
      if (type === 'check') { const el = h('input', { type: 'checkbox', checked: state.fields[k] === 'true' }); els[k] = () => String(el.checked); return h('label', { class: 'check' }, el, lab); }
      let el;
      if (type.startsWith('select:')) {
        el = select([['', '—']].concat(type.slice(7).split('|').map(o => [o, o])), state.fields[k] || '');
        if (k === 'force_type' && fire) el.addEventListener('change', () => { if (el.value === 'Arme à feu') fire.checked = true; });
      } else el = type === 'area' ? area({ maxlength: 1000, value: state.fields[k] || '' }) : input({ maxlength: 160, value: state.fields[k] || '' });
      els[k] = () => el.value.trim();
      return field(lab, el);
    };
    const chosen = state.charges;
    const parts = T.participants ? d.members.filter(m => m.active && m.id !== me.member_id).map(m => [m, h('input', { type: 'checkbox', checked: state.participants.includes(m.id) })]) : null;
    form.replaceChildren(...[
      h('section', { class: 'card stack' }, h('h2', {}, 'Faits'), field('Titre', title), h('div', { class: 'row' }, field('Date et heure', when), field('Lieu' + (T.needPlace ? ' (obligatoire)' : ''), place, 'grow')),
        h('div', { class: 'row' }, per ? field(T.person + (T.needPerson ? ' (obligatoire)' : ''), per, 'grow') : null, ctx.has('dossiers') ? field('Dossier', cas, 'grow') : null),
        T.fields.length ? h('div', { class: 'rfields' }, T.fields.map(ctl)) : null,
        fire ? h('label', { class: 'check firecheck' }, fire, 'Une arme à feu a été utilisée (un dossier Affaires internes s’ouvre à la soumission)') : null),
      T.charges ? h('section', { class: 'card' }, h('h2', {}, 'Infractions retenues'), chargesPicker(d.penal, chosen)) : null,
      parts ? h('section', { class: 'card' }, h('h2', {}, 'Agents présents'), parts.length ? h('div', { class: 'checks' }, parts.map(([m, cb]) => h('label', { class: 'check' }, cb, (m.callsign ? m.callsign + ' · ' : '') + m.name))) : h('p', { class: 'muted' }, 'Aucun autre agent.')) : null,
      h('section', { class: 'card stack' }, h('h2', {}, 'Récit'), narrative, h('p', { class: 'note' }, '20 caractères au moins. Écris ce que tu as constaté, pas ce que tu supposes.'))].filter(Boolean));
    get = () => ({ person: per ? per.value || null : null, fields: Object.fromEntries(Object.entries(els).map(([k, g]) => [k, g()])), firearm: fire ? fire.checked : false,
      participants: parts ? parts.filter(([, cb]) => cb.checked).map(([m]) => m.id) : [] });
    autoTitle(per);
  };
  const seg = r ? null : h('div', { class: 'seg wrap', role: 'group', 'aria-label': 'Type de rapport' });
  const paintSeg = () => seg && seg.replaceChildren(...Object.entries(RTYPES).map(([k, T]) => h('button', { type: 'button', class: k === kind ? 'on' : null, 'aria-pressed': String(k === kind),
    onclick: () => { if (k === kind) return; const g = get(); state.person = g.person; kind = k; state.fields = {}; paintSeg(); paint(); } }, T.label.replace(/^Rapport d[’e]\s?/, '').replace(/^./, x => x.toUpperCase()))));
  paintSeg(); paint();
  const save = async () => {
    const g = get();
    const res = await rpc('save_report', { p_org: me.id, p_id: r ? r.id : null, p_kind: kind, p_title: title.value.trim(), p_person: g.person, p_case: cas.value || null,
      p_occurred: iso(new Date(when.value).getTime() || Date.now()), p_place: place.value.trim(), p_fields: g.fields, p_narrative: narrative.value, p_charges: state.charges, p_firearm: g.firearm, p_participants: g.participants });
    return res;
  };
  const saveB = h('button', { class: 'btn', type: 'button' }, 'Enregistrer le brouillon'), sendB = h('button', { class: 'btn primary', type: 'button' }, 'Soumettre pour validation');
  const delB = r && r.status === 'brouillon' ? h('button', { class: 'btn danger', type: 'button' }, 'Supprimer le brouillon') : null;
  saveB.addEventListener('click', () => run(saveB, async () => { const res = await save(); if (!r) ctx.go(base(ctx) + '/rapports/' + res.id, true); else ctx.reload(); return { message: 'Brouillon ' + res.num + ' enregistré.' }; }));
  sendB.addEventListener('click', () => run(sendB, async () => {
    const res = await save();
    try { await rpc('submit_report', { p_id: res.id }); } catch (e) { if (!r) ctx.go(base(ctx) + '/rapports/' + res.id, true); throw e; }
    ctx.go(base(ctx) + '/rapports/' + res.id, true); return { message: 'Rapport ' + res.num + ' soumis pour validation.' };
  }));
  if (delB) delB.addEventListener('click', () => run(delB, async () => { await rpc('delete_report', { p_id: r.id }); ctx.go(base(ctx) + '/rapports', true); return { message: 'Brouillon supprimé.' }; }));
  ctx.show('rapports', r ? r.num + ' · ' + r.title : 'Nouveau rapport', r ? h('span', { class: 'pills' }, pill(RTYPES[r.kind].label, 'info'), statusPill(r)) : 'Les champs obligatoires sont vérifiés à la soumission',
    delB,
    r && r.status === 'a_corriger' ? h('div', { class: 'banner bad' }, ico('alerte', 20), h('div', {}, h('b', {}, 'À corriger · ' + r.reviewer), h('span', {}, r.review_note || ''))) : null,
    seg ? h('section', { class: 'card' }, h('h2', {}, 'Type de rapport'), seg) : null, form,
    h('div', { class: 'formfoot' }, h('a', { class: 'btn ghost', href: base(ctx) + '/rapports' }, 'Annuler'), saveB, sendB));
}

function reportView(ctx, r, d) {
  const { me, can } = ctx;
  const T = RTYPES[r.kind], p = d.people.find(x => x.id === r.person_id), c = d.cases.find(x => x.id === r.case_id);
  const parts = (r.participants || []).map(id => d.members.find(m => m.id === id)).filter(Boolean);
  const info = (k, v) => v ? [h('dt', {}, k), h('dd', {}, v)] : null;
  const fieldText = ([k, label, type]) => { const v = (r.fields || {})[k]; if (!v) return null; return [label, type === 'check' ? (v === 'true' ? 'Oui' : 'Non') : v]; };
  const extra = T.fields.map(fieldText).filter(Boolean);
  const review = can('valider') && r.status === 'soumis' && r.author_id !== me.member_id;
  const note = area({ maxlength: 1000, placeholder: 'Remarque pour l’auteur (obligatoire pour renvoyer en correction)', 'aria-label': 'Remarque du valideur' });
  const decide = ok => ev => run(ev.currentTarget, async () => { await rpc('review_report', { p_id: r.id, p_ok: ok, p_note: note.value }); ctx.reload(); return { message: ok ? 'Rapport ' + r.num + ' validé et signé.' : 'Rapport renvoyé à ' + r.author_name + ' pour correction.' }; });
  const mySig = me.signature_style ? { name: me.signature_name, style: me.signature_style, printed: me.member_name, grade: me.grade } : null;
  const authorSig = r.author_signature || (r.status !== 'brouillon' ? { name: r.author_name, style: 'classique', printed: r.author_name } : null);
  const signBox = (label, sig, at, wait) => h('div', { class: 'signbox' }, h('span', { class: 'lbl' }, label), sig ? sigEl(sig, 36) : h('span', { class: 'wait' }, wait), h('i', { class: 'rule' }),
    sig ? [h('b', {}, sig.printed || sig.name), h('small', { class: 'muted' }, [sig.grade, sig.matricule].filter(Boolean).join(' · ')), at ? h('small', { class: 'muted' }, 'Signé le ' + date(ms(at))) : null] : null);
  const exp = () => exportModal({ kind: T.label.toUpperCase(), num: r.num, title: 'Rapport ' + r.num, render: () => reportCanvas(ctx.me, r, { T, person: p, caseNum: c ? c.num + ' · ' + c.title : null, parts, extra }) }, slug(r.num));
  ctx.show('rapports', r.num + ' · ' + r.title, h('span', { class: 'pills' }, pill(T.label, 'info'), statusPill(r)),
    [h('button', { class: 'btn', type: 'button', onclick: exp }, ico('telecharger', 18), 'PNG ou PDF'), h('a', { class: 'btn ghost', href: base(ctx) + '/rapports' }, 'Tous les rapports')],
    r.firearm ? h('div', { class: 'banner bad' }, ico('alerte', 20), h('div', {}, h('b', {}, 'Usage d’une arme à feu'), h('span', {}, r.status === 'brouillon' ? 'Un dossier Affaires internes s’ouvrira à la soumission.' : 'Un dossier Affaires internes a été ouvert automatiquement.'))) : null,
    review ? h('section', { class: 'card stack reviewcard' }, h('h2', {}, 'Validation'), h('p', { class: 'muted', style: 'margin:0' }, 'En validant, ta signature est apposée au bas du rapport.'),
      h('div', { class: 'signs' }, signBox('Ta signature', mySig, null, 'Choisis ta signature depuis ta fiche agent')), note,
      h('div', { class: 'acts' }, h('button', { class: 'btn', type: 'button', onclick: decide(false) }, 'Renvoyer pour correction'),
        h('button', { class: 'btn primary', type: 'button', onclick: decide(true) }, ico('coche', 18), 'Signer et valider'))) : null,
    h('div', { class: 'grid cols2' },
      h('section', { class: 'card' }, h('h2', {}, 'Informations'), h('dl', { class: 'lines-dl rules' }, info('Auteur', r.author_name), info('Date des faits', date(ms(r.occurred_at))), info('Lieu', r.place),
        info(T.person || 'Personne', p ? linkTo(base(ctx) + '/individus/' + p.id, fullName(p)) : null), info('Dossier', c ? linkTo(base(ctx) + '/dossiers/' + c.id, c.num) : null),
        info('Agents présents', parts.map(m => m.callsign || m.name).join(', ')), extra.map(([k, v]) => info(k, v)))),
      h('section', { class: 'card' }, h('h2', {}, 'Suivi'), h('dl', { class: 'lines-dl rules' }, info('Rédigé le', date(ms(r.created_at))), info('Soumis le', r.submitted_at ? date(ms(r.submitted_at)) : null),
        info(r.status === 'valide' ? 'Validé par' : 'Revu par', r.reviewer ? r.reviewer + ' · ' + date(ms(r.reviewed_at)) : null)), r.review_note ? h('p', { class: 'prewrap' }, r.review_note) : null,
        r.status === 'soumis' && !review ? h('p', { class: 'muted' }, 'En attente de validation par un responsable.') : null)),
    r.charges.length ? h('section', { class: 'card' }, h('h2', {}, 'Infractions'), table([['Code', x => h('b', {}, x.code)], ['Infraction', x => x.title], ['Nombre', x => String(x.qty), 'num'],
      ['Amende', x => money(x.fine * x.qty), 'num', x => x.fine * x.qty], ['Peine', x => months(x.jail * x.qty), 'num', x => x.jail * x.qty]], r.charges),
      h('p', { class: 'charges-total' }, 'Amende totale : ' + money(r.total_fine) + ' · Peine totale : ' + months(r.total_jail))) : null,
    h('section', { class: 'card' }, h('h2', {}, 'Récit'), h('p', { class: 'prewrap' }, r.narrative || '—')),
    h('section', { class: 'card' }, h('h2', {}, 'Signatures'), h('div', { class: 'signs' }, signBox('Agent rédacteur', authorSig, r.submitted_at, 'Non signé : brouillon'),
      signBox('Superviseur', r.status === 'valide' ? r.reviewer_signature || { name: r.reviewer, style: 'classique', printed: r.reviewer } : null, r.reviewed_at,
        r.status === 'a_corriger' ? 'Renvoyé pour correction' : 'En attente de validation'))));
}

// le document officiel : un PNG A4 (et son PDF), papier blanc, au sceau des US Marshals ; signé en bas de page
async function reportCanvas(org, r, x) {
  const author = r.author_signature || (r.status !== 'brouillon' ? { name: r.author_name, style: 'classique', printed: r.author_name } : null);
  const boss = r.status === 'valide' ? r.reviewer_signature || { name: r.reviewer, style: 'classique', printed: r.reviewer } : null;
  try { await Promise.all(['400', '600', '700'].map(w => document.fonts.load(w + ' 24px "Work Sans"')).concat(['500', '600'].map(w => document.fonts.load(w + ' 40px "Fraunces"')))); } catch (e) { /* police de secours */ }
  await sigFontsReady([author && author.style, boss && boss.style]);
  const img = await seal();
  const W = 1240, H = 1754, M = 96, c = document.createElement('canvas'); c.width = W; c.height = H;
  const g = c.getContext('2d'), sans = '"Work Sans", "Helvetica Neue", Arial, sans-serif', serif = '"Fraunces", Georgia, serif';
  const ink = '#16213a', muted = '#5d6577', line = '#c9cfdb', navy = '#1b2a4e', gold = '#b08d3c', tint = '#f2f4f8';
  const font = (w, sz, f) => { g.font = w + ' ' + sz + 'px ' + (f || sans); };
  const text = (t, px, py, color, align) => { g.fillStyle = color || ink; g.textAlign = align || 'left'; g.fillText(t, px, py); };
  const spaced = (t, px, py, color, align, sp) => { g.save(); g.letterSpacing = (sp || 2) + 'px'; text(t, px, py, color, align); g.restore(); };
  const wrap = (t, maxW) => String(t || '').split('\n').flatMap(par => { const out = []; let cur = ''; par.split(/\s+/).forEach(w => { const n = cur ? cur + ' ' + w : w; if (g.measureText(n).width > maxW && cur) { out.push(cur); cur = w; } else cur = n; }); out.push(cur); return out; });
  const fit = (t, maxW) => { let v = String(t || ''); if (g.measureText(v).width <= maxW) return v; while (v.length > 1 && g.measureText(v + '…').width > maxW) v = v.slice(0, -1); return v + '…'; };
  const plain = n => Math.round(Number(n)).toLocaleString('fr-FR').replace(/\s/g, ' ') + ' $';
  const when = t => new Date(t).toLocaleString('fr-FR', { day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit' }).replace(' ', ' à ');
  g.fillStyle = '#ffffff'; g.fillRect(0, 0, W, H);
  // filigrane : le sceau, très pâle, au centre de la page
  if (img) { g.save(); g.globalAlpha = .045; g.drawImage(img, (W - 720) / 2, 560, 720, 720); g.restore(); }
  // en-tête : sceau, ministère, service, cartouche de référence
  if (img) g.drawImage(img, M, 64, 150, 150);
  const hx = M + 176;
  font('600', 14); spaced('U.S. DEPARTMENT OF JUSTICE', hx, 104, gold, 'left', 4);
  font('600', 46, serif); text('United States Marshals Service', hx, 152, navy);
  font('400', 18); text(fit(org.name, 470), hx, 186, muted);
  const bx = W - M - 270, by = 70, bw = 270, bh = 140;
  g.strokeStyle = navy; g.lineWidth = 1.5; g.strokeRect(bx, by, bw, bh);
  g.fillStyle = navy; g.fillRect(bx, by, bw, 34);
  font('700', 13); spaced('RÉFÉRENCE DU DOCUMENT', bx + bw / 2, by + 22, '#ffffff', 'center', 2);
  font('700', 24); text(r.num, bx + bw / 2, by + 72, navy, 'center');
  font('400', 14); text('Émis le ' + new Date().toLocaleDateString('fr-FR'), bx + bw / 2, by + 100, muted, 'center');
  text('Usage interne · confidentiel', bx + bw / 2, by + 124, muted, 'center');
  g.fillStyle = navy; g.fillRect(M, 244, W - 2 * M, 3); g.fillStyle = gold; g.fillRect(M, 251, W - 2 * M, 1);
  // intitulé
  font('700', 24); spaced(x.T.label.toUpperCase(), W / 2, 300, navy, 'center', 4);
  font('italic 500', 26, serif); text(fit(r.title, W - 2 * M - 80), W / 2, 338, muted, 'center');
  let y = 372, n = 0;
  const section = t => { y += 30; n++; font('700', 14); spaced(['I', 'II', 'III', 'IV'][n - 1] + '. ' + t.toUpperCase(), M, y, navy, 'left', 3); g.fillStyle = gold; g.fillRect(M, y + 9, 46, 2); g.fillStyle = line; g.fillRect(M + 52, y + 10, W - 2 * M - 52, 1); y += 24; };
  // I. informations, en cases de formulaire
  section('Informations');
  const items = [['Agent rédacteur', r.author_name + (author && author.matricule ? ' · ' + author.matricule : '')], ['Date et heure des faits', when(r.occurred_at)], ['Lieu', r.place],
    [x.T.person || 'Personne', x.person ? fullName(x.person) + (x.person.alias ? ' « ' + x.person.alias + ' »' : '') : null], ['Dossier', x.caseNum],
    ['Agents présents', x.parts.map(m => m.callsign || m.name).join(', ')]].concat(x.extra).filter(([, v]) => v);
  const cw = (W - 2 * M) / 2, ch = 66;
  items.forEach(([k, v], i) => {
    const cx = M + (i % 2) * cw, cy = y + Math.floor(i / 2) * ch;
    g.strokeStyle = line; g.lineWidth = 1; g.strokeRect(cx, cy, cw, ch);
    font('600', 12); spaced(k.toUpperCase(), cx + 14, cy + 22, muted, 'left', 1.5);
    font('400', 19); text(fit(v, cw - 28), cx + 14, cy + 50, ink);
  });
  y += Math.ceil(items.length / 2) * ch;
  // II. infractions retenues
  if (r.charges.length) {
    section('Infractions retenues');
    const cols = [M + 14, M + 130, W - M - 330, W - M - 170, W - M - 14];
    g.fillStyle = tint; g.fillRect(M, y, W - 2 * M, 36);
    font('700', 12); [['CODE', 0, 'left'], ['INFRACTION', 1, 'left'], ['NOMBRE', 2, 'right'], ['AMENDE', 3, 'right'], ['PEINE', 4, 'right']].forEach(([t, i, a]) => spaced(t, cols[i] + (i === 2 ? 70 : i === 3 ? 60 : 0), y + 23, muted, a, 1.5));
    y += 36;
    r.charges.slice(0, 9).forEach((cg, i) => {
      if (i % 2) { g.fillStyle = '#fafbfd'; g.fillRect(M, y, W - 2 * M, 38); }
      font('700', 17); text(cg.code, cols[0], y + 25, navy); font('400', 17); text(fit(cg.title, cols[2] - cols[1] - 20), cols[1], y + 25, ink);
      text(String(cg.qty), cols[2] + 70, y + 25, ink, 'right'); text(plain(cg.fine * cg.qty), cols[3] + 60, y + 25, ink, 'right'); text(months(cg.jail * cg.qty), cols[4], y + 25, ink, 'right');
      g.fillStyle = line; g.fillRect(M, y + 38, W - 2 * M, 1); y += 38;
    });
    if (r.charges.length > 9) { font('italic 400', 15); text('… et ' + (r.charges.length - 9) + ' autre(s) infraction(s), détaillées dans le MDT.', M + 14, y + 22, muted); y += 32; }
    g.fillStyle = navy; g.fillRect(M, y, W - 2 * M, 2);
    font('700', 18); text('Amende totale : ' + plain(r.total_fine) + '      Peine totale : ' + months(r.total_jail), cols[4], y + 30, navy, 'right');
    font('600', 13); spaced('TOTAL', cols[0], y + 29, muted, 'left', 2);
    y += 44;
  }
  // III. récit
  section('Récit des faits');
  const sigTop = H - 330;
  font('400', 18);
  const lines = wrap(r.narrative || '—', W - 2 * M - 40), maxLines = Math.max(2, Math.floor((sigTop - 40 - y) / 29));
  const shown = lines.slice(0, maxLines);
  const boxH = shown.length * 29 + 30 + (lines.length > maxLines ? 26 : 0);
  g.strokeStyle = line; g.lineWidth = 1; g.strokeRect(M, y, W - 2 * M, boxH);
  shown.forEach((l, i) => text(l, M + 20, y + 36 + i * 29, ink));
  if (lines.length > maxLines) { font('italic 400', 15); text('… suite du récit dans le MDT (' + (lines.length - maxLines) + ' ligne(s)).', M + 20, y + boxH - 14, muted); }
  // signatures, en bas de page
  const sw = (W - 2 * M - 60) / 2;
  [['L’agent rédacteur', author, r.submitted_at, r.status === 'brouillon' ? 'Brouillon : non signé' : ''], ['Le superviseur', boss, r.reviewed_at, r.status === 'a_corriger' ? 'Renvoyé pour correction' : 'En attente de validation']].forEach(([k, sig, at, wait], i) => {
    const sx = M + i * (sw + 60), lineY = sigTop + 150;
    font('600', 13); spaced(k.toUpperCase(), sx, sigTop + 20, muted, 'left', 2);
    if (sig) drawSig(g, sig, sx + sw / 2, lineY - 22, sw - 40, 64);
    else { font('italic 400', 17); text(wait, sx + sw / 2, lineY - 30, '#9aa1b0', 'center'); }
    g.fillStyle = ink; g.fillRect(sx, lineY, sw, 1.5);
    if (sig) {
      font('700', 18); text(sig.printed || sig.name, sx, lineY + 30, ink);
      font('400', 15); text([sig.grade, sig.matricule].filter(Boolean).join(' · '), sx, lineY + 54, muted);
      if (at) text('Signé le ' + when(at), sx + sw, lineY + 30, muted, 'right');
    }
  });
  // pied de page
  g.fillStyle = navy; g.fillRect(M, H - 86, W - 2 * M, 2);
  font('600', 13); spaced('UNITED STATES MARSHALS SERVICE', M, H - 56, navy, 'left', 2);
  font('400', 13); text(org.name + ' · document généré par le MDT', W / 2, H - 56, muted, 'center');
  text(r.num + ' · page 1 / 1', W - M, H - 56, muted, 'right');
  return c;
}

// ================================================================== preuves et saisies
export async function evidenceModal(ctx, preset) {
  const p = preset || {};
  const [cases, people, reports] = await Promise.all([rows('cases', q => q.eq('org_id', ctx.me.id).order('opened_at', { ascending: false })), load.people(ctx),
    ctx.has('rapports') ? rows('reports', q => q.eq('org_id', ctx.me.id).order('created_at', { ascending: false }).limit(200)) : []]);
  const kind = select(Object.entries(EKIND).map(([k, [l]]) => [k, l]), 'arme'), desc = input({ maxlength: 300, placeholder: 'Ex. Pistolet 9 mm, numéro de série limé' });
  const qty = h('input', { type: 'number', min: 0.001, step: 'any', value: 1, class: 'short' }), unit = input({ maxlength: 20, value: 'unité', class: 'short' }), amount = h('input', { type: 'number', min: 0, step: 1, class: 'short', placeholder: '$' });
  const at = h('input', { type: 'datetime-local', value: toLocal(Date.now()) }), place = input({ maxlength: 120 }), storage = input({ maxlength: 60, placeholder: 'Ex. Armurerie, casier 4' });
  const cas = select([['', 'Aucun']].concat(cases.map(c => [c.id, c.num + ' · ' + c.title])), p.case_id || ''), per = select([['', 'Aucun']].concat(people.map(x => [x.id, fullName(x)])), p.person_id || '');
  const rep = select([['', 'Aucun']].concat(reports.map(r => [r.id, r.num + ' · ' + r.title])), p.report_id || ''), photo = photoIn();
  const amountL = field('Montant ($)', amount);
  const paint = () => { amountL.hidden = kind.value !== 'argent'; };
  kind.addEventListener('change', paint);
  const ok = h('button', { class: 'btn primary' }, 'Placer sous scellé');
  const md = modal('Enregistrer une saisie', h('div', { class: 'row' }, field('Type', kind, 'grow'), field('Quantité', qty), field('Unité', unit), amountL), field('Description', desc),
    h('div', { class: 'row' }, field('Saisie le', at, 'grow'), field('Lieu de saisie', place, 'grow')), field('Emplacement de stockage', storage),
    h('div', { class: 'row' }, field('Dossier', cas, 'grow'), field('Rapport', rep, 'grow')), field('Propriétaire ou détenteur', per), field('Photo', photo), foot({ close: () => md.close() }, ok));
  md.box.classList.add('wide');
  paint();
  ok.addEventListener('click', () => run(ok, async () => {
    const path = await send(ctx.me.id, 'preuves', photo.files[0], true);
    const r = await rpc('add_evidence', { p_org: ctx.me.id, p: { kind: kind.value, description: desc.value, qty: qty.value, unit: unit.value, amount: kind.value === 'argent' ? amount.value : '',
      seized_at: iso(new Date(at.value).getTime() || Date.now()), place: place.value, storage: storage.value, case_id: cas.value, report_id: rep.value, person_id: per.value, photo: path } });
    md.close(); ctx.go(base(ctx) + '/preuves/' + r.id); return { message: 'Scellé ' + r.num + ' enregistré.' };
  }));
}
const qtyText = e => e.kind === 'argent' && e.amount !== null ? money(e.amount) : Number(e.qty).toLocaleString('fr-FR', { maximumFractionDigits: 3 }) + ' ' + e.unit;
function evidenceTable(ctx, list, short) {
  return table([['Scellé', e => h('b', { class: 'nowrap' }, e.num), null, e => e.num], ['Type', e => h('span', { class: 'gradecell' }, ico(EKIND[e.kind][1], 16), ' ', EKIND[e.kind][0]), null, e => e.kind],
    ['Description', e => e.description], short ? null : ['Quantité', e => qtyText(e), 'num'], ['Statut', e => pill(...EST[e.status]), null, e => e.status], ['Saisie', e => day(e.seized_at), 'num', e => e.seized_at]].filter(Boolean),
  list, e => ctx.go(base(ctx) + '/preuves/' + e.id), 'Aucune preuve.');
}
export async function preuves(ctx) {
  const { me, can } = ctx;
  const f = S.filters.prvF || 'stock';
  const list = await rows('evidence', q => q.eq('org_id', me.id).order('seized_at', { ascending: false }).limit(1000));
  const inStock = list.filter(e => e.status === 'stock' || e.status === 'tribunal');
  const box = h('div', {});
  const groups = { stock: e => e.status === 'stock', tribunal: e => e.status === 'tribunal', sorties: e => ['restituee', 'detruite'].includes(e.status), tous: () => true };
  const paint = () => box.replaceChildren(evidenceTable(ctx, list.filter(e => groups[f](e) && match(S.filters.prvQ, e.num, e.description, e.storage, e.holder, EKIND[e.kind][0])), false));
  paint();
  const sumKind = k => inStock.filter(e => e.kind === k);
  ctx.show('preuves', 'Preuves et saisies', inStock.length + ' scellé(s) au registre',
    can('dossiers') ? h('button', { class: 'btn primary', type: 'button', onclick: () => evidenceModal(ctx, null).catch(ctx.fail) }, ico('scelle', 18), 'Enregistrer une saisie') : null,
    h('section', { class: 'card' }, h('h2', {}, 'Inventaire des saisies', h('span', { class: 'muted small' }, 'en stock ou au tribunal')),
      strip([['Armes', String(sumKind('arme').reduce((a, e) => a + Number(e.qty), 0))], ['Drogue (scellés)', String(sumKind('drogue').length)], ['Argent', money(sumKind('argent').reduce((a, e) => a + Number(e.amount || 0), 0))],
        ['Véhicules', String(sumKind('vehicule').length)], ['Autres', String(inStock.filter(e => ['document', 'autre'].includes(e.kind)).length)]])),
    h('section', { class: 'card' }, h('div', { class: 'cardhead' }, search(S.filters.prvQ, 'Chercher : scellé, description, emplacement, détenteur', v => { S.filters.prvQ = v; paint(); }),
      fchips([['stock', 'En stock'], ['tribunal', 'Au tribunal'], ['sorties', 'Sorties'], ['tous', 'Toutes']], f, k => { S.filters.prvF = k; preuves(ctx).catch(ctx.fail); }, 'Preuves')), box));
}
export async function preuve(ctx, id) {
  const { me, can } = ctx;
  const [es, log] = await Promise.all([rows('evidence', q => q.eq('id', id)), rows('evidence_log', q => q.eq('evidence_id', id).order('at', { ascending: false }))]);
  const e = es[0];
  if (!e) return ctx.go(base(ctx) + '/preuves', true);
  const [cs, ps, rs] = await Promise.all([e.case_id ? rows('cases', q => q.eq('id', e.case_id)) : [], e.person_id ? rows('people', q => q.eq('id', e.person_id)) : [], e.report_id ? rows('reports', q => q.eq('id', e.report_id)) : []]);
  const urls = await signedUrls([e.photo]);
  const out = ['restituee', 'detruite'].includes(e.status);
  const act = (action, title, label, need, btn, cls) => () => {
    const t = area({ maxlength: 300, placeholder: need }), ok = h('button', { class: 'btn ' + (cls || 'primary') }, btn);
    const md = modal(title + ' · ' + e.num, field(label, t), foot({ close: () => md.close() }, ok));
    ok.addEventListener('click', () => run(ok, async () => { await rpc('evidence_action', { p_id: e.id, p_action: action, p_note: t.value }); md.close(); ctx.reload(); return { message: 'Chaîne de possession mise à jour.' }; }));
  };
  const info = (k, v) => v ? [h('dt', {}, k), h('dd', {}, v)] : null;
  ctx.show('preuves', e.num + ' · ' + e.description, h('span', { class: 'pills' }, pill(EKIND[e.kind][0], 'info'), pill(...EST[e.status])),
    [can('dossiers') && !out ? [h('button', { class: 'btn', type: 'button', onclick: act('transfert', 'Transfert', 'À qui ou où ?', 'Ex. Remise à l’Inspector Park, armurerie casier 4', 'Transférer', null) }, ico('fleche', 18), 'Transférer'),
      h('button', { class: 'btn', type: 'button', onclick: act('consultation', 'Consultation', 'Motif (facultatif)', 'Examen balistique…', 'Noter la consultation', null) }, ico('oeil', 18), 'Consulter'),
      e.status === 'stock' ? h('button', { class: 'btn', type: 'button', onclick: act('tribunal', 'Transmission au tribunal', 'Précision (facultatif)', 'Audience du…', 'Transmettre', null) }, ico('marteau', 18), 'Tribunal') : null,
      h('button', { class: 'btn', type: 'button', onclick: act('restitution', 'Restitution', 'À qui ?', 'Nom de la personne', 'Restituer', null) }, 'Restituer'),
      h('button', { class: 'btn danger', type: 'button', onclick: act('destruction', 'Destruction', 'Comment et sur quelle décision ?', 'Ex. Incinération, décision du juge Hartley', 'Détruire', 'danger') }, 'Détruire')] : null,
      can('dossiers') ? h('button', { class: 'btn', type: 'button', onclick: act('note', 'Note', 'Note', '', 'Ajouter', null) }, ico('journal', 18), 'Note') : null,
      h('a', { class: 'btn ghost', href: base(ctx) + '/preuves' }, 'Registre')],
    h('div', { class: 'grid cols2' },
      h('section', { class: 'card idcard' }, e.photo && urls[e.photo] ? h('a', { href: urls[e.photo], target: '_blank', rel: 'noopener' }, h('img', { class: 'evphoto', src: urls[e.photo], alt: e.description })) : null,
        h('dl', { class: 'lines-dl rules' }, info('Quantité', qtyText(e)), info('Saisie', date(ms(e.seized_at)) + (e.place ? ' · ' + e.place : '')), info('Saisie par', e.by_name),
          info('Emplacement', e.storage), info('Détenteur actuel', e.holder), info('Dossier', cs[0] ? linkTo(base(ctx) + '/dossiers/' + cs[0].id, cs[0].num) : null),
          info('Rapport', rs[0] ? linkTo(base(ctx) + '/rapports/' + rs[0].id, rs[0].num) : null), info('Propriétaire ou détenteur', ps[0] ? linkTo(base(ctx) + '/individus/' + ps[0].id, fullName(ps[0])) : null))),
      h('section', { class: 'card' }, h('h2', {}, 'Chaîne de possession', h('span', { class: 'muted small' }, log.length + ' mouvement(s)')), feed(log.map(l => [l.at, l.action, l.text || EACT[l.action][0], l.by_name, EACT])))));
}
