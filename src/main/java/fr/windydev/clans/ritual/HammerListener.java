package fr.windydev.clans.ritual;

import fr.windydev.clans.WindyClans;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.RayTraceResult;

public class HammerListener implements Listener {
   private final WindyClans plugin;
   private final Set<UUID> breaking = new HashSet<>();

   public HammerListener(WindyClans plugin) {
      this.plugin = plugin;
   }

   @EventHandler(
      ignoreCancelled = true,
      priority = EventPriority.HIGH
   )
   public void onBreak(BlockBreakEvent e) {
      Player p = e.getPlayer();
      if (!this.breaking.contains(p.getUniqueId()) && !p.isSneaking()) {
         ItemStack tool = p.getInventory().getItemInMainHand();
         CustomItems.Def def = this.plugin.items().defOf(tool);
         if (def != null && def.ability() != null && "area-mine".equals(def.ability().getString("type"))) {
            int r = Math.max(1, def.ability().getInt("radius", 1));
            Block center = e.getBlock();
            float centerHardness = center.getType().getHardness();
            BlockFace face = this.face(p, center);
            this.breaking.add(p.getUniqueId());

            try {
               for (int a = -r; a <= r; a++) {
                  for (int b = -r; b <= r; b++) {
                     if (a != 0 || b != 0) {
                        Block t = switch (face) {
                           case UP, DOWN -> center.getRelative(a, 0, b);
                           case NORTH, SOUTH -> center.getRelative(a, b, 0);
                           default -> center.getRelative(0, a, b);
                        };
                        if (this.canBreak(t, tool, centerHardness)) {
                           if (p.getInventory().getItemInMainHand().getType().isAir()) {
                              break;
                           }

                           BlockBreakEvent ev = new BlockBreakEvent(t, p);
                           this.plugin.getServer().getPluginManager().callEvent(ev);
                           if (!ev.isCancelled()) {
                              if (p.getGameMode() == GameMode.CREATIVE) {
                                 t.setType(Material.AIR);
                              } else {
                                 this.plugin.glyphs().breakBlock(p, tool, t);
                                 p.damageItemStack(EquipmentSlot.HAND, 1);
                              }
                           }
                        }
                     }
                  }
               }
            } finally {
               this.breaking.remove(p.getUniqueId());
            }
         }
      }
   }

   private boolean canBreak(Block b, ItemStack tool, float centerHardness) {
      Material m = b.getType();
      if (!m.isAir() && !b.isLiquid()) {
         float h = m.getHardness();
         if (!(h < 0.0F) && !(h > centerHardness + 1.5F)) {
            return m != Material.BEDROCK && !m.name().contains("SPAWNER") && m != Material.END_PORTAL_FRAME ? b.isPreferredTool(tool) : false;
         } else {
            return false;
         }
      } else {
         return false;
      }
   }

   private BlockFace face(Player p, Block target) {
      RayTraceResult rt = p.rayTraceBlocks(6.0);
      if (rt != null && target.equals(rt.getHitBlock()) && rt.getHitBlockFace() != null) {
         return rt.getHitBlockFace();
      } else {
         float pitch = p.getLocation().getPitch();
         if (pitch > 50.0F) {
            return BlockFace.UP;
         } else {
            return pitch < -50.0F ? BlockFace.DOWN : p.getFacing().getOppositeFace();
         }
      }
   }
}
