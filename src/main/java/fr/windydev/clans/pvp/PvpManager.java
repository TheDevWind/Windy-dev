package fr.windydev.clans.pvp;

import fr.windydev.clans.WindyClans;
import fr.windydev.clans.gui.Menu;
import fr.windydev.clans.listener.ProtectionListener;
import fr.windydev.clans.util.ItemBuilder;
import fr.windydev.clans.util.Text;
import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
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
import org.bukkit.OfflinePlayer;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.World.Environment;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.jetbrains.annotations.NotNull;

/**
 * MAJ 1.22.0 : arène PvP (1v1, 2v2, classé). Hors des combats de clans : les relations de clan, la protection
 * des nouveaux et le PvP du spawn ne comptent pas ici. Chaque combattant reçoit un kit donné par le serveur ;
 * son propre stuff est mis de côté (et sauvegardé sur disque) puis rendu à la fin, quoi qu'il arrive.
 */
public class PvpManager implements Listener, CommandExecutor, TabCompleter {
   public static final String WORLD = "windy_pvp";
   private static final int SPACING = 300;
   private static final int Y = 100;
   private static final int RADIUS = 16;

   private final WindyClans plugin;
   private final NamespacedKey kitKey;
   private final Random rnd = new Random();
   private final File configFile;
   private final File dataFile;
   private final File savesFile;
   private final File kitsFile;
   private YamlConfiguration cfg;
   private YamlConfiguration data;
   private YamlConfiguration saves;
   private World world;

   private final Map<String, Kit> kits = new LinkedHashMap<>();
   private final List<UUID> queue1v1 = new ArrayList<>();
   private final List<List<UUID>> queue2v2 = new ArrayList<>();
   private final Map<UUID, Long> queueRanked = new LinkedHashMap<>();
   private final Map<UUID, UUID> duelInvites = new HashMap<>();
   private final Map<UUID, Long> duelInviteAt = new HashMap<>();
   private final Map<UUID, UUID> duoInvites = new HashMap<>();
   private final Map<UUID, UUID> duos = new HashMap<>();
   private final Map<UUID, Match> inMatch = new HashMap<>();
   private final List<Match> matches = new ArrayList<>();
   private final Set<Integer> builtSlots = new HashSet<>();
   /** Téléportations faites par l'arène elle-même (les autres sont bloquées pendant un combat). */
   private final Set<UUID> allowTp = new HashSet<>();
   private final Set<UUID> quitting = new HashSet<>();

   public enum Mode {
      DUEL("Duel", 1, false),
      SOLO("1v1", 1, false),
      DUO("2v2", 2, false),
      RANKED("Classé 1v1", 1, true);

      final String label;
      final int teamSize;
      final boolean ranked;

      Mode(String label, int teamSize, boolean ranked) {
         this.label = label;
         this.teamSize = teamSize;
         this.ranked = ranked;
      }

      String key() {
         return switch (this) {
            case DUEL, SOLO -> "1v1";
            case DUO -> "2v2";
            case RANKED -> "ranked";
         };
      }
   }

   enum State {
      COUNTDOWN,
      FIGHT,
      END
   }

   record Kit(String id, String name, Material icon, ItemStack[] armor, ItemStack offhand, List<ItemStack> items) {
   }

   final class Match {
      final Mode mode;
      final int slot;
      final List<UUID> teamA;
      final List<UUID> teamB;
      final Set<UUID> alive = new HashSet<>();
      final Kit kit;
      State state = State.COUNTDOWN;
      int ticks;

      Match(Mode mode, int slot, List<UUID> a, List<UUID> b, Kit kit) {
         this.mode = mode;
         this.slot = slot;
         this.teamA = a;
         this.teamB = b;
         this.kit = kit;
         this.alive.addAll(a);
         this.alive.addAll(b);
      }

      List<UUID> all() {
         List<UUID> l = new ArrayList<>(this.teamA);
         l.addAll(this.teamB);
         return l;
      }

      boolean sameTeam(UUID x, UUID y) {
         return this.teamA.contains(x) && this.teamA.contains(y) || this.teamB.contains(x) && this.teamB.contains(y);
      }

      Location center() {
         return new Location(PvpManager.this.world(), this.slot * SPACING + 0.5, Y + 1, 0.5);
      }

      boolean teamAlive(List<UUID> team) {
         return team.stream().anyMatch(this.alive::contains);
      }
   }

   public PvpManager(WindyClans plugin) {
      this.plugin = plugin;
      this.kitKey = new NamespacedKey(plugin, "pvp_kit");
      this.configFile = new File(plugin.getDataFolder(), "pvp.yml");
      this.dataFile = new File(plugin.getDataFolder(), "pvp-data.yml");
      this.savesFile = new File(plugin.getDataFolder(), "pvp-sauvegardes.yml");
      this.kitsFile = new File(plugin.getDataFolder(), "pvp-kits.yml");
   }

   // ------------------------------------------------------------------ chargement

   public void load() {
      if (!this.configFile.exists()) {
         this.plugin.saveResource("pvp.yml", false);
      }

      this.cfg = YamlConfiguration.loadConfiguration(this.configFile);
      this.data = YamlConfiguration.loadConfiguration(this.dataFile);
      this.saves = YamlConfiguration.loadConfiguration(this.savesFile);
      this.kits.clear();
      ConfigurationSection ks = this.cfg.getConfigurationSection("kits");
      if (ks != null) {
         for (String id : ks.getKeys(false)) {
            ConfigurationSection s = ks.getConfigurationSection(id);
            if (s != null) {
               try {
                  ItemStack[] armor = new ItemStack[]{
                     this.parse(s.getString("armor.boots")),
                     this.parse(s.getString("armor.leggings")),
                     this.parse(s.getString("armor.chestplate")),
                     this.parse(s.getString("armor.helmet"))
                  };
                  List<ItemStack> items = new ArrayList<>();

                  for (String line : s.getStringList("items")) {
                     ItemStack it = this.parse(line);
                     if (it != null) {
                        items.add(it);
                     }
                  }

                  Material icon = Material.matchMaterial(s.getString("icon", "IRON_SWORD"));
                  this.kits.put(id, new Kit(id, s.getString("name", id), icon == null ? Material.IRON_SWORD : icon, armor, this.parse(s.getString("offhand")), items));
               } catch (Exception var11) {
                  this.plugin.getLogger().warning("pvp.yml : kit '" + id + "' invalide : " + var11.getMessage());
               }
            }
         }
      }

      // kits enregistrés en jeu (/pvp admin kit creer <id>) : copie exacte de l'inventaire de l'admin
      YamlConfiguration custom = YamlConfiguration.loadConfiguration(this.kitsFile);
      for (String id : custom.getKeys(false)) {
         try {
            List<?> raw = custom.getList(id + ".items", List.of());
            List<ItemStack> items = new ArrayList<>();

            for (Object o : raw) {
               if (o instanceof ItemStack it) {
                  items.add(it);
               }
            }

            ItemStack[] armor = new ItemStack[4];
            List<?> ar = custom.getList(id + ".armor", List.of());
            for (int i = 0; i < 4 && i < ar.size(); i++) {
               armor[i] = ar.get(i) instanceof ItemStack it ? it : null;
            }

            Material icon = Material.matchMaterial(custom.getString(id + ".icon", "IRON_SWORD"));
            this.kits.put(id, new Kit(id, custom.getString(id + ".name", id), icon == null ? Material.IRON_SWORD : icon, armor, custom.getItemStack(id + ".offhand"), items));
         } catch (Exception var10) {
            this.plugin.getLogger().warning("pvp-kits.yml : kit '" + id + "' invalide : " + var10.getMessage());
         }
      }

      this.plugin.getLogger().info("Arène PvP : " + this.kits.size() + " kit(s) chargé(s).");
   }

