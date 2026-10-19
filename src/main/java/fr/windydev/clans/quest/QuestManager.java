package fr.windydev.clans.quest;

import fr.windydev.clans.WindyClans;
import fr.windydev.clans.gui.Menu;
import fr.windydev.clans.profile.Profile;
import fr.windydev.clans.util.ItemBuilder;
import fr.windydev.clans.util.Text;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import net.kyori.adventure.title.Title;
import net.kyori.adventure.title.Title.Times;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.jetbrains.annotations.NotNull;

/** Quêtes du jour et de la semaine, série, classement de la semaine et titre de Champion (MAJ 1.21.0). */
public final class QuestManager implements Listener, CommandExecutor, TabCompleter {
   public enum Kind {
      KILL_MOB(Material.IRON_SWORD),
      MINE(Material.IRON_PICKAXE),
      CHOP(Material.IRON_AXE),
      HARVEST(Material.WHEAT),
      FISH(Material.FISHING_ROD),
      BREED(Material.WHEAT_SEEDS),
      ENCHANT(Material.ENCHANTING_TABLE),
      PLAY(Material.CLOCK),
      SOULS(Material.SOUL_LANTERN),
      RITUAL(Material.ENCHANTED_BOOK),
      BOSS(Material.WITHER_SKELETON_SKULL),
      DUNGEON(Material.MOSSY_STONE_BRICKS),
      RIFT(Material.END_PORTAL_FRAME),
      EVENT(Material.FIREWORK_ROCKET);

      final Material icon;

      Kind(Material icon) {
         this.icon = icon;
      }
   }

   static final String[] POOLS = new String[]{"facile", "moyenne", "difficile"};
   static final int WEEKLY_COUNT = 3;

   record Def(String id, String pool, Kind kind, Set<String> targets, int amount, String name, int minLevel, Reward reward) {
      boolean matches(String t) {
         if (this.targets.isEmpty()) {
            return true;
         } else {
            String u = t == null ? "" : t.toUpperCase(Locale.ROOT);
            return this.targets.contains(u) || u.startsWith("DEEPSLATE_") && this.targets.contains(u.substring(10));
         }
      }
   }

   static final class Slot {
      String id;
      int progress;
      boolean done;

      Slot(String id) {
         this.id = id;
      }
   }

   /** Données d'un joueur (quêtes, série, points, parcours). */
   static final class State {
      long day = -1L;
      final List<Slot> daily = new ArrayList<>();
      int rerolls;
      long week = -1L;
      final List<Slot> weekly = new ArrayList<>();
      int streak;
      long lastFullDay = -10L;
      long pointsWeek = -1L;
      int points;
      boolean everDone;
      int dungeons;
      final List<String> pending = new ArrayList<>();
      final Set<String> journey = new LinkedHashSet<>();
      boolean journeyInit;
      boolean journeyHidden;
      boolean journeyDone;
   }

   private final WindyClans plugin;
   private final Map<String, Def> defs = new LinkedHashMap<>();
   private final Map<UUID, State> states = new HashMap<>();
   private final Map<String, Integer> points = new HashMap<>();
   private final TreeMap<Integer, Reward> streakRewards = new TreeMap<>();
   private final Random rnd = new Random();
   private final Journey journey;
   private File dataFile;
   private boolean dirty;
   private boolean enabled = true;
   private ZoneId zone = ZoneId.of("Europe/Paris");
   private int rerollsPerDay = 1;
   private String championTitle = "&6✦ Champion &r";
   private Reward championReward = Reward.NONE;
   private boolean championDiscord = true;
   private UUID champion;
   private String championName;
   private long championWeek = -1L;
   private long processedWeek = -1L;

   public QuestManager(WindyClans plugin) {
      this.plugin = plugin;
      this.journey = new Journey(plugin, this);
   }

   public Journey journey() {
      return this.journey;
   }

   // ------------------------------------------------------------------ configuration

