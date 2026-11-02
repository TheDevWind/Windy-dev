// Page d'accueil : présentation du produit, démonstrations, configurateur qui produit un devis.
import { $app, S, calm, h, ico, toast, themeSwitch, countUp, liveDot, pill, money, initials, gauge, nextAt, untilText, pad, BRAND, butterfly, lockup, branch } from './ui.js';
import { MODULES, PACKS, quoteTotal } from './catalog.js';
import { exportModal, quoteDoc } from './doc.js';

export function landing() {
  S.shown = null; S.persona = null;
  clearInterval(S.timer);
  const smooth = calm ? 'auto' : 'smooth';
  const jump = id => { closeMenu(); const el = document.getElementById(id); if (el) el.scrollIntoView({ behavior: smooth, block: 'start' }); };
  const LINKS = [['fonctions', 'Fonctionnalités'], ['modules', 'Modules'], ['fisc', 'Fisc'], ['tarifs', 'Tarifs'], ['questions', 'Questions']];

  // ---- navigation (menu plein écran sur téléphone)
  const menu = h('div', { class: 'l-menu', hidden: true }, h('nav', {}, LINKS.map(([id, l]) => h('button', { type: 'button', onclick: () => jump(id) }, l, ico('fleche', 20)))),
    h('div', { class: 'l-menu-cta' }, h('a', { class: 'btn wide', href: '/connexion' }, 'Se connecter'), h('button', { class: 'btn primary wide', type: 'button', onclick: () => jump('configurateur') }, 'Composer mon offre')));
  const burger = h('button', { class: 'l-burger', type: 'button', 'aria-label': 'Menu', 'aria-expanded': 'false', onclick: () => { const open = menu.hidden; menu.hidden = !open; burger.setAttribute('aria-expanded', String(open)); document.body.classList.toggle('l-lock', open); } }, h('i', {}), h('i', {}));
  function closeMenu() { menu.hidden = true; burger.setAttribute('aria-expanded', 'false'); document.body.classList.remove('l-lock'); }
  const navbar = h('header', { class: 'l-nav' }, h('div', { class: 'l-wrap l-nav-in' },
    h('a', { class: 'l-brand', href: '/', onclick: ev => { ev.preventDefault(); window.scrollTo({ top: 0, behavior: smooth }); } }, butterfly('l-fly', 60), h('span', { class: 'l-wm' }, h('b', {}, 'Windy'), ' Desk')),
    h('nav', { class: 'l-links', 'aria-label': 'Sections' }, LINKS.map(([id, l]) => h('button', { type: 'button', onclick: () => jump(id) }, l))),
    h('div', { class: 'l-actions' }, themeSwitch(), h('a', { class: 'btn ghost l-login', href: '/connexion' }, 'Se connecter'), h('button', { class: 'btn primary', type: 'button', onclick: () => jump('configurateur') }, 'Composer mon offre'), burger)));

  // ---- maquette vivante du produit
  const MENU = [['Margherita', 18], ['Regina', 22], ['Chorizo', 22], ['Soda', 4]];
  const tilesM = MENU.map(([n, p]) => h('div', { class: 'm-tile' }, h('span', {}, n), h('b', {}, p + ' $')));
  const salesEl = h('b', {}, '1 284 $'), ticketsEl = h('b', {}, '57'), mozzaBar = h('i', { style: 'width:64%' }), mozzaVal = h('b', {}, '32');
  const tlist = h('ul', { class: 'm-tickets' }, [['n°57', 'Regina ×2', 44], ['n°56', 'Margherita, Soda', 22], ['n°55', 'Chorizo', 22]].map(([n, w, a]) => h('li', {}, h('span', {}, n), h('span', {}, w), h('b', {}, a + ' $'))));
  const mtoast = h('div', { class: 'm-toast' }, ico('coche', 14), h('span', {}, 'Ticket encaissé'));
  const mock = h('div', { class: 'mock', 'aria-hidden': 'true' },
    h('div', { class: 'm-bar' }, h('i', {}), h('i', {}), h('i', {}), h('span', {}, '/bella-napoli · Caisse')),
    h('div', { class: 'm-body' },
      h('div', { class: 'm-side' }, h('b', { class: 'mk sm' }, 'B'), ['accueil', 'caisse', 'boite', 'calendrier', 'camion', 'balance'].map((i, k) => h('span', { class: k === 1 ? 'on' : null }, ico(i, 16)))),
      h('div', { class: 'm-main' },
        h('div', { class: 'm-head' }, h('b', {}, 'Caisse'), h('span', { class: 'live' }, h('i', {}), 'Ouverte')),
        h('div', { class: 'm-strip' }, h('div', {}, salesEl, h('span', {}, 'Ventes du jour')), h('div', {}, ticketsEl, h('span', {}, 'Tickets')), h('div', {}, mozzaVal, h('span', {}, 'Mozzarella'), h('span', { class: 'meter' }, mozzaBar))),
        h('div', { class: 'm-grid' }, h('div', { class: 'm-tiles' }, tilesM), h('div', { class: 'm-ticket' }, h('span', { class: 'm-label' }, 'Derniers tickets'), tlist)),
        mtoast)),
    h('div', { class: 'float f1' }, h('span', { class: 'f-ic' }, ico('balance', 16)), h('span', {}, h('b', {}, 'Semaine 44 déclarée'), h('small', {}, 'Impôt payé · 326 $'))),
    h('div', { class: 'float f2' }, h('span', { class: 'plate sm' }, 'BN 201'), h('span', {}, h('b', {}, 'Scooter Faggio'), h('small', {}, 'Nina · livraison · 64 %'))));
  let sales = 1284, tickets = 57, mozza = 32;
  const tick = () => {
    if (!mock.isConnected) return clearInterval(S.timer);
    const k = Math.floor(Math.random() * MENU.length), q = 1 + Math.floor(Math.random() * 2), amount = MENU[k][1] * q;
    tilesM.forEach(t => t.classList.remove('press')); void tilesM[k].offsetWidth; tilesM[k].classList.add('press');
    countUp(salesEl, sales + amount, sales); sales += amount; salesEl.dataset.v = sales;
    setTimeout(() => { salesEl.textContent = sales.toLocaleString('fr-FR') + ' $'; }, 700);
    tickets++; ticketsEl.textContent = tickets;
    if (k < 3) { mozza = Math.max(4, mozza - q); mozzaVal.textContent = mozza; mozzaBar.style.width = Math.round(mozza / 50 * 100) + '%'; if (mozza < 12) { mozza = 40; } }
    const li = h('li', { class: 'in' }, h('span', {}, 'n°' + tickets), h('span', {}, MENU[k][0] + (q > 1 ? ' ×' + q : '')), h('b', {}, amount + ' $'));
    tlist.prepend(li); while (tlist.children.length > 4) tlist.lastChild.remove();
    mtoast.querySelector('span').textContent = 'Ticket n°' + tickets + ' encaissé · ' + amount + ' $';
    mtoast.classList.remove('show'); void mtoast.offsetWidth; mtoast.classList.add('show');
  };
  if (!calm) S.timer = setInterval(tick, 2600);

  // ---- démonstration interactive : la caisse vide le stock
  const stockDemo = { Pâte: 12, Mozzarella: 9, Soda: 8 };
  const uses = { Margherita: { Pâte: 1, Mozzarella: 1 }, Regina: { Pâte: 1, Mozzarella: 1 }, Soda: { Soda: 1 } };
  const meters = {};
  const demoStock = h('div', { class: 'd-stock' }, Object.keys(stockDemo).map(k => { meters[k] = { bar: h('i', { style: 'width:100%' }), val: h('b', {}, stockDemo[k]) }; return h('div', { class: 'd-row' }, h('span', {}, k), meters[k].val, h('span', { class: 'meter ok' }, meters[k].bar)); }));
  const demoTotal = h('b', { class: 'd-total' }, '0 $');
  let dt = 0;
  const demoPos = h('div', { class: 'd-pos' }, Object.keys(uses).map(n => { const price = { Margherita: 18, Regina: 22, Soda: 4 }[n];
    const b = h('button', { type: 'button', class: 'tilebtn' }, h('span', { class: 'tb-name' }, n), h('b', { class: 'tb-price' }, price + ' $'), h('span', { class: 'tb-left' }, 'Touche pour vendre'));
    b.addEventListener('click', () => {
      const need = uses[n];
      if (Object.entries(need).some(([k, q]) => stockDemo[k] < q)) { toast('Rupture : plus assez de ' + Object.keys(need).find(k => stockDemo[k] < need[k]).toLowerCase() + '. Windy Desk bloque la vente.', true); return; }
      Object.entries(need).forEach(([k, q]) => { stockDemo[k] -= q; meters[k].val.textContent = stockDemo[k]; meters[k].bar.style.width = Math.round(stockDemo[k] / 12 * 100) + '%'; meters[k].bar.parentNode.className = 'meter ' + (stockDemo[k] < 4 ? 'bad' : stockDemo[k] < 7 ? 'warn' : 'ok'); });
      countUp(demoTotal, dt + price, dt || 1); dt += price; setTimeout(() => { demoTotal.textContent = dt + ' $'; }, 700);
    });
    return b; }));
  // ---- démonstration : compte à rebours d'un événement
  const evStart = nextAt(4, 19);
  const cdBoxes = h('div', { class: 'cd-boxes' });
  const paintCd = () => { const u = untilText(evStart - Date.now()); cdBoxes.replaceChildren(...[['jours', u.d], ['heures', u.hh], ['minutes', u.mm], ['secondes', u.ss]].map(([l, v]) => h('div', { class: 'cd-box' }, h('b', {}, pad(v)), h('span', {}, l)))); };
  paintCd();
  const cdTimer = setInterval(() => { if (!cdBoxes.isConnected) return clearInterval(cdTimer); paintCd(); }, 1000);

  const feat = (id, eyebrow, title, text, points, visual, flip) => h('div', { class: 'l-feat rv' + (flip ? ' flip' : ''), id },
    h('div', { class: 'l-feat-copy' }, h('span', { class: 'l-eyebrow' }, eyebrow), h('h3', {}, title), h('p', {}, text), h('ul', { class: 'l-points' }, points.map(p => h('li', {}, ico('coche', 18), p)))),
    h('div', { class: 'l-feat-visual' }, visual));

  // ---- configurateur de prix
  let pick = PACKS.Commerce.slice(), weeks = 4;
  const DISC = { 1: 0, 4: 5, 12: 10 };
  const cfgName = h('input', { type: 'text', maxlength: 40, placeholder: 'Nom de ton entreprise', 'aria-label': 'Nom de ton entreprise' });
  const cfgMods = h('div', { class: 'cfg-mods' }), cfgWeeks = h('div', { class: 'seg', role: 'group', 'aria-label': 'Durée' });
  const cfgWeek = h('b', {}), cfgTotal = h('b', { class: 'cfg-total' }), cfgSub = h('span', { class: 'muted small' });
  const quoteNum = 'D-' + new Date().toISOString().slice(2, 10).replace(/-/g, '') + '-' + String(Math.floor(Math.random() * 900) + 100);
  const quote = () => ({ id: 'qv', num: quoteNum, client: cfgName.value.trim() || 'Ton entreprise', contact: '', type: '', modules: MODULES.filter(m => pick.includes(m.id)).map(m => m.id), weeks, discount: DISC[weeks], status: 'brouillon', created: Date.now(), note: null });
  const paintCfg = () => {
    cfgMods.replaceChildren(...MODULES.map(m => h('button', { type: 'button', class: 'cfg-mod' + (pick.includes(m.id) ? ' on' : ''), 'aria-pressed': String(pick.includes(m.id)), onclick: () => { pick = pick.includes(m.id) ? pick.filter(x => x !== m.id) : pick.concat([m.id]); paintCfg(); } },
      h('span', { class: 'cm-ic' }, ico(m.icon, 18)), h('span', {}, m.name), h('small', {}, m.price + ' $'))));
    cfgWeeks.replaceChildren(...[1, 4, 12].map(w => h('button', { type: 'button', class: weeks === w ? 'on' : null, 'aria-pressed': String(weeks === w), onclick: () => { weeks = w; paintCfg(); } }, w + ' sem.' + (DISC[w] ? ' · −' + DISC[w] + ' %' : ''))));
    const t = quoteTotal(quote());
    cfgWeek.textContent = money(t.week1) + ' par semaine';
    const before = S.prev.cfg || t.total;
    cfgTotal.textContent = money(t.total);
    if (before !== t.total && !calm) { const from = before; const t0 = performance.now(); const step = n => { const k = Math.min(1, (n - t0) / 450); cfgTotal.textContent = money(from + (t.total - from) * (1 - Math.pow(1 - k, 3))); if (k < 1) requestAnimationFrame(step); }; requestAnimationFrame(step); }
    S.prev.cfg = t.total;
    cfgSub.textContent = pick.length + ' module' + (pick.length > 1 ? 's' : '') + ' · ' + weeks + ' semaine' + (weeks > 1 ? 's' : '') + (DISC[weeks] ? ' · remise de ' + DISC[weeks] + ' %' : '');
  };
  paintCfg();
  const choosePack = k => { pick = PACKS[k].slice(); paintCfg(); jump('configurateur'); };
  const cfgGo = h('button', { class: 'btn primary wide' }, ico('telecharger', 18), 'Recevoir mon devis en PNG ou PDF');
  cfgGo.addEventListener('click', () => { if (!pick.length) return toast('Choisis au moins un module.', true); exportModal(quoteDoc(quote()), 'devis-windy-desk'); });

  const FAQ = [['Faut-il installer quelque chose sur le serveur ?', 'Non. Windy Desk est un site : chaque entreprise a son adresse, ses comptes et ses grades. Une liaison avec le jeu (ventes qui remontent toutes seules) pourra devenir un module.'],
    ['Comment se passe le paiement ?', 'En argent RP, à la semaine. Tu reçois un devis, tu paies à la signature, l’espace est ouvert le jour même. La licence se renouvelle quand tu veux.'],
    ['Chaque employé a-t-il son compte ?', 'Oui. Le patron envoie un lien d’activation, l’employé choisit son mot de passe ou se connecte avec Discord. Il ne voit que ce que son grade permet : un serveur voit la caisse, pas la comptabilité.'],
    ['Que voit exactement le fisc ?', 'Les déclarations, la comptabilité et les mouvements de stock, en lecture seule. Jamais les mots de passe ni les notes de la direction. Chaque consultation est notée dans le journal de l’entreprise.'],
    ['On peut changer de modules en cours de route ?', 'À tout moment. Un module ajouté apparaît tout de suite dans le menu de l’entreprise ; le prix suit à la semaine suivante.'],
    ['Et pour une association ?', 'Le pack Association coûte 350 $ par semaine : stock, dons en argent ou en nature, distributions et événements. Elle déclare chaque semaine, sans impôt à payer.']];

  const page = h('div', { class: 'land' }, navbar, menu,
    h('section', { class: 'l-hero' }, branch('h-leaf one'), branch('h-leaf two'), h('div', { class: 'l-wrap l-hero-in' },
      h('div', { class: 'l-hero-copy' },
        h('span', { class: 'l-eyebrow l-in', style: '--i:0' }, BRAND + ' · gestion d’entreprise pour serveurs RP'),
        h('h1', {}, h('span', { class: 'l-line l-in', style: '--i:1' }, 'Ton entreprise RP'), ' ', h('span', { class: 'l-line l-in', style: '--i:2' }, 'tourne enfin'), ' ', h('span', { class: 'l-line l-in accent', style: '--i:3' }, 'comme une vraie.')),
        h('p', { class: 'l-lead l-in', style: '--i:4' }, 'Stock, recettes, caisse, employés, véhicules, événements et comptabilité dans un seul espace, avec un compte pour chaque employé. Tu ne paies que les modules dont tu as besoin.'),
        h('div', { class: 'l-ctas l-in', style: '--i:5' }, h('button', { class: 'btn primary big', type: 'button', onclick: () => jump('configurateur') }, 'Composer mon offre', ico('fleche', 18)), h('a', { class: 'btn big', href: '/connexion' }, 'Se connecter')),
        h('p', { class: 'l-trust l-in', style: '--i:6' }, 'Pizzerias · fermes · bars · garages · associations · taxis')),
      h('div', { class: 'l-hero-visual l-in', style: '--i:3' }, mock))),
    h('div', { class: 'l-marquee', 'aria-hidden': 'true' }, h('div', { class: 'l-track' }, [0, 1].map(() => h('span', {}, ['Pizzeria', 'Ferme', 'Bar', 'Garage', 'Association', 'Taxi', 'Boulangerie', 'Concession', 'Pêcherie', 'Club', 'Épicerie', 'Brasserie'].map(t => h('span', { class: 'mq' }, t, h('i', {}))))))),
    h('section', { class: 'l-sec', id: 'fonctions' }, h('div', { class: 'l-wrap' },
      h('div', { class: 'l-sec-head rv' }, h('span', { class: 'l-eyebrow' }, 'Fonctionnalités'), h('h2', {}, 'Tout ce que fait une entreprise, au même endroit.')),
      feat('f-caisse', 'Caisse et stock', 'La caisse sort les ingrédients du stock.', 'Chaque produit de la carte connaît sa recette. Une Regina vendue, c’est une pâte, une sauce et une mozzarella en moins. Essaie : vends quelques pizzas.',
        ['Rupture détectée avant la vente', 'Tickets du jour et moyens de paiement', 'Alerte quand un article passe sous son seuil'],
        h('div', { class: 'd-card' }, h('div', { class: 'd-head' }, h('b', {}, 'Caisse'), h('span', {}, 'Encaissé ', demoTotal)), demoPos, h('span', { class: 'm-label' }, 'Stock en direct'), demoStock)),
      feat('f-fab', 'Fabrication', 'Les recettes calculent le coût de revient.', 'La ferme transforme son blé en farine, la pizzeria sa farine en pâte. Windy Desk vérifie les ingrédients, les retire du stock et ajoute le produit fini.',
        ['Fournées possibles calculées en direct', 'Ordres de fabrication horodatés', 'Coût de revient à l’unité'],
        h('div', { class: 'd-card' }, h('div', { class: 'rc-head' }, h('div', { class: 'art' }, h('b', { class: 'd-title' }, 'Pâte à pizza'), h('small', {}, 'Donne 10 pâtons · 20 min')), pill('6 fournées possibles', 'ok')),
          h('ul', { class: 'ins' }, [['Farine', '2 kg', '22 kg'], ['Eau', '2 bouteilles', '60 bouteilles']].map(([n, q, s2]) => h('li', {}, h('span', {}, n), h('span', { class: 'need' }, q), h('span', { class: 'have' }, ico('coche', 16), 'en stock : ' + s2)))),
          h('div', { class: 'rc-foot' }, h('span', { class: 'muted small' }, 'Coût de revient : 1,30 $ par pâton'), h('span', { class: 'btn primary small' }, ico('fiole', 16), 'Fabriquer'))), true),
      feat('f-equipe', 'Employés et véhicules', 'Qui est en service, qui a quel véhicule.', 'Fiches du personnel, prises de service, paie en un clic. La flotte suit chaque sortie : conducteur, carburant, dégâts et réparations passées en dépense.',
        ['Un compte et un grade par employé', 'Avertissements et licenciements tracés', 'Assurances à renouveler signalées'],
        h('div', { class: 'd-card' }, h('ul', { class: 'people' }, [['Lucia Bianchi', 'Gérante', true], ['Tom Rizzo', 'Pizzaiolo', true], ['Nina Costa', 'Serveuse · en livraison', true], ['Hugo Martin', 'Stagiaire', false]].map(([n, g, on]) =>
          h('li', {}, h('span', { class: 'ava sm' }, initials(n)), h('span', {}, h('b', {}, n), h('small', {}, g)), on ? liveDot('En service') : h('span', { class: 'muted small' }, 'Hors service')))),
          h('div', { class: 'vmini' }, h('div', { class: 'vc-top' }, h('div', {}, h('b', {}, 'Scooter Faggio'), h('small', { class: 'muted' }, ' · Nina Costa')), h('span', { class: 'plate' }, 'BN 201')), gauge('Carburant', 64, 'carburant'), gauge('État', 92, 'reglages')))),
      feat('f-event', 'Événements', 'Un événement ? Il a sa propre vue.', 'Soirée, fête des vendanges, collecte : dates, équipe, stock réservé, tâches, objectif. Pendant l’événement, les ventes et le résultat s’affichent en direct.',
        ['Compte à rebours et objectif', 'Stock réservé vérifié', 'Bilan figé à la clôture'],
        h('div', { class: 'd-card' }, h('div', { class: 'd-head' }, h('b', {}, 'Soirée italienne'), pill('À venir', 'info')), h('span', { class: 'm-label' }, 'Commence dans'), cdBoxes,
          h('div', { class: 'ev-goal' }, h('div', { class: 'g-top' }, h('span', {}, 'Objectif de ventes'), h('b', {}, '3 000 $')), h('span', { class: 'meter big' }, h('i', { style: 'width:0%' })), h('div', { class: 'g-top muted small' }, h('span', {}, '3 tâches sur 5 à faire'), h('span', {}, '40 pâtons réservés')))), true))),
    h('section', { class: 'l-sec l-alt', id: 'modules' }, h('div', { class: 'l-wrap' },
      h('div', { class: 'l-sec-head rv' }, h('span', { class: 'l-eyebrow' }, MODULES.length + ' modules'), h('h2', {}, 'Prends ce qu’il te faut. Rien de plus.'), h('p', {}, 'Le socle est toujours inclus : tableau de bord, comptabilité, équipe, grades et journal.')),
      h('div', { class: 'l-mods' }, MODULES.map((m, i) => h('article', { class: 'l-mod rv', style: '--i:' + (i % 4) }, h('div', { class: 'l-mod-top' }, h('span', { class: 'l-mod-ic' }, ico(m.icon, 22)), h('b', {}, m.price + ' $ / sem.')), h('h3', {}, m.name), h('p', {}, m.text)))))),
    h('section', { class: 'l-sec' }, h('div', { class: 'l-wrap' },
      h('div', { class: 'l-sec-head rv' }, h('span', { class: 'l-eyebrow' }, 'Mise en place'), h('h2', {}, 'Ouvert le jour même, en trois étapes.')),
      h('ol', { class: 'l-steps' }, [['Tu choisis tes modules', 'Un pack de départ ou à la carte. Tu reçois un devis en PNG ou en PDF, prêt à envoyer.'],
        ['L’entreprise reçoit son espace', 'Une adresse à son nom, comme /bella-napoli, et un lien d’activation pour le patron.'],
        ['L’équipe se connecte', 'Chaque employé a son compte et ne voit que ce que son grade permet.']].map(([t, d], i) => h('li', { class: 'rv', style: '--i:' + i }, h('span', { class: 'l-num' }, String(i + 1).padStart(2, '0')), h('h3', {}, t), h('p', {}, d)))))),
    h('section', { class: 'l-sec l-dark', id: 'fisc' }, h('div', { class: 'l-wrap l-split' },
      h('div', { class: 'rv' }, h('span', { class: 'l-eyebrow' }, 'Pour l’administration du serveur'), h('h2', {}, 'Le fisc a son propre accès.'),
        h('p', {}, 'Le contrôleur voit toutes les entreprises en lecture seule. Windy Desk croise déclarations, comptabilité et stock, et lui montre où regarder.'),
        h('ul', { class: 'l-points' }, ['Chiffre d’affaires sous-déclaré repéré', 'Retraits d’espèces sans justificatif', 'Contrôles et redressements', 'Chaque consultation notée dans le journal de l’entreprise'].map(p => h('li', {}, ico('coche', 18), p))),
        h('a', { class: 'btn big l-inv', href: '/connexion/plateforme' }, 'Accès du fisc', ico('fleche', 18))),
      h('div', { class: 'l-fisc-visual rv' }, h('div', { class: 'd-card' }, h('div', { class: 'd-head' }, h('b', {}, 'Bar Le Vespucci'), pill('Risque élevé', 'bad')),
        h('ul', { class: 'anos' }, [[3, 'Déclaration de la semaine 44 en retard', 'Rien n’a été déclaré ni payé.'], [3, 'Chiffre d’affaires sous-déclaré', 'Écart de 6 023 $ en semaine 42.'], [3, 'Retrait d’espèces de 18 000 $', 'Sans justificatif.']].map(([sv, t, d]) =>
          h('li', { class: 'sev' + sv }, pill('Élevé', 'bad'), h('div', {}, h('b', {}, t), h('span', {}, d))))),
        h('div', { class: 'd-foot' }, h('span', { class: 'btn small' }, ico('loupe', 16), 'Ouvrir un contrôle'), h('span', { class: 'btn small danger' }, 'Redressement')))))),
    h('section', { class: 'l-sec', id: 'tarifs' }, h('div', { class: 'l-wrap' },
      h('div', { class: 'l-sec-head rv' }, h('span', { class: 'l-eyebrow' }, 'Tarifs'), h('h2', {}, 'À la semaine, en argent RP.'), h('p', {}, 'Commence par un pack, puis ajoute ou retire des modules quand tu veux.')),
      h('div', { class: 'l-packs' }, [['Commerce', 'Pizzeria, bar, restaurant, club'], ['Ferme', 'Exploitation, pêcherie, vignoble'], ['Association', 'Banque alimentaire, refuge, collecte']].map(([k, who], i) => {
        const price = PACKS[k].reduce((a, id) => a + MODULES.find(m => m.id === id).price, 0);
        return h('article', { class: 'l-pack rv' + (i === 0 ? ' best' : ''), style: '--i:' + i }, i === 0 ? h('span', { class: 'l-badge' }, 'Le plus choisi') : null, h('h3', {}, k), h('p', { class: 'muted' }, who),
          h('div', { class: 'l-price' }, h('b', {}, money(price)), h('span', {}, '/ semaine')), h('ul', { class: 'l-points' }, PACKS[k].map(id => h('li', {}, ico('coche', 16), MODULES.find(m => m.id === id).name))),
          h('button', { class: 'btn wide' + (i === 0 ? ' primary' : ''), type: 'button', onclick: () => choosePack(k) }, 'Composer à partir de ce pack'));
      })),
      h('div', { class: 'l-cfg rv', id: 'configurateur' }, h('div', { class: 'cfg-left' }, h('h3', {}, 'Compose ton offre'), h('p', { class: 'muted' }, 'Touche un module pour l’ajouter ou le retirer.'), cfgMods),
        h('div', { class: 'cfg-right' }, h('label', { class: 'f' }, 'Ton entreprise', cfgName), h('div', { class: 'f' }, 'Durée de la licence', cfgWeeks), h('div', { class: 'cfg-sum' }, cfgWeek, cfgTotal, cfgSub), cfgGo)))),
    h('section', { class: 'l-sec l-alt', id: 'questions' }, h('div', { class: 'l-wrap l-narrow' },
      h('div', { class: 'l-sec-head rv' }, h('span', { class: 'l-eyebrow' }, 'Questions'), h('h2', {}, 'Ce qu’on nous demande le plus.')),
      h('div', { class: 'l-faq' }, FAQ.map(([q, a], i) => h('details', { class: 'rv', open: i === 0 }, h('summary', {}, h('span', {}, q), h('i', { class: 'l-plus', 'aria-hidden': 'true' })), h('p', {}, a)))))),
    h('section', { class: 'l-cta' }, h('div', { class: 'l-wrap' }, lockup('cta-lock rv'), h('h2', { class: 'rv' }, 'Ouvre ton espace.'), h('p', { class: 'rv' }, 'Compose ton offre, reçois ton devis, et ton entreprise a son espace le jour même.'),
      h('div', { class: 'l-ctas rv' }, h('button', { class: 'btn primary big', type: 'button', onclick: () => jump('configurateur') }, 'Composer mon offre', ico('fleche', 18)), h('a', { class: 'btn big', href: '/connexion' }, 'Se connecter')))),
    h('footer', { class: 'l-foot' }, h('div', { class: 'l-wrap l-foot-in' }, h('a', { class: 'l-brand', href: '/' }, butterfly('l-fly', 60), h('span', { class: 'l-wm' }, h('b', {}, 'Windy'), ' Desk')),
      h('nav', {}, LINKS.map(([id, l]) => h('button', { type: 'button', onclick: () => jump(id) }, l)), h('a', { href: '/connexion' }, 'Connexion')),
      h('p', {}, 'Un produit Windy Project, développé par Windy Dev. Les entreprises et les chiffres montrés en exemple sur cette page sont fictifs.'))));

  $app.replaceChildren(page);
  window.scrollTo(0, 0);
  // barre de navigation qui se marque au défilement, et petites montées des blocs quand ils arrivent à l'écran (le contenu reste visible sans elles)
  const onScroll = () => navbar.classList.toggle('scrolled', window.scrollY > 12);
  if (window.__landScroll) window.removeEventListener('scroll', window.__landScroll);
  window.__landScroll = onScroll;
  window.addEventListener('scroll', onScroll, { passive: true });
  onScroll();
  if (!calm && 'IntersectionObserver' in window) {
    const io = new IntersectionObserver(es => es.forEach(en => { if (en.isIntersecting) { en.target.classList.add('in'); io.unobserve(en.target); } }), { rootMargin: '0px 0px -8% 0px' });
    page.querySelectorAll('.rv').forEach(el => { if (el.getBoundingClientRect().top > innerHeight) { el.classList.add('wait'); io.observe(el); } });
  }
  // la barre d'objectif de l'événement se remplit une fois arrivée à l'écran
  const evBar = page.querySelector('#f-event .meter.big i');
  if (evBar) setTimeout(() => { evBar.style.width = '38%'; }, calm ? 0 : 900);
}
