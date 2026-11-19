package mkukic.client;

import arc.*;
import arc.func.*;
import arc.graphics.*;
import arc.math.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import mindustry.content.*;
import mindustry.entities.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.net.Administration.*;
import mindustry.net.Packets.*;
import mindustry.type.*;
import mindustry.ui.*;
import mindustry.ui.dialogs.*;
import mkukic.*;
import mindustry.world.blocks.storage.CoreBlock.*;

import static mindustry.Vars.*;

/** Админ-панель: игроки, сервер, спаун, магазин, консоль команд. */
public class AdminPanel{
    private static int subTab = 0;
    private static final String[] subNames = {"Игроки", "Сервер", "Спаун", "Магазин", "Консоль"};
    private static String filter = "";

    // спаун
    private static @Nullable UnitType spawnType = UnitTypes.dagger;
    private static int spawnCount = 5;
    private static Team spawnTeam = null;
    private static @Nullable Item giveItem = Items.copper;
    private static int giveAmount = 4000;

    public static void build(Table t){
        MKUKICSkin.init();
        t.table(nav -> {
            nav.left();
            for(int i = 0; i < subNames.length; i++){
                final int idx = i;
                nav.button(subNames[i], MKUKICSkin.toggleBtn, () -> {
                    subTab = idx;
                    MKUKICMenu.refresh();
                }).checked(b -> subTab == idx).minWidth(100f).height(34f).pad(2f);
            }
        }).left().growX().row();

        t.image(MKUKICSkin.div).height(2f).growX().pad(6f, 0f, 6f, 0f).row();

        // статус — в карточке с акцентной полосой слева
        t.table(status -> {
            status.setBackground(MKUKICSkin.card);
            status.left().top();
            status.label(() -> serverStatus()).left().wrap().growX().pad(7f, 14f, 8f, 10f);
        }).left().growX().padBottom(6f).row();

        switch(subTab){
            case 0 -> players(t);
            case 1 -> server(t);
            case 2 -> spawn(t);
            case 3 -> shop(t);
            case 4 -> console(t);
        }
    }

    private static String serverStatus(){
        if(!net.active() || net.server()){
            return "[lime]Локальная игра / хост[lightgray] — доступны все серверные команды MKUKIC.";
        }
        if(AdminNet.serverHasMod()){
            return "[lime]Сервер с MKUKIC[lightgray] v" + AdminNet.serverVersion +
                "  |  ваша роль: [accent]" + AdminNet.roleName + "[lightgray] (" + AdminNet.roleLevel + ")" +
                "  |  кредиты: [accent]" + AdminNet.credits;
        }
        return "[scarlet]На сервере нет MKUKIC[lightgray] — работают только стандартные админ-действия игры " +
            "(kick / ban / trace / пропуск волны), и только если вы админ сервера. " +
            "Полный набор команд появится, если владелец поставит этот мод на сервер.";
    }

    // ============================== ИГРОКИ ==============================

    private static void players(Table t){
        t.table(c -> {
            c.left();
            c.add("Поиск:").padRight(6f);
            c.field(filter, MKUKICSkin.field, s -> filter = s).width(200f);
            c.button(Ico.of("refresh") + " Сбросить", MKUKICSkin.actBtn, () -> {
                filter = "";
                MKUKICMenu.refresh();
            }).padLeft(6f).minWidth(90f);
        }).left().row();

        t.pane(list -> {
            list.top().left();
            Seq<Player> players = new Seq<>();
            Groups.player.each(p -> players.add(p));
            players.sort((a, b) -> a.name.compareTo(b.name));

            int shown = 0;
            for(Player p : players){
                if(p == null) continue;
                if(!filter.isEmpty() && !p.name.toLowerCase().contains(filter.toLowerCase())
                    && !p.uuid().toLowerCase().contains(filter.toLowerCase())) continue;
                shown++;
                playerRow(list, p);
            }
            if(shown == 0) list.add("[darkgray]Никого не найдено").pad(8f);
        }).grow().row();

        t.table(c -> {
            c.left();
            c.button(Ico.of("list") + " Список банов", MKUKICSkin.actBtn, () -> AdminNet.runCommand("bans")).pad(2f).minWidth(200f);
            c.button(Ico.of("chartBar") + " Статистика сервера", MKUKICSkin.actBtn, () -> AdminNet.runCommand("stats")).pad(2f).minWidth(200f);
        }).left().padTop(4f).row();
    }

