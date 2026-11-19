package mkukic.client;

import arc.math.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.ui.*;
import mkukic.*;
import mkukic.client.onix.*;
import mkukic.client.qol.*;

import static mindustry.Vars.*;

/**
 * Цикл «угона» юнитов через смену команды (серверная команда /team).
 *
 * Один круг:
 *   1) команда врагов (по умолчанию «/team 2»);
 *   2) садимся в ближайшего подходящего юнита (Call.unitControl);
 *   3) возвращаемся в свою команду («/team 1»);
 *   4) выходим из юнита (Call.unitClear);
 *   5) пауза и заново.
 *
 * Режимы:
 *   0 — один юнит: берётся ближайший к игроку, дальше цикл крутится только на нём
 *       (если он погиб — берётся новый ближайший);
 *   1 — все: каждый круг — ближайший ещё не тронутый юнит, когда все пройдены, список сбрасывается;
 *   2 — только сильные: то же, что «все», но только юниты с макс. HP не ниже порога.
 *
 * Включение — клавиша mkukic_team_swap (по умолчанию K) или окно «Угон юнитов»;
 * отдельные клавиши режимов (по умолчанию не назначены) стартуют цикл сразу в нужном режиме,
 * повторное нажатие той же клавиши — стоп.
 * При остановке посреди круга бот сам возвращает игрока в свою команду.
 */
public class TeamSwap{
    public static final int MODE_ONE = 0, MODE_ALL = 1, MODE_STRONG = 2;
    private static final String[] modeNames = {"Один юнит", "Все юниты", "Только сильные"};

    private enum Phase{ pause, toEnemy, board, toHome, exit }

    private static boolean running;
    private static Phase phase = Phase.pause;
    private static float phaseStart;
    private static Team home;
    private static Unit target, locked;
    private static final IntSet done = new IntSet();
    private static int cycles, retries;
    private static String info = "";

    // ============================ ЧИСТАЯ ЛОГИКА ============================

    public static String modeName(int m){
        return modeNames[Mathf.clamp(m, 0, modeNames.length - 1)];
    }

    /** Подходит ли юнит по режиму (без учёта команды/расстояния). */
    public static boolean passesMode(int mode, float maxHealth, float strongHp){
        return mode != MODE_STRONG || maxHealth >= strongHp;
    }

    // ============================ УПРАВЛЕНИЕ ============================

    public static boolean isRunning(){
        return running;
    }

    public static void toggle(){
        if(running) stop("выключено"); else start();
    }

    /** Клавиша режима: старт в этом режиме или стоп, если он уже идёт. */
    public static void toggleMode(int mode){
        if(running && MKUKICSettings.s().tswapMode == mode){
            stop("выключено");
            return;
        }
        setMode(mode);
        if(!running) start();
    }

    public static void setMode(int mode){
        MKUKICSettings s = MKUKICSettings.s();
        s.tswapMode = Mathf.clamp(mode, 0, 2);
        MKUKICSettings.save();
        locked = null;
        done.clear();
    }

    public static void start(){
        if(player == null || state == null || !state.isGame()) return;
        if(net == null || !net.client()){
            MKUKICMod.toast("[scarlet]Угон юнитов работает только на сервере (команда /team)");
            return;
        }
        if(!state.rules.possessionAllowed){
            MKUKICMod.toast("[scarlet]На этой карте запрещено садиться в юнитов");
            return;
        }
        running = true;
        phase = Phase.pause;
        phaseStart = Time.time;
        home = player.team();
        target = null;
        locked = null;
        done.clear();
        cycles = 0;
        retries = 0;
        info = "";
        MKUKICMod.toast("[accent]Угон юнитов: [white]" + modeName(MKUKICSettings.s().tswapMode));
    }

    public static void stop(String why){
        if(!running) return;
        running = false;
        // остановились посреди круга — возвращаем игрока в свою команду
        if(home != null && player != null && player.team() != home){
            OnixChat.send(MKUKICSettings.s().tswapCmdHome);
        }
        if(player != null && target != null && player.unit() == target){
            Call.unitClear(player);
        }
        target = null;
        MKUKICMod.toast("[accent]Угон юнитов: [white]" + why + " [lightgray](кругов: " + cycles + ")");
    }

