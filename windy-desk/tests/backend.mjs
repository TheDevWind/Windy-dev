// La vraie base pour le banc d'essai : Postgres 16 en mémoire (PGlite), les migrations du dossier supabase,
// et une imitation de ce que fait Supabase (comptes, rôle « authenticated », appel des fonctions, lecture des tables).
// Le navigateur y accède par window.__backend (voir web/js/fake.js).
import { readFileSync, readdirSync } from 'node:fs';
import { randomUUID } from 'node:crypto';
import { PGlite } from '@electric-sql/pglite';

const NAME = /^[a-z_]+$/;

export async function createBackend() {
  const db = new PGlite();
  const dir = new URL('../supabase/migrations/', import.meta.url);
  await db.exec(readFileSync(new URL('./supabase-shim.sql', import.meta.url), 'utf8'));
  for (const f of readdirSync(dir).sort()) await db.exec(readFileSync(new URL(f, dir), 'utf8'));

  const accounts = new Map();   // e-mail → { id, password }
  let chain = Promise.resolve();
  const serial = fn => { const p = chain.then(fn); chain = p.catch(() => {}); return p; };

  /** Exécute une requête comme le ferait l'API de Supabase pour cette personne (ou un visiteur). */
  const as = (uid, sql, params = []) => serial(async () => {
    await db.exec('reset role');
    await db.query("select set_config('request.jwt.claim.sub', $1, false)", [uid || '']);
    await db.exec('set role ' + (uid ? 'authenticated' : 'anon'));
    try { return (await db.query(sql, params)).rows; } finally { await db.exec('reset role'); }
  });

  async function account(email, password, create) {
    let a = accounts.get(email);
    if (create) {
      if (a) return { error: { message: 'User already registered' } };
      a = { id: randomUUID(), password };
      accounts.set(email, a);
      await serial(() => db.query('insert into auth.users (id, email) values ($1, $2)', [a.id, email]));
    } else if (!a || a.password !== password) return { error: { message: 'Invalid login credentials' } };
    const user = { id: a.id, email };
    return { data: { user, session: { access_token: 'test', user } } };
  }

  // PGlite convertit lui-même les valeurs JS (booléens, nombres, tableaux) selon le type attendu, comme PostgREST
  const value = v => (v === undefined ? null : v);

  async function rpc(uid, name, args) {
    if (!NAME.test(name)) throw new Error('Fonction inconnue.');
    const [fn] = await serial(async () => (await db.query(
      "select p.proargnames as names, array(select format_type(t, null) from unnest(p.proargtypes) t) as types from pg_proc p where p.proname = $1 and p.pronamespace = 'public'::regnamespace", [name])).rows);
    if (!fn) throw new Error('Could not find the function public.' + name);
    const names = fn.names || [];
    const list = names.map((n, i) => n + ' => $' + (i + 1) + '::' + fn.types[i]);
    const [row] = await as(uid, 'select to_json(public.' + name + '(' + list.join(', ') + ')) as r', names.map(n => value(args[n])));
    return row.r;
  }

  async function from(uid, q) {
    if (!NAME.test(q.table)) throw new Error('Table inconnue.');
    const params = [];
    const where = q.filters.map(([col, op, v]) => {
      if (!NAME.test(col)) throw new Error('Colonne inconnue.');
      if (op === 'is') return col + ' is null';
      params.push(value(v));
      return col + (op === 'gte' ? ' >= $' : ' = $') + params.length;
    });
    const cond = where.length ? ' where ' + where.join(' and ') : '';
    let sql;
    if (q.write === 'insert' || q.write === 'update') {
      const cols = Object.keys(q.values);
      if (!cols.every(c => NAME.test(c))) throw new Error('Colonne inconnue.');
      const start = params.length;
      cols.forEach(c => params.push(value(q.values[c])));
      sql = q.write === 'insert'
        ? 'insert into public.' + q.table + ' (' + cols.join(', ') + ') values (' + cols.map((c, i) => '$' + (start + i + 1)).join(', ') + ') returning *'
        : 'update public.' + q.table + ' set ' + cols.map((c, i) => c + ' = $' + (start + i + 1)).join(', ') + cond + ' returning *';
      sql = 'with t as (' + sql + ") select coalesce(json_agg(t), '[]'::json) as r from t";
    } else {
      const order = q.order && NAME.test(q.order[0]) ? ' order by ' + q.order[0] + (q.order[1] ? ' asc' : ' desc') : '';
      const limit = q.limit ? ' limit ' + Number(q.limit) : '';
      sql = "select coalesce(json_agg(t), '[]'::json) as r from (select * from public." + q.table + cond + order + limit + ') t';
    }
    return (await as(uid, sql, params))[0].r;
  }

  /** Point d'entrée unique : une requête JSON du navigateur, une réponse JSON { data, error }. */
  async function handle(text) {
    const req = JSON.parse(text);
    try {
      if (req.op === 'signUp') return JSON.stringify(await account(req.email, req.password, true));
      if (req.op === 'signIn') return JSON.stringify(await account(req.email, req.password, false));
      if (req.op === 'discord') {
        const email = req.name + '@discord.test';
        const r = accounts.has(email) ? await account(email, 'discord', false) : await account(email, 'discord', true);
        return JSON.stringify(r);
      }
      if (req.op === 'rpc') return JSON.stringify({ data: await rpc(req.uid, req.name, req.args || {}), error: null });
      if (req.op === 'from') return JSON.stringify({ data: await from(req.uid, req.q), error: null });
      throw new Error('Opération inconnue : ' + req.op);
    } catch (e) {
      return JSON.stringify({ data: null, error: { message: e.message } });
    }
  }

  /** Lien à usage unique pour l'éditeur ou le fisc (en production : une ligne insérée depuis le tableau de bord Supabase). */
  const claim = async (role, name) => (await serial(() => db.query('insert into public.platform_claims (role, display_name) values ($1, $2) returning token', [role, name]))).rows[0].token;
  const sql = (text, params) => serial(async () => (await db.query(text, params)).rows);

  return { handle, claim, sql, db };
}
