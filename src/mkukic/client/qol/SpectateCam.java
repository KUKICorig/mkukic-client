package mkukic.client.qol;

import arc.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.math.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import mindustry.content.*;
import mindustry.core.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.ui.*;
import mindustry.world.blocks.storage.CoreBlock.*;
import mkukic.*;
import mkukic.client.*;

import static mindustry.Vars.*;

/**
 * Операторская камера (v1.16) — порт идей из модов:
 *  - Caster-UI (JiroCab): листание игроков клавишами, «следить за игроком»,
 *    уведомления о потере ядра и прыжок камерой к месту потери;
 *  - MinerTools «PlayerList»: список игроков с переходом к их позиции;
 *  - MI2-Utilities «Multi-Team»: наблюдение за другими командами.
 *
 * Что даёт:
 *  - камера следует за выбранным игроком (или за ядром) — удобно смотреть матч со стороны;
 *  - листание целей клавишами (назначаются в Настройки → Управление → mkukic);
 *  - уведомление о потере ядра: вспышка экрана, тост, звук и координаты в журнале;
 *  - всё только чтение состояния + движение СВОЕЙ камеры: на сервер ничего не уходит,
 *    поэтому работает в любом мультиплеере (в т.ч. без мода на сервере).
 */
public class SpectateCam{
    /** За кем сейчас следим. */
    public static @Nullable Player followPlayer;
    public static @Nullable CoreBuild followCore;
    public static boolean following;

    /** Журнал потерянных ядер. */
    public static final Seq<String> coreLog = new Seq<>();

    // id ядра -> HP в прошлом кадре (для детектора потери)
    private static final ObjectMap<Integer, Float> cores = new ObjectMap<>();
    private static final IntSet aliveTmp = new IntSet();
    private static boolean coresInit;
    private static float flash, flashTime;
    private static Table listRef;
    private static int lastSig = -1;

    // ======================= ЧИСТЫЕ ФУНКЦИИ (тестируются) =======================

    /**
     * Следующий индекс в кольцевом списке, пропуская недоступные элементы.
     * dir = +1 вперёд, −1 назад; valid[i] — доступен ли элемент i.
     * Возвращает -1, если доступных нет вовсе.
     */
    public static int nextIndex(int current, int dir, boolean[] valid){
        if(valid == null || valid.length == 0) return -1;
        for(int step = 1; step <= valid.length; step++){
            int i = ((current + dir * step) % valid.length + valid.length) % valid.length;
            if(valid[i]) return i;
        }
        return -1;
    }

    /** Точка камеры для «свободного» наблюдения (не дёргает юнита игрока). */
    public static void freeLook(float wx, float wy){
        if(Core.camera != null) Core.camera.position.set(wx, wy);
    }

    /** Вспышка экрана: затухание 1 -> 0 за life кадров. */
    public static float flashAlpha(float now, float start, float life){
        if(life <= 0f) return 0f;
        float f = 1f - (now - start) / life;
        return Mathf.clamp(f, 0f, 1f);
    }

    // ======================= ЛОГИКА =======================

    public static void reset(){
        followPlayer = null;
        followCore = null;
        following = false;
        cores.clear();
        coresInit = false;
        coreLog.clear();
        flash = 0f;
        lastSig = -1;
    }

    public static void tick(){
        MKUKICSettings s = MKUKICSettings.s();
        if(!s.enabled || !s.qolEnabled) return;
        if(player == null || state == null || state.isMenu()) return;

        if(s.coreLostAlert) checkCores(s);
        if(s.spectateFollow && following) followTarget();
    }

    /** Жив ли ещё этот игрок в списке. */
    private static boolean playerPresent(Player p){
        for(Player other : Groups.player) if(other == p) return true;
        return false;
    }

    /** Камера идёт за целью; если цель пропала — слежение выключается. */
    private static void followTarget(){
        if(followCore != null && !followCore.dead && followCore.isAdded()){
            freeLook(followCore.x, followCore.y);
            return;
        }
        if(followPlayer != null && playerPresent(followPlayer)){
            Unit u = followPlayer.unit();
            if(u != null && !u.dead()){
                freeLook(u.x, u.y);
                return;
            }
            // игрок без юнита (наблюдатель) — следим за его камерой
            freeLook(followPlayer.x, followPlayer.y);
            return;
        }
        following = false;
        followCore = null;
        followPlayer = null;
    }

