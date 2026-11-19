package mkukic;

import arc.files.*;
import arc.struct.*;
import arc.util.*;
import arc.util.serialization.*;

/**
 * Все настройки мода. Хранятся в configs/mkukic-client/settings.json.
 * Доступ — всегда через {@link #s()}, чтобы замена объекта не ломала ссылки.
 */
public class MKUKICSettings{
    // ================= БОЕВЫЕ =================
    public boolean aimbot = false;
    public float aimRange = 420f;           // 0 = автоматически по дальности оружия
    public int aimPriority = 0;             // 0 ближний | 1 опасный | 2 воздух | 3 наземка | 4 только на экране
    public boolean aimShoot = true;         // стрелять автоматически
    public boolean aimPredict = true;       // упреждение по скорости цели
    public boolean aimBuildings = true;     // целиться в здания
    public boolean aimOnlyOnFire = false;   // работать только при зажатой кнопке огня
    public boolean aimSilent = false;       // не доворачивать корпус юнита (только стволы)

    public boolean speed = false;
    public float speedMult = 1.8f;          // множитель скорости юнита
    public boolean rage = false;
    public float damageMult = 2f;
    public float reloadMult = 2f;           // >1 = быстрее перезарядка
    public boolean instantReload = false;   // мгновенная перезарядка всех орудий
    public boolean fortress = false;
    public float healthMult = 3f;
    public float armorAdd = 30f;
    public boolean godmode = false;
    public boolean fly = false;             // полёт для любого юнита
    public boolean infiniteRange = false;   // дальность стрельбы x2
    public float buildRangeAdd = 0f;        // +к радиусу стройки (0 = выкл)
    public float mineSpeedMult = 1f;        // множитель скорости копки (1 = выкл)
    public boolean autoHeal = false;        // медленно регенерировать HP

    // ================= ИИ ЮНИТОВ =================
    public boolean aiEnabled = true;
    public boolean aiAutoMine = false;      // сами копают нужную руду и сдают в ядро
    public boolean aiAutoBuild = false;     // достраивать чужие планы и разрушенные блоки
    public boolean aiAutoRepair = false;    // чинить повреждённые блоки
    public boolean aiFollow = false;        // следовать за игроком
    public float followDistance = 90f;
    public boolean aiEscort = false;        // прикрывать игрока, атакуя врагов рядом
    public float escortRange = 340f;
    public boolean aiRetreat = true;        // отступать к ядру при низком HP
    public float retreatHealth = 0.3f;
    public boolean aiAutoTarget = true;     // сами выбирают цель
    public boolean aiOnlyCommandable = true;// трогать только юниты под vanilla-командованием
    public float aiRadius = 1400f;          // радиус групповых фич
    public boolean aiAutoRebuild = false;   // восстанавливать разрушенные блоки (сканирует тайлы)

    // ============ ИИ: цели, группы, безопасность ============
    public boolean aiSafeZone = false;      // уход от врагов: при врагах рядом юниты идут к ядру
    public float fleeRadius = 450f;         // радиус обнаружения врагов для отхода

    // ============ ИИ: ремонт транспорта и отряды ============
    public boolean aiRepairTransports = false; // строители чинят конвейеры/мосты/турели/заводы
    public boolean aiSquads = false;           // отряды сторожат пути от точек высадки
    public int squadCount = 3;                 // число отрядов (0 = по числу точек высадки)

