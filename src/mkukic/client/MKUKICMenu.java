package mkukic.client;

import arc.*;
import arc.func.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.input.*;
import arc.math.*;
import arc.math.geom.*;
import arc.scene.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import mindustry.content.*;
import mindustry.core.*;
import mindustry.entities.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.net.Packets.*;
import mindustry.type.*;
import mindustry.ui.*;
import mindustry.ui.dialogs.*;
import mindustry.world.blocks.storage.CoreBlock.*;
import mkukic.*;
import mkukic.client.onix.*;

import static mindustry.Vars.*;

/** Главное меню мода: вкладки, переключатели, слайдеры, спаунер, админ-панель. */
public class MKUKICMenu{
    private static BaseDialog dialog;
    private static Table body;
    private static int tab = 0;

    // состояние спаунера
    private static @Nullable UnitType spawnType = UnitTypes.dagger;
    private static int spawnCount = 1;
    private static Team spawnTeam = null;
    private static @Nullable Item spawnItem = Items.copper;
    private static int spawnItemAmount = 4000;

    // v1.17: вкладка «QoL» расформирована — её функции разложены по тематическим разделам
    // (визуальное -> «Визуал», управление юнитами -> «ИИ юнитов», автопилот -> «ИИ игрока»,
    //  стройка/инструменты -> «Утилиты», сводки и тревоги -> новый раздел «Информация»).
    private static final String[] tabNames = {"Бой", "ИИ юнитов", "ИИ игрока", "Визуал", "Информация", "Утилиты", "Админ", "Штаб", "Onix", "Настройки"};
    // Названия для людей. Ключи выше (tabNames) не трогаем: по ним вкладки ищут бинды (F8 → «Штаб») и тесты.
    private static final String[] tabTitles = {"Бой и бафы", "Армия (ИИ юнитов)", "Автопилот", "Визуал и камера", "Инфо и тревоги", "Инструменты", "Админ-панель", "Штаб (карта)", "Onix", "Настройки"};
    // Левая колонка собрана по смыслу: что делает вкладка, а не в каком порядке её писали.
    private static final String[] navGroupTitles = {"ИГРОК", "ЮНИТЫ", "МИР", "СЕРВЕР", "СИСТЕМА"};
    private static final int[][] navGroups = {{0, 2}, {1, 7}, {3, 4, 5}, {6, 8}, {9}};
    // родитель текущей группы опций: дочерние (с отступом) гаснут, пока родитель выключен
    private static @Nullable Boolf<MKUKICSettings> parentOn;
    private static int selGroup = -1;

    public static void init(){
        MKUKICSkin.init();
        dialog = new BaseDialog("MKUKIC Client");
        dialog.addCloseButton();
        // стиль окна: тёмная панель с акцентной рамкой
        Dialog.DialogStyle ds = new Dialog.DialogStyle();
        Dialog.DialogStyle old = dialog.getStyle();
        if(old != null) ds.stageBackground = old.stageBackground;
        ds.background = MKUKICSkin.panel;
        ds.titleFont = Fonts.def;
        ds.titleFontColor = MKUKICSkin.textHi;
        dialog.setStyle(ds);
        // разрешить ручной ресайз окна за края
        dialog.setResizable(true);
        dialog.setResizeBorder(14);

        // ---- фирменная шапка: логотип + название + версия ----
        dialog.titleTable.clear();
        dialog.titleTable.setBackground(MKUKICSkin.head);
        dialog.titleTable.margin(7f, 14f, 9f, 14f);
        dialog.titleTable.left();
        dialog.titleTable.image(MKUKICSkin.logoDrawable).size(28f).padRight(10f).left();
        dialog.titleTable.add("[#ffb454]MKUKIC[]").left();
        dialog.titleTable.add("[#e8ecf4] Client[]").padRight(12f).left();
        dialog.titleTable.add("[#8b94a5]v" + MKUKICMod.version + "[]").left();
        dialog.titleTable.add().growX();
        dialog.titleTable.button(Ico.of("star") + "  Быстрые переключатели", MKUKICSkin.actBtn, () -> {
            dialog.hide();
            QuickPalette.show();
        }).height(32f).minWidth(240f).padRight(10f);
        dialog.titleTable.add("[#8b94a5]" + Ico.of("menu") + " F9  ·  Shift+F9 — переключатели[]").padRight(4f).left();

        dialog.cont.table(t -> {
            // навигация слева: группы по смыслу, у каждой вкладки счётчик включённого
            t.table(nav -> {
                nav.top().left();
                nav.setBackground(MKUKICSkin.panelSoft);
                for(int g = 0; g < navGroups.length; g++){
                    nav.add("[#5f6b7d]" + navGroupTitles[g]).left().padLeft(12f).padTop(g == 0 ? 6f : 12f).padBottom(2f).row();
                    for(int idx : navGroups[g]){
                        final int fi = idx;
                        Cell<TextButton> c = nav.button("", MKUKICSkin.navBtn, () -> {
                            tab = fi;
                            rebuild();
                        }).width(220f).height(42f).pad(1.5f).checked(n -> tab == fi);
                        c.get().getLabel().setAlignment(Align.left);
                        c.update(bt -> {
                            int n = tabCount(fi);
                            bt.setText(tabIcon(tabNames[fi]) + "  " + tabTitles[fi] + (n > 0 ? "   [#ffb454]●" + n + "[]" : ""));
                        });
                        c.row();
                    }
                }
                nav.add().growY().row();
                nav.image(MKUKICSkin.divSoft).height(2f).growX().pad(4f, 8f, 6f, 8f).row();
                Cell<TextButton> cc = nav.button(Ico.of("cancel") + "  Закрыть  [#5f6b7d]Esc[]", MKUKICSkin.actBtn, dialog::hide)
                    .width(220f).height(40f).pad(0f, 2f, 6f, 2f);
                cc.get().getLabel().setAlignment(Align.left);
            }).left().fillY().width(234f);

            t.image(MKUKICSkin.vline).width(2f).fillY().pad(6f, 10f, 6f, 10f);

            // контент справа
            t.pane(MKUKICSkin.scroll, p -> {
                body = p;
                p.top().left();
            }).grow().top().left();
        }).grow().pad(8f, 8f, 2f, 8f);

        // ---- нижняя строка состояния ----
        dialog.cont.row();
        dialog.cont.table(f -> {
            f.left();
            f.setBackground(MKUKICSkin.panelSoft);
            f.label(() -> serverChip()).left().padLeft(12f);
            f.add().growX();
            f.label(() -> {
                int n = activeCount();
                return "[#8b94a5]Активных функций: " + (n > 0 ? "[#ffb454]" : "[#5f6b7d]") + n + "[]";
            }).padRight(12f);
        }).growX().height(30f).pad(2f, 8f, 8f, 8f);

        // развернуть окно почти на весь экран (диалог сам зажмёт себя рамками сцены)
        dialog.shown(() -> {
            sizeToScreen(dialog);
        });

        dialog.keyDown(KeyCode.escape, dialog::hide);
        dialog.keyDown(KeyCode.back, dialog::hide);

        rebuild();
    }

    /** Строка состояния сервера для футера меню. */
    private static String serverChip(){
        if(!net.active()) return "[#7dffa8]●[] [#8b94a5]Одиночная игра";
        if(!net.client()) return "[#7dffa8]●[] [#8b94a5]Вы — хост/сервер";
        if(AdminNet.serverHasMod()){
            return "[#7dffa8]●[] [#8b94a5]Сервер с модом v" + AdminNet.serverVersion +
                " [#6fd6ff]·[] [#8b94a5]роль: [#ffb454]" + AdminNet.roleName + "[]";
        }
        return "[#ff8f7d]●[] [#8b94a5]На сервере нет мода — часть функций ограничена";
    }

    /** Сколько основных функций сейчас включено. */
    private static int activeCount(){
        MKUKICSettings s = s();
        int n = 0;
        if(s.aimbot) n++;
        if(s.speed) n++;
        if(s.rage) n++;
        if(s.fortress) n++;
        if(s.godmode) n++;
        if(s.fly) n++;
        if(s.instantReload) n++;
        if(s.infiniteRange) n++;
        if(s.esp) n++;
        if(s.coinMagnet) n++;
        if(s.coinPilot) n++;
        if(s.paEnabled) n++;
        if(s.tripwire) n++;
        if(s.qolHpBars) n++;
        // v1.16/v1.17: модули, перенесённые из QoL-вкладки в тематические разделы
        if(s.attackAlert) n++;
        if(s.alerts) n++;
        if(s.xray) n++;
        if(s.coreFlow) n++;
        if(s.prodGraph) n++;
        if(s.chatLog) n++;
        if(s.edgePan) n++;
        if(s.perfMaster) n++;
        if(s.aiEnabled && (s.aiAutoMine || s.aiAutoBuild || s.aiAutoRepair || s.aiFollow || s.aiEscort || s.aiSquads)) n++;
        // v1.19: Onix-слой
        if(s.onixEnabled){
            if(s.onixAutoUnit) n++;
            if(s.onixAutofill) n++;
            if(s.onixBot) n++;
            if(s.onixDetect) n++;
            if(s.onixChatVote) n++;
            if(s.onixAfkReply) n++;
            if(s.onixConsole) n++;
        }
        return n;
    }

    /** Сколько основных функций включено на вкладке (число в левой колонке). */
    public static int tabCount(int idx){
        MKUKICSettings s = s();
        int n = 0;
        switch(idx){
            case 0 -> n = b(s.aimbot) + b(s.speed) + b(s.rage) + b(s.fortress) + b(s.godmode) + b(s.fly) + b(s.instantReload) + b(s.infiniteRange);
            case 1 -> n = b(s.aiEnabled && (s.aiAutoMine || s.aiAutoBuild || s.aiAutoRepair || s.aiFollow || s.aiEscort || s.aiSquads));
            case 2 -> n = b(s.paEnabled) + b(s.coinMagnet) + b(s.coinPilot) + b(s.onixEnabled && s.onixAutoUnit);
            case 3 -> n = b(s.esp) + b(s.espPlayers) + b(s.qolHpBars) + b(s.qolRanges) + b(s.qolServiceZones) + b(s.xray)
                + b(s.qolPathOverlay) + b(s.markerOverlay) + b(s.edgePan) + b(s.spectateFollow) + b(s.perfMaster);
            case 4 -> n = b(s.coreFlow) + b(s.prodGraph) + b(s.attackAlert) + b(s.alerts) + b(s.tripwire) + b(s.chatLog);
            case 5 -> n = b(s.cmdBarVisible) + b(s.qolDrillOverlay) + b(s.onixEnabled && s.onixAutofill);
            case 8 -> n = b(s.onixEnabled && (s.onixBot || s.onixDetect || s.onixChatVote || s.onixAfkReply || s.onixConsole));
            default -> n = 0;
        }
        return n;
    }

    private static int b(boolean v){
        return v ? 1 : 0;
    }

    /** Выключить боевые и ИИ-функции разом (кнопка «Выключить всё» и палитра). */
    public static void disableAll(){
        MKUKICSettings s = MKUKICSettings.s();
        s.aimbot = s.speed = s.rage = s.fortress = s.godmode = s.fly = s.esp = s.instantReload = false;
        s.aiAutoMine = s.aiAutoBuild = s.aiAutoRepair = s.aiFollow = s.aiEscort = false;
        CombatFeatures.reset();
        MKUKICSettings.save();
        rebuild();
    }

    /** Группы вкладок для левой колонки и плавающей панели: индексы вкладок. */
    public static int[][] navGroups(){
        return navGroups;
    }

    public static String navGroupTitle(int g){
        return navGroupTitles[g];
    }

    /** Подпись вкладки для людей (ключ остаётся в tabLabels()). */
    public static String tabTitle(int idx){
        return idx >= 0 && idx < tabTitles.length ? tabTitles[idx] : String.valueOf(idx);
    }

    public static String[] tabLabels(){
        return tabNames;
    }

    /** Иконка вкладки (шрифтовая). Public: используется тулбаром OverlayUI и тестами. */
    public static String tabIcon(String name){
        switch(name){
            case "Бой": return "" + (char)Iconc.commandAttack;
            case "ИИ юнитов": return "" + (char)Iconc.units;
            case "ИИ игрока": return "" + (char)Iconc.logic;
            case "Визуал": return "" + (char)Iconc.eye;
            case "Утилиты": return "" + (char)Iconc.wrench;
            case "Админ": return "" + (char)Iconc.terminal;
            case "Штаб": return "" + (char)Iconc.map;
            case "Информация": return "" + (char)Iconc.info;
            case "Onix": return "" + (char)Iconc.github;
            case "QoL": return "" + (char)Iconc.layers;  // совместимость со старыми вызовами showTab
            default: return "" + (char)Iconc.settings;
        }
    }

