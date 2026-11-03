// ================================================================== Windy Desk · les modules de l'espace entreprise
// Caisse, stock, fabrication, récolte, factures, dons, véhicules, événements, fiche employé.
// Chaque page lit ses données (les règles de la base filtrent ce que le grade peut voir) et écrit par les fonctions SQL.
import { S, calm, h, ico, modal, toast, run, table, pill, strip, fchips, dailyBars, money, money2, initials, date, ago, clock, liveDot, gauge, untilText, pad } from './ui.js';
import { rpc, rows } from './sb.js';
import { exportModal, invoiceDoc } from './doc.js';

const DAY = 864e5, HOUR = 36e5;
const num = n => Number(n || 0).toLocaleString('fr-FR', { maximumFractionDigits: 3 });
const qtyOf = (it, n) => num(n) + ' ' + (it ? it.unit : '');
const dayStart = (t = Date.now()) => { const d = new Date(t); d.setHours(0, 0, 0, 0); return d.getTime(); };
const weekStart = () => { const d = new Date(); d.setHours(0, 0, 0, 0); d.setDate(d.getDate() - ((d.getDay() + 6) % 7)); return d.getTime(); };
const iso = t => new Date(t).toISOString();
const ms = s => new Date(s).getTime();
const foot = (m, ...btns) => h('div', { class: 'foot' }, h('button', { class: 'btn', type: 'button', onclick: () => m.close() }, 'Annuler'), ...btns);
const field = (label, ctl, cls) => h('label', { class: 'f' + (cls ? ' ' + cls : '') }, label, ctl);
const input = (attrs) => h('input', Object.assign({ type: 'text' }, attrs));
const numIn = (value, attrs) => h('input', Object.assign({ type: 'number', value, class: 'short' }, attrs || {}));
const itemOptions = (items, selected, label) => items.map(i => h('option', { value: i.id, selected: i.id === selected }, label ? label(i) : i.name));

// ---------------------------------------------------------------- articles (partagés : stock, caisse, récolte, dons, factures)
export function itemModal(ctx, it, preset) {
  const p = Object.assign({}, preset || {}, it || {});
  const name = input({ maxlength: 40, value: p.name || '', placeholder: 'Ex. Farine, Margherita, Blé' });
  const unit = input({ maxlength: 16, value: p.unit || 'unité', class: 'short' });
  const cat = input({ maxlength: 30, value: p.category || 'Divers' });
  const place = input({ maxlength: 30, value: p.place || 'Réserve' });
  const thr = numIn(Number(p.threshold || 0), { min: 0, step: 'any' });
  const cost = numIn(Number(p.cost || 0), { min: 0, step: 0.01 });
  const price = numIn(p.price ? Number(p.price) : '', { min: 0, step: 0.01, placeholder: '—' });
  const bonus = numIn(p.harvest_bonus !== null && p.harvest_bonus !== undefined ? Number(p.harvest_bonus) : '', { min: 0, step: 0.01, placeholder: '—' });
  const has = m => ctx.me.modules.includes(m);
  const ok = h('button', { class: 'btn primary' }, it ? 'Enregistrer' : 'Créer l’article');
  const del = it ? h('button', { class: 'btn danger', type: 'button' }, 'Retirer l’article') : null;
  const m = modal(it ? it.name : 'Nouvel article', field('Nom', name),
    h('div', { class: 'row' }, field('Unité', unit), field('Catégorie', cat, 'grow'), field('Emplacement', place, 'grow')),
    h('div', { class: 'row' }, field('Seuil d’alerte', thr), field('Coût unitaire ($)', cost),
      has('caisse') ? field('Prix de vente ($)', price) : null, has('recolte') ? field('Prime / unité récoltée ($)', bonus) : null),
    h('p', { class: 'note' }, (has('caisse') ? 'Avec un prix de vente, l’article est à la carte de la caisse. ' : '') + (has('recolte') ? 'Avec une prime, il se récolte. ' : '') + 'Le stock se remplit par les mouvements, pas ici.'),
    foot({ close: () => m.close() }, del, ok));
  if (del) del.addEventListener('click', () => run(del, async () => { await rpc('archive_item', { p_item: it.id }); m.close(); ctx.reload(); return { message: it.name + ' retiré.' }; }));
  ok.addEventListener('click', () => run(ok, async () => {
    if (name.value.trim().length < 2) throw new Error('Donne un nom à l’article.');
    await rpc('save_item', { p_org: ctx.me.id, p_id: it ? it.id : null, p_name: name.value.trim(), p_unit: unit.value, p_category: cat.value, p_place: place.value,
      p_threshold: Number(thr.value) || 0, p_cost: Number(cost.value) || 0, p_price: price.value === '' ? null : Number(price.value), p_bonus: bonus.value === '' ? null : Number(bonus.value) });
    m.close(); ctx.reload(); return { message: it ? 'Article enregistré.' : 'Article créé.' };
  }));
  return m;
}

// ---------------------------------------------------------------- caisse
const possible = (it, items, recipes, inputs) => {
  const r = recipes.find(x => x.item_id === it.id);
  if (!r) return Number(it.qty);
  const ins = inputs.filter(x => x.recipe_id === r.id);
  const made = ins.length ? Math.min(...ins.map(x => { const i = items.find(y => y.id === x.item_id); return i ? Math.floor(Number(i.qty) / (Number(x.qty) / Number(r.yield)) + 1e-9) : 0; })) : 0;
  return Number(it.qty) + made;
};
export async function caisse(ctx) {
  const { me, can } = ctx;
  const [items, recipes, inputs, sales] = await Promise.all([rows('items', q => q.eq('org_id', me.id).eq('archived', false).order('name')),
    rows('recipes', q => q.eq('org_id', me.id).eq('archived', false)), rows('recipe_inputs'),
    rows('sales', q => q.eq('org_id', me.id).gte('at', iso(dayStart())).order('at', { ascending: false }))]);
  const menu = items.filter(i => i.price !== null);
  if (!S.cart || S.cart.org !== me.id) S.cart = { org: me.id, lines: [], pay: 'carte' };
  const cart = S.cart;
  const cats = ['Tout'].concat([...new Set(menu.map(i => i.category))]);
  const cat = cats.includes(S.filters.menuCat) ? S.filters.menuCat : 'Tout';
  const inCart = id => (cart.lines.find(l => l.id === id) || { n: 0 }).n;
  const total = () => cart.lines.reduce((a, l) => a + l.price * l.n, 0);
  const ticket = h('div', { class: 'ticket' }), cartbar = h('div', { class: 'cartbar' });
  const payBtn = h('button', { class: 'btn primary wide' });
  const tiles = h('div', { class: 'tiles-pos' });
  const add = (it, d) => {
    const l = cart.lines.find(x => x.id === it.id), need = (l ? l.n : 0) + d;
    if (d > 0 && need > possible(it, items, recipes, inputs)) return toast('Plus assez de stock pour une « ' + it.name + ' » de plus.', true);
    if (l) { l.n += d; if (l.n <= 0) cart.lines.splice(cart.lines.indexOf(l), 1); } else if (d > 0) cart.lines.push({ id: it.id, name: it.name, price: Number(it.price), n: d });
    paintTiles(); paintTicket();
  };
  const paintTiles = () => tiles.replaceChildren(...menu.filter(i => cat === 'Tout' || i.category === cat).map(it => {
    const n = possible(it, items, recipes, inputs);
    return h('button', { type: 'button', class: 'tilebtn' + (n <= 0 ? ' out' : n < 5 ? ' few' : '') + (inCart(it.id) ? ' picked' : ''), disabled: n <= 0, onclick: () => add(it, 1) },
      h('span', { class: 'tb-name' }, it.name), h('b', { class: 'tb-price' }, money2(it.price)), h('span', { class: 'tb-left' }, n <= 0 ? 'Rupture' : n + ' possible' + (n > 1 ? 's' : '')),
      h('span', { class: 'inc' }, inCart(it.id) ? '×' + inCart(it.id) : ''));
  }));
  const paintTicket = () => {
    const n = cart.lines.reduce((a, l) => a + l.n, 0);
    payBtn.replaceChildren(ico('coche', 18), n ? 'Encaisser ' + money2(total()) : 'Encaisser');
    payBtn.disabled = !n;
    ticket.replaceChildren(h('div', { class: 'ticket-head' }, h('b', {}, 'Ticket en cours'), h('span', { class: 'muted small' }, clock(Date.now()) + ' · ' + me.member_name)),
      n ? h('ul', { class: 'lines' }, cart.lines.map(l => h('li', {}, h('span', { class: 'ln-name' }, l.name), h('span', { class: 'stepper' },
        h('button', { type: 'button', 'aria-label': 'Retirer : ' + l.name, onclick: () => add(menu.find(i => i.id === l.id), -1) }, ico('moins', 16)), h('b', {}, l.n),
        h('button', { type: 'button', 'aria-label': 'Ajouter : ' + l.name, onclick: () => add(menu.find(i => i.id === l.id), 1) }, ico('ajout', 16))), h('span', { class: 'ln-amt' }, money2(l.price * l.n)))))
        : h('div', { class: 'empty' }, 'Touche un produit pour l’ajouter.'),
      h('div', { class: 'ticket-total' }, h('span', {}, 'Total'), h('b', {}, money2(total()))),
      h('div', { class: 'seg full', role: 'group', 'aria-label': 'Moyen de paiement' }, [['carte', 'Carte'], ['especes', 'Espèces']].map(([k, l]) =>
        h('button', { type: 'button', class: cart.pay === k ? 'on' : null, 'aria-pressed': String(cart.pay === k), onclick: () => { cart.pay = k; paintTicket(); } }, l))),
      ...[payBtn, n ? h('button', { class: 'btn wide ghost', type: 'button', onclick: () => { cart.lines = []; paintTiles(); paintTicket(); } }, 'Vider le ticket') : null].filter(Boolean));
    cartbar.replaceChildren(h('span', {}, h('b', {}, n + ' article' + (n > 1 ? 's' : '')), ' · ' + money2(total())),
      h('button', { class: 'btn primary', type: 'button', disabled: !n, onclick: () => ticket.scrollIntoView({ behavior: calm ? 'auto' : 'smooth', block: 'start' }) }, 'Voir le ticket'));
    cartbar.classList.toggle('show', n > 0);
  };
  payBtn.addEventListener('click', () => run(payBtn, async () => {
    const r = await rpc('sell', { p_org: me.id, p_lines: cart.lines.map(l => ({ item: l.id, qty: l.n })), p_payment: cart.pay });
    cart.lines = [];
    ctx.reload();
    return { message: 'Ticket n°' + r.num + ' encaissé : ' + money2(r.total) + (r.event ? ' · compté dans l’événement en cours.' : '.') };
  }));
  paintTiles(); paintTicket();
  const sum = sales.reduce((a, s) => a + Number(s.total), 0);
  ctx.show('caisse', 'Caisse', [liveDot('Ouverte'), ' ' + sales.length + ' ticket(s) aujourd’hui · ' + money(sum)],
    [can('stock') ? h('button', { class: 'btn', onclick: () => itemModal(ctx, null, { category: 'Carte', place: 'Comptoir', price: 10 }) }, ico('ajout', 18), 'Produit à la carte') : null],
    menu.length ? h('div', { class: 'possys' },
      h('section', { class: 'card pos-menu' }, fchips(cats.map(c => [c, c]), cat, k => { S.filters.menuCat = k; caisse(ctx).catch(ctx.fail); }, 'Catégorie'), tiles,
        h('p', { class: 'note' }, 'Le nombre possible vient du stock. Un produit qui a une recette se prépare à la commande : ses ingrédients sortent du stock à l’encaissement.')),
      h('section', { class: 'card pos-ticket' }, ticket))
      : h('section', { class: 'card' }, h('div', { class: 'empty' }, 'La carte est vide. Un article avec un prix de vente devient un produit de la caisse' + (can('stock') ? ' : utilise « Produit à la carte ».' : '.'))),
    h('section', { class: 'card' }, h('h2', {}, 'Tickets du jour'), table([['Heure', s => clock(ms(s.at)), 'num', s => s.at], ['N°', s => '#' + s.num, 'num', s => s.num],
      ['Articles', s => s.lines.map(l => l.qty + ' ' + l.name).join(', ')], ['Paiement', s => s.payment === 'carte' ? 'Carte' : 'Espèces'], ['Vendeur', s => s.by_name],
      ['Total', s => h('b', {}, money2(s.total)), 'num', s => Number(s.total)]], sales, null, 'Aucun ticket aujourd’hui.')),
    cartbar);
}

