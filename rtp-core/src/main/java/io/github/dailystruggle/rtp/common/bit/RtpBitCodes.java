package io.github.dailystruggle.rtp.common.bit;

import io.github.dailystruggle.bit.BitCodeNamespace;
import io.github.dailystruggle.bit.BitCodeRegistry;
import io.github.dailystruggle.rtp.common.action.ActionCbitCounters;
import io.github.dailystruggle.rtp.common.action.ActionDefinitionValidator;
import io.github.dailystruggle.rtp.common.bit.config.ConfigPbit;
import io.github.dailystruggle.rtp.common.bit.config.DirectiveConfigCheck;
import io.github.dailystruggle.rtp.common.bit.config.EffectsGroupValidator;
import io.github.dailystruggle.rtp.common.commands.test.TestAccessorCmd;
import io.github.dailystruggle.rtp.common.commands.test.TestApiCompatCmd;
import io.github.dailystruggle.rtp.common.commands.test.TestChunkTicketCmd;
import io.github.dailystruggle.rtp.common.commands.test.TestEventsCmd;
import io.github.dailystruggle.rtp.common.commands.test.TestSchedulerCmd;
import io.github.dailystruggle.rtp.common.commands.test.TestTeleportCmd;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * Process-wide BIT code catalog for RTP and its addons (ADR-121).
 *
 * <p>Namespaces are reserved here; codes are registered by the class whose check emits them, so
 * adding a check never edits a central list. Addons reserve their own prefix and id range from
 * {@link #REGISTRY} (10000 and up, see the ADR-121 namespace table).
 */
public final class RtpBitCodes {
    public static final BitCodeRegistry REGISTRY = new BitCodeRegistry();

    /** Every core class whose static fields register codes in {@link #REGISTRY}. */
    public static final List<Class<?>> OWNERS = List.of(
            ActionDefinitionValidator.class,
            ActionCbitCounters.class,
            io.github.dailystruggle.rtp.common.action.ActionEngineSelfTest.class,
            ConfigPbit.class,
            DirectiveConfigCheck.class,
            // config owners: directives
            io.github.dailystruggle.rtp.common.bit.config.ConfigReferenceCheck.class,
            io.github.dailystruggle.rtp.common.selection.region.RegionConfigConstraints.class,
            io.github.dailystruggle.rtp.common.action.ActionConfigConstraints.class,
            // config owners: references
            io.github.dailystruggle.rtp.common.bit.config.MessagesConfigCheck.class, // config owners: messages
            io.github.dailystruggle.rtp.common.bit.config.NetworkConfigCheck.class, // config owners: network
            EffectsGroupValidator.class, // config owners: effects
            TestAccessorCmd.class,
            TestApiCompatCmd.class,
            TestChunkTicketCmd.class,
            TestEventsCmd.class,
            TestSchedulerCmd.class,
            TestTeleportCmd.class);

    /**
     * General configuration PBIT (ADR-121 section 6). 100-id blocks: 1000 snapshot/YAML, 1100
     * ADR-064 directives, 1200 ADR-073 references, 1300 cross-file references, 1400 semantic
     * constraints, 1500 messages, 1600 effects, 1700 network, 1800 addon configs, 1900 reload gate.
     */
    public static final BitCodeNamespace RTP = REGISTRY.reserve("RTP", 1000, 1999);

    /** Action-engine and scoreboard checks. */
    public static final BitCodeNamespace ACTION = REGISTRY.reserve("ACTION", 2000, 2999);

    /** On-request test probes. */
    public static final BitCodeNamespace TEST = REGISTRY.reserve("TEST", 3000, 4099);

    /** Runtime diagnostic probes. */
    public static final BitCodeNamespace PROBE = REGISTRY.reserve("PROBE", 4100, 4699);

    /** Initiated mock teleport heatmap diagnostics. */
    public static final BitCodeNamespace HEATMAP = REGISTRY.reserve("HEATMAP", 4700, 4799);

    /** Initialises every core owner and any additional plugin owners against the process registry. */
    public static BitCodeRegistry initialiseOwners(Collection<Class<?>> additionalOwners) {
        Objects.requireNonNull(additionalOwners, "additionalOwners must not be null");
        LinkedHashSet<Class<?>> owners = new LinkedHashSet<>(OWNERS);
        owners.addAll(additionalOwners);
        for (Class<?> owner : owners) {
            Objects.requireNonNull(owner, "owner must not be null");
            try {
                Class.forName(owner.getName(), true, owner.getClassLoader());
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("BIT code owner could not be initialised: " + owner.getName(), e);
            }
        }
        return REGISTRY;
    }

    public static BitCodeRegistry initialiseOwners() {
        return initialiseOwners(List.of());
    }

    private RtpBitCodes() {}
}
