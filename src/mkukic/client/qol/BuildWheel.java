package mkukic.client.qol;

import arc.*;
import arc.graphics.*;
import arc.input.*;
import arc.math.*;
import arc.math.geom.*;
import arc.scene.*;
import arc.scene.event.*;
import arc.scene.style.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.type.*;
import mindustry.ui.*;
import mindustry.world.*;
import mindustry.world.meta.*;
import mkukic.*;
import mkukic.client.*;

import static mindustry.Vars.*;

/**
 * Радиальное строительное меню (порт Neon Radial Build) + полный каталог блоков.
 *
 * F2 — колесо у курсора: кольцо «избранное + недавние», центр — категории/закрыть.
 * Выбор блока ставит его в режим размещения (control.input.block) — как клик по
 * ванильной панели строительства, поэтому дальше всё работает штатно (в т.ч. в MP).
 * Недавние блоки отслеживаются автоматически; избранное — звёздочкой в каталоге.
 *
 * Окно «Каталог блоков» (id: blocks) — сетка всех доступных блоков по категориям.
 * Полярная раскладка кнопок — чистая функция sectorPos (покрыта FeatureTest).
 */
public class BuildWheel{
    public static final int maxRing = 12;
    private static final float radius = 150f;
    private static final float btnSize = 52f;

    private static Group group;
    private static Label hoverLabel;
    private static boolean open;
    private static String view = "recent";   // recent | cats | blocks
    private static Category cat;
    private static int page;
    private static Block lastSeen;

    // ============================ ЧИСТАЯ ГЕОМЕТРИЯ (тестируется) ============================

    /** Позиция i-й кнопки кольца из total штук (начиная сверху, по часовой). Возвращает [x,y] центра кнопки. */
    public static float[] sectorPos(int i, int total, float cx, float cy, float r){
        float a = 90f - i * 360f / Math.max(1, total);
        return new float[]{cx + Angles.trnsx(a, r), cy + Angles.trnsy(a, r)};
    }

    public static int wrapPage(int p, int pages){
        if(pages <= 1) return 0;
        return ((p % pages) + pages) % pages;
    }

    // ============================ КОЛЕСО ============================

    public static boolean isOpen(){
        return open;
    }

    public static void toggle(){
        if(open) close(); else show();
    }

    public static void show(){
        if(headless || Core.scene == null) return;
        if(group == null) createGroup();
        Vec2 v = Core.scene.screenToStageCoordinates(new Vec2(Core.input.mouseX(), Core.input.mouseY()));
        float cx = Mathf.clamp(v.x, radius + btnSize, Core.scene.getWidth() - radius - btnSize);
        float cy = Mathf.clamp(v.y, radius + btnSize, Core.scene.getHeight() - radius - btnSize);
        view = "recent";
        open = true;
        group.visible = true;
        group.toFront();
        rebuild(cx, cy);
    }

    public static void close(){
        open = false;
        if(group != null) group.visible = false;
    }

    private static void createGroup(){
        group = new WidgetGroup();
        group.setFillParent(true);
        group.visible = false;
        Core.scene.add(group);
    }

    private static void rebuild(float cx, float cy){
        group.clearChildren();

        // фон: клик мимо колеса = закрыть (правый клик — тоже)
        Image bg = new Image(Tex.clear);
        bg.setFillParent(true);
        bg.setOrigin(Align.center);
        bg.touchable = Touchable.enabled;
        bg.addListener(new InputListener(){
            @Override
            public boolean touchDown(InputEvent e, float x, float y, int pointer, KeyCode button){
                close();
                return true;
            }
        });
        group.addChild(bg);

        Seq<Block> ring = new Seq<>();
        if(view.equals("recent")){
            ring.addAll(favAndRecent());
        }else if(view.equals("blocks") && cat != null){
            Seq<Block> all = catBlocks(cat);
            int pages = Math.max(1, (all.size + maxRing - 1) / maxRing);
            page = wrapPage(page, pages);
            for(int i = page * maxRing; i < Math.min(all.size, (page + 1) * maxRing); i++) ring.add(all.get(i));
        }

        if(view.equals("cats") || (view.equals("recent") && ring.isEmpty())){
            // кольцо категорий
            Category[] cs = Category.all;
            for(int i = 0; i < cs.length; i++){
                final Category c = cs[i];
                Block rep = representative(c);
                float[] p = sectorPos(i, cs.length, cx, cy, radius);
                if(rep != null){
                    ringButton(new TextureRegionDrawable(rep.uiIcon), rep.localizedName, p, () -> {
                        cat = c;
                        page = 0;
                        view = "blocks";
                        rebuild(cx, cy);
                    });
                }else{
                    ringButton(Tex.whiteui, catName(c), p, () -> {
                        cat = c;
                        page = 0;
                        view = "blocks";
                        rebuild(cx, cy);
                    });
                }
            }
            hoverText(cx, cy, "Категории" + (view.equals("recent") ? " (список пуст)" : ""));
        }else{
            for(int i = 0; i < ring.size; i++){
                final Block b = ring.get(i);
                float[] p = sectorPos(i, ring.size, cx, cy, radius);
                ringButton(new TextureRegionDrawable(b.uiIcon), b.localizedName, p, () -> pick(b));
            }
            hoverText(cx, cy, view.equals("blocks") ? catName(cat) + " (стр. " + (page + 1) + ")" : "Быстрое строительство");
        }

        // центральные кнопки
        centerButton(cx, cy, 0f, "[scarlet]✕", () -> close());
        if(view.equals("recent")){
            centerButton(cx, cy, 90f, "[accent]≡", () -> {
                view = "cats";
                rebuild(cx, cy);
            });
        }else if(view.equals("blocks")){
            centerButton(cx, cy, 180f, "[accent]‹", () -> {
                page = wrapPage(page - 1, Math.max(1, (catBlocks(cat).size + maxRing - 1) / maxRing));
                rebuild(cx, cy);
            });
            centerButton(cx, cy, 0f, "[accent]›", () -> {
                page = wrapPage(page + 1, Math.max(1, (catBlocks(cat).size + maxRing - 1) / maxRing));
                rebuild(cx, cy);
            });
            centerButton(cx, cy, 270f, "[lightgray]↑", () -> {
                view = "cats";
                rebuild(cx, cy);
            });
        }else{
            centerButton(cx, cy, 270f, "[lightgray]↑", () -> {
                view = "recent";
                rebuild(cx, cy);
            });
        }
    }

