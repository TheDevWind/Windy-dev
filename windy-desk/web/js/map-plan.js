// Éditeur des plans d'intervention : calques photo (déplacer, redimensionner, pivoter, opacité) et dessin de formes en SVG Leaflet,
// avec équipe et rôle. Tout se fait aussi au clavier (boutons et champs de la fiche), la souris ajoute les poignées.
// Le plan (data) est modifié en place, en coordonnées de jeu ; l'éditeur travaille en coordonnées carte (voir map-geo.js).
import { h, ico, toast, run } from './ui.js';
import { signedUrls } from './sb.js';
import { send, field, input, select } from './mdt-kit.js';
import { TEAMS, TYPES, LIMITS, centre, translate, rotate, scale, angleOf, sizeOf, photoCorners, affine, resizeByCorner, metre, toGame, fromGame, checkShape, describe, newId } from './plan-geo.js';

const NS = 'http://www.w3.org/2000/svg';
const ROLES = ['Chef d’équipe', 'Brèche', 'Appui', 'Couverture', 'Tireur d’élite', 'Négociateur', 'Médic', 'Périmètre', 'Chauffeur', 'Renfort'];
const tcls = t => 't-' + (t || 'none');

export async function planEditor(ctx, { L, map, calib, data, editable, onDirty }) {
  const me = ctx.me, mt = metre(calib);
  data.layers = data.layers || []; data.shapes = data.shapes || [];
  let urls = await signedUrls(data.layers.map(l => l.path));
  map.createPane('planPhotos').style.zIndex = 250;
  map.createPane('planHandles').style.zIndex = 650;
  const gPhotos = L.layerGroup().addTo(map), gShapes = L.layerGroup().addTo(map), gDraft = L.layerGroup().addTo(map), gHandles = L.layerGroup().addTo(map);
  const st = { tool: 'select', team: 'A', role: '', label: '', sel: null, draft: null };
  const hist = [], photos = new Map(), toolbar = h('div', { class: 'plan-tools' }), panel = h('div', { class: 'plan-panel' });
  const box = map.getContainer();

  // ---------------------------------------------------------------- historique
  const snap = () => JSON.stringify({ layers: data.layers, shapes: data.shapes });
  const pushHist = () => { hist.push(snap()); if (hist.length > 40) hist.shift(); };
  const changed = () => { onDirty && onDirty(); renderShapes(); renderHandles(); refresh(); };
  const mutate = fn => { pushHist(); fn(); changed(); };
  const undo = () => {
    const s = hist.pop(); if (!s) return toast('Rien à annuler.');
    const d = JSON.parse(s); data.layers = d.layers; data.shapes = d.shapes;
    if (!find(st.sel)) st.sel = null;
    renderPhotos(); changed();
  };
  const find = id => data.shapes.find(s => s.id === id) || data.layers.find(l => l.id === id);

  // ---------------------------------------------------------------- calques photo
  const Photo = L.Layer.extend({
    initialize(url, corners, opacity, alt) { this._url = url; this._c = corners; this._o = opacity; this._alt = alt; },
    onAdd() {
      const img = this._img = L.DomUtil.create('img', 'plan-photo leaflet-zoom-animated'); img.alt = this._alt; img.draggable = false; img.style.opacity = this._o;
      img.onload = () => { this._w = img.naturalWidth; this._h = img.naturalHeight; this._place(); };
      img.src = this._url; this.getPane().appendChild(img);
    },
    onRemove() { L.DomUtil.remove(this._img); },
    getEvents() { return { zoom: this._place, viewreset: this._place, zoomanim: e => this._place(e.zoom, e.center) }; },
    _place(zoom, center) {
      if (!this._w || !this._map) return;
      const m = this._map, p = this._c.map(ll => { const q = typeof zoom === 'number' ? m._latLngToNewLayerPoint(ll, zoom, center) : m.latLngToLayerPoint(ll); return [q.x, q.y]; });
      const [a, b, c, d, e, f] = affine(p[0], p[1], p[3], this._w, this._h);
      this._img.style.width = this._w + 'px'; this._img.style.height = this._h + 'px'; this._img.style.transform = `matrix(${a},${b},${c},${d},${e},${f})`;
    },
    setCorners(c) { this._c = c; this._place(); },
    setOpacity(o) { this._o = o; if (this._img) this._img.style.opacity = o; } });
  const corners = l => fromGame(l.corners, calib);
  function renderPhotos() {
    gPhotos.clearLayers(); photos.clear();
    data.layers.forEach((l, i) => { if (!urls[l.path]) return; const p = new Photo(urls[l.path], corners(l), l.opacity ?? 0.7, 'Photo ' + (i + 1) + ' du plan'); photos.set(l.id, p); gPhotos.addLayer(p); });
  }
  const setCorners = (l, c) => { l.corners = toGame(c, calib); const p = photos.get(l.id); if (p) p.setCorners(c); };

  async function addPhoto(file) {
    if (data.layers.length >= LIMITS.layers) throw new Error('30 calques au plus.');
    const local = URL.createObjectURL(file);
    const dim = await new Promise((ok, ko) => { const i = new Image(); i.onload = () => ok(i.naturalWidth / i.naturalHeight); i.onerror = () => ko(new Error('Image illisible.')); i.src = local; }).finally(() => URL.revokeObjectURL(local));
    const path = await send(me.id, 'plans', file, true);
    urls = { ...urls, ...await signedUrls([path]) };
    const c = map.getCenter(), cs = photoCorners([c.lat, c.lng], 60 * mt.lng, dim);
    const l = { id: newId(), kind: 'photo', path, corners: toGame(cs, calib), opacity: 0.7 };
    pushHist(); data.layers.push(l); st.sel = l.id; renderPhotos(); changed();
  }

  // ---------------------------------------------------------------- formes
  function ensureDefs() {
    const svg = map.getPane('overlayPane').querySelector('svg');
    if (!svg || svg.querySelector('#plan-defs')) return;
    const defs = document.createElementNS(NS, 'defs'); defs.id = 'plan-defs';
    for (const t of ['', ...TEAMS]) {
      const m = document.createElementNS(NS, 'marker'), p = document.createElementNS(NS, 'path');
      Object.entries({ id: 'plan-arrow-' + (t || 'none'), viewBox: '0 0 10 10', refX: 6, refY: 5, markerWidth: 3.4, markerHeight: 3.4, orient: 'auto' }).forEach(([k, v]) => m.setAttribute(k, v));
      p.setAttribute('d', 'M0 0L10 5L0 10z'); p.setAttribute('class', 'plan-arrowhead ' + tcls(t)); m.append(p); defs.append(m);
    }
    svg.prepend(defs);
  }
  const tip = s => (s.team ? '[' + s.team + '] ' : '') + (s.label || TYPES[s.type][0]) + (s.role ? ' · ' + s.role : '');
  function pick(s) { st.sel = s.id; renderShapes(); renderHandles(); refresh(); }
  function renderShapes() {
    gShapes.clearLayers();
    for (const s of data.shapes) {
      const ll = fromGame(s.pts, calib), on = st.sel === s.id, cls = 'plan-shape plan-' + s.type + ' ' + tcls(s.team) + (on ? ' sel' : '');
      let lay;
      if (s.type === 'zone' || s.type === 'perimetre') lay = L.polygon(ll, { className: cls, bubblingMouseEvents: false });
      else if (s.type === 'fleche' || s.type === 'ligne') lay = L.polyline(ll, { className: cls, bubblingMouseEvents: false });
      else lay = L.marker(ll[0], { keyboard: true, title: describe(s), icon: L.divIcon({ className: cls, iconSize: s.type === 'entree' ? [30, 30] : null,
        html: s.type === 'entree' ? '<svg viewBox="0 0 24 24" width="30" height="30" aria-hidden="true"><path d="M4 3h9v18H4zM13 12h8M17.5 8l4 4-4 4"/></svg>' : h('span', {}, tip(s)) }) });
      lay.addTo(map); gShapes.addLayer(lay);
      lay.on('click', ev => { L.DomEvent.stopPropagation(ev); if (st.tool === 'select') pick(s); });
      if (s.type !== 'entree' && s.type !== 'etiquette' && (s.label || s.role || s.team)) lay.bindTooltip(tip(s), { permanent: true, direction: 'center', className: 'plan-tip ' + tcls(s.team) });
      const el = lay.getElement && lay.getElement();
      if (el && !(lay instanceof L.Marker)) {
        el.setAttribute('tabindex', '0'); el.setAttribute('role', 'button'); el.setAttribute('aria-label', describe(s)); el.setAttribute('aria-pressed', String(on));
        el.addEventListener('keydown', ev => { if (ev.key === 'Enter' || ev.key === ' ') { ev.preventDefault(); pick(s); } });
      } else if (el) el.setAttribute('aria-label', describe(s));
      if (s.type === 'fleche') { ensureDefs(); el.setAttribute('marker-end', 'url(#plan-arrow-' + (s.team || 'none') + ')'); }
    }
  }

  // ---------------------------------------------------------------- poignées de la sélection
  const dot = (ll, cls, title) => L.marker(ll, { draggable: true, keyboard: false, pane: 'planHandles', title, icon: L.divIcon({ className: 'plan-handle ' + cls, iconSize: [16, 16] }) }).addTo(gHandles);
  function renderHandles() {
    gHandles.clearLayers();
    if (!editable) return;
    const s = data.shapes.find(x => x.id === st.sel), l = data.layers.find(x => x.id === st.sel);
    if (s) {
      s.pts.forEach((pt, i) => { const m = dot(fromGame([pt], calib)[0], 'vertex ' + tcls(s.team), 'Déplacer le point ' + (i + 1));
        m.on('dragstart', () => pushHist());
        m.on('drag', ev => { const g = ev.target.getLatLng(); s.pts[i] = toGame([[g.lat, g.lng]], calib)[0]; liveShape(); });
        m.on('dragend', () => { onDirty && onDirty(); renderShapes(); renderHandles(); }); });
    } else if (l && photos.has(l.id)) {
      let base = corners(l), c0 = centre(base), dragging = null;
      const rotAt = cs => { const c = centre(cs), top = [(cs[0][0] + cs[1][0]) / 2, (cs[0][1] + cs[1][1]) / 2], v = [top[0] - c[0], top[1] - c[1]], n = Math.hypot(v[0], v[1]) || 1, k = 0.12 * Math.max(sizeOf(cs).w, sizeOf(cs).h);
        return [top[0] + v[0] / n * k, top[1] + v[1] / n * k]; };
      const hs = { move: dot(c0, 'move', 'Déplacer la photo'), rot: dot(rotAt(base), 'rot', 'Faire pivoter la photo'), cor: base.map((p, i) => dot(p, 'corner', 'Redimensionner la photo')) };
      const layout = () => { const cs = corners(l); if (dragging !== hs.move) hs.move.setLatLng(centre(cs)); if (dragging !== hs.rot) hs.rot.setLatLng(rotAt(cs)); hs.cor.forEach((m, i) => { if (dragging !== m) m.setLatLng(cs[i]); }); };
      const wire = (m, fn, start) => { m.on('dragstart', () => { pushHist(); base = corners(l); c0 = centre(base); dragging = m; start && start(); });
        m.on('drag', ev => { const g = ev.target.getLatLng(); setCorners(l, fn([g.lat, g.lng])); layout(); });
        m.on('dragend', () => { dragging = null; layout(); onDirty && onDirty(); refresh(); }); };
      wire(hs.move, p => translate(base, p[0] - c0[0], p[1] - c0[1]));
      hs.cor.forEach((m, i) => wire(m, p => resizeByCorner(base, i, p)));
      let a0 = 0;
      wire(hs.rot, p => rotate(base, c0, (Math.atan2(p[0] - c0[0], p[1] - c0[1]) - a0) * 180 / Math.PI), () => { const g = hs.rot.getLatLng(); a0 = Math.atan2(g.lat - c0[0], g.lng - c0[1]); });
    }
  }
  const liveShape = () => renderShapes();   // aperçu pendant le glissement d'un point (les poignées vivent dans un autre groupe)

  // ---------------------------------------------------------------- dessin
  const mouseLL = { v: null };
  function drawDraft() {
    gDraft.clearLayers();
    const d = st.draft; if (!d || !d.pts.length) return;
    const cls = 'plan-draft ' + tcls(st.team), pts = mouseLL.v ? [...d.pts, mouseLL.v] : d.pts;
    (d.type === 'zone' || d.type === 'perimetre' ? L.polygon : L.polyline)(pts, { className: cls, interactive: false }).addTo(gDraft);
    d.pts.forEach(p => L.circleMarker(p, { radius: 5, className: 'plan-draftpt ' + tcls(st.team), interactive: false }).addTo(gDraft));
  }
  function setTool(t) {
    st.draft = null; mouseLL.v = null; gDraft.clearLayers(); st.tool = t;
    box.classList.toggle('placing', t !== 'select');
    map.doubleClickZoom[t === 'select' ? 'enable' : 'disable']();
    if (t !== 'select') { st.sel = null; renderShapes(); renderHandles(); }
    refresh();
  }
  function addShape(s) {
    if (data.shapes.length >= LIMITS.shapes) return toast('500 formes au plus.');
    pushHist(); data.shapes.push(s); st.sel = s.id; setTool('select'); changed();
  }
  const draftShape = pts => ({ id: newId(), type: st.tool, pts: toGame(pts, calib), team: st.team, role: st.role.trim(), label: st.label.trim() });
  function addPoint(ll) {
    if (!editable || st.tool === 'select') return;
    const t = TYPES[st.tool];
    if (t[2] === 1) { const s = draftShape([ll]), err = checkShape(s); return err ? toast(err) : addShape(s); }
    (st.draft || (st.draft = { type: st.tool, pts: [] })).pts.push(ll); drawDraft(); refresh();
  }
  function finish() {
    const d = st.draft; if (!d) return;
    const s = draftShape(d.pts), err = checkShape(s);
    if (err) return toast(d.pts.length < TYPES[d.type][1] ? 'Encore ' + (TYPES[d.type][1] - d.pts.length) + ' point(s) à placer.' : err);
    addShape(s);
  }
  map.on('click', ev => { if (st.tool !== 'select') addPoint([ev.latlng.lat, ev.latlng.lng]); else if (st.sel) { st.sel = null; renderShapes(); renderHandles(); refresh(); } });
  map.on('dblclick', () => { const d = st.draft; if (!d) return; if (d.pts.length > 1) d.pts.pop(); finish(); });   // le double clic ajoute un point en trop
  map.on('mousemove', ev => { if (st.draft) { mouseLL.v = [ev.latlng.lat, ev.latlng.lng]; drawDraft(); } });
  const typing = ev => /^(INPUT|TEXTAREA|SELECT)$/.test(ev.target.tagName);
  const onKey = ev => {
    if (!box.isConnected) return;   // la page a changé : cet éditeur n'écoute plus le clavier
    if ((ev.ctrlKey || ev.metaKey) && ev.key.toLowerCase() === 'z' && !typing(ev)) { ev.preventDefault(); return undo(); }
    if (typing(ev) || !editable) return;
    if (ev.key === 'Escape') { if (st.draft || st.tool !== 'select') setTool('select'); else if (st.sel) { st.sel = null; renderShapes(); renderHandles(); refresh(); } }
    else if (ev.key === 'Enter' && st.draft) { ev.preventDefault(); finish(); }
    else if (ev.key === 'Backspace' && st.draft) { ev.preventDefault(); st.draft.pts.pop(); if (!st.draft.pts.length) st.draft = null; drawDraft(); refresh(); }
    else if (ev.key === 'Delete' && st.sel) remove(st.sel);
  };
  document.addEventListener('keydown', onKey);

  // ---------------------------------------------------------------- actions sur la sélection
  const remove = id => mutate(() => { data.shapes = data.shapes.filter(s => s.id !== id); const had = data.layers.length; data.layers = data.layers.filter(l => l.id !== id); if (had !== data.layers.length) renderPhotos(); if (st.sel === id) st.sel = null; });
  const nudge = (it, dx, dy) => {   // dx vers l'est, dy vers le nord, en mètres de jeu
    pushHist();
    if (it.pts) it.pts = it.pts.map(p => [Math.round((p[0] + dx) * 100) / 100, Math.round((p[1] + dy) * 100) / 100]);
    else setCorners(it, translate(corners(it), dy * mt.lat, dx * mt.lng));
    changed();
  };
  const turn = (l, deg) => { pushHist(); setCorners(l, rotate(corners(l), centre(corners(l)), deg)); changed(); };

  // ---------------------------------------------------------------- interface : outils et fiche
  const btn = (label, fn, on, cls, title) => h('button', { type: 'button', class: 'btn small' + (on ? ' primary' : '') + (cls ? ' ' + cls : ''), 'aria-pressed': on === undefined ? null : String(!!on), title: title || null, onclick: fn }, label);
  function refreshToolbar() {
    toolbar.replaceChildren();
    if (!editable) return;
    const role = input({ maxlength: 40, value: st.role, placeholder: 'Rôle (Brèche, Appui…)', 'aria-label': 'Rôle de la forme à dessiner', list: 'plan-roles' });
    role.addEventListener('input', () => { st.role = role.value; });
    const lab = input({ maxlength: 80, value: st.label, placeholder: 'Texte', 'aria-label': 'Texte de la forme à dessiner' });
    lab.addEventListener('input', () => { st.label = lab.value; });
    const team = select([['', 'Sans équipe'], ...TEAMS.map(t => [t, 'Équipe ' + t])], st.team, { 'aria-label': 'Équipe de la forme à dessiner' });
    team.addEventListener('change', () => { st.team = team.value; drawDraft(); });
    const file = h('input', { type: 'file', accept: 'image/png,image/jpeg,image/webp', 'aria-label': 'Ajouter une photo au plan', hidden: true });
    file.addEventListener('change', () => { const f = file.files[0]; file.value = ''; if (f) run(add, async () => { await addPhoto(f); return { message: 'Photo ajoutée : place-la avec les poignées ou la fiche.' }; }); });
    const add = btn('Ajouter une photo', () => file.click(), undefined, null, 'PNG, JPEG ou WebP, 10 Mo au plus');
    const d = st.draft;
    toolbar.append(h('datalist', { id: 'plan-roles' }, ROLES.map(r => h('option', { value: r }))),
      h('div', { class: 'plan-row', role: 'toolbar', 'aria-label': 'Outils de dessin' }, btn('Sélectionner', () => setTool('select'), st.tool === 'select'),
        Object.entries(TYPES).map(([k, v]) => btn(v[0], () => setTool(k), st.tool === k))),
      h('div', { class: 'plan-row' }, team, role, lab, add, file, btn('Annuler (Ctrl+Z)', undo, undefined, null, 'Annuler la dernière modification')),
      h('div', { class: 'plan-row plan-help', role: 'status' }, st.tool === 'select' ? h('span', {}, st.sel ? 'Fais glisser les poignées sur la carte, ou utilise la fiche sous la carte.' : 'Choisis un outil pour dessiner, ajoute une photo, ou sélectionne un élément dans la liste.') : h('span', {}, TYPES[st.tool][2] === 1 ? 'Clique sur la carte pour placer.' : d ? d.pts.length + ' point(s). Entrée ou double clic pour terminer, Retour arrière pour retirer le dernier, Échap pour annuler.' : 'Clique sur la carte pour poser le premier point.'),
        btn('Ajouter un point au centre', () => { const c = map.getCenter(); addPoint([c.lat, c.lng]); }, undefined, null, 'Sans souris : place un point au centre de la vue'),
        d ? btn('Terminer', finish, undefined, 'primary') : null, d ? btn('Retirer le dernier point', () => { d.pts.pop(); if (!d.pts.length) st.draft = null; drawDraft(); refresh(); }) : null, btn('Annuler le tracé', () => setTool('select'))));
  }
  const step = h('select', { 'aria-label': 'Pas de déplacement' }, [[1, '1 m'], [5, '5 m'], [25, '25 m']].map(([v, l]) => h('option', { value: v, selected: v === 5 }, l)));
  const pad = it => h('div', { class: 'plan-nudge', role: 'group', 'aria-label': 'Déplacer' }, step,
    [['←', -1, 0, 'à l’ouest'], ['↑', 0, 1, 'au nord'], ['↓', 0, -1, 'au sud'], ['→', 1, 0, 'à l’est']].map(([t, dx, dy, a]) => h('button', { type: 'button', class: 'btn small', 'aria-label': 'Déplacer ' + a, onclick: () => nudge(it, dx * step.value, dy * step.value) }, t)));
  function refreshPanel() {
    panel.replaceChildren();
    const s = data.shapes.find(x => x.id === st.sel), l = data.layers.find(x => x.id === st.sel);
    const item = (id, label, kind) => h('li', {}, h('button', { type: 'button', class: 'plan-item' + (st.sel === id ? ' on' : ''), 'aria-pressed': String(st.sel === id), onclick: () => { st.tool = 'select'; setTool('select'); st.sel = id; renderShapes(); renderHandles(); refresh(); } }, ico(kind, 16), label));
    panel.append(h('div', { class: 'plan-list' }, h('h3', { class: 'sub-h' }, 'Calques et formes'),
      data.layers.length || data.shapes.length ? h('ul', { class: 'people' }, data.layers.map((x, i) => item(x.id, 'Photo ' + (i + 1), 'carte')), data.shapes.map(x => item(x.id, describe(x), 'cible'))) : h('p', { class: 'muted' }, 'Rien pour l’instant.')));
    if (s) {
      const lab = input({ maxlength: 80, value: s.label || '', 'aria-label': 'Texte', disabled: !editable }), role = input({ maxlength: 40, value: s.role || '', 'aria-label': 'Rôle', list: 'plan-roles', disabled: !editable });
      const team = select([['', 'Sans équipe'], ...TEAMS.map(t => [t, 'Équipe ' + t])], s.team || '', { 'aria-label': 'Équipe', disabled: !editable });
      const apply = () => { const v = { ...s, label: lab.value.trim(), role: role.value.trim(), team: team.value }, err = checkShape(v); if (err) return toast(err); mutate(() => Object.assign(s, v)); };
      [lab, role, team].forEach(c => c.addEventListener('change', apply));
      panel.append(h('div', { class: 'plan-edit' }, h('h3', { class: 'sub-h' }, TYPES[s.type][0]), h('div', { class: 'row' }, field('Texte', lab, 'grow'), field('Rôle', role, 'grow'), field('Équipe', team)),
        editable ? h('div', { class: 'plan-row' }, pad(s), btn('Supprimer la forme', () => remove(s.id), undefined, 'danger')) : null));
    } else if (l) {
      const op = h('input', { type: 'range', min: 10, max: 100, step: 5, value: Math.round((l.opacity ?? 0.7) * 100), 'aria-label': 'Opacité de la photo', disabled: !editable });
      op.addEventListener('input', () => { l.opacity = Number(op.value) / 100; const p = photos.get(l.id); p && p.setOpacity(l.opacity); onDirty && onDirty(); });
      op.addEventListener('change', () => { pushHist(); onDirty && onDirty(); });
      const ang = h('input', { type: 'number', min: -180, max: 180, step: 1, value: Math.round(angleOf(corners(l))), 'aria-label': 'Rotation en degrés', disabled: !editable });
      ang.addEventListener('change', () => { const to = Number(ang.value); if (Number.isFinite(to)) turn(l, to - angleOf(corners(l))); });
      const k = f => { pushHist(); setCorners(l, scale(corners(l), centre(corners(l)), f)); changed(); };
      const i = data.layers.indexOf(l), move = d => mutate(() => { data.layers.splice(i, 1); data.layers.splice(i + d, 0, l); renderPhotos(); });
      const sz = sizeOf(corners(l));
      panel.append(h('div', { class: 'plan-edit' }, h('h3', { class: 'sub-h' }, 'Photo ' + (i + 1) + ' · ' + Math.round(sz.w / mt.lng) + ' m de large'),
        h('div', { class: 'row' }, field('Opacité', op, 'grow'), field('Rotation (°)', ang)),
        editable ? h('div', { class: 'plan-row' }, pad(l), btn('Agrandir', () => k(1.05)), btn('Réduire', () => k(1 / 1.05)), btn('Pivoter à gauche', () => turn(l, 5)), btn('Pivoter à droite', () => turn(l, -5)),
          btn('Au centre de la vue', () => { const c = map.getCenter(), cs = corners(l), cc = centre(cs); pushHist(); setCorners(l, translate(cs, c.lat - cc[0], c.lng - cc[1])); changed(); }),
          i > 0 ? btn('Passer dessous', () => move(-1)) : null, i < data.layers.length - 1 ? btn('Passer dessus', () => move(1)) : null, btn('Supprimer la photo', () => remove(l.id), undefined, 'danger')) : null));
    }
  }
  const refresh = () => { refreshToolbar(); refreshPanel(); };

  renderPhotos(); renderShapes(); renderHandles(); refresh();
  return { toolbar, panel, destroy() { document.removeEventListener('keydown', onKey); } };
}
