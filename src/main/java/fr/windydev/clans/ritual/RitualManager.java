package fr.windydev.clans.ritual;

import fr.windydev.clans.WindyClans;
import fr.windydev.clans.elixir.ElixirFeature;
import fr.windydev.clans.model.Clan;
import fr.windydev.clans.model.Rank;
import fr.windydev.clans.util.Text;
import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.title.Title;
import net.kyori.adventure.title.Title.Times;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.Particle.DustOptions;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Display.Billboard;
import org.bukkit.entity.Display.Brightness;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

public class RitualManager implements Listener {
   private static final BlockFace[] DIRS = new BlockFace[]{BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST};
   private final WindyClans plugin;
   private final Map<Block, RitualManager.Altar> altars = new HashMap<>();
   private final Map<String, RitualManager.Recipe> recipes = new LinkedHashMap<>();
   private Material centerMat = Material.ENCHANTING_TABLE;
   private Material pedestalMat = Material.AMETHYST_BLOCK;
   private Material cornerMat = Material.CRYING_OBSIDIAN;
   private Material baseMat = Material.DIAMOND_BLOCK;
   private final int[] tierDuration = new int[]{30, 30, 90, 180};
   private static final int[][] CORNERS = new int[][]{{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
   private AltarVisuals visuals;
   private int distance = 3;
   private int interruptDistance = 8;
   private int xpReward = 50;
   private boolean dropOnFail = true;
   private boolean announce = true;
   private Rank rank = Rank.MEMBRE;
   private final File returnsFile;
   private boolean requireInfused = true;
   private boolean requireLink = true;
   private final int[] fluidCost = new int[]{0, 2000, 1500, 600};
   private final Map<String, Integer> recipeFluid = new HashMap<>();

   public RitualManager(WindyClans plugin) {
      this.plugin = plugin;
      this.returnsFile = new File(plugin.getDataFolder(), "ritual-returns.yml");
   }

   public void load() {
      File f = new File(this.plugin.getDataFolder(), "rituals.yml");
      if (!f.exists()) {
         this.plugin.saveResource("rituals.yml", false);
      }

      YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
      if (y.getInt("config-version", 1) < 3) {
         File old = new File(this.plugin.getDataFolder(), "rituals-ancien.yml");
         if (old.exists()) {
            old.delete();
         }

         f.renameTo(old);
         this.plugin.saveResource("rituals.yml", true);
         y = YamlConfiguration.loadConfiguration(f);
         this.plugin.getLogger().info("rituals.yml mis à jour (tiers d'autel + armes). Ancienne version : rituals-ancien.yml");
      }

      y = fr.windydev.clans.util.YamlMerge.addNew(this.plugin, f, y, "rituals.yml", "rituals");
      this.centerMat = mat(y.getString("settings.altar.center"), Material.ENCHANTING_TABLE);
      this.pedestalMat = mat(y.getString("settings.altar.pedestal"), Material.AMETHYST_BLOCK);
      this.distance = y.getInt("settings.altar.distance", 3);
      this.cornerMat = mat(y.getString("settings.altar.tier-2-corners"), Material.CRYING_OBSIDIAN);
      this.baseMat = mat(y.getString("settings.altar.tier-3-base"), Material.DIAMOND_BLOCK);

      for (int t = 1; t <= 3; t++) {
         this.tierDuration[t] = y.getInt("settings.tier-duration." + t, this.tierDuration[t]);
      }

      this.rank = Rank.parse(y.getString("settings.rank"), Rank.MEMBRE);
      this.interruptDistance = y.getInt("settings.interrupt-distance", 8);
      this.dropOnFail = y.getBoolean("settings.drop-offerings-on-fail", true);
      this.xpReward = y.getInt("settings.clan-xp-reward", 50);
      this.announce = y.getBoolean("settings.announce", true);
      this.requireInfused = y.getBoolean("settings.infused.require-infused-pedestals", true);
      this.requireLink = y.getBoolean("settings.infused.require-link", true);

      for (int t = 1; t <= 3; t++) {
         this.fluidCost[t] = Math.max(0, y.getInt("settings.infused.fluid-cost." + t, this.fluidCost[t]));
      }

      this.recipes.clear();
      this.recipeFluid.clear();
      ConfigurationSection rs = y.getConfigurationSection("rituals");
      if (rs != null) {
         for (String id : rs.getKeys(false)) {
            ConfigurationSection s = rs.getConfigurationSection(id);
            List<RitualManager.Offering> offs = new ArrayList<>();

            for (String o : s.getStringList("offerings")) {
               RitualManager.Offering of = RitualManager.Offering.parse(o);
               if (of == null) {
                  this.plugin.getLogger().warning("rituals.yml : offrande inconnue '" + o + "' (" + id + ")");
               } else {
                  offs.add(of);
               }
            }

            if (offs.size() > 4) {
               offs = offs.subList(0, 4);
            }

            int tier = Math.max(1, Math.min(3, s.getInt("tier", 1)));
            this.recipeFluid.put(id, Math.max(0, s.getInt("fluid", this.fluidCost[tier])));
            this.recipes
               .put(
                  id,
                  new RitualManager.Recipe(
                     id,
                     s.getString("name", id),
                     s.getString("focus", "DIAMOND_PICKAXE"),
                     offs,
                     s.getString("result", id),
                     s.getBoolean("keep-enchantments", true),
                     s.getInt("clan-level", 1),
                     Math.max(3, s.getInt("duration", this.tierDuration[tier])),
                     s.getBoolean("night-only", false),
                     tier
                  )
               );
         }
      }

      this.plugin.getLogger().info(this.recipes.size() + " rituel(s) chargé(s).");
   }

   private static Material mat(String s, Material def) {
      Material m = s == null ? null : Material.matchMaterial(s);
      return m == null ? def : m;
   }

   /** Rituels proposés pour cet autel : ceux de rituals.yml + les gravures possibles sur l'objet posé. */
   public List<RitualManager.Recipe> recipesFor(RitualManager.Altar a) {
      List<RitualManager.Recipe> out = new ArrayList<>(this.recipes.values());
      ItemStack focus = a == null ? null : a.focus.item;
      if (focus != null && this.plugin.glyphs() != null) {
         for (fr.windydev.clans.forge.GlyphManager.Option o : this.plugin.glyphs().options(focus)) {
            List<RitualManager.Offering> offs = new ArrayList<>();
            for (String spec : o.offerings()) {
               RitualManager.Offering of = RitualManager.Offering.parse(spec);
               if (of != null) {
                  offs.add(of);
               }
            }

            this.recipeFluid.put(o.id(), o.fluid());
            out.add(new RitualManager.Recipe(o.id(), o.name(), "windy:" + this.plugin.items().idOf(focus), offs, o.result(), true, 1, o.duration(), false, o.tier()));
         }
      }

      return out;
   }

   public Collection<RitualManager.Recipe> recipes() {
      return this.recipes.values();
   }

   public Material centerMaterial() {
      return this.centerMat;
   }

   public Material pedestalMaterial() {
      return this.pedestalMat;
   }

   public int distance() {
      return this.distance;
   }

   public void start() {
      Bukkit.getScheduler().runTaskTimer(this.plugin, this::tick, 2L, 2L);
      this.visuals = new AltarVisuals(this.plugin, this);
      Bukkit.getPluginManager().registerEvents(this.visuals, this.plugin);
      this.visuals.start();
   }

   public Material cornerMaterial() {
      return this.cornerMat;
   }

   public Material baseMaterial() {
      return this.baseMat;
   }

   public int tierDuration(int t) {
      return this.tierDuration[Math.max(1, Math.min(3, t))];
   }

   public int tier(Block center) {
      if (!this.validStructure(center)) {
         return 0;
      } else {
         int cap = this.infusedCap(center);

         for (int[] c : CORNERS) {
            if (center.getRelative(c[0] * this.distance, 0, c[1] * this.distance).getType() != this.cornerMat) {
               return Math.min(1, cap);
            }
         }

         return Math.min(center.getRelative(BlockFace.DOWN).getType() != this.baseMat ? 2 : 3, cap);
      }
   }

   /** Plus petit tier d'améthyste infusée parmi les 4 piliers (3 si l'infusion n'est pas exigée). */
   public int infusedCap(Block center) {
      if (!this.requireInfused || this.plugin.elixirs() == null) {
         return 3;
      } else {
         int min = 3;

         for (BlockFace d : DIRS) {
            min = Math.min(min, this.plugin.elixirs().infusedTier(center.getRelative(d, this.distance)));
         }

         return min;
      }
   }

   public int fluidCost(RitualManager.Recipe r) {
      return this.recipeFluid.getOrDefault(r.id(), this.fluidCost[Math.max(1, Math.min(3, r.tier()))]);
   }

   public List<Block> pedestals(Block center) {
      List<Block> l = new ArrayList<>();

      for (BlockFace d : DIRS) {
         l.add(center.getRelative(d, this.distance));
      }

      return l;
   }

   public List<Block> corners(Block center) {
      List<Block> l = new ArrayList<>();

      for (int[] c : CORNERS) {
         l.add(center.getRelative(c[0] * this.distance, 0, c[1] * this.distance));
      }

      return l;
   }

   public boolean validStructure(Block center) {
      if (center.getType() != this.centerMat) {
         return false;
      } else {
         for (BlockFace d : DIRS) {
            Block ped = center.getRelative(d, this.distance);
            if (ped.getType() != this.pedestalMat) {
               return false;
            }

            if (this.requireInfused && this.plugin.elixirs() != null && this.plugin.elixirs().infusedTier(ped) <= 0) {
               return false;
            }
         }

         return true;
      }
   }

   /** Structure d'autel posée avec de l'améthyste normale : on prévient le joueur. */
   private boolean plainAltar(Block center) {
      if (center.getType() != this.centerMat || !this.requireInfused) {
         return false;
      } else {
         for (BlockFace d : DIRS) {
            if (center.getRelative(d, this.distance).getType() != this.pedestalMat) {
               return false;
            }
         }

         return !this.validStructure(center);
      }
   }

   private Block centerOfPedestal(Block b) {
      if (b.getType() != this.pedestalMat) {
         return null;
      } else {
         for (BlockFace d : DIRS) {
            Block c = b.getRelative(d, this.distance);
            if (this.validStructure(c)) {
               return c;
            }
         }

         return null;
      }
   }

   private int pedestalIndex(Block center, Block pedestal) {
      for (int i = 0; i < 4; i++) {
         if (center.getRelative(DIRS[i], this.distance).equals(pedestal)) {
            return i;
         }
      }

      return -1;
   }

   private RitualManager.Altar altar(Block center) {
      return this.altars.computeIfAbsent(center, RitualManager.Altar::new);
   }

   @EventHandler(
      priority = EventPriority.HIGH
   )
   public void onInteract(PlayerInteractEvent e) {
      if (e.getAction() == Action.RIGHT_CLICK_BLOCK && e.getHand() == EquipmentSlot.HAND && e.getClickedBlock() != null) {
         Player p = e.getPlayer();
         Block b = e.getClickedBlock();
         if (this.plugin.items().matches(p.getInventory().getItemInMainHand(), "windy:baguette_liaison")) {
            return;
         }

         if (!p.isSneaking() && this.plainAltar(b)) {
            p.sendActionBar(Text.c("&5✦ &7Autel inactif : les 4 piliers doivent être en &dAméthyste infusée &7(infuseur magique)."));
         }

         if (b.getType() == this.centerMat && this.validStructure(b)) {
            if (this.visuals != null) {
               this.visuals.refreshLater(b);
            }

            if (!p.isSneaking()) {
               e.setCancelled(true);
               if (this.canUse(p, b)) {
                  RitualManager.Altar a = this.altar(b);
                  ItemStack hand = p.getInventory().getItemInMainHand();
                  if (a.running()) {
                     this.plugin.msg(p, "&dUn rituel est en cours...");
                  } else if (!hand.getType().isAir() && a.focus.item == null && this.isPossibleFocus(hand)) {
                     a.focus.item = hand.asOne();
                     a.focus.owner = p.getUniqueId();
                     hand.setAmount(hand.getAmount() - 1);
                     this.show(a, a.focus, this.focusLocation(b), 0.55F);
                     p.playSound(b.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1.0F, 1.2F);
                     p.sendActionBar(Text.c("&dObjet posé sur l'autel. &7Clic sur la table pour ouvrir le grimoire."));
                  } else {
                     this.plugin.menus().grimoire(p, a);
                  }
               }
            }
         } else {
            Block center = this.centerOfPedestal(b);
            if (center != null) {
               e.setCancelled(true);
               if (!this.canUse(p, center)) {
                  return;
               }

               RitualManager.Altar a = this.altar(center);
               if (a.running()) {
                  this.plugin.msg(p, "&dUn rituel est en cours...");
                  return;
               }

               int idx = this.pedestalIndex(center, b);
               RitualManager.Slot s = a.pedestals[idx];
               ItemStack hand = p.getInventory().getItemInMainHand();
               if (s.item == null && !hand.getType().isAir()) {
                  s.item = hand.clone();
                  s.owner = p.getUniqueId();
                  p.getInventory().setItemInMainHand(null);
                  this.show(a, s, this.pedestalLocation(center, idx), 0.45F);
                  p.playSound(b.getLocation(), Sound.BLOCK_AMETHYST_CLUSTER_PLACE, 1.0F, 1.0F);
               } else if (s.item != null) {
                  this.give(p, s.item);
                  this.clear(s);
                  p.playSound(b.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1.0F, 1.0F);
               }
            }
         }
      }
   }

   private boolean canUse(Player p, Block center) {
      if (this.plugin.bypass().contains(p.getUniqueId())) {
         return true;
      } else {
         Clan mine = this.plugin.clans().of(p);
         Clan owner = this.plugin.clans().at(center.getLocation());
         if (mine == null) {
            this.plugin.msg(p, "&cIl faut un clan pour utiliser un autel.");
            return false;
         } else if (owner != mine) {
            this.plugin
               .msg(
                  p, owner == null ? "&cUn autel ne fonctionne que dans le territoire de ton clan." : "&cCet autel appartient à " + owner.coloredName() + "&c."
               );
            return false;
         } else {
            return true;
         }
      }
   }

   private boolean isPossibleFocus(ItemStack it) {
      if (this.plugin.glyphs() != null && this.plugin.glyphs().eligible(it)) {
         return true;
      }

      for (RitualManager.Recipe r : this.recipes.values()) {
         if (this.plugin.items().matches(it, r.focus())) {
            return true;
         }
      }

      return false;
   }

   private Location focusLocation(Block center) {
      return center.getLocation().add(0.5, 1.45, 0.5);
   }

   private Location pedestalLocation(Block center, int i) {
      return center.getRelative(DIRS[i], this.distance).getLocation().add(0.5, 1.5, 0.5);
   }

   private void show(RitualManager.Altar a, RitualManager.Slot s, Location loc, float scale) {
      if (s.display != null && s.display.isValid()) {
         s.display.remove();
      }

      s.display = (ItemDisplay)loc.getWorld().spawn(loc, ItemDisplay.class, d -> {
         d.setItemStack(s.item);
         d.setPersistent(false);
         d.setBillboard(Billboard.FIXED);
         d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(scale, scale, scale), new AxisAngle4f()));
         d.setBrightness(new Brightness(15, 15));
         d.setInterpolationDuration(2);
         d.setTeleportDuration(2);
      });
   }

