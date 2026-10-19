package fr.windydev.clans.elixir;

import fr.windydev.clans.WindyClans;
import fr.windydev.clans.util.Text;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
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
import java.util.Map.Entry;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Instrument;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Note;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.Particle.DustOptions;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.NoteBlock;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.Display.Brightness;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.NotePlayEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerHarvestBlockEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

public class ElixirFeature implements Listener {
   private static final String EXTRACTOR = "extracteur";
   private static final String INFUSER = "infuseur";
   private static final String GLASS = "verre_magique";
   private static final String WAND = "baguette_liaison";
   private static final String[] ROMAN = new String[]{"0", "I", "II", "III"};
   private static final BlockFace[] AROUND = new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.UP, BlockFace.DOWN};
   private static final Map<String, Integer> MACHINE_NOTE = Map.of("extracteur", 0, "infuseur", 1);
   private final WindyClans plugin;
   private final Random rnd = new Random();
   private final NamespacedKey displayKey;
   private final Map<String, String> machines = new HashMap<>();
   private final Set<String> glass = new HashSet<>();
   private final Map<String, ElixirFeature.Tank> tanks = new HashMap<>();
   private final Map<String, String> glassOwner = new HashMap<>();
   private final List<ElixirFeature.Link> links = new ArrayList<>();
   private final Map<String, Integer> infused = new HashMap<>();
   private final Map<String, Integer> cores = new HashMap<>();
   private final Map<UUID, String> pending = new HashMap<>();
   private int coreCost = 1000;
   private File dataFile;
   private boolean dirty;
   private double slowGrowth = 0.85;
   private int harvestMax = 1;
   private int baseCapacity = 10000;
   private int sizeMul = 4;
   private int layerMul = 2;
   private int maxSize = 9;
   private int maxLayers = 5;
   private int linkDistance = 16;
   private int perBerry = 100;
   private int amethystCost = 300;
   private final int[] upgradeCost = new int[]{0, 400, 800};
   private final double[] upgradeFail = new double[]{0.0, 0.6, 0.75};
   private boolean debugStates = false;

   public ElixirFeature(WindyClans plugin) {
      this.plugin = plugin;
      this.displayKey = new NamespacedKey(plugin, "elixir_tank");
   }

   public void load() {
      File f = new File(this.plugin.getDataFolder(), "elixirs.yml");
      if (!f.exists()) {
         this.plugin.saveResource("elixirs.yml", false);
      }

      YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
      if (y.getInt("config-version", 1) < 2) {
         f.renameTo(new File(this.plugin.getDataFolder(), "elixirs-ancien.yml"));
         this.plugin.saveResource("elixirs.yml", true);
         y = YamlConfiguration.loadConfiguration(f);
      }

      if (y.getInt("config-version", 1) < 3) {
         // 1.14.1 : le fluide était trop facile à obtenir, les baies poussent moins vite et rendent moins
         if (y.getDouble("baies.slow-growth", 0.5) < 0.85) {
            y.set("baies.slow-growth", 0.85);
         }

         y.set("baies.harvest-max", 1);
         y.set("config-version", 3);

         try {
            y.save(f);
            this.plugin.getLogger().info("elixirs.yml : baies ralenties (slow-growth 0.85, harvest-max 1).");
         } catch (Exception ex) {
            this.plugin.getLogger().warning("elixirs.yml : mise à jour impossible : " + ex.getMessage());
         }
      }

      try (InputStream in = this.plugin.getResource("elixirs.yml")) {
         if (in != null) {
            y.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)));
         }
      } catch (Exception var14) {
      }

      this.slowGrowth = Math.max(0.0, Math.min(0.98, y.getDouble("baies.slow-growth", 0.85)));
      this.harvestMax = Math.max(1, y.getInt("baies.harvest-max", 1));
      this.baseCapacity = Math.max(100, y.getInt("tank.base-capacity", 10000));
      this.sizeMul = Math.max(1, y.getInt("tank.size-multiplier", 4));
      this.layerMul = Math.max(1, y.getInt("tank.layer-multiplier", 2));
      this.maxSize = Math.max(3, Math.min(15, y.getInt("tank.max-size", 9)));
      this.maxLayers = Math.max(1, Math.min(10, y.getInt("tank.max-layers", 5)));
      this.linkDistance = Math.max(2, y.getInt("network.link-distance", 16));
      this.perBerry = Math.max(1, y.getInt("extraction.per-berry", 100));
      this.amethystCost = Math.max(1, y.getInt("amethyst.fluid", 300));
      this.coreCost = Math.max(1, y.getInt("lodestone.fluid", 1000));

      for (int t = 1; t <= 2; t++) {
         this.upgradeCost[t] = Math.max(1, y.getInt("upgrade." + t + ".fluid", this.upgradeCost[t]));
         this.upgradeFail[t] = Math.max(0.0, Math.min(0.95, y.getDouble("upgrade." + t + ".fail-chance", this.upgradeFail[t])));
      }

      this.removeDisplays();
      this.dataFile = new File(this.plugin.getDataFolder(), "elixirs-data.yml");
      this.machines.clear();
      this.glass.clear();
      this.tanks.clear();
      this.glassOwner.clear();
      this.links.clear();
      this.infused.clear();
      this.cores.clear();
      this.pending.clear();
      YamlConfiguration d = YamlConfiguration.loadConfiguration(this.dataFile);
      ConfigurationSection ms = d.getConfigurationSection("machines");
      if (ms != null) {
         for (String k : ms.getKeys(false)) {
            String type = ms.getString(k);
            if (MACHINE_NOTE.containsKey(type)) {
               this.machines.put(k, type);
            }
         }
      }

      this.glass.addAll(d.getStringList("glass"));
      ConfigurationSection ts = d.getConfigurationSection("tanks");
      if (ts != null) {
         for (String kx : ts.getKeys(false)) {
            ConfigurationSection s = ts.getConfigurationSection(kx);
            if (s != null && s.contains("size")) {
               ElixirFeature.Tank t = parseKey(kx);
               if (t != null) {
                  t.size = s.getInt("size");
                  t.layers = s.getInt("layers");
                  t.tier = s.getInt("tier");
                  t.amount = s.getInt("amount");
                  t.active = s.getBoolean("active");
                  this.tanks.put(kx, t);
                  if (t.active) {
                     for (String g : this.members(t)) {
                        this.glassOwner.put(g, kx);
                     }
                  }
               }
            }
         }
      }

      for (String l : d.getStringList("links")) {
         String[] p = l.split("\\|");
         if (p.length == 2) {
            this.links.add(new ElixirFeature.Link(p[0], p[1]));
         }
      }

      ConfigurationSection as = d.getConfigurationSection("amethysts");
      if (as != null) {
         for (String kxx : as.getKeys(false)) {
            this.infused.put(kxx, as.getInt(kxx));
         }
      }

      ConfigurationSection cs = d.getConfigurationSection("cores");
      if (cs != null) {
         for (String kc : cs.getKeys(false)) {
            this.cores.put(kc, cs.getInt(kc));
         }
      }

      File old = new File(this.plugin.getDataFolder(), "amethystes.yml");
      if (old.exists()) {
         YamlConfiguration o = YamlConfiguration.loadConfiguration(old);

         for (String kxx : o.getKeys(false)) {
            this.infused.put(kxx, o.getInt(kxx));
         }

         old.delete();
         this.dirty = true;
      }
   }

   private void save() {
      if (this.dataFile != null) {
         YamlConfiguration d = new YamlConfiguration();

         for (Entry<String, String> e : this.machines.entrySet()) {
            d.set("machines." + e.getKey(), e.getValue());
         }

         d.set("glass", new ArrayList<>(this.glass));

         for (ElixirFeature.Tank t : this.tanks.values()) {
            String p = "tanks." + t.key + ".";
            d.set(p + "size", t.size);
            d.set(p + "layers", t.layers);
            d.set(p + "tier", t.tier);
            d.set(p + "amount", t.amount);
            d.set(p + "active", t.active);
         }

         d.set("links", this.links.stream().map(l -> l.a() + "|" + l.b()).toList());

         for (Entry<String, Integer> e : this.infused.entrySet()) {
            d.set("amethysts." + e.getKey(), e.getValue());
         }

         for (Entry<String, Integer> e : this.cores.entrySet()) {
            d.set("cores." + e.getKey(), e.getValue());
         }

         try {
            d.save(this.dataFile);
         } catch (Exception var5) {
            this.plugin.getLogger().warning("elixirs-data.yml : sauvegarde impossible : " + var5.getMessage());
         }

         this.dirty = false;
      }
   }

   public void start() {
      Bukkit.getScheduler().runTaskTimer(this.plugin, this::tickEffects, 40L, 40L);
      Bukkit.getScheduler().runTaskTimer(this.plugin, this::tickWands, 20L, 8L);
      Bukkit.getScheduler().runTaskTimer(this.plugin, this::keepMachineStates, 20L, 10L);
      Bukkit.getScheduler().runTaskTimer(this.plugin, () -> {
         if (this.dirty) {
            this.save();
         }
      }, 200L, 200L);
   }

   public void shutdown() {
      this.save();
      this.removeDisplays();
   }

   private static String key(Block b) {
      return key(b.getWorld().getName(), b.getX(), b.getY(), b.getZ());
   }

   private static String key(String w, int x, int y, int z) {
      return w + ";" + x + ";" + y + ";" + z;
   }

   private static ElixirFeature.Tank parseKey(String k) {
      String[] p = k.split(";");
      if (p.length != 4) {
         return null;
      } else {
         ElixirFeature.Tank t = new ElixirFeature.Tank();
         t.key = k;
         t.world = p[0];

         try {
            t.x = Integer.parseInt(p[1]);
            t.y = Integer.parseInt(p[2]);
            t.z = Integer.parseInt(p[3]);
            return t;
         } catch (NumberFormatException var4) {
            return null;
         }
      }
   }

   private static Location loc(String key) {
      String[] p = key.split(";");
      World w = Bukkit.getWorld(p[0]);
      return w == null ? null : new Location(w, Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
   }

   private int capacity(ElixirFeature.Tank t) {
      long c = this.baseCapacity;

      for (int s = 3; s < t.size; s += 2) {
         c *= this.sizeMul;
      }

      for (int l = 1; l < t.layers; l++) {
         c *= this.layerMul;
      }

      return (int)Math.min(2147483647L, c);
   }

   private List<String> members(ElixirFeature.Tank t) {
      List<String> out = new ArrayList<>();
      int m = t.size - 1;

      for (int l = 0; l < t.layers; l++) {
         for (int dx = 0; dx <= m; dx++) {
            for (int dz = 0; dz <= m; dz++) {
               if (dx == 0 || dz == 0 || dx == m || dz == m) {
                  out.add(key(t.world, t.x + dx, t.y + l, t.z + dz));
               }
            }
         }
      }

      return out;
   }

   private boolean inside(ElixirFeature.Tank t, String world, int x, int y, int z) {
      return t.world.equals(world) && x > t.x && x < t.x + t.size - 1 && z > t.z && z < t.z + t.size - 1 && y >= t.y && y < t.y + t.layers;
   }

   private static int berryTier(String id) {
      if (id != null && id.startsWith("baie_")) {
         try {
            return Integer.parseInt(id.substring(5));
         } catch (NumberFormatException var2) {
            return 0;
         }
      } else {
         return 0;
      }
   }

   private static String fluidName(int tier) {
      return switch (tier) {
         case 1 -> "&aFluide I";
         case 2 -> "&bFluide II";
         case 3 -> "&dFluide III";
         default -> "&7vide";
      };
   }

   private static String fmt(long n) {
      return String.format(Locale.FRANCE, "%,d", n).replace(' ', ' ').replace(' ', ' ');
   }

   private static DustOptions dust(int tier, float size) {
      return switch (tier) {
         case 1 -> new DustOptions(Color.fromRGB(120, 230, 70), size);
         case 2 -> new DustOptions(Color.fromRGB(70, 200, 245), size);
         case 3 -> new DustOptions(Color.fromRGB(225, 100, 250), size);
         default -> new DustOptions(Color.fromRGB(180, 130, 255), size);
      };
   }

   private BlockData machineState(String type) {
      NoteBlock nb = (NoteBlock)Material.NOTE_BLOCK.createBlockData();
      nb.setInstrument(Instrument.CUSTOM_HEAD);
      nb.setNote(new Note(MACHINE_NOTE.getOrDefault(type, 0)));
      nb.setPowered(false);
      return nb;
   }

   @EventHandler(
      ignoreCancelled = true
   )
   public void onGrow(BlockGrowEvent e) {
      if (e.getBlock().getType() == Material.SWEET_BERRY_BUSH && this.slowGrowth > 0.0 && this.rnd.nextDouble() < this.slowGrowth) {
         e.setCancelled(true);
      }
   }

   @EventHandler(
      ignoreCancelled = true,
      priority = EventPriority.HIGH
   )
   public void onHarvest(PlayerHarvestBlockEvent e) {
      if (e.getHarvestedBlock().getType() == Material.SWEET_BERRY_BUSH) {
         List<ItemStack> list = e.getItemsHarvested();

         for (int i = 0; i < list.size(); i++) {
            ItemStack it = list.get(i);
            if (it != null && it.getType() == Material.SWEET_BERRIES && this.plugin.items().idOf(it) == null) {
               ItemStack b = this.plugin.items().create("baie_1");
               if (b == null) {
                  return;
               }

               b.setAmount(Math.min(this.harvestMax, it.getAmount()));
               list.set(i, b);
            }
         }
      }
   }

   @EventHandler(
      ignoreCancelled = true,
      priority = EventPriority.HIGH
   )
   public void onBushDrops(BlockDropItemEvent e) {
      if (e.getBlockState().getType() == Material.SWEET_BERRY_BUSH) {
         for (Item item : e.getItems()) {
            ItemStack it = item.getItemStack();
            if (it.getType() == Material.SWEET_BERRIES && this.plugin.items().idOf(it) == null) {
               ItemStack b = this.plugin.items().create("baie_1");
               if (b == null) {
                  return;
               }

               b.setAmount(Math.min(this.harvestMax, it.getAmount()));
               item.setItemStack(b);
            }
         }
      }
   }

   @EventHandler(
      ignoreCancelled = true
   )
   public void onPhysics(BlockPhysicsEvent e) {
      if (e.getBlock().getType() == Material.NOTE_BLOCK && this.machines.containsKey(key(e.getBlock()))) {
         e.setCancelled(true);
      }
   }

   @EventHandler(
      ignoreCancelled = true
   )
   public void onNote(NotePlayEvent e) {
      if (this.machines.containsKey(key(e.getBlock()))) {
         e.setCancelled(true);
      }
   }

   private void refreshAround(Block b) {
      List<Block> near = new ArrayList<>();

      for (BlockFace f : AROUND) {
         Block n = b.getRelative(f);
         if (this.machines.containsKey(key(n))) {
            near.add(n);
         }
      }

      if (!near.isEmpty()) {
         Bukkit.getScheduler().runTask(this.plugin, () -> {
            for (Block nx : near) {
               String type = this.machines.get(key(nx));
               if (type != null) {
                  BlockData want = this.machineState(type);
                  if (!nx.getBlockData().matches(want)) {
                     nx.setBlockData(want, false);
                  }

                  for (Player p : nx.getWorld().getPlayers()) {
                     if (p.getLocation().distanceSquared(nx.getLocation()) < 4096.0) {
                        p.sendBlockChange(nx.getLocation(), want);
                     }
                  }
               }
            }
         });
      }
   }

   private void keepMachineStates() {
      for (Entry<String, String> e : this.machines.entrySet()) {
         Location l = loc(e.getKey());
         if (l != null && l.isChunkLoaded()) {
            Block b = l.getBlock();
            if (b.getType() == Material.NOTE_BLOCK) {
               BlockData want = this.machineState(e.getValue());
               if (!b.getBlockData().matches(want)) {
                  b.setBlockData(want, false);
                  if (this.debugStates) {
                     this.plugin.getLogger().info("[élixirs] état de machine rétabli en " + e.getKey());
                  }
               }
            }
         }
      }
   }

   @EventHandler(
      ignoreCancelled = true,
      priority = EventPriority.HIGHEST
   )
   public void onPlaceInside(BlockPlaceEvent e) {
      Block b = e.getBlock();

      for (ElixirFeature.Tank t : this.tanks.values()) {
         if (t.active && this.inside(t, b.getWorld().getName(), b.getX(), b.getY(), b.getZ())) {
            e.setCancelled(true);
            e.getPlayer().sendActionBar(Text.c("&cL'intérieur d'un tank magique doit rester vide."));
            return;
         }
      }
   }

   @EventHandler(
      ignoreCancelled = true,
      priority = EventPriority.MONITOR
   )
   public void onPlace(BlockPlaceEvent e) {
      Block b = e.getBlock();
      this.refreshAround(b);
      String id = this.plugin.items().idOf(e.getItemInHand());
      if (id != null) {
         String k = key(b);
         if (MACHINE_NOTE.containsKey(id)) {
            this.machines.put(k, id);
            b.setBlockData(this.machineState(id), false);
            this.refreshAround(b.getRelative(BlockFace.UP));
            b.getWorld().playSound(b.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_PLACE, 1.0F, 0.8F);
         } else if ("verre_magique".equals(id)) {
            this.glass.add(k);
            ElixirFeature.Tank t = this.tryForm(b);
            if (t != null) {
               e.getPlayer()
                  .sendActionBar(
                     Text.c(
                        "&d⚗ Tank magique formé &8| &f"
                           + t.size
                           + "x"
                           + t.size
                           + " &7sur &f"
                           + t.layers
                           + " couche(s) &8| &7capacité &f"
                           + fmt(this.capacity(t))
                           + " mB"
                     )
                  );
               b.getWorld().playSound(b.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.8F, 1.4F);
            }
         } else if (id.startsWith("magnetite_infusee_")) {
            try {
               this.cores.put(k, Integer.parseInt(id.substring("magnetite_infusee_".length())));
            } catch (NumberFormatException var7) {
               return;
            }
         } else {
            if (!id.startsWith("amethyste_infusee_")) {
               return;
            }

            try {
               this.infused.put(k, Integer.parseInt(id.substring("amethyste_infusee_".length())));
            } catch (NumberFormatException var6) {
               return;
            }
         }

         this.dirty = true;
      }
   }

   @EventHandler(
      ignoreCancelled = true,
      priority = EventPriority.HIGH
   )
   public void onBreak(BlockBreakEvent e) {
      Block b = e.getBlock();
      this.refreshAround(b);
      String k = key(b);
      if (this.links.removeIf(l -> l.a().equals("a:" + k) || l.b().equals("a:" + k))) {
         this.dirty = true;
      }

      if (this.machines.containsKey(k) || this.glass.contains(k) || this.infused.containsKey(k) || this.cores.containsKey(k)) {
         e.setDropItems(false);
         ItemStack drop = this.remove(b);
         if (drop != null && e.getPlayer().getGameMode() != GameMode.CREATIVE) {
            b.getWorld().dropItemNaturally(b.getLocation().add(0.5, 0.5, 0.5), drop);
         }

         String owner = this.glassOwner.get(k);
         if (owner != null) {
            ElixirFeature.Tank t = this.tanks.get(owner);
            if (t != null && t.amount > 0) {
               this.plugin
                  .msg(
                     e.getPlayer(),
                     "&eLe tank est ouvert : son fluide (&f"
                        + fmt(t.amount)
                        + " mB "
                        + fluidName(t.tier)
                        + "&e) est gardé tant que tu le reconstruis au même endroit."
                  );
            }
         }
      }
   }

   private ItemStack remove(Block b) {
      String k = key(b);
      this.dirty = true;
      String type = this.machines.remove(k);
      if (type != null) {
         this.links.removeIf(l -> l.a().equals("m:" + k) || l.b().equals("m:" + k));
         return this.plugin.items().create(type);
      } else if (this.glass.remove(k)) {
         String owner = this.glassOwner.get(k);
         if (owner != null) {
            this.deactivate(this.tanks.get(owner));
         }

         return this.plugin.items().create("verre_magique");
      } else {
         Integer co = this.cores.remove(k);
         if (co != null) {
            return this.plugin.items().create("magnetite_infusee_" + co);
         }

         Integer am = this.infused.remove(k);
         return am == null ? null : this.plugin.items().create("amethyste_infusee_" + am);
      }
   }

   private boolean tracked(Block b) {
      String k = key(b);
      return this.machines.containsKey(k) || this.glass.contains(k) || this.infused.containsKey(k) || this.cores.containsKey(k);
   }

   // ------------------------------------------------------------------ autels infusés (API publique)

   /** Tier de l'améthyste infusée posée à cet endroit (0 = bloc normal). */
   public int infusedTier(Block b) {
      Integer t = this.infused.get(key(b));
      return t == null || b.getType() != Material.AMETHYST_BLOCK ? 0 : t;
   }

   /** Tier de la magnétite infusée (cœur de sanctuaire) posée à cet endroit (0 = bloc normal). */
   public int coreTier(Block b) {
      Integer t = this.cores.get(key(b));
      return t == null || b.getType() != Material.LODESTONE ? 0 : t;
   }

   private boolean isAltarBlock(Block b) {
      if (b.getType() == Material.LODESTONE) {
         return this.coreTier(b) > 0;
      } else {
         return this.plugin.rituals() != null && b.getType() == this.plugin.rituals().centerMaterial();
      }
   }

   /** L'autel (table centrale ou cœur de sanctuaire) est-il relié à au moins un tank complet ? */
   public boolean linked(Block altar) {
      return !this.networkTanks("a:" + key(altar)).isEmpty();
   }

   /** Fluide disponible (mB) de tier >= minTier dans le réseau relié à l'autel. */
   public int fluidAvailable(Block altar, int minTier) {
      long sum = 0L;

      for (ElixirFeature.Tank t : this.networkTanks("a:" + key(altar)).keySet()) {
         if (t.tier >= minTier && t.amount > 0) {
            sum += t.amount;
         }
      }

      return (int)Math.min(2147483647L, sum);
   }

   /** Prélève le fluide pour un rituel. Renvoie false (et ne prélève rien) s'il n'y en a pas assez. */
   public boolean drawFluid(Block altar, int minTier, int amount) {
      if (amount <= 0) {
         return true;
      } else if (this.fluidAvailable(altar, minTier) < amount) {
         return false;
      } else {
         Map<ElixirFeature.Tank, List<String>> net = this.networkTanks("a:" + key(altar));
         List<ElixirFeature.Tank> order = new ArrayList<>();

         for (ElixirFeature.Tank t : net.keySet()) {
            if (t.tier >= minTier && t.amount > 0) {
               order.add(t);
            }
         }

         order.sort((x, y) -> Integer.compare(x.tier, y.tier));
         int left = amount;

         for (ElixirFeature.Tank tx : order) {
            if (left <= 0) {
               break;
            }

            int n = Math.min(left, tx.amount);
            int tier = tx.tier;
            this.take(tx, n);
            left -= n;
            this.pulse(net.get(tx), tier, true);
         }

         return true;
      }
   }

   public static String fluidLabel(int tier) {
      return fluidName(tier);
   }

   public static String format(long n) {
      return fmt(n);
   }

   @EventHandler(
      ignoreCancelled = true
   )
   public void onExplode(EntityExplodeEvent e) {
      e.blockList().removeIf(this::tracked);
   }

   @EventHandler(
      ignoreCancelled = true
   )
   public void onBlockExplode(BlockExplodeEvent e) {
      e.blockList().removeIf(this::tracked);
   }

   @EventHandler(
      ignoreCancelled = true
   )
   public void onPistonExtend(BlockPistonExtendEvent e) {
      if (e.getBlocks().stream().anyMatch(this::tracked)) {
         e.setCancelled(true);
      }
   }

   @EventHandler(
      ignoreCancelled = true
   )
   public void onPistonRetract(BlockPistonRetractEvent e) {
      if (e.getBlocks().stream().anyMatch(this::tracked)) {
         e.setCancelled(true);
      }
   }

   private ElixirFeature.Tank tryForm(Block start) {
      String w = start.getWorld().getName();
      int limit = 4 * (this.maxSize - 1) * this.maxLayers + 1;
      Set<String> comp = new HashSet<>();
      Deque<int[]> queue = new ArrayDeque<>();
      queue.add(new int[]{start.getX(), start.getY(), start.getZ()});
      comp.add(key(start));
      int minX = Integer.MAX_VALUE;
      int minY = Integer.MAX_VALUE;
      int minZ = Integer.MAX_VALUE;
      int maxX = Integer.MIN_VALUE;
      int maxY = Integer.MIN_VALUE;
      int maxZ = Integer.MIN_VALUE;

      while (!queue.isEmpty()) {
         int[] c = queue.poll();
         minX = Math.min(minX, c[0]);
         maxX = Math.max(maxX, c[0]);
         minY = Math.min(minY, c[1]);
         maxY = Math.max(maxY, c[1]);
         minZ = Math.min(minZ, c[2]);
         maxZ = Math.max(maxZ, c[2]);

         for (BlockFace f : AROUND) {
            int x = c[0] + f.getModX();
            int y = c[1] + f.getModY();
            int z = c[2] + f.getModZ();
            String k = key(w, x, y, z);
            if (this.glass.contains(k) && comp.add(k)) {
               if (comp.size() > limit) {
                  return null;
               }

               queue.add(new int[]{x, y, z});
            }
         }
      }

      int size = maxX - minX + 1;
      int layers = maxY - minY + 1;
      if (size == maxZ - minZ + 1 && size >= 3 && size <= this.maxSize && size % 2 != 0 && layers <= this.maxLayers) {
         ElixirFeature.Tank t = new ElixirFeature.Tank();
         t.world = w;
         t.x = minX;
         t.y = minY;
         t.z = minZ;
         t.size = size;
         t.layers = layers;
         t.key = key(w, minX, minY, minZ);
         List<String> need = this.members(t);
         if (need.size() == comp.size() && comp.containsAll(need)) {
            World world = start.getWorld();

            for (int l = 0; l < layers; l++) {
               for (int x = minX + 1; x < maxX; x++) {
                  for (int z = minZ + 1; z < maxZ; z++) {
                     if (!world.getBlockAt(x, minY + l, z).getType().isAir()) {
                        return null;
                     }
                  }
               }
            }

            int cap = this.capacity(t);
            Map<Integer, Integer> byTier = new HashMap<>();
            List<String> absorbed = new ArrayList<>();

            for (ElixirFeature.Tank o : new ArrayList<>(this.tanks.values())) {
               if (o.world.equals(w)) {
                  boolean overlap = o.x <= maxX
                     && o.x + o.size - 1 >= minX
                     && o.z <= maxZ
                     && o.z + o.size - 1 >= minZ
                     && o.y <= maxY
                     && o.y + o.layers - 1 >= minY;
                  if (overlap) {
                     if (o.active) {
                        this.deactivate(o);
                     }

                     if (o.amount > 0) {
                        byTier.merge(o.tier, o.amount, Integer::sum);
                     }

                     this.tanks.remove(o.key);
                     absorbed.add(o.key);
                  }
               }
            }

            byTier.entrySet().stream().max(Entry.comparingByValue()).ifPresent(en -> {
               t.tier = en.getKey();
               t.amount = Math.min(cap, en.getValue());
            });
            if (t.amount <= 0) {
               t.amount = 0;
               t.tier = 0;
            }

            t.active = true;
            this.tanks.put(t.key, t);

            for (String g : need) {
               this.glassOwner.put(g, t.key);
            }

            for (int i = 0; i < this.links.size(); i++) {
               ElixirFeature.Link l = this.links.get(i);
               String a = l.a();
               String b = l.b();

               for (String ox : absorbed) {
                  if (a.equals("t:" + ox)) {
                     a = "t:" + t.key;
                  }

                  if (b.equals("t:" + ox)) {
                     b = "t:" + t.key;
                  }
               }

               this.links.set(i, new ElixirFeature.Link(a, b));
            }

            this.links.removeIf(lx -> lx.a().equals(lx.b()));
            this.dedupeLinks();
            this.updateDisplay(t);
            this.dirty = true;
            return t;
         } else {
            return null;
         }
      } else {
         return null;
      }
   }

   private void dedupeLinks() {
      Set<String> seen = new HashSet<>();
      this.links.removeIf(l -> !seen.add(l.a().compareTo(l.b()) < 0 ? l.a() + "|" + l.b() : l.b() + "|" + l.a()));
   }

   private void deactivate(ElixirFeature.Tank t) {
      if (t != null) {
         t.active = false;

         for (String g : this.members(t)) {
            if (t.key.equals(this.glassOwner.get(g))) {
               this.glassOwner.remove(g);
            }
         }

         this.removeDisplay(t);
         if (t.amount <= 0) {
            this.tanks.remove(t.key);
            this.links.removeIf(l -> l.a().equals("t:" + t.key) || l.b().equals("t:" + t.key));
         }

         this.dirty = true;
      }
   }

   private void updateDisplay(ElixirFeature.Tank t) {
      if (t.active && t.amount > 0) {
         World w = Bukkit.getWorld(t.world);
         if (w != null && w.isChunkLoaded(t.x + t.size / 2 >> 4, t.z + t.size / 2 >> 4)) {
            BlockDisplay d;
            if ((t.display == null ? null : Bukkit.getEntity(t.display)) instanceof BlockDisplay bd && bd.isValid()) {
               d = bd;
            } else {
               d = (BlockDisplay)w.spawn(new Location(w, t.x + 1, t.y, t.z + 1), BlockDisplay.class, n -> {
                  n.setPersistent(false);
                  n.setBrightness(new Brightness(15, 15));
                  n.getPersistentDataContainer().set(this.displayKey, PersistentDataType.STRING, t.key);
               });
               t.display = d.getUniqueId();
            }
            Material m = switch (t.tier) {
               case 2 -> Material.LIGHT_BLUE_STAINED_GLASS;
               case 3 -> Material.MAGENTA_STAINED_GLASS;
               default -> Material.LIME_STAINED_GLASS;
            };
            if (d.getBlock().getMaterial() != m) {
               d.setBlock(m.createBlockData());
            }

            float inner = t.size - 2 - 0.02F;
            float h = Math.max(0.06F, (float)(t.layers * ((double)t.amount / this.capacity(t))) - 0.02F);
            d.setInterpolationDuration(10);
            d.setInterpolationDelay(0);
            d.setTransformation(new Transformation(new Vector3f(0.01F, 0.01F, 0.01F), new AxisAngle4f(), new Vector3f(inner, h, inner), new AxisAngle4f()));
         }
      } else {
         this.removeDisplay(t);
      }
   }

   private void removeDisplay(ElixirFeature.Tank t) {
      if (t.display != null) {
         Entity e = Bukkit.getEntity(t.display);
         if (e != null) {
            e.remove();
         }

         t.display = null;
      }
   }

   private void removeDisplays() {
      for (ElixirFeature.Tank t : this.tanks.values()) {
         this.removeDisplay(t);
      }

      for (World w : Bukkit.getWorlds()) {
         for (BlockDisplay d : w.getEntitiesByClass(BlockDisplay.class)) {
            if (d.getPersistentDataContainer().has(this.displayKey)) {
               d.remove();
            }
         }
      }
   }

   private Location nodeLoc(String node) {
      if (node.startsWith("m:") || node.startsWith("a:")) {
         Location l = loc(node.substring(2));
         return l == null ? null : l.add(0.5, 0.5, 0.5);
      } else {
         ElixirFeature.Tank t = this.tanks.get(node.substring(2));
         if (t == null) {
            return null;
         } else {
            World w = Bukkit.getWorld(t.world);
            return w == null ? null : new Location(w, t.x + t.size / 2.0, t.y + t.layers + 0.1, t.z + t.size / 2.0);
         }
      }
   }

   private String nodeOf(Block b) {
      String k = key(b);
      if (this.machines.containsKey(k)) {
         return "m:" + k;
      } else {
         String owner = this.glassOwner.get(k);
         if (owner != null) {
            return "t:" + owner;
         } else {
            return this.isAltarBlock(b) ? "a:" + k : null;
         }
      }
   }

   private List<String> neighbors(String node) {
      List<String> out = new ArrayList<>();

      for (ElixirFeature.Link l : this.links) {
         if (l.a().equals(node)) {
            out.add(l.b());
         } else if (l.b().equals(node)) {
            out.add(l.a());
         }
      }

      if (node.startsWith("m:") || node.startsWith("a:")) {
         Location lx = loc(node.substring(2));
         if (lx != null) {
            for (BlockFace f : AROUND) {
               String owner = this.glassOwner.get(key(lx.getBlock().getRelative(f)));
               if (owner != null) {
                  out.add("t:" + owner);
               }
            }
         }
      }

      return out;
   }

   private Map<ElixirFeature.Tank, List<String>> networkTanks(String machineNode) {
      Map<ElixirFeature.Tank, List<String>> out = new LinkedHashMap<>();
      Map<String, String> parent = new HashMap<>();
      Deque<String> q = new ArrayDeque<>();
      q.add(machineNode);
      parent.put(machineNode, null);

      while (!q.isEmpty() && parent.size() < 256) {
         String n = q.poll();
         if (n.startsWith("t:")) {
            ElixirFeature.Tank t = this.tanks.get(n.substring(2));
            if (t != null && t.active) {
               List<String> path = new ArrayList<>();

               for (String c = n; c != null; c = parent.get(c)) {
                  path.add(0, c);
               }

               out.put(t, path);
            }
         }

         for (String m : this.neighbors(n)) {
            if (!parent.containsKey(m)) {
               parent.put(m, n);
               q.add(m);
            }
         }
      }

      return out;
   }

   private void pulse(List<String> path, int tier, boolean reverse) {
      List<Location> pts = new ArrayList<>();

      for (String n : path) {
         Location l = this.nodeLoc(n);
         if (l != null) {
            pts.add(l);
         }
      }

      if (pts.size() >= 2) {
         if (reverse) {
            Collections.reverse(pts);
         }

         final List<Location> line = new ArrayList<>();

         for (int i = 0; i + 1 < pts.size(); i++) {
            Location a = pts.get(i);
            Location b = pts.get(i + 1);
            if (a.getWorld() != b.getWorld()) {
               return;
            }

            double dist = a.distance(b);
            int steps = Math.max(1, (int)(dist / 0.5));

            for (int s = 0; s <= steps; s++) {
               line.add(a.clone().add(b.toVector().subtract(a.toVector()).multiply((double)s / steps)));
            }
         }

         final DustOptions col = dust(tier, 1.4F);
         (new BukkitRunnable() {
            int i = 0;

            public void run() {
               int k = 0;

               while (k < 3 && this.i < line.size()) {
                  Location p = line.get(this.i);
                  p.getWorld().spawnParticle(Particle.DUST, p, 2, 0.03, 0.03, 0.03, 0.0, col);
                  k++;
                  this.i++;
               }

               if (this.i >= line.size()) {
                  this.cancel();
               }
            }
         }).runTaskTimer(this.plugin, 0L, 1L);
      }
   }

   private boolean holdsWand(Player p) {
      return this.plugin.items().matches(p.getInventory().getItemInMainHand(), "windy:baguette_liaison");
   }

   private String nodeName(String node) {
      if (node.startsWith("a:")) {
         Location l = loc(node.substring(2));
         return l != null && l.getBlock().getType() == Material.LODESTONE ? "&5Sanctuaire d'Éveil" : "&5Autel de rituel";
      } else if (node.startsWith("m:")) {
         return "&d" + ("extracteur".equals(this.machines.get(node.substring(2))) ? "Extracteur" : "Infuseur");
      } else {
         ElixirFeature.Tank t = this.tanks.get(node.substring(2));
         return t == null ? "&dTank" : "&dTank " + t.size + "x" + t.size + "x" + t.layers;
      }
   }

   private void useWand(Player p, Block b) {
      String node = this.nodeOf(b);
      if (node == null) {
         if (this.glass.contains(key(b))) {
            p.sendActionBar(Text.c("&cCe tank n'est pas complet : carré creux de verre magique, 3x3 à " + this.maxSize + "x" + this.maxSize + "."));
         } else {
            p.sendActionBar(Text.c("&7Clique sur une machine, le verre d'un tank, la table d'un autel ou un cœur de sanctuaire infusé."));
         }
      } else if (p.isSneaking()) {
         int before = this.links.size();
         this.links.removeIf(lx -> lx.a().equals(node) || lx.b().equals(node));
         this.pending.remove(p.getUniqueId());
         this.dirty = true;
         p.sendActionBar(Text.c("&e✂ " + (before - this.links.size()) + " lien(s) retiré(s) &8| " + this.nodeName(node)));
         p.playSound(p.getLocation(), Sound.BLOCK_CHAIN_BREAK, 1.0F, 1.2F);
      } else {
         String first = this.pending.remove(p.getUniqueId());
         if (first != null && !first.equals(node) && this.nodeLoc(first) != null) {
            Location a = this.nodeLoc(first);
            Location c = this.nodeLoc(node);
            if (a.getWorld() == c.getWorld() && !(a.distance(c) > this.linkDistance)) {
               for (ElixirFeature.Link l : this.links) {
                  if (l.a().equals(first) && l.b().equals(node) || l.a().equals(node) && l.b().equals(first)) {
                     p.sendActionBar(Text.c("&7Ces deux éléments sont déjà reliés."));
                     return;
                  }
               }

               this.links.add(new ElixirFeature.Link(first, node));
               this.dirty = true;
               p.sendActionBar(Text.c("&a✦ Lien créé &8| " + this.nodeName(first) + " &8↔ " + this.nodeName(node)));
               p.playSound(p.getLocation(), Sound.BLOCK_BEACON_POWER_SELECT, 0.8F, 1.6F);
               this.pulse(List.of(first, node), 0, false);
            } else {
               p.sendActionBar(Text.c("&cTrop loin : " + this.linkDistance + " blocs maximum entre deux éléments."));
            }
         } else {
            this.pending.put(p.getUniqueId(), node);
            p.sendActionBar(Text.c("&d✦ " + this.nodeName(node) + " &7sélectionné : clique sur un autre élément pour les relier."));
            p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.0F, 1.4F);
         }
      }
   }

   private void tickWands() {
      for (Player p : Bukkit.getOnlinePlayers()) {
         if (this.holdsWand(p)) {
            Location eye = p.getLocation();

            for (ElixirFeature.Link l : this.links) {
               Location a = this.nodeLoc(l.a());
               Location b = this.nodeLoc(l.b());
               if (a != null
                  && b != null
                  && a.getWorld() == eye.getWorld()
                  && b.getWorld() == eye.getWorld()
                  && (!(a.distanceSquared(eye) > 2304.0) || !(b.distanceSquared(eye) > 2304.0))) {
                  int tier = 0;

                  for (String n : List.of(l.a(), l.b())) {
                     if (n.startsWith("t:")) {
                        ElixirFeature.Tank t = this.tanks.get(n.substring(2));
                        if (t != null && t.amount > 0) {
                           tier = t.tier;
                        }
                     }
                  }

                  DustOptions col = dust(tier, 0.7F);
                  Vector d = b.toVector().subtract(a.toVector());
                  double len = d.length();
                  int steps = Math.max(1, (int)(len / 0.35));

                  for (int s = 0; s <= steps; s++) {
                     p.spawnParticle(Particle.DUST, a.clone().add(d.clone().multiply((double)s / steps)), 1, 0.0, 0.0, 0.0, 0.0, col);
                  }
               }
            }

            String sel = this.pending.get(p.getUniqueId());
            if (sel != null) {
               Location lx = this.nodeLoc(sel);
               if (lx != null && lx.getWorld() == eye.getWorld()) {
                  p.spawnParticle(Particle.END_ROD, lx, 3, 0.25, 0.25, 0.25, 0.0);
               }
            }
         }
      }
   }

   @EventHandler(
      priority = EventPriority.HIGH
   )
   public void onUse(PlayerInteractEvent e) {
      if (e.getHand() == EquipmentSlot.HAND) {
         Player p = e.getPlayer();
         if (!this.holdsWand(p)) {
            if (e.getAction() == Action.RIGHT_CLICK_BLOCK && e.getClickedBlock() != null) {
               Block b = e.getClickedBlock();
               String k = key(b);
               ItemStack hand = p.getInventory().getItemInMainHand();
               String type = this.machines.get(k);
               if (type != null) {
                  if (!p.isSneaking() || hand.getType().isAir()) {
                     e.setCancelled(true);
                     if ("extracteur".equals(type)) {
                        this.extract(p, b, hand);
                     } else {
                        this.infuse(p, b, hand);
                     }
                  }
               } else {
                  if (this.glass.contains(k) && hand.getType().isAir()) {
                     e.setCancelled(true);
                     String owner = this.glassOwner.get(k);
                     ElixirFeature.Tank t = owner == null ? null : this.tanks.get(owner);
                     if (t == null) {
                        p.sendActionBar(
                           Text.c(
                              "&cTank incomplet &8| &7carré creux de verre magique, de 3x3 à "
                                 + this.maxSize
                                 + "x"
                                 + this.maxSize
                                 + ", sur 1 à "
                                 + this.maxLayers
                                 + " couches, vide à l'intérieur."
                           )
                        );
                        return;
                     }

                     p.sendActionBar(
                        Text.c(
                           "&d⚗ Tank "
                              + t.size
                              + "x"
                              + t.size
                              + "x"
                              + t.layers
                              + " &8| "
                              + fluidName(t.tier)
                              + " &8| &f"
                              + fmt(t.amount)
                              + " &7/ "
                              + fmt(this.capacity(t))
                              + " mB"
                        )
                     );
                  }
               }
            }
         } else {
            if (e.getAction() == Action.RIGHT_CLICK_BLOCK && e.getClickedBlock() != null) {
               e.setCancelled(true);
               this.useWand(p, e.getClickedBlock());
            } else if (e.getAction() == Action.RIGHT_CLICK_AIR && this.pending.remove(p.getUniqueId()) != null) {
               p.sendActionBar(Text.c("&7Sélection annulée."));
            }
         }
      }
   }

   private void extract(Player p, Block b, ItemStack hand) {
      int tier = berryTier(this.plugin.items().idOf(hand));
      if (tier == 0) {
         p.sendActionBar(Text.c("&7Extracteur : clic droit avec des &dbaies arcaniques&7."));
      } else {
         Map<ElixirFeature.Tank, List<String>> net = this.networkTanks("m:" + key(b));
         if (net.isEmpty()) {
            this.plugin.msg(p, "&cL'extracteur n'est relié à aucun &dtank magique &ccomplet (baguette de liaison, ou posé contre le tank).");
         } else {
            ElixirFeature.Tank target = null;

            for (ElixirFeature.Tank t : net.keySet()) {
               if (t.tier == tier && t.amount < this.capacity(t)) {
                  target = t;
                  break;
               }
            }

            if (target == null) {
               for (ElixirFeature.Tank tx : net.keySet()) {
                  if (tx.amount == 0) {
                     target = tx;
                     break;
                  }
               }
            }

            if (target == null) {
               this.plugin.msg(p, "&cAucun tank du réseau ne peut recevoir du " + fluidName(tier) + "&c (plein, ou il contient un autre tier).");
            } else {
               int cap = this.capacity(target);
               int n = Math.min(hand.getAmount(), (cap - target.amount) / this.perBerry);
               if (n <= 0) {
                  this.plugin.msg(p, "&cLe tank est plein.");
               } else {
                  hand.setAmount(hand.getAmount() - n);
                  target.tier = tier;
                  target.amount = target.amount + n * this.perBerry;
                  this.dirty = true;
                  this.updateDisplay(target);
                  Location c = b.getLocation().add(0.5, 1.0, 0.5);
                  b.getWorld().spawnParticle(Particle.DUST, c, 14, 0.25, 0.2, 0.25, dust(tier, 1.2F));
                  b.getWorld().playSound(c, Sound.ITEM_BOTTLE_FILL, 1.0F, 0.9F + 0.1F * tier);
                  this.pulse(net.get(target), tier, false);
                  p.sendActionBar(
                     Text.c(
                        "&d⚗ &f"
                           + n
                           + " baie(s) extraite(s) &8→ &f+"
                           + fmt((long)n * this.perBerry)
                           + " mB "
                           + fluidName(tier)
                           + " &8| &f"
                           + fmt(target.amount)
                           + " &7/ "
                           + fmt(cap)
                     )
                  );
               }
            }
         }
      }
   }

   private void infuse(Player p, Block b, ItemStack hand) {
      int tier = berryTier(this.plugin.items().idOf(hand));
      boolean amethyst = this.plugin.items().matches(hand, "AMETHYST_BLOCK");
      boolean lodestone = this.plugin.items().matches(hand, "LODESTONE");
      if (tier == 0 && !amethyst && !lodestone) {
         p.sendActionBar(Text.c("&7Infuseur : clic droit avec une &dbaie arcanique&7, un &dbloc d'améthyste &7ou une &dmagnétite&7."));
      } else {
         Map<ElixirFeature.Tank, List<String>> net = this.networkTanks("m:" + key(b));
         if (net.isEmpty()) {
            this.plugin.msg(p, "&cL'infuseur n'est relié à aucun &dtank magique &ccomplet (baguette de liaison, ou posé contre le tank).");
         } else {
            Location c = b.getLocation().add(0.5, 1.1, 0.5);
            World w = b.getWorld();
            if (lodestone) {
               ElixirFeature.Tank best = null;

               for (ElixirFeature.Tank t : net.keySet()) {
                  if (t.amount >= this.coreCost && (best == null || t.tier > best.tier)) {
                     best = t;
                  }
               }

               if (best == null) {
                  this.plugin.msg(p, "&cIl faut &f" + fmt(this.coreCost) + " mB &cde fluide dans un tank du réseau pour infuser la magnétite.");
               } else {
                  int ft = best.tier;
                  this.take(best, this.coreCost);
                  hand.setAmount(hand.getAmount() - 1);
                  this.give(p, this.plugin.items().create("magnetite_infusee_" + ft));
                  this.pulse(net.get(best), ft, true);
                  w.spawnParticle(Particle.DUST, c, 30, 0.3, 0.3, 0.3, dust(ft, 1.3F));
                  w.spawnParticle(Particle.REVERSE_PORTAL, c, 20, 0.2, 0.2, 0.2, 0.05);
                  w.playSound(c, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 1.0F, 1.2F);
                  this.plugin.msg(p, "&d⚗ &fMagnétite infusée de tier " + ROMAN[ft] + " &7(-" + fmt(this.coreCost) + " mB).");
               }
            } else if (amethyst) {
               ElixirFeature.Tank best = null;

               for (ElixirFeature.Tank t : net.keySet()) {
                  if (t.amount >= this.amethystCost && (best == null || t.tier > best.tier)) {
                     best = t;
                  }
               }

               if (best == null) {
                  this.plugin.msg(p, "&cIl faut &f" + this.amethystCost + " mB &cde fluide dans le réseau pour infuser l'améthyste.");
               } else {
                  int ft = best.tier;
                  this.take(best, this.amethystCost);
                  hand.setAmount(hand.getAmount() - 1);
                  this.give(p, this.plugin.items().create("amethyste_infusee_" + ft));
                  this.pulse(net.get(best), ft, true);
                  w.spawnParticle(Particle.DUST, c, 25, 0.3, 0.3, 0.3, dust(ft, 1.2F));
                  w.playSound(c, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1.0F, 1.2F);
                  this.plugin.msg(p, "&d⚗ &fAméthyste infusée de tier " + ROMAN[ft] + " &7(-" + this.amethystCost + " mB).");
               }
            } else if (tier >= 3) {
               this.plugin.msg(p, "&7Cette baie est déjà au tier maximum.");
            } else {
               int cost = this.upgradeCost[tier];
               ElixirFeature.Tank src = null;

               for (ElixirFeature.Tank tx : net.keySet()) {
                  if (tx.tier == tier && tx.amount >= cost) {
                     src = tx;
                     break;
                  }
               }

               if (src == null) {
                  this.plugin
                     .msg(
                        p,
                        "&cIl faut &f"
                           + fmt(cost)
                           + " mB &cde "
                           + fluidName(tier)
                           + " &cdans le réseau pour faire passer cette baie au tier "
                           + ROMAN[tier + 1]
                           + "."
                     );
               } else {
                  this.take(src, cost);
                  hand.setAmount(hand.getAmount() - 1);
                  this.pulse(net.get(src), tier, true);
                  if (this.rnd.nextDouble() < this.upgradeFail[tier]) {
                     w.spawnParticle(Particle.SMOKE, c, 20, 0.2, 0.2, 0.2, 0.02);
                     w.playSound(c, Sound.BLOCK_FIRE_EXTINGUISH, 0.8F, 1.0F);
                     this.plugin.msg(p, "&cL'infusion a échoué : la baie et le fluide sont perdus.");
                  } else {
                     this.give(p, this.plugin.items().create("baie_" + (tier + 1)));
                     w.spawnParticle(Particle.DUST, c, 30, 0.3, 0.3, 0.3, dust(tier + 1, 1.2F));
                     w.spawnParticle(Particle.END_ROD, c, 8, 0.2, 0.3, 0.2, 0.03);
                     w.playSound(c, Sound.BLOCK_BREWING_STAND_BREW, 1.0F, 1.3F);
                     this.plugin.msg(p, "&d⚗ &fInfusion réussie : " + this.plugin.items().displayName("windy:baie_" + (tier + 1)) + "&f !");
                  }
               }
            }
         }
      }
   }

   private void take(ElixirFeature.Tank t, int amount) {
      t.amount = Math.max(0, t.amount - amount);
      if (t.amount == 0) {
         t.tier = 0;
      }

      this.dirty = true;
      this.updateDisplay(t);
   }

   private void give(Player p, ItemStack it) {
      if (it != null) {
         for (ItemStack left : p.getInventory().addItem(new ItemStack[]{it}).values()) {
            p.getWorld().dropItemNaturally(p.getLocation(), left);
         }
      }
   }

   private void tickEffects() {
      for (Entry<String, Integer> e : this.infused.entrySet()) {
         Location l = loc(e.getKey());
         if (l != null && l.isChunkLoaded()) {
            l.getWorld().spawnParticle(Particle.DUST, l.add(0.5, 1.05, 0.5), 2, 0.15, 0.05, 0.15, dust(e.getValue(), 1.0F));
         }
      }

      for (Entry<String, Integer> e : this.cores.entrySet()) {
         Location l = loc(e.getKey());
         if (l != null && l.isChunkLoaded()) {
            l.getWorld().spawnParticle(Particle.DUST, l.add(0.5, 1.05, 0.5), 3, 0.2, 0.05, 0.2, dust(e.getValue(), 1.1F));
         }
      }

      for (ElixirFeature.Tank t : this.tanks.values()) {
         if (t.active && t.amount > 0) {
            this.updateDisplay(t);
            World w = Bukkit.getWorld(t.world);
            if (w != null && w.isChunkLoaded(t.x + t.size / 2 >> 4, t.z + t.size / 2 >> 4)) {
               double h = t.layers * ((double)t.amount / this.capacity(t));
               w.spawnParticle(
                  Particle.DUST,
                  new Location(w, t.x + t.size / 2.0, t.y + h + 0.1, t.z + t.size / 2.0),
                  3,
                  (t.size - 2) / 3.0,
                  0.05,
                  (t.size - 2) / 3.0,
                  0.0,
                  dust(t.tier, 1.0F)
               );
            }
         }
      }

      for (Entry<String, String> ex : this.machines.entrySet()) {
         Location l = loc(ex.getKey());
         if (l != null && l.isChunkLoaded()) {
            l.getWorld().spawnParticle(Particle.ENCHANT, l.add(0.5, 1.2, 0.5), 3, 0.2, 0.1, 0.2, 0.3);
         }
      }
   }

   private record Link(String a, String b) {
   }

   private static final class Tank {
      String key;
      String world;
      int x;
      int y;
      int z;
      int size;
      int layers;
      int tier;
      int amount;
      boolean active;
      UUID display;
   }
}
