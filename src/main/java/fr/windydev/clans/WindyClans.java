package fr.windydev.clans;

import fr.windydev.clans.bag.BagManager;
import fr.windydev.clans.bag.CauldronRitual;
import fr.windydev.clans.boss.BossManager;
import fr.windydev.clans.command.AdminCommand;
import fr.windydev.clans.command.ChatCommands;
import fr.windydev.clans.command.ClanCommand;
import fr.windydev.clans.command.ProfileCommand;
import fr.windydev.clans.data.ClanManager;
import fr.windydev.clans.data.Settings;
import fr.windydev.clans.discord.DiscordBot;
import fr.windydev.clans.dungeon.DungeonManager;
import fr.windydev.clans.magic.MagicManager;
import fr.windydev.clans.rift.RiftManager;
import fr.windydev.clans.hub.HubManager;
import fr.windydev.clans.hub.WheelManager;
import fr.windydev.clans.staff.ServiceManager;
import fr.windydev.clans.forge.GlyphManager;
import fr.windydev.clans.forge.ArmorSets;
import fr.windydev.clans.elixir.ElixirFeature;
import fr.windydev.clans.feature.BlueMapHook;
import fr.windydev.clans.feature.DiscordWebhook;
import fr.windydev.clans.feature.HudService;
import fr.windydev.clans.feature.MapHook;
import fr.windydev.clans.feature.PackService;
import fr.windydev.clans.feature.ScoreboardService;
import fr.windydev.clans.feature.SpawnService;
import fr.windydev.clans.feature.SpeedFix;
import fr.windydev.clans.feature.TeleportService;
import fr.windydev.clans.feature.TickTasks;
import fr.windydev.clans.feature.TopHologram;
import fr.windydev.clans.feature.WarManager;
import fr.windydev.clans.gui.MenuListener;
import fr.windydev.clans.gui.Menus;
import fr.windydev.clans.gui.TextInput;
import fr.windydev.clans.listener.ChatListener;
import fr.windydev.clans.listener.CombatListener;
import fr.windydev.clans.listener.InventoryButtonListener;
import fr.windydev.clans.listener.MenuItemListener;
import fr.windydev.clans.listener.PlayerListener;
import fr.windydev.clans.listener.ProtectionListener;
import fr.windydev.clans.profile.Awakening;
import fr.windydev.clans.profile.Powers;
import fr.windydev.clans.profile.ProfileManager;
import fr.windydev.clans.profile.RaceListener;
import fr.windydev.clans.profile.SkillEngine;
import fr.windydev.clans.ritual.CustomItemGuard;
import fr.windydev.clans.ritual.CustomItems;
import fr.windydev.clans.ritual.HammerListener;
import fr.windydev.clans.ritual.RitualManager;
import fr.windydev.clans.ritual.WeaponListener;
import fr.windydev.clans.util.Glyphs;
import fr.windydev.clans.util.Text;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

public final class WindyClans extends JavaPlugin {
   private final Settings settings = new Settings();
   private ClanManager clans;
   private WarManager war;
   private ScoreboardService scoreboard;
   private Menus menus;
   private DiscordWebhook discord;
   private MapHook bluemap = MapHook.NONE;
   private TopHologram hologram;
   private PackService pack;
   private HudService hud;
   private MenuItemListener menuItem;
   private static WindyClans instance;
   private CustomItems items;
   private RitualManager rituals;
   private BossManager bosses;
   private DiscordBot bot;
   private ProfileManager profiles;
   private BagManager bags;
   private CauldronRitual cauldron;
   private SkillEngine skills;
   private Powers powers;
   private Awakening awakening;
   private ElixirFeature elixirs;
   private DungeonManager dungeons;
   private MagicManager magic;
   private RiftManager rift;
   private WheelManager wheel;
   private HubManager hub;
   private ServiceManager service;
   private GlyphManager glyphs;
   private ArmorSets armorSets;
   private SpawnService spawns;
   private final Set<UUID> bypass = new HashSet<>();
   private final Set<UUID> borders = new HashSet<>();
   private final Set<UUID> clanChatToggle = new HashSet<>();

