package fr.windydev.clans.command;

import fr.windydev.clans.WindyClans;
import fr.windydev.clans.data.ClanManager;
import fr.windydev.clans.model.Clan;
import fr.windydev.clans.model.Relation;
import fr.windydev.clans.util.Text;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class ClanCommand implements CommandExecutor, TabCompleter {
   private static final List<String> SUBS = List.of(
      "aide",
      "menu",
      "creer",
      "dissoudre",
      "inviter",
      "accepter",
      "refuser",
      "quitter",
      "exclure",
      "promouvoir",
      "retrograder",
      "chef",
      "claim",
      "unclaim",
      "unclaimall",
      "capitale",
      "sethome",
      "home",
      "carte",
      "bordures",
      "info",
      "liste",
      "top",
      "banque",
      "coffre",
      "allie",
      "neutre",
      "ennemi",
      "guerre",
      "couleur",
      "description",
      "chat",
      "sb",
      "journal"
   );
   private final WindyClans plugin;

   public ClanCommand(WindyClans plugin) {
      this.plugin = plugin;
   }

   private Actions a() {
      return this.plugin.menus().actions;
   }

   public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, @NotNull String[] args) {
      if (!(sender instanceof Player p)) {
         sender.sendMessage("Commande réservée aux joueurs. Utilise /clanadmin.");
         return true;
      } else if (args.length == 0) {
         this.plugin.menus().main(p);
         return true;
      } else {
         String sub = args[0].toLowerCase(Locale.ROOT);
         String arg1 = args.length > 1 ? args[1] : null;
         if (!this.plugin.getConfig().getBoolean("player-commands", false)
            && !p.hasPermission("windyclans.admin")
            && !List.of("accepter", "accept", "refuser", "deny", "menu", "boss").contains(sub)) {
            this.plugin.msg(p, "&7Tout se fait dans le menu : &bobjet Menu &7(dernière case de ta barre).");
            this.plugin.menus().main(p);
            return true;
         } else {
            switch (sub) {
               case "aide":
               case "help":
               case "?":
                  this.help(p);
                  break;
               case "menu":
               case "gui":
                  this.plugin.menus().main(p);
                  break;
               case "creer":
               case "create":
                  if (args.length < 3) {
                     this.plugin.msg(p, "&c/clan creer <nom> <TAG>");
                     return true;
                  }

                  this.a().create(p, args[1], args[2]);
                  break;
               case "dissoudre":
               case "disband":
                  this.a().disband(p);
                  break;
               case "inviter":
               case "invite":
                  if (arg1 == null) {
                     this.plugin.menus().invite(p, 0);
                     return true;
                  }

                  this.a().invite(p, Bukkit.getPlayerExact(arg1));
                  break;
               case "accepter":
               case "accept":
               case "rejoindre":
                  this.a().accept(p, arg1);
                  break;
               case "refuser":
               case "deny":
                  if (arg1 != null) {
                     this.a().deny(p, arg1);
                  }
                  break;
               case "quitter":
               case "leave":
                  this.a().leave(p);
                  break;
               case "exclure":
               case "kick":
                  this.withMember(p, arg1, u -> this.a().kick(p, u));
                  break;
               case "promouvoir":
               case "promote":
                  this.withMember(p, arg1, u -> this.a().promote(p, u));
                  break;
               case "retrograder":
               case "demote":
                  this.withMember(p, arg1, u -> this.a().demote(p, u));
                  break;
               case "chef":
               case "leader":
                  this.withMember(p, arg1, u -> this.a().transfer(p, u));
                  break;
               case "claim":
                  this.a().claim(p);
                  break;
               case "unclaim":
                  this.a().unclaim(p);
                  break;
               case "unclaimall":
                  this.a().unclaimAll(p);
                  break;
               case "capitale":
               case "capital":
                  this.a().moveCapital(p);
                  break;
               case "sethome":
                  this.a().setHome(p);
                  break;
               case "home":
               case "capitale-tp":
                  this.a().home(p);
                  break;
               case "carte":
               case "map":
                  this.plugin.menus().map(p);
                  break;
               case "bordures":
               case "borders":
                  this.a().toggleBorders(p);
                  break;
               case "info":
               case "i":
                  this.info(p, arg1);
                  break;
               case "liste":
               case "list":
                  this.list(p);
                  break;
               case "top":
                  this.plugin.menus().top(p);
                  break;
               case "banque":
               case "bank":
                  this.bank(p, args);
                  break;
               case "coffre":
               case "chest":
                  this.a().openChest(p);
                  break;
               case "allie":
               case "ally":
                  this.a().ally(p, this.plugin.clans().byName(arg1));
                  break;
               case "neutre":
               case "neutral":
                  this.a().neutral(p, this.plugin.clans().byName(arg1));
                  break;
               case "ennemi":
               case "enemy":
                  this.a().enemy(p, this.plugin.clans().byName(arg1));
                  break;
               case "guerre":
               case "war":
                  this.war(p, args);
                  break;
               case "couleur":
               case "color":
                  if (arg1 == null) {
                     this.plugin.menus().settings(p);
                     return true;
                  }

                  this.a().color(p, arg1);
                  break;
               case "description":
               case "desc":
                  if (args.length < 2) {
                     this.plugin.msg(p, "&c/clan description <texte>");
                     return true;
                  }

                  this.a().description(p, String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
                  break;
               case "chat":
                  this.a().toggleChat(p);
                  break;
               case "sb":
               case "scoreboard":
                  this.a().toggleScoreboard(p);
                  break;
               case "journal":
               case "log":
                  this.plugin.menus().log(p);
                  break;
               case "boss":
                  if (arg1 != null && arg1.equalsIgnoreCase("rejoindre")) {
                     this.plugin.bosses().join(p);
                  } else {
                     this.plugin.menus().bosses(p);
                  }
                  break;
               default:
                  this.plugin.msg(p, "&cSous-commande inconnue. &7/clan aide");
            }

            return true;
         }
      }
   }

   private void withMember(Player p, String name, Consumer<UUID> action) {
      Clan c = this.a().requireClan(p);
      if (c != null) {
         if (name == null) {
            this.plugin.menus().members(p, 0);
         } else {
            for (UUID u : c.members().keySet()) {
               if (this.plugin.clans().nameOf(u).equalsIgnoreCase(name)) {
                  action.accept(u);
                  return;
               }
            }

            this.plugin.msg(p, "&c" + name + " n'est pas dans ton clan.");
         }
      }
   }

   private void bank(Player p, String[] args) {
      if (args.length < 2) {
         this.plugin.menus().bank(p);
      } else {
         String op = args[1].toLowerCase(Locale.ROOT);
         int amount = 0;
         if (args.length >= 3 && !args[2].equalsIgnoreCase("tout")) {
            try {
               amount = Integer.parseInt(args[2]);
            } catch (NumberFormatException var7) {
               this.plugin.msg(p, "&cNombre invalide.");
               return;
            }
         }

         switch (op) {
            case "deposer":
            case "deposit":
            case "d":
               this.a().deposit(p, amount);
               break;
            case "retirer":
            case "withdraw":
            case "r":
               this.a().withdraw(p, amount);
               break;
            default:
               this.plugin.msg(p, "&c/clan banque <deposer|retirer> <nombre|tout>");
         }
      }
   }

   private void war(Player p, String[] args) {
      if (args.length < 2) {
         this.plugin.menus().war(p);
      } else {
         String var3 = args[1].toLowerCase(Locale.ROOT);
         switch (var3) {
            case "declarer":
            case "declare":
               if (args.length < 3) {
                  this.plugin.msg(p, "&c/clan guerre declarer <clan> <raison>");
                  return;
               }

               this.a().declareWar(p, this.plugin.clans().byName(args[2]), args.length > 3 ? String.join(" ", Arrays.copyOfRange(args, 3, args.length)) : null);
               break;
            case "capituler":
            case "surrender":
               this.a().surrender(p);
               break;
            default:
               this.plugin.menus().war(p);
         }
      }
   }

   private void info(Player p, String name) {
      Clan c = name == null ? this.plugin.clans().of(p) : this.plugin.clans().byName(name);
      if (c == null) {
         if (name == null) {
            c = this.plugin.clans().at(p.getLocation());
         }

         if (c == null) {
            this.plugin.msg(p, "&cClan introuvable.");
            return;
         }
      }

      ClanManager cm = this.plugin.clans();
      Relation rel = cm.relation(p, c);
      p.sendMessage(Text.c("&8&m                                        "));
      p.sendMessage(Text.c(" " + c.coloredTag() + " &f&l" + c.name() + " &8(" + rel.color() + rel.label() + "&8)"));
      p.sendMessage(Text.c(" &8" + c.description()));
      p.sendMessage(
         Text.c(" &7Chef : &f" + (c.leader() == null ? "?" : cm.nameOf(c.leader())) + " &8• &7Niveau &e" + c.level() + " &8• &7Claims &f" + c.claims().size())
      );
      String members = c.members()
         .entrySet()
         .stream()
         .sorted(Comparator.comparing(e -> -e.getValue().ordinal()))
         .map(e -> (Bukkit.getPlayer(e.getKey()) != null ? "&a" : "&7") + cm.nameOf(e.getKey()))
         .collect(Collectors.joining("&8, "));
      p.sendMessage(Text.c(" &7Membres (" + c.members().size() + ") : " + members));
      String allies = c.allies().stream().map(cm::get).filter(Objects::nonNull).map(Clan::name).collect(Collectors.joining(", "));
      if (!allies.isEmpty()) {
         p.sendMessage(Text.c(" &7Alliés : &b" + allies));
      }

      p.sendMessage(Text.c("&8&m                                        "));
   }

   private void list(Player p) {
      this.plugin.msg(p, "&7Clans (" + this.plugin.clans().all().size() + ") :");

      for (Clan c : this.plugin.clans().top()) {
         p.sendMessage(
            Text.c(
               " "
                  + c.coloredTag()
                  + " &f"
                  + c.name()
                  + " &8- &7niv. &e"
                  + c.level()
                  + " &8- &7"
                  + this.plugin.clans().onlineCount(c)
                  + "/"
                  + c.members().size()
                  + " en ligne"
            )
         );
      }
   }

   private void help(Player p) {
      String[] lines = new String[]{
         "&8&m                    &r &b&lClans &8&m                    ",
         "&f/clan &7- ouvrir le menu",
         "&f/clan creer <nom> <TAG> &7- fonder un clan",
         "&f/clan inviter <joueur> &7| &faccepter &7| &fquitter",
         "&f/clan claim &7| &funclaim &7| &fcarte &7| &fbordures",
         "&f/clan home &7| &fsethome &7| &fcapitale &7(déplacer)",
         "&f/clan promouvoir|retrograder|exclure|chef <joueur>",
         "&f/clan banque deposer|retirer <n|tout> &7| &fcoffre",
         "&f/clan allie|neutre|ennemi <clan>",
         "&f/clan guerre declarer <clan> <raison> &7| &fguerre capituler",
         "&f/clan info [clan] &7| &fliste &7| &ftop &7| &fjournal",
         "&f/clan couleur <couleur> &7| &fdescription <texte>",
         "&f/cc <msg> &7chat clan &8• &f/ca <msg> &7chat alliance &8• &f/clan chat",
         "&f/clan sb &7- afficher/masquer le panneau d'infos"
      };

      for (String l : lines) {
         p.sendMessage(Text.c(l));
      }
   }

   public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, @NotNull String[] args) {
      if (!this.plugin.getConfig().getBoolean("player-commands", false) && !sender.hasPermission("windyclans.admin")) {
         return List.of();
      } else if (args.length == 1) {
         return filter(SUBS, args[0]);
      } else {
         String sub = args[0].toLowerCase(Locale.ROOT);
         if (args.length == 2) {
            switch (sub) {
               case "inviter":
               case "invite":
                  return filter(
                     Bukkit.getOnlinePlayers()
                        .stream()
                        .filter(o -> this.plugin.clans().of(o) == null)
                        .<String>map(Player::getName)
                        .collect(Collectors.toList()),
                     args[1]
                  );
               case "exclure":
               case "promouvoir":
               case "retrograder":
               case "chef":
               case "kick":
               case "promote":
               case "demote":
                  if (sender instanceof Player p && this.plugin.clans().of(p) != null) {
                     return filter(this.plugin.clans().of(p).members().keySet().stream().map(this.plugin.clans()::nameOf).collect(Collectors.toList()), args[1]);
                  }

                  return List.of();
               case "accepter":
               case "refuser":
                  if (sender instanceof Player p) {
                     return filter(this.plugin.clans().invitesOf(p.getUniqueId()).stream().map(Clan::name).collect(Collectors.toList()), args[1]);
                  }

                  return List.of();
               case "allie":
               case "neutre":
               case "ennemi":
               case "info":
                  return filter(this.plugin.clans().names(), args[1]);
               case "banque":
                  return filter(List.of("deposer", "retirer"), args[1]);
               case "guerre":
                  return filter(List.of("declarer", "capituler", "info"), args[1]);
               case "couleur":
                  return filter(new ArrayList<>(Actions.COLORS.keySet()), args[1]);
               default:
                  return List.of();
            }
         } else if (args.length == 3 && sub.equals("guerre") && args[1].equalsIgnoreCase("declarer")) {
            return filter(this.plugin.clans().names(), args[2]);
         } else {
            return args.length == 3 && sub.equals("banque") ? filter(List.of("1", "10", "64", "tout"), args[2]) : List.of();
         }
      }
   }

   static List<String> filter(List<String> options, String start) {
      String s = start.toLowerCase(Locale.ROOT);
      return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(s)).sorted().collect(Collectors.toList());
   }
}
