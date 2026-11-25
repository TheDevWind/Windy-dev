// Horloge : heure (analogique et numérique, avec l'heure du jeu), minuteur qui sonne même application fermée, chronomètre avec tours.
import { html, esc, pad } from './kit.js';

let ring = null, timer = null, ctxRef = null;   // le minuteur vit hors de l'application : il continue quand on la ferme
function beep() {
  try {
    const a = new (window.AudioContext || window.webkitAudioContext)();
    [0, .28, .56].forEach(t => { const o = a.createOscillator(), g = a.createGain(); o.frequency.value = 880; o.connect(g); g.connect(a.destination); g.gain.setValueAtTime(.0001, a.currentTime + t); g.gain.exponentialRampToValueAtTime(.25, a.currentTime + t + .02); g.gain.exponentialRampToValueAtTime(.0001, a.currentTime + t + .22); o.start(a.currentTime + t); o.stop(a.currentTime + t + .25); });
    setTimeout(() => a.close(), 1200);
  } catch { /* pas de son disponible */ }
}
const mmss = ms => { const s = Math.max(0, Math.ceil(ms / 1000)); return pad(Math.floor(s / 60)) + ':' + pad(s % 60); };

export default {
  id: 'clock', name: 'Horloge', icon: 'clock', grad: ['#3a3f4b', '#14171d'], bar: 'light', bg: '#0b0d12',
  mount(el, ctx) {
    const { glyph, world } = ctx; ctxRef = ctx;
    let tab = 'heure', raf = 0, chrono = { run: false, t0: 0, acc: 0, laps: [] };
    const ticks = Array.from({ length: 60 }, (_, i) => `<line x1="150" y1="${i % 5 ? 14 : 8}" x2="150" y2="${i % 5 ? 20 : 26}" transform="rotate(${i * 6} 150 150)" class="${i % 5 ? 't' : 'T'}"/>`).join('');
    const nums = Array.from({ length: 12 }, (_, i) => { const a = (i + 1) * Math.PI / 6; return `<text x="${150 + Math.sin(a) * 108}" y="${150 - Math.cos(a) * 108 + 8}" text-anchor="middle">${i + 1}</text>`; }).join('');
    el.append(html(`<div class="clk"><nav class="seg top" role="tablist" aria-label="Horloge"><button role="tab" data-t="heure" class="on">Heure</button><button role="tab" data-t="minuteur">Minuteur</button><button role="tab" data-t="chrono">Chronomètre</button></nav>
      <section class="pane" data-p="heure"><svg class="face" viewBox="0 0 300 300" aria-hidden="true"><circle cx="150" cy="150" r="146" class="rim"/>${ticks}${nums}<line id="h-h" x1="150" y1="150" x2="150" y2="84" class="hand h"/><line id="h-m" x1="150" y1="150" x2="150" y2="52" class="hand m"/><line id="h-s" x1="150" y1="168" x2="150" y2="40" class="hand s"/><circle cx="150" cy="150" r="7" class="cap"/></svg>
        <div class="digital" id="h-d"></div><div class="dl" id="h-date"></div><div class="dl game" id="h-game"></div></section>
      <section class="pane" data-p="minuteur" hidden><div class="tring"><svg viewBox="0 0 200 200" aria-hidden="true"><circle cx="100" cy="100" r="92" class="bg"/><circle cx="100" cy="100" r="92" class="fg" id="t-ring" pathLength="100"/></svg><div class="tt" id="t-time">05:00</div></div>
        <div class="presets" id="t-pre">${[1, 5, 10, 15, 30].map(m => `<button type="button" data-m="${m}">${m} min</button>`).join('')}</div>
        <div class="ctl"><button type="button" class="round" id="t-minus" aria-label="Retirer une minute">${glyph('arrow-left', 20, 2)}</button><button type="button" class="cbtn" id="t-go">Démarrer</button><button type="button" class="round" id="t-plus" aria-label="Ajouter une minute">${glyph('arrow-right', 20, 2)}</button></div></section>
      <section class="pane" data-p="chrono" hidden><div class="digital big" id="c-d">00:00,00</div><div class="ctl"><button type="button" class="round" id="c-lap" disabled>Tour</button><button type="button" class="cbtn" id="c-go">Démarrer</button><button type="button" class="round" id="c-rst">${glyph('refresh', 20, 2)}</button></div><ol class="laps" id="c-laps"></ol></section></div>`));
    const $ = s => el.querySelector(s);
    // ---- heure
    const hands = { h: $('#h-h'), m: $('#h-m'), s: $('#h-s') };
    // ---- minuteur
    let setMs = (timer && timer.total) || 5 * 60000;
    const tState = () => timer && !timer.paused ? Math.max(0, timer.end - Date.now()) : timer ? timer.left : setMs;
    function startTimer() {
      if (timer && timer.paused) { timer.end = Date.now() + timer.left; timer.paused = false; arm(); return; }
      timer = { total: setMs, end: Date.now() + setMs, paused: false }; arm();
    }
    function arm() { clearTimeout(ring); ring = setTimeout(() => { beep(); ctxRef.toast('Minuteur terminé', 'alarm'); timer = null; ring = null; }, timer.end - Date.now()); }
    $('#t-go').addEventListener('click', () => { if (!timer) startTimer(); else if (timer.paused) startTimer(); else { timer.left = timer.end - Date.now(); timer.paused = true; clearTimeout(ring); } });
    $('#t-pre').addEventListener('click', e => { const b = e.target.closest('[data-m]'); if (b && !timer) { setMs = b.dataset.m * 60000; } });
    $('#t-minus').addEventListener('click', () => { if (timer) { clearTimeout(ring); timer = null; } else setMs = Math.max(60000, setMs - 60000); });   // à l'arrêt : retire une minute ; en marche : annule
    $('#t-plus').addEventListener('click', () => { if (!timer) setMs = Math.min(99 * 60000, setMs + 60000); else { timer.end += 60000; if (timer.paused) timer.left += 60000; else arm(); timer.total += 60000; } });
    // ---- chronomètre
    const cNow = () => chrono.acc + (chrono.run ? performance.now() - chrono.t0 : 0);
    const cfmt = ms => { const c = Math.floor(ms / 10) % 100, s = Math.floor(ms / 1000) % 60, m = Math.floor(ms / 60000); return pad(m) + ':' + pad(s) + ',' + pad(c); };
    $('#c-go').addEventListener('click', () => { if (chrono.run) { chrono.acc = cNow(); chrono.run = false; } else { chrono.t0 = performance.now(); chrono.run = true; } });
    $('#c-lap').addEventListener('click', () => { if (chrono.run) { chrono.laps.unshift(cNow()); paintLaps(); } });
    $('#c-rst').addEventListener('click', () => { chrono = { run: false, t0: 0, acc: 0, laps: [] }; paintLaps(); });
    function paintLaps() { $('#c-laps').innerHTML = chrono.laps.map((t, i) => `<li><span>Tour ${chrono.laps.length - i}</span><b>${cfmt(t - (chrono.laps[i + 1] || 0))}</b><i>${cfmt(t)}</i></li>`).join(''); }
    // ---- onglets et dessin
    el.querySelector('.seg').addEventListener('click', e => { const b = e.target.closest('[data-t]'); if (!b) return; tab = b.dataset.t; el.querySelectorAll('.seg button').forEach(x => x.classList.toggle('on', x === b)); el.querySelectorAll('.pane').forEach(p => { p.hidden = p.dataset.p !== tab; }); });
    function frame() {
      const d = new Date(), ms = d.getMilliseconds(), s = d.getSeconds() + ms / 1000, m = d.getMinutes() + s / 60, hh = d.getHours() % 12 + m / 60;
      hands.s.setAttribute('transform', `rotate(${s * 6} 150 150)`); hands.m.setAttribute('transform', `rotate(${m * 6} 150 150)`); hands.h.setAttribute('transform', `rotate(${hh * 30} 150 150)`);
      $('#h-d').textContent = pad(d.getHours()) + ':' + pad(d.getMinutes()) + ':' + pad(d.getSeconds());
      $('#h-date').textContent = d.toLocaleDateString('fr-FR', { weekday: 'long', day: 'numeric', month: 'long' });
      $('#h-game').textContent = 'Heure du jeu : ' + (world.hour === null ? '—' : pad(world.hour) + ':' + pad(world.minute));
      const left = tState(), total = timer ? timer.total : setMs;
      $('#t-time').textContent = mmss(left); $('#t-ring').style.strokeDashoffset = String(100 - (timer ? left / total * 100 : 100));
      $('#t-go').textContent = !timer ? 'Démarrer' : timer.paused ? 'Reprendre' : 'Pause'; $('#t-go').classList.toggle('stop', !!timer && !timer.paused);
      $('#t-minus').innerHTML = timer ? glyph('x', 20, 2) : glyph('arrow-left', 20, 2); $('#t-minus').setAttribute('aria-label', timer ? 'Annuler le minuteur' : 'Retirer une minute');
      $('#c-d').textContent = cfmt(cNow()); $('#c-go').textContent = chrono.run ? 'Arrêter' : chrono.acc ? 'Reprendre' : 'Démarrer'; $('#c-go').classList.toggle('stop', chrono.run); $('#c-lap').disabled = !chrono.run;
      raf = requestAnimationFrame(frame);
    }
    paintLaps(); frame();
    return () => cancelAnimationFrame(raf);
  },
};
