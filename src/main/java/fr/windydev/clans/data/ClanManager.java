package fr.windydev.clans.data;

import fr.windydev.clans.WindyClans;
import fr.windydev.clans.model.ChunkPos;
import fr.windydev.clans.model.Clan;
import fr.windydev.clans.model.Rank;
import fr.windydev.clans.model.Relation;
import fr.windydev.clans.util.Text;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Map.Entry;
import java.util.stream.Collectors;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

public class ClanManager {
   private final WindyClans plugin;
   private final File file;
   private final Map<UUID, Clan> clans = new LinkedHashMap<>();
   private final Map<UUID, UUID> playerClan = new HashMap<>();
   private final Map<ChunkPos, UUID> claimIndex = new HashMap<>();
   private final Map<UUID, Map<UUID, Long>> invites = new HashMap<>();
   private final Map<String, Long> warCooldowns = new HashMap<>();
   private boolean dirty;
   private final Map<UUID, List<?>> pendingChests = new HashMap<>();

   public ClanManager(WindyClans plugin) {
      this.plugin = plugin;
      this.file = new File(plugin.getDataFolder(), "clans.yml");
   }

   private Settings s() {
      return this.plugin.settings();
   }

   public Collection<Clan> all() {
      return this.clans.values();
   }

   public Clan get(UUID id) {
      return id == null ? null : this.clans.get(id);
   }

   public Clan of(UUID player) {
      return this.get(this.playerClan.get(player));
   }

   public Clan of(Player p) {
      return this.of(p.getUniqueId());
   }

   public Clan byName(String name) {
      if (name == null) {
         return null;
      } else {
         for (Clan c : this.clans.values()) {
            if (c.name().equalsIgnoreCase(name) || c.tag().equalsIgnoreCase(name)) {
               return c;
            }
         }

         return null;
      }
   }

   public Clan at(ChunkPos pos) {
      return this.get(this.claimIndex.get(pos));
   }

   public Clan at(Location loc) {
      return this.at(ChunkPos.of(loc));
   }

   public List<String> names() {
      return this.clans.values().stream().map(Clan::name).collect(Collectors.toList());
   }

   public boolean isClaimWorld(String world) {
      return this.s().claimWorlds.contains(world);
   }

   public boolean isSpawnZone(ChunkPos pos) {
      if (this.plugin.hub() != null && this.plugin.hub().zoneTouches(pos.world(), pos.x(), pos.z())) {
         return true;
      } else if (this.s().spawnRadius <= 0) {
         return false;
      } else {
         World main = (World)Bukkit.getWorlds().get(0);
         if (!main.getName().equals(pos.world())) {
            return false;
         } else {
            ChunkPos spawn = ChunkPos.of(main.getSpawnLocation());
            return spawn.distance(pos) <= this.s().spawnRadius;
         }
      }
   }

   public boolean isCapitalZone(Clan clan, ChunkPos pos) {
      return clan != null && clan.capital() != null && clan.claims().contains(pos) && clan.capital().distance(pos) <= this.s().capitalRadius;
   }

   public Clan capitalAt(ChunkPos pos) {
      Clan c = this.at(pos);
      return this.isCapitalZone(c, pos) ? c : null;
   }

   public Relation relation(Clan a, Clan b) {
      if (a == null || b == null) {
         return Relation.NEUTRAL;
      } else if (a == b) {
         return Relation.SELF;
      } else if (this.plugin.war().atWar(a, b)) {
         return Relation.WAR;
      } else if (a.allies().contains(b.id())) {
         return Relation.ALLY;
      } else {
         return !a.enemies().contains(b.id()) && !b.enemies().contains(a.id()) ? Relation.NEUTRAL : Relation.ENEMY;
      }
   }

   public Relation relation(Player p, Clan target) {
      return this.relation(this.of(p), target);
   }

   public void setAlly(Clan a, Clan b) {
      a.enemies().remove(b.id());
      b.enemies().remove(a.id());
      a.allyRequests().remove(b.id());
      b.allyRequests().remove(a.id());
      a.allies().add(b.id());
      b.allies().add(a.id());
      this.dirty();
   }

   public void setNeutral(Clan a, Clan b) {
      a.allies().remove(b.id());
      b.allies().remove(a.id());
      a.enemies().remove(b.id());
      a.allyRequests().remove(b.id());
      b.allyRequests().remove(a.id());
      this.dirty();
   }

   public void setEnemy(Clan a, Clan b) {
      a.allies().remove(b.id());
      b.allies().remove(a.id());
      a.allyRequests().remove(b.id());
      b.allyRequests().remove(a.id());
      a.enemies().add(b.id());
      this.dirty();
   }

