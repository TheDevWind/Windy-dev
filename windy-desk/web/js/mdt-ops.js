// ================================================================== Windy Desk · MDT, étape 3 : opérations
// Transferts de détenus (POD), tribunal et protection judiciaire (JSD), WITSEC, SOG, renseignement, alertes internes.
// Ne dépend que de mdt-kit.js (mdt.js l'utilise pour la carte des alertes du tableau de bord).
import { S, h, s, ico, modal, toast, run, table, pill, strip, money, date, ago, initials } from './ui.js';
import { rpc, rows, signedUrls } from './sb.js';
import { DAY, ms, iso, day, field, input, area, select, foot, fullName, toLocal, tabs, match, load, send, photoIn, face } from './mdt-kit.js';

const base = ctx => '/' + ctx.me.code;
const RISK = { faible: ['Faible', null], moyen: ['Moyen', 'warn'], eleve: ['Élevé', 'bad'], extreme: ['Extrême', 'bad'] };
const riskOpts = Object.entries(RISK).map(([k, [l]]) => [k, l]);
const TST = { prevu: ['Prévu', 'info'], en_route: ['En route', 'warn'], arrive: ['Arrivé', 'ok'], incident: ['Incident', 'bad'], annule: ['Annulé', null] };
const HST = { prevue: ['Prévue', 'info'], terminee: ['Terminée', 'ok'], reportee: ['Reportée', 'warn'], annulee: ['Annulée', null] };
const ROLE = { juge: 'Juge', procureur: 'Procureur', avocat: 'Avocat', greffier: 'Greffier', temoin: 'Témoin', autre: 'Autre' };
const ID_ST = { a_faire: ['Identité à créer', 'warn'], en_cours: ['Identité en cours', 'info'], faite: ['Nouvelle identité faite', 'ok'] };
const W_ST = { actif: ['Actif', 'info'], termine: ['Terminé', 'ok'], perdu: ['Perdu', 'bad'] };
const EV = { temoignage: 'Témoignage', escorte: 'Escorte', transfert: 'Transfert', autre: 'Autre' };
const MST = { activee: ['Activée', 'bad'], en_cours: ['En cours', 'warn'], terminee: ['Terminée', 'ok'], annulee: ['Annulée', null] };
const SPEC = ['Assaut', 'Tireur d’élite', 'Brèche', 'Négociateur', 'Médecin', 'Conducteur', 'Drone'];
const CLASS = { diffusion: ['Diffusion', 'ok'], confidentiel: ['Confidentiel', 'info'], secret: ['Secret', 'warn'], tres_secret: ['Très secret', 'bad'] };
const REL = ['A', 'B', 'C', 'D', 'E'];
const relPill = r => r ? pill('Fiabilité ' + r, r <= 'B' ? 'ok' : r === 'C' ? 'info' : 'warn') : null;
const memberOpts = (members, none) => (none ? [['', none]] : []).concat(members.filter(m => m.active).map(m => [m.id, (m.callsign ? m.callsign + ' · ' : '') + m.name]));
const peopleOpts = (people, none) => (none ? [['', none]] : []).concat(people.map(p => [p.id, fullName(p) + (p.alias ? ' « ' + p.alias + ' »' : '')]));
const caseOpts = cases => [['', 'Aucun']].concat(cases.map(c => [c.id, c.num + ' · ' + c.title]));
const when = t => date(ms(t));
const linkTo = (href, text) => h('a', { href }, text);

// ================================================================== alertes internes (tableau de bord)
export async function alertsCard(ctx) {
  const list = await rows('alerts', q => q.eq('org_id', ctx.me.id).is('read_at', null).order('at', { ascending: false }).limit(20));
  if (!list.length) return null;
  const IC = { witsec: 'cadenas', sog: 'bouclier', transfert: 'camion', menace: 'alerte', autre: 'megaphone' };
  return h('section', { class: 'card alertcard' }, h('h2', {}, 'Alertes', h('span', { class: 'right' }, h('button', { class: 'btn small', type: 'button',
    onclick: ev => run(ev.currentTarget, async () => { await rpc('read_alerts', { p_org: ctx.me.id }); ctx.reload(); return { message: 'Alertes marquées comme lues.' }; }) }, 'Tout marquer comme lu'))),
    h('ul', { class: 'people' }, list.map(a => h('li', { class: 'al-' + a.kind }, h('span', { class: 'ev-ic' }, ico(IC[a.kind] || 'alerte', 16)),
      h('span', { class: 'art grow' }, a.link ? h('a', { href: base(ctx) + a.link }, h('b', {}, a.text)) : h('b', {}, a.text), h('small', {}, ago(ms(a.at))))))));
}

