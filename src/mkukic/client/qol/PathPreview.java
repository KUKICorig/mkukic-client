package mkukic.client.qol;

import arc.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.math.*;
import arc.math.geom.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.util.*;

import java.util.Arrays;
import mindustry.ai.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.ui.*;
import mkukic.*;
import mkukic.client.*;

import static mindustry.Vars.*;

/**
 * Визуализатор маршрутов врага + узкие места (порт MindustryTool Pathfinding Visualizer).
 *
 * Использует СОБСТВЕННОЕ flow-поле игры: pathfinder.getField(waveTeam, costGround, fieldCore).
 * Веса (public int[] weights) — «расстояние» до ядра; спуск к соседу с меньшим весом =
 * шаг врага. Стрелки рисуются разреженно (каждые step тайлов), «узкие места» — клетки,
 * в которые стекается много соседних маршрутов (конвергенция).
 *
 * Анализ выполняется порциями по кадрам (без потоков — читаем только то, что игра и так
 * хранит), результат кэшируется; пересчёт — кнопкой или при загрузке мира.
 * Вся математика — чистые функции, покрыты FeatureTest.
 */
public class PathPreview{
    /** Pathfinder.impassable — package-private в игре; значение стабильно: 0xFFFF. */
    static final int impassable = 65535;
    /** Шаг сетки анализа/отрисовки (тайлы). */
    static final int step = 6;
    private static final int rowsPerTick = 48;

    /** 8 соседей: индексы в dx8/dy8. */
    public static final int[] dx8 = {1, 1, 0, -1, -1, -1, 0, 1};
    public static final int[] dy8 = {0, 1, 1, 1, 0, -1, -1, -1};

    // кэш анализа
    private static byte[] dirs;      // 0 = нет; 1..8 = индекс соседа+1
    private static byte[] chokes;    // конвергенция (0..8+)
    private static int sw, sh;       // размеры сетки анализа
    private static int rowCursor;
    private static boolean computing, ready;
    private static int[] wCache;     // снимок weights на момент анализа
    private static int wCacheW, wCacheH;

    private static final Rect viewRect = new Rect();

    // ============================ ЧИСТАЯ ЛОГИКА (тестируется) ============================