// ---------------------------------------------------------------- stock
function moveModal(ctx, items, itemId, kind) {
  const sel = h('select', {}, itemOptions(items, itemId, i => i.name + ' (' + qtyOf(i, i.qty) + ')'));
  let k = kind || 'in';
  const seg = h('div', { class: 'seg full', role: 'group', 'aria-label': 'Type de mouvement' });
  const qty = numIn(10, { min: 0, step: 'any' }), why = input({ maxlength: 120, placeholder: 'Livraison, consommation, casse…' });
  const paid = numIn('', { min: 0, step: 0.01, placeholder: '0' });
  const paidRow = field('Payé (facultatif, enregistré en dépense « Achats »)', paid);
  const paintSeg = () => { seg.replaceChildren(...[['in', 'Entrée'], ['out', 'Sortie'], ['loss', 'Perte']].map(([v, l]) => h('button', { type: 'button', class: k === v ? 'on' : null, 'aria-pressed': String(k === v), onclick: () => { k = v; paintSeg(); } }, l))); paidRow.hidden = k !== 'in'; };
  paintSeg();
  const ok = h('button', { class: 'btn primary' }, 'Enregistrer');
  const m = modal('Mouvement de stock', field('Article', sel), seg, h('div', { class: 'row' }, field('Quantité', qty), field('Motif', why, 'grow')), paidRow, foot({ close: () => m.close() }, ok));
  ok.addEventListener('click', () => run(ok, async () => {
    const n = Math.abs(Number(qty.value) || 0);
    if (!n) throw new Error('Indique une quantité.');
    const reason = (k === 'loss' ? 'Perte · ' : '') + (why.value.trim() || (k === 'in' ? 'Entrée' : k === 'out' ? 'Sortie' : 'non précisée'));
    const left = await rpc('move_stock', { p_org: ctx.me.id, p_item: sel.value, p_delta: k === 'in' ? n : -n, p_reason: reason, p_paid: k === 'in' ? Number(paid.value) || 0 : 0 });
    const it = items.find(i => i.id === sel.value);
    m.close(); ctx.reload(); return { message: it.name + ' : ' + (k === 'in' ? '+' : '−') + qtyOf(it, n) + '. Stock : ' + qtyOf(it, left) + '.' };
  }));
}
export async function stock(ctx) {
  const { me } = ctx;
  const [items, moves] = await Promise.all([rows('items', q => q.eq('org_id', me.id).eq('archived', false).order('name')),
    rows('stock_moves', q => q.eq('org_id', me.id).order('at', { ascending: false }).limit(40))]);
  const places = [...new Set(items.map(i => i.place))].sort();
  const place = places.includes(S.filters.place) ? S.filters.place : 'Tout', lowOnly = !!S.filters.low;
  const low = i => Number(i.qty) < Number(i.threshold);
  const search = h('input', { type: 'search', placeholder: 'Chercher un article', 'aria-label': 'Chercher un article', value: S.filters.q || '' });
  const body = h('div', {});
  const paint = () => {
    const q = (S.filters.q || '').toLowerCase();
    const list = items.filter(i => (place === 'Tout' || i.place === place) && (!lowOnly || low(i)) && (!q || (i.name + ' ' + i.category).toLowerCase().includes(q)));
    body.replaceChildren(table([
      ['Article', i => h('span', { class: 'art' }, h('b', {}, i.name), h('small', {}, i.category + (i.price !== null ? ' · à la carte ' + money2(i.price) : '') + (i.harvest_bonus !== null ? ' · récoltable' : '')))],
      ['Emplacement', i => i.place],
      ['Quantité', i => h('span', { class: 'qtycell' }, h('b', {}, qtyOf(i, i.qty)), Number(i.threshold) > 0 ? h('span', { class: 'meter ' + (low(i) ? 'bad' : Number(i.qty) < Number(i.threshold) * 1.5 ? 'warn' : 'ok') },
        h('i', { style: 'width:' + Math.min(100, Math.round(Number(i.qty) / (Number(i.threshold) * 3) * 100)) + '%' })) : null), 'num', i => Number(i.qty)],
      ['Seuil', i => low(i) ? pill('Sous le seuil (' + num(i.threshold) + ')', 'bad') : h('span', { class: 'muted' }, num(i.threshold)), 'num', i => Number(i.threshold)],
      ['Valeur', i => money(Number(i.qty) * Number(i.cost)), 'num', i => Number(i.qty) * Number(i.cost)],
      ['', i => h('span', { class: 'acts' },
        h('button', { class: 'btn small', title: 'Entrée', 'aria-label': 'Entrée : ' + i.name, onclick: ev => { ev.stopPropagation(); moveModal(ctx, items, i.id, 'in'); } }, ico('ajout', 16)),
        h('button', { class: 'btn small', title: 'Sortie', 'aria-label': 'Sortie : ' + i.name, onclick: ev => { ev.stopPropagation(); moveModal(ctx, items, i.id, 'out'); } }, ico('moins', 16)),
        h('button', { class: 'btn small', title: 'Modifier', 'aria-label': 'Modifier : ' + i.name, onclick: ev => { ev.stopPropagation(); itemModal(ctx, i); } }, ico('reglages', 16)))]
    ], list, null, items.length ? 'Aucun article ne correspond.' : 'Aucun article. Crée le premier.'));
  };
  let t;
  search.addEventListener('input', () => { clearTimeout(t); t = setTimeout(() => { S.filters.q = search.value.trim(); paint(); }, 150); });
  paint();
  const value = items.reduce((a, i) => a + Number(i.qty) * Number(i.cost), 0);
  const week = moves.filter(x => ms(x.at) > Date.now() - 7 * DAY);
  const itemName = id => (items.find(i => i.id === id) || { name: 'Article retiré' }).name;
  ctx.show('stock', 'Stock', items.length + ' article(s) · ' + places.length + ' emplacement(s)',
    [search, items.length ? h('button', { class: 'btn primary', onclick: () => moveModal(ctx, items) }, ico('boite', 18), 'Mouvement') : null, h('button', { class: 'btn', onclick: () => itemModal(ctx) }, ico('ajout', 18), 'Nouvel article')],
    h('section', { class: 'card' }, strip([['Valeur du stock', money(value)], ['Sous le seuil', String(items.filter(low).length), items.some(low) ? 'neg' : null],
      ['Mouvements · 7 jours', String(week.length)], ['Pertes · 7 jours', String(week.filter(x => /^Perte/.test(x.reason)).length)]])),
    h('section', { class: 'card' }, h('div', { class: 'fbar' }, fchips(['Tout'].concat(places).map(p => [p, p, p === 'Tout' ? items.length : items.filter(i => i.place === p).length]), place, k => { S.filters.place = k; stock(ctx).catch(ctx.fail); }, 'Emplacement'),
      h('label', { class: 'check fcheck' }, h('input', { type: 'checkbox', checked: lowOnly, onchange: ev => { S.filters.low = ev.target.checked; stock(ctx).catch(ctx.fail); } }), 'Sous le seuil seulement')), body),
    h('section', { class: 'card' }, h('h2', {}, 'Derniers mouvements'), table([['Quand', x => date(ms(x.at)), 'num', x => x.at], ['Article', x => itemName(x.item_id)],
      ['Mouvement', x => h('b', { class: 'amt ' + (Number(x.delta) > 0 ? 'in' : 'out') }, (Number(x.delta) > 0 ? '+' : '−') + num(Math.abs(Number(x.delta)))), 'num', x => Number(x.delta)], ['Motif', x => x.reason], ['Par', x => x.by_name]],
      moves, null, 'Aucun mouvement.')));
}

