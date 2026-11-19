package mkukic.client;

import arc.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.graphics.g2d.TextureAtlas.*;
import arc.math.*;
import arc.math.geom.*;
import arc.struct.*;
import arc.util.*;
import mindustry.ctype.*;
import mindustry.game.MapObjectives.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.input.*;
import mindustry.mod.data.*;
import mindustry.type.*;
import mindustry.ui.*;
import mindustry.world.*;
import mkukic.*;

import static mindustry.Vars.*;

/**
 * МОНЕТЫ: распознавание, подсветка и магнит (порт логики CoinMagnet 2.7.0, лицензия автора KUKIC).
 *
 * «Монеты» на серверах-минииграх — это юниты/постройки/мировые надписи/маркеры целей,
 * у которых в имени, текстуре или тексте есть слова coin/money/currency/монет/деньг/credit
 * (плюс образы карты с известным хэшем). Магнит телепортирует СОБСТВЕННОГО юнита к ближайшей
 * монете в радиусе и держит его там (обнуляя движение), пока монета не подберётся.
 * Позиция своего юнита в Mindustry авторитетна на клиенте, поэтому работает и онлайн;
 * для игры по сети радиус честно ограничен 10 тайлами (как в оригинале).
 */
public class CoinMagnet{
    private static final String[] words = {"coin", "money", "currency", "монет", "деньг", "credit"};
    private static final String coinImageHash = "TMNYNHSBXINKBQIOLKKXJMR4CXQKWJFOSDYVJKZZBHMU3R3SIHYQ";
    private static final int maxTargets = 256;
    private static final Color lineColor = Color.valueOf("ffd36b");
    private static final Color valuableColor = Color.valueOf("ff505b");
    private static final double valuableThreshold = 5000.0;

    // ---------- распознавание ----------
    private static final ObjectSet<UnlockableContent> recContent = new ObjectSet<>();
    private static final ObjectSet<TextureRegion> recRegions = new ObjectSet<>();
    private static final Seq<TextureRegion> regionList = new Seq<>();
    private static final ObjectSet<String> aliasSet = new ObjectSet<>();
    private static final Seq<String> aliases = new Seq<>();
    private static final IntSet recGlyphs = new IntSet();
    private static long nextRecognition;

    // ---------- цели ----------
    private static final Object[] targets = new Object[maxTargets];
    private static final float[] distances = new float[maxTargets];
    private static final double[] values = new double[maxTargets];
    private static final boolean[] directValues = new boolean[maxTargets];
    private static int count;
    private static float scanX, scanY;
    private static long nextScan;
    private static boolean preferHighValue;

    // ---------- магнит ----------
    private static final Vec2 target = new Vec2(), candidate = new Vec2(), destination = new Vec2();
    private static final float[] visitedX = new float[64], visitedY = new float[64];
    private static final long[] visitedAt = new long[64];
    private static int visitedCount, visitedIndex;
    private static long lastJump;
    private static boolean jumped;
    private static Unit activeUnit, heldUnit;
    private static float heldCoinX, heldCoinY, landingX, landingY;
    private static long missingSince;
    private static boolean targetMissing;

    public static void reset(){
        clearTargets();
        nextScan = 0;
        nextRecognition = 0;
        resetMagnet();
    }

    // ============================ РАСПОЗНАВАНИЕ ============================

    private static void rebuildRecognition(){
        recContent.clear(); recRegions.clear(); regionList.clear();
        aliasSet.clear(); aliases.clear(); recGlyphs.clear();
        try{
            if(state != null && state.data != null){
                Seq<ImageAsset> images = state.data.getImages();
                for(ImageAsset image : images){
                    if(image != null && (coinImageHash.equals(image.stringHash)
                        || containsWord(image.name) || containsWord(image.path))){
                        addAlias(image.name);
                        addAlias(image.path);
                        if(image.name != null && !image.name.isEmpty()) addAlias("dp-" + image.name);
                    }
                }
            }
            if(Core.atlas != null){
                for(AtlasRegion region : Core.atlas.getRegions()){
                    if(matchesName(region.name)) addRegion(region);
                }
                for(String alias : aliases){
                    if(Core.atlas.has(alias)) addRegion(Core.atlas.find(alias));
                }
            }
            if(content != null){
                for(UnitType u : content.units()) classify(u);
                for(Block b : content.blocks()) classify(b);
                for(Item i : content.items()) classify(i);
            }
            for(String alias : aliases){
                int glyph = Fonts.getUnicode(alias);
                if(glyph > 0 && glyph <= 65535 && !Character.isSurrogate((char)glyph)) recGlyphs.add(glyph);
            }
        }catch(Throwable t){
            Log.err("[MKUKIC] coin recognition", t);
        }
    }

