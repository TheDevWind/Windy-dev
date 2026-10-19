package fr.windydev.clans.profile;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier.Operation;
import org.bukkit.potion.PotionEffectType;

public final class Stats {
   public static final Map<String, Stats.Meta> META = new LinkedHashMap<>();

   private static void put(String key, String label, String attr, Operation op, Stats.Fmt fmt) {
      Attribute a = null;
      if (attr != null) {
         try {
            a = Attribute.valueOf(attr);
         } catch (IllegalArgumentException var7) {
         }
      }

      META.put(key, new Stats.Meta(label, a, op, fmt));
   }

   private Stats() {
   }

   private static String num(double v) {
      return Math.abs(v - Math.round(v)) < 0.05 ? String.valueOf(Math.round(v)) : String.format(Locale.ROOT, "%.1f", v).replace('.', ',');
   }

   private static String signed(double v, String unit) {
      return (v >= 0.0 ? "+" : "") + num(v) + unit;
   }

   public static String format(String key, double v) {
      Stats.Meta m = META.get(key);
      return (m == null ? key : m.label()) + " " + value(key, v);
   }

   public static String label(String key) {
      Stats.Meta m = META.get(key);
      return m == null ? key : m.label();
   }

   public static String value(String key, double v) {
      Stats.Meta m = META.get(key);
      if (m == null) {
         return num(v);
      } else if (key.equals("double-jump-cd")) {
         return signed(v, " s");
      } else {
         return switch (m.fmt()) {
            case PCT -> signed(v * 100.0, "%");
            case NUM -> signed(v, "");
            case HEARTS -> signed(v / 2.0, "");
            case BLOCKS -> signed(v, " bl.");
            case SIZE -> num(100.0 + v * 100.0) + "%";
            case SECONDS -> "/" + num(v) + "s";
            case CHANCE -> signed(v * 100.0, "%");
            case REDUCE -> "-" + num(v * 100.0) + "%";
            case REGEN -> "+" + num(v / 2.0) + " ❤/5s";
            case LEVEL -> "niv. " + num(v);
         };
      }
   }

   public static String effectName(PotionEffectType t) {
      String k = t.getKey().getKey();

      return switch (k) {
         case "night_vision" -> "Nyctalopie";
         case "fire_resistance" -> "Ignifugé";
         case "hero_of_the_village" -> "Héros village";
         case "dolphins_grace" -> "Nage rapide";
         case "conduit_power" -> "Conduit";
         case "strength" -> "Force";
         case "weakness" -> "Faiblesse";
         case "slow_falling" -> "Chute lente";
         case "speed" -> "Rapidité";
         case "haste" -> "Célérité";
         default -> k.replace('_', ' ');
      };
   }

   public static String when(String w) {
      return switch (w) {
         case "underground" -> "sous terre";
         case "night" -> "la nuit";
         case "day-sun" -> "au soleil sans casque";
         case "nether" -> "dans le Nether";
         case "water" -> "dans l'eau";
         case "water-or-rain" -> "dans l'eau et sous la pluie";
         case "forest" -> "en forêt";
         case "altitude" -> "en altitude";
         case "low-health" -> "sous 30 % de vie";
         case "axe" -> "hache en main";
         default -> "toujours";
      };
   }