// ================================================================== transferts de détenus
function transferModal(ctx, t, d) {
  const v = t || {}, route = t ? d.routes.find(r => r.transfer_id === t.id) || {} : {};
  const per = select(peopleOpts(d.people, 'Choisir…'), v.person_id || ''), from = input({ maxlength: 120, value: v.from_place || '', placeholder: 'Ex. Mission Row' }), to = input({ maxlength: 120, value: v.to_place || '', placeholder: 'Ex. Bolingbroke' });
  const at = h('input', { type: 'datetime-local', value: toLocal(t ? ms(t.depart_at) : Date.now() + 3600e3) }), risk = select(riskOpts, v.risk || 'moyen');
  const leader = select(memberOpts(d.members, 'À désigner'), v.leader_id || ''), vehicles = input({ maxlength: 200, value: v.vehicles || '', placeholder: 'Ex. 2 SUV, 1 fourgon blindé' }), sog = h('input', { type: 'checkbox', checked: !!v.sog });
  const agents = d.members.filter(m => m.active).map(m => [m, h('input', { type: 'checkbox', checked: t ? d.agents.some(a => a.transfer_id === t.id && a.member_id === m.id) : false })]);
  const main = area({ maxlength: 600, value: route.main || '', placeholder: 'Itinéraire principal' }), backup = area({ maxlength: 600, value: route.backup || '', placeholder: 'Itinéraire de secours' });
  const cas = select(caseOpts(d.cases), v.case_id || ''), ok = h('button', { class: 'btn primary' }, t ? 'Enregistrer' : 'Créer l’ordre de transfert');
  const md = modal(t ? 'Transfert ' + t.num : 'Ordre de transfert', field('Détenu', per), h('div', { class: 'row' }, field('Départ', from, 'grow'), field('Arrivée', to, 'grow')),
    h('div', { class: 'row' }, field('Date et heure', at, 'grow'), field('Niveau de risque', risk, 'grow')), h('div', { class: 'row' }, field('Chef de convoi', leader, 'grow'), field('Dossier', cas, 'grow')),
    field('Véhicules', vehicles), h('label', { class: 'check' }, sog, 'Escorte SOG'), h('div', { class: 'f' }, 'Agents du convoi', h('div', { class: 'checks' }, agents.map(([m, cb]) => h('label', { class: 'check' }, cb, m.callsign || m.name)))),
    field('Itinéraire principal (lu seulement par le convoi)', main), field('Itinéraire de secours', backup), foot({ close: () => md.close() }, ok));
  md.box.classList.add('wide');
  ok.addEventListener('click', () => run(ok, async () => {
    const r = await rpc('save_transfer', { p_org: ctx.me.id, p_id: t ? t.id : null, p: { person_id: per.value, from_place: from.value, to_place: to.value, depart_at: iso(new Date(at.value).getTime() || Date.now()), risk: risk.value,
      leader_id: leader.value, vehicles: vehicles.value, sog: sog.checked, case_id: cas.value, route_main: main.value, route_backup: backup.value }, p_agents: agents.filter(([, cb]) => cb.checked).map(([m]) => m.id) });
    md.close(); ctx.reload(); return { message: 'Transfert ' + r.num + (t ? ' enregistré.' : ' planifié. Le convoi est prévenu.') };
  }));
}
function transferView(ctx, t, d) {
  const route = d.routes.find(r => r.transfer_id === t.id), team = d.agents.filter(a => a.transfer_id === t.id).map(a => d.members.find(m => m.id === a.member_id)).filter(Boolean);
  const leader = d.members.find(m => m.id === t.leader_id), p = d.people.find(x => x.id === t.person_id);
  const info = (k, v) => v ? [h('dt', {}, k), h('dd', {}, v)] : null;
  const md = modal('Transfert ' + t.num, h('div', { class: 'pills' }, pill(...TST[t.status]), pill('Risque ' + RISK[t.risk][0].toLowerCase(), RISK[t.risk][1]), t.sog ? pill('Escorte SOG', 'bad') : null),
    h('dl', { class: 'lines-dl rules' }, info('Détenu', p ? fullName(p) : '—'), info('Trajet', t.from_place + ' → ' + t.to_place), info('Départ', when(t.depart_at)), info('Chef de convoi', leader ? leader.name : 'À désigner'),
      info('Agents', team.map(m => m.callsign || m.name).join(', ')), info('Véhicules', t.vehicles), info('Parti à', t.started_at ? when(t.started_at) : null), info('Arrivé à', t.arrived_at ? when(t.arrived_at) : null)),
    h('h3', { class: 'sub-h' }, 'Itinéraires'), route ? h('dl', { class: 'lines-dl rules' }, info('Principal', route.main || '—'), info('Secours', route.backup || '—'))
      : h('p', { class: 'muted' }, ico('cadenas', 14), ' Réservé au convoi et aux responsables des transferts.'),
    t.incident ? [h('h3', { class: 'sub-h' }, 'Incident'), h('p', { class: 'prewrap' }, t.incident)] : null, h('div', { class: 'foot' }, h('button', { class: 'btn', type: 'button', onclick: () => md.close() }, 'Fermer')));
}
function transferActions(ctx, t, d) {
  const mine = t.leader_id === ctx.me.member_id || d.agents.some(a => a.transfer_id === t.id && a.member_id === ctx.me.member_id);
  if (!(mine || ctx.can('transferts')) || ['arrive', 'annule'].includes(t.status)) return null;
  const go = (st, label, cls) => h('button', { class: 'btn small' + (cls ? ' ' + cls : ''), type: 'button', onclick: ev => { ev.stopPropagation(); run(ev.currentTarget, async () => { await rpc('transfer_status', { p_id: t.id, p_status: st, p_note: null, p_escape: false }); ctx.reload(); return { message: t.num + ' : ' + TST[st][0].toLowerCase() + '.' }; }); } }, label);
  const incident = h('button', { class: 'btn small danger', type: 'button', onclick: ev => {
    ev.stopPropagation();
    const txt = area({ maxlength: 500, placeholder: 'Ce qui s’est passé, où, quand' }), esc = h('input', { type: 'checkbox' }), ok = h('button', { class: 'btn danger' }, 'Déclarer l’incident');
    const md = modal('Incident · ' + t.num, field('Description', txt), h('label', { class: 'check' }, esc, 'Le détenu s’est évadé (ouvre un dossier Affaires internes et alerte le Director)'), foot({ close: () => md.close() }, ok));
    ok.addEventListener('click', () => run(ok, async () => { await rpc('transfer_status', { p_id: t.id, p_status: 'incident', p_note: txt.value, p_escape: esc.checked }); md.close(); ctx.reload(); return { message: 'Incident déclaré.' }; }));
  } }, 'Incident');
  return h('span', { class: 'acts' }, t.status === 'prevu' ? go('en_route', 'Départ', 'primary') : null, t.status !== 'prevu' && t.status !== 'incident' ? go('arrive', 'Arrivé', 'primary') : null,
    t.status === 'incident' ? go('arrive', 'Arrivé malgré tout') : null, t.status !== 'incident' ? incident : null, t.status === 'prevu' && ctx.can('transferts') ? go('annule', 'Annuler') : null);
}
export async function transferts(ctx) {
  const { me, can } = ctx;
  const tab = S.filters.tra === 'detenus' ? 'detenus' : 'transferts';
  const [list, agents, routes, people, members, detentions, cases] = await Promise.all([rows('transfers', q => q.eq('org_id', me.id).order('depart_at', { ascending: false }).limit(300)),
    rows('transfer_agents', q => q.eq('org_id', me.id)), rows('transfer_routes', q => q.eq('org_id', me.id)), load.people(ctx), load.members(ctx),
    rows('detentions', q => q.eq('org_id', me.id).order('since', { ascending: false })), rows('cases', q => q.eq('org_id', me.id).order('opened_at', { ascending: false }))]);
  const d = { agents, routes, people, members, cases }, pOf = id => people.find(p => p.id === id), mOf = id => members.find(m => m.id === id);
  const live = list.filter(t => ['prevu', 'en_route'].includes(t.status)), held = detentions.filter(x => !x.released_at);
  const detainModal = () => {
    const per = select(peopleOpts(people, 'Choisir…'), ''), place = input({ maxlength: 120, placeholder: 'Ex. Bolingbroke, bloc C' }), until = h('input', { type: 'date' }), why = input({ maxlength: 200 }), cas = select(caseOpts(cases), '');
    const ok = h('button', { class: 'btn primary' }, 'Inscrire');
    const md = modal('Inscrire un détenu', field('Individu', per), field('Lieu de détention', place), h('div', { class: 'row' }, field('Jusqu’au (facultatif)', until), field('Dossier', cas, 'grow')), field('Motif', why), foot({ close: () => md.close() }, ok));
    ok.addEventListener('click', () => run(ok, async () => { await rpc('save_detention', { p_org: me.id, p_person: per.value || null, p_place: place.value, p_until: until.value ? iso(new Date(until.value).getTime()) : null, p_reason: why.value, p_case: cas.value || null }); md.close(); ctx.reload(); return { message: 'Détenu inscrit au registre.' }; }));
  };
  ctx.show('transferts', 'Transferts de détenus', live.length + ' transfert(s) prévu(s) ou en route · ' + held.length + ' détenu(s)',
    can('transferts') ? [h('button', { class: 'btn', type: 'button', onclick: detainModal }, 'Inscrire un détenu'), h('button', { class: 'btn primary', type: 'button', onclick: () => transferModal(ctx, null, d) }, ico('camion', 18), 'Ordre de transfert')] : null,
    h('section', { class: 'card' }, strip([['Prévus', String(list.filter(t => t.status === 'prevu').length)], ['En route', String(list.filter(t => t.status === 'en_route').length), list.some(t => t.status === 'en_route') ? 'warn-t' : null],
      ['Incidents', String(list.filter(t => t.status === 'incident').length), list.some(t => t.status === 'incident') ? 'warn-t' : null], ['Détenus', String(held.length)]])),
    tabs([['transferts', 'Transferts'], ['detenus', 'Registre des détenus (' + held.length + ')']], tab, k => { S.filters.tra = k; transferts(ctx).catch(ctx.fail); }),
    tab === 'transferts'
      ? h('section', { class: 'card' }, table([['N°', t => h('b', { class: 'nowrap' }, t.num), null, t => t.num], ['Détenu', t => fullName(pOf(t.person_id))], ['Trajet', t => t.from_place + ' → ' + t.to_place],
        ['Départ', t => when(t.depart_at), 'num', t => t.depart_at], ['Risque', t => pill(...RISK[t.risk]), null, t => Object.keys(RISK).indexOf(t.risk)],
        ['Convoi', t => h('span', { class: 'art' }, (mOf(t.leader_id) || {}).name || 'Chef à désigner', h('small', {}, agents.filter(a => a.transfer_id === t.id).length + ' agent(s)' + (t.sog ? ' · SOG' : '')))],
        ['Statut', t => pill(...TST[t.status]), null, t => t.status], ['', t => transferActions(ctx, t, d)]], list, t => transferView(ctx, t, d), 'Aucun transfert.'))
      : h('section', { class: 'card' }, table([['Détenu', x => linkTo(base(ctx) + '/individus/' + x.person_id, fullName(pOf(x.person_id)))], ['Lieu', x => x.place], ['Depuis', x => day(x.since), 'num', x => x.since],
        ['Jusqu’au', x => x.until ? day(x.until) : '—'], ['Motif', x => x.reason || '—'], ['Statut', x => x.released_at ? pill('Libéré le ' + day(x.released_at)) : pill('Détenu', 'warn')],
        ['', x => !x.released_at && can('transferts') ? h('button', { class: 'btn small', type: 'button', onclick: ev => run(ev.currentTarget, async () => { await rpc('release_detention', { p_id: x.id, p_note: null }); ctx.reload(); return { message: 'Libération enregistrée.' }; }) }, 'Libérer') : null]],
      detentions, null, 'Le registre est vide.')));
}

