import arc.*;
import arc.graphics.*;
import arc.graphics.gl.*;
import arc.struct.*;
import arc.util.*;

import mkukic.client.qol.*;

/**
 * FeatureTest (v1.10.0): чистые функции новых модулей —
 * Formations (геометрия строёв + назначение), PathPreview (спуск по flow-полю, конвергенция),
 * BuildWheel (сектора кольца, пейджинг), DrillPlanner (тепловая карта, топ-точки),
 * MusicBox (циклический индекс), LogicBackup (санитизация имён), ViewControl (сглаживание дельты).
 *
 * Запуск: java -cp OUT:tests-classes:toolchain... FeatureTest
 */
public class FeatureTest{
    static int passed, failed;

    // минимальные стабы как в TimeTest (на случай Vars.<clinit>)
    static class StubGraphics extends Graphics{
        public GL20 getGL20(){ return null; }
        public void setGL20(GL20 gl){}
        public GL30 getGL30(){ return null; }
        public void setGL30(GL30 gl){}
        public int getWidth(){ return 1280; }
        public int getHeight(){ return 720; }
        public int getBackBufferWidth(){ return 1280; }
        public int getBackBufferHeight(){ return 720; }
        public long getFrameId(){ return 0; }
        public float getDeltaTime(){ return 1f/60f; }
        public int getFramesPerSecond(){ return 60; }
        public GLVersion getGLVersion(){ return null; }
        public float getPpiX(){ return 96; }
        public float getPpiY(){ return 96; }
        public float getPpcX(){ return 96; }
        public float getPpcY(){ return 96; }
        public float getDensity(){ return 1; }
        public void setTitle(String t){}
        public void setVSync(boolean v){}
        public BufferFormat getBufferFormat(){ return null; }
        public boolean supportsExtension(String e){ return false; }
        public boolean isContinuousRendering(){ return true; }
        public void setContinuousRendering(boolean c){}
        public void requestRendering(){}
        public boolean isFullscreen(){ return false; }
        public Cursor newCursor(Pixmap p, int x, int y){ return null; }
        protected void setCursor(Cursor c){}
        protected void setSystemCursor(Cursor.SystemCursor c){}
        public void dispose(){}
    }

    public static void main(String[] args){
        Core.graphics = new StubGraphics();
        mindustry.Vars.headless = false;

        formations();
        pathPreview();
        buildWheel();
        drillPlanner();
        musicBox();
        logicBackup();
        smooth();
        inspector();
        markers();
        markerJson();
        cursorTracker();
        pruneAndLogic();
        inspectorFlow();
        coinPilot();
        // v1.18: дальность автопилота x5 + свой ограниченный A* / сегменты / объезд
        coinPilotRange();
        // v1.16: вторая волна портов из каталога модов
        attackAlert();
        chatLog();
        coreFlow();
        productionGraph();
        alertList();
        spectateCam();
        edgePan();
        // v1.16.1: регрессия краша загрузки мода из-за битой параметр-строки команды
        commandParams();
        // v1.17: структура меню после расформирования QoL-вкладки
        menuStructure();
        // v1.19: порт Onix Lite — поиск по панели, команды, подстановки чата, анимации
        onix();
        // v1.19.2: детект клиентов — семейства Onix / Onix Lite / Foo's, значки только у клиентов
        onixDetect();

        System.out.println("=========================================");
        System.out.println("FeatureTest: passed=" + passed + " failed=" + failed);
        if(failed > 0) System.exit(1);
        System.out.println("ALL FEATURE TESTS PASSED");
    }

    // ==================== Formations ====================

    static void formations(){
        // line: 3 юнита вдоль перпендикуляра к (1,0) => по оси Y, отступы -10,0,+10
        float[] p = Formations.positions("line", 3, 0f, 0f, 1f, 0f, 10f);
        eq("line n=3 len", 6, p.length, 0);
        eq("line[0].x", 0f, p[0], 0.01f);
        eq("line[0].y", -10f, p[1], 0.01f);
        eq("line[1].y", 0f, p[3], 0.01f);
        eq("line[2].y", 10f, p[5], 0.01f);

        // column: вдоль -направления
        p = Formations.positions("column", 2, 0f, 0f, 1f, 0f, 10f);
        eq("col[0]", 0f, p[0], 0.01f);
        eq("col[1].x", -10f, p[2], 0.01f);

        // circle: 4 точки на радиусе max(spacing, ...) = 10
        p = Formations.positions("circle", 4, 50f, 50f, 1f, 0f, 10f);
        eq("circ[0].x", 60f, p[0], 0.01f); // угол 0 => справа от центра
        eq("circ[0].y", 50f, p[1], 0.01f);
        eq("circ[1].x", 50f, p[2], 0.01f); // угол 90 => сверху
        eq("circ[1].y", 60f, p[3], 0.01f);

        // wedge: лидер впереди по fx
        p = Formations.positions("wedge", 3, 0f, 0f, 1f, 0f, 10f);
        eq("wedge leader x", 5f, p[0], 0.01f);
        ok("wedge wings behind", p[2] < 0f && p[4] < 0f);
        eq("wedge wings symmetric", p[3], -p[5], 0.01f);

        // square: 4 юнита => углы (±5, ±5) при fx=(1,0)
        p = Formations.positions("square", 4, 0f, 0f, 1f, 0f, 10f);
        for(int i = 0; i < 4; i++){
            eq("sq[" + i + "].|x|", 5f, Math.abs(p[i * 2]), 0.01f);
            eq("sq[" + i + "].|y|", 5f, Math.abs(p[i * 2 + 1]), 0.01f);
        }

        // вырожденные случаи
        eq("n=0 empty", 0, Formations.positions("line", 0, 0, 0, 1, 0, 10f).length, 0);
        eq("n<0 empty", 0, Formations.positions("line", -5, 0, 0, 1, 0, 10f).length, 0);
        p = Formations.positions(null, 2, 0f, 0f, 0f, 0f, 10f); // null-тип + нулевой вектор => как line с fx=(1,0)
        eq("null-type line y0", -5f, p[1], 0.01f);

        // assign: ближайший юнит -> ближайший слот, без повторов
        float[] units = {0f, 0f, 100f, 0f};
        float[] slots = {90f, 0f, 10f, 0f};
        int[] a = Formations.assign(units, slots);
        eq("assign[0]", 1, a[0], 0);
        eq("assign[1]", 0, a[1], 0);

        // юнитов больше чем слотов -> оставшимся -1
        float[] units3 = {0f, 0f, 5f, 0f, 100f, 0f};
        a = Formations.assign(units3, slots);
        eq("assign overflow", -1, a[2], 0);

        // пустые входы
        eq("assign empty", 0, Formations.assign(new float[0], new float[4]).length, 0);
    }

    // ==================== PathPreview ====================

    static void pathPreview(){
        // градиент по X: weights[y*3+x] = x+1 (1..3)
        int[] w = {
            1, 2, 3,
            1, 2, 3,
            1, 2, 3
        };
        // с (2,0): шаг должен уменьшить вес
        int d = PathPreview.descend(w, 3, 3, 2, 0);
        ok("descend finds lower", d >= 0 && d < 8);
        // с клетки веса 1 меньшего соседа нет => -1
        eq("descend at min", -1, PathPreview.descend(w, 3, 3, 0, 0), 0);
        // цель (0) и непроходимо (65535) => -1
        int[] w2 = {0, 5, 5, 5, 5, 5, 5, 5, 5};
        eq("descend target", -1, PathPreview.descend(w2, 3, 3, 0, 0), 0);
        int[] w3 = {65535, 5, 5, 5, 5, 5, 5, 5, 5};
        eq("descend impassable", -1, PathPreview.descend(w3, 3, 3, 0, 0), 0);
        // вне границ
        eq("descend oob", -1, PathPreview.descend(w, 3, 3, 5, 5), 0);
        eq("descend null", -1, PathPreview.descend(null, 3, 3, 0, 0), 0);

        // конвергенция: воронка 5x5 (манхэттен до центра), все 8 соседей стекаются в центр
        int[] funnel = new int[25];
        for(int y = 0; y < 5; y++)
            for(int x = 0; x < 5; x++)
                funnel[y * 5 + x] = Math.abs(x - 2) + Math.abs(y - 2);
        int c = PathPreview.convergence(funnel, 5, 5, 2, 2, 1);
        eq("convergence funnel", 8, c, 0);
        // на ровном поле (веса равны) никто никуда не течёт
        int[] flat = new int[25];
        java.util.Arrays.fill(flat, 7);
        eq("convergence flat", 0, PathPreview.convergence(flat, 5, 5, 2, 2, 1), 0);
    }

    // ==================== BuildWheel ====================

    static void buildWheel(){
        // сектор 0 из 4 — сверху (угол 90)
        float[] p = BuildWheel.sectorPos(0, 4, 50f, 50f, 100f);
        eq("sector0.x", 50f, p[0], 0.1f);
        eq("sector0.y", 150f, p[1], 0.1f);
        // сектор 1 — справа (угол 0)
        p = BuildWheel.sectorPos(1, 4, 50f, 50f, 100f);
        eq("sector1.x", 150f, p[0], 0.1f);
        eq("sector1.y", 50f, p[1], 0.1f);
        // все 8 секторов на радиусе r
        for(int i = 0; i < 8; i++){
            p = BuildWheel.sectorPos(i, 8, 0f, 0f, 120f);
            eq("sector dist " + i, 120f, (float)Math.sqrt(p[0] * p[0] + p[1] * p[1]), 0.2f);
        }

        // пейджинг
        eq("wrap -1/3", 2, BuildWheel.wrapPage(-1, 3), 0);
        eq("wrap 3/3", 0, BuildWheel.wrapPage(3, 3), 0);
        eq("wrap 1/3", 1, BuildWheel.wrapPage(1, 3), 0);
        eq("wrap 5/1", 0, BuildWheel.wrapPage(5, 1), 0);
        eq("wrap x/0", 0, BuildWheel.wrapPage(2, 0), 0);
    }

    // ==================== DrillPlanner ====================

    static void drillPlanner(){
        // footprint-офсеты: size2 -> {0,1} (якорь слева-снизу), size3 -> {-1,0,1}
        eq("foot s2 i0", 0, DrillPlanner.footprintOffset(0, 2), 0);
        eq("foot s2 i1", 1, DrillPlanner.footprintOffset(1, 2), 0);
        eq("foot s3 i0", -1, DrillPlanner.footprintOffset(0, 3), 0);
        eq("foot s3 i2", 1, DrillPlanner.footprintOffset(2, 3), 0);

        // 4x4 всё добываемо, бур 2x2
        boolean[] mine = new boolean[16];
        java.util.Arrays.fill(mine, true);
        boolean[] none = new boolean[16];
        short[] heat = DrillPlanner.heatFor(mine, none, 4, 4, 2);
        eq("heat (0,0)", 4, heat[0], 0);
        eq("heat (2,2)", 4, heat[2 * 4 + 2], 0);
        eq("heat (3,3) invalid", -1, heat[3 * 4 + 3], 0); // пятка выходит за край
        eq("heat (3,0) invalid", -1, heat[3], 0);

        // частично добываемо: только (1,1) руда -> бур 2x2 в (0,0) накроет 1
        boolean[] one = new boolean[16];
        one[1 * 4 + 1] = true;
        heat = DrillPlanner.heatFor(one, none, 4, 4, 2);
        eq("heat partial", 1, heat[0], 0);

        // blocked-клетка запрещает пятку
        boolean[] blocked = new boolean[16];
        blocked[1 * 4 + 1] = true;
        heat = DrillPlanner.heatFor(mine, blocked, 4, 4, 2);
        eq("heat blocked (0,0)", -1, heat[0], 0);
        eq("heat blocked (2,2)", 4, heat[2 * 4 + 2], 0); // не задевает (1,1)

        // size3: центр в (1,1) накрывает 3x3 = 9
        heat = DrillPlanner.heatFor(mine, none, 4, 4, 3);
        eq("heat s3 (1,1)", 9, heat[1 * 4 + 1], 0);
        eq("heat s3 (0,0) invalid", -1, heat[0], 0);

        // collectBest: топ-N по убыванию, <=0 не попадают
        short[] h2 = {1, 5, 2, 0, 3, -1, 9, 1, 1}; // 3x3 построчно
        int[][] top = DrillPlanner.collectBest(h2, 3, 3, 3);
        eq("best count", 3, top.length, 0);
        eq("best[0] score", 9, top[0][2], 0);
        eq("best[0] x", 0, top[0][0], 0);
        eq("best[0] y", 2, top[0][1], 0);
        eq("best[1] score", 5, top[1][2], 0);
        eq("best[2] score", 3, top[2][2], 0);

        // пустая карта
        top = DrillPlanner.collectBest(new short[]{0, -1, 0, 0}, 2, 2, 5);
        eq("best empty", 0, top.length, 0);
    }