    /** Открыть меню сразу на нужной вкладке (использует плавающая панель). */
    public static void showTab(int idx){
        tab = idx;
        show();
    }

    public static void show(){
        if(dialog == null) init();
        rebuild();
        dialog.show();
    }

    public static void hide(){
        if(dialog != null) dialog.hide();
    }

    /** Открыто ли главное меню мода (чтобы плавающие панели не лезли поверх). */
    public static boolean isShown(){
        return dialog != null && dialog.isShown();
    }

    public static void toggle(){
        if(dialog == null) init();
        if(dialog.isShown()) dialog.hide(); else show();
    }

    /** Перерисовать содержимое меню (используется админ-панелью). */
    public static void refresh(){
        rebuild();
    }

    /** Собрать содержимое вкладки idx в произвольный стол (используется поиском функций). */
    public static void buildTabInto(int idx, Table into){
        switch(idx){
            case 0 -> combatTab(into);
            case 1 -> aiTab(into);
            case 2 -> playerAiTab(into);
            case 3 -> visualTab(into);
            case 4 -> infoTab(into);
            case 5 -> utilTab(into);
            case 6 -> AdminPanel.build(into);
            case 7 -> TacticalHQ.build(into);
            case 8 -> OnixPanel.build(into);
            case 9 -> settingsTab(into);
        }
    }

    static void rebuild(){
        if(body == null) return;
        body.clear();
        body.top().left();
        parentOn = null;
        try{
            // v1.19 (порт Onix): «Поиск функций в панели» — строка поиска живёт над вкладкой
            OnixPanel.searchRow(body);
            if(!OnixPanel.searchText().isEmpty()){
                OnixPanel.buildSearchResults(body, OnixPanel.searchText());
                return;
            }
            // «панель функций вылетает снизу с каскадом категорий, таб открывается без телепорта»
            OnixAnims.armPanel();
            switch(tab){
                case 0 -> combatTab(body);
                case 1 -> aiTab(body);
                case 2 -> playerAiTab(body);
                case 3 -> visualTab(body);
                case 4 -> infoTab(body);
                case 5 -> utilTab(body);
                case 6 -> AdminPanel.build(body);
                case 7 -> TacticalHQ.build(body);
                case 8 -> OnixPanel.build(body);
                case 9 -> settingsTab(body);
            }
        }catch(Throwable e){
            // страховка: сбой одного раздела не должен уносить всё меню (урок краш-репорта v1.19.0)
            String name = tab >= 0 && tab < tabNames.length ? tabNames[tab] : String.valueOf(tab);
            Log.err("[MKUKIC] раздел «" + name + "» не построился", e);
            try{
                body.clear();
                body.top().left();
                body.add("[scarlet]Не удалось построить раздел «" + name + "»").left().padTop(18f).row();
                body.add("[#c2cad8]" + String.valueOf(e)).left().wrap().width(660f).padTop(6f).row();
                body.add("[#8b94a5]Подробный стек — в логе игры. Остальные вкладки работают.").left().wrap().width(660f).padTop(8f).row();
            }catch(Throwable ignored){}
        }
    }

    // ============================== ВКЛАДКИ ==============================

    private static void combatTab(Table t){
        header(t, Ico.of("commandAttack") + " Боевые бафы и автоприцел", "Применяется только к вашему юниту. Бафы — локальные множители юнита: по сети они не ходят и не создают рассинхрона.");

        toggle(t, Ico.of("commandAttack") + " Автоприцел", "Сам наводится и стреляет, с упреждением", s -> s.aimbot, (s, v) -> s.aimbot = v);
        toggle(t, "   " + Ico.of("ok") + " Огонь без нажатия", null, s -> s.aimShoot, (s, v) -> s.aimShoot = v);
        toggle(t, "   " + Ico.of("zoom") + " Упреждение по цели", "Учитывает скорость цели и снаряда", s -> s.aimPredict, (s, v) -> s.aimPredict = v);
        toggle(t, "   " + Ico.of("box") + " Бить по зданиям", null, s -> s.aimBuildings, (s, v) -> s.aimBuildings = v);
        toggle(t, "   " + Ico.of("lock") + " Тихий прицел (корпус не крутится)", "Корпус не разворачивается, только стволы", s -> s.aimSilent, (s, v) -> s.aimSilent = v);
        toggle(t, "   " + Ico.of("mouseLeft") + " Только при зажатой ЛКМ", null, s -> s.aimOnlyOnFire, (s, v) -> s.aimOnlyOnFire = v);
        slider(t, "   " + Ico.of("resize") + " Радиус захвата", 60f, 1400f, 10f, s -> s.aimRange, v -> s().aimRange = v, v -> v <= 61f ? "авто" : (int)(float)v + " ед.");
        chooser(t, "   " + Ico.of("star") + " Приоритет целей", new String[]{"Ближний", "Опасный", "Воздух", "Земля", "На экране"}, s -> s.aimPriority, v -> s().aimPriority = v);

        sep(t);
        toggle(t, Ico.of("up") + " Ускорение юнита", "Множитель скорости вашего юнита", s -> s.speed, (s, v) -> s.speed = v);
        slider(t, "   " + Ico.of("up") + " Множитель скорости", 1.05f, 6f, 0.05f, s -> s.speedMult, v -> s().speedMult = v, v -> "x" + Strings.autoFixed(v, 2));
        toggle(t, Ico.of("fire") + " Ярость (урон и темп огня)", null, s -> s.rage, (s, v) -> s.rage = v);
        slider(t, "   " + Ico.of("fire") + " Множитель урона", 1.05f, 20f, 0.05f, s -> s.damageMult, v -> s().damageMult = v, v -> "x" + Strings.autoFixed(v, 2));
        slider(t, "   " + Ico.of("refresh") + " Множитель перезарядки", 1.05f, 10f, 0.05f, s -> s.reloadMult, v -> s().reloadMult = v, v -> "x" + Strings.autoFixed(v, 2));
        toggle(t, "   " + Ico.of("refresh") + " Мгновенная перезарядка", "Обнуляет cooldown орудий каждый тик", s -> s.instantReload, (s, v) -> s.instantReload = v);

        sep(t);
        toggle(t, Ico.of("defense") + " Крепость (HP и броня)", "Множитель прочности", s -> s.fortress, (s, v) -> s.fortress = v);
        slider(t, "   " + Ico.of("defense") + " Множитель HP", 1.05f, 50f, 0.05f, s -> s.healthMult, v -> s().healthMult = v, v -> "x" + Strings.autoFixed(v, 2));
        slider(t, "   " + Ico.of("defense") + " Плоская броня", 0f, 200f, 1f, s -> s.armorAdd, v -> s().armorAdd = v, v -> (int)(float)v + " ед.");
        toggle(t, Ico.of("star") + " Бессмертие", "Полученный урон мгновенно компенсируется", s -> s.godmode, (s, v) -> s.godmode = v);
        toggle(t, Ico.of("add") + " Авто-лечение", null, s -> s.autoHeal, (s, v) -> s.autoHeal = v);

        sep(t);
        toggle(t, Ico.of("units") + " Полёт для любого юнита", "Любой наземный юнит летает", s -> s.fly, (s, v) -> s.fly = v);
        toggle(t, Ico.of("resize") + " Дальность орудий ×2", null, s -> s.infiniteRange, (s, v) -> s.infiniteRange = v);
        slider(t, Ico.of("hammer") + " Радиус стройки +", 0f, 600f, 10f, s -> s.buildRangeAdd, v -> s().buildRangeAdd = v, v -> v < 1f ? "выкл" : "+" + (int)(float)v);
        slider(t, Ico.of("production") + " Скорость добычи", 1f, 10f, 0.1f, s -> s.mineSpeedMult, v -> s().mineSpeedMult = v, v -> v <= 1.001f ? "обычная" : "x" + Strings.autoFixed(v, 1));
    }

