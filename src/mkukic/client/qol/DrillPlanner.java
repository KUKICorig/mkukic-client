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
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.ui.*;
import mindustry.world.*;
import mindustry.world.blocks.production.*;
import mindustry.world.meta.*;
import mkukic.*;
import mkukic.client.*;

import static mindustry.Vars.*;

/**
 * Буровой планер (порт MindustryTool Smart Drill).
 *
 * Анализирует карту под выбранный бур: для каждой клетки считает, сколько рудных
 * тайлов накроет его «пятка» (size×size, как у ванильного Drill.canMine/getDrop),
 * рисует тепловую карту и список лучших точек (циклический обход камерой).
 *
 * Анализ идёт порциями по кадрам (без потоков), оверлей — только на видимую область.
 * Чистая математика (heatFor/collectBest) покрыта FeatureTest.
 */
public class DrillPlanner{
    private static final int rowsPerTick = 64;

    private static Drill drill;
    private static boolean[] mineable, blocked;
    private static short[] heat;
    private static int[][] best;      // {{x,y,score},...} по убыванию score
    private static int bestIdx;
    private static int w, h;
    private static int phase, rowCursor; // phase 0 = idle, 1 = mineable, 2 = heat
    private static boolean ready;

    private static final Rect viewRect = new Rect();

    // ============================ ЧИСТАЯ ЛОГИКА (тестируется) ============================

    /** Смещения «пятки» бура размера size относительно центрального тайла (как в игре: i - (size-1)/2). */
    public static int footprintOffset(int i, int size){
        return i - (size - 1) / 2;
    }

    /**
     * Тепловая карта: для каждой клетки — сколько mineable-тайлов накроет бур size×size
     * с центром в клетке. -1 = ставить нельзя (blocked-пятка или выход за край).
     */
    public static short[] heatFor(boolean[] mineable, boolean[] blocked, int w, int h, int size){
        short[] heat = new short[w * h];
        for(int y = 0; y < h; y++){
            for(int x = 0; x < w; x++){
                boolean ok = true;
                int cnt = 0;
                for(int j = 0; j < size; j++){
                    int oy = y + footprintOffset(j, size);
                    if(oy < 0 || oy >= h){ ok = false; break; }
                    for(int i = 0; i < size; i++){
                        int ox = x + footprintOffset(i, size);
                        if(ox < 0 || ox >= w){ ok = false; break; }
                        int idx = oy * w + ox;
                        if(blocked[idx]){ ok = false; break; }
                        if(mineable[idx]) cnt++;
                    }
                    if(!ok) break;
                }
                heat[y * w + x] = ok ? (short)cnt : (short)-1;
            }
        }
        return heat;
    }

    /** Топ-limit клеток по score (>0), сортировка по убыванию. Возвращает {{x,y,score},...}. */
    public static int[][] collectBest(short[] heat, int w, int h, int limit){
        int[][] top = new int[Math.max(1, limit)][3];
        int n = 0;
        for(int i = 0; i < top.length; i++) top[i][2] = -1;
        for(int y = 0; y < h; y++){
            for(int x = 0; x < w; x++){
                int v = heat[y * w + x];
                if(v <= 0) continue;
                if(n < top.length){
                    top[n][0] = x; top[n][1] = y; top[n][2] = v;
                    n++;
                    // всплытие
                    for(int k = n - 1; k > 0 && top[k][2] > top[k - 1][2]; k--){
                        int[] t = top[k]; top[k] = top[k - 1]; top[k - 1] = t;
                    }
                }else if(v > top[top.length - 1][2]){
                    top[top.length - 1][0] = x; top[top.length - 1][1] = y; top[top.length - 1][2] = v;
                    for(int k = top.length - 1; k > 0 && top[k][2] > top[k - 1][2]; k--){
                        int[] t = top[k]; top[k] = top[k - 1]; top[k - 1] = t;
                    }
                }
            }
        }
        int cnt = 0;
        for(int[] e : top) if(e[2] > 0) cnt++;
        int[][] out = new int[cnt][3];
        System.arraycopy(top, 0, out, 0, cnt);
        return out;
    }

    // ============================ АНАЛИЗ ============================

