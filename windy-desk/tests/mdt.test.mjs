// Tests des services publics (US Marshals) : node --test tests/  (PGlite, mêmes migrations que Supabase)
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { PGlite } from '@electric-sql/pglite';

const dir = new URL('../supabase/migrations/', import.meta.url);
const db = new PGlite();
await db.exec(readFileSync(new URL('./supabase-shim.sql', import.meta.url), 'utf8'));
for (const f of readdirSync(dir).sort()) await db.exec(readFileSync(new URL(f, dir), 'utf8'));

const U = { editeur: 'e1', fisc: 'f1', juge: 'e2', dir: 'a1', chief: 'a2', sup: 'a3', insp: 'a4', dep: 'a5', rec: 'a6', gino: 'c1', autre: 'd9' };
for (const k of Object.keys(U)) U[k] = '00000000-0000-0000-0000-0000000000' + U[k];
for (const id of Object.values(U)) await db.query('insert into auth.users (id) values ($1)', [id]);

async function as(who, sql, params = []) {
  await db.exec('reset role');
  await db.query("select set_config('request.jwt.claim.sub', $1, false)", [who ? U[who] : '']);
  await db.exec('set role ' + (who ? 'authenticated' : 'anon'));
  try { return (await db.query(sql, params)).rows; } finally { await db.exec('reset role'); }
}
const one = async (who, sql, params) => (await as(who, sql, params))[0];
const val = async (who, sql, params) => Object.values(await one(who, sql, params))[0];
const fails = (who, sql, params, re) => assert.rejects(() => as(who, sql, params), re);
const su = async (sql, params) => (await db.query(sql, params)).rows;
const MDT = "array['effectifs','presence','dossiers','mandats','wanted','annonces','rapports','preuves']";

let usms, bella, G = {}, M = {}, unit = {}, qual = {};

test('l’éditeur crée un service US Marshals prérempli ; les modules suivent le type de client', async () => {
  for (const [role, who, name] of [['editeur', 'editeur', 'Windy Dev'], ['fisc', 'fisc', 'Inspecteur Garnier']]) {
    const [{ token }] = await su('insert into public.platform_claims (role, display_name) values ($1, $2) returning token', [role, name]);
    await one(who, 'select public.claim_platform_role($1)', [token]);
  }
  const tj = await val('editeur', "select public.create_claim('justice', 'Juge Hartley')");
  await fails('editeur', "select public.create_claim('editeur', 'X')", [], /Rôle inconnu/);
  assert.equal(await val('juge', 'select public.claim_platform_role($1)', [tj]), 'justice');
  const acc = await val('editeur', 'select public.platform_access()');
  assert.ok(acc.accounts.some(a => a.role === 'justice' && a.display_name === 'Juge Hartley'));

  await fails('dir', `select public.create_service('usms-ls', 'US Marshals', 'Jack Reed', 'jack.reed', 'USMS', ${MDT}, 4)`, [], /Réservé à l’éditeur/);
  const r = await val('editeur', `select public.create_service('usms-ls', 'US Marshals · Los Santos', 'Jack Reed', 'jack.reed', 'USMS', ${MDT}, 4)`);
  usms = r.org_id;
  await fails('editeur', "select public.create_service('usms-2', 'US Marshals 2', 'A B', 'a.b', 'USMS', array['caisse'], 4)", [], /ne correspond pas/);
  const b = await val('editeur', "select public.create_org('bella-napoli', 'Bella Napoli', 'Pizzeria', 'Gino Romano', 'gino.romano', false, array['caisse'], 4)");
  bella = b.org_id;
  await one('gino', 'select public.accept_invitation($1)', [b.token]);
  await fails('editeur', 'select public.set_org_modules($1, array[\'dossiers\'])', [bella], /ne correspond pas/);

  await one('dir', 'select public.accept_invitation($1)', [r.token]);
  const sp = await val('dir', 'select public.my_spaces()');
  assert.equal(sp.orgs[0].sector, 'service_public'); assert.equal(sp.orgs[0].grade, 'Director'); assert.equal(sp.orgs[0].id_prefix, 'USMS');
  for (const g of await su('select id, name from public.grades where org_id = $1', [usms])) G[g.name] = g.id;
  assert.deepEqual(Object.keys(G).sort(), ['Chief Deputy', 'Deputy', 'Director', 'Inspector', 'Recrue', 'Supervisory']);
  for (const u of await su('select id, code from public.units where org_id = $1', [usms])) unit[u.code] = u.id;
  for (const q of await su('select id, name from public.qualifications where org_id = $1', [usms])) qual[q.name] = q.id;
  assert.equal(Object.keys(unit).length, 5); assert.equal(Object.keys(qual).length, 5);
  const me = await one('dir', 'select matricule, callsign from public.members where org_id = $1', [usms]);
  assert.equal(me.matricule, 'USMS-0001'); assert.equal(me.callsign, 'Marshal-1');
  // le fisc ne contrôle que les entreprises
  const fo = await val('fisc', 'select public.fisc_overview()');
  assert.deepEqual(fo.map(o => o.code), ['bella-napoli']);
});

