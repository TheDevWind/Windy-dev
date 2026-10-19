package fr.windydev.clans.quest;

import fr.windydev.clans.WindyClans;
import fr.windydev.clans.gui.Menu;
import fr.windydev.clans.profile.Profile;
import fr.windydev.clans.util.ItemBuilder;
import fr.windydev.clans.util.Text;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.bossbar.BossBar.Color;
import net.kyori.adventure.bossbar.BossBar.Overlay;
import net.kyori.adventure.title.Title;
import net.kyori.adventure.title.Title.Times;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Parcours des nouveaux : étapes guidées et récompensées une fois (MAJ 1.21.0). */
public final class Journey {
   record Step(String id, String name, String help, int level, Reward reward) {
   }

   private static final List<String> KNOWN = List.of("race", "clan", "home", "quete", "niveau", "boss", "donjon");
   private final WindyClans plugin;
   private final QuestManager quests;
   private final List<Step> steps = new ArrayList<>();
   private final Map<UUID, BossBar> bars = new HashMap<>();
   private boolean enabled = true;
   private int barHours = 12;
   private Reward complete = Reward.NONE;

   Journey(WindyClans plugin, QuestManager quests) {
      this.plugin = plugin;
      this.quests = quests;
   }

   void load(ConfigurationSection c) {
      this.steps.clear();
      if (c == null) {
         this.enabled = false;
      } else {
         this.enabled = c.getBoolean("enabled", true);
         this.barHours = Math.max(0, c.getInt("bar-hours", 12));
         this.complete = Reward.of(c.getConfigurationSection("complete"));
         ConfigurationSection ss = c.getConfigurationSection("steps");
         if (ss != null) {
            for (String id : ss.getKeys(false)) {
               ConfigurationSection s = ss.getConfigurationSection(id);
               if (s != null) {
                  if (!KNOWN.contains(id)) {
                     this.plugin.getLogger().warning("quetes.yml : étape de parcours inconnue '" + id + "' (connues : " + String.join(", ", KNOWN) + ").");
                  } else {
                     this.steps.add(new Step(id, s.getString("name", id), s.getString("help", ""), Math.max(1, s.getInt("level", 5)), Reward.of(s.getConfigurationSection("reward"))));
                  }
               }
            }
         }
      }
   }

   void start() {
      Bukkit.getScheduler().runTaskTimer(this.plugin, () -> {
         for (Player p : Bukkit.getOnlinePlayers()) {
            this.check(p);
         }
      }, 100L, 100L);
   }

   void shutdown() {
      for (Entry e : this.entries()) {
         Player p = Bukkit.getPlayer(e.id);
         if (p != null) {
            p.hideBossBar(e.bar);
         }
      }

      this.bars.clear();
   }

   private record Entry(UUID id, BossBar bar) {
   }

   private List<Entry> entries() {
      List<Entry> l = new ArrayList<>();
      this.bars.forEach((k, v) -> l.add(new Entry(k, v)));
      return l;
   }

   private boolean done(Player p, Step s) {
      Profile pr = this.plugin.profiles().of(p);
      return switch (s.id) {
         case "race" -> pr.race() != null;
         case "clan" -> this.plugin.clans().of(p) != null;
         case "home" -> this.plugin.teleports() != null && this.plugin.teleports().hasHome(p.getUniqueId());
         case "quete" -> this.quests.everDone(p.getUniqueId());
         case "niveau" -> pr.level() >= s.level;
         case "boss" -> pr.bossWins() >= 1;
         case "donjon" -> this.quests.dungeons(p.getUniqueId()) >= 1;
         default -> false;
      };
   }