   private void clear(RitualManager.Slot s) {
      if (s.display != null && s.display.isValid()) {
         s.display.remove();
      }

      s.display = null;
      s.item = null;
      s.owner = null;
   }

   private void give(Player p, ItemStack it) {
      p.getInventory().addItem(new ItemStack[]{it}).values().forEach(rest -> p.getWorld().dropItemNaturally(p.getLocation(), rest));
   }

   public void takeFocus(Player p, RitualManager.Altar a) {
      if (!a.running() && a.focus.item != null) {
         this.give(p, a.focus.item);
         this.clear(a.focus);
      }
   }

   public ItemStack focusItem(RitualManager.Altar a) {
      return a.focus.item;
   }

   public List<String> missing(Player p, RitualManager.Altar a, RitualManager.Recipe r) {
      List<String> out = new ArrayList<>();
      Clan c = this.plugin.clans().of(p);
      if (c == null) {
         out.add("&cAvoir un clan");
         return out;
      } else {
         if (c.level() < r.clanLevel()) {
            out.add("&cClan niveau " + r.clanLevel() + " &7(actuel : " + c.level() + ")");
         }

         if (!c.rank(p.getUniqueId()).atLeast(this.rank)) {
            out.add("&cGrade " + this.rank.display() + " minimum");
         }

         if (r.nightOnly() && this.isDay(a.center.getWorld())) {
            out.add("&cAttendre la nuit");
         }

         int t = this.tier(a.center);
         if (t < r.tier()) {
            out.add("&cAutel de tier " + roman(r.tier()) + " &7(celui-ci : tier " + roman(t) + ")");
         }

         if (a.focus.item == null || !this.plugin.items().matches(a.focus.item, r.focus())) {
            out.add("&cPoser &f" + Text.strip(this.plugin.items().displayName(r.focus())) + " &csur la table");
         }

         for (RitualManager.Offering o : this.unmatchedOfferings(a, r)) {
            out.add("&cOffrande : &f" + o.amount() + "x " + Text.strip(this.plugin.items().displayName(o.spec())));
         }

         if (r.result().startsWith("@") && this.plugin.glyphs() != null) {
            out.addAll(this.plugin.glyphs().missing(a.focus.item, r.result()));
         }

         int cost = this.fluidCost(r);
         if (this.plugin.elixirs() != null && (cost > 0 || this.requireLink)) {
            if (!this.plugin.elixirs().linked(a.center)) {
               out.add("&cRelier l'autel à un &dtank magique &c(baguette de liaison)");
            } else if (cost > 0) {
               int have = this.plugin.elixirs().fluidAvailable(a.center, r.tier());
               if (have < cost) {
                  out.add(
                     "&cFluide : &f"
                        + ElixirFeature.format(cost)
                        + " mB &7de "
                        + ElixirFeature.fluidLabel(r.tier())
                        + " &7ou plus (réseau : "
                        + ElixirFeature.format(have)
                        + ")"
                  );
               }
            }
         }

         return out;
      }
   }

