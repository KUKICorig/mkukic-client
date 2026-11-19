package mkukic.client.onix;

import arc.*;
import arc.math.*;
import arc.math.geom.*;
import arc.struct.*;
import arc.util.*;
import mindustry.content.*;
import mindustry.entities.units.*;
import mindustry.gen.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.distribution.*;
import mindustry.world.blocks.logic.*;
import mkukic.*;
import mkukic.client.*;

import static mindustry.Vars.*;

/**
 * <b>Команды клиента Onix</b> — порт раздела «Commands» из Onix Lite:
 * {@code go, lookat, tp, here, cursor, unit, uc, count, builder, miner, voids,
 * procfind, unloaders, blank, replacemessage, gamejointext/gamewintext/gamelosetext,
 * text, bannedcontent, mute/unmute/clearmutes, pathing, admin}.
 *
 * <p>Вызов — из консоли Onix (вкладка «Действия»/«Код»), из палитры команд MKUKIC
 * или из чата через префикс {@code onixCmdPrefix}. Отличие от ванили: эти команды
 * <b>клиентские</b> — они ничего не просят у сервера и работают даже там,
 * где серверные команды выключены.
 */
public class OnixCommands{
    /** Описание команды для списка. */
    public static class Cmd{
        public final String name, args, desc;
        public final arc.func.Cons<String[]> run;

        public Cmd(String name, String args, String desc, arc.func.Cons<String[]> run){
            this.name = name;
            this.args = args;
            this.desc = desc;
            this.run = run;
        }
    }

    public static final Seq<Cmd> all = new Seq<>();
    public static final Seq<String> mutes = new Seq<>();
    public static final ObjectMap<String, String> replacers = new ObjectMap<>();

    private static boolean built;

    // ============================== РЕЕСТР ==============================

    public static Seq<Cmd> commands(){
        if(!built) build();
        return all;
    }

