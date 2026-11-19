package mkukic.server;

import arc.*;
import arc.math.*;
import arc.struct.*;
import arc.util.*;
import mindustry.content.*;
import mindustry.core.*;
import mindustry.entities.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.maps.*;
import mindustry.net.Administration.*;
import mindustry.net.Packets.*;
import mindustry.type.*;
import mindustry.ui.*;
import mindustry.world.blocks.storage.CoreBlock.*;
import mkukic.*;
import mkukic.server.ServerData.*;

import static mindustry.Vars.*;

/**
 * Серверные чат-команды MKUKIC.
 * Работают, если мод установлен на сервере (или вы хост).
 *
 * Формат, как просили: /nban <игрок> <минуты|true> <причина...>
 */
public class ServerCommands{
    private static boolean registered = false;

    public static void register(arc.util.CommandHandler handler){
        if(handler == null || registered) return;
        registered = true;

        // ---------- справка ----------
        handler.<Player>register("nhelp", "[страница]", "Список команд MKUKIC.", (args, player) -> {
            int page = args.length > 0 && Strings.canParseInt(args[0]) ? Mathf.clamp(Strings.parseInt(args[0]), 1, 4) : 1;
            String[][] pages = {
                {
                    "[accent]MKUKIC: игровые команды[]",
                    "[white]/shop[] — [lightgray]магазин (нужны кредиты)",
                    "[white]/buy <id>[] — [lightgray]купить товар",
                    "[white]/credits[] — [lightgray]свои кредиты",
                    "[white]/stats[] — [lightgray]статистика сервера и игроков",
                    "[white]/stats <игрок>[] — [lightgray]карточка игрока",
                    "[white]/history [игрок] [n][] — [lightgray]журнал событий",
                    "[white]/nversion[] — [lightgray]версия мода на сервере",
                    "[accent]MKUKIC v1.16: общение и карты[]",
                    "[white]/w <игрок> <текст>[] — [lightgray]личное сообщение (шепот)",
                    "[white]/r <текст>[] — [lightgray]ответить на последний шепот",
                    "[white]/maps [страница][] — [lightgray]список карт сервера",
                    "[white]/rtv <номер|имя>[] — [lightgray]голосовать за смену карты",
                    "[white]/pinfo [игрок][] — [lightgray]карточка игрока",
                    "[white]/sync [игрок][] — [lightgray]переотправить мир (модератор+)",
                },
                {
                    "[accent]MKUKIC: модерация[] (уровень 1+)",
                    "[white]/warn <игрок> <причина>[] — [lightgray]предупреждение",
                    "[white]/nkick <игрок> [true] [причина][] — [lightgray]кик",
                    "[white]/nban <игрок> <минуты|true> [причина][] — [lightgray]бан",
                    "[white]/pardon <игрок>[] — [lightgray]разбан",
                    "[white]/bans[] — [lightgray]список банов",
                    "[white]/notes <игрок> [текст][] — [lightgray]заметки об игроке",
                    "[white]/mute <игрок> <минуты>[] — [lightgray]запрет чата",
                },
                {
                    "[accent]MKUKIC: админ[] (уровень 2+)",
                    "[white]/spawnunit <тип> [кол-во] [here|core] [команда][]",
                    "[white]/give <ресурс> <кол-во>[] — [lightgray]ресурсы в ваше ядро",
                    "[white]/giveall <ресурс> <кол-во>[]",
                    "[white]/heal [игрок|all][] — [lightgray]лечение",
                    "[white]/tp <игрок> [к игроку][] — [lightgray]телепорт",
                    "[white]/wave[] — [lightgray]пропустить волну",
                    "[white]/gameover <win|lose>[]",
                    "[white]/balance[] — [lightgray]уравнять команды",
                },
                {
                    "[accent]MKUKIC: владелец[] (уровень 3+)",
                    "[white]/setlevel <игрок> <0-4>[] — [lightgray]роль (0 игрок, 1 модер, 2 админ, 3 старший, 4 владелец)",
                    "[white]/roles[] — [lightgray]роли всех онлайн-игроков",
                    "[white]/credits <игрок> <кол-во>[] — [lightgray]начислить/снять кредиты",
                    "[white]/reload[] — [lightgray]перезагрузить мир",
                    "[white]/nconfig <ключ> [значение][] — [lightgray]настройки серверной части",
                },
            };
            StringBuilder sb = new StringBuilder(pages[page - 1][0]).append('\n');
            for(int i = 1; i < pages[page - 1].length; i++) sb.append(pages[page - 1][i]).append('\n');
            sb.append("[darkgray]страница ").append(page).append("/").append(pages.length).append(" — /nhelp <страница>");
            say(player, sb.toString());
        });

        handler.<Player>register("nversion", "Версия MKUKIC на сервере.", (args, player) ->
            say(player, "[accent]MKUKIC Server v" + MKUKICMod.version + "[lightgray], Mindustry build " + Version.build));

        // ---------- статистика ----------
        handler.<Player>register("stats", "[игрок]", "Статистика сервера или игрока.", (args, player) -> {
            if(args.length > 0){
                String id = ServerData.findId(args[0]);
                Player target = ServerData.find(args[0]);
                if(id == null && target == null){
                    say(player, "[scarlet]Игрок не найден: " + args[0]);
                    return;
                }
                if(id == null) id = target.uuid();
                Rec r = ServerData.rec(id);
                int lvl = ServerData.level(target != null ? target : player);
                StringBuilder sb = new StringBuilder();
                sb.append("[accent]Карточка игрока[lightgray]\n");
                sb.append("ник: [white]").append(r.lastName).append('\n');
                sb.append("id: [white]").append(id).append('\n');
                if(lvl >= 2) sb.append("ip: [white]").append(r.lastIP).append('\n');
                sb.append("роль: [white]").append(ServerData.roleName(r.level)).append('\n');
                sb.append("заходов: [white]").append(r.joins).append("  |  киков: [white]").append(r.kicks)
                    .append("  |  предупреждений: [white]").append(r.warns).append('\n');
                sb.append("кредиты: [accent]").append(r.credits).append('\n');
                sb.append("последний визит: [white]").append(r.lastSeen == 0 ? "не заходил" : ServerData.time()).append('\n');
                if(r.banUntil > System.currentTimeMillis()){
                    sb.append("[scarlet]БАН до ").append(r.banUntil == Long.MAX_VALUE ? "навсегда" : new java.util.Date(r.banUntil)).append(": ").append(r.banReason);
                }
                if(lvl >= 1 && !r.notes.isEmpty()) sb.append("\n[orange]заметки: [lightgray]").append(r.notes);
                if(target != null && !target.dead() && target.unit() != null){
                    sb.append("\n[lightgray]сейчас: ").append(target.unit().type.localizedName)
                        .append(", HP ").append((int)target.unit().health()).append('/').append((int)target.unit().maxHealth())
                        .append(", команда ").append(target.team().name)
                        .append(", x").append(World.toTile(target.x)).append(" y").append(World.toTile(target.y));
                }
                say(player, sb.toString());
                return;
            }

            int lvl = ServerData.level(player);
            StringBuilder sb = new StringBuilder();
            sb.append("[accent]Статистика сервера[lightgray]\n");
            sb.append("карта: [white]").append(state.map.name()).append("  |  режим: [white]").append(state.rules.mode().name()).append('\n');
            sb.append("волна: [white]").append(state.wave).append("  |  игроков: [white]").append(Groups.player.size())
                .append("  |  юнитов: [white]").append(Groups.unit.size()).append('\n');
            sb.append("лимит юнитов: [white]").append(Units.getStringCap(player.team())).append('\n');
            CoreBuild core = player.bestCore();
            if(core != null && core.items != null){
                sb.append("ядро: ");
                for(Item item : content.items()){
                    int a = core.items.get(item);
                    if(a > 0) sb.append("[white]").append(a).append("[] ").append(item.emoji()).append(' ');
                }
                sb.append('\n');
            }
            sb.append("[lightgray]Онлайн:\n");
            for(String[] row : ServerData.onlineList()){
                sb.append("  [white]").append(row[0]).append("[] [darkgray](").append(row[1].substring(0, Math.min(6, row[1].length())))
                    .append(")[] ").append(row[2]).append("  ").append(row[3]);
                if(lvl >= 1) sb.append("  [darkgray]кр:").append(row[4]).append(" ").append(row[6]);
                sb.append('\n');
            }
            say(player, sb.toString());
        });

        // ---------- история ----------
        handler.<Player>register("history", "[игрок] [кол-во]", "Журнал событий сервера (уровень 1+).", (args, player) -> {
            if(!check(player, 1, "history")) return;
            int limit = 20;
            String query = "";
            if(args.length > 0){
                if(Strings.canParseInt(args[0])) limit = Mathf.clamp(Strings.parseInt(args[0]), 1, 100);
                else query = args[0];
            }
            if(args.length > 1 && Strings.canParseInt(args[1])) limit = Mathf.clamp(Strings.parseInt(args[1]), 1, 100);

            Seq<String> found = ServerData.searchLog(query, limit);
            if(found.isEmpty()){
                say(player, "[lightgray]Ничего не найдено" + (query.isEmpty() ? "" : " по запросу '" + query + "'"));
                return;
            }
            StringBuilder sb = new StringBuilder("[accent]История событий[]\n");
            for(String line : found) sb.append("[lightgray]").append(line.replace("|", " | ")).append('\n');
            say(player, sb.toString());
        });

        // ---------- магазин ----------
        handler.<Player>register("shop", "[log]", "Магазин за кредиты.", (args, player) -> {
            if(args.length > 0 && args[0].equals("log")){
                say(player, "[lightgray]Покупки сохраняются в журнале сервера: /history " + player.plainName());
                return;
            }
            Rec r = ServerData.rec(player);
            StringBuilder sb = new StringBuilder();
            sb.append("[accent]Магазин MKUKIC[]  [lightgray]ваши кредиты: [accent]").append(r.credits).append('\n');
            if(ServerData.shop.isEmpty()){
                sb.append("[darkgray]каталог пуст");
            }else{
                for(ShopItem it : ServerData.shop){
                    sb.append("[white]").append(it.id).append("[] — ").append(it.title)
                        .append(" [accent]").append(it.price).append(" кр.[]  (/buy ").append(it.id).append(")\n");
                }
            }
            sb.append("[darkgray]Кредиты капают за игру: ").append(ServerData.cfg.creditsPerMinute).append("/мин");
            say(player, sb.toString());
        });

        handler.<Player>register("buy", "<номер>", "Купить товар в магазине.", (args, player) -> {
            ShopItem item = ServerData.shop.find(i -> i.id.equalsIgnoreCase(args[0]));
            if(item == null){
                say(player, "[scarlet]Нет такого товара: " + args[0] + ". Список: /shop");
                return;
            }
            Rec r = ServerData.rec(player);
            if(r.credits < item.price){
                say(player, "[scarlet]Недостаточно кредитов: нужно " + item.price + ", у вас " + r.credits);
                return;
            }
            if(doShopAction(player, item)){
                r.credits -= item.price;
                ServerData.logPlayer(player, "shop", "купил " + item.id + " за " + item.price);
                ServerData.savePlayers();
                say(player, "[lime]Куплено: [white]" + item.title + "[lime]. Осталось кредитов: [accent]" + r.credits);
                ServerNet.answer(player, "info", infoPayload(player));
            }
        });

        handler.<Player>register("credits", "[игрок]", "Свои кредиты (или кредиты игрока для админов).", (args, player) -> {
            if(args.length > 0 && ServerData.level(player) >= 1){
                String id = ServerData.findId(args[0]);
                if(id == null){
                    say(player, "[scarlet]Игрок не найден");
                    return;
                }
                say(player, "[lightgray]" + ServerData.rec(id).lastName + ": [accent]" + ServerData.rec(id).credits + " кредитов");
                return;
            }
            say(player, "[lightgray]Ваши кредиты: [accent]" + ServerData.rec(player).credits);
        });

        // ---------- модерация ----------
        handler.<Player>register("warn", "<игрок> <причина...>", "Предупредить игрока (уровень 1+).", (args, player) -> {
            if(!check(player, 1, "warn")) return;
            Player target = ServerData.find(args[0]);
            if(target == null){
                say(player, "[scarlet]Игрок не найден: " + args[0]);
                return;
            }
            if(!canAct(player, target)){
                say(player, "[scarlet]Нельзя применить к игроку с более высокой ролью.");
                return;
            }
            Rec r = ServerData.rec(target);
            r.warns++;
            ServerData.savePlayers();
            target.sendMessage("[scarlet]Предупреждение от администрации: [white]" + args[1]);
            announceAdmin(target.plainName() + " получил предупреждение (" + r.warns + "): " + args[1]);
            ServerData.logPlayer(player, "warn", "-> " + target.plainName() + ": " + args[1]);
            say(player, "[lime]Предупреждение выдано. Всего: " + r.warns);
        });

        handler.<Player>register("nkick", "<игрок> [админ] [причина...]", "Кикнуть игрока (уровень 1+).", (args, player) -> {
            if(!check(player, 1, "nkick")) return;
            Player target = ServerData.find(args[0]);
            if(target == null){
                say(player, "[scarlet]Игрок не найден: " + args[0]);
                return;
            }
            if(!canAct(player, target)){
                say(player, "[scarlet]Нельзя кикнуть игрока с более высокой ролью.");
                return;
            }
            int shift = (args.length > 1 && (args[1].equals("true") || Strings.canParseInt(args[1]))) ? 1 : 0;
            String reason = args.length > 1 + shift ? args[1 + shift] : "kick by admin";

            ServerData.rec(target).kicks++;
            ServerData.savePlayers();
            ServerData.logPlayer(player, "kick", "-> " + target.plainName() + " [" + target.uuid() + "]: " + reason);
            target.kick("[scarlet]" + reason, NetServer.kickDuration);
            if(netServer != null) netServer.admins.handleKicked(target.uuid(), target.ip(), NetServer.kickDuration);
            announceAdmin(target.plainName() + " кикнут: " + reason);
            say(player, "[lime]" + target.plainName() + " кикнут.");
        });

        handler.<Player>register("nban", "<игрок> <минуты|true> [причина...]", "Забанить игрока (уровень 2+). true/0 = навсегда.", (args, player) -> {
            if(!check(player, 2, "nban")) return;
            Player target = ServerData.find(args[0]);
            String id = target != null ? target.uuid() : ServerData.findId(args[0]);
            if(id == null){
                say(player, "[scarlet]Игрок не найден: " + args[0]);
                return;
            }
            if(target != null && !canAct(player, target)){
                say(player, "[scarlet]Нельзя забанить игрока с более высокой ролью.");
                return;
            }

            long minutes = 0;
            if(args.length > 1){
                if(args[1].equals("true") || args[1].equals("0")) minutes = 0;
                else if(Strings.canParseInt(args[1])) minutes = Strings.parseInt(args[1]);
                else{
                    say(player, "[scarlet]Второй аргумент — минуты или 'true' (навсегда). Пример: /nban Nick 60 причина");
                    return;
                }
            }
            String reason = args.length > 2 ? args[2] : "ban by admin";

            Rec r = ServerData.rec(id);
            r.banReason = reason;
            r.banUntil = minutes <= 0 ? Long.MAX_VALUE : System.currentTimeMillis() + minutes * 60_000L;
            r.kicks++;
            ServerData.savePlayers();
            ServerData.logPlayer(player, "ban", "-> " + r.lastName + " [" + id + "] " + (minutes <= 0 ? "навсегда" : minutes + " мин") + ": " + reason);

            if(netServer != null){
                netServer.admins.banPlayerID(id);
                if(target != null && target.con != null) netServer.admins.banPlayerIP(target.con.address);
            }
            if(target != null){
                target.sendMessage("[scarlet]Вы забанены: [white]" + reason);
                target.kick(KickReason.banned);
            }
            announceAdmin(r.lastName + " забанен (" + (minutes <= 0 ? "навсегда" : minutes + " мин") + "): " + reason);
            say(player, "[lime]Бан выдан: " + r.lastName);
        });

        handler.<Player>register("pardon", "<игрок|id>", "Разбанить игрока (уровень 2+).", (args, player) -> {
            if(!check(player, 2, "pardon")) return;
            String id = ServerData.findId(args[0]);
            if(id == null) id = args[0];
            Rec r = ServerData.rec(id);
            r.banUntil = 0L;
            r.banReason = "";
            ServerData.savePlayers();
            if(netServer != null){
                netServer.admins.unbanPlayerID(id);
                if(!r.lastIP.isEmpty()) netServer.admins.unbanPlayerIP(r.lastIP);
            }
            ServerData.logPlayer(player, "pardon", "-> " + r.lastName + " [" + id + "]");
            say(player, "[lime]Разбан: " + r.lastName);
        });

        handler.<Player>register("bans", "Список банов MKUKIC (уровень 1+).", (args, player) -> {
            if(!check(player, 1, "bans")) return;
            StringBuilder sb = new StringBuilder("[accent]Баны MKUKIC[]\n");
            int n = 0;
            for(var e : ServerData.players.entries()){
                Rec r = e.value;
                if(r.banUntil > System.currentTimeMillis()){
                    n++;
                    sb.append("[white]").append(r.lastName).append("[] [darkgray]").append(shortId(e.key))
                        .append("[] до ").append(r.banUntil == Long.MAX_VALUE ? "навсегда" : new java.util.Date(r.banUntil).toString())
                        .append(" — ").append(r.banReason).append('\n');
                }
            }
            if(netServer != null){
                for(PlayerInfo info : netServer.admins.getBanned()){
                    sb.append("[lightgray]vanilla: ").append(info.lastName).append(" [darkgray]").append(info.id).append('\n');
                    n++;
                }
            }
            if(n == 0) sb.append("[darkgray]пусто");
            say(player, sb.toString());
        });

        handler.<Player>register("notes", "<игрок> [текст...]", "Заметки об игроке (уровень 1+).", (args, player) -> {
            if(!check(player, 1, "notes")) return;
            String id = ServerData.findId(args[0]);
            if(id == null){
                say(player, "[scarlet]Игрок не найден");
                return;
            }
            Rec r = ServerData.rec(id);
            if(args.length < 2){
                say(player, "[lightgray]Заметки о " + r.lastName + ": " + (r.notes.isEmpty() ? "[darkgray]нет" : r.notes));
            }else{
                r.notes = args[1];
                ServerData.savePlayers();
                say(player, "[lime]Заметки сохранены.");
            }
        });

        handler.<Player>register("mute", "<игрок> <минуты>", "Запретить чат (уровень 1+).", (args, player) -> {
            if(!check(player, 1, "mute")) return;
            Player target = ServerData.find(args[0]);
            if(target == null || !Strings.canParseInt(args[1])){
                say(player, "[scarlet]Использование: /mute <игрок> <минуты>");
                return;
            }
            Anticheat.mute(target, Strings.parseInt(args[1]) * 60_000L);
            ServerData.logPlayer(player, "mute", "-> " + target.plainName() + " на " + args[1] + " мин");
            say(player, "[lime]" + target.plainName() + " заглушён на " + args[1] + " мин.");
        });

        // ---------- админ-действия ----------
        handler.<Player>register("spawnunit", "<юнит> [кол-во] [here|core] [команда]", "Создать юнитов (уровень 2+).", (args, player) -> {
            if(!check(player, 2, "spawnunit")) return;
            UnitType type = content.units().find(u -> u.name.equalsIgnoreCase(args[0]) ||
                (u.localizedName != null && u.localizedName.equalsIgnoreCase(args[0])));
            if(type == null){
                say(player, "[scarlet]Неизвестный юнит: " + args[0]);
                return;
            }
            int count = args.length > 1 && Strings.canParseInt(args[1]) ? Mathf.clamp(Strings.parseInt(args[1]), 1, 200) : 1;
            String where = args.length > 2 ? args[2] : "here";
            Team team = player.team();
            if(args.length > 3){
                Team t2 = teamByName(args[3]);
                if(t2 != null) team = t2;
            }

            float x = player.x, y = player.y;
            if(where.equals("core")){
                CoreBuild core = player.bestCore();
                if(core != null){
                    x = core.x; y = core.y;
                }
            }

            int n = 0;
            for(int i = 0; i < count; i++){
                if(!Units.canCreate(team, type)){
                    say(player, "[scarlet]Достигнут лимит юнитов команды.");
                    break;
                }
                Unit u = type.create(team);
                u.set(x + Mathf.range(24f), y + Mathf.range(24f));
                u.rotation(Mathf.random(360f));
                u.add();
                Units.notifyUnitSpawn(u);
                n++;
            }
            ServerData.logPlayer(player, "spawnunit", type.name + " x" + n);
            say(player, "[lime]Создано юнитов: " + n + " (" + type.localizedName + ")");
        });

        handler.<Player>register("give", "<ресурс> <кол-во>", "Выдать ресурсы в своё ядро (уровень 2+).", (args, player) -> {
            if(!check(player, 2, "give")) return;
            giveItems(player, args, 1);
        });

        handler.<Player>register("giveall", "<ресурс> <кол-во>", "Выдать ресурсы всем командам (уровень 2+).", (args, player) -> {
            if(!check(player, 2, "giveall")) return;
            giveItems(player, args, 2);
        });

        handler.<Player>register("heal", "[игрок|all]", "Вылечить игрока, команду или всех (уровень 1+).", (args, player) -> {
            if(!check(player, 1, "heal")) return;
            if(args.length == 0 || args[0].equalsIgnoreCase("me")){
                healPlayer(player);
                say(player, "[lime]Вы вылечены.");
                return;
            }
            if(args[0].equalsIgnoreCase("all")){
                Groups.player.each(p -> healPlayer(p));
                announceAdmin("все игроки вылечены");
                return;
            }
            if(args[0].equalsIgnoreCase("team")){
                Groups.player.each(p -> p.team() == player.team(), p -> healPlayer(p));
                say(player, "[lime]Команда вылечена.");
                return;
            }
            Player target = ServerData.find(args[0]);
            if(target == null){
                say(player, "[scarlet]Игрок не найден");
                return;
            }
            healPlayer(target);
            say(player, "[lime]" + target.plainName() + " вылечен.");
        });

        handler.<Player>register("tp", "<игрок> [к-игроку]", "Телепорт (уровень 1+).", (args, player) -> {
            if(!check(player, 1, "tp")) return;
            Player a = ServerData.find(args[0]);
            if(a == null){
                say(player, "[scarlet]Игрок не найден: " + args[0]);
                return;
            }
            if(args.length > 1){
                Player b = ServerData.find(args[1]);
                if(b == null){
                    say(player, "[scarlet]Игрок не найден: " + args[1]);
                    return;
                }
                if(!a.dead() && a.unit() != null) a.unit().set(b.x, b.y);
                say(player, "[lime]" + a.plainName() + " телепортирован к " + b.plainName());
            }else{
                if(player.dead() || player.unit() == null){
                    say(player, "[scarlet]Вы мертвы — телепорт невозможен");
                    return;
                }
                player.unit().set(a.x, a.y);
                say(player, "[lime]Вы телепортированы к " + a.plainName());
            }
            ServerData.logPlayer(player, "tp", args[0]);
        });

        handler.<Player>register("tphere", "<игрок>", "Телепортировать игрока к себе (уровень 1+).", (args, player) -> {
            if(!check(player, 1, "tphere")) return;
            Player target = ServerData.find(args[0]);
            if(target == null || target.dead() || target.unit() == null){
                say(player, "[scarlet]Игрок не найден или мёртв");
                return;
            }
            target.unit().set(player.x, player.y);
            say(player, "[lime]" + target.plainName() + " телепортирован к вам.");
        });

        handler.<Player>register("wave", "Пропустить волну (уровень 1+).", (args, player) -> {
            if(!check(player, 1, "wave")) return;
            logic.skipWave();
            ServerData.logPlayer(player, "wave", "пропустил волну");
            say(player, "[lime]Волна пропущена.");
        });

        handler.<Player>register("gameover", "<win|lose>", "Завершить игру (уровень 3+).", (args, player) -> {
            if(!check(player, 3, "gameover")) return;
            Team winner = args[0].equalsIgnoreCase("lose") ? state.rules.waveTeam : player.team();
            Logic.gameOver(winner);
            ServerData.logPlayer(player, "gameover", args[0]);
        });

        handler.<Player>register("balance", "Уравнять команды (уровень 2+).", (args, player) -> {
            if(!check(player, 2, "balance")) return;
            Seq<Player> list = new Seq<>();
            Groups.player.each(list::add);
            int i = 0;
            for(Player p : list){
                p.team(Team.baseTeams[i++ % Team.baseTeams.length]);
            }
            announceAdmin("команды уравнены");
        });

        // ---------- роли и экономика ----------
        handler.<Player>register("setlevel", "<игрок> <0-4>", "Назначить роль (уровень 3+).", (args, player) -> {
            if(!check(player, 3, "setlevel")) return;
            String id = ServerData.findId(args[0]);
            if(id == null || !Strings.canParseInt(args[1])){
                say(player, "[scarlet]Использование: /setlevel <игрок> <0-4>");
                return;
            }
            int lvl = Mathf.clamp(Strings.parseInt(args[1]), 0, 4);
            if(lvl >= ServerData.level(player) && lvl != 0){
                say(player, "[scarlet]Нельзя выдать роль выше своей.");
                return;
            }
            Rec r = ServerData.rec(id);
            r.level = lvl;
            ServerData.savePlayers();
            ServerData.logPlayer(player, "role", "-> " + r.lastName + " = " + ServerData.roleName(lvl));
            say(player, "[lime]" + r.lastName + " теперь [accent]" + ServerData.roleName(lvl));

            Player target = ServerData.find(args[0]);
            if(target != null){
                target.admin = lvl >= 2;
                target.sendMessage("[accent]Ваша роль MKUKIC: " + ServerData.roleName(lvl) + "[lightgray]. Перезайдите, чтобы игровой клиент подхватил права админа.");
            }
        });

        handler.<Player>register("roles", "Роли онлайн-игроков (уровень 1+).", (args, player) -> {
            if(!check(player, 1, "roles")) return;
            StringBuilder sb = new StringBuilder("[accent]Роли[]\n");
            for(Player p : Groups.player){
                sb.append("[white]").append(p.plainName()).append("[] — ").append(ServerData.roleName(ServerData.level(p)))
                    .append(" [darkgray](").append(ServerData.level(p)).append(")[]\n");
            }
            say(player, sb.toString());
        });

        handler.<Player>register("setcredits", "<игрок> <кол-во>", "Изменить кредиты игрока (уровень 2+).", (args, player) -> {
            if(ServerData.level(player) < 2 && args.length > 1){
                say(player, "[scarlet]Нужен уровень 2+");
                return;
            }
            if(args.length < 2){
                say(player, "[lightgray]Ваши кредиты: [accent]" + ServerData.rec(player).credits);
                return;
            }
            String id = ServerData.findId(args[0]);
            if(id == null || !Strings.canParseInt(args[1])){
                say(player, "[scarlet]Использование: /setcredits <игрок> <кол-во>");
                return;
            }
            Rec r = ServerData.rec(id);
            r.credits = Math.max(0, r.credits + Strings.parseInt(args[1]));
            ServerData.savePlayers();
            ServerData.logPlayer(player, "credits", "-> " + r.lastName + " " + args[1]);
            say(player, "[lime]" + r.lastName + ": " + r.credits + " кредитов");
        });

        handler.<Player>register("ngod", "[on|off]", "Серверное бессмертие для себя (уровень 2+): сервер сам держит HP полным.", (args, player) -> {
            if(ServerData.level(player) < 2){
                say(player, "[scarlet]Нужен уровень 2+");
                return;
            }
            boolean on = args.length > 0 ? args[0].equalsIgnoreCase("on")
                : !ServerData.godIds.contains(player.uuid());
            if(on) ServerData.godIds.add(player.uuid()); else ServerData.godIds.remove(player.uuid());
            ServerData.logPlayer(player, "ngod", on ? "on" : "off");
            say(player, on ? "[yellow]Серверное бессмертие: ВКЛ[]" : "[lightgray]Серверное бессмертие: выкл[]");
        });

        handler.<Player>register("nrage", "[on|off]", "Серверная ярость x2 урон/перезарядка для себя (уровень 2+).", (args, player) -> {
            if(ServerData.level(player) < 2){
                say(player, "[scarlet]Нужен уровень 2+");
                return;
            }
            boolean on = args.length > 0 ? args[0].equalsIgnoreCase("on")
                : !ServerData.rageIds.contains(player.uuid());
            if(on) ServerData.rageIds.add(player.uuid()); else ServerData.rageIds.remove(player.uuid());
            ServerData.logPlayer(player, "nrage", on ? "on" : "off");
            say(player, on ? "[scarlet]Серверная ярость x2: ВКЛ[]" : "[lightgray]Серверная ярость: выкл[]");
        });

        handler.<Player>register("reload", "Перезагрузить мир (уровень 3+).", (args, player) -> {
            if(!check(player, 3, "reload")) return;
            ServerData.logPlayer(player, "reload", "перезагрузил мир");
            try{
                logic.reset();
                for(Player p : Groups.player){
                    if(p.con != null) netServer.sendWorldData(p);
                }
                say(player, "[lime]Мир перезагружен, клиенты синхронизированы.");
            }catch(Throwable t){
                say(player, "[scarlet]Не удалось перезагрузить мир: " + t.getMessage());
            }
        });

        handler.<Player>register("nconfig", "<ключ> [значение]", "Настройки серверной части MKUKIC (уровень 4).", (args, player) -> {
            if(!check(player, 4, "nconfig")) return;
            Cfg c = ServerData.cfg;
            if(args.length < 2){
                say(player, "[accent]Ключи:[lightgray] startCredits=" + c.startCredits +
                    ", creditsPerMinute=" + c.creditsPerMinute + ", creditsPerKill=" + c.creditsPerKill +
                    ", anticheat=" + c.anticheat + ", anticheatKick=" + c.anticheatKick +
                    ", anticheatViolationsToKick=" + c.anticheatViolationsToKick +
                    ", speedCheatFactor=" + c.speedCheatFactor + ", logChat=" + c.logChat + ", maxLogEntries=" + c.maxLogEntries);
                return;
            }
            String v = args[1];
            switch(args[0]){
                case "startCredits" -> c.startCredits = num(v, c.startCredits);
                case "creditsPerMinute" -> c.creditsPerMinute = num(v, c.creditsPerMinute);
                case "creditsPerKill" -> c.creditsPerKill = num(v, c.creditsPerKill);
                case "anticheat" -> c.anticheat = bool(v, c.anticheat);
                case "anticheatKick" -> c.anticheatKick = bool(v, c.anticheatKick);
                case "anticheatViolationsToKick" -> c.anticheatViolationsToKick = num(v, c.anticheatViolationsToKick);
                case "speedCheatFactor" -> c.speedCheatFactor = (float)numf(v, c.speedCheatFactor);
                case "logChat" -> c.logChat = bool(v, c.logChat);
                case "maxLogEntries" -> c.maxLogEntries = num(v, c.maxLogEntries);
                default -> {
                    say(player, "[scarlet]Неизвестный ключ: " + args[0]);
                    return;
                }
            }
            ServerData.saveServer();
            say(player, "[lime]" + args[0] + " = " + v);
        });

        handler.<Player>register("become", "<юнит>", "Сесть в нового юнита (уровень 2+).", (args, player) -> {
            if(!check(player, 2, "become")) return;
            UnitType type = content.units().find(u -> u.name.equalsIgnoreCase(args[0]));
            if(type == null){
                say(player, "[scarlet]Неизвестный юнит: " + args[0]);
                return;
            }
            Unit u = type.create(player.team());
            u.set(player.x, player.y);
            u.add();
            Units.notifyUnitSpawn(u);
            Call.unitControl(player, u);
            say(player, "[lime]Вы управляете: " + type.localizedName);
        });

        handler.<Player>register("kill", "<игрок>", "Убить юнита игрока (уровень 2+).", (args, player) -> {
            if(!check(player, 2, "kill")) return;
            Player target = ServerData.find(args[0]);
            if(target == null || target.dead() || target.unit() == null){
                say(player, "[scarlet]Игрок не найден или мёртв");
                return;
            }
            target.unit().kill();
            ServerData.logPlayer(player, "kill", "-> " + target.plainName());
            say(player, "[lime]" + target.plainName() + " уничтожен.");
        });
        // ================= v1.16: порты из MoreCommands / JomMod / MinerTools =================

        handler.<Player>register("w", "<игрок> <сообщение...>", "Личное сообщение игроку (шепот).", (args, player) -> {
            if(args.length < 2){
                say(player, "[scarlet]Использование: /w <игрок> <сообщение>");
                return;
            }
            Player target = ServerData.find(args[0]);
            if(target == null){
                say(player, "[scarlet]Игрок не найден: " + args[0]);
                return;
            }
            if(target == player){
                say(player, "[scarlet]Себе шептать не нужно — пиши в чат :)");
                return;
            }
            String msg = joinFrom(args, 1);
            whisper(target, "[#c8a2ff][W][] " + player.coloredName() + "[#c8a2ff] -> ты: [white]" + msg, player);
            say(player, "[#c8a2ff][W][] ты -> " + target.coloredName() + "[white]: " + msg);
            replyTo.put(player.uuid(), target.uuid());
            replyTo.put(target.uuid(), player.uuid());
            ServerData.logPlayer(player, "whisper", "-> " + target.plainName() + ": " + Strings.stripColors(msg));
        });

        handler.<Player>register("r", "<сообщение...>", "Ответить на последнее личное сообщение.", (args, player) -> {
            if(args.length < 1){
                say(player, "[scarlet]Использование: /r <сообщение>");
                return;
            }
            String id = replyTo.get(player.uuid());
            Player target = id == null ? null : ServerData.find(id);
            if(!online(target)){
                say(player, "[scarlet]Собеседник уже не в игре");
                return;
            }
            String msg = String.join(" ", args);
            whisper(target, "[#c8a2ff][W][] " + player.coloredName() + "[#c8a2ff] -> ты: [white]" + msg, player);
            say(player, "[#c8a2ff][W][] ты -> " + target.coloredName() + "[white]: " + msg);
        });

        handler.<Player>register("maps", "[страница]", "Список карт сервера (для /rtv).", (args, player) -> {
            Seq<Map> list = mapList();
            if(list.isEmpty()){
                say(player, "[scarlet]карт на сервере нет");
                return;
            }
            int pages = Math.max(1, (list.size + 9) / 10);
            int page = args.length > 0 && Strings.canParseInt(args[0]) ? Mathf.clamp(Strings.parseInt(args[0]), 1, pages) : 1;
            StringBuilder sb = new StringBuilder("[accent]Карты сервера[] [darkgray](" + list.size + ", страница " + page + "/" + pages + ")\n");
            for(int i = (page - 1) * 10; i < Math.min(list.size, page * 10); i++){
                Map m = list.get(i);
                sb.append("[white]").append(i + 1).append(". ").append(m.name())
                    .append(" [darkgray]").append(m.width).append('x').append(m.height)
                    .append(m.custom ? " [accent](своя)" : "").append('\n');
            }
            sb.append("[darkgray]голосование за смену карты: /rtv <номер|имя>");
            say(player, sb.toString());
        });

        // ВАЖНО: arc.util.CommandHandler режет paramText ПО ПРОБЕЛАМ и требует, чтобы каждый
        // кусок был целиком обернут в <...> или [...] («Arg names must not have spaces in them»).
        // Строка "<номер|имя карты>" давала IllegalArgumentException: Malformed param '<номер|имя'
        // и роняла загрузку мода. Скобки [] + "..." = необязательный variadic:
        //  /rtv                -> args.length == 0 (показываем счёт голосов)
        //  /rtv 5              -> args[0] == "5"
        //  /rtv my cool map    -> args[0] == "my cool map" (variadic не режет пробелы)
        handler.<Player>register("rtv", "[номер|имя...]", "Голосовать за смену карты (rock the vote).", (args, player) -> {
            if(args.length < 1){
                int need = votesNeeded();
                say(player, "[lightgray]Голосов за смену карты: [accent]" + rtvVotes.size + "/" + need
                    + (rtvTarget == null ? "" : "[lightgray], цель: [white]" + rtvTarget.name())
                    + "\n[darkgray]использование: /rtv <номер|имя карты>; список: /maps");
                return;
            }
            Seq<Map> list = mapList();
            Map target = null;
            String q = String.join(" ", args);
            if(Strings.canParseInt(q)){
                int idx = Strings.parseInt(q) - 1;
                if(idx >= 0 && idx < list.size) target = list.get(idx);
            }
            if(target == null) target = list.find(m -> m.name().equalsIgnoreCase(q));
            if(target == null) target = list.find(m -> m.name().toLowerCase().contains(q.toLowerCase()));
            if(target == null){
                say(player, "[scarlet]Карта не найдена: " + q + " [darkgray](список: /maps)");
                return;
            }
            rtvVotes.put(player.uuid(), target.name());
            rtvTarget = target;
            int need = votesNeeded();
            Call.sendMessage("[#6fd6ff]" + (char)Iconc.map + " " + player.plainName() + " голосует за карту [white]"
                + target.name() + " [#6fd6ff](" + rtvVotes.size + "/" + need + ")");
            if(rtvVotes.size >= need){
                Call.sendMessage("[accent]Голосование прошло — карта [white]" + target.name() + " [accent]будет следующей.");
                try{
                    maps.setNextMapOverride(target);
                    ServerData.log("rtv", "следующая карта: " + target.name());
                }catch(Throwable t){
                    Log.err("[MKUKIC] не удалось задать следующую карту", t);
                }
                rtvVotes.clear();
                rtvTarget = null;
            }
        });

        handler.<Player>register("pinfo", "[игрок|uuid]", "Карточка игрока: роль, кредиты, заходы, пинг.", (args, player) -> {
            Player t = args.length > 0 ? ServerData.find(args[0]) : player;
            if(t == null){
                say(player, "[scarlet]Игрок не найден");
                return;
            }
            Rec r = ServerData.rec(t);
            int lvl = ServerData.level(t);
            StringBuilder sb = new StringBuilder();
            sb.append("[accent]Карточка: ").append(t.coloredName()).append('\n');
            sb.append("[lightgray]uuid: [white]").append(shortId(t.uuid()));
            if(t.con != null) sb.append("[lightgray]  ip: [white]").append(t.con.address);
            sb.append('\n');
            sb.append("[lightgray]роль: [#ffb454]").append(ServerData.roleName(lvl))
                .append("[lightgray]  кредиты: [white]").append(r.credits).append('\n');
            try{
                PlayerInfo info = t.getInfo();
                if(info != null){
                    sb.append("[lightgray]заходов: [white]").append(info.timesJoined)
                        .append("[lightgray]  киков: [white]").append(info.timesKicked).append('\n');
                }
            }catch(Throwable ignored){}
            sb.append("[lightgray]команда: ").append(t.team() == null ? "—" : t.team().localized())
                .append("[lightgray]  юнит: ").append(t.dead() || t.unit() == null ? "[darkgray]нет"
                    : (t.unit().type.localizedName == null ? t.unit().type.name : t.unit().type.localizedName))
                .append("[lightgray]  позиция: [white]").append(World.toTile(t.x)).append(", ").append(World.toTile(t.y));
            say(player, sb.toString());
        });

        handler.<Player>register("sync", "[игрок]", "Переотправить игроку состояние мира (при рассинхроне). Уровень 1+.", (args, player) -> {
            if(!check(player, 1, "sync")) return;
            Player target = args.length > 0 ? ServerData.find(args[0]) : player;
            if(target == null){
                say(player, "[scarlet]Игрок не найден");
                return;
            }
            try{
                if(target.con != null){
                    netServer.sendWorldData(target);
                }else{
                    control.playMap(state.map, state.rules);
                }
                say(player, "[lime]Синхронизация отправлена: " + target.plainName());
                ServerData.logPlayer(player, "sync", "-> " + target.plainName());
            }catch(Throwable t){
                say(player, "[scarlet]Не удалось синхронизировать: " + t.getClass().getSimpleName());
            }
        });
    }