   public static String roman(int t) {
      return switch (t) {
         case 1 -> "I";
         case 2 -> "II";
         case 3 -> "III";
         default -> "0";
      };
   }

   private boolean isDay(World w) {
      long t = w.getTime();
      return t < 12500L || t > 23500L;
   }

   private List<RitualManager.Offering> unmatchedOfferings(RitualManager.Altar a, RitualManager.Recipe r) {
      List<RitualManager.Offering> left = new ArrayList<>(r.offerings());
      boolean[] used = new boolean[4];
      Iterator<RitualManager.Offering> it = left.iterator();

      while (it.hasNext()) {
         RitualManager.Offering o = it.next();

         for (int i = 0; i < 4; i++) {
            ItemStack s = a.pedestals[i].item;
            if (!used[i] && s != null && this.plugin.items().matches(s, o.spec()) && s.getAmount() >= o.amount()) {
               used[i] = true;
               it.remove();
               break;
            }
         }
      }

      return left;
   }

   public void launch(Player p, RitualManager.Altar a, RitualManager.Recipe r) {
      if (a.running()) {
         this.plugin.msg(p, "&cUn rituel est déjà en cours.");
      } else if (!this.validStructure(a.center)) {
         this.plugin.msg(p, "&cL'autel est incomplet.");
      } else if (this.canUse(p, a.center)) {
         List<String> miss = this.missing(p, a, r);
         if (!miss.isEmpty()) {
            this.plugin.msg(p, "&cImpossible : " + String.join("&7, ", miss));
         } else {
            Clan c = this.plugin.clans().of(p);
            int cost = this.fluidCost(r);
            if (this.plugin.elixirs() != null && cost > 0 && !this.plugin.elixirs().drawFluid(a.center, r.tier(), cost)) {
               this.plugin.msg(p, "&cPas assez de fluide magique dans le réseau relié à l'autel.");
               return;
            }

            if (cost > 0) {
               this.plugin.msg(p, "&d⚗ &7L'autel absorbe &f" + ElixirFeature.format(cost) + " mB &7de fluide magique.");
            }

            RitualManager.Run run = new RitualManager.Run();
            run.recipe = r;
            run.caster = p.getUniqueId();
            run.clan = c.id();
            run.start = System.currentTimeMillis();
            double speed = Math.max(0.0, Math.min(0.9, this.plugin.profiles().stat(p, "ritual-speed")));
            run.total = (int)Math.max(40L, Math.round(r.duration() * 20 * (1.0 - speed)));
            int secs = run.total / 20;
            a.run = run;
            Location loc = a.center.getLocation().add(0.5, 1.0, 0.5);
            loc.getWorld().playSound(loc, Sound.ENTITY_EVOKER_PREPARE_SUMMON, 1.5F, 0.8F);
            p.showTitle(
               Title.title(
                  Text.c("&5✦ Rituel &5✦"),
                  Text.c("&dReste près de l'autel " + secs + " secondes"),
                  Times.times(Duration.ofMillis(300L), Duration.ofSeconds(2L), Duration.ofMillis(500L))
               )
            );
            this.plugin.clans().broadcast(c, "&d✦ &f" + p.getName() + " &7a lancé le rituel &d" + Text.strip(r.name()) + "&7. Protégez l'autel !");
            this.plugin
               .bot()
               .staffLog(
                  "✨ Rituel lancé",
                  "**"
                     + c.name()
                     + "** — "
                     + Text.strip(r.name())
                     + " par "
                     + p.getName()
                     + "\nPosition : "
                     + loc.getWorld().getName()
                     + " "
                     + loc.getBlockX()
                     + " "
                     + loc.getBlockY()
                     + " "
                     + loc.getBlockZ()
               );
            if (this.announce) {
               for (Player o : Bukkit.getOnlinePlayers()) {
                  if (this.plugin.clans().of(o) != c) {
                     this.plugin
                        .msg(
                           o,
                           "&5⚠ &fLe clan "
                              + c.coloredName()
                              + " &fa lancé un rituel &7"
                              + direction(o.getLocation(), loc)
                              + "&f. Il se termine dans &d"
                              + secs
                              + " s&f."
                        );
                     o.playSound(o.getLocation(), Sound.BLOCK_BELL_RESONATE, 0.6F, 0.7F);
                  }
               }
            }
         }
      }
   }

