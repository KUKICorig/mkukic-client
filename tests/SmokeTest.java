import arc.*;
import arc.graphics.*;
import arc.graphics.gl.*;
import arc.input.*;
import arc.struct.*;
import arc.util.*;

import java.io.*;
import java.util.*;

/**
 * SmokeTest (v1.10.0):
 * 1) загрузка ВСЕХ классов мода через Class.forName (ловит падения статических инициализаторов);
 * 2) горячие клавиши: mkukic_build_wheel=F2, mkukic_form_move=F3, mkukic_qol_hub=F4 — без конфликтов имён;
 * 3) MKUKICSettings: дефолты новых полей v1.10 + round-trip save/load через временный файл;
 * 4) MKUKICKeys.init() и describe() не падают.
 */
public class SmokeTest{
    static int passed, failed;

    static class StubGraphics extends Graphics{
        public GL20 getGL20(){ return null; }
        public void setGL20(GL20 gl){}
        public GL30 getGL30(){ return null; }
        public void setGL30(GL30 gl){}
        public int getWidth(){ return 1280; }
        public int getHeight(){ return 720; }
        public int getBackBufferWidth(){ return 1280; }
        public int getBackBufferHeight(){ return 720; }
        public long getFrameId(){ return 0; }
        public float getDeltaTime(){ return 1f/60f; }
        public int getFramesPerSecond(){ return 60; }
        public GLVersion getGLVersion(){ return null; }
        public float getPpiX(){ return 96; }
        public float getPpiY(){ return 96; }
        public float getPpcX(){ return 96; }
        public float getPpcY(){ return 96; }
        public float getDensity(){ return 1; }
        public void setTitle(String t){}
        public void setVSync(boolean v){}
        public BufferFormat getBufferFormat(){ return null; }
        public boolean supportsExtension(String e){ return false; }
        public boolean isContinuousRendering(){ return true; }
        public void setContinuousRendering(boolean c){}
        public void requestRendering(){}
        public boolean isFullscreen(){ return false; }
        public Cursor newCursor(Pixmap p, int x, int y){ return null; }
        protected void setCursor(Cursor c){}
        protected void setSystemCursor(Cursor.SystemCursor c){}
        public void dispose(){}
    }

    public static void main(String[] args) throws Exception{
        Core.graphics = new StubGraphics();
        mindustry.Vars.headless = false;

        loadAllClasses(args.length > 0 ? args[0] : "/tmp/nxout");
        keyBinds();
        settingsRoundTrip();

        System.out.println("=========================================");
        System.out.println("SmokeTest: passed=" + passed + " failed=" + failed);
        if(failed > 0) System.exit(1);
        System.out.println("ALL SMOKE TESTS PASSED");
    }

    static void loadAllClasses(String root) throws Exception{
        File dir = new File(root, "mkukic");
        List<String> names = new ArrayList<>();
        collect(dir, "mkukic", names);
        ok("classes found >= 46 (" + names.size() + ")", names.size() >= 46);
        Collections.sort(names);
        int loaded = 0;
        for(String n : names){
            try{
                Class.forName(n, true, SmokeTest.class.getClassLoader());
                loaded++;
            }catch(Throwable t){
                failed++;
                System.out.println("FAIL classload " + n + ": " + t);
                t.printStackTrace(System.out);
            }
        }
        ok("all " + names.size() + " mkukic classes loaded (" + loaded + ")", loaded == names.size());
    }

    static void collect(File f, String pkg, List<String> out){
        File[] kids = f.listFiles();
        if(kids == null) return;
        for(File k : kids){
            if(k.isDirectory()) collect(k, pkg + "." + k.getName(), out);
            else if(k.getName().endsWith(".class")){
                String cn = k.getName().substring(0, k.getName().length() - 6);
                out.add(pkg + "." + cn);
            }
        }
    }

