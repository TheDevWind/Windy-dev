package fr.windydev.clans.listener;

import fr.windydev.clans.WindyClans;
import fr.windydev.clans.util.ItemBuilder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.inventory.InventoryType.SlotType;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

public class InventoryButtonListener implements Listener {
   private final WindyClans plugin;
   private final NamespacedKey key;

   public InventoryButtonListener(WindyClans plugin) {
      this.plugin = plugin;
      this.key = new NamespacedKey(plugin, "inv_button");
   }

   public void start() {
      Bukkit.getScheduler().runTaskTimer(this.plugin, () -> {
         if (this.enabled()) {
            for (Player p : Bukkit.getOnlinePlayers()) {
               this.place(p);
            }
         }
      }, 40L, 40L);
   }

   private boolean enabled() {
      return this.plugin.getConfig().getBoolean("inventory-button.enabled", true);
   }

   private ItemStack button() {
      ItemStack it = ItemBuilder.of(Material.NETHER_STAR)
         .name("&b&lMenu du clan")
         .lore("&7Clan, territoire, banque,", "&7diplomatie, guerre...", "", "&eClic : ouvrir")
         .build();
      ItemMeta meta = it.getItemMeta();
      meta.setCustomModelData(7703);
      meta.setEnchantmentGlintOverride(false);
      meta.getPersistentDataContainer().set(this.key, PersistentDataType.BYTE, (byte)1);
      it.setItemMeta(meta);
      return it;
   }

   public boolean isButton(ItemStack it) {
      return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(this.key, PersistentDataType.BYTE);
   }

   public void place(Player p) {
      if (this.enabled() && p.isOnline()) {
         if (p.getOpenInventory().getTopInventory() instanceof CraftingInventory ci) {
            if (ci.getType() == InventoryType.CRAFTING) {
               for (ItemStack m : ci.getMatrix()) {
                  if (m != null && !m.getType().isAir()) {
                     return;
                  }
               }

               if (!this.isButton(ci.getResult())) {
                  ci.setResult(this.button());
               }
            }
         }
      }
   }

   @EventHandler
   public void onJoin(PlayerJoinEvent e) {
      Bukkit.getScheduler().runTaskLater(this.plugin, () -> this.place(e.getPlayer()), 20L);
   }

   @EventHandler
   public void onRespawn(PlayerRespawnEvent e) {
      Bukkit.getScheduler().runTaskLater(this.plugin, () -> this.place(e.getPlayer()), 5L);
   }

   @EventHandler
   public void onGamemode(PlayerGameModeChangeEvent e) {
      Bukkit.getScheduler().runTaskLater(this.plugin, () -> this.place(e.getPlayer()), 5L);
   }

   @EventHandler
   public void onClose(InventoryCloseEvent e) {
      if (e.getPlayer() instanceof Player p) {
         Bukkit.getScheduler().runTaskLater(this.plugin, () -> this.place(p), 1L);
      }
   }

   @EventHandler(
      priority = EventPriority.MONITOR
   )
   public void onPrepare(PrepareItemCraftEvent e) {
      if (this.enabled() && e.getInventory().getType() == InventoryType.CRAFTING) {
         boolean empty = true;

         for (ItemStack m : e.getInventory().getMatrix()) {
            if (m != null && !m.getType().isAir()) {
               empty = false;
               break;
            }
         }

         if (empty) {
            e.getInventory().setResult(this.button());
         } else if (this.isButton(e.getInventory().getResult())) {
            e.getInventory().setResult(null);
         }
      }
   }

   @EventHandler(
      priority = EventPriority.LOWEST
   )
   public void onClick(InventoryClickEvent e) {
      if (e.getWhoClicked() instanceof Player p) {
         boolean onButton = this.isButton(e.getCurrentItem())
            || e.getSlotType() == SlotType.RESULT
               && e.getView().getTopInventory().getType() == InventoryType.CRAFTING
               && this.isButton(e.getView().getTopInventory().getItem(0));
         if (this.isButton(e.getCursor())) {
            e.setCancelled(true);
            p.setItemOnCursor(null);
         } else if (onButton) {
            e.setCancelled(true);
            if (e.getSlotType() == SlotType.RESULT) {
               Bukkit.getScheduler().runTask(this.plugin, () -> this.plugin.menus().main(p));
            }
         }
      }
   }

   @EventHandler
   public void onCraft(CraftItemEvent e) {
      if (this.isButton(e.getRecipe().getResult()) || this.isButton(e.getCurrentItem())) {
         e.setCancelled(true);
      }
   }
}
