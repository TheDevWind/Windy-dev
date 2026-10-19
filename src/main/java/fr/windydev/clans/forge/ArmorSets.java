package fr.windydev.clans.forge;

import fr.windydev.clans.WindyClans;
import fr.windydev.clans.util.Text;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.attribute.AttributeModifier.Operation;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * Bonus des sets d'armure complets (v1.2) :
 *  Acier runique    : +2 cœurs max
 *  Argent lunaire   : immunité à la lenteur et au givre
 *  Arcanite du Vide : Résistance I pendant 5 s après un coup reçu (recharge 30 s)
 */
public class ArmorSets implements Listener {
   public static final String ACIER = "acier";
   public static final String ARGENT = "argent";
   public static final String ARCANITE = "arcanite";
   private final WindyClans plugin;
   private final NamespacedKey hpKey;
   private final Map<UUID, String> worn = new HashMap<>();
   private final Map<UUID, Long> resistReady = new HashMap<>();

   public ArmorSets(WindyClans plugin) {
      this.plugin = plugin;
      this.hpKey = new NamespacedKey(plugin, "set_acier");
   }

   public void start() {
      Bukkit.getScheduler().runTaskTimer(this.plugin, () -> {
         for (Player p : Bukkit.getOnlinePlayers()) {
            this.update(p);
         }
      }, 40L, 10L);
   }

   /** Le set complet porté ("acier", "argent", "arcanite") ou null. */
   public String set(Player p) {
      String set = null;
      for (ItemStack it : p.getInventory().getArmorContents()) {
         String id = this.plugin.items().idOf(it);
         if (id == null) {
            return null;
         }

         int u = id.indexOf('_');
         String s = u < 0 ? id : id.substring(0, u);
         if (!s.equals(ACIER) && !s.equals(ARGENT) && !s.equals(ARCANITE)) {
            return null;
         }

         if (set == null) {
            set = s;
         } else if (!set.equals(s)) {
            return null;
         }
      }

      return set;
   }

   private void update(Player p) {
      String now = this.set(p);
      String before = this.worn.get(p.getUniqueId());
      if (now == null ? before != null : !now.equals(before)) {
         if (now == null) {
            this.worn.remove(p.getUniqueId());
         } else {
            this.worn.put(p.getUniqueId(), now);
            String name = switch (now) {
               case ACIER -> "&7Acier runique &8: &c+2 cœurs";
               case ARGENT -> "&bArgent lunaire &8: &fimmunité à la lenteur et au givre";
               default -> "&5Arcanite du Vide &8: &dRésistance après un coup";
            };
            p.sendActionBar(Text.c("&6✦ Set complet ! " + name));
            p.playSound(p.getLocation(), Sound.ITEM_ARMOR_EQUIP_NETHERITE, 0.8F, 1.3F);
         }
      }

      AttributeInstance hp = p.getAttribute(Attribute.MAX_HEALTH);
      if (hp != null) {
         AttributeModifier cur = null;
         for (AttributeModifier m : hp.getModifiers()) {
            if (m.getKey().equals(this.hpKey)) {
               cur = m;
            }
         }

         if (ACIER.equals(now) && cur == null) {
            hp.addTransientModifier(new AttributeModifier(this.hpKey, 4.0, Operation.ADD_NUMBER, EquipmentSlotGroup.ANY));
         } else if (!ACIER.equals(now) && cur != null) {
            hp.removeModifier(cur);
            if (p.getHealth() > hp.getValue()) {
               p.setHealth(hp.getValue());
            }
         }
      }

      if (ARGENT.equals(now)) {
         if (p.hasPotionEffect(PotionEffectType.SLOWNESS)) {
            p.removePotionEffect(PotionEffectType.SLOWNESS);
         }

         if (p.getFreezeTicks() > 0) {
            p.setFreezeTicks(0);
         }
      }
   }

   @EventHandler(ignoreCancelled = true)
   public void onEffect(EntityPotionEffectEvent e) {
      if (e.getEntity() instanceof Player p && e.getNewEffect() != null && e.getNewEffect().getType().equals(PotionEffectType.SLOWNESS)
         && ARGENT.equals(this.worn.get(p.getUniqueId()))) {
         e.setCancelled(true);
      }
   }

   @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
   public void onFreeze(EntityDamageEvent e) {
      if (e.getEntity() instanceof Player p && e.getCause() == EntityDamageEvent.DamageCause.FREEZE && ARGENT.equals(this.worn.get(p.getUniqueId()))) {
         e.setCancelled(true);
      }
   }

   @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
   public void onHurt(EntityDamageEvent e) {
      if (e.getEntity() instanceof Player p) {
         if (ARCANITE.equals(this.worn.get(p.getUniqueId())) && e.getFinalDamage() > 0.0) {
            long now = System.currentTimeMillis();
            Long ready = this.resistReady.get(p.getUniqueId());
            if (ready == null || now >= ready) {
               this.resistReady.put(p.getUniqueId(), now + 30000L);
               Bukkit.getScheduler().runTask(this.plugin, () -> {
                  if (p.isOnline()) {
                     p.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 100, 0, false, true, true));
                     p.getWorld().spawnParticle(Particle.REVERSE_PORTAL, p.getLocation().add(0.0, 1.0, 0.0), 40, 0.4, 0.8, 0.4, 0.05);
                     p.getWorld().playSound(p.getLocation(), Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 0.7F, 1.4F);
                  }
               });
            }
         }
      }
   }

   @EventHandler
   public void onQuit(PlayerQuitEvent e) {
      this.worn.remove(e.getPlayer().getUniqueId());
      AttributeInstance hp = e.getPlayer().getAttribute(Attribute.MAX_HEALTH);
      if (hp != null) {
         for (AttributeModifier m : hp.getModifiers()) {
            if (m.getKey().equals(this.hpKey)) {
               hp.removeModifier(m);
            }
         }
      }
   }
}