    // ================= v1.16: хелперы чат-команд =================

    /** Кому отвечать командой /r: uuid -> uuid. */
    private static final ObjectMap<String, String> replyTo = new ObjectMap<>();
    /** Голоса за смену карты: uuid игрока -> имя карты. */
    private static final ObjectMap<String, String> rtvVotes = new ObjectMap<>();
    private static @Nullable Map rtvTarget;

    /** Отправить личное сообщение конкретному игроку (работает и для хоста, и для сети). */
    private static void whisper(Player to, String text, Player from){
        if(to == null) return;
        if(to.con != null){
            // сигнатура ванили: sendMessage(con, message, unformatted, sender)
            Call.sendMessage(to.con, text, Strings.stripColors(text), from);
        }else{
            to.sendMessage(text);
        }
    }

    /** Онлайн ли игрок. */
    private static boolean online(Player p){
        if(p == null) return false;
        for(Player other : Groups.player) if(other == p) return true;
        return false;
    }

    /** Сколько голосов нужно, чтобы сменить карту (половина онлайна, но не меньше 2). */
    public static int votesNeeded(){
        int online = Groups.player.size();
        return Math.max(2, (online + 1) / 2);
    }

    /** Все карты сервера: свои + стандартные + модовые. */
    public static Seq<Map> mapList(){
        Seq<Map> all = new Seq<>();
        try{
            if(maps != null){
                all.addAll(maps.customMaps());
                all.addAll(maps.defaultMaps());
                all.addAll(maps.moddedMaps());
            }
        }catch(Throwable t){
            Log.err("[MKUKIC] не удалось прочитать список карт", t);
        }
        return all;
    }

