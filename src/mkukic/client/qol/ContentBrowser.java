package mkukic.client.qol;

import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.scene.style.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import mindustry.ctype.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.type.*;
import mindustry.ui.*;
import mindustry.world.*;
import mindustry.world.blocks.production.*;
import mindustry.world.blocks.units.*;
import mindustry.world.consumers.*;
import mindustry.world.meta.*;
import mkukic.*;
import mkukic.client.MKUKICSkin;

import static mindustry.Vars.*;

/**
 * База рецептов (порт TooManyItems + MindustryTool Content Browser):
 * справочник по всему контенту всех установленных модов —
 * как и где производится/добывается/потребляется каждый предмет, жидкость, юнит;
 * что умеет каждая постройка. Поиск по локализованному и внутреннему имени.
 */
public class ContentBrowser{
    private static int category = 0; // 0 блоки | 1 предметы | 2 жидкости | 3 юниты
    private static String query = "";
    private static float queryTime = -9999f;

    // индексы (строятся один раз за сессию)
    private static boolean indexed;
    private static final ObjectMap<Item, Seq<Block>> itemProducers = new ObjectMap<>();
    private static final ObjectMap<Item, Seq<Block>> itemConsumers = new ObjectMap<>();
    private static final ObjectMap<Liquid, Seq<Block>> liqProducers = new ObjectMap<>();
    private static final ObjectMap<Liquid, Seq<Block>> liqConsumers = new ObjectMap<>();
    private static final ObjectMap<UnitType, Seq<Block>> unitFactories = new ObjectMap<>();

    private static @Nullable Table listRef, detailRef;
    private static @Nullable UnlockableContent selected;

    // ============================ ИНДЕКСЫ ============================

    public static void reset(){
        indexed = false;
        itemProducers.clear();
        itemConsumers.clear();
        liqProducers.clear();
        liqConsumers.clear();
        unitFactories.clear();
    }

    private static void buildIndex(){
        if(indexed || content == null) return;
        indexed = true;
        for(Block b : content.blocks()){
            if(b == null) continue;
            // потребление
            if(b.consumers != null){
                for(Consume c : b.consumers){
                    if(c instanceof ConsumeItems){
                        for(ItemStack st : ((ConsumeItems)c).items){
                            if(st != null && st.item != null) cons(itemConsumers, st.item).add(b);
                        }
                    }else if(c instanceof ConsumeLiquid){
                        Liquid l = ((ConsumeLiquid)c).liquid;
                        if(l != null) cons(liqConsumers, l).add(b);
                    }
                }
            }
            // производство
            if(b instanceof GenericCrafter){
                GenericCrafter gc = (GenericCrafter)b;
                if(gc.outputItem != null) cons(itemProducers, gc.outputItem.item).add(b);
                if(gc.outputItems != null) for(ItemStack st : gc.outputItems) if(st != null) cons(itemProducers, st.item).add(b);
                if(gc.outputLiquid != null) cons(liqProducers, gc.outputLiquid.liquid).add(b);
                if(gc.outputLiquids != null) for(LiquidStack ls : gc.outputLiquids) if(ls != null) cons(liqProducers, ls.liquid).add(b);
            }else if(b instanceof Separator){
                for(ItemStack st : ((Separator)b).results){
                    if(st != null) cons(itemProducers, st.item).add(b);
                }
            }else if(b instanceof Drill){
                // бур добывает любую руду своего уровня — отметим все подходящие предметы
                for(Item it : content.items()){
                    if(it.hardness > 0 && it.hardness <= ((Drill)b).tier) cons(itemProducers, it).add(b);
                }
            }else if(b instanceof SolidPump){
                Liquid l = ((SolidPump)b).result;
                if(l != null) cons(liqProducers, l).add(b);
            }else if(b instanceof Pump){
                // насос качает жидкость тайла (вода/нефть) — отметим все жидкости-дропа
                for(Liquid l : content.liquids()){
                    cons(liqProducers, l).add(b);
                }
            }else if(b instanceof UnitFactory){
                for(UnitFactory.UnitPlan p : ((UnitFactory)b).plans){
                    if(p != null && p.unit != null) cons(unitFactories, p.unit).add(b);
                }
            }
        }
    }