   /** Vérifie les étapes du joueur, donne les récompenses et met la barre à jour. */
   public void check(Player p) {
      if (this.enabled && !this.steps.isEmpty() && p.isOnline()) {
         QuestManager.State s = this.quests.state(p.getUniqueId());
         if (!s.journeyDone) {
            if (!s.journeyInit) {
               // première vérification : ce qui est déjà fait est validé sans récompense (joueurs d'avant la MAJ)
               s.journeyInit = true;

               for (Step st : this.steps) {
                  if (this.done(p, st)) {
                     s.journey.add(st.id);
                  }
               }

               if (s.journey.size() >= this.steps.size()) {
                  s.journeyDone = true;
               }

               this.quests.markDirty();
            } else {
               for (Step st : this.steps) {
                  if (!s.journey.contains(st.id) && this.done(p, st)) {
                     s.journey.add(st.id);
                     this.quests.markDirty();
                     String got = st.reward.give(this.plugin, p, "quetes");
                     this.plugin.msg(p, "&d✦ Parcours &7: étape validée &f" + st.name + (got.isEmpty() ? "" : " &8(" + got + "&8)"));
                     p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6F, 1.6F);
                     Step next = this.next(s);
                     if (next != null) {
                        this.plugin.msg(p, "&7Étape suivante : &e" + next.name + (next.help.isEmpty() ? "" : " &8(" + next.help + ")"));
                     }
                  }
               }

               if (s.journey.size() >= this.steps.size()) {
                  s.journeyDone = true;
                  this.quests.markDirty();
                  String got = this.complete.give(this.plugin, p, "quetes");
                  p.showTitle(
                     Title.title(
                        Text.c("&d✦ Parcours terminé ✦"),
                        Text.c("&7Tu connais maintenant WINDY_MC !"),
                        Times.times(Duration.ofMillis(300L), Duration.ofSeconds(3L), Duration.ofMillis(700L))
                     )
                  );
                  p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0F, 1.0F);
                  this.plugin.msg(p, "&d✦ Parcours des nouveaux terminé ! " + (got.isEmpty() ? "" : "&8(" + got + "&8)"));
                  Bukkit.broadcast(Text.c(this.plugin.settings().prefix + "&d✦ &f" + p.getName() + " &7a terminé le parcours des nouveaux. Bienvenue !"));
               }
            }
         }

