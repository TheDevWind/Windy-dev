package fr.windydev.clans.profile;

import fr.windydev.clans.WindyClans;
import fr.windydev.clans.model.Clan;
import fr.windydev.clans.util.Text;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.Map.Entry;
import net.kyori.adventure.title.Title;
import net.kyori.adventure.title.Title.Times;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.World.Environment;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerFishEvent.State;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

public class ProfileManager implements Listener {
   private final WindyClans plugin;
   private final File dataFile;
   private final Map<UUID, Profile> profiles = new HashMap<>();
   private final Map<String, RaceDef> races = new LinkedHashMap<>();
   private final NamespacedKey placedKey;
   private boolean dirty;
   int maxLevel = 100;
   int tierEvery = 20;
   int xpBase = 150;
   int xpStep = 25;
   int xpSquare = 3;
   int levelCoins = 5;
   int tierCoins = 100;
   private int soulMob = 1;
   private int soulPlayer = 25;
   private int soulBoss = 200;
   private double soulSteal = 0.2;
   private long soulPlayerCooldown = 600000L;
   private final Map<String, Long> recentKills = new HashMap<>();
   private int antiFree = 30;
   private int playDailyCap = 600;
   private double antiMin = 0.2;
   private final Map<UUID, Map<String, ArrayDeque<Long>>> farm = new HashMap<>();
   private final Map<UUID, Integer> playToday = new HashMap<>();
   private long playDay = -1L;
   boolean chooseOnJoin = true;
   private final Map<String, Integer> mobXp = new HashMap<>();
   private final Map<Material, Integer> blockXp = new EnumMap<>(Material.class);
   private int mobDefault = 6;
   private int warKill = 40;
   private int bossPerLevel = 100;
   private int logsXp = 1;
   private int cropsXp = 1;
   private int fishingXp = 6;
   private int ritualPerTier = 60;
   private int playMinutes = 5;
   private int playXp = 20;
   private double spawnerFactor = 0.2;
   private final Map<UUID, Location> lastPos = new HashMap<>();
   private final Map<UUID, Integer> activeMinutes = new HashMap<>();
   private final Map<UUID, Integer> hurtCounter = new HashMap<>();
   final Map<UUID, Long> jumpReady = new HashMap<>();
   final Map<UUID, Integer> airJumps = new HashMap<>();
   private final Map<UUID, Map<String, List<ProfileManager.Contrib>>> skillCache = new HashMap<>();
   private final SkillTree tree;

   public ProfileManager(WindyClans plugin) {
      this.plugin = plugin;
      this.dataFile = new File(plugin.getDataFolder(), "profiles.yml");
      this.placedKey = new NamespacedKey(plugin, "placed");
      this.tree = new SkillTree(plugin);
   }

   public SkillTree tree() {
      return this.tree;
   }

   public void load() {
      File f = new File(this.plugin.getDataFolder(), "races.yml");
      if (!f.exists()) {
         this.plugin.saveResource("races.yml", false);
      }

      YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
      if (y.getInt("config-version", 1) < 2) {
         f.renameTo(new File(this.plugin.getDataFolder(), "races-ancien.yml"));
         this.plugin.saveResource("races.yml", true);
         y = YamlConfiguration.loadConfiguration(f);
      }

      YamlConfiguration def = YamlConfiguration.loadConfiguration(
         new InputStreamReader(Objects.requireNonNull(this.plugin.getResource("races.yml")), StandardCharsets.UTF_8)
      );
      y.setDefaults(def);
      this.maxLevel = Math.max(2, y.getInt("settings.max-level"));
      this.tierEvery = Math.max(1, y.getInt("settings.tier-every"));
      this.xpBase = y.getInt("settings.xp-curve.base");
      this.xpStep = y.getInt("settings.xp-curve.step");
      this.xpSquare = y.getInt("settings.xp-curve.square");
      this.antiFree = y.getInt("xp.anti-farm.free", 30);
      this.antiMin = y.getDouble("xp.anti-farm.min", 0.2);
      this.playDailyCap = y.getInt("xp.playtime-daily-cap", 600);
      this.soulMob = y.getInt("souls.mob", 1);
      this.soulPlayer = y.getInt("souls.player", 25);
      this.soulBoss = y.getInt("souls.boss", 200);
      this.soulSteal = y.getDouble("souls.steal-on-death", 0.2);
      this.soulPlayerCooldown = y.getLong("souls.same-victim-minutes", 10L) * 60000L;
      this.levelCoins = y.getInt("settings.level-reward-coins");
      this.tierCoins = y.getInt("settings.tier-reward-coins");
      this.chooseOnJoin = y.getBoolean("settings.choose-on-join");
      this.mobXp.clear();
      ConfigurationSection mobs = y.getConfigurationSection("xp.mobs");
      if (mobs != null) {
         for (String k : mobs.getKeys(false)) {
            this.mobXp.put(k.toLowerCase(Locale.ROOT), mobs.getInt(k));
         }
      }

      this.mobDefault = this.mobXp.getOrDefault("default", 6);
      this.spawnerFactor = y.getDouble("xp.spawner-factor");
      this.warKill = y.getInt("xp.war-kill");
      this.bossPerLevel = y.getInt("xp.boss-per-level");
      this.blockXp.clear();
      ConfigurationSection blocks = y.getConfigurationSection("xp.blocks");
      if (blocks != null) {
         for (String k : blocks.getKeys(false)) {
            Material m = Material.matchMaterial(k);
            if (m != null) {
               this.blockXp.put(m, blocks.getInt(k));
            }
         }
      }

      this.logsXp = y.getInt("xp.logs");
      this.cropsXp = y.getInt("xp.crops");
      this.fishingXp = y.getInt("xp.fishing");
      this.ritualPerTier = y.getInt("xp.ritual-per-tier");
      this.playMinutes = Math.max(1, y.getInt("xp.playtime.minutes"));
      this.playXp = y.getInt("xp.playtime.xp");
      this.races.clear();
      ConfigurationSection rs = y.isConfigurationSection("races") && !y.getConfigurationSection("races").getKeys(false).isEmpty()
         ? y.getConfigurationSection("races")
         : def.getConfigurationSection("races");

      for (String id : rs.getKeys(false)) {
         ConfigurationSection s = rs.getConfigurationSection(id);
         if (s != null) {
            Material icon = Material.matchMaterial(s.getString("icon", "PAPER"));
            ConfigurationSection malus = s.getConfigurationSection("malus");
            RaceDef.Hurt hurt = null;
            if (malus != null && malus.isConfigurationSection("hurt")) {
               ConfigurationSection h = malus.getConfigurationSection("hurt");
               hurt = new RaceDef.Hurt(h.getString("when", "water"), h.getDouble("damage", 1.0), Math.max(1, h.getInt("every", 4)));
            }

            this.races
               .put(
                  id.toLowerCase(Locale.ROOT),
                  new RaceDef(
                     id.toLowerCase(Locale.ROOT),
                     s.getString("name", id),
                     s.getString("color", "&f"),
                     icon == null ? Material.PAPER : icon,
                     s.getStringList("description"),
                     this.stats(s.getConfigurationSection("bonus")),
                     this.stats(malus),
                     this.effects(s.getMapList("effects")),
                     malus == null ? List.of() : this.effects(malus.getMapList("effects")),
                     hurt
                  )
               );
         }
      }

      this.plugin.getLogger().info(this.races.size() + " race(s) chargée(s), niveau max " + this.maxLevel + ".");
      this.tree.load();
      this.skillCache.clear();
   }

