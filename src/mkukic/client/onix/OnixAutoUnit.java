package mkukic.client.onix;

import arc.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.math.*;
import arc.math.geom.*;
import arc.struct.*;
import arc.util.*;
import mindustry.content.*;
import mindustry.entities.units.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.ConstructBlock.*;
import mindustry.world.blocks.storage.CoreBlock.*;
import mkukic.*;
import mkukic.client.*;

import static mindustry.Vars.*;

/**
 * <b>Авто-Уни</b> — порт главной функции Onix Lite 3.4.6 (категория «Бой и авто»).
 *
 * <p>Поведение по описанию клиента:
 * <ul>
 *  <li>есть свои планы стройки — юнит строит их;</li>
 *  <li>не хватает ресурса для плана — летит добывать <b>именно его</b>;</li>
 *  <li>планов нет — обычный автомайн;</li>
 *  <li>использует уже существующие BuildPath и MinePath, не тянет новый код навигации.</li>
 * </ul>
 *
 * <p>В MKUKIC «BuildPath/MinePath» — это {@link OnixNav} поверх ограниченного A* из
 * {@link CoinPilot} (тот же принцип: один навигационный слой на все автопилоты).
 *
 * <p>Планы берутся из трёх источников: сохранённая схема (по курсору), снятая с карты
 * выделенная область и «починить сломанное в радиусе». Очередь живёт между мирами
 * в рамках сессии и рисуется в мире: фиолетовый — ждёт, зелёный — готово,
 * красный — не хватает ресурса, серый — недостижим.
 */
public class OnixAutoUnit{
    /** Одна задача очереди. */
    public static class Task{
        public BuildPlan plan;
        /** 0 = ждёт, 1 = строится, 2 = готово, 3 = пропущен (недостижим/нет ресурса на карте). */
        public int state;
        public Item missing;
        public float skipUntil;

        public int x(){ return plan.x; }
        public int y(){ return plan.y; }
        public float wx(){ return plan.drawx(); }
        public float wy(){ return plan.drawy(); }
    }

    public static final Seq<Task> queue = new Seq<>();
    public static boolean showPath = true;

    private static final OnixNav nav = new OnixNav();
    private static final Interval timer = new Interval(4);
    private static final IntSet planned = new IntSet();
    private static final Vec2 tmp = new Vec2();

    private static Task current;
    private static Item mining;
    private static Tile oreTile;
    private static String status = "";
    private static int built, skipped;
    private static float statusUntil;

    // ============================== СОСТОЯНИЕ ==============================

    public static void reset(){
        queue.clear();
        planned.clear();
        current = null;
        mining = null;
        oreTile = null;
        built = skipped = 0;
        status = "";
        nav.reset();
    }

    public static String status(){
        return status;
    }

    public static int remaining(){
        int n = 0;
        for(Task t : queue) if(t.state == 0 || t.state == 1) n++;
        return n;
    }

    public static int builtCount(){ return built; }
    public static int skippedCount(){ return skipped; }

    // ============================== ИСТОЧНИКИ ПЛАНОВ ==============================

    /** Схема по курсору: все её блоки становятся задачами очереди. */
    public static int loadSchematicAtCursor(@Nullable Schematic sch){
        if(sch == null) sch = control == null || control.input == null ? null : control.input.lastSchematic;
        if(sch == null){
            MKUKICMod.toast("[scarlet]Onix: сначала выберите схему (Ctrl+ПКМ по сохранённой)");
            return 0;
        }
        Vec2 m = Core.input.mouseWorld();
        Tile cursor = m == null ? null : world.tileWorld(m.x, m.y);
        if(cursor == null && player != null) cursor = world.tileWorld(player.x(), player.y());
        if(cursor == null) return 0;

        Seq<BuildPlan> plans = schematics.toPlans(sch, cursor.x - sch.width / 2, cursor.y - sch.height / 2);
        int added = addPlans(plans);
        MKUKICMod.toast("[accent]Onix Авто-Уни[]: +" + added + " планов из схемы «" + sch.name() + "»");
        return added;
    }

    /** Текущие планы/выделение игрока — «снять с карты то, что я уже наметил». */
    public static int capturePlayerPlans(){
        if(player == null || player.unit() == null) return 0;
        Seq<BuildPlan> copy = new Seq<>();
        for(BuildPlan p : player.unit().plans()){
            if(p != null && p.block != null) copy.add(p.copy());
        }
        if(copy.isEmpty() && control != null && control.input != null){
            for(BuildPlan p : control.input.selectPlans) if(p != null && p.block != null) copy.add(p.copy());
            for(BuildPlan p : control.input.linePlans) if(p != null && p.block != null) copy.add(p.copy());
        }
        int added = addPlans(copy);
        MKUKICMod.toast("[accent]Onix Авто-Уни[]: +" + added + " планов снято с вашей разметки");
        return added;
    }

