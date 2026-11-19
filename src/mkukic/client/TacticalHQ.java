package mkukic.client;

import arc.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.input.*;
import arc.math.*;
import arc.math.geom.*;
import arc.scene.*;
import arc.scene.event.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import mindustry.ai.types.*;
import mindustry.content.*;
import mindustry.core.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.type.*;
import mindustry.ui.*;
import mindustry.world.*;
import mindustry.world.blocks.defense.turrets.*;
import mindustry.world.blocks.storage.CoreBlock.*;
import mkukic.*;

import static mindustry.Vars.*;

/**
 * ТАКТИЧЕСКИЙ ШТАБ MKUKIC (v2) — полноэкранная командная карта.
 *
 * Слои (всё отключается галками):
 *  - terrain: карта мира, запечённая в текстуру (цвет берётся как в ванильном минимэпе:
 *    block.getColor(tile), т.к. minimapColor() у большинства блоков = 0);
 *  - руда: жилы с цветом ресурса (видно, где богатые участки);
 *  - радиусы турелей: фактический range() каждой турели команды — дыры в обороне видны сразу;
 *  - сектора обороны: 16 секторов вокруг ядра, цвет = плотность прикрытия турелями;
 *  - точки высадки: пульсирующие ромбы + стрелки прогноза атаки к ядру;
 *  - повреждённый транспорт: оранжевые пульсирующие точки (куда слать ремонтников);
 *  - юниты: свои/враги/игрок; отряды: пронумерованные маркеры;
 *  - радар-развёртка: вращающийся луч от игрока (красиво + показывает центр обзора);
 *  - вспышка краёв карты за 10 сек до волны.
 *
 * Панель справа: прогнозатор волн (номер, таймер, состав наземка/воздух, щиты, топ-4 юнита,
 * оценка угрозы; если карта скрывает волны — состав врагов, которые уже в бою),
 * экономика ядра (+ресурс/сек по факту), быстрые приказы, галки слоёв.
 *
 * Управление: ЛКМ по карте — приказ выделенным юнитам в точку; ПКМ — перенести линию обороны
 * отрядов кольцом вокруг клика. Наведение — подсказка по юниту/координатам.
 * Все приказы — стандартные серверные команды, работает в мультиплеере.
 */
public class TacticalHQ{
    // ---------- кэш карты ----------
    private static Texture mapTex;
    private static TextureRegion mapRegion;
    private static Object mapRef;
    private static int texW, texH;

    // жилы руды (мировые координаты + цвет)
    private static final FloatSeq oreX = new FloatSeq();
    private static final FloatSeq oreY = new FloatSeq();
    private static final IntSeq oreC = new IntSeq();

    // геометрия нарисованной карты в координатах сцены
    private static float mapX, mapY, mapW, mapH;

    // ---------- кэши слоёв ----------
    private static final Seq<Tile> spawnCache = new Seq<>();
    private static float spawnCacheTime = -9999f;
    private static float dmgScanTime = -9999f;
    private static final Seq<Building> turretCache = new Seq<>();
    private static final FloatSeq turretRange = new FloatSeq();
    private static float turretCacheTime = -9999f;
    private static final float[] sectorCov = new float[16];
    private static float sectorTime = -9999f;
    private static float sectorCoreX, sectorCoreY;
    private static boolean sectorOk;

    // экономика ядра
    private static final IntIntMap lastItems = new IntIntMap();
    private static float incomeTime = -9999f;
    private static String incomeText = "";

    // наведение
    private static float hoverLX = -1, hoverLY = -1;
    private static boolean hoverIn;

    // алерт волны
    private static int lastAlertWave = -1;

    // кэш строк панели (обновляются раз в 0.5 сек, а не каждый кадр)
    private static String waveStr = "", compStr = "", threatStr = "", armyStr = "";
    private static float textTime = -9999f;

    private static final Vec2 tmp = new Vec2();
    private static final Color tmpC = new Color();

    public static void reset(){
        mapRef = null;
        lastAlertWave = -1;
        incomeTime = -9999f;
        sectorTime = -9999f;
        turretCacheTime = -9999f;
        spawnCacheTime = -9999f;
    }

    // ============================ ГЕНЕРАЦИЯ КАРТЫ ============================

