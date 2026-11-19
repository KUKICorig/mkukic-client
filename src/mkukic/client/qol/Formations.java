package mkukic.client.qol;

import arc.*;
import arc.math.*;
import arc.math.geom.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import mindustry.gen.*;
import mindustry.input.*;
import mindustry.ui.*;
import mindustry.world.*;
import mkukic.*;
import mkukic.client.*;

import static mindustry.Vars.*;

/**
 * RTS-формации и focus-fire (порт Neon).
 * Выбранные юниты (ванильное выделение рамкой/кликом) получают приказ двигаться
 * в точку под курсором, распределяясь по формации: линия, колонна, клин, квадрат, круг.
 * Focus-fire — приказ атаковать врага под курсором всем выделенным.
 *
 * Приказы уходят через ванильный {@code InputHandler.commandUnits(...)} — тот же путь,
 * что и у правого клика мыши, поэтому работает и в мультиплеере (сервер валидирует сам).
 * Вся геометрия — чистые функции, покрыты тестом FeatureTest.
 */
public class Formations{
    public static final String[] types = {"line", "column", "wedge", "square", "circle"};
    public static final String[] typeNames = {"Линия", "Колонна", "Клин", "Квадрат", "Круг"};
    public static final int maxUnits = 80;

    private static final Vec2 tmp = new Vec2();

    // ============================ ЧИСТАЯ ГЕОМЕТРИЯ ============================

    /**
     * Точки формации. Возвращает [x0,y0,x1,y1,...] вокруг (cx,cy);
     * (fx,fy) — направление «вперёд» (ненормализованное, допускает 0).
     */
    public static float[] positions(String type, int n, float cx, float cy, float fx, float fy, float spacing){
        float[] out = new float[Math.max(0, n) * 2];
        if(n <= 0) return out;
        float len = Mathf.len(fx, fy);
        if(len < 0.0001f){ fx = 1f; fy = 0f; } else { fx /= len; fy /= len; }
        float px = -fy, py = fx; // перпендикуляр (слева от направления)
        spacing = Math.max(1f, spacing);

        switch(type == null ? "line" : type){
            case "column": {
                for(int i = 0; i < n; i++){
                    out[i * 2]     = cx - fx * spacing * i;
                    out[i * 2 + 1] = cy - fy * spacing * i;
                }
                break;
            }
            case "wedge": {
                // лидер впереди, остальные — два крыла сзади
                out[0] = cx + fx * spacing * 0.5f;
                out[1] = cy + fy * spacing * 0.5f;
                for(int i = 1; i < n; i++){
                    int side = (i % 2 == 1) ? 1 : -1;
                    int k = (i + 1) / 2;
                    out[i * 2]     = cx - fx * spacing * k * 0.8f + px * spacing * k * side;
                    out[i * 2 + 1] = cy - fy * spacing * k * 0.8f + py * spacing * k * side;
                }
                break;
            }
            case "square": {
                int side = (int)Math.ceil(Math.sqrt(n));
                for(int i = 0; i < n; i++){
                    int gx = i % side, gy = i / side;
                    float ox = (gx - (side - 1) / 2f) * spacing;
                    float oy = (gy - (side - 1) / 2f) * spacing;
                    // oy — вдоль направления, ox — поперёк
                    out[i * 2]     = cx + fx * oy + px * ox;
                    out[i * 2 + 1] = cy + fy * oy + py * ox;
                }
                break;
            }
            case "circle": {
                float r = Math.max(spacing, spacing * n / (2f * Mathf.pi));
                for(int i = 0; i < n; i++){
                    float a = i * 360f / n;
                    out[i * 2]     = cx + Angles.trnsx(a, r);
                    out[i * 2 + 1] = cy + Angles.trnsy(a, r);
                }
                break;
            }
            default: { // line
                for(int i = 0; i < n; i++){
                    float off = (i - (n - 1) / 2f) * spacing;
                    out[i * 2]     = cx + px * off;
                    out[i * 2 + 1] = cy + py * off;
                }
            }
        }
        return out;
    }

    /** Жадейное назначение «ближайший юнит -> ближайший свободный слот». Возвращает слот для каждого юнита. */
    public static int[] assign(float[] unitXY, float[] slots){
        int n = unitXY.length / 2;
        int[] res = new int[n];
        boolean[] used = new boolean[slots.length / 2];
        for(int i = 0; i < n; i++){
            int best = -1;
            float bd = Float.MAX_VALUE;
            for(int j = 0; j < used.length; j++){
                if(used[j]) continue;
                float d = Mathf.dst2(unitXY[i * 2], unitXY[i * 2 + 1], slots[j * 2], slots[j * 2 + 1]);
                if(d < bd){
                    bd = d;
                    best = j;
                }
            }
            res[i] = best;
            if(best >= 0) used[best] = true;
        }
        return res;
    }

    public static String nameOf(String type){
        for(int i = 0; i < types.length; i++) if(types[i].equals(type)) return typeNames[i];
        return type == null ? "?" : type;
    }

    // ============================ ПРИКАЗЫ ============================

    public static int selectedCount(){
        return control != null && control.input != null ? control.input.selectedUnits.size : 0;
    }

    /** Отправить выделенных юнитов в точку под курсором выбранной формацией. */
    public static void moveToCursor(){
        moveToCursor(MKUKICSettings.s().formType);
    }

