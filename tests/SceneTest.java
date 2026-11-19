import arc.*;
import arc.graphics.*;
import arc.graphics.gl.*;
import arc.scene.*;
import arc.util.*;
import java.lang.reflect.*;

/**
 * Регрессия v8: arc.scene.Group.act() пропускает НЕВИДИМЫХ детей (act/update не вызываются),
 * но updateVisibility() (visible(Boolp)) выполняется для всех каждый кадр.
 * Старый паттерн OverlayUI (update-runnable, переключающий .visible) в v8 «умирает» навсегда.
 */
public class SceneTest{
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

    public static void main(String[] a){
        Core.graphics = new StubGraphics();
        Core.app = (Application)Proxy.newProxyInstance(SceneTest.class.getClassLoader(),
            new Class[]{Application.class}, (p, m, args) -> {
                Class<?> rt = m.getReturnType();
                if(rt == boolean.class) return false;
                if(rt == float.class) return 0f;
                if(rt == int.class) return 0;
                if(rt == long.class) return 0L;
                if(rt == double.class) return 0d;
                return null;
            });

        // GL20-заглушка: Scene() в конструкторе делает glViewport через Core.gl
        Core.gl = (GL20)Proxy.newProxyInstance(SceneTest.class.getClassLoader(),
            new Class[]{GL20.class}, (p, m, args) -> {
                Class<?> rt = m.getReturnType();
                if(rt == boolean.class) return false;
                if(rt == float.class) return 0f;
                if(rt == int.class) return 0;
                if(rt == long.class) return 0L;
                if(rt == double.class) return 0d;
                return null;
            });

        Scene scene = new Scene();
        final boolean[] cond = {true};

        // ---- СТАРЫЙ паттерн (как в OverlayUI до фикса): update-runnable переключает visible ----
        Element old = new Element();
        old.update(() -> old.visible = cond[0]);
        scene.add(old);
        scene.act();                       // cond=true -> виден
        assertTrue("старый: виден при cond=true", old.visible);
        cond[0] = false;
        scene.act();                       // act ещё вызывается (был виден) -> скрывается
        assertTrue("старый: скрыт при cond=false", !old.visible);
        cond[0] = true;
        scene.act(); scene.act(); scene.act();
        assertTrue("СТАРОТНЫЙ ПАТТЕРН МЁРТВ (баг v8 воспроизведён)", !old.visible);

        // ---- НОВЫЙ паттерн: visible(Boolp) ----
        Element nw = new Element();
        nw.visible(() -> cond[0]);
        scene.add(nw);
        cond[0] = false;
        scene.act();
        assertTrue("новый: скрыт при cond=false", !nw.visible);
        cond[0] = true;
        scene.act();
        assertTrue("новый: СНОВА ВИДЕН при cond=true (фикс работает)", nw.visible);
        cond[0] = false;
        scene.act();
        assertTrue("новый: скрывается обратно", !nw.visible);
        cond[0] = true;
        scene.act();
        assertTrue("новый: многократные циклы скрытия/показа работают", nw.visible);

        System.out.println("=== SCENE TEST PASSED ===");
    }

    static void assertTrue(String msg, boolean v){
        if(!v) throw new AssertionError(msg);
        System.out.println("OK  " + msg);
    }
}
