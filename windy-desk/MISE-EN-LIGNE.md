# Windy Desk · mise en ligne

Windy Desk est un produit **Windy Project** (direction artistique : le papillon, Cormorant Garamond, lavande, crème et sauge).
Le site est statique (dossier `web/`, publié par Netlify) et parle directement à Supabase.
Toute la sécurité est dans la base : règles RLS en lecture, fonctions SQL pour chaque écriture.

| Quoi | Où |
|---|---|
| Site | https://desk.windydev.fr (projet Netlify `windy-desk`, aussi joignable sur https://windy-desk.netlify.app) |
| Base Supabase | projet « Comptoir » (`vevezyxavdfykvcbsiws`, l’ancien nom du produit ; le nom du projet Supabase n’apparaît nulle part sur le site) |
| Schéma | `supabase/migrations/` (5 migrations, déjà appliquées) |
| Réglages publics du site | `web/config.js` (adresse Supabase et clé *publishable*, faite pour être publique) |
| Réglages Netlify | `netlify.toml` **à la racine du dépôt** (dossier `windy-desk`, redirections, en-têtes de sécurité) |

## 1. Publier le site sur Netlify

Le projet Netlify **windy-desk** est relié au dépôt GitHub `TheDevWind/Windy-dev`, branche `claude/practical-knuth-t355tp` :
chaque `git push` republie le site. Rien à régler dans le tableau de bord : le `netlify.toml` de la racine indique le dossier
(`windy-desk`), le dossier publié (`web`), les redirections et les en-têtes. Laisser *Base directory* et *Build command* vides.

**Le nom de domaine** `desk.windydev.fr` :

1. Netlify → projet **windy-desk** → *Domain management* → *Add a domain* → `desk.windydev.fr`.
2. Si `windydev.fr` est géré par Netlify DNS, l’enregistrement est créé tout seul. Sinon, chez le registraire du domaine :
   enregistrement **CNAME** `desk` → `windy-desk.netlify.app`.
3. Le certificat HTTPS (Let’s Encrypt) arrive tout seul quelques minutes après. Le code ne contient aucune adresse de site :
   rien à changer dans le dépôt si le domaine change un jour.

## 2. Régler la connexion dans Supabase

Tableau de bord Supabase → projet **Comptoir** → *Authentication*.

1. **URL Configuration**
   - *Site URL* : `https://desk.windydev.fr`
   - *Redirect URLs* : ajouter `https://desk.windydev.fr/**` et `https://windy-desk.netlify.app/**`
2. **Sign In / Providers → Email**
   - décocher **Confirm email** (les identifiants sont des adresses internes du type `gino.romano.bella-napoli@comptes.windydev.fr` : aucun e-mail n’est jamais envoyé) ;
   - *Minimum password length* : `8` (le site demande déjà 8 caractères).
3. **Sign In / Providers → Discord**
   1. Sur https://discord.com/developers/applications : *New Application* (nom : Windy Desk) → *OAuth2*.
   2. Dans *Redirects*, ajouter : `https://vevezyxavdfykvcbsiws.supabase.co/auth/v1/callback`
   3. Copier le *Client ID*, puis *Reset Secret* pour obtenir le *Client Secret*.
   4. Les coller dans Supabase (Discord → *Enable*). Le secret ne se colle **que** là, jamais dans le code ni dans une discussion.

## 3. Ouvrir les accès éditeur et fisc

Les rôles de plateforme se donnent par un lien à usage unique, valable 7 jours :
`https://desk.windydev.fr/reclamer/<jeton>`.
La personne ouvre le lien, choisit un identifiant et un mot de passe (ou Discord), et le rôle est attribué.

Pour créer un nouveau lien : Supabase → *SQL Editor* :

```sql
insert into public.platform_claims (role, display_name)
values ('fisc', 'Inspecteur Garnier')   -- ou ('editeur', 'Windy Dev')
returning 'https://desk.windydev.fr/reclamer/' || token as lien;
```

Le nom affiché (`display_name`) apparaît dans le journal des entreprises à chaque consultation ou contrôle du fisc.

## 4. Premier client

Espace éditeur → **Nouveau client** : adresse (`/bella-napoli`), patron, modules, durée de licence.
Le site donne le lien d’activation du patron (48 h, usage unique). Le patron invite ensuite son équipe depuis **Équipe**.

