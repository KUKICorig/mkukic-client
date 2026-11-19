package mkukic.client.qol;

import arc.*;
import arc.files.*;
import arc.scene.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.ui.*;
import mkukic.*;
import mkukic.client.*;

import static mindustry.Vars.*;

/**
 * История чата (v1.16) — порт идей из модов:
 *  - MinerTools «聊天记录»: журнал чата с поиском и копированием;
 *  - MI2-Utilities: свои плавающие окна и экспорт данных;
 *  - Extra Logging 2: понятные метки времени в сообщениях.
 *
 * Ваниль хранит только последние 10 сообщений и не даёт их прочитать из мода
 * (поле приватное), поэтому MKUKIC ведёт СВОЙ журнал:
 *  - PlayerChatEvent  — сообщения других игроков и сервера (приходит и на клиенте);
 *  - ClientChatEvent  — то, что отправил ты (только клиент).
 * Свои сообщения не дублируются: отправка помечается, и совпавшее входящее гасится.
 *
 * Окно: поиск по подстроке, фильтр «без команд», копирование в буфер обмена,
 * экспорт в configs/mkukic-client/chatlog-<дата>.txt (текст без цветовой разметки).
 */
public class ChatLog{
    /** Одна запись журнала. */
    public static class Msg{
        public long millis;
        public String text;
        public boolean mine;

        public String plain(){
            return Strings.stripColors(text == null ? "" : text).replace('\n', ' ');
        }
    }

    /** Максимум записей в журнале (дальше — вытеснение старых). */
    public static final int MAX = 500;

    public static final Seq<Msg> messages = new Seq<>();
    private static final Seq<String> sent = new Seq<>();
    private static Table listRef;
    private static String filter = "";
    private static boolean noCommands = false;
    private static int lastShown = -1;

    // ======================= ЧИСТЫЕ ФУНКЦИИ (тестируются) =======================

    /** Подходит ли запись под фильтр (регистронезависимо) и не команда ли это. */
    public static boolean matches(String text, String query, boolean hideCommands, boolean mine){
        if(text == null) text = "";
        String plain = Strings.stripColors(text).toLowerCase();
        if(hideCommands && plain.startsWith("/") && !mine) return false;
        if(query == null || query.isEmpty()) return true;
        return plain.contains(query.toLowerCase());
    }

    /** Дубликат ли это собственного сообщения (ваниль присылает его обратно). */
    public static boolean isEcho(Seq<String> pending, String text){
        if(text == null || pending.isEmpty()) return false;
        int idx = pending.indexOf(text, false);
        if(idx >= 0){
            pending.remove(idx);
            return true;
        }
        return false;
    }

    /** Обрезать журнал до лимита (удаляются самые старые). */
    public static <T> int prune(Seq<T> seq, int max){
        int removed = 0;
        while(seq.size > max){
            seq.remove(0);
            removed++;
        }
        return removed;
    }

    /** Имя файла экспорта. */
    public static String fileName(long millis){
        return "chatlog-" + new java.text.SimpleDateFormat("yyyy-MM-dd-HH-mm").format(new java.util.Date(millis)) + ".txt";
    }

    // ======================= СОБЫТИЯ =======================

    public static void init(){
        Events.on(PlayerChatEvent.class, e -> onIncoming(e.player, e.message));
        Events.on(ClientChatEvent.class, e -> onOutgoing(e.message));
    }

    private static void onOutgoing(String text){
        if(text == null || !MKUKICSettings.s().chatLog) return;
        sent.add(text);
        while(sent.size > 8) sent.remove(0);
        add(text, true);
    }

    private static void onIncoming(@Nullable Player from, String text){
        if(text == null || !MKUKICSettings.s().chatLog) return;
        if(isEcho(sent, text)) return;               // это наше собственное сообщение
        String name = from == null ? "" : from.plainName();
        add(name.isEmpty() ? text : "[#" + (from.color == null ? "ffffff" : from.color.toString()) + "]"
            + name + ":[white] " + text, false);
    }

    private static void add(String text, boolean mine){
        Msg m = new Msg();
        m.millis = System.currentTimeMillis();
        m.text = text;
        m.mine = mine;
        messages.add(m);
        prune(messages, MAX);
    }

    public static void reset(){
        messages.clear();
        sent.clear();
        lastShown = -1;
    }

    // ======================= ОКНО =======================

