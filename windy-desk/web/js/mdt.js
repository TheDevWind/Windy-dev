// ================================================================== Windy Desk · MDT des services publics (US Marshals)
// Tableau de bord, effectifs et fiche agent, présence, dossiers et individus, mandats, Most Wanted, annonces,
// réglages propres aux services, espace Justice et page publique des avis de recherche.
// Comme le reste du site : lecture des tables (les règles RLS filtrent), écriture par les fonctions SQL.
import { S, h, ico, modal, toast, run, table, pill, strip, fchips, money, initials, date, ago, clock, liveDot, copyText, slug, themeSwitch, butterfly, BRAND } from './ui.js';
import { rpc, rows, upload, signedUrls, publicUrl } from './sb.js';
import { saveBlob } from './doc.js';
import { KIND, DANGER, personCards, caseCards, reportAlerts } from './mdt-enquete.js';

import { DAY, HOUR, ms, iso, day, field, input, area, select, foot, fullName, today, toLocal, weekStart, hours, fmtH, tabs, search, match, STATUS, OPEN, PRIO, WKIND, WST, wStatus, DUTY, STAGE, CAREER, LOG, ANN, AGENDA, IMAGE, ANY, stamp, extOf, send, photoIn, face, personCell, feed, load, gradeOf, myRank, absentToday, stateOf, inactiveDays } from './mdt-kit.js';

// ---------------------------------------------------------------- prise de service (tableau de bord et présence)
function dutyCard(ctx, mine, meRow) {
  const status = meRow ? meRow.duty_status : 'dispo';
  const toggle = h('button', { class: 'btn' + (mine ? '' : ' primary'), type: 'button', onclick: ev => run(ev.currentTarget, async () => {
    const on = await rpc('mdt_duty', { p_org: ctx.me.id }); ctx.reload(); return { message: on ? 'Bon service !' : 'Fin de service enregistrée.' };
  }) }, ico('horloge', 18), mine ? 'Finir mon service' : 'Prendre mon service');
  const seg = mine ? h('div', { class: 'seg', role: 'group', 'aria-label': 'Mon statut' }, Object.entries(DUTY).map(([k, [l]]) => h('button', { type: 'button', class: k === status ? 'on' : null, 'aria-pressed': String(k === status),
    onclick: ev => run(ev.currentTarget, async () => { await rpc('set_duty_status', { p_org: ctx.me.id, p_status: k }); ctx.reload(); return { message: 'Statut : ' + l.toLowerCase() + '.' }; }) }, l))) : null;
  return h('section', { class: 'card dutycard' + (mine ? ' on' : '') }, h('div', { class: 'dc-who' }, h('span', { class: 'dc-ic' }, ico('etoile', 20)),
    h('span', { class: 'art' }, h('b', {}, meRow ? (meRow.callsign || meRow.name) + ' · ' + (meRow.matricule || '') : ctx.me.member_name),
      mine ? liveDot('En service depuis ' + clock(ms(mine.started_at))) : h('small', {}, 'Hors service'))), h('div', { class: 'dc-acts' }, seg, toggle));
}

// ================================================================== tableau de bord
export async function dashboard(ctx) {
  const { me, can, has } = ctx;
  const [members, grades, open, cases, warrants, wanted, people, anns, reads, agenda, absences, tips] = await Promise.all([
    load.members(ctx), load.grades(ctx),
    has('presence') ? rows('shifts', q => q.eq('org_id', me.id).is('ended_at', null)) : [],
    has('dossiers') ? rows('cases', q => q.eq('org_id', me.id).is('closed_at', null)) : [],
    has('mandats') ? rows('warrants', q => q.eq('org_id', me.id).order('created_at', { ascending: false }).limit(200)) : [],
    has('wanted') ? rows('wanted', q => q.eq('org_id', me.id).order('rank')) : [],
    has('dossiers') || has('wanted') ? load.people(ctx) : [],
    has('annonces') ? rows('announcements', q => q.eq('org_id', me.id).order('at', { ascending: false }).limit(20)) : [],
    has('annonces') ? rows('announcement_reads', q => q.eq('member_id', me.member_id)) : [],
    has('presence') ? rows('agenda', q => q.eq('org_id', me.id).gte('ends_at', iso(Date.now())).order('starts_at').limit(5)) : [],
    has('presence') ? rows('absences', q => q.eq('org_id', me.id).eq('status', 'validee').gte('ends_on', today())) : [],
    has('wanted') && can('wanted') ? rows('tips', q => q.eq('org_id', me.id).eq('status', 'nouveau')) : []]);
  const alerts = await reportAlerts(ctx);
  const active = members.filter(m => m.active), meRow = members.find(m => m.id === me.member_id);
  const urls = await signedUrls(people.filter(p => wanted.some(w => w.person_id === p.id)).map(p => p.photo).concat(active.map(m => m.photo)));
  const pOf = id => people.find(p => p.id === id), mOf = id => members.find(m => m.id === id);
  const unread = anns.filter(a => !reads.some(r => r.announcement_id === a.id) && (a.level !== 'info' || a.pinned));
  const mine = open.find(s => s.member_id === me.member_id);
  const prio = cases.filter(c => c.priority !== 'normale').sort((a, b) => (b.priority === 'most_wanted') - (a.priority === 'most_wanted') || ms(b.opened_at) - ms(a.opened_at));
  const pending = warrants.filter(w => w.status === 'envoye'), toRun = warrants.filter(w => wStatus(w) === 'signe');
  const out = absences.filter(a => a.starts_on <= today());
  const base = '/' + me.code;
  ctx.show('accueil', me.name, [pill(me.kind, 'info'), ' ', me.grade + ' · ' + new Date().toLocaleDateString('fr-FR', { weekday: 'long', day: 'numeric', month: 'long' })], null,
    unread.map(a => h('div', { class: 'banner' + (a.level === 'urgent' ? ' bad' : '') }, ico('megaphone', 20), h('div', {}, h('b', {}, a.title), h('span', {}, a.body.slice(0, 160) + (a.body.length > 160 ? '…' : ''))),
      h('button', { class: 'btn small', type: 'button', onclick: ev => run(ev.currentTarget, async () => { await rpc('read_announcement', { p_id: a.id }); ctx.reload(); return { message: 'Annonce marquée comme lue.' }; }) }, 'J’ai lu'))),
    alerts.banner,
    has('presence') ? dutyCard(ctx, mine, meRow) : null,
    h('section', { class: 'card' }, strip([has('presence') ? ['En service', open.length + ' / ' + active.length] : ['Effectif', String(active.length)],
      has('dossiers') ? ['Dossiers actifs', String(cases.length)] : null, has('mandats') ? ['Mandats en attente', String(pending.length), pending.length ? 'warn-t' : null] : null,
      has('mandats') ? ['Mandats à exécuter', String(toRun.length)] : null, has('wanted') && can('wanted') ? ['Signalements', String(tips.length), tips.length ? 'warn-t' : null] : null,
      alerts.pending !== null ? ['Rapports à valider', String(alerts.pending), alerts.pending ? 'warn-t' : null] : null])),
    h('div', { class: 'dash' },
      h('div', { class: 'col' },
        has('presence') ? h('section', { class: 'card' }, h('h2', {}, 'En service maintenant', h('span', { class: 'right' }, h('a', { href: base + '/presence' }, 'Présence'))),
          open.length ? h('ul', { class: 'people' }, open.map(s => mOf(s.member_id)).filter(Boolean).map(m => h('li', {}, face(m.photo, urls, m.name, 'sm'),
            h('span', { class: 'art grow' }, h('b', {}, (m.callsign ? m.callsign + ' · ' : '') + m.name), h('small', {}, gradeOf(grades, m.grade_id).name)), pill(...DUTY[m.duty_status] || DUTY.dispo))))
            : h('div', { class: 'empty' }, 'Personne n’est en service.')) : null,
        has('dossiers') ? h('section', { class: 'card' }, h('h2', {}, 'Dossiers prioritaires', h('span', { class: 'right' }, h('a', { href: base + '/dossiers' }, 'Tous les dossiers'))),
          table([['N°', c => h('b', { class: 'nowrap' }, c.num)], ['Individu', c => fullName(pOf(c.person_id))], ['Statut', c => pill(...STATUS[c.status])], ['Priorité', c => pill(...PRIO[c.priority])],
            ['Vu', c => c.last_seen ? h('span', { class: 'art' }, c.last_seen, h('small', {}, ago(ms(c.last_seen_at)))) : '—']], prio.slice(0, 8), c => ctx.go(base + '/dossiers/' + c.id), 'Aucun dossier prioritaire.')) : null),
      h('div', { class: 'col aside' },
        has('wanted') ? h('section', { class: 'card' }, h('h2', {}, 'Most Wanted', h('span', { class: 'right' }, h('a', { href: base + '/wanted' }, 'Classement'))),
          wanted.length ? h('ol', { class: 'board' }, wanted.slice(0, 5).map(w => { const p = pOf(w.person_id); return h('li', {}, h('span', { class: 'rank' }, String(w.rank)),
            h('span', { class: 'person' }, face(p && p.photo, urls, fullName(p), 'sm'), h('span', { class: 'art' }, h('b', {}, fullName(p)), h('small', {}, w.reason + (Number(w.reward) ? ' · ' + money(w.reward) : ''))))); }))
            : h('div', { class: 'empty' }, 'Classement vide.')) : null,
        has('presence') ? h('section', { class: 'card' }, h('h2', {}, 'Planning'), agenda.length ? h('ul', { class: 'people' }, agenda.map(a => h('li', {}, h('span', { class: 'ev-ic' }, ico('calendrier', 16)),
          h('span', { class: 'art' }, h('b', {}, a.title), h('small', {}, AGENDA[a.kind] + ' · ' + date(ms(a.starts_at)) + (a.place ? ' · ' + a.place : ''))))))
          : h('div', { class: 'empty' }, 'Rien de prévu.')) : null,
        has('presence') && out.length ? h('section', { class: 'card' }, h('h2', {}, 'Absents aujourd’hui'), h('ul', { class: 'people' }, out.map(a => { const m = mOf(a.member_id); return m ? h('li', {}, face(m.photo, urls, m.name, 'sm'), h('span', { class: 'art' }, h('b', {}, m.name), h('small', {}, 'jusqu’au ' + day(a.ends_on)))) : null; }))) : null)));
}

// ================================================================== effectifs
function recruitModal(ctx, grades, rank) {
  const name = input({ maxlength: 60, placeholder: 'Prénom Nom (nom RP)' }), login = input({ maxlength: 40, autocapitalize: 'none', spellcheck: 'false' });
  let touched = false;
  name.addEventListener('input', () => { if (!touched) login.value = slug(name.value).replace('-', '.'); });
  login.addEventListener('input', () => { touched = true; });
  const below = grades.filter(g => g.rank <= rank);
  const grade = select(below.map(g => [g.id, g.name + (g.max_seats ? ' (' + g.max_seats + (g.max_seats > 1 ? ' places)' : ' place)') : '')]), below.length ? below[below.length - 1].id : null);
  const call = input({ maxlength: 20, placeholder: 'Ex. Marshal-12' }), disc = input({ maxlength: 25, inputmode: 'numeric', placeholder: 'Identifiant Discord (chiffres)' });
  const ok = h('button', { class: 'btn primary' }, 'Recruter');
  const m = modal('Recruter un agent', field('Nom', name), h('div', { class: 'row' }, field('Identifiant de connexion', login, 'grow'), field('Grade', grade, 'grow')),
    h('div', { class: 'row' }, field('Indicatif', call, 'grow'), field('Discord (facultatif)', disc, 'grow')),
    h('p', { class: 'note' }, 'Le matricule est attribué tout seul. Un lien d’activation est créé : l’agent y choisit Discord ou un mot de passe.'), foot({ close: () => m.close() }, ok));
  ok.addEventListener('click', () => run(ok, async () => {
    if (name.value.trim().length < 2) throw new Error('Indique le nom de l’agent.');
    if (!/^[a-z0-9]+([._-][a-z0-9]+)*$/.test(login.value.trim())) throw new Error('Identifiant invalide : lettres minuscules, chiffres, point ou tiret.');
    const r = await rpc('recruit_agent', { p_org: ctx.me.id, p_name: name.value.trim(), p_login: login.value.trim(), p_grade: grade.value, p_callsign: call.value.trim() || null, p_discord: disc.value.trim() || null });
    m.close(); ctx.reload(); ctx.linkModal('Agent recruté · ' + r.matricule, ctx.activationUrl(ctx.me.code, r.token), name.value.trim());
  }));
}

