package fr.windydev.clans.profile;

import fr.windydev.clans.WindyClans;
import fr.windydev.clans.elixir.ElixirFeature;
import fr.windydev.clans.listener.ProtectionListener;
import fr.windydev.clans.model.ChunkPos;
import fr.windydev.clans.model.Clan;
import fr.windydev.clans.util.Text;
import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.Map.Entry;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.bossbar.BossBar.Color;
import net.kyori.adventure.bossbar.BossBar.Overlay;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import net.kyori.adventure.title.Title.Times;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

public class Awakening implements Listener {
   private final WindyClans plugin;
   private final ProfileManager pm;
   private final Map<Block, Awakening.Rite> rites = new HashMap<>();
   private final Map<Item, UUID> thrown = new HashMap<>();
   private final Map<Integer, Awakening.RankDef> ranks = new HashMap<>();
   private final Map<String, Map<Integer, List<Awakening.Offering>>> raceOfferings = new HashMap<>();
   private int stayRadius = 10;
   private int pvpRadius = 30;
   private int failHours = 24;
   private int waveEvery = 30;
   private int precision = 50;
   private int rewardCoins = 250;
   private int rewardXp = 200;
   private double failLoss = 0.5;
   private final NamespacedKey mobKey;
   SanctuaryVisuals visuals;
   private static final int[][] CARD = new int[][]{{3, 0}, {-3, 0}, {0, 3}, {0, -3}};
   private static final int[][] DIAG = new int[][]{{2, 2}, {2, -2}, {-2, 2}, {-2, -2}};
   private static final int[][] SIDE = new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
   private static WindyClans INSTANCE;
   private static boolean requireInfused = true;
   private boolean requireLink = true;
   private final Map<Integer, Integer> rankFluid = new HashMap<>(Map.of(2, 2000, 3, 4000, 4, 1500, 5, 800));
   private final Map<Integer, Integer> rankFluidTier = new HashMap<>(Map.of(2, 1, 3, 1, 4, 2, 5, 3));
   private final Map<UUID, Long> plainWarned = new HashMap<>();

   public Awakening(WindyClans plugin, ProfileManager pm) {
      this.plugin = plugin;
      this.pm = pm;
      INSTANCE = plugin;
      this.mobKey = new NamespacedKey(plugin, "awaken_mob");
   }

   public void load() {
      File f = new File(this.plugin.getDataFolder(), "eveil.yml");
      if (!f.exists()) {
         this.plugin.saveResource("eveil.yml", false);
      }

      YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
      this.stayRadius = y.getInt("settings.stay-radius", 10);
      this.pvpRadius = y.getInt("settings.pvp-radius", 30);
      this.failHours = y.getInt("settings.fail-cooldown-hours", 24);
      this.failLoss = y.getDouble("settings.fail-loss", 0.5);
      this.waveEvery = Math.max(5, y.getInt("settings.wave-every", 30));
      this.precision = Math.max(1, y.getInt("settings.broadcast-precision", 50));
      this.rewardCoins = y.getInt("settings.interrupt-reward.coins", 250);
      this.rewardXp = y.getInt("settings.interrupt-reward.clan-xp", 200);
      requireInfused = y.getBoolean("settings.infused.require-infused-core", true);
      this.requireLink = y.getBoolean("settings.infused.require-link", true);
      this.ranks.clear();
      ConfigurationSection rs = y.getConfigurationSection("ranks");
      if (rs != null) {
         Iterator ro = rs.getKeys(false).iterator();

         label95:
         while (true) {
            ConfigurationSection s;
            int r;
            while (true) {
               if (!ro.hasNext()) {
                  break label95;
               }

               String k = (String)ro.next();
               s = rs.getConfigurationSection(k);

               try {
                  r = Integer.parseInt(k);
                  break;
               } catch (NumberFormatException var14) {
               }
            }

            if (s != null && r >= 2 && r <= 5) {
               this.rankFluid.put(r, Math.max(0, s.getInt("fluid", this.rankFluid.getOrDefault(r, 0))));
               this.rankFluidTier.put(r, Math.max(1, Math.min(3, s.getInt("fluid-tier", this.rankFluidTier.getOrDefault(r, 1)))));
               List<EntityType> mobs = new ArrayList<>();

               for (String m : s.getStringList("mobs")) {
                  try {
                     mobs.add(EntityType.valueOf(m.toUpperCase(Locale.ROOT)));
                  } catch (IllegalArgumentException var13) {
                  }
               }

               if (mobs.isEmpty()) {
                  mobs.add(EntityType.ZOMBIE);
               }

               this.ranks
                  .put(
                     r,
                     new Awakening.RankDef(
                        r,
                        Math.max(10, s.getInt("duration", 300)),
                        s.getLong("souls"),
                        s.getInt("boss-wins"),
                        offerings(s.getStringList("offerings")),
                        Math.max(0, s.getInt("wave-size", 3)),
                        mobs
                     )
                  );
            }
         }
      }

      this.raceOfferings.clear();
      ConfigurationSection ro = y.getConfigurationSection("race-offerings");
      if (ro != null) {
         for (String race : ro.getKeys(false)) {
            ConfigurationSection sx = ro.getConfigurationSection(race);
            if (sx != null) {
               Map<Integer, List<Awakening.Offering>> m = new HashMap<>();

               for (String k : sx.getKeys(false)) {
                  try {
                     m.put(Integer.parseInt(k), offerings(sx.getStringList(k)));
                  } catch (NumberFormatException var12) {
                  }
               }

               this.raceOfferings.put(race, m);
            }
         }
      }
   }

