package fr.windydev.clans.model;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.Map.Entry;
import org.bukkit.Location;
import org.bukkit.inventory.Inventory;

public class Clan {
   private final UUID id;
   private String name;
   private String tag;
   private String color = "&a";
   private String description = "Aucune description.";
   private final long createdAt;
   private final Map<UUID, Rank> members = new LinkedHashMap<>();
   private final Set<ChunkPos> claims = new LinkedHashSet<>();
   private ChunkPos capital;
   private Location home;
   private final Set<UUID> allies = new HashSet<>();
   private final Set<UUID> enemies = new HashSet<>();
   private final Set<UUID> allyRequests = new HashSet<>();
   private int level = 1;
   private long xp = 0L;
   private long bank = 0L;
   private Inventory chest;
   private final Deque<String> log = new ArrayDeque<>();
   private final Set<Integer> bossesBeaten = new TreeSet<>();
   private long bossRetryAt = 0L;
   private final Map<Integer, Long> bossCooldowns = new HashMap<>();

   public Set<Integer> bossesBeaten() {
      return this.bossesBeaten;
   }

   public long bossRetryAt() {
      return this.bossRetryAt;
   }

   public void bossRetryAt(long t) {
      this.bossRetryAt = t;
   }

   public Map<Integer, Long> bossCooldowns() {
      return this.bossCooldowns;
   }

   public Clan(UUID id, String name, String tag, long createdAt) {
      this.id = id;
      this.name = name;
      this.tag = tag;
      this.createdAt = createdAt;
   }

   public UUID id() {
      return this.id;
   }

   public String name() {
      return this.name;
   }

   public void name(String n) {
      this.name = n;
   }

   public String tag() {
      return this.tag;
   }

   public void tag(String t) {
      this.tag = t;
   }

   public String color() {
      return this.color;
   }

   public void color(String c) {
      this.color = c;
   }

   public String description() {
      return this.description;
   }

   public void description(String d) {
      this.description = d;
   }

   public long createdAt() {
      return this.createdAt;
   }

   public Map<UUID, Rank> members() {
      return this.members;
   }

   public Rank rank(UUID p) {
      return this.members.get(p);
   }

   public boolean isMember(UUID p) {
      return this.members.containsKey(p);
   }

   public UUID leader() {
      return this.members.entrySet().stream().filter(e -> e.getValue() == Rank.CHEF).map(Entry::getKey).findFirst().orElse(null);
   }

   public Set<ChunkPos> claims() {
      return this.claims;
   }

   public ChunkPos capital() {
      return this.capital;
   }

   public void capital(ChunkPos c) {
      this.capital = c;
   }

   public Location home() {
      return this.home;
   }

   public void home(Location l) {
      this.home = l;
   }

   public Set<UUID> allies() {
      return this.allies;
   }

   public Set<UUID> enemies() {
      return this.enemies;
   }

   public Set<UUID> allyRequests() {
      return this.allyRequests;
   }

   public int level() {
      return this.level;
   }

   public void level(int l) {
      this.level = l;
   }

   public long xp() {
      return this.xp;
   }

   public void xp(long x) {
      this.xp = x;
   }

   public long bank() {
      return this.bank;
   }

   public void bank(long b) {
      this.bank = b;
   }

   public Inventory chest() {
      return this.chest;
   }

   public void chest(Inventory inv) {
      this.chest = inv;
   }

   public Deque<String> log() {
      return this.log;
   }

   public void addLog(String entry) {
      this.log.addFirst(entry);

      while (this.log.size() > 50) {
         this.log.removeLast();
      }
   }

   public String coloredName() {
      return this.color + this.name;
   }

   public String coloredTag() {
      return this.color + "[" + this.tag + "]";
   }
}
