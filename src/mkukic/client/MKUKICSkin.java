package mkukic.client;

import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.math.*;
import arc.scene.style.*;
import arc.scene.ui.*;
import mindustry.ui.*;

/**
 * Единый скин MKUKIC: тёмные «стеклянные» панели с янтарной акцентной рамкой,
 * угловыми скобками, градиентами и полным набором стилей (кнопки, чекбоксы,
 * слайдеры, поля ввода, скроллбары, логотип). Всё генерируется из Pixmap на
 * лету — никаких внешних ассетов.
 *
 * Палитра бренда:
 *  - accent  #ffb454 (тёплый янтарь) — основной акцент;
 *  - accent2 #6fd6ff (лёд-голубой)   — вторичный акцент/подсветки;
 *  - фон     #0f131b..#1b2230        — глубокий сине-графитовый.
 */
public class MKUKICSkin{
    // ---------------- палитра ----------------
    public static final Color accent   = Color.valueOf("ffb454");
    public static final Color accentHi = Color.valueOf("ffc978");
    public static final Color accentLo = Color.valueOf("f0913a");
    public static final Color accent2  = Color.valueOf("6fd6ff");
    public static final Color textHi   = Color.valueOf("e8ecf4");
    public static final Color text     = Color.valueOf("c2cad8");
    public static final Color textDim  = Color.valueOf("8b94a5");
    public static final Color panelTop = Color.valueOf("1b2230");
    public static final Color panelBot = Color.valueOf("0f131b");

    /** Шестнадцатеричная разметка бренда для строк: "[mkukic]текст[]". */
    public static final String hex = "[#ffb454]";
    public static final String hex2 = "[#6fd6ff]";
    public static final String dim = "[#8b94a5]";

    // ---------------- drawables ----------------
    public static Drawable panel;      // основное окно: градиент + рамка + угловые скобки
    public static Drawable panelSoft;  // внутренние блоки (мягкая панель без акцента)
    public static Drawable card;       // карточка секции с акцентной полосой слева
    public static Drawable head;       // шапка окна/диалога (градиент с янтарным отливом)
    public static Drawable div;        // горизонтальный акцентный разделитель (градиент)
    public static Drawable divSoft;    // горизонтальный нейтральный разделитель
    public static Drawable vline;      // вертикальный разделитель (затухает к краям)
    public static Drawable chip;       // маленький «бейдж» (значение слайдера и т.п.)

    // ---------------- стили ----------------
    public static TextButton.TextButtonStyle navBtn;    // вкладки/навигация (checked = левая акцент-полоса)
    public static TextButton.TextButtonStyle actBtn;    // кнопки действий
    public static TextButton.TextButtonStyle toggleBtn; // чипы-переключатели (подтабы, фильтры, группы)
    public static TextButton.TextButtonStyle iconBtn;   // мелкие иконочные кнопки («–», «✕»)
    public static CheckBox.CheckBoxStyle check;         // чекбоксы
    public static Slider.SliderStyle slider;            // слайдеры
    public static TextField.TextFieldStyle field;       // поля ввода
    public static ScrollPane.ScrollPaneStyle scroll;    // полосы прокрутки

    // ---------------- логотип ----------------
    public static TextureRegion logo;            // шестиугольник с «M» (48x48)
    public static TextureRegionDrawable logoDrawable;

    public static boolean ready;