   public Clan create(String name, String tag, Player leader) {
      Clan c = new Clan(UUID.randomUUID(), name, tag, System.currentTimeMillis());
      c.members().put(leader.getUniqueId(), Rank.CHEF);
      this.clans.put(c.id(), c);
      this.playerClan.put(leader.getUniqueId(), c.id());
      this.invites.remove(leader.getUniqueId());
      this.log(c, leader.getName() + " a fondé le clan");
      this.dirty();
      if (this.plugin.bot() != null) {
         this.plugin.bot().requestSync();
      }

      return c;
   }

   public void disband(Clan c) {
      for (UUID m : c.members().keySet()) {
         this.playerClan.remove(m);
      }

      for (ChunkPos p : c.claims()) {
         this.claimIndex.remove(p);
      }

      for (Clan o : this.clans.values()) {
         o.allies().remove(c.id());
         o.enemies().remove(c.id());
         o.allyRequests().remove(c.id());
      }

      for (Map<UUID, Long> m : this.invites.values()) {
         m.remove(c.id());
      }

      this.plugin.war().clanRemoved(c);
      if (c.chest() != null) {
         new ArrayList<>(c.chest().getViewers()).forEach(v -> v.closeInventory());
      }

      this.clans.remove(c.id());
      this.dirty();
      if (this.plugin.bot() != null) {
         this.plugin.bot().requestSync();
      }

      this.plugin.bluemap().refresh();
   }

   public void addMember(Clan c, UUID player, Rank rank) {
      c.members().put(player, rank);
      this.playerClan.put(player, c.id());
      this.invites.remove(player);
      this.dirty();
      if (this.plugin.bot() != null) {
         this.plugin.bot().requestSync();
      }
   }

   public void removeMember(Clan c, UUID player) {
      c.members().remove(player);
      this.playerClan.remove(player);
      this.dirty();
      if (this.plugin.bot() != null) {
         this.plugin.bot().requestSync();
      }
   }

   public void setRank(Clan c, UUID player, Rank rank) {
      c.members().put(player, rank);
      this.dirty();
      if (this.plugin.bot() != null) {
         this.plugin.bot().requestSync();
      }
   }

   public int onlineCount(Clan c) {
      int n = 0;

      for (UUID u : c.members().keySet()) {
         if (Bukkit.getPlayer(u) != null) {
            n++;
         }
      }

      return n;
   }

   public List<Player> onlineMembers(Clan c) {
      List<Player> list = new ArrayList<>();

      for (UUID u : c.members().keySet()) {
         Player p = Bukkit.getPlayer(u);
         if (p != null) {
            list.add(p);
         }
      }

      return list;
   }

   public void broadcast(Clan c, String msg) {
      for (Player p : this.onlineMembers(c)) {
         this.plugin.msg(p, msg);
      }
   }

   public void log(Clan c, String entry) {
      String date = new SimpleDateFormat("dd/MM HH:mm").format(new Date());
      c.addLog("&8" + date + " &7" + entry);
      this.dirty();
   }

   public void invite(Clan c, UUID player) {
      this.invites.computeIfAbsent(player, k -> new HashMap<>()).put(c.id(), System.currentTimeMillis() + this.s().inviteExpire * 1000L);
   }

   public List<Clan> invitesOf(UUID player) {
      Map<UUID, Long> m = this.invites.get(player);
      if (m == null) {
         return List.of();
      } else {
         long now = System.currentTimeMillis();
         m.values().removeIf(exp -> exp < now);
         List<Clan> out = new ArrayList<>();

         for (UUID id : m.keySet()) {
            Clan c = this.get(id);
            if (c != null) {
               out.add(c);
            }
         }

         return out;
      }
   }

   public boolean hasInvite(UUID player, Clan c) {
      return this.invitesOf(player).contains(c);
   }

   public void removeInvite(UUID player, Clan c) {
      Map<UUID, Long> m = this.invites.get(player);
      if (m != null) {
         m.remove(c.id());
      }
   }

   public int maxClaims(Clan c) {
      return this.s().claimBase + c.members().size() * this.s().claimPerMember + c.level() * this.s().claimPerLevel;
   }

   public int maxOutposts(Clan c) {
      return c.level() / this.s().outpostEvery;
   }

   public int chestRows(Clan c) {
      return Math.min(6, 1 + c.level() / this.s().chestRowEvery);
   }

   public int outpostCount(Clan c) {
      return this.outpostCount(c.claims(), c.capital());
   }

