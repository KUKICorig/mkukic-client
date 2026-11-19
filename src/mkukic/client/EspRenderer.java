package mkukic.client;

import arc.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.math.*;
import arc.struct.*;
import arc.math.geom.*;
import arc.util.*;
import arc.util.pooling.*;
import mindustry.game.Teams.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.ui.*;
import arc.scene.ui.layout.*;
import mkukic.*;

import static mindustry.Vars.*;

/**
 * ESP / радар: подсветка врагов в мире, линия до цели автоприцела,
 * круги дальности, стрелки-радар по краю экрана и инфо-строка.
 */
public class EspRenderer{
    private static final Vec2 v = new Vec2();
    private static final Color tmpColor = new Color();
    private static int enemiesNear = 0;
    private static @Nullable Unit closestEnemyUnit;

    public static void reset(){
        trails.clear();
        enemiesNear = 0;
        closestEnemyUnit = null;
    }

    public static int enemiesNear(){
        return enemiesNear;
    }

    public static @Nullable Unit closestEnemy(){
        return closestEnemyUnit;
    }

    // ============================ МИРОВОЙ СЛОЙ =============================

    public static void draw(){
        MKUKICSettings s = MKUKICSettings.s();
        if(!s.enabled || player == null || state == null || state.isMenu()) return;
        if(s.espPlayers) drawPlayers(s);
        if(s.espTrails) drawTrails(s);
        if(!s.esp) return;

        Unit self = player.dead() ? null : player.unit();
        enemiesNear = 0;
        closestEnemyUnit = null;
        float closestDist = Float.MAX_VALUE;

        Draw.z(Layer.overlayUI);

        for(Unit u : Groups.unit){
            if(u == null || u.dead() || u.team() == player.team()) continue;
            if(s.espFog && u.inFogTo(player.team())) continue;

            enemiesNear++;
            if(self != null){
                float d = self.dst(u);
                if(d < closestDist){
                    closestDist = d;
                    closestEnemyUnit = u;
                }
            }

            float alpha = self == null ? 1f : Mathf.clamp(1.4f - self.dst(u) / 2400f, 0.25f, 1f);
            Color c = tmpColor.set(u.team().color).a(alpha);
            float size = Math.max(u.hitSize() * 1.5f, 16f);

            // рамка вокруг врага
            Draw.color(0f, 0f, 0f, 0.5f * alpha);
            Lines.stroke(3f);
            Lines.square(u.x, u.y, size / 2f + 1f, 45f);
            Draw.color(c);
            Lines.stroke(1.6f);
            Lines.square(u.x, u.y, size / 2f, 45f);

            // полоска HP
            if(s.espHealth){
                float w = size + 6f;
                float hf = Mathf.clamp(u.health() / u.maxHealth(), 0f, 1f);
                Draw.color(0f, 0f, 0f, 0.55f * alpha);
                Fill.rect(u.x, u.y - size / 2f - 6f, w, 3.4f);
                Draw.color(Color.scarlet.r, hf > 0.5f ? 0.85f : 0.3f, 0.25f, alpha);
                Fill.rect(u.x - (w - w * hf) / 2f, u.y - size / 2f - 6f, w * hf, 2.2f);
            }

            Draw.color();

            // имя + дистанция
            if(s.espNames || s.espDistance){
                StringBuilder sb = new StringBuilder();
                if(s.espNames) sb.append(u.type.localizedName == null ? u.type.name : u.type.localizedName);
                if(s.espDistance && self != null){
                    if(sb.length() > 0) sb.append(' ');
                    sb.append((int)(self.dst(u) / tilesize)).append('m');
                }
                text(sb.toString(), u.x, u.y + size / 2f + 9f, c);
            }

            // круг дальности
            if(s.espRangeCircles){
                Draw.color(c.r, c.g, c.b, 0.18f);
                Lines.stroke(1f);
                Lines.circle(u.x, u.y, Math.max(u.range(), 60f));
                Draw.color();
            }

            // линия от игрока
            if(s.espLines && self != null && !self.dead()){
                Draw.color(c.r, c.g, c.b, 0.22f);
                Lines.stroke(1f);
                Lines.line(self.x, self.y, u.x, u.y);
                Draw.color();
            }
        }

        // вражеские постройки
        if(s.espBuildings){
            for(TeamData data : state.teams.present){
                if(data.team == player.team()) continue;
                for(Building b : data.buildings){
                    if(b == null || b.dead) continue;
                    if(s.espFog && b.inFogTo(player.team())) continue;
                    Draw.color(data.team.color.r, data.team.color.g, data.team.color.b, 0.35f);
                    Lines.stroke(1f);
                    Lines.square(b.x, b.y, b.block.size * tilesize / 2f + 2f);
                }
            }
            Draw.color();
        }

        // линия до цели автоприцела
        if(s.showTargetLine && self != null && CombatFeatures.aimTarget != null){
            Teamc t = CombatFeatures.aimTarget;
            if(t instanceof Healthc hc && !hc.dead()){
                Draw.color(Pal.remove.r, Pal.remove.g, Pal.remove.b, 0.75f);
                Lines.stroke(1.4f);
                Lines.line(self.x, self.y, t.getX(), t.getY());
                Lines.square(t.getX(), t.getY(), 9f, 45f);
                Draw.color();
            }else{
                CombatFeatures.aimTarget = null;
            }
        }

        // круг радиуса автоприцела
        if(s.aimbot && self != null){
            float r = CombatFeatures.aimRange(self, s);
            Draw.color(Pal.accent.r, Pal.accent.g, Pal.accent.b, 0.16f);
            Lines.stroke(1f);
            Lines.circle(self.x, self.y, r);
            Draw.color();
        }

        Draw.reset();
    }

