package mkukic.client;

import arc.*;
import arc.func.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import mindustry.ui.dialogs.*;
import mkukic.*;

/**
 * «Быстрые переключатели» (Shift+F9): все основные функции мода одной сеткой плиток.
 * Клик по плитке включает/выключает функцию, горящая плитка = включено. Сверху — поиск (с учётом раскладки,
 * транслита и опечаток, как в главном меню). Нужна, чтобы не лазить по вкладкам ради одного переключателя.
 */
public class QuickPalette{
    private static BaseDialog dialog;
    private static Table grid;
    private static String query = "";
    private static final Seq<Group> groups = new Seq<>();

    private static class Tile{
        final String glyph, name, key;
        final Boolf<MKUKICSettings> get;
        final Cons2<MKUKICSettings, Boolean> set;

        Tile(String glyph, String name, String key, Boolf<MKUKICSettings> get, Cons2<MKUKICSettings, Boolean> set){
            this.glyph = glyph;
            this.name = name;
            this.key = key;
            this.get = get;
            this.set = set;
        }
    }

    private static class Group{
        final String title;
        final Seq<Tile> tiles = new Seq<>();

        Group(String title){
            this.title = title;
        }

        Group add(String glyph, String name, String key, Boolf<MKUKICSettings> get, Cons2<MKUKICSettings, Boolean> set){
            tiles.add(new Tile(glyph, name, key, get, set));
            return this;
        }
    }

    private static void init(){
        if(!groups.isEmpty()) return;

        groups.add(new Group("Бой")
            .add("commandAttack", "Автоприцел", "F10", s -> s.aimbot, (s, v) -> s.aimbot = v)
            .add("up", "Ускорение", "F11", s -> s.speed, (s, v) -> s.speed = v)
            .add("fire", "Ярость", null, s -> s.rage, (s, v) -> s.rage = v)
            .add("defense", "Крепость", null, s -> s.fortress, (s, v) -> s.fortress = v)
            .add("star", "Бессмертие", "G", s -> s.godmode, (s, v) -> s.godmode = v)
            .add("units", "Полёт", null, s -> s.fly, (s, v) -> s.fly = v)
            .add("refresh", "Мгновенная перезарядка", null, s -> s.instantReload, (s, v) -> s.instantReload = v)
            .add("resize", "Дальность орудий ×2", null, s -> s.infiniteRange, (s, v) -> s.infiniteRange = v)
            .add("add", "Авто-лечение", null, s -> s.autoHeal, (s, v) -> s.autoHeal = v));

        groups.add(new Group("Автоматика")
            .add("logic", "Автопилот", null, s -> s.paEnabled, (s, v) -> s.paEnabled = v)
            .add("production", "Добыча руды (вы)", null, s -> s.paMine, (s, v) -> s.paMine = v)
            .add("hammer", "Стройка и ремонт (вы)", null, s -> s.paBuild, (s, v) -> s.paBuild = v)
            .add("defense", "Уклонение от снарядов", null, s -> s.paDodge, (s, v) -> s.paDodge = v)
            .add("star", "Магнит монет", "F7", s -> s.coinMagnet, (s, v) -> {
                s.coinMagnet = v;
                if(!v) CoinMagnet.resetMagnet();
            })
            .add("map", "Автопилот до монет", null, s -> s.coinPilot, (s, v) -> {
                s.coinPilot = v;
                CoinPilot.reset();
            })
            .add("logic", "ИИ юнитов", null, s -> s.aiEnabled, (s, v) -> s.aiEnabled = v)
            .add("logic", "Авто-Уни (Onix)", null, s -> s.onixAutoUnit, (s, v) -> s.onixAutoUnit = v)
            .add("distribution", "Авто-Филл (Onix)", null, s -> s.onixAutofill, (s, v) -> s.onixAutofill = v)
            .add("units", "Бот (Onix)", "Z", s -> s.onixBot, (s, v) -> s.onixBot = v));

        groups.add(new Group("Обзор")
            .add("eye", "ESP: подсветка врагов", "F12", s -> s.esp, (s, v) -> s.esp = v)
            .add("players", "Подсветка игроков", null, s -> s.espPlayers, (s, v) -> s.espPlayers = v)
            .add("add", "HP и щиты над юнитами", null, s -> s.qolHpBars, (s, v) -> s.qolHpBars = v)
            .add("resize", "Круги дальности", null, s -> s.qolRanges, (s, v) -> s.qolRanges = v)
            .add("box", "Просвет зданий", null, s -> s.xray, (s, v) -> s.xray = v)
            .add("move", "Пути волн на карте", null, s -> s.qolPathOverlay, (s, v) -> s.qolPathOverlay = v)
            .add("info", "Строка статуса (HUD)", null, s -> s.infoHud, (s, v) -> s.infoHud = v)
            .add("move", "Прокрутка краем экрана", null, s -> s.edgePan, (s, v) -> s.edgePan = v));

        groups.add(new Group("Тревоги и сводки")
            .add("warning", "Тревога об атаках", null, s -> s.attackAlert, (s, v) -> s.attackAlert = v)
            .add("warning", "Тревоги производства", null, s -> s.alerts, (s, v) -> s.alerts = v)
            .add("warning", "Геозона", null, s -> s.tripwire, (s, v) -> s.tripwire = v)
            .add("distribution", "Плашка потока ресурсов", null, s -> s.coreFlow, (s, v) -> s.coreFlow = v)
            .add("chartBar", "График производства", null, s -> s.prodGraph, (s, v) -> s.prodGraph = v)
            .add("chat", "Журнал чата", null, s -> s.chatLog, (s, v) -> s.chatLog = v));

        groups.add(new Group("Интерфейс и FPS")
            .add("settings", "Режим производительности", null, s -> s.perfMaster, (s, v) -> {
                s.perfMaster = v;
                mkukic.client.qol.ViewControl.applyPerf();
            })
            .add("chat", "Всплывающие уведомления", null, s -> s.toasts, (s, v) -> s.toasts = v)
            .add("menu", "Кнопка меню на экране", null, s -> s.menuButton, (s, v) -> s.menuButton = v));
    }

