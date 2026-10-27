package com.dismov.safetyrecorder;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.vosk.Model;
import org.vosk.android.StorageService;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Modelo de voz de Vosk, cargado una sola vez por proceso.
 * La primera vez copia el modelo de los assets al almacenamiento de la app (unos segundos).
 * Todos los callbacks llegan en el hilo principal.
 */
public final class VoiceModel {

    private static final String TAG = "VoiceModel";
    private static final String ASSET_DIR = "model-es";
    private static final String TARGET_DIR = "model";

    public interface Callback<T> {
        void onReady(T value);

        void onError(Exception e);
    }

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final ExecutorService background = Executors.newSingleThreadExecutor();

    private static Model model;
    private static boolean loading;
    private static final List<Callback<Model>> waiting = new ArrayList<>();
    private static Map<String, List<String>> vocabularyIndex;

    private VoiceModel() {
    }

    public static void load(Context context, Callback<Model> callback) {
        if (model != null) {
            callback.onReady(model);
            return;
        }
        waiting.add(callback);
        if (loading) {
            return;
        }
        loading = true;
        StorageService.unpack(context.getApplicationContext(), ASSET_DIR, TARGET_DIR,
                loaded -> {
                    model = loaded;
                    finish(null);
                },
                e -> {
                    Log.e(TAG, "No se pudo cargar el modelo", e);
                    finish(e);
                });
    }

    private static void finish(Exception error) {
        loading = false;
        List<Callback<Model>> callbacks = new ArrayList<>(waiting);
        waiting.clear();
        for (Callback<Model> c : callbacks) {
            if (error == null) {
                c.onReady(model);
            } else {
                c.onError(error);
            }
        }
    }

    /** Índice del vocabulario para validar frases. Se lee del modelo en segundo plano. */
    public static void vocabulary(Context context, Callback<Map<String, List<String>>> callback) {
        if (vocabularyIndex != null) {
            callback.onReady(vocabularyIndex);
            return;
        }
        Context app = context.getApplicationContext();
        load(app, new Callback<Model>() {
            @Override
            public void onReady(Model ignored) {
                File grFst = new File(app.getExternalFilesDir(null), TARGET_DIR + "/graph/Gr.fst");
                background.execute(() -> {
                    try {
                        Set<String> words = ModelVocabulary.read(grFst);
                        Map<String, List<String>> index = PhraseMatcher.indexVocabulary(words);
                        main.post(() -> {
                            vocabularyIndex = index;
                            callback.onReady(index);
                        });
                    } catch (IOException e) {
                        Log.e(TAG, "No se pudo leer el vocabulario", e);
                        main.post(() -> callback.onError(e));
                    }
                });
            }

            @Override
            public void onError(Exception e) {
                callback.onError(e);
            }
        });
    }
}
