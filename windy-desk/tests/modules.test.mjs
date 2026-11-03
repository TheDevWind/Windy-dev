// Tests des modules : node --test tests/  (Postgres 16 en mémoire grâce à PGlite, mêmes migrations que Supabase)
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { PGlite } from '@electric-sql/pglite';

const dir = new URL('../supabase/migrations/', import.meta.url);
const db = new PGlite();
await db.exec(readFileSync(new URL('./supabase-shim.sql', import.meta.url), 'utf8'));
for (const f of readdirSync(dir).sort()) await db.exec(readFileSync(new URL(f, dir), 'utf8'));

const U = { editeur: 'e1', fisc: 'f1', gino: 'a1', nina: 'a2', luc: 'a3', paul: 'b1', marie: 'c1', autre: 'd9' };
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
const num = x => Number(x);

const ALL = "array['stock','fabrication','recolte','caisse','factures','service','dons','employes','vehicules','evenements']";
let bella, ferme, asso, gGrade = {}, item = {};

test('mise en place : éditeur, trois entreprises, leurs équipes', async () => {
  const [{ token }] = await su("insert into public.platform_claims (role, display_name) values ('editeur', 'Windy Dev') returning token");
  await one('editeur', 'select public.claim_platform_role($1)', [token]);
  const mk = async (code, name, owner, login, assoc, mods) => {
    const r = await val('editeur', `select public.create_org($1, $2, 'Test', $3, $4, $5, ${mods}, 4)`, [code, name, owner, login, assoc]);
    return r;
  };
  const b = await mk('bella-napoli', 'Bella Napoli', 'Gino Romano', 'gino.romano', false, ALL);
  const f = await mk('ferme-des-collines', 'Ferme des Collines', 'Paul Martin', 'paul.martin', false, "array['recolte','stock','factures']");
  const a = await mk('les-restos', 'Les Restos', 'Marie Dubois', 'marie.dubois', true, "array['dons','stock','evenements']");
  bella = b.org_id; ferme = f.org_id; asso = a.org_id;
  await one('gino', 'select public.accept_invitation($1)', [b.token]);
  await one('paul', 'select public.accept_invitation($1)', [f.token]);
  await one('marie', 'select public.accept_invitation($1)', [a.token]);
  for (const g of await su('select id, name from public.grades where org_id = $1', [bella])) gGrade[g.name] = g.id;
  const tNina = await val('gino', 'select public.invite_member($1, $2, $3, $4)', [bella, 'Nina Costa', 'nina.costa', gGrade['Stagiaire']]);
  await one('nina', 'select public.accept_invitation($1)', [tNina]);
  const tLuc = await val('gino', 'select public.invite_member($1, $2, $3, $4)', [bella, 'Luc Bianchi', 'luc.bianchi', gGrade['Employé']]);
  await one('luc', 'select public.accept_invitation($1)', [tLuc]);
});

test('stock : articles, entrée payée, sortie impossible au-delà du stock', async () => {
  const save = (name, unit, cost, price, bonus) => val('gino', 'select public.save_item($1, null, $2, $3, $4, $5, 5, $6, $7, $8)', [bella, name, unit, 'Cuisine', 'Réserve', cost, price, bonus]);
  item.farine = await save('Farine', 'kg', 2, null, null);
  item.mozza = await save('Mozzarella', 'boule', 1.5, null, null);
  item.pate = await save('Pâte à pizza', 'pâte', 0, null, null);
  item.marg = await save('Margherita', 'pizza', 0, 18, null);
  item.soda = await save('Soda', 'canette', 0.5, 4, null);
  await fails('gino', 'select public.save_item($1, null, $2, $3, $4, $5, 0, 0, null, null)', [bella, 'Farine', 'kg', 'Cuisine', 'Réserve'], /existe déjà/);
  assert.equal(num(await val('gino', 'select public.move_stock($1, $2, 10, $3, 30)', [bella, item.farine, 'Livraison du moulin'])), 10);
  await one('gino', 'select public.move_stock($1, $2, 6, $3, 0)', [bella, item.mozza, 'Livraison']);
  await one('gino', 'select public.move_stock($1, $2, 24, $3, 0)', [bella, item.soda, 'Livraison']);
  await fails('gino', 'select public.move_stock($1, $2, -50, $3, 0)', [bella, item.farine, 'Erreur'], /Stock insuffisant : il manque 40 kg de « Farine »/);
  const [achat] = await su("select amount from public.ledger where org_id = $1 and category = 'Achats'", [bella]);
  assert.equal(num(achat.amount), 30);
  // la stagiaire n'a pas l'accès « stock »
  await fails('nina', 'select public.move_stock($1, $2, 1, $3, 0)', [bella, item.farine, 'Essai'], /grade ne permet pas/);
});