    private static void playerRow(Table list, Player p){
        list.table(row -> {
            row.left();
            row.setBackground(MKUKICSkin.panelSoft);

            String admin = p.admin ? "[yellow]*[]" : "";
            row.add(admin + "[#" + p.color.toString().toUpperCase() + "]" + p.name + "[]")
                .left().minWidth(170f).wrap();
            row.add("[darkgray]id " + p.uuid().substring(0, Math.min(8, p.uuid().length())))
                .left().minWidth(90f).padLeft(6f);
            row.label(() -> "[lightgray]" + p.team().name + (p.dead() ? " [scarlet](мёртв)[]" : ""))
                .left().minWidth(110f).padLeft(6f).expandX();

            row.button(Ico.of("menu") + " Действия", MKUKICSkin.actBtn, () -> playerDialog(p)).minWidth(120f).padLeft(8f);
        }).left().growX().pad(2f).margin(6f).row();
    }

    private static void playerDialog(Player p){
        BaseDialog d = new BaseDialog("Игрок: " + p.name);
        d.addCloseButton();
        d.cont.pane(t -> {
            t.left().top();
            t.defaults().left().growX().pad(2f).minHeight(40f);

            t.label(() -> "[lightgray]id: [white]" + p.uuid() + "\n[lightgray]ip: [white]" + (p.con != null ? p.con.address : "?") +
                "\n[lightgray]usid: [white]" + p.usid() + "\n[lightgray]команда: [white]" + p.team().name +
                "\n[lightgray]юнит: [white]" + (p.dead() || p.unit() == null ? "-" : p.unit().type.localizedName) +
                "\n[lightgray]админ: [white]" + (p.admin ? "да" : "нет")).left().growX().padBottom(8f).row();

            t.button(Ico.of("cancel") + " Кикнуть", MKUKICSkin.actBtn, () -> {
                ui.showConfirm("Кикнуть " + p.name + "?", () -> {
                    Call.adminRequest(p, AdminAction.kick, null);
                    d.hide();
                });
            }).row();

            t.button(Ico.of("lock") + " Забанить навсегда", MKUKICSkin.actBtn, () -> {
                ui.showConfirm("Забанить " + p.name + " навсегда?", () -> {
                    Call.adminRequest(p, AdminAction.ban, null);
                    d.hide();
                });
            }).row();

            t.button(Ico.of("lock") + " Бан с причиной и сроком", MKUKICSkin.actBtn, () -> {
                ui.showTextInput("Бан игрока " + p.name, "Срок в минутах (0 = навсегда)", 6, "60", true, dur -> {
                    ui.showTextInput("Бан игрока " + p.name, "Причина", 120, "нарушение правил", reason -> {
                        AdminNet.runCommand("nban " + p.name + " " + dur + " " + reason);
                        d.hide();
                    });
                });
            }).row();

            t.button(Ico.of("cancel") + " Кик с причиной", MKUKICSkin.actBtn, () -> {
                ui.showTextInput("Кик " + p.name, "Причина", 120, "afk", reason -> {
                    AdminNet.runCommand("nkick " + p.name + " " + reason);
                    d.hide();
                });
            }).row();

            t.button(Ico.of("zoom") + " Трассировка игрока", MKUKICSkin.actBtn, () -> {
                Call.adminRequest(p, AdminAction.trace, null);
                d.hide();
            }).row();

            t.button(Ico.of("chartBar") + " Статистика игрока", MKUKICSkin.actBtn, () -> {
                AdminNet.runCommand("stats " + p.name);
                d.hide();
            }).row();

            t.button(Ico.of("list") + " История действий игрока", MKUKICSkin.actBtn, () -> {
                AdminNet.runCommand("history " + p.name);
                d.hide();
            }).row();

            t.button(Ico.of("warning") + " Предупредить игрока", MKUKICSkin.actBtn, () -> {
                ui.showTextInput("Предупреждение для " + p.name, "Текст", 200, "прекрати читерить", reason -> {
                    AdminNet.runCommand("warn " + p.name + " " + reason);
                    d.hide();
                });
            }).row();

            t.button(Ico.of("players") + " Сменить команду игрока", MKUKICSkin.actBtn, () -> teamDialog(team -> {
                Call.adminRequest(p, AdminAction.switchTeam, team);
                d.hide();
            })).row();

            t.button(Ico.of("admin") + " Выдать админ-роль", MKUKICSkin.actBtn, () -> {
                ui.showTextInput("Роль для " + p.name, "0 игрок | 1 модер | 2 админ | 3 старший | 4 владелец", 2, "2", true, lvl -> {
                    AdminNet.runCommand("setlevel " + p.name + " " + lvl);
                    d.hide();
                });
            }).row();

            t.button(Ico.of("add") + " Вылечить игрока", MKUKICSkin.actBtn, () -> {
                AdminNet.runCommand("heal " + p.name);
                d.hide();
            }).row();

            t.button(Ico.of("move") + " Телепорт игрока ко мне", MKUKICSkin.actBtn, () -> {
                AdminNet.runCommand("tphere " + p.name);
                d.hide();
            }).row();

            t.button(Ico.of("chat") + " Личное сообщение", MKUKICSkin.actBtn, () -> {
                ui.showTextInput("Сообщение для " + p.name, "Текст", 200, "", text -> {
                    if(net.server() || !net.active()){
                        p.sendMessage("[accent][MKUKIC][] " + text);
                    }else{
                        AdminNet.runCommand("msg " + p.name + " " + text);
                    }
                    d.hide();
                });
            }).row();

            t.button(Ico.of("star") + " Выдать кредиты магазина", MKUKICSkin.actBtn, () -> {
                ui.showTextInput("Кредиты для " + p.name, "Сумма (можно отрицательную)", 8, "100", text -> {
                    AdminNet.runCommand("setcredits " + p.name + " " + text);
                    d.hide();
                });
            }).row();
        }).grow();
        d.show();
    }

