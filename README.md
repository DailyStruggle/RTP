# RTP

A spigot plugin for random teleportation.
https://www.spigotmc.org/resources/rtp.94812/

The goal of this plugin is function over form, 
 * unique geometry to produce flat random distributions and support a learning algorithm
 * unique methods to learn from placements and avoid redundant selections, e.g. trying the same ocean thousands of times
 * region-based design, supporting any number of rtp regions per world
 * per-world and per-region configuration and teleport permissions
 * adjustment of region and world settings by command or by config file 
 * adjustment of all plugin messages in lang.yml

I made this in a flow state and now I only understand how it works because I wrote down the math. I question my sanity.

V1 is primarily focused on setting up a functional implementation with various plugin integrations.

V2 is planned to focus on pushing main functions into a server-independent API and on supplying developers with additional methods, particularly for adding shapes and distributions.

V2 will take an unknown amount of time for the following reasons: 
 - V1 already involves some multi-dimensional thinking, so it's hard to conceptualize the entire thing and how to redefine it.
 - Several classes in V1 currently rely on supplied methods from the bukkit/spigot server, so I want to replace a lot of those supplied methods to enforce platform-independence in the new API.
 - Java is fickle in ways I didn't expect, so there is a lot of nuance involved in implementing the new API design.
 - I want to implement a bunch of system-level changes in a single pass to ease the burden on operators.