   public void load() {
      File f = new File(this.plugin.getDataFolder(), "quetes.yml");
      if (!f.exists()) {
         this.plugin.saveResource("quetes.yml", false);
      }

      YamlConfiguration y = YamlConfiguration.loadConfiguration(f);

      try (InputStream in = this.plugin.getResource("quetes.yml")) {
         if (in != null) {
            y.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)));
         }
      } catch (Exception var12) {
      }

      this.enabled = y.getBoolean("settings.enabled", true);

      try {
         this.zone = ZoneId.of(y.getString("settings.timezone", "Europe/Paris"));
      } catch (Exception var11) {
         this.zone = ZoneId.of("Europe/Paris");
      }

      this.rerollsPerDay = Math.max(0, y.getInt("settings.rerolls-per-day", 1));
      this.points.clear();
      this.points.put("facile", y.getInt("settings.points.facile", 1));
      this.points.put("moyenne", y.getInt("settings.points.moyenne", 2));
      this.points.put("difficile", y.getInt("settings.points.difficile", 3));
      this.points.put("semaine", y.getInt("settings.points.semaine", 5));
      this.streakRewards.clear();
      ConfigurationSection st = y.getConfigurationSection("streak");
      if (st != null) {
         for (String k : st.getKeys(false)) {
            try {
               this.streakRewards.put(Integer.parseInt(k), Reward.of(st.getConfigurationSection(k)));
            } catch (NumberFormatException var10) {
            }
         }
      }

      this.championTitle = y.getString("champion.title", "&6✦ Champion &r");
      this.championReward = Reward.of(y.getConfigurationSection("champion.reward"));
      this.championDiscord = y.getBoolean("champion.discord", true);
      this.defs.clear();

      for (String pool : POOLS) {
         this.loadPool(y.getConfigurationSection("daily." + pool), pool);
      }

      this.loadPool(y.getConfigurationSection("weekly"), "semaine");
      this.journey.load(y.getConfigurationSection("journey"));
      this.dataFile = new File(this.plugin.getDataFolder(), "quetes-data.yml");
      if (this.states.isEmpty()) {
         this.loadData();
      }

      this.plugin.getLogger().info(this.defs.size() + " quête(s) chargée(s).");
   }

   private void loadPool(ConfigurationSection sec, String pool) {
      if (sec != null) {
         for (String id : sec.getKeys(false)) {
            ConfigurationSection s = sec.getConfigurationSection(id);
            if (s != null) {
               try {
                  Kind k = Kind.valueOf(s.getString("type", "").toUpperCase(Locale.ROOT));
                  Set<String> targets = new HashSet<>();
                  String raw = s.getString("target", "");
                  if (raw != null) {
                     for (String t : raw.split(",")) {
                        if (!t.isBlank()) {
                           targets.add(t.trim().toUpperCase(Locale.ROOT));
                        }
                     }
                  }

                  String key = pool.equals("semaine") ? "w:" + id : id;
                  this.defs
                     .put(
                        key,
                        new Def(
                           key,
                           pool,
                           k,
                           targets,
                           Math.max(1, s.getInt("amount", 1)),
                           s.getString("name", id),
                           s.getInt("niveau-min", s.getInt("min-level", 0)),
                           Reward.of(s.getConfigurationSection("reward"))
                        )
                     );
               } catch (IllegalArgumentException var11) {
                  this.plugin.getLogger().warning("quetes.yml : type inconnu pour la quête '" + id + "' (" + s.getString("type") + ").");
               }
            }
         }
      }
   }

   public void start() {
      Bukkit.getScheduler().runTaskTimer(this.plugin, () -> {
         this.weeklyResult();
         if (this.dirty) {
            this.save();
         }
      }, 200L, 1200L);
      this.journey.start();
   }

   public void shutdown() {
      this.journey.shutdown();
      this.save();
   }

   // ------------------------------------------------------------------ données

   private void loadData() {
      YamlConfiguration y = YamlConfiguration.loadConfiguration(this.dataFile);
      this.processedWeek = y.getLong("processed-week", -1L);
      String ch = y.getString("champion.uuid");
      this.champion = ch == null ? null : UUID.fromString(ch);
      this.championName = y.getString("champion.name");
      this.championWeek = y.getLong("champion.week", -1L);
      ConfigurationSection ps = y.getConfigurationSection("players");
      if (ps != null) {
         for (String k : ps.getKeys(false)) {
            ConfigurationSection s = ps.getConfigurationSection(k);
            if (s != null) {
               UUID id;
               try {
                  id = UUID.fromString(k);
               } catch (IllegalArgumentException var9) {
                  continue;
               }

               State st = new State();
               st.day = s.getLong("day", -1L);
               st.rerolls = s.getInt("rerolls");
               readSlots(s.getStringList("daily"), st.daily);
               st.week = s.getLong("week", -1L);
               readSlots(s.getStringList("weekly"), st.weekly);
               st.streak = s.getInt("streak");
               st.lastFullDay = s.getLong("last-full-day", -10L);
               st.pointsWeek = s.getLong("points-week", -1L);
               st.points = s.getInt("points");
               st.everDone = s.getBoolean("ever-done");
               st.dungeons = s.getInt("dungeons");
               st.pending.addAll(s.getStringList("pending"));
               st.journey.addAll(s.getStringList("journey"));
               st.journeyInit = s.getBoolean("journey-init");
               st.journeyHidden = s.getBoolean("journey-hidden");
               st.journeyDone = s.getBoolean("journey-done");
               this.states.put(id, st);
            }
         }
      }
   }

   private static void readSlots(List<String> raw, List<Slot> out) {
      out.clear();

      for (String r : raw) {
         String[] p = r.split("\\|");
         Slot s = new Slot(p[0]);

         try {
            s.progress = p.length > 1 ? Integer.parseInt(p[1]) : 0;
         } catch (NumberFormatException var6) {
         }

         s.done = p.length > 2 && p[2].equals("1");
         out.add(s);
      }
   }

   private static List<String> writeSlots(List<Slot> in) {
      List<String> l = new ArrayList<>();

      for (Slot s : in) {
         l.add(s.id + "|" + s.progress + "|" + (s.done ? "1" : "0"));
      }

      return l;
   }

   public void save() {
      YamlConfiguration y = new YamlConfiguration();
      y.set("processed-week", this.processedWeek);
      if (this.champion != null) {
         y.set("champion.uuid", this.champion.toString());
         y.set("champion.name", this.championName);
         y.set("champion.week", this.championWeek);
      }

      for (Entry<UUID, State> e : this.states.entrySet()) {
         State s = e.getValue();
         String b = "players." + e.getKey() + ".";
         y.set(b + "day", s.day);
         y.set(b + "rerolls", s.rerolls);
         y.set(b + "daily", writeSlots(s.daily));
         y.set(b + "week", s.week);
         y.set(b + "weekly", writeSlots(s.weekly));
         y.set(b + "streak", s.streak);
         y.set(b + "last-full-day", s.lastFullDay);
         y.set(b + "points-week", s.pointsWeek);
         y.set(b + "points", s.points);
         y.set(b + "ever-done", s.everDone);
         y.set(b + "dungeons", s.dungeons);
         if (!s.pending.isEmpty()) {
            y.set(b + "pending", new ArrayList<>(s.pending));
         }

         y.set(b + "journey", new ArrayList<>(s.journey));
         y.set(b + "journey-init", s.journeyInit);
         y.set(b + "journey-hidden", s.journeyHidden);
         y.set(b + "journey-done", s.journeyDone);
      }

      try {
         y.save(this.dataFile);
         this.dirty = false;
      } catch (Exception var5) {
         this.plugin.getLogger().warning("quetes-data.yml : sauvegarde impossible : " + var5.getMessage());
      }
   }

   void markDirty() {
      this.dirty = true;
   }

   State state(UUID u) {
      return this.states.computeIfAbsent(u, k -> {
         this.dirty = true;
         return new State();
      });
   }

   // ------------------------------------------------------------------ temps

   public ZoneId zone() {
      return this.zone;
   }

   public long today() {
      return LocalDate.now(this.zone).toEpochDay();
   }

   /** Identifiant de la semaine : jour (epoch) du lundi. */
   public long thisWeek() {
      return LocalDate.now(this.zone).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toEpochDay();
   }

   private long secondsToMidnight() {
      ZonedDateTime now = ZonedDateTime.now(this.zone);
      ZonedDateTime next = now.toLocalDate().plusDays(1L).atStartOfDay(this.zone);
      return Duration.between(now, next).getSeconds();
   }

   private long secondsToMonday() {
      ZonedDateTime now = ZonedDateTime.now(this.zone);
      ZonedDateTime next = now.toLocalDate().with(TemporalAdjusters.next(DayOfWeek.MONDAY)).atStartOfDay(this.zone);
      return Duration.between(now, next).getSeconds();
   }

   static String duration(long seconds) {
      long h = seconds / 3600L;
      long m = seconds % 3600L / 60L;
      if (h >= 48L) {
         return h / 24L + " j " + h % 24L + " h";
      } else {
         return h > 0L ? h + " h " + String.format("%02d", m) : m + " min";
      }
   }

   // ------------------------------------------------------------------ tirage

   private void refresh(Player p, State s) {
      long d = this.today();
      if (s.day != d) {
         s.day = d;
         s.rerolls = 0;
         s.daily.clear();
         Random r = new Random(d * 1000003L + p.getUniqueId().hashCode());

         for (String pool : POOLS) {
            Def def = this.draw(pool, p, r, Set.of());
            if (def != null) {
               s.daily.add(new Slot(def.id));
            }
         }

         this.dirty = true;
      }

      long w = this.thisWeek();
      if (s.week != w) {
         s.week = w;
         s.weekly.clear();
         Random r = new Random(w * 7919L + p.getUniqueId().hashCode());
         Set<String> taken = new HashSet<>();

         for (int i = 0; i < WEEKLY_COUNT; i++) {
            Def def = this.draw("semaine", p, r, taken);
            if (def != null) {
               taken.add(def.id);
               s.weekly.add(new Slot(def.id));
            }
         }

         this.dirty = true;
      }
   }

   private Def draw(String pool, Player p, Random r, Set<String> exclude) {
      int lvl = this.plugin.profiles().of(p).level();
      List<Def> ok = new ArrayList<>();
      List<Def> all = new ArrayList<>();

      for (Def d : this.defs.values()) {
         if (d.pool.equals(pool) && !exclude.contains(d.id)) {
            all.add(d);
            if (d.minLevel <= lvl) {
               ok.add(d);
            }
         }
      }

      List<Def> from = ok.isEmpty() ? all : ok;
      return from.isEmpty() ? null : from.get(r.nextInt(from.size()));
   }

   // ------------------------------------------------------------------ progression

   /** Fait avancer les quêtes du joueur pour ce type d'objectif. */
   public void progress(Player p, Kind kind, String target, int amount) {
      if (this.enabled && p != null && amount > 0 && p.isOnline()) {
         if (p.getGameMode() != GameMode.CREATIVE && p.getGameMode() != GameMode.SPECTATOR) {
            State s = this.state(p.getUniqueId());
            this.refresh(p, s);

            for (Slot sl : new ArrayList<>(s.daily)) {
               this.step(p, s, sl, kind, target, amount, false);
            }

            for (Slot sl : new ArrayList<>(s.weekly)) {
               this.step(p, s, sl, kind, target, amount, true);
            }
         }
      }
   }

   private void step(Player p, State s, Slot sl, Kind kind, String target, int amount, boolean weekly) {
      if (!sl.done) {
         Def d = this.defs.get(sl.id);
         if (d != null && d.kind == kind && d.matches(target)) {
            int before = sl.progress;
            sl.progress = Math.min(d.amount, sl.progress + amount);
            this.dirty = true;
            if (sl.progress >= d.amount) {
               this.complete(p, s, sl, d, weekly);
            } else if (sl.progress * 4 / d.amount > before * 4 / d.amount) {
               p.sendActionBar(Text.c("&6✦ Quête &8| &f" + d.name + " &e" + sl.progress + "/" + d.amount));
            }
         }
      }
   }

   private void complete(Player p, State s, Slot sl, Def d, boolean weekly) {
      sl.done = true;
      sl.progress = d.amount;
      s.everDone = true;
      this.dirty = true;
      String got = d.reward.give(this.plugin, p, "quetes");
      int pts = this.points.getOrDefault(weekly ? "semaine" : d.pool, 1);
      this.addPoints(s, pts);
      p.showTitle(
         Title.title(
            Text.c("&6✦ Quête accomplie"),
            Text.c("&f" + d.name),
            Times.times(Duration.ofMillis(200L), Duration.ofMillis(2200L), Duration.ofMillis(500L))
         )
      );
      p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8F, 1.2F);
      this.plugin
         .msg(
            p,
            "&6✦ Quête "
               + (weekly ? "de la semaine" : "du jour")
               + " accomplie : &f"
               + d.name
               + (got.isEmpty() ? "" : " &8(" + got + "&8)")
               + " &e+"
               + pts
               + " pt"
               + (pts > 1 ? "s" : "")
         );
      if (!weekly && s.daily.stream().allMatch(x -> x.done)) {
         this.fullDay(p, s);
      }

      this.journey.check(p);
   }

   private void fullDay(Player p, State s) {
      long d = s.day;
      if (s.lastFullDay != d) {
         s.streak = s.lastFullDay == d - 1L ? s.streak + 1 : 1;
         s.lastFullDay = d;
         this.dirty = true;
         this.plugin.msg(p, "&a✔ Toutes les quêtes du jour sont faites ! &7Série : &f" + s.streak + " jour" + (s.streak > 1 ? "s" : "") + "&7.");
         Reward r = this.streakReward(s.streak);
         if (r != null && !r.empty()) {
            String got = r.give(this.plugin, p, "quetes");
            this.plugin.msg(p, "&6✹ Série de " + s.streak + " jours ! &8(" + got + "&8)");
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8F, 1.4F);
            if (s.streak >= 7) {
               Bukkit.broadcast(Text.c(this.plugin.settings().prefix + "&6✹ &f" + p.getName() + " &7a fini ses quêtes du jour &f" + s.streak + " jours de suite &7!"));
            }
         }
      }
   }

   private Reward streakReward(int streak) {
      Reward exact = this.streakRewards.get(streak);
      if (exact != null) {
         return exact;
      } else if (this.streakRewards.isEmpty()) {
         return null;
      } else {
         int max = this.streakRewards.lastKey();
         return streak > max && streak % max == 0 ? this.streakRewards.get(max) : null;
      }
   }

   /** Série en cours (0 si elle est cassée). */
   public int streak(UUID u) {
      State s = this.states.get(u);
      return s != null && s.lastFullDay >= this.today() - 1L ? s.streak : 0;
   }

   private void addPoints(State s, int n) {
      long w = this.thisWeek();
      if (s.pointsWeek != w) {
         s.pointsWeek = w;
         s.points = 0;
      }

      s.points += n;
      this.dirty = true;
   }

   /** Quêtes du jour pas encore finies. */
   public int openCount(Player p) {
      if (!this.enabled) {
         return 0;
      } else {
         State s = this.state(p.getUniqueId());
         this.refresh(p, s);
         return (int)s.daily.stream().filter(x -> !x.done).count();
      }
   }

   public int points(UUID u) {
      State s = this.states.get(u);
      return s != null && s.pointsWeek == this.thisWeek() ? s.points : 0;
   }

   /** Classement de la semaine en cours : (joueur, points), du meilleur au moins bon. */
   public List<Entry<UUID, Integer>> ranking() {
      long w = this.thisWeek();
      List<Entry<UUID, Integer>> l = new ArrayList<>();

      for (Entry<UUID, State> e : this.states.entrySet()) {
         if (e.getValue().pointsWeek == w && e.getValue().points > 0) {
            l.add(Map.entry(e.getKey(), e.getValue().points));
         }
      }

      l.sort((a, b) -> {
         int c = Integer.compare(b.getValue(), a.getValue());
         return c != 0 ? c : this.nameOf(a.getKey()).compareToIgnoreCase(this.nameOf(b.getKey()));
      });
      return l;
   }

   String nameOf(UUID u) {
      Profile pr = this.plugin.profiles().get(u);
      if (pr != null) {
         return pr.name();
      } else {
         OfflinePlayer op = Bukkit.getOfflinePlayer(u);
         return op.getName() == null ? "?" : op.getName();
      }
   }

   /** Titre affiché dans le chat (Champion de la semaine), vide sinon. */
   public String title(UUID u) {
      return this.champion != null && this.champion.equals(u) && this.championWeek == this.thisWeek() ? this.championTitle : "";
   }

   public void dungeonDone(Player p) {
      State s = this.state(p.getUniqueId());
      s.dungeons++;
      this.dirty = true;
      this.progress(p, Kind.DUNGEON, "", 1);
      this.journey.check(p);
   }

   int dungeons(UUID u) {
      State s = this.states.get(u);
      return s == null ? 0 : s.dungeons;
   }

   boolean everDone(UUID u) {
      State s = this.states.get(u);
      return s != null && s.everDone;
   }

   // ------------------------------------------------------------------ Champion de la semaine

   private void weeklyResult() {
      long w = this.thisWeek();
      if (this.processedWeek < 0L) {
         this.processedWeek = w;
         this.dirty = true;
      } else if (this.processedWeek < w) {
         this.processedWeek = w;
         this.dirty = true;
         long prev = w - 7L;
         UUID best = null;
         int bp = 0;

         for (Entry<UUID, State> e : this.states.entrySet()) {
            State s = e.getValue();
            if (s.pointsWeek == prev && (s.points > bp || s.points == bp && best != null && this.nameOf(e.getKey()).compareToIgnoreCase(this.nameOf(best)) < 0)) {
               if (s.points > 0) {
                  best = e.getKey();
                  bp = s.points;
               }
            }
         }

         if (best != null) {
            this.crown(best, bp, w);
         }

         this.save();
      }
   }

   private void crown(UUID best, int bp, long week) {
      this.champion = best;
      this.championWeek = week;
      this.championName = this.nameOf(best);
      Player online = Bukkit.getPlayer(best);
      if (online != null) {
         this.giveChampion(online);
      } else {
         this.state(best).pending.add("champion");
      }

      Bukkit.broadcast(
         Text.c(
            this.plugin.settings().prefix
               + "&6✦ &f"
               + this.championName
               + " &7est le &6Champion de la semaine &7avec &e"
               + bp
               + " points &7de quêtes ! Titre "
               + this.championTitle
               + "&7jusqu'à lundi prochain."
         )
      );
      if (this.championDiscord) {
         this.plugin
            .discord()
            .send(
               "quests",
               "🏆 **" + this.championName + "** est le **Champion de la semaine** avec **" + bp + " points** de quêtes ! Il porte le titre en jeu jusqu'à lundi prochain."
            );
      }

      this.dirty = true;
   }

   private void giveChampion(Player p) {
      String got = this.championReward.give(this.plugin, p, "quetes");
      p.showTitle(Title.title(Text.c("&6✦ Champion de la semaine ✦"), Text.c("&7Bravo ! " + Text.strip(this.championTitle).trim())));
      p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0F, 1.0F);
      this.plugin.msg(p, "&6✦ Tu es le Champion de la semaine ! " + (got.isEmpty() ? "" : "&8(" + got + "&8)"));
   }

   @EventHandler
   public void onQuit(org.bukkit.event.player.PlayerQuitEvent e) {
      this.journey.quit(e.getPlayer());
   }

   @EventHandler
   public void onJoin(PlayerJoinEvent e) {
      Player p = e.getPlayer();
      Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
         if (p.isOnline()) {
            State s = this.states.get(p.getUniqueId());
            if (s != null && s.pending.remove("champion")) {
               this.giveChampion(p);
               this.dirty = true;
            }

            if (this.enabled) {
               State st = this.state(p.getUniqueId());
               this.refresh(p, st);
               long left = st.daily.stream().filter(x -> !x.done).count();
               if (left > 0L && this.plugin.profiles().of(p).race() != null) {
                  p.sendMessage(
                     Text.c(this.plugin.settings().prefix + "&6✦ &7Tu as &f" + left + " quête" + (left > 1L ? "s" : "") + " du jour &7à faire. ")
                        .append(
                           Text.c("&e&l[VOIR]")
                              .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/quetes"))
                              .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(Text.c("&7Ouvrir les quêtes")))
                        )
                  );
               }
            }
         }
      }, 100L);
   }

   // ------------------------------------------------------------------ évènements de jeu

   @EventHandler(
      priority = EventPriority.MONITOR
   )
   public void onKill(EntityDeathEvent e) {
      LivingEntity dead = e.getEntity();
      Player k = dead.getKiller();
      if (k != null && !(dead instanceof Player) && dead instanceof Enemy) {
         SpawnReason r = dead.getEntitySpawnReason();
         if (r != SpawnReason.SPAWNER && r != SpawnReason.SPAWNER_EGG) {
            this.progress(k, Kind.KILL_MOB, dead.getType().name(), 1);
         }
      }
   }

   @EventHandler(
      priority = EventPriority.MONITOR,
      ignoreCancelled = true
   )
   public void onFish(PlayerFishEvent e) {
      if (e.getState() == PlayerFishEvent.State.CAUGHT_FISH) {
         this.progress(e.getPlayer(), Kind.FISH, "", 1);
      }
   }

   @EventHandler(
      priority = EventPriority.MONITOR,
      ignoreCancelled = true
   )
   public void onBreed(EntityBreedEvent e) {
      if (e.getBreeder() instanceof Player p) {
         this.progress(p, Kind.BREED, e.getEntity().getType().name(), 1);
      }
   }

   @EventHandler(
      priority = EventPriority.MONITOR,
      ignoreCancelled = true
   )
   public void onEnchant(EnchantItemEvent e) {
      this.progress(e.getEnchanter(), Kind.ENCHANT, e.getItem().getType().name(), 1);
   }

   // ------------------------------------------------------------------ menus

   public void menu(Player p) {
      if (!this.enabled) {
         this.plugin.msg(p, "&7Les quêtes sont désactivées.");
      } else {
         State s = this.state(p.getUniqueId());
         this.refresh(p, s);
         Menu m = new Menu(p, "list", 6, "&6Quêtes");
         List<Entry<UUID, Integer>> rank = this.ranking();
         int pos = 0;

         for (int i = 0; i < rank.size(); i++) {
            if (rank.get(i).getKey().equals(p.getUniqueId())) {
               pos = i + 1;
               break;
            }
         }

         int streak = this.streak(p.getUniqueId());
         List<String> head = new ArrayList<>();
         head.add("&7Série : &f" + streak + " jour" + (streak > 1 ? "s" : "") + (streak > 0 ? " &6✹" : ""));
         Integer next = this.streakRewards.higherKey(streak);
         if (next != null) {
            head.add("&8Prochain palier : " + next + " jours");
         }

         head.add("&7Points de la semaine : &e" + this.points(p.getUniqueId()));
         head.add("&7Classement : " + (pos > 0 ? "&f#" + pos : "&8pas encore classé"));
         head.add("");
         head.add("&8Toutes les quêtes du jour finies");
         head.add("&8plusieurs jours de suite = série.");
         if (!this.title(p.getUniqueId()).isEmpty()) {
            head.add("");
            head.add("&6✦ Tu es le Champion de la semaine !");
         }

         m.set(4, ItemBuilder.of(Material.NETHER_STAR).name("&6Tes quêtes").lore(head).glow(streak > 0).build());
         m.set(
            10,
            ItemBuilder.of(Material.CLOCK)
               .name("&eQuêtes du jour")
               .lore(
                  "&7Nouvelles quêtes dans &f" + duration(this.secondsToMidnight()) + "&7.",
                  "",
                  "&7Changements restants : &f" + Math.max(0, this.rerollsPerDay - s.rerolls),
                  "&8Clique sur une quête pour la changer."
               )
               .build()
         );
         int[] dslots = new int[]{12, 14, 16};

         for (int i = 0; i < s.daily.size() && i < dslots.length; i++) {
            int idx = i;
            Slot sl = s.daily.get(i);
            Def d = this.defs.get(sl.id);
            if (d != null) {
               boolean canReroll = !sl.done && s.rerolls < this.rerollsPerDay;
               m.set(dslots[i], this.questItem(d, sl, false, canReroll), canReroll ? e -> this.confirmReroll(p, idx) : null);
            }
         }

         m.set(
            28,
            ItemBuilder.of(Material.WRITABLE_BOOK)
               .name("&dQuêtes de la semaine")
               .lore("&7Nouvelles quêtes dans &f" + duration(this.secondsToMonday()) + "&7.", "", "&7Elles rapportent &e" + this.points.getOrDefault("semaine", 5) + " points &7chacune.")
               .build()
         );
         int[] wslots = new int[]{30, 32, 34};

         for (int i = 0; i < s.weekly.size() && i < wslots.length; i++) {
            Slot sl = s.weekly.get(i);
            Def d = this.defs.get(sl.id);
            if (d != null) {
               m.set(wslots[i], this.questItem(d, sl, true, false));
            }
         }

         List<String> top = new ArrayList<>();
         if (this.champion != null && this.championWeek == this.thisWeek()) {
            top.add("&6Champion en titre : &f" + this.championName);
            top.add("");
         }

         if (rank.isEmpty()) {
            top.add("&8Personne n'a encore de points.");
         } else {
            for (int i = 0; i < rank.size() && i < 10; i++) {
               Entry<UUID, Integer> en = rank.get(i);
               String medal = i == 0 ? "&6#1" : (i == 1 ? "&7#2" : (i == 2 ? "&c#3" : "&8#" + (i + 1)));
               top.add(medal + " &f" + this.nameOf(en.getKey()) + " &7- &e" + en.getValue() + " pts");
            }
         }

         top.add("");
         top.add("&7Le premier dimanche soir devient");
         top.add("&6Champion de la semaine &7(titre + récompense).");
         m.set(47, ItemBuilder.of(Material.GOLDEN_HELMET).name("&6Classement de la semaine").lore(top).build());
         m.set(45, ItemBuilder.of(Material.ARROW).name("&7Retour").build(), e -> this.plugin.menus().profile(p));
         m.set(49, ItemBuilder.of(Material.BARRIER).name("&cFermer").build(), e -> p.closeInventory());
         m.set(51, this.journey.icon(p), e -> this.journey.menu(p));
         if (this.plugin.weeklyEvent() != null) {
            m.set(53, this.plugin.weeklyEvent().icon(), e -> {
               p.closeInventory();
               this.plugin.weeklyEvent().info(p);
            });
         }

         m.fillBorder();
         m.open(p);
      }
   }

   private org.bukkit.inventory.ItemStack questItem(Def d, Slot sl, boolean weekly, boolean canReroll) {
      List<String> lore = new ArrayList<>();
      lore.add(weekly ? "&dQuête de la semaine" : "&7Difficulté : " + poolLabel(d.pool));
      lore.add("");
      lore.add(bar(sl.progress, d.amount) + " &f" + sl.progress + "/" + d.amount);
      lore.add("");
      lore.add("&7Récompense :");
      lore.addAll(d.reward.lines(this.plugin));
      int pts = this.points.getOrDefault(weekly ? "semaine" : d.pool, 1);
      lore.add("&e  + " + pts + " point" + (pts > 1 ? "s" : "") + " de la semaine");
      if (sl.done) {
         lore.add("");
         lore.add("&a✔ Terminée");
      } else if (canReroll) {
         lore.add("");
         lore.add("&8Clic : changer cette quête");
      }

      return ItemBuilder.of(sl.done ? Material.LIME_DYE : d.kind.icon)
         .name((sl.done ? "&a✔ " : (weekly ? "&d" : "&e")) + d.name)
         .lore(lore)
         .glow(sl.done)
         .build();
   }

   static String poolLabel(String pool) {
      return switch (pool) {
         case "facile" -> "&aFacile";
         case "moyenne" -> "&eMoyenne";
         case "difficile" -> "&cDifficile";
         default -> "&dSemaine";
      };
   }

   static String bar(int have, int need) {
      int n = need <= 0 ? 10 : Math.min(10, have * 10 / need);
      return "&a" + "▮".repeat(n) + "&8" + "▮".repeat(10 - n);
   }

   private void confirmReroll(Player p, int idx) {
      State s = this.state(p.getUniqueId());
      if (idx < s.daily.size()) {
         Slot sl = s.daily.get(idx);
         Def d = this.defs.get(sl.id);
         Menu m = new Menu(p, "trio", 3, "&eChanger de quête ?");
         m.set(
            11,
            ItemBuilder.of(Material.LIME_DYE)
               .name("&a✔ Changer")
               .lore("&7Une autre quête " + Text.strip(poolLabel(d == null ? "facile" : d.pool)).toLowerCase(Locale.ROOT) + " la remplace.", "&8La progression actuelle est perdue.")
               .build(),
            e -> {
               this.reroll(p, idx);
               this.menu(p);
            }
         );
         m.set(13, d == null ? ItemBuilder.of(Material.PAPER).name("?").build() : this.questItem(d, sl, false, false));
         m.set(15, ItemBuilder.of(Material.RED_DYE).name("&c✖ Garder").build(), e -> this.menu(p));
         m.set(22, ItemBuilder.of(Material.ARROW).name("&7Retour").build(), e -> this.menu(p));
         m.fillBorder();
         m.open(p);
      }
   }

   private void reroll(Player p, int idx) {
      State s = this.state(p.getUniqueId());
      this.refresh(p, s);
      if (idx < s.daily.size()) {
         Slot sl = s.daily.get(idx);
         Def d = this.defs.get(sl.id);
         if (sl.done) {
            this.plugin.msg(p, "&cCette quête est déjà terminée.");
         } else if (s.rerolls >= this.rerollsPerDay) {
            this.plugin.msg(p, "&cTu as déjà changé de quête aujourd'hui.");
         } else {
            Set<String> current = new HashSet<>();
            s.daily.forEach(x -> current.add(x.id));
            Def nd = this.draw(d == null ? POOLS[Math.min(idx, 2)] : d.pool, p, this.rnd, current);
            if (nd == null) {
               this.plugin.msg(p, "&cAucune autre quête disponible.");
            } else {
               s.daily.set(idx, new Slot(nd.id));
               s.rerolls++;
               this.dirty = true;
               this.plugin.msg(p, "&7Nouvelle quête : &f" + nd.name + "&7.");
            }
         }
      }
   }

   // ------------------------------------------------------------------ commandes

   @Override
   public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, @NotNull String[] args) {
      String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
      if (label.equalsIgnoreCase("parcours") || sub.equals("parcours")) {
         if (sender instanceof Player p) {
            String a = label.equalsIgnoreCase("parcours") ? sub : (args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "");
            this.journey.command(p, a);
         }

         return true;
      } else if (sub.equals("top") || sub.equals("classement")) {
         List<Entry<UUID, Integer>> rank = this.ranking();
         this.plugin.msg(sender, "&6Classement des quêtes de la semaine :");
         if (rank.isEmpty()) {
            sender.sendMessage(Text.c("&8  personne pour l'instant"));
         }

         for (int i = 0; i < rank.size() && i < 10; i++) {
            sender.sendMessage(Text.c("&e  #" + (i + 1) + " &f" + this.nameOf(rank.get(i).getKey()) + " &7- &e" + rank.get(i).getValue() + " pts"));
         }

         return true;
      } else if (sub.equals("admin") && sender.hasPermission("windyclans.admin")) {
         this.admin(sender, args);
         return true;
      } else {
         if (sender instanceof Player p) {
            this.menu(p);
         }

         return true;
      }
   }

   private void admin(CommandSender s, String[] args) {
      if (args.length < 3) {
         this.plugin.msg(s, "&c/quetes admin <joueur> <finir <1-6>|jour|semaine|serie <n>|points <n>|parcours-reset|champion>");
      } else {
         Player t = Bukkit.getPlayer(args[1]);
         if (t == null) {
            this.plugin.msg(s, "&cJoueur hors ligne.");
         } else {
            State st = this.state(t.getUniqueId());
            this.refresh(t, st);
            String a = args[2].toLowerCase(Locale.ROOT);
            switch (a) {
               case "finir":
                  int n = args.length > 3 ? parse(args[3]) : 1;
                  List<Slot> all = new ArrayList<>(st.daily);
                  all.addAll(st.weekly);
                  if (n >= 1 && n <= all.size()) {
                     Slot sl = all.get(n - 1);
                     Def d = this.defs.get(sl.id);
                     if (d != null && !sl.done) {
                        this.complete(t, st, sl, d, n > st.daily.size());
                     }
                  }

                  this.plugin.msg(s, "&aQuête " + n + " de " + t.getName() + " terminée.");
                  break;
               case "jour":
                  st.day = -1L;
                  this.refresh(t, st);
                  this.plugin.msg(s, "&aNouvelles quêtes du jour tirées pour " + t.getName() + ".");
                  break;
               case "semaine":
                  st.week = -1L;
                  this.refresh(t, st);
                  this.plugin.msg(s, "&aNouvelles quêtes de la semaine tirées pour " + t.getName() + ".");
                  break;
               case "serie":
                  st.streak = args.length > 3 ? parse(args[3]) : 0;
                  st.lastFullDay = this.today() - 1L;
                  this.plugin.msg(s, "&aSérie de " + t.getName() + " : " + st.streak + ".");
                  break;
               case "points":
                  st.pointsWeek = this.thisWeek();
                  st.points = args.length > 3 ? parse(args[3]) : 0;
                  this.plugin.msg(s, "&aPoints de " + t.getName() + " : " + st.points + ".");
                  break;
               case "parcours-reset":
                  st.journey.clear();
                  st.journeyInit = true;
                  st.journeyDone = false;
                  st.journeyHidden = false;
                  st.dungeons = 0;
                  this.plugin.msg(s, "&aParcours de " + t.getName() + " remis à zéro.");
                  break;
               case "champion":
                  this.crown(t.getUniqueId(), this.points(t.getUniqueId()), this.thisWeek());
                  break;
               default:
                  this.plugin.msg(s, "&c/quetes admin <joueur> <finir <1-6>|jour|semaine|serie <n>|points <n>|parcours-reset|champion>");
            }

            this.dirty = true;
         }
      }
   }

   private static int parse(String s) {
      try {
         return Integer.parseInt(s);
      } catch (NumberFormatException var2) {
         return 0;
      }
   }

   @Override
   public List<String> onTabComplete(@NotNull CommandSender s, @NotNull Command c, @NotNull String label, @NotNull String[] args) {
      if (label.equalsIgnoreCase("parcours")) {
         return args.length == 1 ? filter(List.of("masquer", "afficher"), args[0]) : List.of();
      } else if (args.length == 1) {
         List<String> l = new ArrayList<>(List.of("top", "parcours"));
         if (s.hasPermission("windyclans.admin")) {
            l.add("admin");
         }

         return filter(l, args[0]);
      } else if (args.length == 2 && args[0].equalsIgnoreCase("admin") && s.hasPermission("windyclans.admin")) {
         return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[1]);
      } else {
         return args.length == 3 && args[0].equalsIgnoreCase("admin") && s.hasPermission("windyclans.admin")
            ? filter(List.of("finir", "jour", "semaine", "serie", "points", "parcours-reset", "champion"), args[2])
            : List.of();
      }
   }

   static List<String> filter(List<String> l, String start) {
      String st = start.toLowerCase(Locale.ROOT);
      return l.stream().filter(x -> x.toLowerCase(Locale.ROOT).startsWith(st)).toList();
   }
}
