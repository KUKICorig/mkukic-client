package mkukic.client.qol;

import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.math.*;
import arc.scene.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import mindustry.content.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.type.*;
import mindustry.ui.*;
import mindustry.world.blocks.storage.CoreBlock.*;
import mindustry.world.modules.*;
import mkukic.*;
import mkukic.client.MKUKICSkin;

import static mindustry.Vars.*;

/**
 * Информация о ядре (MI2-Utilities «Core Info» + MindustryTool «Team Resource HUD»):
 *  - линейный график изменения ресурсов ядра за 60 секунд (до 4 предметов);
 *  - текущий инвентарь ядра и скорость поступления;
 *  - счётчики юнитов в реальном времени (свои / враги / лимит).
 */
public class CoreStats{
    private static final int SAMPLES = 120;      // 120 * 0.5s = 60 секунд
    private static final float SAMPLE_EVERY = 30f; // кадров

    private static final Seq<Item> tracked = new Seq<>();
    private static final ObjectMap<Item, FloatSeq> history = new ObjectMap<>();
    private static final ObjectMap<Item, Float> rate = new ObjectMap<>(); // единиц/мин
    private static float lastSample = -9999f;

    private static int ownUnits, enemyUnits;
    private static float unitCacheTime = -9999f;

    public static void reset(){
        history.clear();
        rate.clear();
        lastSample = -9999f;
        ownUnits = enemyUnits = 0;
        unitCacheTime = -9999f;
        syncTracked();
    }

    /** Привести список отслеживаемых предметов в соответствие с настройками. */
    public static void syncTracked(){
        tracked.clear();
        if(content == null) return;
        for(String name : MKUKICSettings.s().qolTrackedItems){
            Item it = content.item(name);
            if(it != null && !tracked.contains(it)) tracked.add(it);
            if(tracked.size >= 4) break;
        }
        if(tracked.isEmpty()){
            Item def = content.item("copper");
            if(def == null && content != null && content.items().size > 0) def = content.items().first();
            if(def != null) tracked.add(def);
        }
        for(Item it : tracked){
            if(!history.containsKey(it)) history.put(it, new FloatSeq());
        }
    }

    public static void tick(){
        if(player == null || state == null || state.isMenu()) return;

        // счётчики юнитов (кэш 0.5с)
        if(Time.time - unitCacheTime > 30f){
            unitCacheTime = Time.time;
            ownUnits = 0;
            enemyUnits = 0;
            Team my = player.team();
            for(Unit u : Groups.unit){
                if(u == null || u.dead()) continue;
                if(u.team() == my) ownUnits++; else enemyUnits++;
            }
        }

        // выборка ресурсов ядра (каждые 0.5с)
        if(Time.time - lastSample < SAMPLE_EVERY) return;
        lastSample = Time.time;
        if(tracked.isEmpty()) syncTracked();

        ItemModule items = coreItems();
        for(Item it : tracked){
            FloatSeq h = history.get(it, FloatSeq::new);
            float v = items == null ? 0f : items.get(it);
            // скорость: разница за 60 секунд (единиц/мин)
            if(h.size >= 2){
                float old = h.get(Math.max(0, h.size - SAMPLES));
                rate.put(it, (v - old) * (60f / SAMPLES) * 2f);
            }
            h.add(v);
            while(h.size > SAMPLES) h.removeIndex(0);
            history.put(it, h);
        }
    }

    private static @Nullable ItemModule coreItems(){
        if(player == null) return null;
        Team t = player.team();
        return t == null ? null : t.items();
    }

    // ============================ ОКНО ============================

    public static void build(Table t){
        // --- юниты ---
        t.label(() -> {
            int cap = state == null ? 0 : state.rules.unitCap;
            return "[white]Юниты: [lime]" + ownUnits + "[white]  |  [scarlet]враги: " + enemyUnits
                + "[white]  |  [lightgray]лимит: " + (cap <= 0 ? "∞" : cap);
        }).left().pad(3f).row();

        t.image(Tex.whiteui, Pal.accent).height(1f).growX().pad(4f, 0f, 4f, 0f).row();

        // --- график ---
        t.add("[accent]Ресурсы ядра: изменение за 60 секунд").left().row();
        Element chart = new Element(){
            @Override
            public void draw(){
                drawChart(this);
            }
        };
        t.add(chart).size(330f, 96f).left().pad(3f).row();

        // --- легенда ---
        t.table(leg -> {
            leg.left().top();
            leg.label(() -> legendText()).left().top().wrap().width(330f);
        }).left().pad(2f).row();

        t.image(Tex.whiteui, Pal.accent).height(1f).growX().pad(4f, 0f, 4f, 0f).row();

        // --- инвентарь ядра ---
        t.add("[accent]Инвентарь ядра").left().row();
        t.pane(inv -> {
            inv.left().top();
            inv.label(() -> inventoryText()).left().top().wrap().width(320f);
        }).maxHeight(140f).growX().pad(2f).row();

        t.image(Tex.whiteui, Pal.accent).height(1f).growX().pad(4f, 0f, 4f, 0f).row();

        // --- выбор предметов для графика ---
        t.add("[accent]Линии графика (до 4)").left().row();
        t.pane(pick -> {
            pick.left().top();
            int i = 0;
            for(Item it : content.items()){
                final Item fit = it;
                pick.button(it.localizedName == null ? it.name : it.localizedName, MKUKICSkin.toggleBtn, () -> {
                    toggleTrack(fit);
                }).checked(b -> tracked.contains(fit)).minWidth(96f).height(28f).pad(1f);
                if(++i % 3 == 0) pick.row();
            }
            if(i % 3 != 0) pick.row();
        }).maxHeight(120f).growX().pad(2f).row();
    }