    public static void init(){
        if(ready) return;
        ready = true;

        // ============ ПАНЕЛИ ============
        panel = drawable(panelCanvas(), 11);
        panelSoft = drawable(roundedCanvas(18, 6, Color.valueOf("0b0f17"), 0.62f, Color.white, 0.07f, 1), 7);
        card = drawable(cardCanvas(), 9);
        head = drawable(headCanvas(), 11);

        // ============ РАЗДЕЛИТЕЛИ / ЧИП ============
        div = gradientLine(64, accent, 0.95f);
        divSoft = gradientLine(64, Color.white, 0.10f);
        vline = vGradientLine(48, Color.white, 0.16f);
        chip = drawable(roundedCanvas(16, 6, Color.valueOf("111827"), 0.85f, accent, 0.35f, 1), 6);

        // ============ ЛОГОТИП ============
        logo = logoCanvas(48);
        logoDrawable = new TextureRegionDrawable(logo);
        logoDrawable.setMinWidth(0f);
        logoDrawable.setMinHeight(0f);

        // ============ КНОПКИ НАВИГАЦИИ ============
        navBtn = new TextButton.TextButtonStyle();
        navBtn.font = Fonts.def;
        navBtn.fontColor = text;
        navBtn.overFontColor = accentHi;
        navBtn.downFontColor = accentHi;
        navBtn.checkedFontColor = accent;
        navBtn.checkedOverFontColor = accentHi;
        navBtn.up = drawable(navCanvas(0.00f, false), 8);
        navBtn.over = drawable(navCanvas(0.10f, false), 8);
        navBtn.down = drawable(navCanvas(0.16f, false), 8);
        navBtn.checked = drawable(navCanvas(0.15f, true), 8);
        navBtn.checkedOver = drawable(navCanvas(0.22f, true), 8);

        // ============ КНОПКИ ДЕЙСТВИЙ ============
        actBtn = new TextButton.TextButtonStyle();
        actBtn.font = Fonts.def;
        actBtn.fontColor = textHi;
        actBtn.overFontColor = Color.white;
        actBtn.downFontColor = accentHi;
        actBtn.checkedFontColor = accent;
        actBtn.checkedOverFontColor = accentHi;
        actBtn.up = drawable(buttonCanvas(Color.valueOf("232d40"), Color.valueOf("1a2231"), Color.white, 0.13f));
        actBtn.over = drawable(buttonCanvas(Color.valueOf("2c3850"), Color.valueOf("212b3e"), accent, 0.55f));
        actBtn.down = drawable(buttonCanvas(Color.valueOf("3a3020"), Color.valueOf("2a2214"), accent, 0.8f));
        actBtn.checked = drawable(buttonCanvas(Color.valueOf("33291a"), Color.valueOf("251d11"), accent, 0.7f));
        actBtn.checkedOver = drawable(buttonCanvas(Color.valueOf("3d3120"), Color.valueOf("2c2416"), accentHi, 0.9f));

        // ============ ЧИПЫ-ПЕРЕКЛЮЧАТЕЛИ ============
        toggleBtn = new TextButton.TextButtonStyle();
        toggleBtn.font = Fonts.def;
        toggleBtn.fontColor = textDim;
        toggleBtn.overFontColor = textHi;
        toggleBtn.downFontColor = accentHi;
        toggleBtn.checkedFontColor = accent;
        toggleBtn.checkedOverFontColor = accentHi;
        toggleBtn.up = drawable(buttonCanvas(Color.valueOf("1a2130"), Color.valueOf("141a26"), Color.white, 0.09f));
        toggleBtn.over = drawable(buttonCanvas(Color.valueOf("222b3d"), Color.valueOf("1a2231"), Color.white, 0.22f));
        toggleBtn.down = drawable(buttonCanvas(Color.valueOf("2a3446"), Color.valueOf("20293a"), accent, 0.5f));
        toggleBtn.checked = drawable(buttonCanvas(Color.valueOf("33291a"), Color.valueOf("251d11"), accent, 0.65f));
        toggleBtn.checkedOver = drawable(buttonCanvas(Color.valueOf("3d3120"), Color.valueOf("2c2416"), accentHi, 0.85f));

        // ============ ИКОНОЧНЫЕ КНОПКИ («–», «✕») ============
        iconBtn = new TextButton.TextButtonStyle();
        iconBtn.font = Fonts.def;
        iconBtn.fontColor = textDim;
        iconBtn.overFontColor = textHi;
        iconBtn.downFontColor = accent;
        iconBtn.up = drawable(roundedCanvas(14, 5, Color.white, 0.0f, Color.white, 0.0f, 0), 5);
        iconBtn.over = drawable(roundedCanvas(14, 5, Color.white, 0.13f, Color.white, 0.0f, 0), 5);
        iconBtn.down = drawable(roundedCanvas(14, 5, accent, 0.25f, Color.white, 0.0f, 0), 5);
        iconBtn.checked = iconBtn.down;

        // ============ ЧЕКБОКСЫ ============
        check = new CheckBox.CheckBoxStyle();
        check.font = Fonts.def;
        check.fontColor = text;
        check.overFontColor = textHi;
        check.downFontColor = accentHi;
        check.checkedFontColor = accentHi;
        check.checkedOverFontColor = accentHi;
        check.checkboxOff = drawable(boxCanvas(false, false), 9);
        check.checkboxOver = drawable(boxCanvas(false, true), 9);
        check.checkboxOn = drawable(boxCanvas(true, false), 9);
        check.checkboxOnOver = drawable(boxCanvas(true, true), 9);
        check.checkboxOnDisabled = drawable(boxCanvas(true, false), 9);
        check.checkboxOffDisabled = drawable(boxCanvas(false, false), 9);

        // ============ СЛАЙДЕРЫ ============
        slider = new Slider.SliderStyle();
        slider.background = drawable(trackCanvas(), 8, 8, 4, 4);
        slider.knobBefore = drawable(knobBeforeCanvas(), 8, 8, 4, 4);
        slider.knob = drawable(knobCanvas(false), 8);
        slider.knobOver = drawable(knobCanvas(true), 8);
        slider.knobDown = drawable(knobCanvas(true), 8);

        // ============ ПОЛЯ ВВОДА ============
        field = new TextField.TextFieldStyle();
        field.font = Fonts.def;
        field.fontColor = textHi;
        field.focusedFontColor = Color.white;
        field.disabledFontColor = textDim;
        field.background = drawable(roundedCanvas(18, 6, Color.valueOf("0d121c"), 0.92f, Color.white, 0.13f, 1), 7);
        field.focusedBackground = drawable(roundedCanvas(18, 6, Color.valueOf("101725"), 0.95f, accent, 0.65f, 1), 7);
        field.disabledBackground = drawable(roundedCanvas(18, 6, Color.valueOf("0d121c"), 0.6f, Color.white, 0.06f, 1), 7);
        field.cursor = solidDrawable(accent, 2f, 0f);
        field.selection = solidDrawable(new Color(accent.r, accent.g, accent.b, 0.28f), 0f, 0f);
        field.messageFont = Fonts.def;
        field.messageFontColor = textDim;

        // ============ СКРОЛЛБАРЫ ============
        scroll = new ScrollPane.ScrollPaneStyle();
        scroll.vScroll = drawable(roundedCanvas(10, 4, Color.valueOf("0d121c"), 0.45f, Color.white, 0.05f, 1), 4);
        scroll.vScrollKnob = drawable(roundedCanvas(10, 4, accent, 0.42f, Color.white, 0.0f, 0), 4);
        scroll.hScroll = scroll.vScroll;
        scroll.hScrollKnob = scroll.vScrollKnob;
    }