    // ======================= ПОТЕРЯ ЯДРА =======================

    private static void checkCores(MKUKICSettings s){
        Team my = player.team();
        if(my == null) return;
        Seq<CoreBuild> list = my.cores();

        IntSet alive = aliveTmp;
        alive.clear();
        if(list != null){
            for(CoreBuild c : list){
                if(c == null) continue;
                alive.add(c.id);
                float h = c.health;
                Float prevF = cores.get(c.id);
                float prev = prevF == null ? -1f : prevF;
                cores.put(c.id, h);
                if(coresInit && prev > 0f && h <= 0f) onCoreLost(c, s);
            }
        }
        // ядра, которых больше нет в списке команды, — тоже потеря
        if(coresInit){
            Seq<Integer> ids = new Seq<>();
            for(Integer id : cores.keys()) ids.add(id);
            for(Integer id : ids){
                if(!alive.contains(id)) onCoreLost(null, s, id);
            }
        }
        coresInit = true;
    }

    private static void onCoreLost(@Nullable CoreBuild c, MKUKICSettings s){
        onCoreLost(c, s, c == null ? -1 : c.id);
    }

    private static void onCoreLost(@Nullable CoreBuild c, MKUKICSettings s, int id){
        cores.remove(id);
        float x = c == null ? 0f : c.x, y = c == null ? 0f : c.y;
        int tx = c == null ? 0 : c.tileX(), ty = c == null ? 0 : c.tileY();

        coreLog.insert(0, "[scarlet]" + (char)Iconc.warning + " ядро потеряно (" + tx + ", " + ty + ")");
        while(coreLog.size > 20) coreLog.pop();

        if(s.coreLostFlash){
            flash = 1f;
            flashTime = Time.time;
        }
        if(s.coreLostSound){
            try{ Sounds.explosionCore.at(x, y); }catch(Throwable ignored){}
        }
        MKUKICMod.toast("[scarlet]⚠ ЯДРО ПОТЕРЯНО (" + tx + ", " + ty + ")");
        if(s.coreLostSnap && c != null){
            freeLook(x, y);
        }
    }

    /** Вспышка поверх экрана (рисуем из Trigger.uiDrawEnd). */
    public static void drawScreen(){
        if(flash <= 0.001f) return;
        MKUKICSettings s = MKUKICSettings.s();
        if(!s.enabled || !s.qolEnabled || !s.coreLostAlert || !s.coreLostFlash) return;

        flash = flashAlpha(Time.time, flashTime, 60f);
        if(flash <= 0.001f) return;

        float w = Core.graphics.getWidth(), h = Core.graphics.getHeight();
        Draw.z(Layer.overlayUI);
        Draw.color(1f, 0.25f, 0.2f, 0.42f * flash);
        Fill.rect(w / 2f, h / 2f, w, h);
        Draw.reset();
    }

    // ======================= ЦЕЛИ =======================

    /** Список игроков (живые, кроме себя) — цели для листания. */
    public static Seq<Player> players(){
        Seq<Player> out = new Seq<>();
        for(Player p : Groups.player){
            if(p == null || p == player) continue;
            out.add(p);
        }
        out.sort((a, b) -> a.plainName().compareTo(b.plainName()));
        return out;
    }

    /** Ядра всех активных команд — цели для листания. */
    public static Seq<CoreBuild> cores(){
        Seq<CoreBuild> out = new Seq<>();
        if(state == null) return out;
        for(Team t : Team.all){
            if(t == null || !state.teams.isActive(t)) continue;
            Seq<CoreBuild> cs = t.cores();
            if(cs == null) continue;
            for(CoreBuild c : cs) if(c != null) out.add(c);
        }
        return out;
    }

    /** Следующий игрок (листание). */
    public static void cyclePlayer(int dir){
        Seq<Player> list = players();
        if(list.isEmpty()){
            MKUKICMod.toast("[lightgray]других игроков нет");
            return;
        }
        boolean[] valid = new boolean[list.size];
        for(int i = 0; i < valid.length; i++) valid[i] = true;
        int cur = followPlayer == null ? -1 : list.indexOf(followPlayer, true);
        int next = nextIndex(cur, dir, valid);
        if(next < 0) return;
        followPlayer = list.get(next);
        followCore = null;
        following = MKUKICSettings.s().spectateFollow;
        MKUKICMod.toast("[#6fd6ff]" + (char)Iconc.eye + " наблюдение: [lightgray]" + followPlayer.plainName());
        snapToPlayer(followPlayer);
    }