    private static void teamDialog(Cons<Team> cons){
        BaseDialog d = new BaseDialog("Команда");
        d.addCloseButton();
        d.cont.table(t -> {
            t.defaults().pad(3f).size(150f, 42f);
            int i = 0;
            for(Team team : Team.baseTeams){
                t.button(team.name, MKUKICSkin.toggleBtn, () -> {
                    cons.get(team);
                    d.hide();
                });
                if(++i % 3 == 0) t.row();
            }
        });
        d.show();
    }

    // ============================== СЕРВЕР ==============================

    private static void server(Table t){
        t.table(c -> {
            c.left();
            c.defaults().pad(2f).minWidth(190f).left();
            c.button(Ico.of("right") + " Пропустить волну", MKUKICSkin.actBtn, () -> {
                if(player.admin || !net.active() || net.server()) Call.adminRequest(player, AdminAction.wave, null);
                else MKUKICMod.toast("[scarlet]Нужны права админа");
            });
            c.button(Ico.of("players") + " Баланс команд", MKUKICSkin.actBtn, () -> AdminNet.runCommand("balance"));
            c.button(Ico.of("refresh") + " Перезагрузить мир", MKUKICSkin.actBtn, () -> AdminNet.runCommand("reload"));
        }).left().row();

        t.add("[accent]Правила (нужен хост или сервер с модом)").left().padTop(8f).row();
        t.table(c -> {
            c.left();
            ruleToggle(c, "Бесконечные ресурсы", () -> state.rules.infiniteResources, v -> state.rules.infiniteResources = v);
            ruleToggle(c, "Туман войны", () -> state.rules.fog, v -> state.rules.fog = v);
            ruleToggle(c, "Волны", () -> state.rules.waves, v -> state.rules.waves = v);
            ruleToggle(c, "PVP", () -> state.rules.pvp, v -> state.rules.pvp = v);
            ruleToggle(c, "Ядро сжигает излишки", () -> state.rules.coreIncinerates, v -> state.rules.coreIncinerates = v);
        }).left().row();

        t.table(c -> {
            c.left();
            c.add("Скорость стройки команд:").padRight(6f);
            c.slider(0.25f, 8f, 0.25f, state.rules.unitBuildSpeedMultiplier, v -> {
                if(!net.client()){
                    state.rules.unitBuildSpeedMultiplier = v;
                    syncRules();
                }
            }).width(200f);
            c.add("Лимит юнитов:").padLeft(14f).padRight(6f);
            c.slider(0f, 500f, 10f, state.rules.unitCap, v -> {
                if(!net.client()){
                    state.rules.unitCap = (int)v;
                    syncRules();
                }
            }).width(200f);
        }).left().padTop(4f).row();

        t.table(c -> {
            c.left();
            c.button(Ico.of("ok") + " Завершить игру: победа", MKUKICSkin.actBtn, () -> AdminNet.runCommand("gameover win")).pad(2f).minWidth(200f);
            c.button(Ico.of("cancel") + " Завершить игру: поражение", MKUKICSkin.actBtn, () -> AdminNet.runCommand("gameover lose")).pad(2f).minWidth(200f);
        }).left().padTop(6f).row();
    }

