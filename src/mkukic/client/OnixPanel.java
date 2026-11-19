package mkukic.client;

import arc.*;
import arc.graphics.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.scene.Element;
import arc.scene.ui.Label;
import arc.struct.*;
import arc.util.*;
import mindustry.content.*;
import mindustry.gen.*;
import mindustry.type.*;
import mindustry.world.Block;
import mindustry.ui.*;
import mindustry.ui.dialogs.*;
import mkukic.*;
import mkukic.client.onix.*;

import static mindustry.Vars.*;
import mkukic.client.onix.Onix;

/**
 * Вкладка «Onix» и секции Onix-функций в остальных вкладках меню MKUKIC (v1.19).
 *
 * <p>Раскладка — «смешанная»: автоматизация живёт в «ИИ игрока»/«ИИ юнитов»/«Утилитах»,
 * чат и консоль — в «Информации», анимации — в «Визуале», а специфично-ониксовое
 * (детект клиентов, аккаунты, команды, апдейтер) собрано в отдельной вкладке «Onix».
 *
 * <p>Источник функций: Onix Lite 3.4.6 (GPL-3.0), см. шапку {@link Onix}.
 */
public class OnixPanel{
    private static String searchText = "";

    // ============================== ВКЛАДКА «ONIX» ==============================

    public static void build(Table t){
        MKUKICMenu.header(t, Ico.of("github") + " Onix: перенесённые функции",
            "Порт функций клиента [accent]Onix Lite 3.4.6[] (GPL-3.0, github.com/DiscoD3979/OnixLite).\n"
            + "Поведение восстановлено по открытым описаниям клиента (features/changelog) и по GPL-коду Foo's Client,\n"
            + "на котором Onix основан; реализация написана заново под архитектуру MKUKIC.\n"
            + "Автоматизация (Авто-Уни, Авто-Филл, бот) лежит в «ИИ игрока», «ИИ юнитов» и «Утилитах», чат — в «Информации».");

        MKUKICMenu.toggle(t, Ico.of("github") + " Onix-слой включён", "Мастер-выключатель всех перенесённых функций", s -> s.onixEnabled, (s, v) -> s.onixEnabled = v);
        MKUKICMenu.row(t, Ico.of("refresh") + " Сбросить состояние Onix", "Очистить очереди, детект, журнал — как после перезахода", Onix::reset);

        MKUKICMenu.sep(t);
        detectSection(t);
        MKUKICMenu.sep(t);
        accountsSection(t);
        MKUKICMenu.sep(t);
        consoleSection(t);
        MKUKICMenu.sep(t);
        commandsSection(t);
        MKUKICMenu.sep(t);
        updaterSection(t);
        MKUKICMenu.sep(t);
        miscSection(t);
    }

    // ------------------------------ ДЕТЕКТ КЛИЕНТОВ ------------------------------

    public static void detectSection(Table t){
        MKUKICMenu.header(t, Ico.of("zoom") + " Детектор клиентов: Onix / Lite / Foo's",
            "Onix: «сканирование только в коротком окне после захода на сервер и при заходе новых игроков»,\n"
            + "«определяются полная версия Onix, другие Onix Lite и Foo-клиенты», «в чат не уходит ни одного символа».\n"
            + "Значки рисуются [accent]только у тех, у кого клиент найден[] — над головой и в списке игроков (Tab).\n"
            + "[scarlet]Ограничение:[] Onix — форк игры и обменивается зашифрованными «мышиными маячками», из мода их\n"
            + "не принять. Поэтому семейство определяется так: (1) тег ника — Onix сам пишет в ник знак シ,\n"
            + "у Lite слева буква L: <Lシ>, у полной версии <シ>; (2) поведение — циклы авто-филла (фирменная\n"
            + "функция Onix), рывки позиции, идеально прямой ход, длинная очередь планов, непрерывная копка;\n"
            + "(3) ручная пометка ниже. Если данных мало — значок «C» (клиент без семейства) или «?» (догадка).");

        MKUKICMenu.toggle(t, Ico.of("zoom") + " Сканировать игроков", null, s -> s.onixDetect, (s, v) -> s.onixDetect = v);
        MKUKICMenu.toggle(t, "   " + Ico.of("players") + " Значки над головой", "Только клиенты: MK / Onix / Lite / Foo / C", s -> s.onixDetectTags, (s, v) -> s.onixDetectTags = v);
        MKUKICMenu.toggle(t, "   " + Ico.of("list") + " Значки в списке игроков (Tab)", "Бейдж семейства слева от ника — как в Onix", s -> s.onixDetectTabIcons, (s, v) -> s.onixDetectTabIcons = v);
        MKUKICMenu.toggle(t, "   " + Ico.of("info") + " Знак «?» при догадке", "Семейство угадано по поведению, а не подтверждено тегом ника", s -> s.onixDetectConfidence, (s, v) -> s.onixDetectConfidence = v);
        MKUKICMenu.toggle(t, "   " + Ico.of("warning") + " Показывать подозрительных", "Выключено: значки только у подтверждённых клиентов", s -> s.onixDetectSuspicious, (s, v) -> s.onixDetectSuspicious = v);
        MKUKICMenu.toggle(t, "   " + Ico.of("warning") + " Оповещать о найденных клиентах", null, s -> s.onixDetectAlert, (s, v) -> s.onixDetectAlert = v);
        MKUKICMenu.toggle(t, "   " + Ico.of("refresh") + " Только сразу после захода", null, s -> s.onixDetectJoinWindow, (s, v) -> s.onixDetectJoinWindow = v);
        MKUKICMenu.slider(t, "      " + Ico.of("refresh") + " Длина окна сканирования", 5f, 120f, 5f, s -> s.onixDetectWindow, v -> MKUKICMenu.s().onixDetectWindow = v, v -> (int)(float)v + " с");
        MKUKICMenu.chooser(t, "   " + Ico.of("resize") + " Чувствительность", new String[]{"Мягко", "Нормально", "Строго"}, s -> s.onixDetectSensitivity, v -> MKUKICMenu.s().onixDetectSensitivity = v);
        MKUKICMenu.toggle(t, "   " + Ico.of("github") + " Помечать себя как MKUKIC", null, s -> s.onixDetectSelf, (s, v) -> s.onixDetectSelf = v);
        MKUKICMenu.row(t, Ico.of("refresh") + " Пересканировать сейчас", "Открыть окно сканирования для всех игроков в игре", () -> {
            for(Player p : Groups.player) OnixClientDetect.openWindow(p, true);
            MKUKICMod.toast("[accent]Onix[]: окно сканирования открыто заново");
            MKUKICMenu.rebuild();
        });

        t.table(l -> l.left().add("[lightgray]" + OnixClientDetect.legend())).left().top().padTop(4f).row();

        // ---- кто в игре: вердикт + ручная пометка семейства ----
        MKUKICMenu.sub(t, Ico.of("players") + " Кто в игре");
        t.table(list -> {
            list.left().top();
            list.defaults().left().top();
            int n = 0;
            for(Player p : Groups.player){
                if(p == null) continue;
                n++;
                OnixClientDetect.Verdict v = OnixClientDetect.get(p);
                String mark = v == null ? "[#8b94a5]нет данных[]" : v.describe();
                String icon = v == null ? "" : v.iconText();
                list.table(r -> {
                    r.left().top();
                    r.add((icon.isEmpty() ? "[#8b94a5]—[]" : "[#" + (v == null ? "8b94a5" : v.color().toString()) + "]" + icon + "[]")
                        + " [accent]" + p.name() + (p == player ? " [lightgray](вы)[]" : ""))
                        .left().minWidth(240f).top();
                    r.add("[lightgray]" + mark).left().wrap().width(420f).padLeft(6f).top();
                    r.table(b -> {
                        b.left();
                        b.defaults().height(28f).padLeft(3f);
                        markButton(b, p, "Onix", OnixClientDetect.FAM_ONIX);
                        markButton(b, p, "Lite", OnixClientDetect.FAM_ONIX_LITE);
                        markButton(b, p, "Foo", OnixClientDetect.FAM_FOO);
                        markButton(b, p, "C", OnixClientDetect.FAM_CLIENT);
                        markButton(b, p, "снять", OnixClientDetect.FAM_NONE);
                    }).left().padLeft(6f).top();
                }).left().growX().padBottom(3f).row();
            }
            if(n == 0) list.add("[gray]В игре никого нет (или вы в главном меню).").left().padTop(6f).row();
            else list.add("[#8b94a5]Ручная пометка перекрывает эвристику и живёт до конца сессии. "
                + "«снять» — вернуть автоматический вердикт.[]").left().wrap().width(680f).padTop(4f).row();
        }).left().top().growX().padTop(2f).row();
    }