test('effectifs : recrutement numéroté, places par grade, droits selon le grade', async () => {
  const recruit = async (by, name, login, grade, who, extra = [null, null]) => {
    const r = await val(by, 'select public.recruit_agent($1, $2, $3, $4, $5, $6)', [usms, name, login, G[grade], ...extra]);
    if (who) await one(who, 'select public.accept_invitation($1)', [r.token]);
    M[login] = r.member_id;
    return r;
  };
  assert.equal((await recruit('dir', 'Ava Chen', 'ava.chen', 'Chief Deputy', 'chief', ['Marshal-2', null])).matricule, 'USMS-0002');
  await assert.rejects(() => recruit('dir', 'Bob Two', 'bob.two', 'Chief Deputy'), /Plus de place en Chief Deputy : 1\/1/);
  await recruit('chief', 'Sam Ortiz', 'sam.ortiz', 'Supervisory', 'sup');
  await recruit('sup', 'Ines Park', 'ines.park', 'Inspector', 'insp');
  await recruit('sup', 'Dan Moss', 'dan.moss', 'Deputy', 'dep', ['Marshal-12', '123456789012']);
  const rec = await recruit('sup', 'Rémi Laval', 'remi.laval', 'Recrue', 'rec');
  assert.equal(rec.matricule, 'USMS-0006');   // le recrutement refusé n'a pas consommé de numéro
  assert.equal((await one('sup', 'select stage from public.members where id = $1', [M['remi.laval']])).stage, 'recrue');
  await fails('sup', 'select public.recruit_agent($1, $2, $3, $4, null, null)', [usms, 'Trop Haut', 'trop.haut', G['Chief Deputy']], /plus haut que le tien/);
  await fails('dep', 'select public.recruit_agent($1, $2, $3, $4, null, null)', [usms, 'Non Non', 'non.non', G['Recrue']], /ne permet pas/);
  await fails('sup', 'select public.save_agent($1, $2, null, null, null, null)', [M['ines.park'], 'Marshal-12'], /déjà pris/);

  // promotion : historique de carrière ; on ne promeut pas au-dessus de soi, ni soi-même
  await one('sup', 'select public.promote_agent($1, $2, $3)', [M['dan.moss'], G['Inspector'], 'Belle arrestation']);
  await fails('sup', 'select public.promote_agent($1, $2, null)', [M['dan.moss'], G['Chief Deputy']], /plus haut que le tien/);
  await fails('sup', 'select public.promote_agent($1, $2, null)', [M['sam.ortiz'], G['Inspector']], /propre fiche/);
  await one('sup', 'select public.promote_agent($1, $2, null)', [M['dan.moss'], G['Deputy']]);
  const ev = await as('sup', 'select kind, text from public.career_events where member_id = $1 order by id', [M['dan.moss']]);
  assert.deepEqual(ev.map(e => e.kind), ['recrutement', 'promotion', 'retrogradation']);
  assert.match(ev[1].text, /Deputy → Inspector · Belle arrestation/);

  // unités, qualifications, notes ; l'agent voit sa carrière mais pas les notes du commandement
  await one('sup', 'select public.set_agent_units($1, $2, $3)', [M['dan.moss'], [unit.IOD, unit.SOG], unit.IOD]);
  assert.deepEqual((await as('dep', 'select u.code, mu.main from public.member_units mu join public.units u on u.id = mu.unit_id where mu.member_id = $1 order by u.code', [M['dan.moss']])).map(x => x.code + (x.main ? '*' : '')), ['IOD*', 'SOG']);
  await one('sup', 'select public.set_qualification($1, $2, true)', [M['dan.moss'], qual.SOG]);
  await one('sup', "select public.career_note($1, 'commandement', 'À surveiller de près')", [M['dan.moss']]);
  await one('sup', "select public.career_note($1, 'felicitation', 'Excellent tir')", [M['dan.moss']]);
  const mine = await as('dep', 'select kind from public.career_events where member_id = $1', [M['dan.moss']]);
  assert.ok(mine.some(e => e.kind === 'felicitation')); assert.ok(!mine.some(e => e.kind === 'commandement'));
  assert.equal((await as('dep', 'select * from public.blacklist')).length, 0);
  // l'agent modifie ses coordonnées, pas son indicatif
  await one('dep', 'select public.save_agent($1, $2, $3, null, null, null)', [M['dan.moss'], 'Marshal-99', '555-0199']);
  const d = await one('dep', 'select callsign, phone from public.members where id = $1', [M['dan.moss']]);
  assert.equal(d.callsign, 'Marshal-12'); assert.equal(d.phone, '555-0199');
  await fails('dep', 'select public.save_agent($1, null, $2, null, null, null)', [M['ines.park'], '1'], /propre fiche/);
  // un grade limité ne descend pas sous le nombre d'agents en poste
  await fails('dir', 'select public.set_grade_meta($1, $2, 0, null)', [G['Supervisory'], '★★'], /Insigne|places/);
  await one('dir', 'select public.set_grade_meta($1, $2, 3, 4500)', [G['Supervisory'], '★★']);
});

