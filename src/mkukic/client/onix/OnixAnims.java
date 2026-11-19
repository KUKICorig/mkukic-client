package mkukic.client.onix;

import arc.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.math.*;
import arc.struct.*;
import arc.scene.*;
import arc.util.*;
import mindustry.gen.*;
import mindustry.ui.Fonts;
import mkukic.*;

import static mindustry.Vars.*;

/**
 * <b>Анимации Onix</b> — порт раздела «Анимации» из Onix Lite 3.4.5/3.4.6:
 *
 * <ul>
 *  <li>«Раздел „Анимации“ с переключателями: что анимировать, а что нет»;</li>
 *  <li>«Плавный чат: мягкое движение строк, печатная машинка, каретка»;</li>
 *  <li>«Панель функций вылетает снизу с каскадом категорий, таб открывается без телепорта»;</li>
 *  <li>«Мягкие счётчики, HUD функций в цвете темы».</li>
 * </ul>
 *
 * <p>Реализация: MKUKIC — мод, поэтому ванильный чат не перестраивается. Плавность
 * даётся двумя слоями: (1) общая «анимационная база» — плавные счётчики и плавное
 * открытие панелей без телепорта; (2) эффект печатной машинки с кареткой для
 * последнего сообщения чата, который рисуется поверх строки в экранном слое.
 */
public class OnixAnims{
    /** Плавный счётчик (мягкие числа в HUD). */
    public static class Smooth{
        private final String key;
        private float value;
        private boolean init;

        public Smooth(String key){
            this.key = key;
        }

        public float get(float target){
            MKUKICSettings s = MKUKICSettings.s();
            if(!s.onixEnabled || !s.onixAnims || !s.onixSmoothCounters){
                value = target;
                init = true;
                return target;
            }
            if(!init){
                value = target;
                init = true;
                return target;
            }
            value = Mathf.lerpDelta(value, target, 0.18f);
            if(Mathf.equal(value, target, 0.01f)) value = target;
            return value;
        }

        public float raw(){
            return value;
        }

        public void reset(){
            init = false;
        }
    }

    private static final ObjectMap<String, Smooth> smooths = new ObjectMap<>();

    // печатная машинка
    private static String twText = "";
    private static int twShown;
    private static float twStart = -1f;
    private static float twY = -1f;
    private static int lastChatSize = -1;

    // каскад панели
    private static float panelAnim = 1f;
    private static boolean panelAnimArmed;

    private static boolean inited;

    public static void init(){
        if(inited) return;
        inited = true;
    }

    public static void reset(){
        for(Smooth s : smooths.values()) s.reset();
        twText = "";
        twShown = 0;
        twStart = -1f;
        lastChatSize = -1;
        panelAnim = 1f;
        panelAnimArmed = false;
    }

    /** Плавный счётчик по имени (например, «core-copper»). */
    public static Smooth smooth(String key){
        Smooth s = smooths.get(key, (Smooth)null);
        if(s == null){
            s = new Smooth(key);
            smooths.put(key, s);
        }
        return s;
    }

    public static float smoothValue(String key, float target){
        return smooth(key).get(target);
    }

    // ============================== ПАНЕЛЬ: КАСКАД БЕЗ ТЕЛЕПОРТА ==============================

    /**
     * Вызывается при открытии панели/вкладки: включает анимацию «вылет снизу
     * с каскадом категорий». Дальше {@link #panelAlpha()} и {@link #panelOffset()}
     * используются рисующим кодом.
     */
    public static void armPanel(){
        MKUKICSettings s = MKUKICSettings.s();
        if(!s.onixEnabled || !s.onixAnims) return;
        panelAnim = 0f;
        panelAnimArmed = true;
    }

    public static void uiTick(){
        if(panelAnimArmed){
            panelAnim = Mathf.lerpDelta(panelAnim, 1f, 0.22f);
            if(panelAnim > 0.995f){
                panelAnim = 1f;
                panelAnimArmed = false;
            }
        }
        typewriterTick();
    }