    private static void aiTab(Table t){
        header(t, Ico.of("logic") + " Армия: ИИ юнитов",
            "Порядок работы: [accent]1[] выделить юнитов → [accent]2-4[] включить нужные действия → " +
            "[accent]5[] при необходимости собрать группы: у каждой группы свои действия, свой список игроков, " +
            "которым она помогает, и привязка. Команды уходят на сервер обычными приказами игры.");

        // ---------- 1. ВЫДЕЛЕНИЕ ----------
        header(t, Ico.of("players") + " Шаг 1 · Выделение", null);
        t.table(c -> {
            c.left();
            c.button(Ico.of("players") + " Выделить всех рядом", MKUKICSkin.actBtn, UnitAI::selectAllNearby).minWidth(180f).height(36f).pad(2f);
            c.button(Ico.of("move") + " Собрать ко мне", MKUKICSkin.actBtn, UnitAI::gatherToPlayer).minWidth(150f).height(36f).pad(2f);
            c.button(Ico.of("cancel") + " Снять выделение", MKUKICSkin.actBtn, UnitAI::clearOrders).minWidth(150f).height(36f).pad(2f);
        }).left().row();
        t.label(() -> "[lightgray]В выделении MKUKIC: [accent]" + UnitAI.selIds.size +
            "[] юнитов (+ ваш). Ванильное выделение рамкой подхватывается автоматически.")
            .left().growX().wrap().padTop(2f).row();
        slider(t, Ico.of("resize") + " Радиус выделения и ИИ", 200f, 4000f, 50f, s -> s.aiRadius, v -> s().aiRadius = v, v -> (int)(float)v + " ед.");
        toggle(t, Ico.of("logic") + " Мастер-выключатель ИИ", "Мастер-переключатель раздела", s -> s.aiEnabled, (s, v) -> s.aiEnabled = v);

        // ---------- 2. РАБОТА ----------
        header(t, Ico.of("production") + " Шаг 2 · Работа", null);
        toggle(t, Ico.of("production") + " Добыча руды", "Серверная команда «копать»: сами находят руду и сдают в ядро", s -> s.aiAutoMine, (s, v) -> s.aiAutoMine = v);
        toggle(t, Ico.of("hammer") + " Стройка и ремонт", "Команда «восстановление»: строят и чинят блоки команды рядом", s -> s.aiAutoBuild, (s, v) -> s.aiAutoBuild = v);
        toggle(t, "   " + Ico.of("wrench") + " Только ремонт", "Команда «ремонт» вместо восстановления", s -> s.aiAutoRepair, (s, v) -> s.aiAutoRepair = v);
        toggle(t, "   " + Ico.of("refresh") + " Восстановление разрушенного", "Усиленный режим восстановления (rebuild)", s -> s.aiAutoRebuild, (s, v) -> s.aiAutoRebuild = v);

        // ---------- 3. БОЙ ----------
        header(t, Ico.of("commandAttack") + " Шаг 3 · Бой", null);
        toggle(t, Ico.of("commandAttack") + " Авто-выбор целей", "Стойка «преследовать цель»: сами атакуют врагов в радиусе", s -> s.aiAutoTarget, (s, v) -> s.aiAutoTarget = v);
        toggle(t, Ico.of("defense") + " Эскорт (прикрытие точки)", "Держатся возле точки привязки и атакуют врагов", s -> s.aiEscort, (s, v) -> s.aiEscort = v);
        slider(t, "   " + Ico.of("resize") + " Радиус эскорта", 100f, 1200f, 20f, s -> s.escortRange, v -> s().escortRange = v, v -> (int)(float)v + " ед.");

        // ---------- 4. ДВИЖЕНИЕ И БЕЗОПАСНОСТЬ ----------
        header(t, Ico.of("move") + " Шаг 4 · Движение и безопасность", null);
        toggle(t, Ico.of("right") + " Следование за привязкой", "Периодический приказ идти к точке привязки", s -> s.aiFollow, (s, v) -> s.aiFollow = v);
        slider(t, "   " + Ico.of("resize") + " Дистанция следования", 20f, 400f, 5f, s -> s.followDistance, v -> s().followDistance = v, v -> (int)(float)v + " ед.");
        toggle(t, Ico.of("left") + " Отход при низком HP", "Приказ идти к ядру лечиться", s -> s.aiRetreat, (s, v) -> s.aiRetreat = v);
        slider(t, "   " + Ico.of("left") + " Порог отхода (HP)", 0.05f, 0.9f, 0.05f, s -> s.retreatHealth, v -> s().retreatHealth = v, v -> (int)(v * 100) + "%");
        toggle(t, Ico.of("home") + " Бегство от врагов к ядру", "Если враги появились в радиусе — юниты отходят ближе к ядру", s -> s.aiSafeZone, (s, v) -> s.aiSafeZone = v);
        slider(t, "   " + Ico.of("zoom") + " Радиус обнаружения врагов", 100f, 1200f, 25f, s -> s.fleeRadius, v -> s().fleeRadius = v, v -> (int)(float)v + " ед.");
        row(t, Ico.of("home") + " Всех к ядру (отступление)", "Юниты идут в проходимую точку рядом с ядром — наземные мехи больше не упрутся в само ядро", UnitAI::retreatToCore);

        // ---------- 5. РЕМОНТ ТРАНСПОРТА ----------
        header(t, Ico.of("wrench") + " Шаг 5 · Ремонт транспорта и турелей", null);
        toggle(t, Ico.of("distribution") + " Чинить конвейеры, мосты, турели", "Все юниты-строители уходят чинить повреждённые конвейеры, мосты, роутеры, " +
            "сортировщики, а также турели и заводы команды. Ищут повреждённое по всей базе, идут туда и чинят.",
            s -> s.aiRepairTransports, (s, v) -> s.aiRepairTransports = v);
        row(t, Ico.of("zoom") + " Найти повреждённые блоки", "Показать, сколько повреждённых блоков транспорта/турелей/заводов на базе", () -> {
            UnitAI.scanTransports();
            MKUKICMod.toast("Повреждено блоков (транспорт/турели/заводы): " + UnitAI.damagedTransports.size);
        });
        t.label(() -> "[lightgray]Сейчас повреждено: [accent]" + UnitAI.damagedTransports.size + "[] блоков")
            .left().padTop(2f).row();

        // ---------- 6. ОТРЯДЫ ----------
        header(t, Ico.of("units") + " Шаг 6 · Отряды на пути врага", null);
        t.add("[darkgray]Выделенные боевые юниты делятся на равные отряды. Каждый отряд занимает точку на пути " +
            "от точки высадки врага к ядру и держит её: отбившиеся возвращаются, враги в радиусе атакуются. " +
            "Если на карте нет фиксированных точек высадки — отряды встают в круговую оборону вокруг ядра.").left().top().wrap().width(660f).padBottom(4f).row();
        toggle(t, Ico.of("units") + " Режим отрядов", "Отряды удерживают свои точки и атакуют врагов", s -> s.aiSquads, (s, v) -> s.aiSquads = v);
        slider(t, Ico.of("units") + " Количество отрядов", 0f, 8f, 1f, s -> s.squadCount, v -> s().squadCount = (int)v,
            v -> (int)(float)v <= 0 ? "по точкам высадки" : "" + (int)(float)v);
        t.table(c -> {
            c.left();
            c.button(Ico.of("units") + " Разделить на отряды", MKUKICSkin.actBtn, () -> {
                UnitAI.makeSquads();
                rebuild();
            }).minWidth(200f).height(36f).pad(2f);
            c.button(Ico.of("cancel") + " Распустить отряды", MKUKICSkin.actBtn, () -> {
                UnitAI.clearSquads();
                rebuild();
            }).minWidth(160f).height(36f).pad(2f);
        }).left().row();
        t.label(() -> {
            StringBuilder sb = new StringBuilder("[lightgray]Активных отрядов: [accent]" + UnitAI.squads.size);
            for(int i = 0; i < UnitAI.squads.size; i++){
                sb.append("\n  Отряд ").append(i + 1).append(": ").append(UnitAI.squads.get(i).ids.size).append(" юнитов");
            }
            return sb.toString();
        }).left().top().wrap().width(400f).padTop(3f).row();

        // ---------- 7. ГРУППЫ ----------
        header(t, Ico.of("players") + " Шаг 7 · Группы юнитов", null);
        t.add("[darkgray]Группа перекрывает переключатели выше для своих типов юнитов. Пустой список типов = все юниты.").left().top().wrap().width(660f).padBottom(4f).row();
        t.table(gl -> {
            gl.left();
            for(int i = 0; i < UnitGroups.groups.size; i++){
                final int gi = i;
                UnitGroups.Group g = UnitGroups.groups.get(i);
                gl.button(g.name + (g.types.isEmpty() ? " (все)" : " [" + g.types.size + "]"), MKUKICSkin.toggleBtn, () -> {
                    selGroup = (selGroup == gi ? -1 : gi);
                    rebuild();
                }).checked(b -> selGroup == gi).minWidth(130f).height(34f).pad(2f);
            }
            gl.button(Ico.of("add") + " Группа", MKUKICSkin.actBtn, () -> {
                UnitGroups.groups.add(new UnitGroups.Group());
                UnitGroups.save();
                selGroup = UnitGroups.groups.size - 1;
                rebuild();
            }).minWidth(100f).height(34f).pad(2f);
        }).left().row();
        if(selGroup >= 0 && selGroup < UnitGroups.groups.size){
            UnitGroups.Group g = UnitGroups.groups.get(selGroup);
            t.table(e -> {
                e.left();
                e.add("Имя:").padRight(6f);
                e.field(g.name, MKUKICSkin.field, txt -> {
                    g.name = txt;
                    UnitGroups.save();
                }).width(140f);
                e.check(" свои настройки", g.override, v -> {
                    g.override = v;
                    UnitGroups.save();
                }).padLeft(10f);
                e.button("удалить", MKUKICSkin.actBtn, () -> {
                    UnitGroups.groups.remove(selGroup);
                    selGroup = -1;
                    UnitGroups.save();
                    rebuild();
                }).padLeft(10f).minWidth(90f);
            }).left().padTop(4f).row();
            t.table(ch -> {
                ch.left();
                for(String tn : new Seq<>(g.types)){
                    UnitType ut = content.units().find(u2 -> u2.name.equals(tn));
                    Table chip = new Table();
                    chip.left();
                    chip.background(MKUKICSkin.panelSoft);
                    if(ut != null && ut.uiIcon != null) chip.image(ut.uiIcon).size(22f).padRight(4f);
                    chip.add(ut != null ? ut.localizedName : tn).padRight(4f);
                    chip.button("x", MKUKICSkin.actBtn, () -> {
                        g.types.remove(tn);
                        UnitGroups.save();
                        rebuild();
                    }).size(24f);
                    ch.add(chip).pad(2f).margin(4f);
                }
                ch.button(Ico.of("add") + " Тип юнита", MKUKICSkin.actBtn, () -> groupTypeChooser(g)).minWidth(130f).height(34f).pad(2f);
            }).left().padTop(4f).row();
            gToggle(t, g, Ico.of("production") + " Копать", null, gg -> gg.mine, (gg, v) -> gg.mine = v);
            gToggle(t, g, Ico.of("hammer") + " Строить и восстанавливать", null, gg -> gg.build, (gg, v) -> gg.build = v);
            gToggle(t, g, Ico.of("wrench") + " Ремонтировать", null, gg -> gg.repair, (gg, v) -> gg.repair = v);
            gToggle(t, g, Ico.of("players") + " Помогать игрокам", "Команда «помощь»: достраивают рядом с привязанными игроками и прикрывают их", gg -> gg.assist, (gg, v) -> gg.assist = v);
            t.table(c -> {
                c.left();
                c.add("   Кому помогать / за кем держаться:").padRight(6f);
                c.button("выбрать игроков...", MKUKICSkin.actBtn, () -> playersMultiChooser(g.helpPlayers, "Кому помогает группа", UnitGroups::save)).minWidth(180f).height(34f)
                    .update(b -> b.setText(g.helpPlayers.isEmpty() ? "возле меня" : String.join(", ", g.helpPlayers)));
            }).left().padLeft(24f).padBottom(3f).row();
            gToggle(t, g, Ico.of("right") + " Следовать", null, gg -> gg.follow, (gg, v) -> gg.follow = v);
            gToggle(t, g, Ico.of("defense") + " Эскорт", null, gg -> gg.escort, (gg, v) -> gg.escort = v);
            gToggle(t, g, Ico.of("commandAttack") + " Авто-выбор целей", null, gg -> gg.target, (gg, v) -> gg.target = v);
            gToggle(t, g, Ico.of("left") + " Отходить при низком HP", null, gg -> gg.retreat, (gg, v) -> gg.retreat = v);
            gToggle(t, g, Ico.of("home") + " Бежать от врагов к ядру", null, gg -> gg.safe, (gg, v) -> gg.safe = v);
        }

        sep(t);
        row(t, Ico.of("add") + " Вылечить выделенных", "Мгновенно (локально); в сети сервер может откатить HP чужих юнитов", () -> {
            int n = 0;
            for(Unit u : UnitAI.managed()){
                u.heal(u.maxHealth());
                n++;
            }
            MKUKICMod.toast("Вылечено: " + n);
        });

        // ---- v1.17: перенесено из QoL-вкладки — управление отрядами относится к ИИ юнитов ----
        sep(t);
        sub(t, Ico.of("commandRally") + " Формации и фокус-огонь (RTS)");
        row(t, "  " + Ico.of("commandRally") + " Строем к курсору (F3)",
            "Отправить выделенных юнитов к курсору строем: выделенные (до 80) назначаются на ближайшие точки строя и идут вместе",
            () -> mkukic.client.qol.Formations.moveToCursor());
        winRow(t, "formations", "  " + Ico.of("commandRally") + " Окно: Формации (RTS)",
            "Тип строя (линия/колонна/клин/квадрат/круг), шаг 8–64 и «Фокус-огонь» по врагу под курсором");
        t.table(c -> {
            c.left();
            c.add("[#c2cad8]Строй:").left().minWidth(230f);
            for(int i = 0; i < mkukic.client.qol.Formations.types.length; i++){
                final String type = mkukic.client.qol.Formations.types[i];
                c.button(mkukic.client.qol.Formations.typeNames[i], MKUKICSkin.toggleBtn, () -> {
                    s().formType = type;
                    MKUKICSettings.save();
                    rebuild();
                }).checked(b -> type.equals(s().formType)).minWidth(84f).height(32f).pad(2f);
            }
        }).left().growX().padTop(2f).row();
        slider(t, Ico.of("resize") + " Шаг строя", 8f, 64f, 2f, s -> s.formSpacing, v -> s().formSpacing = v,
            v -> (int)((float)v / 8f) + " тайлов");
        sep(t);
        OnixPanel.botSection(t);
    }

    private static void playerAiTab(Table t){
        header(t, Ico.of("logic") + " Автопилот: ваш юнит",
            "Ваш юнит сам копает как poly и строит/чинит как mega. Mine-тайл и планы стройки вашего юнита " +
            "синхронизируются игрой как обычные действия, поэтому работает и в сети.");

        toggle(t, Ico.of("logic") + " Мастер-выключатель автопилота", "Управляет движением и действиями вашего юнита", s -> s.paEnabled, (s, v) -> s.paEnabled = v);
        toggle(t, Ico.of("production") + " Добыча руды", "Сам находит нужную руду, копает и сдаёт в ядро", s -> s.paMine, (s, v) -> s.paMine = v);
        toggle(t, Ico.of("hammer") + " Стройка и ремонт", "Достраивает недострои и чинит повреждённое рядом с собой", s -> s.paBuild, (s, v) -> s.paBuild = v);
        toggle(t, "   " + Ico.of("wrench") + " Чинить повреждённое", null, s -> s.paRepair, (s, v) -> s.paRepair = v);
        t.table(c -> {
            c.left();
            c.add("Помогать игрокам:").padRight(6f);
            c.button("выбрать список...", MKUKICSkin.actBtn, () -> playersMultiChooser(s().paHelpPlayers, "Кому помогает ваш юнит", MKUKICSettings::save)).minWidth(180f).height(34f)
                .update(b -> b.setText(s().paHelpPlayers.isEmpty() ? "никому" : String.join(", ", s().paHelpPlayers)));
        }).left().row();
        t.add("[darkgray]Ваш юнит берёт недострои/повреждённые блоки рядом с отмеченными игроками.").left().top().wrap().width(660f).padBottom(3f).row();
        toggle(t, Ico.of("defense") + " Уклонение от снарядов", "Маневрирует перпендикулярно летящим в вас снарядам", s -> s.paDodge, (s, v) -> s.paDodge = v);
        slider(t, "   " + Ico.of("resize") + " Радиус реакции", 60f, 500f, 10f, s -> s.paDodgeRadius, v -> s().paDodgeRadius = v, v -> (int)(float)v + " ед.");

        // ---- v1.17: перенесено из «Утилит» — магнит и автопилот до монет управляют ВАШИМ юнитом,
        //              поэтому живут рядом с остальным автопилотом игрока ----
        sep(t);
        coinSection(t);
    }

