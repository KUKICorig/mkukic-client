package mkukic.client.qol;

import arc.*;
import arc.files.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import mindustry.ui.*;
import mkukic.*;
import mkukic.client.*;

import static mindustry.Vars.*;

/**
 * Бэкапы кода процессоров (порт части «Помощника логики (mlog)» из MI2-Utilities:
 * «плановые бэкапы кода»).
 *
 * Редактор логики v8 умеет сериализовать код целиком: LCanvas.save() -> String и
 * LCanvas.load(String). Мы снимаем снапшоты открытого кода в папку конфига мода
 * (config/mkukic-client/logic/), держим последние N (авто-прунинг) и восстанавливаем
 * их обратно в редактор. Дальше пользователь applies код в диалоге процессора как обычно.
 *
 * Именование файлов и прунинг — чистые функции, покрыты FeatureTest.
 */
public class LogicBackup{
    public static final int keep = 20;

    public static Fi folder(){
        Fi base = MKUKICSettings.dir();
        if(base == null) base = Core.settings.getDataDirectory().child("config").child("mkukic-client");
        Fi f = base.child("logic");
        try{ f.mkdirs(); }catch(Throwable ignored){}
        return f;
    }

    /** Код, сейчас находящийся в редакторе логики (или null). */
    public static String currentCode(){
        try{
            if(ui != null && ui.logic != null && ui.logic.canvas != null){
                String s = ui.logic.canvas.save();
                if(s != null && !s.isEmpty()) return s;
            }
        }catch(Throwable t){
            Log.err("[MKUKIC] LogicBackup: не удалось снять копию кода", t);
        }
        return null;
    }

    /** Безопасное имя файла из пользовательского (чистая функция). */
    public static String sanitize(String name){
        if(name == null) return "snapshot";
        StringBuilder sb = new StringBuilder();
        for(int i = 0; i < name.length() && sb.length() < 40; i++){
            char c = name.charAt(i);
            if(Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.' || c == ' ') sb.append(c);
        }
        String out = sb.toString().trim().replace(' ', '_');
        return out.isEmpty() ? "snapshot" : out;
    }

    static String stamp(){
        return new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date());
    }

    public static Fi saveSnapshot(String name){
        String code = currentCode();
        if(code == null){
            MKUKICMod.toast("[scarlet]Нет кода в редакторе логики — открой процессор (или процессорный дисплей) и повтори");
            return null;
        }
        try{
            Fi f = folder().child(sanitize(name) + "-" + stamp() + ".json");
            f.writeString(code, false);
            prune(keep);
            MKUKICMod.toast("[accent]" + (char)mindustry.gen.Iconc.save + " [white]Снимок сохранён: " + f.name());
            return f;
        }catch(Throwable t){
            Log.err("[MKUKIC] LogicBackup: save", t);
            MKUKICMod.toast("[scarlet]Не удалось сохранить снимок (см. лог)");
            return null;
        }
    }

    /** Список снимков (новые сверху). */
    public static Seq<Fi> list(){
        Seq<Fi> out = new Seq<>();
        try{
            for(Fi f : folder().list(".json")) out.add(f);
        }catch(Throwable ignored){}
        out.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return out;
    }

    /** Прунинг: оставить только keep самых свежих. Возвращает число удалённых. */
    public static int prune(int keepN){
        Seq<Fi> l = list();
        int removed = 0;
        for(int i = Math.max(0, keepN); i < l.size; i++){
            try{
                if(l.get(i).delete()) removed++;
            }catch(Throwable ignored){}
        }
        return removed;
    }

    public static boolean loadInto(Fi f){
        try{
            if(ui == null || ui.logic == null || ui.logic.canvas == null){
                MKUKICMod.toast("[scarlet]Редактор логики недоступен");
                return false;
            }
            ui.logic.canvas.load(f.readString());
            MKUKICMod.toast("[accent]Код загружен в редактор.[] Открой процессор и подтверди изменения (как после обычного редактирования).");
            return true;
        }catch(Throwable t){
            Log.err("[MKUKIC] LogicBackup: load", t);
            MKUKICMod.toast("[scarlet]Файл повреждён или не читается");
            return false;
        }
    }

    // ======================= ПОИСК/ЗАМЕНА (чистые функции, тестируются) =======================

    /** Число неперекрывающихся вхождений find в hay. */
    public static int countOccurrences(String hay, String find){
        if(hay == null || find == null || find.isEmpty()) return 0;
        int c = 0, i = 0;
        while((i = hay.indexOf(find, i)) >= 0){
            c++;
            i += find.length();
        }
        return c;
    }

    /** Замена всех неперекрывающихся вхождений (как String.replace, но без regex-подвохов). */
    public static String replaceAll(String hay, String find, String repl){
        if(hay == null || find == null || find.isEmpty()) return hay;
        if(repl == null) repl = "";
        StringBuilder sb = new StringBuilder();
        int i = 0, j;
        while((j = hay.indexOf(find, i)) >= 0){
            sb.append(hay, i, j).append(repl);
            i = j + find.length();
        }
        sb.append(hay, i, hay.length());
        return sb.toString();
    }

    // ============================ ОКНО ============================

    /** Таблица списка снимков — обновляется на месте, без пересборки окна. */
    private static Table bodyRef;

