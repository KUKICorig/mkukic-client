package mkukic.client.qol;

import arc.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.math.*;
import arc.math.geom.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import mindustry.content.*;
import mindustry.core.*;
import mindustry.game.*;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.ui.*;
import mindustry.world.*;
import mindustry.world.blocks.defense.*;
import mindustry.world.blocks.storage.*;
import mindustry.world.blocks.storage.CoreBlock.*;
import mkukic.*;
import mkukic.client.*;

import static mindustry.Vars.*;

/**
 * Атака-алерт (v1.16) — порт идей из модов:
 *  - m3rcur1e/attack-alert: «факторио-подобные» оповещения, когда твои здания бьют или ломают;
 *  - MinerTools «EnemyIndicator / UnitAlert»: индикатор опасности у края экрана;
 *  - Caster-UI «Core lost notifications»: отдельный алерт на потерю ядра.
 *
 * Обнаружение ЧЕСТНОЕ, без обращения к приватным полям ванили:
 *  1) опрос здоровья своих зданий раз в {@link #SCAN_EVERY} кадров — ловит и одиночную игру,
 *     и сетевую синхронизацию (на клиенте BuildDamageEvent может не приходить);
 *  2) BlockDestroyEvent — фиксирует уничтожение (в т.ч. ядра).
 *
 * Что показывает:
 *  - красные пульсирующие метки в мире на месте удара/разрушения;
 *  - стрелки у края экрана на свежие происшествия вне поля зрения;
 *  - звук + тост (настраивается, с троттлингом);
 *  - плавающее окно «Атаки и счётчики»: список происшествий (клик = прыжок камерой)
 *    и счётчики по командам (игроки / юниты / ядра / здания).
 */
public class AttackAlert{
    /** Одно происшествие. */
    public static class Entry{
        public float time;      // Time.time
        public long millis;     // системное время (для сортировки/экспорта)
        public int tx, ty;      // тайл
        public String block;    // имя блока
        public boolean destroy; // уничтожен, а не просто повреждён
        public boolean core;    // это ядро
        public float dmg;       // снятое HP (для повреждения)

        public float wx(){ return tx * tilesize + tilesize / 2f; }
        public float wy(){ return ty * tilesize + tilesize / 2f; }
        public float age(){ return Time.time - time; }
    }

    /** Сколько кадров между опросами здоровья зданий. */
    public static final int SCAN_EVERY = 6;
    /** Максимум записей в журнале. */
    public static final int MAX_ENTRIES = 80;

    public static final Seq<Entry> entries = new Seq<>();

    // id здания -> последнее известное HP
    private static final ObjectMap<Integer, Float> hp = new ObjectMap<>();
    // "tx,ty" -> Time.time последней записи (анти-спам по одной точке)
    private static final ObjectMap<String, Float> lastAt = new ObjectMap<>();

    public static int totalHits, totalLost;
    private static float lastSound = -9999f, lastToast = -9999f, scanAt;
    private static Table listRef;
    private static int lastSig = -1;

    // ======================= ЧИСТЫЕ ФУНКЦИИ (тестируются в FeatureTest) =======================

    /**
     * Считать ли падение HP атакой.
     * drop — насколько упало здоровье (всегда > 0), ratio — доля оставшегося HP,
     * wall — это стена/дверь, alertWalls — настройка «тревожить на стенах».
     * Мелкие царапины (< 1 HP) и «стены» без настройки игнорируются.
     */
    public static boolean shouldAlert(float drop, boolean wall, boolean alertWalls){
        if(drop < 1f) return false;
        return !wall || alertWalls;
    }

    /** Можно ли сейчас подать звуковой/тост-сигнал (троттлинг), и обновить отметку. */
    public static boolean throttle(float now, float last, float interval){
        return now - last >= interval;
    }

    /** Кластеризация свежих меток: не рисовать больше N стрелок — вернуть N самых свежих. */
    public static int visibleCap(int count, int cap){
        return Math.min(count, Math.max(1, cap));
    }

