package fr.windydev.clans.rift;

import fr.windydev.clans.WindyClans;
import fr.windydev.clans.util.Text;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.World.Environment;
import org.bukkit.WorldCreator;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.AbstractSkeleton;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

/**
 * Faille arcanique : un sanctuaire, dans un monde à part, qui s'ouvre environ une fois par jour quand il y a
 * du monde. Les joueurs y chargent un rituel en tuant des monstres, puis affrontent l'Avatar de la faille.
 * Chaque rituel réussi donne un Fragment d'éveil ; assez de fragments = lancer ses sorts sans catalyseur.
 */
public class RiftManager implements Listener, CommandExecutor, TabCompleter {
   public static final String WORLD = "windy_faille";
   private static final int BUILD_VERSION = 1;
   private static final int Y = 100;
   private static final int R = 24;

   private final WindyClans plugin;
   private final Random rnd = new Random();
   private final NamespacedKey keyMob;
   private final NamespacedKey keyDmg;
   private final NamespacedKey keyBoss;
   private final NamespacedKey keyBuilt;
   private World world;
   private File dataFile;
   private long nextAt;

   // réglages
   private boolean enabled = true;
   private double intervalH = 24.0;
   private double jitterH = 2.0;
   private int minPlayers = 2;
   private int durationMin = 30;
   private int emptyCloseMin = 10;
   private double minDamage = 150.0;
   private int level = 4;
   private double hpPerLevel = 0.35;
   private double dmgPerLevel = 0.2;
   private double hpPerPlayer = 0.15;
   private int killsBase = 60;
   private int killsPerPlayer = 25;
   private int aliveBase = 8;
   private int alivePerPlayer = 4;
   private double bossHp = 600.0;
   private double bossHpPerPlayer = 250.0;
   private double bossDamage = 1.8;
   private String bossName = "&5Avatar de la faille";
   private EntityType bossType = EntityType.WITHER_SKELETON;
   private long xp = 1500L;
   private long souls = 150L;
   private final List<MobSpec> mobs = new ArrayList<>();
   private final List<Guardian> guardians = new ArrayList<>();
   private final List<String> rewards = new ArrayList<>();

   // Faille géante (évènement de la semaine, MAJ 1.21.0)
   private boolean giant;
   private double gDuration = 1.5;
   private double gKills = 1.5;
   private double gHp = 1.4;
   private double gBossHp = 2.0;
   private int gRewards = 2;

   // évènement en cours
   private boolean running;
   private boolean finishing;
   private long endsAt;
   private long startedAt;
   private long emptySince;
   private int kills;
   private int needed;
   private int peak;
   private int nextGuardian;
   private UUID boss;
   private double bossMax;
   private long nextAbility;
   private int warned;
   private BossBar bar;
   private final Map<UUID, Double> damage = new HashMap<>();
   private final Map<UUID, Location> returns = new HashMap<>();
   private final Set<UUID> alive = new HashSet<>();

   record MobSpec(EntityType type, String name, double from) {
   }

   record Guardian(double at, EntityType type, String name, double hp, double dmg) {
   }

   public RiftManager(WindyClans plugin) {
      this.plugin = plugin;
      this.keyMob = new NamespacedKey(plugin, "rift");
      this.keyDmg = new NamespacedKey(plugin, "rift_dmg");
      this.keyBoss = new NamespacedKey(plugin, "rift_boss");
      this.keyBuilt = new NamespacedKey(plugin, "rift_built");
   }

   // ------------------------------------------------------------------ configuration

   public void load() {
      File f = new File(this.plugin.getDataFolder(), "faille.yml");
      if (!f.exists()) {
         this.plugin.saveResource("faille.yml", false);
      }

      YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
      this.enabled = y.getBoolean("settings.enabled", true);
      this.intervalH = Math.max(0.1, y.getDouble("settings.interval-hours", 24.0));
      this.jitterH = Math.max(0.0, y.getDouble("settings.jitter-hours", 2.0));
      this.minPlayers = Math.max(1, y.getInt("settings.min-players", 2));
      this.durationMin = Math.max(5, y.getInt("settings.duration-minutes", 30));
      this.emptyCloseMin = Math.max(1, y.getInt("settings.close-if-empty-minutes", 10));
      this.minDamage = y.getDouble("settings.min-damage", 150.0);
      this.level = Math.max(1, y.getInt("monsters.level", 4));
      this.hpPerLevel = y.getDouble("monsters.hp-per-level", 0.35);
      this.dmgPerLevel = y.getDouble("monsters.damage-per-level", 0.2);
      this.hpPerPlayer = y.getDouble("monsters.hp-per-extra-player", 0.15);
      this.killsBase = Math.max(5, y.getInt("ritual.kills", 60));
      this.killsPerPlayer = Math.max(0, y.getInt("ritual.kills-per-extra-player", 25));
      this.aliveBase = Math.max(2, y.getInt("monsters.max-alive", 8));
      this.alivePerPlayer = Math.max(0, y.getInt("monsters.max-alive-per-extra-player", 4));
      this.bossHp = y.getDouble("boss.hp", 600.0);
      this.bossHpPerPlayer = y.getDouble("boss.hp-per-extra-player", 250.0);
      this.bossDamage = y.getDouble("boss.damage", 1.8);
      this.bossName = y.getString("boss.name", "&5Avatar de la faille");
      this.bossType = type(y.getString("boss.type"), EntityType.WITHER_SKELETON);
      this.xp = y.getLong("rewards.xp", 1500L);
      this.souls = y.getLong("rewards.souls", 150L);
      this.rewards.clear();
      this.rewards.addAll(y.getStringList("rewards.items"));
      this.mobs.clear();
      for (Map<?, ?> m : y.getMapList("monsters.types")) {
         EntityType t = type(String.valueOf(m.get("type")), null);
         if (t != null) {
            this.mobs.add(new MobSpec(t, String.valueOf(m.get("name")), m.get("from") instanceof Number n ? n.doubleValue() : 0.0));
         }
      }

      if (this.mobs.isEmpty()) {
         this.mobs.add(new MobSpec(EntityType.ZOMBIE, "Rôdeur de la faille", 0.0));
      }

      this.guardians.clear();
      for (Map<?, ?> m : y.getMapList("guardians")) {
         EntityType t = type(String.valueOf(m.get("type")), null);
         if (t != null) {
            this.guardians.add(new Guardian(num(m.get("at"), 0.5), t, String.valueOf(m.get("name")), num(m.get("hp"), 150.0), num(m.get("damage"), 1.3)));
         }
      }

      this.guardians.sort((a, b) -> Double.compare(a.at(), b.at()));
      this.dataFile = new File(this.plugin.getDataFolder(), "faille-data.yml");
      this.nextAt = YamlConfiguration.loadConfiguration(this.dataFile).getLong("next-at", 0L);
      if (this.nextAt <= 0L) {
         // première installation : première faille dans 2 h
         this.nextAt = System.currentTimeMillis() + 2L * 3600_000L;
         this.saveData();
      }
   }