    public static void toggle(){
        if(dialog != null && dialog.isShown()) dialog.hide(); else show();
    }

    public static void show(){
        init();
        MKUKICSkin.init();
        if(dialog == null){
            dialog = MKUKICMenu.styledDialog(Ico.of("star") + "  Быстрые переключатели");
            dialog.cont.table(top -> {
                top.left();
                top.add("[#6fd6ff]" + Ico.of("zoom") + "[]").padRight(8f);
                top.field(query, MKUKICSkin.field, txt -> {
                    query = txt == null ? "" : txt.trim();
                    fill();
                }).width(360f).height(36f);
                top.button(Ico.of("cancel") + " Выключить всё", MKUKICSkin.actBtn, () -> {
                    MKUKICMenu.disableAll();
                    fill();
                }).height(36f).minWidth(200f).padLeft(14f);
                top.button(Ico.of("menu") + " Полное меню", MKUKICSkin.actBtn, () -> {
                    dialog.hide();
                    MKUKICMenu.show();
                }).height(36f).minWidth(180f).padLeft(6f);
            }).growX().padBottom(8f).row();
            dialog.cont.pane(MKUKICSkin.scroll, p -> {
                grid = p;
                p.top().left();
            }).grow().minWidth(Core.scene == null ? 1000f : Math.min(1100f, Core.scene.getWidth() * 0.8f))
                .minHeight(Core.scene == null ? 500f : Core.scene.getHeight() * 0.62f);
        }
        fill();
        dialog.show();
    }

    private static void fill(){
        if(grid == null) return;
        grid.clear();
        grid.top().left();
        int shown = 0;
        for(Group g : groups){
            Seq<Tile> vis = g.tiles.select(t -> query.isEmpty() || OnixPanel.fuzzyMatch(query, t.name));
            if(vis.isEmpty()) continue;
            MKUKICMenu.sub(grid, g.title);
            grid.table(row -> {
                row.left().top();
                int col = 0;
                for(Tile t : vis){
                    row.button(b -> {
                        b.left();
                        b.add("[#6fd6ff]" + Ico.of(t.glyph) + "[]").padRight(10f).padLeft(4f);
                        b.add(t.name).left().growX();
                        if(t.key != null) b.add("[#5f6b7d]" + t.key).padLeft(8f).padRight(4f);
                    }, MKUKICSkin.toggleBtn, () -> {
                        MKUKICSettings s = MKUKICSettings.s();
                        t.set.get(s, !t.get.get(s));
                        MKUKICSettings.save();
                    }).checked(b -> t.get.get(MKUKICSettings.s())).size(262f, 48f).pad(3f);
                    if(++col % 4 == 0) row.row();
                }
            }).left().growX().row();
            shown += vis.size;
        }
        if(shown == 0) grid.add("[gray]ничего не найдено").left().padTop(16f).row();
    }
}
