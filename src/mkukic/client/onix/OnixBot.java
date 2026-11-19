package mkukic.client.onix;

import arc.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.math.*;
import arc.math.geom.*;
import arc.struct.*;
import arc.util.*;
import mindustry.content.*;
import mindustry.entities.units.*;
import mindustry.type.Weapon;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.ConstructBlock.*;
import mindustry.world.blocks.power.*;
import mindustry.world.blocks.storage.CoreBlock.*;
import mindustry.ui.Fonts;
import mkukic.*;
import mkukic.client.*;

import static mindustry.Vars.*;

/**
 * <b>Боты Onix</b> — порт «Ассист бота» и «Антигрифер бота» (SmartBot) из Onix Lite,
 * включая дополнения из Onix Extras:
 *
 * <ul>
 *  <li>мини-табличка ровно над панелью схем: «Цель: Nick», «Блоки: -N (сломано) +M (построено)»;</li>
 *  <li>табличка видна только при активном боте;</li>
 *  <li>визуал пути бота: фиолетовый пунктир, звезда-цель, орбита кружения, красные зоны опасности;</li>
 *  <li>отступление от перегретых реакторов;</li>
 *  <li>слайдеры бота: радиус, скорость кружения, скорость бота.</li>
 * </ul>
 *
 * <p>Ассист: предугадывает действия игрока — кружит вокруг цели, достраивает её планы,
 * чинит повреждённое, прикрывает огнём и отходит при низком HP.
 * Антигрифер: следит за постройками цели и команды; кто ломает — тот и цель — становится целью
 * (список гриферов ведётся по событиям урона/разрушения), блоки чинятся немедленно.
 */
public class OnixBot{
    /** Запись о грифере: кто и сколько сломал/повредил. */
    public static class Griever{
        public String name;
        public int id;
        public int broken;
        public float lastSeen;

        public boolean fresh(){
            return Time.time - lastSeen < 60f * 30f;
        }
    }

    public static final Seq<Griever> griefers = new Seq<>();
    public static int builtByBot, brokenSeen;

    private static final OnixNav nav = new OnixNav();
    private static final Interval timer = new Interval(4);
    private static final Vec2 tmp = new Vec2();

    private static Player target;
    private static Building work;
    private static Tile ore;
    private static Item needItem;
    private static float orbitAngle;
    private static boolean registered;
    private static String status = "выключен";

    // ============================== ЖИЗНЕННЫЙ ЦИКЛ ==============================

    public static void initEvents(){
        if(registered) return;
        registered = true;

        // «Блоки: -N (сломано)» — считаем разрушения построек нашей команды
        Events.on(BlockDestroyEvent.class, e -> {
            if(state == null || state.isMenu() || e.tile == null) return;
            if(player == null || e.tile.team() != player.team()) return;
            brokenSeen++;
        });

        // Антигрифер: кто именно бьёт по нашим блокам
        Events.on(BuildDamageEvent.class, e -> {
            if(state == null || state.isMenu() || e.build == null || e.source == null) return;
            if(player == null || e.build.team == player.team()) return;
            if(e.source.team() == e.build.team) return;
            if(e.source.owner instanceof Unit u && u.isPlayer()){
                Player p = u.getPlayer();
                if(p != null) noteGriever(p);
            }else if(e.source.owner instanceof Player p){
                noteGriever(p);
            }
        });

        Events.on(BlockBuildEndEvent.class, e -> {
            if(state == null || state.isMenu() || e.unit == null || e.breaking) return;
            if(player == null || e.unit == player.unit()) return;
            // наш юнит что-то достроил — счётчик в табличке
            if(e.team == player.team() && MKUKICSettings.s().onixBot) builtByBot++;
        });
    }

    public static void reset(){
        griefers.clear();
        builtByBot = brokenSeen = 0;
        target = null;
        work = null;
        ore = null;
        needItem = null;
        status = "выключен";
        nav.reset();
    }

    public static String status(){ return status; }

    public static @Nullable Player target(){
        return target;
    }