   private Map<String, double[]> stats(ConfigurationSection s) {
      Map<String, double[]> out = new LinkedHashMap<>();
      if (s == null) {
         return out;
      } else {
         for (String k : s.getKeys(false)) {
            if (!k.equals("effects") && !k.equals("hurt")) {
               if (!Stats.META.containsKey(k)) {
                  this.plugin.getLogger().warning("races.yml : statistique inconnue '" + k + "'");
               } else if (s.isList(k)) {
                  List<Double> l = s.getDoubleList(k);
                  if (l.size() >= 2) {
                     out.put(k, new double[]{l.get(0), l.get(1)});
                  } else if (l.size() == 1) {
                     out.put(k, new double[]{l.get(0)});
                  }
               } else {
                  out.put(k, new double[]{s.getDouble(k)});
               }
            }
         }

         return out;
      }
   }

   private List<RaceDef.Effect> effects(List<Map<?, ?>> list) {
      List<RaceDef.Effect> out = new ArrayList<>();

      for (Map<?, ?> m : list) {
         String type = String.valueOf(m.get("type")).toLowerCase(Locale.ROOT);
         PotionEffectType t = (PotionEffectType)Registry.EFFECT.get(NamespacedKey.minecraft(type));
         if (t == null) {
            this.plugin.getLogger().warning("races.yml : effet inconnu '" + type + "'");
         } else {
            out.add(
               new RaceDef.Effect(t, num(m.get("amplifier"), 0), String.valueOf(m.get("when") == null ? "always" : m.get("when")), num(m.get("from-tier"), 0))
            );
         }
      }

      return out;
   }

   private static int num(Object o, int def) {
      if (o instanceof Number n) {
         return n.intValue();
      } else {
         try {
            return Integer.parseInt(String.valueOf(o));
         } catch (Exception var3) {
            return def;
         }
      }
   }

   public void loadData() {
      this.profiles.clear();
      YamlConfiguration y = YamlConfiguration.loadConfiguration(this.dataFile);
      if (this.dataFile.exists() && y.getInt("data-version", 1) < 2) {
         this.dataFile.renameTo(new File(this.plugin.getDataFolder(), "profiles-ancien.yml"));
         this.plugin.getLogger().info("Profils remis à zéro pour les rangs d'Éveil (ancienne sauvegarde : profiles-ancien.yml).");
         this.dirty = true;
      } else {
         ConfigurationSection ps = y.getConfigurationSection("players");
         if (ps != null) {
            Iterator var3 = ps.getKeys(false).iterator();

            while (true) {
               ConfigurationSection s;
               UUID id;
               while (true) {
                  if (!var3.hasNext()) {
                     return;
                  }

                  String k = (String)var3.next();
                  s = ps.getConfigurationSection(k);
                  if (s != null) {
                     try {
                        id = UUID.fromString(k);
                        break;
                     } catch (IllegalArgumentException var12) {
                     }
                  }
               }

               Profile p = new Profile(id, s.getString("name", "?"));
               p.race = s.getString("race");
               if (p.race != null && !this.races.containsKey(p.race)) {
                  p.race = null;
               }

               p.level = Math.max(1, Math.min(this.maxLevel, s.getInt("level", 1)));
               p.xp = s.getLong("xp");
               p.totalXp = s.getLong("total-xp");
               p.raceChanges = s.getInt("race-changes");
               p.playMinutes = s.getLong("play-minutes");
               p.mobKills = s.getInt("mob-kills");
               p.bossWins = s.getInt("boss-wins");
               p.rituals = s.getInt("rituals");
               p.rank = Math.max(1, Math.min(5, s.getInt("rank", 1)));
               p.level = Math.min(p.level, this.levelCap(p));
               p.souls = s.getLong("souls");
               p.awakenRetryAt = s.getLong("awaken-retry-at");
               p.awakenInterrupts = Math.max(0, s.getInt("awaken-interrupts.count"));
               p.awakenInterruptRank = s.getInt("awaken-interrupts.rank");
               ConfigurationSection skl = s.getConfigurationSection("skills");
               if (skl != null) {
                  for (String nk : skl.getKeys(false)) {
                     p.skills.put(nk.replace('_', '.'), skl.getInt(nk));
                  }
               }

               ConfigurationSection src = s.getConfigurationSection("sources");
               if (src != null) {
                  for (String sk : src.getKeys(false)) {
                     p.sources.put(sk, src.getLong(sk));
                  }
               }

               this.profiles.put(id, p);
            }
         }
      }
   }

