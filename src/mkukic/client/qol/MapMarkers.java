package mkukic.client.qol;

import arc.*;
import arc.files.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.math.geom.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import arc.util.serialization.*;
import mindustry.core.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.ui.*;
import mkukic.*;
import mkukic.client.*;

import static mindustry.Vars.*;

/**
 * Кастомные маркеры карты (порт «кастомных маркеров» из Neon).
 *
 * Точки и круги с подписями, которые рисуются прямо в мире. Хранятся ЛОКАЛЬНО
 * (config/mkukic-client/markers.json) в разрезе карты — на серверах ничего не
 * синхронизируется и не ломается: это твой личный слой заметок.
 *
 * Добавление: кнопка в окне или назначаемая клавиша (Настройки → Управление → mkukic
 * → mkukic_marker_add). Цвет/тип/радиус/подпись берутся из панели окна.
 */
public class MapMarkers{
    /** Один маркер. Поля публичные — так сериализует arc Json. */
    public static class Marker{
        public String map = "";
        public float x, y;
        public String text = "";
        public int color;
        public boolean circle;
        public float radius = 8f; // тайлы (для circle)
        /** v1.12: имя игрока, за которым следует маркер (пусто = статичный). Позиция обновляется в draw(). */
        public String track = "";
        /** v1.12: hex-цвет игрока для отслеживающего маркера (пусто = цвет палитры). */
        public String trackColor = "";
    }

    public static final Color[] palette = {
        Color.scarlet, Color.orange, Color.yellow, Color.lime,
        Color.sky, Color.violet, Color.white
    };

    /** Все маркеры (всех карт) — в памяти и в файле. */
    public static Seq<Marker> all = new Seq<>();

    // состояние панели «следующий маркер»
    static String pendingText = "";
    static boolean pendingCircle = false;
    static float pendingRadius = 8f;

    private static final Json json = new Json();
    private static final Rect viewRect = new Rect();
    private static Table bodyRef;

    // ======================= ЧИСТЫЕ ФУНКЦИИ (тестируются) =======================

    /** Ключ карты для хранения: sanitized-имя + размеры (однозначно различает карты). */
    public static String mapKey(String name, int w, int h){
        StringBuilder sb = new StringBuilder();
        if(name != null){
            for(int i = 0; i < name.length() && sb.length() < 40; i++){
                char c = Character.toLowerCase(name.charAt(i));
                if(Character.isLetterOrDigit(c) || c == '-' || c == '_') sb.append(c);
                else if(c == ' ') sb.append('_');
            }
        }
        if(sb.length() == 0) sb.append("map");
        return sb + "_" + w + "x" + h;
    }

    /** Цвет палитры по индексу (циклически, отрицательные допустимы). */
    public static Color colorOf(int i){
        int n = palette.length;
        return palette[((i % n) + n) % n];
    }

    /** Подпись по умолчанию: «Маркер N». */
    public static String autoName(int existing){
        return "Маркер " + (existing + 1);
    }

    // ======================= ХРАНЕНИЕ =======================

    static Fi file(){
        Fi base = MKUKICSettings.dir();
        if(base == null) base = Core.settings.getDataDirectory().child("config").child("mkukic-client");
        return base.child("markers.json");
    }

    public static void save(){
        try{
            json.setIgnoreUnknownFields(true);
            Marker[] arr = all.toArray(Marker.class);
            file().writeString(json.toJson(arr), false);
        }catch(Throwable t){
            Log.err("[MKUKIC] MapMarkers: save", t);
        }
    }

    public static void load(){
        try{
            Fi f = file();
            all.clear();
            if(f.exists()){
                Marker[] arr = json.fromJson(Marker[].class, f.readString());
                if(arr != null) for(Marker m : arr) if(m != null) all.add(m);
            }
        }catch(Throwable t){
            Log.err("[MKUKIC] MapMarkers: load (файл повреждён — начинаю с пустого)", t);
            all.clear();
        }
    }

    /** Ключ текущей карты или null (меню/нет мира). */
    public static String currentKey(){
        try{
            if(state == null || state.isMenu() || state.map == null || world == null) return null;
            return mapKey(state.map.name(), world.width(), world.height());
        }catch(Throwable t){
            return null;
        }
    }

    /** Игрок по имени (локальный реестр Groups.player), или null. */
    public static Player byName(String name){
        if(name == null || name.isEmpty()) return null;
        for(Player p : Groups.player){
            if(p != null && name.equals(p.name)) return p;
        }
        return null;
    }