// ---------------------------------------------------------------- fabrication
function recipeModal(ctx, items, r, inputs) {
  const prod = h('select', {}, itemOptions(items, r ? r.item_id : null));
  const yld = numIn(r ? Number(r.yield) : 1, { min: 0, step: 'any' }), mins = numIn(r ? r.minutes : 10, { min: 0 });
  const list = h('div', { class: 'stack' });
  const addRow = (itemId, q) => {
    const sel = h('select', { 'aria-label': 'Ingrédient' }, itemOptions(items, itemId)), qn = numIn(q || 1, { min: 0, step: 'any' });
    const row = h('div', { class: 'row' }, h('label', { class: 'f grow' }, 'Ingrédient', sel), h('label', { class: 'f' }, 'Quantité', qn),
      h('button', { class: 'btn small', type: 'button', 'aria-label': 'Retirer l’ingrédient', onclick: () => row.remove() }, ico('moins', 16)));
    row._v = () => ({ item: sel.value, qty: Number(qn.value) || 0 });
    list.append(row);
  };
  (inputs && inputs.length ? inputs : [{}]).forEach(x => addRow(x.item_id, x.qty ? Number(x.qty) : 1));
  const ok = h('button', { class: 'btn primary' }, 'Enregistrer la recette');
  const del = r ? h('button', { class: 'btn danger', type: 'button' }, 'Retirer la recette') : null;
  const m = modal(r ? 'Recette' : 'Nouvelle recette', h('div', { class: 'row' }, field('Produit fabriqué', prod, 'grow'), field('Quantité produite par fournée', yld), field('Durée (min)', mins)),
    h('div', { class: 'f' }, 'Ingrédients (par fournée)', list), h('button', { class: 'btn small', type: 'button', onclick: () => addRow() }, ico('ajout', 16), 'Ajouter un ingrédient'),
    h('p', { class: 'note' }, 'Si le produit est à la carte, la caisse le prépare aussi à la commande avec cette recette.'), foot({ close: () => m.close() }, del, ok));
  m.box.classList.add('wide');
  if (del) del.addEventListener('click', () => run(del, async () => { await rpc('archive_recipe', { p_recipe: r.id }); m.close(); ctx.reload(); return { message: 'Recette retirée.' }; }));
  ok.addEventListener('click', () => run(ok, async () => {
    const ins = [...list.children].map(x => x._v()).filter(x => x.qty > 0);
    await rpc('save_recipe', { p_org: ctx.me.id, p_item: prod.value, p_yield: Number(yld.value) || 0, p_minutes: Number(mins.value) || 0, p_inputs: ins });
    m.close(); ctx.reload(); return { message: 'Recette enregistrée.' };
  }));
}
export async function fabrication(ctx) {
  const { me } = ctx;
  const [items, recipes, inputs, prods] = await Promise.all([rows('items', q => q.eq('org_id', me.id).eq('archived', false).order('name')),
    rows('recipes', q => q.eq('org_id', me.id).eq('archived', false)), rows('recipe_inputs'), rows('productions', q => q.eq('org_id', me.id).order('at', { ascending: false }).limit(30))]);
  const itemOf = id => items.find(i => i.id === id) || { name: '?', unit: '', qty: 0, cost: 0 };
  const cards = recipes.map(r => {
    const ins = inputs.filter(x => x.recipe_id === r.id), out = itemOf(r.item_id);
    const max = ins.length ? Math.min(...ins.map(x => Math.floor(Number(itemOf(x.item_id).qty) / Number(x.qty) + 1e-9))) : 0;
    const unitCost = ins.reduce((a, x) => a + Number(itemOf(x.item_id).cost) * Number(x.qty), 0) / Number(r.yield);
    const n = numIn(Math.min(1, Math.max(1, max)), { min: 1, max: Math.max(1, max), 'aria-label': 'Nombre de fournées' });
    const make = h('button', { class: 'btn primary', disabled: max < 1 }, ico('fiole', 18), 'Fabriquer');
    make.addEventListener('click', () => run(make, async () => {
      const k = Math.max(1, parseInt(n.value, 10) || 1);
      const made = await rpc('produce', { p_recipe: r.id, p_runs: k });
      ctx.reload(); return { message: qtyOf(out, made) + ' de ' + out.name.toLowerCase() + ' fabriqué(s).' };
    }));
    return h('section', { class: 'card recipe' + (max < 1 ? ' blocked' : '') },
      h('div', { class: 'rc-head' }, h('div', {}, h('h2', {}, out.name), h('span', { class: 'muted small' }, 'Donne ' + qtyOf(out, r.yield) + (r.minutes ? ' · ' + r.minutes + ' min' : ''))),
        pill(max < 1 ? 'Ingrédients manquants' : max + ' fournée' + (max > 1 ? 's' : '') + ' possible' + (max > 1 ? 's' : ''), max < 1 ? 'bad' : 'ok')),
      h('ul', { class: 'ins' }, ins.map(x => { const i = itemOf(x.item_id), ok = Number(i.qty) >= Number(x.qty);
        return h('li', { class: ok ? null : 'miss' }, h('span', {}, i.name), h('span', { class: 'need' }, qtyOf(i, x.qty)), h('span', { class: 'have' }, ok ? ico('coche', 16) : ico('alerte', 16), 'en stock : ' + qtyOf(i, i.qty))); })),
      h('div', { class: 'rc-foot' }, h('span', { class: 'muted small' }, 'Coût de revient : ' + money2(unitCost) + ' par ' + out.unit),
        h('button', { class: 'btn small', type: 'button', onclick: () => recipeModal(ctx, items, r, ins) }, 'Modifier'), h('label', { class: 'f inline' }, 'Fournées', n), make));
  });
  ctx.show('fabrication', 'Fabrication', recipes.length + ' recette(s) · les ingrédients sont pris dans le stock',
    [items.length >= 2 ? h('button', { class: 'btn primary', onclick: () => recipeModal(ctx, items) }, ico('ajout', 18), 'Nouvelle recette') : null],
    recipes.length ? h('div', { class: 'recipes' }, cards) : h('section', { class: 'card' }, h('div', { class: 'empty' }, items.length >= 2 ? 'Aucune recette. Crée la première : un produit, ses ingrédients et la quantité obtenue.' : 'Crée d’abord tes articles dans le Stock : le produit et ses ingrédients.')),
    h('section', { class: 'card' }, h('h2', {}, 'Ordres de fabrication'), table([['Quand', x => date(ms(x.at)), 'num', x => x.at],
      ['Produit', x => { const r = recipes.find(y => y.id === x.recipe_id); return r ? itemOf(r.item_id).name : 'Recette retirée'; }],
      ['Quantité', x => '×' + x.runs + ' · ' + num(x.produced), 'num', x => Number(x.produced)], ['Coût', x => money2(x.cost), 'num', x => Number(x.cost)], ['Par', x => x.by_name]], prods, null, 'Aucune fabrication.')));
}

// ---------------------------------------------------------------- récolte
export async function recolte(ctx) {
  const { me, can } = ctx;
  const [items, harvests, members] = await Promise.all([rows('items', q => q.eq('org_id', me.id).eq('archived', false).order('name')),
    rows('harvests', q => q.eq('org_id', me.id).order('at', { ascending: false }).limit(300)), rows('members', q => q.eq('org_id', me.id).eq('active', true).order('name'))]);
  const crops = items.filter(i => i.harvest_bonus !== null);
  const itemOf = id => items.find(i => i.id === id) || { name: '?', unit: '' };
  const wk = harvests.filter(x => ms(x.at) >= weekStart());
  const who = can('stock') ? h('select', {}, members.map(m => h('option', { value: m.id, selected: m.id === me.member_id }, m.name))) : null;
  const what = h('select', {}, crops.map(i => h('option', { value: i.id }, i.name + ' · prime ' + money2(i.harvest_bonus) + ' / ' + i.unit)));
  const qty = numIn(40, { min: 0, step: 'any' });
  const save = h('button', { class: 'btn primary' }, ico('epi', 18), 'Enregistrer');
  save.addEventListener('click', () => run(save, async () => {
    const n = Number(qty.value) || 0;
    if (n <= 0) throw new Error('Indique une quantité.');
    const bonus = await rpc('add_harvest', { p_org: me.id, p_item: what.value, p_qty: n, p_member: who ? who.value : null });
    const it = itemOf(what.value);
    ctx.reload(); return { message: qtyOf(it, n) + ' de ' + it.name.toLowerCase() + ' entré(s) en stock. Prime : ' + money2(bonus) + '.' };
  }));
  const people = {};
  wk.forEach(x => { const p = people[x.member_id || x.by_name] || (people[x.member_id || x.by_name] = { who: x.by_name, bonus: 0, by: {} }); p.bonus += Number(x.bonus); p.by[x.item_id] = (p.by[x.item_id] || 0) + Number(x.qty); });
  const board = Object.values(people).sort((a, b) => b.bonus - a.bonus), max = Math.max(1, ...board.map(r => r.bonus));
  const due = harvests.filter(x => !x.paid_at).reduce((a, x) => a + Number(x.bonus), 0);
  const pay = h('button', { class: 'btn', disabled: due <= 0 || !can('paie') }, 'Verser les primes · ' + money2(due));
  pay.addEventListener('click', () => run(pay, async () => { const v = await rpc('pay_harvest_bonuses', { p_org: me.id }); ctx.reload(); return { message: 'Primes versées : ' + money2(v) + '. La dépense est en comptabilité.' }; }));
  const tot = {};
  wk.forEach(x => { tot[x.item_id] = (tot[x.item_id] || 0) + Number(x.qty); });
  ctx.show('recolte', 'Récolte', 'Chaque saisie entre directement en stock',
    [can('stock') ? h('button', { class: 'btn', onclick: () => itemModal(ctx, null, { category: 'Récolte', place: 'Silo', harvest_bonus: 0.5 }) }, ico('ajout', 18), 'Produit récoltable') : null],
    crops.length ? h('section', { class: 'card' }, h('h2', {}, 'Nouvelle récolte'), h('div', { class: 'row' }, who ? field('Employé', who, 'grow') : null, field('Produit', what, 'grow'), field('Quantité', qty), save))
      : h('section', { class: 'card' }, h('div', { class: 'empty' }, 'Aucun produit récoltable. Un article avec une prime par unité se récolte.')),
    crops.length ? h('section', { class: 'card' }, h('h2', {}, 'Total de la semaine'), strip(crops.slice(0, 6).map(i => [i.name, qtyOf(i, tot[i.id] || 0)]))) : null,
    h('div', { class: 'grid cols2' },
      h('section', { class: 'card' }, h('h2', {}, 'Classement de la semaine'), board.length ? h('ol', { class: 'board' }, board.map((r, i) => h('li', {}, h('span', { class: 'rank' }, i + 1),
        h('div', { class: 'bd-main' }, h('div', { class: 'bd-top' }, h('b', {}, r.who), h('b', { class: 'num' }, money2(r.bonus))), h('span', { class: 'bd-bar' }, h('i', { style: 'width:' + (r.bonus / max * 100) + '%' })),
          h('small', {}, Object.entries(r.by).map(([id, q]) => qtyOf(itemOf(id), q) + ' de ' + itemOf(id).name.toLowerCase()).join(' · ')))))) : h('div', { class: 'calm' }, 'Aucune récolte cette semaine.')),
      h('section', { class: 'card' }, h('h2', {}, 'Primes au rendement'), h('div', { class: 'kv' }, crops.map(i => h('div', { class: 'kvrow' }, h('span', { class: 'k' }, i.name), h('b', {}, money2(i.harvest_bonus) + ' / ' + i.unit), h('span', {})))),
        h('p', { class: 'note', style: 'margin-top:12px' }, 'Les primes non versées s’accumulent. Les verser crée une seule dépense « Salaires » en comptabilité.'), pay)),
    h('section', { class: 'card' }, h('h2', {}, 'Dernières saisies'), table([['Quand', x => date(ms(x.at)), 'num', x => x.at], ['Employé', x => x.by_name], ['Produit', x => itemOf(x.item_id).name],
      ['Quantité', x => qtyOf(itemOf(x.item_id), x.qty), 'num', x => Number(x.qty)], ['Prime', x => money2(x.bonus), 'num', x => Number(x.bonus)], ['', x => x.paid_at ? h('span', { class: 'muted' }, 'Versée') : pill('À verser', 'warn')]],
      harvests.slice(0, 30), null, 'Aucune saisie.')));
}

