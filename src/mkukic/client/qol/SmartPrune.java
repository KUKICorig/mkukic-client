package mkukic.client.qol;

import arc.*;
import arc.math.*;
import arc.math.geom.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import mindustry.content.*;
import mindustry.core.*;
import mindustry.entities.units.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.ui.*;
import mindustry.world.*;
import mkukic.*;
import mkukic.client.*;

import static mindustry.Vars.*;

/**
 * Умный снос (порт «Auto pruning» из Neon) + полная очистка карты от обломков
 * (порт «Deep Cleaning» из Scheme-Size).
 *
 * Снос цепочки работает через ОБЫЧНЫЕ планы сноса юнита (BuildPlan(x,y) —
 * ванильный конструктор лома, breaking=true) — поэтому честен на любом сервере:
 * юнит реально подходит и ломает, ничего не телепортится.
 * Полная очистка обломков меняет мир напрямую — только хост/одиночная игра.
 */
public class SmartPrune{
    // ======================= ДЕЙСТВИЯ =======================

    static boolean hostLike(){
        try{
            return !net.active() || net.server();
        }catch(Throwable t){
            return false;
        }
    }

    static Unit unit(){
        try{
            if(player == null) return null;
            Unit u = player.unit();
            return (u != null && !u.dead()) ? u : null;
        }catch(Throwable t){
            return null;
        }
    }

    /** Блок под курсором (origin для мульти-тайловых) или null. */
    static Tile cursorTile(){
        Vec2 m = Core.input.mouseWorld();
        Tile t = world.tile(World.toTile(m.x), World.toTile(m.y));
        if(t == null) return null;
        Building b = t.build;
        return b != null ? b.tile : t;
    }

    /** Посчитать длину цепочки того же блока под курсором (ничего не ломает). */
    public static int countChain(){
        try{
            if(world == null || state.isMenu()) return -1;
            Tile t = cursorTile();
            if(t == null || t.block() == Blocks.air){
                MKUKICMod.toast("[scarlet]Курсор не на блоке");
                return 0;
            }
            Seq<Tile> chain = SmartUpgrade.collectChain(t.block(), t, cap());
            MKUKICMod.toast("[accent]Цепочка «" + t.block().localizedName + "»: " + chain.size
                + (chain.size >= cap() ? "+ [darkgray](упёрлась в лимит)" : ""));
            return chain.size;
        }catch(Throwable e){
            Log.err("[MKUKIC] SmartPrune: countChain", e);
            return -1;
        }
    }

    /** Снести связную цепочку того же блока под курсором (планами сноса). Возвращает число планов. */
    public static int pruneChain(){
        try{
            if(world == null || state.isMenu()) return -1;
            Unit u = unit();
            if(u == null){
                MKUKICMod.toast("[scarlet]Нет юнита");
                return 0;
            }
            Tile t = cursorTile();
            if(t == null || t.block() == Blocks.air){
                MKUKICMod.toast("[scarlet]Курсор не на блоке");
                return 0;
            }
            Block b = t.block();
            if(!b.canBreak(t)){
                MKUKICMod.toast("[scarlet]Этот блок нельзя сломать (статический контент карты)");
                return -2;
            }

            Seq<Tile> chain = SmartUpgrade.collectChain(b, t, cap());
            for(Tile o : chain){
                u.addBuild(new BuildPlan(o.x, o.y), false);
            }
            MKUKICMod.toast("[accent]" + (char)Iconc.eraser + " [white]Планов сноса: " + chain.size
                + " [lightgray](" + b.localizedName + ")[] — юнит сломает их как обычно");
            return chain.size;
        }catch(Throwable e){
            Log.err("[MKUKIC] SmartPrune: pruneChain", e);
            return -1;
        }
    }

    static int cap(){
        return Mathf.clamp(MKUKICSettings.s().pruneMax, 10, 5000);
    }

    /** Сколько планов сноса сейчас в очереди юнита. */
    public static int countBreaking(Seq<BuildPlan> plans){
        if(plans == null) return 0;
        int n = 0;
        for(BuildPlan p : plans) if(p != null && p.breaking) n++;
        return n;
    }

