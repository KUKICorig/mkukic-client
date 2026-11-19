package mkukic.client.qol;

import arc.graphics.*;
import arc.math.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.ui.*;
import mindustry.world.blocks.power.*;
import mkukic.*;
import mkukic.client.MKUKICSkin;

import static mindustry.Vars.*;

/**
 * Анализ энергосетей (MI2-Utilities «Power Analysis»):
 * полоса состояния каждой сети отдельно (производство/потребление/батареи)
 * + итоговая сводка. Клик по сети — прыжок к её первому генератору.
 */
public class PowerMonitor{
    private static final Seq<NetInfo> nets = new Seq<>();
    private static float cacheTime = -9999f;
    private static float totProd, totNeed, totStored, totCap;
    private static int totGrids;

    static class NetInfo{
        PowerGraph graph;
        float produced, needed, stored, capacity, satisfaction, balance;
        float anchorX, anchorY;
        boolean hasAnchor;
        int producers, consumers, batteries;
    }

    public static void reset(){
        nets.clear();
        cacheTime = -9999f;
    }

    public static void tick(){
        if(state == null || state.isMenu()) return;
        if(Time.time - cacheTime < 20f) return;
        cacheTime = Time.time;

        ObjectSet<PowerGraph> seen = new ObjectSet<>();
        nets.clear();
        totProd = totNeed = totStored = totCap = 0f;
        totGrids = 0;

        for(Building b : Groups.build){
            if(b == null || b.power == null || b.power.graph == null) continue;
            PowerGraph g = b.power.graph;
            if(!seen.add(g)) continue;
            if(g.all.size == 0) continue;

            NetInfo n = new NetInfo();
            n.graph = g;
            n.produced = g.getLastPowerProduced();
            n.needed = g.getLastPowerNeeded();
            n.stored = g.getBatteryStored();
            n.capacity = g.getBatteryCapacity();
            n.satisfaction = g.getSatisfaction();
            n.balance = g.getPowerBalance();
            n.producers = g.producers.size;
            n.consumers = g.consumers.size;
            n.batteries = g.batteries.size;

            Building anchor = g.producers.size > 0 ? g.producers.peek()
                : (g.consumers.size > 0 ? g.consumers.peek() : g.all.peek());
            if(anchor != null){
                n.anchorX = anchor.x;
                n.anchorY = anchor.y;
                n.hasAnchor = true;
            }
            nets.add(n);
            totProd += n.produced;
            totNeed += n.needed;
            totStored += n.stored;
            totCap += n.capacity;
            totGrids++;
        }
        // крупные сети сверху
        nets.sort((a, b) -> Float.compare(b.needed, a.needed));
    }

    public static void build(Table t){
        t.label(() -> "[white]Сети: [accent]" + totGrids
            + "   [lightgray]|  производство: [lime]" + Strings.autoFixed(totProd * 60f, 0)
            + "[lightgray]/потребление: [scarlet]" + Strings.autoFixed(totNeed * 60f, 0) + " ед/с[]"
            + (totCap > 1f ? "\n[lightgray]Батареи: [sky]" + (int)totStored + "[lightgray] / " + (int)totCap
                + " (" + (int)(totCap > 0 ? totStored / totCap * 100f : 0) + "%)" : "")
        ).left().top().wrap().width(380f).pad(3f).row();

        t.image(Tex.whiteui, Pal.accent).height(1f).growX().pad(4f, 0f, 4f, 0f).row();

        Table list = new Table();
        list.top().left();
        rebuildList(list);
        // перестраиваем строки, только когда меняется состав сетей (иначе скролл сбрасывался бы)
        list.update(() -> {
            if(signature() != shownSig) rebuildList(list);
        });
        t.pane(list).maxHeight(300f).growX().pad(2f).row();

        t.add("[darkgray]Полоса = удовлетворённость сети (100% — хватает на всё).\nКлик «→» — камера к первому генератору сети.").left().top().wrap().width(380f).padTop(6f).row();
    }

    private static int shownSig = Integer.MIN_VALUE;

    private static int signature(){
        int s = nets.size;
        for(NetInfo n : nets) s = s * 31 + n.graph.getID();
        return s;
    }

    private static void rebuildList(Table list){
        shownSig = signature();
        list.clear();
        list.top().left();
        if(nets.isEmpty()){
            list.add("[darkgray]энергосетей не найдено").left().pad(6f).row();
            return;
        }
        int i = 1;
        for(NetInfo n : nets){
            final NetInfo fn = n;
            final PowerGraph g = n.graph;
            final int idx = i++;
            list.table(row -> {
                row.left();
                row.add("[lightgray]#" + idx).padRight(4f).top();
                row.table(cell -> {
                    cell.left().top();
                    // живые значения — прямо из графа
                    cell.add(new Bar(
                        () -> Strings.autoFixed(g.getLastPowerProduced() * 60f, 0) + "→" + Strings.autoFixed(g.getLastPowerNeeded() * 60f, 0) + " ед/с"
                            + (g.getBatteryCapacity() > 1f ? "  |  " + (int)(g.getBatteryStored() / Math.max(1f, g.getBatteryCapacity()) * 100f) + "% бат." : ""),
                        () -> g.getSatisfaction() >= 0.999f ? Pal.place
                            : (g.getSatisfaction() > 0.5f ? Color.orange : Color.scarlet),
                        () -> Mathf.clamp(g.getSatisfaction())
                    )).width(250f).height(20f).row();
                    cell.label(() -> "[darkgray]" + g.producers.size + " ген. / " + g.consumers.size + " потр. / " + g.batteries.size + " бат.")
                        .left().padTop(1f);
                }).left().padRight(4f);
                if(fn.hasAnchor){
                    row.button("→", MKUKICSkin.actBtn, () -> QolWindows.jumpTo(fn.anchorX, fn.anchorY)).size(28f).top();
                }
            }).left().growX().pad(2f).row();
            list.image(Tex.whiteui, Pal.darkestGray).height(1f).growX().row();
            if(idx >= 40) break; // защита от гигантских списков
        }
    }
}