    /**
     * «Восстановить сломанное в радиусе»: все недострои и разрушенные блоки команды
     * в радиусе вокруг курсора (или юнита) уходят в очередь.
     */
    public static int addBrokenAround(float radiusTiles){
        Vec2 c = cursorOrPlayer();
        float r = radiusTiles * tilesize;
        Seq<BuildPlan> copy = new Seq<>();
        indexer.eachBlock(player.team(), c.x, c.y, r,
            b -> b != null && (b instanceof ConstructBuild || b.damaged()),
            b -> {
                if(b instanceof ConstructBuild cb && cb.current != null){
                    copy.add(new BuildPlan(cb.tile.x, cb.tile.y, cb.rotation, cb.current, cb.lastConfig));
                }else if(!(b instanceof ConstructBuild)){
                    copy.add(new BuildPlan(b.tile.x, b.tile.y, b.rotation, b.block, b.config()));
                }
            });
        int added = addPlans(copy);
        MKUKICMod.toast("[accent]Onix Авто-Уни[]: +" + added + " планов восстановления");
        return added;
    }

    /** Добавить один план (например, блок под курсором). */
    public static boolean addTask(int x, int y, int rot, Block block, @Nullable Object config){
        if(block == null || block == Blocks.air) return false;
        return addPlans(Seq.with(new BuildPlan(x, y, rot, block, config))) > 0;
    }

    public static int addPlans(@Nullable Seq<BuildPlan> plans){
        if(plans == null) return 0;
        int max = Mathf.clamp(MKUKICSettings.s().onixAutoMaxPlans, 1, 5000);
        int added = 0;
        for(BuildPlan p : plans){
            if(p == null || p.block == null || p.block == Blocks.air) continue;
            if(queue.size >= max) break;
            int key = Point2.pack(p.x, p.y);
            if(planned.contains(key)) continue;
            planned.add(key);
            Task t = new Task();
            t.plan = p.copy();
            queue.add(t);
            added++;
        }
        return added;
    }

    public static void clearQueue(){
        reset();
        MKUKICMod.toast("Onix Авто-Уни: очередь очищена");
    }

    /** Пропустить текущую задачу (недостижима / не нравится). */
    public static void skipCurrent(){
        Task t = current;
        if(t == null) return;
        t.state = 3;
        t.skipUntil = Time.time + 60f * 30f;
        skipped++;
        current = null;
        mining = null;
        setStatus("пропущено: " + t.plan.block.localizedName);
    }

    // ============================== ЛОГИКА ==============================

    /** Подготовка: выбор задачи, подсчёт нехватки ресурсов (до обновления сущностей). */
    public static void update(){
        if(player == null || player.unit() == null || state == null || !state.isGame()) return;
        MKUKICSettings s = MKUKICSettings.s();
        Unit u = player.unit();

        pruneFinished();
        if(!s.onixAutoBuild && !s.onixAutoMine) return;

        // вернуть в очередь задачи, у которых истёк срок пропуска
        for(Task t : queue){
            if(t.state == 3 && t.skipUntil > 0f && Time.time > t.skipUntil){
                t.state = 0;
                t.skipUntil = 0f;
            }
        }

        if(timer.get(0, 30f) || current == null) selectTask(u, s);
    }

    /** Движение, копка и сдача ресурсов (после обновления сущностей). */
    public static void lateUpdate(){
        try{
            if(player == null || player.unit() == null || state == null || !state.isGame()) return;
            MKUKICSettings s = MKUKICSettings.s();
            Unit u = player.unit();
            if(u.dead()) return;
            if(CoinMagnet.isHolding()) return;                  // магнит монет важнее
            if(!s.onixAutoBuild && !s.onixAutoMine) return;

            if(mining != null && u.canMine()){
                mineLoop(u, s);
                return;
            }
            if(current != null && u.canBuild()){
                buildLoop(u, s);
                return;
            }
            if(s.onixAutoMine && u.canMine()){
                // планов нет — обычный автомайн
                defaultMine(u, s);
            }
        }catch(Throwable t){
            Log.err("[MKUKIC Onix] AutoUnit.lateUpdate", t);
        }
    }

