// ================================================================== Windy Desk · le site
// Adresses : /  ·  /connexion  ·  /connexion/plateforme  ·  /reclamer/<lien>  ·  /<entreprise>[/compta|/equipe|/reglages]
//            /<entreprise>/activer/<lien>  ·  /fisc[/bareme|/<entreprise>]  ·  /editeur[/modules|/devis|/devis/<id>]
import { $app, $tip, S, calm, arriving, h, ico, modal, toast, run, table, themeSwitch, liveDot, pill, strip, fchips, dailyBars, money, money2, initials,
  date, ago, clock, dayLabel, copyText, hideTip, slug, BRAND, butterfly, lockup, branch } from './ui.js';
import { MODULES, moduleOf, PACKS, CAPS, CATEGORIES, DAYS, quoteTotal } from './catalog.js';
import { renderDoc, exportModal, quoteDoc } from './doc.js';
import { landing } from './landing.js';
import { sb, rpc, rows, emailFor } from './sb.js';

const ME = { session: null, spaces: null, quiet: false };
const DAY = 864e5;
const RESERVED = ['connexion', 'reclamer', 'fisc', 'editeur', 'api', 'assets'];

// ---------------------------------------------------------------- navigation
export function go(path, replace) {
  if (location.pathname + location.search === path) return route();
  history[replace ? 'replaceState' : 'pushState'](null, '', path);
  route();
}
document.addEventListener('click', ev => {
  const a = ev.target.closest && ev.target.closest('a[href^="/"]');
  if (!a || a.target || a.hasAttribute('download') || ev.metaKey || ev.ctrlKey || ev.shiftKey || ev.button !== 0) return;
  ev.preventDefault();
  document.querySelectorAll('.modal-bg, .menu').forEach(m => m.remove());
  go(a.getAttribute('href'));
});
window.addEventListener('popstate', () => { document.querySelectorAll('.modal-bg, .menu').forEach(m => m.remove()); route(); });

async function spaces(force) {
  if (!ME.session) return null;
  if (!ME.spaces || force) ME.spaces = await rpc('my_spaces');
  return ME.spaces;
}
function fail(e) { toast(e.message || String(e), true); }
function message(title, text, ...actions) {
  S.shown = null;
  $app.replaceChildren(h('div', { class: 'gate' }, themeSwitch(), h('div', { class: 'gate-card' }, butterfly('gate-fly'), h('h1', {}, title), h('p', {}, text), ...actions)));
}

async function route() {
  clearInterval(S.timer);
  hideTip();
  const parts = location.pathname.split('/').filter(Boolean).map(decodeURIComponent);
  const [a, b, c] = parts;
  try {
    if (!a) return landing();
    if (a === 'connexion') return b === 'plateforme' ? platformLogin() : loginHome();
    if (a === 'reclamer') return claimPage(b);
    if (a === 'fisc') return await platformSpace('fisc', b);
    if (a === 'editeur') return await platformSpace('editeur', b, c);
    if (RESERVED.includes(a)) return message('Page introuvable', 'Cette adresse n’existe pas.', h('a', { class: 'btn', href: '/' }, 'Retour à l’accueil'));
    if (b === 'activer') return await activatePage(a, c);
    return await orgSpace(a, b || 'accueil');
  } catch (e) {
    message('Quelque chose s’est mal passé', e.message || String(e), h('a', { class: 'btn', href: location.pathname }, 'Réessayer'));
  }
}

// ---------------------------------------------------------------- cadre des espaces connectés
function navLink(n, current) {
  return h('a', { class: 'nav' + (n.page === current ? ' on' : ''), href: n.href, title: n.label, 'aria-current': n.page === current ? 'page' : null }, h('span', { class: 'ic' }, ico(n.icon)), h('span', { class: 'lbl' }, n.short || n.label));
}
function shell(nav, current, ...content) {
  const side = h('nav', { class: 'side', 'aria-label': 'Menu' }, h('a', { class: 'brand', href: nav[0].href, title: BRAND, 'aria-label': BRAND }, butterfly('side-fly', 70)),
    h('div', { class: 'navs' }, nav.map(n => navLink(n, current))), themeSwitch());
  const bar = h('nav', { class: 'tabbar', 'aria-label': 'Menu' }, nav.slice(0, 4).map(n => navLink(n, current)),
    nav.length > 4 ? h('button', { class: 'nav' + (nav.slice(4).some(n => n.page === current) ? ' on' : ''), type: 'button', onclick: () => {
      let m;
      m = modal('Menu', h('div', { class: 'sheet' }, nav.slice(4).map(n => h('a', { class: n.page === current ? 'on' : null, href: n.href, onclick: () => m.close() }, ico(n.icon), n.label)),
        h('hr', {}), h('div', { class: 'mrow' }, 'Mode sombre', themeSwitch()), h('button', { type: 'button', onclick: () => { m.close(); signOut(); } }, ico('sortie'), 'Se déconnecter')));
    } }, h('span', { class: 'ic' }, ico('plus')), h('span', { class: 'lbl' }, 'Plus')) : null);
  const fresh = arriving();
  S.shown = location.pathname;
  $app.replaceChildren(h('div', { class: 'shell' + (fresh ? ' enter' : '') }, side, h('main', { class: 'main' }, ...content), bar));
  if (fresh) window.scrollTo(0, 0);
}
function head(title, sub, who, ...right) {
  const tools = right.flat(Infinity).filter(Boolean);
  return h('header', { class: 'head' }, h('div', { class: 'titles' }, h('h1', {}, title), sub ? h('div', { class: 'sub' }, sub) : null), tools.length ? h('div', { class: 'right' }, ...tools) : null, userBox(who));
}
function userBox(who) {
  const btn = h('button', { class: 'who', type: 'button', 'aria-haspopup': 'true', 'aria-expanded': 'false', 'aria-label': 'Mon compte' }, h('span', { class: 'ava' }, initials(who.name)),
    h('span', { class: 'id' }, h('b', {}, who.name), h('span', {}, who.role)), ico('chevron', 16));
  const box = h('div', { class: 'user' }, btn);
  let menu = null;
  const close = () => { if (menu) { menu.remove(); menu = null; btn.setAttribute('aria-expanded', 'false'); document.removeEventListener('click', close); } };
  btn.addEventListener('click', ev => {
    ev.stopPropagation();
    if (menu) return close();
    const sp = ME.spaces || { orgs: [] };
    const others = [sp.role === 'fisc' ? ['/fisc', 'Espace du fisc', 'balance'] : null, sp.role === 'editeur' ? ['/editeur', 'Espace éditeur', 'grille'] : null,
      ...sp.orgs.map(o => ['/' + o.code, o.name, 'batiment'])].filter(Boolean).filter(([p]) => !location.pathname.startsWith(p));
    menu = h('div', { class: 'menu', onclick: e2 => e2.stopPropagation() }, h('div', { class: 'mhead' }, h('span', { class: 'ava' }, initials(who.name)), h('span', { class: 'mtx' }, h('b', {}, who.name), h('small', {}, who.role))),
      others.length ? [h('div', { class: 'mlabel' }, 'Mes autres espaces'), others.map(([p, l, i]) => h('a', { href: p, onclick: close }, ico(i, 18), l))] : null,
      h('hr', {}), h('div', { class: 'mrow' }, 'Mode sombre', themeSwitch()), h('button', { type: 'button', onclick: () => { close(); signOut(); } }, ico('sortie', 18), 'Se déconnecter'));
    box.append(menu);
    btn.setAttribute('aria-expanded', 'true');
    document.addEventListener('click', close);
  });
  return box;
}
async function signOut() {
  const back = location.pathname.split('/').filter(Boolean)[0];
  ME.quiet = true;
  try { await sb.auth.signOut(); } finally { ME.quiet = false; }
  ME.session = null; ME.spaces = null;
  go(back && !RESERVED.includes(back) ? '/' + back : '/connexion', true);
}
function linkModal(title, url, who) {
  const input = h('input', { type: 'text', value: url, readonly: true, style: 'width:100%', 'aria-label': 'Lien' });
  const copy = h('button', { class: 'btn primary' }, ico('copier', 18), 'Copier le lien');
  copy.addEventListener('click', () => { input.select(); copyText(url, 'Lien copié : envoie-le à ' + who + ' en message privé.'); });
  const m = modal(title, h('p', { class: 'muted', style: 'margin:0' }, 'Envoie ce lien à ' + who + ' en message privé. Il est valable 48 heures et ne sert qu’une fois : la personne y choisit sa façon de se connecter (Discord ou mot de passe).'),
    input, h('div', { class: 'foot' }, h('button', { class: 'btn', onclick: () => m.close() }, 'Fermer'), copy));
  input.select();
}
const activationUrl = (code, token) => location.origin + '/' + code + '/activer/' + token;

