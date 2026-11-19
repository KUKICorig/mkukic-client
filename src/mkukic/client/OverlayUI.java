package mkukic.client;

import arc.*;
import arc.input.*;
import arc.math.*;
import arc.scene.*;
import arc.graphics.g2d.*;
import arc.util.Align;
import arc.util.Time;
import mindustry.gen.Iconc;
import mindustry.gen.Tex;
import mindustry.graphics.Pal;
import arc.scene.event.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import mindustry.ui.*;
import mkukic.*;

import static mindustry.Vars.*;

/**
 * Плавающие панели быстрого доступа поверх игры:
 *  - ВЕРТИКАЛЬНАЯ ПАНЕЛЬ ВКЛАДОК: по умолчанию справа по центру, заголовок «MKUKIC» (за него
 *    перетаскивается), кнопки всех вкладок меню + кнопка окна команд; сворачивается в кнопку «N».
 *  - ОКНО СВОИХ КОМАНД: по умолчанию слева по центру, перетаскивается за заголовок.
 * Позиции запоминаются в настройках, каждый кадр зажимаются в границы экрана.
 */
public class OverlayUI{
    private static Table toolbar, cmdBar;
    private static boolean built;
    private static boolean hintShown;
    private static float lastToFront = -9999f;
    // предыдущая видимость — для toFront() при переходе «скрыт -> виден».
    // ВАЖНО (v8): arc.scene.Group.act() НЕ вызывает act/update у невидимых детей,
    // поэтому переключать .visible из update-runnable нельзя — элемент «умрёт» скрытым
    // навсегда. Видимость задаётся через visible(Boolp): updateVisibility() выполняется
    // для всех детей каждый кадр, даже скрытых.
    private static boolean prevToolbarVis, prevCmdBarVis;

    public static void init(){
        if(built || Core.scene == null) return;
        built = true;
        MKUKICSkin.init();
        buildToolbar();
        rebuildCmdBar();
    }

    // ============================ ПАНЕЛЬ ВКЛАДОК ============================

    private static void buildToolbar(){
        toolbar = new Table();
        toolbar.background(MKUKICSkin.panel);
        rebuildToolbarBody();
        Core.scene.add(toolbar);
        toolbar.visible(() -> {
            MKUKICSettings s = MKUKICSettings.s();
            // окно ВСЕГДА видно в игре, но прячется, когда открыто меню MKUKIC или ванильное UI
            boolean vis = state != null && !state.isMenu() && s.enabled
                && !(Core.scene != null && Core.scene.hasDialog());
            if(vis && !prevToolbarVis) toolbar.toFront();
            prevToolbarVis = vis;
            return vis;
        });
        toolbar.update(() -> {
            // держим поверх окна команд, чтобы панель не терялась за ним
            if(Time.time - lastToFront > 90f && !MKUKICMenu.isShown()){
                lastToFront = Time.time;
                toolbar.toFront();
            }
            clampPos(toolbar);
            if(!hintShown){
                hintShown = true;
                MKUKICMod.toast("[#ffb454]◆ MKUKIC[] — окно вкладок справа. Тяни за шапку, «–» — свернуть");
            }
        });
    }

