package mkukic.client.onix;

import arc.*;
import arc.files.*;
import arc.graphics.*;
import arc.input.*;
import arc.math.Mathf;
import mindustry.mod.Mods;
import arc.scene.*;
import arc.scene.event.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import arc.util.Log.*;
import mindustry.gen.*;
import mindustry.ui.*;
import mindustry.ui.dialogs.*;
import mkukic.*;
import mkukic.client.*;

import static mindustry.Vars.*;

/**
 * <b>Консоль Onix</b> — порт функции Onix Lite «Консоль (F8)»:
 *
 * <ul>
 *  <li>«Вкладки Чат / Действия / Логи / Код на весь экран вместо ванильной мини-консоли»;</li>
 *  <li>«История переживает перезапуск: файл всегда хранит прошлый запуск»;</li>
 *  <li>«Выделение + Ctrl+C — копировать; Ctrl+C без выделения — весь таб в буфер»;</li>
 *  <li>«Фильтры DEBUG / INFO / WARN / ERROR + поиск»;</li>
 *  <li>«Чат как в игре: „&gt; [ID n] [Team: t] Ник: текст“, точные игровые цвета ников»;</li>
 *  <li>«Ник копируется с цветом: вставка в игровой чат — цветная»;</li>
 *  <li>«Действия: время МСК [чч:мм:сс], координаты подсвечены»;</li>
 *  <li>«Код: подсветка JS, Tab — дополнить, Ctrl+Enter — выполнить, стрелки — история,
 *      print() уходит в Логи».</li>
 * </ul>
 *
 * <p>Ссылки: в Onix они подсвечены и открываются в браузере — здесь то же самое
 * ({@code Core.app.openURI}).
 */
public class OnixConsole{
    public static final int maxLogs = 3000;

    /** Одна запись журнала. */
    public static class Line{
        public long millis;
        public String text;
        public String level = "INFO";
        /** 0 = лог, 1 = чат, 2 = действие. */
        public int kind;

        public String clock(){
            return OnixCommands.mskClock(millis);
        }
    }

    public static final Seq<Line> lines = new Seq<>();
    public static final Seq<String> codeHistory = new Seq<>();

    private static boolean inited, hooked;
    private static LogHandler original;
    private static BaseDialog dialog;
    private static int tab;
    private static String search = "";
    private static String level = "INFO";
    private static Table body;
    private static Fi logFile;
    private static int historyPos = -1;
    private static TextField codeField;

    private static final String[] tabs = {"Чат", "Действия", "Логи", "Код"};
    private static final String[] completes = {
        "Vars.", "player", "player.unit()", "Groups.player", "Groups.unit", "Groups.build",
        "state.", "world.tile(", "content.blocks()", "content.units()", "content.items()",
        "indexer.", "net.", "ui.", "Log.info(", "print(", "OnixAutoUnit.", "OnixAutoFill.",
        "OnixBot.", "OnixClientDetect.", "MKUKICSettings.s()."
    };

    // ============================== ИНИЦИАЛИЗАЦИЯ ==============================

    public static void init(){
        if(inited || headless) return;
        inited = true;

        // «История переживает перезапуск: файл всегда хранит прошлый запуск»
        Fi dir = MKUKICSettings.dir();
        if(dir != null){
            Fi prev = dir.child("console-previous.log");
            logFile = dir.child("console.log");
            if(logFile.exists()){
                try{
                    // прошлый запуск -> console-previous.log, его и показываем при старте
                    prev.writeString(logFile.readString());
                }catch(Throwable ignored){}
            }
            if(prev.exists()){
                for(String s : prev.readString().split("\n")){
                    if(s.isEmpty()) continue;
                    Line l = new Line();
                    l.text = "[gray](прошлый запуск)[] " + s;
                    l.kind = 0;
                    lines.add(l);
                }
            }
        }

        hookLogger();
        // Окно НЕ строим здесь: init() вызывается на ClientLoadEvent, а сборка диалога
        // требует готовых стилей/сцены. Строим лениво — в show()/toggle() (краш v1.19.0).
    }