    public static Drill currentDrill(){
        MKUKICSettings s = MKUKICSettings.s();
        Block b = content != null ? content.block(s.drillName) : null;
        if(b instanceof Drill) return (Drill)b;
        if(content != null){
            for(Block bb : content.blocks()){
                if(bb instanceof Drill) return (Drill)bb;
            }
        }
        return null;
    }

    public static Seq<Drill> allDrills(){
        Seq<Drill> out = new Seq<>();
        if(content == null) return out;
        for(Block b : content.blocks()){
            if(b instanceof Drill && b.buildVisibility == BuildVisibility.shown) out.add((Drill)b);
        }
        return out;
    }

    public static boolean isReady(){ return ready; }
    public static boolean isComputing(){ return phase > 0; }
    public static int spotsCount(){ return best == null ? 0 : best.length; }

    public static float progress(){
        if(h <= 0) return 0f;
        float base = phase == 1 ? 0f : 0.5f;
        return Mathf.clamp(base + 0.5f * rowCursor / h, 0f, 1f);
    }

    public static void startAnalysis(){
        if(headless || world == null || world.tiles == null || state == null || state.isMenu()) return;
        drill = currentDrill();
        if(drill == null){
            MKUKICMod.toast("[scarlet]Буры не найдены");
            return;
        }
        w = world.width();
        h = world.height();
        mineable = new boolean[w * h];
        blocked = new boolean[w * h];
        heat = null;
        best = null;
        bestIdx = 0;
        ready = false;
        phase = 1;
        rowCursor = 0;
    }

    /** Порция анализа каждый кадр (из Qol.tick). */
    public static void tick(){
        if(phase == 0 || world == null) return;
        int rows = 0;
        if(phase == 1){
            while(rowCursor < h && rows < rowsPerTick){
                int y = rowCursor++;
                rows++;
                for(int x = 0; x < w; x++){
                    Tile t = world.tile(x, y);
                    if(t == null) continue;
                    int idx = y * w + x;
                    blocked[idx] = t.solid();
                    try{
                        mineable[idx] = drill.canMine(t);
                    }catch(Throwable e){
                        mineable[idx] = false;
                    }
                }
            }
            if(rowCursor >= h){
                phase = 2;
                rowCursor = 0;
            }
        }else if(phase == 2){
            if(heat == null) heat = new short[w * h];
            while(rowCursor < h && rows < rowsPerTick){
                int y = rowCursor++;
                rows++;
                for(int x = 0; x < w; x++){
                    heat[y * w + x] = heatAt(x, y);
                }
            }
            if(rowCursor >= h){
                phase = 0;
                ready = true;
                best = collectBest(heat, w, h, 24);
                MKUKICMod.toast("[accent]Буровой анализ готов[]: " + best.length + " лучших точек (" + drill.localizedName + ")");
            }
        }
    }

    private static short heatAt(int x, int y){
        int size = drill.size;
        int cnt = 0;
        for(int j = 0; j < size; j++){
            int oy = y + footprintOffset(j, size);
            if(oy < 0 || oy >= h) return -1;
            for(int i = 0; i < size; i++){
                int ox = x + footprintOffset(i, size);
                if(ox < 0 || ox >= w) return -1;
                int idx = oy * w + ox;
                if(blocked[idx]) return -1;
                if(mineable[idx]) cnt++;
            }
        }
        return (short)cnt;
    }

    public static void reset(){
        phase = 0;
        ready = false;
        mineable = null;
        blocked = null;
        heat = null;
        best = null;
    }

    /** Камера к следующей лучшей точке (цикл). */
    public static void jumpBest(){
        if(best == null || best.length == 0){
            MKUKICMod.toast("[scarlet]Сначала выполни анализ");
            return;
        }
        int[] e = best[bestIdx % best.length];
        bestIdx = (bestIdx + 1) % best.length;
        if(Core.camera != null) Core.camera.position.set(e[0] * tilesize + tilesize / 2f, e[1] * tilesize + tilesize / 2f);
        MKUKICMod.toast("[accent]Точка " + (bestIdx == 0 ? best.length : bestIdx) + "/" + best.length + ": руды " + e[2] + "/" + (drill != null ? drill.size * drill.size : "?"));
    }

    // ============================ ОТРИСОВКА ============================

