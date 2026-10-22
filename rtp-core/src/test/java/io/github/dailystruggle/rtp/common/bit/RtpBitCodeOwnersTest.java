package io.github.dailystruggle.rtp.common.bit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.bit.BitCode;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("REQ-API-F-009 BIT code catalog owner completeness")
class RtpBitCodeOwnersTest {
    @Test
    void everyCoreStaticBitCodeFieldHasAnExplicitOwnerAndCatalogEntry() throws Exception {
        Path classes = repositoryRoot().resolve("rtp-core/build/classes/java/main");
        List<Class<?>> discovered = RtpBitCodeCatalog.declaredOwners(classes, getClass().getClassLoader());
        assertEquals(RtpBitCodes.OWNERS.stream().sorted(Comparator.comparing(Class::getName)).toList(), discovered,
                "RtpBitCodes.OWNERS must list exactly the core classes declaring static BitCode fields");

        var registry = RtpBitCodes.initialiseOwners();
        for (Class<?> owner : discovered) {
            for (Field field : owner.getDeclaredFields()) {
                if (field.getType() != BitCode.class || !Modifier.isStatic(field.getModifiers())) continue;
                BitCode code = (BitCode) field.get(null);
                assertSame(code, registry.byId(code.id()).orElse(null), owner.getSimpleName() + "." + field.getName());
                assertSame(code, registry.byKey(code.key()).orElse(null), owner.getSimpleName() + "." + field.getName());
            }
        }

        String json = RtpBitCodeCatalog.json(registry);
        int previousIndex = -1;
        for (BitCode code : registry.codes()) {
            int currentIndex = json.indexOf("\"key\": \"" + code.key() + "\"");
            assertTrue(currentIndex > previousIndex, code.key() + " must appear once in id order");
            previousIndex = currentIndex;
        }
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.isRegularFile(current.resolve("settings.gradle"))) {
            current = current.getParent();
        }
        if (current == null) throw new IllegalStateException("could not locate repository root");
        return current;
    }
}