    private static <K> Seq<Block> cons(ObjectMap<K, Seq<Block>> map, K key){
        return map.get(key, Seq::new);
    }

    // ============================ ОКНО ============================

    public static void build(Table t){
        buildIndex();

        // категории
        t.table(cats -> {
            cats.left();
            String[] names = {"Постройки", "Предметы", "Жидкости", "Юниты"};
            for(int i = 0; i < names.length; i++){
                final int idx = i;
                cats.button(names[i], MKUKICSkin.toggleBtn, () -> {
                    category = idx;
                    selected = null;
                    rebuildLists();
                }).checked(b -> category == idx).minWidth(88f).height(30f).pad(1f);
            }
        }).left().growX().row();

        // поиск
        t.table(c -> {
            c.left();
            c.add("Поиск:").padRight(4f);
            c.field("", MKUKICSkin.field, txt -> {
                query = txt == null ? "" : txt.trim().toLowerCase();
                queryTime = Time.time;
                rebuildLists();
            }).width(220f);
            c.button("Сброс", MKUKICSkin.actBtn, () -> {
                query = "";
                selected = null;
                rebuildLists();
            }).padLeft(6f).minWidth(80f);
        }).left().growX().pad(3f, 0f, 3f, 0f).row();

        t.image(Tex.whiteui, Pal.accent).height(1f).growX().row();

        // список + детали
        t.table(body -> {
            body.top().left();
            Table list = new Table();
            list.top().left();
            listRef = list;
            body.pane(list).width(210f).maxHeight(300f).left().top();

            Table detail = new Table();
            detail.top().left();
            detailRef = detail;
            body.pane(detail).width(300f).maxHeight(300f).padLeft(6f).left().top();
        }).left().top().growX().padTop(4f).row();

        rebuildLists();
    }

    private static void rebuildLists(){
        if(listRef == null) return;
        Table list = listRef;
        list.clear();
        list.top().left();

        Seq<UnlockableContent> all = switch(category){
            case 1 -> new Seq<>(content.items());
            case 2 -> new Seq<>(content.liquids());
            case 3 -> new Seq<>(content.units());
            default -> new Seq<>(content.blocks());
        };

        int shown = 0;
        for(UnlockableContent c : all){
            if(c == null) continue;
            if(category == 0 && ((Block)c).buildVisibility == BuildVisibility.hidden) continue;
            String ln = c.localizedName == null ? "" : c.localizedName.toLowerCase();
            if(!query.isEmpty() && !ln.contains(query) && !c.name.toLowerCase().contains(query)) continue;
            final UnlockableContent fc = c;
            list.button(b -> {
                b.left();
                if(c.uiIcon != null && c.uiIcon.found()){
                    b.image(new TextureRegionDrawable(c.uiIcon)).size(24f).padRight(4f);
                }
                b.add(c.localizedName == null ? c.name : c.localizedName).left();
            }, MKUKICSkin.actBtn, () -> {
                selected = fc;
                rebuildDetail();
            }).checked(b -> selected == fc).minWidth(196f).height(30f).pad(0.5f).left();
            list.row();
            if(++shown >= 150){
                list.add("[darkgray]... ещё " + (all.size - shown)).left().pad(4f).row();
                break;
            }
        }
        if(shown == 0) list.add("[darkgray]ничего не найдено").left().pad(6f).row();

        // автовыбор первого
        if(selected == null || !all.contains(selected, true)){
            selected = shown > 0 ? findFirst(all) : null;
        }
        rebuildDetail();
    }

