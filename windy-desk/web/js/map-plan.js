// Éditeur des plans d'intervention : calques photo (déplacer, redimensionner, pivoter, opacité) et dessin de formes en SVG Leaflet,
// avec équipe et rôle. Tout se fait aussi au clavier (boutons et champs de la fiche), la souris ajoute les poignées.
// Le plan (data) est modifié en place, en coordonnées de jeu ; l'éditeur travaille en coordonnées carte (voir map-geo.js).
import { h, ico, toast, run } from './ui.js';
import { signedUrls } from './sb.js';
import { send, field, input, select } from './mdt-kit.js';
import { TEAMS, TYPES, VEHICLES, TEXT_SIZES, LIMITS, colorOf, teamColor, teamName, centre, translate, rotate, scale, angleOf, sizeOf, photoCorners, affine, resizeByCorner, metre, toGame, fromGame, checkShape, describe, tipOf, newId } from './plan-geo.js';

const NS = 'http://www.w3.org/2000/svg';
const ROLES = ['Chef d’équipe', 'Brèche', 'Appui', 'Couverture', 'Tireur d’élite', 'Négociateur', 'Médic', 'Périmètre', 'Chauffeur', 'Renfort'];
const tcls = t => 't-' + (t || 'none');
// symboles de véhicules vus de dessus, nez vers le haut (le nord) ; la classe g = vitres, l = traits
const VSVG = {
  voiture: ['0 0 20 40', '<rect x="2" y="1" width="16" height="38" rx="6"/><rect class="g" x="4.5" y="9" width="11" height="8" rx="2"/><rect class="g" x="4.5" y="24" width="11" height="6" rx="2"/>'],
  suv: ['0 0 20 40', '<rect x="2" y="1" width="16" height="38" rx="5"/><rect class="g" x="4.5" y="8" width="11" height="7" rx="2"/><rect class="g" x="4.5" y="17" width="11" height="14" rx="2"/>'],
  camion: ['0 0 20 40', '<rect x="1" y="1" width="18" height="38" rx="3"/><rect class="g" x="3" y="3" width="14" height="8" rx="2"/><path class="l" d="M10 13V38"/>'],
  moto: ['0 0 20 40', '<rect x="8" y="1" width="4" height="38" rx="2"/><rect x="3" y="11" width="14" height="3" rx="1.5"/><rect class="g" x="7" y="18" width="6" height="8" rx="2"/>'],
  helico: ['0 0 40 40', '<ellipse cx="20" cy="15" rx="6" ry="10"/><rect x="18.4" y="22" width="3.2" height="16" rx="1.2"/><path class="l" d="M4 15H36M9 4L31 26M31 4L9 26"/>'],
  bateau: ['0 0 20 40', '<path d="M10 1C15 8 18 15 18 22V39H2V22C2 15 5 8 10 1z"/><path class="l" d="M10 12V30"/>'] };
const vehicleSvg = (kind, color, deg) => { const [vb, body] = VSVG[kind] || VSVG.voiture;
  return `<svg viewBox="${vb}" preserveAspectRatio="none" width="100%" height="100%" style="transform:rotate(${deg || 0}deg)" fill="${color}" stroke="#fff" stroke-width="1.4" aria-hidden="true">${body}</svg>`; };
const LEG = { fleche: 'Déplacement', zone: 'Zone', perimetre: 'Périmètre', ligne: 'Ligne', entree: 'Point d’entrée' };
const symbol = t => ({
  fleche: '<path d="M2 8h16M13 3l6 5-6 5" fill="none"/>', zone: '<rect x="3" y="2" width="16" height="12" fill-opacity=".25"/>', perimetre: '<rect x="3" y="2" width="16" height="12" fill="none" stroke-dasharray="4 3"/>',
  ligne: '<path d="M2 8h18" fill="none" stroke-dasharray="1 4" stroke-linecap="round"/>', entree: '<path d="M5 2h7v12H5zM12 8h8" fill="none"/>' })[t];

