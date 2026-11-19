package mkukic.client;

import arc.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.scene.style.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.util.*;
import mindustry.core.*;
import mindustry.gen.*;
import mindustry.ui.*;
import mkukic.*;

import static mindustry.Vars.*;

/**
 * Плавающая кнопка меню (логотип MKUKIC) + строка статуса активных фич.
 * Строка статуса — элемент сцены: фирменная полупрозрачная плашка с акцентной
 * полосой слева в левом нижнем углу экрана.
 */
public class MKUKICHud{
    private static ImageButton button;
    private static Table statusTable;
    private static Label statusLabel;
    private static final StringBuilder sb = new StringBuilder();
    private static String lastText = "";

    public static TextureRegion icon(){
        MKUKICSkin.init();
        return MKUKICSkin.logo;
    }

    public static void init(){
        MKUKICSkin.init();
        buildButton();
        buildStatus();
    }

    private static void buildButton(){
        if(ui == null || ui.hudGroup == null) return;

        button = new ImageButton(new TextureRegionDrawable(icon()), Styles.emptyi);
        button.resizeImage(34f);
        button.clicked(() -> MKUKICMenu.show());
        button.visible(() -> MKUKICSettings.s().menuButton && state != null && !state.isMenu() && ui.hudfrag.shown());

        ui.hudGroup.addChild(button);
        button.toFront();
        button.top().right();
        button.marginTop(Scl.scl(34f));
        button.marginRight(Scl.scl(6f));
        button.setSize(Scl.scl(46f), Scl.scl(46f));
    }

    // ============================ СТРОКА СТАТУСА ============================

    private static void buildStatus(){
        if(Core.scene == null) return;
        statusTable = new Table();
        statusTable.background(MKUKICSkin.panelSoft);
        statusTable.image(Tex.whiteui, MKUKICSkin.accent).width(3f).fillY().padRight(8f);
        statusLabel = new Label("", new Label.LabelStyle(Fonts.def, Color.white));
        statusTable.add(statusLabel).left().pad(5f, 0f, 5f, 10f);
        statusTable.pack();
        Core.scene.add(statusTable);
        statusTable.visible(() -> {
            MKUKICSettings s = MKUKICSettings.s();
            return s.enabled && s.infoHud && player != null && state != null && !state.isMenu()
                && !(Core.scene != null && Core.scene.hasDialog());
        });
        statusTable.update(() -> {
            String text = statusText();
            if(!text.equals(lastText)){
                lastText = text;
                statusLabel.setText(text);
                statusTable.pack();
            }
            statusTable.setPosition(6f, 5f);
        });
    }

    /** Текст строки статуса (разметка). */
    public static String statusText(){
        MKUKICSettings s = MKUKICSettings.s();
        sb.setLength(0);

        boolean any = false;
        if(s.aimbot){ sb.append("[#ffb454]AIM "); any = true; }
        if(s.speed){ sb.append("[#ffb454]SPD x").append(Strings.autoFixed(s.speedMult, 1)).append(' '); any = true; }
        if(s.rage){ sb.append("[#ff8f7d]DMG x").append(Strings.autoFixed(s.damageMult, 1)).append(' '); any = true; }
        if(s.fortress){ sb.append("[#6fd6ff]HP x").append(Strings.autoFixed(s.healthMult, 1)).append(' '); any = true; }
        if(s.godmode){ sb.append("[#ffe07d]GOD "); any = true; }
        if(s.fly){ sb.append("[#6fd6ff]FLY "); any = true; }
        if(s.instantReload){ sb.append("[#ffb454]RELOAD "); any = true; }
        if(s.esp){ sb.append("[#9dff8f]ESP "); any = true; }
        if(s.aiAutoMine){ sb.append("[#9dff8f]MINE "); any = true; }
        if(s.aiAutoBuild || s.aiAutoRepair){ sb.append("[#9dff8f]BUILD "); any = true; }
        if(s.aiFollow){ sb.append("[#9dff8f]FOLLOW "); any = true; }
        if(s.aiEscort){ sb.append("[#9dff8f]ESCORT "); any = true; }
        if(any) sb.append("[#5f6b7d]| ");

        sb.append("[#8b94a5]FPS ").append(Core.graphics.getFramesPerSecond());
        sb.append("[#5f6b7d]  |  [#8b94a5]волна ").append(state.wave);
        if(!player.dead() && player.unit() != null){
            Unit u = player.unit();
            sb.append("[#5f6b7d]  |  [#8b94a5]").append(u.type.localizedName == null ? u.type.name : u.type.localizedName);
            sb.append("[#e8ecf4] HP [#ffb454]").append((int)u.health()).append("[#8b94a5]/").append((int)u.maxHealth());
            sb.append("[#5f6b7d]  |  [#8b94a5]x").append(World.toTile(u.x)).append(" y").append(World.toTile(u.y));
        }
        if(s.esp){
            sb.append("[#5f6b7d]  |  [#ff8f7d]врагов: ").append(EspRenderer.enemiesNear());
        }
        if(UnitAI.managedCount > 0 && (s.aiAutoMine || s.aiAutoBuild || s.aiFollow || s.aiEscort)){
            sb.append("[#5f6b7d]  |  [#9dff8f]ИИ: ").append(UnitAI.managedCount);
        }
        // v1.16: свежие алерты и проблемы производства — в той же строке
        if(s.qolEnabled && s.attackAlert){
            String chip = mkukic.client.qol.AttackAlert.hudChip();
            if(chip != null) sb.append("[#5f6b7d]  |  ").append(chip);
        }
        if(s.qolEnabled && s.alerts && mkukic.client.qol.AlertList.total() > 0){
            sb.append("[#5f6b7d]  |  ").append(mkukic.client.qol.AlertList.hudChip()).append(" тревог");
        }
        return sb.toString();
    }
}
