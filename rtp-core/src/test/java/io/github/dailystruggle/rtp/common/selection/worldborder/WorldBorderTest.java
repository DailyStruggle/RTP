package io.github.dailystruggle.rtp.common.selection.worldborder;

import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Circle;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link WorldBorder}.
 *
 * <p>Covers: construction, isInside predicate (true/false/null-location),
 * shape supplier override, equality, hashCode, and toString.
 */
public class WorldBorderTest {

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static final MockRTPWorld WORLD = new MockRTPWorld("border_world");

    /** Minimal stub RTPLocation — only x/z matter for boundary checks. */
    private static RTPLocation loc(double x, double z) {
        return new RTPLocation(WORLD, (int) x, 64, (int) z);
    }

    // -----------------------------------------------------------------------
    // Construction
    // -----------------------------------------------------------------------

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void constructor_storesSupplierAndPredicate() {
        Circle circle = new Circle();
        Supplier<Shape<?>> shapeSupplier = () -> circle;
        Function<RTPLocation, Boolean> inside = l -> true;

        WorldBorder wb = new WorldBorder(shapeSupplier, inside);

        assertSame(shapeSupplier, wb.getShape());
        assertSame(inside, wb.isInside());
    }

    // -----------------------------------------------------------------------
    // isInside predicate — always-true
    // -----------------------------------------------------------------------

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void isInside_alwaysTruePredicate_returnsTrue() {
        WorldBorder wb = new WorldBorder(() -> new Circle(), l -> true);
        assertTrue(wb.isInside().apply(loc(0, 0)));
        assertTrue(wb.isInside().apply(loc(100_000, 100_000)));
        assertTrue(wb.isInside().apply(loc(-50_000, -50_000)));
    }

    // -----------------------------------------------------------------------
    // isInside predicate — always-false
    // -----------------------------------------------------------------------

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void isInside_alwaysFalsePredicate_returnsFalse() {
        WorldBorder wb = new WorldBorder(() -> new Circle(), l -> false);
        assertFalse(wb.isInside().apply(loc(0, 0)));
        assertFalse(wb.isInside().apply(loc(500, 500)));
    }

    // -----------------------------------------------------------------------
    // isInside predicate — boundary calculation
    // -----------------------------------------------------------------------

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void isInside_squareBoundary_insideReturnsTrue() {
        int halfSize = 1000;
        Function<RTPLocation, Boolean> squareBorder =
                l -> Math.abs(l.x()) <= halfSize && Math.abs(l.z()) <= halfSize;

        WorldBorder wb = new WorldBorder(() -> new Square(), squareBorder);

        assertTrue(wb.isInside().apply(loc(0, 0)));
        assertTrue(wb.isInside().apply(loc(1000, 1000)));
        assertTrue(wb.isInside().apply(loc(-1000, -1000)));
    }

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void isInside_squareBoundary_outsideReturnsFalse() {
        int halfSize = 1000;
        Function<RTPLocation, Boolean> squareBorder =
                l -> Math.abs(l.x()) <= halfSize && Math.abs(l.z()) <= halfSize;

        WorldBorder wb = new WorldBorder(() -> new Square(), squareBorder);

        assertFalse(wb.isInside().apply(loc(1001, 0)));
        assertFalse(wb.isInside().apply(loc(0, -1001)));
        assertFalse(wb.isInside().apply(loc(5000, 5000)));
    }

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void isInside_circleBoundary_insideReturnsTrue() {
        double radius = 1000.0;
        Function<RTPLocation, Boolean> circleBorder =
                l -> (l.x() * (double) l.x() + l.z() * (double) l.z()) <= radius * radius;

        WorldBorder wb = new WorldBorder(() -> new Circle(), circleBorder);

        assertTrue(wb.isInside().apply(loc(0, 0)));
        assertTrue(wb.isInside().apply(loc(700, 700)));
    }

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void isInside_circleBoundary_outsideReturnsFalse() {
        double radius = 1000.0;
        Function<RTPLocation, Boolean> circleBorder =
                l -> (l.x() * (double) l.x() + l.z() * (double) l.z()) <= radius * radius;

        WorldBorder wb = new WorldBorder(() -> new Circle(), circleBorder);

        assertFalse(wb.isInside().apply(loc(1000, 1)));
        assertFalse(wb.isInside().apply(loc(5000, 0)));
    }

    // -----------------------------------------------------------------------
    // Shape supplier override
    // -----------------------------------------------------------------------

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void shapeSupplier_returnsCorrectShapeType() {
        Circle circle = new Circle();
        WorldBorder wb = new WorldBorder(() -> circle, l -> true);
        assertSame(circle, wb.getShape().get());
    }

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void shapeSupplier_canBeOverriddenWithSquare() {
        Square square = new Square();
        WorldBorder wb = new WorldBorder(() -> square, l -> true);
        assertInstanceOf(Square.class, wb.getShape().get());
    }

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void shapeSupplier_dynamicOverride_returnsLatestShape() {
        Shape<?>[] holder = { new Circle() };
        WorldBorder wb = new WorldBorder(() -> holder[0], l -> true);

        assertInstanceOf(Circle.class, wb.getShape().get());

        holder[0] = new Square();
        assertInstanceOf(Square.class, wb.getShape().get(),
                "Supplier must reflect the updated shape after override");
    }

