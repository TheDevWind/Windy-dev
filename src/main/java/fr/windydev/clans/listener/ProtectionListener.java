package fr.windydev.clans.listener;

import fr.windydev.clans.WindyClans;
import fr.windydev.clans.data.ClanManager;
import fr.windydev.clans.data.Settings;
import fr.windydev.clans.feature.WarManager;
import fr.windydev.clans.model.ChunkPos;
import fr.windydev.clans.model.Clan;
import fr.windydev.clans.model.Rank;
import fr.windydev.clans.util.Text;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Allay;
import org.bukkit.entity.Animals;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Golem;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.LeashHitch;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Vehicle;
import org.bukkit.entity.Villager;
import org.bukkit.entity.WanderingTrader;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.Event.Result;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockIgniteEvent.IgniteCause;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.InventoryHolder;

public class ProtectionListener implements Listener {
   private final WindyClans plugin;
   private final Map<UUID, Long> lastWarn = new HashMap<>();

   public ProtectionListener(WindyClans plugin) {
      this.plugin = plugin;
   }

   public boolean can(Player p, Location loc, ProtectionListener.Action action) {
      if (this.plugin.bypass().contains(p.getUniqueId())) {
         return true;
      } else {
         ClanManager cm = this.plugin.clans();
         ChunkPos pos = ChunkPos.of(loc);
         Clan owner = cm.at(pos);
         if (owner == null) {
            // zone du spawn : rien ne se casse, ne se pose ni ne se vide ; les portes restent ouvertes à tous.
            // Les admins aussi sont bloqués, sauf en mode /clanadmin bypass (plus de casse par accident).
            return !cm.isSpawnZone(pos) || action == ProtectionListener.Action.DOOR;
         } else {
            Clan mine = cm.of(p);
            Settings s = this.plugin.settings();
            if (mine == owner) {
               Rank r = owner.rank(p.getUniqueId());

               return switch (action) {
                  case BUILD -> r.atLeast(s.rank("build"));
                  case CONTAINER -> r.atLeast(s.rank("containers"));
                  case DOOR -> r.atLeast(s.rank("doors"));
               };
            } else {
               return mine != null && this.plugin.war().atWar(mine, owner) && !cm.isCapitalZone(owner, pos)
                  ? action != ProtectionListener.Action.CONTAINER || s.warRaidContainers
                  : mine != null && action == ProtectionListener.Action.DOOR && s.alliesDoors && mine.allies().contains(owner.id());
            }
         }
      }
   }

   private boolean check(Player p, Location loc, ProtectionListener.Action action) {
      if (this.can(p, loc, action)) {
         return true;
      } else {
         long now = System.currentTimeMillis();
         if (now - this.lastWarn.getOrDefault(p.getUniqueId(), 0L) > 1500L) {
            this.lastWarn.put(p.getUniqueId(), now);
            Clan owner = this.plugin.clans().at(loc);
            Clan mine = this.plugin.clans().of(p);
            if (owner == null) {
               p.sendActionBar(Text.c(p.hasPermission("windyclans.admin") ? "&eZone du spawn protégée &7- &f/clanadmin bypass &7pour construire" : "&eZone du spawn protégée"));
            } else if (owner == mine) {
               p.sendActionBar(Text.c("&cTon grade ne permet pas de faire ça ici."));
            } else {
               p.sendActionBar(Text.c("&cTerritoire de " + owner.coloredName() + " &c- action impossible"));
            }
         }

         return false;
      }
   }

   @EventHandler(
      ignoreCancelled = true,
      priority = EventPriority.LOW
   )
   public void onBreak(BlockBreakEvent e) {
      if (!this.plugin.awakening().isActiveCore(e.getBlock())) {
         if (!this.check(e.getPlayer(), e.getBlock().getLocation(), ProtectionListener.Action.BUILD)) {
            e.setCancelled(true);
         }
      }
   }

