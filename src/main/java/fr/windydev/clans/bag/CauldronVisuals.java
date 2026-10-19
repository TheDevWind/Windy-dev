package fr.windydev.clans.bag;

import fr.windydev.clans.WindyClans;
import fr.windydev.clans.util.Text;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Map.Entry;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.Particle.DustOptions;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.Display.Brightness;
import org.bukkit.entity.ItemDisplay.ItemDisplayTransform;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.CauldronLevelChangeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

public class CauldronVisuals implements Listener {
   static final int SHELL = 7940;
   static final int PILLAR = 7910;
   static final int CORNER = 7920;
   private final WindyClans plugin;
   private final NamespacedKey keyFx;
   private final NamespacedKey keyTier;
   private final Map<Block, Integer> known = new HashMap<>();
   private final Set<Block> pending = new HashSet<>();

   CauldronVisuals(WindyClans plugin) {
      this.plugin = plugin;
      this.keyFx = new NamespacedKey(plugin, "cauldron_fx");
      this.keyTier = new NamespacedKey(plugin, "cauldron_tier");
   }

   void start() {
      Bukkit.getScheduler().runTaskTimer(this.plugin, this::pulse, 60L, 40L);
      Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
         for (World w : Bukkit.getWorlds()) {
            for (ItemDisplay d : w.getEntitiesByClass(ItemDisplay.class)) {
               this.adopt(d);
            }
         }
      }, 20L);
   }

   void refreshLater(Block c) {
      if (this.pending.add(c)) {
         Bukkit.getScheduler().runTask(this.plugin, () -> {
            this.pending.remove(c);
            this.refresh(c);
         });
      }
   }

   private static boolean relevant(Material m) {
      return m == Material.CAULDRON
         || m == Material.WATER_CAULDRON
         || m == Material.CAMPFIRE
         || m == Material.SOUL_CAMPFIRE
         || m == Material.AMETHYST_BLOCK
         || m == Material.CRYING_OBSIDIAN;
   }

   private void around(Block b) {
      for (int dx = -1; dx <= 1; dx++) {
         for (int dz = -1; dz <= 1; dz++) {
            for (int dy = 0; dy <= 1; dy++) {
               Block c = b.getRelative(dx, dy, dz);
               if (c.getType() == Material.WATER_CAULDRON || c.getType() == Material.CAULDRON || this.known.containsKey(c)) {
                  this.refreshLater(c);
               }
            }
         }
      }
   }

   @EventHandler(
      ignoreCancelled = true,
      priority = EventPriority.MONITOR
   )
   public void onPlace(BlockPlaceEvent e) {
      if (relevant(e.getBlockPlaced().getType())) {
         this.around(e.getBlockPlaced());
      }
   }

   @EventHandler(
      ignoreCancelled = true,
      priority = EventPriority.MONITOR
   )
   public void onBreak(BlockBreakEvent e) {
      if (relevant(e.getBlock().getType())) {
         this.around(e.getBlock());
      }
   }

   @EventHandler(
      ignoreCancelled = true,
      priority = EventPriority.MONITOR
   )
   public void onLevel(CauldronLevelChangeEvent e) {
      this.refreshLater(e.getBlock());
   }

   @EventHandler(
      priority = EventPriority.MONITOR
   )
   public void onInteract(PlayerInteractEvent e) {
      Block b = e.getClickedBlock();
      if (b != null && relevant(b.getType())) {
         Bukkit.getScheduler().runTaskLater(this.plugin, () -> this.around(b), 2L);
      }
   }

   @EventHandler(
      ignoreCancelled = true,
      priority = EventPriority.MONITOR
   )
   public void onExplode(EntityExplodeEvent e) {
      for (Block b : e.blockList()) {
         if (relevant(b.getType())) {
            this.around(b);
         }
      }
   }

   @EventHandler(
      ignoreCancelled = true,
      priority = EventPriority.MONITOR
   )
   public void onExplode(BlockExplodeEvent e) {
      for (Block b : e.blockList()) {
         if (relevant(b.getType())) {
            this.around(b);
         }
      }
   }

   @EventHandler
   public void onEntitiesLoad(EntitiesLoadEvent e) {
      for (Entity en : e.getEntities()) {
         if (en instanceof ItemDisplay d) {
            this.adopt(d);
         }
      }
   }

   private void adopt(ItemDisplay d) {
      String tag = (String)d.getPersistentDataContainer().get(this.keyFx, PersistentDataType.STRING);
      if (tag != null) {
         Block c = this.parse(tag);
         if (c == null) {
            d.remove();
         } else {
            this.known.putIfAbsent(c, -1);
            this.refreshLater(c);
         }
      }
   }

   private String tag(Block c) {
      return c.getWorld().getName() + ";" + c.getX() + ";" + c.getY() + ";" + c.getZ();
   }

   private Block parse(String tag) {
      String[] p = tag.split(";");
      World w = p.length == 4 ? Bukkit.getWorld(p[0]) : null;
      if (w == null) {
         return null;
      } else {
         try {
            return w.getBlockAt(Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
         } catch (NumberFormatException var5) {
            return null;
         }
      }
   }

   private List<ItemDisplay> shells(Block c) {
      String tag = this.tag(c);
      List<ItemDisplay> out = new ArrayList<>();

      for (Entity e : c.getWorld().getNearbyEntities(c.getLocation().add(0.5, 0.5, 0.5), 2.0, 1.5, 2.0, en -> en instanceof ItemDisplay)) {
         if (tag.equals(e.getPersistentDataContainer().get(this.keyFx, PersistentDataType.STRING))) {
            out.add((ItemDisplay)e);
         }
      }

      return out;
   }

   void refresh(Block c) {
      if (c.getWorld().isChunkLoaded(c.getX() >> 4, c.getZ() >> 4)) {
         int tier = CauldronRitual.structureTier(c);
         List<ItemDisplay> old = this.shells(c);
         int expected = tier == 0 ? 0 : 5 + (tier >= 2 ? 4 : 0);
         boolean same = old.size() == expected
            && old.stream().allMatch(d -> Objects.equals(d.getPersistentDataContainer().get(this.keyTier, PersistentDataType.INTEGER), tier));
         Integer before = this.known.get(c);
         if (tier == 0) {
            old.forEach(Entity::remove);
            this.known.remove(c);
         } else {
            this.known.put(c, tier);
            if (same) {
               old.forEach(this::light);
            } else {
               old.forEach(Entity::remove);
               this.spawn(c, c, 7940 + tier, tier, 1.012F);

               for (Block p : CauldronRitual.pillars(c)) {
                  this.spawn(c, p, 7910 + tier, tier, 1.012F);
               }

               if (tier >= 2) {
                  for (Block k : CauldronRitual.corners(c)) {
                     this.spawn(c, k, 7922, tier, 1.012F);
                  }
               }

               if (before == null || before >= 0 && before < tier) {
                  this.awaken(c, tier);
               }
            }
         }
      }
   }

   private void awaken(Block c, int tier) {
      World w = c.getWorld();
      Location l = c.getLocation().add(0.5, 1.0, 0.5);
      w.playSound(l, Sound.BLOCK_BREWING_STAND_BREW, 1.0F, 0.6F + tier * 0.2F);
      w.playSound(l, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1.0F, 1.2F);
      w.spawnParticle(Particle.WITCH, l, 40, 0.6, 0.3, 0.6, 0.05);
      w.spawnParticle(tier >= 2 ? Particle.SOUL : Particle.END_ROD, l, 16, 0.3, 0.5, 0.3, 0.03);

      for (Player p : l.getNearbyPlayers(12.0)) {
         p.sendActionBar(Text.c("&5⚗ &dChaudron runique de tier " + (tier >= 2 ? "II" : "I") + " éveillé &5⚗"));
      }
   }

   private void spawn(Block center, Block b, int model, int tier, float scale) {
      ItemStack it = new ItemStack(Material.PAPER);
      ItemMeta m = it.getItemMeta();
      m.setCustomModelData(model);
      it.setItemMeta(m);
      Location loc = b.getLocation().add(0.5, 0.5, 0.5);
      loc.getWorld().spawn(loc, ItemDisplay.class, d -> {
         d.setItemStack(it);
         d.setItemDisplayTransform(ItemDisplayTransform.NONE);
         d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(scale, scale, scale), new AxisAngle4f()));
         d.setPersistent(true);
         d.setViewRange(1.5F);
         d.getPersistentDataContainer().set(this.keyFx, PersistentDataType.STRING, this.tag(center));
         d.getPersistentDataContainer().set(this.keyTier, PersistentDataType.INTEGER, tier);
         this.light(d);
      });
   }

   private void light(ItemDisplay d) {
      Block b = d.getLocation().getBlock();
      int bl = b.getType().isOccluding() ? 0 : b.getLightFromBlocks();
      int sl = b.getType().isOccluding() ? 0 : b.getLightFromSky();

      for (BlockFace f : new BlockFace[]{BlockFace.UP, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
         Block n = b.getRelative(f);
         if (!n.getType().isOccluding()) {
            bl = Math.max(bl, n.getLightFromBlocks());
            sl = Math.max(sl, n.getLightFromSky());
         }
      }

      Brightness want = new Brightness(Math.max(bl, 5), sl);
      if (!want.equals(d.getBrightness())) {
         d.setBrightness(want);
      }
   }

   private void pulse() {
      for (Entry<Block, Integer> e : new ArrayList<>(this.known.entrySet())) {
         Block c = e.getKey();
         if (!c.getWorld().isChunkLoaded(c.getX() >> 4, c.getZ() >> 4)) {
            this.known.remove(c);
         } else {
            this.refresh(c);
            Integer tier = this.known.get(c);
            if (tier != null && tier > 0) {
               Location l = c.getLocation().add(0.5, 1.05, 0.5);
               if (!l.getNearbyPlayers(24.0).isEmpty()) {
                  World w = c.getWorld();
                  w.spawnParticle(Particle.BUBBLE_POP, l, 2, 0.2, 0.02, 0.2, 0.01);
                  DustOptions col = new DustOptions(tier >= 2 ? Color.fromRGB(255, 210, 90) : Color.fromRGB(190, 130, 250), 0.8F);

                  for (Block p : CauldronRitual.pillars(c)) {
                     w.spawnParticle(Particle.DUST, p.getLocation().add(0.5, 1.15, 0.5), 1, 0.2, 0.1, 0.2, 0.0, col);
                  }
               }
            }
         }
      }
   }
}