    /** Кнопка ручной пометки семейства клиента. */
    private static void markButton(Table b, Player p, String title, int family){
        OnixClientDetect.Verdict v = OnixClientDetect.get(p);
        boolean on = v != null && v.manual && v.family == family;
        b.button((on ? "[accent]" : "") + title, MKUKICSkin.toggleBtn, () -> {
            OnixClientDetect.markFamily(p.id(), family);
            MKUKICSettings.save();
            MKUKICMenu.rebuild();
        }).checked(x -> on).minWidth(52f);
    }

    // ------------------------------ АККАУНТЫ ------------------------------

    public static void accountsSection(Table t){
        MKUKICMenu.header(t, Ico.of("admin") + " Аккаунты и обход бана",
            "Onix: «сохранение ника, UUID и USID под своим названием; применение кнопкой-замком или кликом;\n"
            + "звезда — активный аккаунт; подсветка гаснет после обхода бана».\n"
            + "UUID и USID подставляются в ConnectPacket при подключении, поэтому после смены личности\n"
            + "нужно переподключиться к серверу.");

        MKUKICMenu.toggle(t, Ico.of("admin") + " Раздел аккаунтов включён", null, s -> s.onixAccounts, (s, v) -> s.onixAccounts = v);
        t.label(() -> "[lightgray]Сейчас: ник [accent]" + OnixAccounts.currentName()
            + "[lightgray], uuid [accent]" + OnixAccounts.currentUuid()
            + "[lightgray], usid [accent]" + OnixAccounts.currentUsid())
            .left().top().wrap().width(680f).pad(4f).row();

        t.table(c -> {
            c.left();
            c.button(Ico.of("save") + " Сохранить текущую личность", MKUKICSkin.actBtn, () -> {
                BaseDialog d = new BaseDialog("Имя аккаунта");
                TextField f = new TextField("аккаунт " + (OnixAccounts.count() + 1));
                d.cont.add(f).width(280f).pad(10f);
                d.buttons.button("Отмена", MKUKICSkin.actBtn, d::hide).size(150f, 44f);
                d.buttons.button(Ico.of("save") + " Сохранить", MKUKICSkin.actBtn, () -> {
                    OnixAccounts.saveCurrent(f.getText());
                    d.hide();
                    MKUKICMenu.rebuild();
                }).size(170f, 44f);
                d.show();
            }).minWidth(230f).height(38f).pad(2f);
            c.button(Ico.of("admin") + " Обход бана (новые UUID и USID)", MKUKICSkin.actBtn, () ->
                ui.showConfirm("Сменить личность?\nUUID и USID будут сброшены — сервер увидит нового игрока.\nНужно переподключиться.", () -> {
                    String r = OnixAccounts.bypassBan(false);
                    MKUKICMod.toast("[accent]Onix[]: " + r);
                    MKUKICMenu.rebuild();
                })).minWidth(260f).height(38f).pad(2f);
            c.button(Ico.of("refresh") + " Сбросить USID серверов", MKUKICSkin.actBtn, () -> {
                int n = OnixAccounts.clearUsidKeys(null);
                MKUKICMod.toast("[accent]Onix[]: стёрто usid-ключей: " + n);
                MKUKICMenu.rebuild();
            }).minWidth(250f).height(38f).pad(2f);
        }).left().growX().row();

        Seq<OnixAccounts.Account> list = OnixAccounts.all();
        if(list.isEmpty()){
            MKUKICMenu.note(t, "Сохранённых личностей пока нет.");
        }else{
            for(OnixAccounts.Account a : list){
                boolean active = a.label.equals(OnixAccounts.activeLabel);
                t.table(c -> {
                    c.left();
                    c.add((active ? "[gold]" + (char)Iconc.star + " []" : "[lightgray]  []") + a.label).left().minWidth(150f);
                    c.add("[darkgray]" + a.name).left().minWidth(120f);
                    c.add("[#5f6b7d]uuid " + a.uuid.substring(0, Math.min(6, a.uuid.length())) + "…").left().minWidth(110f);
                    c.button((char)Iconc.lock + "", MKUKICSkin.actBtn, () -> {   // «кнопка-замок» — применить
                        OnixAccounts.apply(a);
                        MKUKICMenu.rebuild();
                    }).size(34f, 30f).pad(2f);
                    c.button("удалить", MKUKICSkin.actBtn, () -> {
                        OnixAccounts.remove(a.label);
                        MKUKICMenu.rebuild();
                    }).size(90f, 30f).pad(2f);
                }).left().growX().pad(1f).row();
            }
        }
    }

    // ------------------------------ КОНСОЛЬ ------------------------------

    public static void consoleSection(Table t){
        MKUKICMenu.header(t, Ico.of("terminal") + " Консоль Onix (F5)",
            "Onix: «вкладки Чат / Действия / Логи / Код на весь экран вместо ванильной мини-консоли;\n"
            + "история переживает перезапуск; выделение + Ctrl+C — копировать, без выделения — весь таб;\n"
            + "фильтры DEBUG/INFO/WARN/ERROR + поиск; чат как в игре с точными цветами ников».");

        MKUKICMenu.toggle(t, Ico.of("terminal") + " Консоль включена", null, s -> s.onixConsole, (s, v) -> s.onixConsole = v);
        MKUKICMenu.toggle(t, "   " + Ico.of("save") + " История между запусками", "Прошлый запуск показывается серым при старте", s -> s.onixConsolePersist, (s, v) -> s.onixConsolePersist = v);
        MKUKICMenu.row(t, Ico.of("terminal") + " Открыть консоль", "Клавишу можно переназначить: Настройки → Управление → mkukic (mkukic_onix_console)", OnixConsole::show);
        t.label(() -> "[lightgray]строк в журнале: [accent]" + OnixConsole.lines.size).left().padTop(2f).row();
    }