    public static void build(Table t){
        QolWindows.section(t, "История чата");
        t.add("[lightgray]Ваниль показывает только 10 последних сообщений и не хранит их после перезахода.\n"
            + "MKUKIC ведёт свой журнал (до " + MAX + " строк) с поиском, копированием и экспортом в файл.\n"
            + "Файлы: configs/mkukic-client/")
            .left().top().wrap().width(400f).pad(3f, 0f, 6f, 0f).row();

        t.table(c -> {
            c.left();
            c.add((char)Iconc.zoom + " ").padRight(3f);
            TextField tf = new TextField("");
            tf.setMessageText("поиск по тексту или нику...");
            tf.changed(() -> {
                filter = tf.getText();
                rebuildList();
            });
            c.add(tf).width(230f).padRight(6f);
            c.check("Без команд", noCommands, v -> {
                noCommands = v;
                rebuildList();
            }).padRight(6f);
            c.label(() -> "[#8b94a5]" + messages.size + " стр.").left();
        }).left().row();

        t.table(c -> {
            c.left();
            c.button((char)Iconc.copy + "  Копировать", MKUKICSkin.actBtn, ChatLog::copyFiltered).minWidth(130f).height(32f).pad(2f);
            c.button((char)Iconc.save + "  В файл", MKUKICSkin.actBtn, ChatLog::export).minWidth(110f).height(32f).pad(2f);
            c.button((char)Iconc.cancel + "  Очистить", MKUKICSkin.actBtn, () -> {
                reset();
                rebuildList();
            }).minWidth(110f).height(32f).pad(2f);
        }).left().pad(3f, 0f, 4f, 0f).row();

        Table list = new Table();
        list.top().left();
        listRef = list;
        t.pane(MKUKICSkin.scroll, list).maxHeight(260f).growX().row();

        t.add("[#7e8798]Клик по строке — скопировать её в буфер обмена.").left().padTop(4f).row();
        rebuildList();
    }

    /** Обновление списка раз в ~0.5с, если окно открыто (новые сообщения появляются постоянно). */
    public static void tick(){
        if(listRef == null || !QolWindows.isOpen("chat")) return;
        if(messages.size == lastShown) return;
        lastShown = messages.size;
        rebuildList();
    }

    private static Seq<Msg> filtered(){
        Seq<Msg> out = new Seq<>();
        for(Msg m : messages){
            if(matches(m.text, filter, noCommands, m.mine)) out.add(m);
        }
        return out;
    }

    private static void rebuildList(){
        if(listRef == null) return;
        Table list = listRef;
        list.clear();
        list.top().left();
        Seq<Msg> f = filtered();
        lastShown = messages.size;
        if(f.isEmpty()){
            list.add("[darkgray]" + (messages.isEmpty() ? "журнал пуст — напиши что-нибудь в чат" : "ничего не найдено")).left().pad(6f).row();
            list.pack();
            return;
        }
        // показываем хвост (свежие снизу, как в чате), максимум 200 строк
        int from = Math.max(0, f.size - 200);
        for(int i = from; i < f.size; i++){
            Msg m = f.get(i);
            String line = "[#5f6b7d]" + clockOf(m) + "[] " + (m.mine ? "[#6fd6ff]>[] " : "") + m.text;
            final String copy = m.plain();
            list.button(line, MKUKICSkin.navBtn, () -> {
                Core.app.setClipboardText(clockOf(m) + " " + copy);
                MKUKICMod.toast("[lightgray]строка скопирована");
            }).minWidth(420f).height(26f).left().pad(0.5f).get().getLabel()
                .setAlignment(Align.left);
            list.row();
        }
        list.pack();
    }

    private static String clockOf(Msg m){
        try{
            return new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date(m.millis));
        }catch(Throwable t){
            return "--:--:--";
        }
    }

    private static String buildText(){
        StringBuilder sb = new StringBuilder();
        for(Msg m : filtered()) sb.append(clockOf(m)).append(' ').append(m.plain()).append('\n');
        return sb.toString();
    }

    private static void copyFiltered(){
        String text = buildText();
        Core.app.setClipboardText(text);
        MKUKICMod.toast("[lightgray]в буфер обмена скопировано строк: [accent]" + filtered().size);
    }

    private static void export(){
        try{
            Fi dir = MKUKICSettings.dir();
            if(dir == null){
                MKUKICMod.toast("[scarlet]папка конфигов недоступна");
                return;
            }
            Fi f = dir.child(fileName(System.currentTimeMillis()));
            f.writeString(buildText());
            MKUKICMod.toast("[lime]сохранено: [lightgray]" + f.name());
        }catch(Throwable t){
            Log.err("[MKUKIC] не удалось сохранить историю чата", t);
            MKUKICMod.toast("[scarlet]ошибка экспорта: " + t.getClass().getSimpleName());
        }
    }
}
