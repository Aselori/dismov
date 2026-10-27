package com.dismov.safetyrecorder;

import android.content.Context;
import android.util.Log;

import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;
import org.vosk.android.SpeechService;

import java.io.IOException;

/**
 * Prueba la frase con el mismo reconocedor y las mismas reglas que la protección,
 * para que el usuario sepa que funcionará antes de necesitarla.
 */
final class PhraseTester {

    private static final String TAG = "PhraseTester";
    private static final float SAMPLE_RATE = 16000f;
    private static final int TIMEOUT_MS = 10000;

    interface Listener {
        void onHeard(String text, double confidence, boolean recognized);

        void onFinished(int messageRes);
    }

    private final Context context;
    private final PhraseMatcher.Validation phrase;
    private final Listener listener;
    private Recognizer recognizer;
    private SpeechService speechService;
    private boolean stopped;

    PhraseTester(Context context, PhraseMatcher.Validation phrase, Listener listener) {
        this.context = context.getApplicationContext();
        this.phrase = phrase;
        this.listener = listener;
    }

    void start() {
        VoiceModel.load(context, new VoiceModel.Callback<Model>() {
            @Override
            public void onReady(Model model) {
                if (stopped) {
                    return;
                }
                try {
                    recognizer = new Recognizer(model, SAMPLE_RATE, phrase.grammar);
                    recognizer.setWords(true);
                    speechService = new SpeechService(recognizer, SAMPLE_RATE);
                    speechService.startListening(recognitionListener, TIMEOUT_MS);
                } catch (IOException e) {
                    Log.w(TAG, "No se pudo abrir el micrófono", e);
                    release();
                    listener.onFinished(R.string.test_mic_busy);
                }
            }

            @Override
            public void onError(Exception e) {
                if (!stopped) {
                    listener.onFinished(R.string.problem_model);
                }
            }
        });
    }

    void stop() {
        stopped = true;
        release();
    }

    private void release() {
        if (speechService != null) {
            speechService.cancel();
            speechService.shutdown();
            speechService = null;
        }
        if (recognizer != null) {
            recognizer.close();
            recognizer = null;
        }
    }

    private void handle(String json) {
        if (stopped) {
            return;
        }
        Hypothesis h = Hypothesis.parse(json);
        if (!h.text.isEmpty()) {
            listener.onHeard(h.text, h.confidence, h.triggers(phrase.words));
        }
    }

    private final RecognitionListener recognitionListener = new RecognitionListener() {
        @Override
        public void onPartialResult(String hypothesis) {
        }

        @Override
        public void onResult(String hypothesis) {
            handle(hypothesis);
        }

        @Override
        public void onFinalResult(String hypothesis) {
            handle(hypothesis);
        }

        @Override
        public void onError(Exception e) {
            Log.w(TAG, "Error al escuchar", e);
            if (!stopped) {
                listener.onFinished(R.string.test_mic_busy);
            }
        }

        @Override
        public void onTimeout() {
            if (!stopped) {
                listener.onFinished(R.string.test_timeout);
            }
        }
    };
}
