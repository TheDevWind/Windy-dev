// ================================================================== Windy Desk · MDT, étape 4 : gestion du service
// Académie (candidatures, parcours, bibliothèque, examens, évaluations, entraînements, promotions), Affaires internes,
// logistique (parc, armurerie, équipement), statistiques, carte, réglages du service, compléments de la fiche agent
// et formulaires publics (candidature, plainte). Ne dépend que de mdt-kit.js.
import { S, h, ico, modal, toast, run, table, pill, strip, date, ago, dailyBars, gauge, fchips } from './ui.js';
import { rpc, rows, signedUrls, upload, publicUrl } from './sb.js';
import { saveBlob, exportModal } from './doc.js';
import { createMap, tilesMeta, imageVersCarte } from './map-view.js';
import { SIDE, carteVersRepere } from './map-geo.js';
import { DAY, ms, iso, day, field, input, area, select, foot, fullName, toLocal, tabs, search, match, load, send, face, gradeOf, OPEN, IMAGE, stamp, extOf, feed, seal } from './mdt-kit.js';

const base = ctx => '/' + ctx.me.code;
const when = t => date(ms(t));
const nameOf = (members, id) => (members.find(m => m.id === id) || {}).name || '—';
const memberOpts = (members, none, skip) => (none ? [['', none]] : []).concat(members.filter(m => m.active && m.id !== skip).map(m => [m.id, (m.callsign ? m.callsign + ' · ' : '') + m.name]));
const isTop = (ctx, grades) => grades.length > 0 && ctx.me.grade === grades[0].name;
const suspended = m => m.suspended_until && ms(m.suspended_until) > Date.now();
const btn = (label, onclick, cls, icon) => h('button', { class: 'btn' + (cls ? ' ' + cls : ''), type: 'button', onclick }, icon ? ico(icon, 18) : null, label);
const small = (label, onclick, cls) => h('button', { class: 'btn small' + (cls ? ' ' + cls : ''), type: 'button', onclick: ev => { ev.stopPropagation(); onclick(ev); } }, label);
const call = (ctx, name, args, message) => async () => { await rpc(name, args); ctx.reload(); return { message }; };
const card = (title, ...content) => h('section', { class: 'card' }, title ? h('h2', {}, ...[].concat(title)) : null, ...content);
const kv = (k, v) => v === null || v === undefined || v === '' ? null : [h('dt', {}, k), h('dd', {}, v)];

const APP = { recue: ['Reçue', 'info'], entretien: ['Entretien prévu', 'warn'], acceptee: ['Acceptée', 'ok'], refusee: ['Refusée', null] };
const DOCCAT = { reglement: 'Règlement', procedure: 'Procédures', radio: 'Codes radio et fréquences', guide: 'Guides' };
const CRIT = ['Conduite', 'Procédures', 'Communication', 'Tir', 'Comportement', 'Code pénal'];
const PROMO = { proposee: ['En attente', 'warn'], acceptee: ['Acceptée', 'ok'], refusee: ['Refusée', null] };
const IA_ST = { ouvert: ['Ouvert', 'info'], enquete: ['Enquête en cours', 'warn'], classe: ['Classé', null], sanction: ['Clos par une sanction', 'bad'] };
const ORIGIN = { auto: 'Ouverture automatique', interne: 'Ouvert par les Affaires internes', plainte: 'Plainte' };
const COMPL = { recue: ['À traiter', 'warn'], enquete: ['Enquête ouverte', 'info'], classee: ['Classée', null] };
const SANCTION = { avertissement: ['Avertissement', 'warn'], blame: ['Blâme', 'warn'], suspension: ['Suspension', 'bad'], retrogradation: ['Rétrogradation', 'bad'], renvoi: ['Renvoi', 'bad'] };
const IA_LOG = { note: ['Note', null, 'journal'], preuve: ['Preuve', 'info', 'trombone'], audition: ['Audition', 'warn', 'joueurs'], decision: ['Décision', 'bad', 'marteau'] };
const WCAT = { poing: 'Arme de poing', epaule: 'Arme d’épaule', lourde: 'Arme lourde (SOG)', non_letal: 'Non létale' };
const WST = { armurerie: ['À l’armurerie', 'ok'], attribuee: ['Attribuée', 'info'], maintenance: ['Maintenance', 'warn'], perdue: ['Perdue', 'bad'], detruite: ['Détruite', null] };
const EQK = { munitions: 'Munitions', gilet: 'Gilets', bouclier: 'Boucliers', radio: 'Radios', autre: 'Autre' };
const VK = { marque: 'Sérigraphié', banalise: 'Banalisé', blinde: 'Blindé (SOG)', moto: 'Moto', utilitaire: 'Utilitaire', aerien: 'Aérien' };
const VST = { ok: ['En état', 'ok'], abime: ['Abîmé', 'warn'], reparation: ['En réparation', 'warn'], hors_service: ['Hors service', 'bad'] };
const MARK = { position: 'Dernières positions', interpellation: 'Interpellations', territoire: 'Territoires des gangs', lieu: 'Lieux', planque: 'Planques WITSEC' };
const TPL = { candidature: 'Formulaire de candidature', promotion: 'Proposition de promotion', r_arrestation: 'Rapport d’arrestation', r_sog: 'Rapport SOG', r_transfert: 'Rapport de transfert',
  r_incident: 'Rapport d’incident', r_force: 'Usage de la force', r_audience: 'Rapport d’audience', r_libre: 'Rapport libre' };

// ================================================================== académie
const progressOf = (m, live, progress) => live.length ? Math.round(100 * live.filter(md => progress.some(p => p.member_id === m.id && p.module_id === md.id)).length / live.length) : 0;
const stars = n => h('span', { class: 'stars', 'aria-label': n + ' sur 5' }, '★'.repeat(n) + '☆'.repeat(5 - n));

function evalModal(ctx, m) {
  const sel = CRIT.map(c => [c, select([['', '—'], ['1', '1'], ['2', '2'], ['3', '3'], ['4', '4'], ['5', '5']], '', { 'aria-label': c })]);
  const overall = select([['5', '5 · excellent'], ['4', '4 · bien'], ['3', '3 · correct'], ['2', '2 · à revoir'], ['1', '1 · insuffisant']], '3', { 'aria-label': 'Note globale' });
  const comment = area({ maxlength: 1000, rows: 4, placeholder: 'Ce qui va, ce qui reste à travailler', 'aria-label': 'Commentaire' }), ok = btn('Enregistrer l’évaluation', null, 'primary');
  const md = modal('Évaluation terrain · ' + m.name, h('div', { class: 'evalgrid' }, sel.map(([c, s]) => field(c, s))), field('Note globale', overall), field('Commentaire', comment), foot({ close: () => md.close() }, ok));
  ok.addEventListener('click', () => run(ok, async () => {
    const scores = Object.fromEntries(sel.filter(([, s]) => s.value).map(([c, s]) => [c, Number(s.value)]));
    await rpc('add_field_eval', { p_member: m.id, p_scores: scores, p_overall: Number(overall.value), p_comment: comment.value.trim() || null });
    md.close(); ctx.reload(); return { message: 'Évaluation enregistrée.' };
  }));
}

function traineeModal(ctx, m, d) {
  const live = d.modules.filter(x => !x.archived), mine = m.id === ctx.me.member_id;
  const fto = select(memberOpts(d.members, 'Aucun formateur', m.id), m.fto_id || '', { 'aria-label': 'Formateur' });
  const evals = d.evals.filter(e => e.member_id === m.id);
  const md = modal('Parcours de ' + m.name, gauge('Progression', progressOf(m, live, d.progress), 'journal'),
    ctx.can('academie') ? h('div', { class: 'row' }, field('Formateur (FTO)', fto, 'grow'), h('button', { class: 'btn', type: 'button', onclick: ev => run(ev.currentTarget, async () => {
      await rpc('set_fto', { p_member: m.id, p_fto: fto.value || null }); md.close(); ctx.reload(); return { message: 'Formateur enregistré.' }; }) }, 'Attribuer'))
      : h('p', { class: 'muted' }, 'Formateur : ' + (m.fto_id ? nameOf(d.members, m.fto_id) : 'aucun')),
    h('h3', { class: 'sub-h' }, 'Modules'),
    h('ul', { class: 'quals' }, live.map(x => {
      const got = d.progress.find(p => p.member_id === m.id && p.module_id === x.id);
      const cb = d.fto && !mine ? h('input', { type: 'checkbox', checked: !!got, 'aria-label': x.name, onchange: async ev => {
        ev.target.disabled = true;
        try { await rpc('validate_module', { p_member: m.id, p_module: x.id, p_on: ev.target.checked, p_note: null }); toast(x.name + (ev.target.checked ? ' validé.' : ' à refaire.')); ctx.reload(); }
        catch (e) { ev.target.checked = !ev.target.checked; ev.target.disabled = false; ctx.fail(e); }
      } }) : h('span', { class: 'q-ic' }, ico(got ? 'coche' : 'moins', 16));
      return h('li', { class: got ? 'got' : null }, cb, h('span', { class: 'art' }, h('b', {}, x.name), h('small', {}, got ? 'Validé le ' + day(got.validated_at) + ' · ' + got.by_name + (got.note ? ' · ' + got.note : '') : x.description || 'À valider')));
    })),
    h('h3', { class: 'sub-h' }, 'Évaluations terrain'),
    evals.length ? h('ul', { class: 'people' }, evals.map(e => h('li', {}, h('span', { class: 'art grow' }, h('b', {}, stars(e.overall), ' ', e.by_name), h('small', {}, when(e.at) + (Object.keys(e.scores).length ? ' · ' + Object.entries(e.scores).map(([k, v]) => k + ' ' + v + '/5').join(', ') : '')),
      e.comment ? h('span', { class: 'prewrap' }, e.comment) : null)))) : h('p', { class: 'muted' }, 'Aucune évaluation visible.'),
    h('div', { class: 'foot' }, d.fto && !mine ? btn('Évaluer sur le terrain', () => { md.close(); evalModal(ctx, m); }, null, 'etoile') : null, btn('Fermer', () => md.close())));
  md.box.classList.add('wide');
}

function modulesModal(ctx, modules) {
  const name = input({ maxlength: 60, placeholder: 'Ex. Négociation', 'aria-label': 'Nouveau module' }), desc = input({ maxlength: 300, placeholder: 'Ce qui est vu (facultatif)', 'aria-label': 'Description' });
  const md = modal('Modules du parcours', h('ul', { class: 'quals' }, modules.map(x => h('li', { class: x.archived ? 'off' : null }, h('span', { class: 'art grow' }, h('b', {}, x.name), h('small', {}, x.archived ? 'Archivé' : x.description || '')),
    small(x.archived ? 'Rétablir' : 'Archiver', ev => run(ev.currentTarget, async () => { await rpc('save_training_module', { p_org: ctx.me.id, p_id: x.id, p_name: x.name, p_description: x.description, p_sort: x.sort, p_archived: !x.archived }); md.close(); ctx.reload(); return { message: 'Module mis à jour.' }; }), 'ghost')))),
    h('div', { class: 'row' }, h('div', { class: 'f grow' }, name), h('div', { class: 'f grow' }, desc), h('button', { class: 'btn', type: 'button', onclick: ev => run(ev.currentTarget, async () => {
      await rpc('save_training_module', { p_org: ctx.me.id, p_id: null, p_name: name.value.trim(), p_description: desc.value.trim(), p_sort: null, p_archived: false }); md.close(); ctx.reload(); return { message: 'Module ajouté.' }; }) }, 'Ajouter')),
    h('div', { class: 'foot' }, btn('Fermer', () => md.close())));
}

function appModal(ctx, a, d) {
  const notes = d.notes.filter(n => n.application_id === a.id), open = ['recue', 'entretien'].includes(a.status);
  const black = d.blacklist.find(b => b.name.toLowerCase() === a.name.toLowerCase() || (b.discord_id && b.discord_id === a.discord_id));
  const note = input({ maxlength: 1000, placeholder: 'Ajouter une note', 'aria-label': 'Note' });
  const md = modal('Candidature ' + a.num, h('div', { class: 'pills' }, pill(...APP[a.status]), black ? pill('Sur la liste noire', 'bad') : null),
    h('dl', { class: 'lines-dl rules' }, kv('Nom', a.name), kv('Discord', a.discord_id), kv('Contact', a.contact), kv('Reçue', when(a.created_at)),
      kv('Entretien', a.interview_at ? when(a.interview_at) + ' · ' + nameOf(d.members, a.interviewer_id) : null), kv('Décision', a.decided_at ? when(a.decided_at) + ' · ' + a.decided_by : null)),
    h('h3', { class: 'sub-h' }, 'Réponses'), h('dl', { class: 'answers' }, a.answers.map(x => [h('dt', {}, x.q), h('dd', { class: 'prewrap' }, x.a)])),
    h('h3', { class: 'sub-h' }, 'Notes'), feed(notes.map(n => [n.at, 'note', n.text, n.by_name, { note: ['Note', null, 'journal'] }])),
    ctx.can('academie') ? h('div', { class: 'row' }, h('div', { class: 'f grow' }, note), h('button', { class: 'btn', type: 'button', onclick: ev => run(ev.currentTarget, async () => {
      await rpc('application_note', { p_id: a.id, p_text: note.value }); md.close(); ctx.reload(); return { message: 'Note ajoutée.' }; }) }, 'Noter')) : null,
    h('div', { class: 'foot' }, btn('Fermer', () => md.close()),
      open && ctx.can('academie') ? btn('Refuser', () => { md.close(); refuseApp(ctx, a); }, 'danger') : null,
      open && ctx.can('academie') ? btn('Entretien', () => { md.close(); interviewModal(ctx, a, d); }, null, 'calendrier') : null,
      open && ctx.can('effectifs') ? btn('Accepter et recruter', () => { md.close(); acceptModal(ctx, a, d); }, 'primary', 'badge') : null));
  md.box.classList.add('wide');
}
function interviewModal(ctx, a, d) {
  const at = h('input', { type: 'datetime-local', value: toLocal(a.interview_at ? ms(a.interview_at) : Date.now() + DAY), 'aria-label': 'Date de l’entretien' });
  const who = select(memberOpts(d.members), a.interviewer_id || ctx.me.member_id, { 'aria-label': 'Recruteur' }), ok = btn('Programmer', null, 'primary');
  const md = modal('Entretien · ' + a.name, h('div', { class: 'row' }, field('Date et heure', at, 'grow'), field('Recruteur', who, 'grow')),
    h('p', { class: 'note' }, 'L’entretien entre dans le planning du recruteur, qui reçoit une alerte.'), foot({ close: () => md.close() }, ok));
  ok.addEventListener('click', () => run(ok, async () => { await rpc('schedule_interview', { p_id: a.id, p_at: iso(new Date(at.value).getTime() || Date.now()), p_interviewer: who.value }); md.close(); ctx.reload(); return { message: 'Entretien programmé.' }; }));
}
function refuseApp(ctx, a) {
  const t = area({ maxlength: 500, placeholder: 'Pourquoi la candidature est refusée', 'aria-label': 'Motif' }), ok = btn('Refuser la candidature', null, 'danger');
  const md = modal('Refuser · ' + a.name, field('Motif', t), foot({ close: () => md.close() }, ok));
  ok.addEventListener('click', () => run(ok, async () => { await rpc('refuse_application', { p_id: a.id, p_note: t.value }); md.close(); ctx.reload(); return { message: 'Candidature refusée.' }; }));
}
function acceptModal(ctx, a, d) {
  const low = d.grades[d.grades.length - 1] || {};
  const login = input({ maxlength: 40, value: a.name.toLowerCase().normalize('NFD').replace(/\p{M}/gu, '').replace(/[^a-z0-9]+/g, '.').replace(/^\.|\.$/g, ''), 'aria-label': 'Identifiant de connexion' });
  const grade = select(d.grades.map(g => [g.id, g.name]), low.id, { 'aria-label': 'Grade' }), call = input({ maxlength: 20, placeholder: 'Ex. Marshal-40', 'aria-label': 'Indicatif' }), ok = btn('Recruter', null, 'primary');
  const md = modal('Recruter ' + a.name, h('div', { class: 'row' }, field('Identifiant de connexion', login, 'grow'), field('Grade', grade, 'grow')), field('Indicatif (facultatif)', call),
    h('p', { class: 'note' }, 'Le compte est créé tout de suite (liste noire et places du grade vérifiées). Tu reçois le lien d’activation à envoyer.'), foot({ close: () => md.close() }, ok));
  ok.addEventListener('click', () => run(ok, async () => {
    const r = await rpc('accept_application', { p_id: a.id, p_login: login.value.trim(), p_grade: grade.value, p_callsign: call.value.trim() || null });
    md.close(); ctx.reload(); ctx.linkModal('Recruté · ' + r.matricule, ctx.activationUrl(ctx.me.code, r.token), a.name);
  }));
}