    private static void ensureMap(){
        if(state == null || state.isMenu() || world == null) return;
        if(mapTex != null && mapRef == state.map) return;

        if(mapTex != null){
            mapTex.dispose();
            mapTex = null;
            mapRegion = null;
        }
        mapRef = state.map;
        oreX.clear(); oreY.clear(); oreC.clear();

        int mw = world.width(), mh = world.height();
        int step = Math.max(1, Math.max(mw, mh) / 900);
        texW = Math.max(1, mw / step);
        texH = Math.max(1, mh / step);

        Pixmap p = new Pixmap(texW, texH);
        try{
            for(int ty = 0; ty < texH; ty++){
                for(int tx = 0; tx < texW; tx++){
                    int wxi = tx * step + step / 2, wyi = ty * step + step / 2;
                    Tile t = world.tile(wxi, wyi);
                    int c = 0xFF101018;
                    if(t != null){
                        Block b = t.block();
                        // ВАЖНО: minimapColor() у базового Block возвращает 0 —
                        // цвет даёт getColor(tile) (так делает ванильный минимэп)
                        Block src = (b != null && b != Blocks.air) ? b : t.floor();
                        if(src != null){
                            Color mc = src.getColor(t);
                            if(mc != null) c = mc.rgba8888();
                        }
                        // руда
                        Item drop = t.overlay() != null && t.overlay().itemDrop != null
                            ? t.overlay().itemDrop : (t.floor() != null ? t.floor().itemDrop : null);
                        if(drop != null){
                            oreX.add(wxi * tilesize);
                            oreY.add(wyi * tilesize);
                            oreC.add(drop.color.rgba8888());
                        }
                    }
                    p.setRaw(tx, texH - 1 - ty, c);
                }
            }
            mapTex = new Texture(p);
            mapRegion = new TextureRegion(mapTex);
        }catch(Throwable t){
            Log.err("[MKUKIC] карта штаба не построена", t);
        }finally{
            p.dispose();
        }
    }

    private static Seq<Tile> spawns(){
        if(Time.time - spawnCacheTime > 180f){
            spawnCacheTime = Time.time;
            spawnCache.clear();
            try{
                if(spawner != null){
                    for(Tile t : spawner.getSpawns()){
                        if(t != null) spawnCache.add(t);
                    }
                }
            }catch(Throwable ignored){
            }
        }
        return spawnCache;
    }

    private static void refreshTurrets(){
        turretCache.clear();
        turretRange.clear();
        try{
            for(Building b : player.team().data().buildings){
                if(b instanceof Turret.TurretBuild tb){
                    float r = 0f;
                    try{
                        r = tb.range();
                    }catch(Throwable ignored){
                    }
                    if(r > 40f){
                        turretCache.add(b);
                        turretRange.add(r);
                    }
                }
            }
        }catch(Throwable ignored){
        }
    }

    /** Покрытие 16 секторов вокруг ядра турелями (0..1). */
    private static void computeSectors(){
        sectorOk = false;
        CoreBuild core = player.team().core();
        if(core == null) return;
        sectorCoreX = core.x;
        sectorCoreY = core.y;
        float R = 620f;
        for(int i = 0; i < 16; i++) sectorCov[i] = 0f;
        if(turretCacheTime < Time.time - 90f){
            turretCacheTime = Time.time;
            refreshTurrets();
        }
        for(int i = 0; i < 16; i++){
            float ang = i * 22.5f + 11.25f;
            float px = core.x + Angles.trnsx(ang, R), py = core.y + Angles.trnsy(ang, R);
            int cov = 0;
            for(int k = 0; k < turretCache.size; k++){
                Building b = turretCache.get(k);
                if(Mathf.dst(b.x, b.y, px, py) <= turretRange.get(k)) cov++;
            }
            sectorCov[i] = Math.min(1f, cov / 3f);
        }
        sectorOk = true;
    }

    private static void refreshIncome(){
        CoreBuild core = player.team() != null ? player.team().core() : null;
        if(core == null || core.items == null){
            incomeText = "";
            return;
        }
        float dt = (Time.time - incomeTime) / 60f;
        StringBuilder sb = new StringBuilder();
        if(incomeTime > 0 && dt > 0.5f){
            Seq<Item> top = new Seq<>();
            FloatSeq rates = new FloatSeq();
            for(Item item : content.items()){
                int now = core.items.get(item);
                int prev = lastItems.get(item.id, now);
                float rate = (now - prev) / dt;
                if(rate >= 0.5f){
                    top.add(item);
                    rates.add(rate);
                }
            }
            top.sort((a, b) -> Float.compare(rates.get(top.indexOf(b)), rates.get(top.indexOf(a))));
            for(int i = 0; i < Math.min(4, top.size); i++){
                sb.append("[white]+").append((int)rates.get(i)).append("/с[] ").append(top.get(i).emoji()).append(" ");
            }
        }
        for(Item item : content.items()){
            lastItems.put(item.id, core.items.get(item));
        }
        incomeTime = Time.time;
        incomeText = sb.length() == 0 ? "[lightgray]доход стабилен" : sb.toString();
    }