    private static void coinSection(Table t){
        header(t, Ico.of("star") + " Монеты: магнит и автопилот", null);
        t.add("[darkgray]Магнит телепортирует вашего юнита к ближайшей монете в радиусе и держит его,\n" +
            "пока монета не подберётся. Монеты = юниты/постройки/надписи/маркеры со словами\n" +
            "coin/money/credit/монет/деньг в имени, текстуре или тексте. В сети радиус ограничен 10 тайлами.").left().top().wrap().width(660f).padBottom(4f).row();
        toggle(t, Ico.of("star") + " Магнит монет (F7)", "F7 или этот переключатель: автоподбор монет рядом", s -> s.coinMagnet, (s, v) -> {
            s.coinMagnet = v;
            if(!v) CoinMagnet.resetMagnet();
        });
        slider(t, "   " + Ico.of("resize") + " Радиус магнита", 1f, 150f, 1f, s -> s.coinRadius, v -> s().coinRadius = v,
            v -> (int)(float)v + " тайлов" + (MKUKICSettings.s().coinServerLimit && net != null && net.client() && v > 10f ? " (в сети ≤10)" : ""));
        toggle(t, "   " + Ico.of("lock") + " Честный лимит в сети (≤10)", "Честный лимит 10 тайлов при игре на сервере", s -> s.coinServerLimit, (s, v) -> s.coinServerLimit = v);
        toggle(t, "   " + Ico.of("star") + " Дорогие монеты первыми (≥5000)", "Магнит приоритизирует монеты высокой ценности", s -> s.coinPreferHighValue, (s, v) -> s.coinPreferHighValue = v);
        toggle(t, Ico.of("map") + " Автопилот до монет", "Юнит сам летит/едет к монете; наземные — сегментный A* в объезд стен и воды.\nЛюбое ваше движение или магнит важнее автопилота.", s -> s.coinPilot, (s, v) -> {
            s.coinPilot = v;
            CoinPilot.reset();
        });
        slider(t, "   " + Ico.of("resize") + " Радиус автопилота", 8f, 1280f, 8f, s -> s.coinPilotRadius, v -> s().coinPilotRadius = v,
            v -> (int)(float)v + " тайлов" + ((int)(float)v >= 1280 ? " (максимум)" : ""));
        t.add("[darkgray]Маршрут строится порциями по 200 тайлов к цели: дальний A* не тормозит кадр, а цель не \n" +
            "перескакивает каждые полсекунды. Если монета недостижима — автопилот едет ближе всех и ищет дальше.").left().wrap().width(660f).padTop(2f).padBottom(4f).row();
        // v1.20: выбор юнита для пересадки
        t.table(c -> {
            c.left();
            c.add("   Сортировка выбора юнитов: ").left().padRight(6f);
            c.button("", MKUKICSkin.actBtn, () -> {
                s().pilotSort = (s().pilotSort + 1) % 3;
                MKUKICSettings.save();
            }).minWidth(250f).height(32f).update(b -> b.setText(new String[]{"По времени прибытия", "Ближайший к монете", "Самый быстрый рядом"}[Mathf.clamp(s().pilotSort, 0, 2)]));
        }).left().padTop(2f).row();
        t.add("[#7e8798]Время прибытия = расстояние / скорость. «Самый быстрый» смотрит юнитов в 40 тайлах от монеты.").left().top().padLeft(42f).wrap().width(640f).padBottom(4f).row();
        toggle(t, "   " + Ico.of("up") + " Только воздушные юниты", "Морские тоже считаются, если на сервере они летают (проверяется реальный полёт). Выкл — берутся и наземные", s -> s.pilotAirOnly, (s, v) -> s.pilotAirOnly = v);
        toggle(t, "   " + Ico.of("left") + " Избегать медленных юнитов", "Моно, поли, мега, квад, окт и всё медленнее порога — оценка ×4 (берутся только если других нет)", s -> s.pilotAvoidSlow, (s, v) -> s.pilotAvoidSlow = v);
        slider(t, "      " + Ico.of("left") + " Порог скорости", 0.5f, 4f, 0.1f, s -> s.pilotMinSpeed, v -> s().pilotMinSpeed = v, v -> String.format("%.1f ед./тик", v));
        toggle(t, "   " + Ico.of("defense") + " Уклонение в пути", "Встроено в автопилот: один вектор — прогресс к цели + уклон; наземные проверяют проходимость", s -> s.coinPilotDodge, (s, v) -> s.coinPilotDodge = v);
        slider(t, "      " + Ico.of("resize") + " Радиус уклонения", 40f, 400f, 10f, s -> s.coinPilotDodgeR, v -> s().coinPilotDodgeR = v, v -> (int)(float)v + " ед.");
        toggle(t, "   " + Ico.of("units") + " Автопилот при посадке (N)", "Клавиша N садится в ближайшего юнита; повторное нажатие — выход. С этой опцией автопилот до монет включается при посадке и выключается при выходе. Пока вы сели по кнопке, автопилот не пересаживает вас в других юнитов.", s -> NearestBoard.pilotOnBoard(), (s, v) -> NearestBoard.setPilotOnBoard(v));
        toggle(t, "   " + Ico.of("map") + " Патруль точек высадки", "Когда монет нет, автопилот летает от точки высадки к точке высадки у границы зоны волны (не заходя в неё). Выкл — юнит просто стоит.", s -> CoinPilot.patrolEnabled(), (s, v) -> CoinPilot.setPatrolEnabled(v));
        toggle(t, Ico.of("pencil") + " Линии и ценники до монет", "Золотые линии до монет, красные — дорогие; ценник рядом", s -> s.coinLines, (s, v) -> s.coinLines = v);
        t.label(() -> "[lightgray]Монет в пределах видимости: [accent]" + CoinMagnet.targetCount() + "[]")
            .left().padTop(2f).row();
        sep(t);
        OnixPanel.autoUnitSection(t);
    }

    private static void visualTab(Table t){
        header(t, Ico.of("eye") + " Визуал и разведка", "ESP рисуется локально и ничего не отправляет на сервер.");

        toggle(t, Ico.of("eye") + " ESP: подсветка врагов", "Подсветка врагов и стрелки по краю экрана", s -> s.esp, (s, v) -> s.esp = v);
        toggle(t, "   " + Ico.of("units") + " Тип юнита", null, s -> s.espNames, (s, v) -> s.espNames = v);
        toggle(t, "   " + Ico.of("add") + " Полоска HP", null, s -> s.espHealth, (s, v) -> s.espHealth = v);
        toggle(t, "   " + Ico.of("resize") + " Дистанция", null, s -> s.espDistance, (s, v) -> s.espDistance = v);
        toggle(t, "   " + Ico.of("pencil") + " Линии до врагов", null, s -> s.espLines, (s, v) -> s.espLines = v);
        toggle(t, "   " + Ico.of("resize") + " Круги дальности врагов", null, s -> s.espRangeCircles, (s, v) -> s.espRangeCircles = v);
        toggle(t, "   " + Ico.of("box") + " Вражеские постройки", null, s -> s.espBuildings, (s, v) -> s.espBuildings = v);
        toggle(t, "   " + Ico.of("map") + " Радар по краю экрана", null, s -> s.espRadar, (s, v) -> s.espRadar = v);
        toggle(t, "   " + Ico.of("eye") + " Учитывать туман войны", "Не рисовать то, что вы и так не видите", s -> s.espFog, (s, v) -> s.espFog = v);
        slider(t, "   " + Ico.of("resize") + " Радиус радара", 400f, 8000f, 100f, s -> s.espRadarRadius, v -> s().espRadarRadius = v, v -> (int)(float)v + " ед.");
        sep(t);
        toggle(t, Ico.of("players") + " Подсветка игроков", "Рамка и ник над игроками; работает отдельно от общего ESP", s -> s.espPlayers, (s, v) -> s.espPlayers = v);
        t.table(c -> {
            c.left();
            c.add("   Команда:").padRight(6f);
            c.button("все", MKUKICSkin.actBtn, () -> teamChooser(tm -> {
                s().espPlayerTeam = tm == null ? -1 : tm.id;
                MKUKICSettings.save();
                rebuild();
            })).minWidth(130f).update(b -> b.setText(s().espPlayerTeam < 0 ? "все" : mindustry.game.Team.all[s().espPlayerTeam].name));
            c.button("только выбранные...", MKUKICSkin.actBtn, () -> playerWhitelistDialog()).padLeft(8f).minWidth(170f).height(34f)
                .update(b -> b.setText(s().espWhitelist.isEmpty() ? "только выбранные..." : "выбрано: " + s().espWhitelist.split(",").length));
        }).left().row();
        toggle(t, Ico.of("move") + " Следы движения врагов", "Линии-следы недавнего движения врагов со стрелкой направления", s -> s.espTrails, (s, v) -> s.espTrails = v);
        slider(t, "   " + Ico.of("resize") + " Длина следа", 10f, 200f, 5f, s -> s.espTrailLen, v -> s().espTrailLen = (int)(float)v, v -> (int)(float)v + " точек");
        toggle(t, Ico.of("pencil") + " Линия до цели автоприцела", null, s -> s.showTargetLine, (s, v) -> s.showTargetLine = v);
        toggle(t, Ico.of("info") + " Строка статуса (HUD)", "Активные фичи, FPS, волна, координаты", s -> s.infoHud, (s, v) -> s.infoHud = v);
        toggle(t, Ico.of("menu") + " Кнопка меню на экране", null, s -> s.menuButton, (s, v) -> s.menuButton = v);
        toggle(t, Ico.of("chat") + " Всплывающие уведомления", null, s -> s.toasts, (s, v) -> s.toasts = v);

        // ==================================================================
        // v1.17: перенесено из QoL-вкладки — всё, что РИСУЕТСЯ на экране
        // ==================================================================

        sep(t);
        sub(t, Ico.of("eye") + " Индикаторы в мире");
        toggle(t, Ico.of("add") + " HP и щиты над юнитами", "Полоски здоровья и щитов в реальном времени", s -> s.qolHpBars, (s, v) -> s.qolHpBars = v);
        toggle(t, "   " + Ico.of("units") + " Над всеми юнитами", "Иначе — только над врагами", s -> s.qolHpBarsAll, (s, v) -> s.qolHpBarsAll = v);
        toggle(t, "   " + Ico.of("defense") + " Счётчик стеков щита", "Когда щитов больше макс. HP — показывать «xN»", s -> s.qolShieldStacks, (s, v) -> s.qolShieldStacks = v);
        toggle(t, Ico.of("resize") + " Круги дальности", "Турель/юнит под курсором, выделенные юниты, свой юнит", s -> s.qolRanges, (s, v) -> s.qolRanges = v);
        toggle(t, Ico.of("wrench") + " Зоны сервисных блоков", "Ремонтники, овердрайвы, силовые проекторы — разными цветами", s -> s.qolServiceZones, (s, v) -> s.qolServiceZones = v);

        sep(t);
        sub(t, Ico.of("box") + " Просвет зданий");
        toggle(t, Ico.of("box") + " Просвет зданий (X-Ray)", "Показывает, чем набиты склады, заводы и турели: предметы, жидкости, боезапас, прогресс, нехватка питания (MI2 Building X-ray)", s -> s.xray, (s, v) -> s.xray = v);
        toggle(t, "   " + Ico.of("liquid") + " Цвет жидкости в трубах", "Квадрат цвета жидкости в центре труб и каналов (Color-the-ducts)", s -> s.xrayDucts, (s, v) -> s.xrayDucts = v);
        slider(t, "   " + Ico.of("resize") + " Размер цветного квадрата", 0.2f, 1f, 0.05f, s -> s.xrayDuctScale, v -> s().xrayDuctScale = v, v -> Strings.autoFixed(v, 2));
        toggle(t, "   " + Ico.of("power") + " Показывать питание", "Подпись, когда блок не запитан или сеть просела", s -> s.xrayPower, (s, v) -> s.xrayPower = v);
        toggle(t, "   " + Ico.of("players") + " Чужие команды тоже", null, s -> s.xrayAllTeams, (s, v) -> s.xrayAllTeams = v);
        toggle(t, "   " + Ico.of("zoom") + " Подписи при дальнем зуме", "Медленнее, зато видно всю базу", s -> s.xrayZoomedOut, (s, v) -> s.xrayZoomedOut = v);
        winRow(t, "xray", "  " + Ico.of("box") + " Окно: Просвет зданий", "Те же переключатели в плавающем окне");

        sep(t);
        sub(t, Ico.of("map") + " Слои карты");
        toggle(t, Ico.of("move") + " Пути волн на карте", "Стрелки flow-поля: куда реально пойдут враги", s -> s.qolPathOverlay, (s, v) -> s.qolPathOverlay = v);
        toggle(t, "   " + Ico.of("warning") + " Узкие места пути", "Оранжевые круги там, где враги сжимаются в поток", s -> s.qolPathChokes, (s, v) -> s.qolPathChokes = v);
        winRow(t, "paths", "  " + Ico.of("move") + " Окно: Пути волн", "Чанковый анализ без фризов, пересчёт по кнопке");
        toggle(t, Ico.of("pencil") + " Маркеры в мире", null, s -> s.markerOverlay, (s, v) -> s.markerOverlay = v);
        winRow(t, "markers", "  " + Ico.of("pencil") + " Окно: Маркеры карты", "Личный слой заметок: точки и круги с подписями, 7 цветов, радиус 2–64 тайла");
        row(t, "  " + Ico.of("pencil") + " Поставить маркер у курсора", "Добавить маркер в точке курсора (клавиша mkukic_marker_add — назначь в Настройках)", () -> mkukic.client.qol.MapMarkers.addAtCursor());
        winRow(t, "cursors", "  " + Ico.of("players") + " Окно: Курсоры игроков", "Метки, следующие за курсором любого игрока: локальные (только тебе) и глобальные (видят все; хост/SP)");

        sep(t);
        sub(t, Ico.of("zoom") + " Камера");
        slider(t, Ico.of("zoom") + " Расширенный зум", 1f, 8f, 0.25f, s -> s.zoomMult,
            v -> { s().zoomMult = v; mkukic.client.qol.ViewControl.applyZoom(); },
            v -> v <= 1.01f ? "ваниль" : "x" + Strings.autoFixed(v, 1));
        toggle(t, Ico.of("move") + " Прокрутка краем экрана", "Мышь у края — камера едет туда (MI2 Edge Panning, только десктоп)", s -> s.edgePan, (s, v) -> s.edgePan = v);
        slider(t, "   " + Ico.of("resize") + " Ширина зоны у края", 4f, 60f, 2f, s -> s.edgePanMargin, v -> s().edgePanMargin = v, v -> (int)(float)v + " px");
        slider(t, "   " + Ico.of("up") + " Скорость прокрутки", 0.25f, 4f, 0.25f, s -> s.edgePanSpeed, v -> s().edgePanSpeed = v, v -> "x" + Strings.autoFixed(v, 2));
        toggle(t, Ico.of("eye") + " Операторская камера (F1)", "Слежение за игроками и ядрами: F1 — следить, [ и ] — листать игроков (Caster-UI)", s -> s.spectateFollow, (s, v) -> s.spectateFollow = v);
        toggle(t, "   " + Ico.of("warning") + " Тревога потери ядра", "Тост + координаты, когда ядро твоей команды уничтожено", s -> s.coreLostAlert, (s, v) -> s.coreLostAlert = v);
        toggle(t, "   " + Ico.of("warning") + " Вспышка экрана", null, s -> s.coreLostFlash, (s, v) -> s.coreLostFlash = v);
        toggle(t, "   " + Ico.of("music") + " Звук", null, s -> s.coreLostSound, (s, v) -> s.coreLostSound = v);
        toggle(t, "   " + Ico.of("home") + " Прыжок камеры к ядру", null, s -> s.coreLostSnap, (s, v) -> s.coreLostSnap = v);
        winRow(t, "spectate", "  " + Ico.of("eye") + " Окно: Операторская камера", "Список игроков и ядер всех активных команд: клик — камера следует за целью");
        winRow(t, "view", "  " + Ico.of("eye") + " Окно: Камера, время, FPS", "Зум, панорама, скорость симуляции и режим производительности в одном окне");

        sep(t);
        sub(t, Ico.of("refresh") + " Время и производительность");
        slider(t, Ico.of("refresh") + " Скорость симуляции (хост/SP)", 0.1f, 4f, 0.1f, s -> s.timeScale,
            v -> { s().timeScale = v; mkukic.client.qol.ViewControl.applyTime(); },
            v -> Strings.autoFixed(v, 1) + "x");
        note(t, "Слоу-мо/ускорение доступно в одиночной игре и на своём сервере — на выделенном сервере скорость задаёт сервер.");
        toggle(t, Ico.of("refresh") + " Сглаживание лаг-скачков", "Ровная картинка при просадках FPS (MI2 Time Scaler)", s -> s.qolSmoothDelta, (s, v) -> s.qolSmoothDelta = v);
        toggle(t, Ico.of("settings") + " Режим производительности", "Отключает выбранные проходы рендера — больше FPS", s -> s.perfMaster, (s, v) -> {
            s.perfMaster = v;
            mkukic.client.qol.ViewControl.applyPerf();
        });
        toggle(t, "   " + Ico.of("waves") + " Без погоды", null, s -> s.perfWeather, (s, v) -> { s.perfWeather = v; mkukic.client.qol.ViewControl.applyPerf(); });
        toggle(t, "   " + Ico.of("effect") + " Без частиц и эффектов", null, s -> s.perfEffects, (s, v) -> { s.perfEffects = v; mkukic.client.qol.ViewControl.applyPerf(); });
        toggle(t, "   " + Ico.of("eye") + " Без освещения", null, s -> s.perfLight, (s, v) -> { s.perfLight = v; mkukic.client.qol.ViewControl.applyPerf(); });
        toggle(t, "   " + Ico.of("defense") + " Без анимации щитов", null, s -> s.perfShields, (s, v) -> { s.perfShields = v; mkukic.client.qol.ViewControl.applyPerf(); });
        toggle(t, "   " + Ico.of("liquid") + " Без анимации воды", null, s -> s.perfWater, (s, v) -> { s.perfWater = v; mkukic.client.qol.ViewControl.applyPerf(); });
        toggle(t, "   " + Ico.of("players") + " Без пингов игроков", null, s -> s.perfPings, (s, v) -> { s.perfPings = v; mkukic.client.qol.ViewControl.applyPerf(); });
        toggle(t, "   " + Ico.of("hammer") + " Без чужих планов стройки", null, s -> s.perfOtherPlans, (s, v) -> { s.perfOtherPlans = v; mkukic.client.qol.ViewControl.applyPerf(); });
        toggle(t, "   " + Ico.of("logic") + " Без экранов логики", null, s -> s.perfDisplays, (s, v) -> { s.perfDisplays = v; mkukic.client.qol.ViewControl.applyPerf(); });
        sep(t);
        OnixPanel.animsSection(t);
    }