    private static void ruleToggle(Table c, String title, Boolp get, Boolc set){
        c.check(title, get.get(), v -> {
            if(net.client()){
                MKUKICMod.toast("[scarlet]Менять правила может только хост/сервер");
                return;
            }
            set.get(v);
            syncRules();
        }).left().minWidth(260f).pad(2f);
        c.row();
    }

    /** Рассылает изменённые правила всем клиентам. */
    private static void syncRules(){
        try{
            Call.setRules(state.rules);
        }catch(Throwable t){
            Log.err("[MKUKIC] не удалось синхронизировать правила", t);
        }
    }

    // =============================== СПАУН ===============================

    private static void spawn(Table t){
        t.add("[accent]Юниты").left().row();
        t.table(c -> {
            c.left();
            c.add("Тип:").padRight(6f);
            c.button("выбрать...", MKUKICSkin.actBtn, () -> unitChooser()).minWidth(150f).update(b -> b.setText(spawnType == null ? "-" : spawnType.localizedName));
            c.add("Кол-во:").padLeft(14f).padRight(6f);
            c.field(String.valueOf(spawnCount), MKUKICSkin.field, s -> {
                String cl = s.replace(",", "");
                if(Strings.canParseInt(cl)) spawnCount = Mathf.clamp(Strings.parseInt(cl), 1, 200);
            }).width(70f);
            c.button("команда", MKUKICSkin.actBtn, () -> teamChooser(tm -> {
                spawnTeam = tm;
                MKUKICMenu.refresh();
            })).minWidth(140f).padLeft(8f)
                .update(b -> b.setText("команда: " + (spawnTeam == null ? "своя" : spawnTeam.name)));
        }).left().row();

        t.table(c -> {
            c.left();
            c.defaults().pad(2f).minWidth(170f);
            c.button(Ico.of("add") + " Создать у себя", MKUKICSkin.actBtn, () -> doSpawn("here"));
            c.button(Ico.of("home") + " Создать у ядра", MKUKICSkin.actBtn, () -> doSpawn("core"));
            c.button(Ico.of("players") + " Создать всем командам", MKUKICSkin.actBtn, () -> doSpawn("all"));
            c.button(Ico.of("units") + " Сесть в юнита", MKUKICSkin.actBtn, () -> {
                if(spawnType == null) return;
                if(!net.client()){
                    Unit u = spawnType.create(spawnTeam == null ? player.team() : spawnTeam);
                    u.set(player.x, player.y);
                    u.add();
                    Units.notifyUnitSpawn(u);
                    Call.unitControl(player, u);
                }else{
                    AdminNet.runCommand("become " + spawnType.name);
                }
            });
        }).left().row();

        t.add("[accent]Ресурсы").left().padTop(8f).row();
        t.table(c -> {
            c.left();
            c.add("Ресурс:").padRight(6f);
            c.button("выбрать...", MKUKICSkin.actBtn, () -> itemChooser()).minWidth(140f).update(b -> b.setText(giveItem == null ? "-" : giveItem.localizedName));
            c.add("Кол-во:").padLeft(14f).padRight(6f);
            c.field(String.valueOf(giveAmount), MKUKICSkin.field, s -> {
                String cl = s.replace(",", "");
                if(Strings.canParseInt(cl)) giveAmount = Mathf.clamp(Strings.parseInt(cl), 1, 1000000);
            }).width(110f);
            c.button(Ico.of("box") + " Выдать в ядро", MKUKICSkin.actBtn, () -> {
                if(giveItem == null) return;
                AdminNet.runCommand("give " + giveItem.name + " " + giveAmount);
            }).minWidth(150f).padLeft(10f);
            c.button(Ico.of("box") + " Выдать всем", MKUKICSkin.actBtn, () -> {
                if(giveItem == null) return;
                AdminNet.runCommand("giveall " + giveItem.name + " " + giveAmount);
            }).minWidth(150f).padLeft(4f);
        }).left().row();
    }

