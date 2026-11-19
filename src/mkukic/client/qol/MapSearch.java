package mkukic.client.qol;

import arc.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import mindustry.content.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.type.*;
import mindustry.ui.*;
import mindustry.world.*;
import mindustry.world.blocks.environment.*;
import mkukic.*;
import mkukic.client.*;

import static mindustry.Vars.*;

/**
 * Глобальный поиск по карте (MI2-Utilities «Миникарта: глобальный поиск местности и построек»):
 * ищет постройки/руду/местность по имени, а также по категориям
 * (turret, production, power, defense, liquid, distribution, unit, storage, "spawn", "core", "ore").
 * Сканирование чанками по кадрам — без фризов на больших картах. Клик «→» — прыжок камерой/телепорт.
 */
public class MapSearch{
    public static class Result{
        String name;
        int x, y;
        String kind;
        Result(String name, int x, int y, String kind){
            this.name = name; this.x = x; this.y = y; this.kind = kind;
        }
    }

    private static final Seq<Result> results = new Seq<>();
    private static String query = "";
    private static float queryChanged = -9999f;
    private static boolean scanning;
    private static long scanIdx;
    private static long scanTotal;
    private static int found;
    private static @Nullable Table listRef;
    private static final int MAX_RESULTS = 300;

    public static void reset(){
        results.clear();
        scanning = false;
        scanIdx = 0;
        found = 0;
    }

    public static void tick(){
        // дебаунс: авто-пересканирование через 0.75с после последнего изменения запроса
        if(!scanning && !query.isEmpty() && Time.time - queryChanged > 45f && Time.time - queryChanged < 60f){
            queryChanged = -9999f; // не запускать дважды
            startScan(query);
        }
        if(!scanning || world == null) return;

        int w = world.width(), h = world.height();
        long budget = 40000;
        long end = Math.min(scanTotal, scanIdx + budget);
        for(long i = scanIdx; i < end; i++){
            int x = (int)(i % w), y = (int)(i / w);
            Tile t = world.tile(x, y);
            if(t == null) continue;
            if(match(t)) addResult(t);
        }
        scanIdx = end;
        if(scanIdx >= scanTotal){
            scanning = false;
            rebuildList();
        }else if(found % 10 == 0){
            // частичное обновление списка по ходу скана
            if(((int)Time.time) % 20 == 0) rebuildList();
        }
    }

    private static void addResult(Tile t){
        if(results.size >= MAX_RESULTS){
            scanning = false;
            return;
        }
        found++;
        Block b = t.block();
        String name = b != Blocks.air ? (b.localizedName == null ? b.name : b.localizedName)
            : (t.overlay() != Blocks.air.asFloor() ? floorName(t) : "");
        if(name == null || name.isEmpty()) name = floorName(t);
        String kind = b != Blocks.air ? "блок" : "местность";
        results.add(new Result(name, t.x, t.y, kind));
    }

    private static String floorName(Tile t){
        Floor f = t.overlay() == null || t.overlay() == Blocks.air.asFloor() ? t.floor() : t.overlay();
        if(f == null) return "";
        String ln = f.localizedName;
        return ln == null || ln.isEmpty() ? f.name : ln;
    }

    private static boolean match(Tile t){
        Block b = t.block();
        String q = query;

        // спец-токены
        switch(q){
            case "spawn":
                return b == Blocks.spawn;
            case "core":
                return b instanceof mindustry.world.blocks.storage.CoreBlock;
            case "ore":
                return t.overlay() != null && t.overlay().itemDrop != null;
        }

        if(b != Blocks.air && b.buildVisibility != mindustry.world.meta.BuildVisibility.hidden){
            String ln = b.localizedName == null ? "" : b.localizedName.toLowerCase();
            if(ln.contains(q) || b.name.toLowerCase().contains(q)) return true;
            if(b.category != null && b.category.name().toLowerCase().equals(q)) return true;
        }

        // руда / местность
        Floor ov = t.overlay();
        if(ov != null && ov != Blocks.air.asFloor()){
            if(ov.itemDrop != null){
                Item drop = ov.itemDrop;
                String dn = drop.localizedName == null ? drop.name : drop.localizedName;
                if(dn.toLowerCase().contains(q) || drop.name.toLowerCase().contains(q)
                    || ov.name.toLowerCase().contains(q)) return true;
            }else{
                String ln = ov.localizedName == null ? "" : ov.localizedName.toLowerCase();
                if(!ln.isEmpty() && ln.contains(q)) return true;
            }
        }
        if(q.length() >= 4){
            Floor fl = t.floor();
            if(fl != null){
                String ln = fl.localizedName == null ? "" : fl.localizedName.toLowerCase();
                if(!ln.isEmpty() && ln.contains(q)) return true;
            }
        }
        return false;
    }

    public static void startScan(String q){
        q = q == null ? "" : q.trim().toLowerCase();
        if(q.isEmpty() || world == null) return;
        query = q;
        results.clear();
        found = 0;
        scanIdx = 0;
        scanTotal = (long)world.width() * world.height();
        scanning = true;
    }

    // ============================ ОКНО ============================

    public static void build(Table t){
        t.table(c -> {
            c.left();
            c.add("Запрос:").padRight(4f);
            c.field("", MKUKICSkin.field, txt -> {
                queryChanged = Time.time;
                if(txt != null && txt.trim().length() >= 2) query = txt.trim().toLowerCase();
            }).width(190f);
            c.button("Найти", MKUKICSkin.actBtn, () -> {
                queryChanged = -9999f;
                startScan(query);
            }).padLeft(6f).minWidth(90f).height(30f);
        }).left().growX().row();

        t.add("[darkgray]Ищи: имя постройки/руды/местности или категорию:\n"
            + "turret, production, power, defense, liquid, distribution, unit, storage;\n"
            + "спец: spawn, core, ore. Авто-поиск через 0.75с после ввода.").left().top().wrap().width(360f).pad(3f, 0f, 5f, 0f).row();

        t.label(() -> scanning
            ? "[accent]Сканирование: " + (scanTotal == 0 ? 0 : (int)(scanIdx * 100 / scanTotal)) + "%   найдено: " + results.size
            : "[lightgray]Результатов: [white]" + results.size + (results.size >= MAX_RESULTS ? "[scarlet] (лимит)" : ""))
            .left().pad(2f, 0f, 4f, 0f).row();

        Table list = new Table();
        list.top().left();
        listRef = list;
        t.pane(list).maxHeight(260f).growX().row();
        rebuildList();
    }

    private static void rebuildList(){
        if(listRef == null) return;
        Table list = listRef;
        list.clear();
        list.top().left();
        if(results.isEmpty()){
            list.add(scanning ? "[darkgray]сканирую..." : "[darkgray]пусто — введи запрос и жми «Найти»").left().pad(6f).row();
            return;
        }
        int i = 0;
        for(Result r : results){
            final Result fr = r;
            list.table(row -> {
                row.left();
                row.add("[white]" + fr.name).left().minWidth(150f).padRight(6f);
                row.add("[darkgray](" + fr.x + ", " + fr.y + ")").left().padRight(6f);
                row.button("→", MKUKICSkin.actBtn, () -> QolWindows.jumpTo(fr.x * tilesize + tilesize / 2f, fr.y * tilesize + tilesize / 2f))
                    .size(26f);
            }).left().growX().pad(1f).row();
            if(++i >= 100) break;
        }
    }
}
