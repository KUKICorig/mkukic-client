package mkukic.client.qol;

import arc.*;
import arc.graphics.*;
import arc.math.*;
import arc.math.geom.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.util.*;
import mindustry.content.*;
import mindustry.core.*;
import mindustry.entities.units.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.input.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.environment.*;
import mkukic.*;
import mkukic.client.*;

import static mindustry.Vars.*;

/**
 * Инспектор объектов (порт «расширенной панели статуса» из MI2-Utilities:
 * местность + постройка + юнит одновременно, следует за курсором;
 * плюс «X-ray содержимого построек» — инвентарь/жидкости/энергия).
 *
 * Два режима: «следить за курсором» (по умолчанию) и «закрепить точку».
 * Опционально рисует компактную сводку прямо в мире у курсора.
 * Всё клиентское, читает только локальное состояние — мультиплеер не ломает.
 */
public class Inspector{
    // закреплённая точка (тайл); -1 = нет
    private static int lockTX = -1, lockTY = -1;

    // ======================= ЧИСТЫЕ ФУНКЦИИ (тестируются) =======================

    /** "123/456 (27%)" — компактная запись HP/ёмкости. */
    public static String pct(float cur, float max){
        if(max <= 0f) return (int)cur + "?";
        int p = Mathf.clamp((int)(cur / max * 100f), 0, 999);
        return (int)cur + "/" + (int)max + " (" + p + "%)";
    }

    /**
     * «Дистанция до юнита с учётом размера»: расстояние от точки до края хитбокса.
     * Используется для выбора юнита под курсором (меньшее = ближе).
     */
    public static float reach(float dx, float dy, float hitSize){
        return Math.max(0f, Mathf.dst(dx, dy) - hitSize / 2f);
    }

    /** Индекс ближайшего элемента в наборе троек {x, y, hitSize} или -1. */
    public static int closest(float[] xys, float wx, float wy, float maxReach){
        int best = -1;
        float bd = maxReach;
        for(int i = 0; i + 2 < xys.length; i += 3){
            float r = reach(xys[i] - wx, xys[i + 1] - wy, xys[i + 2]);
            if(r < bd){
                bd = r;
                best = i / 3;
            }
        }
        return best;
    }

    /**
     * Скорость потока: изменение счётчика за dFrames кадров -> единиц в секунду.
     * (60 кадров = 1 секунда игрового времени). Чистая функция, тестируется.
     */
    public static float flowRate(int dTotal, float dFrames){
        if(dFrames <= 0f) return 0f;
        return dTotal / (dFrames / 60f);
    }

    // семплер потока предметов (одно отслеживаемое здание за раз)
    private static int flowBid = -1;
    private static int flowTotal;
    private static float flowT;
    private static float flowRateVal;

    /** Подпись потока для отчёта: ед/с по выборкам раз в ~0.5с. */
    static String flowLabel(Building b){
        try{
            if(b.items == null) return "";
            int total = b.items.total();
            float now = Time.globalTime;
            if(b.id != flowBid){
                flowBid = b.id;
                flowTotal = total;
                flowT = now;
                flowRateVal = 0f;
                return "[darkgray]измерение…[]";
            }
            float df = now - flowT;
            if(df >= 30f){
                flowRateVal = flowRate(total - flowTotal, df);
                flowTotal = total;
                flowT = now;
            }
            String sign = flowRateVal > 0.05f ? "[lime]+" : flowRateVal < -0.05f ? "[scarlet]" : "[lightgray]";
            return sign + Strings.autoFixed(Math.abs(flowRateVal), 1) + "[] ед/с";
        }catch(Throwable e){
            return "";
        }
    }

    // ======================= СОСТОЯНИЕ =======================

    public static void reset(){
        lockTX = -1;
        lockTY = -1;
        flowBid = -1;
    }

    public static boolean isLocked(){
        return lockTX >= 0;
    }

    public static void lockHere(){
        Vec2 v = Core.input.mouseWorld();
        lockTX = World.toTile(v.x);
        lockTY = World.toTile(v.y);
        MKUKICSettings.s().inspFollow = false;
        MKUKICSettings.save();
        MKUKICMod.toast("[accent]" + (char)Iconc.ok + " [white]Инспектор закреплён: " + lockTX + ", " + lockTY);
    }

    public static void unlock(){
        lockTX = -1;
        lockTY = -1;
        MKUKICSettings.s().inspFollow = true;
        MKUKICSettings.save();
    }

    /** Тайл в фокусе (курсор или закрепление) или null. */
    static Tile focusTile(){
        if(state == null || state.isMenu() || world == null) return null;
        int tx, ty;
        if(MKUKICSettings.s().inspFollow || lockTX < 0){
            Vec2 v = Core.input.mouseWorld();
            tx = World.toTile(v.x);
            ty = World.toTile(v.y);
        }else{
            tx = lockTX;
            ty = lockTY;
        }
        return world.tile(tx, ty);
    }

