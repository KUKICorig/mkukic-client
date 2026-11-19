package mkukic.client.qol;

import arc.*;
import arc.math.geom.*;
import arc.scene.ui.*;
import arc.struct.*;
import arc.scene.ui.layout.*;
import arc.util.*;
import mindustry.content.*;
import mindustry.core.*;
import mindustry.game.*;
import mindustry.entities.units.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.ui.*;
import mindustry.world.*;
import mindustry.world.blocks.*;
import mkukic.*;
import mkukic.client.*;

import static mindustry.Vars.*;

/**
 * Быстрые действия (MI2-Utilities «Quick Actions»):
 *  - очистить свои планы стройки («призраки») в один клик;
 *  - мгновенно достроить свои планы (хост/песочница);
 *  - снести обломки (derelict) в области курсора (хост/песочница);
 *  - переключатели рендера сущностей (чужие планы, статус-оверлеи).
 */
public class QuickActions{

    public static void build(Table t){
        QolWindows.section(t, "Планы стройки");

        t.table(c -> {
            c.left();
            c.button("Очистить мои планы", MKUKICSkin.actBtn, () -> {
                if(MKUKICMenu.dead()){
                    MKUKICMod.toast("[scarlet]Нет юнита");
                    return;
                }
                int n = player.unit().plans().size;
                player.unit().plans().clear();
                MKUKICMod.toast("[accent]Удалено планов: " + n);
            }).minWidth(190f).height(34f).pad(2f);
        }).left().row();

        t.table(c -> {
            c.left();
            c.button("Мгновенно достроить", MKUKICSkin.actBtn, () -> {
                if(!hostLike()){
                    MKUKICMod.toast("[scarlet]Только хост/одиночная игра");
                    return;
                }
                if(MKUKICMenu.dead()){
                    MKUKICMod.toast("[scarlet]Нет юнита");
                    return;
                }
                int done = 0;
                Seq<BuildPlan> copy = new Seq<>();
                for(BuildPlan p : player.unit().plans()) copy.add(p);
                for(BuildPlan plan : copy){
                    Tile tile = world.tile(plan.x, plan.y);
                    if(tile == null || tile.build == null) continue;
                    if(tile.build instanceof ConstructBlock.ConstructBuild){
                        ConstructBlock.ConstructBuild cb = (ConstructBlock.ConstructBuild)tile.build;
                        if(cb.team() != player.team()) continue;
                        if(cb.current == Blocks.air){
                            // это снос — просто убираем призрак
                            tile.setBlock(Blocks.air, cb.team, 0);
                        }else{
                            tile.setBlock(cb.current, cb.team, cb.rotation);
                        }
                        done++;
                    }
                }
                player.unit().validatePlans();
                MKUKICMod.toast("[accent]Достроено: " + done);
            }).minWidth(190f).height(34f).pad(2f);
        }).left().row();
        t.add("[darkgray]Завершает стройки из вашей очереди планов (только для своей команды).\nРесурсы не тратятся — песочница-режим.").left().top().wrap().width(330f).padLeft(6f).row();

        QolWindows.section(t, "Обломки и мусор");

        t.table(c -> {
            c.left();
            c.button("Снести обломки у курсора", MKUKICSkin.actBtn, () -> {
                if(!hostLike()){
                    MKUKICMod.toast("[scarlet]Только хост/одиночная игра");
                    return;
                }
                Vec2 m = Core.input.mouseWorld();
                int cx = World.toTile(m.x), cy = World.toTile(m.y);
                int rad = 30;
                int removed = 0;
                for(int x = cx - rad; x <= cx + rad; x++){
                    for(int y = cy - rad; y <= cy + rad; y++){
                        Tile tile = world.tile(x, y);
                        if(tile == null) continue;
                        if(tile.team() == Team.derelict && tile.block() != Blocks.air){
                            tile.setBlock(Blocks.air, Team.derelict, 0);
                            removed++;
                        }
                    }
                }
                MKUKICMod.toast("[accent]Снесено обломков: " + removed + " (61x61 у курсора)");
            }).minWidth(230f).height(34f).pad(2f);
        }).left().row();
        t.add("[darkgray]Убирает wreck-блоки (остатки разрушенных построек) вокруг курсора. Только хост — изменения локальные, на сервере нужен админ-плагин.").left().top().wrap().width(330f).padLeft(6f).row();

        QolWindows.section(t, "Рендер");

        t.table(c -> {
            c.left();
            c.check("Показывать чужие планы стройки", renderer != null && renderer.showOtherBuildPlans, v -> {
                if(renderer != null) renderer.showOtherBuildPlans = v;
            }).update(cb -> cb.setChecked(renderer != null && renderer.showOtherBuildPlans)).left().pad(2f);
        }).left().row();
        t.table(c -> {
            c.left();
            c.check("Показывать статус-оверлеи блоков", renderer != null && renderer.drawStatus, v -> {
                if(renderer != null) renderer.drawStatus = v;
            }).update(cb -> cb.setChecked(renderer != null && renderer.drawStatus)).left().pad(2f);
        }).left().row();
        t.add("[darkgray]Эти переключатели — прямые флаги рендера игры, действуют сразу. Полный набор — в окне «Камера / время / FPS».").left().top().wrap().width(330f).pad(4f, 6f, 0f, 6f).row();
    }

    private static boolean hostLike(){
        return !net.active() || net.server();
    }
}