    public static void moveToCursor(String type){
        if(headless || control == null || control.input == null || player == null) return;
        Seq<Unit> units = control.input.selectedUnits;
        if(units.size == 0){
            MKUKICMod.toast("[scarlet]Юниты не выделены[] — выдели рамкой (как для обычных приказов)");
            return;
        }
        applyAt(type, units, Core.input.mouseWorldX(), Core.input.mouseWorldY());
    }

    public static void applyAt(String type, Seq<Unit> units, float tx, float ty){
        int n = Math.min(units.size, maxUnits);
        float cx = 0f, cy = 0f;
        for(int i = 0; i < n; i++){
            cx += units.get(i).x;
            cy += units.get(i).y;
        }
        cx /= n;
        cy /= n;
        float sp = Mathf.clamp(MKUKICSettings.s().formSpacing, 8f, 96f);
        float[] slots = positions(type, n, tx, ty, tx - cx, ty - cy, sp);
        float[] uxy = new float[n * 2];
        for(int i = 0; i < n; i++){
            uxy[i * 2] = units.get(i).x;
            uxy[i * 2 + 1] = units.get(i).y;
        }
        int[] slotOf = assign(uxy, slots);
        for(int i = 0; i < n; i++){
            Unit u = units.get(i);
            int s = slotOf[i];
            if(s < 0) continue;
            InputHandler.commandUnits(player, new int[]{u.id}, null, null,
                tmp.set(slots[s * 2], slots[s * 2 + 1]), false, true);
        }
        MKUKICMod.toast("[accent]Формация «" + nameOf(type) + "»[]: " + n + " юнитов"
            + (units.size > n ? " [lightgray](лимит " + maxUnits + ")" : ""));
    }

    /** Focus-fire: все выделенные атакуют врага (юнита или постройку) под курсором. */
    public static void focusFire(){
        if(headless || control == null || control.input == null || player == null) return;
        Seq<Unit> units = control.input.selectedUnits;
        if(units.size == 0){
            MKUKICMod.toast("[scarlet]Юниты не выделены");
            return;
        }
        float mx = Core.input.mouseWorldX(), my = Core.input.mouseWorldY();
        Unit enemy = null;
        try{
            enemy = control.input.selectedEnemyUnit(mx, my);
        }catch(Throwable ignored){}
        Building b = world != null ? world.buildWorld(mx, my) : null;
        Building enemyBuild = (b != null && b.team != player.team()) ? b : null;
        if(enemy == null && enemyBuild == null){
            MKUKICMod.toast("[scarlet]Под курсором нет врага");
            return;
        }
        int n = Math.min(units.size, 200);
        int[] ids = new int[n];
        for(int i = 0; i < n; i++) ids[i] = units.get(i).id;
        Vec2 pos = enemy != null ? tmp.set(enemy.x, enemy.y) : tmp.set(mx, my);
        InputHandler.commandUnits(player, ids, enemyBuild, enemy, pos, false, false);
        MKUKICMod.toast("[scarlet]" + (char)mindustry.gen.Iconc.commandAttack + "[] Огонь по цели: " + n + " юнитов");
    }

    // ============================ ОКНО ============================

    public static void build(Table t){
        QolWindows.section(t, "Формации (Neon RTS)");
        t.add("[lightgray]Выдели юнитов рамкой (как для обычных приказов), наведи курсор\n"
            + "на точку карты и жми кнопку/хоткей. Приказы уходят тем же путём, что и\n"
            + "правый клик мыши, — работают в мультиплеере.").left().top().wrap().width(340f).pad(3f, 0f, 6f, 0f).row();

        t.table(c -> {
            c.left();
            c.add("Формация:").padRight(6f).left();
        }).left().row();
        t.table(c -> {
            c.left();
            for(int i = 0; i < types.length; i++){
                final String type = types[i];
                c.button(typeNames[i], MKUKICSkin.toggleBtn, () -> {
                    MKUKICSettings.s().formType = type;
                    MKUKICSettings.save();
                }).checked(b -> MKUKICSettings.s().formType.equals(type)).minWidth(84f).height(32f).pad(2f);
                if(i == 2) c.row();
            }
        }).left().row();

        t.table(c -> {
            c.left();
            c.add("Интервал:").minWidth(90f).left();
            Label val = new Label("", Styles.outlineLabel);
            c.add(val).right().minWidth(70f).padRight(6f);
            c.slider(8f, 64f, 4f, MKUKICSettings.s().formSpacing, v -> {
                MKUKICSettings.s().formSpacing = v;
                MKUKICSettings.save();
            }).width(160f);
            val.setText(() -> ((int)(MKUKICSettings.s().formSpacing / 8f)) + " тайлов");
        }).left().padTop(4f).row();

        t.table(c -> {
            c.left();
            c.button((char)mindustry.gen.Iconc.commandRally + " К курсору (F3)", MKUKICSkin.actBtn, Formations::moveToCursor)
                .minWidth(200f).height(36f).pad(2f);
        }).left().padTop(6f).row();
        t.table(c -> {
            c.left();
            c.button((char)mindustry.gen.Iconc.commandAttack + " Focus-fire по врагу", MKUKICSkin.actBtn, Formations::focusFire)
                .minWidth(200f).height(36f).pad(2f);
        }).left().row();
        t.label(() -> "[lightgray]Выделено юнитов: [white]" + selectedCount())
            .left().padTop(6f).row();
        t.label(() -> "[darkgray]Текущая формация: " + nameOf(MKUKICSettings.s().formType))
            .left().padTop(2f).row();
    }
}