   private static List<Awakening.Offering> offerings(List<String> lines) {
      List<Awakening.Offering> out = new ArrayList<>();

      for (String line : lines) {
         int c = line.lastIndexOf(58);
         String spec = line;
         int n = 1;
         if (c > 0) {
            try {
               n = Integer.parseInt(line.substring(c + 1));
               spec = line.substring(0, c);
            } catch (NumberFormatException var8) {
            }
         }

         out.add(new Awakening.Offering(spec, Math.max(1, n)));
      }

      return out;
   }

   public Awakening.RankDef rank(int r) {
      return this.ranks.get(r);
   }

   public List<Awakening.Offering> offeringsFor(String race, int r) {
      List<Awakening.Offering> l = new ArrayList<>();
      Awakening.RankDef d = this.ranks.get(r);
      if (d != null) {
         l.addAll(d.offerings());
      }

      Map<Integer, List<Awakening.Offering>> m = this.raceOfferings.get(race);
      if (m != null && m.get(r) != null) {
         l.addAll(m.get(r));
      }

      return l;
   }

   public int stayRadius() {
      return this.stayRadius;
   }

   public int failHours() {
      return this.failHours;
   }

   public void start() {
      Bukkit.getScheduler().runTaskTimer(this.plugin, this::tick, 5L, 5L);
      this.visuals = new SanctuaryVisuals(this.plugin);
      Bukkit.getPluginManager().registerEvents(this.visuals, this.plugin);
      this.visuals.start();
   }

   public void shutdown() {
      for (Entry<Block, Awakening.Rite> e : new ArrayList<>(this.rites.entrySet())) {
         Awakening.Rite r = e.getValue();
         if (r.def != null) {
            Profile pr = this.pm.get(r.owner);
            if (pr != null) {
               pr.souls = pr.souls + r.soulsPaid;
            }

            this.pm.markDirty();
            this.release(r);
         }

         this.spill(e.getKey(), r, 1.0);
      }

      this.rites.clear();
   }

   public static boolean isCore(Block b) {
      return b.getType() == Material.LODESTONE;
   }

   /** Rang du sanctuaire, limité par le tier de la magnétite infusée du cœur (I -> rang III, II -> IV, III -> V). */
   public static int structureTier(Block core) {
      int raw = rawStructureTier(core);
      if (raw <= 0 || !requireInfused || INSTANCE == null || INSTANCE.elixirs() == null) {
         return raw;
      } else {
         int ct = INSTANCE.elixirs().coreTier(core);
         return ct <= 0 ? 0 : Math.min(raw, ct + 2);
      }
   }

   public static int rawStructureTier(Block core) {
      if (!isCore(core)) {
         return 0;
      } else {
         for (int[] c : CARD) {
            if (!pillar(core.getRelative(c[0], 0, c[1]))) {
               return 0;
            }
         }

         int t = 2;

         for (int[] d : DIAG) {
            Block b = core.getRelative(d[0], 0, d[1]);
            if (!pillar(b) || b.getRelative(0, 2, 0).getType() != Material.GOLD_BLOCK) {
               return t;
            }
         }

         int var8 = 3;

         for (int[] cx : CARD) {
            if (core.getRelative(cx[0], 2, cx[1]).getType() != Material.DIAMOND_BLOCK) {
               return var8;
            }
         }

         var8 = 4;
         if (core.getRelative(0, -1, 0).getType() != Material.NETHERITE_BLOCK) {
            return var8;
         } else {
            for (int[] s : SIDE) {
               if (core.getRelative(s[0], 0, s[1]).getType() != Material.CRYING_OBSIDIAN) {
                  return var8;
               }
            }

            return 5;
         }
      }
   }

   private static boolean pillar(Block b) {
      return b.getType() == Material.CHISELED_TUFF_BRICKS && b.getRelative(0, 1, 0).getType() == Material.CHISELED_TUFF_BRICKS;
   }

   public static List<Block> pillarBlocks(Block core, int tier) {
      List<Block> l = new ArrayList<>();

      for (int[] c : CARD) {
         l.add(core.getRelative(c[0], 0, c[1]));
         l.add(core.getRelative(c[0], 1, c[1]));
      }

      if (tier >= 3) {
         for (int[] d : DIAG) {
            l.add(core.getRelative(d[0], 0, d[1]));
            l.add(core.getRelative(d[0], 1, d[1]));
         }
      }

      return l;
   }