   public void save() {
      YamlConfiguration y = new YamlConfiguration();
      y.set("data-version", 2);

      for (Profile p : this.profiles.values()) {
         String b = "players." + p.id + ".";
         y.set(b + "name", p.name);
         y.set(b + "race", p.race);
         y.set(b + "level", p.level);
         y.set(b + "xp", p.xp);
         y.set(b + "total-xp", p.totalXp);
         y.set(b + "race-changes", p.raceChanges);
         y.set(b + "play-minutes", p.playMinutes);
         y.set(b + "mob-kills", p.mobKills);
         y.set(b + "boss-wins", p.bossWins);
         y.set(b + "rituals", p.rituals);
         y.set(b + "rank", p.rank);
         y.set(b + "souls", p.souls);
         if (p.awakenRetryAt > System.currentTimeMillis()) {
            y.set(b + "awaken-retry-at", p.awakenRetryAt);
         }

         if (p.awakenInterrupts > 0) {
            y.set(b + "awaken-interrupts.rank", p.awakenInterruptRank);
            y.set(b + "awaken-interrupts.count", p.awakenInterrupts);
         }

         for (Entry<String, Integer> e : p.skills.entrySet()) {
            if (e.getValue() > 0) {
               y.set(b + "skills." + e.getKey().replace('.', '_'), e.getValue());
            }
         }

         for (Entry<String, Long> ex : p.sources.entrySet()) {
            y.set(b + "sources." + ex.getKey(), ex.getValue());
         }
      }

      try {
         y.save(this.dataFile);
         this.dirty = false;
      } catch (IOException var7) {
         this.plugin.getLogger().warning("Sauvegarde des profils impossible : " + var7.getMessage());
      }
   }

   public void saveIfDirty() {
      if (this.dirty) {
         this.save();
      }
   }

   public Profile of(OfflinePlayer p) {
      Profile pr = this.profiles.computeIfAbsent(p.getUniqueId(), id -> {
         this.dirty = true;
         return new Profile(id, p.getName() == null ? "?" : p.getName());
      });
      if (p.getName() != null && !p.getName().equals(pr.name)) {
         pr.name = p.getName();
         this.dirty = true;
      }

      return pr;
   }

   public Profile get(UUID id) {
      return this.profiles.get(id);
   }

   public Collection<Profile> all() {
      return this.profiles.values();
   }

   public List<Profile> top() {
      List<Profile> l = new ArrayList<>(this.profiles.values());
      l.sort(Comparator.comparingInt(Profile::level).reversed().thenComparing(Comparator.comparingLong(Profile::totalXp).reversed()));
      return l;
   }

   public Map<String, RaceDef> races() {
      return this.races;
   }

   public RaceDef race(Profile p) {
      return p.race == null ? null : this.races.get(p.race);
   }

   public int maxLevel() {
      return this.maxLevel;
   }

   public int tierEvery() {
      return this.tierEvery;
   }

   public int maxTier() {
      return 4;
   }

   public boolean mustChoose(Player p) {
      // le règlement passe avant le choix de la race (MAJ 1.21.0)
      return this.chooseOnJoin && this.of(p).race == null && !this.races.isEmpty() && (this.plugin.rules() == null || !this.plugin.rules().pending(p));
   }

   public int tierCoins() {
      return this.tierCoins;
   }

   public int tier(Profile p) {
      return Math.max(0, Math.min(this.maxTier(), p.rank - 1));
   }

   public int levelCap(Profile p) {
      return Math.min(this.maxLevel, p.rank * this.tierEvery);
   }

   public boolean atCap(Profile p) {
      return p.level >= this.levelCap(p) && p.rank < 5;
   }

   public String rankName(Profile p) {
      return p.race == null ? "Sans race" : this.tree.rankName(p.race, p.rank);
   }

   public int tierLevel(int t) {
      return t <= 0 ? 1 : t * this.tierEvery;
   }

   public long need(int level) {
      return this.xpBase + (long)this.xpStep * level + (long)this.xpSquare * level * level;
   }

   public int percent(Profile p) {
      return p.level >= this.maxLevel ? 100 : (int)Math.min(100L, p.xp * 100L / Math.max(1L, this.need(p.level)));
   }

   public double stat(Player p, String key) {
      Profile pr = this.profiles.get(p.getUniqueId());
      if (pr == null) {
         return 0.0;
      } else {
         RaceDef r = this.race(pr);
         double s = r == null ? 0.0 : r.stat(key, this.tier(pr), this.maxTier());
         List<ProfileManager.Contrib> cs = this.contribs(pr).get(key);
         if (cs != null) {
            for (ProfileManager.Contrib c : cs) {
               if (c.when() == null || this.condition(p, c.when())) {
                  s += c.v();
               }
            }
         }

         if (this.plugin.powers() != null) {
            s += this.plugin.powers().bonus(p, key);
         }

         return s;
      }
   }

   private double baseStat(Profile pr, String key) {
      RaceDef r = this.race(pr);
      double s = r == null ? 0.0 : r.stat(key, this.tier(pr), this.maxTier());
      List<ProfileManager.Contrib> cs = this.contribs(pr).get(key);
      if (cs != null) {
         for (ProfileManager.Contrib c : cs) {
            if (c.when() == null) {
               s += c.v();
            }
         }
      }

      return s;
   }

   private Map<String, List<ProfileManager.Contrib>> contribs(Profile pr) {
      return this.skillCache.computeIfAbsent(pr.id, k -> {
         Map<String, List<ProfileManager.Contrib>> m = new HashMap<>();

         for (Entry<String, Integer> e : pr.skills.entrySet()) {
            SkillTree.Node n = this.tree.node(e.getKey());
            if (n != null && e.getValue() > 0 && Objects.equals(n.race(), pr.race)) {
               m.computeIfAbsent(n.stat(), x -> new ArrayList<>()).add(new ProfileManager.Contrib(n.value(Math.min(e.getValue(), n.max())), n.when()));
            }
         }

         return m;
      });
   }

   public void skillsChanged(Profile pr) {
      this.skillCache.remove(pr.id);
      this.dirty = true;
      Player p = Bukkit.getPlayer(pr.id);
      if (p != null) {
         this.apply(p);
      }
   }

   public boolean hasSpecial(Player p, String kind) {
      Profile pr = this.profiles.get(p.getUniqueId());
      return pr != null && pr.race != null && pr.skill(pr.race + "." + kind) > 0;
   }