export async function effectifs(ctx) {
  const { me, can, has } = ctx;
  const tab = ['agents', 'organigramme', 'anciens', 'noire'].includes(S.filters.eff) ? S.filters.eff : 'agents';
  const [members, grades, units, mu, quals, mq, shifts, absences, black] = await Promise.all([load.members(ctx), load.grades(ctx), load.units(ctx),
    rows('member_units', q => q.eq('org_id', me.id)), rows('qualifications', q => q.eq('org_id', me.id).order('name')), rows('member_qualifications', q => q.eq('org_id', me.id)),
    has('presence') ? rows('shifts', q => q.eq('org_id', me.id).gte('started_at', iso(Date.now() - 30 * DAY)).order('started_at', { ascending: false }).limit(3000)) : [],
    has('presence') ? rows('absences', q => q.eq('org_id', me.id).eq('status', 'validee').gte('ends_on', today())) : [],
    can('effectifs') ? rows('blacklist', q => q.eq('org_id', me.id).order('at', { ascending: false })) : []]);
  const active = members.filter(m => m.active), gone = members.filter(m => !m.active);
  const urls = await signedUrls(members.map(m => m.photo));
  const open = shifts.filter(x => !x.ended_at), rank = myRank(ctx, members, grades), base = '/' + me.code + '/effectifs/';
  const unitsOf = id => mu.filter(x => x.member_id === id).map(x => [units.find(u => u.id === x.unit_id), x.main]).filter(([u]) => u).sort((a, b) => b[1] - a[1]);
  const setTab = k => { S.filters.eff = k; effectifs(ctx).catch(ctx.fail); };
  const list = h('div', {});
  const paint = () => list.replaceChildren(table([
    ['Agent', m => personCell(m.name, m.matricule, m.photo, urls), null, m => m.name],
    ['Indicatif', m => m.callsign || '—'],
    ['Grade', m => { const g = gradeOf(grades, m.grade_id); return h('span', { class: 'gradecell' }, g.badge ? h('span', { class: 'insigne' }, g.badge) : null, g.name); }, null, m => -gradeOf(grades, m.grade_id).rank],
    ['Unités', m => { const u = unitsOf(m.id); return u.length ? h('span', { class: 'pills' }, u.map(([x, main]) => pill(x.code, main ? 'info' : null))) : h('span', { class: 'muted' }, '—'); }],
    ['Parcours', m => m.stage === 'titulaire' ? h('span', { class: 'muted' }, 'Titulaire') : pill(...STAGE[m.stage])],
    ['Qualifications', m => String(mq.filter(x => x.member_id === m.id).length), 'num', m => mq.filter(x => x.member_id === m.id).length],
    ['Statut', m => stateOf(m, open, absences, shifts)]],
    active.filter(m => match(S.filters.effQ, m.name, m.matricule, m.callsign, m.login)), m => ctx.go(base + m.id), 'Aucun agent ne correspond.'));
  paint();
  const seats = g => active.filter(m => m.grade_id === g.id).length;
  const body = {
    agents: () => h('section', { class: 'card' }, search(S.filters.effQ, 'Chercher un agent, un matricule, un indicatif', v => { S.filters.effQ = v; paint(); }), list),
    organigramme: () => [h('div', { class: 'orgchart' }, grades.map(g => h('section', { class: 'card og' + (g.max_seats && seats(g) >= g.max_seats ? ' full' : '') },
      h('div', { class: 'og-head' }, g.badge ? h('span', { class: 'insigne big' }, g.badge) : null, h('b', {}, g.name), h('span', { class: 'og-seats' }, seats(g) + (g.max_seats ? ' / ' + g.max_seats : '') + ' agent(s)')),
      h('ul', { class: 'og-list' }, active.filter(m => m.grade_id === g.id).map(m => h('li', {}, h('a', { href: base + m.id }, face(m.photo, urls, m.name, 'sm'), h('span', { class: 'art' }, h('b', {}, m.name), h('small', {}, [m.callsign, m.matricule].filter(Boolean).join(' · ')))))),
        g.max_seats && seats(g) < g.max_seats ? h('li', { class: 'free' }, (g.max_seats - seats(g)) + ' place(s) libre(s)') : null)))),
      h('section', { class: 'card' }, h('h2', {}, 'Unités'), h('div', { class: 'unitgrid' }, units.filter(u => !u.archived).map(u => {
        const inU = mu.filter(x => x.unit_id === u.id).map(x => [members.find(m => m.id === x.member_id), x.main]).filter(([m]) => m && m.active);
        return h('div', { class: 'unitcard' }, h('div', { class: 'u-top' }, h('b', {}, u.code), u.restricted ? pill('Restreinte', 'warn') : null, h('span', { class: 'muted small' }, inU.length + ' agent(s)')),
          h('p', { class: 'muted small' }, u.name), inU.length ? h('div', { class: 'pills' }, inU.map(([m, main]) => pill(m.callsign || m.name, main ? 'info' : null))) : h('span', { class: 'muted small' }, 'Aucun agent'));
      })))],
    anciens: () => h('section', { class: 'card' }, table([['Agent', m => personCell(m.name, m.matricule, m.photo, urls)], ['Dernier grade', m => gradeOf(grades, m.grade_id).name],
      ['Parti le', m => m.left_at ? day(m.left_at) : '—', 'num', m => m.left_at], ['Motif', m => m.left_reason || '—']], gone, m => ctx.go(base + m.id), 'Aucun ancien agent.')),
    noire: () => h('section', { class: 'card' }, h('div', { class: 'cardhead' }, h('p', { class: 'muted' }, 'Personne sur cette liste ne peut être recrutée ni réintégrée (même nom ou même identifiant Discord).'),
      h('button', { class: 'btn', type: 'button', onclick: () => blackModal(ctx) }, ico('ajout', 18), 'Ajouter')),
      table([['Nom', b => h('b', {}, b.name)], ['Discord', b => b.discord_id || '—'], ['Motif', b => b.reason], ['Ajouté', b => day(b.at) + ' · ' + b.by_name, 'num', b => b.at],
        ['', b => h('button', { class: 'btn small', type: 'button', onclick: ev => { ev.stopPropagation(); run(ev.currentTarget, async () => { await rpc('blacklist_remove', { p_id: b.id }); ctx.reload(); return { message: b.name + ' retiré de la liste noire.' }; }); } }, 'Retirer')]],
      black, null, 'La liste noire est vide.')),
  };
  ctx.show('effectifs', 'Effectifs', active.length + ' agent(s) en poste · ' + open.length + ' en service',
    can('effectifs') ? h('button', { class: 'btn primary', type: 'button', onclick: () => recruitModal(ctx, grades, rank) }, ico('ajout', 18), 'Recruter') : null,
    tabs([['agents', 'Agents'], ['organigramme', 'Organigramme'], ['anciens', 'Anciens (' + gone.length + ')'], can('effectifs') ? ['noire', 'Liste noire (' + black.length + ')'] : null], tab, setTab),
    body[tab === 'noire' && !can('effectifs') ? 'agents' : tab]());
}
function blackModal(ctx) {
  const name = input({ maxlength: 60 }), disc = input({ maxlength: 25, inputmode: 'numeric' }), why = area({ maxlength: 200 });
  const ok = h('button', { class: 'btn danger' }, 'Ajouter à la liste noire');
  const m = modal('Liste noire', field('Nom', name), field('Discord (facultatif)', disc), field('Motif', why), foot({ close: () => m.close() }, ok));
  ok.addEventListener('click', () => run(ok, async () => { await rpc('blacklist_add', { p_org: ctx.me.id, p_name: name.value.trim(), p_discord: disc.value.trim() || null, p_reason: why.value.trim() }); m.close(); ctx.reload(); return { message: 'Ajouté à la liste noire.' }; }));
}

// ---------------------------------------------------------------- fiche agent
export async function agent(ctx, id) {
  const { me, can, has } = ctx;
  const [members, grades, units, mu, quals, mq, career, shifts, absences] = await Promise.all([load.members(ctx), load.grades(ctx), load.units(ctx),
    rows('member_units', q => q.eq('member_id', id)), rows('qualifications', q => q.eq('org_id', me.id).order('name')), rows('member_qualifications', q => q.eq('member_id', id)),
    rows('career_events', q => q.eq('member_id', id).order('at', { ascending: false }).limit(200)),
    has('presence') ? rows('shifts', q => q.eq('member_id', id).order('started_at', { ascending: false }).limit(200)) : [],
    has('presence') ? rows('absences', q => q.eq('member_id', id).order('starts_on', { ascending: false }).limit(20)) : []]);
  const m = members.find(x => x.id === id);
  if (!m) return ctx.go('/' + me.code + '/effectifs', true);
  const urls = await signedUrls([m.photo]);
  const g = gradeOf(grades, m.grade_id), rank = myRank(ctx, members, grades), top = Math.max(...grades.map(x => x.rank));
  const self = m.id === me.member_id, boss = can('effectifs') && g.rank <= rank;
  const manage = boss && !self, selfOk = can('effectifs') && (!self || rank === top);
  const open = shifts.find(x => !x.ended_at);
  const myUnits = mu.map(x => [units.find(u => u.id === x.unit_id), x.main]).filter(([u]) => u);
  const r = (name, args, msg) => async () => { await rpc(name, args()); ctx.reload(); return { message: msg }; };

  const editModal = () => {
    const call = input({ maxlength: 20, value: m.callsign || '', disabled: !boss }), phone = input({ maxlength: 20, value: m.phone || '', placeholder: '555-0100' });
    const disc = input({ maxlength: 25, value: m.discord_id || '', inputmode: 'numeric' }), fivem = input({ maxlength: 60, value: m.fivem_id || '', placeholder: 'license:…' });
    const photo = photoIn(), ok = h('button', { class: 'btn primary' }, 'Enregistrer');
    const md = modal('Fiche de ' + m.name, h('div', { class: 'row' }, field('Indicatif', call, 'grow'), field('Téléphone', phone, 'grow')),
      h('div', { class: 'row' }, field('Discord (chiffres)', disc, 'grow'), field('Identifiant FiveM', fivem, 'grow')), field('Photo (10 Mo au plus)', photo),
      boss ? null : h('p', { class: 'note' }, 'L’indicatif est donné par les responsables des effectifs.'), foot({ close: () => md.close() }, ok));
    ok.addEventListener('click', () => run(ok, async () => {
      const path = await send(me.id, 'agents/' + m.id, photo.files[0], true);
      await rpc('save_agent', { p_member: m.id, p_callsign: call.value.trim(), p_phone: phone.value.trim(), p_discord: disc.value.trim(), p_fivem: fivem.value.trim(), p_photo: path });
      md.close(); ctx.reload(); return { message: 'Fiche enregistrée.' };
    }));
  };
  const gradeModal = () => {
    const opts = grades.filter(x => x.rank <= rank && x.id !== m.grade_id);
    const sel = select(opts.map(x => [x.id, x.name + (x.rank > g.rank ? ' (promotion)' : ' (rétrogradation)')]), opts[0] && opts[0].id), note = input({ maxlength: 120, placeholder: 'Ex. Belle arrestation, faute grave…' });
    const ok = h('button', { class: 'btn primary' }, 'Changer de grade');
    const md = modal('Grade de ' + m.name, field('Nouveau grade', sel), field('Motif (facultatif)', note), foot({ close: () => md.close() }, ok));
    ok.addEventListener('click', () => run(ok, async () => { await rpc('promote_agent', { p_member: m.id, p_grade: sel.value, p_note: note.value.trim() || null }); md.close(); ctx.reload(); return { message: 'Grade mis à jour.' }; }));
  };
  const unitsModal = () => {
    const live = units.filter(u => !u.archived);
    let main = (myUnits.find(([, x]) => x) || [{}])[0].id || null;
    const boxes = live.map(u => [u, h('input', { type: 'checkbox', checked: myUnits.some(([x]) => x.id === u.id) }), h('input', { type: 'radio', name: 'main', checked: main === u.id, 'aria-label': 'Unité principale : ' + u.code })]);
    boxes.forEach(([u, cb, rd]) => rd.addEventListener('change', () => { main = u.id; cb.checked = true; }));
    const ok = h('button', { class: 'btn primary' }, 'Enregistrer');
    const md = modal('Unités de ' + m.name, h('div', { class: 'unitpick' }, h('div', { class: 'up-head muted small' }, h('span', {}, 'Unité'), h('span', {}, 'Principale')),
      boxes.map(([u, cb, rd]) => h('div', { class: 'up-row' }, h('label', { class: 'check' }, cb, h('span', {}, h('b', {}, u.code), ' ', h('small', { class: 'muted' }, u.name))), rd))), foot({ close: () => md.close() }, ok));
    ok.addEventListener('click', () => run(ok, async () => {
      const ids = boxes.filter(([, cb]) => cb.checked).map(([u]) => u.id);
      await rpc('set_agent_units', { p_member: m.id, p_units: ids, p_main: ids.includes(main) ? main : (ids[0] || null) }); md.close(); ctx.reload(); return { message: 'Unités enregistrées.' };
    }));
  };
  const noteModal = () => {
    const kind = select([['felicitation', 'Félicitation'], ['sanction', 'Sanction'], ['note', 'Note (visible par l’agent)'], ['commandement', 'Note du commandement (invisible pour l’agent)']], 'note');
    const t = area({ maxlength: 300 }), ok = h('button', { class: 'btn primary' }, 'Ajouter');
    const md = modal('Carrière de ' + m.name, field('Type', kind), field('Texte', t), foot({ close: () => md.close() }, ok));
    ok.addEventListener('click', () => run(ok, async () => { await rpc('career_note', { p_member: m.id, p_kind: kind.value, p_text: t.value.trim() }); md.close(); ctx.reload(); return { message: 'Ajouté à la carrière.' }; }));
  };
  const leaveModal = () => {
    const t = area({ maxlength: 200, placeholder: 'Démission, renvoi, mutation…' }), bl = h('input', { type: 'checkbox' }), ok = h('button', { class: 'btn danger' }, 'Confirmer le départ');
    const md = modal('Départ de ' + m.name, field('Motif', t), h('label', { class: 'check' }, bl, 'Inscrire sur la liste noire (ne pourra plus être recruté)'),
      h('p', { class: 'note' }, 'Le compte est désactivé, le service en cours est clos et l’agent quitte ses unités. La fiche reste dans les archives.'), foot({ close: () => md.close() }, ok));
    ok.addEventListener('click', () => run(ok, async () => { await rpc('agent_leave', { p_member: m.id, p_reason: t.value.trim(), p_blacklist: bl.checked }); md.close(); ctx.reload(); return { message: m.name + ' a quitté le service.' }; }));
  };
  const returnModal = () => {
    const opts = grades.filter(x => x.rank <= rank), sel = select(opts.map(x => [x.id, x.name]), m.grade_id), ok = h('button', { class: 'btn primary' }, 'Réintégrer');
    const md = modal('Réintégrer ' + m.name, field('Grade', sel), foot({ close: () => md.close() }, ok));
    ok.addEventListener('click', () => run(ok, async () => { await rpc('agent_return', { p_member: m.id, p_grade: sel.value }); md.close(); ctx.reload(); return { message: m.name + ' est de retour.' }; }));
  };
  const renew = ev => run(ev.currentTarget, async () => { const tok = await rpc('renew_invitation', { p_member: m.id }); ctx.reload(); ctx.linkModal('Nouveau lien pour ' + m.name, ctx.activationUrl(me.code, tok), m.name); });
  const info = (k, v) => [h('dt', {}, k), h('dd', {}, v)];
  const week = hours(shifts, weekStart());
  ctx.show('effectifs', m.name, [g.badge ? h('span', { class: 'insigne' }, g.badge) : null, ' ', pill(g.name, 'info'), ' ', m.matricule || '', m.callsign ? ' · ' + m.callsign : '', ' ',
    !m.active ? pill('Parti', 'bad') : open ? liveDot((DUTY[m.duty_status] || DUTY.dispo)[0]) : null],
    [self && m.active ? h('button', { class: 'btn', type: 'button', onclick: editModal }, 'Modifier ma fiche') : null,
      manage && m.active ? [h('button', { class: 'btn', type: 'button', onclick: editModal }, 'Modifier'), h('button', { class: 'btn', type: 'button', onclick: gradeModal }, ico('etoile', 18), 'Grade'),
        h('button', { class: 'btn', type: 'button', onclick: noteModal }, ico('journal', 18), 'Note'), h('button', { class: 'btn danger', type: 'button', onclick: leaveModal }, 'Départ')] : null,
      manage && !m.active ? h('button', { class: 'btn primary', type: 'button', onclick: returnModal }, 'Réintégrer') : null,
      can('equipe') && !self && m.active && g.rank <= rank ? h('button', { class: 'btn', type: 'button', onclick: renew }, 'Nouveau lien') : null,
      h('a', { class: 'btn ghost', href: '/' + me.code + '/effectifs' }, 'Tous les agents')],
    !m.active ? h('div', { class: 'banner bad' }, ico('sortie', 20), h('div', {}, h('b', {}, 'Ancien agent' + (m.left_at ? ' · parti le ' + day(m.left_at) : '')), h('span', {}, m.left_reason || ''))) : null,
    h('div', { class: 'grid cols2' },
      h('section', { class: 'card idcard' }, h('div', { class: 'id-top' }, face(m.photo, urls, m.name, 'xl'), h('div', { class: 'art' }, h('b', { class: 'id-name' }, m.name),
        h('span', {}, g.name + (m.rank_since ? ' depuis le ' + day(m.rank_since) : '')), h('span', { class: 'pills' }, myUnits.map(([u, main]) => pill(u.code + (main ? ' · principale' : ''), main ? 'info' : null))))),
        h('dl', { class: 'lines-dl rules' }, info('Matricule', m.matricule || '—'), info('Indicatif', m.callsign || '—'), info('Parcours', STAGE[m.stage][0]), info('Téléphone', m.phone || '—'),
          info('Discord', m.discord_id || '—'), info('FiveM', m.fivem_id || '—'), info('Recruté le', day(m.created_at)), info('Vu', m.last_seen ? ago(ms(m.last_seen)) : 'jamais'),
          has('presence') ? info('Service cette semaine', fmtH(week)) : null),
        selfOk && m.active ? h('div', { class: 'acts' }, h('button', { class: 'btn small', type: 'button', onclick: unitsModal }, ico('grille', 16), 'Unités'),
          manage ? h('div', { class: 'seg', role: 'group', 'aria-label': 'Parcours' }, Object.entries(STAGE).map(([k, [l]]) => h('button', { type: 'button', class: m.stage === k ? 'on' : null, 'aria-pressed': String(m.stage === k),
            onclick: ev => run(ev.currentTarget, r('set_stage', () => ({ p_member: m.id, p_stage: k }), 'Parcours : ' + l.toLowerCase() + '.')) }, l))) : null) : null),
      h('section', { class: 'card' }, h('h2', {}, 'Qualifications'), quals.filter(q => !q.archived || mq.some(x => x.qualification_id === q.id)).length
        ? h('ul', { class: 'quals' }, quals.filter(q => !q.archived || mq.some(x => x.qualification_id === q.id)).map(q => {
          const got = mq.find(x => x.qualification_id === q.id);
          const cb = selfOk && m.active ? h('input', { type: 'checkbox', checked: !!got, 'aria-label': q.name, onchange: async ev => {
            ev.target.disabled = true;
            try { await rpc('set_qualification', { p_member: m.id, p_qualification: q.id, p_on: ev.target.checked }); toast(q.name + (ev.target.checked ? ' obtenue.' : ' retirée.')); ctx.reload(); }
            catch (e) { ev.target.checked = !ev.target.checked; ctx.fail(e); ev.target.disabled = false; }
          } }) : null;
          return h('li', { class: got ? 'got' : null }, cb || h('span', { class: 'q-ic' }, ico(got ? 'coche' : 'moins', 16)), h('span', { class: 'art' }, h('b', {}, q.name), h('small', {}, got ? 'Obtenue le ' + day(got.obtained_at) + ' · ' + got.by_name : 'Pas encore')));
        })) : h('div', { class: 'empty' }, 'Aucune qualification définie (Réglages).'))),
    h('div', { class: 'grid cols2' },
      h('section', { class: 'card' }, h('h2', {}, 'Carrière', h('span', { class: 'muted small' }, career.length + ' entrée(s)')), feed(career.map(e => [e.at, e.kind, e.text, e.by_name, CAREER]))),
      has('presence') ? h('section', { class: 'card' }, h('h2', {}, 'Présence'), strip([['Cette semaine', fmtH(week)], ['30 derniers jours', fmtH(hours(shifts, Date.now() - 30 * DAY))], ['Services', String(shifts.length)]]),
        table([['Début', x => date(ms(x.started_at)), 'num', x => x.started_at], ['Fin', x => x.ended_at ? clock(ms(x.ended_at)) : 'en cours'], ['Durée', x => fmtH(hours([x], 0)), 'num']], shifts.slice(0, 8), null, 'Aucun service.'),
        absences.length ? [h('h3', { class: 'sub-h' }, 'Absences'), table([['Du', a => day(a.starts_on), 'num', a => a.starts_on], ['Au', a => day(a.ends_on)], ['Motif', a => a.reason], ['', a => absPill(a)]], absences)] : null) : null));
}
const absPill = a => a.status === 'validee' ? pill('Validée', 'ok') : a.status === 'refusee' ? pill('Refusée', 'bad') : pill('À valider', 'warn');