    /** Отменить ВСЕ планы сноса у своего юнита (планы стройки не трогает). */
    public static int cancelBreaks(){
        try{
            Unit u = unit();
            if(u == null){
                MKUKICMod.toast("[scarlet]Нет юнита");
                return 0;
            }
            Queue<BuildPlan> plans = u.plans();
            Seq<BuildPlan> copy = new Seq<>();
            for(BuildPlan p : plans) copy.add(p);
            int n = 0;
            for(BuildPlan p : copy){
                if(p.breaking){
                    plans.remove(p);
                    n++;
                }
            }
            MKUKICMod.toast(n > 0 ? "[accent]Отменено планов сноса: " + n : "[lightgray]Планов сноса не было");
            return n;
        }catch(Throwable e){
            Log.err("[MKUKIC] SmartPrune: cancelBreaks", e);
            return 0;
        }
    }

    /**
     * Deep Cleaning: убрать ВСЕ обломки (derelict) на карте. Меняет мир напрямую —
     * только хост/одиночная игра. Возвращает число убранных тайлов.
     */
    public static int cleanDerelictMap(){
        try{
            if(world == null || state.isMenu()) return -1;
            if(!hostLike()){
                MKUKICMod.toast("[scarlet]Только хост/одиночная игра");
                return 0;
            }
            int removed = 0;
            int w = world.width(), h = world.height();
            for(int y = 0; y < h; y++){
                for(int x = 0; x < w; x++){
                    Tile tile = world.tile(x, y);
                    if(tile == null) continue;
                    if(tile.team() == Team.derelict && tile.block() != Blocks.air){
                        tile.setBlock(Blocks.air, Team.derelict, 0);
                        removed++;
                    }
                }
            }
            MKUKICMod.toast("[accent]" + (char)Iconc.eraser + " [white]Обломков убрано: " + removed + " (вся карта)");
            return removed;
        }catch(Throwable e){
            Log.err("[MKUKIC] SmartPrune: cleanDerelictMap", e);
            return -1;
        }
    }

    // ============================ ОКНО ============================

    public static void build(Table t){
        QolWindows.section(t, "Умный снос (Auto Pruning)");
        t.add("[lightgray]Курсор на блок — сносится ВСЯ связная цепочка того же типа\n"
            + "(как Smart Upgrade, но наоборот). Работает через обычные планы сноса:\n"
            + "юнит реально подходит и ломает — честно на любом сервере.")
            .left().top().wrap().width(380f).pad(3f, 0f, 6f, 0f).row();

        t.table(c -> {
            c.left();
            c.button((char)Iconc.eraser + "  Снести цепочку", MKUKICSkin.actBtn, SmartPrune::pruneChain).minWidth(170f).height(36f).pad(2f);
            c.button("Посчитать", MKUKICSkin.actBtn, SmartPrune::countChain).minWidth(110f).height(36f).pad(2f);
            c.button("Отменить сносы", MKUKICSkin.actBtn, SmartPrune::cancelBreaks).minWidth(150f).height(36f).pad(2f);
        }).left().row();

        t.table(c -> {
            c.left();
            c.add("Лимит цепочки:").minWidth(140f).left();
            Label val = new Label("", Styles.outlineLabel);
            c.add(val).right().minWidth(60f).padRight(6f);
            c.slider(10f, 2000f, 10f, MKUKICSettings.s().pruneMax, v -> {
                MKUKICSettings.s().pruneMax = (int)v;
                MKUKICSettings.save();
            }).width(160f);
            val.setText(() -> "" + cap());
        }).left().padTop(4f).row();

        QolWindows.section(t, "Полная очистка (Deep Cleaning)");
        t.table(c -> {
            c.left();
            c.button((char)Iconc.cancel + "  Убрать все обломки на карте", MKUKICSkin.actBtn, SmartPrune::cleanDerelictMap)
                .minWidth(280f).height(36f).pad(2f).disabled(b -> !hostLike());
        }).left().row();
        t.add(hostLike()
            ? "[darkgray]Удаляет wreck-блоки (остатки разрушенных построек) команды derelict\nсо всей карты сразу. Только хост/SP — изменение мира напрямую."
            : "[scarlet]Недоступно: ты клиент на чужом сервере (нужен админ-плагин на стороне сервера).")
            .left().top().wrap().width(380f).pad(4f, 6f, 0f, 6f).row();
    }
}