function docModal(ctx, doc, urls) {
  const md = modal(doc.title, h('div', { class: 'pills' }, pill(DOCCAT[doc.category], 'info'), h('span', { class: 'muted small' }, 'Mis à jour le ' + day(doc.updated_at) + ' · ' + doc.by_name)),
    doc.body ? h('div', { class: 'docbody prewrap' }, doc.body) : null,
    doc.file_path ? h('p', {}, urls[doc.file_path] ? h('a', { href: urls[doc.file_path], target: '_blank', rel: 'noopener' }, ico('trombone', 16), ' ', doc.file_name || 'Pièce jointe') : 'Pièce jointe indisponible') : null,
    h('div', { class: 'foot' }, ctx.can('academie') ? btn('Supprimer', ev => run(ev.currentTarget, async () => { await rpc('delete_doc', { p_id: doc.id }); md.close(); ctx.reload(); return { message: 'Document supprimé.' }; }), 'danger') : null,
      ctx.can('academie') ? btn('Modifier', () => { md.close(); docEdit(ctx, doc); }) : null, btn('Fermer', () => md.close())));
  md.box.classList.add('wide');
}
function docEdit(ctx, doc) {
  const v = doc || {};
  const cat = select(Object.entries(DOCCAT), v.category || 'procedure', { 'aria-label': 'Catégorie' }), title = input({ maxlength: 120, value: v.title || '', 'aria-label': 'Titre' });
  const body = area({ maxlength: 20000, rows: 12, value: v.body || '', 'aria-label': 'Texte' }), file = h('input', { type: 'file', accept: 'application/pdf,image/png,image/jpeg,text/plain', 'aria-label': 'Pièce jointe' });
  const ok = btn(doc ? 'Enregistrer' : 'Publier', null, 'primary');
  const md = modal(doc ? doc.title : 'Nouveau document', h('div', { class: 'row' }, field('Catégorie', cat), field('Titre', title, 'grow')), field('Texte', body), field('Pièce jointe (PDF, image, texte)', file), foot({ close: () => md.close() }, ok));
  md.box.classList.add('wide');
  ok.addEventListener('click', () => run(ok, async () => {
    const f = file.files[0], path = await send(ctx.me.id, 'bibliotheque', f, false);
    await rpc('save_doc', { p_org: ctx.me.id, p_id: doc ? doc.id : null, p: { category: cat.value, title: title.value, body: body.value, file_path: path, file_name: f ? f.name : null } });
    md.close(); ctx.reload(); return { message: 'Document enregistré.' };
  }));
}

async function takeExam(ctx, x) {
  const paper = await rpc('exam_paper', { p_exam: x.id });
  const qs = paper.questions.map((q, i) => h('fieldset', { class: 'qcm' }, h('legend', {}, (i + 1) + '. ' + q.q),
    q.choices.map((c, j) => h('label', { class: 'check' }, h('input', { type: 'radio', name: 'q' + i, value: String(j) }), c))));
  const ok = btn('Rendre ma copie', null, 'primary');
  const md = modal(paper.title, h('p', { class: 'muted', style: 'margin:0' }, paper.questions.length + ' question(s) · ' + paper.pass_pct + ' % de bonnes réponses pour réussir. En cas d’échec, nouvelle tentative 24 heures plus tard.'), ...qs, foot({ close: () => md.close() }, ok));
  md.box.classList.add('wide');
  ok.addEventListener('click', () => run(ok, async () => {
    const answers = qs.map((f, i) => { const r = f.querySelector('input[name="q' + i + '"]:checked'); return r ? Number(r.value) : null; });
    if (answers.some(a => a === null)) throw new Error('Réponds à toutes les questions.');
    const r = await rpc('submit_exam', { p_exam: x.id, p_answers: answers });
    md.close(); ctx.reload();
    return { message: r.passed ? 'Réussi : ' + r.score + ' % (' + r.good + '/' + r.total + ').' : 'Échoué : ' + r.score + ' %, il faut ' + r.pass_pct + ' %. Nouvelle tentative dans 24 heures.' };
  }));
}
async function examEdit(ctx, x, modules) {
  const key = x ? (await rows('exam_keys', q => q.eq('exam_id', x.id)))[0] : null;
  const title = input({ maxlength: 80, value: x ? x.title : '', 'aria-label': 'Titre' }), pass = h('input', { type: 'number', min: 1, max: 100, value: x ? x.pass_pct : 70, class: 'short', 'aria-label': 'Seuil de réussite' });
  const mod = select([['', 'Aucun']].concat(modules.filter(m => !m.archived).map(m => [m.id, m.name])), x && x.module_id || '', { 'aria-label': 'Module validé' });
  const active = h('input', { type: 'checkbox', checked: x ? x.active : true });
  const box = h('div', { class: 'stack' });
  const addQ = q => {
    const v = q || { q: '', choices: ['', ''], answer: 0 }, n = box.children.length, gid = 'good-' + stamp();
    const qi = input({ maxlength: 300, value: v.q, placeholder: 'Énoncé', 'aria-label': 'Question ' + (n + 1) });
    const list = h('div', { class: 'stack' });
    const addC = (c, good) => { const ci = input({ maxlength: 200, value: c, placeholder: 'Réponse', 'aria-label': 'Réponse' }), rd = h('input', { type: 'radio', name: gid, checked: good, 'aria-label': 'Bonne réponse' });
      const row = h('div', { class: 'll-row' }, rd, ci, h('button', { class: 'btn small ghost', type: 'button', 'aria-label': 'Retirer la réponse', onclick: () => row.remove() }, ico('moins', 16))); row._get = () => [ci.value.trim(), rd.checked]; list.append(row); };
    v.choices.forEach((c, i) => addC(c, i === v.answer));
    const wrap = h('div', { class: 'qedit' }, qi, list, h('div', { class: 'acts' }, small('Réponse', () => addC('', false)), small('Retirer la question', () => wrap.remove(), 'ghost')));
    wrap._get = () => { const cs = [...list.children].map(r => r._get()).filter(([t]) => t); return { q: qi.value.trim(), choices: cs.map(([t]) => t), answer: Math.max(0, cs.findIndex(([, g]) => g)) }; };
    box.append(wrap);
  };
  ((key && key.questions) || [null]).forEach(addQ);
  const ok = btn('Enregistrer l’examen', null, 'primary');
  const md = modal(x ? x.title : 'Nouvel examen', h('div', { class: 'row' }, field('Titre', title, 'grow'), field('Réussite (%)', pass), field('Valide le module', mod)),
    h('label', { class: 'check' }, active, 'Ouvert aux agents'), h('h3', { class: 'sub-h' }, 'Questions (coche la bonne réponse)'), box, small('Ajouter une question', () => addQ(null)), foot({ close: () => md.close() }, ok));
  md.box.classList.add('wide');
  ok.addEventListener('click', () => run(ok, async () => {
    await rpc('save_exam', { p_org: ctx.me.id, p_id: x ? x.id : null, p: { title: title.value, pass_pct: Number(pass.value) || 70, module_id: mod.value, active: active.checked, questions: [...box.children].map(w => w._get()) } });
    md.close(); ctx.reload(); return { message: 'Examen enregistré.' };
  }));
}

function trainingModal(ctx) {
  const title = input({ maxlength: 80, placeholder: 'Ex. Tir de précision', 'aria-label': 'Titre' }), place = input({ maxlength: 80, placeholder: 'Ex. Stand de tir', 'aria-label': 'Lieu' });
  const t0 = h('input', { type: 'datetime-local', value: toLocal(Date.now() + DAY), 'aria-label': 'Début' }), t1 = h('input', { type: 'datetime-local', value: toLocal(Date.now() + DAY + 2 * 36e5), 'aria-label': 'Fin' });
  const note = input({ maxlength: 300, placeholder: 'Tenue, matériel… (facultatif)', 'aria-label': 'Note' }), ok = btn('Planifier', null, 'primary');
  const md = modal('Nouvel entraînement', field('Titre', title), h('div', { class: 'row' }, field('Début', t0, 'grow'), field('Fin', t1, 'grow')), field('Lieu', place), field('Note', note), foot({ close: () => md.close() }, ok));
  ok.addEventListener('click', () => run(ok, async () => {
    await rpc('plan_training', { p_org: ctx.me.id, p_title: title.value, p_starts: iso(new Date(t0.value).getTime()), p_ends: iso(new Date(t1.value).getTime()), p_place: place.value, p_note: note.value });
    md.close(); ctx.reload(); return { message: 'Entraînement planifié.' };
  }));
}
function rollModal(ctx, t, d) {
  const staff = d.staff.filter(x => x.agenda_id === t.id);
  const list = d.members.filter(m => m.active).map(m => { const st = staff.find(x => x.member_id === m.id); return [m, st, h('input', { type: 'checkbox', checked: !!(st && st.present), 'aria-label': m.name })]; });
  const ok = btn('Enregistrer l’appel', null, 'primary');
  const md = modal('Appel · ' + t.title, h('p', { class: 'muted', style: 'margin:0' }, 'Coche les présents. Les inscrits sont en tête.'),
    h('div', { class: 'checks col' }, list.sort((a, b) => !!b[1] - !!a[1]).map(([m, st, cb]) => h('label', { class: 'check' }, cb, (m.callsign ? m.callsign + ' · ' : '') + m.name, st ? h('small', { class: 'muted' }, ' inscrit') : null))),
    foot({ close: () => md.close() }, ok));
  ok.addEventListener('click', () => run(ok, async () => {
    for (const [m, st, cb] of list) if (cb.checked !== !!(st && st.present) && (st || cb.checked)) await rpc('mark_presence', { p_agenda: t.id, p_member: m.id, p_present: cb.checked });
    md.close(); ctx.reload(); return { message: 'Appel enregistré.' };
  }));
}

/** Proposer une promotion (aussi depuis la fiche agent) ; la raison part du modèle « promotion ». */
export function proposeModal(ctx, members, grades, templates, preset) {
  const cands = members.filter(m => m.active && m.id !== ctx.me.member_id);
  const who = select(cands.map(m => [m.id, m.name + ' · ' + gradeOf(grades, m.grade_id).name]), preset ? preset.id : (cands[0] || {}).id, { 'aria-label': 'Agent' });
  const grade = select([], '', { 'aria-label': 'Grade proposé' });
  const fillGrades = () => { const m = members.find(x => x.id === who.value), cur = m ? gradeOf(grades, m.grade_id).rank : 0;
    grade.replaceChildren(...grades.filter(g => g.rank > cur).reverse().map(g => h('option', { value: g.id }, g.name))); };
  who.addEventListener('change', fillGrades); fillGrades();
  const tpl = (templates.find(t => t.kind === 'promotion') || {}).body;
  const reason = area({ maxlength: 2000, rows: 6, value: (tpl && tpl.text) || '', placeholder: 'Ce que l’agent a montré : comportement, résultats, fiabilité', 'aria-label': 'Motivation' });
  const ok = btn('Proposer', null, 'primary');
  const md = modal('Proposer une promotion', preset ? null : field('Agent', who), field('Grade proposé', grade), field('Motivation', reason),
    h('p', { class: 'note' }, 'Ses statistiques (arrestations, heures, rapports, sanctions…) sont jointes automatiquement. Un responsable des effectifs décide.'), foot({ close: () => md.close() }, ok));
  ok.addEventListener('click', () => run(ok, async () => { await rpc('propose_promotion', { p_member: who.value, p_grade: grade.value, p_reason: reason.value }); md.close(); ctx.reload(); return { message: 'Proposition envoyée au commandement.' }; }));
}
function promoCard(ctx, p, d, top) {
  const m = d.members.find(x => x.id === p.member_id) || { name: '?' }, from = gradeOf(d.grades, m.grade_id), to = gradeOf(d.grades, p.to_grade_id);
  const days = m.rank_since ? Math.floor((Date.now() - ms(m.rank_since)) / DAY) : 0, okDays = !from.min_days || days >= from.min_days;
  const used = d.members.filter(x => x.active && x.grade_id === to.id).length, okSeat = !to.max_seats || used < to.max_seats, st = p.stats || {};
  const decide = okDecision => {
    const note = input({ maxlength: 300, placeholder: okDecision ? 'Mot pour l’agent (facultatif)' : 'Pourquoi (facultatif)', 'aria-label': 'Note' }), force = h('input', { type: 'checkbox' });
    const ok = btn(okDecision ? 'Promouvoir' : 'Refuser', null, okDecision ? 'primary' : 'danger');
    const md = modal((okDecision ? 'Promouvoir ' : 'Refuser · ') + m.name, field('Note', note), okDecision && !okDays && top ? h('label', { class: 'check' }, force, 'Passer outre l’ancienneté (Director)') : null, foot({ close: () => md.close() }, ok));
    ok.addEventListener('click', () => run(ok, async () => { await rpc('decide_promotion', { p_id: p.id, p_ok: okDecision, p_note: note.value.trim() || null, p_force: force.checked }); md.close(); ctx.reload(); return { message: okDecision ? m.name + ' passe ' + to.name + '.' : 'Proposition refusée.' }; }));
  };
  return h('section', { class: 'card wcard' }, h('div', { class: 'wc-top' }, h('b', { class: 'wc-num' }, m.name), pill(from.name + ' → ' + to.name, 'info'), pill(...PROMO[p.status]),
    h('span', { class: 'muted small grow' }, 'Proposé par ' + p.proposed_by + ' · ' + ago(ms(p.created_at)))),
    h('p', { class: 'prewrap' }, p.reason),
    strip([['Arrestations', String(st.arrests || 0)], ['Sur 30 jours', String(st.arrests_30 || 0)], ['Heures (30 j)', String(st.hours_30 || 0)], ['Rapports validés', String(st.reports || 0)],
      ['Dossiers résolus', String(st.cases_closed || 0)], ['Sanctions', String(st.sanctions || 0), st.sanctions ? 'warn-t' : null]]),
    p.status === 'proposee' ? h('div', { class: 'pills' }, pill((okDays ? '✓ ' : '✗ ') + 'Ancienneté : ' + days + ' j en ' + from.name + (from.min_days ? ' (' + from.min_days + ' requis)' : ''), okDays ? 'ok' : 'bad'),
      pill((okSeat ? '✓ ' : '✗ ') + 'Places : ' + to.name + ' ' + used + (to.max_seats ? '/' + to.max_seats : ''), okSeat ? 'ok' : 'bad')) : h('p', { class: 'muted small' }, 'Décidé par ' + p.decided_by + ' le ' + when(p.decided_at) + (p.decision_note ? ' · ' + p.decision_note : '')),
    p.status === 'proposee' && ctx.can('effectifs') && p.member_id !== ctx.me.member_id ? h('div', { class: 'acts' }, btn('Refuser', () => decide(false), 'danger'), btn('Promouvoir', () => decide(true), 'primary', 'etoile')) : null);
}

