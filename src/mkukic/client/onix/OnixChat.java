package mkukic.client.onix;

import arc.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.input.*;
import arc.math.*;
import arc.math.geom.*;
import arc.scene.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.Scl;
import arc.struct.*;
import arc.util.*;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.type.*;
import mindustry.content.Blocks;
import mindustry.core.NetClient;
import mindustry.ui.*;
import mindustry.ui.fragments.ChatFragment;
import mkukic.*;

import static mindustry.Vars.*;

/**
 * <b>Чат-функции Onix Lite</b> — порт:
 *
 * <ul>
 *  <li><b>Голосование в чате:</b> «кнопки „vote y“ / „vote n“ появляются отдельным блоком
 *      под сообщением о голосовании; стоят на отдельной строке и не накрывают текст;
 *      распознавание подсказки /vote не зависит от языка сервера; после голоса кнопки
 *      гаснут вместе с сообщением».</li>
 *  <li><b>Копирование из чата:</b> «ПКМ по сообщению копирует текст со всеми цветами
 *      и цветным никнеймом»; тумблер по умолчанию выключен, состояние сохраняется.</li>
 *  <li><b>Ники:</b> «копирование ника теперь работает с цветами всегда, без лимита
 *      на количество тегов [ в имени».</li>
 *  <li><b>Автоответчик АФК:</b> «цветной, включён по умолчанию, триггер — ник подстрокой
 *      в любом месте».</li>
 *  <li><b>Текст при заходе/победе/поражении</b> ({@code gamejointext/gamewintext/gamelosetext}).</li>
 *  <li><b>Подстановки</b> {@code %s} (пожать плечами), {@code %c} (координаты курсора),
 *      {@code %h} (координаты юнита).</li>
 *  <li><b>Блокировка серверных окон и анти-спам</b>.</li>
 * </ul>
 */
public class OnixChat{
    /** Строка чата, которую мы «видим» на экране (для копирования по ПКМ). */
    private static class Row{
        String text;
        float x, y, w, h;
        int index;
    }

    private static final Seq<Row> rows = new Seq<>();
    private static final Seq<String> journal = new Seq<>();
    private static final ObjectMap<String, Float> replied = new ObjectMap<>();
    private static final Seq<String> suppressed = new Seq<>();

    private static boolean inited;
    private static boolean voteActive, voted;
    private static String voteText = "";
    private static float voteUntil;
    private static int lastSize = -1;
    private static float lastSpamNotice = -9999f;

    // reflection-поля ванильного ChatFragment (приватные)
    private static java.lang.reflect.Field fMessages, fOffsetX, fOffsetY, fFontOffsetX,
        fTextSpacing, fFont, fScrollPos;
    private static boolean reflectOk;

    public static void init(){
        if(inited) return;
        inited = true;

        try{
            Class<?> c = ChatFragment.class;
            fMessages = c.getDeclaredField("messages");
            fOffsetX = c.getDeclaredField("offsetx");
            fOffsetY = c.getDeclaredField("offsety");
            fFontOffsetX = c.getDeclaredField("fontoffsetx");
            fTextSpacing = c.getDeclaredField("textspacing");
            fFont = c.getDeclaredField("font");
            fScrollPos = c.getDeclaredField("scrollPos");
            for(java.lang.reflect.Field f : new java.lang.reflect.Field[]{fMessages, fOffsetX, fOffsetY, fFontOffsetX, fTextSpacing, fFont, fScrollPos})
                f.setAccessible(true);
            reflectOk = true;
        }catch(Throwable t){
            reflectOk = false;
            Log.warn("[MKUKIC Onix] копирование из чата недоступно (ChatFragment изменился): " + t);
        }

        // входящие сообщения: журнал, голосование, автоответчик
        Events.on(PlayerChatEvent.class, e -> {
            if(e.message == null) return;
            journal(e.player == null ? "" : e.player.coloredName(), e.message, false);
            checkVote(e.message);
            checkAfkReply(e.player, e.message);
        });
        // свои сообщения (чтобы не отвечать самому себе и не дублировать в журнале)
        Events.on(ClientChatEvent.class, e -> {
            if(e.message == null) return;
            journal(player == null ? "" : player.coloredName(), e.message, true);
            suppressed.add(e.message);
            if(suppressed.size > 8) suppressed.remove(0);
        });

        // текст при заходе на сервер
        Events.on(WorldLoadEvent.class, e -> Core.app.post(() -> {
            MKUKICSettings s = MKUKICSettings.s();
            if(!s.onixEnabled || s.onixJoinText.isEmpty()) return;
            if(state != null && state.isGame()) send(s.onixJoinText);
        }));
        // победа / поражение
        Events.on(GameOverEvent.class, e -> {
            MKUKICSettings s = MKUKICSettings.s();
            if(!s.onixEnabled || state == null) return;
            boolean win = player != null && e.winner == player.team();
            String txt = win ? s.onixWinText : s.onixLoseText;
            if(!txt.isEmpty()) send(txt);
        });

        // ПКМ по сообщению — копирование
        Events.run(Trigger.uiDrawBegin, OnixChat::layoutRows);
    }

