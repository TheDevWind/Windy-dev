package fr.windydev.clans.feature;

import com.sun.net.httpserver.HttpServer;
import fr.windydev.clans.WindyClans;
import fr.windydev.clans.util.Text;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackInfoLike;
import net.kyori.adventure.resource.ResourcePackRequest;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent.Status;

public class PackService implements Listener, CommandExecutor {
   private static final UUID PACK_ID = UUID.fromString("5b1d7a1e-3c2f-4e8a-9d6b-77a1c0de0001");
   private final WindyClans plugin;
   private final Set<UUID> loaded = new HashSet<>();
   private final Set<UUID> settled = new HashSet<>();
   /** Second pack, à part : l'habillage des écrans du jeu (coffres, fours, boutons, barre d'objets). */
   private static final UUID GAME_ID = UUID.fromString("5b1d7a1e-3c2f-4e8a-9d6b-77a1c0de0002");
   private static final String PACK_NAME = "windyclans-pack.zip";
   private static final String GAME_NAME = "windyclans-jeu.zip";
   private static final String CUSTOM_NAME = "windyclans-pack-perso.zip";
   /** Joueurs qui ont retiré l'habillage des écrans du jeu avec /ecrans. */
   private final Set<UUID> noGame = new HashSet<>();
   private File packFile;
   private String sha1;
   private File gameFile;
   private String gameSha1;
   private File prefsFile;
   private HttpServer http;

   public PackService(WindyClans plugin) {
      this.plugin = plugin;
   }