    /** Перехват игрового лога: пишем в свой журнал и передаём дальше ванильному хендлеру. */
    private static void hookLogger(){
        if(hooked) return;
        hooked = true;
        original = Log.logger;
        Log.logger = (LogLevel l, String text) -> {
            try{
                add(l.name(), text, 0);
            }catch(Throwable ignored){}
            if(original != null) original.log(l, text);
        };
    }

    /**
     * Кадр интерфейса: здесь живёт горячая клавиша консоли.
     * Обработчик СОЗНАТЕЛЬНО один (не дублируется в {@code ClientHook.handleKeys}) —
     * {@code Core.input.keyTap} не «съедает» нажатие, и два вызова за кадр открыли бы
     * и тут же закрыли окно. Плюс этот тик идёт через {@code Trigger.uiDrawBegin},
     * поэтому консоль открывается и в главном меню, где {@code ui.consolefrag == null}.
     */
    public static void uiTick(){
        if(!inited) return;
        MKUKICSettings s = MKUKICSettings.s();
        if(!s.onixEnabled || !s.onixConsole) return;
        if(!MKUKICKeys.tap(MKUKICKeys.onixConsole)) return;
        if(isOpen()){
            // закрыть можно всегда — в том числе когда фокус в поле вкладки «Код»
            toggle();
        }else if(Core.scene == null || !Core.scene.hasField()){
            // не открываем, пока игрок печатает в чате/консоли сервера/любом поле ввода
            toggle();
        }
    }

    // ============================== ЖУРНАЛ ==============================

    public static void add(String lvl, String text, int kind){
        if(text == null) return;
        Line l = new Line();
        l.millis = System.currentTimeMillis();
        l.text = text;
        l.level = lvl == null ? "INFO" : lvl;
        l.kind = kind;
        lines.add(l);
        while(lines.size > maxLogs) lines.remove(0);
        if(logFile != null && MKUKICSettings.s().onixConsolePersist){
            try{
                logFile.writeString(Strings.stripColors(l.clock() + " [" + l.level + "] " + l.text) + "\n", true);
                if(logFile.length() > 4L * 1024 * 1024) logFile.writeString("");
            }catch(Throwable ignored){}
        }
    }

    /** Запись чата: «&gt; [ID n] [Team: t] Ник: текст» с точными игровыми цветами. */
    public static void addChat(@Nullable Player from, String text, boolean mine){
        String id = from == null ? "?" : String.valueOf(from.id());
        String team = from == null || from.team() == null ? "?" : from.team().name;
        String nick = from == null ? "сервер" : OnixChat.nickWithColors(from);
        add("CHAT", "> [ID " + id + "] [Team: " + team + "] " + nick + "[white]: " + text, 1);
    }

    /** Запись действия (сломал/поставил/вошёл) с координатами. */
    public static void addAction(String text, float wx, float wy){
        String coords = "[#" + Onix.theme2.toString() + "](" + (int)(wx / tilesize) + "," + (int)(wy / tilesize) + ")[]";
        add("ACTION", text + " " + coords, 2);
    }

    private static final ObjectMap<Integer, Long> lastAction = new ObjectMap<>();

    public static void addAction(String text, int key){
        long now = System.currentTimeMillis();
        Long prev = lastAction.get(key, (Long)null);
        if(prev == null) prev = 0L;
        if(now - prev < 500L) return; // анти-флуд одинаковых действий
        lastAction.put(key, now);
        add("ACTION", text, 2);
    }

    /** Проходит ли запись под фильтр вкладки/уровня/поиска. */
    public static boolean filter(Line l){
        if(l.kind != tab && !(tab == 2 && l.kind == 0)) return tab == l.kind;
        if(tab == 2){
            int want = levelOrdinal(level);
            if(levelOrdinal(l.level) < want) return false;
        }
        if(!search.isEmpty()){
            String plain = Strings.stripColors(l.text).toLowerCase();
            if(!plain.contains(search.toLowerCase())) return false;
        }
        return true;
    }

    public static int levelOrdinal(String lvl){
        if(lvl == null) return 1;
        switch(lvl.toUpperCase()){
            case "DEBUG": return 0;
            case "INFO":
            case "CHAT":
            case "ACTION": return 1;
            case "WARN": return 2;
            case "ERR":
            case "ERROR": return 3;
            default: return 1;
        }
    }

