package fr.windydev.clans.command;

import fr.windydev.clans.WindyClans;
import fr.windydev.clans.model.ChunkPos;
import fr.windydev.clans.model.Clan;
import fr.windydev.clans.profile.Profile;
import fr.windydev.clans.profile.ProfileManager;
import fr.windydev.clans.util.Text;
import java.util.List;
import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

public class AdminCommand implements CommandExecutor, TabCompleter {
   private static final List<String> SUBS = List.of(
      "aide",
      "reload",
      "bypass",
      "supprimer",
      "hologramme",
      "setlevel",
      "addxp",
      "setbanque",
      "unclaim",
      "sauvegarder",
      "objet",
      "boss",
      "profil",
      "setspawn",
      "vitesse",
      "magie",
      "faille",
      "hub",
      "roue",
      "evenement",
      "inactifs",
      "reglement"
   );
   private final WindyClans plugin;

   public AdminCommand(WindyClans plugin) {
      this.plugin = plugin;
   }

   public boolean onCommand(@NotNull CommandSender s, @NotNull Command cmd, @NotNull String label, @NotNull String[] args) {
      String sub = args.length == 0 ? "aide" : args[0].toLowerCase(Locale.ROOT);
      Clan target = args.length > 1 ? this.plugin.clans().byName(args[1]) : null;
      switch (sub) {
         case "profil":
         case "profile":
            if (args.length < 4 && (args.length != 3 || !args[2].equalsIgnoreCase("reset"))) {
               this.plugin.msg(s, "&c/clanadmin profil <joueur> <niveau|xp|race|rang|ames|interruptions|reset> <valeur|aucune>");
               return true;
            }

            OfflinePlayer op = Bukkit.getOfflinePlayerIfCached(args[1]);
            if (op == null) {
               this.plugin.msg(s, "&cJoueur inconnu.");
               return true;
            }

            ProfileManager pm = this.plugin.profiles();
            Profile pr = pm.of(op);
            Player online = op.getPlayer();
            String var35 = args[2].toLowerCase(Locale.ROOT);
            switch (var35) {
               case "niveau":
               case "level":
                  if (online != null) {
                     pm.setLevel(online, this.parse(args[3]));
                  } else {
                     pm.setLevel(pr, this.parse(args[3]));
                  }

                  this.plugin.msg(s, "&a" + pr.name() + " est niveau " + pr.level() + ".");
                  return true;
               case "xp":
                  if (online == null) {
                     this.plugin.msg(s, "&cLe joueur doit être connecté.");
                     return true;
                  }

                  pm.addXp(online, this.parse(args[3]), "combat");
                  this.plugin.msg(s, "&aXP ajoutée : niveau " + pr.level() + ".");
                  return true;
               case "race":
                  String id = args[3].toLowerCase(Locale.ROOT);
                  if (!id.equals("aucune") && !pm.races().containsKey(id)) {
                     this.plugin.msg(s, "&cRaces : " + String.join(", ", pm.races().keySet()) + ", aucune");
                     return true;
                  }

                  pm.setRace(pr, id.equals("aucune") ? null : id);
                  this.plugin.msg(s, "&aRace de " + pr.name() + " : " + (pr.race() == null ? "aucune" : pm.race(pr).colored()));
                  return true;
               case "rang":
               case "rank":
                  pm.setRank(pr, this.parse(args[3]));
                  this.plugin.msg(s, "&a" + pr.name() + " est maintenant " + pm.rankName(pr) + " &a(rang " + pr.rank() + ").");
                  return true;
               case "ames":
               case "âmes":
               case "souls":
                  pm.setSouls(pr, this.parse(args[3]));
                  this.plugin.msg(s, "&a" + pr.name() + " a " + pr.souls() + " âmes.");
                  return true;
               case "interruptions":
               case "interruption":
                  pm.setInterrupts(pr, pr.rank() + 1, this.parse(args[3]));
                  this.plugin.msg(s, "&a" + pr.name() + " : " + pr.interrupts(pr.rank() + 1) + " interruption(s) pour l'Éveil du rang " + (pr.rank() + 1) + ".");
                  return true;
               case "reset":
               case "competences":
                  pm.resetSkills(pr);
                  this.plugin.msg(s, "&aPoints de compétence de " + pr.name() + " rendus.");
                  return true;
               default:
                  this.plugin.msg(s, "&c/clanadmin profil <joueur> <niveau|xp|race|rang|ames|interruptions|reset> <valeur|aucune>");
                  return true;
            }
         case "reload":
            this.plugin.reload();
            this.plugin.msg(s, "&aConfiguration rechargée.");
            break;
         case "sauvegarder":
         case "save":
            this.plugin.clans().save();
            this.plugin.msg(s, "&aClans sauvegardés.");
            break;
         case "bypass":
            if (!(s instanceof Player p)) {
               return true;
            }

            if (this.plugin.bypass().remove(p.getUniqueId())) {
               this.plugin.msg(p, "&7Bypass &cdésactivé&7.");
            } else {
               this.plugin.bypass().add(p.getUniqueId());
               this.plugin.msg(p, "&7Bypass &aactivé &7: tu ignores les protections.");
            }
            break;
         case "supprimer":
         case "delete":
            if (target == null) {
               this.plugin.msg(s, "&cClan introuvable.");
               return true;
            }

            this.plugin.clans().broadcast(target, "&cTon clan a été supprimé par un administrateur.");
            this.plugin.clans().disband(target);
            this.plugin.msg(s, "&aClan " + target.name() + " supprimé.");
            break;
         case "setspawn":
         case "spawn":
            if (!(s instanceof Player p)) {
               this.plugin.msg(s, "&cCommande à faire en jeu, à l'endroit voulu.");
               return true;
            }

            this.plugin.spawns().set(p.getLocation());
            this.plugin.msg(p, "&aSpawn du serveur placé ici &7(nouveaux joueurs et réapparitions sans lit).");
            break;
         case "vitesse":
         case "debloquer":
            Player t = args.length > 1 ? Bukkit.getPlayerExact(args[1]) : (s instanceof Player p ? p : null);
            if (t == null) {
               this.plugin.msg(s, "&c/clanadmin vitesse <joueur connecté>");
               return true;
            }

            t.setWalkSpeed(0.2F);
            t.setFlySpeed(0.1F);
            this.plugin.msg(s, "&aVitesse de " + t.getName() + " remise par défaut.");
            break;
         case "hologramme":
         case "hologram":
            if (!(s instanceof Player p)) {
               return true;
            }

            this.plugin.hologram().setLocation(p.getLocation());
            this.plugin.msg(p, "&aHologramme du top clans placé ici.");
            break;
         case "setlevel":
            if (target == null || args.length < 3) {
               this.plugin.msg(s, "&c/clanadmin setlevel <clan> <niveau>");
               return true;
            }

            target.level(Math.max(1, Math.min(this.plugin.settings().maxLevel, this.parse(args[2]))));
            target.xp(0L);
            this.plugin.clans().dirty();
            this.plugin.msg(s, "&a" + target.name() + " est maintenant niveau " + target.level() + ".");
            break;
         case "addxp":
            if (target == null || args.length < 3) {
               this.plugin.msg(s, "&c/clanadmin addxp <clan> <xp>");
               return true;
            }

            this.plugin.clans().addXp(target, this.parse(args[2]));
            this.plugin.msg(s, "&aXP ajoutée. Niveau " + target.level() + ", " + target.xp() + " XP.");
            break;
         case "setbanque":
         case "setbank":
            if (target == null || args.length < 3) {
               this.plugin.msg(s, "&c/clanadmin setbanque <clan> <montant>");
               return true;
            }

            target.bank(Math.max(0, this.parse(args[2])));
            this.plugin.clans().dirty();
            this.plugin.msg(s, "&aBanque de " + target.name() + " : " + target.bank());
            break;
         case "boss":
            if (target == null) {
               this.plugin.msg(s, "&c/clanadmin boss <clan> [niveau|0=tous] &7- rend les boss disponibles");
               return true;
            }

            int lvl = args.length > 2 ? this.parse(args[2]) : 0;
            this.plugin.bosses().reset(target, lvl);
            this.plugin.msg(s, "&aBoss réinitialisé(s) pour " + target.name() + ".");
            break;
         case "objet":
         case "item":
            if (args.length < 2) {
               this.plugin.msg(s, "&7Objets : &f" + String.join(", ", this.plugin.items().all().stream().map(d -> d.id()).toList()));
               return true;
            }

            Player dest = args.length > 2 ? this.plugin.getServer().getPlayerExact(args[2]) : (s instanceof Player pl ? pl : null);
            ItemStack it = this.plugin.items().create(args[1]);
            if (it == null || dest == null) {
               this.plugin.msg(s, "&cObjet ou joueur introuvable.");
               return true;
            }

            int n = 1;

            try {
               if (args.length > 3) {
                  n = Math.max(1, Integer.parseInt(args[3]));
               }
            } catch (NumberFormatException var16) {
            }

            while (n > 0) {
               ItemStack one = it.clone();
               int take = Math.min(n, it.getMaxStackSize());
               one.setAmount(take);
               n -= take;
               dest.getInventory().addItem(new ItemStack[]{one}).values().forEach(r -> dest.getWorld().dropItemNaturally(dest.getLocation(), r));
            }

            this.plugin.msg(s, "&aObjet donné à " + dest.getName() + ".");
            break;
         case "faille":
            this.plugin.rift().admin(s, args);
            break;
         case "hub":
            this.plugin.hub().admin(s, args);
            break;
         case "roue":
            this.plugin.wheel().admin(s, args);
            break;
         case "evenement":
         case "event":
            this.plugin.weeklyEvent().admin(s, args);
            return true;
         case "inactifs":
            this.plugin.inactive().report(s);
            if (args.length > 1 && args[1].equalsIgnoreCase("verifier")) {
               this.plugin.inactive().check();
               this.plugin.msg(s, "&aVérification des clans inactifs faite.");
            }

            return true;
         case "reglement":
            this.plugin.rules().onCommand(s, cmd, "reglement", args.length > 1 ? java.util.Arrays.copyOfRange(args, 1, args.length) : new String[]{"recharger"});
            return true;
         case "magie":
         case "magic":
            this.plugin.magic().admin(s, args);
            break;
         case "unclaim":
            if (!(s instanceof Player p)) {
               return true;
            }

            ChunkPos var25 = ChunkPos.of(p.getLocation());
            Clan c = this.plugin.clans().at(var25);
            if (c == null) {
               this.plugin.msg(p, "&cCe chunk n'est pas claim.");
               return true;
            }

            this.plugin.clans().unclaim(c, var25);
            this.plugin.msg(p, "&aChunk retiré à " + c.name() + ".");
            break;
         default:
            for (String l : new String[]{
               "&b&lClans - admin",
               "&f/clanadmin reload &7- recharger config.yml",
               "&f/clanadmin bypass &7- ignorer les protections",
               "&f/clanadmin supprimer <clan>",
               "&f/clanadmin unclaim &7- retirer le chunk actuel",
               "&f/clanadmin hologramme &7- placer le top clans ici",
               "&f/clanadmin setlevel|addxp|setbanque <clan> <n>",
               "&f/clanadmin objet <id> [joueur] &7- donner un objet de rituel",
               "&f/clanadmin boss <clan> [niveau] &7- rendre un boss de nouveau disponible",
               "&f/clanadmin profil <joueur> <niveau|xp|race|rang|ames|interruptions|reset> <valeur> &7- profils des joueurs",
               "&f/clanadmin magie <joueur> <apprendre|oublier> <sort|tout> &7- sorts (mana, info, fragment aussi)",
               "&f/clanadmin faille <ouvrir|fermer|info> &7- Faille arcanique",
               "&f/clanadmin hub <installer|annuler|holo|pnj|suppr|liste> &7- place du spawn, hologrammes, PNJ",
               "&f/clanadmin roue <joueur> reset &7- rend le tour de roue du jour",
               "&f/clanadmin evenement <lancer [boss|tresor|faille]|arreter|info|lieu> &7- évènement de la semaine",
               "&f/clanadmin inactifs [verifier] &7- clans inactifs (libération des territoires)",
               "&f/clanadmin reglement <recharger|joueur <nom>> &7- règlement (acceptations, Discord)",
               "&f/quetes admin <joueur> ... &7- quêtes (finir, jour, semaine, serie, points, parcours-reset)",
               "&f/protection <retirer|rendre> <joueur> &7- protection des nouveaux",
               "&f/clanadmin sauvegarder"
            }) {
               s.sendMessage(Text.c(l));
            }
      }

      return true;
   }