test('effectifs : départ, liste noire et retour', async () => {
  await one('sup', 'select public.agent_leave($1, $2, true)', [M['remi.laval'], 'Abandon de poste']);
  const sp = await val('rec', 'select public.my_spaces()');
  assert.equal(sp.orgs.length, 0);
  await fails('sup', 'select public.recruit_agent($1, $2, $3, $4, null, null)', [usms, 'Rémi Laval', 'remi.laval2', G['Recrue']], /liste noire/);
  await fails('sup', 'select public.agent_return($1, $2)', [M['remi.laval'], G['Recrue']], /liste noire/);
  const bl = await as('sup', 'select id from public.blacklist where org_id = $1', [usms]);
  await one('sup', 'select public.blacklist_remove($1)', [bl[0].id]);
  await one('sup', 'select public.agent_return($1, $2)', [M['remi.laval'], G['Recrue']]);
  assert.equal((await val('rec', 'select public.my_spaces()')).orgs.length, 1);
  await one('sup', "select public.blacklist_add($1, 'Vic Hale', '99999999', 'Corruption')", [usms]);
  await fails('sup', "select public.recruit_agent($1, 'Victor H', 'victor.h', $2, null, '99999999')", [usms, G['Recrue']], /liste noire/);
});

test('présence : service, statut, absences validées par un responsable, planning', async () => {
  assert.equal(await val('dep', 'select public.mdt_duty($1)', [usms]), true);
  await one('dep', "select public.set_duty_status($1, 'intervention')", [usms]);
  await fails('insp', "select public.set_duty_status($1, 'pause')", [usms], /Prends d’abord ton service/);
  await fails('dep', 'select public.end_duty($1)', [M['dan.moss']], /ne permet pas/);
  await one('sup', 'select public.end_duty($1)', [M['dan.moss']]);
  assert.equal((await as('dep', 'select * from public.shifts where member_id = $1 and ended_at is null', [M['dan.moss']])).length, 0);

  await one('dep', "select public.declare_absence($1, current_date + 1, current_date + 3, 'Vacances')", [usms]);
  await one('sup', "select public.declare_absence($1, current_date, current_date + 1, 'Rendez-vous')", [usms]);
  await fails('dep', "select public.declare_absence($1, current_date + 5, current_date + 2, 'X y z')", [usms], /Vérifie les dates/);
  const abs = await as('sup', 'select id, member_id from public.absences where org_id = $1 order by id', [usms]);
  await fails('dep', 'select public.decide_absence($1, true)', [abs[0].id], /ne permet pas/);
  await fails('sup', 'select public.decide_absence($1, true)', [abs[1].id], /Un autre responsable/);
  await one('sup', 'select public.decide_absence($1, true)', [abs[0].id]);
  await fails('chief', 'select public.decide_absence($1, false)', [abs[0].id], /déjà été traitée/);

  const ag = await val('sup', "select public.save_agenda($1, null, 'Patrouille centre', 'patrouille', now() + interval '1 hour', now() + interval '3 hours', 'Vinewood', $2, null)", [usms, unit.IOD]);
  await fails('dep', "select public.save_agenda($1, null, 'Pas moi', 'patrouille', now(), now() + interval '1 hour', null, null, null)", [usms], /ne permet pas/);
  await one('dep', 'select public.agenda_join($1, true)', [ag]);
  assert.equal((await as('insp', 'select * from public.agenda_staff where agenda_id = $1', [ag])).length, 1);
});

