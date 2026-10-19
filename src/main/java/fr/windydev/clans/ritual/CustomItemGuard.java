package fr.windydev.clans.ritual;

import fr.windydev.clans.WindyClans;
import org.bukkit.block.Crafter;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.CrafterCraftEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.SmithingInventory;

public class CustomItemGuard implements Listener {
   private final WindyClans plugin;

   public CustomItemGuard(WindyClans plugin) {
      this.plugin = plugin;
   }

   private boolean custom(ItemStack it) {
      return it != null && !it.getType().isAir() && this.plugin.items().idOf(it) != null;
   }

   @EventHandler(
      priority = EventPriority.HIGH
   )
   public void onCraft(PrepareItemCraftEvent e) {
      for (ItemStack m : e.getInventory().getMatrix()) {
         if (this.custom(m)) {
            e.getInventory().setResult(null);
            return;
         }
      }
   }

   @EventHandler(
      priority = EventPriority.HIGH
   )
   public void onSmith(PrepareSmithingEvent e) {
      SmithingInventory inv = e.getInventory();

      for (int i = 0; i < 3; i++) {
         if (this.custom(inv.getItem(i))) {
            e.setResult(null);
            return;
         }
      }
   }

   @EventHandler(
      priority = EventPriority.HIGH,
      ignoreCancelled = true
   )
   public void onCrafter(CrafterCraftEvent e) {
      if (e.getBlock().getState() instanceof Crafter c) {
         for (ItemStack it : c.getInventory().getContents()) {
            if (this.custom(it)) {
               e.setCancelled(true);
               return;
            }
         }
      }
   }

   @EventHandler(
      ignoreCancelled = true
   )
   public void onPlace(BlockPlaceEvent e) {
      String id = this.plugin.items().idOf(e.getItemInHand());
      if (id != null && e.getItemInHand().getType().isBlock()) {
         if (!id.equals("verre_magique") && !id.equals("extracteur") && !id.equals("infuseur") && !id.startsWith("amethyste_infusee_") && !id.startsWith("magnetite_infusee_")) {
            e.setCancelled(true);
         }
      }
   }
}
