package fr.windydev.clans.bag;

import fr.windydev.clans.WindyClans;
import fr.windydev.clans.model.Clan;
import fr.windydev.clans.util.Text;
import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Map.Entry;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import net.kyori.adventure.title.Title.Times;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.World.Environment;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Levelled;
import org.bukkit.block.data.type.Campfire;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

public class CauldronRitual implements Listener {
   private final WindyClans plugin;
   private final BagManager bags;
   private final Map<Block, CauldronRitual.Brew> brews = new HashMap<>();
   private final Map<Item, UUID> thrown = new HashMap<>();
   private final Map<String, CauldronRitual.Recipe> recipes = new LinkedHashMap<>();
   private int stayRadius = 6;
   CauldronVisuals visuals;
   private static final BlockFace[] SIDES = new BlockFace[]{BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST};

   public CauldronRitual(WindyClans plugin, BagManager bags) {
      this.plugin = plugin;
      this.bags = bags;
   }

   public void load() {
      File f = new File(this.plugin.getDataFolder(), "chaudron.yml");
      if (!f.exists()) {
         this.plugin.saveResource("chaudron.yml", false);
      }

      YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
      if (y.getInt("config-version", 1) < 6) {
         f.renameTo(new File(this.plugin.getDataFolder(), "chaudron-ancien.yml"));
         this.plugin.saveResource("chaudron.yml", true);
         y = YamlConfiguration.loadConfiguration(f);
      }

      y = fr.windydev.clans.util.YamlMerge.addNew(this.plugin, f, y, "chaudron.yml", "recipes");
      this.stayRadius = y.getInt("settings.stay-radius", 6);
      this.recipes.clear();
      ConfigurationSection rs = y.getConfigurationSection("recipes");
      if (rs != null) {
         for (String id : rs.getKeys(false)) {
            ConfigurationSection s = rs.getConfigurationSection(id);
            if (s != null) {
               List<CauldronRitual.Ingredient> ing = new ArrayList<>();

               for (String line : s.getStringList("ingredients")) {
                  int c = line.lastIndexOf(58);
                  String spec = line;
                  int n = 1;
                  if (c > 0) {
                     try {
                        n = Integer.parseInt(line.substring(c + 1));
                        spec = line.substring(0, c);
                     } catch (NumberFormatException var14) {
                     }
                  }

                  ing.add(new CauldronRitual.Ingredient(spec, Math.max(1, n)));
               }

               this.recipes
                  .put(
                     id,
                     new CauldronRitual.Recipe(
                        id,
                        s.getString("name", id),
                        s.getString("result", id),
                        Math.max(1, Math.min(2, s.getInt("tier", 1))),
                        s.getInt("player-level", 0),
                        s.getInt("clan-level", 0),
                        s.getBoolean("night-only", false),
                        Math.max(5, s.getInt("duration", 30)),
                        s.getString("base"),
                        ing,
                        Math.max(1, s.getInt("result-amount", 1)),
                        Math.max(0.0, Math.min(0.95, s.getDouble("fail-chance", 0.0)))
                     )
                  );
            }
         }

         this.plugin.getLogger().info(this.recipes.size() + " recette(s) de chaudron chargée(s).");
      }
   }

   public Collection<CauldronRitual.Recipe> recipes() {
      return this.recipes.values();
   }

   public void start() {
      Bukkit.getScheduler().runTaskTimer(this.plugin, this::tick, 5L, 5L);
      this.visuals = new CauldronVisuals(this.plugin);
      Bukkit.getPluginManager().registerEvents(this.visuals, this.plugin);
      this.visuals.start();
   }

   public void shutdown() {
      for (Entry<Block, CauldronRitual.Brew> e : this.brews.entrySet()) {
         this.spill(e.getKey(), e.getValue());
      }

      this.brews.clear();
   }