    private static void build(){
        built = true;
        all.clear();

        add("help", "[command]", "Описание команд", a -> {
            if(a.length > 0){
                Cmd c = find(a[0]);
                if(c == null) say("нет команды " + a[0]);
                else say("[accent]" + c.name + " [lightgray]" + c.args + "[] — " + c.desc);
            }else{
                StringBuilder sb = new StringBuilder("[accent]Команды клиента Onix (MKUKIC):\n");
                for(Cmd c : all) sb.append("  [lightgray]").append(prefix()).append(c.name).append(" [darkgray]").append(c.args).append("[]\n");
                say(sb.toString());
            }
        });

        add("go", "[x y | cursor | here]", "Идти к координатам / к курсору / к последним из чата", a -> {
            float[] xy = coords(a);
            if(xy == null){ say("нужны координаты: go x y"); return; }
            OnixAutoUnit.clearQueue();
            lastCoords[0] = xy[0];
            lastCoords[1] = xy[1];
            say("[accent]иду к " + (int)xy[0] + "," + (int)xy[1]);
            goTo(xy[0], xy[1]);
        });

        add("lookat", "[x y | cursor | here]", "Посмотреть на координаты / на курсор", a -> {
            float[] xy = coords(a);
            if(xy == null){ say("нужны координаты"); return; }
            Core.camera.position.set(xy[0], xy[1]);
            say("[accent]камера на " + (int)(xy[0] / tilesize) + "," + (int)(xy[1] / tilesize));
        });

        add("tp", "<x y | cursor>", "Телепорт (только если сервер не в строгом режиме)", a -> {
            if(Onix.strict() || MKUKICSettings.s().onixStrictMode){
                say("[scarlet]телепорт выключен (строгий режим)");
                return;
            }
            float[] xy = coords(a);
            if(xy == null || player == null || player.unit() == null){ say("нужны координаты"); return; }
            player.unit().set(xy[0], xy[1]);
            Core.camera.position.set(xy[0], xy[1]);
            say("[accent]телепорт на " + (int)(xy[0] / tilesize) + "," + (int)(xy[1] / tilesize));
        });

        add("here", "[message...]", "Отправить сообщение со своими координатами", a ->
            OnixChat.send(join(a) + " " + coordText(player == null ? 0f : player.x(), player == null ? 0f : player.y())));

        add("cursor", "[message...]", "Отправить сообщение с координатами курсора", a -> {
            Vec2 v = Core.input == null ? null : Core.input.mouseWorld();
            OnixChat.send(join(a) + " " + coordText(v == null ? 0f : v.x, v == null ? 0f : v.y));
        });

        add("unit", "<unittype>", "Переключиться на юнит этого типа (ближайший)", a -> {
            UnitType t = parseUnit(a);
            if(t == null){ say("неизвестный юнит"); return; }
            Unit u = nearestOfType(t);
            if(u == null){ say("нет свободного " + t.localizedName); return; }
            if(player != null){
                player.unit(u);
                Core.camera.position.set(u.x, u.y);
                say("[accent]вы теперь " + t.localizedName);
            }
        });

        add("uc", "<unittype>", "Переключиться на ближайшего к курсору юнита типа", a -> {
            UnitType t = parseUnit(a);
            if(t == null){ say("неизвестный юнит"); return; }
            Vec2 c = Core.input == null ? null : Core.input.mouseWorld();
            Unit best = null;
            float bd = Float.MAX_VALUE;
            for(Unit u : Groups.unit){
                if(u == null || u.dead || u.type != t || u.isPlayer()) continue;
                float d = c == null ? u.dst2(player) : u.dst2(c.x, c.y);
                if(d < bd){ bd = d; best = u; }
            }
            if(best == null){ say("нет " + t.localizedName + " рядом с курсором"); return; }
            player.unit(best);
            say("[accent]вы теперь " + t.localizedName + " у курсора");
        });

        add("count", "<unittype>", "Посчитать юнитов типа", a -> {
            UnitType t = parseUnit(a);
            if(t == null){
                int total = 0;
                for(Unit u : Groups.unit) if(u != null && u.team == player.team()) total++;
                say("[accent]всего юнитов команды: " + total);
                return;
            }
            int mine = 0, all = 0;
            for(Unit u : Groups.unit){
                if(u == null || u.type != t) continue;
                all++;
                if(u.team == player.team()) mine++;
            }
            say("[accent]" + t.localizedName + ": ваших " + mine + ", всего на карте " + all);
        });

        add("builder", "[all|self|broken|boul]", "Авто-стройка: всё / свои планы / сломанное", a -> {
            String mode = a.length > 0 ? a[0].toLowerCase() : "broken";
            int n;
            switch(mode){
                case "all":
                case "self":
                    n = OnixAutoUnit.capturePlayerPlans();
                    break;
                case "boul":
                case "broken":
                default:
                    n = OnixAutoUnit.addBrokenAround(MKUKICSettings.s().onixAutoRadius);
                    break;
            }
            MKUKICSettings s = MKUKICSettings.s();
            s.onixAutoUnit = true;
            s.onixAutoBuild = true;
            s.onixAutoMineMissing = true;
            MKUKICSettings.save();
            say("[accent]builder " + mode + ": +" + n + " планов, Авто-Уни включён");
        });

        add("miner", "[item]", "Авто-копка (по умолчанию — самый дефицитный ресурс)", a -> {
            MKUKICSettings s = MKUKICSettings.s();
            s.onixAutoUnit = true;
            s.onixAutoMine = true;
            MKUKICSettings.save();
            say("[accent]автомайнер включён" + (a.length > 0 ? " (" + a[0] + ")" : ""));
        });

        add("fill", "[on|off]", "Авто-Филл: развоз ресурсов по голодным постройкам", a -> {
            MKUKICSettings s = MKUKICSettings.s();
            s.onixAutofill = a.length == 0 || a[0].equalsIgnoreCase("on");
            MKUKICSettings.save();
            say((s.onixAutofill ? "[accent]Авто-Филл включён" : "[scarlet]Авто-Филл выключен"));
        });

        add("bot", "[assist|antigrief] [nick]", "Включить бота на игрока", a -> {
            MKUKICSettings s = MKUKICSettings.s();
            if(a.length > 0) s.onixBotMode = a[0].equalsIgnoreCase("antigrief") || a[0].equalsIgnoreCase("антигриф") ? 1 : 0;
            if(a.length > 1) s.onixBotTarget = a[1];
            s.onixBot = true;
            MKUKICSettings.save();
            say("[accent]бот (" + (s.onixBotMode == 1 ? "антигрифер" : "ассист") + ") включён"
                + (s.onixBotTarget.isEmpty() ? "" : " на " + s.onixBotTarget));
        });

        add("voids", "[count]", "Показать пустоты (void) на карте", a -> {
            int want = a.length > 0 ? Strings.parseInt(a[0], 10) : 10;
            int found = 0;
            StringBuilder sb = new StringBuilder();
            for(int i = 0; i < world.width() * world.height() && found < want; i += 37){
                Tile t = world.tile(i % world.width(), i / world.width());
                if(t == null || t.floor() != Blocks.space) continue;
                if(t.block() != Blocks.air) continue;
                found++;
                sb.append("  ").append(t.x).append(",").append(t.y).append('\n');
            }
            say("[accent]пустоты (" + found + "):\n" + (found == 0 ? "  не найдено (сканирование выборочное)" : sb.toString()));
        });

        add("procfind", "[regex]", "Найти процессоры, подходящие под регулярку", a -> {
            String rx = a.length > 0 ? String.join(" ", a) : "";
            int n = 0;
            StringBuilder sb = new StringBuilder();
            for(Building b : Groups.build){
                if(b == null || !(b instanceof LogicBlock.LogicBuild)) continue;
                LogicBlock.LogicBuild lb = (LogicBlock.LogicBuild)b;
                String code = "";
                try{
                    code = lb.block == null ? "" : String.valueOf(lb.config());
                }catch(Throwable ignored){}
                if(!rx.isEmpty()){
                    try{
                        if(!code.matches("(?s).*" + rx + ".*")) continue;
                    }catch(Throwable t){
                        say("[scarlet]плохая регулярка: " + t.getMessage());
                        return;
                    }
                }
                n++;
                if(sb.length() < 1200) sb.append("  ").append(b.tile.x).append(",").append(b.tile.y)
                    .append(" [darkgray]").append(b.block.name).append("[]\n");
            }
            say("[accent]процессоров найдено: " + n + "\n" + sb);
        });

        add("unloaders", "<item> [enabledOnly]", "Показать позиции разгрузчиков предмета", a -> {
            Item item = a.length > 0 ? parseItem(a[0]) : null;
            if(item == null){ say("нужен предмет, например: unloaders copper"); return; }
            int n = 0;
            StringBuilder sb = new StringBuilder();
            for(Building b : Groups.build){
                if(b == null || !(b.block instanceof mindustry.world.blocks.storage.Unloader)) continue;
                if(b.team != player.team()) continue;
                Object cfg = b.config();
                if(cfg instanceof Item ci && ci != item) continue;
                n++;
                if(sb.length() < 1200) sb.append("  ").append(b.tile.x).append(",").append(b.tile.y).append('\n');
            }
            say("[accent]разгрузчиков " + item.localizedName + ": " + n + "\n" + sb);
        });

        add("blank", "", "Отправить пустое сообщение (символ нулевой ширины)", a -> OnixChat.sendBlank());

        add("replacemessage", "<from> <to>", "Замена текста в отправляемых сообщениях", a -> {
            if(a.length < 2){ say("нужно: replacemessage <from> <to>"); return; }
            replacers.put(a[0], String.join(" ", java.util.Arrays.copyOfRange(a, 1, a.length)));
            say("[accent]замена добавлена: " + a[0] + " → " + replacers.get(a[0], ""));
        });

        add("gamejointext", "[text]", "Текст, отправляемый при заходе", a -> setTxt("join", a));
        add("gamewintext", "[text]", "Текст при победе", a -> setTxt("win", a));
        add("gamelosetext", "[text]", "Текст при поражении", a -> setTxt("lose", a));

        add("bannedcontent", "", "Список запрещённых на карте блоков и юнитов", a -> {
            if(state == null || state.rules == null){ say("нет мира"); return; }
            Seq<String> bb = new Seq<>(), bu = new Seq<>();
            for(Block b : content.blocks()){
                if(b != null && state.rules.isBanned(b)) bb.add(b.localizedName);
            }
            for(UnitType u : content.units()){
                if(u != null && state.rules.isBanned(u)) bu.add(u.localizedName);
            }
            say("[accent]Запрещено блоков: " + bb.size + "\n  [lightgray]" + String.join(", ", bb)
                + "\n[accent]Юнитов: " + bu.size + "\n  [lightgray]" + String.join(", ", bu));
        });

        add("mute", "<player>", "Глушить сообщения игрока (локально)", a -> {
            if(a.length == 0){ say("нужен ник или id"); return; }
            String key = String.join(" ", a);
            if(!mutes.contains(key, false)) mutes.add(key);
            say("[accent]игрок заглушён: " + key);
        });
        add("unmute", "<player>", "Снять глушение", a -> {
            String key = String.join(" ", a);
            mutes.remove(key);
            say("[accent]глушение снято: " + key);
        });
        add("clearmutes", "", "Очистить список глушения", a -> {
            mutes.clear();
            say("[accent]список глушения очищен");
        });

        add("pathing", "", "Переключить классический/оптимизированный A*", a -> {
            MKUKICSettings s = MKUKICSettings.s();
            s.onixAutoSkipUnreachable = !s.onixAutoSkipUnreachable;
            MKUKICSettings.save();
            say("[accent]пропуск недостижимых целей: " + (s.onixAutoSkipUnreachable ? "вкл" : "выкл"));
        });

        add("accounts", "", "Список сохранённых личностей", a -> {
            Seq<OnixAccounts.Account> list = OnixAccounts.all();
            if(list.isEmpty()){ say("аккаунтов нет — сохраните текущую личность в меню Onix"); return; }
            StringBuilder sb = new StringBuilder("[accent]Аккаунты:\n");
            for(OnixAccounts.Account ac : list){
                sb.append("  ").append(ac.label.equals(OnixAccounts.activeLabel) ? "[gold]★ " : "[lightgray]  ")
                    .append(ac.label).append(" [darkgray](").append(ac.name).append(")[]\n");
            }
            say(sb.toString());
        });

        add("bypass", "[all]", "Обход бана: сброс UUID и USID", a -> {
            String res = OnixAccounts.bypassBan(a.length > 0 && a[0].equalsIgnoreCase("all"));
            say("[accent]" + res + "[] — переподключитесь к серверу");
        });

        add("detect", "", "Кто определён как клиент", a -> {
            Seq<OnixClientDetect.Verdict> list = OnixClientDetect.list();
            if(list.isEmpty()){ say("никто не помечен"); return; }
            StringBuilder sb = new StringBuilder("[accent]Детект клиентов:\n");
            for(OnixClientDetect.Verdict v : list) sb.append("  ").append(v.describe()).append('\n');
            say(sb.toString());
        });
    }

