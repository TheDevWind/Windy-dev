package fr.windydev.clans.quest;

import fr.windydev.clans.WindyClans;
import fr.windydev.clans.ritual.CustomItems;
import fr.windydev.clans.util.Text;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Récompense configurable : coins, XP personnelle, âmes et objets.
 * Objets : "windy:id:quantité", "MATÉRIAU:quantité", quantité "min-max", "@0.25" à la fin pour une chance.
 */
public record Reward(long coins, long xp, long souls, long clanXp, List<String> items) {
   public static final Reward NONE = new Reward(0L, 0L, 0L, 0L, List.of());
   private static final Random RND = new Random();

   public static Reward of(ConfigurationSection s) {
      if (s == null) {
         return NONE;
      } else {
         return new Reward(
            Math.max(0L, s.getLong("coins")),
            Math.max(0L, s.getLong("xp")),
            Math.max(0L, s.getLong("ames", s.getLong("souls"))),
            Math.max(0L, s.getLong("xp-clan", s.getLong("clan-xp"))),
            List.copyOf(s.getStringList("items"))
         );
      }
   }

   public boolean empty() {
      return this.coins <= 0L && this.xp <= 0L && this.souls <= 0L && this.clanXp <= 0L && this.items.isEmpty();
   }

   /** Donne la récompense et rend le texte des gains (pour le message). */
   public String give(WindyClans plugin, Player p, String source) {
      List<String> got = new ArrayList<>();
      if (this.coins > 0L) {
         plugin.menus().actions.giveCurrency(p, this.coins);
         got.add("&6+" + this.coins + " " + plugin.settings().currencyName);
      }

      if (this.xp > 0L) {
         plugin.profiles().addXp(p, this.xp, source);
         got.add("&d+" + this.xp + " XP");
      }

      if (this.souls > 0L) {
         plugin.profiles().addSouls(p, this.souls, false);
         got.add("&5+" + this.souls + " âmes");
      }

      if (this.clanXp > 0L && plugin.clans().of(p) != null) {
         plugin.clans().addXp(plugin.clans().of(p), this.clanXp);
         got.add("&e+" + this.clanXp + " XP de clan");
      }

      for (String spec : this.items) {
         ItemStack it = item(plugin, spec);
         if (it != null) {
            for (ItemStack rest : p.getInventory().addItem(it).values()) {
               p.getWorld().dropItemNaturally(p.getLocation(), rest);
            }

            got.add("&f" + it.getAmount() + "x " + name(plugin, it));
         }
      }

      return String.join("&7, ", got);
   }

   /** Lignes de description pour un menu. */
   public List<String> lines(WindyClans plugin) {
      List<String> l = new ArrayList<>();
      if (this.coins > 0L) {
         l.add("&6  + " + this.coins + " " + plugin.settings().currencyName);
      }

      if (this.xp > 0L) {
         l.add("&d  + " + this.xp + " XP");
      }

      if (this.souls > 0L) {
         l.add("&5  + " + this.souls + " âmes");
      }

      if (this.clanXp > 0L) {
         l.add("&e  + " + this.clanXp + " XP de clan");
      }

      for (String spec : this.items) {
         l.add("&f  + " + describe(plugin, spec));
      }

      return l;
   }

   public static String name(WindyClans plugin, ItemStack it) {
      String id = plugin.items().idOf(it);
      if (id != null) {
         return Text.strip(plugin.items().displayName("windy:" + id));
      } else {
         return CustomItems.prettify(it.getType().name());
      }
   }

   /** "windy:piece:20-40@0.5" -> "20 à 40 Coins (50 %)". */
   public static String describe(WindyClans plugin, String spec) {
      String s = spec.trim();
      String chance = "";
      int at = s.lastIndexOf('@');
      if (at > 0) {
         try {
            chance = " &8(" + Math.round(Double.parseDouble(s.substring(at + 1)) * 100.0) + " %)";
         } catch (NumberFormatException var9) {
         }

         s = s.substring(0, at);
      }

      String[] p = s.split(":");
      String amount = "1";
      String name;
      if (p[0].equalsIgnoreCase("windy") && p.length >= 2) {
         name = Text.strip(plugin.items().displayName("windy:" + p[1]));
         if (p.length >= 3) {
            amount = p[2];
         }
      } else {
         Material m = Material.matchMaterial(p[0]);
         name = m == null ? p[0] : CustomItems.prettify(m.name());
         if (p.length >= 2) {
            amount = p[1];
         }
      }

      return amount.replace("-", " à ") + "x " + name + chance;
   }

   /** Crée l'objet décrit (null si la chance échoue ou si l'objet est inconnu). */
   public static ItemStack item(WindyClans plugin, String spec) {
      String s = spec.trim();
      int at = s.lastIndexOf('@');
      if (at > 0) {
         try {
            if (RND.nextDouble() >= Double.parseDouble(s.substring(at + 1))) {
               return null;
            }
         } catch (NumberFormatException var8) {
         }

         s = s.substring(0, at);
      }

      String[] p = s.split(":");

      try {
         if (p[0].equalsIgnoreCase("windy") && p.length >= 2) {
            ItemStack it = plugin.items().create(p[1]);
            if (it != null && p.length >= 3) {
               it.setAmount(amount(p[2], it.getMaxStackSize()));
            }

            return it;
         } else {
            Material m = Material.matchMaterial(p[0]);
            return m != null && m.isItem() ? new ItemStack(m, p.length >= 2 ? amount(p[1], m.getMaxStackSize()) : 1) : null;
         }
      } catch (NumberFormatException var7) {
         return null;
      }
   }

   private static int amount(String s, int max) {
      int dash = s.indexOf('-');
      int n;
      if (dash > 0) {
         int a = Integer.parseInt(s.substring(0, dash).trim());
         int b = Integer.parseInt(s.substring(dash + 1).trim());
         n = a + RND.nextInt(Math.max(1, b - a + 1));
      } else {
         n = Integer.parseInt(s.trim());
      }

      return Math.max(1, Math.min(Math.max(1, max), n));
   }
}
