package fr.windydev.clans.feature;

import fr.windydev.clans.WindyClans;
import fr.windydev.clans.model.Clan;
import fr.windydev.clans.util.Text;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Clans inactifs (règlement, art. 18.3) : quand aucun membre ne s'est connecté depuis "days" jours,
 * les territoires du clan sont libérés, après un avertissement publié sur Discord "warn-days-before" jours avant.
 */
public final class InactiveClans {
   private static final long DAY = 86_400_000L;
   private final WindyClans plugin;
   private final File file;
   private YamlConfiguration data = new YamlConfiguration();

   public InactiveClans(WindyClans plugin) {
      this.plugin = plugin;
      this.file = new File(plugin.getDataFolder(), "clans-inactifs.yml");
   }

   private boolean enabled() {
      return this.plugin.getConfig().getBoolean("inactive-clans.enabled", true);
   }

   private int days() {
      return Math.max(1, this.plugin.getConfig().getInt("inactive-clans.days", 30));
   }

   private int warnDays() {
      return Math.max(0, this.plugin.getConfig().getInt("inactive-clans.warn-days-before", 7));
   }

   private boolean exempt(Clan c) {
      for (String s : this.plugin.getConfig().getStringList("inactive-clans.exempt")) {
         if (s.equalsIgnoreCase(c.name()) || s.equalsIgnoreCase(c.tag())) {
            return true;
         }
      }

      return false;
   }

   public void start() {
      this.data = YamlConfiguration.loadConfiguration(this.file);
      Bukkit.getScheduler().runTaskTimer(this.plugin, this::check, 2400L, 72000L);
   }

   /** Dernière connexion d'un membre du clan (maintenant si l'un d'eux est en ligne). */
   public long lastSeen(Clan c) {
      long best = 0L;

      for (UUID u : c.members().keySet()) {
         OfflinePlayer op = Bukkit.getOfflinePlayer(u);
         long seen = op.isOnline() ? System.currentTimeMillis() : op.getLastSeen();
         best = Math.max(best, seen);
      }

      return best > 0L ? best : c.createdAt();
   }

   public void check() {
      if (this.enabled()) {
         long now = System.currentTimeMillis();
         boolean changed = false;
         SimpleDateFormat fmt = new SimpleDateFormat("EEEE d MMMM", Locale.FRENCH);

         for (Clan c : new ArrayList<>(this.plugin.clans().all())) {
            if (!c.claims().isEmpty() && !this.exempt(c)) {
               long seen = this.lastSeen(c);
               long idle = now - seen;
               String k = "clans." + c.id();
               if (idle >= this.days() * DAY) {
                  int n = c.claims().size();
                  this.plugin.clans().unclaimAll(c);
                  this.plugin.clans().log(c, "Territoires libérés : aucun membre connecté depuis " + this.days() + " jours");
                  this.data.set(k, null);
                  changed = true;
                  Bukkit.broadcast(
                     Text.c(
                        this.plugin.settings().prefix
                           + "&7Les territoires du clan "
                           + c.coloredName()
                           + " &7("
                           + n
                           + " chunks) sont libérés : aucun membre connecté depuis "
                           + this.days()
                           + " jours."
                     )
                  );
                  this.plugin
                     .discord()
                     .send(
                        "clan-inactive",
                        "🏚️ Les territoires du clan **"
                           + c.name()
                           + "** ("
                           + n
                           + " chunks) sont libérés : aucun membre ne s'est connecté depuis "
                           + this.days()
                           + " jours. Les constructions qui s'y trouvent ne sont plus protégées."
                     );
                  this.plugin.bot().staffLog("🏚 Clan inactif", c.name() + " : " + n + " chunks libérés (dernière connexion " + new SimpleDateFormat("dd/MM/yyyy").format(new Date(seen)) + ")");
               } else if (this.warnDays() > 0 && idle >= (this.days() - this.warnDays()) * DAY && this.data.getLong(k + ".warned-for", -1L) != seen) {
                  this.data.set(k + ".warned-for", seen);
                  changed = true;
                  String when = fmt.format(new Date(seen + this.days() * DAY));
                  this.plugin
                     .discord()
                     .send(
                        "clan-inactive",
                        "⚠️ Aucun membre du clan **"
                           + c.name()
                           + "** ne s'est connecté depuis "
                           + idle / DAY
                           + " jours. Sans connexion d'ici le **"
                           + when
                           + "**, ses territoires seront libérés."
                     );
                  this.plugin.bot().dmClan(c, "⚠️ Ton clan **" + c.name() + "** n'a plus eu de membre connecté depuis " + idle / DAY + " jours : ses territoires seront libérés le **" + when + "** si personne ne se connecte d'ici là.");
               }
            }
         }

         if (changed) {
            try {
               this.data.save(this.file);
            } catch (Exception var13) {
               this.plugin.getLogger().warning("clans-inactifs.yml : " + var13.getMessage());
            }
         }
      }
   }

   /** /clanadmin inactifs : état des clans. */
   public void report(CommandSender s) {
      long now = System.currentTimeMillis();
      List<String> lines = new ArrayList<>();

      for (Clan c : this.plugin.clans().all()) {
         long idle = (now - this.lastSeen(c)) / DAY;
         lines.add("&f" + c.name() + " &7: dernière connexion il y a &f" + idle + " j &8(" + c.claims().size() + " chunks)" + (this.exempt(c) ? " &bexempté" : ""));
      }

      this.plugin.msg(s, "&7Clans inactifs : libération après &f" + this.days() + " j&7, avertissement &f" + this.warnDays() + " j &7avant" + (this.enabled() ? "" : " &c(désactivé)"));
      lines.forEach(l -> s.sendMessage(Text.c("  " + l)));
   }
}
