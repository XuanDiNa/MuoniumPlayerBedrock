package com.muoniumplayer.bedrock;

import org.junit.jupiter.api.Test;
import org.joml.Vector3f;
import static org.junit.jupiter.api.Assertions.*;

class StageProjectionTest {
    @Test void centerAtPositiveDepthIsViewportCenter() {
        var m = StageProjection.projection(1280, 720, 70);
        var p = m.transformProject(new Vector3f(0, 0, 10));
        assertEquals(640, p.x, .001); assertEquals(360, p.y, .001);
    }
    @Test void distanceHalvesSizeAndPreservesFacing() {
        var near = StageProjection.billboard(1280, 720, 70, 0, 0, 5, 0, 0, .04f);
        var far = StageProjection.billboard(1280, 720, 70, 0, 0, 10, 0, 0, .04f);
        float nearX = near.transformProject(new Vector3f(20, 0, 0)).x;
        float farX = far.transformProject(new Vector3f(20, 0, 0)).x;
        assertTrue(nearX > 640); assertEquals((nearX - 640) / 2, farX - 640, .001);
    }
    @Test void angledPlaneHasDifferentHomogeneousDepthAcrossGlyphs() {
        var m = StageProjection.billboard(1280, 720, 70, 0, 0, 10, 35, 0, .04f);
        var a = m.transform(new org.joml.Vector4f(-30, 0, 0, 1));
        var b = m.transform(new org.joml.Vector4f(30, 0, 0, 1));
        assertNotEquals(a.w, b.w); // perspective, not a 2D skew
        assertTrue(a.w > 0 && b.w > 0);
    }
    @Test void rejectsInvalidFramesBeforeDrawing() {
        assertThrows(IllegalArgumentException.class, () -> StageProjection.projection(0, 720, 70));
        assertThrows(IllegalArgumentException.class, () -> StageProjection.projection(1280, 720, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> StageProjection.billboard(1280, 720, 70, 0, 0, -1, 0, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> StageProjection.billboard(1280, 720, 70, 1e300, 0, 10, 0, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> StageProjection.billboard(1280, 720, 70, 0, 0, 10, Double.MAX_VALUE, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> StageProjection.billboard(1280, 720, 70, 0, 0, 10, 0, 0, Float.MAX_VALUE));
    }
}