   public static int structureTier(Block c) {
      if (!isCauldron(c)) {
         return 0;
      } else {
         Material fire = fireUnder(c);
         if (fire == null) {
            return 0;
         } else {
            for (BlockFace f : SIDES) {
               if (c.getRelative(f).getType() != Material.AMETHYST_BLOCK) {
                  return 0;
               }
            }

            if (fire == Material.SOUL_CAMPFIRE) {
               boolean corners = true;

               for (Block k : corners(c)) {
                  if (k.getType() != Material.CRYING_OBSIDIAN) {
                     corners = false;
                  }
               }

               if (corners) {
                  return 2;
               }
            }

            return 1;
         }
      }
   }

   public static List<Block> pillars(Block c) {
      List<Block> l = new ArrayList<>();

      for (BlockFace f : SIDES) {
         l.add(c.getRelative(f));
      }

      return l;
   }

   public static List<Block> corners(Block c) {
      List<Block> l = new ArrayList<>();

      for (int dx = -1; dx <= 1; dx += 2) {
         for (int dz = -1; dz <= 1; dz += 2) {
            l.add(c.getRelative(dx, 0, dz));
         }
      }

      return l;
   }

   static boolean isCauldron(Block b) {
      return b.getType() == Material.WATER_CAULDRON && b.getBlockData() instanceof Levelled l && l.getLevel() >= l.getMaximumLevel();
   }

   static Material fireUnder(Block b) {
      Block under = b.getRelative(0, -1, 0);
      return (under.getType() == Material.CAMPFIRE || under.getType() == Material.SOUL_CAMPFIRE) && under.getBlockData() instanceof Campfire c && c.isLit()
         ? under.getType()
         : null;
   }

   private static boolean isNight(World w) {
      long t = w.getTime();
      return w.getEnvironment() == Environment.NORMAL && t >= 13000L && t <= 23000L;
   }

   @EventHandler(
      ignoreCancelled = true,
      priority = EventPriority.MONITOR
   )
   public void onDrop(PlayerDropItemEvent e) {
      this.thrown.put(e.getItemDrop(), e.getPlayer().getUniqueId());
   }

   private void tick() {
      Iterator<Entry<Item, UUID>> it = this.thrown.entrySet().iterator();

      while (it.hasNext()) {
         Entry<Item, UUID> en = it.next();
         Item item = en.getKey();
         if (item.isValid() && item.getTicksLived() <= 120) {
            Block b = item.getLocation().getBlock();
            if (structureTier(b) >= 1) {
               CauldronRitual.Brew brew = this.brews.computeIfAbsent(b, k -> {
                  CauldronRitual.Brew nx = new CauldronRitual.Brew();
                  nx.owner = en.getValue();
                  return nx;
               });
               if (brew.cooking == null) {
                  if (!brew.owner.equals(en.getValue())) {
                     Player p = Bukkit.getPlayer(en.getValue());
                     if (p != null) {
                        p.sendActionBar(Text.c("&cCe chaudron est utilisé par quelqu'un d'autre."));
                     }
                  } else {
                     brew.contents.add(item.getItemStack().clone());
                     item.remove();
                     it.remove();
                     Location c = b.getLocation().add(0.5, 0.9, 0.5);
                     b.getWorld().spawnParticle(Particle.SPLASH, c, 20, 0.2, 0.05, 0.2, 0.1);
                     b.getWorld().playSound(c, Sound.ENTITY_GENERIC_SPLASH, 0.6F, 1.4F);
                     this.check(b, brew);
                  }
               }
            }
         } else {
            it.remove();
         }
      }

      for (Entry<Block, CauldronRitual.Brew> e : new ArrayList<>(this.brews.entrySet())) {
         Block b = e.getKey();
         CauldronRitual.Brew brew = e.getValue();
         if (brew.cooking != null) {
            Player owner = Bukkit.getPlayer(brew.owner);
            Location c = b.getLocation().add(0.5, 0.9, 0.5);
            String fail = null;
            if (!isCauldron(b)) {
               fail = "le chaudron a été vidé";
            } else if (structureTier(b) < brew.cooking.tier()) {
               fail = "le chaudron runique a été défait (feu, améthystes ou obsidiennes)";
            } else if (owner == null || !owner.isOnline() || owner.getWorld() != b.getWorld() || owner.getLocation().distance(c) > this.stayRadius) {
               fail = "tu t'es éloigné du chaudron";
            }

            if (fail != null) {
               if (owner != null) {
                  this.plugin.msg(owner, "&cLe rituel du chaudron a échoué : " + fail + ". Les ingrédients ressortent.");
               }

               b.getWorld().playSound(c, Sound.BLOCK_FIRE_EXTINGUISH, 1.0F, 0.8F);
               this.spill(b, brew);
               this.brews.remove(b);
            } else {
               brew.ticks += 5;
               double prog = Math.min(1.0, (double)brew.ticks / brew.total);
               boolean soul = brew.cooking.tier() >= 2;
               b.getWorld().spawnParticle(Particle.BUBBLE_POP, c, 6, 0.2, 0.02, 0.2, 0.02);
               b.getWorld().spawnParticle(soul ? Particle.SOUL_FIRE_FLAME : Particle.WITCH, c.clone().add(0.0, 0.2 + prog, 0.0), 3, 0.25, 0.3, 0.25, 0.01);
               if (brew.ticks % 20 == 0) {
                  b.getWorld().playSound(c, Sound.BLOCK_BUBBLE_COLUMN_BUBBLE_POP, 0.8F, 0.8F + (float)prog);
                  int left = (int)Math.ceil((brew.total - brew.ticks) / 20.0);
                  int n = (int)Math.round(prog * 12.0);
                  owner.sendActionBar(
                     Text.c("&5⚗ &d" + Text.strip(brew.cooking.name()) + " &8| &f" + left + " s &8| &d" + "|".repeat(n) + "&8" + "|".repeat(12 - n))
                  );
               }

               if (brew.ticks >= brew.total) {
                  this.finish(b, brew, owner);
               }
            }
         }
      }
   }