   public static WindyClans get() {
      return instance;
   }

   public void onEnable() {
      instance = this;
      this.saveDefaultConfig();
      this.mergeDefaults();
      this.settings.load(this.getConfig());
      Glyphs.load();
      this.pack = new PackService(this);
      this.pack.start();
      this.hud = new HudService(this);
      this.items = new CustomItems(this);
      this.items.load();
      this.rituals = new RitualManager(this);
      this.rituals.load();
      this.bosses = new BossManager(this);
      this.bosses.load();
      this.dungeons = new DungeonManager(this);
      this.dungeons.load();
      this.magic = new MagicManager(this);
      this.magic.load();
      this.rift = new RiftManager(this);
      this.rift.load();
      this.wheel = new WheelManager(this);
      this.wheel.load();
      this.hub = new HubManager(this);
      this.hub.load();
      this.service = new ServiceManager(this);
      this.service.load();
      this.glyphs = new GlyphManager(this);
      this.glyphs.load();
      this.armorSets = new ArmorSets(this);
      this.bot = new DiscordBot(this);
      this.profiles = new ProfileManager(this);
      this.profiles.load();
      this.profiles.loadData();
      this.bags = new BagManager(this);
      this.bags.load();
      this.cauldron = new CauldronRitual(this, this.bags);
      this.cauldron.load();
      this.skills = new SkillEngine(this, this.profiles);
      this.powers = new Powers(this, this.profiles, this.skills);
      this.awakening = new Awakening(this, this.profiles);
      this.awakening.load();
      this.elixirs = new ElixirFeature(this);
      this.elixirs.load();
      this.discord = new DiscordWebhook(this);
      this.war = new WarManager(this);
      this.clans = new ClanManager(this);
      this.clans.load();
      this.menus = new Menus(this);
      this.scoreboard = new ScoreboardService(this);
      this.hologram = new TopHologram(this);
      if (this.settings.bluemap && Bukkit.getPluginManager().getPlugin("BlueMap") != null) {
         try {
            this.bluemap = new BlueMapHook(this);
            this.getLogger().info("BlueMap détecté : les territoires seront affichés sur la carte web.");
         } catch (Throwable var12) {
            this.getLogger().warning("Intégration BlueMap impossible : " + var12.getMessage());
         }
      }

      ClanCommand clanCmd = new ClanCommand(this);
      this.register("clan", clanCmd);
      ChatCommands chat = new ChatCommands(this);
      this.register("cc", chat);
      this.register("ca", chat);
      this.register("clanadmin", new AdminCommand(this));
      ProfileCommand profileCmd = new ProfileCommand(this);
      this.register("profil", profileCmd);
      this.register("races", profileCmd);
      this.register("competences", profileCmd);
      this.register("eveil", profileCmd);
      this.register("lier", (CommandExecutor)(sender, cmd, label, args) -> {
         if (sender instanceof Player pl) {
            if (args.length < 1) {
               this.msg(pl, "&c/lier <code> &7(code obtenu sur Discord, salon liaison)");
               return true;
            } else {
               this.bot.linkWithCode(pl, args[0]);
               return true;
            }
         } else {
            return true;
         }
      });
      PluginManager pm = Bukkit.getPluginManager();
      pm.registerEvents(new ProtectionListener(this), this);
      pm.registerEvents(new PlayerListener(this), this);
      pm.registerEvents(new ChatListener(this), this);
      pm.registerEvents(new MenuListener(this), this);
      pm.registerEvents(new CombatListener(this), this);
      pm.registerEvents(this.pack, this);
      this.menuItem = new MenuItemListener(this);
      pm.registerEvents(this.menuItem, this);
      InventoryButtonListener invButton = new InventoryButtonListener(this);
      pm.registerEvents(invButton, this);
      invButton.start();
      TextInput.register(this);
      pm.registerEvents(this.rituals, this);
      pm.registerEvents(new HammerListener(this), this);
      pm.registerEvents(new CustomItemGuard(this), this);
      pm.registerEvents(new WeaponListener(this), this);
      this.rituals.start();
      pm.registerEvents(this.profiles, this);
      RaceListener raceListener = new RaceListener(this, this.profiles);
      pm.registerEvents(raceListener, this);
      raceListener.start();
      this.profiles.start();
      pm.registerEvents(this.skills, this);
      this.skills.start();
      pm.registerEvents(this.powers, this);
      this.powers.start();
      pm.registerEvents(this.awakening, this);
      this.awakening.start();
      this.spawns = new SpawnService(this);
      pm.registerEvents(this.spawns, this);
      pm.registerEvents(new SpeedFix(this), this);
      TeleportService tps = new TeleportService(this);
      pm.registerEvents(tps, this);

      for (String c : new String[]{"sethome", "home", "delhome", "spawn", "tpa", "tpaccept", "tpdeny"}) {
         this.register(c, tps);
      }

      pm.registerEvents(this.elixirs, this);
      this.elixirs.start();
      pm.registerEvents(this.bags, this);
      pm.registerEvents(this.cauldron, this);
      this.cauldron.start();
      pm.registerEvents(this.bosses, this);
      this.bosses.start();
      pm.registerEvents(this.dungeons, this);
      this.dungeons.start();
      this.register("donjon", this.dungeons);
      pm.registerEvents(this.magic, this);
      this.magic.start();
      this.register("grimoire", this.magic);
      pm.registerEvents(this.rift, this);
      this.rift.start();
      this.register("faille", this.rift);
      pm.registerEvents(this.wheel, this);
      this.wheel.start();
      this.register("roue", this.wheel);
      pm.registerEvents(this.hub, this);
      this.hub.start();
      pm.registerEvents(this.service, this);
      this.service.start();
      this.register("service", this.service);
      this.register("ecrans", this.pack);
      pm.registerEvents(this.glyphs, this);
      pm.registerEvents(this.armorSets, this);
      this.armorSets.start();
      this.bot.start();
      Bukkit.getScheduler().runTaskTimer(this, this.clans::saveIfDirty, 1200L, 1200L);
      this.scoreboard.start();
      new TickTasks(this).start();
      this.war.start();
      this.hologram.start();
      Bukkit.getOnlinePlayers().forEach(p -> {
         this.scoreboard.setup(p);
         this.menuItem.give(p);
         this.pack.send(p);
         this.profiles.apply(p);
      });
      this.getLogger().info("WindyClans activé.");
   }