// ---------------------------------------------------------------- factures
const INV = { attente: ['En attente', 'warn'], retard: ['En retard', 'bad'], payee: ['Payée', 'ok'], annulee: ['Annulée', null] };
const invState = x => x.status === 'attente' && ms(x.due_at) < Date.now() ? 'retard' : x.status;
function invoiceModal(ctx, items) {
  let toOrg = false;
  const client = input({ maxlength: 60, placeholder: 'Nom du client' }), code = input({ maxlength: 40, placeholder: 'adresse, ex. bella-napoli', autocapitalize: 'none' });
  const seg = h('div', { class: 'seg full', role: 'group', 'aria-label': 'Type de client' });
  const clientRow = field('Client', client), codeRow = field('Adresse Windy Desk du client (la facture apparaîtra chez lui)', code);
  const paintSeg = () => { seg.replaceChildren(...[[false, 'Particulier ou autre'], [true, 'Entreprise sur Windy Desk']].map(([v, l]) => h('button', { type: 'button', class: toOrg === v ? 'on' : null, 'aria-pressed': String(toOrg === v), onclick: () => { toOrg = v; paintSeg(); } }, l))); clientRow.hidden = toOrg; codeRow.hidden = !toOrg; };
  paintSeg();
  const list = h('div', { class: 'stack' });
  const addRow = () => {
    const art = h('select', { 'aria-label': 'Article' }, h('option', { value: '' }, '— libre —'), itemOptions(items, null, i => i.name + ' (' + qtyOf(i, i.qty) + ')'));
    const lbl = input({ maxlength: 60, placeholder: 'Libellé' }), q = numIn(1, { min: 0, step: 'any' }), pr = numIn(10, { min: 0, step: 0.01 });
    art.addEventListener('change', () => { const it = items.find(i => i.id === art.value); if (it) { if (!lbl.value) lbl.value = it.name; if (it.price) pr.value = Number(it.price); } });
    const row = h('div', { class: 'row' }, items.length ? h('label', { class: 'f grow' }, 'Article', art) : null, h('label', { class: 'f grow' }, 'Libellé', lbl), h('label', { class: 'f' }, 'Qté', q), h('label', { class: 'f' }, 'Prix ($)', pr),
      h('button', { class: 'btn small', type: 'button', 'aria-label': 'Retirer la ligne', onclick: () => row.remove() }, ico('moins', 16)));
    row._v = () => ({ item: art.value || null, label: lbl.value.trim(), qty: Number(q.value) || 0, price: Number(pr.value) || 0 });
    list.append(row);
  };
  addRow();
  const due = numIn(7, { min: 0, max: 90 }), take = h('input', { type: 'checkbox', checked: true });
  const ok = h('button', { class: 'btn primary' }, 'Émettre la facture');
  const m = modal('Nouvelle facture', seg, clientRow, codeRow, h('div', { class: 'f' }, 'Lignes', list), h('button', { class: 'btn small', type: 'button', onclick: addRow }, ico('ajout', 16), 'Ajouter une ligne'),
    h('div', { class: 'row' }, field('Échéance (jours)', due), items.length ? h('label', { class: 'check' }, take, 'Sortir du stock les articles facturés') : null), foot({ close: () => m.close() }, ok));
  m.box.classList.add('wide');
  ok.addEventListener('click', () => run(ok, async () => {
    const lines = [...list.children].map(r => r._v()).filter(l => l.qty > 0);
    const n = await rpc('create_invoice', { p_org: ctx.me.id, p_client: toOrg ? null : client.value, p_client_code: toOrg ? code.value : null, p_lines: lines, p_due_days: Number(due.value) || 0, p_take_stock: take.checked });
    m.close(); ctx.reload(); return { message: 'Facture ' + n + ' émise.' };
  }));
}
export async function factures(ctx) {
  const { me } = ctx;
  const [all, items] = await Promise.all([rows('invoices', q => q.order('created_at', { ascending: false }).limit(300)), rows('items', q => q.eq('org_id', me.id).eq('archived', false).order('name'))]);
  const mine = all.filter(x => x.org_id === me.id), received = all.filter(x => x.client_org_id === me.id);
  const f = S.filters.inv || 'tout';
  const list = mine.filter(x => f === 'tout' || invState(x) === f);
  const sum = st => mine.filter(x => invState(x) === st).reduce((a, x) => a + Number(x.total), 0);
  const org = { name: me.name, kind: me.kind, owner: me.member_name };
  const acts = x => h('span', { class: 'acts' },
    h('button', { class: 'btn small', title: 'Exporter en PNG ou PDF', onclick: ev => { ev.stopPropagation(); exportModal(invoiceDoc(org, x), 'facture-' + x.num); } }, ico('telecharger', 16), 'Exporter'),
    x.status === 'attente' ? h('button', { class: 'btn small', onclick: ev => { ev.stopPropagation(); run(ev.currentTarget, async () => { await rpc('pay_invoice', { p_invoice: x.id }); ctx.reload(); return { message: x.num + ' payée : ' + money2(x.total) + ' en comptabilité.' }; }); } }, 'Marquer payée') : null,
    x.status === 'attente' ? h('button', { class: 'btn small ghost', onclick: ev => { ev.stopPropagation(); run(ev.currentTarget, async () => { await rpc('cancel_invoice', { p_invoice: x.id }); ctx.reload(); return { message: x.num + ' annulée.' }; }); } }, 'Annuler') : null);
  ctx.show('factures', 'Factures', 'Clients et entreprises partenaires', [h('button', { class: 'btn primary', onclick: () => invoiceModal(ctx, items) }, ico('facture', 18), 'Nouvelle facture')],
    h('section', { class: 'card' }, strip([['À encaisser', money(sum('attente') + sum('retard'))], ['En retard', money(sum('retard')), sum('retard') ? 'neg' : null], ['Payées', money(sum('payee'))], ['Reçues à payer', money(received.filter(x => x.status === 'attente').reduce((a, x) => a + Number(x.total), 0))]])),
    h('section', { class: 'card' }, h('h2', {}, 'Factures émises'), fchips([['tout', 'Toutes', mine.length], ['attente', 'En attente'], ['retard', 'En retard'], ['payee', 'Payées'], ['annulee', 'Annulées']], f, k => { S.filters.inv = k; factures(ctx).catch(ctx.fail); }, 'Statut'),
      table([['N°', x => h('b', {}, x.num), null, x => x.num], ['Client', x => x.client], ['Émise', x => date(ms(x.created_at)), 'num', x => x.created_at], ['Échéance', x => date(ms(x.due_at)), 'num', x => x.due_at],
        ['Détail', x => x.lines.map(l => num(l.qty) + ' ' + l.label).join(', ')], ['Montant', x => h('b', {}, money2(x.total)), 'num', x => Number(x.total)], ['Statut', x => pill(INV[invState(x)][0], INV[invState(x)][1])], ['', acts]],
        list, null, 'Aucune facture.')),
    received.length ? h('section', { class: 'card' }, h('h2', {}, 'Factures reçues', h('span', { class: 'muted small' }, 'd’autres entreprises sur Windy Desk')),
      table([['N°', x => h('b', {}, x.num)], ['De', x => x.by_name], ['Émise', x => date(ms(x.created_at)), 'num', x => x.created_at], ['Échéance', x => date(ms(x.due_at)), 'num', x => x.due_at],
        ['Montant', x => h('b', {}, money2(x.total)), 'num', x => Number(x.total)], ['Statut', x => pill(INV[invState(x)][0], INV[invState(x)][1])]], received)) : null,
    h('p', { class: 'note' }, 'Une facture payée entre en comptabilité (« Factures »). Si le client est une entreprise sur Windy Desk, la dépense entre aussi dans la sienne.'));
}