    // ------------------------------ КОМАНДЫ ------------------------------

    public static void commandsSection(Table t){
        MKUKICMenu.header(t, Ico.of("terminal") + " Команды клиента",
            "Onix: go/lookat/tp/here/cursor/unit/uc/count/builder/miner/voids/procfind/unloaders/blank/\n"
            + "replacemessage/gamejointext/gamewintext/gamelosetext/bannedcontent/mute/unmute/pathing.\n"
            + "Команды клиентские — работают и там, где серверные выключены.");

        MKUKICMenu.toggle(t, Ico.of("terminal") + " Команды включены", null, s -> s.onixCommands, (s, v) -> s.onixCommands = v);
        t.table(c -> {
            c.left();
            c.add("[#c2cad8]Префикс:").left().padRight(6f);
            c.field(MKUKICSettings.s().onixCmdPrefix, MKUKICSkin.field, txt -> {
                MKUKICMenu.s().onixCmdPrefix = txt.isEmpty() ? "/m" : txt;
                MKUKICSettings.save();
            }).width(120f);
            c.add("[#7e8798]  например: " + MKUKICSettings.s().onixCmdPrefix + "go 100 200").left();
        }).left().growX().pad(2f).row();
        MKUKICMenu.row(t, Ico.of("list") + " Палитра команд", "Окно со всеми командами: клик — выполнить, поле — со своими аргументами", OnixPanel::commandsDialog);
        MKUKICMenu.row(t, Ico.of("chat") + " Тексты захода и итога игры", "gamejointext / gamewintext / gamelosetext", OnixPanel::textsDialog);
        MKUKICMenu.row(t, Ico.of("pencil") + " Замены в тексте (%s %c %h)", "replacemessage и подстановки Onix", OnixPanel::replacerDialog);
        MKUKICMenu.toggle(t, Ico.of("pencil") + " Подстановки %s / %c / %h", "%s — ¯\\_(ツ)_/¯, %c — координаты курсора, %h — координаты юнита",
            s -> s.onixTextReplacer, (s, v) -> s.onixTextReplacer = v);
        MKUKICMenu.toggle(t, Ico.of("lock") + " Строгий режим (без читерских команд)", "Запретить телепорт и прочие «читерские» команды клиента", s -> s.onixStrictMode, (s, v) -> s.onixStrictMode = v);
    }

    // ------------------------------ АПДЕЙТЕР ------------------------------

    public static void updaterSection(Table t){
        MKUKICMenu.header(t, Ico.of("download") + " Обновления",
            "Onix: «клиент сам проверяет обновления и ставит новую версию (с откатом); скачивание в фоне —\n"
            + "игра не зависает; в окне видно переход версий, процент и оставшееся время; „Отменить“ удаляет недокачанный файл».");

        MKUKICMenu.toggle(t, Ico.of("download") + " Проверять обновления", "Релизы GitHub-репозитория MKUKIC", s -> s.onixUpdateCheck, (s, v) -> s.onixUpdateCheck = v);
        t.table(c -> {
            c.left();
            c.add("[#c2cad8]Репозиторий:").left().padRight(6f);
            c.field(MKUKICSettings.s().onixUpdateRepo, MKUKICSkin.field, txt -> {
                MKUKICMenu.s().onixUpdateRepo = txt.trim();
                MKUKICSettings.save();
            }).width(280f);
        }).left().growX().pad(2f).row();
        MKUKICMenu.toggle(t, "   " + Ico.of("download") + " Скачивать в фоне", null, s -> s.onixUpdateBackground, (s, v) -> s.onixUpdateBackground = v);
        MKUKICMenu.toggle(t, "   " + Ico.of("download") + " Фирменная полоса загрузки", "Фиолетовая полоса со звездой и бегущим бликом", s -> s.onixLoadBar, (s, v) -> s.onixLoadBar = v);
        t.table(c -> {
            c.left();
            c.button(Ico.of("download") + " Проверить обновления", MKUKICSkin.actBtn, () -> {
                MKUKICMenu.s().onixUpdateCheck = true;
                MKUKICSettings.save();
                OnixUpdater.check();
                MKUKICMenu.rebuild();
            }).minWidth(190f).height(38f).pad(2f);
            c.button(OnixUpdater.state == OnixUpdater.State.downloading ? "Отменить" : "Скачать", MKUKICSkin.actBtn, () -> {
                if(OnixUpdater.state == OnixUpdater.State.downloading) OnixUpdater.cancel();
                else OnixUpdater.download();
                MKUKICMenu.rebuild();
            }).minWidth(140f).height(38f).pad(2f);
            c.button(Ico.of("left") + " Откатить версию", MKUKICSkin.actBtn, () -> {
                boolean ok = OnixUpdater.rollback();
                MKUKICMod.toast(ok ? "[accent]Onix[]: " + OnixUpdater.message : "[scarlet]Onix[]: нечего откатывать");
                MKUKICMenu.rebuild();
            }).minWidth(150f).height(38f).pad(2f);
        }).left().growX().row();
        t.label(() -> "[lightgray]версия: [accent]" + OnixUpdater.currentVersion
            + "  [lightgray]статус: [accent]" + OnixUpdater.state
            + (OnixUpdater.state == OnixUpdater.State.downloading ? " " + (int)(OnixUpdater.progress * 100f) + "% (ещё " + OnixUpdater.etaText() + ")" : "")
            + (OnixUpdater.message.isEmpty() ? "" : "\n[#8b94a5]" + OnixUpdater.message))
            .left().top().wrap().width(680f).pad(4f).row();
    }

    // ------------------------------ ПРОЧЕЕ ------------------------------

    public static void miscSection(Table t){
        MKUKICMenu.header(t, Ico.of("settings") + " Прочее из Onix", null);
        MKUKICMenu.toggle(t, Ico.of("lock") + " Блок серверных окон и анти-спам",
            "Повторяющиеся сообщения и флуд помечаются уведомлением (полностью глушить чужие сообщения мод не может — их рисует игра)",
            s -> s.onixBlockServerWindows, (s, v) -> s.onixBlockServerWindows = v);
        MKUKICMenu.toggle(t, Ico.of("zoom") + " Поиск функций в панели", "Onix: «поиск функций в панели (раскладки, транслит, опечатки)»", s -> s.onixPanelSearch, (s, v) -> s.onixPanelSearch = v);
        MKUKICMenu.toggle(t, Ico.of("eye") + " Скрывать недоступные функции", "Onix: функции, недоступные на текущем сервере, убираются из панели", s -> s.onixHideUnavailable, (s, v) -> s.onixHideUnavailable = v);
    }

