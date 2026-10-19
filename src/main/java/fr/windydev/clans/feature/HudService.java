package fr.windydev.clans.feature;

import fr.windydev.clans.WindyClans;
import fr.windydev.clans.data.ClanManager;
import fr.windydev.clans.model.ChunkPos;
import fr.windydev.clans.model.Clan;
import fr.windydev.clans.model.Rank;
import fr.windydev.clans.util.Glyphs;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Map.Entry;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.bossbar.BossBar.Color;
import net.kyori.adventure.bossbar.BossBar.Overlay;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

public class HudService {
   private final WindyClans plugin;
   private final Map<UUID, BossBar> bars = new HashMap<>();
   private final Map<UUID, Component> last = new HashMap<>();

   public HudService(WindyClans plugin) {
      this.plugin = plugin;
   }

   public boolean active(Player p) {
      return p.isOnline() && this.plugin.pack().has(p) && !this.plugin.scoreboard().isHidden(p);
   }

   public void refresh(Player p) {
      if (this.active(p)) {
         this.update(p);
      } else {
         this.hide(p);
      }

      this.plugin.scoreboard().refreshVisibility(p);
   }

   public void hide(Player p) {
      BossBar bar = this.bars.remove(p.getUniqueId());
      this.last.remove(p.getUniqueId());
      if (bar != null) {
         p.hideBossBar(bar);
      }
   }

   public void update(Player p) {
      if (!this.active(p)) {
         if (this.bars.containsKey(p.getUniqueId())) {
            this.hide(p);
         }
      } else {
         Component title = this.build(p);
         BossBar bar = this.bars.get(p.getUniqueId());
         if (bar == null) {
            bar = BossBar.bossBar(title, 0.0F, Color.WHITE, Overlay.PROGRESS);
            this.bars.put(p.getUniqueId(), bar);
            p.showBossBar(bar);
         } else if (!title.equals(this.last.get(p.getUniqueId()))) {
            bar.name(title);
         }

         this.last.put(p.getUniqueId(), title);
      }
   }

   public void shutdown() {
      for (Entry<UUID, BossBar> e : this.bars.entrySet()) {
         Player p = this.plugin.getServer().getPlayer(e.getKey());
         if (p != null) {
            p.hideBossBar(e.getValue());
         }
      }

      this.bars.clear();
   }

   private List<HudService.Row> rows(Player p) {
      ClanManager cm = this.plugin.clans();
      Clan c = cm.of(p);
      ChunkPos pos = ChunkPos.of(p.getLocation());
      List<HudService.Row> rows = new ArrayList<>();
      rows.add(new HudService.Row("zone", this.plugin.scoreboard().zoneLabel(p, pos).replace("&7(protégé)", "")));
      if (c == null) {
         rows.add(new HudService.Row("clan", "&8Aucun clan"));
         rows.add(new HudService.Row("level", "&7Menu : &fE &7puis &bbouton W"));
         return rows;
      } else {
         Rank r = c.rank(p.getUniqueId());
         rows.add(new HudService.Row("clan", c.color() + "[" + c.tag() + "] &f" + c.name()));
         rows.add(new HudService.Row("grade", r.colored()));
         long need = cm.xpForNext(c.level());
         int pct = c.level() >= this.plugin.settings().maxLevel ? 100 : (int)Math.min(100L, c.xp() * 100L / Math.max(1L, need));
         rows.add(new HudService.Row("level", "&eNiveau " + c.level() + " &8(" + pct + "%)"));
         rows.add(new HudService.Row("bank", "&6" + c.bank() + " &7" + this.plugin.settings().currencyName));
         rows.add(new HudService.Row("online", "&a" + cm.onlineCount(c) + "&7/" + c.members().size() + " en ligne"));
         String war = this.plugin.war().status(c);
         if (war != null) {
            rows.add(new HudService.Row("war", war.replace("⚔ ", "")));
         }

         return rows;
      }
   }

   private Component build(Player p) {
      List<HudService.Row> rows = this.rows(p);
      int n = Math.min(7, rows.size());
      int titleDy = Glyphs.HUD_TITLE_TOP - 3;
      Glyphs.Line title = Glyphs.text(this.plugin.settings().serverName.replace("&l", ""), titleDy, true);
      List<Glyphs.Line> lines = new ArrayList<>();
      List<Integer> widths = new ArrayList<>();
      int maxW = title.width();

      for (int i = 0; i < n; i++) {
         int dy = Glyphs.HUD_FIRST_LINE + i * Glyphs.HUD_LINE_H - 3;
         HudService.Row row = rows.get(i);
         Glyphs.Line t = Glyphs.text(row.text(), dy, true);
         Component icon = Component.text(Glyphs.icon(row.icon())).font(Key.key("windy", "dy" + dy)).color(Glyphs.mark(NamedTextColor.WHITE));
         int w = Glyphs.iconWidth(row.icon()) + 1 + t.width();
         lines.add(new Glyphs.Line(((TextComponent)((TextComponent)Component.empty().append(icon)).append(Glyphs.shift(1))).append(t.component()), w));
         widths.add(w);
         maxW = Math.max(maxW, w);
      }

      int inner = maxW + 10;
      int tiles = Math.max(1, (inner - 8 + 7) / 8);
      int pw = 8 + tiles * 8;
      Component white = Component.empty();
      Component panel = Component.text(Glyphs.panel("l", n)).font(Glyphs.HUD).color(Glyphs.mark(NamedTextColor.WHITE)).append(Glyphs.shift(-1));

      for (int i = 0; i < tiles; i++) {
         panel = panel.append(Component.text(Glyphs.panel("m", n)).font(Glyphs.HUD).color(Glyphs.mark(NamedTextColor.WHITE))).append(Glyphs.shift(-1));
      }

      panel = panel.append(Component.text(Glyphs.panel("r", n)).font(Glyphs.HUD).color(Glyphs.mark(NamedTextColor.WHITE))).append(Glyphs.shift(-1));
      Component c = white.append(panel).append(Glyphs.shift(-pw));
      int tx = (pw - title.width()) / 2;
      c = c.append(Glyphs.shift(tx)).append(title.component()).append(Glyphs.shift(-(tx + title.width())));

      for (Glyphs.Line l : lines) {
         c = c.append(Glyphs.shift(5)).append(l.component()).append(Glyphs.shift(-(5 + l.width())));
      }

      return c;
   }

   private record Row(String icon, String text) {
   }
}
