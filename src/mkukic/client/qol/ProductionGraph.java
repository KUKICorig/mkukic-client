package mkukic.client.qol;

import arc.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.math.*;
import arc.scene.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import mindustry.content.*;
import mindustry.gen.*;
import mindustry.type.*;
import mindustry.ui.*;
import mindustry.graphics.*;
import mindustry.world.modules.*;
import mkukic.*;
import mkukic.client.*;

import static mindustry.Vars.*;

/**
 * График производства (v1.16) — порт идеи vadymchan/mindustry-production-graph
 * («Factorio-style production graph: per-item produced/consumed throughput over time»)
 * и развитие окна «Ядро: ресурсы» (MI2 Core Info).
 *
 * Раз в секунду берём снапшот ItemModule команды и складываем ЧИСТЫЙ ПОТОК по каждому
 * предмету в кольцевой буфер. Потом рисуем «факторио-график»: зелёная площадь выше оси —
 * приход, красная ниже оси — расход, окно 60с / 5м / 10м.
 *
 * Отличие от CoreStats: там линейный график накопленного количества по 4 предметам,
 * здесь — именно ПРОИЗВОДСТВО/ПОТРЕБЛЕНИЕ во времени и по всем предметам сразу,
 * с таблицей средних значений (чтобы видеть, какая цепочка просела).
 */
public class ProductionGraph{
    /** Секунд в буфере максимум (10 минут). */
    public static final int MAX_SAMPLES = 600;
    /** Как часто берём срез, в кадрах (60 = 1с). */
    public static final int SAMPLE_EVERY = 60;

    private static final ObjectMap<Item, FloatSeq> hist = new ObjectMap<>();
    private static final ObjectMap<Item, Integer> last = new ObjectMap<>();
    private static float sampleAt;
    private static Table listRef;
    private static int lastListSig = -1;

    /** Окна графика в секундах. */
    public static final int[] WINDOWS = {60, 300, 600};

    // ======================= ЧИСТЫЕ ФУНКЦИИ (тестируются) =======================

    /** Добавить срез потока в кольцевой буфер. */
    public static void push(FloatSeq buf, float v, int max){
        if(buf == null) return;
        buf.add(v);
        while(buf.size > max) buf.removeIndex(0);
    }

    /** Среднее по последним N элементам буфера. */
    public static float avg(FloatSeq buf, int n){
        if(buf == null || buf.isEmpty()) return 0f;
        int from = Math.max(0, buf.size - Math.max(1, n));
        float sum = 0f;
        int c = 0;
        for(int i = from; i < buf.size; i++){ sum += buf.get(i); c++; }
        return c == 0 ? 0f : sum / c;
    }

    /** Средний приход (только положительные срезы). */
    public static float avgProduced(FloatSeq buf, int n){
        if(buf == null || buf.isEmpty()) return 0f;
        int from = Math.max(0, buf.size - Math.max(1, n));
        float sum = 0f; int c = 0;
        for(int i = from; i < buf.size; i++){
            float v = buf.get(i);
            if(v > 0f){ sum += v; c++; }
        }
        return c == 0 ? 0f : sum / c;
    }

    /** Средний расход (модуль отрицательных срезов). */
    public static float avgConsumed(FloatSeq buf, int n){
        if(buf == null || buf.isEmpty()) return 0f;
        int from = Math.max(0, buf.size - Math.max(1, n));
        float sum = 0f; int c = 0;
        for(int i = from; i < buf.size; i++){
            float v = buf.get(i);
            if(v < 0f){ sum -= v; c++; }
        }
        return c == 0 ? 0f : sum / c;
    }

    /** Максимум по модулю — для масштаба графика (не меньше 1, чтобы не делить на ноль). */
    public static float peak(FloatSeq buf, int n){
        if(buf == null || buf.isEmpty()) return 1f;
        int from = Math.max(0, buf.size - Math.max(1, n));
        float m = 0f;
        for(int i = from; i < buf.size; i++) m = Math.max(m, Math.abs(buf.get(i)));
        return Math.max(1f, m);
    }