    private static void hoverText(float cx, float cy, String title){
        hoverLabel = new Label(title, Styles.outlineLabel);
        hoverLabel.setAlignment(Align.center);
        hoverLabel.setWidth(260f);
        hoverLabel.setPosition(cx - 130f, cy - radius - 34f);
        hoverLabel.touchable = Touchable.disabled;
        group.addChild(hoverLabel);
    }

    private static void ringButton(Drawable icon, String name, float[] pos, Runnable action){
        ImageButton b = new ImageButton(icon, Styles.defaulti);
        b.setSize(btnSize, btnSize);
        b.setPosition(pos[0] - btnSize / 2f, pos[1] - btnSize / 2f);
        b.addListener(new ChangeListener(){
            @Override
            public void changed(ChangeEvent e, Element el){
                action.run();
            }
        });
        b.update(() -> {
            if(hoverLabel != null && b.isOver()) hoverLabel.setText(name);
        });
        group.addChild(b);
    }

    private static void centerButton(float cx, float cy, float angleDeg, String text, Runnable action){
        float r = angleDeg == 0f ? 0f : 58f;
        float bx = r == 0f ? cx : cx + Angles.trnsx(angleDeg, r);
        float by = r == 0f ? cy : cy + Angles.trnsy(angleDeg, r);
        TextButton b = new TextButton(text, MKUKICSkin.actBtn);
        b.setSize(40f, 40f);
        b.setPosition(bx - 20f, by - 20f);
        b.getLabel().setAlignment(Align.center);
        b.addListener(new ChangeListener(){
            @Override
            public void changed(ChangeEvent e, Element el){
                action.run();
            }
        });
        group.addChild(b);
    }

    private static void pick(Block b){
        if(control != null && control.input != null && b != null){
            control.input.block = b;
            pushRecent(b.name);
            MKUKICMod.toast("[accent]" + (char)Iconc.ok + " [white]" + b.localizedName);
        }
        close();
    }

    // ============================ ИЗБРАННОЕ / НЕДАВНИЕ ============================

    public static Seq<Block> favAndRecent(){
        Seq<Block> out = new Seq<>();
        MKUKICSettings s = MKUKICSettings.s();
        for(String n : s.wheelFav){
            Block b = content.block(n);
            if(b != null && wheelable(b) && !out.contains(b, true)) out.add(b);
            if(out.size >= maxRing) return out;
        }
        for(String n : s.wheelRecent){
            Block b = content.block(n);
            if(b != null && wheelable(b) && !out.contains(b, true)) out.add(b);
            if(out.size >= maxRing) break;
        }
        return out;
    }

    public static void pushRecent(String name){
        Seq<String> r = MKUKICSettings.s().wheelRecent;
        r.remove(name);
        r.insert(0, name);
        while(r.size > 10) r.pop();
        MKUKICSettings.save();
    }

    public static void toggleFav(String name){
        Seq<String> f = MKUKICSettings.s().wheelFav;
        if(!f.remove(name)) f.add(name);
        while(f.size > maxRing) f.remove(0);
        MKUKICSettings.save();
    }

    /** Отслеживание текущего выбора ванильной панели — каждый кадр в игре (из Qol.tick). */
    public static void trackRecent(){
        if(control == null || control.input == null) return;
        Block b = control.input.block;
        if(b != null && b != lastSeen && wheelable(b)){
            lastSeen = b;
            pushRecent(b.name);
        }else if(b == null){
            lastSeen = null;
        }
    }

