package fr.windydev.clans.staff;

import fr.windydev.clans.WindyClans;
import fr.windydev.clans.util.Text;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Prise de service du staff : un modérateur ou un admin n'a ses pouvoirs que pendant son service.
 * /service lui ajoute son groupe LuckPerms, /service le lui retire. Tout ce qu'il tape pendant
 * le service est noté dans service-journal.log, montré en direct aux responsables et résumé sur Discord.
 */
public class ServiceManager implements Listener, CommandExecutor, TabCompleter {
   private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
   private static final String WATCH = "windyclans.service.watch";
   /** commandes jamais notées : elles contiennent un mot de passe */
   private static final List<String> SECRET = List.of("login", "l", "log", "register", "reg", "changepassword", "changepass", "cp", "unregister", "email");

   private final WindyClans plugin;
   private final Map<UUID, Session> sessions = new HashMap<>();
   private List<String> groups = List.of("admin", "moderateur");
   private boolean enabled = true;
   private boolean requireReason;
   private int maxMinutes = 120;
   private File journal;
   private File activeFile;

   static final class Session {
      final String name;
      final String group;
      final String reason;
      final long start = System.currentTimeMillis();
      final List<String> commands = new ArrayList<>();
      int count;

      Session(String name, String group, String reason) {
         this.name = name;
         this.group = group;
         this.reason = reason;
      }
   }

   public ServiceManager(WindyClans plugin) {
      this.plugin = plugin;
   }

   public void load() {
      File f = new File(this.plugin.getDataFolder(), "service.yml");
      if (!f.exists()) {
         this.plugin.saveResource("service.yml", false);
      }

      YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
      this.enabled = y.getBoolean("enabled", true);
      List<String> g = y.getStringList("groups");
      this.groups = g.isEmpty() ? List.of("admin", "moderateur") : g.stream().map(s -> s.toLowerCase(Locale.ROOT)).filter(s -> s.matches("[a-z0-9_-]+")).toList();
      this.requireReason = y.getBoolean("require-reason", false);
      this.maxMinutes = Math.max(0, y.getInt("max-minutes", 120));
      this.journal = new File(this.plugin.getDataFolder(), "service-journal.log");
      this.activeFile = new File(this.plugin.getDataFolder(), "service-actifs.yml");
   }