    /** Следующее ядро (листание). */
    public static void cycleCore(int dir){
        Seq<CoreBuild> list = cores();
        if(list.isEmpty()){
            MKUKICMod.toast("[lightgray]ядер на карте нет");
            return;
        }
        boolean[] valid = new boolean[list.size];
        for(int i = 0; i < valid.length; i++) valid[i] = true;
        int cur = followCore == null ? -1 : list.indexOf(followCore, true);
        int next = nextIndex(cur, dir, valid);
        if(next < 0) return;
        followCore = list.get(next);
        followPlayer = null;
        following = MKUKICSettings.s().spectateFollow;
        MKUKICMod.toast("[#6fd6ff]" + (char)Iconc.eye + " наблюдение: [lightgray]ядро "
            + followCore.team.localized() + " (" + followCore.tileX() + ", " + followCore.tileY() + ")");
        freeLook(followCore.x, followCore.y);
    }

    private static void snapToPlayer(Player p){
        if(p == null) return;
        Unit u = p.unit();
        if(u != null && !u.dead()) freeLook(u.x, u.y);
        else freeLook(p.x, p.y);
    }

    /** Вкл/выкл слежение за текущей целью. */
    public static void toggleFollow(){
        if(followPlayer == null && followCore == null){
            cyclePlayer(1);
            return;
        }
        following = !following;
        MKUKICMod.toast(following ? "[accent]камера следит за целью" : "[scarlet]камера свободна");
    }

    /** Строка статуса для окна/HUD. */
    public static String statusText(){
        if(followCore != null) return "ядро " + followCore.team.localized() + " (" + followCore.tileX() + ", " + followCore.tileY() + ")";
        if(followPlayer != null) return followPlayer.plainName();
        return "цель не выбрана";
    }

    // ======================= ОКНО =======================

    public static void build(Table t){
        QolWindows.section(t, "Операторская камера");
        t.add("[lightgray]Наблюдение за матчем: камера идёт за выбранным игроком или ядром,\n"
            + "цели листаются клавишами. Плюс 알림 о потере ядра (вспышка, звук, координаты).\n"
            + "Ничего не отправляет на сервер — работает в любом мультиплеере.")
            .left().top().wrap().width(400f).pad(3f, 0f, 6f, 0f).row();

        t.table(c -> {
            c.left();
            c.label(() -> "[#8b94a5]цель: [white]" + statusText()
                + (following ? "  [#7dff9a](следим)" : "  [#8b94a5](свободно)")).left();
        }).left().padBottom(3f).row();

        t.table(c -> {
            c.left();
            c.button((char)Iconc.left + "  Пред. игрок", MKUKICSkin.actBtn, () -> cyclePlayer(-1)).minWidth(130f).height(30f).pad(2f);
            c.button("След. игрок  " + (char)Iconc.right, MKUKICSkin.actBtn, () -> cyclePlayer(1)).minWidth(130f).height(30f).pad(2f);
            c.button((char)Iconc.eye + "  Следить", MKUKICSkin.toggleBtn, SpectateCam::toggleFollow)
                .checked(b -> following).minWidth(110f).height(30f).pad(2f);
        }).left().row();

        t.table(c -> {
            c.left();
            c.button((char)Iconc.home + "  Пред. ядро", MKUKICSkin.actBtn, () -> cycleCore(-1)).minWidth(130f).height(30f).pad(2f);
            c.button("След. ядро  " + (char)Iconc.home, MKUKICSkin.actBtn, () -> cycleCore(1)).minWidth(130f).height(30f).pad(2f);
            c.button((char)Iconc.cancel + "  Сброс", MKUKICSkin.actBtn, SpectateCam::reset)
                .minWidth(90f).height(30f).pad(2f);
        }).left().row();

        t.table(c -> {
            c.left();
            c.check("Слежение включено", MKUKICSettings.s().spectateFollow, v -> {
                MKUKICSettings.s().spectateFollow = v;
                MKUKICSettings.save();
                following = v && (followPlayer != null || followCore != null);
            }).padRight(10f);
            c.check("Алерт потери ядра", MKUKICSettings.s().coreLostAlert, v -> {
                MKUKICSettings.s().coreLostAlert = v;
                MKUKICSettings.save();
            }).padRight(10f);
            c.check("Вспышка", MKUKICSettings.s().coreLostFlash, v -> {
                MKUKICSettings.s().coreLostFlash = v;
                MKUKICSettings.save();
            });
        }).left().padTop(4f).row();

        t.table(c -> {
            c.left();
            c.check("Звук", MKUKICSettings.s().coreLostSound, v -> {
                MKUKICSettings.s().coreLostSound = v;
                MKUKICSettings.save();
            }).padRight(10f);
            c.check("Прыжок камерой к ядру", MKUKICSettings.s().coreLostSnap, v -> {
                MKUKICSettings.s().coreLostSnap = v;
                MKUKICSettings.save();
            });
        }).left().row();

        t.image(MKUKICSkin.divSoft).height(2f).growX().pad(6f, 0f, 4f, 0f).row();
        t.add("[#6fd6ff]Игроки").left().padBottom(2f).row();

        Table list = new Table();
        list.top().left();
        listRef = list;
        t.pane(MKUKICSkin.scroll, list).maxHeight(170f).growX().row();

        if(!coreLog.isEmpty()){
            t.image(MKUKICSkin.divSoft).height(2f).growX().pad(6f, 0f, 4f, 0f).row();
            t.add("[#6fd6ff]Потери ядер").left().padBottom(2f).row();
            t.label(() -> String.join("\n", coreLog)).left().top().wrap().width(400f).row();
        }
        rebuildList();
    }

