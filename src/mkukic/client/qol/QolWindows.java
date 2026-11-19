package mkukic.client.qol;

import arc.*;
import arc.func.*;
import arc.input.*;
import arc.math.*;
import arc.scene.*;
import arc.scene.event.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import mindustry.core.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.ui.*;
import mkukic.*;
import mkukic.client.*;

import static mindustry.Vars.*;

/**
 * Система плавающих окон MKUKIC QoL (аналог Mindow из MI2-Utilities).
 * Каждое окно — независимая Table на сцене: перетаскивается за заголовок,
 * сворачивается в шапку, закрывается, запоминает позицию/состояние в настройках.
 * Окна регистрируются один раз, контент строится лениво при первом открытии.
 */
public class QolWindows{
    /** Одно плавающее окно. */
    public static class Win{
        public final String id;
        public final String title;
        public final Cons<Table> builder;
        public final float defXF, defYF; // доля экрана для позиции по умолчанию

        Table root, content;
        boolean built, collapsed, open;

        Win(String id, String title, float defXF, float defYF, Cons<Table> builder){
            this.id = id;
            this.title = title;
            this.defXF = defXF;
            this.defYF = defYF;
            this.builder = builder;
        }

        public boolean isOpen(){
            return open;
        }

        void build(){
            if(built) return;
            built = true;
            MKUKICSkin.init();

            root = new Table();
            root.background(MKUKICSkin.panel);

            // шапка: логотип + заголовок (ручка перетаскивания) + свернуть + закрыть
            Table head = root.table(h -> {
                h.left();
                h.image(MKUKICSkin.logoDrawable).size(16f).padLeft(8f).padRight(6f);
                h.add("[#ffb454]" + icon(id) + " " + title + "[]").padRight(10f).left();
                h.add().growX();
                h.button("–", MKUKICSkin.iconBtn, () -> {
                    collapsed = !collapsed;
                    applyCollapsed();
                    savePos();
                }).size(26f).padRight(1f);
                h.button("✕", MKUKICSkin.iconBtn, () -> close()).size(26f).padRight(4f);
            }).growX().minWidth(180f).height(30f).get();
            head.setBackground(MKUKICSkin.head);
            makeDraggable(root, head, this::savePos);
            root.row();

            content = new Table();
            content.top().left();
            builder.get(content);
            root.add(content).top().left().pad(4f).maxWidth(Core.scene == null ? 560f : Core.scene.getWidth() * 0.8f);

            root.pack();

            // клик по окну — поверх остальных
            root.addListener(new InputListener(){
                @Override
                public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button){
                    root.toFront();
                    return false;
                }
            });

            Core.scene.add(root);
            loadPos();
            applyCollapsed();
        }

        void applyCollapsed(){
            if(root == null) return;
            content.visible = !collapsed;
            root.pack();
            clamp(root);
        }

        public void open(){
            if(Core.scene == null) return;
            if(!built) build();
            open = true;
            root.visible = true;
            root.toFront();
            loadPos();
            applyCollapsed();
        }

        public void close(){
            open = false;
            if(root != null) root.visible = false;
        }

        public void toggle(){
            if(open) close(); else open();
        }

        void savePos(){
            if(root == null) return;
            MKUKICSettings.s().winPos.put(id, (int)root.x + "|" + (int)root.y + "|" + (collapsed ? 1 : 0));
            MKUKICSettings.save();
        }

        void loadPos(){
            if(root == null || Core.scene == null) return;
            String v = MKUKICSettings.s().winPos.get(id);
            if(v != null){
                String[] p = v.split("\\|");
                if(p.length >= 2 && Strings.canParseFloat(p[0]) && Strings.canParseFloat(p[1])){
                    collapsed = p.length >= 3 && p[2].equals("1");
                    root.setPosition(Strings.parseFloat(p[0]), Strings.parseFloat(p[1]));
                    clamp(root);
                    return;
                }
            }
            // позиция по умолчанию — доля экрана
            root.setPosition(
                Mathf.clamp(Core.scene.getWidth() * defXF, 0f, Math.max(0f, Core.scene.getWidth() - root.getWidth())),
                Mathf.clamp(Core.scene.getHeight() * defYF, 0f, Math.max(0f, Core.scene.getHeight() - root.getHeight())));
        }

