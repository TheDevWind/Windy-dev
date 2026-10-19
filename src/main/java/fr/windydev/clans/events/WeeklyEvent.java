package fr.windydev.clans.events;

import fr.windydev.clans.WindyClans;
import fr.windydev.clans.listener.ProtectionListener;
import fr.windydev.clans.model.ChunkPos;
import fr.windydev.clans.quest.QuestManager;
import fr.windydev.clans.quest.Reward;
import fr.windydev.clans.util.ItemBuilder;
import fr.windydev.clans.util.Text;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.bossbar.BossBar.Color;
import net.kyori.adventure.bossbar.BossBar.Overlay;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.TileState;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

/** Évènement de la semaine : boss mondial, chasse au trésor ou Faille géante (MAJ 1.21.0). */
public final class WeeklyEvent implements Listener, CommandExecutor, TabCompleter {
   public enum Type {
      BOSS("boss", "Boss mondial", Material.WITHER_SKELETON_SKULL),
      TRESOR("tresor", "Chasse au trésor", Material.CHEST),
      FAILLE("faille", "Faille géante", Material.END_PORTAL_FRAME);

      final String id;
      final String label;
      final Material icon;

      Type(String id, String label, Material icon) {
         this.id = id;
         this.label = label;
         this.icon = icon;
      }

      static Type of(String s) {
         for (Type t : values()) {
            if (t.id.equalsIgnoreCase(s) || t.name().equalsIgnoreCase(s)) {
               return t;
            }
         }

         return null;
      }
   }

   /** Tag posé sur le colosse et ses sbires (reconnu par la zone sûre du spawn). */
   public static final String TAG = "windy_event";
   private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEEE d MMMM 'à' HH'h'mm", Locale.FRENCH);
   private final WindyClans plugin;
   private final NamespacedKey chestKey;
   private final Random rnd = new Random();
   private File file;
   private File dataFile;
   private YamlConfiguration data = new YamlConfiguration();
   // configuration
   private boolean enabled = true;
   private ZoneId zone = ZoneId.of("Europe/Paris");
   private DayOfWeek day = DayOfWeek.SATURDAY;
   private LocalTime time = LocalTime.of(20, 30);
   private final List<Type> rotation = new ArrayList<>();
   private int minPlayers = 2;
   private int waitMinutes = 90;
   private final List<Integer> announce = new ArrayList<>();
   private String bossName = "&4&lColosse de la Tempête";
   private EntityType bossType = EntityType.WITHER_SKELETON;
   private double bossScale = 2.6;
   private double bossHealth = 900.0;
   private double bossHealthPerPlayer = 350.0;
   private double bossDamage = 7.0;
   private int bossMinutes = 20;
   private String bossLocation = "";
   private double minDamage = 80.0;
   private final List<EntityType> minionTypes = new ArrayList<>();
   private Reward bossReward = Reward.NONE;
   private Reward bossBest = Reward.NONE;
   private int chestCount = 5;
   private int minDist = 250;
   private int maxDist = 1500;
   private int chestMinutes = 45;
   private final Map<Integer, Integer> clues = new HashMap<>();
   private final List<String> loot = new ArrayList<>();
   private double rDuration = 1.5;
   private double rKills = 1.5;
   private double rHp = 1.4;
   private double rBossHp = 2.0;
   private int rRewards = 2;
   // état
   private Type running;
   private long startedAt;
   private long endsAt;
   private boolean waitingTold;
   private long riftWaitUntil;
   private final Set<UUID> participants = new LinkedHashSet<>();
   // boss
   private UUID boss;
   private double bossMax;
   private double bossHp;
   private Location bossHome;
   private BossBar bar;
   private final Set<UUID> barViewers = new HashSet<>();
   private final Map<UUID, Double> damage = new HashMap<>();
   private final Set<UUID> minions = new HashSet<>();
   private long nextAbility;
   // trésor
   private final List<Treasure> chests = new ArrayList<>();
   private int clueIndex;
   private boolean placing;

   static final class Treasure {
      final int n;
      final Location loc;
      String finder;
      final Map<Integer, int[]> hint = new HashMap<>();

      Treasure(int n, Location loc) {
         this.n = n;
         this.loc = loc;
      }
   }

   public WeeklyEvent(WindyClans plugin) {
      this.plugin = plugin;
      this.chestKey = new NamespacedKey(plugin, "event_treasure");
   }

   // ------------------------------------------------------------------ configuration

