package fr.windydev.clans.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

public final class Glyphs {
   public static final Key SHIFT = Key.key("windy", "shift");
   public static final Key GUI = Key.key("windy", "gui");
   public static final Key HUD = Key.key("windy", "hud");
   private static final Map<Integer, Integer> WIDTHS = new HashMap<>();
   private static final Map<String, String> LAYOUTS = new HashMap<>();
   private static final Map<String, Integer> LAYOUT_ROWS = new HashMap<>();
   private static final Map<String, String> ICONS = new HashMap<>();
   private static final Map<String, Integer> ICON_WIDTHS = new HashMap<>();
   private static final Map<String, String> PANEL = new HashMap<>();
   private static Set<Integer> DYS = new HashSet<>();
   public static int HUD_TITLE_TOP;
   public static int HUD_FIRST_LINE;
   public static int HUD_LINE_H;
   public static final Key ARCANE = Key.key("windy", "arcane");
   public static final Key BIG = Key.key("windy", "big");
   private static final Map<String, Glyphs.Arcane> ARCANE_LAYOUTS = new HashMap<>();
   private static final Map<Integer, String> GOLD_ROWS = new HashMap<>();
   private static final Map<String, Integer> MAP_TILES = new HashMap<>();
   private static int ORIGIN_X = -112;
   private static int TILE = 80;
   private static int GOLD_X = 7;
   private static int XP_X = 27;
   private static int XP_W = 121;
   private static String XP_CHARS = "";
   private static final Set<String> PAINTED_TITLE = Set.of("members", "map", "bank", "races", "skills", "evolution");

   private Glyphs() {
   }

   public static void load() {
      try (InputStreamReader r = new InputStreamReader(Glyphs.class.getResourceAsStream("/widths.json"), StandardCharsets.UTF_8)) {
         JsonObject o = JsonParser.parseReader(r).getAsJsonObject();

         for (Entry<String, JsonElement> e : o.entrySet()) {
            WIDTHS.put(Integer.parseInt(e.getKey()), e.getValue().getAsInt());
         }
      } catch (Exception var15) {
         throw new IllegalStateException("widths.json", var15);
      }

      try (InputStreamReader r = new InputStreamReader(Glyphs.class.getResourceAsStream("/glyphs.json"), StandardCharsets.UTF_8)) {
         JsonObject o = JsonParser.parseReader(r).getAsJsonObject();
         o.getAsJsonObject("layouts").entrySet().forEach(ex -> LAYOUTS.put((String)ex.getKey(), ((JsonElement)ex.getValue()).getAsString()));
         o.getAsJsonObject("layout_rows").entrySet().forEach(ex -> LAYOUT_ROWS.put((String)ex.getKey(), ((JsonElement)ex.getValue()).getAsInt()));
         o.getAsJsonObject("icons").entrySet().forEach(ex -> ICONS.put((String)ex.getKey(), ((JsonElement)ex.getValue()).getAsString()));
         o.getAsJsonObject("icon_widths").entrySet().forEach(ex -> ICON_WIDTHS.put((String)ex.getKey(), ((JsonElement)ex.getValue()).getAsInt()));
         o.getAsJsonObject("panel").entrySet().forEach(ex -> PANEL.put((String)ex.getKey(), ((JsonElement)ex.getValue()).getAsString()));
         o.getAsJsonArray("dys").forEach(ex -> DYS.add(ex.getAsInt()));
         JsonObject h = o.getAsJsonObject("hud");
         HUD_TITLE_TOP = h.get("title_top").getAsInt();
         HUD_FIRST_LINE = h.get("first_line").getAsInt();
         HUD_LINE_H = h.get("line_h").getAsInt();
      } catch (Exception var11) {
         throw new IllegalStateException("glyphs.json", var11);
      }

      InputStream in = Glyphs.class.getResourceAsStream("/arcane.json");
      if (in != null) {
         try (InputStreamReader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            JsonObject o = JsonParser.parseReader(r).getAsJsonObject();
            TILE = o.get("tile").getAsInt();
            ORIGIN_X = o.get("origin_x").getAsInt();

            for (Entry<String, JsonElement> e : o.getAsJsonObject("layouts").entrySet()) {
               JsonObject l = e.getValue().getAsJsonObject();
               List<String> t = new ArrayList<>();
               l.getAsJsonArray("tiles").forEach(x -> t.add(x.getAsString()));
               ARCANE_LAYOUTS.put(e.getKey(), new Glyphs.Arcane(l.get("rows").getAsInt(), t, l.get("painted").getAsBoolean()));
            }

            JsonObject g = o.getAsJsonObject("overlays").getAsJsonObject("gold_row");
            GOLD_X = g.get("x").getAsInt();

            for (Entry<String, JsonElement> e : g.getAsJsonObject("rows").entrySet()) {
               GOLD_ROWS.put(Integer.parseInt(e.getKey()), e.getValue().getAsJsonArray().get(0).getAsString());
            }

            JsonObject xp = o.getAsJsonObject("xpbar");
            XP_CHARS = xp.get("chars").getAsString();
            XP_X = xp.get("x").getAsInt();
            XP_W = xp.get("width").getAsInt();
            o.getAsJsonObject("map_tiles").entrySet().forEach(ex -> MAP_TILES.put((String)ex.getKey(), ((JsonElement)ex.getValue()).getAsInt()));
         } catch (Exception var13) {
            throw new IllegalStateException("arcane.json", var13);
         }
      }
   }