    // ==================================================================
    //  Мини-«канвас» для процедурной генерации текстур с антиалиасингом.
    // ==================================================================

    private static class Canvas{
        final int w, h;
        final float[] r, g, b, a;

        Canvas(int w, int h){
            this.w = w; this.h = h;
            int n = w * h;
            r = new float[n]; g = new float[n]; b = new float[n]; a = new float[n];
        }

        /** Покрытие пикселя скруглённым прямоугольником (SDF, сглаживание ~1px). */
        static float roundAlpha(float x, float y, float rw, float rh, float rad){
            float dx = Math.abs(x + 0.5f - rw / 2f) - (rw / 2f - rad);
            float dy = Math.abs(y + 0.5f - rh / 2f) - (rh / 2f - rad);
            float ox = Math.max(dx, 0f), oy = Math.max(dy, 0f);
            float outside = (float)Math.sqrt(ox * ox + oy * oy);
            float inside = Math.min(Math.max(dx, dy), 0f);
            return Mathf.clamp(0.5f - (outside + inside - rad), 0f, 1f);
        }

        /** Залить скруглённый прямоугольник с вертикальным градиентом top→bot. */
        void fillRound(int ox, int oy, int rw, int rh, float rad, Color top, Color bot, float alpha){
            if(rw <= 0 || rh <= 0) return;
            for(int y = 0; y < rh; y++){
                float t = rh <= 1 ? 0f : (float)y / (rh - 1);
                float cr = top.r + (bot.r - top.r) * t;
                float cg = top.g + (bot.g - top.g) * t;
                float cb = top.b + (bot.b - top.b) * t;
                for(int x = 0; x < rw; x++){
                    float cov = roundAlpha(x, y, rw, rh, rad);
                    if(cov > 0.004f) put(ox + x, oy + y, cr, cg, cb, alpha * cov);
                }
            }
        }

