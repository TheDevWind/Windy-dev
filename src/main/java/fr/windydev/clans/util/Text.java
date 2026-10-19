package fr.windydev.clans.util;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.format.TextDecoration.State;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.legacy.LegacyFormat;
import org.bukkit.ChatColor;

public final class Text {
   private static final LegacyComponentSerializer AMP = LegacyComponentSerializer.legacyAmpersand();

   private Text() {
   }

   public static Component c(String s) {
      return AMP.deserialize(s == null ? "" : s);
   }

   public static Component item(String s) {
      return c(s).decorationIfAbsent(TextDecoration.ITALIC, State.FALSE);
   }

   public static List<Component> lore(List<String> lines) {
      List<Component> out = new ArrayList<>(lines.size());

      for (String l : lines) {
         out.add(item(l));
      }

      return out;
   }

   public static int rgb(String code) {
      if (code != null && code.length() >= 2) {
         LegacyFormat f = LegacyComponentSerializer.parseChar(code.charAt(1));
         if (f != null && f.color() != null) {
            return f.color().value();
         }
      }

      return 5635925;
   }

   public static String strip(String s) {
      return ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', s));
   }

   public static String formatTime(long seconds) {
      long m = seconds / 60L;
      long s = seconds % 60L;
      return m >= 60L ? m / 60L + "h" + String.format("%02d", m % 60L) : m + ":" + String.format("%02d", s);
   }
}
