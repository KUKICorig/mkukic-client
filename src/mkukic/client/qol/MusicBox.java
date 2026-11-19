package mkukic.client.qol;

import arc.*;
import arc.audio.*;
import arc.files.*;
import arc.math.*;
import arc.scene.ui.*;
import arc.struct.*;
import arc.scene.ui.layout.*;
import arc.util.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.ui.*;
import mkukic.*;
import mkukic.client.*;

import static mindustry.Vars.*;

/**
 * Музыкальный плеер (порт «Музыкального помощника» из MI2-Utilities):
 * своя папка с треками в конфиге мода, транспорт (пред./пауза/след./стоп),
 * автопереход к следующему треку, зацикливание, громкость, плейлист.
 *
 * Папка: config/mkukic-client/music/ — кидай туда .ogg (гарантированно) или .mp3/.wav.
 * Ванильная музыка игры не глушится — убавь её отдельным слайдером в настройках Mindustry.
 *
 * Индексы плейлиста и переходы — чистые функции, покрыты FeatureTest.
 */
public class MusicBox{
    /** Треки, найденные при последнем сканировании. */
    public static Seq<Fi> tracks = new Seq<>();
    /** Индекс текущего трека (-1 = ничего). */
    public static int index = -1;

    private static Music cur;
    /** Кадр, на котором игра последний раз видела трек играющим (для автоперехода). */
    private static float lastPlayingAt = -999f;
    private static boolean userPaused;

    public static Fi folder(){
        Fi base = MKUKICSettings.dir();
        if(base == null) base = Core.settings.getDataDirectory().child("config").child("mkukic-client");
        Fi f = base.child("music");
        try{ f.mkdirs(); }catch(Throwable ignored){}
        return f;
    }

    public static void rescan(){
        tracks.clear();
        try{
            for(String ext : new String[]{"ogg", "mp3", "wav"}){
                for(Fi f : folder().list("." + ext)) tracks.add(f);
            }
            tracks.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        }catch(Throwable ignored){}
    }

    // ======================= ЧИСТЫЕ ФУНКЦИИ =======================

    /** Циклический индекс: wrap(5, 3) = 2, wrap(-1, 3) = 2, wrap(i, 0) = -1. */
    public static int wrap(int i, int n){
        if(n <= 0) return -1;
        return ((i % n) + n) % n;
    }

    // ======================= ВОСПРОИЗВЕДЕНИЕ =======================

    static void disposeCur(){
        if(cur != null){
            try{ cur.stop(); cur.dispose(); }catch(Throwable ignored){}
            cur = null;
        }
    }

    /** Запустить трек по индексу. Возвращает успех. */
    public static boolean playIndex(int i){
        if(i < 0 || i >= tracks.size) return false;
        disposeCur();
        try{
            cur = Music.create(tracks.get(i));
            index = i;
            if(cur == null || !cur.valid()) throw new IllegalStateException("bad audio");
            cur.setVolume(MKUKICSettings.s().musicVolume);
            cur.setLooping(MKUKICSettings.s().musicLoop && tracks.size == 1);
            cur.play();
            userPaused = false;
            lastPlayingAt = Time.globalTime;
            return true;
        }catch(Throwable t){
            Log.err("[MKUKIC] MusicBox: не удалось воспроизвести " + tracks.get(i).name(), t);
            disposeCur();
            MKUKICMod.toast("[scarlet]Трек не читается: " + tracks.get(i).name() + "[] ([lightgray]рекомендуется .ogg)");
            return false;
        }
    }

    public static void playPause(){
        if(tracks.size == 0){ rescan(); }
        if(tracks.size == 0){
            MKUKICMod.toast("[scarlet]Папка пуста: " + folder().path() + "[] ([lightgray]кинь туда .ogg/.mp3)");
            return;
        }
        if(cur == null || !cur.valid()){
            playIndex(wrap(index >= 0 ? index : 0, tracks.size));
        }else if(userPaused){
            try{ cur.pause(false); }catch(Throwable ignored){}
            userPaused = false;
            lastPlayingAt = Time.globalTime;
        }else{
            try{ cur.pause(true); }catch(Throwable ignored){}
            userPaused = true;
        }
    }

    public static void next(){ if(tracks.size > 0) playIndex(wrap(index + 1, tracks.size)); }
    public static void prev(){ if(tracks.size > 0) playIndex(wrap(index - 1, tracks.size)); }

    public static void stopMusic(){
        disposeCur();
        index = -1;
        userPaused = false;
    }

    public static void applyVolume(){
        MKUKICSettings.s().save();
        if(cur != null){
            try{ cur.setVolume(MKUKICSettings.s().musicVolume); }catch(Throwable ignored){}
        }
    }

    public static boolean isPlaying(){
        try{ return cur != null && cur.valid() && cur.isPlaying(); }catch(Throwable ignored){ return false; }
    }

