package mkukic.client.onix;

import arc.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.math.*;
import arc.math.geom.*;
import arc.scene.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.type.*;
import mindustry.ui.Fonts;
import mindustry.world.Tile;
import mindustry.world.blocks.storage.CoreBlock;
import mindustry.world.blocks.storage.StorageBlock;
import mkukic.*;
import mkukic.client.EspRenderer;

import java.lang.reflect.Field;

import static mindustry.Vars.*;

/**
 * <b>Определение клиентов: Onix / Onix Lite / Foo's Client</b> — порт функции Onix Lite 3.4.6
 * с разделением по семействам клиентов (v1.19.2).
 *
 * <p>Что показывает MKUKIC:
 * <ul>
 *  <li><b>значок над головой</b> — только у тех, у кого клиент найден (обычные игроки и
 *      «подозрительные» без явных признаков не помечаются; подозрительных можно включить отдельно);</li>
 *  <li><b>значок в списке игроков (Tab)</b> — как в Onix: бейдж семейства стоит слева от ника;</li>
 *  <li><b>семейство клиента</b> — {@code Onix} / {@code Lite} / {@code Foo} / {@code C}
 *      (клиент есть, семейство не определено) / {@code MK} (свой MKUKIC).</li>
 * </ul>
 *
 * <p><b>Честное ограничение.</b> Настоящий Onix обменивается «мышиными маячками» — скрытым
 * протоколом, зашитым в его зашифрованный клиентский код ({@code data.bin}, заголовок
 * ONIX\x01). Мод принять их не может, поэтому здесь работают три источника признаков:
 * <ol>
 *  <li><b>Тег в нике</b> (надёжно). Onix сам пишет в ник значок {@code シ}: у Onix Lite тег
 *      вида {@code <Lシ>} (слева буква L), у полной версии — {@code <シ>}. Это единственный
 *      способ из мода отличить Lite от полной версии — так же, как это делает сам Onix.</li>
 *  <li><b>Поведение</b> (эвристика). Рывки позиции, идеально прямой ход без дрожания курса,
 *      мгновенные развороты, длинная очередь планов, непрерывная копка и — главное для
 *      определения семейства — <b>циклы авто-филла</b>: юнит забирает предмет из ядра/склада
 *      и сдаёт его в НЕ-ядерную постройку, снова и снова. Авто-филл — фирменная функция Onix,
 *      в Foo's Client её нет.</li>
 *  <li><b>Ручная пометка</b> (точно). В панели Onix → «Определение клиентов» игрока можно
 *      отметить как Onix / Onix Lite / Foo's вручную — это перекрывает эвристику.</li>
 * </ol>
 *
 * <p>Если клиент обнаружен, но семейство не определяется, MKUKIC рисует {@code C} и честно
 * пишет «семейство не определено»; при слабом перевесе одного семейства добавляется знак
 * вопроса ({@code Foo?}, {@code Onix?}) — это догадка, а не факт. Никаких символов в чат
 * детект не отправляет — как и Onix.
 */
public class OnixClientDetect{

    // ============================== СЕМЕЙСТВА ==============================

    /** Обычный игрок — клиента не видно. */
    public static final int FAM_NONE = 0;
    /** Клиент есть, семейство не определено. */
    public static final int FAM_CLIENT = 1;
    /** Foo's Client (BalaM314) — прародитель Onix. */
    public static final int FAM_FOO = 2;
    /** Onix, полная версия (тег {@code <シ>}). */
    public static final int FAM_ONIX = 3;
    /** Onix Lite (тег {@code <Lシ>}). */
    public static final int FAM_ONIX_LITE = 4;
    /** Свой MKUKIC Client. */
    public static final int FAM_MKUKIC = 5;

    public static final Color colMkukic = Color.valueOf("ffb454");
    public static final Color colOnix = Color.valueOf("c9a6ff");
    public static final Color colLite = Color.valueOf("9d7bff");
    public static final Color colFoo = Color.valueOf("6fd6ff");
    public static final Color colClient = Color.valueOf("ffd166");
    public static final Color colSuspicious = Color.valueOf("9aa4b2");

    /** Значок Onix в нике: катакана СИ (U+30B7). */
    private static final char ONIX_GLYPH = '\u30b7';

    // ============================== ПОДСКАЗКА ИЗ НИКА ==============================

