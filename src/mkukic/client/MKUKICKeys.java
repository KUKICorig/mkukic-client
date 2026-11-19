package mkukic.client;

import arc.*;
import arc.input.*;
import arc.util.*;

/**
 * Горячие клавиши мода. Регистрируются как обычные бинды игры,
 * поэтому их можно переназначить: Настройки -> Управление -> категория "mkukic".
 */
public class MKUKICKeys{
    public static final KeyBind
        menu      = KeyBind.add("mkukic_menu",      KeyCode.f9,  "mkukic"),
        aimbot    = KeyBind.add("mkukic_aimbot",    KeyCode.f10, "mkukic"),
        speed     = KeyBind.add("mkukic_speed",     KeyCode.f11, "mkukic"),
        esp       = KeyBind.add("mkukic_esp",       KeyCode.f12, "mkukic"),
        godmode   = KeyBind.add("mkukic_godmode",   KeyCode.g,   "mkukic"),
        fly       = KeyBind.add("mkukic_fly",       KeyCode.unset, "mkukic"),
        aiFollow  = KeyBind.add("mkukic_ai_follow", KeyCode.unset, "mkukic"),
        aiMine    = KeyBind.add("mkukic_ai_mine",   KeyCode.unset, "mkukic"),
        aiBuild   = KeyBind.add("mkukic_ai_build",  KeyCode.unset, "mkukic"),
        selectAll = KeyBind.add("mkukic_select_all",KeyCode.unset, "mkukic"),
        hq        = KeyBind.add("mkukic_hq",        KeyCode.f8,  "mkukic"),   // тактический штаб
        coinMagnet= KeyBind.add("mkukic_coin_magnet",KeyCode.f7, "mkukic"),   // магнит монет
        mapGen    = KeyBind.add("mkukic_mapgen",    KeyCode.f6,  "mkukic"),   // генератор карт (в редакторе)
        qolHub    = KeyBind.add("mkukic_qol_hub",   KeyCode.f4,  "mkukic"),   // хаб плавающих окон QoL
        buildWheel= KeyBind.add("mkukic_build_wheel",KeyCode.f2, "mkukic"),   // радиальное колесо строительства
        quickMsg  = KeyBind.add("mkukic_quick_msg", KeyCode.v,    "mkukic"),   // колесо быстрых сообщений
        teamSwap  = KeyBind.add("mkukic_team_swap", KeyCode.k, "mkukic"),     // цикл угона юнитов вкл/выкл
        teamSwapOne    = KeyBind.add("mkukic_team_swap_one",    KeyCode.unset, "mkukic"), // режим: один юнит
        teamSwapAll    = KeyBind.add("mkukic_team_swap_all",    KeyCode.unset, "mkukic"), // режим: все юниты
        teamSwapStrong = KeyBind.add("mkukic_team_swap_strong", KeyCode.unset, "mkukic"), // режим: только сильные
        formMove  = KeyBind.add("mkukic_form_move", KeyCode.f3,  "mkukic"),   // отправить выделенных юнитов формацией к курсору
        markerAdd = KeyBind.add("mkukic_marker_add", KeyCode.unset, "mkukic"),// маркер карты у курсора (назначается вручную)
        coinPilot = KeyBind.add("mkukic_coin_pilot", KeyCode.unset, "mkukic"),// автопилот до монет (назначается вручную)
        boardNearest = KeyBind.add("mkukic_board_nearest", KeyCode.n, "mkukic"), // сесть в ближайшего юнита / повторно — выйти
        // v1.16: операторская камера, алерты атак, просвет зданий, поток ресурсов
        specNext  = KeyBind.add("mkukic_spec_next", KeyCode.rightBracket, "mkukic"), // следующий игрок
        specPrev  = KeyBind.add("mkukic_spec_prev", KeyCode.leftBracket,  "mkukic"), // предыдущий игрок
        specFollow= KeyBind.add("mkukic_spec_follow", KeyCode.f1,         "mkukic"), // следить / не следить
        coreNext  = KeyBind.add("mkukic_core_next", KeyCode.unset,        "mkukic"), // следующее ядро
        attackNext= KeyBind.add("mkukic_attack_next", KeyCode.unset,      "mkukic"), // камера к последней атаке
        xrayKey   = KeyBind.add("mkukic_xray",      KeyCode.unset,        "mkukic"), // просвет зданий
        coreFlowKey = KeyBind.add("mkukic_core_flow", KeyCode.unset,      "mkukic"), // плашка потока ресурсов
        alertsKey = KeyBind.add("mkukic_alerts",    KeyCode.unset,        "mkukic"), // окно тревог производства
        panic     = KeyBind.add("mkukic_panic",     KeyCode.unset, "mkukic"), // мгновенно выключить всё
        // v1.19 (порт Onix Lite): консоль, авто-уни, авто-филл, бот, навигация
        onixConsole = KeyBind.add("mkukic_onix_console", KeyCode.f5,        "mkukic"), // консоль Onix (в Onix — F8, у нас F8 занят штабом)
        onixAutoBuild = KeyBind.add("mkukic_onix_auto_build", KeyCode.unset, "mkukic"), // {auto_build}
        onixAutoMine = KeyBind.add("mkukic_onix_auto_mine", KeyCode.unset,   "mkukic"), // {auto_mine}
        onixAutofill = KeyBind.add("mkukic_onix_autofill", KeyCode.unset,    "mkukic"), // авто-филл
        onixBotKey = KeyBind.add("mkukic_onix_bot", KeyCode.z,               "mkukic"), // бот (в Onix — Z)
        onixNavCursor = KeyBind.add("mkukic_onix_nav_cursor", KeyCode.unset, "mkukic"), // {navigate_to_cursor}
        onixStopPath = KeyBind.add("mkukic_onix_stop_path", KeyCode.unset,   "mkukic"), // {stop_following_path}
        onixVoteYes = KeyBind.add("mkukic_onix_vote_yes", KeyCode.unset,     "mkukic"), // vote y
        onixVoteNo = KeyBind.add("mkukic_onix_vote_no", KeyCode.unset,       "mkukic"), // vote n
        onixDetectKey = KeyBind.add("mkukic_onix_detect", KeyCode.unset,     "mkukic"), // пересканировать клиентов
        onixChatComplete = KeyBind.add("mkukic_onix_chat_complete", KeyCode.unset, "mkukic"); // {chat_autocomplete}