    // ============================== СЕКЦИИ В ДРУГИХ ВКЛАДКАХ ==============================

    /** «ИИ игрока»: Авто-Уни. */
    public static void autoUnitSection(Table t){
        MKUKICMenu.header(t, Ico.of("logic") + " Авто-Уни: очередь планов",
            "«Есть свои планы стройки — юнит строит их. Не хватает ресурса для плана — летит добывать именно его.\n"
            + "Планов нет — обычный автомайн. Использует уже существующие BuildPath и MinePath,\n"
            + "не тянет новый код навигации» — в MKUKIC это общий навигатор поверх A* из автопилота до монет.");

        MKUKICMenu.toggle(t, Ico.of("logic") + " Авто-Уни включён", "Водит ВАШ юнит по очереди планов", s -> s.onixAutoUnit, (s, v) -> s.onixAutoUnit = v);
        MKUKICMenu.toggle(t, "   " + Ico.of("hammer") + " Выполнять планы стройки", null, s -> s.onixAutoBuild, (s, v) -> s.onixAutoBuild = v);
        MKUKICMenu.toggle(t, "   " + Ico.of("production") + " Добывать недостающий ресурс", null, s -> s.onixAutoMineMissing, (s, v) -> s.onixAutoMineMissing = v);
        MKUKICMenu.toggle(t, "   " + Ico.of("production") + " Без планов — обычная добыча", null, s -> s.onixAutoMine, (s, v) -> s.onixAutoMine = v);
        MKUKICMenu.toggle(t, "   " + Ico.of("home") + " Сдавать добытое в ядро", null, s -> s.onixAutoDeliver, (s, v) -> s.onixAutoDeliver = v);
        MKUKICMenu.toggle(t, "   " + Ico.of("right") + " Пропускать недостижимые планы", null, s -> s.onixAutoSkipUnreachable, (s, v) -> s.onixAutoSkipUnreachable = v);
        MKUKICMenu.slider(t, "   " + Ico.of("resize") + " Радиус поиска руды и целей", 50f, 2000f, 50f, s -> s.onixAutoRadius, v -> MKUKICMenu.s().onixAutoRadius = v, v -> (int)(float)v + " тайлов");
        MKUKICMenu.slider(t, "   " + Ico.of("list") + " Предел очереди планов", 10f, 2000f, 10f, s -> s.onixAutoMaxPlans, v -> MKUKICMenu.s().onixAutoMaxPlans = (int)v, v -> (int)(float)v + " шт.");

        t.table(c -> {
            c.left();
            c.button(Ico.of("hammer") + " Схема у курсора → в очередь", MKUKICSkin.actBtn, () -> {
                OnixAutoUnit.loadSchematicAtCursor(null);
                MKUKICMenu.rebuild();
            }).minWidth(230f).height(38f).pad(2f);
            c.button(Ico.of("hammer") + " Мои планы → в очередь", MKUKICSkin.actBtn, () -> {
                OnixAutoUnit.capturePlayerPlans();
                MKUKICMenu.rebuild();
            }).minWidth(200f).height(38f).pad(2f);
            c.button(Ico.of("wrench") + " Сломанное рядом → в очередь", MKUKICSkin.actBtn, () -> {
                OnixAutoUnit.addBrokenAround(MKUKICMenu.s().onixAutoRadius);
                MKUKICMenu.rebuild();
            }).minWidth(230f).height(38f).pad(2f);
            c.button(Ico.of("right") + " Пропустить текущий", MKUKICSkin.actBtn, () -> {
                OnixAutoUnit.skipCurrent();
                MKUKICMenu.rebuild();
            }).minWidth(180f).height(38f).pad(2f);
            c.button(Ico.of("eraser") + " Очистить очередь", MKUKICSkin.actBtn, () -> {
                OnixAutoUnit.clearQueue();
                MKUKICMenu.rebuild();
            }).minWidth(170f).height(38f).pad(2f);
        }).left().growX().row();
        t.label(() -> "[lightgray]в очереди: [accent]" + OnixAutoUnit.queue.size
            + " [lightgray](осталось [accent]" + OnixAutoUnit.remaining()
            + "[lightgray], построено [accent]" + OnixAutoUnit.builtCount()
            + "[lightgray], пропущено [accent]" + OnixAutoUnit.skippedCount() + "[])\n"
            + "[#8b94a5]статус: " + OnixAutoUnit.status())
            .left().top().wrap().width(680f).pad(4f).row();
    }

    /** «Утилиты»: Авто-Филл. */
    public static void autoFillSection(Table t){
        MKUKICMenu.header(t, Ico.of("distribution") + " Авто-Филл: развоз ресурсов",
            "«Развозит ресурсы по голодным постройкам: берёт из ядра, контейнеров и цехов.\n"
            + "Источники с лучшим запасом выбираются сами; почти полные постройки не доливает.\n"
            + "Приоритеты блоков и предметов: диалог со степперами, -1 = пропустить, кнопка сброса».");

        MKUKICMenu.toggle(t, Ico.of("distribution") + " Авто-Филл включён", "Ваш юнит развозит ресурсы по базе", s -> s.onixAutofill, (s, v) -> s.onixAutofill = v);
        MKUKICMenu.toggle(t, "   " + Ico.of("home") + " Брать из ядра", null, s -> s.onixFillFromCore, (s, v) -> s.onixFillFromCore = v);
        MKUKICMenu.toggle(t, "   " + Ico.of("box") + " Брать из складов", null, s -> s.onixFillFromStorage, (s, v) -> s.onixFillFromStorage = v);
        MKUKICMenu.toggle(t, "   " + Ico.of("crafting") + " Брать из цехов", null, s -> s.onixFillFromFactory, (s, v) -> s.onixFillFromFactory = v);
        MKUKICMenu.slider(t, "   " + Ico.of("box") + " Предметов за рейс", 5f, 400f, 5f, s -> s.onixFillBatch, v -> MKUKICMenu.s().onixFillBatch = (int)v, v -> (int)(float)v + " шт.");
        MKUKICMenu.slider(t, "   " + Ico.of("resize") + " Радиус развоза", 100f, 3000f, 100f, s -> s.onixFillRadius, v -> MKUKICMenu.s().onixFillRadius = v, v -> (int)(float)v + " тайлов");
        MKUKICMenu.slider(t, "   " + Ico.of("box") + " Не доливать почти полные", 0.3f, 1f, 0.05f, s -> s.onixFillFullRatio, v -> MKUKICMenu.s().onixFillFullRatio = v, v -> (int)(v * 100f) + "%");
        MKUKICMenu.toggle(t, "   " + Ico.of("pencil") + " Линии рейсов", null, s -> s.onixShowFillPath, (s, v) -> s.onixShowFillPath = v);

        t.table(c -> {
            c.left();
            c.button(Ico.of("list") + " Приоритеты предметов", MKUKICSkin.actBtn, () -> priorityDialog(true)).minWidth(200f).height(38f).pad(2f);
            c.button(Ico.of("list") + " Приоритеты блоков", MKUKICSkin.actBtn, () -> priorityDialog(false)).minWidth(200f).height(38f).pad(2f);
            c.button(Ico.of("refresh") + " Сбросить приоритеты", MKUKICSkin.actBtn, () -> {
                MKUKICMenu.s().onixFillItemPriority.clear();
                MKUKICMenu.s().onixFillBlockPriority.clear();
                MKUKICSettings.save();
                MKUKICMenu.rebuild();
            }).minWidth(190f).height(38f).pad(2f);
            c.button(Ico.of("zoom") + " Найти нужды базы", MKUKICSkin.actBtn, () -> {
                if(player != null && player.unit() != null){
                    OnixAutoFill.scan(player.unit(), MKUKICMenu.s());
                    MKUKICMod.toast("[accent]Onix[]: голодных построек: " + OnixAutoFill.pendingCount() + " (рейсов в плане)");
                }
            }).minWidth(180f).height(38f).pad(2f);
        }).left().growX().row();
        t.label(() -> "[lightgray]рейсов в плане: [accent]" + OnixAutoFill.pendingCount()
            + " [lightgray]выполнено: [accent]" + OnixAutoFill.tripsDone()
            + " [lightgray]доставлено: [accent]" + OnixAutoFill.itemsDelivered()
            + "\n[#8b94a5]статус: " + OnixAutoFill.status())
            .left().top().wrap().width(680f).pad(4f).row();
    }