export async function academie(ctx) {
  const { me, can } = ctx;
  const mgr = can('academie'), seesApps = mgr || can('effectifs'), canPropose = can('effectifs') || can('valider') || mgr;
  const [members, grades, modules, progress, quals, mq, exams, attempts, evals, trainings, staff, docs, templates] = await Promise.all([load.members(ctx), load.grades(ctx),
    rows('training_modules', q => q.eq('org_id', me.id).order('sort')), rows('training_progress', q => q.eq('org_id', me.id)), rows('qualifications', q => q.eq('org_id', me.id)),
    rows('member_qualifications', q => q.eq('org_id', me.id)), rows('exams', q => q.eq('org_id', me.id).order('created_at')),
    rows('exam_attempts', q => q.eq('org_id', me.id).order('at', { ascending: false })), rows('field_evals', q => q.eq('org_id', me.id).order('at', { ascending: false })),
    rows('agenda', q => q.eq('org_id', me.id).eq('kind', 'formation').order('starts_at', { ascending: false }).limit(60)), rows('agenda_staff', q => q.eq('org_id', me.id)),
    rows('library_docs', q => q.eq('org_id', me.id).order('sort')), rows('doc_templates', q => q.eq('org_id', me.id))]);
  const [apps, notes, promos, blacklist] = await Promise.all([seesApps ? rows('applications', q => q.eq('org_id', me.id).order('created_at', { ascending: false })) : [],
    seesApps ? rows('application_notes', q => q.eq('org_id', me.id).order('at')) : [], canPropose ? rows('promotions', q => q.eq('org_id', me.id).order('created_at', { ascending: false })) : [],
    can('effectifs') ? rows('blacklist', q => q.eq('org_id', me.id)) : []]);
  const fto = mgr || quals.some(q => q.name.toUpperCase() === 'FTO' && mq.some(x => x.qualification_id === q.id && x.member_id === me.member_id));
  const d = { members, grades, modules, progress, evals, staff, notes, blacklist, fto };
  const live = modules.filter(x => !x.archived), meRow = members.find(m => m.id === me.member_id) || {};
  const openApps = apps.filter(a => ['recue', 'entretien'].includes(a.status)), openPromos = promos.filter(p => p.status === 'proposee');
  const TABS = [['parcours', 'Parcours'], seesApps ? ['candidatures', 'Candidatures (' + openApps.length + ')'] : null, ['bibliotheque', 'Bibliothèque'], ['examens', 'Examens'],
    ['entrainements', 'Entraînements'], canPropose ? ['promotions', 'Promotions (' + openPromos.length + ')'] : null].filter(Boolean);
  const tab = TABS.some(([k]) => k === S.filters.aca) ? S.filters.aca : 'parcours';
  const setTab = k => { S.filters.aca = k; academie(ctx).catch(ctx.fail); };
  let body;
  if (tab === 'parcours') {
    const trainees = members.filter(m => m.active && (m.stage !== 'titulaire' || m.fto_id));
    const mineIsTrainee = trainees.some(m => m.id === me.member_id);
    body = [mineIsTrainee ? h('section', { class: 'card' }, h('h2', {}, 'Mon parcours', h('span', { class: 'right' }, small('Détail', () => traineeModal(ctx, meRow, d)))),
      gauge('Progression', progressOf(meRow, live, progress), 'journal'), h('p', { class: 'muted small' }, 'Formateur : ' + (meRow.fto_id ? nameOf(members, meRow.fto_id) : 'pas encore attribué'))) : null,
    card(['Recrues et agents en formation', mgr ? h('span', { class: 'right' }, small('Modules du parcours', () => modulesModal(ctx, modules))) : null],
      table([['Agent', m => h('span', { class: 'art' }, h('b', {}, m.name), h('small', {}, gradeOf(grades, m.grade_id).name + (m.callsign ? ' · ' + m.callsign : '')))],
        ['Formateur', m => m.fto_id ? nameOf(members, m.fto_id) : pill('À attribuer', 'warn')], ['Progression', m => { const v = progressOf(m, live, progress); return h('span', { class: 'art' }, h('span', { class: 'meter ' + (v >= 100 ? 'ok' : 'warn') }, h('i', { style: 'width:' + v + '%' })), h('small', {}, v + ' %')); }, null, m => progressOf(m, live, progress)],
        ['Dernière évaluation', m => { const e = evals.find(x => x.member_id === m.id); return e ? h('span', { class: 'art' }, stars(e.overall), h('small', {}, day(e.at))) : '—'; }]],
      trainees, m => traineeModal(ctx, m, d), 'Aucune recrue en formation.'))];
  } else if (tab === 'candidatures') {
    const f = ['cours', 'acceptee', 'refusee', 'toutes'].includes(S.filters.acaApp) ? S.filters.acaApp : 'cours';
    const keep = { cours: a => ['recue', 'entretien'].includes(a.status), acceptee: a => a.status === 'acceptee', refusee: a => a.status === 'refusee', toutes: () => true }[f];
    body = [h('p', { class: 'muted' }, 'Les candidatures arrivent par le formulaire public : ', h('a', { href: '/avis/' + me.code + '/candidature', target: '_blank', rel: 'noopener' }, location.host + '/avis/' + me.code + '/candidature')),
      fchips([['cours', 'En cours'], ['acceptee', 'Acceptées'], ['refusee', 'Refusées'], ['toutes', 'Toutes']], f, k => { S.filters.acaApp = k; academie(ctx).catch(ctx.fail); }, 'Candidatures'),
      card(null, table([['N°', a => h('b', { class: 'nowrap' }, a.num)], ['Candidat', a => h('span', { class: 'art' }, h('b', {}, a.name), a.discord_id ? h('small', {}, 'Discord ' + a.discord_id) : null)],
        ['Reçue', a => ago(ms(a.created_at)), 'num', a => a.created_at], ['Entretien', a => a.interview_at ? when(a.interview_at) : '—'],
        ['Statut', a => h('span', { class: 'pills' }, pill(...APP[a.status]), blacklist.some(b => b.name.toLowerCase() === a.name.toLowerCase() || (b.discord_id && b.discord_id === a.discord_id)) ? pill('Liste noire', 'bad') : null)]],
      apps.filter(keep), a => appModal(ctx, a, d), 'Aucune candidature.'))];
  } else if (tab === 'bibliotheque') {
    const urls = await signedUrls(docs.map(x => x.file_path));
    body = [h('div', { class: 'grid cols2' }, Object.entries(DOCCAT).map(([k, label]) => card(label, docs.filter(x => x.category === k).length
      ? h('ul', { class: 'people' }, docs.filter(x => x.category === k).map(x => h('li', { class: 'clickable', tabindex: 0, onclick: () => docModal(ctx, x, urls), onkeydown: ev => { if (ev.key === 'Enter') docModal(ctx, x, urls); } },
        h('span', { class: 'ev-ic' }, ico(x.file_path ? 'trombone' : 'journal', 16)), h('span', { class: 'art' }, h('b', {}, x.title), h('small', {}, 'Mis à jour le ' + day(x.updated_at))))))
      : h('div', { class: 'empty' }, 'Rien pour l’instant.'))),
      card('Code pénal', h('p', { class: 'muted' }, 'Les infractions, amendes et peines du service.'), h('a', { class: 'btn', href: base(ctx) + '/rapports', onclick: () => { S.filters.rap = 'code'; } }, ico('marteau', 18), 'Ouvrir le code pénal')))];
  } else if (tab === 'examens') {
    const mineOf = x => attempts.filter(a => a.exam_id === x.id && a.member_id === me.member_id);
    body = [h('div', { class: 'grid cols2' }, exams.filter(x => x.active || mgr).map(x => {
      const mine = mineOf(x), passed = mine.find(a => a.passed), last = mine[0], wait = !passed && last && ms(last.at) + DAY > Date.now();
      const mod = modules.find(m => m.id === x.module_id);
      return h('section', { class: 'card' }, h('h2', {}, x.title, h('span', { class: 'right' }, x.active ? null : pill('Fermé'))),
        h('p', { class: 'muted small' }, x.questions + ' question(s) · réussite à ' + x.pass_pct + ' %' + (mod ? ' · valide « ' + mod.name + ' »' : '')),
        passed ? pill('Réussi le ' + day(passed.at) + ' · ' + passed.score + ' %', 'ok') : last ? pill('Dernière note : ' + last.score + ' %', 'bad') : null,
        h('div', { class: 'acts' }, !passed && x.active ? h('button', { class: 'btn primary', type: 'button', disabled: !!wait, title: wait ? 'Nouvelle tentative le ' + when(ms(last.at) + DAY) : null,
          onclick: ev => run(ev.currentTarget, async () => { await takeExam(ctx, x); }) }, wait ? 'Retente le ' + when(ms(last.at) + DAY) : 'Passer l’examen') : null,
          mgr ? small('Modifier', () => examEdit(ctx, x, modules).catch(ctx.fail)) : null));
    })),
      mgr ? card('Résultats', table([['Agent', a => nameOf(members, a.member_id)], ['Examen', a => (exams.find(x => x.id === a.exam_id) || {}).title || '—'], ['Note', a => a.score + ' %', 'num', a => a.score],
        ['Résultat', a => pill(a.passed ? 'Réussi' : 'Échoué', a.passed ? 'ok' : 'bad')], ['Date', a => when(a.at), 'num', a => a.at]], attempts.slice(0, 100), null, 'Aucune copie rendue.')) : null];
  } else if (tab === 'entrainements') {
    const now = Date.now(), coming = trainings.filter(t => ms(t.ends_at) >= now).reverse(), past = trainings.filter(t => ms(t.ends_at) < now);
    const row = t => { const st = staff.filter(x => x.agenda_id === t.id), mine = st.some(x => x.member_id === me.member_id), live = ms(t.ends_at) >= now;
      return h('li', {}, h('span', { class: 'ev-ic' }, ico('calendrier', 16)), h('span', { class: 'art grow' }, h('b', {}, t.title), h('small', {}, when(t.starts_at) + (t.place ? ' · ' + t.place : '') + ' · ' + st.length + ' inscrit(s)' + (live ? '' : ' · ' + st.filter(x => x.present).length + ' présent(s)'))),
        live ? small(mine ? 'Je ne viens plus' : 'Je viens', ev => run(ev.currentTarget, call(ctx, 'training_join', { p_id: t.id, p_on: !mine }, mine ? 'Désinscrit.' : 'Inscrit.')), mine ? 'ghost' : null) : null,
        fto && ms(t.starts_at) <= now + 36e5 ? small('Appel', () => rollModal(ctx, t, d)) : null); };
    body = [card('À venir', coming.length ? h('ul', { class: 'people' }, coming.map(row)) : h('div', { class: 'empty' }, 'Aucun entraînement prévu.')),
      card('Passés', past.length ? h('ul', { class: 'people' }, past.slice(0, 20).map(row)) : h('div', { class: 'empty' }, 'Rien pour l’instant.'))];
  } else {
    const top = isTop(ctx, grades);
    body = [openPromos.length ? openPromos.map(p => promoCard(ctx, p, d, top)) : card(null, h('div', { class: 'calm' }, h('span', { class: 'ic' }, ico('coche')), 'Aucune proposition en attente.')),
      card('Historique', table([['Agent', p => nameOf(members, p.member_id)], ['Proposé', p => gradeOf(grades, p.to_grade_id).name], ['Par', p => p.proposed_by], ['Statut', p => pill(...PROMO[p.status])],
        ['Date', p => when(p.created_at), 'num', p => p.created_at]], promos.filter(p => p.status !== 'proposee'), null, 'Aucune décision pour l’instant.'))];
  }
  ctx.show('academie', 'Académie', 'Recrutement, formation et promotions', [
    tab === 'bibliotheque' && mgr ? btn('Nouveau document', () => docEdit(ctx, null), 'primary', 'ajout') : null,
    tab === 'examens' && mgr ? btn('Nouvel examen', () => examEdit(ctx, null, modules).catch(ctx.fail), 'primary', 'ajout') : null,
    tab === 'entrainements' && mgr ? btn('Planifier', () => trainingModal(ctx), 'primary', 'calendrier') : null,
    tab === 'promotions' && canPropose ? btn('Proposer une promotion', () => proposeModal(ctx, members, grades, templates, null), 'primary', 'etoile') : null],
  card(null, strip([['En formation', String(members.filter(m => m.active && m.stage !== 'titulaire').length)], seesApps ? ['Candidatures à traiter', String(openApps.length), openApps.length ? 'warn-t' : null] : null,
    ['Examens ouverts', String(exams.filter(x => x.active).length)], ['Entraînements à venir', String(trainings.filter(t => ms(t.ends_at) >= Date.now()).length)],
    canPropose ? ['Promotions en attente', String(openPromos.length), openPromos.length ? 'warn-t' : null] : null])),
  tabs(TABS, tab, setTab), ...body);
}

