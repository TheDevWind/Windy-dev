package fr.windydev.clans.data;

import fr.windydev.clans.WindyClans;
import fr.windydev.clans.model.Rank;
import fr.windydev.clans.ritual.CustomItems;
import fr.windydev.clans.util.ItemBuilder;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.Map.Entry;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.potion.PotionEffectType;

public class Settings {
   public String serverName;
   public String serverFooter;
   public String prefix;
   public int nameMin;
   public int nameMax;
   public int tagMin;
   public int tagMax;
   public int maxMembers;
   public int inviteExpire;
   public int homeWarmup;
   public Set<String> claimWorlds;
   public int claimBase;
   public int claimPerMember;
   public int claimPerLevel;
   public int capitalRadius;
   public int spawnRadius;
   public int bufferChunks;
   public boolean allowExplosions;
   public boolean alliesDoors;
   public final Map<String, Rank> ranks = new HashMap<>();
   public boolean pvpSpawn;
   public boolean pvpCapitals;
   public Material currency;
   public String currencySpec;
   public String currencyName;
   public double xpBase;
   public double xpExponent;
   public int maxLevel;
   public int xpMob;
   public int xpWarKill;
   public int xpWarWin;
   public int xpOnline;
   public int outpostEvery;
   public int chestRowEvery;
   public final TreeMap<Integer, List<String>> capitalEffects = new TreeMap<>();
   public int warPrep;
   public int warDuration;
   public int warCooldown;
   public int warMinDefenders;
   public int warLoot;
   public boolean warRaidContainers;
   public boolean sbDefault;
   public int sbTicks;
   public String webhook;
   public String webhookName;
   public final Map<String, Boolean> discordEvents = new HashMap<>();
   public boolean bluemap;
   public String bluemapLabel;
   public double bluemapOpacity;
   public String hologramLocation;
   public int hologramSize;

   public ItemBuilder currencyIcon() {
      if (this.currencySpec != null && this.currencySpec.startsWith("windy:")) {
         CustomItems.Def d = WindyClans.get().items().def(this.currencySpec.substring(6));
         if (d != null) {
            return ItemBuilder.of(d.material()).model(d.model());
         }
      }

      return ItemBuilder.of(this.currency);
   }

   public void load(FileConfiguration c) {
      this.serverName = c.getString("server-name", "&b&lSERVEUR");
      this.serverFooter = c.getString("server-footer", "");
      this.prefix = c.getString("prefix", "&8[&bClans&8] &r");
      this.nameMin = c.getInt("clan.name-min", 3);
      this.nameMax = c.getInt("clan.name-max", 16);
      this.tagMin = c.getInt("clan.tag-min", 2);
      this.tagMax = c.getInt("clan.tag-max", 5);
      this.maxMembers = c.getInt("clan.max-members", 20);
      this.inviteExpire = c.getInt("clan.invite-expire-seconds", 300);
      this.homeWarmup = c.getInt("clan.home-warmup-seconds", 3);
      this.claimWorlds = new HashSet<>(c.getStringList("claims.worlds"));
      this.claimBase = c.getInt("claims.base", 4);
      this.claimPerMember = c.getInt("claims.per-member", 2);
      this.claimPerLevel = c.getInt("claims.per-level", 3);
      this.capitalRadius = c.getInt("claims.capital-radius", 1);
      this.spawnRadius = c.getInt("claims.spawn-radius", 6);
      this.bufferChunks = c.getInt("claims.buffer-chunks", 1);
      this.allowExplosions = c.getBoolean("claims.allow-explosions", false);
      this.alliesDoors = c.getBoolean("claims.allies-can-use-doors", true);
      this.ranks.clear();
      ConfigurationSection rs = c.getConfigurationSection("ranks");
      if (rs != null) {
         for (String k : rs.getKeys(false)) {
            this.ranks.put(k, Rank.parse(rs.getString(k), Rank.OFFICIER));
         }
      }

      this.pvpSpawn = c.getBoolean("pvp.disable-in-spawn", true);
      this.pvpCapitals = c.getBoolean("pvp.disable-in-capitals", true);
      this.currencySpec = c.getString("bank.currency", "windy:piece");
      Material m = this.currencySpec.startsWith("windy:") ? null : Material.matchMaterial(this.currencySpec);
      this.currency = m == null ? Material.GOLD_NUGGET : m;
      if (m == null && !this.currencySpec.startsWith("windy:")) {
         this.currencySpec = "windy:piece";
      }

      this.currencyName = c.getString("bank.currency-name", "coins");
      this.xpBase = c.getDouble("levels.xp-base", 100.0);
      this.xpExponent = c.getDouble("levels.xp-exponent", 1.5);
      this.maxLevel = c.getInt("levels.max-level", 20);
      this.xpMob = c.getInt("levels.xp.hostile-mob-kill", 1);
      this.xpWarKill = c.getInt("levels.xp.war-kill", 25);
      this.xpWarWin = c.getInt("levels.xp.war-win", 300);
      this.xpOnline = c.getInt("levels.xp.online-per-5min", 2);
      this.outpostEvery = Math.max(1, c.getInt("levels.outpost-every-levels", 3));
      this.chestRowEvery = Math.max(1, c.getInt("levels.chest-row-every-levels", 2));
      this.capitalEffects.clear();
      ConfigurationSection ce = c.getConfigurationSection("levels.capital-effects");
      if (ce != null) {
         for (String k : ce.getKeys(false)) {
            try {
               int lvl = Integer.parseInt(k);
               List<String> list = ce.isList(k) ? ce.getStringList(k) : List.of(Objects.requireNonNull(ce.getString(k)));
               this.capitalEffects.put(lvl, list);
            } catch (Exception var9) {
            }
         }
      }

      this.warPrep = c.getInt("war.preparation-minutes", 10);
      this.warDuration = c.getInt("war.duration-minutes", 30);
      this.warCooldown = c.getInt("war.cooldown-hours", 24);
      this.warMinDefenders = c.getInt("war.min-defenders-online", 1);
      this.warLoot = c.getInt("war.loot-percent", 10);
      this.warRaidContainers = c.getBoolean("war.allow-container-raid", true);
      this.sbDefault = c.getBoolean("scoreboard.enabled-by-default", true);
      this.sbTicks = Math.max(5, c.getInt("scoreboard.update-ticks", 20));
      this.webhook = c.getString("discord.webhook-url", "");
      this.webhookName = c.getString("discord.username", "Clans");
      this.discordEvents.clear();
      ConfigurationSection de = c.getConfigurationSection("discord.events");
      if (de != null) {
         for (String k : de.getKeys(false)) {
            this.discordEvents.put(k, de.getBoolean(k));
         }
      }

      this.bluemap = c.getBoolean("bluemap.enabled", true);
      this.bluemapLabel = c.getString("bluemap.marker-set-label", "Territoires des clans");
      this.bluemapOpacity = c.getDouble("bluemap.fill-opacity", 0.35);
      this.hologramLocation = c.getString("hologram.location", "");
      this.hologramSize = c.getInt("hologram.size", 10);
   }

   public Rank rank(String action) {
      return this.ranks.getOrDefault(action, Rank.OFFICIER);
   }

   public static Entry<PotionEffectType, Integer> parseEffect(String s) {
      String[] p = s.split(":");
      PotionEffectType t = (PotionEffectType)Registry.EFFECT.get(NamespacedKey.minecraft(p[0].trim().toLowerCase(Locale.ROOT)));
      if (t == null) {
         return null;
      } else {
         int amp = p.length > 1 ? Integer.parseInt(p[1].trim()) : 0;
         return Map.entry(t, amp);
      }
   }
}
