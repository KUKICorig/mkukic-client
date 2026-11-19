package mkukic.client.qol;

import arc.*;
import arc.graphics.*;
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
import mindustry.world.blocks.storage.CoreBlock.*;
import mindustry.world.modules.*;
import mkukic.*;
import mkukic.client.*;

import static mindustry.Vars.*;

/**
 * Поток ресурсов ядра (v1.16) — порт идеи marcusbandit/resource-flow
 * («compact HUD overlay: current count and net flow per item») и
 * MindustryTool «Team Resource HUD».
 *
 * Показывает по каждому предмету команды: сколько в ядре и ЧИСТЫЙ ПРИХОД в секунду
 * (зелёный = растёт, красный = тратится). Ванильная панель такого не показывает:
 * по ней не видно, что производство встало или склад переполняется.
 *
 * Скорость считается честно — по разнице снапшотов ItemModule команды
 * (раз в {@link #SAMPLE_EVERY} кадров), поэтому работает и в одиночной игре, и по сети.
 */
public class CoreFlow{
    /** Как часто берём снапшот ядра, в кадрах (30 = 0.5с). */
    public static final int SAMPLE_EVERY = 30;

    public static final ObjectMap<Item, Float> rate = new ObjectMap<>();     // единиц/сек
    public static final ObjectMap<Item, Integer> last = new ObjectMap<>();   // последний снапшот
    public static final Seq<Item> order = new Seq<>();                       // порядок строк

    private static float sampleAt;
    private static boolean dragging;
    private static Table root;
    private static Table rows;
    private static String lastSig = "";

    // ======================= ЧИСТЫЕ ФУНКЦИИ (тестируются) =======================

    /**
     * Чистый приход в секунду по двум снапшотам.
     * coreLimit — вместимость ядра: если склад упёрся в потолок, приход «срезается»
     * и мы показываем реальное производство, а не ноль (важно для диагностики переполнения).
     */
    public static float rateOf(int prev, int now, float seconds, int capacity){
        if(seconds <= 0f) return 0f;
        float diff = now - prev;
        if(capacity > 0 && now >= capacity && diff < 0f) diff = 0f;
        return diff / seconds;
    }

    /** Формат скорости: «+12.5/с», «−3/с», «0». */
    public static String fmtRate(float v){
        if(Math.abs(v) < 0.05f) return "0";
        String n = Strings.autoFixed(Math.abs(v), Math.abs(v) < 100f ? 1 : 0);
        return (v > 0f ? "+" : "−") + n + "/с";
    }

    /** Цвет строки по скорости. */
    public static Color rateColor(float v){
        if(v > 0.05f) return Color.valueOf("7dff9a");
        if(v < -0.05f) return Color.valueOf("ff8f7d");
        return Color.valueOf("8b94a5");
    }

    /**
     * Компактное число: 1234 -> 1.2k, 1234567 -> 1.2M (как в ванильной панели ядра).
     */
    public static String compact(int v){
        if(v >= 1_000_000) return Strings.autoFixed(v / 1_000_000f, 1) + "M";
        if(v >= 10_000) return Strings.autoFixed(v / 1000f, 1) + "k";
        return String.valueOf(v);
    }

    /** Нужно ли показывать предмет (фильтр «только с движением»). */
    public static boolean showItem(float rate, int amount, boolean onlyMoving){
        return !onlyMoving || Math.abs(rate) >= 0.05f || amount > 0;
    }

    // ======================= ЛОГИКА =======================

    public static void reset(){
        rate.clear();
        last.clear();
        order.clear();
        sampleAt = 0f;
        lastSig = "";
    }

    public static void tick(){
        MKUKICSettings s = MKUKICSettings.s();
        if(!s.enabled || !s.qolEnabled || !s.coreFlow) return;
        if(player == null || state == null || state.isMenu()) return;
        if(Time.time - sampleAt < SAMPLE_EVERY) return;
        sampleAt = Time.time;

        ItemModule items = player.team() == null ? null : player.team().items();
        if(items == null) return;

        float seconds = SAMPLE_EVERY / 60f;
        int cap = 0;
        CoreBuild core = player.bestCore();
        if(core != null && content.items().size > 0) cap = core.getMaximumAccepted(content.items().first());

        for(Item item : content.items()){
            if(item == null) continue;
            int now = items.get(item);
            Integer prev = last.get(item);
            if(prev != null){
                rate.put(item, rateOf(prev, now, seconds, cap));
            }else{
                rate.put(item, 0f);
            }
            last.put(item, now);
            if(!order.contains(item, true)) order.add(item);
        }
        // предметы, которых больше нет в контенте (выгруженный мод), — не показываем
        order.removeAll(i -> i == null);
    }

