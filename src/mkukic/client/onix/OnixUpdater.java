package mkukic.client.onix;

import arc.*;
import arc.files.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.math.*;
import arc.scene.ui.layout.*;
import arc.util.*;
import arc.util.Http.*;
import mindustry.gen.*;
import mindustry.mod.Mods.*;
import mindustry.ui.*;
import mkukic.*;
import mkukic.client.*;

import static mindustry.Vars.*;

/**
 * <b>Свой апдейтер</b> — порт блока «Загрузка и обновления» из Onix Lite 3.4.5/3.4.6:
 *
 * <ul>
 *  <li>«Клиент сам проверяет обновления и ставит новую версию (с откатом)»;</li>
 *  <li>«Обновление скачивается в фоне — игра не зависает»;</li>
 *  <li>«В окне загрузки видно переход версий, процент и оставшееся время»;</li>
 *  <li>«Кнопка „Отменить“ удаляет недокачанный файл»;</li>
 *  <li>«Единый стиль полосы загрузки: своя полоса со звездой и бегущим бликом».</li>
 * </ul>
 *
 * <p>Проверяются релизы GitHub-репозитория из настройки {@code onixUpdateRepo}
 * (по умолчанию — репозиторий MKUKIC). Скачанный jar кладётся в папку модов рядом
 * со старым; старая версия переименовывается в {@code .bak} — это и есть «откат».
 */
public class OnixUpdater{
    public enum State{ idle, checking, available, downloading, done, error }

    public static State state = State.idle;
    public static String latestTag = "";
    public static String latestUrl = "";
    public static long latestSize;
    public static String message = "";
    public static float progress;      // 0…1
    public static float etaSeconds = -1f;
    public static String currentVersion = "";

    private static boolean inited;
    private static float lastCheck = -1e9f;
    private static Fi targetFile;
    private static long downloaded, speedSamples, speedBytes;
    private static float speedAt;

    public static void init(){
        if(inited) return;
        inited = true;
        currentVersion = MKUKICMod.version;
    }

    public static void tick(){
        if(headless) return;
        MKUKICSettings s = MKUKICSettings.s();
        if(!s.onixEnabled || !s.onixUpdateCheck) return;
        // автопроверка раз в 30 минут и при заходе в меню
        if(state == State.idle && Time.time - lastCheck > 60f * 60f * 30f){
            check();
        }
    }

    public static void uiTick(){
        if(!inited) return;
        if(state == State.downloading) updateEta();
    }

    // ============================== ПРОВЕРКА ==============================

    /** Проверить релизы GitHub (асинхронно — игра не зависает). */
    public static void check(){
        MKUKICSettings s = MKUKICSettings.s();
        if(!s.onixUpdateCheck){
            message = "проверка обновлений выключена в настройках";
            return;
        }
        lastCheck = Time.time;
        state = State.checking;
        message = "проверяю релизы " + repo() + "…";
        String url = "https://api.github.com/repos/" + repo() + "/releases/latest";
        try{
            Http.get(url, res -> {
                String body = res.getResultAsString();
                parseRelease(body);
            }, err -> {
                state = State.error;
                message = "не удалось проверить: " + err;
            });
        }catch(Throwable t){
            state = State.error;
            message = "не удалось проверить: " + t;
        }
    }

    static String repo(){
        String r = MKUKICSettings.s().onixUpdateRepo;
        return r == null || r.isEmpty() ? "KUKICorig/MKukic" : r.trim();
    }

    /**
     * Разбор ответа GitHub API без внешних библиотек: берём tag_name и первый
     * browser_download_url с .jar.
     */
    public static void parseRelease(String json){
        try{
            latestTag = jsonField(json, "tag_name");
            String name = jsonField(json, "name");
            // ищем .jar-ассет
            String url = "";
            long size = 0;
            int idx = 0;
            while(true){
                int a = json.indexOf("browser_download_url", idx);
                if(a < 0) break;
                String u = jsonValueAt(json, a);
                if(u.endsWith(".jar")){
                    url = u;
                    int s = json.indexOf("\"size\"", a);
                    if(s > 0 && s - a < 400){
                        String sz = jsonValueAt(json, s);
                        try{ size = Long.parseLong(sz); }catch(Throwable ignored){}
                    }
                    break;
                }
                idx = a + 10;
            }
            latestUrl = url;
            latestSize = size;

            if(latestTag.isEmpty()){
                state = State.error;
                message = "в репозитории " + repo() + " нет релизов";
                return;
            }
            int cmp = compareVersions(latestTag, currentVersion);
            if(cmp > 0 && !url.isEmpty()){
                state = State.available;
                message = "доступна " + latestTag + " (сейчас " + currentVersion + ")"
                    + (name.isEmpty() ? "" : " — " + name);
            }else if(cmp > 0){
                state = State.error;
                message = "доступна " + latestTag + ", но в релизе нет jar";
            }else{
                state = State.idle;
                message = "у вас последняя версия (" + currentVersion + ")";
            }
        }catch(Throwable t){
            state = State.error;
            message = "разбор ответа не удался: " + t;
        }
    }

