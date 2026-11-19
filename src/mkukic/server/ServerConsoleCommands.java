package mkukic.server;

import arc.util.*;
import mindustry.gen.*;
import mkukic.*;
import mkukic.server.ServerData.*;

import static mindustry.Vars.*;

/** Команды консоли сервера (вводятся в терминале, а не в чате). */
public class ServerConsoleCommands{
    private static boolean registered = false;

    public static void register(CommandHandler handler){
        if(handler == null || registered) return;
        registered = true;

        handler.register("mkukic", "[sub] [args...]", "MKUKIC: status | roles | setlevel | credits | unban | history | reloadcfg | save", (args) -> {
            if(args.length == 0 || args[0].equals("status")){
                Log.info("&lc[MKUKIC]&fi v" + MKUKICMod.version + ": игроков " + Groups.player.size()
                    + ", записей " + ServerData.players.size + ", событий в журнале " + ServerData.recentLog.size
                    + ", товаров " + ServerData.shop.size);
                return;
            }
            String sub = args[0];
            String rest = args.length > 1 ? args[1] : "";

            switch(sub){
                case "roles" -> {
                    for(var e : ServerData.players.entries()){
                        if(e.value.level > 0){
                            Log.info("&lc[MKUKIC]&fi @ (@) — @", e.value.lastName, shortId(e.key), ServerData.roleName(e.value.level));
                        }
                    }
                    for(Player p : Groups.player){
                        Log.info("&lc[MKUKIC]&fi онлайн: @ — @", p.plainName(), ServerData.roleName(ServerData.level(p)));
                    }
                }
                case "setlevel" -> {
                    String[] f = rest.split(" ");
                    if(f.length < 2 || !Strings.canParseInt(f[1])){
                        Log.err("&lc[MKUKIC]&fi использование: mkukic setlevel <id|ник> <0-4>");
                        return;
                    }
                    String id = ServerData.findId(f[0]);
                    if(id == null) id = f[0];
                    Rec r = ServerData.rec(id);
                    r.level = Strings.parseInt(f[1]);
                    ServerData.savePlayers();
                    Log.info("&lc[MKUKIC]&fi роль @ = @ (@)", r.lastName, ServerData.roleName(r.level), shortId(id));
                    Player online = ServerData.find(f[0]);
                    if(online != null){
                        online.admin = r.level >= 2;
                        online.sendMessage("[accent]Ваша роль MKUKIC: " + ServerData.roleName(r.level));
                    }
                }
                case "credits" -> {
                    String[] f = rest.split(" ");
                    if(f.length < 2 || !Strings.canParseInt(f[1])){
                        Log.err("&lc[MKUKIC]&fi использование: mkukic credits <id|ник> <кол-во>");
                        return;
                    }
                    String id = ServerData.findId(f[0]);
                    if(id == null) id = f[0];
                    Rec r = ServerData.rec(id);
                    r.credits = Math.max(0, r.credits + Strings.parseInt(f[1]));
                    ServerData.savePlayers();
                    Log.info("&lc[MKUKIC]&fi @: @ кредитов", r.lastName, r.credits);
                }
                case "unban" -> {
                    String id = ServerData.findId(rest);
                    if(id == null) id = rest;
                    Rec r = ServerData.rec(id);
                    r.banUntil = 0L;
                    r.banReason = "";
                    if(netServer != null){
                        netServer.admins.unbanPlayerID(id);
                        if(!r.lastIP.isEmpty()) netServer.admins.unbanPlayerIP(r.lastIP);
                    }
                    ServerData.savePlayers();
                    ServerData.log("pardon", "консоль: " + id);
                    Log.info("&lc[MKUKIC]&fi разбан: @ (@)", r.lastName, shortId(id));
                }
                case "history" -> {
                    var found = ServerData.searchLog(rest.isEmpty() ? "" : rest, 30);
                    if(found.isEmpty()) Log.info("&lc[MKUKIC]&fi пусто");
                    for(String line : found) Log.info("&lc[MKUKIC]&fi @", line);
                }
                case "reloadcfg" -> {
                    ServerData.reload();
                    Log.info("&lc[MKUKIC]&fi конфиг перезагружен");
                }
                case "save" -> {
                    ServerData.savePlayers();
                    ServerData.saveServer();
                    Log.info("&lc[MKUKIC]&fi данные сохранены");
                }
                default -> Log.err("&lc[MKUKIC]&fi неизвестная подкоманда: @", sub);
            }
        });
    }

    private static String shortId(String id){
        return id == null ? "?" : (id.length() <= 8 ? id : id.substring(0, 8));
    }
}
