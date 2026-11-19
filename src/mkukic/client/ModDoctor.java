package mkukic.client;

import arc.*;
import arc.files.*;
import arc.struct.*;
import arc.util.*;
import mindustry.ctype.*;
import mindustry.mod.*;
import mindustry.mod.Mods.*;
import mindustry.ui.dialogs.*;
import mkukic.*;

import static mindustry.Vars.*;

/**
 * Самодиагностика установки (v1.9.2).
 *
 * Чинит симптом: «Ошибки были вызваны загружаемым содержимым: mkukic-client» со списком
 * чужих content-имён (coal-liquefier, wind-turbine и т.п.). MKUKIC Client — hidden-мод без
 * контента, поэтому такие ошибки всегда приходят из ПОСТОРОННЕГО файла с тем же внутренним
 * именем (старая папка/архив/jar из прежних попыток порта с hjson-контентом).
 *
 * Доктор:
 *  1) всегда пишет отчёт mods/mkukic-doctor-report.txt: версия игры и мода, ВСЕ загруженные
 *     моды (имя, версия, состояние, путь файла), ВСЁ errored-содержимое с путём источника;
 *  2) если находит дубли имени mkukic-client или контент с ошибками под этим именем —
 *     показывает диалог с точными путями, которые нужно удалить.
 */
public class ModDoctor{
    private static boolean reported;

    public static void init(){
        Time.runTask(60f, ModDoctor::check);
    }

    private static String nameOf(Content c){
        if(c instanceof mindustry.ctype.MappableContent){
            String n = ((mindustry.ctype.MappableContent)c).name;
            if(n != null) return n;
        }
        return c == null ? "?" : c.getClass().getSimpleName();
    }

    private static void check(){
      try{
        if(headless || ui == null) return;
        LoadedMod mine = mods.getMod(MKUKICMod.class);
        String myName = mine != null && mine.name != null ? mine.name : "mkukic-client";

        Seq<String> dupPaths = new Seq<>();
        Seq<String> errLines = new Seq<>();
        Seq<String> errNames = new Seq<>();

        // 1) дубли имени мода
        if(mine != null){
            for(LoadedMod lm : mods.list()){
                if(lm == null || lm == mine) continue;
                if(myName.equals(lm.name)){
                    String p = lm.file == null ? "(путь неизвестен)" : lm.file.path();
                    if(!dupPaths.contains(p)) dupPaths.add(p);
                }
            }
        }

        // 2) errored-контент: весь — в отчёт; под нашим именем — в диалог
        for(LoadedMod lm : mods.list()){
            if(lm == null || !lm.hasContentErrors() || lm.erroredContent == null) continue;
            for(Content c : lm.erroredContent){
                if(c == null) continue;
                String src = c.minfo != null && c.minfo.sourceFile != null ? c.minfo.sourceFile.path() : "?";
                String owner = c.minfo != null && c.minfo.mod != null && c.minfo.mod.name != null ? c.minfo.mod.name : lm.name;
                String err = c.minfo != null && c.minfo.error != null ? c.minfo.error : String.valueOf(c.minfo != null ? c.minfo.baseError : null);
                errLines.add(nameOf(c) + "  |  мод-владелец: " + owner + "  |  файл: " + src + "  |  " + Strings.truncate(err, 200));

                if(myName.equals(owner)){
                    if(errNames.size < 8) errNames.add(nameOf(c));
                    if(!dupPaths.contains(src)) dupPaths.add(src);
                    // путь до корня мода-владельца (папка/jar/zip), а не до отдельного hjson
                    if(c.minfo != null && c.minfo.mod != null && c.minfo.mod.file != null){
                        String root = c.minfo.mod.file.path();
                        if(!dupPaths.contains(root)) dupPaths.add(root);
                    }
                }
            }
        }

        // 3) отчёт на диск — всегда, независимо от наличия проблем
        writeReport(myName, dupPaths, errLines);

        if(reported || dupPaths.isEmpty()) return;
        reported = true;

        StringBuilder sb = new StringBuilder();
        sb.append("[scarlet]Найдена старая копия мода mkukic-client с битым контентом.[]\n\n");
        sb.append("[lightgray]MKUKIC Client не регистрирует собственный контент (hidden-режим), поэтому ошибки вида «coal-liquefier», «wind-turbine» приходят из постороннего файла с тем же именем мода.\n\n");
        sb.append("[accent]Удали из папки mods эти файлы/папки (оставь только новый MKUKICClient.jar):[]\n");
        for(String p : dupPaths) sb.append("[white]• ").append(p).append("[]\n");
        if(errNames.size > 0){
            sb.append("\n[lightgray]Битый контент: [white]").append(String.join(", ", errNames)).append("[]\n");
        }
        sb.append("\n[lightgray]Полный отчёт: [white]").append(reportPath()).append("[]\n");
        sb.append("[lightgray]Папка модов: [white]%APPDATA%/Mindustry/mods[] (Windows), [white]~/.local/share/Mindustry/mods[] (Linux). После удаления перезапусти игру.");

        Log.err("[MKUKIC] ModDoctor: дубли/битый контент под именем " + myName + ": " + dupPaths.toString(" | "));

        BaseDialog d = new BaseDialog("[scarlet]Конфликт копий мода mkukic-client");
        d.cont.pane(p -> p.add(sb.toString()).left().top().wrap().width(640f).pad(6f)).grow().maxHeight(Core.graphics.getHeight() * 0.7f);
        d.buttons.button("Понятно", () -> d.hide()).minWidth(160f).height(44f);
        d.show();
      }catch(Throwable t){
        Log.err("[MKUKIC] ModDoctor: сбой диагностики", t);
      }
    }