    private static void utilTab(Table t){
        header(t, Ico.of("wrench") + " Инструменты", "Локальные действия. На публичном сервере без мода спаун юнитов и выдача ресурсов невозможны — для этого мод должен стоять на сервере.");

        sub(t, Ico.of("units") + " Создание юнитов");
        t.table(c -> {
            c.left();
            c.add("Тип:").padRight(6f);
            c.button("выбрать...", MKUKICSkin.actBtn, () -> unitChooser()).minWidth(150f).height(34f).update(b -> b.setText(spawnType == null ? "-" : spawnType.localizedName));
            c.add("Кол-во:").padLeft(16f).padRight(6f);
            c.field(String.valueOf(spawnCount), MKUKICSkin.field, txt -> {
                String clean = txt.replace(",", "");
                if(Strings.canParseInt(clean)) spawnCount = Mathf.clamp(Strings.parseInt(clean), 1, 100);
            }).width(70f);
            c.button("команда", MKUKICSkin.actBtn, () -> teamChooser(tm -> {
                spawnTeam = tm;
                rebuild();
            })).minWidth(140f).height(34f).padLeft(10f)
                .update(b -> b.setText("команда: " + (spawnTeam == null ? "своя" : spawnTeam.name)));
        }).left().row();

        t.table(c -> {
            c.left();
            action(c, Ico.of("add") + " Создать здесь", () -> doSpawn(false));
            action(c, Ico.of("home") + " Создать у ядра", () -> doSpawn(true));
            action(c, Ico.of("units") + " Сесть в созданного юнита", () -> doBecome());
        }).left().row();

        sep(t);
        sub(t, Ico.of("box") + " Выдача ресурсов в ядро");
        t.table(c -> {
            c.left();
            c.add("Ресурс:").padRight(6f);
            c.button("выбрать...", MKUKICSkin.actBtn, () -> itemChooser()).minWidth(140f).height(34f).update(b -> b.setText(spawnItem == null ? "-" : spawnItem.localizedName));
            c.add("Кол-во:").padLeft(16f).padRight(6f);
            c.field(String.valueOf(spawnItemAmount), MKUKICSkin.field, txt -> {
                String clean = txt.replace(",", "");
                if(Strings.canParseInt(clean)) spawnItemAmount = Mathf.clamp(Strings.parseInt(clean), 1, 1000000);
            }).width(110f);
            c.button(Ico.of("box") + " Выдать", MKUKICSkin.actBtn, () -> doGiveItems()).padLeft(12f).minWidth(110f).height(36f);
        }).left().row();

        sep(t);
        sub(t, Ico.of("terminal") + " Свои команды: плавающее окно");
        t.add("[darkgray]Соберите список команд и жмите их в один клик в плавающем окне на экране. Окно перетаскивается за заголовок.").left().top().wrap().width(660f).padBottom(4f).row();
        toggle(t, Ico.of("terminal") + " Показывать окно команд", null, s -> s.cmdBarVisible, (s, v) -> s.cmdBarVisible = v);
        toggle(t, "   " + Ico.of("list") + " Вертикальный список", null, s -> s.cmdBarVertical, (s, v) -> {
            s.cmdBarVertical = v;
            OverlayUI.rebuildCmdBar();
        });
        slider(t, "   " + Ico.of("eye") + " Прозрачность окна", 0.3f, 1f, 0.05f, s -> s.cmdBarOpacity, v -> s().cmdBarOpacity = v, v -> (int)((float)v * 100) + "%");
        t.table(list -> {
            list.left().top();
            Seq<String> en = s().cmdEntries;
            for(int i = 0; i < en.size; i++){
                final int idx = i;
                list.table(r -> {
                    r.left();
                    r.field(labelOf(en.get(idx)), MKUKICSkin.field, txt -> en.set(idx, txt + "|" + cmdOf(en.get(idx)))).width(150f).height(32f);
                    r.field(cmdOf(en.get(idx)), MKUKICSkin.field, txt -> en.set(idx, labelOf(en.get(idx)) + "|" + txt)).width(280f).height(32f).padLeft(4f);
                    r.button("x", MKUKICSkin.actBtn, () -> {
                        en.remove(idx);
                        MKUKICSettings.save();
                        OverlayUI.rebuildCmdBar();
                        rebuild();
                    }).size(28f).padLeft(4f);
                }).left().pad(2f).row();
            }
        }).left().row();
        t.table(c -> {
            c.left();
            c.button(Ico.of("add") + " Команда", MKUKICSkin.actBtn, () -> {
                s().cmdEntries.add("команда|/stats");
                MKUKICSettings.save();
                OverlayUI.rebuildCmdBar();
                rebuild();
            }).minWidth(120f).height(36f);
            c.button(Ico.of("ok") + " Применить к окну", MKUKICSkin.actBtn, OverlayUI::rebuildCmdBar).padLeft(8f).minWidth(150f).height(36f);
        }).left().padTop(4f).row();

        sep(t);
        sub(t, Ico.of("players") + " Игрок: быстрые действия");
        t.table(c -> {
            c.left();
            action(c, Ico.of("add") + " Вылечить себя", () -> {
                if(!dead()){
                    player.unit().heal(player.unit().maxHealth());
                    MKUKICMod.toast("HP восстановлено");
                }
            });
            action(c, Ico.of("defense") + " Восстановить щит", () -> {
                if(!dead()){
                    player.unit().shield(player.unit().maxHealth() * 0.5f);
                    MKUKICMod.toast("Щит восстановлен");
                }
            });
            action(c, Ico.of("move") + " Телепорт к курсору", () -> {
                if(!dead()){
                    Vec2 p = Core.input.mouseWorld();
                    player.unit().set(p.x, p.y);
                    MKUKICMod.toast("Телепорт");
                }
            });
            action(c, Ico.of("home") + " Телепорт к ядру", () -> {
                if(!dead()){
                    CoreBuild core = player.bestCore();
                    if(core != null) player.unit().set(core.x, core.y);
                }
            });
            action(c, Ico.of("eraser") + " Очистить инвентарь", () -> { if(!dead()) player.unit().clearItem(); });
            action(c, Ico.of("box") + " Заполнить инвентарь", () -> {
                if(!dead()){
                    Unit u = player.unit();
                    Item it = spawnItem != null ? spawnItem : Items.copper;
                    u.stack().set(it, u.itemCapacity());
                }
            });
        }).left().row();

        sep(t);
        sub(t, Ico.of("map") + " Мир: хост, сервер с модом, админ");
        t.table(c -> {
            c.left();
            action(c, Ico.of("right") + " Пропустить волну", () -> {
                if(player.admin || !net.active()){
                    Call.adminRequest(player, AdminAction.wave, null);
                }else{
                    MKUKICMod.toast("[scarlet]Нужны права админа");
                }
            });
            action(c, Ico.of("star") + " Бесконечные ресурсы (правила)", () -> {
                if(!net.client()){
                    state.rules.infiniteResources = !state.rules.infiniteResources;
                    MKUKICMod.toast("infiniteResources = " + state.rules.infiniteResources);
                }else{
                    MKUKICMod.toast("[scarlet]Только для хоста");
                }
            });
        }).left().row();

        // ==================================================================
        // v1.17: перенесено из QoL-вкладки — инструменты стройки и обслуживания базы
        // ==================================================================
        sep(t);
        sub(t, Ico.of("hammer") + " Строительство");
        row(t, "  " + Ico.of("book") + " Колесо строительства (F2)",
            "Радиальное меню блоков: избранное ★ и недавние; ПКМ/клик мимо — закрыть",
            () -> mkukic.client.qol.BuildWheel.toggle());
        row(t, "  " + Ico.of("chat") + " Колесо быстрых сообщений (V)",
            "Нажми клавишу — у курсора откроется кольцо заготовок, клик по сообщению отправляет его в чат; ПКМ/клик мимо — закрыть",
            () -> mkukic.client.qol.QuickMsgWheel.toggle());
        winRow(t, "quickmsg", "  " + Ico.of("pencil") + " Окно: Редактор быстрых сообщений",
            "Редактор списка: подпись на кнопке, текст/команда, порядок и удаление (до 10 штук). Клавишу можно сменить: Настройки → Управление → mkukic");
        row(t, "  " + Ico.of("units") + " Угон юнитов по кругу (K)",
            "Цикл /team: команда врагов → сесть в ближайшего юнита → своя команда → выйти. Режимы: один юнит / все / только сильные",
            () -> mkukic.client.TeamSwap.toggle());
        winRow(t, "teamswap", "  " + Ico.of("units") + " Окно: Угон юнитов",
            "Кнопки режимов, команды /team, пауза между шагами, порог «сильных» по HP и статус цикла");
        winRow(t, "blocks", "  " + Ico.of("book") + " Окно: Каталог блоков",
            "Все размещаемые блоки с категориями, поиском и ★-избранным; выбор ставит блок как на ванильной панели");
        slider(t, Ico.of("resize") + " Лимит размера схемы", 16f, 512f, 16f, s -> (float)s.schematicSize,
            v -> { s().schematicSize = (int)(float)v; mkukic.client.qol.ViewControl.applySchematicSize(); },
            v -> (int)(float)v <= 64 ? "ваниль (64)" : (int)(float)v + "x" + (int)(float)v);
        note(t, "До 512x512, как в Scheme-Size. Сервер без мода может отклонять установку очень больших схем.");
        slider(t, Ico.of("resize") + " Лимит блоков в апгрейде", 20f, 2000f, 20f, s -> (float)s.smartUpgradeMax, v -> s().smartUpgradeMax = (int)(float)v, v -> String.valueOf((int)(float)v));
        t.table(c -> {
            c.left();
            action(c, Ico.of("up") + " Улучшить цепочку под курсором", mkukic.client.qol.SmartUpgrade::doUpgradePublic);
        }).left().row();
        note(t, "Курсор на цепочку конвейеров/труб/буров → вся связная цепочка того же типа заменяется на следующий уровень "
            + "через обычные планы стройки (работает по сети). Семейства: конвейеры, трубы, мосты→фазовые, буры, payload-конвейеры, каналы.");
        winRow(t, "upgrade", "  " + Ico.of("distribution") + " Окно: Апгрейд транспорта", "Просмотр цепочки до замены и настройка лимита");
        winRow(t, "prune", "  " + Ico.of("eraser") + " Окно: Умный снос",
            "Снос всей связной цепочки того же типа (лимит 10–2000, «Посчитать» до сноса), отмена всех планов лома, полная очистка карты от обломков (хост/SP)");
        winRow(t, "quick", "  " + Ico.of("hammer") + " Окно: Быстрые действия стройки",
            "Очистить/мгновенно достроить свои планы стройки, снести обломки 61×61 у курсора (хост), тумблеры рендера");

        sep(t);
        sub(t, Ico.of("production") + " Добыча и логика");
        toggle(t, Ico.of("production") + " Теплокарта рудности для буров", "Тепловая карта рудности под пятку выбранного бура", s -> s.qolDrillOverlay, (s, v) -> s.qolDrillOverlay = v);
        winRow(t, "drill", "  " + Ico.of("production") + " Окно: Планировщик буров",
            "Топ-24 лучших места с прыжком камеры, чанковый анализ рудности (мех/пневмо/лазер/бласт/эрликс)");
        winRow(t, "logic", "  " + Ico.of("save") + " Окно: Бэкапы логики (mlog)",
            "Снимки кода редактора логики, загрузка/удаление, поиск и замена со счётчиком вхождений, копирование в буфер и из буфера");
        winRow(t, "music", "  " + Ico.of("music") + " Окно: Музыкальный плеер",
            "Свои треки из config/mkukic-client/music: транспорт, плейлист, позиция/длительность, зацикливание, громкость");

        // ---- v1.19 (порт Onix Lite): Авто-Филл — инструмент базы ----
        sep(t);
        OnixPanel.autoFillSection(t);
    }

