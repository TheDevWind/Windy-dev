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
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

public class MenuItemListener implements Listener {
   private final WindyClans plugin;
   private final NamespacedKey key;

   public MenuItemListener(WindyClans plugin) {
      this.plugin = plugin;
      this.key = new NamespacedKey(plugin, "menu_item");
   }

   private int slot() {
      return Math.max(0, Math.min(8, this.plugin.getConfig().getInt("menu-item.slot", 9) - 1));
   }

   private boolean enabled() {
      return this.plugin.getConfig().getBoolean("menu-item.enabled", false);
   }

   public ItemStack create() {
      ItemStack it = ItemBuilder.of(Material.NETHER_STAR)
         .name("&b&lMenu &7(clic droit)")
         .lore("&7Clan, territoire, banque,", "&7diplomatie, guerre...")
         .build();
      ItemMeta meta = it.getItemMeta();
      meta.setCustomModelData(7701);
      meta.setEnchantmentGlintOverride(false);
      meta.getPersistentDataContainer().set(this.key, PersistentDataType.BYTE, (byte)1);
      it.setItemMeta(meta);
      return it;
   }

   public boolean isMenuItem(ItemStack it) {
      return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(this.key, PersistentDataType.BYTE);
   }

   public void give(Player p) {
      PlayerInventory inv = p.getInventory();

      for (int i = 0; i < inv.getSize(); i++) {
         if (this.isMenuItem(inv.getItem(i))) {
            inv.setItem(i, null);
         }
      }

      if (this.enabled()) {
         ItemStack current = inv.getItem(this.slot());
         if (current != null && !current.getType().isAir()) {
            inv.setItem(this.slot(), null);
            inv.addItem(new ItemStack[]{current}).values().forEach(rest -> p.getWorld().dropItemNaturally(p.getLocation(), rest));
         }

         inv.setItem(this.slot(), this.create());
      }
   }

   @EventHandler
   public void onJoin(PlayerJoinEvent e) {
      Bukkit.getScheduler().runTaskLater(this.plugin, () -> this.give(e.getPlayer()), 5L);
   }

   @EventHandler
   public void onRespawn(PlayerRespawnEvent e) {
      Bukkit.getScheduler().runTaskLater(this.plugin, () -> this.give(e.getPlayer()), 2L);
   }

   @EventHandler
   public void onGamemode(PlayerGameModeChangeEvent e) {
      Bukkit.getScheduler().runTaskLater(this.plugin, () -> this.give(e.getPlayer()), 2L);
   }

   @EventHandler(
      priority = EventPriority.LOWEST
   )
   public void onUse(PlayerInteractEvent e) {
      if (this.isMenuItem(e.getItem())) {
         e.setCancelled(true);
         if (e.getAction() == Action.RIGHT_CLICK_AIR || e.getAction() == Action.RIGHT_CLICK_BLOCK) {
            this.plugin.menus().main(e.getPlayer());
         }
      }
   }

   @EventHandler(
      priority = EventPriority.LOWEST
   )
   public void onEntity(PlayerInteractEntityEvent e) {
      if (this.isMenuItem(e.getPlayer().getInventory().getItem(e.getHand()))) {
         e.setCancelled(true);
         this.plugin.menus().main(e.getPlayer());
      }
   }

   @EventHandler
   public void onDrop(PlayerDropItemEvent e) {
      if (this.isMenuItem(e.getItemDrop().getItemStack())) {
         e.setCancelled(true);
      }
   }

   @EventHandler
   public void onSwap(PlayerSwapHandItemsEvent e) {
      if (this.isMenuItem(e.getMainHandItem()) || this.isMenuItem(e.getOffHandItem())) {
         e.setCancelled(true);
      }
   }

   @EventHandler(
      priority = EventPriority.LOWEST
   )
   public void onClick(InventoryClickEvent e) {
      boolean hotbarSwap = e.getHotbarButton() >= 0 && e.getWhoClicked() instanceof Player p && this.isMenuItem(p.getInventory().getItem(e.getHotbarButton()));
      if (this.isMenuItem(e.getCurrentItem())
         || this.isMenuItem(e.getCursor())
         || hotbarSwap
         || e.getClick() == ClickType.SWAP_OFFHAND && this.isMenuItem(e.getWhoClicked().getInventory().getItemInOffHand())) {
         e.setCancelled(true);
         if (this.isMenuItem(e.getCurrentItem()) && e.getWhoClicked() instanceof Player px && e.getView().getTopInventory().getType() == InventoryType.CRAFTING
            )
          {
            Bukkit.getScheduler().runTask(this.plugin, () -> this.plugin.menus().main(px));
         }
      }
   }

   @EventHandler
   public void onDrag(InventoryDragEvent e) {
      if (this.isMenuItem(e.getOldCursor())) {
         e.setCancelled(true);
      }
   }

   @EventHandler
   public void onDeath(PlayerDeathEvent e) {
      e.getDrops().removeIf(this::isMenuItem);
   }
}
