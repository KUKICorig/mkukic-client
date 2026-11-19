package mkukic.client.qol;

import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.math.*;
import arc.util.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.net.Packets.*;
import mindustry.type.*;
import mindustry.ui.*;
import mkukic.*;
import mkukic.client.*;

import static mindustry.Vars.*;

/**
 * Монитор волн (MI2-Utilities «Waves» + MindustryTool «Wave Preview»):
 *  - HP и состав текущей волны в реальном времени;
 *  - подробный прогноз следующих волн по SpawnGroup (типы, количество, щиты, боссы);
 *  - кнопка принудительного пропуска волны (песочница/админ).
 */
public class WaveMonitor{
    // кэш (обновляется в tick, чтобы не считать каждый кадр)
    private static float enemyHp, enemyHpMax, enemyShield;
    private static int enemyCount, airCount, groundCount;
    private static float cacheTime = -9999f;

    public static void reset(){
        enemyHp = enemyHpMax = enemyShield = 0f;
        enemyCount = airCount = groundCount = 0;
        cacheTime = -9999f;
        previewCacheTime = -9999f;
    }

    public static void tick(){
        if(player == null || state == null || state.isMenu()) return;
        if(Time.time - cacheTime < 15f) return;
        cacheTime = Time.time;

        enemyHp = enemyHpMax = enemyShield = 0f;
        enemyCount = airCount = groundCount = 0;
        Team my = player.team();
        for(Unit u : Groups.unit){
            if(u == null || u.dead() || u.team() == my) continue;
            enemyCount++;
            enemyHp += u.health();
            enemyHpMax += u.maxHealth();
            enemyShield += Math.max(0f, u.shield());
            if(u.isFlying()) airCount++; else groundCount++;
        }
    }

    public static void build(Table t){
        t.label(() -> "[white]Волна: [accent]" + state.wave
            + (state.rules.waves && state.rules.waveTimer ? "   [lightgray]следующая через: [white]" + fmtTime(state.wavetime) : "")
            + (state.rules.waves ? "" : "   [darkgray](волны выключены)")
        ).left().pad(2f).row();

        t.table(c -> {
            c.left();
            c.label(() -> "[scarlet]Врагов в бою: [white]" + enemyCount
                + "  [lightgray](воздух [sky]" + airCount + "[lightgray], земля [orange]" + groundCount + ")").left();
        }).left().pad(2f).row();

        // полоса HP текущей волны
        t.table(c -> {
            c.left();
            c.add("[lightgray]HP волны:").padRight(6f).top();
            c.table(bar -> bar.background(MKUKICSkin.panelSoft).add(new Bar(
                () -> Strings.fixed(enemyHp, 0) + (enemyShield > 1f ? " [sky](+" + Strings.fixed(enemyShield, 0) + " щит)" : ""),
                () -> enemyHpMax > 0f && enemyHp / enemyHpMax > 0.5f ? Pal.health : Color.scarlet,
                () -> enemyHpMax <= 0f ? 0f : Mathf.clamp(enemyHp / enemyHpMax)
            ))).width(280f).height(22f);
        }).left().pad(3f).row();

        t.image(Tex.whiteui, Pal.accent).height(1f).growX().pad(5f, 0f, 5f, 0f).row();

        // прогноз следующих волн
        t.add("[accent]Прогноз волн").left().row();
        t.table(list -> {
            list.top().left();
            list.label(() -> previewText()).left().top().wrap().width(430f);
        }).left().pad(3f).row();

        t.image(Tex.whiteui, Pal.accent).height(1f).growX().pad(5f, 0f, 5f, 0f).row();

        t.table(c -> {
            c.left();
            c.button("Пропустить волну", MKUKICSkin.actBtn, () -> {
                if(state == null || !state.rules.waves) return;
                if(player.admin || !net.active()){
                    Call.adminRequest(player, AdminAction.wave, null);
                    MKUKICMod.toast("Волна пропущена");
                }else{
                    MKUKICMod.toast("[scarlet]Нужны права админа");
                }
            }).minWidth(190f).height(36f).pad(2f);
            c.button("Обновить", MKUKICSkin.actBtn, () -> cacheTime = -9999f).minWidth(120f).height(36f).pad(2f);
        }).left().row();

        t.add("[darkgray]Прогноз считается по правилам карты (SpawnGroup). Если карта скрывает волны — точный состав неизвестен.").left().top().wrap().width(430f).padTop(6f).row();
    }

    private static String fmtTime(float ticks){
        int sec = Math.max(0, (int)(ticks / 60f));
        return sec / 60 + ":" + (sec % 60 < 10 ? "0" : "") + sec % 60;
    }

    /** Превью 5 следующих волн (кэш 1 c). */
    private static String previewCache = "";
    private static float previewCacheTime = -9999f;
    private static int previewCacheWave = -1;

    private static String previewText(){
        if(state == null) return "";
        if(Time.time - previewCacheTime < 60f && state.wave == previewCacheWave) return previewCache;
        previewCacheTime = Time.time;
        previewCacheWave = state.wave;
        previewCache = buildPreview();
        return previewCache;
    }

    private static String buildPreview(){
        if(state == null || !state.rules.waves) return "[darkgray]волны отключены правилами карты";
        Seq<SpawnGroup> spawns = state.rules.spawns;
        if(spawns == null || spawns.isEmpty()) return "[darkgray]на карте нет настроенных волн (враги могут приходить иначе)";

        StringBuilder sb = new StringBuilder();
        int maxW = state.wave + 4;
        for(int w = state.wave; w <= maxW; w++){
            ObjectMap<UnitType, int[]> counts = new ObjectMap<>();
            float maxShield = 0f;
            float biggest = 0f;
            UnitType boss = null;
            for(SpawnGroup g : spawns){
                if(!g.canSpawn(w) || g.type == null) continue;
                int n = g.getSpawned(w);
                if(n <= 0) continue;
                int[] arr = counts.get(g.type, () -> new int[1]);
                arr[0] += n;
                float sh = g.getShield(w);
                if(sh > maxShield) maxShield = sh;
                if(g.type.health > biggest){
                    biggest = g.type.health;
                    boss = g.type;
                }
            }
            if(counts.isEmpty()) continue;

            int total = 0;
            for(int[] a : counts.values()) total += a[0];

            sb.append("[white]Волна ").append(w).append(w == state.wave ? " [scarlet](текущая)[]" : "").append(": [accent]").append(total).append("[] юнитов");
            if(maxShield > 1f) sb.append(", [sky]щиты ").append((int)maxShield);
            sb.append('\n');

            // топ-4 типа по количеству
            Seq<UnitType> types = new Seq<>(counts.keys().toSeq());
            types.sort((a, b) -> counts.get(b)[0] - counts.get(a)[0]);
            for(int i = 0; i < Math.min(4, types.size); i++){
                UnitType ty = types.get(i);
                sb.append("   [lightgray]• ").append(ty.localizedName == null ? ty.name : ty.localizedName)
                    .append(" x").append(counts.get(ty)[0]).append('\n');
            }
            if(boss != null && boss.health >= 4000f){
                sb.append("   [scarlet]⚠ БОСС: ").append(boss.localizedName == null ? boss.name : boss.localizedName)
                    .append(" (").append((int)boss.health).append(" HP)[]\n");
            }
            sb.append('\n');
        }
        return sb.length() == 0 ? "[darkgray]ближайших волн не найдено" : sb.toString();
    }
}