    private static void doSpawn(String where){
        if(spawnType == null) return;
        if(net.client()){
            AdminNet.runCommand("spawnunit " + spawnType.name + " " + spawnCount + " " + where +
                (spawnTeam == null ? "" : " " + spawnTeam.name));
            return;
        }
        Team team = spawnTeam == null ? player.team() : spawnTeam;
        float x = player.x, y = player.y;
        if(!where.equals("here")){
            CoreBuild core = player.bestCore();
            if(core != null){
                x = core.x; y = core.y;
            }
        }
        int n = 0;
        for(int i = 0; i < spawnCount; i++){
            if(!Units.canCreate(team, spawnType)) break;
            Unit u = spawnType.create(team);
            u.set(x + Mathf.range(24f), y + Mathf.range(24f));
            u.rotation(Mathf.random(360f));
            u.add();
            Units.notifyUnitSpawn(u);
            n++;
        }
        MKUKICMod.toast("Создано юнитов: " + n);
    }

    private static void unitChooser(){
        BaseDialog d = new BaseDialog("Выбор юнита");
        d.addCloseButton();
        d.cont.table(top -> {
            top.left();
            top.add("Поиск:").padRight(6f);
            top.field("", MKUKICSkin.field, s -> unitFilter = s).width(200f);
            top.button(Ico.of("refresh") + " Обновить", MKUKICSkin.actBtn, () -> {
                d.hide();
                unitChooser();
            }).padLeft(6f);
        }).left().row();
        d.cont.pane(p -> {
            p.top().left();
            p.defaults().pad(2f).size(150f, 42f);
            int i = 0;
            for(UnitType ut : content.units()){
                if(ut == null || ut.isHidden()) continue;
                if(!unitFilter.isEmpty() && !ut.name.contains(unitFilter) && !ut.localizedName.toLowerCase().contains(unitFilter.toLowerCase())) continue;
                p.button(ut.localizedName, MKUKICSkin.actBtn, () -> {
                    spawnType = ut;
                    d.hide();
                    MKUKICMenu.refresh();
                });
                if(++i % 4 == 0) p.row();
            }
        }).grow();
        d.show();
    }

    private static String unitFilter = "";

    private static void itemChooser(){
        BaseDialog d = new BaseDialog("Выбор ресурса");
        d.addCloseButton();
        d.cont.table(p -> {
            p.defaults().pad(3f).size(160f, 42f);
            int i = 0;
            for(Item it : content.items()){
                p.button(it.localizedName, MKUKICSkin.actBtn, () -> {
                    giveItem = it;
                    d.hide();
                    MKUKICMenu.refresh();
                });
                if(++i % 3 == 0) p.row();
            }
        });
        d.show();
    }