    // ============================== ЖУРНАЛ ==============================

    private static void journal(String who, String text, boolean mine){
        if(!mine) shouldSuppress(text);
        Entry en = new Entry();
        en.time = Time.time;
        en.who = who;
        en.text = text;
        en.mine = mine;
        history.add(en);
        while(history.size > 200) history.remove(0);
    }

    /** Запись журнала чата (с цветным ником — «ник копируется с цветом»). */
    public static class Entry{
        public float time;
        public String who = "";
        public String text = "";
        public boolean mine;

        /** Полный текст строки, как её рисует игра: «Ник: сообщение» с цветами. */
        public String full(){
            String nick = who == null || who.isEmpty() ? "" : who + "[white]: ";
            return nick + (text == null ? "" : text);
        }
    }

    public static final Seq<Entry> history = new Seq<>();

    /**
     * Копирование ника «со всеми цветами и без лимита на количество тегов [ в имени».
     * Ваниль обрезает имя по первому '[' — здесь этого нет.
     */
    public static String nickWithColors(@Nullable Player p){
        if(p == null) return "";
        try{
            String col = p.coloredName();
            if(col != null && !col.isEmpty()) return col;
        }catch(Throwable ignored){}
        return "[#" + p.team().color.toString() + "]" + p.name() + "[]";
    }

    /** Скопировать строку чата в буфер обмена (со всей цветовой разметкой). */
    public static boolean copyLine(String text){
        if(text == null || text.isEmpty()) return false;
        try{
            Core.app.setClipboardText(text);
            MKUKICMod.toast("[accent]Onix[]: скопировано в буфер");
            return true;
        }catch(Throwable t){
            return false;
        }
    }

    /** Скопировать ник игрока вместе с цветом. */
    public static boolean copyNick(@Nullable Player p){
        String s = nickWithColors(p);
        if(s.isEmpty()) return false;
        Core.app.setClipboardText(s);
        MKUKICMod.toast("[accent]Onix[]: ник скопирован с цветом");
        return true;
    }

    // ============================== ГОЛОСОВАНИЕ ==============================

    /**
     * «Распознавание подсказки /vote не зависит от языка сервера»: ищем сам маркер
     * голосования, а не перевод фразы.
     */
    public static boolean isVoteMessage(String text){
        if(text == null) return false;
        String t = Strings.stripColors(text).toLowerCase();
        if(t.contains("/vote")) return true;
        if(t.contains("vote y") && t.contains("vote n")) return true;
        if(t.contains("rtv")) return true;
        // серверные плагины голосования: «голосование», «проголосуйте», kick/ban vote
        if(t.contains("голосовани") || t.contains("проголос")) return true;
        return (t.contains("vote") && (t.contains("yes") || t.contains("no") || t.contains("y/n")));
    }

    private static void checkVote(String text){
        MKUKICSettings s = MKUKICSettings.s();
        if(!s.onixEnabled || !s.onixChatVote) return;
        if(isVoteMessage(text)){
            voteActive = true;
            voted = false;
            voteText = Strings.stripColors(text);
            voteUntil = Time.time + 60f * 45f;
        }
    }

    /** Отдать голос («после голоса кнопки гаснут вместе с сообщением»). */
    public static void vote(boolean yes){
        if(!voteActive || voted) return;
        send(yes ? "/vote y" : "/vote n");
        voted = true;
        voteActive = false;
        MKUKICMod.toast(yes ? "[accent]Onix[]: голос «за» отправлен" : "[scarlet]Onix[]: голос «против» отправлен");
    }

    public static boolean voteVisible(){
        if(!voteActive) return false;
        if(Time.time > voteUntil){
            voteActive = false;
            return false;
        }
        return true;
    }

    // ============================== АВТООТВЕТЧИК АФК ==============================