    // ======================= ОТЧЁТ =======================

    /** Полный отчёт (местность + постройка + юнит + план). Никогда не бросает. */
    public static String report(){
        try{
            StringBuilder sb = new StringBuilder();
            Tile t = focusTile();
            if(t == null) return "[darkgray]Нет данных (загрузи мир)";

            // ---- местность ----
            sb.append("[accent]◆ Местность [darkgray](").append(t.x).append(", ").append(t.y).append(")[]\n");
            Floor f = t.floor();
            if(f != null){
                sb.append("  ").append(f.localizedName);
                if(f.isLiquid) sb.append(f.shallow ? " [sky](мелководье)" : "[sky](жидкость, глубоко)");
                if(!Mathf.equal(f.speedMultiplier, 1f, 0.01f)) sb.append(" [darkgray]скорость x").append(Strings.autoFixed(f.speedMultiplier, 2));
                if(f.damageTaken > 0f) sb.append(" [scarlet]урон");
                sb.append('\n');
            }
            Floor ov = t.overlay();
            if(ov != null && ov != Blocks.air) sb.append("  Покрытие: ").append(ov.localizedName).append('\n');
            Item drop = t.drop();
            if(drop != null) sb.append("  [gold]Руда: ").append(drop.localizedName).append('\n');

            // ---- постройка ----
            Building b = t.build;
            if(b != null && b.block != null){
                sb.append("[accent]◆ Постройка[]\n");
                sb.append("  ").append(b.block.localizedName)
                    .append(" [darkgray](").append(b.team != null ? b.team.localized() : "?").append(")[]\n");
                sb.append("  HP: ").append(pct(b.health(), b.maxHealth())).append('\n');
                sb.append("  ").append(b.enabled ? "[lime]включена" : "[scarlet]выключена").append("[]")
                    .append(", эффективность ").append((int)(b.efficiency * 100f)).append("%\n");
                // содержимое (X-ray)
                if(b.items != null){
                    StringBuilder it = new StringBuilder();
                    int shown = 0;
                    for(Item item : content.items()){
                        int n = b.items.get(item);
                        if(n > 0){
                            if(shown++ > 0) it.append(", ");
                            it.append(item.localizedName).append(' ').append(n);
                            if(shown >= 8){ it.append("…"); break; }
                        }
                    }
                    if(shown > 0) sb.append("  Предметы: ").append(it).append('\n');
                    sb.append("  Проток: ").append(flowLabel(b)).append('\n');
                }
                if(b.liquids != null){
                    StringBuilder li = new StringBuilder();
                    int shown = 0;
                    for(Liquid l : content.liquids()){
                        float n = b.liquids.get(l);
                        if(n > 0.5f){
                            if(shown++ > 0) li.append(", ");
                            li.append(l.localizedName).append(' ').append((int)n);
                            if(shown >= 6){ li.append("…"); break; }
                        }
                    }
                    if(shown > 0) sb.append("  Жидкости: ").append(li).append('\n');
                }
                if(b.power != null && b.power.graph != null){
                    var g = b.power.graph;
                    sb.append("  [lightgray]⚡[] ").append((int)g.getLastPowerProduced()).append("/")
                        .append((int)g.getLastPowerNeeded()).append(" ед/с, удовл. ")
                        .append((int)(g.getSatisfaction() * 100f)).append("%");
                    float cap = g.getBatteryCapacity();
                    if(cap > 0f) sb.append(", батарея ").append((int)g.getBatteryStored()).append("/").append((int)cap);
                    sb.append('\n');
                }
                Object cfg = b.config();
                if(cfg != null){
                    String cs = String.valueOf(cfg);
                    if(cs.length() > 48) cs = cs.substring(0, 48) + "…";
                    sb.append("  Конфиг: [lightgray]").append(cs).append("[]\n");
                }
                try{
                    Object p = b.getPayload();
                    if(p != null) sb.append("  Груз: ").append(p).append('\n');
                }catch(Throwable ignored){}
            }

            // ---- юнит у точки ----
            Unit u = unitAt(t);
            if(u != null){
                sb.append("[accent]◆ Юнит[]\n");
                sb.append("  ").append(u.type != null ? u.type.localizedName : "?")
                    .append(" [darkgray](").append(u.team != null ? u.team.localized() : "?").append(")[]\n");
                sb.append("  HP: ").append(pct(u.health(), u.maxHealth()));
                if(u.shield() > 0.5f) sb.append(" [sky]+щит ").append((int)u.shield());
                sb.append('\n');
                UnitController c = u.controller();
                if(c instanceof Player pl){
                    sb.append("  Управление: [accent]игрок ").append(pl.name).append("[]\n");
                }else if(c != null){
                    sb.append("  Управление: [darkgray]").append(c.getClass().getSimpleName()).append("[]\n");
                }
                if(u.stack != null && u.stack.amount > 0 && u.stack.item != null){
                    sb.append("  Несёт: ").append(u.stack.item.localizedName).append(" x").append(u.stack.amount).append('\n');
                }
                StringBuilder st = new StringBuilder();
                for(StatusEffect e : content.statusEffects()){
                    try{
                        if(u.hasEffect(e)){
                            if(st.length() > 0) st.append(", ");
                            st.append(e.localizedName);
                        }
                    }catch(Throwable ignored){}
                }
                if(st.length() > 0) sb.append("  Статусы: [lightgray]").append(st).append("[]\n");
            }

            // ---- мой план стройки на этом тайле ----
            try{
                if(player != null && player.unit() != null){
                    for(BuildPlan p : player.unit().plans()){
                        if(p.x == t.x && p.y == t.y && p.block != null){
                            sb.append("[accent]◆ План[]\n  ")
                                .append(p.block.localizedName)
                                .append(p.breaking ? " [scarlet](слом)" : "")
                                .append(", поворот ").append(p.rotation * 90).append("°")
                                .append(", готово ").append((int)(p.progress * 100f)).append("%\n");
                            break;
                        }
                    }
                }
            }catch(Throwable ignored){}

            return sb.toString();
        }catch(Throwable e){
            return "[scarlet]Ошибка инспектора: " + e.getClass().getSimpleName();
        }
    }

