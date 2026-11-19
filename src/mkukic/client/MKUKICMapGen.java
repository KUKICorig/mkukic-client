package mkukic.client;

import arc.*;
import arc.func.*;
import arc.graphics.*;
import arc.graphics.g2d.TextureRegion;
import arc.math.*;
import arc.scene.style.TextureRegionDrawable;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;

import arc.struct.*;
import arc.util.*;
import mindustry.content.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.io.JsonIO;
import mindustry.maps.filters.*;
import mindustry.ui.*;
import mindustry.ui.dialogs.*;
import mindustry.world.*;
import mindustry.world.blocks.environment.Floor;
import mkukic.*;

import static mindustry.Vars.*;

/**
 * ◆ MKUKIC Генератор карт — настраиваемый процедурный генератор для редактора карт.
 *
 * Работает поверх ванильного конвейера GenerateFilter (та же математика, что и у
 * мирового генератора Mindustry), но управляется высокоуровневыми параметрами:
 * стиль рельефа, биом, уровень воды, горы, реки, руды, декор, искажения,
 * симметрия (для честных PvP-карт), автоматические спавны волн и ядра.
 *
 * Открытие: кнопка «Генератор MKUKIC» в меню редактора (☰) или клавиша F6.
 * Предпросмотр пересчитывается в фоне при любом изменении параметров.
 */
public class MKUKICMapGen{

    // ========================= БИОМЫ =========================

    public static class Biome{
        public final String name;
        public final Block deep, shallow, mid, high, wall, rock, tree, core;
        public final Block[] ores;

        Biome(String name, Block deep, Block shallow, Block mid, Block high, Block wall,
              Block rock, Block tree, Block core, Block... ores){
            this.name = name;
            this.deep = deep; this.shallow = shallow; this.mid = mid; this.high = high;
            this.wall = wall; this.rock = rock; this.tree = tree; this.core = core;
            this.ores = ores;
        }
    }

    private static final Block[] SERPULO_ORES = {
        Blocks.oreCopper, Blocks.oreLead, Blocks.oreCoal, Blocks.oreTitanium, Blocks.oreThorium, Blocks.oreScrap
    };
    private static final Block[] EREKIR_ORES = {
        Blocks.oreBeryllium, Blocks.oreTungsten, Blocks.oreThorium, null, null, null
    };

    public static final Biome[] BIOMES = {
        new Biome("Кратеры", Blocks.deepwater, Blocks.water, Blocks.dirt, Blocks.stone,
            Blocks.stoneWall, Blocks.boulder, Blocks.pine, Blocks.coreShard, SERPULO_ORES),
        new Biome("Пески", Blocks.deepwater, Blocks.sandWater, Blocks.sand, Blocks.darksand,
            Blocks.duneWall, Blocks.sandBoulder, Blocks.whiteTreeDead, Blocks.coreShard, SERPULO_ORES),
        new Biome("Тундра", Blocks.deepwater, Blocks.water, Blocks.snow, Blocks.ice,
            Blocks.iceWall, Blocks.snowBoulder, Blocks.snowPine, Blocks.coreShard, SERPULO_ORES),
        new Biome("Споры", Blocks.deepwater, Blocks.taintedWater, Blocks.moss, Blocks.sporeMoss,
            Blocks.sporeWall, Blocks.shaleBoulder, Blocks.sporePine, Blocks.coreShard, SERPULO_ORES),
        new Biome("Магма", Blocks.slag, Blocks.hotrock, Blocks.basalt, Blocks.magmarock,
            Blocks.daciteWall, Blocks.daciteBoulder, Blocks.air, Blocks.coreShard, SERPULO_ORES),
        new Biome("Феррит", Blocks.deepwater, Blocks.water, Blocks.ferricStone, Blocks.ferricCraters,
            Blocks.ferricStoneWall, Blocks.ferricBoulder, Blocks.air, Blocks.coreBastion, EREKIR_ORES),
        new Biome("Кристаллы", Blocks.deepwater, Blocks.water, Blocks.crystallineStone, Blocks.crystalFloor,
            Blocks.crystallineStoneWall, Blocks.crystallineBoulder, Blocks.crystalCluster, Blocks.coreBastion, EREKIR_ORES),
        new Biome("Аркит", Blocks.arkyciteFloor, Blocks.yellowStone, Blocks.redStone, Blocks.denseRedStone,
            Blocks.arkyicWall, Blocks.arkyicBoulder, Blocks.air, Blocks.coreBastion, EREKIR_ORES),
    };