// ---------------------------------------------------------------- connexion
function authFrame(side, ...form) {
  S.shown = null;
  $app.replaceChildren(h('div', { class: 'auth' }, h('aside', { class: 'auth-side' }, branch('a-leaf one'), branch('a-leaf two'), side), h('main', { class: 'auth-main' },
    h('div', { class: 'auth-top' }, h('a', { class: 'auth-back', href: '/' }, ico('fleche', 16), 'Retour au site'), themeSwitch()), h('div', { class: 'auth-form' }, ...form))));
  window.scrollTo(0, 0);
}
function discordButton(redirectPath, label) {
  return h('button', { class: 'btn wide', type: 'button', onclick: async ev => {
    await run(ev.currentTarget, async () => {
      const { error } = await sb.auth.signInWithOAuth({ provider: 'discord', options: { redirectTo: location.origin + redirectPath } });
      if (error) throw new Error(error.message);
    });
  } }, ico('discord', 18), label || 'Se connecter avec Discord');
}
function loginHome() {
  const code = h('input', { type: 'text', placeholder: 'ex. bella-napoli', autocapitalize: 'none', spellcheck: 'false', 'aria-label': 'Adresse de ton entreprise' });
  const next = async b => {
    const v = slug(code.value);
    if (v.length < 3) return toast('Entre l’adresse de ton entreprise : elle est dans le lien envoyé par ton patron.', true);
    await run(b, async () => { const o = await rpc('org_public', { p_code: v }); if (!o) throw new Error('Aucune entreprise à l’adresse /' + v + '.'); go('/' + v); });
  };
  const btn = h('button', { class: 'btn primary', onclick: () => next(btn) }, 'Continuer');
  code.addEventListener('keydown', ev => { if (ev.key === 'Enter') next(btn); });
  authFrame([lockup('auth-lock'), h('h2', {}, 'Chaque entreprise a son espace.'), h('p', {}, 'Son adresse, ses comptes, ses grades. Ton patron t’envoie un lien d’activation : tu choisis Discord ou un mot de passe.'),
      h('ul', { class: 'auth-points' }, ['Un compte par employé', 'Des accès selon le grade', 'Une adresse propre à l’entreprise'].map(t => h('li', {}, ico('coche', 18), t)))],
    h('h1', {}, 'Se connecter'), h('p', { class: 'muted' }, 'Entre l’adresse de ton entreprise.'),
    h('div', { class: 'codefield' }, h('span', {}, '/'), code, btn),
    h('div', { class: 'auth-pro' }, h('a', { href: '/connexion/plateforme' }, ico('balance', 18), 'Fisc et éditeur')));
}
function passwordForm(space, onDone, labels) {
  const u = h('input', { type: 'text', autocomplete: 'username', autocapitalize: 'none', spellcheck: 'false', value: (labels && labels.login) || '', readonly: !!(labels && labels.login) });
  const pw = h('input', { type: 'password', autocomplete: labels && labels.create ? 'new-password' : 'current-password' });
  const pw2 = labels && labels.create ? h('input', { type: 'password', autocomplete: 'new-password' }) : null;
  const go2 = h('button', { class: 'btn primary wide' }, labels && labels.create ? 'Créer mon accès' : 'Se connecter');
  const submit = () => run(go2, async () => {
    const login = u.value.trim().toLowerCase();
    if (!/^[a-z0-9]+([._-][a-z0-9]+)*$/.test(login)) throw new Error('Identifiant invalide : lettres, chiffres, point ou tiret.');
    if (pw.value.length < 8) throw new Error('Le mot de passe doit faire au moins 8 caractères.');
    if (pw2 && pw2.value !== pw.value) throw new Error('Les deux mots de passe sont différents.');
    const email = emailFor(space, login);
    let res;
    ME.quiet = true;   // c'est ce formulaire qui enchaîne, pas l'écouteur de session
    try {
      res = pw2 ? await sb.auth.signUp({ email, password: pw.value }) : await sb.auth.signInWithPassword({ email, password: pw.value });
      if (pw2 && res.error && /registered|exists/i.test(res.error.message)) res = await sb.auth.signInWithPassword({ email, password: pw.value });
    } finally { ME.quiet = false; }
    if (res.error) throw new Error(/Invalid login/i.test(res.error.message) ? 'Identifiant ou mot de passe incorrect.' : /confirm/i.test(res.error.message) ? 'La confirmation par e-mail est encore activée dans Supabase : l’éditeur doit la couper.' : res.error.message);
    if (!res.data.session) throw new Error('La confirmation par e-mail est encore activée dans Supabase : l’éditeur doit la couper.');
    ME.session = res.data.session; ME.spaces = null;
    await onDone();
  });
  (pw2 || pw).addEventListener('keydown', ev => { if (ev.key === 'Enter') submit(); });
  go2.addEventListener('click', submit);
  return [h('label', { class: 'f' }, 'Identifiant', u), h('label', { class: 'f' }, labels && labels.create ? 'Choisis un mot de passe (8 caractères au moins)' : 'Mot de passe', pw), pw2 ? h('label', { class: 'f' }, 'Le même, une seconde fois', pw2) : null, go2];
}
async function orgLogin(code) {
  const o = await rpc('org_public', { p_code: code });
  if (!o) return message('Entreprise introuvable', 'Aucune entreprise n’utilise l’adresse /' + code + '. Vérifie le lien envoyé par ton patron.', h('a', { class: 'btn', href: '/connexion' }, 'Chercher une autre entreprise'));
  authFrame([h('b', { class: 'mk big inv' }, o.name[0].toUpperCase()), h('h2', {}, o.name), h('p', {}, o.kind + ' · espace de l’entreprise'), h('code', { class: 'auth-url' }, '/' + o.code),
      h('ul', { class: 'auth-points' }, o.modules.slice(0, 6).map(id => h('li', {}, ico(moduleOf(id).icon, 18), moduleOf(id).name)))],
    h('h1', {}, 'Connexion'), h('p', { class: 'muted' }, 'Espace réservé à l’équipe de ' + o.name + '.'),
    o.password_login ? passwordForm(o.code, () => route()) : null,
    o.discord_login ? (o.password_login ? h('div', { class: 'auth-or' }, h('span', {}, 'ou')) : null) : null,
    o.discord_login ? discordButton('/' + o.code) : null,
    h('p', { class: 'note' }, 'Pas encore de compte ? Ton patron t’envoie un lien d’activation depuis la page Équipe.'));
}
function platformLogin() {
  authFrame([lockup('auth-lock'), h('h2', {}, 'Fisc et éditeur'), h('p', {}, 'L’espace du contrôleur du fisc (lecture seule sur toutes les entreprises) et celui de l’éditeur de ' + BRAND + '.')],
    h('h1', {}, 'Connexion'), h('p', { class: 'muted' }, 'Ton accès a été ouvert par un lien à usage unique.'),
    passwordForm('plateforme', async () => { const sp = await spaces(true); go(sp.role === 'fisc' ? '/fisc' : sp.role === 'editeur' ? '/editeur' : '/connexion'); }),
    h('div', { class: 'auth-or' }, h('span', {}, 'ou')), discordButton('/connexion/plateforme'));
  if (ME.session) spaces().then(sp => { if (sp && sp.role) go(sp.role === 'fisc' ? '/fisc' : '/editeur', true); }).catch(() => {});
}
async function claimPage(token) {
  if (!ME.session) {
    return authFrame([lockup('auth-lock'), h('h2', {}, 'Ouvrir ton accès'), h('p', {}, 'Ce lien donne un accès au fisc ou à l’éditeur. Il ne sert qu’une fois.')],
      h('h1', {}, 'Choisis ta connexion'), h('p', { class: 'muted' }, 'Crée un identifiant, ou utilise Discord.'),
      passwordForm('plateforme', () => route(), { create: true }), h('div', { class: 'auth-or' }, h('span', {}, 'ou')), discordButton('/reclamer/' + token, 'Continuer avec Discord'));
  }
  const role = await rpc('claim_platform_role', { p_token: token });
  ME.spaces = null;
  toast(role === 'fisc' ? 'Accès du fisc ouvert.' : 'Accès éditeur ouvert.');
  go(role === 'fisc' ? '/fisc' : '/editeur', true);
}
async function activatePage(code, token) {
  const inv = await rpc('invitation_info', { p_token: token || '' });
  if (!inv) return message('Lien inconnu', 'Ce lien d’activation n’existe pas. Demande un nouveau lien à ton patron.', h('a', { class: 'btn', href: '/' + code }, 'Page de connexion'));
  if (inv.used || inv.expired) return message(inv.used ? 'Lien déjà utilisé' : 'Lien expiré', 'Demande un nouveau lien à ton patron (page Équipe, « Nouveau lien »).', h('a', { class: 'btn', href: '/' + inv.org_code }, 'Page de connexion'));
  const accept = async () => { await rpc('accept_invitation', { p_token: token }); ME.spaces = null; toast('Bienvenue dans ' + inv.org_name + ' !'); go('/' + inv.org_code, true); };
  if (ME.session) {
    const b = h('button', { class: 'btn primary wide' }, 'Activer avec cette session');
    b.addEventListener('click', () => run(b, accept));
    return authFrame([h('b', { class: 'mk big inv' }, inv.org_name[0].toUpperCase()), h('h2', {}, inv.org_name), h('p', {}, 'Compte de ' + inv.member_name + ' · ' + inv.grade)],
      h('h1', {}, 'Activer le compte'), h('p', { class: 'muted' }, 'Tu es déjà connecté. Relier ce compte à ' + inv.member_name + ' ?'), b,
      h('button', { class: 'btn ghost wide', onclick: async () => { ME.quiet = true; try { await sb.auth.signOut(); } finally { ME.quiet = false; } ME.session = null; ME.spaces = null; route(); } }, 'Utiliser un autre compte'));
  }
  authFrame([h('b', { class: 'mk big inv' }, inv.org_name[0].toUpperCase()), h('h2', {}, inv.org_name), h('p', {}, 'Bienvenue, ' + inv.member_name + '. Grade : ' + inv.grade + '.'), h('code', { class: 'auth-url' }, '/' + inv.org_code)],
    h('h1', {}, 'Activer ton compte'), h('p', { class: 'muted' }, 'Choisis comment tu te connecteras à l’avenir.'),
    inv.password_login ? passwordForm(inv.org_code, accept, { create: true, login: inv.login }) : null,
    inv.discord_login && inv.password_login ? h('div', { class: 'auth-or' }, h('span', {}, 'ou')) : null,
    inv.discord_login ? discordButton(location.pathname, 'Continuer avec Discord') : null,
    h('p', { class: 'note' }, 'Ce lien ne sert qu’une fois et expire 48 heures après sa création.'));
}