// ================================================================== affaires internes
export function sanctionModal(ctx, members, preset, caseId, after) {
  const who = select(memberOpts(members, null, ctx.me.member_id), preset ? preset.id : '', { 'aria-label': 'Agent' });
  const kind = select(Object.entries(SANCTION).map(([k, [l]]) => [k, l]), 'avertissement', { 'aria-label': 'Sanction' });
  const days = h('input', { type: 'number', min: 1, max: 60, value: 3, class: 'short', 'aria-label': 'Durée (jours)' }), daysF = field('Durée (jours)', days);
  const why = area({ maxlength: 300, rows: 3, placeholder: 'Les faits reprochés', 'aria-label': 'Motif' }), ok = btn('Appliquer la sanction', null, 'danger');
  const sync = () => { daysF.hidden = kind.value !== 'suspension'; };
  kind.addEventListener('change', sync); sync();
  const md = modal('Sanction', preset ? h('p', { class: 'muted', style: 'margin:0' }, preset.name) : field('Agent', who), h('div', { class: 'row' }, field('Sanction', kind, 'grow'), daysF), field('Motif', why),
    h('p', { class: 'note' }, 'Appliquée tout de suite : une suspension retire tout accès au MDT jusqu’à son terme, une rétrogradation fait descendre d’un grade, un renvoi désactive le compte.'),
    foot({ close: () => md.close() }, ok));
  ok.addEventListener('click', () => run(ok, async () => {
    await rpc('apply_sanction', { p_member: preset ? preset.id : who.value, p_kind: kind.value, p_reason: why.value, p_days: kind.value === 'suspension' ? Number(days.value) : null, p_case: caseId || null });
    md.close(); (after || ctx.reload)(); return { message: 'Sanction appliquée.' };
  }));
}
/** Plainte d'un agent contre un collègue (depuis la fiche agent). */
export function complaintModal(ctx, m) {
  const facts = area({ maxlength: 3000, rows: 6, placeholder: 'Les faits, précisément : ce qui s’est passé, les témoins', 'aria-label': 'Faits' }), happened = input({ maxlength: 120, placeholder: 'Ex. Hier soir, Mission Row', 'aria-label': 'Quand et où' });
  const ok = btn('Envoyer aux Affaires internes', null, 'primary');
  const md = modal('Plainte contre ' + m.name, field('Quand et où', happened), field('Faits', facts), h('p', { class: 'note' }, 'Seules les Affaires internes la lisent ; l’agent visé ne la voit pas. Tu peux suivre son statut.'), foot({ close: () => md.close() }, ok));
  ok.addEventListener('click', () => run(ok, async () => { const n = await rpc('file_complaint', { p_org: ctx.me.id, p: { against_member_id: m.id, facts: facts.value, happened: happened.value } }); md.close(); return { message: 'Plainte ' + n + ' transmise.' }; }));
}
function openCaseModal(ctx, members) {
  const who = select(memberOpts(members, 'Aucun agent en particulier', ctx.me.member_id), '', { 'aria-label': 'Agent visé' }), why = input({ maxlength: 300, placeholder: 'Objet de l’enquête', 'aria-label': 'Objet' });
  const ok = btn('Ouvrir le dossier', null, 'primary');
  const md = modal('Nouveau dossier', field('Agent visé', who), field('Objet', why), foot({ close: () => md.close() }, ok));
  ok.addEventListener('click', () => run(ok, async () => { const r = await rpc('open_ia_case', { p_org: ctx.me.id, p: { member_id: who.value, reason: why.value } }); md.close(); ctx.go(base(ctx) + '/affaires/' + r.id); return { message: 'Dossier ' + r.num + ' ouvert.' }; }));
}
function officerModal(ctx, members, current) {
  const who = select(memberOpts(members, 'Personne (le Director seul)', ctx.me.member_id), current || '', { 'aria-label': 'Personne désignée' }), ok = btn('Enregistrer', null, 'primary');
  const md = modal('Personne chargée des Affaires internes', h('p', { class: 'muted', style: 'margin:0' }, 'En plus des grades qui ont l’accès « Affaires internes », tu peux confier les enquêtes à une personne. Un dossier qui la vise lui reste fermé.'),
    field('Personne désignée', who), foot({ close: () => md.close() }, ok));
  ok.addEventListener('click', () => run(ok, async () => { await rpc('set_ia_officer', { p_org: ctx.me.id, p_member: who.value || null }); md.close(); ctx.reload(); return { message: 'Désignation enregistrée.' }; }));
}

export async function affaires(ctx) {
  const { me } = ctx;
  const tab = ['dossiers', 'plaintes', 'casier', 'journal'].includes(S.filters.ai) ? S.filters.ai : 'dossiers';
  const [cases, complaints, discipline, members, grades, org, journal] = await Promise.all([rows('ia_cases', q => q.eq('org_id', me.id).order('opened_at', { ascending: false })),
    rows('complaints', q => q.eq('org_id', me.id).order('created_at', { ascending: false })), rows('discipline', q => q.eq('org_id', me.id).order('at', { ascending: false })),
    load.members(ctx), load.grades(ctx), rows('orgs', q => q.eq('id', me.id)), tab === 'journal' ? rows('journal', q => q.eq('org_id', me.id).order('at', { ascending: false }).limit(400)) : []]);
  const top = isTop(ctx, grades), open = cases.filter(c => ['ouvert', 'enquete'].includes(c.status)), todo = complaints.filter(c => c.status === 'recue');
  const live = discipline.filter(x => x.kind === 'suspension' && !x.lifted_at && x.until && ms(x.until) > Date.now());
  const setTab = k => { S.filters.ai = k; affaires(ctx).catch(ctx.fail); };
  let body;
  if (tab === 'dossiers') {
    body = card(null, table([['N°', c => h('b', { class: 'nowrap' }, c.num)], ['Agent visé', c => c.member_id ? nameOf(members, c.member_id) : '—'], ['Objet', c => c.reason], ['Origine', c => ORIGIN[c.origin]],
      ['Statut', c => pill(...IA_ST[c.status]), null, c => c.status], ['Ouvert', c => when(c.opened_at), 'num', c => c.opened_at]], cases, c => ctx.go(base(ctx) + '/affaires/' + c.id), 'Aucun dossier.'));
  } else if (tab === 'plaintes') {
    body = [h('p', { class: 'muted' }, 'Les civils déposent plainte sur ', h('a', { href: '/avis/' + me.code + '/plainte', target: '_blank', rel: 'noopener' }, location.host + '/avis/' + me.code + '/plainte'), '.'),
      card(null, table([['N°', c => h('b', { class: 'nowrap' }, c.num)], ['Source', c => pill(c.source === 'public' ? 'Publique' : 'Interne · ' + (c.complainant || ''), c.source === 'public' ? 'info' : null)],
        ['Agent visé', c => c.against_member_id ? nameOf(members, c.against_member_id) : c.against_text || '—'], ['Faits', c => h('span', { class: 'clip' }, c.facts)], ['Reçue', c => ago(ms(c.created_at)), 'num', c => c.created_at],
        ['Statut', c => pill(...COMPL[c.status])], ['', c => c.status === 'recue' ? h('span', { class: 'acts' }, small('Enquêter', ev => run(ev.currentTarget, async () => { const r = await rpc('open_ia_case', { p_org: me.id, p: { complaint_id: c.id } }); ctx.go(base(ctx) + '/affaires/' + r.id); return { message: 'Dossier ' + r.num + ' ouvert.' }; }), 'primary'),
          small('Classer', ev => run(ev.currentTarget, call(ctx, 'complaint_status', { p_id: c.id, p_status: 'classee' }, 'Plainte classée.')), 'ghost')) : c.ia_case_id ? small('Dossier', () => ctx.go(base(ctx) + '/affaires/' + c.ia_case_id)) : null]],
      complaints, c => { const md = modal('Plainte ' + c.num, h('dl', { class: 'lines-dl rules' }, kv('Agent visé', c.against_member_id ? nameOf(members, c.against_member_id) : c.against_text), kv('Quand et où', c.happened),
        kv('Plaignant', c.complainant || 'Anonyme'), kv('Contact', c.contact), kv('Reçue', when(c.created_at))), h('p', { class: 'prewrap' }, c.facts), h('div', { class: 'foot' }, btn('Fermer', () => md.close()))); }, 'Aucune plainte.'))];
  } else if (tab === 'casier') {
    body = card(null, table([['Agent', x => h('a', { href: base(ctx) + '/effectifs/' + x.member_id, onclick: ev => ev.stopPropagation() }, nameOf(members, x.member_id))], ['Sanction', x => pill(...SANCTION[x.kind])],
      ['Motif', x => x.reason], ['Jusqu’au', x => x.until ? when(x.until) + (x.lifted_at ? ' · levée' : '') : '—'], ['Date', x => when(x.at), 'num', x => x.at], ['Par', x => x.by_name],
      ['', x => live.includes(x) ? small('Lever', ev => run(ev.currentTarget, call(ctx, 'lift_suspension', { p_member: x.member_id }, 'Suspension levée.'))) : null]], discipline, null, 'Casier vierge.'));
  } else {
    const list = h('div', {});
    const paint = () => list.replaceChildren(table([['Date', j => when(j.at), 'num', j => j.at], ['Qui', j => j.who], ['Action', j => j.what]], journal.filter(j => match(S.filters.aiQ, j.who, j.what)), null, 'Rien dans le journal.'));
    paint();
    body = card(null, h('div', { class: 'cardhead' }, search(S.filters.aiQ, 'Chercher dans le journal (agent, action…)', v => { S.filters.aiQ = v; paint(); })), list);
  }
  ctx.show('affaires', 'Affaires internes', 'Accès réservé · ' + open.length + ' dossier(s) ouvert(s)', [top ? btn('Personne désignée', () => officerModal(ctx, members, org[0] && org[0].ia_member_id)) : null,
    btn('Sanctionner', () => sanctionModal(ctx, members, null, null)), btn('Ouvrir un dossier', () => openCaseModal(ctx, members), 'primary', 'ajout')],
  card(null, strip([['Dossiers ouverts', String(open.length), open.length ? 'warn-t' : null], ['Plaintes à traiter', String(todo.length), todo.length ? 'warn-t' : null], ['Suspendus', String(live.length)],
    ['Sanctions (30 j)', String(discipline.filter(x => ms(x.at) > Date.now() - 30 * DAY).length)]])),
  tabs([['dossiers', 'Dossiers'], ['plaintes', 'Plaintes (' + todo.length + ')'], ['casier', 'Casier disciplinaire'], ['journal', 'Journal d’audit']], tab, setTab), body);
}

export async function affaire(ctx, id) {
  const { me } = ctx;
  const [c] = await rows('ia_cases', q => q.eq('id', id));
  if (!c) return ctx.go(base(ctx) + '/affaires', true);
  const [log, complaints, members, discipline] = await Promise.all([rows('ia_log', q => q.eq('case_id', id).order('at')), c.complaint_id ? rows('complaints', q => q.eq('id', c.complaint_id)) : [],
    load.members(ctx), rows('discipline', q => q.eq('ia_case_id', id))]);
  const urls = await signedUrls(log.map(x => x.path));
  const open = ['ouvert', 'enquete'].includes(c.status), target = members.find(m => m.id === c.member_id), pl = complaints[0];
  const facts = area({ maxlength: 4000, rows: 6, value: c.facts || '', disabled: !open, 'aria-label': 'Faits' }), version = area({ maxlength: 4000, rows: 5, value: c.agent_version || '', disabled: !open, 'aria-label': 'Version de l’agent' });
  const inv = select(memberOpts(members, 'À désigner', c.member_id), c.investigator_id || '', { disabled: !open, 'aria-label': 'Enquêteur' });
  const kind = select([['note', 'Note'], ['preuve', 'Preuve'], ['audition', 'Audition']], 'note', { 'aria-label': 'Type d’entrée' }), text = area({ maxlength: 2000, rows: 3, placeholder: 'Constat, déclaration, élément de preuve…', 'aria-label': 'Entrée' });
  const file = h('input', { type: 'file', 'aria-label': 'Fichier (facultatif)' });
  const close = () => {
    const dec = area({ maxlength: 2000, rows: 5, placeholder: 'Ce que l’enquête établit et la décision', 'aria-label': 'Décision' });
    const sanc = select([['', 'Classer sans sanction']].concat(Object.entries(SANCTION).map(([k, [l]]) => [k, l])), '', { 'aria-label': 'Suite' });
    const days = h('input', { type: 'number', min: 1, max: 60, value: 3, class: 'short', 'aria-label': 'Durée (jours)' }), daysF = field('Durée (jours)', days);
    const sync = () => { daysF.hidden = sanc.value !== 'suspension'; }; sanc.addEventListener('change', sync); sync();
    const ok = btn('Clore le dossier', null, 'danger');
    const md = modal('Clore ' + c.num, field('Décision', dec), h('div', { class: 'row' }, field('Suite', sanc, 'grow'), daysF),
      !target ? h('p', { class: 'note' }, 'Aucun agent n’est visé : le dossier ne peut être que classé.') : h('p', { class: 'note' }, 'La sanction choisie s’applique tout de suite à ' + target.name + '.'), foot({ close: () => md.close() }, ok));
    ok.addEventListener('click', () => run(ok, async () => { await rpc('ia_close', { p_id: c.id, p_decision: dec.value, p_sanction: sanc.value || null, p_days: sanc.value === 'suspension' ? Number(days.value) : null }); md.close(); ctx.reload(); return { message: 'Dossier clos.' }; }));
  };
  ctx.show('affaires', 'Dossier ' + c.num, [pill(...IA_ST[c.status]), ' ', ORIGIN[c.origin] + ' · ouvert le ' + when(c.opened_at)],
    [open ? btn('Clore le dossier', close, 'danger', 'marteau') : null, h('a', { class: 'btn ghost', href: base(ctx) + '/affaires' }, 'Tous les dossiers')],
    h('div', { class: 'grid cols2' },
      card('Objet', h('p', {}, c.reason), h('dl', { class: 'lines-dl rules' }, kv('Agent visé', target ? h('a', { href: base(ctx) + '/effectifs/' + target.id }, target.name + (target.matricule ? ' · ' + target.matricule : '')) : 'Aucun'),
        kv('Ouvert par', c.opened_by), kv('Enquêteur', c.investigator_id ? nameOf(members, c.investigator_id) : null))),
      pl ? card('Plainte ' + pl.num, h('dl', { class: 'lines-dl rules' }, kv('Source', pl.source === 'public' ? 'Page publique' : 'Interne'), kv('Plaignant', pl.complainant || 'Anonyme'), kv('Contact', pl.contact), kv('Quand et où', pl.happened)),
        h('p', { class: 'prewrap' }, pl.facts)) : c.decision ? card('Décision', h('p', { class: 'prewrap' }, c.decision), h('p', { class: 'muted small' }, c.decided_by + ' · ' + when(c.decided_at)),
          discipline.map(x => pill(SANCTION[x.kind][0] + ' · ' + x.reason, 'bad'))) : card('Décision', h('p', { class: 'muted' }, 'En attente de la fin de l’enquête.'))),
    pl && c.decision ? card('Décision', h('p', { class: 'prewrap' }, c.decision), h('p', { class: 'muted small' }, c.decided_by + ' · ' + when(c.decided_at)), discipline.map(x => pill(SANCTION[x.kind][0] + ' · ' + x.reason, 'bad'))) : null,
    h('div', { class: 'grid cols2' }, card('Faits établis', facts), card('Version de l’agent', version)),
    open ? h('div', { class: 'acts' }, field('Enquêteur', inv), btn('Enregistrer', ev => run(ev.currentTarget, call(ctx, 'ia_update', { p_id: c.id, p: { facts: facts.value, agent_version: version.value, investigator_id: inv.value } }, 'Dossier enregistré.')), 'primary')) : null,
    card(['Journal d’enquête', h('span', { class: 'muted small' }, log.length + ' entrée(s)')],
      feed(log.map(x => [x.at, x.kind, x.path ? [x.text, ' ', urls[x.path] ? h('a', { href: urls[x.path], target: '_blank', rel: 'noopener' }, ico('trombone', 14), ' ', x.file_name || 'fichier') : null] : x.text, x.by_name, IA_LOG])),
      open ? h('div', { class: 'stack' }, h('div', { class: 'row' }, field('Type', kind), field('Fichier (facultatif)', file, 'grow')), text,
        h('div', { class: 'acts' }, btn('Ajouter au journal', ev => run(ev.currentTarget, async () => {
          const f = file.files[0], path = f ? await send(me.id, 'ia/' + c.id, f, false) : null;
          await rpc('ia_note', { p_id: c.id, p_kind: kind.value, p_text: text.value, p_path: path, p_name: f ? f.name : null }); ctx.reload(); return { message: 'Ajouté au journal d’enquête.' };
        }), 'primary'))) : null));
}