    private static void rebuildToolbarBody(){
        toolbar.clearChildren();
        MKUKICSettings s = MKUKICSettings.s();

        if(s.toolbarCollapsed){
            // свёрнуто: кнопка с логотипом (клик — развернуть, тянуть можно прямо за неё)
            ImageButton.ImageButtonStyle st = new ImageButton.ImageButtonStyle(Styles.emptyi);
            st.imageUp = MKUKICSkin.logoDrawable;
            st.up = MKUKICSkin.panelSoft;
            st.over = MKUKICSkin.panelSoft;
            st.down = MKUKICSkin.panel;
            ImageButton b = new ImageButton(st);
            b.resizeImage(38f);
            b.clicked(() -> {
                MKUKICSettings s2 = MKUKICSettings.s();
                s2.toolbarCollapsed = false;
                MKUKICSettings.save();
                rebuildToolbarBody();
            });
            toolbar.add(b).size(50f).pad(3f);
            makeDraggable(toolbar, b, OverlayUI::saveToolbarPos);
        }else{
            // заголовок = ручка перетаскивания
            Table head = toolbar.table(h -> {
                h.left();
                h.image(MKUKICSkin.logoDrawable).size(20f).padLeft(7f).padRight(7f);
                h.add("[#ffb454]MKUKIC[]").padRight(10f);
                h.add().growX();
                h.button("–", MKUKICSkin.iconBtn, () -> {
                    MKUKICSettings st = MKUKICSettings.s();
                    st.toolbarCollapsed = true;
                    MKUKICSettings.save();
                    rebuildToolbarBody();
                }).size(30f).padRight(4f);
            }).growX().height(34f).pad(0f).get();
            head.setBackground(MKUKICSkin.head);
            makeDraggable(toolbar, head, OverlayUI::saveToolbarPos);
            toolbar.row();

            toolbar.table(g -> {
                g.defaults().pad(1.5f);
                int[][] groups = MKUKICMenu.navGroups();
                String[] names = MKUKICMenu.tabLabels();
                for(int gi = 0; gi < groups.length; gi++){
                    g.add("[#5f6b7d]" + MKUKICMenu.navGroupTitle(gi)).left().padLeft(8f).padTop(gi == 0 ? 0f : 6f).row();
                    for(int idx : groups[gi]){
                        final int fi = idx;
                        TextButton tb = g.button("", MKUKICSkin.navBtn, () -> MKUKICMenu.showTab(fi)).width(210f).height(34f).get();
                        tb.getLabel().setAlignment(Align.left);
                        tb.update(() -> {
                            int n = MKUKICMenu.tabCount(fi);
                            tb.setText(MKUKICMenu.tabIcon(names[fi]) + "  " + MKUKICMenu.tabTitle(fi) + (n > 0 ? "   [#ffb454]●" + n + "[]" : ""));
                        });
                        g.row();
                    }
                }
            }).pad(5f, 4f, 2f, 4f).row();
            toolbar.image(MKUKICSkin.divSoft).height(2f).growX().pad(3f, 8f, 3f, 8f).row();
            TextButton pb = toolbar.button(Ico.of("star") + " Быстрые переключатели", MKUKICSkin.actBtn, QuickPalette::toggle)
                .width(222f).height(36f).padBottom(4f).get();
            pb.getLabel().setAlignment(Align.left);
            TextButton cb = toolbar.button(Ico.of("terminal") + " Окно команд", MKUKICSkin.actBtn, () -> {
                MKUKICSettings st = MKUKICSettings.s();
                st.cmdBarVisible = !st.cmdBarVisible;
                MKUKICSettings.save();
            }).width(222f).height(36f).padBottom(7f).get();
            cb.getLabel().setAlignment(Align.left);
        }
        toolbar.pack();
        clampPos(toolbar);
    }

    /** Развернуть панель извне (например, после сворачивания). */
    public static void refreshToolbar(){
        if(toolbar != null) rebuildToolbarBody();
    }

    private static void saveToolbarPos(){
        MKUKICSettings s = MKUKICSettings.s();
        s.toolbarX = toolbar.x;
        s.toolbarY = toolbar.y;
        MKUKICSettings.save();
    }

    // ============================ ОКНО КОМАНД ============================

