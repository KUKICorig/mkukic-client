package mkukic.client.qol;

import arc.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.math.*;
import arc.math.geom.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import mindustry.content.*;
import mindustry.core.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.type.*;
import mindustry.ui.*;
import mindustry.world.*;
import mindustry.world.blocks.defense.turrets.*;
import mindustry.world.blocks.power.*;
import mindustry.world.blocks.production.*;
import mindustry.world.blocks.storage.*;
import mindustry.world.blocks.storage.CoreBlock.*;
import mindustry.world.blocks.units.*;
import mkukic.*;
import mkukic.client.*;

import static mindustry.Vars.*;

/**
 * Список тревог производства (v1.16) — порт идей из модов:
 *  - MinerTools «TurretAlert»: предупреждение, что турель осталась без боекомплекта;
 *  - MinerTools «ConstructorBlockInfo»: видно, что завод встал;
 *  - Power-Grid-Minimap (Wind-DeterMination): подсказки, где энергосеть в минусе;
 *  - MI2 «Quick Actions»: список проблем в одном окне, клик = прыжок камерой.
 *
 * Раз в {@link #SCAN_EVERY} кадров сканируем здания своей команды в кадре и собираем
 * четыре класса проблем:
 *   ПУСТАЯ ТУРЕЛЬ   — ItemTurret с нулевым боезапасом (или LiquidTurret без жидкости);
 *   ВСТАЛ ЗАВОД     — питается, но прогресс не двигается (забит выход / нет входа);
 *   НЕТ ПИТАНИЯ     — потребитель с power.status ≈ 0;
 *   ПЕРЕПОЛНЕНИЕ    — ядро/склад забиты, добыча уходит в никуда.
 * Всё клиентское: только чтение состояния, ничего не ломает в мультиплеере.
 */
public class AlertList{
    /** Тип проблемы. */
    public enum Kind{
        turret("[#ff8f7d]турель без БК"),
        factory("[#ffd37f]завод встал"),
        power("[#ff9d5c]нет питания"),
        full("[#6fd6ff]переполнен склад");

        public final String label;
        Kind(String label){ this.label = label; }
    }

    /** Одна найденная проблема. */
    public static class Problem{
        public Kind kind;
        public String name;
        public int tx, ty;
        public String detail = "";
        public float since;      // Time.time, когда заметили впервые

        public float wx(){ return tx * tilesize + tilesize / 2f; }
        public float wy(){ return ty * tilesize + tilesize / 2f; }
    }

    /** Кадров между сканами (15 = 4 раза в секунду). */
    public static final int SCAN_EVERY = 15;
    public static final int MAX_PROBLEMS = 200;

    public static final Seq<Problem> problems = new Seq<>();
    private static final IntMap<Problem> byId = new IntMap<>();
    /** id здания -> прогресс в прошлый скан и время, когда мы его впервые увидели. */
    private static final IntFloatMap lastProgress = new IntFloatMap();
    private static final ObjectMap<Integer, Float> seenSince = new ObjectMap<>();
    private static float scanAt;
    private static Table listRef;
    private static int lastSig = -1;
    private static Kind filter = null;

    // ======================= ЧИСТЫЕ ФУНКЦИИ (тестируются) =======================

    /** Турель без боекомплекта. */
    public static boolean turretStarved(int ammo){
        return ammo <= 0;
    }

    /**
     * Завод «встал»: питается и должен работать, но прогресс НЕ ДВИНУЛСЯ с прошлого скана.
     * (Старая проверка «progress < 0.02» давала ложные срабатывания на только что заложенном
     * заводе и на паузе в 1 кадр — сравниваем именно приращение за knownFor секунд.)
     */
    public static boolean factoryStalled(float progress, float lastProgress, float knownFor,
                                         float efficiency, boolean powered){
        if(!powered || efficiency < 0.3f) return false;
        if(knownFor < 1.5f) return false;                    // ещё рано судить
        return Math.abs(progress - lastProgress) < 0.0005f;  // ни шага за всё время наблюдения
    }

    /** Потребитель без питания. */
    public static boolean powerStarved(float status, boolean consumesPower){
        return consumesPower && status < 0.02f;
    }

    /** Склад забит (>= доли от вместимости). */
    public static boolean storageFull(int total, int capacity, float threshold){
        return capacity > 0 && total >= capacity * threshold;
    }

