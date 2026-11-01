package io.github.dailystruggle.rtp.common.commands.docs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("DocsLoweringOptions tests")
class DocsLoweringOptionsTest {

    @Test
    void testDefaults() {
        DocsLoweringOptions options = DocsLoweringOptions.defaults();
        assertEquals(56, options.maxLineWidth());
        assertEquals(48, options.maxCodeLineWidth());
        assertFalse(options.exposeDeveloperDocs());
        assertEquals(262144L, options.maxFileBytes());
    }

    @Test
    void testCustomValid() {
        DocsLoweringOptions options = new DocsLoweringOptions(20, 25, true, 2048L);
        assertEquals(20, options.maxLineWidth());
        assertEquals(25, options.maxCodeLineWidth());
        assertTrue(options.exposeDeveloperDocs());
        assertEquals(2048L, options.maxFileBytes());
    }

    @Test
    void testValidation() {
        assertThrows(IllegalArgumentException.class, () -> new DocsLoweringOptions(15, 20, false, 2048L));
        assertThrows(IllegalArgumentException.class, () -> new DocsLoweringOptions(20, 15, false, 2048L));
        assertThrows(IllegalArgumentException.class, () -> new DocsLoweringOptions(20, 20, false, 1023L));
    }
}