    private static @Nullable UnlockableContent findFirst(Seq<UnlockableContent> all){
        for(UnlockableContent c : all){
            if(c == null) continue;
            if(category == 0 && ((Block)c).buildVisibility == BuildVisibility.hidden) continue;
            String ln = c.localizedName == null ? "" : c.localizedName.toLowerCase();
            if(query.isEmpty() || ln.contains(query) || c.name.toLowerCase().contains(query)) return c;
        }
        return null;
    }

    private static void rebuildDetail(){
        if(detailRef == null) return;
        Table d = detailRef;
        d.clear();
        d.top().left();
        if(selected == null){
            d.add("[darkgray]выбери элемент слева").left().pad(8f);
            return;
        }
        d.table(head -> {
            head.left();
            if(selected.uiIcon != null && selected.uiIcon.found()){
                head.image(new TextureRegionDrawable(selected.uiIcon)).size(36f).padRight(6f);
            }
            head.add("[accent]" + (selected.localizedName == null ? selected.name : selected.localizedName)).left().wrap().width(230f);
        }).left().growX().row();
        d.add("[darkgray]" + selected.name + "  |  " + selected.getContentType().name()).left().pad(2f, 0f, 4f, 0f).row();

        if(selected instanceof Block) blockDetail(d, (Block)selected);
        else if(selected instanceof Item) itemDetail(d, (Item)selected);
        else if(selected instanceof Liquid) liquidDetail(d, (Liquid)selected);
        else if(selected instanceof UnitType) unitDetail(d, (UnitType)selected);
    }

    // ---------------- детали: блок ----------------