    private static void teamChooser(Cons<Team> cons){
        BaseDialog d = new BaseDialog("Команда");
        d.addCloseButton();
        d.cont.table(t -> {
            t.defaults().pad(3f).size(150f, 42f);
            t.button("своя", MKUKICSkin.toggleBtn, () -> {
                cons.get(null);
                d.hide();
            });
            int i = 1;
            for(Team team : Team.baseTeams){
                t.button(team.name, MKUKICSkin.toggleBtn, () -> {
                    cons.get(team);
                    d.hide();
                });
                if(i++ % 3 == 0) t.row();
            }
        });
        d.show();
    }

    // ============================== МАГАЗИН ==============================

    private static void shop(Table t){
        t.table(c -> {
            c.left();
            c.label(() -> "[lightgray]Кредитов: [accent]" + AdminNet.credits).left().minWidth(180f);
            c.button(Ico.of("refresh") + " Обновить", MKUKICSkin.actBtn, () -> {
                AdminNet.queryServer();
                MKUKICMenu.refresh();
            }).padLeft(8f).minWidth(120f);
            c.button(Ico.of("list") + " Мои покупки", MKUKICSkin.actBtn, () -> AdminNet.runCommand("shop log")).padLeft(4f).minWidth(140f);
        }).left().row();

        if(AdminNet.shop.isEmpty()){
            t.add("[darkgray]Каталог пуст. Магазин работает, только если на сервере установлен MKUKIC.\n" +
                "Каталог настраивается в configs/mkukic-client/shop.json").left().wrap().growX().padTop(8f).row();
            return;
        }

        t.pane(list -> {
            list.top().left();
            for(String[] e : AdminNet.shop){
                list.table(row -> {
                    row.left();
                    row.setBackground(MKUKICSkin.panelSoft);
                    row.add("[white]" + e[0]).left().minWidth(180f);
                    row.add("[lightgray]" + e[1]).left().minWidth(110f).padLeft(6f);
                    row.button(Ico.of("star") + " Купить за [accent]" + e[2], MKUKICSkin.actBtn, () -> AdminNet.runCommand("buy " + e[0])).minWidth(170f).padLeft(8f);
                }).left().growX().pad(2f).margin(6f).row();
            }
        }).grow().padTop(4f).row();
    }

    // ============================== КОНСОЛЬ ==============================

    private static void console(Table t){
        t.add("[lightgray]Команды сервера MKUKIC (нужен мод на сервере). Стандартные команды игры тоже работают.").left().wrap().growX().row();

        t.table(c -> {
            c.left();
            TextField field = new TextField("/");
            field.setStyle(Styles.defaultField);
            c.add(field).width(380f).pad(3f);
            c.button(Ico.of("play") + " Выполнить", MKUKICSkin.actBtn, () -> AdminNet.runCommand(field.getText())).minWidth(130f).padLeft(6f);
            c.row();
            c.add("[darkgray]Примеры: /nban Nick 60 причина   /nkick Nick afk   /stats   /history Nick   /spawnunit dagger 10 core   /give copper 5000")
                .left().wrap().width(500f).padTop(4f);
        }).left().padTop(4f).row();

        t.table(c -> {
            c.left();
            c.defaults().pad(2f).minWidth(150f);
            quick(c, "/stats", "stats");
            quick(c, "/history", "history");
            quick(c, "/shop", "shop");
            quick(c, "/wave", "wave");
            quick(c, "/heal all", "heal all");
            quick(c, "/credits", "credits");
            quick(c, "/bans", "bans");
            quick(c, "/roles", "roles");
        }).left().padTop(6f).row();

        t.add("[accent]Журнал").left().padTop(8f).row();
        t.pane(list -> {
            list.top().left();
            for(String s : AdminNet.log){
                list.add(s).left().wrap().width(520f).pad(1f).row();
            }
            if(AdminNet.log.isEmpty()) list.add("[darkgray]пусто").row();
        }).growX().maxHeight(220f).row();

        t.button(Ico.of("eraser") + " Очистить журнал", MKUKICSkin.actBtn, () -> {
            AdminNet.log.clear();
            MKUKICMenu.refresh();
        }).left().minWidth(180f).padTop(4f).row();
    }

    private static void quick(Table c, String title, String cmd){
        c.button(title, MKUKICSkin.actBtn, () -> AdminNet.runCommand(cmd));
    }
}