   private static double num(Object o, double def) {
      return o instanceof Number n ? n.doubleValue() : def;
   }

   private static EntityType type(String s, EntityType def) {
      if (s == null) {
         return def;
      }

      try {
         EntityType t = EntityType.valueOf(s.toUpperCase(Locale.ROOT));
         return t.isAlive() && t.getEntityClass() != null ? t : def;
      } catch (IllegalArgumentException e) {
         return def;
      }
   }

   private void saveData() {
      YamlConfiguration y = new YamlConfiguration();
      y.set("next-at", this.nextAt);

      try {
         y.save(this.dataFile);
      } catch (IOException e) {
         this.plugin.getLogger().warning("faille-data.yml : sauvegarde impossible : " + e.getMessage());
      }
   }

   public void start() {
      Bukkit.getScheduler().runTaskTimer(this.plugin, this::tick, 40L, 20L);
   }

   public void shutdown() {
      if (this.running) {
         this.end(false, "le serveur redémarre", false);
      }
   }

   public boolean isRift(World w) {
      return w != null && w.getName().equals(WORLD);
   }

   public boolean running() {
      return this.running;
   }

   // ------------------------------------------------------------------ monde et sanctuaire

   private World world() {
      if (this.world == null) {
         this.world = Bukkit.getWorld(WORLD);
         if (this.world == null) {
            this.world = new WorldCreator(WORLD)
               .generator(new VoidGenerator())
               .environment(Environment.NORMAL)
               .generateStructures(false)
               .keepSpawnLoaded(net.kyori.adventure.util.TriState.FALSE)
               .createWorld();
         }

         this.world.setGameRule(GameRule.KEEP_INVENTORY, true);
         this.world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
         this.world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
         this.world.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
         this.world.setGameRule(GameRule.DO_FIRE_TICK, false);
         this.world.setGameRule(GameRule.MOB_GRIEFING, false);
         this.world.setGameRule(GameRule.ANNOUNCE_ADVANCEMENTS, false);
         this.world.setGameRule(GameRule.DO_INSOMNIA, false);
         this.world.setTime(18000L);
         this.world.setDifficulty(Difficulty.HARD);
      }

      return this.world;
   }

   private Location spawn() {
      return new Location(this.world(), 0.5, Y + 1, -13.5, 0.0F, 0.0F);
   }

   private Location center() {
      return new Location(this.world(), 0.5, Y + 1, 0.5);
   }

   private void set(World w, int x, int y, int z, Material m) {
      w.getBlockAt(x, y, z).setType(m, false);
   }

