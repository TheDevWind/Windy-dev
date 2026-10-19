package fr.windydev.clans.feature;

import fr.windydev.clans.WindyClans;
import fr.windydev.clans.model.Clan;
import fr.windydev.clans.util.Text;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Display.Billboard;
import org.bukkit.scheduler.BukkitTask;

public class TopHologram {
   private static final String TAG = "windyclans_top";
   private final WindyClans plugin;
   private TextDisplay display;
   private BukkitTask task;

   public TopHologram(WindyClans plugin) {
      this.plugin = plugin;
   }

   public void start() {
      if (this.task != null) {
         this.task.cancel();
      }

      this.remove();
      Location loc = this.location();
      if (loc != null) {
         this.task = Bukkit.getScheduler().runTaskTimer(this.plugin, this::update, 40L, 1200L);
      }
   }

   public void setLocation(Location l) {
      String s = l.getWorld().getName() + ";" + l.getX() + ";" + (l.getY() + 2.5) + ";" + l.getZ();
      this.plugin.getConfig().set("hologram.location", s);
      this.plugin.saveConfig();
      this.plugin.settings().hologramLocation = s;
      this.start();
   }

   private Location location() {
      String s = this.plugin.settings().hologramLocation;
      if (s != null && !s.isBlank()) {
         try {
            String[] p = s.split(";");
            World w = Bukkit.getWorld(p[0]);
            return w == null ? null : new Location(w, Double.parseDouble(p[1]), Double.parseDouble(p[2]), Double.parseDouble(p[3]));
         } catch (Exception var4) {
            return null;
         }
      } else {
         return null;
      }
   }

   private void update() {
      Location loc = this.location();
      if (loc != null && loc.isChunkLoaded()) {
         if (this.display == null || !this.display.isValid()) {
            for (Entity e : loc.getWorld().getNearbyEntities(loc, 3.0, 3.0, 3.0)) {
               if (e.getScoreboardTags().contains("windyclans_top")) {
                  e.remove();
               }
            }

            this.display = (TextDisplay)loc.getWorld().spawn(loc, TextDisplay.class, d -> {
               d.addScoreboardTag("windyclans_top");
               d.setPersistent(false);
               d.setBillboard(Billboard.CENTER);
               d.setShadowed(true);
               d.setLineWidth(250);
            });
         }

         StringBuilder sb = new StringBuilder("&6&l✪ TOP CLANS ✪\n&8―――――――――――――\n");
         List<Clan> top = this.plugin.clans().top();
         int size = Math.min(this.plugin.settings().hologramSize, top.size());

         for (int i = 0; i < size; i++) {
            Clan c = top.get(i);
            String medal = i == 0 ? "&6" : (i == 1 ? "&7" : (i == 2 ? "&c" : "&8"));
            sb.append(medal)
               .append("#")
               .append(i + 1)
               .append(" ")
               .append(c.coloredTag())
               .append(" &f")
               .append(c.name())
               .append(" &7- niv. &e")
               .append(c.level())
               .append("\n");
         }

         if (size == 0) {
            sb.append("&7Aucun clan pour l'instant\n");
         }

         this.display.text(Text.c(sb.toString().trim()));
      }
   }

   public void remove() {
      if (this.display != null && this.display.isValid()) {
         this.display.remove();
      }

      this.display = null;
   }
}