// ================================================================== logistique
const gearNote = (x, grades, quals, sog) => [x.min_grade_id ? gradeOf(grades, x.min_grade_id).name + ' et plus' : null, x.qualification_id ? (quals.find(q => q.id === x.qualification_id) || {}).name : null, sog ? 'SOG' : null].filter(Boolean).join(' · ') || 'Tous';
function gearFields(v, grades, quals) {
  const g = select([['', 'Tous les grades']].concat(grades.slice().reverse().map(x => [x.id, x.name + ' et plus'])), v.min_grade_id || '', { 'aria-label': 'Grade minimum' });
  const q = select([['', 'Aucune']].concat(quals.filter(x => !x.archived).map(x => [x.id, x.name])), v.qualification_id || '', { 'aria-label': 'Qualification requise' });
  return [g, q, h('div', { class: 'row' }, field('Grade minimum', g, 'grow'), field('Qualification requise', q, 'grow'))];
}
function vehicleEdit(ctx, v, d) {
  const x = v || {};
  const model = input({ maxlength: 60, value: x.model || '', placeholder: 'Ex. Vapid Scout', 'aria-label': 'Modèle' }), plate = input({ maxlength: 10, value: x.plate || '', placeholder: 'USMS 12', 'aria-label': 'Plaque' });
  const kind = select(Object.entries(VK), x.kind || 'marque', { 'aria-label': 'Type' }), notes = input({ maxlength: 300, value: x.notes || '', 'aria-label': 'Notes' });
  const [g, q, row] = gearFields(x, d.grades, d.quals), ok = btn('Enregistrer', null, 'primary');
  const md = modal(v ? v.model + ' · ' + v.plate : 'Nouveau véhicule', h('div', { class: 'row' }, field('Modèle', model, 'grow'), field('Plaque', plate)), field('Type', kind), row, field('Notes', notes),
    h('p', { class: 'note' }, 'Un blindé est réservé aux opérateurs SOG.'), foot({ close: () => md.close() }, ok));
  ok.addEventListener('click', () => run(ok, async () => { await rpc('fleet_save', { p_org: ctx.me.id, p_id: v ? v.id : null, p: { model: model.value, plate: plate.value, kind: kind.value, min_grade_id: g.value, qualification_id: q.value, notes: notes.value } }); md.close(); ctx.reload(); return { message: 'Véhicule enregistré.' }; }));
}
function takeModal(ctx, v) {
  const why = input({ maxlength: 120, placeholder: 'Ex. Patrouille, transfert USMS-TR-0003', 'aria-label': 'Motif' }), until = h('input', { type: 'datetime-local', 'aria-label': 'Retour prévu' }), ok = btn('Prendre le véhicule', null, 'primary');
  const md = modal(v.model + ' · ' + v.plate, field('Motif', why), field('Retour prévu (facultatif)', until), foot({ close: () => md.close() }, ok));
  ok.addEventListener('click', () => run(ok, async () => { await rpc('fleet_take', { p_vehicle: v.id, p_purpose: why.value, p_until: until.value ? iso(new Date(until.value).getTime()) : null }); md.close(); ctx.reload(); return { message: 'Bonne route.' }; }));
}
function returnModal(ctx, v, trip) {
  const dmg = input({ maxlength: 500, placeholder: 'Rien à signaler', 'aria-label': 'Dégâts' }), ok = btn('Rendre le véhicule', null, 'primary');
  const md = modal('Retour · ' + v.plate, field('Dégâts constatés (facultatif)', dmg), foot({ close: () => md.close() }, ok));
  ok.addEventListener('click', () => run(ok, async () => { await rpc('fleet_return', { p_trip: trip.id, p_damage: dmg.value || null }); md.close(); ctx.reload(); return { message: 'Véhicule rendu.' }; }));
}
function bookModal(ctx, v) {
  const t0 = h('input', { type: 'datetime-local', value: toLocal(Date.now() + 36e5), 'aria-label': 'Début' }), t1 = h('input', { type: 'datetime-local', value: toLocal(Date.now() + 3 * 36e5), 'aria-label': 'Fin' });
  const why = input({ maxlength: 120, placeholder: 'Ex. Audience, escorte', 'aria-label': 'Motif' }), ok = btn('Réserver', null, 'primary');
  const md = modal('Réserver · ' + v.model + ' ' + v.plate, h('div', { class: 'row' }, field('Début', t0, 'grow'), field('Fin', t1, 'grow')), field('Motif', why), foot({ close: () => md.close() }, ok));
  ok.addEventListener('click', () => run(ok, async () => { await rpc('fleet_book', { p_vehicle: v.id, p_from: iso(new Date(t0.value).getTime()), p_to: iso(new Date(t1.value).getTime()), p_purpose: why.value }); md.close(); ctx.reload(); return { message: 'Véhicule réservé.' }; }));
}
function vehicleView(ctx, v, d) {
  const trips = d.trips.filter(t => t.vehicle_id === v.id), dmg = d.damage.filter(x => x.vehicle_id === v.id), mgr = ctx.can('logistique');
  const report = input({ maxlength: 500, placeholder: 'Ex. Pare-brise fissuré', 'aria-label': 'Dégâts à signaler' });
  const state = select(Object.entries(VST).map(([k, [l]]) => [k, l]), v.state, { 'aria-label': 'État' });
  const md = modal(v.model + ' · ' + v.plate, h('div', { class: 'pills' }, pill(VK[v.kind], 'info'), pill(...VST[v.state])), h('p', { class: 'muted small' }, 'Accès : ' + gearNote(v, d.grades, d.quals, v.kind === 'blinde') + (v.notes ? ' · ' + v.notes : '')),
    h('h3', { class: 'sub-h' }, 'Sorties et réservations'), table([['Agent', t => nameOf(d.members, t.member_id)], ['Statut', t => t.status === 'sortie' ? pill('Sorti', 'warn') : t.status === 'reservee' ? pill('Réservé', 'info') : t.status === 'rendue' ? 'Rendu' : 'Annulé'],
      ['Quand', t => when(t.out_at || t.starts_at) + (t.back_at ? ' → ' + when(t.back_at) : t.ends_at ? ' → ' + when(t.ends_at) : ''), 'num', t => t.out_at || t.starts_at], ['Motif', t => t.purpose || '—'],
      ['', t => t.status === 'reservee' && (t.member_id === ctx.me.member_id || mgr) ? small('Annuler', ev => run(ev.currentTarget, async () => { await rpc('fleet_cancel', { p_trip: t.id }); md.close(); ctx.reload(); return { message: 'Réservation annulée.' }; }), 'ghost') : null]], trips.slice(0, 20), null, 'Jamais sorti.'),
    h('h3', { class: 'sub-h' }, 'Dégâts'), dmg.length ? h('ul', { class: 'people' }, dmg.map(x => h('li', {}, h('span', { class: 'ev-ic' }, ico('alerte', 16)), h('span', { class: 'art' }, h('b', {}, x.description), h('small', {}, when(x.at) + ' · ' + x.by_name + (x.repaired_at ? ' · réparé par ' + x.repaired_by : ' · à réparer')))))) : h('p', { class: 'muted' }, 'Aucun dégât signalé.'),
    h('div', { class: 'row' }, h('div', { class: 'f grow' }, report), h('button', { class: 'btn', type: 'button', onclick: ev => run(ev.currentTarget, async () => { await rpc('fleet_report_damage', { p_vehicle: v.id, p_text: report.value }); md.close(); ctx.reload(); return { message: 'Dégâts signalés à la logistique.' }; }) }, 'Signaler')),
    mgr ? h('div', { class: 'row' }, field('État', state, 'grow'), h('button', { class: 'btn', type: 'button', onclick: ev => run(ev.currentTarget, async () => { await rpc('fleet_state', { p_vehicle: v.id, p_state: state.value }); md.close(); ctx.reload(); return { message: 'État mis à jour.' }; }) }, 'Appliquer')) : null,
    h('div', { class: 'foot' }, mgr ? btn('Modifier', () => { md.close(); vehicleEdit(ctx, v, d); }) : null, btn('Fermer', () => md.close())));
  md.box.classList.add('wide');
}
function weaponEdit(ctx, w, d) {
  const x = w || {};
  const serial = input({ maxlength: 30, value: x.serial || '', placeholder: 'Ex. GL17-00042', 'aria-label': 'Numéro de série' }), model = input({ maxlength: 60, value: x.model || '', placeholder: 'Ex. Glock 17', 'aria-label': 'Modèle' });
  const cat = select(Object.entries(WCAT), x.category || 'poing', { 'aria-label': 'Catégorie' }), notes = input({ maxlength: 300, value: x.notes || '', 'aria-label': 'Notes' });
  const [g, q, row] = gearFields(x, d.grades, d.quals), ok = btn('Enregistrer', null, 'primary');
  const md = modal(w ? w.model + ' · ' + w.serial : 'Entrer une arme', h('div', { class: 'row' }, field('N° de série', serial, 'grow'), field('Modèle', model, 'grow')), field('Catégorie', cat), row, field('Notes', notes),
    h('p', { class: 'note' }, 'Une arme lourde ne se remet qu’à un opérateur SOG.'), foot({ close: () => md.close() }, ok));
  ok.addEventListener('click', () => run(ok, async () => { await rpc('save_weapon', { p_org: ctx.me.id, p_id: w ? w.id : null, p: { serial: serial.value, model: model.value, category: cat.value, min_grade_id: g.value, qualification_id: q.value, notes: notes.value } }); md.close(); ctx.reload(); return { message: 'Arme enregistrée.' }; }));
}
function issueModal(ctx, w, d) {
  const who = select(memberOpts(d.members), '', { 'aria-label': 'Agent' }), note = input({ maxlength: 200, placeholder: 'Ex. Dotation, opération Grove', 'aria-label': 'Note' }), ok = btn('Remettre l’arme', null, 'primary');
  const md = modal('Remettre ' + w.model + ' · ' + w.serial, field('Agent', who), field('Note', note), h('p', { class: 'note' }, 'Accès : ' + gearNote(w, d.grades, d.quals, w.category === 'lourde')), foot({ close: () => md.close() }, ok));
  ok.addEventListener('click', () => run(ok, async () => { await rpc('issue_weapon', { p_weapon: w.id, p_member: who.value, p_note: note.value }); md.close(); ctx.reload(); return { message: 'Arme remise.' }; }));
}
function moveModal(ctx, e, d) {
  const dir = select([['out', 'Sortie (remise à un agent, consommation)'], ['in', 'Entrée (livraison, retour)']], 'out', { 'aria-label': 'Mouvement' });
  const qty = h('input', { type: 'number', min: 1, value: 1, class: 'short', 'aria-label': 'Quantité' }), who = select(memberOpts(d.members, 'Aucun agent'), '', { 'aria-label': 'Agent' });
  const note = input({ maxlength: 200, 'aria-label': 'Note' }), ok = btn('Enregistrer', null, 'primary');
  const md = modal(e.name + ' · ' + e.qty + ' ' + e.unit, h('div', { class: 'row' }, field('Mouvement', dir, 'grow'), field('Quantité', qty)), field('Agent', who), field('Note', note), foot({ close: () => md.close() }, ok));
  ok.addEventListener('click', () => run(ok, async () => { const n = Math.abs(Number(qty.value) || 0); const left = await rpc('move_equipment', { p_item: e.id, p_qty: dir.value === 'out' ? -n : n, p_member: who.value || null, p_note: note.value }); md.close(); ctx.reload(); return { message: 'Stock : ' + left + ' ' + e.unit + '.' }; }));
}
function equipEdit(ctx, e) {
  const x = e || {};
  const name = input({ maxlength: 60, value: x.name || '', placeholder: 'Ex. Munitions 9 mm', 'aria-label': 'Article' }), kind = select(Object.entries(EQK), x.kind || 'munitions', { 'aria-label': 'Type' });
  const unit = input({ maxlength: 20, value: x.unit || 'unité', 'aria-label': 'Unité' }), alert = h('input', { type: 'number', min: 0, value: x.alert_below ?? '', class: 'short', 'aria-label': 'Alerte sous' }), ok = btn('Enregistrer', null, 'primary');
  const md = modal(e ? e.name : 'Nouvel article', field('Article', name), h('div', { class: 'row' }, field('Type', kind, 'grow'), field('Unité', unit), field('Alerte sous', alert)), foot({ close: () => md.close() }, ok));
  ok.addEventListener('click', () => run(ok, async () => { await rpc('save_equipment', { p_org: ctx.me.id, p_id: e ? e.id : null, p: { name: name.value, kind: kind.value, unit: unit.value, alert_below: alert.value } }); md.close(); ctx.reload(); return { message: 'Article enregistré.' }; }));
}

