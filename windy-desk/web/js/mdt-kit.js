// ================================================================== Windy Desk · outils partagés du MDT (services publics)
// Petites briques d'interface, libellés, fichiers et lectures communes aux pages du MDT (mdt.js, mdt-enquete.js).
import { h, ico, pill, liveDot, initials, date } from './ui.js';
import { rows, upload } from './sb.js';

export const DAY = 864e5, HOUR = 36e5;
export const ms = s => new Date(s).getTime();
export const iso = t => new Date(t).toISOString();
export const day = s => new Date(s).toLocaleDateString('fr-FR');
export const field = (label, ctl, cls) => h('label', { class: 'f' + (cls ? ' ' + cls : '') }, label, ctl);
export const input = attrs => h('input', Object.assign({ type: 'text' }, attrs));
export const area = attrs => h('textarea', attrs);
export const select = (opts, value, attrs) => h('select', attrs || {}, opts.map(([v, l]) => h('option', { value: v, selected: v === value }, l)));
export const foot = (m, ...btns) => h('div', { class: 'foot' }, h('button', { class: 'btn', type: 'button', onclick: () => m.close() }, 'Annuler'), ...btns);
export const fullName = p => p ? p.first_name + ' ' + p.last_name : '—';
export const today = () => new Date(Date.now() - new Date().getTimezoneOffset() * 6e4).toISOString().slice(0, 10);
export const toLocal = t => new Date(t - new Date(t).getTimezoneOffset() * 6e4).toISOString().slice(0, 16);
export const weekStart = () => { const d = new Date(); d.setHours(0, 0, 0, 0); d.setDate(d.getDate() - ((d.getDay() + 6) % 7)); return d.getTime(); };
// heures de service depuis « from » (12 h au plus par service, contre les oublis de fin de service)
export const hours = (list, from) => list.reduce((a, x) => { const t0 = Math.max(ms(x.started_at), from), t1 = x.ended_at ? ms(x.ended_at) : Date.now(); return a + Math.max(0, Math.min(t1 - t0, 12 * HOUR)); }, 0) / HOUR;
export const fmtH = x => (Math.round(x * 10) / 10).toLocaleString('fr-FR') + ' h';
export const tabs = (list, current, onPick) => h('div', { class: 'tabs', role: 'tablist' }, list.filter(Boolean).map(([k, l]) => h('button', { type: 'button', role: 'tab', class: 'tab' + (k === current ? ' on' : ''), 'aria-selected': String(k === current), onclick: () => onPick(k) }, l)));
export const search = (value, placeholder, onInput) => { const i = input({ value: value || '', placeholder, 'aria-label': placeholder, class: 'find-in' }); i.addEventListener('input', () => onInput(i.value)); return h('div', { class: 'findbar' }, ico('loupe', 18), i); };
export const match = (q, ...parts) => !q || parts.filter(Boolean).join(' ').toLowerCase().includes(q.trim().toLowerCase());

export const STATUS = { ouvert: ['Ouvert', 'info'], en_cours: ['En cours', 'info'], localise: ['Localisé', 'warn'], arrete: ['Arrêté', 'ok'], rendu: ['S’est rendu', 'ok'], decede: ['Décédé', null], abandonne: ['Abandonné', null] };
export const OPEN = ['ouvert', 'en_cours', 'localise'];
export const PRIO = { normale: ['Normale', null], haute: ['Haute', 'warn'], most_wanted: ['Most Wanted', 'bad'] };
export const WKIND = { arret: 'Mandat d’arrêt', perquisition: 'Perquisition', ecoute: 'Mise sur écoute', saisie: 'Saisie' };
export const WST = { brouillon: ['Brouillon', null], envoye: ['En attente de la Justice', 'warn'], signe: ['Signé', 'ok'], refuse: ['Refusé', 'bad'], execute: ['Exécuté', 'info'], annule: ['Annulé', null], expire: ['Expiré', 'bad'] };
export const wStatus = w => w.status === 'signe' && w.expires_at && ms(w.expires_at) < Date.now() ? 'expire' : w.status;
export const DUTY = { dispo: ['Disponible', 'ok'], intervention: ['En intervention', 'bad'], pause: ['En pause', 'warn'] };
export const STAGE = { recrue: ['Recrue', 'warn'], formation: ['En formation', 'info'], titulaire: ['Titulaire', null] };
export const CAREER = { recrutement: ['Recrutement', 'info', 'badge'], promotion: ['Promotion', 'ok', 'etoile'], retrogradation: ['Rétrogradation', 'warn', 'fleche'], unite: ['Unités', null, 'grille'],
  qualification: ['Qualification', 'ok', 'coche'], formation: ['Parcours', 'info', 'journal'], felicitation: ['Félicitation', 'ok', 'etoile'], sanction: ['Sanction', 'bad', 'alerte'],
  note: ['Note', null, 'journal'], commandement: ['Note du commandement', 'warn', 'cadenas'], depart: ['Départ', 'bad', 'sortie'], retour: ['Retour', 'ok', 'fleche'] };
