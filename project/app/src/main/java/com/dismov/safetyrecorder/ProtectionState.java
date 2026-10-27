package com.dismov.safetyrecorder;

import android.content.Context;
import android.os.Looper;

import java.io.File;

import java.util.ArrayList;
import java.util.List;

/**
 * Estado actual del servicio, compartido con la actividad y el widget.
 * Vive en memoria a propósito: si el proceso muere, vuelve a OFF, que es la verdad.
 * Solo se modifica desde el hilo principal.
 */
public final class ProtectionState {

    public enum Status {
        /** Ni escucha ni graba. */
        OFF,
        /** Cargando el modelo de voz o abriendo el micrófono. */
        STARTING,
        /** Esperando la frase secreta. */
        LISTENING,
        /** Otra app usa el micrófono; se reintenta solo. */
        MIC_BUSY,
        RECORDING
    }

    public interface Listener {
        void onStateChanged();
    }

    private static Status status = Status.OFF;
    private static boolean protectionOn;
    private static long recordingStartElapsed;
    private static long recordingStartWall;
    private static volatile File recordingFile;
    private static final List<Listener> listeners = new ArrayList<>();

    private ProtectionState() {
    }

    public static Status status() {
        return status;
    }

    /** La protección sigue activa durante una grabación iniciada por la frase. */
    public static boolean isProtectionOn() {
        return protectionOn;
    }

    /** Base para Chronometer (SystemClock.elapsedRealtime). */
    public static long recordingStartElapsed() {
        return recordingStartElapsed;
    }

    /** Base para el cronómetro de la notificación (System.currentTimeMillis). */
    public static long recordingStartWall() {
        return recordingStartWall;
    }

    /** Archivo que se está grabando, o null. */
    public static File recordingFile() {
        return recordingFile;
    }

    static void update(Context context, Status newStatus, boolean newProtectionOn) {
        checkMainThread();
        if (newStatus != Status.RECORDING) {
            recordingFile = null;
        }
        status = newStatus;
        protectionOn = newProtectionOn;
        notifyChanged(context);
    }

    static void startRecording(Context context, File file, long startElapsed, long startWall,
                               boolean newProtectionOn) {
        checkMainThread();
        recordingFile = file;
        recordingStartElapsed = startElapsed;
        recordingStartWall = startWall;
        update(context, Status.RECORDING, newProtectionOn);
    }

    private static void notifyChanged(Context context) {
        for (Listener l : new ArrayList<>(listeners)) {
            l.onStateChanged();
        }
        RecorderWidget.refreshAll(context);
    }

    public static void addListener(Listener listener) {
        checkMainThread();
        listeners.add(listener);
    }

    public static void removeListener(Listener listener) {
        checkMainThread();
        listeners.remove(listener);
    }

    private static void checkMainThread() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("ProtectionState solo se usa desde el hilo principal");
        }
    }
}