    /** «ИИ юнитов»: бот Ассист / Антигрифер. */
    public static void botSection(Table t){
        MKUKICMenu.header(t, Ico.of("units") + " Бот: ассистент и антигрифер",
            "Onix: «Асист, антигрифер (SmartBot) и другие боты с целью: мини-табличка ровно над панелью схем.\n"
            + "Табличка видна только при активном боте: „Цель: Nick“, „Блоки: -N (сломано) +M (построено)“.\n"
            + "Хопы бота, визуал пути: фиолетовый пунктир, звезда-цель, орбита кружения, красные зоны опасности.\n"
            + "Отступление от перегретых реакторов. Слайдеры бота: радиус, скорость кружения, скорость бота».\n"
            + "В MKUKIC бот управляет ВАШИМ юнитом — так же, как остальные автопилоты клиента.");

        MKUKICMenu.toggle(t, Ico.of("units") + " Бот включён (Z)", null, s -> s.onixBot, (s, v) -> s.onixBot = v);
        MKUKICMenu.chooser(t, "   " + Ico.of("settings") + " Режим бота", new String[]{"Ассист", "Антигрифер"}, s -> s.onixBotMode, v -> MKUKICMenu.s().onixBotMode = v);
        t.table(c -> {
            c.left();
            c.add("[#c2cad8]Цель (ник):").left().minWidth(230f);
            c.field(MKUKICSettings.s().onixBotTarget, MKUKICSkin.field, txt -> {
                MKUKICMenu.s().onixBotTarget = txt.trim();
                MKUKICSettings.save();
            }).width(200f);
            c.button("игроки…", MKUKICSkin.actBtn, () -> {
                BaseDialog d = new BaseDialog("Цель бота");
                d.cont.pane(MKUKICSkin.scroll, p -> {
                    p.top().left();
                    p.button("ближайший союзник (авто)", MKUKICSkin.toggleBtn, () -> {
                        MKUKICMenu.s().onixBotTarget = "";
                        MKUKICSettings.save();
                        d.hide();
                        MKUKICMenu.rebuild();
                    }).minWidth(280f).height(38f).pad(2f).row();
                    for(Player pl : Groups.player){
                        if(pl == player) continue;
                        p.button(pl.name() + " [darkgray](" + pl.team().name + ")", MKUKICSkin.toggleBtn, () -> {
                            MKUKICMenu.s().onixBotTarget = pl.name();
                            MKUKICSettings.save();
                            d.hide();
                            MKUKICMenu.rebuild();
                        }).checked(b -> pl.name().equals(MKUKICMenu.s().onixBotTarget)).minWidth(280f).height(38f).pad(2f).row();
                    }
                }).grow();
                d.show();
            }).minWidth(110f).height(34f).pad(2f);
        }).left().growX().padTop(2f).row();
        MKUKICMenu.slider(t, "   " + Ico.of("resize") + " Радиус работы бота", 40f, 1200f, 20f, s -> s.onixBotRadius, v -> MKUKICMenu.s().onixBotRadius = v, v -> (int)(float)v + " тайлов");
        MKUKICMenu.slider(t, "   " + Ico.of("refresh") + " Скорость кружения", 0.1f, 4f, 0.1f, s -> s.onixBotOrbitSpeed, v -> MKUKICMenu.s().onixBotOrbitSpeed = v, v -> "x" + Strings.autoFixed(v, 1));
        MKUKICMenu.toggle(t, "   " + Ico.of("eye") + " Путь бота на экране", "Фиолетовый пунктир, звезда-цель, орбита кружения", s -> s.onixBotPath, (s, v) -> s.onixBotPath = v);
        MKUKICMenu.toggle(t, "      " + Ico.of("warning") + " Красные зоны опасности", "Радиусы вражеских турелей и реакторов", s -> s.onixBotDanger, (s, v) -> s.onixBotDanger = v);
        MKUKICMenu.toggle(t, "   " + Ico.of("left") + " Отход от перегретых реакторов", null, s -> s.onixBotOverheat, (s, v) -> s.onixBotOverheat = v);
        MKUKICMenu.note(t, "Мини-табличка «Цель / Блоки» рисуется над панелью схем только при включённом боте — как в Onix.");
        t.label(() -> {
            StringBuilder sb = new StringBuilder("[lightgray]Бот: [accent]" + OnixBot.status() + "[]");
            if(!OnixBot.griefers.isEmpty()){
                sb.append("\n[lightgray]Гриферы:");
                for(OnixBot.Griever g : OnixBot.griefers) sb.append("\n  [scarlet]").append(g.name).append(" [darkgray]x").append(g.broken);
            }
            sb.append("\n[lightgray]построено ботом: [accent]").append(OnixBot.builtByBot)
                .append(" [lightgray]сломано у команды: [scarlet]").append(OnixBot.brokenSeen);
            return sb.toString();
        }).left().top().wrap().width(680f).pad(4f).row();
    }

