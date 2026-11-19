package mkukic.client;

import arc.struct.*;
import arc.util.*;
import mindustry.gen.*;
import mkukic.*;

import static mindustry.Vars.*;

/**
 * Связь клиента с сервером, на котором тоже стоит MKUKIC.
 * Использует встроенный в v8 механизм кастомных пакетов (serverPacket/clientPacket),
 * поэтому никаких собственных протоколов регистрировать не нужно.
 *
 * Если на сервере мода нет — панель админа работает через стандартные
 * admin-запросы игры (kick/ban/trace/wave), которые доступны админам сервера.
 */
public class AdminNet{
    public static final String type = "mkukic";
    public static final String proto = "1";

    // состояние, известное клиенту
    public static boolean serverHasMod = false;
    public static String serverVersion = "?";
    public static int roleLevel = 0;
    public static String roleName = "игрок";
    public static int credits = 0;
    public static long lastAnswer = 0L;

    public static final Seq<String> log = new Seq<>();
    public static final Seq<String[]> shop = new Seq<>();

    private static long lastQuery = 0L;

    public static void initClient(){
        if(netClient == null) return;
        netClient.addPacketHandler(type, AdminNet::handle);
    }

    public static void initServer(){
        if(netServer == null) return;
        netServer.addPacketHandler(type, (player, data) -> mkukic.server.ServerNet.handle(player, data));
    }

    public static boolean serverHasMod(){
        return serverHasMod && Time.timeSinceMillis(lastAnswer) < 15000;
    }

    public static String serverVersion(){
        return serverVersion;
    }

    public static void queryServer(){
        if(!net.active() || net.server()){
            serverHasMod = true;
            serverVersion = MKUKICMod.version + " (локально)";
            roleLevel = 4;
            roleName = "хост";
            return;
        }
        send("q|info");
        send("q|shop");
    }

    /** Периодический опрос сервера (раз в ~10 секунд), чтобы статус не протухал. */
    public static void tick(){
        if(!net.active() || net.server()) return;
        if(Time.timeSinceMillis(lastQuery) > 10000){
            lastQuery = Time.millis();
            send("q|info");
        }
    }

    public static void send(String data){
        if(!net.active() || net.server()) return;
        try{
            Call.serverPacketReliable(type, proto + "|" + data);
        }catch(Throwable t){
            Log.err("[MKUKIC] не удалось отправить пакет на сервер", t);
        }
    }

    public static void runCommand(String raw){
        if(raw == null) return;
        raw = raw.trim();
        if(raw.isEmpty()) return;
        addLog("> " + raw);

        if(net.server() || !net.active()){
            // локальная игра / хост — выполняем напрямую
            mkukic.server.ServerCommands.runLocal(player, raw);
            return;
        }
        if(serverHasMod()){
            send("cmd|" + raw);
        }else{
            // мод на сервере не стоит: отправляем как обычный чат-команд
            if(raw.startsWith("/")){
                Call.sendChatMessage(raw);
            }else{
                addLog("[scarlet]Сервер без MKUKIC: команда недоступна");
            }
        }
    }

    public static void addLog(String text){
        log.insert(0, "[" + new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date()) + "] " + text);
        if(log.size > 200) log.pop();
    }

    // ============================ приём ответов ============================

    private static void handle(String data){
        if(data == null) return;
        lastAnswer = Time.millis();
        String[] parts = data.split("\\|", 3);
        if(parts.length < 2) return;
        String cmd = parts[0];
        String arg = parts.length > 1 ? parts[1] : "";
        String rest = parts.length > 2 ? parts[2] : "";

        switch(cmd){
            case "info" -> {
                serverHasMod = true;
                String[] f = arg.split(";");
                if(f.length > 0) serverVersion = f[0];
                if(f.length > 1) roleLevel = Strings.canParseInt(f[1]) ? Strings.parseInt(f[1]) : 0;
                if(f.length > 2) roleName = f[2];
                if(f.length > 3 && Strings.canParseInt(f[3])) credits = Strings.parseInt(f[3]);
            }
            case "r" -> addLog(rest.isEmpty() ? arg : arg + " " + rest);
            case "err" -> addLog("[scarlet]" + arg + (rest.isEmpty() ? "" : " " + rest));
            case "shop" -> {
                shop.clear();
                for(String line : arg.split(";")){
                    String[] f = line.split(",");
                    if(f.length >= 3) shop.add(new String[]{f[0], f[1], f[2]});
                }
            }
            case "alert" -> {
                if(MKUKICSettings.s().anticheatAlerts){
                    addLog("[yellow][!] " + arg + (rest.isEmpty() ? "" : " " + rest));
                    MKUKICMod.toast("[yellow]Античит: " + arg);
                }
            }
            default -> addLog("[lightgray]" + data);
        }
    }
}