    // ============================== ИНФОРМАЦИЯ ==============================

    /**
     * Раздел «Информация» (v1.17).
     *
     * Сюда переехало всё, что ПОКАЗЫВАЕТ ДАННЫЕ, из расформированной вкладки «QoL»:
     * сводки о базе и волнах, энергосети, поток ресурсов и график производства,
     * тревоги (атаки на базу, проблемы производства, геозона), история чата,
     * инспектор объектов и справочники (рецепты, поиск по карте, символы).
     *
     * Оверлеи и камера — во вкладке «Визуал», инструменты стройки — в «Утилитах»,
     * формации — в «ИИ юнитов», автопилот до монет — в «ИИ игрока».
     */
    private static void infoTab(Table t){
        header(t, Ico.of("info") + " Информация и сводки",
            "Окна с данными о базе, волнах, энергосетях, ресурсах и событиях. Каждое окно перетаскивается "
            + "за заголовок, сворачивается «–», позиция запоминается; F4 — общий хаб всех окон. "
            + "Всё клиентское: только чтение состояния, на сервер ничего не отправляется.");

        // ---------- сводки базы ----------
        sub(t, Ico.of("chartBar") + " Сводки базы");
        winRow(t, "core", "  " + Ico.of("chartBar") + " Окно: Ядро и ресурсы",
            "Линейный график изменения до 4 ресурсов за 60 секунд, скорость +ед/мин, полный инвентарь ядра, счётчики юнитов (MI2 Core Info)");
        winRow(t, "power", "  " + Ico.of("power") + " Окно: Энергосети",
            "Каждая сеть отдельно: производство и потребление ед/с, заряд батарей, удовлетворённость; клик «→» — прыжок к сети (MI2 Power Analysis)");
        winRow(t, "waves", "  " + Ico.of("waves") + " Окно: Монитор волн",
            "HP/щиты/число врагов текущей волны, прогноз 5 следующих волн по SpawnGroup, кнопка пропуска волны (админ/песочница)");
        winRow(t, "rules", "  " + Ico.of("list") + " Окно: Правила карты",
            "Все ~120 правил текущей карты в одной таблице с поиском; булевы правила переключаются кликом (хост/SP)");

        // ---------- экономика ----------
        sep(t);
        sub(t, Ico.of("chartBar") + " Экономика и производство");
        toggle(t, Ico.of("distribution") + " Плашка потока ресурсов", "Плашка на экране: по каждому предмету — количество и приход/расход в секунду (resource-flow)", s -> s.coreFlow, (s, v) -> s.coreFlow = v);
        toggle(t, "   " + Ico.of("chartBar") + " Показывать скорость", null, s -> s.coreFlowRates, (s, v) -> s.coreFlowRates = v);
        toggle(t, "   " + Ico.of("move") + " Только изменяющиеся", "Прятать предметы, которые не меняются", s -> s.coreFlowOnlyMoving, (s, v) -> s.coreFlowOnlyMoving = v);
        slider(t, "   " + Ico.of("list") + " Предметов в строке", 1f, 8f, 1f, s -> (float)s.coreFlowColumns, v -> s().coreFlowColumns = (int)(float)v, v -> String.valueOf((int)(float)v));
        row(t, "  " + Ico.of("move") + " Передвинуть плашку потока", "Потяни плашку мышью — позиция сохранится", () -> mkukic.client.qol.CoreFlow.startDrag());
        winRow(t, "flow", "  " + Ico.of("distribution") + " Окно: Поток ресурсов", "Та же сводка текстом + настройки плашки");
        toggle(t, Ico.of("chartBar") + " График производства", "Факторио-график прихода/расхода по предметам, окна 60с / 5м / 10м (production-graph)", s -> s.prodGraph, (s, v) -> s.prodGraph = v);
        winRow(t, "prodgraph", "  " + Ico.of("chartBar") + " Окно: График производства", "Выбор до 5 предметов, средние значения +производство / −потребление");

        // ---------- тревоги ----------
        sep(t);
        sub(t, Ico.of("warning") + " Тревоги и оповещения");
        toggle(t, Ico.of("warning") + " Тревога об атаках на базу", "Факторио-оповещения: метки, стрелки у края экрана, звук и журнал, когда твои здания бьют или ломают (m3rcur1e/attack-alert)", s -> s.attackAlert, (s, v) -> s.attackAlert = v);
        toggle(t, "   " + Ico.of("defense") + " Тревожить из-за стен", "Иначе стены/двери игнорируются — их бьют постоянно", s -> s.attackAlertWalls, (s, v) -> s.attackAlertWalls = v);
        toggle(t, "   " + Ico.of("eye") + " Учитывать вне экрана", "События за пределами поля зрения тоже попадают в журнал", s -> s.attackAlertOffscreen, (s, v) -> s.attackAlertOffscreen = v);
        toggle(t, "   " + Ico.of("music") + " Звук", null, s -> s.attackAlertSound, (s, v) -> s.attackAlertSound = v);
        toggle(t, "   " + Ico.of("chat") + " Всплывающее сообщение", null, s -> s.attackAlertToast, (s, v) -> s.attackAlertToast = v);
        toggle(t, "   " + Ico.of("right") + " Стрелки у края экрана", "Мини-индикаторы направления на свежие происшествия (MinerTools)", s -> s.attackAlertArrow, (s, v) -> s.attackAlertArrow = v);
        toggle(t, "   " + Ico.of("pencil") + " Подписи у меток", null, s -> s.attackAlertText, (s, v) -> s.attackAlertText = v);
        slider(t, "   " + Ico.of("refresh") + " Время жизни метки", 3f, 40f, 1f, s -> s.attackAlertLife, v -> s().attackAlertLife = v, v -> (int)(float)v + " с");
        slider(t, "   " + Ico.of("list") + " Максимум стрелок", 1f, 12f, 1f, s -> (float)s.attackAlertMaxArrows, v -> s().attackAlertMaxArrows = (int)(float)v, v -> String.valueOf((int)(float)v));
        row(t, "  " + Ico.of("zoom") + " Камера к последней атаке", "То же, что горячая клавиша mkukic_attack_next", () -> mkukic.client.qol.AttackAlert.jumpToLatest());
        winRow(t, "attack", "  " + Ico.of("warning") + " Окно: Журнал атак", "Журнал происшествий (клик — прыжок камерой) и счётчики команд: игроки/юниты/ядра/здания");

        toggle(t, Ico.of("warning") + " Тревоги производства", "Турели без БК, вставшие заводы, обесточенные блоки, забитые склады — списком и метками в мире (MinerTools TurretAlert)", s -> s.alerts, (s, v) -> s.alerts = v);
        toggle(t, "   " + Ico.of("map") + " Метки в мире", null, s -> s.alertsWorld, (s, v) -> s.alertsWorld = v);
        toggle(t, "   " + Ico.of("zoom") + " Сканировать всю карту", "Иначе только здания в кадре (дешевле)", s -> s.alertsWholeMap, (s, v) -> s.alertsWholeMap = v);
        toggle(t, "   " + Ico.of("power") + " Проверять питание", null, s -> s.alertsPower, (s, v) -> s.alertsPower = v);
        slider(t, "   " + Ico.of("box") + " Порог «склад забит»", 0.5f, 1f, 0.05f, s -> s.alertsFullRatio, v -> s().alertsFullRatio = v, v -> (int)((float)v * 100f) + "%");
        winRow(t, "alerts", "  " + Ico.of("warning") + " Окно: Тревоги производства", "Фильтры по классам проблем, прыжок камерой, счётчик тревог в HUD");

        toggle(t, Ico.of("warning") + " Геозона-сигнализация", "Круг-периметр: враг внутри → тост, звук, запись в журнал (Neon tripwire)", s -> s.tripwire, (s, v) -> s.tripwire = v);
        toggle(t, "   " + Ico.of("home") + " Центр — ядро", "Иначе — вокруг игрока", s -> s.tripwireAtCore, (s, v) -> s.tripwireAtCore = v);
        toggle(t, "   " + Ico.of("music") + " Звук тревоги", null, s -> s.tripwireSound, (s, v) -> s.tripwireSound = v);
        slider(t, "   " + Ico.of("resize") + " Радиус периметра", 100f, 4000f, 50f, s -> s.tripwireRadius, v -> s().tripwireRadius = v, v -> (int)((float)v / 8) + " тайлов");
        winRow(t, "tripwire", "  " + Ico.of("warning") + " Окно: Геозона", "Журнал пересечений периметра и настройки круга");

        // ---------- чат ----------
        sep(t);
        sub(t, Ico.of("chat") + " Чат");
        toggle(t, Ico.of("chat") + " Журнал чата", "Свой журнал чата до 500 строк: поиск, копирование, экспорт в файл (MinerTools ChatTable)", s -> s.chatLog, (s, v) -> s.chatLog = v);
        winRow(t, "chat", "  " + Ico.of("chat") + " Окно: Журнал чата", "Поиск по нику и тексту, фильтр команд, экспорт в config/mkukic-client/");

        // ---------- инспектор и справочники ----------
        sep(t);
        sub(t, Ico.of("info") + " Инспектор и справочники");
        toggle(t, Ico.of("info") + " Инспектор следит за курсором", "Иначе — закреплённая точка", s -> s.inspFollow, (s, v) -> s.inspFollow = v);
        toggle(t, "   " + Ico.of("info") + " Мини-сводка в мире", "Компактная панель рядом с объектом", s -> s.inspWorld, (s, v) -> s.inspWorld = v);
        winRow(t, "inspector", "  " + Ico.of("info") + " Окно: Инспектор",
            "Местность (пол/покрытие/руда), постройка (HP, предметы, жидкости, проток ед/с, энергосеть, конфиг, груз), юнит (HP/щит, кто управляет, статусы) и мой план стройки");
        winRow(t, "recipes", "  " + Ico.of("crafting") + " Окно: Справочник рецептов",
            "Справочник по контенту ВСЕХ установленных модов: что производит/потребляет каждая постройка, кто делает нужный предмет; поиск по именам");
        winRow(t, "mapsearch", "  " + Ico.of("zoom") + " Окно: Поиск по карте",
            "Глобальный поиск построек/руды/местности (чанковое сканирование без фризов), клик «→» — прыжок камерой или телепорт (админ/хост)");
        winRow(t, "icons", "  " + Ico.of("tags") + " Окно: Символы и иконки",
            "Каталог всех 750+ игровых глифов: поиск по имени или символу, клик копирует символ в буфер обмена");
        sep(t);
        OnixPanel.chatSection(t);
        sep(t);
        OnixPanel.consoleSection(t);
    }