    /** Индекс первого среза, попадающего в окно в N секунд (срезы — раз в секунду). */
    public static int windowFrom(int size, int seconds){
        return Math.max(0, size - Math.max(1, seconds));
    }

    // ======================= ЛОГИКА =======================

    public static void reset(){
        hist.clear();
        last.clear();
        sampleAt = 0f;
        lastListSig = -1;
    }

    public static void tick(){
        MKUKICSettings s = MKUKICSettings.s();
        if(!s.enabled || !s.qolEnabled || !s.prodGraph) return;
        if(player == null || state == null || state.isMenu()) return;
        if(Time.time - sampleAt < SAMPLE_EVERY) return;
        sampleAt = Time.time;

        ItemModule items = player.team() == null ? null : player.team().items();
        if(items == null) return;

        for(Item item : content.items()){
            if(item == null) continue;
            int now = items.get(item);
            Integer prev = last.get(item);
            last.put(item, now);
            if(prev == null) continue;
            FloatSeq buf = hist.get(item);
            if(buf == null){
                buf = new FloatSeq();
                hist.put(item, buf);
            }
            push(buf, now - prev, MAX_SAMPLES);
        }
    }

    private static @Nullable FloatSeq buf(Item item){
        return hist.get(item);
    }

    private static int window(){
        int idx = Mathf.clamp(MKUKICSettings.s().prodGraphWindow, 0, WINDOWS.length - 1);
        return WINDOWS[idx];
    }

    /** Предметы, выбранные для графика (из настроек, только существующие). */
    public static Seq<Item> selected(){
        Seq<Item> out = new Seq<>();
        if(content == null) return out;
        for(String name : MKUKICSettings.s().prodGraphItems){
            Item it = content.item(name);
            if(it != null && !out.contains(it, true)) out.add(it);
        }
        return out;
    }

    public static void toggleItem(Item item){
        Seq<String> sel = MKUKICSettings.s().prodGraphItems;
        if(sel.contains(item.name, false)) sel.remove(item.name);
        else if(sel.size < 5) sel.add(item.name);
        else{
            sel.remove(0);
            sel.add(item.name);
        }
        MKUKICSettings.save();
        lastListSig = -1;
    }

    // ============================ ОКНО ============================

    public static void build(Table t){
        QolWindows.section(t, "График производства");
        t.add("[lightgray]Факторио-стиль: зелёная площадь выше оси — приход в ядро,\n"
            + "красная ниже — расход. Данные честно считаются по снапшотам склада команды\n"
            + "раз в секунду, поэтому видно и «встало производство», и «съедает фронт».")
            .left().top().wrap().width(420f).pad(3f, 0f, 6f, 0f).row();

        t.table(c -> {
            c.left();
            c.check("График включён", MKUKICSettings.s().prodGraph, v -> {
                MKUKICSettings.s().prodGraph = v;
                MKUKICSettings.save();
            }).padRight(10f);
            for(int i = 0; i < WINDOWS.length; i++){
                final int idx = i;
                String label = WINDOWS[i] < 60 ? WINDOWS[i] + "с" : (WINDOWS[i] / 60) + "м";
                c.button(label, MKUKICSkin.toggleBtn, () -> {
                    MKUKICSettings.s().prodGraphWindow = idx;
                    MKUKICSettings.save();
                    rebuildList();
                }).checked(b -> MKUKICSettings.s().prodGraphWindow == idx).minWidth(56f).height(30f).pad(2f);
            }
            c.label(() -> "[#8b94a5]срезов: " + maxBufSize()).padLeft(8f);
        }).left().row();

        Element chart = new Element(){
            @Override
            public void draw(){
                drawChart(this);
            }
        };
        t.add(chart).size(420f, 130f).left().pad(4f, 0f, 2f, 0f).row();
        t.label(ProductionGraph::chartInfo).left().pad(0f, 0f, 4f, 0f).row();

        t.image(MKUKICSkin.divSoft).height(2f).growX().pad(4f, 0f, 4f, 0f).row();
        t.add("[#6fd6ff]Предметы (до 5 на графике) — клик выбирает").left().padBottom(2f).row();

        Table list = new Table();
        list.top().left();
        listRef = list;
        t.pane(MKUKICSkin.scroll, list).maxHeight(200f).growX().row();

        t.table(c -> {
            c.left();
            c.button((char)Iconc.refresh + "  Топ-3 активных", MKUKICSkin.actBtn, ProductionGraph::selectTop)
                .minWidth(160f).height(30f).padRight(6f);
            c.button((char)Iconc.cancel + "  Снять выбор", MKUKICSkin.actBtn, () -> {
                MKUKICSettings.s().prodGraphItems.clear();
                MKUKICSettings.save();
                lastListSig = -1;
            }).minWidth(140f).height(30f);
        }).left().padTop(5f).row();

        rebuildList();
    }

