import arc.util.serialization.*;
import mkukic.MKUKICSettings;

public class TestSer{
    public static void main(String[] a){
        Json json = new Json();
        json.setIgnoreUnknownFields(true);
        json.setOutputType(JsonWriter.OutputType.json);

        // 1) round-trip новых полей
        MKUKICSettings s = new MKUKICSettings();
        s.winPos.put("hub", "10|20|0");
        s.winPos.put("rules", "300|400|1");
        s.qolTrackedItems.add("thorium");
        s.zoomMult = 2.5f;
        s.tripwireRadius = 1234f;
        s.timeScale = 0.5f;
        s.perfMaster = true;

        String out = json.prettyPrint(json.toJson(s, MKUKICSettings.class));
        int i = out.indexOf("winPos");
        System.out.println("--- JSON fragment ---");
        System.out.println(out.substring(Math.max(0, i), Math.min(out.length(), i + 160)));

        MKUKICSettings back = json.fromJson(MKUKICSettings.class, out);
        check("winPos.hub", "10|20|0", back.winPos.get("hub"));
        check("winPos.rules", "300|400|1", back.winPos.get("rules"));
        check("tracked contains thorium", true, back.qolTrackedItems.contains("thorium"));
        check("zoomMult", 2.5f, back.zoomMult);
        check("tripwireRadius", 1234f, back.tripwireRadius);
        check("timeScale", 0.5f, back.timeScale);
        check("perfMaster", true, back.perfMaster);

        // 2) copyFields (так делает MKUKICSettings.load)
        MKUKICSettings target = new MKUKICSettings();
        json.copyFields(back, target);
        check("copyFields winPos.hub", "10|20|0", target.winPos.get("hub"));
        check("copyFields tracked size", 4, target.qolTrackedItems.size);

        // 3) старый конфиг без новых полей — должен подняться с дефолтами
        String oldCfg = "{\"aimbot\":true,\"speedMult\":2.0,\"cmdEntries\":[\"a|/b\"]}";
        MKUKICSettings up = json.fromJson(MKUKICSettings.class, oldCfg);
        check("old cfg aimbot", true, up.aimbot);
        check("old cfg qolEnabled default", true, up.qolEnabled);
        check("old cfg winPos empty", 0, up.winPos.size);
        check("old cfg tracked default", 3, up.qolTrackedItems.size);
        check("old cfg qolRanges default", true, up.qolRanges);

        // 4) рефлексия Iconc (как в IconKit)
        int icons = 0;
        try{
            Class<?> ic = Class.forName("mindustry.gen.Iconc");
            for(java.lang.reflect.Field f : ic.getFields()){
                if(f.getType() == char.class && java.lang.reflect.Modifier.isStatic(f.getModifiers())) icons++;
            }
        }catch(Throwable t){
            System.out.println("Iconc reflection FAILED: " + t);
        }
        System.out.println("Iconc icons found: " + icons + (icons > 50 ? " OK" : " SUSPECT"));

        // 5) рефлексия Rules (как в RuleViewer) — только список полей, без инстанса
        int ruleFields = 0;
        try{
            Class<?> rc = Class.forName("mindustry.game.Rules");
            for(java.lang.reflect.Field f : rc.getFields()){
                if(!java.lang.reflect.Modifier.isStatic(f.getModifiers())) ruleFields++;
            }
        }catch(Throwable t){
            System.out.println("Rules reflection FAILED: " + t);
        }
        System.out.println("Rules instance fields: " + ruleFields + (ruleFields > 30 ? " OK" : " SUSPECT"));

        System.out.println("=== ALL TESTS DONE ===");
    }

    static void check(String name, Object exp, Object got){
        boolean ok = exp.equals(got) || (exp instanceof Float && got instanceof Float && Math.abs((Float)exp - (Float)got) < 0.001f);
        System.out.println((ok ? "OK  " : "FAIL") + "  " + name + " = " + got + (ok ? "" : " (expected " + exp + ")"));
        if(!ok) System.exit(1);
    }
}