// ================================================================== présence
export async function presence(ctx) {
  const { me, can } = ctx;
  const tab = ['service', 'planning', 'absences', 'classement'].includes(S.filters.pres) ? S.filters.pres : 'service';
  const [members, grades, units, shifts, absences, agenda, staff] = await Promise.all([load.members(ctx), load.grades(ctx), load.units(ctx),
    rows('shifts', q => q.eq('org_id', me.id).gte('started_at', iso(Date.now() - 30 * DAY)).order('started_at', { ascending: false }).limit(3000)),
    rows('absences', q => q.eq('org_id', me.id).order('starts_on', { ascending: false }).limit(300)),
    rows('agenda', q => q.eq('org_id', me.id).gte('ends_at', iso(Date.now() - DAY)).order('starts_at')), rows('agenda_staff', q => q.eq('org_id', me.id))]);
  // les services encore ouverts depuis plus de 30 jours (oubli) comptent aussi
  const open = (await rows('shifts', q => q.eq('org_id', me.id).is('ended_at', null)));
  const active = members.filter(m => m.active), meRow = members.find(m => m.id === me.member_id), mine = open.find(x => x.member_id === me.member_id);
  const urls = await signedUrls(active.map(m => m.photo));
  const mOf = id => members.find(m => m.id === id), setTab = k => { S.filters.pres = k; presence(ctx).catch(ctx.fail); };
  const ws = weekStart(), ranking = active.map(m => [m, hours(shifts.filter(x => x.member_id === m.id), ws)]).sort((a, b) => b[1] - a[1]);
  const inactive = active.filter(m => m.user_id && !absentToday(absences, m.id) && !open.some(x => x.member_id === m.id) && inactiveDays(m, shifts) >= 7);
  const pending = absences.filter(a => a.status === 'attente');
  const absenceModal = () => {
    const from = h('input', { type: 'date', value: today() }), to = h('input', { type: 'date', value: today() }), why = area({ maxlength: 200, placeholder: 'Vacances, indisponibilité…' });
    const ok = h('button', { class: 'btn primary' }, 'Déclarer');
    const md = modal('Déclarer une absence', h('div', { class: 'row' }, field('Du', from, 'grow'), field('Au', to, 'grow')), field('Motif', why),
      h('p', { class: 'note' }, 'Un responsable la valide. Une absence validée ne compte pas dans l’inactivité.'), foot({ close: () => md.close() }, ok));
    ok.addEventListener('click', () => run(ok, async () => { await rpc('declare_absence', { p_org: me.id, p_from: from.value, p_to: to.value, p_reason: why.value.trim() }); md.close(); ctx.reload(); return { message: 'Absence déclarée : en attente de validation.' }; }));
  };
  const agendaModal = a => {
    const title = input({ maxlength: 80, value: a ? a.title : '' }), kind = select(Object.entries(AGENDA), a ? a.kind : 'patrouille');
    const t0 = Date.now() + HOUR - (Date.now() % HOUR);
    const from = h('input', { type: 'datetime-local', value: toLocal(a ? ms(a.starts_at) : t0) }), to = h('input', { type: 'datetime-local', value: toLocal(a ? ms(a.ends_at) : t0 + 2 * HOUR) });
    const place = input({ maxlength: 80, value: a && a.place ? a.place : '' }), unit = select([['', 'Toutes les unités']].concat(units.filter(u => !u.archived).map(u => [u.id, u.code + ' · ' + u.name])), a && a.unit_id ? a.unit_id : '');
    const note = area({ maxlength: 300, value: a && a.note ? a.note : '' }), ok = h('button', { class: 'btn primary' }, a ? 'Enregistrer' : 'Planifier');
    const del = a ? h('button', { class: 'btn danger', type: 'button' }, 'Supprimer') : null;
    const md = modal(a ? a.title : 'Planifier', field('Titre', title), h('div', { class: 'row' }, field('Type', kind, 'grow'), field('Unité', unit, 'grow')),
      h('div', { class: 'row' }, field('Début', from, 'grow'), field('Fin', to, 'grow')), field('Lieu', place), field('Consignes', note), foot({ close: () => md.close() }, del, ok));
    if (del) del.addEventListener('click', () => run(del, async () => { await rpc('delete_agenda', { p_id: a.id }); md.close(); ctx.reload(); return { message: 'Créneau supprimé.' }; }));
    ok.addEventListener('click', () => run(ok, async () => {
      await rpc('save_agenda', { p_org: me.id, p_id: a ? a.id : null, p_title: title.value.trim(), p_kind: kind.value, p_starts: iso(new Date(from.value).getTime()), p_ends: iso(new Date(to.value).getTime()),
        p_place: place.value.trim(), p_unit: unit.value || null, p_note: note.value.trim() });
      md.close(); ctx.reload(); return { message: a ? 'Créneau enregistré.' : 'Créneau planifié.' };
    }));
  };
  const body = {
    service: () => h('section', { class: 'card' }, table([['Agent', s => { const m = mOf(s.member_id); return m ? personCell((m.callsign ? m.callsign + ' · ' : '') + m.name, gradeOf(grades, m.grade_id).name, m.photo, urls) : '?'; }],
      ['Statut', s => pill(...(DUTY[(mOf(s.member_id) || {}).duty_status] || DUTY.dispo))], ['Depuis', s => clock(ms(s.started_at)) + ' · ' + fmtH(hours([s], 0)), 'num', s => s.started_at],
      can('valider') ? ['', s => s.member_id === me.member_id ? null : h('button', { class: 'btn small', type: 'button', onclick: ev => { ev.stopPropagation(); run(ev.currentTarget, async () => { await rpc('end_duty', { p_member: s.member_id }); ctx.reload(); return { message: 'Service terminé.' }; }); } }, 'Finir son service')] : null].filter(Boolean),
      open, null, 'Personne n’est en service.')),
    planning: () => h('section', { class: 'card' }, agenda.length ? h('ul', { class: 'agenda' }, agenda.map(a => {
      const who = staff.filter(x => x.agenda_id === a.id).map(x => mOf(x.member_id)).filter(Boolean), inIt = who.some(m => m.id === me.member_id), past = ms(a.ends_at) < Date.now();
      const unit = units.find(u => u.id === a.unit_id);
      return h('li', { class: past ? 'past' : null }, h('div', { class: 'ag-when' }, h('b', {}, new Date(a.starts_at).toLocaleDateString('fr-FR', { weekday: 'short', day: 'numeric', month: 'short' })), h('span', {}, clock(ms(a.starts_at)) + ' – ' + clock(ms(a.ends_at)))),
        h('div', { class: 'art' }, h('b', {}, a.title), h('small', {}, [AGENDA[a.kind], unit ? unit.code : null, a.place].filter(Boolean).join(' · ')), a.note ? h('span', { class: 'muted small' }, a.note) : null,
          h('span', { class: 'pills' }, who.length ? who.map(m => pill(m.callsign || m.name)) : h('span', { class: 'muted small' }, 'Personne d’inscrit'))),
        h('div', { class: 'ag-acts' }, past ? pill('Terminé') : h('button', { class: 'btn small' + (inIt ? '' : ' primary'), type: 'button', onclick: ev => run(ev.currentTarget, async () => { await rpc('agenda_join', { p_id: a.id, p_on: !inIt }); ctx.reload(); return { message: inIt ? 'Désinscrit.' : 'Inscrit.' }; }) }, inIt ? 'Me retirer' : 'Je participe'),
          can('valider') ? h('button', { class: 'btn small ghost', type: 'button', onclick: () => agendaModal(a) }, 'Modifier') : null));
    })) : h('div', { class: 'empty' }, 'Rien de prévu. ' + (can('valider') ? 'Planifie une patrouille ou une opération.' : ''))),
    absences: () => [can('valider') && pending.length ? h('section', { class: 'card' }, h('h2', {}, 'À valider', pill(String(pending.length), 'warn')), table([['Agent', a => (mOf(a.member_id) || {}).name || '?'],
      ['Du', a => day(a.starts_on), 'num', a => a.starts_on], ['Au', a => day(a.ends_on)], ['Motif', a => a.reason],
      ['', a => a.member_id === me.member_id ? h('span', { class: 'muted small' }, 'Un autre responsable valide') : h('span', { class: 'acts' },
        h('button', { class: 'btn small primary', type: 'button', onclick: ev => run(ev.currentTarget, async () => { await rpc('decide_absence', { p_id: a.id, p_ok: true }); ctx.reload(); return { message: 'Absence validée.' }; }) }, 'Valider'),
        h('button', { class: 'btn small', type: 'button', onclick: ev => run(ev.currentTarget, async () => { await rpc('decide_absence', { p_id: a.id, p_ok: false }); ctx.reload(); return { message: 'Absence refusée.' }; }) }, 'Refuser'))]], pending)) : null,
      h('section', { class: 'card' }, h('h2', {}, 'Absences'), table([['Agent', a => (mOf(a.member_id) || {}).name || '?'], ['Du', a => day(a.starts_on), 'num', a => a.starts_on], ['Au', a => day(a.ends_on)], ['Motif', a => a.reason],
        ['Statut', a => absPill(a)], ['Décidé par', a => a.decided_by || '—']], absences.filter(a => a.status !== 'attente' || !can('valider')), null, 'Aucune absence déclarée.'))],
    classement: () => h('div', { class: 'grid cols2' }, h('section', { class: 'card' }, h('h2', {}, 'Heures de service · cette semaine'),
      h('ol', { class: 'board' }, ranking.slice(0, 15).map(([m, x], i) => h('li', {}, h('span', { class: 'rank' }, String(i + 1)), h('span', { class: 'person' }, face(m.photo, urls, m.name, 'sm'),
        h('span', { class: 'art' }, h('b', {}, m.name), h('small', {}, fmtH(x) + ' · ' + gradeOf(grades, m.grade_id).name))))))),
      h('section', { class: 'card' }, h('h2', {}, 'Inactifs depuis 7 jours ou plus'), inactive.length ? table([['Agent', m => personCell(m.name, m.matricule, m.photo, urls)], ['Inactif depuis', m => inactiveDays(m, shifts) + ' j', 'num', m => inactiveDays(m, shifts)],
        ['Vu', m => m.last_seen ? ago(ms(m.last_seen)) : 'jamais']], inactive, m => ctx.go('/' + me.code + '/effectifs/' + m.id)) : h('div', { class: 'calm' }, h('span', { class: 'ic' }, ico('coche')), 'Tout le monde est actif.'))),
  };
  ctx.show('presence', 'Présence', open.length + ' en service · ' + fmtH(hours(shifts, ws)) + ' de service cette semaine',
    [h('button', { class: 'btn', type: 'button', onclick: absenceModal }, ico('calendrier', 18), 'Déclarer une absence'),
      can('valider') ? h('button', { class: 'btn', type: 'button', onclick: () => agendaModal(null) }, ico('ajout', 18), 'Planifier') : null],
    dutyCard(ctx, mine, meRow),
    tabs([['service', 'En service (' + open.length + ')'], ['planning', 'Planning (' + agenda.filter(a => ms(a.ends_at) >= Date.now()).length + ')'], ['absences', 'Absences' + (can('valider') && pending.length ? ' (' + pending.length + ' à valider)' : '')], ['classement', 'Classement']], tab, setTab),
    body[tab]());
}