        void hline(int x1, int x2, int y, float thick, Color c, float alpha){
            for(int dy = 0; dy < (int)Math.ceil(thick); dy++){
                float f = Mathf.clamp(thick - dy, 0f, 1f);
                for(int x = x1; x <= x2; x++) put(x, y + dy, c.r, c.g, c.b, alpha * f);
            }
        }

        void vline(int x, int y1, int y2, float thick, Color c, float alpha){
            for(int dx = 0; dx < (int)Math.ceil(thick); dx++){
                float f = Mathf.clamp(thick - dx, 0f, 1f);
                for(int y = y1; y <= y2; y++) put(x + dx, y, c.r, c.g, c.b, alpha * f);
            }
        }

        /** Отрезок с толщиной (галочка, буква). */
        void line(float x1, float y1, float x2, float y2, float thick, Color c, float alpha){
            int steps = Math.max(4, (int)(Mathf.dst(x1, y1, x2, y2) * 3f));
            float rad = thick / 2f;
            int ri = (int)Math.ceil(rad) + 1;
            for(int i = 0; i <= steps; i++){
                float t = (float)i / steps;
                float cx = x1 + (x2 - x1) * t, cy = y1 + (y2 - y1) * t;
                for(int dy = -ri; dy <= ri; dy++){
                    for(int dx = -ri; dx <= ri; dx++){
                        float cov = Mathf.clamp(rad + 0.4f - Mathf.dst(dx, dy), 0f, 1f);
                        if(cov > 0.004f) put(Math.round(cx) + dx, Math.round(cy) + dy, c.r, c.g, c.b, alpha * cov);
                    }
                }
            }
        }

        void circle(float cx, float cy, float rad, Color c, float alpha){
            int ri = (int)Math.ceil(rad) + 1;
            for(int dy = -ri; dy <= ri; dy++){
                for(int dx = -ri; dx <= ri; dx++){
                    float cov = Mathf.clamp(rad + 0.4f - Mathf.dst(dx, dy), 0f, 1f);
                    if(cov > 0.004f) put(Math.round(cx) + dx, Math.round(cy) + dy, c.r, c.g, c.b, alpha * cov);
                }
            }
        }

        /** Композиция SRC_OVER. */
        void put(int x, int y, float cr, float cg, float cb, float ca){
            if(x < 0 || y < 0 || x >= w || y >= h || ca <= 0f) return;
            int i = y * w + x;
            float da = a[i];
            float oa = ca + da * (1f - ca);
            if(oa <= 0.0001f) return;
            r[i] = (cr * ca + r[i] * da * (1f - ca)) / oa;
            g[i] = (cg * ca + g[i] * da * (1f - ca)) / oa;
            b[i] = (cb * ca + b[i] * da * (1f - ca)) / oa;
            a[i] = oa;
        }

