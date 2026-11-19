package mkukic.client.qol;

import arc.*;
import arc.graphics.*;
import arc.scene.ui.*;
import arc.struct.*;
import arc.scene.ui.layout.*;
import arc.util.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.logic.*;
import mindustry.ui.*;
import mkukic.*;
import mkukic.client.*;

import static mindustry.Vars.*;

/**
 * Трекер курсоров игроков (v1.12). Два варианта меток:
 *
 * 1) ЛОКАЛЬНЫЕ — слой маркеров мода (MapMarkers): видны только тебе, работают
 *    всегда (любой сервер), хранятся в разрезе карты, цвет = цвет игрока.
 *
 * 2) ГЛОБАЛЬНЫЕ — штатные маркеры игры (state.markers, как у мир-процессоров):
 *    их видят ВСЕ игроки. Техническое ограничение ванили: создавать/обновлять
 *    маркеры может только одиночная игра или хост/сервер (Call.createMarker на
 *    чистом клиенте — no-op, серверного обработчика пакета нет). Чистым клиентам
 *    окно честно сообщает об этом и предлагает локальный вариант.
 *
 * Курсор чужого игрока берётся из Player.mouseX/mouseY — эти поля синхронизируются
 * по сети ванильным sync-механизмом (сверено с байткодом readSyncManual/writeSyncManual).
 */
public class CursorTracker{
    /** Одна запись слежения. */
    public static class Track{
        public String name = "";
        public boolean local;      // локальная метка включена
        public boolean globalOn;   // глобальная метка включена
        public int gid = -1;       // id глобального маркера (пара: gid = круг, gid+1 = текст)
        public float lx, ly;       // последние известные координаты курсора
        public boolean seen;       // игрок сейчас виден в Groups.player
    }

    public static final Seq<Track> tracks = new Seq<>();

    /** База id глобальных маркеров (далеко от id мир-процессоров) и размер диапазона. */
    public static final int idBase = 880000;
    public static final int idSlots = 100;   // по 2 id на слот: круг + текст

    private static int tickCount;
    private static Table bodyRef;
    private static String lastSig = "";

    // ======================= ЧИСТЫЕ ФУНКЦИИ (тестируются) =======================

    /** Глобальные маркеры доступны: одиночная игра или хост/сервер. */
    public static boolean canGlobal(boolean netActive, boolean netServer){
        return !netActive || netServer;
    }

    public static boolean globalAllowed(){
        try{
            return canGlobal(net.active(), net.server());
        }catch(Throwable t){
            return false;
        }
    }

    /** id маркера (круг) для слота; текст всегда id+1. */
    public static int markerId(int slot){
        return idBase + ((slot % idSlots) + idSlots) % idSlots * 2;
    }

    /** Первый свободный слот в used[] или -1. */
    public static int freeSlot(boolean[] used){
        if(used == null) return -1;
        for(int i = 0; i < used.length; i++) if(!used[i]) return i;
        return -1;
    }

    /** Подпись состава игроков (порядок важен) — для детектора изменений списка. */
    public static String sig(String[] names){
        StringBuilder sb = new StringBuilder();
        if(names != null) for(String n : names){
            if(n == null) continue;
            if(sb.length() > 0) sb.append('\n');
            sb.append(n);
        }
        return sb.toString();
    }

    public static boolean sigChanged(String a, String b){
        return a == null ? b != null : !a.equals(b);
    }

    // ======================= СОСТОЯНИЕ =======================

    public static Track find(String name){
        for(Track t : tracks) if(t.name.equals(name)) return t;
        return null;
    }

    static Track obtain(String name){
        Track t = find(name);
        if(t == null){
            t = new Track();
            t.name = name;
            tracks.add(t);
        }
        return t;
    }

    static void dropIfIdle(Track t){
        if(t != null && !t.local && !t.globalOn) tracks.remove(t);
    }

    public static void reset(){
        // новый мир: глобальные маркеры прежней карты чистим напрямую (без сети),
        // записи слежения сбрасываем
        try{
            if(state != null && state.markers != null){
                for(int id = idBase; id < idBase + idSlots * 2; id++){
                    if(state.markers.has(id)) state.markers.remove(id);
                }
            }
        }catch(Throwable ignored){}
        tracks.clear();
        tickCount = 0;
        lastSig = "";
    }

    // ======================= ЛОКАЛЬНЫЕ МЕТКИ =======================