    /** Соединить аргументы команды, начиная с индекса from. */
    private static String joinFrom(String[] args, int from){
        StringBuilder sb = new StringBuilder();
        for(int i = from; i < args.length; i++){
            if(sb.length() > 0) sb.append(' ');
            sb.append(args[i]);
        }
        return sb.toString();
    }





    /** Выполняет команду, пришедшую от клиента-мода (или из локального меню). */
    public static void runLocal(Player player, String raw){
        if(raw == null) return;
        String cmd = raw.startsWith("/") ? raw.substring(1) : raw;
        if(netServer != null && netServer.clientCommands != null){
            var resp = netServer.clientCommands.handleMessage("/" + cmd, player);
            if(resp.type == arc.util.CommandHandler.ResponseType.unknownCommand){
                say(player, "[scarlet]Неизвестная команда: " + cmd);
            }
        }
    }



    // ================================ ХЕЛПЕРЫ ================================

    private static boolean check(Player player, int level, String cmd){
        int have = ServerData.level(player);
        if(have < level){
            say(player, "[scarlet]Команда /" + cmd + " требует уровень " + level + " (" + ServerData.roleName(level) + "). У вас: " + ServerData.roleName(have));
            ServerData.logPlayer(player, "denied", "/" + cmd + " (нужен " + level + ", есть " + have + ")");
            return false;
        }
        return true;
    }