   static String direction(Location from, Location to) {
      if (from.getWorld() != to.getWorld()) {
         return "dans une autre dimension";
      } else {
         double dx = to.getX() - from.getX();
         double dz = to.getZ() - from.getZ();
         double dist = Math.sqrt(dx * dx + dz * dz);
         if (dist < 40.0) {
            return "tout près de toi";
         } else {
            double ang = Math.toDegrees(Math.atan2(dx, -dz));
            if (ang < 0.0) {
               ang += 360.0;
            }

            String[] names = new String[]{"au nord", "au nord-est", "à l'est", "au sud-est", "au sud", "au sud-ouest", "à l'ouest", "au nord-ouest"};
            String dir = names[(int)Math.round(ang / 45.0) % 8];
            long rounded = Math.max(50L, Math.round(dist / 50.0) * 50L);
            return dir + ", à environ " + rounded + " blocs";
         }
      }
   }

   private void tick() {
      for (RitualManager.Altar a : new ArrayList<>(this.altars.values())) {
         float yaw = (float)(System.currentTimeMillis() % 7200L) / 20.0F;
         this.spin(a.focus, yaw);

         for (RitualManager.Slot s : a.pedestals) {
            this.spin(s, -yaw);
         }

         if (a.run != null) {
            RitualManager.Run run = a.run;
            run.ticks += 2;
            Player caster = Bukkit.getPlayer(run.caster);
            Location c = a.center.getLocation().add(0.5, 1.2, 0.5);
            if (caster == null || !caster.isOnline() || caster.getWorld() != c.getWorld() || caster.getLocation().distance(c) > this.interruptDistance) {
               this.fail(a, caster == null ? "le lanceur est parti" : "le lanceur s'est éloigné de l'autel");
            } else if (this.tier(a.center) < run.recipe.tier()) {
               this.fail(a, "l'autel a été abîmé");
            } else {
               double total = run.total > 0 ? run.total : run.recipe.duration() * 20.0;
               double prog = Math.min(1.0, run.ticks / total);
               this.effects(a, c, prog, run.ticks);
               int left = (int)Math.ceil((total - run.ticks) / 20.0);
               if (run.ticks % 20 == 0) {
                  for (Player near : c.getNearbyPlayers(16.0)) {
                     near.sendActionBar(Text.c("&5✦ &d" + Text.strip(run.recipe.name()) + " &8| &f" + left + " s &8| " + this.bar(prog)));
                  }
               }

               if (run.ticks >= total) {
                  this.succeed(a);
               }
            }
         } else if (a.focus.item == null && Arrays.stream(a.pedestals).allMatch(s -> s.item == null)) {
            this.altars.remove(a.center);
         }
      }
   }