let person, kase;
test('dossiers : individus, dossier numéroté, journal, pièces jointes rangées dans le stockage du service', async () => {
  person = await val('dep', 'select public.save_person($1, null, $2)', [usms, { first_name: 'Tony', last_name: 'Vercetti', alias: 'Le Baron', height: '182', dangerous: true,
    vehicles: [{ plate: '7ABC123', model: 'Sultan', color: 'Noir' }], places: [{ label: 'Planque', address: 'Grove Street' }] }]);
  await fails('rec', 'select public.save_person($1, null, $2)', [usms, { first_name: 'A', last_name: 'B' }], /ne permet pas/);
  await fails('dep', 'select public.save_person($1, null, $2)', [usms, { first_name: 'A', last_name: 'B', height: '20' }], /taille/);
  const c = await val('dep', "select public.open_case($1, $2, 'Évasion de Bolingbroke', 'haute', 'Évasion, vol à main armée', null)", [usms, person]);
  assert.equal(c.num, 'USMS-IOD-0001'); kase = c.id;
  await one('dep', "select public.update_case($1, null, 'localise', null, null, 'Évasion, vol à main armée', 'Vu à Sandy Shores')", [kase]);
  await one('insp', "select public.case_note($1, 'piste', 'Contact connu au Yellow Jack')", [kase]);
  await fails('rec', "select public.case_note($1, 'note', 'Je ne peux pas')", [kase], /ne permet pas/);
  const log = await as('rec', 'select kind, text from public.case_log where case_id = $1 order by id', [kase]);
  assert.deepEqual(log.map(l => l.kind), ['statut', 'statut', 'observation', 'piste']);
  assert.equal((await one('rec', 'select status, last_seen from public.cases where id = $1', [kase])).status, 'localise');

  // stockage : chaque service écrit seulement dans son dossier ; la pièce jointe doit exister
  const path = usms + '/cases/' + kase + '/photo.png';
  await as('dep', "insert into storage.objects (bucket_id, name) values ('mdt', $1)", [path]);
  await fails('gino', "insert into storage.objects (bucket_id, name) values ('mdt', $1)", [usms + '/intrus.png'], /row-level security/);
  await fails('dep', "insert into storage.objects (bucket_id, name) values ('avis', $1)", [usms + '/avis.png'], /row-level security/);
  assert.equal((await as('gino', "select * from storage.objects where bucket_id = 'mdt'")).length, 0);
  await fails('dep', "select public.attach_case_file($1, $2, 'x.png', 'image/png', 10)", [kase, usms + '/cases/nope.png'], /introuvable/);
  await one('dep', "select public.attach_case_file($1, $2, 'photo.png', 'image/png', 2048)", [kase, path]);
  await one('dep', 'select public.save_person($1, $2, $3)', [usms, person, { first_name: 'Tony', last_name: 'Vercetti', alias: 'Le Baron', photo: path, dangerous: true }]);
  assert.equal((await one('dep', 'select photo from public.people where id = $1', [person])).photo, path);
  assert.equal((await as('gino', 'select * from public.cases')).length, 0);
});

let warrant;
test('mandats : demandés par un Inspector, signés par la Justice, avec expiration', async () => {
  await fails('dep', "select public.save_warrant($1, null, 'arret', $2, $3, null, null, 'Évadé, dangereux et armé', 7)", [usms, person, kase], /ne permet pas/);
  warrant = await val('insp', "select public.save_warrant($1, null, 'arret', $2, $3, null, 'Sandy Shores', 'Évadé, dangereux et armé', 3)", [usms, person, kase]);
  await fails('insp', "select public.save_warrant($1, null, 'perquisition', null, null, 'Ferme', null, 'court', 7)", [usms], /Motive/);
  assert.equal((await val('juge', 'select public.justice_warrants()')).length, 0);
  assert.equal((await as('juge', 'select * from public.warrants')).length, 0);
  await one('insp', 'select public.send_warrant($1)', [warrant]);
  const jw = await val('juge', 'select public.justice_warrants()');
  assert.equal(jw.length, 1); assert.equal(jw[0].num, 'USMS-M-0001'); assert.equal(jw[0].target, 'Tony Vercetti'); assert.equal(jw[0].dangerous, true);
  await fails('insp', 'select public.decide_warrant($1, true, null, 3)', [warrant], /Réservé à la Justice/);
  await fails('dep', 'select public.execute_warrant($1, null)', [warrant], /Seul un mandat signé/);
  await one('juge', "select public.decide_warrant($1, true, 'Accordé', 2)", [warrant]);
  const w = await one('insp', 'select status, decided_by, valid_days from public.warrants where id = $1', [warrant]);
  assert.equal(w.status, 'signe'); assert.equal(w.decided_by, 'Juge Hartley'); assert.equal(w.valid_days, 2);
  // expiré : il ne s'exécute plus
  await su("update public.warrants set expires_at = now() - interval '1 minute' where id = $1", [warrant]);
  await fails('dep', 'select public.execute_warrant($1, null)', [warrant], /a expiré/);
  await su("update public.warrants set expires_at = now() + interval '1 day' where id = $1", [warrant]);
  await one('dep', "select public.execute_warrant($1, 'Interpellé sans incident')", [warrant]);
  const log = await as('dep', "select text from public.case_log where case_id = $1 and kind = 'mandat' order by id", [kase]);
  assert.equal(log.length, 3); assert.match(log[2].text, /exécuté/);
  // un refus se motive
  const w2 = await val('insp', "select public.save_warrant($1, null, 'perquisition', null, null, '12 Grove Street', null, 'Planque présumée du suspect', 7)", [usms]);
  await one('insp', 'select public.send_warrant($1)', [w2]);
  await fails('juge', 'select public.decide_warrant($1, false, null, null)', [w2], /Motive le refus/);
  await one('juge', "select public.decide_warrant($1, false, 'Éléments insuffisants', null)", [w2]);
});

