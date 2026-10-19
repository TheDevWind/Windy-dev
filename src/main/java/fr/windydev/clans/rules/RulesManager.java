package fr.windydev.clans.rules;

import fr.windydev.clans.WindyClans;
import fr.windydev.clans.gui.Menu;
import fr.windydev.clans.util.Glyphs;
import fr.windydev.clans.util.ItemBuilder;
import fr.windydev.clans.util.Text;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.inventory.Book;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

/**
 * Règlement en articles (MAJ 1.21.0) : lecture en jeu (/reglement), acceptation obligatoire à la première
 * connexion et après chaque modification importante (art. 2.2), texte fourni au bot Discord.
 */
public final class RulesManager implements Listener, CommandExecutor, TabCompleter {
   public record Article(String name, List<String> lines) {
   }

   public record Part(String name, String emoji, Material icon, List<Article> articles) {
   }

   private static final Set<String> ALLOWED = Set.of(
      "reglement", "règlement", "rules", "regles", "règles", "login", "l", "log", "register", "reg", "logout", "changepassword", "changepass", "cp"
   );
   private static final TextColor INK = TextColor.color(0x3A3150);
   private static final TextColor NUM = TextColor.color(0x5B4BA8);
   private static final int PAGE_W = 113;
   private static final int PAGE_LINES = 14;
   private final WindyClans plugin;
   private final File file;
   private final File dataFile;
   private YamlConfiguration data = new YamlConfiguration();
   private final List<Part> parts = new ArrayList<>();
   private final List<String> summary = new ArrayList<>();
   private final Map<UUID, Long> reading = new HashMap<>();
   private final Map<UUID, Long> lastPrompt = new HashMap<>();
   private final java.util.Set<UUID> waiting = java.util.concurrent.ConcurrentHashMap.newKeySet();
   private final java.util.Set<UUID> authed = java.util.concurrent.ConcurrentHashMap.newKeySet();
   private String title = "Règlement";
   private String date = "";
   private String intro = "";
   private int version = 1;
   private boolean enabled = true;

   public RulesManager(WindyClans plugin) {
      this.plugin = plugin;
      this.file = new File(plugin.getDataFolder(), "reglement.yml");
      this.dataFile = new File(plugin.getDataFolder(), "reglement-data.yml");
   }

   // ------------------------------------------------------------------ chargement

   public void load() {
      if (!this.file.exists()) {
         this.plugin.saveResource("reglement.yml", false);
      }

      YamlConfiguration y = YamlConfiguration.loadConfiguration(this.file);
      if (y.getInt("config-version", 1) < 2) {
         // ancien règlement en 6 sections : gardé à côté, remplacé par la version en articles
         File old = new File(this.plugin.getDataFolder(), "reglement-ancien.yml");
         if (old.exists()) {
            old.delete();
         }

         this.file.renameTo(old);
         this.plugin.saveResource("reglement.yml", true);
         y = YamlConfiguration.loadConfiguration(this.file);
         this.plugin.getLogger().info("reglement.yml remplacé par le règlement en articles (ancienne version : reglement-ancien.yml).");
      }

      this.enabled = this.plugin.getConfig().getBoolean("rules.require-accept", true);
      this.title = y.getString("titre", "Règlement");
      this.date = y.getString("date", "");
      this.intro = y.getString("introduction", "");
      this.version = Math.max(1, y.getInt("version", 1));
      this.summary.clear();
      this.summary.addAll(y.getStringList("resume"));
      this.parts.clear();

      for (Map<?, ?> t : y.getMapList("titres")) {
         List<Article> arts = new ArrayList<>();
         Object list = t.get("articles");
         if (list instanceof List<?> l) {
            for (Object o : l) {
               if (o instanceof Map<?, ?> a) {
                  String txt = String.valueOf(a.get("texte") == null ? "" : a.get("texte"));
                  List<String> lines = new ArrayList<>();

                  for (String line : txt.split("\n")) {
                     if (!line.isBlank()) {
                        lines.add(line.trim());
                     }
                  }

                  arts.add(new Article(String.valueOf(a.get("nom")), lines));
               }
            }
         }

         Material icon = Material.matchMaterial(String.valueOf(t.get("icone") == null ? "BOOK" : t.get("icone")));
         this.parts
            .add(
               new Part(
                  String.valueOf(t.get("nom")),
                  t.get("emoji") == null ? "📜" : String.valueOf(t.get("emoji")),
                  icon == null || !icon.isItem() ? Material.BOOK : icon,
                  arts
               )
            );
      }

      this.data = YamlConfiguration.loadConfiguration(this.dataFile);
      this.waiting.clear();

      for (Player p : Bukkit.getOnlinePlayers()) {
         if (this.accepted(p.getUniqueId()) < this.version) {
            this.waiting.add(p.getUniqueId());
         }
      }

      this.plugin.getLogger().info("Règlement : " + this.parts.size() + " titres, " + this.articleCount() + " articles (version " + this.version + ").");
   }

