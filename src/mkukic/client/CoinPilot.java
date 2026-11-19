package mkukic.client;

import arc.*;
import arc.func.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.math.*;
import arc.math.geom.*;
import arc.struct.*;
import arc.util.*;
import mindustry.ai.*;
import mindustry.content.*;
import mindustry.core.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.input.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.storage.*;
import mkukic.*;

import static mindustry.Vars.*;

/**
 * Автопилот до монет (v1.14, дальность x5 в v1.18) с встроенным уклонением от снарядов.
 *
 * В отличие от магнита (телепорт вблизи) автопилот ДОЕЗЖАЕТ до монеты сам:
 *  - летающие юниты — прямое наведение;
 *  - наземные/морские — СВОЙ ограниченный A* ({@link #boundedAstar}) с предикатом unit.canPass,
 *    бюджетом узлов и частичным маршрутом: стены, вода и пропасти объезжаются честно
 *    на дистанциях до {@value #maxRadiusTiles} тайлов (v1.17 и раньше — до 256, а фактически
 *    маршрут строился только до ~220 тайлов, дальше юнит упирался в препятствия);
 *    длинные перегоны режутся на сегменты по {@value #maxSegmentTiles} тайлов, пересчёт
 *    не чаще раза в ~1.5с и при смене цели, цель «липкая» ({@value #selectInterval} кадров);
 *  - уклонение от вражеских снарядов ВСТРОЕНО в тот же вектор движения
 *    (один movePref за кадр: сначала прогресс к цели, при угрозе — доминирует уклон);
 *    для наземных направление уклона проверяется на проходимость (иначе — зеркальная
 *    сторона, иначе — отход назад);
 *  - недостижимая монета (застряли ~2с) помечается «посещённой» (общая память
 *    с магнитом) и автопилот берёт следующую;
 *  - игрок всегда главнее: любое нажатие клавиш движения, диалог/чат или пауза —
 *    автопилот не мешает; пока магнит держит юнита на монете — автопилот ждёт.
 *
 * v1.19:
 *  - «игнор толкотни»: толчки союзных юнитов откатываются (для летающих), а если на пути
 *    плотная толпа (>= {@value #crowdBlockCount} юнитов) — облёт вместо пролёта насквозь;
 *  - монет нет -> патруль от точки высадки к точке высадки на расстоянии
 *    dropZoneRadius + запас (не попадаем под удар волны);
 *  - монета есть -> пересадка в ближайшего к ней свободного союзного юнита;
 *  - монет долго нет -> респавн в ядре (если сидим не в юните ядра).
 *
 * Движение своего юнита на клиенте авторитетно (как у магнита и PlayerAI) —
 * работает и онлайн; это обычное перемещение со скоростью юнита, не телепорт.
 */
public class CoinPilot{
    private static final Vec2 tmp = new Vec2(), tmp2 = new Vec2(), coinPos = new Vec2(), landing = new Vec2();
    private static final Vec2 segOut = new Vec2();   // v1.18: выход segmentGoal (не путать с tmp2 из addEvade)

    // текущая цель
    private static boolean hasTarget;
    private static float coinX, coinY;     // позиция монеты (может двигаться)
    private static float destX, destY;     // точка приземления/подъезда

    // наземный маршрут (A*)
    private static Seq<Tile> path;
    private static int pathIdx;
    private static float lastRepath = -999f;
    private static float pathForX, pathForY;

    // анти-застревание
    private static float stuckX, stuckY, stuckT;
    private static int stuckN;

    private static final Astar.TileHeuristic tileH = t -> 1f;
    private static final float repathInterval = 90f; // кадров между пересчётами маршрута

    // ======================= v1.18: ДАЛЬНОСТЬ x5 =======================
    // Было: радиус автопилота 8..256 тайлов (по умолчанию 120), а наземный маршрут
    // считался ванильным Astar.pathfind только если манхэттен <= 220 тайлов —
    // дальше юнит шёл «напрямую» и упирался в стены. Стало: 8..1280 тайлов
    // (по умолчанию 600) + собственный ограниченный A* с сегментами и частичным маршрутом.

    /** Минимальный радиус поиска монет, тайлы. */
    public static final float minRadiusTiles = 8f;
    /** Максимальный радиус поиска монет, тайлы (v1.18: было 256 — увеличено в 5 раз). */
    public static final float maxRadiusTiles = 1280f;
    /** Радиус по умолчанию, тайлы (v1.18: было 120). */
    public static final float defaultRadiusTiles = 600f;
    /** Бюджет раскрытия узлов на один вызов A* — защита от фриза на гигантских картах. */
    public static final int astarBudget = 6000;
    /** Максимальная длина одного сегмента маршрута (тайлы по манхэттену). */
    public static final int maxSegmentTiles = 200;
    /** Кадров между полными перевыборами цели (липкость цели на дальних маршрутах). */
    public static final float selectInterval = 20f;
    /** Сколько финалистов пробуем, если точка подъезда не нашлась. */
    public static final int landingAttempts = 4;
    /** Базовый шаг бокового смещения при объезде препятствия, тайлы. */
    public static final int detourStep = 32;
    /** Потолок бокового смещения объезда, тайлы (меньше сегмента, чтобы A* успевал). */
    public static final int maxDetourTiles = 224;

    /** Сколько подряд сегментов не достроено до конца (растёт — смещение объезда увеличивается). */
    private static int detourStreak;

    private static float lastSelect = -999f;
    private static final IntSet landingFailed = new IntSet();
    private static final Seq<Tile> routeBuf = new Seq<>(false, 64);

    // ======================= v1.19 =======================
    // Переключатели (public static — при желании выведи в MKUKICSettings).
    public static boolean crowdAvoid = true;      // облёт плотной толпы союзников
    public static boolean ignorePush = true;      // откат толчков союзников (летающие)
    /** Ключ настройки патруля (хранится в Core.settings, не требует поля в MKUKICSettings). */
    public static final String patrolKey = "mkukic-coin-patrol";
    public static boolean boardNearest = true;    // пересадка в ближайшего к монете юнита
    public static boolean respawnOnIdle = true;   // респавн в ядре, когда монет нет

    /** Дальность проверки толпы по курсу, тайлы. */
    public static final float crowdLookTiles = 9f;
    /** Запас к радиусам юнитов при проверке прохода, мировые единицы. */
    public static final float crowdPad = 2f;
    /** Сколько союзников на пути считается «не пролететь» -> облёт. */
    public static final int crowdBlockCount = 3;
    public static final int crowdMax = 64;
    /** Сколько кадров держим выбранную сторону облёта (против дёрганья). */
    public static final float crowdHold = 25f;
    private static final float[] crowdAngles = {0f, 35f, 70f, 105f, 140f, 180f};
    private static final float[] crowdX = new float[crowdMax], crowdY = new float[crowdMax], crowdR = new float[crowdMax];
    private static float crowdAngle, crowdHoldUntil;