    /** Юнит под фокус-тайлом (в пределах ~2 тайлов от центра), или null. */
    static Unit unitAt(Tile t){
        float wx = t.worldx(), wy = t.worldy();
        Unit best = null;
        float bd = 16f; // 2 тайла от края хитбокса
        for(Unit u : Groups.unit){
            if(u == null || u.dead() || u.type == null) continue;
            float r = reach(u.x - wx, u.y - wy, u.hitSize);
            if(r < bd){
                bd = r;
                best = u;
            }
        }
        return best;
    }

    /** Короткая сводка для world-текста у курсора. */
    public static String shortReport(){
        try{
            Tile t = focusTile();
            if(t == null) return "";
            StringBuilder sb = new StringBuilder();
            Building b = t.build;
            if(b != null && b.block != null){
                sb.append(b.block.localizedName).append(' ')
                    .append((int)(b.health() / Math.max(1f, b.maxHealth()) * 100f)).append('%');
                if(b.items != null && b.items.total() > 0) sb.append(" | ").append(b.items.total()).append(" ед.");
            }
            Unit u = unitAt(t);
            if(u != null && u.type != null){
                if(sb.length() > 0) sb.append('\n');
                sb.append(u.type.localizedName).append(' ')
                    .append((int)(u.health() / Math.max(1f, u.maxHealth()) * 100f)).append('%');
            }
            if(sb.length() == 0 && t.drop() != null) sb.append(t.drop().localizedName);
            return sb.toString();
        }catch(Throwable e){
            return "";
        }
    }

    // ======================= WORLD-DRAW =======================

    /** Компактная сводка в мире у курсора (опция inspWorld). */
    public static void drawWorld(){
        try{
            if(state == null || state.isMenu()) return;
            MKUKICSettings s = MKUKICSettings.s();
            if(!s.enabled || !s.qolEnabled || !s.inspWorld) return;
            String txt = shortReport();
            if(txt.isEmpty()) return;
            Vec2 v = Core.input.mouseWorld();
            QolWindows.worldText(txt, v.x, v.y + 14f, Color.white);
        }catch(Throwable ignored){}
    }

    // ============================ ОКНО ============================

    public static void build(Table t){
        QolWindows.section(t, "Инспектор (панель статуса)");
        t.add("[lightgray]Местность + постройка (содержимое, энергия) + юнит (статусы,\n"
            + "кто управляет) + мой план стройки — одновременно. Порт «расширенной\n"
            + "панели статуса» и «X-ray содержимого» из MI2-Utilities.")
            .left().top().wrap().width(380f).pad(3f, 0f, 6f, 0f).row();

        t.check("Следить за курсором", MKUKICSettings.s().inspFollow, v -> {
            MKUKICSettings.s().inspFollow = v;
            if(v) lockTX = -1;
            MKUKICSettings.save();
        }).left().pad(2f).row();
        t.check("Короткая сводка в мире у курсора", MKUKICSettings.s().inspWorld, v -> {
            MKUKICSettings.s().inspWorld = v;
            MKUKICSettings.save();
        }).left().pad(2f).row();

        t.table(c -> {
            c.left();
            c.button((char)Iconc.zoom + " Закрепить здесь", MKUKICSkin.actBtn, Inspector::lockHere).minWidth(150f).height(32f).padRight(6f);
            c.button((char)Iconc.cancel + " Отпустить", MKUKICSkin.actBtn, Inspector::unlock).minWidth(120f).height(32f);
        }).left().pad(3f, 0f, 3f, 0f).row();

        t.pane(p -> p.top().left().label(Inspector::report).left().top().wrap().width(370f))
            .growX().maxHeight(Core.graphics.getHeight() * 0.42f).padTop(4f).row();
    }
}