   public void start() {
      Bukkit.getScheduler().runTaskTimer(this.plugin, () -> {
         long now = System.currentTimeMillis();

         for (Player p : Bukkit.getOnlinePlayers()) {
            if (this.pending(p) && this.ready(p) && p.getOpenInventory().getType() == InventoryType.CRAFTING) {
               long r = this.reading.getOrDefault(p.getUniqueId(), 0L);
               long last = this.lastPrompt.getOrDefault(p.getUniqueId(), 0L);
               if (now - r > 120000L && now - last > 4000L) {
                  this.prompt(p);
               }
            }
         }
      }, 60L, 40L);
   }

   public int version() {
      return this.version;
   }

   public int articleCount() {
      return this.parts.stream().mapToInt(x -> x.articles().size()).sum();
   }

   public List<Part> parts() {
      return this.parts;
   }

   public String title() {
      return this.title;
   }

   public String date() {
      return this.date;
   }

   public String intro() {
      return this.intro;
   }

   // ------------------------------------------------------------------ acceptation

   public int accepted(UUID u) {
      return this.data.getInt("accepted." + u, 0);
   }

   /** Le joueur doit-il encore accepter cette version du règlement ? */
   public boolean pending(Player p) {
      return this.enabled && this.waiting.contains(p.getUniqueId());
   }

   /** Connecté (AuthMe) et pack de ressources chargé : on peut lui ouvrir un menu. */
   private boolean ready(Player p) {
      return this.authenticated(p) && (this.plugin.pack() == null || this.plugin.pack().settled(p));
   }

   private boolean authenticated(Player p) {
      if (this.authed.contains(p.getUniqueId())) {
         return true;
      } else if (authMe(p)) {
         this.authed.add(p.getUniqueId());
         return true;
      } else {
         return false;
      }
   }

   private static boolean authMe(Player p) {
      Plugin am = Bukkit.getPluginManager().getPlugin("AuthMe");
      if (am != null && am.isEnabled()) {
         try {
            Class<?> api = Class.forName("fr.xephi.authme.api.v3.AuthMeApi", true, am.getClass().getClassLoader());
            Object inst = api.getMethod("getInstance").invoke(null);
            Method m = api.getMethod("isAuthenticated", Player.class);
            return (Boolean)m.invoke(inst, p);
         } catch (Throwable var5) {
            return true;
         }
      } else {
         return true;
      }
   }

   /** Joueur Bedrock (Geyser/Floodgate) : pas de livre, le texte passe par le chat. */
   static boolean bedrock(Player p) {
      Plugin fg = Bukkit.getPluginManager().getPlugin("floodgate");
      if (fg != null && fg.isEnabled()) {
         try {
            Class<?> api = Class.forName("org.geysermc.floodgate.api.FloodgateApi", true, fg.getClass().getClassLoader());
            Object inst = api.getMethod("getInstance").invoke(null);
            return (Boolean)api.getMethod("isFloodgatePlayer", UUID.class).invoke(inst, p.getUniqueId());
         } catch (Throwable var4) {
         }
      }

      return p.getName().startsWith(".");
   }