    // толчки
    private static Unit pgUnit;
    private static float pgX, pgY, pgVX, pgVY, pgTime;

    // патруль
    /** Запас до границы зоны высадки, тайлы. */
    public static final float patrolMarginTiles = 4f;
    /** Сколько кадров висим у точки патруля. */
    public static final float patrolDwell = 150f;
    public static final float patrolArriveTiles = 3f;
    /** Шаг по кольцу, если зона высадки одна, градусы. */
    public static final float patrolSingleStep = 120f;
    private static final float[] patrolOffsets = {0f, 30f, -30f, 60f, -60f, 90f, -90f, 120f, -120f, 150f, -150f, 180f};
    private static final FloatSeq zx = new FloatSeq(), zy = new FloatSeq();
    private static float zonesAt = -999f;
    private static boolean patrolActive, patrolHas;
    private static float patrolX, patrolY, patrolDwellUntil, patrolAngle;
    private static int patrolIdx;

    // пересадка / респавн
    public static final float boardGainTiles = 6f;
    public static final float boardCooldown = 90f;
    public static final float idleRespawnDelay = 180f;
    public static final float respawnCooldown = 300f;
    private static float lastBoard = -999f, lastRespawn = -999f, noCoinSince = -1f;
    private static final Vec2 tmp3 = new Vec2();

    // ---------- пул узлов A* (переиспользуем, чтобы не мусорить в кадре) ----------
    static class Node{ float g, f; int x, y, key; }
    private static final Seq<Node> live = new Seq<>();
    private static final Seq<Node> pool = new Seq<>();
    private static final PQueue<Node> open = new PQueue<>(256, (a, b) -> Float.compare(a.f, b.f));
    private static final IntFloatMap gCost = new IntFloatMap();
    private static final IntIntMap parent = new IntIntMap();
    private static final IntSet closed = new IntSet();
    private static final IntSeq keysBuf = new IntSeq();   // цепочка родителей (стоп -> цель)
    private static Unit passUnit;                          // юнит для предиката проходимости
    /** Переиспользуемый предикат (иначе на каждый пересчёт маршрута — новая лямбда). */
    private static final Passable unitPass = (x, y) -> passUnit != null && passUnit.canPass(x, y);
    private static final GridPath gridResult = new GridPath();

    // ======================= ЧИСТЫЕ ФУНКЦИИ (тестируются) =======================

    /**
     * Насколько снаряд летит точно в нас: cos угла между вектором «снаряд->мы»
     * и скоростью снаряда. 1 = летит прямо в нас, <=0 = мимо/от нас.
     */
    public static float aimFactor(float ax, float ay, float bvx, float bvy){
        float len = Mathf.len(ax, ay), vl = Mathf.len(bvx, bvy);
        if(len < 0.001f || vl < 0.001f) return 0f;
        return (ax * bvx + ay * bvy) / (len * vl);
    }

    /** Знак стороны уклонения: перпендикуляр должен уводить ОТ линии огня. */
    public static float sideSign(float px, float py, float ax, float ay){
        return (px * ax + py * ay) < 0f ? -1f : 1f;
    }

    /** Вес угрозы: 1 вплотную, 0 на границе радиуса. */
    public static float evadeWeight(float dist, float radius){
        if(radius <= 0f) return 0f;
        return 1f - Mathf.clamp(dist / radius);
    }

    /**
     * Радиус поиска в мировых единицах (чистая функция — тестируется).
     * Защита от битого settings.json: NaN Mathf.clamp НЕ ловит (любое сравнение с NaN
     * даёт false, и радиус остался бы NaN — то есть «фильтр дальности выключен»),
     * поэтому подменяем его дефолтом. ±Infinity клампятся штатно.
     */
    public static float radiusWorld(float tiles){
        if(Float.isNaN(tiles)) tiles = defaultRadiusTiles;
        return tilesize * Mathf.clamp(tiles, minRadiusTiles, maxRadiusTiles);
    }

    /** Манхэттен-расстояние в тайлах (чистая функция — тестируется). */
    public static int manhattan(int x1, int y1, int x2, int y2){
        return Math.abs(x1 - x2) + Math.abs(y1 - y2);
    }

    /**
     * Промежуточная точка сегмента: если до цели дальше {@link #maxSegmentTiles},
     * берём точку на линии к цели, чтобы A* искал в узком коридоре и укладывался в бюджет.
     * Возвращает координаты в out (тайлы); чистая функция — тестируется.
     */
    public static void segmentGoal(int fx, int fy, int tx, int ty, int w, int h, arc.math.geom.Vec2 out){
        int md = manhattan(fx, fy, tx, ty);
        if(md <= maxSegmentTiles || w <= 0 || h <= 0){
            out.set(tx, ty);
            return;
        }
        float dx = tx - fx, dy = ty - fy;
        float len = Mathf.len(dx, dy);
        if(len < 0.001f){
            out.set(tx, ty);
            return;
        }
        // 0.7 — чтобы диагональный сегмент оставался в пределах maxSegmentTiles по манхэттену
        float k = maxSegmentTiles * 0.7f / len;
        out.set(Mathf.clamp(fx + Mathf.round(dx * k), 0, w - 1),
                Mathf.clamp(fy + Mathf.round(dy * k), 0, h - 1));
    }

    /**
     * Знаковое смещение объезда в тайлах по числу неудачных сегментов подряд:
     * 0, +32, -32, +64, -64, +96, -96 ... с потолком {@value #maxDetourTiles}.
     * Чередование сторон нужно, чтобы юнит не «тёрся» об одну и ту же стену,
     * когда проход с другой стороны. Чистая функция — тестируется.
     */
    public static int nextDetour(int streak){
        if(streak <= 0) return 0;
        int n = (streak + 1) / 2;                            // 1,1,2,2,3,3,...
        int mag = Math.min(n * detourStep, maxDetourTiles);
        return (streak % 2 == 1) ? mag : -mag;
    }

    /**
     * Цель сегмента, сдвинутая перпендикулярно направлению на цель ({@code offTiles} — со знаком).
     * Так объезжаются длинные стены: A* ведёт точку маршрута сбоку от линии «мы -> монета».
     * Координаты зажимаются в пределы карты. Чистая функция — тестируется.
     */
    public static void detourGoal(int fx, int fy, int tx, int ty, int w, int h, int offTiles, arc.math.geom.Vec2 out){
        out.set(tx, ty);
        if(offTiles == 0 || w <= 0 || h <= 0) return;
        float dx = tx - fx, dy = ty - fy;
        float len = Mathf.len(dx, dy);
        if(len < 0.001f) return;
        float nx = -dy / len, ny = dx / len;                 // нормаль к направлению
        out.set(Mathf.clamp(Mathf.round(tx + nx * offTiles), 0, w - 1),
                Mathf.clamp(Mathf.round(ty + ny * offTiles), 0, h - 1));
    }

