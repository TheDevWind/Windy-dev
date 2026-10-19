package fr.windydev.clans.listener;

import fr.windydev.clans.WindyClans;
import fr.windydev.clans.data.ClanManager;
import fr.windydev.clans.model.ChunkPos;
import fr.windydev.clans.model.Clan;
import fr.windydev.clans.model.Relation;
import fr.windydev.clans.util.Text;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.kyori.adventure.title.Title;
import net.kyori.adventure.title.Title.Times;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

public class PlayerListener implements Listener {
   private final WindyClans plugin;
   private final Map<UUID, String> lastZone = new HashMap<>();

   public PlayerListener(WindyClans plugin) {
      this.plugin = plugin;
   }

   @EventHandler
   public void onJoin(PlayerJoinEvent e) {
      Player p = e.getPlayer();
      this.plugin.scoreboard().setup(p);
      this.lastZone.put(p.getUniqueId(), this.zoneKey(ChunkPos.of(p.getLocation())));
      Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
         if (p.isOnline()) {
            for (Clan c : this.plugin.clans().invitesOf(p.getUniqueId())) {
               this.plugin.menus().sendInviteMessage(p, c);
            }

            Clan c = this.plugin.clans().of(p);
            if (c != null && !c.allyRequests().isEmpty() && c.rank(p.getUniqueId()).atLeast(this.plugin.settings().rank("relations"))) {
               this.plugin.msg(p, "&bTon clan a &f" + c.allyRequests().size() + " &bdemande(s) d'alliance. &7Menu > Diplomatie");
            }
         }
      }, 40L);
   }

   @EventHandler
   public void onQuit(PlayerQuitEvent e) {
      this.plugin.scoreboard().remove(e.getPlayer());
      this.lastZone.remove(e.getPlayer().getUniqueId());
      this.plugin.clanChatToggle().remove(e.getPlayer().getUniqueId());
   }

   @EventHandler(
      ignoreCancelled = true,
      priority = EventPriority.MONITOR
   )
   public void onMove(PlayerMoveEvent e) {
      if (e.getFrom().getBlockX() >> 4 != e.getTo().getBlockX() >> 4
         || e.getFrom().getBlockZ() >> 4 != e.getTo().getBlockZ() >> 4
         || e.getFrom().getWorld() != e.getTo().getWorld()) {
         this.enter(e.getPlayer(), ChunkPos.of(e.getTo()));
      }
   }

   @EventHandler(
      ignoreCancelled = true,
      priority = EventPriority.MONITOR
   )
   public void onTeleport(PlayerTeleportEvent e) {
      this.enter(e.getPlayer(), ChunkPos.of(e.getTo()));
   }

   private String zoneKey(ChunkPos pos) {
      ClanManager cm = this.plugin.clans();
      Clan c = cm.at(pos);
      if (c != null) {
         return c.id().toString() + (cm.isCapitalZone(c, pos) ? ":cap" : "");
      } else {
         return cm.isSpawnZone(pos) ? "spawn" : "wild";
      }
   }

   private void enter(Player p, ChunkPos pos) {
      String key = this.zoneKey(pos);
      String prev = this.lastZone.put(p.getUniqueId(), key);
      if (!Objects.equals(prev, key)) {
         ClanManager cm = this.plugin.clans();
         Clan c = cm.at(pos);
         String title;
         String sub;
         if (c != null) {
            Relation rel = cm.relation(p, c);
            boolean cap = cm.isCapitalZone(c, pos);
            title = rel.color() + c.name();
            sub = "&7" + (cap ? "Capitale" : "Territoire") + " " + rel.color() + "• " + rel.label();
            if (prev != null && prev.startsWith(c.id().toString())) {
               p.sendActionBar(Text.c(rel.color() + (cap ? "★ Capitale de " : "Territoire de ") + c.name()));
               return;
            }
         } else {
            if (!key.equals("spawn")) {
               if (prev == null) {
                  return;
               }

               p.sendActionBar(Text.c("&2~ Zone sauvage ~"));
               return;
            }

            title = "&eSpawn";
            sub = "&7Zone protégée";
         }

         p.showTitle(Title.title(Text.c(title), Text.c(sub), Times.times(Duration.ofMillis(200L), Duration.ofMillis(1500L), Duration.ofMillis(400L))));
      }
   }
}