    public static final String[] STYLE_NAMES = {"Континент", "Архипелаг", "Равнины", "Горы", "Каньоны"};
    public static final String[] SYM_NAMES = {"нет", "↔ лево/право", "↕ верх/низ", "✚ 4 стороны", "✕ 4 диагонали"};

    // ========================= ПАРАМЕТРЫ =========================

    public static class Params{
        public int seed = 1337;
        public int style = 0;            // индекс STYLE_NAMES
        public int biome = 0;            // индекс BIOMES
        public float waterLevel = 0.46f; // порог суши
        public float roughness = 0.5f;   // изрезанность (октавы/фоллофф)
        public float mountains = 0.45f;  // плотность гор (стен)
        public float peakBias = 0.5f;    // стягивание гор к центру
        public boolean rivers = true;
        public float riverWidth = 0.4f;
        public boolean distort = false;
        public float distortMag = 0.4f;
        public int smoothing = 0;        // радиус медианного сглаживания (0 = выкл)
        public boolean[] ores = {true, true, true, true, true, false};
        public float oreDensity = 0.55f;
        public float oreClusters = 0.5f; // 0 = широкие жилы, 1 = мелкие плотные
        public boolean scatter = true;
        public float scatterAmount = 0.4f;
        public int symmetry = 0;         // индекс SYM_NAMES
        public int spawns = 0;           // всего точек спавна волн (0..8)
        public boolean cores = false;    // поставить 2 ядра
        public boolean spawnPaths = true;
        public int pathRadius = 3;

        public Biome biome(){
            return BIOMES[Mathf.clamp(biome, 0, BIOMES.length - 1)];
        }
    }

    public static Params p = new Params();

    // ========================= СОСТОЯНИЕ =========================

    private static GenDialog dialog;
    private static Pixmap pixmap;
    private static Texture texture;
    private static long[] buffer1 = {}, buffer2 = {};
    private static int scaling = 2;
    private static boolean generating;
    private static boolean previewDirty;
    private static long lastTouch;
    private static boolean menuHooked;
    private static Seq<GenerateFilter> previewFilters;

    private static final CachedTile ctile = new CachedTile(){
        @Override
        protected void changeBuild(Team team, Prov<Building> entityprov, int rotation){}
        @Override
        public void setBlock(Block type, Team team, int rotation, Prov<Building> entityprov){
            this.block = type;
        }
    };

    // ========================= КОНВЕЙЕР ФИЛЬТРОВ =========================

