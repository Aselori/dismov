package com.dismov.safetyrecorder;

import com.dismov.safetyrecorder.ProtectionState.Status;

import java.util.List;

/**
 * Lógica de la protección, sin dependencias de Android: qué hacer ante cada orden
 * (activar, grabar, detener) y cada evento (frase escuchada, micrófono ocupado, error).
 * {@link ProtectionService} la conecta con Vosk, MediaRecorder, la notificación y el widget.
 *
 * <p>Regla central: escuchar y grabar nunca ocurren a la vez, porque el micrófono
 * solo lo puede usar uno. Todo se llama desde un mismo hilo (el principal).
 */
public final class ProtectionController {

    static final long LISTEN_RETRY_MS = 3000;
    static final long RECORD_RETRY_MS = 400;
    static final int RECORD_ATTEMPTS = 5;

    public enum Problem {
        /** No hay frase guardada o la gramática no es válida. */
        NO_PHRASE,
        /** No se pudo cargar el modelo de voz. */
        MODEL,
        /** Se reintentó y el micrófono siguió ocupado. */
        CANNOT_RECORD,
        /** MediaRecorder falló a mitad; lo grabado se conserva. */
        RECORDING_INTERRUPTED,
        /** Android no permitió usar el micrófono al iniciar (falta permiso). */
        CANNOT_START,
        /** El sistema reinició el servicio y ya no permite usar el micrófono: hay que reactivar. */
        PROTECTION_STOPPED
    }

    /** Escucha de la frase secreta. */
    interface Ears {
        /** Abre el micrófono con esa gramática. El resultado llega por {@code callback}, quizá más tarde. */
        void open(String grammar, OpenCallback callback);

        /** Suelta el micrófono. Cancela también una apertura que todavía no terminó. */
        void close();
    }

    interface OpenCallback {
        void onOpened();

        void onMicBusy();

        void onModelError();

        void onGrammarError();
    }

    interface Recorder {
        /** @return false si no pudo empezar (por ejemplo, el micrófono sigue ocupado). */
        boolean start();

        void stop();
    }

    interface Settings {
        /** Gramática de la frase, o null si no hay frase guardada. */
        String grammar();

        List<String> phraseWords();

        void setProtectionWanted(boolean wanted);
    }

    interface Scheduler {
        void schedule(Runnable task, long delayMs);

        void cancel(Runnable task);
    }

    interface Output {
        void onStateChanged(Status status, boolean protectionOn);

        /** Empezó a grabar: el estado ya es RECORDING. */
        void onRecordingStarted(boolean protectionOn);

        void onProblem(Problem problem);

        /** Ya no hay nada que hacer: el servicio debe terminar. */
        void onShutdown();
    }

    private final Ears ears;
    private final Recorder recorder;
    private final Settings settings;
    private final Scheduler scheduler;
    private final Output output;

    private final Runnable retryListening = this::startListening;
    private final Runnable retryRecording = this::startRecording;

    private Status status = Status.OFF;
    private boolean protectionOn;
    private boolean recording;
    /** Se pidió abrir la escucha (puede estar cargando el modelo todavía). */
    private boolean earsActive;
    /** Cambia con cada apertura o cierre: descarta respuestas de aperturas viejas. */
    private int earsGeneration;
    private int recordAttempts;
    private boolean destroyed;

    ProtectionController(Ears ears, Recorder recorder, Settings settings, Scheduler scheduler, Output output) {
        this.ears = ears;
        this.recorder = recorder;
        this.settings = settings;
        this.scheduler = scheduler;
        this.output = output;
    }

    Status status() {
        return status;
    }

    boolean isProtectionOn() {
        return protectionOn;
    }

    // ---- Órdenes ----

    void enable() {
        if (settings.grammar() == null) {
            output.onProblem(Problem.NO_PHRASE);
            if (!recording) {
                shutdown();
            }
            return;
        }
        protectionOn = true;
        settings.setProtectionWanted(true);
        if (recording) {
            // Al detener la grabación volverá a escuchar
            setStatus(Status.RECORDING);
            return;
        }
        // Reinicia la escucha por si cambió la frase
        closeEars();
        startListening();
    }

    void disable() {
        protectionOn = false;
        settings.setProtectionWanted(false);
        if (recording) {
            // Una grabación en curso no se corta; al detenerla, el servicio termina
            setStatus(Status.RECORDING);
        } else {
            shutdown();
        }
    }

