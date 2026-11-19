package mkukic.client;

import arc.*;
import arc.input.*;
import arc.util.*;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mkukic.*;
import mkukic.client.qol.*;

import static mindustry.Vars.*;

/** Главный клиентский цикл: горячие клавиши, тики фич, отрисовка. */
public class ClientHook{
    public static boolean inited = false;

    public static void init(){
        if(inited) return;
        inited = true;

        Events.run(Trigger.update, ClientHook::update);
        Events.run(Trigger.afterGameUpdate, ClientHook::lateUpdate);
        Events.run(Trigger.afterGameUpdate, CoinPilot::lateUpdate);
        Events.run(Trigger.update, CoinMagnet::updateMagnet);
        Events.run(Trigger.preDraw, CoinMagnet::finishInput);
        Events.run(Trigger.update, CoinMagnet::update);
        Events.run(Trigger.draw, EspRenderer::draw);
        Events.run(Trigger.draw, CoinMagnet::draw);
        Events.run(Trigger.draw, CoinPilot::draw);
        Events.run(Trigger.uiDrawEnd, EspRenderer::drawScreen);
        Events.run(Trigger.uiDrawEnd, mkukic.client.qol.AttackAlert::drawScreen);   // v1.16: стрелки у края экрана
        Events.run(Trigger.uiDrawEnd, mkukic.client.qol.SpectateCam::drawScreen);   // v1.16: вспышка потери ядра
        Events.run(Trigger.update, MKUKICMapGen::tick);
        Qol.init(); // QoL-подсистема: плавающие окна, оверлеи, инструменты
        mkukic.client.onix.Onix.init(); // v1.19: Onix-слой (порт функций Onix Lite 3.4.6)
        Events.on(WorldLoadEvent.class, e -> reset());
        Events.on(ResetEvent.class, e -> reset());
    }

    public static void reset(){
        CombatFeatures.reset();
        UnitAI.reset();
        EspRenderer.reset();
        TacticalHQ.reset();
        CoinMagnet.reset();
        CoinPilot.reset();
        TeamSwap.reset();
        NearestBoard.reset();
        Qol.reset();
        mkukic.client.onix.Onix.reset();
    }

    private static void update(){
        if(headless) return;

        handleKeys();
        NearestBoard.tick();

        if(state == null || state.isMenu()) return;

        MKUKICSettings s = MKUKICSettings.s();
        if(!s.enabled){
            return;
        }

        AdminNet.tick();

        CombatFeatures.update();
        PlayerAI.update();
        TeamSwap.update();

        if(s.aiEnabled) UnitAI.update();
    }

    /** После обновления сущностей: серверные команды ИИ (важен порядок — см. UnitAI). */
    private static void lateUpdate(){
        if(headless || state == null || state.isMenu()) return;
        MKUKICSettings s = MKUKICSettings.s();
        if(!s.enabled) return;
        PlayerAI.lateUpdate();
        if(s.aiEnabled) UnitAI.lateUpdate();
        TacticalHQ.waveAlertTick();
    }