    private static int maxBufSize(){
        int m = 0;
        for(Item item : content.items()){
            FloatSeq b = buf(item);
            if(b != null) m = Math.max(m, b.size);
        }
        return m;
    }

    /** Выбрать 3 предмета с самым большим движением за текущее окно. */
    public static void selectTop(){
        int w = window();
        Seq<Item> all = new Seq<>(content.items());
        all.sort((a, b) -> Float.compare(peak(buf(b), w), peak(buf(a), w)));
        Seq<String> sel = MKUKICSettings.s().prodGraphItems;
        sel.clear();
        int n = 0;
        for(Item it : all){
            if(n >= 3) break;
            FloatSeq b = buf(it);
            if(b == null || peak(b, w) <= 1f) continue;
            sel.add(it.name);
            n++;
        }
        MKUKICSettings.save();
        lastListSig = -1;
        rebuildList();
    }

    private static void rebuildList(){
        if(listRef == null) return;
        Table list = listRef;
        list.clear();
        list.top().left();

        int w = window();
        Seq<Item> items = new Seq<>(content.items());
        // сначала — уже выбранные и самые активные
        items.sort((a, b) -> Float.compare(peak(buf(b), w), peak(buf(a), w)));
        Seq<Item> sel = selected();
        for(Item it : sel) if(items.contains(it, true)){ items.remove(it); items.insert(0, it); }

        int col = 0;
        Table line = new Table();
        line.left();
        list.add(line).left().row();
        for(Item item : items){
            final Item fi = item;
            FloatSeq b = buf(item);
            boolean on = sel.contains(item, true);
            String name = item.localizedName == null ? item.name : item.localizedName;
            String info = b == null || b.isEmpty() ? "—" :
                "[#7dff9a]+" + Strings.autoFixed(avgProduced(b, w), 1)
                + "[] [#ff8f7d]−" + Strings.autoFixed(avgConsumed(b, w), 1) + "[]";
            line.table(c -> {
                c.left();
                c.button((on ? "[#ffb454]◉ " : "[#5f6b7d]○ ") + name, MKUKICSkin.toggleBtn, () -> toggleItem(fi))
                    .checked(x -> selected().contains(fi, true)).minWidth(150f).height(28f).left();
                c.add(info).left().padLeft(4f).minWidth(96f);
            }).left().pad(1f);
            if(++col >= 2){
                col = 0;
                line = new Table();
                line.left();
                list.add(line).left().row();
            }
        }
        if(col == 0 && list.getCells().size > 0) list.getCells().pop();
        list.pack();
        lastListSig = items.size;
    }

    /** Обновлять таблицу раз в ~2с, если окно открыто. */
    public static void updateWindow(){
        if(listRef == null || !QolWindows.isOpen("prodgraph")) return;
        if((int)(Time.time / 120f) % 2 == 0 && lastListSig >= 0){
            lastListSig = -1;
            rebuildList();
        }
    }

    // ============================ РЕНДЕР ГРАФИКА ============================

