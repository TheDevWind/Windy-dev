import { h, ico, modal, toast, run, FLY_PATH, BRAND } from './ui.js';
import { moduleOf, quoteTotal } from './catalog.js';
const DAY = 864e5;
// ================================================================== documents : devis et factures, en PNG ou en PDF
/*
 * Le document est dessiné une seule fois sur un canvas A4 (1240 × 1754, soit 150 points par pouce) ;
 * le PNG est ce canvas, le PDF l'enveloppe telle quelle (image JPEG dans une page A4). Pas de bibliothèque : le PDF est écrit ici.
 * Le papier est toujours blanc, quel que soit le thème de l'écran : c'est un document à envoyer ou à imprimer.
 */
// couleurs de Windy Project : violet du papillon, encre ardoise, lavande, crème et sauge
const PAPER = { W: 1240, H: 1754, M: 100, ink: '#2b2d33', muted: '#5f5b66', line: '#e4ddd1', accent: '#6458b0', ok: '#4d6a2c', bad: '#ad241d' };
const SERIF = '"Cormorant Garamond", Georgia, serif';
const plainMoney = n => (n < 0 ? '-' : '') + Math.abs(Math.round(n)).toLocaleString('fr-FR').replace(/\s/g, ' ') + ' $';
const longDate = ms => new Date(ms).toLocaleDateString('fr-FR', { day: '2-digit', month: '2-digit', year: 'numeric' });

async function fontsReady() {
  try { await Promise.all(['400', '600', '700'].map(w => document.fonts.load(w + ' 24px Inter')).concat(['500', '600'].map(w => document.fonts.load(w + ' 40px "Cormorant Garamond"')))); } catch (e) { /* police système en secours */ }
}
function wrapText(x, text, maxW) {
  const words = String(text).split(/\s+/), lines = [];
  let cur = '';
  words.forEach(w => { const t = cur ? cur + ' ' + w : w; if (x.measureText(t).width > maxW && cur) { lines.push(cur); cur = w; } else cur = t; });
  if (cur) lines.push(cur);
  return lines;
}
/**
 * d : { kind: 'DEVIS' | 'FACTURE', num, date, valid, status, from: { title, lines[] }, to: { title, lines[] },
 *       cols: [titre, …], rows: [{ name, sub, cells: […] }], totals: [[libellé, valeur, gras]], notes: [], sign: bool }
 */