    /** Сравнение «1.19.0» против «v1.18.0» → 1 / 0 / -1. */
    public static int compareVersions(String a, String b){
        if(a == null || b == null) return 0;
        String[] pa = a.replaceAll("[^0-9.]", "").split("\\.");
        String[] pb = b.replaceAll("[^0-9.]", "").split("\\.");
        int n = Math.max(pa.length, pb.length);
        for(int i = 0; i < n; i++){
            int x = i < pa.length && !pa[i].isEmpty() ? Strings.parseInt(pa[i], 0) : 0;
            int y = i < pb.length && !pb[i].isEmpty() ? Strings.parseInt(pb[i], 0) : 0;
            if(x != y) return x > y ? 1 : -1;
        }
        return 0;
    }

    static String jsonField(String json, String key){
        int a = json.indexOf("\"" + key + "\"");
        if(a < 0) return "";
        return jsonValueAt(json, a);
    }

    static String jsonValueAt(String json, int keyIndex){
        int c = json.indexOf(':', keyIndex);
        if(c < 0) return "";
        int i = c + 1;
        while(i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
        if(i >= json.length()) return "";
        if(json.charAt(i) == '"'){
            int e = json.indexOf('"', i + 1);
            return e < 0 ? "" : json.substring(i + 1, e);
        }
        int e = i;
        while(e < json.length() && ",}\n".indexOf(json.charAt(e)) < 0) e++;
        return json.substring(i, e).trim();
    }

    // ============================== СКАЧИВАНИЕ ==============================

    /** Скачать обновление в фоне и поставить его в папку модов. */
    public static void download(){
        if(state != State.available || latestUrl.isEmpty()){
            message = "нечего скачивать";
            return;
        }
        MKUKICSettings s = MKUKICSettings.s();
        Fi modsDir = s.onixUpdateBackground ? modsFolder() : null;
        if(modsDir == null){
            state = State.error;
            message = "не найдена папка модов";
            return;
        }
        targetFile = modsDir.child("MKUKICClient-" + latestTag.replaceAll("[^0-9.a-zA-Z_-]", "") + ".jar");
        Fi part = modsDir.child(targetFile.name() + ".part");
        state = State.downloading;
        progress = 0f;
        downloaded = 0;
        speedBytes = 0;
        speedSamples = 0;
        speedAt = Time.time;
        etaSeconds = -1f;
        message = "скачиваю " + latestTag + " в фоне…";

        try{
            Http.get(latestUrl, res -> {
                try{
                    part.writeBytes(res.getResult(), false);
                    downloaded = res.getResult().length;
                    finishInstall(part, targetFile);
                }catch(Throwable t){
                    state = State.error;
                    message = "ошибка записи: " + t;
                }
            }, err -> {
                state = State.error;
                message = "скачивание не удалось: " + err;
                try{ if(part.exists()) part.delete(); }catch(Throwable ignored){}
            });
        }catch(Throwable t){
            state = State.error;
            message = "скачивание не удалось: " + t;
        }
    }

    /** «Кнопка „Отменить“ удаляет недокачанный файл». */
    public static void cancel(){
        try{
            if(targetFile != null){
                Fi part = targetFile.sibling(targetFile.name() + ".part");
                if(part.exists()) part.delete();
            }
        }catch(Throwable ignored){}
        state = State.idle;
        progress = 0f;
        etaSeconds = -1f;
        message = "скачивание отменено, недокачанный файл удалён";
    }

    /** «Ставит новую версию (с откатом)»: старая переименовывается в .bak. */
    public static void finishInstall(Fi part, Fi target){
        try{
            if(!part.exists()){
                state = State.error;
                message = "файл не скачался";
                return;
            }
            // откат: прячем текущую версию мода
            LoadedMod lm = mods.getMod(MKUKICMod.class);
            if(lm != null && lm.file != null && lm.file.exists()){
                Fi bak = lm.file.sibling(lm.file.name() + ".bak");
                try{
                    lm.file.moveTo(bak);
                }catch(Throwable ignored){}
            }
            part.moveTo(target);
            progress = 1f;
            state = State.done;
            message = "готова " + latestTag + " — перезапустите игру (старая версия в .bak)";
            MKUKICMod.toast("[accent]Onix[]: " + message);
        }catch(Throwable t){
            state = State.error;
            message = "установка не удалась: " + t;
        }
    }

    /** Папка модов клиента. */
    public static @Nullable Fi modsFolder(){
        try{
            return Core.settings.getDataDirectory().child("mods");
        }catch(Throwable t){
            return null;
        }
    }

    /** Откат: вернуть .bak на место. */
    public static boolean rollback(){
        try{
            LoadedMod lm = mods.getMod(MKUKICMod.class);
            Fi dir = modsFolder();
            if(dir == null) return false;
            Fi bak = null;
            for(Fi f : dir.list()){
                if(f.name().endsWith(".jar.bak")){
                    bak = f;
                    break;
                }
            }
            if(bak == null) return false;
            Fi back = bak.sibling(bak.name().substring(0, bak.name().length() - 4));
            bak.moveTo(back);
            message = "откат выполнен: " + back.name() + " — перезапустите игру";
            return true;
        }catch(Throwable t){
            message = "откат не удался: " + t;
            return false;
        }
    }

    /** Пересчёт процента и ETA («процент и оставшееся время»). */
    private static void updateEta(){
        if(latestSize <= 0 || downloaded <= 0) return;
        progress = Mathf.clamp((float)downloaded / latestSize);
        float dt = (Time.time - speedAt) / 60f;
        if(dt > 0.5f){
            float speed = (downloaded - speedBytes) / dt;
            speedAt = Time.time;
            speedBytes = downloaded;
            if(speed > 1f) etaSeconds = (latestSize - downloaded) / speed;
        }
    }

    public static String etaText(){
        if(etaSeconds < 0f) return "—";
        int s = (int)etaSeconds;
        if(s < 60) return s + "с";
        return (s / 60) + "м " + (s % 60) + "с";
    }

    // ============================== ПОЛОСА ЗАГРУЗКИ ==============================

    /**
     * «Единый стиль полосы загрузки: своя полоса со звездой и бегущим бликом».
     * Рисуется в экранном слое, пока идёт скачивание.
     */
    public static void drawScreen(){
        if(headless) return;
        MKUKICSettings s = MKUKICSettings.s();
        if(!s.onixEnabled || !s.onixLoadBar) return;
        if(state != State.downloading && state != State.done && state != State.error) return;
        if(state == State.done && Time.time - lastCheck > 60f * 5f) return;

        float w = 420f, h = 16f;
        float x = Core.graphics.getWidth() / 2f - w / 2f;
        float y = 64f;

        Draw.color(0f, 0f, 0f, 0.6f);
        Fill.rect(x + w / 2f, y + h / 2f, w + 8f, h + 8f);

        float p = state == State.done ? 1f : Mathf.clamp(progress);
        // фиолетовая полоса со свечением
        Draw.color(Onix.theme, 0.9f);
        Fill.rect(x + w * p / 2f, y + h / 2f, w * p, h);
        // бегущий блик
        float gl = (Time.time / 40f) % 1f;
        if(p > 0.02f){
            float gx = x + Mathf.clamp(gl, 0f, p) * w;
            Draw.color(Color.white, 0.35f);
            Fill.rect(gx, y + h / 2f, 26f, h);
        }
        Draw.color(Onix.theme2, 0.9f);
        Lines.stroke(1.4f);
        Lines.rect(x, y, w, h);
        // звезда
        Draw.color(Onix.theme2, 1f);
        OnixNav.star(x - 18f, y + h / 2f, 7f + Mathf.absin(Time.time, 6f, 1.5f));
        Draw.reset();

        Font font = Fonts.def;
        font.setColor(Color.white);
        font.getData().setScale(0.95f);
        String txt = "[#" + Onix.theme.toString() + "]MKUKIC[] " + currentVersion + " → " + latestTag
            + "  [lightgray]" + (int)(p * 100f) + "%  осталось: " + etaText();
        GlyphLayout g = new GlyphLayout(font, txt);
        font.draw(g, x + w / 2f - g.width / 2f, y + h + 18f);
        Draw.reset();
    }
}