    // ============ МОНЕТЫ (порт CoinMagnet) ============
    public boolean coinMagnet = false;       // магнит: телепорт своего юнита к монете и удержание
    public boolean coinLines = true;         // линии-подсветка до монет и ценники
    public float coinRadius = 10f;           // радиус магнита в тайлах
    public boolean coinServerLimit = true;   // в сети ограничивать радиус 10 тайлами
    public boolean coinPreferHighValue = true; // сначала дорогие монеты (>= 5000)
    public boolean coinPilot = false;        // v1.14: автопилот до монет (летит/идёт сам, не телепорт)
    public float coinPilotRadius = 600f;     // радиус поиска монет автопилотом, тайлы (v1.18: x5, было 120)
    // v1.20: выбор юнита для пересадки автопилотом
    public int pilotSort = 0;                // 0 по времени прибытия (расстояние/скорость) | 1 ближайший к монете | 2 самый быстрый рядом с монетой
    public boolean pilotAirOnly = true;      // брать только летающие (на их сервере морские тоже летают — isFlying это учитывает)
    public boolean pilotAvoidSlow = true;    // понижать приоритет медленных: моно/поли/мега/квад/окт и всё, что медленнее порога
    public float pilotMinSpeed = 1.2f;       // порог «медленного», ед./тик (1.2 ≈ 9 тайлов/с)
    public boolean coinPilotDodge = true;    // уклонение от снарядов во время автопилота
    public float coinPilotDodgeR = 170f;     // радиус реагирования на снаряды, мир. единицы

    // ============ ШТАБ: слои карты ============
    public boolean hqOre = true;               // жилы руды
    public boolean hqRanges = true;            // радиусы турелей
    public boolean hqSectors = true;           // сектора обороны вокруг ядра
    public boolean hqSweep = true;             // радар-развёртка
    public boolean hqWaveAlert = true;         // алерт за 10с до волны

    // ============ ИИ ИГРОКА (автопилот) ============
    public boolean paEnabled = false;       // юнитом игрока управляет ИИ
    public boolean paMine = true;           // копать как poly
    public boolean paBuild = true;          // строить/чинить как mega
    public boolean paRepair = true;
    public Seq<String> paHelpPlayers = new Seq<>(); // список игроков, которым помогает ваш юнит
    public boolean paDodge = false;         // маневрировать от снарядов
    public float paDodgeRadius = 170f;

    // ================= ВИЗУАЛ / УТИЛИТЫ =================
    public boolean esp = false;
    public boolean espNames = true;
    public boolean espHealth = true;
    public boolean espDistance = true;
    public boolean espLines = false;
    public boolean espRangeCircles = false;
    public boolean espRadar = true;         // стрелки по краю экрана
    public boolean espFog = true;           // уважать туман войны
    public boolean espBuildings = false;    // подсвечивать вражеские постройки
    public boolean espPlayers = false;      // подсвечивать игроков
    public int espPlayerTeam = -1;          // -1 = все команды, иначе id команды
    public String espWhitelist = "";        // ники через запятую: показывать только их
    public boolean espTrails = false;       // маршруты движения вражеских юнитов
    public int espTrailLen = 50;            // длина следа (точек)
    public float espRadarRadius = 2600f;
    public boolean infoHud = true;          // строка статуса в углу
    public boolean showTargetLine = true;   // линия до текущей цели автоприцела
    public boolean menuButton = true;       // плавающая кнопка меню
    public boolean toolbarVisible = true;   // панель вкладок на экране
    public boolean toolbarCollapsed = false;  // свёрнута в кнопку «N»
    public float toolbarX = -1, toolbarY = -1;
    public boolean cmdBarVisible = false;   // плавающее окно своих команд
    public boolean cmdBarVertical = true;
    public float cmdBarX = -1, cmdBarY = -1;
    public float cmdBarOpacity = 0.9f;
    public Seq<String> cmdEntries = Seq.with("Статы|/stats", "Магазин|/shop", "История|/history");
    public boolean toasts = true;           // всплывающие уведомления

    // ================= АДМИН =================
    public boolean adminPanel = true;
    public boolean anticheatAlerts = true;  // алерты серверного античита