   private void spin(RitualManager.Slot s, float yaw) {
      if (s.display != null && s.display.isValid()) {
         Location l = s.display.getLocation();
         l.setYaw(yaw * 3.0F);
         s.display.teleport(l);
      }
   }

   private String bar(double prog) {
      int n = (int)Math.round(prog * 12.0);
      return "&d" + "|".repeat(n) + "&8" + "|".repeat(12 - n);
   }

   private void effects(RitualManager.Altar a, Location c, double prog, int ticks) {
      World w = c.getWorld();
      double t = ticks / 6.0;

      for (int k = 0; k < 2; k++) {
         double ang = t + k * Math.PI;
         double rad = 2.6 * (1.0 - prog * 0.6);
         w.spawnParticle(Particle.WITCH, c.getX() + Math.cos(ang) * rad, c.getY() + prog * 2.0, c.getZ() + Math.sin(ang) * rad, 1, 0.0, 0.0, 0.0, 0.0);
      }

      DustOptions gold = new DustOptions(Color.fromRGB(232, 184, 76), 0.9F);

      for (int i = 0; i < 4; i++) {
         Location from = this.pedestalLocation(a.center, i);
         Vector v = c.toVector().add(new Vector(0.0, 0.3, 0.0)).subtract(from.toVector());
         double f = ticks % 20 / 20.0;
         Location pt = from.clone().add(v.clone().multiply(f));
         w.spawnParticle(Particle.DUST, pt, 1, 0.0, 0.0, 0.0, 0.0, gold);
      }

      int tier = this.tier(a.center);
      if (tier >= 2) {
         DustOptions vio = new DustOptions(Color.fromRGB(200, 120, 255), 0.8F);
         double f = (ticks + 10) % 20 / 20.0;

         for (Block k : this.corners(a.center)) {
            Location from = k.getLocation().add(0.5, 1.2, 0.5);
            Vector v = c.toVector().add(new Vector(0.0, 0.3, 0.0)).subtract(from.toVector());
            w.spawnParticle(Particle.DUST, from.clone().add(v.multiply(f)), 1, 0.0, 0.0, 0.0, 0.0, vio);
         }
      }

      if (tier >= 3 && ticks % 10 == 0) {
         w.spawnParticle(Particle.END_ROD, c.clone().add(0.0, 2.0 + prog * 2.0, 0.0), 2, 0.2, 0.2, 0.2, 0.02);
      }

      w.spawnParticle(Particle.ENCHANT, c.clone().add(0.0, 0.6, 0.0), 6, 0.4, 0.4, 0.4, 0.8);
      if (a.focus.display != null && a.focus.display.isValid()) {
         Location l = this.focusLocation(a.center).add(0.0, prog * 1.2, 0.0);
         l.setYaw(a.focus.display.getLocation().getYaw());
         a.focus.display.teleport(l);
      }

      if (ticks % 40 == 0) {
         w.playSound(c, Sound.BLOCK_BEACON_AMBIENT, 1.0F, 0.6F + (float)prog);
      }

      if (ticks % 100 == 0) {
         w.playSound(c, Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1.0F, 0.8F);
      }
   }

