package mkukic.client;

import arc.math.*;
import arc.math.geom.*;
import arc.struct.*;
import arc.util.*;
import mindustry.content.*;
import mindustry.entities.*;
import mindustry.entities.units.*;
import mindustry.gen.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.ConstructBlock.*;
import mindustry.world.blocks.storage.CoreBlock.*;
import mkukic.*;

import static mindustry.Vars.*;

/**
 * Автопилот для СОБСТВЕННОГО юнита игрока: копает как poly, строит/чинит как mega,
 * помогает указанным игрокам и уворачивается от снарядов.
 * Всё локальное: mineTile и build-планы своего юнита клиент и так синхронизирует сам.
 */
public class PlayerAI{
    private static final Interval timer = new Interval(3);
    private static final Vec2 vec = new Vec2();
    private static @Nullable Tile lastOre;

    public static void reset(){
        lastOre = null;
    }

    /** До обновления сущностей: подготовка планов стройки/ремонта. */
    public static void update(){
        if(player == null || player.unit() == null) return;
        MKUKICSettings s = MKUKICSettings.s();
        if(!s.paEnabled) return;
        Unit u = player.unit();
        if((s.paBuild || s.paRepair) && u.canBuild()) managePlans(u, s);
    }

    /** После обновления сущностей: движение, копка, уклонение. */
    public static void lateUpdate(){
        if(player == null || player.unit() == null) return;
        MKUKICSettings s = MKUKICSettings.s();
        if(!s.paEnabled) return;
        Unit u = player.unit();
        if(u.dead()) return;

        if(s.paMine && u.canMine()){
            mineSelf(u, s);
        }else if((s.paBuild || s.paRepair) && u.canBuild()){
            // пока копаем — стройка подождёт
            goToPlan(u);
        }

        // Уклонение вызывается ПОСЛЕ движения: movePref перезаписывает вектор,
        // поэтому при угрозе снарядом evade должен идти последним (иначе мёртв).
        if(s.paDodge) dodge(u, s);
    }

    // =============================== КОПКА ===============================

    private static void mineSelf(Unit u, MKUKICSettings s){
        // сдать груз
        if(u.stack().amount >= u.itemCapacity() * 0.9f){
            CoreBuild core = u.closestCore();
            if(core != null && !u.within(core, mineTransferRange * 0.7f)){
                u.mineTile(null);
                UnitAI.moveTo(u, core, mineTransferRange * 0.5f);
                return;
            }
            if(core != null && u.within(core, mineTransferRange)){
                u.mineTile(null);
                return;
            }
        }

        Tile cur = u.mineTile();
        if(cur != null && u.validMine(cur) && u.within(cur.worldx(), cur.worldy(), u.type.mineRange * 0.9f)){
            UnitAI.moveTo(u, cur.worldx(), cur.worldy(), 6f);
            return;
        }

        if(timer.get(0, 40f) || cur == null){
            Item item = UnitAI.pickItem(u);
            if(item == null){
                u.mineTile(null);
                return;
            }
            Tile ore = u.type.mineWalls ? indexer.findClosestWallOre(u, item) : null;
            if(ore == null) ore = indexer.findClosestOre(u, item);
            lastOre = ore;
            u.mineTile(ore);
        }
        Tile t = u.mineTile() != null ? u.mineTile() : lastOre;
        if(t != null) UnitAI.moveTo(u, t.worldx(), t.worldy(), u.type.mineRange * 0.5f);
    }

    // ============================ СТРОЙКА/РЕМОНТ ============================

    private static void managePlans(Unit u, MKUKICSettings s){
        Queue<BuildPlan> plans = u.plans();
        if(plans.size >= 3) return;
        if(!timer.get(1, 30f)) return;

        float radius = 600f;
        // 1) свои недострои и повреждённые рядом
        if(!addNearbyWork(u, u.x, u.y, radius, s)) {
            // 2) помощь игрокам: ближайшие N союзников-игроков
            if(!s.paHelpPlayers.isEmpty()){
                for(String nm : s.paHelpPlayers){
                    Player mp = Groups.player.find(p -> p != null && p != player && p.name().equals(nm)
                        && p.unit() != null && !p.unit().dead());
                    if(mp != null && addNearbyWork(u, mp.unit().x, mp.unit().y, 400f, s)) break;
                }
            }
        }
    }

    /** Добавить план стройки/ремонта около точки; true если добавили. */
    private static boolean addNearbyWork(Unit u, float x, float y, float radius, MKUKICSettings s){
        Building best = null;
        float bestScore = Float.MAX_VALUE;
        for(Building b : indexer.getDamaged(u.team())){
            if(b == null || b.dead || b.healthf() >= 0.98f) continue;
            if(!b.within(x, y, radius)) continue;
            float score = b.dst2(x, y);
            if(score < bestScore){
                bestScore = score;
                best = b;
            }
        }
        if(s.paBuild){
            final Building[] holder = {best};
            final float[] bs = {bestScore};
            indexer.eachBlock(u.team(), x, y, radius,
                b -> b instanceof ConstructBuild cb && cb.progress < 0.999f,
                b -> {
                    float sc = b.dst2(x, y) - 1f;
                    if(sc < bs[0]){
                        bs[0] = sc;
                        holder[0] = b;
                    }
                });
            best = holder[0];
            bestScore = bs[0];
        }
        if(best == null) return false;
        // не дублировать план
        for(BuildPlan p : u.plans()){
            if(p.x == best.tileX() && p.y == best.tileY()) return true;
        }
        if(best instanceof ConstructBuild cb){
            u.addBuild(new BuildPlan(cb.tileX(), cb.tileY(), cb.rotation, cb.current, cb.lastConfig));
        }else{
            u.addBuild(new BuildPlan(best.tileX(), best.tileY(), best.rotation, best.block));
        }
        return true;
    }

    private static void goToPlan(Unit u){
        if(u.plans().size == 0) return;
        BuildPlan p = u.plans().first();
        if(p == null) return;
        float wx = p.x * tilesize + tilesize / 2f, wy = p.y * tilesize + tilesize / 2f;
        if(!u.within(wx, wy, u.type.buildRange * 0.8f)){
            UnitAI.moveTo(u, wx, wy, u.type.buildRange * 0.6f);
        }
    }

    // ============================ УКЛОНЕНИЕ ============================

    private static void dodge(Unit u, MKUKICSettings s){
        Vec2 evade = vec.setZero();
        int hits = 0;
        for(Bullet b : Groups.bullet){
            if(b == null || b.team() == player.team() || !b.within(u, s.paDodgeRadius)) continue;
            Vec2 bv = b.vel();
            if(bv.isZero(0.01f)) continue;
            // снаряд летит в нас: вектор «снаряд->мы» сонаправлен со скоростью снаряда
            float ax = u.x - b.x, ay = u.y - b.y;
            float dot = ax * bv.x + ay * bv.y;
            float len = Mathf.len(ax, ay);
            if(len < 1f || dot / (len * bv.len()) < 0.5f) continue;
            // уклон перпендикулярно снаряду, в сторону от линии огня
            float px = -bv.y, py = bv.x;
            if(px * ax + py * ay < 0){
                px = -px;
                py = -py;
            }
            evade.add(px, py);
            hits++;
            if(hits >= 4) break;
        }
        if(hits > 0 && !evade.isZero()){
            evade.setLength(u.speed() * 1.3f);
            u.movePref(evade);
        }
    }
}