    /** Строка-ключ точки для анти-спама. */
    public static String key(int tx, int ty){
        return tx + "," + ty;
    }

    /** Формат подписи «сколько секунд назад». */
    public static String agoText(float frames){
        int sec = Math.max(0, (int)(frames / 60f));
        if(sec < 60) return sec + "с назад";
        if(sec < 3600) return (sec / 60) + "м " + (sec % 60) + "с назад";
        return (sec / 3600) + "ч " + ((sec % 3600) / 60) + "м назад";
    }

    // ======================= СОСТОЯНИЕ =======================

    public static void reset(){
        entries.clear();
        hp.clear();
        breaking.clear();
        lastAt.clear();
        totalHits = totalLost = 0;
        lastSound = lastToast = -9999f;
        scanAt = 0f;
        lastSig = -1;
    }

    // тайлы, которые МЫ сами разбираем/перестраиваем: key = Point2.pack(x,y), value = Time.time
    private static final IntFloatMap breaking = new IntFloatMap();

    /** Регистрация обработчиков (один раз, из Qol.init). */
    public static void init(){
        Events.on(BlockDestroyEvent.class, e -> onDestroyed(e.tile));
        // свой разбор/реплейс — не атака: гасим алерты по этому тайлу
        Events.on(BlockBuildBeginEvent.class, e -> {
            if(e == null || e.tile == null) return;
            if(e.breaking && e.team == (player == null ? null : player.team())){
                breaking.put(Point2.pack(e.tile.x, e.tile.y), Time.time);
            }
        });
        Events.on(BlockBuildEndEvent.class, e -> {
            if(e == null || e.tile == null) return;
            breaking.remove(Point2.pack(e.tile.x, e.tile.y), 0f);
        });
    }

    /** Тайл сейчас разбираем мы сами (ложная тревога). */
    public static boolean selfBreaking(int tx, int ty){
        int key = Point2.pack(tx, ty);
        float t = breaking.get(key, -1f);
        if(t < 0f) return false;
        if(Time.time - t > 60f * 30f){
            breaking.remove(key, 0f);   // план брошен — больше не гасим
            return false;
        }
        return true;
    }

    private static boolean active(){
        MKUKICSettings s = MKUKICSettings.s();
        return s.enabled && s.qolEnabled && s.attackAlert
            && player != null && state != null && !state.isMenu();
    }

    // ======================= ОБНАРУЖЕНИЕ =======================

    /** Каждый кадр из Qol.tick: редкий опрос + прореживание старых записей. */
    public static void tick(){
        if(!active()) return;

        if(Time.time - scanAt >= SCAN_EVERY){
            scanAt = Time.time;
            scanHealth();
        }
        // чистим метки старых точек, чтобы карта не зарастала
        if(lastAt.size > 64){
            Seq<String> keys = new Seq<>();
            for(String k : lastAt.keys()) keys.add(k);
            for(String k : keys){
                Float v = lastAt.get(k);
                if(v != null && Time.time - v > 600f) lastAt.remove(k);
            }
        }
    }

    private static void scanHealth(){
        MKUKICSettings s = MKUKICSettings.s();
        Team my = player.team();
        Rect view = Core.camera.bounds(Tmp.r1);

        for(Building b : Groups.build){
            if(b == null || b.team != my) continue;
            int id = b.id;
            float h = b.health, max = b.maxHealth;
            if(max <= 0f) continue;

            Float prevF = hp.get(id);
            float prev = prevF == null ? -1f : prevF;
            hp.put(id, h);
            if(prev < 0f) continue;                 // первый кадр — только запоминаем

            float drop = prev - h;
            if(drop <= 0f) continue;                 // ремонт/постройка

            if(selfBreaking(b.tileX(), b.tileY())) continue;   // мы сами разбираем

            boolean wall = b.block instanceof Wall || b.block instanceof AutoDoor;
            if(!shouldAlert(drop, wall, s.attackAlertWalls)) continue;

            // дорогие события (сильный урон или потеря блока) — важнее остальных
            boolean serious = b.block instanceof CoreBlock || drop >= max * 0.2f || h <= 0f;
            if(!serious && !view.contains(b.x, b.y) && !s.attackAlertOffscreen) continue;

            add(b.tileX(), b.tileY(), b.block.localizedName == null ? b.block.name : b.block.localizedName,
                false, b.block instanceof CoreBlock, drop);
        }
    }

