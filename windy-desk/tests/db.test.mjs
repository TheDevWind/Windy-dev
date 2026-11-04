// Tests de la base : node --test tests/  (Postgres 16 en mémoire grâce à PGlite, avec une imitation de l'auth Supabase)
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { PGlite } from '@electric-sql/pglite';

const dir = new URL('../supabase/migrations/', import.meta.url);
const db = new PGlite();
await db.exec(readFileSync(new URL('./supabase-shim.sql', import.meta.url), 'utf8'));
for (const f of readdirSync(dir).sort()) await db.exec(readFileSync(new URL(f, dir), 'utf8'));

const U = { editeur: '00000000-0000-0000-0000-0000000000e1', fisc: '00000000-0000-0000-0000-0000000000f1', patron: '00000000-0000-0000-0000-0000000000a1',
  serveuse: '00000000-0000-0000-0000-0000000000a2', inconnu: '00000000-0000-0000-0000-0000000000b9' };
for (const id of Object.values(U)) await db.query('insert into auth.users (id) values ($1)', [id]);

/** Exécute en tant qu'utilisateur (comme une requête de l'API Supabase). */
async function as(who, sql, params = []) {
  await db.exec('reset role');
  await db.query("select set_config('request.jwt.claim.sub', $1, false)", [who ? U[who] : '']);
  await db.exec('set role ' + (who ? 'authenticated' : 'anon'));
  try { return (await db.query(sql, params)).rows; } finally { await db.exec('reset role'); }
}
const one = async (who, sql, params) => (await as(who, sql, params))[0];
const fails = async (who, sql, params, re) => { await assert.rejects(() => as(who, sql, params), re); };

let org, tokPatron;
test('l’éditeur et le fisc obtiennent leur rôle par un lien à usage unique', async () => {
  const [{ token: t1 }] = (await db.query("insert into public.platform_claims (role, display_name) values ('editeur', 'Windy Dev') returning token")).rows;
  const [{ token: t2 }] = (await db.query("insert into public.platform_claims (role, display_name) values ('fisc', 'Inspecteur Garnier') returning token")).rows;
  assert.equal((await one('editeur', 'select public.claim_platform_role($1) r', [t1])).r, 'editeur');
  assert.equal((await one('fisc', 'select public.claim_platform_role($1) r', [t2])).r, 'fisc');
  await fails('inconnu', 'select public.claim_platform_role($1)', [t1], /plus valable/);
});

test('seul l’éditeur crée une entreprise', async () => {
  await fails('inconnu', "select public.create_org('bella-napoli','Bella Napoli','Pizzeria','Gino Romano','gino.romano',false,array['caisse','stock'],4)", [], /Réservé à l’éditeur/);
  const r = (await one('editeur', "select public.create_org('bella-napoli','Bella Napoli','Pizzeria','Gino Romano','gino.romano',false,array['caisse','stock','caisse'],4) r")).r;
  org = r.org_id; tokPatron = r.token;
  assert.ok(org && tokPatron.length === 64);
  await fails('editeur', "select public.create_org('bella-napoli','Autre','Bar','X Y','x.y',false,array[]::text[],1)", [], /déjà prise/);
  await fails('editeur', "select public.create_org('test-mod','Test','Bar','X Y','x.y',false,array['inconnu'],1)", [], /Module inconnu/);
  assert.deepEqual((await one(null, "select public.org_public('Bella-Napoli') r")).r.modules.sort(), ['caisse', 'stock']);
});

test('le patron active son compte et voit son espace', async () => {
  const info = (await one(null, 'select public.invitation_info($1) r', [tokPatron])).r;
  assert.equal(info.member_name, 'Gino Romano'); assert.equal(info.used, false);
  assert.equal((await one('patron', 'select public.accept_invitation($1) r', [tokPatron])).r.org_code, 'bella-napoli');
  await fails('inconnu', 'select public.accept_invitation($1)', [tokPatron], /déjà servi/);
  const sp = (await one('patron', 'select public.my_spaces() r')).r;
  assert.equal(sp.orgs.length, 1); assert.equal(sp.orgs[0].grade, 'Patron'); assert.equal(sp.orgs[0].caps.length, 23);
});