        Pixmap toPixmap(){
            Pixmap p = new Pixmap(w, h);
            p.fill(0);
            Color tmp = new Color();
            for(int y = 0; y < h; y++){
                for(int x = 0; x < w; x++){
                    int i = y * w + x;
                    if(a[i] <= 0.004f) continue;
                    tmp.set(Mathf.clamp(r[i], 0f, 1f), Mathf.clamp(g[i], 0f, 1f), Mathf.clamp(b[i], 0f, 1f), Mathf.clamp(a[i], 0f, 1f));
                    p.fillRect(x, y, 1, 1, tmp.rgba8888());
                }
            }
            return p;
        }
    }

    // ==================================================================
    //  Упаковка канвасов в Drawable
    // ==================================================================

    private static Drawable drawable(Canvas c){
        // margin чуть меньше половины, чтобы всегда оставалась растягиваемая середина
        return drawable(c, Math.max(2, Math.min(c.w, c.h) / 2 - 2));
    }

    private static Drawable drawable(Canvas c, int margin){
        return drawable(c, margin, margin, margin, margin);
    }

    private static Drawable drawable(Canvas c, int left, int right, int top, int bottom){
        Pixmap p = c.toPixmap();
        Texture t = new Texture(p);
        p.dispose();
        return new NinePatchDrawable(new NinePatch(t, left, right, top, bottom));
    }

    private static Drawable solidDrawable(Color c, float minWidth, float minHeight){
        Pixmap p = new Pixmap(2, 2);
        p.fill(c);
        TextureRegionDrawable d = new TextureRegionDrawable(new TextureRegion(new Texture(p)));
        p.dispose();
        d.setMinWidth(minWidth);
        d.setMinHeight(minHeight);
        return d;
    }

    // ==================================================================
    //  Генераторы текстур
    // ==================================================================

    /** Основная панель: градиент, акцентная рамка, блик и угловые скобки. */
    private static Canvas panelCanvas(){
        int size = 26, rad = 9;
        Canvas c = new Canvas(size, size);
        c.fillRound(0, 0, size, size, rad, panelTop, panelBot, 0.95f);

        // рамка (2px) + внутренняя световая линия
        for(int y = 0; y < size; y++){
            for(int x = 0; x < size; x++){
                float outer = Canvas.roundAlpha(x, y, size, size, rad);
                float inner = Canvas.roundAlpha(x - 1.6f, y - 1.6f, size - 3.2f, size - 3.2f, rad - 1.6f);
                float m = Mathf.clamp(outer - inner, 0f, 1f);
                if(m > 0.004f){
                    float lift = 1f - 0.4f * ((float)y / size); // сверху ярче
                    c.put(x, y, accent.r * lift + 0.1f, accent.g * lift + 0.06f, accent.b * lift, 0.8f * m);
                }
                float glass = Canvas.roundAlpha(x - 2.8f, y - 2.8f, size - 5.6f, size - 5.6f, rad - 2.8f);
                float glass2 = Canvas.roundAlpha(x - 3.8f, y - 3.8f, size - 7.6f, size - 7.6f, rad - 3.8f);
                float gm = Mathf.clamp(glass - glass2, 0f, 1f);
                if(gm > 0.004f) c.put(x, y, 1f, 1f, 1f, 0.045f * gm);
            }
        }

        // угловые скобки вторым акцентом
        int L = 5, o = 3;
        c.hline(o, o + L, o, 1.6f, accent2, 0.85f);
        c.vline(o, o + 1, o + L, 1.6f, accent2, 0.85f);
        c.hline(size - 1 - o - L, size - 1 - o, o, 1.6f, accent2, 0.85f);
        c.vline(size - 1 - o - 1, o + 1, o + L, 1.6f, accent2, 0.85f);
        c.hline(o, o + L, size - 1 - o - 1, 1.6f, accent2, 0.85f);
        c.vline(o, size - 1 - o - L, size - 2 - o, 1.6f, accent2, 0.85f);
        c.hline(size - 1 - o - L, size - 1 - o, size - 1 - o - 1, 1.6f, accent2, 0.85f);
        c.vline(size - 1 - o - 1, size - 1 - o - L, size - 2 - o, 1.6f, accent2, 0.85f);
        return c;
    }