    /** Выбор ближайшей выполнимой задачи. */
    private static void selectTask(Unit u, MKUKICSettings s){
        if(!s.onixAutoBuild){
            current = null;
            mining = null;
            return;
        }
        Task best = null;
        float bestD = Float.MAX_VALUE;
        Task bestNoRes = null;
        float bestNoResD = Float.MAX_VALUE;

        for(Task t : queue){
            if(t.state == 2 || t.state == 3) continue;
            float d = Mathf.dst2(u.x, u.y, t.wx(), t.wy());
            Item lack = missingFor(t.plan, u);
            t.missing = lack;
            if(lack == null){
                if(d < bestD){ bestD = d; best = t; }
            }else if(d < bestNoResD){
                bestNoResD = d;
                bestNoRes = t;
            }
        }

        if(best != null){
            current = best;
            current.state = 1;
            mining = null;
            setStatus("план " + current.plan.block.localizedName + " (" + remaining() + " в очереди)");
        }else if(bestNoRes != null && s.onixAutoMineMissing){
            current = bestNoRes;
            mining = bestNoRes.missing;
            oreTile = null;
            setStatus("нет " + mining.localizedName + " — лечу добывать");
        }else{
            current = null;
            mining = null;
            if(queue.size > 0 && remaining() > 0) setStatus("ждёт ресурсов: " + (bestNoRes != null ? bestNoRes.missing.localizedName : "?"));
            else setStatus("планов нет" + (s.onixAutoMine ? " — автомайн" : ""));
        }
    }

    /**
     * Чего не хватает для плана: требования блока минус (ядро + инвентарь юнита).
     * {@code null} — всего достаточно или ресурса вообще нет на карте.
     */
    public static @Nullable Item missingFor(BuildPlan plan, Unit u){
        if(plan == null || plan.block == null) return null;
        ItemStack[] req = plan.block.requirements;
        if(req == null || req.length == 0) return null;
        CoreBuild core = u.closestCore();
        Item worst = null;
        int worstLack = 0;
        for(ItemStack st : req){
            if(st == null || st.item == null) continue;
            int have = (core != null && core.items != null ? core.items.get(st.item) : 0)
                + (u.stack != null && u.stack.item == st.item ? u.stack.amount : 0);
            int lack = st.amount - have;
            if(lack > worstLack){
                worstLack = lack;
                worst = st.item;
            }
        }
        if(worst == null) return null;
        // ресурса нет на карте и юнит не может его копать — задача невыполнима
        if(!reachableItem(worst, u)) return worst;
        return worst;
    }

    /** Есть ли шанс добыть предмет: руда на карте, крафт или склад. */
    public static boolean reachableItem(Item item, Unit u){
        if(item == null) return false;
        if(indexer.hasOre(item) || indexer.hasWallOre(item)) return true;
        if(u != null && u.canMine(item)) return true;
        CoreBuild core = u == null ? null : u.closestCore();
        return core != null && core.items != null && core.items.get(item) > 0;
    }

    /** Цикл добычи недостающего ресурса. */
    private static void mineLoop(Unit u, MKUKICSettings s){
        CoreBuild core = u.closestCore();

        // инвентарь полон или ресурс добыт — сдать в ядро
        boolean full = u.stack != null && u.stack.amount >= u.itemCapacity() * 0.95f;
        boolean enough = current != null && missingFor(current.plan, u) == null;
        if((full || enough) && s.onixAutoDeliver && core != null){
            if(!u.within(core, mineTransferRange)){
                u.mineTile(null);
                nav.moveTo(u, core.x, core.y, mineTransferRange * 0.6f, s.coinPilotDodge ? s.coinPilotDodgeR : -1f);
                setStatus(full ? "гружусь — везу в ядро" : "ресурс есть — возвращаюсь");
                return;
            }
            u.mineTile(null);
            if(full){
                // рядом с ядром: просто стоим, игра сама сдаёт стек
                setStatus("сдаю " + (u.stack == null ? "" : u.stack.item.localizedName) + " в ядро");
                return;
            }
            mining = null;
            oreTile = null;
            return;
        }

        // выбрать жилу
        if(oreTile == null || !u.validMine(oreTile) || !oreNear(oreTile, u, s)){
            if(timer.get(1, 40f) || oreTile == null){
                oreTile = findOre(u, mining, s);
                if(oreTile == null){
                    setStatus(mining == null ? "автомайн" : "нет жилы: " + mining.localizedName);
                    if(mining != null && current != null && MKUKICSettings.s().onixAutoSkipUnreachable){
                        current.state = 3;
                        current.skipUntil = Time.time + 60f * 60f;
                        current = null;
                        mining = null;
                    }
                    return;
                }
            }
        }
        if(oreTile == null) return;

        Tile t = oreTile;
        if(u.within(t.worldx(), t.worldy(), u.type.mineRange * 0.9f)){
            if(u.mineTile() != t) u.mineTile(t);
            nav.moveTo(u, t.worldx(), t.worldy(), 4f, s.coinPilotDodge ? s.coinPilotDodgeR : -1f);
            setStatus("копаю " + (mining == null ? "" : mining.localizedName));
        }else{
            u.mineTile(t);
            nav.moveTo(u, t.worldx(), t.worldy(), u.type.mineRange * 0.4f, s.coinPilotDodge ? s.coinPilotDodgeR : -1f);
            setStatus("лечу к жиле " + (mining == null ? "" : mining.localizedName));
        }
    }

