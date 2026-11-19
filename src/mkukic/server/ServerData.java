package mkukic.server;

import arc.files.*;
import arc.struct.*;
import arc.util.*;
import arc.util.serialization.*;
import mindustry.gen.*;

import java.text.*;
import java.util.*;

import static mindustry.Vars.*;

/**
 * Серверное хранилище MKUKIC: роли админов, кредиты, заметки, история событий, настройки.
 * Всё лежит в configs/mkukic-client/ — рядом с конфигом клиента.
 *
 *  players.json  — id -> {level, credits, joins, kicks, banned, banUntil, banReason, notes, lastName, lastSeen}
 *  server.json   — настройки серверной части (экономика, античит, авто-кик)
 *  shop.json     — каталог магазина
 *  history.log   — журнал событий (по одной строке на событие)
 */
public class ServerData{
    public static class Rec{
        public int level = 0;          // 0 игрок, 1 модер, 2 админ, 3 старший, 4 владелец
        public int credits = 0;
        public int joins = 0;
        public int kicks = 0;
        public int warns = 0;
        public long banUntil = 0L;     // 0 = не забанен, Long.MAX_VALUE = навсегда
        public String banReason = "";
        public String notes = "";
        public String lastName = "?";
        public String lastIP = "";
        public long lastSeen = 0L;
    }

    public static class ShopItem{
        public String id;
        public String title;
        public int price;
        public String action;   // item | heal | unit | credits | speed
        public String arg = "";
        public int amount = 1;
    }

    public static class Cfg{
        public int startCredits = 100;
        public int creditsPerMinute = 6;
        public int creditsPerKill = 4;
        public int maxLogEntries = 4000;
        public boolean anticheat = true;
        public boolean anticheatKick = false;
        public int anticheatViolationsToKick = 12;
        public float speedCheatFactor = 1.8f;
        public boolean logChat = true;
        public boolean logJoins = true;
        public String banMessagePrefix = "[scarlet]Вы забанены: ";
    }

    private static Fi folder;
    private static Fi playersFile, serverFile, shopFile, historyFile;
    private static final Json json = new Json();

    public static final ObjectMap<String, Rec> players = new ObjectMap<>();
    /** uuid игроков с включённым серверным бессмертием / яростью (не персистентно). */
    public static final arc.struct.ObjectSet<String> godIds = new arc.struct.ObjectSet<>();
    public static final arc.struct.ObjectSet<String> rageIds = new arc.struct.ObjectSet<>();
    public static final Seq<ShopItem> shop = new Seq<>();
    public static final Seq<String> recentLog = new Seq<>();
    public static Cfg cfg = new Cfg();

    private static final SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    public static void init(Fi configFolder){
        if(configFolder == null) return;
        folder = configFolder;
        folder.mkdirs();
        playersFile = folder.child("players.json");
        serverFile = folder.child("server.json");
        shopFile = folder.child("shop.json");
        historyFile = folder.child("history.log");

        json.setIgnoreUnknownFields(true);
        json.setOutputType(JsonWriter.OutputType.json);

        loadPlayers();
        loadServer();
        loadShop();
        loadRecentLog();

        log("system", "серверная часть MKUKIC запущена (v" + mkukic.MKUKICMod.version + ")");
    }

    /** Перечитать все конфиги (для консольной команды mkukic reloadcfg). */
    public static void reload(){
        if(folder == null) return;
        loadPlayers();
        loadServer();
        loadShop();
        loadRecentLog();
    }

    // ================================ ЗАГРУЗКА ================================

    private static void loadPlayers(){
        players.clear();
        if(playersFile == null || !playersFile.exists()) return;
        try{
            Jval root = Jval.read(playersFile.readString());
            if(!root.isObject()) return;
            var obj = root.asObject();
            for(int i = 0; i < obj.size; i++){
                String key = obj.getKeyAt(i);
                Jval v = obj.getValueAt(i);
                Rec r = new Rec();
                r.level = v.getInt("level", 0);
                r.credits = v.getInt("credits", 0);
                r.joins = v.getInt("joins", 0);
                r.kicks = v.getInt("kicks", 0);
                r.warns = v.getInt("warns", 0);
                r.banUntil = v.getLong("banUntil", 0L);
                r.banReason = v.getString("banReason", "");
                r.notes = v.getString("notes", "");
                r.lastName = v.getString("lastName", "?");
                r.lastIP = v.getString("lastIP", "");
                r.lastSeen = v.getLong("lastSeen", 0L);
                players.put(key, r);
            }
        }catch(Throwable t){
            Log.err("[MKUKIC] players.json повреждён", t);
        }
    }

