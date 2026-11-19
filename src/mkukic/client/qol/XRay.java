package mkukic.client.qol;

import arc.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.math.*;
import arc.math.geom.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.util.*;
import mindustry.content.*;
import mindustry.core.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.type.*;
import mindustry.ui.*;
import mindustry.world.*;
import mindustry.world.blocks.defense.turrets.*;
import mindustry.world.blocks.distribution.*;
import mindustry.world.blocks.liquid.*;
import mindustry.world.blocks.power.*;
import mindustry.world.blocks.production.*;
import mindustry.world.blocks.storage.*;
import mindustry.ctype.*;
import mindustry.world.blocks.units.*;
import mindustry.world.modules.*;
import mkukic.*;
import mkukic.client.*;

import static mindustry.Vars.*;

/**
 * «Просвет» зданий и подсветка жидкостей (v1.16) — порт идей из модов:
 *  - MI2-Utilities «Building X-ray»: видно, ЧЕМ набиты склады, заводы, турели и буферы;
 *  - Wind-DeterMination/Color-the-ducts: квадрат цвета жидкости в центре труб и каналов;
 *  - MinerTools «ConstructorBlockInfo / TurretAlert»: расходники и боезапас поверх блока.
 *
 * Всё рисуется только для зданий в кадре (Core.camera.bounds), только своей команды
 * (или всех — настройкой) и только когда блок реально что-то хранит.
 * Подписи — через общий QolWindows.worldText, поэтому выглядят как остальной мод.
 */
public class XRay{
    private static final Rect view = new Rect();
    private static final Color tmp = new Color();
    private static final StringBuilder sb = new StringBuilder();

    /**
     * Мелкий транспорт не подписываем — иначе экран в каше.
     * Список закрытый и только из ванильных типов: модовые блоки подписываются как обычно.
     */
    private static boolean isTransport(Block b){
        return b instanceof Conveyor || b instanceof ArmoredConveyor || b instanceof StackConveyor
            || b instanceof Duct || b instanceof DuctBridge || b instanceof DuctJunction
            || b instanceof DuctRouter || b instanceof StackRouter || b instanceof Junction
            || b instanceof Router || b instanceof ItemBridge || b instanceof Sorter
            || b instanceof OverflowGate || b instanceof OverflowDuct
            || b instanceof DirectionLiquidBridge || isLiquidBlock(b);
    }

    /** Трубы/каналы: рисуем в них цветной квадрат жидкости. */
    private static boolean isLiquidBlock(Block b){
        return b instanceof LiquidBlock || b instanceof LiquidBridge
            || b instanceof LiquidJunction || b instanceof LiquidRouter
            || b instanceof DirectionLiquidBridge;
    }

    public static void draw(){
        MKUKICSettings s = MKUKICSettings.s();
        if(!s.enabled || !s.qolEnabled) return;
        if(!s.xray && !s.xrayDucts) return;
        if(player == null || state == null || state.isMenu() || world == null) return;
        if(Core.scene != null && (Core.scene.hasDialog() || Core.scene.hasField())) return;

        Core.camera.bounds(view).grow(64f);
        int x0 = World.toTile(view.x), y0 = World.toTile(view.y);
        int x1 = World.toTile(view.x + view.width), y1 = World.toTile(view.y + view.height);
        if(x1 - x0 > 320 || y1 - y0 > 320){
            // слишком далеко отзумили — подписи нечитаемы, а обход дорого стоит
            if(!s.xrayZoomedOut) return;
        }

        Draw.z(Layer.overlayUI);
        Team my = player.team();

        for(int tx = Math.max(0, x0); tx <= Math.min(world.width() - 1, x1); tx++){
            for(int ty = Math.max(0, y0); ty <= Math.min(world.height() - 1, y1); ty++){
                Tile tile = world.tile(tx, ty);
                if(tile == null) continue;
                Building b = tile.build;
                if(b == null || b.block == null) continue;
                if(b.team != my && !s.xrayAllTeams) continue;
                if(b.isPayload()) continue;

                if(s.xrayDucts && isLiquidBlock(b.block)) drawLiquidSquare(b, s);
                if(s.xray) drawContents(b, s);
            }
        }
        Draw.reset();
    }

    // ============================ ЦВЕТ ЖИДКОСТИ В ТРУБАХ ============================

    private static void drawLiquidSquare(Building b, MKUKICSettings s){
        LiquidModule lm = b.liquids;
        if(lm == null) return;
        Liquid l = lm.current();
        float amount = lm.currentAmount();
        if(l == null || l == Liquids.water && amount < 0.6f) return;
        if(amount < 0.35f) return;

        float size = Mathf.clamp(s.xrayDuctScale, 0.1f, 1f) * tilesize * 0.72f;
        float fill = Mathf.clamp(amount / Math.max(1f, b.block.liquidCapacity), 0.15f, 1f);
        Draw.color(tmp.set(l.color).mul(0.85f, 0.85f, 0.85f, 1f).a(0.35f + 0.5f * fill));
        Fill.square(b.x, b.y, size / 2f);
        Draw.reset();
    }

    // ============================ СОДЕРЖИМОЕ ЗДАНИЙ ============================

