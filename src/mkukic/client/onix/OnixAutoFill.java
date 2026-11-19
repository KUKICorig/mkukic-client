package mkukic.client.onix;

import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.math.*;
import arc.math.geom.*;
import arc.struct.*;
import arc.util.*;
import mindustry.entities.units.*;
import mindustry.gen.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.storage.*;
import mindustry.world.blocks.storage.CoreBlock.*;
import mkukic.*;

import static mindustry.Vars.*;

/**
 * <b>Авто-Филл</b> — порт функции Onix Lite: «развозит ресурсы по голодным постройкам:
 * берёт из ядра, контейнеров и цехов; источники с лучшим запасом выбираются сами;
 * почти полные постройки не доливает; приоритеты блоков и предметов: диалог со
 * степперами, -1 = пропустить, кнопка сброса».
 *
 * <p>Работает на собственном юните игрока (как и остальная автоматизация MKUKIC):
 * рейс «источник → получатель» считается заранее, поэтому юнит не мечется между
 * постройками. Навигация — общий {@link OnixNav}.
 */
public class OnixAutoFill{
    public static boolean showPath = true;

    /** Один рейс: откуда, кому, что и сколько. */
    public static class Trip{
        public Building source, target;
        public Item item;
        public int amount;
        /** false = ещё надо забрать у источника. */
        public boolean picked;

        public boolean valid(){
            return source != null && !source.dead && target != null && !target.dead
                && item != null && amount > 0;
        }
    }

    private static final Seq<Trip> pending = new Seq<>();
    private static final Seq<Building> hungry = new Seq<>();
    private static final OnixNav nav = new OnixNav();
    private static final Interval timer = new Interval(3);
    private static final Vec2 tmp = new Vec2();

    private static Trip current;
    private static String status = "";
    private static int trips, delivered;
    private static float scanR;

    public static void reset(){
        pending.clear();
        hungry.clear();
        current = null;
        status = "";
        trips = delivered = 0;
        nav.reset();
    }

    public static String status(){ return status; }
    public static int tripsDone(){ return trips; }
    public static int itemsDelivered(){ return delivered; }
    public static int pendingCount(){ return pending.size; }

    // ============================== ПОИСК РАБОТЫ ==============================

    /**
     * Сканирование «голодных» построек рядом с юнитом.
     * Голодная = принимает предметы, не забита выше {@code onixFillFullRatio}
     * и реально нуждается (низкая эффективность или пустой буфер).
     */
    public static void scan(Unit u, MKUKICSettings s){
        hungry.clear();
        scanR = s.onixFillRadius * tilesize;
        float cx = u.x, cy = u.y;
        indexer.eachBlock(u.team(), cx, cy, scanR,
            b -> isHungry(b, s),
            b -> hungry.add(b));
        hungry.sort((a, b) -> Float.compare(score(a, cx, cy), score(b, cx, cy)));
    }

    /** Нуждается ли постройка в подвозе. */
    public static boolean isHungry(Building b, MKUKICSettings s){
        if(b == null || b.dead || b.items == null) return false;
        if(!b.block.hasItems) return false;
        if(b instanceof CoreBuild) return false;            // ядро — источник, не получатель
        if(b.block instanceof StorageBlock) return false;   // склады тоже источники
        int cap = Math.max(1, b.getMaximumAccepted(null) > 0 ? b.getMaximumAccepted(null) : b.block.itemCapacity);
        if(b.items.total() >= cap * s.onixFillFullRatio) return false; // почти полную не доливаем

        // приоритет -1 = пропустить
        if(itemPriorityOf(b) < 0 && blockPriorityOf(b) < 0) return false;

        // реальная нужда: пустой буфер или низкая эффективность
        boolean starving = b.items.total() == 0;
        boolean lowEff = b.efficiency < 0.99f && b.shouldConsume();
        boolean wants = false;
        for(Item it : content.items()){
            if(it == null) continue;
            if(priorityOfItem(it) < 0) continue;
            if(b.acceptItem(b, it)){ wants = true; break; }
        }
        return wants && (starving || lowEff);
    }

    /** Чем меньше — тем нужнее: нужда по эффективности + дистанция. */
    private static float score(Building b, float cx, float cy){
        float need = 1f - Mathf.clamp(b.efficiency);
        int bp = blockPriorityOf(b);
        float prio = bp > 0 ? -bp * 1000f : 0f;
        return prio + need * 400f + Mathf.dst(b.x, b.y, cx, cy) / tilesize;
    }

