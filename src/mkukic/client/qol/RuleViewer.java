package mkukic.client.qol;

import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.ui.*;
import mkukic.*;
import mkukic.client.MKUKICSkin;

import java.lang.reflect.*;

import static mindustry.Vars.*;

/**
 * Rule Viewer (из MI2-Utilities): все правила текущей карты в одной таблице
 * с поиском. На хосте/в одиночке булевы правила можно переключать кликом.
 */
public class RuleViewer{
    private static String filter = "";
    private static @Nullable Table paneRef;

    public static void build(Table t){
        t.table(c -> {
            c.left();
            c.add("Фильтр:").padRight(4f);
            c.field("", MKUKICSkin.field, txt -> {
                filter = txt == null ? "" : txt.trim().toLowerCase();
                rebuild();
            }).width(180f);
            c.button("Обновить", MKUKICSkin.actBtn, RuleViewer::rebuild).padLeft(6f).minWidth(110f);
        }).left().growX().row();

        t.label(() -> {
            if(state == null || state.map == null) return "[darkgray]—";
            return "[lightgray]Карта: [white]" + (state.map.name() == null ? "?" : state.map.name())
                + "  [lightgray]|  Режим: [white]" + state.rules.mode().name()
                + "  [lightgray]|  Волна: [white]" + state.wave;
        }).left().wrap().width(460f).pad(4f, 0f, 4f, 0f).row();

        Table holder = new Table();
        holder.top().left();
        paneRef = holder;
        t.pane(holder).maxHeight(320f).growX().padTop(2f).row();
        rebuild();
    }

    private static void rebuild(){
        if(paneRef == null) return;
        Table out = paneRef;
        out.clear();
        out.top().left();
        if(state == null){
            out.add("[darkgray]нет активного мира").left().pad(4f);
            return;
        }
        Rules r = state.rules;
        Field[] fields;
        try{
            fields = Rules.class.getFields();
        }catch(Throwable e){
            out.add("[scarlet]ошибка: " + e).left();
            return;
        }

        int shown = 0;
        for(Field f : fields){
            if(Modifier.isStatic(f.getModifiers()) || f.isSynthetic()) continue;
            String name = f.getName();
            if(!filter.isEmpty() && !name.toLowerCase().contains(filter)) continue;
            Object v;
            try{
                v = f.get(r);
            }catch(Throwable e){
                continue;
            }
            String vs = fmt(v);
            final Field ff = f;
            out.table(row -> {
                row.left().top();
                row.add("[accent]" + name).left().minWidth(190f).top().padRight(6f);
                boolean boolHost = ff.getType() == boolean.class && (!net.active() || net.server());
                if(boolHost){
                    row.button(vs, MKUKICSkin.toggleBtn, () -> {
                        try{
                            ff.setBoolean(state.rules, !ff.getBoolean(state.rules));
                            rebuild();
                            MKUKICMod.toast("[accent]" + ff.getName() + " = " + ff.getBoolean(state.rules));
                        }catch(Throwable ignored){}
                    }).checked(b -> {
                        try{ return ff.getBoolean(state.rules); }catch(Throwable e){ return false; }
                    }).minWidth(110f).left();
                }else{
                    row.add("[lightgray]" + vs).left().top().wrap().width(280f);
                }
            }).left().growX().pad(1f).row();
            out.image(Tex.whiteui, Pal.darkestGray).height(1f).growX().row();
            shown++;
            if(shown >= 400) break;
        }
        if(shown == 0) out.add("[darkgray]ничего не найдено").left().pad(6f);
        out.invalidate();
        if(out.parent != null) out.parent.invalidate();
    }

    private static String fmt(Object v){
        if(v == null) return "null";
        if(v instanceof Float || v instanceof Double) return Strings.autoFixed(((Number)v).floatValue(), 2);
        if(v instanceof Seq) return "[" + ((Seq<?>)v).size + "] " + Strings.truncate(v.toString(), 90);
        if(v instanceof ObjectSet) return "[" + ((ObjectSet<?>)v).size + "] " + Strings.truncate(v.toString(), 90);
        if(v instanceof ObjectMap) return "[" + ((ObjectMap<?, ?>)v).size + "] " + Strings.truncate(v.toString(), 90);
        if(v.getClass().isArray()){
            int n = Array.getLength(v);
            return "[" + n + "]";
        }
        return Strings.truncate(String.valueOf(v), 120);
    }
}