// ---------------------------------------------------------------- dons et distributions
export async function dons(ctx) {
  const { me } = ctx;
  const [items, gifts, outs] = await Promise.all([rows('items', q => q.eq('org_id', me.id).eq('archived', false).order('name')),
    rows('donations', q => q.eq('org_id', me.id).order('at', { ascending: false }).limit(100)), rows('distributions', q => q.eq('org_id', me.id).order('at', { ascending: false }).limit(100))]);
  const itemOf = id => items.find(i => i.id === id) || { name: 'article retiré', unit: '' };
  const giftModal = () => {
    let k = 'argent';
    const from = input({ maxlength: 60, placeholder: 'Nom du donateur, ou vide pour Anonyme' }), seg = h('div', { class: 'seg full', role: 'group', 'aria-label': 'Type de don' });
    const amount = numIn(100, { min: 0, step: 0.01 }), art = h('select', {}, itemOptions(items)), qty = numIn(10, { min: 0, step: 'any' }), note = input({ maxlength: 160, placeholder: 'Facultatif' });
    const moneyRow = field('Montant ($)', amount), goodsRow = h('div', { class: 'row' }, field('Article', art, 'grow'), field('Quantité', qty));
    const paint = () => { seg.replaceChildren(...[['argent', 'Argent'], ['nature', 'En nature']].map(([v, l]) => h('button', { type: 'button', class: k === v ? 'on' : null, 'aria-pressed': String(k === v), disabled: v === 'nature' && !items.length, onclick: () => { k = v; paint(); } }, l))); moneyRow.hidden = k !== 'argent'; goodsRow.hidden = k === 'argent'; };
    paint();
    const ok = h('button', { class: 'btn primary' }, 'Enregistrer le don');
    const m = modal('Enregistrer un don', field('Donateur', from), seg, moneyRow, goodsRow, field('Note', note), foot({ close: () => m.close() }, ok));
    ok.addEventListener('click', () => run(ok, async () => {
      await rpc('add_donation', { p_org: me.id, p_donor: from.value, p_amount: k === 'argent' ? Number(amount.value) || 0 : null, p_item: k === 'nature' ? art.value : null, p_qty: k === 'nature' ? Number(qty.value) || 0 : null, p_note: note.value });
      m.close(); ctx.reload(); return { message: 'Don de ' + (from.value.trim() || 'Anonyme') + ' enregistré. Merci pour eux !' };
    }));
  };
  const outModal = () => {
    const to = input({ maxlength: 60, placeholder: 'Refuge, maraude, famille…' }), art = h('select', {}, itemOptions(items.filter(i => Number(i.qty) > 0), null, i => i.name + ' (' + qtyOf(i, i.qty) + ')')), qty = numIn(10, { min: 0, step: 'any' });
    const ok = h('button', { class: 'btn primary' }, 'Distribuer');
    const m = modal('Distribution', field('Bénéficiaire', to), h('div', { class: 'row' }, field('Article', art, 'grow'), field('Quantité', qty)), foot({ close: () => m.close() }, ok));
    ok.addEventListener('click', () => run(ok, async () => {
      await rpc('distribute', { p_org: me.id, p_beneficiary: to.value, p_item: art.value, p_qty: Number(qty.value) || 0, p_note: null });
      m.close(); ctx.reload(); return { message: 'Distribution enregistrée pour ' + to.value.trim() + '.' };
    }));
  };
  const wk = x => ms(x.at) >= weekStart();
  ctx.show('dons', 'Dons et distributions', 'Ce qui entre, ce qui repart, et pour qui',
    [h('button', { class: 'btn primary', onclick: giftModal }, ico('coeur', 18), 'Enregistrer un don'), items.some(i => Number(i.qty) > 0) ? h('button', { class: 'btn', onclick: outModal }, ico('fleche', 18), 'Distribuer') : null,
      h('button', { class: 'btn', onclick: () => itemModal(ctx, null, { category: 'Dons', place: 'Réserve' }) }, ico('ajout', 18), 'Article')],
    h('section', { class: 'card' }, h('h2', {}, 'Cette semaine'), strip([['Dons en argent', money(gifts.filter(d => wk(d) && d.amount !== null).reduce((a, d) => a + Number(d.amount), 0))],
      ['Dons en nature', String(gifts.filter(d => wk(d) && d.qty !== null).length)], ['Distributions', String(outs.filter(wk).length)], ['Bénéficiaires suivis', String(new Set(outs.map(d => d.beneficiary)).size)]])),
    h('div', { class: 'grid cols2' },
      h('section', { class: 'card' }, h('h2', {}, 'Dons reçus'), gifts.length ? h('ul', { class: 'dons' }, gifts.map(d => h('li', {}, h('span', { class: 'ev-ic ' + (d.amount !== null ? 'ok' : 'info') }, ico(d.amount !== null ? 'balance' : 'boite', 16)),
        h('span', {}, h('b', {}, d.donor), h('small', {}, (d.amount !== null ? money2(d.amount) : qtyOf(itemOf(d.item_id), d.qty) + ' · ' + itemOf(d.item_id).name.toLowerCase()) + (d.note ? ' · ' + d.note : ''))), h('time', {}, ago(ms(d.at)))))) : h('div', { class: 'empty' }, 'Aucun don pour l’instant.')),
      h('section', { class: 'card' }, h('h2', {}, 'Distributions'), table([['Quand', d => ago(ms(d.at)), 'num', d => d.at], ['Pour', d => h('b', {}, d.beneficiary)], ['Quoi', d => qtyOf(itemOf(d.item_id), d.qty) + ' · ' + itemOf(d.item_id).name.toLowerCase()], ['Par', d => d.by_name]], outs, null, 'Aucune distribution.'))));
}

// ---------------------------------------------------------------- véhicules
const VST = { garage: ['Au garage', 'ok'], sortie: ['En sortie', 'info'], reparation: ['En réparation', 'warn'] };
export async function vehicules(ctx) {
  const { me, can } = ctx;
  const [fleet, logs, members] = await Promise.all([rows('vehicles', q => q.eq('org_id', me.id).eq('archived', false).order('model')),
    rows('vehicle_logs', q => q.eq('org_id', me.id).order('at', { ascending: false }).limit(40)), rows('members', q => q.eq('org_id', me.id).eq('active', true).order('name'))]);
  const manage = can('vehicules');
  const nameOf = id => (members.find(m => m.id === id) || { name: '—' }).name;
  const takeOut = v => {
    const who = h('select', {}, members.map(m => h('option', { value: m.id, selected: m.id === me.member_id }, m.name + (m.licences && m.licences.length ? ' · permis ' + m.licences.join(', ') : ''))));
    const why = input({ maxlength: 80, placeholder: 'Livraison, récolte, maraude…' }), ok = h('button', { class: 'btn primary' }, 'Sortir le véhicule');
    const m = modal('Sortir ' + v.model + ' · ' + v.plate, field('Conducteur', who), field('Pour quoi faire', why), foot({ close: () => m.close() }, ok));
    ok.addEventListener('click', () => run(ok, async () => { await rpc('take_vehicle', { p_vehicle: v.id, p_driver: who.value, p_note: why.value }); m.close(); ctx.reload(); return { message: v.model + ' sorti.' }; }));
  };
  const bringBack = v => {
    const fuel = h('input', { type: 'range', min: 0, max: 100, value: Math.max(0, v.fuel - 15), 'aria-label': 'Carburant' }), hp = h('input', { type: 'range', min: 0, max: 100, value: v.condition, 'aria-label': 'État' });
    const fl = h('b', {}, fuel.value + ' %'), hl = h('b', {}, hp.value + ' %');
    fuel.addEventListener('input', () => { fl.textContent = fuel.value + ' %'; }); hp.addEventListener('input', () => { hl.textContent = hp.value + ' %'; });
    const km = numIn(12, { min: 0 }), note = input({ maxlength: 100, placeholder: 'RAS, accrochage, pneu crevé…' }), ok = h('button', { class: 'btn primary' }, 'Rentrer au garage');
    const m = modal('Retour de ' + v.model + ' · ' + v.plate, h('div', { class: 'f' }, h('span', { class: 'rangehead' }, 'Carburant', fl), fuel), h('div', { class: 'f' }, h('span', { class: 'rangehead' }, 'État', hl), hp),
      h('div', { class: 'row' }, field('Km parcourus', km), field('Remarque', note, 'grow')), foot({ close: () => m.close() }, ok));
    ok.addEventListener('click', () => run(ok, async () => {
      const st = await rpc('return_vehicle', { p_vehicle: v.id, p_fuel: Number(fuel.value), p_condition: Number(hp.value), p_km: Number(km.value) || 0, p_note: note.value });
      m.close(); ctx.reload(); return { message: v.model + (st === 'reparation' ? ' rentré, trop abîmé : en réparation.' : ' rentré au garage.') };
    }));
  };
  const service = (v, kind) => {
    const amount = numIn(kind === 'plein' ? 60 : kind === 'reparation' ? Math.max(80, (100 - v.condition) * 12) : 150, { min: 0, step: 0.01 }), days = numIn(7, { min: 1, max: 365 });
    const titles = { plein: 'Faire le plein', reparation: 'Réparation', assurance: 'Assurance' };
    const ok = h('button', { class: 'btn primary' }, 'Payer et enregistrer');
    const m = modal(titles[kind] + ' · ' + v.model, h('p', { class: 'muted' }, 'Le coût est enregistré en dépense « Entretien ».'), h('div', { class: 'row' }, field('Coût ($)', amount), kind === 'assurance' ? field('Durée (jours)', days) : null), foot({ close: () => m.close() }, ok));
    ok.addEventListener('click', () => run(ok, async () => { await rpc('service_vehicle', { p_vehicle: v.id, p_kind: kind, p_amount: Number(amount.value) || 0, p_days: Number(days.value) || 0 }); m.close(); ctx.reload(); return { message: titles[kind] + ' enregistré(e).' }; }));
  };
  const vehicleModal = v => {
    const model = input({ maxlength: 40, value: v ? v.model : '', placeholder: 'Ex. Camionnette Rumpo' }), plate = input({ maxlength: 10, value: v ? v.plate : '', placeholder: 'AB 123', class: 'short' });
    const usage = input({ maxlength: 30, value: v ? v.usage : 'Utilitaire' }), ins = h('input', { type: 'date', value: v && v.insured_until ? v.insured_until : '' });
    const ok = h('button', { class: 'btn primary' }, v ? 'Enregistrer' : 'Ajouter'), del = v ? h('button', { class: 'btn danger', type: 'button' }, 'Retirer de la flotte') : null;
    const m = modal(v ? v.model : 'Nouveau véhicule', field('Modèle', model), h('div', { class: 'row' }, field('Plaque', plate), field('Usage', usage, 'grow'), field('Assuré jusqu’au', ins)), foot({ close: () => m.close() }, del, ok));
    if (del) del.addEventListener('click', () => run(del, async () => { await rpc('archive_vehicle', { p_vehicle: v.id }); m.close(); ctx.reload(); return { message: v.model + ' retiré de la flotte.' }; }));
    ok.addEventListener('click', () => run(ok, async () => {
      await rpc('save_vehicle', { p_org: me.id, p_id: v ? v.id : null, p_model: model.value, p_plate: plate.value, p_usage: usage.value, p_insured_until: ins.value || null });
      m.close(); ctx.reload(); return { message: v ? 'Véhicule enregistré.' : 'Véhicule ajouté à la flotte.' };
    }));
  };
  const soon = v => v.insured_until && ms(v.insured_until) < Date.now() + 3 * DAY;
  const vlog = id => fleet.find(v => v.id === id) || { model: 'Véhicule retiré', plate: '' };
  ctx.show('vehicules', 'Véhicules', fleet.length + ' véhicule(s) · ' + fleet.filter(v => v.status === 'sortie').length + ' en sortie',
    [manage ? h('button', { class: 'btn', onclick: () => vehicleModal() }, ico('ajout', 18), 'Ajouter un véhicule') : null],
    h('section', { class: 'card' }, strip([['En sortie', String(fleet.filter(v => v.status === 'sortie').length)], ['Au garage', String(fleet.filter(v => v.status === 'garage').length)],
      ['En réparation', String(fleet.filter(v => v.status === 'reparation').length)], ['Assurance à renouveler', String(fleet.filter(soon).length), fleet.some(soon) ? 'neg' : null]])),
    fleet.length ? h('div', { class: 'fleet' }, fleet.map(v => h('section', { class: 'card vcard ' + v.status },
      h('div', { class: 'vc-top' }, h('div', {}, h('h2', {}, v.model), h('span', { class: 'muted small' }, v.usage + ' · ' + v.km.toLocaleString('fr-FR') + ' km')), h('span', { class: 'plate' }, v.plate)),
      h('div', { class: 'vc-status' }, pill(VST[v.status][0], VST[v.status][1]), v.status === 'sortie' ? h('span', {}, h('b', {}, nameOf(v.driver_id)), ' · depuis ' + clock(ms(v.out_since))) : null,
        soon(v) ? pill('Assurance : ' + new Date(v.insured_until).toLocaleDateString('fr-FR'), 'bad') : null),
      gauge('Carburant', v.fuel, 'carburant'), gauge('État', v.condition, 'reglages'),
      manage ? h('div', { class: 'vc-acts' }, v.status === 'garage' ? h('button', { class: 'btn primary', onclick: () => takeOut(v) }, 'Sortir') : null, v.status === 'sortie' ? h('button', { class: 'btn primary', onclick: () => bringBack(v) }, 'Rentrer') : null,
        v.status !== 'sortie' && v.condition < 100 ? h('button', { class: 'btn', onclick: () => service(v, 'reparation') }, 'Réparer') : null, v.fuel < 100 ? h('button', { class: 'btn', onclick: () => service(v, 'plein') }, 'Plein') : null,
        h('button', { class: 'btn', onclick: () => service(v, 'assurance') }, 'Assurance'), h('button', { class: 'btn ghost', onclick: () => vehicleModal(v) }, 'Modifier')) : null)))
      : h('section', { class: 'card' }, h('div', { class: 'empty' }, 'Aucun véhicule. ' + (manage ? 'Ajoute le premier à la flotte.' : ''))),
    h('section', { class: 'card' }, h('h2', {}, 'Historique'), table([['Quand', l => date(ms(l.at)), 'num', l => l.at], ['Véhicule', l => h('span', {}, h('span', { class: 'plate sm' }, vlog(l.vehicle_id).plate), ' ' + vlog(l.vehicle_id).model)],
      ['Qui', l => l.by_name], ['Action', l => l.action], ['Remarque', l => l.note || '']], logs, null, 'Rien pour l’instant.')));
}