    public static void rebuildCmdBar(){
        if(Core.scene == null) return;
        if(cmdBar != null) cmdBar.remove();
        MKUKICSettings s = MKUKICSettings.s();

        cmdBar = new Table();
        cmdBar.background(MKUKICSkin.panel);

        Table head = cmdBar.table(h -> {
            h.left();
            h.add("[#ffb454]" + (char)Iconc.terminal + "[] [#e8ecf4]команды[]").padLeft(8f).padRight(10f);
            h.add().growX();
            h.button("✕", MKUKICSkin.iconBtn, () -> {
                MKUKICSettings.s().cmdBarVisible = false;
                MKUKICSettings.save();
            }).size(26f).padRight(3f);
        }).growX().height(30f).get();
        head.setBackground(MKUKICSkin.head);
        makeDraggable(cmdBar, head, () -> {
            MKUKICSettings s2 = MKUKICSettings.s();
            s2.cmdBarX = cmdBar.x;
            s2.cmdBarY = cmdBar.y;
            MKUKICSettings.save();
        });
        cmdBar.row();

        for(String e : s.cmdEntries){
            int sep = e.indexOf('|');
            String label = sep < 0 ? e : e.substring(0, sep);
            String cmd = sep < 0 ? e : e.substring(sep + 1);
            cmdBar.button(label, MKUKICSkin.actBtn, () -> AdminNet.runCommand(cmd))
                .minWidth(160f).height(36f).pad(2f, 4f, 2f, 4f);
            if(s.cmdBarVertical) cmdBar.row();
        }
        if(s.cmdEntries.isEmpty()){
            cmdBar.add("[#8b94a5]пусто: Утилиты → Свои команды").pad(6f);
        }
        cmdBar.row();

        cmdBar.pack();
        Core.scene.add(cmdBar);
        // плавающее окно вкладок всегда ПОВЕРХ окна команд
        if(toolbar != null) toolbar.toFront();
        applyCmdBarPosition();
        // v8: видимость — через visible(Boolp) (см. комментарий в начале класса);
        // update-runnable у скрытого элемента не вызывается и «оживить» его не сможет.
        cmdBar.visible(() -> {
            MKUKICSettings st = MKUKICSettings.s();
            boolean vis = state != null && !state.isMenu() && st.enabled && st.cmdBarVisible;
            if(vis && !prevCmdBarVis) cmdBar.toFront();
            prevCmdBarVis = vis;
            return vis;
        });
        cmdBar.update(() -> {
            clampPos(cmdBar);
            cmdBar.color.a = MKUKICSettings.s().cmdBarOpacity;
        });
    }

    // =============================== ОБЩЕЕ ===============================

    public static void resetPositions(){
        MKUKICSettings s = MKUKICSettings.s();
        s.toolbarX = s.toolbarY = s.cmdBarX = s.cmdBarY = -1;
        MKUKICSettings.save();
        applyPositions();
    }

    private static void applyPositions(){
        if(toolbar != null){
            MKUKICSettings s = MKUKICSettings.s();
            if(s.toolbarX < 0){
                // по умолчанию: справа по центру
                toolbar.setPosition(Core.scene.getWidth() - toolbar.getWidth() - 10f,
                    (Core.scene.getHeight() - toolbar.getHeight()) / 2f);
            }else{
                toolbar.setPosition(s.toolbarX, s.toolbarY);
            }
            clampPos(toolbar);
        }
        applyCmdBarPosition();
    }

    private static void applyCmdBarPosition(){
        if(cmdBar == null) return;
        MKUKICSettings s = MKUKICSettings.s();
        if(s.cmdBarX < 0){
            // по умолчанию: слева по центру
            cmdBar.setPosition(10f, (Core.scene.getHeight() - cmdBar.getHeight()) / 2f);
        }else{
            cmdBar.setPosition(s.cmdBarX, s.cmdBarY);
        }
        clampPos(cmdBar);
    }

    private static void clampPos(Table t){
        t.setPosition(
            Mathf.clamp(t.x, 0f, Math.max(0f, Core.scene.getWidth() - t.getWidth())),
            Mathf.clamp(t.y, 0f, Math.max(0f, Core.scene.getHeight() - t.getHeight())));
    }

    /** Перетаскивание элемента за ручку. */
    private static void makeDraggable(Table target, Element handle, Runnable onSave){
        handle.addListener(new InputListener(){
            float ox, oy;
            @Override
            public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button){
                ox = x;
                oy = y;
                return true;
            }
            @Override
            public void touchDragged(InputEvent event, float x, float y, int pointer){
                target.setPosition(target.x + (x - ox), target.y + (y - oy));
                clampPos(target);
            }
            @Override
            public void touchUp(InputEvent event, float x, float y, int pointer, KeyCode button){
                onSave.run();
            }
        });
    }
}