    // ======================= HUD =======================

    /** Один раз при загрузке клиента: создаём плашку на сцене. */
    public static void initHud(){
        if(root != null || Core.scene == null || headless) return;
        MKUKICSkin.init();

        root = new Table(MKUKICSkin.panelSoft);
        root.top().left();
        root.margin(4f, 7f, 5f, 7f);
        rows = new Table();
        rows.top().left();
        root.add(rows).top().left();
        root.pack();

        Core.scene.add(root);
        root.visible(() -> {
            MKUKICSettings s = MKUKICSettings.s();
            return s.enabled && s.qolEnabled && s.coreFlow && player != null && state != null
                && !state.isMenu() && !(Core.scene != null && Core.scene.hasDialog())
                && (ui == null || ui.hudfrag == null || ui.hudfrag.shown());
        });
        root.update(() -> {
            MKUKICSettings s = MKUKICSettings.s();
            String sig = signature(s);
            if(!sig.equals(lastSig)){
                lastSig = sig;
                rebuild(s);
            }
            if(!dragging){
                root.setPosition(s.coreFlowX, Core.scene.getHeight() - s.coreFlowY - root.getHeight());
                QolWindows.clamp(root);
            }
        });

        // перетаскивание включается кнопкой в окне — иначе плашку не отличить от текста
        root.addListener(new arc.scene.event.InputListener(){
            float ox, oy;
            @Override
            public boolean touchDown(arc.scene.event.InputEvent event, float x, float y, int pointer, arc.input.KeyCode button){
                if(!dragging) return false;
                ox = x; oy = y;
                root.toFront();
                return true;
            }
            @Override
            public void touchDragged(arc.scene.event.InputEvent event, float x, float y, int pointer){
                if(!dragging) return;
                root.setPosition(root.x + (x - ox), root.y + (y - oy));
                QolWindows.clamp(root);
            }
            @Override
            public void touchUp(arc.scene.event.InputEvent event, float x, float y, int pointer, arc.input.KeyCode button){
                if(!dragging) return;
                dragging = false;
                MKUKICSettings st = MKUKICSettings.s();
                st.coreFlowX = root.x;
                st.coreFlowY = Math.max(0f, Core.scene.getHeight() - root.y - root.getHeight());
                MKUKICSettings.save();
                MKUKICMod.toast("[lightgray]позиция плашки сохранена");
            }
        });
    }

    /** Подпись текущего содержимого — перестраиваем строки только когда что-то изменилось. */
    private static String signature(MKUKICSettings s){
        StringBuilder sb = new StringBuilder();
        for(Item item : order){
            int amount = last.get(item, 0);
            float r = rate.get(item, 0f);
            if(!showItem(r, amount, s.coreFlowOnlyMoving)) continue;
            sb.append(item.name).append(':').append(compact(amount)).append(':').append((int)(r * 2f)).append('|');
        }
        return sb.toString();
    }

    private static void rebuild(MKUKICSettings s){
        if(rows == null) return;
        rows.clear();
        rows.top().left();

        int col = 0;
        Table line = new Table();
        line.left();
        rows.add(line).left().row();

        for(Item item : order){
            int amount = last.get(item, 0);
            float r = rate.get(item, 0f);
            if(!showItem(r, amount, s.coreFlowOnlyMoving)) continue;

            line.table(c -> {
                c.left();
                try{
                    if(item.uiIcon != null) c.image(item.uiIcon).size(18f).padRight(3f);
                }catch(Throwable ignored){}
                c.label(() -> "[#e8ecf4]" + compact(last.get(item, 0))).left().minWidth(46f);
                if(s.coreFlowRates){
                    c.label(() -> {
                        float v = rate.get(item, 0f);
                        Color cc = rateColor(v);
                        return "[#" + cc.toString() + "]" + fmtRate(v);
                    }).left().padLeft(4f).minWidth(52f);
                }
            }).left().pad(1f, 3f, 1f, 3f).minWidth(s.coreFlowRates ? 132f : 74f);

            if(++col >= s.coreFlowColumns){
                col = 0;
                line = new Table();
                line.left();
                rows.add(line).left().row();
            }
        }
        if(col == 0 && rows.getCells().size > 0){
            // последняя строка оказалась пустой — убираем
            rows.getCells().pop();
        }
        root.pack();
    }