    private static void onDestroyed(@Nullable Tile tile){
        if(!active() || tile == null) return;
        Building b = tile.build;
        if(b == null || b.team != player.team()) return;
        if(selfBreaking(tile.x, tile.y)) return;   // снос своими руками
        boolean core = b.block instanceof CoreBlock;
        add(tile.x, tile.y, b.block.localizedName == null ? b.block.name : b.block.localizedName, true, core, b.maxHealth);
        hp.remove(b.id);

        if(core){
            MKUKICMod.toast("[scarlet]⚠ ЯДРО УНИЧТОЖЕНО (" + tile.x + ", " + tile.y + ")");
            try{ Sounds.explosionCore.at(b.x, b.y); }catch(Throwable ignored){}
        }
    }

    /** Добавить происшествие (с анти-спамом по точке и троттлингом звука/тоста). */
    private static void add(int tx, int ty, String block, boolean destroy, boolean core, float dmg){
        MKUKICSettings s = MKUKICSettings.s();
        String k = key(tx, ty);
        Float last = lastAt.get(k);
        if(last != null && Time.time - last < 90f && !destroy) return;
        lastAt.put(k, Time.time);

        Entry e = new Entry();
        e.time = Time.time;
        e.millis = System.currentTimeMillis();
        e.tx = tx; e.ty = ty;
        e.block = block;
        e.destroy = destroy;
        e.core = core;
        e.dmg = dmg;
        entries.insert(0, e);
        while(entries.size > MAX_ENTRIES) entries.pop();

        if(destroy) totalLost++; else totalHits++;

        if(s.attackAlertSound && throttle(Time.time, lastSound, 90f)){
            lastSound = Time.time;
            try{
                if(destroy) Sounds.explosionDull.at(tx * tilesize, ty * tilesize, 1f);
                else Sounds.shieldHit.at(tx * tilesize, ty * tilesize, 0.8f);
            }catch(Throwable ignored){}
        }
        if(s.attackAlertToast && throttle(Time.time, lastToast, 150f)){
            lastToast = Time.time;
            MKUKICMod.toast((destroy ? "[scarlet]✖ Уничтожен: []" : "[orange]⚠ Атакуют: []")
                + "[lightgray]" + block + " (" + tx + ", " + ty + ")");
        }
    }

    // ======================= ОТРИСОВКА В МИРЕ =======================

    public static void draw(){
        if(!active()) return;
        if(Core.scene != null && Core.scene.hasDialog()) return;

        MKUKICSettings s = MKUKICSettings.s();
        float life = s.attackAlertLife * 60f;
        Rect view = Core.camera.bounds(Tmp.r1).grow(120f);

        Draw.z(Layer.overlayUI);
        for(Entry e : entries){
            float a = e.age();
            if(a > life) continue;
            float wx = e.wx(), wy = e.wy();
            if(!view.contains(wx, wy)) continue;

            float fin = a / life;                    // 0..1
            float fini = 1f - fin;
            float pulse = 0.6f + 0.4f * Mathf.sin(a / 5f);
            Color c = e.destroy ? Color.scarlet : (e.core ? Color.orange : Pal.remove);

            Draw.color(c.r, c.g, c.b, 0.9f * fini * pulse);
            Lines.stroke(2f * fini + 0.6f);
            float r = 9f + 26f * fin + (e.core ? 10f : 0f);
            Lines.poly(wx, wy, 4, r, 45f);
            if(e.destroy){
                Lines.line(wx - r, wy, wx + r, wy);
                Lines.line(wx, wy - r, wx, wy + r);
            }else{
                Lines.circle(wx, wy, r * 0.55f);
            }

            if(s.attackAlertText && fini > 0.25f){
                Draw.color(c.r, c.g, c.b, fini);
                QolWindows.worldText((e.destroy ? "✖ " : "⚠ ") + e.block, wx, wy + r + 9f, Draw.getColor());
            }
        }
        Draw.reset();
    }