   /** Construit le sanctuaire (une île flottante) s'il n'existe pas encore. */
   private void build() {
      World w = this.world();
      Integer v = w.getPersistentDataContainer().get(this.keyBuilt, PersistentDataType.INTEGER);
      if (v != null && v == BUILD_VERSION) {
         return;
      }

      Random r = new Random(4242L);
      Material[] floor = {Material.DEEPSLATE_TILES, Material.DEEPSLATE_TILES, Material.DEEPSLATE_TILES, Material.CRACKED_DEEPSLATE_TILES, Material.DEEPSLATE_BRICKS,
         Material.POLISHED_DEEPSLATE};
      for (int x = -R - 1; x <= R + 1; x++) {
         for (int z = -R - 1; z <= R + 1; z++) {
            double d = Math.sqrt(x * x + z * z);
            for (int y = Y - 12; y <= Y + 12; y++) {
               set(w, x, y, z, Material.AIR);
            }

            if (d > R + 0.5) {
               continue;
            }

            // dessous de l'île : de plus en plus étroit
            int depth = (int)Math.max(1, (R - d) * 0.45 + r.nextInt(2));
            for (int k = 1; k <= Math.min(10, depth); k++) {
               set(w, x, Y - k, z, k == 1 ? Material.DEEPSLATE : (r.nextInt(5) == 0 ? Material.BLACKSTONE : Material.COBBLED_DEEPSLATE));
            }

            Material m = floor[r.nextInt(floor.length)];
            int ring = (int)Math.round(d);
            if (ring == 6 || ring == 15) {
               m = Material.POLISHED_BLACKSTONE_BRICKS;
            } else if (ring == 5 || ring == 16) {
               m = r.nextInt(4) == 0 ? Material.CRYING_OBSIDIAN : Material.OBSIDIAN;
            } else if (d < 4.5) {
               m = (x + z) % 2 == 0 ? Material.AMETHYST_BLOCK : Material.POLISHED_BLACKSTONE;
            } else if ((x == 0 || z == 0 || Math.abs(x) == Math.abs(z)) && d > 6.5 && d < 14.5) {
               m = Material.PURPLE_GLAZED_TERRACOTTA;
            }

            set(w, x, Y, z, m);
            // muret du bord : personne ne tombe dans le vide
            if (d > R - 1.5) {
               int h = 3 + r.nextInt(2);
               for (int k = 1; k <= h; k++) {
                  set(w, x, Y + k, z, k == h && r.nextInt(3) == 0 ? Material.CRACKED_DEEPSLATE_BRICKS : Material.DEEPSLATE_BRICKS);
               }
            }
         }
      }

      // autel central
      for (int x = -1; x <= 1; x++) {
         for (int z = -1; z <= 1; z++) {
            set(w, x, Y + 1, z, x == 0 && z == 0 ? Material.CRYING_OBSIDIAN : Material.POLISHED_BLACKSTONE_BRICK_SLAB);
         }
      }

      set(w, 0, Y + 2, 0, Material.BUDDING_AMETHYST);
      set(w, 0, Y + 3, 0, Material.AMETHYST_CLUSTER);
      // huit obélisques
      for (int i = 0; i < 8; i++) {
         double a = Math.PI * 2 * i / 8 + Math.PI / 8;
         int x = (int)Math.round(Math.cos(a) * 19), z = (int)Math.round(Math.sin(a) * 19);
         int h = i % 2 == 0 ? 7 : 5;
         for (int k = 1; k <= h; k++) {
            set(w, x, Y + k, z, k == 1 || k == h ? Material.CHISELED_POLISHED_BLACKSTONE : Material.POLISHED_BLACKSTONE);
         }

         set(w, x, Y + h + 1, z, Material.PEARLESCENT_FROGLIGHT);
         set(w, x, Y + h + 2, z, Material.AMETHYST_CLUSTER);
         for (int[] o : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            set(w, x + o[0], Y + 1, z + o[1], Material.POLISHED_BLACKSTONE_BRICK_STAIRS);
         }
      }

      // lumières du bord
      for (int i = 0; i < 16; i++) {
         double a = Math.PI * 2 * i / 16;
         int x = (int)Math.round(Math.cos(a) * (R - 3)), z = (int)Math.round(Math.sin(a) * (R - 3));
         set(w, x, Y + 1, z, Material.POLISHED_BLACKSTONE_BRICK_WALL);
         set(w, x, Y + 2, z, Material.SOUL_LANTERN);
      }

      w.getPersistentDataContainer().set(this.keyBuilt, PersistentDataType.INTEGER, BUILD_VERSION);
   }

   // ------------------------------------------------------------------ déroulement

   private void scheduleNext() {
      double h = this.intervalH + (this.rnd.nextDouble() * 2.0 - 1.0) * Math.min(this.jitterH, this.intervalH / 2.0);
      this.nextAt = System.currentTimeMillis() + Math.round(h * 3600_000.0);
      this.saveData();
   }

   private List<Player> inside() {
      return this.world == null ? List.of() : new ArrayList<>(this.world.getPlayers());
   }

   private List<Player> fighters() {
      List<Player> l = new ArrayList<>();
      for (Player p : this.inside()) {
         if (!p.isDead() && p.getGameMode() != GameMode.SPECTATOR && p.getGameMode() != GameMode.CREATIVE) {
            l.add(p);
         }
      }

      return l;
   }

   public boolean giant() {
      return this.running && this.giant;
   }

   /** Ouvre la Faille géante de l'évènement de la semaine (multiplicateurs de evenements.yml). */
   public boolean openGiant(double duration, double kills, double hp, double bossHp, int rewards) {
      if (this.running) {
         return false;
      } else {
         this.giant = true;
         this.gDuration = Math.max(1.0, duration);
         this.gKills = Math.max(0.5, kills);
         this.gHp = Math.max(0.5, hp);
         this.gBossHp = Math.max(0.5, bossHp);
         this.gRewards = Math.max(1, rewards);
         boolean ok = this.open(null);
         if (!ok) {
            this.giant = false;
         }

         return ok;
      }
   }

   private double killsMult() {
      return this.giant ? this.gKills : 1.0;
   }

   private double hpMult() {
      return this.giant ? this.gHp : 1.0;
   }

   private String riftName() {
      return this.giant ? "Faille géante" : "Faille arcanique";
   }

   public boolean open(CommandSender by) {
      if (this.running) {
         return false;
      }

      this.build();
      this.clearMobs();
      this.running = true;
      this.finishing = false;
      this.startedAt = System.currentTimeMillis();
      this.endsAt = this.startedAt + Math.round(this.durationMin * (this.giant ? this.gDuration : 1.0) * 60_000L);
      this.emptySince = this.startedAt;
      this.kills = 0;
      this.peak = 1;
      this.needed = (int)Math.round(this.killsBase * this.killsMult());
      this.nextGuardian = 0;
      this.boss = null;
      this.warned = 0;
      this.damage.clear();
      this.alive.clear();
      this.bar = BossBar.bossBar(Text.c("&5" + this.riftName()), 0.0F, BossBar.Color.PURPLE, BossBar.Overlay.NOTCHED_10);
      long minutes = Math.round((this.endsAt - this.startedAt) / 60_000.0);
      Component click = Text.c("&d&l[REJOINDRE]").clickEvent(ClickEvent.runCommand("/faille")).hoverEvent(HoverEvent.showText(Text.c("&7Clique pour entrer dans la faille")));
      Component msg = this.giant
         ? Text.c("&5✦ &d&lFAILLE GÉANTE ! &7L'évènement de la semaine commence : la faille s'ouvre en grand, ses créatures sont plus fortes et les &drécompenses x" + this.gRewards + "&7. &8(" + minutes + " min) ").append(click)
         : Text.c("&5✦ &dUne &5&lFaille arcanique &ds'ouvre ! &7Chargez le rituel en tuant ses créatures, puis abattez l'Avatar. &8(" + minutes + " min) ").append(click);
      for (Player p : Bukkit.getOnlinePlayers()) {
         p.sendMessage(msg);
         p.showTitle(Title.title(Text.c("&5✦ " + this.riftName()), Text.c("&d/faille &7pour entrer")));
         p.playSound(p.getLocation(), Sound.BLOCK_END_PORTAL_SPAWN, 0.5F, 1.2F);
      }

      if (this.giant) {
         this.plugin.bot().announce("event", "🌀 **Faille géante !** L'évènement de la semaine commence : la Faille arcanique s'ouvre en grand pendant " + minutes
            + " min, récompenses x" + this.gRewards + ". `/faille` pour entrer.");
      } else {
         this.plugin.bot().announce("rift", "🌀 **Une Faille arcanique s'ouvre !** Elle reste ouverte " + minutes
            + " min : `/faille` pour entrer. Trois rituels réussis éveillent la magie sans catalyseur.");
      }
      this.plugin.getLogger().info("Faille arcanique ouverte" + (by == null ? "" : " par " + by.getName()) + ".");
      return true;
   }