    private static void rebuildList(){
        if(listRef == null) return;
        Table list = listRef;
        list.clear();
        list.top().left();

        Seq<Player> ps = players();
        for(Player p : ps){
            final Player fp = p;
            Unit u = p.unit();
            String unit = u == null || u.dead() ? "[darkgray]нет юнита"
                : (u.type.localizedName == null ? u.type.name : u.type.localizedName)
                + " [lightgray]" + (int)u.health() + "/" + (int)u.maxHealth();
            boolean watched = followPlayer == p;
            list.table(row -> {
                row.left();
                row.button((watched ? "[#ffb454]" + (char)Iconc.eye + " []" : "")
                        + "[#" + p.color.toString() + "]" + p.plainName() + "[] [#8b94a5]" + unit,
                    MKUKICSkin.navBtn, () -> {
                        followPlayer = fp;
                        followCore = null;
                        following = MKUKICSettings.s().spectateFollow;
                        snapToPlayer(fp);
                        rebuildList();
                    })
                    .minWidth(330f).height(28f).left().pad(0.5f).get()
                    .getLabel().setAlignment(Align.left);
            }).left().row();
        }
        if(ps.isEmpty()) list.add("[darkgray]ты на карте один").left().pad(6f).row();

        Seq<CoreBuild> cs = cores();
        if(!cs.isEmpty()){
            list.add("[#8b94a5]ядра:").left().pad(4f, 0f, 2f, 0f).row();
            for(CoreBuild c : cs){
                final CoreBuild fc = c;
                list.table(row -> {
                    row.left();
                    row.button((char)Iconc.home + " " + c.team.localized() + " [#8b94a5]("
                            + c.tileX() + ", " + c.tileY() + ")  " + (int)c.health + "/" + (int)c.maxHealth,
                        MKUKICSkin.navBtn, () -> {
                            followCore = fc;
                            followPlayer = null;
                            following = MKUKICSettings.s().spectateFollow;
                            freeLook(fc.x, fc.y);
                            rebuildList();
                        })
                        .minWidth(330f).height(28f).left().pad(0.5f).get()
                        .getLabel().setAlignment(Align.left);
                }).left().row();
            }
        }
        list.pack();
    }

    /** Обновление списка раз в ~1с, если окно открыто. */
    public static void updateWindow(){
        if(listRef == null || !QolWindows.isOpen("spectate")) return;
        int sig = players().size * 7 + cores().size;
        if(sig == lastSig) return;
        lastSig = sig;
        rebuildList();
    }
}
