package mkukic.client.onix;

import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.math.*;
import arc.math.geom.*;
import arc.struct.*;
import arc.util.*;
import mindustry.gen.*;
import mindustry.world.*;
import mkukic.client.CoinPilot;
import mkukic.client.CoinPilot.*;

import static mindustry.Vars.*;

/**
 * Навигация для Onix-функций MKUKIC (v1.19).
 *
 * <p>Onix Lite водит юнита по BuildPath/MinePath и «не тянет новый код навигации».
 * MKUKIC делает то же самое: вместо собственной реализации используется ограниченный
 * A* из {@link CoinPilot} (бюджет узлов, пул памяти, сегментные маршруты, частичный
 * маршрут к ближайшей достижимой точке, объезд длинных препятствий со сменой стороны).
 *
 * <p>Класс — ПЕР-ЮНИТОВЫЙ навигатор: у каждого бота/автопилота свой экземпляр со своим
 * маршрутом, своей липкостью цели и своим анти-застреванием. Расчёт пути общий
 * ({@code CoinPilot.boundedAstar} возвращает переиспользуемый буфер), поэтому результат
 * копируется в свой список сразу после вызова — до следующего обращения к A*.
 *
 * <p>Отрисовка пути (фиолетовый пунктир + звезда-цель + орбита) — порт «Визуала пути бота»
 * из Onix Extras.
 */
public class OnixNav{
    /** Максимум точек маршрута, которые храним (дальше — перепланирование). */
    public static final int maxRoute = 4096;
    /** Пауза между неудачными попытками построить маршрут, кадров. */
    public static final float repathInterval = 45f;
    /** Проверка «застрял» раз в N кадров. */
    public static final float stuckCheck = 30f;

    private final Seq<Tile> route = new Seq<>(false, 256, Tile.class);
    private final Vec2 seg = new Vec2();
    private final Vec2 move = new Vec2();
    private final Vec2 evade = new Vec2();

    private int routeIdx;
    private float lastRepath = -1000f, stuckT = -1000f, stuckX, stuckY;
    private int stuckN, detourStreak;
    private float pathForX, pathForY;
    private boolean hasRoute;
    /** Точка, для которой строился текущий маршрут (мировая). */
    public float destX, destY;
    /** Счётчик сбросов из-за застревания — используется вызывающим кодом для смены цели. */
    public int stuckResets;

    private Unit passUnit;
    private final Passable unitPass = (x, y) -> {
        Unit u = passUnit;
        if(u == null || world == null) return false;
        try{
            return u.canPass(x, y);
        }catch(Throwable t){
            return false;
        }
    };

    public void reset(){
        route.clear();
        routeIdx = 0;
        hasRoute = false;
        stuckN = 0;
        detourStreak = 0;
        stuckResets = 0;
        lastRepath = Time.time;
    }

    public boolean hasRoute(){
        return hasRoute && routeIdx < route.size;
    }

    /**
     * Повести юнита к точке (мировые координаты). Один вызов {@code movePref} за кадр.
     *
     * @param circle радиус «прибытия» в мировых единицах
     * @param dodgeRadius радиус реагирования на снаряды; &lt;= 0 — уклонение выключено
     * @return true, если юнит у цели
     */
    public boolean moveTo(Unit u, float x, float y, float circle, float dodgeRadius){
        if(u == null) return false;
        destX = x;
        destY = y;

        float d = Mathf.dst(u.x, u.y, x, y);
        if(d <= circle){
            u.movePref(move.setZero());
            route.clear();
            hasRoute = false;
            return true;
        }

        move.setZero();
        if(u.isFlying() || u.type.flying){
            steer(move, u, x, y, circle);
        }else{
            steerGround(move, u, x, y);
        }
        if(dodgeRadius > 0f) addEvade(move, u, dodgeRadius);

        if(!move.isZero(0.01f)) u.movePref(move);
        checkStuck(u);
        return false;
    }

    /** Прямое наведение: вектор к точке, ограниченный скоростью юнита. */
    public static void steer(Vec2 out, Unit u, float x, float y, float circle){
        out.set(x - u.x, y - u.y);
        float len = out.len();
        if(len < circle){
            out.setZero();
        }else{
            out.setLength(Math.min(u.speed() * 1.35f, len - circle * 0.5f));
        }
    }

