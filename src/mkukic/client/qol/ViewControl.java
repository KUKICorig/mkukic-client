package mkukic.client.qol;

import arc.*;
import arc.math.*;
import arc.scene.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.util.*;
import mindustry.graphics.*;
import mindustry.ui.*;
import mindustry.world.blocks.storage.CoreBlock.*;
import mkukic.*;
import mkukic.client.*;

import static mindustry.Vars.*;

/**
 * Камера / время / производительность:
 *  - Расширенный зум + свободная камера-«отвязка» (MindustryTool «Free Camera & Extended Zoom»);
 *  - TimeControl (sk7725): локальная скорость симуляции — слоу-мо и ускорение (хост/одиночная игра);
 *  - Performance Optimizer (MindustryTool): отключение проходов рендера
 *    (погода, частицы, свет, щиты, вода, пинги, чужие планы, дисплеи) для прироста FPS.
 * Все изменения обратимы: исходные значения запоминаются и восстанавливаются.
 */
public class ViewControl{
    // зум
    private static boolean zoomCaptured;
    private static float origMinZoom, origMaxZoom, origMinZoomGame, origMaxZoomGame;

    // производительность
    private static boolean perfCaptured;
    private static boolean origWeather, origEffects, origLight, origShields, origWater, origPings, origOtherPlans, origDisplays;

    // время
    private static boolean timeApplied;
    // сглаживание дельты (EMA), -1 = не инициализировано
    private static float ema = -1f;

    /**
     * Сглаживание лаг-спайков (порт идеи «Lag smoother»): EMA следит за ВЫХОДОМ (α=0.25),
     * результат = min(raw, max(ema*2, 0.5)). Одиночный спайк проходит не больше чем 2x
     * от текущего темпа, а устойчивое изменение FPS догоняется за несколько кадров.
     * Первый кадр после resetSmooth() проходит как есть. Чистая функция состояния (ema — поле),
     * тестируется в FeatureTest.
     */
    public static float smoothDelta(float raw){
        if(Float.isNaN(raw) || Float.isInfinite(raw) || raw <= 0f) return 1e-4f;
        if(ema < 0f){
            ema = raw;
            return raw;
        }
        float out = Math.min(raw, Math.max(ema * 2f, 0.5f));
        ema += (out - ema) * 0.25f;
        return out;
    }

    public static void resetSmooth(){ ema = -1f; }

    public static void reset(){
        // при смене мира — не трогаем настройки пользователя, только применяем заново
        resetSmooth();
        applyAll();
    }

    /** Применить всё по настройкам (вызывается при загрузке клиента и после изменений). */
    public static void applyAll(){
        if(headless || renderer == null) return;
        applyZoom();
        applyPerf();
        applyTime();
        applySchematicSize();
    }

    // ============================ РАЗМЕР СХЕМ (Scheme-Size) ============================

    /** Порт главной фичи Scheme-Size: снимаем ванильный лимит 64 (до 512). */
    public static void applySchematicSize(){
        int v = Mathf.clamp(MKUKICSettings.s().schematicSize, 16, 512);
        if(maxSchematicSize != v){
            maxSchematicSize = v;
        }
    }

    // ============================ ЗУМ ============================

    public static void applyZoom(){
        if(headless || renderer == null) return;
        float mult = MKUKICSettings.s().zoomMult;
        if(mult > 1.01f){
            if(!zoomCaptured){
                zoomCaptured = true;
                origMinZoom = renderer.minZoom;
                origMaxZoom = renderer.maxZoom;
                origMinZoomGame = renderer.minZoomInGame;
                origMaxZoomGame = renderer.maxZoomInGame;
            }
            renderer.minZoom = origMinZoom / mult;       // ближе
            renderer.maxZoom = origMaxZoom * mult;       // дальше
            renderer.minZoomInGame = origMinZoomGame / mult;
            renderer.maxZoomInGame = origMaxZoomGame * mult;
        }else if(zoomCaptured){
            renderer.minZoom = origMinZoom;
            renderer.maxZoom = origMaxZoom;
            renderer.minZoomInGame = origMinZoomGame;
            renderer.maxZoomInGame = origMaxZoomGame;
            zoomCaptured = false;
        }
    }

