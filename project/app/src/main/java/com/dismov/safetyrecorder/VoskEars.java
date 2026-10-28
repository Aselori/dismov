package com.dismov.safetyrecorder;

import android.content.Context;
import android.util.Log;

import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;
import org.vosk.android.SpeechService;

import java.io.IOException;

/** Escucha con Vosk: implementa {@link ProtectionController.Ears} y le pasa lo que oye. */
final class VoskEars implements ProtectionController.Ears {

    private static final String TAG = "VoskEars";
    private static final float SAMPLE_RATE = 16000f;

    interface Heard {
        void onHeard(String text, double confidence);

        void onError();
    }

    private final Context context;
    private final Heard heard;
    private Recognizer recognizer;
    private SpeechService speechService;
    /** Cambia con cada open/close: una carga del modelo que termina tarde no abre nada. */
    private int request;

    VoskEars(Context context, Heard heard) {
        this.context = context.getApplicationContext();
        this.heard = heard;
    }

    @Override
    public void open(String grammar, ProtectionController.OpenCallback callback) {
        int myRequest = ++request;
        VoiceModel.load(context, new VoiceModel.Callback<Model>() {
            @Override
            public void onReady(Model model) {
                if (myRequest == request) {
                    openWith(model, grammar, callback);
                }
            }

            @Override
            public void onError(Exception e) {
                if (myRequest == request) {
                    callback.onModelError();
                }
            }
        });
    }

    private void openWith(Model model, String grammar, ProtectionController.OpenCallback callback) {
        try {
            recognizer = new Recognizer(model, SAMPLE_RATE, grammar);
            recognizer.setWords(true);
        } catch (IOException e) {
            Log.e(TAG, "Gramática inválida: " + grammar, e);
            release();
            callback.onGrammarError();
            return;
        }
        try {
            speechService = new SpeechService(recognizer, SAMPLE_RATE);
        } catch (IOException e) {
            Log.w(TAG, "Micrófono ocupado", e);
            release();
            callback.onMicBusy();
            return;
        }
        speechService.startListening(listener);
        callback.onOpened();
    }

    @Override
    public void close() {
        request++;
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
        if (speechService == null) {
            return; // resultado que llegó después de cerrar
        }
        Hypothesis h = Hypothesis.parse(json);
        if (!h.text.isEmpty()) {
            Log.d(TAG, "Escuchado: \"" + h.text + "\" confianza=" + h.confidence);
            heard.onHeard(h.text, h.confidence);
        }
    }

    private final RecognitionListener listener = new RecognitionListener() {
        @Override
        public void onPartialResult(String hypothesis) {
            // Solo se actúa con resultados completos: los parciales cambian mientras se habla
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
            if (speechService != null) {
                Log.w(TAG, "Error al escuchar", e);
                heard.onError();
            }
        }

        @Override
        public void onTimeout() {
            // Sin tiempo límite: no aplica
        }
    };
}