   public void onDisable() {
      if (this.hud != null) {
         this.hud.shutdown();
      }

      if (this.rituals != null) {
         this.rituals.shutdown();
      }

      if (this.bosses != null) {
         this.bosses.shutdown();
      }

      if (this.dungeons != null) {
         this.dungeons.shutdown();
      }

      if (this.service != null) {
         this.service.shutdown();
      }

      if (this.hub != null) {
         this.hub.shutdown();
      }

      if (this.wheel != null) {
         this.wheel.shutdown();
      }

      if (this.rift != null) {
         this.rift.shutdown();
      }

      if (this.magic != null) {
         this.magic.shutdown();
      }

      if (this.bot != null) {
         this.bot.stop();
      }

      if (this.pack != null) {
         this.pack.stop();
      }

      if (this.war != null) {
         this.war.shutdown();
      }

      if (this.clans != null) {
         this.clans.save();
      }

      if (this.powers != null) {
         this.powers.shutdown();
      }

      if (this.awakening != null) {
         this.awakening.shutdown();
      }

      if (this.elixirs != null) {
         this.elixirs.shutdown();
      }

      if (this.profiles != null) {
         this.profiles.save();
      }

      if (this.cauldron != null) {
         this.cauldron.shutdown();
      }

      if (this.bags != null) {
         this.bags.shutdown();
      }

      if (this.hologram != null) {
         this.hologram.remove();
      }

      if (this.scoreboard != null) {
         this.scoreboard.shutdown();
      }
   }

