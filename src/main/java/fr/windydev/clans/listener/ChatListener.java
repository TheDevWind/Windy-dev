package fr.windydev.clans.listener;

import fr.windydev.clans.WindyClans;
import fr.windydev.clans.model.Clan;
import fr.windydev.clans.model.Relation;
import fr.windydev.clans.profile.Profile;
import fr.windydev.clans.profile.ProfileManager;
import fr.windydev.clans.profile.RaceDef;
import fr.windydev.clans.util.Text;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

public class ChatListener implements Listener {
   private final WindyClans plugin;

   public ChatListener(WindyClans plugin) {
      this.plugin = plugin;
   }

   @EventHandler(
      ignoreCancelled = true,
      priority = EventPriority.HIGH
   )
   public void onChat(AsyncChatEvent e) {
      Player p = e.getPlayer();
      Clan c = this.plugin.clans().of(p);
      String raw = PlainTextComponentSerializer.plainText().serialize(e.message());
      if (c != null && raw.startsWith("!") && raw.length() > 1) {
         e.setCancelled(true);
         boolean ally = raw.startsWith("!!");
         String msg = raw.substring(ally ? 2 : 1).trim();
         if (!msg.isEmpty()) {
            Bukkit.getScheduler().runTask(this.plugin, () -> this.plugin.menus().clanChat(p, msg, ally));
         }
      } else if (c != null && this.plugin.clanChatToggle().contains(p.getUniqueId())) {
         e.setCancelled(true);
         String msg = PlainTextComponentSerializer.plainText().serialize(e.message());
         Bukkit.getScheduler().runTask(this.plugin, () -> this.plugin.menus().clanChat(p, msg, false));
      } else {
         String rankTag = this.rankTag(p);
         if (c == null) {
            if (!rankTag.isEmpty()) {
               e.renderer((source, displayName, message, viewer) -> Text.c(rankTag).append(displayName).append(Text.c("&7 » &f")).append(message));
            }
         } else {
            e.renderer(
               (source, displayName, message, viewer) -> {
                  Relation rel = viewer instanceof Player vp ? this.plugin.clans().relation(vp, c) : Relation.NEUTRAL;
                  if (viewer instanceof Player vpx && this.plugin.clans().of(vpx) == null) {
                     rel = Relation.NEUTRAL;
                  }

                  return Text.c(rel.color() + "[" + c.tag() + "] " + rankTag)
                     .append(
                        displayName.colorIfAbsent(TextColor.color(Text.rgb(c.rank(source.getUniqueId()) == null ? "&f" : c.rank(source.getUniqueId()).color())))
                     )
                     .append(Text.c("&7 » &f"))
                     .append(message);
               }
            );
         }
      }
   }

   private String rankTag(Player p) {
      ProfileManager pm = this.plugin.profiles();
      Profile pr = pm.of(p);
      RaceDef race = pm.race(pr);
      return race == null ? "" : race.color() + pm.rankName(pr) + " ";
   }
}
