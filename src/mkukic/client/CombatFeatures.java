package mkukic.client;

import arc.*;
import arc.math.*;
import arc.math.geom.*;
import arc.struct.*;
import arc.util.*;
import mindustry.content.*;
import mindustry.entities.*;
import mindustry.gen.*;
import mindustry.entities.units.*;
import mindustry.game.*;
import mindustry.type.*;
import mkukic.*;

import static mindustry.Vars.*;

/**
 * Боевые фичи игрока: автоприцел с упреждением, бафы через настоящие статус-эффекты,
 * бессмертие, полёт, мгновенная перезарядка, расширенные радиусы, авто-реген.
 *
 * Всё сделано так, чтобы не ломать сетевую синхронизацию: бафы выдаются обычными
 * статусами игры, а юнит двигается ровно так же, как это делает vanilla-контроллер.
 */
public class CombatFeatures{
    /** Текущая цель автоприцела (используется для отрисовки линии). */
    public static @Nullable Teamc aimTarget;

    private static @Nullable Unit godUnit;
    private static float lastHealth;

    private static final ObjectMap<UnitType, Float> baseMineSpeed = new ObjectMap<>();
    private static final ObjectMap<UnitType, Float> baseBuildRange = new ObjectMap<>();
    /** Тип юнита, чьи характеристики сейчас пропатчены (чтобы вернуть при смене юнита). */
    private static @Nullable UnitType patchedType;

    // синхронизация god/rage с сервером MKUKIC (иначе сервер их не видит)
    private static Boolean godSent = null, rageSent = null;

    /** Мы последними включили player.shooting — только тогда имеем право выключить. */
    private static boolean aimShotLast = false;

    public static void reset(){
        aimTarget = null;
        godUnit = null;
        lastHealth = 0f;
        godSent = null;
        rageSent = null;
        aimShotLast = false;
        restoreAll();
    }

    public static void update(){
        if(player == null || player.dead() || player.unit() == null) return;
        Unit u = player.unit();
        MKUKICSettings s = MKUKICSettings.s();

        syncWithServer(s);
        registerBaseStats(u);
        applyPatches(u, s);
        applyBuffs(u, s);
        applyGodmode(u, s);
        applyFlight(u, s);
        applyInstantReload(u, s);
        applyAutoHeal(u, s);
        updateAimbot(u, s);
    }

    /**
     * Бессмертие/ярость на сервере с MKUKIC применяются сервером (здоровье и урон у
     * чужих юнитов авторитетны на сервере). Шлём состояние только когда сервер ответил
     * по нашему протоколу — на чужих серверах никакого спама в чат.
     */
    private static void syncWithServer(MKUKICSettings s){
        if(!net.active() || net.server()) return;
        if(!AdminNet.serverHasMod()) return;
        if(godSent == null || godSent != s.godmode){
            godSent = s.godmode;
            AdminNet.send("cmd|/ngod " + (s.godmode ? "on" : "off"));
        }
        if(rageSent == null || rageSent != s.rage){
            rageSent = s.rage;
            AdminNet.send("cmd|/nrage " + (s.rage ? "on" : "off"));
        }
    }

    /** Запоминаем оригинальные характеристики типа юнита до первого патча. */
    private static void registerBaseStats(Unit u){
        UnitType t = u.type;
        if(!baseMineSpeed.containsKey(t)) baseMineSpeed.put(t, t.mineSpeed);
        if(!baseBuildRange.containsKey(t)) baseBuildRange.put(t, t.buildRange);
    }

    // ========================= ПАТЧИ ХАРАКТЕРИСТИК =========================

    private static void applyPatches(Unit u, MKUKICSettings s){
        UnitType t = u.type;

        // сменили юнита — возвращаем прежнему типу оригинальные характеристики,
        // иначе баги скорости копки/стройки остаются на старом типе навсегда (и врагам тоже)
        if(patchedType != null && patchedType != t){
            Float bm = baseMineSpeed.get(patchedType);
            if(bm != null) patchedType.mineSpeed = bm;
            Float bb = baseBuildRange.get(patchedType);
            if(bb != null) patchedType.buildRange = bb;
            patchedType = null;
        }

        float mine = baseMineSpeed.get(t, t.mineSpeed) * (s.mineSpeedMult > 1f ? s.mineSpeedMult : 1f);
        if(!Mathf.equal(t.mineSpeed, mine, 0.001f)) t.mineSpeed = mine;

        float build = baseBuildRange.get(t, t.buildRange) + Math.max(0f, s.buildRangeAdd);
        if(!Mathf.equal(t.buildRange, build, 0.01f)) t.buildRange = build;
        if(!Mathf.equal(t.mineSpeed, baseMineSpeed.get(t, t.mineSpeed), 0.001f)
            || !Mathf.equal(t.buildRange, baseBuildRange.get(t, t.buildRange), 0.01f)){
            patchedType = t;
        }

        // В v8 дальность оружия хранится в общем BulletType, поэтому глобально её не патчим —
        // «Дальность x2» работает только для логики автоприцела/ИИ (см. aimRange()).
    }

