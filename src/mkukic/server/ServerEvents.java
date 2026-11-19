package mkukic.server;

import arc.*;
import arc.util.*;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.type.*;
import mkukic.*;
import mkukic.server.ServerData.*;

import static mindustry.Vars.*;

/** Серверные события: журнал, кредиты, бан-контроль, уведомления админов. */
public class ServerEvents{
    private static boolean inited = false;
    private static final Interval timer = new Interval(3);

    /** Серверные бафы MKUKIC: держим HP полным и множители урона у помеченных игроков. */
    private static void applyBuffs(){
        if(ServerData.godIds.isEmpty() && ServerData.rageIds.isEmpty()) return;
        for(Player p : Groups.player){
            if(p == null || p.dead() || p.unit() == null || p.unit().dead()) continue;
            String id = p.uuid();
            boolean god = ServerData.godIds.contains(id);
            boolean rage = ServerData.rageIds.contains(id);
            if(!god && !rage) continue;
            mindustry.gen.Unit u = p.unit();
            if(god){
                if(u.health() < u.maxHealth()) u.health(u.maxHealth());
                u.unapply(mindustry.content.StatusEffects.burning);
                u.unapply(mindustry.content.StatusEffects.melting);
                u.unapply(mindustry.content.StatusEffects.sapped);
                u.unapply(mindustry.content.StatusEffects.shocked);
                u.unapply(mindustry.content.StatusEffects.electrified);
                u.unapply(mindustry.content.StatusEffects.unmoving);
                u.unapply(mindustry.content.StatusEffects.slow);
            }
            if(rage){
                u.damageMultiplier = 2f;
                u.reloadMultiplier = 2f;
            }
        }
    }

    public static void init(){
        if(inited) return;
        inited = true;

        Events.on(PlayerJoin.class, e -> {
            Player p = e.player;
            if(p == null || p.con == null) return;

            Rec r = ServerData.rec(p);
            r.joins++;
            r.lastName = p.plainName();
            r.lastIP = p.con.address;
            if(r.joins == 1) r.credits += ServerData.cfg.startCredits;
            ServerData.savePlayers();

            // собственный бан MKUKIC (срок может уже истечь — тогда снимаем)
            if(r.banUntil > System.currentTimeMillis()){
                String left = r.banUntil == Long.MAX_VALUE ? "навсегда"
                    : ((r.banUntil - System.currentTimeMillis()) / 60000 + 1) + " мин";
                p.sendMessage("[scarlet]Вы забанены (" + left + "): [white]" + r.banReason);
                p.kick(mindustry.net.Packets.KickReason.banned);
                ServerData.logPlayer(p, "ban-block", "попытка захода под баном");
                return;
            }else if(r.banUntil != 0L){
                r.banUntil = 0L;
                r.banReason = "";
                if(netServer != null){
                    netServer.admins.unbanPlayerID(p.uuid());
                    netServer.admins.unbanPlayerIP(p.con.address);
                }
                ServerData.savePlayers();
            }

            // подтягиваем игровой флаг админа из роли MKUKIC
            if(r.level >= 2) p.admin = true;

            if(ServerData.cfg.logJoins) ServerData.logPlayer(p, "join", "ip " + p.con.address + (p.con.mobile ? " (mobile)" : ""));

            int lvl = ServerData.level(p);
            if(lvl >= 1){
                p.sendMessage("[accent]MKUKIC Server v" + MKUKICMod.version + "[lightgray] — ваша роль: [accent]"
                    + ServerData.roleName(lvl) + "[lightgray]. Команды: /nhelp");
            }
            ServerNet.answer(p, "info", ServerCommands.infoPayload(p));
        });

        Events.on(PlayerLeave.class, e -> {
            Player p = e.player;
            if(p == null) return;
            ServerData.godIds.remove(p.uuid());
            ServerData.rageIds.remove(p.uuid());
            ServerData.rec(p).lastSeen = System.currentTimeMillis();
            ServerData.savePlayers();
            if(ServerData.cfg.logJoins) ServerData.logPlayer(p, "leave", "");
            Anticheat.forget(p);
        });

        Events.on(PlayerChatEvent.class, e -> {
            if(e.player == null || e.message == null) return;
            if(ServerData.cfg.logChat) ServerData.logPlayer(e.player, "chat", e.message);
            Anticheat.checkChat(e.player, e.message);
        });

        Events.on(PlayerBanEvent.class, e -> {
            if(e.uuid != null){
                Rec r = ServerData.rec(e.uuid);
                r.banUntil = Long.MAX_VALUE;
                r.banReason = "vanilla ban";
                ServerData.savePlayers();
                ServerData.log("ban", "vanilla-бан id " + e.uuid);
            }
        });

        Events.on(UnitBulletDestroyEvent.class, e -> {
            if(e == null || e.unit == null || e.bullet == null) return;
            if(!(e.bullet.owner() instanceof Unit killer)) return;
            Player owner = killer.getPlayer();
            if(owner == null) return;
            int add = ServerData.cfg.creditsPerKill;
            if(add > 0){
                ServerData.rec(owner).credits += add;
            }
        });

        Events.on(WaveEvent.class, e -> ServerData.log("wave", "волна " + state.wave));

        Events.on(GameOverEvent.class, e -> ServerData.log("gameover", "победила команда " + (e.winner == null ? "?" : e.winner.name)));

        // периодика
        Events.run(mindustry.game.EventType.Trigger.update, () -> {
            if(timer.get(0, 60f * 60f)) ServerCommands.creditTick();     // кредиты раз в минуту
            if(timer.get(1, 60f * 300f)) ServerData.savePlayers();        // автосохранение раз в 5 минут
            applyBuffs();
            Anticheat.update();
        });
    }
}
