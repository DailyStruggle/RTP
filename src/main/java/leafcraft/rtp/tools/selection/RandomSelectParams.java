package leafcraft.rtp.tools.selection;

import leafcraft.rtp.RTP;
import leafcraft.rtp.tools.configuration.Configs;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public class RandomSelectParams {
    public TeleportRegion.Shapes shape;
    public UUID worldID;
    public int r, cr, cx, cz, minY, maxY;
    public boolean requireSkyLight,worldBorderOverride,uniquePlacements,expand;

    public Map<String,String> params;

    public RandomSelectParams(@NotNull World world, @Nullable Map<String,String> params) {
        if(params == null) params = new HashMap<>();
        Configs configs = RTP.getConfigs();
        String worldName = params.getOrDefault("world",world.getName());
        worldName = configs.worlds.worldPlaceholder2Name(worldName);
        if(!configs.worlds.checkWorldExists(worldName)) worldName = world.getName();
        world = Bukkit.getWorld(worldName);

        String defaultRegion = (String)configs.worlds.getWorldSetting(world.getName(),"region","default");
        String regionName = params.getOrDefault("region",defaultRegion);

        String targetWorldName = (String)configs.regions.getRegionSetting(regionName,"world",world.getName());
        if(configs.worlds.checkWorldExists(targetWorldName)) {
            world = Bukkit.getWorld(targetWorldName);
            worldName = targetWorldName;
        }

        this.params = params;

        worldBorderOverride = Boolean.getBoolean(this.params.getOrDefault("worldBorderOverride","false"));
        if(worldBorderOverride) {
            this.params.put("shape", "SQUARE");
            this.params.put("radius", String.valueOf((int)world.getWorldBorder().getSize()/16));
            this.params.put("centerX", String.valueOf(world.getWorldBorder().getCenter().getBlockX()/16));
            this.params.put("centerZ", String.valueOf(world.getWorldBorder().getCenter().getBlockZ()/16));
        }

        //ugh string parsing, but at least it's short and clean
        // fills in any missing values
        this.params.put("world",worldName);
        this.params.putIfAbsent("shape",(String)configs.regions.getRegionSetting(regionName,"shape","CIRCLE"));
        this.params.putIfAbsent("mode",(String)configs.regions.getRegionSetting(regionName,"mode","ACCUMULATE"));
        this.params.putIfAbsent("radius", (configs.regions.getRegionSetting(regionName,"radius",4096)).toString());
        this.params.putIfAbsent("centerRadius", (configs.regions.getRegionSetting(regionName,"centerRadius",1024)).toString());
        this.params.putIfAbsent("centerX", (configs.regions.getRegionSetting(regionName,"centerX",0)).toString());
        this.params.putIfAbsent("centerZ", (configs.regions.getRegionSetting(regionName,"centerZ",0)).toString());
        this.params.putIfAbsent("weight", (configs.regions.getRegionSetting(regionName,"weight",1.0)).toString());
        this.params.putIfAbsent("minY", (configs.regions.getRegionSetting(regionName,"minY",0)).toString());
        this.params.putIfAbsent("maxY", (configs.regions.getRegionSetting(regionName,"maxY",128)).toString());
        this.params.putIfAbsent("requireSkyLight", (configs.regions.getRegionSetting(regionName,"requireSkyLight",true)).toString());
        this.params.putIfAbsent("worldBorderOverride", (configs.regions.getRegionSetting(regionName,"worldBorderOverride",false)).toString());
        this.params.putIfAbsent("uniquePlacements", (configs.regions.getRegionSetting(regionName,"uniquePlacements",false)).toString());
        this.params.putIfAbsent("expand", (configs.regions.getRegionSetting(regionName,"expand",false)).toString());

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
        if(o instanceof RandomSelectParams) {
            RandomSelectParams that = (RandomSelectParams) o;
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
        return "RandomSelectParams{" +
                "shape=" + shape +
                ", world=" + Bukkit.getWorld(worldID).getName() +
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
