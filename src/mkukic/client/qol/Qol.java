package mkukic.client.qol;

import arc.*;
import arc.util.*;
import mindustry.game.EventType.*;
import mkukic.*;

import static mindustry.Vars.*;

/**
 * Фасад QoL-подсистемы MKUKIC (порт функций из каталога модов:
 * MI2-Utilities, Helium, MindustryTool, TooManyItems, Neon, Scheme-Size, TimeControl).
 *
 * Окна (Mindow-стиль):      QolWindows + хаб (F4)
 * Rule Viewer:              RuleViewer
 * Волны (HP + превью):      WaveMonitor
 * Инфо о ядре (график 60c): CoreStats
 * Анализ энергосетей:       PowerMonitor
 * HP-бары/щиты/дальности:   RangeOverlay (Helium)
 * Браузер рецептов:         ContentBrowser (TooManyItems)
 * Зум/слоу-мо/FPS:          ViewControl (TimeControl + Performance Optimizer)
 * Поиск по карте:           MapSearch (MI2 minimap search)
 * Quick Actions:            QuickActions (MI2)
 * Геозона-тревога:          Tripwire (Neon)
 * Smart Upgrade:            SmartUpgrade (MindustryTool)
 * Символы/иконки:           IconKit (Emoji Toolkit)
 *
 * v1.16 — вторая волна портов из каталога модов:
 * Атака-алерт:              AttackAlert (m3rcur1e/attack-alert + MinerTools + Caster-UI)
 * История чата:             ChatLog (MinerTools ChatTable + Extra Logging)
 * Просвет зданий:           XRay (MI2 Building X-ray + Color-the-ducts)
 * Поток ресурсов ядра:      CoreFlow (resource-flow + MindustryTool Team Resource HUD)
 * График производства:      ProductionGraph (Factorio-style production graph)
 * Тревоги производства:     AlertList (MinerTools TurretAlert + Power-Grid-Minimap)
 * Операторская камера:      SpectateCam (Caster-UI + MinerTools PlayerList + MI2 Multi-Team)
 * Камера краем экрана:      ViewControl.edgePanTick (MI2 Edge Panning)
 */
public class Qol{
    private static boolean inited;

    public static void init(){
        if(inited || headless) return;
        inited = true;

        QolWindows.init();

        // v1.16: события новых модулей (повреждения/уничтожения, чат)
        AttackAlert.init();
        ChatLog.init();
        CoreFlow.initHud();

        Events.run(Trigger.update, Qol::tick);
        // Синхронизация видимости окон должна работать и в главном меню, а Trigger.update
        // в v8 срабатывает только во время игры — поэтому окна ведем от uiDrawBegin (каждый кадр).
        Events.run(Trigger.uiDrawBegin, Qol::uiTick);
        Events.run(Trigger.draw, RangeOverlay::draw);
        Events.run(Trigger.draw, Tripwire::draw);
        Events.run(Trigger.draw, PathPreview::draw);
        Events.run(Trigger.draw, DrillPlanner::draw);
        Events.run(Trigger.draw, MapMarkers::draw);
        Events.run(Trigger.draw, Inspector::drawWorld);
        Events.run(Trigger.draw, AttackAlert::draw);
        Events.run(Trigger.draw, AlertList::draw);
        Events.run(Trigger.draw, XRay::draw);

        ViewControl.applyAll();
        Log.info("[MKUKIC QoL] подсистема загружена: 28 окон (F4 — хаб, F2 — колесо блоков, F3 — формация-ход, F1 — операторская камера)");
    }

    private static void uiTick(){
        if(headless) return;
        QolWindows.update();
        MusicBox.tick();
        // v1.16: окна с живыми списками перестраиваем не каждый кадр
        AttackAlert.updateWindow();
        AlertList.updateWindow();
        ChatLog.tick();
        SpectateCam.updateWindow();
        ProductionGraph.updateWindow();
    }

    private static void tick(){
        if(headless) return;

        if(state == null || state.isMenu()) return;
        MKUKICSettings s = MKUKICSettings.s();
        if(!s.enabled || !s.qolEnabled) return;

        WaveMonitor.tick();
        CoreStats.tick();
        PowerMonitor.tick();
        MapSearch.tick();
        Tripwire.tick();
        BuildWheel.trackRecent();
        PathPreview.tick();
        DrillPlanner.tick();
        CursorTracker.tick();

        // v1.16
        AttackAlert.tick();
        AlertList.tick();
        CoreFlow.tick();
        ProductionGraph.tick();
        SpectateCam.tick();
        ViewControl.edgePanTick();
    }

    /** Сброс при загрузке нового мира. */
    public static void reset(){
        WaveMonitor.reset();
        CoreStats.reset();
        PowerMonitor.reset();
        MapSearch.reset();
        Tripwire.reset();
        ContentBrowser.reset();
        ViewControl.reset();
        PathPreview.reset();
        DrillPlanner.reset();
        BuildWheel.close();
        QuickMsgWheel.close();
        Inspector.reset();
        MapMarkers.reset();
        CursorTracker.reset();
        AttackAlert.reset();
        AlertList.reset();
        ChatLog.reset();
        CoreFlow.reset();
        ProductionGraph.reset();
        SpectateCam.reset();
    }
}