   private int outpostCount(Set<ChunkPos> claims, ChunkPos capital) {
      Set<ChunkPos> left = new HashSet<>(claims);
      int groups = 0;
      boolean capitalFound = false;

      while (!left.isEmpty()) {
         ChunkPos start = left.iterator().next();
         Deque<ChunkPos> q = new ArrayDeque<>();
         q.add(start);
         left.remove(start);
         boolean hasCapital = false;

         while (!q.isEmpty()) {
            ChunkPos p = q.poll();
            if (p.equals(capital)) {
               hasCapital = true;
            }

            for (ChunkPos n : new ChunkPos[]{p.offset(1, 0), p.offset(-1, 0), p.offset(0, 1), p.offset(0, -1)}) {
               if (left.remove(n)) {
                  q.add(n);
               }
            }
         }

         if (hasCapital) {
            capitalFound = true;
         } else {
            groups++;
         }
      }

      return capitalFound ? groups : Math.max(0, groups - 1);
   }

   public String canClaim(Clan c, ChunkPos pos) {
      if (!this.isClaimWorld(pos.world())) {
         return "&cOn ne peut pas claim dans ce monde.";
      } else if (this.isSpawnZone(pos)) {
         return "&cTu es dans la zone du spawn, impossible de claim ici.";
      } else {
         Clan owner = this.at(pos);
         if (owner == c) {
            return "&cCe chunk appartient déjà à ton clan.";
         } else if (owner != null) {
            return "&cCe chunk appartient à " + owner.coloredName() + "&c.";
         } else if (c.claims().size() >= this.maxClaims(c)) {
            return "&cLimite de claims atteinte (&f" + this.maxClaims(c) + "&c). Recrute ou monte de niveau !";
         } else {
            int buf = this.s().bufferChunks;

            for (int dx = -buf; dx <= buf; dx++) {
               for (int dz = -buf; dz <= buf; dz++) {
                  Clan o = this.at(pos.offset(dx, dz));
                  if (o != null && o != c) {
                     return "&cTrop proche du territoire de " + o.coloredName() + "&c.";
                  }
               }
            }

            if (c.claims().isEmpty()) {
               return null;
            } else {
               boolean adjacent = c.claims().contains(pos.offset(1, 0))
                  || c.claims().contains(pos.offset(-1, 0))
                  || c.claims().contains(pos.offset(0, 1))
                  || c.claims().contains(pos.offset(0, -1));
               if (!adjacent) {
                  Set<ChunkPos> after = new HashSet<>(c.claims());
                  after.add(pos);
                  if (this.outpostCount(after, c.capital()) > this.maxOutposts(c)) {
                     return "&cCe chunk doit toucher ton territoire. Avant-postes : &f"
                        + this.outpostCount(c)
                        + "/"
                        + this.maxOutposts(c)
                        + " &c(1 avant-poste tous les "
                        + this.s().outpostEvery
                        + " niveaux).";
                  }
               }

               return null;
            }
         }
      }
   }

   public void claim(Clan c, ChunkPos pos) {
      c.claims().add(pos);
      this.claimIndex.put(pos, c.id());
      if (c.capital() == null) {
         c.capital(pos);
      }

      this.dirty();
      this.plugin.bluemap().refresh();
   }

   public String canUnclaim(Clan c, ChunkPos pos) {
      if (!c.claims().contains(pos)) {
         return "&cCe chunk n'appartient pas à ton clan.";
      } else if (pos.equals(c.capital()) && c.claims().size() > 1) {
         return "&cC'est le chunk de ta capitale. Déplace-la d'abord (Paramètres > Déplacer la capitale).";
      } else {
         Set<ChunkPos> after = new HashSet<>(c.claims());
         after.remove(pos);
         return !after.isEmpty() && this.outpostCount(after, c.capital()) > this.maxOutposts(c)
            ? "&cRetirer ce chunk couperait ton territoire en trop de morceaux."
            : null;
      }
   }

   public void unclaim(Clan c, ChunkPos pos) {
      c.claims().remove(pos);
      this.claimIndex.remove(pos);
      if (pos.equals(c.capital())) {
         c.capital(null);
         c.home(null);
      }

      this.dirty();
      this.plugin.bluemap().refresh();
   }

   public void unclaimAll(Clan c) {
      for (ChunkPos p : c.claims()) {
         this.claimIndex.remove(p);
      }

      c.claims().clear();
      c.capital(null);
      c.home(null);
      this.dirty();
      this.plugin.bluemap().refresh();
   }

