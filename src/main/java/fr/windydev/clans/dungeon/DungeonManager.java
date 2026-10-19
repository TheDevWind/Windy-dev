package fr.windydev.clans.dungeon;

import fr.windydev.clans.WindyClans;
import fr.windydev.clans.gui.Menu;
import fr.windydev.clans.model.Clan;
import fr.windydev.clans.model.Rank;
import fr.windydev.clans.profile.Profile;
import fr.windydev.clans.util.ItemBuilder;
import fr.windydev.clans.util.Text;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
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
import org.bukkit.block.Chest;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.AbstractSkeleton;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
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
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

/** Donjons : instances générées, à plusieurs, avec monstres à niveau, étages et butin. */
public class DungeonManager implements Listener, CommandExecutor, TabCompleter {
   public static final String WORLD = "windy_donjons";
   private static final int SLOT_SPACING = 2000;
   private static final int FLOOR_SPACING = 400;
   private static final int BASE_Y = 80;
   private static final String[] AFFIXES = new String[]{"blinde", "rapide", "explosif", "invocateur", "vampirique"};
   private final WindyClans plugin;
   private final NamespacedKey keySlot;
   private final NamespacedKey keyLevel;
   private final NamespacedKey keyDmg;
   private final NamespacedKey keyPdmg;
   private final NamespacedKey keyAffix;
   private final NamespacedKey keyBoss;
   private final NamespacedKey keyInv;
   private final Map<String, DungeonDef> defs = new LinkedHashMap<>();
   private final Map<Integer, DungeonInstance> instances = new HashMap<>();
   private final Set<Integer> clearing = new HashSet<>();
   private final Deque<Runnable> clearQueue = new ArrayDeque<>();
   private final Map<UUID, Location> pendingRespawn = new HashMap<>();
   private final Random rnd = new Random();
   private File dataFile;
   private YamlConfiguration data = new YamlConfiguration();
   private World world;
   private Rank rank = Rank.MEMBRE;
   private int lobbySeconds = 30;
   private int maxPlayers = 5;
   private int maxInstances = 3;
   private int timeLimit = 2400;
   private int lives = 3;
   private int rewardCooldown = 7200;
   private double hpPerLevel = 0.35;
   private double dmgPerLevel = 0.2;
   private double groupScaling = 0.6;
   private double eliteChance = 0.125;
   private int xpPerMobLevel = 5;
   private int combatRooms = 4;
   private int mobsBase = 4;
   private int mobsPerPlayer = 2;
   private int mobsCap = 14;
   private String mutationSetting = "auto";
   private double mutationLoot = 1.5;
   private final EclatShop shop;

   /** Mutations de la semaine (v1.2). */
   record Mutation(String id, String name, String description) {
   }

   static final List<Mutation> MUTATIONS = List.of(
      new Mutation("frenesie", "&cFrénésie", "Les monstres sont plus rapides (Vitesse I)."),
      new Mutation("elites", "&6Nid d'élites", "Deux fois plus d'élites."),
      new Mutation("colosses", "&4Colosses", "Les monstres ont 30 % de PV en plus."),
      new Mutation("regeneration", "&aChair tenace", "Les monstres ont Régénération I."),
      new Mutation("explosifs", "&eCendres vives", "Toutes les élites sont explosives.")
   );

   public DungeonManager(WindyClans plugin) {
      this.plugin = plugin;
      this.keySlot = new NamespacedKey(plugin, "dungeon");
      this.keyLevel = new NamespacedKey(plugin, "dungeon_level");
      this.keyDmg = new NamespacedKey(plugin, "dungeon_dmg");
      this.keyPdmg = new NamespacedKey(plugin, "dungeon_pdmg");
      this.keyAffix = new NamespacedKey(plugin, "dungeon_affix");
      this.keyBoss = new NamespacedKey(plugin, "dungeon_boss");
      this.keyInv = new NamespacedKey(plugin, "dungeon_inv");
      this.shop = new EclatShop(plugin);
   }

   public EclatShop shop() {
      return this.shop;
   }

   /** Mutation active cette semaine (change chaque lundi), ou null. */
   Mutation mutation() {
      if ("none".equalsIgnoreCase(this.mutationSetting) || "aucune".equalsIgnoreCase(this.mutationSetting)) {
         return null;
      } else {
         for (Mutation m : MUTATIONS) {
            if (m.id().equalsIgnoreCase(this.mutationSetting)) {
               return m;
            }
         }

         long week = Math.floorDiv(java.time.LocalDate.now().toEpochDay() + 3L, 7L);
         return MUTATIONS.get((int)Math.floorMod(week, (long)MUTATIONS.size()));
      }
   }

   private boolean mut(String id) {
      Mutation m = this.mutation();
      return m != null && m.id().equals(id);
   }

   /** Records : meilleur temps de chaque donjon (tous clans), pour le menu et Discord. */
   public List<String> recordLines(DungeonDef d, int max) {
      List<String> out = new ArrayList<>();
      ConfigurationSection rs = this.data.getConfigurationSection("records." + d.id);
      if (rs != null) {
         List<Map.Entry<String, Long>> l = new ArrayList<>();
         for (String k : rs.getKeys(false)) {
            l.add(Map.entry(k, rs.getLong(k)));
         }

         l.sort(Map.Entry.comparingByValue());
         for (int i = 0; i < Math.min(max, l.size()); i++) {
            Clan c = null;
            try {
               c = this.plugin.clans().get(UUID.fromString(l.get(i).getKey()));
            } catch (IllegalArgumentException ignored) {
            }

            out.add((i + 1) + ". " + (c == null ? "?" : c.name()) + " — " + Text.formatTime(l.get(i).getValue() / 1000L));
         }
      }

      return out;
   }

   public String recordsDiscord() {
      StringBuilder sb = new StringBuilder();
      String[] medals = new String[]{"\ud83e\udd47", "\ud83e\udd48", "\ud83e\udd49"};
      for (DungeonDef d : this.defs.values()) {
         List<String> l = this.recordLines(d, 3);
         sb.append("**").append(Text.strip(d.name)).append("**\n");
         if (l.isEmpty()) {
            sb.append("▫️ pas encore de record\n");
         } else {
            for (int i = 0; i < l.size(); i++) {
               sb.append(i < 3 ? medals[i] : "▫️").append(' ').append(l.get(i).substring(l.get(i).indexOf(' ') + 1)).append('\n');
            }
         }
      }

      Mutation m = this.mutation();
      if (m != null) {
         sb.append("\n\ud83e\uddec Mutation de la semaine : **").append(Text.strip(m.name())).append("** — ").append(m.description());
      }

      return sb.toString();
   }

   private long bestTime(DungeonDef d) {
      long best = 0L;
      ConfigurationSection rs = this.data.getConfigurationSection("records." + d.id);
      if (rs != null) {
         for (String k : rs.getKeys(false)) {
            long v = rs.getLong(k);
            if (v > 0L && (best == 0L || v < best)) {
               best = v;
            }
         }
      }

      return best;
   }

   // ------------------------------------------------------------------ configuration

