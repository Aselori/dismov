package com.dismov.safetyrecorder;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import com.dismov.safetyrecorder.ProtectionState.Status;

import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;
import org.vosk.android.SpeechService;

import java.io.File;
import java.io.IOException;

/**
 * Servicio en primer plano (tipo micrófono) que hace las dos cosas de la app:
 * escuchar la frase secreta con Vosk y grabar audio con MediaRecorder.
 * Nunca hace las dos a la vez: el micrófono solo lo puede usar uno.
 */
public class ProtectionService extends Service {

    private static final String TAG = "ProtectionService";

    public static final String ACTION_ENABLE = "com.dismov.safetyrecorder.action.ENABLE";
    public static final String ACTION_DISABLE = "com.dismov.safetyrecorder.action.DISABLE";
    public static final String ACTION_RECORD = "com.dismov.safetyrecorder.action.RECORD";
    public static final String ACTION_STOP = "com.dismov.safetyrecorder.action.STOP";

    private static final int NOTIFICATION_ID = 1;
    private static final int PROBLEM_NOTIFICATION_ID = 2;
    private static final String CHANNEL_STATUS = "status";
    private static final String CHANNEL_PROBLEMS = "problems";

    private static final float SAMPLE_RATE = 16000f;
    private static final long LISTEN_RETRY_MS = 3000;
    private static final long RECORD_RETRY_MS = 400;
    private static final int RECORD_ATTEMPTS = 5;
    private static final long CONFIRM_VIBRATION_MS = 300;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable retryListening = this::startListening;
    private final Runnable retryRecording = this::startRecording;

    private SpeechService speechService;
    private Recognizer recognizer;
    private MediaRecorder recorder;
    private File currentFile;
    private PowerManager.WakeLock wakeLock;
    private boolean protectionOn;
    private boolean inForeground;
    private boolean destroyed;
    private int recordAttempts;

    public static void send(Context context, String action) {
        ContextCompat.startForegroundService(context, intent(context, action));
    }