   public static List<Block> sideBlocks(Block core) {
      List<Block> l = new ArrayList<>();

      for (int[] s : SIDE) {
         l.add(core.getRelative(s[0], 0, s[1]));
      }

      return l;
   }

   public Block nearestCore(Player p, int radius) {
      Block c = p.getLocation().getBlock();
      Block best = null;
      double bd = Double.MAX_VALUE;

      for (int x = -radius; x <= radius; x++) {
         for (int y = -3; y <= 3; y++) {
            for (int z = -radius; z <= radius; z++) {
               Block b = c.getRelative(x, y, z);
               if (isCore(b)) {
                  double d = b.getLocation().distanceSquared(p.getLocation());
                  if (d < bd) {
                     bd = d;
                     best = b;
                  }
               }
            }
         }
      }

      return best;
   }

   public boolean forcedPvp(Location l) {
      for (Entry<Block, Awakening.Rite> e : this.rites.entrySet()) {
         if (e.getValue().def != null) {
            Block b = e.getKey();
            if (b.getWorld() == l.getWorld() && b.getLocation().distanceSquared(l) <= (double)this.pvpRadius * this.pvpRadius) {
               return true;
            }
         }
      }

      return false;
   }

   public boolean isActiveCore(Block b) {
      Awakening.Rite r = this.rites.get(b);
      return r != null && r.def != null;
   }

   public boolean sleeping(Player p) {
      for (Awakening.Rite r : this.rites.values()) {
         if (r.sleeping && r.owner.equals(p.getUniqueId())) {
            return true;
         }
      }

      return false;
   }

   public boolean running(Player p) {
      for (Awakening.Rite r : this.rites.values()) {
         if (r.def != null && r.owner.equals(p.getUniqueId())) {
            return true;
         }
      }

      return false;
   }

   @EventHandler(
      ignoreCancelled = true,
      priority = EventPriority.MONITOR
   )
   public void onDrop(PlayerDropItemEvent e) {
      this.thrown.put(e.getItemDrop(), e.getPlayer().getUniqueId());
   }

   private Block coreUnder(Item item) {
      Block b = item.getLocation().getBlock();
      if (isCore(b)) {
         return b;
      } else {
         Block down = b.getRelative(0, -1, 0);
         return isCore(down) ? down : null;
      }
   }

   private void tick() {
      Iterator<Entry<Item, UUID>> it = this.thrown.entrySet().iterator();

      while (it.hasNext()) {
         Entry<Item, UUID> en = it.next();
         Item item = en.getKey();
         if (item.isValid() && item.getTicksLived() <= 160) {
            Block core = this.coreUnder(item);
            if (core != null && structureTier(core) < 2 && rawStructureTier(core) >= 2) {
               UUID who = en.getValue();
               Player pw = Bukkit.getPlayer(who);
               long now = System.currentTimeMillis();
               if (pw != null && now - this.plainWarned.getOrDefault(who, 0L) > 3000L) {
                  this.plainWarned.put(who, now);
                  pw.sendActionBar(Text.c("&5✦ &7Sanctuaire inactif : le cœur doit être une &dMagnétite infusée &7(infuseur magique)."));
               }
            }

            if (core != null && structureTier(core) >= 2) {
               if (this.visuals != null) {
                  this.visuals.refreshLater(core);
               }

               Player p = Bukkit.getPlayer(en.getValue());
               Awakening.Rite rite = this.rites.get(core);
               if (rite == null) {
                  rite = new Awakening.Rite();
                  rite.owner = en.getValue();
                  this.rites.put(core, rite);
               }

               if (rite.def == null) {
                  if (!rite.owner.equals(en.getValue())) {
                     if (p != null) {
                        p.sendActionBar(Text.c("&cCe sanctuaire est utilisé par quelqu'un d'autre."));
                     }
                  } else {
                     rite.contents.add(item.getItemStack().clone());
                     item.remove();
                     it.remove();
                     Location c = core.getLocation().add(0.5, 1.1, 0.5);
                     core.getWorld().spawnParticle(Particle.REVERSE_PORTAL, c, 30, 0.2, 0.2, 0.2, 0.05);
                     core.getWorld().playSound(c, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 0.7F, 1.3F);
                     if (p != null) {
                        this.check(core, rite, p);
                     }
                  }
               }
            }
         } else {
            it.remove();
         }
      }

      for (Entry<Block, Awakening.Rite> e : new ArrayList<>(this.rites.entrySet())) {
         if (e.getValue().def != null) {
            this.run(e.getKey(), e.getValue());
         }
      }
   }

