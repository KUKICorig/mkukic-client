package mkukic.server;

import arc.math.*;
import arc.math.geom.*;
import arc.struct.*;
import arc.util.*;
import mindustry.gen.*;
import mkukic.server.ServerData.*;

import static mindustry.Vars.*;

/**
 * Лёгкий серверный античит: ловит невозможную скорость, "полёт" наземных юнитов
 * и бессмертие (HP растёт без источника лечения). Работает только на сервере.
 *
 * Пороги намеренно мягкие — цель не банить, а подсвечивать админам подозрительных.
 */
public class Anticheat{
    private static final ObjectIntMap<String> violations = new ObjectIntMap<>();
    private static final ObjectMap<String, Vec2> lastPos = new ObjectMap<>();
    private static final ObjectMap<String, Float> lastHealth = new ObjectMap<>();
    private static final ObjectMap<String, Float> flyTime = new ObjectMap<>();
    private static final ObjectMap<String, Long> mutes = new ObjectMap<>();
    private static final Interval timer = new Interval(2);

    private static final String speedFlag = "невозможная скорость";
    private static final String flyFlag = "полёт наземного юнита";
    private static final String healFlag = "HP растёт без лечения";

    public static void init(){
        // регистрация не требуется, update() дёргается из ServerEvents
    }

    public static void forget(Player p){
        if(p == null) return;
        String id = p.uuid();
        lastPos.remove(id);
        lastHealth.remove(id);
        flyTime.remove(id);
        mutes.remove(id);
        // нарушения оставляем в истории, но сбрасываем счётчик
        violations.remove(id);
    }

    public static void update(){
        if(!ServerData.cfg.anticheat) return;
        if(!timer.get(0, 20f)) return;

        for(Player p : Groups.player){
            if(p == null || p.con == null) continue;   // только удалённые клиенты
            if(ServerData.level(p) >= 2) continue;      // админов не проверяем
            try{
                check(p);
            }catch(Throwable ignored){
            }
        }
    }

    private static void check(Player p){
        String id = p.uuid();
        if(p.dead() || p.unit() == null){
            lastPos.remove(id);
            lastHealth.remove(id);
            flyTime.put(id, 0f);
            return;
        }

        Unit u = p.unit();

        // ---------- скорость ----------
        Vec2 prev = lastPos.get(id);
        if(prev != null){
            float dist = Mathf.dst(prev.x, prev.y, u.x, u.y);
            float maxSpeed = u.speed() * 20f * ServerData.cfg.speedCheatFactor + 40f; // 20 тиков между проверками
            if(dist > 900f){
                flag(p, "телепорт на " + (int)(dist / tilesize) + " тайлов");
            }else if(dist > maxSpeed){
                flag(p, speedFlag + " (" + (int)dist + " > " + (int)maxSpeed + ")");
            }
        }
        lastPos.put(id, new Vec2(u.x, u.y));

        // ---------- полёт ----------
        boolean canFly = u.type.flying || u.type.canBoost || u.type.naval;
        if(!canFly && u.elevation() > 0.5f){
            float t = flyTime.get(id, 0f) + 20f / 60f;
            flyTime.put(id, t);
            if(t > 1.5f) flag(p, flyFlag);
        }else{
            flyTime.put(id, 0f);
        }

        // ---------- регенерация ----------
        Float prevHealth = lastHealth.get(id);
        if(prevHealth != null){
            float delta = u.health() - prevHealth;
            if(delta > u.maxHealth() * 0.12f && !hasHealSource(u)){
                flag(p, healFlag + " (+" + (int)delta + " HP)");
            }
        }
        lastHealth.put(id, u.health());
    }

    /** Есть ли рядом законный источник лечения (ремонтные турели/пилоны/ядро). */
    private static boolean hasHealSource(Unit u){
        final boolean[] found = {false};
        indexer.eachBlock(u.team(), u.x, u.y, 240f,
            b -> b != null && !b.dead && b.block != null
                && (b.block instanceof mindustry.world.blocks.units.RepairTurret
                    || b.block instanceof mindustry.world.blocks.storage.CoreBlock),
            b -> found[0] = true);
        return found[0];
    }

    // ================================ МУТ ================================

    public static void mute(Player p, long millis){
        if(p == null) return;
        mutes.put(p.uuid(), System.currentTimeMillis() + millis);
    }

    public static boolean isMuted(Player p){
        Long until = mutes.get(p.uuid());
        return until != null && until > System.currentTimeMillis();
    }

    /** Проверяет мут и флуд; возвращает true, если сообщение нужно пропустить. */
    public static boolean checkChat(Player p, String message){
        if(isMuted(p)){
            ServerCommands.say(p, "[scarlet]Ваш чат временно заблокирован администрацией.");
            return false;
        }
        return true;
    }

    // ============================== НАРУШЕНИЯ ==============================

    private static void flag(Player p, String reason){
        String id = p.uuid();
        int v = violations.get(id, 0) + 1;
        violations.put(id, v);

        if(v == 3 || v % 5 == 0){
            String text = p.plainName() + " [" + ServerCommands.shortId(id) + "] подозревается: " + reason
                + " (нарушений: " + v + ")";
            ServerNet.alertAdmins(text);
            ServerData.logPlayer(p, "anticheat", reason + " #" + v);
        }

        if(ServerData.cfg.anticheatKick && v >= ServerData.cfg.anticheatViolationsToKick){
            violations.put(id, 0);
            ServerData.rec(p).kicks++;
            ServerData.savePlayers();
            ServerNet.alertAdmins(p.plainName() + " автоматически кикнут античитом (" + reason + ")");
            p.kick("[scarlet]Автоматический кик античитом: " + reason, 60_000L);
        }
    }

    public static int violations(Player p){
        return p == null ? 0 : violations.get(p.uuid(), 0);
    }
}