    /** Результат разбора ника: семейство + уверенность. Чистая функция {@link #nickHint(String)}. */
    public static class NickHint{
        public int family = FAM_NONE;
        /** 0 — ничего, 1 — упоминание словом, 2 — фирменный тег клиента. */
        public int confidence;
        public String why = "";

        public boolean isClient(){
            return family != FAM_NONE;
        }

        public String describe(){
            return family == FAM_NONE ? "нет тега клиента в нике" : familyName(family) + " (" + why + ")";
        }
    }

    private static final NickHint noHint = new NickHint();

    /**
     * Разбор ника на предмет тегов клиента. Чистая функция — покрыта тестами.
     * <ul>
     *  <li>{@code シ} в нике → Onix; если рядом слева {@code L} (или в нике есть «lite») → Onix Lite.
     *      Уверенность 2: так помечает себя сам клиент.</li>
     *  <li>слово «onix» → Onix (Lite при «lite»/«&lt;l»), уверенность 1;</li>
     *  <li>слово «foo»/«foos»/«foo's» → Foo's Client, уверенность 1;</li>
     *  <li>иначе — ничего.</li>
     * </ul>
     */
    public static NickHint nickHint(String name){
        if(name == null || name.isEmpty()) return noHint;
        String plain = Strings.stripColors(name);
        String low = plain.toLowerCase();

        NickHint h = new NickHint();
        int g = plain.indexOf(ONIX_GLYPH);
        if(g >= 0){
            // «У Onix Lite в теге слева стоит буква L: <Lシ>, у полной версии — <シ>»
            String before = low.substring(Math.max(0, g - 6), g);
            boolean lite = before.contains("l") || low.contains("lite");
            h.family = lite ? FAM_ONIX_LITE : FAM_ONIX;
            h.confidence = 2;
            h.why = "тег «" + plain.substring(Math.max(0, g - 3), Math.min(plain.length(), g + 2)) + "» в нике";
            return h;
        }
        // «onix» ищем подстрокой: токен характерный, а в нике он часто слит с другим словом
        // (OnixPro, XOnixX). «foo» — только как отдельное слово, иначе ловились бы «food»/«fool».
        if(low.contains("onix")){
            boolean lite = hasWord(low, "lite") || low.contains("<l");
            h.family = lite ? FAM_ONIX_LITE : FAM_ONIX;
            h.confidence = 1;
            h.why = "«onix" + (lite ? " lite" : "") + "» в нике";
            return h;
        }
        if(hasWord(low, "foo") || hasWord(low, "foos") || low.contains("foo's") || low.contains("fooclient")){
            h.family = FAM_FOO;
            h.confidence = 1;
            h.why = "«foo» в нике";
            return h;
        }
        return h;
    }

    /** Слово целиком (не кусок другого слова): «foo» в «food» не считается. */
    private static boolean hasWord(String low, String word){
        int from = 0;
        while(true){
            int i = low.indexOf(word, from);
            if(i < 0) return false;
            boolean left = i == 0 || !isLetter(low.charAt(i - 1));
            int end = i + word.length();
            boolean right = end >= low.length() || !isLetter(low.charAt(end));
            if(left && right) return true;
            from = i + 1;
        }
    }

    private static boolean isLetter(char c){
        return (c >= 'a' && c <= 'z') || (c >= 'а' && c <= 'я') || c == 'ё' || Character.isLetter(c);
    }

    public static String familyName(int family){
        switch(family){
            case FAM_MKUKIC: return "MKUKIC";
            case FAM_ONIX: return "Onix";
            case FAM_ONIX_LITE: return "Onix Lite";
            case FAM_FOO: return "Foo's Client";
            case FAM_CLIENT: return "клиент";
            default: return "игрок";
        }
    }

    public static Color familyColor(int family){
        switch(family){
            case FAM_MKUKIC: return colMkukic;
            case FAM_ONIX: return colOnix;
            case FAM_ONIX_LITE: return colLite;
            case FAM_FOO: return colFoo;
            case FAM_CLIENT: return colClient;
            default: return colSuspicious;
        }
    }