   public List<Component> missing(Player p, Block core, List<ItemStack> items) {
      List<Component> out = new ArrayList<>();
      Profile pr = this.pm.of(p);
      int target = pr.rank + 1;
      Awakening.RankDef d = this.ranks.get(target);
      if (pr.race == null) {
         out.add(Text.item("&cune race"));
         return out;
      } else if (pr.rank < 5 && d != null) {
         if (pr.level < this.pm.levelCap(pr)) {
            out.add(Text.item("&fniveau " + this.pm.levelCap(pr) + " &7(tu es " + pr.level + ")"));
         }

         if (pr.souls < d.souls()) {
            out.add(Text.item("&d" + (d.souls() - pr.souls) + " âmes"));
         }

         if (pr.bossWins < d.bossWins()) {
            out.add(Text.item("&c" + (d.bossWins() - pr.bossWins) + " victoire(s) contre un boss"));
         }

         if (core != null && structureTier(core) < target) {
            int ct = this.plugin.elixirs() == null ? 0 : this.plugin.elixirs().coreTier(core);
            if (requireInfused && rawStructureTier(core) >= target && ct + 2 < target) {
               out.add(Text.item("&5une magnétite infusée de tier " + Stats.roman(Math.max(1, target - 2)) + " au cœur"));
            } else {
               out.add(Text.item("&5un sanctuaire de rang " + Stats.roman(target)));
            }
         }

         if (core != null && this.plugin.elixirs() != null) {
            int cost = this.rankFluid.getOrDefault(target, 0);
            int ft = this.rankFluidTier.getOrDefault(target, 1);
            if ((cost > 0 || this.requireLink) && !this.plugin.elixirs().linked(core)) {
               out.add(Text.item("&5un lien vers un tank magique &7(baguette de liaison)"));
            } else if (cost > 0) {
               int have = this.plugin.elixirs().fluidAvailable(core, ft);
               if (have < cost) {
                  out.add(Text.item("&f" + ElixirFeature.format(cost - have) + " mB &7de " + ElixirFeature.fluidLabel(ft) + " &7ou plus"));
               }
            }
         }

         if (items != null) {
            for (Awakening.Offering o : this.offeringsFor(pr.race, target)) {
               int have = items.stream().filter(i -> this.plugin.items().matches(i, o.spec())).mapToInt(ItemStack::getAmount).sum();
               if (have < o.amount()) {
                  out.add(Text.item("&f" + (o.amount() - have) + " ").append(this.name(o.spec())));
               }
            }
         }

         if (pr.awakenRetryAt > System.currentTimeMillis()) {
            long m = (pr.awakenRetryAt - System.currentTimeMillis()) / 60000L + 1L;
            out.add(Text.item("&cattendre " + (m >= 60L ? m / 60L + " h " + m % 60L + " min" : m + " min") + " &7(échec récent)"));
         }

         return out;
      } else {
         out.add(Text.item("&arien : tu as atteint le rang ultime"));
         return out;
      }
   }

   public Component name(String spec) {
      if (spec.startsWith("windy:")) {
         return Text.item(this.plugin.items().displayName(spec));
      } else {
         Material m = Material.matchMaterial(spec);
         return m == null ? Text.item("&f" + spec) : Component.translatable(m.translationKey()).color(NamedTextColor.WHITE);
      }
   }

   private void check(Block core, Awakening.Rite rite, Player p) {
      List<Component> miss = this.missing(p, core, rite.contents);
      if (miss.isEmpty()) {
         if (this.plugin.clans().isSpawnZone(ChunkPos.of(core.getLocation()))) {
            this.plugin.msg(p, "&cImpossible d'accomplir un Éveil au spawn.");
         } else {
            this.begin(core, rite, p);
         }
      } else {
         Component line = Text.c("&5✦ &7Éveil &8| &7il manque : ");

         for (int i = 0; i < miss.size() && i < 4; i++) {
            line = line.append(i > 0 ? Text.c("&7, ") : Text.c("")).append(miss.get(i));
         }

         if (miss.size() > 4) {
            line = line.append(Text.c("&7..."));
         }

         p.sendActionBar(line);
      }
   }