   private void tick() {
      long now = System.currentTimeMillis();
      if (!this.running) {
         if (this.enabled && now >= this.nextAt && Bukkit.getOnlinePlayers().size() >= this.minPlayers) {
            this.open(null);
         }

         return;
      }

      if (this.finishing) {
         return;
      }

      List<Player> in = this.inside();
      List<Player> fight = this.fighters();
      if (!fight.isEmpty()) {
         this.emptySince = now;
         if (fight.size() > this.peak) {
            this.peak = fight.size();
            this.needed = (int)Math.round((this.killsBase + this.killsPerPlayer * (this.peak - 1)) * this.killsMult());
         }
      } else if (now - this.emptySince > this.emptyCloseMin * 60_000L) {
         this.end(false, "personne n'est resté dans la faille", true);
         return;
      }

      if (now >= this.endsAt) {
         this.end(false, "le temps est écoulé", true);
         return;
      }

      long left = (this.endsAt - now) / 1000L;
      int mark = left <= 60 ? 3 : (left <= 300 ? 2 : (left <= 900 ? 1 : 0));
      if (mark > this.warned) {
         this.warned = mark;
         String t = mark == 3 ? "1 minute" : (mark == 2 ? "5 minutes" : "15 minutes");
         for (Player p : in) {
            p.sendMessage(Text.c("&5✦ &7La faille se referme dans &d" + t + "&7."));
         }

         if (mark == 1 && this.boss == null) {
            Component click = Text.c("&d[REJOINDRE]").clickEvent(ClickEvent.runCommand("/faille"));
            Bukkit.broadcast(Text.c("&5✦ &7La " + this.riftName() + " est encore ouverte &815 min &7(rituel à &d" + this.percent() + " %&7). ").append(click));
         }
      }

      this.alive.removeIf(u -> {
         Entity e = Bukkit.getEntity(u);
         return e == null || e.isDead() || !e.isValid();
      });
      World w = this.world();
      LivingEntity b = this.boss == null ? null : (Bukkit.getEntity(this.boss) instanceof LivingEntity le ? le : null);
      if (this.boss != null) {
         if (b == null || b.isDead()) {
            // disparu sans mourir (chute, déchargement) : il revient
            this.spawnBoss();
         } else {
            if (b.getLocation().getY() < Y - 6 || b.getLocation().distanceSquared(this.center()) > (R + 4) * (R + 4)) {
               b.teleport(this.center().add(0.0, 2.0, 0.0));
            }

            this.bar.progress((float)Math.max(0.0, Math.min(1.0, b.getHealth() / b.getAttribute(Attribute.MAX_HEALTH).getValue())));
            this.bar.name(Text.c(this.bossName + " &8• &f" + Text.formatTime(left)));
            this.bar.color(BossBar.Color.RED);
            if (now >= this.nextAbility && !fight.isEmpty()) {
               this.nextAbility = now + 7000L + this.rnd.nextInt(4000);
               this.ability(b, fight);
            }
         }
      } else {
         double pr = Math.min(1.0, (double)this.kills / this.needed);
         this.bar.progress((float)pr);
         this.bar.name(Text.c("&5" + this.riftName() + " &8• &dRituel " + this.percent() + " % &8• &f" + Text.formatTime(left)));
         while (this.nextGuardian < this.guardians.size() && pr >= this.guardians.get(this.nextGuardian).at()) {
            this.spawnGuardian(this.guardians.get(this.nextGuardian++), Math.max(1, fight.size()));
         }

         if (pr >= 1.0) {
            this.spawnBoss();
         } else if (!fight.isEmpty()) {
            int max = this.aliveBase + this.alivePerPlayer * (fight.size() - 1);
            for (int i = 0; i < 2 && this.alive.size() < max; i++) {
               this.spawnMob(pr, fight.size());
            }
         }
      }

      for (Player p : in) {
         p.showBossBar(this.bar);
      }

      // le cœur de l'autel
      w.spawnParticle(Particle.DUST, this.center().add(0.0, 3.0, 0.0), 12, 0.3, 1.2, 0.3, 0.0, new Particle.DustOptions(Color.fromRGB(0xB06BFF), 1.4F));
      w.spawnParticle(Particle.END_ROD, this.center().add(0.0, 2.5 + this.rnd.nextDouble() * 4.0, 0.0), 2, 0.1, 0.5, 0.1, 0.01);
   }

   private int percent() {
      return (int)Math.min(100L, Math.round(100.0 * this.kills / Math.max(1, this.needed)));
   }

   private Location randomSpot() {
      double a = this.rnd.nextDouble() * Math.PI * 2, d = 9.0 + this.rnd.nextDouble() * 9.0;
      Location l = new Location(this.world(), Math.cos(a) * d + 0.5, Y + 1, Math.sin(a) * d + 0.5);
      for (int i = 0; i < 4 && !l.getBlock().isPassable(); i++) {
         l.add(0.0, 1.0, 0.0);
      }

      return l;
   }

   private double base(LivingEntity le, Attribute a, double def) {
      AttributeInstance i = le.getAttribute(a);
      return i == null ? def : i.getBaseValue();
   }