    /** Нельзя применять действия к игрокам с равной или более высокой ролью. */
    private static boolean canAct(Player actor, Player target){
        if(target == null) return false;
        if(target == actor) return false;
        return ServerData.level(target) < ServerData.level(actor);
    }

    public static void say(Player player, String text){
        if(player == null) return;
        try{
            player.sendMessage(text);
        }catch(Throwable t){
            Log.err("[MKUKIC] не удалось отправить сообщение", t);
        }
    }

    private static void announceAdmin(String text){
        Groups.player.each(p -> p.admin || ServerData.level(p) >= 1, p -> say(p, "[orange][A][] " + text));
        ServerData.log("admin", text);
    }

    private static void healPlayer(Player p){
        if(p == null || p.dead() || p.unit() == null) return;
        Unit u = p.unit();
        u.heal(u.maxHealth());
        u.shield(u.shield() + u.maxHealth() * 0.25f);
        Fx.healBlockFull.at(u.x, u.y, u.hitSize() / 8f, Pal.heal);
    }

    private static void giveItems(Player player, String[] args, int mode){
        if(args.length < 2){
            say(player, "[scarlet]Использование: /give <ресурс> <кол-во>");
            return;
        }
        Item item = content.items().find(i -> i.name.equalsIgnoreCase(args[0]));
        if(item == null){
            say(player, "[scarlet]Неизвестный ресурс: " + args[0]);
            return;
        }
        int amount = Strings.canParseInt(args[1]) ? Mathf.clamp(Strings.parseInt(args[1]), 1, 1_000_000) : 1000;

        if(mode == 2){
            for(CoreBuild core : state.teams.playerCores()){
                core.items.add(item, Math.min(amount, core.getMaximumAccepted(item) - core.items.get(item)));
            }
            announceAdmin(player.plainName() + " выдал всем " + amount + " " + item.localizedName);
        }else{
            CoreBuild core = player.bestCore();
            if(core == null){
                say(player, "[scarlet]У вас нет ядра");
                return;
            }
            int added = Math.min(amount, Math.max(0, core.getMaximumAccepted(item) - core.items.get(item)));
            core.items.add(item, added);
            say(player, "[lime]Выдано " + added + " " + item.localizedName);
        }
        ServerData.logPlayer(player, "give", item.name + " x" + amount);
    }