    public static Seq<GenerateFilter> buildFilters(){
        Biome b = p.biome();
        Seq<GenerateFilter> out = new Seq<>();
        int s = p.seed;

        // --- пресет стиля ---
        float landScl, oct, falloff, terrainScl, wallOff = 0f, riverBoost = 0f;
        switch(p.style){
            case 1: landScl = 24f;  oct = 4f;   falloff = 0.45f; terrainScl = 30f; break;                        // архипелаг
            case 2: landScl = 130f; oct = 2.5f; falloff = 0.4f;  terrainScl = 90f; wallOff = 0.14f; break;       // равнины
            case 3: landScl = 70f;  oct = 5f;   falloff = 0.55f; terrainScl = 30f; wallOff = -0.12f; break;      // горы
            case 4: landScl = 60f;  oct = 3f;   falloff = 0.5f;  terrainScl = 44f; riverBoost = 0.2f; break;     // каньоны
            default: landScl = 95f; oct = 3.5f; falloff = 0.5f;  terrainScl = 55f; break;                        // континент
        }
        oct = Mathf.clamp(oct + (p.roughness - 0.5f) * 3f, 1f, 8f);
        falloff = Mathf.clamp(falloff + (p.roughness - 0.5f) * 0.2f, 0.2f, 0.8f);

        // 0. база: глубокая жидкость + воздух
        GenerateFilter base = new GenerateFilter(){
            @Override
            public FilterOption[] options(){ return new FilterOption[0]; }
            @Override
            public void apply(GenerateFilter.GenerateInput in){
                in.floor = b.deep;
                in.block = Blocks.air;
                in.overlay = Blocks.air;
            }
        };
        base.seed = s;
        out.add(base);

        // одна и та же шумовая форма (seed s+1) даёт согласованные берег/сушу/высокогорье
        // 1. мелководье (пляжное кольцо)
        NoiseFilter beach = new NoiseFilter();
        beach.seed = s + 1; beach.scl = landScl; beach.threshold = p.waterLevel;
        beach.octaves = oct; beach.falloff = falloff;
        beach.floor = b.shallow; beach.block = Blocks.air; beach.target = b.deep;
        out.add(beach);

        // 2. суша
        NoiseFilter land = new NoiseFilter();
        land.seed = s + 1; land.scl = landScl; land.threshold = p.waterLevel + 0.06f;
        land.octaves = oct; land.falloff = falloff;
        land.floor = b.mid; land.block = Blocks.air; land.target = b.shallow;
        out.add(land);

        // 3. высокогорье (плато)
        NoiseFilter high = new NoiseFilter();
        high.seed = s + 1; high.scl = landScl; high.threshold = p.waterLevel + 0.24f;
        high.octaves = oct; high.falloff = falloff;
        high.floor = b.high; high.block = Blocks.air; high.target = b.mid;
        out.add(high);

        // 4. горы-стены
        TerrainFilter tf = new TerrainFilter();
        tf.seed = s + 2; tf.scl = terrainScl;
        tf.threshold = Mathf.clamp(1.04f - p.mountains * 0.6f + wallOff, 0.25f, 1.3f);
        tf.octaves = Mathf.clamp(oct - 1f, 1f, 6f); tf.falloff = falloff;
        tf.magnitude = 1f; tf.circleScl = p.peakBias * 3f; tf.tilt = 0f;
        tf.floor = Blocks.air; tf.block = b.wall;
        out.add(tf);

        // 5. реки (ridged-шум прорезает сушу)
        if(p.rivers || p.style == 4){
            float rw = p.style == 4 ? Math.max(p.riverWidth, 0.5f) : p.riverWidth;
            float th = -0.06f + (1f - rw) * 0.18f - riverBoost;
            out.add(river(s + 3, th, rw, b.mid, b));
            out.add(river(s + 3, th, rw, b.high, b)); // та же форма на плато
        }

        // 6. руды
        float oth = 0.95f - p.oreDensity * 0.35f;
        float oscl = 12f + (1f - p.oreClusters) * 30f;
        for(int i = 0; i < b.ores.length && i < p.ores.length; i++){
            Block ore = b.ores[i];
            if(ore == null || !p.ores[i]) continue;
            OreFilter of = new OreFilter();
            of.seed = s + 40 + i * 7; of.scl = oscl;
            of.threshold = Mathf.clamp(oth + i * 0.008f, 0.4f, 0.99f);
            of.octaves = 2f; of.falloff = 0.3f; of.tilt = 0f;
            of.ore = ore; of.target = Blocks.air;
            out.add(of);
        }

        // 7. декорации: валуны и «деревья»
        if(p.scatter){
            if(b.rock != null && b.rock != Blocks.air){
                ScatterFilter sc = new ScatterFilter();
                sc.seed = s + 7; sc.chance = 0.002f + p.scatterAmount * 0.014f;
                sc.flooronto = b.mid; sc.floor = Blocks.air; sc.block = b.rock;
                out.add(sc);
            }
            if(b.tree != null && b.tree != Blocks.air){
                ScatterFilter tr = new ScatterFilter();
                tr.seed = s + 8; tr.chance = 0.001f + p.scatterAmount * 0.01f;
                tr.flooronto = b.mid; tr.floor = Blocks.air; tr.block = b.tree;
                out.add(tr);
            }
        }

        // 8. искажение рельефа
        if(p.distort){
            DistortFilter d = new DistortFilter();
            d.seed = s + 9; d.scl = 20f; d.mag = 0.5f + p.distortMag * 9f;
            out.add(d);
        }

        // 9. сглаживание
        if(p.smoothing > 0){
            MedianFilter m = new MedianFilter();
            m.seed = s; m.radius = p.smoothing; m.percentile = 0.5f;
            out.add(m);
        }

        // 10. симметрия
        switch(p.symmetry){
            case 1: out.add(mirror(0, s)); break;
            case 2: out.add(mirror(90, s)); break;
            case 3: out.add(mirror(0, s)); out.add(mirror(90, s)); break;
            case 4: out.add(mirror(45, s)); out.add(mirror(135, s)); break;
        }
        return out;
    }

    private static RiverNoiseFilter river(int seed, float th, float rw, Block target, Biome b){
        RiverNoiseFilter r = new RiverNoiseFilter();
        r.seed = seed; r.scl = 36f; r.octaves = 1f; r.falloff = 0.5f;
        r.threshold = th; r.threshold2 = th + 0.04f + rw * 0.07f;
        // реки — всегда жидкость биома (магма/аркит — свои), для «водных» биомов обычная вода
        r.floor = b.deep == Blocks.deepwater ? Blocks.water : b.deep;
        r.floor2 = b.deep;
        r.block = Blocks.air; r.target = target;
        return r;
    }

    private static MirrorFilter mirror(int angle, int s){
        MirrorFilter m = new MirrorFilter();
        m.seed = s + 11; m.angle = angle; m.rotate = false;
        m.axisX = 0.5f; m.axisY = 0.5f;
        return m;
    }

