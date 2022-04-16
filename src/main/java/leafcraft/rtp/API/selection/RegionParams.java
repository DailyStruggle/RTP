package leafcraft.rtp.api.selection;

import leafcraft.rtp.api.RTPAPI;
import leafcraft.rtp.api.configuration.ConfigParser;
import leafcraft.rtp.api.configuration.Configs;
import leafcraft.rtp.api.configuration.enums.RegionKeys;
import leafcraft.rtp.api.configuration.enums.WorldKeys;
import leafcraft.rtp.api.factory.Factory;
import leafcraft.rtp.api.factory.FactoryValue;
import leafcraft.rtp.api.selection.worldborder.WorldBorder;
import leafcraft.rtp.api.substitutions.RTPWorld;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public class RegionParams extends FactoryValue<RegionKeys> {
//    public String shape;
//    public UUID worldID;
//    public int r;
//    public int cr;
//    public int cx;
//    public int cz;
//    public int minY;
//    public int maxY;
//    public boolean requireSkyLight;
//    public boolean worldBorderOverride;
//    public boolean uniquePlacements;
//    public boolean expand;

//    public Map<String,String> params;

    public RegionParams() {
        super(RegionKeys.class);
        setParams(null, null);
    }

    public RegionParams(@Nullable RTPWorld world, @Nullable Map<String, String> params) {
        super(RegionKeys.class);
        setParams(world,params);
    }

    /**
     * @param world valid world to check, null input corrects to default world
     * @param params
     */
    public void setParams(@Nullable RTPWorld world, @Nullable Map<String, String> params) {
        if(params == null) params = new HashMap<>();

        RTPAPI rtpapi = RTPAPI.getInstance();

        if(world == null) world = rtpapi.serverAccessor.getDefaultRTPWorld();

        Configs configs = rtpapi.configs;

        String worldName = params.getOrDefault("world", world.name());
        ConfigParser<WorldKeys> worldsParser = configs.worlds.getParser(worldName);
        if(worldsParser!=null) worldName = world.name();
        else {
            worldsParser = configs.worlds.getParser("default");
            assert worldsParser != null;
        }
        world = rtpapi.serverAccessor.getRTPWorld(worldName);
        Objects.requireNonNull(world);

        String defaultRegionName = (String)worldsParser.getConfigValue(WorldKeys.region,"default");

        String regionName = params.getOrDefault("region", defaultRegionName);

        ConfigParser<RegionKeys> regionsParser = configs.regions.getParser(regionName);

        String targetWorldName = (String)regionsParser.getConfigValue(RegionKeys.world,world.name());
        if(configs.checkWorldExists(targetWorldName)) {
            world = rtpapi.serverAccessor.getRTPWorld(targetWorldName);
            Objects.requireNonNull(world);
            worldName = targetWorldName;
        }

        boolean worldBorderOverride = Boolean.getBoolean(params.getOrDefault("worldBorderOverride","false"));
        if(worldBorderOverride) {
            final WorldBorder worldBorder = rtpapi.selectionAPI.worldBorder;
            params.put("shape", "SQUARE");
            params.put("radius", String.valueOf((int)(worldBorder.getRadius(worldName)/16)));

            long[] center = worldBorder.getCenter(worldName);
            params.put("centerX", String.valueOf(center[0]));
            params.put("centerZ", String.valueOf(center[2]));
        }

        //ugh string parsing, but at least it's short and clean
        // todo: for each shape parameter
        // fills in any missing values
        for(RegionKeys key : RegionKeys.values()) {
            if(params.containsKey(key.name())) {
                String valStr = params.get(key.name());
                RTPAPI.factoryNames factoryName = null;
                try {
                    factoryName = RTPAPI.factoryNames.valueOf(key.name());
                } catch (IllegalArgumentException ignored) {

                }

                if (factoryName != null) {
                    Factory<?> factory = rtpapi.factoryMap.get(factoryName);
                    if (factory.contains(valStr)) {
                        FactoryValue<?> construct = factory.construct(valStr);
                        this.data.put(key, construct);
                    } else continue;
                } else {
                    Object val;
                    try {
                        val = Long.parseLong(valStr);
                        this.data.put(key,val);
                        continue;
                    } catch (NumberFormatException ignored) {

                    }

                    try {
                        val = Double.parseDouble(valStr);
                        this.data.put(key,val);
                        continue;
                    } catch (NumberFormatException ignored) {

                    }

                    if(valStr.equalsIgnoreCase("true")) {
                        val = Boolean.TRUE;
                        this.data.put(key,val);
                        continue;
                    }

                    if(valStr.equalsIgnoreCase("false")) {
                        val = Boolean.FALSE;
                        this.data.put(key,val);
                        continue;
                    }

                    RTPWorld rtpWorld = rtpapi.serverAccessor.getRTPWorld(valStr);
                    if(rtpWorld != null) {
                        val = rtpWorld;
                        this.data.put(key,val);
                        continue;
                    }

                    this.data.put(key,valStr);
                }
            }
            else {
                this.data.put(key,regionsParser.getConfigValue(key,null));
            }
        }
    }

    @Override
    public boolean equals(Object o) {
        if(this == o) return true;
        if(o == null) return false;
        if(o instanceof RegionParams that) {
            for(var entry : this.data.entrySet()) {
                Object other = that.data.get(entry.getKey());
                if(!entry.getValue().equals(other)) return false;
            }
            return true;
        }
        return false;
    }

    @Override
    public int hashCode() {
        int res = 0;
        for(var o : this.data.values()) {
            res ^= o.hashCode();
        }
        return res;
    }
}
