package mkukic.client;

import arc.math.*;
import arc.math.geom.*;
import arc.struct.*;
import arc.util.*;
import mindustry.ai.*;
import mindustry.ai.types.*;
import mindustry.core.*;
import mindustry.entities.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.input.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.defense.turrets.*;
import mindustry.world.blocks.environment.Floor;

import mindustry.world.blocks.storage.CoreBlock.*;
import mkukic.*;

import static mindustry.Vars.*;

/**
 * ИИ для юнитов команды игрока.
 *
 * ВАЖНО (мультиплеер): логика контроллеров юнитов (CommandAI) выполняется ТОЛЬКО на сервере
 * (UnitComp.update: «AI only updates on the server»), а позиции юнитов приходят на клиент
 * снапшотами. Поэтому двигать/копать/строить «напрямую» на клиенте бессмысленно — сервер
 * всё откатит. Вместо этого мод управляет юнитами ЧЕРЕЗ СТАНДАРТНЫЕ КОМАНДЫ ИГРЫ
 * (те же пакеты, что шлёт ванильное выделение рамкой):
 *   Call.setUnitCommand  — режим: копка / стройка / ремонт;
 *   Call.setUnitStance   — стойки: mineAuto (авто-выбор руды), pursueTarget (авто-бой);
 *   Call.commandUnits    — приказы «идти сюда» (follow / escort / отступление).
 * Дальше всю работу честно делает серверный ИИ игры — это работает и онлайн, и оффлайн,
 * и выглядит для сервера как обычные команды игрока.
 *
 * Юниты, которыми управляет мод = юниты, ВЫДЕЛЕННЫЕ игроком рамкой (ванильное выделение)
 * + юнит, в котором сидит игрок. Кнопка/хоткей «выбрать всех» заполняет ванильное выделение.
 */
public class UnitAI{
    public static int managedCount = 0;
    public static int lastSelected = 0;

    /** Желаемый набор команд/стоек на юнит (биты), чтобы не спамить пакетами. */
    private static final IntIntMap sentState = new IntIntMap();
    /** id юнита -> Time.time последней команды движения. */
    private static final IntFloatMap lastPos = new IntFloatMap();
    /** id юнита -> Time.time последней отправки режима/стойки. */
    private static final IntFloatMap lastSync = new IntFloatMap();
    /** id юнита -> Time.time последнего приказа движения на ремонт транспорта. */
    private static final IntFloatMap lastRepairMove = new IntFloatMap();

    /** Выделение MKUKIC: id юнитов, набранных кнопкой/хоткеем (ванильное выделение тоже сюда вливается). */
    public static final IntSet selIds = new IntSet();
    private static final IntSeq rmIds = new IntSeq();
    private static int lastVanillaSel = 0;
    private static final Seq<Unit> tmpUnits = new Seq<>();
    private static final Vec2 vec = new Vec2();
    private static final IntSeq ids = new IntSeq();

    public static void reset(){
        managedCount = 0;
        tmpUnits.clear();
        selIds.clear();
        rmIds.clear();
        sentState.clear();
        lastPos.clear();
        lastSync.clear();
        lastRepairMove.clear();
        lastVanillaSel = 0;
        squads.clear();
        damagedTransports.clear();
        lastScan = -9999f;
        lastSquadCmd = -9999f;
    }

    /** Юниты под управлением: выделение MKUKIC (кнопки/хоткей) + ванильное выделение + свой юнит. */
    public static Seq<Unit> managed(){
        tmpUnits.clear();
        if(player == null || player.unit() == null || state == null || state.isMenu()) return tmpUnits;
        Unit self = player.unit();

        // ванильное выделение рамкой тоже подхватывается модом;
        // если игрок снял выделение в игре (рамка пустая) — чистим и выделение MKUKIC
        try{
            if(control != null && control.input != null){
                Seq<Unit> van = control.input.selectedUnits;
                if(van.isEmpty() && lastVanillaSel > 0){
                    selIds.clear();
                }
                lastVanillaSel = van.size;
                for(Unit u : van){
                    if(u != null && !u.dead() && u != self && u.team() == player.team() && u.allowCommand()) selIds.add(u.id);
                }
            }
        }catch(Throwable ignored){
        }

        // чистим мёртвых/чужих, собираем живых
        rmIds.clear();
        for(IntSet.IntSetIterator it = selIds.iterator(); it.hasNext;){
            int id = it.next();
            Unit u = Groups.unit.getByID(id);
            if(u == null || u.dead() || u.team() != player.team() || !u.allowCommand()){
                rmIds.add(id);
                continue;
            }
            if(u != self) tmpUnits.add(u);
        }
        for(int i = 0; i < rmIds.size; i++){
            selIds.remove(rmIds.get(i));
            sentState.remove(rmIds.get(i));
            lastPos.remove(rmIds.get(i), 0f);
            lastSync.remove(rmIds.get(i), 0f);
            lastRepairMove.remove(rmIds.get(i), 0f);
        }

        tmpUnits.add(self);
        managedCount = tmpUnits.size;
        return tmpUnits;
    }