    private static void toggleTrack(Item it){
        if(tracked.contains(it)){
            tracked.remove(it);
            history.remove(it);
            rate.remove(it);
        }else{
            if(tracked.size >= 4){
                MKUKICMod.toast("[scarlet]Максимум 4 линии — сначала отключи одну");
                return;
            }
            tracked.add(it);
            history.put(it, new FloatSeq());
        }
        // синхронизируем настройки
        Seq<String> names = MKUKICSettings.s().qolTrackedItems;
        names.clear();
        for(Item t2 : tracked) names.add(t2.name);
        MKUKICSettings.save();
    }

    private static final Color[] lineColors = {Pal.accent, Color.sky, Color.royal, Pal.heal};

    private static void drawChart(Element e){
        // координаты локальные: (0,0) — низ-лево элемента (батч уже сдвинут сценой)
        float x = 0f, y = 0f, w = e.getWidth(), h = e.getHeight();
        Draw.color(0f, 0f, 0f, 0.35f);
        Fill.rect(x + w / 2f, y + h / 2f, w, h);
        Draw.color(Pal.darkestGray);
        Lines.stroke(1f);
        Lines.rect(x, y, w, h);

        int ti = 0;
        for(Item it : tracked){
            FloatSeq hs = history.get(it);
            Color c = lineColors[ti % lineColors.length];
            ti++;
            if(hs == null || hs.size < 2) continue;

            // нормируем по общему диапазону всех линий (одна шкала)
            float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
            for(Item it2 : tracked){
                FloatSeq h2 = history.get(it2);
                if(h2 == null) continue;
                for(int i = 0; i < h2.size; i++){
                    min = Math.min(min, h2.get(i));
                    max = Math.max(max, h2.get(i));
                }
            }
            float span = Math.max(1f, max - min);

            Lines.stroke(1.8f);
            Draw.color(c);
            float px = x, py = 0f;
            for(int i = 0; i < hs.size; i++){
                float fx = x + w * (i / (float)(SAMPLES - 1));
                float fy = y + 3f + (h - 6f) * ((hs.get(i) - min) / span);
                if(i > 0) Lines.line(px, py, fx, fy);
                px = fx;
                py = fy;
            }
        }
        Draw.reset();
    }

    private static String legendText(){
        StringBuilder sb = new StringBuilder();
        int ti = 0;
        String[] tags = {"[accent]", "[sky]", "[royal]", "[lime]"};
        for(Item it : tracked){
            FloatSeq hs = history.get(it);
            float r = rate.get(it, 0f);
            sb.append(tags[ti % tags.length]).append("■[] ").append(it.localizedName == null ? it.name : it.localizedName)
                .append(": [white]").append(hs == null || hs.isEmpty() ? "0" : (int)hs.peek())
                .append(" [lightgray](").append(r >= 0 ? "+" : "").append(Strings.fixed(r, 1)).append("/мин)[]");
            if(ti < tracked.size - 1) sb.append("   ");
            ti++;
        }
        return sb.length() == 0 ? "[darkgray]выбери предметы ниже" : sb.toString();
    }

    private static String inventoryText(){
        ItemModule items = coreItems();
        if(items == null) return "[darkgray]ядро недоступно";
        StringBuilder sb = new StringBuilder();
        int cap = 0;
        CoreBuild core = player.team().core();
        if(core != null) cap = core.storageCapacity;
        boolean any = false;
        for(Item it : content.items()){
            int v = items.get(it);
            if(v <= 0) continue;
            any = true;
            sb.append('[').append(it.color.toString()).append(']')
                .append(it.localizedName == null ? it.name : it.localizedName)
                .append("[]: [white]").append(v).append("[]   ");
        }
        if(!any) sb.append("[darkgray]пусто");
        if(cap > 0) sb.append("\n[lightgray]вместимость: ").append(cap).append(" на предмет, всего: ").append(items.total());
        return sb.toString();
    }
}