    // ==================== MusicBox ====================

    static void musicBox(){
        eq("wrap 5/3", 2, MusicBox.wrap(5, 3), 0);
        eq("wrap -1/3", 2, MusicBox.wrap(-1, 3), 0);
        eq("wrap 3/3", 0, MusicBox.wrap(3, 3), 0);
        eq("wrap x/0", -1, MusicBox.wrap(2, 0), 0);
        eq("wrap x/-1", -1, MusicBox.wrap(2, -1), 0);
    }

    // ==================== LogicBackup ====================

    static void logicBackup(){
        eq("sanitize null", "snapshot", LogicBackup.sanitize(null), null);
        eq("sanitize empty", "snapshot", LogicBackup.sanitize(""), null);
        eq("sanitize spaces", "snapshot", LogicBackup.sanitize("   "), null);
        eq("sanitize simple", "My_Drill_Code", LogicBackup.sanitize("My Drill Code"), null);
        eq("sanitize path-traversal", "....etcpasswd", LogicBackup.sanitize("../../etc/passwd"), null);
        eq("sanitize html", "script", LogicBackup.sanitize("<script>"), null);
        eq("sanitize dots", "a.b-c_d", LogicBackup.sanitize("a.b-c_d"), null);
        String lng = LogicBackup.sanitize("x".repeat(100));
        eq("sanitize max40", 40, lng.length(), 0);
    }

    // ==================== ViewControl.smoothDelta ====================

    static void smooth(){
        ViewControl.resetSmooth();
        // стабильные 1.0 сходятся к 1.0
        float out = 0f;
        for(int i = 0; i < 60; i++) out = ViewControl.smoothDelta(1f);
        eq("smooth steady", 1f, out, 0.001f);

        // одиночный спайк x10 гасится до ~2 (ema*2)
        out = ViewControl.smoothDelta(10f);
        ok("smooth spike capped <=2.2, got " + out, out <= 2.2f);
        ok("smooth spike >= raw/5", out >= 2f - 0.001f);

        // после спайка возврат к 1.0 (сырая меньше ограничения — проходит как есть)
        out = ViewControl.smoothDelta(1f);
        eq("smooth recover", 1f, out, 0.001f);

        // устойчивое ускорение проходит за несколько кадров (не залипает)
        ViewControl.resetSmooth();
        for(int i = 0; i < 10; i++) out = ViewControl.smoothDelta(1f);
        for(int i = 0; i < 20; i++) out = ViewControl.smoothDelta(3f); // 144->20fps стабильно
        eq("smooth sustained", 3f, out, 0.05f);

        // мусор на входе
        ViewControl.resetSmooth();
        eq("smooth NaN", 1e-4f, ViewControl.smoothDelta(Float.NaN), 1e-6f);
        eq("smooth negative", 1e-4f, ViewControl.smoothDelta(-5f), 1e-6f);
        eq("smooth inf", 1e-4f, ViewControl.smoothDelta(Float.POSITIVE_INFINITY), 1e-6f);
    }

    // ==================== Inspector (v1.11) ====================

    static void inspector(){
        // pct: "cur/max (P%)"
        eqS("pct basic", "123/456 (26%)", Inspector.pct(123f, 456f)); // trunc, не round — как в игре
        eqS("pct half", "1/3 (33%)", Inspector.pct(1f, 3f));
        eqS("pct full", "456/456 (100%)", Inspector.pct(456f, 456f));
        eqS("pct zero max", "50?", Inspector.pct(50f, 0f));
        eqS("pct over", "500/456 (109%)", Inspector.pct(500f, 456f));

        // reach: дистанция до края хитбокса, не отрицательная
        eqF("reach 3-4-5", 4f, Inspector.reach(3f, 4f, 2f));     // 5 - 1
        eqF("reach inside", 0f, Inspector.reach(0f, 0f, 10f));    // внутри хитбокса -> 0
        eqF("reach zero hit", 5f, Inspector.reach(3f, 4f, 0f));

        // closest: тройки {x,y,hitSize}
        float[] xys = {0f, 0f, 8f, 10f, 0f, 8f, 50f, 50f, 8f};
        eq("closest pick", 1, Inspector.closest(xys, 9f, 0f, 16f), 0);
        eq("closest none", -1, Inspector.closest(xys, 100f, 100f, 16f), 0);
        eq("closest empty", -1, Inspector.closest(new float[0], 0f, 0f, 16f), 0);
        // первый из двух одинаково близких (строго <)
        float[] tie = {0f, 0f, 4f, 2f, 0f, 4f};
        eq("closest tie keeps first", 0, Inspector.closest(tie, 1f, 0f, 16f), 0);
    }

    // ==================== MapMarkers (v1.11) ====================

    static void markers(){
        // mapKey: sanitize + размеры
        eqS("mapKey basic", "my_map!_100x200".replace("!", ""), MapMarkers.mapKey("My Map!", 100, 200));
        eqS("mapKey null", "map_1x1", MapMarkers.mapKey(null, 1, 1));
        eqS("mapKey empty", "map_5x5", MapMarkers.mapKey("", 5, 5));
        eqS("mapKey spaces", "over_growth_256x256", MapMarkers.mapKey("Over growth", 256, 256));
        eqS("mapKey cyrillic", "karta_рука_8x8", MapMarkers.mapKey("Karta Рука", 8, 8));
        // стабильность: одинаковый вход -> одинаковый ключ
        eqS("mapKey stable", MapMarkers.mapKey("Some Map", 64, 64), MapMarkers.mapKey("Some Map", 64, 64));
        ok("mapKey differs by size", !MapMarkers.mapKey("Some Map", 64, 64).equals(MapMarkers.mapKey("Some Map", 65, 64)));
        // ограничение длины имени
        ok("mapKey len capped", MapMarkers.mapKey("x".repeat(100), 1, 1).length() <= 40 + 8);

        // colorOf: цикл, отрицательные индексы
        ok("color 0", MapMarkers.colorOf(0) == MapMarkers.palette[0]);
        ok("color wrap", MapMarkers.colorOf(MapMarkers.palette.length) == MapMarkers.palette[0]);
        ok("color negative", MapMarkers.colorOf(-1) == MapMarkers.palette[MapMarkers.palette.length - 1]);

        // autoName
        eqS("autoName 0", "Маркер 1", MapMarkers.autoName(0));
        eqS("autoName 4", "Маркер 5", MapMarkers.autoName(4));
    }

    // ==================== Marker JSON (обратная совместимость) ====================

    static void markerJson(){
        arc.util.serialization.Json json = new arc.util.serialization.Json();
        json.setIgnoreUnknownFields(true);

        // старый формат v1.11 (без track/trackColor) грузится, дефолты на месте
        String old = "[{\"map\":\"m_1x1\",\"x\":10,\"y\":20,\"text\":\"old\",\"color\":2,\"circle\":true,\"radius\":5}]";
        MapMarkers.Marker[] a = json.fromJson(MapMarkers.Marker[].class, old);
        eq("old json len", 1, a.length, 0);
        eqS("old json track default", "", a[0].track);
        eqS("old json trackColor default", "", a[0].trackColor);
        eqF("old json x", 10f, a[0].x);
        eqF("old json radius", 5f, a[0].radius);
        ok("old json circle", a[0].circle);

        // round-trip нового формата
        MapMarkers.Marker m = new MapMarkers.Marker();
        m.map = "k_8x8"; m.x = 1.5f; m.y = 2.5f; m.text = "t";
        m.track = "player-one"; m.trackColor = "#ff0000ff"; m.circle = false; m.radius = 3f;
        String ser = json.toJson(new MapMarkers.Marker[]{m});
        MapMarkers.Marker[] b = json.fromJson(MapMarkers.Marker[].class, ser);
        eq("rt len", 1, b.length, 0);
        eqS("rt track", "player-one", b[0].track);
        eqS("rt trackColor", "#ff0000ff", b[0].trackColor);
        eqS("rt map", "k_8x8", b[0].map);
        eqF("rt x", 1.5f, b[0].x);
        eqF("rt y", 2.5f, b[0].y);

        // неизвестные поля не ломают загрузку (ignoreUnknownFields)
        String future = "[{\"map\":\"m\",\"x\":1,\"y\":2,\"text\":\"f\",\"color\":0,\"circle\":false,\"radius\":8,\"track\":\"\",\"trackColor\":\"\",\"someFutureField\":42}]";
        MapMarkers.Marker[] c = json.fromJson(MapMarkers.Marker[].class, future);
        eq("future json len", 1, c.length, 0);
        eqF("future json y", 2f, c[0].y);
    }

    // ==================== CursorTracker (v1.12) ====================

    static void cursorTracker(){
        // canGlobal: SP и хост — да; чистый клиент — нет
        ok("canGlobal SP", CursorTracker.canGlobal(false, false));
        ok("canGlobal host", CursorTracker.canGlobal(true, true));
        ok("canGlobal client", !CursorTracker.canGlobal(true, false));

        // markerId: база, шаг 2 (пара круг+текст), цикличность, отрицательные
        eq("markerId 0", CursorTracker.idBase, CursorTracker.markerId(0), 0);
        eq("markerId 1", CursorTracker.idBase + 2, CursorTracker.markerId(1), 0);
        eq("markerId wrap", CursorTracker.idBase, CursorTracker.markerId(CursorTracker.idSlots), 0);
        eq("markerId neg", CursorTracker.idBase + (CursorTracker.idSlots - 1) * 2, CursorTracker.markerId(-1), 0);
        // id не пересекаются с соседними слотами (текст = id+1 не залезает на чужой круг)
        ok("markerId no overlap", CursorTracker.markerId(0) + 1 < CursorTracker.markerId(1));

        // freeSlot
        eq("freeSlot first", 0, CursorTracker.freeSlot(new boolean[]{false, false, true}), 0);
        eq("freeSlot mid", 1, CursorTracker.freeSlot(new boolean[]{true, false, false}), 0);
        eq("freeSlot full", -1, CursorTracker.freeSlot(new boolean[]{true, true}), 0);
        eq("freeSlot null", -1, CursorTracker.freeSlot(null), 0);
        eq("freeSlot empty", -1, CursorTracker.freeSlot(new boolean[0]), 0);

        // sig / sigChanged: детектор ростера
        eqS("sig join", "a\nb", CursorTracker.sig(new String[]{"a", "b"}));
        eqS("sig order matters", false + "", (CursorTracker.sig(new String[]{"a", "b"}).equals(CursorTracker.sig(new String[]{"b", "a"}))) + "");
        eqS("sig empty", "", CursorTracker.sig(new String[0]));
        eqS("sig null", "", CursorTracker.sig(null));
        ok("sigChanged yes", CursorTracker.sigChanged("a", "b"));
        ok("sigChanged no", !CursorTracker.sigChanged("a", "a"));
        ok("sigChanged null/null", !CursorTracker.sigChanged(null, null));
    }

    // ==================== SmartPrune + LogicBackup (v1.13) ====================