    /** Простая скруглённая плашка. */
    private static Canvas roundedCanvas(int size, int rad, Color bg, float bgA, Color border, float borderA, int bw){
        Canvas c = new Canvas(size, size);
        if(borderA > 0f && bw > 0) c.fillRound(0, 0, size, size, rad, border, border, borderA);
        if(bgA > 0f) c.fillRound(bw, bw, size - bw * 2, size - bw * 2, Math.max(rad - bw, 0.5f), bg, bg, bgA);
        return c;
    }

    /** Карточка секции: мягкий фон, тонкая рамка, акцентная полоса слева. */
    private static Canvas cardCanvas(){
        int size = 24, rad = 7;
        Canvas c = new Canvas(size, size);
        c.fillRound(0, 0, size, size, rad, Color.valueOf("1d2534"), Color.valueOf("151b28"), 0.7f);
        for(int y = 0; y < size; y++){
            for(int x = 0; x < size; x++){
                float outer = Canvas.roundAlpha(x, y, size, size, rad);
                float inner = Canvas.roundAlpha(x - 1f, y - 1f, size - 2f, size - 2f, rad - 1f);
                float m = Mathf.clamp(outer - inner, 0f, 1f);
                if(m > 0.004f) c.put(x, y, 1f, 1f, 1f, 0.09f * m);
            }
        }
        c.fillRound(2, 4, 3, size - 8, 1.5f, accentHi, accentLo, 0.9f);
        return c;
    }

    /** Шапка диалога/окна: тёплый градиент, свечение слева, нижняя акцентная линия. */
    private static Canvas headCanvas(){
        int size = 26, rad = 9;
        Canvas c = new Canvas(size, size);
        c.fillRound(0, 0, size, size, rad, Color.valueOf("2b2416"), Color.valueOf("161c28"), 0.97f);
        for(int y = 0; y < size; y++){
            for(int x = 0; x < size; x++){
                float cov = Canvas.roundAlpha(x, y, size, size, rad);
                if(cov <= 0.004f) continue;
                float d = Mathf.dst(x, y, 13, size / 2f) / (size * 0.85f);
                float glow = Mathf.clamp(1f - d, 0f, 1f) * 0.09f;
                c.put(x, y, accent.r, accent.g, accent.b, glow * cov);
            }
        }
        for(int x = 0; x < size; x++){
            float t = (float)x / (size - 1);
            float a2 = 0.8f * (1f - t * 0.7f);
            c.put(x, size - 2, accent.r, accent.g, accent.b, a2);
            c.put(x, size - 1, accent.r * 0.8f, accent.g * 0.8f, accent.b * 0.8f, a2 * 0.5f);
        }
        return c;
    }

    /** Кнопка вкладки: подсветка фона + (опц.) акцентная полоса слева. */
    private static Canvas navCanvas(float bgAlpha, boolean active){
        int w = 26, h = 24;
        Canvas c = new Canvas(w, h);
        c.fillRound(0, 0, w, h, 7, Color.white, Color.white, 0.02f);
        if(bgAlpha > 0f) c.fillRound(0, 0, w, h, 7, accent, accentLo, bgAlpha);
        if(active) c.fillRound(1, 4, 3, h - 8, 1.5f, accentHi, accent, 0.95f);
        return c;
    }

    /** Обычная кнопка: вертикальный градиент + рамка + блик сверху. */
    private static Canvas buttonCanvas(Color top, Color bot, Color border, float borderA){
        int size = 22, rad = 7;
        Canvas c = new Canvas(size, size);
        c.fillRound(0, 0, size, size, rad, border, border, borderA);
        c.fillRound(1, 1, size - 2, size - 2, rad - 1, top, bot, 0.96f);
        for(int x = 3; x < size - 3; x++){
            float t = 1f - Math.abs(x - (size - 1) / 2f) / (size / 2f);
            c.put(x, 2, 1f, 1f, 1f, 0.07f * Mathf.clamp(t, 0f, 1f));
        }
        return c;
    }

