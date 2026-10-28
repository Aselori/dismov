package com.dismov.safetyrecorder;

/**
 * Reconoce el patrón "botón de encendido 3 veces seguidas". Las apps no reciben el botón
 * de encendido, pero cada pulsación enciende o apaga la pantalla, y eso sí se puede escuchar.
 *
 * <p>Se usan 3 pulsaciones porque 2 suelen abrir la cámara y 5 activan la emergencia (SOS)
 * de Android. No depende de Android para poder probarse con JUnit.
 */
final class PowerPressDetector {

    static final int PRESSES = 3;
    /** Tiempo máximo entre la primera y la última pulsación del patrón. */
    static final long WINDOW_MS = 2000;

    private final long[] times = new long[PRESSES];
    private int count;

    /**
     * Registra un cambio de pantalla (encendida o apagada).
     *
     * @param nowMs reloj monotónico en milisegundos
     * @return true si con esta pulsación se completa el patrón
     */
    boolean onPress(long nowMs) {
        // Descarta las pulsaciones que ya quedaron fuera de la ventana
        int kept = 0;
        for (int i = 0; i < count; i++) {
            if (nowMs - times[i] <= WINDOW_MS) {
                times[kept++] = times[i];
            }
        }
        count = kept;
        times[count++] = nowMs;
        if (count == PRESSES) {
            count = 0; // la siguiente pulsación empieza un patrón nuevo
            return true;
        }
        return false;
    }
}