    /**
     * Короткий текст бейджа: {@code MK}, {@code Onix}, {@code Lite}, {@code Foo}, {@code C}.
     * При низкой уверенности (догадка по поведению) добавляется «?». Чистая функция.
     */
    public static String iconText(int family, int confidence, boolean withConfidence){
        String base;
        switch(family){
            case FAM_MKUKIC: base = "MK"; break;
            case FAM_ONIX: base = "Onix"; break;
            case FAM_ONIX_LITE: base = "Lite"; break;
            case FAM_FOO: base = "Foo"; break;
            case FAM_CLIENT: base = "C"; break;
            default: return "";
        }
        return withConfidence && confidence <= 0 ? base + "?" : base;
    }

    // ============================== ВЕРДИКТ ==============================

    /** Вердикт по игроку: оценка, признаки, семейство клиента. */
    public static class Verdict{
        public int id;
        public String name = "";
        /** 0 = игрок, 1 = подозрительный, 2 = клиент, 3 = MKUKIC (свой). */
        public int level;
        public float score;
        public int family = FAM_NONE;
        /** 0 = догадка, 1 = признак семейства, 2 = тег ника / ручная пометка / свой клиент. */
        public int confidence;
        /** Вклад признаков в каждое семейство — для выбора Onix против Foo. */
        public float onixScore, fooScore;
        public final Seq<String> reasons = new Seq<>();
        /** Сколько очков дал каждый признак (защита от накрутки одним сигналом). */
        public final ObjectMap<String, Float> contributed = new ObjectMap<>();

        // состояние анализа
        public float lastX = Float.NaN, lastY, lastAngle = Float.NaN;
        public int straightRun, snapCount, turnCount, planRun, mineRun;
        public int depositCount, pickupCount;
        public Item lastStackItem;
        public int lastStack = -1;
        public float lastStackAt = -999f;
        public float windowEnd;
        /** Семейство назначено вручную (перекрывает эвристику). */
        public boolean manual;
        private String nickChecked;
        private NickHint nick = noHint;

        public NickHint nick(){
            if(nickChecked == null || !nickChecked.equals(name)){
                nickChecked = name;
                nick = nickHint(name);
            }
            return nick;
        }

        /** Начислить очки: вклад признака ограничен сверху, чтобы один сигнал не решал всё. */
        public void addScore(float amount, String reason, float onixWeight, float fooWeight, MKUKICSettings s){
            if(manual) return;
            float mult = s.onixDetectSensitivity == 0 ? 0.6f : s.onixDetectSensitivity == 2 ? 1.45f : 1f;
            float cap = 26f;
            Float had = contributed.get(reason, (Float)null);
            float before = had == null ? 0f : had;
            float add = Math.min(amount * mult, cap - before);
            if(add <= 0.001f) return;
            contributed.put(reason, before + add);
            score = Mathf.clamp(score + add, 0f, 100f);
            onixScore += add * onixWeight;
            fooScore += add * fooWeight;
            if(!reasons.contains(reason, false)) reasons.add(reason);
            relevel(s);
        }

        /** Уровень по сумме очков и числу РАЗНЫХ признаков (один сигнал клиентом не делает). */
        public void relevel(MKUKICSettings s){
            int lvl = (score >= 70f && reasons.size >= 3) ? 2 : (score >= 40f ? 1 : 0);
            NickHint h = nick();
            if(h.confidence >= 2) lvl = Math.max(lvl, 2);        // фирменный тег = клиент
            else if(h.confidence == 1) lvl = Math.max(lvl, 1);  // упоминание словом = повод присмотреться
            if(manual) lvl = Math.max(lvl, family == FAM_NONE ? 0 : 2);
            if(s != null && s.onixDetectSelf && player != null && id == player.id()) lvl = 3;
            level = lvl;
        }

        /** Выбрать семейство: ручная пометка → тег ника → поведение → «не определено». */
        public void classify(MKUKICSettings s){
            if(s != null && s.onixDetectSelf && player != null && id == player.id()){
                family = FAM_MKUKIC;
                confidence = 2;
                return;
            }
            if(manual){
                confidence = 2;
                return;
            }
            NickHint h = nick();
            if(h.confidence >= 2){
                family = h.family;
                confidence = 2;
                return;
            }
            if(level < 2){
                // клиента не видно: семейство не приписываем (иначе значки полезли бы на всех)
                if(h.confidence == 1){
                    family = h.family;
                    confidence = 1;
                }else{
                    family = FAM_NONE;
                    confidence = 0;
                }
                return;
            }
            // клиент есть — определяем семейство по перевесу признаков
            if(onixScore >= 12f && onixScore > fooScore * 1.5f){
                family = FAM_ONIX;
                confidence = 1;
            }else if(fooScore >= 12f && fooScore > onixScore * 1.5f){
                family = FAM_FOO;
                confidence = 1;
            }else if(h.confidence == 1){
                family = h.family;
                confidence = 1;
            }else if(onixScore > fooScore + 0.01f){
                family = FAM_ONIX;
                confidence = 0;
            }else if(fooScore > onixScore + 0.01f){
                family = FAM_FOO;
                confidence = 0;
            }else{
                family = FAM_CLIENT;
                confidence = 0;
            }
        }

