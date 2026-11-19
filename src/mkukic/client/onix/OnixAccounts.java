package mkukic.client.onix;

import arc.*;
import arc.math.*;
import arc.struct.*;
import arc.util.*;
import arc.util.serialization.*;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mkukic.*;

import static mindustry.Vars.*;

/**
 * <b>Аккаунты</b> и <b>«Обход бана»</b> — порт функций Onix Lite:
 *
 * <ul>
 *  <li>«Вкладка в лобби: сохранение ника, UUID и USID под своим названием»;</li>
 *  <li>«Применение кнопкой-замком или кликом; звезда — активный аккаунт»;</li>
 *  <li>«Подсветка гаснет после „Обхода бана“»;</li>
 *  <li>«Кнопка „Обход бана“ в меню — сброс UUID и USID (новая личность на сервере)».</li>
 * </ul>
 *
 * <p>Технически идентичность клиента Mindustry — это три значения:
 * {@code name} (Core.settings «name»), {@code uuid} (Core.settings «uuid»,
 * генерируется в {@code Platform.getUUID()} как base64 от 8 случайных байт) и
 * {@code usid} — отдельный для каждого сервера ключ вида {@code "usid-" + host}
 * (см. {@code NetClient.getUsid(String)}). Обход бана = новые uuid/usid,
 * сохранение аккаунта = запомнить все три и вернуть их обратно.
 *
 * <p>Важно: USID/UUID подставляются в {@code ConnectPacket} в момент подключения,
 * поэтому после смены личности нужно переподключиться к серверу.
 */
public class OnixAccounts{
    /** Сохранённая личность: "label|name|uuid|usid". */
    public static class Account{
        public String label = "", name = "", uuid = "", usid = "";

        public String encode(){
            return label + "|" + name + "|" + uuid + "|" + usid;
        }

        public static @Nullable Account decode(String s){
            if(s == null) return null;
            String[] p = s.split("\\|", -1);
            if(p.length < 4) return null;
            Account a = new Account();
            a.label = p[0];
            a.name = p[1];
            a.uuid = p[2];
            a.usid = p[3];
            return a;
        }
    }

    /** Метка активного аккаунта («звезда» в списке). */
    public static String activeLabel = "";

    private static boolean inited;

    public static void init(){
        if(inited) return;
        inited = true;
        // init() и так вызывается на ClientLoadEvent — вешать ещё один слушатель того же
        // события бессмысленно (и он мог бы сработать прямо во время текущей рассылки).
        activeLabel = "";
    }

    // ============================== ТЕКУЩАЯ ИДЕНТИЧНОСТЬ ==============================

    public static String currentName(){
        return Core.settings.getString("name", "");
    }

    public static String currentUuid(){
        return Core.settings.getString("uuid", "");
    }

    /** USID текущего (или указанного) сервера — ключ «usid-&lt;host&gt;». */
    public static String currentUsid(){
        return Core.settings.getString(usidKey(currentHost()), "");
    }

    public static String usidKey(String host){
        // NetClient.getUsid обрезает хост до части после первого '/'
        if(host != null && host.contains("/")) host = host.substring(host.indexOf('/') + 1);
        return "usid-" + (host == null ? "" : host);
    }

    /** Адрес сервера, к которому подключены (или последний из «серверов»). */
    public static String currentHost(){
        try{
            if(net != null && net.active() && netServer == null){
                // клиент: адрес берём из последнего подключения
                String last = Core.settings.getString("server-last", "");
                if(!last.isEmpty()) return last;
            }
        }catch(Throwable ignored){}
        return Core.settings.getString("server-last", "");
    }

    // ============================== АККАУНТЫ ==============================

    public static Seq<Account> all(){
        Seq<Account> out = new Seq<>();
        for(String s : MKUKICSettings.s().onixAccountList){
            Account a = Account.decode(s);
            if(a != null) out.add(a);
        }
        return out;
    }

    private static void saveAll(Seq<Account> list){
        Seq<String> enc = new Seq<>();
        for(Account a : list) enc.add(a.encode());
        MKUKICSettings.s().onixAccountList = enc;
        MKUKICSettings.save();
    }