    public static void build(Table t){
        QolWindows.section(t, "Бэкапы кода процессоров (mlog)");
        t.add("[lightgray]Снимок копирует код, который СЕЙЧАС в редакторе логики\n"
            + "(последний открытый процессор/дисплей). Хранится в папке конфига мода,\n"
            + "последние " + keep + " штук. «Загрузить» возвращает код в редактор —\n"
            + "затем открой процессор и подтверди, как обычно.").left().top().wrap().width(380f).pad(3f, 0f, 6f, 0f).row();

        t.table(c -> {
            c.left();
            TextField name = new TextField("snapshot");
            c.add(name).width(200f).padRight(6f);
            c.button((char)mindustry.gen.Iconc.save + " Снять снимок", MKUKICSkin.actBtn, () -> {
                saveSnapshot(name.getText());
                refresh();
            }).minWidth(170f).height(34f);
        }).left().row();

        // ---- поиск и замена (порт части mlog-помощника MI2: «поиск/замена») ----
        QolWindows.section(t, "Поиск и замена в коде");
        t.add("[lightgray]Работает с кодом, который сейчас в редакторе логики.")
            .left().wrap().width(380f).padBottom(3f).row();
        t.table(c -> {
            c.left();
            TextField find = new TextField();
            find.setMessageText("найти…");
            c.add(find).width(170f).padRight(5f);
            TextField repl = new TextField();
            repl.setMessageText("заменить на…");
            c.add(repl).width(170f).padRight(5f);
            c.button("Посчитать", MKUKICSkin.actBtn, () -> {
                String code = currentCode();
                if(code == null){
                    MKUKICMod.toast("[scarlet]Нет кода в редакторе логики");
                    return;
                }
                String f = find.getText();
                if(f == null || f.isEmpty()){
                    MKUKICMod.toast("[scarlet]Пустая строка поиска");
                    return;
                }
                MKUKICMod.toast("[accent]Вхождений: " + countOccurrences(code, f));
            }).minWidth(110f).height(30f).padRight(4f);
            c.button("Заменить все", MKUKICSkin.actBtn, () -> {
                String code = currentCode();
                if(code == null){
                    MKUKICMod.toast("[scarlet]Нет кода в редакторе логики");
                    return;
                }
                String f = find.getText();
                if(f == null || f.isEmpty()){
                    MKUKICMod.toast("[scarlet]Пустая строка поиска");
                    return;
                }
                int n = countOccurrences(code, f);
                if(n == 0){
                    MKUKICMod.toast("[lightgray]Не найдено: " + f);
                    return;
                }
                try{
                    ui.logic.canvas.load(replaceAll(code, f, repl.getText()));
                    MKUKICMod.toast("[accent]Заменено: " + n + " [lightgray](открой процессор и подтверди, как после правки)");
                }catch(Throwable ex){
                    MKUKICMod.toast("[scarlet]Редактор логики недоступен");
                }
            }).minWidth(140f).height(30f);
        }).left().row();
        t.table(c -> {
            c.left();
            c.button("Копировать код в буфер", MKUKICSkin.actBtn, () -> {
                String code = currentCode();
                if(code == null){
                    MKUKICMod.toast("[scarlet]Нет кода в редакторе логики");
                    return;
                }
                Core.app.setClipboardText(code);
                MKUKICMod.toast("[accent]Код скопирован в буфер обмена");
            }).minWidth(190f).height(30f).padRight(4f);
            c.button("Загрузить код из буфера", MKUKICSkin.actBtn, () -> {
                try{
                    String clip = Core.app.getClipboardText();
                    if(clip == null || clip.isEmpty()){
                        MKUKICMod.toast("[scarlet]Буфер обмена пуст");
                        return;
                    }
                    ui.logic.canvas.load(clip);
                    MKUKICMod.toast("[accent]Код из буфера загружен в редактор");
                }catch(Throwable ex){
                    MKUKICMod.toast("[scarlet]Редактор логики недоступен");
                }
            }).minWidth(210f).height(30f);
        }).left().padTop(4f).row();

        QolWindows.section(t, "Снимки");
        t.add().row();
        bodyRef = new Table();
        t.add(bodyRef).top().left().growX().row();
        refresh();
    }

    public static void refresh(){
        if(bodyRef == null) return;
        Table body = bodyRef;
        body.clearChildren();
        Seq<Fi> l = list();
        if(l.size == 0){
            body.add("[darkgray]Снимков пока нет.").left().pad(4f).row();
        }else{
            body.pane(p -> {
                p.left().top();
                for(Fi f : l){
                    p.table(r -> {
                        r.left();
                        r.add("[lightgray]" + f.nameWithoutExtension()).left().growX().wrap().width(240f);
                        r.button("Загрузить", MKUKICSkin.actBtn, () -> loadInto(f)).minWidth(96f).height(28f).padLeft(4f);
                        r.button("[scarlet]✕", MKUKICSkin.actBtn, () -> {
                            try{ f.delete(); }catch(Throwable ignored){}
                            refresh();
                        }).size(28f).padLeft(4f);
                    }).left().growX().pad(1.5f).row();
                }
            }).growX().maxHeight(220f).row();
        }
        body.table(c -> {
            c.left();
            c.add("[darkgray]Папка: " + folder().path()).left().wrap().width(380f);
        }).left().padTop(4f).row();
    }

}