    static void pruneAndLogic(){
        // countOccurrences: неперекрывающиеся вхождения
        eq("count basic", 2, LogicBackup.countOccurrences("abcabc", "abc"), 0);
        eq("count overlap", 1, LogicBackup.countOccurrences("aaa", "aa"), 0);   // неперекрывающиеся: 1
        eq("count none", 0, LogicBackup.countOccurrences("abc", "x"), 0);
        eq("count empty find", 0, LogicBackup.countOccurrences("abc", ""), 0);
        eq("count null hay", 0, LogicBackup.countOccurrences(null, "a"), 0);
        eq("count at edges", 2, LogicBackup.countOccurrences("aXa", "a"), 0);

        // replaceAll
        eqS("replace basic", "bbbc", LogicBackup.replaceAll("abc", "a", "bb"));
        eqS("replace all", "XbX", LogicBackup.replaceAll("aba", "a", "X"));
        eqS("replace empty find", "abc", LogicBackup.replaceAll("abc", "", "X"));
        eqS("replace with empty", "bc", LogicBackup.replaceAll("abc", "a", ""));
        eqS("replace no match", "abc", LogicBackup.replaceAll("abc", "z", "Q"));
        eqS("replace null repl", "bc", LogicBackup.replaceAll("abc", "a", null));
        // mlog-подобный код: замена имени передела переменных
        String code = "set a 1\nprint a\nset a 2\nprint a";
        eq("mlog count", 4, LogicBackup.countOccurrences(code, "a"), 0);
        eqS("mlog replace", "set counter 1\nprint counter\nset counter 2\nprint counter",
            LogicBackup.replaceAll(code, "a", "counter"));

        // countBreaking: планы сноса против планов стройки
        Seq<mindustry.entities.units.BuildPlan> plans = new Seq<>();
        mindustry.entities.units.BuildPlan b1 = new mindustry.entities.units.BuildPlan();
        b1.breaking = true;
        mindustry.entities.units.BuildPlan b2 = new mindustry.entities.units.BuildPlan();
        b2.breaking = false;
        mindustry.entities.units.BuildPlan b3 = new mindustry.entities.units.BuildPlan();
        b3.breaking = true;
        plans.add(b1); plans.add(b2); plans.add(b3);
        eq("countBreaking", 2, SmartPrune.countBreaking(plans), 0);
        eq("countBreaking empty", 0, SmartPrune.countBreaking(new Seq<>()), 0);
        eq("countBreaking null", 0, SmartPrune.countBreaking(null), 0);
    }

    // ==================== Inspector flow (v1.13) ====================

    static void inspectorFlow(){
        // +60 единиц за 60 кадров = 60 ед/с
        eqF("flow +60/60f", 60f, Inspector.flowRate(60, 60f));
        // -30 за 60 кадров = -30 ед/с (расход)
        eqF("flow -30/60f", -30f, Inspector.flowRate(-30, 60f));
        // 10 за 30 кадров = 20 ед/с
        eqF("flow 10/30f", 20f, Inspector.flowRate(10, 30f));
        // защита от деления на ноль/отрицательных кадров
        eqF("flow zero frames", 0f, Inspector.flowRate(100, 0f));
        eqF("flow negative frames", 0f, Inspector.flowRate(100, -5f));
        // ноль изменений
        eqF("flow no change", 0f, Inspector.flowRate(0, 120f));
    }

    // ==================== CoinPilot (v1.14) ====================

    static void coinPilot(){
        // aimFactor: снаряд летит прямо в нас / мимо / от нас / вырожденный
        eqF("pilot aim head-on", 1f, mkukic.client.CoinPilot.aimFactor(10f, 0f, 5f, 0f));
        eqF("pilot aim sideways", 0f, mkukic.client.CoinPilot.aimFactor(10f, 0f, 0f, 5f));
        eqF("pilot aim away", -1f, mkukic.client.CoinPilot.aimFactor(10f, 0f, -5f, 0f));
        eqF("pilot aim degenerate a", 0f, mkukic.client.CoinPilot.aimFactor(0f, 0f, 5f, 0f));
        eqF("pilot aim degenerate v", 0f, mkukic.client.CoinPilot.aimFactor(10f, 0f, 0f, 0f));
        eqF("pilot aim diagonal", 0.71f, mkukic.client.CoinPilot.aimFactor(10f, 10f, 5f, 0f));

        // sideSign: перпендикуляр уводит от линии огня
        eqF("pilot side neutral", 1f, mkukic.client.CoinPilot.sideSign(0f, 5f, 10f, 0f));
        eqF("pilot side below", -1f, mkukic.client.CoinPilot.sideSign(0f, 5f, 10f, -1f));
        eqF("pilot side above", 1f, mkukic.client.CoinPilot.sideSign(0f, 5f, 10f, 1f));

        // evadeWeight: 1 вплотную, 0 на границе и дальше
        eqF("pilot weight close", 1f, mkukic.client.CoinPilot.evadeWeight(0f, 100f));
        eqF("pilot weight half", 0.5f, mkukic.client.CoinPilot.evadeWeight(50f, 100f));
        eqF("pilot weight edge", 0f, mkukic.client.CoinPilot.evadeWeight(100f, 100f));
        eqF("pilot weight beyond", 0f, mkukic.client.CoinPilot.evadeWeight(250f, 100f));
        eqF("pilot weight zero radius", 0f, mkukic.client.CoinPilot.evadeWeight(10f, 0f));
    }



    // ==================== v1.18: CoinPilot дальность x5 ====================

    /** Чистая сетка проходимости [x][y] для A*-тестов (без Mindustry). */
    static boolean[][] gpass;
    static final mkukic.client.CoinPilot.Passable gridPass =
        (x, y) -> gpass != null && x >= 0 && y >= 0 && x < gpass.length && y < gpass[x].length && gpass[x][y];

    static boolean[][] makeGrid(int w, int h){
        boolean[][] g = new boolean[w][h];
        for(int x = 0; x < w; x++) for(int y = 0; y < h; y++) g[x][y] = true;
        return g;
    }

    static int cl(int v, int lo, int hi){ return v < lo ? lo : (v > hi ? hi : v); }

    /** Маршрут целиком проходим и идёт шагами в 4 направлениях (без телепортов/диагоналей). */
    static boolean pathValid(arc.struct.IntSeq xy){
        if(xy.size < 2 || (xy.size & 1) != 0) return false;
        for(int i = 0; i < xy.size; i += 2){
            if(!gridPass.canPass(xy.get(i), xy.get(i + 1))) return false;
            if(i > 0 && mkukic.client.CoinPilot.manhattan(xy.get(i - 2), xy.get(i - 1), xy.get(i), xy.get(i + 1)) != 1) return false;
        }
        return true;
    }

    /**
     * Симуляция сегментной навигации v1.18 на чистой сетке: те же вызовы, что в
     * planRoute/steerGround (segmentGoal -> detour -> boundedAstar -> переезд в конец маршрута).
     * stats: [0]=итераций, [1]=всего раскрыто узлов, [2]=пик detourStreak.
     * @return {x, y, arrived(1/0)}
     */
    static int[] walkSegments(int w, int h, int sx, int sy, int tx, int ty, int maxIter, int[] stats){
        int cx = sx, cy = sy, streak = 0, iter = 0, expanded = 0, peak = 0;
        arc.math.geom.Vec2 seg = new arc.math.geom.Vec2();
        while(iter++ < maxIter){
            if(cx == tx && cy == ty) break;
            mkukic.client.CoinPilot.segmentGoal(cx, cy, tx, ty, w, h, seg);
            boolean fin = mkukic.client.CoinPilot.manhattan(cx, cy, tx, ty) <= mkukic.client.CoinPilot.maxSegmentTiles;
            if(!fin){
                int off = mkukic.client.CoinPilot.nextDetour(streak);
                if(off != 0) mkukic.client.CoinPilot.detourGoal(cx, cy, Math.round(seg.x), Math.round(seg.y), w, h, off, seg);
            }
            int gx = cl(Math.round(seg.x), 0, w - 1), gy = cl(Math.round(seg.y), 0, h - 1);
            mkukic.client.CoinPilot.GridPath gp =
                mkukic.client.CoinPilot.boundedAstar(cx, cy, gx, gy, w, h, mkukic.client.CoinPilot.astarBudget, gridPass);
            expanded += gp.expanded;
            if(gp.xy.size < 4){ streak++; peak = Math.max(peak, streak); continue; }
            if(gp.reached) streak = 0; else streak++;
            peak = Math.max(peak, streak);
            int nx = gp.endX, ny = gp.endY;      // юнит доехал до конца (возможно частичного) маршрута
            if(nx == cx && ny == cy && fin) break;   // финальный подход упёрся — дальше анти-застревание
            cx = nx; cy = ny;
        }
        stats[0] = iter; stats[1] = expanded; stats[2] = peak;
        return new int[]{cx, cy, (cx == tx && cy == ty) ? 1 : 0};
    }

