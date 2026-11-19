package mkukic.client.onix;

import arc.*;
import arc.graphics.*;
import arc.util.*;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mkukic.*;
import mkukic.client.*;

import static mindustry.Vars.*;

/**
 * Фасад Onix-слоя MKUKIC (v1.19) — порт функций клиента <b>Onix Lite 3.4.6</b>.
 *
 * <p>Источник: Onix Lite (https://github.com/DiscoD3979/OnixLite, GPL-3.0). Поведение
 * функций восстановлено по открытым описаниям клиента (файлы {@code features} и
 * {@code changelog} внутри Onix-Lite.jar) и по открытому GPL-3 коду Foo's Client
 * (BalaM314/mindustry-mod), на котором Onix основан. Бинарные данные Onix
 * ({@code data.bin}, заголовок ONIX\x01) зашифрованы и НЕ использовались —
 * реализация написана заново под архитектуру MKUKIC.
 *
 * <table>
 * <tr><td>Авто-Уни</td><td>{@link OnixAutoUnit}</td><td>планы стройки, недостающий ресурс, автомайн</td></tr>
 * <tr><td>Авто-Филл</td><td>{@link OnixAutoFill}</td><td>развоз ресурсов по голодным постройкам</td></tr>
 * <tr><td>Боты</td><td>{@link OnixBot}</td><td>Ассист / Антигрифер, визуал пути</td></tr>
 * <tr><td>Детект клиентов</td><td>{@link OnixClientDetect}</td><td>Onix / Onix Lite / Foo</td></tr>
 * <tr><td>Аккаунты</td><td>{@link OnixAccounts}</td><td>сохранение ника/UUID/USID, обход бана</td></tr>
 * <tr><td>Чат</td><td>{@link OnixChat}</td><td>голосование, копирование с цветами, автоответчик</td></tr>
 * <tr><td>Консоль</td><td>{@link OnixConsole}</td><td>вкладки Чат / Действия / Логи / Код</td></tr>
 * <tr><td>Команды</td><td>{@link OnixCommands}</td><td>префикс-команды клиента</td></tr>
 * <tr><td>Анимации</td><td>{@link OnixAnims}</td><td>плавный чат, мягкие счётчики</td></tr>
 * <tr><td>Обновления</td><td>{@link OnixUpdater}</td><td>проверка релизов, скачивание в фоне</td></tr>
 * </table>
 */
public class Onix{
    /** Фирменный фиолетовый Onix (из README клиента: #8A2BE2). */
    public static final Color theme = Color.valueOf("8A2BE2");
    /** Светлый акцент того же семейства. */
    public static final Color theme2 = Color.valueOf("c9a6ff");
    /** Цвет «своего» клиента MKUKIC в детекте. */
    public static final Color selfColor = Color.valueOf("ffb454");

    private static boolean inited;