    // ============ QoL: окна, оверлеи, инструменты (порт каталога модов) ============
    public boolean qolEnabled = true;          // мастер-выключатель QoL-слоя
    // Helium-оверлеи
    public boolean qolHpBars = false;          // HP/щит-бары над юнитами
    public boolean qolHpBarsAll = false;       // над всеми юнитами (не только врагами)
    public boolean qolShieldStacks = true;     // счётчик «стеков» многослойных щитов
    public boolean qolRanges = true;           // дальности атак (курсор/выделение/свой юнит)
    public boolean qolServiceZones = false;    // зоны ремонтников/овердрайвов/щитов
    // ViewControl
    public float zoomMult = 1f;                // >1 = расширенный зум (1 = ваниль)
    public float timeScale = 1f;               // скорость симуляции (хост/одиночная)
    public boolean perfMaster = false;         // режим производительности
    public boolean perfWeather = false;
    public boolean perfEffects = false;
    public boolean perfLight = false;
    public boolean perfShields = false;
    public boolean perfWater = false;
    public boolean perfPings = false;
    public boolean perfOtherPlans = false;
    public boolean perfDisplays = false;
    // Tripwire (геозона)
    public boolean tripwire = false;
    public boolean tripwireAtCore = true;      // false = вокруг игрока
    public boolean tripwireSound = true;
    public float tripwireRadius = 900f;
    // Smart Upgrade
    public int smartUpgradeMax = 300;
    // Scheme-Size: лимит размера схем (64 = ваниль, до 512)
    public int schematicSize = 64;
    // CoreStats: линии графика (имена предметов, до 4)
    public Seq<String> qolTrackedItems = Seq.with("copper", "lead", "silicon");
    // позиции плавающих окон: id -> "x|y|collapsed"
    public arc.struct.ObjectMap<String, String> winPos = new arc.struct.ObjectMap<>();

    // ============ QoL v1.10: формации, маршруты, колесо, бэкапы, музыка, буры ============
    // RTS-формации (Neon): line/column/wedge/square/circle
    public String formType = "line";
    public float formSpacing = 24f;              // мир. единицы между юнитами (3 тайла)
    // Визуализатор маршрутов врага (MindustryTool Pathfinding Visualizer)
    public boolean qolPathOverlay = false;
    public boolean qolPathChokes = true;         // подсвечивать узкие места
    // Буровой планер (MindustryTool Smart Drill)
    public boolean qolDrillOverlay = false;
    public String drillName = "mechanical-drill";
    // Радиальное строительное меню (Neon Radial Build)
    public Seq<String> wheelFav = new Seq<>();   // избранные блоки (имена)
    public Seq<String> wheelRecent = new Seq<>();// недавно использованные
    // Колесо быстрых сообщений: записи «подпись|текст»
    public Seq<String> quickMsgs = Seq.with("gg|gg", "Помогите!|Нужна помощь на базе!", "Спасибо|Спасибо!", "Атака!|Нас атакуют!", "Иду|Уже иду", "+|+");
    // Угон юнитов через смену команды (/team): режим 0 один юнит | 1 все | 2 только сильные
    public int tswapMode = 1;
    public String tswapCmdEnemy = "/team 2";
    public String tswapCmdHome = "/team 1";
    public float tswapStepSec = 0.5f;            // пауза между шагами цикла
    public float tswapStrongHp = 700f;           // порог «сильных» по макс. HP
    // Музыкальный плеер (MindustryTool Music Player)
    public float musicVolume = 0.8f;
    public boolean musicLoop = false;
    // Сглаживание лаг-скачков (MI2 Time Scaler)
    public boolean qolSmoothDelta = false;

    // ---------------- v1.11: инспектор и маркеры ----------------
    public boolean inspFollow = true;            // инспектор: следить за курсором (иначе закреплённая точка)
    public boolean inspWorld = false;            // инспектор: компактная сводка в мире у курсора
    public boolean markerOverlay = true;         // маркеры: рисовать в мире
    public boolean markerText = true;            // маркеры: подписи текстом
    public float markerRadius = 8f;              // радиус круга по умолчанию (тайлы)
    public int markerColor = 0;                  // индекс цвета в палитре маркеров