   public void start() {
      try {
         this.packFile = new File(this.plugin.getDataFolder(), PACK_NAME);
         this.plugin.getDataFolder().mkdirs();

         // MAJ 1.22.0 : un pack déposé à la main dans plugins/WindyClans/windyclans-pack-perso.zip remplace celui du plugin
         File custom = new File(this.plugin.getDataFolder(), CUSTOM_NAME);
         if (custom.isFile() && custom.length() > 0L) {
            Files.copy(custom.toPath(), this.packFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            this.plugin.getLogger().info("Pack de ressources : " + CUSTOM_NAME + " utilisé à la place du pack intégré.");
         } else {
            try (InputStream in = this.plugin.getResource(PACK_NAME)) {
               Files.copy(in, this.packFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
         }

         this.sha1 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(Files.readAllBytes(this.packFile.toPath())));
         this.gameFile = new File(this.plugin.getDataFolder(), GAME_NAME);

         try (InputStream in = this.plugin.getResource(GAME_NAME)) {
            Files.copy(in, this.gameFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
         }

         this.gameSha1 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(Files.readAllBytes(this.gameFile.toPath())));
      } catch (Exception var7) {
         this.plugin.getLogger().severe("Impossible de préparer le pack de ressources : " + var7.getMessage());
         return;
      }

      FileConfiguration c = this.plugin.getConfig();
      if (c.getBoolean("resource-pack.self-host.enabled", false)) {
         int port = c.getInt("resource-pack.self-host.port", 0);

         try {
            this.http = HttpServer.create(new InetSocketAddress(port), 0);
            this.serve("/" + PACK_NAME, this.packFile);
            this.serve("/" + GAME_NAME, this.gameFile);
            this.http.start();
            this.plugin.getLogger().info("Pack de ressources servi sur le port " + port + ".");
         } catch (Exception var5) {
            this.plugin.getLogger().warning("Hébergement du pack impossible sur le port " + port + " : " + var5.getMessage());
         }
      }

      String url = this.url();
      if (url == null) {
         this.plugin
            .getLogger()
            .info("Pas d'URL dans config.yml : le pack de server.properties sera utilisé s'il y en a un (sha1 attendu : " + this.sha1 + ").");
      } else {
         this.plugin.getLogger().info("Pack de ressources : " + url + " (sha1 " + this.sha1() + ")");
         String game = this.gameUrl();
         if (!this.gameEnabled()) {
            this.plugin.getLogger().info("Pack des écrans du jeu : désactivé dans config.yml.");
         } else if (game == null) {
            this.plugin
               .getLogger()
               .info(
                  "Pack des écrans du jeu : pas d'adresse (resource-pack.game-pack.url vide et pas d'hébergement intégré), il n'est pas envoyé. Fichier : plugins/WindyClans/"
                     + GAME_NAME
                     + " (sha1 "
                     + this.gameSha1
                     + ")."
               );
         } else {
            this.plugin.getLogger().info("Pack des écrans du jeu : " + game + " (sha1 " + this.gameSha1() + ")");
         }
      }

      this.prefsFile = new File(this.plugin.getDataFolder(), "ecrans.yml");
      this.noGame.clear();
      if (this.prefsFile.exists()) {
         for (String id : YamlConfiguration.loadConfiguration(this.prefsFile).getStringList("sans-habillage")) {
            try {
               this.noGame.add(UUID.fromString(id));
            } catch (IllegalArgumentException var6) {
            }
         }
      }
   }

   private void serve(String path, File file) {
      this.http.createContext(path, ex -> {
         byte[] data = Files.readAllBytes(file.toPath());
         ex.getResponseHeaders().add("Content-Type", "application/zip");
         ex.sendResponseHeaders(200, data.length);

         try (OutputStream os = ex.getResponseBody()) {
            os.write(data);
         }
      });
   }

   private boolean gameEnabled() {
      return this.plugin.getConfig().getBoolean("resource-pack.game-pack.enabled", true);
   }

   private String gameUrl() {
      FileConfiguration c = this.plugin.getConfig();
      String url = c.getString("resource-pack.game-pack.url", "");
      if (url != null && !url.isBlank()) {
         return url.trim();
      } else {
         if (this.http != null) {
            String host = c.getString("resource-pack.self-host.public-address", "");
            if (host != null && !host.isBlank()) {
               return "http://" + host.trim() + ":" + c.getInt("resource-pack.self-host.port") + "/" + GAME_NAME;
            }
         }

         return null;
      }
   }

   private String gameSha1() {
      String s = this.plugin.getConfig().getString("resource-pack.game-pack.sha1", "");
      return s != null && !s.isBlank() ? s.trim().toLowerCase() : this.gameSha1;
   }

   /** L'habillage des écrans du jeu est-il envoyé à ce joueur ? */
   public boolean gameSkin(Player p) {
      return this.gameEnabled() && this.gameUrl() != null && !this.noGame.contains(p.getUniqueId());
   }

   private void savePrefs() {
      YamlConfiguration y = new YamlConfiguration();
      List<String> ids = new ArrayList<>();
      this.noGame.forEach(u -> ids.add(u.toString()));
      y.set("sans-habillage", ids);

      try {
         y.save(this.prefsFile);
      } catch (Exception var4) {
         this.plugin.getLogger().warning("Impossible d'enregistrer ecrans.yml : " + var4.getMessage());
      }
   }

   public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
      if (!(sender instanceof Player p)) {
         sender.sendMessage("Commande réservée aux joueurs.");
         return true;
      } else {
         String prefix = this.plugin.settings().prefix;
         if (this.bedrock(p)) {
            p.sendMessage(Text.c(prefix + "&7Cette commande concerne l'édition Java : sur Bedrock, les écrans du jeu ne changent pas."));
            return true;
         } else if (!this.gameEnabled() || this.gameUrl() == null) {
            p.sendMessage(Text.c(prefix + "&7L'habillage des écrans du jeu n'est pas proposé sur ce serveur."));
            return true;
         } else {
            boolean off = this.noGame.add(p.getUniqueId());
            if (!off) {
               this.noGame.remove(p.getUniqueId());
            }

            this.savePrefs();
            p.sendMessage(
               Text.c(
                  prefix
                     + (
                        off
                           ? "&7Habillage des écrans du jeu &cretiré&7 : coffres, fours et boutons reprennent leur apparence d'origine. Les menus du serveur ne changent pas."
                           : "&7Habillage des écrans du jeu &aactivé&7."
                     )
               )
            );
            p.sendMessage(Text.c("&8Le jeu recharge ses ressources, patiente quelques secondes. &7/ecrans &8pour changer d'avis."));
            this.send(p);
            return true;
         }
      }
   }

   public void stop() {
      if (this.http != null) {
         this.http.stop(0);
      }
   }

   private String url() {
      FileConfiguration c = this.plugin.getConfig();
      String url = c.getString("resource-pack.url", "");
      if (url != null && !url.isBlank()) {
         return url.trim();
      } else {
         if (this.http != null) {
            String host = c.getString("resource-pack.self-host.public-address", "");
            if (host != null && !host.isBlank()) {
               return "http://" + host.trim() + ":" + c.getInt("resource-pack.self-host.port") + "/" + PACK_NAME;
            }
         }

         return null;
      }
   }

   private String sha1() {
      String s = this.plugin.getConfig().getString("resource-pack.sha1", "");
      return s != null && !s.isBlank() ? s.trim().toLowerCase() : this.sha1;
   }

   public File file() {
      return this.packFile;
   }

   public String embeddedSha1() {
      return this.sha1;
   }

   public boolean has(Player p) {
      return this.loaded.contains(p.getUniqueId());
   }

   /**
    * Joueur Bedrock (Geyser / Floodgate) ? Un pack Java ne s'affiche pas sur Bedrock, mais Geyser répond
    * « chargé » à sa place quand le pack est obligatoire : on ne lui envoie donc rien et il garde les menus classiques.
    */
   public boolean bedrock(Player p) {
      UUID id = p.getUniqueId();

      try {
         Object api = Class.forName("org.geysermc.geyser.api.GeyserApi").getMethod("api").invoke(null);
         if (api != null && (Boolean)api.getClass().getMethod("isBedrockPlayer", UUID.class).invoke(api, id)) {
            return true;
         }
      } catch (Throwable var4) {
      }

      // identifiant Floodgate (moitié haute à zéro) : couvre aussi un Geyser placé sur un proxy
      return id.getMostSignificantBits() == 0L;
   }

   public boolean settled(Player p) {
      return this.settled.contains(p.getUniqueId());
   }

   public void send(Player p) {
      String url = this.url();
      if (this.bedrock(p)) {
         this.loaded.remove(p.getUniqueId());
         this.settled.add(p.getUniqueId());
      } else if (url != null) {
         boolean required = this.plugin.getConfig().getBoolean("resource-pack.required", true);

         try {
            ResourcePackInfo info = ResourcePackInfo.resourcePackInfo(PACK_ID, URI.create(url), this.sha1());
            // une seule demande pour les deux packs : le joueur ne voit qu'une invite
            ResourcePackInfoLike[] more = this.gameSkin(p)
               ? new ResourcePackInfoLike[]{ResourcePackInfo.resourcePackInfo(GAME_ID, URI.create(this.gameUrl()), this.gameSha1())}
               : new ResourcePackInfoLike[0];
            p.sendResourcePacks(
               ResourcePackRequest.resourcePackRequest()
                  .packs(info, more)
                  .required(required)
                  .replace(true)
                  .prompt(
                     Text.c(this.plugin.getConfig().getString("resource-pack.prompt", "&bInterface des clans &7- accepte le pack pour les menus et le HUD."))
                  )
            );
         } catch (Exception var5) {
            this.plugin.getLogger().warning("Envoi du pack impossible : " + var5.getMessage());
         }
      }
   }

   @EventHandler
   public void onJoin(PlayerJoinEvent e) {
      Player p = e.getPlayer();
      Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
         if (p.isOnline()) {
            this.send(p);
            this.plugin.hud().refresh(p);
         }
      }, 10L);
   }

   @EventHandler
   public void onQuit(PlayerQuitEvent e) {
      this.loaded.remove(e.getPlayer().getUniqueId());
      this.settled.remove(e.getPlayer().getUniqueId());
   }

   private void refreshLater(Player p) {
      UUID id = p.getUniqueId();
      Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
         Player online = Bukkit.getPlayer(id);
         if (online != null && online.isOnline()) {
            this.plugin.hud().refresh(online);
         }
      }, 5L);
   }

   @EventHandler
   public void onStatus(PlayerResourcePackStatusEvent e) {
      Player p = e.getPlayer();
      if (this.bedrock(p)) {
         return;
      }

      if (GAME_ID.equals(e.getID())) {
         // le pack des écrans du jeu est un habillage : s'il manque, rien ne casse côté serveur
         switch (e.getStatus()) {
            case FAILED_DOWNLOAD:
            case INVALID_URL:
            case FAILED_RELOAD:
               this.plugin.getLogger().warning("Pack des écrans du jeu non chargé pour " + p.getName() + " : " + e.getStatus());
            default:
               return;
         }
      }

      switch (e.getStatus()) {
         case SUCCESSFULLY_LOADED:
            this.loaded.add(p.getUniqueId());
            this.settled.add(p.getUniqueId());
            this.refreshLater(p);
            break;
         case DECLINED:
         case FAILED_DOWNLOAD:
         case INVALID_URL:
         case FAILED_RELOAD:
         case DISCARDED:
            this.loaded.remove(p.getUniqueId());
            this.settled.add(p.getUniqueId());
            this.refreshLater(p);
            if (e.getStatus() != Status.DECLINED) {
               this.plugin.getLogger().warning("Pack non chargé pour " + p.getName() + " : " + e.getStatus());
            }
      }
   }
}