## Les modules

Chaque module apparaît dans le menu d’une entreprise s’il est dans son offre (éditeur → *Modules*) **et** si le grade
de la personne a l’accès voulu (entreprise → *Réglages* → grades). La base vérifie les deux à chaque action.

| Module | Ce qu’il fait | Accès du grade |
|---|---|---|
| Stock | Articles, emplacements, seuils, entrées / sorties / pertes ; un achat payé part en dépense | Gérer le stock |
| Fabrication | Recettes et fournées ; coût de revient calculé | Fabriquer |
| Récolte | Chacun saisit sa récolte, qui entre en stock ; classement et primes au rendement | tout le monde (primes : Payer les salaires) |
| Caisse | Carte, tickets, carte ou espèces ; un produit avec recette se prépare à la commande | Encaisser |
| Factures | Factures PNG/PDF ; entre deux entreprises Windy Desk, le paiement entre dans les deux comptabilités | Comptabilité |
| Service et paie | Prise et fin de service, taux horaire, paie en un clic (12 h max par service) | tout le monde (paie : Payer les salaires) |
| Dons | Dons en argent (comptabilité) ou en nature (stock), distributions | Gérer le stock |
| Employés | Fiche (téléphone, contrat, permis), avertissements, notes, départ | Gérer l’équipe |
| Véhicules | Sorties, retours (carburant, état, km), plein, réparation, assurance | Véhicules |
| Événements | Page dédiée : compte à rebours, objectif, équipe, stock réservé, tâches, bilan ; les ventes et dons pendant l’événement s’y rattachent | Événements |

## Services publics (US Marshals)

- **Créer le service** : *Éditeur → Clients → Nouveau client*, choisir « Service public (US Marshals) ». Grades (Director,
  Chief Deputy, Supervisory, Inspector, Deputy, Recrue) avec leurs places, unités IOD, POD, JSD, SOG, WITSEC et qualifications
  sont préremplis ; le lien d’activation va au Director.
- **Compte Justice** : *Éditeur → Accès*, rôle « Justice ». Le lien (7 jours, usage unique) ouvre l’espace `/justice`,
  où les demandes de mandat de tous les services sont signées ou refusées.
- **Page publique** : `/avis/<adresse du service>` montre les affiches publiées et reçoit les signalements anonymes
  (30 par heure et par service au plus).
- **Rapports** : numérotés par type (`USMS-A-0001`, `USMS-SOG-001`…), validés par un grade qui a l’accès « Valider ».
  L’amende et la peine sont recopiées du code pénal du service (Rapports → Code pénal, modifiable avec l’accès « Réglages »).
  Un rapport où une arme à feu a servi ouvre un dossier Affaires internes, visible du Director (accès « Affaires internes »).
- **Recherche** : nom, surnom, téléphone, plaque, numéro de dossier, de mandat, de rapport ou de scellé. Les véhicules et
  organisations des fiches individus alimentent tout seuls le fichier des véhicules et celui des organisations.
- **Signatures** : à sa première connexion, chaque agent écrit son nom de signature et choisit un des 6 modèles
  (modifiable ensuite depuis sa fiche, bouton « Ma signature »). La signature de l’auteur est apposée à la soumission,
  celle du superviseur quand il valide : un rapport garde ses signatures même si l’agent change la sienne plus tard.
- **Transferts (POD)** : ordres numérotés `USMS-TR-0001`, convoi alerté, itinéraires lus seulement par le convoi et
  l’accès « Transferts ». À l’arrivée, le registre des détenus suit ; une évasion ouvre un dossier Affaires internes
  et alerte le Director.
- **Tribunal (JSD)** : audiences, plan de sécurité (agents alertés), magistrats protégés et menaces (accès « Tribunal ») ;
  tout agent peut signaler une menace.
- **WITSEC** : visible seulement de l’unité WITSEC et du Director. Témoins sous code (`WS-ALPHA-01`), vrai nom
  révélé uniquement avec un motif ; chaque consultation et chaque révélation est journalisée et alerte le Director.
- **SOG** : opérateurs (test de sélection, recertification tous les 60 jours), activation par l’accès « SOG »
  (2 sorties par semaine au plus) qui alerte tous les opérateurs, briefing avec carte, retour d’expérience.