   public static int width(int codepoint) {
      Integer w = WIDTHS.get(codepoint);
      return w == null ? WIDTHS.getOrDefault(63, 6) : w;
   }

   public static int width(String plain) {
      int w = 0;
      int i = 0;

      while (i < plain.length()) {
         int cp = plain.codePointAt(i);
         w += width(cp);
         i += Character.charCount(cp);
      }

      return w;
   }

   public static String sanitize(String plain) {
      StringBuilder sb = new StringBuilder();
      int i = 0;

      while (i < plain.length()) {
         int cp = plain.codePointAt(i);
         sb.appendCodePoint(WIDTHS.containsKey(cp) ? cp : 63);
         i += Character.charCount(cp);
      }

      return sb.toString();
   }

   public static Component shift(int px) {
      if (px == 0) {
         return Component.empty();
      } else {
         StringBuilder sb = new StringBuilder();
         int abs = Math.abs(px);
         int base = px < 0 ? '\uf801' : '\uf821';

         for (int i = 9; i >= 0; i--) {
            for (int v = 1 << i; abs >= v; abs -= v) {
               sb.append((char)(base + i));
            }
         }

         return Component.text(sb.toString()).font(SHIFT);
      }
   }

   public static Glyphs.Line text(String legacy, int dy, boolean marked) {
      Key font = dy == 0 ? Key.key("minecraft", "default") : Key.key("windy", "dy" + dy);
      if (dy != 0 && !DYS.contains(dy)) {
         throw new IllegalArgumentException("dy non généré : " + dy);
      } else {
         TextComponent parsed = LegacyComponentSerializer.legacyAmpersand().deserialize(legacy == null ? "" : legacy);
         Component out = Component.empty();
         int[] w = new int[]{0};
         out = append(out, parsed, NamedTextColor.WHITE, font, marked, w);
         return new Glyphs.Line(out, w[0]);
      }
   }

   private static Component append(Component out, Component node, TextColor inherited, Key font, boolean marked, int[] w) {
      TextColor color = node.color() != null ? node.color() : inherited;
      if (node instanceof TextComponent tc && !tc.content().isEmpty()) {
         String s = sanitize(tc.content());
         w[0] += width(s);
         out = out.append(
            Component.text(s).font(font).color(marked ? mark(color) : color).decoration(TextDecoration.BOLD, false).decoration(TextDecoration.ITALIC, false)
         );
      }

      for (Component child : node.children()) {
         out = append(out, child, color, font, marked, w);
      }

      return out;
   }

   public static TextColor mark(TextColor c) {
      if (c == null) {
         c = NamedTextColor.WHITE;
      }

      return TextColor.color(snap(c.red(), 5), snap(c.green(), 10), snap(c.blue(), 15));
   }

   private static int snap(int v, int residue) {
      int best = residue;
      int bestD = Integer.MAX_VALUE;

      for (int k = residue; k <= 255; k += 16) {
         int d = Math.abs(k - v);
         if (d < bestD) {
            bestD = d;
            best = k;
         }
      }

      return best;
   }

   public static boolean hasLayout(String layout) {
      return ARCANE_LAYOUTS.containsKey(layout) || LAYOUTS.containsKey(layout);
   }

   public static int layoutRows(String layout) {
      Glyphs.Arcane a = ARCANE_LAYOUTS.get(layout);
      return a != null ? a.rows() : LAYOUT_ROWS.getOrDefault(layout, 6);
   }

   public static boolean painted(String layout) {
      Glyphs.Arcane a = ARCANE_LAYOUTS.get(layout);
      return a != null && a.painted();
   }

   private static Component tiles(String chars, Key font) {
      Component c = Component.empty();
      int i = 0;

      while (i < chars.length()) {
         int cp = chars.codePointAt(i);
         c = c.append(Component.text(new String(Character.toChars(cp))).font(font).color(NamedTextColor.WHITE)).append(shift(-1));
         i += Character.charCount(cp);
      }

      return c;
   }

   private static Component arcaneTitle(String layout, Glyphs.Arcane a, String titleLegacy) {
      Component c = ((TextComponent)Component.empty().color(NamedTextColor.WHITE)).append(shift(-8 + ORIGIN_X));

      for (String row : a.tiles()) {
         int n = row.codePointCount(0, row.length());
         c = c.append(tiles(row, ARCANE)).append(shift(-n * TILE));
      }

      c = c.append(shift(-ORIGIN_X));
      if (!PAINTED_TITLE.contains(layout) && titleLegacy != null && !titleLegacy.isEmpty()) {
         c = c.append(centered(88, 5, titleLegacy, 104));
      }

      return c;
   }