// ================================================================== individus et dossiers
function personModal(ctx, p, after) {
  const v = p || {};
  const fn = input({ maxlength: 40, value: v.first_name || '' }), ln = input({ maxlength: 40, value: v.last_name || '' }), alias = input({ maxlength: 60, value: v.alias || '' });
  const birth = h('input', { type: 'date', value: v.birth || '' }), sex = select([['', '—'], ['H', 'Homme'], ['F', 'Femme'], ['X', 'Autre']], v.sex || '');
  const height = h('input', { type: 'number', min: 100, max: 250, value: v.height || '', class: 'short', placeholder: 'cm' });
  const eyes = input({ maxlength: 20, value: v.eyes || '' }), hair = input({ maxlength: 20, value: v.hair || '' }), marks = input({ maxlength: 200, value: v.marks || '', placeholder: 'Tatouages, cicatrices…' });
  const phone = input({ maxlength: 20, value: v.phone || '' }), address = input({ maxlength: 120, value: v.address || '' }), job = input({ maxlength: 60, value: v.job || '' }), gang = input({ maxlength: 60, value: v.gang || '' });
  const kind = select(Object.entries(KIND).map(([k, [l]]) => [k, l]), v.kind || 'suspect'), armed = h('input', { type: 'checkbox', checked: !!v.armed });
  const danger = select([['faible', 'Faible'], ['moyenne', 'Moyenne'], ['elevee', 'Élevée'], ['extreme', 'Extrême']], v.danger || (v.dangerous ? 'elevee' : 'faible'));
  const notes = area({ maxlength: 2000, value: v.notes || '' }), photo = photoIn();
  const lines = (list, cols) => { const box = h('div', { class: 'linelist' }); const add = (x) => { const ins = cols.map(([k, ph, w]) => input({ maxlength: w, value: (x || {})[k] || '', placeholder: ph, 'aria-label': ph }));
    const row = h('div', { class: 'll-row' }, ins, h('button', { class: 'btn small ghost', type: 'button', 'aria-label': 'Retirer', onclick: () => row.remove() }, ico('moins', 16))); row._get = () => Object.fromEntries(cols.map(([k], i) => [k, ins[i].value.trim()])); box.append(row); };
    (list || []).forEach(add); return [box, () => add(null), () => [...box.children].map(r => r._get()).filter(o => Object.values(o).some(Boolean))]; };
  const [veh, addVeh, getVeh] = lines(v.vehicles, [['plate', 'Plaque', 10], ['model', 'Modèle', 40], ['color', 'Couleur', 20]]);
  const [plc, addPlc, getPlc] = lines(v.places, [['label', 'Lieu (planque, travail…)', 40], ['address', 'Adresse', 120]]);
  const ok = h('button', { class: 'btn primary' }, p ? 'Enregistrer' : 'Créer la fiche');
  const md = modal(p ? fullName(p) : 'Nouvel individu', h('div', { class: 'row' }, field('Prénom', fn, 'grow'), field('Nom', ln, 'grow')), h('div', { class: 'row' }, field('Surnom', alias, 'grow'), field('Né(e) le', birth)),
    h('div', { class: 'row' }, field('Sexe', sex), field('Taille', height), field('Yeux', eyes, 'grow'), field('Cheveux', hair, 'grow')), field('Signes particuliers', marks),
    h('div', { class: 'row' }, field('Téléphone', phone, 'grow'), field('Métier', job, 'grow')), h('div', { class: 'row' }, field('Adresse', address, 'grow'), field('Gang / organisation', gang, 'grow')),
    h('div', { class: 'row' }, field('Type de fiche', kind, 'grow'), field('Dangerosité', danger, 'grow'), h('label', { class: 'check' }, armed, 'Armé')),
    h('div', { class: 'f' }, 'Véhicules connus', veh, h('button', { class: 'btn small', type: 'button', onclick: addVeh }, ico('ajout', 16), 'Véhicule')),
    h('div', { class: 'f' }, 'Lieux fréquentés', plc, h('button', { class: 'btn small', type: 'button', onclick: addPlc }, ico('ajout', 16), 'Lieu')),
    field('Photo', photo), field('Notes', notes), foot({ close: () => md.close() }, ok));
  md.box.classList.add('wide');
  ok.addEventListener('click', () => run(ok, async () => {
    if (!fn.value.trim() || !ln.value.trim()) throw new Error('Indique le prénom et le nom.');
    const path = await send(ctx.me.id, 'people', photo.files[0], true);
    const id = await rpc('save_person', { p_org: ctx.me.id, p_id: p ? p.id : null, p: { first_name: fn.value.trim(), last_name: ln.value.trim(), alias: alias.value, birth: birth.value, sex: sex.value,
      height: height.value, eyes: eyes.value, hair: hair.value, marks: marks.value, phone: phone.value, address: address.value, job: job.value, gang: gang.value,
      kind: kind.value, danger: danger.value, armed: armed.checked, notes: notes.value, photo: path, vehicles: getVeh(), places: getPlc() } });
    md.close(); if (after) after(id); else ctx.reload(); return { message: p ? 'Fiche enregistrée.' : 'Fiche créée.' };
  }));
}
function caseModal(ctx, people, members, preset) {
  const per = select(people.map(p => [p.id, fullName(p) + (p.alias ? ' « ' + p.alias + ' »' : '')]), preset || (people[0] && people[0].id));
  const title = input({ maxlength: 80, placeholder: 'Ex. Évasion de Bolingbroke' }), prio = select(Object.entries(PRIO).map(([k, [l]]) => [k, l]), 'normale');
  const charges = area({ maxlength: 500, placeholder: 'Chefs d’accusation' }), lead = select(members.filter(m => m.active).map(m => [m.id, m.name]), ctx.me.member_id);
  const ok = h('button', { class: 'btn primary' }, 'Ouvrir le dossier');
  const md = modal('Ouvrir un dossier', field('Individu recherché', per), field('Titre', title), h('div', { class: 'row' }, field('Priorité', prio, 'grow'), field('Responsable', lead, 'grow')), field('Charges', charges), foot({ close: () => md.close() }, ok));
  ok.addEventListener('click', () => run(ok, async () => {
    const r = await rpc('open_case', { p_org: ctx.me.id, p_person: per.value, p_title: title.value.trim(), p_priority: prio.value, p_charges: charges.value.trim(), p_lead: lead.value });
    md.close(); ctx.go('/' + ctx.me.code + '/dossiers/' + r.id); return { message: 'Dossier ' + r.num + ' ouvert.' };
  }));
}

export async function dossiers(ctx) {
  const { me, can } = ctx;
  const tab = S.filters.dos === 'individus' ? 'individus' : 'dossiers', f = S.filters.dosF || 'actifs';
  const [cases, people, members, wanted] = await Promise.all([rows('cases', q => q.eq('org_id', me.id).order('opened_at', { ascending: false })), load.people(ctx), load.members(ctx),
    me.modules.includes('wanted') ? rows('wanted', q => q.eq('org_id', me.id)) : []]);
  const urls = await signedUrls(people.map(p => p.photo));
  const pOf = id => people.find(p => p.id === id), mOf = id => members.find(m => m.id === id), setTab = k => { S.filters.dos = k; dossiers(ctx).catch(ctx.fail); };
  const list = h('div', {});
  const plates = p => (p.vehicles || []).map(x => x.plate).join(' ');
  const paint = () => {
    const q = S.filters.dosQ;
    list.replaceChildren(tab === 'dossiers'
      ? table([['N°', c => h('b', { class: 'nowrap' }, c.num), null, c => c.num], ['Individu', c => { const p = pOf(c.person_id); return personCell(fullName(p), p && p.alias ? '« ' + p.alias + ' »' : null, p && p.photo, urls); }],
        ['Titre', c => c.title], ['Statut', c => pill(...STATUS[c.status])], ['Priorité', c => pill(...PRIO[c.priority]), null, c => ['most_wanted', 'haute', 'normale'].indexOf(c.priority)],
        ['Responsable', c => (mOf(c.lead_id) || {}).name || '—'], ['Ouvert', c => day(c.opened_at), 'num', c => c.opened_at]],
      cases.filter(c => (f === 'tous' || (f === 'actifs') === OPEN.includes(c.status)) && match(q, c.num, c.title, c.charges, fullName(pOf(c.person_id)), (pOf(c.person_id) || {}).alias)),
      c => ctx.go('/' + me.code + '/dossiers/' + c.id), 'Aucun dossier.')
      : table([['Individu', p => personCell(fullName(p), p.alias ? '« ' + p.alias + ' »' : null, p.photo, urls), null, p => p.last_name],
        ['Signalement', p => h('span', { class: 'pills' }, pill(...KIND[p.kind || 'suspect']), p.danger && p.danger !== 'faible' ? pill(...DANGER[p.danger]) : null, p.armed ? pill('Armé', 'bad') : null, wanted.some(w => w.person_id === p.id) ? pill('Most Wanted', 'bad') : null)],
        ['Gang', p => p.gang || '—'], ['Dossiers', p => String(cases.filter(c => c.person_id === p.id).length), 'num', p => cases.filter(c => c.person_id === p.id).length],
        ['Mis à jour', p => day(p.updated_at), 'num', p => p.updated_at]],
      people.filter(p => match(q, fullName(p), p.alias, p.gang, p.phone, plates(p))), p => ctx.go('/' + me.code + '/individus/' + p.id), 'Aucun individu.'));
  };
  paint();
  const opened = cases.filter(c => OPEN.includes(c.status));
  ctx.show('dossiers', 'Dossiers', opened.length + ' dossier(s) actif(s) · ' + people.length + ' individu(s)',
    can('dossiers') ? [h('button', { class: 'btn', type: 'button', onclick: () => personModal(ctx, null, id => ctx.go('/' + me.code + '/individus/' + id)) }, ico('ajout', 18), 'Nouvel individu'),
      h('button', { class: 'btn primary', type: 'button', disabled: !people.length, title: people.length ? null : 'Crée d’abord la fiche de l’individu', onclick: () => caseModal(ctx, people, members) }, ico('dossier', 18), 'Ouvrir un dossier')] : null,
    h('section', { class: 'card' }, strip([['Actifs', String(opened.length)], ['Priorité haute', String(opened.filter(c => c.priority === 'haute').length)], ['Most Wanted', String(opened.filter(c => c.priority === 'most_wanted').length)],
      ['Clos ce mois', String(cases.filter(c => c.closed_at && ms(c.closed_at) > Date.now() - 30 * DAY).length)]])),
    tabs([['dossiers', 'Dossiers'], ['individus', 'Individus']], tab, setTab),
    h('section', { class: 'card' }, h('div', { class: 'cardhead' }, search(S.filters.dosQ, tab === 'dossiers' ? 'Chercher : numéro, nom, surnom, charges' : 'Chercher : nom, surnom, gang, téléphone, plaque', v => { S.filters.dosQ = v; paint(); }),
      tab === 'dossiers' ? fchips([['actifs', 'Actifs'], ['clos', 'Clos'], ['tous', 'Tous']], f, k => { S.filters.dosF = k; dossiers(ctx).catch(ctx.fail); }, 'Dossiers') : null), list));
}