   private void attr(LivingEntity le, Attribute a, double v) {
      AttributeInstance i = le.getAttribute(a);
      if (i != null) {
         i.setBaseValue(v);
      }
   }

   /** hpMult = true : hp multiplie les PV de base de la créature. */
   private LivingEntity summon(Location loc, EntityType type, double hp, boolean hpMult, double dmg, String name) {
      World w = this.world();
      Entity e = w.spawn(loc, type.getEntityClass(), false, x -> {});
      if (!(e instanceof LivingEntity le)) {
         e.remove();
         return null;
      }

      if (hpMult) {
         hp *= this.base(le, Attribute.MAX_HEALTH, 20.0);
      }

      fr.windydev.clans.feature.BigHealth.apply(le, hp);
      this.attr(le, Attribute.FOLLOW_RANGE, 48.0);
      le.getPersistentDataContainer().set(this.keyMob, PersistentDataType.BYTE, (byte)1);
      le.getPersistentDataContainer().set(this.keyDmg, PersistentDataType.DOUBLE, dmg);
      le.customName(Text.c(name));
      le.setCustomNameVisible(true);
      le.setRemoveWhenFarAway(false);
      le.setPersistent(false);
      if (le instanceof Zombie z) {
         z.setAdult();
         z.setShouldBurnInDay(false);
      }

      if (le instanceof AbstractSkeleton sk) {
         sk.setShouldBurnInDay(false);
      }

      EntityEquipment eq = le.getEquipment();
      if (eq != null) {
         Material hand = switch (type) {
            case SKELETON, STRAY, BOGGED -> Material.BOW;
            case PILLAGER -> Material.CROSSBOW;
            case VINDICATOR -> Material.IRON_AXE;
            case WITHER_SKELETON -> Material.STONE_SWORD;
            case ZOMBIE, HUSK, DROWNED -> Material.IRON_SWORD;
            default -> null;
         };
         if (hand != null) {
            eq.setItemInMainHand(new ItemStack(hand));
            eq.setItemInMainHandDropChance(0.0F);
         }
      }

      w.spawnParticle(Particle.REVERSE_PORTAL, loc.clone().add(0.0, 1.0, 0.0), 40, 0.3, 0.8, 0.3, 0.05);
      w.playSound(loc, Sound.ENTITY_ENDERMAN_TELEPORT, 0.5F, 0.6F);
      this.alive.add(le.getUniqueId());
      return le;
   }

   private void spawnMob(double progress, int players) {
      List<MobSpec> ok = new ArrayList<>();
      for (MobSpec m : this.mobs) {
         if (progress >= m.from()) {
            ok.add(m);
         }
      }

      if (ok.isEmpty()) {
         ok.add(this.mobs.get(0));
      }

      MobSpec m = ok.get(this.rnd.nextInt(ok.size()));
      int lvl = this.level + (int)(progress * 3.0);
      double hp = (1.0 + this.hpPerLevel * (lvl - 1)) * (1.0 + this.hpPerPlayer * (players - 1)) * this.hpMult();
      this.summon(this.randomSpot(), m.type(), hp, true, 1.0 + this.dmgPerLevel * (lvl - 1), "&8[&7Niv. " + lvl + "&8] &f" + m.name());
   }

