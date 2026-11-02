// Le client Supabase (ou, pour les tests et la démo hors ligne, une imitation en mémoire).
const cfg = window.COMPTOIR_CONFIG || {};
export const sb = cfg.fake
  ? (await import('./fake.js')).createFake()
  : window.supabase.createClient(cfg.supabaseUrl, cfg.supabaseKey, { auth: { flowType: 'pkce', persistSession: true, autoRefreshToken: true, detectSessionInUrl: true } });

/** Appelle une fonction de la base ; le message d'erreur est celui écrit en SQL, déjà lisible. */
export async function rpc(name, args) {
  const { data, error } = await sb.rpc(name, args || {});
  if (error) throw new Error(friendly(error));
  return data;
}
/** Lit une table (les règles de sécurité de la base filtrent ce qu'on a le droit de voir). */
export async function rows(table, build) {
  let q = sb.from(table).select('*');
  if (build) q = build(q);
  const { data, error } = await q;
  if (error) throw new Error(friendly(error));
  return data || [];
}
function friendly(e) {
  const m = (e && (e.message || e.error_description)) || 'Erreur inconnue.';
  if (/Failed to fetch|NetworkError/i.test(m)) return 'Le serveur ne répond pas. Vérifie ta connexion.';
  if (/JWT expired/i.test(m)) return 'Ta session a expiré : reconnecte-toi.';
  return m;
}
/** Adresse interne d'un compte à identifiant : jamais affichée, jamais utilisée pour écrire. */
export const emailFor = (space, login) => login.toLowerCase() + '.' + space.toLowerCase() + '@' + (cfg.passwordDomain || 'comptes.windydev.fr');
