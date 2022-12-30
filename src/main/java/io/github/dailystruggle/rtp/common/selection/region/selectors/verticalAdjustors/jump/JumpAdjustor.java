package io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.jump;

import io.github.dailystruggle.commandsapi.bukkit.LocalParameters.*;
import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.SafetyKeys;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.VerticalAdjustor;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPBlock;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPChunk;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPLocation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class JumpAdjustor extends VerticalAdjustor<JumpAdjustorKeys> {
    private static final EnumMap<JumpAdjustorKeys,Object> defaults = new EnumMap<>(JumpAdjustorKeys.class);
    protected static final Map<String, CommandParameter> subParameters = new ConcurrentHashMap<>();
    protected static final List<String> keys = Arrays.stream(GenericMemoryShapeParams.values()).map(Enum::name).collect(Collectors.toList());
    static {
        defaults.put(JumpAdjustorKeys.maxY, 127);
        defaults.put(JumpAdjustorKeys.minY,32);
        defaults.put(JumpAdjustorKeys.step, 0);
        defaults.put(JumpAdjustorKeys.requireSkyLight,true);

        subParameters.put("maxy",new IntegerParameter("rtp.params", "highest possible location", (sender, s) -> true, 64,92,127,256,320));
        subParameters.put("miny",new IntegerParameter("rtp.params", "lowest possible location", (sender, s) -> true, -64,0,64,128));
        subParameters.put("step",new IntegerParameter("rtp.params", "initial amount to jump", (sender, s) -> true, 1,16,32));
        subParameters.put("requireskyLight",new BooleanParameter("rtp.params", "require sky light for placement", (sender, s) -> true));
    }

    public JumpAdjustor(List<Predicate<RTPBlock>> verifiers) {
        super(JumpAdjustorKeys.class,"jump",verifiers, defaults);
    }

    @Override
    public Collection<String> keys() {
        return Arrays.stream(JumpAdjustorKeys.values()).map(Enum::name).collect(Collectors.toList());
    }

    private static final Set<String> unsafeBlocks = new ConcurrentSkipListSet<>();
    private static final AtomicLong lastUpdate = new AtomicLong();
    @Override
    public @Nullable
    RTPLocation adjust(@NotNull RTPChunk chunk) {
        if(chunk==null) return null;

        int maxY = getNumber(JumpAdjustorKeys.maxY, 256L).intValue();
        int minY = getNumber(JumpAdjustorKeys.minY, 0L).intValue();
        int step = getNumber(JumpAdjustorKeys.step, 0).intValue();

        boolean requireSkyLight;
        Object o = getData().getOrDefault(JumpAdjustorKeys.requireSkyLight, false);
        if(o instanceof Boolean) {
            requireSkyLight = (Boolean) o;
        }
        else requireSkyLight = Boolean.parseBoolean(o.toString());

        int oldY = minY;

        //enforce valid inputs
        step = Math.max(step,1);
        step = Math.min(step,(maxY-minY)/8);

        long t = System.currentTimeMillis();
        long dt = t - lastUpdate.get();
        if (dt > 5000 || dt < 0) {
            ConfigParser<SafetyKeys> safety = (ConfigParser<SafetyKeys>) RTP.configs.getParser(SafetyKeys.class);
            Object value = safety.getConfigValue(SafetyKeys.unsafeBlocks, new ArrayList<>());
            unsafeBlocks.clear();
            if(value instanceof Collection) {
                unsafeBlocks.addAll(((Collection<?>) value).stream().filter(Objects::nonNull).map(Object::toString).collect(Collectors.toSet()));
            }
            lastUpdate.set(t);
        }

        for(int i = minY; i < maxY; i++) {
            RTPBlock blockAt = chunk.getBlockAt(7, i, 7);
            if(!blockAt.isAir() && !unsafeBlocks.contains(blockAt.getMaterial())) {
                minY = i;
                break;
            }
        }

        for(int it_len = step; it_len > 2; it_len = it_len/2) {
            for(int i = minY; i < maxY; i+= it_len) {
                RTPBlock block1;
                try {
                    block1 = chunk.getBlockAt(7, i, 7);
                } catch (NullPointerException exception) {
                    exception.printStackTrace();
                    return null;
                }
                RTPBlock block2 = chunk.getBlockAt(7, i+1, 7);
                int skylight = 15;
                if(requireSkyLight) skylight = block2.skyLight();
                if(block1.isAir() && block2.isAir() && skylight > 7
                        && !unsafeBlocks.contains(block2.getMaterial())) {
                    minY = oldY;
                    maxY = i;
                    break;
                }
                if(i > maxY-it_len) return null;
                oldY = i;
            }
        }

        for(int i = minY; i < maxY; i++) {
            RTPBlock block1 = chunk.getBlockAt(7, i, 7);
            RTPBlock block2 = chunk.getBlockAt(7, i+1, 7);
            int skylight = 15;
            if(requireSkyLight) skylight = block2.skyLight();
            if(block1.isAir() && block2.isAir() && skylight > 7
                    && !unsafeBlocks.contains(block2.getMaterial())
                    && !unsafeBlocks.contains(block1.getMaterial())
                    && !unsafeBlocks.contains(chunk.getBlockAt(7, i-1, 7).getMaterial())) {
                return block1.getLocation();
            }
        }

        return null;
    }

    @Override
    public boolean testPlacement(@NotNull RTPBlock block) {
        for (Predicate<RTPBlock> rtpLocationPredicate : verifiers) {
            if (!rtpLocationPredicate.test(block))
                return false;
        }
        return true;
    }

    @Override
    public Map<String, CommandParameter> getParameters() {
        return subParameters;
    }

    @Override
    public int minY() {
        return getNumber(JumpAdjustorKeys.minY,0).intValue();
    }

    @Override
    public int maxY() {
        return getNumber(JumpAdjustorKeys.maxY,256).intValue();
    }
}