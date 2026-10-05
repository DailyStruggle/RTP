package io.github.dailystruggle.bstats.api;

/**
 * Platform-neutral server facts; {@link BStatsPlatform} maps them onto the
 * platform-specific root fields bStats expects. {@code null} fields are omitted
 * from the payload.
 *
 * @param playerCount online players; negative is reported as 0
 * @param onlineMode  authentication mode, or {@code null} when unknown
 * @param name        server software name (e.g. {@code Paper}, {@code Fabric})
 * @param version     server software version string
 */
public record ServerInfo(int playerCount, Boolean onlineMode, String name, String version) {

    public static ServerInfo of(int playerCount, String name, String version) {
        return new ServerInfo(playerCount, null, name, version);
    }

    public ServerInfo withOnlineMode(Boolean onlineMode) {
        return new ServerInfo(playerCount, onlineMode, name, version);
    }
}