    /** Подходящий предмет для получателя: учитывает приоритеты и запас источников. */
    private static @Nullable Item itemFor(Building target, MKUKICSettings s){
        Item best = null;
        int bestPrio = Integer.MAX_VALUE;
        for(Item it : content.items()){
            if(it == null) continue;
            int p = priorityOfItem(it);
            if(p < 0) continue;                       // -1 = пропустить предмет
            if(!target.acceptItem(target, it)) continue;
            if(p == 0) p = 1000;                      // без приоритета — в конец
            if(p < bestPrio){
                bestPrio = p;
                best = it;
            }
        }
        return best;
    }

    public static int priorityOfItem(Item it){
        if(it == null) return 0;
        Seq<String> list = MKUKICSettings.s().onixFillItemPriority;
        for(int i = 0; i < list.size; i++){
            if(list.get(i).equalsIgnoreCase(it.name)) return i;
        }
        return 0;
    }

    public static int blockPriorityOf(Building b){
        if(b == null) return 0;
        Seq<String> list = MKUKICSettings.s().onixFillBlockPriority;
        for(int i = 0; i < list.size; i++){
            if(list.get(i).equalsIgnoreCase(b.block.name)) return i;
        }
        return 0;
    }

    private static int itemPriorityOf(Building b){
        Item it = itemFor(b, MKUKICSettings.s());
        return it == null ? -1 : priorityOfItem(it);
    }

    /**
     * Лучший источник: ядро / контейнер / цех с наибольшим запасом предмета.
     * «Источники с лучшим запасом выбираются сами».
     */
    public static @Nullable Building bestSource(Item item, Unit u, MKUKICSettings s){
        Building best = null;
        int bestAmount = 0;
        float lim = s.onixFillRadius * tilesize;
        for(Building b : Groups.build){
            if(b == null || b.dead || b.team != u.team() || b.items == null) continue;
            if(!b.within(u.x, u.y, lim)) continue;
            boolean core = b instanceof CoreBuild;
            boolean storage = b.block instanceof StorageBlock;
            if(!core && !storage && !s.onixFillFromFactory) continue;
            if(core && !s.onixFillFromCore) continue;
            if(storage && !core && !s.onixFillFromStorage) continue;
            int have = b.items.get(item);
            // у источника оставляем неприкосновенный запас, чтобы не обесточить базу
            int keep = core ? 0 : Math.min(have, (int)(b.block.itemCapacity * 0.15f));
            int free = have - keep;
            if(free > bestAmount){
                bestAmount = free;
                best = b;
            }
        }
        return best;
    }

    // ============================== ЛОГИКА РЕЙСА ==============================

    public static void update(){
        if(player == null || player.unit() == null || state == null || !state.isGame()) return;
        MKUKICSettings s = MKUKICSettings.s();
        Unit u = player.unit();

        // чистим протухшие рейсы
        for(int i = pending.size - 1; i >= 0; i--){
            if(!pending.get(i).valid()) pending.remove(i);
        }
        if(current != null && !current.valid()){
            current = null;
            setStatus("рейс отменён");
        }

        if(timer.get(0, 120f) || (current == null && pending.isEmpty())){
            scan(u, s);
            planTrips(u, s);
        }
    }

    /** Составить рейсы по списку голодных построек. */
    private static void planTrips(Unit u, MKUKICSettings s){
        int maxNew = 8;
        for(Building target : hungry){
            if(pending.size >= maxNew) break;
            Item item = itemFor(target, s);
            if(item == null) continue;
            boolean already = false;
            for(Trip t : pending) if(t.target == target) already = true;
            if(already || (current != null && current.target == target)) continue;

            Building src = bestSource(item, u, s);
            if(src == null) continue;

            int cap = target.getMaximumAccepted(item);
            if(cap <= 0) cap = target.block.itemCapacity;
            int space = Math.max(0, cap - target.items.get(item));
            int amount = Math.min(Math.min(s.onixFillBatch, space), src.items.get(item));
            if(amount <= 0) continue;

            Trip t = new Trip();
            t.source = src;
            t.target = target;
            t.item = item;
            t.amount = amount;
            pending.add(t);
        }
    }