    private static boolean doShopAction(Player player, ShopItem item){
        switch(item.action){
            case "item" -> {
                CoreBuild core = player.bestCore();
                if(core == null){
                    say(player, "[scarlet]У вас нет ядра — покупка невозможна");
                    return false;
                }
                Item res = content.items().find(i -> i.name.equalsIgnoreCase(item.arg));
                if(res == null) return false;
                core.items.add(res, Math.min(item.amount, Math.max(0, core.getMaximumAccepted(res) - core.items.get(res))));
                say(player, "[lime]В ядро добавлено " + item.amount + " " + res.localizedName);
                return true;
            }
            case "heal" -> {
                healPlayer(player);
                return true;
            }
            case "unit" -> {
                UnitType type = content.units().find(u -> u.name.equalsIgnoreCase(item.arg));
                if(type == null) return false;
                for(int i = 0; i < item.amount; i++){
                    if(!Units.canCreate(player.team(), type)) break;
                    Unit u = type.create(player.team());
                    u.set(player.x + Mathf.range(20f), player.y + Mathf.range(20f));
                    u.add();
                    Units.notifyUnitSpawn(u);
                }
                say(player, "[lime]Юнит создан: " + type.localizedName);
                return true;
            }
            case "credits" -> {
                ServerData.rec(player).credits += item.amount;
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    public static String shortId(String id){
        if(id == null) return "?";
        return id.length() <= 8 ? id : id.substring(0, 8);
    }

    private static @Nullable Team teamByName(String name){
        if(name == null) return null;
        for(Team t : Team.all){
            if(t.name.equalsIgnoreCase(name)) return t;
        }
        if(Strings.canParseInt(name)){
            int i = Strings.parseInt(name);
            if(i >= 0 && i < Team.all.length) return Team.all[i];
        }
        return null;
    }

    private static int num(String v, int def){
        return Strings.canParseInt(v) ? Strings.parseInt(v) : def;
    }

    private static double numf(String v, double def){
        try{
            return Double.parseDouble(v.replace(',', '.'));
        }catch(Throwable t){
            return def;
        }
    }

    private static boolean bool(String v, boolean def){
        if(v.equalsIgnoreCase("true") || v.equals("1")) return true;
        if(v.equalsIgnoreCase("false") || v.equals("0")) return false;
        return def;
    }

    /** Формирует строку статуса для клиента. */
    public static String infoPayload(Player player){
        Rec r = ServerData.rec(player);
        int lvl = ServerData.level(player);
        return MKUKICMod.version + ";" + lvl + ";" + ServerData.roleName(lvl) + ";" + r.credits;
    }

    /** Периодическая выдача кредитов (раз в минуту). */
    public static void creditTick(){
        if(ServerData.cfg.creditsPerMinute <= 0) return;
        Groups.player.each(p -> {
            Rec r = ServerData.rec(p);
            r.credits += ServerData.cfg.creditsPerMinute;
        });
        ServerData.savePlayers();
    }
}
