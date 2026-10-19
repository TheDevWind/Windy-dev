package fr.windydev.clans.dungeon;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;

/** Définition d'un donjon lue dans donjons.yml. */
final class DungeonDef {
   final String id;
   final String name;
   final Material icon;
   final String theme;
   final String seal;
   final int playerLevel;
   final int clanXp;
   final int completionXp;
   final List<Integer> floors;
   final Map<EntityType, String> mobs;
   final BossSpec miniBoss;
   final BossSpec guardian;
   final List<Loot> chestLoot;
   final List<Loot> finalLoot;

   record Ability(String type, int cooldown, String mob, int count) {
   }

   record BossSpec(String name, EntityType type, double health, double damage, double scale, Map<String, String> equipment, List<Ability> abilities) {
   }

   record Loot(String spec, int min, int max, double chance) {
      static Loot parse(String line) {
         String[] p = line.trim().split("\\s+");
         if (p.length == 0 || p[0].isEmpty()) {
            return null;
         } else {
            int min = 1;
            int max = 1;
            double chance = 100.0;

            try {
               if (p.length > 1) {
                  String[] r = p[1].split("-");
                  min = Integer.parseInt(r[0]);
                  max = r.length > 1 ? Integer.parseInt(r[1]) : min;
               }

               if (p.length > 2) {
                  chance = Double.parseDouble(p[2].replace("%", ""));
               }
            } catch (NumberFormatException var7) {
               return null;
            }

            return new Loot(p[0], Math.max(1, min), Math.max(min, max), chance / 100.0);
         }
      }
   }

   DungeonDef(String id, ConfigurationSection s) {
      this.id = id;
      this.name = s.getString("name", id);
      Material ic = Material.matchMaterial(s.getString("icon", "SKELETON_SKULL"));
      this.icon = ic == null ? Material.SKELETON_SKULL : ic;
      this.theme = s.getString("theme", "crypte");
      this.seal = s.getString("seal", "");
      this.playerLevel = s.getInt("player-level", 1);
      this.clanXp = s.getInt("clan-xp", 100);
      this.completionXp = s.getInt("completion-xp", 300);
      List<Integer> fl = s.getIntegerList("floors");
      this.floors = fl.isEmpty() ? List.of(1, 2, 3) : fl;
      this.mobs = new LinkedHashMap<>();
      ConfigurationSection ms = s.getConfigurationSection("mobs");
      if (ms != null) {
         for (String k : ms.getKeys(false)) {
            try {
               this.mobs.put(EntityType.valueOf(k.toUpperCase(Locale.ROOT)), ms.getString(k, k));
            } catch (IllegalArgumentException var9) {
            }
         }
      }

      if (this.mobs.isEmpty()) {
         this.mobs.put(EntityType.ZOMBIE, "Mort-vivant");
      }

      this.miniBoss = boss(s.getConfigurationSection("mini-boss"), "&7Gardien d'étage", 120.0);
      this.guardian = boss(s.getConfigurationSection("guardian"), "&fGardien", 300.0);
      this.chestLoot = loot(s.getStringList("loot.chest"));
      this.finalLoot = loot(s.getStringList("loot.final"));
   }

   private static List<Loot> loot(List<String> lines) {
      List<Loot> out = new ArrayList<>();

      for (String l : lines) {
         Loot x = Loot.parse(l);
         if (x != null) {
            out.add(x);
         }
      }

      return out;
   }

   private static BossSpec boss(ConfigurationSection s, String defName, double defHealth) {
      if (s == null) {
         return new BossSpec(defName, EntityType.ZOMBIE, defHealth, 6.0, 1.3, Map.of(), List.of());
      } else {
         EntityType t;
         try {
            t = EntityType.valueOf(s.getString("type", "ZOMBIE").toUpperCase(Locale.ROOT));
         } catch (IllegalArgumentException var9) {
            t = EntityType.ZOMBIE;
         }

         Map<String, String> eq = new HashMap<>();
         ConfigurationSection es = s.getConfigurationSection("equipment");
         if (es != null) {
            for (String k : es.getKeys(false)) {
               eq.put(k, es.getString(k));
            }
         }

         List<Ability> ab = new ArrayList<>();

         for (Map<?, ?> m : s.getMapList("abilities")) {
            Object cd = m.get("cooldown");
            Object cnt = m.get("count");
            ab.add(
               new Ability(
                  String.valueOf(m.get("type")).toLowerCase(Locale.ROOT),
                  cd instanceof Number n ? n.intValue() : 10,
                  m.get("mob") == null ? "ZOMBIE" : String.valueOf(m.get("mob")),
                  cnt instanceof Number n2 ? n2.intValue() : 2
               )
            );
         }

         return new BossSpec(s.getString("name", defName), t, s.getDouble("health", defHealth), s.getDouble("damage", 6.0), s.getDouble("scale", 1.3), eq, ab);
      }
   }
}