    /** «Информация»: чат-функции Onix. */
    public static void chatSection(Table t){
        MKUKICMenu.header(t, Ico.of("chat") + " Чат: голосование, копирование, АФК",
            "Onix: «кнопки vote y / vote n отдельным блоком под сообщением, не накрывают текст, гаснут после голоса;\n"
            + "распознавание /vote не зависит от языка сервера»; «ПКМ по сообщению копирует текст со всеми цветами\n"
            + "и цветным никнеймом, тумблер по умолчанию выключен»; «копирование ника работает с цветами всегда,\n"
            + "без лимита на количество тегов [ в имени»; «автоответчик АФК: цветной, включён по умолчанию,\n"
            + "триггер — ник подстрокой в любом месте».");

        MKUKICMenu.toggle(t, Ico.of("ok") + " Кнопки голосования в чате", null, s -> s.onixChatVote, (s, v) -> s.onixChatVote = v);
        MKUKICMenu.toggle(t, Ico.of("copy") + " Копирование из чата (ПКМ)", "По умолчанию выключено — как в Onix", s -> s.onixChatCopy, (s, v) -> s.onixChatCopy = v);
        MKUKICMenu.toggle(t, "   " + Ico.of("copy") + " Копировать ник с цветом", "Без лимита на количество тегов [ в имени", s -> s.onixCopyNickColors, (s, v) -> s.onixCopyNickColors = v);
        MKUKICMenu.slider(t, "      " + Ico.of("list") + " Лимит цветовых тегов в нике", 1f, 99f, 1f, s -> s.onixCopyNickTagLimit, v -> MKUKICMenu.s().onixCopyNickTagLimit = (int)v,
            v -> (int)(float)v >= 99 ? "без лимита" : (int)(float)v + "");
        MKUKICMenu.toggle(t, Ico.of("chat") + " Автоответчик АФК", "Отвечает, когда ваш ник упоминают в чате", s -> s.onixAfkReply, (s, v) -> s.onixAfkReply = v);
        t.table(c -> {
            c.left();
            c.add("[#c2cad8]Текст ответа:").left().minWidth(230f);
            c.field(MKUKICSettings.s().onixAfkText, MKUKICSkin.field, txt -> {
                MKUKICMenu.s().onixAfkText = txt;
                MKUKICSettings.save();
            }).width(360f);
        }).left().growX().padTop(2f).row();
        MKUKICMenu.slider(t, "   " + Ico.of("refresh") + " Пауза между ответами", 3f, 120f, 1f, s -> s.onixAfkCooldown, v -> MKUKICMenu.s().onixAfkCooldown = v, v -> (int)(float)v + " с");
        MKUKICMenu.toggle(t, "   " + Ico.of("zoom") + " Триггер: ник в любом месте текста", "Иначе — на любой вопросительный текст", s -> s.onixAfkOnlyNick, (s, v) -> s.onixAfkOnlyNick = v);
        MKUKICMenu.row(t, Ico.of("copy") + " Скопировать свой ник", "«Ник копируется с цветом: вставка в игровой чат — цветная»", () -> OnixChat.copyNick(player));
        MKUKICMenu.row(t, Ico.of("chat") + " Отправить пустое сообщение", "blank — символ нулевой ширины", OnixChat::sendBlank);
        MKUKICMenu.toggle(t, Ico.of("pencil") + " Замены %s / %c / %h", null, s -> s.onixTextReplacer, (s, v) -> s.onixTextReplacer = v);
    }

    /** «Визуал»: анимации Onix. */
    public static void animsSection(Table t){
        MKUKICMenu.header(t, Ico.of("effect") + " Анимации интерфейса",
            "Onix: «раздел „Анимации“ с переключателями: что анимировать, а что нет; плавный чат — мягкое движение\n"
            + "строк, печатная машинка, каретка; панель функций вылетает снизу с каскадом категорий, таб открывается\n"
            + "без телепорта; мягкие счётчики, HUD функций в цвете темы».");

        MKUKICMenu.toggle(t, Ico.of("effect") + " Анимации включены", "Мастер-переключатель раздела", s -> s.onixAnims, (s, v) -> s.onixAnims = v);
        MKUKICMenu.toggle(t, "   " + Ico.of("chat") + " Плавный чат", null, s -> s.onixSmoothChat, (s, v) -> s.onixSmoothChat = v);
        MKUKICMenu.toggle(t, "   " + Ico.of("pencil") + " Печатная машинка в чате", "Новое сообщение чата допечатывается посимвольно", s -> s.onixTypewriter, (s, v) -> s.onixTypewriter = v);
        MKUKICMenu.toggle(t, "   " + Ico.of("chartBar") + " Мягкие счётчики", "Числа в HUD не прыгают, а догоняют значение", s -> s.onixSmoothCounters, (s, v) -> s.onixSmoothCounters = v);
        MKUKICMenu.toggle(t, "   " + Ico.of("eye") + " Цвет HUD по теме", null, s -> s.onixHudThemeColor, (s, v) -> s.onixHudThemeColor = v);
        MKUKICMenu.toggle(t, "   " + Ico.of("menu") + " Каскадное появление панелей", "Окна мода появляются снизу с отставанием строк", s -> s.onixAnims, (s, v) -> s.onixAnims = v);
        MKUKICMenu.row(t, Ico.of("play") + " Показать анимацию панели", "Сбросить каскад — видно, как панель вылетает снизу", OnixAnims::armPanel);
    }

    // ============================== ДИАЛОГИ ==============================

    /** «Диалог со степперами, -1 = пропустить, кнопка сброса». */
    public static void priorityDialog(boolean items){
        BaseDialog d = new BaseDialog(items ? "Приоритет предметов (Авто-Филл)" : "Приоритет блоков (Авто-Филл)");
        Seq<String> list = items ? MKUKICMenu.s().onixFillItemPriority : MKUKICMenu.s().onixFillBlockPriority;
        d.cont.pane(MKUKICSkin.scroll, p -> {
            p.top().left();
            p.add("[#8b94a5]Порядок сверху вниз = приоритет (0 — самый нужный). [scarlet]-1 = пропустить[].\n"
                + "Пустой список = решать по нужде постройки.").wrap().width(560f).padBottom(8f).row();
            if(items){
                for(Item it : content.items()){
                    if(it == null) continue;
                    priorityRow(p, it.localizedName, it.name, list);
                }
            }else{
                for(Block b : content.blocks()){
                    if(b == null || !b.hasItems || b.isAir()) continue;
                    if(b instanceof mindustry.world.blocks.storage.CoreBlock) continue;
                    if(b instanceof mindustry.world.blocks.storage.StorageBlock) continue;
                    priorityRow(p, b.localizedName, b.name, list);
                }
            }
        }).size(620f, 560f);
        d.buttons.button(Ico.of("refresh") + " Сбросить", MKUKICSkin.actBtn, () -> {
            list.clear();
            MKUKICSettings.save();
            d.hide();
            MKUKICMenu.rebuild();
        }).size(150f, 44f);
        d.buttons.button("Закрыть", MKUKICSkin.actBtn, d::hide).size(150f, 44f);
        d.show();
    }