    /**
     * «Автоответчик АФК: цветной, включён по умолчанию, триггер — ник подстрокой
     * в любом месте».
     */
    public static void checkAfkReply(@Nullable Player from, String text){
        MKUKICSettings s = MKUKICSettings.s();
        if(!s.onixEnabled || !s.onixAfkReply) return;
        if(from == null || from == player) return;
        String plain = Strings.stripColors(text == null ? "" : text).toLowerCase();
        if(plain.isEmpty()) return;

        boolean hit;
        if(s.onixAfkOnlyNick){
            String me = player == null ? "" : Strings.stripColors(player.name()).toLowerCase();
            hit = !me.isEmpty() && plain.contains(me);
        }else{
            hit = plain.contains("?") || plain.contains("где") || plain.contains("ты");
        }
        if(!hit) return;

        Float last = replied.get(from.name(), (Float)null);
        if(last != null && Time.time - last < s.onixAfkCooldown * 60f) return;
        replied.put(from.name(), Time.time);
        send(s.onixAfkText);
    }

    // ============================== ОТПРАВКА И ПОДСТАНОВКИ ==============================

    /** Подстановки Onix: %s — shrug, %c — координаты курсора, %h — координаты юнита. */
    public static String applyReplacers(String text){
        if(text == null) return null;
        MKUKICSettings s = MKUKICSettings.s();
        if(!s.onixEnabled || !s.onixTextReplacer) return text;
        Vec2 c = Core.input == null ? null : Core.input.mouseWorld();
        String cs = c == null ? "0,0" : (int)(c.x / tilesize) + "," + (int)(c.y / tilesize);
        String hs = player == null ? "0,0" : (int)(player.x() / tilesize) + "," + (int)(player.y() / tilesize);
        return text.replace("%s", "¯\\_(ツ)_/¯").replace("%c", cs).replace("%h", hs);
    }

    /** Отправить сообщение в чат (в одиночной игре — в локальный лог чата). */
    public static void send(String text){
        if(text == null || text.isEmpty()) return;
        text = applyReplacers(text);
        try{
            if(net != null && net.client()){
                Call.sendChatMessage(text);
            }else{
                NetClient.sendMessage(text);
            }
        }catch(Throwable t){
            try{
                NetClient.sendMessage(text);
            }catch(Throwable ignored){
                Log.warn("[MKUKIC Onix] не удалось отправить в чат: " + t);
            }
        }
    }

    /** «Пустое сообщение» ({@code blank}) — символ нулевой ширины. */
    public static void sendBlank(){
        send("\u200b");
    }

    // ============================== АНТИ-СПАМ / СЕРВЕРНЫЕ ОКНА ==============================

    /**
     * Блокировка серверных окон и анти-спам: серверные «окна» в Mindustry приходят
     * как сообщения с маркерами форм (infoToast/окна) — глушим повторяющиеся.
     */
    public static boolean shouldSuppress(String text){
        MKUKICSettings s = MKUKICSettings.s();
        if(!s.onixEnabled || !s.onixBlockServerWindows) return false;
        if(text == null) return false;
        String t = Strings.stripColors(text);
        // спам: одно и то же сообщение чаще 3 раз за 5 секунд
        int n = 0;
        for(int i = history.size - 1; i >= 0 && i > history.size - 30; i--){
            Entry e = history.get(i);
            if(Time.time - e.time > 60f * 5f) break;
            if(Strings.stripColors(e.text == null ? "" : e.text).equals(t)) n++;
        }
        if(n >= 3){
            if(Time.time - lastSpamNotice > 60f * 10f){
                lastSpamNotice = Time.time;
                MKUKICMod.toast("[scarlet]Onix анти-спам[]: «" + (t.length() > 30 ? t.substring(0, 30) + "…" : t) + "» x" + n);
            }
            return true;
        }
        return false;
    }

    // ============================== РАСКЛАДКА СТРОК ЧАТА (для ПКМ-копирования) ==============================