    private static void blockDetail(Table d, Block b){
        d.add("[lightgray]Размер: " + b.size + "x" + b.size + "   HP: " + (int)b.scaledHealth
            + "   Категория: " + (b.category == null ? "-" : b.category.name())).left().wrap().width(290f).pad(2f, 0f, 4f, 0f).row();

        if(b.requirements != null && b.requirements.length > 0){
            d.add("[accent]Стоимость постройки:").left().row();
            StringBuilder sb = new StringBuilder();
            for(ItemStack st : b.requirements){
                if(st == null) continue;
                sb.append('[').append(hex(st.item.color)).append(']').append(st.amount).append("x ")
                    .append(st.item.localizedName == null ? st.item.name : st.item.localizedName).append("[]  ");
            }
            d.add(sb.toString()).left().wrap().width(290f).pad(1f, 0f, 3f, 0f).row();
        }

        // потребление
        boolean hasCons = false;
        StringBuilder cs = new StringBuilder();
        if(b.consumers != null){
            for(Consume c : b.consumers){
                if(c instanceof ConsumeItems){
                    hasCons = true;
                    for(ItemStack st : ((ConsumeItems)c).items){
                        if(st == null) continue;
                        cs.append('[').append(hex(st.item.color)).append(']').append(st.amount).append("x ")
                            .append(st.item.localizedName == null ? st.item.name : st.item.localizedName).append("[]  ");
                    }
                }else if(c instanceof ConsumeLiquid){
                    hasCons = true;
                    ConsumeLiquid cl = (ConsumeLiquid)c;
                    cs.append("[sky]").append(Strings.autoFixed(cl.amount, 2)).append("/с ")
                        .append(cl.liquid.localizedName == null ? cl.liquid.name : cl.liquid.localizedName).append("[]  ");
                }else if(c instanceof ConsumePower){
                    float use = ((ConsumePower)c).usage;
                    if(use > 0f){
                        hasCons = true;
                        cs.append("[yellow]⚡ ").append(Strings.autoFixed(use * 60f, 0)).append(" ед/с[]  ");
                    }
                }else{
                    hasCons = true;
                    cs.append("[darkgray]").append(c.getClass().getSimpleName()).append("[]  ");
                }
            }
        }
        if(hasCons){
            d.add("[accent]Потребляет:").left().row();
            d.add(cs.toString()).left().wrap().width(290f).pad(1f, 0f, 3f, 0f).row();
        }

        // производство
        StringBuilder ps = new StringBuilder();
        boolean hasProd = false;
        float craftTime = -1f;
        if(b instanceof GenericCrafter){
            GenericCrafter gc = (GenericCrafter)b;
            craftTime = gc.craftTime;
            if(gc.outputItem != null){
                hasProd = true;
                ps.append('[').append(hex(gc.outputItem.item.color)).append(']').append(gc.outputItem.amount).append("x ")
                    .append(loc(gc.outputItem.item)).append("[]  ");
            }
            if(gc.outputItems != null) for(ItemStack st : gc.outputItems){
                if(st == null) continue;
                hasProd = true;
                ps.append('[').append(hex(st.item.color)).append(']').append(st.amount).append("x ").append(loc(st.item)).append("[]  ");
            }
            if(gc.outputLiquid != null){
                hasProd = true;
                ps.append("[sky]").append(Strings.autoFixed(gc.outputLiquid.amount, 2)).append("/с ").append(loc(gc.outputLiquid.liquid)).append("[]  ");
            }
            if(gc.outputLiquids != null) for(LiquidStack ls : gc.outputLiquids){
                if(ls == null) continue;
                hasProd = true;
                ps.append("[sky]").append(Strings.autoFixed(ls.amount, 2)).append("/с ").append(loc(ls.liquid)).append("[]  ");
            }
        }else if(b instanceof Separator){
            Separator sp = (Separator)b;
            craftTime = sp.craftTime;
            hasProd = true;
            ps.append("[lightgray]случайный из: []");
            for(ItemStack st : sp.results){
                if(st == null) continue;
                ps.append('[').append(hex(st.item.color)).append(']').append(loc(st.item)).append("[] ");
            }
        }else if(b instanceof Drill){
            hasProd = true;
            ps.append("[lightgray]добывает руду тайла, уровень твёрдости ≤ [white]").append(((Drill)b).tier).append("[]");
        }else if(b instanceof Pump){
            hasProd = true;
            if(b instanceof SolidPump && ((SolidPump)b).result != null){
                ps.append("[sky]").append(loc(((SolidPump)b).result)).append("[] [lightgray](из пола)[]");
            }else{
                ps.append("[sky]жидкость тайла[] [lightgray](").append(Strings.autoFixed(((Pump)b).pumpAmount * 60f, 1)).append("/с)[]");
            }
        }else if(b instanceof UnitFactory){
            hasProd = true;
            d.add("[accent]Производит юнитов:").left().row();
            for(UnitFactory.UnitPlan p : ((UnitFactory)b).plans){
                if(p == null || p.unit == null) continue;
                StringBuilder req = new StringBuilder();
                if(p.requirements != null){
                    for(ItemStack st : p.requirements){
                        if(st == null) continue;
                        req.append('[').append(hex(st.item.color)).append(']').append(st.amount).append("x ").append(loc(st.item)).append("[] ");
                    }
                }
                final UnitType ut = p.unit;
                d.table(row -> {
                    row.left();
                    if(ut.uiIcon != null && ut.uiIcon.found()) row.image(new TextureRegionDrawable(ut.uiIcon)).size(22f).padRight(3f);
                    row.button(loc(ut), MKUKICSkin.actBtn, () -> {
                        category = 3;
                        selected = ut;
                        rebuildLists();
                    }).minWidth(110f).height(26f).left();
                    row.add("  " + req + "[darkgray]" + Strings.autoFixed(p.time / 60f, 1) + "с").left().wrap().width(150f);
                }).left().growX().pad(1f).row();
            }
        }
        if(hasProd){
            d.add("[accent]Производит:").left().row();
            d.add(ps.toString() + (craftTime > 0f ? "  [darkgray](" + Strings.autoFixed(craftTime / 60f, 1) + "с/цикл)" : ""))
                .left().wrap().width(290f).pad(1f, 0f, 3f, 0f).row();
        }

        if(b.description != null && !b.description.isEmpty()){
            d.add("[darkgray]" + Strings.stripColors(b.displayDescription())).left().top().wrap().width(290f).padTop(4f).row();
        }
    }

    // ---------------- детали: предмет ----------------