// level : 0 = descriptions complètes ; 1 = une ligne par description ; 2 = sans description ; 3 = lignes resserrées
async function renderDoc(d, level = 0) {
  await fontsReady();
  const { W, H, M } = PAPER, c = document.createElement('canvas');
  c.width = W; c.height = H;
  const x = c.getContext('2d');
  const font = (w, s) => { x.font = w + ' ' + s + 'px Inter, "Helvetica Neue", Arial, sans-serif'; };
  const text = (t, px, py, align, color) => { x.fillStyle = color || PAPER.ink; x.textAlign = align || 'left'; x.fillText(t, px, py); };
  const rule = (py, w, color, x0, x1) => { x.fillStyle = color || PAPER.ink; x.fillRect(x0 === undefined ? M : x0, py, (x1 === undefined ? W - M : x1) - (x0 === undefined ? M : x0), w); };
  x.fillStyle = '#ffffff'; x.fillRect(0, 0, W, H);
  x.textBaseline = 'alphabetic';
  // en-tête
  let bx;
  if (d.mark) {   // document d'une entreprise : son initiale dans un rond violet
    x.fillStyle = PAPER.accent; x.beginPath(); x.arc(M + 36, M + 36, 36, 0, 2 * Math.PI); x.fill();
    x.font = '600 46px ' + SERIF; text(d.mark, M + 36, M + 52, 'center', '#ffffff');
    bx = M + 96;
  } else {        // document de l'éditeur : le papillon de Windy Project
    const k = 132 / 3780;
    x.save(); x.translate(M, M + 6); x.scale(k, k); x.fillStyle = PAPER.accent; x.fill(new Path2D(FLY_PATH), 'evenodd'); x.restore();
    bx = M + 156;
  }
  x.font = '600 44px ' + SERIF; text(d.brand || BRAND, bx, M + 36);
  font('400', 19); text(d.brandSub || 'Gestion des entreprises RP · Windy Project', bx, M + 66, 'left', PAPER.muted);
  font('700', 64); text(d.kind, W - M, M + 50, 'right');
  font('600', 21); text('N° ' + d.num, W - M, M + 88, 'right');
  font('400', 19); text('Émis le ' + longDate(d.date), W - M, M + 116, 'right', PAPER.muted);
  if (d.valid) text((d.kind === 'DEVIS' ? 'Valable jusqu’au ' : 'Échéance le ') + longDate(d.valid), W - M, M + 142, 'right', PAPER.muted);
  let y = M + 190;
  rule(y, 3, PAPER.accent);
  // parties
  y += 56;
  const col2 = W / 2 + 20;
  [[d.from, M], [d.to, col2]].forEach(([p, px]) => {
    font('600', 15); x.save(); x.letterSpacing = '2px'; text(p.title.toUpperCase(), px, y, 'left', PAPER.muted); x.restore();
    p.lines.forEach((l, i) => { font(i === 0 ? '700' : '400', i === 0 ? 26 : 20); text(l, px, y + 40 + i * 32, 'left', i === 0 ? PAPER.ink : PAPER.muted); });
  });
  y += 40 + Math.max(d.from.lines.length, d.to.lines.length) * 32 + 48;
  // tableau
  const widths = d.colW || [0.52, 0.16, 0.12, 0.2], tw = W - 2 * M;
  const cx = [M]; widths.forEach((w, i) => cx.push(cx[i] + w * tw));
  rule(y, 2);
  y += 34;
  font('600', 15);
  d.cols.forEach((t, i) => { x.save(); x.letterSpacing = '1.5px'; text(t.toUpperCase(), i === 0 ? cx[0] : cx[i + 1], y, i === 0 ? 'left' : 'right', PAPER.muted); x.restore(); });
  y += 22;
  rule(y, 1, PAPER.line);
  d.rows.forEach(r => {
    font('600', 22);
    let subLines = r.sub && level < 2 ? (font('400', 17), wrapText(x, r.sub, widths[0] * tw - 24)) : [];
    if (level === 1 && subLines.length > 1) subLines = [subLines[0].replace(/[\s,.;:]*$/, '') + '…'];
    const tight = level >= 3, base = tight ? 32 : 40;   // ligne resserrée : texte plus haut, filet sous les jambages
    const rowH = tight ? 46 : 30 + subLines.length * 24 + 26;
    font('600', 22); text(r.name, cx[0], y + base);
    font('400', 17); subLines.forEach((l, i) => text(l, cx[0], y + 68 + i * 24, 'left', PAPER.muted));
    font('400', 21); r.cells.forEach((cv, i) => { if (i === r.cells.length - 1) font('600', 21); text(cv, cx[i + 2], y + base, 'right'); });
    y += rowH;
    rule(y, 1, PAPER.line);
  });
  // totaux
  y += 28;
  const tx0 = W - M - 440;
  d.totals.forEach(([l, v, strong], i) => {
    if (strong) { y += 10; rule(y - 32, 3, PAPER.accent, tx0, W - M); font('700', 22); text(l, tx0, y + 12); font('700', 44); text(v, W - M, y + 18, 'right'); y += 64; }
    else { font('400', 20); text(l, tx0, y, 'left', PAPER.muted); text(v, W - M, y, 'right'); y += 36; }
  });
  // tampon de statut
  if (d.stamp) {
    x.save(); x.translate(M + 190, y - 60); x.rotate(-0.12);
    x.strokeStyle = d.stamp.color; x.lineWidth = 5; x.strokeRect(-150, -46, 300, 84);
    font('700', 40); x.fillStyle = d.stamp.color; x.textAlign = 'center'; x.fillText(d.stamp.text, 0, 12); x.restore();
  }
  // conditions
  y += 24;
  if (d.notes && d.notes.length) {
    font('600', 15); x.save(); x.letterSpacing = '2px'; text('CONDITIONS', M, y, 'left', PAPER.muted); x.restore();
    y += 32;
    font('400', 18);
    d.notes.forEach(n => wrapText(x, '— ' + n, W - 2 * M).forEach(l => { text(l, M, y, 'left', '#333333'); y += 28; }));
  }
  // trop long pour la page : on recommence en plus compact plutôt que de chevaucher le pied de page
  if ((d.sign ? Math.max(y + 40, H - M - 250) + (level >= 2 ? 104 : 170) : y) > H - M + 4 && level < 3) return renderDoc(d, level + 1);
  // signatures
  if (d.sign) {
    const sy = Math.max(y + 40, H - M - 250), bw = (W - 2 * M - 40) / 2, bh = Math.max(104, Math.min(170, H - M + 4 - sy));
    [[d.to.lines[0], M], [d.from.lines[0], M + bw + 40]].forEach(([who, px]) => {
      x.strokeStyle = PAPER.line; x.lineWidth = 2; x.strokeRect(px, sy, bw, bh);
      font('600', 15); x.save(); x.letterSpacing = '1.5px'; text('BON POUR ACCORD', px + 20, sy + 36, 'left', PAPER.muted); x.restore();
      font('600', 20); text(who, px + 20, sy + 70);
      font('400', 16); text('Date et signature', px + 20, sy + bh - 20, 'left', PAPER.muted);
    });
  }
  // pied de page
  rule(H - M + 20, 1, PAPER.line);
  font('400', 15);
  text(d.footer || 'Montants en dollars du serveur (argent RP). Document généré par ' + BRAND + ' le ' + longDate(Date.now()) + '.', M, H - M + 54, 'left', PAPER.muted);
  text(d.num, W - M, H - M + 54, 'right', PAPER.muted);
  const band = x.createLinearGradient(0, 0, W, 0);   // le dégradé de la bannière : lavande, crème, sauge
  band.addColorStop(0, '#b79ad8'); band.addColorStop(0.5, '#f1e4e6'); band.addColorStop(1, '#a9bc86');
  x.fillStyle = band; x.fillRect(0, H - 14, W, 14);
  return c;
}