   public void start() {
      // après un arrêt brutal : personne ne garde ses pouvoirs
      Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
         YamlConfiguration y = YamlConfiguration.loadConfiguration(this.activeFile);
         for (String name : y.getKeys(false)) {
            String group = y.getString(name, "");
            if (this.luckPerms() && name.matches("[A-Za-z0-9_.*]{1,32}") && this.groups.contains(group)) {
               this.console("lp user " + name + " parent remove " + group);
               this.write(name + " (" + group + ") FIN service fermé au redémarrage du serveur");
            }
         }

         this.activeFile.delete();
      }, 40L);
      Bukkit.getScheduler().runTaskTimer(this.plugin, () -> {
         if (this.maxMinutes <= 0) {
            return;
         }

         for (UUID u : new ArrayList<>(this.sessions.keySet())) {
            Session s = this.sessions.get(u);
            Player p = Bukkit.getPlayer(u);
            if (p != null && System.currentTimeMillis() - s.start > this.maxMinutes * 60_000L) {
               p.sendMessage(Text.c("&c⚑ Ton service est terminé automatiquement après " + this.maxMinutes + " min. &7/service pour le reprendre."));
               this.end(p, "durée maximale atteinte");
            }
         }
      }, 1200L, 1200L);
   }

   public void shutdown() {
      for (UUID u : new ArrayList<>(this.sessions.keySet())) {
         Player p = Bukkit.getPlayer(u);
         if (p != null) {
            this.end(p, "arrêt du serveur");
         }
      }
   }

   private boolean luckPerms() {
      return Bukkit.getPluginManager().isPluginEnabled("LuckPerms");
   }

   private void console(String cmd) {
      Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
   }

   private void write(String line) {
      try {
         Files.writeString(this.journal.toPath(), "[" + LocalDateTime.now().format(TS) + "] " + line + System.lineSeparator(), StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
      } catch (IOException e) {
         this.plugin.getLogger().warning("service-journal.log : écriture impossible : " + e.getMessage());
      }
   }

   private void saveActive() {
      YamlConfiguration y = new YamlConfiguration();
      this.sessions.values().forEach(s -> y.set(s.name, s.group));

      try {
         y.save(this.activeFile);
      } catch (IOException ignored) {
      }
   }

   private void feed(Player about, String msg) {
      for (Player o : Bukkit.getOnlinePlayers()) {
         if (o != about && o.hasPermission(WATCH)) {
            o.sendMessage(Text.c(msg));
         }
      }
   }

   public boolean onDuty(UUID u) {
      return this.sessions.containsKey(u);
   }

   /** Groupe de service auquel ce joueur a droit (le plus élevé), ou null. */
   private String eligible(Player p) {
      for (String g : this.groups) {
         if (p.hasPermission("windyclans.service." + g)) {
            return g;
         }
      }

      return null;
   }

   // ------------------------------------------------------------------ début et fin

   private void begin(Player p, String group, String reason) {
      Session s = new Session(p.getName(), group, reason);
      this.sessions.put(p.getUniqueId(), s);
      this.saveActive();
      this.console("lp user " + p.getName() + " parent add " + group);
      this.write(p.getName() + " (" + group + ") DEBUT" + (reason.isEmpty() ? "" : " motif : " + reason));
      p.sendMessage(Text.c("&a⚑ Tu es en service &7(" + group + "). &7Tes commandes sont enregistrées. &8/service pour terminer."));
      this.feed(p, "&c⚑ &f" + p.getName() + " &7prend son service &8(" + group + ")" + (reason.isEmpty() ? "" : " &7: " + reason));
      this.plugin.bot().staffLog("⚑ Prise de service", p.getName() + " (" + group + ")" + (reason.isEmpty() ? "" : " — " + reason));
   }

   private void end(Player p, String why) {
      Session s = this.sessions.remove(p.getUniqueId());
      if (s == null) {
         return;
      }

      this.saveActive();
      this.console("lp user " + s.name + " parent remove " + s.group);
      long min = Math.max(1L, (System.currentTimeMillis() - s.start) / 60_000L);
      this.write(s.name + " (" + s.group + ") FIN " + min + " min, " + s.count + " commande(s)" + (why == null ? "" : ", " + why));
      p.sendMessage(Text.c("&7⚑ Fin de service : &f" + min + " min&7, &f" + s.count + " commande(s)&7. Tes pouvoirs sont retirés."));
      this.feed(p, "&7⚑ &f" + s.name + " &7termine son service &8(" + min + " min, " + s.count + " commande(s))");
      StringBuilder sb = new StringBuilder(s.name + " (" + s.group + ") — " + min + " min, " + s.count + " commande(s)" + (why == null ? "" : " — " + why));
      for (String c : s.commands) {
         sb.append("\n`").append(c.replace("`", "'")).append("`");
      }

      if (s.count > s.commands.size()) {
         sb.append("\n… et ").append(s.count - s.commands.size()).append(" autre(s), voir service-journal.log");
      }

      this.plugin.bot().staffLog("⚑ Fin de service", sb.toString());
   }

   // ------------------------------------------------------------------ évènements

   @EventHandler(priority = EventPriority.MONITOR)
   public void onCommand(PlayerCommandPreprocessEvent e) {
      Session s = this.sessions.get(e.getPlayer().getUniqueId());
      if (s == null) {
         return;
      }

      String msg = e.getMessage();
      String label = msg.substring(1).split(" ")[0].toLowerCase(Locale.ROOT);
      if (label.contains(":")) {
         label = label.substring(label.indexOf(':') + 1);
      }

      if (SECRET.contains(label) || label.equals("service") || label.equals("duty") || label.equals("staff")) {
         return;
      }

      s.count++;
      if (s.commands.size() < 20) {
         s.commands.add(msg.length() > 120 ? msg.substring(0, 120) + "…" : msg);
      }

      this.write(s.name + " (" + s.group + ") CMD " + msg + (e.isCancelled() ? " [refusée]" : ""));
      this.feed(e.getPlayer(), "&8[service] &7" + s.name + " &8» &f" + msg);
   }

   @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
   public void onGameMode(PlayerGameModeChangeEvent e) {
      Session s = this.sessions.get(e.getPlayer().getUniqueId());
      if (s != null) {
         this.write(s.name + " (" + s.group + ") MODE " + e.getNewGameMode().name().toLowerCase(Locale.ROOT));
      }
   }

   @EventHandler
   public void onQuit(PlayerQuitEvent e) {
      this.end(e.getPlayer(), "déconnexion");
   }

   @EventHandler
   public void onJoin(PlayerJoinEvent e) {
      Player p = e.getPlayer();
      if (this.enabled && !this.sessions.containsKey(p.getUniqueId())) {
         Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
            if (p.isOnline() && this.eligible(p) != null && !p.hasPermission("windyclans.admin")) {
               p.sendMessage(Text.c("&7⚑ Tu fais partie du staff : &f/service &7pour prendre ton service."));
            }
         }, 60L);
      }
   }

   // ------------------------------------------------------------------ commande

   public boolean onCommand(@NotNull CommandSender s, @NotNull Command cmd, @NotNull String label, @NotNull String[] args) {
      String a = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
      boolean boss = !(s instanceof Player) || s.hasPermission("windyclans.admin");
      switch (a) {
         case "liste", "list" -> {
            if (!boss && !s.hasPermission(WATCH)) {
               this.plugin.msg(s, "&cRéservé aux responsables.");
               return true;
            }

            if (this.sessions.isEmpty()) {
               this.plugin.msg(s, "&7Personne n'est en service.");
            }

            for (Session x : this.sessions.values()) {
               this.plugin.msg(s, "&c⚑ &f" + x.name + " &8(" + x.group + ") &7depuis " + Math.max(1L, (System.currentTimeMillis() - x.start) / 60_000L)
                  + " min, " + x.count + " commande(s)" + (x.reason.isEmpty() ? "" : " &8- " + x.reason));
            }

            return true;
         }
         case "journal", "log" -> {
            if (!boss && !s.hasPermission(WATCH)) {
               this.plugin.msg(s, "&cRéservé aux responsables.");
               return true;
            }

            String who = args.length > 1 && !args[1].matches("\\d+") ? args[1].toLowerCase(Locale.ROOT) : null;
            int n = 15;
            for (String x : args) {
               if (x.matches("\\d+")) {
                  n = Math.max(1, Math.min(50, Integer.parseInt(x)));
               }
            }

            try {
               List<String> lines = this.journal.exists() ? Files.readAllLines(this.journal.toPath(), StandardCharsets.UTF_8) : List.of();
               List<String> keep = new ArrayList<>();
               for (String l : lines) {
                  if (who == null || l.toLowerCase(Locale.ROOT).contains("] " + who + " (")) {
                     keep.add(l);
                  }
               }

               if (keep.isEmpty()) {
                  this.plugin.msg(s, "&7Journal vide.");
               }

               for (String l : keep.subList(Math.max(0, keep.size() - n), keep.size())) {
                  s.sendMessage(Text.c("&8" + l.replace("] ", "] &7").replace(" CMD ", " &f").replace(" DEBUT", " &aDÉBUT").replace(" FIN ", " &cFIN &7")));
               }
            } catch (IOException e) {
               this.plugin.msg(s, "&cLecture du journal impossible.");
            }

            return true;
         }
         case "ajouter", "retirer", "add", "remove" -> {
            if (!boss) {
               this.plugin.msg(s, "&cRéservé aux administrateurs.");
               return true;
            }

            if (args.length < 3 || !this.groups.contains(args[2].toLowerCase(Locale.ROOT)) || !args[1].matches("[A-Za-z0-9_.*]{1,32}")) {
               this.plugin.msg(s, "&c/service " + a + " <joueur> <" + String.join("|", this.groups) + ">");
               return true;
            }

            if (!this.luckPerms()) {
               this.plugin.msg(s, "&cLuckPerms n'est pas installé.");
               return true;
            }

            boolean add = a.startsWith("a");
            String g = args[2].toLowerCase(Locale.ROOT);
            this.console("lp user " + args[1] + " permission " + (add ? "set windyclans.service." + g + " true" : "unset windyclans.service." + g));
            if (!add) {
               Player t = Bukkit.getPlayerExact(args[1]);
               if (t != null) {
                  this.end(t, "retiré du staff");
               }
            }

            this.write(s.getName() + (add ? " AJOUTE " : " RETIRE ") + args[1] + " (" + g + ")");
            this.plugin.msg(s, add ? "&a" + args[1] + " peut maintenant prendre son service comme " + g + " &7(/service)."
               : "&a" + args[1] + " ne peut plus prendre de service " + g + ".");
            return true;
         }
         default -> {
         }
      }

      if (!(s instanceof Player p)) {
         this.plugin.msg(s, "&7/service liste &8| &7/service journal [joueur] [lignes] &8| &7/service ajouter|retirer <joueur> <groupe>");
         return true;
      }

      if (this.sessions.containsKey(p.getUniqueId())) {
         this.end(p, null);
         return true;
      }

      if (!this.enabled || !this.luckPerms()) {
         p.sendMessage(Text.c("&cLa prise de service n'est pas disponible."));
         return true;
      }

      String g = this.eligible(p);
      if (g == null) {
         p.sendMessage(Text.c("&cTu ne fais pas partie du staff."));
         return true;
      }

      String reason = String.join(" ", args).trim();
      if (this.requireReason && reason.isEmpty()) {
         p.sendMessage(Text.c("&cIndique un motif : &f/service <motif>"));
         return true;
      }

      this.begin(p, g, reason.length() > 100 ? reason.substring(0, 100) : reason);
      return true;
   }

   public List<String> onTabComplete(@NotNull CommandSender s, @NotNull Command c, @NotNull String l, @NotNull String[] args) {
      if (args.length == 1 && s.hasPermission(WATCH)) {
         return Arrays.asList("liste", "journal", "ajouter", "retirer");
      }

      return args.length == 3 && (args[0].startsWith("aj") || args[0].startsWith("re")) ? this.groups : List.of();
   }
}