// ================================================================== tribunal et protection judiciaire
function hearingModal(ctx, hr, d) {
  const v = hr || {};
  const at = h('input', { type: 'datetime-local', value: toLocal(hr ? ms(hr.starts_at) : Date.now() + DAY) }), room = input({ maxlength: 60, value: v.room || '', placeholder: 'Ex. Salle 2' });
  const per = select(peopleOpts(d.people, 'Aucun'), v.person_id || ''), judge = input({ maxlength: 80, value: v.judge || '' }), cas = select(caseOpts(d.cases), v.case_id || ''), risk = select(riskOpts, v.risk || 'moyen');
  const notes = area({ maxlength: 1000, value: v.notes || '' }), ok = h('button', { class: 'btn primary' }, hr ? 'Enregistrer' : 'Programmer');
  const md = modal(hr ? 'Audience du ' + day(hr.starts_at) : 'Nouvelle audience', h('div', { class: 'row' }, field('Date et heure', at, 'grow'), field('Salle', room, 'grow')), h('div', { class: 'row' }, field('Accusé', per, 'grow'), field('Juge', judge, 'grow')),
    h('div', { class: 'row' }, field('Dossier', cas, 'grow'), field('Niveau de risque', risk, 'grow')), field('Notes', notes), foot({ close: () => md.close() }, ok));
  ok.addEventListener('click', () => run(ok, async () => {
    await rpc('save_hearing', { p_org: ctx.me.id, p_id: hr ? hr.id : null, p: { starts_at: iso(new Date(at.value).getTime() || Date.now()), room: room.value, person_id: per.value, judge: judge.value, case_id: cas.value, risk: risk.value, notes: notes.value } });
    md.close(); ctx.reload(); return { message: hr ? 'Audience enregistrée.' : 'Audience programmée.' };
  }));
}
function postsModal(ctx, hr, d) {
  const box = h('div', { class: 'linelist' });
  const addRow = (post, mid) => { const pi = input({ maxlength: 60, value: post || '', placeholder: 'Poste (entrée, salle, escorte…)', 'aria-label': 'Poste' }), ms2 = select(memberOpts(d.members, 'Agent…'), mid || '', { 'aria-label': 'Agent du poste' });
    const row = h('div', { class: 'll-row' }, pi, ms2, h('button', { class: 'btn small ghost', type: 'button', 'aria-label': 'Retirer', onclick: () => row.remove() }, ico('moins', 16))); row._get = () => ({ post: pi.value.trim(), member_id: ms2.value }); box.append(row); };
  const current = d.posts.filter(x => x.hearing_id === hr.id);
  (current.length ? current : [{ post: 'Entrée' }, { post: 'Salle d’audience' }, { post: 'Escorte de l’accusé' }]).forEach(x => addRow(x.post, x.member_id));
  const ok = h('button', { class: 'btn primary' }, 'Enregistrer le plan');
  const md = modal('Plan de sécurité · ' + when(hr.starts_at), h('p', { class: 'muted', style: 'margin:0' }, 'Chaque agent affecté reçoit une alerte.'), box,
    h('button', { class: 'btn small', type: 'button', onclick: () => addRow('', '') }, ico('ajout', 16), 'Poste'), foot({ close: () => md.close() }, ok));
  md.box.classList.add('wide');
  ok.addEventListener('click', () => run(ok, async () => { await rpc('set_hearing_posts', { p_id: hr.id, p_posts: [...box.children].map(r => r._get()).filter(x => x.post) }); md.close(); ctx.reload(); return { message: 'Plan de sécurité enregistré.' }; }));
}
function protecteeModal(ctx, pr) {
  const v = pr || {};
  const name = input({ maxlength: 80, value: v.name || '' }), role = select(Object.entries(ROLE), v.role || 'juge'), threat = select(riskOpts, v.threat || 'faible'), notes = area({ maxlength: 1000, value: v.notes || '' });
  const active = h('input', { type: 'checkbox', checked: pr ? pr.active : true }), ok = h('button', { class: 'btn primary' }, 'Enregistrer');
  const md = modal(pr ? pr.name : 'Personne à protéger', field('Nom', name), h('div', { class: 'row' }, field('Fonction', role, 'grow'), field('Niveau de menace', threat, 'grow')), field('Notes', notes), h('label', { class: 'check' }, active, 'Protection active'), foot({ close: () => md.close() }, ok));
  ok.addEventListener('click', () => run(ok, async () => { await rpc('save_protectee', { p_org: ctx.me.id, p_id: pr ? pr.id : null, p: { name: name.value, role: role.value, threat: threat.value, notes: notes.value, active: active.checked } }); md.close(); ctx.reload(); return { message: 'Fiche enregistrée.' }; }));
}
function threatModal(ctx, d) {
  const pr = select(d.protectees.filter(x => x.active).map(x => [x.id, x.name + ' · ' + ROLE[x.role]]), ''), per = select(peopleOpts(d.people, 'Auteur non fiché'), ''), author = input({ maxlength: 80, placeholder: 'Nom ou description de l’auteur' });
  const desc = area({ maxlength: 1000, placeholder: 'Lettre, appel, propos rapportés…' }), level = select(riskOpts, 'moyen'), ok = h('button', { class: 'btn danger' }, 'Enregistrer la menace');
  const md = modal('Signaler une menace', field('Personne visée', pr), h('div', { class: 'row' }, field('Auteur fiché', per, 'grow'), field('Ou auteur', author, 'grow')), field('Description', desc), field('Niveau', level), foot({ close: () => md.close() }, ok));
  ok.addEventListener('click', () => run(ok, async () => { await rpc('add_threat', { p_org: ctx.me.id, p: { protectee_id: pr.value, person_id: per.value, author: author.value, description: desc.value, level: level.value } }); md.close(); ctx.reload(); return { message: 'Menace enregistrée. Les responsables sont alertés.' }; }));
}
export async function tribunal(ctx) {
  const { me, can } = ctx;
  const tab = ['audiences', 'proteges', 'menaces'].includes(S.filters.tri) && (S.filters.tri === 'audiences' || can('tribunal')) ? S.filters.tri : 'audiences';
  const [hearings, posts, protectees, threats, people, members, cases] = await Promise.all([rows('hearings', q => q.eq('org_id', me.id).order('starts_at', { ascending: false }).limit(300)),
    rows('hearing_posts', q => q.eq('org_id', me.id)), rows('protectees', q => q.eq('org_id', me.id).order('name')), rows('threats', q => q.eq('org_id', me.id).order('at', { ascending: false })),
    load.people(ctx), load.members(ctx), rows('cases', q => q.eq('org_id', me.id).order('opened_at', { ascending: false }))]);
  const d = { posts, protectees, people, members, cases }, pOf = id => people.find(p => p.id === id), mOf = id => members.find(m => m.id === id);
  const upcoming = hearings.filter(x => x.status === 'prevue' && ms(x.starts_at) > Date.now() - 6 * 3600e3);
  const minePosts = posts.filter(x => x.member_id === me.member_id && upcoming.some(hr => hr.id === x.hearing_id));
  const statusSel = hr => { const sl = select(Object.entries(HST).map(([k, [l]]) => [k, l]), hr.status, { 'aria-label': 'Statut de l’audience', class: 'mini' });
    sl.addEventListener('click', ev => ev.stopPropagation());
    sl.addEventListener('change', () => rpc('hearing_status', { p_id: hr.id, p_status: sl.value }).then(() => { toast('Audience : ' + HST[sl.value][0].toLowerCase() + '.'); ctx.reload(); }, ctx.fail)); return sl; };
  const body = {
    audiences: () => h('section', { class: 'card' }, table([['Date', x => when(x.starts_at), 'num', x => x.starts_at], ['Salle', x => x.room], ['Accusé', x => x.person_id ? linkTo(base(ctx) + '/individus/' + x.person_id, fullName(pOf(x.person_id))) : '—'],
      ['Juge', x => x.judge || '—'], ['Risque', x => pill(...RISK[x.risk])], ['Sécurité', x => { const ps = posts.filter(y => y.hearing_id === x.id); return ps.length ? h('span', { class: 'art' }, ps.length + ' poste(s)', h('small', {}, ps.map(y => y.post + ' : ' + ((mOf(y.member_id) || {}).callsign || (mOf(y.member_id) || {}).name || '—')).join(' · '))) : h('span', { class: 'muted' }, 'Aucun plan'); }],
      ['Statut', x => can('tribunal') ? statusSel(x) : pill(...HST[x.status])],
      can('tribunal') ? ['', x => h('span', { class: 'acts' }, h('button', { class: 'btn small', type: 'button', onclick: ev => { ev.stopPropagation(); postsModal(ctx, x, d); } }, 'Plan de sécurité'),
        h('button', { class: 'btn small ghost', type: 'button', onclick: ev => { ev.stopPropagation(); hearingModal(ctx, x, d); } }, 'Modifier'))] : null].filter(Boolean),
      hearings, null, 'Aucune audience programmée.')),
    proteges: () => h('section', { class: 'card' }, h('div', { class: 'cardhead' }, h('p', { class: 'muted' }, 'Le niveau de menace monte tout seul quand une menace plus grave est enregistrée.'),
      h('button', { class: 'btn', type: 'button', onclick: () => protecteeModal(ctx, null) }, ico('ajout', 18), 'Ajouter')),
      table([['Nom', x => h('b', {}, x.name)], ['Fonction', x => ROLE[x.role]], ['Menace', x => pill(...RISK[x.threat]), null, x => Object.keys(RISK).indexOf(x.threat)],
        ['Menaces reçues', x => String(threats.filter(t => t.protectee_id === x.id).length), 'num'], ['', x => x.active ? pill('Protégé', 'info') : pill('Protection levée')]], protectees, x => protecteeModal(ctx, x), 'Aucune personne protégée.')),
    menaces: () => h('section', { class: 'card' }, h('div', { class: 'cardhead' }, h('p', { class: 'muted' }, 'Registre des menaces contre les juges, procureurs et témoins.'),
      h('button', { class: 'btn danger', type: 'button', disabled: !protectees.length, onclick: () => threatModal(ctx, d) }, ico('alerte', 18), 'Signaler une menace')),
      table([['Reçue', x => when(x.at), 'num', x => x.at], ['Visé', x => (protectees.find(y => y.id === x.protectee_id) || {}).name || '?'], ['Auteur', x => x.person_id ? linkTo(base(ctx) + '/individus/' + x.person_id, fullName(pOf(x.person_id))) : x.author || 'Inconnu'],
        ['Menace', x => h('span', { class: 'prewrap' }, x.description)], ['Niveau', x => pill(...RISK[x.level])], ['Statut', x => x.status === 'ouverte' ? pill('Ouverte', 'warn') : x.status === 'traitee' ? pill('Traitée', 'ok') : pill('Classée')],
        ['', x => x.status === 'ouverte' ? h('span', { class: 'acts' }, ['traitee', 'classee'].map(st => h('button', { class: 'btn small', type: 'button', onclick: ev => run(ev.currentTarget, async () => { await rpc('threat_status', { p_id: x.id, p_status: st }); ctx.reload(); return { message: 'Menace mise à jour.' }; }) }, st === 'traitee' ? 'Traitée' : 'Classer'))) : null]],
      threats, null, 'Aucune menace enregistrée.')),
  };
  ctx.show('tribunal', 'Tribunal et protection', upcoming.length + ' audience(s) à venir' + (can('tribunal') ? ' · ' + threats.filter(t => t.status === 'ouverte').length + ' menace(s) ouverte(s)' : ''),
    can('tribunal') ? h('button', { class: 'btn primary', type: 'button', onclick: () => hearingModal(ctx, null, d) }, ico('ajout', 18), 'Nouvelle audience') : null,
    minePosts.length ? h('div', { class: 'banner' }, ico('balance', 20), h('div', {}, h('b', {}, 'Tu es affecté à ' + minePosts.length + ' poste(s)'), h('span', {}, minePosts.map(x => { const hr = hearings.find(y => y.id === x.hearing_id); return x.post + ' · ' + when(hr.starts_at) + ' · salle ' + hr.room; }).join(' ; ')))) : null,
    tabs([['audiences', 'Audiences'], can('tribunal') ? ['proteges', 'Magistrats protégés'] : null, can('tribunal') ? ['menaces', 'Menaces'] : null], tab, k => { S.filters.tri = k; tribunal(ctx).catch(ctx.fail); }),
    body[tab]());
}