    public static void lateUpdate(){
        try{
            if(player == null || player.unit() == null || state == null || !state.isGame()) return;
            MKUKICSettings s = MKUKICSettings.s();
            Unit u = player.unit();
            if(u.dead()) return;

            if(current == null){
                if(pending.isEmpty()){
                    setStatus(hungry.isEmpty() ? "голодных построек нет" : "нет запаса на источниках");
                    return;
                }
                current = pending.remove(0);
            }
            Trip t = current;
            if(!t.valid()){
                current = null;
                return;
            }

            float dodge = s.coinPilotDodge ? s.coinPilotDodgeR : -1f;

            if(!t.picked){
                // --- забираем у источника ---
                if(u.within(t.source.x, t.source.y, tilesize * (t.source.block.size * 0.5f + 1.6f))){
                    int take = takeFrom(t.source, u, t.item, Math.min(t.amount, s.onixFillBatch));
                    if(take > 0){
                        t.amount = take;
                        t.picked = true;
                        setStatus("забрал " + take + " " + t.item.localizedName + " → " + t.target.block.localizedName);
                    }else{
                        // источник опустел — рейс не нужен
                        pending.remove(t);
                        current = null;
                        setStatus("источник пуст, беру следующий");
                        return;
                    }
                }else{
                    nav.moveTo(u, t.source.x, t.source.y, tilesize * (t.source.block.size * 0.5f + 1.2f), dodge);
                    setStatus("к источнику: " + t.source.block.localizedName);
                }
            }else{
                // --- везём получателю ---
                if(u.within(t.target.x, t.target.y, tilesize * (t.target.block.size * 0.5f + 1.6f))){
                    int put = putInto(t.target, u, t.item, u.stack == null ? 0 : u.stack.amount);
                    delivered += put;
                    trips++;
                    pending.remove(t);
                    current = null;
                    if(put > 0) setStatus("доставил " + put + " " + t.item.localizedName + " в " + t.target.block.localizedName);
                    else setStatus("получатель уже полон");
                }else{
                    nav.moveTo(u, t.target.x, t.target.y, tilesize * (t.target.block.size * 0.5f + 1.2f), dodge);
                    setStatus("везу " + t.item.localizedName + " → " + t.target.block.localizedName);
                }
            }

            if(nav.stuckResets >= 3){
                nav.stuckResets = 0;
                pending.remove(t);
                current = null;
                setStatus("не проехать — рейс сброшен");
            }
        }catch(Throwable e){
            Log.err("[MKUKIC Onix] AutoFill.lateUpdate", e);
        }
    }

    /** Забрать предмет из постройки в инвентарь юнита. */
    public static int takeFrom(Building src, Unit u, Item item, int want){
        if(src == null || src.items == null || u == null || item == null) return 0;
        int have = src.items.get(item);
        int space = Math.max(0, u.itemCapacity() - (u.stack == null ? 0 : u.stack.amount));
        int take = Math.min(Math.min(want, have), space);
        if(take <= 0) return 0;
        src.items.remove(item, take);
        if(u.stack == null) u.stack = new ItemStack(item, take);
        else u.stack.set(item, (u.stack.item == item ? u.stack.amount : 0) + take);
        return take;
    }

    /**
     * Положить предмет из инвентаря юнита в постройку.
     * {@code acceptItem/handleItem} требуют Building-источник — передаём ядро команды
     * (игра сама подставит его в логику приёма), это тот же приём, что у ванильного
     * разгрузчика.
     */
    public static int putInto(Building dst, Unit u, Item item, int want){
        if(dst == null || dst.dead || u == null || item == null) return 0;
        if(u.stack == null || u.stack.item != item || u.stack.amount <= 0) return 0;
        Building src = u.closestCore();
        if(src == null) src = dst;
        int put = Math.min(want, u.stack.amount);
        int done = 0;
        while(put > 0 && dst.acceptItem(src, item)){
            dst.handleItem(src, item);
            put--;
            done++;
        }
        u.stack.amount -= done;
        if(u.stack.amount <= 0){
            u.stack.amount = 0;
            u.stack.item = null;
        }
        return done;
    }

    private static void setStatus(String s){
        status = s;
    }

    // ============================== ОТРИСОВКА ==============================

    /** Линии текущих рейсов: источник → получатель, плюс метка цели. */
    public static void draw(){
        if(player == null || player.unit() == null) return;
        Unit u = player.unit();
        Lines.stroke(1.4f);
        for(Trip t : pending){
            if(!t.valid()) continue;
            float ax = t.picked ? u.x : t.source.x, ay = t.picked ? u.y : t.source.y;
            Draw.color(t.picked ? Onix.theme2 : Onix.theme, 0.35f);
            Lines.line(ax, ay, t.target.x, t.target.y);
        }
        if(current != null && current.valid()){
            Building b = current.picked ? current.target : current.source;
            Draw.color(Color.valueOf("ffd166"), 0.9f);
            Lines.stroke(1.8f);
            Lines.square(b.x, b.y, b.block.size * tilesize * 0.5f + 4f + Mathf.absin(Time.time, 7f, 1.5f));
            Draw.color(Color.valueOf("ffd166"), 0.5f);
            Lines.line(u.x, u.y, b.x, b.y);
        }
        Draw.reset();
    }
}