        public String iconText(){
            // статический метод внешнего класса скрыт одноимённым методом Verdict — qualificруем
            return OnixClientDetect.iconText(family, confidence, MKUKICSettings.s().onixDetectConfidence);
        }

        public Color color(){
            return familyColor(level == 1 && family == FAM_NONE ? FAM_NONE : family);
        }

        /** Тег для ника (чат/списки): {@code <MK>}, {@code <Onix>}, {@code <Lite>}, {@code <Foo>}, {@code <C>}. */
        public String tag(){
            if(level == 0) return "";
            String t = iconText();
            if(t.isEmpty()) return level == 1 ? "[#" + colSuspicious.toString() + "]<m>[]" : "";
            return "[#" + color().toString() + "]<" + t + ">[]";
        }

        public String describe(){
            String who = level == 3 ? "MKUKIC (вы)"
                : level >= 2 ? (family == FAM_CLIENT ? "КЛИЕНТ (семейство не определено)" : familyName(family).toUpperCase())
                : level == 1 ? "подозрительно" : "игрок";
            StringBuilder sb = new StringBuilder(name).append(" — ").append(who);
            if(level >= 2 && confidence == 0) sb.append('?');
            sb.append("  ").append((int)score).append("/100");
            NickHint h = nick();
            if(h.confidence > 0) sb.append("  [lightgray]").append(h.why).append("[]");
            else if(level >= 2) sb.append("  [lightgray]onix:").append((int)onixScore)
                .append(" foo:").append((int)fooScore).append("[]");
            if(!reasons.isEmpty()) sb.append(": ").append(String.join(", ", reasons));
            return sb.toString();
        }

        public void clearAnalysis(){
            score = 0f;
            onixScore = 0f;
            fooScore = 0f;
            reasons.clear();
            contributed.clear();
            straightRun = snapCount = turnCount = planRun = mineRun = 0;
            depositCount = pickupCount = 0;
            lastX = Float.NaN;
            lastAngle = Float.NaN;
            lastStack = -1;
            lastStackItem = null;
            if(!manual && !(player != null && id == player.id())){
                level = 0;
                family = FAM_NONE;
                confidence = 0;
            }
        }
    }

    // ============================== РЕЕСТР ==============================

    public static final ObjectMap<Integer, Verdict> verdicts = new ObjectMap<>();
    public static final Seq<Verdict> flagged = new Seq<>();
    /** Ручные пометки семейства: id игрока → FAM_*. */
    public static final ObjectMap<Integer, Integer> manualFamily = new ObjectMap<>();

    private static boolean inited, alerted;
    private static float lastAlertAt;
    private static final Vec2 tmp = new Vec2();
    private static Field fListVisible;
    private static boolean listReflectTried;

    public static void init(){
        if(inited) return;
        inited = true;

        // «короткое окно после захода на сервер»
        Events.on(WorldLoadEvent.class, e -> {
            reset();
            if(player != null) openWindow(player, true);
        });
        // «…и при заходе новых игроков»
        Events.on(PlayerJoin.class, e -> {
            if(e.player != null) openWindow(e.player, true);
        });
        Events.on(PlayerLeave.class, e -> {
            if(e.player != null) verdicts.remove(e.player.id());
        });
        // чужие сообщения не трогаем; следим только за ником и поведением
    }

    public static void reset(){
        verdicts.clear();
        flagged.clear();
        alerted = false;
    }

