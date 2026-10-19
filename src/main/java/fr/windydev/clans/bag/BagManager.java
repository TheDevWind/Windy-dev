package fr.windydev.clans.bag;

import fr.windydev.clans.WindyClans;
import fr.windydev.clans.ritual.CustomItems;
import fr.windydev.clans.util.Text;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Map.Entry;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

public class BagManager implements Listener {
   private final WindyClans plugin;
   private final NamespacedKey bagKey;
   private final File file;
   private YamlConfiguration data;
   private final Map<UUID, Inventory> open = new HashMap<>();
   private boolean dirty;

   public BagManager(WindyClans plugin) {
      this.plugin = plugin;
      this.bagKey = new NamespacedKey(plugin, "bag_id");
      this.file = new File(plugin.getDataFolder(), "bags.yml");
   }

   public void load() {
      this.data = YamlConfiguration.loadConfiguration(this.file);
      Bukkit.getScheduler().runTaskTimer(this.plugin, this::saveIfDirty, 1200L, 1200L);
   }

   public void save() {
      for (Entry<UUID, Inventory> e : this.open.entrySet()) {
         this.store(e.getKey(), e.getValue());
      }

      try {
         this.data.save(this.file);
         this.dirty = false;
      } catch (IOException var3) {
         this.plugin.getLogger().warning("Sauvegarde des sacs impossible : " + var3.getMessage());
      }
   }

   private void saveIfDirty() {
      for (Entry<UUID, Inventory> e : this.open.entrySet()) {
         this.store(e.getKey(), e.getValue());
      }

      if (this.dirty) {
         this.save();
      }
   }

   public boolean isBag(ItemStack it) {
      CustomItems.Def d = this.plugin.items().defOf(it);
      return d != null && d.ability() != null && "bag".equals(d.ability().getString("type"));
   }

   public int rows(ItemStack it) {
      CustomItems.Def d = this.plugin.items().defOf(it);
      return d != null && d.ability() != null ? Math.max(1, Math.min(6, d.ability().getInt("rows", 2))) : 1;
   }

   public UUID idOf(ItemStack it) {
      if (it != null && it.hasItemMeta()) {
         String s = (String)it.getItemMeta().getPersistentDataContainer().get(this.bagKey, PersistentDataType.STRING);

         try {
            return s == null ? null : UUID.fromString(s);
         } catch (IllegalArgumentException var4) {
            return null;
         }
      } else {
         return null;
      }
   }

   public UUID stamp(ItemStack it) {
      UUID id = this.idOf(it);
      if (id != null) {
         return id;
      } else {
         id = UUID.randomUUID();
         ItemMeta meta = it.getItemMeta();
         meta.getPersistentDataContainer().set(this.bagKey, PersistentDataType.STRING, id.toString());
         it.setItemMeta(meta);
         return id;
      }
   }

   public void carry(ItemStack from, ItemStack to) {
      UUID id = this.idOf(from);
      if (id != null) {
         ItemMeta meta = to.getItemMeta();
         meta.getPersistentDataContainer().set(this.bagKey, PersistentDataType.STRING, id.toString());
         to.setItemMeta(meta);
      }
   }

   @EventHandler(
      priority = EventPriority.HIGH
   )
   public void onUse(PlayerInteractEvent e) {
      if (e.getHand() == EquipmentSlot.HAND) {
         if (e.getAction() == Action.RIGHT_CLICK_AIR || e.getAction() == Action.RIGHT_CLICK_BLOCK) {
            Player p = e.getPlayer();
            ItemStack it = p.getInventory().getItemInMainHand();
            if (this.isBag(it)) {
               if (e.getAction() != Action.RIGHT_CLICK_BLOCK
                  || e.getClickedBlock() == null
                  || !e.getClickedBlock().getType().isInteractable()
                  || p.isSneaking()) {
                  e.setCancelled(true);
                  if (it.getAmount() > 1) {
                     ItemStack rest = it.clone();
                     rest.setAmount(it.getAmount() - 1);
                     it.setAmount(1);
                     p.getInventory().addItem(new ItemStack[]{rest}).values().forEach(r -> p.getWorld().dropItemNaturally(p.getLocation(), r));
                  }

                  UUID id = this.stamp(it);
                  p.getInventory().setItemInMainHand(it);
                  this.openBag(p, id, this.rows(it), it);
               }
            }
         }
      }
   }