export async function logistique(ctx) {
  const { me, can } = ctx;
  const mgr = can('logistique');
  const tab = ['parc', 'armurerie', 'equipement'].includes(S.filters.log) ? S.filters.log : 'parc';
  const [fleet, trips, damage, weapons, loans, equipment, moves, members, grades, quals] = await Promise.all([rows('fleet', q => q.eq('org_id', me.id).order('plate')),
    rows('fleet_trips', q => q.eq('org_id', me.id).order('starts_at', { ascending: false }).limit(300)), rows('fleet_damage', q => q.eq('org_id', me.id).order('at', { ascending: false }).limit(200)),
    rows('weapons', q => q.eq('org_id', me.id).order('serial')), rows('weapon_loans', q => q.eq('org_id', me.id).order('out_at', { ascending: false }).limit(200)),
    rows('equipment', q => q.eq('org_id', me.id).order('name')), rows('equipment_moves', q => q.eq('org_id', me.id).order('at', { ascending: false }).limit(60)),
    load.members(ctx), load.grades(ctx), rows('qualifications', q => q.eq('org_id', me.id))]);
  const d = { fleet, trips, damage, members, grades, quals };
  const outOf = v => trips.find(t => t.vehicle_id === v.id && t.status === 'sortie');
  const nextBook = v => trips.filter(t => t.vehicle_id === v.id && t.status === 'reservee' && ms(t.ends_at || t.starts_at) > Date.now()).sort((a, b) => ms(a.starts_at) - ms(b.starts_at))[0];
  const myWeapons = weapons.filter(w => w.holder_id === me.member_id), low = equipment.filter(e => e.alert_below !== null && e.qty <= e.alert_below);
  const setTab = k => { S.filters.log = k; logistique(ctx).catch(ctx.fail); };
  let body;
  if (tab === 'parc') {
    body = card(null, table([['Véhicule', v => h('span', { class: 'art' }, h('b', {}, v.model), h('small', {}, v.plate))], ['Type', v => VK[v.kind]], ['État', v => pill(...VST[v.state]), null, v => v.state],
      ['Disponibilité', v => { const o = outOf(v), b = nextBook(v); return o ? h('span', { class: 'art' }, pill('Sorti · ' + nameOf(members, o.member_id), 'warn'), o.purpose ? h('small', {}, o.purpose) : null)
        : b ? h('span', { class: 'art' }, pill('Libre', 'ok'), h('small', {}, 'Réservé le ' + when(b.starts_at) + ' · ' + nameOf(members, b.member_id))) : ['reparation', 'hors_service'].includes(v.state) ? '—' : pill('Libre', 'ok'); }],
      ['Accès', v => gearNote(v, grades, quals, v.kind === 'blinde')],
      ['', v => { const o = outOf(v); return h('span', { class: 'acts' }, o && (o.member_id === me.member_id || mgr) ? small('Rendre', () => returnModal(ctx, v, o), 'primary')
        : !o && !['reparation', 'hors_service'].includes(v.state) ? small('Prendre', () => takeModal(ctx, v), 'primary') : null, v.state !== 'hors_service' ? small('Réserver', () => bookModal(ctx, v)) : null); }]],
    fleet, v => vehicleView(ctx, v, d), 'Aucun véhicule dans le parc.'));
  } else if (tab === 'armurerie') {
    body = [myWeapons.length ? card('Mes armes', h('ul', { class: 'people' }, myWeapons.map(w => h('li', {}, h('span', { class: 'ev-ic' }, ico('bouclier', 16)), h('span', { class: 'art' }, h('b', {}, w.model), h('small', {}, w.serial + ' · ' + WCAT[w.category])))))) : null,
      card(null, table([['N° de série', w => h('b', { class: 'nowrap' }, w.serial)], ['Modèle', w => w.model], ['Catégorie', w => WCAT[w.category]], ['Statut', w => pill(...WST[w.status]), null, w => w.status],
        ['Détenteur', w => w.holder_id ? nameOf(members, w.holder_id) : '—'], ['Accès', w => gearNote(w, grades, quals, w.category === 'lourde')],
        ['', w => mgr ? h('span', { class: 'acts' }, w.status === 'armurerie' ? small('Remettre', () => issueModal(ctx, w, d), 'primary') : null,
          w.status === 'attribuee' ? small('Récupérer', ev => run(ev.currentTarget, call(ctx, 'return_weapon', { p_weapon: w.id, p_note: null }, 'Arme rendue à l’armurerie.'))) : null,
          small('Modifier', () => weaponEdit(ctx, w, d), 'ghost')) : null]], weapons, null, 'Armurerie vide.')),
      mgr ? card('Attributions récentes', table([['Arme', l => (weapons.find(w => w.id === l.weapon_id) || {}).serial || '—'], ['Agent', l => nameOf(members, l.member_id)], ['Remise', l => when(l.out_at) + ' · ' + l.out_by, 'num', l => l.out_at],
        ['Retour', l => l.back_at ? when(l.back_at) + ' · ' + l.back_by : pill('En cours', 'info')], ['Note', l => l.note || '—']], loans.slice(0, 30), null, 'Aucune attribution.')) : null];
  } else {
    body = [card(null, table([['Article', e => h('b', {}, e.name)], ['Type', e => EQK[e.kind]], ['Stock', e => h('span', { class: e.alert_below !== null && e.qty <= e.alert_below ? 'warn-t' : null }, e.qty + ' ' + e.unit), 'num', e => e.qty],
      ['', e => mgr ? h('span', { class: 'acts' }, small('Mouvement', () => moveModal(ctx, e, d), 'primary'), small('Modifier', () => equipEdit(ctx, e), 'ghost')) : null]], equipment, null, 'Aucun article.')),
      card('Derniers mouvements', table([['Article', x => (equipment.find(e => e.id === x.item_id) || {}).name || '—'], ['Quantité', x => (x.qty > 0 ? '+' : '') + x.qty, 'num', x => x.qty], ['Agent', x => x.member_id ? nameOf(members, x.member_id) : '—'],
        ['Par', x => x.by_name], ['Date', x => when(x.at), 'num', x => x.at], ['Note', x => x.note || '—']], moves, null, 'Aucun mouvement visible.'))];
  }
  ctx.show('logistique', 'Logistique', 'Parc automobile, armurerie et équipement', [
    mgr && tab === 'parc' ? btn('Ajouter un véhicule', () => vehicleEdit(ctx, null, d), 'primary', 'ajout') : null,
    mgr && tab === 'armurerie' ? btn('Entrer une arme', () => weaponEdit(ctx, null, d), 'primary', 'ajout') : null,
    mgr && tab === 'equipement' ? btn('Nouvel article', () => equipEdit(ctx, null), 'primary', 'ajout') : null],
  card(null, strip([['Véhicules libres', String(fleet.filter(v => !outOf(v) && !['reparation', 'hors_service'].includes(v.state)).length) + ' / ' + fleet.length], ['Véhicules abîmés', String(fleet.filter(v => v.state === 'abime').length), fleet.some(v => v.state === 'abime') ? 'warn-t' : null],
    ['Armes attribuées', String(weapons.filter(w => w.status === 'attribuee').length) + ' / ' + weapons.length], ['Stocks bas', String(low.length), low.length ? 'warn-t' : null]])),
  tabs([['parc', 'Parc automobile'], ['armurerie', 'Armurerie'], ['equipement', 'Équipement']], tab, setTab), body);
}

// ================================================================== statistiques
async function weekCanvas(ctx, st) {
  try { await Promise.all(['400', '600', '700'].map(w => document.fonts.load(w + ' 24px "Work Sans"'))); } catch (e) { /* police de secours */ }
  const img = await seal(), W = 1200, H = 675, c = document.createElement('canvas'); c.width = W; c.height = H;
  const g = c.getContext('2d'), sans = '"Work Sans", "Helvetica Neue", Arial, sans-serif', navy = '#1b2a4e', gold = '#b08d3c', ink = '#16213a', muted = '#5d6577';
  const last = st.series[st.series.length - 1] || {}, best = st.week_best && st.week_best.score > 0 ? st.week_best : null;
  g.fillStyle = '#ffffff'; g.fillRect(0, 0, W, H);
  g.fillStyle = navy; g.fillRect(0, 0, W, 132);
  if (img) g.drawImage(img, 40, 16, 100, 100);
  g.fillStyle = '#ffffff'; g.font = '700 38px ' + sans; g.fillText('Bilan de la semaine', 164, 64);
  g.font = '400 22px ' + sans; g.fillStyle = '#c9d3ea'; g.fillText(ctx.me.name + ' · semaine du ' + day(last.week || Date.now()), 164, 100);
  g.fillStyle = gold; g.fillRect(0, 132, W, 5);
  const tiles = [['Arrestations', last.arrests || 0], ['Heures de service', Math.round(last.hours || 0)], ['Rapports', last.reports || 0], ['Sorties SOG', last.sog || 0], ['Transferts', last.transfers || 0], ['Incidents', last.incidents || 0]];
  tiles.forEach(([l, v], i) => { const x = 40 + (i % 3) * 380, y = 172 + Math.floor(i / 3) * 150;
    g.fillStyle = '#f2f4f8'; g.fillRect(x, y, 360, 130); g.fillStyle = navy; g.font = '700 54px ' + sans; g.fillText(String(v), x + 24, y + 74); g.fillStyle = muted; g.font = '500 20px ' + sans; g.fillText(l, x + 24, y + 108); });
  g.fillStyle = ink; g.font = '700 22px ' + sans; g.fillText('Agent de la semaine', 40, 512);
  g.font = '600 30px ' + sans; g.fillStyle = navy; g.fillText(best ? best.name + (best.callsign ? ' · ' + best.callsign : '') : 'Personne cette semaine', 40, 552);
  if (best) { g.font = '400 20px ' + sans; g.fillStyle = muted; g.fillText(best.arrests + ' arrestation(s) · ' + best.hours + ' h de service · ' + best.reports + ' rapport(s)', 40, 584); }
  g.fillStyle = ink; g.font = '700 22px ' + sans; g.fillText('Classement', 640, 512);
  st.agents.filter(a => a.score > 0).slice(0, 4).forEach((a, i) => { g.font = '500 20px ' + sans; g.fillStyle = i ? ink : gold; g.fillText((i + 1) + '. ' + a.name, 640, 548 + i * 30); g.fillStyle = muted; g.textAlign = 'right'; g.fillText(a.score + ' pts', W - 40, 548 + i * 30); g.textAlign = 'left'; });
  g.fillStyle = muted; g.font = '400 16px ' + sans; g.fillText('Généré par Windy Desk le ' + new Date().toLocaleString('fr-FR'), 40, H - 24);
  return c;
}

export async function statistiques(ctx) {
  const { me } = ctx;
  const weeks = [4, 12, 26].includes(S.filters.stw) ? S.filters.stw : 4;
  const st = await rpc('mdt_stats', { p_org: me.id, p_weeks: weeks });
  const sum = k => st.series.reduce((a, w) => a + Number(w[k] || 0), 0);
  const units = Object.values(st.agents.reduce((acc, a) => { const k = a.unit || 'Sans unité'; const u = acc[k] || (acc[k] = { unit: k, agents: 0, arrests: 0, hours: 0, reports: 0 });
    u.agents++; u.arrests += a.arrests; u.hours += Number(a.hours); u.reports += a.reports; return acc; }, {}));
  const cs = st.cases, rate = cs.total ? Math.round(100 * cs.resolved / cs.total) : 0;
  const bestCard = (label, a) => card(label, a && a.score > 0 ? h('div', { class: 'bestagent' }, h('span', { class: 'rank' }, '1'), h('span', { class: 'art' }, h('b', {}, a.name + (a.callsign ? ' · ' + a.callsign : '')),
    h('small', {}, a.arrests + ' arrestation(s) · ' + a.hours + ' h · ' + a.reports + ' rapport(s) · ' + a.score + ' pts'))) : h('div', { class: 'empty' }, 'Personne pour l’instant.'));
  const RLATE = { brouillon: ['Brouillon depuis 48 h', 'warn'], soumis: ['À valider depuis 48 h', 'warn'], a_corriger: ['Refusé, à corriger', 'bad'] };
  ctx.show('statistiques', 'Statistiques', 'Les ' + weeks + ' dernières semaines', btn('Bilan de la semaine', () => exportModal({ kind: 'BILAN', num: day(Date.now()), title: 'Bilan de la semaine', render: () => weekCanvas(ctx, st) }, 'bilan-' + new Date().toISOString().slice(0, 10)), 'primary', 'telecharger'),
    fchips([[4, '4 semaines'], [12, '12 semaines'], [26, '6 mois']], weeks, k => { S.filters.stw = k; statistiques(ctx).catch(ctx.fail); }, 'Période'),
    card(null, strip([['Arrestations', String(sum('arrests'))], ['Heures de service', String(Math.round(sum('hours')))], ['Rapports', String(sum('reports'))], ['Sorties SOG', String(sum('sog'))],
      ['Transferts', String(sum('transfers'))], ['Incidents', String(sum('incidents')), sum('incidents') ? 'warn-t' : null]])),
    h('div', { class: 'grid cols2' },
      card('Arrestations par semaine', dailyBars(st.series.map((w, i) => ({ v: w.arrests, label: 'Semaine du ' + day(w.week), today: i === st.series.length - 1 })), v => v + ' arrestation(s)')),
      card('Heures de service par semaine', dailyBars(st.series.map((w, i) => ({ v: Number(w.hours), label: 'Semaine du ' + day(w.week), today: i === st.series.length - 1 })), v => Math.round(v) + ' h'))),
    h('div', { class: 'grid cols2' }, bestCard('Agent de la semaine', st.week_best), bestCard('Agent du mois', st.month_best)),
    h('div', { class: 'grid cols2' },
      card('Dossiers fugitifs', gauge('Taux de résolution', rate, 'dossier'), strip([['Ouverts', String(cs.open)], ['Résolus', String(cs.resolved)], ['Abandonnés', String(cs.abandoned)], ['Résolus sur la période', String(cs.resolved_period)]])),
      card(['Rapports en retard ou refusés', h('span', { class: 'muted small' }, String(st.late.length))], table([['N°', r => h('b', { class: 'nowrap' }, r.num)], ['Auteur', r => r.author], ['État', r => pill(...RLATE[r.status])],
        ['Depuis', r => ago(ms(r.since)), 'num', r => r.since]], st.late, r => r.title ? ctx.go(base(ctx) + '/rapports/' + r.id) : null, 'Aucun rapport en retard.'))),
    card('Par agent', table([['Agent', a => h('span', { class: 'art' }, h('b', {}, a.name), h('small', {}, [a.callsign, a.unit].filter(Boolean).join(' · ')))], ['Arrestations', a => String(a.arrests), 'num', a => a.arrests],
      ['Heures', a => String(a.hours), 'num', a => Number(a.hours)], ['Rapports', a => String(a.reports), 'num', a => a.reports], ['Refusés', a => a.refused ? h('span', { class: 'warn-t' }, String(a.refused)) : '0', 'num', a => a.refused],
      ['Dossiers résolus', a => String(a.cases_closed), 'num', a => a.cases_closed], ['SOG', a => String(a.sog), 'num', a => a.sog], ['Score', a => h('b', {}, String(a.score)), 'num', a => a.score]], st.agents, a => ctx.go(base(ctx) + '/effectifs/' + a.id), 'Aucun agent.')),
    card('Par unité', table([['Unité', u => h('b', {}, u.unit)], ['Agents', u => String(u.agents), 'num', u => u.agents], ['Arrestations', u => String(u.arrests), 'num', u => u.arrests], ['Heures', u => String(Math.round(u.hours)), 'num', u => u.hours],
      ['Rapports', u => String(u.reports), 'num', u => u.reports]], units, null, 'Aucune unité.')),
    h('p', { class: 'note' }, 'Score : 3 points par arrestation, 2 par dossier résolu, 1 par rapport soumis et 1 par heure de service. Le bilan s’exporte en image ou en PDF ; sa publication automatique sur Discord arrive avec l’intégration.'));
}