    /** Открыть окно сканирования для игрока. */
    public static void openWindow(Player p, boolean full){
        if(p == null) return;
        MKUKICSettings s = MKUKICSettings.s();
        Verdict v = verdicts.get(p.id(), (Verdict)null);
        if(v == null){
            v = new Verdict();
            v.id = p.id();
            verdicts.put(p.id(), v);
        }
        v.name = p.name();
        v.windowEnd = Time.time + (full ? s.onixDetectWindow * 60f : s.onixDetectWindow * 30f);
        v.lastX = Float.NaN;
        v.lastStack = -1;
        v.lastStackItem = null;

        Integer man = manualFamily.get(p.id(), (Integer)null);
        if(man != null){
            v.manual = true;
            v.family = man;
        }
        if(s.onixDetectSelf && p == player){
            v.manual = false;
            v.level = 3;
            v.score = 100f;
            v.family = FAM_MKUKIC;
            v.confidence = 2;
            v.reasons.clear();
            v.reasons.add("MKUKIC Client");
        }else{
            v.classify(s);
            v.relevel(s);
        }
    }

    public static @Nullable Verdict get(Player p){
        return p == null ? null : verdicts.get(p.id(), (Verdict)null);
    }

    public static @Nullable Verdict get(int id){
        return verdicts.get(id, (Verdict)null);
    }

    /** Тег для ника (в табе, чате и над головой). */
    public static String tagFor(Player p){
        MKUKICSettings s = MKUKICSettings.s();
        if(!s.onixDetect || !s.onixDetectTags) return "";
        Verdict v = get(p);
        if(v == null || !showFor(v, s)) return "";
        return v.tag();
    }

    /** Рисовать ли значок: только клиенты (и свой MKUKIC); «подозрительные» — по настройке. */
    public static boolean showFor(@Nullable Verdict v, MKUKICSettings s){
        if(v == null) return false;
        if(v.level >= 3) return s.onixDetectSelf;
        if(v.level == 2) return true;
        return v.level == 1 && s.onixDetectSuspicious;
    }

    // ============================== АНАЛИЗ ==============================

    public static void tick(){
        if(player == null || state == null || state.isMenu()) return;
        MKUKICSettings s = MKUKICSettings.s();
        if(!s.onixDetect) return;

        flagged.clear();
        for(Player p : Groups.player){
            if(p == null) continue;
            Verdict v = verdicts.get(p.id(), (Verdict)null);
            if(v == null){
                openWindow(p, false);
                v = verdicts.get(p.id(), (Verdict)null);
                if(v == null) continue;
            }
            if(!p.name().equals(v.name)){
                v.name = p.name();     // ник сменился — пересмотреть тег
                v.classify(s);
                v.relevel(s);
            }
            if(p.unit() != null && !p.unit().dead()){
                boolean inWindow = !s.onixDetectJoinWindow || Time.time < v.windowEnd;
                if(inWindow && p != player) analyze(p, v, s);
            }
            v.classify(s);
            if(v.level >= 1) flagged.add(v);
        }
        flagged.sort((a, b) -> b.level != a.level ? b.level - a.level : Float.compare(b.score, a.score));

        // «Оповещение о найденных в игре клиентах Onix»
        if(s.onixDetectAlert){
            int clients = 0;
            for(Verdict v : flagged) if(v.level >= 2 && v.id != player.id) clients++;
            if(clients > 0 && (!alerted || Time.time - lastAlertAt > 60f * 60f)){
                alerted = true;
                lastAlertAt = Time.time;
                StringBuilder sb = new StringBuilder("[#" + Onix.theme.toString() + "]Onix-детект[]: найдено клиентов — " + clients + "\n");
                int n = 0;
                for(Verdict v : flagged){
                    if(v.level < 2 || v.id == player.id) continue;
                    sb.append("  [lightgray]• []").append(v.describe()).append('\n');
                    if(++n >= 6) break;
                }
                MKUKICMod.toast(sb.toString());
            }
        }
    }