   public void accept(Player p) {
      if (this.pending(p)) {
         this.data.set("accepted." + p.getUniqueId(), this.version);
         this.waiting.remove(p.getUniqueId());
         this.data.set("names." + p.getUniqueId(), p.getName());
         this.data.set("dates." + p.getUniqueId(), System.currentTimeMillis());

         try {
            this.data.save(this.dataFile);
         } catch (Exception var3) {
            this.plugin.getLogger().warning("reglement-data.yml : " + var3.getMessage());
         }

         p.closeInventory();
         this.reading.remove(p.getUniqueId());
         this.plugin.msg(p, "&a✔ Merci ! Tu as accepté le règlement. &7Il reste consultable avec &f/reglement&7. Bon jeu !");
         p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7F, 1.4F);
         this.plugin.getLogger().info(p.getName() + " a accepté le règlement (version " + this.version + ").");
         Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
            if (p.isOnline() && this.plugin.profiles().mustChoose(p) && p.getOpenInventory().getType() == InventoryType.CRAFTING) {
               this.plugin.msg(p, "&dBienvenue ! &7Choisis ta race : chacune donne des bonus qui grandissent à chaque Éveil.");
               this.plugin.menus().races(p);
            }
         }, 30L);
      } else {
         this.plugin.msg(p, "&7Tu as déjà accepté la version actuelle du règlement.");
      }
   }

   private void prompt(Player p) {
      this.lastPrompt.put(p.getUniqueId(), System.currentTimeMillis());
      this.acceptMenu(p);
      boolean again = this.accepted(p.getUniqueId()) > 0;
      p.sendMessage(
         Text.c(
               this.plugin.settings().prefix
                  + (again ? "&eLe règlement a changé. " : "&dBienvenue ! ")
                  + "&7Pour jouer, lis et accepte le règlement : "
            )
            .append(
               Text.c("&a&l[J'ACCEPTE]")
                  .clickEvent(ClickEvent.runCommand("/reglement accepter"))
                  .hoverEvent(HoverEvent.showText(Text.c("&aJ'ai lu et j'accepte le règlement")))
            )
            .append(Text.c(" "))
            .append(Text.c("&e&l[LIRE]").clickEvent(ClickEvent.runCommand("/reglement")).hoverEvent(HoverEvent.showText(Text.c("&7Lire le règlement"))))
      );
   }

   public void acceptMenu(Player p) {
      Menu m = new Menu(p, "trio", 3, "&5Règlement de WINDY_MC");
      List<String> lore = new ArrayList<>();
      lore.add("&7" + this.parts.size() + " titres, " + this.articleCount() + " articles.");
      lore.add("");
      lore.add("&eClic : lire le règlement");
      m.set(11, ItemBuilder.of(Material.WRITABLE_BOOK).name("&eLire le règlement").lore(lore).build(), e -> {
         this.reading.put(p.getUniqueId(), System.currentTimeMillis());
         this.menu(p);
      });
      List<String> key = new ArrayList<>();
      key.add("&7L'essentiel :");

      for (String s : this.summary) {
         key.add("&f• " + s);
      }

      key.add("");
      key.add("&8Accepter, c'est s'engager à respecter");
      key.add("&8tout le règlement (art. 2).");
      m.set(13, ItemBuilder.of(Material.PAPER).name("&dPoints clés").lore(key).build());
      m.set(
         15,
         ItemBuilder.of(Material.LIME_DYE).name("&a✔ J'ai lu et j'accepte le règlement").lore("&7Version " + this.version + (this.date.isEmpty() ? "" : " du " + this.date)).glow(true).build(),
         e -> this.accept(p)
      );
      m.set(
         22,
         ItemBuilder.of(Material.BARRIER).name("&cRefuser et quitter").lore("&8Il faut accepter le règlement", "&8pour jouer sur le serveur.").build(),
         e -> p.kick(Text.c("&cTu dois accepter le règlement pour jouer sur WINDY_MC.\n&7Tu peux le lire sur Discord, salon règlement."))
      );
      m.fillBorder();
      m.open(p);
   }

   // ------------------------------------------------------------------ lecture

   public void menu(Player p) {
      Menu m = new Menu(p, "list", 6, "&5Règlement de WINDY_MC");
      int[] slots = new int[]{10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};

      for (int i = 0; i < this.parts.size() && i < slots.length; i++) {
         Part part = this.parts.get(i);
         List<String> lore = new ArrayList<>();

         for (Article a : part.articles()) {
            lore.add("&7" + a.name());
         }

         lore.add("");
         lore.add("&eClic : lire");
         int idx = i;
         m.set(slots[i], ItemBuilder.of(part.icon()).amount(i + 1).name("&d" + part.name()).lore(lore).build(), e -> this.read(p, idx));
      }

      List<String> head = new ArrayList<>();
      head.add("&7Version &f" + this.version + (this.date.isEmpty() ? "" : " &7du &f" + this.date));
      head.add("");

      for (String w : wrap(Text.strip(this.intro), 36)) {
         head.add("&8" + w);
      }

      m.set(4, ItemBuilder.of(Material.BOOK).name("&5" + Text.strip(this.title).replace("📜", "").trim()).lore(head).build());
      if (this.pending(p)) {
         m.set(49, ItemBuilder.of(Material.LIME_DYE).name("&a✔ J'accepte le règlement").glow(true).build(), e -> this.accept(p));
         m.set(45, ItemBuilder.of(Material.ARROW).name("&7Retour").build(), e -> this.acceptMenu(p));
      } else {
         m.set(49, ItemBuilder.of(Material.BARRIER).name("&cFermer").build(), e -> p.closeInventory());
      }

      m.fillBorder();
      m.open(p);
   }

   /** Ouvre un titre : livre sur Java, chat sur Bedrock. */
   public void read(Player p, int idx) {
      if (idx >= 0 && idx < this.parts.size()) {
         Part part = this.parts.get(idx);
         this.reading.put(p.getUniqueId(), System.currentTimeMillis());
         if (bedrock(p)) {
            p.closeInventory();
            p.sendMessage(Text.c("&5&l" + part.name()));

            for (Article a : part.articles()) {
               p.sendMessage(Text.c("&d" + a.name()));

               for (String line : a.lines()) {
                  p.sendMessage(Text.c("&7" + line.replaceAll("\\*\\*(.+?)\\*\\*", "&f$1&7")));
               }
            }

            p.sendMessage(
               Text.c(this.pending(p) ? "&8Ensuite : " : "")
                  .append(this.pending(p) ? Text.c("&a&l[J'ACCEPTE]").clickEvent(ClickEvent.runCommand("/reglement accepter")) : Component.empty())
                  .append(Text.c(" &e[SOMMAIRE]").clickEvent(ClickEvent.runCommand("/reglement")))
            );
         } else {
            p.closeInventory();
            p.openBook(this.book(part));
         }
      }
   }

   private Book book(Part part) {
      List<Component> pages = new ArrayList<>();
      List<Component> lines = new ArrayList<>();
      lines.addAll(this.wrapComponent("**" + part.name() + "**", NUM));
      lines.add(Component.empty());

      for (Article a : part.articles()) {
         List<Component> block = new ArrayList<>(this.wrapComponent("**" + a.name() + "**", NUM));

         for (String l : a.lines()) {
            block.addAll(this.wrapComponent(l, INK));
         }

         block.add(Component.empty());
         if (lines.size() > 2 && lines.size() + Math.min(block.size(), 4) > PAGE_LINES) {
            pages.add(join(lines));
            lines.clear();
         }

         for (Component c : block) {
            if (lines.size() >= PAGE_LINES) {
               pages.add(join(lines));
               lines.clear();
            }

            if (!lines.isEmpty() || !c.equals(Component.empty())) {
               lines.add(c);
            }
         }
      }

      if (!lines.isEmpty()) {
         pages.add(join(lines));
      }

      // le titre d'un livre est limité à 32 caractères : "Titre III" suffit
      String t = part.name().split(" — ")[0].trim();
      return Book.book(Component.text(t.length() > 32 ? t.substring(0, 32) : t), Component.text("WINDY_MC"), pages.size() > 100 ? pages.subList(0, 100) : pages);
   }

   private static Component join(List<Component> lines) {
      Component c = Component.empty();

      for (int i = 0; i < lines.size(); i++) {
         c = c.append(lines.get(i));
         if (i < lines.size() - 1) {
            c = c.append(Component.newline());
         }
      }

      return c;
   }

   /** Coupe une ligne "**gras** texte" en lignes de livre (largeur en pixels de la police du jeu). */
   private List<Component> wrapComponent(String text, TextColor color) {
      List<String[]> words = new ArrayList<>();
      boolean bold = false;

      for (String chunk : text.split("\\*\\*", -1)) {
         for (String w : chunk.split(" ")) {
            if (!w.isEmpty()) {
               words.add(new String[]{w, bold ? "1" : "0"});
            }
         }

         bold = !bold;
      }

      List<Component> out = new ArrayList<>();
      Component line = Component.empty();
      int width = 0;
      int space = Glyphs.width(32);

      for (String[] w : words) {
         boolean b = w[1].equals("1");
         int ww = Glyphs.width(w[0]) + (b ? w[0].codePointCount(0, w[0].length()) : 0);
         if (width > 0 && width + space + ww > PAGE_W) {
            out.add(line);
            line = Component.empty();
            width = 0;
         }

         if (ww > PAGE_W) {
            // mot trop long : on le coupe
            String rest = w[0];

            while (Glyphs.width(rest) > PAGE_W) {
               int cut = 1;

               while (cut < rest.length() && Glyphs.width(rest.substring(0, cut + 1)) <= PAGE_W - 2) {
                  cut++;
               }

               if (width > 0) {
                  out.add(line);
                  line = Component.empty();
               }

               out.add(styled(rest.substring(0, cut), b, color));
               rest = rest.substring(cut);
               width = 0;
            }

            line = styled(rest, b, color);
            width = Glyphs.width(rest);
         } else {
            if (width > 0) {
               line = line.append(Component.text(" ", color));
               width += space;
            }

            line = line.append(styled(w[0], b, color));
            width += ww;
         }
      }

      if (width > 0) {
         out.add(line);
      }

      return out;
   }

   private static Component styled(String s, boolean bold, TextColor color) {
      Component c = Component.text(s, bold ? NUM : color);
      return bold ? c.decorate(TextDecoration.BOLD) : c;
   }

   static List<String> wrap(String text, int max) {
      List<String> out = new ArrayList<>();
      StringBuilder cur = new StringBuilder();

      for (String w : text.split(" ")) {
         if (cur.length() > 0 && cur.length() + 1 + w.length() > max) {
            out.add(cur.toString());
            cur.setLength(0);
         }

         if (cur.length() > 0) {
            cur.append(' ');
         }

         cur.append(w);
      }

      if (cur.length() > 0) {
         out.add(cur.toString());
      }

      return out;
   }

   // ------------------------------------------------------------------ Discord

   /** Blocs (titre, texte) pour le bot : un par titre, coupé par article au-delà de "max" caractères. */
   public List<String[]> discordBlocks(int max) {
      List<String[]> out = new ArrayList<>();

      for (Part part : this.parts) {
         String head = part.emoji() + " " + part.name();
         StringBuilder sb = new StringBuilder();
         int n = 0;

         for (Article a : part.articles()) {
            String block = "**" + a.name() + "**\n" + String.join("\n", a.lines()) + "\n\n";
            if (sb.length() > 0 && sb.length() + block.length() > max) {
               out.add(new String[]{n == 0 ? head : head + " (suite)", sb.toString().trim()});
               sb.setLength(0);
               n++;
            }

            if (block.length() > max) {
               block = block.substring(0, max - 3) + "...";
            }

            sb.append(block);
         }

         if (sb.length() > 0) {
            out.add(new String[]{n == 0 ? head : head + " (suite)", sb.toString().trim()});
         }
      }

      return out;
   }

   // ------------------------------------------------------------------ blocages tant que non accepté

   @EventHandler(
      priority = EventPriority.LOWEST
   )
   public void onJoin(PlayerJoinEvent e) {
      UUID u = e.getPlayer().getUniqueId();
      this.lastPrompt.remove(u);
      this.authed.remove(u);
      if (this.accepted(u) < this.version) {
         this.waiting.add(u);
      } else {
         this.waiting.remove(u);
      }
   }

   @EventHandler
   public void onQuit(PlayerQuitEvent e) {
      UUID u = e.getPlayer().getUniqueId();
      this.reading.remove(u);
      this.lastPrompt.remove(u);
      this.authed.remove(u);
      this.waiting.remove(u);
   }

   @EventHandler
   public void onClose(InventoryCloseEvent e) {
      if (e.getPlayer() instanceof Player p
         && e.getReason() == InventoryCloseEvent.Reason.PLAYER
         && this.pending(p)
         && e.getInventory().getHolder() instanceof Menu
         && !this.reading.containsKey(p.getUniqueId())) {
         Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
            if (p.isOnline() && this.pending(p) && p.getOpenInventory().getType() == InventoryType.CRAFTING) {
               this.prompt(p);
            }
         }, 30L);
      }
   }

   private boolean blocked(Player p) {
      return this.pending(p) && this.authenticated(p);
   }

   private void tell(Player p) {
      long now = System.currentTimeMillis();
      if (now - this.lastPrompt.getOrDefault(p.getUniqueId(), 0L) > 1500L) {
         p.sendActionBar(Text.c("&eAccepte d'abord le règlement : &f/reglement"));
      }
   }

   @EventHandler(
      priority = EventPriority.LOWEST,
      ignoreCancelled = true
   )
   public void onChat(AsyncChatEvent e) {
      if (this.blocked(e.getPlayer())) {
         e.setCancelled(true);
         e.getPlayer().sendMessage(Text.c("&eAccepte d'abord le règlement pour écrire dans le chat : &f/reglement"));
      }
   }

   @EventHandler(
      priority = EventPriority.LOWEST,
      ignoreCancelled = true
   )
   public void onCommand(PlayerCommandPreprocessEvent e) {
      if (this.blocked(e.getPlayer())) {
         String cmd = e.getMessage().substring(1).split(" ")[0].toLowerCase(Locale.ROOT);
         int colon = cmd.indexOf(58);
         if (colon >= 0) {
            cmd = cmd.substring(colon + 1);
         }

         if (!ALLOWED.contains(cmd)) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(Text.c("&eAccepte d'abord le règlement : &f/reglement"));
         }
      }
   }

   /** Le joueur peut bouger, mais s'il marche sans menu ouvert, l'écran d'acceptation revient. */
   @EventHandler(
      priority = EventPriority.MONITOR,
      ignoreCancelled = true
   )
   public void onMove(PlayerMoveEvent e) {
      if ((e.getFrom().getBlockX() != e.getTo().getBlockX() || e.getFrom().getBlockZ() != e.getTo().getBlockZ()) && this.blocked(e.getPlayer())) {
         Player p = e.getPlayer();
         long now = System.currentTimeMillis();
         if (p.getOpenInventory().getType() == InventoryType.CRAFTING
            && now - this.lastPrompt.getOrDefault(p.getUniqueId(), 0L) > 6000L
            && now - this.reading.getOrDefault(p.getUniqueId(), 0L) > 3000L
            && this.ready(p)) {
            this.prompt(p);
         }
      }
   }

   @EventHandler(
      priority = EventPriority.LOWEST,
      ignoreCancelled = true
   )
   public void onBreak(BlockBreakEvent e) {
      if (this.blocked(e.getPlayer())) {
         e.setCancelled(true);
         this.tell(e.getPlayer());
      }
   }

   @EventHandler(
      priority = EventPriority.LOWEST,
      ignoreCancelled = true
   )
   public void onPlace(BlockPlaceEvent e) {
      if (this.blocked(e.getPlayer())) {
         e.setCancelled(true);
         this.tell(e.getPlayer());
      }
   }

   @EventHandler(
      priority = EventPriority.LOWEST
   )
   public void onInteract(PlayerInteractEvent e) {
      if (this.blocked(e.getPlayer())) {
         e.setCancelled(true);
      }
   }

   @EventHandler(
      priority = EventPriority.LOWEST,
      ignoreCancelled = true
   )
   public void onDrop(PlayerDropItemEvent e) {
      if (this.blocked(e.getPlayer())) {
         e.setCancelled(true);
      }
   }

   @EventHandler(
      priority = EventPriority.LOWEST,
      ignoreCancelled = true
   )
   public void onHurt(EntityDamageEvent e) {
      if (e.getEntity() instanceof Player p && this.blocked(p)) {
         e.setCancelled(true);
      } else if (e instanceof EntityDamageByEntityEvent ed && ed.getDamager() instanceof Player a && this.blocked(a)) {
         e.setCancelled(true);
      }
   }

   // ------------------------------------------------------------------ commande

   @Override
   public boolean onCommand(@NotNull CommandSender s, @NotNull Command cmd, @NotNull String label, @NotNull String[] args) {
      String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
      if (sub.equals("recharger") || sub.equals("reload")) {
         if (s.hasPermission("windyclans.admin")) {
            int before = this.version;
            this.load();
            if (this.plugin.bot() != null) {
               this.plugin.bot().republishRules();
            }

            this.plugin.msg(s, "&aRèglement rechargé (version " + this.version + ")." + (this.version > before ? " &eNouvelle version : tout le monde devra l'accepter." : ""));
         }

         return true;
      } else if (sub.equals("joueur") && s.hasPermission("windyclans.admin") && args.length > 1) {
         Player t = Bukkit.getPlayer(args[1]);
         UUID u = t != null ? t.getUniqueId() : Bukkit.getOfflinePlayer(args[1]).getUniqueId();
         int v = this.accepted(u);
         long at = this.data.getLong("dates." + u, 0L);
         this.plugin.msg(s, "&7" + args[1] + " : " + (v == 0 ? "&cjamais accepté" : "&aversion " + v + (at > 0L ? " le " + new java.text.SimpleDateFormat("dd/MM/yyyy HH:mm").format(new java.util.Date(at)) : "")));
         return true;
      } else if (!(s instanceof Player p)) {
         this.plugin.msg(s, "&7Règlement version " + this.version + " : " + this.parts.size() + " titres, " + this.articleCount() + " articles.");
         return true;
      } else if (sub.equals("accepter") || sub.equals("accept") || sub.equals("ok")) {
         this.accept(p);
         return true;
      } else {
         if (!sub.isEmpty()) {
            try {
               int n = Integer.parseInt(sub);
               this.read(p, n - 1);
               return true;
            } catch (NumberFormatException var9) {
            }
         }

         if (this.pending(p) && sub.isEmpty() && !this.reading.containsKey(p.getUniqueId())) {
            this.acceptMenu(p);
         } else {
            this.menu(p);
         }

         return true;
      }
   }

   @Override
   public List<String> onTabComplete(@NotNull CommandSender s, @NotNull Command c, @NotNull String l, @NotNull String[] args) {
      if (args.length == 1) {
         List<String> opts = new ArrayList<>(List.of("accepter"));

         for (int i = 1; i <= this.parts.size(); i++) {
            opts.add(String.valueOf(i));
         }

         if (s.hasPermission("windyclans.admin")) {
            opts.add("recharger");
            opts.add("joueur");
         }

         return opts.stream().filter(x -> x.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
      } else {
         return List.of();
      }
   }
}
