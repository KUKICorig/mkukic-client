package mkukic.client.qol;

import arc.*;
import arc.input.*;
import arc.math.*;
import arc.math.geom.*;
import arc.scene.*;
import arc.scene.event.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import mindustry.gen.*;
import mindustry.ui.*;
import mkukic.*;
import mkukic.client.*;
import mkukic.client.onix.*;

import static mindustry.Vars.*;

/**
 * Колесо быстрых сообщений.
 *
 * Клавиша (mkukic_quick_msg, по умолчанию V; переназначается в Настройки → Управление → mkukic)
 * открывает у курсора кольцо кнопок. Клик по кнопке — сообщение сразу уходит в чат.
 * Клик мимо колеса / ПКМ / ✕ — закрыть, ⚙ — открыть окно редактирования списка.
 *
 * Каждая запись хранится как «подпись|текст»: подпись — коротко на кнопке,
 * текст — то, что реально отправится (можно и команду, например /msg 5 привет).
 * Подстановки %s %c %h работают, если включены в Onix (см. OnixChat.send).
 *
 * Окно «Быстрые сообщения» (id: quickmsg) — редактор списка: подпись, текст, порядок, удаление.
 */
public class QuickMsgWheel{
    public static final int maxSlots = 10;

    private static final float rx = 230f, ry = 165f;   // полуоси эллипса кольца
    private static final float bw = 140f, bh = 34f;    // размер кнопки
    private static final int labelMax = 16;

    private static Group group;
    private static Label hoverLabel;
    private static boolean open;
    private static boolean savePending;

    // ============================ ЧИСТАЯ ЛОГИКА ============================

    /** Центр i-й кнопки на эллипсе из total штук (сверху, по часовой). */
    public static float[] slotPos(int i, int total, float cx, float cy, float rx, float ry){
        float a = 90f - i * 360f / Math.max(1, total);
        return new float[]{cx + Angles.trnsx(a, rx), cy + Angles.trnsy(a, ry)};
    }

    public static String labelOf(String e){
        if(e == null) return "";
        int i = e.indexOf('|');
        return i < 0 ? e : e.substring(0, i);
    }

    public static String textOf(String e){
        if(e == null) return "";
        int i = e.indexOf('|');
        return i < 0 ? e : e.substring(i + 1);
    }

    public static String entry(String label, String text){
        return label.replace("|", "") + "|" + text;
    }

    public static String cut(String s, int max){
        if(s == null) return "";
        return s.length() <= max ? s : s.substring(0, Math.max(0, max - 1)) + "…";
    }

    private static Seq<String> list(){
        return MKUKICSettings.s().quickMsgs;
    }

    private static void saveLater(){
        if(savePending) return;
        savePending = true;
        Timer.schedule(() -> {
            savePending = false;
            MKUKICSettings.save();
        }, 0.8f);
    }

    // ============================ КОЛЕСО ============================

    public static boolean isOpen(){
        return open;
    }

    public static void toggle(){
        if(open) close(); else show();
    }

    public static void show(){
        if(headless || Core.scene == null || state == null || state.isMenu()) return;
        if(Core.scene.hasDialog()) return;
        if(group == null) createGroup();
        Vec2 v = Core.scene.screenToStageCoordinates(new Vec2(Core.input.mouseX(), Core.input.mouseY()));
        float cx = Mathf.clamp(v.x, rx + bw / 2f + 4f, Math.max(rx + bw / 2f + 4f, Core.scene.getWidth() - rx - bw / 2f - 4f));
        float cy = Mathf.clamp(v.y, ry + bh / 2f + 4f, Math.max(ry + bh / 2f + 4f, Core.scene.getHeight() - ry - bh / 2f - 4f));
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

        // фон: любой клик мимо колеса (в т.ч. ПКМ) закрывает его
        Image bg = new Image(Tex.clear);
        bg.setFillParent(true);
        bg.touchable = Touchable.enabled;
        bg.addListener(new InputListener(){
            @Override
            public boolean touchDown(InputEvent e, float x, float y, int pointer, KeyCode button){
                close();
                return true;
            }
        });
        group.addChild(bg);

        Seq<String> l = list();
        int n = Math.min(l.size, maxSlots);

        hoverLabel = new Label(n == 0 ? "[lightgray]Список пуст — нажми ⚙" : "[lightgray]Выбери сообщение", Styles.outlineLabel);
        hoverLabel.setAlignment(Align.center);
        hoverLabel.setWidth(260f);
        hoverLabel.setPosition(cx - 130f, cy - 10f);
        hoverLabel.touchable = Touchable.disabled;
        group.addChild(hoverLabel);

        for(int i = 0; i < n; i++){
            final String text = textOf(l.get(i));
            String label = labelOf(l.get(i));
            if(label.isEmpty()) label = text;
            final String shown = cut(label, labelMax);
            float[] p = slotPos(i, n, cx, cy, rx, ry);

            TextButton b = new TextButton(shown, MKUKICSkin.actBtn);
            b.setSize(bw, bh);
            b.setPosition(p[0] - bw / 2f, p[1] - bh / 2f);
            b.getLabel().setAlignment(Align.center);
            b.addListener(new ChangeListener(){
                @Override
                public void changed(ChangeEvent e, Element el){
                    send(text);
                }
            });
            b.update(() -> {
                if(hoverLabel != null && b.isOver()) hoverLabel.setText("[white]" + cut(text, 42));
            });
            group.addChild(b);
        }

        centerButton(cx - 26f, cy + 40f, "[scarlet]✕", QuickMsgWheel::close);
        centerButton(cx + 26f, cy + 40f, "[accent]⚙", () -> {
            close();
            QolWindows.open("quickmsg");
        });
    }