export async function individu(ctx, id) {
  const { me, can } = ctx;
  const [all, cases, warrants, wanted] = await Promise.all([load.people(ctx), rows('cases', q => q.eq('person_id', id).order('opened_at', { ascending: false })),
    me.modules.includes('mandats') ? rows('warrants', q => q.eq('person_id', id).order('created_at', { ascending: false })) : [], me.modules.includes('wanted') ? rows('wanted', q => q.eq('person_id', id)) : []]);
  const p = all.find(x => x.id === id);
  if (!p) return ctx.go('/' + me.code + '/dossiers', true);
  const urls = await signedUrls([p.photo]);
  const info = (k, v) => v ? [h('dt', {}, k), h('dd', {}, v)] : null;
  const w = wanted[0], more = await personCards(ctx, p, all);
  ctx.show('dossiers', fullName(p), h('span', { class: 'pills' }, p.alias ? h('span', {}, '« ' + p.alias + ' »') : null, pill(...KIND[p.kind || 'suspect']), pill(...DANGER[p.danger || 'faible']),
    p.armed ? pill('Armé', 'bad') : null, w ? pill('Most Wanted n° ' + w.rank, 'bad') : null),
    [can('dossiers') ? [h('button', { class: 'btn', type: 'button', onclick: () => personModal(ctx, p) }, 'Modifier'),
      h('button', { class: 'btn primary', type: 'button', onclick: async () => caseModal(ctx, [p], await load.members(ctx), p.id) }, ico('dossier', 18), 'Ouvrir un dossier')] : null,
      h('a', { class: 'btn ghost', href: '/' + me.code + '/dossiers' }, 'Tous les dossiers')],
    h('div', { class: 'grid cols2' },
      h('section', { class: 'card idcard' }, h('div', { class: 'id-top' }, face(p.photo, urls, fullName(p), 'xl'), h('div', { class: 'art' }, h('b', { class: 'id-name' }, fullName(p)), p.alias ? h('span', {}, '« ' + p.alias + ' »') : null,
        h('span', { class: 'muted small' }, 'Fiche mise à jour ' + ago(ms(p.updated_at))))),
        h('dl', { class: 'lines-dl rules' }, info('Né(e) le', p.birth ? day(p.birth) : null), info('Sexe', { H: 'Homme', F: 'Femme', X: 'Autre' }[p.sex]), info('Taille', p.height ? p.height + ' cm' : null), info('Yeux', p.eyes), info('Cheveux', p.hair),
          info('Signes particuliers', p.marks), info('Téléphone', p.phone), info('Adresse', p.address), info('Métier', p.job), info('Gang / organisation', p.gang_id ? h('a', { href: '/' + me.code + '/organisations/' + p.gang_id }, p.gang) : p.gang))),
      h('div', { class: 'stack' },
        h('section', { class: 'card' }, h('h2', {}, 'Lieux fréquentés'), (p.places || []).length ? h('ul', { class: 'people' }, p.places.map(l => h('li', {}, h('span', { class: 'ev-ic' }, ico('localisation', 16)), h('span', { class: 'art' }, h('b', {}, l.label || 'Lieu'), l.address ? h('small', {}, l.address) : null))))
          : h('div', { class: 'empty' }, 'Aucun lieu connu.')),
        p.notes ? h('section', { class: 'card' }, h('h2', {}, 'Notes'), h('p', { class: 'prewrap' }, p.notes)) : null)),
    ...more,
    h('section', { class: 'card' }, h('h2', {}, 'Dossiers'), table([['N°', c => h('b', { class: 'nowrap' }, c.num)], ['Titre', c => c.title], ['Statut', c => pill(...STATUS[c.status])], ['Priorité', c => pill(...PRIO[c.priority])], ['Ouvert', c => day(c.opened_at), 'num', c => c.opened_at]],
      cases, c => ctx.go('/' + me.code + '/dossiers/' + c.id), 'Aucun dossier.')),
    warrants.length ? h('section', { class: 'card' }, h('h2', {}, 'Mandats'), warrantTable(ctx, warrants, null)) : null);
}

export async function dossier(ctx, id) {
  const { me, can, has } = ctx;
  const [cs, log, files, agents, members] = await Promise.all([rows('cases', q => q.eq('id', id)), rows('case_log', q => q.eq('case_id', id).order('at', { ascending: false }).limit(300)),
    rows('case_files', q => q.eq('case_id', id).order('at', { ascending: false })), rows('case_agents', q => q.eq('case_id', id)), load.members(ctx)]);
  const c = cs[0];
  if (!c) return ctx.go('/' + me.code + '/dossiers', true);
  const [pp, warrants, wanted] = await Promise.all([rows('people', q => q.eq('id', c.person_id)), has('mandats') ? rows('warrants', q => q.eq('case_id', id).order('created_at', { ascending: false })) : [],
    has('wanted') ? rows('wanted', q => q.eq('person_id', c.person_id)) : []]);
  const p = pp[0];
  const urls = await signedUrls([p && p.photo].concat(files.map(f => f.path)));
  const mOf = x => members.find(m => m.id === x), team = agents.map(a => mOf(a.member_id)).filter(Boolean);
  const extra = await caseCards(ctx, c);
  const edit = () => {
    const title = input({ maxlength: 80, value: c.title }), st = select(Object.entries(STATUS).map(([k, [l]]) => [k, l]), c.status), pr = select(Object.entries(PRIO).map(([k, [l]]) => [k, l]), c.priority);
    const lead = select(members.filter(m => m.active).map(m => [m.id, m.name]), c.lead_id), charges = area({ maxlength: 500, value: c.charges || '' }), seen = input({ maxlength: 160, value: c.last_seen || '', placeholder: 'Lieu, heure, véhicule…' });
    const ok = h('button', { class: 'btn primary' }, 'Enregistrer');
    const md = modal(c.num, field('Titre', title), h('div', { class: 'row' }, field('Statut', st, 'grow'), field('Priorité', pr, 'grow')), field('Responsable', lead), field('Dernière localisation', seen), field('Charges', charges), foot({ close: () => md.close() }, ok));
    ok.addEventListener('click', () => run(ok, async () => {
      await rpc('update_case', { p_case: c.id, p_title: title.value.trim(), p_status: st.value, p_priority: pr.value, p_lead: lead.value, p_charges: charges.value.trim(), p_last_seen: seen.value.trim() });
      md.close(); ctx.reload(); return { message: 'Dossier mis à jour.' };
    }));
  };
  const teamModal = () => {
    const boxes = members.filter(m => m.active).map(m => [m, h('input', { type: 'checkbox', checked: team.some(t => t.id === m.id) })]);
    const ok = h('button', { class: 'btn primary' }, 'Enregistrer');
    const md = modal('Agents sur ' + c.num, h('div', { class: 'checks' }, boxes.map(([m, cb]) => h('label', { class: 'check' }, cb, (m.callsign ? m.callsign + ' · ' : '') + m.name))), foot({ close: () => md.close() }, ok));
    ok.addEventListener('click', () => run(ok, async () => { await rpc('set_case_agents', { p_case: c.id, p_members: boxes.filter(([, cb]) => cb.checked).map(([m]) => m.id) }); md.close(); ctx.reload(); return { message: 'Équipe enregistrée.' }; }));
  };
  const fileModal = () => {
    const fi = h('input', { type: 'file', accept: 'image/png,image/jpeg,image/webp,image/gif,video/mp4,video/webm,application/pdf,text/plain' }), ok = h('button', { class: 'btn primary' }, 'Joindre');
    const md = modal('Pièce jointe', field('Photo, vidéo, PDF ou texte (50 Mo au plus)', fi), h('p', { class: 'note' }, 'Une pièce jointe ne se supprime pas : elle reste au dossier.'), foot({ close: () => md.close() }, ok));
    ok.addEventListener('click', () => run(ok, async () => {
      const f = fi.files[0];
      if (!f) throw new Error('Choisis un fichier.');
      const path = await send(me.id, 'cases/' + c.id, f, false);
      await rpc('attach_case_file', { p_case: c.id, p_path: path, p_name: f.name, p_mime: f.type, p_size: f.size });
      md.close(); ctx.reload(); return { message: 'Pièce jointe ajoutée.' };
    }));
  };
  let kind = 'note';
  const kseg = h('div', { class: 'seg', role: 'group', 'aria-label': 'Type d’entrée' });
  const paintSeg = () => kseg.replaceChildren(...[['note', 'Note'], ['piste', 'Piste'], ['observation', 'Observation']].map(([k, l]) => h('button', { type: 'button', class: k === kind ? 'on' : null, 'aria-pressed': String(k === kind), onclick: () => { kind = k; paintSeg(); } }, l)));
  paintSeg();
  const text = area({ maxlength: 1000, placeholder: 'Ce qui a été vu, entendu ou fait', 'aria-label': 'Nouvelle entrée du journal' }), add = h('button', { class: 'btn primary' }, 'Ajouter au journal');
  add.addEventListener('click', () => run(add, async () => { await rpc('case_note', { p_case: c.id, p_kind: kind, p_text: text.value.trim() }); ctx.reload(); return { message: 'Ajouté au journal.' }; }));
  const fileView = f => {
    const u = urls[f.path];
    const body = !u ? h('span', { class: 'muted small' }, 'Indisponible') : /^image\//.test(f.mime) ? h('a', { href: u, target: '_blank', rel: 'noopener' }, h('img', { src: u, alt: f.name }))
      : /^video\//.test(f.mime) ? h('video', { src: u, controls: true, preload: 'metadata' }) : h('a', { class: 'fileicon', href: u, target: '_blank', rel: 'noopener' }, ico('facture', 28), f.mime === 'application/pdf' ? 'PDF' : 'Texte');
    return h('figure', { class: 'filecard' }, body, h('figcaption', {}, h('b', {}, f.name), h('small', {}, Math.max(1, Math.round(f.size / 1024)).toLocaleString('fr-FR') + ' Ko · ' + f.by_name + ' · ' + date(ms(f.at)))));
  };
  ctx.show('dossiers', c.num + ' · ' + c.title, [pill(...STATUS[c.status]), ' ', pill(...PRIO[c.priority]), ' Ouvert le ' + day(c.opened_at) + (c.closed_at ? ' · clos le ' + day(c.closed_at) : '')],
    [can('dossiers') ? [h('button', { class: 'btn', type: 'button', onclick: edit }, 'Modifier'), h('button', { class: 'btn', type: 'button', onclick: teamModal }, ico('joueurs', 18), 'Agents'),
      h('button', { class: 'btn', type: 'button', onclick: fileModal }, ico('trombone', 18), 'Pièce jointe')] : null,
      has('mandats') && can('mandats') ? h('button', { class: 'btn', type: 'button', onclick: () => warrantModal(ctx, null, { person: c.person_id, case: c.id, people: p ? [p] : [], cases: [c] }) }, ico('marteau', 18), 'Demander un mandat') : null,
      has('wanted') && can('wanted') && !wanted.length ? h('button', { class: 'btn', type: 'button', onclick: () => wantedModal(ctx, p ? [p] : [], [c], null, c) }, ico('cible', 18), 'Most Wanted') : null, extra.buttons],
    h('div', { class: 'grid cols2' },
      h('section', { class: 'card idcard' }, p ? [h('div', { class: 'id-top' }, face(p.photo, urls, fullName(p), 'xl'), h('div', { class: 'art' }, h('a', { class: 'id-name', href: '/' + me.code + '/individus/' + p.id }, fullName(p)),
        p.alias ? h('span', {}, '« ' + p.alias + ' »') : null, h('span', { class: 'pills' }, pill(...DANGER[p.danger || 'faible']), p.armed ? pill('Armé', 'bad') : null, wanted[0] ? pill('Most Wanted n° ' + wanted[0].rank, 'bad') : null))),
        h('dl', { class: 'lines-dl rules' }, [h('dt', {}, 'Signalement'), h('dd', {}, [p.sex === 'H' ? 'Homme' : p.sex === 'F' ? 'Femme' : null, p.height ? p.height + ' cm' : null, p.eyes ? 'yeux ' + p.eyes : null, p.hair ? 'cheveux ' + p.hair : null].filter(Boolean).join(', ') || '—')],
          p.marks ? [h('dt', {}, 'Signes'), h('dd', {}, p.marks)] : null, p.gang ? [h('dt', {}, 'Gang'), h('dd', {}, p.gang)] : null)] : h('div', { class: 'empty' }, 'Individu introuvable.')),
      h('section', { class: 'card' }, h('h2', {}, 'Enquête'), h('dl', { class: 'lines-dl rules' }, h('dt', {}, 'Responsable'), h('dd', {}, (mOf(c.lead_id) || {}).name || '—'),
        h('dt', {}, 'Agents'), h('dd', {}, team.length ? team.map(m => m.callsign || m.name).join(', ') : '—'),
        h('dt', {}, 'Dernière localisation'), h('dd', {}, c.last_seen ? c.last_seen + ' · ' + ago(ms(c.last_seen_at)) : '—'), h('dt', {}, 'Ouvert par'), h('dd', {}, c.by_name)),
        h('h3', { class: 'sub-h' }, 'Charges'), h('p', { class: 'prewrap' }, c.charges || 'Non renseignées.'))),
    h('div', { class: 'grid cols2' },
      h('section', { class: 'card' }, h('h2', {}, 'Journal', h('span', { class: 'muted small' }, log.length + ' entrée(s)')),
        can('dossiers') ? h('div', { class: 'logform' }, kseg, text, add) : null, feed(log.map(l => [l.at, l.kind, l.text, l.by_name, LOG]))),
      h('div', { class: 'stack' },
        h('section', { class: 'card' }, h('h2', {}, 'Pièces jointes', h('span', { class: 'muted small' }, String(files.length))), files.length ? h('div', { class: 'files' }, files.map(fileView)) : h('div', { class: 'empty' }, 'Aucune pièce jointe.')),
        has('mandats') ? h('section', { class: 'card' }, h('h2', {}, 'Mandats'), warrantTable(ctx, warrants, null)) : null)),
    ...extra.cards);
}