    private static void drawContents(Building b, MKUKICSettings s){
        Block block = b.block;
        if(isTransport(block)) return;

        sb.setLength(0);

        // --- предметы ---
        if(block.hasItems && b.items != null && b.items.total() > 0){
            int shown = 0;
            for(Item item : content.items()){
                if(item == null) continue;
                int n = b.items.get(item);
                if(n <= 0) continue;
                if(shown++ > 0) sb.append(' ');
                sb.append('[').append(item.color.toString()).append(']').append(shortName(item)).append(' ').append(n).append("[]");
                if(shown >= 3) break;
            }
        }

        // --- жидкости (не трубы — они рисуются квадратом) ---
        if(block.hasLiquids && b.liquids != null && !isLiquidBlock(block)){
            Liquid l = b.liquids.current();
            float a = b.liquids.currentAmount();
            if(l != null && a >= 1f){
                if(sb.length() > 0) sb.append(' ');
                sb.append('[').append(l.color.toString()).append(']').append(shortName(l))
                    .append(' ').append((int)a).append('/').append((int)block.liquidCapacity).append("[]");
            }
        }

        // --- боезапас турели (только предметные: у жидкостных totalAmmo всегда 0) ---
        if(b instanceof Turret.TurretBuild t && (block instanceof ItemTurret)){
            if(sb.length() > 0) sb.append(' ');
            if(t.totalAmmo <= 0) sb.append("[scarlet]нет БК[]");
            else sb.append("[#ffd37f]БК ").append(t.totalAmmo).append("[]");
        }

        // --- прогресс производства ---
        String prog = progressOf(b);
        if(prog != null){
            if(sb.length() > 0) sb.append(' ');
            sb.append("[#9dff8f]").append(prog).append("[]");
        }

        // --- содержимое грузового отсека ---
        if(b.getPayload() != null){
            if(sb.length() > 0) sb.append(' ');
            sb.append("[#6fd6ff]").append((char)Iconc.upload).append(" груз[]");
        }

        // --- энергия: только когда плохо ---
        if(s.xrayPower && b.power != null && block.consumesPower){
            float st = b.power.status;
            if(st < 0.9f){
                if(sb.length() > 0) sb.append(' ');
                sb.append(st <= 0.01f ? "[scarlet]нет питания[]" : "[orange]⚡" + (int)(st * 100f) + "%[]");
            }
        }

        if(sb.length() == 0) return;

        float y = b.y + block.size * tilesize / 2f + 7f;
        QolWindows.worldText(sb.toString(), b.x, y, tmp.set(Color.white));
    }

    /** Прогресс завода/реконструктора/крафтера в процентах или null. */
    private static @Nullable String progressOf(Building b){
        try{
            if(b instanceof UnitFactory.UnitFactoryBuild f && f.currentPlan >= 0){
                return (int)(f.fraction() * 100f) + "%";
            }
            if(b instanceof Reconstructor.ReconstructorBuild r){
                return (int)(r.fraction() * 100f) + "%";
            }
            if(b instanceof GenericCrafter.GenericCrafterBuild c){
                return (int)(Mathf.clamp(c.progress, 0f, 1f) * 100f) + "%";
            }
        }catch(Throwable ignored){}
        return null;
    }

    private static String shortName(UnlockableContent c){
        String n = c.localizedName == null ? c.name : c.localizedName;
        if(n.length() <= 5) return n;
        // длинные названия укорачиваем, чтобы не перекрывать соседние блоки
        return n.substring(0, Math.min(5, n.length())) + ".";
    }

    // ============================ ОКНО (шпаргалка) ============================

    public static void build(Table t){
        QolWindows.section(t, "Просвет зданий");
        t.add("[lightgray]Рисует прямо на блоках, что внутри: предметы, жидкости, боезапас турелей,\n"
            + "прогресс заводов и нехватку питания. Плюс цветной квадрат в трубах — сразу видно,\n"
            + "какая жидкость куда течёт (идея Color-the-ducts).")
            .left().top().wrap().width(360f).pad(3f, 0f, 6f, 0f).row();

        t.table(c -> {
            c.left();
            c.check("Подписи содержимого", MKUKICSettings.s().xray, v -> {
                MKUKICSettings.s().xray = v;
                MKUKICSettings.save();
            }).padRight(10f);
            c.check("Цвет жидкости в трубах", MKUKICSettings.s().xrayDucts, v -> {
                MKUKICSettings.s().xrayDucts = v;
                MKUKICSettings.save();
            });
        }).left().row();

        t.table(c -> {
            c.left();
            c.check("Чужие команды тоже", MKUKICSettings.s().xrayAllTeams, v -> {
                MKUKICSettings.s().xrayAllTeams = v;
                MKUKICSettings.save();
            }).padRight(10f);
            c.check("Показывать питание", MKUKICSettings.s().xrayPower, v -> {
                MKUKICSettings.s().xrayPower = v;
                MKUKICSettings.save();
            });
        }).left().row();

        t.table(c -> {
            c.left();
            c.add("[#c2cad8]Размер квадрата в трубах").padRight(6f);
            c.slider(0.2f, 1f, 0.05f, MKUKICSettings.s().xrayDuctScale, v -> {
                MKUKICSettings.s().xrayDuctScale = v;
                MKUKICSettings.save();
            }).width(150f);
            c.label(() -> Strings.autoFixed(MKUKICSettings.s().xrayDuctScale, 2)).padLeft(6f);
        }).left().padTop(4f).row();

        t.add("[#7e8798]Мелкий транспорт (конвейеры, трубы, сортировщики) не подписывается — иначе экран нечитаем.\n"
            + "При очень дальнем зуме подписи отключаются (можно вернуть в меню F9 → QoL).").left().top().wrap().width(360f).padTop(6f).row();
    }
}