let tokServeuse, gradeEmploye;
test('le patron invite une serveuse avec un grade limité', async () => {
  gradeEmploye = (await one('patron', "select id from public.grades where org_id = $1 and name = 'Employé'", [org])).id;
  tokServeuse = (await one('patron', 'select public.invite_member($1, $2, $3, $4) t', [org, 'Nina Costa', 'nina.costa', gradeEmploye])).t;
  await fails('patron', 'select public.invite_member($1, $2, $3, $4)', [org, 'Autre Nina', 'nina.costa', gradeEmploye], /déjà pris/);
  await one('serveuse', 'select public.accept_invitation($1)', [tokServeuse]);
  const caps = (await one('serveuse', 'select public.my_spaces() r')).r.orgs[0].caps;
  assert.ok(caps.includes('encaisser') && !caps.includes('compta'));
  await fails('serveuse', 'select public.invite_member($1, $2, $3, $4)', [org, 'Hugo Martin', 'hugo.martin', gradeEmploye], /gérer l’équipe/);
});

test('comptabilité : réservée aux grades autorisés, et immuable', async () => {
  await one('patron', "select public.add_entry($1, 'in', 'Ventes', 'Clôture de caisse', 1250.5)", [org]);
  await fails('serveuse', "select public.add_entry($1, 'in', 'Ventes', 'Vente', 10)", [org], /comptabilité/);
  await fails('patron', "select public.add_entry($1, 'out', 'Impôts', 'Impôt maison', 10)", [org], /impôts sont enregistrés/);
  assert.equal((await as('serveuse', 'select * from public.ledger')).length, 0, 'la serveuse ne lit pas la compta');
  assert.equal((await as('inconnu', 'select * from public.ledger')).length, 0);
  assert.equal((await as('patron', 'select * from public.ledger')).length, 1);
  await fails('patron', 'update public.ledger set amount = 1', [], /permission denied/);
  await fails('patron', 'delete from public.ledger', [], /permission denied/);
  await fails('patron', "insert into public.ledger (org_id, kind, category, label, amount, by_name) values ($1, 'in', 'Ventes', 'triche', 5, 'x')", [org], /permission denied/);
});

test('déclaration : une semaine terminée, une seule fois, impôt selon le barème', async () => {
  // une semaine passée remplie directement (comme si l'activité avait eu lieu)
  await db.query("insert into public.ledger (org_id, at, kind, category, label, amount, by_name) values ($1, now() - interval '8 days', 'in', 'Ventes', 'Semaine passée', 9000, 'Gino Romano'), ($1, now() - interval '8 days', 'out', 'Achats', 'Fournisseur', 2000, 'Gino Romano'), ($1, now() - interval '8 days', 'out', 'Divers', 'Retrait d’espèces', 6000, 'Gino Romano')", [org]);
  const lastWeek = (await db.query("select public.week_of(now() - interval '8 days') w")).rows[0].w;
  const bk = (await one('patron', 'select public.week_book($1, $2) r', [org, lastWeek])).r;
  assert.equal(Number(bk.revenue), 9000); assert.equal(Number(bk.charges), 2000); assert.equal(Number(bk.tax), 700);
  await fails('patron', 'select public.declare_week($1, public.week_of(now()), 0, 0)', [org], /terminée/);
  const r = (await one('patron', 'select public.declare_week($1, $2, $3, $4) r', [org, lastWeek, 6000, 2000])).r;
  assert.equal(Number(r.tax), 400, 'l’entreprise peut sous-déclarer : c’est au fisc de le voir');
  await fails('patron', 'select public.declare_week($1, $2, 1, 1)', [org, lastWeek], /déjà déclarée/);
  await fails('serveuse', 'select public.declare_week($1, $2, 1, 1)', [org, lastWeek], /déclarer/);
  const imp = await as('patron', "select amount from public.ledger where category = 'Impôts'");
  assert.equal(Number(imp[0].amount), 400 + Number(r.late_fee));
});