    private static boolean oreNear(Tile ore, Unit u, MKUKICSettings s){
        return ore.within(u.x(), u.y(), s.onixAutoRadius * tilesize);
    }

    /** Жила нужного предмета: сначала обычная, затем стеновая. */
    public static @Nullable Tile findOre(Unit u, @Nullable Item item, MKUKICSettings s){
        if(u == null) return null;
        if(item == null) item = UnitAI.pickItem(u);
        if(item == null) return null;
        Tile best = null;
        float bestD = Float.MAX_VALUE;
        float lim = s.onixAutoRadius * tilesize;
        // обычный поиск индексатора (быстрый, по кэшу руды)
        Tile t = u.type.mineWalls ? indexer.findClosestWallOre(u, item) : null;
        if(t == null) t = indexer.findClosestOre(u, item);
        if(t != null && t.within(u.x(), u.y(), lim)) return t;
        // если индексатор не нашёл в радиусе — не ограничиваем (Onix летит и далеко)
        if(t != null){
            best = t;
            bestD = t.dst2(u);
        }
        return best;
    }

    /** Цикл стройки текущей задачи. */
    private static void buildLoop(Unit u, MKUKICSettings s){
        Task t = current;
        if(t == null) return;

        // задача уже выполнена другим юнитом/игроком
        if(taskDone(t)){
            t.state = 2;
            built++;
            planned.remove(Point2.pack(t.x(), t.y()));
            current = null;
            setStatus("готово: " + t.plan.block.localizedName + " (" + built + " построено)");
            return;
        }
        Tile tt = world.tile(t.x(), t.y());
        boolean constructing = tt != null && tt.build instanceof ConstructBuild;
        if(!t.plan.placeable(player.team()) && !constructing){
            if(s.onixAutoSkipUnreachable){
                t.state = 3;
                t.skipUntil = Time.time + 60f * 45f;
                skipped++;
                current = null;
                setStatus("нельзя поставить — пропускаю");
            }
            return;
        }

        float range = u.type.buildRange;
        if(u.within(t.wx(), t.wy(), range * 0.85f)){
            // добавляем план в очередь юнита, если его там ещё нет
            boolean has = false;
            for(BuildPlan p : u.plans()){
                if(p != null && p.x == t.x() && p.y == t.y()){ has = true; break; }
            }
            if(!has) u.addBuild(t.plan.copy(), false);
            nav.moveTo(u, t.wx(), t.wy(), Math.min(range * 0.5f, 24f), s.coinPilotDodge ? s.coinPilotDodgeR : -1f);
            setStatus("строю " + t.plan.block.localizedName);
        }else{
            nav.moveTo(u, t.wx(), t.wy(), range * 0.6f, s.coinPilotDodge ? s.coinPilotDodgeR : -1f);
            setStatus("иду к " + t.plan.block.localizedName);
        }

        // анти-застрывание: навигатор несколько раз сбросил маршрут — задача недостижима
        if(nav.stuckResets >= 3 && s.onixAutoSkipUnreachable){
            nav.stuckResets = 0;
            t.state = 3;
            t.skipUntil = Time.time + 60f * 60f;
            skipped++;
            current = null;
            setStatus("не проехать — пропускаю " + t.plan.block.localizedName);
        }
    }

