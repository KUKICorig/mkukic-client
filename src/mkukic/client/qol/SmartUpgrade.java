package mkukic.client.qol;

import arc.*;
import arc.math.geom.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.math.*;
import arc.util.*;
import mindustry.content.*;
import mindustry.core.*;
import mindustry.entities.units.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.ui.*;
import mindustry.world.*;
import mkukic.*;
import mkukic.client.*;

import static mindustry.Vars.*;

/**
 * Smart Upgrade (MindustryTool): апгрейд целых цепочек транспорта в один клик.
 * Наведи курсор на конвейер/трубу/бур — кнопка найдёт всю связную цепочку того же типа
 * и заменит её на следующий уровень (через обычные планы стройки — работает по сети).
 */
public class SmartUpgrade{
    /** Семейства апгрейда: цикл идёт по списку, с последней ступени — снова на первую. */
    private static final Block[][] families = {
        {Blocks.conveyor, Blocks.titaniumConveyor, Blocks.plastaniumConveyor, Blocks.surgeConveyor},
        {Blocks.conduit, Blocks.pulseConduit, Blocks.platedConduit},
        {Blocks.itemBridge, Blocks.phaseConveyor},
        {Blocks.bridgeConduit, Blocks.phaseConduit},
        {Blocks.mechanicalDrill, Blocks.pneumaticDrill, Blocks.laserDrill, Blocks.blastDrill},
        {Blocks.payloadConveyor, Blocks.reinforcedPayloadConveyor},
        {Blocks.duct, Blocks.armoredDuct},
    };

    public static void build(Table t){
        t.add("[lightgray]Наведи курсор на блок транспорта/бур в мире и жми кнопку.\n"
            + "Вся связанная цепочка того же типа будет заменена на следующий уровень\n"
            + "(через планы стройки — сервер увидит обычное строительство).").left().top().wrap().width(340f).pad(3f).row();

        t.label(() -> {
            Block b = blockUnderCursor();
            Block next = nextOf(b);
            if(b == null) return "[darkgray]курсор: —";
            String bn = b.localizedName == null ? b.name : b.localizedName;
            if(next == null || next == b) return "[lightgray]курсор: [white]" + bn + " [darkgray](нет следующего уровня)";
            String nn = next.localizedName == null ? next.name : next.localizedName;
            return "[lightgray]курсор: [white]" + bn + " [accent]→ [lime]" + nn;
        }).left().top().wrap().width(340f).pad(4f).row();

        t.table(c -> {
            c.left();
            Label val = new Label("", Styles.outlineLabel);
            c.add("Лимит блоков:").left();
            c.add(val).right().minWidth(56f).padRight(6f);
            c.slider(20f, 2000f, 20f, MKUKICSettings.s().smartUpgradeMax, v -> {
                MKUKICSettings.s().smartUpgradeMax = (int)v;
                MKUKICSettings.save();
            }).width(150f);
            val.setText(() -> String.valueOf(MKUKICSettings.s().smartUpgradeMax));
        }).left().pad(4f).row();

        t.table(c -> {
            c.left();
            c.button("Улучшить цепочку от курсора", MKUKICSkin.actBtn, SmartUpgrade::doUpgrade)
                .minWidth(250f).height(38f).pad(3f);
        }).left().row();

        t.image(Tex.whiteui, Pal.accent).height(1f).growX().pad(5f, 0f, 5f, 0f).row();
        t.add("[accent]Семейства апгрейда:").left().row();
        StringBuilder sb = new StringBuilder();
        for(Block[] fam : families){
            for(int i = 0; i < fam.length; i++){
                if(fam[i] == null) continue;
                sb.append(i > 0 ? " → " : "").append(fam[i].localizedName == null ? fam[i].name : fam[i].localizedName);
            }
            sb.append(" → (цикл)\n");
        }
        t.add("[darkgray]" + sb).left().top().wrap().width(340f).pad(3f).row();
    }

    private static @Nullable Block blockUnderCursor(){
        if(world == null || state == null || state.isMenu()) return null;
        Vec2 m = Core.input.mouseWorld();
        Tile t = world.tile(World.toTile(m.x), World.toTile(m.y));
        return t == null ? null : t.block();
    }

    private static @Nullable Block nextOf(@Nullable Block b){
        if(b == null) return null;
        for(Block[] fam : families){
            for(int i = 0; i < fam.length; i++){
                if(fam[i] == b) return fam[(i + 1) % fam.length];
            }
        }
        return null;
    }

    /**
     * BFS по связным тайлам одного блока (учитываем, что мульти-тайловые блоки
     * дают один и тот же build на всех своих тайлах — идем по origin).
     * Общий помощник: используется апгрейдом и умным сносом (SmartPrune).
     */
    public static Seq<Tile> collectChain(Block b, Tile start, int max){
        Seq<Tile> origins = new Seq<>();
        if(b == null || start == null) return origins;
        IntSet seen = new IntSet();
        Queue<Tile> q = new Queue<>();
        q.addLast(start);
        seen.add(start.pos());
        while(!q.isEmpty() && origins.size < max){
            Tile cur = q.removeFirst();
            Building build = cur.build;
            Tile origin = build != null ? build.tile : cur;
            if(build != null){
                if(!seen.add(origin.pos())) continue;
                if(origin.block() == b) origins.add(origin);
            }else if(cur.block() == b){
                origins.add(cur);
            }
            for(int i = 0; i < 4; i++){
                Tile n = cur.nearby(i);
                if(n == null) continue;
                Block nb = n.block();
                if(nb != b) continue;
                Building nbuild = n.build;
                int key = nbuild != null ? nbuild.tile.pos() : n.pos();
                if(!seen.contains(key)){
                    seen.add(key);
                    q.addLast(n);
                }
            }
        }
        return origins;
    }

    /** Публичная точка входа (меню/кнопки). */
    public static void doUpgradePublic(){
        doUpgrade();
    }

    private static void doUpgrade(){
        if(MKUKICMenu.dead()){
            MKUKICMod.toast("[scarlet]Нет юнита");
            return;
        }
        Block b = blockUnderCursor();
        Block next = nextOf(b);
        if(b == null || next == null || next == b){
            MKUKICMod.toast("[scarlet]Курсор не на блоке из семейства апгрейда");
            return;
        }
        Unit u = player.unit();

        Vec2 m = Core.input.mouseWorld();
        Tile start = world.tile(World.toTile(m.x), World.toTile(m.y));
        if(start == null) return;

        int max = Mathf.clamp(MKUKICSettings.s().smartUpgradeMax, 10, 5000);

        Seq<Tile> origins = collectChain(b, start, max);

        if(origins.isEmpty()){
            MKUKICMod.toast("[scarlet]Цепочка не найдена");
            return;
        }

        int planned = 0;
        for(Tile o : origins){
            Building build = o.build;
            int rot = build != null ? build.rotation : 0;
            u.addBuild(new BuildPlan(o.x, o.y, rot, next), false);
            planned++;
        }
        String nn = next.localizedName == null ? next.name : next.localizedName;
        MKUKICMod.toast("[accent]Планов на замену: " + planned + " → " + nn);
    }
}