    private static String reportPath(){
        try{
            return (modDirectory != null ? modDirectory : Core.settings.getDataDirectory().child("mods")).child("mkukic-doctor-report.txt").path();
        }catch(Throwable t){
            return "(недоступен)";
        }
    }

    private static void writeReport(String myName, Seq<String> dupPaths, Seq<String> errLines){
        try{
            StringBuilder r = new StringBuilder();
            r.append("=== MKUKIC DOCTOR REPORT ===\n");
            r.append("дата: ").append(new java.util.Date()).append('\n');
            r.append("игра: Mindustry build ").append(mindustry.core.Version.build).append('\n');
            LoadedMod self = mods != null ? mods.getMod(MKUKICMod.class) : null;
            r.append("этот мод: ").append(myName).append(" v").append(MKUKICMod.version)
                .append(" (файл: ").append(self != null && self.file != null ? self.file.path() : "?").append(")\n");
            r.append("глобально content-ошибок: ").append(mods != null && mods.hasContentErrors()).append("\n\n");

            r.append("--- ВСЕ ЗАГРУЖЕННЫЕ МОДЫ ---\n");
            for(LoadedMod lm : mods != null ? mods.list() : new Seq<LoadedMod>()){
                if(lm == null) continue;
                r.append("• имя: ").append(lm.name)
                    .append(" | версия: ").append(lm.meta != null ? lm.meta.version : "?")
                    .append(" | состояние: ").append(lm.state)
                    .append(" | enabled: ").append(lm.enabled())
                    .append(" | content-ошибок: ").append(lm.hasContentErrors())
                    .append("\n  путь: ").append(lm.file != null ? lm.file.path() : "?").append('\n');
            }

            r.append("\n--- МОДЫ С ТЕМ ЖЕ ИМЕНЕМ, ЧТО И ЭТОТ ---\n");
            if(dupPaths.isEmpty()) r.append("(нет)\n");
            for(String p : dupPaths) r.append("• ").append(p).append('\n');

            r.append("\n--- ВСЁ СОДЕРЖИМОЕ С ОШИБКАМИ ---\n");
            if(errLines.isEmpty()) r.append("(нет)\n");
            for(String l : errLines) r.append("• ").append(l).append('\n');

            Fi f = (modDirectory != null ? modDirectory : Core.settings.getDataDirectory().child("mods")).child("mkukic-doctor-report.txt");
            f.writeString(r.toString(), false);
            Log.info("[MKUKIC] ModDoctor: отчёт записан в " + f.path());
        }catch(Throwable t){
            Log.err("[MKUKIC] ModDoctor: не удалось записать отчёт", t);
        }
    }
}