    /** Коробка чекбокса. */
    private static Canvas boxCanvas(boolean on, boolean over){
        int s = 20, rad = 6;
        Canvas c = new Canvas(s, s);
        if(on){
            c.fillRound(0, 0, s, s, rad, accentHi, accentLo, 1f);
            c.hline(3, s - 4, 2, 1f, Color.white, 0.3f);
            Color tick = Color.valueOf("241603");
            c.line(4.6f, 10.2f, 8.4f, 14.2f, 2.7f, tick, 1f);
            c.line(8.4f, 14.2f, 15.4f, 5.6f, 2.7f, tick, 1f);
        }else{
            c.fillRound(0, 0, s, s, rad, Color.valueOf("0e1420"), Color.valueOf("141b2a"), 0.95f);
            for(int y = 0; y < s; y++){
                for(int x = 0; x < s; x++){
                    float outer = Canvas.roundAlpha(x, y, s, s, rad);
                    float inner = Canvas.roundAlpha(x - 1.5f, y - 1.5f, s - 3f, s - 3f, rad - 1.5f);
                    float m = Mathf.clamp(outer - inner, 0f, 1f);
                    if(m > 0.004f){
                        if(over) c.put(x, y, accent.r, accent.g, accent.b, 0.85f * m);
                        else c.put(x, y, 1f, 1f, 1f, 0.30f * m);
                    }
                }
            }
        }
        return c;
    }

    /** Трек слайдера. */
    private static Canvas trackCanvas(){
        int w = 26, h = 12;
        Canvas c = new Canvas(w, h);
        c.fillRound(0, 2, w, h - 4, 4, Color.valueOf("0a0e15"), Color.valueOf("0e131d"), 0.95f);
        for(int y = 2; y < h - 2; y++){
            for(int x = 0; x < w; x++){
                float outer = Canvas.roundAlpha(x, y - 2, w, h - 4, 4);
                float inner = Canvas.roundAlpha(x - 1f, y - 3f, w - 2f, h - 6f, 3f);
                float m = Mathf.clamp(outer - inner, 0f, 1f);
                if(m > 0.004f) c.put(x, y, 1f, 1f, 1f, 0.10f * m);
            }
        }
        return c;
    }

    /** Заполненная часть слайдера. */
    private static Canvas knobBeforeCanvas(){
        int w = 26, h = 12;
        Canvas c = new Canvas(w, h);
        c.fillRound(0, 2, w, h - 4, 4, accentHi, accentLo, 0.95f);
        c.hline(3, w - 4, 3, 1f, Color.white, 0.28f);
        return c;
    }

    /** Ручка слайдера: тёмный круг с янтарным кольцом и центром. */
    private static Canvas knobCanvas(boolean over){
        int s = 20;
        Canvas c = new Canvas(s, s);
        float cx = (s - 1) / 2f;
        c.circle(cx, cx, 8.6f, Color.valueOf("0e131d"), 1f);
        for(int y = 0; y < s; y++){
            for(int x = 0; x < s; x++){
                float d = Mathf.dst(x + 0.5f - cx, y + 0.5f - cx);
                float m = Mathf.clamp(2.4f - Math.abs(d - 6.8f) * 1.6f, 0f, 1f);
                if(m > 0.004f){
                    Color cc = over ? accentHi : accent;
                    c.put(x, y, cc.r, cc.g, cc.b, m);
                }
            }
        }
        c.circle(cx, cx, 2.3f, over ? Color.white : accentHi, over ? 0.95f : 0.8f);
        return c;
    }

    /** Горизонтальный разделитель: градиент от цвета к прозрачности. */
    private static Drawable gradientLine(int w, Color c, float alpha){
        Canvas cv = new Canvas(w, 4);
        for(int x = 0; x < w; x++){
            float t = 1f - (float)x / (w - 1);
            float a = alpha * t * t;
            for(int y = 0; y < 4; y++){
                float fy = (y == 0 || y == 3) ? 0.2f : 1f;
                cv.put(x, y, c.r, c.g, c.b, a * fy);
            }
        }
        Pixmap p = cv.toPixmap();
        Texture t = new Texture(p);
        p.dispose();
        return new NinePatchDrawable(new NinePatch(t, 2, 2, 0, 0));
    }