    /** Маркеры текущей карты. */
    public static Seq<Marker> current(){
        Seq<Marker> out = new Seq<>();
        String key = currentKey();
        if(key == null) return out;
        for(Marker m : all) if(key.equals(m.map)) out.add(m);
        return out;
    }

    // ======================= ДЕЙСТВИЯ =======================

    /** Добавить маркер у курсора (кнопка окна / горячая клавиша). */
    public static void addAtCursor(){
        try{
            String key = currentKey();
            if(key == null){
                MKUKICMod.toast("[scarlet]Маркеры доступны только в игре");
                return;
            }
            Vec2 v = Core.input.mouseWorld();
            Marker m = new Marker();
            m.map = key;
            m.x = v.x;
            m.y = v.y;
            m.text = (pendingText == null || pendingText.trim().isEmpty())
                ? autoName(current().size) : pendingText.trim();
            m.color = MKUKICSettings.s().markerColor;
            m.circle = pendingCircle;
            m.radius = pendingCircle ? pendingRadius : 8f;
            all.add(m);
            save();
            MKUKICMod.toast("[accent]" + (char)Iconc.pencil + " [white]Маркер «" + m.text + "» добавлен (" + World.toTile(m.x) + ", " + World.toTile(m.y) + ")");
            refresh();
        }catch(Throwable t){
            Log.err("[MKUKIC] MapMarkers: add", t);
        }
    }

    public static void remove(Marker m){
        all.remove(m);
        save();
        refresh();
    }

    public static void clearMap(){
        String key = currentKey();
        if(key == null) return;
        for(int i = all.size - 1; i >= 0; i--){
            if(key.equals(all.get(i).map)) all.remove(i);
        }
        save();
        refresh();
    }

    public static void jump(Marker m){
        Core.camera.position.set(m.x, m.y);
        MKUKICMod.toast("[lightgray]Камера: " + World.toTile(m.x) + ", " + World.toTile(m.y));
    }

    // ======================= WORLD-DRAW =======================

    public static void draw(){
        try{
            if(state == null || state.isMenu() || world == null) return;
            MKUKICSettings s = MKUKICSettings.s();
            if(!s.enabled || !s.qolEnabled || !s.markerOverlay) return;
            String key = currentKey();
            if(key == null) return;

            Rect b = Core.camera.bounds(viewRect).grow(96f);
            Draw.z(Layer.overlayUI);
            for(Marker m : all){
                if(!key.equals(m.map)) continue;
                // отсечение по видимой области (без мусора: чистая арифметика)
                float pad = m.circle ? Math.max(1f, m.radius) * 8f + 8f : 16f;
                if(m.x + pad < b.x || m.x - pad > b.x + b.width || m.y + pad < b.y || m.y - pad > b.y + b.height) continue;
                Color c = colorOf(m.color);
                String label = m.text;
                // отслеживание курсора игрока (v1.12): позиция живая, цвет — цвет игрока
                if(m.track != null && !m.track.isEmpty()){
                    Player tp = byName(m.track);
                    if(tp != null){
                        m.x = tp.mouseX;
                        m.y = tp.mouseY;
                        if(m.trackColor != null && !m.trackColor.isEmpty()){
                            try{ c = Color.valueOf(m.trackColor); }catch(Throwable ignored){}
                        }
                    }else{
                        label = m.text + " (нет связи)";
                    }
                }
                if(m.circle){
                    float r = Math.max(1f, m.radius) * 8f;
                    Lines.stroke(2f, c);
                    Lines.circle(m.x, m.y, r);
                    Draw.color(c.r, c.g, c.b, 0.08f);
                    Fill.circle(m.x, m.y, r);
                    Draw.color(c);
                    if(s.markerText) QolWindows.worldText(label, m.x, m.y + r + 6f, c);
                }else{
                    Lines.stroke(2f, c);
                    Lines.circle(m.x, m.y, 6f);
                    Fill.circle(m.x, m.y, 2.5f);
                    if(s.markerText) QolWindows.worldText(label, m.x, m.y + 12f, c);
                }
            }
            Draw.reset();
        }catch(Throwable ignored){}
    }

    // ============================ ОКНО ============================