    private static void add(String name, String args, String desc, arc.func.Cons<String[]> run){
        all.add(new Cmd(name, args, desc, run));
    }

    private static void setTxt(String which, String[] a){
        MKUKICSettings s = MKUKICSettings.s();
        String txt = String.join(" ", a);
        switch(which){
            case "join": s.onixJoinText = txt; break;
            case "win": s.onixWinText = txt; break;
            default: s.onixLoseText = txt; break;
        }
        MKUKICSettings.save();
        say("[accent]текст (" + which + ") установлен: " + (txt.isEmpty() ? "<пусто>" : txt));
    }

    // ============================== ВЫПОЛНЕНИЕ ==============================

    public static String prefix(){
        String p = MKUKICSettings.s().onixCmdPrefix;
        return p == null || p.isEmpty() ? "/m" : p;
    }

    public static @Nullable Cmd find(String name){
        if(name == null) return null;
        String n = name.toLowerCase();
        for(Cmd c : commands()) if(c.name.equals(n)) return c;
        return null;
    }

    /** Строка — команда клиента? */
    public static boolean isCommand(String text){
        if(text == null) return false;
        String p = prefix();
        return text.startsWith(p) && find(stripPrefix(text, p)) != null;
    }

    public static String stripPrefix(String text, String p){
        String rest = text.substring(p.length()).trim();
        int sp = rest.indexOf(' ');
        return sp < 0 ? rest.toLowerCase() : rest.substring(0, sp).toLowerCase();
    }