    // ========================= ПРИМЕНЕНИЕ К РЕДАКТОРУ =========================

    public static void applyToEditor(){
        if(editor == null || ui == null || ui.editor == null || !ui.editor.isShown()) return;
        Seq<GenerateFilter> filters = buildFilters();
        int w = editor.width(), h = editor.height();

        ui.loadAnd(() -> {
            long[] writeTiles = new long[w * h], writeData = new long[w * h];
            GenerateFilter.GenerateInput input = new GenerateFilter.GenerateInput();

            for(GenerateFilter f : filters){
                input.begin(w, h, editor::tile);
                for(int x = 0; x < w; x++){
                    for(int y = 0; y < h; y++){
                        input.set(editor.tile(x, y));
                        f.apply(input);
                        writeTiles[x + y * w] = PackTile.get(input.block.id, input.floor.id, input.overlay.id);
                        writeData[x + y * w] = input.packedData;
                    }
                }
                editor.load(() -> {
                    for(int i = 0; i < w * h; i++){
                        Tile tile = world.tiles.geti(i);
                        long wr = writeTiles[i];
                        Block block = content.block(PackTile.block(wr));
                        Block floor = content.block(PackTile.floor(wr));
                        Block overlay = content.block(PackTile.overlay(wr));
                        if(!tile.synthetic() && !block.synthetic()) tile.setBlock(block);
                        tile.setPackedData(writeData[i]);
                        tile.setFloor((Floor)floor);
                        tile.setOverlay(overlay);
                    }
                });
            }

            // пост-обработка: спавны, ядра, проходы
            editor.load(() -> postProcess(input, w, h));

            editor.updateRenderer(); // пересборка EditorTiles + рендера (recache package-private)
            editor.clearOp();
        });
    }

    private static void postProcess(GenerateFilter.GenerateInput input, int w, int h){
        Tiles tiles = world.tiles;
        Biome b = p.biome();

        // --- точки спавна волн (симметрично по краям) ---
        if(p.spawns > 0){
            int perSide = (p.spawns + 1) / 2;
            boolean topBottom = p.symmetry == 2;
            int placed = 0;
            for(int i = 0; i < perSide && placed < p.spawns; i++){
                float frac = perSide == 1 ? 0.5f : 0.18f + 0.64f * i / (perSide - 1);
                if(topBottom){
                    int x = (int)(w * frac), y = 2;
                    placeSpawn(tiles, x, y, w, h); placed++;
                    if(placed < p.spawns){ placeSpawn(tiles, x, h - 1 - y, w, h); placed++; }
                }else{
                    int y = (int)(h * frac), x = 2;
                    placeSpawn(tiles, x, y, w, h); placed++;
                    if(placed < p.spawns){ placeSpawn(tiles, w - 1 - x, y, w, h); placed++; }
                }
            }
        }

        // --- ядра ---
        if(p.cores){
            boolean topBottom = p.symmetry == 2;
            int x1 = topBottom ? w / 2 : Math.max(4, (int)(w * 0.16f));
            int y1 = topBottom ? Math.max(4, (int)(h * 0.16f)) : h / 2;
            int x2 = topBottom ? w / 2 : w - 1 - x1;
            int y2 = topBottom ? h - 1 - y1 : h / 2;
            placeCore(tiles, x1, y1, b);
            placeCore(tiles, x2, y2, b);
        }

        // --- проходы от ядер к спавнам ---
        if(p.spawnPaths && p.cores && p.spawns > 0){
            SpawnPathFilter sp = new SpawnPathFilter();
            sp.seed = p.seed; sp.radius = p.pathRadius; sp.block = Blocks.air;
            input.begin(w, h, editor::tile);
            try{
                sp.apply(tiles, input);
            }catch(Throwable t){
                Log.warn("[MKUKIC] spawnPaths failed: @", t);
            }
        }
    }

    private static void placeSpawn(Tiles tiles, int x, int y, int w, int h){
        for(int r = 0; r <= 16; r++){
            for(int dx = -r; dx <= r; dx++){
                for(int dy = -r; dy <= r; dy++){
                    if(Math.max(Math.abs(dx), Math.abs(dy)) != r) continue;
                    int wx = x + dx, wy = y + dy;
                    if(wx < 1 || wy < 1 || wx >= w - 1 || wy >= h - 1) continue;
                    Tile t = tiles.get(wx, wy);
                    if(t != null && t.floor().hasSurface() && !t.floor().isLiquid && !t.block().solid && t.overlay() != Blocks.spawn){
                        t.setOverlay(Blocks.spawn);
                        return;
                    }
                }
            }
        }
    }