    private static void itemDetail(Table d, Item it){
        buildIndex();
        d.add("[lightgray]Твёрдость: " + it.hardness + "   Взрывоопасность: " + Strings.autoFixed(it.explosiveness, 2)
            + "   Горючесть: " + Strings.autoFixed(it.flammability, 2)
            + "   Радиоактивность: " + Strings.autoFixed(it.radioactivity, 2)).left().top().wrap().width(290f).pad(2f, 0f, 4f, 0f).row();

        if(it.hardness > 0){
            d.add("[accent]Добывается бурами (твёрдость " + it.hardness + "):").left().row();
            StringBuilder sb = new StringBuilder();
            for(Block b : content.blocks()){
                if(b instanceof Drill && ((Drill)b).tier >= it.hardness && b.buildVisibility != BuildVisibility.hidden){
                    sb.append("[lightgray]• ").append(loc(b)).append("[]\n");
                }
            }
            d.add(sb.length() == 0 ? "[darkgray]—" : sb.toString()).left().top().wrap().width(290f).pad(1f, 0f, 3f, 4f).row();
        }

        contentList(d, "Производят постройки:", itemProducers.get(it));
        contentList(d, "Потребляют постройки:", itemConsumers.get(it));
    }

    private static void liquidDetail(Table d, Liquid l){
        buildIndex();
        d.add("[lightgray]" + (l.gas ? "Газ" : "Жидкость") + "   Температура: " + Strings.autoFixed(l.temperature, 2)
            + "   Вязкость: " + Strings.autoFixed(l.viscosity, 2)
            + "   Теплоёмкость: " + Strings.autoFixed(l.heatCapacity, 2)).left().top().wrap().width(290f).pad(2f, 0f, 4f, 0f).row();
        contentList(d, "Производят постройки:", liqProducers.get(l));
        contentList(d, "Потребляют постройки:", liqConsumers.get(l));
    }

    private static void unitDetail(Table d, UnitType u){
        buildIndex();
        d.add("[lightgray]HP: " + (int)u.health + "   Скорость: " + Strings.autoFixed(u.speed * 60f / tilesize, 1) + " тайл/с"
            + "   Дальность: " + Strings.autoFixed(u.range / tilesize, 1) + " тайлов"
            + (u.flying ? "   [sky]летающий[]" : "")).left().top().wrap().width(290f).pad(2f, 0f, 4f, 0f).row();
        if(u.buildSpeed > 0f) d.add("[lightgray]Строит: x" + Strings.autoFixed(u.buildSpeed, 2)).left().row();
        if(u.mineSpeed > 0f && u.mineTier >= 0) d.add("[lightgray]Копает: уровень " + u.mineTier + ", скорость " + Strings.autoFixed(u.mineSpeed, 1)).left().row();
        if(u.canHeal) d.add("[lime]Ремонтирует союзников").left().row();

        contentList(d, "Производится на фабриках:", unitFactories.get(u));
    }

    // кликабельный список построек
    private static void contentList(Table d, String title, @Nullable Seq<Block> blocks){
        if(blocks == null || blocks.isEmpty()) return;
        d.add("[accent]" + title + " (" + blocks.size + ")").left().padTop(3f).row();
        Seq<Block> uniq = blocks.copy().distinct();
        int i = 0;
        Table grid = d.table(g -> {}).left().growX().get();
        grid.top().left();
        for(Block b : uniq){
            if(b == null) continue;
            final Block fb = b;
            grid.button(loc(b), MKUKICSkin.actBtn, () -> {
                category = 0;
                selected = fb;
                rebuildLists();
            }).minWidth(138f).height(26f).pad(1f).left();
            if(++i % 2 == 0) grid.row();
        }
        if(i % 2 != 0) grid.row();
        d.row();
    }

    private static String loc(UnlockableContent c){
        return c.localizedName == null ? c.name : c.localizedName;
    }

    private static String hex(Color c){
        return "#" + String.format("%08x", c.rgba8888()).substring(0, 6);
    }
}