   private void check(Block b, CauldronRitual.Brew brew) {
      Player p = Bukkit.getPlayer(brew.owner);
      int structure = structureTier(b);
      CauldronRitual.Recipe best = null;
      List<Component> bestMissing = null;

      for (CauldronRitual.Recipe r : this.recipes.values()) {
         if (r.tier() <= Math.max(1, structure) || r.base() != null) {
            List<Component> miss = this.missing(r, brew.contents);
            if (bestMissing == null || miss.size() < bestMissing.size()) {
               best = r;
               bestMissing = miss;
            }
         }
      }

      if (best != null && p != null) {
         if (!bestMissing.isEmpty()) {
            Component line = Text.c("&5⚗ &7" + Text.strip(best.name()) + " &8| &7il manque : ");

            for (int i = 0; i < bestMissing.size(); i++) {
               line = line.append(i > 0 ? Text.c("&7, ") : Text.c("")).append(bestMissing.get(i));
            }

            p.sendActionBar(line);
         } else if (structure < best.tier()) {
            this.plugin
               .msg(
                  p,
                  "&cIl faut un chaudron runique de &dtier "
                     + (best.tier() == 2 ? "II" : "I")
                     + " &c: feu de camp des âmes allumé dessous et 4 obsidiennes pleureuses aux coins. &7Puis clic droit sur le chaudron."
               );
         } else {
            int lvl = this.plugin.profiles().of(p).level();
            if (lvl < best.playerLevel()) {
               this.plugin
                  .msg(
                     p,
                     "&cIl faut être &dniveau "
                        + best.playerLevel()
                        + " &cpour ce rituel &7(tu es niveau "
                        + lvl
                        + ")&c. Les ingrédients restent dans le chaudron."
                  );
            } else {
               if (best.clanLevel() > 0) {
                  Clan clan = this.plugin.clans().of(p);
                  if (clan == null || clan.level() < best.clanLevel()) {
                     this.plugin.msg(p, "&cIl faut un &eclan de niveau " + best.clanLevel() + " &cpour ce rituel. Les ingrédients restent dans le chaudron.");
                     return;
                  }
               }

               if (best.nightOnly() && !isNight(b.getWorld())) {
                  this.plugin.msg(p, "&cCe rituel ne fonctionne que la nuit. &7Les ingrédients restent dans le chaudron : clic droit dessus à la nuit tombée.");
               } else {
                  double speed = Math.max(0.0, Math.min(0.9, this.plugin.profiles().stat(p, "ritual-speed")));
                  brew.cooking = best;
                  brew.ticks = 0;
                  brew.total = (int)Math.max(40L, Math.round(best.duration() * 20 * (1.0 - speed)));
                  p.showTitle(
                     Title.title(
                        Text.c("&5⚗ Rituel du chaudron"),
                        Text.c("&dReste près du chaudron " + brew.total / 20 + " secondes"),
                        Times.times(Duration.ofMillis(300L), Duration.ofSeconds(2L), Duration.ofMillis(500L))
                     )
                  );
                  b.getWorld().playSound(b.getLocation(), Sound.BLOCK_BREWING_STAND_BREW, 1.0F, 0.7F);
               }
            }
         }
      }
   }