    // ============================ КАТАЛОГ ============================

    public static boolean wheelable(Block b){
        return b != null && b.isPlaceable() && b.buildVisibility == BuildVisibility.shown && b.unlockedNowHost();
    }

    public static Seq<Block> catBlocks(Category c){
        Seq<Block> out = new Seq<>();
        for(Block b : content.blocks()){
            if(b.category == c && wheelable(b)) out.add(b);
        }
        out.sort((x, y) -> x.localizedName.compareTo(y.localizedName));
        return out;
    }

    static Block representative(Category c){
        Seq<Block> l = catBlocks(c);
        return l.size > 0 ? l.first() : null;
    }

    static String catName(Category c){
        if(c == null) return "?";
        switch(c){
            case turret: return "Турели";
            case production: return "Добыча";
            case distribution: return "Логистика";
            case liquid: return "Жидкости";
            case power: return "Энергия";
            case defense: return "Оборона";
            case crafting: return "Производство";
            case units: return "Юниты";
            case effect: return "Поддержка";
            case logic: return "Логика";
            default: return c.name();
        }
    }

    private static Category gridCat = Category.distribution;
    private static int gridPage;
    private static final int gridPerPage = 30;

    /** Окно-каталог (id: blocks). */
    public static void buildGrid(Table t){
        t.add("[lightgray]Клик по блоку — выбрать для размещения (как на ванильной панели).\n"
            + "[accent]★[] — добавить в избранное (попадает в колесо F2).").left().top().wrap().width(430f).pad(3f, 0f, 6f, 0f).row();

        Table holder = new Table();
        Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> {
            holder.clearChildren();
            // строка категорий
            holder.table(c -> {
                c.left();
                for(Category cc : Category.all){
                    c.button(catName(cc), MKUKICSkin.toggleBtn, () -> {
                        gridCat = cc;
                        gridPage = 0;
                        refresh[0].run();
                    }).checked(b -> gridCat == cc).minWidth(92f).height(30f).pad(1.5f);
                }
            }).left().row();
            holder.image(Tex.whiteui, Pal.accent).height(1.5f).growX().pad(4f, 2f, 4f, 2f).row();

            Seq<Block> list = catBlocks(gridCat);
            int pages = Math.max(1, (list.size + gridPerPage - 1) / gridPerPage);
            gridPage = wrapPage(gridPage, pages);
            Table grid = holder.table(g -> {}).pad(2f).get();
            // сетка иконка + звезда в строке
            Table rows = new Table();
            for(int i = gridPage * gridPerPage; i < Math.min(list.size, (gridPage + 1) * gridPerPage); i++){
                final Block b = list.get(i);
                rows.table(r -> {
                    r.left();
                    ImageButton ib = new ImageButton(new TextureRegionDrawable(b.uiIcon), Styles.defaulti);
                    ib.getImageCell().size(32f);
                    ib.addListener(new ChangeListener(){
                        @Override
                        public void changed(ChangeEvent e, Element el){
                            if(control != null && control.input != null){
                                control.input.block = b;
                                pushRecent(b.name);
                                MKUKICMod.toast("[accent]" + (char)Iconc.ok + " [white]" + b.localizedName);
                            }
                        }
                    });
                    r.add(ib).size(40f).padRight(4f);
                    r.add(b.localizedName).left().growX().wrap().width(220f);
                    r.button(MKUKICSettings.s().wheelFav.contains(b.name) ? "[gold]★" : "[darkgray]☆", MKUKICSkin.actBtn, () -> {
                        toggleFav(b.name);
                        refresh[0].run();
                    }).size(40f).padLeft(4f);
                }).left().growX().row();
            }
            grid.add(rows).left().top().growX().row();
            // пейджер
            if(pages > 1){
                grid.table(c -> {
                    c.button("‹", MKUKICSkin.actBtn, () -> {
                        gridPage = wrapPage(gridPage - 1, pages);
                        refresh[0].run();
                    }).size(36f);
                    c.add("  " + (gridPage + 1) + " / " + pages + "  ").color(Color.lightGray);
                    c.button("›", MKUKICSkin.actBtn, () -> {
                        gridPage = wrapPage(gridPage + 1, pages);
                        refresh[0].run();
                    }).size(36f);
                }).padTop(4f).row();
            }
        };
        refresh[0].run();
        t.pane(holder).growX().maxHeight(Core.graphics.getHeight() * 0.5f).row();
        t.table(c -> {
            c.left();
            c.button((char)Iconc.production + " Колесо строительства (F2)", MKUKICSkin.actBtn, BuildWheel::toggle)
                .minWidth(230f).height(34f).pad(4f, 2f, 4f, 2f);
        }).left().row();
    }
}