// ================================================================== espace d'une entreprise
async function orgSpace(code, page) {
  if (!ME.session) return orgLogin(code);
  const sp = await spaces();
  const me = sp.orgs.find(o => o.code === code);
  if (!me) {
    const pub = await rpc('org_public', { p_code: code });
    if (!pub) return message('Entreprise introuvable', 'Aucune entreprise n’utilise l’adresse /' + code + '.', h('a', { class: 'btn', href: '/' }, 'Accueil'));
    return message('Pas d’accès à ' + pub.name, 'Ce compte n’a pas (ou plus) accès à cette entreprise. Si c’est une erreur, demande un nouveau lien à ton patron.',
      h('button', { class: 'btn', onclick: signOut }, 'Se connecter avec un autre compte'));
  }
  const can = cap => me.caps.includes(cap);
  const nav = [{ page: 'accueil', href: '/' + code, label: 'Tableau de bord', short: 'Accueil', icon: 'accueil' },
    can('compta') || can('declarer') ? { page: 'compta', href: '/' + code + '/compta', label: 'Comptabilité', short: 'Compta', icon: 'balance' } : null,
    { page: 'equipe', href: '/' + code + '/equipe', label: me.is_association ? 'Bénévoles' : 'Équipe', icon: 'joueurs' },
    can('equipe') ? { page: 'reglages', href: '/' + code + '/reglages', label: 'Réglages', icon: 'reglages' } : null].filter(Boolean);
  if (!nav.some(n => n.page === page)) return go('/' + code, true);
  const ctx = { me, can, nav, who: { name: me.member_name, role: me.grade + ' · ' + me.name } };
  if (arriving()) rpc('touch_member', { p_org: me.id }).catch(() => {});
  return ({ accueil: dashboard, compta, equipe, reglages })[page](ctx);
}
const orgHead = (ctx, title, sub, ...right) => head(title, sub, ctx.who, ...right);
const weekBook = (org, ms) => rpc('week_book', { p_org: org, p_week: new Date(ms).toISOString().slice(0, 10) });
const lastWeekMs = () => Date.now() - 7 * DAY;
const fmtLedger = e => h('b', { class: 'amt ' + (e.kind === 'in' ? 'in' : 'out') }, (e.kind === 'in' ? '+' : '−') + Number(e.amount).toLocaleString('fr-FR', { maximumFractionDigits: 2 }) + ' $');
function ledgerTable(list, short) {
  return table([['Quand', e => short ? ago(new Date(e.at).getTime()) : date(new Date(e.at).getTime()), 'num', e => e.at], ['Opération', e => h('span', {}, e.label, short ? null : h('small', { class: 'cat' }, e.category))],
    short ? null : ['Par', e => e.by_name], ['Montant', e => fmtLedger(e), 'num', e => (e.kind === 'in' ? 1 : -1) * e.amount]].filter(Boolean), list, null, 'Aucune opération pour l’instant.');
}

async function dashboard(ctx) {
  const { me, can } = ctx;
  const books = can('compta') || can('declarer');
  const [controls, wb, lb, recent] = await Promise.all([rows('controls', q => q.eq('org_id', me.id).is('closed_at', null)),
    books ? weekBook(me.id, Date.now()) : null, books ? weekBook(me.id, lastWeekMs()) : null,
    can('compta') ? rows('ledger', q => q.eq('org_id', me.id).gte('at', new Date(Date.now() - 14 * DAY).toISOString()).order('at', { ascending: false })) : []]);
  const control = controls[0];
  const per = [];
  for (let d = 13; d >= 0; d--) { const t0 = new Date(); t0.setHours(0, 0, 0, 0); const t = t0.getTime() - d * DAY; per.push({ t, today: d === 0, v: recent.filter(e => e.kind === 'in' && e.category !== 'Impôts' && new Date(e.at) >= t && new Date(e.at) < t + DAY).reduce((a, e) => a + Number(e.amount), 0) }); }
  const income = wb ? Number(wb.revenue) + (me.is_association ? Number(wb.other_in) : 0) : 0;
  shell(ctx.nav, 'accueil', orgHead(ctx, me.name, [pill(me.kind), ' Semaine ' + (wb ? wb.week : '') + ' · ' + me.grade]),
    control ? h('div', { class: 'banner bad' }, ico('alerte', 20), h('div', {}, h('b', {}, 'Contrôle fiscal en cours'), h('span', {}, control.reason + ' · ouvert ' + ago(new Date(control.opened_at).getTime()))),
      can('compta') ? h('a', { class: 'btn small', href: '/' + me.code + '/compta' }, 'Voir la comptabilité') : null) : null,
    lb && !lb.declaration && can('declarer') ? h('div', { class: 'banner warn' }, ico('balance', 20), h('div', {}, h('b', {}, 'Déclaration de la semaine ' + lb.week + ' à faire'),
      h('span', {}, lb.exempt ? 'Association exonérée : la déclaration reste obligatoire.' : 'Impôt calculé : ' + money(lb.tax) + '. À déclarer avant le ' + date(new Date(lb.deadline).getTime()) + (lb.days_late ? ' · en retard de ' + lb.days_late + ' j' : '') + '.')),
      h('a', { class: 'btn small', href: '/' + me.code + '/compta' }, 'Déclarer')) : null,
    wb ? h('section', { class: 'card' }, h('h2', {}, 'Cette semaine'), strip([[me.is_association ? 'Entrées' : 'Chiffre d’affaires', money(income)], ['Dépenses', money(wb.charges)],
      ['Résultat', money(income - wb.charges), income - wb.charges < 0 ? 'neg' : null], [wb.exempt ? 'Impôt' : 'Impôt estimé', wb.exempt ? 'Exonérée' : money(wb.tax)]])) : null,
    h('div', { class: 'dash' },
      h('div', { class: 'col' },
        can('compta') ? h('section', { class: 'card' }, h('h2', {}, me.is_association ? 'Entrées d’argent' : 'Recettes', h('span', { class: 'muted small' }, '14 derniers jours')), dailyBars(per, money)) : null,
        can('compta') ? h('section', { class: 'card' }, h('h2', {}, 'Dernières opérations', h('span', { class: 'right' }, h('a', { href: '/' + me.code + '/compta' }, 'Toute la comptabilité'))), ledgerTable(recent.slice(0, 8), true)) : null,
        !books ? h('section', { class: 'card' }, h('h2', {}, 'Bienvenue, ' + me.member_name), h('p', { class: 'muted' }, 'Ton grade (' + me.grade + ') te donne ces accès :'),
          h('ul', { class: 'l-points' }, CAPS.filter(([k]) => me.caps.includes(k)).map(([, l]) => h('li', {}, ico('coche', 18), l)))) : null),
      h('div', { class: 'col aside' },
        h('section', { class: 'card' }, h('h2', {}, 'Modules de l’offre'), me.modules.length ? h('ul', { class: 'people' }, me.modules.map(id => h('li', {}, h('span', { class: 'ev-ic' }, ico(moduleOf(id).icon, 16)),
          h('span', {}, h('b', {}, moduleOf(id).name))))) : h('div', { class: 'calm' }, 'Aucun module : le socle seulement.'),
          h('p', { class: 'note', style: 'margin-top:12px' }, me.modules.length ? 'Ces modules arrivent avec les prochaines versions de ' + BRAND + ' et s’ajouteront au menu. Le socle (comptabilité, déclarations, équipe, grades, journal) est déjà en service.' : 'Le socle (comptabilité, déclarations, équipe, grades, journal) est en service.')),
        h('section', { class: 'card' }, h('h2', {}, 'Licence'), h('p', { class: 'muted', style: 'margin:0' }, new Date(me.licence_until).getTime() < Date.now() ? 'Expirée le ' + date(new Date(me.licence_until).getTime()) + '.' : 'Valable jusqu’au ' + date(new Date(me.licence_until).getTime()) + '.')))));
}