   public static String roman(int n) {
      String[] r = new String[]{"I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
      return n >= 1 && n <= r.length ? r[n - 1] : String.valueOf(n);
   }

   static {
      put("max-health", "Cœurs", "GENERIC_MAX_HEALTH", Operation.ADD_NUMBER, Stats.Fmt.HEARTS);
      put("speed", "Vitesse", "GENERIC_MOVEMENT_SPEED", Operation.ADD_SCALAR, Stats.Fmt.PCT);
      put("jump", "Saut", "GENERIC_JUMP_STRENGTH", Operation.ADD_SCALAR, Stats.Fmt.PCT);
      put("safe-fall", "Amorti", "GENERIC_SAFE_FALL_DISTANCE", Operation.ADD_NUMBER, Stats.Fmt.BLOCKS);
      put("fall-damage", "Dégâts chute", "GENERIC_FALL_DAMAGE_MULTIPLIER", Operation.ADD_SCALAR, Stats.Fmt.PCT);
      put("scale", "Taille", "GENERIC_SCALE", Operation.ADD_SCALAR, Stats.Fmt.SIZE);
      put("attack-damage", "Force", "GENERIC_ATTACK_DAMAGE", Operation.ADD_NUMBER, Stats.Fmt.NUM);
      put("armor", "Armure", "GENERIC_ARMOR", Operation.ADD_NUMBER, Stats.Fmt.NUM);
      put("knockback-resistance", "Anti-recul", "GENERIC_KNOCKBACK_RESISTANCE", Operation.ADD_NUMBER, Stats.Fmt.PCT);
      put("mining-speed", "Minage", "PLAYER_BLOCK_BREAK_SPEED", Operation.ADD_SCALAR, Stats.Fmt.PCT);
      put("oxygen", "Souffle", "GENERIC_OXYGEN_BONUS", Operation.ADD_NUMBER, Stats.Fmt.NUM);
      put("submerged-mining", "Minage eau", "PLAYER_SUBMERGED_MINING_SPEED", Operation.ADD_SCALAR, Stats.Fmt.PCT);
      put("gravity", "Gravité", "GENERIC_GRAVITY", Operation.ADD_SCALAR, Stats.Fmt.PCT);
      put("luck", "Chance", "GENERIC_LUCK", Operation.ADD_NUMBER, Stats.Fmt.NUM);
      put("xp-gain", "XP gagnée", null, null, Stats.Fmt.PCT);
      put("coin-gain", "Coins gagnés", null, null, Stats.Fmt.PCT);
      put("bow-damage", "Arc", null, null, Stats.Fmt.PCT);
      put("trident-damage", "Trident", null, null, Stats.Fmt.PCT);
      put("melee-ignite", "Coups de feu", null, null, Stats.Fmt.CHANCE);
      put("night-damage", "Force nuit", null, null, Stats.Fmt.NUM);
      put("night-speed", "Vitesse nuit", null, null, Stats.Fmt.PCT);
      put("lifesteal", "Vol de vie", null, null, Stats.Fmt.PCT);
      put("ritual-speed", "Rituels", null, null, Stats.Fmt.REDUCE);
      put("rune-chance", "Runes", null, null, Stats.Fmt.CHANCE);
      put("ability-cooldown", "Recharge armes", null, null, Stats.Fmt.REDUCE);
      put("double-jump", "Double saut", null, null, Stats.Fmt.SECONDS);
      put("attack-speed", "Vitesse d'attaque", "GENERIC_ATTACK_SPEED", Operation.ADD_SCALAR, Stats.Fmt.PCT);
      put("armor-toughness", "Robustesse", "GENERIC_ARMOR_TOUGHNESS", Operation.ADD_NUMBER, Stats.Fmt.NUM);
      put("water-speed", "Nage", "GENERIC_WATER_MOVEMENT_EFFICIENCY", Operation.ADD_NUMBER, Stats.Fmt.PCT);
      put("sneak-speed", "Vitesse accroupi", "PLAYER_SNEAKING_SPEED", Operation.ADD_SCALAR, Stats.Fmt.PCT);
      put("damage", "Dégâts", null, null, Stats.Fmt.PCT);
      put("ranged-damage", "Dégâts à distance", null, null, Stats.Fmt.PCT);
      put("dodge", "Esquive", null, null, Stats.Fmt.CHANCE);
      put("crit", "Critique", null, null, Stats.Fmt.CHANCE);
      put("rage", "Dégâts blessé", null, null, Stats.Fmt.PCT);
      put("execute", "Exécution", null, null, Stats.Fmt.PCT);
      put("burning-damage", "Dégâts sur cible en feu", null, null, Stats.Fmt.PCT);
      put("detonate", "Déflagration", null, null, Stats.Fmt.CHANCE);
      put("fire-reduction", "Dégâts de feu", null, null, Stats.Fmt.REDUCE);
      put("explosion-reduction", "Dégâts d'explosion", null, null, Stats.Fmt.REDUCE);
      put("magic-reduction", "Dégâts magiques", null, null, Stats.Fmt.REDUCE);
      put("regen", "Régénération", null, null, Stats.Fmt.REGEN);
      put("malus-resist", "Résistance au malus", null, null, Stats.Fmt.PCT);
      put("soul-gain", "Âmes gagnées", null, null, Stats.Fmt.PCT);
      put("heal-on-kill", "Soin par kill", null, null, Stats.Fmt.HEARTS);
      put("absorption-on-kill", "Absorption par kill", null, null, Stats.Fmt.HEARTS);
      put("night-lifesteal", "Vol de vie nocturne", null, null, Stats.Fmt.PCT);
      put("fear-aura", "Aura de peur", null, null, Stats.Fmt.CHANCE);
      put("pvp-bonus", "Gains sur joueurs", null, null, Stats.Fmt.PCT);
      put("knockback-on-hit", "Recul infligé", null, null, Stats.Fmt.NUM);
      put("shield-break", "Brise-bouclier", null, null, Stats.Fmt.CHANCE);
      put("food-bonus", "Nourriture", null, null, Stats.Fmt.PCT);
      put("hunger-resist", "Faim", null, null, Stats.Fmt.REDUCE);
      put("kill-food", "Nourriture par kill", null, null, Stats.Fmt.NUM);
      put("rune-damage", "Armes runiques", null, null, Stats.Fmt.PCT);
      put("cd-reset", "Surcharge", null, null, Stats.Fmt.CHANCE);
      put("armor-pierce", "Projectiles", null, null, Stats.Fmt.PCT);
      put("arcane-shield", "Bouclier /60s", null, null, Stats.Fmt.HEARTS);
      put("escape-blink", "Repli", null, null, Stats.Fmt.CHANCE);
      put("ingredient-save", "Ingrédient épargné", null, null, Stats.Fmt.CHANCE);
      put("wither-on-hit", "Morsure", null, null, Stats.Fmt.CHANCE);
      put("blood-reserve", "Réserve de sang", null, null, Stats.Fmt.PCT);
      put("track", "Traque", null, null, Stats.Fmt.BLOCKS);
      put("dive-damage", "Frappe plongeante", null, null, Stats.Fmt.PCT);
      put("arrow-speed", "Vitesse des flèches", null, null, Stats.Fmt.PCT);
      put("air-crit", "Critique aérien", null, null, Stats.Fmt.CHANCE);
      put("glide", "Plume", null, null, Stats.Fmt.PCT);
      put("double-jump-cd", "Recharge double saut", null, null, Stats.Fmt.SECONDS);
      put("momentum", "Élan", null, null, Stats.Fmt.PCT);
      put("updraft", "Hauteur double saut", null, null, Stats.Fmt.PCT);
      put("arrow-pierce", "Tir perçant", null, null, Stats.Fmt.CHANCE);
      put("bow-crit", "Critique à l'arc", null, null, Stats.Fmt.CHANCE);
      put("arrow-save", "Flèche récupérée", null, null, Stats.Fmt.CHANCE);
      put("hunter-mark", "Marque", null, null, Stats.Fmt.CHANCE);
      put("crop-bonus", "Récolte", null, null, Stats.Fmt.CHANCE);
      put("tame-strength", "Animaux", null, null, Stats.Fmt.PCT);
      put("potion-duration", "Durée des potions", null, null, Stats.Fmt.PCT);
      put("camouflage", "Camouflage", null, null, Stats.Fmt.LEVEL);
      put("stun-on-hit", "Étourdissement", null, null, Stats.Fmt.CHANCE);
      put("armor-break", "Brise-armure", null, null, Stats.Fmt.CHANCE);
      put("ore-double", "Minerai doublé", null, null, Stats.Fmt.CHANCE);
      put("tool-save", "Usure évitée", null, null, Stats.Fmt.CHANCE);
      put("anvil-discount", "Enclume", null, null, Stats.Fmt.REDUCE);
      put("mining-xp", "XP de minage", null, null, Stats.Fmt.PCT);
      put("trident-return", "Retour du trident", null, null, Stats.Fmt.CHANCE);
      put("harpoon", "Harpon", null, null, Stats.Fmt.CHANCE);
      put("fishing-loot", "Double prise", null, null, Stats.Fmt.CHANCE);
      put("fishing-xp", "XP de pêche", null, null, Stats.Fmt.PCT);
      put("abyss-vision", "Vision abyssale", null, null, Stats.Fmt.LEVEL);
      put("trade-discount", "Prix marchands", null, null, Stats.Fmt.REDUCE);
      put("endurance", "Faim", null, null, Stats.Fmt.REDUCE);
   }

   public static enum Fmt {
      PCT,
      NUM,
      HEARTS,
      BLOCKS,
      SIZE,
      SECONDS,
      CHANCE,
      REDUCE,
      REGEN,
      LEVEL;
   }

   public record Meta(String label, Attribute attr, Operation op, Stats.Fmt fmt) {
   }
}