    // ---------------- v1.13: умный снос ----------------
    public int pruneMax = 300;                   // лимит блоков в цепочке сноса

    // ---------------- v1.16: порт фич из других модов ----------------
    // Атака-алерт (m3rcur1e/attack-alert + MinerTools EnemyIndicator + Caster-UI core-lost)
    public boolean attackAlert = true;           // следить за уроном своим зданиям
    public boolean attackAlertWalls = false;     // тревожить на стенах/дверях
    public boolean attackAlertOffscreen = true;  // учитывать события вне поля зрения
    public boolean attackAlertSound = true;
    public boolean attackAlertToast = true;
    public boolean attackAlertArrow = true;      // стрелки у края экрана
    public boolean attackAlertText = true;       // подписи у меток в мире
    public float attackAlertLife = 12f;          // сколько секунд живёт метка
    public int attackAlertMaxArrows = 6;         // максимум стрелок одновременно

    // Просвет зданий (MI2 Building X-ray + MinerTools BlockStatus)
    public boolean xray = false;                 // подписи содержимого зданий
    public boolean xrayDucts = false;            // цвет жидкости в трубах (Color-the-ducts)
    public float xrayDuctScale = 0.55f;          // размер квадрата в трубе (доля тайла)
    public boolean xrayAllTeams = false;         // показывать и чужие команды
    public boolean xrayPower = true;             // подписывать нехватку питания
    public boolean xrayZoomedOut = false;        // подписи при дальнем зуме

    // Поток ресурсов ядра (marcusbandit/resource-flow + MindustryTool Team Resource HUD)
    public boolean coreFlow = false;             // плашка «сколько + приход/сек»
    public boolean coreFlowRates = true;         // показывать скорость
    public boolean coreFlowOnlyMoving = false;   // только предметы с движением
    public int coreFlowColumns = 4;              // предметов в строке
    public float coreFlowX = 8f;                 // позиция плашки (слева)
    public float coreFlowY = 132f;               // позиция плашки (сверху)

    // График производства (vadymchan/mindustry-production-graph, Factorio-стиль)
    public boolean prodGraph = true;             // собирать статистику потока
    public int prodGraphWindow = 1;              // 0=60с, 1=5м, 2=10м
    public Seq<String> prodGraphItems = Seq.with("copper", "lead", "silicon");

    // Тревоги производства (MinerTools TurretAlert + Power-Grid-Minimap)
    public boolean alerts = false;               // сканировать проблемы производства
    public boolean alertsWorld = true;           // метки в мире
    public boolean alertsWholeMap = false;       // сканировать всю карту, а не только кадр
    public boolean alertsPower = true;           // класс «нет питания»
    public float alertsFullRatio = 0.95f;        // порог «склад забит»

    // Операторская камера (Caster-UI + MinerTools PlayerList + MI2 Multi-Team)
    public boolean spectateFollow = true;        // камера следует за выбранной целью
    public boolean coreLostAlert = true;         // уведомление о потере ядра
    public boolean coreLostFlash = true;         // вспышка экрана
    public boolean coreLostSound = true;
    public boolean coreLostSnap = false;         // прыжок камерой к потерянному ядру

    // История чата (MinerTools ChatTable + Extra Logging)
    public boolean chatLog = true;               // вести журнал чата

    // Панорамирование краем экрана (MI2-Utilities Edge Panning)
    public boolean edgePan = false;
    public float edgePanMargin = 14f;            // px от края
    public float edgePanSpeed = 1f;              // множитель скорости

    // ==================================================================
    // =======  ONIX-ПОРТ (v1.19): функции клиента Onix Lite 3.4.6  =====
    // ==================================================================
    // Источник поведения: Onix Lite (https://github.com/DiscoD3979/OnixLite, GPL-3.0) —
    // файлы features/changelog из Onix-Lite.jar и открытый GPL-3 код Foo's Client
    // (BalaM314/mindustry-mod), на котором Onix основан. Реализация написана заново
    // под архитектуру MKUKIC; бинарные данные Onix не использовались (они зашифрованы).