   private void succeed(RitualManager.Altar a) {
      RitualManager.Run run = a.run;
      RitualManager.Recipe r = run.recipe;
      Player caster = Bukkit.getPlayer(run.caster);
      Clan clan = this.plugin.clans().get(run.clan);
      Location c = a.center.getLocation().add(0.5, 1.2, 0.5);
      a.run = null;
      boolean[] used = new boolean[4];

      for (RitualManager.Offering o : r.offerings()) {
         for (int i = 0; i < 4; i++) {
            ItemStack s = a.pedestals[i].item;
            if (!used[i] && s != null && this.plugin.items().matches(s, o.spec()) && s.getAmount() >= o.amount()) {
               used[i] = true;
               int rest = s.getAmount() - o.amount();
               if (caster != null && this.plugin.skills().ingredientSaved(caster)) {
                  rest++;
                  this.plugin.msg(caster, "&d✦ Économie : une offrande t'est rendue.");
               }

               if (rest > 0 && caster != null) {
                  ItemStack back = s.clone();
                  back.setAmount(rest);
                  this.give(caster, back);
               }

               this.clear(a.pedestals[i]);
               break;
            }
         }
      }

      this.plugin.profiles().ritualDone(caster, r.tier());
      ItemStack result;
      if (r.result().startsWith("@")) {
         result = this.plugin.glyphs().apply(a.focus.item, r.result());
      } else {
         result = this.plugin.items().transform(a.focus.item, r.result(), r.keepEnchants());
         if (result != null && this.plugin.glyphs() != null) {
            this.plugin.glyphs().carry(a.focus.item, result);
         }
      }

      this.clear(a.focus);
      if (result == null) {
         this.plugin.getLogger().warning("Rituel " + r.id() + " : objet résultat inconnu '" + r.result() + "' (items.yml)");
      } else {
         World w = c.getWorld();
         w.strikeLightningEffect(c);
         w.spawnParticle(Particle.TOTEM_OF_UNDYING, c, 80, 0.6, 0.8, 0.6, 0.4);
         w.spawnParticle(Particle.END_ROD, c, 40, 0.3, 1.2, 0.3, 0.05);
         w.playSound(c, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0F, 1.0F);
         if (caster != null && caster.isOnline()) {
            this.give(caster, result);
            caster.showTitle(Title.title(Text.c("&6✦ Rituel accompli ✦"), Text.item(r.name()).append(Text.c(""))));
         } else {
            w.dropItemNaturally(c, result);
         }

         if (clan != null) {
            this.plugin.clans().addXp(clan, this.xpReward);
            this.plugin.clans().log(clan, (caster == null ? "?" : caster.getName()) + " a accompli le rituel " + Text.strip(r.name()));
            Bukkit.broadcast(
               Text.c(this.plugin.settings().prefix + "&6✦ &fLe clan " + clan.coloredName() + " &fa accompli le rituel &d" + Text.strip(r.name()) + "&f !")
            );
            this.plugin.discord().send("war", "✨ Le clan **" + clan.name() + "** a accompli le rituel **" + Text.strip(r.name()) + "** !");
            this.plugin
               .bot()
               .staffLog("✨ Rituel réussi", "**" + clan.name() + "** — " + Text.strip(r.name()) + " par " + (caster == null ? "?" : caster.getName()));
            this.plugin.bot().countRitual();
         }
      }
   }