   public void addXp(Player p, long amount, String source) {
      if (amount > 0L) {
         Profile pr = this.of(p);
         amount = Math.round(amount * (1.0 + this.stat(p, "xp-gain")));
         pr.totalXp += amount;
         pr.sources.merge(source, amount, Long::sum);
         this.dirty = true;
         if (pr.level >= this.maxLevel) {
            p.sendActionBar(Text.c("&d+" + amount + " XP &8• &6Niveau max"));
         } else {
            pr.xp += amount;
            int before = pr.level;
            this.climb(pr);
            if (this.atCap(pr) && pr.xp >= this.need(pr.level)) {
               pr.xp = this.need(pr.level);
               p.sendActionBar(Text.c("&5✦ &dÉveil requis &8• &7Niv. &f" + pr.level + " &8(&eXP bloquée&8) &7: &f/eveil"));
            } else {
               p.sendActionBar(Text.c("&d+" + amount + " XP &8• &7Niv. &f" + pr.level + " &8(&e" + this.percent(pr) + "%&8)"));
            }

            if (pr.level > before) {
               this.levelUp(p, pr, before, 0);
            }
         }
      }
   }

   private void climb(Profile pr) {
      for (int cap = this.levelCap(pr); pr.level < cap && pr.xp >= this.need(pr.level); pr.level++) {
         pr.xp = pr.xp - this.need(pr.level);
      }

      if (pr.level >= this.maxLevel) {
         pr.xp = 0L;
      }
   }