   @EventHandler(
      ignoreCancelled = true,
      priority = EventPriority.LOW
   )
   public void onPlace(BlockPlaceEvent e) {
      if (!this.check(e.getPlayer(), e.getBlock().getLocation(), ProtectionListener.Action.BUILD)) {
         e.setCancelled(true);
      }
   }

   @EventHandler(
      ignoreCancelled = true,
      priority = EventPriority.LOW
   )
   public void onMultiPlace(BlockMultiPlaceEvent e) {
      for (BlockState st : e.getReplacedBlockStates()) {
         if (!this.can(e.getPlayer(), st.getLocation(), ProtectionListener.Action.BUILD)) {
            e.setCancelled(true);
            return;
         }
      }
   }

   @EventHandler(
      ignoreCancelled = true,
      priority = EventPriority.LOW
   )
   public void onBucketEmpty(PlayerBucketEmptyEvent e) {
      if (!this.check(e.getPlayer(), e.getBlockClicked().getRelative(e.getBlockFace()).getLocation(), ProtectionListener.Action.BUILD)) {
         e.setCancelled(true);
      }
   }

   @EventHandler(
      ignoreCancelled = true,
      priority = EventPriority.LOW
   )
   public void onBucketFill(PlayerBucketFillEvent e) {
      if (!this.check(e.getPlayer(), e.getBlockClicked().getLocation(), ProtectionListener.Action.BUILD)) {
         e.setCancelled(true);
      }
   }

   @EventHandler(
      priority = EventPriority.LOW
   )
   public void onInteract(PlayerInteractEvent e) {
      Block b = e.getClickedBlock();
      if (b != null) {
         Player p = e.getPlayer();
         Material m = b.getType();
         if (e.getAction() == org.bukkit.event.block.Action.PHYSICAL) {
            if (m != Material.FARMLAND && m != Material.TURTLE_EGG) {
               if (!this.can(p, b.getLocation(), ProtectionListener.Action.DOOR)) {
                  e.setCancelled(true);
               }
            } else if (!this.can(p, b.getLocation(), ProtectionListener.Action.BUILD)) {
               e.setCancelled(true);
            }
         } else if (e.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) {
            ProtectionListener.Action needed = null;
            boolean spawn = this.plugin.clans().at(b.getLocation()) == null && this.plugin.clans().isSpawnZone(ChunkPos.of(b.getLocation()));
            if (spawn && (m == Material.CAULDRON || m == Material.WATER_CAULDRON || m == Material.LAVA_CAULDRON || m == Material.RESPAWN_ANCHOR)) {
               needed = null; // comme avant : les chaudrons du spawn restent utilisables
            } else if (b.getState() instanceof InventoryHolder || this.isOtherContainer(m)) {
               needed = ProtectionListener.Action.CONTAINER;
            } else if (spawn && Tag.TRAPDOORS.isTagged(m)) {
               needed = ProtectionListener.Action.BUILD; // au spawn les trappes sont du décor (volets, tables)
            } else if (this.isDoorLike(m)) {
               needed = ProtectionListener.Action.DOOR;
            } else if (this.isBuildInteract(m)) {
               needed = ProtectionListener.Action.BUILD;
            } else if (e.getItem() != null && this.isPlacingEntityItem(e.getItem().getType())) {
               needed = ProtectionListener.Action.BUILD;
            }

            if (needed != null && !this.check(p, b.getLocation(), needed)) {
               e.setUseInteractedBlock(Result.DENY);
               if (needed != ProtectionListener.Action.DOOR) {
                  e.setUseItemInHand(Result.DENY);
               }
            }
         }
      }
   }

   private boolean isOtherContainer(Material m) {
      return m == Material.JUKEBOX
         || m == Material.CHISELED_BOOKSHELF
         || m == Material.DECORATED_POT
         || m == Material.LECTERN
         || m == Material.CAULDRON
         || m == Material.WATER_CAULDRON
         || m == Material.LAVA_CAULDRON
         || m == Material.RESPAWN_ANCHOR;
   }

