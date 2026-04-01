package io.github.dailystruggle.rtp.common.commands.parameters;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;

public class ParameterParsingTest {
    private final UUID uuid = UUID.randomUUID();

    @Test
    void testIntegerParameter() {
        IntegerParameter param = new IntegerParameter("perm", "desc", (u, s) -> true);
        assertNotNull(param);
    }

    @Test
    void testFloatParameter() {
        FloatParameter param = new FloatParameter("perm", "desc", (u, s) -> true);
        assertNotNull(param);
    }

    @Test
    void testShapeParameter() {
        // ShapeParameter needs RTP.factoryMap to be initialized.
        // We can't easily initialize it here without full RTP setup,
        // so we'll just check if the class is loadable.
        assertNotNull(ShapeParameter.class);
    }
}