    private static void placeCore(Tiles tiles, int cx, int cy, Biome b){
        int w = tiles.width, h = tiles.height;
        int r = b.core.size / 2 + 1;
        // ищем самую плоскую площадку рядом
        int best = -1, bx = Mathf.clamp(cx, r + 1, w - r - 2), by = Mathf.clamp(cy, r + 1, h - r - 2);
        for(int dx = -10; dx <= 10; dx++){
            for(int dy = -10; dy <= 10; dy++){
                int wx = cx + dx, wy = cy + dy;
                if(wx < r + 1 || wy < r + 1 || wx >= w - r - 1 || wy >= h - r - 1) continue;
                int score = 0;
                for(int ox = -r; ox <= r; ox++){
                    for(int oy = -r; oy <= r; oy++){
                        Tile t = tiles.get(wx + ox, wy + oy);
                        if(t != null && !t.block().solid && t.floor().hasSurface() && !t.floor().isLiquid) score++;
                    }
                }
                if(score > best){ best = score; bx = wx; by = wy; }
            }
        }
        // расчищаем площадку
        for(int ox = -r; ox <= r; ox++){
            for(int oy = -r; oy <= r; oy++){
                int wx = bx + ox, wy = by + oy;
                if(wx < 0 || wy < 0 || wx >= w || wy >= h) continue;
                Tile t = tiles.get(wx, wy);
                if(t == null) continue;
                if(t.block() != Blocks.air) t.setBlock(Blocks.air);
                if(!t.floor().hasSurface() || t.floor().isLiquid) t.setFloor(b.mid instanceof Floor f ? f : (Floor)Blocks.stone);
                if(t.overlay() != Blocks.air) t.setOverlay(Blocks.air);
            }
        }
        Tile center = tiles.get(bx, by);
        if(center != null) center.setBlock(b.core, state.rules.defaultTeam, 0);
    }

    // ========================= ПРЕДПРОСМОТР =========================

    private static long pack(Tile tile){
        return PackTile.get(tile.blockID(), tile.floorID(), tile.overlayID());
    }

    private static Tile unpack(long tile){
        ctile.setFloor((Floor)content.block(PackTile.floor(tile)));
        ctile.setBlock(content.block(PackTile.block(tile)));
        ctile.setOverlay(content.block(PackTile.overlay(tile)));
        return ctile;
    }

    private static void createPixmaps(){
        if(editor == null) return;
        int w = editor.width(), h = editor.height();
        scaling = Math.max(1, Math.max(w, h) / 200);
        int pw = Math.max(1, w / scaling), ph = Math.max(1, h / scaling);
        if(pixmap != null && pixmap.getWidth() == pw && pixmap.getHeight() == ph) return; // тот же размер — переиспользуем
        if(pixmap != null){
            pixmap.dispose();
            texture.dispose();
        }
        pixmap = new Pixmap(pw, ph);
        texture = new Texture(pixmap);
        buffer1 = new long[pw * ph];
        buffer2 = new long[pw * ph];
    }

    public static void updatePreview(){
        if(pixmap == null || editor == null) return;
        if(generating){
            previewDirty = true;
            return;
        }
        generating = true;
        previewDirty = false;
        lastTouch = Time.millis();

        Seq<GenerateFilter> filters = buildFilters();
        previewFilters = filters;
        int w = editor.width(), h = editor.height();
        int sc = scaling;
        final long[] b1 = buffer1, b2 = buffer2;
        final Pixmap pix = pixmap;

        mainExecutor.submit(() -> {
            try{
                world.setGenerating(true);
                GenerateFilter.GenerateInput in = new GenerateFilter.GenerateInput();
                int pw = pix.width, ph = pix.height;

                for(int px = 0; px < pw; px++){
                    for(int py = 0; py < ph; py++){
                        b1[px + py * pw] = pack(editor.tile(Mathf.clamp(px * sc, 0, w - 1), Mathf.clamp(py * sc, 0, h - 1)));
                    }
                }

                for(GenerateFilter f : filters){
                    in.begin(w, h, (x, y) -> unpack(b1[
                        Mathf.clamp(x / sc, 0, pw - 1) + pw * Mathf.clamp(y / sc, 0, ph - 1)]));
                    pix.each((px, py) -> {
                        long t = b1[px + py * pw];
                        in.set(px * sc, py * sc,
                            content.block(PackTile.block(t)),
                            content.block(PackTile.floor(t)),
                            content.block(PackTile.overlay(t)),
                            0L);
                        f.apply(in);
                        b2[px + py * pw] = PackTile.get(in.block.id, in.floor.id, in.overlay.id);
                    });
                    System.arraycopy(b2, 0, b1, 0, b1.length);
                }

                for(int px = 0; px < pw; px++){
                    for(int py = 0; py < ph; py++){
                        long t = b1[px + py * pw];
                        int color = mindustry.io.MapIO.colorFor(
                            content.block(PackTile.block(t)),
                            content.block(PackTile.floor(t)),
                            content.block(PackTile.overlay(t)),
                            Team.derelict);
                        pix.set(px, ph - 1 - py, color);
                    }
                }
                world.setGenerating(false);

                Core.app.post(() -> {
                    if(texture != null && pixmap == pix){
                        texture.draw(pix);
                    }
                    generating = false;
                    if(previewDirty) updatePreview();
                });
            }catch(Throwable e){
                world.setGenerating(false);
                generating = false;
                Log.err("[MKUKIC] mapgen preview failed", e);
            }
        });
    }