    void record() {
        startRecording();
    }

    void stop() {
        stopRecording();
        continueAfterRecording();
    }

    /** Android no dejó entrar en primer plano con micrófono: no hay nada que hacer. */
    void foregroundDenied(boolean restartedBySystem) {
        output.onProblem(restartedBySystem ? Problem.PROTECTION_STOPPED : Problem.CANNOT_START);
        shutdown();
    }

    /** El servicio se destruye: suelta el micrófono sin avisar más cambios. */
    void destroy() {
        destroyed = true;
        scheduler.cancel(retryListening);
        scheduler.cancel(retryRecording);
        closeEars();
        stopRecording();
    }

    // ---- Eventos ----

    void onHeard(String text, double confidence) {
        if (status != Status.LISTENING || text.isEmpty()) {
            return;
        }
        if (PhraseMatcher.triggers(text, confidence, settings.phraseWords())) {
            startRecording();
        }
    }

    void onListenError() {
        if (!earsActive) {
            return;
        }
        closeEars();
        setStatus(Status.MIC_BUSY);
        scheduler.schedule(retryListening, LISTEN_RETRY_MS);
    }

    void onRecorderError() {
        if (!recording) {
            return;
        }
        output.onProblem(Problem.RECORDING_INTERRUPTED);
        stop();
    }

    // ---- Escucha ----

    private void startListening() {
        scheduler.cancel(retryListening);
        if (!protectionOn || recording || earsActive || destroyed) {
            return;
        }
        String grammar = settings.grammar();
        if (grammar == null) {
            output.onProblem(Problem.NO_PHRASE);
            shutdown();
            return;
        }
        setStatus(Status.STARTING);
        earsActive = true;
        int generation = ++earsGeneration;
        ears.open(grammar, new OpenCallback() {
            @Override
            public void onOpened() {
                if (generation == earsGeneration) {
                    setStatus(Status.LISTENING);
                }
            }

            @Override
            public void onMicBusy() {
                if (generation != earsGeneration) {
                    return;
                }
                // Otra app (o una llamada) tiene el micrófono: se reintenta solo
                earsActive = false;
                setStatus(Status.MIC_BUSY);
                scheduler.schedule(retryListening, LISTEN_RETRY_MS);
            }

            @Override
            public void onModelError() {
                if (generation != earsGeneration) {
                    return;
                }
                earsActive = false;
                output.onProblem(Problem.MODEL);
                if (!recording) {
                    shutdown();
                }
            }

            @Override
            public void onGrammarError() {
                if (generation != earsGeneration) {
                    return;
                }
                earsActive = false;
                output.onProblem(Problem.NO_PHRASE);
                shutdown();
            }
        });
    }

    private void closeEars() {
        if (earsActive) {
            earsActive = false;
            earsGeneration++;
            ears.close();
        }
    }

    // ---- Grabación ----

    private void startRecording() {
        scheduler.cancel(retryRecording);
        scheduler.cancel(retryListening);
        if (recording || destroyed) {
            return;
        }
        closeEars(); // libera el micrófono para la grabadora
        if (!recorder.start()) {
            if (++recordAttempts < RECORD_ATTEMPTS) {
                // Justo después de soltar la escucha el micrófono puede tardar en liberarse
                setStatus(Status.STARTING);
                scheduler.schedule(retryRecording, RECORD_RETRY_MS);
                return;
            }
            recordAttempts = 0;
            output.onProblem(Problem.CANNOT_RECORD);
            continueAfterRecording();
            return;
        }
        recordAttempts = 0;
        recording = true;
        status = Status.RECORDING;
        output.onRecordingStarted(protectionOn);
    }

    private void stopRecording() {
        scheduler.cancel(retryRecording);
        recordAttempts = 0;
        if (!recording) {
            return;
        }
        recording = false;
        recorder.stop();
    }

    private void continueAfterRecording() {
        if (protectionOn) {
            startListening();
        } else {
            shutdown();
        }
    }

    // ---- Estado ----

    private void shutdown() {
        scheduler.cancel(retryListening);
        scheduler.cancel(retryRecording);
        closeEars();
        stopRecording();
        protectionOn = false;
        settings.setProtectionWanted(false);
        setStatus(Status.OFF);
        output.onShutdown();
    }

    private void setStatus(Status newStatus) {
        status = newStatus;
        if (!destroyed) {
            output.onStateChanged(newStatus, protectionOn);
        }
    }
}