    /** Выполнить строку вида «/m go 100 200». */
    public static boolean execute(String text){
        if(text == null) return false;
        String p = prefix();
        if(!text.startsWith(p)) return false;
        String rest = text.substring(p.length()).trim();
        if(rest.isEmpty()) return false;
        String[] parts = rest.split("\\s+");
        Cmd c = find(parts[0].toLowerCase());
        if(c == null) return false;
        String[] args = new String[parts.length - 1];
        System.arraycopy(parts, 1, args, 0, args.length);
        try{
            c.run.get(args);
        }catch(Throwable t){
            say("[scarlet]ошибка команды: " + t);
            Log.err("[MKUKIC Onix] command " + c.name, t);
        }
        OnixConsole.add("ACTION", "команда: " + text, 2);
        return true;
    }

    /** Заглушённый ли игрок (для фильтрации чата). */
    public static boolean isMuted(@Nullable Player p){
        if(p == null || mutes.isEmpty()) return false;
        for(String m : mutes){
            if(m.equalsIgnoreCase(p.name()) || m.equals(String.valueOf(p.id()))) return true;
        }
        return false;
    }

    /** Применить пользовательские замены к исходящему тексту. */
    public static String applyReplacers(String text){
        if(text == null || replacers.size == 0) return text;
        for(ObjectMap.Entry<String, String> e : replacers.entries()){
            if(e.key == null || e.value == null) continue;
            text = text.replace(e.key, e.value);
        }
        return text;
    }