         this.updateBar(p, s);
      }
   }

   private Step next(QuestManager.State s) {
      for (Step st : this.steps) {
         if (!s.journey.contains(st.id)) {
            return st;
         }
      }

      return null;
   }

   private void updateBar(Player p, QuestManager.State s) {
      Step next = this.next(s);
      boolean show = !s.journeyDone && !s.journeyHidden && next != null && this.plugin.profiles().of(p).playMinutes() < this.barHours * 60L;
      BossBar bar = this.bars.get(p.getUniqueId());
      if (!show) {
         if (bar != null) {
            p.hideBossBar(bar);
            this.bars.remove(p.getUniqueId());
         }
      } else {
         int done = s.journey.size();
         String name = "&d✦ Parcours " + (done + 1) + "/" + this.steps.size() + " &8| &f" + next.name + (next.help.isEmpty() ? "" : " &8| &7" + next.help);
         float prog = Math.min(1.0F, (float)done / this.steps.size());
         if (bar == null) {
            bar = BossBar.bossBar(Text.c(name), prog, Color.PURPLE, Overlay.NOTCHED_10);
            this.bars.put(p.getUniqueId(), bar);
            p.showBossBar(bar);
         } else {
            bar.name(Text.c(name));
            bar.progress(prog);
         }
      }
   }

   public void quit(Player p) {
      BossBar bar = this.bars.remove(p.getUniqueId());
      if (bar != null) {
         p.hideBossBar(bar);
      }
   }

   ItemStack icon(Player p) {
      QuestManager.State s = this.quests.state(p.getUniqueId());
      if (!this.enabled || this.steps.isEmpty()) {
         return ItemBuilder.of(Material.GRAY_DYE).name("&7Parcours des nouveaux").lore("&8Désactivé.").build();
      } else if (s.journeyDone) {
         return ItemBuilder.of(Material.MAP).name("&dParcours des nouveaux").lore("&a✔ Terminé", "", "&eClic : revoir les étapes").build();
      } else {
         Step next = this.next(s);
         return ItemBuilder.of(Material.FILLED_MAP)
            .name("&dParcours des nouveaux")
            .lore(
               "&7Étapes : &f" + s.journey.size() + "/" + this.steps.size(),
               next == null ? "" : "&7Suivante : &e" + next.name,
               "",
               "&eClic : voir les étapes"
            )
            .glow(true)
            .build();
      }
   }

   public void menu(Player p) {
      QuestManager.State s = this.quests.state(p.getUniqueId());
      this.check(p);
      Menu m = new Menu(p, "row", 3, "&dParcours des nouveaux");
      Step next = this.next(s);
      int[] slots = new int[]{10, 11, 12, 13, 14, 15, 16};

      for (int i = 0; i < this.steps.size() && i < slots.length; i++) {
         Step st = this.steps.get(i);
         boolean ok = s.journey.contains(st.id);
         boolean isNext = st == next;
         List<String> lore = new ArrayList<>();
         if (!st.help.isEmpty()) {
            lore.add("&7" + st.help);
         }

         if (!st.reward.empty()) {
            lore.add("");
            lore.add(ok ? "&8Récompense reçue :" : "&7Récompense :");
            lore.addAll(st.reward.lines(this.plugin));
         }

         lore.add("");
         lore.add(ok ? "&a✔ Fait" : (isNext ? "&e➜ À faire maintenant" : "&8À faire"));
         m.set(
            slots[i],
            ItemBuilder.of(ok ? Material.LIME_DYE : (isNext ? Material.WRITABLE_BOOK : Material.GRAY_DYE))
               .amount(i + 1)
               .name((ok ? "&a✔ " : (isNext ? "&e" : "&7")) + (i + 1) + ". " + st.name)
               .lore(lore)
               .glow(isNext)
               .build()
         );
      }

      List<String> head = new ArrayList<>();
      head.add("&7Étapes faites : &f" + s.journey.size() + "/" + this.steps.size());
      head.add("");
      head.add(s.journeyDone ? "&a✔ Parcours terminé" : "&7Récompense finale :");
      if (!s.journeyDone) {
         head.addAll(this.complete.lines(this.plugin));
      }

      m.set(4, ItemBuilder.of(Material.FILLED_MAP).name("&dParcours des nouveaux").lore(head).build());
      m.set(18, ItemBuilder.of(Material.ARROW).name("&7Retour aux quêtes").build(), e -> this.quests.menu(p));
      if (!s.journeyDone) {
         m.set(
            26,
            ItemBuilder.of(s.journeyHidden ? Material.ENDER_EYE : Material.ENDER_PEARL)
               .name(s.journeyHidden ? "&aAfficher la barre du parcours" : "&7Masquer la barre du parcours")
               .lore("&8La barre en haut de l'écran", "&8indique l'étape suivante.")
               .build(),
            e -> {
               s.journeyHidden = !s.journeyHidden;
               this.quests.markDirty();
               this.updateBar(p, s);
               this.menu(p);
            }
         );
      }

      m.set(22, ItemBuilder.of(Material.BARRIER).name("&cFermer").build(), e -> p.closeInventory());
      m.fillBorder();
      m.open(p);
   }

   void command(Player p, String arg) {
      QuestManager.State s = this.quests.state(p.getUniqueId());
      switch (arg) {
         case "masquer", "cacher", "off" -> {
            s.journeyHidden = true;
            this.quests.markDirty();
            this.updateBar(p, s);
            this.plugin.msg(p, "&7Barre du parcours masquée. &8(/parcours afficher)");
         }
         case "afficher", "on" -> {
            s.journeyHidden = false;
            this.quests.markDirty();
            this.updateBar(p, s);
            this.plugin.msg(p, "&7Barre du parcours affichée.");
         }
         default -> this.menu(p);
      }
   }
}
