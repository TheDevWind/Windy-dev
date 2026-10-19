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