    // ============================ КООРДИНАТЫ ============================

    private static float sx(float wx){
        return mapX + wx / (world.width() * tilesize) * mapW;
    }

    private static float sy(float wy){
        //.scene-координаты: y растёт вверх; row0 текстуры (север) рисуется сверху,
        //поэтому север мира = верх прямоугольника карты = mapY + mapH
        return mapY + (wy / (world.height() * tilesize)) * mapH;
    }

    private static Vec2 toWorld(Element e, float lx, float ly){
        float px = e.x + lx, py = e.y + ly;
        return tmp.set((px - mapX) / mapW * (world.width() * tilesize),
            (py - mapY) / mapH * (world.height() * tilesize));
    }

    // ============================ ВКЛАДКА ============================

    public static void build(Table t){
        ensureMap();
        MKUKICSettings s = MKUKICSettings.s();

        float sceneW = Core.scene.getWidth(), sceneH = Core.scene.getHeight();
        float mapW = Math.max(420f, sceneW * 0.96f - 186f - 350f - 46f);
        float mapH = Math.max(360f, sceneH * 0.90f - 118f);

        t.table(c -> {
            c.top().left();
            MapElement map = new MapElement();
            c.add(map).size(mapW, mapH).top().left().pad(4f);

            Table side = sidePanel(s);
            ScrollPane sp = new ScrollPane(side, MKUKICSkin.scroll);
            sp.setScrollingDisabled(true, false);
            sp.setFadeScrollBars(true);
            c.add(sp).width(344f).growY().top().left().pad(4f);
        }).top().left();
    }

    private static Table sidePanel(MKUKICSettings s){
        Table side = new Table();
        side.top().left();
        side.setBackground(MKUKICSkin.panelSoft);

        side.add("[#ffb454]◆ [#e8ecf4]ПРОГНОЗ ВОЛН").left().pad(10f, 10f, 4f, 10f).row();
        side.image(MKUKICSkin.div).height(2f).growX().pad(0f, 10f, 6f, 10f).row();

        Label wave = new Label("", Styles.outlineLabel);
        wave.setText(() -> { ensureTexts(); return waveStr; });
        side.add(wave).left().top().wrap().width(310f).pad(0f, 10f, 6f, 10f).row();

        Label comp = new Label("", Styles.outlineLabel);
        comp.setText(() -> { ensureTexts(); return compStr; });
        side.add(comp).left().top().wrap().width(310f).padBottom(6f).row();

        Label threat = new Label("", Styles.outlineLabel);
        threat.setText(() -> { ensureTexts(); return threatStr; });
        side.add(threat).left().top().wrap().width(310f).padBottom(8f).row();

        side.image(MKUKICSkin.div).height(2f).growX().pad(2f, 10f, 6f, 10f).row();
        side.add("[#ffb454]◆ [#e8ecf4]ЭКОНОМИКА ЯДРА").left().pad(0f, 10f, 4f, 10f).row();
        Label income = new Label("", Styles.outlineLabel);
        income.setText(() -> {
            if(Time.time - incomeTime > 60f) refreshIncome();
            return incomeText;
        });
        side.add(income).left().top().wrap().width(310f).pad(0f, 10f, 8f, 10f).row();

        side.image(MKUKICSkin.div).height(2f).growX().pad(2f, 10f, 6f, 10f).row();
        side.add("[#ffb454]◆ [#e8ecf4]ВАША АРМИЯ").left().pad(0f, 10f, 4f, 10f).row();
        Label army = new Label("", Styles.outlineLabel);
        army.setText(() -> { ensureTexts(); return armyStr; });
        side.add(army).left().top().wrap().width(310f).pad(0f, 10f, 8f, 10f).row();

        side.image(MKUKICSkin.div).height(2f).growX().pad(2f, 10f, 6f, 10f).row();
        side.add("[#ffb454]◆ [#e8ecf4]БЫСТРЫЕ ПРИКАЗЫ").left().pad(0f, 10f, 4f, 10f).row();
        side.button(Ico.of("move") + " Собрать всех ко мне", MKUKICSkin.actBtn, UnitAI::gatherToPlayer)
            .width(250f).height(36f).pad(3f).left().row();
        side.button(Ico.of("home") + " Всех к ядру (отступление)", MKUKICSkin.actBtn, UnitAI::retreatToCore)
            .width(250f).height(36f).pad(3f).left().row();
        side.button(Ico.of("units") + " Разделить на отряды", MKUKICSkin.actBtn, () -> {
            UnitAI.makeSquads();
            MKUKICSettings st = MKUKICSettings.s();
            st.aiSquads = !UnitAI.squads.isEmpty();
            MKUKICSettings.save();
        }).width(250f).height(36f).pad(3f).left().row();
        side.button(Ico.of("cancel") + " Распустить отряды", MKUKICSkin.actBtn, UnitAI::clearSquads)
            .width(250f).height(36f).pad(3f).left().row();
        side.button(Ico.of("defense") + " Анализ обороны: слабые секторы", MKUKICSkin.actBtn, TacticalHQ::analyzeDefense)
            .width(250f).height(36f).pad(3f).left().row();

        side.image(MKUKICSkin.div).height(2f).growX().pad(6f, 10f, 6f, 10f).row();
        side.add("[#ffb454]◆ [#e8ecf4]СЛОИ КАРТЫ").left().pad(0f, 10f, 4f, 10f).row();
        hqCheck(side, " Рудные жилы", s.hqOre, v -> s.hqOre = v);
        hqCheck(side, " Радиусы турелей", s.hqRanges, v -> s.hqRanges = v);
        hqCheck(side, " Сектора обороны", s.hqSectors, v -> s.hqSectors = v);
        hqCheck(side, " Радар-развёртка", s.hqSweep, v -> s.hqSweep = v);
        hqCheck(side, " Алерт за 10с до волны", s.hqWaveAlert, v -> s.hqWaveAlert = v);

        side.image(MKUKICSkin.div).height(2f).growX().pad(6f, 10f, 6f, 10f).row();
        side.add("[darkgray]ЛКМ по карте — приказ выделенным юнитам в точку.\n" +
            "ПКМ — перенести линию обороны отрядов кольцом.\n" +
            "Наведение — справка по юниту и координатам.\n" +
            "Красные ромбы — высадки, стрелки — прогноз атаки.\n" +
            "Оранжевые точки — повреждённый транспорт.\n" +
            "Дуги вокруг ядра — прикрытость секторов турелями.").left().top().wrap().width(310f).pad(0f, 10f, 10f, 10f).row();
        return side;
    }