    /**
     * Пересчитывает экранные прямоугольники строк чата, повторяя раскладку ванильного
     * {@code ChatFragment.draw()} (те же поля: offsetx/offsety/fontoffsetx/textspacing,
     * тот же шрифт и ширина переноса). Нужно, чтобы ПКМ по конкретной строке копировал
     * именно её текст со всеми цветами.
     */
    public static void layoutRows(){
        rows.clear();
        MKUKICSettings s = MKUKICSettings.s();
        if(!s.onixEnabled || !s.onixChatCopy || !reflectOk) return;
        if(ui == null || ui.chatfrag == null || state == null || state.isMenu()) return;
        if(ui.chatfrag.shown()) return; // открыт ввод — строки не кликаем

        try{
            ChatFragment cf = ui.chatfrag;
            Object om = fMessages.get(cf);
            if(!(om instanceof Seq)) return;
            Seq<?> messages = (Seq<?>)om;
            if(messages.size == 0) return;

            float offsetx = fOffsetX.getFloat(cf);
            float offsety = fOffsetY.getFloat(cf);
            float fontoffsetx = fFontOffsetX.getFloat(cf);
            float textspacing = fTextSpacing.getFloat(cf);
            int scrollPos = fScrollPos.getInt(cf);
            Font font = (Font)fFont.get(cf);
            if(font == null) font = Fonts.def;

            float w = Math.min(Core.graphics.getWidth() * 1.5f, Scl.scl(700f));
            float y = offsety + cf.getMarginBottom() + Core.scene.marginBottom;
            GlyphLayout layout = new GlyphLayout();

            int from = messages.size - 1 - scrollPos;
            for(int i = from; i >= 0 && i > from - 12; i--){
                Object o = messages.get(i);
                if(o == null) continue;
                String msg = String.valueOf(o);
                layout.setText(font, msg, Color.white, w, Align.topLeft, true);
                float h = layout.height;
                float x = fontoffsetx + offsetx;
                Row r = new Row();
                r.text = msg;
                r.index = i;
                // координаты сцены (y вверх) -> экранные (y вниз)
                r.x = x;
                r.y = Core.graphics.getHeight() - (y + h);
                r.w = w;
                r.h = h + textspacing;
                rows.add(r);
                y += h + textspacing;
            }
            lastSize = messages.size;
        }catch(Throwable t){
            reflectOk = false;
            Log.warn("[MKUKIC Onix] раскладка чата недоступна: " + t);
        }
    }

    /**
     * Обработать клик по экрану: если это ПКМ по строке чата — скопировать её.
     * Вызывается из {@link Onix} (каждый кадр) — сами опрашиваем ввод, чтобы не
     * перехватывать клики игры.
     */
    public static boolean handleCopyClick(){
        MKUKICSettings s = MKUKICSettings.s();
        if(!s.onixEnabled || !s.onixChatCopy || rows.isEmpty()) return false;
        if(Core.input == null) return false;
        if(!Core.input.keyTap(KeyCode.mouseRight)) return false;
        if(Core.scene != null && Core.scene.hasMouse()) return false; // клик по UI-элементу

        float mx = Core.input.mouseX(), my = Core.graphics.getHeight() - Core.input.mouseY();
        for(Row r : rows){
            if(mx >= r.x && mx <= r.x + r.w && my >= r.y && my <= r.y + r.h){
                copyLine(r.text);
                return true;
            }
        }
        return false;
    }

    /**
     * {chat_autocomplete} из Onix: подстановка «:block / :unit / :liquid / :item» и «@playername»
     * в открытом поле чата. Работает по текущему тексту ванильного поля ввода.
     */
    public static void autocompleteChat(){
        if(ui == null || ui.chatfrag == null || !ui.chatfrag.shown()) return;
        try{
            TextField f = findChatField(ui.chatfrag);
            if(f == null) return;
            String txt = f.getText() == null ? "" : f.getText();
            if(txt.isEmpty()) return;
            int sp = txt.lastIndexOf(' ');
            String head = sp < 0 ? "" : txt.substring(0, sp + 1);
            String part = sp < 0 ? txt : txt.substring(sp + 1);
            if(part.length() < 1) return;

            String best = null;
            if(part.startsWith("@")){
                String q = part.substring(1).toLowerCase();
                for(Player p : Groups.player){
                    if(p == null || p.name() == null) continue;
                    if(p.name().toLowerCase().startsWith(q)){ best = "@" + p.name(); break; }
                }
            }else if(part.startsWith(":")){
                String q = part.substring(1).toLowerCase();
                best = findContent(q);
            }
            if(best != null && !best.equals(part)){
                f.setText(head + best);
                f.setCursorPosition(f.getText().length());
            }
        }catch(Throwable t){
            Log.warn("[MKUKIC Onix] autocomplete: " + t);
        }
    }