    /** Стрелки у края экрана на свежие происшествия вне поля зрения (MinerTools-индикатор). */
    public static void drawScreen(){
        if(!active()) return;
        MKUKICSettings s = MKUKICSettings.s();
        if(!s.attackAlertArrow || entries.isEmpty()) return;
        if(Core.scene != null && (Core.scene.hasDialog() || Core.scene.hasField())) return;

        float w = Core.graphics.getWidth(), h = Core.graphics.getHeight();
        Rect view = Core.camera.bounds(Tmp.r2);
        float margin = 34f;
        int drawn = 0;
        int cap = visibleCap(entries.size, s.attackAlertMaxArrows);

        Draw.z(Layer.overlayUI);
        for(Entry e : entries){
            if(drawn >= cap) break;
            if(e.age() > s.attackAlertLife * 60f) continue;
            float wx = e.wx(), wy = e.wy();
            if(view.contains(wx, wy)) continue;   // и так видно

            Vec2 sc = Core.camera.project(wx, wy);
            float dx = sc.x - w / 2f, dy = sc.y - h / 2f;
            float ang = Angles.angle(0f, 0f, dx, dy);
            float rad = Math.min(w, h) * 0.44f;
            float px = w / 2f + Angles.trnsx(ang, rad), py = h / 2f + Angles.trnsy(ang, rad);
            px = Mathf.clamp(px, margin, w - margin);
            py = Mathf.clamp(py, margin, h - margin);

            float fade = 1f - e.age() / (s.attackAlertLife * 60f);
            Color c = e.destroy ? Color.scarlet : Color.orange;
            Draw.color(c.r, c.g, c.b, 0.85f * fade);
            // треугольник-стрелка, как у ESP-радара (проверенный ванильный паттерн)
            Fill.tri(
                px + Angles.trnsx(ang, 10f), py + Angles.trnsy(ang, 10f),
                px + Angles.trnsx(ang + 140f, 8f), py + Angles.trnsy(ang + 140f, 8f),
                px + Angles.trnsx(ang - 140f, 8f), py + Angles.trnsy(ang - 140f, 8f));
            drawn++;
        }
        Draw.reset();
    }

    // ======================= ОКНО =======================

    public static void build(Table t){
        QolWindows.section(t, "Атаки и счётчики");
        t.add("[lightgray]Оповещения в стиле Factorio: когда твои здания повреждают или уничтожают.\n"
            + "Клик по строке — прыжок камерой к месту. Метки в мире и стрелки у края экрана настраиваются\n"
            + "в меню F9 → QoL → Атака-алерт.")
            .left().top().wrap().width(380f).pad(3f, 0f, 6f, 0f).row();

        t.table(c -> {
            c.left();
            c.label(() -> "[#ffb454]⚠ " + totalHits + " [lightgray]ударов   [#ff8f7d]✖ " + totalLost
                + " [lightgray]потерь   [#8b94a5]в журнале: " + entries.size).left();
        }).left().padBottom(3f).row();

        t.table(c -> {
            c.left();
            c.button((char)Iconc.cancel + "  Очистить журнал", MKUKICSkin.actBtn, () -> {
                entries.clear();
                totalHits = totalLost = 0;
                rebuildList();
            }).minWidth(170f).height(32f).padRight(4f);
            c.button((char)Iconc.refresh + "  Обновить", MKUKICSkin.actBtn, AttackAlert::rebuildList)
                .minWidth(120f).height(32f);
        }).left().padBottom(4f).row();

        Table list = new Table();
        list.top().left();
        listRef = list;
        t.pane(MKUKICSkin.scroll, list).maxHeight(220f).growX().row();

        t.image(MKUKICSkin.divSoft).height(2f).growX().pad(6f, 0f, 4f, 0f).row();
        t.add("[#6fd6ff]Счётчики команд").left().padBottom(2f).row();
        t.label(AttackAlert::countersText).left().top().wrap().width(380f).row();

        rebuildList();
    }