    public boolean onixEnabled = true;          // мастер-выключатель Onix-слоя

    // ---- Авто-Уни (в Onix — категория «Бой и авто») ----
    public boolean onixAutoUnit = false;        // водить собственный юнит по планам
    public boolean onixAutoBuild = false;       // выполнять очередь планов стройки
    public boolean onixAutoMine = false;        // плана нет -> обычный автомайн
    public boolean onixAutoMineMissing = true;  // не хватает ресурса -> добывать именно его
    public float onixAutoRadius = 700f;         // радиус поиска руды/целей, тайлы
    public boolean onixAutoDeliver = true;      // сдавать добытое в ядро
    public int onixAutoMaxPlans = 400;          // предел очереди планов
    public boolean onixAutoSkipUnreachable = true; // пропускать недостижимые планы
    public boolean onixShowAutoUnitPath = true;  // рисовать маршрут/метки Авто-Уни
    public boolean onixShowFillPath = true;      // рисовать линии рейсов Авто-Филла

    // ---- Авто-Филл ----
    public boolean onixAutofill = false;        // развоз ресурсов по «голодным» постройкам
    public boolean onixFillFromCore = true;     // брать из ядра
    public boolean onixFillFromStorage = true;  // брать из контейнеров/складов
    public boolean onixFillFromFactory = true;  // брать из переполненных цехов
    public int onixFillBatch = 40;              // предметов за рейс
    public float onixFillRadius = 900f;         // радиус развоза, тайлы
    public float onixFillFullRatio = 0.85f;     // почти полные постройки не доливать
    public Seq<String> onixFillItemPriority = new Seq<>();   // порядок предметов (пусто = авто)
    public Seq<String> onixFillBlockPriority = new Seq<>();  // порядок блоков (пусто = по нужде)

    // ---- Бот (Ассист / Антигрифер) ----
    public boolean onixBot = false;
    public int onixBotMode = 0;                 // 0 = Ассист, 1 = Антигрифер
    public String onixBotTarget = "";           // ник игрока (пусто = ближайший союзник)
    public float onixBotRadius = 320f;          // радиус работы вокруг цели
    public float onixBotOrbitSpeed = 1f;        // скорость кружения
    public boolean onixBotPath = true;          // визуал пути: пунктир, звезда, орбита
    public boolean onixBotDanger = true;        // красные зоны опасности
    public boolean onixBotOverheat = true;      // отступление от перегретых реакторов

    // ---- Определение клиентов Onix / Foo ----
    public boolean onixDetect = true;           // сканировать игроков
    public boolean onixDetectAlert = true;      // оповещение о найденных клиентах
    public boolean onixDetectTags = true;       // значок клиента в табе/чате/над головой
    public boolean onixDetectJoinWindow = true; // только короткое окно после захода
    public float onixDetectWindow = 20f;        // длина окна, секунд
    public int onixDetectSensitivity = 1;       // 0 мягко | 1 нормально | 2 строго
    public boolean onixDetectSelf = true;       // помечать себя как MKUKIC
    // v1.19.2: семейства клиентов (Onix / Onix Lite / Foo's) и значки в списке игроков
    public boolean onixDetectTabIcons = true;   // значок семейства в ванильном списке игроков (Tab)
    public boolean onixDetectSuspicious = false;// рисовать значки и «подозрительным» (не только клиентам)
    public boolean onixDetectConfidence = true; // добавлять «?» когда семейство угадано, а не подтверждено

    // ---- Аккаунты и «Обход бана» ----
    public boolean onixAccounts = true;
    public Seq<String> onixAccountList = new Seq<>(); // "label|name|uuid|usid"