    private static @Nullable String findContent(String q){
        if(q.isEmpty()) return null;
        for(mindustry.world.Block b : content.blocks()){
            if(b != null && !b.isAir() && b.name.toLowerCase().startsWith(q)) return ":" + b.name;
        }
        for(UnitType u : content.units()){
            if(u != null && u.name.toLowerCase().startsWith(q)) return ":" + u.name;
        }
        for(Item i : content.items()){
            if(i != null && i.name.toLowerCase().startsWith(q)) return ":" + i.name;
        }
        for(Liquid l : content.liquids()){
            if(l != null && l.name.toLowerCase().startsWith(q)) return ":" + l.name;
        }
        return null;
    }

    /** Ищем поле ввода чата в дереве ChatFragment (оно приватное). */
    private static @Nullable TextField findChatField(Element root){
        if(root instanceof TextField tf) return tf;
        if(root instanceof arc.scene.Group g){
            for(Element ch : g.getChildren()){
                TextField f = findChatField(ch);
                if(f != null) return f;
            }
        }
        return null;
    }

    public static void tick(){
        if(headless) return;
        handleCopyClick();
        if(voteActive && Time.time > voteUntil) voteActive = false;
        // чистка старых ответов автоответчика
        if(Mathf.chance(0.002f)){
            Seq<String> keys = new Seq<>();
            for(String k : replied.keys()) keys.add(k);
            for(String k : keys){
                if(Time.time - replied.get(k, 0f) > 60f * 60f) replied.remove(k);
            }
        }
    }

    public static void reset(){
        voteActive = voted = false;
        voteText = "";
        rows.clear();
        replied.clear();
    }

    // ============================== ЭКРАННЫЙ СЛОЙ ==============================

    /**
     * Кнопки голосования «отдельным блоком под сообщением»: рисуем их над полем чата,
     * на своей строке, чтобы не накрывать текст сообщения.
     */
    public static void drawScreen(){
        if(headless || state == null || state.isMenu()) return;
        MKUKICSettings s = MKUKICSettings.s();
        if(!s.onixEnabled || !s.onixChatVote || !voteVisible()) return;
        if(ui != null && ui.chatfrag != null && ui.chatfrag.shown()) return;

        float bw = 96f, bh = 30f, pad = 6f;
        float x = 20f;
        float y = 150f; // над строками чата, отдельной строкой

        Font font = Fonts.def;
        // подпись
        font.getData().setScale(0.9f);
        font.setColor(Color.lightGray);
        GlyphLayout gl = new GlyphLayout(font, "[#8b94a5]Голосование: " + Strings.stripColors(voteText).substring(0, Math.min(60, Strings.stripColors(voteText).length())));
        font.draw(gl, x, y + bh + 16f);
        font.getData().setScale(1f);

        drawButton(x, y, bw, bh, "vote y", Color.valueOf("7dff8a"), () -> vote(true));
        drawButton(x + bw + pad, y, bw, bh, "vote n", Color.valueOf("ff6b6b"), () -> vote(false));

        if(voted){
            font.setColor(Color.gray);
            GlyphLayout g2 = new GlyphLayout(font, "[gray]голос отправлен");
            font.draw(g2, x + (bw + pad) * 2f + 8f, y + bh * 0.7f);
        }
        Draw.reset();
    }

    /** Кнопка голосования с обработкой клика (рисуем сами — ванильных элементов не добавляем). */
    private static void drawButton(float x, float y, float w, float h, String text, Color c, Runnable action){
        float sy = Core.graphics.getHeight() - y - h; // в экранные координаты для проверки мыши
        boolean hover = Core.input != null
            && Core.input.mouseX() >= x && Core.input.mouseX() <= x + w
            && Core.input.mouseY() >= sy && Core.input.mouseY() <= sy + h;

        if(hover) Draw.color(c.r, c.g, c.b, 0.35f);
        else Draw.color(0.06f, 0.075f, 0.11f, 0.85f);
        Fill.rect(x + w / 2f, y + h / 2f, w, h);
        Draw.color(c, hover ? 1f : 0.75f);
        Lines.stroke(1.4f);
        Lines.rect(x, y, w, h);

        Font font = Fonts.def;
        font.setColor(c);
        font.getData().setScale(1f);
        GlyphLayout gl = new GlyphLayout(font, text);
        font.draw(gl, x + (w - gl.width) / 2f, y + h / 2f + gl.height / 2f - 2f);

        if(hover && voted) return;
        if(hover && Core.input != null && Core.input.keyTap(KeyCode.mouseLeft)){
            action.run();
        }
        Draw.reset();
    }

    public static String voteText(){
        return voteText;
    }
}