    private static void noteGriever(Player p){
        if(p == null) return;
        Griever g = griefers.find(x -> x.id == p.id());
        if(g == null){
            g = new Griever();
            g.id = p.id();
            g.name = p.name();
            griefers.add(g);
            if(griefers.size > 40) griefers.remove(0);
        }
        g.name = p.name();
        g.broken++;
        g.lastSeen = Time.time;
    }

    // ============================== ЛОГИКА ==============================

    /** Подготовка: выбор цели и работы (до обновления сущностей). */
    public static void update(){
        if(player == null || player.unit() == null || state == null || !state.isGame()) return;
        MKUKICSettings s = MKUKICSettings.s();
        Unit u = player.unit();
        initEvents();

        target = resolveTarget(s);
        if(timer.get(0, 45f) || work == null || work.dead) work = findWork(u, s);
        if(timer.get(1, 90f)) cleanGriefers();

        // отступление от перегретых реакторов
        if(s.onixBotOverheat && timer.get(2, 20f)){
            Building hot = overheatedNear(u, s.onixBotRadius * 1.4f);
            if(hot != null){
                work = null;
                status = "ухожу от перегретого реактора";
            }
        }
    }

    /** Движение, стройка, копка и бой (после обновления сущностей). */
    public static void lateUpdate(){
        try{
            if(player == null || player.unit() == null || state == null || !state.isGame()) return;
            MKUKICSettings s = MKUKICSettings.s();
            Unit u = player.unit();
            if(u.dead()) return;
            initEvents();

            float dodge = s.coinPilotDodge ? s.coinPilotDodgeR : -1f;

            // 1) опасно: перегретый реактор или низкое HP — отступаем
            Building hot = s.onixBotOverheat ? overheatedNear(u, s.onixBotRadius * 1.4f) : null;
            if(hot != null){
                Vec2 away = tmp.set(u.x - hot.x, u.y - hot.y).setLength(s.onixBotRadius * 1.8f).add(hot.x, hot.y);
                nav.moveTo(u, away.x, away.y, 20f, dodge);
                status = "отход от реактора";
                return;
            }
            if(u.healthf() < 0.3f){
                CoreBuild core = u.closestCore();
                if(core != null && !u.within(core, mineTransferRange)){
                    nav.moveTo(u, core.x, core.y, mineTransferRange * 0.6f, dodge);
                    status = "низкое HP — отход к ядру";
                    return;
                }
            }

            // 2) антигрифер: бьём по обидчику
            if(s.onixBotMode == 1){
                Unit foe = nearestGrieverUnit(u, s.onixBotRadius * 2.2f);
                if(foe != null){
                    engage(u, foe, dodge);
                    status = "антигрифер: цель " + (foe.isPlayer() && foe.getPlayer() != null ? foe.getPlayer().name() : foe.type.localizedName);
                    return;
                }
            }

            // 3) работа: стройка/ремонт, при нехватке ресурса — копка
            if(work != null && !work.dead && u.canBuild()){
                float need = s.onixBotRadius * tilesize;
                if(!u.within(work.x, work.y, u.type.buildRange * 0.85f)){
                    nav.moveTo(u, work.x, work.y, Math.min(u.type.buildRange * 0.5f, 24f), dodge);
                    status = "иду к работе";
                    return;
                }
                Item lack = lackingFor(u, work);
                if(lack != null && u.canMine(lack)){
                    needItem = lack;
                    ore = null;
                    mineMissing(u, s, dodge);
                    return;
                }
                needItem = null;
                addWorkPlan(u, work);
                nav.moveTo(u, work.x, work.y, Math.min(u.type.buildRange * 0.4f, 20f), dodge);
                orbitAround(u, work.x, work.y, s, dodge);
                status = (work instanceof ConstructBuild ? "достраиваю " : "чиню ") + work.block.localizedName;
                return;
            }

            // 4) работы нет — кружим вокруг цели (ассист) и прикрываем огнём
            if(target != null && target.unit() != null){
                Unit t = target.unit();
                if(s.onixBotMode == 1){
                    Unit foe = nearestEnemy(u, s.onixBotRadius * 2.2f);
                    if(foe != null){
                        engage(u, foe, dodge);
                        status = "прикрываю " + t.getPlayer() .name();
                        return;
                    }
                }else{
                    Unit foe = nearestEnemy(u, s.onixBotRadius * 1.2f);
                    if(foe != null && u.type.weapons.size > 0){
                        engage(u, foe, dodge);
                        status = "прикрываю " + target.name();
                        return;
                    }
                }
                orbitAround(u, t.x, t.y, s, dodge);
                status = "ассист: цель " + target.name();
                return;
            }
            status = "нет цели";
        }catch(Throwable t){
            Log.err("[MKUKIC Onix] Bot.lateUpdate", t);
        }
    }