    /** Каждый кадр, ДО обновления сущностей (Trigger.update): только локальная логика своего юнита. */
    public static void update(){
        if(player == null || player.unit() == null) return;
        Unit self = player.unit();
        MKUKICSettings s = MKUKICSettings.s();

        // достраивать/чинить собственные планы игрока можно локально:
        // планы своего юнита и так синхронизируются на сервер клиентом.
        if((s.aiAutoBuild || s.aiAutoRepair) && self.canBuild() && self.plans().size > 0){
            self.updateBuilding(true);
        }
    }

    /** Каждый кадр, ПОСЛЕ обновления сущностей (Trigger.afterGameUpdate): серверные команды. */
    public static void lateUpdate(){
        Seq<Unit> units = managed();
        if(units.isEmpty()) return;
        MKUKICSettings s = MKUKICSettings.s();

        for(int i = 0; i < units.size; i++){
            Unit u = units.get(i);
            if(u == null || u.dead() || u == player.unit()) continue;
            try{
                syncUnit(u, s);
            }catch(Throwable ignored){
            }
        }

        // отряды: возвращать отбившихся на точку
        try{
            if(s.aiSquads && !squads.isEmpty() && Time.time - lastSquadCmd > 45f){
                lastSquadCmd = Time.time;
                for(Squad sq : squads){
                    for(int i = 0; i < sq.ids.size; i++){
                        int id = sq.ids.get(i);
                        Unit u = Groups.unit.getByID(id);
                        if(u == null || u.dead() || u.team() != player.team() || !(u.controller() instanceof CommandAI)){
                            sq.ids.removeIndex(i);
                            i--;
                            continue;
                        }
                        float d = Mathf.dst(u.x, u.y, sq.point.x, sq.point.y);
                        if(d > 260f && tryMove(u, 60f)){
                            commandTo(u, sq.point.x + Mathf.range(90f), sq.point.y + Mathf.range(90f));
                        }
                    }
                }
            }
        }catch(Throwable ignored){
        }
    }

    // ==================== СИНХРОНИЗАЦИЯ КОМАНД/СТОЕК ====================