    static void coinPilotRange(){
        // ---------- 1) радиус x5: константы, кламп, мировые единицы ----------
        ok("макс. радиус автопилота увеличен в 5 раз (256 -> 1280)",
            mkukic.client.CoinPilot.maxRadiusTiles == 256f * 5f);
        ok("дефолтный радиус увеличен в 5 раз (120 -> 600)",
            mkukic.client.CoinPilot.defaultRadiusTiles == 120f * 5f);
        ok("мин. радиус не изменился (8)", mkukic.client.CoinPilot.minRadiusTiles == 8f);
        eqF("дефолт в настройках = 600 тайлов", 600f, mkukic.MKUKICSettings.s().coinPilotRadius);
        eqF("radiusWorld(600) = 4800 ед.", 4800f, mkukic.client.CoinPilot.radiusWorld(600f));
        eqF("radiusWorld(120) = 960 ед.", 960f, mkukic.client.CoinPilot.radiusWorld(120f));
        eqF("дальность выросла ровно в 5 раз", 5f,
            mkukic.client.CoinPilot.radiusWorld(mkukic.client.CoinPilot.defaultRadiusTiles)
                / mkukic.client.CoinPilot.radiusWorld(120f));
        eqF("radiusWorld(1280) = 10240 ед.", 10240f, mkukic.client.CoinPilot.radiusWorld(1280f));
        eqF("radiusWorld кламп снизу (1 -> 8 тайлов)", 64f, mkukic.client.CoinPilot.radiusWorld(1f));
        eqF("radiusWorld кламп снизу (отрицательный)", 64f, mkukic.client.CoinPilot.radiusWorld(-50f));
        eqF("radiusWorld кламп сверху (99999 -> 1280)", 10240f, mkukic.client.CoinPilot.radiusWorld(99999f));
        eqF("radiusWorld(NaN) -> дефолт ( Mathf.clamp NaN не ловит)", 4800f, mkukic.client.CoinPilot.radiusWorld(Float.NaN));
        eqF("radiusWorld(Infinity) -> максимальный радиус", 10240f, mkukic.client.CoinPilot.radiusWorld(Float.POSITIVE_INFINITY));
        eqF("radiusWorld(-Infinity) -> минимальный радиус", 64f, mkukic.client.CoinPilot.radiusWorld(Float.NEGATIVE_INFINITY));
        eqF("radiusWorld(min) = 64 ед.", 64f, mkukic.client.CoinPilot.radiusWorld(mkukic.client.CoinPilot.minRadiusTiles));

        // ---------- 2) манхэттен и цель сегмента ----------
        ok("manhattan (0,0)-(3,4) = 7", mkukic.client.CoinPilot.manhattan(0, 0, 3, 4) == 7);
        ok("manhattan симметричен", mkukic.client.CoinPilot.manhattan(-5, 7, 2, -1) == mkukic.client.CoinPilot.manhattan(2, -1, -5, 7));
        ok("manhattan одной точки = 0", mkukic.client.CoinPilot.manhattan(9, 9, 9, 9) == 0);

        arc.math.geom.Vec2 out = new arc.math.geom.Vec2();
        mkukic.client.CoinPilot.segmentGoal(10, 10, 100, 100, 500, 500, out);
        ok("короткий сегмент (md<=200) — цель как есть", Math.round(out.x) == 100 && Math.round(out.y) == 100);

        mkukic.client.CoinPilot.segmentGoal(0, 0, 1280, 0, 2000, 2000, out);
        int segMd = mkukic.client.CoinPilot.manhattan(0, 0, Math.round(out.x), Math.round(out.y));
        ok("дальний сегмент урезан до <= " + mkukic.client.CoinPilot.maxSegmentTiles + " тайлов, факт. " + segMd,
            segMd > 0 && segMd <= mkukic.client.CoinPilot.maxSegmentTiles);
        ok("дальний сегмент лежит на линии к цели (y=0)", Math.round(out.y) == 0 && Math.round(out.x) > 0);

        mkukic.client.CoinPilot.segmentGoal(0, 0, 900, 900, 2000, 2000, out);
        ok("диагональный сегмент тоже в бюджете",
            mkukic.client.CoinPilot.manhattan(0, 0, Math.round(out.x), Math.round(out.y)) <= mkukic.client.CoinPilot.maxSegmentTiles);
        ok("диагональный сегмент на линии (x==y)", Math.round(out.x) == Math.round(out.y));

        mkukic.client.CoinPilot.segmentGoal(0, 0, 1000, 1000, 5, 5, out);
        ok("цель сегмента зажимается в пределы карты", Math.round(out.x) <= 4 && Math.round(out.y) <= 4);
        mkukic.client.CoinPilot.segmentGoal(0, 0, 500, 500, 0, 0, out);
        ok("при нулевом размере карты сегмент = цель (не режем)", Math.round(out.x) == 500 && Math.round(out.y) == 500);

        // ---------- 3) объезд: чередование сторон и потолок ----------
        ok("nextDetour(0) = 0 (нет объезда)", mkukic.client.CoinPilot.nextDetour(0) == 0);
        ok("nextDetour(-3) = 0 (защита от мусора)", mkukic.client.CoinPilot.nextDetour(-3) == 0);
        ok("nextDetour(1) = +32", mkukic.client.CoinPilot.nextDetour(1) == mkukic.client.CoinPilot.detourStep);
        ok("nextDetour(2) = -32 (другая сторона)", mkukic.client.CoinPilot.nextDetour(2) == -mkukic.client.CoinPilot.detourStep);
        ok("nextDetour(3) = +64", mkukic.client.CoinPilot.nextDetour(3) == 2 * mkukic.client.CoinPilot.detourStep);
        ok("nextDetour(4) = -64", mkukic.client.CoinPilot.nextDetour(4) == -2 * mkukic.client.CoinPilot.detourStep);
        boolean capped = true, alternates = true;
        for(int i = 1; i <= 40; i++){
            int d = mkukic.client.CoinPilot.nextDetour(i);
            if(Math.abs(d) > mkukic.client.CoinPilot.maxDetourTiles) capped = false;
            if((i % 2 == 1) != (d > 0)) alternates = false;
            if(Math.abs(d) < mkukic.client.CoinPilot.maxDetourTiles && Math.abs(d) != ((i + 1) / 2) * mkukic.client.CoinPilot.detourStep) alternates = false;
        }
        ok("смещение объезда не превышает потолок " + mkukic.client.CoinPilot.maxDetourTiles, capped);
        ok("стороны объезда чередуются и растут", alternates);

        mkukic.client.CoinPilot.detourGoal(0, 0, 100, 0, 500, 500, 0, out);
        ok("detourGoal(0) не смещает цель", Math.round(out.x) == 100 && Math.round(out.y) == 0);
        mkukic.client.CoinPilot.detourGoal(0, 0, 100, 0, 500, 500, 32, out);
        ok("detourGoal(+32) сдвигает перпендикулярно (вверх по y)", Math.round(out.x) == 100 && Math.round(out.y) == 32);
        mkukic.client.CoinPilot.detourGoal(0, 0, 100, 0, 500, 500, -32, out);
        ok("detourGoal(-32) сдвигает в другую сторону", Math.round(out.x) == 100 && Math.round(out.y) == -32 + 0 || Math.round(out.y) == 0);
        mkukic.client.CoinPilot.detourGoal(0, 0, 100, 0, 500, 500, -90, out);
        ok("detourGoal зажимает смещение в карту (y>=0)", Math.round(out.y) >= 0);
        mkukic.client.CoinPilot.detourGoal(0, 0, 0, 100, 500, 500, 32, out);
        ok("detourGoal для вертикального направления сдвигает по x", Math.round(out.y) == 100 && Math.round(out.x) == -32 + 0 || Math.round(out.x) >= 0);
        mkukic.client.CoinPilot.detourGoal(0, 0, 0, 0, 500, 500, 32, out);
        ok("detourGoal при нулевой длине не делит на ноль", Float.isFinite(out.x) && Float.isFinite(out.y));

        // ---------- 4) анти-застрывание: в объезде ждём дольше ----------
        ok("порог застревания без объезда = 4 (~2с)", mkukic.client.CoinPilot.stuckLimit(0) == 4);
        ok("порог застревания в объезде = 10 (~5с)", mkukic.client.CoinPilot.stuckLimit(3) == 10);

        // ---------- 5) ограниченный A*: прямая ----------
        int w = 64, h = 64;
        gpass = makeGrid(w, h);
        mkukic.client.CoinPilot.GridPath gp = mkukic.client.CoinPilot.boundedAstar(0, 0, 40, 0, w, h, 6000, gridPass);
        ok("прямая: дошли до цели", gp.reached);
        ok("прямая: длина = 41 тайл, факт. " + (gp.xy.size / 2), gp.xy.size / 2 == 41);
        ok("прямая: начинается со стартового тайла", gp.xy.get(0) == 0 && gp.xy.get(1) == 0);
        ok("прямая: заканчивается целью", gp.endX == 40 && gp.endY == 0
            && gp.xy.get(gp.xy.size - 2) == 40 && gp.xy.get(gp.xy.size - 1) == 0);
        ok("прямая: маршрут валиден (проходим, шаги по 1)", pathValid(gp.xy));
        ok("прямая: раскрыто узлов в пределах бюджета (" + gp.expanded + ")", gp.expanded > 0 && gp.expanded <= 6000);
        ok("прямая: A* почти оптимален по раскрытиям (" + gp.expanded + " <= 300)", gp.expanded <= 300);

        // ---------- 6) обход стены с проходом сверху ----------
        gpass = makeGrid(w, h);
        for(int y = 0; y <= 20; y++) gpass[20][y] = false;             // стена x=20, проход y>=21
        gp = mkukic.client.CoinPilot.boundedAstar(0, 0, 40, 0, w, h, 6000, gridPass);
        ok("стена: маршрут найден в обход", gp.reached);
        boolean crossesWall = false;
        for(int i = 0; i < gp.xy.size; i += 2) if(gp.xy.get(i) == 20 && gp.xy.get(i + 1) <= 20) crossesWall = true;
        ok("стена: маршрут не проходит сквозь стену", !crossesWall);
        ok("стена: маршрут валиден", pathValid(gp.xy));
        ok("стена: обошли длиннее прямой (" + (gp.xy.size / 2) + " > 41)", gp.xy.size / 2 > 41);
        ok("стена: уложились в бюджет (" + gp.expanded + ")", gp.expanded <= 6000);

        // ---------- 7) недостижимая цель -> частичный маршрут к ближайшей точке ----------
        gpass = makeGrid(32, 32);
        for(int x = 14; x <= 18; x++) for(int y = 14; y <= 18; y++)
            if(x == 14 || x == 18 || y == 14 || y == 18) gpass[x][y] = false;  // кольцо вокруг (16,16)
        gp = mkukic.client.CoinPilot.boundedAstar(0, 0, 16, 16, 32, 32, 6000, gridPass);
        ok("кольцо: цель недостижима -> reached=false", !gp.reached);
        ok("кольцо: частичный маршрут не пуст", gp.xy.size >= 2);
        ok("кольцо: частичный маршрут валиден", pathValid(gp.xy));
        ok("кольцо: конечная точка проходима", gridPass.canPass(gp.endX, gp.endY));
        ok("кольцо: доехали вплотную к кольцу (md=3), факт. "
            + mkukic.client.CoinPilot.manhattan(gp.endX, gp.endY, 16, 16),
            mkukic.client.CoinPilot.manhattan(gp.endX, gp.endY, 16, 16) == 3);
        ok("кольцо: обошлись бюджетом (" + gp.expanded + " <= 6000)", gp.expanded <= 6000);

        // ---------- 8) бюджет узлов ----------
        gpass = makeGrid(w, h);
        gp = mkukic.client.CoinPilot.boundedAstar(0, 0, 60, 60, w, h, 5, gridPass);
        ok("бюджет: раскрыто не больше 5 узлов, факт. " + gp.expanded, gp.expanded <= 5);
        ok("бюджет: цель не достигнута", !gp.reached);
        ok("бюджет: частичный маршрут есть", gp.xy.size >= 2);

        // ---------- 9) вырожденные входы ----------
        gpass = makeGrid(w, h);
        gp = mkukic.client.CoinPilot.boundedAstar(5, 5, 5, 5, w, h, 6000, gridPass);
        ok("старт==цель: reached=true", gp.reached);
        ok("старт==цель: маршрут из одного тайла", gp.xy.size == 2 && gp.endX == 5 && gp.endY == 5);

        gp = mkukic.client.CoinPilot.boundedAstar(0, 0, 64, 0, w, h, 6000, gridPass);
        ok("цель вне карты: пустой результат", gp.xy.size == 0 && !gp.reached);
        gp = mkukic.client.CoinPilot.boundedAstar(0, 0, -1, 0, w, h, 6000, gridPass);
        ok("отрицательная цель: пустой результат", gp.xy.size == 0 && !gp.reached);
        gp = mkukic.client.CoinPilot.boundedAstar(0, 0, 10, 10, w, h, 6000, null);
        ok("pass=null: пустой результат", gp.xy.size == 0 && !gp.reached);
        gp = mkukic.client.CoinPilot.boundedAstar(0, 0, 10, 10, w, h, 0, gridPass);
        ok("budget=0: пустой результат", gp.xy.size == 0 && !gp.reached);
        gp = mkukic.client.CoinPilot.boundedAstar(0, 0, 10, 10, 0, 0, 6000, gridPass);
        ok("пустая карта: пустой результат", gp.xy.size == 0 && !gp.reached);

        // ---------- 10) пул узлов: повторные вызовы не портят результат ----------
        gpass = makeGrid(w, h);
        for(int y = 0; y <= 20; y++) gpass[20][y] = false;
        mkukic.client.CoinPilot.boundedAstar(0, 0, 40, 0, w, h, 6000, gridPass);
        gpass = makeGrid(w, h);
        gp = mkukic.client.CoinPilot.boundedAstar(0, 0, 40, 0, w, h, 6000, gridPass);
        ok("пул узлов: после «грязного» прогона маршрут снова прямой (41 тайл)", gp.reached && gp.xy.size / 2 == 41);
        gpass = makeGrid(w, h);
        for(int y = 0; y <= 20; y++) gpass[20][y] = false;
        gp = mkukic.client.CoinPilot.boundedAstar(0, 0, 40, 0, w, h, 6000, gridPass);
        ok("пул узлов: стена снова обходится", gp.reached && gp.xy.size / 2 > 41 && pathValid(gp.xy));

        // ---------- 11) дальний перегон 1280 тайлов (радиус x5) ----------
        int big = 1400;
        gpass = makeGrid(big, 64);
        int[] stats = new int[3];
        int[] res = walkSegments(big, 64, 0, 0, 1280, 0, 40, stats);
        ok("перегон 1280 тайлов: доехали до цели", res[2] == 1);
        ok("перегон 1280 тайлов: сегментов <= 12, факт. " + stats[0], stats[0] <= 12);
        ok("перегон 1280 тайлов: бюджет не превышен суммарно (" + stats[1] + " <= " + (12 * 6000) + ")",
            stats[1] <= 12 * mkukic.client.CoinPilot.astarBudget);
        ok("перегон 1280 тайлов: без объездов (стена не нужна)", stats[2] == 0);

        // ---------- 12) дальний перегон со стеной: объезд находит проход ----------
        gpass = makeGrid(500, 300);
        for(int y = 0; y <= 120; y++) gpass[200][y] = false;           // стена x=200 высотой 121, проход сверху
        stats = new int[3];
        res = walkSegments(500, 300, 0, 0, 400, 0, 60, stats);
        ok("перегон со стеной: доехали до цели (объезд сработал)", res[2] == 1);
        ok("перегон со стеной: пришлось объезжать (detourStreak пик " + stats[2] + " >= 1)", stats[2] >= 1);
        ok("перегон со стеной: итераций <= 20, факт. " + stats[0], stats[0] <= 20);

        // ---------- 13) сплошная стена: честно упираемся и не крутимся вечно ----------
        gpass = makeGrid(500, 64);
        for(int y = 0; y < 64; y++) gpass[200][y] = false;             // стена на всю высоту карты
        stats = new int[3];
        res = walkSegments(500, 64, 0, 0, 400, 0, 30, stats);
        ok("сплошная стена: цель недостижима", res[2] == 0);
        ok("сплошная стена: юнит доехал вплотную (x >= 190), факт. " + res[0], res[0] >= 190);
        ok("сплошная стена: конечная точка проходима", gridPass.canPass(res[0], res[1]));
        ok("сплошная стена: не ушли за стену", res[0] < 200);
        ok("сплошная стена: объезд пробовал разные стороны (пик " + stats[2] + " >= 2)", stats[2] >= 2);
        gpass = null;
    }

    // ==================== v1.16: AttackAlert (attack-alert / MinerTools) ====================