    public static void toggleLocal(String name){
        Player p = MapMarkers.byName(name);
        if(p == null){
            MKUKICMod.toast("[scarlet]Игрок не найден: " + name);
            return;
        }
        Track t = obtain(name);
        t.local = !t.local;
        if(t.local){
            String key = MapMarkers.currentKey();
            if(key == null){
                t.local = false;
                dropIfIdle(t);
                MKUKICMod.toast("[scarlet]Локальные метки доступны только в игре");
                return;
            }
            MapMarkers.Marker m = new MapMarkers.Marker();
            m.map = key;
            m.x = p.mouseX;
            m.y = p.mouseY;
            m.text = p.name;
            m.track = p.name;
            try{ m.trackColor = p.color != null ? p.color.toString() : ""; }catch(Throwable ignored){}
            m.color = MKUKICSettings.s().markerColor;
            m.circle = false;
            t.lx = m.x;
            t.ly = m.y;
            MapMarkers.all.add(m);
            MapMarkers.save();
            MapMarkers.refresh();
            MKUKICMod.toast("[accent]" + (char)Iconc.eye + " [white]Локальная метка на " + p.name + " (видна только тебе)");
        }else{
            removeLocalMarkers(name);
            MKUKICMod.toast("[lightgray]Локальная метка снята: " + name);
        }
        dropIfIdle(t);
    }

    static void removeLocalMarkers(String name){
        String key = MapMarkers.currentKey();
        for(int i = MapMarkers.all.size - 1; i >= 0; i--){
            MapMarkers.Marker m = MapMarkers.all.get(i);
            if(name.equals(m.track) && (key == null || key.equals(m.map))){
                MapMarkers.all.remove(i);
            }
        }
        MapMarkers.save();
        MapMarkers.refresh();
    }

    // ======================= ГЛОБАЛЬНЫЕ МЕТКИ =======================

    public static void toggleGlobal(String name){
        Player p = MapMarkers.byName(name);
        if(p == null){
            MKUKICMod.toast("[scarlet]Игрок не найден: " + name);
            return;
        }
        if(!globalAllowed()){
            MKUKICMod.toast("[scarlet]Глобальные метки создаёт только хост/одиночная игра (ограничение ванили: у клиента нет канала для маркеров).[] Используй локальную метку — она видна тебе и работает на любом сервере.");
            return;
        }
        Track t = obtain(name);
        if(!t.globalOn){
            if(createGlobal(t, p)){
                t.globalOn = true;
                MKUKICMod.toast("[accent]" + (char)Iconc.players + " [white]Глобальная метка на " + p.name + " (видят все игроки)");
            }
        }else{
            removeGlobal(t);
            t.globalOn = false;
            MKUKICMod.toast("[lightgray]Глобальная метка снята: " + name);
        }
        dropIfIdle(t);
    }

    static boolean createGlobal(Track t, Player p){
        try{
            if(state == null || state.markers == null) return false;
            // свободный слот: оба id (круг и текст) не заняты — ни нами, ни мир-процессорами
            boolean[] used = new boolean[idSlots];
            for(int i = 0; i < idSlots; i++){
                int id = markerId(i);
                used[i] = state.markers.has(id) || state.markers.has(id + 1);
            }
            int slot = freeSlot(used);
            if(slot < 0){
                MKUKICMod.toast("[scarlet]Нет свободных id для глобальных меток (>" + idSlots + ")");
                return false;
            }
            int id = markerId(slot);
            t.lx = p.mouseX;
            t.ly = p.mouseY;

            MapObjectives.ShapeMarker sm = new MapObjectives.ShapeMarker(t.lx, t.ly);
            sm.radius = 6f;
            sm.fill = true;
            sm.stroke = 2f;
            sm.outline = false;
            sm.sides = 0; // 0 = круг
            sm.color = p.color != null ? new Color(p.color) : new Color(Color.white);

            MapObjectives.TextMarker tm = new MapObjectives.TextMarker(p.name, t.lx, t.ly + 12f);
            tm.fontSize = 1.5f; // цвет текста у TextMarker в v8 не настраивается — остаётся штатным

            Call.createMarker(id, sm);
            Call.createMarker(id + 1, tm);
            t.gid = id;
            return true;
        }catch(Throwable e){
            Log.err("[MKUKIC] CursorTracker: createGlobal", e);
            return false;
        }
    }

    static void removeGlobal(Track t){
        if(t.gid >= 0){
            try{
                Call.removeMarker(t.gid);
                Call.removeMarker(t.gid + 1);
            }catch(Throwable ignored){}
            t.gid = -1;
        }
    }

    // ======================= TICK =======================