// ---------------------------------------------------------------- événements
const evState = e => e.closed_at || ms(e.ends_at) < Date.now() ? 'fini' : ms(e.starts_at) <= Date.now() ? 'encours' : 'avenir';
const EVS = { avenir: ['À venir', 'info'], encours: ['En cours', 'ok'], fini: ['Terminé', null] };
const toLocal = t => { const d = new Date(t); return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()) + 'T' + pad(d.getHours()) + ':' + pad(d.getMinutes()); };
function eventModal(ctx, members, items, e, staff, stockRes) {
  const name = input({ maxlength: 60, value: e ? e.name : '', placeholder: 'Ex. Soirée karaoké' }), place = input({ maxlength: 80, value: e ? e.place : ctx.me.name });
  const start = h('input', { type: 'datetime-local', value: toLocal(e ? ms(e.starts_at) : dayStart(Date.now() + 2 * DAY) + 20 * HOUR) });
  const hours = numIn(e ? Math.round((ms(e.ends_at) - ms(e.starts_at)) / HOUR * 10) / 10 : 4, { min: 0.5, max: 336, step: 0.5 });
  const kind = h('select', {}, h('option', { value: 'ventes', selected: !e || e.kind === 'ventes' }, 'Ventes'), h('option', { value: 'dons', selected: e ? e.kind === 'dons' : ctx.me.is_association }, 'Dons'));
  const goal = numIn(e ? Number(e.goal) : 2000, { min: 0 }), budget = numIn(e ? Number(e.budget) : 300, { min: 0 }), note = input({ maxlength: 300, value: e && e.note ? e.note : '', placeholder: 'Facultatif' });
  const team = members.map(m => h('label', { class: 'check' }, h('input', { type: 'checkbox', value: m.id, checked: !!(staff && staff.some(s => s.member_id === m.id)) }), m.name));
  const tasks = e ? null : h('textarea', { placeholder: 'Une tâche par ligne\nEx. Préparer la scène' });
  const res = h('div', { class: 'stack' });
  const addRes = (itemId, q) => {
    const sel = h('select', { 'aria-label': 'Article' }, itemOptions(items, itemId)), qn = numIn(q || 10, { min: 0, step: 'any' });
    const row = h('div', { class: 'row' }, h('label', { class: 'f grow' }, 'Article', sel), h('label', { class: 'f' }, 'Quantité', qn), h('button', { class: 'btn small', type: 'button', 'aria-label': 'Retirer', onclick: () => row.remove() }, ico('moins', 16)));
    row._v = () => ({ item: sel.value, qty: Number(qn.value) || 0 });
    res.append(row);
  };
  (stockRes || []).forEach(r => addRes(r.item_id, Number(r.qty)));
  const ok = h('button', { class: 'btn primary' }, e ? 'Enregistrer' : 'Créer l’événement');
  const m = modal(e ? e.name : 'Nouvel événement', field('Nom', name), field('Lieu', place),
    h('div', { class: 'row' }, field('Début', start, 'grow'), field('Durée (h)', hours)),
    h('div', { class: 'row' }, field('Objectif', kind), field('Montant visé ($)', goal), field('Budget ($)', budget)), field('Note', note),
    members.length ? h('div', { class: 'f' }, 'Équipe', h('div', { class: 'checks' }, team)) : null,
    tasks ? field('Tâches', tasks) : null,
    items.length ? h('div', { class: 'f' }, 'Stock réservé', res, h('button', { class: 'btn small', type: 'button', onclick: () => addRes() }, ico('ajout', 16), 'Réserver un article')) : null,
    foot({ close: () => m.close() }, ok));
  m.box.classList.add('wide');
  ok.addEventListener('click', () => run(ok, async () => {
    const st = new Date(start.value).getTime();
    if (!st) throw new Error('Choisis la date de début.');
    const id = await rpc('save_event', { p_org: ctx.me.id, p_id: e ? e.id : null, p_name: name.value, p_place: place.value, p_kind: kind.value, p_starts: iso(st), p_ends: iso(st + (Number(hours.value) || 1) * HOUR),
      p_goal: Number(goal.value) || 0, p_budget: Number(budget.value) || 0, p_note: note.value, p_staff: team.map(l => l.firstChild).filter(c => c.checked).map(c => c.value),
      p_stock: [...res.children].map(r => r._v()).filter(r => r.qty > 0), p_tasks: tasks ? tasks.value.split('\n').map(t => t.trim()).filter(Boolean) : [] });
    m.close();
    if (e) ctx.reload(); else ctx.go('/' + ctx.me.code + '/evenements/' + id);
    return { message: e ? 'Événement enregistré.' : 'Événement créé : il a sa propre page.' };
  }));
}
export async function evenements(ctx) {
  const { me, can } = ctx;
  const [list, members, items] = await Promise.all([rows('events', q => q.eq('org_id', me.id).order('starts_at', { ascending: false }).limit(60)),
    rows('members', q => q.eq('org_id', me.id).eq('active', true).order('name')), rows('items', q => q.eq('org_id', me.id).eq('archived', false).order('name'))]);
  const order = { encours: 0, avenir: 1, fini: 2 };
  const sorted = list.slice().sort((a, b) => order[evState(a)] - order[evState(b)] || (evState(a) === 'fini' ? ms(b.starts_at) - ms(a.starts_at) : ms(a.starts_at) - ms(b.starts_at)));
  const books = await Promise.all(sorted.slice(0, 12).map(e => rpc('event_book', { p_event: e.id }).catch(() => ({ income: 0 }))));
  const card = (e, i) => {
    const st = evState(e), b = books[i] || { income: 0 }, pct = Math.min(100, Math.round(Number(b.income) / Math.max(1, Number(e.goal)) * 100));
    const u = untilText((st === 'avenir' ? ms(e.starts_at) : ms(e.ends_at)) - Date.now());
    return h('a', { class: 'card evcard ' + st, href: '/' + me.code + '/evenements/' + e.id },
      h('div', { class: 'ec-top' }, pill(EVS[st][0], EVS[st][1]), h('span', { class: 'muted small' }, st === 'avenir' ? 'dans ' + u.short : st === 'encours' ? 'encore ' + u.short : date(ms(e.ends_at)))),
      h('h2', {}, e.name), h('span', { class: 'muted small' }, (e.place ? e.place + ' · ' : '') + date(ms(e.starts_at))),
      Number(e.goal) > 0 ? h('div', { class: 'ec-goal' }, h('div', { class: 'g-top' }, h('span', {}, e.kind === 'dons' ? 'Dons collectés' : 'Ventes'), h('b', {}, money(b.income) + ' / ' + money(e.goal))), h('span', { class: 'meter' }, h('i', { style: 'width:' + pct + '%' }))) : null);
  };
  ctx.show('evenements', 'Événements', 'Chaque événement a sa propre vue : objectif, équipe, stock et bilan',
    [can('evenements') ? h('button', { class: 'btn primary', onclick: () => eventModal(ctx, members, items) }, ico('calendrier', 18), 'Créer un événement') : null],
    sorted.length ? h('div', { class: 'evgrid' }, sorted.map(card)) : h('section', { class: 'card' }, h('div', { class: 'empty' }, 'Aucun événement. ' + (can('evenements') ? 'Crée le premier : il aura sa propre page.' : ''))));
}
export async function evenement(ctx, id) {
  const { me, can } = ctx;
  const [evs, staff, stockRes, tasks, members, items, shifts] = await Promise.all([rows('events', q => q.eq('id', id)), rows('event_staff', q => q.eq('event_id', id)), rows('event_stock', q => q.eq('event_id', id)),
    rows('event_tasks', q => q.eq('event_id', id).order('id')), rows('members', q => q.eq('org_id', me.id).eq('active', true).order('name')), rows('items', q => q.eq('org_id', me.id).eq('archived', false).order('name')),
    me.modules.includes('service') ? rows('shifts', q => q.eq('org_id', me.id).is('ended_at', null)) : []]);
  const e = evs[0];
  if (!e || e.org_id !== me.id) return ctx.go('/' + me.code + '/evenements', true);
  const b = await rpc('event_book', { p_event: e.id });
  const st = evState(e), pct = Math.min(100, Math.round(Number(b.income) / Math.max(1, Number(e.goal)) * 100));
  const cd = h('div', { class: 'countdown' });
  const paintCd = () => {
    if (st === 'fini') return cd.replaceChildren(h('div', { class: 'cd-label' }, e.closed_at ? 'Clôturé le ' + date(ms(e.closed_at)) : 'Terminé le ' + date(ms(e.ends_at))));
    const u = untilText((st === 'avenir' ? ms(e.starts_at) : ms(e.ends_at)) - Date.now());
    cd.replaceChildren(h('div', { class: 'cd-label' }, st === 'avenir' ? 'Commence dans' : 'Se termine dans'),
      h('div', { class: 'cd-boxes' }, [['jours', u.d], ['heures', u.hh], ['minutes', u.mm], ['secondes', u.ss]].map(([l, v]) => h('div', { class: 'cd-box' }, h('b', {}, pad(v)), h('span', {}, l)))));
  };
  paintCd();
  clearInterval(S.timer);
  if (st !== 'fini') S.timer = setInterval(() => { if (!cd.isConnected) return clearInterval(S.timer); paintCd(); }, 1000);
  const spend = () => {
    const label = input({ maxlength: 60, placeholder: 'Ex. Location de la sono' }), amount = numIn(100, { min: 0, step: 0.01 }), ok = h('button', { class: 'btn primary' }, 'Enregistrer');
    const m = modal('Dépense de l’événement', h('div', { class: 'row' }, field('Libellé', label, 'grow'), field('Montant ($)', amount)), h('p', { class: 'note' }, 'Elle entre aussi dans la comptabilité de l’entreprise (« Achats »).'), foot({ close: () => m.close() }, ok));
    ok.addEventListener('click', () => run(ok, async () => { await rpc('event_spend', { p_event: e.id, p_label: label.value, p_amount: Number(amount.value) || 0 }); m.close(); ctx.reload(); return { message: 'Dépense ajoutée au bilan.' }; }));
  };
  const taskIn = input({ maxlength: 80, placeholder: 'Nouvelle tâche', 'aria-label': 'Nouvelle tâche' });
  const addTask = () => { if (!taskIn.value.trim()) return; rpc('event_add_task', { p_event: e.id, p_label: taskIn.value }).then(() => ctx.reload()).catch(ctx.fail); };
  taskIn.addEventListener('keydown', ev => { if (ev.key === 'Enter') addTask(); });
  const itemOf = iid => items.find(i => i.id === iid) || { name: 'Article retiré', unit: '', qty: 0 };
  const memberOf = mid => members.find(m => m.id === mid) || { name: 'Ancien membre' };
  const series = b.series.map(x => ({ t: ms(x.t), label: clock(ms(x.t)) + ' – ' + clock(ms(x.t) + HOUR), v: Number(x.v) }));
  const doneN = tasks.filter(t => t.done).length;
  ctx.show('evenements', e.name, [pill(EVS[st][0], EVS[st][1]), ' ' + (e.place ? e.place + ' · ' : '') + date(ms(e.starts_at)) + ' → ' + date(ms(e.ends_at))],
    [st !== 'fini' && can('evenements') ? h('button', { class: 'btn', onclick: spend }, ico('ajout', 18), 'Dépense') : null,
      st === 'encours' && e.kind === 'ventes' && me.modules.includes('caisse') && can('encaisser') ? h('a', { class: 'btn primary', href: '/' + me.code + '/caisse' }, ico('caisse', 18), 'Ouvrir la caisse') : null,
      can('evenements') && st !== 'fini' ? h('button', { class: 'btn ghost', onclick: () => eventModal(ctx, members, items, e, staff, stockRes) }, 'Modifier') : null,
      can('evenements') && !e.closed_at ? h('button', { class: 'btn ghost', onclick: ev => run(ev.currentTarget, async () => { await rpc('close_event', { p_event: e.id }); ctx.reload(); return { message: 'Événement clôturé. Le bilan est figé.' }; }) }, 'Clôturer') : null,
      h('a', { class: 'btn ghost', href: '/' + me.code + '/evenements' }, 'Tous les événements')],
    h('section', { class: 'card evhero ' + st }, cd, h('div', { class: 'ev-goal' }, h('div', { class: 'g-top' }, h('span', {}, e.kind === 'dons' ? 'Dons collectés' : 'Ventes de l’événement'), h('b', {}, pct + ' %')),
      h('span', { class: 'meter big' }, h('i', { style: 'width:' + pct + '%' })), h('div', { class: 'g-top muted small' }, h('span', {}, money(b.income) + ' sur ' + money(e.goal)), h('span', {}, e.note || '')))),
    h('section', { class: 'card' }, strip([['Objectif', money(e.goal)], [e.kind === 'dons' ? 'Collecté' : 'Réalisé', money(b.income)], ['Dépenses', money(b.spent)],
      ['Résultat', money(Number(b.income) - Number(b.spent)), Number(b.income) - Number(b.spent) < 0 ? 'neg' : null], ['Budget restant', money(Number(e.budget) - Number(b.spent)), Number(e.budget) - Number(b.spent) < 0 ? 'neg' : null]])),
    h('div', { class: 'grid cols2' },
      h('section', { class: 'card' }, h('h2', {}, e.kind === 'dons' ? 'Dons' : 'Ventes', h('span', { class: 'muted small' }, 'par heure · ' + (e.kind === 'dons' ? b.donors + ' don(s)' : b.tickets + ' ticket(s)'))),
        series.length ? dailyBars(series, money, 'var(--c2)') : h('div', { class: 'empty' }, st === 'avenir' ? 'Les ' + (e.kind === 'dons' ? 'dons' : 'ventes') + ' apparaîtront ici dès le début.' : 'Rien pour l’instant. Les ' + (e.kind === 'dons' ? 'dons enregistrés' : 'tickets encaissés') + ' pendant l’événement s’y ajoutent tout seuls.')),
      h('section', { class: 'card' }, h('h2', {}, 'Tâches', h('span', { class: 'muted small' }, doneN + ' sur ' + tasks.length)),
        h('ul', { class: 'tasks' }, tasks.map(t => h('li', { class: t.done ? 'done' : null }, h('label', { class: 'check' }, h('input', { type: 'checkbox', checked: t.done, onchange: ev => rpc('event_toggle_task', { p_task: t.id, p_done: ev.target.checked }).then(() => ctx.reload()).catch(ctx.fail) }), h('span', {}, t.label)), t.done_by ? h('small', {}, t.done_by) : null))),
        h('div', { class: 'adder' }, taskIn, h('button', { class: 'btn small', onclick: addTask }, 'Ajouter')))),
    h('div', { class: 'grid cols2' },
      h('section', { class: 'card' }, h('h2', {}, 'Équipe', h('span', { class: 'muted small' }, staff.length + ' personne(s)')), staff.length ? h('ul', { class: 'people' }, staff.map(s => { const m = memberOf(s.member_id), on = shifts.some(x => x.member_id === s.member_id);
        return h('li', {}, h('span', { class: 'ava sm' }, initials(m.name)), h('span', {}, h('b', {}, m.name)), me.modules.includes('service') ? (on ? liveDot('En service') : h('span', { class: 'muted small' }, 'Hors service')) : h('span', {})); })) : h('div', { class: 'empty' }, 'Personne n’est affecté.')),
      h('section', { class: 'card' }, h('h2', {}, 'Stock réservé'), table([['Article', r => itemOf(r.item_id).name], ['Réservé', r => qtyOf(itemOf(r.item_id), r.qty), 'num'], ['En stock', r => qtyOf(itemOf(r.item_id), itemOf(r.item_id).qty), 'num'],
        ['', r => Number(itemOf(r.item_id).qty) >= Number(r.qty) ? pill('Suffisant', 'ok') : pill('Il manque ' + qtyOf(itemOf(r.item_id), Number(r.qty) - Number(itemOf(r.item_id).qty)), 'bad')]], stockRes, null, 'Rien de réservé.'))),
    b.spends.length ? h('section', { class: 'card' }, h('h2', {}, 'Dépenses'), table([['Quand', x => date(ms(x.at)), 'num', x => x.at], ['Libellé', x => x.label], ['Par', x => x.by], ['Montant', x => h('b', { class: 'amt out' }, '−' + money2(x.amount)), 'num', x => Number(x.amount)]], b.spends)) : null);
}