    public static void restoreAll(){
        baseMineSpeed.each((t, v) -> t.mineSpeed = v);
        baseBuildRange.each((t, v) -> t.buildRange = v);
        if(godUnit != null){
            godUnit.armorOverride(-1f);
            godUnit = null;
        }
    }

    // ================================ БАФЫ =================================

    private static void applyBuffs(Unit u, MKUKICSettings s){
        // Бафы ставятся напрямую в transient-множители юнита: игра сбрасывает их в 1
        // каждый тик (StatusComp.update), поэтому просто перезаписываем каждый кадр.
        // Эти поля не уходят по сети — серверу без мода они не видны и не мешают.
        if(s.speed) u.speedMultiplier(Math.max(1.05f, s.speedMult));
        if(s.rage){
            u.damageMultiplier(Math.max(1.05f, s.damageMult));
            u.reloadMultiplier(Math.max(1.05f, s.reloadMult));
        }
        if(s.aiAutoBuild) u.buildSpeedMultiplier(1.6f);
        // fortress: множитель прочности (transient-поле, игра сбрасывает каждый тик)
        if(s.fortress && s.healthMult > 1f) u.healthMultiplier = Math.max(1.05f, s.healthMult);

        // броня: игра сбрасывает armorOverride каждый тик, поэтому ставим заново
        if(s.armorAdd > 0f && (s.fortress || s.godmode)){
            u.armorOverride(s.armorAdd);
        }else if(u.armorOverride() >= 0f){
            u.armorOverride(-1f);
        }
    }

// ============================= БЕССМЕРТИЕ ==============================

    private static void applyGodmode(Unit u, MKUKICSettings s){
        if(!s.godmode){
            if(godUnit == u) godUnit = null;
            return;
        }
        if(godUnit != u){
            godUnit = u;
            lastHealth = u.health();
        }
        // компенсация урона: возвращаем всё, что сняли за тик (спасает и от ваншотов)
        float h = u.health();
        if(h < lastHealth - 0.001f){
            u.heal(lastHealth - h);
            if(Mathf.chance(0.25f)) Fx.heal.at(u.x, u.y);
        }
        lastHealth = Math.min(u.health(), u.maxHealth());

        u.unapply(StatusEffects.burning);
        u.unapply(StatusEffects.melting);
        u.unapply(StatusEffects.sapped);
        u.unapply(StatusEffects.shocked);
        u.unapply(StatusEffects.electrified);
        u.unapply(StatusEffects.unmoving);
        u.unapply(StatusEffects.slow);
    }

    // ================================ ПОЛЁТ ================================

    private static void applyFlight(Unit u, MKUKICSettings s){
        if(!s.fly) return;
        if(u.type.flying || u.type.canBoost || u.type.naval) return;
        u.elevation(Mathf.approachDelta(u.elevation(), 1f, 0.09f));
    }

    // ======================= МГНОВЕННАЯ ПЕРЕЗАРЯДКА ========================

    private static void applyInstantReload(Unit u, MKUKICSettings s){
        if(!s.instantReload) return;
        WeaponMount[] mounts = u.mounts();
        for(int i = 0; i < mounts.length; i++){
            WeaponMount m = mounts[i];
            if(m == null || m.weapon == null || !m.weapon.controllable) continue;
            if(m.reload > 0f) m.reload = 0f;
        }
    }

    // ============================== АВТО-ХИЛ ===============================

    private static void applyAutoHeal(Unit u, MKUKICSettings s){
        if(!s.autoHeal || s.godmode) return;
        if(u.health() < u.maxHealth()){
            u.heal(u.maxHealth() * 0.005f * Time.delta);
        }
    }