    private static void handleKeys(){
        if(ui == null || ui.consolefrag == null) return;
        // не перехватываем ввод, пока открыт чат/консоль или любое окно ввода
        // не перехватываем ввод, пока открыт чат/консоль или любое поле ввода
        if(Core.scene != null && Core.scene.hasField()) return;

        if(MKUKICKeys.tap(MKUKICKeys.panic)){
            MKUKICSettings s = MKUKICSettings.s();
            s.aimbot = s.speed = s.rage = s.fortress = s.godmode = s.fly = s.esp = s.instantReload = false;
            s.aiAutoMine = s.aiAutoBuild = s.aiAutoRepair = s.aiFollow = s.aiEscort = false;
            s.coinMagnet = s.coinPilot = false;
            s.xray = s.coreFlow = s.alerts = s.tripwire = s.edgePan = false;
            mkukic.client.qol.SpectateCam.reset();
            CombatFeatures.reset();
            NearestBoard.reset();
            MKUKICSettings.save();
            MKUKICMod.toast("MKUKIC: все фичи выключены");
            return;
        }

        if(MKUKICKeys.tap(MKUKICKeys.menu)){
            // Shift+F9 — быстрые переключатели, F9 — полное меню
            if(Core.input.keyDown(KeyCode.shiftLeft) || Core.input.keyDown(KeyCode.shiftRight)) QuickPalette.toggle();
            else MKUKICMenu.toggle();
        }
        if(MKUKICKeys.tap(MKUKICKeys.coinMagnet)){
            MKUKICSettings cs = MKUKICSettings.s();
            cs.coinMagnet = !cs.coinMagnet;
            MKUKICSettings.save();
            if(!cs.coinMagnet) CoinMagnet.resetMagnet();
            toggleToast("магнит монет", cs.coinMagnet);
        }
        if(MKUKICKeys.tap(MKUKICKeys.coinPilot)){
            CoinPilot.toggle();
        }
        if(MKUKICKeys.tap(MKUKICKeys.boardNearest)){
            NearestBoard.toggle();
        }
        if(MKUKICKeys.tap(MKUKICKeys.mapGen)){
            MKUKICMapGen.show();
        }
        if(MKUKICKeys.tap(MKUKICKeys.qolHub)){
            mkukic.client.qol.QolWindows.toggle("hub");
        }
        if(MKUKICKeys.tap(MKUKICKeys.buildWheel)){
            mkukic.client.qol.BuildWheel.toggle();
        }
        if(MKUKICKeys.tap(MKUKICKeys.quickMsg)){
            mkukic.client.qol.QuickMsgWheel.toggle();
        }
        if(MKUKICKeys.tap(MKUKICKeys.teamSwap)) TeamSwap.toggle();
        if(MKUKICKeys.tap(MKUKICKeys.teamSwapOne)) TeamSwap.toggleMode(TeamSwap.MODE_ONE);
        if(MKUKICKeys.tap(MKUKICKeys.teamSwapAll)) TeamSwap.toggleMode(TeamSwap.MODE_ALL);
        if(MKUKICKeys.tap(MKUKICKeys.teamSwapStrong)) TeamSwap.toggleMode(TeamSwap.MODE_STRONG);
        if(MKUKICKeys.tap(MKUKICKeys.formMove)){
            mkukic.client.qol.Formations.moveToCursor();
        }
        if(MKUKICKeys.tap(MKUKICKeys.markerAdd)){
            mkukic.client.qol.MapMarkers.addAtCursor();
        }
        if(MKUKICKeys.tap(MKUKICKeys.hq)){
            String[] names = MKUKICMenu.tabLabels();
            for(int i = 0; i < names.length; i++){
                if(names[i].equals("Штаб")){
                    MKUKICMenu.showTab(i);
                    break;
                }
            }
        }
        if(MKUKICKeys.tap(MKUKICKeys.aimbot)){
            toggle("aimbot", () -> MKUKICSettings.s().aimbot, v -> MKUKICSettings.s().aimbot = v);
        }
        if(MKUKICKeys.tap(MKUKICKeys.speed)){
            toggle("баф скорости x" + Strings.autoFixed(MKUKICSettings.s().speedMult, 1),
                () -> MKUKICSettings.s().speed, v -> MKUKICSettings.s().speed = v);
        }
        if(MKUKICKeys.tap(MKUKICKeys.esp)){
            toggle("ESP/радар", () -> MKUKICSettings.s().esp, v -> MKUKICSettings.s().esp = v);
        }
        if(MKUKICKeys.tap(MKUKICKeys.godmode)){
            toggle("бессмертие", () -> MKUKICSettings.s().godmode, v -> MKUKICSettings.s().godmode = v);
        }
        if(MKUKICKeys.tap(MKUKICKeys.fly)){
            toggle("полёт", () -> MKUKICSettings.s().fly, v -> MKUKICSettings.s().fly = v);
        }
        if(MKUKICKeys.tap(MKUKICKeys.aiFollow)){
            toggle("ИИ: следование", () -> MKUKICSettings.s().aiFollow, v -> MKUKICSettings.s().aiFollow = v);
        }
        if(MKUKICKeys.tap(MKUKICKeys.aiMine)){
            toggle("ИИ: авто-копка", () -> MKUKICSettings.s().aiAutoMine, v -> MKUKICSettings.s().aiAutoMine = v);
        }
        if(MKUKICKeys.tap(MKUKICKeys.aiBuild)){
            toggle("ИИ: авто-стройка", () -> MKUKICSettings.s().aiAutoBuild, v -> MKUKICSettings.s().aiAutoBuild = v);
        }
        if(MKUKICKeys.tap(MKUKICKeys.selectAll)){
            UnitAI.selectAllNearby();
        }

        // ---- v1.16: операторская камера, алерты, просвет, поток ресурсов ----
        if(MKUKICKeys.tap(MKUKICKeys.specNext)){
            mkukic.client.qol.SpectateCam.cyclePlayer(1);
        }
        if(MKUKICKeys.tap(MKUKICKeys.specPrev)){
            mkukic.client.qol.SpectateCam.cyclePlayer(-1);
        }
        if(MKUKICKeys.tap(MKUKICKeys.specFollow)){
            mkukic.client.qol.SpectateCam.toggleFollow();
        }
        if(MKUKICKeys.tap(MKUKICKeys.coreNext)){
            mkukic.client.qol.SpectateCam.cycleCore(1);
        }
        if(MKUKICKeys.tap(MKUKICKeys.attackNext)){
            mkukic.client.qol.AttackAlert.jumpToLatest();
        }
        if(MKUKICKeys.tap(MKUKICKeys.xrayKey)){
            toggleQol("просвет зданий", () -> MKUKICSettings.s().xray, v -> MKUKICSettings.s().xray = v);
        }
        if(MKUKICKeys.tap(MKUKICKeys.coreFlowKey)){
            toggleQol("поток ресурсов ядра", () -> MKUKICSettings.s().coreFlow, v -> MKUKICSettings.s().coreFlow = v);
        }
        if(MKUKICKeys.tap(MKUKICKeys.alertsKey)){
            mkukic.client.qol.QolWindows.toggle("alerts");
        }

        // ---- v1.19: горячие клавиши Onix-слоя ----
        // F5 (консоль Onix) обрабатывается в OnixConsole.uiTick — там он работает
        // и в главном меню, и не конфликтует с полем ввода (дубль здесь ломал бы открытие).
        if(MKUKICKeys.tap(MKUKICKeys.onixAutoBuild)){
            toggleOnix("Авто-Уни: стройка", () -> MKUKICSettings.s().onixAutoBuild, v -> {
                MKUKICSettings s2 = MKUKICSettings.s();
                s2.onixAutoBuild = v;
                s2.onixAutoUnit = v || s2.onixAutoMine;
            });
        }
        if(MKUKICKeys.tap(MKUKICKeys.onixAutoMine)){
            toggleOnix("Авто-Уни: автомайн", () -> MKUKICSettings.s().onixAutoMine, v -> {
                MKUKICSettings s2 = MKUKICSettings.s();
                s2.onixAutoMine = v;
                s2.onixAutoUnit = v || s2.onixAutoBuild;
            });
        }
        if(MKUKICKeys.tap(MKUKICKeys.onixAutofill)){
            toggleOnix("Авто-Филл", () -> MKUKICSettings.s().onixAutofill, v -> MKUKICSettings.s().onixAutofill = v);
        }
        if(MKUKICKeys.tap(MKUKICKeys.onixBotKey)){
            toggleOnix("бот (" + (MKUKICSettings.s().onixBotMode == 1 ? "антигрифер" : "ассист") + ")",
                () -> MKUKICSettings.s().onixBot, v -> MKUKICSettings.s().onixBot = v);
        }
        if(MKUKICKeys.tap(MKUKICKeys.onixNavCursor)){
            // {navigate_to_cursor}: маршрут к курсору БЕЗ сообщения в чат
            mkukic.client.onix.OnixCommands.execute(mkukic.client.onix.OnixCommands.prefix() + "go cursor");
        }
        if(MKUKICKeys.tap(MKUKICKeys.onixStopPath)){
            mkukic.client.onix.OnixAutoUnit.clearQueue();
            MKUKICSettings s2 = MKUKICSettings.s();
            s2.onixAutoUnit = false;
            MKUKICSettings.save();
            MKUKICMod.toast("[accent]Onix[]: путь сброшен");
        }
        if(MKUKICKeys.tap(MKUKICKeys.onixVoteYes)){
            mkukic.client.onix.OnixChat.vote(true);
        }
        if(MKUKICKeys.tap(MKUKICKeys.onixVoteNo)){
            mkukic.client.onix.OnixChat.vote(false);
        }
        if(MKUKICKeys.tap(MKUKICKeys.onixDetectKey)){
            for(Player pl : Groups.player) mkukic.client.onix.OnixClientDetect.openWindow(pl, true);
            MKUKICMod.toast("[accent]Onix[]: сканирование клиентов запущено заново");
        }
        if(MKUKICKeys.tap(MKUKICKeys.onixChatComplete)){
            mkukic.client.onix.OnixChat.autocompleteChat();
        }
    }

    /** Переключатель Onix-функции с тостом и сохранением (v1.19). */
    private static void toggleOnix(String name, arc.func.Boolp get, arc.func.Boolc set){
        boolean v = !get.get();
        set.get(v);
        MKUKICSettings.save();
        MKUKICMod.toast((v ? "[#" + mkukic.client.onix.Onix.theme.toString() + "]+ " : "[scarlet]- ") + name);
    }

    /** Переключатель QoL-фичи с тостом и сохранением (v1.16). */
    private static void toggleQol(String name, arc.func.Boolp get, arc.func.Boolc set){
        boolean v = !get.get();
        set.get(v);
        MKUKICSettings.save();
        MKUKICMod.toast((v ? "[accent]+ " : "[scarlet]- ") + name);
    }

    private static void toggleToast(String name, boolean on){
        MKUKICMod.toast((on ? "[accent]+ " : "[scarlet]- ") + name);
    }

    private static void toggle(String name, arc.func.Boolp get, arc.func.Boolc set){
        boolean v = !get.get();
        set.get(v);
        MKUKICSettings.save();
        MKUKICMod.toast((v ? "[accent]+ " : "[scarlet]- ") + name);
    }
}