test('Most Wanted : classement, historique, affiche publique et signalements anonymes', async () => {
  const people = [];
  for (const [f, l] of [['Ana', 'Ruiz'], ['Ben', 'Holt'], ['Cid', 'Moro'], ['Dee', 'Fox'], ['Eli', 'Ward'], ['Fay', 'Lin']]) {
    people.push(await val('dep', 'select public.save_person($1, null, $2)', [usms, { first_name: f, last_name: l }]));
  }
  await fails('dep', "select public.set_wanted($1, $2, 1, 0, 'Meurtre', null)", [usms, person], /ne permet pas/);
  await one('insp', "select public.set_wanted($1, $2, 1, 50000, 'Évasion', $3)", [usms, person, kase]);
  for (const p of people.slice(0, 4)) await one('insp', "select public.set_wanted($1, $2, 99, 1000, 'Vol', null)", [usms, p]);
  await one('insp', "select public.set_wanted($1, $2, 2, 9000, 'Meurtre', null)", [usms, people[4]]);
  const order = async () => (await as('rec', 'select p.first_name from public.wanted w join public.people p on p.id = w.person_id where w.org_id = $1 order by w.rank', [usms])).map(x => x.first_name);
  assert.deepEqual(await order(), ['Tony', 'Eli', 'Ana', 'Ben', 'Cid', 'Dee']);
  assert.equal((await one('rec', 'select priority from public.cases where id = $1', [kase])).priority, 'most_wanted');
  // on remonte quelqu'un, puis le classement passe à 5 places
  await one('insp', "select public.set_wanted($1, $2, 1, 1000, 'Vol', null)", [usms, people[3]]);
  assert.deepEqual(await order(), ['Dee', 'Tony', 'Eli', 'Ana', 'Ben', 'Cid']);
  await one('insp', 'select public.set_wanted_size($1, 5)', [usms]);
  assert.deepEqual(await order(), ['Dee', 'Tony', 'Eli', 'Ana', 'Ben']);
  await one('insp', "select public.set_wanted($1, $2, 3, 0, 'Recel', null)", [usms, people[5]]);
  assert.deepEqual(await order(), ['Dee', 'Tony', 'Fay', 'Eli', 'Ana']);
  const tony = (await one('insp', 'select id from public.wanted where person_id = $1', [person])).id;
  await one('insp', "select public.remove_wanted($1, 'Arrêté')", [tony]);
  assert.deepEqual(await order(), ['Dee', 'Fay', 'Eli', 'Ana']);
  const hist = await as('rec', 'select list from public.wanted_history where org_id = $1', [usms]);
  assert.equal(hist.length, 1); assert.deepEqual(hist[0].list.map(x => x.rank), [1, 2, 3, 4]);

  // affiche : photo obligatoire, déposée dans le stockage public par un agent qui a l'accès
  const dee = (await one('insp', 'select id from public.wanted where person_id = $1', [people[3]])).id;
  await fails('insp', 'select public.publish_wanted($1, true, null)', [dee], /Ajoute une photo/);
  const poster = usms + '/wanted/' + dee + '.png';
  await as('insp', "insert into storage.objects (bucket_id, name) values ('avis', $1)", [poster]);
  await one('insp', 'select public.publish_wanted($1, true, $2)', [dee, poster]);
  const pub = await val(null, "select public.wanted_public('USMS-LS')");
  assert.equal(pub.list.length, 1); assert.equal(pub.list[0].name, 'Dee Fox'); assert.equal(pub.list[0].photo, poster);
  assert.equal(await val(null, "select public.wanted_public('bella-napoli')"), null);

  await one(null, "select public.send_tip('usms-ls', $1, 'Je l’ai vue au Vanilla Unicorn ce soir', null)", [dee]);
  await fails(null, "select public.send_tip('usms-ls', null, 'court', null)", [], /10 caractères/);
  await fails(null, "select * from public.tips", [], /permission denied/);
  assert.equal((await as('dep', 'select * from public.tips')).length, 0);
  const tips = await as('insp', 'select id, person_name from public.tips where org_id = $1', [usms]);
  assert.equal(tips[0].person_name, 'Dee Fox');
  await one('insp', "select public.handle_tip($1, 'utile')", [tips[0].id]);
  await su("insert into public.tips (org_id, text) select $1, 'signalement de test ' || g from generate_series(1, 29) g", [usms]);
  await fails(null, "select public.send_tip('usms-ls', null, 'Encore un signalement', null)", [], /Trop de signalements/);
});