   private void fail(RitualManager.Altar a, String reason) {
      RitualManager.Run run = a.run;
      a.run = null;
      Clan clan = this.plugin.clans().get(run.clan);
      Location c = a.center.getLocation().add(0.5, 1.2, 0.5);
      World w = c.getWorld();
      w.spawnParticle(Particle.EXPLOSION, c, 3, 0.5, 0.5, 0.5, 0.0);
      w.spawnParticle(Particle.SMOKE, c, 40, 1.0, 0.5, 1.0, 0.05);
      w.playSound(c, Sound.ENTITY_WITHER_SPAWN, 0.6F, 1.6F);
      if (a.focus.item != null) {
         w.dropItemNaturally(c, a.focus.item);
      }

      this.clear(a.focus);

      for (int i = 0; i < 4; i++) {
         RitualManager.Slot s = a.pedestals[i];
         if (s.item != null && this.dropOnFail) {
            w.dropItemNaturally(this.pedestalLocation(a.center, i), s.item);
         }

         this.clear(s);
      }

      if (clan != null) {
         this.plugin.clans().broadcast(clan, "&c✖ Rituel interrompu : " + reason + ".");
         this.plugin.bot().staffLog("✖ Rituel interrompu", "**" + clan.name() + "** — " + Text.strip(run.recipe.name()) + " : " + reason);
         if (this.announce) {
            Bukkit.broadcast(Text.c(this.plugin.settings().prefix + "&7Le rituel du clan " + clan.coloredName() + " &7a été &cinterrompu &7!"));
         }
      }
   }

   @EventHandler(
      priority = EventPriority.MONITOR
   )
   public void onHit(EntityDamageByEntityEvent e) {
      if (e.getEntity() instanceof Player victim) {
         Player attacker = e.getDamager() instanceof Player pl
            ? pl
            : (e.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player pl2 ? pl2 : null);
         if (attacker != null && attacker != victim) {
            for (RitualManager.Altar a : this.altars.values()) {
               if (a.run != null && a.run.caster.equals(victim.getUniqueId())) {
                  Clan ac = this.plugin.clans().of(attacker);
                  if (ac == null || !ac.id().equals(a.run.clan)) {
                     this.fail(a, attacker.getName() + " a frappé le lanceur");
                     return;
                  }
               }
            }
         }
      }
   }

   @EventHandler
   public void onDeath(PlayerDeathEvent e) {
      for (RitualManager.Altar a : this.altars.values()) {
         if (a.run != null && a.run.caster.equals(e.getEntity().getUniqueId())) {
            this.fail(a, "le lanceur est mort");
            return;
         }
      }
   }

