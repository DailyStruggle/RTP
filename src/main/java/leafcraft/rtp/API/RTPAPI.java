package leafcraft.rtp.API;

import leafcraft.rtp.API.selection.region.SelectorInterface;
import leafcraft.rtp.RTP;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.ConcurrentHashMap;

public class RTPAPI {
    //all available region selectors and a default
    private static SelectorInterface defaultSelector = null;
    private static final ConcurrentHashMap<String,SelectorInterface> selectors = new ConcurrentHashMap<>();

    //server version checking
    private static String version = null;
    private static Integer intVersion = null;

    @Nullable
    public static SelectorInterface getSelector(String name) {
        if(name == null || name.isBlank()) return null;
        return selectors.get(name);
    }

    @Nullable
    public static SelectorInterface addSelector(String name, SelectorInterface selectorInterface) {
        if(name == null || name.isBlank()) return null;
        if(selectorInterface == null) return null;
        if(defaultSelector == null) defaultSelector = selectorInterface;
        return selectors.put(name,selectorInterface);
    }

    public static String getServerVersion() {
        if(version == null) {
            version = RTP.getPlugin().getServer().getClass().getPackage().getName();
            version = version.replaceAll("[-+^.a-zA-Z]*","");
        }

        return version;
    }

    public static Integer getServerIntVersion() {
        if(intVersion == null) {
            String[] splitVersion = getServerVersion().split("_");
            if(splitVersion.length == 0) {
                intVersion = 0;
            }
            else if (splitVersion.length == 1) {
                intVersion = Integer.valueOf(splitVersion[0]);
            }
            else {
                intVersion = Integer.valueOf(splitVersion[1]);
            }
        }
        return intVersion;
    }
}
