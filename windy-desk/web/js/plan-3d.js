// Vue 3D d'un plan d'intervention : une maquette générée à partir du plan lui-même, sans modèle ni fichier à fournir.
// Sol = tuiles satellite du lieu ; périmètres = murs, zones = volumes (hauteur réglable), flèches et lignes en relief, véhicules en volumes simples,
// photos posées au sol, textes en étiquettes. three.js (MIT) est chargé à la demande depuis web/vendor/three/ (CSP : rien d'externe).
// Repère : x vers l'est, y du jeu vers le nord → X = x, Z = -y, Y = hauteur ; l'avant d'un modèle regarde vers -Z (le nord).
import { h, ico, toast } from './ui.js';
import { saveBlob } from './doc.js';
import { signedUrls } from './sb.js';
import { VEHICLES, colorOf, teamColor, teamName, planBounds, groundTiles, tipOf } from './plan-geo.js';

let lib = null;
const loadThree = () => lib || (lib = import('/vendor/three/three.plan.min.js').catch(e => { lib = null; throw e; }));
const loadImg = (src, cors) => new Promise((ok, ko) => { const i = new Image(); if (cors) i.crossOrigin = 'anonymous'; i.onload = () => ok(i); i.onerror = () => ko(new Error('Image introuvable')); i.src = src; });

