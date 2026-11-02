# Windy Desk · mise en ligne

Windy Desk est un produit **Windy Project** (direction artistique : le papillon, Cormorant Garamond, lavande, crème et sauge).
Le site est statique (dossier `web/`, publié par Netlify) et parle directement à Supabase.
Toute la sécurité est dans la base : règles RLS en lecture, fonctions SQL pour chaque écriture.

| Quoi | Où |
|---|---|
| Site | https://desk.windydev.fr (projet Netlify `windy-desk`, aussi joignable sur https://windy-desk.netlify.app) |
| Base Supabase | projet « Comptoir » (`vevezyxavdfykvcbsiws`, l’ancien nom du produit ; le nom du projet Supabase n’apparaît nulle part sur le site) |
| Schéma | `supabase/migrations/` (4 migrations, déjà appliquées) |
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

## Bon à savoir

- **Mot de passe oublié** : le patron clique « Nouveau lien » sur la personne (page Équipe). Avec Discord, rien d’autre à faire.
  Avec un mot de passe, l’identifiant reste pris dans Supabase : supprimer d’abord l’ancien compte dans
  *Authentication → Users* (adresse `identifiant.entreprise@comptes.windydev.fr`), puis envoyer le nouveau lien.
- **Comptabilité immuable** : une opération ne se modifie ni ne se supprime ; on saisit l’opération inverse.
- **Barème** : modifiable par le fisc (*Barème*), appliqué aux déclarations suivantes.

## Tests

```bash
npm install          # PGlite pour les tests de la base
npm test             # 10 tests de la base (Postgres en mémoire, mêmes migrations)
npm run test:site    # scénario complet dans Chromium + revue de 80 pages (Playwright requis)
npm run serve        # le site en local sur http://localhost:8080, branché sur le vrai Supabase
```