    private static void hqCheck(Table side, String title, boolean val, arc.func.Boolc set){
        side.check(title, val, v -> {
            set.get(v);
            MKUKICSettings.save();
        }).left().pad(2f, 0f, 2f, 12f).row();
    }

    // ============================ ТЕКСТЫ ============================

    private static String waveText(){
        if(state == null || state.isMenu()) return "";
        if(!state.rules.waves) return "[lightgray]Волны на этой карте отключены";
        return "[white]Следующая волна: [accent]" + (state.wave + 1) +
            "[]\n[white]Удар через: [scarlet]" + (int)(state.wavetime / 60f) + " сек[]" +
            "\n[lightgray]Врагов на карте сейчас: [scarlet]" + state.enemies + "[]";
    }

    private static final ObjectFloatMap<String> topUnits = new ObjectFloatMap<>();
    private static final Seq<String> topKeys = new Seq<>();

    private static int[] countWave(int w){
        int ground = 0, air = 0, shields = 0;
        topUnits.clear();
        try{
            for(SpawnGroup g : state.rules.spawns){
                if(g == null || g.type == null) continue;
                int n = 0;
                try{
                    if(g.canSpawn(w)) n = g.getSpawned(w);
                }catch(Throwable ignored){}
                if(n <= 0) continue;
                float sh = 0f;
                try{
                    sh = g.getShield(w);
                }catch(Throwable ignored){}
                if(g.type.flying) air += n; else ground += n;
                shields += (int)(sh * n);
                String key = g.type.localizedName + (sh > 1f ? " [щит " + (int)sh + "]" : "");
                topUnits.put(key, topUnits.get(key, 0f) + n);
            }
        }catch(Throwable ignored){}
        return new int[]{ground, air, shields};
    }