export async function open3D(host, { data, calib, meta, title, legend }) {
  const T = await loadThree();
  const bounds = planBounds(data, calib), gt = meta ? groundTiles(bounds, calib, meta.maxZoom) : null, rect = gt ? gt.rect : bounds;
  const cx = (rect.minX + rect.maxX) / 2, cy = (rect.minY + rect.maxY) / 2, W = rect.maxX - rect.minX, D = rect.maxY - rect.minY, span = Math.max(W, D);
  const P = (x, y) => [x - cx, -(y - cy)];
  const u = Math.max(1, span / 150);   // les plans très étendus grossissent étiquettes, flèches et lignes pour rester lisibles
  const canvas = h('canvas', { class: 'p3d-canvas', tabindex: 0, role: 'img', 'aria-label': 'Vue 3D du plan « ' + title + ' » : ' + data.shapes.length + ' forme(s), ' + data.layers.length + ' photo(s). Souris : glisser pour tourner, molette pour zoomer. Clavier : flèches pour déplacer la vue.' });
  const status = h('p', { class: 'muted small', role: 'status' }, 'Préparation de la maquette…');
  const view = h('div', { class: 'p3d-view' }, canvas);
  let renderer;
  try { renderer = new T.WebGLRenderer({ canvas, antialias: true }); } catch { host.replaceChildren(h('p', { class: 'muted' }, 'La vue 3D demande WebGL, que ce navigateur ne fournit pas. Le plan en 2D reste utilisable.')); return { destroy() {} }; }
  renderer.outputColorSpace = T.SRGBColorSpace;
  const scene = new T.Scene(); scene.background = new T.Color('#c9d3de');
  const camera = new T.PerspectiveCamera(50, 1, 0.5, span * 20);
  scene.add(new T.HemisphereLight('#ffffff', '#5b6470', 1.35)); const sun = new T.DirectionalLight('#ffffff', 1.5); sun.position.set(span * 0.4, span * 0.9, span * 0.3); scene.add(sun);
  const controls = new T.OrbitControls(camera, canvas);
  controls.maxPolarAngle = Math.PI * 0.495; controls.minDistance = 4; controls.maxDistance = span * 4; controls.listenToKeyEvents(canvas); controls.keyPanSpeed = 20;
  let gone = false;
  const render = () => { if (!gone) renderer.render(scene, camera); };
  controls.addEventListener('change', render);
  const textures = [], geos = [];
  const hold = g => (geos.push(g), g);
  const mat = (c, o = 1) => new T.MeshStandardMaterial({ color: c, roughness: 0.75, metalness: 0.05, transparent: o < 1, opacity: o });
  const add = (g, m, x, y, z, parent = scene) => { const o = new T.Mesh(hold(g), m); o.position.set(x, y, z); parent.add(o); return o; };

  // ---- sol : tuiles satellite du lieu (ou fond neutre sans tuiles)
  let ground = mat('#8d9a85');
  if (gt) {
    const cv = document.createElement('canvas'); cv.width = (gt.x1 - gt.x0 + 1) * 256; cv.height = (gt.y1 - gt.y0 + 1) * 256;
    const c2 = cv.getContext('2d'), jobs = [];
    for (let x = gt.x0; x <= gt.x1; x++) for (let y = gt.y0; y <= gt.y1; y++) jobs.push(loadImg('/tiles/gta5/' + gt.z + '/' + x + '/' + y + '.' + (meta.ext || 'webp')).then(i => c2.drawImage(i, (x - gt.x0) * 256, (y - gt.y0) * 256), () => {}));
    await Promise.all(jobs);
    const tex = new T.CanvasTexture(cv); tex.colorSpace = T.SRGBColorSpace; tex.anisotropy = renderer.capabilities.getMaxAnisotropy(); textures.push(tex);
    ground = new T.MeshBasicMaterial({ map: tex });
  }
  const gm = add(new T.PlaneGeometry(W, D), ground, 0, 0, 0); gm.rotation.x = -Math.PI / 2;

  // ---- photos posées au sol
  const urls = await signedUrls(data.layers.map(l => l.path)).catch(() => ({}));
  for (const l of data.layers) {
    if (!urls[l.path]) continue;
    const g = new T.BufferGeometry(), c = l.corners.map(p => P(p[0], p[1]));
    g.setAttribute('position', new T.Float32BufferAttribute(c.flatMap(p => [p[0], 0.08, p[1]]), 3)); g.setAttribute('uv', new T.Float32BufferAttribute([0, 1, 1, 1, 1, 0, 0, 0], 2)); g.setIndex([0, 2, 1, 0, 3, 2]);
    const tex = new T.TextureLoader().setCrossOrigin('anonymous').load(urls[l.path], render); tex.colorSpace = T.SRGBColorSpace; textures.push(tex);
    const m = new T.Mesh(hold(g), new T.MeshBasicMaterial({ map: tex, transparent: true, opacity: l.opacity ?? 0.7, side: T.DoubleSide, depthWrite: false })); m.renderOrder = 1; scene.add(m);
  }

  // ---- étiquettes (sprites dessinés sur un canevas)
  function label(text, color, hgt, x, y, z, plain) {
    const cv = document.createElement('canvas'), c2 = cv.getContext('2d'), f = 'bold 44px system-ui, sans-serif';
    c2.font = f; const w = Math.ceil(c2.measureText(text).width) + 36; cv.width = w; cv.height = 72; c2.font = f;
    if (!plain) { c2.fillStyle = 'rgba(255,255,255,.94)'; c2.strokeStyle = color; c2.lineWidth = 6; c2.beginPath(); c2.roundRect(3, 3, w - 6, 66, 33); c2.fill(); c2.stroke(); }
    else { c2.strokeStyle = '#fff'; c2.lineWidth = 9; c2.lineJoin = 'round'; c2.textBaseline = 'middle'; c2.strokeText(text, 18, 38); }
    c2.fillStyle = plain ? color : '#1b1e24'; c2.textBaseline = 'middle'; c2.fillText(text, 18, 38);
    const tex = new T.CanvasTexture(cv); tex.colorSpace = T.SRGBColorSpace; textures.push(tex);
    const sp = new T.Sprite(new T.SpriteMaterial({ map: tex, depthTest: false, transparent: true })); sp.scale.set(hgt * u * w / 72, hgt * u, 1); sp.position.set(x, y, z); sp.renderOrder = 10; scene.add(sp); return sp;
  }
  const bar = (a, b, w, hgt, y, m) => {   // tronçon droit entre deux points du sol
    const dx = b[0] - a[0], dz = b[1] - a[1], len = Math.hypot(dx, dz) || 0.01, o = add(new T.BoxGeometry(len, hgt, w), m, (a[0] + b[0]) / 2, y + hgt / 2, (a[1] + b[1]) / 2);
    o.rotation.y = -Math.atan2(dz, dx); return o;
  };
  const rod = (a, b, r, m) => {   // cylindre entre deux points 3D
    const va = new T.Vector3(...a), vb = new T.Vector3(...b), d = vb.clone().sub(va), len = d.length() || 0.01;
    const o = add(new T.CylinderGeometry(r * u, r * u, len, 10), m, (a[0] + b[0]) / 2, (a[1] + b[1]) / 2, (a[2] + b[2]) / 2); o.quaternion.setFromUnitVectors(new T.Vector3(0, 1, 0), d.normalize()); return o;
  };
  const wheel = (g, r, x, z) => { const o = add(new T.CylinderGeometry(r, r, 0.3, 14), mat('#1d1f23'), x, r, z, g); o.rotation.z = Math.PI / 2; };

  function vehicle(s, color) {
    const [, L, Wd, Hh] = VEHICLES[s.vkind], g = new T.Group(), body = mat(color), glass = mat('#dce6ef', 0.95);
    if (s.vkind === 'helico') {
      add(new T.SphereGeometry(1, 16, 12), body, 0, 3, 0, g).scale.set(Wd * 0.5, 1.25, L * 0.3);
      add(new T.BoxGeometry(0.5, 0.5, L * 0.42), body, 0, 3.2, L * 0.4, g); add(new T.BoxGeometry(L, 0.08, 0.5), mat('#2b2f36'), 0, 4.6, 0, g); add(new T.BoxGeometry(0.5, 0.08, L), mat('#2b2f36'), 0, 4.6, 0, g);
      add(new T.CylinderGeometry(0.1, 0.1, 1.6, 8), mat('#2b2f36'), 0, 3.8, 0, g); add(new T.BoxGeometry(Wd, 0.1, L * 0.5), mat('#2b2f36'), 0, 1.2, 0, g);
    } else if (s.vkind === 'bateau') {
      const sh = new T.Shape(); [[-Wd / 2, -L / 2], [Wd / 2, -L / 2], [Wd / 2, L * 0.2], [0, L / 2], [-Wd / 2, L * 0.2]].forEach(([x, y], i) => i ? sh.lineTo(x, y) : sh.moveTo(x, y));
      const hull = add(new T.ExtrudeGeometry(sh, { depth: Hh * 0.55, bevelEnabled: false }), body, 0, 0.2, 0, g); hull.rotation.x = -Math.PI / 2;
      add(new T.BoxGeometry(Wd * 0.6, Hh * 0.5, L * 0.25), glass, 0, Hh * 0.8, -L * 0.1, g);
    } else if (s.vkind === 'moto') {
      add(new T.BoxGeometry(0.35, 0.5, L * 0.55), body, 0, 0.65, 0, g); add(new T.BoxGeometry(Wd, 0.08, 0.1), mat('#1d1f23'), 0, 1.1, -L * 0.3, g);
      wheel(g, 0.32, 0, -L * 0.35); wheel(g, 0.32, 0, L * 0.35);
    } else {
      const truck = s.vkind === 'camion', hb = Hh * (truck ? 0.78 : 0.5);
      add(new T.BoxGeometry(Wd, hb, truck ? L * 0.66 : L), body, 0, 0.45 + hb / 2, truck ? L * 0.17 : 0, g);
      if (truck) add(new T.BoxGeometry(Wd, Hh * 0.55, L * 0.3), glass, 0, 0.45 + Hh * 0.275, -L * 0.35, g);
      else add(new T.BoxGeometry(Wd * 0.9, Hh * 0.38, L * (s.vkind === 'suv' ? 0.62 : 0.45)), glass, 0, 0.45 + hb + Hh * 0.19, L * 0.06, g);
      const r = truck ? 0.5 : 0.36; for (const sx of [-1, 1]) for (const sz of [-0.32, 0.32]) wheel(g, r, sx * (Wd / 2 - 0.05), sz * L);
    }
    return g;
  }

  // ---- formes du plan
  for (const s of data.shapes) {
    const color = colorOf(s, legend), pts = s.pts.map(p => P(p[0], p[1])), c0 = pts.reduce((a, p) => [a[0] + p[0] / pts.length, a[1] + p[1] / pts.length], [0, 0]);
    let tipY = 2.5;
    if (s.type === 'perimetre') {
      const hgt = s.height ?? 3.5; pts.forEach((p, i) => bar(p, pts[(i + 1) % pts.length], 0.35, hgt, 0, mat(color, 0.85))); tipY = hgt + 1.8;
    } else if (s.type === 'zone') {
      const hgt = Math.max(0.12, s.height ?? 0.3), sh = new T.Shape(); pts.forEach((p, i) => i ? sh.lineTo(p[0], -p[1]) : sh.moveTo(p[0], -p[1]));
      const o = add(new T.ExtrudeGeometry(sh, { depth: hgt, bevelEnabled: false }), mat(color, hgt > 1 ? 0.5 : 0.7), 0, 0.1, 0); o.rotation.x = -Math.PI / 2; tipY = hgt + 1.8;
    } else if (s.type === 'ligne') {
      for (let i = 0; i < pts.length - 1; i++) bar(pts[i], pts[i + 1], 0.5 * u, 0.25 * u, 0.12, mat(color));
    } else if (s.type === 'fleche') {
      const m = mat(color), y = 1.4, n = pts.length, last = pts[n - 1], prev = pts[n - 2], dl = Math.hypot(last[0] - prev[0], last[1] - prev[1]) || 1, hl = Math.min(2.6 * u, dl * 0.6);
      const stop = [last[0] - (last[0] - prev[0]) / dl * hl, last[1] - (last[1] - prev[1]) / dl * hl];
      for (let i = 0; i < n - 1; i++) rod([pts[i][0], y, pts[i][1]], i === n - 2 ? [stop[0], y, stop[1]] : [pts[i + 1][0], y, pts[i + 1][1]], 0.28, m);
      const cone = add(new T.ConeGeometry(0.95 * u, hl, 14), m, (stop[0] + last[0]) / 2, y, (stop[1] + last[1]) / 2); cone.quaternion.setFromUnitVectors(new T.Vector3(0, 1, 0), new T.Vector3(last[0] - stop[0], 0, last[1] - stop[1]).normalize()); tipY = y + 1.8;
    } else if (s.type === 'entree') {
      const m = mat(color); add(new T.BoxGeometry(0.25, 2.4, 0.25), m, c0[0] - 0.7, 1.2, c0[1]); add(new T.BoxGeometry(0.25, 2.4, 0.25), m, c0[0] + 0.7, 1.2, c0[1]); add(new T.BoxGeometry(1.65, 0.25, 0.25), m, c0[0], 2.4, c0[1]); tipY = 3.6;
    } else if (s.type === 'vehicule') {
      const g = vehicle(s, color); g.position.set(c0[0], 0, c0[1]); g.rotation.y = -((s.angle || 0) * Math.PI / 180); scene.add(g); tipY = VEHICLES[s.vkind][3] + (s.vkind === 'helico' ? 4.5 : 1.8);
    } else if (s.type === 'etiquette') { label(tipOf(s, legend), color, 2, c0[0], 3.4, c0[1]); continue; }
    else if (s.type === 'texte') { label(s.label, color, (s.size || 16) * 0.1, c0[0], 2.2, c0[1], true); continue; }
    if (s.label || s.role || s.team) label(tipOf(s, legend), color, 1.6, c0[0], tipY, c0[1]);
  }

  // ---- caméra, tailles, boutons
  const setView = k => {
    const d = span * 0.9; controls.target.set(0, 0, 0);
    if (k === 'top') camera.position.set(0, d * 1.25, 0.01); else if (k === 'front') camera.position.set(0, d * 0.3, d * 1.05); else camera.position.set(d * 0.35, d * 0.75, d * 0.85);
    camera.lookAt(0, 0, 0); controls.update(); render();
  };
  const fit = () => { const w = view.clientWidth || 640, hh = Math.max(320, Math.min(640, Math.round(w * 0.62))); renderer.setPixelRatio(Math.min(2, window.devicePixelRatio || 1)); renderer.setSize(w, hh, false); canvas.style.width = w + 'px'; canvas.style.height = hh + 'px'; camera.aspect = w / hh; camera.updateProjectionMatrix(); render(); };
  const ro = new ResizeObserver(fit); ro.observe(view);
  const zoom = f => { const v = camera.position.clone().sub(controls.target).multiplyScalar(f); camera.position.copy(controls.target).add(v); controls.update(); render(); };
  const png = () => {   // image du rendu avec titre et légende dessinés dessus
    render(); const cv = document.createElement('canvas'); cv.width = canvas.width; cv.height = canvas.height; const c2 = cv.getContext('2d'), k = cv.width / 900;
    c2.drawImage(canvas, 0, 0); c2.font = 'bold ' + 22 * k + 'px system-ui, sans-serif'; c2.fillStyle = 'rgba(255,255,255,.9)'; c2.fillRect(12 * k, 12 * k, c2.measureText(title).width + 24 * k, 36 * k); c2.fillStyle = '#1b1e24'; c2.textBaseline = 'middle'; c2.fillText(title, 24 * k, 31 * k);
    const teams = ['A', 'B', 'C', 'D'].filter(t => data.shapes.some(s => s.team === t)); c2.font = 'bold ' + 16 * k + 'px system-ui, sans-serif';
    teams.forEach((t, i) => { const y = cv.height - (teams.length - i) * 28 * k - 8 * k, nm = teamName(legend, t); c2.fillStyle = 'rgba(255,255,255,.9)'; c2.fillRect(12 * k, y, c2.measureText(nm).width + 48 * k, 24 * k); c2.fillStyle = teamColor(legend, t); c2.fillRect(20 * k, y + 5 * k, 14 * k, 14 * k); c2.fillStyle = '#1b1e24'; c2.fillText(nm, 42 * k, y + 13 * k); });
    cv.toBlob(b => b && saveBlob(b, 'plan-3d.png'));
  };
  const b = (txt, fn, icon) => h('button', { type: 'button', class: 'btn small', onclick: fn }, icon ? ico(icon, 16) : null, txt);
  host.replaceChildren(h('div', { class: 'p3d' }, h('div', { class: 'plan-row' }, b('Perspective', () => setView('persp')), b('Vue du dessus', () => setView('top')), b('Vue de face', () => setView('front')), b('Zoom +', () => zoom(0.8)), b('Zoom −', () => zoom(1.25)), b('Enregistrer l’image', png, 'telecharger')), view, status));
  fit(); setView('persp');
  status.textContent = 'Maquette générée depuis le plan : ' + data.shapes.length + ' forme(s), ' + data.layers.length + ' photo(s). Glisser pour tourner, molette pour zoomer, flèches pour déplacer.';
  canvas.dataset.ready = '1';
  return { destroy() { gone = true; ro.disconnect(); controls.dispose(); geos.forEach(g => g.dispose()); textures.forEach(t => t.dispose()); renderer.dispose(); } };
}
