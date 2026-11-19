package mkukic.client;

import arc.struct.*;
import arc.util.*;
import mindustry.gen.*;

import java.lang.reflect.*;

/**
 * Безопасный доступ к глифам игрового шрифта (Iconc) по имени.
 * Имя можно передать списком через «|»: берётся первое, которое существует в этой сборке игры.
 * Если не нашлось ни одного — подставляется нейтральная иконка, а в лог один раз пишется предупреждение.
 * Так переименование/удаление глифа в новой версии Mindustry не ломает компиляцию мода.
 */
public class Ico{
    private static final ObjectMap<String, String> cache = new ObjectMap<>();
    private static final String fallback = "" + Iconc.settings;

    public static String of(String names){
        String c = cache.get(names);
        if(c != null) return c;
        String res = null;
        for(String n : names.split("\\|")){
            try{
                Field f = Iconc.class.getField(n.trim());
                if(f.getType() == char.class){
                    res = "" + f.getChar(null);
                    break;
                }
            }catch(Throwable ignored){}
        }
        if(res == null){
            Log.warn("[MKUKIC] нет иконки Iconc.@ — использована запасная", names);
            res = fallback;
        }
        cache.put(names, res);
        return res;
    }

    /** Символ из частной области шрифта (так выглядят все глифы Iconc). */
    public static boolean isGlyph(char c){
        return c >= '\uE000' && c <= '\uF8FF';
    }
}