    private static void loadServer(){
        if(serverFile == null || !serverFile.exists()){
            saveServer();
            return;
        }
        try{
            Cfg loaded = json.fromJson(Cfg.class, serverFile.readString());
            if(loaded != null) json.copyFields(loaded, cfg);
        }catch(Throwable t){
            Log.err("[MKUKIC] server.json повреждён", t);
        }
    }

    private static void loadShop(){
        shop.clear();
        if(shopFile == null || !shopFile.exists()){
            writeDefaultShop();
            return;
        }
        try{
            Jval root = Jval.read(shopFile.readString());
            if(!root.isObject() || !root.has("items")) return;
            for(Jval v : root.get("items").asArray()){
                ShopItem it = new ShopItem();
                it.id = v.getString("id", "");
                it.title = v.getString("title", it.id);
                it.price = v.getInt("price", 100);
                it.action = v.getString("action", "item");
                it.arg = v.getString("arg", "");
                it.amount = v.getInt("amount", 1);
                if(!it.id.isEmpty()) shop.add(it);
            }
        }catch(Throwable t){
            Log.err("[MKUKIC] shop.json повреждён", t);
        }
    }

    private static void writeDefaultShop(){
        if(shopFile == null) return;
        String def = """
            {
              "comment": "Каталог магазина MKUKIC. action: item (выдать ресурс в ядро), heal (вылечить игрока), unit (заспаунить юнита), credits (начислить кредиты).",
              "items": [
                { "id": "copper4000",   "title": "4000 меди",        "price": 60,  "action": "item", "arg": "copper",   "amount": 4000 },
                { "id": "lead4000",     "title": "4000 свинца",      "price": 60,  "action": "item", "arg": "lead",     "amount": 4000 },
                { "id": "silicon2000",  "title": "2000 кремния",     "price": 120, "action": "item", "arg": "silicon",  "amount": 2000 },
                { "id": "titanium2000", "title": "2000 титана",      "price": 140, "action": "item", "arg": "titanium", "amount": 2000 },
                { "id": "thorium1500",  "title": "1500 тория",       "price": 200, "action": "item", "arg": "thorium",  "amount": 1500 },
                { "id": "surge1000",    "title": "1000 кинетика",    "price": 320, "action": "item", "arg": "surge-alloy", "amount": 1000 },
                { "id": "heal",         "title": "Полное лечение",   "price": 40,  "action": "heal", "arg": "",         "amount": 1 },
                { "id": "mace",         "title": "Юнит Mace",        "price": 250, "action": "unit", "arg": "mace",     "amount": 1 },
                { "id": "flare",        "title": "Юнит Flare",       "price": 180, "action": "unit", "arg": "flare",    "amount": 1 },
                { "id": "credits500",   "title": "+500 кредитов",    "price": 400, "action": "credits", "arg": "",      "amount": 500 }
              ]
            }
            """;
        shopFile.writeString(def, false);
        loadShop();
    }

    private static void loadRecentLog(){
        recentLog.clear();
        if(historyFile == null || !historyFile.exists()) return;
        try{
            String[] lines = historyFile.readString().split("\n");
            int from = Math.max(0, lines.length - cfg.maxLogEntries);
            for(int i = from; i < lines.length; i++){
                if(!lines[i].isBlank()) recentLog.add(lines[i]);
            }
        }catch(Throwable ignored){
        }
    }

    // ================================= СОХРАНЕНИЕ =================================

    public static void savePlayers(){
        if(playersFile == null) return;
        try{
            Jval root = Jval.newObject();
            players.each((id, r) -> {
                Jval v = Jval.newObject();
                v.put("level", r.level);
                v.put("credits", r.credits);
                v.put("joins", r.joins);
                v.put("kicks", r.kicks);
                v.put("warns", r.warns);
                v.put("banUntil", r.banUntil);
                v.put("banReason", r.banReason);
                v.put("notes", r.notes);
                v.put("lastName", r.lastName);
                v.put("lastIP", r.lastIP);
                v.put("lastSeen", r.lastSeen);
                root.put(id, v);
            });
            playersFile.writeString(root.toString(Jval.Jformat.formatted), false);
        }catch(Throwable t){
            Log.err("[MKUKIC] не удалось сохранить players.json", t);
        }
    }