// ================================================================== WITSEC
function witnessModal(ctx, w, d) {
  const v = w || {};
  const real = w ? null : input({ maxlength: 80, autocomplete: 'off', placeholder: 'Il restera masqué derrière le code témoin' });
  const threat = select(riskOpts, v.threat || 'eleve'), cas = select(caseOpts(d.cases), v.case_id || ''), against = input({ maxlength: 300, value: v.against || '', placeholder: 'Personnes contre qui il témoigne' });
  const sh = select([['', 'Aucune']].concat(d.safehouses.map(x => [x.id, x.code + ' · ' + x.location_code])), v.safehouse_id || ''), idst = select(Object.entries(ID_ST).map(([k, [l]]) => [k, l]), v.identity_status || 'a_faire');
  const idnote = input({ maxlength: 500, value: v.identity_note || '', placeholder: 'Suivi avec le staff (ticket, demande…)' }), newid = input({ maxlength: 80, autocomplete: 'off', placeholder: w ? 'Laisser vide pour ne pas changer' : '' });
  const st = select(Object.entries(W_ST).map(([k, [l]]) => [k, l]), v.status || 'actif'), notes = area({ maxlength: 2000, value: v.notes || '' }), ok = h('button', { class: 'btn primary' }, w ? 'Enregistrer' : 'Placer sous protection');
  const md = modal(w ? 'Témoin ' + w.code : 'Nouveau témoin protégé', real ? field('Vrai nom', real) : null, h('div', { class: 'row' }, field('Niveau de menace', threat, 'grow'), field('Statut', st, 'grow')),
    h('div', { class: 'row' }, field('Affaire', cas, 'grow'), field('Planque', sh, 'grow')), field('Témoigne contre', against), h('div', { class: 'row' }, field('Nouvelle identité', idst, 'grow'), field('Nom de la nouvelle identité', newid, 'grow')),
    field('Suivi avec le staff', idnote), field('Notes', notes), foot({ close: () => md.close() }, ok));
  md.box.classList.add('wide');
  ok.addEventListener('click', () => run(ok, async () => {
    const r = await rpc('save_witness', { p_org: ctx.me.id, p_id: w ? w.id : null, p: { real_name: real ? real.value : null, threat: threat.value, case_id: cas.value, against: against.value, safehouse_id: sh.value,
      identity_status: idst.value, identity_note: idnote.value, new_identity: newid.value, status: st.value, notes: notes.value } });
    md.close(); if (w) ctx.reload(); else ctx.go(base(ctx) + '/witsec/' + r.id); return { message: w ? 'Fiche enregistrée.' : 'Témoin ' + r.code + ' placé sous protection.' };
  }));
}
function eventModal(ctx, witnesses, preset) {
  const w = select(witnesses.map(x => [x.id, x.code]), preset || (witnesses[0] && witnesses[0].id)), kind = select(Object.entries(EV), 'temoignage');
  const at = h('input', { type: 'datetime-local', value: toLocal(Date.now() + DAY) }), place = input({ maxlength: 120, placeholder: 'Lieu codé de préférence' }), notes = input({ maxlength: 300 }), ok = h('button', { class: 'btn primary' }, 'Planifier');
  const md = modal('Témoignage ou escorte', h('div', { class: 'row' }, field('Témoin', w, 'grow'), field('Type', kind, 'grow')), h('div', { class: 'row' }, field('Date et heure', at, 'grow'), field('Lieu', place, 'grow')), field('Notes', notes), foot({ close: () => md.close() }, ok));
  ok.addEventListener('click', () => run(ok, async () => { await rpc('save_witsec_event', { p_org: ctx.me.id, p: { witness_id: w.value, kind: kind.value, starts_at: iso(new Date(at.value).getTime() || Date.now()), place: place.value, notes: notes.value } }); md.close(); ctx.reload(); return { message: 'Créneau planifié.' }; }));
}
function safehouseModal(ctx, x) {
  const v = x || {};
  const code = input({ maxlength: 20, value: v.code || '', placeholder: 'Ex. PL-NORD', autocapitalize: 'characters' }), loc = input({ maxlength: 120, value: v.location_code || '', placeholder: 'Localisation codée, ex. Secteur 7, grille K3' });
  const cap = h('input', { type: 'number', min: 1, max: 20, value: v.capacity || 1, class: 'short' }), av = h('input', { type: 'checkbox', checked: x ? x.available : true }), notes = input({ maxlength: 500, value: v.notes || '' }), ok = h('button', { class: 'btn primary' }, 'Enregistrer');
  const md = modal(x ? 'Planque ' + x.code : 'Nouvelle planque', h('div', { class: 'row' }, field('Code', code), field('Capacité', cap)), field('Localisation codée', loc), h('label', { class: 'check' }, av, 'Disponible'), field('Notes', notes), foot({ close: () => md.close() }, ok));
  ok.addEventListener('click', () => run(ok, async () => { await rpc('save_safehouse', { p_org: ctx.me.id, p_id: x ? x.id : null, p: { code: code.value, location_code: loc.value, capacity: cap.value, available: av.checked, notes: notes.value } }); md.close(); ctx.reload(); return { message: 'Planque enregistrée.' }; }));
}
async function witsecData(ctx) {
  const { me } = ctx;
  const [witnesses, safehouses, events, cases] = await Promise.all([rows('witnesses', q => q.eq('org_id', me.id).order('created_at', { ascending: false })), rows('safehouses', q => q.eq('org_id', me.id).order('code')),
    rows('witsec_events', q => q.eq('org_id', me.id).order('starts_at')), rows('cases', q => q.eq('org_id', me.id).order('opened_at', { ascending: false }))]);
  return { witnesses, safehouses, events, cases };
}
export async function witsec(ctx) {
  const { me, can } = ctx;
  const tab = ['temoins', 'planques', 'calendrier', 'audit'].includes(S.filters.wit) && (S.filters.wit !== 'audit' || can('witsec')) ? S.filters.wit : 'temoins';
  const d = await witsecData(ctx);
  const audit = tab === 'audit' ? await rows('witsec_audit', q => q.eq('org_id', me.id).order('at', { ascending: false }).limit(300)) : [];
  const members = tab === 'audit' ? await load.members(ctx) : [];
  const wOf = id => d.witnesses.find(w => w.id === id), cOf = id => d.cases.find(c => c.id === id), shOf = id => d.safehouses.find(x => x.id === id);
  const soon = d.events.filter(e => ms(e.starts_at) > Date.now() - 3600e3);
  const body = {
    temoins: () => h('section', { class: 'card' }, table([['Code', w => h('b', { class: 'nowrap wcode' }, w.code), null, w => w.code], ['Menace', w => pill(...RISK[w.threat])], ['Affaire', w => (cOf(w.case_id) || {}).num || '—'],
      ['Témoigne contre', w => w.against || '—'], ['Planque', w => (shOf(w.safehouse_id) || {}).code || '—'], ['Identité', w => pill(...ID_ST[w.identity_status])], ['Statut', w => pill(...W_ST[w.status])]],
    d.witnesses, w => ctx.go(base(ctx) + '/witsec/' + w.id), 'Aucun témoin protégé.')),
    planques: () => h('section', { class: 'card' }, h('div', { class: 'cardhead' }, h('p', { class: 'muted' }, 'Localisations codées : aucune adresse en clair.'), h('button', { class: 'btn', type: 'button', onclick: () => safehouseModal(ctx, null) }, ico('ajout', 18), 'Planque')),
      table([['Code', x => h('b', {}, x.code)], ['Localisation codée', x => x.location_code], ['Occupation', x => d.witnesses.filter(w => w.safehouse_id === x.id && w.status === 'actif').length + ' / ' + x.capacity, 'num'],
        ['', x => x.available ? pill('Disponible', 'ok') : pill('Indisponible', 'bad')]], d.safehouses, x => safehouseModal(ctx, x), 'Aucune planque.')),
    calendrier: () => h('section', { class: 'card' }, h('div', { class: 'cardhead' }, h('p', { class: 'muted' }, 'Témoignages et escortes à venir.'), h('button', { class: 'btn', type: 'button', disabled: !d.witnesses.length, onclick: () => eventModal(ctx, d.witnesses, null) }, ico('ajout', 18), 'Planifier')),
      table([['Date', e => when(e.starts_at), 'num', e => e.starts_at], ['Type', e => EV[e.kind]], ['Témoin', e => (wOf(e.witness_id) || {}).code || '?'], ['Lieu', e => e.place || '—'], ['Notes', e => e.notes || '']], soon, null, 'Rien de prévu.')),
    audit: () => h('section', { class: 'card' }, h('p', { class: 'muted', style: 'margin-top:0' }, 'Chaque consultation de fiche et chaque révélation de nom est notée ici.'),
      table([['Quand', a => when(a.at), 'num', a => a.at], ['Agent', a => a.by_name], ['Action', a => a.action === 'revelation' ? pill('Nom révélé', 'bad') : a.action === 'consultation' ? pill('Consultation', 'info') : pill(a.action === 'creation' ? 'Création' : 'Modification')],
        ['Témoin', a => (wOf(a.witness_id) || {}).code || '—'], ['Motif', a => a.note || '']], audit, null, 'Journal vide.')),
  };
  ctx.show('witsec', 'WITSEC', d.witnesses.filter(w => w.status === 'actif').length + ' témoin(s) actif(s) · accès restreint et journalisé',
    h('button', { class: 'btn primary', type: 'button', onclick: () => witnessModal(ctx, null, d) }, ico('cadenas', 18), 'Nouveau témoin'),
    h('div', { class: 'banner' }, ico('cadenas', 20), h('div', {}, h('b', {}, 'Module ultra-restreint'), h('span', {}, 'Les vrais noms restent masqués. Ouvrir une fiche ou révéler un nom est journalisé et le Director en est averti.'))),
    tabs([['temoins', 'Témoins'], ['planques', 'Planques'], ['calendrier', 'Calendrier (' + soon.length + ')'], can('witsec') ? ['audit', 'Journal d’audit'] : null], tab, k => { S.filters.wit = k; witsec(ctx).catch(ctx.fail); }),
    body[tab]());
}
export async function temoin(ctx, id) {
  const d = await witsecData(ctx);
  const w = d.witnesses.find(x => x.id === id);
  if (!w) return ctx.go(base(ctx) + '/witsec', true);
  // une ouverture = une consultation journalisée (pas une de plus quand la fiche se recharge après une modification)
  if (!S.witnessSeen || S.witnessSeen.id !== w.id || Date.now() - S.witnessSeen.at > 120e3) { await rpc('open_witness', { p_id: w.id }); S.witnessSeen = { id: w.id, at: Date.now() }; }
  const c = d.cases.find(x => x.id === w.case_id), sh = d.safehouses.find(x => x.id === w.safehouse_id), evs = d.events.filter(e => e.witness_id === w.id);
  const nameBox = h('dd', { class: 'masked' }, '●●●●●● ●●●●●●'), idBox = h('dd', { class: 'masked' }, '●●●●●●');
  const reveal = h('button', { class: 'btn small danger', type: 'button', onclick: () => {
    const why = input({ maxlength: 200, placeholder: 'Ex. Escorte vers le tribunal, audition' }), ok = h('button', { class: 'btn danger' }, 'Révéler');
    const md = modal('Révéler le vrai nom · ' + w.code, h('p', { class: 'muted', style: 'margin:0' }, 'Cette action est notée dans le journal d’audit avec ton nom et ton motif, et le Director est averti.'), field('Motif', why), foot({ close: () => md.close() }, ok));
    ok.addEventListener('click', () => run(ok, async () => {
      const r = await rpc('reveal_witness', { p_id: w.id, p_reason: why.value });
      nameBox.textContent = r.real_name; nameBox.classList.remove('masked'); idBox.textContent = r.new_identity || 'Pas encore attribuée'; idBox.classList.remove('masked'); reveal.remove();
      md.close(); return { message: 'Nom révélé. La révélation est journalisée.' };
    }));
  } }, ico('oeil', 16), 'Révéler le vrai nom');
  const info = (k, v) => [h('dt', {}, k), h('dd', {}, v)];
  ctx.show('witsec', 'Témoin ' + w.code, h('span', { class: 'pills' }, pill(...W_ST[w.status]), pill('Menace ' + RISK[w.threat][0].toLowerCase(), RISK[w.threat][1]), pill(...ID_ST[w.identity_status])),
    [h('button', { class: 'btn', type: 'button', onclick: () => witnessModal(ctx, w, d) }, 'Modifier'), h('button', { class: 'btn', type: 'button', onclick: () => eventModal(ctx, d.witnesses, w.id) }, ico('calendrier', 18), 'Planifier'),
      h('a', { class: 'btn ghost', href: base(ctx) + '/witsec' }, 'Tous les témoins')],
    h('div', { class: 'banner' }, ico('oeil', 20), h('div', {}, h('b', {}, 'Consultation enregistrée'), h('span', {}, 'Le Director est averti de chaque ouverture de cette fiche.'))),
    h('div', { class: 'grid cols2' },
      h('section', { class: 'card' }, h('h2', {}, 'Identité'), h('dl', { class: 'lines-dl rules' }, h('dt', {}, 'Vrai nom'), nameBox, h('dt', {}, 'Nouvelle identité'), idBox, info('Suivi avec le staff', w.identity_note || '—')),
        h('div', { class: 'acts' }, reveal)),
      h('section', { class: 'card' }, h('h2', {}, 'Protection'), h('dl', { class: 'lines-dl rules' }, info('Affaire', c ? h('a', { href: base(ctx) + '/dossiers/' + c.id }, c.num + ' · ' + c.title) : '—'),
        info('Témoigne contre', w.against || '—'), info('Planque', sh ? sh.code + ' · ' + sh.location_code : 'Aucune'), info('Sous protection depuis', day(w.created_at))), w.notes ? h('p', { class: 'prewrap' }, w.notes) : null)),
    h('section', { class: 'card' }, h('h2', {}, 'Témoignages et escortes'), table([['Date', e => when(e.starts_at), 'num', e => e.starts_at], ['Type', e => EV[e.kind]], ['Lieu', e => e.place || '—'], ['Notes', e => e.notes || '']], evs, null, 'Rien de prévu.')));
}

