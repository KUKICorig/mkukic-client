package mkukic.client;

import arc.*;
import arc.math.*;
import arc.util.*;
import mindustry.gen.*;
import mkukic.*;

import static mindustry.Vars.*;

/**
 * «Сесть в ближайшего юнита» — одна клавиша (mkukic_board_nearest, по умолчанию N):
 *   1-е нажатие — садимся в ближайшего свободного союзного юнита (Call.unitControl) и включаем режим;
 *   2-е нажатие — выходим из юнита (Call.unitClear) и выключаем режим.
 *
 * Пока режим включён, автопилот до монет НЕ пересаживает вас в других юнитов и не делает
 * респавн в ядре (иначе он выкинул бы вас из выбранного юнита). По желанию режим сам
 * включает автопилот до монет и выключает его при выходе (настройка «автопилот при посадке»).
 */
public class NearestBoard{
    /** Ключ настройки «включать автопилот при посадке» (в Core.settings, как и патруль). */
    public static final String pilotKey = "mkukic-board-pilot";
    /** Сколько кадров ждём подтверждения смены юнита от сервера, прежде чем считать посадку потерянной. */
    private static final float confirmFrames = 90f;
    /** Кулдаун между нажатиями, кадров (защита от дребезга и двойных сетевых вызовов). */
    private static final float cooldown = 20f;

    private static boolean active;
    private static Unit boarded;
    private static float boardAt = -999f, lastPress = -999f;
    private static boolean pilotWasOff;

    public static boolean isActive(){
        return active;
    }

    /** Автопилот до монет не должен менять юнита/респавниться, пока мы сидим «по кнопке». */
    public static boolean isPinned(){
        return active;
    }

    public static boolean pilotOnBoard(){
        return Core.settings != null && Core.settings.getBool(pilotKey, false);
    }

    public static void setPilotOnBoard(boolean v){
        if(Core.settings != null) Core.settings.put(pilotKey, v);
    }

    /** Название юнита, в котором сидим по кнопке (для окон/подсказок). */
    public static String currentName(){
        return active && boarded != null && boarded.type != null ? boarded.type.localizedName : "—";
    }

    /** Клавиша: включить (сесть) или выключить (выйти). */
    public static void toggle(){
        if(player == null || state == null || !state.isGame()) return;
        if(Time.time - lastPress < cooldown) return;
        lastPress = Time.time;

        if(active){
            leave(true);
        }else{
            board();
        }
    }

    private static void board(){
        if(!state.rules.possessionAllowed){
            MKUKICMod.toast("[scarlet]Посадка в юнитов запрещена правилами карты");
            return;
        }
        if(net != null && net.client() && player.justSwitchTo != null){
            MKUKICMod.toast("[lightgray]Подожди, предыдущая пересадка ещё не завершилась");
            return;
        }

        Unit best = nearest();
        if(best == null){
            MKUKICMod.toast("[lightgray]Рядом нет свободных союзных юнитов");
            return;
        }

        MKUKICSettings s = MKUKICSettings.s();
        boarded = best;
        active = true;
        boardAt = Time.time;
        CoinPilot.reset();                       // чистое состояние: путь/цель/патруль привязаны к прошлому юниту

        if(pilotOnBoard() && !s.coinPilot){
            pilotWasOff = true;
            s.coinPilot = true;
            MKUKICSettings.save();
        }else{
            pilotWasOff = false;
        }

        Call.unitControl(player, best);
        MKUKICMod.toast("[accent]Посадка: [white]" + best.type.localizedName
            + (pilotWasOff ? " [lightgray]+ автопилот до монет" : "")
            + " [lightgray](та же клавиша — выйти)");
    }

    /** Выход из юнита и выключение режима. */
    private static void leave(boolean clearUnit){
        if(clearUnit && player != null && !player.dead()){
            Call.unitClear(player);                  // выход из юнита — респавн в ядре
        }

        MKUKICSettings s = MKUKICSettings.s();
        if(pilotWasOff){
            s.coinPilot = false;
            MKUKICSettings.save();
            CoinPilot.reset();
        }
        pilotWasOff = false;
        active = false;
        boarded = null;
        MKUKICMod.toast(clearUnit ? "[lightgray]Вышли из юнита" : "[lightgray]Юнит потерян — режим посадки выключен");
    }

    /** Каждый кадр: если юнита убили/забрали — тихо выключаем режим, чтобы не залипнуть во «включено». */
    public static void tick(){
        if(!active) return;
        if(player == null || state == null || !state.isGame()){
            reset();
            return;
        }
        if(Time.time - boardAt < confirmFrames) return;       // ждём сервер
        Unit me = player.unit();
        if(player.dead() || me == null || me != boarded || boarded == null || boarded.dead()){
            leave(false);
        }
    }

    /** Сброс при смене мира (без вызовов в сеть). */
    public static void reset(){
        active = false;
        boarded = null;
        pilotWasOff = false;
    }

    /** Ближайший к нам свободный союзный юнит, в которого можно сесть. */
    static Unit nearest(){
        Unit me = player.unit();
        float px = me != null && !player.dead() ? me.x : player.x;
        float py = me != null && !player.dead() ? me.y : player.y;

        Unit best = null;
        float bd = Float.MAX_VALUE;
        for(Unit o : Groups.unit){
            if(!canTake(o, me)) continue;
            float d = Mathf.dst2(o.x, o.y, px, py);
            if(d < bd){
                bd = d;
                best = o;
            }
        }
        return best;
    }

    private static boolean canTake(Unit o, Unit me){
        if(o == null || o == me || o.dead || !o.isAdded() || o.team != player.team()) return false;
        if(o instanceof BlockUnitc || o.type == null || !o.type.playerControllable || !o.isAI()) return false;
        return !CoinMagnet.matchesUnit(o);        // «монеты» в этом моде — тоже юниты, их не берём
    }
}
