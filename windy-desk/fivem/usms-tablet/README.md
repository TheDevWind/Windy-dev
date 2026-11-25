# usms-tablet : la tablette du U.S. Marshals Service

Ressource FiveM qui ouvre la console du service dans une tablette en jeu : le joueur la tient en main (animation et accessoire), elle affiche le logiciel avec l'habillage « U.S. Marshals Service », sans nom de produit tiers ni vocabulaire de jeu. Elle n'a aucune base de données : tout est dans le site.

> État : écrite et vérifiée côté site (chaîne tablette → console → fermeture testée dans un navigateur) et pour la syntaxe Lua. **Pas encore essayée dans FiveM** : les positions de l'accessoire et de l'animation sont à ajuster en jeu.

## Installation (serveur de test)

1. Copier le dossier `usms-tablet` dans `resources/`. **Garder ce nom** : il apparaît dans la règle de sécurité du site (voir plus bas).
2. Dans `server.cfg` : `ensure usms-tablet`.
3. Ouvrir `config.lua` et régler au minimum `Config.Url` : l'adresse du site, `?marque=usms`, et `&service=` suivi du code du service (celui de l'adresse du service sur le site).
4. En jeu : `/tablette` ou la touche F9 (modifiable dans les paramètres de touches). Échap, la croix, ou `/tablette` de nouveau range la tablette.

Un objet utilisable est possible (ESX ou QBCore) : `Config.Item = 'tablet'`, l'objet devant exister dans l'inventaire du serveur.

## Ce que le site doit autoriser

Le site refuse d'être affiché dans un cadre, sauf par cette ressource : `netlify.toml` contient `frame-ancestors 'self' https://cfx-nui-usms-tablet`. Si la ressource porte un autre nom, changer cette valeur et redéployer. Le nom ne peut pas contenir de tiret bas (`_`) : le navigateur refuse alors la règle.

## Adapter à un autre serveur

1. **Adresse** : `Config.Url` (site, habillage, service).
2. **Qui peut l'ouvrir** : `Config.AllowedJobs` (noms de métiers) ; si le framework n'est ni ESX ni QBCore, adapter la fonction `Bridge.GetJob()` dans `bridge.lua`, c'est le seul fichier lié au serveur.
3. **Règle de sécurité du site** : voir ci-dessus, si le nom de la ressource change.
4. **Comptes** : chaque agent se connecte dans la tablette avec son identifiant et son mot de passe (connexion Discord retirée dans cet habillage). Une connexion automatique à partir de l'identité en jeu est possible plus tard, avec un petit service côté serveur.
5. **Données du serveur** (personnages, véhicules, métiers) : non incluses. Elles passeraient par un point d'entrée du site auquel le serveur envoie ses données : voir `MISE-EN-LIGNE.md`.

## Contenu

| Fichier | Rôle |
|---|---|
| `fxmanifest.lua` | Déclaration de la ressource |
| `config.lua` | Tous les réglages |
| `bridge.lua` | Lien avec le framework (métier du joueur) |
| `client.lua` | Ouverture, fermeture, animation, accessoire, commande et touche |
| `server.lua` | Objet utilisable (facultatif) |
| `ui/` | Cadre de la tablette, horloge, chargement, fermeture |

## Sécurité

- La console ne peut demander à la tablette que de se fermer, et seulement depuis l'adresse du site configurée. Elle ne peut pas faire ouvrir une autre adresse.
- Aucun mot de passe ni donnée n'est stocké par la ressource.
- Le site, lui, reste protégé par ses règles d'accès habituelles : un compte ne voit que son service.

## Dépannage

- **Écran noir ou message « La console ne répond pas »** : le site est injoignable depuis le jeu, ou la règle `frame-ancestors` ne correspond pas au nom de la ressource.
- **La session ne se garde pas d'une ouverture à l'autre** : à signaler, le stockage du navigateur intégré de FiveM peut varier selon la version.
- **Accessoire mal placé** : régler `Config.Prop.pos` et `Config.Prop.rot`.