    /** Сохранить текущую личность под меткой. */
    public static Account saveCurrent(String label){
        Account a = new Account();
        a.label = label == null || label.isEmpty() ? ("аккаунт " + (all().size + 1)) : label;
        a.name = currentName();
        a.uuid = currentUuid();
        a.usid = currentUsid();
        Seq<Account> list = all();
        Account old = list.find(x -> x.label.equals(a.label));
        if(old != null) list.remove(old);
        list.add(a);
        saveAll(list);
        activeLabel = a.label;
        MKUKICMod.toast("[accent]Onix[]: аккаунт «" + a.label + "» сохранён");
        return a;
    }

    public static void remove(String label){
        Seq<Account> list = all();
        Account a = list.find(x -> x.label.equals(label));
        if(a != null){
            list.remove(a);
            saveAll(list);
            if(activeLabel.equals(label)) activeLabel = "";
        }
    }

    /**
     * Применить аккаунт («кнопка-замок»): ставим ник сразу, UUID/USID — в настройки
     * клиента; они попадут в ConnectPacket при следующем подключении.
     */
    public static boolean apply(Account a){
        if(a == null) return false;
        try{
            if(!a.name.isEmpty()){
                Core.settings.put("name", a.name);
                if(player != null) player.name(a.name);
            }
            if(!a.uuid.isEmpty()) Core.settings.put("uuid", a.uuid);
            if(!a.usid.isEmpty()) Core.settings.put(usidKey(currentHost()), a.usid);
            Core.settings.forceSave();
            activeLabel = a.label;
            MKUKICMod.toast("[accent]Onix[]: применён аккаунт «" + a.label + "»"
                + (net != null && net.client() ? " — переподключитесь к серверу" : ""));
            return true;
        }catch(Throwable t){
            Log.err("[MKUKIC Onix] apply account", t);
            return false;
        }
    }

    // ============================== ОБХОД БАНА ==============================

    /** Новый случайный идентификатор в том же формате, что и у игры (base64, 8 байт). */
    public static String randomId(){
        byte[] b = new byte[8];
        new Rand().nextBytes(b);
        return new String(Base64Coder.encode(b));
    }

    /**
     * «Обход бана»: сброс UUID и USID — новая личность на сервере.
     *
     * @param allServers true = стереть USID для всех серверов, false = только для текущего
     * @return описание того, что сделано
     */
    public static String bypassBan(boolean allServers){
        int removed = 0;
        try{
            String newUuid = randomId();
            Core.settings.put("uuid", newUuid);

            if(allServers){
                removed = clearUsidKeys(null);
            }else{
                String key = usidKey(currentHost());
                if(Core.settings.has(key)){
                    Core.settings.put(key, randomId());
                    removed = 1;
                }else{
                    Core.settings.put(key, randomId());
                    removed = 1;
                }
            }
            Core.settings.forceSave();
            activeLabel = "";   // «подсветка гаснет после обхода бана»
        }catch(Throwable t){
            Log.err("[MKUKIC Onix] bypassBan", t);
            return "ошибка: " + t.getClass().getSimpleName();
        }
        return "новая личность: uuid=" + short_(currentUuid()) + ", usid сброшен (" + removed + ")";
    }

    /**
     * Стереть все ключи «usid-*» из настроек (поле values приватное — через reflection,
     * с фолбэком на сброс последнего сервера).
     */
    @SuppressWarnings("unchecked")
    public static int clearUsidKeys(@Nullable String onlyHostPrefix){
        try{
            java.lang.reflect.Field f = Core.settings.getClass().getDeclaredField("values");
            f.setAccessible(true);
            Object o = f.get(Core.settings);
            if(o instanceof java.util.Map<?, ?> map){
                java.util.List<String> dead = new java.util.ArrayList<>();
                for(Object k : map.keySet()){
                    String key = String.valueOf(k);
                    if(!key.startsWith("usid-")) continue;
                    if(onlyHostPrefix != null && !key.startsWith("usid-" + onlyHostPrefix)) continue;
                    dead.add(key);
                }
                for(String k : dead) map.remove(k);
                Core.settings.forceSave();
                return dead.size();
            }
        }catch(Throwable t){
            Log.warn("[MKUKIC Onix] не удалось перечислить usid-ключи: " + t);
        }
        return 0;
    }

    private static String short_(String s){
        if(s == null || s.length() <= 6) return s == null ? "?" : s;
        return s.substring(0, 4) + "…";
    }

    /** Есть ли сохранённые аккаунты (для строки статуса меню). */
    public static int count(){
        return MKUKICSettings.s().onixAccountList.size;
    }
}