test('fabrication : la recette consomme les ingrédients et calcule le coût de revient', async () => {
  const rPate = await val('gino', 'select public.save_recipe($1, $2, 4, 20, $3)', [bella, item.pate, JSON.stringify([{ item: item.farine, qty: 1 }])]);
  await val('gino', 'select public.save_recipe($1, $2, 1, 5, $3)', [bella, item.marg, JSON.stringify([{ item: item.pate, qty: 1 }, { item: item.mozza, qty: 1 }])]);
  await fails('gino', 'select public.save_recipe($1, $2, 1, 5, $3)', [bella, item.pate, JSON.stringify([{ item: item.pate, qty: 1 }])], /propre ingrédient/);
  assert.equal(num(await val('luc', 'select public.produce($1, 2)', [rPate])), 8);
  const rows = await su('select name, qty, cost from public.items where org_id = $1 order by name', [bella]);
  const by = Object.fromEntries(rows.map(r => [r.name, r]));
  assert.equal(num(by['Farine'].qty), 8);
  assert.equal(num(by['Pâte à pizza'].qty), 8);
  assert.equal(num(by['Pâte à pizza'].cost), 0.5);
  await fails('luc', 'select public.produce($1, 20)', [rPate], /Stock insuffisant/);
});

test('caisse : vente du stock, puis préparée à la commande, puis rupture', async () => {
  const sell = (who, lines, pay = 'carte') => one(who, 'select public.sell($1, $2, $3) r', [bella, JSON.stringify(lines), pay]);
  const t1 = (await sell('nina', [{ item: item.marg, qty: 3 }, { item: item.soda, qty: 2 }])).r;
  assert.equal(t1.num, 1); assert.equal(num(t1.total), 62);
  const st = Object.fromEntries((await su('select name, qty from public.items where org_id = $1', [bella])).map(r => [r.name, num(r.qty)]));
  assert.deepEqual([st['Pâte à pizza'], st['Mozzarella'], st['Soda']], [5, 3, 22]);
  const [v] = await su("select amount, label from public.ledger where org_id = $1 and category = 'Ventes'", [bella]);
  assert.equal(num(v.amount), 62); assert.match(v.label, /Ticket n°1 · 5 article/);
  await fails('nina', 'select public.sell($1, $2, $3)', [bella, JSON.stringify([{ item: item.marg, qty: 4 }]), 'especes'], /Stock insuffisant : il manque 1 boule de « Mozzarella »/);
  await fails('nina', 'select public.sell($1, $2, $3)', [bella, JSON.stringify([{ item: item.farine, qty: 1 }]), 'carte'], /pas à la carte/);
  // un ticket refusé ne laisse aucune trace
  assert.equal((await su('select count(*)::int n from public.sales where org_id = $1', [bella]))[0].n, 1);
  assert.equal(num((await su('select qty from public.items where id = $1', [item.mozza]))[0].qty), 3);
});

test('récolte : saisie, stock, prime au rendement, versement unique', async () => {
  const ble = await val('paul', 'select public.save_item($1, null, $2, $3, $4, $5, 0, 1, null, 0.5)', [ferme, 'Blé', 'kg', 'Champs', 'Silo']);
  assert.equal(num(await val('paul', 'select public.add_harvest($1, $2, 100, null)', [ferme, ble])), 50);
  assert.equal(num((await su('select qty from public.items where id = $1', [ble]))[0].qty), 100);
  assert.equal(num(await val('paul', 'select public.pay_harvest_bonuses($1)', [ferme])), 50);
  await fails('paul', 'select public.pay_harvest_bonuses($1)', [ferme], /Aucune prime/);
  await fails('gino', 'select public.add_harvest($1, $2, 1, null)', [bella, item.farine], /ne se récolte pas/);
});