    /** Сброс при смене мира — без отправки команд. */
    public static void reset(){
        running = false;
        target = null;
        locked = null;
        done.clear();
        phase = Phase.pause;
    }

    public static String status(){
        if(!running) return "[lightgray]Выключено";
        String p;
        switch(phase){
            case toEnemy: p = "в команду врагов"; break;
            case board: p = "посадка в юнита"; break;
            case toHome: p = "возврат в свою команду"; break;
            case exit: p = "выход из юнита"; break;
            default: p = "пауза";
        }
        return "[lime]Работает[] · " + modeName(MKUKICSettings.s().tswapMode) + " · кругов: " + cycles + "\n[lightgray]" + p
            + (info.isEmpty() ? "" : "\n[gray]" + info);
    }

    // ============================ ЦИКЛ ============================

    public static void update(){
        if(!running) return;
        if(player == null || state == null || !state.isGame() || net == null || !net.client()){
            running = false;
            return;
        }
        MKUKICSettings s = MKUKICSettings.s();
        float step = Math.max(0.1f, s.tswapStepSec) * 60f;
        float elapsed = Time.time - phaseStart;
        float timeout = Math.max(120f, step * 4f);

        switch(phase){
            case pause:
                if(elapsed < step) return;
                home = player.team();
                OnixChat.send(s.tswapCmdEnemy);
                next(Phase.toEnemy);
                break;

            case toEnemy:
                if(player.team() != home){
                    if(elapsed < step) return;   // дать клиенту обработать смену команды
                    target = pickTarget(s);
                    if(target == null){
                        info = "нет подходящих юнитов";
                        OnixChat.send(s.tswapCmdHome);
                        next(Phase.toHome);
                        return;
                    }
                    info = target.type.localizedName + " (" + (int)target.maxHealth() + " HP)";
                    done.add(target.id);
                    Call.unitControl(player, target);
                    next(Phase.board);
                }else if(elapsed > timeout){
                    stop("команда не сменилась — проверь команды в окне");
                }
                break;

            case board:
                if((player.unit() == target && elapsed >= step * 0.5f) || elapsed > timeout){
                    OnixChat.send(s.tswapCmdHome);
                    next(Phase.toHome);
                }
                break;

            case toHome:
                if(player.team() == home){
                    if(elapsed < step) return;
                    retries = 0;
                    if(target != null && player.unit() == target){
                        Call.unitClear(player);
                        next(Phase.exit);
                    }else{
                        endCycle();
                    }
                }else if(elapsed > timeout){
                    // не оставляем игрока во вражеской команде: повторяем команду возврата
                    if(++retries > 5){
                        stop("не удалось вернуться в свою команду!");
                        return;
                    }
                    OnixChat.send(s.tswapCmdHome);
                    phaseStart = Time.time;
                }
                break;

            case exit:
                if(player.unit() != target || elapsed > timeout){
                    endCycle();
                }
                break;
        }
    }

    private static void next(Phase p){
        phase = p;
        phaseStart = Time.time;
    }

    private static void endCycle(){
        cycles++;
        target = null;
        next(Phase.pause);
    }

    private static boolean canTake(Unit o, MKUKICSettings s){
        if(o == null || o == player.unit() || o.dead || !o.isAdded() || o.team != player.team()) return false;
        if(o instanceof BlockUnitc || o.type == null || !o.type.playerControllable || !o.isAI()) return false;
        return passesMode(s.tswapMode, o.maxHealth(), s.tswapStrongHp);
    }

    /** Выбор юнита по режиму; вызывается уже после смены команды (юниты врага = наша текущая команда). */
    private static Unit pickTarget(MKUKICSettings s){
        if(s.tswapMode == MODE_ONE && locked != null && canTake(locked, s)) return locked;

        Unit me = player.unit();
        float px = me != null ? me.x : player.x, py = me != null ? me.y : player.y;

        Unit best = nearest(s, px, py, s.tswapMode != MODE_ONE);
        if(best == null && s.tswapMode != MODE_ONE && done.size > 0){
            done.clear();   // прошли всех — начинаем новый проход
            best = nearest(s, px, py, true);
        }
        if(s.tswapMode == MODE_ONE) locked = best;
        return best;
    }