    private static String compositionText(){
        if(state == null || state.isMenu()) return "";
        if(!state.rules.waves) return "";
        int[] c = countWave(state.wave + 1);
        StringBuilder sb = new StringBuilder();
        if(c[0] + c[1] > 0){
            sb.append("[white]Состав: [stat]" + c[0] + "[] наземных, [sky]" + c[1] + "[] воздушных\n");
            if(c[2] > 0) sb.append("[white]Суммарные щиты: [sky]" + c[2] + "[]\n");
            topKeys.clear();
            for(String k : topUnits.keys()) topKeys.add(k);
            topKeys.sort((a, b) -> Float.compare(topUnits.get(b, 0f), topUnits.get(a, 0f)));
            for(int i = 0; i < Math.min(4, topKeys.size); i++){
                String k = topKeys.get(i);
                sb.append("[lightgray]  " + k + " ×[white]" + (int)topUnits.get(k, 0f) + "\n");
            }
        }else{
            // карта/сервер скрывает состав волны: показываем врагов, которые уже в бою
            sb.append("[lightgray]Волна скрыта правилами карты.\n[white]В бою сейчас:[]\n");
            ObjectIntMap<String> live = new ObjectIntMap<>();
            Groups.unit.each(u -> {
                if(u != null && !u.dead() && u.team() != player.team() && u.hittable()){
                    live.put(u.type.localizedName, live.get(u.type.localizedName, 0) + 1);
                }
            });
            topKeys.clear();
            for(String k : live.keys()) topKeys.add(k);
            topKeys.sort((a, b) -> Integer.compare(live.get(b, 0), live.get(a, 0)));
            if(topKeys.isEmpty()) sb.append("[lightgray]  врагов не видно");
            for(int i = 0; i < Math.min(5, topKeys.size); i++){
                String k = topKeys.get(i);
                sb.append("[scarlet]  " + k + " ×" + live.get(k, 0) + "\n");
            }
        }
        return sb.toString();
    }

    private static String threatText(){
        if(state == null || state.isMenu() || !state.rules.waves) return "";
        int[] c = countWave(state.wave + 1);
        int total = c[0] + c[1];
        StringBuilder sb = new StringBuilder();
        if(total >= 40) sb.append("[scarlet]⚠ МАССИРОВАННАЯ АТАКА — " + total + " юнитов![]\n");
        else if(total >= 15) sb.append("[orange]⚠ Серьёзная волна: " + total + " юнитов[]\n");
        if(c[1] > 0) sb.append("[sky]Воздух: " + c[1] + " — проверьте ПВО![]\n");
        if(c[2] > total * 150) sb.append("[purple]Много щитов — нужны бронебойные[]\n");
        if(state.wavetime < 600f) sb.append("[scarlet]УДАР БЛИЗКО — по местам![]\n");
        if(sb.length() == 0 && total > 0) sb.append("[green]Угроза умеренная");
        return sb.toString();
    }

    private static void ensureTexts(){
        if(Time.time - textTime < 30f) return;
        textTime = Time.time;
        waveStr = waveText();
        compStr = compositionText();
        threatStr = threatText();
        armyStr = armyText();
    }

    /** Сводка по вашей армии: типы и количество командных юнитов. */
    private static String armyText(){
        if(state == null || state.isMenu() || player == null) return "";
        ObjectIntMap<String> m = new ObjectIntMap<>();
        int total = 0, air = 0;
        for(Unit u : UnitAI.managed()){
            if(u == null || u.dead()) continue;
            total++;
            if(u.type.flying) air++;
            m.put(u.type.localizedName, m.get(u.type.localizedName, 0) + 1);
        }
        if(total == 0) return "[lightgray]Армия: только ваш юнит";
        Seq<String> keys = new Seq<>();
        for(String k : m.keys()) keys.add(k);
        keys.sort((a, b) -> Integer.compare(m.get(b, 0), m.get(a, 0)));
        StringBuilder sb = new StringBuilder("[white]Армия: [accent]" + total + "[] (воздух: [sky]" + air + "[])\n");
        for(int i = 0; i < Math.min(5, keys.size); i++){
            sb.append("[lightgray]  " + keys.get(i) + " ×[white]" + m.get(keys.get(i), 0) + "\n");
        }
        return sb.toString();
    }

    /** Кнопка: найти слабые сектора обороны вокруг ядра. */
    public static void analyzeDefense(){
        if(state == null || state.isMenu() || player == null) return;
        turretCacheTime = -9999f;
        sectorTime = -9999f;
        computeSectors();
        if(!sectorOk){
            MKUKICMod.toast("Нет ядра — анализ невозможен");
            return;
        }
        int worst = 0;
        for(int i = 1; i < 16; i++){
            if(sectorCov[i] < sectorCov[worst]) worst = i;
        }
        int open = 0;
        for(int i = 0; i < 16; i++){
            if(sectorCov[i] < 0.34f) open++;
        }
        int deg = worst * 22 + 11;
        if(open == 0){
            MKUKICMod.toast("[green]Оборона сплошная: все 16 секторов прикрыты[]");
        }else{
            MKUKICMod.toast("[scarlet]Слабый сектор: азимут ~" + deg + "° (прикрытие " +
                (int)(sectorCov[worst] * 3) + "/3). Открытых секторов: " + open + "/16[]");
        }
    }