    /** Вертикальный разделитель: затухание к краям. */
    private static Drawable vGradientLine(int h, Color c, float alpha){
        Canvas cv = new Canvas(4, h);
        for(int y = 0; y < h; y++){
            float t = 1f - Math.abs(y - (h - 1) / 2f) / ((h - 1) / 2f);
            for(int x = 0; x < 4; x++){
                float fx = (x == 0 || x == 3) ? 0.25f : 1f;
                cv.put(x, y, c.r, c.g, c.b, alpha * t * t * fx);
            }
        }
        Pixmap p = cv.toPixmap();
        Texture t = new Texture(p);
        p.dispose();
        return new NinePatchDrawable(new NinePatch(t, 1, 1, 3, 3));
    }

    /** SDF плоского сверху шестиугольника (apothem = r). */
    private static float sdHexagon(float px, float py, float r){
        float x = Math.abs(px), y = Math.abs(py);
        float dot = -0.866025404f * x + 0.5f * y;
        float m = Math.min(dot, 0f);
        x -= 2f * m * -0.866025404f;
        y -= 2f * m * 0.5f;
        x -= Mathf.clamp(x, -0.577350269f * r, 0.577350269f * r);
        y -= r;
        return Mathf.dst(x, y) * Math.signum(y);
    }

    /** Логотип: шестиугольник с градиентной рамкой и буквой «M». */
    private static TextureRegion logoCanvas(int s){
        Canvas c = new Canvas(s, s);
        float cx = (s - 1) / 2f, cy = (s - 1) / 2f;
        float R = s * 0.46f;
        for(int y = 0; y < s; y++){
            for(int x = 0; x < s; x++){
                float sdf = sdHexagon(x + 0.5f - cx, y + 0.5f - cy, R * 0.92f);
                float fill = Mathf.clamp(0.5f - sdf, 0f, 1f);
                if(fill <= 0.004f) continue;
                // тёмный фон с лёгким градиентом
                float t = (float)y / s;
                c.put(x, y, 0.11f + 0.05f * (1f - t), 0.13f + 0.05f * (1f - t), 0.19f + 0.06f * (1f - t), 0.97f * fill);
                // янтарная рамка-кольцо по краю
                float ring = Mathf.clamp(1.5f - Math.abs(sdf + 1.5f), 0f, 1f) * fill;
                if(ring > 0.004f){
                    float gt = (float)(x + y) / (2f * s);
                    Color gc = gt < 0.5f ? accentHi : accentLo;
                    c.put(x, y, gc.r, gc.g, gc.b, ring);
                }
            }
        }
        // буква «M»
        float mTop = cy - R * 0.44f, mBot = cy + R * 0.52f;
        float mL = cx - R * 0.50f, mR = cx + R * 0.50f;
        float th = Math.max(2.6f, s * 0.075f);
        c.line(mL, mTop, mL, mBot, th, accentHi, 1f);
        c.line(mR, mTop, mR, mBot, th, accentHi, 1f);
        c.line(mL + th * 0.3f, mTop, cx, cy + R * 0.14f, th, accent, 1f);
        c.line(mR - th * 0.3f, mTop, cx, cy + R * 0.14f, th, accent, 1f);
        // искра вторым акцентом
        c.circle(cx, cy - R * 0.66f, Math.max(1.3f, s * 0.03f), accent2, 0.95f);

        Pixmap p = c.toPixmap();
        Texture t = new Texture(p);
        p.dispose();
        return new TextureRegion(t);
    }

    /** Стиль приглушённой подписи. */
    public static Label.LabelStyle dimLabel(){
        Label.LabelStyle st = new Label.LabelStyle();
        st.font = Fonts.def;
        st.fontColor = textDim;
        return st;
    }
}