    // ============================== АВТОПРИЦЕЛ =============================

    public static void updateAimbot(Unit u, MKUKICSettings s){
        if(!s.aimbot || !u.hasWeapons()){
            aimTarget = null;
            releaseShoot(u);
            return;
        }
        if(s.aimOnlyOnFire && !Core.input.keyDown(mindustry.input.Binding.select)) return;

        float range = aimRange(u, s);
        Teamc target = findTarget(u, s, range);
        aimTarget = target;

        if(target == null){
            if(u.isLocal()) u.controlWeapons(false, false);
            releaseShoot(u);
            return;
        }

        float speed = bestBulletSpeed(u);
        Vec2 predicted = (s.aimPredict && speed > 0.01f)
            ? Predict.intercept(u, target, speed)
            : Tmp.v3.set(target.getX(), target.getY());

        u.aim(predicted.x, predicted.y);
        if(!s.aimSilent) u.lookAt(predicted.x, predicted.y);

        boolean aligned = s.aimSilent || Angles.angleDist(u.rotation(), u.angleTo(predicted)) < 35f;
        boolean inRange = u.within(target, range + (target instanceof Sized sz ? sz.hitSize() / 2f : 0f));

        boolean wantShoot = s.aimShoot && u.canShoot() && aligned && inRange;
        if(wantShoot){
            u.controlWeapons(true, true);
            // СЕТВОЕ СОСТОЯНИЕ ВЫСТРЕЛА: сервер стреляет за юнита игрока по
            // player.shooting + player.mouseX/Y из снапшота клиента. Локального
            // controlWeapons мало — урон не наносился. mouseX/Y уже сетевые:
            // DesktopInput копирует в них unit.aimX()/aimY() каждый кадр.
            if(u.isLocal()){
                player.shooting = true;
                aimShotLast = true;
            }
        }else{
            u.controlWeapons(true, false);
            releaseShoot(u);
        }
    }

    /** Выключить сетевой выстрел, если последними его включили мы (не трогать ручную стрельбу). */
    private static void releaseShoot(Unit u){
        if(aimShotLast && u != null && u.isLocal() && player != null){
            player.shooting = false;
        }
        aimShotLast = false;
    }

    public static float aimRange(Unit u, MKUKICSettings s){
        if(s.aimRange > 1f) return s.aimRange;
        float r = u.range();
        if(r <= 1f) r = u.type.maxRange > 0f ? u.type.maxRange : 220f;
        return r * (s.infiniteRange ? 2f : 1f);
    }

    /** Скорость самого быстрого управляемого снаряда — нужна для упреждения. */
    public static float bestBulletSpeed(Unit u){
        float best = -1f;
        WeaponMount[] mounts = u.mounts();
        for(int i = 0; i < mounts.length; i++){
            WeaponMount m = mounts[i];
            if(m == null || m.weapon == null || m.weapon.bullet == null || !m.weapon.controllable) continue;
            if(m.weapon.bullet.speed > best) best = m.weapon.bullet.speed;
        }
        return best <= 0f ? 4f : best;
    }

    public static @Nullable Teamc findTarget(Unit u, MKUKICSettings s, float range){
        boolean air = u.type.targetAir, ground = u.type.targetGround;
        Team team = u.team();

        float camW = Core.camera.width / 2f + 60f;
        float camX = Core.camera.position.x, camY = Core.camera.position.y;

        Unit enemy = Units.bestEnemy(team, u.x, u.y, range,
            e -> !e.dead() && e.hittable() && e.targetable(team) && e.checkTarget(air, ground)
                && !(s.espFog && e.inFogTo(team))
                && (s.aimPriority != 2 || e.isFlying())
                && (s.aimPriority != 3 || !e.isFlying())
                && (s.aimPriority != 4 || e.within(camX, camY, camW)),
            s.aimPriority == 1
                ? (e, x, y) -> -e.maxHealth() + Mathf.dst2(e.x, e.y, x, y) / 3600f
                : (e, x, y) -> Mathf.dst2(e.x, e.y, x, y));

        if(enemy != null) return enemy;

        if(s.aimBuildings && ground){
            return Units.findEnemyTile(team, u.x, u.y, range, b -> b != null && !b.dead
                && !(s.espFog && b.inFogTo(team)));
        }
        return null;
    }
}