   private void spawnGuardian(Guardian g, int players) {
      LivingEntity le = this.summon(this.randomSpot(), g.type(), g.hp() * (1.0 + 0.5 * (players - 1)) * this.hpMult(), false, g.dmg(), "&6★ &e" + g.name());
      if (le != null) {
         le.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, -1, 0, false, false));
         for (Player p : this.inside()) {
            p.sendMessage(Text.c("&5✦ &7Un gardien sort de la faille : &e" + g.name() + "&7 !"));
            p.playSound(p.getLocation(), Sound.ENTITY_RAVAGER_ROAR, 0.7F, 0.8F);
         }
      }
   }

   private void spawnBoss() {
      int players = Math.max(1, this.fighters().size());
      this.bossMax = (this.bossHp + this.bossHpPerPlayer * (Math.max(players, this.peak) - 1)) * (this.giant ? this.gBossHp : 1.0);
      Location c = this.center().add(4.0, 0.0, 0.0);
      LivingEntity le = this.summon(c, this.bossType, this.bossMax, false, this.bossDamage, this.bossName);
      if (le == null) {
         return;
      }

      boolean first = this.boss == null;
      this.boss = le.getUniqueId();
      le.getPersistentDataContainer().set(this.keyBoss, PersistentDataType.BYTE, (byte)1);
      this.attr(le, Attribute.SCALE, 1.8);
      this.attr(le, Attribute.KNOCKBACK_RESISTANCE, 0.8);
      le.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, -1, 0, false, false));
      le.setGlowing(true);
      this.nextAbility = System.currentTimeMillis() + 6000L;
      if (first) {
         World w = this.world();
         w.strikeLightningEffect(c);
         w.spawnParticle(Particle.EXPLOSION_EMITTER, c, 2);
         for (Player p : this.inside()) {
            p.showTitle(Title.title(Text.c(this.bossName), Text.c("&7Le rituel est chargé. &dAbattez-le !")));
            p.playSound(p.getLocation(), Sound.ENTITY_WITHER_SPAWN, 0.8F, 0.8F);
         }
      }
   }

   /** Pouvoirs de l'Avatar : onde de choc, pluie d'éclats sur chaque joueur, ou renforts. */
   private void ability(LivingEntity b, List<Player> targets) {
      World w = b.getWorld();
      Particle.DustOptions warn = new Particle.DustOptions(Color.fromRGB(0xFF3B6B), 1.3F);
      switch (this.rnd.nextInt(3)) {
         case 0 -> {
            Location c = b.getLocation();
            w.playSound(c, Sound.ENTITY_WARDEN_SONIC_CHARGE, 1.5F, 1.0F);
            for (int t = 0; t < 30; t += 5) {
               Bukkit.getScheduler().runTaskLater(this.plugin, () -> ring(w, c.clone().add(0.0, 0.2, 0.0), 7.0, 48, warn), t);
            }

            Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
               if (!this.running || b.isDead()) {
                  return;
               }

               w.playSound(c, Sound.ENTITY_GENERIC_EXPLODE, 1.2F, 0.6F);
               w.spawnParticle(Particle.SONIC_BOOM, c.clone().add(0.0, 1.0, 0.0), 3, 2.0, 0.3, 2.0, 0.0);
               for (Player p : this.fighters()) {
                  if (p.getLocation().distanceSquared(c) <= 49.0) {
                     p.damage(9.0, b);
                     Vector k = p.getLocation().toVector().subtract(c.toVector()).setY(0.0);
                     p.setVelocity((k.lengthSquared() < 0.01 ? new Vector(1, 0, 0) : k.normalize()).multiply(1.3).setY(0.6));
                  }
               }
            }, 32L);
         }
         case 1 -> {
            w.playSound(b.getLocation(), Sound.ENTITY_EVOKER_PREPARE_ATTACK, 1.5F, 0.7F);
            for (Player p : targets) {
               Location at = p.getLocation();
               for (int t = 0; t < 30; t += 5) {
                  Bukkit.getScheduler().runTaskLater(this.plugin, () -> ring(w, at.clone().add(0.0, 0.2, 0.0), 3.0, 24, warn), t);
               }

               Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
                  if (!this.running) {
                     return;
                  }

                  w.spawnParticle(Particle.EXPLOSION, at.clone().add(0.0, 0.5, 0.0), 4, 1.0, 0.3, 1.0, 0.0);
                  w.spawnParticle(Particle.DRAGON_BREATH, at.clone().add(0.0, 0.3, 0.0), 60, 1.2, 0.2, 1.2, 0.02);
                  w.playSound(at, Sound.ENTITY_DRAGON_FIREBALL_EXPLODE, 1.0F, 1.2F);
                  for (Player q : this.fighters()) {
                     if (q.getLocation().distanceSquared(at) <= 9.0) {
                        q.damage(8.0, b);
                     }
                  }
               }, 32L);
            }
         }
         default -> {
            w.playSound(b.getLocation(), Sound.ENTITY_EVOKER_PREPARE_SUMMON, 1.5F, 0.8F);
            int n = 2 + Math.min(4, targets.size());
            for (int i = 0; i < n && this.alive.size() < 20; i++) {
               this.spawnMob(1.0, targets.size());
            }
         }
      }
   }

   private static void ring(World w, Location c, double r, int n, Particle.DustOptions d) {
      for (int i = 0; i < n; i++) {
         double a = Math.PI * 2 * i / n;
         w.spawnParticle(Particle.DUST, c.getX() + Math.cos(a) * r, c.getY(), c.getZ() + Math.sin(a) * r, 1, 0.0, 0.0, 0.0, 0.0, d);
      }
   }

   private void clearMobs() {
      World w = this.world();
      for (Entity e : w.getEntities()) {
         if (!(e instanceof Player)) {
            e.remove();
         }
      }

      this.alive.clear();
   }

   private void victory() {
      List<Player> in = this.inside();
      List<String> names = new ArrayList<>();
      for (Player p : in) {
         double d = this.damage.getOrDefault(p.getUniqueId(), 0.0);
         if (d < this.minDamage) {
            p.sendMessage(Text.c("&5✦ &7Rituel réussi, mais tu n'as pas assez combattu pour recevoir un fragment &8(" + (int)d + "/" + (int)this.minDamage + " dégâts)&7."));
            continue;
         }

         names.add(p.getName());
         int times = this.giant ? this.gRewards : 1;
         this.plugin.profiles().addXp(p, this.xp * times, "combat");
         this.plugin.profiles().addSouls(p, this.souls * times, true);
         for (int t = 0; t < times; t++) {
            for (String spec : this.rewards) {
               ItemStack it = this.item(spec);
               if (it != null) {
                  for (ItemStack rest : p.getInventory().addItem(it).values()) {
                     p.getWorld().dropItemNaturally(p.getLocation(), rest);
                  }
               }
            }
         }

         if (this.giant) {
            p.sendMessage(Text.c("&5✦ &dFaille géante &7: récompenses x" + times + " !"));
         }

         this.plugin.magic().grantFragment(p);
         this.plugin.quests().progress(p, fr.windydev.clans.quest.QuestManager.Kind.RIFT, "", 1);
         if (this.giant) {
            this.plugin.weeklyEvent().participated(p);
         }
         p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0F, 1.0F);
      }

      String who = names.isEmpty() ? "" : " &7Bravo à &f" + String.join("&7, &f", names) + "&7.";
      Bukkit.broadcast(Text.c("&5✦ &dL'Avatar de la " + (this.giant ? "Faille géante" : "faille") + " est tombé : le rituel est accompli !" + who));
      if (!names.isEmpty()) {
         this.plugin.bot().announce(this.giant ? "event" : "rift", "✨ **Rituel accompli !** L'Avatar de la " + (this.giant ? "Faille géante" : "faille") + " a été vaincu par " + String.join(", ", names) + ".");
      }

      this.end(true, null, true);
   }

   /** "windy:id:quantité" ou "MATÉRIAU:quantité", avec "@0.25" à la fin pour une chance. */
   private ItemStack item(String spec) {
      String s = spec.trim();
      int at = s.lastIndexOf('@');
      if (at > 0) {
         try {
            if (this.rnd.nextDouble() >= Double.parseDouble(s.substring(at + 1))) {
               return null;
            }
         } catch (NumberFormatException ignored) {
         }

         s = s.substring(0, at);
      }

      String[] p = s.split(":");
      try {
         if (p[0].equalsIgnoreCase("windy") && p.length >= 2) {
            ItemStack it = this.plugin.items().create(p[1]);
            if (it != null && p.length >= 3) {
               it.setAmount(Math.max(1, Integer.parseInt(p[2])));
            }

            return it;
         }

         Material m = Material.matchMaterial(p[0]);
         return m == null ? null : new ItemStack(m, p.length >= 2 ? Math.max(1, Integer.parseInt(p[1])) : 1);
      } catch (NumberFormatException e) {
         return null;
      }
   }

   private void end(boolean win, String reason, boolean reschedule) {
      if (!this.running) {
         return;
      }

      this.running = false;
      this.boss = null;
      if (!win) {
         Bukkit.broadcast(Text.c("&5✦ &7La " + this.riftName() + " s'est refermée : &c" + reason + "&7."));
      }

      if (this.giant) {
         this.giant = false;
         this.plugin.weeklyEvent().ended("faille");
      }

      List<Player> in = this.inside();
      for (Player p : in) {
         if (this.bar != null) {
            p.hideBossBar(this.bar);
         }
      }

      this.clearMobs();
      if (reschedule) {
         this.scheduleNext();
         // on laisse 15 s pour souffler avant le retour
         Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
            if (!this.running) {
               this.inside().forEach(this::sendBack);
            }
         }, win ? 300L : 100L);
      } else {
         in.forEach(this::sendBack);
      }
   }

   private void sendBack(Player p) {
      Location back = this.returns.remove(p.getUniqueId());
      if (back == null || back.getWorld() == null) {
         back = Bukkit.getWorlds().get(0).getSpawnLocation();
      }

      p.teleport(back);
      if (this.bar != null) {
         p.hideBossBar(this.bar);
      }
   }

   // ------------------------------------------------------------------ entrer, sortir

   public void join(Player p) {
      if (!this.running) {
         long left = this.nextAt - System.currentTimeMillis();
         p.sendMessage(Text.c("&5✦ &7Aucune faille n'est ouverte. " + (this.enabled
            ? (left > 0L ? "&7Prochaine dans environ &d" + Text.formatTime(left / 1000L) + "&7." : "&7Elle s'ouvrira dès qu'il y aura &d" + this.minPlayers + " joueurs&7.")
            : "")));
         return;
      }

      if (this.isRift(p.getWorld())) {
         p.sendMessage(Text.c("&7Tu es déjà dans la faille. &8(/faille quitter)"));
         return;
      }

      if (this.plugin.dungeons().isDungeon(p.getWorld()) || this.plugin.bosses().isArena(p.getWorld())) {
         p.sendMessage(Text.c("&cTermine d'abord ton donjon ou ton combat de boss."));
         return;
      }

      this.returns.put(p.getUniqueId(), p.getLocation());
      p.teleport(this.spawn());
      p.playSound(p.getLocation(), Sound.BLOCK_PORTAL_TRAVEL, 0.3F, 1.4F);
      int have = this.plugin.magic().fragments(p.getUniqueId()), need = this.plugin.magic().fragmentsNeeded();
      p.sendMessage(Text.c("&5✦ &dFaille arcanique &7: tue les créatures pour charger le rituel, puis abats l'Avatar."));
      p.sendMessage(Text.c("&7Inflige au moins &f" + (int)this.minDamage + " dégâts &7pour gagner un &dFragment d'éveil"
         + (this.plugin.magic().innate(p.getUniqueId()) ? " &8(tu as déjà l'incantation innée)" : " &8(" + have + "/" + need + ")") + "&7. &8/faille quitter pour sortir."));
   }

   public void leave(Player p) {
      if (this.isRift(p.getWorld())) {
         this.sendBack(p);
      } else {
         p.sendMessage(Text.c("&7Tu n'es pas dans la faille."));
      }
   }

   // ------------------------------------------------------------------ évènements

   private boolean isMob(Entity e) {
      return e != null && e.getPersistentDataContainer().has(this.keyMob, PersistentDataType.BYTE);
   }

   public boolean isBoss(Entity e) {
      return e != null && e.getPersistentDataContainer().has(this.keyBoss, PersistentDataType.BYTE);
   }

   @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
   public void onDamage(EntityDamageByEntityEvent e) {
      Entity src = e.getDamager();
      if (src instanceof Projectile pr && pr.getShooter() instanceof Entity sh) {
         src = sh;
      }

      if (this.isMob(src) && e.getEntity() instanceof Player) {
         e.setDamage(e.getDamage() * src.getPersistentDataContainer().getOrDefault(this.keyDmg, PersistentDataType.DOUBLE, 1.0));
      } else if (this.isMob(src) && this.isMob(e.getEntity())) {
         e.setCancelled(true); // pas de tirs entre créatures de la faille
      } else if (src instanceof Player p && this.isMob(e.getEntity()) && e.getEntity() instanceof LivingEntity le) {
         this.damage.merge(p.getUniqueId(), Math.min(e.getFinalDamage(), le.getHealth()), Double::sum);
      } else if (src instanceof Player && e.getEntity() instanceof Player && this.isRift(e.getEntity().getWorld())) {
         e.setCancelled(true); // pas de PvP dans la faille
      }
   }

   @EventHandler
   public void onMobDeath(EntityDeathEvent e) {
      LivingEntity dead = e.getEntity();
      if (!this.isMob(dead)) {
         return;
      }

      e.getDrops().clear();
      this.alive.remove(dead.getUniqueId());
      if (!this.running) {
         return;
      }

      if (dead.getUniqueId().equals(this.boss)) {
         e.setDroppedExp(0);
         this.finishing = true;
         dead.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, dead.getLocation().add(0.0, 1.5, 0.0), 150, 1.2, 1.5, 1.2, 0.5);
         Bukkit.getScheduler().runTask(this.plugin, this::victory);
      } else if (this.boss == null) {
         this.kills++;
         Player k = dead.getKiller();
         if (k != null) {
            this.plugin.profiles().addXp(k, 8L * this.level, "combat");
            this.plugin.profiles().addSouls(k, Math.max(1L, this.level / 2L), true);
         }
      }
   }

   @EventHandler(ignoreCancelled = true)
   public void onSpawn(CreatureSpawnEvent e) {
      if (this.isRift(e.getLocation().getWorld()) && e.getSpawnReason() != CreatureSpawnEvent.SpawnReason.CUSTOM) {
         if (this.running && e.getSpawnReason() == CreatureSpawnEvent.SpawnReason.SPELL) {
            // vex d'un évocateur : compté comme créature de la faille
            e.getEntity().getPersistentDataContainer().set(this.keyMob, PersistentDataType.BYTE, (byte)1);
            e.getEntity().setPersistent(false);
         } else {
            e.setCancelled(true);
         }
      }
   }

   @EventHandler
   public void onPlayerDeath(PlayerDeathEvent e) {
      if (this.isRift(e.getPlayer().getWorld())) {
         e.setKeepInventory(true);
         e.getDrops().clear();
         e.setKeepLevel(true);
         e.setDroppedExp(0);
      }
   }

   @EventHandler(priority = EventPriority.HIGH)
   public void onRespawn(PlayerRespawnEvent e) {
      Player p = e.getPlayer();
      if (this.isRift(p.getWorld())) {
         if (this.running) {
            e.setRespawnLocation(this.spawn());
            // 5 s de protection : les créatures attendent souvent au point de retour
            Bukkit.getScheduler().runTask(this.plugin, () -> p.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 100, 4, false, false, true)));
         } else {
            Location back = this.returns.remove(p.getUniqueId());
            e.setRespawnLocation(back != null && back.getWorld() != null ? back : Bukkit.getWorlds().get(0).getSpawnLocation());
         }
      } else if (this.isRift(e.getRespawnLocation().getWorld()) && !this.running) {
         e.setRespawnLocation(Bukkit.getWorlds().get(0).getSpawnLocation());
      }
   }

   @EventHandler
   public void onJoin(PlayerJoinEvent e) {
      Player p = e.getPlayer();
      if (this.isRift(p.getWorld()) && !this.returns.containsKey(p.getUniqueId())) {
         // reconnecté dans la faille après un redémarrage
         Bukkit.getScheduler().runTask(this.plugin, () -> p.teleport(Bukkit.getWorlds().get(0).getSpawnLocation()));
      }
   }

   @EventHandler
   public void onQuit(PlayerQuitEvent e) {
      Player p = e.getPlayer();
      if (this.isRift(p.getWorld())) {
         this.sendBack(p);
      }
   }

   @EventHandler(ignoreCancelled = true)
   public void onBreak(BlockBreakEvent e) {
      if (this.isRift(e.getBlock().getWorld()) && !e.getPlayer().hasPermission("windyclans.admin")) {
         e.setCancelled(true);
      }
   }

   @EventHandler(ignoreCancelled = true)
   public void onPlace(BlockPlaceEvent e) {
      if (this.isRift(e.getBlock().getWorld()) && !e.getPlayer().hasPermission("windyclans.admin")) {
         e.setCancelled(true);
      }
   }

   @EventHandler(ignoreCancelled = true)
   public void onBucket(PlayerBucketEmptyEvent e) {
      if (this.isRift(e.getBlock().getWorld())) {
         e.setCancelled(true);
      }
   }

   @EventHandler(ignoreCancelled = true)
   public void onExplode(EntityExplodeEvent e) {
      if (this.isRift(e.getLocation().getWorld())) {
         e.blockList().clear();
      }
   }

   // ------------------------------------------------------------------ commandes

   public boolean onCommand(@NotNull CommandSender s, @NotNull Command cmd, @NotNull String label, @NotNull String[] args) {
      if (s instanceof Player p) {
         if (args.length > 0 && (args[0].equalsIgnoreCase("quitter") || args[0].equalsIgnoreCase("leave"))) {
            this.leave(p);
         } else {
            this.join(p);
         }
      }

      return true;
   }

   public List<String> onTabComplete(@NotNull CommandSender s, @NotNull Command c, @NotNull String l, @NotNull String[] args) {
      return args.length == 1 ? List.of("quitter") : List.of();
   }

   /** /clanadmin faille <ouvrir|fermer|info> */
   public void admin(CommandSender s, String[] args) {
      String act = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "info";
      switch (act) {
         case "ouvrir", "start", "open" -> this.plugin.msg(s, this.open(s) ? "&aFaille ouverte." : "&cUne faille est déjà ouverte.");
         case "fermer", "stop", "close" -> {
            if (this.running) {
               this.end(false, "un administrateur l'a fermée", true);
            } else {
               this.plugin.msg(s, "&cAucune faille ouverte.");
            }
         }
         case "rituel" -> {
            // test : remplit le rituel pour faire venir l'Avatar
            if (this.running && this.boss == null) {
               this.kills = this.needed;
               this.plugin.msg(s, "&aRituel chargé.");
            }
         }
         default -> {
            long left = this.nextAt - System.currentTimeMillis();
            this.plugin.msg(s, this.running
               ? "&dFaille ouverte &7: rituel " + this.percent() + " % (" + this.kills + "/" + this.needed + "), " + this.inside().size() + " joueur(s), "
                  + Text.formatTime((this.endsAt - System.currentTimeMillis()) / 1000L) + " restantes."
               : "&7Prochaine faille : " + (left > 0L ? "dans " + Text.formatTime(left / 1000L) : "dès que " + this.minPlayers + " joueurs sont connectés")
                  + (this.enabled ? "" : " &c(désactivée)"));
            this.plugin.msg(s, "&7/clanadmin faille <ouvrir|fermer|info>");
         }
      }
   }

   static final class VoidGenerator extends ChunkGenerator {
      @Override
      public Location getFixedSpawnLocation(World world, Random random) {
         return new Location(world, 0.5, Y + 1, 0.5);
      }
   }
}