    private static void classify(UnlockableContent c){
        if(c == null) return;
        boolean matched = matchesName(c.name) || containsWord(c.localizedName)
            || matchesRegion(c.uiIcon) || matchesRegion(c.fullIcon);
        if(c instanceof UnitType u) matched |= matchesRegion(u.region);
        if(c instanceof Block b) matched |= matchesRegion(b.region);
        if(matched){
            recContent.add(c);
            addAlias(c.name);
            addRegion(c.uiIcon);
            addRegion(c.fullIcon);
            if(c instanceof UnitType u) addRegion(u.region);
            if(c instanceof Block b) addRegion(b.region);
        }
    }

    private static void addAlias(String name){
        if(name == null || name.isEmpty()) return;
        if(aliasSet.add(name)) aliases.add(name);
        String norm = name.replace('\\', '/');
        if(aliasSet.add(norm)) aliases.add(norm);
        int slash = norm.lastIndexOf('/');
        String base = norm.substring(slash + 1);
        if(!base.isEmpty() && aliasSet.add(base)) aliases.add(base);
        int ext = base.lastIndexOf('.');
        if(ext > 0){
            String stem = base.substring(0, ext);
            if(aliasSet.add(stem)) aliases.add(stem);
        }
    }

    private static void addRegion(TextureRegion region){
        if(region == null || (Core.atlas != null && !Core.atlas.isFound(region))) return;
        if(region instanceof AtlasRegion a && ("error".equals(a.name) || "white".equals(a.name) || "clear".equals(a.name))) return;
        if(recRegions.add(region)) regionList.add(region);
    }

    private static boolean matchesRegion(TextureRegion region){
        if(region == null || (Core.atlas != null && !Core.atlas.isFound(region))) return false;
        if(recRegions.contains(region)) return true;
        if(region instanceof AtlasRegion a && matchesName(a.name)) return true;
        if(region.texture == null || region.width <= 0 || region.height <= 0) return false;
        for(TextureRegion known : regionList){
            if(known.texture == region.texture
                && Math.min(known.u, known.u2) == Math.min(region.u, region.u2)
                && Math.max(known.u, known.u2) == Math.max(region.u, region.u2)
                && Math.min(known.v, known.v2) == Math.min(region.v, region.v2)
                && Math.max(known.v, known.v2) == Math.max(region.v, region.v2)) return true;
        }
        return false;
    }

    private static boolean matchesName(String name){
        return name != null && (containsWord(name) || matchesAlias(name));
    }