// ---------------------------------------------------------------- comptabilité et déclaration
function entryModal(ctx, after) {
  let k = 'out';
  const seg = h('div', { class: 'seg full', role: 'group', 'aria-label': 'Sens' });
  const cat = h('select', {});
  const paint = () => { seg.replaceChildren(...[['in', 'Entrée'], ['out', 'Sortie']].map(([v, l]) => h('button', { type: 'button', class: k === v ? 'on' : null, 'aria-pressed': String(k === v), onclick: () => { k = v; paint(); } }, l)));
    cat.replaceChildren(...CATEGORIES[k].map(c => h('option', {}, c))); };
  paint();
  const label = h('input', { type: 'text', maxlength: 140, placeholder: 'Ex. Livraison de farine, Ferme des Collines' }), amount = h('input', { type: 'number', min: 0.01, step: 0.01, value: 100, class: 'short' });
  const ok = h('button', { class: 'btn primary' }, 'Enregistrer');
  const m = modal('Nouvelle opération', seg, h('div', { class: 'row' }, h('label', { class: 'f grow' }, 'Catégorie', cat), h('label', { class: 'f' }, 'Montant ($)', amount)), h('label', { class: 'f' }, 'Libellé', label),
    h('p', { class: 'note' }, 'Une opération enregistrée ne se modifie plus et ne se supprime pas : en cas d’erreur, enregistre l’opération inverse.'),
    h('div', { class: 'foot' }, h('button', { class: 'btn', onclick: () => m.close() }, 'Annuler'), ok));
  ok.addEventListener('click', () => run(ok, async () => {
    if (label.value.trim().length < 3) throw new Error('Écris un libellé : le fisc doit pouvoir comprendre l’opération.');
    await rpc('add_entry', { p_org: ctx.me.id, p_kind: k, p_category: cat.value, p_label: label.value.trim(), p_amount: Number(amount.value) });
    m.close(); after(); return { message: 'Opération enregistrée.' };
  }));
}
async function compta(ctx) {
  const { me, can } = ctx;
  const [wb, lb, rules, decls, ledger] = await Promise.all([weekBook(me.id, Date.now()), weekBook(me.id, lastWeekMs()), rows('tax_rules'),
    can('compta') ? rows('declarations', q => q.eq('org_id', me.id).order('week_start', { ascending: false })) : [],
    can('compta') ? rows('ledger', q => q.eq('org_id', me.id).order('at', { ascending: false }).limit(300)) : []]);
  const R = rules[0];
  const refresh = () => compta(ctx).catch(fail);
  const f = S.filters.ledger || 'tout';
  const income = Number(wb.revenue) + (me.is_association ? Number(wb.other_in) : 0);
  // déclaration de la semaine passée : préremplie avec la comptabilité, modifiable (le fisc compare ensuite)
  let declCard;
  if (lb.declaration) {
    const d = lb.declaration;
    declCard = h('section', { class: 'card decl' }, h('h2', {}, 'Déclaration · semaine ' + lb.week, pill('Déclarée le ' + date(new Date(d.declared_at).getTime()), 'ok')),
      h('dl', { class: 'lines-dl' }, h('dt', {}, 'Chiffre d’affaires déclaré'), h('dd', {}, money(d.revenue)), h('dt', {}, 'Charges déclarées'), h('dd', {}, money(d.charges)),
        h('dt', {}, 'Impôt'), h('dd', {}, money(d.tax)), Number(d.late_fee) ? [h('dt', {}, 'Pénalité de retard'), h('dd', {}, money(d.late_fee))] : null),
      h('p', { class: 'note' }, 'Déclarée par ' + d.declared_by + '.'));
  } else {
    const rev = h('input', { type: 'number', min: 0, step: 0.01, value: Number(lb.revenue), class: 'short', 'aria-label': 'Chiffre d’affaires déclaré' });
    const chg = h('input', { type: 'number', min: 0, step: 0.01, value: Number(lb.charges), class: 'short', 'aria-label': 'Charges déclarées' });
    const taxOut = h('dd', { class: 'big' }), gap = h('p', { class: 'note' });
    const calc = () => {
      const t = lb.exempt ? 0 : Math.max(0, (Number(rev.value) - Number(chg.value)) * Number(R.rate));
      const fee = t * Number(R.late_fee) * lb.days_late;
      taxOut.textContent = lb.exempt ? 'Exonérée' : money2(t + fee) + (fee ? ' (dont ' + money2(fee) + ' de retard)' : '');
      const diff = Number(lb.revenue) - Number(rev.value);
      gap.textContent = diff > 0.5 ? 'Tu déclares ' + money(diff) + ' de moins que ta comptabilité. Le fisc compare les deux.' : '';
    };
    rev.addEventListener('input', calc); chg.addEventListener('input', calc); calc();
    const ok = h('button', { class: 'btn primary', disabled: !can('declarer') }, 'Déclarer et payer');
    ok.addEventListener('click', () => run(ok, async () => {
      const r = await rpc('declare_week', { p_org: me.id, p_week: lb.week_start, p_revenue: Number(rev.value), p_charges: Number(chg.value) });
      refresh(); return { message: 'Semaine ' + lb.week + ' déclarée' + (Number(r.tax) + Number(r.late_fee) > 0 ? ' : ' + money2(Number(r.tax) + Number(r.late_fee)) + ' payés au fisc.' : '.') };
    }));
    declCard = h('section', { class: 'card decl todo-decl' }, h('h2', {}, 'Déclaration · semaine ' + lb.week, lb.days_late ? pill('En retard de ' + lb.days_late + ' j', 'bad') : pill('Avant le ' + date(new Date(lb.deadline).getTime()), 'warn')),
      h('dl', { class: 'lines-dl' }, h('dt', {}, 'Chiffre d’affaires'), h('dd', {}, rev), h('dt', {}, 'Charges déductibles'), h('dd', {}, chg),
        h('dt', {}, lb.exempt ? 'Impôt' : 'Impôt (' + Math.round(Number(R.rate) * 100) + ' % du résultat)'), taxOut),
      gap, ok, h('p', { class: 'note' }, 'Préremplie avec ta comptabilité. Les dons et subventions ne sont pas imposables, les retraits d’espèces ne sont pas déductibles.'));
  }
  const list = ledger.filter(e => f === 'tout' || e.kind === f);
  shell(ctx.nav, 'compta', orgHead(ctx, 'Comptabilité', 'Toutes les entrées et sorties d’argent', can('compta') ? h('button', { class: 'btn', onclick: () => entryModal(ctx, refresh) }, ico('ajout', 18), 'Ajouter une opération') : null),
    h('section', { class: 'card' }, strip([[me.is_association ? 'Entrées · semaine' : 'Recettes · semaine', money(income)], ['Dépenses · semaine', money(wb.charges)],
      [wb.exempt ? 'Impôt' : 'Impôt estimé · semaine', wb.exempt ? 'Exonérée' : money(wb.tax)], ['Taux du serveur', Math.round(Number(R.rate) * 100) + ' %']])),
    h('div', { class: 'grid cols2' }, declCard,
      can('compta') ? h('section', { class: 'card' }, h('h2', {}, 'Déclarations précédentes'), table([['Semaine', d => 'du ' + new Date(d.week_start).toLocaleDateString('fr-FR'), null, d => d.week_start],
        ['Déclaré', d => money(d.revenue), 'num', d => Number(d.revenue)], ['Impôt', d => money(Number(d.tax) + Number(d.late_fee)), 'num', d => Number(d.tax)], ['Par', d => d.declared_by]], decls, null, 'Aucune déclaration pour l’instant.')) : null),
    can('compta') ? h('section', { class: 'card' }, h('h2', {}, 'Grand livre'), fchips([['tout', 'Tout', ledger.length], ['in', 'Entrées'], ['out', 'Sorties']], f, k => { S.filters.ledger = k; refresh(); }, 'Sens'), ledgerTable(list)) : null,
    h('p', { class: 'note' }, ico('oeil', 16), ' Le contrôleur du fisc voit cette comptabilité en lecture seule. Chacune de ses consultations est notée dans le journal (Réglages).'));
}

// ---------------------------------------------------------------- équipe
async function equipe(ctx) {
  const { me, can } = ctx;
  const [members, grades] = await Promise.all([rows('members', q => q.eq('org_id', me.id).order('created_at')), rows('grades', q => q.eq('org_id', me.id).order('rank', { ascending: false }))]);
  const gName = id => (grades.find(g => g.id === id) || { name: '?' }).name, gRank = id => (grades.find(g => g.id === id) || { rank: 0 }).rank;
  const myRank = gRank((members.find(m => m.id === me.member_id) || {}).grade_id);
  const refresh = () => equipe(ctx).catch(fail);
  const below = grades.filter(g => g.rank <= myRank);
  const inviteModal = () => {
    const name = h('input', { type: 'text', maxlength: 60, placeholder: 'Prénom Nom (le nom RP)' }), login = h('input', { type: 'text', maxlength: 40, autocapitalize: 'none', spellcheck: 'false' });
    let touched = false;
    name.addEventListener('input', () => { if (!touched) login.value = slug(name.value).replace('-', '.'); });
    login.addEventListener('input', () => { touched = true; });
    const grade = h('select', {}, below.map(g => h('option', { value: g.id, selected: g.rank === Math.min(...below.map(x => x.rank)) }, g.name)));
    const ok = h('button', { class: 'btn primary' }, 'Créer le compte');
    const m = modal(me.is_association ? 'Accueillir un bénévole' : 'Ajouter quelqu’un à l’équipe', h('label', { class: 'f' }, 'Nom', name), h('div', { class: 'row' }, h('label', { class: 'f grow' }, 'Identifiant', login), h('label', { class: 'f grow' }, 'Grade', grade)),
      h('p', { class: 'note' }, 'Un lien d’activation est créé : la personne y choisit Discord ou un mot de passe.'), h('div', { class: 'foot' }, h('button', { class: 'btn', onclick: () => m.close() }, 'Annuler'), ok));
    ok.addEventListener('click', () => run(ok, async () => {
      if (name.value.trim().length < 2) throw new Error('Indique le nom.');
      const tok = await rpc('invite_member', { p_org: me.id, p_name: name.value.trim(), p_login: login.value.trim(), p_grade: grade.value });
      m.close(); refresh(); linkModal('Compte créé', activationUrl(me.code, tok), name.value.trim());
    }));
  };
  const editModal = mem => {
    const grade = h('select', {}, below.map(g => h('option', { value: g.id, selected: g.id === mem.grade_id }, g.name)));
    const active = h('input', { type: 'checkbox', class: 'toggle', checked: mem.active });
    const ok = h('button', { class: 'btn primary' }, 'Enregistrer');
    const m = modal(mem.name, h('label', { class: 'f' }, 'Grade', grade), h('label', { class: 'check' }, active, 'Compte actif (décoché : plus aucun accès)'),
      h('div', { class: 'foot' }, h('button', { class: 'btn', onclick: () => m.close() }, 'Annuler'), ok));
    ok.addEventListener('click', () => run(ok, async () => { await rpc('set_member', { p_member: mem.id, p_grade: grade.value, p_active: active.checked }); m.close(); refresh(); return { message: 'Compte de ' + mem.name + ' mis à jour.' }; }));
  };
  const renew = (mem, b) => run(b, async () => { const tok = await rpc('renew_invitation', { p_member: mem.id }); refresh(); linkModal('Nouveau lien pour ' + mem.name, activationUrl(me.code, tok), mem.name); });
  shell(ctx.nav, 'equipe', orgHead(ctx, me.is_association ? 'Bénévoles' : 'Équipe', members.filter(m => m.active).length + ' compte(s) actif(s)', can('equipe') ? h('button', { class: 'btn primary', onclick: inviteModal }, ico('ajout', 18), me.is_association ? 'Accueillir un bénévole' : 'Ajouter quelqu’un') : null),
    h('section', { class: 'card' }, table([
      ['Nom', m => h('span', { class: 'person' }, h('span', { class: 'ava sm' }, initials(m.name)), h('span', { class: 'art' }, h('b', {}, m.name), h('small', {}, m.login)))],
      ['Grade', m => pill(gName(m.grade_id), gRank(m.grade_id) === Math.max(...grades.map(g => g.rank)) ? 'info' : null), null, m => -gRank(m.grade_id)],
      ['Compte', m => !m.active ? pill('Désactivé', 'bad') : m.user_id ? pill('Activé', 'ok') : pill('Lien envoyé, pas encore activé', 'warn')],
      ['Vu', m => m.last_seen ? ago(new Date(m.last_seen).getTime()) : 'jamais', 'num', m => m.last_seen || ''],
      can('equipe') ? ['', m => m.id === me.member_id || gRank(m.grade_id) > myRank ? h('span', { class: 'muted small' }, m.id === me.member_id ? 'Toi' : 'Grade au-dessus du tien') : h('span', { class: 'acts' },
        h('button', { class: 'btn small', onclick: ev => { ev.stopPropagation(); editModal(m); } }, 'Modifier'), h('button', { class: 'btn small', onclick: ev => { ev.stopPropagation(); renew(m, ev.currentTarget); } }, 'Nouveau lien'))] : null
    ].filter(Boolean), members)),
    h('p', { class: 'note' }, '« Nouveau lien » détache l’ancienne connexion (mot de passe oublié, changement de compte Discord) et en crée une nouvelle.'));
}