// ================================================================== mandats
function warrantModal(ctx, w, o) {
  const kind = select(Object.entries(WKIND), w ? w.kind : 'arret');
  const per = select([['', 'Aucun (lieu ou personne non fichée)']].concat((o.people || []).map(p => [p.id, fullName(p)])), w ? w.person_id || '' : o.person || '');
  const target = input({ maxlength: 120, value: w && !w.person_id ? w.target : '', placeholder: 'Personne ou adresse visée' });
  const cas = select([['', 'Aucun']].concat((o.cases || []).map(c => [c.id, c.num + ' · ' + c.title])), w ? w.case_id || '' : o.case || '');
  const place = input({ maxlength: 120, value: w && w.place ? w.place : '', placeholder: 'Ex. 12 Grove Street' });
  const reason = area({ maxlength: 1500, value: w ? w.reason : '', placeholder: 'Faits, éléments, urgence : la Justice lit ce texte avant de signer.' });
  const days = h('input', { type: 'number', min: 1, max: 30, value: w ? w.valid_days : 7, class: 'short' });
  const paint = () => { target.closest('.f').hidden = !!per.value; };
  per.addEventListener('change', paint);
  const save = h('button', { class: 'btn' }, 'Garder en brouillon'), sendB = h('button', { class: 'btn primary' }, 'Envoyer à la Justice');
  const md = modal(w ? w.num : 'Demande de mandat', h('div', { class: 'row' }, field('Type', kind, 'grow'), field('Validité (jours)', days)), field('Individu', per), field('Visé', target),
    h('div', { class: 'row' }, field('Dossier', cas, 'grow'), field('Lieu', place, 'grow')), field('Motif', reason), foot({ close: () => md.close() }, save, sendB));
  paint();
  const go = async sendIt => {
    const id = await rpc('save_warrant', { p_org: ctx.me.id, p_id: w ? w.id : null, p_kind: kind.value, p_person: per.value || null, p_case: cas.value || null, p_target: target.value.trim(), p_place: place.value.trim(), p_reason: reason.value.trim(), p_days: Number(days.value) || 7 });
    if (sendIt) await rpc('send_warrant', { p_id: id });
    md.close(); ctx.reload(); return { message: sendIt ? 'Demande envoyée à la Justice.' : 'Brouillon enregistré.' };
  };
  save.addEventListener('click', () => run(save, () => go(false)));
  sendB.addEventListener('click', () => run(sendB, () => go(true)));
}
const expiry = w => w.status === 'signe' && w.expires_at ? (ms(w.expires_at) < Date.now() ? 'expiré le ' + date(ms(w.expires_at)) : 'jusqu’au ' + date(ms(w.expires_at))) : '';
function warrantView(ctx, w, extra) {
  const st = wStatus(w);
  const md = modal(w.num + ' · ' + WKIND[w.kind], h('div', { class: 'pills' }, pill(...WST[st]), extra || null),
    h('dl', { class: 'lines-dl rules' }, h('dt', {}, 'Visé'), h('dd', {}, w.target), w.place ? [h('dt', {}, 'Lieu'), h('dd', {}, w.place)] : null, h('dt', {}, 'Demandé par'), h('dd', {}, w.requested_by + (w.sent_at ? ' · envoyé ' + date(ms(w.sent_at)) : '')),
      w.decided_by ? [h('dt', {}, 'Décision'), h('dd', {}, w.decided_by + ' · ' + date(ms(w.decided_at)))] : null, w.expires_at ? [h('dt', {}, 'Validité'), h('dd', {}, expiry(w))] : null,
      w.executed_by ? [h('dt', {}, 'Exécuté'), h('dd', {}, w.executed_by + ' · ' + date(ms(w.executed_at)))] : null),
    h('h3', { class: 'sub-h' }, 'Motif'), h('p', { class: 'prewrap' }, w.reason), w.judge_note ? [h('h3', { class: 'sub-h' }, 'Note de la Justice'), h('p', { class: 'prewrap' }, w.judge_note)] : null,
    w.exec_note ? [h('h3', { class: 'sub-h' }, 'Exécution'), h('p', { class: 'prewrap' }, w.exec_note)] : null,
    h('div', { class: 'foot' }, h('button', { class: 'btn', type: 'button', onclick: () => md.close() }, 'Fermer')));
  return md;
}
function warrantActions(ctx, w) {
  const st = wStatus(w), can = ctx.can;
  const b = (label, cls, fn) => h('button', { class: 'btn small' + (cls ? ' ' + cls : ''), type: 'button', onclick: ev => { ev.stopPropagation(); fn(ev.currentTarget); } }, label);
  return h('span', { class: 'acts' },
    st === 'brouillon' && can('mandats') ? b('Envoyer', 'primary', btn => run(btn, async () => { await rpc('send_warrant', { p_id: w.id }); ctx.reload(); return { message: 'Envoyé à la Justice.' }; })) : null,
    st === 'signe' ? b('Exécuter', 'primary', () => {
      const note = area({ maxlength: 300, placeholder: 'Interpellation, saisie, déroulé…' }), ok = h('button', { class: 'btn primary' }, 'Mandat exécuté');
      const md = modal('Exécuter ' + w.num, field('Compte rendu (facultatif)', note), foot({ close: () => md.close() }, ok));
      ok.addEventListener('click', () => run(ok, async () => { await rpc('execute_warrant', { p_id: w.id, p_note: note.value.trim() || null }); md.close(); ctx.reload(); return { message: w.num + ' exécuté.' }; }));
    }) : null,
    ['brouillon', 'envoye', 'signe'].includes(w.status) && st !== 'expire' && can('mandats') ? b('Annuler', null, btn => run(btn, async () => { await rpc('cancel_warrant', { p_id: w.id }); ctx.reload(); return { message: w.num + ' annulé.' }; })) : null);
}
function warrantTable(ctx, list, cases) {
  return table([['N°', w => h('b', { class: 'nowrap' }, w.num), null, w => w.num], ['Type', w => WKIND[w.kind]], ['Visé', w => h('span', { class: 'art' }, h('b', {}, w.target), w.place ? h('small', {}, w.place) : null)],
    cases ? ['Dossier', w => (cases.find(c => c.id === w.case_id) || {}).num || '—'] : null,
    ['Statut', w => h('span', { class: 'art' }, pill(...WST[wStatus(w)]), expiry(w) ? h('small', {}, expiry(w)) : null), null, w => wStatus(w)], ['Demandé', w => w.requested_by + ' · ' + day(w.created_at), 'num', w => w.created_at],
    ['', w => warrantActions(ctx, w)]].filter(Boolean), list, w => warrantView(ctx, w), 'Aucun mandat.');
}
export async function mandats(ctx) {
  const { me, can } = ctx;
  const f = S.filters.manF || 'actifs';
  const [list, people, cases] = await Promise.all([rows('warrants', q => q.eq('org_id', me.id).order('created_at', { ascending: false }).limit(500)), load.people(ctx),
    rows('cases', q => q.eq('org_id', me.id).order('opened_at', { ascending: false }))]);
  const box = h('div', {});
  const groups = { actifs: w => ['brouillon', 'envoye', 'signe'].includes(wStatus(w)), attente: w => w.status === 'envoye', signes: w => wStatus(w) === 'signe', clos: w => ['execute', 'refuse', 'annule', 'expire'].includes(wStatus(w)), tous: () => true };
  const paint = () => box.replaceChildren(warrantTable(ctx, list.filter(w => groups[f](w) && match(S.filters.manQ, w.num, w.target, w.place, w.requested_by, WKIND[w.kind])), cases));
  paint();
  ctx.show('mandats', 'Mandats', 'Demandés au service, signés par la Justice',
    can('mandats') ? h('button', { class: 'btn primary', type: 'button', onclick: () => warrantModal(ctx, null, { people, cases: cases.filter(c => OPEN.includes(c.status)) }) }, ico('ajout', 18), 'Demander un mandat') : null,
    h('section', { class: 'card' }, strip([['Brouillons', String(list.filter(w => w.status === 'brouillon').length)], ['En attente de la Justice', String(list.filter(w => w.status === 'envoye').length)],
      ['Signés, à exécuter', String(list.filter(w => wStatus(w) === 'signe').length)], ['Expirés', String(list.filter(w => wStatus(w) === 'expire').length)]])),
    h('section', { class: 'card' }, h('div', { class: 'cardhead' }, search(S.filters.manQ, 'Chercher : numéro, personne, lieu, agent', v => { S.filters.manQ = v; paint(); }),
      fchips([['actifs', 'En cours'], ['attente', 'En attente'], ['signes', 'Signés'], ['clos', 'Clos'], ['tous', 'Tous']], f, k => { S.filters.manF = k; mandats(ctx).catch(ctx.fail); }, 'Mandats')), box),
    can('mandats') ? null : h('p', { class: 'note' }, 'Ton grade permet d’exécuter un mandat signé, pas d’en demander.'));
}