    /**
     * Порог анти-застревания (в тактах по 30 кадров): пока идёт объезд — ждём дольше,
     * иначе юнит бросал бы монету, не успев объехать стену. Чистая функция — тестируется.
     */
    public static int stuckLimit(int detourStreak){
        return detourStreak > 0 ? 10 : 4;                    // ~5с в объезде, ~2с обычно
    }

    /** Патруль точек высадки включён (по умолчанию да). */
    public static boolean patrolEnabled(){
        return Core.settings == null || Core.settings.getBool(patrolKey, true);
    }

    public static void setPatrolEnabled(boolean v){
        if(Core.settings != null) Core.settings.put(patrolKey, v);
        if(!v) stopPatrol();
    }

    /** Расстояние от точки до отрезка AB. Чистая функция — тестируется. */
    public static float distToSegment(float px, float py, float ax, float ay, float bx, float by){
        float dx = bx - ax, dy = by - ay;
        float l2 = dx * dx + dy * dy;
        float t = l2 < 0.0001f ? 0f : Mathf.clamp(((px - ax) * dx + (py - ay) * dy) / l2, 0f, 1f);
        return Mathf.dst(px, py, ax + dx * t, ay + dy * t);
    }

    /**
     * Сколько кружков (cx, cy, cr) задевает «труба» радиуса r от (ux, uy) вдоль (dx, dy) длиной len.
     * Чистая функция — тестируется.
     */
    public static int crowdBlockers(float ux, float uy, float dx, float dy, float len, float r,
                                    float[] cx, float[] cy, float[] cr, int n){
        float dl = Mathf.len(dx, dy);
        if(dl < 0.001f || len <= 0f) return 0;
        dx /= dl;
        dy /= dl;
        int c = 0;
        for(int i = 0; i < n; i++){
            float t = Mathf.clamp((cx[i] - ux) * dx + (cy[i] - uy) * dy, 0f, len);
            float rr = r + cr[i];
            if(Mathf.dst2(cx[i], cy[i], ux + dx * t, uy + dy * t) < rr * rr) c++;
        }
        return c;
    }

    /**
     * Обход круга (зоны высадки): внутри safe — прямо наружу; если курс на look вперёд пересекает круг —
     * скольжение по касательной. true, если out заполнен. Чистая функция — тестируется.
     */
    public static boolean deflectFromCircle(float ux, float uy, float mx, float my, float cx, float cy,
                                            float safe, float look, float speed, Vec2 out){
        float rx = ux - cx, ry = uy - cy;
        float d = Mathf.len(rx, ry);
        if(d < safe){
            if(d < 0.001f){ rx = 1f; ry = 0f; d = 1f; }
            out.set(rx / d * speed, ry / d * speed);
            return true;
        }
        float ml = Mathf.len(mx, my);
        if(ml < 0.001f) return false;
        float nx = mx / ml, ny = my / ml;
        if(distToSegment(cx, cy, ux, uy, ux + nx * look, uy + ny * look) >= safe) return false;
        float tx = -ry / d, ty = rx / d;                       // касательная, по ходу движения
        if(tx * nx + ty * ny < 0f){ tx = -tx; ty = -ty; }
        float push = Mathf.clamp((safe + look * 0.25f - d) / (look * 0.25f));
        out.set(tx + rx / d * push * 0.6f, ty + ry / d * push * 0.6f).setLength(speed);
        return true;
    }

    /** Проходимость тайла — интерфейс, чтобы A* можно было гонять на чистой сетке в тестах. */
    public interface Passable{ boolean canPass(int x, int y); }

    /** Результат ограниченного A* (один переиспользуемый экземпляр). */
    public static class GridPath{
        /** Пары (x, y) от старта к цели; включает стартовый тайл. */
        public final IntSeq xy = new IntSeq();
        /** Дошли ли ровно до цели (false = маршрут частичный, ведёт максимально близко). */
        public boolean reached;
        /** Сколько узлов раскрыто (для контроля бюджета). */
        public int expanded;
        /** Конечная тайловая точка маршрута. */
        public int endX, endY;

        void reset(){
            xy.clear();
            reached = false;
            expanded = 0;
            endX = endY = 0;
        }
    }