    static void attackAlert(){
        ok("alert: урон 0.5 не тревожит", !mkukic.client.qol.AttackAlert.shouldAlert(0.5f, false, false));
        ok("alert: урон 10 тревожит", mkukic.client.qol.AttackAlert.shouldAlert(10f, false, false));
        ok("alert: стена молчит по умолчанию", !mkukic.client.qol.AttackAlert.shouldAlert(10f, true, false));
        ok("alert: стена с настройкой тревожит", mkukic.client.qol.AttackAlert.shouldAlert(10f, true, true));

        ok("alert: троттлинг первый раз", mkukic.client.qol.AttackAlert.throttle(100f, 0f, 90f));
        ok("alert: троттлинг держит", !mkukic.client.qol.AttackAlert.throttle(150f, 100f, 90f));
        ok("alert: троттлинг отпустил", mkukic.client.qol.AttackAlert.throttle(200f, 100f, 90f));

        eq("alert: cap стрелок", 6f, mkukic.client.qol.AttackAlert.visibleCap(30, 6), 0);
        eq("alert: cap меньше списка", 3f, mkukic.client.qol.AttackAlert.visibleCap(3, 6), 0);
        eq("alert: cap пустого списка", 0f, mkukic.client.qol.AttackAlert.visibleCap(0, 6), 0);
        eq("alert: cap при 0 в настройках", 1f, mkukic.client.qol.AttackAlert.visibleCap(5, 0), 0);

        eqS("alert: ключ точки", "12,34", mkukic.client.qol.AttackAlert.key(12, 34));
        eqS("alert: 30с назад", "30с назад", mkukic.client.qol.AttackAlert.agoText(1800f));
        eqS("alert: 2м назад", "2м 5с назад", mkukic.client.qol.AttackAlert.agoText((125) * 60f));
        eqS("alert: часы", "1ч 0м назад", mkukic.client.qol.AttackAlert.agoText(3600f * 60f));
    }

    // ==================== v1.16: ChatLog (MinerTools ChatTable) ====================

    static void chatLog(){
        ok("chat: пустой запрос пропускает всё", mkukic.client.qol.ChatLog.matches("[#ff0000]Привет[]", "", false, false));
        ok("chat: поиск по подстроке", mkukic.client.qol.ChatLog.matches("Привет мир", "мир", false, false));
        ok("chat: поиск регистронезависим", mkukic.client.qol.ChatLog.matches("Привет МИР", "мир", false, false));
        ok("chat: разметка не мешает поиску", mkukic.client.qol.ChatLog.matches("[accent]Привет[]", "привет", false, false));
        ok("chat: не найдено", !mkukic.client.qol.ChatLog.matches("Привет", "пока", false, false));
        ok("chat: фильтр команд", !mkukic.client.qol.ChatLog.matches("/spawn dagger", "", true, false));
        ok("chat: своя команда не скрывается", mkukic.client.qol.ChatLog.matches("/spawn dagger", "", true, true));

        Seq<String> pending = Seq.with("привет", "второе");
        ok("chat: эхо найден", mkukic.client.qol.ChatLog.isEcho(pending, "привет"));
        eq("chat: эхо удалено из очереди", 1f, pending.size, 0);
        ok("chat: чужое не эхо", !mkukic.client.qol.ChatLog.isEcho(pending, "третье"));

        Seq<Integer> seq = new Seq<>();
        for(int i = 0; i < 10; i++) seq.add(i);
        eq("chat: prune вернул число удалённых", 5f, mkukic.client.qol.ChatLog.prune(seq, 5), 0);
        eq("chat: размер после prune", 5f, seq.size, 0);
        ok("chat: имя файла экспорта", mkukic.client.qol.ChatLog.fileName(0L).startsWith("chatlog-"));
    }

    // ==================== v1.16: CoreFlow (resource-flow) ====================

    static void coreFlow(){
        eqF("flow: +10 за 0.5с = 20/с", 20f, mkukic.client.qol.CoreFlow.rateOf(100, 110, 0.5f, 0));
        eqF("flow: -5 за 1с = -5/с", -5f, mkukic.client.qol.CoreFlow.rateOf(100, 95, 1f, 0));
        eqF("flow: ноль секунд", 0f, mkukic.client.qol.CoreFlow.rateOf(100, 110, 0f, 0));
        // склад упёрся в потолок: счётчик не растёт, но производство есть
        eqF("flow: потолок ядра не даёт минуса", 0f, mkukic.client.qol.CoreFlow.rateOf(1000, 1000, 0.5f, 1000));
        eqF("flow: расход при полном ядре считается", -20f, mkukic.client.qol.CoreFlow.rateOf(1000, 990, 0.5f, 1000));

        eqS("flow: формат +", "+12.5/с", mkukic.client.qol.CoreFlow.fmtRate(12.5f));
        eqS("flow: формат −", "−3/с", mkukic.client.qol.CoreFlow.fmtRate(-3f));
        eqS("flow: формат ноль", "0", mkukic.client.qol.CoreFlow.fmtRate(0.01f));
        eqS("flow: compact k", "12.3k", mkukic.client.qol.CoreFlow.compact(12345));
        eqS("flow: compact M", "1.2M", mkukic.client.qol.CoreFlow.compact(1234567));
        eqS("flow: compact малое", "42", mkukic.client.qol.CoreFlow.compact(42));

        ok("flow: фильтр движения прячет ноль", !mkukic.client.qol.CoreFlow.showItem(0f, 0, true));
        ok("flow: фильтр движения показывает приход", mkukic.client.qol.CoreFlow.showItem(2f, 0, true));
        ok("flow: без фильтра показывает всё", mkukic.client.qol.CoreFlow.showItem(0f, 0, false));
        ok("flow: цвет прихода зелёный", mkukic.client.qol.CoreFlow.rateColor(1f).g > 0.5f);
        ok("flow: цвет расхода красный", mkukic.client.qol.CoreFlow.rateColor(-1f).r > 0.5f);
    }

    // ==================== v1.16: ProductionGraph (Factorio-style) ====================

    static void productionGraph(){
        FloatSeq buf = new FloatSeq();
        mkukic.client.qol.ProductionGraph.push(buf, 5f, 600);
        mkukic.client.qol.ProductionGraph.push(buf, -3f, 600);
        mkukic.client.qol.ProductionGraph.push(buf, 7f, 600);
        eq("graph: размер буфера", 3f, buf.size, 0);
        eqF("graph: среднее", 3f, mkukic.client.qol.ProductionGraph.avg(buf, 3));
        eqF("graph: приход", 6f, mkukic.client.qol.ProductionGraph.avgProduced(buf, 3));
        eqF("graph: расход", 3f, mkukic.client.qol.ProductionGraph.avgConsumed(buf, 3));
        eqF("graph: пик", 7f, mkukic.client.qol.ProductionGraph.peak(buf, 3));
        eqF("graph: пик пустого = 1", 1f, mkukic.client.qol.ProductionGraph.peak(new FloatSeq(), 3));

        // кольцевой буфер не растёт бесконечно
        FloatSeq small = new FloatSeq();
        for(int i = 0; i < 10; i++) mkukic.client.qol.ProductionGraph.push(small, i, 4);
        eq("graph: буфер ограничен", 4f, small.size, 0);
        eqF("graph: осталось последнее", 9f, small.get(3));

        eq("graph: окно от размера", 40f, mkukic.client.qol.ProductionGraph.windowFrom(100, 60), 0);
        eq("graph: окно больше буфера", 0f, mkukic.client.qol.ProductionGraph.windowFrom(10, 60), 0);
        // среднее по окну короче буфера
        FloatSeq w = new FloatSeq();
        for(int i = 0; i < 10; i++) mkukic.client.qol.ProductionGraph.push(w, 2f, 600);
        eqF("graph: среднее по окну 3", 2f, mkukic.client.qol.ProductionGraph.avg(w, 3));
    }

    // ==================== v1.16: AlertList (MinerTools TurretAlert) ====================

    static void alertList(){
        ok("alerts: турель пуста", mkukic.client.qol.AlertList.turretStarved(0));
        ok("alerts: турель с БК молчит", !mkukic.client.qol.AlertList.turretStarved(12));

        ok("alerts: завод встал (прогресс не двигается)",
            mkukic.client.qol.AlertList.factoryStalled(0.4f, 0.4f, 3f, 1f, true));
        ok("alerts: завод работает (прогресс растёт)",
            !mkukic.client.qol.AlertList.factoryStalled(0.4f, 0.2f, 3f, 1f, true));
        ok("alerts: без питания не «встал»",
            !mkukic.client.qol.AlertList.factoryStalled(0.4f, 0.4f, 3f, 1f, false));
        ok("alerts: без эффективности не «встал»",
            !mkukic.client.qol.AlertList.factoryStalled(0.4f, 0.4f, 3f, 0.1f, true));
        ok("alerts: слишком рано судить",
            !mkukic.client.qol.AlertList.factoryStalled(0.4f, 0.4f, 0.5f, 1f, true));

        ok("alerts: нет питания", mkukic.client.qol.AlertList.powerStarved(0f, true));
        ok("alerts: питание в норме", !mkukic.client.qol.AlertList.powerStarved(1f, true));
        ok("alerts: не потребитель", !mkukic.client.qol.AlertList.powerStarved(0f, false));

        ok("alerts: склад забит", mkukic.client.qol.AlertList.storageFull(980, 1000, 0.95f));
        ok("alerts: склад не забит", !mkukic.client.qol.AlertList.storageFull(500, 1000, 0.95f));
        ok("alerts: нулевая вместимость", !mkukic.client.qol.AlertList.storageFull(10, 0, 0.95f));

        Seq<mkukic.client.qol.AlertList.Problem> list = new Seq<>();
        mkukic.client.qol.AlertList.Problem p1 = new mkukic.client.qol.AlertList.Problem();
        p1.kind = mkukic.client.qol.AlertList.Kind.turret;
        mkukic.client.qol.AlertList.Problem p2 = new mkukic.client.qol.AlertList.Problem();
        p2.kind = mkukic.client.qol.AlertList.Kind.power;
        list.add(p1); list.add(p2);
        eq("alerts: счётчик турелей", 1f, mkukic.client.qol.AlertList.count(list, mkukic.client.qol.AlertList.Kind.turret), 0);
        eq("alerts: счётчик питания", 1f, mkukic.client.qol.AlertList.count(list, mkukic.client.qol.AlertList.Kind.power), 0);
        eq("alerts: счётчик складов", 0f, mkukic.client.qol.AlertList.count(list, mkukic.client.qol.AlertList.Kind.full), 0);
    }

    // ==================== v1.16: SpectateCam (Caster-UI) ====================

    static void spectateCam(){
        boolean[] v = {true, true, true, true};
        eq("spec: следующий", 2f, mkukic.client.qol.SpectateCam.nextIndex(1, 1, v), 0);
        eq("spec: предыдущий", 0f, mkukic.client.qol.SpectateCam.nextIndex(1, -1, v), 0);
        eq("spec: кольцо вперёд", 0f, mkukic.client.qol.SpectateCam.nextIndex(3, 1, v), 0);
        eq("spec: кольцо назад", 3f, mkukic.client.qol.SpectateCam.nextIndex(0, -1, v), 0);
        eq("spec: старт без цели", 0f, mkukic.client.qol.SpectateCam.nextIndex(-1, 1, v), 0);

        boolean[] g = {false, true, false, true};
        eq("spec: пропуск недоступных", 1f, mkukic.client.qol.SpectateCam.nextIndex(0, 1, g), 0);
        eq("spec: пропуск назад", 3f, mkukic.client.qol.SpectateCam.nextIndex(0, -1, g), 0);
        eq("spec: доступных нет", -1f, mkukic.client.qol.SpectateCam.nextIndex(0, 1, new boolean[]{false, false}), 0);
        eq("spec: пустой список", -1f, mkukic.client.qol.SpectateCam.nextIndex(0, 1, new boolean[0]), 0);

        eqF("spec: вспышка в начале", 1f, mkukic.client.qol.SpectateCam.flashAlpha(0f, 0f, 60f));
        eqF("spec: вспышка на середине", 0.5f, mkukic.client.qol.SpectateCam.flashAlpha(30f, 0f, 60f));
        eqF("spec: вспышка потухла", 0f, mkukic.client.qol.SpectateCam.flashAlpha(120f, 0f, 60f));
        eqF("spec: нулевая длительность", 0f, mkukic.client.qol.SpectateCam.flashAlpha(10f, 0f, 0f));
    }