    private static void syncUnit(Unit u, MKUKICSettings s){
        if(!(u.controller() instanceof CommandAI)) return;

        // настройки группы юнита (если есть) перекрывают общие
        UnitGroups.Group g = UnitGroups.forType(u.type);
        boolean o = g != null && g.override;
        boolean mine   = o ? g.mine    : s.aiAutoMine;
        boolean build  = o ? g.build   : (s.aiAutoBuild || s.aiAutoRebuild);
        boolean repair = o ? g.repair  : s.aiAutoRepair;
        boolean follow = o ? g.follow  : s.aiFollow;
        boolean escort = o ? g.escort  : s.aiEscort;
        boolean assist = o ? g.assist  : false;
        boolean target = o ? g.target  : s.aiAutoTarget;
        boolean retreat= o ? g.retreat : s.aiRetreat;
        boolean flee   = o ? g.safe    : s.aiSafeZone;

        // --- желаемый режим (команда) ---
        UnitCommand want = null;
        boolean inSquad = s.aiSquads && inAnySquad(u.id);
        boolean repairOn = s.aiRepairTransports && (!o || g.build);
        if(repairOn && u.canBuild()) want = UnitCommand.rebuildCommand; // ремонт транспорта приоритетнее
        else if(mine && u.canMine()) want = UnitCommand.mineCommand;
        else if(assist) want = UnitCommand.assistCommand;
        else if(build) want = UnitCommand.rebuildCommand;
        else if(repair) want = UnitCommand.repairCommand;

        // --- желаемые стойки ---
        boolean stMine = mine && u.canMine();
        boolean stPursue = target || escort || assist || inSquad;

        int wantBits =
            (want == UnitCommand.mineCommand ? 1 : 0) |
            (want == UnitCommand.rebuildCommand ? 2 : 0) |
            (want == UnitCommand.repairCommand ? 4 : 0) |
            (stMine ? 8 : 0) |
            (stPursue ? 16 : 0) |
            (want == UnitCommand.assistCommand ? 32 : 0);

        int haveBits = sentState.get(u.id, -1);
        // раз в ~7 секунд повторяем пакет на случай, если игрок сменил режим руками
        if(haveBits != wantBits || (haveBits >= 0 && Time.time - lastSync.get(u.id, -1000f) > 60f * 7f)){
            ids.clear();
            ids.add(u.id);
            int[] arr = ids.toArray();

            if(want != null && u.type.allowCommand(u, want)) Call.setUnitCommand(player, arr, want);
            if(stMine && u.type.allowStance(u, UnitStance.mineAuto)) Call.setUnitStance(player, arr, UnitStance.mineAuto, true);
            if(stPursue && u.type.allowStance(u, UnitStance.pursueTarget)) Call.setUnitStance(player, arr, UnitStance.pursueTarget, true);
            if(!stPursue && haveBits >= 0 && (haveBits & 16) != 0 && u.type.allowStance(u, UnitStance.pursueTarget)){
                Call.setUnitStance(player, arr, UnitStance.pursueTarget, false);
            }
            sentState.put(u.id, wantBits);
            lastSync.put(u.id, Time.time);
        }

        // --- приказы движения: отступление / follow / escort / безопасная зона ---
        if(u.isPlayer()) return;

        if(retreat && u.health() < u.maxHealth() * s.retreatHealth){
            CoreBuild core = u.closestCore();
            if(core != null && !u.within(core, 140f) && tryMove(u, 75f)){
                Vec2 p = coreStandPos(u, core);
                if(p != null) commandTo(u, p.x, p.y);
                return;
            }
        }

        // ремонт транспорта: отправить строителей к повреждённым конвейерам/мостам/турелям/заводам
        if(repairOn && u.canBuild()){
            if(Time.time - lastScan > 150f){
                lastScan = Time.time;
                scanTransports();
            }
            Building tgt = null;
            float bd2 = Float.MAX_VALUE;
            for(int i = 0; i < damagedTransports.size; i++){
                Building b = damagedTransports.get(i);
                if(b == null || !b.damaged()){
                    damagedTransports.remove(i);
                    i--;
                    continue;
                }
                float d2 = Mathf.dst2(b.x, b.y, u.x, u.y);
                if(d2 < bd2){
                    bd2 = d2;
                    tgt = b;
                }
            }
            float reach = u.type.buildRange + 40f;
            if(tgt != null && bd2 > reach * reach && Time.time - lastRepairMove.get(u.id, -1000f) >= 120f){
                lastRepairMove.put(u.id, Time.time);
                Vec2 p = repairStandPos(u, tgt);
                commandTo(u, p.x, p.y);
                return;
            }
        }

        Unit anchor = nearestAnchor(g, u);
        // помощь игрокам = держаться возле них + помогать со стройкой;
        // если юнит в отряде — точку держит отряд, follow/escort не мешают
        boolean followEff = !inSquad && (follow || (assist && anchor != player.unit()));
        if(followEff && anchor != null){
            float d = u.dst(anchor);
            if(d > s.followDistance * 1.7f && tryMove(u, 35f)){
                Vec2 p = new Vec2(anchor.x + Mathf.range(s.followDistance * 0.5f),
                    anchor.y + Mathf.range(s.followDistance * 0.5f));
                commandTo(u, p.x, p.y);
            }
        }else if(!inSquad && escort && anchor != null){
            float d = u.dst(anchor);
            if(d > s.escortRange && tryMove(u, 75f)){
                commandTo(u, anchor.x, anchor.y);
            }
        }

        // уход от врагов: враг в радиусе -> приказ к ядру
        if(flee && tryMove(u, 60f)){
            Unit enemy = Units.closestEnemy(u.team(), u.x, u.y, s.fleeRadius, e -> e != null && !e.dead() && e.hittable());
            if(enemy != null){
                CoreBuild core = u.closestCore();
                if(core != null && !u.within(core, 150f)){
                    Vec2 p = coreStandPos(u, core);
                    if(p != null) commandTo(u, p.x, p.y);
                }
            }
        }
    }