    /** Сколько проблем каждого типа (для шапки окна). */
    public static int count(Seq<Problem> list, Kind k){
        int n = 0;
        for(Problem p : list) if(p.kind == k) n++;
        return n;
    }

    // ======================= ЛОГИКА =======================

    /**
     * Турель, которая ест ПРЕДМЕТЫ: у жидкостных/энергетических totalAmmo всегда 0,
     * поэтому их нельзя тревожить (иначе ложные срабатывания на всех волновых/лазерах).
     */
    public static boolean isItemTurret(Block block){
        return block instanceof ItemTurret;
    }

    /** Ведём историю прогресса здания и решаем, встал ли он. */
    private static boolean stalled(int id, float progress, float efficiency, boolean powered){
        Float since = seenSince.get(id);
        if(since == null){
            seenSince.put(id, Time.time);
            lastProgress.put(id, progress);
            return false;
        }
        float prev = lastProgress.get(id, progress);
        boolean stuck = factoryStalled(progress, prev, (Time.time - since) / 60f, efficiency, powered);
        if(Math.abs(progress - prev) > 0.0005f){
            // прогресс пошёл — сбрасываем точку отсчёта
            seenSince.put(id, Time.time);
        }
        lastProgress.put(id, progress);
        return stuck;
    }

    public static void reset(){
        problems.clear();
        byId.clear();
        lastProgress.clear();
        seenSince.clear();
        scanAt = 0f;
        lastSig = -1;
    }

    public static void tick(){
        MKUKICSettings s = MKUKICSettings.s();
        if(!s.enabled || !s.qolEnabled || !s.alerts) return;
        if(player == null || state == null || state.isMenu() || world == null) return;
        if(Time.time - scanAt < SCAN_EVERY) return;
        scanAt = Time.time;
        scan(s);
    }

    private static void scan(MKUKICSettings s){
        Team my = player.team();
        Rect view = Core.camera.bounds(Tmp.r1).grow(s.alertsWholeMap ? 1e6f : 200f);

        IntSet seen = new IntSet();
        int x0 = World.toTile(view.x), y0 = World.toTile(view.y);
        int x1 = World.toTile(view.x + view.width), y1 = World.toTile(view.y + view.height);
        x0 = Mathf.clamp(x0, 0, world.width() - 1);
        x1 = Mathf.clamp(x1, 0, world.width() - 1);
        y0 = Mathf.clamp(y0, 0, world.height() - 1);
        y1 = Mathf.clamp(y1, 0, world.height() - 1);

        for(int tx = x0; tx <= x1; tx++){
            for(int ty = y0; ty <= y1; ty++){
                Tile tile = world.tile(tx, ty);
                if(tile == null) continue;
                Building b = tile.build;
                if(b == null || b.team != my || b.isPayload()) continue;
                if(b.block == null) continue;
                seen.add(b.id);

                Kind kind = null;
                String detail = "";

                boolean powered = b.power == null || b.power.status > 0.02f;

                if(isItemTurret(b.block) && b instanceof Turret.TurretBuild t){
                    if(turretStarved(t.totalAmmo)){
                        kind = Kind.turret;
                        detail = "снарядов: 0";
                    }
                }else if(b instanceof UnitFactory.UnitFactoryBuild f){
                    float pr = f.currentPlan >= 0 ? f.fraction() : -1f;
                    if(pr >= 0f && stalled(b.id, pr, b.efficiency, powered)){
                        kind = Kind.factory;
                        detail = "план #" + (f.currentPlan + 1) + " не двигается";
                    }
                }else if(b instanceof GenericCrafter.GenericCrafterBuild c){
                    if(stalled(b.id, c.progress, b.efficiency, powered)){
                        kind = Kind.factory;
                        detail = "производство стоит";
                    }
                }else if(b instanceof CoreBuild core){
                    int cap = 0, total = 0;
                    for(Item item : content.items()){
                        if(item == null) continue;
                        cap = Math.max(cap, core.getMaximumAccepted(item));
                        total = Math.max(total, core.items.get(item));
                    }
                    if(storageFull(total, cap, s.alertsFullRatio)){
                        kind = Kind.full;
                        detail = total + "/" + cap;
                    }
                }else if(b instanceof StorageBlock.StorageBuild st){
                    if(b.items != null && storageFull(b.items.total(), b.block.itemCapacity, s.alertsFullRatio)){
                        kind = Kind.full;
                        detail = b.items.total() + "/" + b.block.itemCapacity;
                    }
                }

                if(kind == null && s.alertsPower && b.power != null
                    && !(b.block instanceof PowerNode) && powerStarved(b.power.status, b.block.consumesPower)){
                    kind = Kind.power;
                    detail = "статус сети " + (int)(b.power.status * 100f) + "%";
                }

                if(kind == null){
                    Problem old = byId.get(b.id);
                    if(old != null){
                        byId.remove(b.id);
                        problems.remove(old);
                    }
                    continue;
                }

                Problem p = byId.get(b.id);
                if(p == null){
                    p = new Problem();
                    p.since = Time.time;
                    byId.put(b.id, p);
                    problems.add(p);
                    while(problems.size > MAX_PROBLEMS){
                        Problem removed = problems.remove(0);
                        dropFromIndex(removed);
                    }
                }
                p.kind = kind;
                p.detail = detail;
                p.tx = tx; p.ty = ty;
                p.name = b.block.localizedName == null ? b.block.name : b.block.localizedName;
            }
        }

        // здания, ушедшие из кадра/разрушенные, — снимаем с учёта
        Seq<IntMap.Entry<Problem>> snapshot = new Seq<>();
        for(IntMap.Entry<Problem> e : byId.entries()) snapshot.add(e);
        for(IntMap.Entry<Problem> e : snapshot){
            Building b = Groups.build.getByID(e.key);
            if(b == null || b.team != my){
                byId.remove(e.key);
                problems.remove(e.value);
                lastProgress.remove(e.key, 0f);
                seenSince.remove(e.key);
            }
        }

        problems.sort((a, b) -> a.kind.compareTo(b.kind));
    }

