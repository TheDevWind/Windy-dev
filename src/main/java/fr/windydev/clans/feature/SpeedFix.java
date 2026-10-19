package fr.windydev.clans.feature;

import fr.windydev.clans.WindyClans;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public final class SpeedFix implements Listener {
   private final WindyClans plugin;

   public SpeedFix(WindyClans plugin) {
      this.plugin = plugin;
   }

   @EventHandler
   public void onJoin(PlayerJoinEvent e) {
      Player p = e.getPlayer();

      for (long delay : new long[]{40L, 200L}) {
         Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
            if (p.isOnline() && !(p.getWalkSpeed() > 0.0F) && !this.waitingForLogin(p)) {
               p.setWalkSpeed(0.2F);
               if (p.getFlySpeed() <= 0.0F) {
                  p.setFlySpeed(0.1F);
               }

               this.plugin.getLogger().info("Vitesse de " + p.getName() + " débloquée (elle était à 0).");
            }
         }, delay);
      }
   }

   private boolean waitingForLogin(Player p) {
      if (Bukkit.getPluginManager().getPlugin("AuthMe") == null) {
         return false;
      } else {
         try {
            Class<?> api = Class.forName("fr.xephi.authme.api.v3.AuthMeApi");
            Object inst = api.getMethod("getInstance").invoke(null);

            try {
               if ((Boolean)api.getMethod("isUnrestricted", Player.class).invoke(inst, p)) {
                  return false;
               }
            } catch (NoSuchMethodException var5) {
            }

            return !(Boolean)api.getMethod("isAuthenticated", Player.class).invoke(inst, p);
         } catch (Throwable var6) {
            return true;
         }
      }
   }
}
