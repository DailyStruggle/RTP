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
 * Shared economy charging gate for teleportation costs.
 * Computes base price, region price, and param/biome modifiers; checks balance floor;
 * and applies asynchronous withdrawal via {@link EconomyHop}.
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
   * Computes and applies a single teleport charge.
   *
   * @param payerId paying account UUID; never {@code null}
   * @param freeSubject actor whose {@code rtp.free} permission exempts charges; may be {@code null}
   * @param region target region for region price; may be {@code null}
   * @param which base price key to use (self vs. other)
   * @param hasParams whether extra params were supplied
   * @param hasBiome whether a biome was supplied
   * @return {@link Charge} result and cost
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