    public static void build(Table t){
        QolWindows.section(t, "Маркеры карты");
        t.add("[lightgray]Личный слой заметок на карте: точки и круги с подписями.\n"
            + "Хранится локально (config/mkukic-client/markers.json) в разрезе карты —\n"
            + "на серверах ничего не синхронизируется и не ломается.\n"
            + "Горячую клавишу добавления назначь: Настройки → Управление → mkukic.")
            .left().top().wrap().width(400f).pad(3f, 0f, 6f, 0f).row();

        // панель «следующий маркер»
        t.table(c -> {
            c.left();
            c.add("Подпись:").padRight(4f);
            TextField tf = new TextField(pendingText);
            tf.setMessageText("Маркер N");
            c.add(tf).width(160f).padRight(6f);
            c.update(() -> pendingText = tf.getText());
        }).left().row();

        t.table(c -> {
            c.left();
            c.add("Цвет:").padRight(4f);
            for(int i = 0; i < palette.length; i++){
                final int ci = i;
                String hex = palette[i].toString();
                c.button("[" + hex + "]●[]", MKUKICSkin.toggleBtn, () -> {
                    MKUKICSettings.s().markerColor = ci;
                    MKUKICSettings.save();
                }).checked(b -> MKUKICSettings.s().markerColor == ci).size(30f).pad(1f);
            }
        }).left().padTop(3f).row();

        t.table(c -> {
            c.left();
            c.check("Круг вместо точки", pendingCircle, v -> pendingCircle = v).padRight(8f);
            c.add("R, тайлов:").padRight(4f);
            Label rv = new Label("");
            c.add(rv).right().minWidth(34f).padRight(4f);
            c.slider(2f, 64f, 1f, pendingRadius, v -> {
                pendingRadius = v;
                MKUKICSettings.s().markerRadius = v;
                MKUKICSettings.save();
            }).width(140f);
            rv.setText(() -> "" + (int)pendingRadius);
        }).left().padTop(2f).row();

        t.table(c -> {
            c.left();
            c.button((char)Iconc.pencil + "  Добавить у курсора", MKUKICSkin.actBtn, MapMarkers::addAtCursor).minWidth(200f).height(36f).padRight(6f);
            c.button((char)Iconc.cancel + "  Очистить карту", MKUKICSkin.actBtn, MapMarkers::clearMap).minWidth(160f).height(36f);
        }).left().padTop(5f).row();

        t.check("Рисовать в мире", MKUKICSettings.s().markerOverlay, v -> {
            MKUKICSettings.s().markerOverlay = v;
            MKUKICSettings.save();
        }).left().padTop(5f).row();
        t.check("Подписи текстом", MKUKICSettings.s().markerText, v -> {
            MKUKICSettings.s().markerText = v;
            MKUKICSettings.save();
        }).left().row();

        t.add().row();
        bodyRef = new Table();
        t.add(bodyRef).top().left().growX().row();
        refresh();
    }

    /** Перерисовать список маркеров текущей карты. */
    public static void refresh(){
        if(bodyRef == null) return;
        Table body = bodyRef;
        body.clearChildren();
        Seq<Marker> l = current();
        if(currentKey() == null){
            body.add("[darkgray]Маркеры доступны только в игре.").left().pad(4f).row();
            return;
        }
        if(l.size == 0){
            body.add("[darkgray]На этой карте маркеров нет.").left().pad(4f).row();
        }else{
            body.pane(p -> {
                p.left().top();
                for(Marker m : l){
                    p.table(r -> {
                        r.left();
                        r.add("[" + colorOf(m.color).toString() + "]●[]").padRight(4f);
                        r.add("[lightgray]" + m.text + (m.circle ? " [darkgray](R" + (int)m.radius + ")" : "")).left().growX().wrap().width(220f);
                        r.add("[darkgray]" + World.toTile(m.x) + "," + World.toTile(m.y)).padRight(4f);
                        r.button("→", MKUKICSkin.actBtn, () -> jump(m)).size(28f).padRight(3f);
                        r.button("[scarlet]✕", MKUKICSkin.actBtn, () -> remove(m)).size(28f);
                    }).left().growX().pad(1.5f).row();
                }
            }).growX().maxHeight(200f).row();
        }
        body.add("[darkgray]Всего на карте: " + l.size).left().padTop(3f).row();
    }

    /** Сброс при загрузке мира: перечитать файл (новый мир — возможно, другая карта). */
    public static void reset(){
        load();
        pendingRadius = MKUKICSettings.s().markerRadius;
        refresh();
    }
}