    /** Ближайший игрок из привязки группы; если список пуст — сам игрок. */
    private static @Nullable Unit nearestAnchor(@Nullable UnitGroups.Group g, Unit u){
        Unit self = player.unit();
        if(g == null || g.helpPlayers.isEmpty()) return self;
        Unit best = null;
        float bd = Float.MAX_VALUE;
        for(Player p : Groups.player){
            if(p == null || p == player || p.unit() == null || p.unit().dead()) continue;
            if(!g.helpPlayers.contains(p.name(), false)) continue;
            float d = p.unit().dst2(u);
            if(d < bd){
                bd = d;
                best = p.unit();
            }
        }
        return best != null ? best : self;
    }

    private static void commandTo(Unit u, float x, float y){
        ids.clear();
        ids.add(u.id);
        Call.commandUnits(player, ids.toArray(), null, null, new Vec2(x, y), false, true);
        lastPos.put(u.id, Time.time);
    }

    /** Не чаще, чем раз в `every` тиков отдавать приказ движения этому юниту. */
    private static boolean tryMove(Unit u, float every){
        return Time.time - lastPos.get(u.id, -1000f) >= every;
    }

    // ====================== РЕМОНТ ТРАНСПОРТА ======================

    /** Повреждённые «транспортные» блоки команды: конвейеры, мосты, роутеры, а также турели/заводы. */
    public static final Seq<Building> damagedTransports = new Seq<>();
    private static float lastScan = -9999f;

    /** Просканировать все постройки команды и собрать повреждённые, проводящие предметы. */
    public static void scanTransports(){
        damagedTransports.clear();
        if(state == null || state.isMenu() || player == null) return;
        try{
            Seq<Building> all = player.team().data().buildings;
            for(int i = 0; i < all.size; i++){
                Building b = all.get(i);
                if(b != null && b.damaged() && isTransport(b.block)) damagedTransports.add(b);
            }
        }catch(Throwable ignored){
        }
    }

    /** Блок проводит предметы (конвейер/мост/роутер/сортировщик) либо потребляет их (турель, завод). */
    public static boolean isTransport(Block block){
        return block != null && block.hasItems && !(block instanceof mindustry.world.blocks.storage.CoreBlock);
    }

    /** Точка рядом со зданием, где юнит может стоять и строить. */
    public static Vec2 repairStandPos(Unit u, Building b){
        if(u.type.flying){
            // летающему достаточно встать в радиусе стройки со своей стороны
            float dx = u.x - b.x, dy = u.y - b.y;
            float len = Mathf.len(dx, dy);
            float r = Math.min(len, u.type.buildRange * 0.6f);
            if(len > 0.001f){
                return new Vec2(b.x + dx / len * r, b.y + dy / len * r);
            }
            return new Vec2(b.x, b.y);
        }
        Tile bt = b.tileOn();
        if(bt == null) return new Vec2(b.x, b.y);
        int rad = b.block.size / 2 + 2;
        Tile best = null;
        float bd = Float.MAX_VALUE;
        for(int dx = -rad; dx <= rad; dx++){
            for(int dy = -rad; dy <= rad; dy++){
                Tile t = world.tile(bt.x + dx, bt.y + dy);
                if(t == null || !canStand(u, t)) continue;
                float d = Mathf.dst2(t.worldx(), t.worldy(), u.x, u.y);
                if(d < bd){
                    bd = d;
                    best = t;
                }
            }
        }
        return best != null ? new Vec2(best.worldx(), best.worldy()) : new Vec2(b.x, b.y);
    }

    /** Может ли юнит стоять на тайле (наземные мехи не ходят по ядру, стенам и глубокой воде). */
    public static boolean canStand(Unit u, Tile t){
        if(t == null) return false;
        if(u.type.flying) return !t.solid();
        if(t.solid() || !t.passable()) return false;
        Floor f = t.floor();
        if(f != null && (f.isDeep() || f.isLiquid) && !u.type.naval) return false;
        return true;
    }