- **Renseignement** : informateurs sous nom de code, paiements, notes classées Diffusion, Confidentiel, Secret,
  Très secret (les deux dernières réservées à l’accès « Renseignement »), graphe des relations.
- **Alertes** : affichées sur le tableau de bord de chaque agent (l’envoi sur Discord viendra avec l’intégration).
- **Page publique** : `/avis/<adresse>` (avis de recherche), `/avis/<adresse>/candidature` (formulaire de candidature,
  20 par heure au plus, une seule en cours par compte Discord) et `/avis/<adresse>/plainte` (plainte contre un agent,
  lue par les Affaires internes seules). Le logo du service s’envoie dans *Réglages → Identité du service*.
- **Académie** : candidatures (entretien inscrit au planning du recruteur, « Accepter et recruter » crée le compte),
  parcours des recrues (modules validés par un FTO, formateur attribué), bibliothèque, examens QCM corrigés par le serveur
  (réponses jamais envoyées au navigateur, nouvelle tentative 24 h après un échec), évaluations terrain, entraînements
  avec appel, propositions de promotion avec les statistiques de l’agent. L’ancienneté requise se règle par grade
  (*Réglages → Grades*) ; seul le Director peut passer outre.
- **Affaires internes** : visibles du Director, des grades qui ont l’accès « Affaires internes » et de la personne que le
  Director désigne. Un dossier s’ouvre seul pour un tir, une évasion ou un témoin WITSEC perdu. Les sanctions s’appliquent
  tout de suite : une suspension coupe tout accès au MDT jusqu’à son terme (ou jusqu’à ce qu’elle soit levée), une
  rétrogradation fait descendre d’un grade, un renvoi désactive le compte. Un dossier qui vise l’enquêteur lui reste fermé.
- **Logistique** : armes par numéro de série (arme lourde réservée au SOG), équipement en stock, parc automobile
  (blindé réservé au SOG, réservations, dégâts signalés au retour).
- **Carte** : l’image de la carte de ton serveur s’envoie dans *Réglages → Carte de la ville* ; les repères se placent
  en un clic. Les planques WITSEC n’apparaissent qu’à ceux qui ont l’accès WITSEC.
- **Sauvegarde** : *Réglages → Sauvegarde* télécharge toutes les données du service (sauf les vrais noms WITSEC).
- **Fichiers** : deux espaces de stockage Supabase. `mdt` (privé, 50 Mo par fichier) pour les photos et pièces jointes,
  lus par liens signés d’une heure ; `avis` (public, 5 Mo) pour les affiches. Rien ne se supprime depuis le site.

## Bon à savoir

- **Mot de passe oublié** : le patron clique « Nouveau lien » sur la personne (page Équipe). Avec Discord, rien d’autre à faire.
  Avec un mot de passe, l’identifiant reste pris dans Supabase : supprimer d’abord l’ancien compte dans
  *Authentication → Users* (adresse `identifiant.entreprise@comptes.windydev.fr`), puis envoyer le nouveau lien.
- **Comptabilité immuable** : une opération ne se modifie ni ne se supprime ; on saisit l’opération inverse.
- **Barème** : modifiable par le fisc (*Barème*), appliqué aux déclarations suivantes.

## Tests

```bash
npm install          # PGlite pour les tests de la base
npm test             # 45 tests de la base (Postgres en mémoire, mêmes migrations)
npm run test:site    # scénario complet (entreprises et US Marshals) dans Chromium + revue de 328 pages (Playwright requis)
npm run serve        # le site en local sur http://localhost:8080, branché sur le vrai Supabase
```

## Carte : tuiles de l’image satellite

La page Carte utilise Leaflet (copié dans `web/vendor/leaflet/`, aucune ressource externe). Sans tuiles, elle affiche l’image envoyée dans Réglages ; avec des tuiles dans `web/tiles/gta5/`, elle les préfère.

```bash
npm install --no-save sharp
node scripts/make-tiles.mjs assets-src/gta5-satellite.jpg --calib minX,maxX,minY,maxY
```

`--calib` donne les coordonnées de jeu (en mètres) des bords de l’image : sans lui, un étalonnage provisoire est utilisé (voir `web/js/map-geo.js`). L’image source reste dans `assets-src/`, hors de `web/`, donc non déployée.
