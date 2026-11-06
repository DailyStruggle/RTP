package io.github.dailystruggle.yaml;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Runtime parser for {@code @...} directive lines in YAML block comments ({@code :yaml-api}).
 *
 * @governedBy ADR-064, ADR-115, yaml-api-ADR-003
 */
public final class ConfigDirectives {

    private final io.github.dailystruggle.rtp.common.configuration.ConfigDirectives delegate;

    private ConfigDirectives(io.github.dailystruggle.rtp.common.configuration.ConfigDirectives delegate) {
        this.delegate = delegate;
    }

    public static ConfigDirectives parse(String commentBlock) {
        return new ConfigDirectives(
                io.github.dailystruggle.rtp.common.configuration.ConfigDirectives.parse(commentBlock));
    }

    public static ConfigDirectives parseLines(List<String> commentLines) {
        return new ConfigDirectives(
                io.github.dailystruggle.rtp.common.configuration.ConfigDirectives.parseLines(commentLines));
    }

    public String type() { return delegate.type(); }
    public List<String> options() { return delegate.options(); }
    public String source() { return delegate.source(); }
    public String unit() { return delegate.unit(); }
    public Double rangeMin() { return delegate.rangeMin(); }
    public Double rangeMax() { return delegate.rangeMax(); }
    public boolean rangeMalformed() { return delegate.rangeMalformed(); }
    public String pattern() { return delegate.pattern(); }
    public Pattern compiledPattern() { return delegate.compiledPattern(); }
    public boolean patternMalformed() { return delegate.patternMalformed(); }
    public String defaultValue() { return delegate.defaultValue(); }
    public boolean hasFiniteDomain() { return delegate.hasFiniteDomain(); }
    public boolean hasRange() { return delegate.hasRange(); }
    public boolean hasPattern() { return delegate.hasPattern(); }
}