   private void levelUp(Player p, Profile pr, int before, int unused) {
      int gained = pr.level - before;
      boolean newTier = false;
      int t = 0;
      long coins = (long)this.levelCoins * gained;
      coins = Math.round(coins * (1.0 + this.stat(p, "coin-gain")));
      if (coins > 0L) {
         this.plugin.menus().actions.giveCurrency(p, coins);
      }

      String cur = this.plugin.settings().currencyName;
      if (newTier) {
         RaceDef r = this.race(pr);
         p.showTitle(
            Title.title(
               Text.c("&6✦ Palier " + Stats.roman(t + 1) + " ✦"),
               Text.c(r == null ? "&7Choisis une race pour profiter des bonus : &f/races" : r.colored() + " &7: tes bonus augmentent !"),
               Times.times(Duration.ofMillis(300L), Duration.ofSeconds(3L), Duration.ofMillis(700L))
            )
         );
         p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0F, 1.0F);
         p.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, p.getLocation().add(0.0, 1.0, 0.0), 60, 0.5, 0.8, 0.5, 0.3);
         Bukkit.broadcast(
            Text.c(this.plugin.settings().prefix + "&f" + p.getName() + " &7atteint le &6palier " + Stats.roman(t + 1) + " &7(niveau &f" + pr.level + "&7) !")
         );
      } else {
         p.showTitle(
            Title.title(
               Text.c("&dNiveau " + pr.level),
               Text.c(coins > 0L ? "&6+" + coins + " " + cur : ""),
               Times.times(Duration.ofMillis(200L), Duration.ofMillis(1500L), Duration.ofMillis(500L))
            )
         );
         p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8F, 1.2F);
      }

      this.plugin
         .msg(
            p,
            "&dNiveau "
               + pr.level
               + " &7atteint"
               + (coins > 0L ? " &8(&6+" + coins + " " + cur + "&8)" : "")
               + " &8• &d+"
               + gained * this.tree.pointsPerLevel
               + " point"
               + (gained * this.tree.pointsPerLevel > 1 ? "s" : "")
               + " de compétence &7(&f/competences&7)"
         );
      if (this.atCap(pr)) {
         this.plugin.msg(p, "&5✦ &dTu as atteint le niveau maximum de ton rang. &7Accomplis ton &dÉveil &7pour continuer : &f/eveil");
      }

      this.apply(p);
   }

   public void setLevel(Player p, int level) {
      Profile pr = this.of(p);
      pr.level = Math.max(1, Math.min(this.levelCap(pr), level));
      pr.xp = 0L;
      this.dirty = true;
      this.apply(p);
   }

   public void setLevel(Profile pr, int level) {
      pr.level = Math.max(1, Math.min(this.levelCap(pr), level));
      pr.xp = 0L;
      this.dirty = true;
   }

   public void setRank(Profile pr, int rank) {
      pr.rank = Math.max(1, Math.min(5, rank));
      pr.awakenInterrupts = 0;
      pr.level = Math.min(pr.level, this.levelCap(pr));
      this.skillsChanged(pr);
   }

   public void awaken(Player p) {
      Profile pr = this.of(p);
      if (pr.rank < 5) {
         pr.rank++;
         pr.awakenRetryAt = 0L;
         pr.awakenInterrupts = 0;
         pr.awakenInterruptRank = 0;
         int before = pr.level;
         this.climb(pr);
         this.skillsChanged(pr);
         if (pr.level > before) {
            this.levelUp(p, pr, before, 0);
         }
      }
   }

   public void setSouls(Profile pr, long souls) {
      pr.souls = Math.max(0L, souls);
      this.dirty = true;
   }

   public void addSouls(Player p, long amount, boolean bonus) {
      if (amount > 0L) {
         Profile pr = this.of(p);
         if (bonus) {
            amount = Math.max(1L, Math.round(amount * (1.0 + this.stat(p, "soul-gain"))));
         }

         pr.souls += amount;
         this.dirty = true;
         this.plugin.quests().progress(p, fr.windydev.clans.quest.QuestManager.Kind.SOULS, "", (int)Math.min(Integer.MAX_VALUE, amount));
      }
   }

   public void failAwakening(Profile pr, long retryAt, double soulLoss) {
      pr.awakenRetryAt = retryAt;
      pr.souls = Math.round(pr.souls * (1.0 - soulLoss));
      this.dirty = true;
   }

   /** Compte une interruption de l'Éveil visant le rang donné et rend le nouveau total. */
   public int addInterrupt(Profile pr, int rank) {
      if (pr.awakenInterruptRank != rank) {
         pr.awakenInterruptRank = rank;
         pr.awakenInterrupts = 0;
      }

      pr.awakenInterrupts++;
      this.dirty = true;
      return pr.awakenInterrupts;
   }

   public void setInterrupts(Profile pr, int rank, int count) {
      pr.awakenInterruptRank = rank;
      pr.awakenInterrupts = Math.max(0, count);
      this.dirty = true;
   }

   public void spendSouls(Profile pr, long n) {
      pr.souls = Math.max(0L, pr.souls - n);
      this.dirty = true;
   }

   public String learn(Player p, String id) {
      Profile pr = this.of(p);
      if (pr.race == null) {
         return "Choisis d'abord une race.";
      } else {
         SkillTree.Node n = this.tree.node(id);
         SkillTree.Special sp = this.tree.special(id);
         if (n == null && sp == null) {
            return "Compétence inconnue.";
         } else if (!(n != null ? n.race() : sp.race()).equals(pr.race)) {
            return "Cette compétence n'est pas de ta race.";
         } else {
            int need = n != null ? n.rank() : sp.rank();
            if (pr.rank < need) {
               return "Il faut être " + this.tree.rankName(pr.race, need) + " (rang " + Stats.roman(need) + ").";
            } else if (sp != null && sp.kind().equals("ultimate") && pr.level < this.maxLevel) {
               return "Il faut être niveau " + this.maxLevel + ".";
            } else if (sp != null && sp.kind().equals("unique-plus") && pr.skill(pr.race + ".unique") <= 0) {
               return "Apprends d'abord la compétence unique.";
            } else {
               int cur = pr.skill(id);
               int max = n != null ? n.max() : 1;
               if (cur >= max) {
                  return "Déjà au maximum.";
               } else {
                  int cost = this.tree.cost(id);
                  if (this.tree.free(pr) < cost) {
                     return "Il te faut " + cost + " point" + (cost > 1 ? "s" : "") + " (tu en as " + this.tree.free(pr) + ").";
                  } else {
                     pr.skills.put(id, cur + 1);
                     this.skillsChanged(pr);
                     return null;
                  }
               }
            }
         }
      }
   }

   public void resetSkills(Profile pr) {
      pr.skills.clear();
      this.skillsChanged(pr);
   }

   public boolean choose(Player p, String raceId) {
      Profile pr = this.of(p);
      RaceDef r = this.races.get(raceId);
      if (r == null) {
         return false;
      } else if (pr.race != null) {
         this.plugin.msg(p, "&7Ta race est " + this.race(pr).colored() + "&7. Le changement de race arrivera bientôt avec un objet spécial.");
         return false;
      } else {
         pr.race = raceId;
         this.dirty = true;
         this.apply(p);
         p.showTitle(
            Title.title(
               Text.c(r.color() + "✦ " + r.name() + " ✦"),
               Text.c("&7Ta race est choisie"),
               Times.times(Duration.ofMillis(300L), Duration.ofSeconds(2L), Duration.ofMillis(600L))
            )
         );
         p.playSound(p.getLocation(), Sound.BLOCK_BEACON_POWER_SELECT, 1.0F, 1.2F);
         p.getWorld().spawnParticle(Particle.WITCH, p.getLocation().add(0.0, 1.0, 0.0), 60, 0.4, 0.8, 0.4, 0.05);
         this.plugin
            .msg(
               p,
               "&7Tu es maintenant "
                  + r.colored()
                  + "&7. Monte de niveau pour gagner des points de compétence (&f/competences&7) et accomplis ton &dÉveil &7tous les "
                  + this.tierEvery
                  + " niveaux pour évoluer (&f/eveil&7)."
            );
         return true;
      }
   }

   public void setRace(Profile pr, String raceId) {
      pr.race = raceId != null && this.races.containsKey(raceId) ? raceId : null;
      pr.skills.clear();
      this.skillCache.remove(pr.id);
      this.dirty = true;
      Player p = Bukkit.getPlayer(pr.id);
      if (p != null) {
         this.apply(p);
      }
   }

   private boolean ours(AttributeModifier m) {
      return m.getKey().getNamespace().equals(this.plugin.getName().toLowerCase(Locale.ROOT)) && m.getKey().getKey().startsWith("race_");
   }

   private void clearModifiers(Player p, String prefix) {
      for (Stats.Meta meta : Stats.META.values()) {
         if (meta.attr() != null) {
            AttributeInstance inst = p.getAttribute(meta.attr());
            if (inst != null) {
               for (AttributeModifier m : new ArrayList<AttributeModifier>(inst.getModifiers())) {
                  if (this.ours(m) && m.getKey().getKey().startsWith(prefix)) {
                     inst.removeModifier(m);
                  }
               }
            }
         }
      }
   }

   private void addModifier(Player p, Stats.Meta meta, String name, double value) {
      if (meta.attr() != null && value != 0.0) {
         AttributeInstance inst = p.getAttribute(meta.attr());
         if (inst != null) {
            inst.addTransientModifier(new AttributeModifier(new NamespacedKey(this.plugin, name), value, meta.op(), EquipmentSlotGroup.ANY));
         }
      }
   }

   public void apply(Player p) {
      this.clearModifiers(p, "race_");
      Profile pr = this.of(p);
      RaceDef r = this.race(pr);
      if (r != null) {
         for (Entry<String, Stats.Meta> e : Stats.META.entrySet()) {
            if (e.getValue().attr() != null) {
               double v = this.baseStat(pr, e.getKey());
               if (v != 0.0) {
                  this.addModifier(p, e.getValue(), "race_" + e.getKey().replace('-', '_'), v);
               }
            }
         }
      }

      this.condModifiers(p, pr);
      if (this.plugin.powers() != null) {
         this.plugin.powers().reapply(p);
      }

      AttributeInstance hp = p.getAttribute(Attribute.MAX_HEALTH);
      if (hp != null && p.getHealth() > hp.getValue()) {
         p.setHealth(hp.getValue());
      }

      if ((r == null || this.stat(p, "double-jump") <= 0.0) && (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE)) {
         p.setAllowFlight(false);
         p.setFlying(false);
      }
   }

   public static boolean isNight(Player p) {
      World w = p.getWorld();
      if (w.getEnvironment() != Environment.NORMAL) {
         return false;
      } else {
         long t = w.getTime();
         return t >= 13000L && t <= 23000L;
      }
   }

   private static boolean underground(Player p) {
      Block b = p.getEyeLocation().getBlock();
      return b.getLightFromSky() == 0 && p.getWorld().getEnvironment() == Environment.NORMAL;
   }

   private static boolean underSky(Player p) {
      return p.getEyeLocation().getBlock().getLightFromSky() >= 15;
   }

   private static boolean inRain(Player p) {
      World w = p.getWorld();
      if (w.hasStorm() && w.getEnvironment() == Environment.NORMAL && underSky(p)) {
         double temp = p.getLocation().getBlock().getTemperature();
         return temp >= 0.15 && temp <= 0.95;
      } else {
         return false;
      }
   }

   public boolean night(Player p) {
      return isNight(p) || this.plugin.powers() != null && this.plugin.powers().active(p, "vampire");
   }

   public boolean wet(Player p) {
      return p.isInWater() || this.plugin.powers() != null && this.plugin.powers().active(p, "ondin");
   }

   public boolean rain(Player p) {
      return inRain(p) || this.plugin.powers() != null && this.plugin.powers().raining(p);
   }

   private static boolean forest(Player p) {
      String b = p.getLocation().getBlock().getBiome().getKey().getKey();
      return b.contains("forest") || b.contains("taiga") || b.contains("jungle") || b.contains("grove") || b.equals("dark_forest");
   }

   boolean condition(Player p, String when) {
      return switch (when) {
         case "underground" -> underground(p);
         case "night" -> this.night(p);
         case "forest" -> forest(p);
         case "altitude" -> p.getLocation().getY() >= 120.0;
         case "low-health" -> {
            AttributeInstance hp = p.getAttribute(Attribute.MAX_HEALTH);
            yield hp != null && p.getHealth() <= hp.getValue() * 0.3;
         }
         case "axe" -> p.getInventory().getItemInMainHand().getType().name().endsWith("_AXE");
         case "day-sun" -> {
            World w = p.getWorld();
            long t = w.getTime();
            boolean day = w.getEnvironment() == Environment.NORMAL && (t < 12300L || t > 23850L) && !w.hasStorm();
            yield day && underSky(p) && (p.getInventory().getHelmet() == null || p.getInventory().getHelmet().getType().isAir());
         }
         case "nether" -> p.getWorld().getEnvironment() == Environment.NETHER;
         case "water" -> this.wet(p);
         case "water-or-rain" -> this.wet(p) || this.rain(p);
         default -> true;
      };
   }

   private void condModifiers(Player p, Profile pr) {
      this.clearModifiers(p, "race_cond_");
      Map<String, List<ProfileManager.Contrib>> m = this.contribs(pr);

      for (Entry<String, List<ProfileManager.Contrib>> e : m.entrySet()) {
         Stats.Meta meta = Stats.META.get(e.getKey());
         if (meta != null && meta.attr() != null) {
            double v = 0.0;

            for (ProfileManager.Contrib c : e.getValue()) {
               if (c.when() != null && this.condition(p, c.when())) {
                  v += c.v();
               }
            }

            if (v != 0.0) {
               this.addModifier(p, meta, "race_cond_" + e.getKey().replace('-', '_'), v);
            }
         }
      }
   }

   public void start() {
      Bukkit.getScheduler().runTaskTimer(this.plugin, () -> {
         for (Player p : Bukkit.getOnlinePlayers()) {
            this.tickEffects(p);
         }
      }, 40L, 40L);
      Bukkit.getScheduler().runTaskTimer(this.plugin, () -> {
         for (Player p : Bukkit.getOnlinePlayers()) {
            Location now = p.getLocation();
            Location prev = this.lastPos.put(p.getUniqueId(), now);
            if (prev != null && prev.getWorld() == now.getWorld() && !(prev.distanceSquared(now) < 4.0)) {
               Profile pr = this.of(p);
               pr.playMinutes++;
               this.dirty = true;
               this.plugin.quests().progress(p, fr.windydev.clans.quest.QuestManager.Kind.PLAY, "", 1);
               int m = this.activeMinutes.merge(p.getUniqueId(), 1, Integer::sum);
               long day = System.currentTimeMillis() / 86400000L;
               if (day != this.playDay) {
                  this.playDay = day;
                  this.playToday.clear();
               }

               if (m >= this.playMinutes) {
                  this.activeMinutes.put(p.getUniqueId(), 0);
                  int done = this.playToday.getOrDefault(p.getUniqueId(), 0);
                  if (done < this.playDailyCap) {
                     int give = Math.min(this.playXp, this.playDailyCap - done);
                     this.playToday.put(p.getUniqueId(), done + give);
                     this.addXp(p, give, "temps");
                  }
               }
            }
         }
      }, 1200L, 1200L);
      Bukkit.getScheduler().runTaskTimer(this.plugin, this::saveIfDirty, 1200L, 1200L);
   }

   private void tickEffects(Player p) {
      Profile pr = this.profiles.get(p.getUniqueId());
      RaceDef r = pr == null ? null : this.race(pr);
      if (r != null && !p.isDead()) {
         int t = this.tier(pr);
         Set<PotionEffectType> wanted = new HashSet<>();
         Map<PotionEffectType, Integer> amp = new HashMap<>();
         double resist = Math.min(1.0, this.stat(p, "malus-resist"));
         boolean nobleBlood = pr.race.equals("vampire") && this.hasSpecial(p, "extra");

         for (List<RaceDef.Effect> list : List.of(r.effects(), r.malusEffects())) {
            for (RaceDef.Effect e : list) {
               if (t >= e.fromTier()
                  && this.condition(p, e.when())
                  && (list != r.malusEffects() || !(resist >= 1.0) && (!nobleBlood || !e.when().equals("day-sun")))) {
                  wanted.add(e.type());
                  amp.merge(e.type(), e.amplifier(), Math::max);
               }
            }
         }

         Set<PotionEffectType> all = new HashSet<>();
         r.effects().forEach(ex -> all.add(ex.type()));
         r.malusEffects().forEach(ex -> all.add(ex.type()));

         for (PotionEffectType type : all) {
            PotionEffect cur = p.getPotionEffect(type);
            if (wanted.contains(type)) {
               int a = amp.get(type);
               if (cur == null || cur.isAmbient() && (cur.getAmplifier() != a || cur.getDuration() < 300) || cur.getAmplifier() < a) {
                  p.addPotionEffect(new PotionEffect(type, 400, a, true, false, true));
               }
            } else if (cur != null && cur.isAmbient() && cur.getDuration() <= 400) {
               p.removePotionEffect(type);
            }
         }

         double ns = this.stat(p, "night-speed");
         this.clearModifiers(p, "race_night_");
         if (ns != 0.0 && this.night(p)) {
            this.addModifier(p, Stats.META.get("speed"), "race_night_speed", ns);
         }

         this.condModifiers(p, pr);
         RaceDef.Hurt h = r.hurt();
         if (h != null && this.condition(p, h.when()) && (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE)) {
            int c = this.hurtCounter.merge(p.getUniqueId(), 2, Integer::sum);
            if (c >= h.every()) {
               this.hurtCounter.put(p.getUniqueId(), 0);
               if (resist < 1.0) {
                  p.damage(h.damage() * (1.0 - Math.max(0.0, resist)));
               }

               p.getWorld().spawnParticle(Particle.SMOKE, p.getLocation().add(0.0, 1.0, 0.0), 8, 0.3, 0.5, 0.3, 0.01);
            }
         } else {
            this.hurtCounter.remove(p.getUniqueId());
         }
      }
   }

   public void join(Player p) {
      Profile pr = this.of(p);
      this.lastPos.put(p.getUniqueId(), p.getLocation());
      this.apply(p);
      if (this.mustChoose(p)) {
         this.openChoiceWhenReady(p, 0);
      }
   }

   private void openChoiceWhenReady(Player p, int tries) {
      Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
         if (p.isOnline() && this.mustChoose(p)) {
            if (!this.plugin.pack().settled(p) && tries < 20 || this.plugin.rules().pending(p) && tries < 400) {
               // le règlement doit d'abord être accepté (art. 2.2)
               this.openChoiceWhenReady(p, tries + 1);
            } else {
               this.plugin.msg(p, "&dBienvenue ! &7Choisis ta race : chacune donne des bonus qui grandissent à chaque Éveil.");
               this.plugin.menus().races(p);
            }
         }
      }, tries == 0 ? 60L : 40L);
   }

   public void quit(Player p) {
      this.lastPos.remove(p.getUniqueId());
      this.activeMinutes.remove(p.getUniqueId());
      this.hurtCounter.remove(p.getUniqueId());
      this.jumpReady.remove(p.getUniqueId());
      this.airJumps.remove(p.getUniqueId());
      this.farm.remove(p.getUniqueId());
      this.skillCache.remove(p.getUniqueId());
   }

   private boolean survival(Player p) {
      return p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE;
   }

   private double farmFactor(Player p, String source) {
      long now = System.currentTimeMillis();
      ArrayDeque<Long> q = this.farm.computeIfAbsent(p.getUniqueId(), k -> new HashMap<>()).computeIfAbsent(source, k -> new ArrayDeque<>());

      while (!q.isEmpty() && now - q.peekFirst() > 600000L) {
         q.pollFirst();
      }

      q.addLast(now);
      if (q.size() > 400) {
         q.pollFirst();
      }

      int n = q.size();
      return n <= this.antiFree ? 1.0 : Math.max(this.antiMin, (double)this.antiFree / n);
   }

   @EventHandler(
      priority = EventPriority.MONITOR
   )
   public void onKill(EntityDeathEvent e) {
      LivingEntity dead = e.getEntity();
      Player killer = dead.getKiller();
      if (killer != null && killer != dead) {
         if (dead instanceof Player victim) {
            Clan a = this.plugin.clans().of(killer);
            Clan b = this.plugin.clans().of(victim);
            if (a != null && b != null && a != b && this.plugin.war().atWar(a, b)) {
               this.addXp(killer, Math.round(this.warKill * (1.0 + this.stat(killer, "pvp-bonus"))), "combat");
            }

            Profile vp = this.of(victim);
            long stolen = Math.round(vp.souls * this.soulSteal);
            vp.souls -= stolen;
            long bonus = 0L;
            String k = killer.getUniqueId() + ">" + victim.getUniqueId();
            long now = System.currentTimeMillis();
            if ((a == null || b == null || a != b && !a.allies().contains(b.id())) && now - this.recentKills.getOrDefault(k, 0L) > this.soulPlayerCooldown) {
               this.recentKills.put(k, now);
               bonus = Math.round(this.soulPlayer * (1.0 + this.stat(killer, "pvp-bonus")));
            }

            this.addSouls(killer, stolen, false);
            this.addSouls(killer, bonus, true);
            this.dirty = true;
            if (stolen + bonus > 0L) {
               killer.sendActionBar(Text.c("&5☠ &d+" + (stolen + bonus) + " âmes &8(&7" + victim.getName() + "&8)"));
            }

            if (stolen > 0L) {
               this.plugin.msg(victim, "&5☠ &7" + killer.getName() + " t'a pris &d" + stolen + " âmes&7.");
            }
         } else if (!dead.getPersistentDataContainer()
            .getKeys()
            .stream()
            .anyMatch(kx -> kx.getNamespace().equals(this.plugin.getName().toLowerCase(Locale.ROOT)))) {
            String key = dead.getType().getKey().getKey();
            Integer xp = this.mobXp.get(key);
            if (xp == null) {
               if (!(dead instanceof Enemy)) {
                  return;
               }

               xp = this.mobDefault;
            }

            double f = dead.getEntitySpawnReason() == SpawnReason.SPAWNER ? this.spawnerFactor : 1.0;
            if (f >= 1.0 && dead instanceof Enemy) {
               this.addSouls(killer, this.soulMob, true);
            }

            long amount = Math.round(xp.intValue() * f * this.farmFactor(killer, "mob:" + key));
            if (amount > 0L) {
               this.of(killer).mobKills++;
               this.addXp(killer, amount, "combat");
            }
         }
      }
   }

   @EventHandler(
      priority = EventPriority.MONITOR,
      ignoreCancelled = true
   )
   public void onPlace(BlockPlaceEvent e) {
      Material m = e.getBlockPlaced().getType();
      if (this.blockXp.containsKey(m) || Tag.LOGS.isTagged(m) || m == Material.MELON || m == Material.PUMPKIN) {
         this.mark(e.getBlockPlaced(), true);
      }
   }

   @EventHandler(
      priority = EventPriority.MONITOR,
      ignoreCancelled = true
   )
   public void onBreak(BlockBreakEvent e) {
      Player p = e.getPlayer();
      Block b = e.getBlock();
      Material m = b.getType();
      boolean placed = this.mark(b, false);
      if (this.survival(p)) {
         long xp = 0L;
         Integer ore = this.blockXp.get(m);
         if (ore != null) {
            if (placed
               || !p.getInventory().getItemInMainHand().getEnchantments().isEmpty()
                  && p.getInventory().getItemInMainHand().containsEnchantment(Enchantment.SILK_TOUCH)) {
               return;
            }

            xp = ore.intValue();
         } else if (Tag.LOGS.isTagged(m)) {
            if (placed) {
               return;
            }

            xp = this.logsXp;
         } else if (b.getBlockData() instanceof Ageable ag && isCrop(m)) {
            if (ag.getAge() < ag.getMaximumAge()) {
               return;
            }

            xp = this.cropsXp;
         } else if ((m == Material.MELON || m == Material.PUMPKIN) && !placed) {
            xp = this.cropsXp;
         }

         if (xp > 0L) {
            this.plugin
               .quests()
               .progress(
                  p,
                  ore != null
                     ? fr.windydev.clans.quest.QuestManager.Kind.MINE
                     : (Tag.LOGS.isTagged(m) ? fr.windydev.clans.quest.QuestManager.Kind.CHOP : fr.windydev.clans.quest.QuestManager.Kind.HARVEST),
                  m.name(),
                  1
               );
            if (ore != null) {
               xp = Math.round(xp * (1.0 + this.stat(p, "mining-xp")));
            }

            double ff = this.farmFactor(p, "block:" + m.name());
            long got = Math.round(xp * ff);
            if (got <= 0L && Math.random() < xp * ff) {
               got = 1L;
            }

            if (got > 0L) {
               this.addXp(p, got, "metiers");
            }
         }
      }
   }

   private static boolean isCrop(Material m) {
      return switch (m) {
         case WHEAT, CARROTS, POTATOES, BEETROOTS, NETHER_WART, COCOA, TORCHFLOWER_CROP, PITCHER_CROP -> true;
         default -> false;
      };
   }

   @EventHandler(
      priority = EventPriority.MONITOR,
      ignoreCancelled = true
   )
   public void onFish(PlayerFishEvent e) {
      if (e.getState() == State.CAUGHT_FISH) {
         this.addXp(
            e.getPlayer(), Math.round(this.fishingXp * (1.0 + this.stat(e.getPlayer(), "fishing-xp")) * this.farmFactor(e.getPlayer(), "fish")), "metiers"
         );
      }
   }

   public void ritualDone(Player caster, int tier) {
      if (caster != null) {
         this.of(caster).rituals++;
         this.addXp(caster, (long)this.ritualPerTier * Math.max(1, tier), "rituels");
         this.plugin.quests().progress(caster, fr.windydev.clans.quest.QuestManager.Kind.RITUAL, "", 1);
      }
   }

   public void bossWon(Collection<UUID> participants, int bossLevel) {
      for (UUID u : participants) {
         Player p = Bukkit.getPlayer(u);
         if (p != null) {
            this.of(p).bossWins++;
            this.addXp(p, (long)this.bossPerLevel * bossLevel, "combat");
            this.addSouls(p, this.soulBoss, true);
            this.plugin.msg(p, "&5☠ &d+" + Math.round(this.soulBoss * (1.0 + this.stat(p, "soul-gain"))) + " âmes &7pour la victoire.");
            this.plugin.quests().progress(p, fr.windydev.clans.quest.QuestManager.Kind.BOSS, String.valueOf(bossLevel), 1);
         }
      }
   }

   private boolean mark(Block b, boolean set) {
      PersistentDataContainer pdc = b.getChunk().getPersistentDataContainer();
      long code = b.getX() & 15 | (long)(b.getZ() & 15) << 4 | (long)(b.getY() + 4096) << 8;
      long[] arr = (long[])pdc.getOrDefault(this.placedKey, PersistentDataType.LONG_ARRAY, new long[0]);
      int idx = -1;

      for (int i = 0; i < arr.length; i++) {
         if (arr[i] == code) {
            idx = i;
            break;
         }
      }

      if (set) {
         if (idx >= 0) {
            return true;
         } else {
            long[] n;
            if (arr.length >= 4096) {
               n = Arrays.copyOfRange(arr, 1, arr.length + 1);
               n[n.length - 1] = code;
            } else {
               n = Arrays.copyOf(arr, arr.length + 1);
               n[arr.length] = code;
            }

            pdc.set(this.placedKey, PersistentDataType.LONG_ARRAY, n);
            return true;
         }
      } else if (idx < 0) {
         return false;
      } else {
         long[] n = new long[arr.length - 1];
         System.arraycopy(arr, 0, n, 0, idx);
         System.arraycopy(arr, idx + 1, n, idx, arr.length - idx - 1);
         if (n.length == 0) {
            pdc.remove(this.placedKey);
         } else {
            pdc.set(this.placedKey, PersistentDataType.LONG_ARRAY, n);
         }

         return true;
      }
   }

   public boolean isPlaced(Block b) {
      long code = b.getX() & 15 | (long)(b.getZ() & 15) << 4 | (long)(b.getY() + 4096) << 8;

      for (long v : (long[])b.getChunk().getPersistentDataContainer().getOrDefault(this.placedKey, PersistentDataType.LONG_ARRAY, new long[0])) {
         if (v == code) {
            return true;
         }
      }

      return false;
   }

   public void markDirty() {
      this.dirty = true;
   }

   record Contrib(double v, String when) {
   }
}