    private static Unit nearest(MKUKICSettings s, float px, float py, boolean skipDone){
        Unit best = null;
        float bd = Float.MAX_VALUE;
        for(Unit o : Groups.unit){
            if(!canTake(o, s)) continue;
            if(skipDone && done.contains(o.id)) continue;
            float d = Mathf.dst2(o.x, o.y, px, py);
            if(d < bd){
                bd = d;
                best = o;
            }
        }
        return best;
    }

    // ============================ ОКНО (id: teamswap) ============================

    public static void build(Table t){
        MKUKICSettings s = MKUKICSettings.s();

        t.add("[lightgray]Бот по кругу: команда врагов → сесть в юнита → своя команда → выйти из юнита.\n"
            + "Включение — клавиша mkukic_team_swap (по умолчанию K), клавиши режимов — Настройки → Управление → mkukic.")
            .left().top().wrap().width(420f).pad(3f, 0f, 6f, 0f).row();

        t.table(c -> {
            c.left();
            c.button("▶ Старт", MKUKICSkin.actBtn, TeamSwap::toggle)
                .minWidth(150f).height(36f).pad(2f)
                .update(b -> b.setText(running ? "[scarlet]■ Стоп" : "[lime]▶ Старт"));
        }).left().row();

        QolWindows.section(t, "Режим");
        t.table(c -> {
            c.left();
            for(int i = 0; i < modeNames.length; i++){
                final int m = i;
                c.button(modeNames[i], MKUKICSkin.toggleBtn, () -> setMode(m))
                    .checked(b -> MKUKICSettings.s().tswapMode == m).minWidth(125f).height(34f).pad(2f);
            }
        }).left().row();
        t.add("[darkgray]Один юнит — цикл крутится на ближайшем юните.\nВсе — каждый круг следующий ближайший, потом заново.\n"
            + "Сильные — как «все», но только с макс. HP не ниже порога.").left().top().wrap().width(420f).padLeft(4f).row();

        QolWindows.section(t, "Настройки");
        t.table(c -> {
            c.left();
            c.add("Команда врагов: ").left();
            c.field(s.tswapCmdEnemy, MKUKICSkin.field, txt -> {
                s.tswapCmdEnemy = txt;
                MKUKICSettings.save();
            }).width(140f).height(32f);
        }).left().row();
        t.table(c -> {
            c.left();
            c.add("Своя команда: ").left();
            c.field(s.tswapCmdHome, MKUKICSkin.field, txt -> {
                s.tswapCmdHome = txt;
                MKUKICSettings.save();
            }).width(140f).height(32f).padLeft(14f);
        }).left().row();
        t.table(c -> {
            c.left();
            c.add("Шаг, сек: ").left();
            c.field(String.valueOf(s.tswapStepSec), MKUKICSkin.field, txt -> {
                if(Strings.canParseFloat(txt)){
                    s.tswapStepSec = Mathf.clamp(Strings.parseFloat(txt), 0.1f, 10f);
                    MKUKICSettings.save();
                }
            }).width(80f).height(32f).padLeft(34f);
            c.add("  [darkgray]пауза между шагами (0.1–10)").left();
        }).left().row();
        t.table(c -> {
            c.left();
            c.add("Порог «сильных» (HP): ").left();
            c.field(String.valueOf((int)s.tswapStrongHp), MKUKICSkin.field, txt -> {
                if(Strings.canParseFloat(txt)){
                    s.tswapStrongHp = Math.max(1f, Strings.parseFloat(txt));
                    MKUKICSettings.save();
                }
            }).width(80f).height(32f);
        }).left().row();

        QolWindows.section(t, "Статус");
        t.label(TeamSwap::status).left().wrap().width(420f).row();
    }
}