    /** 0…1 — насколько панель уже «вылетела». */
    public static float panelProgress(){
        return panelAnim;
    }

    public static float panelAlpha(){
        return Mathf.clamp(panelAnim * 1.4f);
    }

    /** Смещение снизу (в пикселях) — «вылетает снизу». */
    public static float panelOffset(){
        return (1f - panelAnim) * 60f;
    }

    /**
     * Каскад категорий: каждая следующая строка/вкладка отстаёт на шаг.
     * @param index порядковый номер элемента
     */
    public static float cascade(int index){
        if(!MKUKICSettings.s().onixAnims) return 1f;
        float p = panelAnim - index * 0.045f;
        return Mathf.clamp(p * 1.6f);
    }

    public static float cascadeAlpha(int index){
        return Mathf.clamp(cascade(index));
    }

    public static float cascadeOffsetY(int index){
        return (1f - cascade(index)) * 22f;
    }

    // ============================== ПЛАВНЫЙ ЧАТ / ПЕЧАТНАЯ МАШИНКА ==============================

    /**
     * Печатная машинка: самое новое сообщение чата «допечатывается» посимвольно,
     * в конце мигает каретка. Берём текст из журнала чата MKUKIC (он ведётся
     * в {@link OnixChat}) — ванильное поле messages приватное.
     */
    private static void typewriterTick(){
        MKUKICSettings s = MKUKICSettings.s();
        if(!s.onixEnabled || !s.onixAnims || !s.onixTypewriter) return;
        if(state == null || state.isMenu()) return;
        int size = OnixChat.history.size;
        if(size != lastChatSize){
            lastChatSize = size;
            if(size > 0){
                OnixChat.Entry e = OnixChat.history.peek();
                twText = e.full();
                twShown = 0;
                twStart = Time.time;
            }
        }
        if(twStart > 0f && twShown < twText.length()){
            // ~40 символов в секунду
            int want = (int)((Time.time - twStart) * 0.7f);
            twShown = Mathf.clamp(want, 0, twText.length());
        }
    }

    /** Дорисовать эффект печатной машинки поверх строки чата (экранный слой). */
    public static void drawScreen(){
        MKUKICSettings s = MKUKICSettings.s();
        if(!s.onixEnabled || !s.onixAnims || !s.onixTypewriter) return;
        if(state == null || state.isMenu()) return;
        if(twText == null || twText.isEmpty() || twShown <= 0) return;
        if(twStart > 0f && Time.time - twStart > 6f) return; // эффект короткий

        Font font = Fonts.def;
        String shown = twText.substring(0, Math.min(twShown, twText.length()));
        boolean caret = ((int)(Time.time / 12f)) % 2 == 0;
        if(caret && twShown < twText.length()) shown += "[accent]|[]";

        float x = 24f;
        float y = Core.graphics.getHeight() - 132f; // над полем ввода чата
        font.getData().setScale(1f);
        GlyphLayout gl = new GlyphLayout(font, shown);
        font.setColor(Color.white);
        font.draw(gl, x, y);
        Draw.reset();
    }

    /**
     * «Плавное появление игроков в табе»: коэффициент появления для строки списка
     * игроков (используется списком, который рисует MKUKIC).
     */
    public static float listFade(int id, boolean present){
        MKUKICSettings s = MKUKICSettings.s();
        if(!s.onixEnabled || !s.onixAnims) return 1f;
        Smooth sm = smooth("tab-" + id);
        return Mathf.clamp(sm.get(present ? 1f : 0f));
    }

    /** Плавный скролл: вернуть «мягкую» позицию прокрутки. */
    public static float smoothScroll(String key, float target){
        MKUKICSettings s = MKUKICSettings.s();
        if(!s.onixEnabled || !s.onixAnims) return target;
        return smooth("scroll-" + key).get(target);
    }
}
