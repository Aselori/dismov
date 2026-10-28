package com.dismov.safetyrecorder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.dismov.safetyrecorder.ProtectionController.Problem;
import com.dismov.safetyrecorder.ProtectionState.Status;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ProtectionControllerTest {

    private static final String PHRASE = "totalmente inédito";
    private static final List<String> WORDS = Arrays.asList("totalmente", "inedito");

    private FakeEars ears;
    private FakeRecorder recorder;
    private FakeSettings settings;
    private FakeScheduler scheduler;
    private FakeOutput output;
    private ProtectionController controller;

    @Before
    public void setUp() {
        ears = new FakeEars();
        recorder = new FakeRecorder();
        settings = new FakeSettings();
        scheduler = new FakeScheduler();
        output = new FakeOutput();
        controller = new ProtectionController(ears, recorder, settings, scheduler, output);
    }

    /** Activa y deja escuchando (el modelo responde al momento). */
    private void listening() {
        controller.enable();
        ears.completeOpen();
        assertEquals(Status.LISTENING, controller.status());
    }

    // ---- Activar y desactivar ----

    @Test
    public void enableLoadsThenListens() {
        controller.enable();
        assertEquals(Status.STARTING, controller.status());
        assertTrue(settings.protectionWanted);
        assertEquals(PHRASE, ears.openedWith);

        ears.completeOpen();
        assertEquals(Status.LISTENING, controller.status());
        assertTrue(controller.isProtectionOn());
    }

    @Test
    public void enableWithoutPhraseReportsAndShutsDown() {
        settings.grammar = null;
        controller.enable();
        assertEquals(Arrays.asList(Problem.NO_PHRASE), output.problems);
        assertTrue(output.shutdown);
        assertEquals(Status.OFF, controller.status());
        assertFalse(ears.isOpen());
    }

    @Test
    public void disableWhileListeningReleasesMicAndShutsDown() {
        listening();
        controller.disable();
        assertFalse(ears.isOpen());
        assertFalse(settings.protectionWanted);
        assertEquals(Status.OFF, controller.status());
        assertTrue(output.shutdown);
    }

    @Test
    public void enableAgainRestartsListeningWithNewPhrase() {
        listening();
        settings.grammar = "otra frase";
        controller.enable();
        assertEquals(1, ears.closeCount);
        assertEquals("otra frase", ears.openedWith);
    }

    @Test
    public void lateModelLoadAfterDisableDoesNotListen() {
        controller.enable();                                   // el modelo sigue cargando
        ProtectionController.OpenCallback late = ears.pending;
        controller.disable();
        late.onOpened();                                       // la carga termina tarde
        assertEquals(Status.OFF, controller.status());
        assertEquals(Status.OFF, output.statuses.get(output.statuses.size() - 1));
    }

    @Test
    public void lateAnswerFromOldOpenIsIgnoredAfterRestart() {
        controller.enable();
        ProtectionController.OpenCallback old = ears.pending;
        controller.enable();                                   // frase nueva: reabre
        old.onMicBusy();                                       // respuesta de la apertura vieja
        assertEquals(Status.STARTING, controller.status());
        scheduler.advance(ProtectionController.LISTEN_RETRY_MS * 2);
        ears.completeOpen();
        assertEquals(Status.LISTENING, controller.status());
    }

    // ---- Frase escuchada ----

    @Test
    public void phraseStartsRecordingAfterReleasingMic() {
        listening();
        controller.onHeard(PHRASE, 0.9);
        assertEquals(Status.RECORDING, controller.status());
        assertFalse("la escucha debe soltar el micrófono antes de grabar", ears.isOpen());
        assertTrue(recorder.recording);
        assertTrue(recorder.startedAfterEarsClosed);
        assertEquals(1, output.recordingsStarted);
    }

    @Test
    public void phraseInsideLongerSpeechStillTriggers() {
        listening();
        controller.onHeard("[unk] totalmente inedito [unk]", 0.8);
        assertTrue(recorder.recording);
    }

    @Test
    public void otherSpeechOrLowConfidenceDoesNotTrigger() {
        listening();
        controller.onHeard("totalmente", 0.95);
        controller.onHeard("inédito totalmente", 0.95);
        controller.onHeard(PHRASE, PhraseMatcher.MIN_CONFIDENCE - 0.01);
        controller.onHeard("", 1.0);
        assertFalse(recorder.recording);
        assertEquals(Status.LISTENING, controller.status());
    }

    @Test
    public void phraseIgnoredWhenNotListening() {
        controller.enable(); // todavía STARTING
        controller.onHeard(PHRASE, 1.0);
        assertFalse(recorder.recording);
    }

    // ---- Grabar y detener ----

    @Test
    public void stopWithProtectionGoesBackToListening() {
        listening();
        controller.onHeard(PHRASE, 0.9);
        controller.stop();
        assertFalse(recorder.recording);
        assertEquals(Status.STARTING, controller.status());
        ears.completeOpen();
        assertEquals(Status.LISTENING, controller.status());
        assertFalse(output.shutdown);
    }

    @Test
    public void manualRecordWithoutProtectionShutsDownOnStop() {
        controller.record();
        assertEquals(Status.RECORDING, controller.status());
        assertFalse(controller.isProtectionOn());
        assertFalse(ears.isOpen());

        controller.stop();
        assertFalse(recorder.recording);
        assertEquals(Status.OFF, controller.status());
        assertTrue(output.shutdown);
    }

    @Test
    public void recordWhileRecordingIsIgnored() {
        controller.record();
        controller.record();
        assertEquals(1, recorder.startCount);
    }

    @Test
    public void disableWhileRecordingKeepsRecordingThenShutsDown() {
        listening();
        controller.onHeard(PHRASE, 0.9);
        controller.disable();
        assertTrue("desactivar no corta una grabación en curso", recorder.recording);
        assertEquals(Status.RECORDING, controller.status());

        controller.stop();
        assertEquals(Status.OFF, controller.status());
        assertTrue(output.shutdown);
        assertFalse(ears.isOpen());
    }

    @Test
    public void enableWhileManualRecordingListensAfterStop() {
        controller.record();
        controller.enable();
        assertTrue(recorder.recording);
        assertFalse(ears.isOpen());

        controller.stop();
        ears.completeOpen();
        assertEquals(Status.LISTENING, controller.status());
    }

    @Test
    public void staleStopWithNothingRunningShutsDown() {
        controller.stop();
        assertTrue(output.shutdown);
        assertEquals(0, recorder.stopCount);
    }

    // ---- Fallas ----

    @Test
    public void recorderRetriesWhileMicIsStillBusy() {
        listening();
        recorder.failuresLeft = 2;
        controller.onHeard(PHRASE, 0.9);
        assertFalse(recorder.recording);
        assertEquals(Status.STARTING, controller.status());

        scheduler.advance(ProtectionController.RECORD_RETRY_MS);
        assertFalse(recorder.recording);
        scheduler.advance(ProtectionController.RECORD_RETRY_MS);
        assertTrue(recorder.recording);
        assertEquals(Status.RECORDING, controller.status());
        assertTrue(output.problems.isEmpty());
    }

    @Test
    public void recorderGivesUpAfterAllAttemptsAndListensAgain() {
        listening();
        recorder.failuresLeft = ProtectionController.RECORD_ATTEMPTS;
        controller.onHeard(PHRASE, 0.9);
        scheduler.advance(ProtectionController.RECORD_RETRY_MS * ProtectionController.RECORD_ATTEMPTS);
        assertEquals(ProtectionController.RECORD_ATTEMPTS, recorder.startCount);
        assertEquals(Arrays.asList(Problem.CANNOT_RECORD), output.problems);
        ears.completeOpen();
        assertEquals(Status.LISTENING, controller.status());
    }

    @Test
    public void stopDuringRecordRetryCancelsIt() {
        controller.record();
        recorder.reset();
        controller.stop();

        recorder.failuresLeft = 1;
        controller.record();
        controller.stop();
        scheduler.advance(10_000);
        assertEquals("el reintento cancelado no debe grabar", 1, recorder.startCount);
        assertFalse(recorder.recording);
    }

    @Test
    public void micBusyRetriesListening() {
        controller.enable();
        ears.failOpenMicBusy();
        assertEquals(Status.MIC_BUSY, controller.status());

        scheduler.advance(ProtectionController.LISTEN_RETRY_MS);
        assertEquals(Status.STARTING, controller.status());
        ears.completeOpen();
        assertEquals(Status.LISTENING, controller.status());
    }

    @Test
    public void listenErrorRetriesListening() {
        listening();
        controller.onListenError();
        assertEquals(Status.MIC_BUSY, controller.status());
        assertFalse(ears.isOpen());
        scheduler.advance(ProtectionController.LISTEN_RETRY_MS);
        ears.completeOpen();
        assertEquals(Status.LISTENING, controller.status());
    }

    @Test
    public void manualRecordDuringMicBusyCancelsListenRetry() {
        controller.enable();
        ears.failOpenMicBusy();
        controller.record();
        assertTrue(recorder.recording);
        scheduler.advance(ProtectionController.LISTEN_RETRY_MS * 2);
        assertFalse("no debe abrir la escucha mientras graba", ears.isOpen());
    }

    @Test
    public void modelErrorReportsAndShutsDown() {
        controller.enable();
        ears.failOpenModel();
        assertEquals(Arrays.asList(Problem.MODEL), output.problems);
        assertTrue(output.shutdown);
    }

    @Test
    public void grammarErrorReportsAndShutsDown() {
        controller.enable();
        ears.failOpenGrammar();
        assertEquals(Arrays.asList(Problem.NO_PHRASE), output.problems);
        assertTrue(output.shutdown);
    }

    @Test
    public void recorderErrorKeepsFileAndListensAgain() {
        listening();
        controller.onHeard(PHRASE, 0.9);
        controller.onRecorderError();
        assertEquals(Arrays.asList(Problem.RECORDING_INTERRUPTED), output.problems);
        assertEquals(1, recorder.stopCount);
        ears.completeOpen();
        assertEquals(Status.LISTENING, controller.status());
    }

    @Test
    public void recorderErrorWhenNotRecordingIsIgnored() {
        listening();
        controller.onRecorderError();
        assertTrue(output.problems.isEmpty());
        assertEquals(Status.LISTENING, controller.status());
    }

    @Test
    public void foregroundDeniedReportsTheRightProblem() {
        controller.foregroundDenied(false);
        assertEquals(Arrays.asList(Problem.CANNOT_START), output.problems);
        assertTrue(output.shutdown);

        setUp();
        controller.foregroundDenied(true);
        assertEquals("tras un reinicio del sistema, el aviso permite reactivar",
                Arrays.asList(Problem.PROTECTION_STOPPED), output.problems);
    }

    @Test
    public void destroyReleasesEverythingSilently() {
        listening();
        controller.onHeard(PHRASE, 0.9);
        int changesBefore = output.statuses.size();
        controller.destroy();
        assertFalse(recorder.recording);
        assertFalse(ears.isOpen());
        assertEquals(changesBefore, output.statuses.size());

        controller.record();
        controller.enable();
        assertEquals("después de destruir no se abre nada", 1, recorder.startCount);
        assertFalse(ears.isOpen());
    }

    // ---- Dobles de prueba ----

    private final class FakeEars implements ProtectionController.Ears {
        String openedWith;
        int closeCount;
        ProtectionController.OpenCallback pending;
        private boolean open;

        @Override
        public void open(String grammar, ProtectionController.OpenCallback callback) {
            openedWith = grammar;
            pending = callback;
        }

        @Override
        public void close() {
            closeCount++;
            open = false;
            pending = null;
        }

        boolean isOpen() {
            return open || pending != null;
        }

        void completeOpen() {
            ProtectionController.OpenCallback cb = pending;
            pending = null;
            if (cb == null) {
                return;
            }
            open = true;
            cb.onOpened();
        }

        void failOpenMicBusy() {
            ProtectionController.OpenCallback cb = pending;
            pending = null;
            cb.onMicBusy();
        }

        void failOpenModel() {
            ProtectionController.OpenCallback cb = pending;
            pending = null;
            cb.onModelError();
        }

        void failOpenGrammar() {
            ProtectionController.OpenCallback cb = pending;
            pending = null;
            cb.onGrammarError();
        }
    }

    private final class FakeRecorder implements ProtectionController.Recorder {
        boolean recording;
        boolean startedAfterEarsClosed;
        int startCount;
        int stopCount;
        int failuresLeft;

        @Override
        public boolean start() {
            startCount++;
            if (failuresLeft > 0) {
                failuresLeft--;
                return false;
            }
            startedAfterEarsClosed = !ears.isOpen();
            recording = true;
            return true;
        }

        @Override
        public void stop() {
            stopCount++;
            recording = false;
        }

        void reset() {
            startCount = 0;
            stopCount = 0;
        }
    }

    private static final class FakeSettings implements ProtectionController.Settings {
        String grammar = PHRASE;
        boolean protectionWanted;

        @Override
        public String grammar() {
            return grammar;
        }

        @Override
        public List<String> phraseWords() {
            return WORDS;
        }

        @Override
        public void setProtectionWanted(boolean wanted) {
            protectionWanted = wanted;
        }
    }

    /** Reloj manual: las tareas solo corren cuando la prueba avanza el tiempo. */
    private static final class FakeScheduler implements ProtectionController.Scheduler {
        private long now;
        private final List<long[]> times = new ArrayList<>();
        private final List<Runnable> tasks = new ArrayList<>();

        @Override
        public void schedule(Runnable task, long delayMs) {
            times.add(new long[]{now + delayMs});
            tasks.add(task);
        }

        @Override
        public void cancel(Runnable task) {
            for (int i = tasks.size() - 1; i >= 0; i--) {
                if (tasks.get(i) == task) {
                    tasks.remove(i);
                    times.remove(i);
                }
            }
        }

        void advance(long ms) {
            long target = now + ms;
            while (true) {
                int next = -1;
                for (int i = 0; i < tasks.size(); i++) {
                    if (times.get(i)[0] <= target && (next < 0 || times.get(i)[0] < times.get(next)[0])) {
                        next = i;
                    }
                }
                if (next < 0) {
                    break;
                }
                now = times.get(next)[0];
                Runnable task = tasks.remove(next);
                times.remove(next);
                task.run();
            }
            now = target;
        }
    }

    private static final class FakeOutput implements ProtectionController.Output {
        final List<Status> statuses = new ArrayList<>();
        final List<Problem> problems = new ArrayList<>();
        int recordingsStarted;
        boolean shutdown;

        @Override
        public void onStateChanged(Status status, boolean protectionOn) {
            statuses.add(status);
        }

        @Override
        public void onRecordingStarted(boolean protectionOn) {
            statuses.add(Status.RECORDING);
            recordingsStarted++;
        }

        @Override
        public void onProblem(Problem problem) {
            problems.add(problem);
        }

        @Override
        public void onShutdown() {
            shutdown = true;
        }
    }
}
