package io.github.dailystruggle.rtp.common.bit.config;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Architectural boundary verification for {@code config-bit-api}: the module is layered solely on
 * {@code yaml-api}, {@code bit-api}, and {@code units-api} and must not import {@code rtp-api},
 * {@code rtp-core}, or platform packages.
 */
@DisplayName("config-bit-api: Module Boundary Architecture")
class ConfigBitApiBoundaryArchTest {
    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.github.dailystruggle.rtp.common.bit.config");
    }

    @Test
    @DisplayName("config-bit-api classes do not depend on rtp-api, rtp-core, or platform packages")
    void noDependencyOnRtpCoreOrPlatformPackages() {
        noClasses()
                .that().resideInAPackage("io.github.dailystruggle.rtp.common.bit.config..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "io.github.dailystruggle.rtp.api..",
                        "io.github.dailystruggle.rtp.common.action..",
                        "io.github.dailystruggle.rtp.common.commands..",
                        "io.github.dailystruggle.rtp.common.database..",
                        "io.github.dailystruggle.rtp.common.factory..",
                        "io.github.dailystruggle.rtp.common.network..",
                        "io.github.dailystruggle.rtp.common.serverSide..",
                        "io.github.dailystruggle.rtp.common.tasks..",
                        "io.github.dailystruggle.rtp.bukkit..",
                        "io.github.dailystruggle.rtp.paper..",
                        "io.github.dailystruggle.rtp.folia..",
                        "io.github.dailystruggle.rtp.fabric..",
                        "io.github.dailystruggle.rtp.neoforge..",
                        "org.bukkit..",
                        "net.minecraft..")
                .check(classes);
    }
}