    /** Убрать запись из индекса по значению (используется при переполнении списка). */
    private static void dropFromIndex(Problem p){
        Seq<IntMap.Entry<Problem>> tmp = new Seq<>();
        for(IntMap.Entry<Problem> e : byId.entries()) tmp.add(e);
        for(IntMap.Entry<Problem> e : tmp){
            if(e.value == p){
                byId.remove(e.key);
                return;
            }
        }
    }

    // ======================= МИР =======================

    public static void draw(){
        MKUKICSettings s = MKUKICSettings.s();
        if(!s.enabled || !s.qolEnabled || !s.alerts || !s.alertsWorld) return;
        if(problems.isEmpty()) return;
        if(player == null || state == null || state.isMenu()) return;
        if(Core.scene != null && Core.scene.hasDialog()) return;

        Rect view = Core.camera.bounds(Tmp.r1).grow(80f);
        Draw.z(Layer.overlayUI);
        for(Problem p : problems){
            if(filter != null && p.kind != filter) continue;
            float wx = p.wx(), wy = p.wy();
            if(!view.contains(wx, wy)) continue;
            Color c = colorOf(p.kind);
            float pulse = 0.55f + 0.45f * Mathf.sin(Time.time / 8f + p.tx);
            Draw.color(c.r, c.g, c.b, 0.85f * pulse);
            Lines.stroke(1.6f);
            Lines.poly(wx, wy, 4, 11f, 45f);
            Draw.color(c.r, c.g, c.b, 0.9f);
            QolWindows.worldText(iconOf(p.kind), wx, wy + 15f, Draw.getColor());
        }
        Draw.reset();
    }

    public static Color colorOf(Kind k){
        switch(k){
            case turret: return Color.valueOf("ff8f7d");
            case factory: return Color.valueOf("ffd37f");
            case power: return Color.valueOf("ff9d5c");
            default: return Color.valueOf("6fd6ff");
        }
    }

    private static String iconOf(Kind k){
        switch(k){
            case turret: return "" + (char)Iconc.commandAttack;
            case factory: return "" + (char)Iconc.production;
            case power: return "" + (char)Iconc.power;
            default: return "" + (char)Iconc.box;
        }
    }

    // ======================= ОКНО =======================

