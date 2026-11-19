package mkukic.client.qol;

import arc.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.math.*;
import arc.math.geom.*;
import arc.util.*;
import mindustry.content.*;
import mindustry.core.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.world.*;
import mindustry.world.blocks.defense.*;
import mindustry.world.blocks.defense.turrets.*;
import mindustry.world.blocks.units.*;
import mkukic.*;

import static mindustry.Vars.*;

/**
 * Визуальный оверлей (порт функций Helium):
 *  - HP/щит-бары над юнитами в реальном времени, многослойные щиты со счётчиком стеков
 *    (когда щитов больше макс. HP — показываем «xN»);
 *  - индикаторы дальности атаки: турель/юнит под курсором, выделенные юниты, свой юнит;
 *  - зоны действия сервисных блоков (ремонтники, овердрайвы, силовые проекторы) — разными цветами.
 * Независимо от ESP: рисуется даже когда ESP выключен.
 */
public class RangeOverlay{
    private static final Rect viewRect = new Rect();
    private static final Color tmp = new Color();

    public static void draw(){
        MKUKICSettings s = MKUKICSettings.s();
        if(!s.enabled || !s.qolEnabled) return;
        if(player == null || state == null || state.isMenu()) return;
        if(!s.qolHpBars && !s.qolRanges && !s.qolServiceZones) return;
        if(Core.scene != null && Core.scene.hasDialog()) return;

        Draw.z(Layer.overlayUI);

        Core.camera.bounds(viewRect);
        float pad = 120f;
        viewRect.grow(pad);

        if(s.qolServiceZones) drawServiceZones();
        if(s.qolRanges) drawRanges(s);
        if(s.qolHpBars) drawHealthBars(s);

        Draw.reset();
    }

    // ============================ HP / ЩИТЫ ============================

    private static void drawHealthBars(MKUKICSettings s){
        Team my = player.team();
        for(Unit u : Groups.unit){
            if(u == null || u.dead()) continue;
            boolean enemy = u.team() != my;
            if(!s.qolHpBarsAll && !enemy) continue;
            if(enemy && u.inFogTo(my)) continue;
            if(!viewRect.contains(u.x, u.y)) continue;
            // свой юнит и так имеет ванильный бар — не дублируем
            if(u == player.unit()) continue;

            float size = Math.max(u.hitSize(), 14f);
            float w = size + 6f;
            float yTop = u.y + size / 2f + 5f;
            float hf = Mathf.clamp(u.health() / u.maxHealth(), 0f, 1f);

            // подложка
            Draw.color(0f, 0f, 0f, 0.55f);
            Fill.rect(u.x, yTop, w, 3.2f);

            // HP: зелёный→красный
            Draw.color(tmp.set(Color.scarlet).lerp(Pal.health, hf).a(0.95f));
            Fill.rect(u.x - (w - w * hf) / 2f, yTop, w * hf, 2.2f);

            // щиты
            float sh = u.shield();
            if(sh > 0.5f){
                float sf = Mathf.clamp(sh / u.maxHealth(), 0f, 1f);
                Draw.color(tmp.set(Color.sky).a(0.85f));
                Fill.rect(u.x - (w - w * sf) / 2f, yTop + 3.2f, w * sf, 2f);

                // многослойные щиты: стеки = shield / maxHealth
                if(s.qolShieldStacks && sh > u.maxHealth()){
                    int stacks = Mathf.ceil(sh / u.maxHealth());
                    QolWindows.worldText("x" + stacks, u.x, yTop + 11f, tmp.set(Color.sky));
                }
            }
            Draw.color();
        }
    }

    // ============================ ДАЛЬНОСТИ ============================