    private static void settingsTab(Table t){
        header(t, Ico.of("settings") + " Настройки и сервис", "Конфиг хранится в configs/mkukic-client/settings.json");
        toggle(t, Ico.of("settings") + " Мод включён глобально", "Полный выключатель всех клиент-фич", s -> s.enabled, (s, v) -> {
            s.enabled = v;
            if(!v) CombatFeatures.restoreAll();
        });
        row(t, Ico.of("refresh") + " Сбросить позиции панелей", "Вернуть тулбар, окно команд и все QoL-окна на места по умолчанию", () -> {
            OverlayUI.resetPositions();
            mkukic.client.qol.QolWindows.resetPositions();
        });

        sep(t);
        sub(t, Ico.of("layers") + " Окна и оверлеи (QoL)");
        toggle(t, Ico.of("layers") + " Окна и оверлеи включены",
            "Мастер-выключатель всех плавающих окон и мировых оверлеев: «Визуал», «Информация», инструменты в «Утилитах» и формации в «ИИ юнитов»",
            s -> s.qolEnabled, (s, v) -> s.qolEnabled = v);
        row(t, "  " + Ico.of("layers") + " Открыть хаб окон (F4)",
            "Полный список плавающих окон: открытия/закрытия и сброс позиций — удобно, когда нужно всё сразу",
            () -> mkukic.client.qol.QolWindows.toggle("hub"));
        note(t, "Позиции окон сохраняются автоматически; «Сбросить позиции панелей» выше возвращает их на места по умолчанию.");

        sep(t);
        sub(t, Ico.of("settings") + " Горячие клавиши");
        t.add("[lightgray]" + MKUKICKeys.describe()).left().top().wrap().width(680f).pad(4f).row();
        row(t, Ico.of("settings") + " Настроить клавиши", "Категория mkukic — можно переназначить любую клавишу", () -> {
            if(ui != null && ui.controls != null) ui.controls.show();
        });

        sep(t);
        sub(t, Ico.of("host") + " Сервер");
        t.label(() -> "[lightgray]Мод на сервере: " + (AdminNet.serverHasMod()
            ? "[lime]да[lightgray] (v" + AdminNet.serverVersion + ", роль: [accent]" + AdminNet.roleName + "[])"
            : "[scarlet]нет[lightgray] — доступны только стандартные админ-действия"))
            .left().wrap().width(520f).pad(4f).row();
        row(t, Ico.of("refresh") + " Обновить статус сервера", null, AdminNet::queryServer);

        sep(t);
        t.table(c -> {
            c.left();
            action(c, Ico.of("save") + " Сохранить настройки", MKUKICSettings::save);
            action(c, Ico.of("refresh") + " Сбросить настройки", () -> ui.showConfirm("Сбросить все настройки MKUKIC?", () -> {
                MKUKICSettings.reset();
                CombatFeatures.restoreAll();
                rebuild();
            }));
            action(c, Ico.of("cancel") + " Выключить всё", MKUKICMenu::disableAll);
        }).left().row();

        sep(t);
        t.add("[darkgray]MKUKIC Client v" + MKUKICMod.version + "  |  Mindustry v8 build " + Version.build).left().pad(6f).row();
    }

    // ============================== ДЕЙСТВИЯ ==============================

    public static boolean dead(){
        return player == null || player.dead() || player.unit() == null;
    }

    private static void doSpawn(boolean atCore){
        if(spawnType == null) return;
        float x, y;
        if(atCore){
            CoreBuild core = player.bestCore();
            if(core == null){
                MKUKICMod.toast("[scarlet]Нет ядра");
                return;
            }
            x = core.x; y = core.y;
        }else if(!dead()){
            x = player.unit().x; y = player.unit().y;
        }else{
            Vec2 p = Core.input.mouseWorld();
            x = p.x; y = p.y;
        }

        Team team = spawnTeam != null ? spawnTeam : player.team();
        int n = 0;
        for(int i = 0; i < spawnCount; i++){
            if(!Units.canCreate(team, spawnType)){
                MKUKICMod.toast("[scarlet]Достигнут лимит юнитов");
                break;
            }
            Unit u = spawnType.create(team);
            u.set(x + Mathf.range(20f), y + Mathf.range(20f));
            u.rotation(Mathf.random(360f));
            u.add();
            Units.notifyUnitSpawn(u);
            n++;
        }
        if(n > 0) MKUKICMod.toast("Создано юнитов: " + n);
        else if(net.client()) MKUKICMod.toast("[scarlet]Сервер без мода не создаст юнитов");
    }

    private static void doBecome(){
        if(spawnType == null) return;
        Team team = spawnTeam != null ? spawnTeam : player.team();
        Vec2 p = Core.input.mouseWorld();
        float x = dead() ? p.x : player.unit().x;
        float y = dead() ? p.y : player.unit().y;

        Unit u = spawnType.create(team);
        u.set(x, y);
        u.add();
        Units.notifyUnitSpawn(u);
        Call.unitControl(player, u);
        MKUKICMod.toast("Вы управляете: " + spawnType.localizedName);
    }

    private static void doGiveItems(){
        if(spawnItem == null) return;
        CoreBuild core = player.bestCore();
        if(core == null){
            MKUKICMod.toast("[scarlet]Нет ядра");
            return;
        }
        if(net.client()){
            MKUKICMod.toast("[scarlet]На чужом сервере ресурсы выдаёт только сервер (нужен мод на сервере)");
            return;
        }
        core.items.set(spawnItem, Mathf.clamp(core.items.get(spawnItem) + spawnItemAmount, 0, core.getMaximumAccepted(spawnItem)));
        MKUKICMod.toast("+" + spawnItemAmount + " " + spawnItem.localizedName);
    }

    /** Диалог в фирменном стиле MKUKIC. */
    static BaseDialog styledDialog(String title){
        BaseDialog d = new BaseDialog(title);
        Dialog.DialogStyle old = d.getStyle();
        Dialog.DialogStyle ds = new Dialog.DialogStyle();
        if(old != null) ds.stageBackground = old.stageBackground;
        ds.background = MKUKICSkin.panel;
        ds.titleFont = Fonts.def;
        ds.titleFontColor = MKUKICSkin.accent;
        d.setStyle(ds);
        d.titleTable.setBackground(MKUKICSkin.head);
        d.addCloseButton();
        return d;
    }

    private static void unitChooser(){
        BaseDialog d = styledDialog("Выбор юнита");
        d.cont.pane(MKUKICSkin.scroll, p -> {
            p.top().left();
            p.defaults().pad(3f).size(168f, 46f);
            int i = 0;
            for(UnitType ut : content.units()){
                if(ut == null || ut.isHidden()) continue;
                p.button(b -> {
                    b.left();
                    if(ut.uiIcon != null) b.image(ut.uiIcon).size(30f).padRight(8f).left();
                    b.add(ut.localizedName).left();
                }, MKUKICSkin.actBtn, () -> {
                    spawnType = ut;
                    d.hide();
                    rebuild();
                });
                if(++i % 4 == 0) p.row();
            }
        }).grow();
        d.shown(() -> sizeToScreen(d, 0.8f, 0.75f));
        d.show();
    }

    private static void itemChooser(){
        BaseDialog d = styledDialog("Выбор ресурса");
        d.cont.pane(MKUKICSkin.scroll, p -> {
            p.top().left();
            p.defaults().pad(3f).size(170f, 46f);
            int i = 0;
            for(Item it : content.items()){
                p.button(b -> {
                    b.left();
                    if(it.uiIcon != null) b.image(it.uiIcon).size(28f).padRight(8f).left();
                    b.add(it.localizedName).left();
                }, MKUKICSkin.actBtn, () -> {
                    spawnItem = it;
                    d.hide();
                    rebuild();
                });
                if(++i % 3 == 0) p.row();
            }
        }).grow();
        d.show();
    }

    private static void teamChooser(arc.func.Cons<Team> cons){
        BaseDialog d = styledDialog("Выбор команды");
        d.cont.table(p -> {
            p.defaults().pad(3f).size(156f, 44f);
            p.button(b -> {
                b.left();
                b.add("[#8b94a5]своя").left();
            }, MKUKICSkin.toggleBtn, () -> {
                cons.get(null);
                d.hide();
            }).checked(b -> spawnTeam == null);
            int i = 1;
            for(Team team : Team.baseTeams){
                p.button(b -> {
                    b.left();
                    b.image(Tex.whiteui).color(team.color).size(16f).padRight(8f).left();
                    b.add(team.name).left();
                }, MKUKICSkin.toggleBtn, () -> {
                    cons.get(team);
                    d.hide();
                }).checked(b -> spawnTeam == team);
                if(i++ % 3 == 0) p.row();
            }
        });
        d.show();
    }

    /** Растягивает ячейку с cont диалога почти на весь экран (диалог сам центрируется). */
    private static void sizeToScreen(BaseDialog d){
        sizeToScreen(d, 0.94f, 0.88f);
    }

    private static void sizeToScreen(BaseDialog d, float wf, float hf){
        float w = Core.scene.getWidth() * wf, h = Core.scene.getHeight() * hf;
        for(arc.scene.ui.layout.Cell<?> c : d.getCells()){
            if(c.get() == d.cont) c.minSize(w, h);
        }
        d.invalidate();
        d.centerWindow();
    }

    // ========================= ДИАЛОГИ ИИ/ВИЗУАЛА =========================

    private static void gToggle(Table t, UnitGroups.Group g, String title, @Nullable String desc,
                                arc.func.Boolf<UnitGroups.Group> get, arc.func.Cons2<UnitGroups.Group, Boolean> set){
        Table row = new Table();
        row.left();
        CheckBox box = new CheckBox(pretty(title), MKUKICSkin.check);
        box.setChecked(get.get(g));
        box.changed(() -> {
            set.get(g, box.isChecked());
            UnitGroups.save();
        });
        box.getImageCell().size(20f).padRight(8f);
        row.add(box).left().minWidth(460f).pad(3f, 0f, 3f, 6f);
        if(desc != null) row.add("[#4a5568]" + Ico.of("info")).left().padLeft(2f);
        tip(box, desc);
        t.add(row).left().growX().row();
    }

