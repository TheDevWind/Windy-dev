package fr.windydev.clans.feature;

import fr.windydev.clans.WindyClans;
import fr.windydev.clans.data.Settings;
import fr.windydev.clans.model.Clan;
import fr.windydev.clans.util.Text;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.title.Title;
import net.kyori.adventure.title.Title.Times;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

public class WarManager {
   private final WindyClans plugin;
   private final List<WarManager.War> wars = new ArrayList<>();

   public WarManager(WindyClans plugin) {
      this.plugin = plugin;
   }

   public void start() {
      Bukkit.getScheduler().runTaskTimer(this.plugin, this::tick, 20L, 20L);
   }

   public WarManager.War warOf(Clan c) {
      if (c == null) {
         return null;
      } else {
         for (WarManager.War w : this.wars) {
            if (w.involves(c)) {
               return w;
            }
         }

         return null;
      }
   }

   public boolean atWar(Clan a, Clan b) {
      if (a != null && b != null) {
         for (WarManager.War w : this.wars) {
            if (w.active && w.involves(a) && w.involves(b)) {
               return true;
            }
         }

         return false;
      } else {
         return false;
      }
   }

   public String declare(Clan attacker, Clan defender) {
      return this.declare(attacker, defender, null);
   }

   public String declare(Clan attacker, Clan defender, String reason) {
      if (reason != null && reason.strip().length() >= 5) {
         reason = reason.strip();
         Settings s = this.plugin.settings();
         if (attacker == defender) {
            return "&cTu ne peux pas déclarer la guerre à ton propre clan.";
         } else if (attacker.allies().contains(defender.id())) {
            return "&cRompez d'abord l'alliance (Diplomatie > " + defender.name() + " > Neutre).";
         } else if (this.warOf(attacker) != null) {
            return "&cTon clan est déjà engagé dans une guerre.";
         } else if (this.warOf(defender) != null) {
            return "&c" + defender.name() + " est déjà engagé dans une guerre.";
         } else if (defender.claims().isEmpty()) {
            return "&cCe clan n'a pas de territoire.";
         } else {
            long cd = this.plugin.clans().lastWar(attacker, defender) + s.warCooldown * 3600000L - System.currentTimeMillis();
            if (cd > 0L) {
               return "&cVous vous êtes affrontés récemment. Nouvelle guerre possible dans &f" + Text.formatTime(cd / 1000L) + "&c.";
            } else if (this.plugin.clans().onlineCount(defender) < s.warMinDefenders) {
               return "&cIl faut au moins &f" + s.warMinDefenders + " &cmembre(s) de " + defender.name() + " connecté(s).";
            } else {
               long now = System.currentTimeMillis();
               long start = now + s.warPrep * 60000L;
               WarManager.War w = new WarManager.War(attacker.id(), defender.id(), start, start + s.warDuration * 60000L);
               this.wars.add(w);
               this.plugin.clans().setEnemy(attacker, defender);
               this.plugin.clans().setEnemy(defender, attacker);
               this.plugin.clans().log(attacker, "Guerre déclarée à " + defender.name() + " : " + reason);
               this.plugin.clans().log(defender, attacker.name() + " nous a déclaré la guerre : " + reason);
               Bukkit.broadcast(
                  Text.c(
                     s.prefix
                        + "&4&l⚔ GUERRE ! &c"
                        + attacker.name()
                        + " &7déclare la guerre à &c"
                        + defender.name()
                        + "&7. Début dans &f"
                        + s.warPrep
                        + " min&7."
                  )
               );
               Bukkit.broadcast(Text.c(s.prefix + "&7Raison : &f« " + reason + " »"));

               for (Player p : this.plugin.clans().onlineMembers(defender)) {
                  p.showTitle(
                     Title.title(
                        Text.c("&4⚔ GUERRE"),
                        Text.c("&c" + attacker.name() + " vous attaque dans " + s.warPrep + " min"),
                        Times.times(Duration.ofMillis(300L), Duration.ofSeconds(3L), Duration.ofMillis(700L))
                     )
                  );
                  p.playSound(p.getLocation(), Sound.EVENT_RAID_HORN, 1.0F, 1.0F);
               }

               this.plugin.bot().staffLog("⚔ Guerre déclarée", "**" + attacker.name() + "** → **" + defender.name() + "**\nRaison : " + reason);
               this.plugin
                  .discord()
                  .send(
                     "war",
                     "⚔️ **"
                        + attacker.name()
                        + "** déclare la guerre à **"
                        + defender.name()
                        + "** ! Début dans "
                        + s.warPrep
                        + " minutes, durée "
                        + s.warDuration
                        + " minutes.\n> Raison : "
                        + reason
                  );
               return null;
            }
         }
      } else {
         return "&cIl faut donner une raison à la guerre (5 caractères minimum).";
      }
   }