    // ============================== ХЕЛПЕРЫ ==============================

    /** Время МСК [чч:мм:сс] — как в Onix («Действия: время МСК до секунд»). */
    public static String mskClock(long millis){
        try{
            java.time.ZonedDateTime z = java.time.Instant.ofEpochMilli(millis)
                .atZone(java.time.ZoneId.of("Europe/Moscow"));
            return String.format("%02d:%02d:%02d", z.getHour(), z.getMinute(), z.getSecond());
        }catch(Throwable t){
            return "--:--:--";
        }
    }

    public static String coordText(float wx, float wy){
        return "[#" + Onix.theme2.toString() + "](" + (int)(wx / tilesize) + "," + (int)(wy / tilesize) + ")[]";
    }

    /** Последние координаты, встреченные в чате или в командах. */
    public static final float[] lastCoords = {Float.NaN, Float.NaN};

    /** Запомнить координаты из текста (Onix: «или к последним координатам в чате»). */
    public static void scanCoords(String text){
        if(text == null) return;
        String plain = Strings.stripColors(text);
        int i = 0;
        while(i < plain.length()){
            int a = plain.indexOf('(', i);
            if(a < 0) break;
            int b = plain.indexOf(')', a);
            if(b < 0) break;
            String[] p = plain.substring(a + 1, b).split(",");
            if(p.length == 2){
                try{
                    lastCoords[0] = Float.parseFloat(p[0].trim()) * tilesize;
                    lastCoords[1] = Float.parseFloat(p[1].trim()) * tilesize;
                    return;
                }catch(Throwable ignored){}
            }
            i = b + 1;
        }
    }

    private static @Nullable float[] coords(String[] a){
        Vec2 c = Core.input == null ? null : Core.input.mouseWorld();
        return parseCoords(a,
            c == null ? Float.NaN : c.x, c == null ? Float.NaN : c.y,
            player == null ? Float.NaN : player.x(), player == null ? Float.NaN : player.y(),
            world == null ? 0 : world.width());
    }

