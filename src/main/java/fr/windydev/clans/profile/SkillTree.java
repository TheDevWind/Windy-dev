package fr.windydev.clans.profile;

import fr.windydev.clans.WindyClans;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Map.Entry;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

public final class SkillTree {
   public static final String[] KINDS = new String[]{"extra", "unique", "unique-plus", "ultimate"};
   public static final String[] BRANCHES = new String[]{"offensive", "def", "sig"};
   private final WindyClans plugin;
   private final Map<String, SkillTree.Tree> trees = new LinkedHashMap<>();
   private final Map<String, SkillTree.Node> nodes = new HashMap<>();
   private final Map<String, SkillTree.Special> specials = new HashMap<>();
   private final Map<String, List<String>> ranks = new HashMap<>();
   int pointsPerLevel = 1;
   int pointsPerAwakening = 3;
   String resetItem = "windy:parchemin_oubli";

   public SkillTree(WindyClans plugin) {
      this.plugin = plugin;
   }

   public void load() {
      File f = new File(this.plugin.getDataFolder(), "competences.yml");
      if (!f.exists()) {
         this.plugin.saveResource("competences.yml", false);
      }

      YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
      YamlConfiguration def = YamlConfiguration.loadConfiguration(
         new InputStreamReader(Objects.requireNonNull(this.plugin.getResource("competences.yml")), StandardCharsets.UTF_8)
      );
      y.setDefaults(def);
      this.pointsPerLevel = y.getInt("settings.points-per-level", 1);
      this.pointsPerAwakening = y.getInt("settings.points-per-awakening", 3);
      this.resetItem = y.getString("settings.reset-item", this.resetItem);
      Map<String, Integer> costs = new HashMap<>();

      for (String k : KINDS) {
         String var10003 = "settings.costs." + k;

         costs.put(k, y.getInt(var10003, switch (k) {
            case "extra" -> 5;
            case "ultimate" -> 20;
            default -> 10;
         }));
      }

      this.ranks.clear();
      ConfigurationSection rs = y.getConfigurationSection("ranks");
      if (rs != null) {
         for (String r : rs.getKeys(false)) {
            this.ranks.put(r, rs.getStringList(r));
         }
      }

      this.trees.clear();
      this.nodes.clear();
      this.specials.clear();
      ConfigurationSection ts = y.getConfigurationSection("trees");
      if (ts != null) {
         for (String race : ts.getKeys(false)) {
            ConfigurationSection t = ts.getConfigurationSection(race);
            if (t != null) {
               List<SkillTree.Branch> branches = new ArrayList<>();

               for (String b : BRANCHES) {
                  ConfigurationSection bs = t.getConfigurationSection(b);
                  if (bs != null) {
                     List<SkillTree.Node> list = new ArrayList<>();
                     List<Map<?, ?>> raw = bs.getMapList("nodes");

                     for (int i = 0; i < raw.size() && i < 5; i++) {
                        Map<?, ?> m = raw.get(i);
                        String stat = str(m.get("stat"), "");
                        if (!Stats.META.containsKey(stat)) {
                           this.plugin.getLogger().warning("competences.yml : statistique inconnue '" + stat + "' (" + race + "." + b + ")");
                        }

                        SkillTree.Node n = new SkillTree.Node(
                           race + "." + b + "." + (i + 1),
                           race,
                           b,
                           i,
                           str(m.get("name"), "?"),
                           mat(m.get("icon"), Material.PAPER),
                           stat,
                           dbl(m.get("per-level")),
                           Math.max(1, (int)dbl(m.get("max"), 5.0)),
                           Math.max(1, (int)dbl(m.get("cost"), 1.0)),
                           m.get("when") == null ? null : str(m.get("when"), null)
                        );
                        list.add(n);
                        this.nodes.put(n.id(), n);
                     }

                     String name = bs.getString("name", b);
                     Material icon = mat(
                        bs.getString("icon"), b.equals("offensive") ? Material.IRON_SWORD : (b.equals("def") ? Material.SHIELD : Material.NETHER_STAR)
                     );
                     branches.add(new SkillTree.Branch(b, name, icon, list));
                  }
               }

               Map<String, SkillTree.Special> sp = new LinkedHashMap<>();

               for (int k = 0; k < KINDS.length; k++) {
                  ConfigurationSection s = t.getConfigurationSection(KINDS[k]);
                  if (s != null) {
                     SkillTree.Special spec = new SkillTree.Special(
                        race + "." + KINDS[k],
                        race,
                        KINDS[k],
                        s.getString("name", KINDS[k]),
                        mat(s.getString("icon"), Material.NETHER_STAR),
                        k + 2,
                        s.getInt("cost", costs.get(KINDS[k])),
                        s.getInt("cooldown", 0),
                        s.getStringList("description")
                     );
                     sp.put(KINDS[k], spec);
                     this.specials.put(spec.id(), spec);
                  }
               }

               this.trees.put(race, new SkillTree.Tree(race, branches, sp));
            }
         }

         this.plugin.getLogger().info(this.trees.size() + " arbre(s) de compétences chargé(s).");
      }
   }