    /** Весь текст текущей вкладки (для «Ctrl+C без выделения — весь таб в буфер»). */
    public static String currentTabText(){
        StringBuilder sb = new StringBuilder();
        sb.append("=== MKUKIC Onix-консоль: ").append(tabs[Mathf.clamp(tab, 0, tabs.length - 1)]).append(" ===\n");
        for(Line l : lines){
            if(!filter(l)) continue;
            sb.append(l.clock()).append(" [").append(l.level).append("] ")
                .append(Strings.stripColors(l.text)).append('\n');
        }
        return sb.toString();
    }

    /** Копирование: выделенный текст, иначе вся вкладка. */
    public static boolean copy(@Nullable String selection){
        String s = selection == null || selection.isEmpty() ? currentTabText() : selection;
        try{
            Core.app.setClipboardText(s);
            MKUKICMod.toast("[accent]Onix[]: скопировано (" + s.length() + " симв.)");
            return true;
        }catch(Throwable t){
            return false;
        }
    }

    // ============================== ОКНО ==============================

    public static boolean isOpen(){
        return dialog != null && dialog.isShown();
    }

    public static void toggle(){
        MKUKICSettings s = MKUKICSettings.s();
        if(!s.onixEnabled || !s.onixConsole){
            MKUKICMod.toast("[scarlet]Onix[]: консоль выключена в настройках");
            return;
        }
        if(isOpen()) dialog.hide();
        else show();
    }

    public static void show(){
        if(dialog == null) buildDialog();
        rebuild();
        dialog.show();
    }

    private static void buildDialog(){
        dialog = new BaseDialog("[#" + Onix.theme.toString() + "]MKUKIC[] Onix-консоль"){{
            setFillParent(true);
            titleTable.visible(() -> MKUKICSettings.s().onixConsole);
            cont.top();
            cont.table(t -> {
                t.left().top();
                // вкладки
                for(int i = 0; i < tabs.length; i++){
                    final int ti = i;
                    t.button(tabs[i], MKUKICSkin.toggleBtn, () -> {
                        tab = ti;
                        rebuild();
                    }).checked(b -> tab == ti).minWidth(110f).height(38f).pad(2f);
                }
                t.image(MKUKICSkin.div).width(2f).height(30f).pad(0f, 8f, 0f, 8f);
                // фильтры уровней (для вкладки «Логи»)
                for(String lv : new String[]{"DEBUG", "INFO", "WARN", "ERROR"}){
                    t.button(lv, MKUKICSkin.toggleBtn, () -> {
                        level = lv.equals("ERROR") ? "ERR" : lv;
                        rebuild();
                    }).checked(b -> level.equalsIgnoreCase(lv.equals("ERROR") ? "ERR" : lv))
                        .visible(() -> tab == 2).minWidth(80f).height(34f).pad(2f);
                }
                t.field(search, MKUKICSkin.field, txt -> {
                    search = txt;
                    rebuild();
                }).width(220f).padLeft(6f);
                t.button("копировать", MKUKICSkin.actBtn, () -> copy(getSelection()))
                    .minWidth(130f).height(34f).padLeft(6f);
                t.button("очистить", MKUKICSkin.actBtn, () -> {
                    lines.clear();
                    rebuild();
                }).minWidth(110f).height(34f).padLeft(4f);
            }).left().growX().row();

            cont.table(c -> {
                body = c;
            }).grow().padTop(6f);

            // ВАЖНО: this::hide, а НЕ dialog::hide — поле dialog ещё не присвоено
            // (мы внутри инициализатора того самого объекта), и javac вставляет
            // Objects.requireNonNull(dialog) -> NPE на загрузке клиента.
            buttons.button("закрыть", MKUKICSkin.actBtn, this::hide).size(180f, 44f);
        }};
        // «Ctrl+C — копировать выделение, без выделения — весь таб», «Ctrl+Enter — выполнить»
        dialog.cont.addListener(new InputListener(){
            @Override
            public boolean keyDown(InputEvent event, KeyCode k){
                boolean ctrl = Core.input != null
                    && (Core.input.keyDown(KeyCode.controlLeft) || Core.input.keyDown(KeyCode.controlRight));
                if(ctrl && k == KeyCode.c){
                    copy(getSelection());
                    return true;
                }
                if(ctrl && k == KeyCode.enter && tab == 3){
                    runCode(codeField == null ? "" : codeField.getText());
                    return true;
                }
                return false;
            }
        });
        dialog.shown(() -> {
            if(MKUKICSettings.s().onixConsolePersist) rebuild();
        });
    }

