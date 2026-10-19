package fr.windydev.clans.profile;

import fr.windydev.clans.WindyClans;
import fr.windydev.clans.gui.Menu;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Trident;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

public class RaceListener implements Listener {
   private final WindyClans plugin;
   private final ProfileManager pm;

   public RaceListener(WindyClans plugin, ProfileManager pm) {
      this.plugin = plugin;
      this.pm = pm;
   }

   public void start() {
      Bukkit.getScheduler()
         .runTaskTimer(
            this.plugin,
            () -> {
               long now = System.currentTimeMillis();

               for (Player p : Bukkit.getOnlinePlayers()) {
                  if ((p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE)
                     && !(this.pm.stat(p, "double-jump") <= 0.0)
                     && !this.plugin.powers().flying(p)) {
                     if (p.isOnGround()) {
                        this.pm.airJumps.remove(p.getUniqueId());
                     }

                     if (p.isOnGround() && !p.getAllowFlight() && now >= this.pm.jumpReady.getOrDefault(p.getUniqueId(), 0L)) {
                        p.setAllowFlight(true);
                     }
                  }
               }
            },
            5L,
            4L
         );
   }

   @EventHandler
   public void onJoin(PlayerJoinEvent e) {
      this.pm.join(e.getPlayer());
   }

   @EventHandler
   public void onQuit(PlayerQuitEvent e) {
      this.pm.quit(e.getPlayer());
   }

   @EventHandler
   public void onRespawn(PlayerRespawnEvent e) {
      Player p = e.getPlayer();
      Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
         if (p.isOnline()) {
            this.pm.apply(p);
            AttributeInstance hp = p.getAttribute(Attribute.MAX_HEALTH);
            if (hp != null) {
               p.setHealth(hp.getValue());
            }
         }
      }, 1L);
   }

   @EventHandler
   public void onClose(InventoryCloseEvent e) {
      if (e.getPlayer() instanceof Player p && e.getInventory().getHolder() instanceof Menu) {
         if (this.pm.mustChoose(p)) {
            Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
               if (p.isOnline() && this.pm.mustChoose(p)) {
                  if (!(p.getOpenInventory().getTopInventory().getHolder() instanceof Menu)) {
                     this.plugin.msg(p, "&dChoisis ta race pour commencer l'aventure.");
                     this.plugin.menus().races(p);
                  }
               }
            }, 30L);
         }
      }
   }

   @EventHandler
   public void onWorld(PlayerChangedWorldEvent e) {
      this.pm.apply(e.getPlayer());
   }

   @EventHandler
   public void onGameMode(PlayerGameModeChangeEvent e) {
      Player p = e.getPlayer();
      Bukkit.getScheduler().runTask(this.plugin, () -> {
         if (p.isOnline()) {
            this.pm.apply(p);
         }
      });
   }

   @EventHandler(
      ignoreCancelled = true
   )
   public void onToggleFlight(PlayerToggleFlightEvent e) {
      Player p = e.getPlayer();
      if (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE) {
         double cd = this.pm.stat(p, "double-jump");
         if (!(cd <= 0.0) && e.isFlying() && !this.plugin.powers().flying(p)) {
            cd = Math.max(0.5, cd + this.pm.stat(p, "double-jump-cd"));
            e.setCancelled(true);
            int jumps = this.pm.airJumps.merge(p.getUniqueId(), 1, Integer::sum);
            boolean triple = this.pm.hasSpecial(p, "extra") && "sylphe".equals(this.pm.of(p).race());
            p.setFlying(false);
            if (!triple || jumps >= 2) {
               p.setAllowFlight(false);
            }

            Vector v = p.getLocation().getDirection().setY(0).normalize().multiply(0.35);
            p.setVelocity(v.setY(0.72 * (1.0 + this.pm.stat(p, "updraft"))));
            p.setFallDistance(0.0F);
            double mo = this.pm.stat(p, "momentum");
            if (mo > 0.0) {
               p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 60, mo >= 0.2 ? 1 : 0, false, false, true));
            }

            if (!triple || jumps >= 2) {
               this.pm.jumpReady.put(p.getUniqueId(), System.currentTimeMillis() + (long)(cd * 1000.0));
            }

            p.getWorld().spawnParticle(Particle.CLOUD, p.getLocation(), 12, 0.3, 0.05, 0.3, 0.02);
            p.playSound(p.getLocation(), Sound.ENTITY_BREEZE_JUMP, 0.6F, 1.4F);
         }
      }
   }

   @EventHandler(
      priority = EventPriority.HIGH,
      ignoreCancelled = true
   )
   public void onDamage(EntityDamageByEntityEvent e) {
      Entity d = e.getDamager();
      if (d instanceof Projectile proj && proj.getShooter() instanceof Player shooter) {
         String key = proj instanceof Trident ? "trident-damage" : (proj instanceof AbstractArrow ? "bow-damage" : null);
         if (key != null) {
            double f = this.pm.stat(shooter, key);
            if (f != 0.0) {
               e.setDamage(Math.max(0.0, e.getDamage() * (1.0 + f)));
            }
         }
      } else if (d instanceof Player p) {
         if (this.pm.night(p)) {
            double nd = this.pm.stat(p, "night-damage");
            if (nd > 0.0) {
               e.setDamage(e.getDamage() + nd);
            }
         }

         double ignite = this.pm.stat(p, "melee-ignite");
         if (ignite > 0.0 && Math.random() < ignite && e.getEntity() instanceof LivingEntity le) {
            le.setFireTicks(Math.max(le.getFireTicks(), 80));
         }
      }
   }
}
