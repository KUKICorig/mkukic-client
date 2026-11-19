import arc.*;
import arc.util.*;
import arc.graphics.*;
import arc.graphics.gl.*;

/** Проверяет TimeControl: 1x == ванильная дельта игры, Nx == ровно в N раз быстрее. */
public class TimeTest{
    static float gdt = 1f/60f;

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
        public float getDeltaTime(){ return gdt; }
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

    public static void main(String[] a) throws Exception{
        Core.graphics = new StubGraphics();
        mindustry.Vars.headless = false;
        mindustry.Vars.maxDeltaClient = 4f;
        mindustry.Vars.net = new mindustry.net.Net(null); // active() == false

        mkukic.MKUKICSettings.s().timeScale = 1f;
        mkukic.client.qol.ViewControl.applyTime();

        // 1) 60 FPS: базовая дельта игры == 1.0
        gdt = 1f/60f;
        setTs(1f); tick();
        eq("x1 @60fps", 1f, Time.delta, 0.01f);

        // 2) x4 @60fps == 4.0 (раньше было ~0.067 — баг)
        setTs(4f); tick();
        eq("x4 @60fps", 4f, Time.delta, 0.04f);

        // 3) x2 @60fps
        setTs(2f); tick();
        eq("x2 @60fps", 2f, Time.delta, 0.02f);

        // 4) слоу-мо x0.25
        setTs(0.25f); tick();
        eq("x0.25 @60fps", 0.25f, Time.delta, 0.01f);

        // 5) 144 FPS: ванила 60/144 = 0.4167; x4 = 1.667
        gdt = 1f/144f;
        setTs(1f); tick();
        eq("x1 @144fps", 60f/144f, Time.delta, 0.01f);
        setTs(4f); tick();
        eq("x4 @144fps", 4f*60f/144f, Time.delta, 0.02f);

        // 6) лаг-спайк 1с: ванила clamp до 4; x4 -> 16 (кламп ДО множителя)
        gdt = 1f;
        setTs(1f); tick();
        eq("x1 spike", 4f, Time.delta, 0.001f);
        setTs(4f); tick();
        eq("x4 spike", 16f, Time.delta, 0.001f);

        // 7) NaN/Inf дельта: ванила -> 1, x2 -> 2
        gdt = Float.NaN;
        setTs(1f); tick();
        eq("x1 NaN", 1f, Time.delta, 0.001f);
        setTs(2f); tick();
        eq("x2 NaN", 2f, Time.delta, 0.001f);
        gdt = Float.POSITIVE_INFINITY;
        setTs(1f); tick();
        eq("x1 Inf", 1f, Time.delta, 0.001f);

        // 8) откат к 1x восстанавливает ванильного провайдера
        gdt = 1f/60f;
        setTs(1f); tick();
        eq("rollback x1", 1f, Time.delta, 0.01f);

        // 9) эталон: ванильный провайдер ClientLauncher (clamp(gdt*60,1e-4,4)) совпадает с нашим x1
        for(float d : new float[]{1f/60f, 1f/30f, 1f/144f, 0.5f, 1f, Float.NaN}){
            gdt = d;
            setTs(1f); tick();
            float r = d * 60f;
            float expect = Float.isNaN(r) || Float.isInfinite(r) ? 1f : Math.max(1e-4f, Math.min(r, 4f));
            eq("vanilla parity gdt=" + d, expect, Time.delta, 0.001f);
        }

        System.out.println("=== TIME TEST PASSED ===");
    }

    static void setTs(float v){
        mkukic.MKUKICSettings.s().timeScale = v;
        mkukic.client.qol.ViewControl.applyTime();
    }
    // как в игре: updateGlobal() читает провайдера и пишет Time.delta
    static void tick(){ Time.updateGlobal(); }
    static void eq(String what, float exp, float got, float eps){
        if(Math.abs(exp - got) > eps) throw new AssertionError(what + ": ожидалось " + exp + ", получено " + got);
        System.out.println("OK  " + what + " -> delta=" + got);
    }
}