    /** Алерт за 10 секунд до волны (вызывается из клиентского тика). */
    public static void waveAlertTick(){
        if(state == null || state.isMenu() || !state.rules.waves) return;
        if(!MKUKICSettings.s().hqWaveAlert) return;
        if(state.wavetime < 600f && state.wavetime > 0f && lastAlertWave != state.wave){
            lastAlertWave = state.wave;
            int[] c = countWave(state.wave + 1);
            String extra = c[1] > 0 ? " (воздух: " + c[1] + ")" : "";
            MKUKICMod.toast("[scarlet]⚠ Волна " + (state.wave + 1) + " через 10 секунд!" + extra + "[]");
        }
    }

    // ============================ ПРИКАЗЫ КЛИКОМ ============================

    private static void commandSelected(float wx, float wy){
        if(player == null || player.unit() == null) return;
        IntSeq ids = new IntSeq();
        for(Unit u : UnitAI.managed()){
            if(u != player.unit() && !u.dead() && u.controller() instanceof CommandAI) ids.add(u.id);
        }
        if(ids.size == 0){
            MKUKICMod.toast("Нет выделенных юнитов — выдели рамкой или «Выделить всех рядом»");
            return;
        }
        int[] all = ids.toArray();
        for(int i = 0; i < all.length; i += 40){
            int[] chunk = new int[Math.min(40, all.length - i)];
            System.arraycopy(all, i, chunk, 0, chunk.length);
            Call.commandUnits(player, chunk, null, null, new Vec2(wx, wy), false, true);
        }
        MKUKICMod.toast("Приказ: " + all.length + " юнитов → точка (" + (int)(wx / tilesize) + ", " + (int)(wy / tilesize) + ")");
    }

    private static void moveSquadLine(float wx, float wy){
        if(UnitAI.squads.isEmpty()){
            MKUKICMod.toast("Отрядов нет — нажми «Разделить на отряды»");
            return;
        }
        int K = UnitAI.squads.size;
        for(int i = 0; i < K; i++){
            UnitAI.Squad sq = UnitAI.squads.get(i);
            float ang = 360f * i / K + 45f;
            float r = 130f + (K > 4 ? 60f : 0f);
            sq.point.set(wx + Angles.trnsx(ang, r), wy + Angles.trnsy(ang, r));
            sq.spawn = null;
        }
        MKUKICSettings s = MKUKICSettings.s();
        if(!s.aiSquads){
            s.aiSquads = true;
            MKUKICSettings.save();
        }
        MKUKICMod.toast("Линия обороны: " + K + " отрядов кольцом вокруг точки");
    }

    // ============================ ЭЛЕМЕНТ КАРТЫ ============================

    private static class MapElement extends Element{
        MapElement(){
            addListener(new InputListener(){
                @Override
                public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button){
                    if(state == null || state.isMenu() || world == null) return false;
                    Vec2 w = toWorld(MapElement.this, x, y);
                    if(w.x < 0 || w.y < 0 || w.x > world.width() * tilesize || w.y > world.height() * tilesize) return false;
                    if(button == KeyCode.mouseRight) moveSquadLine(w.x, w.y);
                    else if(button == KeyCode.mouseLeft) commandSelected(w.x, w.y);
                    return true;
                }

                @Override
                public void enter(InputEvent event, float x, float y, int pointer, Element fromActor){
                    hoverIn = true;
                }

                @Override
                public void exit(InputEvent event, float x, float y, int pointer, Element toActor){
                    hoverIn = false;
                }

                @Override
                public boolean mouseMoved(InputEvent event, float x, float y){
                    hoverLX = x;
                    hoverLY = y;
                    hoverIn = true;
                    return false;
                }
            });
        }

        @Override
        public void draw(){
            super.draw();
            if(state == null || state.isMenu() || world == null) return;
            ensureMap();
            MKUKICSettings s = MKUKICSettings.s();

            float w = getWidth(), h = getHeight();

            Draw.color(Color.white);
            MKUKICSkin.panel.draw(x, y, w, h);
            Draw.color(0.02f, 0.03f, 0.06f, 1f);
            Fill.quad(x + 3f, y + 3f, x + w - 3f, y + 3f, x + w - 3f, y + h - 3f, x + 3f, y + h - 3f);

            if(mapTex != null && mapRegion != null){
                float aspect = texW / (float)texH;
                float dw = w - 10f, dh = dw / aspect;
                if(dh > h - 10f){
                    dh = h - 10f;
                    dw = dh * aspect;
                }
                mapX = x + (w - dw) / 2f;
                mapY = y + (h - dh) / 2f;
                mapW = dw;
                mapH = dh;
                Draw.color(Color.white);
                Draw.rect(mapRegion, mapX + dw / 2f, mapY + dh / 2f, dw, dh);
            }else{
                mapX = x + 5f; mapY = y + 5f; mapW = w - 10f; mapH = h - 10f;
                Draw.color(Pal.accent);
                Fonts.def.draw("карта недоступна", x + w / 2f - 30f, y + h / 2f);
            }

            drawOre(s);
            drawTurretRanges(s);
            drawSectors(s);
            drawSpawns();
            drawDamaged();
            drawUnits();
            drawSquads();
            drawSweep(s);
            drawWaveFlash();
            drawHover();

            Draw.color();
        }