// ---------------------------------------------------------------- fiche employé (module Employés)
export async function employe(ctx, id) {
  const { me, can } = ctx;
  const has = m => me.modules.includes(m);
  const [list, grades, warns, notes, drives, shifts] = await Promise.all([rows('members', q => q.eq('org_id', me.id)), rows('grades', q => q.eq('org_id', me.id).order('rank', { ascending: false })),
    rows('warnings', q => q.eq('member_id', id).order('at', { ascending: false })), can('equipe') ? rows('staff_notes', q => q.eq('member_id', id).order('at', { ascending: false })) : [],
    has('vehicules') ? rows('vehicle_logs', q => q.eq('member_id', id).order('at', { ascending: false }).limit(20)) : [],
    has('service') ? rows('shifts', q => q.eq('member_id', id).order('started_at', { ascending: false }).limit(20)) : []]);
  const m = list.find(x => x.id === id), mine = list.find(x => x.id === me.member_id);
  if (!m) return ctx.go('/' + me.code + '/equipe', true);
  const gOf = gid => grades.find(g => g.id === gid) || { name: '?', rank: 0 };
  const myRank = mine ? gOf(mine.grade_id).rank : 0;
  const self = m.id === me.member_id, gone = !m.active, manage = can('equipe') && !self && gOf(m.grade_id).rank <= myRank;
  const onDuty = shifts.find(s => !s.ended_at);
  const editProfile = () => {
    const phone = input({ maxlength: 20, value: m.phone || '', placeholder: '555-0100' }), contract = input({ maxlength: 20, value: m.contract || 'CDI' });
    const lic = input({ maxlength: 40, value: (m.licences || []).join(', '), placeholder: 'B, C, poids lourd…' }), hired = h('input', { type: 'date', value: m.hired_at || '' });
    const ok = h('button', { class: 'btn primary' }, 'Enregistrer');
    const md = modal('Fiche de ' + m.name, h('div', { class: 'row' }, field('Téléphone', phone, 'grow'), field('Contrat', contract, 'grow')), h('div', { class: 'row' }, field('Permis (séparés par des virgules)', lic, 'grow'), field('Embauché le', hired)), foot({ close: () => md.close() }, ok));
    ok.addEventListener('click', () => run(ok, async () => {
      await rpc('save_profile', { p_member: m.id, p_phone: phone.value, p_contract: contract.value, p_licences: lic.value.split(',').map(x => x.trim()).filter(Boolean), p_hired_at: hired.value || null });
      md.close(); ctx.reload(); return { message: 'Fiche enregistrée.' };
    }));
  };
  const textAction = (title, label, placeholder, btn, fn, cls) => {
    const t = h('textarea', { maxlength: 200, placeholder }), ok = h('button', { class: 'btn ' + (cls || 'primary') }, btn);
    const md = modal(title, field(label, t), foot({ close: () => md.close() }, ok));
    ok.addEventListener('click', () => run(ok, async () => { const r = await fn(t.value.trim()); md.close(); ctx.reload(); return r; }));
  };
  const info = (k, v) => [h('dt', {}, k), h('dd', {}, v)];
  ctx.show('equipe', m.name, [pill(gOf(m.grade_id).name, 'info'), ' ', gone ? pill('Parti le ' + date(ms(m.left_at || Date.now())), 'bad') : onDuty ? liveDot('En service depuis ' + clock(ms(onDuty.started_at))) : has('service') ? pill('Hors service') : null],
    [manage && !gone ? [h('button', { class: 'btn', onclick: editProfile }, 'Modifier la fiche'),
      h('button', { class: 'btn', onclick: () => textAction('Avertissement · ' + m.name, 'Motif', 'Ce qui s’est passé', 'Donner l’avertissement', async v => { await rpc('add_warning', { p_member: m.id, p_reason: v }); return { message: 'Avertissement enregistré.' }; }, 'danger') }, ico('alerte', 18), 'Avertir'),
      h('button', { class: 'btn danger', onclick: () => textAction('Départ de ' + m.name, 'Motif', 'Démission, licenciement…', 'Confirmer le départ', async v => { await rpc('dismiss_member', { p_member: m.id, p_reason: v }); return { message: m.name + ' ne fait plus partie de l’équipe. Compte désactivé.' }; }, 'danger') }, 'Licencier')] : null,
      h('a', { class: 'btn ghost', href: '/' + me.code + '/equipe' }, 'Toute l’équipe')],
    gone ? h('div', { class: 'banner bad' }, ico('alerte', 20), h('div', {}, h('b', {}, 'Ancien membre'), h('span', {}, m.left_reason || ''))) : null,
    h('div', { class: 'grid cols2' },
      h('section', { class: 'card' }, h('h2', {}, 'Fiche'), h('dl', { class: 'lines-dl rules' }, info('Identifiant', m.login), info('Téléphone', m.phone || '—'), info('Contrat', m.contract || '—'),
        info('Permis', m.licences && m.licences.length ? m.licences.join(', ') : 'Aucun'), info('Embauché le', m.hired_at ? new Date(m.hired_at).toLocaleDateString('fr-FR') : '—'),
        has('service') ? info('Taux horaire', Number(m.hourly_rate) ? money2(m.hourly_rate) + ' / h' : '—') : null, info('Vu', m.last_seen ? ago(ms(m.last_seen)) : 'jamais'))),
      h('section', { class: 'card' }, h('h2', {}, 'Avertissements', h('span', { class: 'muted small' }, warns.length + ' au total')),
        warns.length ? h('ul', { class: 'anos' }, warns.map(w => h('li', { class: 'sev2' }, pill(new Date(w.at).toLocaleDateString('fr-FR'), 'warn'), h('div', {}, h('b', {}, w.reason), h('span', {}, 'par ' + w.by_name))))) : h('div', { class: 'calm' }, h('span', { class: 'ic' }, ico('coche')), 'Aucun avertissement.'))),
    h('div', { class: 'grid cols2' },
      can('equipe') ? h('section', { class: 'card' }, h('h2', {}, 'Notes de la direction', manage ? h('span', { class: 'right' }, h('button', { class: 'btn small', onclick: () => textAction('Note sur ' + m.name, 'Note', 'Visible par la direction seulement', 'Ajouter la note', async v => { await rpc('add_staff_note', { p_member: m.id, p_text: v }); return { message: 'Note ajoutée.' }; }) }, ico('ajout', 16), 'Note')) : null),
        notes.length ? notes.map(n => h('div', { class: 'noteitem' }, h('div', {}, n.text), h('div', { class: 'muted small' }, n.by_name + ' · ' + date(ms(n.at))))) : h('div', { class: 'empty' }, 'Aucune note.')) : null,
      has('vehicules') ? h('section', { class: 'card' }, h('h2', {}, 'Véhicules utilisés'), table([['Quand', l => date(ms(l.at)), 'num', l => l.at], ['Action', l => l.action], ['Remarque', l => l.note || '']], drives, null, 'Aucun véhicule utilisé.'))
        : has('service') ? h('section', { class: 'card' }, h('h2', {}, 'Derniers services'), table([['Début', s => date(ms(s.started_at)), 'num', s => s.started_at], ['Fin', s => s.ended_at ? clock(ms(s.ended_at)) : 'en cours'],
          ['Durée', s => Math.round(((s.ended_at ? ms(s.ended_at) : Date.now()) - ms(s.started_at)) / HOUR * 10) / 10 + ' h', 'num'], ['', s => s.paid_at ? h('span', { class: 'muted' }, 'Payé') : s.ended_at ? pill('À payer', 'warn') : '']], shifts, null, 'Aucun service.')) : null));
}

