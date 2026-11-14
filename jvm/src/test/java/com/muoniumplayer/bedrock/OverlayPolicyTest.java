package com.muoniumplayer.bedrock;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OverlayPolicyTest {
    @Test void altTabAndMinimizeHideWithoutResettingThePlayer() {
        assertFalse(OverlayPolicy.visible(true, true, false, false));
        assertFalse(OverlayPolicy.visible(true, false, false, false));
        assertTrue(OverlayPolicy.visible(true, false, true, false));
        assertTrue(OverlayPolicy.visible(true, false, false, true));
    }
    @Test void disablingAlwaysHidesBothPanelAndStage() {
        assertFalse(OverlayPolicy.visible(false, false, true, true));
    }
}