    // ======================= ЭКРАННЫЙ СЛОЙ (РАДАР) ========================

    public static void drawScreen(){
        MKUKICSettings s = MKUKICSettings.s();
        if(!s.enabled || !s.esp || !s.espRadar) return;
        if(player == null || state == null || state.isMenu()) return;
        if(Core.scene != null && (Core.scene.hasField() || Core.scene.hasDialog())) return;

        Unit self = player.dead() ? null : player.unit();
        float cx = Core.camera.position.x, cy = Core.camera.position.y;
        float w = Core.graphics.getWidth(), h = Core.graphics.getHeight();
        float rad = Math.max(120f, s.espRadarRadius);
        float ring = Math.min(w, h) * 0.42f;

        for(Unit u : Groups.unit){
            if(u == null || u.dead() || u.team() == player.team()) continue;
            if(s.espFog && u.inFogTo(player.team())) continue;

            float dx = u.x - (self == null ? cx : self.x);
            float dy = u.y - (self == null ? cy : self.y);
            float dist = Mathf.len(dx, dy);
            if(dist < rad * 0.05f) continue;

            float k = Math.min(dist / rad, 1f) * ring;
            float ang = Angles.angle(0f, 0f, dx, dy);
            float px = w / 2f + Angles.trnsx(ang, k);
            float py = h / 2f + Angles.trnsy(ang, k);

            boolean onScreen = px > 30f && px < w - 30f && py > 30f && py < h - 30f;
            float alpha = onScreen ? 0.35f : 0.9f;
            Color c = tmpColor.set(u.team().color).a(alpha);

            Draw.color(c);
            if(!onScreen){
                Fill.tri(
                    px + Angles.trnsx(ang, 9f), py + Angles.trnsy(ang, 9f),
                    px + Angles.trnsx(ang + 140f, 7f), py + Angles.trnsy(ang + 140f, 7f),
                    px + Angles.trnsx(ang - 140f, 7f), py + Angles.trnsy(ang - 140f, 7f));
            }else{
                Fill.square(px, py, 3f, 45f);
            }
        }
        Draw.color();
        Draw.reset();
    }