    /** Выделение текста в окне (Scene.getSelection у arc — через focused TextField). */
    private static @Nullable String getSelection(){
        try{
            Element f = Core.scene == null ? null : Core.scene.getKeyboardFocus();
            if(f instanceof TextField tf){
                String sel = tf.getSelection();
                if(sel != null && !sel.isEmpty()) return sel;
            }
        }catch(Throwable ignored){}
        return null;
    }

    private static void rebuild(){
        if(body == null) return;
        body.clear();
        if(tab == 3){
            buildCodeTab(body);
            return;
        }
        body.top().left();
        body.pane(MKUKICSkin.scroll, p -> {
            p.top().left();
            int shown = 0;
            for(int i = lines.size - 1; i >= 0 && shown < 400; i--){
                Line l = lines.get(i);
                if(!filter(l)) continue;
                shown++;
                final Line fl = l;
                String prefix = "[gray]" + l.clock() + "[] ";
                p.table(row -> {
                    row.left().top();
                    row.add(prefix + colorize(l)).left().top().wrap().width(Core.graphics.getWidth() * 0.8f);
                }).left().growX().padBottom(1f);
                // клик по строке — копировать её (ник копируется с цветом)
                p.getCells().peek().get().clicked(() -> copy(l.text));
                p.row();
            }
            if(shown == 0) p.add("[gray]пусто").padTop(20f);
        }).grow();
        body.row();
        body.label(() -> "[#8b94a5]строк: " + lines.size + " | фильтр: " + (tab == 2 ? level + " + " : "") + (search.isEmpty() ? "нет" : "«" + search + "»")
            + " | [accent]клик по строке[] — копировать, [accent]Ctrl+C[] — вся вкладка").left().padTop(4f).row();
    }

    /** Раскраска строки по уровню; координаты подсвечиваются (вкладка «Действия»). */
    private static String colorize(Line l){
        String t = l.text == null ? "" : l.text;
        switch(l.level){
            case "err":
            case "ERR":
            case "ERROR": return "[scarlet]" + t + "[]";
            case "warn":
            case "WARN": return "[gold]" + t + "[]";
            case "debug":
            case "DEBUG": return "[darkgray]" + t + "[]";
            default: return t;
        }
    }

    // ============================== ВКЛАДКА «КОД» ==============================

    private static void buildCodeTab(Table t){
        t.top().left();
        t.add("[#8b94a5]JavaScript (Rhino, как в /js сервера). [accent]Ctrl+Enter[] — выполнить, "
            + "[accent]Tab[] — дополнить, [accent]↑/↓[] — история, print() уходит в Логи.").left().wrap().width(700f).padBottom(6f).row();

        // поле ввода кода: «Tab — дополнить, Ctrl+Enter — выполнить, стрелки — история»
        TextField input = t.field("", MKUKICSkin.field, txt -> {}).growX().height(40f).padTop(6f).get();
        codeField = input;
        input.addListener(new InputListener(){
            @Override
            public boolean keyDown(InputEvent event, KeyCode k){
                boolean ctrl = Core.input != null
                    && (Core.input.keyDown(KeyCode.controlLeft) || Core.input.keyDown(KeyCode.controlRight));
                if(k == KeyCode.tab){
                    complete(input);
                    return true;
                }
                if(k == KeyCode.up){
                    history(input, -1);
                    return true;
                }
                if(k == KeyCode.down){
                    history(input, 1);
                    return true;
                }
                if(k == KeyCode.enter){
                    if(ctrl || true) runCode(input.getText());
                    return true;
                }
                return false;
            }
        });
        t.row();
        t.table(c -> {
            c.left();
            c.button("выполнить", MKUKICSkin.actBtn, () -> runCode(input.getText())).minWidth(150f).height(40f).pad(3f);
            c.button("очистить поле", MKUKICSkin.actBtn, () -> input.setText("")).minWidth(150f).height(40f).pad(3f);
            c.button("print в логи", MKUKICSkin.actBtn, () -> runCode("print(\"привет из MKUKIC\")")).minWidth(160f).height(40f).pad(3f);
        }).left().growX().padTop(6f).row();

        t.table(res -> {
            res.left().top();
            res.add("[#8b94a5]Результат последнего выполнения появится в Логах.").left().growX();
        }).growX().padTop(8f).row();
    }