    // ============================ ПРОИЗВОДИТЕЛЬНОСТЬ ============================

    public static void applyPerf(){
        if(headless || renderer == null) return;
        MKUKICSettings s = MKUKICSettings.s();
        if(!perfCaptured){
            perfCaptured = true;
            origWeather = renderer.drawWeather;
            origEffects = renderer.enableEffects;
            origLight = renderer.drawLight;
            origShields = renderer.animateShields;
            origWater = renderer.animateWater;
            origPings = renderer.showPings;
            origOtherPlans = renderer.showOtherBuildPlans;
            origDisplays = renderer.drawDisplays;
        }
        if(s.perfMaster){
            renderer.drawWeather = origWeather && !s.perfWeather;
            renderer.enableEffects = origEffects && !s.perfEffects;
            renderer.drawLight = origLight && !s.perfLight;
            renderer.animateShields = origShields && !s.perfShields;
            renderer.animateWater = origWater && !s.perfWater;
            renderer.showPings = origPings && !s.perfPings;
            renderer.showOtherBuildPlans = origOtherPlans && !s.perfOtherPlans;
            renderer.drawDisplays = origDisplays && !s.perfDisplays;
        }else{
            renderer.drawWeather = origWeather;
            renderer.enableEffects = origEffects;
            renderer.drawLight = origLight;
            renderer.animateShields = origShields;
            renderer.animateWater = origWater;
            renderer.showPings = origPings;
            renderer.showOtherBuildPlans = origOtherPlans;
            renderer.drawDisplays = origDisplays;
        }
    }

    // ============================ EDGE PANNING (MI2) ============================

    /**
     * Битовая маска краёв: 1=лево, 2=право, 4=низ, 8=верх. 0 — мышь не у края.
     * Чистая функция, покрывается FeatureTest.
     */
    public static int edgeBits(float mx, float my, float w, float h, float margin){
        if(w <= 0f || h <= 0f || margin <= 0f) return 0;
        int b = 0;
        if(mx < margin) b |= 1;
        if(mx > w - margin) b |= 2;
        if(my < margin) b |= 4;
        if(my > h - margin) b |= 8;
        return b;
    }

    /** Курсор наведен на элемент интерфейса, которому мы можем помешать. */
    public static boolean isUiElement(Element e){
        return e instanceof arc.scene.ui.Button
            || e instanceof arc.scene.ui.TextField
            || e instanceof arc.scene.ui.Slider
            || e instanceof arc.scene.ui.CheckBox
            || e instanceof arc.scene.ui.ScrollPane
            || e instanceof arc.scene.ui.Label
            || e instanceof arc.scene.ui.Image;
    }

