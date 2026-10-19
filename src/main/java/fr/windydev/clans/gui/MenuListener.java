package fr.windydev.clans.gui;

import fr.windydev.clans.WindyClans;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

public class MenuListener implements Listener {
   private final WindyClans plugin;

   public MenuListener(WindyClans plugin) {
      this.plugin = plugin;
   }

   @EventHandler
   public void onClick(InventoryClickEvent e) {
      if (e.getView().getTopInventory().getHolder() instanceof Menu menu) {
         e.setCancelled(true);
         if (e.getRawSlot() >= 0 && e.getRawSlot() < e.getView().getTopInventory().getSize()) {
            if (e.getCurrentItem() != null) {
               if (e.getWhoClicked() instanceof Player p) {
                  p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.4F, 1.2F);
               }

               try {
                  menu.click(e);
               } catch (Exception var5) {
                  this.plugin.getLogger().warning("Erreur dans le menu : " + var5);
                  var5.printStackTrace();
               }
            }
         }
      }
   }

   @EventHandler
   public void onDrag(InventoryDragEvent e) {
      if (e.getView().getTopInventory().getHolder() instanceof Menu) {
         e.setCancelled(true);
      }
   }
}
