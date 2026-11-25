# usms-tablet : la tablette du U.S. Marshals Service

Une tablette en jeu, dans le style d'un iPad : écran d'accueil avec widgets et dock, applications qui s'ouvrent et se ferment avec des animations, thème sombre ou clair, fonds d'écran. Elle contient la **console du service** (le site, avec l'habillage U.S. Marshals, sans nom de produit tiers ni vocabulaire de jeu) et des applications intégrées.

> État : écrite et testée côté navigateur (chaque application, l'appareil photo simulé, la fermeture, la sécurité du cadre) et pour la syntaxe Lua. Les parties qui touchent au jeu (animation, accessoire, position, météo, appareil photo) sont à vérifier en jeu.

## Applications

| Application | Ce qu'elle fait |
|---|---|
| **Console** | Le logiciel du service, dans la tablette. Reste chargé entre deux ouvertures. |
| **Notes** | Liste et éditeur, épinglage, recherche, enregistrement automatique. |
| **Appareil photo** | Passe en vue à la première personne : Entrée prend la photo, Retour arrière quitte. Demande `screenshot-basic` (voir plus bas). |
| **Photos** | Galerie des photos prises, visionneuse, enregistrement d'une image, suppression. |
| **Calculatrice** | Style iOS, clavier, historique. |
| **Horloge** | Heure (analogique et numérique, avec l'heure du jeu), minuteur qui sonne même application fermée, chronomètre avec tours. |
| **Localisation** | Position en direct (rue, quartier, coordonnées), boussole, copie des coordonnées, pose d'un repère sur la carte du jeu. |
| **Codes radio** | Aide-mémoire hors ligne, recherche, copie au toucher. |
| **Rappels** | Liste de choses à faire. |
| **Contacts** | Petit répertoire. |
| **Réglages** | Fond d'écran, apparence (sombre, clair, automatique selon l'heure du jeu), luminosité. |

Notes, rappels, contacts, photos et réglages sont gardés **sur le poste de jeu du joueur** (stockage du navigateur intégré de FiveM) : ils ne suivent pas un autre ordinateur ni un autre personnage.

## Installation (serveur de test)

1. Copier le dossier `usms-tablet` dans `resources/`. **Garder ce nom** : il apparaît dans la règle de sécurité du site (voir plus bas).
2. Dans `server.cfg` : `ensure usms-tablet`.
3. Ouvrir `config.lua` et régler au minimum `Config.Url` : l'adresse du site, `?marque=usms`, et `&service=` suivi du code du service.
4. **Appareil photo** : installer la ressource gratuite [screenshot-basic](https://github.com/citizenfx/screenshot-basic) et la démarrer avant `usms-tablet` (`ensure screenshot-basic`). Sans elle, l'application s'ouvre puis se referme avec un message, le reste fonctionne.
5. En jeu : `/tablette` ou F9 (touche modifiable dans les paramètres de touches du jeu). Échap revient à l'accueil puis range la tablette ; le bouton d'alimentation, en haut à droite, la range aussi.

Un objet utilisable est possible (ESX ou QBCore) : `Config.Item = 'tablet'`, l'objet devant exister dans l'inventaire du serveur.

## Ce que le site doit autoriser

Le site refuse d'être affiché dans un cadre, sauf par cette ressource : `netlify.toml` contient `frame-ancestors 'self' https://cfx-nui-usms-tablet nui:`. FiveM affiche la ressource dans une page racine interne (`nui://game`), d'où `nui:`. Si la ressource porte un autre nom, changer `cfx-nui-<nom>` et redéployer. Le nom ne peut pas contenir de tiret bas (`_`) : le navigateur refuse alors la règle.

## Adapter à un autre serveur

1. **Adresse** : `Config.Url` (site, habillage, service).
2. **Qui peut l'ouvrir** : `Config.AllowedJobs` (noms de métiers) ; si le framework n'est ni ESX ni QBCore, adapter `Bridge.GetJob()` dans `bridge.lua`, le seul fichier lié au serveur.
3. **Règle de sécurité du site** : voir ci-dessus, si le nom de la ressource change.
4. **Comptes** : chaque agent se connecte dans la console avec son identifiant et son mot de passe. Une connexion automatique à partir de l'identité en jeu est possible plus tard.
5. **Données du serveur** (personnages, véhicules, métiers) : non incluses.

## Contenu

| Fichier | Rôle |
|---|---|
| `fxmanifest.lua`, `config.lua` | Déclaration et réglages |
| `bridge.lua` | Lien avec le framework (métier du joueur) |
| `client.lua` | Ouverture, animation, accessoire, position, météo, mode photo |
| `server.lua` | Objet utilisable (facultatif) |
| `ui/index.html`, `ui/css/`, `ui/js/` | Interface : écran d'accueil, fenêtres, applications (`ui/js/apps/`) |
| `ui/fonts/`, `ui/img/` | Police Inter (licence OFL) et sceau |

## Sécurité

- La console ne peut demander à la tablette que de revenir à l'accueil, et seulement depuis l'adresse du site configurée. Elle ne peut pas faire ouvrir une autre adresse ni fermer la tablette.
- Aucun mot de passe n'est stocké par la ressource.
- Le site reste protégé par ses règles d'accès : un compte ne voit que son service.

## Dépannage

- **Cadre vide ou « La console ne répond pas »** : mettre `Config.Debug = true` : le diagnostic de Réglages et de la console indique l'origine, les ancêtres du cadre et si le site est joignable depuis le jeu.
- **« Appareil photo indisponible »** : `screenshot-basic` n'est pas démarré.
- **Accessoire mal placé** : régler `Config.Prop.pos` et `Config.Prop.rot`.
- **Tablette trop petite ou trop grande** : elle s'adapte à la taille de la fenêtre du jeu.
