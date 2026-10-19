package fr.windydev.clans.profile;

import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.potion.PotionEffectType;

public record RaceDef(
   String id,
   String name,
   String color,
   Material icon,
   List<String> description,
   Map<String, double[]> bonus,
   Map<String, double[]> malus,
   List<RaceDef.Effect> effects,
   List<RaceDef.Effect> malusEffects,
   RaceDef.Hurt hurt
) {
   public String colored() {
      return this.color + this.name;
   }

   public static double at(double[] v, int tier, int maxTier) {
      return v.length != 1 && maxTier > 0 ? v[0] + (v[1] - v[0]) * Math.min(1.0, (double)tier / maxTier) : v[0];
   }

   public double stat(String key, int tier, int maxTier) {
      double s = 0.0;
      double[] b = this.bonus.get(key);
      if (b != null) {
         s += at(b, tier, maxTier);
      }

      double[] m = this.malus.get(key);
      if (m != null) {
         s += at(m, tier, maxTier);
      }

      return s;
   }

   public record Effect(PotionEffectType type, int amplifier, String when, int fromTier) {
   }

   public record Hurt(String when, double damage, int every) {
   }
}
