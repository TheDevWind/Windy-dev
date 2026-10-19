package fr.windydev.clans.command;

import fr.windydev.clans.WindyClans;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class ChatCommands implements CommandExecutor {
   private final WindyClans plugin;

   public ChatCommands(WindyClans plugin) {
      this.plugin = plugin;
   }

   public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, @NotNull String[] args) {
      if (sender instanceof Player p) {
         boolean ally = cmd.getName().equalsIgnoreCase("ca");
         if (args.length == 0) {
            if (ally) {
               this.plugin.msg(p, "&c/ca <message>");
            } else {
               this.plugin.menus().actions.toggleChat(p);
            }

            return true;
         } else {
            this.plugin.menus().clanChat(p, String.join(" ", args), ally);
            return true;
         }
      } else {
         return true;
      }
   }
}
