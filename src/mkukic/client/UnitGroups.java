package mkukic.client;

import arc.files.*;
import arc.struct.*;
import arc.util.*;
import arc.util.serialization.*;
import mindustry.type.*;

/**
 * Группы юнитов: каждому ТИПУ юнита можно назначить свою группу со своим набором действий.
 * Группа без списка типов = «любой юнит». Первая подходящая группа побеждает.
 * Хранится в configs/mkukic-client/groups.json.
 */
public class UnitGroups{
    public static class Group{
        public String name = "группа";
        /** Имена типов юнитов (unit.type.name); пусто = любые юниты. */
        public Seq<String> types = new Seq<>();
        public boolean override = true;   // использовать настройки группы вместо общих
        public boolean mine = false;
        public boolean build = false;
        public boolean repair = false;
        public boolean follow = false;
        public boolean escort = false;
        public boolean assist = false;      // помощь игрокам: стройка (команда «помощь») + бой рядом
        public Seq<String> helpPlayers = new Seq<>(); // кому помогает группа (пусто = мне)
        public boolean target = true;
        public boolean retreat = true;
        public boolean safe = false;      // остерегаться зон атаки врага
    }

    public static final Seq<Group> groups = new Seq<>();
    private static final Json json = new Json();
    private static @Nullable Fi file;

    public static void init(Fi folder){
        file = folder.child("groups.json");
        json.setIgnoreUnknownFields(true);
        json.setOutputType(JsonWriter.OutputType.json);
        load();
    }

    public static void load(){
        groups.clear();
        if(file == null || !file.exists()) return;
        try{
            Seq<Group> g = json.fromJson(Seq.class, Group.class, file.readString());
            if(g != null) groups.addAll(g);
        }catch(Throwable t){
            Log.err("[MKUKIC] groups.json повреждён", t);
        }
    }

    public static void save(){
        if(file == null) return;
        try{
            file.writeString(json.prettyPrint(json.toJson(groups, Seq.class, Group.class)), false);
        }catch(Throwable t){
            Log.err("[MKUKIC] не удалось сохранить groups.json", t);
        }
    }

    /** Группа для типа юнита: первая, содержащая тип; иначе первая «любая»; иначе null. */
    public static @Nullable Group forType(UnitType t){
        Group any = null;
        for(Group g : groups){
            if(g.types.isEmpty()){
                if(any == null) any = g;
            }else if(t != null && g.types.contains(t.name, false)){
                return g;
            }
        }
        return any;
    }
}