    /**
     * Панорамирование камеры краем экрана (порт MI2-Utilities «Edge Panning», десктоп).
     * Работает только когда юнит жив: иначе камера и так свободная (наблюдатель).
     * Ничего не шлёт на сервер — двигаем только локальную камеру.
     */
    public static void edgePanTick(){
        MKUKICSettings s = MKUKICSettings.s();
        if(!s.enabled || !s.qolEnabled || !s.edgePan) return;
        if(mobile || headless) return;
        if(Core.scene == null || Core.input == null || Core.camera == null) return;
        if(Core.scene.hasDialog() || Core.scene.hasField()) return;
        if(state == null || state.isMenu()) return;
        if(player == null || player.dead() || player.unit() == null) return;

        float w = Core.graphics.getWidth(), h = Core.graphics.getHeight();
        if(w <= 0f || h <= 0f) return;

        // курсор над интерактивным элементом интерфейса — не мешаем кликать
        Element hover = Core.scene.getHoverElement();
        if(isUiElement(hover)) return;

        int bits = edgeBits(Core.input.mouseX(), Core.input.mouseY(), w, h, s.edgePanMargin);
        if(bits == 0) return;

        float dt = Mathf.clamp(Core.graphics.getDeltaTime(), 0f, 0.1f);
        // мировых единиц на пиксель = ширина видимой области / ширина экрана
        float worldPerPixel = Core.graphics.getWidth() <= 0f ? 1f
            : Core.camera.width / Core.graphics.getWidth();
        float speed = s.edgePanSpeed * 60f * dt * worldPerPixel * 12f;
        float dx = ((bits & 2) != 0 ? speed : 0f) - ((bits & 1) != 0 ? speed : 0f);
        float dy = ((bits & 8) != 0 ? speed : 0f) - ((bits & 4) != 0 ? speed : 0f);
        if(dx == 0f && dy == 0f) return;

        Core.camera.position.x = Mathf.clamp(Core.camera.position.x + dx,
            -tilesize * 2f, world.unitWidth() + tilesize * 2f);
        Core.camera.position.y = Mathf.clamp(Core.camera.position.y + dy,
            -tilesize * 2f, world.unitHeight() + tilesize * 2f);
    }

    // ============================ СКОРОСТЬ ВРЕМЕНИ ============================

    public static boolean timeControlAllowed(){
        return !net.active() || net.server();
    }

    /**
     * Ванильная дельта игры, 1-в-1 как в ClientLauncher.setup() (сверено с байткодом build 160.7):
     * result = graphicsDelta * 60; NaN/Inf -> 1; иначе clamp(result, 1e-4, maxDeltaClient).
     * При 60 FPS delta ~= 1.0 — это и есть «базовая скорость».
     */
    public static float vanillaDelta(){
        float r = Core.graphics.getDeltaTime() * 60f;
        return Float.isNaN(r) || Float.isInfinite(r) ? 1f : Mathf.clamp(r, 1e-4f, maxDeltaClient);
    }

    public static void restoreVanillaDelta(){
        Time.setDeltaProvider(ViewControl::vanillaDelta);
    }

    public static void applyTime(){
        if(headless) return;
        float ts = MKUKICSettings.s().timeScale;
        boolean smooth = MKUKICSettings.s().qolSmoothDelta;
        if((Mathf.equal(ts, 1f, 0.001f) && !smooth) || !timeControlAllowed()){
            if(timeApplied){
                restoreVanillaDelta();
                resetSmooth();
                timeApplied = false;
            }
            return;
        }
        final float scale = Mathf.clamp(ts, 0.05f, 8f);
        // Множитель применяется ПОСЛЕ ванильного клампа: 1x == обычная скорость,
        // 4x == ровно в 4 раза быстрее, защита от лаг-спайков сохраняется.
        Time.setDeltaProvider(() -> {
            float d = vanillaDelta();
            if(MKUKICSettings.s().qolSmoothDelta) d = smoothDelta(d);
            return d * scale;
        });
        timeApplied = true;
    }

    // ============================ ОКНО ============================