const toBlob = (c, type, q) => new Promise(r => c.toBlob(r, type, q));
/** Un vrai fichier PDF d'une page A4 qui contient l'image JPEG du document. */
async function pdfFromCanvas(c) {
  const jpg = new Uint8Array(await (await toBlob(c, 'image/jpeg', 0.92)).arrayBuffer());
  const enc = new TextEncoder(), chunks = [], offs = [];
  let len = 0;
  const add = b => { const u = typeof b === 'string' ? enc.encode(b) : b; chunks.push(u); len += u.length; };
  const obj = (n, body) => { offs[n] = len; add(n + ' 0 obj\n'); body(); add('\nendobj\n'); };
  const pw = 595.28, ph = 841.89, content = 'q ' + pw + ' 0 0 ' + ph + ' 0 0 cm /Im0 Do Q';
  add('%PDF-1.4\n'); add(new Uint8Array([0x25, 0xE2, 0xE3, 0xCF, 0xD3, 0x0A]));
  obj(1, () => add('<< /Type /Catalog /Pages 2 0 R >>'));
  obj(2, () => add('<< /Type /Pages /Kids [3 0 R] /Count 1 >>'));
  obj(3, () => add('<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ' + pw + ' ' + ph + '] /Resources << /XObject << /Im0 4 0 R >> >> /Contents 5 0 R >>'));
  obj(4, () => { add('<< /Type /XObject /Subtype /Image /Width ' + c.width + ' /Height ' + c.height + ' /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ' + jpg.length + ' >>\nstream\n'); add(jpg); add('\nendstream'); });
  obj(5, () => add('<< /Length ' + content.length + ' >>\nstream\n' + content + '\nendstream'));
  const xref = len;
  add('xref\n0 6\n0000000000 65535 f \n' + [1, 2, 3, 4, 5].map(n => String(offs[n]).padStart(10, '0') + ' 00000 n \n').join(''));
  add('trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n' + xref + '\n%%EOF\n');
  return new Blob(chunks, { type: 'application/pdf' });
}
function saveBlob(blob, name) {
  const a = h('a', { href: URL.createObjectURL(blob), download: name });
  document.body.append(a);
  a.click();
  setTimeout(() => { URL.revokeObjectURL(a.href); a.remove(); }, 2000);
}
/** Fenêtre d'export : aperçu exact du document, PNG, PDF, copie de l'image. */
async function exportModal(d, fileBase) {
  const img = h('img', { class: 'docimg', alt: d.kind.toLowerCase() + ' ' + d.num });
  const busy = h('div', { class: 'docwait' }, 'Préparation du document…');
  const png = h('button', { class: 'btn primary' }, ico('telecharger', 18), 'PNG'), pdf = h('button', { class: 'btn primary' }, ico('telecharger', 18), 'PDF');
  const copy = h('button', { class: 'btn' }, ico('copier', 18), 'Copier l’image');
  const m = modal(d.kind === 'DEVIS' ? 'Devis ' + d.num : 'Facture ' + d.num, h('div', { class: 'docframe' }, busy, img),
    h('p', { class: 'note' }, 'Si rien ne se télécharge (aperçu sur claude.ai, qui bloque les téléchargements), fais un clic droit ou un appui long sur le document : « Enregistrer l’image ». Sur ton site, les boutons téléchargent directement.'),
    h('div', { class: 'foot' }, h('button', { class: 'btn', onclick: () => m.close() }, 'Fermer'), copy, png, pdf));
  m.box.classList.add('wide');
  const c = await renderDoc(d);
  img.src = c.toDataURL('image/png');
  busy.remove();
  png.addEventListener('click', async () => { saveBlob(await toBlob(c, 'image/png'), fileBase + '.png'); toast(fileBase + '.png prêt.'); });
  pdf.addEventListener('click', () => run(pdf, async () => { saveBlob(await pdfFromCanvas(c), fileBase + '.pdf'); return { message: fileBase + '.pdf prêt.' }; }));
  copy.addEventListener('click', async () => {
    try { await navigator.clipboard.write([new ClipboardItem({ 'image/png': toBlob(c, 'image/png') })]); toast('Image copiée : colle-la dans Discord ou un message.'); }
    catch (e) { toast('Ce navigateur refuse la copie d’image. Utilise le clic droit sur l’aperçu.', true); }
  });
  return c;
}