// ================================================================== carte
function markModal(ctx, x, y, d, mk) {
  const v = mk || {};
  const kind = select([['position', 'Dernière position connue'], ['interpellation', 'Lieu d’interpellation'], ['territoire', 'Territoire d’un gang'], ['lieu', 'Lieu fréquenté, autre']], v.kind || 'position', { 'aria-label': 'Type de repère' });
  const label = input({ maxlength: 80, value: v.label || '', placeholder: 'Ex. Garage de Grove Street', 'aria-label': 'Nom du repère' });
  const per = select([['', 'Aucun']].concat(d.people.map(p => [p.id, fullName(p) + (p.alias ? ' « ' + p.alias + ' »' : '')])), v.person_id || '', { 'aria-label': 'Individu' });
  const gang = select([['', 'Aucune']].concat(d.gangs.map(gg => [gg.id, gg.name])), v.gang_id || '', { 'aria-label': 'Organisation' });
  const cas = select([['', 'Aucun']].concat(d.cases.map(c => [c.id, c.num + ' · ' + c.title])), v.case_id || '', { 'aria-label': 'Dossier' });
  const radius = h('input', { type: 'range', min: 1, max: 20, value: Math.round(Number(v.radius || 0.04) * 100), 'aria-label': 'Étendue' }), radF = field('Étendue du territoire', radius);
  const note = input({ maxlength: 300, value: v.note || '', 'aria-label': 'Note' }), ok = btn(mk ? 'Enregistrer' : 'Placer le repère', null, 'primary');
  const sync = () => { radF.hidden = kind.value !== 'territoire'; }; kind.addEventListener('change', sync); sync();
  const md = modal(mk ? mk.label : 'Nouveau repère', field('Type', kind), field('Nom', label), h('div', { class: 'row' }, field('Individu', per, 'grow'), field('Organisation', gang, 'grow')), field('Dossier', cas), radF, field('Note', note), foot({ close: () => md.close() }, ok));
  ok.addEventListener('click', () => run(ok, async () => {
    await rpc('save_mark', { p_org: ctx.me.id, p_id: mk ? mk.id : null, p: { kind: kind.value, label: label.value, x, y, person_id: per.value, gang_id: gang.value, case_id: cas.value, radius: Number(radius.value) / 100, note: note.value } });
    md.close(); ctx.reload(); return { message: 'Repère enregistré.' };
  }));
}
function markView(ctx, mk, d) {
  const p = d.people.find(x => x.id === mk.person_id), gg = d.gangs.find(x => x.id === mk.gang_id), c = d.cases.find(x => x.id === mk.case_id);
  const mine = mk.by_member_id === ctx.me.member_id || ctx.can('dossiers');
  const md = modal(mk.label, h('div', { class: 'pills' }, pill(MARK[mk.kind], 'info')),
    h('dl', { class: 'lines-dl rules' }, kv('Individu', p ? h('a', { href: base(ctx) + '/individus/' + p.id }, fullName(p)) : null), kv('Organisation', gg ? h('a', { href: base(ctx) + '/organisations/' + gg.id }, gg.name) : null),
      kv('Dossier', c ? h('a', { href: base(ctx) + '/dossiers/' + c.id }, c.num + ' · ' + c.title) : null), kv('Note', mk.note), kv('Placé', when(mk.at) + ' · ' + mk.by_name)),
    h('div', { class: 'foot' }, mine ? btn('Retirer', ev => run(ev.currentTarget, async () => { await rpc('delete_mark', { p_id: mk.id }); md.close(); ctx.reload(); return { message: 'Repère retiré.' }; }), 'danger') : null,
      mine ? btn('Modifier', () => { md.close(); markModal(ctx, Number(mk.x), Number(mk.y), d, mk); }) : null, btn('Fermer', () => md.close())));
}

export async function carte(ctx) {
  const { me } = ctx;
  const [org, marks, people, gangs, cases, safe, meta] = await Promise.all([rows('orgs', q => q.eq('id', me.id)), rows('map_marks', q => q.eq('org_id', me.id).order('at', { ascending: false })), load.people(ctx),
    rows('gangs', q => q.eq('org_id', me.id).order('name')), rows('cases', q => q.eq('org_id', me.id).order('opened_at', { ascending: false })), me.witsec ? rows('safehouses', q => q.eq('org_id', me.id).order('code')) : [], tilesMeta()]);
  const mapPath = org[0] && org[0].map_path, urls = mapPath && !meta ? await signedUrls([mapPath]) : {};
  const d = { people, gangs, cases };
  const layers = S.filters.mapL || (S.filters.mapL = { position: true, interpellation: true, territoire: true, lieu: true, planque: true });
  const fugitives = !!S.filters.mapF;
  const hunted = new Set(cases.filter(c => OPEN.includes(c.status)).map(c => c.person_id));
  const shown = marks.filter(m => layers[m.kind] && (!fugitives || m.kind !== 'position' || hunted.has(m.person_id)));
  let placing = null;   // 'mark' ou l'id d'une planque à placer
  let mp = null;        // { L, map } une fois la carte montée
  const place = (nx, ny) => {
    const what = placing; setPlacing(null);
    if (what === 'mark') markModal(ctx, nx, ny, d, null);
    else rpc('place_safehouse', { p_id: what, p_x: nx, p_y: ny }).then(() => { toast('Planque placée.'); ctx.reload(); }, ctx.fail);
  };
  const hint = h('div', { class: 'maphint', hidden: true, role: 'status' });
  const hintText = h('span');
  const center = btn('Placer au centre de la vue', () => { if (placing && mp) { const c = mp.map.getCenter(); place(...carteVersRepere([c.lat, c.lng])); } });
  hint.append(hintText, ' ', center);
  const setPlacing = (v, text) => { placing = v; box.classList.toggle('placing', !!v); hint.hidden = !v; hintText.textContent = text || ''; };
  const box = h('div', { class: 'mapbox', role: 'region', 'aria-label': 'Carte de la ville : glisser pour déplacer, molette ou boutons pour zoomer, flèches du clavier pour se déplacer' });
  const toggle = (k, label, n) => h('label', { class: 'check chip-check mk-' + k }, h('input', { type: 'checkbox', checked: !!layers[k], onchange: ev => { layers[k] = ev.target.checked; carte(ctx).catch(ctx.fail); } }), h('i', { class: 'dot' }), label + ' (' + n + ')');
  const unplaced = safe.filter(x => x.map_x === null);
  ctx.show('carte', 'Carte', marks.length + ' repère(s)' + (fugitives ? ' · fugitifs actifs seulement' : ''), [
    btn('Ajouter un repère', () => setPlacing('mark', 'Clique sur la carte à l’endroit du repère. Échap pour annuler.'), 'primary', 'localisation')],
    h('div', { class: 'maplayers' }, ['position', 'interpellation', 'territoire', 'lieu'].map(k => toggle(k, MARK[k], marks.filter(m => m.kind === k).length)),
      me.witsec ? toggle('planque', MARK.planque, safe.filter(x => x.map_x !== null).length) : null,
      h('label', { class: 'check chip-check' }, h('input', { type: 'checkbox', checked: fugitives, onchange: ev => { S.filters.mapF = ev.target.checked; carte(ctx).catch(ctx.fail); } }), 'Fugitifs actifs seulement')),
    hint, h('div', { class: 'mapwrap' }, box),
    me.witsec && unplaced.length ? card('Planques WITSEC à placer', h('ul', { class: 'people' }, unplaced.map(x => h('li', {}, h('span', { class: 'ev-ic' }, ico('cadenas', 16)), h('span', { class: 'art grow' }, h('b', {}, x.code), h('small', {}, x.location_code)),
      small('Placer', () => setPlacing(x.id, 'Clique sur la carte pour placer la planque ' + x.code + '.')))))) : null,
    card('Repères', table([['Repère', m => h('span', { class: 'art' }, h('b', {}, m.label), h('small', {}, MARK[m.kind]))], ['Lié à', m => { const p = people.find(x => x.id === m.person_id), gg = gangs.find(x => x.id === m.gang_id); return [p ? fullName(p) : null, gg ? gg.name : null].filter(Boolean).join(' · ') || '—'; }],
      ['Placé', m => when(m.at) + ' · ' + m.by_name, 'num', m => m.at]], shown, m => markView(ctx, m, d), 'Aucun repère pour l’instant.')));
  // La carte se monte une fois la page affichée (Leaflet a besoin de la taille réelle du conteneur).
  if (S.map) { S.map.remove(); S.map = null; }
  mp = await createMap(box, { meta, imageUrl: mapPath && urls[mapPath], view: S.filters.mapView });
  const { L, map } = mp; S.map = map;
  if (mp.fond === 'aucun') box.append(h('div', { class: 'mapblank' }, h('span', {}, ico('localisation', 20), ' Aucune carte disponible : le Director envoie une image dans Réglages. Les repères se placent déjà sur cette grille.')));
  map.on('moveend', () => { S.filters.mapView = { c: [map.getCenter().lat, map.getCenter().lng], z: map.getZoom() }; });
  map.on('click', ev => { if (placing) place(...carteVersRepere([ev.latlng.lat, ev.latlng.lng])); });
  const open = (el, label, fn) => { el.setAttribute('tabindex', '0'); el.setAttribute('role', 'button'); el.setAttribute('aria-label', label);
    el.addEventListener('keydown', ev => { if (ev.key === 'Enter' || ev.key === ' ') { ev.preventDefault(); fn(); } }); };
  const pin = (x, y, cls, label, fn) => L.marker(imageVersCarte([x, y]), { icon: L.divIcon({ className: 'pin ' + cls, iconSize: [18, 18] }), title: label, keyboard: true, riseOnHover: true })
    .on('add', ev => ev.target.getElement().setAttribute('aria-label', label)).on('click', ev => { L.DomEvent.stopPropagation(ev); if (!placing) fn(); }).addTo(map);
  shown.filter(m => m.kind === 'territoire').forEach(m => {
    const c = L.circle(imageVersCarte([Number(m.x), Number(m.y)]), { radius: Number(m.radius || 0.04) * SIDE, className: 'zone-svg', interactive: true, bubblingMouseEvents: false }).addTo(map);
    c.on('click', () => { if (!placing) markView(ctx, m, d); }); c.bindTooltip(m.label); open(c.getElement(), 'Territoire : ' + m.label, () => markView(ctx, m, d));
  });
  shown.filter(m => m.kind !== 'territoire').forEach(m => pin(Number(m.x), Number(m.y), 'mk-' + m.kind + (m.kind === 'position' && hunted.has(m.person_id) ? ' hunted' : ''), MARK[m.kind] + ' : ' + m.label, () => markView(ctx, m, d)));
  if (layers.planque) safe.filter(x => x.map_x !== null).forEach(x => pin(Number(x.map_x), Number(x.map_y), 'mk-planque', 'Planque WITSEC ' + x.code, () => {
    const md = modal('Planque ' + x.code, h('p', {}, x.location_code), h('p', { class: 'muted small' }, 'Capacité ' + x.capacity + (x.available ? ' · disponible' : ' · occupée')),
      h('div', { class: 'foot' }, btn('Déplacer', () => { md.close(); setPlacing(x.id, 'Clique sur la carte pour placer la planque ' + x.code + '.'); }), btn('Fermer', () => md.close()))); }));
  const esc = ev => { if (ev.key === 'Escape' && placing) setPlacing(null); };
  document.addEventListener('keydown', esc);
  S.mapEsc && document.removeEventListener('keydown', S.mapEsc); S.mapEsc = esc;
}

