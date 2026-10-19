package fr.windydev.clans.feature;

import fr.windydev.clans.WindyClans;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.spigotmc.event.player.PlayerSpawnLocationEvent;

public class SpawnService implements Listener {
   private final WindyClans plugin;

   public SpawnService(WindyClans plugin) {
      this.plugin = plugin;
   }

   public Location get() {
      FileConfiguration c = this.plugin.getConfig();
      if (!c.contains("spawn.world")) {
         return null;
      } else {
         World w = Bukkit.getWorld(c.getString("spawn.world", ""));
         return w == null
            ? null
            : new Location(
               w, c.getDouble("spawn.x"), c.getDouble("spawn.y"), c.getDouble("spawn.z"), (float)c.getDouble("spawn.yaw"), (float)c.getDouble("spawn.pitch")
            );
      }
   }

   public void set(Location l) {
      FileConfiguration c = this.plugin.getConfig();
      c.set("spawn.world", l.getWorld().getName());
      c.set("spawn.x", l.getX());
      c.set("spawn.y", l.getY());
      c.set("spawn.z", l.getZ());
      c.set("spawn.yaw", (double)l.getYaw());
      c.set("spawn.pitch", (double)l.getPitch());
      this.plugin.saveConfig();
      l.getWorld().setSpawnLocation(l);
   }

   @EventHandler(
      priority = EventPriority.HIGH
   )
   public void onFirstSpawn(PlayerSpawnLocationEvent e) {
      if (!e.getPlayer().hasPlayedBefore()) {
         Location l = this.get();
         if (l != null) {
            e.setSpawnLocation(l);
         }
      }
   }

   @EventHandler(
      priority = EventPriority.HIGH
   )
   public void onRespawn(PlayerRespawnEvent e) {
      if (!e.isBedSpawn() && !e.isAnchorSpawn()) {
         Location l = this.get();
         if (l != null) {
            e.setRespawnLocation(l);
         }
      }
   }
}