// ---------------------------------------------------------------- documents de Windy Desk
const EDITOR = { title: 'Éditeur', lines: ['Windy Dev', BRAND + ' · licences', 'Paiement en argent RP', 'Contact : en ville ou sur Discord'] };
function quoteDoc(q) {
  const t = quoteTotal(q);
  return { kind: 'DEVIS', num: q.num, date: new Date(q.created_at || q.created || Date.now()).getTime(), valid: new Date(q.created_at || q.created || Date.now()).getTime() + 7 * DAY,
    from: EDITOR, to: { title: 'Client', lines: [q.client || 'Client', q.contact ? 'À l’attention de ' + q.contact : '', q.type || ''].filter(Boolean) },
    cols: ['Module', 'Prix / semaine', 'Semaines', 'Montant'],
    rows: q.modules.map(id => { const m = moduleOf(id); return { name: m.name, sub: m.text, cells: [plainMoney(m.price), String(q.weeks), plainMoney(m.price * q.weeks)] }; }),
    totals: [['Sous-total', plainMoney(t.gross)], q.discount ? ['Remise de ' + q.discount + ' %', '-' + plainMoney(t.cut)] : null, ['Total à payer', plainMoney(t.total), true]].filter(Boolean),
    notes: ['Licence valable ' + q.weeks + ' semaine' + (q.weeks > 1 ? 's' : '') + ' à compter de l’activation, renouvelable.', 'Paiement en argent RP à la signature. L’espace de l’entreprise est ouvert le jour même.',
      'Les modules peuvent être ajoutés ou retirés à tout moment ; le prix suit à la semaine suivante.', q.note].filter(Boolean),
    sign: true, stamp: q.status === 'accepte' ? { text: 'ACCEPTÉ', color: PAPER.ok } : q.status === 'refuse' ? { text: 'REFUSÉ', color: PAPER.bad } : null };
}
// facture d'une entreprise (module Factures) : inv = ligne de la table invoices, o = { name, kind, owner }
function invoiceDoc(o, inv) {
  const st = inv.status === 'attente' && new Date(inv.due_at).getTime() < Date.now() ? 'retard' : inv.status;
  const m2 = n => Number(n).toLocaleString('fr-FR', { minimumFractionDigits: Number(n) % 1 ? 2 : 0, maximumFractionDigits: 2 }).replace(/\s/g, ' ') + ' $';
  return { kind: 'FACTURE', num: inv.num, date: new Date(inv.created_at).getTime(), valid: new Date(inv.due_at).getTime(), brand: o.name, mark: o.name[0].toUpperCase(), brandSub: (o.kind || 'Entreprise') + ' · géré avec ' + BRAND,
    from: { title: 'Émetteur', lines: [o.name, o.owner, o.kind].filter(Boolean) }, to: { title: 'Client', lines: [inv.client] },
    cols: ['Article', 'Quantité', 'Prix unitaire', 'Montant'], colW: [0.46, 0.16, 0.18, 0.2],
    rows: inv.lines.map(l => ({ name: l.label, sub: null, cells: [Number(l.qty).toLocaleString('fr-FR'), m2(l.price), m2(Number(l.qty) * Number(l.price))] })),
    totals: [['Total à payer', m2(inv.total), true]],
    notes: ['Paiement à réception, en argent RP.', 'Une facture payée est enregistrée dans la comptabilité des deux entreprises.'],
    sign: false, stamp: st === 'payee' ? { text: 'PAYÉE', color: PAPER.ok } : st === 'retard' ? { text: 'EN RETARD', color: PAPER.bad } : st === 'annulee' ? { text: 'ANNULÉE', color: PAPER.bad } : null };
}


export { renderDoc, pdfFromCanvas, saveBlob, exportModal, quoteDoc, invoiceDoc };
