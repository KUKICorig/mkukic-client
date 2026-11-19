package mkukic;

import arc.*;
import arc.util.*;
import mindustry.game.EventType.*;
import mindustry.mod.*;
import mindustry.mod.Mods.*;
import mkukic.client.*;
import mkukic.server.*;

import static mindustry.Vars.*;

/**
 * MKUKIC Client — точка входа мода.
 * Работает и как клиент-мод (фичи игрока, меню, ESP), и как серверный плагин
 * (админ-команды, история, магазин, античит) — в одном jar.
 */
public class MKUKICMod extends Mod{
    public static final String id = "mkukic";
    public static String version = "1.0.0";

    /** true, если мод загружен на сервере (dedicated или хост) — включаются серверные команды. */
    public static boolean serverSide = false;
    public static boolean clientReady = false;

    public MKUKICMod(){
        Log.info("[MKUKIC] загрузка мода...");

        MKUKICSettings.init(getConfigFolder());
        MKUKICSettings.load();
        UnitGroups.init(getConfigFolder());

        // имя мода в списке = имя jar-файла, поэтому ищем по классу, а не по строке
        LoadedMod lm = mods.getMod(MKUKICMod.class);
        if(lm != null && lm.meta != null && lm.meta.version != null) version = lm.meta.version;

        // клиентская часть
        Events.on(ClientLoadEvent.class, e -> {
            clientReady = true;
            MKUKICKeys.init();
            MKUKICHud.init();
            MKUKICMenu.init();
            OverlayUI.init();
            AdminNet.initClient();
            ClientHook.init();
            ModDoctor.init(); // самодиагностика дублей мода и битого контента
            Log.info("[MKUKIC] клиентская часть загружена, v" + version);
        });

        // серверная часть (dedicated server / хост)
        Events.on(ServerLoadEvent.class, e -> {
            serverSide = true;
            ServerData.init(getConfigFolder());
            AdminNet.initServer();
            ServerCommands.register(netServer.clientCommands); // на случай, если registerClientCommands ещё не вызван
            Anticheat.init();
            ServerEvents.init();
            Log.info("[MKUKIC] серверная часть загружена (команды /nhelp)");
        });
    }

    @Override
    public void registerServerCommands(CommandHandler handler){
        // команды консоли сервера (не чат)
        ServerConsoleCommands.register(handler);
    }

    @Override
    public void registerClientCommands(CommandHandler handler){
        // чат-команды: регистрируются и на сервере, и у хоста
        ServerCommands.register(handler);
    }

    /** Активен ли мод на сервере, к которому подключён клиент. */
    public static boolean serverHasMod(){
        return AdminNet.serverHasMod();
    }

    public static void toast(String text){
        MKUKICSettings s = MKUKICSettings.s();
        if(!s.toasts || ui == null || headless) return;
        ui.showInfoToast(text, 2.2f);
    }
}