    private static void analyze(Player p, Verdict v, MKUKICSettings s){
        Unit u = p.unit();
        if(u == null) return;
        float x = u.x, y = u.y;

        trackStack(u, v, s);
        trackPlans(u, v, s);

        if(Float.isNaN(v.lastX)){
            v.lastX = x;
            v.lastY = y;
            return;
        }

        float dx = x - v.lastX, dy = y - v.lastY;
        float dist = Mathf.len(dx, dy);
        float speed = Math.max(1f, u.type() == null ? 3f : u.type().speed);
        float ang = Mathf.angle(dx, dy);
        boolean working = u.mineTile() != null || (u.canBuild() && u.plans().size > 0);

        // 1) рывок позиции: прошли больше, чем юнит физически может за кадр.
        // В мультиплеере позиции чужих юнитов приходят снапшотами, поэтому одиночный
        // рывок на лаге ничего не значит — считаем только серию.
        if(dist > speed * 3.2f + 12f){
            v.snapCount++;
            if(v.snapCount == 3) v.addScore(13f, "рывки позиции", 0.4f, 1.2f, s);
            else if(v.snapCount > 3 && v.snapCount % 6 == 0) v.addScore(6f, "рывки позиции", 0.4f, 1.2f, s);
        }

        if(dist > 0.75f){
            float dev = Float.isNaN(v.lastAngle) ? 90f : Angles.angleDist(ang, v.lastAngle);
            if(dev < 0.18f){
                // 2) идеально прямой ход: BuildPath/MinePath ведут юнита как по линейке.
                // Считаем только длинную серию и только когда юнит реально работает,
                // иначе любой игрок, ведущий юнита к ядру, попал бы в подозреваемые.
                v.straightRun++;
                if(working && v.straightRun == 240) v.addScore(9f, "идеально прямой ход", 0.5f, 1.4f, s);
                if(working && v.straightRun == 600) v.addScore(12f, "идеально прямой ход", 0.5f, 1.4f, s);
            }else{
                v.straightRun = 0;
                // 3) мгновенный разворот на большом ходу — так водит автоматика
                if(dev > 145f && dist > speed * 0.6f && working){
                    v.turnCount++;
                    if(v.turnCount % 10 == 0) v.addScore(6f, "мгновенные развороты", 0.5f, 1.2f, s);
                }
            }
            v.lastAngle = ang;
        }else{
            v.straightRun = 0;
        }

        // 4) непрерывная копка без пауз и без смены цели (automine)
        if(u.mineTile() != null){
            v.mineRun++;
            if(v.mineRun == 60 * 60) v.addScore(9f, "непрерывная копка 60с", 0.8f, 1f, s);
            if(v.mineRun == 60 * 180) v.addScore(10f, "копка 3 минуты без пауз", 0.8f, 1f, s);
        }else{
            v.mineRun = 0;
        }

        v.lastX = x;
        v.lastY = y;
    }

    /**
     * Циклы авто-филла: юнит взял предмет в ядре/складе и сдал его в НЕ-ядерную постройку.
     * Авто-филл — фирменная функция Onix (в Foo's Client её нет), поэтому это самый
     * весомый признак семейства Onix. Обычная копка сюда не попадает: она сдаёт руду в ядро.
     */
    private static void trackStack(Unit u, Verdict v, MKUKICSettings s){
        Item carried = u.stack == null ? null : u.stack.item;
        int amount = u.stack == null ? 0 : u.stack.amount;
        if(v.lastStack < 0 || carried != v.lastStackItem){
            v.lastStack = amount;
            v.lastStackItem = carried;
            return;
        }
        int delta = amount - v.lastStack;
        v.lastStack = amount;
        if(Math.abs(delta) < 5) return;
        if(Time.time - v.lastStackAt < 20f) return;

        Building b = nearBuild(u);
        if(b == null || b.items == null) return;
        v.lastStackAt = Time.time;
        if(delta < 0 && b.block.hasItems && !(b.block instanceof CoreBlock)){
            v.depositCount++;
            if(v.depositCount == 3) v.addScore(13f, "развоз ресурсов по постройкам", 3f, 0f, s);
            else if(v.depositCount == 8) v.addScore(13f, "развоз ресурсов по постройкам", 3f, 0f, s);
        }else if(delta > 0 && b.block instanceof StorageBlock){
            v.pickupCount++;
        }
    }

    /** Длинная очередь планов: так работают autobuilder/auto_build по схеме. */
    private static void trackPlans(Unit u, Verdict v, MKUKICSettings s){
        if(!u.canBuild()) return;
        int plans = u.plans().size;
        if(plans >= 8){
            v.planRun++;
            if(v.planRun == 120) v.addScore(9f, plans + " планов в очереди", 1f, 1.3f, s);
            if(v.planRun == 420) v.addScore(11f, "очередь планов держится долго", 1f, 1.3f, s);
        }else{
            v.planRun = 0;
        }
    }