    /** Шаг спуска по flow-полю: индекс соседа (0..7) с меньшим весом, или -1 (тупик/цель/непроходимо). */
    public static int descend(int[] weights, int w, int h, int x, int y){
        if(weights == null || x < 0 || y < 0 || x >= w || y >= h) return -1;
        int cur = weights[y * w + x];
        if(cur >= impassable || cur <= 0) return -1;
        int best = -1, bw = cur;
        for(int i = 0; i < 8; i++){
            int nx = x + dx8[i], ny = y + dy8[i];
            if(nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
            int nw = weights[ny * w + nx];
            if(nw < bw){
                bw = nw;
                best = i;
            }
        }
        return best;
    }

    /**
     * Конвергенция клетки: сколько из 8 sampled-соседей (на расстоянии step) доходят
     * по цепочке спуска до (x,y) за ограниченное число шагов.
     */
    public static int convergence(int[] weights, int w, int h, int x, int y, int step){
        int cnt = 0;
        float maxD2 = (step * 3f) * (step * 3f);
        for(int i = 0; i < 8; i++){
            int cx = x + dx8[i] * step, cy = y + dy8[i] * step;
            if(cx < 0 || cy < 0 || cx >= w || cy >= h) continue;
            for(int s = 0; s < step * 3; s++){
                if(cx == x && cy == y){
                    cnt++;
                    break;
                }
                int d = descend(weights, w, h, cx, cy);
                if(d < 0) break;
                cx += dx8[d];
                cy += dy8[d];
                if(Mathf.dst2(cx - x, cy - y) > maxD2) break;
            }
        }
        return cnt;
    }

    // ============================ АНАЛИЗ ============================

    public static boolean isReady(){
        return ready;
    }

    public static boolean isComputing(){
        return computing;
    }

    public static float progress(){
        return sh == 0 ? 0f : Mathf.clamp((float)rowCursor / sh, 0f, 1f);
    }

    public static void startAnalysis(){
        if(headless || world == null || world.tiles == null || state == null || state.isMenu()) return;
        Pathfinder.Flowfield field = null;
        try{
            field = pathfinder.getField(state.rules.waveTeam, Pathfinder.costGround, Pathfinder.fieldCore);
        }catch(Throwable t){
            Log.err("[MKUKIC] PathPreview: getField", t);
        }
        if(field == null || field.weights == null || field.width <= 0){
            MKUKICMod.toast("[scarlet]Flow-поле ещё не готово — подожди пару секунд в игре");
            return;
        }
        int res = Math.max(1, field.resolution);
        wCacheW = field.width;
        wCacheH = field.height;
        // снимок весов в координатах ТАЙЛОВ мира (учитываем resolution поля)
        int ww = world.width(), hh = world.height();
        wCache = new int[ww * hh];
        for(int y = 0; y < hh; y++){
            int fy = Math.min(y / res, wCacheH - 1);
            for(int x = 0; x < ww; x++){
                int fx = Math.min(x / res, wCacheW - 1);
                int idx = fy * wCacheW + fx;
                wCache[y * ww + x] = idx < field.weights.length ? field.weights[idx] : impassable;
            }
        }
        sw = (ww + step - 1) / step;
        sh = (hh + step - 1) / step;
        dirs = new byte[sw * sh];
        chokes = new byte[sw * sh];
        Arrays.fill(dirs, (byte)0);
        computing = true;
        ready = false;
        rowCursor = 0;
    }

    /** Порция анализа каждый кадр (из Qol.tick). */
    public static void tick(){
        if(!computing) return;
        int ww = world != null ? world.width() : 0;
        int hh = world != null ? world.height() : 0;
        int done = 0;
        while(rowCursor < sh && done < rowsPerTick){
            int sy = rowCursor++;
            done++;
            for(int sx = 0; sx < sw; sx++){
                int tx = sx * step, ty = sy * step;
                if(tx >= ww || ty >= hh) continue;
                int d = descend(wCache, ww, hh, tx, ty);
                if(d < 0) continue;
                dirs[sy * sw + sx] = (byte)(d + 1);
                if(MKUKICSettings.s().qolPathChokes){
                    int c = convergence(wCache, ww, hh, tx, ty, step);
                    chokes[sy * sw + sx] = (byte)Math.min(c, 100);
                }
            }
        }
        if(rowCursor >= sh){
            computing = false;
            ready = true;
            MKUKICMod.toast("[accent]Маршруты врага построены[] (" + sw + "x" + sh + " узлов)");
        }
    }

    public static void reset(){
        computing = false;
        ready = false;
        dirs = null;
        chokes = null;
        wCache = null;
        sw = sh = rowCursor = 0;
    }

    // ============================ ОТРИСОВКА ============================

    public static void draw(){
        MKUKICSettings s = MKUKICSettings.s();
        if(!s.enabled || !s.qolEnabled || !s.qolPathOverlay) return;
        if(!ready || dirs == null || world == null || state == null || state.isMenu()) return;
        if(player == null) return;

        Core.camera.bounds(viewRect);
        viewRect.grow(64f);
        int x0 = Math.max(0, (int)(viewRect.x / tilesize) / step);
        int y0 = Math.max(0, (int)(viewRect.y / tilesize) / step);
        int x1 = Math.min(sw - 1, (int)((viewRect.x + viewRect.width) / tilesize) / step);
        int y1 = Math.min(sh - 1, (int)((viewRect.y + viewRect.height) / tilesize) / step);

        Color teamCol = state.rules.waveTeam != null ? state.rules.waveTeam.color : Color.scarlet;

        Draw.z(Layer.overlayUI);
        Lines.stroke(1.4f);
        for(int sy = y0; sy <= y1; sy++){
            for(int sx = x0; sx <= x1; sx++){
                int idx = sy * sw + sx;
                byte d = dirs[idx];
                float wx = sx * step * tilesize + tilesize / 2f;
                float wy = sy * step * tilesize + tilesize / 2f;
                if(d > 0){
                    int i = d - 1;
                    float ex = wx + dx8[i] * tilesize * 2.2f;
                    float ey = wy + dy8[i] * tilesize * 2.2f;
                    Draw.color(teamCol, 0.55f);
                    Lines.line(wx, wy, ex, ey, false);
                    Fill.circle(ex, ey, 1.6f);
                }
                if(s.qolPathChokes && chokes != null && chokes[idx] >= 3){
                    float r = 3f + Math.min(chokes[idx], 8) * 1.4f;
                    Draw.color(Color.orange, 0.28f);
                    Fill.circle(wx, wy, r);
                    Draw.color(Color.orange, 0.7f);
                    Lines.circle(wx, wy, r);
                }
            }
        }
        Draw.color();
        Lines.stroke(1f);
    }

    // ============================ ОКНО ============================

    private static MKUKICSettings s0(){
        return MKUKICSettings.s();
    }

    public static void build(Table t){
        QolWindows.section(t, "Маршруты врага (Pathfinding Visualizer)");
        t.add("[lightgray]Стрелки — куда пойдут наземные враги (данные собственного\n"
            + "flow-поля игры). Оранжевые круги — узкие места, где маршруты сходятся:\n"
            + "лучшие точки для обороны. Пересчитывай после постройки стен!").left().top().wrap().width(340f).pad(3f, 0f, 6f, 0f).row();

        t.table(c -> {
            c.left();
            c.button("Построить маршруты", MKUKICSkin.actBtn, PathPreview::startAnalysis)
                .minWidth(190f).height(36f).pad(2f);
        }).left().row();

        t.check("Оверлей на карте", s0().qolPathOverlay, v -> {
            s0().qolPathOverlay = v;
            MKUKICSettings.save();
            if(v && !ready) startAnalysis();
        }).left().pad(2f).row();
        t.check("Узкие места (chokepoints)", s0().qolPathChokes, v -> {
            s0().qolPathChokes = v;
            MKUKICSettings.save();
        }).left().pad(2f).row();

        t.label(() -> {
            if(computing) return "[accent]Анализ: " + (int)(progress() * 100f) + "%";
            if(ready) return "[lightgray]Готово. Сетка " + sw + "x" + sh + ", шаг " + step + " тайлов.";
            return "[darkgray]Ещё не анализировалось";
        }).left().padTop(6f).wrap().width(340f).row();
    }
}