    /** Включить режим перетаскивания плашки (следующее нажатие на неё — двигает). */
    public static void startDrag(){
        if(root == null) initHud();
        dragging = true;
        MKUKICMod.toast("[accent]Потяни плашку потока ресурсов — отпустишь, позиция сохранится");
    }

    public static boolean isDragging(){
        return dragging;
    }

    // ======================= ОКНО =======================

    public static void build(Table t){
        QolWindows.section(t, "Поток ресурсов ядра");
        t.add("[lightgray]Плашка на экране: по каждому предмету — сколько в ядре и чистый приход в секунду.\n"
            + "Зелёный = копится, красный = тратится, серый = стоит. Так сразу видно, что конвейер\n"
            + "встал, склад переполнен или производство не успевает за фронтом.")
            .left().top().wrap().width(360f).pad(3f, 0f, 6f, 0f).row();

        t.table(c -> {
            c.left();
            c.check("Плашка включена", MKUKICSettings.s().coreFlow, v -> {
                MKUKICSettings.s().coreFlow = v;
                MKUKICSettings.save();
            }).padRight(10f);
            c.check("Показывать скорость", MKUKICSettings.s().coreFlowRates, v -> {
                MKUKICSettings.s().coreFlowRates = v;
                MKUKICSettings.save();
                lastSig = "";
            }).padRight(10f);
            c.check("Только с движением", MKUKICSettings.s().coreFlowOnlyMoving, v -> {
                MKUKICSettings.s().coreFlowOnlyMoving = v;
                MKUKICSettings.save();
                lastSig = "";
            });
        }).left().row();

        t.table(c -> {
            c.left();
            c.add("[#c2cad8]Предметов в строке").padRight(6f);
            c.slider(1f, 8f, 1f, MKUKICSettings.s().coreFlowColumns, v -> {
                MKUKICSettings.s().coreFlowColumns = (int)v;
                MKUKICSettings.save();
                lastSig = "";
            }).width(150f);
            c.label(() -> String.valueOf(MKUKICSettings.s().coreFlowColumns)).padLeft(6f);
        }).left().padTop(4f).row();

        t.table(c -> {
            c.left();
            c.button((char)Iconc.move + "  Переместить плашку", MKUKICSkin.actBtn, CoreFlow::startDrag)
                .minWidth(180f).height(32f).padRight(6f);
            c.button((char)Iconc.up + "  На место по умолчанию", MKUKICSkin.actBtn, () -> {
                MKUKICSettings.s().coreFlowX = 8f;
                MKUKICSettings.s().coreFlowY = 132f;
                MKUKICSettings.save();
            }).minWidth(200f).height(32f);
        }).left().padTop(6f).row();

        t.image(MKUKICSkin.divSoft).height(2f).growX().pad(7f, 0f, 4f, 0f).row();
        t.add("[#6fd6ff]Сейчас").left().padBottom(2f).row();
        t.label(CoreFlow::summary).left().top().wrap().width(360f).row();
    }

    /** Текстовая сводка (то же, что на плашке) — для окна. */
    public static String summary(){
        if(order.isEmpty()) return "[darkgray]нет данных — зайди в игру";
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for(Item item : order){
            float r = rate.get(item, 0f);
            int a = last.get(item, 0);
            if(a <= 0 && Math.abs(r) < 0.05f) continue;
            Color c = rateColor(r);
            sb.append('[').append(item.color.toString()).append(']').append((char)Iconc.box)
                .append("[] [lightgray]").append(item.localizedName == null ? item.name : item.localizedName)
                .append(": [white]").append(compact(a)).append("  [#" ).append(c.toString()).append(']')
                .append(fmtRate(r)).append('\n');
            if(++n >= 12) break;
        }
        return sb.length() == 0 ? "[darkgray]ядро пустое" : sb.toString();
    }
}
