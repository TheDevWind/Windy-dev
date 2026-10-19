package fr.windydev.clans.ritual;

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
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

public class AltarVisuals implements Listener {
   static final int TABLE = 7900;
   static final int PILLAR = 7910;
   static final int CORNER = 7920;
   static final int BASE = 7931;
   private final WindyClans plugin;
   private final RitualManager rm;
   private final NamespacedKey keyAltar;
   private final NamespacedKey keyTier;
   private final Map<Block, Integer> known = new HashMap<>();
   private final Set<Block> pending = new HashSet<>();

   AltarVisuals(WindyClans plugin, RitualManager rm) {
      this.plugin = plugin;
      this.rm = rm;
      this.keyAltar = new NamespacedKey(plugin, "altar_fx");
      this.keyTier = new NamespacedKey(plugin, "altar_tier");
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

   public void refreshLater(Block center) {
      if (this.pending.add(center)) {
         Bukkit.getScheduler().runTask(this.plugin, () -> {
            this.pending.remove(center);
            this.refresh(center);
         });
      }
   }

   private boolean relevant(Material m) {
      return m == this.rm.centerMaterial() || m == this.rm.pedestalMaterial() || m == this.rm.cornerMaterial() || m == this.rm.baseMaterial();
   }

   private void around(Block b) {
      int d = this.rm.distance();

      for (int dx = -d; dx <= d; dx++) {
         for (int dz = -d; dz <= d; dz++) {
            for (int dy = -1; dy <= 1; dy++) {
               Block c = b.getRelative(dx, dy, dz);
               if (c.getType() == this.rm.centerMaterial() || this.known.containsKey(c)) {
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
      if (this.relevant(e.getBlockPlaced().getType())) {
         this.around(e.getBlockPlaced());
      }
   }

   @EventHandler(
      ignoreCancelled = true,
      priority = EventPriority.MONITOR
   )
   public void onBreak(BlockBreakEvent e) {
      if (this.relevant(e.getBlock().getType())) {
         this.around(e.getBlock());
      }
   }

   @EventHandler(
      ignoreCancelled = true,
      priority = EventPriority.MONITOR
   )
   public void onExplode(EntityExplodeEvent e) {
      for (Block b : e.blockList()) {
         if (this.relevant(b.getType())) {
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
         if (this.relevant(b.getType())) {
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
      String tag = (String)d.getPersistentDataContainer().get(this.keyAltar, PersistentDataType.STRING);
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
      Location mid = c.getLocation().add(0.5, 0.5, 0.5);
      double r = this.rm.distance() + 1.5;

      for (Entity e : c.getWorld().getNearbyEntities(mid, r, 2.5, r, en -> en instanceof ItemDisplay)) {
         if (tag.equals(e.getPersistentDataContainer().get(this.keyAltar, PersistentDataType.STRING))) {
            out.add((ItemDisplay)e);
         }
      }

      return out;
   }

   void refresh(Block c) {
      if (c.getWorld().isChunkLoaded(c.getX() >> 4, c.getZ() >> 4)) {
         int tier = this.rm.tier(c);
         List<ItemDisplay> old = this.shells(c);
         int expected = tier == 0 ? 0 : 5 + (tier >= 2 ? 4 : 0) + (tier >= 3 ? 1 : 0);
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
               this.spawn(c, c, 7900 + tier, tier, 1.012F);

               for (Block p : this.rm.pedestals(c)) {
                  this.spawn(c, p, 7910 + tier, tier, 1.012F);
               }

               if (tier >= 2) {
                  for (Block k : this.rm.corners(c)) {
                     this.spawn(c, k, 7920 + tier, tier, 1.012F);
                  }
               }

               if (tier >= 3) {
                  this.spawn(c, c.getRelative(BlockFace.DOWN), 7931, tier, 1.012F);
               }

               if (before != null && before >= 0 && before < tier || before == null) {
                  this.awaken(c, tier);
               }
            }
         }
      }
   }

   private void awaken(Block c, int tier) {
      World w = c.getWorld();
      Location l = c.getLocation().add(0.5, 1.0, 0.5);
      w.playSound(l, Sound.BLOCK_BEACON_ACTIVATE, 1.0F, 0.8F + tier * 0.2F);
      w.playSound(l, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1.0F, 1.0F);
      w.spawnParticle(Particle.ENCHANT, l, 80, 1.5, 0.5, 1.5, 1.0);
      w.spawnParticle(Particle.END_ROD, l, 20, 0.3, 0.6, 0.3, 0.05);

      for (Player p : l.getNearbyPlayers(12.0)) {
         p.sendActionBar(Text.c("&5✦ &dAutel de tier " + RitualManager.roman(tier) + " éveillé &5✦"));
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
         d.getPersistentDataContainer().set(this.keyAltar, PersistentDataType.STRING, this.tag(center));
         d.getPersistentDataContainer().set(this.keyTier, PersistentDataType.INTEGER, tier);
         this.light(d);
      });
   }

   private void light(ItemDisplay d) {
      Block b = d.getLocation().getBlock();
      int bl = 0;
      int sl = 0;

      for (BlockFace f : new BlockFace[]{BlockFace.UP, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.DOWN}) {
         Block n = b.getRelative(f);
         if (!n.getType().isOccluding()) {
            bl = Math.max(bl, n.getLightFromBlocks());
            sl = Math.max(sl, n.getLightFromSky());
         }
      }

      Brightness want = new Brightness(Math.max(bl, 4), sl);
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
               Location l = c.getLocation().add(0.5, 1.1, 0.5);
               if (!l.getNearbyPlayers(24.0).isEmpty()) {
                  World w = c.getWorld();
                  DustOptions col = new DustOptions(
                     tier >= 3 ? Color.fromRGB(255, 210, 90) : (tier == 2 ? Color.fromRGB(230, 170, 255) : Color.fromRGB(170, 110, 240)), 0.8F
                  );

                  for (Block p : this.rm.pedestals(c)) {
                     w.spawnParticle(Particle.DUST, p.getLocation().add(0.5, 1.15, 0.5), 2, 0.2, 0.1, 0.2, 0.0, col);
                  }

                  if (tier >= 2) {
                     for (Block k : this.rm.corners(c)) {
                        w.spawnParticle(Particle.DUST, k.getLocation().add(0.5, 1.1, 0.5), 1, 0.15, 0.1, 0.15, 0.0, col);
                     }
                  }

                  if (tier >= 3) {
                     w.spawnParticle(Particle.END_ROD, l.clone().add(0.0, 0.4, 0.0), 1, 0.2, 0.2, 0.2, 0.01);
                  }
               }
            }
         }
      }
   }
}
