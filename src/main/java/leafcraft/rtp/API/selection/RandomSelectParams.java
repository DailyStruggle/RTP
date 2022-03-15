package leafcraft.rtp.API.selection;

import leafcraft.rtp.tools.configuration.Configs;
import leafcraft.rtp.tools.selection.TeleportRegion;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public class RandomSelectParams {
    public final TeleportRegion.Shapes shape;
    public final UUID worldID;
    public final int r;
    public final int cr;
    public final int cx;
    public final int cz;
    public final int minY;
    public final int maxY;
    public final boolean requireSkyLight;
    public boolean worldBorderOverride;
    public final boolean uniquePlacements;
    public final boolean expand;

    public final Map<String,String> params;

    public RandomSelectParams(@NotNull World world, @Nullable Map<String,String> params) {
        if(params == null) params = new HashMap<>();
        String worldName = params.getOrDefault("world",world.getName());
        worldName = Configs.worlds.worldPlaceholder2Name(worldName);
        if(!Configs.worlds.checkWorldExists(worldName)) worldName = world.getName();
        world = Bukkit.getWorld(worldName);
        Objects.requireNonNull(world);

        String defaultRegion = (String)Configs.worlds.getWorldSetting(world.getName(),"region","default");
        String regionName = params.getOrDefault("region",defaultRegion);

        String targetWorldName = (String)Configs.regions.getRegionSetting(regionName,"world",world.getName());
        if(Configs.worlds.checkWorldExists(targetWorldName)) {
            world = Bukkit.getWorld(targetWorldName);
            Objects.requireNonNull(world);
            worldName = targetWorldName;
        }

        this.params = params;

        worldBorderOverride = Boolean.getBoolean(this.params.getOrDefault("worldBorderOverride","false"));
        if(worldBorderOverride) {
            this.params.put("shape", "SQUARE");
            this.params.put("radius", String.valueOf((int)(world.getWorldBorder().getSize()/16)));
            this.params.put("centerX", String.valueOf(world.getWorldBorder().getCenter().getBlockX()));
            this.params.put("centerZ", String.valueOf(world.getWorldBorder().getCenter().getBlockZ()));
        }

        //ugh string parsing, but at least it's short and clean
        // fills in any missing values
        this.params.put("world",worldName);
        this.params.putIfAbsent("shape",(String)Configs.regions.getRegionSetting(regionName,"shape","CIRCLE"));
        this.params.putIfAbsent("mode",(String)Configs.regions.getRegionSetting(regionName,"mode","ACCUMULATE"));
        this.params.putIfAbsent("radius", (Configs.regions.getRegionSetting(regionName,"radius",4096)).toString());
        this.params.putIfAbsent("centerRadius", (Configs.regions.getRegionSetting(regionName,"centerRadius",1024)).toString());
        this.params.putIfAbsent("centerX", (Configs.regions.getRegionSetting(regionName,"centerX",0)).toString());
        this.params.putIfAbsent("centerZ", (Configs.regions.getRegionSetting(regionName,"centerZ",0)).toString());
        this.params.putIfAbsent("weight", (Configs.regions.getRegionSetting(regionName,"weight",1.0)).toString());
        this.params.putIfAbsent("minY", (Configs.regions.getRegionSetting(regionName,"minY",0)).toString());
        this.params.putIfAbsent("maxY", (Configs.regions.getRegionSetting(regionName,"maxY",128)).toString());
        this.params.putIfAbsent("requireSkyLight", (Configs.regions.getRegionSetting(regionName,"requireSkyLight",true)).toString());
        this.params.putIfAbsent("worldBorderOverride", (Configs.regions.getRegionSetting(regionName,"worldBorderOverride",false)).toString());
        this.params.putIfAbsent("uniquePlacements", (Configs.regions.getRegionSetting(regionName,"uniquePlacements",false)).toString());
        this.params.putIfAbsent("expand", (Configs.regions.getRegionSetting(regionName,"expand",false)).toString());

        worldID = Objects.requireNonNull(world).getUID();
        shape = TeleportRegion.Shapes.valueOf(this.params.getOrDefault("shape","CIRCLE"));
        r = Integer.parseInt(this.params.get("radius"));
        cr = Integer.parseInt(this.params.get("centerRadius"));
        cx = Integer.parseInt(this.params.get("centerX"));
        cz = Integer.parseInt(this.params.get("centerZ"));
        minY = Integer.parseInt(this.params.get("minY"));
        maxY = Integer.parseInt(this.params.get("maxY"));
        requireSkyLight = Boolean.parseBoolean(this.params.get("requireSkyLight"));
        worldBorderOverride = Boolean.parseBoolean(this.params.get("worldBorderOverride"));
        uniquePlacements = Boolean.parseBoolean(this.params.get("uniquePlacements"));
        expand = Boolean.parseBoolean(this.params.get("expand"));
    }

    @Override
    public boolean equals(Object o) {
        if(this == o) return true;
        if(o == null) return false;
        if(o instanceof RandomSelectParams that) {
            if(this.worldID != that.worldID) return false;
            if(this.shape != that.shape) return false;
            if(this.r != that.r) return false;
            if(this.cr != that.cr) return false;
            if(this.cx != that.cx) return false;
            if(this.cz != that.cz) return false;
            if(this.maxY != that.maxY) return false;
            if(this.minY != that.minY) return false;
            if(this.requireSkyLight != that.requireSkyLight) return false;
            if(this.worldBorderOverride != that.worldBorderOverride) return false;
            if(this.uniquePlacements != that.uniquePlacements) return false;
            return this.expand == that.expand;
        }
        return false;
    }

    @Override
    public int hashCode() {
        int res = 0;
        res ^= worldID.hashCode();
        res ^= shape.hashCode();
        res ^= Integer.hashCode(r);
        res ^= Integer.hashCode(cr);
        res ^= Integer.hashCode(cx);
        res ^= Integer.hashCode(cz);
        res ^= Integer.hashCode(minY);
        res ^= Integer.hashCode(maxY);
        res ^= Boolean.hashCode(requireSkyLight);
        res ^= Boolean.hashCode(worldBorderOverride);
        res ^= Boolean.hashCode(uniquePlacements);
        res ^= Boolean.hashCode(expand);
        return res;
    }

    @Override
    public String toString() {
        World world = Bukkit.getWorld(worldID);
        Objects.requireNonNull(world);
        return "RandomSelectParams{" +
                "shape=" + shape +
                ", world=" + world.getName() +
                ", r=" + r +
                ", cr=" + cr +
                ", cx=" + cx +
                ", cz=" + cz +
                ", minY=" + minY +
                ", maxY=" + maxY +
                ", requireSkyLight=" + requireSkyLight +
                ", worldBorderOverride=" + worldBorderOverride +
                ", uniquePlacements=" + uniquePlacements +
                ", expand=" + expand +
                '}';
    }
}