   private static String str(Object o, String d) {
      return o == null ? d : String.valueOf(o);
   }

   private static double dbl(Object o) {
      return dbl(o, 0.0);
   }

   private static double dbl(Object o, double d) {
      if (o instanceof Number n) {
         return n.doubleValue();
      } else {
         try {
            return o == null ? d : Double.parseDouble(String.valueOf(o));
         } catch (NumberFormatException var4) {
            return d;
         }
      }
   }

   private static Material mat(Object o, Material d) {
      if (o == null) {
         return d;
      } else {
         Material m = Material.matchMaterial(String.valueOf(o));
         return m != null && m.isItem() ? m : d;
      }
   }

   public SkillTree.Tree tree(String race) {
      return race == null ? null : this.trees.get(race);
   }

   public SkillTree.Node node(String id) {
      return this.nodes.get(id);
   }

   public SkillTree.Special special(String id) {
      return this.specials.get(id);
   }

   public SkillTree.Special special(String race, String kind) {
      SkillTree.Tree t = this.tree(race);
      return t == null ? null : t.specials().get(kind);
   }

   public int pointsPerAwakening() {
      return this.pointsPerAwakening;
   }

   public String resetItem() {
      return this.resetItem;
   }

   public String rankName(String race, int rank) {
      List<String> l = this.ranks.get(race);
      return l != null && !l.isEmpty() ? l.get(Math.max(0, Math.min(l.size() - 1, rank - 1))) : "Rang " + Stats.roman(rank);
   }

   public int cost(String id) {
      SkillTree.Node n = this.nodes.get(id);
      if (n != null) {
         return n.cost();
      } else {
         SkillTree.Special s = this.specials.get(id);
         return s == null ? 0 : s.cost();
      }
   }

   public int earned(Profile p) {
      return (p.level - 1) * this.pointsPerLevel + (p.rank - 1) * this.pointsPerAwakening;
   }

   public int spent(Profile p) {
      int s = 0;

      for (Entry<String, Integer> e : p.skills.entrySet()) {
         s += this.cost(e.getKey()) * e.getValue();
      }

      return s;
   }

   public int free(Profile p) {
      return Math.max(0, this.earned(p) - this.spent(p));
   }

   public record Branch(String id, String name, Material icon, List<SkillTree.Node> nodes) {
   }

   public record Node(
      String id, String race, String branch, int index, String name, Material icon, String stat, double perLevel, int max, int cost, String when
   ) {
      public int rank() {
         return this.index + 1;
      }

      public double value(int level) {
         return this.perLevel * level;
      }
   }

   public record Special(String id, String race, String kind, String name, Material icon, int rank, int cost, int cooldown, List<String> description) {
   }

   public record Tree(String race, List<SkillTree.Branch> branches, Map<String, SkillTree.Special> specials) {
   }
}