   private void register(String name, Object executor) {
      PluginCommand cmd = Objects.requireNonNull(this.getCommand(name), name);
      cmd.setExecutor((CommandExecutor)executor);
      if (executor instanceof TabCompleter tc) {
         cmd.setTabCompleter(tc);
      }
   }

   public void reload() {
      this.reloadConfig();
      this.settings.load(this.getConfig());
      this.hologram.start();
      this.bluemap.refresh();
      this.items.load();
      this.rituals.load();
      this.bosses.load();
      this.dungeons.load();
      this.magic.shutdown();
      this.magic.load();
      this.rift.load();
      this.wheel.load();
      this.hub.load();
      this.service.load();
      this.glyphs.load();
      this.profiles.load();
      this.cauldron.load();
      this.awakening.load();
      this.elixirs.load();
      Bukkit.getOnlinePlayers().forEach(this.profiles::apply);
   }

   public void msg(CommandSender to, String text) {
      to.sendMessage(Text.c(this.settings.prefix + text));
   }

   private void mergeDefaults() {
      this.getConfig().options().copyDefaults(true);
      if ("DIAMOND".equalsIgnoreCase(this.getConfig().getString("bank.currency"))) {
         this.getConfig().set("bank.currency", "windy:piece");
         this.getConfig().set("bank.currency-name", "coins");
         this.getLogger().info("Banque : la monnaie passe des diamants aux coins (les soldes sont gardés : 1 diamant = 1 coin).");
      }

      this.saveConfig();
   }

   public CustomItems items() {
      return this.items;
   }

   public RitualManager rituals() {
      return this.rituals;
   }

   public BossManager bosses() {
      return this.bosses;
   }

   public DiscordBot bot() {
      return this.bot;
   }

   public ProfileManager profiles() {
      return this.profiles;
   }

   public BagManager bags() {
      return this.bags;
   }

   public CauldronRitual cauldron() {
      return this.cauldron;
   }

   public SkillEngine skills() {
      return this.skills;
   }

   public Powers powers() {
      return this.powers;
   }

   public Awakening awakening() {
      return this.awakening;
   }

   public ElixirFeature elixirs() {
      return this.elixirs;
   }

   public GlyphManager glyphs() {
      return this.glyphs;
   }

   public ArmorSets armorSets() {
      return this.armorSets;
   }

   public WheelManager wheel() {
      return this.wheel;
   }

   public ServiceManager service() {
      return this.service;
   }

   public HubManager hub() {
      return this.hub;
   }

   public RiftManager rift() {
      return this.rift;
   }

   public MagicManager magic() {
      return this.magic;
   }

   public DungeonManager dungeons() {
      return this.dungeons;
   }

   public SpawnService spawns() {
      return this.spawns;
   }

   public PackService pack() {
      return this.pack;
   }

   public HudService hud() {
      return this.hud;
   }

   public MenuItemListener menuItem() {
      return this.menuItem;
   }

   public Settings settings() {
      return this.settings;
   }

   public ClanManager clans() {
      return this.clans;
   }

   public WarManager war() {
      return this.war;
   }

   public ScoreboardService scoreboard() {
      return this.scoreboard;
   }

   public Menus menus() {
      return this.menus;
   }

   public DiscordWebhook discord() {
      return this.discord;
   }

   public MapHook bluemap() {
      return this.bluemap;
   }

   public TopHologram hologram() {
      return this.hologram;
   }

   public Set<UUID> bypass() {
      return this.bypass;
   }

   public Set<UUID> borders() {
      return this.borders;
   }

   public Set<UUID> clanChatToggle() {
      return this.clanChatToggle;
   }
}
