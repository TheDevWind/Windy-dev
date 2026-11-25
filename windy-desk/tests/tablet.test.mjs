// Tablette en jeu : cœur de la calculatrice (calculs, pourcentages, erreurs, égal répété, affichage).
import test from 'node:test';
import assert from 'node:assert/strict';
import { createCalc, pretty } from '../fivem/usms-tablet/ui/js/calc-core.js';

const run = (...keys) => {
  const c = createCalc();
  for (const k of keys) {
    if (/^[0-9.]$/.test(k)) c.digit(k);
    else if ('+−×÷'.includes(k)) c.operator(k);
    else c[{ '=': 'equals', '%': 'percent', '±': 'negate', C: 'clear', '⌫': 'backspace' }[k]]();
  }
  return c;
};
const val = (...keys) => run(...keys).state().display;

test('calculatrice : les quatre opérations et l’enchaînement immédiat', () => {
  assert.equal(val('1', '2', '+', '3', '='), '15');
  assert.equal(val('9', '−', '4', '×', '3', '='), '15');   // (9 − 4) × 3 : chaque opérateur résout le précédent
  assert.equal(val('8', '÷', '4', '='), '2');
  assert.equal(val('0', '.', '1', '+', '0', '.', '2', '='), '0.3');   // pas de 0.30000000000000004
});

test('calculatrice : égal répété, pourcentage, signe, effacement', () => {
  assert.equal(val('2', '+', '3', '=', '='), '8');   // 5 puis 5 + 3
  assert.equal(val('5', '0', '+', '1', '0', '%', '='), '55');   // 10 % de 50 ajouté
  assert.equal(val('2', '0', '0', '×', '5', '%', '='), '10');
  assert.equal(val('7', '±'), '-7'); assert.equal(val('7', '±', '±'), '7');
  assert.equal(val('1', '2', '3', '⌫'), '12'); assert.equal(val('5', '⌫'), '0');
  const c = run('4', '2'); assert.equal(c.state().clearLabel, 'C'); c.clear(); assert.equal(c.state().clearLabel, 'AC'); assert.equal(c.state().display, '0');
});

test('calculatrice : erreur de division, reprise, limites', () => {
  const c = run('5', '÷', '0', '=');
  assert.equal(c.state().display, 'Erreur');
  c.digit('7'); assert.equal(c.state().display, '7');   // une frappe repart à zéro
  assert.equal(val('1', '÷', '3', '='), '0.3333333333');
  assert.equal(val('1', '.', '.', '5'), '1.5');   // un seul point
  assert.equal(run(...'1234567890123456'.split('')).state().display.length, 12);   // 12 chiffres au plus
  assert.equal(run('2', '+').state().op, '+');   // l'opérateur en attente est signalé
});

test('calculatrice : affichage à la française', () => {
  assert.equal(pretty('1234567.5'), '1 234 567,5');
  assert.equal(pretty('-1000'), '−1 000');
  assert.equal(pretty('0.3333'), '0,3333');
  assert.equal(pretty('Erreur'), 'Erreur');
});

// Le navigateur intégré de FiveM est un Chromium ancien : certaines fonctions CSS récentes y sont ignorées, la couleur disparaît (pastilles sans fond, barre d'accueil invisible…).
import { readFileSync, readdirSync } from 'node:fs';
const css = dir => readdirSync(new URL(dir, import.meta.url)).filter(f => f.endsWith('.css')).map(f => [dir + f, readFileSync(new URL(dir + f, import.meta.url), 'utf8')]);
test('compatibilité FiveM : pas de CSS trop récent dans la tablette ni dans le site', () => {
  const BAD = [/color-mix\(\s*in/, /@container/, /@layer\b/, /@property\b/];   // ignorées sans repli = couleur ou mise en page perdue (dvh et text-wrap ont un repli ou sont décoratifs)
  for (const [f, s] of [...css('../fivem/usms-tablet/ui/css/'), ...css('../web/css/')]) {
    const code = s.replace(/\/\*[\s\S]*?\*\//g, '');
    for (const re of BAD) assert.ok(!re.test(code), f + ' : ' + re);
  }
  for (const [f, s] of css('../fivem/usms-tablet/ui/css/')) assert.ok(!/:has\(/.test(s.replace(/\/\*[\s\S]*?\*\//g, '')), f + ' : :has()');
});
