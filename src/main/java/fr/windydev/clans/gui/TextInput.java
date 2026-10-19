package fr.windydev.clans.gui;

import fr.windydev.clans.WindyClans;
import fr.windydev.clans.util.ItemBuilder;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;

public class TextInput implements Listener {
   private static final Map<UUID, TextInput.Session> SESSIONS = new HashMap<>();
   private static TextInput instance;

   public static void register(WindyClans plugin) {
      instance = new TextInput();
      Bukkit.getPluginManager().registerEvents(instance, plugin);
   }

   public static void ask(WindyClans plugin, Player p, String prompt, String initial, Consumer<String> onDone, Runnable onCancel) {
      p.closeInventory();
      Bukkit.getScheduler()
         .runTask(
            plugin,
            () -> {
               InventoryView view = p.openAnvil(null, true);
               if (view != null && view.getTopInventory() instanceof AnvilInventory anvil) {
                  anvil.setFirstItem(
                     ItemBuilder.of(Material.PAPER)
                        .name("&f" + (initial != null && !initial.isBlank() ? initial : prompt))
                        .lore("&7" + prompt, "&eRenomme puis clique sur le résultat")
                        .build()
                  );
                  SESSIONS.put(p.getUniqueId(), new TextInput.Session(onDone, onCancel, view));
                  plugin.msg(p, "&7" + prompt + " : &ftape le texte en haut puis clique sur le papier de droite.");
               } else {
                  plugin.msg(p, "&cImpossible d'ouvrir la saisie.");
               }
            }
         );
   }

   private static TextInput.Session session(HumanEntity h, InventoryView view) {
      TextInput.Session s = SESSIONS.get(h.getUniqueId());
      return s != null && s.view() == view ? s : null;
   }

   @EventHandler(
      priority = EventPriority.HIGHEST
   )
   public void onPrepare(PrepareAnvilEvent e) {
      TextInput.Session s = session(e.getView().getPlayer(), e.getView());
      if (s != null) {
         String text = e.getInventory().getRenameText();
         if (text == null || text.isBlank()) {
            text = "";
         }

         ItemStack result = ItemBuilder.of(Material.PAPER).name("&a✔ " + text).lore("&7Clique pour valider").build();
         e.setResult(result);
         e.getInventory().setRepairCost(0);
      }
   }

   @EventHandler(
      priority = EventPriority.HIGHEST
   )
   public void onClick(InventoryClickEvent e) {
      TextInput.Session s = session(e.getWhoClicked(), e.getView());
      if (s != null) {
         e.setCancelled(true);
         if (e.getRawSlot() == 2 && e.getWhoClicked() instanceof Player p) {
            String text = ((AnvilInventory)e.getInventory()).getRenameText();
            if (text != null && !text.isBlank()) {
               SESSIONS.remove(p.getUniqueId());
               clear(e.getInventory());
               p.closeInventory();
               String value = text.trim();
               Bukkit.getScheduler().runTask(WindyClans.get(), () -> s.onDone().accept(value));
            }
         }
      }
   }

   @EventHandler
   public void onDrag(InventoryDragEvent e) {
      if (session(e.getWhoClicked(), e.getView()) != null) {
         e.setCancelled(true);
      }
   }

   @EventHandler
   public void onClose(InventoryCloseEvent e) {
      TextInput.Session s = session(e.getPlayer(), e.getView());
      if (s != null) {
         SESSIONS.remove(e.getPlayer().getUniqueId());
         clear(e.getInventory());
         if (s.onCancel() != null && e.getPlayer() instanceof Player p) {
            Bukkit.getScheduler().runTask(WindyClans.get(), () -> {
               if (p.isOnline()) {
                  s.onCancel().run();
               }
            });
         }
      }
   }

   private static void clear(Inventory inv) {
      for (int i = 0; i < inv.getSize(); i++) {
         inv.setItem(i, null);
      }
   }

   private record Session(Consumer<String> onDone, Runnable onCancel, InventoryView view) {
   }
}