    private static boolean matchesAlias(String name){
        if(name == null || name.isEmpty()) return false;
        if(aliasSet.contains(name)) return true;
        for(String a : aliases){
            if(a.equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    private static boolean containsWord(String name){
        if(name == null) return false;
        for(String needle : words){
            int offset = 0;
            for(int last = name.length() - needle.length(); offset <= last; offset++){
                if(name.regionMatches(true, offset, needle, 0, needle.length())) return true;
            }
        }
        return false;
    }

    static boolean matchesUnit(Unit u){
        return u != null && u.type != null && recContent.contains(u.type);
    }

    static boolean matchesBuilding(Building b){
        return b != null && b.block != null && recContent.contains(b.block);
    }

    static boolean matchesText(String text){
        if(text == null || text.isEmpty()) return false;
        if(containsWord(text) || matchesAlias(text)) return true;
        for(int i = 0; i < text.length(); i++){
            char g = text.charAt(i);
            if(recGlyphs.contains(g)) return true;
            if(g >= '\ue000' && g <= '\uf8ff' && matchesName(Fonts.unicodeToName(g))) return true;
        }
        return false;
    }

    private static boolean matchesTexture(Object value){
        if(value instanceof UnlockableContent c) return recContent.contains(c);
        if(value instanceof TextureRegion r) return matchesRegion(r);
        return value instanceof String s && matchesName(s);
    }

    // ============================ ЦЕНЫ ============================

    /** Разбор числа в тексте метки: «1 500», «12.5k», «300 монет» и т.п. */
    public static double valueOf(String text){
        if(text == null || text.isEmpty() || text.length() > 2048) return -1;
        int i = 0, len = text.length();
        while(i < len){
            char c = text.charAt(i);
            if(c == '['){ // пропускаем markup [color]
                int end = text.indexOf(']', i);
                if(end < 0) return -1;
                i = end + 1;
                continue;
            }
            if(c >= '0' && c <= '9') break;
            i++;
        }
        if(i >= len) return -1;
        double val = 0;
        int digits = 0;
        while(i < len){
            char c = text.charAt(i);
            if(c >= '0' && c <= '9'){
                if(++digits > 18) return -1;
                val = val * 10 + (c - '0');
                i++;
            }else if((c == '.' || c == ',' || c == ' ') && i + 1 < len && text.charAt(i + 1) >= '0' && text.charAt(i + 1) <= '9'){
                i++;
            }else{
                break;
            }
        }
        if(i < len){
            char s = Character.toLowerCase(text.charAt(i));
            if(s == 'k' || s == 'к') val *= 1e3;
            else if(s == 'm' || s == 'м') val *= 1e6;
            else if(s == 'b' || s == 'б') val *= 1e9;
        }
        return val;
    }

    // ============================ СКАН ЦЕЛЕЙ ============================

    public static int targetCount(){
        return count;
    }

    private static void clearTargets(){
        for(int i = 0; i < count; i++) targets[i] = null;
        count = 0;
    }

    private static boolean live(Object t){
        if(t instanceof Unit u) return u.isAdded() && !u.dead;
        if(t instanceof Building b) return b.isAdded();
        if(t instanceof WorldLabel l) return l.isAdded();
        return true; // маркеры целей статичны
    }

    private static float targetX(Object t){
        if(t instanceof Unit u) return u.x;
        if(t instanceof Building b) return b.x;
        if(t instanceof WorldLabel l) return l.x;
        if(t instanceof PosMarker p) return p.pos.x;
        return Float.NaN;
    }

    private static float targetY(Object t){
        if(t instanceof Unit u) return u.y;
        if(t instanceof Building b) return b.y;
        if(t instanceof WorldLabel l) return l.y;
        if(t instanceof PosMarker p) return p.pos.y;
        return Float.NaN;
    }

    private static boolean visible(float x, float y){
        return Float.isFinite(x) && Float.isFinite(y) && world != null
            && x >= 0 && y >= 0 && x <= world.width() * tilesize && y <= world.height() * tilesize;
    }

    private static void offer(Object t, float x, float y){
        if(!live(t) || !visible(x, y)) return;
        float dist = Mathf.dst2(scanX, scanY, x, y);
        double value = (t instanceof TextMarker tm) ? valueOf(tm.text)
            : (t instanceof WorldLabel wl) ? valueOf(wl.text) : -1;
        boolean priority = preferHighValue && value >= valuableThreshold;
        int farthest = 0;
        for(int i = 0; i < count; i++){
            if(Mathf.dst2(x, y, targetX(targets[i]), targetY(targets[i])) < 4f) return; // дубликат
            boolean candP = preferHighValue && values[i] >= valuableThreshold;
            boolean farP = preferHighValue && values[farthest] >= valuableThreshold;
            if(farP && !candP || (candP == farP && distances[i] > distances[farthest])) farthest = i;
        }
        int index;
        if(count < maxTargets){
            index = count++;
        }else{
            boolean farP = preferHighValue && values[farthest] >= valuableThreshold;
            if((!priority || farP) && (priority != farP || !(dist < distances[farthest]))) return;
            index = farthest;
        }
        targets[index] = t;
        distances[index] = dist;
        values[index] = value;
        directValues[index] = value >= 0;
    }

    /** Привязать ценники (отдельные надписи с числом) к ближайшим монетам. */
    private static void associatePrices(){
        for(int i = 0; i < count; i++){
            Object t = targets[i];
            String text = (t instanceof TextMarker tm) ? tm.text : (t instanceof WorldLabel wl ? wl.text : null);
            if(text == null) continue;
            if(!matchesText(text) && valueOf(text) < 0) continue;
            double value = valueOf(text);
            if(value < 0) continue;
            float x = targetX(t), y = targetY(t);
            int selected = -1;
            float nearest = 256f;
            for(int k = 0; k < count; k++){
                Object o = targets[k];
                if(o instanceof WorldLabel || o instanceof TextMarker) continue;
                float d = Mathf.dst2(x, y, targetX(o), targetY(o));
                if(d <= nearest){
                    selected = k;
                    nearest = d;
                }
            }
            if(selected >= 0 && !directValues[selected]){
                values[selected] = value;
                directValues[selected] = true;
            }
        }
    }

    // ================== ДОСТУП ДЛЯ АВТОПИЛОТА (CoinPilot) ==================

    /** Координаты i-й цели (живая + видимая) в out; false если цель не годится. */
    public static boolean targetPos(int i, Vec2 out){
        if(i < 0 || i >= count) return false;
        Object t = targets[i];
        if(t == null || !live(t)) return false;
        float tx = targetX(t), ty = targetY(t);
        if(!Float.isFinite(tx) || !Float.isFinite(ty) || !visible(tx, ty)) return false;
        out.set(tx, ty);
        return true;
    }

    /** Ценность i-й цели (-1 если неизвестна). */
    public static double targetValue(int i){
        return (i >= 0 && i < count) ? values[i] : -1.0;
    }

    /** i-я цель считается дорогой (порог как у магнита). */
    public static boolean targetValuable(int i){
        return i >= 0 && i < count && values[i] >= valuableThreshold;
    }

    /** Магнит сейчас держит юнита на монете (автопилоту лучше не мешать). */
    public static boolean isHolding(){
        return heldUnit != null;
    }

    /** Точка приземления под юнита (наземным — ближайший проходимый тайл). */
    public static boolean landingFor(Unit u, float x, float y, Vec2 out){
        return findLanding(u, x, y, out);
    }

    /** Пометить монету как недавно посещённую (недостижима/уже подобрана). */
    public static void markVisited(float x, float y){
        remember(x, y, Time.millis());
    }

    /** Монета недавно посещена (защита от круга между недостижимыми целями). */
    public static boolean recentlyVisited(float x, float y){
        return visited(x, y, Time.millis());
    }

    public static void update(){
        MKUKICSettings s = MKUKICSettings.s();
        Unit origin = player == null || player.dead() ? null : player.unit();
        if(!(s.coinLines || s.coinMagnet || s.coinPilot) || origin == null || state == null || state.isMenu()){
            if(count > 0) clearTargets();
            return;
        }
        long now = Time.millis();
        if(now >= nextRecognition){
            nextRecognition = now + 2000;
            rebuildRecognition();
        }
        boolean fast = s.coinMagnet || s.coinPilot;
        if(now < nextScan) return;
        nextScan = now + (fast ? 100 : 250);
        preferHighValue = fast && s.coinPreferHighValue;

        clearTargets();
        scanX = origin.x;
        scanY = origin.y;
        try{
            for(Unit u : Groups.unit){
                if(u != origin && u.isAdded() && !u.dead && !u.inFogTo(player.team()) && matchesUnit(u)) offer(u, u.x, u.y);
            }
            for(Building b : Groups.build){
                if(b.isAdded() && !b.inFogTo(player.team()) && matchesBuilding(b)) offer(b, b.x, b.y);
            }
            for(Drawc d : Groups.draw){
                if(d instanceof WorldLabel l && l.isAdded() && matchesText(l.text)) offer(l, l.x, l.y);
            }
            if(state.markers != null){
                for(ObjectiveMarker m : state.markers.worldMarkers) offerMarker(m);
            }
            if(state.rules.objectives != null){
                for(MapObjective o : state.rules.objectives.all){
                    if(o.qualified() && o.markers != null){
                        for(ObjectiveMarker m : o.markers){
                            if(m != null && m.world != -1) offerMarker(m);
                        }
                    }
                }
            }
            associatePrices();
        }catch(Throwable ignored){
        }
    }

    private static void offerMarker(ObjectiveMarker m){
        if(m instanceof TextureMarker tex && matchesTexture(tex.texture.value)) offer(tex, tex.pos.x, tex.pos.y);
        else if(m instanceof TextMarker tm && matchesText(tm.text)) offer(tm, tm.pos.x, tm.pos.y);
    }

    // ============================ МАГНИТ ============================

    public static float effectiveRadius(){
        MKUKICSettings s = MKUKICSettings.s();
        return (s.coinServerLimit && net != null && net.client()) ? Math.min(s.coinRadius, 10f) : s.coinRadius;
    }

    private static boolean eligible(Unit u){
        return u != null && !(u instanceof BlockUnitc) && !u.dead && u.isAdded() && u.isLocal()
            && u.controller() == player && u.type != null && u.type.playerControllable
            && Float.isFinite(u.x) && Float.isFinite(u.y);
    }

    private static boolean inputBlocked(){
        return (Core.scene != null && (Core.scene.hasField() || Core.scene.hasKeyboard() || Core.scene.hasDialog()))
            || (control != null && control.input != null && control.input.locked());
    }

    public static void updateMagnet(){
        MKUKICSettings s = MKUKICSettings.s();
        long now = Time.millis();
        if(!s.coinMagnet){
            resetMagnet();
            return;
        }
        Unit unit = state != null && state.isGame() && player != null && !player.dead()
            && !(net != null && net.client() && player.justSwitchTo != null) ? player.unit() : null;
        if(unit == null || !eligible(unit)){
            resetMagnet();
            return;
        }
        if(activeUnit != null && activeUnit != unit){
            resetMagnet();
            activeUnit = unit;
            return;
        }
        activeUnit = unit;
        if(state.isPaused() || inputBlocked()){
            releaseHold();
            return;
        }

        if(heldUnit != null){
            if(validHold(unit, now)){
                unit.vel.setZero();
                return;
            }
            deferCorrectedTarget(unit, now);
            releaseHold();
            return;
        }
        if(jumped && now >= lastJump && now - lastJump < 100) return;

        float radius = 8f * effectiveRadius();
        float radiusSq = radius * radius;
        float nearest = Float.POSITIVE_INFINITY;
        float keyX = 0, keyY = 0;
        boolean found = false, preferredValue = false;

        for(int i = 0; i < count; i++){
            Object t = targets[i];
            if(t == null || !live(t)) continue;
            float tx = targetX(t), ty = targetY(t);
            if(!Float.isFinite(tx) || !Float.isFinite(ty) || !visible(tx, ty)) continue;
            float d = Mathf.dst2(unit.x, unit.y, tx, ty);
            if(d > radiusSq || visited(tx, ty, now)) continue;
            if(!findLanding(unit, tx, ty, candidate)) continue;
            float ld = Mathf.dst2(unit.x, unit.y, candidate.x, candidate.y);
            if(ld > radiusSq) continue;
            boolean valuable = values[i] >= valuableThreshold;
            if(!found || (valuable && !preferredValue) || (valuable == preferredValue && d < nearest)){
                nearest = d;
                preferredValue = valuable;
                destination.set(candidate);
                keyX = tx;
                keyY = ty;
                found = true;
            }
        }
        if(!found) return;

        boolean moved = Mathf.dst2(unit.x, unit.y, destination.x, destination.y) > 16f;
        if(moved) unit.set(destination.x, destination.y);
        unit.vel.setZero();
        heldUnit = unit;
        heldCoinX = keyX;
        heldCoinY = keyY;
        landingX = unit.x;
        landingY = unit.y;
        targetMissing = false;
        remember(keyX, keyY, now);
        lastJump = now;
        jumped = true;
    }

    /** Вызывается на preDraw: держим юнита на месте, гасим ввод движения. */
    public static void finishInput(){
        long now = Time.millis();
        if(heldUnit == null) return;
        Unit unit = player == null ? null : player.unit();
        if(MKUKICSettings.s().coinMagnet && unit != null && !state.isPaused() && !inputBlocked() && validHold(unit, now)){
            unit.vel.setZero();
            if(control != null){
                if(control.input instanceof DesktopInput d) d.movement.setZero();
                else if(control.input instanceof MobileInput m) m.movement.setZero();
            }
        }else{
            if(unit != null) deferCorrectedTarget(unit, now);
            releaseHold();
        }
    }

    private static boolean validHold(Unit unit, long now){
        if(unit != heldUnit || Mathf.dst2(unit.x, unit.y, landingX, landingY) > 64f) return false;
        if(hasTargetNear(heldCoinX, heldCoinY, 0.05f)){
            targetMissing = false;
            return true;
        }
        if(!targetMissing || now < missingSince){
            targetMissing = true;
            missingSince = now;
        }
        return now - missingSince < 250;
    }

    private static boolean hasTargetNear(float x, float y, float radius){
        float sq = radius * radius;
        for(int i = 0; i < count; i++){
            Object t = targets[i];
            if(t == null || !live(t)) continue;
            float tx = targetX(t), ty = targetY(t);
            if(Mathf.dst2(x, y, tx, ty) <= sq) return true;
        }
        return false;
    }

    private static void deferCorrectedTarget(Unit unit, long now){
        if(unit == heldUnit && Mathf.dst2(unit.x, unit.y, landingX, landingY) > 64f){
            remember(heldCoinX, heldCoinY, now);
            lastJump = now;
            jumped = true;
        }
    }

    private static void releaseHold(){
        heldUnit = null;
        targetMissing = false;
    }

    public static void resetMagnet(){
        activeUnit = null;
        visitedCount = visitedIndex = 0;
        lastJump = 0;
        jumped = false;
        releaseHold();
    }

    /** Точка приземления: если наземный юнит не проходит в тайл монеты — ближайший проходимый рядом. */
    private static boolean findLanding(Unit unit, float x, float y, Vec2 out){
        Tile tile = world.tileWorld(x, y);
        if(tile == null) return false;
        if(!unit.isFlying() && !unit.canPass(tile.x, tile.y)){
            float nearest = Float.POSITIVE_INFINITY;
            boolean found = false;
            for(int dx = -2; dx <= 2; dx++){
                for(int dy = -2; dy <= 2; dy++){
                    if((dx != 0 || dy != 0) && dx * dx + dy * dy <= 4){
                        int tx = tile.x + dx, ty = tile.y + dy;
                        Tile landing = world.tile(tx, ty);
                        if(landing != null && unit.canPass(tx, ty)){
                            float px = tx * tilesize, py = ty * tilesize;
                            float d = Mathf.dst2(x, y, px, py);
                            if(d < nearest){
                                nearest = d;
                                out.set(px, py);
                                found = true;
                            }
                        }
                    }
                }
            }
            return found;
        }
        out.set(x, y);
        return true;
    }

    private static boolean visited(float x, float y, long now){
        for(int i = 0; i < visitedCount; i++){
            long elapsed = now - visitedAt[i];
            if(elapsed >= 0 && elapsed < 1500 && Mathf.dst2(x, y, visitedX[i], visitedY[i]) <= 0.0025f) return true;
        }
        return false;
    }

    private static void remember(float x, float y, long now){
        visitedX[visitedIndex] = x;
        visitedY[visitedIndex] = y;
        visitedAt[visitedIndex] = now;
        visitedIndex = (visitedIndex + 1) % 64;
        visitedCount = Math.min(64, visitedCount + 1);
    }

    // ============================ ОТРИСОВКА ============================

    public static void draw(){
        MKUKICSettings s = MKUKICSettings.s();
        Unit origin = player == null || player.dead() ? null : player.unit();
        if(!s.coinLines || origin == null || ui == null || ui.hudfrag == null || !ui.hudfrag.shown()) return;

        float z = Draw.z();
        float stroke = Lines.getStroke();
        Draw.z(Layer.overlayUI);
        try{
            for(int i = 0; i < count; i++){
                Object t = targets[i];
                if(t == null || !live(t)) continue;
                float tx = targetX(t), ty = targetY(t);
                if(!Float.isFinite(tx) || !Float.isFinite(ty) || !visible(tx, ty)) continue;

                boolean valuable = values[i] >= valuableThreshold;
                Draw.color(valuable ? valuableColor : lineColor, 0.7f);
                Lines.stroke(1.4f);
                Lines.line(origin.x, origin.y, tx, ty);
                Lines.square(tx, ty, 5f, 45f);

                if(values[i] >= 0 && origin.within(tx, ty, 16f * tilesize)){
                    String label = formatValue(values[i]);
                    Draw.color(valuable ? valuableColor : lineColor);
                    Fonts.def.draw(label, tx + 6f, ty + 6f);
                }
            }
            if(s.coinMagnet && origin != null){
                // радиус магнита вокруг игрока
                Draw.color(lineColor, 0.18f);
                Lines.stroke(1f);
                Lines.circle(origin.x, origin.y, 8f * effectiveRadius());
            }
        }finally{
            Draw.color();
            Lines.stroke(stroke);
            Draw.z(z);
        }
    }

    private static String formatValue(double v){
        if(v >= 1e9) return Strings.autoFixed((float)(v / 1e9), 1) + "B";
        if(v >= 1e6) return Strings.autoFixed((float)(v / 1e6), 1) + "M";
        if(v >= 1e3) return Strings.autoFixed((float)(v / 1e3), 1) + "K";
        return "" + (long)v;
    }
}