   private void begin(Block core, Awakening.Rite rite, Player p) {
      Profile pr = this.pm.of(p);
      Awakening.RankDef d = this.ranks.get(pr.rank + 1);
      int fcost = this.rankFluid.getOrDefault(d.rank(), 0);
      if (this.plugin.elixirs() != null && fcost > 0) {
         if (!this.plugin.elixirs().drawFluid(core, this.rankFluidTier.getOrDefault(d.rank(), 1), fcost)) {
            this.plugin.msg(p, "&cPas assez de fluide magique dans le réseau relié au sanctuaire.");
            return;
         }

         this.plugin.msg(p, "&d⚗ &7Le sanctuaire absorbe &f" + ElixirFeature.format(fcost) + " mB &7de fluide magique.");
      }

      rite.def = d;
      rite.needed = this.offeringsFor(pr.race, d.rank());
      rite.soulsPaid = d.souls();
      this.pm.spendSouls(pr, d.souls());
      double speed = Math.max(0.0, Math.min(0.5, this.pm.stat(p, "ritual-speed")));
      rite.total = (int)Math.max(200L, Math.round(d.duration() * 20 * (1.0 - speed)));
      rite.ticks = 0;
      String next = this.pm.tree().rankName(pr.race, d.rank());
      RaceDef race = this.pm.race(pr);
      rite.bar = BossBar.bossBar(Text.c("&5✦ Éveil de &f" + p.getName()), 0.0F, Color.PURPLE, Overlay.NOTCHED_10);
      int x = Math.round((float)core.getX() / this.precision) * this.precision;
      int z = Math.round((float)core.getZ() / this.precision) * this.precision;
      Bukkit.broadcast(
         Text.c(
            this.plugin.settings().prefix
               + "&5✦ &dUn Éveil commence ! &f"
               + p.getName()
               + " &7("
               + race.colored()
               + "&7) tente de devenir &d"
               + next
               + " &7vers &fX≈"
               + x
               + " Z≈"
               + z
               + " &7("
               + core.getWorld().getName()
               + ")."
         )
      );
      Bukkit.broadcast(Text.c("&7   Durée : &f" + Text.formatTime(rite.total / 20) + " &8• &7Le PvP est ouvert à " + this.pvpRadius + " blocs du sanctuaire."));

      for (Player o : Bukkit.getOnlinePlayers()) {
         o.playSound(o.getLocation(), Sound.ENTITY_ELDER_GUARDIAN_CURSE, 0.5F, 0.6F);
      }

      p.showTitle(
         Title.title(
            Text.c("&5✦ Éveil ✦"),
            Text.c("&dReste près du sanctuaire et survis"),
            Times.times(Duration.ofMillis(300L), Duration.ofSeconds(3L), Duration.ofMillis(700L))
         )
      );
      core.getWorld().strikeLightningEffect(core.getLocation().add(0.5, 1.0, 0.5));
      if (this.visuals != null) {
         this.visuals.refreshLater(core);
      }

      this.plugin.discord().send("eveil", "✦ **" + p.getName() + "** commence son Éveil pour devenir **" + next + "** (X≈" + x + " Z≈" + z + ") !");
   }