        // ---- руда ----
        private void drawOre(MKUKICSettings s){
            if(!s.hqOre || mapTex == null) return;
            for(int i = 0; i < oreX.size; i++){
                Draw.color(tmpC.set(oreC.get(i)));
                Fill.circle(sx(oreX.get(i)), sy(oreY.get(i)), 1.1f);
            }
        }

        // ---- радиусы турелей ----
        private void drawTurretRanges(MKUKICSettings s){
            if(!s.hqRanges) return;
            if(turretCacheTime < Time.time - 90f){
                turretCacheTime = Time.time;
                refreshTurrets();
            }
            Lines.stroke(1f);
            Draw.color(Pal.accent, 0.22f);
            float scale = mapW / (world.width() * tilesize);
            for(int i = 0; i < turretCache.size; i++){
                Building b = turretCache.get(i);
                Lines.circle(sx(b.x), sy(b.y), turretRange.get(i) * scale);
            }
        }

        // ---- сектора обороны ----
        private void drawSectors(MKUKICSettings s){
            if(!s.hqSectors) return;
            if(sectorTime < Time.time - 150f){
                sectorTime = Time.time;
                computeSectors();
            }
            if(!sectorOk) return;
            Lines.stroke(2.5f);
            for(int i = 0; i < 16; i++){
                float cov = sectorCov[i];
                Draw.color(Color.scarlet.cpy().lerp(Color.valueOf("74ff60"), cov), 0.75f);
                float a0 = i * 22.5f + 3f, a1 = (i + 1) * 22.5f - 3f;
                float px = 0, py = 0;
                for(int k = 0; k <= 4; k++){
                    float ang = Mathf.lerp(a0, a1, k / 4f);
                    float cx = sx(sectorCoreX + Angles.trnsx(ang, 620f));
                    float cy = sy(sectorCoreY + Angles.trnsy(ang, 620f));
                    if(k > 0) Lines.line(px, py, cx, cy);
                    px = cx;
                    py = cy;
                }
            }
        }

        // ---- точки высадки + стрелки ----
        private void drawSpawns(){
            float pulse = 1f + Mathf.sin(Time.time / 7f) * 0.35f;
            CoreBuild core = player.team().data().lastCore;
            for(Tile sp : spawns()){
                float ex = sx(sp.worldx()), ey = sy(sp.worldy());
                Draw.color(Color.scarlet);
                float r = 4.5f * pulse;
                Fill.quad(ex, ey + r, ex + r, ey, ex, ey - r, ex - r, ey);
                if(core != null){
                    float cx = sx(core.x), cy = sy(core.y);
                    float ax = ex + (cx - ex) * 0.28f, ay = ey + (cy - ey) * 0.28f;
                    Lines.stroke(2.2f);
                    Draw.color(Color.scarlet, 0.55f);
                    Lines.line(ex, ey, ax, ay);
                    Draw.color(Color.scarlet);
                    float ang = Angles.angle(ex, ey, ax, ay);
                    Fill.tri(ax + Angles.trnsx(ang, 5f), ay + Angles.trnsy(ang, 5f),
                        ax + Angles.trnsx(ang + 145f, 4.5f), ay + Angles.trnsy(ang + 145f, 4.5f),
                        ax + Angles.trnsx(ang - 145f, 4.5f), ay + Angles.trnsy(ang - 145f, 4.5f));
                }
            }
        }

        // ---- повреждённый транспорт ----
        private void drawDamaged(){
            if(Time.time - dmgScanTime > 120f){
                dmgScanTime = Time.time;
                UnitAI.scanTransports();
            }
            float pulse = 0.6f + Mathf.absin(Time.time / 5f, 1f, 0.4f);
            Draw.color(1f, 0.55f, 0.15f, pulse);
            for(Building b : UnitAI.damagedTransports){
                if(b == null) continue;
                Fill.circle(sx(b.x), sy(b.y), 1.8f);
            }
        }