    private static void drawRanges(MKUKICSettings s){
        if(world == null) return;
        Vec2 mouse = Core.input.mouseWorld();

        // 1) блок под курсором
        Tile t = world.tile(World.toTile(mouse.x), World.toTile(mouse.y));
        if(t != null && t.build != null){
            rangeOfBuild(t.build, Pal.accent, 0.9f);
        }

        // 2) юнит под курсором (враг или свой)
        Unit hovered = null;
        float best = 10f * 10f;
        for(Unit u : Groups.unit){
            if(u == null || u.dead()) continue;
            float d = u.dst2(mouse);
            if(d < best && d < u.hitSize() * u.hitSize() + 64f){
                best = d;
                hovered = u;
            }
        }
        if(hovered != null && hovered.type != null && hovered.type.range > 1f){
            Color c = tmp.set(hovered.team() == player.team() ? Pal.accent : Color.scarlet);
            circleRange(hovered.x, hovered.y, hovered.type.range, c, 0.5f);
            QolWindows.worldText(Strings.autoFixed(hovered.type.range / tilesize, 1) + " тайлов",
                hovered.x, hovered.y + hovered.type.range + 12f, c);
        }

        // 3) выделенные юниты (vanilla-выделение под команды)
        if(control != null && control.input != null && control.input.selectedUnits != null){
            for(Unit u : control.input.selectedUnits){
                if(u == null || u.dead() || u.type == null || u.type.range <= 1f) continue;
                circleRange(u.x, u.y, u.type.range, tmp.set(u.team().color), 0.35f);
            }
        }

        // 4) свой юнит
        Unit self = player.dead() ? null : player.unit();
        if(self != null && self.type != null && self.type.range > 1f){
            circleRange(self.x, self.y, self.type.range, tmp.set(Pal.accent).a(0.5f), 0.22f);
        }
    }

    private static void rangeOfBuild(Building b, Color c, float alpha){
        float range = -1f;
        if(b instanceof Turret.TurretBuild){
            range = ((Turret.TurretBuild)b).range();
        }else if(b.block instanceof RepairTurret){
            range = ((RepairTurret)b.block).repairRadius;
        }else if(b.block instanceof MendProjector){
            range = ((MendProjector)b.block).range;
        }else if(b.block instanceof OverdriveProjector){
            range = ((OverdriveProjector)b.block).range;
        }else if(b.block instanceof ForceProjector){
            range = ((ForceProjector)b.block).radius;
        }
        if(range > 1f) circleRange(b.x, b.y, range, tmp.set(c).a(alpha), 0.3f);
    }

    // ============================ СЕРВИСНЫЕ ЗОНЫ ============================

    private static void drawServiceZones(){
        for(Building b : Groups.build){
            if(b == null || b.team() == Team.derelict) continue;
            if(!viewRect.contains(b.x, b.y)) continue;
            Color c = null;
            float range = -1f;
            if(b.block instanceof RepairTurret){
                range = ((RepairTurret)b.block).repairRadius;
                c = Pal.heal;
            }else if(b.block instanceof MendProjector){
                range = ((MendProjector)b.block).range;
                c = Pal.heal;
            }else if(b.block instanceof OverdriveProjector){
                range = ((OverdriveProjector)b.block).range;
                c = Color.sky;
            }else if(b.block instanceof ForceProjector){
                range = ((ForceProjector)b.block).radius;
                c = Pal.shield;
            }
            if(c == null || range <= 1f) continue;
            if(b.team() != player.team()) c = tmp.set(c).mul(0.7f);

            // мягкая заливка + контур
            Draw.color(tmp.set(c).a(0.05f));
            Fill.circle(b.x, b.y, range);
            Draw.color(tmp.set(c).a(0.35f));
            Lines.stroke(1.2f);
            Lines.circle(b.x, b.y, range);
        }
        Draw.color();
    }

    private static void circleRange(float x, float y, float range, Color c, float fillAlpha){
        Draw.color(tmp.set(c).a(fillAlpha * 0.25f));
        Fill.circle(x, y, range);
        Draw.color(tmp.set(c).a(fillAlpha + 0.25f));
        Lines.stroke(1.4f);
        Lines.circle(x, y, range);
        Draw.color();
    }
}