   private void run(Block core, Awakening.Rite r) {
      Player p = Bukkit.getPlayer(r.owner);
      Location c = core.getLocation().add(0.5, 1.2, 0.5);
      World w = core.getWorld();
      String fail = null;
      if (!isCore(core)) {
         fail = "le cœur du sanctuaire a été brisé";
      } else if (structureTier(core) < r.def.rank()) {
         fail = "le sanctuaire a été défait";
      } else if (p == null || !p.isOnline()) {
         fail = "le rituelliste a quitté le serveur";
      } else if (p.isDead()) {
         fail = "le rituelliste est mort";
      } else if (p.getWorld() != w || p.getLocation().distance(c) > this.stayRadius) {
         fail = "le rituelliste s'est éloigné du sanctuaire";
      }

      if (fail != null) {
         this.failRite(core, r, p, fail, null);
      } else {
         r.ticks += 5;
         double prog = Math.min(1.0, (double)r.ticks / r.total);
         boolean sleep = prog >= 0.6;
         if (sleep && !r.sleeping) {
            r.sleeping = true;
            p.showTitle(
               Title.title(
                  Text.c("&5☾ Sommeil d'Éveil"),
                  Text.c("&7Ton clan doit te protéger !"),
                  Times.times(Duration.ofMillis(300L), Duration.ofSeconds(3L), Duration.ofMillis(700L))
               )
            );
            w.playSound(c, Sound.BLOCK_BEACON_ACTIVATE, 1.5F, 0.5F);
            Clan clan = this.plugin.clans().of(p);
            if (clan != null) {
               for (UUID m : clan.members().keySet()) {
                  Player mp = Bukkit.getPlayer(m);
                  if (mp != null && mp != p) {
                     this.plugin.msg(mp, "&5☾ &f" + p.getName() + " &dentre dans le Sommeil d'Éveil : protège-le !");
                  }
               }
            }
         }

         if (r.sleeping) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 30, 9, false, false, false));
            p.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, 30, 200, false, false, false));
            w.spawnParticle(Particle.ENCHANT, p.getLocation().add(0.0, 2.2, 0.0), 6, 0.3, 0.2, 0.3, 0.5);
         }

         if (r.ticks % 20 == 0) {
            for (double y = 0.0; y < 40.0; y += 0.8) {
               w.spawnParticle(Particle.END_ROD, c.clone().add(0.0, y, 0.0), 1, 0.05, 0.1, 0.05, 0.0, null, true);
            }

            w.spawnParticle(r.def.rank() >= 4 ? Particle.SOUL_FIRE_FLAME : Particle.WITCH, c, 20, 1.5, 0.5, 1.5, 0.02);
            w.playSound(c, Sound.BLOCK_BEACON_AMBIENT, 1.2F, 0.5F + (float)prog);
         }

         if (!r.sleeping && r.ticks % (this.waveEvery * 20) == 0) {
            this.wave(core, r, p);
         }

         int left = (int)Math.ceil((r.total - r.ticks) / 20.0);
         r.bar.progress((float)prog);
         r.bar
            .name(
               Text.c(
                  "&5✦ Éveil de &f"
                     + p.getName()
                     + " &8| "
                     + (r.sleeping ? "&dSommeil d'Éveil" : "&cVagues")
                     + " &8| &f"
                     + left / 60
                     + ":"
                     + String.format("%02d", left % 60)
               )
            );
         if (r.ticks % 20 == 0) {
            Set<UUID> now = new HashSet<>();

            for (Player o : c.getNearbyPlayers(80.0)) {
               now.add(o.getUniqueId());
               if (r.barViewers.add(o.getUniqueId())) {
                  o.showBossBar(r.bar);
               }
            }

            for (UUID u : new ArrayList<>(r.barViewers)) {
               if (!now.contains(u)) {
                  Player ox = Bukkit.getPlayer(u);
                  if (ox != null) {
                     ox.hideBossBar(r.bar);
                  }

                  r.barViewers.remove(u);
               }
            }
         }

         if (r.ticks >= r.total) {
            this.succeed(core, r, p);
         }
      }
   }

   private void wave(Block core, Awakening.Rite r, Player p) {
      World w = core.getWorld();
      Random rnd = new Random();
      r.mobs.removeIf(u -> {
         Entity ex = Bukkit.getEntity(u);
         return ex == null || ex.isDead();
      });
      if (r.mobs.size() <= r.def.waveSize() * 3) {
         for (int i = 0; i < r.def.waveSize(); i++) {
            double a = rnd.nextDouble() * Math.PI * 2.0;
            double d = 6.0 + rnd.nextDouble() * 4.0;
            Location l = core.getLocation().add(0.5 + Math.cos(a) * d, 0.0, 0.5 + Math.sin(a) * d);
            l = w.getHighestBlockAt(l).getLocation().add(0.5, 1.0, 0.5);
            if (Math.abs(l.getY() - core.getY()) > 6.0) {
               l = core.getLocation().add(0.5 + Math.cos(a) * 4.0, 1.0, 0.5 + Math.sin(a) * 4.0);
            }

            EntityType t = r.def.mobs().get(rnd.nextInt(r.def.mobs().size()));
            Entity e = w.spawnEntity(l, t);
            e.getPersistentDataContainer().set(this.mobKey, PersistentDataType.BYTE, (byte)1);
            e.setPersistent(false);
            if (e instanceof Mob m) {
               m.setTarget(p);
            }

            if (e instanceof LivingEntity le) {
               le.setRemoveWhenFarAway(false);
            }

            r.mobs.add(e.getUniqueId());
            w.spawnParticle(Particle.SOUL, l, 12, 0.3, 0.6, 0.3, 0.02);
         }

         w.playSound(core.getLocation(), Sound.ENTITY_WITHER_AMBIENT, 1.0F, 0.7F);
         p.sendActionBar(Text.c("&c☠ Une vague approche !"));
      }
   }

   private void succeed(Block core, Awakening.Rite r, Player p) {
      this.rites.remove(core);
      this.release(r);
      Profile pr = this.pm.of(p);
      String before = this.pm.rankName(pr);
      this.pm.awaken(p);
      String after = this.pm.rankName(pr);
      RaceDef race = this.pm.race(pr);
      World w = core.getWorld();
      Location c = core.getLocation().add(0.5, 1.2, 0.5);
      w.strikeLightningEffect(c);
      w.spawnParticle(Particle.TOTEM_OF_UNDYING, p.getLocation().add(0.0, 1.0, 0.0), 150, 0.8, 1.2, 0.8, 0.5);
      w.spawnParticle(Particle.END_ROD, c, 80, 0.4, 2.0, 0.4, 0.1);

      for (Player o : Bukkit.getOnlinePlayers()) {
         o.playSound(o.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7F, 0.8F);
      }

      p.showTitle(
         Title.title(
            Text.c((race == null ? "&d" : race.color()) + "✦ " + after + " ✦"),
            Text.c("&7Éveil accompli : rang " + Stats.roman(pr.rank)),
            Times.times(Duration.ofMillis(300L), Duration.ofSeconds(4L), Duration.ofMillis(800L))
         )
      );
      Bukkit.broadcast(
         Text.c(
            this.plugin.settings().prefix
               + "&5✦ &f"
               + p.getName()
               + " &7s'est éveillé : &8"
               + before
               + " &7→ "
               + (race == null ? "&d" : race.color())
               + "&l"
               + after
               + " &7!"
         )
      );
      this.plugin.msg(p, "&d+" + this.pm.tree().pointsPerAwakening() + " points de compétence &7et de nouvelles compétences à débloquer : &f/competences");
      this.plugin.discord().send("eveil", "✦ **" + p.getName() + "** s'est éveillé : **" + after + "** (rang " + Stats.roman(pr.rank) + ")");

      for (ItemStack i : this.consume(r.contents, r.needed)) {
         w.dropItem(c, i);
      }

      if (this.visuals != null) {
         this.visuals.refreshLater(core);
      }
   }

   private List<ItemStack> consume(List<ItemStack> contents, List<Awakening.Offering> needed) {
      List<ItemStack> rest = new ArrayList<>();

      for (ItemStack i : contents) {
         rest.add(i.clone());
      }

      for (Awakening.Offering o : needed) {
         int need = o.amount();
         Iterator<ItemStack> it = rest.iterator();

         while (it.hasNext() && need > 0) {
            ItemStack i = it.next();
            if (this.plugin.items().matches(i, o.spec())) {
               int take = Math.min(need, i.getAmount());
               need -= take;
               if (take >= i.getAmount()) {
                  it.remove();
               } else {
                  i.setAmount(i.getAmount() - take);
               }
            }
         }
      }

      return rest;
   }

   private void failRite(Block core, Awakening.Rite r, Player p, String reason, Player breaker) {
      this.rites.remove(core);
      this.release(r);
      Profile pr = this.pm.get(r.owner);
      if (pr != null) {
         this.pm.failAwakening(pr, System.currentTimeMillis() + this.failHours * 3600000L, 0.0);
         pr.souls = pr.souls + Math.round(r.soulsPaid * (1.0 - this.failLoss));
      }

      List<ItemStack> keep = new ArrayList<>();
      List<ItemStack> rest = this.consume(r.contents, r.needed);

      for (Awakening.Offering o : r.needed) {
         int back = (int)Math.floor(o.amount() * (1.0 - this.failLoss));
         if (back > 0) {
            for (ItemStack i : r.contents) {
               if (this.plugin.items().matches(i, o.spec())) {
                  ItemStack b = i.clone();
                  b.setAmount(back);
                  keep.add(b);
                  break;
               }
            }
         }
      }

      Location c = core.getLocation().add(0.5, 1.2, 0.5);

      for (ItemStack ix : rest) {
         core.getWorld().dropItem(c, ix);
      }

      for (ItemStack ix : keep) {
         core.getWorld().dropItem(c, ix);
      }

      core.getWorld().playSound(c, Sound.ENTITY_WITHER_DEATH, 0.8F, 1.4F);
      core.getWorld().spawnParticle(Particle.LARGE_SMOKE, c, 60, 0.6, 1.0, 0.6, 0.05);
      String name = pr == null ? "?" : pr.name;
      UUID by = breaker != null ? breaker.getUniqueId() : (r.lastHitBy != null && System.currentTimeMillis() - r.lastHitAt < 30000L ? r.lastHitBy : null);
      Player who = by == null ? null : Bukkit.getPlayer(by);
      Clan clan = who == null ? null : this.plugin.clans().of(who);
      Bukkit.broadcast(
         Text.c(
            this.plugin.settings().prefix
               + "&5✦ &7L'Éveil de &f"
               + name
               + " &7a échoué : &c"
               + reason
               + (who != null ? " &7(interrompu par &f" + who.getName() + (clan != null ? " &7de " + clan.coloredName() : "") + "&7)" : "")
               + "&7."
         )
      );
      if (clan != null) {
         int mult = Math.max(1, r.def.rank() - 1);
         clan.bank(clan.bank() + (long)this.rewardCoins * mult);
         this.plugin.clans().addXp(clan, (long)this.rewardXp * mult);
         this.plugin.clans().dirty();
         clan.addLog("Éveil de " + name + " interrompu par " + who.getName() + " (+" + this.rewardCoins * mult + " coins)");
         this.plugin
            .msg(
               who,
               "&6+"
                  + this.rewardCoins * mult
                  + " "
                  + this.plugin.settings().currencyName
                  + " &7pour ton clan et &d+"
                  + this.rewardXp * mult
                  + " XP de clan&7."
            );
      }

      if (p != null && p.isOnline()) {
         this.plugin.msg(p, "&cTon Éveil a échoué. &7Tu as perdu la moitié des offrandes et des âmes. Nouvel essai dans &f" + this.failHours + " h&7.");
      }

      if (this.visuals != null) {
         this.visuals.refreshLater(core);
      }
   }

   private void release(Awakening.Rite r) {
      if (r.bar != null) {
         for (UUID u : r.barViewers) {
            Player o = Bukkit.getPlayer(u);
            if (o != null) {
               o.hideBossBar(r.bar);
            }
         }
      }

      r.barViewers.clear();

      for (UUID ux : r.mobs) {
         Entity e = Bukkit.getEntity(ux);
         if (e != null) {
            e.getWorld().spawnParticle(Particle.SOUL, e.getLocation(), 8);
            e.remove();
         }
      }

      r.mobs.clear();
      Player p = Bukkit.getPlayer(r.owner);
      if (p != null && r.sleeping) {
         p.removePotionEffect(PotionEffectType.SLOWNESS);
         p.removePotionEffect(PotionEffectType.JUMP_BOOST);
      }

      r.sleeping = false;
   }

   private void spill(Block core, Awakening.Rite r, double part) {
      Location c = core.getLocation().add(0.5, 1.2, 0.5);

      for (ItemStack i : r.contents) {
         core.getWorld().dropItem(c, i);
      }

      r.contents.clear();
   }

   @EventHandler(
      ignoreCancelled = true
   )
   public void onInteract(PlayerInteractEvent e) {
      if (e.getHand() == EquipmentSlot.HAND && e.getAction() == Action.RIGHT_CLICK_BLOCK && e.getClickedBlock() != null) {
         Block b = e.getClickedBlock();
         if (isCore(b) && e.getPlayer().getInventory().getItemInMainHand().getType().isAir()) {
            Player p = e.getPlayer();
            e.setCancelled(true);
            if (this.visuals != null) {
               this.visuals.refreshLater(b);
            }

            Awakening.Rite r = this.rites.get(b);
            if (r == null) {
               int t = structureTier(b);
               if (t < 2 && rawStructureTier(b) >= 2) {
                  this.plugin.msg(p, "&7Sanctuaire inactif : le cœur doit être une &dMagnétite infusée &7(infuseur magique), puis relié à un tank.");
               } else if (t < 2) {
                  this.plugin.msg(p, "&7Ce cœur n'est pas encore un sanctuaire complet. &f/eveil &7pour voir le plan.");
               } else {
                  this.plugin.menus().evolution(p);
               }
            } else if (r.def != null) {
               this.plugin.msg(p, "&7Un Éveil est en cours ici.");
            } else if (!r.owner.equals(p.getUniqueId())) {
               this.plugin.msg(p, "&cCe ne sont pas tes offrandes.");
            } else {
               if (p.isSneaking()) {
                  this.spill(b, r, 1.0);
                  this.rites.remove(b);
                  this.plugin.msg(p, "&7Tu récupères tes offrandes.");
               } else {
                  this.check(b, r, p);
               }
            }
         }
      }
   }

   @EventHandler(
      priority = EventPriority.MONITOR,
      ignoreCancelled = true
   )
   public void onBreak(BlockBreakEvent e) {
      Block b = e.getBlock();
      Awakening.Rite r = this.rites.get(b);
      if (r != null) {
         if (r.def == null) {
            this.spill(b, r, 1.0);
            this.rites.remove(b);
         } else {
            Player owner = Bukkit.getPlayer(r.owner);
            Player breaker = e.getPlayer() == owner ? null : e.getPlayer();
            this.failRite(b, r, owner, "le cœur du sanctuaire a été brisé", breaker);
         }
      }
   }

   @EventHandler(
      priority = EventPriority.MONITOR,
      ignoreCancelled = true
   )
   public void onHit(EntityDamageByEntityEvent e) {
      if (e.getEntity() instanceof Player v) {
         Player att = ProtectionListener.attacker(e.getDamager());
         if (att != null && att != v) {
            for (Awakening.Rite r : this.rites.values()) {
               if (r.def != null && r.owner.equals(v.getUniqueId())) {
                  r.lastHitBy = att.getUniqueId();
                  r.lastHitAt = System.currentTimeMillis();
               }
            }
         }
      }
   }

   @EventHandler(
      priority = EventPriority.LOWEST,
      ignoreCancelled = true
   )
   public void onAttackWhileAsleep(EntityDamageByEntityEvent e) {
      if (e.getDamager() instanceof Player p && this.sleeping(p)) {
         e.setCancelled(true);
      }
   }

   @EventHandler(
      ignoreCancelled = true
   )
   public void onRetarget(EntityTargetEvent e) {
      if (e.getEntity().getPersistentDataContainer().has(this.mobKey, PersistentDataType.BYTE)) {
         if (e.getTarget() == null) {
            e.setCancelled(true);
         }
      }
   }

   @EventHandler
   public void onMobDeath(EntityDeathEvent e) {
      if (this.isAwakenMob(e.getEntity())) {
         e.getDrops().clear();
         e.setDroppedExp(0);
      }
   }

   public boolean isAwakenMob(Entity e) {
      return e.getPersistentDataContainer().has(this.mobKey, PersistentDataType.BYTE);
   }

   public record Offering(String spec, int amount) {
   }

   public record RankDef(int rank, int duration, long souls, int bossWins, List<Awakening.Offering> offerings, int waveSize, List<EntityType> mobs) {
   }

   static final class Rite {
      UUID owner;
      final List<ItemStack> contents = new ArrayList<>();
      Awakening.RankDef def;
      List<Awakening.Offering> needed;
      int ticks;
      int total;
      long soulsPaid;
      UUID lastHitBy;
      long lastHitAt;
      BossBar bar;
      final Set<UUID> barViewers = new HashSet<>();
      final List<UUID> mobs = new ArrayList<>();
      boolean sleeping;
   }
}
