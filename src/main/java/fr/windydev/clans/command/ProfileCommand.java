package fr.windydev.clans.command;

import fr.windydev.clans.WindyClans;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class ProfileCommand implements CommandExecutor, TabCompleter {
   private final WindyClans plugin;

   public ProfileCommand(WindyClans plugin) {
      this.plugin = plugin;
   }

   public boolean onCommand(@NotNull CommandSender s, @NotNull Command cmd, @NotNull String label, @NotNull String[] args) {
      if (s instanceof Player p) {
         if (cmd.getName().equals("races")) {
            this.plugin.menus().races(p);
            return true;
         } else if (cmd.getName().equals("competences")) {
            this.plugin.menus().skills(p);
            return true;
         } else if (cmd.getName().equals("eveil")) {
            this.plugin.menus().evolution(p);
            return true;
         } else if (args.length > 0) {
            OfflinePlayer t = Bukkit.getOfflinePlayerIfCached(args[0]);
            if (t != null && this.plugin.profiles().get(t.getUniqueId()) != null) {
               this.plugin.menus().profile(p, t);
               return true;
            } else {
               this.plugin.msg(p, "&cJoueur inconnu.");
               return true;
            }
         } else {
            this.plugin.menus().profile(p);
            return true;
         }
      } else {
         s.sendMessage("Commande réservée aux joueurs.");
         return true;
      }
   }

   public List<String> onTabComplete(@NotNull CommandSender s, @NotNull Command cmd, @NotNull String alias, @NotNull String[] args) {
      return cmd.getName().equals("profil") && args.length == 1
         ? Bukkit.getOnlinePlayers().stream().<String>map(Player::getName).filter(n -> n.toLowerCase().startsWith(args[0].toLowerCase())).toList()
         : List.of();
   }
}