// ================================================================== SOG
function activateModal(ctx, cases) {
  const title = input({ maxlength: 80, placeholder: 'Ex. Assaut planque Grove Street' }), obj = area({ maxlength: 500, placeholder: 'Objectif de l’intervention' }), cas = select(caseOpts(cases), ''), ok = h('button', { class: 'btn danger' }, ico('bouclier', 18), 'Activer le SOG');
  const md = modal('Activation du SOG', h('p', { class: 'muted', style: 'margin:0' }, 'Tous les opérateurs sont alertés tout de suite. Deux sorties par semaine au plus.'), field('Opération', title), field('Objectif', obj), field('Dossier', cas), foot({ close: () => md.close() }, ok));
  ok.addEventListener('click', () => run(ok, async () => { const r = await rpc('activate_sog', { p_org: ctx.me.id, p_title: title.value, p_objective: obj.value, p_case: cas.value || null }); md.close(); ctx.go(base(ctx) + '/sog/' + r.id); return { message: 'SOG activé : ' + r.num + '. Les opérateurs sont alertés (' + r.week_count + '/2 cette semaine).' }; }));
}
function operatorModal(ctx, members, op) {
  const who = op ? null : select(memberOpts(members, 'Choisir…'), ''), spec = select(SPEC.map(x => [x, x]), op ? op.specialty : 'Assaut'), av = h('input', { type: 'checkbox', checked: op ? op.available : true }), notes = input({ maxlength: 300, value: op && op.notes ? op.notes : '' });
  const ok = h('button', { class: 'btn primary' }, 'Enregistrer'), del = op ? h('button', { class: 'btn danger', type: 'button' }, 'Retirer du SOG') : null;
  const md = modal(op ? 'Opérateur' : 'Ajouter un opérateur', who ? field('Agent', who) : null, field('Spécialité', spec), h('label', { class: 'check' }, av, 'Disponible'), field('Notes', notes), foot({ close: () => md.close() }, del, ok));
  if (del) del.addEventListener('click', () => run(del, async () => { await rpc('remove_operator', { p_member: op.member_id }); md.close(); ctx.reload(); return { message: 'Opérateur retiré.' }; }));
  ok.addEventListener('click', () => run(ok, async () => { await rpc('save_operator', { p_member: op ? op.member_id : who.value || null, p_specialty: spec.value, p_available: av.checked, p_notes: notes.value }); md.close(); ctx.reload(); return { message: 'Opérateur enregistré.' }; }));
}
function testModal(ctx, members) {
  const who = select(memberOpts(members, 'Choisir…'), ''), kind = select([['selection', 'Test de sélection'], ['recertification', 'Recertification']], 'selection'), passed = select([['true', 'Réussi'], ['false', 'Échoué']], 'true');
  const score = h('input', { type: 'number', min: 0, max: 100, class: 'short', placeholder: '/100' }), notes = input({ maxlength: 300 }), ok = h('button', { class: 'btn primary' }, 'Enregistrer');
  const md = modal('Test SOG', field('Agent', who), h('div', { class: 'row' }, field('Type', kind, 'grow'), field('Résultat', passed, 'grow'), field('Note', score)), field('Remarques', notes),
    h('p', { class: 'note' }, 'Une sélection réussie fait de l’agent un opérateur ; une recertification réussie la prolonge de 60 jours.'), foot({ close: () => md.close() }, ok));
  ok.addEventListener('click', () => run(ok, async () => { await rpc('add_sog_test', { p_member: who.value || null, p_kind: kind.value, p_passed: passed.value === 'true', p_score: score.value === '' ? null : Number(score.value), p_notes: notes.value }); md.close(); ctx.reload(); return { message: 'Test enregistré.' }; }));
}
export async function sog(ctx) {
  const { me, can } = ctx;
  const tab = ['operations', 'operateurs', 'tests'].includes(S.filters.sog) ? S.filters.sog : 'operations';
  const [missions, ops, tests, members, cases] = await Promise.all([rows('sog_missions', q => q.eq('org_id', me.id).order('activated_at', { ascending: false }).limit(200)), rows('sog_operators', q => q.eq('org_id', me.id)),
    rows('sog_tests', q => q.eq('org_id', me.id).order('at', { ascending: false }).limit(200)), load.members(ctx), rows('cases', q => q.eq('org_id', me.id).order('opened_at', { ascending: false }))]);
  const mOf = id => members.find(m => m.id === id), mine = ops.find(o => o.member_id === me.member_id);
  const ws = new Date(); ws.setHours(0, 0, 0, 0); ws.setDate(ws.getDate() - ((ws.getDay() + 6) % 7));
  const week = missions.filter(x => x.status !== 'annulee' && ms(x.activated_at) >= ws.getTime()).length;
  const live = ops.filter(o => (mOf(o.member_id) || {}).active);
  const due = o => o.recert_due && ms(o.recert_due) < Date.now() + 7 * DAY;
  const body = {
    operations: () => h('section', { class: 'card' }, table([['N°', x => h('b', { class: 'nowrap' }, x.num), null, x => x.num], ['Opération', x => x.title], ['Statut', x => pill(...MST[x.status])], ['Activée', x => when(x.activated_at) + ' · ' + x.activated_by, 'num', x => x.activated_at]],
      missions, x => ctx.go(base(ctx) + '/sog/' + x.id), 'Aucune opération.')),
    operateurs: () => h('section', { class: 'card' }, can('sog') ? h('div', { class: 'cardhead' }, h('p', { class: 'muted' }, 'Opérateurs certifiés, spécialité et disponibilité.'), h('button', { class: 'btn', type: 'button', onclick: () => operatorModal(ctx, members, null) }, ico('ajout', 18), 'Opérateur')) : null,
      table([['Opérateur', o => { const m = mOf(o.member_id) || {}; return h('span', { class: 'art' }, h('b', {}, (m.callsign ? m.callsign + ' · ' : '') + (m.name || '?')), h('small', {}, o.notes || '')); }], ['Spécialité', o => o.specialty],
        ['Disponibilité', o => o.available ? pill('Disponible', 'ok') : pill('Indisponible')], ['Certifié le', o => day(o.certified_at), 'num', o => o.certified_at],
        ['Recertification', o => o.recert_due ? (due(o) ? pill('Avant le ' + day(o.recert_due), 'warn') : day(o.recert_due)) : '—', 'num', o => o.recert_due || '']], live, can('sog') ? o => operatorModal(ctx, members, o) : null, 'Aucun opérateur.')),
    tests: () => h('section', { class: 'card' }, can('sog') ? h('div', { class: 'cardhead' }, h('p', { class: 'muted' }, 'Tests de sélection et recertifications.'), h('button', { class: 'btn', type: 'button', onclick: () => testModal(ctx, members) }, ico('ajout', 18), 'Enregistrer un test')) : null,
      table([['Date', t => when(t.at), 'num', t => t.at], ['Agent', t => (mOf(t.member_id) || {}).name || '?'], ['Type', t => t.kind === 'selection' ? 'Sélection' : 'Recertification'], ['Résultat', t => t.passed ? pill('Réussi', 'ok') : pill('Échoué', 'bad')],
        ['Note', t => t.score === null ? '—' : t.score + ' / 100', 'num'], ['Par', t => t.by_name]], tests, null, 'Aucun test.')),
  };
  ctx.show('sog', 'SOG', live.length + ' opérateur(s) · ' + week + ' / 2 sortie(s) cette semaine',
    [mine ? h('button', { class: 'btn', type: 'button', onclick: ev => run(ev.currentTarget, async () => { await rpc('set_sog_available', { p_org: me.id, p_available: !mine.available }); ctx.reload(); return { message: mine.available ? 'Tu es indisponible.' : 'Tu es disponible.' }; }) }, mine.available ? 'Me rendre indisponible' : 'Me rendre disponible') : null,
      can('sog') ? h('button', { class: 'btn danger', type: 'button', disabled: week >= 2, title: week >= 2 ? 'Limite de 2 sorties atteinte cette semaine' : null, onclick: () => activateModal(ctx, cases) }, ico('bouclier', 18), 'Activer le SOG') : null],
    h('section', { class: 'card' }, strip([['Opérateurs', String(live.length)], ['Disponibles', String(live.filter(o => o.available).length)], ['Sorties cette semaine', week + ' / 2', week >= 2 ? 'warn-t' : null], ['Recertifications à faire', String(live.filter(due).length), live.some(due) ? 'warn-t' : null]])),
    tabs([['operations', 'Opérations'], ['operateurs', 'Opérateurs'], ['tests', 'Tests']], tab, k => { S.filters.sog = k; sog(ctx).catch(ctx.fail); }), body[tab]());
}
export async function mission(ctx, id) {
  const { me, can } = ctx;
  const [ms0, members, reports] = await Promise.all([rows('sog_missions', q => q.eq('id', id)), load.members(ctx), ctx.has('rapports') ? rows('reports', q => q.eq('org_id', me.id).eq('kind', 'sog').order('created_at', { ascending: false })) : []]);
  const m = ms0[0];
  if (!m) return ctx.go(base(ctx) + '/sog', true);
  const urls = await signedUrls([m.map_path]), mOf = x => members.find(y => y.id === x), open = !['terminee', 'annulee'].includes(m.status), rep = reports.find(r => r.id === m.report_id);
  const brief = () => {
    const obj = area({ maxlength: 500, value: m.objective || '' }), plan = area({ maxlength: 3000, rows: 7, value: m.plan || '', placeholder: 'Approche, points d’entrée, signaux, repli' }), map = photoIn();
    const box = h('div', { class: 'linelist' });
    const addRow = (mid, role) => { const sl = select(memberOpts(members, 'Agent…'), mid || '', { 'aria-label': 'Opérateur' }), ri = input({ maxlength: 40, value: role || '', placeholder: 'Rôle (brèche, appui, tireur…)', 'aria-label': 'Rôle' });
      const row = h('div', { class: 'll-row' }, sl, ri, h('button', { class: 'btn small ghost', type: 'button', 'aria-label': 'Retirer', onclick: () => row.remove() }, ico('moins', 16))); row._get = () => ({ member_id: sl.value, role: ri.value.trim() }); box.append(row); };
    (m.roles.length ? m.roles : [{}]).forEach(r => addRow(r.member_id, r.role));
    const ok = h('button', { class: 'btn primary' }, 'Enregistrer le briefing');
    const md = modal('Briefing · ' + m.num, field('Objectif', obj), field('Plan', plan), field('Carte annotée (image)', map), h('div', { class: 'f' }, 'Répartition des rôles', box, h('button', { class: 'btn small', type: 'button', onclick: () => addRow('', '') }, ico('ajout', 16), 'Rôle')), foot({ close: () => md.close() }, ok));
    md.box.classList.add('wide');
    ok.addEventListener('click', () => run(ok, async () => {
      const path = await send(me.id, 'sog/' + m.id, map.files[0], true);
      await rpc('save_briefing', { p_id: m.id, p: { objective: obj.value, plan: plan.value, map_path: path, roles: [...box.children].map(r => r._get()).filter(r => r.member_id && r.role) } });
      md.close(); ctx.reload(); return { message: 'Briefing enregistré.' };
    }));
  };
  const close = () => {
    const st = select([['terminee', 'Terminée'], ['annulee', 'Annulée']], 'terminee'), deb = area({ maxlength: 3000, rows: 6, placeholder: 'Ce qui a fonctionné, ce qui doit changer' }), rp = select([['', 'Aucun']].concat(reports.map(r => [r.id, r.num + ' · ' + r.title])), '');
    const ok = h('button', { class: 'btn primary' }, 'Clôturer');
    const md = modal('Clôturer ' + m.num, field('Issue', st), field('Retour d’expérience', deb), field('Rapport d’intervention', rp), foot({ close: () => md.close() }, ok));
    ok.addEventListener('click', () => run(ok, async () => { await rpc('close_sog', { p_id: m.id, p_status: st.value, p_debrief: deb.value, p_report: rp.value || null }); md.close(); ctx.reload(); return { message: 'Opération clôturée.' }; }));
  };
  const toReport = () => { S.reportDraft = { kind: 'sog', case: m.case_id }; ctx.go(base(ctx) + '/rapports/nouveau'); };
  ctx.show('sog', m.num + ' · ' + m.title, h('span', { class: 'pills' }, pill(...MST[m.status]), ' Activée ' + ago(ms(m.activated_at)) + ' par ' + m.activated_by),
    [can('sog') && open ? [h('button', { class: 'btn', type: 'button', onclick: brief }, ico('journal', 18), 'Briefing'), h('button', { class: 'btn', type: 'button', onclick: close }, 'Clôturer')] : null,
      ctx.has('rapports') ? h('button', { class: 'btn', type: 'button', onclick: toReport }, ico('rapport', 18), 'Rédiger le rapport') : null, h('a', { class: 'btn ghost', href: base(ctx) + '/sog' }, 'Toutes les opérations')],
    h('div', { class: 'grid cols2' },
      h('section', { class: 'card' }, h('h2', {}, 'Briefing'), h('h3', { class: 'sub-h' }, 'Objectif'), h('p', { class: 'prewrap' }, m.objective || '—'), h('h3', { class: 'sub-h' }, 'Plan'), h('p', { class: 'prewrap' }, m.plan || 'Pas encore de plan.'),
        h('h3', { class: 'sub-h' }, 'Rôles'), m.roles.length ? h('ul', { class: 'people' }, m.roles.map(r => { const x = mOf(r.member_id) || {}; return h('li', {}, h('span', { class: 'ev-ic' }, ico('bouclier', 16)), h('span', { class: 'art' }, h('b', {}, (x.callsign ? x.callsign + ' · ' : '') + (x.name || '?')), h('small', {}, r.role))); })) : h('p', { class: 'muted' }, 'Rôles à répartir.')),
      h('section', { class: 'card' }, h('h2', {}, 'Carte annotée'), m.map_path && urls[m.map_path] ? h('a', { href: urls[m.map_path], target: '_blank', rel: 'noopener' }, h('img', { class: 'evphoto', src: urls[m.map_path], alt: 'Carte de l’opération ' + m.num })) : h('div', { class: 'empty' }, 'Aucune carte : ajoute-la au briefing.'))),
    !open ? h('section', { class: 'card' }, h('h2', {}, 'Retour d’expérience'), h('p', { class: 'prewrap' }, m.debrief || '—'), rep ? h('p', {}, 'Rapport d’intervention : ', h('a', { href: base(ctx) + '/rapports/' + rep.id }, rep.num)) : null) : null);
}