    private static void centerButton(float x, float y, String text, Runnable action){
        TextButton b = new TextButton(text, MKUKICSkin.actBtn);
        b.setSize(40f, 40f);
        b.setPosition(x - 20f, y - 20f);
        b.getLabel().setAlignment(Align.center);
        b.addListener(new ChangeListener(){
            @Override
            public void changed(ChangeEvent e, Element el){
                action.run();
            }
        });
        group.addChild(b);
    }

    public static void send(String text){
        close();
        if(text == null || text.trim().isEmpty()) return;
        OnixChat.send(text);
    }

    // ============================ ОКНО-РЕДАКТОР (id: quickmsg) ============================

    public static void buildEditor(Table t){
        t.add("[lightgray]Кнопка колеса — Настройки → Управление → mkukic → «mkukic_quick_msg» (по умолчанию V). "
            + "Клик по сообщению в колесе отправляет его в чат.\n"
            + "[accent]Подпись[] — коротко на кнопке, [accent]Текст[] — что отправится (можно команду, например /msg 5 привет).")
            .left().top().wrap().width(470f).pad(3f, 0f, 6f, 0f).row();

        Table holder = new Table();
        Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> {
            holder.clearChildren();
            Seq<String> l = list();
            for(int i = 0; i < l.size; i++){
                final int idx = i;
                holder.table(r -> {
                    r.left();
                    r.add("[accent]" + (idx + 1)).width(22f);
                    r.field(labelOf(l.get(idx)), MKUKICSkin.field, txt -> {
                        l.set(idx, entry(txt, textOf(l.get(idx))));
                        saveLater();
                    }).width(110f).height(32f);
                    r.field(textOf(l.get(idx)), MKUKICSkin.field, txt -> {
                        l.set(idx, entry(labelOf(l.get(idx)), txt));
                        saveLater();
                    }).width(220f).height(32f).padLeft(4f);
                    r.button("↑", MKUKICSkin.actBtn, () -> {
                        if(idx > 0){
                            l.swap(idx, idx - 1);
                            MKUKICSettings.save();
                            refresh[0].run();
                        }
                    }).size(30f).padLeft(4f);
                    r.button("↓", MKUKICSkin.actBtn, () -> {
                        if(idx < l.size - 1){
                            l.swap(idx, idx + 1);
                            MKUKICSettings.save();
                            refresh[0].run();
                        }
                    }).size(30f).padLeft(2f);
                    r.button("[scarlet]x", MKUKICSkin.actBtn, () -> {
                        l.remove(idx);
                        MKUKICSettings.save();
                        refresh[0].run();
                    }).size(30f).padLeft(2f);
                }).left().pad(2f).row();
            }
            if(l.isEmpty()){
                holder.add("[darkgray]Пусто. Нажми «+ сообщение».").left().pad(4f).row();
            }
            if(l.size > maxSlots){
                holder.add("[scarlet]В колесе помещается " + maxSlots + " — остальные не показываются.").left().pad(4f).row();
            }
        };
        refresh[0].run();
        t.pane(holder).growX().maxHeight(Core.graphics.getHeight() * 0.55f).row();

        t.table(c -> {
            c.left();
            c.button("+ сообщение", MKUKICSkin.actBtn, () -> {
                if(list().size >= maxSlots){
                    MKUKICMod.toast("[scarlet]Максимум " + maxSlots + " сообщений");
                    return;
                }
                list().add(entry("новое", "Текст сообщения"));
                MKUKICSettings.save();
                refresh[0].run();
            }).minWidth(130f).height(34f).pad(4f, 2f, 4f, 2f);
            c.button((char)Iconc.chat + " Открыть колесо", MKUKICSkin.actBtn, QuickMsgWheel::show)
                .minWidth(160f).height(34f).pad(4f, 2f, 4f, 2f);
        }).left().row();
    }
}