   public static String fit(String legacy, int maxWidth) {
      if (legacy == null) {
         return "";
      } else if (width(stripLegacy(legacy)) <= maxWidth) {
         return legacy;
      } else {
         String s = legacy;

         while (s.length() > 1 && width(stripLegacy(s)) + 8 > maxWidth) {
            s = s.substring(0, s.length() - 1);
            if (s.endsWith("&")) {
               s = s.substring(0, s.length() - 1);
            }
         }

         return s + "&7…";
      }
   }

   private static String stripLegacy(String legacy) {
      return sanitize(legacy.replaceAll("&[0-9a-fk-orA-FK-OR]", ""));
   }

   public static Component centered(int cx, int top, String legacy, int maxWidth) {
      Glyphs.Line t = text(fit(legacy, maxWidth), top - 6, false);
      int x = cx - t.width() / 2;
      return ((TextComponent)((TextComponent)Component.empty().append(shift(x))).append(t.component())).append(shift(-(x + t.width())));
   }

   public static Component label(int x, int top, String legacy, int maxWidth) {
      return label(x, top, fit(legacy, maxWidth));
   }

   public static Component right(int x, int top, String legacy) {
      Glyphs.Line l = text(legacy, top - 6, false);
      return ((TextComponent)((TextComponent)Component.empty().append(shift(x - l.width()))).append(l.component())).append(shift(-x));
   }

   public static Component goldRow(int row) {
      String ch = GOLD_ROWS.get(row);
      if (ch == null) {
         return Component.empty();
      } else {
         int n = ch.codePointCount(0, ch.length());
         return ((TextComponent)((TextComponent)Component.empty().append(shift(GOLD_X))).append(tiles(ch, ARCANE))).append(shift(-(GOLD_X + n * TILE)));
      }
   }

   public static Component xpBar(int pct) {
      int n = Math.max(0, Math.min(XP_W, Math.round(XP_W * pct / 100.0F)));
      if (n != 0 && !XP_CHARS.isEmpty()) {
         int shades = XP_CHARS.length();
         Component bar = Component.empty();

         for (int i = 0; i < n; i++) {
            char ch = XP_CHARS.charAt(Math.min(shades - 1, i * shades / XP_W));
            bar = bar.append(Component.text(String.valueOf(ch)).font(ARCANE).color(NamedTextColor.WHITE)).append(shift(-1));
         }

         return ((TextComponent)((TextComponent)Component.empty().append(shift(XP_X))).append(bar)).append(shift(-(XP_X + n)));
      } else {
         return Component.empty();
      }
   }

   public static Glyphs.Line big(String plain, TextColor color) {
      String s = sanitize(plain);
      int w = 0;
      int i = 0;

      while (i < s.length()) {
         int cp = s.codePointAt(i);
         w += cp == 32 ? 8 : 2 * width(cp) - 1;
         i += Character.charCount(cp);
      }

      return new Glyphs.Line(Component.text(s).font(BIG).color(color).decoration(TextDecoration.BOLD, false), w);
   }

   public static Component at(int x, Glyphs.Line l) {
      return ((TextComponent)((TextComponent)Component.empty().append(shift(x))).append(l.component())).append(shift(-(x + l.width())));
   }

   public static Component iconAt(int x, int top, String name) {
      String ch = ICONS.get(name);
      if (ch == null) {
         return Component.empty();
      } else {
         int dy = top - 6;
         Key font = dy == 0 ? Key.key("minecraft", "default") : Key.key("windy", "dy" + dy);
         int w = ICON_WIDTHS.getOrDefault(ch, 9);
         return ((TextComponent)((TextComponent)Component.empty().append(shift(x))).append(Component.text(ch).font(font).color(NamedTextColor.WHITE)))
            .append(shift(-(x + w)));
      }
   }

   public static Integer mapTile(String kind) {
      return MAP_TILES.get(kind);
   }

   public static Component menuTitle(String layout, String titleLegacy) {
      Glyphs.Arcane a = ARCANE_LAYOUTS.get(layout);
      if (a != null) {
         return arcaneTitle(layout, a, titleLegacy);
      } else {
         Component c = Component.empty().color(NamedTextColor.WHITE);
         c = c.append(shift(-8)).append(Component.text(LAYOUTS.get(layout)).font(GUI).color(NamedTextColor.WHITE)).append(shift(-177));
         Glyphs.Line t = text(titleLegacy, 0, false);
         int x = Math.max(0, (176 - t.width()) / 2);
         return c.append(shift(x)).append(t.component()).append(shift(-(x + t.width())));
      }
   }

   public static Component label(int x, int top, String legacy) {
      Glyphs.Line l = text(legacy, top - 6, false);
      return ((TextComponent)((TextComponent)Component.empty().append(shift(x))).append(l.component())).append(shift(-(x + l.width())));
   }

   public static String icon(String name) {
      return ICONS.getOrDefault(name, "");
   }

   public static int iconWidth(String name) {
      return ICON_WIDTHS.getOrDefault(ICONS.get(name), 9);
   }

   public static String panel(String part, int lines) {
      return PANEL.get(part + lines);
   }

   public record Arcane(int rows, List<String> tiles, boolean painted) {
   }

   public record Line(Component component, int width) {
   }
}