        public void resetPos(){
            MKUKICSettings.s().winPos.remove(id);
            loadPos();
        }
    }

    public static final Seq<Win> all = new Seq<>();
    private static final ObjectMap<String, Win> byId = new ObjectMap<>();

    public static Win register(String id, String title, float defXF, float defYF, Cons<Table> builder){
        Win w = new Win(id, title, defXF, defYF, builder);
        all.add(w);
        byId.put(id, w);
        return w;
    }

    /** Регистрация всех окон QoL + окна-хаба. */
    public static void init(){
        if(!byId.isEmpty()) return;

        register("hub",       "Хаб окон",            0.04f, 0.55f, QolWindows::buildHub);
        register("rules",     "Правила карты",      0.22f, 0.30f, RuleViewer::build);
        register("waves",     "Монитор волн",       0.02f, 0.72f, WaveMonitor::build);
        register("core",      "Ядро и ресурсы",      0.38f, 0.04f, CoreStats::build);
        register("power",     "Энергосети",         0.68f, 0.04f, PowerMonitor::build);
        register("recipes",   "Справочник рецептов",      0.18f, 0.12f, ContentBrowser::build);
        register("mapsearch", "Поиск по карте",     0.60f, 0.45f, MapSearch::build);
        register("quick",     "Быстрые действия стройки",   0.04f, 0.12f, QuickActions::build);
        register("tripwire",  "Геозона",  0.74f, 0.55f, Tripwire::build);
        register("upgrade",   "Апгрейд транспорта", 0.05f, 0.25f, SmartUpgrade::build);
        register("icons",     "Символы и иконки",   0.30f, 0.18f, IconKit::build);
        register("view",      "Камера, время, FPS", 0.62f, 0.30f, ViewControl::build);
        register("formations", "Формации (RTS)",    0.30f, 0.58f, Formations::build);
        register("paths",      "Пути волн",         0.56f, 0.60f, PathPreview::build);
        register("blocks",     "Каталог блоков (F2)", 0.26f, 0.22f, BuildWheel::buildGrid);
        register("quickmsg",   "Редактор быстрых сообщений (V)", 0.30f, 0.30f, QuickMsgWheel::buildEditor);
        register("teamswap",   "Угон юнитов (K)",   0.34f, 0.26f, mkukic.client.TeamSwap::build);
        register("logic",      "Бэкапы логики (mlog)", 0.36f, 0.34f, LogicBackup::build);
        register("music",      "Музыкальный плеер", 0.46f, 0.14f, MusicBox::build);
        register("drill",      "Планировщик буров", 0.66f, 0.18f, DrillPlanner::build);
        register("inspector",  "Инспектор",         0.76f, 0.34f, Inspector::build);
        register("markers",    "Маркеры карты",     0.44f, 0.72f, MapMarkers::build);
        register("cursors",    "Курсоры игроков",   0.82f, 0.68f, CursorTracker::build);
        register("prune",      "Умный снос",        0.48f, 0.42f, SmartPrune::build);

        // ---- v1.16: вторая волна портов из каталога модов ----
        register("attack",     "Журнал атак",  0.86f, 0.42f, AttackAlert::build);
        register("chat",       "Журнал чата",      0.08f, 0.16f, ChatLog::build);
        register("xray",       "Просвет зданий",    0.34f, 0.46f, XRay::build);
        register("flow",       "Поток ресурсов",    0.20f, 0.62f, CoreFlow::build);
        register("prodgraph",  "График производства", 0.40f, 0.26f, ProductionGraph::build);
        register("alerts",     "Тревоги производства", 0.66f, 0.50f, AlertList::build);
        register("spectate",   "Операторская камера", 0.52f, 0.52f, SpectateCam::build);
    }

    public static @Nullable Win get(String id){
        return byId.get(id);
    }

    public static void open(String id){
        Win w = byId.get(id);
        if(w != null) w.open();
    }

    public static void toggle(String id){
        Win w = byId.get(id);
        if(w != null) w.toggle();
    }

    public static boolean isOpen(String id){
        Win w = byId.get(id);
        return w != null && w.open;
    }

    public static void closeAll(){
        for(Win w : all) w.close();
    }

    public static void resetPositions(){
        for(Win w : all) w.resetPos();
        MKUKICSettings.save();
    }

    private static boolean menuHidden;

    /** Каждый кадр (ведём от Trigger.uiDrawBegin — срабатывает и в меню, и в игре). */
    public static void update(){
        if(Core.scene == null) return;
        if(state == null || state.isMenu()){
            // Главное меню: прячем окна ОДИН РАЗ при выходе из игры, дальше не мешаем —
            // хаб можно открыть и здесь (F4), а Trigger.update в меню не fires.
            if(!menuHidden){
                menuHidden = true;
                for(Win w : all) if(w.root != null) w.root.visible = false;
            }
            return;
        }
        menuHidden = false;
        MKUKICSettings s = MKUKICSettings.s();
        boolean vis = s.enabled && s.qolEnabled && !Core.scene.hasDialog();
        for(Win w : all){
            if(w.root == null) continue;
            boolean show = vis && w.open;
            if(show && !w.root.visible) w.root.toFront();
            w.root.visible = show;
            if(show) clamp(w.root);
        }
    }

    /** Окна живут на сцене; при выгрузке игры прячем их. */
    public static void dispose(){
        for(Win w : all){
            if(w.root != null){
                w.root.remove();
                w.root = null;
                w.built = false;
                w.open = false;
            }
        }
    }

    // ============================ ХАБ ============================

    /** Категория хаба: заголовок, иконка и окна (по id), в порядке показа. */
    private static class HubCat{
        final String id, title;
        final char icon;
        final String[] ids;

        HubCat(String id, String title, char icon, String... ids){
            this.id = id;
            this.title = title;
            this.icon = icon;
            this.ids = ids;
        }
    }

    private static final HubCat[] hubCats = {
        new HubCat("combat", "Бой и тревоги",        Iconc.commandAttack, "waves", "paths", "attack", "tripwire"),
        new HubCat("eco",    "Экономика и ресурсы",  Iconc.production,    "core", "flow", "prodgraph", "alerts", "power", "drill"),
        new HubCat("build",  "Строительство",        Iconc.hammer,        "blocks", "quick", "upgrade", "prune", "logic"),
        new HubCat("units",  "Юниты",                Iconc.units,         "formations", "teamswap"),
        new HubCat("cam",    "Камера и наблюдение",  Iconc.eye,           "view", "spectate", "cursors"),
        new HubCat("map",    "Карта и информация",   Iconc.map,           "mapsearch", "markers", "rules", "inspector", "xray", "recipes"),
        new HubCat("chat",   "Чат и утилиты",        Iconc.chat,          "chat", "quickmsg", "icons", "music")
    };

    /** Ключ настройки: свёрнутые категории хаба через запятую. */
    private static final String hubCollapsedKey = "mkukic-hub-collapsed";

    private static boolean catCollapsed(String catId){
        if(Core.settings == null) return false;
        for(String c : Core.settings.getString(hubCollapsedKey, "").split(",")){
            if(c.equals(catId)) return true;
        }
        return false;
    }

    private static void setCatCollapsed(String catId, boolean v){
        if(Core.settings == null) return;
        Seq<String> list = new Seq<>();
        for(String c : Core.settings.getString(hubCollapsedKey, "").split(",")){
            if(!c.isEmpty() && !c.equals(catId)) list.add(c);
        }
        if(v) list.add(catId);
        Core.settings.put(hubCollapsedKey, list.toString(","));
    }

    /** Глиф иконки Mindustry по имени контента (юнит/блок/предмет); если нет — запасная UI-иконка. */
    static String glyph(String content, char fallback){
        try{
            if(content != null){
                int g = Fonts.getUnicode(content);
                if(g > 0 && g <= 65535 && !Character.isSurrogate((char)g)) return String.valueOf((char)g);
            }
        }catch(Throwable ignored){}
        return String.valueOf(fallback);
    }

    /** Иконка окна — из контента Mindustry, подобрана по смыслу. */
    public static String icon(String id){
        switch(id){
            case "hub":        return glyph(null, Iconc.menu);
            // бой и тревоги
            case "waves":      return glyph("flare", Iconc.waves);            // волны воздушных
            case "paths":      return glyph("dagger", Iconc.right);           // путь наземных волн
            case "attack":     return glyph("ripple", Iconc.warning);         // атаки по базе
            case "tripwire":   return glyph("shock-mine", Iconc.warning);     // геозона-ловушка
            // экономика и ресурсы
            case "core":       return glyph("core-shard", Iconc.home);
            case "flow":       return glyph("unloader", Iconc.distribution);  // поток из ядра
            case "prodgraph":  return glyph("silicon-smelter", Iconc.chartBar);
            case "alerts":     return glyph("illuminator", Iconc.warning);    // тревоги производства
            case "power":      return glyph("power-node", Iconc.power);
            case "drill":      return glyph("pneumatic-drill", Iconc.production);
            // строительство
            case "blocks":     return glyph("duo", Iconc.box);
            case "quick":      return glyph("mender", Iconc.wrench);
            case "upgrade":    return glyph("titanium-conveyor", Iconc.up);
            case "prune":      return glyph("scrap-wall", Iconc.eraser);
            case "logic":      return glyph("micro-processor", Iconc.logic);
            // юниты
            case "formations": return glyph("fortress", Iconc.commandRally);
            case "teamswap":   return glyph("zenith", Iconc.units);
            // камера и наблюдение
            case "view":       return glyph(null, Iconc.eye);
            case "spectate":   return glyph("alpha", Iconc.eye);
            case "cursors":    return glyph("beta", Iconc.players);
            // карта и информация
            case "mapsearch":  return glyph(null, Iconc.zoom);
            case "markers":    return glyph("launch-pad", Iconc.map);
            case "rules":      return glyph("memory-cell", Iconc.book);
            case "inspector":  return glyph("logic-processor", Iconc.info);
            case "xray":       return glyph("metaglass", Iconc.layers);
            case "recipes":    return glyph("graphite-press", Iconc.crafting);
            // чат и утилиты
            case "chat":       return glyph("message", Iconc.chat);
            case "quickmsg":   return glyph(null, Iconc.chat);
            case "icons":      return glyph("surge-alloy", Iconc.star);
            case "music":      return glyph(null, Iconc.music);
            default:           return glyph(null, Iconc.settings);
        }
    }

    /** Перестроить содержимое хаба (после сворачивания/разворачивания категории). */
    private static void rebuildHub(){
        Win h = byId.get("hub");
        if(h == null || h.content == null) return;
        h.content.clear();
        buildHub(h.content);
        h.applyCollapsed();
    }

    private static int openCount(String[] ids){
        int n = 0;
        for(String id : ids){
            Win w = byId.get(id);
            if(w != null && w.open) n++;
        }
        return n;
    }

    private static void hubCategory(Table t, String catId, String title, char icon, Seq<Win> wins){
        final String[] ids = new String[wins.size];
        for(int i = 0; i < ids.length; i++) ids[i] = wins.get(i).id;

        t.button("", MKUKICSkin.actBtn, () -> {
            setCatCollapsed(catId, !catCollapsed(catId));
            rebuildHub();
        }).growX().height(30f).pad(3f, 0f, 2f, 0f).left().update(b -> {
            boolean col = catCollapsed(catId);
            int open = openCount(ids);
            b.setText((col ? "[lightgray]" + (char)Iconc.right : "[accent]" + (char)Iconc.up) + " [accent]" + (char)icon + " " + title
                + " [lightgray](" + (open > 0 ? "[lime]" + open + "[lightgray]/" : "") + ids.length + ")");
        });
        t.row();

        if(catCollapsed(catId)) return;

        t.table(c -> {
            c.left();
            int col = 0;
            for(Win w : wins){
                final Win fw = w;
                c.button(icon(fw.id) + " " + fw.title, MKUKICSkin.navBtn, fw::toggle)
                    .checked(b -> fw.open).minWidth(200f).height(32f).pad(1.5f).left();
                if(++col % 2 == 0) c.row();
            }
        }).left().padLeft(6f).row();
    }

    private static void buildHub(Table t){
        t.add("[#8b94a5]Плавающие окна MKUKIC QoL, разложены по типам. Можно открыть сразу несколько.\nЗаголовок категории — свернуть/развернуть.[]").left().wrap().width(430f).padBottom(6f).row();

        ObjectSet<String> placed = new ObjectSet<>();
        for(HubCat cat : hubCats){
            Seq<Win> wins = new Seq<>();
            for(String id : cat.ids){
                Win w = byId.get(id);
                if(w != null){
                    wins.add(w);
                    placed.add(id);
                }
            }
            if(wins.size > 0) hubCategory(t, cat.id, cat.title, cat.icon, wins);
        }

        // окна, которых нет ни в одной категории (добавил новое окно и забыл про хаб) — не теряем
        Seq<Win> rest = new Seq<>();
        for(Win w : all){
            if(!w.id.equals("hub") && !placed.contains(w.id)) rest.add(w);
        }
        if(rest.size > 0) hubCategory(t, "other", "Прочее", Iconc.settings, rest);

        t.image(MKUKICSkin.divSoft).height(2f).growX().pad(7f, 2f, 7f, 2f).row();
        t.table(c -> {
            c.left();
            c.button((char)Iconc.cancel + " Закрыть все", MKUKICSkin.actBtn, QolWindows::closeAll).minWidth(150f).height(32f).pad(2f);
            c.button((char)Iconc.refresh + " Сброс позиций", MKUKICSkin.actBtn, () -> {
                resetPositions();
                OverlayUI.resetPositions();
            }).minWidth(150f).height(32f).pad(2f);
        }).left().row();
        t.add("[#7e8798]Позиции окон сохраняются автоматически. F4 — открыть/закрыть этот хаб.\nПеретаскивай окна за заголовок, «–» — свернуть.").left().wrap().width(430f).padTop(8f).row();
    }

    // ============================ ОБЩЕЕ ============================

    static void clamp(Element e){
        if(Core.scene == null) return;
        e.setPosition(
            Mathf.clamp(e.x, 0f, Math.max(0f, Core.scene.getWidth() - e.getWidth())),
            Mathf.clamp(e.y, 0f, Math.max(0f, Core.scene.getHeight() - e.getHeight())));
    }

    static void makeDraggable(Element target, Element handle, Runnable onSave){
        handle.addListener(new InputListener(){
            float ox, oy;
            @Override
            public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button){
                ox = x;
                oy = y;
                target.toFront();
                return true;
            }
            @Override
            public void touchDragged(InputEvent event, float x, float y, int pointer){
                target.setPosition(target.x + (x - ox), target.y + (y - oy));
                clamp(target);
            }
            @Override
            public void touchUp(InputEvent event, float x, float y, int pointer, KeyCode button){
                onSave.run();
            }
        });
    }

    /** Прыжок камерой (или телепорт, если разрешено) к мировой точке. */
    public static void jumpTo(float wx, float wy){
        if(player == null) return;
        if(player.dead()){
            // наблюдатель — свободная камера
            Core.camera.position.set(wx, wy);
        }else if(player.admin || !net.client()){
            // хост/одиночная игра/админ — как в меню MKUKIC: телепорт юнита
            if(player.unit() != null){
                player.unit().set(wx, wy);
                Core.camera.position.set(wx, wy);
            }
        }else{
            MKUKICMod.toast("[lightgray]Точка: [accent]" + World.toTile(wx) + ", " + World.toTile(wy) + "[lightgray] (прыжок доступен наблюдателям/админам)");
            Core.camera.position.set(wx, wy);
        }
    }

    /** Общий заголовок секции внутри окна. */
    public static void section(Table t, String text){
        t.add("[accent]" + text).left().padTop(4f).row();
        t.image(Tex.whiteui, Pal.accent).height(1f).growX().pad(2f, 0f, 4f, 0f).row();
    }

    /** Общий текстовый world-draw (как в EspRenderer). */
    public static void worldText(String text, float x, float y, arc.graphics.Color color){
        if(text == null || text.isEmpty()) return;
        arc.graphics.g2d.Font font = Fonts.outline;
        arc.graphics.g2d.GlyphLayout layout = arc.util.pooling.Pools.obtain(arc.graphics.g2d.GlyphLayout.class, arc.graphics.g2d.GlyphLayout::new);
        boolean ints = font.usesIntegerPositions();
        font.setUseIntegerPositions(false);
        font.getData().setScale(0.25f / Scl.scl(1f));
        layout.setText(font, text);
        font.setColor(color);
        font.draw(text, x, y, 0, arc.util.Align.center, false);
        arc.graphics.g2d.Draw.reset();
        arc.util.pooling.Pools.free(layout);
        font.getData().setScale(1f);
        font.setColor(arc.graphics.Color.white);
        font.setUseIntegerPositions(ints);
    }

    /** Кадровый счётчик для редких обновлений. */
    static boolean every(float frames){
        return ((int)Time.time) % (int)frames == 0;
    }
}