    private static void priorityRow(Table p, String label, String key, Seq<String> list){
        int idx = list.indexOf(key, false);
        p.table(c -> {
            c.left();
            c.add(label).left().minWidth(180f);
            c.add("[#8b94a5]" + key).left().minWidth(140f);
            c.add().growX();
            c.label(() -> {
                int i = list.indexOf(key, false);
                return i < 0 ? "[darkgray]—" : i == 0 && list.size > 1 ? "[scarlet]-1[]" : "[accent]" + i;
            }).minWidth(50f);
            c.button("+", MKUKICSkin.actBtn, () -> {
                int i = list.indexOf(key, false);
                if(i < 0){
                    list.add(key);
                }else if(i == list.size - 1){
                    list.remove(i);          // последний -> «пропустить» (вне списка = авто)
                }
                MKUKICSettings.save();
            }).size(34f, 30f).pad(2f);
            c.button("–", MKUKICSkin.actBtn, () -> {
                int i = list.indexOf(key, false);
                if(i > 0){
                    list.swap(i, i - 1);
                }else if(i == 0){
                    list.remove(0);          // 0 -> «пропустить»
                }
                MKUKICSettings.save();
            }).size(34f, 30f).pad(2f);
        }).left().growX().pad(1f).row();
    }

    /** Палитра команд клиента. */
    public static void commandsDialog(){
        BaseDialog d = new BaseDialog("Команды клиента Onix");
        TextField arg = new TextField("");
        d.cont.table(t -> {
            t.top().left();
            t.add("[#8b94a5]Клик по команде — выполнить. Аргументы можно вписать в поле справа от префикса.")
                .wrap().width(640f).padBottom(6f).row();
            t.table(c -> {
                c.left();
                c.add("[#c2cad8]" + OnixCommands.prefix()).left();
                c.add(arg).width(320f);
            }).left().row();
        }).row();
        d.cont.pane(MKUKICSkin.scroll, p -> {
            p.top().left();
            for(OnixCommands.Cmd c : OnixCommands.commands()){
                p.table(r -> {
                    r.left();
                    r.button("[accent]" + c.name + " [darkgray]" + c.args, MKUKICSkin.toggleBtn, () -> {
                        String a = arg.getText() == null ? "" : arg.getText().trim();
                        OnixCommands.execute(OnixCommands.prefix() + c.name + (a.isEmpty() ? "" : " " + a));
                    }).left().minWidth(300f).height(34f).pad(2f);
                    r.add("[#7e8798] " + c.desc).left().padLeft(8f);
                }).left().growX().row();
            }
        }).grow();
        d.buttons.button("Закрыть", MKUKICSkin.actBtn, d::hide).size(160f, 44f);
        d.show();
    }

    /** Тексты при заходе/победе/поражении. */
    public static void textsDialog(){
        BaseDialog d = new BaseDialog("Тексты в чат");
        d.cont.table(t -> {
            t.top().left();
            t.defaults().pad(4f).left();
            t.add("[#c2cad8]При заходе (gamejointext):").width(220f);
            t.field(MKUKICMenu.s().onixJoinText, MKUKICSkin.field, v -> { MKUKICMenu.s().onixJoinText = v; MKUKICSettings.save(); }).width(360f).row();
            t.add("[#c2cad8]При победе (gamewintext):").width(220f);
            t.field(MKUKICMenu.s().onixWinText, MKUKICSkin.field, v -> { MKUKICMenu.s().onixWinText = v; MKUKICSettings.save(); }).width(360f).row();
            t.add("[#c2cad8]При поражении (gamelosetext):").width(220f);
            t.field(MKUKICMenu.s().onixLoseText, MKUKICSkin.field, v -> { MKUKICMenu.s().onixLoseText = v; MKUKICSettings.save(); }).width(360f).row();
            t.add("[#8b94a5]Пусто = не отправлять. Подстановки %s %c %h работают, если включены.").wrap().width(560f).padTop(6f).row();
        });
        d.buttons.button("Закрыть", MKUKICSkin.actBtn, d::hide).size(160f, 44f);
        d.show();
    }

    /** Замены текста (replacemessage). */
    public static void replacerDialog(){
        BaseDialog d = new BaseDialog("Замены в сообщениях");
        d.cont.pane(MKUKICSkin.scroll, p -> {
            p.top().left();
            p.add("[#8b94a5]Onix: replacemessage <from> <to>. Подстановки: %s — ¯\\_(ツ)_/¯, %c — координаты курсора, %h — координаты юнита.")
                .wrap().width(560f).padBottom(6f).row();
            for(ObjectMap.Entry<String, String> e : OnixCommands.replacers.entries()){
                p.table(r -> {
                    r.left();
                    r.add("[accent]" + e.key).minWidth(140f).left();
                    r.add("[#8b94a5]→ " + e.value).minWidth(200f).left();
                    r.button("x", MKUKICSkin.actBtn, () -> {
                        OnixCommands.replacers.remove(e.key);
                        d.hide();
                        replacerDialog();
                    }).size(30f, 30f).padLeft(6f);
                }).left().growX().row();
            }
        }).grow();
        d.buttons.button(Ico.of("add") + " Добавить…", MKUKICSkin.actBtn, () -> addReplacer(d)).size(160f, 44f);
        d.buttons.button("Закрыть", MKUKICSkin.actBtn, d::hide).size(150f, 44f);
        d.show();
    }

    private static void addReplacer(BaseDialog parent){
        BaseDialog d = new BaseDialog("Новая замена");
        TextField from = new TextField("");
        TextField to = new TextField("");
        d.cont.table(t -> {
            t.add("[#c2cad8]что:").left().padRight(6f);
            t.add(from).width(200f).row();
            t.add("[#c2cad8]на что:").left().padRight(6f);
            t.add(to).width(200f);
        }).pad(10f);
        d.buttons.button("Отмена", MKUKICSkin.actBtn, d::hide).size(140f, 44f);
        d.buttons.button(Ico.of("add") + " Добавить", MKUKICSkin.actBtn, () -> {
            if(!from.getText().isEmpty()){
                OnixCommands.replacers.put(from.getText(), to.getText());
                MKUKICMenu.s().onixTextReplacer = true;
                MKUKICSettings.save();
            }
            d.hide();
            parent.hide();
            replacerDialog();
        }).size(160f, 44f);
        d.show();
    }

    // ============================== ПОИСК ФУНКЦИЙ ==============================

    /**
     * «Поиск функций в панели (раскладки, транслит, опечатки)».
     * Ищет по названию и описанию всех строк всех вкладок.
     */
    public static void searchRow(Table t){
        if(!MKUKICMenu.s().onixPanelSearch) return;
        t.table(c -> {
            c.left();
            c.add("[(zoom)]").padRight(6f);
            c.field(searchText, MKUKICSkin.field, txt -> {
                searchText = txt;
                MKUKICMenu.rebuild();
            }).width(320f);
            c.button("x", MKUKICSkin.actBtn, () -> {
                searchText = "";
                MKUKICMenu.rebuild();
            }).size(30f, 30f).padLeft(4f);
        }).left().growX().padBottom(6f).row();
    }