    public static void draw(){
        MKUKICSettings s = MKUKICSettings.s();
        if(!s.enabled || !s.qolEnabled || !s.qolDrillOverlay) return;
        if(!ready || heat == null || world == null || state == null || state.isMenu()) return;

        Core.camera.bounds(viewRect);
        viewRect.grow(32f);
        int x0 = Math.max(0, (int)(viewRect.x / tilesize));
        int y0 = Math.max(0, (int)(viewRect.y / tilesize));
        int x1 = Math.min(w - 1, (int)((viewRect.x + viewRect.width) / tilesize));
        int y1 = Math.min(h - 1, (int)((viewRect.y + viewRect.height) / tilesize));

        int area = drill != null ? drill.size * drill.size : 4;
        Draw.z(Layer.overlayUI);
        Lines.stroke(1f);
        for(int y = y0; y <= y1; y++){
            for(int x = x0; x <= x1; x++){
                int v = heat[y * w + x];
                if(v <= 0) continue;
                float t = Mathf.clamp((float)v / area, 0f, 1f);
                Draw.color(Tmp.c1.set(Color.scarlet).lerp(Color.lime, t));
                Draw.alpha(0.5f);
                Lines.rect(x * tilesize + 0.5f, y * tilesize + 0.5f, tilesize - 1f, tilesize - 1f);
            }
        }
        // лучшие точки — маркеры
        if(best != null){
            for(int i = 0; i < best.length; i++){
                int[] e = best[i];
                float wx = e[0] * tilesize + tilesize / 2f, wy = e[1] * tilesize + tilesize / 2f;
                if(!viewRect.contains(wx, wy)) continue;
                Draw.color(i == bestIdx % Math.max(1, best.length) ? Color.gold : Color.white, 0.8f);
                Lines.stroke(i == bestIdx % Math.max(1, best.length) ? 2f : 1f);
                Lines.circle(wx, wy, tilesize * 1.2f);
            }
        }
        Draw.color();
        Lines.stroke(1f);
    }

    // ============================ ОКНО ============================

    public static void build(Table t){
        QolWindows.section(t, "Буровой планер (Smart Drill)");
        t.add("[lightgray]Считает, сколько руды накроет каждый бур, рисует тепловую\n"
            + "карту и находит лучшие точки. Пересчитывай после изменения карты.\n"
            + "Красный = мало руды, зелёный = пятка полностью в руде.").left().top().wrap().width(340f).pad(3f, 0f, 6f, 0f).row();

        t.table(c -> {
            c.left();
            c.add("Бур:").padRight(6f);
        }).left().row();
        t.table(c -> {
            c.left();
            Seq<Drill> ds = allDrills();
            for(int i = 0; i < ds.size; i++){
                Drill d = ds.get(i);
                c.button(d.localizedName, MKUKICSkin.toggleBtn, () -> {
                    MKUKICSettings.s().drillName = d.name;
                    MKUKICSettings.save();
                    reset();
                }).checked(b -> MKUKICSettings.s().drillName.equals(d.name)).minWidth(120f).height(30f).pad(2f);
                if(i % 2 == 1) c.row();
            }
            if(ds.size % 2 == 1) c.row();
        }).left().row();

        t.table(c -> {
            c.left();
            c.button("Анализировать карту", MKUKICSkin.actBtn, DrillPlanner::startAnalysis)
                .minWidth(190f).height(36f).pad(2f);
            c.button("К лучшей точке", MKUKICSkin.actBtn, DrillPlanner::jumpBest)
                .minWidth(150f).height(36f).pad(2f);
        }).left().padTop(4f).row();

        t.check("Тепловая карта на экране", MKUKICSettings.s().qolDrillOverlay, v -> {
            MKUKICSettings.s().qolDrillOverlay = v;
            MKUKICSettings.save();
            if(v && !ready) startAnalysis();
        }).left().pad(4f, 2f, 2f, 2f).row();

        t.label(() -> {
            if(isComputing()) return "[accent]Анализ: " + (int)(progress() * 100f) + "%";
            if(ready && best != null) return "[lightgray]Готово: лучших точек " + best.length
                + (best.length > 0 ? ", топ-рудность " + best[0][2] : "");
            return "[darkgray]Ещё не анализировалось";
        }).left().padTop(4f).wrap().width(340f).row();
    }
}