   public void surrender(Clan c) {
      WarManager.War w = this.warOf(c);
      if (w != null) {
         Clan winner = this.plugin.clans().get(w.opponent(c));
         Bukkit.broadcast(Text.c(this.plugin.settings().prefix + "&f" + c.name() + " &7capitule face à &f" + (winner == null ? "?" : winner.name()) + "&7."));
         this.finish(w, winner, c);
      }
   }

   public void onKill(Clan killer, Clan victim) {
      for (WarManager.War w : this.wars) {
         if (w.active && w.involves(killer) && w.involves(victim) && killer != victim) {
            if (w.attacker.equals(killer.id())) {
               w.attackerKills++;
            } else {
               w.defenderKills++;
            }

            this.plugin.clans().addXp(killer, this.plugin.settings().xpWarKill);
         }
      }
   }

   private void tick() {
      long now = System.currentTimeMillis();

      for (WarManager.War w : new ArrayList<>(this.wars)) {
         Clan a = this.plugin.clans().get(w.attacker);
         Clan d = this.plugin.clans().get(w.defender);
         if (a == null || d == null) {
            this.wars.remove(w);
         } else if (!w.active && now >= w.startAt) {
            if (this.plugin.clans().onlineCount(d) < this.plugin.settings().warMinDefenders) {
               this.wars.remove(w);
               Bukkit.broadcast(
                  Text.c(
                     this.plugin.settings().prefix
                        + "&7La guerre &f"
                        + a.name()
                        + " &7vs &f"
                        + d.name()
                        + " &7est annulée : pas assez de défenseurs connectés."
                  )
               );
            } else {
               w.active = true;
               Bukkit.broadcast(
                  Text.c(
                     this.plugin.settings().prefix
                        + "&4&l⚔ La guerre commence ! &c"
                        + a.name()
                        + " &7vs &c"
                        + d.name()
                        + "&7. Fin dans &f"
                        + this.plugin.settings().warDuration
                        + " min&7."
                  )
               );

               for (Clan c : List.of(a, d)) {
                  for (Player p : this.plugin.clans().onlineMembers(c)) {
                     p.showTitle(Title.title(Text.c("&4⚔ À L'ATTAQUE"), Text.c("&7La guerre a commencé")));
                     p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.7F, 1.0F);
                  }
               }

               this.plugin.discord().send("war", "\ud83d\udd25 La guerre **" + a.name() + "** vs **" + d.name() + "** a commencé !");
            }
         } else if (w.active && now >= w.endAt) {
            Clan winner = w.attackerKills > w.defenderKills ? a : (w.defenderKills > w.attackerKills ? d : null);
            this.finish(w, winner, winner == null ? null : (winner == a ? d : a));
         }
      }
   }

   private void finish(WarManager.War w, Clan winner, Clan loser) {
      this.wars.remove(w);
      Clan a = this.plugin.clans().get(w.attacker);
      Clan d = this.plugin.clans().get(w.defender);
      if (a != null && d != null) {
         this.plugin.clans().markWar(a, d);
         String score = w.attackerKills + " - " + w.defenderKills;
         this.plugin
            .bot()
            .staffLog(
               "⚔ Fin de guerre",
               "**" + a.name() + "** vs **" + d.name() + "** : " + score + (winner == null ? " (égalité)" : " — vainqueur **" + winner.name() + "**")
            );
         if (winner == null) {
            Bukkit.broadcast(
               Text.c(this.plugin.settings().prefix + "&7Fin de la guerre &f" + a.name() + " &7vs &f" + d.name() + " &7: &eégalité &7(" + score + ").")
            );
            this.plugin.discord().send("war", "\ud83e\udd1d Fin de la guerre **" + a.name() + "** vs **" + d.name() + "** : égalité (" + score + ").");
         } else {
            long loot = loser.bank() * this.plugin.settings().warLoot / 100L;
            loser.bank(loser.bank() - loot);
            winner.bank(winner.bank() + loot);
            this.plugin.clans().addXp(winner, this.plugin.settings().xpWarWin);
            this.plugin.clans().log(winner, "Victoire contre " + loser.name() + " (+" + loot + " " + this.plugin.settings().currencyName + ")");
            this.plugin.clans().log(loser, "Défaite contre " + winner.name() + " (-" + loot + " " + this.plugin.settings().currencyName + ")");
            Bukkit.broadcast(
               Text.c(
                  this.plugin.settings().prefix
                     + "&6&l✪ "
                     + winner.name()
                     + " &7remporte la guerre contre &f"
                     + loser.name()
                     + " &7("
                     + score
                     + ") et pille &e"
                     + loot
                     + " "
                     + this.plugin.settings().currencyName
                     + "&7 !"
               )
            );

            for (Player p : this.plugin.clans().onlineMembers(winner)) {
               p.showTitle(Title.title(Text.c("&6✪ VICTOIRE"), Text.c("&7+" + this.plugin.settings().xpWarWin + " XP de clan")));
            }

            for (Player p : this.plugin.clans().onlineMembers(loser)) {
               p.showTitle(Title.title(Text.c("&c☠ DÉFAITE"), Text.c("&7-" + loot + " " + this.plugin.settings().currencyName)));
            }

            this.plugin
               .discord()
               .send(
                  "war",
                  "\ud83c\udfc6 **"
                     + winner.name()
                     + "** remporte la guerre contre **"
                     + loser.name()
                     + "** ("
                     + score
                     + ") et pille "
                     + loot
                     + " "
                     + this.plugin.settings().currencyName
                     + "."
               );
            this.plugin.clans().dirty();
         }
      }
   }

   public void clanRemoved(Clan c) {
      this.wars.removeIf(w -> w.involves(c));
   }

   public void shutdown() {
      for (WarManager.War w : new ArrayList<>(this.wars)) {
         Clan a = this.plugin.clans().get(w.attacker);
         Clan d = this.plugin.clans().get(w.defender);
         if (a != null && d != null && w.active) {
            this.plugin.clans().markWar(a, d);
         }
      }

      this.wars.clear();
   }

   public String status(Clan c) {
      WarManager.War w = this.warOf(c);
      if (w == null) {
         return null;
      } else {
         Clan o = this.plugin.clans().get(w.opponent(c));
         long now = System.currentTimeMillis();
         String name = o == null ? "?" : o.name();
         return !w.active
            ? "&6Prépa vs " + name + " &f" + Text.formatTime((w.startAt - now) / 1000L)
            : "&c⚔ " + name + " &f" + Text.formatTime(Math.max(0L, w.endAt - now) / 1000L) + " &7(" + w.killsOf(c) + "-" + w.killsOf(o == null ? c : o) + ")";
      }
   }

   public static final class War {
      public final UUID attacker;
      public final UUID defender;
      public final long startAt;
      public final long endAt;
      public int attackerKills;
      public int defenderKills;
      public boolean active;

      War(UUID attacker, UUID defender, long startAt, long endAt) {
         this.attacker = attacker;
         this.defender = defender;
         this.startAt = startAt;
         this.endAt = endAt;
      }

      public boolean involves(Clan c) {
         return this.attacker.equals(c.id()) || this.defender.equals(c.id());
      }

      public UUID opponent(Clan c) {
         return this.attacker.equals(c.id()) ? this.defender : this.attacker;
      }

      public int killsOf(Clan c) {
         return this.attacker.equals(c.id()) ? this.attackerKills : this.defenderKills;
      }
   }
}