   public long xpForNext(int level) {
      return Math.round(this.s().xpBase * Math.pow(level, this.s().xpExponent));
   }

   public void addXp(Clan c, long amount) {
      if (amount > 0L && c.level() < this.s().maxLevel) {
         c.xp(c.xp() + amount);

         while (c.level() < this.s().maxLevel && c.xp() >= this.xpForNext(c.level())) {
            c.xp(c.xp() - this.xpForNext(c.level()));
            c.level(c.level() + 1);
            this.broadcast(
               c,
               "&a&lNIVEAU SUPÉRIEUR ! &7Ton clan passe niveau &e"
                  + c.level()
                  + "&7. Claims max : &f"
                  + this.maxClaims(c)
                  + "&7, avant-postes : &f"
                  + this.maxOutposts(c)
                  + "&7."
            );

            for (Player p : this.onlineMembers(c)) {
               p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0F, 1.0F);
            }

            this.log(c, "Le clan passe niveau " + c.level());
            this.plugin.discord().send("level-up", "⭐ Le clan **" + c.name() + "** passe niveau **" + c.level() + "** !");
            this.resizeChest(c);
         }

         if (c.level() >= this.s().maxLevel) {
            c.xp(0L);
         }

         this.dirty();
      }
   }

   public List<Clan> top() {
      return this.clans
         .values()
         .stream()
         .sorted(Comparator.<Clan>comparingInt(Clan::level).thenComparingLong(Clan::xp).reversed().thenComparing(Clan::name, String.CASE_INSENSITIVE_ORDER))
         .collect(Collectors.toList());
   }

   public Inventory chest(Clan c) {
      if (c.chest() == null) {
         c.chest(Bukkit.createInventory(new ClanManager.ClanChestHolder(c.id()), this.chestRows(c) * 9, Text.c("&8Coffre de " + c.name())));
      }

      return c.chest();
   }

   private void resizeChest(Clan c) {
      if (c.chest() != null) {
         int size = this.chestRows(c) * 9;
         if (c.chest().getSize() != size) {
            ItemStack[] old = c.chest().getContents();
            new ArrayList<>(c.chest().getViewers()).forEach(v -> v.closeInventory());
            c.chest(null);
            Inventory inv = this.chest(c);

            for (int i = 0; i < Math.min(old.length, size); i++) {
               inv.setItem(i, old[i]);
            }
         }
      }
   }

   public long lastWar(Clan a, Clan b) {
      return this.warCooldowns.getOrDefault(this.pairKey(a, b), 0L);
   }

   public void markWar(Clan a, Clan b) {
      this.warCooldowns.put(this.pairKey(a, b), System.currentTimeMillis());
      this.dirty();
   }

   private String pairKey(Clan a, Clan b) {
      return a.id().compareTo(b.id()) < 0 ? a.id() + "|" + b.id() : b.id() + "|" + a.id();
   }

   public void dirty() {
      this.dirty = true;
   }

   public void saveIfDirty() {
      if (this.dirty) {
         this.save();
      }
   }

   public void save() {
      YamlConfiguration y = new YamlConfiguration();

      for (Clan c : this.clans.values()) {
         String k = "clans." + c.id();
         y.set(k + ".name", c.name());
         y.set(k + ".tag", c.tag());
         y.set(k + ".color", c.color());
         y.set(k + ".description", c.description());
         y.set(k + ".created", c.createdAt());
         y.set(k + ".level", c.level());
         y.set(k + ".xp", c.xp());
         y.set(k + ".bank", c.bank());

         for (Entry<UUID, Rank> e : c.members().entrySet()) {
            y.set(k + ".members." + e.getKey(), e.getValue().name());
         }

         y.set(k + ".claims", c.claims().stream().map(ChunkPos::serialize).collect(Collectors.toList()));
         y.set(k + ".capital", c.capital() == null ? null : c.capital().serialize());
         y.set(k + ".home", c.home());
         y.set(k + ".allies", c.allies().stream().map(UUID::toString).collect(Collectors.toList()));
         y.set(k + ".enemies", c.enemies().stream().map(UUID::toString).collect(Collectors.toList()));
         y.set(k + ".ally-requests", c.allyRequests().stream().map(UUID::toString).collect(Collectors.toList()));
         y.set(k + ".log", new ArrayList<>(c.log()));
         y.set(k + ".bosses", new ArrayList<>(c.bossesBeaten()));
         long now = System.currentTimeMillis();

         for (Entry<Integer, Long> e : c.bossCooldowns().entrySet()) {
            if (e.getValue() > now) {
               y.set(k + ".boss-cooldowns." + e.getKey(), e.getValue());
            }
         }

         if (c.chest() != null) {
            y.set(k + ".chest", Arrays.asList(c.chest().getContents()));
         } else if (this.pendingChests.containsKey(c.id())) {
            y.set(k + ".chest", this.pendingChests.get(c.id()));
         }
      }

      this.warCooldowns.forEach((key, v) -> y.set("war-cooldowns." + key.replace('|', '_'), v));

      try {
         this.plugin.getDataFolder().mkdirs();
         y.save(this.file);
         this.dirty = false;
      } catch (IOException var9) {
         this.plugin.getLogger().severe("Impossible de sauvegarder clans.yml : " + var9.getMessage());
      }
   }

   public void load() {
      this.clans.clear();
      this.playerClan.clear();
      this.claimIndex.clear();
      this.pendingChests.clear();
      if (this.file.exists()) {
         YamlConfiguration y = YamlConfiguration.loadConfiguration(this.file);
         ConfigurationSection root = y.getConfigurationSection("clans");
         if (root != null) {
            for (String id : root.getKeys(false)) {
               ConfigurationSection s = root.getConfigurationSection(id);
               if (s != null) {
                  Clan c = new Clan(UUID.fromString(id), s.getString("name"), s.getString("tag"), s.getLong("created"));
                  c.color(s.getString("color", "&a"));
                  c.description(s.getString("description", "Aucune description."));
                  c.level(s.getInt("level", 1));
                  c.xp(s.getLong("xp"));
                  c.bank(s.getLong("bank"));
                  ConfigurationSection ms = s.getConfigurationSection("members");
                  if (ms != null) {
                     for (String u : ms.getKeys(false)) {
                        UUID uuid = UUID.fromString(u);
                        c.members().put(uuid, Rank.parse(ms.getString(u), Rank.MEMBRE));
                        this.playerClan.put(uuid, c.id());
                     }
                  }

                  for (String p : s.getStringList("claims")) {
                     ChunkPos pos = ChunkPos.deserialize(p);
                     c.claims().add(pos);
                     this.claimIndex.put(pos, c.id());
                  }

                  String cap = s.getString("capital");
                  if (cap != null) {
                     c.capital(ChunkPos.deserialize(cap));
                  }

                  c.home(s.getLocation("home"));
                  s.getStringList("allies").forEach(a -> c.allies().add(UUID.fromString(a)));
                  s.getStringList("enemies").forEach(a -> c.enemies().add(UUID.fromString(a)));
                  s.getStringList("ally-requests").forEach(a -> c.allyRequests().add(UUID.fromString(a)));
                  c.bossesBeaten().addAll(s.getIntegerList("bosses"));
                  ConfigurationSection bc = s.getConfigurationSection("boss-cooldowns");
                  if (bc != null) {
                     for (String lv : bc.getKeys(false)) {
                        c.bossCooldowns().put(Integer.parseInt(lv), bc.getLong(lv));
                     }
                  }

                  List<String> logs = s.getStringList("log");

                  for (int i = logs.size() - 1; i >= 0; i--) {
                     c.addLog(logs.get(i));
                  }

                  if (s.isList("chest")) {
                     this.pendingChests.put(c.id(), s.getList("chest"));
                  }

                  this.clans.put(c.id(), c);
               }
            }
         }

         ConfigurationSection wc = y.getConfigurationSection("war-cooldowns");
         if (wc != null) {
            for (String k : wc.getKeys(false)) {
               this.warCooldowns.put(k.replace('_', '|'), wc.getLong(k));
            }
         }

         for (Entry<UUID, List<?>> e : new HashMap<>(this.pendingChests).entrySet()) {
            Clan cx = this.clans.get(e.getKey());
            Inventory inv = this.chest(cx);
            List<?> items = e.getValue();

            for (int i = 0; i < Math.min(items.size(), inv.getSize()); i++) {
               if (items.get(i) instanceof ItemStack it) {
                  inv.setItem(i, it);
               }
            }
         }

         this.pendingChests.clear();
         this.plugin.getLogger().info(this.clans.size() + " clan(s) chargé(s), " + this.claimIndex.size() + " chunk(s) claim.");
      }
   }

   public String nameOf(UUID uuid) {
      OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
      return op.getName() == null ? uuid.toString().substring(0, 8) : op.getName();
   }

   public record ClanChestHolder(UUID clanId) implements InventoryHolder {
      public Inventory getInventory() {
         return null;
      }
   }
}
