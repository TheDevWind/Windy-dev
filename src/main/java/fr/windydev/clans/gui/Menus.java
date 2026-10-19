package fr.windydev.clans.gui;

import com.google.common.collect.ArrayListMultimap;
import fr.windydev.clans.WindyClans;
import fr.windydev.clans.bag.CauldronRitual;
import fr.windydev.clans.boss.BossManager;
import fr.windydev.clans.command.Actions;
import fr.windydev.clans.data.ClanManager;
import fr.windydev.clans.data.Settings;
import fr.windydev.clans.feature.WarManager;
import fr.windydev.clans.model.ChunkPos;
import fr.windydev.clans.model.Clan;
import fr.windydev.clans.model.Rank;
import fr.windydev.clans.model.Relation;
import fr.windydev.clans.profile.Awakening;
import fr.windydev.clans.profile.Profile;
import fr.windydev.clans.profile.ProfileManager;
import fr.windydev.clans.profile.RaceDef;
import fr.windydev.clans.profile.SkillTree;
import fr.windydev.clans.profile.Stats;
import fr.windydev.clans.ritual.CustomItems;
import fr.windydev.clans.ritual.RitualManager;
import fr.windydev.clans.util.Glyphs;
import fr.windydev.clans.util.ItemBuilder;
import fr.windydev.clans.util.Text;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.Map.Entry;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public class Menus {
   private static final int[] GRID = new int[]{10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};
   private final WindyClans plugin;
   public final Actions actions;
   private static final String[] RACE_ORDER = new String[]{"humain", "elfe", "nain", "orc", "ondin", "infernal", "vampire", "arcanien", "sylphe"};
   private static final int[] RACE_SLOTS = new int[]{0, 5, 9, 14, 18, 23, 27, 32, 36};
   private static final String[] BRANCH_COLOR = new String[]{"&c", "&9", "&d"};

   public Menus(WindyClans plugin) {
      this.plugin = plugin;
      this.actions = new Actions(plugin);
   }

   private ClanManager cm() {
      return this.plugin.clans();
   }

   private void then(Player p, Runnable action, Runnable reopen) {
      action.run();
      Bukkit.getScheduler().runTask(this.plugin, () -> {
         if (p.isOnline()) {
            reopen.run();
         }
      });
   }

   private ItemStack back() {
      return ItemBuilder.of(Material.ARROW).name("&7← Retour").build();
   }

   public static Material banner(String color) {
      return switch (color) {
         case "&a" -> Material.LIME_BANNER;
         case "&2" -> Material.GREEN_BANNER;
         case "&9", "&1" -> Material.BLUE_BANNER;
         case "&b" -> Material.LIGHT_BLUE_BANNER;
         case "&3" -> Material.CYAN_BANNER;
         case "&c", "&4" -> Material.RED_BANNER;
         case "&5" -> Material.PURPLE_BANNER;
         case "&d" -> Material.PINK_BANNER;
         case "&6" -> Material.ORANGE_BANNER;
         case "&e" -> Material.YELLOW_BANNER;
         case "&7" -> Material.LIGHT_GRAY_BANNER;
         case "&8" -> Material.GRAY_BANNER;
         default -> Material.WHITE_BANNER;
      };
   }

   private static Material wool(String color) {
      return Material.valueOf(banner(color).name().replace("_BANNER", "_WOOL"));
   }

   private String progressBar(Clan c) {
      if (c.level() >= this.plugin.settings().maxLevel) {
         return "&6▰▰▰▰▰▰▰▰▰▰ &7MAX";
      } else {
         long need = this.cm().xpForNext(c.level());
         int filled = (int)Math.min(10L, c.xp() * 10L / Math.max(1L, need));
         return "&a" + "▰".repeat(filled) + "&8" + "▰".repeat(10 - filled) + " &7" + c.xp() + "/" + need;
      }
   }

   private List<String> clanInfoLore(Clan c) {
      Settings s = this.plugin.settings();
      List<String> l = new ArrayList<>();
      l.add("&8" + c.description());
      l.add("");
      l.add("&7Chef : &f" + (c.leader() == null ? "?" : this.cm().nameOf(c.leader())));
      l.add("&7Membres : &f" + c.members().size() + "/" + s.maxMembers + " &8(" + this.cm().onlineCount(c) + " en ligne)");
      l.add("&7Niveau : &e" + c.level());
      l.add(this.progressBar(c));
      l.add("&7Claims : &f" + c.claims().size() + "/" + this.cm().maxClaims(c));
      l.add("&7Avant-postes : &f" + this.cm().outpostCount(c) + "/" + this.cm().maxOutposts(c));
      l.add("&7Capitale : &f" + (c.capital() == null ? "&cnon fondée" : c.capital().toString()));
      l.add("&7Alliés : &b" + c.allies().size() + " &7• Ennemis : &c" + c.enemies().size());
      l.add("&7Créé le : &f" + new SimpleDateFormat("dd/MM/yyyy").format(new Date(c.createdAt())));
      return l;
   }

   public void main(Player p) {
      Clan c = this.cm().of(p);
      if (c == null) {
         this.noClan(p);
      } else {
         Rank r = c.rank(p.getUniqueId());
         Settings s = this.plugin.settings();
         Menu m = new Menu(p, "main", 6, c.coloredTag() + " &f" + c.name());
         ItemStack card = ItemBuilder.of(banner(c.color())).name(c.coloredTag() + " &f" + c.name()).lore(this.clanInfoLore(c)).build();
         m.set(0, card);
         long need = this.cm().xpForNext(c.level());
         boolean maxed = c.level() >= s.maxLevel;
         int pct = maxed ? 100 : (int)Math.min(100L, c.xp() * 100L / Math.max(1L, need));
         Profile mine = this.plugin.profiles().of(p);
         m.set(
            8,
            ItemBuilder.of(Material.PLAYER_HEAD)
               .skull(p)
               .name("&dMon profil")
               .lore(
                  "&7Niveau perso &d" + mine.level(),
                  "&7Race : " + (this.plugin.profiles().race(mine) == null ? "&8aucune" : this.plugin.profiles().race(mine).colored()),
                  "",
                  "&eClic : ouvrir"
               )
               .build(),
            e -> this.profile(p)
         );
         if (m.fancy()) {
            for (int i = 1; i <= 7; i++) {
               m.hitbox(i, card, null);
            }

            m.label(27, 19, "&eNiveau " + c.level() + " &8• &7" + r.display(), 90);
            m.overlay(Glyphs.right(146, 19, maxed ? "&6MAX" : "&e" + pct + "%"));
            m.overlay(Glyphs.xpBar(pct));
            int x = -98;
            int w = 76;
            m.label(x, 40, "&7Chef &f" + (c.leader() == null ? "?" : this.cm().nameOf(c.leader())), w);
            m.label(x, 49, "&7Membres &f" + c.members().size() + "/" + s.maxMembers, w);
            m.label(x, 58, "&7En ligne &a" + this.cm().onlineCount(c), w);
            m.label(x, 67, "&7Claims &f" + c.claims().size() + "/" + this.cm().maxClaims(c), w);
            m.label(x, 76, "&7Postes &f" + this.cm().outpostCount(c) + "/" + this.cm().maxOutposts(c), w);
            m.label(x, 85, "&7Banque &6" + c.bank(), w);
            m.label(x, 94, "&7Alliés &b" + c.allies().size() + " &7Enn. &c" + c.enemies().size(), w);
            m.label(x, 110, "&8Créé " + new SimpleDateFormat("dd/MM/yy").format(new Date(c.createdAt())), w);
            m.overlay(Glyphs.centered(211, 26, c.color() + c.tag(), 28));
         }

         m.button(
            9,
            ItemBuilder.of(Material.PLAYER_HEAD)
               .skull(p)
               .name("&eMembres")
               .lore(
                  "&7" + c.members().size() + " membre(s), " + this.cm().onlineCount(c) + " en ligne",
                  "",
                  "&7Ton grade : " + r.colored(),
                  "",
                  "&eClic : gérer les membres"
               )
               .build(),
            "&fMembres",
            e -> this.members(p, 0)
         );
         m.button(
            14,
            ItemBuilder.of(Material.WRITABLE_BOOK)
               .name("&aInviter un joueur")
               .lore(
                  "&7Invite un joueur connecté",
                  "&7qui n'a pas encore de clan.",
                  "",
                  r.atLeast(s.rank("invite")) ? "&eClic : choisir" : "&cGrade requis : " + s.rank("invite").display()
               )
               .build(),
            "&fInviter",
            e -> this.invite(p, 0)
         );
         m.button(
            18,
            ItemBuilder.of(Material.FILLED_MAP)
               .name("&2Territoire")
               .lore(
                  "&7Claims : &f" + c.claims().size() + "/" + this.cm().maxClaims(c),
                  "&7Clique sur la carte pour",
                  "&7claim ou retirer des chunks.",
                  "",
                  "&eClic : ouvrir la carte"
               )
               .build(),
            "&fTerritoire",
            e -> this.map(p)
         );
         m.button(
            23,
            ItemBuilder.of(Material.RED_BED)
               .name("&6Aller à la capitale")
               .lore(c.home() == null ? "&cPas encore de capitale" : "&7Téléportation au home du clan", "", "&eClic : y aller")
               .build(),
            "&fCapitale",
            e -> {
               p.closeInventory();
               this.actions.home(p);
            }
         );
         m.button(
            27,
            s.currencyIcon().name("&bBanque du clan").lore("&7Solde : &b" + c.bank() + " " + s.currencyName, "", "&eClic : déposer / retirer").build(),
            "&fBanque",
            e -> this.bank(p)
         );
         m.button(
            32,
            ItemBuilder.of(Material.CHEST)
               .name("&6Coffre du clan")
               .lore("&7" + this.cm().chestRows(c) * 9 + " cases partagées", "&7(plus grand avec le niveau)", "", "&eClic : ouvrir")
               .build(),
            "&fCoffre",
            e -> this.actions.openChest(p)
         );
         m.button(
            36,
            ItemBuilder.of(Material.SHIELD)
               .name("&bDiplomatie")
               .lore(
                  "&7Alliés, neutres, ennemis",
                  c.allyRequests().isEmpty() ? "" : "&b" + c.allyRequests().size() + " demande(s) d'alliance !",
                  "",
                  "&eClic : ouvrir"
               )
               .glow(!c.allyRequests().isEmpty())
               .build(),
            c.allyRequests().isEmpty() ? "&fDiplomatie" : "&bDiplomatie",
            e -> this.diplomacy(p, 0)
         );
         String war = this.plugin.war().status(c);
         m.button(
            41,
            ItemBuilder.of(Material.NETHERITE_SWORD)
               .name("&4Guerre")
               .lore(war == null ? "&7Aucune guerre en cours" : war, "", "&eClic : détails")
               .glow(war != null)
               .build(),
            war == null ? "&fGuerre" : "&cGuerre !",
            e -> this.war(p)
         );
         m.set(
            45,
            ItemBuilder.of(Material.NETHER_STAR)
               .name("&6Classement des clans")
               .lore("&7Ton clan est &e#" + (this.cm().top().indexOf(c) + 1), "", "&eClic : voir le top")
               .build(),
            e -> this.top(p)
         );
         m.set(
            46,
            ItemBuilder.of(Material.BOOK).name("&fJournal d'activité").lore("&7Qui a claim, déposé, exclu...", "", "&eClic : lire").build(),
            e -> this.log(p)
         );
         boolean borders = this.plugin.borders().contains(p.getUniqueId());
         m.set(
            47,
            ItemBuilder.of(borders ? Material.LIME_STAINED_GLASS : Material.GLASS)
               .name("&aBordures des territoires")
               .lore("&7Affiche les limites des chunks", "&7en particules autour de toi.", "", borders ? "&aActivées" : "&cDésactivées")
               .build(),
            e -> this.then(p, () -> this.actions.toggleBorders(p), () -> this.main(p))
         );
         m.set(
            48,
            ItemBuilder.of(Material.ENCHANTED_BOOK)
               .name("&6Grimoire des rituels")
               .lore("&7Marteau runique et autres objets", "&7forgés sur l'autel du clan.", "", "&eClic : ouvrir")
               .build(),
            e -> this.grimoire(p, null)
         );
         m.set(49, ItemBuilder.of(Material.BARRIER).name("&cFermer").build(), e -> p.closeInventory());
         long beaten = c.bossesBeaten().size();
         m.set(
            50,
            ItemBuilder.of(Material.WITHER_SKELETON_SKULL)
               .name("&5Boss et donjons")
               .lore(
                  "&7Un boss par niveau de clan.",
                  "&7Ils peuvent lâcher les &drunes &7des rituels.",
                  "&7Les &5donjons &7se lancent aussi d'ici.",
                  "",
                  "&7Vaincus : &f" + beaten + "/" + this.plugin.bosses().bosses().size(),
                  "",
                  "&eClic : ouvrir"
               )
               .glow(this.plugin.bosses().lobbyOpen(c))
               .build(),
            e -> this.bosses(p)
         );
         boolean hud = !this.plugin.scoreboard().isHidden(p);
         m.set(
            51,
            ItemBuilder.of(Material.SPYGLASS)
               .name("&eHUD d'infos")
               .lore("&7Le petit panneau en haut à gauche.", "", hud ? "&aAffiché" : "&cMasqué", "&eClic : afficher / masquer")
               .build(),
            e -> this.then(p, () -> this.actions.toggleScoreboard(p), () -> this.main(p))
         );
         boolean chat = this.plugin.clanChatToggle().contains(p.getUniqueId());
         m.set(
            52,
            ItemBuilder.of(Material.WRITABLE_BOOK)
               .name("&aChat de clan")
               .lore(
                  "&7Activé : tes messages ne vont",
                  "&7qu'à ton clan.",
                  "&7Astuce : commence un message",
                  "&7par &f! &7pour l'envoyer au clan.",
                  "",
                  chat ? "&aActivé" : "&cDésactivé"
               )
               .glow(chat)
               .build(),
            e -> this.then(p, () -> this.actions.toggleChat(p), () -> this.main(p))
         );
         m.set(
            53,
            ItemBuilder.of(Material.COMPARATOR).name("&7Paramètres").lore("&7Couleur, description, home,", "&7capitale, quitter...").build(),
            e -> this.settings(p)
         );
         m.fillBorder();
         m.open(p);
      }
   }

   public void noClan(Player p) {
      Settings s = this.plugin.settings();
      Menu m = new Menu(p, "home", 4, s.serverName.replace("&l", ""));
      List<Clan> inv = this.cm().invitesOf(p.getUniqueId());
      m.set(
         0, ItemBuilder.of(Material.PLAYER_HEAD).skull(p).name("&f" + p.getName()).lore("&7Sans clan", "", "&eClic : mon profil").build(), e -> this.profile(p)
      );
      m.label(27, 22, "&f" + p.getName() + " &8• &7sans clan", 130);
      m.button(
         9,
         ItemBuilder.of(Material.NAME_TAG).name("&aCréer un clan").lore("&7Choisis un nom et un tag", "&7(ex. : Loups / LUP).", "", "&eClic : créer").build(),
         "&aCréer",
         e -> this.createClan(p)
      );
      m.button(
         14,
         ItemBuilder.of(Material.PAPER)
            .name("&bInvitations reçues")
            .lore("&7" + inv.size() + " invitation(s)", "", "&eClic : voir")
            .glow(!inv.isEmpty())
            .amount(Math.max(1, inv.size()))
            .build(),
         inv.isEmpty() ? "&fInvitations" : "&bInvitations",
         e -> this.invitations(p)
      );
      m.button(
         18, ItemBuilder.of(Material.NETHER_STAR).name("&6Classement des clans").lore("", "&eClic : voir le top").build(), "&fTop clans", e -> this.top(p)
      );
      boolean hud = !this.plugin.scoreboard().isHidden(p);
      m.button(
         23,
         ItemBuilder.of(Material.SPYGLASS).name("&eHUD d'infos").lore(hud ? "&aAffiché" : "&cMasqué", "", "&eClic : afficher / masquer").build(),
         "&fAffichage",
         e -> this.then(p, () -> this.actions.toggleScoreboard(p), () -> this.noClan(p))
      );
      m.set(31, ItemBuilder.of(Material.BARRIER).name("&cFermer").build(), e -> p.closeInventory());
      m.fillBorder();
      m.open(p);
   }

   public void createClan(Player p) {
      TextInput.ask(
         this.plugin,
         p,
         "Nom du clan",
         "MonClan",
         name -> TextInput.ask(this.plugin, p, "Tag (2 à 5 lettres)", name.length() >= 3 ? name.substring(0, 3).toUpperCase() : "TAG", tag -> {
            this.actions.create(p, name.trim(), tag.trim());
            if (this.cm().of(p) != null) {
               this.main(p);
            }
         }, () -> this.noClan(p)),
         () -> this.noClan(p)
      );
   }

   public void invitations(Player p) {
      Menu m = new Menu(p, "row", 3, "&fInvitations");
      List<Clan> inv = this.cm().invitesOf(p.getUniqueId());
      int i = 10;

      for (Clan c : inv) {
         if (i > 16) {
            break;
         }

         List<String> lore = new ArrayList<>(this.clanInfoLore(c));
         lore.add("");
         lore.add("&aClic gauche : rejoindre");
         lore.add("&cClic droit : refuser");
         m.set(i++, ItemBuilder.of(banner(c.color())).name(c.coloredTag() + " &f" + c.name()).lore(lore).build(), e -> {
            if (e.isRightClick()) {
               this.then(p, () -> this.actions.deny(p, c.name()), () -> this.invitations(p));
            } else {
               this.then(p, () -> this.actions.accept(p, c.name()), () -> this.main(p));
            }
         });
      }

      if (inv.isEmpty()) {
         m.set(13, ItemBuilder.of(Material.GRAY_DYE).name("&7Aucune invitation").build());
      }

      m.set(22, this.back(), e -> this.main(p));
      m.fillBorder();
      m.open(p);
   }

   public void sendInviteMessage(Player target, Clan c) {
      Component msg = Text.c(this.plugin.settings().prefix + "&7Le clan " + c.coloredTag() + " &f" + c.name() + " &7t'invite à le rejoindre ! ")
         .append(
            Text.c("&a&l[ACCEPTER]")
               .clickEvent(ClickEvent.runCommand("/clan accepter " + c.name()))
               .hoverEvent(HoverEvent.showText(Text.c("&aRejoindre " + c.name())))
         )
         .append(Text.c(" "))
         .append(Text.c("&c&l[REFUSER]").clickEvent(ClickEvent.runCommand("/clan refuser " + c.name())));
      target.sendMessage(msg);
      target.playSound(target.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0F, 1.0F);
   }

   public void members(Player p, int page) {
      Clan c = this.cm().of(p);
      if (c == null) {
         this.noClan(p);
      } else {
         Menu m = new Menu(p, "members", 6, "&fMembres » " + c.color() + c.name());
         List<Entry<UUID, Rank>> list = c.members()
            .entrySet()
            .stream()
            .sorted(
               Comparator.<Entry<UUID, Rank>, Integer>comparing(e -> e.getValue().ordinal())
                  .reversed()
                  .thenComparing(e -> this.cm().nameOf(e.getKey()).toLowerCase())
            )
            .collect(Collectors.toList());
         Rank mine = c.rank(p.getUniqueId());
         boolean fancy = m.fancy();
         int per = fancy ? 5 : GRID.length;
         int start = page * per;

         for (int i = 0; i < per && start + i < list.size(); i++) {
            Entry<UUID, Rank> entry = list.get(start + i);
            UUID u = entry.getKey();
            OfflinePlayer op = Bukkit.getOfflinePlayer(u);
            boolean online = op.isOnline();
            boolean manage = !u.equals(p.getUniqueId())
               && mine.ordinal() > entry.getValue().ordinal()
               && (mine.atLeast(this.plugin.settings().rank("promote")) || mine.atLeast(this.plugin.settings().rank("kick")));
            List<String> lore = new ArrayList<>();
            lore.add("&7Grade : " + entry.getValue().colored());
            lore.add(online ? "&a● En ligne" : "&8● Hors ligne");
            if (manage) {
               lore.add("");
               lore.add("&eClic : gérer");
            }

            ItemStack head = ItemBuilder.of(Material.PLAYER_HEAD).skull(op).name(entry.getValue().color() + this.cm().nameOf(u)).lore(lore).build();
            Consumer<InventoryClickEvent> h = manage ? e -> this.member(p, u) : null;
            if (!fancy) {
               m.set(GRID[i], head, h);
            } else {
               int row = i + 1;
               int top = 41 + 18 * i;
               boolean me = u.equals(p.getUniqueId());
               m.set(row * 9, head, h);

               for (int k = 1; k < 9; k++) {
                  m.hitbox(row * 9 + k, head, h);
               }

               if (me) {
                  m.overlay(Glyphs.goldRow(row));
               }

               m.label(27, top, (me ? "&0" : "&f") + this.cm().nameOf(u), 68);
               m.label(100, top, me ? "&0" + entry.getValue().display() : entry.getValue().colored(), 50);
               m.overlay(Glyphs.iconAt(158, top, online ? "on" : "off"));
            }
         }

         boolean more = start + per < list.size();
         ItemStack prev = ItemBuilder.of(Material.SPECTRAL_ARROW).name("&7← Page précédente").build();
         ItemStack next = ItemBuilder.of(Material.SPECTRAL_ARROW).name("&7Page suivante →").build();
         ItemStack inviteItem = ItemBuilder.of(Material.WRITABLE_BOOK).name("&aInviter un joueur").build();
         if (fancy) {
            m.set(0, inviteItem, e -> this.invite(p, 0));
            m.set(6, this.back(), e -> this.main(p));
            if (page > 0) {
               m.set(7, prev, e -> this.members(p, page - 1));
            }

            if (more) {
               m.set(8, next, e -> this.members(p, page + 1));
            }

            m.label(27, 22, "&f" + list.size() + "/" + this.plugin.settings().maxMembers + " &7membres", 86);
            Rank[] order = new Rank[]{Rank.CHEF, Rank.OFFICIER, Rank.MEMBRE, Rank.RECRUE};
            int[] tops = new int[]{40, 49, 58, 67};

            for (int k = 0; k < order.length; k++) {
               Rank rk = order[k];
               long n = c.members().values().stream().filter(x -> x == rk).count();
               m.label(-98, tops[k], rk.colored(), 60);
               m.overlay(Glyphs.right(-24, tops[k], "&f" + n));
            }

            m.label(-98, 76, "&7En ligne &a" + this.cm().onlineCount(c), 76);
            int pages = Math.max(1, (list.size() + per - 1) / per);
            m.label(-98, 85, "&7Page &f" + (page + 1) + "/" + pages, 76);
         } else {
            if (page > 0) {
               m.set(45, prev, e -> this.members(p, page - 1));
            }

            if (more) {
               m.set(53, next, e -> this.members(p, page + 1));
            }

            m.set(49, this.back(), e -> this.main(p));
            m.set(50, inviteItem, e -> this.invite(p, 0));
         }

         m.fillBorder();
         m.open(p);
      }
   }

   public void member(Player p, UUID target) {
      Clan c = this.cm().of(p);
      if (c != null && c.isMember(target)) {
         Rank tr = c.rank(target);
         String name = this.cm().nameOf(target);
         Menu m = new Menu(p, "action", 3, "&fGérer » &e" + name);
         m.set(4, ItemBuilder.of(Material.PLAYER_HEAD).skull(Bukkit.getOfflinePlayer(target)).name(tr.color() + name).lore("&7Grade : " + tr.colored()).build());
         m.set(
            10,
            ItemBuilder.of(Material.LIME_DYE).name("&aPromouvoir").lore("&7" + tr.display() + " → " + tr.next().colored()).build(),
            e -> this.then(p, () -> this.actions.promote(p, target), () -> this.member(p, target))
         );
         m.set(
            12,
            ItemBuilder.of(Material.ORANGE_DYE).name("&6Rétrograder").lore("&7" + tr.display() + " → " + tr.previous().colored()).build(),
            e -> this.then(p, () -> this.actions.demote(p, target), () -> this.member(p, target))
         );
         m.set(14, ItemBuilder.of(Material.TNT).name("&cExclure du clan").lore("&7Shift + clic pour confirmer").build(), e -> {
            if (!e.isShiftClick()) {
               this.plugin.msg(p, "&7Fais &fShift + clic &7pour confirmer l'exclusion.");
            } else {
               this.then(p, () -> this.actions.kick(p, target), () -> this.members(p, 0));
            }
         });
         if (c.rank(p.getUniqueId()) == Rank.CHEF) {
            m.set(
               16,
               ItemBuilder.of(Material.GOLDEN_HELMET).name("&6Nommer chef").lore("&7Tu deviendras Officier.", "&7Shift + clic pour confirmer").build(),
               e -> {
                  if (!e.isShiftClick()) {
                     this.plugin.msg(p, "&7Fais &fShift + clic &7pour confirmer.");
                  } else {
                     this.then(p, () -> this.actions.transfer(p, target), () -> this.members(p, 0));
                  }
               }
            );
         }

         m.set(22, this.back(), e -> this.members(p, 0));
         m.fillBorder();
         m.open(p);
      } else {
         this.members(p, 0);
      }
   }

   public void invite(Player p, int page) {
      Clan c = this.cm().of(p);
      if (c == null) {
         this.noClan(p);
      } else if (this.actions.requireRank(p, c, "invite")) {
         Menu m = new Menu(p, "list", 6, "&fInviter un joueur");
         List<Player> list = Bukkit.getOnlinePlayers()
            .stream()
            .filter(o -> o != p && this.cm().of(o) == null)
            .sorted(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER))
            .collect(Collectors.toList());
         int start = page * GRID.length;

         for (int i = 0; i < GRID.length && start + i < list.size(); i++) {
            Player t = list.get(start + i);
            boolean invited = this.cm().hasInvite(t.getUniqueId(), c);
            m.set(
               GRID[i],
               ItemBuilder.of(Material.PLAYER_HEAD)
                  .skull(t)
                  .name("&f" + t.getName())
                  .lore(invited ? "&aDéjà invité" : "&7Sans clan", "", "&eClic : inviter")
                  .glow(invited)
                  .build(),
               e -> this.then(p, () -> this.actions.invite(p, t), () -> this.invite(p, page))
            );
         }

         if (list.isEmpty()) {
            m.set(22, ItemBuilder.of(Material.GRAY_DYE).name("&7Aucun joueur sans clan connecté").build());
         }

         if (page > 0) {
            m.set(45, ItemBuilder.of(Material.SPECTRAL_ARROW).name("&7← Page précédente").build(), e -> this.invite(p, page - 1));
         }

         if (start + GRID.length < list.size()) {
            m.set(53, ItemBuilder.of(Material.SPECTRAL_ARROW).name("&7Page suivante →").build(), e -> this.invite(p, page + 1));
         }

         m.set(49, this.back(), e -> this.main(p));
         m.fillBorder();
         m.open(p);
      }
   }

   public void map(Player p) {
      Clan c = this.cm().of(p);
      if (c == null) {
         this.noClan(p);
      } else {
         boolean canClaim = c.rank(p.getUniqueId()).atLeast(this.plugin.settings().rank("claim"));
         ChunkPos center = ChunkPos.of(p.getLocation());
         Menu m = new Menu(p, "map", 6, "&fCarte &7(nord en haut) &f" + center);

         for (int row = 0; row < 5; row++) {
            for (int col = 0; col < 9; col++) {
               ChunkPos pos = center.offset(col - 4, row - 2);
               Clan owner = this.cm().at(pos);
               List<String> lore = new ArrayList<>();
               lore.add("&8Chunk " + pos);
               Material mat;
               String name;
               if (owner != null) {
                  Relation rel = this.cm().relation(c, owner);
                  boolean capChunk = pos.equals(owner.capital());
                  boolean capZone = this.cm().isCapitalZone(owner, pos);

                  mat = switch (rel) {
                     case SELF -> capChunk ? Material.BEACON : (capZone ? Material.LIME_CONCRETE : Material.LIME_STAINED_GLASS_PANE);
                     case ALLY -> Material.LIGHT_BLUE_STAINED_GLASS_PANE;
                     case ENEMY, WAR -> capZone ? Material.RED_CONCRETE : Material.RED_STAINED_GLASS_PANE;
                     default -> capZone ? Material.WHITE_CONCRETE : Material.WHITE_STAINED_GLASS_PANE;
                  };
                  name = rel.color() + owner.name() + (capZone ? " &6★" : "");
                  lore.add("&7" + rel.label() + (capChunk ? " &8- &6chunk de la capitale" : (capZone ? " &8- &6zone capitale" : "")));
                  if (owner == c && canClaim) {
                     lore.add("");
                     lore.add("&cClic droit : retirer ce chunk");
                  }
               } else if (this.cm().isSpawnZone(pos)) {
                  mat = Material.YELLOW_STAINED_GLASS_PANE;
                  name = "&eSpawn";
                  lore.add("&7Zone protégée");
               } else if (!this.cm().isClaimWorld(pos.world())) {
                  mat = Material.BLACK_STAINED_GLASS_PANE;
                  name = "&8Monde sans claims";
               } else {
                  mat = Material.LIGHT_GRAY_STAINED_GLASS_PANE;
                  name = "&2Zone sauvage";
                  if (canClaim) {
                     lore.add("");
                     lore.add("&aClic gauche : claim ce chunk");
                  }
               }

               boolean here = pos.equals(center);
               if (here) {
                  lore.add(0, "&e➤ Tu es ici");
               }

               ItemStack tile;
               if (m.fancy()) {
                  String kind;
                  if (owner != null) {
                     Relation rel = this.cm().relation(c, owner);

                     kind = switch (rel) {
                        case SELF -> this.cm().isCapitalZone(owner, pos) ? "capzone" : "self";
                        case ALLY -> "ally";
                        case ENEMY -> "enemy";
                        case WAR -> "war";
                        default -> "other";
                     };
                     if (pos.equals(owner.capital())) {
                        kind = (rel == Relation.SELF ? "self" : kind) + "_cap";
                     }
                  } else if (this.cm().isSpawnZone(pos)) {
                     kind = "spawn";
                  } else if (!this.cm().isClaimWorld(pos.world())) {
                     kind = "none";
                  } else {
                     kind = "wild";
                  }

                  if (here) {
                     kind = kind.replace("_cap", "") + "_here";
                  }

                  Integer cmd = Glyphs.mapTile(kind);
                  tile = ItemBuilder.of(Material.PAPER).model(cmd == null ? 0 : cmd).name(name).lore(lore).build();
               } else {
                  tile = ItemBuilder.of(mat).name(name).lore(lore).glow(here).build();
               }

               m.set(row * 9 + col, tile, e -> {
                  if (canClaim) {
                     if (e.isRightClick()) {
                        this.then(p, () -> this.actions.unclaim(p, pos), () -> this.map(p));
                     } else if (this.cm().at(pos) == null) {
                        this.then(p, () -> this.actions.claim(p, pos), () -> this.map(p));
                     }
                  }
               });
            }
         }

         m.set(45, this.back(), e -> this.main(p));
         if (m.fancy()) {
            String[] legend = new String[]{"&fTon clan", "&fAllié", "&fAutre clan", "&fEnnemi", "&fSpawn"};

            for (int i = 0; i < legend.length; i++) {
               m.label(210, 41 + 11 * i, legend[i], 64);
            }

            m.label(199, 98, "&7Claims &f" + c.claims().size() + "/" + this.cm().maxClaims(c), 75);
            m.label(199, 109, "&7Postes &f" + this.cm().outpostCount(c) + "/" + this.cm().maxOutposts(c), 75);
            m.label(199, 120, "&7Chunk &f" + center, 75);
            boolean free = this.cm().at(center) == null;
            m.set(
               47,
               ItemBuilder.of(Material.LIME_BANNER)
                  .name("&aClaim le chunk où tu es")
                  .lore(
                     "&8Chunk " + center,
                     "",
                     !canClaim ? "&cGrade requis : " + this.plugin.settings().rank("claim").display() : (free ? "&eClic : claim" : "&7Ce chunk est déjà pris")
                  )
                  .build(),
               e -> {
                  if (canClaim && this.cm().at(center) == null) {
                     this.then(p, () -> this.actions.claim(p, center), () -> this.map(p));
                  }
               }
            );
         } else {
            m.set(
               47,
               ItemBuilder.of(Material.PAPER)
                  .name("&fLégende")
                  .lore(
                     "&6Balise &7: chunk de ta capitale",
                     "&aVert plein &7: zone capitale (protégée)",
                     "&aVert &7: ton territoire",
                     "&bBleu &7: allié",
                     "&cRouge &7: ennemi",
                     "&fBlanc &7: autre clan",
                     "&eJaune &7: spawn",
                     "&7Gris : zone sauvage"
                  )
                  .build()
            );
         }

         m.set(
            49,
            ItemBuilder.of(Material.FILLED_MAP)
               .name("&2Ton territoire")
               .lore(
                  "&7Claims : &f" + c.claims().size() + "/" + this.cm().maxClaims(c),
                  "&7Avant-postes : &f" + this.cm().outpostCount(c) + "/" + this.cm().maxOutposts(c),
                  "",
                  "&8Un nouveau claim doit toucher ton",
                  "&8territoire, sauf avant-poste."
               )
               .build()
         );
         boolean borders = this.plugin.borders().contains(p.getUniqueId());
         m.set(
            51,
            ItemBuilder.of(borders ? Material.LIME_STAINED_GLASS : Material.GLASS)
               .name("&aBordures en particules")
               .lore(borders ? "&aActivées" : "&cDésactivées")
               .build(),
            e -> this.then(p, () -> this.actions.toggleBorders(p), () -> this.map(p))
         );
         m.set(53, ItemBuilder.of(Material.COMPASS).name("&7Actualiser").build(), e -> this.map(p));
         m.open(p);
      }
   }

   public void bank(Player p) {
      Clan c = this.cm().of(p);
      if (c == null) {
         this.noClan(p);
      } else {
         Settings s = this.plugin.settings();
         boolean canWithdraw = c.rank(p.getUniqueId()).atLeast(s.rank("bank-withdraw"));
         Menu m = new Menu(p, "bank", 6, "&fBanque » &b" + c.bank() + " " + s.currencyName);
         ItemStack info = s.currencyIcon()
            .name("&6Solde : &f" + c.bank() + " " + s.currencyName)
            .lore(
               "&7Tout le monde peut déposer.",
               "&7Retrait : " + s.rank("bank-withdraw").colored() + " &7minimum.",
               "",
               "&8En guerre, le perdant cède " + s.warLoot + "% au gagnant."
            )
            .glow(true)
            .build();
         int[] amounts = new int[]{1, 10, 64, 0};

         for (int i = 0; i < 4; i++) {
            int a = amounts[i];
            m.set(
               18 + i,
               (a == 0 ? ItemBuilder.of(Material.HOPPER) : s.currencyIcon().amount(a)).name(a == 0 ? "&aTout déposer" : "&aDéposer " + a).build(),
               e -> this.then(p, () -> this.actions.deposit(p, a), () -> this.bank(p))
            );
            if (canWithdraw) {
               m.set(
                  23 + i,
                  (a == 0 ? ItemBuilder.of(Material.CHEST_MINECART) : s.currencyIcon().amount(a))
                     .name(a == 0 ? "&cTout retirer" : "&cRetirer " + a)
                     .lore(a == 0 ? new String[]{"&8Autant que ton sac peut contenir"} : new String[0])
                     .build(),
                  e -> this.then(p, () -> this.actions.withdraw(p, a == 0 ? this.maxWithdraw(p, c) : a), () -> this.bank(p))
               );
            } else {
               m.set(23 + i, ItemBuilder.of(Material.GRAY_DYE).name("&7Retrait : " + s.rank("bank-withdraw").colored() + " &7minimum").build());
            }
         }

         m.button(45, 3, this.back(), "&fRetour", e -> this.main(p));
         m.set(49, ItemBuilder.of(Material.BARRIER).name("&cFermer").build(), e -> p.closeInventory());
         m.button(
            50,
            4,
            ItemBuilder.of(Material.CHEST).name("&6Coffre du clan").lore("&7" + this.cm().chestRows(c) * 9 + " cases partagées", "", "&eClic : ouvrir").build(),
            "&fCoffre",
            e -> this.actions.openChest(p)
         );
         if (m.fancy()) {
            for (int ix = 0; ix < 18; ix++) {
               m.hitbox(ix, info, null);
            }

            Glyphs.Line big = Glyphs.big(String.valueOf(c.bank()), TextColor.color(16767082));
            m.overlay(Glyphs.at(114 - big.width() / 2, big));
            int[] tops = new int[]{76, 85, 94};
            int k = 0;

            for (String entry : c.log()) {
               if (k >= tops.length) {
                  break;
               }

               String plain = Text.strip(entry);
               if (plain.contains(" a déposé ") || plain.contains(" a retiré ")) {
                  String[] parts = plain.split(" ", 3);
                  String line = parts.length == 3 && parts[1].matches("\\d{1,2}:\\d{2}") ? "&8" + parts[0] + " &0" + parts[2] : "&0" + plain;
                  m.label(10, tops[k++], line, 154);
               }
            }

            if (k == 0) {
               m.label(10, 85, "&8Aucun mouvement pour l'instant", 154);
            }

            int x = -98;
            int w = 76;
            m.label(x, 40, "&7Solde", w);
            m.label(x, 49, "&6" + c.bank() + " &f" + s.currencyName, w);
            m.label(x, 58, "&7Retrait", w);
            m.label(x, 67, s.rank("bank-withdraw").colored() + " &7min.", w);
            m.label(x, 76, "&7Butin guerre", w);
            m.label(x, 85, "&c" + s.warLoot + " %", w);
         } else {
            m.set(4, info);
         }

         m.fillBorder();
         m.open(p);
      }
   }

   private int maxWithdraw(Player p, Clan c) {
      int free = 0;

      for (ItemStack it : p.getInventory().getStorageContents()) {
         if (it == null || it.getType().isAir()) {
            free++;
         }
      }

      return (int)Math.min(c.bank(), free * 64L);
   }

   public void diplomacy(Player p, int page) {
      Clan c = this.cm().of(p);
      if (c == null) {
         this.noClan(p);
      } else {
         Menu m = new Menu(p, "list_top", 6, "&fDiplomatie");
         List<Clan> others = this.cm()
            .all()
            .stream()
            .filter(ox -> ox != c)
            .sorted(
               Comparator.<Clan, Integer>comparing(ox -> c.allyRequests().contains(ox.id()) ? 0 : 1)
                  .thenComparing(ox -> this.cm().relation(c, ox).ordinal())
                  .thenComparing(Clan::name, String.CASE_INSENSITIVE_ORDER)
            )
            .collect(Collectors.toList());
         m.set(
            4,
            ItemBuilder.of(Material.SHIELD)
               .name("&bDiplomatie")
               .lore(
                  "&7Alliés : &b" + c.allies().size(),
                  "&7Ennemis : &c" + c.enemies().size(),
                  "&7Demandes reçues : &f" + c.allyRequests().size(),
                  "",
                  "&8Alliés : pas de dégâts entre vous,",
                  "&8chat d'alliance (!!message), portes.",
                  "&8Ennemis : vous pouvez vous déclarer la guerre."
               )
               .build()
         );
         int start = page * GRID.length;

         for (int i = 0; i < GRID.length && start + i < others.size(); i++) {
            Clan o = others.get(start + i);
            Relation rel = this.cm().relation(c, o);
            List<String> lore = new ArrayList<>();
            lore.add("&7Relation : " + rel.color() + rel.label());
            if (c.allyRequests().contains(o.id())) {
               lore.add("&b➤ Vous propose une alliance !");
            }

            if (o.allyRequests().contains(c.id())) {
               lore.add("&8Demande d'alliance envoyée");
            }

            lore.add("&7Niveau &e" + o.level() + " &8• &7" + o.members().size() + " membres &8• &7" + this.cm().onlineCount(o) + " en ligne");
            lore.add("");
            lore.add("&eClic : changer la relation");
            m.set(
               GRID[i],
               ItemBuilder.of(banner(o.color())).name(rel.color() + "[" + o.tag() + "] " + o.name()).lore(lore).glow(c.allyRequests().contains(o.id())).build(),
               e -> this.relation(p, o)
            );
         }

         if (others.isEmpty()) {
            m.set(22, ItemBuilder.of(Material.GRAY_DYE).name("&7Aucun autre clan").build());
         }

         if (page > 0) {
            m.set(45, ItemBuilder.of(Material.SPECTRAL_ARROW).name("&7← Page précédente").build(), e -> this.diplomacy(p, page - 1));
         }

         if (start + GRID.length < others.size()) {
            m.set(53, ItemBuilder.of(Material.SPECTRAL_ARROW).name("&7Page suivante →").build(), e -> this.diplomacy(p, page + 1));
         }

         m.set(49, this.back(), e -> this.main(p));
         m.fillBorder();
         m.open(p);
      }
   }

   public void relation(Player p, Clan o) {
      Clan c = this.cm().of(p);
      if (c == null) {
         this.noClan(p);
      } else if (this.cm().get(o.id()) == null) {
         this.diplomacy(p, 0);
      } else {
         Settings s = this.plugin.settings();
         Relation rel = this.cm().relation(c, o);
         Menu m = new Menu(p, "action", 3, "&fRelation » " + o.color() + o.name());
         List<String> info = new ArrayList<>(this.clanInfoLore(o));
         info.add(0, "&7Relation : " + rel.color() + rel.label());
         m.set(4, ItemBuilder.of(banner(o.color())).name(o.coloredTag() + " &f" + o.name()).lore(info).build());
         boolean incoming = c.allyRequests().contains(o.id());
         m.set(
            10,
            ItemBuilder.of(Material.LIGHT_BLUE_DYE)
               .name(incoming ? "&bAccepter l'alliance" : "&bProposer une alliance")
               .lore("&7Pas de dégâts entre vous,", "&7chat d'alliance : !!message")
               .glow(incoming)
               .build(),
            e -> this.then(p, () -> this.actions.ally(p, o), () -> this.relation(p, o))
         );
         m.set(
            12,
            ItemBuilder.of(Material.WHITE_DYE).name("&fNeutre").lore("&7Rompt une alliance ou", "&7retire le statut d'ennemi.").build(),
            e -> this.then(p, () -> this.actions.neutral(p, o), () -> this.relation(p, o))
         );
         m.set(
            14,
            ItemBuilder.of(Material.RED_DYE).name("&cDéclarer ennemi").lore("&7Tags rouges, bordures rouges.").build(),
            e -> this.then(p, () -> this.actions.enemy(p, o), () -> this.relation(p, o))
         );
         m.set(
            16,
            ItemBuilder.of(Material.NETHERITE_SWORD)
               .name("&4⚔ Déclarer la guerre")
               .lore(
                  "&7Préparation : &f" + s.warPrep + " min",
                  "&7Durée : &f" + s.warDuration + " min",
                  "&7Pendant la guerre : PvP + raid",
                  "&7autorisés hors capitale.",
                  "&7Le clan avec le plus de kills gagne",
                  "&7" + s.warLoot + "% de la banque adverse.",
                  "",
                  "&cShift + clic pour déclarer"
               )
               .build(),
            e -> {
               if (!e.isShiftClick()) {
                  this.plugin.msg(p, "&7Fais &fShift + clic &7pour confirmer la déclaration de guerre.");
               } else {
                  this.then(p, () -> this.actions.declareWar(p, o), () -> this.war(p));
               }
            }
         );
         m.set(22, this.back(), e -> this.diplomacy(p, 0));
         m.fillBorder();
         m.open(p);
      }
   }

   public void war(Player p) {
      Clan c = this.cm().of(p);
      if (c == null) {
         this.noClan(p);
      } else {
         Settings s = this.plugin.settings();
         Menu m = new Menu(p, "trio", 3, "&cGuerre");
         WarManager.War w = this.plugin.war().warOf(c);
         m.set(
            11,
            ItemBuilder.of(Material.KNOWLEDGE_BOOK)
               .name("&fRègles de la guerre")
               .lore(
                  "&71. Le chef déclare la guerre (Diplomatie)",
                  "&72. Préparation de &f" + s.warPrep + " min",
                  "&73. Guerre de &f" + s.warDuration + " min &7: PvP, casse,",
                  "&7   explosions et coffres (hors capitale)",
                  "&74. Plus de kills = victoire",
                  "&7   +" + s.xpWarWin + " XP et " + s.warLoot + "% de la banque adverse",
                  "",
                  "&8La capitale reste toujours protégée.",
                  "&8Cooldown : " + s.warCooldown + "h entre 2 mêmes clans."
               )
               .build()
         );
         if (w == null) {
            m.set(13, ItemBuilder.of(Material.WHITE_BANNER).name("&aAucune guerre en cours").build());
            m.set(
               15,
               ItemBuilder.of(Material.SHIELD).name("&bDéclarer une guerre").lore("&7Passe par la diplomatie", "", "&eClic : ouvrir").build(),
               e -> this.diplomacy(p, 0)
            );
         } else {
            Clan o = this.cm().get(w.opponent(c));
            m.set(
               13,
               ItemBuilder.of(Material.NETHERITE_SWORD)
                  .name("&4⚔ vs " + (o == null ? "?" : o.name()))
                  .lore(this.plugin.war().status(c), "", "&7Kills : &a" + w.killsOf(c) + " &7- &c" + (o == null ? 0 : w.killsOf(o)))
                  .glow(true)
                  .build()
            );
            m.set(
               15,
               ItemBuilder.of(Material.WHITE_BANNER).name("&fCapituler").lore("&7Ton clan perd la guerre.", "", "&cShift + clic pour confirmer").build(),
               e -> {
                  if (!e.isShiftClick()) {
                     this.plugin.msg(p, "&7Fais &fShift + clic &7pour confirmer.");
                  } else {
                     this.then(p, () -> this.actions.surrender(p), () -> this.war(p));
                  }
               }
            );
         }

         m.set(22, this.back(), e -> this.main(p));
         m.fillBorder();
         m.open(p);
      }
   }

   public void top(Player p) {
      Menu m = new Menu(p, "list", 6, "&6Classement des clans");
      List<Clan> top = this.cm().top();
      Clan mine = this.cm().of(p);

      for (int i = 0; i < GRID.length && i < top.size(); i++) {
         Clan c = top.get(i);
         String medal = i == 0 ? "&6&l#1" : (i == 1 ? "&7&l#2" : (i == 2 ? "&c&l#3" : "&8#" + (i + 1)));
         m.set(
            GRID[i],
            ItemBuilder.of(banner(c.color()))
               .amount(i + 1)
               .name(medal + " " + c.coloredTag() + " &f" + c.name())
               .lore("&7Niveau : &e" + c.level(), this.progressBar(c), "&7Membres : &f" + c.members().size(), "&7Claims : &f" + c.claims().size())
               .glow(c == mine)
               .build()
         );
      }

      if (top.isEmpty()) {
         m.set(22, ItemBuilder.of(Material.GRAY_DYE).name("&7Aucun clan pour l'instant").build());
      }

      m.set(49, this.back(), e -> this.main(p));
      m.fillBorder();
      m.open(p);
   }

   public void grimoire(Player p, RitualManager.Altar altar) {
      this.grimoire(p, altar, 0);
   }

   public void grimoire(Player p, RitualManager.Altar altar, int page) {
      RitualManager rm = this.plugin.rituals();
      CustomItems items = this.plugin.items();
      ItemStack placed = altar == null ? null : rm.focusItem(altar);
      List<RitualManager.Recipe> list = new ArrayList<>();
      for (RitualManager.Recipe r : rm.recipesFor(altar)) {
         if (placed == null || items.matches(placed, r.focus())) {
            list.add(r);
         }
      }

      int pages = Math.max(1, (list.size() + GRID.length - 1) / GRID.length);
      int pg = Math.max(0, Math.min(pages - 1, page));
      Menu m = new Menu(p, "list", 6, "&6Grimoire des rituels" + (pages > 1 ? " &8(" + (pg + 1) + "/" + pages + ")" : ""));
      int i = 0;

      for (RitualManager.Recipe r : list.subList(pg * GRID.length, Math.min(list.size(), (pg + 1) * GRID.length))) {
         if (i >= GRID.length) {
            break;
         }

         ItemStack icon = r.result().startsWith("@") ? (r.result().startsWith("@gravure:") ? items.create("glyphe_" + r.tier()) : items.create("parchemin_effacement")) : items.create(r.result());
         if (icon == null) {
            icon = new ItemStack(Material.ENCHANTED_BOOK);
         }

         List<Component> lore = new ArrayList<>();
         lore.add(Text.item("&8" + Text.strip(r.name())));
         lore.add(Text.item(""));
         lore.add(Text.item("&6Au centre : ").append(this.matName(r.focus())));
         lore.add(Text.item("&6Offrandes :"));

         for (RitualManager.Offering o : r.offerings()) {
            lore.add(Text.item("  &7• &f" + o.amount() + "x ").append(this.matName(o.spec())));
         }

         if (r.result().startsWith("@gravure:")) {
            lore.add(Text.item("&6Effet : &f" + this.plugin.glyphs().describe(r.result())));
         }

         lore.add(Text.item("&6Autel : &dtier " + RitualManager.roman(r.tier())));
         lore.add(Text.item("&6Clan niveau : &f" + r.clanLevel()));
         lore.add(Text.item("&6Durée : &f" + Text.formatTime(r.duration()) + (r.nightOnly() ? " &8(la nuit)" : "")));
         lore.add(Text.item(""));
         if (altar != null) {
            List<String> miss = rm.missing(p, altar, r);
            if (miss.isEmpty()) {
               lore.add(Text.item("&a✔ Tout est prêt !"));
               lore.add(Text.item("&eClic : lancer le rituel"));
            } else {
               lore.add(Text.item("&cIl manque :"));

               for (String x : miss) {
                  lore.add(Text.item("  " + x));
               }
            }
         } else {
            lore.add(Text.item("&7Va à l'autel de ton clan pour le lancer."));
         }

         ItemMeta meta = icon.getItemMeta();
         if (r.result().startsWith("@")) {
            meta.displayName(Text.item(r.name()));
         }

         meta.lore(lore);
         meta.setAttributeModifiers(ArrayListMultimap.create());
         meta.addItemFlags(new ItemFlag[]{ItemFlag.HIDE_ATTRIBUTES});
         icon.setItemMeta(meta);
         m.set(GRID[i++], icon, altar == null ? null : e -> {
            p.closeInventory();
            rm.launch(p, altar, r);
         });
      }

      int d = rm.distance();
      String here = altar == null ? "" : "&7Cet autel : &dtier " + RitualManager.roman(rm.tier(altar.center()));
      m.set(
         53,
         ItemBuilder.of(Material.AMETHYST_BLOCK)
            .name("&dConstruire un autel")
            .lore(
               "&5Tier I &8(" + Text.formatTime(rm.tierDuration(1)) + " par rituel)",
               "&7 Au centre : &fune table d'enchantement",
               "&7 Autour : &f4 blocs d'améthyste &7à " + d + " blocs",
               "&7 (nord, sud, est, ouest), même hauteur.",
               "&5Tier II &8(" + Text.formatTime(rm.tierDuration(2)) + ")",
               "&7 + &f4 obsidiennes pleureuses &7aux coins",
               "&7   (" + d + " blocs en diagonale de la table)",
               "&5Tier III &8(" + Text.formatTime(rm.tierDuration(3)) + ")",
               "&7 + &fun bloc de diamant &7sous la table",
               "",
               here,
               "",
               "&7L'autel doit être dans le territoire",
               "&7de ton clan.",
               "",
               "&71. Clic droit sur la table avec l'objet",
               "&72. Clic droit sur chaque bloc d'améthyste",
               "&7   avec une offrande",
               "&73. Clic sur la table : grimoire > lancer",
               "",
               "&dGravure runique : pose un objet magique,",
               "&d un glyphe runique et le réactif du glyphe.",
               "",
               "&5Les autres clans sont prévenus :",
               "&5reste près de l'autel !",
               "&8Accroupi + clic : table d'enchantement normale"
            )
            .build()
      );
      if (pg > 0) {
         m.set(48, ItemBuilder.of(Material.SPECTRAL_ARROW).name("&7← Page précédente").build(), e -> this.grimoire(p, altar, pg - 1));
      }

      if (pg < pages - 1) {
         m.set(50, ItemBuilder.of(Material.SPECTRAL_ARROW).name("&7Page suivante →").build(), e -> this.grimoire(p, altar, pg + 1));
      }

      if (placed != null && list.isEmpty()) {
         m.set(22, ItemBuilder.of(Material.BARRIER).name("&cAucun rituel pour cet objet").lore("&7Reprends-le avec l'entonnoir.").build());
      }

      if (altar != null && rm.focusItem(altar) != null) {
         m.set(
            45,
            ItemBuilder.of(Material.HOPPER).name("&eReprendre l'objet posé").build(),
            e -> this.then(p, () -> rm.takeFocus(p, altar), () -> this.grimoire(p, altar))
         );
      } else if (altar == null) {
         m.set(
            45,
            ItemBuilder.of(Material.CAULDRON)
               .name("&5⚗ Rituel du chaudron")
               .lore("&7Les sacs magiques se préparent", "&7dans un chaudron, pas sur l'autel.", "", "&eClic : voir les recettes")
               .build(),
            e -> this.cauldron(p)
         );
      }

      m.set(49, altar == null ? this.back() : ItemBuilder.of(Material.BARRIER).name("&cFermer").build(), e -> {
         if (altar == null) {
            this.main(p);
         } else {
            p.closeInventory();
         }
      });
      m.fillBorder();
      m.open(p);
   }

   public void bosses(Player p) {
      Clan c = this.cm().of(p);
      if (c == null) {
         this.noClan(p);
      } else {
         BossManager bm = this.plugin.bosses();
         Menu m = new Menu(p, "list", 6, "&5Boss du clan");
         int i = 0;

         for (BossManager.BossDef d : bm.bosses()) {
            if (i >= GRID.length) {
               break;
            }

            String st = bm.status(c, d);

            Material icon = switch (st) {
               case "cooldown" -> Material.SKELETON_SKULL;
               case "available" -> Material.WITHER_SKELETON_SKULL;
               default -> Material.GRAY_DYE;
            };
            List<Component> lore = new ArrayList<>();
            lore.add(Text.item("&7Boss de niveau &f" + d.level()));
            lore.add(Text.item("&7Points de vie : &c" + (int)d.health()));
            lore.add(Text.item(""));
            lore.add(Text.item("&6Récompenses :"));

            for (String r : d.rewards()) {
               String[] sp = r.split(":");
               String spec = sp[0].equalsIgnoreCase("windy") ? "windy:" + sp[1] : sp[0];
               String amount = sp[0].equalsIgnoreCase("windy") ? (sp.length > 2 ? sp[2] : "1") : (sp.length > 1 ? sp[1] : "1");
               lore.add(Text.item("  &7• &f" + amount + "x ").append(this.matName(spec)));
            }

            if (d.rune() != null) {
               lore.add(
                  Text.item("  &7• &f1x ")
                     .append(this.matName("windy:" + d.rune()))
                     .append(Text.item(" &7(" + Math.round(d.runeChance() * 100.0) + " % de chance)"))
               );
            }

            lore.add(Text.item("  &7• &f" + d.clanXp() + " XP de clan"));
            lore.add(Text.item(""));
            if (c.bossesBeaten().contains(d.level())) {
               lore.add(Text.item("&a✔ Déjà vaincu &8(rejouable toutes les " + Text.formatTime(bm.winCooldown()) + ")"));
            }

            switch (st) {
               case "cooldown":
                  lore.add(Text.item("&7⌛ Se reforme dans &f" + Text.formatTime(bm.cooldownLeft(c, d.level()))));
                  break;
               case "locked":
                  lore.add(Text.item("&c✖ Clan niveau " + d.level() + " requis"));
                  break;
               default:
                  lore.add(Text.item("&e▶ Clic : lancer le combat"));
                  lore.add(Text.item("&8(" + bm.rank().display() + " minimum, le clan a 20 s pour rejoindre)"));
            }

            ItemStack it = ItemBuilder.of(icon).amount(d.level()).name(d.name()).glow("available".equals(st)).build();
            ItemMeta meta = it.getItemMeta();
            meta.lore(lore);
            it.setItemMeta(meta);
            int lvl = d.level();
            m.set(GRID[i++], it, "available".equals(st) ? e -> {
               p.closeInventory();
               bm.launch(p, lvl);
            } : null);
         }

         m.set(
            45,
            ItemBuilder.of(Material.MOSSY_STONE_BRICKS)
               .name("&5&lDonjons")
               .lore("&7Instances de 30 min, à plusieurs,", "&7sur 3 étages, avec un gardien final.", "", "&eClic : ouvrir")
               .glow(true)
               .build(),
            e -> this.plugin.dungeons().menu(p)
         );
         if (bm.lobbyOpen(c)) {
            m.set(53, ItemBuilder.of(Material.LIME_DYE).name("&aRejoindre le combat").glow(true).build(), e -> {
               p.closeInventory();
               bm.join(p);
            });
         }

         m.set(49, this.back(), e -> this.main(p));
         m.fillBorder();
         m.open(p);
      }
   }

   private Component matName(String spec) {
      if (spec.startsWith("windy:")) {
         return Text.item(this.plugin.items().displayName(spec));
      } else {
         Material mat = Material.matchMaterial(spec);
         return mat == null ? Text.item("&f" + spec) : Component.translatable(mat.translationKey()).color(NamedTextColor.WHITE);
      }
   }

   public void log(Player p) {
      Clan c = this.cm().of(p);
      if (c == null) {
         this.noClan(p);
      } else {
         Menu m = new Menu(p, "list", 6, "&fJournal » " + c.color() + c.name());
         List<String> entries = new ArrayList<>(c.log());

         for (int i = 0; i < GRID.length && i < entries.size(); i++) {
            m.set(GRID[i], ItemBuilder.of(Material.PAPER).name(entries.get(i)).build());
         }

         if (entries.isEmpty()) {
            m.set(22, ItemBuilder.of(Material.GRAY_DYE).name("&7Rien pour l'instant").build());
         }

         m.set(49, this.back(), e -> this.main(p));
         m.fillBorder();
         m.open(p);
      }
   }

   public void settings(Player p) {
      Clan c = this.cm().of(p);
      if (c == null) {
         this.noClan(p);
      } else {
         Rank r = c.rank(p.getUniqueId());
         Menu m = new Menu(p, "settings", 5, "&fParamètres");
         int[] slots = new int[]{10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28};
         int i = 0;

         for (Entry<String, String> entry : Actions.COLORS.entrySet()) {
            if (i >= slots.length) {
               break;
            }

            String code = entry.getValue();
            m.set(
               slots[i++],
               ItemBuilder.of(wool(code))
                  .name(code + "Couleur " + entry.getKey().replace('_', ' '))
                  .lore(r == Rank.CHEF ? "&eClic : choisir" : "&cRéservé au chef")
                  .glow(code.equals(c.color()))
                  .build(),
               e -> this.then(p, () -> this.actions.color(p, entry.getKey()), () -> this.settings(p))
            );
         }

         m.set(
            30,
            ItemBuilder.of(Material.RED_BED).name("&6Définir le home ici").lore("&7Doit être dans la zone capitale").build(),
            e -> this.then(p, () -> this.actions.setHome(p), () -> this.settings(p))
         );
         m.set(
            31,
            ItemBuilder.of(Material.BEACON).name("&6Déplacer la capitale ici").lore("&7Chef uniquement,", "&7dans un chunk de ton clan.").build(),
            e -> this.then(p, () -> this.actions.moveCapital(p), () -> this.settings(p))
         );
         boolean chat = this.plugin.clanChatToggle().contains(p.getUniqueId());
         m.set(
            32,
            ItemBuilder.of(Material.WRITABLE_BOOK)
               .name("&aChat de clan automatique")
               .lore("&7Tes messages vont seulement", "&7à ton clan.", "", chat ? "&aActivé" : "&cDésactivé")
               .glow(chat)
               .build(),
            e -> this.then(p, () -> this.actions.toggleChat(p), () -> this.settings(p))
         );
         if (r == Rank.CHEF) {
            m.set(
               34,
               ItemBuilder.of(Material.TNT)
                  .name("&4Dissoudre le clan")
                  .lore("&7Supprime le clan et ses claims.", "&7La banque te revient.", "", "&cShift + clic pour confirmer")
                  .build(),
               e -> {
                  if (!e.isShiftClick()) {
                     this.plugin.msg(p, "&cFais Shift + clic pour confirmer la dissolution.");
                  } else {
                     p.closeInventory();
                     this.actions.disband(p);
                  }
               }
            );
         } else {
            m.set(34, ItemBuilder.of(Material.OAK_DOOR).name("&cQuitter le clan").lore("&cShift + clic pour confirmer").build(), e -> {
               if (!e.isShiftClick()) {
                  this.plugin.msg(p, "&cFais Shift + clic pour confirmer.");
               } else {
                  p.closeInventory();
                  this.actions.leave(p);
               }
            });
         }

         String linked = this.plugin.bot().discordNameOf(p.getUniqueId());
         m.set(
            29,
            ItemBuilder.of(Material.LIGHT_BLUE_DYE)
               .name("&9Lier mon Discord")
               .lore(
                  linked == null ? "&7Pas encore lié." : "&7Lié à &f@" + linked,
                  "&7Tu recevras le rôle de ton clan",
                  "&7et l'accès à ses salons Discord.",
                  "",
                  linked == null ? "&eClic : écrire ton pseudo Discord" : "&eClic : changer  &8| &cShift : délier"
               )
               .build(),
            e -> {
               if (linked != null && e.isShiftClick()) {
                  this.plugin.bot().unlink(p);
                  this.settings(p);
               } else if (!this.plugin.bot().ready()) {
                  this.plugin.msg(p, "&cLe bot Discord n'est pas activé sur ce serveur.");
               } else {
                  TextInput.ask(this.plugin, p, "Ton pseudo Discord", "pseudo", name -> this.plugin.bot().startLink(p, name), () -> this.settings(p));
               }
            }
         );
         m.set(
            33,
            ItemBuilder.of(Material.OAK_SIGN).name("&fDescription du clan").lore("&8" + c.description(), "", "&eClic : modifier").build(),
            e -> TextInput.ask(this.plugin, p, "Description", c.description(), d -> {
               this.actions.description(p, d);
               this.settings(p);
            }, () -> this.settings(p))
         );
         m.set(40, this.back(), e -> this.main(p));
         m.fillBorder();
         m.open(p);
      }
   }

   public void cauldron(Player p) {
      this.cauldron(p, 0);
   }

   public void cauldron(Player p, int page) {
      Menu m = new Menu(p, "row", 3, "&5Rituel du chaudron");
      ArrayList<CauldronRitual.Recipe> list = new ArrayList<>(this.plugin.cauldron().recipes());
      int[] slots = new int[]{10, 11, 12, 13, 14};
      int from = page * slots.length;

      for (int i = 0; i < slots.length; i++) {
         int idx = from + i;
         if (idx >= list.size()) {
            break;
         }

         CauldronRitual.Recipe r = list.get(idx);
         ItemStack icon = this.plugin.items().create(r.result());
         if (icon == null) {
            icon = new ItemStack(Material.RABBIT_HIDE);
         }

         List<Component> lore = new ArrayList<>();
         lore.add(Text.item("&8" + Text.strip(r.name())));
         lore.add(Text.item(""));
         lore.add(Text.item("&6Chaudron : &dtier " + (r.tier() >= 2 ? "II" : "I")));
         lore.add(Text.item("&6Niveau perso : &f" + r.playerLevel() + " &8• &6Clan : &f" + r.clanLevel()));
         if (r.base() != null) {
            lore.add(Text.item("&6Objet à améliorer : ").append(this.matName(r.base())));
         }

         lore.add(Text.item("&6Ingrédients :"));

         for (CauldronRitual.Ingredient ing : r.ingredients()) {
            lore.add(Text.item("  &7• &f" + ing.amount() + "x ").append(this.matName(ing.spec())));
         }

         lore.add(Text.item("&6Durée : &f" + Text.formatTime(r.duration()) + (r.nightOnly() ? " &8(la nuit)" : "")));
         if (r.resultAmount() > 1) {
            lore.add(Text.item("&6Rendu si réussite : &f" + r.resultAmount() + "x ").append(this.matName("windy:" + r.result())));
         }

         if (r.failChance() > 0.0) {
            lore.add(Text.item("&cRisque d'échec : &f" + Math.round(r.failChance() * 100.0) + "% &7(ingrédients perdus)"));
         }

         if (r.base() != null && r.result().startsWith("sac")) {
            lore.add(Text.item("&7Le contenu du sac est gardé."));
         }

         ItemMeta meta = icon.getItemMeta();
         meta.lore(lore);
         icon.setItemMeta(meta);
         m.set(slots[i], icon);
      }

      if (page > 0) {
         m.set(9, ItemBuilder.of(Material.ARROW).name("&7« Page précédente").build(), e -> this.cauldron(p, page - 1));
      }

      if (from + slots.length < list.size()) {
         m.set(15, ItemBuilder.of(Material.ARROW).name("&7Page suivante »").build(), e -> this.cauldron(p, page + 1));
      }

      m.set(
         16,
         ItemBuilder.of(Material.CAULDRON)
            .name("&5⚗ Construire le chaudron runique")
            .lore(
               "&5Tier I",
               "&7 Chaudron &frempli d'eau &7sur un",
               "&7 &ffeu de camp allumé&7,",
               "&7 + &f4 blocs d'améthyste &7collés",
               "&7   (nord, sud, est, ouest).",
               "&5Tier II",
               "&7 Sur un &bfeu de camp des âmes&7,",
               "&7 + &f4 obsidiennes pleureuses &7aux coins.",
               "&8Il change d'apparence quand il est prêt.",
               "",
               "&71. Jette les ingrédients dedans (&fQ&7).",
               "&72. La potion bout : reste à côté.",
               "",
               "&8Clic droit : relancer la vérification",
               "&8Accroupi + clic droit : reprendre",
               "&8les ingrédients."
            )
            .build()
      );
      m.set(22, this.back(), e -> this.grimoire(p, null));
      m.fillBorder();
      m.open(p);
   }

   private ProfileManager pm() {
      return this.plugin.profiles();
   }

   private static String fmtXp(long v) {
      if (v >= 1000000L) {
         return String.format(Locale.ROOT, "%.1fM", v / 1000000.0).replace('.', ',');
      } else {
         return v >= 10000L ? v / 1000L + "k" : String.valueOf(v);
      }
   }

   private List<String> raceLore(RaceDef r) {
      int max = this.pm().maxTier();
      List<String> l = new ArrayList<>();

      for (String d : r.description()) {
         l.add("&8" + d);
      }

      l.add("");
      l.add("&aBonus &8(rang I → V) :");

      for (Entry<String, double[]> e : r.bonus().entrySet()) {
         double[] v = e.getValue();
         String k = e.getKey();
         String line = "&7• &f" + Stats.label(k) + " &a" + Stats.value(k, v[0]);
         if (v.length > 1) {
            line = line + " &8→ &a" + Stats.value(k, v[1]);
         }

         l.add(line);
      }

      for (RaceDef.Effect ef : r.effects()) {
         l.add(
            "&7• &f"
               + Stats.effectName(ef.type())
               + (ef.amplifier() > 0 ? " " + Stats.roman(ef.amplifier() + 1) : "")
               + " &8("
               + Stats.when(ef.when())
               + (ef.fromTier() > 0 ? ", rang " + Stats.roman(ef.fromTier() + 1) + "+" : "")
               + ")"
         );
      }

      boolean hasMalus = !r.malus().isEmpty() || !r.malusEffects().isEmpty() || r.hurt() != null;
      if (hasMalus) {
         l.add("");
         l.add("&cDéfauts :");

         for (Entry<String, double[]> e : r.malus().entrySet()) {
            l.add("&7• &c" + Stats.format(e.getKey(), RaceDef.at(e.getValue(), 0, max)));
         }

         for (RaceDef.Effect ef : r.malusEffects()) {
            l.add("&7• &c" + Stats.effectName(ef.type()) + " &8(" + Stats.when(ef.when()) + ")");
         }

         if (r.hurt() != null) {
            l.add("&7• &cBlessé " + Stats.when(r.hurt().when()));
         }
      } else {
         l.add("");
         l.add("&7Aucun défaut");
      }

      return l;
   }

   private List<String> activeLines(RaceDef r, int t) {
      int max = this.pm().maxTier();
      List<String> l = new ArrayList<>();

      for (Entry<String, double[]> e : r.bonus().entrySet()) {
         l.add("&a" + Stats.format(e.getKey(), RaceDef.at(e.getValue(), t, max)));
      }

      for (RaceDef.Effect ef : r.effects()) {
         if (t >= ef.fromTier()) {
            l.add("&a" + Stats.effectName(ef.type()) + (ef.amplifier() > 0 ? " " + Stats.roman(ef.amplifier() + 1) : ""));
         }
      }

      for (Entry<String, double[]> e : r.malus().entrySet()) {
         l.add("&c" + Stats.format(e.getKey(), RaceDef.at(e.getValue(), t, max)));
      }

      for (RaceDef.Effect efx : r.malusEffects()) {
         l.add("&c" + Stats.effectName(efx.type()));
      }

      if (r.hurt() != null) {
         l.add("&cEau / pluie");
      }

      return l;
   }

   public void profile(Player p) {
      this.profile(p, p);
   }

   public void profile(Player viewer, OfflinePlayer target) {
      ProfileManager pm = this.pm();
      Profile pr = pm.of(target);
      boolean self = viewer.getUniqueId().equals(target.getUniqueId());
      RaceDef race = pm.race(pr);
      int t = pm.tier(pr);
      int pct = pm.percent(pr);
      boolean maxed = pr.level() >= pm.maxLevel();
      Menu m = new Menu(viewer, "profile", 6, "&f" + pr.name());
      ItemStack head = ItemBuilder.of(Material.PLAYER_HEAD)
         .skull(target)
         .name("&f" + pr.name())
         .lore(
            "&7Niveau &d" + pr.level() + (maxed ? " &6(max)" : " &8(" + pct + "%)"),
            race == null ? "&7Sans race" : "&7Race : " + race.colored(),
            "&7Rang : &d" + pm.rankName(pr) + " &8(" + Stats.roman(pr.rank()) + ")"
         )
         .build();
      m.set(0, head);
      ItemStack lvl = ItemBuilder.of(Material.EXPERIENCE_BOTTLE)
         .amount(Math.min(64, pr.level()))
         .name("&dNiveau " + pr.level())
         .lore(
            maxed ? "&6Niveau maximum atteint !" : "&7XP : &f" + pr.xp() + " &8/ &f" + pm.need(pr.level()),
            "&7XP totale : &f" + fmtXp(pr.totalXp()),
            "",
            pm.atCap(pr) ? "&5✦ Éveil requis pour continuer" : "&7Niveau max du rang : &f" + pm.levelCap(pr),
            "&8Chaque niveau : +1 point de compétence."
         )
         .build();
      m.set(8, lvl);
      ItemStack raceItem;
      if (race == null) {
         raceItem = ItemBuilder.of(Material.NAME_TAG).name("&dChoisir une race").lore(self ? "&eClic : choisir" : "&7Pas encore choisie").glow(true).build();
      } else {
         List<String> lore = new ArrayList<>(this.raceLore(race));
         lore.add("");
         lore.add("&8Changer de race : bientôt, avec un objet spécial.");
         raceItem = ItemBuilder.of(race.icon()).name(race.colored() + " &7• " + pm.rankName(pr)).lore(lore).build();
      }

      m.button(9, 4, raceItem, "&fRace", e -> this.races(viewer));
      m.button(
         14,
         4,
         ItemBuilder.of(Material.GRAY_DYE)
            .name("&7Classe &8(verrouillée)")
            .lore("&7Guerrier, mage, archer...", "&7Les classes arrivent dans la &dv2&7.")
            .build(),
         "&7Classe",
         null
      );

      for (int i = 0; i < 5; i++) {
         m.set(28 + i, this.rankItem(pr, race, i + 1));
      }

      m.set(
         33,
         ItemBuilder.of(Material.SOUL_LANTERN)
            .amount((int)Math.max(1L, Math.min(64L, pr.souls() / 100L)))
            .name("&d☠ " + pr.souls() + " âmes")
            .lore(this.soulsLore())
            .build()
      );
      Awakening.RankDef nextDef = this.plugin.awakening().rank(pr.rank() + 1);
      m.set(
         34,
         ItemBuilder.of(pr.rank() >= 5 ? Material.NETHER_STAR : Material.LODESTONE)
            .name(pr.rank() >= 5 ? "&6Rang ultime atteint !" : "&5✦ Prochain Éveil")
            .lore(
               pr.rank() >= 5
                  ? List.of("&7Tu as atteint le sommet de ta lignée.")
                  : List.of(
                     "&7Deviens &d" + (race == null ? "?" : pm.tree().rankName(pr.race(), pr.rank() + 1)),
                     "&7Niveau &f" + pm.levelCap(pr) + " &8• &d" + (nextDef == null ? "?" : nextDef.souls()) + " âmes",
                     "",
                     "&eClic : voir l'Éveil"
                  )
            )
            .glow(pm.atCap(pr))
            .build(),
         self ? e -> this.evolution(viewer) : null
      );
      m.set(45, this.back(), e -> this.main(viewer));
      m.set(
         47,
         ItemBuilder.of(Material.ENCHANTED_BOOK).name("&dLes races").lore("&7Voir toutes les races", "&7et leurs bonus.", "", "&eClic : ouvrir").build(),
         e -> this.races(viewer)
      );
      m.set(48, ItemBuilder.of(Material.GOLDEN_HELMET).name("&6Classement des joueurs").lore("", "&eClic : voir").build(), e -> this.playersTop(viewer));
      m.set(49, ItemBuilder.of(Material.BARRIER).name("&cFermer").build(), e -> viewer.closeInventory());
      if (self) {
         int free = pm.tree().free(pr);
         m.set(
            50,
            ItemBuilder.of(Material.ENCHANTED_BOOK)
               .amount(Math.max(1, free))
               .name("&dCompétences")
               .lore("&7Points libres : &d" + free, "", "&eClic : ouvrir l'arbre")
               .glow(free > 0)
               .build(),
            e -> this.skills(viewer)
         );
         int known = this.plugin.magic().learnedCount(viewer.getUniqueId());
         m.set(
            52,
            ItemBuilder.of(Material.ENCHANTED_BOOK)
               .name("&5✦ Grimoire &8(magie)")
               .lore("&7Sorts appris : &d" + known + "&7/6", "&7Choisis tes 3 sorts équipés.", "", "&eClic : ouvrir")
               .glow(known > 0)
               .build(),
            e -> this.plugin.magic().menu(viewer)
         );
         m.set(
            51,
            ItemBuilder.of(Material.LODESTONE)
               .name("&5Évolution & Éveil")
               .lore("&7Ta lignée, le prochain Éveil", "&7et le sanctuaire.", "", "&eClic : ouvrir")
               .build(),
            e -> this.evolution(viewer)
         );
      }

      m.set(
         53,
         ItemBuilder.of(Material.BOOK)
            .name("&fGagner de l'XP")
            .lore(
               "&c⚔ Combat &7: monstres, boss de clan,",
               "&7   kills en guerre",
               "&6⛏ Métiers &7: minerais, bûches, récoltes,",
               "&7   pêche (pas les blocs posés)",
               "&d✦ Rituels &7: chaque rituel réussi",
               "&b⌚ Temps de jeu &7: toutes les 5 min",
               "&7   (pas si tu es AFK)",
               "",
               "&8Monstres de spawner : XP réduite."
            )
            .build()
      );
      if (m.fancy()) {
         for (int i = 1; i <= 7; i++) {
            m.hitbox(i, head, null);
         }

         m.label(27, 19, "&dNiveau " + pr.level() + " &8• " + (race == null ? "&7Sans race" : race.colored()), 94);
         m.overlay(Glyphs.right(146, 19, maxed ? "&6MAX" : "&e" + pct + "%"));
         m.overlay(Glyphs.xpBar(pct));
         m.overlay(Glyphs.right(165, 58, "&d" + pm.rankName(pr)));
         int x = -98;
         int w = 76;
         m.label(x, 40, "&7XP totale &f" + fmtXp(pr.totalXp()), w);
         m.label(x, 49, "&cCombat &f" + fmtXp(pr.source("combat")), w);
         m.label(x, 58, "&6Métiers &f" + fmtXp(pr.source("metiers")), w);
         m.label(x, 67, "&dRituels &f" + fmtXp(pr.source("rituels")), w);
         m.label(x, 76, "&bTemps &f" + fmtXp(pr.source("temps")), w);
         m.label(x, 85, "&7Monstres &f" + pr.mobKills(), w);
         m.label(x, 94, "&7Boss &f" + pr.bossWins(), w);
         m.label(x, 103, "&7Rituels &f" + pr.rituals(), w);
         m.label(x, 112, "&7Âmes &d" + fmtXp(pr.souls()), w);
         int bx = 198;
         if (race == null) {
            m.label(bx, 40, "&7Aucune race", w);
            if (self) {
               m.label(bx, 49, "&dClique sur Race", w);
            }
         } else {
            m.label(bx, 40, race.colored() + " &8• &d" + Stats.roman(pr.rank()), w);
            int[] tops = new int[]{52, 61, 70, 79, 88, 97, 106, 115};
            List<String> lines = this.activeLines(race, t);

            for (int i = 0; i < tops.length && i < lines.size(); i++) {
               m.label(bx, tops[i], lines.get(i), w);
            }
         }
      }

      m.fillBorder();
      m.open(viewer);
   }

   public void races(Player p) {
      ProfileManager pm = this.pm();
      Profile pr = pm.of(p);
      boolean choosing = pr.race() == null;
      Menu m = new Menu(p, "races", 6, choosing ? "&dChoisis ta race" : "&fLes races");
      List<String> ids = new ArrayList<>(pm.races().keySet());
      int extra = 0;

      for (String id : ids) {
         RaceDef r = pm.races().get(id);
         int idx = Arrays.asList(RACE_ORDER).indexOf(id);
         int slot;
         if (m.fancy()) {
            if (idx < 0) {
               continue;
            }

            slot = RACE_SLOTS[idx];
         } else {
            if (extra >= GRID.length) {
               break;
            }

            slot = GRID[extra++];
         }

         List<String> lore = new ArrayList<>(this.raceLore(r));
         lore.add("");
         boolean mine = id.equals(pr.race());
         if (mine) {
            lore.add("&a✔ Ta race");
         } else if (choosing) {
            lore.add("&eClic : choisir cette race");
         } else {
            lore.add("&8Changement : bientôt, avec un objet spécial.");
         }

         ItemStack it = ItemBuilder.of(r.icon()).name(r.colored()).lore(lore).glow(mine).build();
         Consumer<InventoryClickEvent> h = choosing ? e -> this.raceConfirm(p, id) : null;
         if (m.fancy()) {
            m.button(slot, 4, it, r.colored(), h);
         } else {
            m.set(slot, it, h);
         }
      }

      ItemStack info = ItemBuilder.of(Material.BOOK)
         .name("&dComment ça marche ?")
         .lore(
            "&7Ta race donne des bonus (et un défaut).",
            "&7Ils grandissent à chaque &5Éveil &7:",
            "&7un rituel tous les " + pm.tierEvery() + " niveaux, rang I → V.",
            "&7Chaque niveau donne aussi un",
            "&7point de compétence (&f/competences&7).",
            "",
            "&7Le choix est &fdéfinitif &7pour l'instant :",
            "&7un objet pour changer de race",
            "&7arrivera plus tard.",
            "",
            "&8Les classes arriveront dans la v2."
         )
         .build();
      if (m.fancy()) {
         m.button(41, 4, info, "&fInfos", null);
      } else {
         m.set(4, info);
      }

      if (!choosing) {
         m.set(45, this.back(), e -> this.profile(p));
      }

      m.set(49, ItemBuilder.of(Material.BARRIER).name(choosing ? "&cFermer &8(le choix reviendra)" : "&cFermer").build(), e -> p.closeInventory());
      if (m.fancy()) {
         int x = -98;
         int w = 76;
         if (choosing) {
            m.label(x, 40, "&dChoisis ta race", w);
            m.label(x, 49, "&7Survole une", w);
            m.label(x, 58, "&7race : bonus", w);
            m.label(x, 67, "&7et défauts.", w);
            m.label(x, 85, "&fChoix définitif", w);
            m.label(x, 94, "&8Changement :", w);
            m.label(x, 103, "&8objet spécial,", w);
            m.label(x, 112, "&8plus tard.", w);
         } else {
            RaceDef rx = pm.race(pr);
            m.label(x, 40, "&7Ta race :", w);
            m.label(x, 49, rx.colored(), w);
            m.label(x, 58, "&d" + pm.rankName(pr), w);
            m.label(x, 76, "&7Changement :", w);
            m.label(x, 85, "&7objet spécial,", w);
            m.label(x, 94, "&7bientôt.", w);
         }
      }

      m.fillBorder();
      m.open(p);
   }

   public void raceConfirm(Player p, String id) {
      ProfileManager pm = this.pm();
      RaceDef r = pm.races().get(id);
      if (r != null && pm.of(p).race() == null) {
         Menu m = new Menu(p, "trio", 3, "&fDevenir " + r.colored() + " &f?");
         m.set(
            11,
            ItemBuilder.of(Material.LIME_DYE)
               .name("&a✔ Devenir " + r.name())
               .lore("&7Ce choix est &fdéfinitif &7pour l'instant.", "&8(un objet pour changer arrivera plus tard)")
               .glow(true)
               .build(),
            e -> {
               p.closeInventory();
               if (pm.choose(p, id)) {
                  Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
                     if (p.isOnline()) {
                        this.profile(p);
                     }
                  }, 30L);
               }
            }
         );
         m.set(13, ItemBuilder.of(r.icon()).name(r.colored()).lore(this.raceLore(r)).build());
         m.set(15, ItemBuilder.of(Material.RED_DYE).name("&c✖ Revenir au choix").build(), e -> this.races(p));
         m.set(22, this.back(), e -> this.races(p));
         m.fillBorder();
         m.open(p);
      } else {
         this.races(p);
      }
   }

   public void playersTop(Player p) {
      ProfileManager pm = this.pm();
      Menu m = new Menu(p, "list", 6, "&6Classement des joueurs");
      List<Profile> top = pm.top();

      for (int i = 0; i < GRID.length && i < top.size(); i++) {
         Profile pr = top.get(i);
         RaceDef r = pm.race(pr);
         String medal = i == 0 ? "&6&l#1" : (i == 1 ? "&7&l#2" : (i == 2 ? "&c&l#3" : "&8#" + (i + 1)));
         m.set(
            GRID[i],
            ItemBuilder.of(Material.PLAYER_HEAD)
               .skull(Bukkit.getOfflinePlayer(pr.id()))
               .name(medal + " &f" + pr.name())
               .lore(
                  "&7Niveau &d" + pr.level(),
                  "&7Race : " + (r == null ? "&8aucune" : r.colored()),
                  "&7XP totale : &f" + fmtXp(pr.totalXp()),
                  "",
                  "&eClic : voir le profil"
               )
               .glow(pr.id().equals(p.getUniqueId()))
               .build(),
            e -> this.profile(p, Bukkit.getOfflinePlayer(pr.id()))
         );
      }

      if (top.isEmpty()) {
         m.set(22, ItemBuilder.of(Material.GRAY_DYE).name("&7Personne pour l'instant").build());
      }

      m.set(49, this.back(), e -> this.profile(p));
      m.fillBorder();
      m.open(p);
   }

   private List<String> soulsLore() {
      return List.of(
         "&7Les âmes servent aux Éveils.",
         "",
         "&7• Monstre tué : &d+1",
         "&7• Joueur ennemi tué : &d+25",
         "&7• Victoire contre un boss : &d+200",
         "&7• Mort face à un joueur : &c-20 %",
         "&8   (le vainqueur les prend)",
         "",
         "&8Les monstres de spawner n'en donnent pas."
      );
   }

   private ItemStack rankItem(Profile pr, RaceDef race, int rank) {
      ProfileManager pm = this.pm();
      boolean reached = pr.rank() >= rank;
      String name = race == null ? "Rang " + Stats.roman(rank) : pm.tree().rankName(pr.race(), rank);
      List<String> lore = new ArrayList<>();
      lore.add("&7Rang &f" + Stats.roman(rank) + (rank > 1 ? " &8• &7Éveil au niveau &f" + (rank - 1) * pm.tierEvery() : ""));
      if (race != null) {
         lore.add("");
         lore.add("&7Bonus de race :");

         for (String line : this.activeLines(race, rank - 1)) {
            lore.add("&7• " + line);
         }

         SkillTree.Tree t = pm.tree().tree(pr.race());
         if (t != null) {
            List<String> unlocks = new ArrayList<>();

            for (SkillTree.Branch b : t.branches()) {
               for (SkillTree.Node n : b.nodes()) {
                  if (n.rank() == rank) {
                     unlocks.add(n.name());
                  }
               }
            }

            for (SkillTree.Special sp : t.specials().values()) {
               if (sp.rank() == rank) {
                  unlocks.add("&6" + sp.name());
               }
            }

            if (!unlocks.isEmpty()) {
               lore.add("");
               lore.add("&7Débloque :");

               for (String u : unlocks) {
                  lore.add("&7• &f" + u);
               }
            }
         }
      }

      lore.add("");
      lore.add(reached ? (pr.rank() == rank ? "&d✦ Ton rang actuel" : "&a✔ Atteint") : "&8Pas encore atteint");
      Material icon = reached && race != null ? race.icon() : Material.GRAY_DYE;
      return ItemBuilder.of(icon).name((reached ? (race == null ? "&d" : race.color()) : "&8") + name).lore(lore).glow(pr.rank() == rank).build();
   }

   public void skills(Player p) {
      ProfileManager pm = this.pm();
      Profile pr = pm.of(p);
      if (pr.race() == null) {
         this.races(p);
      } else {
         SkillTree tree = pm.tree();
         SkillTree.Tree t = tree.tree(pr.race());
         if (t == null) {
            this.plugin.msg(p, "&cPas d'arbre pour ta race (competences.yml).");
         } else {
            RaceDef race = pm.race(pr);
            int free = tree.free(pr);
            Menu m = new Menu(p, "skills", 6, "&dCompétences");

            for (int b = 0; b < t.branches().size() && b < 3; b++) {
               SkillTree.Branch br = t.branches().get(b);
               List<String> bl = new ArrayList<>();
               int spent = 0;

               for (SkillTree.Node n : br.nodes()) {
                  int lv = pr.skill(n.id());
                  spent += lv * n.cost();
                  if (lv > 0) {
                     bl.add("&7• &f" + n.name() + " &a" + Stats.value(n.stat(), n.value(lv)));
                  }
               }

               bl.add(0, "&7Points investis : &d" + spent);
               if (bl.size() > 1) {
                  bl.add(1, "");
               }

               m.set(b * 9, ItemBuilder.of(br.icon()).name(BRANCH_COLOR[b] + "&l" + br.name()).lore(bl).build());

               for (int i = 0; i < br.nodes().size() && i < 5; i++) {
                  SkillTree.Node nx = br.nodes().get(i);
                  m.set(b * 9 + 2 + i, this.nodeItem(pr, nx, free), e -> this.learn(p, nx.id()));
               }
            }

            m.set(
               8,
               ItemBuilder.of(free > 0 ? Material.EXPERIENCE_BOTTLE : Material.GLASS_BOTTLE)
                  .amount(Math.max(1, free))
                  .name("&d" + free + " point" + (free > 1 ? "s" : "") + " libre" + (free > 1 ? "s" : ""))
                  .lore(
                     "&7Gagnés : &f" + tree.earned(pr) + " &8(1 par niveau, +" + tree.pointsPerAwakening() + " par Éveil)",
                     "&7Dépensés : &f" + tree.spent(pr),
                     "",
                     "&8Parchemin d'oubli : rend tous les points",
                     "&8(rituel du chaudron)."
                  )
                  .glow(free > 0)
                  .build()
            );
            int[] sslots = new int[]{37, 39, 41, 43};

            for (int k = 0; k < SkillTree.KINDS.length; k++) {
               SkillTree.Special sp = t.specials().get(SkillTree.KINDS[k]);
               if (sp != null) {
                  m.set(sslots[k], this.specialItem(p, pr, sp, free), e -> this.learn(p, sp.id()));
               }
            }

            m.set(45, this.back(), e -> this.profile(p));
            m.set(47, ItemBuilder.of(Material.LODESTONE).name("&5Évolution & Éveil").lore("", "&eClic : ouvrir").build(), e -> this.evolution(p));
            m.set(49, ItemBuilder.of(Material.BARRIER).name("&cFermer").build(), e -> p.closeInventory());
            m.set(
               51,
               ItemBuilder.of(Material.KNOWLEDGE_BOOK)
                  .name("&fComment ça marche ?")
                  .lore(
                     "&7Chaque niveau donne &d1 point&7,",
                     "&7chaque Éveil &d" + tree.pointsPerAwakening() + " points &7de plus.",
                     "",
                     "&7Le passif n°X d'une branche se",
                     "&7débloque au &5rang X&7.",
                     "",
                     "&6Compétences spéciales :",
                     "&7• Extra &8(rang II) &7: passif spécial",
                     "&7• Unique &8(rang III) &7: pouvoir actif",
                     "&7• Améliorée &8(rang IV) &7: renforce l'unique",
                     "&7• Ultime &8(rang V, niv. " + pm.maxLevel() + ")",
                     "",
                     "&fAccroupi + F &7: compétence unique",
                     "&fAccroupi + F deux fois &7: ultime"
                  )
                  .build()
            );
            m.set(53, ItemBuilder.of(Material.PLAYER_HEAD).skull(p).name("&fMon profil").lore("", "&eClic : ouvrir").build(), e -> this.profile(p));
            if (m.fancy()) {
               int x = -98;
               int w = 76;
               m.label(x, 40, race.colored(), w);
               List<String> nm = wrap(pm.rankName(pr), 12);

               for (int i = 0; i < nm.size() && i < 2; i++) {
                  m.label(x, i == 0 ? 49 : 58, "&d" + nm.get(i), w);
               }

               m.label(x, 67, "&7Niveau &f" + pr.level(), w);
               m.label(x, 85, "&7Points libres", w);
               m.label(x, 94, "&d&l" + free, w);
               m.label(x, 103, "&7Dépensés &f" + tree.spent(pr), w);
               m.label(x, 112, "&7Âmes &d" + fmtXp(pr.souls()), w);
               int bx = 198;
               m.label(bx, 40, "&7Accroupi + F", w);
               SkillTree.Special uni = t.specials().get("unique");
               SkillTree.Special ult = t.specials().get("ultimate");
               m.label(bx, 58, "&6Unique", w);
               m.label(bx, 67, this.powerState(p, pr, uni, "unique"), w);
               m.label(bx, 85, "&6Ultime &8(F ×2)", w);
               m.label(bx, 94, this.powerState(p, pr, ult, "ultimate"), w);
            }

            m.fillBorder();
            m.open(p);
         }
      }
   }

   private String powerState(Player p, Profile pr, SkillTree.Special sp, String kind) {
      if (sp != null && pr.skill(sp.id()) > 0) {
         long ms = this.plugin.powers().cooldownLeft(p, kind);
         if (ms <= 0L) {
            return "&aprête";
         } else {
            long sec = (ms + 999L) / 1000L;
            return "&c" + (sec >= 60L ? sec / 60L + " min " + sec % 60L + " s" : sec + " s");
         }
      } else {
         return "&8non apprise";
      }
   }

   private ItemStack nodeItem(Profile pr, SkillTree.Node n, int free) {
      ProfileManager pm = this.pm();
      int lv = pr.skill(n.id());
      boolean locked = pr.rank() < n.rank();
      boolean max = lv >= n.max();
      String col = locked ? "&8" : (max ? "&6" : (lv > 0 ? "&d" : "&f"));
      List<String> lore = new ArrayList<>();
      String cur = lv > 0 ? Stats.value(n.stat(), n.value(lv)) : "&8—";
      String nxt = max ? "" : " &8→ &a" + Stats.value(n.stat(), n.value(lv + 1));
      lore.add("&7" + Stats.label(n.stat()) + " : &a" + cur + nxt);
      if (n.when() != null) {
         lore.add("&8(" + Stats.when(n.when()) + ")");
      }

      lore.add("&7Niveau &f" + lv + "&7/" + n.max());
      lore.add("");
      if (locked) {
         lore.add("&c\ud83d\udd12 Rang " + Stats.roman(n.rank()) + " requis &8(" + pm.tree().rankName(n.race(), n.rank()) + ")");
      } else if (max) {
         lore.add("&6✔ Maximum");
      } else {
         lore.add("&7Coût : &d" + n.cost() + " point" + (n.cost() > 1 ? "s" : ""));
         lore.add(free >= n.cost() ? "&eClic : apprendre" : "&cPas assez de points");
      }

      return ItemBuilder.of(locked ? Material.GRAY_DYE : n.icon())
         .amount(Math.max(1, lv))
         .name(col + (locked ? "\ud83d\udd12 " : "") + n.name())
         .lore(lore)
         .glow(lv > 0)
         .build();
   }

   private ItemStack specialItem(Player p, Profile pr, SkillTree.Special sp, int free) {
      ProfileManager pm = this.pm();
      boolean has = pr.skill(sp.id()) > 0;
      boolean locked = pr.rank() < sp.rank() || sp.kind().equals("ultimate") && pr.level() < pm.maxLevel();
      String kindKey = sp.kind();

      String kindName = switch (kindKey) {
         case "extra" -> "Compétence extra";
         case "unique" -> "Compétence unique";
         case "unique-plus" -> "Unique améliorée";
         default -> "Compétence ultime";
      };
      List<String> lore = new ArrayList<>();
      lore.add("&8" + kindName);
      lore.add("");

      for (String d : sp.description()) {
         lore.add("&7" + d);
      }

      if (sp.cooldown() > 0) {
         lore.add("&8Recharge : " + (sp.cooldown() >= 60 ? sp.cooldown() / 60 + " min" : sp.cooldown() + " s"));
      }

      if (sp.kind().equals("unique")) {
         lore.add("&8Accroupi + F pour l'utiliser.");
      }

      if (sp.kind().equals("ultimate") && !"humain".equals(sp.race())) {
         lore.add("&8Accroupi + F deux fois pour l'utiliser.");
      }

      lore.add("");
      if (has) {
         lore.add("&a✔ Apprise");
      } else if (locked) {
         lore.add(
            "&c\ud83d\udd12 "
               + (
                  pr.rank() < sp.rank()
                     ? "Rang " + Stats.roman(sp.rank()) + " requis &8(" + pm.tree().rankName(sp.race(), sp.rank()) + ")"
                     : "Niveau " + pm.maxLevel() + " requis"
               )
         );
      } else {
         lore.add("&7Coût : &d" + sp.cost() + " points");
         lore.add(free >= sp.cost() ? "&eClic : apprendre" : "&cPas assez de points");
      }

      return ItemBuilder.of(locked && !has ? Material.GRAY_DYE : sp.icon())
         .name((has ? "&6" : (locked ? "&8" : "&f")) + sp.name())
         .lore(lore)
         .glow(has)
         .build();
   }

   private void learn(Player p, String id) {
      String err = this.pm().learn(p, id);
      if (err != null) {
         p.sendActionBar(Text.c("&c" + err));
         p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.7F, 1.0F);
      } else {
         p.playSound(p.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.8F, 1.3F);
         p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.4F, 1.8F);
         this.skills(p);
      }
   }

   public void evolution(Player p) {
      ProfileManager pm = this.pm();
      Profile pr = pm.of(p);
      if (pr.race() == null) {
         this.races(p);
      } else {
         RaceDef race = pm.race(pr);
         Awakening aw = this.plugin.awakening();
         int target = pr.rank() + 1;
         Awakening.RankDef def = aw.rank(target);
         Menu m = new Menu(p, "evolution", 6, "&5Éveil");
         int[] line = new int[]{9, 11, 13, 15, 17};

         for (int i = 0; i < 5; i++) {
            m.set(line[i], this.rankItem(pr, race, i + 1));
         }

         Block core = aw.nearestCore(p, 12);
         int structure = core == null ? 0 : Awakening.structureTier(core);
         if (def == null) {
            m.set(
               22,
               ItemBuilder.of(Material.NETHER_STAR)
                  .name("&6Rang ultime atteint !")
                  .lore("&7Tu es " + race.color() + pm.rankName(pr) + "&7.")
                  .glow(true)
                  .build()
            );
         } else {
            boolean lvlOk = pr.level() >= pm.levelCap(pr);
            m.set(
               21,
               ItemBuilder.of(Material.EXPERIENCE_BOTTLE)
                  .name((lvlOk ? "&a✔ " : "&c✖ ") + "Niveau " + pm.levelCap(pr))
                  .lore("&7Tu es niveau &f" + pr.level() + "&7.", "&8L'XP est bloquée à ce niveau", "&8tant que l'Éveil n'est pas fait.")
                  .build()
            );
            boolean soulOk = pr.souls() >= def.souls();
            m.set(
               22,
               ItemBuilder.of(Material.SOUL_LANTERN)
                  .name((soulOk ? "&a✔ " : "&c✖ ") + def.souls() + " âmes")
                  .lore(merge(List.of("&7Tu en as &d" + pr.souls() + "&7.", ""), this.soulsLore()))
                  .build()
            );
            boolean bossOk = pr.bossWins() >= def.bossWins();
            m.set(
               23,
               ItemBuilder.of(Material.WITHER_SKELETON_SKULL)
                  .name((bossOk ? "&a✔ " : "&c✖ ") + def.bossWins() + " victoire(s) contre un boss")
                  .lore("&7Tu en as &f" + pr.bossWins() + "&7.", "&8Boss de clan : /clan boss")
                  .build()
            );
            boolean structOk = structure >= target;
            m.set(
               24,
               ItemBuilder.of(Material.LODESTONE)
                  .name((structOk ? "&a✔ " : "&c✖ ") + "Sanctuaire de rang " + Stats.roman(target))
                  .lore(
                     core == null
                        ? List.of("&7Aucun sanctuaire à moins de 12 blocs.", "&8Plan : case en bas à gauche.")
                        : List.of("&7Sanctuaire proche : " + (structure < 2 ? "&cincomplet" : "&drang " + Stats.roman(structure)))
                  )
                  .build()
            );
            long wait = pr.awakenRetryAt() - System.currentTimeMillis();
            m.set(
               25,
               ItemBuilder.of(Material.CLOCK)
                  .name(wait <= 0L ? "&a✔ Prêt" : "&c✖ Échec récent")
                  .lore(wait <= 0L ? List.of("&7Aucune attente.") : List.of("&7Nouvel essai dans &f" + Text.formatTime(wait / 1000L) + "&7."))
                  .build()
            );
            List<Awakening.Offering> offers = aw.offeringsFor(pr.race(), target);
            int[] oslots = new int[]{28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};

            for (int i = 0; i < offers.size() && i < oslots.length; i++) {
               Awakening.Offering o = offers.get(i);
               int have = 0;

               for (ItemStack it : p.getInventory().getContents()) {
                  if (it != null && this.plugin.items().matches(it, o.spec())) {
                     have += it.getAmount();
                  }
               }

               ItemStack icon = o.spec().startsWith("windy:") ? this.plugin.items().create(o.spec().substring(6)) : null;
               if (icon == null) {
                  Material mat = Material.matchMaterial(o.spec());
                  icon = new ItemStack(mat == null ? Material.PAPER : mat);
               }

               icon.setAmount(Math.max(1, Math.min(icon.getMaxStackSize(), o.amount())));
               ItemMeta meta = icon.getItemMeta();
               List<Component> lore = new ArrayList<>();
               lore.add(Text.item("&7Offrande : &f" + o.amount() + "x ").append(aw.name(o.spec())));
               lore.add(Text.item("&7Dans ton sac : " + (have >= o.amount() ? "&a" : "&c") + have + "&7/" + o.amount()));
               meta.lore(lore);
               icon.setItemMeta(meta);
               m.set(oslots[i], icon);
            }
         }

         m.set(
            27,
            ItemBuilder.of(Material.CHEST)
               .name("&6Offrandes")
               .lore(
                  "&7Jette-les sur le &dcœur &7du sanctuaire",
                  "&7(touche &fQ&7).",
                  "&7Quand tout y est, l'Éveil commence.",
                  "",
                  "&8Clic droit sur le cœur : vérifier",
                  "&8Accroupi + clic droit : reprendre"
               )
               .build()
         );
         m.set(
            36,
            ItemBuilder.of(Material.CHISELED_TUFF_BRICKS)
               .name("&5Construire le sanctuaire")
               .lore(
                  "&dRang II &8(cœur = magnétite)",
                  "&7 4 piliers de &f2 briques de tuf sculptées",
                  "&7 à 3 blocs du cœur (N, S, E, O).",
                  "&dRang III",
                  "&7 + 4 piliers en diagonale (2, 2)",
                  "&7 surmontés d'un &6bloc d'or&7.",
                  "&dRang IV",
                  "&7 + un &bbloc de diamant &7sur les",
                  "&7 4 piliers N, S, E, O.",
                  "&dRang V",
                  "&7 + un &8bloc de netherite &7sous le cœur",
                  "&7 et 4 &5obsidiennes pleureuses &7collées.",
                  "",
                  "&8Il s'illumine quand il est complet."
               )
               .build()
         );
         m.set(45, this.back(), e -> this.profile(p));
         m.set(47, ItemBuilder.of(Material.ENCHANTED_BOOK).name("&dCompétences").lore("", "&eClic : ouvrir l'arbre").build(), e -> this.skills(p));
         m.set(49, ItemBuilder.of(Material.BARRIER).name("&cFermer").build(), e -> p.closeInventory());
         m.set(
            51,
            ItemBuilder.of(Material.KNOWLEDGE_BOOK)
               .name("&fDéroulement de l'Éveil")
               .lore(
                  "&71. &fVagues &7: des monstres attaquent",
                  "&7   pendant 60 % du rituel.",
                  "&72. &5Sommeil d'Éveil &7: tu es immobilisé,",
                  "&7   ton clan et tes alliés te protègent.",
                  "",
                  "&cTout le serveur est prévenu&7, le PvP",
                  "&7est ouvert à 30 blocs du sanctuaire.",
                  "",
                  "&cÉchec &7si tu meurs, t'éloignes ou si",
                  "&7le cœur est brisé : tu perds la moitié",
                  "&7des offrandes et des âmes, et tu",
                  "&7attends " + aw.failHours() + " h. Le clan qui t'a",
                  "&7interrompu gagne une récompense."
               )
               .build()
         );
         m.set(53, ItemBuilder.of(Material.PLAYER_HEAD).skull(p).name("&fMon profil").lore("", "&eClic : ouvrir").build(), e -> this.profile(p));
         if (m.fancy()) {
            int x = -98;
            int w = 76;
            m.label(x, 40, "&d&l" + fmtXp(pr.souls()) + " &7âmes", w);
            if (def != null) {
               m.label(x, 49, "&7Besoin : &f" + fmtXp(def.souls()), w);
            }

            m.label(x, 67, "&7Monstre &d+1", w);
            m.label(x, 76, "&7Joueur &d+25", w);
            m.label(x, 85, "&7Boss &d+200", w);
            m.label(x, 103, "&7Mort (PvP)", w);
            m.label(x, 112, "&c-20 %", w);
            int bx = 198;
            if (def == null) {
               m.label(bx, 40, "&6Rang ultime", w);
            } else {
               m.label(bx, 40, "&7Rang &d" + Stats.roman(target) + " &7:", w);
               List<String> nm = wrap(pm.tree().rankName(pr.race(), target), 12);

               for (int i = 0; i < nm.size() && i < 2; i++) {
                  m.label(bx, i == 0 ? 49 : 58, "&d" + nm.get(i), w);
               }

               m.label(bx, 76, "&7Proche :", w);
               m.label(bx, 85, core == null ? "&8aucun" : (structure < 2 ? "&cincomplet" : "&arang " + Stats.roman(structure)), w);
               m.label(bx, 103, "&7Durée :", w);
               m.label(bx, 112, "&f" + Text.formatTime(def.duration()), w);
            }
         }

         m.fillBorder();
         m.open(p);
      }
   }

   private static List<String> wrap(String text, int max) {
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

   private static List<String> merge(List<String> a, List<String> b) {
      List<String> l = new ArrayList<>(a);
      l.addAll(b);
      return l;
   }

   public void clanChat(Player p, String message, boolean ally) {
      Clan c = this.cm().of(p);
      if (c == null) {
         this.plugin.msg(p, "&cTu n'as pas de clan.");
      } else {
         Rank r = c.rank(p.getUniqueId());
         Set<Player> to = new HashSet<>(this.cm().onlineMembers(c));
         Component line;
         if (ally) {
            for (UUID a : c.allies()) {
               Clan ac = this.cm().get(a);
               if (ac != null) {
                  to.addAll(this.cm().onlineMembers(ac));
               }
            }

            line = Text.c("&b[Alliance] " + c.coloredTag() + " " + r.color() + p.getName() + "&7 » &b").append(Component.text(message));
         } else {
            line = Text.c("&a[Clan] " + r.colored() + " &f" + p.getName() + "&7 » &a").append(Component.text(message));
         }

         for (Player t : to) {
            t.sendMessage(line);
         }

         Bukkit.getConsoleSender().sendMessage(line);
      }
   }
}