// ================================================================== Most Wanted
function wantedModal(ctx, people, cases, w, presetCase) {
  const per = select(people.map(p => [p.id, fullName(p) + (p.alias ? ' « ' + p.alias + ' »' : '')]), w ? w.person_id : presetCase ? presetCase.person_id : people[0] && people[0].id, { disabled: !!w });
  const rank = h('input', { type: 'number', min: 1, max: 10, value: w ? w.rank : 1, class: 'short' }), reward = h('input', { type: 'number', min: 0, step: 100, value: w ? Number(w.reward) : 0, class: 'short' });
  const reason = input({ maxlength: 200, value: w ? w.reason : '', placeholder: 'Ex. Meurtre, évasion, trafic' });
  const cas = select([['', 'Aucun']].concat(cases.map(c => [c.id, c.num + ' · ' + c.title])), w ? w.case_id || '' : presetCase ? presetCase.id : '');
  const ok = h('button', { class: 'btn primary' }, w ? 'Enregistrer' : 'Ajouter au classement');
  const md = modal(w ? 'Most Wanted · ' + fullName(people.find(p => p.id === w.person_id)) : 'Ajouter au Most Wanted', field('Individu', per), h('div', { class: 'row' }, field('Rang', rank), field('Récompense ($)', reward)), field('Motif', reason), field('Dossier', cas),
    h('p', { class: 'note' }, 'Les suivants descendent d’un rang ; le dernier sort du classement s’il est plein.'), foot({ close: () => md.close() }, ok));
  ok.addEventListener('click', () => run(ok, async () => {
    await rpc('set_wanted', { p_org: ctx.me.id, p_person: per.value, p_rank: Number(rank.value) || 1, p_reward: Number(reward.value) || 0, p_reason: reason.value.trim(), p_case: cas.value || null });
    md.close(); ctx.reload(); return { message: 'Classement mis à jour.' };
  }));
}
// l'affiche : un PNG dessiné ici, à télécharger ou à publier sur la page publique
async function poster(org, w, p, photoUrl) {
  try { await Promise.all([document.fonts.load('700 40px Inter'), document.fonts.load('600 40px "Cormorant Garamond"')]); } catch (e) { /* police de secours */ }
  const W = 1200, H = 1600, c = document.createElement('canvas'); c.width = W; c.height = H;
  const x = c.getContext('2d'), serif = '"Cormorant Garamond", Georgia, serif', sans = 'Inter, "Helvetica Neue", Arial, sans-serif';
  const ink = '#2b2118', paper = '#f4ead8', red = '#9b1c16';
  x.fillStyle = paper; x.fillRect(0, 0, W, H);
  x.strokeStyle = ink; x.lineWidth = 6; x.strokeRect(36, 36, W - 72, H - 72); x.lineWidth = 2; x.strokeRect(52, 52, W - 104, H - 104);
  const t = (s, px, py, font, color, align) => { x.font = font; x.fillStyle = color || ink; x.textAlign = align || 'center'; x.fillText(s, px, py); };
  t((org.kind || 'US Marshals Service').toUpperCase(), W / 2, 130, '600 30px ' + sans, ink);
  t('WANTED', W / 2, 290, '600 190px ' + serif, ink);
  t('AVIS DE RECHERCHE · N° ' + w.rank, W / 2, 350, '700 30px ' + sans, red);
  const pw = 560, ph = 640, px = (W - pw) / 2, py = 400;
  x.fillStyle = '#d9cdb6'; x.fillRect(px, py, pw, ph);
  if (photoUrl) {
    const img = await new Promise(ok => { const i = new Image(); i.crossOrigin = 'anonymous'; i.onload = () => ok(i); i.onerror = () => ok(null); i.src = photoUrl; });
    if (img) { const s = Math.max(pw / img.width, ph / img.height), iw = img.width * s, ih = img.height * s; x.save(); x.beginPath(); x.rect(px, py, pw, ph); x.clip(); x.drawImage(img, px + (pw - iw) / 2, py + (ph - ih) / 2, iw, ih); x.restore(); }
  } else {
    x.fillStyle = '#b9ab92'; x.beginPath(); x.arc(W / 2, py + 250, 120, 0, Math.PI * 2); x.fill(); x.beginPath(); x.ellipse(W / 2, py + ph, 230, 250, 0, Math.PI, 0); x.fill();
  }
  x.strokeStyle = ink; x.lineWidth = 4; x.strokeRect(px, py, pw, ph);
  t(fullName(p).toUpperCase(), W / 2, 1120, '600 76px ' + serif, ink);
  if (p.alias) t('dit « ' + p.alias + ' »', W / 2, 1170, 'italic 500 40px ' + serif, ink);
  const desc = [p.sex === 'H' ? 'Homme' : p.sex === 'F' ? 'Femme' : null, p.height ? p.height + ' cm' : null, p.eyes ? 'yeux ' + p.eyes : null, p.hair ? 'cheveux ' + p.hair : null].filter(Boolean).join(' · ');
  if (desc) t(desc, W / 2, 1225, '400 30px ' + sans, ink);
  t('Recherché pour : ' + w.reason, W / 2, 1280, '600 32px ' + sans, ink);
  if (p.dangerous || p.armed) { x.fillStyle = red; x.fillRect(120, 1310, W - 240, 64); t([p.armed ? 'ARMÉ' : null, p.dangerous ? 'DANGEREUX' : null].filter(Boolean).join(' ET ') + ' · NE PAS INTERVENIR SEUL', W / 2, 1354, '700 30px ' + sans, '#ffffff'); }
  if (Number(w.reward)) t('RÉCOMPENSE : ' + Math.round(Number(w.reward)).toLocaleString('fr-FR').replace(/\s/g, ' ') + ' $', W / 2, 1450, '600 64px ' + serif, red);
  t('Signalement anonyme : ' + location.host + '/avis/' + org.code, W / 2, 1515, '400 26px ' + sans, ink);
  return c;
}
function posterModal(ctx, w, p, photoUrl) {
  const img = h('img', { class: 'posterimg', alt: 'Affiche de ' + fullName(p) }), wait = h('div', { class: 'docwait' }, 'Préparation de l’affiche…');
  let canvas = null;
  const dl = h('button', { class: 'btn', type: 'button', disabled: true }, ico('telecharger', 18), 'Télécharger (PNG)');
  const pub = h('button', { class: 'btn primary', type: 'button', disabled: true }, w.published ? 'Mettre à jour la page publique' : 'Publier sur la page publique');
  const off = w.published ? h('button', { class: 'btn danger', type: 'button' }, 'Retirer de la page publique') : null;
  const md = modal('Affiche · ' + fullName(p), h('div', { class: 'posterwrap' }, wait, img), h('div', { class: 'foot' }, h('button', { class: 'btn', type: 'button', onclick: () => md.close() }, 'Fermer'), off, dl, pub));
  md.box.classList.add('wide');
  poster(ctx.me, w, p, photoUrl).then(c => { canvas = c; img.src = c.toDataURL('image/png'); wait.remove(); dl.disabled = false; pub.disabled = false; }).catch(e => { wait.textContent = 'Affiche impossible : ' + e.message; });
  const blob = () => new Promise((ok, ko) => { try { canvas.toBlob(b => b ? ok(b) : ko(new Error('Affiche impossible.')), 'image/png'); } catch (e) { ko(new Error('La photo empêche l’export de l’affiche.')); } });
  dl.addEventListener('click', () => run(dl, async () => { saveBlob(await blob(), 'most-wanted-' + w.rank + '-' + slug(fullName(p)) + '.png'); return { message: 'Affiche téléchargée.' }; }));
  pub.addEventListener('click', () => run(pub, async () => {
    const b = await blob(), path = ctx.me.id + '/wanted/' + w.id + '-' + stamp() + '.png';
    await upload('avis', path, new File([b], 'affiche.png', { type: 'image/png' }));
    await rpc('publish_wanted', { p_id: w.id, p_on: true, p_photo: path });
    md.close(); ctx.reload(); return { message: 'Affiche publiée sur la page publique.' };
  }));
  if (off) off.addEventListener('click', () => run(off, async () => { await rpc('publish_wanted', { p_id: w.id, p_on: false, p_photo: null }); md.close(); ctx.reload(); return { message: 'Affiche retirée de la page publique.' }; }));
}
export async function wanted(ctx) {
  const { me, can } = ctx;
  const tab = ['classement', 'historique', 'signalements'].includes(S.filters.wan) ? S.filters.wan : 'classement';
  const [list, people, cases, hist, tips, orgRow] = await Promise.all([rows('wanted', q => q.eq('org_id', me.id).order('rank')), load.people(ctx), rows('cases', q => q.eq('org_id', me.id)),
    rows('wanted_history', q => q.eq('org_id', me.id).order('week_start', { ascending: false }).limit(12)),
    can('wanted') ? rows('tips', q => q.eq('org_id', me.id).order('at', { ascending: false }).limit(200)) : [], rows('orgs', q => q.eq('id', me.id))]);
  const size = (orgRow[0] || {}).wanted_size || 10;
  const urls = await signedUrls(people.filter(p => list.some(w => w.person_id === p.id)).map(p => p.photo));
  const pOf = id => people.find(p => p.id === id), setTab = k => { S.filters.wan = k; wanted(ctx).catch(ctx.fail); };
  const fresh = tips.filter(t => t.status === 'nouveau');
  const pubUrl = location.origin + '/avis/' + me.code;
  const move = (w, d) => ev => run(ev.currentTarget, async () => { await rpc('set_wanted', { p_org: me.id, p_person: w.person_id, p_rank: w.rank + d, p_reward: Number(w.reward), p_reason: w.reason, p_case: w.case_id }); ctx.reload(); return { message: 'Classement mis à jour.' }; });
  const body = {
    classement: () => h('section', { class: 'card' }, list.length ? h('ol', { class: 'wboard' }, list.map(w => {
      const p = pOf(w.person_id) || {}, c = cases.find(x => x.id === w.case_id);
      return h('li', {}, h('span', { class: 'rank big' }, String(w.rank)), face(p.photo, urls, fullName(p), 'lg'),
        h('div', { class: 'art grow' }, h('a', { href: '/' + me.code + '/individus/' + p.id }, h('b', {}, fullName(p))), h('small', {}, (p.alias ? '« ' + p.alias + ' » · ' : '') + w.reason),
          h('span', { class: 'pills' }, Number(w.reward) ? pill(money(w.reward), 'ok') : null, c ? pill(c.num) : null, w.published ? pill('Publié', 'info') : pill('Non publié'), p.dangerous ? pill('Dangereux', 'bad') : null)),
        can('wanted') ? h('div', { class: 'acts' }, h('button', { class: 'btn small ghost', type: 'button', disabled: w.rank === 1, 'aria-label': 'Monter ' + fullName(p), onclick: move(w, -1) }, '▲'),
          h('button', { class: 'btn small ghost', type: 'button', disabled: w.rank === list.length, 'aria-label': 'Descendre ' + fullName(p), onclick: move(w, 1) }, '▼'),
          h('button', { class: 'btn small', type: 'button', onclick: () => wantedModal(ctx, people, cases, w) }, 'Modifier'),
          h('button', { class: 'btn small', type: 'button', onclick: () => posterModal(ctx, w, p, urls[p.photo]) }, 'Affiche'),
          h('button', { class: 'btn small', type: 'button', onclick: () => {
            const why = input({ maxlength: 120, placeholder: 'Arrêté, décédé, abandon…' }), ok = h('button', { class: 'btn danger' }, 'Retirer');
            const md = modal('Retirer ' + fullName(p), field('Motif', why), foot({ close: () => md.close() }, ok));
            ok.addEventListener('click', () => run(ok, async () => { await rpc('remove_wanted', { p_id: w.id, p_reason: why.value.trim() }); md.close(); ctx.reload(); return { message: fullName(p) + ' retiré du classement.' }; }));
          } }, 'Retirer')) : null);
    })) : h('div', { class: 'empty' }, 'Personne au classement.')),
    historique: () => h('section', { class: 'card' }, hist.length ? h('div', { class: 'weeks' }, hist.map(wk => h('div', { class: 'week' }, h('h3', {}, 'Semaine du ' + day(wk.week_start)),
      wk.list.length ? h('ol', { class: 'wk-list' }, wk.list.map(x => h('li', {}, h('b', {}, x.rank + '.'), ' ' + x.name + (x.alias ? ' « ' + x.alias + ' »' : '')))) : h('span', { class: 'muted small' }, 'Classement vide'))))
      : h('div', { class: 'empty' }, 'L’historique commence au premier changement du classement.')),
    signalements: () => h('section', { class: 'card' }, table([['Reçu', t => date(ms(t.at)), 'num', t => t.at], ['Au sujet de', t => t.person_name || 'Non précisé'], ['Message', t => h('span', { class: 'prewrap' }, t.text)],
      ['Contact', t => t.contact || 'Anonyme'], ['Statut', t => t.status === 'nouveau' ? pill('Nouveau', 'warn') : t.status === 'utile' ? pill('Utile', 'ok') : t.status === 'inutile' ? pill('Sans intérêt') : pill('Traité', 'info')],
      ['', t => h('span', { class: 'acts' }, ['utile', 'inutile', 'traite'].filter(s => s !== t.status).map(s => h('button', { class: 'btn small', type: 'button', onclick: ev => run(ev.currentTarget, async () => { await rpc('handle_tip', { p_id: t.id, p_status: s }); ctx.reload(); return { message: 'Signalement mis à jour.' }; }) }, { utile: 'Utile', inutile: 'Sans intérêt', traite: 'Traité' }[s])))]],
      tips, null, 'Aucun signalement reçu.')),
  };
  ctx.show('wanted', 'Most Wanted', list.length + ' / ' + size + ' au classement · ' + list.filter(w => w.published).length + ' affiche(s) publique(s)',
    [h('a', { class: 'btn', href: '/avis/' + me.code, target: '_blank', rel: 'noopener' }, ico('oeil', 18), 'Page publique'),
      h('button', { class: 'btn', type: 'button', onclick: () => copyText(pubUrl, 'Lien de la page publique copié.') }, ico('copier', 18), 'Copier le lien'),
      can('wanted') ? h('button', { class: 'btn primary', type: 'button', disabled: !people.length, onclick: () => wantedModal(ctx, people, cases.filter(c => OPEN.includes(c.status)), null) }, ico('ajout', 18), 'Ajouter') : null],
    can('wanted') ? h('div', { class: 'cardhead plain' }, h('span', { class: 'muted small' }, 'Taille du classement'), h('div', { class: 'seg', role: 'group', 'aria-label': 'Taille du classement' }, [5, 10].map(n => h('button', { type: 'button', class: n === size ? 'on' : null, 'aria-pressed': String(n === size),
      onclick: ev => run(ev.currentTarget, async () => { await rpc('set_wanted_size', { p_org: me.id, p_size: n }); ctx.reload(); return { message: 'Classement à ' + n + ' places.' }; }) }, 'Top ' + n)))) : null,
    tabs([['classement', 'Classement'], ['historique', 'Historique'], can('wanted') ? ['signalements', 'Signalements' + (fresh.length ? ' (' + fresh.length + ' nouveaux)' : '')] : null], tab, setTab),
    body[tab === 'signalements' && !can('wanted') ? 'classement' : tab]());
}

// ================================================================== annonces
export async function annonces(ctx) {
  const { me, can } = ctx;
  const [list, reads, members] = await Promise.all([rows('announcements', q => q.eq('org_id', me.id).order('at', { ascending: false }).limit(100)), rows('announcement_reads', q => q.eq('org_id', me.id)), load.members(ctx)]);
  const active = members.filter(m => m.active), sorted = list.slice().sort((a, b) => b.pinned - a.pinned || ms(b.at) - ms(a.at));
  const post = () => {
    const title = input({ maxlength: 80 }), body = area({ maxlength: 2000, rows: 6 }), pinned = h('input', { type: 'checkbox' });
    let level = 'info';
    const seg = h('div', { class: 'seg full', role: 'group', 'aria-label': 'Importance' });
    const paint = () => seg.replaceChildren(...Object.entries(ANN).map(([k, [l]]) => h('button', { type: 'button', class: k === level ? 'on' : null, 'aria-pressed': String(k === level), onclick: () => { level = k; paint(); } }, l)));
    paint();
    const ok = h('button', { class: 'btn primary' }, 'Publier');
    const md = modal('Nouvelle annonce', seg, field('Titre', title), field('Annonce', body), h('label', { class: 'check' }, pinned, 'Épingler en haut'),
      h('p', { class: 'note' }, 'Les annonces importantes et urgentes s’affichent sur le tableau de bord de chaque agent jusqu’à ce qu’il les lise.'), foot({ close: () => md.close() }, ok));
    ok.addEventListener('click', () => run(ok, async () => { await rpc('post_announcement', { p_org: me.id, p_title: title.value.trim(), p_body: body.value.trim(), p_level: level, p_pinned: pinned.checked }); md.close(); ctx.reload(); return { message: 'Annonce publiée.' }; }));
  };
  ctx.show('annonces', 'Annonces', list.filter(a => !reads.some(r => r.announcement_id === a.id && r.member_id === me.member_id)).length + ' non lue(s)',
    can('annonces') ? h('button', { class: 'btn primary', type: 'button', onclick: post }, ico('megaphone', 18), 'Nouvelle annonce') : null,
    sorted.length ? sorted.map(a => {
      const who = reads.filter(r => r.announcement_id === a.id), mine = who.some(r => r.member_id === me.member_id);
      const missing = active.filter(m => !who.some(r => r.member_id === m.id));
      return h('section', { class: 'card ann ' + a.level + (mine ? '' : ' unread') }, h('div', { class: 'ann-top' }, pill(...ANN[a.level]), a.pinned ? pill('Épinglée') : null, mine ? null : pill('Non lue', 'warn'),
        h('span', { class: 'muted small grow' }, a.by_name + ' · ' + date(ms(a.at))),
        can('annonces') ? h('button', { class: 'btn small ghost', type: 'button', onclick: ev => run(ev.currentTarget, async () => { await rpc('delete_announcement', { p_id: a.id }); ctx.reload(); return { message: 'Annonce supprimée.' }; }) }, 'Supprimer') : null),
        h('h2', {}, a.title), h('p', { class: 'prewrap' }, a.body),
        h('div', { class: 'ann-foot' }, h('span', { class: 'muted small', title: can('annonces') && missing.length ? 'Pas encore lu par : ' + missing.map(m => m.name).join(', ') : null }, 'Lu par ' + who.length + ' / ' + active.length + ' agent(s)' + (can('annonces') && missing.length && missing.length <= 6 ? ' · manque ' + missing.map(m => m.callsign || m.name).join(', ') : '')),
          mine ? h('span', { class: 'muted small' }, ico('coche', 14), ' Lue') : h('button', { class: 'btn small primary', type: 'button', onclick: ev => run(ev.currentTarget, async () => { await rpc('read_announcement', { p_id: a.id }); ctx.reload(); return { message: 'Marquée comme lue.' }; }) }, 'J’ai lu')));
    }) : h('section', { class: 'card' }, h('div', { class: 'empty' }, 'Aucune annonce.')));
}