    /** Каждый игровой кадр (из Qol.tick): позиции + апдейт глобальных маркеров. */
    public static void tick(){
        tickCount++;
        boolean doSync = tickCount % 12 == 0; // ~5 раз в секунду
        for(Track t : tracks){
            Player p = MapMarkers.byName(t.name);
            t.seen = p != null;
            if(p != null){
                t.lx = p.mouseX;
                t.ly = p.mouseY;
            }
            if(t.globalOn && t.gid >= 0 && t.seen && doSync){
                try{
                    Call.updateMarker(t.gid, LMarkerControl.pos, t.lx, t.ly, 0);
                    Call.updateMarker(t.gid + 1, LMarkerControl.pos, t.lx, t.ly + 12f, 0);
                }catch(Throwable ignored){}
            }
        }
    }

    // ============================ ОКНО ============================

    public static void build(Table t){
        QolWindows.section(t, "Трекер курсоров игроков");
        t.add("[lightgray]Метка следует за курсором выбранного игрока (позиция курсора\n"
            + "синхронизируется игрой по сети).\n"
            + "[accent]Лок.[] — локальная метка мода: видна только тебе, работает на любом\n"
            + "сервере, хранится в разрезе карты (слой «Маркеры карты»).\n"
            + "[accent]Глоб.[] — штатный маркер игры: видят ВСЕ игроки; доступен только\n"
            + "в одиночной игре и хосту (ограничение ванили — у клиента нет канала маркеров).")
            .left().top().wrap().width(420f).pad(3f, 0f, 6f, 0f).row();

        t.table(c -> {
            c.left();
            c.label(() -> globalAllowed()
                ? "[lime]● Глобальные метки: доступны (ты хост/SP)[]"
                : "[orange]● Глобальные метки: недоступны (ты клиент) — используй локальные[]");
        }).left().padBottom(4f).row();

        bodyRef = new Table();
        t.add(bodyRef).top().left().growX().row();
        refreshPlayers();
        // живой ростер: пересборка при входе/выходе игроков, пока окно открыто
        bodyRef.update(() -> {
            String s = sig(roster());
            if(sigChanged(s, lastSig)) refreshPlayers();
        });

        t.table(c -> {
            c.left();
            c.button((char)Iconc.cancel + "  Снять все метки", MKUKICSkin.actBtn, CursorTracker::clearAll).minWidth(190f).height(34f);
        }).left().padTop(6f).row();
    }

    static String[] roster(){
        Seq<String> names = new Seq<>();
        try{
            for(Player p : Groups.player) if(p != null && p.name != null) names.add(p.name);
        }catch(Throwable ignored){}
        names.sort();
        return names.toArray(String.class);
    }

    public static void refreshPlayers(){
        if(bodyRef == null) return;
        lastSig = sig(roster());
        Table body = bodyRef;
        body.clearChildren();
        try{
            int n = 0;
            for(Player p : Groups.player){
                if(p == null) continue;
                n++;
                final String name = p.name;
                final Color pc = p.color != null ? p.color : Color.white;
                body.table(r -> {
                    r.left();
                    r.table(chip -> chip.add("[#" + pc.toString().substring(0, 7) + "]⬤[]")).padRight(5f);
                    Label nm = new Label(name);
                    r.add(nm).left().minWidth(120f);
                    r.update(() -> {
                        Track t = find(name);
                        boolean seen = MapMarkers.byName(name) != null;
                        nm.setText((seen ? "" : "[gray]") + name + (t != null && t.seen ? "" : t != null ? " [darkgray](нет связи)[]" : ""));
                    });
                    r.button("Лок.", MKUKICSkin.toggleBtn, () -> {
                        toggleLocal(name);
                        refreshPlayers();
                    }).checked(b -> {
                        Track tt = find(name);
                        return tt != null && tt.local;
                    }).minWidth(56f).height(28f).padLeft(6f);
                    r.button("Глоб.", MKUKICSkin.toggleBtn, () -> {
                        toggleGlobal(name);
                        refreshPlayers();
                    }).checked(b -> {
                        Track tt = find(name);
                        return tt != null && tt.globalOn;
                    }).minWidth(64f).height(28f).padLeft(3f);
                }).left().growX().pad(1.5f).row();
            }
            if(n == 0) body.add("[darkgray]Игроков не видно (загрузи мир).").left().pad(4f).row();
        }catch(Throwable e){
            body.add("[scarlet]Ошибка списка: " + e.getClass().getSimpleName()).left().pad(4f).row();
        }
        body.add("[darkgray]Активных слежений: " + tracks.size).left().padTop(3f).row();
    }

    public static void clearAll(){
        for(Track t : new Seq<>(tracks)){
            if(t.local) removeLocalMarkers(t.name);
            if(t.globalOn) removeGlobal(t);
        }
        tracks.clear();
        refreshPlayers();
        MKUKICMod.toast("[lightgray]Все метки курсоров сняты");
    }
}