    public static void init(){
        if(inited || headless) return;
        inited = true;

        OnixConsole.init();
        OnixChat.init();
        OnixBot.initEvents();

        // чат -> консоль Onix + запоминание координат + команды клиента
        Events.on(PlayerChatEvent.class, e -> {
            if(e.message == null) return;
            OnixConsole.addChat(e.player, e.message, false);
            OnixCommands.scanCoords(e.message);
        });
        Events.on(ClientChatEvent.class, e -> {
            if(e.message == null) return;
            OnixConsole.addChat(player, e.message, true);
            // клиентские команды Onix: «/m go 100 200» выполняются локально
            if(MKUKICSettings.s().onixEnabled && MKUKICSettings.s().onixCommands
                && OnixCommands.execute(OnixCommands.applyReplacers(e.message))){
                OnixConsole.add("ACTION", "выполнена команда клиента", 2);
            }
        });
        // действия в мире -> вкладка «Действия» (время МСК, координаты подсвечены)
        Events.on(BlockBuildEndEvent.class, e -> {
            if(e.tile == null || e.unit == null) return;
            if(player != null && e.unit == player.unit()){
                OnixConsole.addAction((e.breaking ? "сломал" : "поставил") + " " + e.tile.block().localizedName,
                    e.tile.worldx(), e.tile.worldy());
            }
        });
        Events.on(PlayerJoin.class, e -> {
            if(e.player != null) OnixConsole.addAction("игрок вошёл: " + e.player.name(), e.player.id() * 31 + 7);
        });
        Events.on(PlayerLeave.class, e -> {
            if(e.player != null) OnixConsole.addAction("игрок вышел: " + e.player.name(), e.player.id() * 31 + 8);
        });
        OnixClientDetect.init();
        OnixAccounts.init();
        OnixUpdater.init();
        OnixAnims.init();

        Events.run(Trigger.update, Onix::tick);
        Events.run(Trigger.afterGameUpdate, Onix::lateTick);
        Events.run(Trigger.draw, Onix::draw);
        Events.run(Trigger.uiDrawEnd, OnixChat::drawScreen);
        Events.run(Trigger.uiDrawEnd, OnixClientDetect::drawScreen);   // v1.19.2: значки клиентов в списке игроков (Tab)
        Events.run(Trigger.uiDrawEnd, OnixBot::drawScreen);
        Events.run(Trigger.uiDrawEnd, OnixAnims::drawScreen);
        Events.run(Trigger.uiDrawEnd, OnixUpdater::drawScreen);
        Events.run(Trigger.uiDrawBegin, Onix::uiTick);
        Events.on(WorldLoadEvent.class, e -> reset());
        Events.on(ResetEvent.class, e -> reset());

        Log.info("[MKUKIC Onix] слой Onix Lite загружен: авто-уни, авто-филл, бот, детект клиентов, "
            + "аккаунты, чат-функции, консоль, команды, апдейтер");
    }

    /** Каждый кадр, в том числе в меню (окна, апдейтер, анимации). */
    private static void uiTick(){
        if(headless) return;
        OnixAnims.uiTick();
        OnixUpdater.uiTick();
        OnixConsole.uiTick();
    }

    private static void tick(){
        if(headless) return;
        OnixChat.tick();
        OnixUpdater.tick();
        if(state == null || state.isMenu()) return;

        MKUKICSettings s = MKUKICSettings.s();
        if(!s.enabled || !s.onixEnabled) return;

        OnixClientDetect.tick();
        if(s.onixAutoUnit) OnixAutoUnit.update();
        if(s.onixAutofill) OnixAutoFill.update();
        if(s.onixBot) OnixBot.update();
    }

    /** После обновления сущностей: движение автопилотов (важен порядок — см. ClientHook). */
    private static void lateTick(){
        if(headless || state == null || state.isMenu()) return;
        MKUKICSettings s = MKUKICSettings.s();
        if(!s.enabled || !s.onixEnabled) return;

        if(s.onixAutoUnit) OnixAutoUnit.lateUpdate();
        if(s.onixAutofill) OnixAutoFill.lateUpdate();
        if(s.onixBot) OnixBot.lateUpdate();
    }

    private static void draw(){
        if(headless || state == null || state.isMenu()) return;
        MKUKICSettings s = MKUKICSettings.s();
        if(!s.enabled || !s.onixEnabled) return;

        if(s.onixBot && s.onixBotPath) OnixBot.draw();
        if(s.onixAutoUnit && s.onixShowAutoUnitPath) OnixAutoUnit.draw();
        if(s.onixAutofill && s.onixShowFillPath) OnixAutoFill.draw();
        OnixClientDetect.draw();
    }

    public static void reset(){
        if(!inited) return;
        OnixAutoUnit.reset();
        OnixAutoFill.reset();
        OnixBot.reset();
        OnixClientDetect.reset();
        OnixChat.reset();
        OnixAnims.reset();
    }

    /** Все ли «опасные» (телепорт/спам) функции запрещены политикой сервера. */
    public static boolean strict(){
        return MKUKICSettings.s().onixStrictMode;
    }

    /** Функция доступна на текущем сервере (для «Скрыть недоступные» в панели). */
    public static boolean available(String feature){
        MKUKICSettings s = MKUKICSettings.s();
        boolean mp = net != null && net.client();
        switch(feature){
            case "autofill":
            case "autounit":
            case "bot":
                // работают везде, но на сервере без мода нет ускорений — всё равно доступны
                return true;
            case "accounts":
                return s.onixAccounts;
            case "detect":
                return s.onixDetect && mp;
            case "console":
                return s.onixConsole;
            case "updater":
                return s.onixUpdateCheck;
            default:
                return true;
        }
    }
}