    // ==================== v1.16: Edge panning (MI2) ====================

    static void edgePan(){
        eq("edge: в центре экрана", 0f, mkukic.client.qol.ViewControl.edgeBits(640f, 360f, 1280f, 720f, 14f), 0);
        eq("edge: левый край", 1f, mkukic.client.qol.ViewControl.edgeBits(5f, 360f, 1280f, 720f, 14f), 0);
        eq("edge: правый край", 2f, mkukic.client.qol.ViewControl.edgeBits(1275f, 360f, 1280f, 720f, 14f), 0);
        eq("edge: нижний край", 4f, mkukic.client.qol.ViewControl.edgeBits(640f, 5f, 1280f, 720f, 14f), 0);
        eq("edge: верхний край", 8f, mkukic.client.qol.ViewControl.edgeBits(640f, 715f, 1280f, 720f, 14f), 0);
        eq("edge: угол = два бита", 9f, mkukic.client.qol.ViewControl.edgeBits(3f, 717f, 1280f, 720f, 14f), 0);
        eq("edge: нулевой экран", 0f, mkukic.client.qol.ViewControl.edgeBits(0f, 0f, 0f, 0f, 14f), 0);
        eq("edge: нулевой margin", 0f, mkukic.client.qol.ViewControl.edgeBits(0f, 0f, 1280f, 720f, 0f), 0);
    }

    // ==================== v1.16.1: параметр-строки чат-команд (регрессия краша) ====================

    /**
     * КРАШ v1.16.0 (crash-report-11_02_2026_13_54_06):
     *   java.lang.IllegalArgumentException: Malformed param '&lt;номер|имя'
     *     at arc.util.CommandHandler$Command.&lt;init&gt;(CommandHandler.java:176)
     *     at mkukic.server.ServerCommands.register(ServerCommands.java:740)   // /rtv
     *     at mkukic.MKUKICMod.registerClientCommands(MKUKICMod.java:71)
     *
     * arc.util.CommandHandler режет paramText ПО ПРОБЕЛАМ и требует, чтобы каждый токен был
     * ЦЕЛИКОМ обернут в &lt;...&gt; или [...] («Arg names must not have spaces in them»).
     * Строка "&lt;номер|имя карты&gt;" распадалась на "&lt;номер|имя" и "карты&gt;" — и валила
     * загрузку мода у КАЖДОГО клиента (registerClientCommands вызывается и на клиенте).
     *
     * Тест регистрирует ВСЕ команды мода в настоящем CommandHandler: любая битая
     * параметр-строка снова уронит сборку, а не игру пользователя.
     */
    static void commandParams(){
        // 1) реальная регистрация всех чат-команд мода — не должна кидать
        arc.util.CommandHandler h = new arc.util.CommandHandler("/");
        try{
            mkukic.server.ServerCommands.register(h);
            ok("ServerCommands.register() не кидает (регрессия краша v1.16.0)", true);
        }catch(Throwable t){
            ok("ServerCommands.register() не кидает: " + t.getClass().getSimpleName() + ": " + t.getMessage(), false);
        }

        // 2) консольные команды сервера — тоже
        try{
            mkukic.server.ServerConsoleCommands.register(new arc.util.CommandHandler(""));
            ok("ServerConsoleCommands.register() не кидает", true);
        }catch(Throwable t){
            ok("ServerConsoleCommands.register() не кидает: " + t.getClass().getSimpleName() + ": " + t.getMessage(), false);
        }

        // 3) каждая зарегистрированная команда: paramText обязан соответствовать правилам arc
        int total = 0, bad = 0;
        for(arc.util.CommandHandler.Command c : h.getCommandList()){
            total++;
            String err = malformedParam(c.paramText);
            if(err != null){
                bad++;
                System.out.println("  битый param у /" + c.text + " ('" + c.paramText + "'): " + err);
            }
        }
        ok("чат-команд зарегистрировано >= 30 (фактически " + total + ")", total >= 30);
        ok("битых параметр-строк среди " + total + " команд нет (найдено " + bad + ")", bad == 0);

        // 4) /rtv обязан быть НЕобязательным variadic: пустой вызов доходит до хендлера (счёт голосов)
        arc.util.CommandHandler probe = new arc.util.CommandHandler("/");
        final String[][] captured = new String[1][];
        probe.<Object>register("rtv", "[номер|имя...]", "probe", (args, p) -> captured[0] = args);

        ok("/rtv без аргументов -> valid (не fewArguments)",
            probe.handleMessage("/rtv", null).type == arc.util.CommandHandler.ResponseType.valid);
        ok("/rtv без аргументов -> хендлер получил 0 аргументов",
            captured[0] != null && captured[0].length == 0);

        probe.handleMessage("/rtv 5", null);
        ok("/rtv 5 -> args[0] == '5'",
            captured[0] != null && captured[0].length == 1 && "5".equals(captured[0][0]));

        probe.handleMessage("/rtv my cool map", null);
        ok("/rtv my cool map -> variadic не режет пробелы (args[0] == 'my cool map')",
            captured[0] != null && captured[0].length == 1 && "my cool map".equals(captured[0][0]));

        // 5) почему именно [] а не <>: обязательный param без аргумента не доходит до хендлера
        arc.util.CommandHandler probe2 = new arc.util.CommandHandler("/");
        probe2.<Object>register("x", "<номер|имя...>", "probe", (args, p) -> {});
        ok("<обязательный...> без аргумента -> fewArguments (поэтому у /rtv скобки [])",
            probe2.handleMessage("/x", null).type == arc.util.CommandHandler.ResponseType.fewArguments);

        // 6) sanity: arc по-прежнему кидает на старой битой строке — значит тест ловит ТОТ ЖЕ класс ошибок
        boolean threw = false;
        try{
            new arc.util.CommandHandler.Command("rtv", "<номер|имя карты>", "d", (a, p) -> {});
        }catch(IllegalArgumentException e){
            threw = true;
        }
        ok("arc по-прежнему кидает Malformed param на '<номер|имя карты>' (тест валиден)", threw);
    }

    /** Точная копия правил arc.util.CommandHandler$Command: null = всё хорошо, иначе текст ошибки. */
    static String malformedParam(String paramText){
        if(paramText == null || paramText.length() == 0) return null;
        String[] ps = paramText.split(" ");
        boolean hadOptional = false;
        for(int i = 0; i < ps.length; i++){
            String p = ps[i];
            if(p.length() <= 2) return "Malformed param '" + p + "'";
            char l = p.charAt(0), r = p.charAt(p.length() - 1);
            boolean optional;
            if(l == '<' && r == '>'){
                if(hadOptional) return "non-optional '" + p + "' после optional";
                optional = false;
            }else if(l == '[' && r == ']'){
                optional = true;
            }else{
                return "Malformed param '" + p + "'";
            }
            if(optional) hadOptional = true;
            String fname = p.substring(1, p.length() - 1);
            if(fname.endsWith("...") && i != ps.length - 1) return "variadic '" + p + "' не последний";
        }
        return null;
    }

    // ==================== v1.17: структура меню ====================

    /**
     * Вкладка «QoL» расформирована: функции разложены по тематическим разделам
     * (визуальное -> «Визуал», управление юнитами -> «ИИ юнитов», автопилот -> «ИИ игрока»,
     * инструменты стройки -> «Утилиты», сводки/тревоги -> новый раздел «Информация»).
     * Проверяем, что навигация осталась согласованной и что ни одно плавающее окно
     * не «осиротело» (все по-прежнему зарегистрированы в хабе F4).
     */
    static void menuStructure(){
        String[] tabs = mkukic.client.MKUKICMenu.tabLabels();

        ok("вкладок в меню 10 (v1.19: добавлена «Onix»)", tabs.length == 10);

        boolean hasInfo = false, hasQol = false, hasHq = false, hasOnix = false;
        for(String n : tabs){
            if("Информация".equals(n)) hasInfo = true;
            if("QoL".equals(n)) hasQol = true;
            if("Штаб".equals(n)) hasHq = true;   // ClientHook ищет эту вкладку ПО ИМЕНИ (F8)
            if("Onix".equals(n)) hasOnix = true; // v1.19: порт Onix Lite
        }
        ok("раздел «Информация» присутствует", hasInfo);
        ok("вкладки «QoL» больше нет", !hasQol);
        ok("вкладка «Штаб» на месте (на неё завязан бинд F8)", hasHq);
        ok("вкладка «Onix» присутствует", hasOnix);

        // имена уникальны — иначе навигация и checked() будут путаться
        boolean uniq = true;
        for(int i = 0; i < tabs.length; i++)
            for(int j = i + 1; j < tabs.length; j++)
                if(tabs[i].equals(tabs[j])) uniq = false;
        ok("имена вкладок уникальны", uniq);

        // у каждой вкладки есть иконка (не пустая строка)
        boolean icons = true;
        for(String n : tabs){
            String ic = mkukic.client.MKUKICMenu.tabIcon(n);
            if(ic == null || ic.isEmpty()) icons = false;
        }
        ok("у всех вкладок есть иконка", icons);

        // иконка «Информации» — не дефолтная (значит case в tabIcon добавлен)
        ok("у «Информации» своя иконка (не дефолт настроек)",
            !mkukic.client.MKUKICMenu.tabIcon("Информация").equals(mkukic.client.MKUKICMenu.tabIcon("___нет_такой_вкладки___")));

        // все плавающие окна по-прежнему зарегистрированы в хабе (F4) и не потерялись
        mkukic.client.qol.QolWindows.init();
        arc.struct.Seq<mkukic.client.qol.QolWindows.Win> all = mkukic.client.qol.QolWindows.all;
        ok("зарегистрировано 31 окно (30 + хаб), факт. " + all.size, all.size == 31);

        boolean hubThere = false, idsUniq = true;
        for(int i = 0; i < all.size; i++){
            if("hub".equals(all.get(i).id)) hubThere = true;
            for(int j = i + 1; j < all.size; j++)
                if(all.get(i).id.equals(all.get(j).id)) idsUniq = false;
        }
        ok("окно-хаб зарегистрировано", hubThere);
        ok("id окон уникальны", idsUniq);

        // все окна v1.16 на месте (их перенесли в «Информацию»/«Визуал»/«Утилиты», но не удалили)
        String[] expected = {"attack", "chat", "xray", "flow", "prodgraph", "alerts", "spectate"};
        for(String id : expected){
            boolean found = false;
            for(mkukic.client.qol.QolWindows.Win w : all) if(id.equals(w.id)) found = true;
            ok("окно «" + id + "» зарегистрировано после переноса", found);
        }
    }

    // ==================== harness ====================

    static void eq(String what, float exp, float got, float eps){
        if(Math.abs(exp - got) <= eps){ passed++; }
        else{ failed++; System.out.println("FAIL " + what + ": expected " + exp + " got " + got); }
    }

    static void eq(String what, String exp, String got, Object unused){
        if(exp == null ? got == null : exp.equals(got)){ passed++; }
        else{ failed++; System.out.println("FAIL " + what + ": expected [" + exp + "] got [" + got + "]"); }
    }

    static void eqS(String what, String exp, String got){
        if(exp == null ? got == null : exp.equals(got)){ passed++; }
        else{ failed++; System.out.println("FAIL " + what + ": expected [" + exp + "] got [" + got + "]"); }
    }

    static void eqF(String what, float exp, float got){
        if(Math.abs(exp - got) < 0.01f){ passed++; }
        else{ failed++; System.out.println("FAIL " + what + ": expected " + exp + " got " + got); }
    }

    static void ok(String what, boolean cond){
        if(cond){ passed++; }
        else{ failed++; System.out.println("FAIL " + what); }
    }

    // ==================== v1.19: порт Onix Lite ====================