// ---------------------------------------------------------------- tableau de bord : ce que les modules ont à dire aujourd'hui
export async function moduleWidgets(ctx) {
  const { me, can } = ctx;
  const has = m => me.modules.includes(m);
  const [items, shifts, events, invoices, sales, members] = await Promise.all([
    (has('stock') || has('caisse')) && can('stock') ? rows('items', q => q.eq('org_id', me.id).eq('archived', false)) : [],
    has('service') ? rows('shifts', q => q.eq('org_id', me.id).is('ended_at', null)) : [],
    has('evenements') ? rows('events', q => q.eq('org_id', me.id).gte('ends_at', iso(Date.now())).order('starts_at').limit(3)) : [],
    has('factures') && can('compta') ? rows('invoices', q => q.eq('org_id', me.id).eq('status', 'attente')) : [],
    has('caisse') && can('encaisser') ? rows('sales', q => q.eq('org_id', me.id).gte('at', iso(dayStart()))) : [],
    has('service') ? rows('members', q => q.eq('org_id', me.id).eq('active', true)) : []]);
  const low = items.filter(i => Number(i.qty) < Number(i.threshold));
  const figures = [has('caisse') && can('encaisser') ? ['Caisse · aujourd’hui', money(sales.reduce((a, s) => a + Number(s.total), 0)) + ' · ' + sales.length + ' ticket(s)'] : null,
    has('service') ? ['En service', String(shifts.length)] : null, low.length || items.length ? ['Articles sous le seuil', String(low.length), low.length ? 'neg' : null] : null,
    has('factures') && can('compta') ? ['Factures à encaisser', money(invoices.reduce((a, x) => a + Number(x.total), 0))] : null].filter(Boolean);
  const nameOf = id => (members.find(m => m.id === id) || { name: '?' }).name;
  const out = [];
  if (figures.length) out.push(h('section', { class: 'card' }, h('h2', {}, 'Aujourd’hui'), strip(figures)));
  const side = [];
  if (low.length) side.push(h('section', { class: 'card' }, h('h2', {}, 'À réapprovisionner', h('span', { class: 'right' }, h('a', { href: '/' + me.code + '/stock' }, 'Stock'))),
    h('ul', { class: 'people' }, low.slice(0, 6).map(i => h('li', {}, h('span', { class: 'ev-ic' }, ico('alerte', 16)), h('span', {}, h('b', {}, i.name), h('small', {}, qtyOf(i, i.qty) + ' · seuil ' + num(i.threshold))))))));
  if (shifts.length) side.push(h('section', { class: 'card' }, h('h2', {}, 'En service'), h('ul', { class: 'people' }, shifts.map(s => h('li', {}, h('span', { class: 'ava sm' }, initials(nameOf(s.member_id))), h('span', {}, h('b', {}, nameOf(s.member_id)), h('small', {}, 'depuis ' + clock(ms(s.started_at)))))))));
  if (events.length) side.push(h('section', { class: 'card' }, h('h2', {}, 'Événements', h('span', { class: 'right' }, h('a', { href: '/' + me.code + '/evenements' }, 'Tous'))),
    h('ul', { class: 'people' }, events.map(e => { const st = evState(e); return h('li', {}, h('span', { class: 'ev-ic' }, ico('calendrier', 16)), h('span', {}, h('a', { href: '/' + me.code + '/evenements/' + e.id }, h('b', {}, e.name)), h('small', {}, date(ms(e.starts_at)))), pill(EVS[st][0], EVS[st][1])); }))));
  return { top: out, side };
}