    /** Кружение вокруг точки (орбита из «Визуала пути бота»). */
    private static void orbitAround(Unit u, float cx, float cy, MKUKICSettings s, float dodge){
        orbitAngle += 0.012f * s.onixBotOrbitSpeed;
        float r = Math.max(28f, s.onixBotRadius * 0.35f);
        float x = cx + Angles.trnsx(orbitAngle, r), y = cy + Angles.trnsy(orbitAngle, r);
        nav.moveTo(u, x, y, 10f, dodge);
    }

    /** Навестись на врага и стрелять (бой — штатным контроллером юнита). */
    private static void engage(Unit u, Unit foe, float dodge){
        if(foe == null) return;
        float lead = 0f;
        if(foe.vel() != null) lead = 6f;
        float px = foe.x + foe.vel().x * lead, py = foe.y + foe.vel().y * lead;
        try{
            u.aim(px, py);
            u.isShooting(true);
        }catch(Throwable ignored){}
        float weaponRange = 0f;
        for(Weapon w : u.type.weapons) weaponRange = Math.max(weaponRange, w.range());
        float want = Math.max(60f, weaponRange * 0.7f);
        if(u.within(foe.x, foe.y, want)) nav.moveTo(u, u.x, u.y, 4f, dodge);
        else nav.moveTo(u, px, py, want * 0.8f, dodge);
    }

    /** Игрок, за которым бот следует. */
    public static @Nullable Player resolveTarget(MKUKICSettings s){
        if(player == null) return null;
        if(!s.onixBotTarget.isEmpty()){
            Player p = Groups.player.find(x -> x != null && x != player && x.name().equalsIgnoreCase(s.onixBotTarget));
            if(p != null) return p;
        }
        Player best = null;
        float bestD = Float.MAX_VALUE;
        for(Player p : Groups.player){
            if(p == null || p == player || p.unit() == null || p.unit().dead()) continue;
            if(p.team() != player.team()) continue;
            float d = p.unit().dst2(player.unit());
            if(d < bestD){
                bestD = d;
                best = p;
            }
        }
        return best;
    }

    /** Ближайшая работа: недострои и повреждённые блоки рядом с целью/юнитом. */
    private static @Nullable Building findWork(Unit u, MKUKICSettings s){
        float cx = target != null && target.unit() != null ? target.unit().x : u.x;
        float cy = target != null && target.unit() != null ? target.unit().y : u.y;
        float r = s.onixBotRadius * tilesize;
        Building best = null;
        float bestScore = Float.MAX_VALUE;

        // повреждённые
        for(Building b : indexer.getDamaged(u.team())){
            if(b == null || b.dead || !b.within(cx, cy, r)) continue;
            if(b.healthf() >= 0.98f) continue;
            float sc = b.dst2(cx, cy);
            if(s.onixBotMode == 1) sc *= 0.5f; // антигрифер чинит охотнее
            if(sc < bestScore){ bestScore = sc; best = b; }
        }
        // недострои
        final Building[] holder = {best};
        final float[] bs = {bestScore};
        indexer.eachBlock(u.team(), cx, cy, r,
            b -> b instanceof ConstructBuild cb && cb.progress < 0.999f,
            b -> {
                float sc = b.dst2(cx, cy) * 0.9f;
                if(sc < bs[0]){ bs[0] = sc; holder[0] = b; }
            });
        return holder[0];
    }