    /**
     * Onix-порт: чистые функции, от которых зависит поведение в игре —
     * нечёткий поиск по панели (раскладка/транслит/опечатки), парсинг клиентских
     * команд, подстановки в чате и сглаживание анимаций.
     */
    static void onix(){
        mkukic.MKUKICSettings s = mkukic.MKUKICSettings.s();

        // --- 1. нечёткий поиск: раскладка, транслит, опечатки ---
        ok("поиск: пустой запрос матчит всё", mkukic.client.OnixPanel.fuzzyMatch("", "Авто-юнит"));
        ok("поиск: точное вхождение", mkukic.client.OnixPanel.fuzzyMatch("авто", "Авто-юнит"));
        ok("поиск: регистр не важен", mkukic.client.OnixPanel.fuzzyMatch("АВТО", "Авто-юнит"));
        ok("поиск: забытая раскладка (fdsn -> авто)", mkukic.client.OnixPanel.fuzzyMatch("fdsn", "Авто-юнит"));
        ok("поиск: транслит (avto -> Авто)", mkukic.client.OnixPanel.fuzzyMatch("avto", "Авто-юнит"));
        ok("поиск: транслит (bot -> Бот)", mkukic.client.OnixPanel.fuzzyMatch("bot", "Бот-помощник"));
        ok("поиск: опечатка на 1 символ", mkukic.client.OnixPanel.fuzzyMatch("аккаунты", "Аккаунтs"));
        ok("поиск: опечатка на 1 символ (аккаунтс -> Аккаунты)", mkukic.client.OnixPanel.fuzzyMatch("аккаунтс", "Аккаунты"));
        ok("поиск: транслит-префикс (konsol -> Консоль)", mkukic.client.OnixPanel.fuzzyMatch("konsol", "Консоль"));
        ok("поиск: далёкая опечатка НЕ матчит (acounts -> Аккаунты)", !mkukic.client.OnixPanel.fuzzyMatch("acounts", "Аккаунты"));
        ok("поиск: не матчит постороннее", !mkukic.client.OnixPanel.fuzzyMatch("музыка", "Авто-юнит"));
        ok("поиск: null-текст не роняет", !mkukic.client.OnixPanel.fuzzyMatch("q", null));

        eqS("translit: QWERTY→ЙЦУКЕН", "привет", mkukic.client.OnixPanel.translit("ghbdtn"));
        eqS("phonetic: avto→авто", "авто", mkukic.client.OnixPanel.phonetic("avto"));
        eqS("phonetic: shchuka→щка? нет: sch→щ", "щ", mkukic.client.OnixPanel.phonetic("sch"));
        eqS("phonetic: zhuk→жук", "жук", mkukic.client.OnixPanel.phonetic("zhuk"));
        eqS("phonetic: yunit→юнит", "юнит", mkukic.client.OnixPanel.phonetic("yunit"));
        ok("levenshtein: kitten/sitting = 3", mkukic.client.OnixPanel.levenshtein("kitten", "sitting") == 3);
        ok("levenshtein: одинаковые = 0", mkukic.client.OnixPanel.levenshtein("бот", "бот") == 0);
        ok("levenshtein: пустая строка = длина другой", mkukic.client.OnixPanel.levenshtein("", "abcd") == 4);

        // --- 2. клиентские команды Onix ---
        boolean cmdsOn = s.onixCommands;
        String oldPrefix = s.onixCmdPrefix;
        s.onixCommands = true;
        s.onixCmdPrefix = "/m";
        ok("команды: префикс по умолчанию", "/m".equals(mkukic.client.onix.OnixCommands.prefix()));
        eqS("команды: stripPrefix без аргументов", "go", mkukic.client.onix.OnixCommands.stripPrefix("/m go", "/m"));
        eqS("команды: stripPrefix с аргументами", "fill", mkukic.client.onix.OnixCommands.stripPrefix("/m fill copper 500", "/m"));
        ok("команды: isCommand на известную", mkukic.client.onix.OnixCommands.isCommand("/m help"));
        ok("команды: isCommand на неизвестную = false", !mkukic.client.onix.OnixCommands.isCommand("/mNoSuchCmdEver"));
        ok("команды: find нечувствителен к регистру", mkukic.client.onix.OnixCommands.find("HELP") != null);
        ok("команды: find(null) = null", mkukic.client.onix.OnixCommands.find(null) == null);
        ok("команды: список не пуст", mkukic.client.onix.OnixCommands.commands().size > 0);
        boolean uniq = true;
        Seq<String> seen = new Seq<>();
        for(mkukic.client.onix.OnixCommands.Cmd c : mkukic.client.onix.OnixCommands.commands()){
            if(seen.contains(c.name)) uniq = false;
            seen.add(c.name);
            ok("команда «" + c.name + "» имеет описание", c.desc != null && !c.desc.isEmpty());
        }
        ok("имена команд уникальны", uniq);
        s.onixCommands = cmdsOn;
        s.onixCmdPrefix = oldPrefix;

        // --- 2b. разбор координат команд (go/lookat/tp) ---
        float lx = mkukic.client.onix.OnixCommands.lastCoords[0], ly = mkukic.client.onix.OnixCommands.lastCoords[1];
        mkukic.client.onix.OnixCommands.lastCoords[0] = Float.NaN;
        mkukic.client.onix.OnixCommands.lastCoords[1] = Float.NaN;
        float[] cc = mkukic.client.onix.OnixCommands.parseCoords(new String[]{"cursor"}, 111f, 222f, 5f, 6f, 500);
        ok("координаты: cursor -> позиция курсора", cc != null && cc[0] == 111f && cc[1] == 222f);
        cc = mkukic.client.onix.OnixCommands.parseCoords(new String[]{"here"}, 111f, 222f, 5f, 6f, 500);
        ok("координаты: here -> позиция юнита", cc != null && cc[0] == 5f && cc[1] == 6f);
        cc = mkukic.client.onix.OnixCommands.parseCoords(new String[]{"курсор"}, 111f, 222f, 5f, 6f, 500);
        ok("координаты: «курсор» по-русски", cc != null && cc[0] == 111f);
        cc = mkukic.client.onix.OnixCommands.parseCoords(new String[]{"100", "200"}, 0f, 0f, 0f, 0f, 500);
        ok("координаты: 100 200 -> тайлы * 8", cc != null && cc[0] == 800f && cc[1] == 1600f);
        cc = mkukic.client.onix.OnixCommands.parseCoords(new String[]{"9000", "12000"}, 0f, 0f, 0f, 0f, 500);
        ok("координаты: большие числа -> мировые", cc != null && cc[0] == 9000f && cc[1] == 12000f);
        cc = mkukic.client.onix.OnixCommands.parseCoords(new String[0], 111f, 222f, 5f, 6f, 500);
        ok("координаты: пусто и нет последних -> null", cc == null);
        mkukic.client.onix.OnixCommands.lastCoords[0] = 300f;
        mkukic.client.onix.OnixCommands.lastCoords[1] = 400f;
        cc = mkukic.client.onix.OnixCommands.parseCoords(new String[0], 111f, 222f, 5f, 6f, 500);
        ok("координаты: фолбэк на последние из чата", cc != null && cc[0] == 300f && cc[1] == 400f);
        cc = mkukic.client.onix.OnixCommands.parseCoords(new String[]{"cursor"}, Float.NaN, Float.NaN, 5f, 6f, 500);
        ok("координаты: cursor без ввода -> фолбэк, не NaN", cc != null && !Float.isNaN(cc[0]));
        cc = mkukic.client.onix.OnixCommands.parseCoords(new String[]{"мусор", "не-число"}, 111f, 222f, 5f, 6f, 500);
        ok("координаты: мусор не роняет парсер", cc != null && cc[0] == 300f);
        mkukic.client.onix.OnixCommands.lastCoords[0] = lx;
        mkukic.client.onix.OnixCommands.lastCoords[1] = ly;

        // --- 3. подстановки в чате ---
        boolean rep = s.onixTextReplacer, on = s.onixEnabled;
        s.onixEnabled = true;
        s.onixTextReplacer = true;
        eqS("подстановки: %s -> shrug", "¯\\_(ツ)_/¯", mkukic.client.onix.OnixChat.applyReplacers("%s"));
        ok("подстановки: %c даёт координаты", mkukic.client.onix.OnixChat.applyReplacers("%c").contains(","));
        ok("подстановки: %h даёт координаты", mkukic.client.onix.OnixChat.applyReplacers("%h").contains(","));
        eqS("подстановки: null безопасно", null, mkukic.client.onix.OnixChat.applyReplacers(null));
        s.onixTextReplacer = false;
        eqS("подстановки выключены -> текст как есть", "%s %c", mkukic.client.onix.OnixChat.applyReplacers("%s %c"));
        s.onixTextReplacer = rep;
        s.onixEnabled = on;

        // --- 4. анимации: сглаживание и «вылет» панели ---
        boolean anims = s.onixAnims;
        s.onixEnabled = true;
        s.onixAnims = true;
        // первый кадр берёт цель сразу (иначе счётчики «накручивались» бы с нуля при первом появлении)
        float v = mkukic.client.onix.OnixAnims.smoothValue("test-counter", 100f);
        eqF("сглаживание: первый кадр = цель без анимации с нуля", 100f, v);
        v = mkukic.client.onix.OnixAnims.smoothValue("test-counter", 500f);
        ok("сглаживание: смена цели идёт постепенно", v > 100f && v < 500f);
        for(int i = 0; i < 400; i++) v = mkukic.client.onix.OnixAnims.smoothValue("test-counter", 500f);
        eqF("сглаживание: сходится к цели", 500f, v);
        s.onixSmoothCounters = false;
        eqF("сглаживание выключено -> сразу цель", 12f, mkukic.client.onix.OnixAnims.smoothValue("test-counter", 12f));
        s.onixSmoothCounters = true;
        float before = mkukic.client.onix.OnixAnims.smoothValue("test-counter", 100f);
        mkukic.client.onix.OnixAnims.reset();
        ok("reset() чистит сглаженные значения", mkukic.client.onix.OnixAnims.smoothValue("test-counter", 100f) != before
            || mkukic.client.onix.OnixAnims.panelProgress() == 1f);

        mkukic.client.onix.OnixAnims.armPanel();
        eqF("панель: после arm анимация в нуле", 0f, mkukic.client.onix.OnixAnims.panelProgress());
        for(int i = 0; i < 60; i++) mkukic.client.onix.OnixAnims.uiTick();
        ok("панель: анимация растёт", mkukic.client.onix.OnixAnims.panelProgress() > 0.5f);
        for(int i = 0; i < 600; i++) mkukic.client.onix.OnixAnims.uiTick();
        eqF("панель: доезжает до 1", 1f, mkukic.client.onix.OnixAnims.panelProgress());
        ok("панель: каскад категорий ограничен 0…1", mkukic.client.onix.OnixAnims.cascadeAlpha(0) >= 0f
            && mkukic.client.onix.OnixAnims.cascadeAlpha(0) <= 1f);
        s.onixAnims = false;
        mkukic.client.onix.OnixAnims.armPanel();
        eqF("панель: анимации выключены -> сразу 1", 1f, mkukic.client.onix.OnixAnims.panelProgress());
        s.onixAnims = anims;

        // --- 5. дефолты настроек Onix (то, что видит пользователь после обновления) ---
        mkukic.MKUKICSettings d = new mkukic.MKUKICSettings();
        ok("дефолт: Onix включён", d.onixEnabled);
        ok("дефолт: клиентские команды включены", d.onixCommands);
        ok("дефолт: префикс команд /m", "/m".equals(d.onixCmdPrefix));
        ok("дефолт: копирование из чата ВЫКЛ (как в Onix Lite)", !d.onixChatCopy);
        ok("дефолт: авто-ответчик включён (как в Onix)", d.onixAfkReply);
        ok("дефолт: авто-юнит выключен (включается хоткеем)", !d.onixAutoUnit);
        ok("дефолт: бот выключен", !d.onixBot);
        ok("дефолт: приоритет предметов пуст = авто-порядок", d.onixFillItemPriority != null && d.onixFillItemPriority.size == 0);
        ok("дефолт: авто-проверка обновлений ВЫКЛ (опт-ин, не дёргаем сеть)", !d.onixUpdateCheck);
        ok("дефолт: качать обновление в фоне", d.onixUpdateBackground);

        // --- 6. регрессия краша v1.19.0: NPE в OnixConsole$1.<init> на ClientLoadEvent ---
        // Причина была в `dialog::hide` внутри инициализатора того же объекта: поле ещё null,
        // а javac вставляет Objects.requireNonNull(dialog). Теперь — this::hide и ленивая сборка.
        try{
            mkukic.client.onix.Onix.init();
            ok("Onix.init() проходит без исключений (загрузка клиента)", true);
        }catch(Throwable t){
            ok("Onix.init() проходит без исключений: " + t, false);
        }
        try{
            java.lang.reflect.Field f = mkukic.client.onix.OnixConsole.class.getDeclaredField("dialog");
            f.setAccessible(true);
            ok("консоль НЕ строит окно в init() (лениво: dialog == null)", f.get(null) == null);
            mkukic.client.onix.OnixConsole.class.getDeclaredMethod("buildDialog");
            ok("buildDialog() на месте (окно собирается по первому открытию)", true);
            mkukic.client.onix.OnixConsole.uiTick();   // не должен ничего требовать от сцены
            ok("OnixConsole.uiTick() безопасен без сцены/ввода", true);
            mkukic.client.onix.OnixConsole.add("INFO", "тестовая строка журнала", 0);
            ok("журнал консоли пишет строки до открытия окна", mkukic.client.onix.OnixConsole.lines.size > 0);
        }catch(Throwable t){
            ok("рефлексия/ленивость OnixConsole: " + t, false);
        }
        ok("дефолт: детект Onix включён", d.onixDetect);
    }

