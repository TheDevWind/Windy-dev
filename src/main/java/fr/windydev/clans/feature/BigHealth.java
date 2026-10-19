package fr.windydev.clans.feature;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * PV au-delà de la limite du serveur (spigot.yml : settings.attribute.maxHealth.max, 1024 par défaut).
 * Au-delà, la créature reçoit le maximum permis et les dégâts qu'elle subit sont réduits d'autant :
 * un boss de 3000 PV a 1024 PV réels et ne prend que 34 % des dégâts. (MAJ 1.21.0 : avant, la créature
 * ne pouvait pas apparaître et le combat plantait.)
 */
public final class BigHealth implements Listener {
   private static NamespacedKey key;

   public BigHealth(Plugin plugin) {
      key = new NamespacedKey(plugin, "hp_scale");
   }

   public static double cap() {
      try {
         return Math.max(20.0, Bukkit.spigot().getConfig().getDouble("settings.attribute.maxHealth.max", 1024.0));
      } catch (Throwable var1) {
         return 1024.0;
      }
   }

   /** Donne "hp" PV à la créature (pleine vie) et rend ses PV réels. */
   public static double apply(LivingEntity le, double hp) {
      double real = Math.max(1.0, Math.min(hp, cap()));
      AttributeInstance a = le.getAttribute(Attribute.MAX_HEALTH);
      if (a != null) {
         a.setBaseValue(real);
         real = Math.min(real, a.getValue());
      }

      le.setHealth(Math.max(1.0, real));
      if (key != null) {
         if (hp > real + 0.01) {
            le.getPersistentDataContainer().set(key, PersistentDataType.DOUBLE, real / hp);
         } else {
            le.getPersistentDataContainer().remove(key);
         }
      }

      return real;
   }

   /** Part des dégâts réellement subis (1 = normal). */
   public static double scale(Entity e) {
      return key == null ? 1.0 : e.getPersistentDataContainer().getOrDefault(key, PersistentDataType.DOUBLE, 1.0);
   }

   @EventHandler(
      priority = EventPriority.HIGH,
      ignoreCancelled = true
   )
   public void onDamage(EntityDamageEvent e) {
      if (e.getEntity() instanceof LivingEntity) {
         double s = scale(e.getEntity());
         if (s < 1.0 && e.getCause() != EntityDamageEvent.DamageCause.KILL && e.getCause() != EntityDamageEvent.DamageCause.VOID) {
            e.setDamage(e.getDamage() * s);
         }
      }
   }
}