   private boolean isDoorLike(Material m) {
      return Tag.DOORS.isTagged(m)
         || Tag.TRAPDOORS.isTagged(m)
         || Tag.FENCE_GATES.isTagged(m)
         || Tag.BUTTONS.isTagged(m)
         || m == Material.LEVER
         || Tag.PRESSURE_PLATES.isTagged(m)
         || Tag.BEDS.isTagged(m);
   }

   private boolean isBuildInteract(Material m) {
      return m == Material.REPEATER
         || m == Material.COMPARATOR
         || m == Material.DAYLIGHT_DETECTOR
         || m == Material.NOTE_BLOCK
         || Tag.FLOWER_POTS.isTagged(m)
         || m == Material.CAKE
         || Tag.CANDLES.isTagged(m)
         || Tag.CANDLE_CAKES.isTagged(m)
         || m == Material.COMPOSTER
         || m == Material.BEEHIVE
         || m == Material.BEE_NEST
         || Tag.ALL_SIGNS.isTagged(m)
         || m == Material.SWEET_BERRY_BUSH
         || m == Material.CAVE_VINES
         || m == Material.CAVE_VINES_PLANT
         || m == Material.DRAGON_EGG
         || m == Material.VAULT;
   }

   private boolean isPlacingEntityItem(Material m) {
      String n = m.name();
      return n.endsWith("_BOAT")
         || n.endsWith("_RAFT")
         || n.endsWith("MINECART")
         || m == Material.ARMOR_STAND
         || m == Material.END_CRYSTAL
         || m == Material.BONE_MEAL
         || n.endsWith("_SPAWN_EGG")
         || m == Material.FLINT_AND_STEEL
         || m == Material.FIRE_CHARGE;
   }

   @EventHandler(
      ignoreCancelled = true,
      priority = EventPriority.LOW
   )
   public void onEntityInteract(PlayerInteractEntityEvent e) {
      Entity en = e.getRightClicked();
      ProtectionListener.Action needed = null;
      if (en instanceof ItemFrame || en instanceof ArmorStand || en instanceof InventoryHolder && !(en instanceof Player) || en instanceof Allay) {
         needed = ProtectionListener.Action.CONTAINER;
      } else if (en instanceof Animals || en instanceof Hanging || en instanceof LeashHitch) {
         needed = ProtectionListener.Action.BUILD;
      }

      if (en instanceof Villager || en instanceof WanderingTrader) {
         needed = null;
      }

      if (needed != null && !this.check(e.getPlayer(), en.getLocation(), needed)) {
         e.setCancelled(true);
      }
   }

   @EventHandler(
      ignoreCancelled = true,
      priority = EventPriority.LOW
   )
   public void onArmorStand(PlayerArmorStandManipulateEvent e) {
      if (!this.check(e.getPlayer(), e.getRightClicked().getLocation(), ProtectionListener.Action.CONTAINER)) {
         e.setCancelled(true);
      }
   }

   @EventHandler(
      ignoreCancelled = true,
      priority = EventPriority.LOW
   )
   public void onHangingBreak(HangingBreakByEntityEvent e) {
      Player p = attacker(e.getRemover());
      if (p != null && !this.check(p, e.getEntity().getLocation(), ProtectionListener.Action.BUILD)) {
         e.setCancelled(true);
      }
   }

   @EventHandler(
      ignoreCancelled = true,
      priority = EventPriority.LOW
   )
   public void onEntityDamage(EntityDamageByEntityEvent e) {
      Entity victim = e.getEntity();
      if (!(victim instanceof Player) && !(victim instanceof Monster)) {
         if (victim instanceof Animals
            || victim instanceof Hanging
            || victim instanceof ArmorStand
            || victim instanceof Villager
            || victim instanceof Golem
            || victim instanceof Vehicle) {
            Player p = attacker(e.getDamager());
            if (p != null && !this.check(p, victim.getLocation(), ProtectionListener.Action.BUILD)) {
               e.setCancelled(true);
            }
         }
      }
   }