    /**
     * Наземное наведение: сегментный ограниченный A* (см. {@link CoinPilot#boundedAstar}),
     * фолбэк — напрямую к цели. Длинные перегоны режутся на сегменты по 200 тайлов,
     * при упоре в препятствие используется частичный маршрут и объезд со сменой стороны.
     */
    private void steerGround(Vec2 out, Unit u, float x, float y){
        Tile from = world.tileWorld(u.x, u.y);
        Tile to = world.tileWorld(x, y);
        if(from == null || to == null){
            steer(out, u, x, y, 4f);
            return;
        }

        boolean need = hasRoute ? routeIdx >= route.size : Time.time - lastRepath > repathInterval;
        if(need) planRoute(u, from, to, x, y);

        if(hasRoute){
            while(routeIdx < route.size){
                Tile wp = route.get(routeIdx);
                if(wp == null){
                    routeIdx++;
                    continue;
                }
                if(u.within(wp.worldx(), wp.worldy(), tilesize * 1.1f)) routeIdx++;
                else break;
            }
            if(routeIdx < route.size){
                Tile wp = route.get(routeIdx);
                if(wp != null){
                    steer(out, u, wp.worldx(), wp.worldy(), 2f);
                    return;
                }
            }
            hasRoute = false; // маршрут пройден — дальше напрямую
        }
        steer(out, u, x, y, 4f);
    }

    /** Построить (или перестроить) маршрут к точке. Результат копируется в свой список. */
    private void planRoute(Unit u, Tile from, Tile to, float wx, float wy){
        lastRepath = Time.time;
        int w = world.width(), h = world.height();
        if(w <= 0 || h <= 0) return;

        // цель уехала далеко — старый маршрут бессмыслен
        if(hasRoute && Mathf.dst2(wx, wy, pathForX, pathForY) > 64f){
            hasRoute = false;
            routeIdx = 0;
        }
        if(CoinPilot.manhattan(from.x, from.y, to.x, to.y) <= 0){
            hasRoute = false;
            return;
        }

        CoinPilot.segmentGoal(from.x, from.y, to.x, to.y, w, h, seg);
        boolean finalApproach = CoinPilot.manhattan(from.x, from.y, to.x, to.y) <= CoinPilot.maxSegmentTiles;
        if(!finalApproach){
            int off = CoinPilot.nextDetour(detourStreak);
            if(off != 0) CoinPilot.detourGoal(from.x, from.y, Mathf.round(seg.x), Mathf.round(seg.y), w, h, off, seg);
        }
        int gx = Mathf.clamp((int)seg.x, 0, w - 1), gy = Mathf.clamp((int)seg.y, 0, h - 1);

        GridPath gp;
        try{
            passUnit = u;
            gp = CoinPilot.boundedAstar(from.x, from.y, gx, gy, w, h, CoinPilot.astarBudget, unitPass);
        }catch(Throwable t){
            detourStreak++;
            return;
        }finally{
            passUnit = null;
        }
        if(gp == null || gp.xy.size < 4){
            detourStreak++;
            hasRoute = false;
            return;
        }
        if(gp.reached) detourStreak = 0; else detourStreak++;

        route.clear();
        routeIdx = 0;
        for(int i = 2; i < gp.xy.size && route.size < maxRoute; i += 2){
            Tile t = world.tile(gp.xy.get(i), gp.xy.get(i + 1));
            if(t != null) route.add(t);
        }
        hasRoute = !route.isEmpty();
        pathForX = wx;
        pathForY = wy;
    }

    /**
     * Уклонение от снарядов (порт логики CoinPilot.addEvade для произвольного юнита).
     * Комбинируется с вектором прогресса: срочная угроза — чистое уклонение,
     * дальняя — смесь 40/60. Наземные юниты уклоняются только в проходимую сторону.
     */
    public void addEvade(Vec2 out, Unit u, float radius){
        evade.setZero();
        int hits = 0;
        boolean urgent = false;
        float R = Math.max(40f, radius);
        for(Bullet b : Groups.bullet){
            if(b == null || b.team() == u.team() || !b.within(u, R)) continue;
            Vec2 bv = b.vel();
            if(bv.isZero(0.01f)) continue;
            float ax = u.x - b.x, ay = u.y - b.y;
            float len = Mathf.len(ax, ay);
            if(len < 1f) continue;
            if(CoinPilot.aimFactor(ax, ay, bv.x, bv.y) < 0.5f) continue; // летит мимо

            float px = -bv.y, py = bv.x;
            float sign = CoinPilot.sideSign(px, py, ax, ay);
            px *= sign;
            py *= sign;

            if(!u.isFlying()){
                if(!passableDir(u, px, py)){
                    px = -px;
                    py = -py;
                    if(!passableDir(u, px, py)){
                        px = -ax;
                        py = -ay;
                        if(!passableDir(u, px, py)){
                            px = 0f;
                            py = 0f;
                        }
                    }
                }
            }
            float nl = Mathf.len(px, py);
            if(nl < 0.001f) continue;
            float wgt = CoinPilot.evadeWeight(len, R);
            evade.add(px / nl * wgt, py / nl * wgt);
            if(len < 60f) urgent = true;
            if(++hits >= 5) break;
        }
        if(hits == 0 || evade.isZero(0.001f)) return;

        evade.setLength(u.speed());
        if(urgent || out.isZero(0.01f)){
            out.set(evade);
        }else{
            out.setLength(u.speed() * 0.4f);
            out.add(evade).limit(u.speed() * 1.15f);
        }
    }