    static PendingIntent pendingIntent(Context context, String action) {
        return PendingIntent.getForegroundService(context, action.hashCode(), intent(context, action),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static Intent intent(Context context, String action) {
        return new Intent(context, ProtectionService.class).setAction(action);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannels();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        boolean restartedBySystem = action == null;
        if (restartedBySystem) {
            // El sistema mató el proceso y recrea el servicio (START_STICKY)
            if (!PhraseStore.isProtectionWanted(this)) {
                stopSelf();
                return START_NOT_STICKY;
            }
            action = ACTION_ENABLE;
        }
        if (!enterForeground(restartedBySystem)) {
            return START_NOT_STICKY;
        }
        switch (action) {
            case ACTION_ENABLE:
                enableProtection();
                break;
            case ACTION_DISABLE:
                disableProtection();
                break;
            case ACTION_RECORD:
                startRecording();
                break;
            case ACTION_STOP:
                stopRecordingAndContinue();
                break;
            default:
                Log.w(TAG, "Acción desconocida: " + action);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        handler.removeCallbacksAndMessages(null);
        releaseListener();
        stopRecording();
        releaseWakeLock();
        if (ProtectionState.status() != Status.OFF) {
            ProtectionState.update(this, Status.OFF, false);
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---- Primer plano ----

    private boolean enterForeground(boolean restartedBySystem) {
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            inForeground = true;
            acquireWakeLock();
            return true;
        } catch (RuntimeException e) {
            if (inForeground) {
                // Ya estaba en primer plano desde un inicio anterior: sigue siendo válido
                Log.w(TAG, "startForeground repetido rechazado; se conserva el anterior", e);
                return true;
            }
            // Sin permiso de micrófono o el sistema no permite usarlo desde segundo plano
            Log.e(TAG, "No se pudo iniciar en primer plano con micrófono", e);
            if (!restartedBySystem && Build.VERSION.SDK_INT >= 34) {
                // startForegroundService() exige llamar a startForeground(); si no, el sistema cierra la app
                try {
                    ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(),
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE);
                } catch (RuntimeException ignored) {
                    // No hay nada más que intentar; shutdown() detiene el servicio
                }
            }
            showProblem(R.string.problem_cannot_start);
            shutdown();
            return false;
        }
    }

    private void shutdown() {
        handler.removeCallbacksAndMessages(null);
        releaseListener();
        stopRecording();
        protectionOn = false;
        PhraseStore.setProtectionWanted(this, false);
        releaseWakeLock();
        ProtectionState.update(this, Status.OFF, false);
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        inForeground = false;
        stopSelf();
    }

    // ---- Protección (escucha) ----

    private void enableProtection() {
        if (!PhraseStore.hasPhrase(this)) {
            showProblem(R.string.problem_no_phrase);
            if (recorder == null) {
                shutdown();
            }
            return;
        }
        protectionOn = true;
        PhraseStore.setProtectionWanted(this, true);
        if (recorder != null) {
            // Grabando: al detener, volverá a escuchar
            setStatus(Status.RECORDING);
            return;
        }
        // Reinicia la escucha por si cambió la frase
        releaseListener();
        startListening();
    }

    private void disableProtection() {
        protectionOn = false;
        PhraseStore.setProtectionWanted(this, false);
        if (recorder != null) {
            // Una grabación en curso no se corta; al detenerla, el servicio termina
            setStatus(Status.RECORDING);
        } else {
            shutdown();
        }
    }

    private void startListening() {
        handler.removeCallbacks(retryListening);
        if (!protectionOn || recorder != null || speechService != null || destroyed) {
            return;
        }
        String grammar = PhraseStore.grammar(this);
        if (grammar == null) {
            showProblem(R.string.problem_no_phrase);
            shutdown();
            return;
        }
        setStatus(Status.STARTING);
        VoiceModel.load(this, new VoiceModel.Callback<Model>() {
            @Override
            public void onReady(Model model) {
                openListener(model, grammar);
            }

            @Override
            public void onError(Exception e) {
                showProblem(R.string.problem_model);
                if (recorder == null) {
                    shutdown();
                }
            }
        });
    }

    private void openListener(Model model, String grammar) {
        if (!protectionOn || recorder != null || speechService != null || destroyed) {
            return;
        }
        try {
            recognizer = new Recognizer(model, SAMPLE_RATE, grammar);
            recognizer.setWords(true);
        } catch (IOException e) {
            Log.e(TAG, "Gramática inválida: " + grammar, e);
            releaseListener();
            showProblem(R.string.problem_no_phrase);
            shutdown();
            return;
        }
        try {
            speechService = new SpeechService(recognizer, SAMPLE_RATE);
        } catch (IOException e) {
            // Otra app (o una llamada) tiene el micrófono: se reintenta solo
            Log.w(TAG, "Micrófono ocupado", e);
            releaseListener();
            setStatus(Status.MIC_BUSY);
            handler.postDelayed(retryListening, LISTEN_RETRY_MS);
            return;
        }
        speechService.startListening(listener);
        setStatus(Status.LISTENING);
    }

    private void releaseListener() {
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

    private final RecognitionListener listener = new RecognitionListener() {
        @Override
        public void onPartialResult(String hypothesis) {
            // Solo se actúa con resultados completos: los parciales cambian mientras se habla
        }

        @Override
        public void onResult(String hypothesis) {
            handleHypothesis(hypothesis);
        }

        @Override
        public void onFinalResult(String hypothesis) {
            handleHypothesis(hypothesis);
        }

        @Override
        public void onError(Exception e) {
            if (speechService == null) {
                return;
            }
            Log.w(TAG, "Error al escuchar", e);
            releaseListener();
            setStatus(Status.MIC_BUSY);
            handler.postDelayed(retryListening, LISTEN_RETRY_MS);
        }

        @Override
        public void onTimeout() {
            // Sin tiempo límite: no aplica
        }
    };

    private void handleHypothesis(String json) {
        if (ProtectionState.status() != Status.LISTENING) {
            return;
        }
        Hypothesis h = Hypothesis.parse(json);
        if (h.text.isEmpty()) {
            return;
        }
        boolean triggers = h.triggers(PhraseStore.words(this));
        Log.d(TAG, "Escuchado: \"" + h.text + "\" confianza=" + h.confidence + " activa=" + triggers);
        if (triggers) {
            startRecording();
        }
    }

    // ---- Grabación ----

    private void startRecording() {
        handler.removeCallbacks(retryRecording);
        handler.removeCallbacks(retryListening);
        if (recorder != null || destroyed) {
            return;
        }
        releaseListener(); // libera el micrófono para MediaRecorder

        File file = Recordings.newFile(this);
        @SuppressWarnings("deprecation") // el constructor sin Context es el único antes de Android 12
        MediaRecorder r = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(this) : new MediaRecorder();
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC);
            // ADTS guarda el audio en bloques independientes: si el teléfono se apaga a mitad,
            // lo grabado hasta ese momento se puede reproducir
            r.setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS);
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            r.setAudioChannels(1);
            r.setAudioSamplingRate(44100);
            r.setAudioEncodingBitRate(64000);
            r.setOutputFile(file.getAbsolutePath());
            r.setOnErrorListener((mr, what, extra) -> handler.post(() -> {
                Log.e(TAG, "Error de MediaRecorder what=" + what + " extra=" + extra);
                showProblem(R.string.problem_recording_interrupted);
                stopRecordingAndContinue();
            }));
            r.prepare();
            r.start();
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "No se pudo iniciar la grabación (intento " + (recordAttempts + 1) + ")", e);
            r.release();
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            if (++recordAttempts < RECORD_ATTEMPTS) {
                // Justo después de soltar Vosk el micrófono puede tardar en liberarse
                handler.postDelayed(retryRecording, RECORD_RETRY_MS);
                return;
            }
            recordAttempts = 0;
            showProblem(R.string.problem_cannot_record);
            continueAfterRecording();
            return;
        }
        recordAttempts = 0;
        recorder = r;
        currentFile = file;
        ProtectionState.startRecording(this, file, SystemClock.elapsedRealtime(), System.currentTimeMillis(), protectionOn);
        refreshNotification();
        vibrate();
    }