// ---------------------------------------------------------------- réglages
async function reglages(ctx) {
  const { me } = ctx;
  const [grades, journal, orgRow] = await Promise.all([rows('grades', q => q.eq('org_id', me.id).order('rank', { ascending: false })), rows('journal', q => q.eq('org_id', me.id).order('at', { ascending: false }).limit(100)),
    rows('orgs', q => q.eq('id', me.id))]);
  const o = orgRow[0];
  const refresh = () => reglages(ctx).catch(fail);
  const top = Math.max(...grades.map(g => g.rank));
  const box = (g, cap) => {
    const cb = h('input', { type: 'checkbox', checked: g.rank === top || g.caps.includes(cap), disabled: g.rank === top, 'aria-label': g.name + ' : ' + cap, title: g.rank === top ? 'Le grade le plus haut a toujours tous les accès' : null });
    cb.addEventListener('change', async () => {
      cb.disabled = true;
      const next = cb.checked ? g.caps.concat([cap]) : g.caps.filter(c => c !== cap);
      try { await rpc('save_grade', { p_org: me.id, p_id: g.id, p_name: g.name, p_caps: next }); g.caps = next; const td = cb.closest('td'); td.classList.add('saved'); setTimeout(() => td.classList.remove('saved'), 900); }
      catch (e) { cb.checked = !cb.checked; fail(e); }
      cb.disabled = false;
    });
    return h('td', { class: 'tick', 'data-label': g.name }, h('div', { class: 'v' }, cb));
  };
  const fresh = h('input', { type: 'text', maxlength: 30, placeholder: 'Nom du nouveau grade' });
  const add = h('button', { class: 'btn' }, 'Créer le grade');
  add.addEventListener('click', () => run(add, async () => { if (fresh.value.trim().length < 2) throw new Error('Donne un nom au grade.'); await rpc('save_grade', { p_org: me.id, p_id: null, p_name: fresh.value.trim(), p_caps: [] }); refresh(); return { message: 'Grade créé, tout en bas. Coche ses accès.' }; }));
  const disc = h('input', { type: 'checkbox', class: 'toggle', checked: o.discord_login }), pwd = h('input', { type: 'checkbox', class: 'toggle', checked: o.password_login });
  const saveLogin = async () => { try { await rpc('set_org_login', { p_org: me.id, p_discord: disc.checked, p_password: pwd.checked }); o.discord_login = disc.checked; o.password_login = pwd.checked; toast('Connexion mise à jour.'); } catch (e) { disc.checked = o.discord_login; pwd.checked = o.password_login; fail(e); } };
  disc.addEventListener('change', saveLogin); pwd.addEventListener('change', saveLogin);
  const url = location.origin + '/' + me.code;
  shell(ctx.nav, 'reglages', orgHead(ctx, 'Réglages', me.name + ' · licence jusqu’au ' + date(new Date(me.licence_until).getTime())),
    h('section', { class: 'card' }, h('h2', {}, 'Grades et accès'), h('p', { class: 'muted', style: 'margin-top:-6px' }, 'Chaque case est enregistrée tout de suite. Le grade le plus haut a toujours tout.'),
      h('div', { class: 'scroll' }, h('table', { class: 'matrix' }, h('thead', {}, h('tr', {}, h('th', {}, 'Accès'), grades.map(g => h('th', { class: 'tick' }, g.name)))),
        h('tbody', {}, CAPS.map(([cap, label]) => h('tr', {}, h('td', { class: 'what' }, label), grades.map(g => box(g, cap))))))),
      h('div', { class: 'row', style: 'margin-top:16px' }, h('label', { class: 'f grow' }, 'Nouveau grade', fresh), add)),
    h('section', { class: 'card' }, h('h2', {}, 'Connexion'),
      h('div', { class: 'connrow' }, h('div', {}, h('span', { class: 'muted small' }, 'Adresse de l’espace'), h('code', { class: 'auth-url' }, url)), h('button', { class: 'btn small', onclick: () => copyText(url, 'Adresse copiée.') }, ico('copier', 16), 'Copier')),
      h('div', { class: 'field' }, h('div', { class: 'lab' }, h('b', {}, 'Connexion avec Discord')), h('div', { class: 'ctl' }, disc)),
      h('div', { class: 'field' }, h('div', { class: 'lab' }, h('b', {}, 'Connexion par identifiant et mot de passe')), h('div', { class: 'ctl' }, pwd))),
    h('section', { class: 'card' }, h('h2', {}, 'Modules de l’offre'), h('div', { class: 'modgrid' }, MODULES.map(m => h('div', { class: 'modcard' + (me.modules.includes(m.id) ? ' on' : '') },
      h('div', { class: 'mc-top' }, h('span', { class: 'mc-ic' }, ico(m.icon, 20)), me.modules.includes(m.id) ? pill('Inclus', 'ok') : pill('Non inclus')), h('b', {}, m.name), h('p', {}, m.text),
      h('div', { class: 'mc-foot' }, h('span', { class: 'muted small' }, money(m.price) + ' / semaine'))))),
      h('p', { class: 'note', style: 'margin-top:12px' }, 'Pour ajouter ou retirer un module, contacte l’éditeur de ' + BRAND + '.')),
    h('section', { class: 'card' }, h('h2', {}, 'Journal'), table([['Quand', j => date(new Date(j.at).getTime()), 'num', j => j.at], ['Qui', j => j.by_fisc ? h('span', {}, pill('Fisc', 'info'), ' ' + j.who) : j.who], ['Quoi', j => j.what]], journal, null, 'Rien pour l’instant.')));
}

// ================================================================== espaces de la plateforme : fisc et éditeur
async function platformSpace(role, b, c) {
  if (!ME.session) return go('/connexion/plateforme', true);
  const sp = await spaces();
  if (sp.role !== role) return message('Accès réservé', role === 'fisc' ? 'Cette page est réservée au contrôleur du fisc.' : 'Cette page est réservée à l’éditeur de ' + BRAND + '.', h('a', { class: 'btn', href: '/' }, 'Accueil'));
  const who = { name: sp.display_name, role: role === 'fisc' ? 'Contrôleur du fisc' : 'Éditeur de ' + BRAND };
  if (role === 'fisc') {
    const nav = [{ page: 'fisc', href: '/fisc', label: 'Vue d’ensemble', short: 'Accueil', icon: 'accueil' }, { page: 'bareme', href: '/fisc/bareme', label: 'Barème', icon: 'balance' }];
    return b === 'bareme' ? bareme(nav, who) : b ? fiscOrg(nav, who, b) : fiscHome(nav, who);
  }
  const nav = [{ page: 'clients', href: '/editeur', label: 'Clients', icon: 'batiment' }, { page: 'devis', href: '/editeur/devis', label: 'Devis', icon: 'facture' }, { page: 'modules', href: '/editeur/modules', label: 'Modules', icon: 'grille' }];
  return b === 'modules' ? modulesPage(nav, who) : b === 'devis' ? (c ? devisEdit(nav, who, c) : devisList(nav, who)) : clients(nav, who);
}