   public void start() {
      Bukkit.getScheduler().runTaskTimer(this.plugin, this::tick, 20L, 10L);
      Bukkit.getScheduler().runTaskLater(this.plugin, this::world, 60L);
   }

   public void shutdown() {
      for (Match m : new ArrayList<>(this.matches)) {
         for (UUID id : m.all()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
               this.restore(p);
            }
         }
      }

      this.matches.clear();
      this.inMatch.clear();
      this.saveData();
   }

   /** « DIAMOND_SWORD:1{sharpness:2,unbreaking:3} » */
   private ItemStack parse(String line) {
      if (line == null || line.isBlank()) {
         return null;
      } else {
         String l = line.trim();
         Map<Enchantment, Integer> ench = new HashMap<>();
         int b = l.indexOf('{');
         if (b >= 0) {
            String inner = l.substring(b + 1, l.lastIndexOf('}'));
            l = l.substring(0, b);

            for (String part : inner.split(",")) {
               String[] kv = part.trim().split(":");
               if (kv.length == 2) {
                  Enchantment e = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(kv[0].trim().toLowerCase(Locale.ROOT)));
                  if (e == null) {
                     throw new IllegalArgumentException("enchantement inconnu " + kv[0]);
                  }

                  ench.put(e, Integer.parseInt(kv[1].trim()));
               }
            }
         }

         String[] mp = l.split(":");
         Material m = Material.matchMaterial(mp[0].trim());
         if (m == null) {
            throw new IllegalArgumentException("objet inconnu " + mp[0]);
         } else {
            ItemStack it = new ItemStack(m, mp.length > 1 ? Integer.parseInt(mp[1].trim()) : 1);
            ench.forEach((e, lvl) -> it.addUnsafeEnchantment(e, lvl));
            return it;
         }
      }
   }

   // ------------------------------------------------------------------ monde et arènes

   private World world() {
      if (this.world == null) {
         this.world = Bukkit.getWorld(WORLD);
         if (this.world == null) {
            this.world = new WorldCreator(WORLD).generator(new VoidGen()).environment(Environment.NORMAL).generateStructures(false).createWorld();
         }

         this.world.setKeepSpawnInMemory(false);
         this.world.setGameRule(GameRule.KEEP_INVENTORY, true);
         this.world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
         this.world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
         this.world.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
         this.world.setGameRule(GameRule.DO_FIRE_TICK, false);
         this.world.setGameRule(GameRule.MOB_GRIEFING, false);
         this.world.setGameRule(GameRule.ANNOUNCE_ADVANCEMENTS, false);
         this.world.setGameRule(GameRule.NATURAL_REGENERATION, true);
         this.world.setTime(6000L);
         this.world.setDifficulty(Difficulty.NORMAL);
      }

      return this.world;
   }

   public boolean isArena(World w) {
      return w != null && w.getName().equals(WORLD);
   }

   private void build(int slot) {
      World w = this.world();
      int cx = slot * SPACING;
      if (this.builtSlots.contains(slot) && w.getBlockAt(cx, Y, 0).getType() == Material.CHISELED_STONE_BRICKS) {
         return;
      }

      for (int x = -RADIUS - 1; x <= RADIUS + 1; x++) {
         for (int z = -RADIUS - 1; z <= RADIUS + 1; z++) {
            boolean wall = Math.abs(x) == RADIUS + 1 || Math.abs(z) == RADIUS + 1;
            Material floor = (x + z) % 7 == 0 ? Material.CRACKED_STONE_BRICKS : ((x * 3 + z) % 5 == 0 ? Material.MOSSY_STONE_BRICKS : Material.STONE_BRICKS);
            if (Math.abs(x) == 12 && z == 0) {
               floor = Math.signum(x) < 0 ? Material.RED_CONCRETE : Material.BLUE_CONCRETE;
            }

            w.getBlockAt(cx + x, Y - 1, z).setType(Material.BEDROCK, false);
            w.getBlockAt(cx + x, Y, z).setType(wall ? Material.POLISHED_ANDESITE : floor, false);

            for (int h = 1; h <= 12; h++) {
               Material m = Material.AIR;
               if (wall) {
                  m = h <= 3 ? Material.STONE_BRICK_WALL : Material.BARRIER;
                  if (h == 1 || h == 2) {
                     m = Material.STONE_BRICKS;
                  }
               } else if (h == 12) {
                  m = Material.BARRIER;
               }

               w.getBlockAt(cx + x, Y + h, z).setType(m, false);
            }
         }
      }

      for (int[] c : new int[][]{{-RADIUS, -RADIUS}, {RADIUS, -RADIUS}, {-RADIUS, RADIUS}, {RADIUS, RADIUS}}) {
         w.getBlockAt(cx + c[0], Y + 1, c[1]).setType(Material.SEA_LANTERN, false);
      }

      w.getBlockAt(cx, Y, 0).setType(Material.CHISELED_STONE_BRICKS, false);
      this.builtSlots.add(slot);
   }

   private int freeSlot() {
      Set<Integer> used = new HashSet<>();
      this.matches.forEach(m -> used.add(m.slot));
      int s = 0;

      while (used.contains(s)) {
         s++;
      }

      return s;
   }

   private Location spawnOf(Match m, UUID id) {
      boolean a = m.teamA.contains(id);
      List<UUID> team = a ? m.teamA : m.teamB;
      int idx = Math.max(0, team.indexOf(id));
      double z = team.size() == 1 ? 0.0 : (idx == 0 ? -2.0 : 2.0);
      Location c = m.center();
      Location l = c.clone().add(a ? -12.0 : 12.0, 0.0, z);
      l.setYaw(a ? -90.0F : 90.0F);
      return l;
   }

   // ------------------------------------------------------------------ files d'attente

   private boolean canQueue(Player p) {
      if (this.inMatch.containsKey(p.getUniqueId())) {
         this.msg(p, "&cTu es déjà en combat.");
         return false;
      } else if (this.inAnyQueue(p.getUniqueId())) {
         this.msg(p, "&cTu es déjà dans une file. &7/pvp quitter pour en sortir.");
         return false;
      } else {
         String w = p.getWorld().getName();
         if (w.equals(WORLD) || w.equals("windy_arenes") || w.equals("windy_donjons") || w.equals("windy_faille")) {
            this.msg(p, "&cImpossible depuis un boss, un donjon ou la Faille.");
            return false;
         } else if (this.plugin.awakening() != null && this.plugin.awakening().running(p)) {
            this.msg(p, "&cImpossible pendant un Éveil.");
            return false;
         } else if (p.isDead() || p.getGameMode() == GameMode.SPECTATOR) {
            return false;
         } else if (this.kits.isEmpty()) {
            this.msg(p, "&cAucun kit n'est configuré (pvp.yml).");
            return false;
         } else {
            return true;
         }
      }
   }

   private boolean inAnyQueue(UUID id) {
      return this.queue1v1.contains(id) || this.queueRanked.containsKey(id) || this.queue2v2.stream().anyMatch(u -> u.contains(id));
   }

   private void leaveQueues(UUID id) {
      this.queue1v1.remove(id);
      this.queueRanked.remove(id);
      Iterator<List<UUID>> it = this.queue2v2.iterator();

      while (it.hasNext()) {
         List<UUID> unit = it.next();
         if (unit.contains(id)) {
            it.remove();

            for (UUID o : unit) {
               Player op = Bukkit.getPlayer(o);
               if (!o.equals(id) && op != null) {
                  this.msg(op, "&7Ton coéquipier a quitté la file 2v2.");
               }
            }
         }
      }
   }

   private void join(Player p, Mode mode) {
      if (!this.canQueue(p)) {
         return;
      }

      UUID id = p.getUniqueId();
      switch (mode) {
         case SOLO -> {
            this.queue1v1.add(id);
            this.msg(p, "&aTu rejoins la file &f1v1&a. &7(" + this.queue1v1.size() + " en attente) &8· &7/pvp quitter");
         }
         case RANKED -> {
            this.queueRanked.put(id, System.currentTimeMillis());
            this.msg(p, "&aTu rejoins la file &6classée&a (" + this.elo(id) + " points, " + this.tier(this.elo(id)) + "&a). &8· &7/pvp quitter");
         }
         case DUO -> {
            UUID mate = this.duos.get(id);
            Player mp = mate == null ? null : Bukkit.getPlayer(mate);
            if (mp != null && this.duos.get(mate) != null && this.duos.get(mate).equals(id)) {
               if (!this.canQueue(mp)) {
                  this.msg(p, "&c" + mp.getName() + " ne peut pas entrer dans la file pour l'instant.");
                  return;
               }

               this.queue2v2.add(new ArrayList<>(List.of(id, mate)));
               this.msg(p, "&aVous rejoignez la file &f2v2&a avec &f" + mp.getName() + "&a.");
               this.msg(mp, "&a" + p.getName() + " vous inscrit en file &f2v2&a.");
            } else {
               this.queue2v2.add(new ArrayList<>(List.of(id)));
               this.msg(p, "&aTu rejoins la file &f2v2&a seul : un coéquipier te sera trouvé. &8· &7/pvp duo <joueur> pour venir à deux");
            }
         }
         default -> {
         }
      }

      p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.7F, 1.6F);
      this.matchmake();
   }

   private void matchmake() {
      this.queue1v1.removeIf(u -> Bukkit.getPlayer(u) == null);
      this.queueRanked.keySet().removeIf(u -> Bukkit.getPlayer(u) == null);
      this.queue2v2.removeIf(unit -> unit.stream().anyMatch(u -> Bukkit.getPlayer(u) == null));

      while (this.queue1v1.size() >= 2) {
         UUID a = this.queue1v1.remove(0);
         UUID b = this.queue1v1.remove(0);
         this.startMatch(Mode.SOLO, List.of(a), List.of(b));
      }

      // classé : les deux plus proches en points, la marge grandit avec l'attente
      boolean found = true;

      while (found && this.queueRanked.size() >= 2) {
         found = false;
         List<UUID> ids = new ArrayList<>(this.queueRanked.keySet());
         long now = System.currentTimeMillis();
         UUID bestA = null;
         UUID bestB = null;
         int bestDiff = Integer.MAX_VALUE;

         for (int i = 0; i < ids.size(); i++) {
            for (int j = i + 1; j < ids.size(); j++) {
               UUID x = ids.get(i);
               UUID y = ids.get(j);
               int diff = Math.abs(this.elo(x) - this.elo(y));
               long waited = Math.min(now - this.queueRanked.get(x), now - this.queueRanked.get(y)) / 1000L;
               int window = this.cfg.getInt("ranked.window", 150) + (int)(waited / 10L) * this.cfg.getInt("ranked.window-grow", 50);
               if (diff <= window && diff < bestDiff) {
                  bestDiff = diff;
                  bestA = x;
                  bestB = y;
               }
            }
         }

         if (bestA != null) {
            this.queueRanked.remove(bestA);
            this.queueRanked.remove(bestB);
            this.startMatch(Mode.RANKED, List.of(bestA), List.of(bestB));
            found = true;
         }
      }

      // 2v2 : duos et joueurs seuls
      while (true) {
         List<List<UUID>> duoUnits = new ArrayList<>();
         List<List<UUID>> solos = new ArrayList<>();

         for (List<UUID> u : this.queue2v2) {
            (u.size() == 2 ? duoUnits : solos).add(u);
         }

         List<UUID> a;
         List<UUID> b;
         if (duoUnits.size() >= 2) {
            a = duoUnits.get(0);
            b = duoUnits.get(1);
            this.queue2v2.remove(a);
            this.queue2v2.remove(b);
         } else if (duoUnits.size() == 1 && solos.size() >= 2) {
            a = duoUnits.get(0);
            b = List.of(solos.get(0).get(0), solos.get(1).get(0));
            this.queue2v2.remove(a);
            this.queue2v2.remove(solos.get(0));
            this.queue2v2.remove(solos.get(1));
         } else {
            if (solos.size() < 4) {
               return;
            }

            a = List.of(solos.get(0).get(0), solos.get(1).get(0));
            b = List.of(solos.get(2).get(0), solos.get(3).get(0));

            for (int i = 0; i < 4; i++) {
               this.queue2v2.remove(solos.get(i));
            }
         }

         this.startMatch(Mode.DUO, new ArrayList<>(a), new ArrayList<>(b));
      }
   }

   // ------------------------------------------------------------------ combats

   private Kit pickKit(Mode mode) {
      List<String> allowed = this.cfg.getStringList("modes." + mode.key() + ".kits");
      List<Kit> pool = new ArrayList<>();

      for (String k : allowed) {
         Kit kit = this.kits.get(k);
         if (kit != null) {
            pool.add(kit);
         }
      }

      if (pool.isEmpty()) {
         pool.addAll(this.kits.values());
      }

      return pool.get(this.rnd.nextInt(pool.size()));
   }

   private void startMatch(Mode mode, List<UUID> a, List<UUID> b) {
      List<UUID> all = new ArrayList<>(a);
      all.addAll(b);

      for (UUID id : all) {
         Player p = Bukkit.getPlayer(id);
         if (p == null || p.isDead()) {
            // quelqu'un est parti entre-temps : les autres retournent en file
            for (UUID o : all) {
               if (!o.equals(id) && Bukkit.getPlayer(o) != null) {
                  this.msg(Bukkit.getPlayer(o), "&7Un adversaire est parti, retour dans la file.");
                  this.join(Bukkit.getPlayer(o), mode == Mode.DUEL ? Mode.SOLO : mode);
               }
            }

            return;
         }
      }

      int slot = this.freeSlot();
      this.build(slot);
      Match m = new Match(mode, slot, a, b, mode == Mode.DUEL ? this.pickKit(Mode.SOLO) : this.pickKit(mode));
      this.matches.add(m);
      String vs = this.names(a) + " &7contre &c" + this.names(b);

      for (UUID id : all) {
         Player p = Bukkit.getPlayer(id);
         this.inMatch.put(id, m);
         this.duelInvites.remove(id);
         this.leaveQueues(id);
         this.saveState(p);
         this.allowTp.add(id);
         p.teleport(this.spawnOf(m, id));
         this.allowTp.remove(id);
         this.equip(p, m.kit);
         this.msg(p, "&6⚔ " + mode.label + " &8· &9" + vs + " &8· &7kit &f" + m.kit.name());
         if (mode.ranked) {
            UUID opp = m.teamA.contains(id) ? m.teamB.get(0) : m.teamA.get(0);
            this.msg(p, "&7Classé : toi &f" + this.elo(id) + " &7pts, adversaire &f" + this.elo(opp) + " &7pts.");
         }
      }
   }

   private String names(List<UUID> team) {
      List<String> n = new ArrayList<>();

      for (UUID id : team) {
         OfflinePlayer o = Bukkit.getOfflinePlayer(id);
         n.add(o.getName() == null ? "?" : o.getName());
      }

      return String.join(" & ", n);
   }

   private void equip(Player p, Kit kit) {
      p.getInventory().clear();
      p.getActivePotionEffects().forEach(e -> p.removePotionEffect(e.getType()));
      p.setGameMode(GameMode.SURVIVAL);
      p.setFireTicks(0);
      p.setFallDistance(0.0F);
      ItemStack[] armor = new ItemStack[4];

      for (int i = 0; i < 4; i++) {
         armor[i] = this.tag(kit.armor()[i]);
      }

      p.getInventory().setArmorContents(armor);
      p.getInventory().setItemInOffHand(this.tag(kit.offhand()));
      int slot = 0;

      for (ItemStack it : kit.items()) {
         if (slot < 36) {
            p.getInventory().setItem(slot++, this.tag(it));
         }
      }

      this.heal(p);
      p.updateInventory();
   }

   private ItemStack tag(ItemStack it) {
      if (it == null || it.getType().isAir()) {
         return null;
      } else {
         ItemStack c = it.clone();
         ItemMeta meta = c.getItemMeta();
         if (meta != null) {
            meta.getPersistentDataContainer().set(this.kitKey, PersistentDataType.BYTE, (byte)1);
            c.setItemMeta(meta);
         }

         return c;
      }
   }

   private boolean isKitItem(ItemStack it) {
      return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(this.kitKey, PersistentDataType.BYTE);
   }

   private void heal(Player p) {
      AttributeInstance max = p.getAttribute(Attribute.MAX_HEALTH);
      p.setHealth(max == null ? 20.0 : max.getValue());
      p.setFoodLevel(20);
      p.setSaturation(10.0F);
      p.setAbsorptionAmount(0.0);
   }

   private void tick() {
      this.matchmake();
      long now = System.currentTimeMillis();
      Iterator<Map.Entry<UUID, Long>> inv = this.duelInviteAt.entrySet().iterator();

      while (inv.hasNext()) {
         Map.Entry<UUID, Long> en = inv.next();
         if (now - en.getValue() > 60000L) {
            this.duelInvites.remove(en.getKey());
            inv.remove();
         }
      }

      int countdown = Math.max(1, this.cfg.getInt("settings.countdown-seconds", 5)) * 2;
      int max = Math.max(30, this.cfg.getInt("settings.max-duration-seconds", 300)) * 2;

      for (Match m : new ArrayList<>(this.matches)) {
         m.ticks++;
         if (m.state == State.COUNTDOWN) {
            int left = (countdown - m.ticks + 1) / 2;
            if (m.ticks % 2 == 1 && left > 0) {
               this.title(m, "&e" + left, "&7Prépare-toi");
               this.sound(m, Sound.BLOCK_NOTE_BLOCK_HAT, 1.2F);
            }

            if (m.ticks >= countdown) {
               m.state = State.FIGHT;
               m.ticks = 0;
               this.title(m, "&c⚔ Combat !", "");
               this.sound(m, Sound.ENTITY_ENDER_DRAGON_GROWL, 1.4F);
            }
         } else if (m.state == State.FIGHT) {
            for (UUID id : m.all()) {
               Player p = Bukkit.getPlayer(id);
               if (p != null && m.alive.contains(id) && (!this.isArena(p.getWorld()) || p.getLocation().getY() < Y - 3)) {
                  this.eliminate(m, p, null);
               }
            }

            if (m.state == State.FIGHT && m.ticks % 20 == 0) {
               int left = (max - m.ticks) / 2;
               if (left == 60 || left == 30 || left == 10) {
                  this.all(m, "&7Fin du combat dans &f" + left + " s&7 (égalité si personne ne gagne).");
               }
            }

            if (m.state == State.FIGHT && m.ticks >= max) {
               this.finish(m, null);
            }
         }
      }
   }

   private void title(Match m, String title, String sub) {
      for (UUID id : m.all()) {
         Player p = Bukkit.getPlayer(id);
         if (p != null) {
            p.showTitle(Title.title(Text.c(title), Text.c(sub), Times.times(Duration.ZERO, Duration.ofMillis(900L), Duration.ofMillis(200L))));
         }
      }
   }

   private void sound(Match m, Sound s, float pitch) {
      for (UUID id : m.all()) {
         Player p = Bukkit.getPlayer(id);
         if (p != null) {
            p.playSound(p.getLocation(), s, 0.8F, pitch);
         }
      }
   }

   private void all(Match m, String text) {
      for (UUID id : m.all()) {
         Player p = Bukkit.getPlayer(id);
         if (p != null) {
            this.msg(p, text);
         }
      }
   }

   private void eliminate(Match m, Player p, Player killer) {
      if (!m.alive.remove(p.getUniqueId())) {
         return;
      }

      boolean present = p.isOnline() && !p.isDead() && !this.quitting.contains(p.getUniqueId());
      if (present) {
         this.heal(p);
         p.getActivePotionEffects().forEach(e -> p.removePotionEffect(e.getType()));
         p.getInventory().clear();
         p.setFireTicks(0);
         p.setGameMode(GameMode.SPECTATOR);
         if (this.isArena(p.getWorld())) {
            this.allowTp.add(p.getUniqueId());
            p.teleport(m.center().clone().add(0.0, 6.0, 0.0));
            this.allowTp.remove(p.getUniqueId());
         }
      }

      this.all(m, killer == null ? "&c☠ &f" + p.getName() + " &7est éliminé." : "&c☠ &f" + p.getName() + " &7est éliminé par &f" + killer.getName() + "&7.");
      p.getWorld().playSound(p.getLocation(), Sound.ENTITY_PLAYER_HURT, 1.0F, 0.6F);
      boolean aAlive = m.teamAlive(m.teamA);
      boolean bAlive = m.teamAlive(m.teamB);
      if (!aAlive || !bAlive) {
         this.finish(m, aAlive ? m.teamA : (bAlive ? m.teamB : null));
      }
   }

   private void finish(Match m, List<UUID> winners) {
      if (m.state == State.END) {
         return;
      }

      m.state = State.END;
      List<UUID> losers = winners == null ? null : (winners == m.teamA ? m.teamB : m.teamA);
      if (winners == null) {
         this.title(m, "&7Égalité", "&8Personne ne l'emporte");
      } else {
         for (UUID id : m.all()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
               boolean won = winners.contains(id);
               p.showTitle(Title.title(Text.c(won ? "&6✦ Victoire" : "&cDéfaite"), Text.c("&7" + m.mode.label), Times.times(Duration.ZERO, Duration.ofSeconds(2L), Duration.ofMillis(500L))));
               p.playSound(p.getLocation(), won ? Sound.UI_TOAST_CHALLENGE_COMPLETE : Sound.ENTITY_VILLAGER_NO, 0.8F, 1.0F);
            }
         }

         String key = "stats." + m.mode.key();

         for (UUID id : winners) {
            this.data.set(id + "." + key + ".wins", this.data.getInt(id + "." + key + ".wins") + 1);
            this.data.set(id + ".name", Bukkit.getOfflinePlayer(id).getName());
         }

         for (UUID id : losers) {
            this.data.set(id + "." + key + ".losses", this.data.getInt(id + "." + key + ".losses") + 1);
            this.data.set(id + ".name", Bukkit.getOfflinePlayer(id).getName());
         }

         if (m.mode.ranked) {
            UUID w = winners.get(0);
            UUID l = losers.get(0);
            int ew = this.elo(w);
            int el = this.elo(l);
            double expected = 1.0 / (1.0 + Math.pow(10.0, (el - ew) / 400.0));
            int k = this.cfg.getInt("ranked.k-factor", 32);
            int delta = Math.max(1, (int)Math.round(k * (1.0 - expected)));
            this.data.set(w + ".elo", ew + delta);
            this.data.set(l + ".elo", Math.max(0, el - delta));
            Player pw = Bukkit.getPlayer(w);
            Player pl = Bukkit.getPlayer(l);
            if (pw != null) {
               this.msg(pw, "&6Classé : &a+" + delta + " &7points &8(" + (ew + delta) + ", " + this.tier(ew + delta) + "&8)");
            }

            if (pl != null) {
               this.msg(pl, "&6Classé : &c-" + delta + " &7points &8(" + Math.max(0, el - delta) + ", " + this.tier(Math.max(0, el - delta)) + "&8)");
            }
         }

         this.saveData();
         if (this.cfg.getBoolean("settings.broadcast", true)) {
            Bukkit.broadcast(Text.c(this.plugin.settings().prefix + "&6⚔ " + m.mode.label + " &8· &a" + this.names(winners) + " &7bat &c" + this.names(losers)));
         }
      }

      Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
         for (UUID id : m.all()) {
            this.inMatch.remove(id);
            Player p = Bukkit.getPlayer(id);
            if (p != null && !p.isDead()) {
               this.restore(p);
            }
         }

         this.matches.remove(m);
      }, 60L);
   }

   // ------------------------------------------------------------------ sauvegarde du stuff des joueurs

   private void saveState(Player p) {
      String k = p.getUniqueId().toString();
      this.saves.set(k + ".name", p.getName());
      this.saves.set(k + ".location", p.getLocation());
      this.saves.set(k + ".inventory", new ArrayList<>(java.util.Arrays.asList(p.getInventory().getContents())));
      this.saves.set(k + ".health", p.getHealth());
      this.saves.set(k + ".food", p.getFoodLevel());
      this.saves.set(k + ".saturation", (double)p.getSaturation());
      this.saves.set(k + ".level", p.getLevel());
      this.saves.set(k + ".exp", (double)p.getExp());
      this.saves.set(k + ".gamemode", p.getGameMode().name());
      this.saves.set(k + ".effects", new ArrayList<>(p.getActivePotionEffects()));
      this.saves.set(k + ".fire", p.getFireTicks());
      this.saveSaves();
   }

   /** Rend au joueur son stuff, sa position et son état d'avant le combat. */
   private void restore(Player p) {
      String k = p.getUniqueId().toString();
      this.stripKit(p);
      if (!this.saves.isConfigurationSection(k)) {
         if (this.isArena(p.getWorld())) {
            this.allowTp.add(p.getUniqueId());
            p.teleport(this.fallback());
            this.allowTp.remove(p.getUniqueId());
         }

         return;
      }

      p.getInventory().clear();
      p.getActivePotionEffects().forEach(e -> p.removePotionEffect(e.getType()));
      GameMode gm = GameMode.SURVIVAL;

      try {
         gm = GameMode.valueOf(this.saves.getString(k + ".gamemode", "SURVIVAL"));
      } catch (IllegalArgumentException var9) {
      }

      p.setGameMode(gm);
      Location l = this.saves.getLocation(k + ".location");
      this.allowTp.add(p.getUniqueId());
      p.teleport(l != null && l.getWorld() != null ? l : this.fallback());
      this.allowTp.remove(p.getUniqueId());
      List<?> inv = this.saves.getList(k + ".inventory", List.of());
      ItemStack[] contents = new ItemStack[p.getInventory().getContents().length];

      for (int i = 0; i < contents.length && i < inv.size(); i++) {
         contents[i] = inv.get(i) instanceof ItemStack it ? it : null;
      }

      p.getInventory().setContents(contents);
      AttributeInstance max = p.getAttribute(Attribute.MAX_HEALTH);
      double mh = max == null ? 20.0 : max.getValue();
      p.setHealth(Math.max(1.0, Math.min(mh, this.saves.getDouble(k + ".health", mh))));
      p.setFoodLevel(this.saves.getInt(k + ".food", 20));
      p.setSaturation((float)this.saves.getDouble(k + ".saturation", 5.0));
      p.setLevel(this.saves.getInt(k + ".level", p.getLevel()));
      p.setExp((float)Math.max(0.0, Math.min(0.999, this.saves.getDouble(k + ".exp", 0.0))));
      p.setFireTicks(0);

      for (Object o : this.saves.getList(k + ".effects", List.of())) {
         if (o instanceof PotionEffect pe) {
            p.addPotionEffect(pe);
         }
      }

      p.updateInventory();
      this.saves.set(k, null);
      this.saveSaves();
   }

   private Location fallback() {
      Location l = this.plugin.spawns() == null ? null : this.plugin.spawns().get();
      return l != null ? l : Bukkit.getWorlds().get(0).getSpawnLocation().add(0.5, 0.0, 0.5);
   }

   /** Retire tout objet de kit qui traînerait (sécurité : rien du kit ne doit sortir de l'arène). */
   private void stripKit(Player p) {
      ItemStack[] c = p.getInventory().getContents();
      boolean changed = false;

      for (int i = 0; i < c.length; i++) {
         if (this.isKitItem(c[i])) {
            c[i] = null;
            changed = true;
         }
      }

      if (changed) {
         p.getInventory().setContents(c);
      }

      if (p.getItemOnCursor() != null && this.isKitItem(p.getItemOnCursor())) {
         p.setItemOnCursor(null);
      }
   }

   private void saveSaves() {
      try {
         this.saves.save(this.savesFile);
      } catch (Exception var2) {
         this.plugin.getLogger().warning("pvp-sauvegardes.yml : " + var2.getMessage());
      }
   }

   private void saveData() {
      try {
         this.data.save(this.dataFile);
      } catch (Exception var2) {
         this.plugin.getLogger().warning("pvp-data.yml : " + var2.getMessage());
      }
   }

   // ------------------------------------------------------------------ classement

   private int elo(UUID id) {
      return this.data.getInt(id + ".elo", this.cfg.getInt("ranked.start", 1000));
   }

   private String tier(int elo) {
      ConfigurationSection t = this.cfg.getConfigurationSection("ranked.tiers");
      String best = "&7Sans rang";
      int bestMin = Integer.MIN_VALUE;
      if (t != null) {
         for (String k : t.getKeys(false)) {
            int min = t.getInt(k);
            if (elo >= min && min > bestMin) {
               bestMin = min;
               best = k;
            }
         }
      }

      return best;
   }

   private void top(CommandSender s) {
      List<String[]> rows = new ArrayList<>();

      for (String k : this.data.getKeys(false)) {
         if (this.data.contains(k + ".elo")) {
            rows.add(new String[]{this.data.getString(k + ".name", "?"), String.valueOf(this.data.getInt(k + ".elo"))});
         }
      }

      rows.sort((x, y) -> Integer.parseInt(y[1]) - Integer.parseInt(x[1]));
      this.msg(s, "&6⚔ Classement PvP &8(1v1 classé)");
      if (rows.isEmpty()) {
         s.sendMessage(Text.c("&7  Personne n'a encore joué en classé."));
      }

      for (int i = 0; i < Math.min(10, rows.size()); i++) {
         int e = Integer.parseInt(rows.get(i)[1]);
         s.sendMessage(Text.c("&e  " + (i + 1) + ". &f" + rows.get(i)[0] + " &8- &f" + e + " &7pts " + this.tier(e)));
      }
   }

   private void stats(CommandSender s, OfflinePlayer o) {
      String k = o.getUniqueId().toString();
      this.msg(s, "&6⚔ PvP de &f" + o.getName());

      for (Mode m : new Mode[]{Mode.SOLO, Mode.DUO, Mode.RANKED}) {
         int w = this.data.getInt(k + ".stats." + m.key() + ".wins");
         int l = this.data.getInt(k + ".stats." + m.key() + ".losses");
         s.sendMessage(Text.c("&7  " + m.label + " : &a" + w + " V &8/ &c" + l + " D"));
      }

      int e = this.elo(o.getUniqueId());
      s.sendMessage(Text.c("&7  Classé : &f" + e + " &7pts " + this.tier(e)));
   }

   // ------------------------------------------------------------------ menu

   public void openMenu(Player p) {
      Menu m = new Menu(p, "pvp", 3, "&fArène PvP");
      UUID id = p.getUniqueId();
      int e = this.elo(id);
      m.set(10, ItemBuilder.of(Material.IRON_SWORD).name("&f⚔ 1v1").lore("&7Un contre un, kit donné.", "&7En file : &f" + this.queue1v1.size(), "", "&eClic : rejoindre la file").build(), ev -> {
         p.closeInventory();
         this.join(p, Mode.SOLO);
      });
      m.set(12, ItemBuilder.of(Material.SHIELD).name("&f⚔ 2v2").lore("&7Deux contre deux, kit donné.", "&7Seul : un coéquipier t'est trouvé.", "&7À deux : &f/pvp duo <joueur>", "", "&eClic : rejoindre la file").build(), ev -> {
         p.closeInventory();
         this.join(p, Mode.DUO);
      });
      m.set(14, ItemBuilder.of(Material.DIAMOND_SWORD).name("&6⚔ Classé 1v1").lore("&7Gagne ou perds des points.", "&7Toi : &f" + e + " &7pts " + this.tier(e), "", "&eClic : rejoindre la file").glow(true).build(), ev -> {
         p.closeInventory();
         this.join(p, Mode.RANKED);
      });
      m.set(16, ItemBuilder.of(Material.GOLDEN_HELMET).name("&e🏆 Classement").lore("&7Les 10 meilleurs en classé", "&7et tes statistiques.").build(), ev -> {
         p.closeInventory();
         this.top(p);
         this.stats(p, p);
      });
      if (this.inAnyQueue(id)) {
         m.set(22, ItemBuilder.of(Material.BARRIER).name("&cQuitter la file").build(), ev -> {
            p.closeInventory();
            this.leaveQueues(id);
            this.msg(p, "&7Tu quittes la file d'attente.");
         });
      }

      m.fillBorder();
      m.open(p);
   }

   // ------------------------------------------------------------------ commande

   private void msg(CommandSender s, String t) {
      this.plugin.msg(s, t);
   }

   public boolean onCommand(@NotNull CommandSender s, @NotNull Command cmd, @NotNull String label, @NotNull String[] args) {
      if (label.equalsIgnoreCase("duel") && args.length == 1 && Bukkit.getPlayerExact(args[0]) != null) {
         args = new String[]{"duel", args[0]};
      }

      String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
      if (sub.equals("admin")) {
         return this.admin(s, args);
      } else if (sub.equals("classement") || sub.equals("top")) {
         this.top(s);
         return true;
      } else if (sub.equals("stats")) {
         OfflinePlayer o = args.length > 1 ? Bukkit.getOfflinePlayer(args[1]) : (s instanceof Player p ? p : null);
         if (o == null) {
            this.msg(s, "&c/pvp stats <joueur>");
         } else {
            this.stats(s, o);
         }

         return true;
      } else if (!(s instanceof Player p)) {
         this.msg(s, "&cCommande réservée aux joueurs.");
         return true;
      } else {
         UUID id = p.getUniqueId();
         switch (sub) {
            case "" -> this.openMenu(p);
            case "1v1", "solo" -> this.join(p, Mode.SOLO);
            case "2v2" -> this.join(p, Mode.DUO);
            case "ranked", "classe", "classé" -> this.join(p, Mode.RANKED);
            case "quitter", "leave", "abandon" -> {
               Match m = this.inMatch.get(id);
               if (m != null) {
                  if (m.state == State.FIGHT || m.state == State.COUNTDOWN) {
                     this.msg(p, "&7Tu abandonnes le combat.");
                     if (m.state == State.COUNTDOWN) {
                        m.state = State.FIGHT;
                     }

                     this.eliminate(m, p, null);
                  }
               } else if (this.inAnyQueue(id)) {
                  this.leaveQueues(id);
                  this.msg(p, "&7Tu quittes la file d'attente.");
               } else {
                  this.msg(p, "&7Tu n'es dans aucune file.");
               }
            }
            case "duel" -> {
               Player t = args.length > 1 ? Bukkit.getPlayerExact(args[1]) : null;
               if (t == null || t == p) {
                  this.msg(p, "&c/pvp duel <joueur en ligne>");
               } else if (this.canQueue(p)) {
                  this.duelInvites.put(t.getUniqueId(), id);
                  this.duelInviteAt.put(t.getUniqueId(), System.currentTimeMillis());
                  this.msg(p, "&aDéfi envoyé à &f" + t.getName() + "&a (60 s).");
                  t.sendMessage(
                     Text.c(this.plugin.settings().prefix + "&6⚔ " + p.getName() + " &7te défie en duel (kit donné, sans classement). ")
                        .append(
                           Text.c("&a[Accepter]")
                              .clickEvent(ClickEvent.runCommand("/pvp accepter"))
                              .hoverEvent(HoverEvent.showText(Component.text("Accepter le duel")))
                        )
                  );
               }
            }
            case "accepter", "accept" -> {
               UUID from = this.duelInvites.remove(id);
               Player f = from == null ? null : Bukkit.getPlayer(from);
               if (f == null) {
                  this.msg(p, "&7Aucun défi en attente.");
               } else if (this.canQueue(p) && this.canQueue(f)) {
                  this.startMatch(Mode.DUEL, List.of(from), List.of(id));
               }
            }
            case "duo" -> {
               Player t = args.length > 1 ? Bukkit.getPlayerExact(args[1]) : null;
               if (t == null || t == p) {
                  this.msg(p, "&c/pvp duo <joueur en ligne> &7: faire équipe en 2v2");
               } else if (id.equals(this.duoInvites.get(t.getUniqueId())) || t.getUniqueId().equals(this.duoInvites.get(id))) {
                  this.duoInvites.remove(id);
                  this.duoInvites.remove(t.getUniqueId());
                  this.duos.put(id, t.getUniqueId());
                  this.duos.put(t.getUniqueId(), id);
                  this.msg(p, "&aTu fais équipe avec &f" + t.getName() + "&a. &7/pvp 2v2 pour entrer en file à deux.");
                  this.msg(t, "&aTu fais équipe avec &f" + p.getName() + "&a. &7/pvp 2v2 pour entrer en file à deux.");
               } else {
                  this.duoInvites.put(t.getUniqueId(), id);
                  this.msg(p, "&aInvitation d'équipe envoyée à &f" + t.getName() + "&a.");
                  t.sendMessage(
                     Text.c(this.plugin.settings().prefix + "&f" + p.getName() + " &7veut faire équipe avec toi en 2v2. ")
                        .append(Text.c("&a[Accepter]").clickEvent(ClickEvent.runCommand("/pvp duo " + p.getName())))
                  );
               }
            }
            default -> this.help(p);
         }

         return true;
      }
   }

   private void help(CommandSender s) {
      this.msg(s, "&6⚔ Arène PvP &7(kit donné par le serveur, ton stuff est mis de côté et rendu)");
      s.sendMessage(Text.c("&e  /pvp &7: menu"));
      s.sendMessage(Text.c("&e  /pvp 1v1 &8· &e/pvp 2v2 &8· &e/pvp classe &7: rejoindre une file"));
      s.sendMessage(Text.c("&e  /pvp duo <joueur> &7: faire équipe en 2v2"));
      s.sendMessage(Text.c("&e  /pvp duel <joueur> &7: défier quelqu'un"));
      s.sendMessage(Text.c("&e  /pvp quitter &7: quitter la file ou abandonner"));
      s.sendMessage(Text.c("&e  /pvp classement &8· &e/pvp stats [joueur]"));
   }

   private boolean admin(CommandSender s, String[] args) {
      if (!s.hasPermission("windyclans.admin")) {
         this.msg(s, "&cRéservé aux admins.");
         return true;
      } else if (args.length >= 4 && args[1].equalsIgnoreCase("kit") && args[2].equalsIgnoreCase("creer") && s instanceof Player p) {
         String id = args[3].toLowerCase(Locale.ROOT);
         YamlConfiguration custom = YamlConfiguration.loadConfiguration(this.kitsFile);
         List<ItemStack> items = new ArrayList<>();

         for (int i = 0; i < 36; i++) {
            ItemStack it = p.getInventory().getItem(i);
            if (it != null && !it.getType().isAir()) {
               items.add(it.clone());
            }
         }

         custom.set(id + ".name", args.length > 4 ? String.join(" ", java.util.Arrays.copyOfRange(args, 4, args.length)) : id);
         custom.set(id + ".icon", items.isEmpty() ? "IRON_SWORD" : items.get(0).getType().name());
         custom.set(id + ".items", items);
         custom.set(id + ".armor", new ArrayList<>(java.util.Arrays.asList(p.getInventory().getArmorContents())));
         custom.set(id + ".offhand", p.getInventory().getItemInOffHand());

         try {
            custom.save(this.kitsFile);
         } catch (Exception var8) {
            this.msg(s, "&cEnregistrement impossible : " + var8.getMessage());
            return true;
         }

         this.load();
         this.msg(s, "&aKit &f" + id + "&a enregistré depuis ton inventaire. Ajoute-le à un mode dans pvp.yml (modes.<mode>.kits), puis &f/clanadmin reload&a.");
         return true;
      } else if (args.length >= 4 && args[1].equalsIgnoreCase("kit") && args[2].equalsIgnoreCase("supprimer")) {
         YamlConfiguration custom = YamlConfiguration.loadConfiguration(this.kitsFile);
         custom.set(args[3].toLowerCase(Locale.ROOT), null);

         try {
            custom.save(this.kitsFile);
         } catch (Exception var7) {
         }

         this.load();
         this.msg(s, "&7Kit supprimé (seuls les kits créés en jeu peuvent l'être ; ceux de pvp.yml se retirent dans le fichier).");
         return true;
      } else if (args.length >= 2 && args[1].equalsIgnoreCase("kits")) {
         this.msg(s, "&7Kits : &f" + String.join(", ", this.kits.keySet()));
         return true;
      } else if (args.length >= 3 && args[1].equalsIgnoreCase("reset")) {
         OfflinePlayer o = Bukkit.getOfflinePlayer(args[2]);
         this.data.set(o.getUniqueId().toString(), null);
         this.saveData();
         this.msg(s, "&7Statistiques PvP de &f" + o.getName() + "&7 remises à zéro.");
         return true;
      } else if (args.length >= 2 && args[1].equalsIgnoreCase("info")) {
         this.msg(s, "&7Combats en cours : &f" + this.matches.size() + " &8· &7files : 1v1 &f" + this.queue1v1.size() + "&7, 2v2 &f" + this.queue2v2.size() + "&7, classé &f" + this.queueRanked.size());
         return true;
      } else {
         this.msg(s, "&e/pvp admin kit creer <id> [nom] &7(depuis ton inventaire) &8· &e/pvp admin kit supprimer <id> &8· &e/pvp admin kits &8· &e/pvp admin reset <joueur> &8· &e/pvp admin info");
         return true;
      }
   }

   public List<String> onTabComplete(@NotNull CommandSender s, @NotNull Command c, @NotNull String l, @NotNull String[] args) {
      List<String> out = new ArrayList<>();
      if (args.length == 1) {
         out.addAll(List.of("1v1", "2v2", "classe", "duo", "duel", "accepter", "quitter", "classement", "stats"));
         if (s.hasPermission("windyclans.admin")) {
            out.add("admin");
         }
      } else if (args.length == 2 && List.of("duo", "duel", "stats").contains(args[0].toLowerCase(Locale.ROOT))) {
         Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
      } else if (args.length == 2 && args[0].equalsIgnoreCase("admin")) {
         out.addAll(List.of("kit", "kits", "reset", "info"));
      } else if (args.length == 3 && args[0].equalsIgnoreCase("admin") && args[1].equalsIgnoreCase("kit")) {
         out.addAll(List.of("creer", "supprimer"));
      }

      String pre = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
      out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(pre));
      return out;
   }

   // ------------------------------------------------------------------ règles de l'arène

   /**
    * Priorité HIGHEST sans ignoreCancelled : passe après les règles de clan, du spawn et de la protection des nouveaux,
    * qui ne s'appliquent pas en arène. Coéquipiers : pas de dégâts. Hors combat (compte à rebours) : rien.
    */
   @EventHandler(
      priority = EventPriority.HIGHEST
   )
   public void onPvp(EntityDamageByEntityEvent e) {
      if (e.getEntity() instanceof Player v) {
         Match m = this.inMatch.get(v.getUniqueId());
         Player a = ProtectionListener.attacker(e.getDamager());
         if (m != null) {
            if (a == null || a == v) {
               if (m.state != State.FIGHT) {
                  e.setCancelled(true);
               }
            } else if (m.state != State.FIGHT || !m.alive.contains(v.getUniqueId()) || this.inMatch.get(a.getUniqueId()) != m || !m.alive.contains(a.getUniqueId())) {
               e.setCancelled(true);
            } else {
               e.setCancelled(m.sameTeam(a.getUniqueId(), v.getUniqueId()));
            }
         } else if (a != null && this.inMatch.containsKey(a.getUniqueId())) {
            e.setCancelled(true);
         }
      }
   }

   /** Un coup mortel en arène : pas de mort ni de perte, le joueur est éliminé. */
   @EventHandler(
      priority = EventPriority.HIGHEST,
      ignoreCancelled = true
   )
   public void onDamage(EntityDamageEvent e) {
      if (e.getEntity() instanceof Player p) {
         Match m = this.inMatch.get(p.getUniqueId());
         if (m != null) {
            if (m.state != State.FIGHT || !m.alive.contains(p.getUniqueId())) {
               e.setCancelled(true);
            } else if (p.getHealth() + p.getAbsorptionAmount() - e.getFinalDamage() <= 0.0) {
               e.setCancelled(true);
               Player killer = e instanceof EntityDamageByEntityEvent ed ? ProtectionListener.attacker(ed.getDamager()) : null;
               this.eliminate(m, p, killer);
            }
         }
      }
   }

   @EventHandler(
      priority = EventPriority.HIGHEST
   )
   public void onDeath(PlayerDeathEvent e) {
      Player p = e.getEntity();
      Match m = this.inMatch.get(p.getUniqueId());
      if (m != null || this.isArena(p.getWorld())) {
         e.getDrops().clear();
         e.setDroppedExp(0);
         e.setKeepInventory(true);
         e.setKeepLevel(true);
         if (m != null) {
            this.eliminate(m, p, p.getKiller());
         }
      }
   }

   @EventHandler
   public void onRespawn(PlayerRespawnEvent e) {
      Player p = e.getPlayer();
      if (this.saves.isConfigurationSection(p.getUniqueId().toString()) && !this.inMatchActive(p)) {
         Location l = this.saves.getLocation(p.getUniqueId() + ".location");
         if (l != null && l.getWorld() != null) {
            e.setRespawnLocation(l);
         }

         Bukkit.getScheduler().runTask(this.plugin, () -> this.restore(p));
      }
   }

   private boolean inMatchActive(Player p) {
      Match m = this.inMatch.get(p.getUniqueId());
      return m != null && m.state != State.END && m.alive.contains(p.getUniqueId());
   }

   @EventHandler(
      priority = EventPriority.LOWEST
   )
   public void onCommand(PlayerCommandPreprocessEvent e) {
      if (this.inMatch.containsKey(e.getPlayer().getUniqueId())) {
         String c = e.getMessage().toLowerCase(Locale.ROOT);
         if (!c.startsWith("/pvp") && !c.startsWith("/arene") && !c.startsWith("/arène") && !e.getPlayer().hasPermission("windyclans.admin")) {
            e.setCancelled(true);
            this.msg(e.getPlayer(), "&cCommandes bloquées pendant un combat. &7/pvp quitter pour abandonner.");
         }
      }
   }

   @EventHandler(
      priority = EventPriority.HIGH,
      ignoreCancelled = true
   )
   public void onTeleport(PlayerTeleportEvent e) {
      Player p = e.getPlayer();
      if (this.inMatch.containsKey(p.getUniqueId()) && !this.allowTp.contains(p.getUniqueId())) {
         Match m = this.inMatch.get(p.getUniqueId());
         // les perles et la caméra spectateur restent possibles, mais pas de sortie de l'arène
         if (e.getTo() == null || !this.isArena(e.getTo().getWorld()) || e.getTo().distance(m.center()) > RADIUS + 12) {
            e.setCancelled(true);
         }
      } else if (!this.allowTp.contains(p.getUniqueId()) && e.getTo() != null && this.isArena(e.getTo().getWorld()) && !this.isArena(e.getFrom().getWorld())
         && !p.hasPermission("windyclans.admin")) {
         e.setCancelled(true);
      }
   }

   @EventHandler(
      ignoreCancelled = true
   )
   public void onMove(PlayerMoveEvent e) {
      Match m = this.inMatch.get(e.getPlayer().getUniqueId());
      if (m != null) {
         if (m.state == State.COUNTDOWN && e.getTo() != null && (e.getFrom().getX() != e.getTo().getX() || e.getFrom().getZ() != e.getTo().getZ())) {
            Location back = e.getFrom().clone();
            back.setYaw(e.getTo().getYaw());
            back.setPitch(e.getTo().getPitch());
            e.setTo(back);
         } else if (e.getPlayer().getGameMode() == GameMode.SPECTATOR && e.getTo() != null && e.getTo().distance(m.center()) > RADIUS + 8) {
            e.setTo(m.center().clone().add(0.0, 6.0, 0.0));
         }
      }
   }

   @EventHandler(
      ignoreCancelled = true
   )
   public void onDrop(PlayerDropItemEvent e) {
      if (this.inMatch.containsKey(e.getPlayer().getUniqueId()) || this.isKitItem(e.getItemDrop().getItemStack())) {
         e.setCancelled(true);
      }
   }

   @EventHandler(
      ignoreCancelled = true
   )
   public void onPickup(EntityPickupItemEvent e) {
      if (e.getEntity() instanceof Player p && this.isArena(p.getWorld())) {
         e.setCancelled(true);
         e.getItem().remove();
      }
   }

   @EventHandler(
      ignoreCancelled = true
   )
   public void onOpen(InventoryOpenEvent e) {
      if (this.inMatch.containsKey(e.getPlayer().getUniqueId()) && e.getInventory().getType() != InventoryType.CRAFTING && e.getInventory().getType() != InventoryType.PLAYER) {
         e.setCancelled(true);
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
      Block b = e.getBlock();
      if (this.isArena(b.getWorld()) && !this.plugin.bypass().contains(e.getPlayer().getUniqueId())) {
         e.setCancelled(true);
      }
   }

   @EventHandler(
      ignoreCancelled = true
   )
   public void onHunger(FoodLevelChangeEvent e) {
      if (e.getEntity() instanceof Player p && this.inMatch.containsKey(p.getUniqueId()) && !this.cfg.getBoolean("settings.hunger", false)) {
         e.setCancelled(true);
      }
   }

   @EventHandler
   public void onQuit(PlayerQuitEvent e) {
      Player p = e.getPlayer();
      UUID id = p.getUniqueId();
      this.leaveQueues(id);
      this.duelInvites.remove(id);
      this.duoInvites.remove(id);
      UUID mate = this.duos.remove(id);
      if (mate != null) {
         this.duos.remove(mate);
      }

      Match m = this.inMatch.get(id);
      if (m != null) {
         if (m.state != State.END) {
            if (m.state == State.COUNTDOWN) {
               m.state = State.FIGHT;
            }

            this.quitting.add(id);
            this.eliminate(m, p, null);
            this.quitting.remove(id);
         }

         this.inMatch.remove(id);
         // le stuff reste dans pvp-sauvegardes.yml et sera rendu à la reconnexion ; on retire le kit tout de suite
         p.getInventory().clear();
         p.setGameMode(GameMode.SURVIVAL);
      }
   }

   @EventHandler(
      priority = EventPriority.LOWEST
   )
   public void onJoin(PlayerJoinEvent e) {
      Player p = e.getPlayer();
      if (this.saves.isConfigurationSection(p.getUniqueId().toString())) {
         Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
            if (p.isOnline() && !this.inMatch.containsKey(p.getUniqueId())) {
               this.restore(p);
               this.msg(p, "&7Ton stuff d'avant le combat t'a été rendu.");
            }
         }, 20L);
      } else {
         this.stripKit(p);
         if (this.isArena(p.getWorld())) {
            Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
               this.allowTp.add(p.getUniqueId());
               p.setGameMode(GameMode.SURVIVAL);
               p.teleport(this.fallback());
               this.allowTp.remove(p.getUniqueId());
            }, 20L);
         }
      }
   }

   @EventHandler
   public void onWorld(PlayerChangedWorldEvent e) {
      Player p = e.getPlayer();
      if (this.inAnyQueue(p.getUniqueId())) {
         String w = p.getWorld().getName();
         if (w.equals("windy_arenes") || w.equals("windy_donjons") || w.equals("windy_faille")) {
            this.leaveQueues(p.getUniqueId());
            this.msg(p, "&7Tu sors de la file PvP (boss, donjon ou Faille).");
         }
      }
   }

   public boolean inMatch(Player p) {
      return this.inMatch.containsKey(p.getUniqueId());
   }

   public Collection<String> kitIds() {
      return this.kits.keySet();
   }

   static final class VoidGen extends ChunkGenerator {
   }
}
