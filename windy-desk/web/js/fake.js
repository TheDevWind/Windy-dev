// Imitation du client Supabase, pour le banc d'essai seulement (tests/site.e2e.mjs).
// Chaque appel part vers window.__backend, que le banc d'essai fournit : il l'exécute dans la vraie base
// (PGlite, mêmes migrations, mêmes règles de sécurité). Jamais chargé en production (config.js n'a pas « fake »).
const KEY = 'windydesk-test-session';

export function createFake() {
  const subs = new Set();
  let session = null;
  try { session = JSON.parse(sessionStorage.getItem(KEY)); } catch { session = null; }

  const save = (s, event) => {
    session = s;
    try { if (s) sessionStorage.setItem(KEY, JSON.stringify(s)); else sessionStorage.removeItem(KEY); } catch { /* navigation privée */ }
    subs.forEach(cb => cb(event, s));
  };
  const call = async req => JSON.parse(await window.__backend(JSON.stringify({ ...req, uid: session ? session.user.id : null })));
  const signed = async (op, body) => {
    const r = await call({ op, ...body });
    if (r.error) return { data: { user: null, session: null }, error: r.error };
    save(r.data.session, 'SIGNED_IN');
    return { data: r.data, error: null };
  };

  const from = table => {
    const q = { table, filters: [], order: null, limit: null, write: null, values: null };
    const b = {
      select() { return b; },
      eq(col, v) { q.filters.push([col, 'eq', v]); return b; },
      is(col, v) { q.filters.push([col, 'is', v]); return b; },
      gte(col, v) { q.filters.push([col, 'gte', v]); return b; },
      order(col, o) { q.order = [col, !o || o.ascending !== false]; return b; },
      limit(n) { q.limit = n; return b; },
      insert(v) { q.write = 'insert'; q.values = v; return b; },
      update(v) { q.write = 'update'; q.values = v; return b; },
      then(ok, ko) { return call({ op: 'from', q }).then(ok, ko); },
    };
    return b;
  };

  return {
    auth: {
      getSession: async () => ({ data: { session }, error: null }),
      onAuthStateChange(cb) {
        subs.add(cb);
        setTimeout(() => cb('INITIAL_SESSION', session));
        return { data: { subscription: { unsubscribe: () => subs.delete(cb) } } };
      },
      signUp: ({ email, password }) => signed('signUp', { email, password }),
      signInWithPassword: ({ email, password }) => signed('signIn', { email, password }),
      // Discord imité : l'identité est celle que le banc d'essai a posée dans sessionStorage
      async signInWithOAuth({ options }) {
        const r = await signed('discord', { name: sessionStorage.getItem('windydesk-test-discord') || 'joueur' });
        if (!r.error) location.assign(options.redirectTo);
        return r;
      },
      async signOut() { save(null, 'SIGNED_OUT'); return { error: null }; },
    },
    rpc: (name, args) => call({ op: 'rpc', name, args }),
    from,
    // stockage imité : les fichiers vont en mémoire côté banc d'essai, servis sous /__fake-storage/ ; les règles RLS de storage.objects s'appliquent
    storage: {
      from: bucket => ({
        async upload(path, file, opts) {
          const bytes = new Uint8Array(await file.arrayBuffer());
          let bin = '';
          for (let i = 0; i < bytes.length; i += 0x8000) bin += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
          const r = await call({ op: 'upload', bucket, path, type: (opts && opts.contentType) || file.type, data: btoa(bin) });
          return r.error ? { data: null, error: r.error } : { data: { path }, error: null };
        },
        createSignedUrls: (paths) => call({ op: 'signed', bucket, paths }),
        getPublicUrl: path => ({ data: { publicUrl: '/__fake-storage/' + bucket + '/' + path } }),
      }),
    },
  };
}