   private int parse(String s) {
      try {
         return Integer.parseInt(s);
      } catch (NumberFormatException var3) {
         return 0;
      }
   }

   public List<String> onTabComplete(@NotNull CommandSender s, @NotNull Command c, @NotNull String l, @NotNull String[] args) {
      if (args.length == 1) {
         return ClanCommand.filter(SUBS, args[0]);
      } else if (args[0].equalsIgnoreCase("hub")) {
         return args.length == 2 ? ClanCommand.filter(List.of("installer", "annuler", "reconstruire", "holo", "pnj", "suppr", "liste", "recharger"), args[1])
            : (args.length == 4 && args[1].equalsIgnoreCase("pnj") ? ClanCommand.filter(fr.windydev.clans.hub.HubManager.ACTIONS, args[3]) : List.of());
      } else if (args[0].equalsIgnoreCase("evenement")) {
         return args.length == 2
            ? ClanCommand.filter(List.of("lancer", "arreter", "info", "lieu"), args[1])
            : (args.length == 3 && args[1].equalsIgnoreCase("lancer") ? ClanCommand.filter(List.of("boss", "tresor", "faille"), args[2]) : List.of());
      } else if (args[0].equalsIgnoreCase("inactifs")) {
         return args.length == 2 ? ClanCommand.filter(List.of("verifier"), args[1]) : List.of();
      } else if (args[0].equalsIgnoreCase("reglement")) {
         return args.length == 2 ? ClanCommand.filter(List.of("recharger", "joueur"), args[1]) : List.of();
      } else if (args[0].equalsIgnoreCase("faille")) {
         return args.length == 2 ? ClanCommand.filter(List.of("ouvrir", "fermer", "info"), args[1]) : List.of();
      } else if (args[0].equalsIgnoreCase("magie")) {
         if (args.length == 2) {
            return ClanCommand.filter(Bukkit.getOnlinePlayers().stream().map(p -> p.getName()).toList(), args[1]);
         } else if (args.length == 3) {
            return ClanCommand.filter(List.of("apprendre", "oublier", "mana", "info", "fragment"), args[2]);
         } else if (args.length == 4) {
            List<String> ids = new java.util.ArrayList<>(this.plugin.magic().spellIds());
            ids.add("tout");
            return ClanCommand.filter(ids, args[3]);
         } else {
            return List.of();
         }
      } else if (args.length == 2 && args[0].equalsIgnoreCase("objet")) {
         return ClanCommand.filter(this.plugin.items().all().stream().map(d -> d.id()).toList(), args[1]);
      } else {
         return args.length == 2 && List.of("supprimer", "setlevel", "addxp", "setbanque", "boss").contains(args[0].toLowerCase(Locale.ROOT))
            ? ClanCommand.filter(this.plugin.clans().names(), args[1])
            : List.of();
      }
   }
}