test('annonces : publiées par le commandement, accusé de lecture', async () => {
  const a = await val('sup', "select public.post_announcement($1, 'Briefing', 'Réunion à 20 h au QG.', 'important', true)", [usms]);
  await fails('dep', "select public.post_announcement($1, 'Moi', 'Non autorisé', 'info', false)", [usms], /ne permet pas/);
  await one('dep', 'select public.read_announcement($1)', [a]);
  await one('dep', 'select public.read_announcement($1)', [a]);
  assert.equal((await as('rec', 'select * from public.announcement_reads where announcement_id = $1', [a])).length, 2);
  assert.equal((await as('gino', 'select * from public.announcements')).length, 0);
  await one('sup', 'select public.delete_announcement($1)', [a]);
  assert.equal((await as('dep', 'select * from public.announcements')).length, 0);
});

// ================================================================== étape 2 : base de données, recherche, rapports, preuves
test('base de données : organisations et véhicules tirés des fiches, entourage, recherche globale', async () => {
  const [tonyPlate] = await as('rec', "select plate, owner_id from public.plates where plate = '7ABC123'");
  assert.equal(tonyPlate.owner_id, person);
  const lance = await val('dep', 'select public.save_person($1, null, $2)', [usms, { first_name: 'Lance', last_name: 'Vance', gang: 'Vagos', danger: 'extreme', kind: 'suspect',
    vehicles: [{ plate: 'ls 42 42', model: 'Infernus' }] }]);
  const l = await one('dep', 'select dangerous, danger, gang_id from public.people where id = $1', [lance]);
  assert.equal(l.dangerous, true); assert.equal(l.danger, 'extreme'); assert.ok(l.gang_id);
  assert.equal((await one('dep', 'select name from public.gangs where id = $1', [l.gang_id])).name, 'Vagos');
  // la même organisation tapée autrement ne se duplique pas
  await one('dep', 'select public.save_person($1, null, $2)', [usms, { first_name: 'Ana', last_name: 'Diaz', gang: 'vagos' }]);
  assert.equal((await as('dep', 'select * from public.gangs where org_id = $1', [usms])).length, 1);
  await fails('rec', "select public.save_gang($1, null, 'Ballas', null, null, null, null)", [usms], /ne permet pas/);
  await one('dep', "select public.save_gang($1, null, 'Ballas', '#7b2d8e', 'Grove Street', 'Trafic de drogue', null)", [usms]);

  await one('dep', "select public.link_people($1, $2, 'complice', 'Évasion')", [person, lance]);
  await one('dep', "select public.link_people($1, $2, 'proche', null)", [lance, person]);
  const links = await as('rec', 'select kind from public.person_links where org_id = $1', [usms]);
  assert.deepEqual(links.map(x => x.kind), ['proche']);
  await fails('dep', "select public.link_people($1, $1, 'proche', null)", [person], /autre personne/);

  const plate = await val('dep', "select public.save_plate($1, null, 'sx 777', 'Sultan', 'Bleue', $2, true, false, null)", [usms, lance]);
  await one('rec', "select public.plate_note($1, 'observation', 'Vue à Vinewood')", [plate]);
  const log = await as('rec', 'select kind from public.plate_log where plate_id = $1 order by id', [plate]);
  assert.deepEqual(log.map(x => x.kind), ['creation', 'vol', 'observation']);
  await fails('dep', "select public.save_plate($1, null, 'SX 777', null, null, null, false, false, null)", [usms], /déjà fichée/);
  await fails('dep', "select public.save_plate($1, null, '!!', null, null, null, false, false, null)", [usms], /Plaque invalide/);

  const s1 = await val('rec', "select public.mdt_search($1, 'vance')", [usms]);
  assert.deepEqual(s1.people.map(x => x.last_name), ['Vance']);
  const s2 = await val('rec', "select public.mdt_search($1, '7abc')", [usms]);
  assert.deepEqual(s2.plates.map(x => x.plate), ['7ABC123']); assert.equal(s2.plates[0].owner, 'Tony Vercetti');
  const s3 = await val('rec', "select public.mdt_search($1, 'iod-0001')", [usms]);
  assert.deepEqual(s3.docs.map(x => x.type + ' ' + x.num), ['dossier USMS-IOD-0001']);
  assert.deepEqual((await val('rec', "select public.mdt_search($1, 'vag')", [usms])).gangs.map(x => x.name), ['Vagos']);
  await fails('gino', "select public.mdt_search($1, 'vance')", [usms], /Accès refusé/);
  await fails('rec', "select public.mdt_search($1, 'v')", [usms], /2 caractères/);
});