// ================================================================== réglages du service : identité, modèles, Affaires internes, sauvegarde
export async function adminCards(ctx, grades) {
  const { me } = ctx;
  const [org, templates, members] = await Promise.all([rows('orgs', q => q.eq('id', me.id)), rows('doc_templates', q => q.eq('org_id', me.id)), load.members(ctx)]);
  const o = org[0] || {}, top = isTop(ctx, grades), mapUrls = o.map_path ? await signedUrls([o.map_path]) : {};
  const logoIn = h('input', { type: 'file', accept: 'image/png,image/jpeg,image/webp', 'aria-label': 'Logo du service' }), mapIn = h('input', { type: 'file', accept: 'image/png,image/jpeg,image/webp', 'aria-label': 'Carte de la ville' });
  const sendImg = async (input2, bucket, folder, max) => {
    const f = input2.files[0];
    if (!f) throw new Error('Choisis une image.');
    if (!IMAGE.test(f.type) || f.type === 'image/gif') throw new Error('Choisis une image PNG, JPEG ou WebP.');
    if (f.size > max * 1024 * 1024) throw new Error('Image trop lourde : ' + max + ' Mo au plus.');
    return upload(bucket, me.id + '/' + folder + '/' + stamp() + '.' + extOf(f), f);
  };
  const recruit = h('input', { type: 'checkbox', class: 'toggle', checked: o.recruiting !== false, 'aria-label': 'Recrutement ouvert' });
  recruit.addEventListener('change', async () => { try { await rpc('save_service_settings', { p_org: me.id, p: { recruiting: recruit.checked } }); toast(recruit.checked ? 'Recrutement ouvert.' : 'Recrutement fermé.'); } catch (e) { recruit.checked = !recruit.checked; ctx.fail(e); } });
  // modèles de documents
  const kindSel = select(Object.entries(TPL), S.filters.tpl || 'candidature', { 'aria-label': 'Modèle' }), editor = h('div', { class: 'stack' });
  const paintTpl = () => {
    const k = kindSel.value, cur = (templates.find(t => t.kind === k) || {}).body;
    S.filters.tpl = k;
    if (k === 'candidature') {
      const list = h('div', { class: 'linelist' });
      const addQ = (q, long) => { const qi = input({ maxlength: 200, value: q || '', placeholder: 'Question', 'aria-label': 'Question' }), lg = h('input', { type: 'checkbox', checked: !!long, 'aria-label': 'Réponse longue' });
        const row = h('div', { class: 'll-row' }, qi, h('label', { class: 'check' }, lg, 'longue'), h('button', { class: 'btn small ghost', type: 'button', 'aria-label': 'Retirer', onclick: () => row.remove() }, ico('moins', 16))); row._get = () => ({ q: qi.value.trim(), long: lg.checked }); list.append(row); };
      ((cur && cur.questions) || DEFAULT_Q).forEach(x => addQ(x.q, x.long));
      editor.replaceChildren(h('p', { class: 'muted small' }, 'Les questions du formulaire public de candidature. Le nom, le Discord et un contact sont toujours demandés.'), list,
        h('div', { class: 'acts' }, small('Ajouter une question', () => addQ('', false)), h('button', { class: 'btn primary', type: 'button', onclick: ev => run(ev.currentTarget, async () => {
          await rpc('save_template', { p_org: me.id, p_kind: k, p_body: { questions: [...list.children].map(r => r._get()).filter(x => x.q) } }); ctx.reload(); return { message: 'Formulaire enregistré.' }; }) }, 'Enregistrer')));
    } else {
      const t = area({ maxlength: 4000, rows: 8, value: (cur && cur.text) || '', placeholder: k === 'promotion' ? 'Texte de départ d’une proposition de promotion' : 'Trame du récit, reprise à chaque nouveau rapport de ce type', 'aria-label': 'Texte du modèle' });
      editor.replaceChildren(t, h('div', { class: 'acts' }, h('button', { class: 'btn primary', type: 'button', onclick: ev => run(ev.currentTarget, async () => { await rpc('save_template', { p_org: me.id, p_kind: k, p_body: { text: t.value } }); ctx.reload(); return { message: 'Modèle enregistré.' }; }) }, 'Enregistrer')));
    }
  };
  kindSel.addEventListener('change', paintTpl); paintTpl();
  const pub = p => h('a', { href: p, target: '_blank', rel: 'noopener' }, location.host + p);
  return [
    h('div', { class: 'grid cols2' },
      card('Identité du service', h('div', { class: 'row' }, o.logo_path ? h('img', { class: 'logoprev', src: publicUrl(o.logo_path), alt: 'Logo actuel' }) : h('span', { class: 'logoprev empty' }, ico('bouclier', 28)),
        h('div', { class: 'stack grow' }, field('Logo (affiché sur la page publique)', logoIn), h('div', { class: 'acts' }, h('button', { class: 'btn', type: 'button', onclick: ev => run(ev.currentTarget, async () => {
          await rpc('save_service_settings', { p_org: me.id, p: { logo_path: await sendImg(logoIn, 'avis', 'logo', 5) } }); ctx.reload(); return { message: 'Logo enregistré.' }; }) }, 'Envoyer le logo')))),
        h('div', { class: 'stack' }, h('label', { class: 'check' }, recruit, 'Recrutement ouvert (formulaire public de candidature)'),
          h('dl', { class: 'lines-dl rules' }, kv('Avis de recherche', pub('/avis/' + me.code)), kv('Candidature', pub('/avis/' + me.code + '/candidature')), kv('Plainte', pub('/avis/' + me.code + '/plainte'))))),
      card('Carte de la ville', o.map_path && mapUrls[o.map_path] ? h('img', { class: 'mapprev', src: mapUrls[o.map_path], alt: 'Carte actuelle' }) : h('p', { class: 'muted' }, 'Aucune carte : envoie l’image de la carte utilisée sur ton serveur (PNG, JPEG ou WebP, 50 Mo au plus).'),
        field('Image de la carte', mapIn), h('div', { class: 'acts' }, h('button', { class: 'btn', type: 'button', onclick: ev => run(ev.currentTarget, async () => {
          await rpc('save_service_settings', { p_org: me.id, p: { map_path: await sendImg(mapIn, 'mdt', 'carte', 50) } }); ctx.reload(); return { message: 'Carte enregistrée. Les repères gardent leur place relative.' }; }) }, 'Envoyer la carte')))),
    card(['Modèles de documents', h('span', { class: 'right' }, h('a', { href: base(ctx) + '/rapports', onclick: () => { S.filters.rap = 'code'; } }, 'Code pénal'))], field('Modèle', kindSel), editor),
    top ? h('div', { class: 'grid cols2' },
      card('Affaires internes', h('p', { class: 'muted' }, 'Personne désignée : ' + (o.ia_member_id ? nameOf(members, o.ia_member_id) : 'aucune (le Director seul, et les grades qui ont l’accès)')),
        h('div', { class: 'acts' }, btn('Changer', () => officerModal(ctx, members, o.ia_member_id)))),
      card('Sauvegarde', h('p', { class: 'muted' }, 'Télécharge toutes les données du service dans un fichier (JSON) à garder en lieu sûr. Les vrais noms des témoins WITSEC n’y figurent pas. La base est aussi sauvegardée chaque jour par Supabase.'),
        h('div', { class: 'acts' }, h('button', { class: 'btn', type: 'button', onclick: ev => run(ev.currentTarget, async () => {
          const data = await rpc('export_service', { p_org: me.id });
          saveBlob(new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' }), 'sauvegarde-' + me.code + '-' + new Date().toISOString().slice(0, 10) + '.json');
          return { message: 'Sauvegarde téléchargée.' };
        }) }, ico('telecharger', 18), 'Exporter les données')))) : null];
}
const DEFAULT_Q = [{ q: 'Âge et origine de ton personnage' }, { q: 'Ton expérience (police, armée, sécurité, autre service)', long: true }, { q: 'Pourquoi veux-tu rejoindre les US Marshals ?', long: true },
  { q: 'Tes disponibilités (jours et heures)' }, { q: 'As-tu lu le règlement du service et du serveur ?' }, { q: 'Ton âge réel (hors RP)' }];

// ================================================================== fiche agent : formation, casier, promotion, plainte, sanction
export async function agentExtras(ctx, m, d) {
  const { me, can, has } = ctx;
  const self = m.id === me.member_id;
  const [modules, progress, evals, discipline, templates, myQuals] = await Promise.all([has('academie') ? rows('training_modules', q => q.eq('org_id', me.id).order('sort')) : [],
    has('academie') ? rows('training_progress', q => q.eq('member_id', m.id)) : [], has('academie') ? rows('field_evals', q => q.eq('member_id', m.id).order('at', { ascending: false })) : [],
    has('affaires') ? rows('discipline', q => q.eq('member_id', m.id).order('at', { ascending: false })) : [], has('academie') ? rows('doc_templates', q => q.eq('org_id', me.id)) : [],
    has('academie') ? rows('member_qualifications', q => q.eq('member_id', me.member_id)) : []]);
  const fto = can('academie') || d.quals.some(q => q.name.toUpperCase() === 'FTO' && myQuals.some(x => x.qualification_id === q.id));
  const live = modules.filter(x => !x.archived), training = has('academie') && m.active && (m.stage !== 'titulaire' || progress.length || m.fto_id);
  const actions = [
    has('academie') && m.active && !self && (can('effectifs') || can('valider') || can('academie')) ? btn('Proposer une promotion', () => proposeModal(ctx, d.members, d.grades, templates, m), null, 'etoile') : null,
    has('affaires') && m.active && !self ? btn('Porter plainte', () => complaintModal(ctx, m), 'ghost') : null,
    me.ia && m.active && !self ? btn('Sanctionner', () => sanctionModal(ctx, d.members, m, null), 'danger') : null,
    me.ia && suspended(m) ? btn('Lever la suspension', ev => run(ev.currentTarget, call(ctx, 'lift_suspension', { p_member: m.id }, 'Suspension levée.'))) : null];
  const cards = [
    training ? card(['Formation', h('span', { class: 'right' }, h('a', { href: base(ctx) + '/academie' }, 'Académie'))], gauge('Progression du parcours', progressOf(m, live, progress), 'journal'),
      h('p', { class: 'muted small' }, 'Formateur : ' + (m.fto_id ? nameOf(d.members, m.fto_id) : 'pas encore attribué')),
      h('ul', { class: 'quals' }, live.map(x => { const got = progress.find(p => p.module_id === x.id); return h('li', { class: got ? 'got' : null }, h('span', { class: 'q-ic' }, ico(got ? 'coche' : 'moins', 16)), h('span', { class: 'art' }, h('b', {}, x.name), h('small', {}, got ? 'Validé le ' + day(got.validated_at) + ' · ' + got.by_name : 'À valider'))); })),
      evals.length ? [h('h3', { class: 'sub-h' }, 'Évaluations terrain'), h('ul', { class: 'people' }, evals.slice(0, 5).map(e => h('li', {}, h('span', { class: 'art' }, h('b', {}, stars(e.overall), ' ', e.by_name), h('small', {}, day(e.at)), e.comment ? h('span', { class: 'prewrap' }, e.comment) : null))))] : null,
      fto && !self ? h('div', { class: 'acts' }, small('Évaluer sur le terrain', () => evalModal(ctx, m))) : null) : null,
    discipline.length ? card(['Casier disciplinaire', h('span', { class: 'muted small' }, discipline.length + ' sanction(s)')], h('ul', { class: 'people' }, discipline.map(x => h('li', {}, h('span', { class: 'ev-ic' }, ico('alerte', 16)),
      h('span', { class: 'art grow' }, h('b', {}, SANCTION[x.kind][0] + (x.until ? ' jusqu’au ' + when(x.until) : '') + (x.lifted_at ? ' (levée)' : '')), h('small', {}, x.reason + ' · ' + day(x.at) + ' · ' + x.by_name)))))) : null];
  const banner = suspended(m) ? h('div', { class: 'banner bad' }, ico('cadenas', 20), h('div', {}, h('b', {}, 'Suspendu jusqu’au ' + when(m.suspended_until)), h('span', {}, 'Aucun accès au MDT pendant la suspension.'))) : null;
  return { actions, cards, banner };
}

// ================================================================== formulaires publics (sans connexion) : candidature et plainte
export function publicApply(info) {
  const name = input({ maxlength: 60, autocomplete: 'off', 'aria-label': 'Nom de ton personnage' }), disc = input({ maxlength: 25, inputmode: 'numeric', placeholder: 'Ex. 123456789012345678', 'aria-label': 'Identifiant Discord' });
  const contact = input({ maxlength: 80, placeholder: 'Pseudo Discord, numéro en jeu…', 'aria-label': 'Contact' });
  const qs = (info.questions || []).map(q => [q.q, q.long ? area({ maxlength: 2000, rows: 4, 'aria-label': q.q }) : input({ maxlength: 2000, 'aria-label': q.q })]);
  const ok = h('button', { class: 'btn primary wide' }, 'Envoyer ma candidature'), done = h('p', { class: 'sent', hidden: true, role: 'status' });
  ok.addEventListener('click', () => run(ok, async () => {
    const num = await rpc('send_application', { p_code: info.code, p_name: name.value.trim(), p_discord: disc.value.trim() || null, p_contact: contact.value.trim() || null, p_answers: qs.map(([q, el]) => ({ q, a: el.value.trim() })) });
    [name, disc, contact, ...qs.map(([, el]) => el)].forEach(el => { el.value = ''; });
    done.textContent = 'Candidature ' + num + ' envoyée. Le service te recontacte sur Discord pour un entretien.'; done.hidden = false; return { message: 'Candidature envoyée, merci.' };
  }));
  return h('section', { class: 'card avis-form' }, h('h2', {}, 'Candidater'), h('p', { class: 'muted' }, 'Réponds sérieusement : chaque candidature est lue par le service. Une seule candidature en cours par compte Discord.'),
    field('Nom de ton personnage', name), h('div', { class: 'row' }, field('Identifiant Discord', disc, 'grow'), field('Contact', contact, 'grow')), qs.map(([q, el]) => field(q, el)), ok, done);
}
export function publicComplain(info) {
  const who = input({ maxlength: 120, placeholder: 'Nom, matricule, indicatif ou description', 'aria-label': 'Agent concerné' }), happened = input({ maxlength: 120, placeholder: 'Ex. Hier vers 22 h, Vinewood', 'aria-label': 'Quand et où' });
  const facts = area({ maxlength: 3000, rows: 6, placeholder: 'Ce qui s’est passé, précisément', 'aria-label': 'Les faits' });
  const me = input({ maxlength: 80, placeholder: 'Facultatif', 'aria-label': 'Ton nom' }), contact = input({ maxlength: 80, placeholder: 'Facultatif : pour être recontacté', 'aria-label': 'Contact' });
  const ok = h('button', { class: 'btn primary wide' }, 'Déposer la plainte'), done = h('p', { class: 'sent', hidden: true, role: 'status' });
  ok.addEventListener('click', () => run(ok, async () => {
    const num = await rpc('send_complaint', { p_code: info.code, p: { against: who.value, happened: happened.value, facts: facts.value, complainant: me.value, contact: contact.value } });
    [who, happened, facts, me, contact].forEach(el => { el.value = ''; });
    done.textContent = 'Plainte ' + num + ' transmise aux Affaires internes. Garde ce numéro.'; done.hidden = false; return { message: 'Plainte déposée.' };
  }));
  return h('section', { class: 'card avis-form' }, h('h2', {}, 'Porter plainte contre un agent'), h('p', { class: 'muted' }, 'Ta plainte est lue par les Affaires internes du service, et par elles seules. Tu peux rester anonyme.'),
    field('Agent concerné', who), field('Quand et où', happened), field('Les faits', facts), h('div', { class: 'row' }, field('Ton nom', me, 'grow'), field('Contact', contact, 'grow')), ok, done);
}
