package io.github.dailystruggle.rtp.common.commands.menu;

import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlSection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("ConfigMenuConsumerProfile unit tests")
class ConfigMenuConsumerProfileTest {

    @Test
    void constructorAndSuggestPrefix() {
        assertThrows(NullPointerException.class, () -> new ConfigMenuConsumerProfile(null));

        ConfigMenuConsumerProfile profile = new ConfigMenuConsumerProfile(file -> null);
        assertNotNull(profile.sectionResolver());
        assertNotNull(profile.commentLookup());

        Deque<String> path = new ArrayDeque<>(List.of("rtp", "config", "default.yml"));
        String prefix = profile.suggestPrefix(path, "radius");
        assertEquals("/rtp config default.yml radius=", prefix);

        Deque<String> emptyPath = new ArrayDeque<>();
        assertEquals("/radius=", profile.suggestPrefix(emptyPath, "radius"));
    }

    @Test
    void commentLookup_variousInputs() {
        RtpYamlSection section = mock(RtpYamlSection.class);
        when(section.getComment("radius")).thenReturn("# The radius value\n# second line");
        when(section.getComment("emptyComment")).thenReturn("");
        when(section.getComment("nullComment")).thenReturn(null);
        when(section.getComment("throwing")).thenThrow(new RuntimeException("boom"));

        ConfigMenuConsumerProfile profile = new ConfigMenuConsumerProfile(file -> {
            if ("test.yml".equals(file)) return section;
            if ("error.yml".equals(file)) throw new RuntimeException("error");
            return null;
        });

        // Edge inputs
        assertFalse(profile.commentLookup().commentFor((String) null, "radius").isPresent());
        assertFalse(profile.commentLookup().commentFor("", "radius").isPresent());
        assertFalse(profile.commentLookup().commentFor("test.yml", null).isPresent());
        assertFalse(profile.commentLookup().commentFor("test.yml", "").isPresent());
        assertFalse(profile.commentLookup().commentFor("nonexistent.yml", "radius").isPresent());
        assertFalse(profile.commentLookup().commentFor("error.yml", "radius").isPresent());

        // Comment variants
        assertFalse(profile.commentLookup().commentFor("test.yml", "emptyComment").isPresent());
        assertFalse(profile.commentLookup().commentFor("test.yml", "nullComment").isPresent());
        assertFalse(profile.commentLookup().commentFor("test.yml", "throwing").isPresent());

        Optional<String> comment = profile.commentLookup().commentFor("test.yml", "radius");
        assertTrue(comment.isPresent());
        assertEquals("The radius value\nsecond line", comment.get());

        // Test stripCommentMarkers directly via commentLookup
        when(section.getComment("stripped")).thenReturn("#  line one  \n  #  line two  \nline three");
        Optional<String> strippedComment = profile.commentLookup().commentFor("test.yml", "stripped");
        assertTrue(strippedComment.isPresent());
        assertEquals(" line one  \n line two  \nline three", strippedComment.get());
    }
}