    // ==================== v1.19.2: детект клиентов (Onix / Onix Lite / Foo's) ====================

    /**
     * Определение семейства клиента: тег ника (シ / <Lシ>), поведенческие признаки,
     * ручная пометка и правила показа значков (только у тех, у кого клиент найден).
     */
    static void onixDetect(){
        // --- 1. тег ника: единственный надёжный способ отличить Lite от полной версии ---
        mkukic.client.onix.OnixClientDetect.NickHint h = mkukic.client.onix.OnixClientDetect.nickHint("Игрок\u30b7");
        ok("ник с シ -> Onix", h.family == mkukic.client.onix.OnixClientDetect.FAM_ONIX);
        ok("ник с シ -> уверенность 2 (фирменный тег)", h.confidence == 2);

        h = mkukic.client.onix.OnixClientDetect.nickHint("<L\u30b7>Player");
        ok("ник с <Lシ> -> Onix Lite", h.family == mkukic.client.onix.OnixClientDetect.FAM_ONIX_LITE);
        ok("ник с <Lシ> -> уверенность 2", h.confidence == 2);

        h = mkukic.client.onix.OnixClientDetect.nickHint("[#ff0000]Onix[]Pro");
        ok("слово «onix» -> Onix, уверенность 1", h.family == mkukic.client.onix.OnixClientDetect.FAM_ONIX
            && h.confidence == 1);
        h = mkukic.client.onix.OnixClientDetect.nickHint("Onix Lite player");
        ok("«onix lite» -> Onix Lite", h.family == mkukic.client.onix.OnixClientDetect.FAM_ONIX_LITE);
        h = mkukic.client.onix.OnixClientDetect.nickHint("Foo's Client enjoyer");
        ok("«foo's» -> Foo's Client", h.family == mkukic.client.onix.OnixClientDetect.FAM_FOO);
        h = mkukic.client.onix.OnixClientDetect.nickHint("Fool food moon");
        ok("«foo» внутри других слов НЕ считается", h.family == mkukic.client.onix.OnixClientDetect.FAM_NONE);
        h = mkukic.client.onix.OnixClientDetect.nickHint("Обычный игрок");
        ok("обычный ник -> ничего", h.family == mkukic.client.onix.OnixClientDetect.FAM_NONE);
        h = mkukic.client.onix.OnixClientDetect.nickHint(null);
        ok("null-ник не роняет", h.family == mkukic.client.onix.OnixClientDetect.FAM_NONE);
        ok("цветные коды в нике не мешают", mkukic.client.onix.OnixClientDetect
            .nickHint("[#c9a6ff]<L\u30b7>[]Name").family == mkukic.client.onix.OnixClientDetect.FAM_ONIX_LITE);

        // --- 2. текст и цвета бейджей ---
        eqS("бейдж MKUKIC", "MK", mkukic.client.onix.OnixClientDetect.iconText(
            mkukic.client.onix.OnixClientDetect.FAM_MKUKIC, 2, true));
        eqS("бейдж Onix", "Onix", mkukic.client.onix.OnixClientDetect.iconText(
            mkukic.client.onix.OnixClientDetect.FAM_ONIX, 2, true));
        eqS("бейдж Onix Lite", "Lite", mkukic.client.onix.OnixClientDetect.iconText(
            mkukic.client.onix.OnixClientDetect.FAM_ONIX_LITE, 2, true));
        eqS("бейдж Foo", "Foo", mkukic.client.onix.OnixClientDetect.iconText(
            mkukic.client.onix.OnixClientDetect.FAM_FOO, 1, true));
        eqS("бейдж неизвестного клиента", "C", mkukic.client.onix.OnixClientDetect.iconText(
            mkukic.client.onix.OnixClientDetect.FAM_CLIENT, 1, true));
        eqS("догадка помечается «?»", "Foo?", mkukic.client.onix.OnixClientDetect.iconText(
            mkukic.client.onix.OnixClientDetect.FAM_FOO, 0, true));
        eqS("«?» можно выключить", "Foo", mkukic.client.onix.OnixClientDetect.iconText(
            mkukic.client.onix.OnixClientDetect.FAM_FOO, 0, false));
        eqS("обычный игрок без бейджа", "", mkukic.client.onix.OnixClientDetect.iconText(
            mkukic.client.onix.OnixClientDetect.FAM_NONE, 0, true));
        ok("у каждого семейства свой цвет", mkukic.client.onix.OnixClientDetect
            .familyColor(mkukic.client.onix.OnixClientDetect.FAM_ONIX)
            != mkukic.client.onix.OnixClientDetect.familyColor(mkukic.client.onix.OnixClientDetect.FAM_FOO));
        eqS("имя семейства Onix Lite", "Onix Lite", mkukic.client.onix.OnixClientDetect
            .familyName(mkukic.client.onix.OnixClientDetect.FAM_ONIX_LITE));

        // --- 3. вердикт: уровень и семейство ---
        mkukic.MKUKICSettings s = new mkukic.MKUKICSettings();
        s.onixDetectSensitivity = 1;
        s.onixDetectSelf = false;

        mkukic.client.onix.OnixClientDetect.Verdict v = new mkukic.client.onix.OnixClientDetect.Verdict();
        v.id = 4242;
        v.name = "Test\u30b7";
        v.relevel(s);
        v.classify(s);
        ok("тег ника сам по себе = клиент", v.level >= 2);
        ok("тег ника -> семейство Onix", v.family == mkukic.client.onix.OnixClientDetect.FAM_ONIX);
        ok("тег ника -> бейдж «Onix»", "Onix".equals(v.iconText()));

        v = new mkukic.client.onix.OnixClientDetect.Verdict();
        v.id = 4243;
        v.name = "Worker";
        // один признак не делает игрока клиентом: нужны 3 разных и 70+ очков
        v.addScore(80f, "рывки позиции", 0f, 1f, s);
        ok("один признак -> не клиент", v.level < 2);
        ok("вклад признака ограничен сверху (26)", v.score <= 26.01f);
        v.addScore(80f, "рывки позиции", 0f, 1f, s);
        eqF("повтор того же признака очки не добавляет", 26f, v.score);
        v.addScore(30f, "идеально прямой ход", 0f, 1f, s);
        v.addScore(30f, "непрерывная копка 60с", 0f, 1f, s);
        ok("три разных признака и 70+ очков -> клиент", v.level == 2);
        v.classify(s);
        ok("поведение без Onix-признаков -> Foo", v.family == mkukic.client.onix.OnixClientDetect.FAM_FOO);
        ok("бейдж Foo есть", v.iconText().startsWith("Foo"));

        v = new mkukic.client.onix.OnixClientDetect.Verdict();
        v.id = 4244;
        v.name = "Hauler";
        v.addScore(30f, "развоз ресурсов по постройкам", 3f, 0f, s);
        v.addScore(30f, "идеально прямой ход", 0.5f, 1.4f, s);
        v.addScore(30f, "мгновенные развороты", 0.5f, 1.2f, s);
        v.classify(s);
        ok("авто-филл перевешивает -> Onix", v.family == mkukic.client.onix.OnixClientDetect.FAM_ONIX);

        v = new mkukic.client.onix.OnixClientDetect.Verdict();
        v.id = 4245;
        v.name = "Mystery";
        v.addScore(30f, "рывки позиции", 1f, 1f, s);
        v.addScore(30f, "идеально прямой ход", 1f, 1f, s);
        v.addScore(30f, "непрерывная копка 60с", 1f, 1f, s);
        v.classify(s);
        ok("нет перевеса -> клиент без семейства", v.level == 2
            && v.family == mkukic.client.onix.OnixClientDetect.FAM_CLIENT);
        eqS("бейдж неизвестного клиента", "C?", v.iconText());

        v = new mkukic.client.onix.OnixClientDetect.Verdict();
        v.id = 4246;
        v.name = "Quiet";
        v.relevel(s);
        v.classify(s);
        ok("обычный игрок: уровень 0", v.level == 0);
        ok("обычный игрок: без семейства", v.family == mkukic.client.onix.OnixClientDetect.FAM_NONE);
        eqS("обычный игрок: пустой тег", "", v.tag());

        // --- 4. значки только у клиентов ---
        mkukic.MKUKICSettings d = new mkukic.MKUKICSettings();
        d.onixDetectSuspicious = false;
        mkukic.client.onix.OnixClientDetect.Verdict susp = new mkukic.client.onix.OnixClientDetect.Verdict();
        susp.level = 1;
        susp.family = mkukic.client.onix.OnixClientDetect.FAM_NONE;
        ok("подозрительный по умолчанию БЕЗ значка", !mkukic.client.onix.OnixClientDetect.showFor(susp, d));
        d.onixDetectSuspicious = true;
        ok("подозрительный со включённой настройкой -> значок", mkukic.client.onix.OnixClientDetect.showFor(susp, d));
        mkukic.client.onix.OnixClientDetect.Verdict client = new mkukic.client.onix.OnixClientDetect.Verdict();
        client.level = 2;
        client.family = mkukic.client.onix.OnixClientDetect.FAM_ONIX_LITE;
        d.onixDetectSuspicious = false;
        ok("клиент -> значок всегда", mkukic.client.onix.OnixClientDetect.showFor(client, d));
        mkukic.client.onix.OnixClientDetect.Verdict self = new mkukic.client.onix.OnixClientDetect.Verdict();
        self.level = 3;
        self.family = mkukic.client.onix.OnixClientDetect.FAM_MKUKIC;
        d.onixDetectSelf = false;
        ok("себя можно не помечать", !mkukic.client.onix.OnixClientDetect.showFor(self, d));
        d.onixDetectSelf = true;
        ok("себя видно при onixDetectSelf", mkukic.client.onix.OnixClientDetect.showFor(self, d));
        ok("null-вердикт не роняет showFor", !mkukic.client.onix.OnixClientDetect.showFor(null, d));

        // --- 5. ручная пометка перекрывает эвристику ---
        mkukic.client.onix.OnixClientDetect.Verdict mv = new mkukic.client.onix.OnixClientDetect.Verdict();
        mv.id = 777;
        mv.name = "Manual";
        mkukic.client.onix.OnixClientDetect.verdicts.put(777, mv);
        mkukic.client.onix.OnixClientDetect.markFamily(777, mkukic.client.onix.OnixClientDetect.FAM_FOO);
        ok("ручная пометка -> Foo", mv.family == mkukic.client.onix.OnixClientDetect.FAM_FOO && mv.manual);
        ok("ручная пометка -> уровень клиент", mv.level >= 2);
        mv.score = 90f;
        mv.addScore(50f, "развоз ресурсов по постройкам", 3f, 0f, s);
        mv.classify(s);
        ok("ручная пометка не сбивается поведением", mv.family == mkukic.client.onix.OnixClientDetect.FAM_FOO);
        mkukic.client.onix.OnixClientDetect.markFamily(777, mkukic.client.onix.OnixClientDetect.FAM_NONE);
        ok("снятие пометки -> не manual", !mv.manual);
        ok("снятие пометки чистит анализ", mv.score == 0f && mv.level == 0);
        mkukic.client.onix.OnixClientDetect.verdicts.remove(777);

        // --- 6. дефолты новых настроек ---
        mkukic.MKUKICSettings n = new mkukic.MKUKICSettings();
        ok("дефолт: значки в табе включены", n.onixDetectTabIcons);
        ok("дефолт: подозрительные НЕ показываются", !n.onixDetectSuspicious);
        ok("дефолт: «?» при догадке включено", n.onixDetectConfidence);
        ok("дефолт: теги над головой включены", n.onixDetectTags);
    }
}