    /**
     * Чистый разбор координат для команд ({@code go}, {@code lookat}, {@code tp}):
     * <ul>
     *   <li>{@code cursor} / {@code c} / {@code курсор} — позиция курсора;</li>
     *   <li>{@code here} / {@code me} / {@code тут} — позиция своего юнита;</li>
     *   <li>{@code x y} — числа: похоже на тайлы (< 4000 и в пределах карты) → умножаем на {@code tilesize};</li>
     *   <li>иначе — последние координаты, встреченные в чате ({@link #lastCoords}).</li>
     * </ul>
     * Все «внешние» значения передаются параметрами, чтобы функцию можно было тестировать.
     * @return мировые координаты {x, y} или {@code null}, если взять их неоткуда.
     */
    public static @Nullable float[] parseCoords(String[] a, float cursorX, float cursorY,
                                                float selfX, float selfY, int worldWidthTiles){
        if(a != null && a.length > 0 && a[0] != null){
            String k = a[0].toLowerCase();
            if(k.equals("cursor") || k.equals("c") || k.equals("курсор")){
                if(!Float.isNaN(cursorX) && !Float.isNaN(cursorY)) return new float[]{cursorX, cursorY};
            }else if(k.equals("here") || k.equals("me") || k.equals("тут") || k.equals("здесь")){
                if(!Float.isNaN(selfX) && !Float.isNaN(selfY)) return new float[]{selfX, selfY};
            }
        }
        if(a != null && a.length >= 2){
            try{
                float x = Float.parseFloat(a[0]), y = Float.parseFloat(a[1]);
                if(!Float.isNaN(x) && !Float.isNaN(y)){
                    // если числа похожи на тайлы (< 4000), считаем тайлами
                    if(Math.abs(x) < 4000 && Math.abs(y) < 4000
                        && (x != (int)x || y != (int)y || worldWidthTiles <= 0 || Math.abs(x) < worldWidthTiles * 2f)){
                        return new float[]{x * tilesize, y * tilesize};
                    }
                    return new float[]{x, y};
                }
            }catch(Throwable ignored){}
        }
        if(!Float.isNaN(lastCoords[0])) return new float[]{lastCoords[0], lastCoords[1]};
        return null;
    }

    /** Пешком/полётом к точке — отдаём юнита Авто-Уни как единственную цель. */
    private static void goTo(float x, float y){
        if(player == null || player.unit() == null) return;
        Tile t = world.tileWorld(x, y);
        if(t == null) return;
        OnixAutoUnit.addTask(t.x, t.y, 0, Blocks.air, null); // маркер цели (не строится)
        // воздушный юнит летит напрямую, наземный — навигатором
        Core.camera.position.set(x, y);
    }

    private static @Nullable UnitType parseUnit(String[] a){
        if(a.length == 0) return null;
        String n = a[0].toLowerCase();
        UnitType best = null;
        for(UnitType t : content.units()){
            if(t == null) continue;
            if(t.name.toLowerCase().equals(n)) return t;
            if(best == null && (t.name.toLowerCase().contains(n)
                || (t.localizedName != null && t.localizedName.toLowerCase().contains(n)))) best = t;
        }
        return best;
    }

    private static @Nullable Item parseItem(String n){
        if(n == null) return null;
        String s = n.toLowerCase();
        for(Item i : content.items()){
            if(i != null && (i.name.toLowerCase().equals(s) || i.localizedName.toLowerCase().equals(s))) return i;
        }
        return null;
    }

    private static @Nullable Unit nearestOfType(UnitType t){
        Unit best = null;
        float bd = Float.MAX_VALUE;
        for(Unit u : Groups.unit){
            if(u == null || u.dead || u.type != t || u.isPlayer()) continue;
            if(player != null && u.team != player.team()) continue;
            float d = u.dst2(player);
            if(d < bd){ bd = d; best = u; }
        }
        return best;
    }

    private static String join(String[] a){
        return a == null || a.length == 0 ? "" : String.join(" ", a);
    }

    /** Показать текст игроку (локально, не отправляя в чат). */
    public static void say(String text){
        try{
            if(ui != null) ui.showInfoToast(text, 6f);
        }catch(Throwable ignored){}
        OnixConsole.add("ACTION", text, 2);
    }
}