    public static void build(Table t){
        QolWindows.section(t, "Тревоги производства");
        t.add("[lightgray]Что сломалось прямо сейчас: турели без снарядов, вставшие заводы,\n"
            + "обесточенные потребители и забитые склады. Клик по строке — прыжок камерой.\n"
            + "Скан идёт по зданиям в кадре (или по всей карте — настройкой).")
            .left().top().wrap().width(400f).pad(3f, 0f, 6f, 0f).row();

        t.table(c -> {
            c.left();
            c.label(AlertList::summary).left();
        }).left().padBottom(3f).row();

        t.table(c -> {
            c.left();
            c.button("Все", MKUKICSkin.toggleBtn, () -> { filter = null; rebuildList(); })
                .checked(b -> filter == null).minWidth(56f).height(28f).pad(1f);
            for(Kind k : Kind.values()){
                final Kind fk = k;
                c.button(iconOf(k) + " " + labelOf(k), MKUKICSkin.toggleBtn, () -> {
                    filter = filter == fk ? null : fk;
                    rebuildList();
                }).checked(b -> filter == fk).minWidth(112f).height(28f).pad(1f);
            }
        }).left().padBottom(4f).row();

        Table list = new Table();
        list.top().left();
        listRef = list;
        t.pane(MKUKICSkin.scroll, list).maxHeight(230f).growX().row();

        t.table(c -> {
            c.left();
            c.check("Метки в мире", MKUKICSettings.s().alertsWorld, v -> {
                MKUKICSettings.s().alertsWorld = v;
                MKUKICSettings.save();
            }).padRight(8f);
            c.check("Сканировать всю карту", MKUKICSettings.s().alertsWholeMap, v -> {
                MKUKICSettings.s().alertsWholeMap = v;
                MKUKICSettings.save();
            }).padRight(8f);
            c.check("Проверять питание", MKUKICSettings.s().alertsPower, v -> {
                MKUKICSettings.s().alertsPower = v;
                MKUKICSettings.save();
            });
        }).left().padTop(4f).row();

        t.table(c -> {
            c.left();
            c.add("[#c2cad8]Порог «склад забит»").padRight(6f);
            c.slider(0.5f, 1f, 0.05f, MKUKICSettings.s().alertsFullRatio, v -> {
                MKUKICSettings.s().alertsFullRatio = v;
                MKUKICSettings.save();
            }).width(140f);
            c.label(() -> (int)(MKUKICSettings.s().alertsFullRatio * 100f) + "%").padLeft(6f);
        }).left().padTop(2f).row();

        rebuildList();
    }

    private static String labelOf(Kind k){
        return Strings.stripColors(k.label);
    }

    public static String summary(){
        return "[#8b94a5]найдено: [white]" + problems.size
            + "  [#ff8f7d]турели " + count(problems, Kind.turret)
            + "  [#ffd37f]заводы " + count(problems, Kind.factory)
            + "  [#ff9d5c]питание " + count(problems, Kind.power)
            + "  [#6fd6ff]склады " + count(problems, Kind.full);
    }

    /** Сколько всего проблем — для HUD-строки. */
    public static int total(){
        return problems.size;
    }

    public static String hudChip(){
        if(problems.isEmpty()) return null;
        return "[#ffd37f]" + (char)Iconc.warning + problems.size;
    }

    private static void rebuildList(){
        if(listRef == null) return;
        Table list = listRef;
        list.clear();
        list.top().left();
        int n = 0;
        for(Problem p : problems){
            if(filter != null && p.kind != filter) continue;
            if(n++ >= 120) break;
            final Problem fp = p;
            Color c = colorOf(p.kind);
            list.table(row -> {
                row.left();
                row.button("[#" + c.toString() + "]" + iconOf(p.kind) + "[] " + p.name
                        + " [#8b94a5]" + p.detail + " (" + p.tx + ", " + p.ty + ")",
                    MKUKICSkin.navBtn, () -> QolWindows.jumpTo(fp.wx(), fp.wy()))
                    .minWidth(360f).height(28f).left().pad(0.5f).get()
                    .getLabel().setAlignment(Align.left);
            }).left().row();
        }
        if(n == 0) list.add("[#7dff9a]всё в порядке — проблем не найдено").left().pad(6f).row();
        list.pack();
    }

    /** Обновление списка раз в ~0.5с, если окно открыто. */
    public static void updateWindow(){
        if(listRef == null || !QolWindows.isOpen("alerts")) return;
        int sig = problems.size * 31 + (filter == null ? 0 : filter.ordinal());
        if(sig == lastSig) return;
        lastSig = sig;
        rebuildList();
    }
}
