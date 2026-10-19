package fr.windydev.clans.gui;

import fr.windydev.clans.WindyClans;
import fr.windydev.clans.util.Glyphs;
import fr.windydev.clans.util.ItemBuilder;
import fr.windydev.clans.util.Text;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public class Menu implements InventoryHolder {
   private final String layout;
   private final int rows;
   private final String title;
   private final boolean fancy;
   private final ItemStack[] items;
   private final Map<Integer, Consumer<InventoryClickEvent>> handlers = new HashMap<>();
   private final List<Component> labels = new ArrayList<>();
   private Inventory inv;

   public Menu(Player viewer, String layout, int rows, String title) {
      this.layout = layout;
      this.rows = rows;
      this.title = title;
      this.items = new ItemStack[rows * 9];
      this.fancy = WindyClans.get().pack().has(viewer) && Glyphs.hasLayout(layout) && Glyphs.layoutRows(layout) == rows;
   }

   public boolean fancy() {
      return this.fancy;
   }

   public Menu set(int slot, ItemStack item) {
      this.items[slot] = item;
      this.handlers.remove(slot);
      return this;
   }

   public Menu set(int slot, ItemStack item, Consumer<InventoryClickEvent> onClick) {
      this.items[slot] = item;
      if (onClick != null) {
         this.handlers.put(slot, onClick);
      } else {
         this.handlers.remove(slot);
      }

      return this;
   }

   public Menu label(int x, int top, String legacy) {
      if (this.fancy) {
         this.labels.add(Glyphs.label(x, top, legacy));
      }

      return this;
   }

   public Menu label(int x, int top, String legacy, int maxWidth) {
      if (this.fancy) {
         this.labels.add(Glyphs.label(x, top, legacy, maxWidth));
      }

      return this;
   }

   public Menu overlay(Component c) {
      if (this.fancy) {
         this.labels.add(c);
      }

      return this;
   }

   public boolean painted() {
      return this.fancy && Glyphs.painted(this.layout);
   }

   public Menu hitbox(int slot, ItemStack look, Consumer<InventoryClickEvent> onClick) {
      if (!this.fancy) {
         return this;
      } else {
         ItemStack blank = look.clone();
         blank.setType(Material.PAPER);
         ItemMeta meta = blank.getItemMeta();
         meta.setCustomModelData(7702);
         meta.setEnchantmentGlintOverride(false);
         blank.setItemMeta(meta);
         blank.setAmount(1);
         return this.set(slot, blank, onClick);
      }
   }

   public Menu button(int iconSlot, ItemStack icon, String label, Consumer<InventoryClickEvent> onClick) {
      return this.button(iconSlot, 4, icon, label, onClick);
   }

   public Menu button(int iconSlot, int span, ItemStack icon, String label, Consumer<InventoryClickEvent> onClick) {
      this.set(iconSlot, icon, onClick);

      for (int i = 1; i < span; i++) {
         this.hitbox(iconSlot + i, icon, onClick);
      }

      if (!this.painted()) {
         int col = iconSlot % 9;
         int row = iconSlot / 9;
         this.label(8 + col * 18 + 20, 18 + row * 18 + 4, label);
      }

      return this;
   }

   public Menu fillBorder() {
      if (this.fancy) {
         return this;
      } else {
         ItemStack pane = ItemBuilder.of(Material.GRAY_STAINED_GLASS_PANE).name(" ").build();

         for (int i = 0; i < this.items.length; i++) {
            int row = i / 9;
            int col = i % 9;
            if ((row == 0 || row == this.rows - 1 || col == 0 || col == 8) && this.items[i] == null) {
               this.items[i] = pane;
            }
         }

         return this;
      }
   }

   public void open(Player p) {
      Component t;
      if (this.fancy) {
         t = Glyphs.menuTitle(this.layout, this.title);

         for (Component l : this.labels) {
            t = t.append(l);
         }
      } else {
         t = Text.c("&8" + Text.strip(this.title));
      }

      this.inv = Bukkit.createInventory(this, this.rows * 9, t);
      this.inv.setContents(this.items);
      p.openInventory(this.inv);
   }

   void click(InventoryClickEvent e) {
      Consumer<InventoryClickEvent> h = this.handlers.get(e.getRawSlot());
      if (h != null) {
         h.accept(e);
      }
   }

   public Inventory getInventory() {
      return this.inv;
   }
}