    /** Чего не хватает, чтобы закончить блок. */
    private static @Nullable Item lackingFor(Unit u, Building b){
        if(b == null) return null;
        ItemStack[] req;
        if(b instanceof ConstructBuild cb && cb.current != null) req = cb.current.requirements;
        else req = b.block.requirements;
        if(req == null) return null;
        CoreBuild core = u.closestCore();
        Item worst = null;
        int worstLack = 0;
        for(ItemStack st : req){
            if(st == null || st.item == null) continue;
            int have = (core != null && core.items != null ? core.items.get(st.item) : 0)
                + (u.stack != null && u.stack.item == st.item ? u.stack.amount : 0);
            int lack = (int)Math.ceil(st.amount * 0.5f) - have;
            if(lack > worstLack){ worstLack = lack; worst = st.item; }
        }
        return worst;
    }

    /** Поставить план ремонта/достройки в очередь юнита. */
    private static void addWorkPlan(Unit u, Building b){
        for(BuildPlan p : u.plans()){
            if(p != null && p.x == b.tile.x && p.y == b.tile.y) return;
        }
        if(b instanceof ConstructBuild cb && cb.current != null){
            u.addBuild(new BuildPlan(b.tile.x, b.tile.y, b.rotation, cb.current, cb.lastConfig), false);
        }else if(b.damaged()){
            u.addBuild(new BuildPlan(b.tile.x, b.tile.y, b.rotation, b.block, b.config()), false);
        }
    }

    /** Добыча недостающего ресурса (общий механизм с Авто-Уни). */
    private static void mineMissing(Unit u, MKUKICSettings s, float dodge){
        if(ore == null || !u.validMine(ore)){
            ore = OnixAutoUnit.findOre(u, needItem, s);
        }
        if(ore == null){
            status = "нет жилы: " + (needItem == null ? "?" : needItem.localizedName);
            return;
        }
        if(u.stack != null && u.stack.amount >= u.itemCapacity() * 0.9f){
            CoreBuild core = u.closestCore();
            if(core != null && !u.within(core, mineTransferRange)){
                u.mineTile(null);
                nav.moveTo(u, core.x, core.y, mineTransferRange * 0.6f, dodge);
                status = "сдаю ресурс в ядро";
                return;
            }
        }
        if(u.within(ore.worldx(), ore.worldy(), u.type.mineRange * 0.9f)){
            if(u.mineTile() != ore) u.mineTile(ore);
            nav.moveTo(u, ore.worldx(), ore.worldy(), 4f, dodge);
        }else{
            u.mineTile(ore);
            nav.moveTo(u, ore.worldx(), ore.worldy(), u.type.mineRange * 0.4f, dodge);
        }
        status = "добываю " + (needItem == null ? "" : needItem.localizedName);
    }

    /** Перегретый реактор рядом (порт «Отступление от перегретых реакторов»). */
    public static @Nullable Building overheatedNear(Unit u, float radius){
        Building worst = null;
        float worstHeat = 0f;
        for(Building b : Groups.build){
            if(b == null || b.dead || b.team != u.team()) continue;
            if(!(b instanceof NuclearReactor.NuclearReactorBuild) && !(b instanceof ImpactReactor.ImpactReactorBuild)) continue;
            if(!b.within(u, radius)) continue;
            float heat = 0f;
            if(b instanceof NuclearReactor.NuclearReactorBuild nr){
                heat = nr.heat;
                if(heat < 0.55f) continue;
            }else if(b instanceof ImpactReactor.ImpactReactorBuild ir){
                heat = 1f - ir.warmup;
                continue; // impact не «греется» в том же смысле
            }
            if(heat > worstHeat){
                worstHeat = heat;
                worst = b;
            }
        }
        return worst;
    }

    private static @Nullable Unit nearestEnemy(Unit u, float radius){
        Unit best = null;
        float bestD = radius * radius;
        for(Unit e : Groups.unit){
            if(e == null || e.dead || e.team == u.team() || !e.targetable(u.team())) continue;
            float d = e.dst2(u);
            if(d < bestD){ bestD = d; best = e; }
        }
        return best;
    }

