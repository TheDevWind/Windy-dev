package fr.windydev.clans.command;

import fr.windydev.clans.WindyClans;
import fr.windydev.clans.data.ClanManager;
import fr.windydev.clans.data.Settings;
import fr.windydev.clans.gui.TextInput;
import fr.windydev.clans.model.ChunkPos;
import fr.windydev.clans.model.Clan;
import fr.windydev.clans.model.Rank;
import fr.windydev.clans.util.Text;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

public class Actions {
   private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_\\-]+");
   public static final Map<String, String> COLORS = new LinkedHashMap<>();
   private final WindyClans plugin;
   private final Map<UUID, BukkitTask> homeTasks = new HashMap<>();

   public Actions(WindyClans plugin) {
      this.plugin = plugin;
   }

   private ClanManager cm() {
      return this.plugin.clans();
   }

   private void msg(Player p, String m) {
      this.plugin.msg(p, m);
   }

   public Clan requireClan(Player p) {
      Clan c = this.cm().of(p);
      if (c == null) {
         this.msg(p, "&cTu n'as pas de clan. &7Menu > Créer un clan");
      }

      return c;
   }

   public boolean requireRank(Player p, Clan c, String action) {
      Rank need = this.plugin.settings().rank(action);
      if (c.rank(p.getUniqueId()).atLeast(need)) {
         return true;
      } else {
         this.msg(p, "&cIl faut être au moins " + need.colored() + " &cpour faire ça.");
         return false;
      }
   }

   public void create(Player p, String name, String tag) {
      Settings s = this.plugin.settings();
      if (this.cm().of(p) != null) {
         this.msg(p, "&cTu es déjà dans un clan.");
      } else if (name.length() < s.nameMin || name.length() > s.nameMax || !NAME.matcher(name).matches()) {
         this.msg(p, "&cLe nom doit faire " + s.nameMin + " à " + s.nameMax + " caractères (lettres, chiffres, _ et -).");
      } else if (tag.length() >= s.tagMin && tag.length() <= s.tagMax && NAME.matcher(tag).matches()) {
         tag = tag.toUpperCase(Locale.ROOT);
         if (this.cm().byName(name) == null && this.cm().byName(tag) == null) {
            Clan c = this.cm().create(name, tag, p);
            Set<String> used = new HashSet<>();

            for (Clan o : this.cm().all()) {
               if (o != c) {
                  used.add(o.color());
               }
            }

            List<String> free = new ArrayList<>(COLORS.values());
            free.removeAll(List.of("&8", "&7", "&f"));
            List<String> unused = new ArrayList<>(free);
            unused.removeAll(used);
            List<String> pool = unused.isEmpty() ? free : unused;
            c.color(pool.get(new Random().nextInt(pool.size())));
            Bukkit.broadcast(Text.c(s.prefix + "&7Le clan " + c.coloredTag() + " &f" + c.name() + " &7vient d'être fondé par &f" + p.getName() + "&7 !"));
            this.msg(p, "&aClan créé ! Va à l'endroit de ta future capitale puis &fMenu > Territoire &aet clique sur ta case.");
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0F, 1.2F);
            this.plugin
               .discord()
               .send("clan-create", "\ud83c\udff0 Le clan **" + c.name() + "** [" + c.tag() + "] vient d'être fondé par " + p.getName() + " !");
         } else {
            this.msg(p, "&cCe nom ou ce tag est déjà pris.");
         }
      } else {
         this.msg(p, "&cLe tag doit faire " + s.tagMin + " à " + s.tagMax + " caractères.");
      }
   }

   public void disband(Player p) {
      Clan c = this.requireClan(p);
      if (c != null) {
         if (c.rank(p.getUniqueId()) != Rank.CHEF) {
            this.msg(p, "&cSeul le chef peut dissoudre le clan.");
         } else if (this.plugin.war().warOf(c) != null) {
            this.msg(p, "&cImpossible pendant une guerre.");
         } else {
            String name = c.name();
            this.cm().broadcast(c, "&cLe clan a été dissous par " + p.getName() + ".");
            this.giveCurrency(p, c.bank());
            this.cm().disband(c);
            Bukkit.broadcast(Text.c(this.plugin.settings().prefix + "&7Le clan &f" + name + " &7a été dissous."));
            this.plugin.discord().send("clan-disband", "\ud83d\udca8 Le clan **" + name + "** a été dissous.");
         }
      }
   }

   public void invite(Player p, Player target) {
      Clan c = this.requireClan(p);
      if (c != null && this.requireRank(p, c, "invite")) {
         if (target == null) {
            this.msg(p, "&cJoueur introuvable ou hors ligne.");
         } else if (target == p) {
            this.msg(p, "&cTu ne peux pas t'inviter toi-même.");
         } else if (this.cm().of(target) != null) {
            this.msg(p, "&c" + target.getName() + " est déjà dans un clan.");
         } else if (c.members().size() >= this.plugin.settings().maxMembers) {
            this.msg(p, "&cTon clan est complet.");
         } else {
            this.cm().invite(c, target.getUniqueId());
            this.msg(p, "&aInvitation envoyée à &f" + target.getName() + "&a.");
            this.plugin.menus().sendInviteMessage(target, c);
         }
      }
   }

   public void accept(Player p, String clanName) {
      if (this.cm().of(p) != null) {
         this.msg(p, "&cTu es déjà dans un clan.");
      } else {
         List<Clan> inv = this.cm().invitesOf(p.getUniqueId());
         Clan c = clanName == null ? (inv.size() == 1 ? inv.get(0) : null) : this.cm().byName(clanName);
         if (c != null && inv.contains(c)) {
            if (c.members().size() >= this.plugin.settings().maxMembers) {
               this.msg(p, "&cCe clan est complet.");
            } else {
               this.cm().addMember(c, p.getUniqueId(), Rank.RECRUE);
               this.cm().log(c, p.getName() + " a rejoint le clan");
               this.cm().broadcast(c, "&a" + p.getName() + " &7a rejoint le clan !");
               p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0F, 1.0F);
            }
         } else {
            this.msg(p, inv.isEmpty() ? "&cTu n'as aucune invitation." : "&cOuvre &fMenu > Invitations &cpour choisir le clan.");
         }
      }
   }

   public void deny(Player p, String clanName) {
      Clan c = this.cm().byName(clanName);
      if (c != null) {
         this.cm().removeInvite(p.getUniqueId(), c);
      }

      this.msg(p, "&7Invitation refusée.");
   }

   public void leave(Player p) {
      Clan c = this.requireClan(p);
      if (c != null) {
         if (c.rank(p.getUniqueId()) == Rank.CHEF) {
            if (c.members().size() == 1) {
               this.disband(p);
            } else {
               this.msg(p, "&cTu es le chef : nomme un autre chef (Menu > Membres) ou dissous le clan (Paramètres).");
            }
         } else {
            this.cm().removeMember(c, p.getUniqueId());
            this.plugin.clanChatToggle().remove(p.getUniqueId());
            this.cm().log(c, p.getName() + " a quitté le clan");
            this.cm().broadcast(c, "&e" + p.getName() + " &7a quitté le clan.");
            this.msg(p, "&7Tu as quitté &f" + c.name() + "&7.");
         }
      }
   }

   public void kick(Player p, UUID target) {
      Clan c = this.requireClan(p);
      if (c != null && this.requireRank(p, c, "kick")) {
         Rank tr = c.rank(target);
         if (tr == null) {
            this.msg(p, "&cCe joueur n'est pas dans ton clan.");
         } else if (target.equals(p.getUniqueId())) {
            this.msg(p, "&cUtilise Paramètres > Quitter le clan.");
         } else if (tr.atLeast(c.rank(p.getUniqueId()))) {
            this.msg(p, "&cTu ne peux exclure que des grades inférieurs au tien.");
         } else {
            String name = this.cm().nameOf(target);
            this.cm().removeMember(c, target);
            this.plugin.clanChatToggle().remove(target);
            this.cm().log(c, p.getName() + " a exclu " + name);
            this.cm().broadcast(c, "&c" + name + " &7a été exclu par " + p.getName() + ".");
            Player t = Bukkit.getPlayer(target);
            if (t != null) {
               this.msg(t, "&cTu as été exclu du clan &f" + c.name() + "&c.");
            }
         }
      }
   }

   public void promote(Player p, UUID target) {
      this.changeRank(p, target, true);
   }

   public void demote(Player p, UUID target) {
      this.changeRank(p, target, false);
   }

   private void changeRank(Player p, UUID target, boolean up) {
      Clan c = this.requireClan(p);
      if (c != null && this.requireRank(p, c, "promote")) {
         Rank tr = c.rank(target);
         if (tr == null) {
            this.msg(p, "&cCe joueur n'est pas dans ton clan.");
         } else {
            Rank mine = c.rank(p.getUniqueId());
            if (!target.equals(p.getUniqueId()) && !tr.atLeast(mine)) {
               Rank nr = up ? tr.next() : tr.previous();
               if (up && nr.atLeast(mine)) {
                  this.msg(p, "&cImpossible de promouvoir au-delà de " + mine.previous().colored() + "&c. Pour céder la tête du clan : Membres > Nommer chef");
               } else if (nr == tr) {
                  this.msg(p, "&cDéjà au grade minimum.");
               } else {
                  this.cm().setRank(c, target, nr);
                  String name = this.cm().nameOf(target);
                  this.cm().log(c, p.getName() + (up ? " a promu " : " a rétrogradé ") + name + " " + Text.strip(nr.display()));
                  this.cm().broadcast(c, "&f" + name + " &7est maintenant " + nr.colored() + "&7.");
               }
            } else {
               this.msg(p, "&cTu ne peux pas modifier ce grade.");
            }
         }
      }
   }

   public void transfer(Player p, UUID target) {
      Clan c = this.requireClan(p);
      if (c != null) {
         if (c.rank(p.getUniqueId()) != Rank.CHEF) {
            this.msg(p, "&cSeul le chef peut faire ça.");
         } else if (c.rank(target) != null && !target.equals(p.getUniqueId())) {
            this.cm().setRank(c, target, Rank.CHEF);
            this.cm().setRank(c, p.getUniqueId(), Rank.OFFICIER);
            String name = this.cm().nameOf(target);
            this.cm().log(c, p.getName() + " a cédé la tête du clan à " + name);
            this.cm().broadcast(c, "&6" + name + " &7est le nouveau chef du clan !");
         } else {
            this.msg(p, "&cJoueur invalide.");
         }
      }
   }

   public void claim(Player p) {
      this.claim(p, ChunkPos.of(p.getLocation()));
   }

   public void claim(Player p, ChunkPos pos) {
      Clan c = this.requireClan(p);
      if (c != null && this.requireRank(p, c, "claim")) {
         String err = this.cm().canClaim(c, pos);
         if (err != null) {
            this.msg(p, err);
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0F, 1.0F);
         } else {
            boolean first = c.claims().isEmpty();
            this.cm().claim(c, pos);
            if (first) {
               Location home = p.getLocation();
               if (ChunkPos.of(home).equals(pos)) {
                  c.home(home);
               } else {
                  World w = Bukkit.getWorld(pos.world());
                  int x = pos.x() * 16 + 8;
                  int z = pos.z() * 16 + 8;
                  c.home(new Location(w, x + 0.5, w.getHighestBlockYAt(x, z) + 1, z + 0.5));
               }

               this.cm().log(c, p.getName() + " a fondé la capitale en " + pos);
               this.cm().broadcast(c, "&6★ &eCapitale fondée ! &7Elle est protégée même en guerre. &fMenu > Capitale &7pour y revenir.");
            } else {
               this.cm().log(c, p.getName() + " a claim le chunk " + pos);
               this.msg(p, "&aChunk " + pos + " claim ! &7(" + c.claims().size() + "/" + this.cm().maxClaims(c) + ")");
            }

            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1.0F, 1.4F);
            this.plugin.borders().add(p.getUniqueId());
            Bukkit.getScheduler().runTaskLater(this.plugin, () -> this.plugin.borders().remove(p.getUniqueId()), 200L);
         }
      }
   }

   public void unclaim(Player p) {
      this.unclaim(p, ChunkPos.of(p.getLocation()));
   }

   public void unclaim(Player p, ChunkPos pos) {
      Clan c = this.requireClan(p);
      if (c != null && this.requireRank(p, c, "claim")) {
         String err = this.cm().canUnclaim(c, pos);
         if (err != null) {
            this.msg(p, err);
         } else {
            this.cm().unclaim(c, pos);
            this.cm().log(c, p.getName() + " a retiré le chunk " + pos);
            this.msg(p, "&eChunk " + pos + " retiré. &7(" + c.claims().size() + "/" + this.cm().maxClaims(c) + ")");
         }
      }
   }

   public void unclaimAll(Player p) {
      Clan c = this.requireClan(p);
      if (c != null) {
         if (c.rank(p.getUniqueId()) != Rank.CHEF) {
            this.msg(p, "&cSeul le chef peut faire ça.");
         } else if (this.plugin.war().warOf(c) != null) {
            this.msg(p, "&cImpossible pendant une guerre.");
         } else {
            this.cm().unclaimAll(c);
            this.cm().log(c, p.getName() + " a retiré tout le territoire");
            this.cm().broadcast(c, "&cTout le territoire du clan a été retiré.");
         }
      }
   }

   public void moveCapital(Player p) {
      Clan c = this.requireClan(p);
      if (c != null) {
         if (c.rank(p.getUniqueId()) != Rank.CHEF) {
            this.msg(p, "&cSeul le chef peut déplacer la capitale.");
         } else if (this.plugin.war().warOf(c) != null) {
            this.msg(p, "&cImpossible pendant une guerre.");
         } else {
            ChunkPos pos = ChunkPos.of(p.getLocation());
            if (!c.claims().contains(pos)) {
               this.msg(p, "&cTu dois être dans un chunk de ton clan.");
            } else {
               ChunkPos old = c.capital();
               c.capital(pos);
               if (this.cm().outpostCount(c) > this.cm().maxOutposts(c)) {
                  c.capital(old);
                  this.msg(p, "&cImpossible : cela créerait trop d'avant-postes.");
               } else {
                  c.home(p.getLocation());
                  this.cm().dirty();
                  this.plugin.bluemap().refresh();
                  this.cm().log(c, p.getName() + " a déplacé la capitale en " + pos);
                  this.cm().broadcast(c, "&6★ &eLa capitale a été déplacée en &f" + pos + "&e.");
               }
            }
         }
      }
   }

   public void setHome(Player p) {
      Clan c = this.requireClan(p);
      if (c != null && this.requireRank(p, c, "set-home")) {
         ChunkPos pos = ChunkPos.of(p.getLocation());
         if (!this.cm().isCapitalZone(c, pos)) {
            this.msg(p, "&cLe home doit être dans la zone de ta capitale.");
         } else {
            c.home(p.getLocation());
            this.cm().dirty();
            this.msg(p, "&aHome du clan défini ici.");
         }
      }
   }

   public void home(Player p) {
      Clan c = this.requireClan(p);
      if (c != null) {
         if (c.home() != null && c.home().getWorld() != null) {
            int warmup = this.plugin.settings().homeWarmup;
            if (warmup > 0 && !this.plugin.bypass().contains(p.getUniqueId())) {
               BukkitTask old = this.homeTasks.remove(p.getUniqueId());
               if (old != null) {
                  old.cancel();
               }

               Location start = p.getLocation();
               this.msg(p, "&7Téléportation dans &f" + warmup + "s&7, ne bouge pas...");
               int[] left = new int[]{warmup};
               this.homeTasks.put(p.getUniqueId(), Bukkit.getScheduler().runTaskTimer(this.plugin, () -> {
                  if (p.isOnline() && p.getLocation().getWorld() == start.getWorld() && !(p.getLocation().distanceSquared(start) > 0.5)) {
                     left[0]--;
                     if (left[0] <= 0) {
                        BukkitTask t = this.homeTasks.remove(p.getUniqueId());
                        if (t != null) {
                           t.cancel();
                        }

                        Clan now = this.cm().of(p);
                        if (now == null || now.home() == null) {
                           return;
                        }

                        p.teleportAsync(now.home()).thenRun(() -> p.playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1.0F, 1.0F));
                     } else {
                        p.sendActionBar(Text.c("&7Téléportation dans &f" + left[0] + "s"));
                     }
                  } else {
                     BukkitTask tx = this.homeTasks.remove(p.getUniqueId());
                     if (tx != null) {
                        tx.cancel();
                     }

                     if (p.isOnline()) {
                        this.msg(p, "&cTéléportation annulée (tu as bougé).");
                     }
                  }
               }, 20L, 20L));
            } else {
               p.teleportAsync(c.home());
            }
         } else {
            this.msg(p, "&cTon clan n'a pas encore de capitale. &7Menu > Territoire");
         }
      }
   }

   public void deposit(Player p, int amount) {
      Clan c = this.requireClan(p);
      if (c != null) {
         int has = 0;

         for (ItemStack it : p.getInventory().getStorageContents()) {
            if (this.isCurrency(it)) {
               has += it.getAmount();
            }
         }

         if (amount <= 0) {
            amount = has;
         }

         if (has <= 0 || amount <= 0) {
            this.msg(p, "&cTu n'as pas de " + this.plugin.settings().currencyName + " sur toi.");
         } else if (amount > has) {
            this.msg(p, "&cTu n'as que &f" + has + " " + this.plugin.settings().currencyName + "&c.");
         } else {
            int toRemove = amount;
            ItemStack[] content = p.getInventory().getStorageContents();

            for (int i = 0; i < content.length && toRemove > 0; i++) {
               ItemStack itx = content[i];
               if (this.isCurrency(itx)) {
                  int take = Math.min(toRemove, itx.getAmount());
                  itx.setAmount(itx.getAmount() - take);
                  toRemove -= take;
               }
            }

            p.getInventory().setStorageContents(content);
            c.bank(c.bank() + amount);
            this.cm().log(c, p.getName() + " a déposé " + amount + " " + this.plugin.settings().currencyName);
            this.msg(p, "&aTu as déposé &f" + amount + " " + this.plugin.settings().currencyName + "&a. Banque : &b" + c.bank());
            p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.0F, 1.2F);
         }
      }
   }

   public void withdraw(Player p, int amount) {
      Clan c = this.requireClan(p);
      if (c != null && this.requireRank(p, c, "bank-withdraw")) {
         if (amount <= 0) {
            this.msg(p, "&cMontant invalide.");
         } else if (amount > c.bank()) {
            this.msg(p, "&cLa banque ne contient que &f" + c.bank() + "&c.");
         } else {
            c.bank(c.bank() - amount);
            this.giveCurrency(p, amount);
            this.cm().log(c, p.getName() + " a retiré " + amount + " " + this.plugin.settings().currencyName);
            this.msg(p, "&aTu as retiré &f" + amount + " " + this.plugin.settings().currencyName + "&a. Banque : &b" + c.bank());
         }
      }
   }

   private boolean isCurrency(ItemStack it) {
      String spec = this.plugin.settings().currencySpec;
      return spec.startsWith("windy:")
         ? this.plugin.items().matches(it, spec)
         : it != null && it.getType() == this.plugin.settings().currency && !it.hasItemMeta();
   }

   public void giveCurrency(Player p, long amount) {
      String spec = this.plugin.settings().currencySpec;

      while (amount > 0L) {
         int n = (int)Math.min(64L, amount);
         amount -= n;
         ItemStack stack = spec.startsWith("windy:") ? this.plugin.items().create(spec.substring(6)) : null;
         if (stack == null) {
            stack = new ItemStack(this.plugin.settings().currency);
         }

         stack.setAmount(n);
         p.getInventory().addItem(new ItemStack[]{stack}).values().forEach(rest -> p.getWorld().dropItemNaturally(p.getLocation(), rest));
      }
   }

   public void openChest(Player p) {
      Clan c = this.requireClan(p);
      if (c != null && this.requireRank(p, c, "chest")) {
         p.openInventory(this.cm().chest(c));
         p.playSound(p.getLocation(), Sound.BLOCK_ENDER_CHEST_OPEN, 1.0F, 1.0F);
      }
   }

   public void ally(Player p, Clan target) {
      Clan c = this.requireClan(p);
      if (c != null && this.requireRank(p, c, "relations")) {
         if (target != null && target != c) {
            if (c.allies().contains(target.id())) {
               this.msg(p, "&cVous êtes déjà alliés.");
            } else if (this.plugin.war().warOf(c) != null && this.plugin.war().warOf(c) == this.plugin.war().warOf(target)) {
               this.msg(p, "&cVous êtes en guerre !");
            } else if (c.allyRequests().contains(target.id())) {
               this.cm().setAlly(c, target);
               this.cm().log(c, "Alliance conclue avec " + target.name());
               this.cm().log(target, "Alliance conclue avec " + c.name());
               Bukkit.broadcast(Text.c(this.plugin.settings().prefix + "&b✦ " + c.name() + " &7et &b" + target.name() + " &7sont maintenant alliés."));
               this.plugin.discord().send("alliance", "\ud83e\udd1d **" + c.name() + "** et **" + target.name() + "** sont maintenant alliés.");
            } else {
               target.allyRequests().add(c.id());
               this.cm().dirty();
               this.msg(p, "&bDemande d'alliance envoyée à &f" + target.name() + "&b.");

               for (Player t : this.cm().onlineMembers(target)) {
                  if (target.rank(t.getUniqueId()).atLeast(this.plugin.settings().rank("relations"))) {
                     this.msg(t, "&b" + c.name() + " &7propose une alliance. &fMenu > Diplomatie &7pour accepter.");
                  }
               }
            }
         } else {
            this.msg(p, "&cClan introuvable.");
         }
      }
   }

   public void neutral(Player p, Clan target) {
      Clan c = this.requireClan(p);
      if (c != null && this.requireRank(p, c, "relations")) {
         if (target != null && target != c) {
            boolean wasAlly = c.allies().contains(target.id());
            this.cm().setNeutral(c, target);
            this.cm().log(c, "Relation neutre avec " + target.name());
            this.msg(p, "&7Vous êtes maintenant neutres envers &f" + target.name() + "&7.");
            if (wasAlly) {
               this.cm().broadcast(target, "&e" + c.name() + " &7a rompu votre alliance.");
            }
         } else {
            this.msg(p, "&cClan introuvable.");
         }
      }
   }

   public void enemy(Player p, Clan target) {
      Clan c = this.requireClan(p);
      if (c != null && this.requireRank(p, c, "relations")) {
         if (target != null && target != c) {
            this.cm().setEnemy(c, target);
            this.cm().log(c, target.name() + " déclaré ennemi");
            this.msg(p, "&c" + target.name() + " &7est maintenant votre ennemi.");
            this.cm().broadcast(target, "&c" + c.name() + " &7vous a déclarés ennemis !");
         } else {
            this.msg(p, "&cClan introuvable.");
         }
      }
   }

   public void declareWar(Player p, Clan target) {
      this.declareWar(p, target, null);
   }

   public void declareWar(Player p, Clan target, String reason) {
      Clan c = this.requireClan(p);
      if (c != null && this.requireRank(p, c, "war")) {
         if (target == null) {
            this.msg(p, "&cClan introuvable.");
         } else if (reason != null && reason.strip().length() >= 5) {
            String err = this.plugin.war().declare(c, target, reason);
            if (err != null) {
               this.msg(p, err);
            }
         } else {
            this.msg(p, "&7Le règlement impose une &fraison &7pour déclarer une guerre. Écris-la dans l'enclume.");
            TextInput.ask(
               this.plugin, p, "Raison de la guerre", "raison", r -> this.declareWar(p, target, r), () -> this.msg(p, "&7Déclaration de guerre annulée.")
            );
         }
      }
   }

   public void surrender(Player p) {
      Clan c = this.requireClan(p);
      if (c != null && this.requireRank(p, c, "war")) {
         if (this.plugin.war().warOf(c) == null) {
            this.msg(p, "&cTon clan n'est pas en guerre.");
         } else {
            this.plugin.war().surrender(c);
         }
      }
   }

   public void color(Player p, String color) {
      Clan c = this.requireClan(p);
      if (c != null) {
         if (c.rank(p.getUniqueId()) != Rank.CHEF) {
            this.msg(p, "&cSeul le chef peut changer la couleur.");
         } else {
            String code = COLORS.get(color.toLowerCase(Locale.ROOT));
            if (code == null) {
               this.msg(p, "&cCouleurs : &f" + String.join(", ", COLORS.keySet()));
            } else {
               c.color(code);
               this.cm().dirty();
               this.plugin.bluemap().refresh();
               this.msg(p, "&7Nouvelle couleur : " + c.coloredTag() + " " + c.name());
            }
         }
      }
   }

   public void description(Player p, String desc) {
      Clan c = this.requireClan(p);
      if (c != null) {
         if (!c.rank(p.getUniqueId()).atLeast(Rank.OFFICIER)) {
            this.msg(p, "&cIl faut être Officier.");
         } else {
            c.description(desc.length() > 80 ? desc.substring(0, 80) : desc);
            this.cm().dirty();
            this.msg(p, "&aDescription mise à jour.");
         }
      }
   }

   public void toggleBorders(Player p) {
      if (this.plugin.borders().remove(p.getUniqueId())) {
         this.msg(p, "&7Bordures &cmasquées&7.");
      } else {
         this.plugin.borders().add(p.getUniqueId());
         this.msg(p, "&7Bordures &aaffichées&7 (vert = toi, bleu = allié, rouge = ennemi, jaune = spawn).");
      }
   }

   public void toggleScoreboard(Player p) {
      boolean on = this.plugin.scoreboard().toggle(p);
      this.msg(p, "&7Panneau d'infos " + (on ? "&aaffiché" : "&cmasqué") + "&7.");
   }

   public void toggleChat(Player p) {
      if (this.requireClan(p) != null) {
         if (this.plugin.clanChatToggle().remove(p.getUniqueId())) {
            this.msg(p, "&7Chat : &fpublic");
         } else {
            this.plugin.clanChatToggle().add(p.getUniqueId());
            this.msg(p, "&7Chat : &aclan &7(tes messages ne vont qu'à ton clan)");
         }
      }
   }

   static {
      COLORS.put("vert", "&a");
      COLORS.put("vert_fonce", "&2");
      COLORS.put("bleu", "&9");
      COLORS.put("cyan", "&b");
      COLORS.put("bleu_fonce", "&1");
      COLORS.put("turquoise", "&3");
      COLORS.put("rouge", "&c");
      COLORS.put("rouge_fonce", "&4");
      COLORS.put("violet", "&5");
      COLORS.put("rose", "&d");
      COLORS.put("or", "&6");
      COLORS.put("jaune", "&e");
      COLORS.put("blanc", "&f");
      COLORS.put("gris", "&7");
      COLORS.put("gris_fonce", "&8");
   }
}
