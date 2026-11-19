package mkukic.client.qol;

import arc.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.scene.style.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.ui.*;
import mkukic.*;
import mkukic.client.MKUKICSkin;

import java.lang.reflect.*;

/**
 * Набор символов и иконок (MI2-Utilities «Emoji Toolkit»):
 * полная коллекция игровых иконок (Iconc) с обратным поиском имён.
 * Клик — копирует символ в буфер обмена (вставлять в чат, надписи, логику).
 */
public class IconKit{
    private static Seq<Object[]> icons; // {name, Character}
    private static String filter = "";

    private static void collect(){
        if(icons != null) return;
        icons = new Seq<>();
        try{
            for(Field f : Iconc.class.getFields()){
                if(f.getType() == char.class && Modifier.isStatic(f.getModifiers())){
                    icons.add(new Object[]{f.getName(), f.getChar(null)});
                }
            }
        }catch(Throwable t){
            Log.err("[MKUKIC QoL] не удалось собрать иконки", t);
        }
        icons.sort((a, b) -> ((String)a[0]).compareTo((String)b[0]));
    }

    public static void build(Table t){
        collect();

        t.table(c -> {
            c.left();
            c.add("Поиск:").padRight(4f);
            c.field("", MKUKICSkin.field, txt -> {
                filter = txt == null ? "" : txt.trim().toLowerCase();
                rebuildGrid(grid);
            }).width(200f);
            c.label(() -> "[darkgray]" + countShown() + " шт.").padLeft(8f);
        }).left().growX().row();

        t.add("[lightgray]Клик по иконке — скопировать символ в буфер обмена.\n"
            + "Вставляй в чат, мировые надписи и тексты процессоров.").left().top().wrap().width(340f).pad(3f, 0f, 5f, 0f).row();

        grid = new Table();
        grid.top().left();
        Table g = grid;
        t.pane(grid).maxHeight(280f).growX().row();
        rebuildGrid(g);
    }

    private static Table grid;

    private static int countShown(){
        collect();
        int n = 0;
        for(Object[] e : icons){
            if(matches((String)e[0], (Character)e[1])) n++;
        }
        return n;
    }

    private static boolean matches(String name, char c){
        if(filter.isEmpty()) return true;
        if(name.toLowerCase().contains(filter)) return true;
        // поиск по самому символу (вставили в поле — находим его имя)
        return filter.length() == 1 && filter.charAt(0) == c;
    }

    private static void rebuildGrid(Table g){
        if(g == null) return;
        collect();
        g.clear();
        g.top().left();
        int i = 0, shown = 0;
        for(Object[] e : icons){
            String name = (String)e[0];
            char c = (Character)e[1];
            if(!matches(name, c)) continue;
            if(shown++ >= 600) break;
            final char fc = c;
            final String fname = name;
            g.button(btn -> {
                btn.add(new Label(String.valueOf(fc), new Label.LabelStyle(Fonts.def, Color.white))).center().size(30f);
            }, MKUKICSkin.actBtn, () -> {
                Core.app.setClipboardText(String.valueOf(fc));
                MKUKICMod.toast("[accent]" + fname + "[] скопировано: " + fc);
            }).size(36f).pad(1f);
            if(++i % 9 == 0) g.row();
        }
        if(i % 9 != 0) g.row();
        if(shown == 0) g.add("[darkgray]ничего не найдено").left().pad(6f);
        g.invalidate();
    }
}