// ================================================================== renseignement
function intelModal(ctx, d) {
  const title = input({ maxlength: 120 }), bodyIn = area({ maxlength: 4000, rows: 6 }), cls = select(Object.entries(CLASS).filter(([k]) => ctx.can('renseignement') || ['diffusion', 'confidentiel'].includes(k)).map(([k, [l]]) => [k, l]), 'confidentiel');
  const rel = select([['', '—']].concat(REL.map(r => [r, r])), ''), inf = d.informants.length ? select([['', 'Aucun']].concat(d.informants.map(i => [i.id, i.code])), '') : null;
  const gang = select([['', 'Aucune']].concat(d.gangs.map(g => [g.id, g.name])), ''), per = select(peopleOpts(d.people, 'Aucun'), ''), cas = select(caseOpts(d.cases), ''), ok = h('button', { class: 'btn primary' }, 'Enregistrer la note');
  const md = modal('Note de renseignement', field('Titre', title), field('Contenu', bodyIn), h('div', { class: 'row' }, field('Classification', cls, 'grow'), field('Fiabilité', rel), inf ? field('Source', inf, 'grow') : null),
    h('div', { class: 'row' }, field('Organisation', gang, 'grow'), field('Individu', per, 'grow'), field('Dossier', cas, 'grow')), foot({ close: () => md.close() }, ok));
  md.box.classList.add('wide');
  ok.addEventListener('click', () => run(ok, async () => { await rpc('add_intel', { p_org: ctx.me.id, p: { title: title.value, body: bodyIn.value, classification: cls.value, reliability: rel.value, informant_id: inf ? inf.value : '', gang_id: gang.value, person_id: per.value, case_id: cas.value } }); md.close(); ctx.reload(); return { message: 'Note enregistrée.' }; }));
}
function informantModal(ctx, d, x) {
  const v = x || {};
  const code = input({ maxlength: 20, value: v.code || '', placeholder: 'Ex. COBRA', autocapitalize: 'characters' }), real = input({ maxlength: 80, value: v.real_name || '', autocomplete: 'off' }), rel = select(REL.map(r => [r, r]), v.reliability || 'C');
  const handler = select(memberOpts(d.members, 'Moi'), v.handler_id || ''), gang = select([['', 'Aucune']].concat(d.gangs.map(g => [g.id, g.name])), v.gang_id || ''), notes = area({ maxlength: 1000, value: v.notes || '' });
  const active = h('input', { type: 'checkbox', checked: x ? x.active : true }), ok = h('button', { class: 'btn primary' }, 'Enregistrer');
  const md = modal(x ? 'Informateur ' + x.code : 'Nouvel informateur', h('div', { class: 'row' }, field('Nom de code', code, 'grow'), field('Fiabilité (A à E)', rel)), field('Vrai nom (réservé au Renseignement)', real),
    h('div', { class: 'row' }, field('Agent traitant', handler, 'grow'), field('Milieu', gang, 'grow')), field('Notes', notes), h('label', { class: 'check' }, active, 'Actif'), foot({ close: () => md.close() }, ok));
  ok.addEventListener('click', () => run(ok, async () => { await rpc('save_informant', { p_org: ctx.me.id, p_id: x ? x.id : null, p: { code: code.value, real_name: real.value, reliability: rel.value, handler_id: handler.value, gang_id: gang.value, notes: notes.value, active: active.checked } }); md.close(); ctx.reload(); return { message: 'Informateur enregistré.' }; }));
}
function informantView(ctx, d, x) {
  const pays = d.payments.filter(p => p.informant_id === x.id), notes = d.intel.filter(n => n.informant_id === x.id);
  const amount = h('input', { type: 'number', min: 1, step: 50, class: 'short', 'aria-label': 'Montant ($)' }), why = input({ maxlength: 200, placeholder: 'Motif', 'aria-label': 'Motif du paiement' }), pay = h('button', { class: 'btn primary' }, 'Payer');
  const md = modal('Informateur ' + x.code, h('div', { class: 'pills' }, relPill(x.reliability), x.active ? pill('Actif', 'ok') : pill('Inactif')), h('p', { class: 'prewrap' }, x.notes || ''),
    h('h3', { class: 'sub-h' }, 'Paiements · ' + money(pays.reduce((a, p) => a + Number(p.amount), 0))), table([['Date', p => when(p.at), 'num', p => p.at], ['Montant', p => money(p.amount), 'num'], ['Motif', p => p.reason || '—'], ['Par', p => p.by_name]], pays, null, 'Aucun paiement.'),
    h('div', { class: 'row' }, h('div', { class: 'f' }, 'Montant ($)', amount), h('div', { class: 'f grow' }, 'Motif', why), pay),
    h('h3', { class: 'sub-h' }, 'Renseignements fournis'), table([['Date', n => day(n.at), 'num', n => n.at], ['Note', n => n.title], ['Classe', n => pill(...CLASS[n.classification])]], notes, null, 'Aucune note liée.'),
    h('div', { class: 'foot' }, h('button', { class: 'btn', type: 'button', onclick: () => { md.close(); informantModal(ctx, d, x); } }, 'Modifier'), h('button', { class: 'btn', type: 'button', onclick: () => md.close() }, 'Fermer')));
  md.box.classList.add('wide');
  pay.addEventListener('click', () => run(pay, async () => { await rpc('pay_informant', { p_informant: x.id, p_amount: Number(amount.value) || 0, p_reason: why.value }); md.close(); ctx.reload(); return { message: 'Paiement de ' + money(Number(amount.value)) + ' enregistré.' }; }));
}
// graphe des relations : individus (entourage, dossiers) et organisations ; placement par forces, calculé une fois
function relationGraph(ctx, people, links, gangs) {
  const nodes = [], idx = new Map(), edges = [];
  const add = (key, n) => { if (!idx.has(key)) { idx.set(key, nodes.length); nodes.push(n); } return idx.get(key); };
  links.forEach(l => { const a = people.find(p => p.id === l.person_id), b = people.find(p => p.id === l.other_id); if (!a || !b) return;
    edges.push([add('p' + a.id, { kind: 'p', id: a.id, label: fullName(a), danger: a.danger }), add('p' + b.id, { kind: 'p', id: b.id, label: fullName(b), danger: b.danger }), l.kind]); });
  people.filter(p => p.gang_id).forEach(p => { const g = gangs.find(x => x.id === p.gang_id); if (!g) return;
    edges.push([add('p' + p.id, { kind: 'p', id: p.id, label: fullName(p), danger: p.danger }), add('g' + g.id, { kind: 'g', id: g.id, label: g.name, color: g.color }), 'gang']); });
  if (!nodes.length) return h('div', { class: 'empty' }, 'Pas encore de relations : renseigne l’entourage des fiches et leur gang.');
  const W = 900, H = Math.max(420, Math.min(900, 160 + nodes.length * 26));
  nodes.forEach((n, i) => { const a = i / nodes.length * Math.PI * 2; n.x = W / 2 + Math.cos(a) * W / 3; n.y = H / 2 + Math.sin(a) * H / 3; n.vx = 0; n.vy = 0; });
  for (let it = 0; it < 320; it++) {
    for (let i = 0; i < nodes.length; i++) for (let j = i + 1; j < nodes.length; j++) {
      const a = nodes[i], b = nodes[j]; let dx = a.x - b.x, dy = a.y - b.y; const d2 = dx * dx + dy * dy + .01, f = 5200 / d2, dd = Math.sqrt(d2);
      dx /= dd; dy /= dd; a.vx += dx * f; a.vy += dy * f; b.vx -= dx * f; b.vy -= dy * f;
    }
    edges.forEach(([i, j]) => { const a = nodes[i], b = nodes[j], dx = b.x - a.x, dy = b.y - a.y, dd = Math.sqrt(dx * dx + dy * dy) || 1, k = (dd - 120) / dd * .06;
      a.vx += dx * k; a.vy += dy * k; b.vx -= dx * k; b.vy -= dy * k; });
    nodes.forEach(n => { n.vx += (W / 2 - n.x) * .004; n.vy += (H / 2 - n.y) * .004; n.x = Math.min(W - 60, Math.max(60, n.x + n.vx * .5)); n.y = Math.min(H - 40, Math.max(40, n.y + n.vy * .5)); n.vx *= .6; n.vy *= .6; });
  }
  const LBL = { famille: 'famille', proche: 'proche', complice: 'complice', associe: 'associé', rival: 'rival', gang: '' };
  const svg = s('svg', { class: 'relgraph', viewBox: '0 0 ' + W + ' ' + H, role: 'img', 'aria-label': 'Graphe des relations : ' + nodes.length + ' éléments' },
    edges.map(([i, j, k]) => [s('line', { x1: nodes[i].x, y1: nodes[i].y, x2: nodes[j].x, y2: nodes[j].y, class: 'rg-edge rg-' + k }),
      LBL[k] ? s('text', { x: (nodes[i].x + nodes[j].x) / 2, y: (nodes[i].y + nodes[j].y) / 2 - 4, class: 'rg-elabel' }, LBL[k]) : null]),
    nodes.map(n => s('a', { href: base(ctx) + (n.kind === 'g' ? '/organisations/' : '/individus/') + n.id },
      n.kind === 'g' ? s('rect', { x: n.x - 24, y: n.y - 16, width: 48, height: 32, rx: 8, class: 'rg-gang', style: 'fill:' + (n.color || 'var(--chip)') }) : s('circle', { cx: n.x, cy: n.y, r: 17, class: 'rg-person rg-' + (n.danger || 'faible') }),
      s('text', { x: n.x, y: n.y + 4, class: 'rg-init' }, n.kind === 'g' ? '' : initials(n.label)), s('text', { x: n.x, y: n.y + (n.kind === 'g' ? 32 : 34), class: 'rg-label' }, n.label))));
  return h('div', { class: 'scroll' }, svg);
}
export async function renseignement(ctx) {
  const { me, can } = ctx;
  const tab = ['notes', 'informateurs', 'graphe'].includes(S.filters.ren) && (S.filters.ren !== 'informateurs' || can('renseignement')) ? S.filters.ren : 'notes';
  const [intel, informants, payments, gangs, people, cases, members, links] = await Promise.all([rows('intel', q => q.eq('org_id', me.id).order('at', { ascending: false }).limit(300)),
    can('renseignement') ? rows('informants', q => q.eq('org_id', me.id).order('code')) : [], can('renseignement') ? rows('informant_payments', q => q.eq('org_id', me.id).order('at', { ascending: false })) : [],
    rows('gangs', q => q.eq('org_id', me.id).order('name')), load.people(ctx), rows('cases', q => q.eq('org_id', me.id).order('opened_at', { ascending: false })), load.members(ctx),
    tab === 'graphe' ? rows('person_links', q => q.eq('org_id', me.id)) : []]);
  const d = { intel, informants, payments, gangs, people, cases, members };
  const gOf = id => gangs.find(g => g.id === id), pOf = id => people.find(p => p.id === id), cOf = id => cases.find(c => c.id === id), iOf = id => informants.find(i => i.id === id);
  const body = {
    notes: () => h('div', { class: 'stack' }, intel.length ? intel.map(n => h('section', { class: 'card intelcard ' + n.classification }, h('div', { class: 'ann-top' }, pill(...CLASS[n.classification]), relPill(n.reliability), n.informant_id && iOf(n.informant_id) ? pill('Source ' + iOf(n.informant_id).code) : null,
      h('span', { class: 'muted small grow' }, n.by_name + ' · ' + when(n.at))), h('h2', {}, n.title), h('p', { class: 'prewrap' }, n.body),
      h('div', { class: 'pills' }, n.gang_id && gOf(n.gang_id) ? h('a', { class: 'pill', href: base(ctx) + '/organisations/' + n.gang_id }, gOf(n.gang_id).name) : null,
        n.person_id && pOf(n.person_id) ? h('a', { class: 'pill', href: base(ctx) + '/individus/' + n.person_id }, fullName(pOf(n.person_id))) : null,
        n.case_id && cOf(n.case_id) ? h('a', { class: 'pill', href: base(ctx) + '/dossiers/' + n.case_id }, cOf(n.case_id).num) : null)))
      : h('section', { class: 'card' }, h('div', { class: 'empty' }, 'Aucune note à ton niveau d’accès.'))),
    informateurs: () => h('section', { class: 'card' }, h('div', { class: 'cardhead' }, h('p', { class: 'muted' }, 'Fiches sous nom de code. Le vrai nom n’apparaît que dans la fiche.'), h('button', { class: 'btn', type: 'button', onclick: () => informantModal(ctx, d, null) }, ico('ajout', 18), 'Informateur')),
      table([['Code', x => h('b', {}, x.code)], ['Fiabilité', x => relPill(x.reliability), null, x => x.reliability], ['Agent traitant', x => (members.find(m => m.id === x.handler_id) || {}).name || '—'], ['Milieu', x => (gOf(x.gang_id) || {}).name || '—'],
        ['Payé', x => money(payments.filter(p => p.informant_id === x.id).reduce((a, p) => a + Number(p.amount), 0)), 'num'], ['Notes', x => String(intel.filter(n => n.informant_id === x.id).length), 'num'], ['', x => x.active ? pill('Actif', 'ok') : pill('Inactif')]],
      informants, x => informantView(ctx, d, x), 'Aucun informateur.')),
    graphe: () => h('section', { class: 'card' }, h('h2', {}, 'Qui connaît qui'), h('p', { class: 'muted', style: 'margin-top:-6px' }, 'Entourage des fiches et appartenance aux organisations. Clique un point pour ouvrir la fiche.'),
      relationGraph(ctx, people, links, gangs), h('div', { class: 'rg-legend muted small' }, h('span', {}, h('i', { class: 'rg-dot rg-extreme' }), 'Danger élevé ou extrême'), h('span', {}, h('i', { class: 'rg-dot rg-faible' }), 'Autre individu'), h('span', {}, h('i', { class: 'rg-sq' }), 'Organisation'))),
  };
  ctx.show('renseignement', 'Renseignement', intel.length + ' note(s) visibles à ton niveau' + (can('renseignement') ? ' · ' + informants.filter(i => i.active).length + ' informateur(s) actif(s)' : ''),
    can('dossiers') || can('renseignement') ? h('button', { class: 'btn primary', type: 'button', onclick: () => intelModal(ctx, d) }, ico('ajout', 18), 'Nouvelle note') : null,
    tabs([['notes', 'Notes'], can('renseignement') ? ['informateurs', 'Informateurs'] : null, ['graphe', 'Graphe des relations']], tab, k => { S.filters.ren = k; renseignement(ctx).catch(ctx.fail); }), body[tab]());
}
