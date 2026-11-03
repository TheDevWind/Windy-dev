// Banc d'essai du site : un vrai navigateur (Playwright) sur le site servi en local, branché sur la vraie base (PGlite).
// npm run test:site  (Playwright doit être installé ; captures dans tests/shots si SHOTS=1)
// 1. un scénario complet : éditeur → client → patron → équipe → déclaration → fisc ;
// 2. une revue de toutes les pages en clair/sombre, ordinateur/téléphone : erreurs JS, contraste, débordement, « null » affiché.
import { createRequire } from 'node:module';
import { mkdirSync, readFileSync } from 'node:fs';
import assert from 'node:assert/strict';
import { createBackend } from './backend.mjs';
import { serve } from './serve.mjs';

const { chromium } = createRequire(import.meta.url)('playwright');
const PORT = 8791, BASE = 'http://127.0.0.1:' + PORT;
const SHOTS = process.env.SHOTS ? new URL('./shots/', import.meta.url).pathname : null;
if (SHOTS) mkdirSync(SHOTS, { recursive: true });

const backend = await createBackend();
const csp = readFileSync(new URL('../../netlify.toml', import.meta.url), 'utf8').match(/Content-Security-Policy = "([^"]+)"/)[1];
const server = await serve(PORT, { '/config.js': "window.WINDY_DESK_CONFIG = { fake: true, passwordDomain: 'comptes.windydev.fr' };" }, { 'content-security-policy': csp });
const browser = await chromium.launch();
const errors = [], pages = [];
const steps = [];
const step = async (name, fn) => { const t = Date.now(); await fn(); steps.push(name); console.log('  ✓ ' + name + ' (' + (Date.now() - t) + ' ms)'); };

async function open(o = {}) {
  const mobile = !!o.mobile;
  const ctx = await browser.newContext({ viewport: mobile ? { width: 390, height: 844 } : { width: 1366, height: 860 }, colorScheme: o.dark ? 'dark' : 'light',
    isMobile: mobile, hasTouch: mobile, reducedMotion: 'reduce', acceptDownloads: true, timezoneId: 'Europe/Paris', locale: 'fr-FR' });
  await ctx.exposeFunction('__backend', backend.handle);
  if (o.session) await ctx.addInitScript(s => { if (!sessionStorage.getItem('windydesk-test-session')) sessionStorage.setItem('windydesk-test-session', s); }, o.session);
  if (o.discord) await ctx.addInitScript(n => sessionStorage.setItem('windydesk-test-discord', n), o.discord);
  const page = await ctx.newPage();
  const tag = o.label || 'page';
  page.setDefaultTimeout(8000);
  pages.push([tag, page]);
  page.on('pageerror', e => errors.push(tag + ' ' + page.url() + ' : ' + e.message));
  page.on('console', m => { if (m.type() === 'error') errors.push(tag + ' ' + page.url() + ' : ' + m.text()); });
  return page;
}
const settle = async (page, sel) => {
  await page.waitForFunction(() => document.querySelector('#app') && document.querySelector('#app').children.length > 0);
  if (sel) await page.locator(sel).first().waitFor({ timeout: 8000 });
  await page.waitForTimeout(200);
};
const toast = (page, re) => page.locator('.toasts .toast', { hasText: re }).first().waitFor({ timeout: 8000 });
const noBadToast = async page => { const bad = await page.locator('.toasts .toast.bad').allInnerTexts(); assert.deepEqual(bad, [], 'message d’erreur affiché : ' + bad.join(' | ')); };
const linkFromModal = async page => { const input = page.locator('.modal input[readonly]'); await input.waitFor(); const v = await input.inputValue(); await page.locator('.modal').getByRole('button', { name: 'Fermer' }).click(); return v; };
const session = page => page.evaluate(() => sessionStorage.getItem('windydesk-test-session'));
const shot = (page, name, full) => SHOTS ? page.screenshot({ path: SHOTS + name + '.png', fullPage: !!full }) : null;
const PW = 'motdepasse-solide';

