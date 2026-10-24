package io.github.dailystruggle.bit;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

class BitApiBoundaryArchTest {
    @Test
    void sharedApiDoesNotDependOnRtpOrPlatformPackages() {
        noClasses().should().dependOnClassesThat().resideInAnyPackage(
                "io.github.dailystruggle.rtp..", "org.bukkit..", "io.papermc..", "net.minecraft..", "net.fabricmc..")
                .check(new ClassFileImporter().importPackages("io.github.dailystruggle.bit"));
    }
}