    /** Ближайшая постройка с предметами рядом с юнитом (3×3 вокруг). */
    private static @Nullable Building nearBuild(Unit u){
        if(world == null) return null;
        Tile t = u.tileOn();
        if(t == null) t = world.tileWorld(u.x, u.y);
        if(t == null) return null;
        for(int dx = -1; dx <= 1; dx++){
            for(int dy = -1; dy <= 1; dy++){
                Tile o = world.tile(t.x + dx, t.y + dy);
                if(o != null && o.build != null && o.build.items != null) return o.build;
            }
        }
        return null;
    }

    // ============================== РУЧНАЯ ПОМЕТКА ==============================

    /** Пометить игрока вручную (перекрывает эвристику). {@code FAM_NONE} — снять пометку. */
    public static void markFamily(int playerId, int family){
        if(family == FAM_NONE){
            manualFamily.remove(playerId);
        }else{
            manualFamily.put(playerId, family);
        }
        Verdict v = get(playerId);
        if(v != null){
            MKUKICSettings s = MKUKICSettings.s();
            if(family == FAM_NONE){
                v.manual = false;
                v.clearAnalysis();
                v.classify(s);
                v.relevel(s);
            }else{
                v.manual = true;
                v.family = family;
                v.confidence = 2;
                v.level = Math.max(v.level, 2);
                if(!v.reasons.contains("помечено вручную", false)) v.reasons.add("помечено вручную");
            }
        }
    }

    public static boolean isManual(int playerId){
        return manualFamily.containsKey(playerId);
    }

    // ============================== ОТРИСОВКА В МИРЕ ==============================

    /** Значок семейства клиента над головой — ТОЛЬКО у тех, у кого клиент найден. */
    public static void draw(){
        MKUKICSettings s = MKUKICSettings.s();
        if(!s.onixDetect || !s.onixDetectTags) return;
        if(state == null || state.isMenu()) return;

        for(Player p : Groups.player){
            if(p == null || p.unit() == null || p.unit().dead()) continue;
            Verdict v = get(p);
            if(!showFor(v, s)) continue;
            String txt = v.iconText();
            if(txt.isEmpty()) continue;
            Unit u = p.unit();
            float x = u.x, y = u.y + (u.type() == null ? 12f : u.type().hitSize * 0.75f) + 15f;
            Color c = v.color();
            // подложка, чтобы значок читался на любом фоне
            Draw.color(0f, 0f, 0f, 0.45f);
            Fill.circle(x, y - 2f, 10f);
            Draw.color(c, 0.9f);
            Lines.stroke(1.2f);
            Lines.circle(x, y - 2f, 10f);
            Draw.reset();
            EspRenderer.text(txt, x, y + 2f, c);
        }
    }

    // ============================== ОТРИСОВКА В ТАБЕ (СПИСОК ИГРОКОВ) ==============================

    /**
     * Значки семейства в ванильном списке игроков (удерживайте Tab) — как в Onix:
     * бейдж стоит слева от ника. Список ванильный, поэтому ничего в нём не перестраиваем,
     * а рисуем поверх: находим подпись ника и вычисляем её координаты в сцене.
     */
    public static void drawScreen(){
        MKUKICSettings s = MKUKICSettings.s();
        if(!s.onixDetect || !s.onixDetectTabIcons) return;
        if(state == null || state.isMenu()) return;
        if(!listVisible()) return;

        Table content = ui.listfrag.content;
        Font font = Fonts.def;
        if(font == null || content == null) return;
        boolean ints = font.usesIntegerPositions();
        font.setUseIntegerPositions(false);
        float scale = font.getData().scaleX;
        font.getData().setScale(1f);
        // левая граница списка: бейдж не должен уезжать за обрез панели (её клипает ScrollPane)
        content.localToStageCoordinates(tmpList.set(0f, 0f));
        float minX = tmpList.x + 2f;
        try{
            usedNames.clear();
            for(Player p : Groups.player){
                if(p == null) continue;
                Verdict v = get(p);
                if(!showFor(v, s)) continue;
                String txt = v.iconText();
                if(txt.isEmpty()) continue;

                // однофамильцы: берём свою строку по порядку среди совпавших ников
                int dup = usedNames.get(p.name(), 0);
                usedNames.put(p.name(), dup + 1);
                labelBuf.clear();
                findLabels(content, p.name(), labelBuf);
                if(labelBuf.size == 0) continue;
                Label lab = labelBuf.get(Math.min(dup, labelBuf.size - 1));
                if(lab == null) continue;

                lab.localToStageCoordinates(tmp.set(0f, lab.getHeight() / 2f));
                Color c = v.color();
                GlyphLayout gl = new GlyphLayout(font, txt);
                float bw = gl.width + 12f, bh = Math.max(15f, gl.height + 6f);
                float bx = Math.max(minX, tmp.x - bw - 5f), by = tmp.y - bh / 2f;
                Draw.color(0f, 0f, 0f, 0.62f);
                Fill.rect(bx + bw / 2f, by + bh / 2f, bw, bh);
                Draw.color(c, 0.85f);
                Lines.stroke(1f);
                Lines.rect(bx, by, bw, bh);
                Fill.rect(bx + bw / 2f, by, bw, 1.6f);
                Draw.reset();
                font.setColor(c);
                font.draw(gl, bx + 6f, by + bh - 4f);
            }
        }catch(Throwable t){
            // список игроков ванильный и может меняться: детект не имеет права ронять клиент
        }finally{
            font.getData().setScale(scale);
            font.setColor(Color.white);
            font.setUseIntegerPositions(ints);
            Draw.reset();
        }
    }

