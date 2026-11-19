package mkukic.server;

import arc.util.*;
import mindustry.gen.*;
import mkukic.*;
import mkukic.client.*;

import static mindustry.Vars.*;

/**
 * Серверная сторона протокола MKUKIC (кастомные пакеты v8).
 * Клиент шлёт:  "1|q|info", "1|cmd|/stats", "1|q|shop", "1|buy|<id>"
 * Сервер шлёт:  "info|<ver>;<level>;<role>;<credits>", "r|<текст>", "shop|<каталог>", "alert|<текст>"
 */
public class ServerNet{
    public static void handle(Player player, String data){
        if(player == null || data == null) return;
        try{
            String[] parts = data.split("\\|", 3);
            if(parts.length < 2) return;
            String proto = parts[0];
            String cmd = parts[1];
            String arg = parts.length > 2 ? parts[2] : "";

            if(!proto.equals(AdminNet.proto)){
                answer(player, "err", "несовместимая версия протокола клиента: " + proto);
                return;
            }

            switch(cmd){
                case "q" -> {
                    if(arg.equals("info")){
                        ServerData.rec(player); // обновить lastSeen/lastName
                        answer(player, "info", ServerCommands.infoPayload(player));
                    }else if(arg.equals("shop")){
                        answer(player, "shop", shopPayload());
                    }
                }
                case "cmd" -> {
                    String raw = arg.trim();
                    if(raw.isEmpty()) return;
                    ServerData.logPlayer(player, "cmd", raw);
                    ServerCommands.runLocal(player, raw);
                }
                case "buy" -> {
                    ServerData.ShopItem item = ServerData.shop.find(i -> i.id.equalsIgnoreCase(arg));
                    if(item == null){
                        answer(player, "err", "нет такого товара: " + arg);
                        return;
                    }
                    ServerCommands.runLocal(player, "/buy " + item.id);
                }
                default -> answer(player, "err", "неизвестный запрос: " + cmd);
            }
        }catch(Throwable t){
            Log.err("[MKUKIC] ошибка обработки пакета от " + player.plainName(), t);
        }
    }

    public static void answer(Player player, String type, String payload){
        if(player == null || player.con == null) return;
        try{
            Call.clientPacketReliable(player.con, AdminNet.type, type + "|" + payload);
        }catch(Throwable t){
            Log.err("[MKUKIC] не удалось отправить ответ клиенту", t);
        }
    }

    public static void answerAll(String type, String payload){
        for(Player p : Groups.player){
            answer(p, type, payload);
        }
    }

    /** Каталог магазина в формате "id,название,цена;id,название,цена;..." */
    public static String shopPayload(){
        StringBuilder sb = new StringBuilder();
        for(ServerData.ShopItem it : ServerData.shop){
            if(sb.length() > 0) sb.append(';');
            sb.append(it.id).append(',').append(it.title.replace(',', ' ').replace(';', ' ')).append(',').append(it.price);
        }
        return sb.toString();
    }

    /** Оповещение всем админам (например, от античита). */
    public static void alertAdmins(String text){
        for(Player p : Groups.player){
            if(p.admin || ServerData.level(p) >= 1){
                answer(p, "alert", text);
                ServerCommands.say(p, "[yellow][Античит][] " + text);
            }
        }
        ServerData.log("anticheat", text);
        if(!headless) Log.warn("[MKUKIC/anticheat] " + text);
    }
}
