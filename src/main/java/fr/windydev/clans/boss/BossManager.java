package fr.windydev.clans.boss;

import fr.windydev.clans.WindyClans;
import fr.windydev.clans.data.Settings;
import fr.windydev.clans.model.Clan;
import fr.windydev.clans.model.Rank;
import fr.windydev.clans.util.Text;
import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.Map.Entry;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.bossbar.BossBar.Color;
import net.kyori.adventure.bossbar.BossBar.Overlay;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.title.Title;
import net.kyori.adventure.title.Title.Times;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.World.Environment;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.AbstractSkeleton;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.SmallFireball;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
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

public class BossManager implements Listener {
   public static final String ARENA_WORLD = "windy_arenes";
   private final WindyClans plugin;
   private final NamespacedKey key;
   private final Map<Integer, BossManager.BossDef> bosses = new TreeMap<>();
   private final Map<UUID, BossManager.Fight> fights = new HashMap<>();
   private final Map<UUID, Location> pendingRespawn = new HashMap<>();
   private final Set<Integer> builtSlots = new HashSet<>();
   private Rank rank = Rank.OFFICIER;
   private int lobbySeconds = 20;
   private int timeLimit = 600;
   private int retryCooldown = 300;
   private int winCooldown = 1800;
   private double groupScaling = 0.5;
   private World arena;

   public BossManager(WindyClans plugin) {
      this.plugin = plugin;
      this.key = new NamespacedKey(plugin, "boss");
   }

   public void load() {
      File f = new File(this.plugin.getDataFolder(), "bosses.yml");
      if (!f.exists()) {
         this.plugin.saveResource("bosses.yml", false);
      }

      YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
      if (y.getInt("config-version", 1) < 3) {
         File old = new File(this.plugin.getDataFolder(), "bosses-ancien.yml");
         if (old.exists()) {
            old.delete();
         }

         f.renameTo(old);
         this.plugin.saveResource("bosses.yml", true);
         y = YamlConfiguration.loadConfiguration(f);
         this.plugin.getLogger().info("bosses.yml mis à jour (équilibrage + glyphes runiques). Ancienne version : bosses-ancien.yml");
      }

      this.rank = Rank.parse(y.getString("settings.rank"), Rank.OFFICIER);
      this.lobbySeconds = y.getInt("settings.lobby-seconds", 20);
      this.timeLimit = y.getInt("settings.time-limit", 600);
      this.retryCooldown = y.getInt("settings.retry-cooldown", 300);
      this.winCooldown = y.getInt("settings.win-cooldown", 1800);
      this.groupScaling = Math.max(0.0, y.getDouble("settings.group-scaling", 0.5));
      this.bosses.clear();
      ConfigurationSection bs = y.getConfigurationSection("bosses");
      if (bs != null) {
         for (String k : bs.getKeys(false)) {
            try {
               ConfigurationSection s = bs.getConfigurationSection(k);
               int lvl = Integer.parseInt(k);
               EntityType type = EntityType.valueOf(s.getString("type", "ZOMBIE").toUpperCase(Locale.ROOT));
               Map<String, String> equip = new HashMap<>();
               ConfigurationSection es = s.getConfigurationSection("equipment");
               if (es != null) {
                  for (String slot : es.getKeys(false)) {
                     equip.put(slot, es.getString(slot));
                  }
               }

               List<BossManager.Ability> abilities = new ArrayList<>();

               for (Map<?, ?> m : s.getMapList("abilities")) {
                  abilities.add(
                     new BossManager.Ability(
                        String.valueOf(m.get("type")),
                        m.get("cooldown") == null ? 10 : ((Number)m.get("cooldown")).intValue(),
                        m.get("mob") == null ? "ZOMBIE" : String.valueOf(m.get("mob")),
                        m.get("count") == null ? 2 : ((Number)m.get("count")).intValue()
                     )
                  );
               }

               this.bosses
                  .put(
                     lvl,
                     new BossManager.BossDef(
                        lvl,
                        s.getString("name", "Boss " + lvl),
                        type,
                        s.getDouble("health", 60.0),
                        s.getDouble("damage", 5.0),
                        s.getDouble("speed", 0.25),
                        s.getDouble("scale", 1.0),
                        s.getStringList("effects"),
                        equip,
                        abilities,
                        s.getStringList("rewards.items"),
                        s.getInt("rewards.clan-xp", 100),
                        s.getString("rewards.rune"),
                        s.getDouble("rewards.rune-chance", 50.0) / 100.0
                     )
                  );
            } catch (Exception var14) {
               this.plugin.getLogger().warning("bosses.yml : boss '" + k + "' invalide : " + var14.getMessage());
            }
         }
      }

      this.plugin.getLogger().info(this.bosses.size() + " boss chargé(s).");
   }