const S = {};
console.log('Scénario');
try {
  // ------------------------------------------------------------ éditeur
  await step('la page d’accueil s’affiche', async () => {
    const p = await open({ label: 'accueil' });
    await p.goto(BASE + '/'); await settle(p, '.land');
    assert.match(await p.locator('h1').first().innerText(), /\S/);
    await shot(p, 'accueil', true);
    await p.context().close();
  });
  const ed = await open({ label: 'éditeur' });
  await step('l’éditeur ouvre son accès avec le lien à usage unique', async () => {
    const tok = await backend.claim('editeur', 'Windy Dev');
    await ed.goto(BASE + '/reclamer/' + tok); await settle(ed, '.auth');
    await ed.getByLabel('Identifiant', { exact: true }).fill('windy');
    await ed.getByLabel(/Choisis un mot de passe/).fill(PW);
    await ed.getByLabel('Le même, une seconde fois').fill(PW);
    await ed.getByRole('button', { name: 'Créer mon accès' }).click();
    await ed.waitForURL(BASE + '/editeur'); await settle(ed, 'h1:text("Clients")');
    await toast(ed, 'Accès éditeur ouvert');
    S.editeur = await session(ed);
    // le lien ne resservira pas
    const again = await backend.sql('select used_at from public.platform_claims where token = $1', [tok]);
    assert.ok(again[0].used_at);
  });
  await step('l’éditeur crée le client Bella Napoli et reçoit le lien du patron', async () => {
    await ed.getByRole('button', { name: 'Nouveau client' }).click();
    const m = ed.locator('.modal');
    await m.getByLabel('Entreprise').fill('Bella Napoli');
    await m.getByLabel('Activité').fill('Pizzeria');
    await m.getByLabel('Patron', { exact: true }).fill('Gino Romano');
    assert.equal(await m.getByLabel('Adresse (/…)').inputValue(), 'bella-napoli');
    assert.equal(await m.getByLabel('Identifiant du patron').inputValue(), 'gino.romano');
    await m.getByRole('button', { name: 'Créer l’espace' }).click();
    S.ginoLink = await linkFromModal(ed);
    assert.match(S.ginoLink, /\/bella-napoli\/activer\/[0-9a-f]{64}$/);
    await ed.locator('table', { hasText: 'Bella Napoli' }).waitFor();
    await noBadToast(ed);
  });
  await step('l’éditeur crée une association', async () => {
    await ed.getByRole('button', { name: 'Nouveau client' }).click();
    const m = ed.locator('.modal');
    await m.getByLabel('Entreprise').fill('Les Restos du Cœur');
    await m.getByLabel('Patron', { exact: true }).fill('Marie Dubois');
    await m.getByLabel(/Association/).check();
    await m.getByRole('button', { name: 'Créer l’espace' }).click();
    await linkFromModal(ed);
    await ed.locator('table', { hasText: 'Les Restos du Cœur' }).waitFor();
    const [o] = await backend.sql("select code, is_association from public.orgs where name = 'Les Restos du Cœur'");
    assert.deepEqual(o, { code: 'les-restos-du-coeur', is_association: true });
  });
  await step('l’éditeur active un module depuis la grille', async () => {
    await ed.getByRole('link', { name: 'Modules' }).first().click(); await settle(ed, 'table.matrix');
    await ed.getByLabel('Bella Napoli : Véhicules').check();
    await toast(ed, 'Véhicules activé pour Bella Napoli');
    const [o] = await backend.sql("select modules from public.orgs where code = 'bella-napoli'");
    assert.ok(o.modules.includes('vehicules'));
  });
  await step('l’éditeur rédige un devis, l’enregistre, l’exporte en PNG et en PDF', async () => {
    await ed.goto(BASE + '/editeur/devis/nouveau'); await settle(ed, '.qedit');
    await ed.getByLabel('Entreprise').fill('Burger Shot');
    await ed.getByLabel('Contact').fill('Lola Martin');
    await ed.getByRole('button', { name: '12 sem.' }).click();
    await ed.locator('.docimg[src^="data:image/png"]').waitFor();
    await ed.getByRole('button', { name: 'Enregistrer' }).click();
    await ed.waitForURL(/\/editeur\/devis\/[0-9a-f-]{36}$/);
    S.devis = ed.url();
    await toast(ed, /Devis D-.* enregistré/);
    await ed.getByRole('button', { name: 'PNG ou PDF' }).click();
    const m = ed.locator('.modal');
    await m.waitFor();
    const [png] = await Promise.all([ed.waitForEvent('download'), m.getByRole('button', { name: /PNG/ }).click()]);
    assert.match(png.suggestedFilename(), /\.png$/);
    const [pdf] = await Promise.all([ed.waitForEvent('download'), m.getByRole('button', { name: /PDF/ }).click()]);
    assert.match(pdf.suggestedFilename(), /\.pdf$/);
    const head = readFileSync(await pdf.path()).subarray(0, 5).toString();
    assert.equal(head, '%PDF-');
    await ed.keyboard.press('Escape');
    await ed.getByRole('button', { name: 'Marquer envoyé' }).click();
    await toast(ed, 'Devis marqué comme envoyé');
    const [q] = await backend.sql("select status, weeks from public.quotes where client = 'Burger Shot'");
    assert.deepEqual(q, { status: 'envoye', weeks: 12 });
  });

  // ------------------------------------------------------------ le patron
  // la comptabilité de la semaine dernière (en vrai : saisie au fil des jours ; ici écrite d'un coup, à la date voulue)
  await backend.sql(`insert into public.ledger (org_id, at, kind, category, label, amount, by_name)
    select o.id, public.week_of(now()) - 7 + interval '2 days 14 hours', x.kind, x.cat, x.label, x.amount, 'Gino Romano'
    from public.orgs o, (values ('in', 'Ventes', 'Service de la semaine', 12000), ('out', 'Achats', 'Farine et mozzarella', 4000), ('out', 'Divers', 'Retrait d’espèces', 6000)) x(kind, cat, label, amount)
    where o.code = 'bella-napoli'`);
  const gino = await open({ label: 'patron' });
  await step('le patron active son compte avec un mot de passe', async () => {
    await gino.goto(S.ginoLink); await settle(gino, '.auth');
    assert.equal(await gino.getByLabel('Identifiant', { exact: true }).inputValue(), 'gino.romano');
    await gino.getByLabel(/Choisis un mot de passe/).fill(PW);
    await gino.getByLabel('Le même, une seconde fois').fill(PW);
    await gino.getByRole('button', { name: 'Créer mon accès' }).click();
    await gino.waitForURL(BASE + '/bella-napoli'); await settle(gino, 'h1:text("Bella Napoli")');
    await toast(gino, 'Bienvenue dans Bella Napoli');
    await gino.locator('.banner.warn', { hasText: 'Déclaration de la semaine' }).waitFor();
    S.gino = await session(gino);
  });
  await step('le lien d’activation ne sert qu’une fois', async () => {
    const p = await open({ label: 'lien usé' });
    await p.goto(S.ginoLink); await settle(p, '.gate');
    assert.match(await p.locator('h1').innerText(), /Lien déjà utilisé/);
    await p.context().close();
  });
  await step('le patron enregistre une opération', async () => {
    await gino.getByRole('link', { name: /Compta/ }).first().click(); await settle(gino, 'h1:text("Comptabilité")');
    await gino.getByRole('button', { name: 'Ajouter une opération' }).click();
    const m = gino.locator('.modal');
    await m.getByRole('button', { name: 'Entrée' }).click();
    await m.getByLabel('Libellé').fill('Service du midi');
    await m.getByLabel('Montant ($)').fill('850');
    await m.getByRole('button', { name: 'Enregistrer' }).click();
    await toast(gino, 'Opération enregistrée');
    await gino.locator('table', { hasText: 'Service du midi' }).waitFor();
  });
  await step('le patron déclare moins que sa comptabilité, l’écart est signalé', async () => {
    const decl = gino.locator('.todo-decl');
    assert.equal(await decl.getByLabel('Chiffre d’affaires déclaré').inputValue(), '12000');
    assert.equal(await decl.getByLabel('Charges déclarées').inputValue(), '4000');
    await decl.getByLabel('Chiffre d’affaires déclaré').fill('9000');
    await decl.getByText(/Tu déclares 3[\s\u202f\u00a0]000 \$ de moins/).waitFor();
    await decl.getByRole('button', { name: 'Déclarer et payer' }).click();
    await toast(gino, /Semaine \d+ déclarée/);
    await gino.locator('.decl', { hasText: 'Déclarée le' }).waitFor();
    const [d] = await backend.sql('select revenue, tax from public.declarations');
    assert.deepEqual(d, { revenue: '9000.00', tax: '500.00' });
  });
  await step('le patron ajoute une stagiaire et récupère son lien', async () => {
    await gino.getByRole('link', { name: 'Équipe' }).first().click(); await settle(gino, 'h1:text("Équipe")');
    await gino.getByRole('button', { name: 'Ajouter quelqu’un' }).click();
    const m = gino.locator('.modal');
    await m.getByLabel('Nom').fill('Nina Costa');
    assert.equal(await m.getByLabel('Identifiant').inputValue(), 'nina.costa');
    assert.equal(await m.getByLabel('Grade').locator('option:checked').innerText(), 'Stagiaire');
    await m.getByRole('button', { name: 'Créer le compte' }).click();
    S.ninaLink = await linkFromModal(gino);
    await gino.locator('table', { hasText: 'Lien envoyé, pas encore activé' }).waitFor();
  });
  await step('le patron règle les accès des grades et la connexion', async () => {
    await gino.getByRole('link', { name: 'Réglages' }).first().click(); await settle(gino, 'table.matrix');
    await gino.getByLabel('Stagiaire : stock').check();
    await gino.locator('td.saved').first().waitFor();
    await gino.getByLabel('Nouveau grade').fill('Livreur');
    await gino.getByRole('button', { name: 'Créer le grade' }).click();
    await toast(gino, 'Grade créé');
    await gino.locator('th', { hasText: 'Livreur' }).waitFor();
    const [g] = await backend.sql("select caps from public.grades where name = 'Stagiaire'");
    assert.deepEqual(g.caps, ['encaisser', 'vehicules', 'stock']);
    await gino.locator('table', { hasText: 'Nouveau compte : Nina Costa' }).waitFor();
  });

  // ------------------------------------------------------------ la stagiaire, par Discord
  const nina = await open({ label: 'stagiaire', discord: 'nina-discord' });
  await step('la stagiaire active son compte avec Discord et n’a que ses accès', async () => {
    await nina.goto(S.ninaLink); await settle(nina, '.auth');
    await nina.getByRole('button', { name: 'Continuer avec Discord' }).click();
    await nina.getByRole('button', { name: 'Activer avec cette session' }).click();
    await nina.waitForURL(BASE + '/bella-napoli'); await settle(nina, '.shell');
    const navs = await nina.locator('nav.side .navs a').allInnerTexts();
    assert.deepEqual(navs.map(s => s.trim()), ['Accueil', 'Équipe']);
    await nina.locator('.card', { hasText: 'Bienvenue, Nina Costa' }).waitFor();
    await nina.goto(BASE + '/bella-napoli/compta'); await settle(nina, '.shell');
    assert.equal(new URL(nina.url()).pathname, '/bella-napoli');
    // la base refuse aussi, même en passant outre l'écran
    const r = JSON.parse(await nina.evaluate(() => window.__backend(JSON.stringify({ op: 'from', q: { table: 'ledger', filters: [], order: null, limit: null }, uid: JSON.parse(sessionStorage.getItem('windydesk-test-session')).user.id }))));
    assert.deepEqual(r.data, []);
    S.nina = await session(nina);
  });

  // ------------------------------------------------------------ le fisc
  const fisc = await open({ label: 'fisc' });
  await step('le contrôleur du fisc ouvre son accès et voit les signaux', async () => {
    const tok = await backend.claim('fisc', 'Inspecteur Garnier');
    await fisc.goto(BASE + '/reclamer/' + tok); await settle(fisc, '.auth');
    await fisc.getByLabel('Identifiant', { exact: true }).fill('garnier');
    await fisc.getByLabel(/Choisis un mot de passe/).fill(PW);
    await fisc.getByLabel('Le même, une seconde fois').fill(PW);
    await fisc.getByRole('button', { name: 'Créer mon accès' }).click();
    await fisc.waitForURL(BASE + '/fisc'); await settle(fisc, 'h1:text("Fisc")');
    const row = fisc.locator('tr', { hasText: 'Bella Napoli' });
    await row.getByText(/Sous-déclaration de 3[\s\u202f\u00a0]000 \$/).waitFor();
    await row.getByText(/retrait\(s\) d’espèces/).waitFor();
    await row.getByText('Risque élevé').waitFor();
    S.fisc = await session(fisc);
  });
  await step('le fisc consulte la fiche, ouvre un contrôle puis redresse', async () => {
    await fisc.locator('tr', { hasText: 'Bella Napoli' }).click();
    await fisc.waitForURL(BASE + '/fisc/bella-napoli'); await settle(fisc, '.tabs');
    await fisc.getByText(/3[\s\u202f\u00a0]000 \$ non déclarés/).waitFor();
    await fisc.getByRole('button', { name: 'Comptabilité' }).click();
    await fisc.locator('table', { hasText: 'Retrait d’espèces' }).waitFor();
    await fisc.getByRole('button', { name: 'Ouvrir un contrôle' }).click();
    await fisc.locator('.modal').getByLabel('Motif').fill('Écart de 3 000 $ sur la semaine dernière');
    await fisc.locator('.modal').getByRole('button', { name: 'Ouvrir le contrôle' }).click();
    await toast(fisc, 'Contrôle ouvert');
    await fisc.locator('.banner.warn', { hasText: 'Contrôle en cours' }).waitFor();
  });
  await step('le patron voit le contrôle et la consultation du fisc au journal', async () => {
    await gino.goto(BASE + '/bella-napoli'); await settle(gino, '.banner.bad');
    assert.match(await gino.locator('.banner.bad').innerText(), /Contrôle fiscal en cours/);
    await gino.goto(BASE + '/bella-napoli/reglages'); await settle(gino, 'table.matrix');
    await gino.locator('table', { hasText: 'Consultation de la fiche par le fisc' }).waitFor();
  });
  await step('le redressement est débité et clôt le contrôle', async () => {
    await fisc.getByRole('button', { name: 'Redressement' }).click();
    const m = fisc.locator('.modal');
    await m.getByLabel('Montant ($)').fill('1500');
    await m.getByLabel('Motif').fill('CA sous-déclaré');
    await m.getByRole('button', { name: 'Appliquer le redressement' }).click();
    await toast(fisc, 'Redressement appliqué');
    await fisc.getByRole('button', { name: 'Ouvrir un contrôle' }).waitFor();
    const [l] = await backend.sql("select amount, by_name from public.ledger where label like 'Redressement%'");
    assert.deepEqual(l, { amount: '1500.00', by_name: 'Fisc · Inspecteur Garnier' });
  });
  await step('le fisc modifie le barème', async () => {
    await fisc.getByRole('link', { name: 'Barème' }).first().click(); await settle(fisc, 'h1:text("Barème")');
    await fisc.locator('.field', { hasText: 'Taux (%)' }).locator('input').fill('12');
    await fisc.getByRole('button', { name: 'Enregistrer le barème' }).click();
    await toast(fisc, 'Barème enregistré');
    const [r] = await backend.sql('select rate, updated_by from public.tax_rules');
    assert.deepEqual(r, { rate: '0.1200', updated_by: 'Inspecteur Garnier' });
  });
  await step('le fisc n’entre pas dans l’espace éditeur, ni l’entreprise dans le fisc', async () => {
    await fisc.goto(BASE + '/editeur'); await settle(fisc, '.gate');
    assert.match(await fisc.locator('h1').innerText(), /Accès réservé/);
    await nina.goto(BASE + '/fisc'); await settle(nina, '.gate');
    assert.match(await nina.locator('h1').innerText(), /Accès réservé/);
    // connectée sans rôle de plateforme : la page de connexion le dit au lieu de rester muette
    await nina.goto(BASE + '/connexion/plateforme'); await settle(nina, '.gate');
    assert.match(await nina.locator('h1').innerText(), /Connecté, mais sans accès/);
    await nina.getByRole('link', { name: 'Aller à Bella Napoli' }).waitFor();
  });

  // ------------------------------------------------------------ connexion ordinaire
  await step('connexion par l’adresse de l’entreprise, mauvais puis bon mot de passe', async () => {
    const p = await open({ label: 'connexion' });
    await p.goto(BASE + '/connexion'); await settle(p, '.codefield');
    await p.getByLabel('Adresse de ton entreprise').fill('Bella-Napoli');
    await p.getByRole('button', { name: 'Continuer' }).click();
    await p.waitForURL(BASE + '/bella-napoli'); await settle(p, '.auth');
    await p.getByLabel('Identifiant', { exact: true }).fill('gino.romano');
    await p.getByLabel('Mot de passe').fill('pas-le-bon-mdp');
    await p.getByRole('button', { name: 'Se connecter', exact: true }).click();
    await toast(p, 'Identifiant ou mot de passe incorrect');
    await p.getByLabel('Mot de passe').fill(PW);
    await p.getByRole('button', { name: 'Se connecter', exact: true }).click();
    await settle(p, '.shell');
    assert.match(await p.locator('h1').first().innerText(), /Bella Napoli/);
    await p.locator('button.who').click();
    await p.getByRole('button', { name: 'Se déconnecter' }).click();
    await p.waitForURL(BASE + '/bella-napoli'); await settle(p, '.auth');
    await p.context().close();
  });
  await step('nouveau lien : l’ancienne connexion est détachée', async () => {
    await gino.goto(BASE + '/bella-napoli/equipe'); await settle(gino, 'table');
    await gino.locator('tr', { hasText: 'Nina Costa' }).getByRole('button', { name: 'Nouveau lien' }).click();
    await linkFromModal(gino);
    await nina.goto(BASE + '/bella-napoli'); await settle(nina, '.gate');
    assert.match(await nina.locator('h1').innerText(), /Pas d’accès à Bella Napoli/);
  });
  for (const p of [ed, gino, nina, fisc]) await p.context().close();
} catch (e) {
  console.error('\n✗ échec après « ' + (steps.at(-1) || 'début') + ' »\n', e);
  for (const [tag, p] of pages) if (!p.isClosed()) await p.screenshot({ path: new URL('./echec-' + tag.replace(/\W+/g, '-') + '.png', import.meta.url).pathname }).catch(() => {});
  for (const [tag, p] of pages) if (!p.isClosed()) console.error('  ' + tag + ' ' + p.url() + ' messages : ' + (await p.locator('.toasts .toast').allInnerTexts().catch(() => [])).join(' | '));
  if (errors.length) console.error(errors.join('\n'));
  await browser.close(); server.close(); process.exit(1);
}

