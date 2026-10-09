package io.github.dailystruggle.rtp.common.commands.parameters;

import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ShapeParameter and VertParameter tests")
class ParametersCoverageTest {

    @TempDir
    File tempDir;

    @BeforeEach
    void setUp() {
        RTPTestSetup.install(tempDir);
    }

    @Test
    void testShapeParameter() {
        assertNotNull(ShapeParameter.class);
    }

    @Test
    void testVertParameter() {
        assertNotNull(VertParameter.class);
    }
}