   public void load() {
      this.file = new File(this.plugin.getDataFolder(), "evenements.yml");
      if (!this.file.exists()) {
         this.plugin.saveResource("evenements.yml", false);
      }

      YamlConfiguration y = YamlConfiguration.loadConfiguration(this.file);

      try (InputStream in = this.plugin.getResource("evenements.yml")) {
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

      this.day = parseDay(y.getString("settings.day", "samedi"));

      try {
         this.time = LocalTime.parse(y.getString("settings.time", "20:30").trim());
      } catch (Exception var10) {
         this.time = LocalTime.of(20, 30);
         this.plugin.getLogger().warning("evenements.yml : heure invalide, 20:30 utilisée.");
      }

      this.rotation.clear();

      for (String s : y.getStringList("settings.rotation")) {
         Type t = Type.of(s.trim());
         if (t != null) {
            this.rotation.add(t);
         }
      }

      if (this.rotation.isEmpty()) {
         this.rotation.addAll(List.of(Type.BOSS, Type.TRESOR, Type.FAILLE));
      }

      this.minPlayers = Math.max(1, y.getInt("settings.min-players", 2));
      this.waitMinutes = Math.max(0, y.getInt("settings.wait-minutes", 90));
      this.announce.clear();
      this.announce.addAll(y.getIntegerList("settings.announce"));
      this.bossName = y.getString("boss.name", this.bossName);

      try {
         this.bossType = EntityType.valueOf(y.getString("boss.type", "WITHER_SKELETON").toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException var9) {
         this.bossType = EntityType.WITHER_SKELETON;
      }

      this.bossScale = y.getDouble("boss.scale", 2.6);
      this.bossHealth = Math.max(20.0, y.getDouble("boss.health", 900.0));
      this.bossHealthPerPlayer = Math.max(0.0, y.getDouble("boss.health-per-player", 350.0));
      this.bossDamage = Math.max(1.0, y.getDouble("boss.damage", 7.0));
      this.bossMinutes = Math.max(2, y.getInt("boss.minutes", 20));
      this.bossLocation = y.getString("boss.location", "");
      this.minDamage = Math.max(0.0, y.getDouble("boss.min-damage", 80.0));
      this.minionTypes.clear();

      for (String s : y.getStringList("boss.minions")) {
         try {
            this.minionTypes.add(EntityType.valueOf(s.toUpperCase(Locale.ROOT)));
         } catch (IllegalArgumentException var8) {
         }
      }

      if (this.minionTypes.isEmpty()) {
         this.minionTypes.add(EntityType.ZOMBIE);
      }

      this.bossReward = Reward.of(y.getConfigurationSection("boss.reward"));
      this.bossBest = Reward.of(y.getConfigurationSection("boss.best"));
      this.chestCount = Math.max(1, Math.min(20, y.getInt("tresor.chests", 5)));
      this.minDist = Math.max(0, y.getInt("tresor.min-distance", 250));
      this.maxDist = Math.max(this.minDist + 50, y.getInt("tresor.max-distance", 1500));
      this.chestMinutes = Math.max(5, y.getInt("tresor.minutes", 45));
      this.clues.clear();
      ConfigurationSection cs = y.getConfigurationSection("tresor.clues");
      if (cs != null) {
         for (String k : cs.getKeys(false)) {
            try {
               this.clues.put(Integer.parseInt(k), Math.max(5, cs.getInt(k)));
            } catch (NumberFormatException var7) {
            }
         }
      }

      if (this.clues.isEmpty()) {
         this.clues.put(0, 150);
      }

      this.loot.clear();
      this.loot.addAll(y.getStringList("tresor.loot"));
      this.rDuration = y.getDouble("faille.duration", 1.5);
      this.rKills = y.getDouble("faille.kills", 1.5);
      this.rHp = y.getDouble("faille.monster-health", 1.4);
      this.rBossHp = y.getDouble("faille.boss-health", 2.0);
      this.rRewards = Math.max(1, y.getInt("faille.rewards", 2));
      this.dataFile = new File(this.plugin.getDataFolder(), "evenements-data.yml");
      this.data = YamlConfiguration.loadConfiguration(this.dataFile);
   }

   static DayOfWeek parseDay(String s) {
      String d = s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
      return switch (d) {
         case "lundi", "monday" -> DayOfWeek.MONDAY;
         case "mardi", "tuesday" -> DayOfWeek.TUESDAY;
         case "mercredi", "wednesday" -> DayOfWeek.WEDNESDAY;
         case "jeudi", "thursday" -> DayOfWeek.THURSDAY;
         case "vendredi", "friday" -> DayOfWeek.FRIDAY;
         case "dimanche", "sunday" -> DayOfWeek.SUNDAY;
         default -> DayOfWeek.SATURDAY;
      };
   }

   private void saveData() {
      try {
         this.data.save(this.dataFile);
      } catch (Exception var2) {
         this.plugin.getLogger().warning("evenements-data.yml : " + var2.getMessage());
      }
   }

   public void start() {
      Bukkit.getScheduler().runTaskLater(this.plugin, this::cleanupChests, 100L);
      Bukkit.getScheduler().runTaskTimer(this.plugin, this::schedule, 200L, 200L);
      Bukkit.getScheduler().runTaskTimer(this.plugin, this::tick, 40L, 20L);
   }

   public void shutdown() {
      if (this.running == Type.BOSS) {
         this.endBoss(false, "le serveur redémarre");
      } else if (this.running == Type.TRESOR) {
         this.endTreasure("le serveur redémarre");
      }
   }

   // ------------------------------------------------------------------ calendrier

   private long weekOf(LocalDate d) {
      return d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toEpochDay();
   }

   private ZonedDateTime occurrence(long monday) {
      return LocalDate.ofEpochDay(monday).plusDays(this.day.getValue() - 1).atTime(this.time).atZone(this.zone);
   }

   private Type typeOf(long monday) {
      return this.rotation.get((int)Math.floorMod(monday / 7L, (long)this.rotation.size()));
   }

   /** Prochain évènement (celui de cette semaine s'il n'a pas encore eu lieu). */
   private ZonedDateTime next() {
      ZonedDateTime now = ZonedDateTime.now(this.zone);
      long w = this.weekOf(now.toLocalDate());
      ZonedDateTime at = this.occurrence(w);
      return this.data.getLong("last-week", -1L) != w && now.isBefore(at.plusMinutes(this.waitMinutes)) ? at : this.occurrence(w + 7L);
   }

   private Type nextType() {
      return this.typeOf(this.weekOf(this.next().toLocalDate()));
   }

   private String describe(Type t) {
      return switch (t) {
         case BOSS -> "le " + Text.strip(this.bossName) + " apparaît : tout le monde tape, chacun est récompensé";
         case TRESOR -> this.chestCount + " coffres sont cachés dans la nature, des indices tombent dans le chat";
         case FAILLE -> "la Faille arcanique s'ouvre en grand, récompenses x" + this.rRewards;
      };
   }

   private void schedule() {
      if (this.enabled && this.running == null) {
         ZonedDateTime now = ZonedDateTime.now(this.zone);
         long w = this.weekOf(now.toLocalDate());
         if (this.data.getLong("last-week", -1L) != w) {
            ZonedDateTime at = this.occurrence(w);
            Type t = this.typeOf(w);
            if (this.data.getLong("announced-week", -1L) != w) {
               this.data.set("announced-week", w);
               this.data.set("announced", new ArrayList<>());
            }

            List<Integer> done = new ArrayList<>(this.data.getIntegerList("announced"));
            if (now.isBefore(at)) {
               long minutesLeft = Duration.between(now, at).toMinutes();

               for (int a : this.announce) {
                  if (!done.contains(a) && minutesLeft < a) {
                     done.add(a);
                     if (minutesLeft >= a - 30L || a <= 10) {
                        this.announceSoon(t, at, a);
                     }
                  }
               }

               this.data.set("announced", done);
               this.saveData();
            } else if (now.isAfter(at.plusMinutes(this.waitMinutes))) {
               this.data.set("last-week", w);
               this.saveData();
               if (!done.isEmpty()) {
                  Bukkit.broadcast(Text.c(this.plugin.settings().prefix + "&7L'évènement de la semaine (&f" + t.label + "&7) est annulé : pas assez de joueurs connectés."));
                  this.plugin.discord().send("event", "😔 L'évènement de la semaine (**" + t.label + "**) est annulé : pas assez de joueurs connectés.");
               }
            } else if (Bukkit.getOnlinePlayers().size() >= this.minPlayers) {
               this.data.set("last-week", w);
               this.saveData();
               this.begin(t);
            } else if (!this.waitingTold) {
               this.waitingTold = true;
               Bukkit.broadcast(
                  Text.c(
                     this.plugin.settings().prefix
                        + "&7L'évènement de la semaine (&f"
                        + t.label
                        + "&7) attend &f"
                        + this.minPlayers
                        + " joueurs connectés &7pour commencer."
                  )
               );
            }
         }
      }
   }

   private void announceSoon(Type t, ZonedDateTime at, int minutes) {
      String when = minutes >= 1440 ? "Demain à " + at.toLocalTime() : (minutes > 60 ? "Dans " + minutes / 60 + " h" : (minutes >= 60 ? "Dans 1 h" : "Dans " + minutes + " min"));
      String where = t == Type.BOSS ? (this.bossLocation.isBlank() ? " au spawn" : "") : (t == Type.FAILLE ? " (`/faille`)" : "");
      this.plugin.discord().send("event", "📅 **" + when + " : " + t.label + "** — " + this.describe(t) + where + ". Venez nombreux !");
      if (minutes < 1440) {
         Bukkit.broadcast(Text.c(this.plugin.settings().prefix + "&d✦ &f" + when.toLowerCase(Locale.ROOT) + " &7: &d" + t.label + " &7— " + this.describe(t) + where.replace("`", "") + "."));

         for (Player p : Bukkit.getOnlinePlayers()) {
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.7F, 1.2F);
         }
      }
   }

   // ------------------------------------------------------------------ départ et fin

   /** Lance un évènement tout de suite. */
   public boolean begin(Type t) {
      if (this.running != null) {
         return false;
      } else {
         this.waitingTold = false;
         this.participants.clear();
         this.startedAt = System.currentTimeMillis();
         switch (t) {
            case BOSS:
               return this.startBoss();
            case TRESOR:
               return this.startTreasure();
            default:
               this.running = Type.FAILLE;
               this.riftWaitUntil = System.currentTimeMillis() + 1_800_000L;
               if (!this.plugin.rift().openGiant(this.rDuration, this.rKills, this.rHp, this.rBossHp, this.rRewards)) {
                  Bukkit.broadcast(Text.c(this.plugin.settings().prefix + "&7La Faille géante s'ouvrira dès que la faille en cours sera refermée."));
               }

               return true;
         }
      }
   }

   /** Appelé par la Faille quand la Faille géante se referme. */
   public void ended(String what) {
      if (this.running == Type.FAILLE && what.equals("faille")) {
         this.running = null;
         this.plugin.getLogger().info("Évènement de la semaine terminé (Faille géante, " + this.participants.size() + " participant(s)).");
      }
   }

   /** Un joueur a participé à l'évènement (quête de la semaine). */
   public void participated(Player p) {
      if (this.participants.add(p.getUniqueId())) {
         this.plugin.quests().progress(p, QuestManager.Kind.EVENT, this.running == null ? "" : this.running.id, 1);
      }
   }

   public boolean isRunning() {
      return this.running != null;
   }

   private void tick() {
      if (this.running == Type.BOSS) {
         this.tickBoss();
      } else if (this.running == Type.TRESOR) {
         this.tickTreasure();
      } else if (this.running == Type.FAILLE && !this.plugin.rift().giant()) {
         if (System.currentTimeMillis() > this.riftWaitUntil) {
            this.running = null;
         } else if (!this.plugin.rift().running()) {
            this.plugin.rift().openGiant(this.rDuration, this.rKills, this.rHp, this.rBossHp, this.rRewards);
         }
      }
   }

   // ------------------------------------------------------------------ boss mondial

   private Location bossSpot() {
      if (this.bossLocation != null && !this.bossLocation.isBlank()) {
         String[] p = this.bossLocation.split(",");

         try {
            World w = Bukkit.getWorld(p[0].trim());
            if (w != null) {
               return new Location(w, Double.parseDouble(p[1].trim()), Double.parseDouble(p[2].trim()), Double.parseDouble(p[3].trim()));
            }
         } catch (Exception var3) {
            this.plugin.getLogger().warning("evenements.yml : boss.location invalide (" + this.bossLocation + ").");
         }
      }

      Location l = Bukkit.getWorlds().get(0).getSpawnLocation().clone();
      return l.add(0.5, 0.0, 0.5);
   }

   private boolean startBoss() {
      Location at = this.bossSpot();
      at.getChunk().addPluginChunkTicket(this.plugin);
      this.bossHome = at.clone();
      int players = Math.max(1, Bukkit.getOnlinePlayers().size());
      this.bossMax = this.bossHealth + this.bossHealthPerPlayer * (players - 1);
      this.bossHp = this.bossMax;
      this.damage.clear();
      this.minions.clear();
      if (!this.spawnBoss()) {
         at.getChunk().removePluginChunkTicket(this.plugin);
         return false;
      } else {
         this.running = Type.BOSS;
         this.endsAt = System.currentTimeMillis() + this.bossMinutes * 60_000L;
         this.nextAbility = System.currentTimeMillis() + 8000L;
         this.bar = BossBar.bossBar(Text.c(this.bossName), 1.0F, Color.RED, Overlay.NOTCHED_20);
         at.getWorld().strikeLightningEffect(at);
         String pos = "X " + at.getBlockX() + " Y " + at.getBlockY() + " Z " + at.getBlockZ() + " (" + at.getWorld().getName() + ")";
         Component go = Text.c("&c&l[Y ALLER]").clickEvent(ClickEvent.runCommand("/spawn")).hoverEvent(HoverEvent.showText(Text.c("&7/spawn")));
         Component msg = Text.c(
               this.plugin.settings().prefix
                  + "&4✦ &c&lBoss mondial ! &f"
                  + this.bossName
                  + " &7est apparu en &f"
                  + pos
                  + "&7. Tout le monde tape : chaque joueur qui fait au moins &f"
                  + (int)this.minDamage
                  + " dégâts &7est récompensé. "
            )
            .append(this.bossLocation.isBlank() ? go : Component.empty());

         for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendMessage(msg);
            p.showTitle(Title.title(Text.c("&4✦ Boss mondial"), Text.c(this.bossName)));
            p.playSound(p.getLocation(), Sound.ENTITY_WITHER_SPAWN, 0.6F, 0.7F);
         }

         this.plugin
            .discord()
            .send(
               "event",
               "⚔️ **Boss mondial !** Le **"
                  + Text.strip(this.bossName)
                  + "** est apparu"
                  + (this.bossLocation.isBlank() ? " au spawn" : " en " + pos)
                  + ". Il reste "
                  + this.bossMinutes
                  + " min pour l'abattre : chaque joueur qui participe est récompensé."
            );
         return true;
      }
   }

