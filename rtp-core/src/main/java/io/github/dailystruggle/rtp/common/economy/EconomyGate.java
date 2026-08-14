package io.github.dailystruggle.rtp.common.economy;

import io.github.dailystruggle.commandsapi.common.CommandsAPI;
import io.github.dailystruggle.rtp.api.economy.RTPEconomy;
import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.EconomyKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.RegionKeys;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import java.util.UUID;

/**
 * Single shared economy charging gate for one teleport charge.
 *
 * <p>This is the only place in {@code rtp-core} that computes a teleport price,
 * performs the balance-floor affordability check, and issues the withdrawal.
 * Both the {@code /rtp} command path ({@code RTPCmd.compute}) and the
 * addon-facing teleport entry point ({@code RTPAPI.teleport(UUID, RtpTarget)},
 * which every GUI/menu addon drives) route through it, so money leaves an
 * account through exactly one code path over whatever {@link RTPEconomy}
 * provider is bound (Vault by default, addon-replaceable via
 * {@code RTPAPI.hooks().economy().bind(...)}). Callers own only the user-facing
 * messaging and the {@code TeleportData.cost} bookkeeping used for the
 * refund-on-failure path ({@code RTPTeleportCancel}).
 *
 * <p>The charge is {@code EconomyKeys.price} (or {@code EconomyKeys.priceOther}
 * for a target that is not the payer, see {@link PriceKey}) plus the target
 * region's {@code RegionKeys.price}, optionally plus
 * {@code paramsPrice}/{@code biomePrice} when the caller supplied
 * shape/vert/world-border or biome parameters. The charge is skipped entirely
 * (returns {@link Result#ALLOWED} with {@code cost == 0}) when no economy
 * provider is bound, when the payer is the console/server sender, or when the
 * free-check subject has {@code rtp.free}. The balance-floor rejection uses
 * {@code EconomyKeys.balanceFloor}. The actual withdrawal is fire-and-forget via
 * {@link EconomyHop}, so no future is blocked (S-005 preserved).
 */
public final class EconomyGate {
  private EconomyGate() {}

  /** Outcome of a {@link #charge} attempt. */
  public enum Result {
    /** No charge was required, or the charge was applied successfully. */
    ALLOWED,
    /** The player could not afford the cost without dropping below the floor. */
    INSUFFICIENT_FUNDS
  }

  /** Selects which configured base price key applies to a charge. */
  public enum PriceKey {
    /** {@code EconomyKeys.price} - the payer is teleporting themselves. */
    SELF,
    /** {@code EconomyKeys.priceOther} - the payer is teleporting another player. */
    OTHER
  }

  /**
   * The immutable outcome of a {@link #charge} attempt: the affordability
   * verdict plus the computed cost (so a caller can record it on
   * {@code TeleportData.cost} for the refund-on-failure path).
   *
   * @param result the affordability verdict
   * @param cost   the computed charge; {@code 0.0} when the charge was skipped
   */
  public record Charge(Result result, double cost) {}

  /**
   * Computes and (when affordable) applies a single teleport charge.
   *
   * @param payerId      the paying account's UUID; never {@code null}
   * @param freeSubject  the actor whose {@code rtp.free} permission exempts the
   *                     charge (the sender for a self/other command charge, the
   *                     target for a target-pays charge); may be {@code null}
   * @param region       the target region (for {@code RegionKeys.price}); may be {@code null}
   * @param which        whether to use the self or other-player base price key
   * @param hasParams    whether shape/vert/world-border parameters were supplied
   * @param hasBiome     whether a biome parameter was supplied
   * @return a {@link Charge} carrying {@link Result#ALLOWED} (charge applied or
   *     not required) or {@link Result#INSUFFICIENT_FUNDS} (payer cannot afford
   *     it, nothing withdrawn) and the computed cost
   */
  public static Charge charge(
      UUID payerId,
      RTPCommandSender freeSubject,
      Region region,
      PriceKey which,
      boolean hasParams,
      boolean hasBiome) {
    RTPEconomy economy = RTP.economy;
    if (economy == null) return new Charge(Result.ALLOWED, 0.0);
    if (payerId == null || payerId.equals(CommandsAPI.serverId)) {
      return new Charge(Result.ALLOWED, 0.0);
    }
    if (freeSubject != null && freeSubject.hasPermission("rtp.free")) {
      return new Charge(Result.ALLOWED, 0.0);
    }

    @SuppressWarnings("unchecked")
    ConfigParser<EconomyKeys> eco =
        (ConfigParser<EconomyKeys>) RTP.configs.getParser(EconomyKeys.class);
    if (eco == null) return new Charge(Result.ALLOWED, 0.0);

    EconomyKeys baseKey = (which == PriceKey.OTHER) ? EconomyKeys.priceOther : EconomyKeys.price;
    double cost = eco.getNumber(baseKey, 0.0).doubleValue();
    if (hasParams) cost += eco.getNumber(EconomyKeys.paramsPrice, 0.0).doubleValue();
    if (hasBiome) cost += eco.getNumber(EconomyKeys.biomePrice, 0.0).doubleValue();
    if (region != null) cost += region.getNumber(RegionKeys.price, 0.0d).doubleValue();

    double floor = eco.getNumber(EconomyKeys.balanceFloor, 0.0d).doubleValue();
    if (economy.bal(payerId) - cost < floor) {
      return new Charge(Result.INSUFFICIENT_FUNDS, cost);
    }

    final RTPEconomy economyRef = economy;
    final UUID payer = payerId;
    final double takeCost = cost;
    EconomyHop.run(
        () -> {
          if (!economyRef.take(payer, takeCost)) {
            RTP.log(
                java.util.logging.Level.WARNING,
                "[RTP] economy.take returned false for " + payer
                    + " cost=" + takeCost + " (balance check passed earlier)");
          }
        });
    return new Charge(Result.ALLOWED, cost);
  }
}