    static void keyBinds(){
        try{
            mkukic.client.MKUKICKeys.init();
        }catch(Throwable t){
            failed++; System.out.println("FAIL MKUKICKeys.init: " + t); return;
        }
        Seq<KeyBind> all = KeyBind.all;
        HashMap<String, KeyBind> byName = new HashMap<>();
        for(KeyBind kb : all){
            ok("no duplicate bind name " + kb.name, !byName.containsKey(kb.name));
            byName.put(kb.name, kb);
        }
        expectBind(byName, "mkukic_build_wheel", KeyCode.f2);
        expectBind(byName, "mkukic_form_move", KeyCode.f3);
        expectBind(byName, "mkukic_qol_hub", KeyCode.f4);
        String d = mkukic.client.MKUKICKeys.describe();
        ok("describe mentions F2", d.contains("F2"));
        ok("describe mentions F3", d.contains("F3"));
    }

    static void expectBind(HashMap<String, KeyBind> byName, String name, KeyCode key){
        KeyBind kb = byName.get(name);
        if(kb == null){ failed++; System.out.println("FAIL missing bind " + name); return; }
        if(kb.defaultValue == key) passed++;
        else{ failed++; System.out.println("FAIL bind " + name + " default=" + kb.defaultValue + " expected=" + key); }
    }

    static void settingsRoundTrip() throws Exception{
        File tmpDir = new File(System.getProperty("java.io.tmpdir"), "mkukic-smoke-" + System.nanoTime());
        tmpDir.mkdirs();
        tmpDir.deleteOnExit();
        mkukic.MKUKICSettings.init(new arc.files.Fi(tmpDir));
        mkukic.MKUKICSettings.load(); // файла нет -> дефолты

        mkukic.MKUKICSettings s = mkukic.MKUKICSettings.s();
        // дефолты v1.10
        eqS("formType default", "line", s.formType);
        eqF("formSpacing default", 24f, s.formSpacing);
        eqS("drillName default", "mechanical-drill", s.drillName);
        eqF("musicVolume default", 0.8f, s.musicVolume);
        ok("qolPathChokes default true", s.qolPathChokes);
        ok("qolPathOverlay default false", !s.qolPathOverlay);
        ok("qolDrillOverlay default false", !s.qolDrillOverlay);
        ok("musicLoop default false", !s.musicLoop);
        ok("qolSmoothDelta default false", !s.qolSmoothDelta);
        ok("wheelFav not null", s.wheelFav != null);
        ok("wheelRecent not null", s.wheelRecent != null);

        // изменяем, сохраняем, перечитываем
        s.formType = "wedge";
        s.formSpacing = 40f;
        s.drillName = "pneumatic-drill";
        s.musicVolume = 0.45f;
        s.qolSmoothDelta = true;
        s.wheelFav.add("duo");
        s.wheelRecent.add("conveyor");
        mkukic.MKUKICSettings.save();

        // dir() указывает на временную папку (LogicBackup/MusicBox используют её)
        ok("dir() == config folder", mkukic.MKUKICSettings.dir() != null && mkukic.MKUKICSettings.dir().path().replace('\\', '/').equals(tmpDir.getAbsolutePath().replace('\\', '/')));

        mkukic.MKUKICSettings.load(); // перечитать из файла в тот же синглтон
        mkukic.MKUKICSettings s2 = mkukic.MKUKICSettings.s();
        eqS("formType roundtrip", "wedge", s2.formType);
        eqF("formSpacing roundtrip", 40f, s2.formSpacing);
        eqS("drillName roundtrip", "pneumatic-drill", s2.drillName);
        eqF("musicVolume roundtrip", 0.45f, s2.musicVolume);
        ok("qolSmoothDelta roundtrip", s2.qolSmoothDelta);
        ok("wheelFav roundtrip", s2.wheelFav.contains("duo"));
        ok("wheelRecent roundtrip", s2.wheelRecent.contains("conveyor"));
    }

    static void eqS(String what, String exp, String got){
        if(exp.equals(got)) passed++; else{ failed++; System.out.println("FAIL " + what + ": [" + exp + "] != [" + got + "]"); }
    }

    static void eqF(String what, float exp, float got){
        if(Math.abs(exp - got) < 0.001f) passed++; else{ failed++; System.out.println("FAIL " + what + ": " + exp + " != " + got); }
    }

    static void ok(String what, boolean cond){
        if(cond) passed++; else{ failed++; System.out.println("FAIL " + what); }
    }
}