        // ---- юниты ----
        private void drawUnits(){
            Unit self = player.unit();
            Groups.unit.each(u -> {
                if(u == null || !u.isAdded() || u.dead()) return;
                float ux = sx(u.x), uy = sy(u.y);
                if(u == self){
                    Draw.color(Color.white);
                    Fill.circle(ux, uy, 3.6f);
                    Draw.color(Pal.accent);
                    Fill.circle(ux, uy, 2.2f);
                }else if(u.team() == player.team()){
                    Draw.color(0.45f, 1f, 0.4f, 0.95f);
                    Fill.circle(ux, uy, u.type.hitSize / 14f + 1.4f);
                }else{
                    Draw.color(Color.scarlet);
                    Fill.circle(ux, uy, u.type.hitSize / 14f + 1.4f);
                }
            });
        }

        // ---- отряды ----
        private void drawSquads(){
            for(int i = 0; i < UnitAI.squads.size; i++){
                UnitAI.Squad sq = UnitAI.squads.get(i);
                float px = sx(sq.point.x), py = sy(sq.point.y);
                Draw.color(0.3f, 1f, 0.55f, 0.85f);
                Fill.circle(px, py, 5f);
                Draw.color(Color.black);
                Fill.circle(px, py, 3.6f);
                Draw.color(0.3f, 1f, 0.55f);
                Fonts.def.draw("" + (i + 1), px - 3f, py + 4.4f);
            }
        }

        // ---- радар-развёртка ----
        private void drawSweep(MKUKICSettings s){
            if(!s.hqSweep || player.unit() == null) return;
            Unit self = player.unit();
            float cx = sx(self.x), cy = sy(self.y);
            float ang = (Time.time * 2.2f) % 360f;
            float len = Math.min(mapW, mapH) * 0.45f;
            Lines.stroke(1.6f);
            for(int k = 0; k < 10; k++){
                float a = ang - k * 2.4f;
                Draw.color(Pal.accent, 0.35f * (1f - k / 10f));
                Lines.line(cx, cy, cx + Angles.trnsx(a, len), cy + Angles.trnsy(a, len));
            }
        }

        // ---- вспышка краёв перед волной ----
        private void drawWaveFlash(){
            if(!state.rules.waves || state.wavetime >= 600f) return;
            float a = 0.25f + Mathf.absin(Time.time / 4f, 1f, 0.25f);
            Draw.color(Color.scarlet, a);
            float w = getWidth(), h = getHeight();
            Fill.quad(x + 3f, y + h - 6f, x + w - 3f, y + h - 6f, x + w - 3f, y + h - 3f, x + 3f, y + h - 3f);
            Fill.quad(x + 3f, y + 3f, x + w - 3f, y + 3f, x + w - 3f, y + 6f, x + 3f, y + 6f);
            Fill.quad(x + 3f, y + 3f, x + 6f, y + 3f, x + 6f, y + h - 3f, x + 3f, y + h - 3f);
            Fill.quad(x + w - 6f, y + 3f, x + w - 3f, y + 3f, x + w - 3f, y + h - 3f, x + w - 6f, y + h - 3f);
        }

        // ---- подсказка при наведении ----
        private void drawHover(){
            if(!hoverIn || hoverLX < 0) return;
            float px = x + hoverLX, py = y + hoverLY;
            if(px < mapX || px > mapX + mapW || py < mapY || py > mapY + mapH) return;

            // ближайший юнит в 12px
            Unit best = null;
            float bd = 12f * 12f;
            for(Unit u : Groups.unit){
                if(u == null || u.dead()) continue;
                float d = Mathf.dst2(sx(u.x), sy(u.y), px, py);
                if(d < bd){
                    bd = d;
                    best = u;
                }
            }
            Vec2 wc = toWorld(this, hoverLX, hoverLY);
            String line1, line2;
            if(best != null){
                line1 = best.type.localizedName + (best.team() == player.team() ? " [green](свой)[]" : " [scarlet](враг)[]");
                line2 = "HP " + (int)best.health() + "/" + (int)best.maxHealth() +
                    "  [" + (int)(best.x / tilesize) + ", " + (int)(best.y / tilesize) + "]";
            }else{
                line1 = "[lightgray]тайл [" + (int)(wc.x / tilesize) + ", " + (int)(wc.y / tilesize) + "][]";
                line2 = "";
            }
            float tw = 150f;
            Draw.color(0.04f, 0.05f, 0.09f, 0.92f);
            Fill.quad(px + 8f, py - 4f, px + 8f + tw, py - 4f, px + 8f + tw, py + 30f, px + 8f, py + 30f);
            Draw.color(Pal.accent, 0.8f);
            Fill.quad(px + 8f, py + 28f, px + 8f + tw, py + 28f, px + 8f + tw, py + 30f, px + 8f, py + 30f);
            Draw.color(Color.white);
            Fonts.def.draw(line1, px + 12f, py + 22f);
            if(!line2.isEmpty()) Fonts.def.draw(line2, px + 12f, py + 10f);
        }
    }
}
