package fr.windydev.clans.quest;

import fr.windydev.clans.WindyClans;
import fr.windydev.clans.listener.ProtectionListener;
import fr.windydev.clans.util.Text;
import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Protection des nouveaux joueurs (règlement, art. 25) : pendant ses premières minutes de jeu,
 * un nouveau ne peut ni infliger ni subir de dégâts d'un autre joueur. Elle prend fin toute seule.
 */
public final class NewbieShield implements Listener, CommandExecutor, TabCompleter {
   private final WindyClans plugin;
   private final File file;
   private final Set<UUID> removed = new HashSet<>();
   private final Set<UUID> shielded = new HashSet<>();
   private int minutes = 120;

   public NewbieShield(WindyClans plugin) {
      this.plugin = plugin;
      this.file = new File(plugin.getDataFolder(), "protection-nouveaux.yml");
   }

   public void load() {
      this.minutes = Math.max(0, this.plugin.getConfig().getInt("pvp.newbie-protection-minutes", 120));
      this.removed.clear();
      for (String s : YamlConfiguration.loadConfiguration(this.file).getStringList("removed")) {
         try {
            this.removed.add(UUID.fromString(s));
         } catch (IllegalArgumentException var4) {
         }
      }
   }

   private void save() {
      YamlConfiguration y = new YamlConfiguration();
      y.set("removed", this.removed.stream().map(UUID::toString).toList());

      try {
         y.save(this.file);
      } catch (Exception var3) {
         this.plugin.getLogger().warning("protection-nouveaux.yml : " + var3.getMessage());
      }
   }

   public void start() {
      Bukkit.getScheduler().runTaskTimer(this.plugin, () -> {
         for (Player p : Bukkit.getOnlinePlayers()) {
            boolean now = this.isProtected(p);
            if (!now && this.shielded.remove(p.getUniqueId())) {
               this.plugin.msg(p, "&e⛨ Ta protection de nouveau joueur est terminée : &7tu peux désormais attaquer et être attaqué hors des zones protégées.");
            } else if (now) {
               this.shielded.add(p.getUniqueId());
            }
         }
      }, 200L, 600L);
   }

   /** Minutes de protection restantes (0 = pas protégé). */
   public long remaining(OfflinePlayer p) {
      if (this.minutes > 0 && !this.removed.contains(p.getUniqueId())) {
         return Math.max(0L, this.minutes - this.plugin.profiles().of(p).playMinutes());
      } else {
         return 0L;
      }
   }

   public boolean isProtected(Player p) {
      return this.remaining(p) > 0L;
   }

   static String fmt(long min) {
      return min >= 60L ? min / 60L + " h " + String.format("%02d", min % 60L) : min + " min";
   }

   @EventHandler(
      priority = EventPriority.LOWEST,
      ignoreCancelled = true
   )
   public void onHit(EntityDamageByEntityEvent e) {
      if (e.getEntity() instanceof Player victim) {
         Player att = ProtectionListener.attacker(e.getDamager());
         if (att != null && att != victim && !this.plugin.bypass().contains(att.getUniqueId())) {
            long v = this.remaining(victim);
            if (v > 0L) {
               e.setCancelled(true);
               att.sendActionBar(Text.c("&a⛨ " + victim.getName() + " &7est un nouveau joueur protégé &8(encore " + fmt(v) + ")"));
            } else {
               long a = this.remaining(att);
               if (a > 0L) {
                  e.setCancelled(true);
                  att.sendActionBar(Text.c("&a⛨ Tu es protégé encore " + fmt(a) + " &7: tu ne peux pas attaquer de joueur."));
               }
            }
         }
      }
   }

   @EventHandler
   public void onJoin(PlayerJoinEvent e) {
      Player p = e.getPlayer();
      Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
         if (p.isOnline()) {
            long r = this.remaining(p);
            if (r > 0L) {
               this.shielded.add(p.getUniqueId());
               this.plugin
                  .msg(p, "&a⛨ Protection des nouveaux &7: encore &f" + fmt(r) + " &7de jeu sans PvP (tu ne peux ni attaquer ni être attaqué par un joueur).");
            }
         }
      }, 80L);
   }

   @Override
   public boolean onCommand(@NotNull CommandSender s, @NotNull Command cmd, @NotNull String label, @NotNull String[] args) {
      String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
      if ((sub.equals("retirer") || sub.equals("rendre")) && s.hasPermission("windyclans.admin") && args.length > 1) {
         OfflinePlayer t = Bukkit.getOfflinePlayerIfCached(args[1]);
         if (t == null) {
            this.plugin.msg(s, "&cJoueur inconnu.");
         } else {
            if (sub.equals("retirer")) {
               this.removed.add(t.getUniqueId());
            } else {
               this.removed.remove(t.getUniqueId());
            }

            this.save();
            this.plugin.msg(s, "&7Protection de &f" + t.getName() + " &7: " + (this.remaining(t) > 0L ? "&aactive (" + fmt(this.remaining(t)) + ")" : "&cinactive"));
         }

         return true;
      } else {
         OfflinePlayer t = args.length > 0 && !sub.isEmpty() ? Bukkit.getOfflinePlayerIfCached(args[0]) : (s instanceof Player p ? p : null);
         if (t == null) {
            this.plugin.msg(s, "&c/protection [joueur]");
         } else {
            long r = this.remaining(t);
            boolean self = s instanceof Player p && p.getUniqueId().equals(t.getUniqueId());
            if (r > 0L) {
               this.plugin.msg(s, "&a⛨ " + (self ? "Tu es protégé" : t.getName() + " est protégé") + " &7encore &f" + fmt(r) + " &7de jeu.");
            } else {
               this.plugin.msg(s, "&7" + (self ? "Tu n'as" : t.getName() + " n'a") + " plus de protection de nouveau joueur.");
            }
         }

         return true;
      }
   }

   @Override
   public List<String> onTabComplete(@NotNull CommandSender s, @NotNull Command c, @NotNull String l, @NotNull String[] args) {
      if (args.length == 1) {
         List<String> names = new java.util.ArrayList<>(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
         if (s.hasPermission("windyclans.admin")) {
            names.add("retirer");
            names.add("rendre");
         }

         return QuestManager.filter(names, args[0]);
      } else {
         return args.length == 2 && s.hasPermission("windyclans.admin")
            ? QuestManager.filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[1])
            : List.of();
      }
   }
}
