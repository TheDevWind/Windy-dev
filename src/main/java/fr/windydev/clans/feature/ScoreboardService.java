package fr.windydev.clans.feature;

import fr.windydev.clans.WindyClans;
import fr.windydev.clans.data.ClanManager;
import fr.windydev.clans.data.Settings;
import fr.windydev.clans.model.ChunkPos;
import fr.windydev.clans.model.Clan;
import fr.windydev.clans.model.Rank;
import fr.windydev.clans.model.Relation;
import fr.windydev.clans.profile.Profile;
import fr.windydev.clans.profile.ProfileManager;
import fr.windydev.clans.profile.RaceDef;
import fr.windydev.clans.util.Text;
import io.papermc.paper.scoreboard.numbers.NumberFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

public class ScoreboardService {
   private static final String[] ENTRIES = new String[15];
   private final WindyClans plugin;
   private final Map<UUID, Scoreboard> boards = new HashMap<>();
   private final Set<UUID> hidden = new HashSet<>();
   private BukkitTask task;

   public ScoreboardService(WindyClans plugin) {
      this.plugin = plugin;
   }

   public void start() {
      if (this.task != null) {
         this.task.cancel();
      }

      this.task = Bukkit.getScheduler().runTaskTimer(this.plugin, this::updateAll, 20L, this.plugin.settings().sbTicks);
   }

   public void shutdown() {
      if (this.task != null) {
         this.task.cancel();
      }

      for (Player p : Bukkit.getOnlinePlayers()) {
         p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
      }

      this.boards.clear();
   }

   public void setup(Player p) {
      Scoreboard sb = Bukkit.getScoreboardManager().getNewScoreboard();
      Objective obj = sb.registerNewObjective("windyclans", Criteria.DUMMY, Text.c(this.plugin.settings().serverName));
      obj.numberFormat(NumberFormat.blank());
      if (!this.plugin.settings().sbDefault) {
         this.hidden.add(p.getUniqueId());
      }

      obj.setDisplaySlot(this.sidebarVisible(p) ? DisplaySlot.SIDEBAR : null);

      for (int i = 0; i < 15; i++) {
         Team t = sb.registerNewTeam("line" + i);
         t.addEntry(ENTRIES[i]);
      }

      this.boards.put(p.getUniqueId(), sb);
      p.setScoreboard(sb);
      this.update(p);
   }

   public void remove(Player p) {
      this.boards.remove(p.getUniqueId());
   }

   public boolean toggle(Player p) {
      UUID u = p.getUniqueId();
      if (!this.hidden.remove(u)) {
         this.hidden.add(u);
      }

      this.plugin.hud().refresh(p);
      return !this.hidden.contains(p.getUniqueId());
   }

   public boolean isHidden(Player p) {
      return this.hidden.contains(p.getUniqueId());
   }

   private boolean sidebarVisible(Player p) {
      return !this.hidden.contains(p.getUniqueId()) && !this.plugin.pack().has(p);
   }

   public void refreshVisibility(Player p) {
      Scoreboard sb = this.boards.get(p.getUniqueId());
      if (sb != null) {
         Objective obj = sb.getObjective("windyclans");
         if (obj != null) {
            DisplaySlot want = this.sidebarVisible(p) ? DisplaySlot.SIDEBAR : null;
            if (obj.getDisplaySlot() != want) {
               obj.setDisplaySlot(want);
            }
         }
      }
   }

   private void updateAll() {
      for (Player p : Bukkit.getOnlinePlayers()) {
         this.update(p);
      }
   }

   public void update(Player p) {
      Scoreboard sb = this.boards.get(p.getUniqueId());
      if (sb != null) {
         Objective obj = sb.getObjective("windyclans");
         if (obj != null) {
            obj.displayName(Text.c(this.plugin.settings().serverName));
            List<String> lines = this.buildLines(p);

            for (int i = 0; i < 15; i++) {
               Team t = sb.getTeam("line" + i);
               if (i < lines.size()) {
                  t.prefix(Text.c(lines.get(i)));
                  obj.getScore(ENTRIES[i]).setScore(15 - i);
               } else {
                  sb.resetScores(ENTRIES[i]);
               }
            }

            this.updateNameTags(p, sb);
            this.updateTabName(p);
            this.refreshVisibility(p);
            this.plugin.hud().update(p);
         }
      }
   }

