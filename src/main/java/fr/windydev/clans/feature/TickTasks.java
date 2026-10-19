package fr.windydev.clans.feature;

import fr.windydev.clans.WindyClans;
import fr.windydev.clans.data.ClanManager;
import fr.windydev.clans.data.Settings;
import fr.windydev.clans.model.ChunkPos;
import fr.windydev.clans.model.Clan;
import fr.windydev.clans.model.Relation;
import java.util.List;
import java.util.Map.Entry;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.Particle.DustOptions;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

public class TickTasks {
   private final WindyClans plugin;
   private long seconds;

   public TickTasks(WindyClans plugin) {
      this.plugin = plugin;
   }

   public void start() {
      Bukkit.getScheduler().runTaskTimer(this.plugin, () -> {
         this.seconds++;

         for (Player p : Bukkit.getOnlinePlayers()) {
            if (this.plugin.borders().contains(p.getUniqueId())) {
               this.drawBorders(p);
            }
         }

         if (this.seconds % 5L == 0L) {
            this.capitalEffects();
         }

         if (this.seconds % 300L == 0L) {
            this.onlineXp();
         }
      }, 20L, 20L);
   }

   private void onlineXp() {
      int per = this.plugin.settings().xpOnline;
      if (per > 0) {
         for (Clan c : this.plugin.clans().all()) {
            int online = this.plugin.clans().onlineCount(c);
            if (online > 0) {
               this.plugin.clans().addXp(c, (long)online * per);
            }
         }
      }
   }

   private void capitalEffects() {
      ClanManager cm = this.plugin.clans();

      for (Player p : Bukkit.getOnlinePlayers()) {
         Clan c = cm.of(p);
         if (c != null) {
            ChunkPos pos = ChunkPos.of(p.getLocation());
            if (cm.isCapitalZone(c, pos)) {
               for (Entry<Integer, List<String>> e : this.plugin.settings().capitalEffects.entrySet()) {
                  if (c.level() >= e.getKey()) {
                     for (String spec : e.getValue()) {
                        Entry<PotionEffectType, Integer> eff = Settings.parseEffect(spec);
                        if (eff != null) {
                           PotionEffectType type = eff.getKey();
                           PotionEffect cur = p.getPotionEffect(type);
                           if (cur == null || cur.getAmplifier() <= eff.getValue() && cur.getDuration() <= 200) {
                              p.addPotionEffect(new PotionEffect(type, 160, eff.getValue(), true, false, true));
                           }
                        }
                     }
                  }
               }
            }
         }
      }
   }

   private void drawBorders(Player p) {
      ClanManager cm = this.plugin.clans();
      ChunkPos center = ChunkPos.of(p.getLocation());
      World w = p.getWorld();
      double y = p.getLocation().getY() + 1.2;

      for (int dx = -1; dx <= 1; dx++) {
         for (int dz = -1; dz <= 1; dz++) {
            ChunkPos pos = center.offset(dx, dz);
            Clan owner = cm.at(pos);
            boolean spawn = owner == null && cm.isSpawnZone(pos);
            if (owner != null || spawn) {
               Color color = spawn ? Color.YELLOW : color(cm.relation(p, owner));
               DustOptions dust = new DustOptions(color, 1.3F);
               int bx = pos.x() * 16;
               int bz = pos.z() * 16;
               if (this.differs(pos, pos.offset(0, -1), spawn)) {
                  this.line(p, w, bx, bz, bx + 16, bz, y, dust);
               }

               if (this.differs(pos, pos.offset(0, 1), spawn)) {
                  this.line(p, w, bx, bz + 16, bx + 16, bz + 16, y, dust);
               }

               if (this.differs(pos, pos.offset(-1, 0), spawn)) {
                  this.line(p, w, bx, bz, bx, bz + 16, y, dust);
               }

               if (this.differs(pos, pos.offset(1, 0), spawn)) {
                  this.line(p, w, bx + 16, bz, bx + 16, bz + 16, y, dust);
               }
            }
         }
      }
   }

   private boolean differs(ChunkPos a, ChunkPos b, boolean spawn) {
      ClanManager cm = this.plugin.clans();
      return !spawn ? cm.at(a) != cm.at(b) : cm.at(b) != null || !cm.isSpawnZone(b);
   }

   private void line(Player p, World w, double x1, double z1, double x2, double z2, double y, DustOptions dust) {
      int steps = 16;

      for (int i = 0; i <= steps; i++) {
         double t = (double)i / steps;
         p.spawnParticle(Particle.DUST, x1 + (x2 - x1) * t, y, z1 + (z2 - z1) * t, 1, 0.0, 0.0, 0.0, 0.0, dust);
      }
   }

   private static Color color(Relation r) {
      return switch (r) {
         case SELF -> Color.LIME;
         case ALLY -> Color.AQUA;
         case ENEMY -> Color.RED;
         case WAR -> Color.MAROON;
         default -> Color.WHITE;
      };
   }
}