    private void stopRecordingAndContinue() {
        stopRecording();
        continueAfterRecording();
    }

    private void continueAfterRecording() {
        if (protectionOn) {
            startListening();
        } else {
            shutdown();
        }
    }

    private void stopRecording() {
        handler.removeCallbacks(retryRecording);
        recordAttempts = 0;
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
        if (currentFile != null && currentFile.length() == 0) {
            //noinspection ResultOfMethodCallIgnored
            currentFile.delete();
        }
        currentFile = null;
    }

    // ---- Estado y notificaciones ----

    private void setStatus(Status status) {
        ProtectionState.update(this, status, protectionOn);
        refreshNotification();
    }

    private void createChannels() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel status = new NotificationChannel(CHANNEL_STATUS,
                getString(R.string.channel_status), NotificationManager.IMPORTANCE_LOW);
        status.setShowBadge(false);
        nm.createNotificationChannel(status);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_PROBLEMS,
                getString(R.string.channel_problems), NotificationManager.IMPORTANCE_DEFAULT));
    }

    private Notification buildNotification() {
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_STATUS)
                .setSmallIcon(R.drawable.ic_testigo)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setShowWhen(false)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setContentIntent(openAppIntent())
                // En la pantalla de bloqueo solo se ve un texto neutro, sin acciones
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(new NotificationCompat.Builder(this, CHANNEL_STATUS)
                        .setSmallIcon(R.drawable.ic_testigo)
                        .setContentTitle(getString(R.string.app_name))
                        .setContentText(getString(R.string.notif_public_text))
                        .build());

        switch (ProtectionState.status()) {
            case RECORDING:
                b.setContentTitle(getString(R.string.notif_recording_title))
                        .setContentText(getString(R.string.notif_recording_text))
                        .setUsesChronometer(true)
                        .setShowWhen(true)
                        .setWhen(ProtectionState.recordingStartWall())
                        .addAction(securedAction(R.drawable.ic_stop, R.string.action_stop, ACTION_STOP));
                break;
            case LISTENING:
                b.setContentTitle(getString(R.string.notif_listening_title))
                        .setContentText(getString(R.string.notif_listening_text))
                        .addAction(new NotificationCompat.Action(R.drawable.ic_mic,
                                getString(R.string.action_record), pendingIntent(this, ACTION_RECORD)))
                        .addAction(securedAction(R.drawable.ic_testigo, R.string.action_disable, ACTION_DISABLE));
                break;
            case MIC_BUSY:
                b.setContentTitle(getString(R.string.notif_busy_title))
                        .setContentText(getString(R.string.notif_busy_text))
                        .addAction(securedAction(R.drawable.ic_testigo, R.string.action_disable, ACTION_DISABLE));
                break;
            default:
                b.setContentTitle(getString(R.string.notif_starting_title));
        }
        return b.build();
    }

    /** Acción que pide desbloquear el teléfono: así nadie más la usa desde la pantalla de bloqueo. */
    private NotificationCompat.Action securedAction(int icon, int title, String action) {
        return new NotificationCompat.Action.Builder(icon, getString(title), pendingIntent(this, action))
                .setAuthenticationRequired(true)
                .build();
    }

    private PendingIntent openAppIntent() {
        Intent open = new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private void refreshNotification() {
        if (inForeground) {
            notifySafely(NOTIFICATION_ID, buildNotification());
        }
    }

    private void showProblem(int textRes) {
        Log.w(TAG, "Problema: " + getString(textRes));
        Notification n = new NotificationCompat.Builder(this, CHANNEL_PROBLEMS)
                .setSmallIcon(R.drawable.ic_testigo)
                .setContentTitle(getString(R.string.problem_title))
                .setContentText(getString(textRes))
                .setStyle(new NotificationCompat.BigTextStyle().bigText(getString(textRes)))
                .setContentIntent(openAppIntent())
                .setAutoCancel(true)
                .build();
        notifySafely(PROBLEM_NOTIFICATION_ID, n);
    }

    /** Sin permiso de notificaciones no se muestran, pero el servicio sigue funcionando. */
    private void notifySafely(int id, Notification notification) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        try {
            NotificationManagerCompat.from(this).notify(id, notification);
        } catch (SecurityException e) {
            Log.w(TAG, "Permiso de notificaciones retirado", e);
        }
    }

    // ---- Recursos del sistema ----

    @SuppressLint("WakelockTimeout") // se libera en shutdown()/onDestroy(); la protección no tiene duración fija
    private void acquireWakeLock() {
        if (wakeLock == null) {
            PowerManager pm = getSystemService(PowerManager.class);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Testigo:protection");
            wakeLock.setReferenceCounted(false);
        }
        if (!wakeLock.isHeld()) {
            // Se libera al detener el servicio; mientras protege, la CPU no debe dormirse
            wakeLock.acquire();
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    /** Vibración corta para confirmar, sin mirar la pantalla, que empezó a grabar. */
    private void vibrate() {
        Vibrator vibrator = Build.VERSION.SDK_INT >= 31
                ? getSystemService(VibratorManager.class).getDefaultVibrator()
                : getSystemService(Vibrator.class);
        if (vibrator == null || !vibrator.hasVibrator()) {
            return;
        }
        VibrationEffect effect = VibrationEffect.createOneShot(CONFIRM_VIBRATION_MS, VibrationEffect.DEFAULT_AMPLITUDE);
        // Uso "alarma" para que vibre aunque el teléfono esté en silencio o la app en segundo plano
        if (Build.VERSION.SDK_INT >= 33) {
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM));
        } else {
            vibrator.vibrate(effect, new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build());
        }
    }
}