    // ---- Чат: голосование, копирование, автоответчик ----
    public boolean onixChatVote = true;         // кнопки «vote y» / «vote n» под сообщением
    public boolean onixChatCopy = false;        // ПКМ по сообщению копирует текст с цветами
    public boolean onixCopyNickColors = true;   // ник копируется вместе с цветом
    public int onixCopyNickTagLimit = 99;       // лимит тегов [ в нике (Onix: без лимита)
    public boolean onixAfkReply = true;         // автоответчик АФК (в Onix включён по умолчанию)
    public String onixAfkText = "[accent]Я АФК, отвечу позже.";
    public float onixAfkCooldown = 12f;         // секунд между ответами одному игроку
    public boolean onixAfkOnlyNick = true;      // триггер — ник подстрокой в любом месте

    // ---- Консоль (F8 в Onix) ----
    public boolean onixConsole = true;
    public boolean onixConsolePersist = true;   // история переживает перезапуск
    public String onixConsoleLevel = "INFO";    // фильтр логов
    public boolean onixConsoleChatColors = true;

    // ---- Анимации и HUD ----
    public boolean onixAnims = true;            // мастер-переключатель анимаций
    public boolean onixSmoothChat = true;       // мягкое движение строк чата
    public boolean onixTypewriter = false;      // печатная машинка для новых сообщений
    public boolean onixSmoothCounters = true;   // мягкие счётчики HUD
    public boolean onixHudThemeColor = true;    // HUD функций в цвете темы

    // ---- Команды и панель ----
    public boolean onixCommands = true;         // чат-команды клиента
    public String onixCmdPrefix = "/m";         // префикс клиентских команд
    public boolean onixPanelSearch = true;      // поиск функций в меню (раскладки/транслит)
    public boolean onixHideUnavailable = false; // скрывать недоступные на сервере функции

    // ---- Обновления ----
    public boolean onixUpdateCheck = false;     // проверять релизы MKUKIC на GitHub
    public String onixUpdateRepo = "KUKICorig/MKukic";
    public boolean onixUpdateBackground = true; // качать в фоне, не блокируя игру
    public boolean onixLoadBar = true;          // фирменная полоса загрузки

    // ---- Прочее из Onix ----
    public boolean onixBlockServerWindows = false; // блокировка серверных окон и анти-спам
    public boolean onixStrictMode = false;         // не использовать телепорт-функции
    public boolean onixTextReplacer = false;       // подстановки %s %c %h в отправляемом тексте
    public String onixJoinText = "";               // текст при заходе на сервер
    public String onixWinText = "gg";
    public String onixLoseText = "";

    // ================= ПРОЧЕЕ =================
    public boolean enabled = true;          // глобальный выключатель клиент-фич

    // ---------------- сохранение ----------------
    private static final Json json = new Json();
    private static Fi file;
    private static Fi folder;
    private static final MKUKICSettings instance = new MKUKICSettings();

    public static MKUKICSettings s(){
        return instance;
    }

    /** Папка конфигов мода (для бэкапов и т.п.). Может быть null до init. */
    public static Fi dir(){
        return folder;
    }

    public static void init(Fi configFolder){
        json.setIgnoreUnknownFields(true);
        json.setOutputType(JsonWriter.OutputType.json);
        folder = configFolder;
        file = configFolder.child("settings.json");
    }

    public static void load(){
        if(file == null || !file.exists()) return;
        try{
            MKUKICSettings o = json.fromJson(MKUKICSettings.class, file.readString());
            if(o != null) json.copyFields(o, instance);
        }catch(Throwable t){
            Log.err("[MKUKIC] settings.json повреждён, использую значения по умолчанию", t);
        }
    }

    public static void save(){
        if(file == null) return;
        try{
            file.writeString(json.prettyPrint(json.toJson(instance, MKUKICSettings.class)), false);
        }catch(Throwable t){
            Log.err("[MKUKIC] не удалось сохранить settings.json", t);
        }
    }

    public static void reset(){
        try{
            json.copyFields(new MKUKICSettings(), instance);
            save();
        }catch(Throwable ignored){}
    }
}