    private static boolean passableDir(Unit u, float dx, float dy){
        float nl = Mathf.len(dx, dy);
        if(nl < 0.001f) return false;
        Tile t = world.tileWorld(u.x + dx / nl * tilesize * 1.5f, u.y + dy / nl * tilesize * 1.5f);
        try{
            return t != null && u.canPass(t.x, t.y);
        }catch(Throwable e){
            return false;
        }
    }

    /** Анти-застрывание: 2с обычно, до 5с пока идёт объезд. */
    private void checkStuck(Unit u){
        if(Time.time - stuckT < stuckCheck) return;
        float moved = Mathf.dst(u.x, u.y, stuckX, stuckY);
        if(moved < 3f){
            stuckN++;
            if(stuckN >= CoinPilot.stuckLimit(detourStreak)){
                stuckN = 0;
                detourStreak = 0;
                stuckResets++;
                hasRoute = false;
                route.clear();
                routeIdx = 0;
            }
        }else{
            stuckN = 0;
        }
        stuckX = u.x;
        stuckY = u.y;
        stuckT = Time.time;
    }

    // ============================ ОТРИСОВКА ПУТИ ============================

    /**
     * «Визуал пути бота» из Onix Extras: фиолетовый пунктир к цели, звезда-цель,
     * орбита кружения и красные зоны опасности.
     */
    public static void drawPath(Unit u, float tx, float ty, float orbitRadius, boolean danger){
        if(u == null || renderer == null) return;
        Color theme = Onix.theme;

        // пунктир от юнита к цели
        Draw.color(theme, 0.55f);
        Lines.stroke(1.6f);
        float x = u.x, y = u.y;
        float dst = Mathf.dst(x, y, tx, ty);
        int segs = Mathf.clamp((int)(dst / 26f), 1, 220);
        for(int i = 0; i < segs; i++){
            if(i % 2 == 1) continue;
            float f0 = (float)i / segs, f1 = Math.min(1f, (float)(i + 1) / segs);
            Lines.line(x + (tx - x) * f0, y + (ty - y) * f0, x + (tx - x) * f1, y + (ty - y) * f1);
        }

        // орбита кружения
        if(orbitRadius > 4f){
            Draw.color(theme, 0.22f);
            Lines.stroke(1.2f);
            Lines.circle(tx, ty, orbitRadius);
        }

        // звезда-цель
        Draw.color(theme, 0.95f);
        star(tx, ty, 6f + Mathf.absin(Time.time, 6f, 1.6f));

        if(danger) drawDangerZones(u);
        Draw.reset();
    }

    /** Четырёхлучевая звезда (как в загрузочном экране Onix). */
    public static void star(float x, float y, float r){
        float r2 = r * 0.34f;
        Fill.quad(x - r, y, x, y + r2, x + r, y, x, y - r2);
        Fill.quad(x, y - r, x + r2, y, x, y + r, x - r2, y);
    }

    /** Красные зоны: турели врага и перегретые реакторы в пределах видимости. */
    public static void drawDangerZones(Unit u){
        if(u == null) return;
        Draw.color(Color.scarlet, 0.16f);
        Lines.stroke(1.4f);
        for(Building b : Groups.build){
            if(b == null || b.team == u.team || b.dead) continue;
            float range = 0f;
            if(b.block instanceof mindustry.world.blocks.defense.turrets.BaseTurret t && t.range > 0f) range = t.range;
            else if(b.block instanceof mindustry.world.blocks.power.ImpactReactor) range = 220f;
            else if(b.block instanceof mindustry.world.blocks.power.NuclearReactor) range = 180f;
            if(range <= 0f || !b.within(u, range + 900f)) continue;
            Draw.color(Color.scarlet, 0.12f);
            Fill.circle(b.x, b.y, range);
            Draw.color(Color.scarlet, 0.4f);
            Lines.circle(b.x, b.y, range);
        }
        Draw.reset();
    }
}
