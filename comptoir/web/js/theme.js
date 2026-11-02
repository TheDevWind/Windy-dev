// Applique le thème choisi avant le premier affichage (sinon l'écran clignote).
(function () {
  try {
    var t = localStorage.getItem('comptoir-theme');
    if (t === 'dark' || t === 'light') document.documentElement.setAttribute('data-theme', t);
  } catch (e) { /* stockage indisponible : on suit le réglage du système */ }
})();