   private boolean matchesSpec(ItemStack it, String spec) {
      return this.plugin.items().matches(it, spec);
   }

   private Component name(String spec) {
      if (spec.startsWith("windy:")) {
         return Text.item(this.plugin.items().displayName(spec));
      } else {
         Material m = Material.matchMaterial(spec);
         return m == null ? Text.item("&f" + spec) : Component.translatable(m.translationKey()).color(NamedTextColor.WHITE);
      }
   }

   private List<Component> missing(CauldronRitual.Recipe r, List<ItemStack> contents) {
      List<Component> out = new ArrayList<>();
      if (r.base() != null && contents.stream().noneMatch(i -> this.matchesSpec(i, r.base()))) {
         out.add(Text.item("&f1 ").append(this.name(r.base())));
      }

      for (CauldronRitual.Ingredient ing : r.ingredients()) {
         int have = contents.stream().filter(i -> this.matchesSpec(i, ing.spec())).mapToInt(ItemStack::getAmount).sum();
         if (have < ing.amount()) {
            out.add(Text.item("&f" + (ing.amount() - have) + " ").append(this.name(ing.spec())));
         }
      }

      return out;
   }

   private void finish(Block b, CauldronRitual.Brew brew, Player p) {
      CauldronRitual.Recipe r = brew.cooking;
      this.brews.remove(b);
      List<ItemStack> rest = new ArrayList<>();

      for (ItemStack i : brew.contents) {
         rest.add(i.clone());
      }

      ItemStack baseItem = null;
      if (r.base() != null) {
         Iterator<ItemStack> it = rest.iterator();

         while (it.hasNext()) {
            ItemStack i = it.next();
            if (this.matchesSpec(i, r.base())) {
               baseItem = i.clone();
               baseItem.setAmount(1);
               if (i.getAmount() > 1) {
                  i.setAmount(i.getAmount() - 1);
               } else {
                  it.remove();
               }
               break;
            }
         }
      }

      for (CauldronRitual.Ingredient ing : r.ingredients()) {
         int need = ing.amount();
         Iterator<ItemStack> it = rest.iterator();

         while (it.hasNext() && need > 0) {
            ItemStack i = it.next();
            if (this.matchesSpec(i, ing.spec())) {
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

      Location c = b.getLocation().add(0.5, 1.1, 0.5);
      World w = b.getWorld();
      boolean failed = r.failChance() > 0.0 && Math.random() < r.failChance();
      if (failed) {
         w.spawnParticle(Particle.SMOKE, c, 40, 0.3, 0.5, 0.3, 0.05);
         w.spawnParticle(Particle.LARGE_SMOKE, c, 10, 0.2, 0.3, 0.2, 0.02);
         w.playSound(c, Sound.ENTITY_GENERIC_EXTINGUISH_FIRE, 1.0F, 0.7F);

         for (ItemStack i : rest) {
            w.dropItem(c, i);
         }

         b.setType(Material.CAULDRON);
         if (this.visuals != null) {
            this.visuals.refreshLater(b);
         }

         if (p != null) {
            this.plugin.msg(p, "&cLe rituel a échoué : les ingrédients sont perdus.");
         }
      } else {
         ItemStack result = baseItem != null ? this.plugin.items().transform(baseItem, r.result(), true) : this.plugin.items().create(r.result());
         if (result == null) {
            this.plugin.getLogger().warning("chaudron.yml : objet inconnu '" + r.result() + "'");
         } else {
            if (baseItem == null) {
               result.setAmount(Math.max(1, r.resultAmount()));
            }

            if (baseItem != null) {
               this.bags.carry(baseItem, result);
            }

            if (this.bags.isBag(result)) {
               this.bags.stamp(result);
            }

            w.spawnParticle(Particle.TOTEM_OF_UNDYING, c, 50, 0.3, 0.5, 0.3, 0.3);
            w.spawnParticle(Particle.END_ROD, c, 20, 0.2, 0.6, 0.2, 0.05);
            w.playSound(c, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0F, 1.2F);
            Item drop = w.dropItem(c, result);
            drop.setGlowing(true);
            drop.setVelocity(new Vector(0.0, 0.35, 0.0));

            for (ItemStack i : rest) {
               w.dropItem(c, i);
            }

            b.setType(Material.CAULDRON);
            if (this.visuals != null) {
               this.visuals.refreshLater(b);
            }

            if (p != null) {
               this.plugin
                  .msg(
                     p,
                     "&d⚗ &fRituel réussi : "
                        + this.plugin.items().displayName("windy:" + r.result())
                        + (result.getAmount() > 1 ? " &7x" + result.getAmount() : "")
                        + "&f !"
                  );
               this.plugin.profiles().ritualDone(p, r.tier());
            }
         }
      }
   }

   private void spill(Block b, CauldronRitual.Brew brew) {
      Location c = b.getLocation().add(0.5, 1.1, 0.5);

      for (ItemStack i : brew.contents) {
         b.getWorld().dropItem(c, i);
      }

      brew.contents.clear();
   }

   @EventHandler(
      ignoreCancelled = true
   )
   public void onInteract(PlayerInteractEvent e) {
      if (e.getHand() == EquipmentSlot.HAND && e.getAction() == Action.RIGHT_CLICK_BLOCK && e.getClickedBlock() != null) {
         Block b = e.getClickedBlock();
         CauldronRitual.Brew brew = this.brews.get(b);
         if (brew != null) {
            Player p = e.getPlayer();
            if (p.getInventory().getItemInMainHand().getType().isAir()) {
               e.setCancelled(true);
               if (!p.isSneaking()) {
                  if (brew.cooking == null && brew.owner.equals(p.getUniqueId())) {
                     this.check(b, brew);
                  }
               } else if (brew.cooking != null) {
                  this.plugin.msg(p, "&7La potion bout déjà, attends la fin.");
               } else if (!brew.owner.equals(p.getUniqueId())) {
                  this.plugin.msg(p, "&cCe ne sont pas tes ingrédients.");
               } else {
                  this.spill(b, brew);
                  this.brews.remove(b);
                  this.plugin.msg(p, "&7Tu récupères tes ingrédients.");
               }
            }
         }
      }
   }

   @EventHandler(
      ignoreCancelled = true,
      priority = EventPriority.MONITOR
   )
   public void onBreak(BlockBreakEvent e) {
      Block b = e.getBlock();
      CauldronRitual.Brew brew = this.brews.remove(b);
      if (brew != null) {
         this.spill(b, brew);
      }
   }

   private static final class Brew {
      UUID owner;
      final List<ItemStack> contents = new ArrayList<>();
      CauldronRitual.Recipe cooking;
      int ticks;
      int total;
   }

   public record Ingredient(String spec, int amount) {
   }

   public record Recipe(
      String id,
      String name,
      String result,
      int tier,
      int playerLevel,
      int clanLevel,
      boolean nightOnly,
      int duration,
      String base,
      List<CauldronRitual.Ingredient> ingredients,
      int resultAmount,
      double failChance
   ) {
   }
}