export const LOG = { note: ['Note', null, 'journal'], piste: ['Piste', 'info', 'loupe'], observation: ['Observation', 'warn', 'localisation'], statut: ['Dossier', 'info', 'dossier'],
  agent: ['Équipe', null, 'joueurs'], fichier: ['Pièce jointe', null, 'trombone'], mandat: ['Mandat', 'ok', 'marteau'], wanted: ['Most Wanted', 'bad', 'cible'] };
export const ANN = { info: ['Information', 'info'], important: ['Important', 'warn'], urgent: ['Urgent', 'bad'] };
export const AGENDA = { patrouille: 'Patrouille', operation: 'Opération', formation: 'Formation', reunion: 'Réunion', autre: 'Autre' };

// ---------------------------------------------------------------- fichiers (photos, pièces jointes)
export const IMAGE = /^image\/(png|jpeg|webp|gif)$/, ANY = /^(image\/(png|jpeg|webp|gif)|video\/(mp4|webm)|application\/pdf|text\/plain)$/;
export const stamp = () => Date.now().toString(36) + Math.random().toString(36).slice(2, 7);
export const extOf = f => (f.name.match(/\.([a-z0-9]{1,5})$/i) || [null, 'bin'])[1].toLowerCase();
export async function send(org, folder, file, imageOnly) {
  if (!file) return null;
  const max = imageOnly ? 10 : 50;
  if (file.size > max * 1024 * 1024) throw new Error('Fichier trop lourd : ' + max + ' Mo au plus.');
  if (!(imageOnly ? IMAGE : ANY).test(file.type)) throw new Error(imageOnly ? 'Choisis une image (PNG, JPEG, WebP ou GIF).' : 'Type refusé : images, vidéos MP4 ou WebM, PDF ou texte.');
  return upload('mdt', org + '/' + folder + '/' + stamp() + '.' + extOf(file), file);
}
export const photoIn = () => h('input', { type: 'file', accept: 'image/png,image/jpeg,image/webp,image/gif' });
/** Photo d'un agent ou d'un individu (lien signé, valable une heure), ou ses initiales. */
export const face = (path, urls, name, cls) => path && urls[path] ? h('img', { class: 'ava ' + (cls || ''), src: urls[path], alt: '' }) : h('span', { class: 'ava ' + (cls || ''), 'aria-hidden': 'true' }, initials(name));
export const personCell = (title, sub, path, urls) => h('span', { class: 'person' }, face(path, urls, title, 'sm'), h('span', { class: 'art' }, h('b', {}, title), sub ? h('small', {}, sub) : null));
export const feed = items => items.length ? h('ul', { class: 'feed tl' }, items.map(([at, kind, text, by, map]) => {
  const [label, cls, icon] = map[kind] || [kind, null, 'journal'];
  return h('li', { class: 'ev' + (cls ? ' ' + cls : '') }, h('time', {}, date(ms(at))), h('span', { class: 'ev-ic' }, ico(icon, 15)),
    h('div', { class: 'ev-body' }, h('b', {}, label), h('span', {}, text), by ? h('small', { class: 'muted' }, by) : null));
})) : h('div', { class: 'empty' }, 'Rien pour l’instant.');

// ---------------------------------------------------------------- données communes
export const load = {
  members: ctx => rows('members', q => q.eq('org_id', ctx.me.id).order('created_at')),
  grades: ctx => rows('grades', q => q.eq('org_id', ctx.me.id).order('rank', { ascending: false })),
  units: ctx => rows('units', q => q.eq('org_id', ctx.me.id).order('code')),
  people: ctx => rows('people', q => q.eq('org_id', ctx.me.id).order('last_name')),
};
export const gradeOf = (grades, id) => grades.find(g => g.id === id) || { name: '?', rank: 0, badge: null };
export const myRank = (ctx, members, grades) => gradeOf(grades, (members.find(m => m.id === ctx.me.member_id) || {}).grade_id).rank;
export const absentToday = (absences, id) => absences.some(a => a.member_id === id && a.status === 'validee' && a.starts_on <= today() && a.ends_on >= today());
/** Où en est l'agent : en service (et son statut), absent, compte à activer, inactif depuis 7 jours, hors service. */
export function stateOf(m, open, absences, shifts) {
  if (!m.active) return pill('Parti', 'bad');
  const o = open.find(x => x.member_id === m.id);
  if (o) return liveDot((DUTY[m.duty_status] || DUTY.dispo)[0]);
  if (absentToday(absences, m.id)) return pill('Absent', 'warn');
  if (!m.user_id) return pill('Compte à activer', 'warn');
  const last = Math.max(...shifts.filter(x => x.member_id === m.id).map(x => ms(x.started_at)), m.last_seen ? ms(m.last_seen) : 0, ms(m.created_at));
  if (Date.now() - last > 7 * DAY) return pill('Inactif depuis ' + Math.floor((Date.now() - last) / DAY) + ' j', 'bad');
  return h('span', { class: 'muted' }, 'Hors service');
}
export const inactiveDays = (m, shifts) => Math.floor((Date.now() - Math.max(...shifts.filter(x => x.member_id === m.id).map(x => ms(x.started_at)), m.last_seen ? ms(m.last_seen) : 0, ms(m.created_at))) / DAY);