// ---------------------------------------------------------------- fisc
function declState(o) {
  const lw = o.last_week;
  if (lw.declaration) return ['payee', pill('Payée', 'ok')];
  if (lw.days_late > 0) return ['retard', pill('En retard · ' + lw.days_late + ' j', 'bad')];
  return ['attente', pill('À déclarer avant le ' + date(new Date(lw.deadline).getTime()), 'warn')];
}
function signals(o) {
  const out = [];
  if (Number(o.underdeclared) > 0) out.push([3, 'Sous-déclaration de ' + money(o.underdeclared)]);
  if (Number(o.cash_out) > 0) out.push([3, o.cash_out + ' retrait(s) d’espèces ≥ 5 000 $']);
  if (declState(o)[0] === 'retard') out.push([3, 'Déclaration en retard']);
  if (Number(o.open_controls) > 0) out.push([1, 'Contrôle en cours']);
  return out;
}
const riskPill = o => { const r = signals(o).reduce((a, [s]) => a + s, 0); return r === 0 ? pill('Rien à signaler', 'ok') : r <= 1 ? pill('Suivi', 'info') : r <= 3 ? pill('Risque moyen', 'warn') : pill('Risque élevé', 'bad'); };
async function fiscHome(nav, who) {
  const all = await rpc('fisc_overview');
  const due = all.reduce((a, o) => a + Number(o.last_week.tax), 0), paid = all.reduce((a, o) => a + (o.last_week.declaration ? Number(o.last_week.declaration.tax) : 0), 0);
  const ranked = all.slice().sort((a, b) => signals(b).reduce((x, [s]) => x + s, 0) - signals(a).reduce((x, [s]) => x + s, 0));
  const wk = all[0] ? all[0].last_week.week : '';
  shell(nav, 'fisc', head('Fisc', [pill('Lecture seule', 'info'), ' ' + all.length + ' entreprise(s) suivie(s)'], who),
    h('section', { class: 'card' }, h('h2', {}, 'Semaine ' + wk), strip([['Impôts attendus (comptabilité)', money(due)], ['Impôts déclarés et payés', money(paid)],
      ['Déclarations manquantes', String(all.filter(o => !o.last_week.declaration).length)], ['Signaux', String(all.reduce((a, o) => a + signals(o).length, 0))]])),
    h('section', { class: 'card' }, h('h2', {}, 'Entreprises'), table([['Entreprise', o => h('span', { class: 'art' }, h('b', {}, o.name), h('small', {}, o.kind + ' · ' + o.owner_name))],
      ['CA enregistré · S' + wk, o => money(o.last_week.revenue), 'num', o => Number(o.last_week.revenue)], ['Impôt dû', o => o.last_week.exempt ? 'Exonérée' : money(o.last_week.tax), 'num', o => Number(o.last_week.tax)],
      ['Déclaration', o => declState(o)[1]], ['Signaux', o => { const s = signals(o); return s.length ? h('span', { class: 'pills' }, s.map(([lv, t]) => pill(t, lv >= 3 ? 'bad' : 'info'))) : h('span', { class: 'muted' }, '—'); }],
      ['Risque', riskPill, null, o => signals(o).reduce((a, [s]) => a + s, 0)]], ranked, o => go('/fisc/' + o.code), 'Aucune entreprise pour l’instant.')));
}
async function fiscOrg(nav, who, code) {
  const orgsList = await rows('orgs', q => q.eq('code', code));
  const o = orgsList[0];
  if (!o) return message('Entreprise introuvable', 'Aucune entreprise à l’adresse /' + code + '.', h('a', { class: 'btn', href: '/fisc' }, 'Retour'));
  if (arriving()) await rpc('fisc_open', { p_org: o.id });
  const tab = S.filters.ftab && S.filters.ftabOrg === o.id ? S.filters.ftab : 'synthese';
  const setTab = t => { S.filters.ftab = t; S.filters.ftabOrg = o.id; fiscOrg(nav, who, code).catch(fail); };
  const refresh = () => fiscOrg(nav, who, code).catch(fail);
  const weeks = [1, 2, 3, 4].map(k => Date.now() - k * 7 * DAY);
  const [books, controls] = await Promise.all([Promise.all(weeks.map(ms => weekBook(o.id, ms))), rows('controls', q => q.eq('org_id', o.id).order('opened_at', { ascending: false }))]);
  const open = controls.find(c => !c.closed_at);
  let content;
  if (tab === 'synthese') {
    const ca = books.reduce((a, b) => a + Number(b.revenue), 0), ch = books.reduce((a, b) => a + Number(b.charges), 0), tx = books.reduce((a, b) => a + (b.declaration ? Number(b.declaration.tax) + Number(b.declaration.late_fee) : 0), 0);
    const gaps = books.filter(b => b.declaration && Number(b.revenue) - Number(b.declaration.revenue) > 0.5);
    content = [h('section', { class: 'card' }, h('h2', {}, '4 dernières semaines'), strip([['Chiffre d’affaires enregistré', money(ca)], ['Charges', money(ch)], ['Impôts payés', o.is_association ? 'Exonérée' : money(tx)]])),
      h('div', { class: 'grid cols2' },
        h('section', { class: 'card' }, h('h2', {}, 'Écarts entre déclaration et comptabilité'), gaps.length ? h('ul', { class: 'anos' }, gaps.map(b => h('li', { class: 'sev3' }, pill('Élevé', 'bad'),
          h('div', {}, h('b', {}, 'Semaine ' + b.week + ' : ' + money(Number(b.revenue) - Number(b.declaration.revenue)) + ' non déclarés'), h('span', {}, 'Déclaré ' + money(b.declaration.revenue) + ' · enregistré ' + money(b.revenue)))))) :
          h('div', { class: 'calm' }, h('span', { class: 'ic' }, ico('coche')), 'Les déclarations correspondent à la comptabilité.')),
        h('section', { class: 'card' }, h('h2', {}, 'CA enregistré par semaine'), dailyBars(books.slice().reverse().map(b => ({ t: Date.parse(b.week_start), label: 'Semaine ' + b.week, v: Number(b.revenue) })), money)))];
  } else if (tab === 'declarations') {
    content = [h('section', { class: 'card' }, table([['Semaine', b => 'S' + b.week, null, b => b.week_start], ['CA enregistré', b => money(b.revenue), 'num'], ['CA déclaré', b => b.declaration ? money(b.declaration.revenue) : '—', 'num'],
      ['Écart', b => { if (!b.declaration) return '—'; const e = Number(b.revenue) - Number(b.declaration.revenue); return e > 0.5 ? h('b', { class: 'amt out' }, '−' + money(e)) : h('span', { class: 'muted' }, 'aucun'); }, 'num'],
      ['Impôt payé', b => b.declaration ? money(Number(b.declaration.tax) + Number(b.declaration.late_fee)) : '—', 'num'],
      ['Statut', b => b.declaration ? pill('Le ' + date(new Date(b.declaration.declared_at).getTime()), 'ok') : b.days_late ? pill('En retard · ' + b.days_late + ' j', 'bad') : pill('À déclarer', 'warn')]], books))];
  } else if (tab === 'compta') {
    const ledger = await rows('ledger', q => q.eq('org_id', o.id).order('at', { ascending: false }).limit(300));
    content = [h('section', { class: 'card' }, ledgerTable(ledger))];
  } else {
    content = [h('section', { class: 'card' }, table([['Ouvert le', c => date(new Date(c.opened_at).getTime()), 'num', c => c.opened_at], ['Motif', c => c.reason], ['Par', c => c.inspector],
      ['Statut', c => c.closed_at ? pill('Clos le ' + date(new Date(c.closed_at).getTime())) : pill('En cours', 'warn')], ['Redressement', c => Number(c.fine) ? h('b', { class: 'amt out' }, money(c.fine)) : '—', 'num'], ['Note', c => c.note || '']], controls, null, 'Aucun contrôle.'))];
  }
  const controlModal = () => {
    const reason = h('textarea', { maxlength: 300, placeholder: 'Ce qui motive le contrôle (visible par l’entreprise)' }), ok = h('button', { class: 'btn primary' }, 'Ouvrir le contrôle');
    const m = modal('Contrôle · ' + o.name, h('p', { class: 'muted' }, 'L’entreprise voit un bandeau « Contrôle fiscal en cours » sur son tableau de bord.'), h('label', { class: 'f' }, 'Motif', reason), h('div', { class: 'foot' }, h('button', { class: 'btn', onclick: () => m.close() }, 'Annuler'), ok));
    ok.addEventListener('click', () => run(ok, async () => { await rpc('fisc_open_control', { p_org: o.id, p_reason: reason.value }); m.close(); refresh(); return { message: 'Contrôle ouvert.' }; }));
  };
  const fineModal = () => {
    const amount = h('input', { type: 'number', min: 1, value: 1000, class: 'short' }), reason = h('input', { type: 'text', maxlength: 160, placeholder: 'Ex. CA sous-déclaré en semaine 43' }), close = h('input', { type: 'checkbox', checked: true });
    const ok = h('button', { class: 'btn danger' }, 'Appliquer le redressement');
    const m = modal('Redressement · ' + o.name, h('div', { class: 'row' }, h('label', { class: 'f' }, 'Montant ($)', amount), h('label', { class: 'f grow' }, 'Motif', reason)), h('label', { class: 'check' }, close, 'Clore le contrôle'),
      h('p', { class: 'note' }, 'Le montant est débité de la comptabilité de l’entreprise, catégorie Impôts.'), h('div', { class: 'foot' }, h('button', { class: 'btn', onclick: () => m.close() }, 'Annuler'), ok));
    ok.addEventListener('click', () => run(ok, async () => { await rpc('fisc_fine', { p_org: o.id, p_amount: Number(amount.value), p_reason: reason.value, p_close: close.checked }); m.close(); refresh(); return { message: 'Redressement appliqué.' }; }));
  };
  shell(nav, 'fisc', head(o.name, [pill(o.kind), ' ' + o.owner_name + ' · /' + o.code + ' · ', pill('Lecture seule', 'info')], who,
      open ? h('button', { class: 'btn', onclick: ev => run(ev.currentTarget, async () => { await rpc('fisc_close_control', { p_control: open.id, p_note: '' }); refresh(); return { message: 'Contrôle clos.' }; }) }, 'Clore le contrôle')
        : h('button', { class: 'btn', onclick: controlModal }, ico('loupe', 18), 'Ouvrir un contrôle'),
      h('button', { class: 'btn danger', onclick: fineModal }, 'Redressement'), h('a', { class: 'btn ghost', href: '/fisc' }, 'Toutes les entreprises')),
    open ? h('div', { class: 'banner warn' }, ico('loupe', 20), h('div', {}, h('b', {}, 'Contrôle en cours depuis le ' + date(new Date(open.opened_at).getTime())), h('span', {}, open.reason))) : null,
    h('div', { class: 'tabs' }, [['synthese', 'Synthèse'], ['declarations', 'Déclarations'], ['compta', 'Comptabilité'], ['controles', 'Contrôles (' + controls.length + ')']].map(([k, l]) => h('button', { class: 'tab' + (k === tab ? ' on' : ''), onclick: () => setTab(k) }, l))),
    ...content, h('p', { class: 'note' }, ico('oeil', 16), ' Ta consultation est notée dans le journal de l’entreprise.'));
}
async function bareme(nav, who) {
  const R = (await rows('tax_rules'))[0];
  const rate = h('input', { type: 'number', min: 0, max: 100, step: 0.5, value: Number(R.rate) * 100, class: 'short' }), fee = h('input', { type: 'number', min: 0, max: 100, step: 0.5, value: Number(R.late_fee) * 100, class: 'short' });
  const dow = h('select', {}, DAYS.map((d, i) => h('option', { value: i + 1, selected: i + 1 === R.deadline_dow }, d))), hour = h('input', { type: 'number', min: 0, max: 23, value: R.deadline_hour, class: 'short' });
  const exempt = h('input', { type: 'checkbox', class: 'toggle', checked: R.associations_exempt });
  const save = h('button', { class: 'btn primary' }, 'Enregistrer le barème');
  save.addEventListener('click', () => run(save, async () => {
    await rpc('set_tax_rules', { p_rate: Number(rate.value) / 100, p_late_fee: Number(fee.value) / 100, p_deadline_dow: Number(dow.value), p_deadline_hour: Number(hour.value), p_assoc_exempt: exempt.checked });
    return { message: 'Barème enregistré : il s’applique aux prochaines déclarations.' };
  }));
  const ca = h('input', { type: 'number', value: 9000, min: 0, class: 'short', 'aria-label': 'Chiffre d’affaires' }), ch = h('input', { type: 'number', value: 5200, min: 0, class: 'short', 'aria-label': 'Charges' }), res = h('b', { class: 'calc' });
  const calc = () => { res.textContent = money(Math.max(0, (Number(ca.value) - Number(ch.value)) * Number(rate.value) / 100)); };
  [ca, ch, rate].forEach(i => i.addEventListener('input', calc)); calc();
  const field = (label, help, ctl) => h('div', { class: 'field' }, h('div', { class: 'lab' }, h('b', {}, label), help ? h('span', {}, help) : null), h('div', { class: 'ctl' }, ctl));
  shell(nav, 'bareme', head('Barème', 'Les règles appliquées à toutes les entreprises du serveur', who),
    h('div', { class: 'grid cols2' },
      h('section', { class: 'card' }, h('h2', {}, 'Règles'), h('div', { class: 'fields' }, field('Taux (%)', 'Part du résultat de la semaine (recettes moins charges).', rate),
        field('Pénalité de retard (% par jour)', 'Ajoutée à l’impôt quand la déclaration arrive après l’échéance.', fee), field('Échéance', 'Pour la semaine précédente.', h('span', { class: 'row' }, dow, hour, h('span', { class: 'muted' }, 'h'))),
        field('Associations exonérées', 'Elles déclarent quand même chaque semaine.', exempt)), h('div', { style: 'margin-top:16px' }, save),
        R.updated_by ? h('p', { class: 'note' }, 'Dernière modification par ' + R.updated_by + ' le ' + date(new Date(R.updated_at).getTime()) + '.') : null),
      h('section', { class: 'card' }, h('h2', {}, 'Calculer un impôt'), h('div', { class: 'row' }, h('label', { class: 'f grow' }, 'Chiffre d’affaires ($)', ca), h('label', { class: 'f grow' }, 'Charges déductibles ($)', ch)),
        h('div', { class: 'calcbox' }, h('span', {}, 'Impôt de la semaine'), res),
        h('p', { class: 'note' }, 'Recettes imposables : ventes et factures. Non imposables : dons et subventions. Non déductibles : retraits d’espèces, impôts, amendes.'))));
}