// ================================================================== réglages propres aux services
export async function settingsCards(ctx, grades) {
  const { me } = ctx;
  const [units, quals, members] = await Promise.all([load.units(ctx), rows('qualifications', q => q.eq('org_id', me.id).order('name')), load.members(ctx)]);
  const used = g => members.filter(m => m.active && m.grade_id === g.id).length;
  const gradeModal = g => {
    const badge = input({ maxlength: 12, value: g.badge || '', placeholder: '★★' }), seats = h('input', { type: 'number', min: 1, max: 500, value: g.max_seats || '', class: 'short', placeholder: '∞' });
    const salary = h('input', { type: 'number', min: 0, step: 50, value: g.salary === null || g.salary === undefined ? '' : Number(g.salary), class: 'short' }), ok = h('button', { class: 'btn primary' }, 'Enregistrer');
    const md = modal('Grade ' + g.name, h('div', { class: 'row' }, field('Insigne', badge, 'grow'), field('Places', seats), field('Salaire ($ / sem.)', salary)),
      h('p', { class: 'note' }, 'Laisse « Places » vide pour ne pas limiter. Un recrutement ou une promotion est refusé quand le grade est plein.'), foot({ close: () => md.close() }, ok));
    ok.addEventListener('click', () => run(ok, async () => { await rpc('set_grade_meta', { p_grade: g.id, p_badge: badge.value.trim(), p_seats: seats.value ? Number(seats.value) : null, p_salary: salary.value === '' ? null : Number(salary.value) }); md.close(); ctx.reload(); return { message: 'Grade enregistré.' }; }));
  };
  const unitModal = u => {
    const code = input({ maxlength: 8, value: u ? u.code : '', placeholder: 'IOD' }), name = input({ maxlength: 60, value: u ? u.name : '' }), restricted = h('input', { type: 'checkbox', checked: u ? u.restricted : false });
    const archived = u ? h('input', { type: 'checkbox', checked: u.archived }) : null, ok = h('button', { class: 'btn primary' }, 'Enregistrer');
    const md = modal(u ? 'Unité ' + u.code : 'Nouvelle unité', h('div', { class: 'row' }, field('Code', code), field('Nom', name, 'grow')), h('label', { class: 'check' }, restricted, 'Accès restreint (ex. WITSEC)'),
      archived ? h('label', { class: 'check' }, archived, 'Archivée (n’apparaît plus dans les choix)') : null, foot({ close: () => md.close() }, ok));
    ok.addEventListener('click', () => run(ok, async () => { await rpc('save_unit', { p_org: me.id, p_id: u ? u.id : null, p_code: code.value.trim(), p_name: name.value.trim(), p_restricted: restricted.checked, p_archived: archived ? archived.checked : false }); md.close(); ctx.reload(); return { message: 'Unité enregistrée.' }; }));
  };
  const qualIn = input({ maxlength: 40, placeholder: 'Ex. Plongeur', 'aria-label': 'Nouvelle qualification' }), qualAdd = h('button', { class: 'btn' }, 'Ajouter');
  qualAdd.addEventListener('click', () => run(qualAdd, async () => { await rpc('save_qualification', { p_org: me.id, p_id: null, p_name: qualIn.value.trim(), p_archived: false }); ctx.reload(); return { message: 'Qualification ajoutée.' }; }));
  return [
    h('section', { class: 'card' }, h('h2', {}, 'Grades : insignes, places et salaires'), table([['Grade', g => h('span', { class: 'gradecell' }, g.badge ? h('span', { class: 'insigne' }, g.badge) : null, h('b', {}, g.name))],
      ['Agents', g => used(g) + (g.max_seats ? ' / ' + g.max_seats : ''), 'num', g => used(g)], ['Salaire', g => g.salary === null || g.salary === undefined ? '—' : money(g.salary) + ' / sem.', 'num', g => Number(g.salary || 0)],
      ['', g => h('button', { class: 'btn small', type: 'button', onclick: ev => { ev.stopPropagation(); gradeModal(g); } }, 'Modifier')]], grades)),
    h('div', { class: 'grid cols2' },
      h('section', { class: 'card' }, h('h2', {}, 'Unités', h('span', { class: 'right' }, h('button', { class: 'btn small', type: 'button', onclick: () => unitModal(null) }, ico('ajout', 16), 'Unité'))),
        table([['Code', u => h('b', {}, u.code)], ['Nom', u => u.name], ['', u => h('span', { class: 'pills' }, u.restricted ? pill('Restreinte', 'warn') : null, u.archived ? pill('Archivée') : null)]], units, u => unitModal(u), 'Aucune unité.')),
      h('section', { class: 'card' }, h('h2', {}, 'Qualifications'), h('ul', { class: 'quals' }, quals.map(q => h('li', { class: q.archived ? 'off' : null }, h('span', { class: 'art grow' }, h('b', {}, q.name), q.archived ? h('small', {}, 'Archivée') : null),
        h('button', { class: 'btn small ghost', type: 'button', onclick: ev => run(ev.currentTarget, async () => { await rpc('save_qualification', { p_org: me.id, p_id: q.id, p_name: q.name, p_archived: !q.archived }); ctx.reload(); return { message: q.archived ? 'Qualification rétablie.' : 'Qualification archivée.' }; }) }, q.archived ? 'Rétablir' : 'Archiver')))),
        h('div', { class: 'row', style: 'margin-top:12px' }, h('div', { class: 'f grow' }, qualIn), qualAdd)))];
}

// ================================================================== espace Justice
export async function justice(o) {
  const all = await rpc('justice_warrants');
  const f = S.filters.jus || 'attente';
  const groups = { attente: w => w.status === 'envoye', signes: w => wStatus(w) === 'signe', tous: () => true };
  const decide = (w, okDecision) => {
    const days = h('input', { type: 'number', min: 1, max: 30, value: w.valid_days, class: 'short' }), note = area({ maxlength: 500, placeholder: okDecision ? 'Conditions, remarques (facultatif)' : 'Pourquoi le mandat est refusé' });
    const ok = h('button', { class: 'btn ' + (okDecision ? 'primary' : 'danger') }, okDecision ? 'Signer le mandat' : 'Refuser');
    const md = modal((okDecision ? 'Signer ' : 'Refuser ') + w.num, okDecision ? field('Validité (jours)', days) : null, field(okDecision ? 'Note' : 'Motif du refus', note), foot({ close: () => md.close() }, ok));
    ok.addEventListener('click', () => run(ok, async () => { await rpc('decide_warrant', { p_id: w.id, p_ok: okDecision, p_note: note.value.trim() || null, p_days: Number(days.value) || w.valid_days }); md.close(); o.reload(); return { message: okDecision ? w.num + ' signé.' : w.num + ' refusé.' }; }));
  };
  const pending = all.filter(groups.attente);
  o.show('justice', 'Mandats', 'Les demandes des services publics, à signer ou à refuser', null,
    h('section', { class: 'card' }, strip([['À décider', String(pending.length), pending.length ? 'warn-t' : null], ['Signés en cours', String(all.filter(groups.signes).length)],
      ['Exécutés', String(all.filter(w => w.status === 'execute').length)], ['Refusés', String(all.filter(w => w.status === 'refuse').length)]])),
    fchips([['attente', 'À décider'], ['signes', 'Signés'], ['tous', 'Tous']], f, k => { S.filters.jus = k; o.reload(); }, 'Mandats'),
    f === 'attente' ? (pending.length ? pending.map(w => h('section', { class: 'card wcard' }, h('div', { class: 'wc-top' }, h('b', { class: 'wc-num' }, w.num), pill(WKIND[w.kind], 'info'), w.dangerous ? pill('Dangereux', 'bad') : null, w.armed ? pill('Armé', 'bad') : null,
      h('span', { class: 'muted small grow' }, w.org + ' · ' + w.requested_by + ' · ' + ago(ms(w.sent_at)))),
      h('h2', {}, w.target + (w.alias ? ' « ' + w.alias + ' »' : '')),
      h('dl', { class: 'lines-dl rules' }, w.place ? [h('dt', {}, 'Lieu'), h('dd', {}, w.place)] : null, w.case_num ? [h('dt', {}, 'Dossier'), h('dd', {}, w.case_num + ' · ' + w.case_title)] : null,
        w.charges ? [h('dt', {}, 'Charges'), h('dd', {}, w.charges)] : null, h('dt', {}, 'Validité demandée'), h('dd', {}, w.valid_days + ' jour(s)')),
      h('p', { class: 'prewrap' }, w.reason),
      h('div', { class: 'acts' }, h('button', { class: 'btn danger', type: 'button', onclick: () => decide(w, false) }, 'Refuser'), h('button', { class: 'btn primary', type: 'button', onclick: () => decide(w, true) }, ico('marteau', 18), 'Signer'))))
      : h('section', { class: 'card' }, h('div', { class: 'calm' }, h('span', { class: 'ic' }, ico('coche')), 'Aucune demande en attente.')))
      : h('section', { class: 'card' }, table([['N°', w => h('b', {}, w.num)], ['Service', w => w.org], ['Type', w => WKIND[w.kind]], ['Visé', w => w.target], ['Statut', w => h('span', { class: 'art' }, pill(...WST[wStatus(w)]), expiry(w) ? h('small', {}, expiry(w)) : null)],
        ['Décision', w => w.decided_at ? date(ms(w.decided_at)) + ' · ' + w.decided_by : '—', 'num', w => w.decided_at || '']], all.filter(groups[f]), w => warrantView({ can: () => false }, w, h('span', { class: 'muted small' }, w.org)), 'Aucun mandat.')));
}

// ================================================================== page publique : avis de recherche et signalement anonyme
export async function publicWanted(code, $app) {
  const d = await rpc('wanted_public', { p_code: code });
  if (!d) return false;
  const pick = select([['', 'Je ne sais pas / autre']].concat(d.list.map(w => [w.id, w.rank + '. ' + w.name])), '', { 'aria-label': 'Au sujet de' });
  const text = area({ maxlength: 1000, rows: 5, placeholder: 'Où, quand, avec qui, quel véhicule…', 'aria-label': 'Ce que tu as vu' }), contact = input({ maxlength: 80, placeholder: 'Facultatif : numéro ou pseudo Discord', 'aria-label': 'Contact (facultatif)' });
  const ok = h('button', { class: 'btn primary wide' }, 'Envoyer le signalement'), done = h('p', { class: 'sent', hidden: true, role: 'status' }, 'Merci. Ton signalement est transmis aux enquêteurs.');
  ok.addEventListener('click', () => run(ok, async () => {
    await rpc('send_tip', { p_code: code, p_wanted: pick.value || null, p_text: text.value.trim(), p_contact: contact.value.trim() || null });
    text.value = ''; contact.value = ''; done.hidden = false; return { message: 'Signalement envoyé, merci.' };
  }));
  $app.replaceChildren(h('div', { class: 'avis' }, h('header', { class: 'avis-head' }, h('div', { class: 'avis-brand' }, h('span', { class: 'avis-star' }, ico('etoile', 26)), h('span', { class: 'art' }, h('b', {}, d.name), h('small', {}, d.kind))), themeSwitch()),
    h('main', { class: 'avis-main' }, h('h1', {}, 'Avis de recherche'), h('p', { class: 'muted' }, 'Ces personnes sont recherchées. Ne tente pas de les arrêter toi-même : signale-les, même anonymement.'),
      d.list.length ? h('div', { class: 'avis-grid' }, d.list.map(w => h('article', { class: 'avis-card' }, w.photo ? h('img', { src: publicUrl(w.photo), alt: 'Affiche de ' + w.name, loading: 'lazy' }) : null,
        h('div', { class: 'avis-info' }, h('span', { class: 'rank' }, String(w.rank)), h('span', { class: 'art' }, h('b', {}, w.name), h('small', {}, (w.alias ? '« ' + w.alias + ' » · ' : '') + w.reason)), Number(w.reward) ? pill('Récompense ' + money(w.reward), 'ok') : null))))
        : h('div', { class: 'card' }, h('div', { class: 'empty' }, 'Aucun avis publié pour l’instant.')),
      h('section', { class: 'card avis-form' }, h('h2', {}, 'Signaler anonymement'), field('Au sujet de', pick), field('Ce que tu as vu', text), field('Contact', contact), ok, done,
        h('p', { class: 'note' }, 'Aucune connexion, aucune adresse IP n’est enregistrée avec ton message.'))),
    h('footer', { class: 'avis-foot' }, butterfly('avis-fly'), h('span', {}, BRAND))));
  return true;
}