   @EventHandler(
      ignoreCancelled = true,
      priority = EventPriority.MONITOR
   )
   public void onBreak(BlockBreakEvent e) {
      Block b = e.getBlock();
      Block center = b.getType() == this.centerMat ? b : null;
      if (center == null && b.getType() == this.pedestalMat) {
         for (BlockFace d : DIRS) {
            Block c = b.getRelative(d, this.distance);
            if (this.altars.containsKey(c)) {
               center = c;
               break;
            }
         }
      }

      if (center != null) {
         RitualManager.Altar a = this.altars.get(center);
         if (a != null) {
            if (a.run != null) {
               this.fail(a, "l'autel a été cassé");
            } else {
               this.dropAll(a);
            }
         }
      }
   }

   private void dropAll(RitualManager.Altar a) {
      World w = a.center.getWorld();
      if (a.focus.item != null) {
         w.dropItemNaturally(this.focusLocation(a.center), a.focus.item);
      }

      this.clear(a.focus);

      for (int i = 0; i < 4; i++) {
         if (a.pedestals[i].item != null) {
            w.dropItemNaturally(this.pedestalLocation(a.center, i), a.pedestals[i].item);
         }

         this.clear(a.pedestals[i]);
      }

      this.altars.remove(a.center);
   }

   @EventHandler
   public void onQuit(PlayerQuitEvent e) {
      for (RitualManager.Altar a : new ArrayList<>(this.altars.values())) {
         if (a.run != null && a.run.caster.equals(e.getPlayer().getUniqueId())) {
            this.fail(a, "le lanceur s'est déconnecté");
         }
      }
   }

   public void shutdown() {
      YamlConfiguration y = YamlConfiguration.loadConfiguration(this.returnsFile);

      for (RitualManager.Altar a : this.altars.values()) {
         List<RitualManager.Slot> slots = new ArrayList<>(List.of(a.pedestals));
         slots.add(a.focus);

         for (RitualManager.Slot s : slots) {
            if (s.item != null) {
               String k = s.owner == null ? "unknown" : s.owner.toString();
               List<ItemStack> list = new ArrayList<>();

               for (Object o : y.getList(k, new ArrayList())) {
                  if (o instanceof ItemStack is) {
                     list.add(is);
                  }
               }

               list.add(s.item);
               y.set(k, list);
               if (s.display != null && s.display.isValid()) {
                  s.display.remove();
               }
            }
         }
      }

      this.altars.clear();

      try {
         y.save(this.returnsFile);
      } catch (IOException var12) {
      }
   }

   @EventHandler
   public void onJoin(PlayerJoinEvent e) {
      if (this.returnsFile.exists()) {
         YamlConfiguration y = YamlConfiguration.loadConfiguration(this.returnsFile);
         String k = e.getPlayer().getUniqueId().toString();
         List<?> list = y.getList(k);
         if (list != null && !list.isEmpty()) {
            Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
               for (Object o : list) {
                  if (o instanceof ItemStack is) {
                     this.give(e.getPlayer(), is);
                  }
               }

               this.plugin.msg(e.getPlayer(), "&dLes objets que tu avais laissés sur un autel t'ont été rendus.");
            }, 40L);
            y.set(k, null);

            try {
               y.save(this.returnsFile);
            } catch (IOException var6) {
            }
         }
      }
   }

   public static final class Altar {
      final Block center;
      final RitualManager.Slot focus = new RitualManager.Slot();
      final RitualManager.Slot[] pedestals = new RitualManager.Slot[]{
         new RitualManager.Slot(), new RitualManager.Slot(), new RitualManager.Slot(), new RitualManager.Slot()
      };
      RitualManager.Run run;

      Altar(Block center) {
         this.center = center;
      }

      public Block center() {
         return this.center;
      }

      public boolean running() {
         return this.run != null;
      }
   }

   public record Offering(String spec, int amount) {
      static RitualManager.Offering parse(String s) {
         String[] p = s.trim().split(":");
         if (p[0].equalsIgnoreCase("windy") && p.length >= 2) {
            return new RitualManager.Offering("windy:" + p[1], p.length > 2 ? Integer.parseInt(p[2]) : 1);
         } else {
            Material m = Material.matchMaterial(p[0]);
            int n = p.length > 1 ? Integer.parseInt(p[1]) : 1;
            return m == null ? null : new RitualManager.Offering(m.name(), n);
         }
      }

      public Material material() {
         return this.spec.startsWith("windy:") ? null : Material.matchMaterial(this.spec);
      }
   }

   public record Recipe(
      String id,
      String name,
      String focus,
      List<RitualManager.Offering> offerings,
      String result,
      boolean keepEnchants,
      int clanLevel,
      int duration,
      boolean nightOnly,
      int tier
   ) {
   }

   static final class Run {
      RitualManager.Recipe recipe;
      UUID caster;
      UUID clan;
      long start;
      int ticks;
      int total;
   }

   static final class Slot {
      ItemStack item;
      UUID owner;
      ItemDisplay display;
   }
}