test('factures : entre deux entreprises Windy Desk, payée dans les deux comptabilités', async () => {
  const n = await val('paul', 'select public.create_invoice($1, null, $2, $3, 7, false)', [ferme, 'bella-napoli', JSON.stringify([{ label: 'Farine T55', qty: 20, price: 3 }])]);
  assert.equal(n, 'F-0001');
  const [inv] = await su('select id, client, total from public.invoices where org_id = $1', [ferme]);
  assert.equal(inv.client, 'Bella Napoli'); assert.equal(num(inv.total), 60);
  // le patron de la pizzeria voit la facture reçue, la stagiaire non
  assert.equal((await as('gino', 'select id from public.invoices')).length, 1);
  assert.equal((await as('nina', 'select id from public.invoices')).length, 0);
  await one('paul', 'select public.pay_invoice($1)', [inv.id]);
  await fails('paul', 'select public.pay_invoice($1)', [inv.id], /plus en attente/);
  assert.equal(num((await su("select amount from public.ledger where org_id = $1 and category = 'Factures'", [ferme]))[0].amount), 60);
  const [achat] = await su("select amount, label from public.ledger where org_id = $1 and label like 'Facture F-0001%'", [bella]);
  assert.equal(num(achat.amount), 60); assert.match(achat.label, /Ferme des Collines/);
  await fails('paul', 'select public.create_invoice($1, null, $2, $3, 7, false)', [ferme, 'ferme-des-collines', '[]'], /elle-même/);
});

test('service et paie : prise de service, heures, salaires payés une fois', async () => {
  assert.equal(await val('luc', 'select public.toggle_duty($1)', [bella]), true);
  assert.equal(await val('luc', 'select public.toggle_duty($1)', [bella]), false);
  await su("update public.shifts set started_at = ended_at - interval '2 hours' where member_id = (select id from public.members where login = 'luc.bianchi')");
  await fails('luc', 'select public.set_rate($1, 50)', [(await su("select id from public.members where login = 'luc.bianchi'"))[0].id], /grade ne permet pas/);
  await one('gino', 'select public.set_rate($1, 50)', [(await su("select id from public.members where login = 'luc.bianchi'"))[0].id]);
  assert.equal(num(await val('gino', 'select public.pay_salaries($1)', [bella])), 100);
  await fails('gino', 'select public.pay_salaries($1)', [bella], /Rien à payer/);
});

test('dons : argent en comptabilité, nature en stock, distribution', async () => {
  const pates = await val('marie', 'select public.save_item($1, null, $2, $3, $4, $5, 0, 0, null, null)', [asso, 'Pâtes', 'paquet', 'Épicerie', 'Réserve']);
  await one('marie', 'select public.add_donation($1, $2, 250, null, null, null)', [asso, 'Garage Benny']);
  await one('marie', 'select public.add_donation($1, $2, null, $3, 40, $4)', [asso, '', pates, 'Collecte']);
  await one('marie', 'select public.distribute($1, $2, $3, 15, null)', [asso, 'Refuge du port', pates]);
  assert.equal(num((await su('select qty from public.items where id = $1', [pates]))[0].qty), 25);
  assert.equal(num((await su("select amount from public.ledger where org_id = $1 and category = 'Dons'", [asso]))[0].amount), 250);
  assert.equal((await su("select donor from public.donations where qty is not null"))[0].donor, 'Anonyme');
  await fails('marie', 'select public.distribute($1, $2, $3, 100, null)', [asso, 'Refuge', pates], /Stock insuffisant/);
});

test('employés : fiche, avertissement visible par l’intéressé seulement, départ', async () => {
  const luc = (await su("select id from public.members where login = 'luc.bianchi'"))[0].id;
  const nina = (await su("select id from public.members where login = 'nina.costa'"))[0].id;
  await one('gino', "select public.save_profile($1, '555-0142', 'CDD', array['B', 'C'], current_date - 10)", [luc]);
  await one('gino', 'select public.add_warning($1, $2)', [luc, 'Retard de trois heures au service']);
  await one('gino', 'select public.add_staff_note($1, $2)', [luc, 'Très bon en cuisine']);
  assert.equal((await as('luc', 'select id from public.warnings')).length, 1);
  assert.equal((await as('nina', 'select id from public.warnings')).length, 0);
  assert.equal((await as('luc', 'select id from public.staff_notes')).length, 0);
  await fails('luc', 'select public.add_warning($1, $2)', [nina, 'Essai de motif'], /grade ne permet pas/);
  await fails('gino', 'select public.dismiss_member($1, $2)', [(await su("select id from public.members where login = 'gino.romano'"))[0].id, 'Test'], /propre compte/);
  await one('gino', 'select public.dismiss_member($1, $2)', [nina, 'Absences répétées']);
  const [n] = await su('select active, left_reason from public.members where id = $1', [nina]);
  assert.deepEqual(n, { active: false, left_reason: 'Absences répétées' });
  await fails('nina', 'select public.sell($1, $2, $3)', [bella, JSON.stringify([{ item: item.soda, qty: 1 }]), 'carte'], /Accès refusé/);
});