    /** Юнит игрока-грифера рядом (антигрифер бьёт по обидчику). */
    private static @Nullable Unit nearestGrieverUnit(Unit u, float radius){
        Unit best = null;
        float bestD = radius * radius;
        for(Griever g : griefers){
            if(!g.fresh()) continue;
            Player p = Groups.player.find(x -> x != null && x.id() == g.id);
            if(p == null || p.unit() == null || p.unit().dead()) continue;
            if(p.unit().team == u.team()) continue;
            float d = p.unit().dst2(u);
            if(d < bestD){ bestD = d; best = p.unit(); }
        }
        if(best != null) return best;
        return nearestEnemy(u, radius * 0.6f);
    }

    private static void cleanGriefers(){
        for(int i = griefers.size - 1; i >= 0; i--){
            if(!griefers.get(i).fresh()) griefers.remove(i);
        }
    }

    // ============================== ОТРИСОВКА ==============================

    /** Визуал пути бота + табличка «Цель / Блоки». */
    public static void draw(){
        if(player == null || player.unit() == null) return;
        Unit u = player.unit();
        MKUKICSettings s = MKUKICSettings.s();
        float tx = target != null && target.unit() != null ? target.unit().x : (work != null ? work.x : u.x);
        float ty = target != null && target.unit() != null ? target.unit().y : (work != null ? work.y : u.y);
        OnixNav.drawPath(u, tx, ty, s.onixBotRadius * 0.35f, s.onixBotDanger);

        if(work != null && !work.dead){
            Draw.color(Color.valueOf("7dff8a"), 0.8f);
            Lines.stroke(1.4f);
            Lines.square(work.x, work.y, work.block.size * tilesize * 0.5f + 3f);
        }
        for(Griever g : griefers){
            if(!g.fresh()) continue;
            Player p = Groups.player.find(x -> x != null && x.id() == g.id);
            if(p == null || p.unit() == null || p.unit().dead()) continue;
            Draw.color(Color.scarlet, 0.75f);
            Lines.stroke(1.6f);
            Lines.circle(p.unit().x, p.unit().y, 22f + Mathf.absin(Time.time, 5f, 3f));
        }
        Draw.reset();
    }

    /**
     * Мини-табличка бота «ровно над панелью схем» — рисуется в экранном слое.
     * Видна только при активном боте.
     */
    public static void drawScreen(){
        if(!MKUKICSettings.s().onixBot) return;
        if(state == null || state.isMenu()) return;
        float w = 300f, h = 46f;
        float x = Core.graphics.getWidth() / 2f - w / 2f;
        float y = 128f; // над панелью схем/блоков
        Draw.color(Color.valueOf("0f131bdd"));
        Fill.rect(x + w / 2f, y + h / 2f, w, h);
        Draw.color(Onix.theme);
        Fill.rect(x + w / 2f, y + h - 1f, w, 2f);
        Draw.reset();

        String tName = target != null ? target.name() : "—";
        String mode = MKUKICSettings.s().onixBotMode == 1 ? "Антигрифер" : "Ассист";
        Font font = Fonts.def;
        font.setColor(Onix.theme2);
        font.getData().setScale(1f);
        GlyphLayout gl = new GlyphLayout(font, "[#" + Onix.theme.toString() + "]" + mode + "[]  Цель: [accent]" + tName);
        font.draw(gl, x + 10f, y + h - 8f);
        GlyphLayout gl2 = new GlyphLayout(font, "[#8b94a5]Блоки: [scarlet]-" + brokenSeen + "[] (сломано) [#7dff8a]+" + builtByBot + "[] (построено)");
        font.draw(gl2, x + 10f, y + 14f);
        if(status != null && !status.isEmpty()){
            font.setColor(Color.valueOf("c2cad8"));
            GlyphLayout gl3 = new GlyphLayout(font, "[#c2cad8]" + status);
            font.draw(gl3, x + w - gl3.width - 10f, y + 14f);
        }
        Draw.reset();
    }
}