    /** Каждый кадр (из Qol.uiTick): автопереход при окончании трека. */
    public static void tick(){
        if(cur == null || !cur.valid() || userPaused) return;
        boolean playing;
        try{ playing = cur.isPlaying(); }catch(Throwable ignored){ return; }
        if(playing){
            lastPlayingAt = Time.globalTime;
        }else if(Time.globalTime - lastPlayingAt > 60f){
            // трек закончился (без loop) — следующий
            if(MKUKICSettings.s().musicLoop && tracks.size > 1){
                playIndex(wrap(index + 1, tracks.size));
            }else{
                stopMusic();
            }
        }
    }

    static String fmtPos(float sec){
        if(sec < 0 || Float.isNaN(sec)) sec = 0;
        int m = (int)(sec / 60), s = ((int)sec) % 60;
        return m + ":" + (s < 10 ? "0" : "") + s;
    }

    // ============================ ОКНО ============================

    public static void build(Table t){
        MKUKICSettings s = MKUKICSettings.s();
        QolWindows.section(t, "Музыкальный плеер");
        t.add("[lightgray]Папка треков: []" + folder().path()
            + "\n[lightgray]Форматы: .ogg (гарантированно), .mp3, .wav. Ванильная музыка\n"
            + "игры играет отдельно — убавь её слайдером в настройках Mindustry.")
            .left().top().wrap().width(400f).pad(3f, 0f, 6f, 0f).row();

        // транспорт
        t.table(c -> {
            c.left();
            c.button("|<", MKUKICSkin.actBtn, MusicBox::prev).size(40f).padRight(4f);
            Cell<TextButton> ppCell = c.button(">", MKUKICSkin.actBtn, MusicBox::playPause);
            ppCell.minWidth(56f).height(40f).padRight(4f);
            c.button(">|", MKUKICSkin.actBtn, MusicBox::next).size(40f).padRight(4f);
            c.button((char)Iconc.cancel + "", MKUKICSkin.actBtn, MusicBox::stopMusic).size(40f).padRight(8f);
            Label pos = new Label("");
            c.add(pos).padRight(8f);
            c.update(() -> {
                ppCell.get().setText(isPlaying() ? "||" : ">");
                String name = (index >= 0 && index < tracks.size) ? tracks.get(index).nameWithoutExtension() : "—";
                float p = 0f, l = 0f;
                try{
                    if(cur != null && cur.valid()){ p = cur.getPosition(); l = cur.getLength(); }
                }catch(Throwable ignored){}
                pos.setText("[lightgray]" + name + "  " + fmtPos(p) + " / " + fmtPos(l));
            });
        }).left().row();

        // громкость + loop
        t.table(c -> {
            c.left();
            c.add("[lightgray]Громкость:").padRight(6f);
            c.slider(0f, 1f, 0.05f, s.musicVolume, v -> {
                s.musicVolume = v;
                applyVolume();
            }).width(180f).padRight(10f);
            c.check("Зациклить плейлист", s.musicLoop, v -> {
                s.musicLoop = v;
                s.save();
            }).padRight(10f);
            c.button((char)Iconc.refresh + " Сканировать", MKUKICSkin.actBtn, () -> {
                rescan();
                MKUKICMod.toast("[accent]Найдено треков: " + tracks.size);
            }).minWidth(130f).height(30f);
        }).left().padTop(6f).row();

        // плейлист
        t.add().row();
        t.pane(p -> {
            p.left().top();
            if(tracks.size == 0){
                p.add("[darkgray]Плейлист пуст — кинь файлы в папку и нажми «Сканировать».").left().pad(6f).row();
            }else{
                p.table(h -> {
                    h.image(Tex.whiteui, Pal.accent).height(1f).growX();
                    h.add("  ПЛЕЙЛИСТ (" + tracks.size + ")  ").labelAlign(Align.center);
                    h.image(Tex.whiteui, Pal.accent).height(1f).growX();
                }).growX().pad(4f, 0f, 4f, 0f).row();
                for(int i = 0; i < tracks.size; i++){
                    int fi = i;
                    p.table(r -> {
                        r.left();
                        Label lb = new Label((fi == index ? "[accent]▶ " : "[darkgray]   ") + tracks.get(fi).nameWithoutExtension());
                        r.add(lb).left().growX().wrap().width(300f);
                        r.update(() -> lb.setText((fi == index && isPlaying() ? "[accent]▶ " : fi == index ? "[accent]❚❚ " : "[darkgray]   ") + tracks.get(fi).nameWithoutExtension()));
                        r.button("Играть", MKUKICSkin.actBtn, () -> playIndex(fi)).minWidth(80f).height(26f).padLeft(4f);
                    }).left().growX().pad(1.5f);
                    p.row();
                }
            }
        }).growX().maxHeight(200f).padTop(4f).row();
    }
}