   public void load() {
      File f = new File(this.plugin.getDataFolder(), "donjons.yml");
      if (!f.exists()) {
         this.plugin.saveResource("donjons.yml", false);
      }

      YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
      if (y.getInt("config-version", 1) < 2) {
         File old = new File(this.plugin.getDataFolder(), "donjons-ancien.yml");
         if (old.exists()) {
            old.delete();
         }

         f.renameTo(old);
         this.plugin.saveResource("donjons.yml", true);
         y = YamlConfiguration.loadConfiguration(f);
         this.plugin.getLogger().info("donjons.yml mis à jour (glyphes runiques). Ancienne version : donjons-ancien.yml");
      }

      try (InputStream in = this.plugin.getResource("donjons.yml")) {
         if (in != null) {
            y.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)));
         }
      } catch (Exception var8) {
      }

      this.rank = Rank.parse(y.getString("settings.rank"), Rank.MEMBRE);
      this.lobbySeconds = Math.max(5, y.getInt("settings.lobby-seconds", 30));
      this.maxPlayers = Math.max(1, y.getInt("settings.max-players", 5));
      this.maxInstances = Math.max(1, y.getInt("settings.max-instances", 3));
      this.timeLimit = Math.max(60, y.getInt("settings.time-limit", 2400));
      this.lives = Math.max(0, y.getInt("settings.lives", 3));
      this.rewardCooldown = Math.max(0, y.getInt("settings.reward-cooldown", 7200));
      this.hpPerLevel = y.getDouble("settings.hp-per-level", 0.35);
      this.dmgPerLevel = y.getDouble("settings.damage-per-level", 0.2);
      this.groupScaling = y.getDouble("settings.group-scaling", 0.6);
      this.eliteChance = y.getDouble("settings.elite-chance", 0.125);
      this.xpPerMobLevel = y.getInt("settings.xp-per-mob-level", 5);
      this.combatRooms = Math.max(2, Math.min(8, y.getInt("settings.combat-rooms", 4)));
      this.mobsBase = Math.max(1, y.getInt("settings.mobs-base", 4));
      this.mobsPerPlayer = Math.max(0, y.getInt("settings.mobs-per-player", 2));
      this.mobsCap = Math.max(1, y.getInt("settings.mobs-cap", 14));
      this.mutationSetting = y.getString("settings.mutation", "auto");
      this.mutationLoot = Math.max(1.0, y.getDouble("settings.mutation-loot-bonus", 1.5));
      this.shop.load();
      this.defs.clear();
      ConfigurationSection ds = y.getConfigurationSection("dungeons");
      if (ds != null) {
         for (String id : ds.getKeys(false)) {
            ConfigurationSection s = ds.getConfigurationSection(id);
            if (s != null) {
               try {
                  this.defs.put(id, new DungeonDef(id, s));
               } catch (Exception var7) {
                  this.plugin.getLogger().warning("donjons.yml : donjon '" + id + "' invalide : " + var7.getMessage());
               }
            }
         }
      }

      this.dataFile = new File(this.plugin.getDataFolder(), "donjons-data.yml");
      this.data = YamlConfiguration.loadConfiguration(this.dataFile);
      this.plugin.getLogger().info(this.defs.size() + " donjon(s) chargé(s).");
   }

   private void saveData() {
      try {
         this.data.save(this.dataFile);
      } catch (Exception var2) {
         this.plugin.getLogger().warning("donjons-data.yml : sauvegarde impossible : " + var2.getMessage());
      }
   }

   public void start() {
      Bukkit.getScheduler().runTaskTimer(this.plugin, this::tick, 20L, 10L);
      Bukkit.getScheduler().runTaskTimer(this.plugin, this::work, 5L, 1L);
      Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
         this.world();

         for (int s : this.data.getIntegerList("dirty-slots")) {
            this.clearSlotBox(s);
         }

         this.data.set("dirty-slots", null);
         this.saveData();
      }, 60L);
   }

   public void shutdown() {
      List<Integer> dirty = new ArrayList<>(this.data.getIntegerList("dirty-slots"));

      for (DungeonInstance inst : new ArrayList<>(this.instances.values())) {
         this.removeMobs(inst);
         this.hideBars(inst);

         for (UUID u : inst.participants) {
            Player p = Bukkit.getPlayer(u);
            if (p != null && this.isDungeon(p.getWorld())) {
               p.teleport(this.returnOf(inst, u));
            }
         }

         if (!dirty.contains(inst.slot)) {
            dirty.add(inst.slot);
         }
      }

      for (int s : this.clearing) {
         if (!dirty.contains(s)) {
            dirty.add(s);
         }
      }

      this.instances.clear();
      this.data.set("dirty-slots", dirty);
      this.saveData();
   }

   // ------------------------------------------------------------------ monde

   private World world() {
      if (this.world == null) {
         this.world = Bukkit.getWorld(WORLD);
         if (this.world == null) {
            this.world = new WorldCreator(WORLD)
               .generator(new DungeonManager.VoidGenerator())
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
         this.world.setKeepSpawnInMemory(false);
      }

      return this.world;
   }

   public boolean isDungeon(World w) {
      return w != null && w.getName().equals(WORLD);
   }

   private int freeSlot() {
      int s = 0;

      while (this.instances.containsKey(s) || this.clearing.contains(s)) {
         s++;
      }

      return s;
   }

   /** Traite la file de construction / d'effacement (15 ms max par tick). */
   private void work() {
      long until = System.nanoTime() + 15000000L;

      for (DungeonInstance inst : this.instances.values()) {
         while (!inst.build.isEmpty() && System.nanoTime() < until) {
            Runnable r = inst.build.poll();

            try {
               r.run();
            } catch (Exception var6) {
               this.plugin.getLogger().warning("Donjon : erreur de construction : " + var6);
            }
         }
      }

      while (!this.clearQueue.isEmpty() && System.nanoTime() < until) {
         try {
            this.clearQueue.poll().run();
         } catch (Exception var5) {
            this.plugin.getLogger().warning("Donjon : erreur d'effacement : " + var5);
         }
      }
   }

   private void clearSlot(DungeonInstance inst) {
      this.clearing.add(inst.slot);
      World w = this.world();

      for (List<Room> rooms : inst.floors) {
         this.clearQueue.addAll(DungeonBuilder.clearTasks(w, rooms));
      }

      int slot = inst.slot;
      this.clearQueue.add(() -> this.clearing.remove(slot));
   }

   /** Effacement « à l'aveugle » d'un emplacement (après un redémarrage en pleine partie). */
   private void clearSlotBox(int slot) {
      this.clearing.add(slot);
      World w = this.world();
      int bx = slot * SLOT_SPACING;

      for (int f = 0; f < 3; f++) {
         int bz = f * FLOOR_SPACING;

         for (int cx = bx - 32; cx <= bx + 12 * DungeonBuilder.GRID; cx += 16) {
            for (int cz = bz - 5 * DungeonBuilder.GRID; cz <= bz + 5 * DungeonBuilder.GRID; cz += 16) {
               int x0 = cx;
               int z0 = cz;
               this.clearQueue.add(() -> {
                  for (int x = x0; x < x0 + 16; x++) {
                     for (int z = z0; z < z0 + 16; z++) {
                        for (int yy = BASE_Y; yy <= BASE_Y + DungeonBuilder.ROOM_H; yy++) {
                           Block b = w.getBlockAt(x, yy, z);
                           if (!b.getType().isAir()) {
                              b.setType(Material.AIR, false);
                           }
                        }
                     }
                  }
               });
            }
         }
      }

      this.clearQueue.add(() -> this.clearing.remove(slot));
   }

   // ------------------------------------------------------------------ menu

   public void menu(Player p) {
      Clan c = this.plugin.clans().of(p);
      Menu m = new Menu(p, "list", 6, "&5Donjons");
      int[] slots = new int[]{20, 22, 24, 30, 32};
      int i = 0;
      long cd = this.rewardLeft(p.getUniqueId());
      Profile pr = this.plugin.profiles().of(p);

      for (DungeonDef d : this.defs.values()) {
         if (i >= slots.length) {
            break;
         }

         List<String> lore = new ArrayList<>();
         lore.add("&7Niveau conseillé : " + (pr.level() >= d.playerLevel ? "&a" : "&c") + d.playerLevel);
         lore.add("&7Monstres : &fniv. " + d.floors.get(0) + " à " + d.floors.get(d.floors.size() - 1));
         lore.add("&7Étages : &f" + d.floors.size() + " &8• &7Joueurs : &f1 à " + this.maxPlayers);
         lore.add("&7Gardien : " + d.guardian.name());
         lore.add("");
         boolean hasSeal = this.hasSeal(p, d);
         lore.add("&7Entrée : " + (hasSeal ? "&a✔ " : "&c✖ ") + Text.strip(this.plugin.items().displayName("windy:" + d.seal)));
         long rec = c == null ? 0L : this.data.getLong("records." + d.id + "." + c.id(), 0L);
         if (rec > 0L) {
            lore.add("&7Record du clan : &f" + Text.formatTime(rec / 1000L));
         }

         lore.add("");
         lore.add(cd > 0L ? "&7Butin de fin : &cdans " + Text.formatTime(cd) : "&7Butin de fin : &adisponible");
         lore.add("");
         lore.add(hasSeal ? "&e▶ Clic : lancer (le groupe a " + this.lobbySeconds + " s pour rejoindre)" : "&cIl te faut le sceau pour entrer.");
         String id = d.id;
         m.set(slots[i++], ItemBuilder.of(d.icon).name(d.name).lore(lore).glow(hasSeal).build(), e -> {
            p.closeInventory();
            this.launch(p, id);
         });
      }

      DungeonInstance lobby = this.joinableFor(p);
      if (lobby != null) {
         m.set(53, ItemBuilder.of(Material.LIME_DYE).name("&aRejoindre le groupe").lore("&7" + lobby.def.name + " &8• &f" + lobby.participants.size() + "/" + this.maxPlayers).glow(true).build(), e -> {
            p.closeInventory();
            this.join(p);
         });
      }

      Mutation mu = this.mutation();
      if (mu != null) {
         m.set(4, ItemBuilder.of(Material.DRAGON_BREATH)
            .name("&5☣ Mutation de la semaine : " + mu.name())
            .lore("&7" + mu.description(), "", "&aButin de fin : chances x" + (this.mutationLoot == Math.floor(this.mutationLoot) ? String.valueOf((int)this.mutationLoot) : String.valueOf(this.mutationLoot)), "&8Change chaque lundi.")
            .glow(true)
            .build());
      }

      m.set(47, ItemBuilder.of(Material.ECHO_SHARD)
         .name("&dBoutique d'Éclats")
         .lore("&7Échange tes Éclats de donjon contre", "&7des glyphes, des sceaux, des runes", "&7et des grimoires.", "", "&eClic : ouvrir")
         .build(), e -> this.shop.menu(p, 0));
      List<String> top = new ArrayList<>();
      for (DungeonDef d : this.defs.values()) {
         top.add(d.name);
         List<String> l = this.recordLines(d, 3);
         if (l.isEmpty()) {
            top.add("&8  pas encore de record");
         } else {
            for (String x : l) {
               top.add("&7  " + x);
            }
         }
      }

      top.add("");
      top.add("&8Aussi sur Discord (statistiques).");
      m.set(51, ItemBuilder.of(Material.GOLDEN_HELMET).name("&6★ Records des donjons").lore(top).build());
      m.set(49, ItemBuilder.of(Material.ARROW).name("&7Retour").build(), e -> {
         if (c == null) {
            p.closeInventory();
         } else {
            this.plugin.menus().bosses(p);
         }
      });
      m.fillBorder();
      m.open(p);
   }

   private boolean hasSeal(Player p, DungeonDef d) {
      if (d.seal == null || d.seal.isEmpty()) {
         return true;
      } else {
         for (ItemStack it : p.getInventory().getContents()) {
            if (it != null && this.plugin.items().matches(it, "windy:" + d.seal)) {
               return true;
            }
         }

         return false;
      }
   }

   private boolean takeSeal(Player p, DungeonDef d) {
      if (d.seal == null || d.seal.isEmpty()) {
         return true;
      } else {
         for (ItemStack it : p.getInventory().getContents()) {
            if (it != null && this.plugin.items().matches(it, "windy:" + d.seal)) {
               it.setAmount(it.getAmount() - 1);
               return true;
            }
         }

         return false;
      }
   }

   private boolean admin(Player p) {
      return p.hasPermission("windyclans.admin") && this.plugin.bypass().contains(p.getUniqueId());
   }

   public long rewardLeft(UUID u) {
      long t = this.data.getLong("rewards." + u, 0L);
      return Math.max(0L, (t - System.currentTimeMillis()) / 1000L);
   }

   // ------------------------------------------------------------------ lancement / groupe

   public DungeonInstance instanceOf(UUID u) {
      for (DungeonInstance inst : this.instances.values()) {
         if (inst.participants.contains(u)) {
            return inst;
         }
      }

      return null;
   }

   private boolean canJoin(Player p, DungeonInstance inst) {
      if (inst.clan == null) {
         return false;
      } else {
         Clan mine = this.plugin.clans().of(p);
         return mine != null && (mine == inst.clan || inst.clan.allies().contains(mine.id()));
      }
   }

   private DungeonInstance joinableFor(Player p) {
      if (this.instanceOf(p.getUniqueId()) != null) {
         return null;
      } else {
         for (DungeonInstance inst : this.instances.values()) {
            if (inst.state == DungeonInstance.State.LOBBY && inst.participants.size() < this.maxPlayers && this.canJoin(p, inst)) {
               return inst;
            }
         }

         return null;
      }
   }

   public void launch(Player p, String id) {
      DungeonDef d = this.defs.get(id);
      if (d != null) {
         boolean admin = this.admin(p);
         Clan c = this.plugin.clans().of(p);
         if (c == null && !admin) {
            this.plugin.msg(p, "&cIl faut un clan pour entrer dans un donjon.");
         } else if (c != null && !c.rank(p.getUniqueId()).atLeast(this.rank) && !admin) {
            this.plugin.msg(p, "&cIl faut être au moins " + this.rank.colored() + " &cpour lancer un donjon.");
         } else if (this.instanceOf(p.getUniqueId()) != null) {
            this.plugin.msg(p, "&cTu es déjà dans un groupe de donjon.");
         } else if (c != null && this.instances.values().stream().anyMatch(ix -> ix.clan == c)) {
            this.plugin.msg(p, "&cTon clan a déjà un donjon en cours.");
         } else if (this.instances.size() >= this.maxInstances) {
            this.plugin.msg(p, "&cTous les donjons sont occupés (" + this.maxInstances + " parties max). Réessaie dans quelques minutes.");
         } else if (!admin && !this.hasSeal(p, d)) {
            this.plugin.msg(p, "&cIl te faut " + this.plugin.items().displayName("windy:" + d.seal) + " &cpour entrer.");
         } else {
            if (this.plugin.profiles().of(p).level() < d.playerLevel) {
               this.plugin.msg(p, "&eAttention : niveau " + d.playerLevel + " conseillé pour " + d.name + "&e.");
            }

            int slot = this.freeSlot();
            DungeonInstance inst = new DungeonInstance(slot, d, c, p.getUniqueId());
            inst.sealTaken = admin;
            inst.lobbyEnd = System.currentTimeMillis() + this.lobbySeconds * 1000L;
            this.instances.put(slot, inst);
            this.plan(inst);
            String msg = this.plugin.settings().prefix + "&5⚔ &f" + p.getName() + " &7ouvre le donjon " + d.name + " &7: départ dans &f" + this.lobbySeconds + " s&7. ";
            Set<Player> notify = new HashSet<>();
            if (c != null) {
               notify.addAll(this.plugin.clans().onlineMembers(c));

               for (UUID allyId : c.allies()) {
                  Clan ally = this.plugin.clans().get(allyId);
                  if (ally != null) {
                     notify.addAll(this.plugin.clans().onlineMembers(ally));
                  }
               }
            } else {
               notify.add(p);
            }

            for (Player m : notify) {
               m.sendMessage(
                  Text.c(msg)
                     .append(
                        Text.c("&a&l[REJOINDRE]")
                           .clickEvent(ClickEvent.runCommand("/donjon rejoindre"))
                           .hoverEvent(HoverEvent.showText(Text.c("&aRejoindre le groupe (" + this.maxPlayers + " joueurs max)")))
                     )
               );
               m.playSound(m.getLocation(), Sound.EVENT_RAID_HORN, 0.7F, 1.4F);
            }

            this.plugin.bot().staffLog("⚔ Donjon", (c == null ? "(admin)" : c.name()) + " ouvre " + Text.strip(d.name) + " — lancé par " + p.getName());
         }
      }
   }

   public void join(Player p) {
      DungeonInstance inst = this.joinableFor(p);
      if (inst == null) {
         this.plugin.msg(p, "&cAucun groupe de donjon à rejoindre (clan ou alliés).");
      } else {
         inst.participants.add(p.getUniqueId());
         Profile pr = this.plugin.profiles().of(p);
         if (pr.level() < inst.def.playerLevel) {
            this.plugin.msg(p, "&eAttention : niveau " + inst.def.playerLevel + " conseillé.");
         }

         this.groupMsg(inst, "&a" + p.getName() + " &7rejoint le groupe (" + inst.participants.size() + "/" + this.maxPlayers + ").");
      }
   }

   public void leave(Player p) {
      DungeonInstance inst = this.instanceOf(p.getUniqueId());
      if (inst == null) {
         this.plugin.msg(p, "&7Tu n'es dans aucun donjon.");
      } else {
         inst.participants.remove(p.getUniqueId());
         if (inst.bar != null) {
            p.hideBossBar(inst.bar);
         }

         if (inst.bossBar != null) {
            p.hideBossBar(inst.bossBar);
         }

         if (this.isDungeon(p.getWorld())) {
            p.teleport(this.returnOf(inst, p.getUniqueId()));
         }

         this.plugin.msg(p, "&7Tu as quitté le donjon.");
         this.groupMsg(inst, "&c" + p.getName() + " &7a quitté le groupe.");
      }
   }

   private void plan(DungeonInstance inst) {
      World w = this.world();
      Random r = new Random();
      DungeonBuilder.Theme th = DungeonBuilder.Theme.of(inst.def.theme);

      for (int f = 0; f < inst.def.floors.size(); f++) {
         List<Room> rooms = DungeonBuilder.plan(f, inst.slot * SLOT_SPACING, BASE_Y, f * FLOOR_SPACING, this.combatRooms, r);
         inst.floors.add(rooms);
         inst.build.addAll(DungeonBuilder.buildTasks(w, rooms, th, r));
      }
   }

   // ------------------------------------------------------------------ boucle

   private void tick() {
      long now = System.currentTimeMillis();

      for (DungeonInstance inst : new ArrayList<>(this.instances.values())) {
         try {
            if (inst.state == DungeonInstance.State.LOBBY) {
               inst.participants.removeIf(u -> Bukkit.getPlayer(u) == null);
               if (inst.participants.isEmpty()) {
                  this.instances.remove(inst.slot);
                  this.clearSlot(inst);
               } else if (now >= inst.lobbyEnd && inst.build.isEmpty()) {
                  this.begin(inst);
               }
            } else if (inst.state == DungeonInstance.State.RUNNING) {
               this.run(inst, now);
            }
         } catch (Exception var6) {
            this.plugin.getLogger().warning("Erreur de donjon : " + var6);
            var6.printStackTrace();
            this.end(inst, false, "erreur interne");
         }
      }
   }

   private void begin(DungeonInstance inst) {
      List<Player> players = new ArrayList<>();

      for (UUID u : inst.participants) {
         Player p = Bukkit.getPlayer(u);
         if (p != null && p.isOnline() && !p.isDead()) {
            players.add(p);
         }
      }

      Player leader = Bukkit.getPlayer(inst.leader);
      if (!inst.sealTaken) {
         boolean ok = leader != null && this.takeSeal(leader, inst.def);
         if (!ok) {
            for (Player p : players) {
               if (this.takeSeal(p, inst.def)) {
                  ok = true;
                  break;
               }
            }
         }

         if (!ok) {
            this.groupMsg(inst, "&cPlus aucun sceau dans le groupe : le donjon est annulé.");
            this.instances.remove(inst.slot);
            this.clearSlot(inst);
            return;
         }

         inst.sealTaken = true;
      }

      inst.participants.clear();
      World w = this.world();
      Room entrance = inst.floors.get(0).get(0);
      inst.checkpoint = entrance.center(w);
      inst.checkpoint.setDirection(new Vector(entrance.nextDx, 0, entrance.nextDz));
      inst.lives = this.lives;
      inst.state = DungeonInstance.State.RUNNING;
      inst.startedAt = System.currentTimeMillis();
      inst.deadline = inst.startedAt + this.timeLimit * 1000L;
      inst.bar = BossBar.bossBar(Text.c(inst.def.name), 1.0F, Color.PURPLE, Overlay.PROGRESS);

      for (Player p : players) {
         inst.participants.add(p.getUniqueId());
         inst.returns.put(p.getUniqueId(), p.getLocation());
         p.teleport(inst.checkpoint);
         p.showBossBar(inst.bar);
         p.showTitle(Title.title(Text.c(inst.def.name), Text.c("&7Étage 1 &8• &fmonstres niv. " + inst.monsterLevel()), Times.times(Duration.ofMillis(300L), Duration.ofSeconds(3L), Duration.ofMillis(700L))));
         p.playSound(p.getLocation(), Sound.AMBIENT_CAVE, 1.0F, 0.8F);
      }

      this.groupMsg(inst, "&7Vies du groupe : &c" + inst.lives + " &8• &7Temps : &f" + Text.formatTime(this.timeLimit) + " &8• &7Videz chaque salle pour ouvrir la suivante.");
   }

   private List<Player> present(DungeonInstance inst) {
      List<Player> out = new ArrayList<>();

      for (UUID u : inst.participants) {
         Player p = Bukkit.getPlayer(u);
         if (p != null && p.isOnline() && this.isDungeon(p.getWorld())) {
            out.add(p);
         }
      }

      return out;
   }

   private List<Player> alive(DungeonInstance inst) {
      List<Player> out = this.present(inst);
      out.removeIf(p -> p.isDead() || p.getGameMode() == GameMode.SPECTATOR);
      return out;
   }

   private void run(DungeonInstance inst, long now) {
      List<Player> present = this.present(inst);
      inst.participants.removeIf(u -> {
         Player p = Bukkit.getPlayer(u);
         return p == null || !this.isDungeon(p.getWorld()) && !p.isDead();
      });
      if (present.isEmpty()) {
         this.end(inst, false, "le groupe a quitté le donjon");
      } else if (now > inst.deadline) {
         this.end(inst, false, "temps écoulé");
      } else {
         long left = (inst.deadline - now) / 1000L;
         inst.bar.name(
            Text.c(
               inst.def.name
                  + " &8• &7Étage &f"
                  + (inst.floor + 1)
                  + "/"
                  + inst.floors.size()
                  + " &8• &f⏳ "
                  + Text.formatTime(left)
                  + " &8• &c❤ "
                  + inst.lives
            )
         );
         inst.bar.progress((float)Math.max(0.0, Math.min(1.0, (double)(inst.deadline - now) / (this.timeLimit * 1000.0))));
         if (inst.floorTransitionAt > 0L) {
            if (now >= inst.floorTransitionAt) {
               inst.floorTransitionAt = 0L;
               this.nextFloor(inst);
            }
         } else {
            World w = this.world();
            List<Player> alive = this.alive(inst);

            for (Room room : inst.currentRooms()) {
               switch (room.state) {
                  case IDLE:
                     for (Player p : alive) {
                        if (room.inside(p.getLocation())) {
                           room.state = Room.State.WAITING;
                           room.waitUntil = now + 2500L;

                           for (Player q : alive) {
                              q.sendActionBar(Text.c("&c⚠ Les portes se referment..."));
                           }
                           break;
                        }
                     }
                     break;
                  case WAITING:
                     if (now >= room.waitUntil) {
                        this.startRoom(inst, room, alive);
                     }
                     break;
                  case FIGHT:
                     this.fightRoom(inst, room, alive, now);
                  case CLEARED:
               }
            }
         }
      }
   }

   private void startRoom(DungeonInstance inst, Room room, List<Player> alive) {
      World w = this.world();

      for (Player p : alive) {
         if (!room.inside(p.getLocation())) {
            p.teleport(room.arrival(w));
         }
      }

      if (room.entry != null) {
         DungeonBuilder.setDoor(w, room.entry, DungeonBuilder.Theme.of(inst.def.theme).bars);
      }

      room.state = Room.State.FIGHT;
      int n = alive.size();
      int level = inst.monsterLevel();
      List<EntityType> types = new ArrayList<>(inst.def.mobs.keySet());
      if (room.type == Room.Type.BOSS) {
         boolean last = inst.floor == inst.floors.size() - 1;
         this.spawnBoss(inst, room, last ? inst.def.guardian : inst.def.miniBoss, n, level);

         for (int i = 0; i < 1 + n / 2; i++) {
            EntityType t = types.get(this.rnd.nextInt(types.size()));
            this.spawnMob(inst, room, this.spot(room), t, inst.def.mobs.get(t), level, false, n);
         }

         for (Player p : alive) {
            p.showTitle(
               Title.title(
                  Text.c(last ? inst.def.guardian.name() : inst.def.miniBoss.name()),
                  Text.c(last ? "&7Gardien du donjon" : "&7Gardien de l'étage " + (inst.floor + 1)),
                  Times.times(Duration.ofMillis(200L), Duration.ofSeconds(2L), Duration.ofMillis(500L))
               )
            );
            p.playSound(p.getLocation(), Sound.ENTITY_WITHER_SPAWN, 0.7F, 1.1F);
         }
      } else if (room.type == Room.Type.TREASURE) {
         for (int i = 0; i < 2 + n; i++) {
            EntityType t = types.get(this.rnd.nextInt(types.size()));
            this.spawnMob(inst, room, this.spot(room), t, inst.def.mobs.get(t), level + 1, true, n);
         }

         for (Player p : alive) {
            p.sendActionBar(Text.c("&6✦ Salle au trésor : des élites gardent le coffre !"));
         }
      } else {
         int count = Math.min(this.mobsCap, this.mobsBase + this.mobsPerPlayer * n);

         for (int i = 0; i < count; i++) {
            EntityType t = types.get(this.rnd.nextInt(types.size()));
            int lv = level + (this.rnd.nextDouble() < 0.3 ? 1 : 0);
            this.spawnMob(inst, room, this.spot(room), t, inst.def.mobs.get(t), lv, this.rnd.nextDouble() < this.eliteChance * (this.mut("elites") ? 2.0 : 1.0), n);
         }
      }

      w.playSound(room.center(w), Sound.BLOCK_IRON_DOOR_CLOSE, 1.0F, 0.6F);
      if (room.mobs.isEmpty() && room.boss == null) {
         this.clearRoom(inst, room);
      }
   }

   private Location spot(Room room) {
      int r = room.half - 3;
      return new Location(this.world(), room.cx + this.rnd.nextInt(2 * r + 1) - r + 0.5, room.y0 + 1, room.cz + this.rnd.nextInt(2 * r + 1) - r + 0.5);
   }

   private void fightRoom(DungeonInstance inst, Room room, List<Player> alive, long now) {
      World w = this.world();
      room.mobs.removeIf(u -> {
         Entity e = Bukkit.getEntity(u);
         return e == null || !e.isValid() || e.isDead();
      });
      if (room.boss != null) {
         Entity b = Bukkit.getEntity(room.boss);
         if (b == null || !b.isValid() || b.isDead()) {
            room.boss = null;
            this.hideBossBar(inst);
         }
      }

      for (UUID u : new ArrayList<>(room.mobs)) {
         Entity e = Bukkit.getEntity(u);
         if (e instanceof LivingEntity le) {
            if (!room.inside(le.getLocation())) {
               le.teleport(room.center(w));
            }

            if (le instanceof Mob mob && (mob.getTarget() == null || !(mob.getTarget() instanceof Player))) {
               Player t = this.nearest(alive, le.getLocation());
               if (t != null) {
                  mob.setTarget(t);
               }
            }
         }
      }

      if (room.boss != null && Bukkit.getEntity(room.boss) instanceof LivingEntity boss) {
         if (!room.inside(boss.getLocation())) {
            boss.teleport(room.center(w));
         }

         if (boss instanceof Mob bm && (bm.getTarget() == null || !(bm.getTarget() instanceof Player))) {
            Player t = this.nearest(alive, boss.getLocation());
            if (t != null) {
               bm.setTarget(t);
            }
         }

         double max = boss.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue();
         if (inst.bossBar != null) {
            inst.bossBar.progress((float)Math.max(0.0, Math.min(1.0, boss.getHealth() / max)));
         }

         DungeonDef.BossSpec spec = inst.floor == inst.floors.size() - 1 ? inst.def.guardian : inst.def.miniBoss;

         for (DungeonDef.Ability a : spec.abilities()) {
            String k = inst.floor + a.type() + a.mob();
            if (now >= inst.nextUse.getOrDefault(k, now + a.cooldown() * 1000L)) {
               inst.nextUse.put(k, now + a.cooldown() * 1000L);
               this.ability(inst, room, boss, a, alive);
            } else if (!inst.nextUse.containsKey(k)) {
               inst.nextUse.put(k, now + a.cooldown() * 1000L);
            }
         }
      }

      if (room.mobs.isEmpty() && room.boss == null) {
         this.clearRoom(inst, room);
      }
   }

   private Player nearest(List<Player> ps, Location l) {
      Player best = null;
      double bd = Double.MAX_VALUE;

      for (Player p : ps) {
         if (p.getWorld() == l.getWorld() && p.getGameMode() == GameMode.SURVIVAL) {
            double d = p.getLocation().distanceSquared(l);
            if (d < bd) {
               bd = d;
               best = p;
            }
         }
      }

      return best;
   }

   private void clearRoom(DungeonInstance inst, Room room) {
      room.state = Room.State.CLEARED;
      World w = this.world();
      List<Room> rooms = inst.currentRooms();
      if (room.exit != null) {
         DungeonBuilder.setDoor(w, room.exit, Material.AIR);
      }

      if (room.index + 1 < rooms.size()) {
         Room next = rooms.get(room.index + 1);
         if (next.entry != null) {
            DungeonBuilder.setDoor(w, next.entry, Material.AIR);
         }
      }

      w.playSound(room.center(w), Sound.BLOCK_IRON_DOOR_OPEN, 1.0F, 0.8F);
      if (room.type == Room.Type.TREASURE) {
         this.placeChest(inst, room);
      }

      if (room.type == Room.Type.BOSS) {
         this.hideBossBar(inst);
         if (inst.floor >= inst.floors.size() - 1) {
            this.end(inst, true, "victoire");
         } else {
            inst.floorTransitionAt = System.currentTimeMillis() + 6000L;
            this.groupMsg(inst, "&d✦ Le gardien est tombé ! &7Le passage vers l'étage &f" + (inst.floor + 2) + " &7s'ouvre dans 6 s...");
            w.spawnParticle(Particle.REVERSE_PORTAL, room.center(w).add(0.0, 1.0, 0.0), 200, 1.0, 1.5, 1.0, 0.05);
         }
      } else {
         for (Player p : this.alive(inst)) {
            p.sendActionBar(Text.c("&a✔ Salle vidée &8• &7la porte suivante est ouverte"));
         }
      }
   }

   private void nextFloor(DungeonInstance inst) {
      inst.floor++;
      World w = this.world();
      Room entrance = inst.currentRooms().get(0);
      inst.checkpoint = entrance.center(w);
      inst.checkpoint.setDirection(new Vector(entrance.nextDx, 0, entrance.nextDz));

      for (Player p : this.present(inst)) {
         if (!p.isDead()) {
            p.teleport(inst.checkpoint);
         }

         p.showTitle(Title.title(Text.c("&5Étage " + (inst.floor + 1)), Text.c("&7Monstres niv. &f" + inst.monsterLevel()), Times.times(Duration.ofMillis(300L), Duration.ofSeconds(2L), Duration.ofMillis(600L))));
         p.playSound(p.getLocation(), Sound.BLOCK_PORTAL_TRAVEL, 0.4F, 1.4F);
      }
   }

   private void placeChest(DungeonInstance inst, Room room) {
      Block b = this.world().getBlockAt(room.cx, room.y0 + 1, room.cz);
      b.setType(Material.CHEST);
      if (b.getState() instanceof Chest chest) {
         int rolls = Math.max(1, (this.present(inst).size() + 1) / 2);

         for (int i = 0; i < rolls; i++) {
            for (ItemStack it : this.roll(inst.def.chestLoot)) {
               chest.getBlockInventory().addItem(it);
            }
         }
      }

      this.world().spawnParticle(Particle.TOTEM_OF_UNDYING, b.getLocation().add(0.5, 1.0, 0.5), 40, 0.4, 0.5, 0.4, 0.3);
      this.groupMsg(inst, "&6✦ Un coffre est apparu au centre de la salle au trésor.");
   }

   private List<ItemStack> roll(List<DungeonDef.Loot> table) {
      return this.roll(table, 1.0);
   }

   private List<ItemStack> roll(List<DungeonDef.Loot> table, double mult) {
      List<ItemStack> out = new ArrayList<>();

      for (DungeonDef.Loot l : table) {
         if (this.rnd.nextDouble() < Math.min(1.0, l.chance() * mult)) {
            int n = l.min() + (l.max() > l.min() ? this.rnd.nextInt(l.max() - l.min() + 1) : 0);
            ItemStack it;
            if (l.spec().startsWith("windy:")) {
               it = this.plugin.items().create(l.spec().substring(6));
            } else {
               Material m = Material.matchMaterial(l.spec());
               it = m == null ? null : new ItemStack(m);
            }

            if (it != null) {
               it.setAmount(Math.min(it.getMaxStackSize(), n));
               out.add(it);
            }
         }
      }

      return out;
   }

   // ------------------------------------------------------------------ monstres

   private double scaleHp(int level, int players) {
      return (1.0 + this.hpPerLevel * (level - 1)) * (1.0 + this.groupScaling * Math.max(0, players - 1));
   }

   private void spawnMob(DungeonInstance inst, Room room, Location loc, EntityType type, String name, int level, boolean elite, int players) {
      Class<? extends Entity> cls = type.getEntityClass();
      if (cls != null && type.isAlive()) {
         Entity e = this.world().spawn(loc, cls, false, x -> {});
         if (!(e instanceof LivingEntity le)) {
            e.remove();
         } else {
            String affix = elite ? (this.mut("explosifs") ? "explosif" : AFFIXES[this.rnd.nextInt(AFFIXES.length)]) : null;
            double hp = this.base(le, Attribute.GENERIC_MAX_HEALTH, 20.0) * this.scaleHp(level, players) * ("blinde".equals(affix) ? 1.5 : 1.0) * (elite ? 1.4 : 1.0)
               * (this.mut("colosses") ? 1.3 : 1.0);
            this.attr(le, Attribute.GENERIC_MAX_HEALTH, hp);
            le.setHealth(hp);
            double dmg = (1.0 + this.dmgPerLevel * (level - 1)) * (elite ? 1.2 : 1.0);
            this.tag(le, inst, level, dmg, dmg, affix, false);
            this.attr(le, Attribute.GENERIC_FOLLOW_RANGE, 32.0);
            if ("rapide".equals(affix)) {
               le.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, -1, 1, false, false));
            } else if (this.mut("frenesie")) {
               le.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, -1, 0, false, false));
            }

            if (this.mut("regeneration")) {
               le.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, -1, 0, false, false));
            }

            if ("blinde".equals(affix)) {
               le.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, -1, 0, false, false));
            }

            String label = elite ? "&6★ " + affixName(affix) + " &e" + name : "&f" + name;
            le.customName(Text.c("&8[&7Niv. " + level + "&8] " + label));
            le.setCustomNameVisible(true);
            le.setRemoveWhenFarAway(false);
            this.equip(le, type, level, null);
            if (elite) {
               le.setGlowing(false);
               this.world().spawnParticle(Particle.FLAME, le.getLocation().add(0.0, 1.0, 0.0), 20, 0.3, 0.6, 0.3, 0.02);
            }

            room.mobs.add(le.getUniqueId());
         }
      }
   }

   private static String affixName(String a) {
      return switch (a == null ? "" : a) {
         case "blinde" -> "Blindé";
         case "rapide" -> "Rapide";
         case "explosif" -> "Explosif";
         case "invocateur" -> "Invocateur";
         case "vampirique" -> "Vampirique";
         default -> "Élite";
      };
   }

   private void spawnBoss(DungeonInstance inst, Room room, DungeonDef.BossSpec spec, int players, int level) {
      Class<? extends Entity> cls = spec.type().getEntityClass();
      if (cls != null) {
         Location c = room.center(this.world());
         this.world().strikeLightningEffect(c);
         Entity e = this.world().spawn(c, cls, false, x -> {});
         if (!(e instanceof LivingEntity le)) {
            e.remove();
         } else {
            double hp = spec.health() * (1.0 + this.groupScaling * Math.max(0, players - 1));
            this.attr(le, Attribute.GENERIC_MAX_HEALTH, hp);
            le.setHealth(hp);
            this.attr(le, Attribute.GENERIC_ATTACK_DAMAGE, spec.damage());
            this.attr(le, Attribute.GENERIC_KNOCKBACK_RESISTANCE, 0.6);
            this.attr(le, Attribute.GENERIC_FOLLOW_RANGE, 40.0);
            this.attr(le, Attribute.GENERIC_SCALE, spec.scale());
            this.tag(le, inst, level, 1.0, Math.max(1.0, spec.damage() / 5.0), null, true);
            le.customName(Text.c(spec.name()));
            le.setCustomNameVisible(true);
            le.setRemoveWhenFarAway(false);
            this.equip(le, spec.type(), level, spec.equipment());
            room.boss = le.getUniqueId();
            this.hideBossBar(inst);
            inst.bossBar = BossBar.bossBar(Text.c(spec.name()), 1.0F, Color.RED, Overlay.NOTCHED_10);

            for (Player p : this.present(inst)) {
               p.showBossBar(inst.bossBar);
            }
         }
      }
   }

   private void tag(LivingEntity le, DungeonInstance inst, int level, double dmg, double pdmg, String affix, boolean boss) {
      PersistentDataContainer pdc = le.getPersistentDataContainer();
      pdc.set(this.keySlot, PersistentDataType.INTEGER, inst.slot);
      pdc.set(this.keyLevel, PersistentDataType.INTEGER, level);
      pdc.set(this.keyDmg, PersistentDataType.DOUBLE, dmg);
      pdc.set(this.keyPdmg, PersistentDataType.DOUBLE, pdmg);
      if (affix != null) {
         pdc.set(this.keyAffix, PersistentDataType.STRING, affix);
      }

      if (boss) {
         pdc.set(this.keyBoss, PersistentDataType.BYTE, (byte)1);
      }

      if (le instanceof Zombie z) {
         z.setAdult();
         z.setShouldBurnInDay(false);
      }

      if (le instanceof AbstractSkeleton sk) {
         sk.setShouldBurnInDay(false);
      }
   }

   private void equip(LivingEntity le, EntityType type, int level, Map<String, String> custom) {
      EntityEquipment eq = le.getEquipment();
      if (eq != null) {
         Material hand = switch (type) {
            case SKELETON, STRAY, BOGGED -> Material.BOW;
            case PILLAGER -> Material.CROSSBOW;
            case VINDICATOR -> Material.IRON_AXE;
            case WITHER_SKELETON -> Material.STONE_SWORD;
            case ZOMBIE, HUSK, DROWNED -> level >= 5 ? Material.IRON_SWORD : (level >= 3 ? Material.STONE_SWORD : null);
            default -> null;
         };
         if (custom != null && custom.get("hand") != null) {
            hand = Material.matchMaterial(custom.get("hand"));
         }

         if (hand != null) {
            eq.setItemInMainHand(new ItemStack(hand));
            eq.setItemInMainHandDropChance(0.0F);
         }

         Material helmet = level >= 7 ? Material.DIAMOND_HELMET : (level >= 4 ? Material.IRON_HELMET : (level >= 2 ? Material.CHAINMAIL_HELMET : null));
         if (custom != null && custom.get("helmet") != null) {
            helmet = Material.matchMaterial(custom.get("helmet"));
         }

         if (helmet != null && le instanceof Mob && type != EntityType.SPIDER && type != EntityType.CAVE_SPIDER && type != EntityType.SILVERFISH && type != EntityType.IRON_GOLEM) {
            eq.setHelmet(new ItemStack(helmet));
            eq.setHelmetDropChance(0.0F);
         }

         if (custom != null) {
            Material ch = custom.get("chestplate") == null ? null : Material.matchMaterial(custom.get("chestplate"));
            if (ch != null) {
               eq.setChestplate(new ItemStack(ch));
               eq.setChestplateDropChance(0.0F);
            }
         }
      }
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

   private void ability(DungeonInstance inst, Room room, LivingEntity boss, DungeonDef.Ability a, List<Player> targets) {
      World w = boss.getWorld();
      Player t = targets.isEmpty() ? null : targets.get(this.rnd.nextInt(targets.size()));
      switch (a.type()) {
         case "summon":
            if (room.mobs.size() >= 12) {
               return;
            }

            EntityType type;
            try {
               type = EntityType.valueOf(a.mob().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException var13) {
               return;
            }

            String name = inst.def.mobs.getOrDefault(type, "Serviteur");

            for (int i = 0; i < a.count(); i++) {
               Location l = boss.getLocation().add(this.rnd.nextDouble() * 4.0 - 2.0, 0.3, this.rnd.nextDouble() * 4.0 - 2.0);
               this.spawnMob(inst, room, l, type, name, inst.monsterLevel(), false, this.present(inst).size());
               w.spawnParticle(Particle.LARGE_SMOKE, l, 10, 0.3, 0.5, 0.3, 0.02);
            }

            w.playSound(boss.getLocation(), Sound.ENTITY_EVOKER_PREPARE_SUMMON, 1.0F, 1.0F);
            break;
         case "leap":
            if (t != null) {
               Vector v = t.getLocation().toVector().subtract(boss.getLocation().toVector()).setY(0);
               if (v.lengthSquared() > 0.01) {
                  boss.setVelocity(v.normalize().multiply(1.3).setY(0.65));
                  w.playSound(boss.getLocation(), Sound.ENTITY_RAVAGER_ROAR, 0.8F, 1.4F);
               }
            }
            break;
         case "shockwave":
            w.spawnParticle(Particle.EXPLOSION, boss.getLocation(), 6, 2.0, 0.2, 2.0, 0.0);
            w.playSound(boss.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 1.0F, 0.7F);

            for (Player p : targets) {
               if (p.getLocation().distanceSquared(boss.getLocation()) <= 36.0) {
                  p.damage(5.0, boss);
                  Vector kb = p.getLocation().toVector().subtract(boss.getLocation().toVector()).setY(0);
                  if (kb.lengthSquared() > 0.01) {
                     p.setVelocity(kb.normalize().multiply(1.3).setY(0.55));
                  }
               }
            }
            break;
         case "slow":
            w.playSound(boss.getLocation(), Sound.BLOCK_GLASS_BREAK, 1.0F, 0.6F);

            for (Player p : targets) {
               if (p.getLocation().distanceSquared(boss.getLocation()) <= 144.0) {
                  p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 80, 1));
                  w.spawnParticle(Particle.SNOWFLAKE, p.getLocation().add(0.0, 1.0, 0.0), 30, 0.4, 0.6, 0.4, 0.02);
               }
            }
            break;
         case "heal":
            double max = boss.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue();
            if (boss.getHealth() < max / 2.0) {
               boss.setHealth(Math.min(max, boss.getHealth() + max * 0.2));
               w.spawnParticle(Particle.HEART, boss.getLocation().add(0.0, 2.0, 0.0), 12, 0.6, 0.6, 0.6, 0.0);
               this.groupMsg(inst, "&d" + Text.strip(boss.getCustomName() == null ? "Le gardien" : boss.getCustomName()) + " se soigne !");
            }
            break;
         case "teleport":
            if (t != null) {
               Vector dir = t.getLocation().getDirection().setY(0);
               Location behind = dir.lengthSquared() > 0.01 ? t.getLocation().clone().subtract(dir.normalize().multiply(2)) : t.getLocation().clone();
               behind.setY(room.y0 + 1);
               if (room.inside(behind)) {
                  w.spawnParticle(Particle.PORTAL, boss.getLocation(), 60, 0.5, 1.0, 0.5, 0.5);
                  boss.teleport(behind);
                  w.playSound(behind, Sound.ENTITY_ENDERMAN_TELEPORT, 1.0F, 0.8F);
               }
            }
            break;
         case "lightning":
            if (t != null) {
               w.strikeLightningEffect(t.getLocation());
               t.damage(6.0, boss);
            }
            break;
         case "fireball":
            if (t != null) {
               Vector dir = t.getEyeLocation().toVector().subtract(boss.getEyeLocation().toVector());
               if (dir.lengthSquared() > 0.01) {
                  SmallFireball fb = boss.launchProjectile(SmallFireball.class, dir.normalize().multiply(1.2));
                  fb.setIsIncendiary(false);
                  w.playSound(boss.getLocation(), Sound.ENTITY_BLAZE_SHOOT, 1.0F, 0.8F);
               }
            }
      }
   }

   private void removeMobs(DungeonInstance inst) {
      for (List<Room> rooms : inst.floors) {
         for (Room r : rooms) {
            for (UUID u : r.mobs) {
               Entity e = Bukkit.getEntity(u);
               if (e != null) {
                  e.remove();
               }
            }

            r.mobs.clear();
            if (r.boss != null) {
               Entity e = Bukkit.getEntity(r.boss);
               if (e != null) {
                  e.remove();
               }

               r.boss = null;
            }
         }
      }

      World w = this.world();
      int x0 = inst.slot * SLOT_SPACING - 100;
      int x1 = inst.slot * SLOT_SPACING + 1500;

      for (Entity e : w.getEntities()) {
         if (!(e instanceof Player) && e.getLocation().getX() >= x0 && e.getLocation().getX() <= x1) {
            e.remove();
         }
      }
   }

   // ------------------------------------------------------------------ fin de partie

   private void end(DungeonInstance inst, boolean win, String reason) {
      if (inst.state != DungeonInstance.State.ENDING) {
         boolean wasRunning = inst.state == DungeonInstance.State.RUNNING;
         inst.state = DungeonInstance.State.ENDING;
         this.hideBossBar(inst);
         if (!wasRunning) {
            this.instances.remove(inst.slot);
            this.clearSlot(inst);
         } else {
            List<Player> present = this.present(inst);
            if (win) {
               this.victory(inst, present);
               Bukkit.getScheduler().runTaskLater(this.plugin, () -> this.finish(inst), 400L);
            } else {
               for (Player p : present) {
                  p.showTitle(Title.title(Text.c("&c✖ Échec"), Text.c("&7" + reason)));
               }

               this.groupMsg(inst, "&c✖ Donjon échoué : " + reason + ". &7Retour dans 5 s.");
               this.plugin.bot().staffLog("⚔ Donjon", (inst.clan == null ? "(admin)" : inst.clan.name()) + " a échoué dans " + Text.strip(inst.def.name) + " (" + reason + ")");
               Bukkit.getScheduler().runTaskLater(this.plugin, () -> this.finish(inst), 100L);
            }
         }
      }
   }

   private void victory(DungeonInstance inst, List<Player> present) {
      long took = System.currentTimeMillis() - inst.startedAt;
      Clan c = inst.clan;
      boolean record = false;
      long serverBest = this.bestTime(inst.def);
      if (c != null) {
         String rk = "records." + inst.def.id + "." + c.id();
         long prev = this.data.getLong(rk, 0L);
         if (prev == 0L || took < prev) {
            this.data.set(rk, took);
            record = prev > 0L;
         }

         this.plugin.clans().addXp(c, inst.def.clanXp);
         this.plugin.clans().log(c, "Donjon terminé : " + Text.strip(inst.def.name) + " en " + Text.formatTime(took / 1000L));
         this.plugin.clans().dirty();
      }

      for (Player p : present) {
         this.plugin.profiles().addXp(p, inst.def.completionXp, "combat");
         long left = this.rewardLeft(p.getUniqueId());
         if (left > 0L) {
            this.plugin.msg(p, "&7Butin de fin déjà récupéré récemment : de nouveau disponible dans &f" + Text.formatTime(left) + "&7.");
         } else {
            List<ItemStack> loot = this.roll(inst.def.finalLoot, this.mutation() != null ? this.mutationLoot : 1.0);

            for (ItemStack it : loot) {
               for (ItemStack rest : p.getInventory().addItem(it).values()) {
                  p.getWorld().dropItemNaturally(p.getLocation(), rest);
               }

               if (it.hasItemMeta() && it.getItemMeta().hasDisplayName()) {
                  p.sendMessage(Text.c("&6  + &f" + it.getAmount() + "x ").append(it.getItemMeta().displayName()));
               } else {
                  p.sendMessage(Text.c("&6  + &f" + it.getAmount() + "x " + fr.windydev.clans.ritual.CustomItems.prettify(it.getType().name())));
               }

               if (this.plugin.items().idOf(it) != null && this.plugin.items().idOf(it).startsWith("grimoire_")) {
                  Bukkit.broadcast(Text.c(this.plugin.settings().prefix + "&d✦ &f" + p.getName() + " &7a trouvé un &d&lgrimoire mythique &7dans " + inst.def.name + "&7 !"));
               }
            }

            this.data.set("rewards." + p.getUniqueId(), System.currentTimeMillis() + this.rewardCooldown * 1000L);
         }

         p.showTitle(Title.title(Text.c("&6✦ Donjon terminé ✦"), Text.c("&7en &f" + Text.formatTime(took / 1000L) + (record ? " &6(record du clan !)" : "") + " &7• retour dans 20 s")));
         p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0F, 1.0F);
      }

      this.saveData();
      Bukkit.broadcast(
         Text.c(
            this.plugin.settings().prefix
               + "&5⚔ &f"
               + (c == null ? "Un groupe" : "Le clan " + c.coloredName())
               + " &fa terminé "
               + inst.def.name
               + " &fen &e"
               + Text.formatTime(took / 1000L)
               + "&f !"
         )
      );
      if (c != null) {
         this.plugin.discord().send("war", "⚔️ Le clan **" + c.name() + "** a terminé le donjon **" + Text.strip(inst.def.name) + "** en " + Text.formatTime(took / 1000L) + " !");
         if (serverBest == 0L || took < serverBest) {
            Bukkit.broadcast(Text.c(this.plugin.settings().prefix + "&6★ Nouveau record du serveur pour " + inst.def.name + " &6: &f" + c.coloredName() + " &6en &e" + Text.formatTime(took / 1000L) + "&6 !"));
            this.plugin.bot().announce("dungeon-record", "\ud83c\udfc6 **Nouveau record** — le clan **" + c.name() + "** a terminé **" + Text.strip(inst.def.name) + "** en **" + Text.formatTime(took / 1000L) + "** !");
         }
      }

      this.plugin.bot().staffLog("⚔ Donjon", (c == null ? "(admin)" : c.name()) + " a terminé " + Text.strip(inst.def.name) + " en " + Text.formatTime(took / 1000L) + " (" + present.size() + " joueurs)");
   }

   private void finish(DungeonInstance inst) {
      this.removeMobs(inst);
      this.hideBars(inst);

      for (UUID u : inst.participants) {
         Player p = Bukkit.getPlayer(u);
         if (p != null) {
            if (p.isDead()) {
               this.pendingRespawn.put(u, this.returnOf(inst, u));
            } else if (this.isDungeon(p.getWorld())) {
               p.teleport(this.returnOf(inst, u));
            }
         }
      }

      this.instances.remove(inst.slot);
      this.clearSlot(inst);
   }

   private Location returnOf(DungeonInstance inst, UUID u) {
      Location l = inst.returns.get(u);
      return l != null && l.getWorld() != null ? l : Bukkit.getWorlds().get(0).getSpawnLocation();
   }

   private void hideBossBar(DungeonInstance inst) {
      if (inst.bossBar != null) {
         for (UUID u : inst.participants) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) {
               p.hideBossBar(inst.bossBar);
            }
         }

         inst.bossBar = null;
      }
   }

   private void hideBars(DungeonInstance inst) {
      this.hideBossBar(inst);
      if (inst.bar != null) {
         for (UUID u : inst.participants) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) {
               p.hideBossBar(inst.bar);
            }
         }
      }
   }

   private void groupMsg(DungeonInstance inst, String msg) {
      for (UUID u : inst.participants) {
         Player p = Bukkit.getPlayer(u);
         if (p != null) {
            this.plugin.msg(p, msg);
         }
      }
   }

   private DungeonInstance instanceOfMob(Entity e) {
      Integer slot = e.getPersistentDataContainer().get(this.keySlot, PersistentDataType.INTEGER);
      return slot == null ? null : this.instances.get(slot);
   }

   private boolean isMob(Entity e) {
      return e != null && e.getPersistentDataContainer().has(this.keySlot, PersistentDataType.INTEGER);
   }

   // ------------------------------------------------------------------ événements

   @EventHandler
   public void onMobDeath(EntityDeathEvent e) {
      LivingEntity dead = e.getEntity();
      if (this.isMob(dead)) {
         PersistentDataContainer pdc = dead.getPersistentDataContainer();
         int level = pdc.getOrDefault(this.keyLevel, PersistentDataType.INTEGER, 1);
         boolean boss = pdc.has(this.keyBoss, PersistentDataType.BYTE);
         if (boss) {
            e.getDrops().clear();
            e.setDroppedExp(0);
            dead.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, dead.getLocation().add(0.0, 1.0, 0.0), 80, 1.0, 1.0, 1.0, 0.4);
         }

         if ("explosif".equals(pdc.get(this.keyAffix, PersistentDataType.STRING))) {
            dead.getWorld().createExplosion(dead.getLocation(), 1.5F, false, false, dead);
         }

         Player killer = dead.getKiller();
         if (killer != null) {
            this.plugin.profiles().addXp(killer, (long)level * this.xpPerMobLevel * (boss ? 10L : 1L), "combat");
            this.plugin.profiles().addSouls(killer, Math.max(1L, (long)level / 2L) * (boss ? 10L : 1L), true);
         }
      }
   }

   @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
   public void onDamage(EntityDamageByEntityEvent e) {
      Entity victim = e.getEntity();
      Entity damager = e.getDamager();
      Entity source = damager;
      boolean projectile = false;
      if (damager instanceof Projectile pr && pr.getShooter() instanceof Entity sh) {
         source = sh;
         projectile = true;
      }

      if (this.isMob(source) && this.isMob(victim)) {
         e.setCancelled(true);
      } else if (victim instanceof Player && source instanceof Player && this.isDungeon(victim.getWorld())) {
         e.setCancelled(true);
      } else {
         if (this.isMob(source) && victim instanceof Player) {
            PersistentDataContainer pdc = source.getPersistentDataContainer();
            double mul = pdc.getOrDefault(projectile ? this.keyPdmg : this.keyDmg, PersistentDataType.DOUBLE, 1.0);
            e.setDamage(e.getDamage() * mul);
            if ("vampirique".equals(pdc.get(this.keyAffix, PersistentDataType.STRING)) && source instanceof LivingEntity le) {
               double max = le.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue();
               le.setHealth(Math.min(max, le.getHealth() + e.getFinalDamage() * 0.3));
            }
         }

         if (this.isMob(victim) && victim instanceof LivingEntity le) {
            PersistentDataContainer pdc = victim.getPersistentDataContainer();
            if ("invocateur".equals(pdc.get(this.keyAffix, PersistentDataType.STRING)) && !pdc.has(this.keyInv, PersistentDataType.BYTE)) {
               double max = le.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue();
               if (le.getHealth() - e.getFinalDamage() < max / 2.0) {
                  pdc.set(this.keyInv, PersistentDataType.BYTE, (byte)1);
                  DungeonInstance inst = this.instanceOfMob(victim);
                  if (inst != null) {
                     for (Room r : inst.currentRooms()) {
                        if (r.mobs.contains(victim.getUniqueId())) {
                           List<EntityType> types = new ArrayList<>(inst.def.mobs.keySet());

                           for (int i = 0; i < 2; i++) {
                              EntityType t = types.get(this.rnd.nextInt(types.size()));
                              this.spawnMob(inst, r, victim.getLocation().add(this.rnd.nextDouble() - 0.5, 0.2, this.rnd.nextDouble() - 0.5), t, inst.def.mobs.get(t), inst.monsterLevel(), false, this.present(inst).size());
                           }

                           victim.getWorld().spawnParticle(Particle.LARGE_SMOKE, victim.getLocation(), 20, 0.5, 0.8, 0.5, 0.02);
                        }
                     }
                  }
               }
            }
         }
      }
   }

   @EventHandler
   public void onTarget(EntityTargetEvent e) {
      if (e.getTarget() != null && this.isMob(e.getEntity()) && this.isMob(e.getTarget())) {
         e.setCancelled(true);
      }
   }

   @EventHandler(ignoreCancelled = true)
   public void onSpawn(CreatureSpawnEvent e) {
      if (this.isDungeon(e.getLocation().getWorld())) {
         SpawnReason r = e.getSpawnReason();
         if (r != SpawnReason.CUSTOM && r != SpawnReason.COMMAND && r != SpawnReason.SPELL && r != SpawnReason.DEFAULT) {
            e.setCancelled(true);
         }
      }
   }

   @EventHandler(priority = EventPriority.HIGH)
   public void onPlayerDeath(PlayerDeathEvent e) {
      Player p = e.getPlayer();
      if (this.isDungeon(p.getWorld())) {
         e.setKeepInventory(true);
         e.getDrops().clear();
         e.setKeepLevel(true);
         e.setDroppedExp(0);
         DungeonInstance inst = this.instanceOf(p.getUniqueId());
         if (inst != null && inst.state == DungeonInstance.State.RUNNING) {
            inst.lives--;
            if (inst.lives < 0) {
               this.groupMsg(inst, "&c☠ " + p.getName() + " est tombé... &4le groupe n'a plus de vies.");
               this.pendingRespawn.put(p.getUniqueId(), this.returnOf(inst, p.getUniqueId()));
               Bukkit.getScheduler().runTask(this.plugin, () -> this.end(inst, false, "plus aucune vie"));
            } else {
               this.groupMsg(inst, "&c☠ " + p.getName() + " est tombé. &7Vies restantes : &c" + inst.lives);
            }
         }
      }
   }

   @EventHandler(priority = EventPriority.HIGHEST)
   public void onRespawn(PlayerRespawnEvent e) {
      Player p = e.getPlayer();
      Location back = this.pendingRespawn.remove(p.getUniqueId());
      if (back != null) {
         e.setRespawnLocation(back);
      } else {
         DungeonInstance inst = this.instanceOf(p.getUniqueId());
         if (inst != null && inst.state == DungeonInstance.State.RUNNING && inst.checkpoint != null) {
            Location at = inst.checkpoint;

            for (Room r : inst.currentRooms()) {
               if (r.state == Room.State.FIGHT || r.state == Room.State.WAITING) {
                  at = r.arrival(this.world());
                  break;
               }
            }

            e.setRespawnLocation(at);
            if (inst.bar != null) {
               Bukkit.getScheduler().runTask(this.plugin, () -> p.showBossBar(inst.bar));
            }
         } else if (this.isDungeon(e.getRespawnLocation().getWorld())) {
            e.setRespawnLocation(Bukkit.getWorlds().get(0).getSpawnLocation());
         }
      }
   }

   @EventHandler
   public void onQuit(PlayerQuitEvent e) {
      Player p = e.getPlayer();
      DungeonInstance inst = this.instanceOf(p.getUniqueId());
      if (inst != null) {
         inst.participants.remove(p.getUniqueId());
         if (inst.state != DungeonInstance.State.LOBBY) {
            this.groupMsg(inst, "&c" + p.getName() + " &7s'est déconnecté et quitte le donjon.");
         }

         if (this.isDungeon(p.getWorld())) {
            p.teleport(this.returnOf(inst, p.getUniqueId()));
         }
      } else if (this.isDungeon(p.getWorld())) {
         p.teleport(Bukkit.getWorlds().get(0).getSpawnLocation());
      }
   }

   @EventHandler
   public void onJoin(PlayerJoinEvent e) {
      Player p = e.getPlayer();
      if (this.isDungeon(p.getWorld()) && this.instanceOf(p.getUniqueId()) == null) {
         Bukkit.getScheduler().runTaskLater(this.plugin, () -> p.teleport(Bukkit.getWorlds().get(0).getSpawnLocation()), 5L);
      }
   }

   @EventHandler(ignoreCancelled = true)
   public void onBreak(BlockBreakEvent e) {
      if (this.isDungeon(e.getBlock().getWorld()) && !this.plugin.bypass().contains(e.getPlayer().getUniqueId())) {
         e.setCancelled(true);
      }
   }

   @EventHandler(ignoreCancelled = true)
   public void onPlace(BlockPlaceEvent e) {
      if (this.isDungeon(e.getBlock().getWorld()) && !this.plugin.bypass().contains(e.getPlayer().getUniqueId())) {
         e.setCancelled(true);
      }
   }

   @EventHandler(ignoreCancelled = true)
   public void onBucket(PlayerBucketEmptyEvent e) {
      if (this.isDungeon(e.getBlock().getWorld()) && !this.plugin.bypass().contains(e.getPlayer().getUniqueId())) {
         e.setCancelled(true);
      }
   }

   @EventHandler
   public void onExplode(EntityExplodeEvent e) {
      if (this.isDungeon(e.getLocation().getWorld())) {
         e.blockList().clear();
      }
   }

   @EventHandler
   public void onIgnite(BlockIgniteEvent e) {
      if (this.isDungeon(e.getBlock().getWorld())) {
         e.setCancelled(true);
      }
   }

   // ------------------------------------------------------------------ commande /donjon

   @Override
   public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
      if (!(sender instanceof Player p)) {
         return true;
      } else {
         String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
         switch (sub) {
            case "rejoindre":
            case "join":
               this.join(p);
               break;
            case "quitter":
            case "leave":
               this.leave(p);
               break;
            case "stop":
               if (p.hasPermission("windyclans.admin")) {
                  for (DungeonInstance inst : new ArrayList<>(this.instances.values())) {
                     this.end(inst, false, "arrêté par un admin");
                  }

                  this.plugin.msg(p, "&7Tous les donjons ont été arrêtés.");
               }
               break;
            case "reset":
               if (p.hasPermission("windyclans.admin")) {
                  Player t = args.length > 1 ? Bukkit.getPlayer(args[1]) : p;
                  if (t != null) {
                     this.data.set("rewards." + t.getUniqueId(), null);
                     this.saveData();
                     this.plugin.msg(p, "&7Butin de fin de nouveau disponible pour &f" + t.getName() + "&7.");
                  }
               }
               break;
            case "boutique":
            case "shop":
               this.shop.menu(p, 0);
               break;
            case "sceau":
               if (p.hasPermission("windyclans.admin") && args.length > 1) {
                  DungeonDef d = this.defs.get(args[1].toLowerCase(Locale.ROOT));
                  ItemStack it = d == null ? null : this.plugin.items().create(d.seal);
                  if (it != null) {
                     p.getInventory().addItem(it);
                  }
               }
               break;
            default:
               this.menu(p);
         }

         return true;
      }
   }

   @Override
   public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
      if (args.length == 1) {
         List<String> l = new ArrayList<>(List.of("rejoindre", "quitter"));
         if (sender.hasPermission("windyclans.admin")) {
            l.addAll(List.of("stop", "reset", "sceau"));
         }

         return l;
      } else {
         return args.length == 2 && args[0].equalsIgnoreCase("sceau") ? new ArrayList<>(this.defs.keySet()) : List.of();
      }
   }

   public int running() {
      return this.instances.size();
   }

   static final class VoidGenerator extends ChunkGenerator {
      @Override
      public Location getFixedSpawnLocation(World world, Random random) {
         return new Location(world, 0.5, 100.0, 0.5);
      }
   }
}
