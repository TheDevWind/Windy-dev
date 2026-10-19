package fr.windydev.clans.discord;

import fr.windydev.clans.WindyClans;
import fr.windydev.clans.model.Clan;
import fr.windydev.clans.model.Rank;
import fr.windydev.clans.util.Text;
import java.awt.Color;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.text.Normalizer.Form;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.components.MessageTopLevelComponent;
import net.dv8tion.jda.api.components.ModalTopLevelComponent;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.actionrow.ActionRowChildComponent;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.modals.ModalMapping;
import net.dv8tion.jda.api.modals.Modal;
import net.dv8tion.jda.api.modals.Modal.Builder;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.requests.RestAction;
import net.dv8tion.jda.api.requests.restaction.ChannelAction;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;
import net.dv8tion.jda.api.requests.restaction.interactions.MessageEditCallbackAction;
import net.dv8tion.jda.api.requests.restaction.order.CategoryOrderAction;
import net.dv8tion.jda.api.requests.restaction.order.ChannelOrderAction;
import net.dv8tion.jda.api.utils.ChunkingFilter;
import net.dv8tion.jda.api.utils.FileUpload;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

public class DiscordBot extends ListenerAdapter {
   private static final Color GOLD = new Color(232, 184, 76);
   private static final Color PURPLE = new Color(146, 106, 206);
   private final WindyClans plugin;
   private final File dataFile;
   private YamlConfiguration data;
   private JDA jda;
   private volatile Guild guild;
   private BukkitTask statsTask;
   private boolean syncQueued;
   private final Map<String, String[]> pendingLinks = new ConcurrentHashMap<>();
   private final Map<String, String[]> linkCodes = new ConcurrentHashMap<>();
   private byte[] bannerBytes;
   private byte[] stripBytes;
   private static final String DEFAULT_WIKI = "https://claude.ai/artifact/Mj41ubTEN973gnqqn5RRSS";

   public DiscordBot(WindyClans plugin) {
      this.plugin = plugin;
      this.dataFile = new File(plugin.getDataFolder(), "discord.yml");
   }

   private ConfigurationSection cfg() {
      return this.plugin.getConfig().getConfigurationSection("discord-bot");
   }

   public boolean ready() {
      return this.guild != null;
   }

   public void start() {
      this.data = YamlConfiguration.loadConfiguration(this.dataFile);
      ConfigurationSection c = this.cfg();
      if (c != null && c.getBoolean("enabled", false)) {
         String token = c.getString("token", "");
         if (token != null && !token.isBlank()) {
            String activity = Text.strip(this.plugin.settings().serverName);
            Bukkit.getScheduler()
               .runTaskAsynchronously(
                  this.plugin,
                  () -> {
                     try {
                        this.jda = JDABuilder.createDefault(token.trim(), GatewayIntent.GUILD_MEMBERS, new GatewayIntent[]{GatewayIntent.GUILD_MESSAGES})
                           .disableCache(
                              CacheFlag.VOICE_STATE,
                              new CacheFlag[]{CacheFlag.EMOJI, CacheFlag.STICKER, CacheFlag.SCHEDULED_EVENTS, CacheFlag.SOUNDBOARD_SOUNDS}
                           )
                           .setMemberCachePolicy(MemberCachePolicy.ALL)
                           .setChunkingFilter(ChunkingFilter.ALL)
                           .setActivity(Activity.playing(activity))
                           .addEventListeners(new Object[]{this})
                           .build();
                     } catch (Throwable var4) {
                        this.plugin
                           .getLogger()
                           .severe(
                              "Bot Discord : démarrage impossible : "
                                 + var4.getMessage()
                                 + " (vérifie le token et active « Server Members Intent » dans le portail développeur)"
                           );
                     }
                  }
               );
         } else {
            this.plugin.getLogger().warning("Bot Discord activé mais sans token.");
         }
      }
   }

   public void stop() {
      if (this.statsTask != null) {
         this.statsTask.cancel();
      }

      if (this.jda != null) {
         this.jda.shutdown();

         try {
            this.jda.awaitShutdown(5L, TimeUnit.SECONDS);
         } catch (InterruptedException var2) {
         }
      }

      this.saveData();
   }

   private synchronized void saveData() {
      try {
         this.data.save(this.dataFile);
      } catch (IOException var2) {
         this.plugin.getLogger().warning("discord.yml : " + var2.getMessage());
      }
   }

   public void onReady(ReadyEvent e) {
      String gid = this.cfg().getString("guild-id", "");
      Guild g = gid != null && !gid.isBlank() ? this.jda.getGuildById(gid.trim()) : null;
      if (g == null && this.jda.getGuilds().size() == 1) {
         g = (Guild)this.jda.getGuilds().get(0);
      }

      if (g == null) {
         this.plugin.getLogger().severe("Bot Discord : serveur Discord introuvable (guild-id).");
      } else {
         this.guild = g;
         this.plugin.getLogger().info("Bot Discord connecté à " + g.getName() + ".");
         g.updateCommands()
            .addCommands(
               new CommandData[]{
                  Commands.slash("stats", "Statistiques du serveur Minecraft"),
                  Commands.slash("clan", "Infos sur un clan").addOption(OptionType.STRING, "nom", "Nom du clan", true),
                  Commands.slash("setup", "Recréer les salons du bot")
                     .setDefaultPermissions(DefaultMemberPermissions.enabledFor(new Permission[]{Permission.ADMINISTRATOR})),
                  Commands.slash("lier", "Obtenir un code pour lier ton compte Minecraft"),
                  Commands.slash("patchnote", "Publier une patch note")
                     .setDefaultPermissions(DefaultMemberPermissions.enabledFor(new Permission[]{Permission.ADMINISTRATOR}))
               }
            )
            .queue();

         try {
            this.setup();
         } catch (Exception var5) {
            this.plugin.getLogger().severe("Bot Discord : configuration impossible (" + var5.getMessage() + "). Vérifie ses permissions.");
            return;
         }

         Bukkit.getScheduler().runTask(this.plugin, () -> {
            this.requestSync();
            int minutes = Math.max(1, this.cfg().getInt("stats-interval-minutes", 5));
            this.statsTask = Bukkit.getScheduler().runTaskTimer(this.plugin, this::updateStats, 100L, minutes * 1200L);
         });
      }
   }

