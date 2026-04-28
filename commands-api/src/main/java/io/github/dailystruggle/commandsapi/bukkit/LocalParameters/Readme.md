# Local Parameters (Bukkit)

Ready-made `CommandParameter` impls for Bukkit-family platforms (Spigot,
Paper, Folia). All extend `BukkitParameter`, which bridges the core's
`UUID callerId` back to a `CommandSender` so `isRelevant` can read the real
sender.

See `../../README.md` (module root) for tree model, `key=value` syntax, and
the two-call `onCommand` lifecycle. Usage:

```java
tree.addParameter("player", new OnlinePlayerParameter(
    "rtp.other", "target player",
    (sender, name) -> sender.hasPermission("rtp.other")
                   && Bukkit.getPlayerExact(name) != null));
```

## Catalog

| Class                    | `values()` source                          | Use                         |
|--------------------------|--------------------------------------------|-----------------------------|
| `BooleanParameter`       | `{"true","false"}`                         | Flags (`persist=true`).     |
| `IntegerParameter`       | Bounded range from constructor             | Counts (`count=10`).        |
| `FloatParameter`         | Bounded range from constructor             | Ratios (`weight=0.5`).      |
| `CoordinateParameter`    | Numeric tokens                             | `x=100`, `y=64`, `z=-200`.  |
| `ColorParameter`         | Color names / hex                          | Cosmetic / effects.         |
| `EnumParameter`          | Names of an enum class                     | Fixed enumerations.         |
| `PotionParameter`        | `PotionEffectType` names                   | Potion identifiers.         |
| `WorldParameter`         | `Bukkit.getWorlds()` names                 | World-scoped cmds.          |
| `OnlinePlayerParameter`  | `Bukkit.getOnlinePlayers()` names          | Target online player.       |
| `OfflinePlayerParameter` | Known offline player names                 | Admin / moderation.         |

## Writing your own

Subclass `BukkitParameter` and implement `values()`:

```java
public final class RegionParameter extends BukkitParameter {
    public RegionParameter(String perm, String desc,
                           BiFunction<CommandSender,String,Boolean> isRelevant) {
        super(perm, desc, isRelevant);
    }
    @Override public Set<String> values() { return RegionKeys.regions.keySet(); }
}
```

Guidelines:
- `values()` fires on every tab-complete — keep cheap or cache.
- Per-caller filtering goes in `isRelevant`, not by mutating `values()`.
- To unlock further params once a value is chosen, populate `subParamMap`
  with `value → (name → CommandParameter)`.
- `CommandsAPI.serverId` (`UUID(0,0)`) is the console sentinel;
  `BukkitParameter` already maps it to `Bukkit.getConsoleSender()`.