    /**
     * Точка ВОЗЛЕ ядра, на которой юнит реально может стоять.
     * Само ядро — твёрдый блок: наземные юниты (мехи) не могут на него войти,
     * поэтому приказ «идти на ядро» для них бессмысленен. Ищем проходимый тайл рядом.
     */
    public static @Nullable Vec2 coreStandPos(Unit u, @Nullable Building core){
        if(core == null) return null;
        Tile ct = core.tileOn();
        if(ct == null) return new Vec2(core.x, core.y);
        if(u.type.flying) return new Vec2(core.x, core.y);
        int r = core.block.size / 2 + 3;
        Tile best = null;
        float bd = Float.MAX_VALUE;
        for(int dx = -r; dx <= r; dx++){
            for(int dy = -r; dy <= r; dy++){
                Tile t = world.tile(ct.x + dx, ct.y + dy);
                if(t == null || !canStand(u, t)) continue;
                float d = Mathf.dst2(t.worldx(), t.worldy(), u.x, u.y);
                if(d < bd){
                    bd = d;
                    best = t;
                }
            }
        }
        if(best == null){
            // совсем рядом ничего проходимого: хоть чуть в сторону от ядра
            return new Vec2(core.x + Mathf.range(tilesize * (r + 1)), core.y + Mathf.range(tilesize * (r + 1)));
        }
        return new Vec2(best.worldx(), best.worldy());
    }

    // ====================== ОТРЯДЫ ======================

    /** Отряд: точка на пути от места высадки к ядру + состав (id юнитов). */
    public static class Squad{
        public final Vec2 point = new Vec2();
        public final IntSeq ids = new IntSeq();
        public @Nullable Tile spawn;
    }

    public static final Seq<Squad> squads = new Seq<>();
    private static float lastSquadCmd = -9999f;

    private static boolean inAnySquad(int id){
        for(Squad sq : squads){
            if(sq.ids.contains(id)) return true;
        }
        return false;
    }

    /**
     * Разделить выделенных юнитов на равные отряды и расставить их сторожить пути
     * от точек высадки врага к ядру. Нет точек высадки — круговая оборона вокруг ядра.
     */
    public static void makeSquads(){
        squads.clear();
        if(state == null || state.isMenu() || player == null || player.unit() == null) return;
        MKUKICSettings s = MKUKICSettings.s();

        Seq<Unit> units = new Seq<>();
        for(Unit u : managed()){
            if(u != player.unit() && !u.dead() && u.controller() instanceof CommandAI) units.add(u);
        }
        if(units.isEmpty()){
            MKUKICMod.toast("Нет выделенных юнитов — сначала выделите их");
            return;
        }

        Seq<Tile> spawns = new Seq<>();
        try{
            if(spawner != null){
                for(Tile t : spawner.getSpawns()){
                    if(t != null) spawns.add(t);
                }
            }
        }catch(Throwable ignored){
        }

        CoreBuild core = player.team().core();
        int K = s.squadCount > 0 ? s.squadCount : Math.max(spawns.size, 1);
        K = Mathf.clamp(K, 1, units.size);

        for(int i = 0; i < K; i++){
            Squad sq = new Squad();
            squads.add(sq);
            if(!spawns.isEmpty()){
                Tile sp = spawns.get(i % spawns.size);
                sq.spawn = sp;
                float sx = sp.worldx(), sy = sp.worldy();
                if(core != null){
                    // несколько отрядов на одну точку высадки — расставить вдоль пути
                    float f = Mathf.clamp(0.35f + 0.15f * (i / (float)spawns.size), 0.15f, 0.8f);
                    sq.point.set(sx + (core.x - sx) * f, sy + (core.y - sy) * f);
                }else{
                    sq.point.set(sx, sy);
                }
            }else if(core != null){
                float ang = 360f * i / K;
                float r = Math.min(s.aiRadius * 0.7f, 800f);
                sq.point.set(core.x + Angles.trnsx(ang, r), core.y + Angles.trnsy(ang, r));
            }
        }

        // равное деление: по кругу
        for(int i = 0; i < units.size; i++){
            squads.get(i % K).ids.add(units.get(i).id);
        }
        int per = units.size / K;
        MKUKICMod.toast("Отрядов: " + K + " × ~" + per + (units.size % K > 0 ? "-" + (per + 1) : "") +
            " юнитов" + (spawns.isEmpty() ? " (точек высадки нет — оборона вокруг ядра)" : ""));
    }

    public static void clearSquads(){
        squads.clear();
        MKUKICMod.toast("Отряды распущены");
    }

    // ====================== ДЕЙСТВИЯ ИЗ МЕНЮ/ХОТКЕЕВ ======================