    // ========================= ИНТЕГРАЦИЯ С РЕДАКТОРОМ =========================

    /** Тик из ClientHook: хук меню редактора + отложенное обновление превью. */
    public static void tick(){
        if(headless) return;
        hookEditor();
        if(previewDirty && !generating && dialog != null && dialog.isShown()
            && Time.millis() - lastTouch >= 160){
            updatePreview();
        }
    }

    /** Добавляет кнопку «Генератор MKUKIC» в меню (☰) редактора карт. */
    private static void hookEditor(){
        if(menuHooked || ui == null || ui.editor == null) return;
        try{
            java.lang.reflect.Field f = mindustry.editor.MapEditorDialog.class.getDeclaredField("menu");
            f.setAccessible(true);
            BaseDialog menu = (BaseDialog)f.get(ui.editor);
            if(menu != null){
                menu.buttons.button("[accent]◆ [white]Генератор MKUKIC", Icon.terrain, () -> {
                    menu.hide();
                    show();
                });
                menuHooked = true;
            }
        }catch(Throwable t){
            menuHooked = true; // не мучаем рефлексию каждый кадр
            Log.warn("[MKUKIC] editor menu hook failed: @", t);
        }
    }

    public static void show(){
        if(headless || ui == null || ui.editor == null || !ui.editor.isShown() || editor == null){
            if(!headless && ui != null) ui.showInfoToast("[scarlet]Генератор доступен только в редакторе карт", 3f);
            return;
        }
        if(dialog == null) dialog = new GenDialog();
        createPixmaps();
        dialog.rebuild();
        dialog.show();
        previewDirty = false;
        updatePreview();
    }

    static void touch(){
        previewDirty = true;
        lastTouch = Time.millis() - 100; // первое изменение почти без задержки
    }

    private static void rebuildControls(){
        if(dialog != null){
            dialog.rebuild();
        }
    }

    // ========================= ДИАЛОГ =========================

    static class GenDialog extends BaseDialog{
        GenDialog(){
            super("[accent]◆ MKUKIC[] [lightgray]Генератор карт");

            Dialog.DialogStyle ds = new Dialog.DialogStyle();
            Dialog.DialogStyle old = getStyle();
            if(old != null) ds.stageBackground = old.stageBackground;
            ds.background = MKUKICSkin.ready ? MKUKICSkin.panel : (old != null ? old.background : null);
            ds.titleFont = Fonts.def;
            ds.titleFontColor = Pal.accent;
            setStyle(ds);

            addCloseButton();
            buttons.defaults().size(215f, 64f).pad(2f);
            buttons.button("Применить к карте", Icon.ok, () -> {
                hide();
                applyToEditor();
                MKUKICMod.toast("Карта сгенерирована");
            });
            buttons.button("Новый сид", Icon.refresh, () -> {
                p.seed = Mathf.random(999999);
                rebuild();
                touch();
            });
            buttons.button("Копировать настройки", Icon.copy, () -> {
                try{
                    Core.app.setClipboardText(JsonIO.write(p));
                    MKUKICMod.toast("Настройки генератора скопированы");
                }catch(Throwable t){
                    Log.err("[MKUKIC] mapgen export failed", t);
                }
            });
            buttons.button("Вставить настройки", Icon.download, () -> {
                try{
                    String txt = Core.app.getClipboardText();
                    if(txt != null && !txt.isEmpty()){
                        p = JsonIO.read(Params.class, txt);
                        rebuild();
                        touch();
                        MKUKICMod.toast("Настройки генератора загружены");
                    }
                }catch(Throwable t){
                    MKUKICMod.toast("[scarlet]Не удалось прочитать настройки");
                }
            });
        }