let report;
test('rapports : numéro, champs obligatoires, peine calculée par le code pénal, validation, brouillons privés', async () => {
  const pc = {}; for (const r of await as('dep', "select id, code from public.penal_code where org_id = $1", [usms])) pc[r.code] = r.id;
  assert.ok(Object.keys(pc).length >= 25);
  const save = (who, id, kind, title, fields, narrative, charges, extra = {}) => val(who,
    'select public.save_report($1, $2, $3, $4, $5, $6, now(), $7, $8, $9, $10, $11, $12)',
    [usms, id, kind, title, extra.person === undefined ? person : extra.person, extra.case === undefined ? kase : extra.case, extra.place === undefined ? 'Sandy Shores' : extra.place,
      fields, narrative, charges, !!extra.firearm, extra.participants || []]);
  const r = await save('dep', null, 'arrestation', 'Arrestation de Tony Vercetti', {}, 'Court', [{ id: pc['D-01'], qty: 2 }, { id: pc['CR-07'], fine: 1 }]);
  assert.equal(r.num, 'USMS-A-0001'); report = r.id;
  const row = await one('dep', 'select total_fine, total_jail, charges from public.reports where id = $1', [report]);
  assert.equal(Number(row.total_fine), 30000); assert.equal(row.total_jail, 65); assert.equal(row.charges.length, 2);
  await fails('dep', 'select public.submit_report($1)', [report], /20 caractères/);
  await save('dep', report, 'arrestation', 'Arrestation de Tony Vercetti', {}, 'Interpellé à Sandy Shores après une course-poursuite.', [{ id: pc['D-01'], qty: 2 }, { id: pc['CR-07'] }]);
  await fails('dep', 'select public.submit_report($1)', [report], /droits/);
  await save('dep', report, 'arrestation', 'Arrestation de Tony Vercetti', { rights: 'true', health: 'Bon' }, 'Interpellé à Sandy Shores après une course-poursuite.', [{ id: pc['D-01'], qty: 2 }, { id: pc['CR-07'] }]);
  await fails('dep', "select public.save_report($1, null, 'arrestation', 'X y z', null, null, now(), null, '{}', '', $2, false, '{}')", [usms, JSON.stringify([{ id: '00000000-0000-0000-0000-000000000000' }])], /Infraction inconnue/);
  // un brouillon n'est lu que par son auteur et par les responsables
  assert.equal((await as('rec', 'select * from public.reports where id = $1', [report])).length, 0);
  assert.equal((await as('sup', 'select * from public.reports where id = $1', [report])).length, 1);
  await one('dep', 'select public.submit_report($1)', [report]);
  await fails('dep', 'select public.save_report($1, $2, \'arrestation\', \'Autre titre\', null, null, now(), null, \'{}\', \'\', \'[]\', false, \'{}\')', [usms, report], /ne se modifie plus/);
  await fails('insp', "select public.review_report($1, true, null)", [report], /ne permet pas/);
  await fails('sup', "select public.review_report($1, false, null)", [report], /corriger/);
  await one('sup', "select public.review_report($1, false, 'Précise l’heure de l’interpellation.')", [report]);
  assert.equal((await one('dep', 'select status, review_note from public.reports where id = $1', [report])).status, 'a_corriger');
  await one('dep', 'select public.submit_report($1)', [report]);
  await one('sup', "select public.review_report($1, true, 'RAS')", [report]);
  const done = await one('rec', 'select status, reviewer from public.reports where id = $1', [report]);
  assert.deepEqual(done, { status: 'valide', reviewer: 'Sam Ortiz' });

  assert.equal((await save('sup', null, 'sog', 'Assaut planque Grove', { objective: 'Interpeller' }, 'x', [], { person: null })).num, 'USMS-SOG-001');
  const lib = await save('dep', null, 'libre', 'Brouillon à jeter', {}, '', [], { person: null, case: null, place: null });
  await fails('sup', 'select public.delete_report($1)', [lib.id], /Seul un brouillon/);
  await one('dep', 'select public.delete_report($1)', [lib.id]);
});