    private static void drawChart(Element e){
        // координаты локальные: (0,0) — низ-лево элемента (батч уже сдвинут сценой), как в CoreStats
        float x = 0f, y = 0f, w = e.getWidth(), h = e.getHeight();
        int win = window();

        Draw.color(0.05f, 0.07f, 0.1f, 0.8f);
        Fill.rect(x + w / 2f, y + h / 2f, w, h);
        Draw.color(Pal.darkestGray);
        Lines.stroke(1f);
        Lines.rect(x, y, w, h);

        // сетка и нулевая ось
        Draw.color(0.28f, 0.33f, 0.42f, 0.45f);
        for(int i = 1; i < 4; i++){
            float gy = y + h * i / 4f;
            Lines.line(x, gy, x + w, gy);
        }
        float mid = y + h / 2f;
        Draw.color(0.6f, 0.67f, 0.78f, 0.85f);
        Lines.stroke(1.3f);
        Lines.line(x, mid, x + w, mid);
        Draw.reset();

        Seq<Item> sel = selected();
        if(sel.isEmpty()) return;

        float max = 1f;
        for(Item it : sel) max = Math.max(max, peak(buf(it), win));

        for(Item it : sel){
            FloatSeq b = buf(it);
            if(b == null || b.size < 2) continue;
            int from = windowFrom(b.size, win);
            int n = b.size - from;
            if(n < 2) continue;

            // по оси X растягиваем имеющиеся срезы на всю ширину окна
            float stepX = w / (float)Math.max(1, n - 1);
            Color c = it.color == null ? Color.white : it.color;

            drawArea(x, mid, y + h, b, from, n, stepX, c, max, true);
            drawArea(x, mid, y, b, from, n, stepX, c, max, false);
        }
        Draw.reset();
    }

    /** Подпись под графиком (окно + масштаб). */
    public static String chartInfo(){
        int win = window();
        Seq<Item> sel = selected();
        if(sel.isEmpty()) return "[#7e8798]выбери предметы ниже — до 5 одновременно";
        float max = 1f;
        for(Item it : sel) max = Math.max(max, peak(buf(it), win));
        return "[#8b94a5]окно [white]" + (win < 60 ? win + "с" : (win / 60) + "м")
            + "[#8b94a5], масштаб [#ffb454]±" + Strings.autoFixed(max, 0) + "/с"
            + "[#8b94a5], срезов [#6fd6ff]" + maxBufSize();
    }

    private static final Color tmpColor = new Color();

    private static void drawArea(float x, float mid, float limit, FloatSeq b, int from, int n,
                                 float stepX, Color c, float max, boolean positive){
        Draw.color(c.r, c.g, c.b, 0.28f);
        for(int i = 0; i < n - 1; i++){
            float v0 = b.get(from + i), v1 = b.get(from + i + 1);
            if(positive){
                v0 = Math.max(0f, v0); v1 = Math.max(0f, v1);
            }else{
                v0 = Math.min(0f, -v0); v1 = Math.min(0f, -v1);
            }
            float h0 = (v0 / max) * (Math.abs(limit - mid));
            float h1 = (v1 / max) * (Math.abs(limit - mid));
            float x0 = x + i * stepX, x1 = x + (i + 1) * stepX;
            float dir = positive ? 1f : -1f;
            if(Math.abs(h0) < 0.2f && Math.abs(h1) < 0.2f) continue;
            Fill.quad(
                x0, mid,
                x1, mid,
                x1, mid + h1 * dir,
                x0, mid + h0 * dir);
        }
        // линия сверху
        Lines.stroke(1.6f, tmpColor.set(c).a(0.95f));
        for(int i = 0; i < n - 1; i++){
            float v0 = b.get(from + i), v1 = b.get(from + i + 1);
            if(positive){ v0 = Math.max(0f, v0); v1 = Math.max(0f, v1); }
            else{ v0 = Math.min(0f, -v0); v1 = Math.min(0f, -v1); }
            float dir = positive ? 1f : -1f;
            float h0 = (v0 / max) * (Math.abs(limit - mid)) * dir;
            float h1 = (v1 / max) * (Math.abs(limit - mid)) * dir;
            Lines.line(x + i * stepX, mid + h0, x + (i + 1) * stepX, mid + h1);
        }
        Draw.reset();
    }
}
