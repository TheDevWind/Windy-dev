package fr.windydev.clans.listener;

import fr.windydev.clans.WindyClans;
import fr.windydev.clans.data.ClanManager;
import fr.windydev.clans.data.Settings;
import fr.windydev.clans.model.ChunkPos;
import fr.windydev.clans.model.Clan;
import fr.windydev.clans.util.Text;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;

public class CombatListener implements Listener {
   private final WindyClans plugin;

   public CombatListener(WindyClans plugin) {
      this.plugin = plugin;
   }

   @EventHandler(
      ignoreCancelled = true,
      priority = EventPriority.LOW
   )
   public void onPvp(EntityDamageByEntityEvent e) {
      if (e.getEntity() instanceof Player victim) {
         Player attacker = ProtectionListener.attacker(e.getDamager());
         if (attacker != null && attacker != victim) {
            if (!this.plugin.bypass().contains(attacker.getUniqueId())) {
               ClanManager cm = this.plugin.clans();
               Settings s = this.plugin.settings();
               Clan a = cm.of(attacker);
               Clan v = cm.of(victim);
               String reason = null;
               if (a != null && a == v) {
                  reason = "&cC'est un membre de ton clan !";
               } else if (a != null && v != null && a.allies().contains(v.id())) {
                  reason = "&bC'est un allié !";
               } else {
                  ChunkPos pos = ChunkPos.of(victim.getLocation());
                  if (!this.plugin.awakening().forcedPvp(victim.getLocation()) && !this.plugin.awakening().forcedPvp(attacker.getLocation())) {
                     if (s.pvpSpawn && cm.isSpawnZone(pos)) {
                        reason = "&eLe PvP est désactivé au spawn.";
                     } else if (s.pvpCapitals) {
                        Clan cap = cm.capitalAt(pos);
                        if (cap != null) {
                           reason = "&6Le PvP est désactivé dans la capitale de " + cap.name() + ".";
                        }
                     }
                  }
               }

               if (reason != null) {
                  e.setCancelled(true);
                  attacker.sendActionBar(Text.c(reason));
               }
            }
         }
      }
   }

   @EventHandler
   public void onPlayerDeath(PlayerDeathEvent e) {
      Player victim = e.getEntity();
      Player killer = victim.getKiller();
      if (killer != null) {
         Clan k = this.plugin.clans().of(killer);
         Clan v = this.plugin.clans().of(victim);
         if (k != null && v != null) {
            this.plugin.war().onKill(k, v);
         }
      }
   }

   @EventHandler
   public void onMobDeath(EntityDeathEvent e) {
      if (e.getEntity() instanceof Enemy && !(e.getEntity() instanceof Player)) {
         Player killer = e.getEntity().getKiller();
         if (killer != null) {
            Clan c = this.plugin.clans().of(killer);
            if (c != null) {
               this.plugin.clans().addXp(c, this.plugin.settings().xpMob);
            }
         }
      }
   }
}