    public static void build(Table t){
        QolWindows.section(t, "Зум камеры");
        t.add("[lightgray]Расширенный зум: колёсико мыши zoom-in/zoom-out сильнее ванильного.\n"
            + "Значение 1.0 = ванильные границы.").left().top().wrap().width(330f).pad(3f, 0f, 5f, 0f).row();
        t.table(c -> {
            c.left();
            c.add("Расширение зума:").minWidth(140f).left();
            Label val = new Label("", Styles.outlineLabel);
            c.add(val).right().minWidth(60f).padRight(6f);
            c.slider(1f, 8f, 0.25f, MKUKICSettings.s().zoomMult, v -> {
                MKUKICSettings.s().zoomMult = v;
                MKUKICSettings.save();
                applyZoom();
            }).width(150f);
            val.setText(() -> Mathf.equal(MKUKICSettings.s().zoomMult, 1f, 0.01f) ? "обычный" : "x" + Strings.autoFixed(MKUKICSettings.s().zoomMult, 1));
        }).left().row();
        t.table(c -> {
            c.left();
            c.button("Камера к ядру", MKUKICSkin.actBtn, () -> {
                if(player != null && !player.dead()){
                    CoreBuild core = player.bestCore();
                    if(core != null) Core.camera.position.set(core.x, core.y);
                }
            }).minWidth(150f).height(30f).pad(2f);
            c.button("Камера к юниту", MKUKICSkin.actBtn, () -> {
                if(player != null && !player.dead() && player.unit() != null){
                    Core.camera.position.set(player.unit().x, player.unit().y);
                }
            }).minWidth(150f).height(30f).pad(2f);
        }).left().row();

        QolWindows.section(t, "Скорость симуляции (TimeControl)");
        t.label(() -> timeControlAllowed()
            ? "[lightgray]Локальная скорость игры. 1.0 = нормально; <1 — слоу-мо; >1 — ускорение."
            : "[scarlet]На выделенном сервере недоступно — скорость задаёт сервер.")
            .left().top().wrap().width(330f).pad(3f, 0f, 5f, 0f).row();
        t.table(c -> {
            c.left();
            c.add("Скорость:").minWidth(140f).left();
            Label val = new Label("", Styles.outlineLabel);
            c.add(val).right().minWidth(60f).padRight(6f);
            c.slider(0.1f, 4f, 0.1f, MKUKICSettings.s().timeScale, v -> {
                if(!timeControlAllowed()){
                    MKUKICMod.toast("[scarlet]Только хост/одиночная игра");
                    return;
                }
                MKUKICSettings.s().timeScale = v;
                MKUKICSettings.save();
                applyTime();
            }).width(150f).disabled(b -> !timeControlAllowed());
            val.setText(() -> Strings.autoFixed(MKUKICSettings.s().timeScale, 1) + "x");
        }).left().row();
        t.table(c -> {
            c.left();
            for(String[] p : new String[][]{{"0.25x", "0.25"}, {"0.5x", "0.5"}, {"1x", "1"}, {"2x", "2"}, {"4x", "4"}}){
                c.button(p[0], MKUKICSkin.actBtn, () -> {
                    if(!timeControlAllowed()){
                        MKUKICMod.toast("[scarlet]Только хост/одиночная игра");
                        return;
                    }
                    MKUKICSettings.s().timeScale = Float.parseFloat(p[1]);
                    MKUKICSettings.save();
                    applyTime();
                }).minWidth(52f).height(28f).pad(2f);
            }
        }).left().row();
        t.check("Сглаживание лаг-спайков (дельта)", MKUKICSettings.s().qolSmoothDelta, v -> {
            MKUKICSettings.s().qolSmoothDelta = v;
            MKUKICSettings.save();
            resetSmooth();
            applyTime();
        }).left().padTop(4f).row();
        t.add("[darkgray]Одиночные просадки FPS не будут дёргать скорость симуляции.\n"
            + "Работает как хост/одиночная игра (как и множитель времени).").left().wrap().width(330f).pad(1f, 0f, 4f, 0f).row();

        QolWindows.section(t, "Размер схем (Scheme-Size)");
        t.add("[lightgray]Снимает ванильный лимит размера схем (64). До 512 — как в моде Scheme-Size.\nСервер без мода может отклонять установку очень больших схем.").left().top().wrap().width(330f).pad(3f, 0f, 5f, 0f).row();
        t.table(c -> {
            c.left();
            c.add("Лимит схемы:").minWidth(140f).left();
            Label val = new Label("", Styles.outlineLabel);
            c.add(val).right().minWidth(60f).padRight(6f);
            c.slider(16f, 512f, 16f, MKUKICSettings.s().schematicSize, v -> {
                MKUKICSettings.s().schematicSize = (int)v;
                MKUKICSettings.save();
                applySchematicSize();
            }).width(150f);
            val.setText(() -> MKUKICSettings.s().schematicSize <= 64 ? "ваниль (64)" : MKUKICSettings.s().schematicSize + "x" + MKUKICSettings.s().schematicSize);
        }).left().row();

        QolWindows.section(t, "Камера краем экрана (MI2 Edge Panning)");
        t.add("[lightgray]Подводишь мышь к краю экрана — камера едет в эту сторону.\n"
            + "Только десктоп, только живой юнит, ничего не отправляет на сервер.")
            .left().top().wrap().width(330f).pad(3f, 0f, 5f, 0f).row();
        t.check("Панорамирование краем", MKUKICSettings.s().edgePan, v -> {
            MKUKICSettings.s().edgePan = v;
            MKUKICSettings.save();
        }).left().pad(2f).row();
        t.table(c -> {
            c.left();
            c.add("Зона у края:").minWidth(140f).left();
            Label mv = new Label("", Styles.outlineLabel);
            c.add(mv).right().minWidth(50f).padRight(6f);
            c.slider(4f, 60f, 2f, MKUKICSettings.s().edgePanMargin, v -> {
                MKUKICSettings.s().edgePanMargin = v;
                MKUKICSettings.save();
            }).width(150f);
            mv.setText(() -> (int)MKUKICSettings.s().edgePanMargin + " px");
        }).left().row();
        t.table(c -> {
            c.left();
            c.add("Скорость:").minWidth(140f).left();
            Label sv = new Label("", Styles.outlineLabel);
            c.add(sv).right().minWidth(50f).padRight(6f);
            c.slider(0.25f, 4f, 0.25f, MKUKICSettings.s().edgePanSpeed, v -> {
                MKUKICSettings.s().edgePanSpeed = v;
                MKUKICSettings.save();
            }).width(150f);
            sv.setText(() -> "x" + Strings.autoFixed(MKUKICSettings.s().edgePanSpeed, 2));
        }).left().row();

        QolWindows.section(t, "Производительность (меньше проходов рендера = больше FPS)");
        t.check("Режим производительности", MKUKICSettings.s().perfMaster, v -> {
            MKUKICSettings.s().perfMaster = v;
            MKUKICSettings.save();
            applyPerf();
        }).left().pad(3f).row();
        perfToggle(t, "Погода", s -> s.perfWeather, (s, v) -> s.perfWeather = v);
        perfToggle(t, "Частицы/эффекты", s -> s.perfEffects, (s, v) -> s.perfEffects = v);
        perfToggle(t, "Освещение", s -> s.perfLight, (s, v) -> s.perfLight = v);
        perfToggle(t, "Анимация щитов", s -> s.perfShields, (s, v) -> s.perfShields = v);
        perfToggle(t, "Анимация воды", s -> s.perfWater, (s, v) -> s.perfWater = v);
        perfToggle(t, "Пинги игроков", s -> s.perfPings, (s, v) -> s.perfPings = v);
        perfToggle(t, "Чужие планы стройки", s -> s.perfOtherPlans, (s, v) -> s.perfOtherPlans = v);
        perfToggle(t, "Экраны логики (display)", s -> s.perfDisplays, (s, v) -> s.perfDisplays = v);
    }

    private static void perfToggle(Table t, String title, arc.func.Boolf<MKUKICSettings> get, arc.func.Cons2<MKUKICSettings, Boolean> set){
        t.table(c -> {
            c.left();
            c.check("   " + title, get.get(MKUKICSettings.s()), v -> {
                set.get(MKUKICSettings.s(), v);
                MKUKICSettings.save();
                applyPerf();
            }).left();
        }).left().growX().padLeft(14f).row();
    }
}