   private List<String> buildLines(Player p) {
      ClanManager cm = this.plugin.clans();
      Settings s = this.plugin.settings();
      Clan mine = cm.of(p);
      ChunkPos pos = ChunkPos.of(p.getLocation());
      List<String> l = new ArrayList<>();
      l.add("&7" + "‒".repeat(16));
      l.add("&fZone");
      l.add(" " + this.zoneLabel(p, pos));
      long shield = this.plugin.shield() == null ? 0L : this.plugin.shield().remaining(p);
      if (shield > 0L) {
         l.add(" &a⛨ Protégé &f" + (shield >= 60L ? shield / 60L + "h" + String.format("%02d", shield % 60L) : shield + " min"));
      }
      l.add(" ");
      l.add("&fClan");
      if (mine == null) {
         l.add(" &8Aucun &7(/clan)");
      } else {
         Rank r = mine.rank(p.getUniqueId());
         l.add(" " + mine.coloredTag() + " &f" + mine.name());
         l.add(" &7Grade : " + r.colored());
         long need = cm.xpForNext(mine.level());
         int pct = mine.level() >= s.maxLevel ? 100 : (int)Math.min(100L, mine.xp() * 100L / Math.max(1L, need));
         l.add(" &7Niveau : &e" + mine.level() + " &8(" + pct + "%)");
         l.add(" &7Banque : &b" + mine.bank() + " " + this.currencyIcon());
         l.add(" &7En ligne : &a" + cm.onlineCount(mine) + "&7/" + mine.members().size());
         String war = this.plugin.war().status(mine);
         if (war != null) {
            l.add(" " + war);
         }
      }

      l.add("  ");
      l.add("&7Chunk &f" + pos.x() + ", " + pos.z());
      if (s.serverFooter != null && !s.serverFooter.isBlank()) {
         l.add(s.serverFooter);
      }

      return l;
   }

   private String currencyIcon() {
      if (this.plugin.settings().currencySpec.startsWith("windy:")) {
         return "&6⛁";
      } else {
         return switch (this.plugin.settings().currency) {
            case DIAMOND -> "&b◆";
            case EMERALD -> "&a◆";
            case GOLD_INGOT -> "&6■";
            default -> "";
         };
      }
   }

   public String zoneLabel(Player p, ChunkPos pos) {
      ClanManager cm = this.plugin.clans();
      if (this.plugin.bosses() != null && pos.world().equals("windy_arenes")) {
         return "&5Arène du boss";
      } else if (cm.isSpawnZone(pos)) {
         return "&eSpawn &7(protégé)";
      } else {
         Clan owner = cm.at(pos);
         if (owner == null) {
            return cm.isClaimWorld(pos.world()) ? "&2Zone sauvage" : "&2Hors territoire";
         } else {
            Relation rel = cm.relation(p, owner);
            String cap = cm.isCapitalZone(owner, pos) ? " &6★" : "";
            return rel.color() + owner.name() + cap;
         }
      }
   }

   private void updateNameTags(Player viewer, Scoreboard sb) {
      ClanManager cm = this.plugin.clans();
      Clan vClan = cm.of(viewer);

      for (Player other : Bukkit.getOnlinePlayers()) {
         Clan oc = cm.of(other);
         String teamName;
         Component prefix;
         NamedTextColor color;
         if (oc == null) {
            teamName = "nt_none";
            prefix = Component.empty();
            color = NamedTextColor.WHITE;
         } else {
            Relation rel = cm.relation(vClan, oc);
            if (vClan == null) {
               rel = Relation.NEUTRAL;
            }

            teamName = "nt_" + oc.id().toString().substring(0, 8) + rel.ordinal();
            prefix = Text.c(rel.color() + "[" + oc.tag() + "] ");

            color = switch (rel) {
               case SELF -> NamedTextColor.GREEN;
               case ALLY -> NamedTextColor.AQUA;
               case ENEMY -> NamedTextColor.RED;
               case WAR -> NamedTextColor.DARK_RED;
               default -> NamedTextColor.WHITE;
            };
         }

         Team t = sb.getTeam(teamName);
         if (t == null) {
            t = sb.registerNewTeam(teamName);
            t.color(color);
         }

         if (!prefix.equals(t.prefix())) {
            t.prefix(prefix);
         }

         Team current = sb.getEntryTeam(other.getName());
         if (current != t) {
            if (current != null) {
               current.removeEntry(other.getName());
            }

            t.addEntry(other.getName());
         }
      }
   }

   private void updateTabName(Player p) {
      Clan c = this.plugin.clans().of(p);
      ProfileManager pm = this.plugin.profiles();
      Profile pr = pm.of(p);
      RaceDef race = pm.race(pr);
      String rank = race == null ? "" : race.color() + pm.rankName(pr) + " ";
      Component name = c == null ? Text.c(rank + "&f" + p.getName()) : Text.c(c.coloredTag() + " " + rank + "&f" + p.getName());
      if (!name.equals(p.playerListName())) {
         p.playerListName(name);
      }
   }

   static {
      for (int i = 0; i < 15; i++) {
         ENTRIES[i] = "§" + Integer.toHexString(i) + "§r";
      }
   }
}