    /**
     * «Поиск функций в панели (раскладки, транслит, опечатки)».
     * Каждую вкладку собираем во временный невидимый стол и обходим дерево элементов:
     * строки, содержащие ячейки с текстом, подходящим под запрос, показываются списком
     * с кнопкой перехода на вкладку. Так ищутся вообще все функции мода, а не только Onix.
     */
    public static void buildSearchResults(Table t, String query){
        t.table(h -> {
            h.left();
            h.image(Tex.whiteui, MKUKICSkin.accent).width(3.5f).height(20f).padRight(9f);
            h.add("[#ffb454]Поиск: «" + query + "»").left();
        }).left().growX().padTop(6f).row();
        t.add("[#8b94a5]Учитываются раскладка клавиатуры, транслит и опечатки. Клик по вкладке — перейти.")
            .left().wrap().width(660f).padBottom(6f).row();

        String[] names = MKUKICMenu.tabLabels();
        int found = 0;
        for(int i = 0; i < names.length; i++){
            final int idx = i;
            Table tmp = new Table();
            try{
                MKUKICMenu.buildTabInto(idx, tmp);
            }catch(Throwable e){
                continue;
            }
            Seq<String> hits = new Seq<>();
            collectHits(tmp, query, hits);
            if(hits.isEmpty()) continue;
            found += hits.size;
            for(String h : hits){
                if(found > 120) break;
                t.table(r -> {
                    r.left().top();
                    r.button("[accent]" + names[idx], MKUKICSkin.toggleBtn, () -> MKUKICMenu.showTab(idx))
                        .minWidth(130f).height(32f).padRight(8f).top();
                    r.add("[#c2cad8]" + h).left().top().wrap().width(520f);
                }).left().growX().padBottom(2f).row();
            }
        }
        if(found == 0) t.add("[gray]ничего не найдено").left().padTop(16f).row();
    }

    /** Обход дерева: собираем тексты строк, подходящих под запрос. */
    private static void collectHits(Element root, String query, Seq<String> out){
        if(root == null || out.size > 60) return;
        if(root instanceof Label l){
            String txt = Strings.stripColors(l.getText().toString());
            if(!txt.isEmpty() && fuzzyMatch(query, txt) && txt.length() > 2){
                String clean = txt.replace("\n", " ");
                if(clean.length() > 170) clean = clean.substring(0, 170) + "…";
                if(!out.contains(clean, false)) out.add(clean);
            }
            return;
        }
        if(root instanceof Table tb){
            for(arc.scene.ui.layout.Cell<?> c : tb.getCells()){
                if(c == null) continue;
                collectHits(c.get(), query, out);
            }
            return;
        }
        if(root instanceof arc.scene.Group g){
            for(Element ch : g.getChildren()) collectHits(ch, query, out);
        }
    }

    public static String searchText(){
        return searchText == null ? "" : searchText.trim();
    }

    public static void setSearchText(String s){
        searchText = s == null ? "" : s;
    }

    /**
     * Нечёткое сравнение с учётом раскладки и транслита:
     * «фвзщ» = «авто», «avto» = «авто», допустимы опечатки (расстояние Левенштейна ≤ 2).
     */
    public static boolean fuzzyMatch(String query, String text){
        if(query == null || query.isEmpty()) return true;
        if(text == null) return false;
        String low = query.toLowerCase();
        // запрос проверяем в трёх видах: как есть, «забытая раскладка» и фонетический транслит
        String[] qs = {low, translit(low), phonetic(low)};
        String tl = text.toLowerCase();
        String[] ts = {tl, translit(tl), phonetic(tl)};
        for(String t : ts){
            for(String q : qs){
                if(q.isEmpty()) continue;
                if(t.contains(q)) return true;
            }
        }
        // опечатки: короткое расстояние для слов
        for(String t : ts){
            for(String w : t.split("[^a-z0-9а-яё]+")){
                if(w.isEmpty()) continue;
                for(String q : qs){
                    if(q.isEmpty()) continue;
                    if(w.startsWith(q) || q.startsWith(w)) return true;
                    if(Math.abs(w.length() - q.length()) <= 2 && levenshtein(w, q) <= 2) return true;
                }
            }
        }
        return false;
    }

    /**
     * Фонетический транслит («avto» -> «авто», «unit» -> «унит»): поиск по панели
     * работает и тогда, когда настройку набирали латиницей. В отличие от
     * {@link #translit(String)} (это про физическую раскладку QWERTY→ЙЦУКЕН),
     * здесь сопоставление по звучанию; двузвучия обрабатываются первыми.
     */
    public static String phonetic(String s){
        if(s == null) return null;
        String[][] pairs = {
            {"sch", "щ"}, {"shch", "щ"}, {"ch", "ч"}, {"sh", "ш"}, {"zh", "ж"}, {"kh", "х"},
            {"ju", "ю"}, {"ja", "я"}, {"yo", "ё"}, {"jo", "ё"}, {"yu", "ю"}, {"ya", "я"}, {"ee", "и"},
            {"a", "а"}, {"b", "б"}, {"v", "в"}, {"w", "в"}, {"g", "г"}, {"d", "д"}, {"e", "е"},
            {"z", "з"}, {"i", "и"}, {"y", "и"}, {"j", "й"}, {"k", "к"}, {"q", "к"}, {"l", "л"},
            {"m", "м"}, {"n", "н"}, {"o", "о"}, {"p", "п"}, {"r", "р"}, {"s", "с"}, {"t", "т"},
            {"u", "у"}, {"f", "ф"}, {"h", "х"}, {"c", "к"}, {"x", "кс"}, {"'", "ь"}, {"`", "ъ"},
        };
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while(i < s.length()){
            boolean hit = false;
            for(String[] p : pairs){
                if(s.startsWith(p[0], i)){
                    sb.append(p[1]);
                    i += p[0].length();
                    hit = true;
                    break;
                }
            }
            if(!hit){
                sb.append(s.charAt(i));
                i++;
            }
        }
        return sb.toString();
    }

    private static final String lat = "qwertyuiop[]asdfghjkl;'zxcvbnm,.`";
    private static final String rus = "йцукенгшщзхъфывапролджэячсмитьбюё";

    /** QWERTY→ЙЦУКЕН и транслит→кириллица. */
    public static String translit(String s){
        StringBuilder sb = new StringBuilder();
        for(char c : s.toCharArray()){
            int i = lat.indexOf(c);
            if(i >= 0 && i < rus.length()){
                sb.append(rus.charAt(i));
                continue;
            }
            sb.append(c);
        }
        String out = sb.toString();
        out = out.replace("zh", "ж").replace("ch", "ч").replace("sh", "ш").replace("sch", "щ")
            .replace("ju", "ю").replace("ja", "я").replace("yo", "ё").replace("kh", "х");
        return out;
    }

    public static int levenshtein(String a, String b){
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for(int j = 0; j <= b.length(); j++) prev[j] = j;
        for(int i = 1; i <= a.length(); i++){
            cur[0] = i;
            for(int j = 1; j <= b.length(); j++){
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = cur;
            cur = tmp;
        }
        return prev[b.length()];
    }
}