    public static void init(){
        // бинды уже зарегистрированы статически; здесь можно прогреть их
        Log.info("[MKUKIC] горячие клавиши: F9 меню, F10 автоприцел, F11 скорость, F12 ESP, G бессмертие, F8 штаб, F7 магнит, "
            + "F6 генератор карт, F4 QoL-хаб, F2 колесо блоков, V колесо сообщений, F3 формация-ход, F1 операторская камера, кв. скобки — листание игроков");
    }

    public static boolean tap(KeyBind b){
        try{
            return b != null && !b.isUnset() && Core.input.keyTap(b);
        }catch(Throwable t){
            return false;
        }
    }

    public static String describe(){
        StringBuilder sb = new StringBuilder();
        sb.append("F9 — меню MKUKIC\n");
        sb.append("Shift+F9 — быстрые переключатели (плитки всех основных функций, поиск)\n");
        sb.append("F10 — автоприцел\n");
        sb.append("F11 — баф скорости\n");
        sb.append("F12 — ESP/радар\n");
        sb.append("F8 — тактический штаб (карта + прогноз волн)\n");
        sb.append("F7 — магнит монет\n");
        sb.append("F4 — хаб окон (плавающие окна: правила, волны, энергосети, рецепты, поиск по карте...)\n");
        sb.append("F2 — радиальное колесо строительства\n");
        sb.append("V — колесо быстрых сообщений (клик по сообщению — отправка в чат)\n");
        sb.append("K — цикл угона юнитов через /team (режимы — отдельные клавиши в Управлении)\n");
        sb.append("N — сесть в ближайшего юнита; ещё раз N — выйти из юнита\n");
        sb.append("F3 — отправить выделенных юнитов формацией к курсору\n");
        sb.append("F1 — операторская камера: следить/не следить за целью\n");
        sb.append("«[» и «]» — предыдущий/следующий игрок для наблюдения\n");
        sb.append("Маркер у курсора — назначь клавишу в Настройки → Управление → mkukic (mkukic_marker_add)\n");
        sb.append("G — бессмертие\n");
        sb.append("F5 — консоль Onix (Чат / Действия / Логи / Код)\n");
        sb.append("Z — бот (Ассист / Антигрифер)\n");
        sb.append("Onix-функции без клавиши по умолчанию: авто-стройка, авто-копка, авто-филл,\n");
        sb.append("  идти к курсору, остановить путь, vote y / vote n, детект клиентов, автодополнение чата\n");
        sb.append("Остальное можно назначить в Настройки -> Управление -> mkukic");
        return sb.toString();
    }
}
