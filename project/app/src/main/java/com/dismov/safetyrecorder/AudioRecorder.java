package com.dismov.safetyrecorder;

import android.content.Context;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.IOException;

/** Grabación con MediaRecorder: implementa {@link ProtectionController.Recorder}. */
final class AudioRecorder implements ProtectionController.Recorder {

    private static final String TAG = "AudioRecorder";

    private final Context context;
    private final Runnable onError;
    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaRecorder recorder;
    private File file;

    /** @param onError se llama en el hilo principal si la grabación falla a mitad. */
    AudioRecorder(Context context, Runnable onError) {
        this.context = context;
        this.onError = onError;
    }

    /** Archivo de la grabación en curso, o null. */
    File file() {
        return file;
    }

    @Override
    public boolean start() {
        File output = Recordings.newFile(context);
        @SuppressWarnings("deprecation") // el constructor sin Context es el único antes de Android 12
        MediaRecorder r = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(context) : new MediaRecorder();
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC);
            // ADTS guarda el audio en bloques independientes: si el teléfono se apaga a mitad,
            // lo grabado hasta ese momento se puede reproducir
            r.setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS);
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            r.setAudioChannels(1);
            r.setAudioSamplingRate(44100);
            r.setAudioEncodingBitRate(64000);
            r.setOutputFile(output.getAbsolutePath());
            r.setOnErrorListener((mr, what, extra) -> main.post(() -> {
                Log.e(TAG, "Error de MediaRecorder what=" + what + " extra=" + extra);
                onError.run();
            }));
            r.prepare();
            r.start();
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "No se pudo iniciar la grabación", e);
            r.release();
            //noinspection ResultOfMethodCallIgnored
            output.delete();
            return false;
        }
        recorder = r;
        file = output;
        return true;
    }

    @Override
    public void stop() {
        if (recorder == null) {
            return;
        }
        try {
            recorder.stop();
        } catch (RuntimeException e) {
            // stop() falla si todavía no llegó audio; lo que haya en el archivo se conserva
            Log.w(TAG, "stop() sin audio válido", e);
        }
        recorder.release();
        recorder = null;
        if (file != null && file.length() == 0) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
        file = null;
    }
}