   public Collection<BossManager.BossDef> bosses() {
      return this.bosses.values();
   }

   public Rank rank() {
      return this.rank;
   }

   public void start() {
      Bukkit.getScheduler().runTaskTimer(this.plugin, this::tick, 20L, 20L);
      Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
         World w = this.arenaWorld();
         w.setKeepSpawnInMemory(false);

         for (int s = 0; s < 2; s++) {
            this.build(s);
         }
      }, 40L);
   }

   public void reset(Clan c, int level) {
      if (level <= 0) {
         c.bossCooldowns().clear();
      } else {
         c.bossCooldowns().remove(level);
      }

      c.bossRetryAt(0L);
      this.plugin.clans().dirty();
   }

   private World arenaWorld() {
      if (this.arena != null) {
         return this.arena;
      } else {
         this.arena = Bukkit.getWorld("windy_arenes");
         if (this.arena == null) {
            this.arena = new WorldCreator("windy_arenes")
               .generator(new BossManager.VoidGenerator())
               .environment(Environment.NORMAL)
               .generateStructures(false)
               .createWorld();
         }

         this.arena.setGameRule(GameRule.KEEP_INVENTORY, true);
         this.arena.setGameRule(GameRule.DO_MOB_SPAWNING, false);
         this.arena.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
         this.arena.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
         this.arena.setGameRule(GameRule.DO_FIRE_TICK, false);
         this.arena.setGameRule(GameRule.MOB_GRIEFING, false);
         this.arena.setGameRule(GameRule.ANNOUNCE_ADVANCEMENTS, false);
         this.arena.setTime(18000L);
         this.arena.setDifficulty(Difficulty.HARD);
         return this.arena;
      }
   }

   public boolean isArena(World w) {
      return w != null && w.getName().equals("windy_arenes");
   }

   private Location slotCenter(int slot) {
      return new Location(this.arenaWorld(), slot * 1000 + 0.5, 101.0, 0.5);
   }

   private void build(int slot) {
      World w = this.arenaWorld();
      int cx = slot * 1000;
      int cy = 100;
      int cz = 0;
      if (!this.builtSlots.contains(slot) || w.getBlockAt(cx, cy, cz).getType() != Material.CRYING_OBSIDIAN) {
         int r = 16;

         for (int x = -r - 1; x <= r + 1; x++) {
            for (int z = -r - 1; z <= r + 1; z++) {
               double d = Math.sqrt(x * x + z * z);
               if (!(d > r + 1.5)) {
                  Block floor = w.getBlockAt(cx + x, cy, cz + z);
                  if (!(d <= r)) {
                     for (int h = 0; h <= 7; h++) {
                        w.getBlockAt(cx + x, cy + h, cz + z).setType(h == 7 ? Material.POLISHED_BLACKSTONE : Material.BLACKSTONE, false);
                     }
                  } else {
                     Material m = Material.POLISHED_BLACKSTONE_BRICKS;
                     if (d >= 9.5 && d < 10.5) {
                        m = Material.CRYING_OBSIDIAN;
                     } else if (x % 5 == 0 && z % 5 == 0 && d < 15.0) {
                        m = Material.SHROOMLIGHT;
                     } else if (d < 2.0) {
                        m = Material.CRYING_OBSIDIAN;
                     }

                     floor.setType(m, false);

                     for (int h = 1; h <= 12; h++) {
                        w.getBlockAt(cx + x, cy + h, cz + z).setType(Material.AIR, false);
                     }
                  }
               }
            }
         }

         for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4.0;
            int px = (int)Math.round(Math.cos(a) * 14.0);
            int pz = (int)Math.round(Math.sin(a) * 14.0);

            for (int h = 1; h <= 5; h++) {
               w.getBlockAt(cx + px, cy + h, cz + pz).setType(Material.PURPUR_PILLAR, false);
            }

            w.getBlockAt(cx + px, cy + 6, cz + pz).setType(Material.AMETHYST_BLOCK, false);
            w.getBlockAt(cx + px, cy + 7, cz + pz).setType(Material.SOUL_LANTERN, false);
         }

         w.getBlockAt(cx, cy, cz).setType(Material.CRYING_OBSIDIAN, false);
         this.builtSlots.add(slot);
      }
   }

   private int freeSlot() {
      Set<Integer> used = new HashSet<>();

      for (BossManager.Fight f : this.fights.values()) {
         used.add(f.slot);
      }

      int s = 0;

      while (used.contains(s)) {
         s++;
      }

      return s;
   }

   public BossManager.Fight fightOf(Clan c) {
      return c == null ? null : this.fights.get(c.id());
   }

   public boolean lobbyOpen(Clan c) {
      BossManager.Fight f = this.fightOf(c);
      return f != null && f.state == BossManager.State.LOBBY;
   }

   public String status(Clan c, BossManager.BossDef d) {
      if (c.level() < d.level()) {
         return "locked";
      } else {
         return this.cooldownLeft(c, d.level()) > 0L ? "cooldown" : "available";
      }
   }

   public long cooldownLeft(Clan c, int level) {
      Long t = c.bossCooldowns().get(level);
      return t == null ? 0L : Math.max(0L, (t - System.currentTimeMillis()) / 1000L);
   }

   public int winCooldown() {
      return this.winCooldown;
   }

   public void launch(Player p, int level) {
      Clan c = this.plugin.clans().of(p);
      if (c == null) {
         this.plugin.msg(p, "&cIl faut un clan.");
      } else {
         BossManager.BossDef d = this.bosses.get(level);
         if (d != null) {
            if (!c.rank(p.getUniqueId()).atLeast(this.rank)) {
               this.plugin.msg(p, "&cIl faut être au moins " + this.rank.colored() + "&c.");
            } else if (c.level() < level) {
               this.plugin.msg(p, "&cTon clan doit être niveau " + level + ".");
            } else {
               long cd = this.cooldownLeft(c, level);
               if (cd > 0L) {
                  this.plugin.msg(p, "&cCe boss se reforme : disponible dans &f" + Text.formatTime(cd) + "&c.");
               } else if (this.fights.containsKey(c.id())) {
                  this.plugin.msg(p, "&cUn combat de boss est déjà en cours pour ton clan.");
               } else {
                  long wait = c.bossRetryAt() - System.currentTimeMillis();
                  if (wait > 0L) {
                     this.plugin.msg(p, "&cVous pourrez retenter dans &f" + Text.formatTime(wait / 1000L) + "&c.");
                  } else {
                     BossManager.Fight f = new BossManager.Fight(c, d);
                     f.participants.add(p.getUniqueId());
                     this.fights.put(c.id(), f);

                     for (Player m : this.plugin.clans().onlineMembers(c)) {
                        m.sendMessage(
                           Text.c(
                                 this.plugin.settings().prefix
                                    + "&5☠ &f"
                                    + p.getName()
                                    + " &7lance le combat contre "
                                    + d.name()
                                    + " &7dans &f"
                                    + this.lobbySeconds
                                    + " s&7. "
                              )
                              .append(
                                 Text.c("&a&l[REJOINDRE]")
                                    .clickEvent(ClickEvent.runCommand("/clan boss rejoindre"))
                                    .hoverEvent(HoverEvent.showText(Text.c("&aRejoindre le combat")))
                              )
                        );
                        m.playSound(m.getLocation(), Sound.EVENT_RAID_HORN, 0.8F, 1.2F);
                     }

                     this.plugin
                        .bot()
                        .staffLog("☠ Boss", c.name() + " lance le boss niv." + level + " (" + Text.strip(d.name()) + ") — lancé par " + p.getName());
                  }
               }
            }
         }
      }
   }

   public void join(Player p) {
      Clan c = this.plugin.clans().of(p);
      BossManager.Fight f = this.fightOf(c);
      if (f != null && f.state == BossManager.State.LOBBY) {
         if (f.participants.add(p.getUniqueId())) {
            this.plugin.clans().broadcast(c, "&a" + p.getName() + " &7rejoint le combat (" + f.participants.size() + " participant(s)).");
         } else {
            this.plugin.msg(p, "&7Tu es déjà inscrit.");
         }
      } else {
         this.plugin.msg(p, "&cAucun combat de boss à rejoindre.");
      }
   }

   private void tick() {
      long now = System.currentTimeMillis();

      for (BossManager.Fight f : new ArrayList<>(this.fights.values())) {
         try {
            if (f.state == BossManager.State.LOBBY && now >= f.lobbyEnd) {
               this.begin(f);
            } else if (f.state == BossManager.State.FIGHT) {
               this.fightTick(f, now);
            }
         } catch (Exception var6) {
            this.plugin.getLogger().warning("Erreur boss : " + var6);
            var6.printStackTrace();
            this.end(f, false, "erreur");
         }
      }
   }

   private void begin(BossManager.Fight f) {
      List<Player> players = new ArrayList<>();

      for (UUID u : f.participants) {
         Player p = Bukkit.getPlayer(u);
         if (p != null && p.isOnline() && !p.isDead()) {
            players.add(p);
         }
      }

      if (players.isEmpty()) {
         this.fights.remove(f.clan.id());
      } else {
         f.slot = this.freeSlot();
         this.build(f.slot);
         f.center = this.slotCenter(f.slot);
         f.state = BossManager.State.FIGHT;
         f.fightEnd = System.currentTimeMillis() + this.timeLimit * 1000L;
         int i = 0;

         for (Player p : players) {
            double a = (Math.PI * 2) * i++ / players.size();
            Location l = f.center.clone().add(Math.cos(a) * 11.0, 0.0, Math.sin(a) * 11.0);
            l.setDirection(f.center.toVector().subtract(l.toVector()));
            f.returns.put(p.getUniqueId(), p.getLocation());
            f.alive.add(p.getUniqueId());
            p.teleport(l);
            p.showTitle(
               Title.title(
                  Text.c(f.def.name()),
                  Text.c("&7Boss de niveau " + f.def.level()),
                  Times.times(Duration.ofMillis(300L), Duration.ofSeconds(3L), Duration.ofMillis(600L))
               )
            );
            p.playSound(p.getLocation(), Sound.ENTITY_WITHER_SPAWN, 0.8F, 1.0F);
         }

         Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
            if (f.state == BossManager.State.FIGHT) {
               this.spawnBoss(f);
            }
         }, 60L);
      }
   }

   private void spawnBoss(BossManager.Fight f) {
      BossManager.BossDef d = f.def;
      World w = f.center.getWorld();
      w.strikeLightningEffect(f.center);
      w.spawnParticle(Particle.PORTAL, f.center, 200, 1.0, 2.0, 1.0, 1.0);
      Entity e = w.spawnEntity(f.center, d.type());
      if (!(e instanceof LivingEntity boss)) {
         e.remove();
         this.end(f, false, "type de boss invalide");
      } else {
         f.boss = boss;
         boss.customName(Text.c(d.name()));
         boss.setCustomNameVisible(true);
         boss.setRemoveWhenFarAway(false);
         boss.setPersistent(true);
         boss.getPersistentDataContainer().set(this.key, PersistentDataType.STRING, f.clan.id().toString());
         int n = Math.max(1, f.alive.size());
         double hp = d.health() * (1.0 + this.groupScaling * (n - 1));
         this.attr(boss, Attribute.GENERIC_MAX_HEALTH, hp);
         boss.setHealth(hp);
         if (n > 1) {
            for (UUID u : f.alive) {
               Player pl = Bukkit.getPlayer(u);
               if (pl != null) {
                  pl.sendActionBar(Text.c("&7Boss renforcé pour &f" + n + " &7joueurs &8(&c" + (int)hp + " PV&8)"));
               }
            }
         }
         this.attr(boss, Attribute.GENERIC_ATTACK_DAMAGE, d.damage());
         this.attr(boss, Attribute.GENERIC_MOVEMENT_SPEED, d.speed());
         this.attr(boss, Attribute.GENERIC_KNOCKBACK_RESISTANCE, 0.7);
         this.attr(boss, Attribute.GENERIC_FOLLOW_RANGE, 48.0);
         this.attr(boss, Attribute.GENERIC_SCALE, d.scale());

         for (String spec : d.effects()) {
            Entry<PotionEffectType, Integer> eff = Settings.parseEffect(spec);
            if (eff != null) {
               boss.addPotionEffect(new PotionEffect(eff.getKey(), -1, eff.getValue(), false, true));
            }
         }

         EntityEquipment eq = boss.getEquipment();
         if (eq != null) {
            d.equipment().forEach((slot, matName) -> {
               Material m = Material.matchMaterial(matName);
               if (m != null) {
                  ItemStack it = new ItemStack(m);
                  switch (slot) {
                     case "hand":
                        eq.setItemInMainHand(it);
                        eq.setItemInMainHandDropChance(0.0F);
                        break;
                     case "helmet":
                        eq.setHelmet(it);
                        eq.setHelmetDropChance(0.0F);
                        break;
                     case "chestplate":
                        eq.setChestplate(it);
                        eq.setChestplateDropChance(0.0F);
                        break;
                     case "leggings":
                        eq.setLeggings(it);
                        eq.setLeggingsDropChance(0.0F);
                        break;
                     case "boots":
                        eq.setBoots(it);
                        eq.setBootsDropChance(0.0F);
                  }
               }
            });
         }

         if (boss instanceof Zombie z) {
            z.setShouldBurnInDay(false);
         }

         if (boss instanceof AbstractSkeleton sk) {
            sk.setShouldBurnInDay(false);
         }

         f.bar = BossBar.bossBar(Text.c(d.name() + " &8- &7niveau " + d.level()), 1.0F, Color.PURPLE, Overlay.NOTCHED_10);

         for (UUID u : f.alive) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) {
               p.showBossBar(f.bar);
            }
         }

         for (BossManager.Ability a : d.abilities()) {
            f.nextUse.put(a.type() + a.mob(), System.currentTimeMillis() + a.cooldown() * 1000L);
         }
      }
   }

   private void attr(LivingEntity e, Attribute a, double v) {
      AttributeInstance i = e.getAttribute(a);
      if (i != null) {
         i.setBaseValue(v);
      }
   }

   private void fightTick(BossManager.Fight f, long now) {
      f.alive.removeIf(u -> {
         Player p = Bukkit.getPlayer(u);
         return p == null || !p.isOnline() || p.isDead() || !this.isArena(p.getWorld());
      });
      if (f.alive.isEmpty()) {
         this.end(f, false, "tous les participants sont tombés");
      } else if (now > f.fightEnd) {
         this.end(f, false, "temps écoulé");
      } else if (f.boss != null) {
         if (f.boss.isValid()) {
            LivingEntity boss = f.boss;
            if (boss.getLocation().distanceSquared(f.center) > 225.0) {
               boss.teleport(f.center);
            }

            double max = boss.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue();
            if (f.bar != null) {
               f.bar.progress((float)Math.max(0.0, Math.min(1.0, boss.getHealth() / max)));
            }

            List<Player> targets = new ArrayList<>();

            for (UUID u : f.alive) {
               Player p = Bukkit.getPlayer(u);
               if (p != null) {
                  targets.add(p);
               }
            }

            Player target = targets.stream().min(Comparator.comparingDouble(p -> p.getLocation().distanceSquared(boss.getLocation()))).orElse(null);
            if (boss instanceof Mob mob && target != null && target.getGameMode() == GameMode.SURVIVAL) {
               mob.setTarget(target);
            }

            for (Entity m : f.minions) {
               if (m instanceof Mob mm && m.isValid() && target != null) {
                  mm.setTarget(target);
               }
            }

            for (BossManager.Ability a : f.def.abilities()) {
               String k = a.type() + a.mob();
               if (now >= f.nextUse.getOrDefault(k, 0L)) {
                  f.nextUse.put(k, now + a.cooldown() * 1000L);
                  this.ability(f, a, targets);
               }
            }
         }
      }
   }

   private void ability(BossManager.Fight f, BossManager.Ability a, List<Player> targets) {
      LivingEntity boss = f.boss;
      World w = boss.getWorld();
      Player t = targets.isEmpty() ? null : targets.get(new Random().nextInt(targets.size()));
      String var7 = a.type().toLowerCase(Locale.ROOT);
      switch (var7) {
         case "summon":
            EntityType type;
            try {
               type = EntityType.valueOf(a.mob().toUpperCase(Locale.ROOT));
            } catch (Exception var14) {
               return;
            }

            f.minions.removeIf(m -> !m.isValid());
            if (f.minions.size() > 12) {
               return;
            }

            for (int i = 0; i < a.count(); i++) {
               Location l = boss.getLocation().add(Math.random() * 4.0 - 2.0, 0.5, Math.random() * 4.0 - 2.0);
               Entity m = w.spawnEntity(l, type);
               m.getPersistentDataContainer().set(this.key, PersistentDataType.STRING, "minion");
               if (m instanceof LivingEntity le) {
                  le.setRemoveWhenFarAway(false);
               }

               f.minions.add(m);
               w.spawnParticle(Particle.LARGE_SMOKE, l, 10, 0.3, 0.5, 0.3, 0.02);
            }

            w.playSound(boss.getLocation(), Sound.ENTITY_EVOKER_PREPARE_SUMMON, 1.0F, 1.0F);
            break;
         case "lightning":
            if (t != null) {
               w.strikeLightning(t.getLocation());
            }
            break;
         case "fireball":
            if (t == null) {
               return;
            }

            Vector dir = t.getEyeLocation().toVector().subtract(boss.getEyeLocation().toVector()).normalize();
            SmallFireball fb = (SmallFireball)boss.launchProjectile(SmallFireball.class, dir.multiply(1.2));
            fb.setIsIncendiary(false);
            w.playSound(boss.getLocation(), Sound.ENTITY_BLAZE_SHOOT, 1.0F, 0.8F);
            break;
         case "leap":
            if (t == null) {
               return;
            }

            Vector v = t.getLocation().toVector().subtract(boss.getLocation().toVector());
            v.setY(0).normalize().multiply(1.3).setY(0.65);
            boss.setVelocity(v);
            w.playSound(boss.getLocation(), Sound.ENTITY_RAVAGER_ROAR, 0.8F, 1.4F);
            break;
         case "shockwave":
            w.spawnParticle(Particle.EXPLOSION, boss.getLocation(), 6, 2.0, 0.2, 2.0, 0.0);
            w.playSound(boss.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 1.0F, 0.7F);

            for (Player px : targets) {
               if (!(px.getLocation().distanceSquared(boss.getLocation()) > 36.0)) {
                  px.damage(4.0, boss);
                  Vector kb = px.getLocation().toVector().subtract(boss.getLocation().toVector()).setY(0).normalize().multiply(1.4).setY(0.6);
                  px.setVelocity(kb);
               }
            }
            break;
         case "teleport":
            if (t == null) {
               return;
            }

            Location behind = t.getLocation().clone().subtract(t.getLocation().getDirection().setY(0).normalize().multiply(2));
            behind.setY(f.center.getY());
            w.spawnParticle(Particle.PORTAL, boss.getLocation(), 60, 0.5, 1.0, 0.5, 0.5);
            boss.teleport(behind);
            w.playSound(behind, Sound.ENTITY_ENDERMAN_TELEPORT, 1.0F, 0.8F);
            break;
         case "slow":
            w.playSound(boss.getLocation(), Sound.BLOCK_GLASS_BREAK, 1.0F, 0.6F);

            for (Player p : targets) {
               if (!(p.getLocation().distanceSquared(boss.getLocation()) > 144.0)) {
                  p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 80, 1));
                  w.spawnParticle(Particle.SNOWFLAKE, p.getLocation().add(0.0, 1.0, 0.0), 30, 0.4, 0.6, 0.4, 0.02);
               }
            }
            break;
         case "heal":
            double max = boss.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue();
            if (boss.getHealth() > max / 2.0) {
               return;
            }

            boss.setHealth(Math.min(max, boss.getHealth() + max * 0.25));
            w.spawnParticle(Particle.HEART, boss.getLocation().add(0.0, 2.0, 0.0), 12, 0.6, 0.6, 0.6, 0.0);

            for (Player p : targets) {
               this.plugin.msg(p, "&d" + Text.strip(f.def.name()) + " se soigne !");
            }
      }
   }

   private void end(BossManager.Fight f, boolean win, String reason) {
      if (f.state != BossManager.State.END) {
         f.state = BossManager.State.END;

         for (Entity m : f.minions) {
            if (m.isValid()) {
               m.remove();
            }
         }

         f.minions.clear();
         if (!win && f.boss != null && f.boss.isValid()) {
            f.boss.remove();
         }

         if (f.bar != null) {
            for (UUID u : f.participants) {
               Player p = Bukkit.getPlayer(u);
               if (p != null) {
                  p.hideBossBar(f.bar);
               }
            }
         }

         Clan c = f.clan;
         if (win) {
            c.bossesBeaten().add(f.def.level());
            c.bossCooldowns().put(f.def.level(), System.currentTimeMillis() + this.winCooldown * 1000L);
            this.plugin.clans().addXp(c, f.def.clanXp());
            this.plugin.profiles().bossWon(f.alive, f.def.level());
            this.plugin.clans().log(c, "Boss niv." + f.def.level() + " vaincu : " + Text.strip(f.def.name()));
            this.plugin.clans().dirty();
            Bukkit.broadcast(
               Text.c(
                  this.plugin.settings().prefix
                     + "&5☠ &fLe clan "
                     + c.coloredName()
                     + " &fa vaincu "
                     + f.def.name()
                     + " &7(boss niveau "
                     + f.def.level()
                     + ")&f !"
               )
            );
            this.plugin
               .discord()
               .send("war", "☠️ Le clan **" + c.name() + "** a vaincu **" + Text.strip(f.def.name()) + "** (boss niveau " + f.def.level() + ") !");
            this.plugin.bot().staffLog("☠ Boss", c.name() + " a vaincu le boss niv." + f.def.level() + " (" + f.participants.size() + " participants)");

            for (UUID ux : f.alive) {
               Player p = Bukkit.getPlayer(ux);
               if (p != null) {
                  p.showTitle(
                     Title.title(
                        Text.c("&6☠ Victoire !"),
                        Text.c(
                           f.runeDropped ? "&dUne rune est tombée ! &7Retour dans 20 s" : "&7Pas de rune cette fois... &8(+" + f.def.clanXp() + " XP de clan)"
                        )
                     )
                  );
               }
            }

            Bukkit.getScheduler().runTaskLater(this.plugin, () -> this.sendBack(f), 400L);
         } else {
            c.bossRetryAt(System.currentTimeMillis() + this.retryCooldown * 1000L);
            this.plugin
               .clans()
               .broadcast(
                  c, "&c☠ Défaite contre " + Text.strip(f.def.name()) + " : " + reason + ". &7Nouvel essai dans " + Text.formatTime(this.retryCooldown) + "."
               );
            this.plugin.bot().staffLog("☠ Boss", c.name() + " a échoué contre le boss niv." + f.def.level() + " (" + reason + ")");
            this.sendBack(f);
         }
      }
   }

   private void sendBack(BossManager.Fight f) {
      for (Entry<UUID, Location> e : f.returns.entrySet()) {
         Player p = Bukkit.getPlayer(e.getKey());
         if (p != null && p.isOnline() && !p.isDead() && this.isArena(p.getWorld())) {
            p.teleport(e.getValue());
         }
      }

      if (f.boss != null && f.boss.isValid()) {
         f.boss.remove();
      }

      if (f.center != null) {
         for (Entity ex : f.center.getWorld().getNearbyEntities(f.center, 20.0, 12.0, 20.0)) {
            if (ex instanceof Item || ex instanceof ExperienceOrb) {
               ex.remove();
            }
         }
      }

      this.fights.remove(f.clan.id());
   }

   public void shutdown() {
      for (BossManager.Fight f : new ArrayList<>(this.fights.values())) {
         f.state = BossManager.State.END;

         for (Entity m : f.minions) {
            if (m.isValid()) {
               m.remove();
            }
         }

         if (f.boss != null && f.boss.isValid()) {
            f.boss.remove();
         }

         for (Entry<UUID, Location> e : f.returns.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null && this.isArena(p.getWorld())) {
               p.teleport(e.getValue());
            }
         }
      }

      this.fights.clear();
   }

   private BossManager.Fight fightOfBoss(Entity e) {
      for (BossManager.Fight f : this.fights.values()) {
         if (f.boss != null && f.boss.equals(e)) {
            return f;
         }
      }

      return null;
   }

   @EventHandler
   public void onBossDeath(EntityDeathEvent e) {
      if (e.getEntity().getPersistentDataContainer().has(this.key, PersistentDataType.STRING)) {
         e.getDrops().clear();
         e.setDroppedExp(0);
         BossManager.Fight f = this.fightOfBoss(e.getEntity());
         if (f != null && f.state == BossManager.State.FIGHT) {
            Location l = e.getEntity().getLocation();
            World w = l.getWorld();
            w.strikeLightningEffect(l);
            w.spawnParticle(Particle.TOTEM_OF_UNDYING, l.clone().add(0.0, 1.0, 0.0), 120, 1.0, 1.0, 1.0, 0.5);

            for (String spec : f.def.rewards()) {
               ItemStack it = this.reward(spec);
               if (it != null) {
                  w.dropItem(l.clone().add(0.0, 0.5, 0.0), it).setGlowing(true);
               }
            }

            f.runeDropped = false;
            double bonus = 0.0;

            for (UUID u : f.alive) {
               Player ap = Bukkit.getPlayer(u);
               if (ap != null) {
                  bonus = Math.max(bonus, this.plugin.profiles().stat(ap, "rune-chance"));
               }
            }

            if (f.def.rune() != null && Math.random() < f.def.runeChance() + bonus) {
               ItemStack rune = this.plugin.items().create(f.def.rune());
               if (rune != null) {
                  w.dropItem(l.clone().add(0.0, 0.5, 0.0), rune).setGlowing(true);
                  f.runeDropped = true;
               }
            }

            this.end(f, true, "victoire");
         }
      }
   }

   private ItemStack reward(String spec) {
      String[] p = spec.split(":");
      if (p[0].equalsIgnoreCase("windy") && p.length >= 2) {
         ItemStack it = this.plugin.items().create(p[1]);
         if (it != null && p.length > 2) {
            it.setAmount(Integer.parseInt(p[2]));
         }

         return it;
      } else {
         Material m = Material.matchMaterial(p[0]);
         return m == null ? null : new ItemStack(m, p.length > 1 ? Integer.parseInt(p[1]) : 1);
      }
   }

   @EventHandler(
      priority = EventPriority.HIGH
   )
   public void onPlayerDeath(PlayerDeathEvent e) {
      Player p = e.getPlayer();

      for (BossManager.Fight f : this.fights.values()) {
         if (f.alive.remove(p.getUniqueId())) {
            this.pendingRespawn.put(p.getUniqueId(), f.returns.get(p.getUniqueId()));
            if (f.bar != null) {
               p.hideBossBar(f.bar);
            }

            this.plugin.clans().broadcast(f.clan, "&c☠ " + p.getName() + " est tombé face au boss.");
         }
      }
   }

   @EventHandler
   public void onRespawn(PlayerRespawnEvent e) {
      Location back = this.pendingRespawn.remove(e.getPlayer().getUniqueId());
      if (back != null) {
         e.setRespawnLocation(back);
      } else if (this.isArena(e.getRespawnLocation().getWorld())) {
         e.setRespawnLocation(((World)Bukkit.getWorlds().get(0)).getSpawnLocation());
      }
   }

   @EventHandler
   public void onQuit(PlayerQuitEvent e) {
      for (BossManager.Fight f : this.fights.values()) {
         f.alive.remove(e.getPlayer().getUniqueId());
         if (f.state == BossManager.State.LOBBY) {
            f.participants.remove(e.getPlayer().getUniqueId());
         }

         Location back = f.returns.get(e.getPlayer().getUniqueId());
         if (back != null && this.isArena(e.getPlayer().getWorld())) {
            e.getPlayer().teleport(back);
         }
      }
   }

   @EventHandler
   public void onJoin(PlayerJoinEvent e) {
      if (this.isArena(e.getPlayer().getWorld())) {
         Bukkit.getScheduler().runTaskLater(this.plugin, () -> e.getPlayer().teleport(((World)Bukkit.getWorlds().get(0)).getSpawnLocation()), 5L);
      }
   }

   @EventHandler(
      ignoreCancelled = true
   )
   public void onBreak(BlockBreakEvent e) {
      if (this.isArena(e.getBlock().getWorld()) && !this.plugin.bypass().contains(e.getPlayer().getUniqueId())) {
         e.setCancelled(true);
      }
   }

   @EventHandler(
      ignoreCancelled = true
   )
   public void onPlace(BlockPlaceEvent e) {
      if (this.isArena(e.getBlock().getWorld()) && !this.plugin.bypass().contains(e.getPlayer().getUniqueId())) {
         e.setCancelled(true);
      }
   }

   @EventHandler
   public void onExplode(EntityExplodeEvent e) {
      if (this.isArena(e.getLocation().getWorld())) {
         e.blockList().clear();
      }
   }

   @EventHandler
   public void onIgnite(BlockIgniteEvent e) {
      if (this.isArena(e.getBlock().getWorld())) {
         e.setCancelled(true);
      }
   }

   @EventHandler
   public void onBossTarget(EntityTargetEvent e) {
      if (e.getTarget() != null
         && e.getTarget().getPersistentDataContainer().has(this.key, PersistentDataType.STRING)
         && e.getEntity().getPersistentDataContainer().has(this.key, PersistentDataType.STRING)) {
         e.setCancelled(true);
      }
   }

   @EventHandler
   public void onFriendlyFire(EntityDamageByEntityEvent e) {
      if (e.getEntity().getPersistentDataContainer().has(this.key, PersistentDataType.STRING)
         && (
            e.getDamager().getPersistentDataContainer().has(this.key, PersistentDataType.STRING)
               || e.getDamager() instanceof Projectile pr
                  && pr.getShooter() instanceof Entity sh
                  && sh.getPersistentDataContainer().has(this.key, PersistentDataType.STRING)
         )) {
         e.setCancelled(true);
      }
   }

   public record Ability(String type, int cooldown, String mob, int count) {
   }

   public record BossDef(
      int level,
      String name,
      EntityType type,
      double health,
      double damage,
      double speed,
      double scale,
      List<String> effects,
      Map<String, String> equipment,
      List<BossManager.Ability> abilities,
      List<String> rewards,
      int clanXp,
      String rune,
      double runeChance
   ) {
   }

   final class Fight {
      final Clan clan;
      final BossManager.BossDef def;
      BossManager.State state = BossManager.State.LOBBY;
      final long lobbyEnd;
      final Set<UUID> participants = new LinkedHashSet<>();
      final Set<UUID> alive = new HashSet<>();
      final Map<UUID, Location> returns = new HashMap<>();
      final Map<String, Long> nextUse = new HashMap<>();
      final List<Entity> minions = new ArrayList<>();
      int slot;
      Location center;
      LivingEntity boss;
      BossBar bar;
      long fightEnd;
      boolean healed;
      boolean runeDropped;

      Fight(Clan clan, BossManager.BossDef def) {
         this.clan = clan;
         this.def = def;
         this.lobbyEnd = System.currentTimeMillis() + BossManager.this.lobbySeconds * 1000L;
      }
   }

   static enum State {
      LOBBY,
      FIGHT,
      END;
   }

   static final class VoidGenerator extends ChunkGenerator {
   }
}