        void rebuild(){
            cont.clear();
            if(pixmap == null || texture == null) createPixmaps();

            cont.margin(10f);
            cont.table(main -> {
                main.top();

                // ---- левая колонка: параметры ----
                main.table(left -> {
                    left.top();
                    left.setBackground(MKUKICSkin.ready ? MKUKICSkin.panelSoft : null);
                    left.pane(pane -> {
                        pane.margin(12f).top().left();
                        pane.defaults().left();
                        buildControls(pane);
                    }).grow();
                }).width(660f).growY().padRight(8f);

                // ---- правая колонка: превью ----
                main.table(right -> {
                    right.top();
                    right.add("[orange]◆ [accent]Предпросмотр").left().padBottom(4f).row();
                    right.image(MKUKICSkin.div).height(2f).growX().padBottom(8f).row();

                    BorderImage img = new BorderImage(texture){
                        { setScaling(Scaling.fit); }
                        @Override
                        public void draw(){
                            super.draw();
                            if(previewFilters != null){
                                for(GenerateFilter f : previewFilters) f.draw(this);
                            }
                        }
                    };
                    right.add(img).grow().padBottom(8f).row();

                    right.label(() -> "[lightgray]Карта: [white]" + editor.width() + "×" + editor.height()
                        + "[lightgray] • сид: [white]" + p.seed
                        + "[lightgray] • биом: [white]" + p.biome().name).left().padBottom(6f).row();
                    right.add("[darkgray]Превью обновляется автоматически при изменении параметров.\n"
                        + "[darkgray]«Применить к карте» перестроит ВСЮ карту в редакторе (без отмены).\n"
                        + "[darkgray]Спавны и ядра ставятся симметрично, с учётом выбранной оси.").left().wrap().width(430f).row();
                }).grow();
            }).grow();

            sizeToScreen(this, 0.96f, 0.92f);
        }
    }

    private static void sizeToScreen(BaseDialog d, float wf, float hf){
        float w = Core.scene.getWidth() * wf, h = Core.scene.getHeight() * hf;
        for(Cell<?> c : d.getCells()){
            if(c.get() == d.cont) c.minSize(w, h);
        }
        d.invalidate();
        d.centerWindow();
    }

    // ========================= ВИДЖЕТЫ =========================

    private static void section(Table t, String title){
        t.add("[orange]◆ [accent]" + title).left().padTop(10f).padBottom(2f).row();
        t.image(MKUKICSkin.div).height(2f).growX().padBottom(6f).row();
    }

    private static void sliderRow(Table t, String name, float min, float max, float step, Floatp get, Floatc set){
        t.table(row -> {
            row.left();
            row.add(name).left().minWidth(185f);
            row.label(() -> "[accent]" + Strings.fixed(get.get(), 2)).minWidth(52f).padRight(6f);
            row.slider(min, max, step, get.get(), f -> {
                set.get(f);
                touch();
            }).minWidth(230f);
        }).left().growX().padBottom(2f).row();
    }

    private static void sliderRowInt(Table t, String name, int min, int max, Intp get, Intc set){
        t.table(row -> {
            row.left();
            row.add(name).left().minWidth(185f);
            row.label(() -> "[accent]" + get.get()).minWidth(30f).padRight(6f);
            row.slider(min, max, 1f, get.get(), f -> {
                set.get(Mathf.round(f));
                touch();
            }).minWidth(230f);
        }).left().growX().padBottom(2f).row();
    }

    private static void choiceRow(Table t, String name, String[] opts, Intp get, Intc set, boolean rebuild){
        t.add(name).left().padBottom(2f).row();
        t.table(row -> {
            row.left();
            for(int i = 0; i < opts.length; i++){
                final int idx = i;
                row.button(opts[i], MKUKICSkin.toggleBtn, () -> {
                    set.get(idx);
                    if(rebuild) rebuildControls();
                    touch();
                }).checked(btn -> get.get() == idx).minWidth(60f).height(38f).pad(2f);
            }
        }).left().growX().padBottom(6f).row();
    }

    private static void checkRow(Table t, String name, Boolp get, Boolc set){
        t.check(name, get.get(), v -> {
            set.get(v);
            rebuildControls();
            touch();
        }).left().padBottom(3f).row();
    }

