// Le client Supabase (ou, pour les tests et la démo hors ligne, une imitation en mémoire).
const cfg = window.WINDY_DESK_CONFIG || {};
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
// ---------------------------------------------------------------- fichiers (Supabase Storage)
// « mdt » est privé : on lit par des liens signés valables une heure, gardés 50 minutes. « avis » est public (affiches).
const signedCache = new Map();
export async function signedUrls(paths) {
  const now = Date.now(), out = {}, missing = [];
  for (const p of new Set(paths.filter(Boolean))) { const c = signedCache.get(p); if (c && c.until > now) out[p] = c.url; else missing.push(p); }
  if (missing.length) {
    const { data, error } = await sb.storage.from('mdt').createSignedUrls(missing, 3600);
    if (!error) for (const d of data || []) if (d.signedUrl) { signedCache.set(d.path, { url: d.signedUrl, until: now + 50 * 60e3 }); out[d.path] = d.signedUrl; }
  }
  return out;
}
export async function upload(bucket, path, file) {
  const { error } = await sb.storage.from(bucket).upload(path, file, { contentType: file.type || 'application/octet-stream', upsert: false, cacheControl: '3600' });
  if (error) throw new Error(/mime|type/i.test(error.message) ? 'Type de fichier refusé.' : /size|large|exceed/i.test(error.message) ? 'Fichier trop lourd.' : /security|policy|unauthorized/i.test(error.message) ? 'Dépôt refusé : ton grade ne le permet pas.' : friendly(error));
  return path;
}
export const publicUrl = path => sb.storage.from('avis').getPublicUrl(path).data.publicUrl;
function friendly(e) {
  const m = (e && (e.message || e.error_description)) || 'Erreur inconnue.';
  if (/Failed to fetch|NetworkError/i.test(m)) return 'Le serveur ne répond pas. Vérifie ta connexion.';
  if (/JWT expired/i.test(m)) return 'Ta session a expiré : reconnecte-toi.';
  return m;
}
/** Adresse interne d'un compte à identifiant : jamais affichée, jamais utilisée pour écrire. */
export const emailFor = (space, login) => login.toLowerCase() + '.' + space.toLowerCase() + '@' + (cfg.passwordDomain || 'comptes.windydev.fr');