// ------------------------------------------------------------ revue de toutes les pages
console.log('Revue des pages');
const [devisId] = (await backend.sql('select id from public.quotes')).map(r => r.id);
const PAGES = {
  visiteur: [null, ['/', '/connexion', '/connexion/plateforme', '/bella-napoli', S.ginoLink.replace(BASE, ''), '/nulle-part', '/fisc']],
  patron: [S.gino, ['/bella-napoli', '/bella-napoli/compta', '/bella-napoli/equipe', '/bella-napoli/reglages']],
  fisc: [S.fisc, ['/fisc', '/fisc/bella-napoli', '/fisc/les-restos-du-coeur', '/fisc/bareme']],
  editeur: [S.editeur, ['/editeur', '/editeur/modules', '/editeur/devis', '/editeur/devis/' + devisId, '/editeur/devis/nouveau']],
};
const problems = [];
for (const [variant, o] of [['ordi-clair', {}], ['ordi-sombre', { dark: true }], ['tel-clair', { mobile: true }], ['tel-sombre', { mobile: true, dark: true }]]) {
  for (const [who, [sess, paths]] of Object.entries(PAGES)) {
    const page = await open({ ...o, session: sess, label: variant + ' ' + who });
    for (const path of paths) {
      await page.goto(BASE + path);
      await settle(page);
      await page.waitForTimeout(path.startsWith('/editeur/devis/') ? 500 : 150);
      const r = await page.evaluate(() => {
        const parse = c => { const m = c.match(/[\d.]+/g); return m ? m.map(Number) : [0, 0, 0, 0]; };
        const lum = ([r, g, b]) => [r, g, b].map(v => { v /= 255; return v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4; }).reduce((a, v, i) => a + v * [0.2126, 0.7152, 0.0722][i], 0);
        const bgOf = el => {
          const layers = [];
          for (let e = el; e; e = e.parentElement) { const c = parse(getComputedStyle(e).backgroundColor); const a = c.length > 3 ? c[3] : 1; if (a > 0) { layers.push([...c.slice(0, 3), a]); if (a >= 1) break; } }
          let col = parse(getComputedStyle(document.body).backgroundColor).slice(0, 3);
          for (let i = layers.length - 1; i >= 0; i--) { const [r, g, b, a] = layers[i]; col = [r * a + col[0] * (1 - a), g * a + col[1] * (1 - a), b * a + col[2] * (1 - a)]; }
          return col;
        };
        const fails = [];
        document.querySelectorAll('body *').forEach(el => {
          if (![...el.childNodes].some(t => t.nodeType === 3 && t.textContent.trim())) return;
          const cs = getComputedStyle(el);
          if (cs.visibility === 'hidden' || cs.display === 'none' || Number(cs.opacity) === 0 || el.closest('[hidden],.tip,:disabled,[aria-hidden="true"]')) return;
          if (!el.getBoundingClientRect().width) return;
          const fg = parse(cs.color).slice(0, 3), bg = bgOf(el), L1 = lum(fg), L2 = lum(bg), cr = (Math.max(L1, L2) + 0.05) / (Math.min(L1, L2) + 0.05);
          const big = parseFloat(cs.fontSize) >= 24 || (parseFloat(cs.fontSize) >= 18.66 && Number(cs.fontWeight) >= 700);
          if (cr < (big ? 3 : 4.5)) fails.push(el.tagName.toLowerCase() + '.' + el.className + ' « ' + el.textContent.trim().slice(0, 24) + ' » ' + cr.toFixed(2));
        });
        const overflow = document.documentElement.scrollWidth > innerWidth + 1;
        const wide = overflow ? [...document.querySelectorAll('body *')].filter(e => e.getBoundingClientRect().right > innerWidth + 1 && !e.closest('.fchips,.scroll,.tabs,.tablewrap')).slice(0, 3).map(e => e.tagName.toLowerCase() + '.' + e.className) : [];
        const words = (document.body.innerText.match(/\b(null|undefined|NaN|Invalid Date)\b/g) || []);
        // texte coupé dans un bouton (trop étroit pour son libellé)
        const clipped = [...document.querySelectorAll('.btn, button')].filter(b => b.offsetParent && b.scrollWidth > b.clientWidth + 1 && !b.closest('[hidden]')).map(b => b.textContent.trim().slice(0, 30)).slice(0, 4);
        return { fails: [...new Set(fails)].slice(0, 6), overflow, wide, words, clipped, h1: (document.querySelector('h1') || {}).textContent || '' };
      });
      if (r.fails.length || r.overflow || r.words.length || r.clipped.length) problems.push(variant + ' ' + who + ' ' + path + ' ' + JSON.stringify(r));
      if (SHOTS) await page.screenshot({ path: SHOTS + variant + '-' + who + '-' + (path.replace(/\//g, '_').replace(/[0-9a-f-]{36}|[0-9a-f]{64}/, 'x') || '_') + '.png', fullPage: !o.mobile });
    }
    await page.context().close();
  }
  console.log('  ✓ ' + variant);
}
await browser.close();
server.close();

if (errors.length) problems.push(...errors.map(e => 'ERREUR ' + e));
if (problems.length) { console.error('\n' + problems.join('\n')); process.exit(1); }
console.log('\nTout est bon : ' + steps.length + ' étapes du scénario, ' + Object.values(PAGES).reduce((a, [, p]) => a + p.length, 0) * 4 + ' pages revues.');
