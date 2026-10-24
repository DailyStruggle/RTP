package io.github.dailystruggle.rtp.common.bit;

import io.github.dailystruggle.bit.BitCodeNamespace;
import io.github.dailystruggle.bit.BitComponent;
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
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-wide BIT code catalog for RTP and its addons (ADR-121).
 *
 * <p>Namespaces are reserved here; codes are registered by the class whose check emits them, so
 * adding a check never edits a central list. Each namespace owns its own numeric ids.
 */
public final class RtpBitCodes {
    /** Owner recorded for the core namespaces; another owner reserving one is a startup finding. */
    public static final String OWNER = "rtp-core";
    public static final BitCodeRegistry REGISTRY = new BitCodeRegistry();
    private static final Set<Class<?>> ADDITIONAL_OWNERS = ConcurrentHashMap.newKeySet();

    /** Every core class that declares BIT codes or components in {@link #REGISTRY}. */
    public static final List<Class<?>> OWNERS = List.of(
            ActionDefinitionValidator.class,
            ActionCbitCounters.class,
            io.github.dailystruggle.rtp.common.action.ActionEngineSelfTest.class,
            ConfigPbit.class,
            DirectiveConfigCheck.class,
            GovernorBitComponent.class,
            PipelineCbitCounters.class,
            TestBitCodes.class,
            PlatformHookBitComponent.class,
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

    /** General configuration PBIT (ADR-121 section 6); existing ids are retained. */
    public static final BitCodeNamespace RTP = REGISTRY.reserve("RTP", OWNER);

    /** Action-engine and scoreboard checks. */
    public static final BitCodeNamespace ACTION = REGISTRY.reserve("ACTION", OWNER);

    /** On-request test probes. */
    public static final BitCodeNamespace TEST = REGISTRY.reserve("TEST", OWNER);

    /** Runtime diagnostic probes. */
    public static final BitCodeNamespace PROBE = REGISTRY.reserve("PROBE", OWNER);

    /** Initiated mock teleport heatmap diagnostics. */
    public static final BitCodeNamespace HEATMAP = REGISTRY.reserve("HEATMAP", OWNER);

    /** Adaptive compute governor CBIT diagnostics (ADR-087 section 6). */
    public static final BitCodeNamespace GOVERNOR = REGISTRY.reserve("GOVERNOR", OWNER);

    /** Initialises every core owner and any additional plugin owners against the process registry. */
    public static BitCodeRegistry initialiseOwners(Collection<Class<?>> additionalOwners) {
        Objects.requireNonNull(additionalOwners, "additionalOwners must not be null");
        return initialise(additionalOwners);
    }

    /** Adds process-wide owners for later bootstraps, such as plugin-side codes before config load. */
    public static void addOwners(Collection<Class<?>> owners) {
        Objects.requireNonNull(owners, "owners must not be null");
        for (Class<?> owner : owners) ADDITIONAL_OWNERS.add(Objects.requireNonNull(owner, "owner must not be null"));
    }

    /** Initialises all known owners and registers their declarative public components. */
    public static BitCodeRegistry initialiseOwners() {
        return initialise(List.of());
    }

    private static BitCodeRegistry initialise(Collection<Class<?>> requestedOwners) {
        LinkedHashSet<Class<?>> owners = new LinkedHashSet<>(OWNERS);
        owners.addAll(ADDITIONAL_OWNERS);
        owners.addAll(requestedOwners);
        for (Class<?> owner : owners) {
            Objects.requireNonNull(owner, "owner must not be null");
            try {
                Class.forName(owner.getName(), true, owner.getClassLoader());
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("BIT code owner could not be initialised: " + owner.getName(), e);
            }
            registerComponents(owner);
        }
        return REGISTRY;
    }

    private static void registerComponents(Class<?> owner) {
        for (Field field : owner.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (field.getType() != BitComponent.class || !Modifier.isPublic(modifiers)
                    || !Modifier.isStatic(modifiers) || !Modifier.isFinal(modifiers)) continue;
            try {
                BitComponent component = (BitComponent) field.get(null);
                if (component == null) {
                    throw new IllegalStateException("BIT component field is null: " + owner.getName() + "." + field.getName());
                }
                RtpBit.RUNNER.register(component);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("BIT component field is inaccessible: " + owner.getName() + "."
                        + field.getName(), e);
            }
        }
    }

    private RtpBitCodes() {}
}