    /** Tab — дополнить: подставляет самый длинный общий префикс из словаря. */
    public static void complete(TextField field){
        if(field == null) return;
        String txt = field.getText() == null ? "" : field.getText();
        String token = txt;
        int sp = Math.max(token.lastIndexOf(' '), Math.max(token.lastIndexOf('('), token.lastIndexOf(',')));
        String head = sp < 0 ? "" : token.substring(0, sp + 1);
        String part = sp < 0 ? token : token.substring(sp + 1);
        if(part.isEmpty()) return;
        String best = null;
        for(String c : completes){
            if(c.toLowerCase().startsWith(part.toLowerCase())){
                if(best == null) best = c;
                else best = commonPrefix(best, c);
            }
        }
        if(best != null && best.length() > part.length()) field.setText(head + best);
    }

    public static String commonPrefix(String a, String b){
        int n = Math.min(a.length(), b.length());
        int i = 0;
        while(i < n && a.charAt(i) == b.charAt(i)) i++;
        return a.substring(0, i);
    }

    private static void history(TextField field, int dir){
        if(codeHistory.isEmpty()) return;
        historyPos = Mathf.clamp(historyPos + dir, -1, codeHistory.size - 1);
        if(historyPos < 0) field.setText("");
        else field.setText(codeHistory.get(codeHistory.size - 1 - historyPos));
    }

    /** Выполнить JS-код; результат и print() уходят в журнал. */
    public static String runCode(String code){
        if(code == null || code.trim().isEmpty()) return "";
        codeHistory.add(code);
        if(codeHistory.size > 50) codeHistory.remove(0);
        historyPos = -1;
        String result;
        try{
            Object scripts = getScripts();
            if(scripts == null){
                result = "JavaScript-движок игры недоступен (mods.scripts == null)";
            }else{
                java.lang.reflect.Method m = scripts.getClass().getMethod("runConsole", String.class);
                Object o = m.invoke(scripts, code);
                result = o == null ? "null" : String.valueOf(o);
            }
        }catch(Throwable t){
            Throwable c = t.getCause() == null ? t : t.getCause();
            result = "ошибка: " + c;
        }
        add("INFO", "[accent]js> [white]" + code, 0);
        add("INFO", "[#" + Onix.theme2.toString() + "]→ [white]" + result, 0);
        return result;
    }

    /** mods.scripts — приватное поле, достаём один раз через reflection. */
    private static Object cachedScripts;
    private static boolean scriptsTried;

    private static @Nullable Object getScripts(){
        if(scriptsTried) return cachedScripts;
        scriptsTried = true;
        try{
            java.lang.reflect.Field f = Mods.class.getDeclaredField("scripts");
            f.setAccessible(true);
            cachedScripts = f.get(mods);
        }catch(Throwable t){
            cachedScripts = null;
        }
        return cachedScripts;
    }

    /** Открыть ссылку в браузере (из Onix: «тап открывает в браузере»). */
    public static void openLink(String url){
        try{
            Core.app.openURI(url);
        }catch(Throwable t){
            Log.warn("[MKUKIC Onix] не открыть ссылку: " + t);
        }
    }

    /** Найти http(s)-ссылки в тексте. */
    public static Seq<String> linksIn(String text){
        Seq<String> out = new Seq<>();
        if(text == null) return out;
        String plain = Strings.stripColors(text);
        int i = 0;
        while(i < plain.length()){
            int a = plain.indexOf("http", i);
            if(a < 0) break;
            int b = a;
            while(b < plain.length() && !Character.isWhitespace(plain.charAt(b))) b++;
            String url = plain.substring(a, b);
            if(url.startsWith("http://") || url.startsWith("https://")) out.add(url);
            i = b + 1;
        }
        return out;
    }
}