// ---------------------------------------------------------------- éditeur : clients, modules, devis
function newClientModal(prefill, after) {
  const p = prefill || {};
  const name = h('input', { type: 'text', maxlength: 60, value: p.client || '', placeholder: 'Ex. Burger Shot' }), code = h('input', { type: 'text', maxlength: 40, value: slug(p.client || ''), autocapitalize: 'none' });
  const kind = h('input', { type: 'text', maxlength: 40, value: p.kind || '', placeholder: 'Ex. Restauration rapide' }), owner = h('input', { type: 'text', maxlength: 60, value: p.contact || '', placeholder: 'Nom RP du patron' });
  const ownerLogin = h('input', { type: 'text', maxlength: 40, value: slug(p.contact || '').replace('-', '.'), autocapitalize: 'none' });
  const assoc = h('input', { type: 'checkbox' }), weeks = h('input', { type: 'number', min: 1, max: 52, value: p.weeks || 1, class: 'short' });
  let codeTouched = !!p.client, loginTouched = !!p.contact;
  name.addEventListener('input', () => { if (!codeTouched) code.value = slug(name.value); }); code.addEventListener('input', () => { codeTouched = true; });
  owner.addEventListener('input', () => { if (!loginTouched) ownerLogin.value = slug(owner.value).replace('-', '.'); }); ownerLogin.addEventListener('input', () => { loginTouched = true; });
  let mods = (p.modules || PACKS.Commerce).slice();
  const pick = h('div', { class: 'modpick' });
  const paint = () => pick.replaceChildren(...MODULES.map(m => h('label', { class: 'mp' + (mods.includes(m.id) ? ' on' : '') }, h('input', { type: 'checkbox', checked: mods.includes(m.id), onchange: ev => { mods = ev.target.checked ? mods.concat([m.id]) : mods.filter(x => x !== m.id); paint(); } }),
    h('span', { class: 'mp-ic' }, ico(m.icon, 18)), h('span', { class: 'mp-name' }, m.name), h('b', {}, money(m.price)))));
  paint();
  const ok = h('button', { class: 'btn primary' }, 'Créer l’espace');
  const m = modal('Nouveau client', h('label', { class: 'f' }, 'Entreprise', name), h('div', { class: 'row' }, h('label', { class: 'f grow' }, 'Adresse (/…)', code), h('label', { class: 'f grow' }, 'Activité', kind)),
    h('div', { class: 'row' }, h('label', { class: 'f grow' }, 'Patron', owner), h('label', { class: 'f grow' }, 'Identifiant du patron', ownerLogin)),
    h('div', { class: 'row' }, h('label', { class: 'f' }, 'Licence (semaines)', weeks), h('label', { class: 'check' }, assoc, 'Association (exonérée d’impôt)')),
    h('div', { class: 'qpacks' }, h('span', { class: 'muted small' }, 'Partir d’un pack :'), fchips(Object.keys(PACKS).map(k => [k, k]), null, k => { mods = PACKS[k].slice(); paint(); }, 'Pack')), pick,
    h('div', { class: 'foot' }, h('button', { class: 'btn', onclick: () => m.close() }, 'Annuler'), ok));
  m.box.classList.add('wide');
  ok.addEventListener('click', () => run(ok, async () => {
    const r = await rpc('create_org', { p_code: slug(code.value), p_name: name.value.trim(), p_kind: kind.value.trim() || (assoc.checked ? 'Association' : 'Entreprise'), p_owner_name: owner.value.trim(), p_owner_login: ownerLogin.value.trim(),
      p_is_association: assoc.checked, p_modules: mods, p_weeks: Number(weeks.value) || 1 });
    m.close(); if (after) after(); linkModal('Espace créé', activationUrl(slug(code.value), r.token), owner.value.trim() || 'le patron');
  }));
}
async function clients(nav, who) {
  const all = await rpc('editor_overview');
  const refresh = () => clients(nav, who).catch(fail);
  const soon = all.filter(o => new Date(o.licence_until).getTime() < Date.now() + 3 * DAY);
  const ownerLink = async (o, b) => run(b, async () => {
    const [members, grades] = await Promise.all([rows('members', q => q.eq('org_id', o.id)), rows('grades', q => q.eq('org_id', o.id))]);
    const top = Math.max(...grades.map(g => g.rank)), owner = members.find(mm => (grades.find(g => g.id === mm.grade_id) || {}).rank === top);
    if (!owner) throw new Error('Aucun patron trouvé dans cet espace.');
    const tok = await rpc('renew_invitation', { p_member: owner.id });
    refresh(); linkModal('Nouveau lien pour ' + owner.name, activationUrl(o.code, tok), owner.name);
  });
  shell(nav, 'clients', head('Clients', 'Les entreprises qui utilisent ' + BRAND, who, h('button', { class: 'btn primary', onclick: () => newClientModal(null, refresh) }, ico('ajout', 18), 'Nouveau client')),
    h('section', { class: 'card' }, strip([['Clients', String(all.length)], ['Comptes actifs', String(all.reduce((a, o) => a + Number(o.accounts), 0))], ['Revenu par semaine', money(all.reduce((a, o) => a + Number(o.price), 0))],
      ['Licences à renouveler', String(soon.length), soon.length ? 'neg' : null]])),
    h('section', { class: 'card' }, table([['Entreprise', o => h('span', { class: 'art' }, h('b', {}, o.name), h('small', {}, o.kind + ' · ' + o.owner_name))], ['Adresse', o => h('code', {}, '/' + o.code)],
      ['Modules', o => o.modules.length ? h('span', { class: 'pills' }, o.modules.map(id => pill(moduleOf(id).name))) : h('span', { class: 'muted' }, 'Socle seul')], ['Par semaine', o => h('b', {}, money(o.price)), 'num', o => Number(o.price)],
      ['Comptes', o => o.accounts + ' actif(s)' + (Number(o.pending) ? ' · ' + o.pending + ' en attente' : ''), 'num', o => Number(o.accounts)],
      ['Licence', o => { const t = new Date(o.licence_until).getTime(); return t < Date.now() ? pill('Expirée le ' + date(t), 'bad') : t < Date.now() + 3 * DAY ? pill('Jusqu’au ' + date(t), 'warn') : h('span', {}, 'jusqu’au ' + date(t)); }, 'num', o => o.licence_until],
      ['', o => h('span', { class: 'acts' }, h('button', { class: 'btn small', onclick: ev => { ev.stopPropagation(); ownerLink(o, ev.currentTarget); } }, 'Lien patron'),
        h('button', { class: 'btn small', onclick: ev => { ev.stopPropagation(); run(ev.currentTarget, async () => { await rpc('extend_licence', { p_org: o.id, p_days: 7 }); refresh(); return { message: 'Licence de ' + o.name + ' prolongée d’une semaine.' }; }); } }, 'Prolonger 7 j'))]],
      all, null, 'Aucun client pour l’instant. Crée le premier.')));
}
async function modulesPage(nav, who) {
  const all = await rpc('editor_overview');
  shell(nav, 'modules', head('Modules', 'Coche un module pour l’activer chez un client : son menu change tout de suite', who),
    h('section', { class: 'card' }, h('div', { class: 'scroll' }, h('table', { class: 'matrix' }, h('thead', {}, h('tr', {}, h('th', {}, 'Client'), MODULES.map(m => h('th', { class: 'tick' }, m.name)))),
      h('tbody', {}, all.map(o => h('tr', {}, h('td', { class: 'what' }, o.name), MODULES.map(m => h('td', { class: 'tick', 'data-label': m.name }, h('div', { class: 'v' },
        h('input', { type: 'checkbox', checked: o.modules.includes(m.id), 'aria-label': o.name + ' : ' + m.name, onchange: async ev => {
          const cb = ev.target, next = cb.checked ? o.modules.concat([m.id]) : o.modules.filter(x => x !== m.id);
          cb.disabled = true;
          try { await rpc('set_org_modules', { p_org: o.id, p_modules: next }); o.modules = next; cb.closest('td').classList.add('saved'); toast(m.name + (cb.checked ? ' activé' : ' retiré') + ' pour ' + o.name + '.'); }
          catch (e) { cb.checked = !cb.checked; fail(e); }
          cb.disabled = false;
        } }))))))))), all.length ? null : h('div', { class: 'empty' }, 'Aucun client.')),
    h('section', { class: 'card' }, h('h2', {}, 'Catalogue'), h('div', { class: 'modgrid' }, MODULES.map(m => h('div', { class: 'modcard on' }, h('div', { class: 'mc-top' }, h('span', { class: 'mc-ic' }, ico(m.icon, 20)), h('b', {}, money(m.price) + ' / sem.')),
      h('b', {}, m.name), h('p', {}, m.text), h('div', { class: 'mc-foot' }, h('span', { class: 'muted small' }, all.filter(o => o.modules.includes(m.id)).length + ' client(s)')))))));
}
const QST = { brouillon: ['Brouillon', null], envoye: ['Envoyé', 'info'], accepte: ['Accepté', 'ok'], refuse: ['Refusé', 'bad'] };
async function devisList(nav, who) {
  const list = await rows('quotes', q => q.order('created_at', { ascending: false }));
  const signed = list.filter(q => q.status === 'accepte'), sent = list.filter(q => q.status !== 'brouillon');
  shell(nav, 'devis', head('Devis', 'Propose tes licences, envoie le devis en image ou en PDF', who, h('a', { class: 'btn primary', href: '/editeur/devis/nouveau' }, ico('ajout', 18), 'Nouveau devis')),
    h('section', { class: 'card' }, strip([['En attente de réponse', String(list.filter(q => q.status === 'envoye').length)], ['Montant signé', money(signed.reduce((a, q) => a + quoteTotal(q).total, 0))],
      ['Taux d’acceptation', sent.length ? Math.round(signed.length / sent.length * 100) + ' %' : '—'], ['Brouillons', String(list.filter(q => q.status === 'brouillon').length)]])),
    h('section', { class: 'card' }, table([['N°', q => h('b', {}, q.num), null, q => q.num], ['Client', q => h('span', { class: 'art' }, h('b', {}, q.client), h('small', {}, [q.contact, q.kind].filter(Boolean).join(' · ')))],
      ['Modules', q => h('span', { class: 'pills' }, q.modules.map(id => pill(moduleOf(id).name)))], ['Durée', q => q.weeks + ' sem.', 'num', q => q.weeks], ['Total', q => h('b', {}, money(quoteTotal(q).total)), 'num', q => quoteTotal(q).total],
      ['Statut', q => pill(QST[q.status][0], QST[q.status][1])], ['Créé', q => ago(new Date(q.created_at).getTime()), 'num', q => q.created_at],
      ['', q => h('button', { class: 'btn small', onclick: ev => { ev.stopPropagation(); exportModal(quoteDoc(q), 'devis-' + q.num); } }, ico('telecharger', 16), 'Exporter')]],
      list, q => go('/editeur/devis/' + q.id), 'Aucun devis. Crée le premier.')));
}
async function devisEdit(nav, who, id) {
  let q;
  if (id === 'nouveau') {
    const d = new Date();
    q = { num: 'D-' + d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + String(d.getDate()).padStart(2, '0') + '-' + String(Math.floor(Math.random() * 900) + 100), client: '', contact: '', kind: '', modules: PACKS.Commerce.slice(), weeks: 4, discount: 0, status: 'brouillon', note: '', created_at: d.toISOString() };
  } else {
    q = (await rows('quotes', b => b.eq('id', id)))[0];
    if (!q) return message('Devis introuvable', 'Ce devis n’existe pas ou a été supprimé.', h('a', { class: 'btn', href: '/editeur/devis' }, 'Tous les devis'));
  }
  const img = h('img', { class: 'docimg live', alt: 'Aperçu du devis' });
  let pend = null;
  const totalBox = h('div', { class: 'qtotal' });
  const paintTotal = () => { const t = quoteTotal(q); totalBox.replaceChildren(h('span', {}, money(t.week1) + ' par semaine × ' + q.weeks + (q.discount ? ' · remise ' + q.discount + ' %' : '')), h('b', {}, money(t.total))); };
  const refresh = () => { clearTimeout(pend); pend = setTimeout(async () => { const c = await renderDoc(quoteDoc(q)); img.src = c.toDataURL('image/png'); }, 150); paintTotal(); };
  const field = (label, key, ph) => { const i = h('input', { type: 'text', value: q[key] || '', placeholder: ph, maxlength: 60 }); i.addEventListener('input', () => { q[key] = i.value; refresh(); }); return h('label', { class: 'f' }, label, i); };
  const mods = h('div', { class: 'modpick' });
  const paintMods = () => mods.replaceChildren(...MODULES.map(m => h('label', { class: 'mp' + (q.modules.includes(m.id) ? ' on' : '') },
    h('input', { type: 'checkbox', checked: q.modules.includes(m.id), onchange: ev => { q.modules = ev.target.checked ? MODULES.filter(x => x.id === m.id || q.modules.includes(x.id)).map(x => x.id) : q.modules.filter(x => x !== m.id); paintMods(); refresh(); } }),
    h('span', { class: 'mp-ic' }, ico(m.icon, 18)), h('span', { class: 'mp-name' }, m.name), h('b', {}, money(m.price)))));
  const weeks = h('div', { class: 'seg', role: 'group', 'aria-label': 'Durée' });
  const paintWeeks = () => weeks.replaceChildren(...[1, 4, 12].map(w => h('button', { type: 'button', class: q.weeks === w ? 'on' : null, 'aria-pressed': String(q.weeks === w), onclick: () => { q.weeks = w; paintWeeks(); refresh(); } }, w + ' sem.')));
  const disc = h('input', { type: 'number', min: 0, max: 50, value: q.discount, class: 'short' });
  disc.addEventListener('input', () => { q.discount = Math.max(0, Math.min(50, parseInt(disc.value, 10) || 0)); refresh(); });
  const note = h('textarea', { placeholder: 'Ex. Première semaine offerte si signature avant vendredi.', maxlength: 160 });
  note.value = q.note || '';
  note.addEventListener('input', () => { q.note = note.value; refresh(); });
  const save = async status => {
    if (!q.client.trim()) throw new Error('Indique le nom du client.');
    if (!q.modules.length) throw new Error('Choisis au moins un module.');
    const data = { num: q.num, client: q.client.trim(), contact: (q.contact || '').trim(), kind: (q.kind || '').trim(), modules: q.modules, weeks: q.weeks, discount: q.discount, status: status || q.status, note: q.note || null };
    const res = q.id ? await sb.from('quotes').update(data).eq('id', q.id).select() : await sb.from('quotes').insert(data).select();
    if (res.error) throw new Error(res.error.message);
    Object.assign(q, res.data[0]);
    return q;
  };
  const actions = h('div', { class: 'qactions' }, h('button', { class: 'btn', onclick: ev => run(ev.currentTarget, async () => { const isNew = !q.id; await save(); if (isNew) go('/editeur/devis/' + q.id, true); return { message: 'Devis ' + q.num + ' enregistré.' }; }) }, 'Enregistrer'),
    h('button', { class: 'btn primary', onclick: ev => run(ev.currentTarget, async () => { await save(); exportModal(quoteDoc(q), 'devis-' + q.num); }) }, ico('telecharger', 18), 'PNG ou PDF'),
    q.status === 'brouillon' ? h('button', { class: 'btn', onclick: ev => run(ev.currentTarget, async () => { await save('envoye'); go('/editeur/devis/' + q.id, true); return { message: 'Devis marqué comme envoyé.' }; }) }, 'Marquer envoyé') : null,
    q.status === 'envoye' ? h('button', { class: 'btn', onclick: ev => run(ev.currentTarget, async () => { await save('accepte'); newClientModal(q, () => go('/editeur')); }) }, ico('coche', 18), 'Accepté : créer l’espace') : null,
    q.status === 'envoye' ? h('button', { class: 'btn ghost', onclick: ev => run(ev.currentTarget, async () => { await save('refuse'); go('/editeur/devis/' + q.id, true); return { message: 'Devis marqué comme refusé.' }; }) }, 'Refusé') : null);
  paintMods(); paintWeeks(); refresh();
  shell(nav, 'devis', head(q.id ? 'Devis ' + q.num : 'Nouveau devis', [pill(QST[q.status][0], QST[q.status][1]), ' ' + q.num], who, h('a', { class: 'btn', href: '/editeur/devis' }, 'Tous les devis')),
    h('div', { class: 'qedit' },
      h('div', { class: 'col' },
        h('section', { class: 'card stack' }, h('h2', {}, 'Client'), field('Entreprise', 'client', 'Ex. Burger Shot'), h('div', { class: 'row' }, field('Contact', 'contact', 'Nom du patron'), field('Activité', 'kind', 'Ex. Restauration rapide'))),
        h('section', { class: 'card' }, h('h2', {}, 'Modules'), h('div', { class: 'qpacks' }, h('span', { class: 'muted small' }, 'Partir d’un pack :'), fchips(Object.keys(PACKS).map(k => [k, k]), null, k => { q.modules = PACKS[k].slice(); paintMods(); refresh(); }, 'Pack')), mods),
        h('section', { class: 'card stack' }, h('h2', {}, 'Conditions'), h('div', { class: 'row' }, h('label', { class: 'f' }, 'Durée', weeks), h('label', { class: 'f' }, 'Remise (%)', disc)), h('label', { class: 'f' }, 'Note sur le devis', note), totalBox, actions)),
      h('section', { class: 'card qpreview' }, h('h2', {}, 'Aperçu', h('span', { class: 'muted small' }, 'exactement ce qui sera exporté')), h('div', { class: 'docframe' }, img))));
}

// ================================================================== démarrage
const { data: { session } } = await sb.auth.getSession();
ME.session = session;
sb.auth.onAuthStateChange((event, s2) => {
  const changed = (ME.session && ME.session.user.id) !== (s2 && s2.user.id);
  ME.session = s2;
  if (changed) { ME.spaces = null; if (!ME.quiet) route(); }   // déconnexion dans un autre onglet, session expirée
});
route();