    /**
     * Ограниченный A* по сетке тайлов (v1.18).
     *
     * Зачем свой, а не ванильный {@code Astar.pathfind}:
     *  - у ванили НЕТ бюджета узлов: на недостижимой цели он вычерпает всю карту,
     *    а каждый вызов ещё и заполняет нулями массив стоимости размером с карту;
     *  - здесь есть бюджет ({@code budget}) и «частичный маршрут»: если цель не достигнута,
     *    возвращаем путь к самой близкой к цели достижимой точке — юнит честно едет
     *    до препятствия и перепланирует маршрут с нового места (сегментная навигация);
     *  - узлы берутся из пула, память — в Int-картах, без аллокаций на карту целиком.
     *
     * @return переиспользуемый {@link GridPath}; {@code xy} пуст, если путь не построен.
     */
    public static GridPath boundedAstar(int sx, int sy, int ex, int ey, int w, int h, int budget, Passable pass){
        GridPath res = gridResult;
        res.reset();
        pool.addAll(live);
        live.clear();
        gCost.clear();
        parent.clear();
        closed.clear();
        open.clear();

        if(pass == null || budget <= 0 || w <= 0 || h <= 0) return res;
        if(sx < 0 || sy < 0 || ex < 0 || ey < 0 || sx >= w || sy >= h || ex >= w || ey >= h) return res;

        int startKey = Point2.pack(sx, sy);
        int goalKey = Point2.pack(ex, ey);

        Node start = node(startKey, sx, sy);
        start.g = 0f;
        start.f = manhattan(sx, sy, ex, ey);
        gCost.put(startKey, 0f);
        open.add(start);

        int bestKey = startKey, bestX = sx, bestY = sy;
        float bestH = start.f;
        boolean found = false;

        while(!open.empty() && res.expanded < budget){
            Node cur = open.poll();
            if(cur == null) break;
            if(closed.contains(cur.key)) continue;      // устаревшая запись очереди (ленивое удаление)
            closed.add(cur.key);
            res.expanded++;

            if(cur.key == goalKey){
                found = true;
                bestKey = goalKey; bestX = ex; bestY = ey;
                break;
            }
            for(int d = 0; d < 4; d++){
                int nx = cur.x + Geometry.d4x[d], ny = cur.y + Geometry.d4y[d];
                if(nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
                int nk = Point2.pack(nx, ny);
                if(closed.contains(nk)) continue;
                if(!pass.canPass(nx, ny)) continue;
                float ng = cur.g + 1f;
                if(ng < gCost.get(nk, Float.POSITIVE_INFINITY)){
                    gCost.put(nk, ng);
                    parent.put(nk, cur.key);
                    float hh = manhattan(nx, ny, ex, ey);
                    Node nn = node(nk, nx, ny);
                    nn.g = ng;
                    nn.f = ng + hh;
                    if(hh < bestH){
                        bestH = hh; bestKey = nk; bestX = nx; bestY = ny;
                    }
                    open.add(nn);
                }
            }
        }

        res.reached = found;
        res.endX = bestX;
        res.endY = bestY;
        // ВАЖНО: IntSeq.reverse() переворачивает ПЛОСКИЙ массив по элементам и меняет
        // пары (x, y) местами, поэтому идём по цепочке родителей в буфер и пишем пары
        // сразу в порядке «старт -> цель».
        keysBuf.clear();
        int key = bestKey;
        int guard = 0;
        while(key != startKey && guard++ <= budget + 4){
            keysBuf.add(key);
            key = parent.get(key, startKey);
        }
        res.xy.add(sx, sy);
        for(int i = keysBuf.size - 1; i >= 0; i--){
            int k = keysBuf.get(i);
            res.xy.add(Point2.x(k), Point2.y(k));       // без аллокации Point2
        }
        return res;
    }

    private static Node node(int key, int x, int y){
        Node n = pool.isEmpty() ? new Node() : pool.pop();
        n.key = key; n.x = x; n.y = y; n.g = 0f; n.f = 0f;
        live.add(n);
        return n;
    }

    // ======================= СОСТОЯНИЕ =======================

    public static boolean isActive(){
        try{
            return MKUKICSettings.s().coinPilot && state != null && state.isGame();
        }catch(Throwable t){
            return false;
        }
    }

    public static void reset(){
        hasTarget = false;
        path = null;
        pathIdx = 0;
        stuckN = 0;
        lastSelect = -999f;          // v1.18: следующий кадр — полный перевыбор цели
        landingFailed.clear();
        detourStreak = 0;            // v1.18: сброс счётчика объезда
        stopPatrol();                // v1.19
        zonesAt = -999f;
        noCoinSince = -1f;
        lastBoard = lastRespawn = -999f;
        crowdAngle = 0f;
        pgUnit = null;
    }

    public static void toggle(){
        MKUKICSettings s = MKUKICSettings.s();
        s.coinPilot = !s.coinPilot;
        MKUKICSettings.save();
        if(s.coinPilot) reset();
        MKUKICMod.toast(s.coinPilot
            ? "[gold]" + (char)Iconc.play + " [white]Автопилот до монет ВКЛ (уклонение: " + (s.coinPilotDodge ? "вкл" : "выкл") + ")"
            : "[lightgray]Автопилот до монет ВЫКЛ");
    }

    /** Свой юнит, которым можно управлять (как eligible у магнита). */
    static Unit pilotUnit(){
        try{
            if(player == null || player.dead()) return null;
            Unit u = player.unit();
            if(u == null || u.dead() || u instanceof BlockUnitc) return null;
            if(!u.isLocal() || u.controller() != player) return null;
            if(u.type == null || !u.type.playerControllable) return null;
            return (Float.isFinite(u.x) && Float.isFinite(u.y)) ? u : null;
        }catch(Throwable t){
            return null;
        }
    }

    static boolean inputBlocked(){
        try{
            return (Core.scene != null && (Core.scene.hasField() || Core.scene.hasKeyboard() || Core.scene.hasDialog()))
                || (control != null && control.input != null && control.input.locked());
        }catch(Throwable t){
            return false;
        }
    }

    /** Игрок сам жмёт клавиши движения — не мешаем. */
    static boolean playerMoving(){
        try{
            if(control == null || control.input == null) return false;
            if(control.input instanceof DesktopInput d) return !d.movement.isZero(0.01f);
            if(control.input instanceof MobileInput m) return !m.movement.isZero(0.01f);
        }catch(Throwable ignored){}
        return false;
    }

    // ======================= ГЛАВНЫЙ ЦИКЛ =======================

    /** Каждый кадр после обновления сущностей (Trigger.afterGameUpdate). */
    public static void lateUpdate(){
        try{
            MKUKICSettings s = MKUKICSettings.s();
            if(!s.coinPilot){
                if(hasTarget || patrolActive) reset();
                return;
            }
            if(state == null || !state.isGame() || state.isPaused()) return;
            Unit u = pilotUnit();
            if(u == null){
                hasTarget = false;
                stopPatrol();
                pgUnit = null;
                return;
            }
            if(inputBlocked() || playerMoving() || CoinMagnet.isHolding()){   // игрок главнее / магнит держит
                pgUnit = null;
                return;
            }

            undoPush(u);                                        // v1.19: откат толчков до всего остального
            selectTarget(u, s);

            Vec2 move = tmp.setZero();
            if(hasTarget){
                noCoinSince = -1f;
                if(patrolActive) stopPatrol();
                if(tryBoard(u)) return;                         // пересели в юнита ближе к монете
                if(u.isFlying()){
                    steer(move, u, destX, destY, 4f);
                }else{
                    steerGround(move, u);
                }
            }else{
                if(noCoinSince < 0f) noCoinSince = Time.time;
                if(respawnToCore(u)) return;
                patrolStep(move, u);                            // нет монет — патруль (или стоим, если зон нет)
            }

            avoidCrowd(move, u);
            if(patrolActive) repelFromZones(move, u);
            if(s.coinPilotDodge) addEvade(move, u, s);

            if(!move.isZero(0.01f)) u.movePref(move);
            checkStuck(u);
            recordPush(u);
        }catch(Throwable t){
            Log.err("[MKUKIC] CoinPilot: lateUpdate", t);
        }
    }

    // ======================= ВЫБОР ЦЕЛИ =======================

    /**
     * Выбор цели (v1.18 — под радиус до {@value #maxRadiusTiles} тайлов).
     *
     * Два изменения против старой версии:
     *  1) ЛИПКОСТЬ ЦЕЛИ: полный перевыбор — не чаще раза в {@value #selectInterval} кадров.
     *     На дальних маршрутах прежний перевыбор каждый кадр заставлял юнита дёргаться
     *     между двумя примерно равноудалёнными монетами и сбрасывать маршрут.
     *  2) ДЕШЁВЫЙ ОТБОР: сначала выбираем лучшего кандидата только по дистанции/ценности,
     *     а {@code landingFor} (подбор проходимой точки подъезда, до 25 проверок тайлов)
     *     зовём лишь для финалистов — не более {@value #landingAttempts} раз.
     *     При радиусе 1280 тайлов в выбор попадают сотни целей, и прежняя схема
     *     вызывала landingFor для каждой из них КАЖДЫЙ кадр.
     */
    static void selectTarget(Unit u, MKUKICSettings s){
        float radiusW = radiusWorld(s.coinPilotRadius);
        float radiusSq = radiusW * radiusW;

        // 1) текущая цель ещё жива и в радиусе — держимся за неё
        if(hasTarget && Time.time - lastSelect < selectInterval
            && !CoinMagnet.recentlyVisited(coinX, coinY)
            && Mathf.dst2(u.x, u.y, coinX, coinY) <= radiusSq){
            return;
        }
        lastSelect = Time.time;
        landingFailed.clear();

        // 2) полный перевыбор (не более landingAttempts попыток подобрать точку подъезда)
        for(int attempt = 0; attempt < landingAttempts; attempt++){
            int best = -1;
            float nearest = Float.POSITIVE_INFINITY;
            boolean preferredValue = false;
            float bx = 0f, by = 0f;
            for(int i = 0; i < CoinMagnet.targetCount(); i++){
                if(landingFailed.contains(i)) continue;
                if(!CoinMagnet.targetPos(i, coinPos)) continue;
                if(CoinMagnet.recentlyVisited(coinPos.x, coinPos.y)) continue;
                float d = Mathf.dst2(u.x, u.y, coinPos.x, coinPos.y);
                if(d > radiusSq) continue;
                boolean valuable = s.coinPreferHighValue && CoinMagnet.targetValuable(i);
                if(best < 0 || (valuable && !preferredValue) || (valuable == preferredValue && d < nearest)){
                    best = i;
                    nearest = d;
                    preferredValue = valuable;
                    bx = coinPos.x;
                    by = coinPos.y;
                }
            }
            if(best < 0) break;                       // целей в радиусе больше нет
            if(CoinMagnet.landingFor(u, bx, by, landing)){
                coinX = bx;
                coinY = by;
                destX = landing.x;
                destY = landing.y;
                hasTarget = true;
                // цель сменилась/уехала — сбросить маршрут
                if(path != null && (Mathf.dst2(coinX, coinY, pathForX, pathForY) > 64f || pathIdx >= path.size)){
                    path = null;
                }
                return;
            }
            landingFailed.add(best);                  // к этой монете не подъехать — берём следующую
        }
        hasTarget = false;
        if(!patrolActive) path = null;   // v1.19: путь патруля не сбрасываем каждый кадр
    }

    // ======================= ДВИЖЕНИЕ =======================

    /** Прямое наведение: вектор к точке, ограниченный скоростью. */
    static void steer(Vec2 move, Unit u, float x, float y, float circle){
        move.set(x - u.x, y - u.y);
        float len = move.len();
        if(len < circle){
            move.setZero();
        }else{
            move.setLength(Math.min(u.speed(), len - circle * 0.5f));
        }
    }

    /**
     * Наземное наведение (v1.18): сегментный ограниченный A* по проходимым тайлам,
     * фолбэк — напрямую к цели.
     *
     * Длинные перегоны (> {@value #maxSegmentTiles} тайлов) режутся на сегменты:
     * цель сегмента — точка на линии к монете, поэтому поиск всегда идёт в узком коридоре
     * и укладывается в бюджет {@value #astarBudget} узлов. Если сегмент не достроен до конца
     * (упёрлись в препятствие), возвращается ЧАСТИЧНЫЙ маршрут — юнит доезжает до конца
     * доступного куска и перепланирует с нового места, а не упирается в стену, как раньше.
     */
    static void steerGround(Vec2 move, Unit u){
        Tile from = world.tileWorld(u.x, u.y);
        Tile to = world.tileWorld(destX, destY);
        if(from == null || to == null){
            steer(move, u, destX, destY, 4f);
            return;
        }
        // маршрут кончился — перепланировать сразу; маршрута нет и прошлая попытка
        // не удалась — ждём repathInterval, чтобы не дёргать A* каждый кадр
        boolean need = path != null ? pathIdx >= path.size : Time.time - lastRepath > repathInterval;
        if(need){
            lastRepath = Time.time;
            path = null;
            pathIdx = 0;
            Seq<Tile> p = planRoute(u, from, to);
            if(p != null && p.size > 0){
                path = p;
                pathForX = coinX;
                pathForY = coinY;
            }
        }
        if(path != null){
            while(pathIdx < path.size){
                Tile wp = path.get(pathIdx);
                if(wp == null){
                    pathIdx++;
                    continue;
                }
                if(u.within(wp.worldx(), wp.worldy(), tilesize * 1.1f)) pathIdx++;
                else break;
            }
            if(pathIdx < path.size){
                Tile wp = path.get(pathIdx);
                if(wp != null){
                    steer(move, u, wp.worldx(), wp.worldy(), 2f);
                    return;
                }
            }
            // маршрут пройден — подъезжаем напрямую (монета могла уехать)
            path = null;
        }
        steer(move, u, destX, destY, 4f);
    }

    /**
     * Построить маршрут наземного юнита к точке (destX, destY) одним сегментом A*.
     * Возвращает переиспользуемый буфер тайлов (без стартового тайла) или null.
     */
    static Seq<Tile> planRoute(Unit u, Tile from, Tile to){
        int w = world.width(), h = world.height();
        if(w <= 0 || h <= 0) return null;
        if(manhattan(from.x, from.y, to.x, to.y) <= 0) return null;

        segmentGoal(from.x, from.y, to.x, to.y, w, h, segOut);   // свой Vec2: tmp2 занят в addEvade
        // объезд применяем только к ПРОМЕЖУТОЧНЫМ сегментам: на финальном подходе
        // цель — сама точка подъезда, иначе юнит не доедет до монеты
        boolean finalApproach = manhattan(from.x, from.y, to.x, to.y) <= maxSegmentTiles;
        if(!finalApproach){
            int off = nextDetour(detourStreak);
            if(off != 0) detourGoal(from.x, from.y, Mathf.round(segOut.x), Mathf.round(segOut.y), w, h, off, segOut);
        }
        int gx = Mathf.clamp((int)segOut.x, 0, w - 1), gy = Mathf.clamp((int)segOut.y, 0, h - 1);

        GridPath gp;
        try{
            passUnit = u;
            gp = boundedAstar(from.x, from.y, gx, gy, w, h, astarBudget, unitPass);
        }catch(Throwable t){
            detourStreak++;
            return null;
        }
        if(gp == null || gp.xy.size < 4){                    // меньше одного шага — стоим, пробуем объезд
            detourStreak++;
            return null;
        }
        if(gp.reached) detourStreak = 0; else detourStreak++; // частичный маршрут — увеличиваем смещение
        routeBuf.clear();
        for(int i = 2; i < gp.xy.size; i += 2){            // i=0 — стартовый тайл, пропускаем
            Tile t = world.tile(gp.xy.get(i), gp.xy.get(i + 1));
            if(t != null) routeBuf.add(t);
        }
        return routeBuf.isEmpty() ? null : routeBuf;
    }

    // ======================= v1.19: ТОЛПА И ТОЛЧКИ =======================

    /** Собирает союзников той же «высоты» (летающие/наземные) вокруг юнита в crowdX/Y/R. */
    static int gatherCrowd(Unit u){
        int n = 0;
        float range = crowdLookTiles * tilesize + 24f;
        boolean fl = u.isFlying();
        for(Unit o : Groups.unit){
            if(o == u || o.team != u.team || o.dead || o.isFlying() != fl) continue;
            if(!o.within(u, range + o.hitSize / 2f) || CoinMagnet.matchesUnit(o)) continue;
            crowdX[n] = o.x;
            crowdY[n] = o.y;
            crowdR[n] = o.hitSize / 2f;
            if(++n >= crowdMax) break;
        }
        return n;
    }

    /**
     * Облёт толпы своих. Если на курсе меньше {@value #crowdBlockCount} союзников — летим насквозь
     * (толчки гасит {@link #undoPush}). Иначе ищем ближайший к курсу угол (0, ±35 ... ±180),
     * на котором «труба» свободна; сторона держится {@value #crowdHold} кадров.
     */
    static void avoidCrowd(Vec2 move, Unit u){
        if(!crowdAvoid || move.isZero(0.01f)){
            crowdAngle = 0f;
            return;
        }
        int n = gatherCrowd(u);
        if(n < crowdBlockCount){
            crowdAngle = 0f;
            return;
        }
        float len = crowdLookTiles * tilesize;
        float r = u.hitSize / 2f + crowdPad;
        float base = move.angle();
        float side = crowdAngle < 0f ? -1f : 1f;
        boolean holding = crowdAngle != 0f && Time.time < crowdHoldUntil;

        float chosen = Float.NaN, bestAngle = 0f;
        int bestCount = Integer.MAX_VALUE;
        for(int i = holding ? -1 : 0; i < crowdAngles.length * 2; i++){
            float a;
            if(i < 0) a = crowdAngle;
            else if(i < crowdAngles.length) a = crowdAngles[i] * side;
            else{
                a = -crowdAngles[i - crowdAngles.length] * side;       // потом — другая сторона
                if(a == 0f) continue;
            }
            tmp3.trns(base + a, 1f);
            if(!u.isFlying() && a != 0f && !evadePassable(u, tmp3.x, tmp3.y)) continue;
            int c = crowdBlockers(u.x, u.y, tmp3.x, tmp3.y, len, r, crowdX, crowdY, crowdR, n);
            if(c < crowdBlockCount){
                chosen = a;
                break;
            }
            if(c < bestCount){
                bestCount = c;
                bestAngle = a;
            }
        }
        if(Float.isNaN(chosen)) chosen = bestAngle;      // везде плотно — берём наименее плотное
        if(chosen != 0f){
            if(chosen != crowdAngle) crowdHoldUntil = Time.time + crowdHold;
            move.rotate(chosen);
        }
        crowdAngle = chosen;
    }

    /** Есть ли рядом свой юнит вплотную (контакт -> возможна толкотня). */
    static boolean touchingFriend(Unit u, float pad){
        for(Unit o : Groups.unit){
            if(o == u || o.team != u.team || o.dead || !o.isFlying()) continue;
            if(o.within(u, (u.hitSize + o.hitSize) / 2f + pad) && !CoinMagnet.matchesUnit(o)) return true;
        }
        return false;
    }

    /**
     * Откат толчков союзников (только летающие: у наземных откат мог бы вдавить в стену).
     * Прошлый кадр мы запомнили позицию и скорость; ожидаемая позиция = pos + vel * delta.
     * Если реальная чуть отличается и мы касаемся союзника — это толкотня, возвращаем ожидаемую.
     * Большие отклонения (взрыв, телепорт) не трогаем.
     */
    static void undoPush(Unit u){
        if(!ignorePush || !u.isFlying() || pgUnit != u || Time.time - pgTime > 3f) return;
        float ex = pgX + pgVX * Time.delta, ey = pgY + pgVY * Time.delta;
        float err = Mathf.dst(u.x, u.y, ex, ey);
        if(err < 0.02f || err > Math.max(4f, u.hitSize * 0.6f)) return;
        if(!touchingFriend(u, 3f)) return;
        u.set(Mathf.clamp(ex, 0f, world.width() * tilesize), Mathf.clamp(ey, 0f, world.height() * tilesize));
    }

    static void recordPush(Unit u){
        pgUnit = u;
        pgX = u.x;
        pgY = u.y;
        pgVX = u.vel.x;
        pgVY = u.vel.y;
        pgTime = Time.time;
    }

    // ======================= v1.19: ПАТРУЛЬ ТОЧЕК ВЫСАДКИ =======================

    /** Радиус зоны, в которой волна убивает всё при спавне. */
    static float dropR(){
        float r = state.rules.dropZoneRadius;
        return Float.isFinite(r) ? Math.max(0f, r) : 0f;
    }

    /** Список точек высадки волны (раз в ~3с). */
    static void refreshZones(){
        if(Time.time - zonesAt < 180f) return;
        zonesAt = Time.time;
        zx.clear();
        zy.clear();
        try{
            if(spawner != null){
                for(Tile t : spawner.getSpawns()){
                    if(t == null) continue;
                    zx.add(t.worldx());
                    zy.add(t.worldy());
                }
            }
        }catch(Throwable ignored){}
    }

    static void stopPatrol(){
        patrolActive = false;
        patrolHas = false;
        patrolDwellUntil = 0f;
        path = null;
        pathIdx = 0;
    }

    static void patrolAdvance(){
        patrolHas = false;
        path = null;
        pathIdx = 0;
        if(zx.size > 1) patrolIdx = (patrolIdx + 1) % zx.size;
        else patrolAngle = (patrolAngle + patrolSingleStep) % 360f;
    }

    /** Точка на кольце радиуса dropR + запас вокруг текущей зоны (со стороны юнита). */
    static boolean pickPatrolPoint(Unit u){
        int zi = patrolIdx % zx.size;
        float cx = zx.get(zi), cy = zy.get(zi);
        float ring = dropR() + patrolMarginTiles * tilesize;
        float base = zx.size > 1
            ? (Mathf.dst(u.x, u.y, cx, cy) < 1f ? 0f : Angles.angle(cx, cy, u.x, u.y))
            : patrolAngle;
        float maxX = world.width() * tilesize - tilesize * 2f, maxY = world.height() * tilesize - tilesize * 2f;
        for(float off : patrolOffsets){
            float a = base + off;
            float px = Mathf.clamp(cx + Mathf.cosDeg(a) * ring, tilesize * 2f, maxX);
            float py = Mathf.clamp(cy + Mathf.sinDeg(a) * ring, tilesize * 2f, maxY);
            if(Mathf.dst(px, py, cx, cy) < ring - tilesize) continue;     // зажим картой загнал в зону
            if(!u.isFlying()){
                Tile t = world.tileWorld(px, py);
                if(t == null || !u.canPass(t.x, t.y)) continue;
            }
            patrolX = px;
            patrolY = py;
            patrolHas = true;
            return true;
        }
        return false;
    }

    /** Один шаг патруля: к точке у границы зоны, потом пауза и следующая зона. */
    static void patrolStep(Vec2 move, Unit u){
        if(!patrolEnabled()){
            stopPatrol();
            return;
        }
        refreshZones();
        if(zx.size == 0){
            stopPatrol();
            return;
        }
        if(!patrolActive){
            patrolActive = true;
            patrolHas = false;
            path = null;
            pathIdx = 0;
            patrolDwellUntil = 0f;
            int nearest = 0;
            float nd = Float.POSITIVE_INFINITY;
            for(int i = 0; i < zx.size; i++){
                float d = Mathf.dst2(u.x, u.y, zx.get(i), zy.get(i));
                if(d < nd){
                    nd = d;
                    nearest = i;
                }
            }
            patrolIdx = nearest;
            patrolAngle = Angles.angle(zx.get(nearest), zy.get(nearest), u.x, u.y);
        }
        if(Time.time < patrolDwellUntil) return;                       // висим у точки
        if(!patrolHas && !pickPatrolPoint(u)){
            patrolAdvance();
            return;
        }
        if(u.within(patrolX, patrolY, tilesize * patrolArriveTiles)){
            patrolDwellUntil = Time.time + patrolDwell;
            patrolAdvance();
            return;
        }
        destX = patrolX;
        destY = patrolY;
        coinX = patrolX;
        coinY = patrolY;
        if(u.isFlying()) steer(move, u, patrolX, patrolY, 4f);
        else steerGround(move, u);
    }

    /** В патруле не заходим в зоны высадки (и по пути между точками тоже). */
    static void repelFromZones(Vec2 move, Unit u){
        float safe = dropR() + tilesize * 1.5f;
        float speed = u.speed();
        for(int i = 0; i < zx.size; i++){
            if(deflectFromCircle(u.x, u.y, move.x, move.y, zx.get(i), zy.get(i), safe, tilesize * 18f, speed, tmp3)){
                move.set(tmp3);
            }
        }
    }

    // ======================= v1.19: ПЕРЕСАДКА И РЕСПАВН =======================

    /** Множитель к оценке медленного юнита (больше — реже выбирается). */
    public static final float slowPenalty = 4f;
    /** В режиме «самый быстрый» смотрим только юнитов не дальше этого от монеты, тайлы. */
    public static final float boardFastRadiusTiles = 40f;
    /** Саппорт-линейки, которые не берём в приоритет независимо от скорости. */
    private static final String[] slowNames = {"mono", "poly", "mega", "quad", "oct"};

    /** Медленный ли юнит: из списка саппортов или скорость ниже порога (ед./тик). */
    public static boolean isSlowUnit(String typeName, float speed, float minSpeed){
        if(typeName != null){
            for(String n : slowNames){
                if(n.equals(typeName)) return true;
            }
        }
        return speed < minSpeed;
    }

    /**
     * Оценка кандидата на пересадку, МЕНЬШЕ — лучше.
     * sort 0: время прибытия dist/speed; 1: расстояние; 2: обратная скорость (самый быстрый).
     * Медленные умножаются на {@link #slowPenalty} (понижение приоритета, а не запрет).
     */
    public static float boardScore(int sort, float dist, float speed, boolean slow){
        float sp = Math.max(speed, 0.1f);
        float base = sort == 1 ? dist : sort == 2 ? 100f / sp : dist / sp;
        return slow ? base * slowPenalty : base;
    }

    /** Достаточно ли кандидат лучше текущего юнита, чтобы пересаживаться (против метаний туда-сюда). */
    public static boolean worthSwitching(int sort, float bestScore, float curScore){
        if(sort == 1) return bestScore < curScore - boardGainTiles * tilesize;
        return bestScore < curScore * 0.75f;
    }

    private static boolean pilotFlying(Unit o){
        return o.isFlying() || (o.type != null && o.type.flying);
    }

    private static boolean pilotSlow(Unit o, MKUKICSettings s){
        return s.pilotAvoidSlow && o.type != null && isSlowUnit(o.type.name, o.speed(), s.pilotMinSpeed);
    }

    /** Пересесть в свободного союзного юнита, который заметно лучше нас по выбранной сортировке. */
    static boolean tryBoard(Unit u){
        if(!boardNearest || player == null) return false;
        if(NearestBoard.isPinned()) return false;                   // посадка по кнопке — не пересаживаем
        if(Time.time - lastBoard < boardCooldown) return false;
        if(net != null && net.client() && player.justSwitchTo != null) return false;
        if(!state.rules.possessionAllowed) return false;

        MKUKICSettings s = MKUKICSettings.s();
        int sort = Mathf.clamp(s.pilotSort, 0, 2);
        float curScore = boardScore(sort, Mathf.dst(u.x, u.y, coinX, coinY), u.speed(), pilotSlow(u, s));
        float bestScore = Float.POSITIVE_INFINITY;
        Unit best = null;
        for(Unit o : Groups.unit){
            if(o == u || o.team != u.team || o.dead || !o.isAdded() || !o.isAI()) continue;
            if(o instanceof BlockUnitc || o.type == null || !o.type.playerControllable || CoinMagnet.matchesUnit(o)) continue;
            if(s.pilotAirOnly && !pilotFlying(o)) continue;
            float d = Mathf.dst(o.x, o.y, coinX, coinY);
            if(sort == 2 && d > boardFastRadiusTiles * tilesize) continue;
            float sc = boardScore(sort, d, o.speed(), pilotSlow(o, s));
            if(sc >= bestScore) continue;
            if(!CoinMagnet.landingFor(o, coinX, coinY, landing)) continue;   // наземному некуда встать у монеты
            best = o;
            bestScore = sc;
        }
        if(best == null || !worthSwitching(sort, bestScore, curScore)) return false;
        lastBoard = Time.time;
        path = null;
        pathIdx = 0;
        pgUnit = null;
        crowdAngle = 0f;
        Call.unitControl(player, best);
        return true;
    }

    /** Мы не в юните ядра (пересели раньше)? */
    static boolean notCoreUnit(Unit u){
        try{
            CoreBlock.CoreBuild core = player.team().core();
            if(core == null) return false;
            Block b = core.block;
            return b instanceof CoreBlock cb && cb.unitType != null && u.type != cb.unitType;
        }catch(Throwable t){
            return false;
        }
    }

    /** Монет давно нет, а сидим в чужом юните — респавн в ядре (для патруля нужен юнит ядра). */
    static boolean respawnToCore(Unit u){
        if(!respawnOnIdle || player == null || NearestBoard.isPinned()) return false;
        if(Time.time - noCoinSince < idleRespawnDelay || Time.time - lastRespawn < respawnCooldown) return false;
        if(!notCoreUnit(u)) return false;
        lastRespawn = Time.time;
        stopPatrol();
        pgUnit = null;
       // Call.unitClear(player);
        return false;
    }

    // ======================= УКЛОНЕНИЕ =======================

    /**
     * Комбинирует уклонение с вектором прогресса (ОДИН movePref за кадр).
     * Срочная угроза (близко) — чистое уклонение; дальняя — смесь 40/60.
     * Наземные: направление уклона проверяется на проходимость.
     */
    static void addEvade(Vec2 move, Unit u, MKUKICSettings s){
        Vec2 evade = tmp2.setZero();
        int hits = 0;
        boolean urgent = false;
        float R = Math.max(40f, s.coinPilotDodgeR);
        for(Bullet b : Groups.bullet){
            if(b == null || b.team() == u.team() || !b.within(u, R)) continue;
            Vec2 bv = b.vel();
            if(bv.isZero(0.01f)) continue;
            float ax = u.x - b.x, ay = u.y - b.y;
            float len = Mathf.len(ax, ay);
            if(len < 1f) continue;
            if(aimFactor(ax, ay, bv.x, bv.y) < 0.5f) continue; // летит мимо

            // перпендикуляр к полёту снаряда, в сторону от линии огня
            float px = -bv.y, py = bv.x;
            float sign = sideSign(px, py, ax, ay);
            px *= sign;
            py *= sign;

            if(!u.isFlying()){
                // наземная адаптация: уклон только в проходимую сторону
                if(!evadePassable(u, px, py)){
                    px = -px;
                    py = -py;
                    if(!evadePassable(u, px, py)){
                        // обе стороны заняты — отход назад от снаряда
                        px = -ax;
                        py = -ay;
                        if(!evadePassable(u, px, py)){
                            px = 0f;
                            py = 0f;
                        }
                    }
                }
            }
            float nl = Mathf.len(px, py);
            if(nl < 0.001f) continue;
            float w = evadeWeight(len, R);
            evade.add(px / nl * w, py / nl * w);
            if(len < 60f) urgent = true;
            if(++hits >= 5) break;
        }
        if(hits == 0 || evade.isZero(0.001f)) return;

        evade.setLength(u.speed());
        if(urgent || move.isZero(0.01f)){
            move.set(evade);
        }else{
            move.setLength(u.speed() * 0.4f);
            move.add(evade).limit(u.speed() * 1.15f);
        }
    }

    /** Проходима ли точка в ~1.5 тайла по направлению уклонения. */
    static boolean evadePassable(Unit u, float dx, float dy){
        try{
            float nl = Mathf.len(dx, dy);
            if(nl < 0.001f) return false;
            float ex = u.x + dx / nl * tilesize * 1.5f, ey = u.y + dy / nl * tilesize * 1.5f;
            Tile t = world.tileWorld(ex, ey);
            return t != null && u.canPass(t.x, t.y);
        }catch(Throwable e){
            return false;
        }
    }

    // ======================= АНТИ-ЗАСТРЯВАНИЕ =======================

    static void checkStuck(Unit u){
        if(Time.time - stuckT < 30f) return;
        float moved = Mathf.dst(u.x, u.y, stuckX, stuckY);
        boolean patrolMoving = patrolActive && !hasTarget && patrolHas && Time.time >= patrolDwellUntil;
        if((hasTarget || patrolMoving) && moved < 3f){
            stuckN++;
            if(stuckN >= stuckLimit(detourStreak)){ // ~2с обычно, ~5с пока идёт объезд
                if(hasTarget){
                    CoinMagnet.markVisited(coinX, coinY);
                    hasTarget = false;
                    path = null;
                }else{
                    patrolAdvance();                 // v1.19: точка патруля недоступна — следующая
                }
                stuckN = 0;
                detourStreak = 0;
            }
        }else{
            stuckN = 0;
        }
        stuckX = u.x;
        stuckY = u.y;
        stuckT = Time.time;
    }

    // ======================= ОТРИСОВКА =======================

    /** Линия к цели + точка подъезда + маршрут; в патруле — зоны высадки и точка патруля. */
    public static void draw(){
        try{
            if(!MKUKICSettings.s().coinPilot || !(hasTarget || patrolActive)) return;
            Unit u = pilotUnit();
            if(u == null || state == null || state.isMenu()) return;

            float z = Draw.z();
            float stroke = Lines.getStroke();
            Draw.z(Layer.overlayUI);
            try{
                // маршрут наземного
                if(path != null){
                    Draw.color(Pal.accent, 0.5f);
                    for(int i = pathIdx; i < path.size; i++){
                        Tile wp = path.get(i);
                        if(wp == null) continue;
                        Fill.square(wp.worldx(), wp.worldy(), 1.6f);
                    }
                }
                if(hasTarget){
                    Draw.color(Color.valueOf("ffd36b"), 0.75f);
                    Lines.stroke(1.1f);
                    Lines.line(u.x, u.y, destX, destY);
                    Lines.square(destX, destY, 4.5f, 45f);
                    Lines.circle(coinX, coinY, 6f);
                }else{
                    Draw.color(Pal.remove, 0.35f);
                    Lines.stroke(1f);
                    for(int i = 0; i < zx.size; i++) Lines.circle(zx.get(i), zy.get(i), dropR());
                    if(patrolHas){
                        Draw.color(Color.valueOf("ffd36b"), 0.75f);
                        Lines.line(u.x, u.y, patrolX, patrolY);
                        Lines.square(patrolX, patrolY, 4.5f, 45f);
                    }
                }
            }finally{
                Draw.color();
                Lines.stroke(stroke);
                Draw.z(z);
            }
        }catch(Throwable ignored){}
    }
}
