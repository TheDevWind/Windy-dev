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
const overrides = { '/config.js': "window.WINDY_DESK_CONFIG = { fake: true, passwordDomain: 'comptes.windydev.fr' };" };
const server = await serve(PORT, overrides, { 'content-security-policy': csp },
  path => { if (!path.startsWith('/__fake-storage/')) return null; const f = backend.files.get(path.slice('/__fake-storage/'.length)); return f ? { type: f.type, body: f.bytes } : null; });
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
    // pour le banc d'essai, la pizzeria a les dix modules
    for (const m of ['Récolte', 'Factures', 'Dons et distributions']) { await ed.getByLabel('Bella Napoli : ' + m).check(); await toast(ed, m + ' activé pour Bella Napoli'); }
    const [o] = await backend.sql("select modules from public.orgs where code = 'bella-napoli'");
    assert.equal(o.modules.length, 10);
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

  // ------------------------------------------------------------ les modules, avec le patron
  const fillModal = async (page, values) => { const m = page.locator('.modal'); for (const [label, v] of values) await m.getByLabel(label, { exact: true }).fill(String(v)); return m; };
  const newItem = async (page, button, values, done = 'Article créé') => {
    await page.getByRole('button', { name: button }).click();
    const m = await fillModal(page, values);
    await m.getByRole('button', { name: 'Créer l’article' }).click();
    await toast(page, done);
  };
  await step('stock : articles, entrée payée en dépense', async () => {
    await gino.goto(BASE + '/bella-napoli/stock'); await settle(gino, 'h1:text("Stock")');
    await newItem(gino, 'Nouvel article', [['Nom', 'Farine'], ['Unité', 'kg'], ['Coût unitaire ($)', 2]]);
    await gino.locator('table b', { hasText: /^Farine$/ }).first().waitFor();
    await newItem(gino, 'Nouvel article', [['Nom', 'Mozzarella'], ['Unité', 'boule'], ['Seuil d’alerte', 4]]);
    await gino.locator('table b', { hasText: /^Mozzarella$/ }).first().waitFor();
    await newItem(gino, 'Nouvel article', [['Nom', 'Pâte à pizza'], ['Unité', 'pâte']]);
    await gino.locator('table b', { hasText: /^Pâte à pizza$/ }).first().waitFor();
    for (const [name, n, paid] of [['Farine', 10, 30], ['Mozzarella', 6, 0]]) {
      await gino.getByRole('button', { name: 'Mouvement', exact: true }).click();
      const m = gino.locator('.modal');
      const sel = m.getByLabel('Article');
      await sel.selectOption(await sel.locator('option', { hasText: name }).first().getAttribute('value'));
      await fillModal(gino, [['Quantité', n], ['Motif', 'Livraison']]);
      if (paid) await m.getByLabel(/Payé/).fill(String(paid));
      await m.getByRole('button', { name: 'Enregistrer' }).click();
      await toast(gino, new RegExp(name + ' : \\+' + n));
    }
    const [achat] = await backend.sql("select amount from public.ledger where category = 'Achats' and label like 'Achat · 10 kg de Farine'");
    assert.equal(achat.amount, '30.00');
  });
  await step('fabrication : recettes et fournées', async () => {
    await gino.goto(BASE + '/bella-napoli/caisse'); await settle(gino, 'h1:text("Caisse")');
    await newItem(gino, 'Produit à la carte', [['Nom', 'Margherita'], ['Unité', 'pizza'], ['Prix de vente ($)', 18]]);
    await gino.goto(BASE + '/bella-napoli/fabrication'); await settle(gino, 'h1:text("Fabrication")');
    for (const [prod, yld, ins] of [['Pâte à pizza', 4, [['Farine', 1]]], ['Margherita', 1, [['Pâte à pizza', 1], ['Mozzarella', 1]]]]) {
      await gino.getByRole('button', { name: 'Nouvelle recette' }).click();
      const m = gino.locator('.modal');
      await m.getByLabel('Produit fabriqué').selectOption({ label: prod });
      await m.getByLabel('Quantité produite par fournée').fill(String(yld));
      for (let i = 0; i < ins.length; i++) {
        if (i) await m.getByRole('button', { name: 'Ajouter un ingrédient' }).click();
        await m.getByLabel('Ingrédient', { exact: true }).nth(i).selectOption({ label: ins[i][0] });
        await m.getByLabel('Quantité', { exact: true }).nth(i).fill(String(ins[i][1]));
      }
      await m.getByRole('button', { name: 'Enregistrer la recette' }).click();
      await toast(gino, 'Recette enregistrée');
      await settle(gino, '.recipe h2:text("' + prod + '")');
    }
    const card = gino.locator('.recipe', { has: gino.locator('h2', { hasText: 'Pâte à pizza' }) });
    await card.getByLabel('Nombre de fournées').fill('2');
    await card.getByRole('button', { name: 'Fabriquer' }).click();
    await toast(gino, /8 pâte de pâte à pizza fabriqué/);
  });
  await step('caisse : une pizza préparée à la commande sort ses ingrédients du stock', async () => {
    await gino.goto(BASE + '/bella-napoli/caisse'); await settle(gino, '.tilebtn');
    const tile = gino.locator('.tilebtn', { hasText: 'Margherita' });
    await tile.getByText('6 possibles').waitFor();   // 8 pâtes, 6 mozzarellas
    await tile.click(); await tile.click();
    await gino.getByRole('button', { name: /^Encaisser 36/ }).click();
    await toast(gino, /Ticket n°1 encaissé : 36/);
    const st = Object.fromEntries((await backend.sql("select name, qty from public.items where org_id = (select id from public.orgs where code = 'bella-napoli')")).map(r => [r.name, Number(r.qty)]));
    assert.deepEqual([st['Pâte à pizza'], st['Mozzarella']], [6, 4]);
    await gino.locator('table', { hasText: '2 Margherita' }).waitFor();
  });
  await step('service et paie : prise de service, taux horaire, salaires', async () => {
    await gino.goto(BASE + '/bella-napoli/equipe'); await settle(gino, 'table');
    await gino.getByRole('button', { name: 'Prendre mon service' }).click(); await toast(gino, 'Bon service');
    await gino.getByRole('button', { name: 'Finir mon service' }).click(); await toast(gino, 'Service terminé');
    await backend.sql("update public.shifts set started_at = ended_at - interval '2 hours'");
    await gino.locator('tr', { hasText: 'Gino Romano' }).getByRole('button', { name: 'Fixer' }).click();
    await gino.locator('.modal').getByLabel(/Taux/).fill('50');
    await gino.locator('.modal').getByRole('button', { name: 'Enregistrer' }).click();
    await toast(gino, 'Taux horaire de Gino Romano');
    await gino.getByRole('button', { name: /Payer les salaires · 100/ }).click();
    await toast(gino, /Salaires payés : 100/);
  });
  await step('véhicules : ajout, sortie, retour', async () => {
    await gino.goto(BASE + '/bella-napoli/vehicules'); await settle(gino, 'h1:text("Véhicules")');
    await gino.getByRole('button', { name: 'Ajouter un véhicule' }).click();
    await fillModal(gino, [['Modèle', 'Camionnette Rumpo'], ['Plaque', 'bn 201']]);
    await gino.locator('.modal').getByRole('button', { name: 'Ajouter' }).click();
    await toast(gino, 'Véhicule ajouté');
    await gino.locator('.vcard').getByRole('button', { name: 'Sortir' }).click();
    await gino.locator('.modal').getByRole('button', { name: 'Sortir le véhicule' }).click();
    await toast(gino, 'Camionnette Rumpo sorti');
    await gino.locator('.vcard').getByRole('button', { name: 'Rentrer' }).click();
    await gino.locator('.modal').getByRole('button', { name: 'Rentrer au garage' }).click();
    await toast(gino, 'rentré au garage');
    await gino.locator('.plate', { hasText: 'BN 201' }).first().waitFor();
  });
  await step('événement en cours : sa propre page, les ventes s’y rattachent', async () => {
    await gino.goto(BASE + '/bella-napoli/evenements'); await settle(gino, 'h1:text("Événements")');
    await gino.getByRole('button', { name: 'Créer un événement' }).click();
    const start = await gino.evaluate(() => { const d = new Date(Date.now() - 30 * 60e3), p = n => String(n).padStart(2, '0'); return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate()) + 'T' + p(d.getHours()) + ':' + p(d.getMinutes()); });
    await fillModal(gino, [['Nom', 'Soirée karaoké'], ['Début', start], ['Montant visé ($)', 100]]);
    await gino.locator('.modal').getByRole('button', { name: 'Créer l’événement' }).click();
    await gino.waitForURL(/\/bella-napoli\/evenements\/[0-9a-f-]{36}$/); await settle(gino, 'h1:text("Soirée karaoké")');
    S.eventPath = new URL(gino.url()).pathname;
    await gino.getByRole('link', { name: 'Ouvrir la caisse' }).click(); await settle(gino, '.tilebtn');
    await gino.locator('.tilebtn', { hasText: 'Margherita' }).click();
    await gino.getByRole('button', { name: /^Encaisser 18/ }).click();
    await toast(gino, /compté dans l’événement en cours/);
    await gino.goto(BASE + S.eventPath); await settle(gino, '.evhero');
    await gino.locator('.evhero', { hasText: '18 % ' }).or(gino.locator('.evhero', { hasText: '18 %' })).first().waitFor();
  });
  await step('récolte, factures et dons', async () => {
    await gino.goto(BASE + '/bella-napoli/recolte'); await settle(gino, 'h1:text("Récolte")');
    await newItem(gino, 'Produit récoltable', [['Nom', 'Basilic'], ['Unité', 'botte']]);
    await settle(gino, 'h2:text("Nouvelle récolte")');
    await gino.locator('.card', { hasText: 'Nouvelle récolte' }).getByLabel('Quantité').fill('20');
    await gino.getByRole('button', { name: 'Enregistrer' }).click();
    await toast(gino, /Prime : 10,00/);
    await gino.goto(BASE + '/bella-napoli/factures'); await settle(gino, 'h1:text("Factures")');
    await gino.getByRole('button', { name: 'Nouvelle facture' }).click();
    const m = gino.locator('.modal');
    await m.getByLabel('Client', { exact: true }).fill('Garage Benny');
    await m.getByLabel('Libellé').fill('Traiteur pour la soirée');
    await m.getByLabel('Prix ($)').fill('250');
    await m.getByRole('button', { name: 'Émettre la facture' }).click();
    await toast(gino, 'Facture F-0001 émise');
    await gino.getByRole('button', { name: 'Marquer payée' }).click();
    await toast(gino, /F-0001 payée : 250/);
    await gino.goto(BASE + '/bella-napoli/dons'); await settle(gino, 'h1:text("Dons et distributions")');
    await gino.getByRole('button', { name: 'Enregistrer un don' }).click();
    await fillModal(gino, [['Donateur', 'Benny'], ['Montant ($)', 100]]);
    await gino.locator('.modal').getByRole('button', { name: 'Enregistrer le don' }).click();
    await toast(gino, 'Don de Benny enregistré');
    const cats = (await backend.sql("select category, sum(amount)::text s from public.ledger where org_id = (select id from public.orgs where code = 'bella-napoli') group by 1 order by 1")).map(r => r.category + ' ' + r.s);
    for (const c of ['Dons 100.00', 'Factures 250.00', 'Salaires 100.00']) assert.ok(cats.includes(c), c + ' absent de ' + cats.join(', '));
  });

  // ------------------------------------------------------------ la stagiaire, par Discord
  const nina = await open({ label: 'stagiaire', discord: 'nina-discord' });
  await step('la stagiaire active son compte avec Discord et n’a que ses accès', async () => {
    await nina.goto(S.ninaLink); await settle(nina, '.auth');
    await nina.getByRole('button', { name: 'Continuer avec Discord' }).click();
    await nina.getByRole('button', { name: 'Activer avec cette session' }).click();
    await nina.waitForURL(BASE + '/bella-napoli'); await settle(nina, '.shell');
    const navs = await nina.locator('nav.side .navs a').allInnerTexts();
    // le menu suit les modules de l'offre et les accès du grade Stagiaire (encaisser, véhicules, stock)
    assert.deepEqual(navs.map(s => s.trim()), ['Accueil', 'Caisse', 'Stock', 'Récolte', 'Dons', 'Véhicules', 'Événements', 'Équipe']);
    await nina.locator('.card', { hasText: 'Bienvenue, Nina Costa' }).waitFor();
    S.ninaId = (await backend.sql("select id from public.members where login = 'nina.costa'"))[0].id;
    await nina.goto(BASE + '/bella-napoli/compta'); await settle(nina, '.shell');
    assert.equal(new URL(nina.url()).pathname, '/bella-napoli');
    // la base refuse aussi, même en passant outre l'écran
    const r = JSON.parse(await nina.evaluate(() => window.__backend(JSON.stringify({ op: 'from', q: { table: 'ledger', filters: [], order: null, limit: null }, uid: JSON.parse(sessionStorage.getItem('windydesk-test-session')).user.id }))));
    assert.deepEqual(r.data, []);
    S.nina = await session(nina);
  });

  await step('fiche employé : avertissement visible par l’intéressée', async () => {
    await gino.goto(BASE + '/bella-napoli/equipe'); await settle(gino, 'table');
    await gino.locator('tr', { hasText: 'Nina Costa' }).click();
    await gino.waitForURL(BASE + '/bella-napoli/equipe/' + S.ninaId); await settle(gino, 'h1:text("Nina Costa")');
    await gino.getByRole('button', { name: 'Avertir' }).click();
    await gino.locator('.modal').getByLabel('Motif').fill('Retard de deux heures au service');
    await gino.locator('.modal').getByRole('button', { name: 'Donner l’avertissement' }).click();
    await toast(gino, 'Avertissement enregistré');
    const w = JSON.parse(await nina.evaluate(() => window.__backend(JSON.stringify({ op: 'from', q: { table: 'warnings', filters: [], order: null, limit: null }, uid: JSON.parse(sessionStorage.getItem('windydesk-test-session')).user.id }))));
    assert.equal(w.data.length, 1);
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

  // ------------------------------------------------------------ services publics : les US Marshals
  const PNG = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==', 'base64');
  const photo = name => ({ name, mimeType: 'image/png', buffer: PNG });
  // première connexion à un service : nom de signature et modèle, avant d'entrer dans le MDT
  const chooseSignature = async (p, name, model) => {
    await settle(p, '.sigpage');
    await p.getByLabel('Nom de signature').fill(name);
    await p.getByRole('radio', { name: 'Modèle ' + model }).click();
    await shot(p, 'signature-' + model.toLowerCase());
    await p.getByRole('button', { name: 'Enregistrer ma signature' }).click();
    await settle(p, '.dutycard');
  };
  await step('l’éditeur crée le service US Marshals et un accès Justice', async () => {
    await ed.goto(BASE + '/editeur'); await settle(ed, 'h1:text("Clients")');
    await ed.getByRole('button', { name: 'Nouveau client' }).click();
    const m = ed.locator('.modal');
    await m.getByRole('button', { name: 'Service public (US Marshals)' }).click();
    await m.getByLabel('Service', { exact: true }).fill('US Marshals · Los Santos');
    await m.getByLabel('Adresse (/…)').fill('usms-ls');
    await m.getByLabel('Director', { exact: true }).fill('Jack Reed');
    assert.equal(await m.getByLabel('Identifiant du Director').inputValue(), 'jack.reed');
    await m.getByRole('button', { name: 'Créer l’espace' }).click();
    S.reedLink = await linkFromModal(ed);
    await ed.locator('table', { hasText: 'US Marshals · Los Santos' }).waitFor();
    const [o] = await backend.sql("select sector, modules from public.orgs where code = 'usms-ls'");
    assert.equal(o.sector, 'service_public'); assert.equal(o.modules.length, 18);
    await ed.getByRole('link', { name: 'Accès' }).first().click(); await settle(ed, 'h1:text("Accès")');
    await ed.getByLabel('Nom affiché').fill('Juge Hartley');
    await ed.getByRole('button', { name: 'Créer le lien' }).click();
    S.judgeLink = await linkFromModal(ed);
    assert.match(S.judgeLink, /\/reclamer\/[0-9a-f]{64}$/);
    await ed.locator('table', { hasText: 'Juge Hartley' }).waitFor();
  });
  const reed = await open({ label: 'director' });
  await step('le Director active son compte, prend son service et passe en intervention', async () => {
    await reed.goto(S.reedLink); await settle(reed, '.auth');
    assert.equal(await reed.getByLabel('Identifiant', { exact: true }).inputValue(), 'jack.reed');
    await reed.getByLabel(/Choisis un mot de passe/).fill(PW);
    await reed.getByLabel('Le même, une seconde fois').fill(PW);
    await reed.getByRole('button', { name: 'Créer mon accès' }).click();
    await reed.waitForURL(BASE + '/usms-ls'); await chooseSignature(reed, 'J. Reed', 'Rapide');
    await reed.getByRole('button', { name: 'Prendre mon service' }).click();
    await toast(reed, 'Bon service');
    await reed.getByRole('button', { name: 'En intervention' }).click();
    await toast(reed, 'Statut : en intervention');
    S.reed = await session(reed);
  });
  await step('le Director recrute un Supervisory ; un grade plein est refusé', async () => {
    await reed.getByRole('link', { name: 'Effectifs' }).first().click(); await settle(reed, 'h1:text("Effectifs")');
    const m = reed.locator('.modal');
    await reed.getByRole('button', { name: 'Recruter' }).click();
    await m.getByLabel('Nom', { exact: true }).fill('Sam Ortiz');
    await m.getByLabel('Grade').selectOption({ label: 'Supervisory (2 places)' });
    await m.getByLabel('Indicatif').fill('Marshal-3');
    await m.getByRole('button', { name: 'Recruter' }).click();
    S.samLink = await linkFromModal(reed);
    await reed.getByRole('button', { name: 'Recruter' }).click();
    await m.getByLabel('Nom', { exact: true }).fill('Bob Two');
    await m.getByLabel('Grade').selectOption({ label: 'Director (1 place)' });
    await m.getByRole('button', { name: 'Recruter' }).click();
    await toast(reed, 'Plus de place en Director : 1/1');
    await reed.keyboard.press('Escape');
    const [x] = await backend.sql("select matricule, stage, callsign from public.members where login = 'sam.ortiz'");
    assert.deepEqual(x, { matricule: 'USMS-0002', stage: 'titulaire', callsign: 'Marshal-3' });
  });
  const sam = await open({ label: 'supervisory', discord: 'sam-discord' });
  await step('le Supervisory active son compte avec Discord et recrute un Deputy', async () => {
    await sam.goto(S.samLink); await settle(sam, '.auth');
    await sam.getByRole('button', { name: 'Continuer avec Discord' }).click();
    await sam.getByRole('button', { name: 'Activer avec cette session' }).click();
    await sam.waitForURL(BASE + '/usms-ls'); await chooseSignature(sam, 'S. Ortiz', 'Paraphe');
    const navs = await sam.locator('nav.side .navs a').allInnerTexts();
    assert.deepEqual(navs.map(x => x.trim()), ['Accueil', 'Mandats', 'Most Wanted', 'Carte', 'Plans d’intervention', 'Transferts', 'SOG', 'Recherche', 'Dossiers', 'Rapports', 'Preuves', 'Renseignement', 'Tribunal', 'Effectifs', 'Présence',
      'Académie', 'Logistique', 'Statistiques', 'Annonces']);
    await sam.goto(BASE + '/usms-ls/effectifs'); await settle(sam, 'h1:text("Effectifs")');
    await sam.getByRole('button', { name: 'Recruter' }).click();
    const m = sam.locator('.modal');
    await m.getByLabel('Nom', { exact: true }).fill('Dan Moss');
    await m.getByLabel('Grade').selectOption({ label: 'Deputy (12 places)' });
    await m.getByLabel('Indicatif').fill('Marshal-12');
    await m.getByRole('button', { name: 'Recruter' }).click();
    S.danLink = await linkFromModal(sam);
    S.sam = await session(sam);
  });
  const dan = await open({ label: 'deputy' });
  await step('le Deputy active son compte, ajoute sa photo et prend son service', async () => {
    await dan.goto(S.danLink); await settle(dan, '.auth');
    await dan.getByLabel(/Choisis un mot de passe/).fill(PW);
    await dan.getByLabel('Le même, une seconde fois').fill(PW);
    await dan.getByRole('button', { name: 'Créer mon accès' }).click();
    await dan.waitForURL(BASE + '/usms-ls'); await chooseSignature(dan, 'Dan Moss', 'Classique');
    S.danId = (await backend.sql("select id from public.members where login = 'dan.moss'"))[0].id;
    await dan.goto(BASE + '/usms-ls/effectifs/' + S.danId); await settle(dan, 'h1:text("Dan Moss")');
    await dan.getByRole('button', { name: 'Modifier ma fiche' }).click();
    const m = dan.locator('.modal');
    await m.getByLabel('Téléphone').fill('555-0112');
    await m.getByLabel(/^Photo/).setInputFiles(photo('dan.png'));
    await m.getByRole('button', { name: 'Enregistrer' }).click();
    await toast(dan, 'Fiche enregistrée');
    await dan.locator('.idcard img.ava').waitFor();
    await dan.goto(BASE + '/usms-ls'); await settle(dan, '.dutycard');
    await dan.getByRole('button', { name: 'Prendre mon service' }).click();
    await toast(dan, 'Bon service');
    S.dan = await session(dan);
  });
  await step('présence : planning, inscription et absence validée par un responsable', async () => {
    await sam.goto(BASE + '/usms-ls/presence'); await settle(sam, 'h1:text("Présence")');
    await sam.locator('table', { hasText: 'Dan Moss' }).waitFor();
    await sam.getByRole('button', { name: 'Planifier' }).click();
    const m = sam.locator('.modal');
    await m.getByLabel('Titre').fill('Patrouille Vinewood');
    await m.getByLabel('Lieu', { exact: true }).fill('Vinewood Hills');
    await m.getByRole('button', { name: 'Planifier' }).click();
    await toast(sam, 'Créneau planifié');
    await dan.goto(BASE + '/usms-ls/presence'); await settle(dan, 'h1:text("Présence")');
    await dan.getByRole('tab', { name: /Planning/ }).click();
    await dan.getByRole('button', { name: 'Je participe' }).click();
    await toast(dan, 'Inscrit');
    await dan.getByRole('button', { name: 'Déclarer une absence' }).click();
    await dan.locator('.modal').getByLabel('Motif').fill('Vacances');
    await dan.locator('.modal').getByRole('button', { name: 'Déclarer', exact: true }).click();
    await toast(dan, 'Absence déclarée');
    await sam.reload(); await settle(sam, 'h1:text("Présence")');
    await sam.getByRole('tab', { name: /Absences/ }).click();
    await sam.getByRole('button', { name: 'Valider', exact: true }).click();
    await toast(sam, 'Absence validée');
  });
  await step('dossiers : individu avec photo, dossier numéroté, journal et pièce jointe', async () => {
    await dan.goto(BASE + '/usms-ls/dossiers'); await settle(dan, 'h1:text("Dossiers")');
    await dan.getByRole('button', { name: 'Nouvel individu' }).click();
    const m = dan.locator('.modal');
    await m.getByLabel('Prénom').fill('Tony');
    await m.getByLabel('Nom', { exact: true }).fill('Vercetti');
    await m.getByLabel('Surnom').fill('Le Baron');
    await m.getByLabel('Taille').fill('182');
    await m.getByLabel('Dangerosité').selectOption('elevee');
    await m.getByLabel('Gang / organisation').fill('Vagos');
    await m.getByRole('button', { name: 'Véhicule', exact: true }).click();
    await m.getByLabel('Plaque').fill('7ABC123');
    await m.getByLabel('Modèle').fill('Sultan');
    await m.getByLabel('Photo', { exact: true }).setInputFiles(photo('tony.png'));
    await m.getByRole('button', { name: 'Créer la fiche' }).click();
    await dan.waitForURL(/\/usms-ls\/individus\/[0-9a-f-]{36}$/); await settle(dan, 'h1:text("Tony Vercetti")');
    S.personPath = new URL(dan.url()).pathname;
    await dan.getByText('7ABC123').waitFor();
    await dan.locator('.idcard img.ava').waitFor();
    await dan.getByRole('button', { name: 'Ouvrir un dossier' }).click();
    const c = dan.locator('.modal');
    await c.getByLabel('Titre').fill('Évasion de Bolingbroke');
    await c.getByLabel('Priorité').selectOption('haute');
    await c.getByRole('button', { name: 'Ouvrir le dossier' }).click();
    await dan.waitForURL(/\/usms-ls\/dossiers\/[0-9a-f-]{36}$/); await settle(dan, 'h1:text("USMS-IOD-0001")');
    S.casePath = new URL(dan.url()).pathname;
    await dan.getByRole('button', { name: 'Piste', exact: true }).click();
    await dan.getByLabel('Nouvelle entrée du journal').fill('Vu au Yellow Jack avec un complice');
    await dan.getByRole('button', { name: 'Ajouter au journal' }).click();
    await toast(dan, 'Ajouté au journal');
    await dan.locator('.feed', { hasText: 'Yellow Jack' }).waitFor();
    await dan.getByRole('button', { name: 'Pièce jointe' }).click();
    await dan.locator('.modal').getByLabel(/Photo, vidéo/).setInputFiles(photo('camera.png'));
    await dan.locator('.modal').getByRole('button', { name: 'Joindre' }).click();
    await toast(dan, 'Pièce jointe ajoutée');
    await dan.locator('.filecard img').waitFor();
    // un agent d'un autre service ne lit pas ces fichiers
    const [f] = await backend.sql('select path from public.case_files');
    const r = JSON.parse(await gino.evaluate(p => window.__backend(JSON.stringify({ op: 'signed', bucket: 'mdt', paths: [p], uid: JSON.parse(sessionStorage.getItem('windydesk-test-session')).user.id })), f.path));
    assert.equal(r.data[0].signedUrl, null);
  });
  let judge;
  await step('mandat : demandé par le Supervisory, signé par la Justice, exécuté par le Deputy', async () => {
    await sam.goto(BASE + S.casePath); await settle(sam, 'h1:text("USMS-IOD-0001")');
    await sam.getByRole('button', { name: 'Demander un mandat' }).click();
    const m = sam.locator('.modal');
    await m.getByLabel('Lieu', { exact: true }).fill('Sandy Shores');
    await m.getByLabel('Motif').fill('Évadé de Bolingbroke, armé et dangereux, localisé à Sandy Shores.');
    await m.getByRole('button', { name: 'Envoyer à la Justice' }).click();
    await toast(sam, 'Demande envoyée à la Justice');
    judge = await open({ label: 'justice' });
    await judge.goto(S.judgeLink); await settle(judge, '.auth');
    await judge.getByLabel('Identifiant', { exact: true }).fill('hartley');
    await judge.getByLabel(/Choisis un mot de passe/).fill(PW);
    await judge.getByLabel('Le même, une seconde fois').fill(PW);
    await judge.getByRole('button', { name: 'Créer mon accès' }).click();
    await judge.waitForURL(BASE + '/justice'); await settle(judge, '.wcard');
    await toast(judge, 'Accès de la Justice ouvert');
    await judge.locator('.wcard', { hasText: 'Tony Vercetti' }).waitFor();
    await judge.getByRole('button', { name: 'Signer', exact: true }).click();
    await judge.locator('.modal').getByRole('button', { name: 'Signer le mandat' }).click();
    await toast(judge, 'USMS-M-0001 signé');
    S.judge = await session(judge);
    await dan.goto(BASE + '/usms-ls/mandats'); await settle(dan, 'h1:text("Mandats")');
    await dan.getByRole('button', { name: 'Exécuter', exact: true }).click();
    await dan.locator('.modal').getByRole('button', { name: 'Mandat exécuté' }).click();
    await toast(dan, 'USMS-M-0001 exécuté');
    const [w] = await backend.sql('select status, decided_by, executed_by from public.warrants');
    assert.deepEqual(w, { status: 'execute', decided_by: 'Juge Hartley', executed_by: 'Dan Moss' });
  });
  await step('Most Wanted : classement, affiche publiée, signalement anonyme reçu', async () => {
    await sam.goto(BASE + '/usms-ls/wanted'); await settle(sam, 'h1:text("Most Wanted")');
    await sam.getByRole('button', { name: 'Ajouter', exact: true }).click();
    const m = sam.locator('.modal');
    await m.getByLabel('Récompense ($)').fill('50000');
    await m.getByLabel('Motif').fill('Évasion, vol à main armée');
    await m.getByRole('button', { name: 'Ajouter au classement' }).click();
    await toast(sam, 'Classement mis à jour');
    await sam.getByRole('button', { name: 'Affiche', exact: true }).click();
    await sam.locator('.modal img.posterimg[src^="data:image/png"]').waitFor();
    const [png] = await Promise.all([sam.waitForEvent('download'), sam.locator('.modal').getByRole('button', { name: /Télécharger/ }).click()]);
    assert.equal(png.suggestedFilename(), 'most-wanted-1-tony-vercetti.png');
    await sam.locator('.modal').getByRole('button', { name: 'Publier sur la page publique' }).click();
    await toast(sam, 'Affiche publiée');
    const visitor = await open({ label: 'visiteur avis' });
    await visitor.goto(BASE + '/avis/usms-ls'); await settle(visitor, '.avis');
    await visitor.locator('.avis-card', { hasText: 'Tony Vercetti' }).locator('img').waitFor();
    await visitor.getByLabel('Au sujet de').selectOption({ label: '1. Tony Vercetti' });
    await visitor.getByLabel('Ce que tu as vu').fill('Je l’ai vu ce soir au Yellow Jack, en Sultan noire.');
    await visitor.getByRole('button', { name: 'Envoyer le signalement' }).click();
    await toast(visitor, 'Signalement envoyé');
    await shot(visitor, 'avis-public', true);
    await visitor.context().close();
    await sam.reload(); await settle(sam, 'h1:text("Most Wanted")');
    await sam.getByRole('tab', { name: /Signalements/ }).click();
    await sam.locator('table', { hasText: 'Yellow Jack' }).waitFor();
    await sam.getByRole('button', { name: 'Utile', exact: true }).click();
    await toast(sam, 'Signalement mis à jour');
  });
  await step('annonce urgente : affichée à l’agent jusqu’à ce qu’il la lise', async () => {
    await reed.goto(BASE + '/usms-ls/annonces'); await settle(reed, 'h1:text("Annonces")');
    await reed.getByRole('button', { name: 'Nouvelle annonce' }).click();
    const m = reed.locator('.modal');
    await m.getByRole('button', { name: 'Urgent', exact: true }).click();
    await m.getByLabel('Titre').fill('Briefing SOG');
    await m.getByLabel('Annonce', { exact: true }).fill('Réunion de toutes les unités à 20 h au QG.');
    await m.getByRole('button', { name: 'Publier', exact: true }).click();
    await toast(reed, 'Annonce publiée');
    await dan.goto(BASE + '/usms-ls'); await settle(dan, '.banner.bad');
    await dan.locator('.banner.bad').getByRole('button', { name: 'J’ai lu' }).click();
    await toast(dan, 'Annonce marquée comme lue');
    await reed.reload(); await settle(reed, '.ann');
    await reed.getByText('Lu par 2 / 3 agent(s)').waitFor();
  });
  await step('carrière : promotion, unités et qualification données par le Director', async () => {
    await reed.goto(BASE + '/usms-ls/effectifs/' + S.danId); await settle(reed, 'h1:text("Dan Moss")');
    await reed.getByRole('button', { name: 'Grade', exact: true }).click();
    await reed.locator('.modal').getByLabel('Nouveau grade').selectOption({ label: 'Inspector (promotion)' });
    await reed.locator('.modal').getByRole('button', { name: 'Changer de grade' }).click();
    await toast(reed, 'Grade mis à jour');
    await reed.getByRole('button', { name: 'Unités', exact: true }).click();
    const m = reed.locator('.modal');
    await m.getByLabel(/^IOD/).check();
    await m.getByLabel('Unité principale : IOD').check();
    await m.getByLabel(/^SOG/).check();
    await m.getByRole('button', { name: 'Enregistrer' }).click();
    await toast(reed, 'Unités enregistrées');
    await reed.getByLabel('SOG', { exact: true }).check();
    await toast(reed, 'SOG obtenue');
    await reed.locator('.feed', { hasText: 'Deputy → Inspector' }).waitFor();
    const units = await backend.sql("select u.code, mu.main from public.member_units mu join public.units u on u.id = mu.unit_id where mu.member_id = $1 order by u.code", [S.danId]);
    assert.deepEqual(units, [{ code: 'IOD', main: true }, { code: 'SOG', main: false }]);
  });

  await step('rapport d’arrestation : formulaire guidé, peine calculée, validation, document officiel, casier', async () => {
    await dan.goto(BASE + S.casePath); await settle(dan, 'h1:text("USMS-IOD-0001")');
    await dan.getByRole('button', { name: 'Rédiger un rapport' }).click();
    await dan.waitForURL(BASE + '/usms-ls/rapports/nouveau'); await settle(dan, '.charges');
    assert.equal(await dan.getByLabel('Titre', { exact: true }).inputValue(), 'Rapport d’arrestation · Tony Vercetti');
    await dan.getByLabel('Lieu (obligatoire)').fill('Sandy Shores');
    await dan.getByLabel('Chercher une infraction').fill('évasion');
    await dan.getByRole('button', { name: 'Ajouter CR-07' }).click();
    await dan.getByLabel('Chercher une infraction').fill('port d');
    await dan.getByRole('button', { name: 'Ajouter D-01' }).click();
    await dan.getByRole('button', { name: 'Ajouter D-01' }).click();
    await dan.getByText(/Amende totale : 30\s000 \$ · Peine totale : 65 mois/).waitFor();
    await dan.getByRole('button', { name: 'Soumettre pour validation' }).click();
    await toast(dan, 'au moins 20 caractères');
    await dan.waitForURL(/\/usms-ls\/rapports\/[0-9a-f-]{36}$/); await settle(dan, '.charges');
    S.reportPath = new URL(dan.url()).pathname;
    await dan.getByLabel(/Droits lus/).check();
    await dan.getByLabel('Récit').fill('Interpellé à Sandy Shores après une courte course-poursuite, sans blessé.');
    await dan.getByRole('button', { name: 'Soumettre pour validation' }).click();
    await toast(dan, /USMS-A-0001 soumis pour validation/);
    await dan.locator('h1', { hasText: 'USMS-A-0001' }).waitFor();
    await sam.goto(BASE + S.reportPath); await settle(sam, '.reviewcard');
    await sam.locator('.reviewcard .sig', { hasText: 'S. Ortiz' }).waitFor();
    await sam.getByRole('button', { name: 'Signer et valider' }).click();
    await toast(sam, 'USMS-A-0001 validé et signé');
    await sam.locator('.signbox', { hasText: 'Superviseur' }).locator('.sig', { hasText: 'S. Ortiz' }).waitFor();
    const [sg] = await backend.sql("select author_signature->>'style' a, reviewer_signature->>'name' b from public.reports where num = 'USMS-A-0001'");
    assert.deepEqual(sg, { a: 'classique', b: 'S. Ortiz' });
    await sam.getByRole('button', { name: 'PNG ou PDF' }).click();
    await sam.locator('.modal .docimg[src^="data:image/png"]').waitFor();
    const [png] = await Promise.all([sam.waitForEvent('download'), sam.locator('.modal').getByRole('button', { name: 'PNG', exact: true }).click()]);
    assert.equal(png.suggestedFilename(), 'usms-a-0001.png');
    if (SHOTS) await png.saveAs(SHOTS + 'rapport-document.png');
    await shot(sam, 'rapport-export');
    await sam.keyboard.press('Escape');
    const [r] = await backend.sql("select total_fine::int f, total_jail j, status from public.reports where num = 'USMS-A-0001'");
    assert.deepEqual(r, { f: 30000, j: 65, status: 'valide' });
    await sam.goto(BASE + S.personPath); await settle(sam, 'h1:text("Tony Vercetti")');
    await sam.locator('.card', { hasText: 'Casier judiciaire' }).getByText('USMS-A-0001').waitFor();
  });
  await step('preuve : scellé avec photo, chaîne de possession, registre', async () => {
    await dan.goto(BASE + S.casePath); await settle(dan, 'h1:text("USMS-IOD-0001")');
    await dan.getByRole('button', { name: 'Enregistrer une saisie' }).click();
    const m = dan.locator('.modal');
    await m.getByLabel('Description').fill('Pistolet 9 mm, numéro limé');
    await m.getByLabel('Emplacement de stockage').fill('Armurerie, casier 4');
    await m.getByLabel('Photo', { exact: true }).setInputFiles(photo('arme.png'));
    await m.getByRole('button', { name: 'Placer sous scellé' }).click();
    await dan.waitForURL(/\/usms-ls\/preuves\/[0-9a-f-]{36}$/); await settle(dan, 'h1:text("USMS-P-0001")');
    S.evidencePath = new URL(dan.url()).pathname;
    await dan.locator('img.evphoto').waitFor();
    await dan.getByRole('button', { name: 'Transférer' }).click();
    await dan.locator('.modal').getByLabel('À qui ou où ?').fill('Remise à l’Inspector pour expertise');
    await dan.locator('.modal').getByRole('button', { name: 'Transférer' }).click();
    await toast(dan, 'Chaîne de possession mise à jour');
    await dan.locator('.feed', { hasText: 'Remise à l’Inspector' }).waitFor();
    await dan.goto(BASE + '/usms-ls/preuves'); await settle(dan, 'h1:text("Preuves")');
    await dan.locator('table', { hasText: 'USMS-P-0001' }).waitFor();
  });
  await step('recherche globale : plaque, fiche véhicule, organisation', async () => {
    await sam.goto(BASE + '/usms-ls/recherche'); await settle(sam, '.searchcard');
    await sam.getByLabel(/Nom, surnom, plaque/).fill('7abc');
    await sam.locator('.results a', { hasText: '7ABC123' }).click();
    await sam.waitForURL(/\/usms-ls\/vehicules\/[0-9a-f-]{36}$/); await settle(sam, 'h1:text("7ABC123")');
    S.platePath = new URL(sam.url()).pathname;
    await sam.getByRole('link', { name: 'Tony Vercetti' }).waitFor();
    await sam.getByRole('button', { name: 'Observation' }).click();
    await sam.locator('.modal').getByLabel('Texte').fill('Vue à Paleto Bay');
    await sam.locator('.modal').getByRole('button', { name: 'Ajouter' }).click();
    await toast(sam, 'Ajouté à l’historique');
    await sam.locator('.feed', { hasText: 'Paleto Bay' }).waitFor();
    await sam.goto(BASE + '/usms-ls/recherche'); await settle(sam, '.searchcard');
    await sam.getByLabel(/Nom, surnom, plaque/).fill('vagos');
    await sam.locator('.results a', { hasText: 'Vagos' }).click();
    await sam.waitForURL(/\/usms-ls\/organisations\/[0-9a-f-]{36}$/); await settle(sam, 'h1:text("Vagos")');
    S.gangPath = new URL(sam.url()).pathname;
    await sam.locator('.card', { hasText: 'Membres connus' }).getByText('Tony Vercetti').waitFor();
  });

  await step('transfert : ordre, convoi alerté, départ et arrivée, registre des détenus', async () => {
    await sam.goto(BASE + '/usms-ls/transferts'); await settle(sam, 'h1:text("Transferts")');
    await sam.getByRole('button', { name: 'Ordre de transfert' }).click();
    const m = sam.locator('.modal');
    await m.getByLabel('Détenu').selectOption({ label: 'Tony Vercetti « Le Baron »' });
    await m.getByLabel('Départ', { exact: true }).fill('Mission Row');
    await m.getByLabel('Arrivée', { exact: true }).fill('Bolingbroke');
    await m.getByLabel('Chef de convoi').selectOption({ label: 'Marshal-12 · Dan Moss' });
    await m.getByLabel(/Itinéraire principal/).fill('Route 68 puis Senora Freeway');
    await m.getByRole('button', { name: 'Créer l’ordre de transfert' }).click();
    await toast(sam, /USMS-TR-0001 planifié/);
    await dan.goto(BASE + '/usms-ls'); await settle(dan, '.alertcard');
    await dan.locator('.alertcard', { hasText: 'USMS-TR-0001' }).waitFor();
    await dan.goto(BASE + '/usms-ls/transferts'); await settle(dan, 'h1:text("Transferts")');
    await dan.getByRole('button', { name: 'Départ', exact: true }).click();
    await toast(dan, 'USMS-TR-0001 : en route');
    await dan.getByRole('button', { name: 'Arrivé', exact: true }).click();
    await toast(dan, 'USMS-TR-0001 : arrivé');
    await dan.getByRole('tab', { name: /Registre/ }).click();
    await dan.locator('table', { hasText: 'Bolingbroke' }).waitFor();
  });
  await step('tribunal : audience, plan de sécurité, agent affecté prévenu', async () => {
    await sam.goto(BASE + '/usms-ls/tribunal'); await settle(sam, 'h1:text("Tribunal")');
    await sam.getByRole('button', { name: 'Nouvelle audience' }).click();
    const m = sam.locator('.modal');
    await m.getByLabel('Salle').fill('Salle 2');
    await m.getByLabel('Accusé').selectOption({ label: 'Tony Vercetti « Le Baron »' });
    await m.getByLabel('Juge', { exact: true }).fill('Hartley');
    await m.getByRole('button', { name: 'Programmer' }).click();
    await toast(sam, 'Audience programmée');
    await sam.getByRole('button', { name: 'Plan de sécurité' }).click();
    const p = sam.locator('.modal');
    await p.getByLabel('Agent du poste').first().selectOption({ label: 'Marshal-12 · Dan Moss' });
    await p.getByRole('button', { name: 'Enregistrer le plan' }).click();
    await toast(sam, 'Plan de sécurité enregistré');
    await dan.goto(BASE + '/usms-ls/tribunal'); await settle(dan, 'h1:text("Tribunal")');
    await dan.locator('.banner', { hasText: 'Tu es affecté' }).waitFor();
  });
  await step('WITSEC : témoin sous code, vrai nom révélé avec motif, journal d’audit, menu réservé', async () => {
    assert.ok(!(await dan.locator('nav.side .navs a').allInnerTexts()).some(x => x.includes('WITSEC')));
    await reed.goto(BASE + '/usms-ls/witsec'); await settle(reed, 'h1:text("WITSEC")');
    await reed.getByRole('button', { name: 'Nouveau témoin' }).click();
    const m = reed.locator('.modal');
    await m.getByLabel('Vrai nom').fill('Lucia Moretti');
    await m.getByLabel('Témoigne contre').fill('Tony Vercetti');
    await m.getByRole('button', { name: 'Placer sous protection' }).click();
    await reed.waitForURL(/\/usms-ls\/witsec\/[0-9a-f-]{36}$/); await settle(reed, 'h1:text("WS-ALPHA-01")');
    S.witnessPath = new URL(reed.url()).pathname;
    assert.equal(await reed.locator('dd.masked').first().innerText(), '●●●●●● ●●●●●●');
    await reed.getByRole('button', { name: 'Révéler le vrai nom' }).click();
    await reed.locator('.modal').getByLabel('Motif').fill('Préparer l’escorte au tribunal');
    await reed.locator('.modal').getByRole('button', { name: 'Révéler', exact: true }).click();
    await reed.getByText('Lucia Moretti').waitFor();
    await reed.goto(BASE + '/usms-ls/witsec'); await settle(reed, 'h1:text("WITSEC")');
    await reed.getByRole('tab', { name: 'Journal d’audit' }).click();
    await reed.locator('table', { hasText: 'Nom révélé' }).waitFor();
  });
  await step('SOG : opérateur, activation qui l’alerte, briefing', async () => {
    await reed.goto(BASE + '/usms-ls/sog'); await settle(reed, 'h1:text("SOG")');
    await reed.getByRole('tab', { name: 'Opérateurs' }).click();
    await reed.getByRole('button', { name: 'Opérateur', exact: true }).click();
    const m = reed.locator('.modal');
    await m.getByLabel(/^Agent/).selectOption({ label: 'Marshal-12 · Dan Moss' });
    await m.getByLabel('Spécialité').selectOption('Brèche');
    await m.getByRole('button', { name: 'Enregistrer' }).click();
    await toast(reed, 'Opérateur enregistré');
    await reed.getByRole('button', { name: 'Activer le SOG' }).click();
    const a = reed.locator('.modal');
    await a.getByLabel('Opération').fill('Assaut planque Grove');
    await a.getByRole('button', { name: 'Activer le SOG' }).click();
    await reed.waitForURL(/\/usms-ls\/sog\/[0-9a-f-]{36}$/); await settle(reed, 'h1:text("USMS-OPS-001")');
    S.missionPath = new URL(reed.url()).pathname;
    await reed.getByRole('button', { name: 'Briefing', exact: true }).click();
    const b = reed.locator('.modal');
    await b.getByLabel('Plan', { exact: true }).fill('Entrée par l’arrière, appui sur le toit');
    await b.getByLabel('Opérateur', { exact: true }).first().selectOption({ label: 'Marshal-12 · Dan Moss' });
    await b.getByLabel('Rôle', { exact: true }).first().fill('Brèche');
    await b.getByRole('button', { name: 'Enregistrer le briefing' }).click();
    await toast(reed, 'Briefing enregistré');
    await dan.goto(BASE + '/usms-ls'); await settle(dan, '.alertcard');
    await dan.locator('.alertcard', { hasText: 'Activation SOG USMS-OPS-001' }).waitFor();
  });
  await step('renseignement : informateur, note secrète, graphe des relations', async () => {
    await reed.goto(BASE + '/usms-ls/renseignement'); await settle(reed, 'h1:text("Renseignement")');
    await reed.getByRole('tab', { name: 'Informateurs' }).click();
    await reed.getByRole('button', { name: 'Informateur', exact: true }).click();
    await reed.locator('.modal').getByLabel('Nom de code').fill('cobra');
    await reed.locator('.modal').getByRole('button', { name: 'Enregistrer' }).click();
    await toast(reed, 'Informateur enregistré');
    await reed.getByRole('button', { name: 'Nouvelle note' }).click();
    const m = reed.locator('.modal');
    await m.getByLabel('Titre').fill('Livraison d’armes');
    await m.getByLabel('Contenu').fill('Samedi soir au port, deux camions.');
    await m.getByLabel('Classification').selectOption('secret');
    await m.getByLabel('Source').selectOption({ label: 'COBRA' });
    await m.getByRole('button', { name: 'Enregistrer la note' }).click();
    await toast(reed, 'Note enregistrée');
    await reed.getByRole('tab', { name: 'Notes' }).click();
    await reed.locator('.intelcard', { hasText: 'Livraison d’armes' }).waitFor();
    await reed.getByRole('tab', { name: 'Graphe des relations' }).click();
    await reed.locator('svg.relgraph').waitFor();
    await shot(reed, 'graphe', true);
    await dan.goto(BASE + '/usms-ls/renseignement'); await settle(dan, 'h1:text("Renseignement")');
    // promu Inspector plus haut dans le scénario, il a l'accès Renseignement : il lit la note secrète (le cas sans accès est couvert par tests/mdt.test.mjs)
    await dan.locator('.intelcard', { hasText: 'Livraison d’armes' }).waitFor();
  });

  // ------------------------------------------------------------ étape 4 : académie, affaires internes, logistique, statistiques, carte, réglages
  await step('candidature publique, entretien, recrutement depuis l’académie', async () => {
    const v = await open({ label: 'candidat' });
    await v.goto(BASE + '/avis/usms-ls/candidature'); await settle(v, 'h1:text("Rejoindre")');
    await v.getByLabel('Nom de ton personnage').fill('Léo Martin');
    await v.getByLabel('Identifiant Discord').fill('55555555');
    for (const q of ['Âge et origine de ton personnage', 'Ton expérience (police, armée, sécurité, autre service)', 'Pourquoi veux-tu rejoindre les US Marshals ?', 'Tes disponibilités (jours et heures)',
      'As-tu lu le règlement du service et du serveur ?', 'Ton âge réel (hors RP)']) await v.getByLabel(q, { exact: true }).fill('Réponse sérieuse');
    await v.getByRole('button', { name: 'Envoyer ma candidature' }).click();
    await toast(v, 'Candidature envoyée');
    await v.locator('.sent', { hasText: 'USMS-CAND-0001' }).waitFor();
    await shot(v, 'candidature-publique', true);
    await v.context().close();
    await sam.goto(BASE + '/usms-ls/academie'); await settle(sam, 'h1:text("Académie")');
    await sam.getByRole('tab', { name: 'Candidatures (1)' }).click();
    await sam.locator('tr', { hasText: 'Léo Martin' }).click();
    await sam.locator('.modal').getByRole('button', { name: 'Entretien' }).click();
    await sam.locator('.modal').getByRole('button', { name: 'Programmer' }).click();
    await toast(sam, 'Entretien programmé');
    await sam.locator('tr', { hasText: 'Léo Martin' }).click();
    await sam.locator('.modal').getByRole('button', { name: 'Accepter et recruter' }).click();
    const m = sam.locator('.modal');
    assert.equal(await m.getByLabel('Identifiant de connexion').inputValue(), 'leo.martin');
    await m.getByRole('button', { name: 'Recruter' }).click();
    S.leoLink = await linkFromModal(sam);
    const [a] = await backend.sql("select a.status, m.matricule, g.name from public.applications a join public.members m on m.id = a.member_id join public.grades g on g.id = m.grade_id");
    assert.equal(a.status, 'acceptee'); assert.equal(a.name, 'Recrue');
    S.leoId = (await backend.sql("select id from public.members where login = 'leo.martin'"))[0].id;
  });
  await step('examen en ligne : QCM corrigé par le serveur, module du parcours validé', async () => {
    await dan.goto(BASE + '/usms-ls/academie'); await settle(dan, 'h1:text("Académie")');
    await dan.getByRole('tab', { name: 'Examens' }).click();
    await dan.getByRole('button', { name: 'Passer l’examen' }).click();
    const m = dan.locator('.modal');
    const answers = ['Reçu, compris', '10-32', 'Agent en détresse, toutes les unités', '10-20', 'Situation sous contrôle'];
    for (const [i, a] of answers.entries()) await m.locator('fieldset').nth(i).getByLabel(a, { exact: true }).check();
    await shot(dan, 'examen');
    await m.getByRole('button', { name: 'Rendre ma copie' }).click();
    await toast(dan, 'Réussi : 100 %');
    const [tp] = await backend.sql("select tm.name from public.training_progress tp join public.training_modules tm on tm.id = tp.module_id where tp.member_id = $1", [S.danId]);
    assert.equal(tp.name, 'Radio et indicatifs');
  });
  await step('promotion : proposée avec les statistiques, ancienneté vérifiée, acceptée par le Director', async () => {
    await sam.getByRole('tab', { name: /^Promotions/ }).click();
    await sam.getByRole('button', { name: 'Proposer une promotion' }).click();
    const m = sam.locator('.modal');
    await m.getByLabel('Agent', { exact: true }).selectOption(S.leoId);
    await m.getByLabel('Grade proposé').selectOption({ label: 'Deputy' });
    await m.getByLabel('Motivation').fill('Très bon entretien, sérieux et disponible.');
    await m.getByRole('button', { name: 'Proposer' }).click();
    await toast(sam, 'Proposition envoyée');
    await reed.goto(BASE + '/usms-ls/academie'); await settle(reed, 'h1:text("Académie")');
    await reed.getByRole('tab', { name: 'Promotions (1)' }).click();
    const c = reed.locator('.wcard', { hasText: 'Léo Martin' });
    await c.locator('.pill', { hasText: '✗ Ancienneté' }).waitFor();
    await shot(reed, 'promotion', true);
    await c.getByRole('button', { name: 'Promouvoir' }).click();
    await reed.locator('.modal').getByLabel('Passer outre l’ancienneté (Director)').check();
    await reed.locator('.modal').getByRole('button', { name: 'Promouvoir' }).click();
    await toast(reed, 'Léo Martin passe Deputy');
    const [g] = await backend.sql("select g.name from public.members m join public.grades g on g.id = m.grade_id where m.id = $1", [S.leoId]);
    assert.equal(g.name, 'Deputy');
  });
  await step('affaires internes : plainte publique, enquête close, suspension qui coupe l’accès puis levée', async () => {
    const v = await open({ label: 'plaignant' });
    await v.goto(BASE + '/avis/usms-ls/plainte'); await settle(v, 'h1:text("Porter plainte")');
    await v.getByLabel('Agent concerné').fill('Marshal-12');
    await v.getByLabel('Quand et où').fill('Hier soir, Vinewood');
    await v.getByLabel('Les faits').fill('L’agent a été insultant pendant un contrôle routier.');
    await v.getByRole('button', { name: 'Déposer la plainte' }).click();
    await toast(v, 'Plainte déposée');
    await v.context().close();
    await reed.goto(BASE + '/usms-ls/affaires'); await settle(reed, 'h1:text("Affaires internes")');
    await reed.getByRole('tab', { name: 'Plaintes (1)' }).click();
    await reed.locator('tr', { hasText: 'USMS-PL-0001' }).getByRole('button', { name: 'Enquêter' }).click();
    await reed.waitForURL(/\/usms-ls\/affaires\/[0-9a-f-]{36}$/); await settle(reed, 'h1:text("Dossier USMS-AI-")');
    S.iaPath = new URL(reed.url()).pathname;
    await reed.getByLabel('Entrée', { exact: true }).fill('Audition du plaignant : propos confirmés par un témoin.');
    await reed.getByLabel('Type d’entrée').selectOption('audition');
    await reed.getByRole('button', { name: 'Ajouter au journal' }).click();
    await toast(reed, 'Ajouté au journal d’enquête');
    await reed.getByRole('button', { name: 'Clore le dossier' }).first().click();
    await reed.locator('.modal').getByLabel('Décision').fill('Faits établis, rappel à l’ordre de l’agent.');
    await reed.locator('.modal').getByRole('button', { name: 'Clore le dossier' }).click();
    await toast(reed, 'Dossier clos');
    await reed.goto(BASE + '/usms-ls/effectifs/' + S.danId); await settle(reed, 'h1:text("Dan Moss")');
    await reed.getByRole('button', { name: 'Sanctionner' }).click();
    const m = reed.locator('.modal');
    await m.getByLabel('Sanction').selectOption('suspension');
    await m.getByLabel('Durée (jours)').fill('1');
    await m.getByLabel('Motif').fill('Insultes pendant un contrôle');
    await m.getByRole('button', { name: 'Appliquer la sanction' }).click();
    await toast(reed, 'Sanction appliquée');
    await reed.locator('.banner', { hasText: 'Suspendu jusqu’au' }).waitFor();
    await dan.goto(BASE + '/usms-ls'); await settle(dan, 'h1:text("Accès suspendu")');
    await reed.goto(BASE + '/usms-ls/affaires'); await settle(reed, 'h1:text("Affaires internes")');
    await reed.getByRole('tab', { name: 'Casier disciplinaire' }).click();
    await reed.locator('tr', { hasText: 'Dan Moss' }).getByRole('button', { name: 'Lever' }).click();
    await toast(reed, 'Suspension levée');
    await dan.goto(BASE + '/usms-ls'); await settle(dan, '.dutycard');
  });
  await step('logistique : blindé réservé au SOG, retour avec dégâts, arme remise, stock', async () => {
    await reed.goto(BASE + '/usms-ls/logistique'); await settle(reed, 'h1:text("Logistique")');
    await reed.getByRole('button', { name: 'Ajouter un véhicule' }).click();
    let m = reed.locator('.modal');
    await m.getByLabel('Modèle').fill('Granger blindé'); await m.getByLabel('Plaque').fill('usms 01'); await m.getByLabel('Type').selectOption('blinde');
    await m.getByRole('button', { name: 'Enregistrer' }).click();
    await toast(reed, 'Véhicule enregistré');
    await sam.goto(BASE + '/usms-ls/logistique'); await settle(sam, 'h1:text("Logistique")');
    await sam.locator('tr', { hasText: 'USMS 01' }).getByRole('button', { name: 'Prendre' }).click();
    await sam.locator('.modal').getByRole('button', { name: 'Prendre le véhicule' }).click();
    await toast(sam, 'réservé au SOG');
    await sam.keyboard.press('Escape');
    await dan.goto(BASE + '/usms-ls/logistique'); await settle(dan, 'h1:text("Logistique")');
    await dan.locator('tr', { hasText: 'USMS 01' }).getByRole('button', { name: 'Prendre' }).click();
    await dan.locator('.modal').getByLabel('Motif').fill('Assaut planque Grove');
    await dan.locator('.modal').getByRole('button', { name: 'Prendre le véhicule' }).click();
    await toast(dan, 'Bonne route');
    await dan.locator('tr', { hasText: 'USMS 01' }).getByRole('button', { name: 'Rendre' }).click();
    await dan.locator('.modal').getByLabel('Dégâts').fill('Rétroviseur arraché');
    await dan.locator('.modal').getByRole('button', { name: 'Rendre le véhicule' }).click();
    await toast(dan, 'Véhicule rendu');
    assert.equal((await backend.sql("select state from public.fleet where plate = 'USMS 01'"))[0].state, 'abime');
    await reed.getByRole('tab', { name: 'Armurerie' }).click();
    await reed.getByRole('button', { name: 'Entrer une arme' }).click();
    m = reed.locator('.modal');
    await m.getByLabel('Numéro de série').fill('gl17-0042'); await m.getByLabel('Modèle').fill('Glock 17');
    await m.getByRole('button', { name: 'Enregistrer' }).click();
    await toast(reed, 'Arme enregistrée');
    await reed.locator('tr', { hasText: 'GL17-0042' }).getByRole('button', { name: 'Remettre' }).click();
    await reed.locator('.modal').getByLabel('Agent').selectOption(S.danId);
    await reed.locator('.modal').getByRole('button', { name: 'Remettre l’arme' }).click();
    await toast(reed, 'Arme remise');
    await reed.getByRole('tab', { name: 'Équipement' }).click();
    await reed.getByRole('button', { name: 'Nouvel article' }).click();
    await reed.locator('.modal').getByLabel('Article').fill('Gilet pare-balles'); await reed.locator('.modal').getByLabel('Type').selectOption('gilet');
    await reed.locator('.modal').getByRole('button', { name: 'Enregistrer' }).click();
    await toast(reed, 'Article enregistré');
    await reed.locator('tr', { hasText: 'Gilet pare-balles' }).getByRole('button', { name: 'Mouvement' }).click();
    await reed.locator('.modal').getByLabel('Mouvement').selectOption('in');
    await reed.locator('.modal').getByLabel('Quantité').fill('10');
    await reed.locator('.modal').getByRole('button', { name: 'Enregistrer' }).click();
    await toast(reed, 'Stock : 10 unité');
  });
  await step('réglages, carte et statistiques : formulaire modifié, logo, carte, repère, bilan exporté', async () => {
    await reed.goto(BASE + '/usms-ls/reglages'); await settle(reed, 'h1:text("Réglages")');
    await reed.getByLabel('Question', { exact: true }).first().fill('Nom, âge et origine de ton personnage');
    await reed.locator('section.card', { hasText: 'Modèles de documents' }).getByRole('button', { name: 'Enregistrer', exact: true }).click();
    await toast(reed, 'Formulaire enregistré');
    await reed.goto(BASE + '/usms-ls/reglages'); await settle(reed, 'h1:text("Réglages")');
    await reed.getByLabel('Logo du service').setInputFiles(photo('logo.png'));
    await reed.getByRole('button', { name: 'Envoyer le logo' }).click();
    await toast(reed, 'Logo enregistré');
    await reed.locator('img.logoprev').waitFor();
    await reed.getByLabel('Carte de la ville').setInputFiles(photo('carte.png'));
    await reed.getByRole('button', { name: 'Envoyer la carte' }).click();
    await toast(reed, 'Carte enregistrée');
    await shot(reed, 'reglages-service', true);
    // Fond de tuiles auto-hébergées (web/tiles/gta5) : elles se chargent, et « Placer au centre » ouvre la fiche du repère.
    await reed.goto(BASE + '/usms-ls/carte'); await settle(reed, 'img.leaflet-tile-loaded');
    await reed.getByRole('button', { name: 'Ajouter un repère' }).click();
    await reed.getByRole('button', { name: 'Placer au centre de la vue' }).click();
    await reed.locator('.modal h2, .modal h3').filter({ hasText: 'Nouveau repère' }).waitFor();
    await reed.keyboard.press('Escape'); await reed.locator('.modal').waitFor({ state: 'detached' }).catch(() => {});
    // Sans tuiles : repli sur l’image envoyée dans Réglages.
    overrides['/tiles/gta5/meta.json'] = 'absent';
    await reed.goto(BASE + '/usms-ls/carte'); await settle(reed, 'img.mapimg');
    await reed.getByRole('button', { name: 'Ajouter un repère' }).click();
    const box = await reed.locator('img.mapimg').boundingBox();   // l’image est calée dans la carte Leaflet : on clique à 40 % / 30 % de l’image
    await reed.mouse.click(box.x + box.width * 0.4, box.y + box.height * 0.3);
    const m = reed.locator('.modal');
    await m.getByLabel('Nom du repère').fill('Vu au garage');
    await m.getByLabel('Individu').selectOption(S.personPath.split('/').pop());
    await m.getByRole('button', { name: 'Placer le repère' }).click();
    await toast(reed, 'Repère enregistré');
    await reed.locator('.pin.mk-position.hunted').waitFor();
    const [mk] = await backend.sql('select x, y from public.map_marks');
    assert.ok(Math.abs(mk.x - 0.4) < 0.02 && Math.abs(mk.y - 0.3) < 0.02);
    await shot(reed, 'carte', true);
    delete overrides['/tiles/gta5/meta.json'];
    await reed.goto(BASE + '/usms-ls/statistiques'); await settle(reed, 'h1:text("Statistiques")');
    await reed.getByRole('button', { name: 'Bilan de la semaine' }).click();
    await reed.locator('.modal img.docimg[src^="data:image/png"]').waitFor();
    await shot(reed, 'bilan');
    await reed.keyboard.press('Escape');
    const v = await open({ label: 'public' });
    await v.goto(BASE + '/avis/usms-ls/candidature'); await settle(v, 'h1:text("Rejoindre")');
    await v.getByLabel('Nom, âge et origine de ton personnage').waitFor();
    assert.match(await v.locator('.avis-seal').getAttribute('src'), /logo/);
    await v.context().close();
  });

  // ------------------------------------------------------------ connexion ordinaire
  await step('plans d’intervention : création, plan d’un mandat, export JSON, enregistrement, droits', async () => {
    await reed.goto(BASE + '/usms-ls/plans'); await settle(reed, 'h1:text("Plans d’intervention")');
    await reed.getByRole('button', { name: 'Nouveau plan' }).click();
    const m = reed.locator('.modal');
    await m.getByLabel('Titre du plan').fill('Entrée arrière de la banque');
    await m.getByLabel('Type de plan').selectOption('mandat');
    await m.getByLabel('Pièce rattachée').locator('option').nth(1).waitFor({ state: 'attached' });
    await m.getByLabel('Pièce rattachée').selectOption({ index: 1 });
    await m.getByRole('button', { name: 'Créer le plan' }).click();
    await settle(reed, 'h1:text("Entrée arrière de la banque")'); await settle(reed, 'img.leaflet-tile-loaded');
    // dessin : flèche à la souris, zone, étiquette, puis photo ; annuler et rétablir
    const at = async (fx, fy) => { await reed.locator('.mapbox').evaluate(e => e.scrollIntoView({ block: 'center' })); const mb = await reed.locator('.mapbox').boundingBox(); await reed.mouse.click(mb.x + mb.width * fx, mb.y + mb.height * fy); };
    await reed.getByRole('button', { name: 'Flèche', exact: true }).click();
    await at(0.3, 0.4); await at(0.6, 0.5); await reed.keyboard.press('Enter');
    await reed.locator('path.plan-fleche').waitFor();
    await reed.getByLabel('Rôle', { exact: true }).fill('Brèche'); await reed.getByLabel('Rôle', { exact: true }).press('Tab');
    await reed.getByRole('button', { name: 'Zone', exact: true }).click();
    await at(0.2, 0.2); await at(0.4, 0.2); await at(0.3, 0.3); await reed.getByRole('button', { name: 'Terminer' }).click();
    await reed.locator('path.plan-zone').waitFor();
    await reed.getByRole('button', { name: 'Étiquette', exact: true }).click();
    await reed.getByLabel('Texte de la forme à dessiner').fill('Hall'); await at(0.75, 0.3);
    await reed.locator('.plan-etiquette span').waitFor();
    await reed.getByRole('button', { name: 'Supprimer la forme' }).click();
    assert.equal(await reed.locator('.plan-etiquette span').count(), 0);
    await reed.getByRole('button', { name: 'Annuler (Ctrl+Z)' }).click();
    await reed.locator('.plan-etiquette span').waitFor();
    // véhicule orienté et coloré, texte libre, légende (noms et couleurs des équipes)
    await reed.getByRole('button', { name: 'Véhicule', exact: true }).click();
    await reed.getByLabel('Type de véhicule').selectOption('suv');
    await at(0.3, 0.25);
    await reed.locator('.plan-vehicule svg').waitFor();
    await reed.getByLabel('Orientation en degrés (0 = nord, 90 = est)').fill('90'); await reed.getByLabel('Orientation en degrés (0 = nord, 90 = est)').press('Tab');
    await reed.locator('.plan-edit').getByLabel('Couleur', { exact: true }).fill('#ff00aa');
    await reed.locator('.plan-vehicule svg[fill="#ff00aa"]').waitFor();
    await reed.getByRole('button', { name: 'Texte', exact: true }).click();
    await reed.getByLabel('Texte de la forme à dessiner').fill('Point de ralliement'); await at(0.5, 0.15);
    await reed.locator('.plan-texte span').waitFor();
    await reed.getByText('Légende et couleurs des équipes').click();
    await reed.getByLabel('Nom de l’équipe A').fill('Alpha'); await reed.getByLabel('Nom de l’équipe A').press('Tab');
    await reed.locator('.plan-legend').getByText('Alpha').waitFor();
    assert.match(await reed.locator('.plan-legend').innerText(), /Déplacement/);
    await reed.locator('input[aria-label="Ajouter une photo au plan"]').setInputFiles(photo('carte.png'));
    await reed.locator('img.plan-photo').waitFor();
    await reed.getByLabel('Rotation en degrés').fill('30'); await reed.getByLabel('Rotation en degrés').press('Tab');
    await reed.getByLabel('Opacité de la photo').fill('50');
    const [dl] = await Promise.all([reed.waitForEvent('download'), reed.getByRole('button', { name: 'Exporter' }).click()]);
    assert.match(dl.suggestedFilename(), /^plan-entree-arriere-de-la-banque\.json$/);
    const exported = JSON.parse(readFileSync(await dl.path(), 'utf8'));
    assert.equal(exported.format, 'windy-desk-plan'); assert.equal(exported.data.v, 1); assert.equal(exported.kind, 'mandat');
    assert.deepEqual(exported.data.shapes.map(x => x.type), ['fleche', 'zone', 'etiquette', 'vehicule', 'texte']); assert.equal(exported.data.shapes[3].color, '#ff00aa'); assert.equal(exported.data.shapes[3].angle, 90); assert.equal(exported.data.legend.teams.A.name, 'Alpha'); assert.equal(exported.data.layers.length, 1); assert.equal(exported.data.shapes[0].role, 'Brèche');
    await reed.getByLabel('Titre du plan').fill('Banque, entrée arrière');
    await reed.getByRole('button', { name: 'Enregistrer' }).click(); await toast(reed, 'Plan enregistré');
    await shot(reed, 'plan', true);
    const [pl] = await backend.sql('select title, kind, ref_id, data from public.intervention_plans');
    assert.equal(pl.title, 'Banque, entrée arrière'); assert.ok(pl.ref_id); assert.equal(pl.data.v, 1);
    assert.equal(pl.data.layers[0].corners.length, 4); assert.equal(pl.data.layers[0].opacity, 0.5); assert.ok(Math.abs(pl.data.shapes[0].pts[0][0]) < 20000);
    await reed.reload(); await settle(reed, 'path.plan-fleche'); await reed.locator('path.plan-zone').waitFor(); await reed.locator('img.plan-photo').waitFor();
    // le bouton « Plan » d’un mandat ouvre ce plan, sans en créer un second
    await reed.goto(BASE + '/usms-ls/mandats'); await settle(reed, 'h1:text("Mandats")');
    await reed.getByRole('button', { name: 'Tous', exact: true }).click();
    await reed.locator('table tbody tr').first().click();
    await reed.locator('.modal').getByRole('button', { name: 'Plan', exact: true }).click();
    await settle(reed, 'h1:text("Banque, entrée arrière")');
    assert.equal((await backend.sql('select count(*)::int n from public.intervention_plans'))[0].n, 1);
    await reed.goto(BASE + '/usms-ls/plans'); await settle(reed, 'td:text("Banque, entrée arrière"), .art b:text("Banque, entrée arrière")');
    await reed.getByRole('button', { name: 'Supprimer' }).count();
  });
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
  for (const p of [ed, gino, nina, fisc, reed, sam, dan, judge]) await p.context().close();
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
  patron: [S.gino, ['/bella-napoli', '/bella-napoli/compta', '/bella-napoli/equipe', '/bella-napoli/reglages', '/bella-napoli/caisse', '/bella-napoli/stock', '/bella-napoli/fabrication',
    '/bella-napoli/recolte', '/bella-napoli/factures', '/bella-napoli/dons', '/bella-napoli/vehicules', '/bella-napoli/evenements', S.eventPath, '/bella-napoli/equipe/' + S.ninaId]],
  fisc: [S.fisc, ['/fisc', '/fisc/bella-napoli', '/fisc/les-restos-du-coeur', '/fisc/bareme']],
  editeur: [S.editeur, ['/editeur', '/editeur/modules', '/editeur/acces', '/editeur/devis', '/editeur/devis/' + devisId, '/editeur/devis/nouveau']],
  director: [S.reed, ['/usms-ls', '/usms-ls/effectifs', '/usms-ls/effectifs/' + S.danId, '/usms-ls/presence', '/usms-ls/dossiers', S.personPath, S.casePath, '/usms-ls/mandats', '/usms-ls/wanted', '/usms-ls/annonces', '/usms-ls/reglages',
    '/usms-ls/recherche', S.platePath, S.gangPath, '/usms-ls/rapports', S.reportPath, '/usms-ls/rapports/nouveau', '/usms-ls/preuves', S.evidencePath,
    '/usms-ls/transferts', '/usms-ls/tribunal', '/usms-ls/witsec', S.witnessPath, '/usms-ls/sog', S.missionPath, '/usms-ls/renseignement',
    '/usms-ls/academie', '/usms-ls/affaires', S.iaPath, '/usms-ls/logistique', '/usms-ls/statistiques', '/usms-ls/carte', '/usms-ls/plans', '/usms-ls/effectifs/' + S.leoId]],
  deputy: [S.dan, ['/usms-ls', '/usms-ls/effectifs/' + S.danId, '/usms-ls/presence', '/usms-ls/mandats', '/usms-ls/rapports', S.reportPath, '/usms-ls/transferts', '/usms-ls/tribunal', '/usms-ls/sog',
    '/usms-ls/academie', '/usms-ls/logistique', '/usms-ls/statistiques', '/usms-ls/carte']],
  justice: [S.judge, ['/justice']],
  public: [null, ['/avis/usms-ls', '/avis/usms-ls/candidature', '/avis/usms-ls/plainte', '/usms-ls']],
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
