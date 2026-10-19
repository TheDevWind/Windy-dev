# WindyClans 1.10.0 — code source reconstruit

Source recompilée à partir de WindyClans-1.8.6.jar (décompilé), puis modifiée pour la 1.9.0 :
- elixir/ElixirFeature.java  : magnétite infusée, autels reliables à la baguette, API fluide (linked, fluidAvailable, drawFluid)
- ritual/RitualManager.java  : autel reconnu seulement avec 4 améthystes infusées, tier plafonné, coût en fluide
- profile/Awakening.java     : sanctuaire reconnu seulement avec un cœur en magnétite infusée, coût en fluide
- ritual/CustomItemGuard.java: autorise la pose de la magnétite infusée

Compiler : `mvn package` (Java 21) -> target/WindyClans-1.9.0.jar
Les noms de variables locales viennent du décompilateur (pas du code d'origine).

1.10.0 — donjons (package fr.windydev.clans.dungeon) :
- DungeonManager : lancement, groupe, boucle de jeu, monstres à niveau, élites, butin, menu, /donjon
- DungeonBuilder : plan aléatoire des étages et construction des salles par thème
- Room / DungeonInstance / DungeonDef : données
- donjons.yml : réglages et contenu des 3 donjons

1.22.0 — repart de la 1.20.3 en production + la 1.21.0 :
- pvp/PvpManager : arène /pvp (1v1, 2v2, classé Elo, duel, duo), kits de pvp.yml ou créés en jeu
  (/pvp admin kit creer <id>), stuff des joueurs sauvegardé dans pvp-sauvegardes.yml et rendu à la fin
- feature/TeleportService : plusieurs homes (teleport.max-homes, 3), migration de homes.yml
- profile/Awakening : zone du rituel 25 blocs (eveil.yml config-version 3)
- profile/SkillEngine : bonus de récolte sur baies (clic droit), tiges, melons, citrouilles, baies lumineuses
- feature/PackService : plugins/WindyClans/windyclans-pack-perso.zip remplace le pack intégré s'il existe ;
  habillage des écrans du jeu désactivé par défaut
- Compilation : API Paper 1.21.4 (noms d'attributs sans GENERIC_). Après `mvn package`, ajouter
  windyclans-pack.zip et windyclans-jeu.zip à la racine du jar (ils ne sont pas dans les sources).