   private boolean spawnBoss() {
      Location at = this.bossHome.clone().add(0.0, 0.5, 0.0);
      Class<? extends Entity> cls = this.bossType.getEntityClass();
      if (cls == null || !this.bossType.isAlive()) {
         return false;
      } else {
         Entity e = at.getWorld().spawn(at, cls, false, x -> {
         });
         if (!(e instanceof LivingEntity le)) {
            e.remove();
            return false;
         } else {
            le.addScoreboardTag(TAG);
            le.setPersistent(false);
            le.setRemoveWhenFarAway(false);
            double real = fr.windydev.clans.feature.BigHealth.apply(le, this.bossMax);
            // retour après déchargement : il garde ses PV
            le.setHealth(Math.max(1.0, Math.min(real, this.bossHp)));
            attr(le, Attribute.ATTACK_DAMAGE, this.bossDamage);
            attr(le, Attribute.KNOCKBACK_RESISTANCE, 0.9);
            attr(le, Attribute.FOLLOW_RANGE, 48.0);
            attr(le, Attribute.SCALE, this.bossScale);
            attr(le, Attribute.MOVEMENT_SPEED, 0.27);
            le.customName(Text.c(this.bossName));
            le.setCustomNameVisible(true);
            le.setGlowing(true);
            le.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, -1, 0, false, false));
            if (le.getEquipment() != null) {
               le.getEquipment().setItemInMainHand(new ItemStack(Material.IRON_SWORD));
               le.getEquipment().setHelmet(new ItemStack(Material.NETHERITE_HELMET));
               le.getEquipment().setItemInMainHandDropChance(0.0F);
               le.getEquipment().setHelmetDropChance(0.0F);
            }

            this.boss = le.getUniqueId();
            return true;
         }
      }
   }

   private static void attr(LivingEntity le, Attribute a, double v) {
      AttributeInstance i = le.getAttribute(a);
      if (i != null) {
         i.setBaseValue(v);
      }
   }

   private LivingEntity bossEntity() {
      return this.boss != null && Bukkit.getEntity(this.boss) instanceof LivingEntity le && le.isValid() ? le : null;
   }

   private List<Player> near(Location c, double r) {
      List<Player> l = new ArrayList<>();

      for (Player p : c.getWorld().getPlayers()) {
         if (!p.isDead() && (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE) && p.getLocation().distanceSquared(c) <= r * r) {
            l.add(p);
         }
      }

      return l;
   }

   private void tickBoss() {
      long now = System.currentTimeMillis();
      if (now >= this.endsAt) {
         this.endBoss(false, "le temps est écoulé");
      } else {
         LivingEntity b = this.bossEntity();
         if (b == null) {
            this.spawnBoss();
            b = this.bossEntity();
            if (b == null) {
               this.endBoss(false, "il n'a pas pu revenir");
               return;
            }
         }

         this.bossHp = b.getHealth();
         double realMax = b.getAttribute(Attribute.MAX_HEALTH).getValue();
         if (b.getLocation().distanceSquared(this.bossHome) > 1024.0 || b.getLocation().getY() < this.bossHome.getY() - 12.0) {
            b.teleport(this.bossHome);
         }

         List<Player> close = this.near(b.getLocation(), 40.0);
         if (b instanceof Mob mob && (mob.getTarget() == null || !(mob.getTarget() instanceof Player t) || !close.contains(t))) {
            Player best = null;
            double bd = Double.MAX_VALUE;

            for (Player p : close) {
               double d = p.getLocation().distanceSquared(b.getLocation());
               if (d < bd) {
                  bd = d;
                  best = p;
               }
            }

            mob.setTarget(best);
         }

         long left = (this.endsAt - now) / 1000L;
         this.bar.progress((float)Math.max(0.0, Math.min(1.0, b.getHealth() / realMax)));
         this.bar.name(Text.c(this.bossName + " &8• &f" + Text.formatTime(left)));
         Set<UUID> seen = new HashSet<>();

         for (Player p : this.near(this.bossHome, 96.0)) {
            seen.add(p.getUniqueId());
            if (this.barViewers.add(p.getUniqueId())) {
               p.showBossBar(this.bar);
            }
         }

         for (UUID u : new ArrayList<>(this.barViewers)) {
            if (!seen.contains(u)) {
               Player p = Bukkit.getPlayer(u);
               if (p != null) {
                  p.hideBossBar(this.bar);
               }

               this.barViewers.remove(u);
            }
         }

         this.minions.removeIf(u -> {
            Entity e = Bukkit.getEntity(u);
            return e == null || e.isDead() || !e.isValid();
         });
         if (now >= this.nextAbility && !close.isEmpty()) {
            this.nextAbility = now + 9000L + this.rnd.nextInt(4000);
            this.ability(b, close);
         }
      }
   }

   private void ability(LivingEntity b, List<Player> targets) {
      World w = b.getWorld();
      Location c = b.getLocation();
      switch (this.rnd.nextInt(3)) {
         case 0:
            w.playSound(c, Sound.ENTITY_WARDEN_SONIC_CHARGE, 1.6F, 0.8F);
            w.spawnParticle(Particle.EXPLOSION, c, 6, 2.0, 0.5, 2.0, 0.0);

            for (Player p : this.near(c, 8.0)) {
               Vector v = p.getLocation().toVector().subtract(c.toVector()).setY(0);
               if (v.lengthSquared() < 0.01) {
                  v = new Vector(1, 0, 0);
               }

               p.setVelocity(v.normalize().multiply(1.6).setY(0.7));
               p.damage(6.0, b);
            }

            for (Player p : targets) {
               p.sendActionBar(Text.c("&c☠ Onde de choc !"));
            }
            break;
         case 1:
            int n = Math.min(6, 2 + targets.size());
            if (this.minions.size() < 12) {
               for (int i = 0; i < n; i++) {
                  double a = this.rnd.nextDouble() * Math.PI * 2.0;
                  Location l = c.clone().add(Math.cos(a) * 4.0, 0.5, Math.sin(a) * 4.0);
                  EntityType t = this.minionTypes.get(this.rnd.nextInt(this.minionTypes.size()));
                  Class<? extends Entity> cls = t.getEntityClass();
                  if (cls != null && t.isAlive()) {
                     Entity e = w.spawn(l, cls, false, x -> {
                     });
                     e.addScoreboardTag(TAG);
                     e.setPersistent(false);
                     if (e instanceof LivingEntity le) {
                        le.setRemoveWhenFarAway(false);
                        le.customName(Text.c("&cSbire de la tempête"));
                        if (le.getEquipment() != null) {
                           le.getEquipment().setHelmet(new ItemStack(Material.LEATHER_HELMET));
                           le.getEquipment().setHelmetDropChance(0.0F);
                        }
                     }

                     if (e instanceof Mob m) {
                        m.setTarget(targets.get(this.rnd.nextInt(targets.size())));
                     }

                     this.minions.add(e.getUniqueId());
                     w.spawnParticle(Particle.SOUL, l, 12, 0.3, 0.6, 0.3, 0.02);
                  }
               }

               w.playSound(c, Sound.ENTITY_EVOKER_PREPARE_SUMMON, 1.4F, 0.7F);
            }
            break;
         default:
            w.playSound(c, Sound.ITEM_TRIDENT_THUNDER, 1.2F, 1.0F);

            for (Player p : targets) {
               Location l = p.getLocation().clone();
               w.spawnParticle(Particle.ELECTRIC_SPARK, l.clone().add(0.0, 0.2, 0.0), 40, 1.0, 0.1, 1.0, 0.05);
               p.sendActionBar(Text.c("&e⚡ La foudre va frapper : bouge !"));
               Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
                  if (this.running == Type.BOSS) {
                     w.strikeLightningEffect(l);

                     for (Player o : this.near(l, 2.5)) {
                        o.damage(7.0);
                     }
                  }
               }, 30L);
            }
      }
   }

   private boolean isBoss(Entity e) {
      return this.boss != null && e.getUniqueId().equals(this.boss);
   }

   @EventHandler(
      priority = EventPriority.MONITOR,
      ignoreCancelled = true
   )
   public void onBossHit(EntityDamageByEntityEvent e) {
      if (this.isBoss(e.getEntity())) {
         Player p = ProtectionListener.attacker(e.getDamager());
         if (p != null) {
            // dégâts « virtuels » (le colosse peut avoir plus de PV que la limite du serveur)
            double s = fr.windydev.clans.feature.BigHealth.scale(e.getEntity());
            this.damage.merge(p.getUniqueId(), Math.min(e.getFinalDamage(), ((LivingEntity)e.getEntity()).getHealth()) / s, Double::sum);
         }
      }
   }

   /** Le colosse ne prend que les coups des joueurs (pas de chute, lave, étouffement...). */
   @EventHandler(
      priority = EventPriority.LOWEST,
      ignoreCancelled = true
   )
   public void onBossHurt(EntityDamageEvent e) {
      if (this.isBoss(e.getEntity())) {
         boolean byPlayer = e instanceof EntityDamageByEntityEvent ed && ProtectionListener.attacker(ed.getDamager()) != null;
         if (!byPlayer && e.getCause() != EntityDamageEvent.DamageCause.KILL) {
            e.setCancelled(true);
         }
      }
   }

   @EventHandler(
      ignoreCancelled = true
   )
   public void onTarget(EntityTargetEvent e) {
      if (e.getEntity().getScoreboardTags().contains(TAG) && e.getTarget() != null && !(e.getTarget() instanceof Player)) {
         e.setCancelled(true);
      }
   }

   @EventHandler
   public void onDeath(EntityDeathEvent e) {
      if (e.getEntity().getScoreboardTags().contains(TAG)) {
         e.getDrops().clear();
         e.setDroppedExp(0);
         if (this.isBoss(e.getEntity())) {
            this.bossHp = 0.0;
            this.endBoss(true, null);
         }
      }
   }

   private void endBoss(boolean win, String reason) {
      if (this.running == Type.BOSS) {
         this.running = null;
         LivingEntity b = this.bossEntity();
         if (b != null && !b.isDead()) {
            b.getWorld().spawnParticle(Particle.LARGE_SMOKE, b.getLocation().add(0.0, 1.5, 0.0), 80, 1.0, 1.5, 1.0, 0.02);
            b.remove();
         }

         this.boss = null;

         for (UUID u : this.minions) {
            Entity e = Bukkit.getEntity(u);
            if (e != null) {
               e.remove();
            }
         }

         this.minions.clear();
         if (this.bar != null) {
            for (UUID u : this.barViewers) {
               Player p = Bukkit.getPlayer(u);
               if (p != null) {
                  p.hideBossBar(this.bar);
               }
            }
         }

         this.barViewers.clear();
         if (this.bossHome != null) {
            this.bossHome.getChunk().removePluginChunkTicket(this.plugin);
         }

         List<Entry<UUID, Double>> top = new ArrayList<>(this.damage.entrySet());
         top.sort((x, y) -> Double.compare(y.getValue(), x.getValue()));
         if (!win) {
            Bukkit.broadcast(Text.c(this.plugin.settings().prefix + "&7Le " + this.bossName + " &7s'est retiré : &c" + reason + "&7."));
            this.plugin.discord().send("event", "🌫️ Le **" + Text.strip(this.bossName) + "** s'est retiré : " + reason + ".");
         } else {
            List<String> names = new ArrayList<>();
            UUID best = top.isEmpty() ? null : top.get(0).getKey();

            for (Entry<UUID, Double> en : top) {
               Player p = Bukkit.getPlayer(en.getKey());
               if (p != null && en.getValue() >= this.minDamage) {
                  names.add(p.getName());
                  String got = this.bossReward.give(this.plugin, p, "combat");
                  this.plugin.msg(p, "&4✦ &7Récompense du boss mondial &8(" + (int)Math.round(en.getValue()) + " dégâts) &7: " + got);
                  if (en.getKey().equals(best) && !this.bossBest.empty()) {
                     String extra = this.bossBest.give(this.plugin, p, "combat");
                     this.plugin.msg(p, "&6★ Meilleur combattant &7: " + extra);
                  }

                  p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0F, 1.0F);
                  this.participated(p);
               } else if (p != null) {
                  this.plugin.msg(p, "&7Pas assez de dégâts pour la récompense &8(" + (int)Math.round(en.getValue()) + "/" + (int)this.minDamage + ")&7.");
               }
            }

            StringBuilder podium = new StringBuilder();

            for (int i = 0; i < top.size() && i < 3; i++) {
               podium.append(i == 0 ? "" : ", ").append(this.name(top.get(i).getKey())).append(" (").append((int)Math.round(top.get(i).getValue())).append(")");
            }

            Bukkit.broadcast(
               Text.c(
                  this.plugin.settings().prefix
                     + "&4✦ &fLe "
                     + this.bossName
                     + " &fest tombé ! &7"
                     + names.size()
                     + " joueur(s) récompensé(s). &8Podium : &f"
                     + podium
               )
            );

            for (Player p : Bukkit.getOnlinePlayers()) {
               p.playSound(p.getLocation(), Sound.ENTITY_WITHER_DEATH, 0.5F, 1.2F);
            }

            this.plugin
               .discord()
               .send("event", "🏆 Le **" + Text.strip(this.bossName) + "** est tombé ! " + names.size() + " joueur(s) récompensé(s). Podium : " + podium + ".");
         }

         this.damage.clear();
      }
   }

   private String name(UUID u) {
      Player p = Bukkit.getPlayer(u);
      if (p != null) {
         return p.getName();
      } else {
         String n = Bukkit.getOfflinePlayer(u).getName();
         return n == null ? "?" : n;
      }
   }

   // ------------------------------------------------------------------ chasse au trésor

   private boolean startTreasure() {
      World w = Bukkit.getWorlds().get(0);
      this.chests.clear();
      this.running = Type.TRESOR;
      this.placing = true;
      // placement pendant que les chunks se chargent en arrière-plan (pas de génération de terrain, pas de lag)
      this.endsAt = Long.MAX_VALUE;
      Bukkit.broadcast(Text.c(this.plugin.settings().prefix + "&6✦ &7La chasse au trésor se prépare : les coffres sont cachés..."));
      this.placeNext(w, w.getSpawnLocation(), 0, new ArrayList<>());
      return true;
   }

   private void placeNext(World w, Location c, int attempt, List<String> saved) {
      if (this.running != Type.TRESOR) {
         return;
      } else if (this.chests.size() < this.chestCount && attempt < this.chestCount * 30) {
         double a = this.rnd.nextDouble() * Math.PI * 2.0;
         double d = this.minDist + this.rnd.nextDouble() * (this.maxDist - this.minDist);
         int x = (int)Math.round(c.getX() + Math.cos(a) * d);
         int z = (int)Math.round(c.getZ() + Math.sin(a) * d);
         Location probe = new Location(w, x, 64.0, z);
         ChunkPos cp = ChunkPos.of(probe);
         if (this.plugin.clans().at(cp) == null && !this.plugin.clans().isSpawnZone(cp) && w.getWorldBorder().isInside(probe)) {
            w.getChunkAtAsync(x >> 4, z >> 4, false).whenComplete((ch, err) -> Bukkit.getScheduler().runTask(this.plugin, () -> {
               if (ch != null && this.running == Type.TRESOR) {
                  this.tryPlace(w, x, z, saved);
               }

               this.placeNext(w, c, attempt + 1, saved);
            }));
         } else {
            this.placeNext(w, c, attempt + 1, saved);
         }
      } else {
         this.treasureReady(saved);
      }
   }

   private void tryPlace(World w, int x, int z, List<String> saved) {
      Block top = w.getHighestBlockAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
      Material m = top.getType();
      if (m.isSolid() && !Tag.LEAVES.isTagged(m) && m != Material.ICE && m != Material.PACKED_ICE && m != Material.BEDROCK && top.getY() > w.getMinHeight() + 1) {
         Block place = top.getRelative(BlockFace.UP);
         if (place.isEmpty() || place.isReplaceable() && !place.isLiquid()) {
            int n = this.chests.size() + 1;
            place.setType(Material.CHEST, false);
            BlockState st = place.getState();
            if (st instanceof TileState ts) {
               ts.getPersistentDataContainer().set(this.chestKey, PersistentDataType.INTEGER, n);
               ts.update(true, false);
            }

            this.chests.add(new Treasure(n, place.getLocation()));
            saved.add(w.getName() + "," + place.getX() + "," + place.getY() + "," + place.getZ());
            this.data.set("chests", saved);
            this.saveData();
         }
      }
   }

   private void treasureReady(List<String> saved) {
      this.placing = false;
      this.startedAt = System.currentTimeMillis();
      if (this.chests.isEmpty()) {
         this.plugin.getLogger().warning("Chasse au trésor : aucun emplacement trouvé pour les coffres (terrain pas encore généré ?).");
         this.running = null;
         Bukkit.broadcast(Text.c(this.plugin.settings().prefix + "&7La chasse au trésor est annulée : aucun coffre n'a pu être caché."));
      } else {
         this.endsAt = System.currentTimeMillis() + this.chestMinutes * 60_000L;
         this.clueIndex = 0;

         for (Treasure t : this.chests) {
            for (Entry<Integer, Integer> cl : this.clues.entrySet()) {
               double a = this.rnd.nextDouble() * Math.PI * 2.0;
               double r = this.rnd.nextDouble() * cl.getValue() * 0.8;
               t.hint.put(cl.getKey(), new int[]{(int)Math.round(t.loc.getX() + Math.cos(a) * r), (int)Math.round(t.loc.getZ() + Math.sin(a) * r)});
            }
         }

         Bukkit.broadcast(
            Text.c(
               this.plugin.settings().prefix
                  + "&6✦ &e&lChasse au trésor ! &f"
                  + this.chests.size()
                  + " coffres &7sont cachés dans la nature du monde principal. Le premier qui en ouvre un garde son contenu. &8("
                  + this.chestMinutes
                  + " min)"
            )
         );

         for (Player p : Bukkit.getOnlinePlayers()) {
            p.showTitle(Title.title(Text.c("&6✦ Chasse au trésor"), Text.c("&7" + this.chests.size() + " coffres cachés : suivez les indices")));
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7F, 0.8F);
         }

         this.plugin
            .discord()
            .send(
               "event",
               "🗺️ **Chasse au trésor !** " + this.chests.size() + " coffres sont cachés dans la nature. Les indices tombent dans le chat du jeu pendant " + this.chestMinutes + " min."
            );
      }
   }

   private void tickTreasure() {
      long now = System.currentTimeMillis();
      if (this.placing) {
         return;
      } else if (now >= this.endsAt) {
         this.endTreasure("le temps est écoulé");
      } else {
         List<Integer> times = new ArrayList<>(this.clues.keySet());
         times.sort(Integer::compare);
         long elapsedMin = (now - this.startedAt) / 60_000L;
         while (this.clueIndex < times.size() && elapsedMin >= times.get(this.clueIndex)) {
            this.clue(times.get(this.clueIndex), this.clueIndex + 1);
            this.clueIndex++;
         }

         if ((now / 1000L) % 2L == 0L) {
            for (Treasure t : this.chests) {
               if (t.finder == null) {
                  Location l = t.loc.clone().add(0.5, 1.2, 0.5);
                  if (l.getWorld() != null && l.isChunkLoaded() && !this.near(l, 24.0).isEmpty()) {
                     l.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, l, 8, 0.4, 0.6, 0.4, 0.0);
                     l.getWorld().spawnParticle(Particle.END_ROD, l.clone().add(0.0, 1.5, 0.0), 3, 0.05, 1.5, 0.05, 0.01);
                  }
               }
            }
         }
      }
   }

   private void clue(int minute, int number) {
      int precision = this.clues.getOrDefault(minute, 100);
      List<Component> lines = new ArrayList<>();
      lines.add(Text.c(this.plugin.settings().prefix + "&6✦ Indice n°" + number + " &7(à moins de &f" + precision + " blocs&7) :"));
      StringBuilder discord = new StringBuilder();

      for (Treasure t : this.chests) {
         if (t.finder == null) {
            int[] h = t.hint.get(minute);
            if (h != null) {
               lines.add(Text.c("&e  Coffre " + t.n + " &7: vers &fX " + h[0] + " &7/ &fZ " + h[1]));
               discord.append("\n• Coffre ").append(t.n).append(" : vers X ").append(h[0]).append(" / Z ").append(h[1]);
            }
         }
      }

      if (lines.size() > 1) {
         for (Component l : lines) {
            Bukkit.broadcast(l);
         }

         for (Player p : Bukkit.getOnlinePlayers()) {
            p.playSound(p.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 1.0F, 1.0F);
         }

         if (number == 1) {
            this.plugin.discord().send("event", "🧭 **Indices de la chasse au trésor** (à moins de " + precision + " blocs) :" + discord);
         }
      }
   }

   private Treasure treasureAt(Block b) {
      if (this.running == Type.TRESOR && b.getType() == Material.CHEST) {
         for (Treasure t : this.chests) {
            if (t.finder == null && t.loc.getBlock().equals(b)) {
               return t;
            }
         }
      }

      return null;
   }

   @EventHandler(
      priority = EventPriority.LOWEST
   )
   public void onOpen(PlayerInteractEvent e) {
      if (e.getAction() == Action.RIGHT_CLICK_BLOCK && e.getClickedBlock() != null && e.getHand() == EquipmentSlot.HAND) {
         Treasure t = this.treasureAt(e.getClickedBlock());
         if (t != null) {
            e.setCancelled(true);
            this.found(e.getPlayer(), t);
         }
      }
   }

   @EventHandler(
      priority = EventPriority.LOWEST
   )
   public void onBreakChest(BlockBreakEvent e) {
      Treasure t = this.treasureAt(e.getBlock());
      if (t != null) {
         e.setCancelled(true);
         this.found(e.getPlayer(), t);
      }
   }

   @EventHandler(
      ignoreCancelled = true
   )
   public void onExplode(EntityExplodeEvent e) {
      e.blockList().removeIf(b -> this.treasureAt(b) != null);
   }

   @EventHandler(
      ignoreCancelled = true
   )
   public void onBlockExplode(BlockExplodeEvent e) {
      e.blockList().removeIf(b -> this.treasureAt(b) != null);
   }

   private void found(Player p, Treasure t) {
      if (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE) {
         t.finder = p.getName();
         Block b = t.loc.getBlock();
         b.setType(Material.AIR, false);
         Location c = t.loc.clone().add(0.5, 0.5, 0.5);
         c.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, c, 60, 0.4, 0.6, 0.4, 0.3);
         c.getWorld().playSound(c, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0F, 1.2F);
         List<String> got = new ArrayList<>();

         for (String spec : this.loot) {
            ItemStack it = Reward.item(this.plugin, spec);
            if (it != null) {
               for (ItemStack rest : p.getInventory().addItem(it).values()) {
                  p.getWorld().dropItemNaturally(p.getLocation(), rest);
               }

               got.add("&f" + it.getAmount() + "x " + Reward.name(this.plugin, it));
            }
         }

         this.plugin.msg(p, "&6✦ Trésor ! " + String.join("&7, ", got));
         this.participated(p);
         long left = this.chests.stream().filter(x -> x.finder == null).count();
         Bukkit.broadcast(
            Text.c(this.plugin.settings().prefix + "&6✦ &f" + p.getName() + " &7a trouvé le &ecoffre " + t.n + "&7 ! " + (left > 0L ? "&8(encore " + left + ")" : ""))
         );
         if (left == 0L) {
            this.endTreasure(null);
         }
      }
   }

   private void endTreasure(String reason) {
      if (this.running == Type.TRESOR) {
         this.running = null;
         List<String> who = new ArrayList<>();

         for (Treasure t : this.chests) {
            if (t.finder != null) {
               who.add("coffre " + t.n + " : " + t.finder);
            } else {
               Block b = t.loc.getBlock();
               if (b.getType() == Material.CHEST) {
                  b.setType(Material.AIR, false);
               }
            }
         }

         this.data.set("chests", null);
         this.saveData();
         String summary = who.isEmpty() ? "aucun coffre trouvé" : String.join(", ", who);
         Bukkit.broadcast(Text.c(this.plugin.settings().prefix + "&6✦ Fin de la chasse au trésor" + (reason == null ? "" : " &7(" + reason + ")") + " : &f" + summary + "&7."));
         this.plugin.discord().send("event", "🗺️ **Fin de la chasse au trésor** : " + summary + ".");
         this.chests.clear();
      }
   }

   /** Retire les coffres d'une chasse interrompue par un arrêt du serveur. */
   private void cleanupChests() {
      List<String> saved = this.data.getStringList("chests");
      if (!saved.isEmpty() && this.running == null) {
         int removed = 0;

         for (String s : saved) {
            String[] p = s.split(",");

            try {
               World w = Bukkit.getWorld(p[0]);
               if (w != null) {
                  Block b = w.getBlockAt(Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
                  if (b.getType() == Material.CHEST
                     && b.getState() instanceof TileState ts
                     && ts.getPersistentDataContainer().has(this.chestKey, PersistentDataType.INTEGER)) {
                     b.setType(Material.AIR, false);
                     removed++;
                  }
               }
            } catch (Exception var8) {
            }
         }

         this.data.set("chests", null);
         this.saveData();
         this.plugin.getLogger().info("Chasse au trésor interrompue : " + removed + " coffre(s) retiré(s).");
      }
   }

   // ------------------------------------------------------------------ joueurs

   public ItemStack icon() {
      ZonedDateTime at = this.next();
      Type t = this.nextType();
      List<String> lore = new ArrayList<>();
      if (this.running != null) {
         lore.add("&a● En cours : &f" + this.running.label);
         lore.add("");
      }

      if (this.enabled) {
         lore.add("&7Prochain : &d" + t.label);
         lore.add("&7" + at.format(WHEN));
         lore.add("");
         lore.add("&8" + this.describe(t) + ".");
      } else {
         lore.add("&8Désactivé.");
      }

      lore.add("");
      lore.add("&eClic : détails");
      return ItemBuilder.of(this.running != null ? this.running.icon : Material.FIREWORK_ROCKET).name("&dÉvènement de la semaine").lore(lore).glow(this.running != null).build();
   }

   public void info(CommandSender s) {
      if (this.running == Type.BOSS) {
         LivingEntity b = this.bossEntity();
         this.plugin
            .msg(
               s,
               "&4✦ Boss mondial en cours &7: "
                  + this.bossName
                  + " &7("
                  + (b == null ? "?" : (int)Math.round(b.getHealth() * 100.0 / b.getAttribute(Attribute.MAX_HEALTH).getValue()) + " %")
                  + ") en &fX "
                  + this.bossHome.getBlockX()
                  + " Z "
                  + this.bossHome.getBlockZ()
                  + "&7, encore &f"
                  + Text.formatTime((this.endsAt - System.currentTimeMillis()) / 1000L)
                  + "&7."
            );
         if (s instanceof Player p && this.damage.containsKey(p.getUniqueId())) {
            this.plugin.msg(s, "&7Tes dégâts : &f" + (int)Math.round(this.damage.get(p.getUniqueId())) + "&7/" + (int)this.minDamage);
         }
      } else if (this.running == Type.TRESOR) {
         long left = this.chests.stream().filter(x -> x.finder == null).count();
         this.plugin.msg(s, "&6✦ Chasse au trésor en cours &7: &f" + left + " coffre(s) &7à trouver, encore &f" + Text.formatTime((this.endsAt - System.currentTimeMillis()) / 1000L) + "&7.");
         List<Integer> times = new ArrayList<>(this.clues.keySet());
         times.sort(Integer::compare);
         if (this.clueIndex > 0) {
            int minute = times.get(this.clueIndex - 1);

            for (Treasure t : this.chests) {
               if (t.finder == null && t.hint.get(minute) != null) {
                  s.sendMessage(Text.c("&e  Coffre " + t.n + " &7: vers &fX " + t.hint.get(minute)[0] + " &7/ &fZ " + t.hint.get(minute)[1] + " &8(" + this.clues.get(minute) + " blocs)"));
               }
            }
         }
      } else if (this.running == Type.FAILLE) {
         this.plugin.msg(s, "&5✦ Faille géante en cours &7: &f/faille &7pour entrer.");
      } else if (!this.enabled) {
         this.plugin.msg(s, "&7L'évènement de la semaine est désactivé.");
      } else {
         ZonedDateTime at = this.next();
         Type t = this.nextType();
         long min = Math.max(0L, Duration.between(ZonedDateTime.now(this.zone), at).toMinutes());
         this.plugin.msg(s, "&d✦ Évènement de la semaine &7: &f" + t.label + " &7" + at.format(WHEN) + " &8(dans " + (min >= 60L ? min / 60L + " h " + min % 60L + " min" : min + " min") + ")");
         s.sendMessage(Text.c("&7  " + this.describe(t) + "."));
      }
   }

   public void admin(CommandSender s, String[] args) {
      String act = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "info";
      switch (act) {
         case "lancer", "start" -> {
            Type t = args.length > 2 ? Type.of(args[2]) : this.nextType();
            if (t == null) {
               this.plugin.msg(s, "&cTypes : boss, tresor, faille");
            } else if (this.running != null) {
               this.plugin.msg(s, "&cUn évènement est déjà en cours.");
            } else {
               this.plugin.msg(s, this.begin(t) ? "&aÉvènement lancé : " + t.label + "." : "&cImpossible de lancer l'évènement.");
            }
         }
         case "arreter", "stop" -> {
            if (this.running == Type.BOSS) {
               this.endBoss(false, "un administrateur l'a arrêté");
            } else if (this.running == Type.TRESOR) {
               this.endTreasure("arrêtée par un administrateur");
            } else if (this.running == Type.FAILLE) {
               this.running = null;
               this.plugin.msg(s, "&7Ferme la faille avec /clanadmin faille fermer.");
            } else {
               this.plugin.msg(s, "&7Aucun évènement en cours.");
            }
         }
         case "lieu", "location" -> {
            if (s instanceof Player p) {
               Location l = p.getLocation();
               this.bossLocation = l.getWorld().getName() + "," + l.getBlockX() + "," + (l.getBlockY() + 0.0) + "," + l.getBlockZ();
               YamlConfiguration y = YamlConfiguration.loadConfiguration(this.file);
               y.set("boss.location", this.bossLocation);

               try {
                  y.save(this.file);
               } catch (Exception var7) {
               }

               this.plugin.msg(s, "&aLieu du boss mondial : " + this.bossLocation);
            }
         }
         default -> {
            this.info(s);
            this.plugin.msg(s, "&7/clanadmin evenement <lancer [boss|tresor|faille]|arreter|info|lieu>");
         }
      }
   }

   @Override
   public boolean onCommand(@NotNull CommandSender s, @NotNull Command cmd, @NotNull String label, @NotNull String[] args) {
      this.info(s);
      return true;
   }

   @Override
   public List<String> onTabComplete(@NotNull CommandSender s, @NotNull Command c, @NotNull String l, @NotNull String[] args) {
      return List.of();
   }
}