    private static void groupTypeChooser(UnitGroups.Group g){
        BaseDialog d = styledDialog("Тип юнита в группу");
        d.cont.pane(MKUKICSkin.scroll, p -> {
            p.top().left();
            p.defaults().pad(3f).size(168f, 44f);
            int i = 0;
            for(UnitType ut : content.units()){
                if(ut == null || ut.isHidden()) continue;
                boolean has = g.types.contains(ut.name, false);
                p.button(b -> {
                    b.left();
                    if(ut.uiIcon != null) b.image(ut.uiIcon).size(28f).padRight(8f).left();
                    b.add(ut.localizedName).left();
                }, MKUKICSkin.toggleBtn, () -> {
                    if(!g.types.contains(ut.name, false)) g.types.add(ut.name);
                    UnitGroups.save();
                    d.hide();
                    rebuild();
                }).checked(b -> g.types.contains(ut.name, false));
                if(++i % 4 == 0) p.row();
            }
        }).grow();
        d.shown(() -> sizeToScreen(d, 0.8f, 0.75f));
        d.show();
    }

    /** Мульти-выбор игроков: отмечает ники в списке list (привязка групп, помощь ИИ игрока). */
    private static void playersMultiChooser(Seq<String> list, String title, Runnable save){
        BaseDialog d = styledDialog(title);
        d.cont.pane(MKUKICSkin.scroll, p -> {
            p.top().left();
            p.add("[#8b94a5]Отметьте одного или нескольких игроков. Пустой список = возле вас / только вы.").wrap().width(460f).padBottom(8f).row();
            for(Player pl : Groups.player){
                if(pl == player) continue;
                String nm = pl.name();
                p.table(c -> {
                    c.left();
                    CheckBox box = new CheckBox(nm + "  [#5f6b7d](" + pl.team().name + ")", MKUKICSkin.check);
                    box.setChecked(list.contains(nm, false));
                    box.changed(() -> {
                        if(box.isChecked()){
                            if(!list.contains(nm, false)) list.add(nm);
                        }else{
                            list.remove(nm);
                        }
                        save.run();
                    });
                    box.getImageCell().size(20f).padRight(8f);
                    c.add(box).left();
                }).left().growX().pad(4f).row();
            }
        }).grow();
        d.show();
    }

    private static void playerWhitelistDialog(){
        BaseDialog d = styledDialog("Показывать игроков");
        d.cont.pane(MKUKICSkin.scroll, p -> {
            p.top().left();
            for(Player pl : Groups.player){
                String nm = pl.name();
                p.table(c -> {
                    c.left();
                    CheckBox box = new CheckBox(nm, MKUKICSkin.check);
                    box.setChecked(whitelistHas(nm));
                    box.changed(() -> {
                        Seq<String> list = whitelistList();
                        if(box.isChecked()){
                            if(!list.contains(nm, false)) list.add(nm);
                        }else{
                            list.remove(nm);
                        }
                        s().espWhitelist = String.join(",", list);
                        MKUKICSettings.save();
                    });
                    box.getImageCell().size(20f).padRight(8f);
                    c.add(box).left();
                }).left().growX().pad(4f).row();
            }
            p.add("[#8b94a5]Пусто = показывать всех по фильтру команды.").wrap().width(420f).padTop(8f).row();
        }).grow();
        d.show();
    }

    private static Seq<String> whitelistList(){
        Seq<String> list = new Seq<>();
        if(!s().espWhitelist.isEmpty()) list.addAll(s().espWhitelist.split(","));
        return list;
    }

    private static boolean whitelistHas(String nm){
        return whitelistList().contains(nm, false);
    }

    private static String labelOf(String e){
        int i = e.indexOf('|');
        return i < 0 ? e : e.substring(0, i);
    }

    private static String cmdOf(String e){
        int i = e.indexOf('|');
        return i < 0 ? "" : e.substring(i + 1);
    }

    // ============================== ВИДЖЕТЫ ==============================

    public static MKUKICSettings s(){
        return MKUKICSettings.s();
    }

    // ---------------------------------------------------------------------
    // Правила оформления (все вкладки и OnixPanel строятся этими же методами):
    //  - ведущие пробелы в названии = вложенность (3 пробела = 1 уровень): опция получает отступ
    //    с направляющей линией и гаснет, пока родитель (последний переключатель уровня 0) выключен;
    //  - глиф игрового шрифта в начале названия подкрашивается в голубой;
    //  - пояснение (desc) не занимает место в списке: оно всплывает при наведении, рядом — значок «i».
    // ---------------------------------------------------------------------

    private static final Color guideColor = Color.valueOf("6fd6ff55");
    private static final float indentStep = 24f;

    /** Уровень вложенности по ведущим пробелам. */
    static int depthOf(String title){
        int n = 0;
        while(n < title.length() && title.charAt(n) == ' ') n++;
        return n / 3;
    }

    /** Название без отступа, глиф — голубым. */
    static String pretty(String title){
        String s = title.trim();
        if(!s.isEmpty() && Ico.isGlyph(s.charAt(0))){
            return "[#6fd6ff]" + s.charAt(0) + "[]" + s.substring(1);
        }
        return s;
    }

    /** Название без отступа и без цветовой разметки (для поиска и тостов). */
    public static String plain(String title){
        String s = title.trim();
        return s.replaceAll("\\[[^\\]]*\\]", "");
    }

    /** Всплывающая подсказка. Вешается на кликабельный элемент, чтобы наведение точно срабатывало. */
    static void tip(Element e, @Nullable String desc){
        if(e == null || desc == null || desc.isEmpty()) return;
        e.addListener(new Tooltip(tt -> {
            tt.background(MKUKICSkin.panel).margin(9f);
            tt.add("[#c2cad8]" + desc).left().wrap().width(440f);
        }));
    }

    private static void guide(Table row, int depth){
        if(depth <= 0) return;
        row.image(Tex.whiteui, guideColor).width(2f).growY().padLeft(10f + (depth - 1) * indentStep).padRight(12f);
    }

    private static void dim(Table row, @Nullable Boolf<MKUKICSettings> parent){
        if(parent == null) return;
        row.update(() -> row.color.a = parent.get(s()) ? 1f : 0.4f);
    }

    /** Подзаголовок секции (маленькая акцентная метка). */
    static void sub(Table t, String title){
        parentOn = null;
        t.table(h -> {
            h.left();
            h.image(Tex.whiteui, MKUKICSkin.accent2).size(2.5f, 15f).padRight(8f);
            h.add("[#6fd6ff]" + title.trim()).left();
            h.add().growX();
        }).left().growX().padTop(12f).padBottom(4f).row();
        t.image(MKUKICSkin.divSoft).height(1f).growX().padBottom(4f).row();
    }

    /** Заголовок раздела: плашка с акцентной полосой, названием и (необязательно) вводным текстом. */
    static void header(Table t, String title, String desc){
        parentOn = null;
        t.table(h -> {
            h.left();
            h.setBackground(MKUKICSkin.panelSoft);
            h.image(Tex.whiteui, MKUKICSkin.accent).width(4f).growY().padRight(12f);
            h.add("[#ffb454]" + title.trim()).left().padTop(9f).padBottom(9f);
            h.add().growX();
        }).left().growX().padTop(14f).padBottom(desc == null ? 6f : 2f).row();
        if(desc != null) t.add("[#8b94a5]" + desc).left().top().wrap().width(780f).pad(4f, 6f, 8f, 13f).row();
    }

    static void sep(Table t){
        parentOn = null;
        t.image(MKUKICSkin.divSoft).height(2f).growX().pad(13f, 6f, 13f, 6f).row();
    }

    static void toggle(Table t, String title, @Nullable String desc, Boolf<MKUKICSettings> get, Cons2<MKUKICSettings, Boolean> set){
        final int d = depthOf(title);
        final Boolf<MKUKICSettings> par = d > 0 ? parentOn : null;
        if(d == 0) parentOn = get;

        Table row = new Table();
        row.left();
        guide(row, d);
        CheckBox box = new CheckBox(pretty(title), MKUKICSkin.check);
        box.setChecked(get.get(s()));
        box.changed(() -> {
            set.get(s(), box.isChecked());
            MKUKICSettings.save();
        });
        box.getImageCell().size(20f).padRight(8f);
        row.add(box).left().minWidth(460f - d * indentStep).pad(3f, 0f, 3f, 6f);
        if(desc != null) row.add("[#4a5568]" + Ico.of("info")).left().padLeft(2f);
        tip(box, desc);
        dim(row, par);
        t.add(row).left().growX().row();
    }

    static void slider(Table t, String title, float min, float max, float step,
                               Floatf<MKUKICSettings> get, Floatc set, Func<Float, String> fmt){
        final int d = depthOf(title);
        final Boolf<MKUKICSettings> par = d > 0 ? parentOn : null;
        if(d == 0) parentOn = null;

        Table row = new Table();
        row.left();
        guide(row, d);
        Label name = new Label("[#c2cad8]" + pretty(title), new Label.LabelStyle(Fonts.def, MKUKICSkin.text));
        row.add(name).left().minWidth(290f - d * indentStep);
        Table badge = new Table(MKUKICSkin.chip);
        Label val = new Label("", new Label.LabelStyle(Fonts.def, MKUKICSkin.accent));
        badge.add(val).pad(1f, 9f, 2f, 9f);
        row.add(badge).right().minWidth(104f).padRight(10f);
        Slider sl = new Slider(min, max, step, false, MKUKICSkin.slider);
        sl.setValue(get.get(s()));
        sl.moved(v -> {
            set.get(v);
            MKUKICSettings.save();
        });
        row.add(sl).width(280f);
        val.setText(() -> fmt.get(get.get(s())));
        dim(row, par);
        t.add(row).left().growX().padTop(2f).padBottom(2f).row();
    }

    static void chooser(Table t, String title, String[] options, Intf<MKUKICSettings> get, Intc set){
        final int d = depthOf(title);
        final Boolf<MKUKICSettings> par = d > 0 ? parentOn : null;

        Table row = new Table();
        row.left();
        guide(row, d);
        row.add("[#c2cad8]" + pretty(title)).left().minWidth(290f - d * indentStep);
        for(int i = 0; i < options.length; i++){
            final int idx = i;
            row.button(options[i], MKUKICSkin.toggleBtn, () -> {
                set.get(idx);
                MKUKICSettings.save();
                rebuild();
            }).checked(bt -> get.get(s()) == idx).minWidth(92f).height(32f).pad(2f);
        }
        dim(row, par);
        t.add(row).left().growX().padTop(2f).padBottom(2f).row();
    }

    static void row(Table t, String title, @Nullable String desc, Runnable action){
        parentOn = null;
        Table r = new Table();
        r.left();
        Cell<TextButton> c = r.button(pretty(title), MKUKICSkin.actBtn, () -> {
            action.run();
            rebuild();
        }).left().minWidth(380f).height(36f).pad(2f);
        c.get().getLabel().setAlignment(Align.left);
        if(desc != null) r.add("[#4a5568]" + Ico.of("info")).left().padLeft(6f);
        tip(c.get(), desc);
        t.add(r).left().growX().row();
    }

    /**
     * Кнопка открытия/закрытия плавающего окна QoL.
     * Внешне как row(), но подсвечивается, если окно уже открыто.
     */
    static void winRow(Table t, String id, String title, @Nullable String desc){
        parentOn = null;
        Table r = new Table();
        r.left();
        Cell<TextButton> c = r.button(pretty(title), MKUKICSkin.toggleBtn, () -> {
            mkukic.client.qol.QolWindows.toggle(id);
            rebuild();
        }).checked(bt -> mkukic.client.qol.QolWindows.isOpen(id))
            .left().minWidth(380f).height(36f).pad(2f);
        c.get().getLabel().setAlignment(Align.left);
        if(desc != null) r.add("[#4a5568]" + Ico.of("info")).left().padLeft(6f);
        tip(c.get(), desc);
        t.add(r).left().growX().row();
    }

    /** Пояснение мелким серым текстом (для секций). */
    static void note(Table t, String text){
        parentOn = null;
        t.add("[#7e8798]" + text).left().top().padLeft(10f).wrap().width(780f).padBottom(4f).row();
    }

    static void action(Table c, String title, Runnable r){
        TextButton bt = c.button(pretty(title), MKUKICSkin.actBtn, r).minWidth(200f).height(36f).pad(2f).left().get();
        bt.getLabel().setAlignment(Align.left);
    }

}
