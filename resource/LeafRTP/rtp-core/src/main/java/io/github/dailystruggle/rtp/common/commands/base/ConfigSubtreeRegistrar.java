package io.github.dailystruggle.rtp.common.commands.base;

import io.github.dailystruggle.rtp.common.configuration.ConfigParser;

/**
 * Capability contract for command nodes that can register child configuration parsers.
 * Allows decoupling menu redeem actions from concrete config command implementations.
 */
public interface ConfigSubtreeRegistrar {
    /**
     * Registers a child configuration parser subcommand in the tree.
     *
     * @param configParser the parser to register
     */
    void registerChildParser(ConfigParser<?> configParser);
}