    public static void saveServer(){
        if(serverFile == null) return;
        try{
            serverFile.writeString(json.prettyPrint(json.toJson(cfg, Cfg.class)), false);
        }catch(Throwable t){
            Log.err("[MKUKIC] не удалось сохранить server.json", t);
        }
    }

    // ================================== ДОСТУП ==================================

    public static Rec rec(String id){
        Rec r = players.get(id);
        if(r == null){
            r = new Rec();
            players.put(id, r);
        }
        return r;
    }

    public static Rec rec(Player p){
        Rec r = rec(p.uuid());
        r.lastName = p.plainName();
        if(p.con != null) r.lastIP = p.con.address;
        r.lastSeen = System.currentTimeMillis();
        return r;
    }

    /** Уровень прав: максимум из ванильного админ-флага и роли MKUKIC. */
    public static int level(Player p){
        if(p == null) return 0;
        if(p.isLocal() || !net.active() || net.server() && p.con == null) return 4;
        Rec r = players.get(p.uuid());
        int lvl = r == null ? 0 : r.level;
        if(p.admin && lvl < 2) lvl = 2;
        return lvl;
    }

    public static String roleName(int level){
        return switch(level){
            case 4 -> "владелец";
            case 3 -> "старший админ";
            case 2 -> "админ";
            case 1 -> "модератор";
            default -> "игрок";
        };
    }

    public static boolean banned(String id){
        Rec r = players.get(id);
        return r != null && r.banUntil > System.currentTimeMillis();
    }

    // ================================= ЖУРНАЛ =================================

    public static void log(String type, String text){
        String line = time() + "|" + type + "|" + text;
        recentLog.add(line);
        while(recentLog.size > cfg.maxLogEntries) recentLog.remove(0);
        if(historyFile != null){
            try{
                historyFile.writeString(line + "\n", true);
            }catch(Throwable ignored){
            }
        }
    }

    public static void logPlayer(Player p, String type, String text){
        if(p == null) return;
        log(type, p.plainName() + " [" + p.uuid() + "] " + text);
    }

    public static String time(){
        synchronized(fmt){
            return fmt.format(new Date());
        }
    }

    /** Поиск по журналу: по нику или id (подстрока). */
    public static Seq<String> searchLog(String query, int limit){
        Seq<String> out = new Seq<>();
        String q = query.toLowerCase();
        for(int i = recentLog.size - 1; i >= 0 && out.size < limit; i--){
            String line = recentLog.get(i);
            if(line.toLowerCase().contains(q)) out.add(line);
        }
        out.reverse();
        return out;
    }

    /** Ищет онлайн-игрока по нику / id / префиксу uuid. */
    public static @Nullable Player find(String s){
        if(s == null || s.isEmpty()) return null;
        String q = s.toLowerCase();
        Player exact = null, partial = null;
        for(Player p : Groups.player){
            if(p == null) continue;
            if(p.plainName().equalsIgnoreCase(s) || p.uuid().equals(s)) return p;
            if(p.plainName().toLowerCase().equals(q)) exact = p;
            else if(partial == null && (p.plainName().toLowerCase().contains(q) || p.uuid().toLowerCase().startsWith(q))) partial = p;
        }
        return exact != null ? exact : partial;
    }

    /** Ищет игрока (в том числе оффлайн) по нику/id — возвращает id для записи. */
    public static @Nullable String findId(String s){
        Player p = find(s);
        if(p != null) return p.uuid();
        String q = s.toLowerCase();
        for(var e : players.entries()){
            if(e.key.toLowerCase().startsWith(q) || e.value.lastName.toLowerCase().equals(q)) return e.key;
        }
        return null;
    }

    public static Seq<String[]> onlineList(){
        Seq<String[]> out = new Seq<>();
        for(Player p : Groups.player){
            if(p == null) continue;
            Rec r = players.get(p.uuid());
            out.add(new String[]{
                p.plainName(),
                p.uuid(),
                p.team().name,
                roleName(level(p)),
                String.valueOf(r == null ? 0 : r.credits),
                p.dead() || p.unit() == null ? "-" : p.unit().type.name,
                p.con == null ? "host" : (p.con.mobile ? "mobile" : "pc")
            });
        }
        return out;
    }
}
