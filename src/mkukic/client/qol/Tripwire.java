package mkukic.client.qol;

import arc.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.math.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import mindustry.core.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.ui.*;
import mindustry.world.blocks.storage.CoreBlock.*;
import mkukic.*;
import mkukic.client.*;

import static mindustry.Vars.*;

/**
 * Геозона-сигнализация (Neon «Tripwire alert / geofence»):
 * круг вокруг ядра (или игрока) заданного радиуса; при проникновении врага —
 * звук, тост и запись в журнал окна. Круг рисуется в мире, пульсирует при тревоге.
 */
public class Tripwire{
    private static final IntSet inside = new IntSet();
    private static final Seq<String> log = new Seq<>();
    private static float lastAlert = -9999f;
    private static float checkTime;
    private static float anchorX, anchorY;
    private static boolean hasAnchor;

    public static void reset(){
        inside.clear();
        log.clear();
        lastAlert = -9999f;
        hasAnchor = false;
    }

    private static boolean updateAnchor(){
        MKUKICSettings s = MKUKICSettings.s();
        if(s.tripwireAtCore){
            if(player == null) return false;
            CoreBuild core = player.bestCore();
            if(core == null) return false;
            anchorX = core.x;
            anchorY = core.y;
        }else{
            if(MKUKICMenu.dead()) return false;
            anchorX = player.unit().x;
            anchorY = player.unit().y;
        }
        hasAnchor = true;
        return true;
    }

    public static void tick(){
        MKUKICSettings s = MKUKICSettings.s();
        if(!s.enabled || !s.qolEnabled || !s.tripwire) return;
        if(player == null || state == null || state.isMenu()) return;
        if(Time.time - checkTime < 10f) return;
        checkTime = Time.time;
        if(!updateAnchor()) return;

        float r2 = s.tripwireRadius * s.tripwireRadius;
        Team my = player.team();
        IntSet now = new IntSet();
        boolean breach = false;
        Unit firstIntruder = null;

        for(Unit u : Groups.unit){
            if(u == null || u.dead() || u.team() == my) continue;
            if(u.team() == Team.derelict) continue;
            float d2 = (u.x - anchorX) * (u.x - anchorX) + (u.y - anchorY) * (u.y - anchorY);
            if(d2 <= r2){
                now.add(u.id);
                if(!inside.contains(u.id)){
                    breach = true;
                    if(firstIntruder == null) firstIntruder = u;
                }
            }
        }
        inside.clear();
        inside.addAll(now);

        if(breach && firstIntruder != null){
            String name = firstIntruder.type.localizedName == null ? firstIntruder.type.name : firstIntruder.type.localizedName;
            String entry = "[scarlet]⚠ " + fmtClock() + "  " + name
                + " [darkgray](" + World.toTile(firstIntruder.x) + ", " + World.toTile(firstIntruder.y) + ")";
            log.insert(0, entry);
            while(log.size > 30) log.pop();
            if(Time.time - lastAlert > 120f){
                lastAlert = Time.time;
                MKUKICMod.toast("[scarlet]⚠ ГЕОЗОНА: " + name + " внутри периметра!");
                if(s.tripwireSound){
                    try{
                        Sounds.explosionDull.at(firstIntruder.x, firstIntruder.y, 0.9f);
                    }catch(Throwable ignored){}
                }
            }
        }
    }

    private static String fmtClock(){
        int sec = (int)(Time.globalTime / 60f) % 100000;
        return (sec / 60) + ":" + (sec % 60 < 10 ? "0" : "") + (sec % 60);
    }

    public static void draw(){
        MKUKICSettings s = MKUKICSettings.s();
        if(!s.enabled || !s.qolEnabled || !s.tripwire) return;
        if(player == null || state == null || state.isMenu()) return;
        if(Core.scene != null && Core.scene.hasDialog()) return;
        if(!hasAnchor && !updateAnchor()) return;

        Draw.z(Layer.overlayUI);
        boolean alarm = Time.time - lastAlert < 90f;
        Color c = alarm ? Color.scarlet : Pal.accent;
        float pulse = alarm ? 0.55f + Mathf.absin(Time.time, 3f, 0.35f) : 0.3f + Mathf.absin(Time.time, 8f, 0.08f);

        Draw.color(c.r, c.g, c.b, pulse * 0.12f);
        Fill.circle(anchorX, anchorY, s.tripwireRadius);
        Draw.color(c.r, c.g, c.b, pulse);
        Lines.stroke(alarm ? 3f : 1.6f);
        Lines.circle(anchorX, anchorY, s.tripwireRadius);
        Draw.reset();
    }

    // ============================ ОКНО ============================

    public static void build(Table t){
        MKUKICSettings s = MKUKICSettings.s();

        t.check("Сигнализация включена", s.tripwire, v -> {
            MKUKICSettings.s().tripwire = v;
            MKUKICSettings.save();
        }).update(cb -> cb.setChecked(MKUKICSettings.s().tripwire)).left().pad(3f).row();

        t.table(c -> {
            c.left();
            c.check("Центр: ядро", MKUKICSettings.s().tripwireAtCore, v -> {
                MKUKICSettings.s().tripwireAtCore = v;
                MKUKICSettings.save();
                hasAnchor = false;
            }).update(cb -> cb.setChecked(MKUKICSettings.s().tripwireAtCore)).left().pad(2f);
            c.check("Центр: игрок", !MKUKICSettings.s().tripwireAtCore, v -> {
                MKUKICSettings.s().tripwireAtCore = !v;
                MKUKICSettings.save();
                hasAnchor = false;
            }).update(cb -> cb.setChecked(!MKUKICSettings.s().tripwireAtCore)).left().pad(2f);
        }).left().row();

        t.check("Звук тревоги", s.tripwireSound, v -> {
            MKUKICSettings.s().tripwireSound = v;
            MKUKICSettings.save();
        }).update(cb -> cb.setChecked(MKUKICSettings.s().tripwireSound)).left().padLeft(6f).row();

        t.table(c -> {
            c.left();
            c.add("Радиус:").minWidth(70f).left();
            Label val = new Label("", Styles.outlineLabel);
            c.add(val).right().minWidth(70f).padRight(6f);
            c.slider(100f, 4000f, 50f, s.tripwireRadius, v -> {
                MKUKICSettings.s().tripwireRadius = v;
                MKUKICSettings.save();
            }).width(160f);
            val.setText(() -> (int)(MKUKICSettings.s().tripwireRadius / tilesize) + " тайлов");
        }).left().pad(3f).row();

        t.add("[darkgray]Враг пересекает периметр → тост, звук и запись в журнал.\nВрагов внутри сейчас: ").left().top().wrap().width(320f).pad(4f, 0f, 2f, 0f).row();
        t.label(() -> "[scarlet]" + inside.size).left().padLeft(6f).row();

        t.image(Tex.whiteui, Pal.accent).height(1f).growX().pad(5f, 0f, 5f, 0f).row();

        t.table(c -> {
            c.left();
            c.add("[accent]Журнал тревог").left();
            c.add().growX();
            c.button("очистить", MKUKICSkin.actBtn, () -> log.clear()).minWidth(90f);
        }).left().growX().row();

        t.pane(l -> {
            l.top().left();
            l.label(() -> log.isEmpty() ? "[darkgray]тишина..." : String.join("\n", log)).left().top().wrap().width(310f);
        }).maxHeight(180f).growX().pad(2f).row();
    }
}