    // ================================ ТЕКСТ =================================

    // ============================ ИГРОКИ ============================

    private static void drawPlayers(MKUKICSettings s){
        Draw.z(Layer.overlayUI);
        String[] white = s.espWhitelist.isEmpty() ? null : s.espWhitelist.split(",");
        Unit self = player.dead() ? null : player.unit();
        for(Player p : Groups.player){
            if(p == null || p == player || p.unit() == null || p.unit().dead()) continue;
            if(s.espPlayerTeam >= 0 && p.team().id != s.espPlayerTeam) continue;
            if(white != null){
                boolean ok = false;
                for(String w : white) if(w.trim().equals(p.name())) ok = true;
                if(!ok) continue;
            }
            Unit u = p.unit();
            Color c = p.team().color;
            Draw.color(c);
            Lines.stroke(2f);
            Lines.square(u.x, u.y, u.hitSize / 2f + 5f);
            Draw.reset();
            String label = p.name();
            if(self != null && s.espDistance) label += " [lightgray]" + (int)(u.dst(self) / tilesize) + " бл.";
            text(label, u.x, u.y + u.hitSize / 2f + 12f, c);
        }
    }

    // ====================== МАРШРУТЫ ВРАГОВ ======================

    private static final IntMap<Seq<Vec2>> trails = new IntMap<>();
    private static float lastTrail = -999f;

    private static void drawTrails(MKUKICSettings s){
        if(Time.time - lastTrail > 6f){
            lastTrail = Time.time;
            if(trails.size > 500) trails.clear();
            for(Unit u : Groups.unit){
                if(u == null || u.dead() || u.team() == player.team()) continue;
                if(player.unit() != null && !u.within(player.unit(), s.espRadarRadius)) continue;
                Seq<Vec2> tr = trails.get(u.id);
                if(tr == null){
                    tr = new Seq<>();
                    trails.put(u.id, tr);
                }
                tr.add(new Vec2(u.x, u.y));
                while(tr.size > s.espTrailLen) tr.remove(0);
            }
        }
        Draw.z(Layer.overlayUI);
        for(IntMap.Entry<Seq<Vec2>> e : trails){
            Unit u = Groups.unit.getByID(e.key);
            if(u == null || u.dead()) continue;
            Seq<Vec2> tr = e.value;
            if(tr.size < 2) continue;
            Color c = tmpColor.set(u.team().color);
            c.a = 0.55f;
            Lines.stroke(1.6f, c);
            for(int i = 1; i < tr.size; i++){
                Vec2 a = tr.get(i - 1), b = tr.get(i);
                float fade = i / (float)tr.size;
                c.a = 0.15f + 0.5f * fade;
                Lines.line(a.x, a.y, b.x, b.y);
            }
            // стрелка направления в конце
            Vec2 a = tr.get(tr.size - 2), b = tr.get(tr.size - 1);
            float ang = b.sub(a).angle();
            c.a = 0.8f;
            Lines.line(b.x, b.y, b.x + Angles.trnsx(ang + 150f, 8f), b.y + Angles.trnsy(ang + 150f, 8f));
            Lines.line(b.x, b.y, b.x + Angles.trnsx(ang - 150f, 8f), b.y + Angles.trnsy(ang - 150f, 8f));
        }
        Draw.reset();
    }

    public static void text(String text, float x, float y, Color color){
        if(text == null || text.isEmpty()) return;
        Font font = Fonts.outline;
        GlyphLayout layout = Pools.obtain(GlyphLayout.class, GlyphLayout::new);
        boolean ints = font.usesIntegerPositions();
        font.setUseIntegerPositions(false);
        font.getData().setScale(0.25f / Scl.scl(1f));
        layout.setText(font, text);
        font.setColor(color);
        font.draw(text, x, y, 0, Align.center, false);
        Draw.reset();
        Pools.free(layout);
        font.getData().setScale(1f);
        font.setColor(Color.white);
        font.setUseIntegerPositions(ints);
    }
}