    /** «Планов нет — обычный автомайн». */
    private static void defaultMine(Unit u, MKUKICSettings s){
        if(u.stack != null && u.stack.amount >= u.itemCapacity() * 0.9f){
            CoreBuild core = u.closestCore();
            if(core != null && !u.within(core, mineTransferRange)){
                u.mineTile(null);
                nav.moveTo(u, core.x, core.y, mineTransferRange * 0.6f, s.coinPilotDodge ? s.coinPilotDodgeR : -1f);
                return;
            }
        }
        Tile cur = u.mineTile();
        if(cur != null && u.validMine(cur) && u.within(cur.worldx(), cur.worldy(), u.type.mineRange * 0.9f)){
            nav.moveTo(u, cur.worldx(), cur.worldy(), 6f, s.coinPilotDodge ? s.coinPilotDodgeR : -1f);
            return;
        }
        if(timer.get(2, 40f) || cur == null){
            Item item = UnitAI.pickItem(u);
            if(item == null){
                u.mineTile(null);
                return;
            }
            Tile ore = u.type.mineWalls ? indexer.findClosestWallOre(u, item) : null;
            if(ore == null) ore = indexer.findClosestOre(u, item);
            u.mineTile(ore);
        }
        Tile t = u.mineTile();
        if(t != null){
            nav.moveTo(u, t.worldx(), t.worldy(), u.type.mineRange * 0.4f, s.coinPilotDodge ? s.coinPilotDodgeR : -1f);
            setStatus("автомайн: " + (u.stack != null && u.stack.item != null ? u.stack.item.localizedName : ""));
        }else{
            setStatus("автомайн: руды нет");
        }
    }

    /** Убирает из очереди выполненные/исчезнувшие задачи. */
    private static void pruneFinished(){
        if(!timer.get(3, 60f) && current != null) return;
        for(int i = queue.size - 1; i >= 0; i--){
            Task t = queue.get(i);
            if(t.state == 2){
                // держим в списке ещё немного для отрисовки «готово», потом чистим
                if(t.skipUntil == 0f) t.skipUntil = Time.time + 60f * 8f;
                if(Time.time > t.skipUntil){
                    queue.remove(i);
                    planned.remove(Point2.pack(t.x(), t.y()));
                    if(current == t) current = null;
                }
                continue;
            }
            if(taskDone(t)){
                t.state = 2;
                t.skipUntil = 0f;
                built++;
                if(current == t){
                    current = null;
                    mining = null;
                }
            }
        }
    }

    /** Задача выполнена: на месте стоит нужный блок (не недострой). */
    public static boolean taskDone(Task t){
        if(t == null || t.plan == null) return true;
        Tile tile = world.tile(t.x(), t.y());
        if(tile == null) return true;
        Block b = tile.block();
        if(b == null || b == Blocks.air) return false;
        if(tile.build instanceof ConstructBuild) return false;
        return b == t.plan.block;
    }

    public static @Nullable Task current(){
        return current;
    }

    private static Vec2 cursorOrPlayer(){
        Vec2 m = Core.input == null ? null : Core.input.mouseWorld();
        if(m != null && !Float.isNaN(m.x)) return tmp.set(m);
        if(player != null) return tmp.set(player.x(), player.y());
        return tmp.setZero();
    }

    private static void setStatus(String s){
        status = s;
        statusUntil = Time.time + 180f;
    }

    // ============================== ОТРИСОВКА ==============================

    /** Пунктир к текущей задаче + метки всех задач очереди. */
    public static void draw(){
        if(player == null || player.unit() == null) return;
        Unit u = player.unit();

        for(Task t : queue){
            float x = t.wx(), y = t.wy();
            if(!Mathf.within(x, y, u.x, u.y, Core.camera.width * 1.6f + 400f)) continue;
            Color c;
            switch(t.state){
                case 1 -> c = Onix.theme;
                case 2 -> c = Color.valueOf("7dff8a");
                case 3 -> c = Color.gray;
                default -> c = t.missing != null ? Color.valueOf("ff6b6b") : Onix.theme2;
            }
            Draw.color(c, 0.85f);
            Lines.stroke(1.4f);
            float r = t.plan.block.size * tilesize * 0.5f + 3f + Mathf.absin(Time.time, 8f, 1.2f);
            Lines.square(x, y, r);
            if(t == current){
                Draw.color(c, 0.5f);
                OnixNav.star(x, y, 7f + Mathf.absin(Time.time, 5f, 2f));
            }
        }

        if(current != null){
            Draw.color(Onix.theme, 0.65f);
            Lines.stroke(1.6f);
            Lines.line(u.x, u.y, current.wx(), current.wy());
        }
        if(oreTile != null && mining != null){
            Draw.color(Color.valueOf("ffd166"), 0.8f);
            Lines.stroke(1.3f);
            Lines.square(oreTile.worldx(), oreTile.worldy(), tilesize * 0.8f);
            Draw.color(Color.valueOf("ffd166"), 0.35f);
            Lines.line(u.x, u.y, oreTile.worldx(), oreTile.worldy());
        }
        Draw.reset();
    }
}