    /** Выделить всех командных юнитов рядом: в выделение MKUKIC И в ванильное (видны рамки). */
    public static void selectAllNearby(){
        if(control == null || control.input == null || player.unit() == null) return;
        MKUKICSettings s = MKUKICSettings.s();
        Unit self = player.unit();
        selIds.clear();
        Seq<Unit> sel = control.input.selectedUnits;
        sel.clear();
        Groups.unit.each(u -> u != self && u.team() == player.team() && !u.dead() && u.allowCommand() && u.within(self, s.aiRadius),
            u -> {
                selIds.add(u.id);
                sel.add(u);
            });
        lastSelected = selIds.size;
        managedCount = selIds.size + 1;
        MKUKICMod.toast("Выделено юнитов: " + selIds.size);
    }

    /** Отдать всем управляемым юнитам приказ идти к игроку (серверная команда движения). */
    public static void gatherToPlayer(){
        if(player == null || player.unit() == null) return;
        Unit self = player.unit();
        ids.clear();
        for(Unit u : managed()){
            if(u != self && !u.dead() && u.controller() instanceof CommandAI) ids.add(u.id);
        }
        if(ids.size == 0){
            MKUKICMod.toast("Нет выделенных юнитов — сначала выделите их рамкой");
            return;
        }
        // пачками по 40 id, чтобы не превышать лимиты пакета
        int[] all = ids.toArray();
        for(int i = 0; i < all.length; i += 40){
            int[] chunk = new int[Math.min(40, all.length - i)];
            System.arraycopy(all, i, chunk, 0, chunk.length);
            Call.commandUnits(player, chunk, null, null,
                new Vec2(self.x + Mathf.range(60f), self.y + Mathf.range(60f)), false, true);
        }
        float t = Time.time;
        for(int id : all) lastPos.put(id, t);
    }

    /** Отправить всех выделенных к ядру — в проходимую точку рядом с ним (мехи не упрутся в ядро). */
    public static void retreatToCore(){
        if(player == null || player.unit() == null) return;
        int n = 0;
        for(Unit u : managed()){
            if(u == player.unit() || u.dead() || !(u.controller() instanceof CommandAI)) continue;
            Vec2 p = coreStandPos(u, u.closestCore());
            if(p != null){
                commandTo(u, p.x, p.y);
                n++;
            }
        }
        MKUKICMod.toast(n > 0 ? "К ядру отправлено: " + n + " юнитов" : "Некого отправлять");
    }

    /** Снять все приказы: серверная отмена (стойка «stop» очищает очереди) + локальная чистка. */
    public static void clearOrders(){
        Seq<Unit> units = managed();
        ids.clear();
        for(Unit u : units){
            if(u != player.unit() && u.controller() instanceof CommandAI) ids.add(u.id);
        }
        if(ids.size > 0){
            Call.setUnitStance(player, ids.toArray(), UnitStance.stop, false);
        }
        for(Unit u : units){
            if(u.controller() instanceof CommandAI ai) ai.clearCommands();
            u.mineTile(null);
            u.clearBuilding();
        }
        sentState.clear();
        selIds.clear();
        if(control != null && control.input != null) control.input.selectedUnits.clear();
        managedCount = 0;
        MKUKICMod.toast("Приказы сняты");
    }

    // ====================== ОБЩИЕ ХЕЛПЕРЫ ДВИЖЕНИЯ ======================

    public static void moveTo(Unit u, Position target, float circle){
        if(target == null) return;
        moveTo(u, target.getX(), target.getY(), circle);
    }

    public static void moveTo(Unit u, float x, float y, float circle){
        vec.set(x - u.x, y - u.y);
        float len = vec.len();
        if(len < circle){
            u.movePref(vec.setZero());
            return;
        }
        vec.setLength(Math.min(u.speed() * 1.4f, len - circle * 0.5f));
        u.movePref(vec);
    }

    /** Ресурс, которого меньше всего в ядре и который юнит может копать. */
    public static @Nullable Item pickItem(Unit u){
        Building core = u.closestCore();
        if(core == null) return null;
        Item best = null;
        int bestAmount = Integer.MAX_VALUE;
        for(Item item : content.items()){
            if(!u.canMine(item)) continue;
            if(!(u.type.mineFloor && indexer.hasOre(item)) && !(u.type.mineWalls && indexer.hasWallOre(item))) continue;
            int amount = core.items == null ? 0 : core.items.get(item);
            if(amount < bestAmount){
                bestAmount = amount;
                best = item;
            }
        }
        return best;
    }
}