   private Role staffRole() {
      String id = this.data.getString("staff-role");
      Role r = id == null ? null : this.guild.getRoleById(id);
      if (r == null) {
         String name = this.cfg().getString("staff-role-name", "Staff WINDY_MC");
         List<Role> found = this.guild.getRolesByName(name, true);
         r = found.isEmpty() ? (Role)this.guild.createRole().setName(name).setColor(GOLD).setMentionable(true).complete() : found.get(0);
         this.data.set("staff-role", r.getId());
      }

      return r;
   }

   private Category mainCategory() {
      String id = this.data.getString("category");
      Category c = id == null ? null : this.guild.getCategoryById(id);
      if (c == null) {
         c = (Category)this.guild.createCategory(this.cfg().getString("category-name", "WINDY_MC")).complete();
         this.data.set("category", c.getId());
      }

      return c;
   }

   private TextChannel channel(String key, String name, String topic, String mode) {
      String id = this.data.getString("channels." + key);
      TextChannel ch = id == null ? null : this.guild.getTextChannelById(id);
      if (ch != null) {
         return ch;
      } else {
         Category cat = this.mainCategory();
         ChannelAction<TextChannel> action = cat.createTextChannel(name).setTopic(topic);
         Role everyone = this.guild.getPublicRole();
         Role staff = this.staffRole();
         if (mode.equals("staff")) {
            action = action.addPermissionOverride(everyone, List.of(), List.of(Permission.VIEW_CHANNEL))
               .addPermissionOverride(staff, List.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND), List.of());
         } else {
            action = action.addPermissionOverride(everyone, List.of(Permission.VIEW_CHANNEL), List.of(Permission.MESSAGE_SEND));
         }

         action = action.addMemberPermissionOverride(
            this.guild.getSelfMember().getIdLong(),
            List.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_EMBED_LINKS, Permission.MANAGE_CHANNEL),
            List.of()
         );
         ch = (TextChannel)action.complete();
         this.data.set("channels." + key, ch.getId());
         return ch;
      }
   }

   private byte[] res(String name) {
      try {
         byte[] var3;
         try (InputStream in = this.plugin.getResource(name)) {
            var3 = in == null ? null : in.readAllBytes();
         }

         return var3;
      } catch (IOException var7) {
         return null;
      }
   }

   private FileUpload banner() {
      if (this.bannerBytes == null) {
         this.bannerBytes = this.res("banniere.jpg");
      }

      return this.bannerBytes == null ? null : FileUpload.fromData(this.bannerBytes, "banniere.jpg");
   }

   private FileUpload strip() {
      if (this.stripBytes == null) {
         this.stripBytes = this.res("banniere-fine.jpg");
      }

      return this.stripBytes == null ? null : FileUpload.fromData(this.stripBytes, "banniere-fine.jpg");
   }

   private String address() {
      return Text.strip(this.plugin.settings().serverFooter);
   }

   private void setup() {
      this.staffRole();
      TextChannel rules = this.channel("rules", "\ud83d\udcdc-règlement", "Règlement du serveur WINDY_MC", "read");
      TextChannel ann = this.channel("announcements", "\ud83d\udce2-annonces", "Annonces du serveur WINDY_MC", "read");
      TextChannel notes = this.channel("patchnotes", "\ud83d\udcf0-patch-notes", "Nouveautés et mises à jour du serveur", "read");
      TextChannel stats = this.channel("stats", "\ud83d\udcca-statistiques", "Statistiques en direct du serveur", "read");
      TextChannel link = this.channel("link", "\ud83d\udd17-liaison", "Lier ton compte Minecraft à Discord", "read");
      TextChannel tickets = this.channel("tickets", "\ud83c\udfab-tickets", "Signaler un bug ou un tricheur", "read");
      TextChannel logs = this.channel("staff-logs", "\ud83d\udee1-logs-staff", "Logs des rituels, guerres et boss", "staff");

      try {
         CategoryOrderAction order = this.mainCategory().modifyTextChannelPositions();
         int i = 0;

         for (TextChannel c : List.of(rules, ann, notes, stats, link, tickets, logs)) {
            ((ChannelOrderAction)order.selectPosition(c)).moveTo(i++);
         }

         order.queue(null, err -> {});
      } catch (Exception var13) {
      }

      if (this.data.getString("messages.welcome") == null) {
         Message m = (Message)((MessageCreateAction)ann.sendMessageEmbeds(
                  new EmbedBuilder()
                     .setTitle("✨ Bienvenue sur WINDY_MC")
                     .setDescription(
                        "Serveur survie avec **clans, territoires et guerres**, 9 races qui s'éveillent jusqu'au rang V, rituels d'autel, boss de clan et magie des élixirs.\n\n\ud83c\udf0d **Adresse** : `"
                           + this.address()
                           + "`\n\ud83e\uddf1 **Version** : Minecraft 1.21 (versions non officielles acceptées)\n\ud83d\udd10 **Première connexion** : `/register <mdp> <mdp>`, puis `/login <mdp>`\n\n\ud83d\udcdc Lis le "
                           + rules.getAsMention()
                           + " · \ud83d\udd17 Lie ton compte dans "
                           + link.getAsMention()
                           + " · \ud83c\udfab Un souci ? "
                           + tickets.getAsMention()
                     )
                     .setImage("attachment://banniere.jpg")
                     .setColor(PURPLE)
                     .build(),
                  new MessageEmbed[0]
               )
               .addFiles(new FileUpload[]{this.banner()}))
            .complete();
         this.data.set("messages.welcome", m.getId());
      }

      this.postRules(rules);
      if (this.data.getString("messages.link") == null) {
         Message m = (Message)((MessageCreateAction)((MessageCreateAction)link.sendMessageEmbeds(
                     new EmbedBuilder()
                        .setTitle("\ud83d\udd17 Lier ton compte Minecraft")
                        .setDescription(
                           "Une fois lié, tu reçois automatiquement le rôle de ton clan et l'accès à ses salons privés.\n\n**Depuis Discord**\n1. Clique sur le bouton ci-dessous (ou tape `/lier`).\n2. En jeu, tape `/lier <code>` avec le code reçu (valable 10 minutes).\n\n**Depuis le jeu**\nMenu › Paramètres › *Lier mon Discord*, puis confirme le message privé du bot."
                        )
                        .setImage("attachment://banniere.jpg")
                        .setColor(GOLD)
                        .build(),
                     new MessageEmbed[0]
                  )
                  .addFiles(new FileUpload[]{this.banner()}))
               .setComponents(
                  new MessageTopLevelComponent[]{ActionRow.of(Button.success("linkcode", "\ud83d\udd17 Obtenir mon code"), new ActionRowChildComponent[0])}
               ))
            .complete();
         this.data.set("messages.link", m.getId());
      }

      if (!this.data.getBoolean("v2.tickets")) {
         String old = this.data.getString("messages.tickets");
         if (old != null) {
            tickets.deleteMessageById(old).queue(null, err -> {});
         }

         this.data.set("messages.tickets", null);
         this.data.set("v2.tickets", true);
      }

      if (this.data.getString("messages.tickets") == null) {
         Message m = (Message)((MessageCreateAction)((MessageCreateAction)tickets.sendMessageEmbeds(
                     new EmbedBuilder()
                        .setTitle("\ud83c\udfab Support WINDY_MC")
                        .setDescription(
                           "Un problème en jeu ? Ouvre un ticket : un salon privé est créé avec le staff.\n\n\ud83d\udc1e **Bug** : quelque chose ne marche pas comme prévu\n\ud83d\udea8 **Triche** : un joueur utilise un cheat ou exploite un bug\n\nTu peux aussi contester une sanction ici."
                        )
                        .setImage("attachment://banniere.jpg")
                        .setColor(GOLD)
                        .build(),
                     new MessageEmbed[0]
                  )
                  .addFiles(new FileUpload[]{this.banner()}))
               .setComponents(
                  new MessageTopLevelComponent[]{
                     ActionRow.of(
                        Button.primary("ticket:bug", "\ud83d\udc1e Signaler un bug"),
                        new ActionRowChildComponent[]{Button.danger("ticket:cheat", "\ud83d\udea8 Signaler un tricheur")}
                     )
                  }
               ))
            .complete();
         this.data.set("messages.tickets", m.getId());
      }

      if (!this.data.getBoolean("v2.stats")) {
         String old = this.data.getString("messages.stats");
         if (old != null) {
            stats.deleteMessageById(old).queue(null, err -> {});
         }

         this.data.set("messages.stats", null);
         this.data.set("v2.stats", true);
      }

      try {
         this.postWiki();
      } catch (Exception var12) {
         this.plugin.getLogger().warning("Discord : message du wiki impossible : " + var12.getMessage());
      }

      this.postVersionNote(notes);
      this.saveData();
   }

   private String wikiUrl() {
      String u = this.cfg().getString("wiki-url", "");
      return u != null && !u.isBlank() ? u.trim() : "https://claude.ai/artifact/Mj41ubTEN973gnqqn5RRSS";
   }

   private TextChannel infoChannel() {
      String id = this.data.getString("channels.info");
      TextChannel ch = id == null ? null : this.guild.getTextChannelById(id);
      if (ch != null) {
         return ch;
      } else {
         String wanted = this.cfg().getString("info-channel", "");

         for (TextChannel c : this.guild.getTextChannels()) {
            String n = Normalizer.normalize(c.getName(), Form.NFD).replaceAll("\\p{M}", "").toLowerCase();
            if (!wanted.isBlank() && c.getName().equalsIgnoreCase(wanted) || wanted.isBlank() && n.contains("info")) {
               ch = c;
               break;
            }
         }

         if (ch == null) {
            ch = this.channel("info", "ℹ️-infos-serveur", "Infos du serveur WINDY_MC et lien du wiki", "read");
         }

         this.data.set("channels.info", ch.getId());
         return ch;
      }
   }

   private void postWiki() {
      TextChannel info = this.infoChannel();
      String url = this.wikiUrl();
      String hash = "3:" + url;
      String id = this.data.getString("messages.wiki");
      if (id == null || !hash.equals(this.data.getString("messages.wiki-hash"))) {
         if (id != null) {
            info.deleteMessageById(id).queue(null, err -> {});
         }

         Message m = (Message)((MessageCreateAction)((MessageCreateAction)info.sendMessageEmbeds(
                     new EmbedBuilder()
                        .setTitle("\ud83d\udcd6 Wiki de WINDY_MC", url)
                        .setDescription(
                           "Tout le fonctionnement du serveur est expliqué sur le wiki, mis à jour pour l'ouverture :\n\n\ud83d\udd10 Première connexion (`/register`, `/login`, skin)\n\ud83c\udff0 Clans, territoires, banque, diplomatie et guerres\n\ud83e\uddec Les 9 races, l'arbre de compétences et l'Éveil\n✨ Rituels d'autel, boss de clan et runes\n\ud83c\udf47 Élixirs magiques : baies, tanks, extracteur, infuseur\n\ud83d\udcdc Règlement et commandes\n\n\ud83c\udf0d **Adresse** : `"
                              + this.address()
                              + "`"
                        )
                        .setImage("attachment://banniere.jpg")
                        .setColor(PURPLE)
                        .setFooter("WINDY_MC · wiki à jour pour la version " + this.plugin.getPluginMeta().getVersion())
                        .build(),
                     new MessageEmbed[0]
                  )
                  .addFiles(new FileUpload[]{this.banner()}))
               .setComponents(new MessageTopLevelComponent[]{ActionRow.of(Button.link(url, "\ud83d\udcd6 Ouvrir le wiki"), new ActionRowChildComponent[0])}))
            .complete();
         this.data.set("messages.wiki", m.getId());
         this.data.set("messages.wiki-hash", hash);
      }
   }

   private void postRules(TextChannel rules) {
      File f = new File(this.plugin.getDataFolder(), "reglement.yml");
      if (!f.exists()) {
         this.plugin.saveResource("reglement.yml", false);
      }

      YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
      List<MessageEmbed> embeds = new ArrayList<>();
      embeds.add(
         new EmbedBuilder()
            .setTitle(y.getString("titre", "\ud83d\udcdc Règlement"))
            .setDescription(y.getString("introduction", ""))
            .setImage("attachment://banniere.jpg")
            .setColor(PURPLE)
            .build()
      );

      for (Map<?, ?> sec : y.getMapList("sections")) {
         if (embeds.size() >= 10) {
            break;
         }

         String t = String.valueOf(sec.get("titre"));
         String txt = String.valueOf(sec.get("texte"));
         embeds.add(
            new EmbedBuilder()
               .setTitle(t.length() > 250 ? t.substring(0, 250) : t)
               .setDescription(txt.length() > 1000 ? txt.substring(0, 1000) : txt)
               .setColor(GOLD)
               .build()
         );
      }

      String hash = Integer.toHexString(embeds.stream().map(e -> e.getTitle() + e.getDescription()).toList().hashCode());
      String id = this.data.getString("messages.rules");
      if (id == null || !hash.equals(this.data.getString("messages.rules-hash"))) {
         if (id != null) {
            rules.deleteMessageById(id).queue(null, err -> {});
         }

         Message m = (Message)((MessageCreateAction)((MessageCreateAction)rules.sendMessageEmbeds(embeds).addFiles(new FileUpload[]{this.banner()}))
               .setComponents(
                  new MessageTopLevelComponent[]{
                     ActionRow.of(Button.success("rules:accept", "✅ J'ai lu et j'accepte le règlement"), new ActionRowChildComponent[0])
                  }
               ))
            .complete();
         this.data.set("messages.rules", m.getId());
         this.data.set("messages.rules-hash", hash);
      }
   }

   private Role playerRole() {
      String id = this.data.getString("player-role");
      Role r = id == null ? null : this.guild.getRoleById(id);
      if (r == null) {
         String name = this.cfg().getString("player-role-name", "Joueur");
         List<Role> found = this.guild.getRolesByName(name, true);
         r = found.isEmpty() ? (Role)this.guild.createRole().setName(name).setColor(PURPLE).complete() : found.get(0);
         this.data.set("player-role", r.getId());
         this.saveData();
      }

      return r;
   }

   private void postVersionNote(TextChannel notes) {
      String version = this.plugin.getPluginMeta().getVersion();
      if (!this.data.getStringList("patchnotes.posted").contains(version)) {
         YamlConfiguration y;
         try {
            label58: {
               try (InputStream in = this.plugin.getResource("patchnotes.yml")) {
                  if (in != null) {
                     y = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
                     break label58;
                  }
               }

               return;
            }
         } catch (IOException var9) {
            return;
         }

         // publie toutes les notes manquées depuis la dernière publiée (ex. saut de 1.8.6 à 1.13.0)
         List<String> posted = new ArrayList<>(this.data.getStringList("patchnotes.posted"));
         String last = null;
         for (String v : posted) {
            if (last == null || cmpVersion(v, last) > 0) {
               last = v;
            }
         }

         ConfigurationSection all = y.getConfigurationSection("notes");
         List<String> todo = new ArrayList<>();
         if (all != null) {
            for (String v : all.getKeys(false)) {
               if (!posted.contains(v) && cmpVersion(v, version) <= 0 && (last == null ? v.equals(version) : cmpVersion(v, last) > 0)) {
                  todo.add(v);
               }
            }
         }

         todo.sort(DiscordBot::cmpVersion);
         // une note « resume: true » remplace toutes les notes manquées : elle est publiée seule
         ConfigurationSection cur = all == null ? null : all.getConfigurationSection(version);
         if (cur != null && cur.getBoolean("resume", false)) {
            for (String v : todo) {
               if (!v.equals(version)) {
                  posted.add(v);
               }
            }

            todo.removeIf(v -> !v.equals(version));
         }

         for (String v : todo) {
            ConfigurationSection n = all.getConfigurationSection(v);
            List<Map<?, ?>> parts = n.getMapList("parties");
            if (parts.isEmpty()) {
               this.postPatchNote(notes, v, n.getString("titre", "Mise à jour"), n.getString("texte", ""));
            } else {
               for (Map<?, ?> part : parts) {
                  this.postPatchNote(notes, v, String.valueOf(part.get("titre")), String.valueOf(part.get("texte")));
               }
            }

            posted.add(v);
         }

         if (!posted.contains(version)) {
            posted.add(version);
         }

         this.data.set("patchnotes.posted", posted);
      }
   }

   static int cmpVersion(String a, String b) {
      String[] x = a.split("\\.");
      String[] y = b.split("\\.");
      for (int i = 0; i < Math.max(x.length, y.length); i++) {
         int p = i < x.length ? parseInt(x[i]) : 0;
         int q = i < y.length ? parseInt(y[i]) : 0;
         if (p != q) {
            return Integer.compare(p, q);
         }
      }

      return 0;
   }

   private static int parseInt(String s) {
      try {
         return Integer.parseInt(s.replaceAll("[^0-9]", ""));
      } catch (NumberFormatException e) {
         return 0;
      }
   }

   private void postPatchNote(TextChannel notes, String version, String title, String text) {
      String t = "\ud83d\udcf0 Patch note " + version + (title.isBlank() ? "" : " — " + title);
      ((MessageCreateAction)notes.sendMessageEmbeds(
               new EmbedBuilder()
                  .setTitle(t.length() > 250 ? t.substring(0, 250) : t)
                  .setDescription(text.length() > 4000 ? text.substring(0, 4000) : text)
                  .setImage("attachment://banniere.jpg")
                  .setColor(PURPLE)
                  .setFooter("WINDY_MC · " + this.address())
                  .setTimestamp(Instant.now())
                  .build(),
               new MessageEmbed[0]
            )
            .addFiles(new FileUpload[]{this.banner()}))
         .complete();
   }

   private TextChannel ch(String key) {
      String id = this.data.getString("channels." + key);
      return this.guild != null && id != null ? this.guild.getTextChannelById(id) : null;
   }

   public void announce(String event, String text) {
      if (this.ready()) {
         TextChannel c = this.ch("announcements");
         if (c != null) {
            FileUpload img = this.strip();
            EmbedBuilder eb = new EmbedBuilder().setDescription(text).setColor(PURPLE).setTimestamp(Instant.now());
            if (img != null) {
               ((MessageCreateAction)c.sendMessageEmbeds(eb.setImage("attachment://banniere-fine.jpg").build(), new MessageEmbed[0])
                     .addFiles(new FileUpload[]{img}))
                  .queue();
            } else {
               c.sendMessageEmbeds(eb.build(), new MessageEmbed[0]).queue();
            }
         }
      }
   }

   public void staffLog(String title, String text) {
      if (this.ready()) {
         TextChannel c = this.ch("staff-logs");
         if (c != null) {
            c.sendMessageEmbeds(new EmbedBuilder().setTitle(title).setDescription(text).setColor(GOLD).setTimestamp(Instant.now()).build(), new MessageEmbed[0])
               .queue();
         }
      }
   }

   public void requestSync() {
      if (this.ready() && !this.syncQueued) {
         this.syncQueued = true;
         Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
            this.syncQueued = false;
            this.syncAll();
         }, 40L);
      }
   }

   private void syncAll() {
      List<DiscordBot.ClanSnap> snaps = new ArrayList<>();

      for (Clan c : this.plugin.clans().all()) {
         Map<String, Rank> m = new HashMap<>();
         c.members().forEach((u, r) -> m.put(u.toString(), r));
         snaps.add(new DiscordBot.ClanSnap(c.id().toString(), c.name(), c.color(), m));
      }

      Bukkit.getScheduler().runTaskAsynchronously(this.plugin, () -> {
         try {
            this.doSync(snaps);
         } catch (Exception var3x) {
            this.plugin.getLogger().warning("Bot Discord (sync) : " + var3x.getMessage());
         }
      });
   }

   private synchronized void doSync(List<DiscordBot.ClanSnap> clans) {
      if (this.ready()) {
         Role staff = this.staffRole();
         Set<String> alive = new HashSet<>();

         for (DiscordBot.ClanSnap c : clans) {
            alive.add(c.id());
            String base = "clans." + c.id();
            Role chef = this.role(base + ".chef", "Chef de clan " + c.name(), c.color());
            Role member = this.role(base + ".member", "Membre de " + c.name(), c.color());
            String catId = this.data.getString(base + ".category");
            if (catId == null || this.guild.getCategoryById(catId) == null) {
               Category cat = (Category)this.guild
                  .createCategory("\ud83c\udff0 " + c.name())
                  .addPermissionOverride(this.guild.getPublicRole(), List.of(), List.of(Permission.VIEW_CHANNEL))
                  .addPermissionOverride(
                     chef, List.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.VOICE_CONNECT, Permission.VOICE_SPEAK), List.of()
                  )
                  .addPermissionOverride(
                     member, List.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.VOICE_CONNECT, Permission.VOICE_SPEAK), List.of()
                  )
                  .addPermissionOverride(staff, List.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.VOICE_CONNECT), List.of())
                  .addMemberPermissionOverride(this.guild.getSelfMember().getIdLong(), List.of(Permission.VIEW_CHANNEL, Permission.MANAGE_CHANNEL), List.of())
                  .complete();
               cat.createTextChannel("discussion-" + slug(c.name())).complete();
               cat.createVoiceChannel("\ud83d\udd0a " + c.name()).complete();
               this.data.set(base + ".category", cat.getId());
               this.data.set(base + ".name", c.name());
               this.staffLog("\ud83c\udff0 Clan créé", "Salons et rôles créés pour **" + c.name() + "**.");
            }
         }

         ConfigurationSection cs = this.data.getConfigurationSection("clans");
         if (cs != null) {
            for (String id : new ArrayList<String>(cs.getKeys(false))) {
               if (!alive.contains(id)) {
                  String base = "clans." + id;
                  this.deleteRole(this.data.getString(base + ".chef"));
                  this.deleteRole(this.data.getString(base + ".member"));
                  String catId = this.data.getString(base + ".category");
                  Category cat = catId == null ? null : this.guild.getCategoryById(catId);
                  if (cat != null) {
                     for (GuildChannel gc : cat.getChannels()) {
                        gc.delete().complete();
                     }

                     cat.delete().complete();
                  }

                  this.staffLog("\ud83d\udca8 Clan dissous", "Salons et rôles supprimés pour **" + this.data.getString(base + ".name", id) + "**.");
                  this.data.set(base, null);
               }
            }
         }

         Set<String> allClanRoles = new HashSet<>();
         if (this.data.getConfigurationSection("clans") != null) {
            for (String idx : this.data.getConfigurationSection("clans").getKeys(false)) {
               allClanRoles.add(this.data.getString("clans." + idx + ".chef"));
               allClanRoles.add(this.data.getString("clans." + idx + ".member"));
            }
         }

         ConfigurationSection links = this.data.getConfigurationSection("links");
         if (links != null) {
            for (String uuid : links.getKeys(false)) {
               Member m = this.guild.getMemberById(links.getString(uuid));
               if (m != null) {
                  String want = null;

                  for (DiscordBot.ClanSnap cx : clans) {
                     Rank r = cx.members().get(uuid);
                     if (r != null) {
                        want = this.data.getString("clans." + cx.id() + (r == Rank.CHEF ? ".chef" : ".member"));
                     }
                  }

                  List<Role> add = new ArrayList<>();
                  List<Role> remove = new ArrayList<>();

                  for (Role r : m.getRoles()) {
                     if (allClanRoles.contains(r.getId()) && !r.getId().equals(want)) {
                        remove.add(r);
                     }
                  }

                  if (want != null) {
                     Role w = this.guild.getRoleById(want);
                     if (w != null && !m.getRoles().contains(w)) {
                        add.add(w);
                     }
                  }

                  if (!add.isEmpty() || !remove.isEmpty()) {
                     this.guild.modifyMemberRoles(m, add, remove).queue();
                  }
               }
            }
         }

         this.saveData();
      }
   }

   private Role role(String path, String name, String legacyColor) {
      String id = this.data.getString(path);
      Role r = id == null ? null : this.guild.getRoleById(id);
      if (r == null) {
         r = (Role)this.guild.createRole().setName(name).setColor(new Color(Text.rgb(legacyColor))).setMentionable(true).complete();
         this.data.set(path, r.getId());
      } else if (!r.getName().equals(name)) {
         r.getManager().setName(name).queue();
      }

      return r;
   }

   private void deleteRole(String id) {
      Role r = id == null ? null : this.guild.getRoleById(id);
      if (r != null) {
         r.delete().complete();
      }
   }

   private static String slug(String s) {
      return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "");
   }

   public String linkedId(UUID player) {
      return this.data.getString("links." + player);
   }

   public void unlink(Player p) {
      this.data.set("links." + p.getUniqueId(), null);
      this.saveData();
      this.requestSync();
      this.plugin.msg(p, "&7Ton compte Discord n'est plus lié.");
   }

   public void startLink(Player p, String discordName) {
      if (!this.ready()) {
         this.plugin.msg(p, "&cLe bot Discord n'est pas connecté.");
      } else {
         String name = discordName.trim().replace("@", "");
         UUID uuid = p.getUniqueId();
         String mcName = p.getName();
         this.guild
            .retrieveMembersByPrefix(name, 25)
            .onSuccess(
               list -> {
                  Member found = null;

                  for (Member m : list) {
                     if (m.getUser().getName().equalsIgnoreCase(name) || m.getEffectiveName().equalsIgnoreCase(name)) {
                        found = m;
                        break;
                     }
                  }

                  if (found == null) {
                     Bukkit.getScheduler().runTask(this.plugin, () -> {
                        Player pl = Bukkit.getPlayer(uuid);
                        if (pl != null) {
                           this.plugin.msg(pl, "&cAucun membre « " + name + " » sur le Discord. Utilise ton nom d'utilisateur Discord.");
                        }
                     });
                  } else {
                     String token = Long.toHexString(new Random().nextLong() & 4294967295L);
                     this.pendingLinks.put(token, new String[]{uuid.toString(), found.getId()});
                     Member target = found;
                     Button ok = Button.success("link:" + token, "✅ Oui, je suis " + mcName);
                     Button no = Button.secondary("linkno:" + token, "Ce n'est pas moi");
                     MessageEmbed embed = new EmbedBuilder()
                        .setTitle("\ud83d\udd17 Lier ton compte Minecraft")
                        .setDescription("Le joueur **" + mcName + "** veut lier son compte Minecraft à ton Discord.\nC'est bien toi ?")
                        .setColor(GOLD)
                        .build();
                     target.getUser()
                        .openPrivateChannel()
                        .flatMap(
                           dm -> (RestAction)dm.sendMessageEmbeds(embed, new MessageEmbed[0])
                              .setComponents(new MessageTopLevelComponent[]{ActionRow.of(ok, new ActionRowChildComponent[]{no})})
                        )
                        .queue(
                           s -> this.tell(uuid, "&aRegarde tes messages privés Discord pour confirmer."),
                           err -> {
                              TextChannel c = this.ch("link");
                              if (c != null) {
                                 ((MessageCreateAction)((MessageCreateAction)c.sendMessage(target.getAsMention()).setEmbeds(new MessageEmbed[]{embed}))
                                       .setComponents(new MessageTopLevelComponent[]{ActionRow.of(ok, new ActionRowChildComponent[]{no})}))
                                    .queue();
                              }

                              this.tell(uuid, "&eTes messages privés sont fermés : confirme dans le salon &f#liaison &edu Discord.");
                           }
                        );
                  }
               }
            )
            .onError(err -> this.tell(uuid, "&cErreur Discord : " + err.getMessage()));
      }
   }

   private void tell(UUID uuid, String msg) {
      Bukkit.getScheduler().runTask(this.plugin, () -> {
         Player pl = Bukkit.getPlayer(uuid);
         if (pl != null) {
            this.plugin.msg(pl, msg);
         }
      });
   }

   private void updateStats() {
      if (this.ready()) {
         List<Clan> top = this.plugin.clans().top();
         StringBuilder topTxt = new StringBuilder();

         for (int i = 0; i < Math.min(5, top.size()); i++) {
            Clan c = top.get(i);
            topTxt.append(i == 0 ? "\ud83e\udd47" : (i == 1 ? "\ud83e\udd48" : (i == 2 ? "\ud83e\udd49" : "▫️")))
               .append(" **")
               .append(c.name())
               .append("** [")
               .append(c.tag())
               .append("] — niv. ")
               .append(c.level())
               .append(" · ")
               .append(c.members().size())
               .append(" membres\n");
         }

         StringBuilder online = new StringBuilder();

         for (Player p : Bukkit.getOnlinePlayers()) {
            online.append(p.getName()).append(", ");
         }

         int wars = 0;

         for (Clan c : this.plugin.clans().all()) {
            if (this.plugin.war().warOf(c) != null) {
               wars++;
            }
         }

         int bosses = 0;

         for (Clan cx : this.plugin.clans().all()) {
            bosses += cx.bossesBeaten().size();
         }

         int rituals = this.data.getInt("counters.rituals");
         MessageEmbed embed = new EmbedBuilder()
            .setTitle("\ud83d\udcca " + Text.strip(this.plugin.settings().serverName) + " — statistiques")
            .setColor(GOLD)
            .addField(
               "\ud83d\udfe2 Joueurs en ligne",
               Bukkit.getOnlinePlayers().size() + " / " + Bukkit.getMaxPlayers() + (online.length() > 0 ? "\n" + online.substring(0, online.length() - 2) : ""),
               false
            )
            .addField("\ud83c\udff0 Clans", String.valueOf(this.plugin.clans().all().size()), true)
            .addField("⚔️ Guerres en cours", String.valueOf(wars / 2), true)
            .addField("☠️ Boss vaincus", String.valueOf(bosses), true)
            .addField("✨ Rituels accomplis", String.valueOf(rituals), true)
            .addField("\ud83c\udfc6 Top clans", topTxt.length() == 0 ? "Aucun clan" : topTxt.toString(), false)
            .addField("\ud83c\udff0 Records des donjons", this.plugin.dungeons() == null ? "—" : this.plugin.dungeons().recordsDiscord(), false)
            .setFooter(Text.strip(this.plugin.settings().serverFooter))
            .setImage("attachment://banniere-fine.jpg")
            .setTimestamp(Instant.now())
            .build();
         TextChannel cx = this.ch("stats");
         if (cx != null) {
            String msgId = this.data.getString("messages.stats");
            if (msgId == null) {
               ((MessageCreateAction)cx.sendMessageEmbeds(embed, new MessageEmbed[0]).addFiles(new FileUpload[]{this.strip()})).queue(m -> {
                  this.data.set("messages.stats", m.getId());
                  this.saveData();
               });
            } else {
               cx.editMessageEmbedsById(msgId, new MessageEmbed[]{embed}).queue(null, err -> {
                  this.data.set("messages.stats", null);
                  ((MessageCreateAction)cx.sendMessageEmbeds(embed, new MessageEmbed[0]).addFiles(new FileUpload[]{this.strip()})).queue(m -> {
                     this.data.set("messages.stats", m.getId());
                     this.saveData();
                  });
               });
            }
         }
      }
   }

   public void countRitual() {
      this.data.set("counters.rituals", this.data.getInt("counters.rituals") + 1);
      this.saveData();
   }

   public void onSlashCommandInteraction(SlashCommandInteractionEvent e) {
      String var2 = e.getName();
      switch (var2) {
         case "stats":
            e.deferReply(true).queue();
            Bukkit.getScheduler()
               .runTask(
                  this.plugin,
                  () -> {
                     this.updateStats();
                     e.getHook()
                        .sendMessage("Les statistiques sont dans " + (this.ch("stats") == null ? "#statistiques" : this.ch("stats").getAsMention()) + ".")
                        .queue();
                  }
               );
            break;
         case "clan":
            String name = e.getOption("nom").getAsString();
            e.deferReply(true).queue();
            Bukkit.getScheduler()
               .runTask(
                  this.plugin,
                  () -> {
                     Clan c = this.plugin.clans().byName(name);
                     if (c == null) {
                        e.getHook().sendMessage("Clan introuvable.").queue();
                     } else {
                        StringBuilder mem = new StringBuilder();
                        c.members().forEach((u, r) -> mem.append(r == Rank.CHEF ? "\ud83d\udc51 " : "• ").append(this.plugin.clans().nameOf(u)).append("\n"));
                        e.getHook()
                           .sendMessageEmbeds(
                              new EmbedBuilder()
                                 .setTitle("\ud83c\udff0 " + c.name() + " [" + c.tag() + "]")
                                 .setColor(new Color(Text.rgb(c.color())))
                                 .setDescription(Text.strip(c.description()))
                                 .addField("Niveau", String.valueOf(c.level()), true)
                                 .addField("Territoire", c.claims().size() + " chunks", true)
                                 .addField("Boss vaincus", c.bossesBeaten().size() + "", true)
                                 .addField("Membres", mem.toString(), false)
                                 .build(),
                              new MessageEmbed[0]
                           )
                           .queue();
                     }
                  }
               );
            break;
         case "lier":
            e.reply(this.codeMessage(e.getUser().getId())).setEphemeral(true).queue();
            break;
         case "patchnote":
            Modal m = Modal.create("patchnote", "Publier une patch note")
               .addComponents(
                  new ModalTopLevelComponent[]{
                     Label.of(
                        "Version",
                        TextInput.create("version", TextInputStyle.SHORT)
                           .setRequired(true)
                           .setMaxLength(20)
                           .setValue(this.plugin.getPluginMeta().getVersion())
                           .build()
                     )
                  }
               )
               .addComponents(
                  new ModalTopLevelComponent[]{Label.of("Titre", TextInput.create("titre", TextInputStyle.SHORT).setRequired(false).setMaxLength(100).build())}
               )
               .addComponents(
                  new ModalTopLevelComponent[]{
                     Label.of(
                        "Nouveautés",
                        TextInput.create("texte", TextInputStyle.PARAGRAPH)
                           .setRequired(true)
                           .setMaxLength(4000)
                           .setPlaceholder("**Titre de section**\n• nouveauté 1\n• nouveauté 2")
                           .build()
                     )
                  }
               )
               .build();
            e.replyModal(m).queue();
            break;
         case "setup":
            e.deferReply(true).queue();
            Bukkit.getScheduler().runTaskAsynchronously(this.plugin, () -> {
               try {
                  this.setup();
                  e.getHook().sendMessage("✅ Salons vérifiés.").queue();
               } catch (Exception var3) {
                  e.getHook().sendMessage("❌ " + var3.getMessage()).queue();
               }

               Bukkit.getScheduler().runTask(this.plugin, this::requestSync);
            });
      }
   }

   public void onButtonInteraction(ButtonInteractionEvent e) {
      String id = e.getComponentId();
      if (id.startsWith("link:") || id.startsWith("linkno:")) {
         String token = id.substring(id.indexOf(58) + 1);
         String[] pending = this.pendingLinks.get(token);
         if (pending == null) {
            e.reply("Cette demande a expiré. Recommence depuis le jeu.").setEphemeral(true).queue();
         } else if (!e.getUser().getId().equals(pending[1])) {
            e.reply("Ce bouton n'est pas pour toi.").setEphemeral(true).queue();
         } else {
            this.pendingLinks.remove(token);
            UUID uuid = UUID.fromString(pending[0]);
            if (id.startsWith("linkno:")) {
               ((MessageEditCallbackAction)((MessageEditCallbackAction)e.editMessage("❌ Liaison refusée.").setEmbeds(new MessageEmbed[0]))
                     .setComponents(new MessageTopLevelComponent[0]))
                  .queue();
               this.tell(uuid, "&cLa liaison Discord a été refusée.");
            } else {
               this.data.set("links." + uuid, pending[1]);
               this.saveData();
               ((MessageEditCallbackAction)((MessageEditCallbackAction)e.editMessage("✅ Compte lié ! Tes rôles de clan vont être mis à jour.")
                        .setEmbeds(new MessageEmbed[0]))
                     .setComponents(new MessageTopLevelComponent[0]))
                  .queue();
               this.tell(uuid, "&a✔ Ton compte Discord est lié : tes rôles de clan arrivent sur le Discord.");
               Bukkit.getScheduler().runTask(this.plugin, this::requestSync);
            }
         }
      } else if (id.equals("linkcode")) {
         e.reply(this.codeMessage(e.getUser().getId())).setEphemeral(true).queue();
      } else if (id.equals("rules:accept")) {
         try {
            Role r = this.playerRole();
            if (e.getMember() != null && !e.getMember().getRoles().contains(r)) {
               this.guild.addRoleToMember(e.getMember(), r).queue();
            }

            e.reply("✅ Merci ! Tu as maintenant le rôle " + r.getAsMention() + ". Bon jeu sur WINDY_MC.").setEphemeral(true).queue();
         } catch (Exception var6) {
            e.reply("✅ Merci d'avoir lu le règlement !").setEphemeral(true).queue();
         }
      } else if (!id.equals("ticket:bug") && !id.equals("ticket:cheat")) {
         if (id.startsWith("ticketclose:")) {
            String authorId = id.substring("ticketclose:".length());
            boolean staff = e.getMember() != null
               && (e.getMember().getRoles().contains(this.staffRole()) || e.getMember().hasPermission(new Permission[]{Permission.ADMINISTRATOR}));
            if (!staff && !e.getUser().getId().equals(authorId)) {
               e.reply("Seul le staff ou l'auteur peut fermer ce ticket.").setEphemeral(true).queue();
               return;
            }

            e.reply("\ud83d\udd12 Ticket fermé par " + e.getUser().getAsMention() + ". Suppression dans 10 secondes.").queue();
            this.staffLog("\ud83c\udfab Ticket fermé", e.getChannel().getName() + " fermé par " + e.getUser().getName());
            e.getChannel().delete().queueAfter(10L, TimeUnit.SECONDS);
         }
      } else {
         boolean cheat = id.endsWith("cheat");
         Builder mb = Modal.create(cheat ? "ticketform:cheat" : "ticketform:bug", cheat ? "Signaler un tricheur" : "Signaler un bug");
         mb.addComponents(
            new ModalTopLevelComponent[]{
               Label.of("Ton pseudo Minecraft", TextInput.create("mc", TextInputStyle.SHORT).setRequired(true).setMaxLength(32).build())
            }
         );
         if (cheat) {
            mb.addComponents(
               new ModalTopLevelComponent[]{
                  Label.of("Pseudo du joueur suspecté", TextInput.create("suspect", TextInputStyle.SHORT).setRequired(true).setMaxLength(32).build())
               }
            );
         }

         mb.addComponents(
            new ModalTopLevelComponent[]{
               Label.of(
                  cheat ? "Que s'est-il passé ?" : "Décris le bug",
                  TextInput.create("desc", TextInputStyle.PARAGRAPH)
                     .setRequired(true)
                     .setMaxLength(1500)
                     .setPlaceholder(cheat ? "Où, quand, quelle triche..." : "Ce que tu faisais, ce qui s'est passé, où...")
                     .build()
               )
            }
         );
         e.replyModal(mb.build()).queue();
      }
   }

   public void onModalInteraction(ModalInteractionEvent e) {
      if (e.getModalId().equals("patchnote")) {
         TextChannel notes = this.ch("patchnotes");
         if (notes == null) {
            e.reply("❌ Salon des patch notes introuvable : tape /setup.").setEphemeral(true).queue();
         } else {
            e.deferReply(true).queue();
            String v = value(e, "version");
            String t = value(e, "titre");
            String txt = value(e, "texte");

            try {
               this.postPatchNote(notes, v, t, txt);
               e.getHook().sendMessage("✅ Patch note publiée dans " + notes.getAsMention() + ".").queue();
               this.staffLog("\ud83d\udcf0 Patch note", "Version " + v + " publiée par " + e.getUser().getName());
            } catch (Exception var9) {
               e.getHook().sendMessage("❌ " + var9.getMessage()).queue();
            }
         }
      } else if (e.getModalId().startsWith("ticketform:")) {
         boolean cheat = e.getModalId().endsWith("cheat");
         String mc = value(e, "mc");
         String suspect = value(e, "suspect");
         String desc = value(e, "desc");
         int n = this.data.getInt("counters.tickets") + 1;
         this.data.set("counters.tickets", n);
         this.saveData();
         String name = (cheat ? "\ud83d\udea8triche-" : "\ud83d\udc1ebug-") + String.format("%04d", n);
         e.deferReply(true).queue();
         Role staff = this.staffRole();
         this.mainCategory()
            .createTextChannel(name)
            .addPermissionOverride(this.guild.getPublicRole(), List.of(), List.of(Permission.VIEW_CHANNEL))
            .addMemberPermissionOverride(
               e.getUser().getIdLong(), List.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_ATTACH_FILES), List.of()
            )
            .addPermissionOverride(staff, List.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_ATTACH_FILES), List.of())
            .addMemberPermissionOverride(
               this.guild.getSelfMember().getIdLong(), List.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MANAGE_CHANNEL), List.of()
            )
            .queue(
               ch -> {
                  EmbedBuilder eb = new EmbedBuilder()
                     .setTitle(cheat ? "\ud83d\udea8 Signalement de triche #" + n : "\ud83d\udc1e Bug #" + n)
                     .setColor(cheat ? new Color(220, 60, 60) : GOLD)
                     .addField("Auteur", e.getUser().getAsMention() + " (MC : " + mc + ")", false);
                  if (cheat) {
                     eb.addField("Joueur suspecté", suspect, false);
                  }

                  eb.addField("Description", desc, false).setTimestamp(Instant.now());
                  ((MessageCreateAction)((MessageCreateAction)ch.sendMessage(staff.getAsMention() + " " + e.getUser().getAsMention())
                           .setEmbeds(new MessageEmbed[]{eb.build()}))
                        .setComponents(
                           new MessageTopLevelComponent[]{
                              ActionRow.of(Button.danger("ticketclose:" + e.getUser().getId(), "\ud83d\udd12 Fermer le ticket"), new ActionRowChildComponent[0])
                           }
                        ))
                     .queue();
                  e.getHook().sendMessage("✅ Ticket créé : " + ch.getAsMention()).queue();
                  this.staffLog(
                     cheat ? "\ud83d\udea8 Nouveau ticket triche" : "\ud83d\udc1e Nouveau ticket bug",
                     ch.getAsMention() + " par " + e.getUser().getName() + (cheat ? " — suspect : **" + suspect + "**" : "")
                  );
               },
               err -> e.getHook().sendMessage("❌ Impossible de créer le ticket : " + err.getMessage()).queue()
            );
      }
   }

   private static String value(ModalInteractionEvent e, String id) {
      ModalMapping m = e.getValue(id);
      return m == null ? "" : m.getAsString();
   }

   private String codeMessage(String discordId) {
      String code;
      do {
         code = String.format("%06d", new Random().nextInt(1000000));
      } while (this.linkCodes.containsKey(code));

      this.linkCodes.put(code, new String[]{discordId, String.valueOf(System.currentTimeMillis() + 600000L)});
      return "\ud83d\udd17 Ton code : **" + code + "**\nEn jeu, tape `/lier " + code + "` (valable 10 minutes).";
   }

   public void linkWithCode(Player p, String code) {
      if (!this.ready()) {
         this.plugin.msg(p, "&cLe bot Discord n'est pas connecté.");
      } else {
         String[] v = this.linkCodes.remove(code.trim());
         if (v != null && Long.parseLong(v[1]) >= System.currentTimeMillis()) {
            this.data.set("links." + p.getUniqueId(), v[0]);
            this.saveData();
            this.plugin.msg(p, "&a✔ Ton compte Discord est lié : tes rôles de clan arrivent sur le Discord.");
            this.requestSync();
            this.guild
               .retrieveMemberById(v[0])
               .queue(
                  m -> m.getUser()
                     .openPrivateChannel()
                     .flatMap(dm -> dm.sendMessage("✅ Ton compte Discord est lié au joueur **" + p.getName() + "**."))
                     .queue(null, err -> {}),
                  err -> {}
               );
         } else {
            this.plugin.msg(p, "&cCode invalide ou expiré. Demande-en un nouveau sur Discord (salon liaison, ou /lier).");
         }
      }
   }

   public String inviteHint() {
      return this.cfg() == null ? "" : this.cfg().getString("invite-link", "");
   }

   public String discordNameOf(UUID uuid) {
      String id = this.linkedId(uuid);
      if (id != null && this.guild != null) {
         Member m = this.guild.getMemberById(id);
         return m == null ? null : m.getUser().getName();
      } else {
         return null;
      }
   }

   private record ClanSnap(String id, String name, String color, Map<String, Rank> members) {
   }
}