   private void openBag(Player p, UUID id, int rows, ItemStack item) {
      Inventory inv = this.open.get(id);
      if (inv == null || inv.getSize() < rows * 9) {
         ItemStack[] old = inv != null ? inv.getContents() : this.load(id);
         if (inv != null) {
            for (HumanEntity v : new ArrayList<HumanEntity>(inv.getViewers())) {
               v.closeInventory();
            }
         }

         ItemMeta meta = item.getItemMeta();
         inv = meta.hasDisplayName()
            ? Bukkit.createInventory(new BagManager.Holder(id), rows * 9, meta.displayName())
            : Bukkit.createInventory(new BagManager.Holder(id), rows * 9, Text.c("&5Sac magique"));

         for (int i = 0; i < old.length && i < inv.getSize(); i++) {
            inv.setItem(i, old[i]);
         }

         for (int i = inv.getSize(); i < old.length; i++) {
            if (old[i] != null) {
               p.getWorld().dropItemNaturally(p.getLocation(), old[i]);
            }
         }

         this.open.put(id, inv);
      }

      p.openInventory(inv);
      p.playSound(p.getLocation(), Sound.ITEM_BUNDLE_DROP_CONTENTS, 0.7F, 1.3F);
   }

   private ItemStack[] load(UUID id) {
      List<?> l = this.data.getList("bags." + id + ".items");
      if (l == null) {
         return new ItemStack[0];
      } else {
         ItemStack[] out = new ItemStack[l.size()];

         for (int i = 0; i < l.size(); i++) {
            out[i] = l.get(i) instanceof ItemStack s ? s : null;
         }

         return out;
      }
   }

   private void store(UUID id, Inventory inv) {
      this.data.set("bags." + id + ".items", Arrays.asList(inv.getContents()));
      this.dirty = true;
   }

   @EventHandler
   public void onClose(InventoryCloseEvent e) {
      if (e.getInventory().getHolder() instanceof BagManager.Holder h) {
         this.store(h.id(), e.getInventory());
         if (e.getPlayer() instanceof Player p) {
            p.playSound(p.getLocation(), Sound.ITEM_BUNDLE_INSERT, 0.6F, 1.1F);
         }

         Bukkit.getScheduler().runTask(this.plugin, () -> {
            Inventory inv = this.open.get(h.id());
            if (inv != null && inv.getViewers().isEmpty()) {
               this.open.remove(h.id());
            }
         });
      }
   }

   @EventHandler(
      ignoreCancelled = true
   )
   public void onClick(InventoryClickEvent e) {
      if (e.getView().getTopInventory().getHolder() instanceof BagManager.Holder) {
         boolean top = e.getRawSlot() >= 0 && e.getRawSlot() < e.getView().getTopInventory().getSize();
         ItemStack moving = null;
         if (e.getClick().isShiftClick() && !top) {
            moving = e.getCurrentItem();
         } else if (top && e.getAction().name().startsWith("PLACE")) {
            moving = e.getCursor();
         } else if (top && e.getAction() == InventoryAction.SWAP_WITH_CURSOR) {
            moving = e.getCursor();
         } else if (top && e.getClick() == ClickType.NUMBER_KEY) {
            moving = e.getWhoClicked().getInventory().getItem(e.getHotbarButton());
         } else if (top && e.getClick() == ClickType.SWAP_OFFHAND) {
            moving = e.getWhoClicked().getInventory().getItemInOffHand();
         }

         if (moving != null && this.isBag(moving)) {
            e.setCancelled(true);
            if (e.getWhoClicked() instanceof Player p) {
               this.plugin.msg(p, "&cImpossible de ranger un sac dans un sac.");
            }
         }
      }
   }

   @EventHandler(
      ignoreCancelled = true
   )
   public void onDrag(InventoryDragEvent e) {
      if (e.getView().getTopInventory().getHolder() instanceof BagManager.Holder) {
         if (this.isBag(e.getOldCursor())) {
            int size = e.getView().getTopInventory().getSize();
            if (e.getRawSlots().stream().anyMatch(s -> s < size)) {
               e.setCancelled(true);
            }
         }
      }
   }

   public void shutdown() {
      for (Inventory inv : new ArrayList<>(this.open.values())) {
         for (HumanEntity v : new ArrayList<HumanEntity>(inv.getViewers())) {
            v.closeInventory();
         }
      }

      this.save();
   }

   public record Holder(UUID id) implements InventoryHolder {
      public Inventory getInventory() {
         return null;
      }
   }
}