    private static void rebuildList(){
        if(listRef == null) return;
        Table list = listRef;
        list.clear();
        list.top().left();
        if(entries.isEmpty()){
            list.add("[darkgray]пока тихо — происшествий нет").left().pad(6f).row();
        }else{
            for(Entry e : entries){
                final Entry fe = e;
                String mark = e.destroy ? "[scarlet]✖[]" : (e.core ? "[orange]⚠[]" : "[#ffb454]⚠[]");
                list.table(row -> {
                    row.left();
                    row.button(mark + " " + e.block + " [#8b94a5](" + e.tx + ", " + e.ty + ")  "
                            + agoText(e.age()), MKUKICSkin.navBtn,
                        () -> QolWindows.jumpTo(fe.wx(), fe.wy()))
                        .minWidth(330f).height(30f).left().pad(1f).get()
                        .getLabel().setAlignment(Align.left);
                }).left().row();
            }
        }
        list.pack();
    }

    /** Сводка по командам: игроки, юниты, ядра, здания. */
    public static String countersText(){
        if(state == null) return "[darkgray]—";
        StringBuilder sb = new StringBuilder();
        for(Team t : Team.all){
            if(t == null || !state.teams.isActive(t)) continue;
            Teams.TeamData d = state.teams.getOrNull(t);
            if(d == null) continue;
            int units = d.units == null ? 0 : d.units.size;
            int cores = d.cores == null ? 0 : d.cores.size;
            int players = d.players == null ? 0 : d.players.size;
            int builds = d.buildings == null ? 0 : d.buildings.size;
            sb.append("[#").append(t.color.toString()).append("]").append((char)Iconc.players)
                .append("[] ").append(t.localized())
                .append("[lightgray]: игроков [white]").append(players)
                .append("[lightgray], юнитов [white]").append(units)
                .append("[lightgray], ядер [white]").append(cores)
                .append("[lightgray], зданий [white]").append(builds).append('\n');
        }
        if(sb.length() == 0) return "[darkgray]нет активных команд";
        return sb.toString();
    }

    /** Прыжок камерой к самому свежему происшествию (горячая клавиша mkukic_attack_next). */
    public static void jumpToLatest(){
        if(entries.isEmpty()){
            MKUKICMod.toast("[lightgray]происшествий нет");
            return;
        }
        Entry e = entries.first();
        QolWindows.jumpTo(e.wx(), e.wy());
        MKUKICMod.toast("[accent]" + (char)Iconc.zoom + " " + e.block + " (" + e.tx + ", " + e.ty + ")");
    }

    /** Подпись для HUD-строки статуса. */
    public static String hudChip(){
        if(entries.isEmpty()) return null;
        Entry e = entries.first();
        if(e.age() > MKUKICSettings.s().attackAlertLife * 60f) return null;
        return (e.destroy ? "[#ff8f7d]✖" : "[#ffb454]⚠") + (int)(e.dmg) + " " + e.block + " (" + e.tx + "," + e.ty + ")";
    }

    /** Подпись для обновления окна (проверяем раз в ~0.5с, чтобы не перестраивать каждый кадр). */
    public static void updateWindow(){
        int sig = entries.size * 31 + totalHits * 7 + totalLost;
        if(sig == lastSig) return;
        lastSig = sig;
        rebuildList();
    }
}
