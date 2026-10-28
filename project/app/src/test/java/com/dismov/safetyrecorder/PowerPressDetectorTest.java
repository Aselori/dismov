package com.dismov.safetyrecorder;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PowerPressDetectorTest {

    private final PowerPressDetector detector = new PowerPressDetector();

    @Test
    public void threeQuickPressesTrigger() {
        assertFalse(detector.onPress(1000));
        assertFalse(detector.onPress(1400));
        assertTrue(detector.onPress(1800));
    }

    @Test
    public void twoPressesDoNotTrigger() {
        assertFalse(detector.onPress(1000));
        assertFalse(detector.onPress(1300));
    }

    @Test
    public void slowPressesDoNotTrigger() {
        assertFalse(detector.onPress(0));
        assertFalse(detector.onPress(1500));
        assertFalse("la primera quedó fuera de la ventana", detector.onPress(2500));
        assertTrue("pero las últimas tres sí entran", detector.onPress(3000));
    }

    @Test
    public void exactlyAtWindowEdgeStillCounts() {
        detector.onPress(0);
        detector.onPress(1000);
        assertTrue(detector.onPress(PowerPressDetector.WINDOW_MS));
    }

    @Test
    public void fourthPressStartsANewPattern() {
        detector.onPress(0);
        detector.onPress(300);
        assertTrue(detector.onPress(600));
        assertFalse("una cuarta pulsación no vuelve a activar", detector.onPress(900));
        assertFalse(detector.onPress(1200));
        assertTrue(detector.onPress(1500));
    }

    @Test
    public void screenTimeoutThenNotificationDoNotLookLikeAPattern() {
        // Apagado por inactividad y encendido por una notificación, muy separados
        assertFalse(detector.onPress(0));
        assertFalse(detector.onPress(45_000));
        assertFalse(detector.onPress(46_000));
    }
}