test('rapports : un tir avec une arme à feu ouvre un dossier Affaires internes, visible du Director seulement', async () => {
  const r = await val('dep', "select public.save_report($1, null, 'force', 'Tir lors de l’interpellation', $2, null, now(), 'Sandy Shores', $3, $4, '[]', false, '{}')",
    [usms, person, { force_type: 'Arme à feu', injuries: 'Blessure à la jambe' }, 'Le suspect a pointé une arme, deux tirs de riposte.']);
  assert.equal(r.num, 'USMS-UF-0001');
  assert.equal((await one('dep', 'select firearm from public.reports where id = $1', [r.id])).firearm, true);
  await one('dep', 'select public.submit_report($1)', [r.id]);
  assert.equal((await as('dep', 'select * from public.ia_cases')).length, 0);
  assert.equal((await as('sup', 'select * from public.ia_cases')).length, 0);
  const ia = await as('dir', 'select num, member_id, reason from public.ia_cases');
  assert.equal(ia.length, 1); assert.equal(ia[0].num, 'USMS-AI-0001'); assert.equal(ia[0].member_id, M['dan.moss']);
});

test('code pénal et preuves : registre, chaîne de possession, sortie du registre', async () => {
  await fails('dep', "select public.save_penal($1, null, 'D-99', 'Test', 'delit', 100, 1, false)", [usms], /ne permet pas/);
  await one('dir', "select public.save_penal($1, null, 'd-99', 'Vandalisme', 'delit', 1200, 2, false)", [usms]);
  await fails('dir', "select public.save_penal($1, null, 'D-99', 'Doublon', 'delit', 1, 1, false)", [usms], /existe déjà/);

  const e = await val('dep', 'select public.add_evidence($1, $2)', [usms, { kind: 'arme', description: 'Pistolet 9 mm, numéro limé', place: 'Sandy Shores', case_id: kase, report_id: report, person_id: person }]);
  assert.equal(e.num, 'USMS-P-0001');
  await fails('rec', 'select public.add_evidence($1, $2)', [usms, { kind: 'drogue', description: 'Sachet' }], /ne permet pas/);
  await one('dep', 'select public.add_evidence($1, $2)', [usms, { kind: 'argent', description: 'Liasses', amount: '12500' }]);
  await fails('dep', "select public.evidence_action($1, 'transfert', null)", [e.id], /Précise/);
  await one('dep', "select public.evidence_action($1, 'transfert', 'Armurerie, casier 4')", [e.id]);
  await one('insp', "select public.evidence_action($1, 'tribunal', null)", [e.id]);
  assert.equal((await one('rec', 'select status, holder from public.evidence where id = $1', [e.id])).status, 'tribunal');
  await one('insp', "select public.evidence_action($1, 'destruction', 'Fin de procédure')", [e.id]);
  await fails('insp', "select public.evidence_action($1, 'transfert', 'Ailleurs')", [e.id], /sortie du registre/);
  const chain = await as('rec', 'select action from public.evidence_log where evidence_id = $1 order by id', [e.id]);
  assert.deepEqual(chain.map(x => x.action), ['saisie', 'transfert', 'tribunal', 'destruction']);
  assert.ok((await as('rec', "select text from public.case_log where case_id = $1 and text like 'Preuve USMS-P-0001%'", [kase])).length === 1);
});

test('signatures : choisies par l’agent, apposées à la soumission et à la validation, figées sur le rapport', async () => {
  await fails('gino', "select public.save_signature($1, 'Gino', 'classique')", [usms], /Accès refusé/);
  await fails('dep', "select public.save_signature($1, 'D', 'classique')", [usms], /2 à 40/);
  await fails('dep', "select public.save_signature($1, 'Dan Moss', 'gothique')", [usms], /modèle/);
  await one('dep', "select public.save_signature($1, 'D. Moss', 'rapide')", [usms]);
  await one('sup', "select public.save_signature($1, 'S. Ortiz', 'paraphe')", [usms]);
  assert.equal((await val('dep', 'select public.my_spaces()')).orgs[0].signature_style, 'rapide');
  const r = await val('dep', "select public.save_report($1, null, 'libre', 'Note de service', null, null, now(), null, '{}', $2, '[]', false, '{}')", [usms, 'Un texte assez long pour être soumis.']);
  await one('dep', 'select public.submit_report($1)', [r.id]);
  await one('sup', 'select public.review_report($1, true, null)', [r.id]);
  const sig = async () => one('rec', 'select author_signature a, reviewer_signature b, reviewer_id from public.reports where id = $1', [r.id]);
  const s = await sig();
  assert.deepEqual([s.a.name, s.a.style, s.a.grade, s.a.printed], ['D. Moss', 'rapide', 'Deputy', 'Dan Moss']);
  assert.deepEqual([s.b.name, s.b.style, s.b.grade], ['S. Ortiz', 'paraphe', 'Supervisory']);
  assert.equal(s.reviewer_id, M['sam.ortiz']);
  await one('dep', "select public.save_signature($1, 'Daniel Moss', 'classique')", [usms]);
  assert.equal((await sig()).a.name, 'D. Moss');
});