test('fisc : lecture seule partout, signaux, contrôle et redressement, barème', async () => {
  assert.ok((await as('fisc', 'select * from public.ledger')).length >= 4);
  await fails('fisc', "select public.add_entry($1, 'in', 'Ventes', 'Test', 10)", [org], /comptabilité/);
  const ov = (await one('fisc', 'select public.fisc_overview() r')).r[0];
  assert.equal(Number(ov.underdeclared), 3000, 'sous-déclaration repérée');
  assert.equal(Number(ov.cash_out), 1, 'retrait d’espèces repéré');
  await one('fisc', 'select public.fisc_open($1)', [org]);
  await one('fisc', 'select public.fisc_open_control($1, $2)', [org, 'Chiffre sous-déclaré']);
  assert.equal((await as('patron', 'select * from public.controls')).length, 1, 'l’entreprise voit le contrôle en cours');
  await one('fisc', 'select public.fisc_fine($1, 1500, $2, true)', [org, 'CA sous-déclaré de 3 000 $']);
  assert.equal((await as('patron', "select * from public.ledger where label like 'Redressement%'")).length, 1);
  assert.ok((await as('patron', 'select what from public.journal where by_fisc')).length >= 3, 'chaque geste du fisc est dans le journal');
  await fails('patron', 'select public.fisc_fine($1, 1, $2, true)', [org, 'test test'], /Réservé au contrôleur/);
  await one('fisc', 'select public.set_tax_rules(0.12, 0.05, 1, 20, true)');
  assert.equal(Number((await one(null, 'select rate from public.tax_rules')).rate), 0.12);
  await fails('patron', 'select public.set_tax_rules(0, 0, 1, 20, true)', [], /Réservé au contrôleur/);
  await fails('fisc', 'update public.tax_rules set rate = 0', [], /permission denied/);
});

test('isolement : un inconnu ne voit rien, l’éditeur ne lit pas les comptes', async () => {
  for (const t of ['orgs', 'members', 'grades', 'ledger', 'declarations', 'journal', 'controls', 'invitations', 'platform_claims'])
    assert.equal((await as('inconnu', 'select * from public.' + t).catch(() => [])).length, 0, t);
  assert.equal((await as('editeur', 'select * from public.ledger')).length, 0);
  assert.equal((await one('editeur', 'select public.editor_overview() r')).r[0].accounts, 2);
  await fails(null, 'select public.my_spaces()', [], /permission denied/);
});

test('équipe : grades, désactivation, nouveau lien', async () => {
  const nina = (await one('patron', "select id from public.members where login = 'nina.costa'")).id;
  const top = (await one('patron', "select id from public.grades where org_id = $1 and name = 'Patron'", [org])).id;
  await one('patron', 'select public.set_member($1, $2, false)', [nina, gradeEmploye]);
  assert.equal((await one('serveuse', 'select public.my_spaces() r')).r.orgs.length, 0, 'compte désactivé : plus d’accès');
  const tok = (await one('patron', 'select public.renew_invitation($1) t', [nina])).t;
  await one('serveuse', 'select public.accept_invitation($1)', [tok]);
  assert.equal((await one('serveuse', 'select public.my_spaces() r')).r.orgs.length, 1);
  const g = (await one('patron', "select public.save_grade($1, null, 'Livreur', array['vehicules']) g", [org])).g;
  await fails('patron', "select public.save_grade($1, null, 'X', array['pirate'])", [org], /Accès inconnu/);
  await one('patron', 'select public.set_member($1, $2, true)', [nina, g]);
  assert.deepEqual((await one('serveuse', 'select public.my_spaces() r')).r.orgs[0].caps, ['vehicules']);
  await fails('patron', 'select public.set_member($1, $2, true)', [(await one('patron', "select id from public.members where login='gino.romano'")).id, top], /propre compte/);
});

test('les montants des libellés sont écrits à la française', async () => {
  const f = async x => (await db.query('select public.fmt_money($1) r', [x])).rows[0].r;
  assert.equal(await f(1500), '1 500 $');
  assert.equal(await f(1234567.5), '1 234 567,50 $');
  assert.equal(await f(0.4), '0,40 $');
  await fails('patron', 'select public.fmt_money(1)', [], /permission denied/);
});