    private static void buildControls(Table t){
        Biome b = p.biome();

        // ---- основа ----
        section(t, "Основа");
        t.table(row -> {
            row.left();
            row.add("Сид").minWidth(60f).left();
            row.field(p.seed + "", MKUKICSkin.field, txt -> {
                p.seed = Strings.parseInt(txt, 0);
                touch();
            }).valid(Strings::canParseInt).width(150f);
            row.button(Icon.refresh, Styles.flati, 40f, () -> {
                p.seed = Mathf.random(999999);
                rebuildControls();
                touch();
            }).padLeft(6f);
        }).left().growX().padBottom(4f).row();
        choiceRow(t, "Стиль рельефа", STYLE_NAMES, () -> p.style, v -> p.style = v, true);

        // ---- биом ----
        section(t, "Биом");
        t.table(g -> {
            for(int i = 0; i < BIOMES.length; i++){
                final int idx = i;
                Biome bio = BIOMES[idx];
                g.table(cell -> {
                    cell.image(new TextureRegionDrawable(bio.mid.uiIcon)).size(30f).padRight(5f);
                    cell.button(bio.name, MKUKICSkin.toggleBtn, () -> {
                        p.biome = idx;
                        rebuildControls();
                        touch();
                    }).checked(btn -> p.biome == idx).minWidth(150f).height(44f);
                }).pad(2f);
                if(i % 2 == 1) g.row();
            }
        }).left().growX().padBottom(4f).row();

        // ---- рельеф ----
        section(t, "Рельеф");
        sliderRow(t, "Уровень воды", 0.30f, 0.64f, 0.01f, () -> p.waterLevel, v -> p.waterLevel = v);
        sliderRow(t, "Изрезанность", 0f, 1f, 0.05f, () -> p.roughness, v -> p.roughness = v);
        sliderRow(t, "Горы", 0f, 1f, 0.05f, () -> p.mountains, v -> p.mountains = v);
        sliderRow(t, "Горы к центру", 0f, 1f, 0.05f, () -> p.peakBias, v -> p.peakBias = v);

        // ---- вода и эффекты ----
        section(t, "Вода и эффекты");
        checkRow(t, "Реки", () -> p.rivers, v -> p.rivers = v);
        if(p.rivers || p.style == 4)
            sliderRow(t, "Ширина рек", 0f, 1f, 0.05f, () -> p.riverWidth, v -> p.riverWidth = v);
        checkRow(t, "Искажение рельефа", () -> p.distort, v -> p.distort = v);
        if(p.distort)
            sliderRow(t, "Сила искажения", 0f, 1f, 0.05f, () -> p.distortMag, v -> p.distortMag = v);
        sliderRowInt(t, "Сглаживание (0 = выкл)", 0, 5, () -> p.smoothing, v -> p.smoothing = v);

        // ---- руды ----
        section(t, "Руды");
        sliderRow(t, "Плотность руд", 0f, 1f, 0.05f, () -> p.oreDensity, v -> p.oreDensity = v);
        sliderRow(t, "Размер жил (0=широкие)", 0f, 1f, 0.05f, () -> p.oreClusters, v -> p.oreClusters = v);
        t.table(g -> {
            int col = 0;
            for(int i = 0; i < b.ores.length && i < p.ores.length; i++){
                Block ore = b.ores[i];
                if(ore == null) continue;
                final int idx = i;
                g.table(cell -> {
                    cell.image(new TextureRegionDrawable(ore.uiIcon)).size(26f).padRight(4f);
                    cell.button(ore.localizedName, MKUKICSkin.toggleBtn, () -> {
                        p.ores[idx] = !p.ores[idx];
                        touch();
                    }).checked(btn -> p.ores[idx]).minWidth(140f).height(40f);
                }).pad(2f);
                if(++col % 2 == 0) g.row();
            }
            if(col % 2 != 0) g.row();
        }).left().growX().padBottom(4f).row();

        // ---- декорации ----
        section(t, "Декорации");
        checkRow(t, "Камни и растения", () -> p.scatter, v -> p.scatter = v);
        if(p.scatter)
            sliderRow(t, "Плотность декора", 0f, 1f, 0.05f, () -> p.scatterAmount, v -> p.scatterAmount = v);

        // ---- симметрия и спавны ----
        section(t, "Симметрия и спавны");
        choiceRow(t, "Симметрия карты", SYM_NAMES, () -> p.symmetry, v -> p.symmetry = v, false);
        sliderRowInt(t, "Точки спавна волн", 0, 8, () -> p.spawns, v -> p.spawns = v);
        checkRow(t, "Поставить ядра (2 шт.)", () -> p.cores, v -> p.cores = v);
        if(p.cores && p.spawns > 0){
            checkRow(t, "Прочистить проходы к ядрам", () -> p.spawnPaths, v -> p.spawnPaths = v);
            if(p.spawnPaths)
                sliderRowInt(t, "Ширина проходов", 1, 8, () -> p.pathRadius, v -> p.pathRadius = v);
        }
        t.add().height(20f).row();
    }
}