   public static Player attacker(Entity damager) {
      if (damager instanceof Player p) {
         return p;
      } else if (damager instanceof Projectile pr && pr.getShooter() instanceof Player p) {
         return p;
      } else {
         return damager instanceof TNTPrimed t && t.getSource() instanceof Player p ? p : null;
      }
   }

   @EventHandler(
      ignoreCancelled = true,
      priority = EventPriority.LOW
   )
   public void onTrample(EntityChangeBlockEvent e) {
      if (e.getEntity() instanceof Player p && !this.can(p, e.getBlock().getLocation(), ProtectionListener.Action.BUILD)) {
         e.setCancelled(true);
      }
   }

   private boolean explosionAllowed(Location loc) {
      if (this.plugin.settings().allowExplosions) {
         return true;
      } else {
         ClanManager cm = this.plugin.clans();
         ChunkPos pos = ChunkPos.of(loc);
         Clan owner = cm.at(pos);
         if (owner == null) {
            return !cm.isSpawnZone(pos);
         } else {
            WarManager.War war = this.plugin.war().warOf(owner);
            return war != null && war.active && !cm.isCapitalZone(owner, pos);
         }
      }
   }

   @EventHandler(
      ignoreCancelled = true
   )
   public void onEntityExplode(EntityExplodeEvent e) {
      e.blockList().removeIf(b -> !this.explosionAllowed(b.getLocation()));
   }

   @EventHandler(
      ignoreCancelled = true
   )
   public void onBlockExplode(BlockExplodeEvent e) {
      e.blockList().removeIf(b -> !this.explosionAllowed(b.getLocation()));
   }

   @EventHandler(
      ignoreCancelled = true
   )
   public void onBurn(BlockBurnEvent e) {
      if (this.plugin.clans().at(e.getBlock().getLocation()) != null) {
         e.setCancelled(true);
      }
   }

   @EventHandler(
      ignoreCancelled = true
   )
   public void onIgnite(BlockIgniteEvent e) {
      if ((e.getCause() == IgniteCause.SPREAD || e.getCause() == IgniteCause.LAVA) && this.plugin.clans().at(e.getBlock().getLocation()) != null) {
         e.setCancelled(true);
      }
   }

   @EventHandler(
      ignoreCancelled = true
   )
   public void onFlow(BlockFromToEvent e) {
      Clan to = this.plugin.clans().at(e.getToBlock().getLocation());
      if (to != null) {
         if (this.plugin.clans().at(e.getBlock().getLocation()) != to) {
            e.setCancelled(true);
         }
      }
   }

   @EventHandler(
      ignoreCancelled = true
   )
   public void onPistonExtend(BlockPistonExtendEvent e) {
      if (this.pistonCrosses(e.getBlock(), e.getBlocks(), e.getDirection())) {
         e.setCancelled(true);
      }
   }

   @EventHandler(
      ignoreCancelled = true
   )
   public void onPistonRetract(BlockPistonRetractEvent e) {
      if (this.pistonCrosses(e.getBlock(), e.getBlocks(), e.getDirection())) {
         e.setCancelled(true);
      }
   }

   private boolean pistonCrosses(Block piston, List<Block> blocks, BlockFace dir) {
      Clan src = this.plugin.clans().at(piston.getLocation());
      Set<Block> check = new HashSet<>(blocks);

      for (Block b : blocks) {
         check.add(b.getRelative(dir));
      }

      check.add(piston.getRelative(dir));

      for (Block b : check) {
         Clan c = this.plugin.clans().at(b.getLocation());
         if (c != null && c != src) {
            return true;
         }
      }

      return false;
   }

   @EventHandler(
      ignoreCancelled = true
   )
   public void onDispense(BlockDispenseEvent e) {
      if (e.getBlock().getBlockData() instanceof Directional d) {
         Block var5 = e.getBlock().getRelative(d.getFacing());
         Clan to = this.plugin.clans().at(var5.getLocation());
         if (to != null && this.plugin.clans().at(e.getBlock().getLocation()) != to) {
            e.setCancelled(true);
         }
      }
   }

   public static enum Action {
      BUILD,
      CONTAINER,
      DOOR;
   }
}