    // -----------------------------------------------------------------------
    // Expansion / retraction logic via predicate replacement
    // -----------------------------------------------------------------------

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void expansion_largerRadiusAcceptsMoreLocations() {
        double smallRadius = 100.0;
        double largeRadius = 10_000.0;

        Function<RTPLocation, Boolean> small =
                l -> (l.x() * (double) l.x() + l.z() * (double) l.z()) <= smallRadius * smallRadius;
        Function<RTPLocation, Boolean> large =
                l -> (l.x() * (double) l.x() + l.z() * (double) l.z()) <= largeRadius * largeRadius;

        WorldBorder wbSmall = new WorldBorder(() -> new Circle(), small);
        WorldBorder wbLarge = new WorldBorder(() -> new Circle(), large);

        RTPLocation farLoc = loc(500, 500);
        assertFalse(wbSmall.isInside().apply(farLoc), "500,500 must be outside small border");
        assertTrue(wbLarge.isInside().apply(farLoc), "500,500 must be inside large border");
    }

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void retraction_smallerRadiusRejectsMoreLocations() {
        double originalRadius = 1000.0;
        double retractedRadius = 200.0;

        Function<RTPLocation, Boolean> original =
                l -> (l.x() * (double) l.x() + l.z() * (double) l.z()) <= originalRadius * originalRadius;
        Function<RTPLocation, Boolean> retracted =
                l -> (l.x() * (double) l.x() + l.z() * (double) l.z()) <= retractedRadius * retractedRadius;

        WorldBorder wbOriginal = new WorldBorder(() -> new Circle(), original);
        WorldBorder wbRetracted = new WorldBorder(() -> new Circle(), retracted);

        RTPLocation midLoc = loc(500, 0);
        assertTrue(wbOriginal.isInside().apply(midLoc), "500,0 must be inside original border");
        assertFalse(wbRetracted.isInside().apply(midLoc), "500,0 must be outside retracted border");
    }

    // -----------------------------------------------------------------------
    // Equality
    // -----------------------------------------------------------------------

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void equals_sameInstance_returnsTrue() {
        Supplier<Shape<?>> s = () -> new Circle();
        Function<RTPLocation, Boolean> f = l -> true;
        WorldBorder wb = new WorldBorder(s, f);
        assertEquals(wb, wb);
    }

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void equals_sameSupplierAndPredicate_returnsTrue() {
        Supplier<Shape<?>> s = () -> new Circle();
        Function<RTPLocation, Boolean> f = l -> true;
        WorldBorder wb1 = new WorldBorder(s, f);
        WorldBorder wb2 = new WorldBorder(s, f);
        assertEquals(wb1, wb2);
    }

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void equals_differentPredicate_returnsFalse() {
        Supplier<Shape<?>> s = () -> new Circle();
        WorldBorder wb1 = new WorldBorder(s, l -> true);
        WorldBorder wb2 = new WorldBorder(s, l -> false);
        assertNotEquals(wb1, wb2);
    }

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void equals_null_returnsFalse() {
        WorldBorder wb = new WorldBorder(() -> new Circle(), l -> true);
        assertNotEquals(null, wb);
    }

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void equals_differentType_returnsFalse() {
        WorldBorder wb = new WorldBorder(() -> new Circle(), l -> true);
        assertNotEquals("not a WorldBorder", wb);
    }

    // -----------------------------------------------------------------------
    // hashCode
    // -----------------------------------------------------------------------

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void hashCode_equalObjects_sameHash() {
        Supplier<Shape<?>> s = () -> new Circle();
        Function<RTPLocation, Boolean> f = l -> true;
        WorldBorder wb1 = new WorldBorder(s, f);
        WorldBorder wb2 = new WorldBorder(s, f);
        assertEquals(wb1.hashCode(), wb2.hashCode());
    }

    // -----------------------------------------------------------------------
    // toString
    // -----------------------------------------------------------------------

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void toString_containsWorldBorderLabel() {
        WorldBorder wb = new WorldBorder(() -> new Circle(), l -> true);
        String str = wb.toString();
        assertNotNull(str);
        assertTrue(str.contains("WorldBorder"), "toString must contain 'WorldBorder'");
    }

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void toString_containsGetShapeAndIsInsideFields() {
        WorldBorder wb = new WorldBorder(() -> new Circle(), l -> true);
        String str = wb.toString();
        assertTrue(str.contains("getShape"), "toString must mention getShape field");
        assertTrue(str.contains("isInside"), "toString must mention isInside field");
    }
}