export async function planEditor(ctx, { L, map, calib, data, editable, onDirty }) {
  const me = ctx.me, mt = metre(calib);
  data.layers = data.layers || []; data.shapes = data.shapes || [];
  if (!data.legend || typeof data.legend !== 'object' || Array.isArray(data.legend)) data.legend = { show: true };
  data.legend.teams = data.legend.teams || {};
  let urls = await signedUrls(data.layers.map(l => l.path));
  map.createPane('planPhotos').style.zIndex = 250;
  map.createPane('planHandles').style.zIndex = 650;
  const gPhotos = L.layerGroup().addTo(map), gShapes = L.layerGroup().addTo(map), gDraft = L.layerGroup().addTo(map), gHandles = L.layerGroup().addTo(map);
  const st = { tool: 'select', team: 'A', role: '', label: '', color: '', vkind: 'voiture', size: 16, sel: null, draft: null, legendOpen: false };
  const hist = [], photos = new Map(), toolbar = h('div', { class: 'plan-tools' }), panel = h('div', { class: 'plan-panel' });
  const box = map.getContainer();

  // ---------------------------------------------------------------- historique
  const snap = () => JSON.stringify({ layers: data.layers, shapes: data.shapes, legend: data.legend });
  const pushHist = () => { hist.push(snap()); if (hist.length > 40) hist.shift(); };
  const changed = () => { onDirty && onDirty(); renderShapes(); renderHandles(); refresh(); legendCtl.update(); };
  const mutate = fn => { pushHist(); fn(); changed(); };
  const undo = () => {
    const s = hist.pop(); if (!s) return toast('Rien à annuler.');
    const d = JSON.parse(s); data.layers = d.layers; data.shapes = d.shapes; data.legend = d.legend;
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
  const col = x => colorOf(x, data.legend);
  function ensureArrow(color) {   // une pointe de flèche par couleur
    const svg = map.getPane('overlayPane').querySelector('svg'); if (!svg) return null;
    let defs = svg.querySelector('#plan-defs'); if (!defs) { defs = document.createElementNS(NS, 'defs'); defs.id = 'plan-defs'; svg.prepend(defs); }
    const id = 'plan-arrow-' + color.slice(1);
    if (!defs.querySelector('#' + id)) {
      const m = document.createElementNS(NS, 'marker'), p = document.createElementNS(NS, 'path');
      Object.entries({ id, viewBox: '0 0 10 10', refX: 6, refY: 5, markerWidth: 3.4, markerHeight: 3.4, orient: 'auto' }).forEach(([k, v]) => m.setAttribute(k, v));
      p.setAttribute('d', 'M0 0L10 5L0 10z'); p.setAttribute('fill', color); m.append(p); defs.append(m);
    }
    return id;
  }
  const tip = x => tipOf(x, data.legend);
  function pick(x) { st.sel = x.id; renderShapes(); renderHandles(); refresh(); }
  const pxPerM = () => mt.lng * Math.pow(2, map.getZoom());
  function vehicleSize(x) {   // taille du symbole : proportionnelle au zoom, avec un minimum pour rester visible
    const [, len, wid] = VEHICLES[x.vkind] || VEHICLES.voiture, hgt = Math.min(140, Math.max(x.vkind === 'helico' ? 34 : 26, len * pxPerM()));
    return [x.vkind === 'helico' ? hgt : Math.max(11, hgt * wid / len), hgt];
  }
  function renderShapes() {
    gShapes.clearLayers();
    for (const x of data.shapes) {
      const color = col(x), on = st.sel === x.id, cls = 'plan-shape plan-' + x.type + ' ' + tcls(x.team) + (on ? ' sel' : ''), ll = fromGame(x.pts, calib);
      let lay;
      if (x.type === 'zone' || x.type === 'perimetre') lay = L.polygon(ll, { className: cls, bubblingMouseEvents: false });
      else if (x.type === 'fleche' || x.type === 'ligne') lay = L.polyline(ll, { className: cls, bubblingMouseEvents: false });
      else {
        const icon = x.type === 'entree' ? L.divIcon({ className: cls, iconSize: [30, 30], html: '<svg viewBox="0 0 24 24" width="30" height="30" aria-hidden="true"><path d="M4 3h9v18H4zM13 12h8M17.5 8l4 4-4 4"/></svg>' })
          : x.type === 'vehicule' ? L.divIcon({ className: cls, iconSize: vehicleSize(x), html: vehicleSvg(x.vkind, color, x.angle) })
          : x.type === 'texte' ? L.divIcon({ className: cls, iconSize: null, html: h('span', { style: 'font-size:' + (x.size || 16) + 'px' }, x.label) })
          : L.divIcon({ className: cls, iconSize: null, html: h('span', {}, tip(x)) });
        lay = L.marker(ll[0], { keyboard: true, title: describe(x, data.legend), icon });
      }
      lay.addTo(map); gShapes.addLayer(lay);
      lay.on('click', ev => { L.DomEvent.stopPropagation(ev); if (st.tool === 'select') pick(x); else addPoint([ev.latlng.lat, ev.latlng.lng]); });   // en cours de tracé, un clic sur une forme place quand même le point
      if (x.type !== 'etiquette' && x.type !== 'texte' && (x.label || x.role || x.team)) {
        lay.bindTooltip(tip(x), { permanent: true, direction: x.type === 'vehicule' || x.type === 'entree' ? 'right' : 'center', offset: x.type === 'vehicule' ? [vehicleSize(x)[0] / 2 + 4, 0] : [0, 0], className: 'plan-tip' });
        lay.on('tooltipopen', ev => ev.tooltip.getElement().style.setProperty('--tc', color));
      }
      const el = lay.getElement && lay.getElement();
      if (!el) continue;
      el.style.setProperty('--tc', color);
      if (lay instanceof L.Marker) el.setAttribute('aria-label', describe(x, data.legend));
      else {
        el.setAttribute('tabindex', '0'); el.setAttribute('role', 'button'); el.setAttribute('aria-label', describe(x, data.legend)); el.setAttribute('aria-pressed', String(on));
        el.addEventListener('keydown', ev => { if (ev.key === 'Enter' || ev.key === ' ') { ev.preventDefault(); pick(x); } });
      }
      if (x.type === 'fleche') { const id = ensureArrow(color); if (id) el.setAttribute('marker-end', 'url(#' + id + ')'); }
    }
  }
  map.on('zoomend', () => { if (data.shapes.some(x => x.type === 'vehicule')) renderShapes(); });

  // ---------------------------------------------------------------- poignées de la sélection
  const dot = (ll, cls, title) => L.marker(ll, { draggable: true, keyboard: false, pane: 'planHandles', title, icon: L.divIcon({ className: 'plan-handle ' + cls, iconSize: [16, 16] }) }).addTo(gHandles);
  function renderHandles() {
    gHandles.clearLayers();
    if (!editable) return;
    const s = data.shapes.find(x => x.id === st.sel), l = data.layers.find(x => x.id === st.sel);
    if (s) {
      s.pts.forEach((pt, i) => { const m = dot(fromGame([pt], calib)[0], 'vertex', 'Déplacer le point ' + (i + 1));
        m.on('dragstart', () => pushHist());
        m.on('drag', ev => { const g = ev.target.getLatLng(); s.pts[i] = toGame([[g.lat, g.lng]], calib)[0]; liveShape(); });
        m.on('dragend', () => { onDirty && onDirty(); renderShapes(); renderHandles(); }); });
      if (s.type === 'vehicule') {   // poignée d'orientation, à 9 m devant le véhicule
        const a = (s.angle || 0) * Math.PI / 180, r = dot(fromGame([[s.pts[0][0] + 9 * Math.sin(a), s.pts[0][1] + 9 * Math.cos(a)]], calib)[0], 'rot', 'Orienter le véhicule');
        r.on('dragstart', () => pushHist());
        r.on('drag', ev => { const g = ev.target.getLatLng(), q = toGame([[g.lat, g.lng]], calib)[0]; s.angle = Math.round((Math.atan2(q[0] - s.pts[0][0], q[1] - s.pts[0][1]) * 180 / Math.PI + 360) % 360); liveShape(); });
        r.on('dragend', () => { onDirty && onDirty(); renderShapes(); renderHandles(); refresh(); });
      }
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
  const draftShape = pts => {
    const x = { id: newId(), type: st.tool, pts: toGame(pts, calib), team: st.team, role: st.role.trim(), label: st.label.trim() };
    if (st.color) x.color = st.color;
    if (st.tool === 'vehicule') { x.vkind = st.vkind; x.angle = 0; }
    if (st.tool === 'texte') x.size = st.size;
    return x;
  };
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
    const color = h('input', { type: 'color', value: st.color || teamColor(data.legend, st.team), 'aria-label': 'Couleur de la forme à dessiner' });
    color.addEventListener('input', () => { st.color = color.value; });
    const team = select([['', 'Sans équipe'], ...TEAMS.map(t => [t, teamName(data.legend, t)])], st.team, { 'aria-label': 'Équipe de la forme à dessiner' });
    team.addEventListener('change', () => { st.team = team.value; if (!st.color) color.value = teamColor(data.legend, st.team); drawDraft(); });
    const vk = select(Object.entries(VEHICLES).map(([k, v]) => [k, v[0]]), st.vkind, { 'aria-label': 'Type de véhicule' });
    vk.addEventListener('change', () => { st.vkind = vk.value; });
    const sz = select(TEXT_SIZES.map(([v, l]) => [String(v), l]), String(st.size), { 'aria-label': 'Taille du texte' });
    sz.addEventListener('change', () => { st.size = Number(sz.value); });
    const file = h('input', { type: 'file', accept: 'image/png,image/jpeg,image/webp', 'aria-label': 'Ajouter une photo au plan', hidden: true });
    file.addEventListener('change', () => { const f = file.files[0]; file.value = ''; if (f) run(add, async () => { await addPhoto(f); return { message: 'Photo ajoutée : place-la avec les poignées ou la fiche.' }; }); });
    const add = btn('Ajouter une photo', () => file.click(), undefined, null, 'PNG, JPEG ou WebP, 10 Mo au plus');
    const d = st.draft;
    toolbar.append(h('datalist', { id: 'plan-roles' }, ROLES.map(r => h('option', { value: r }))),
      h('div', { class: 'plan-row', role: 'toolbar', 'aria-label': 'Outils de dessin' }, btn('Sélectionner', () => setTool('select'), st.tool === 'select'),
        Object.entries(TYPES).map(([k, v]) => btn(v[0], () => setTool(k), st.tool === k))),
      h('div', { class: 'plan-row' }, team, field('Couleur', color), st.color ? btn('Couleur de l’équipe', () => { st.color = ''; refreshToolbar(); }, undefined, null, 'Reprendre la couleur de l’équipe') : null,
        st.tool === 'vehicule' ? vk : null, st.tool === 'texte' ? sz : null, role, lab, add, file, btn('Annuler (Ctrl+Z)', undo, undefined, null, 'Annuler la dernière modification')),
      h('div', { class: 'plan-row plan-help', role: 'status' }, st.tool === 'select' ? h('span', {}, st.sel ? 'Fais glisser les poignées sur la carte, ou utilise la fiche sous la carte.' : 'Choisis un outil pour dessiner, ajoute une photo, ou sélectionne un élément dans la liste.')
        : h('span', {}, TYPES[st.tool][2] === 1 ? 'Clique sur la carte pour placer.' : d ? d.pts.length + ' point(s). Entrée ou double clic pour terminer, Retour arrière pour retirer le dernier, Échap pour annuler.' : 'Clique sur la carte pour poser le premier point.'),
        st.tool !== 'select' ? btn('Ajouter un point au centre', () => { const c = map.getCenter(); addPoint([c.lat, c.lng]); }, undefined, null, 'Sans souris : place un point au centre de la vue') : null,
        d ? btn('Terminer', finish, undefined, 'primary') : null, d ? btn('Retirer le dernier point', () => { d.pts.pop(); if (!d.pts.length) st.draft = null; drawDraft(); refresh(); }) : null,
        st.tool !== 'select' ? btn('Annuler le tracé', () => setTool('select')) : null));
  }
  const step = h('select', { 'aria-label': 'Pas de déplacement' }, [[1, '1 m'], [5, '5 m'], [25, '25 m']].map(([v, l]) => h('option', { value: v, selected: v === 5 }, l)));
  const pad = it => h('div', { class: 'plan-nudge', role: 'group', 'aria-label': 'Déplacer' }, step,
    [['←', -1, 0, 'à l’ouest'], ['↑', 0, 1, 'au nord'], ['↓', 0, -1, 'au sud'], ['→', 1, 0, 'à l’est']].map(([t, dx, dy, a]) => h('button', { type: 'button', class: 'btn small', 'aria-label': 'Déplacer ' + a, onclick: () => nudge(it, dx * step.value, dy * step.value) }, t)));
  const colorField = x => {
    const c = h('input', { type: 'color', value: col(x), 'aria-label': 'Couleur', disabled: !editable });
    c.addEventListener('change', () => mutate(() => { x.color = c.value; }));
    return h('span', { class: 'plan-colorfield' }, c, editable && x.color ? btn('Couleur de l’équipe', () => mutate(() => { delete x.color; })) : null);
  };
  function legendCard() {
    const g = data.legend, show = h('input', { type: 'checkbox', checked: g.show !== false, disabled: !editable });
    show.addEventListener('change', () => mutate(() => { g.show = show.checked; }));
    const rowsT = TEAMS.map(t => {
      const name = input({ maxlength: 30, value: (g.teams[t] && g.teams[t].name) || '', placeholder: 'Équipe ' + t, 'aria-label': 'Nom de l’équipe ' + t, disabled: !editable });
      const c = h('input', { type: 'color', value: teamColor(g, t), 'aria-label': 'Couleur de l’équipe ' + t, disabled: !editable });
      const set = () => mutate(() => { g.teams[t] = { name: name.value.trim(), color: c.value }; });
      name.addEventListener('change', set); c.addEventListener('change', set);
      return h('div', { class: 'row' }, field('Équipe ' + t, name, 'grow'), field('Couleur', c));
    });
    const det = h('details', { class: 'plan-legend-edit', open: st.legendOpen }, h('summary', {}, 'Légende et couleurs des équipes'),
      h('label', { class: 'check' }, show, 'Afficher la légende sur la carte'), rowsT, editable ? btn('Rétablir les couleurs d’origine', () => mutate(() => { g.teams = {}; })) : null);
    det.addEventListener('toggle', () => { st.legendOpen = det.open; });
    return det;
  }
  function refreshPanel() {
    panel.replaceChildren();
    const s = data.shapes.find(x => x.id === st.sel), l = data.layers.find(x => x.id === st.sel);
    const item = (id, label, kind) => h('li', {}, h('button', { type: 'button', class: 'plan-item' + (st.sel === id ? ' on' : ''), 'aria-pressed': String(st.sel === id), onclick: () => { setTool('select'); st.sel = id; renderShapes(); renderHandles(); refresh(); } }, ico(kind, 16), label));
    panel.append(h('div', { class: 'plan-list' }, h('h3', { class: 'sub-h' }, 'Calques et formes'),
      data.layers.length || data.shapes.length ? h('ul', { class: 'people' }, data.layers.map((x, i) => item(x.id, 'Photo ' + (i + 1), 'carte')), data.shapes.map(x => item(x.id, describe(x, data.legend), x.type === 'vehicule' ? 'camion' : 'cible'))) : h('p', { class: 'muted' }, 'Rien pour l’instant.')));
    if (s) {
      const lab = input({ maxlength: 80, value: s.label || '', 'aria-label': 'Texte', disabled: !editable }), role = input({ maxlength: 40, value: s.role || '', 'aria-label': 'Rôle', list: 'plan-roles', disabled: !editable });
      const team = select([['', 'Sans équipe'], ...TEAMS.map(t => [t, teamName(data.legend, t)])], s.team || '', { 'aria-label': 'Équipe', disabled: !editable });
      const vk = s.type === 'vehicule' ? select(Object.entries(VEHICLES).map(([k, v]) => [k, v[0]]), s.vkind, { 'aria-label': 'Type de véhicule', disabled: !editable }) : null;
      const ang = s.type === 'vehicule' ? h('input', { type: 'number', min: 0, max: 359, step: 5, value: Math.round(s.angle || 0), 'aria-label': 'Orientation en degrés (0 = nord, 90 = est)', disabled: !editable }) : null;
      const sz = s.type === 'texte' ? select(TEXT_SIZES.map(([v, t]) => [String(v), t]), String(s.size || 16), { 'aria-label': 'Taille du texte', disabled: !editable }) : null;
      const hg = s.type === 'zone' || s.type === 'perimetre' ? h('input', { type: 'number', min: 0, max: 60, step: 0.5, value: s.height ?? '', placeholder: s.type === 'zone' ? '0,3' : '3,5', 'aria-label': 'Hauteur en mètres pour la vue 3D', disabled: !editable }) : null;
      const apply = () => {
        const v = { ...s, label: lab.value.trim(), role: role.value.trim(), team: team.value };
        if (vk) v.vkind = vk.value; if (ang) v.angle = ((Number(ang.value) || 0) % 360 + 360) % 360; if (sz) v.size = Number(sz.value);
        if (hg) { if (hg.value === '') delete v.height; else v.height = Number(hg.value); }
        const err = checkShape(v); if (err) return toast(err); mutate(() => { Object.keys(s).forEach(k => delete s[k]); Object.assign(s, v); });
      };
      [lab, role, team, vk, ang, sz, hg].filter(Boolean).forEach(c => c.addEventListener('change', apply));
      const turnV = d => mutate(() => { s.angle = (((s.angle || 0) + d) % 360 + 360) % 360; });
      panel.append(h('div', { class: 'plan-edit' }, h('h3', { class: 'sub-h' }, TYPES[s.type][0]),
        h('div', { class: 'row' }, field('Texte', lab, 'grow'), s.type === 'texte' || s.type === 'etiquette' ? null : field('Rôle', role, 'grow'), field('Équipe', team), field('Couleur', colorField(s))),
        h('div', { class: 'row' }, vk ? field('Véhicule', vk) : null, ang ? field('Orientation (°)', ang) : null, sz ? field('Taille', sz) : null, hg ? field('Hauteur 3D (m)', hg) : null),
        editable ? h('div', { class: 'plan-row' }, pad(s), ang ? [btn('Pivoter à gauche', () => turnV(-15)), btn('Pivoter à droite', () => turnV(15))] : null, btn('Supprimer la forme', () => remove(s.id), undefined, 'danger')) : null));
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
    panel.append(legendCard());
  }
  const refresh = () => { refreshToolbar(); refreshPanel(); };
  // légende affichée sur la carte : équipes, symboles et véhicules réellement utilisés dans le plan
  const Legend = L.Control.extend({
    options: { position: 'bottomright' },
    onAdd() { this._d = L.DomUtil.create('div', 'plan-legend'); L.DomEvent.disableClickPropagation(this._d); L.DomEvent.disableScrollPropagation(this._d); this.update(); return this._d; },
    update() {
      const d = this._d; if (!d) return;
      const g = data.legend, used = new Set(data.shapes.map(x => x.team || '')), teams = TEAMS.filter(t => used.has(t) || (g.teams[t] && g.teams[t].name));
      const types = Object.keys(LEG).filter(t => data.shapes.some(x => x.type === t)), kinds = Object.keys(VEHICLES).filter(k => data.shapes.some(x => x.type === 'vehicule' && x.vkind === k));
      d.hidden = g.show === false || !(teams.length || types.length || kinds.length); d.replaceChildren();
      if (d.hidden) return;
      const vi = k => { const e = h('span', { class: 'vk', 'aria-hidden': 'true' }); e.innerHTML = vehicleSvg(k, '#495057', 0); return e; };
      const sw = c => h('i', { class: 'sw', style: 'background:' + c, 'aria-hidden': 'true' });
      const sym = (inner, c) => { const e = document.createElementNS(NS, 'svg'); e.setAttribute('viewBox', '0 0 22 16'); e.setAttribute('width', '22'); e.setAttribute('height', '16'); e.setAttribute('aria-hidden', 'true');
        e.setAttribute('style', 'stroke:' + c + ';fill:' + c + ';stroke-width:2;stroke-linejoin:round'); e.innerHTML = inner; return e; };
      d.append(h('b', {}, 'Légende'), h('ul', {}, teams.map(t => h('li', {}, sw(teamColor(g, t)), teamName(g, t))), types.map(t => h('li', {}, sym(symbol(t), '#495057'), LEG[t])),
        kinds.map(k => h('li', {}, vi(k), VEHICLES[k][0]))));
    } });
  const legendCtl = new Legend().addTo(map);

  renderPhotos(); renderShapes(); renderHandles(); refresh();
  return { toolbar, panel, destroy() { document.removeEventListener('keydown', onKey); } };
}