    private static final Vec2 tmpList = new Vec2();
    private static final Seq<Label> labelBuf = new Seq<>();
    private static final ObjectMap<String, Integer> usedNames = new ObjectMap<>();

    /** Видим ли сейчас ванильный список игроков (Tab). */
    private static boolean listVisible(){
        if(ui == null || ui.listfrag == null) return false;
        Table content = ui.listfrag.content;
        if(content == null || content.parent == null || content.getWidth() <= 1f) return false;
        if(!listReflectTried){
            listReflectTried = true;
            try{
                fListVisible = ui.listfrag.getClass().getDeclaredField("visible");
                fListVisible.setAccessible(true);
            }catch(Throwable t){
                fListVisible = null;
            }
        }
        if(fListVisible != null){
            try{
                return fListVisible.getBoolean(ui.listfrag);
            }catch(Throwable ignored){}
        }
        return content.parent != null && content.visible;
    }

    /**
     * Подписи ника в списке игроков: сначала точные совпадения ( stripped-текст == нику),
     * если их нет — терпимые (ник может идти с префиксом вроде админ-звезды).
     */
    private static void findLabels(Element root, String plainName, Seq<Label> out){
        collectLabels(root, plainName, out, true);
        if(out.size == 0) collectLabels(root, plainName, out, false);
    }

    private static void collectLabels(Element root, String plainName, Seq<Label> out, boolean exact){
        if(root == null || plainName == null || plainName.isEmpty()) return;
        if(root instanceof Label){
            CharSequence cs = ((Label)root).getText();
            if(cs == null) return;
            String t = Strings.stripColors(cs.toString());
            if(exact ? t.equals(plainName) : (t.contains(plainName) && t.length() <= plainName.length() + 4)){
                out.add((Label)root);
            }
            return;
        }
        if(root instanceof Table){
            for(arc.scene.ui.layout.Cell<?> c : ((Table)root).getCells()){
                if(c == null) continue;
                collectLabels(c.get(), plainName, out, exact);
            }
            return;
        }
        if(root instanceof Group){
            Seq<Element> children = ((Group)root).getChildren();
            for(int i = 0; i < children.size; i++) collectLabels(children.get(i), plainName, out, exact);
        }
    }

    // ============================== СПИСКИ ==============================

    /** Список помеченных (клиенты и подозрительные) — для панели и команды /m detect. */
    public static Seq<Verdict> list(){
        return flagged;
    }

    /** Только те, у кого клиент подтверждён (level >= 2). */
    public static Seq<Verdict> clients(){
        Seq<Verdict> out = new Seq<>();
        for(Verdict v : flagged) if(v.level >= 2) out.add(v);
        return out;
    }

    /** Легенда значков — для панели Onix. */
    public static String legend(){
        return "[#" + colMkukic + "]MK[] — свой MKUKIC   "
            + "[#" + colOnix + "]Onix[] — Onix (полная)   "
            + "[#" + colLite + "]Lite[] — Onix Lite   "
            + "[#" + colFoo + "]Foo[] — Foo's Client   "
            + "[#" + colClient + "]C[] — клиент, семейство не определено\n"
            + "[#8b94a5]«?» после значка — семейство угадано по поведению, а не подтверждено тегом ника.[]";
    }
}