test('véhicules : sortie, retour abîmé, réparation payée', async () => {
  const v = await val('luc', 'select public.save_vehicle($1, null, $2, $3, $4, current_date + 3)', [bella, 'Camionnette Rumpo', 'bn 201', 'Livraison']);
  await one('luc', 'select public.take_vehicle($1, null, $2)', [v, 'Livraison au port']);
  await fails('luc', 'select public.take_vehicle($1, null, null)', [v], /pas au garage/);
  assert.equal(await val('luc', 'select public.return_vehicle($1, 40, 20, 35, $2)', [v, 'Accrochage']), 'reparation');
  await one('gino', "select public.service_vehicle($1, 'reparation', 960, null)", [v]);
  const [row] = await su('select status, condition, km, plate from public.vehicles where id = $1', [v]);
  assert.deepEqual(row, { status: 'garage', condition: 100, km: 35, plate: 'BN 201' });
  assert.equal(num((await su("select amount from public.ledger where org_id = $1 and category = 'Entretien'", [bella]))[0].amount), 960);
  await fails('paul', "select public.save_vehicle($1, null, 'Tracteur', 'TR 1', null, null)", [ferme], /n’est pas inclus/);
});

test('événements : les ventes pendant l’événement s’y rattachent, bilan et clôture', async () => {
  const ev = await val('gino', "select public.save_event($1, null, $2, $3, 'ventes', now() - interval '1 hour', now() + interval '2 hours', 500, 200, null, array[(select id from public.members where login = 'luc.bianchi')], $4, array['Monter la scène', 'Sono'])",
    [bella, 'Soirée karaoké', 'Bella Napoli', JSON.stringify([{ item: item.soda, qty: 10 }])]);
  const t = (await one('luc', 'select public.sell($1, $2, $3) r', [bella, JSON.stringify([{ item: item.soda, qty: 5 }]), 'especes'])).r;
  assert.equal(t.event, ev);
  await one('gino', 'select public.event_spend($1, $2, 80)', [ev, 'Location de la sono']);
  const book = await val('luc', 'select public.event_book($1)', [ev]);
  assert.equal(num(book.income), 20); assert.equal(num(book.spent), 80); assert.equal(book.tickets, 1);
  const [task] = await as('luc', 'select id from public.event_tasks order by id limit 1');
  await one('luc', 'select public.event_toggle_task($1, true)', [task.id]);
  await one('gino', 'select public.close_event($1)', [ev]);
  const after = (await one('luc', 'select public.sell($1, $2, $3) r', [bella, JSON.stringify([{ item: item.soda, qty: 1 }]), 'carte'])).r;
  assert.equal(after.event, null);
  await fails('autre', 'select public.event_book($1)', [ev], /inconnu/);
});

test('lecture : les autres ne voient rien, le fisc voit le stock et les ventes', async () => {
  assert.equal((await as('paul', 'select id from public.items where org_id = $1', [bella])).length, 0);
  assert.equal((await as('autre', 'select id from public.sales')).length, 0);
  const [{ token }] = await su("insert into public.platform_claims (role, display_name) values ('fisc', 'Inspecteur') returning token");
  await one('fisc', 'select public.claim_platform_role($1)', [token]);
  assert.ok((await as('fisc', 'select id from public.items where org_id = $1', [bella])).length >= 5);
  assert.ok((await as('fisc', 'select id from public.sales where org_id = $1', [bella])).length >= 2);
  await fails('fisc', 'select public.sell($1, $2, $3)', [bella, JSON.stringify([{ item: item.soda, qty: 1 }]), 'carte'], /Accès refusé/);
  await fails(null, 'select public.toggle_duty($1)', [bella], /permission denied/);
});
