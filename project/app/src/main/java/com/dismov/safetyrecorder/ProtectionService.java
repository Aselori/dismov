package com.dismov.safetyrecorder;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
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

import java.util.List;



/**
 * Servicio en primer plano (tipo micrófono). Conecta {@link ProtectionController}, que decide
 * qué hacer, con Android: Vosk, MediaRecorder, la notificación, el widget y la vibración.
 */
public class ProtectionService extends Service implements ProtectionController.Output {

    private static final String TAG = "ProtectionService";

    public static final String ACTION_ENABLE = "com.dismov.safetyrecorder.action.ENABLE";
    public static final String ACTION_DISABLE = "com.dismov.safetyrecorder.action.DISABLE";
    public static final String ACTION_RECORD = "com.dismov.safetyrecorder.action.RECORD";
    public static final String ACTION_STOP = "com.dismov.safetyrecorder.action.STOP";

    private static final int NOTIFICATION_ID = 1;
    private static final int PROBLEM_NOTIFICATION_ID = 2;
    private static final String CHANNEL_STATUS = "status";
    private static final String CHANNEL_PROBLEMS = "problems";

    private static final long CONFIRM_VIBRATION_MS = 300;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private ProtectionController controller;
    private AudioRecorder recorder;
    private PowerManager.WakeLock wakeLock;
    private boolean inForeground;
    private final PowerPressDetector powerPresses = new PowerPressDetector();
    /** Cada pulsación del botón de encendido enciende o apaga la pantalla. */
    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (powerPresses.onPress(SystemClock.elapsedRealtime())) {
                Log.d(TAG, "Botón de encendido 3 veces");
                controller.onButtonPattern();
            }
        }
    };

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
        recorder = new AudioRecorder(this, () -> controller.onRecorderError());
        VoskEars ears = new VoskEars(this, new VoskEars.Heard() {
            @Override
            public void onHeard(String text, double confidence) {
                controller.onHeard(text, confidence);
            }

            @Override
            public void onError() {
                controller.onListenError();
            }
        });
        controller = new ProtectionController(ears, recorder, new PrefsSettings(this),
                new HandlerScheduler(handler), this);
        IntentFilter screen = new IntentFilter(Intent.ACTION_SCREEN_ON);
        screen.addAction(Intent.ACTION_SCREEN_OFF);
        ContextCompat.registerReceiver(this, screenReceiver, screen, ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        boolean restartedBySystem = action == null;
        if (restartedBySystem) {
            // El sistema mató el proceso y recrea el servicio (START_STICKY)
            if (!AppPrefs.isProtectionWanted(this)) {
                stopSelf();
                return START_NOT_STICKY;
            }
            action = ACTION_ENABLE;
        }
        if (!enterForeground(restartedBySystem)) {
            controller.foregroundDenied(restartedBySystem);
            return START_NOT_STICKY;
        }
        switch (action) {
            case ACTION_ENABLE:
                controller.enable();
                break;
            case ACTION_DISABLE:
                controller.disable();
                break;
            case ACTION_RECORD:
                controller.record();
                break;
            case ACTION_STOP:
                controller.stop();
                break;
            default:
                Log.w(TAG, "Acción desconocida: " + action);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        unregisterReceiver(screenReceiver);
        controller.destroy();
        handler.removeCallbacksAndMessages(null);
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
                    // No hay nada más que intentar; el controlador detiene el servicio
                }
            }
            return false;
        }
    }

    // ---- Salidas del controlador ----

    @Override
    public void onStateChanged(Status status, boolean protectionOn) {
        ProtectionState.update(this, status, protectionOn);
        refreshNotification();
    }

    @Override
    public void onRecordingStarted(boolean protectionOn) {
        ProtectionState.startRecording(this, recorder.file(), SystemClock.elapsedRealtime(),
                System.currentTimeMillis(), protectionOn);
        refreshNotification();
        vibrate();
    }

    @Override
    public void onProblem(ProtectionController.Problem problem) {
        showProblem(problem);
    }

    @Override
    public void onShutdown() {
        releaseWakeLock();
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        inForeground = false;
        stopSelf();
    }

    // ---- Estado y notificaciones ----

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
            case ARMED:
                b.setContentTitle(getString(R.string.notif_listening_title))
                        .setContentText(getString(triggersText(ProtectionState.status())))
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

    /** Qué está esperando la protección, sin revelar la frase. */
    private int triggersText(Status status) {
        boolean button = AppPrefs.buttonStart(this);
        if (status == Status.ARMED) {
            return R.string.notif_armed_text;
        }
        return button ? R.string.notif_listening_button_text : R.string.notif_listening_text;
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

    private void showProblem(ProtectionController.Problem problem) {
        int textRes;
        PendingIntent tap = openAppIntent();
        switch (problem) {
            case NO_PHRASE:
                textRes = R.string.problem_no_phrase;
                break;
            case NO_TRIGGER:
                textRes = R.string.problem_no_trigger;
                break;
            case VOICE_STOP_UNAVAILABLE:
                textRes = R.string.problem_voice_stop_unavailable;
                break;
            case MODEL:
                textRes = R.string.problem_model;
                break;
            case CANNOT_RECORD:
                textRes = R.string.problem_cannot_record;
                break;
            case RECORDING_INTERRUPTED:
                textRes = R.string.problem_recording_interrupted;
                break;
            case PROTECTION_STOPPED:
                textRes = R.string.problem_protection_stopped;
                // Tocar la notificación sí permite volver a usar el micrófono
                tap = pendingIntent(this, ACTION_ENABLE);
                break;
            default:
                textRes = R.string.problem_cannot_start;
        }
        Log.w(TAG, "Problema: " + problem);
        Notification n = new NotificationCompat.Builder(this, CHANNEL_PROBLEMS)
                .setSmallIcon(R.drawable.ic_testigo)
                .setContentTitle(getString(R.string.problem_title))
                .setContentText(getString(textRes))
                .setStyle(new NotificationCompat.BigTextStyle().bigText(getString(textRes)))
                .setContentIntent(tap)
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

    @SuppressLint("WakelockTimeout") // se libera en onShutdown()/onDestroy(); la protección no tiene duración fija
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

    /** Preferencias que necesita el controlador. */
    private static final class PrefsSettings implements ProtectionController.Settings {
        private final Context context;

        PrefsSettings(Context context) {
            this.context = context.getApplicationContext();
        }

        @Override
        public String grammar() {
            return AppPrefs.grammar(context);
        }

        @Override
        public List<String> phraseWords() {
            return AppPrefs.words(context);
        }

        @Override
        public void setProtectionWanted(boolean wanted) {
            AppPrefs.setProtectionWanted(context, wanted);
        }

        @Override
        public boolean voiceStart() {
            return AppPrefs.voiceStart(context);
        }

        @Override
        public boolean voiceStop() {
            return AppPrefs.voiceStop(context);
        }

        @Override
        public boolean buttonStart() {
            return AppPrefs.buttonStart(context);
        }

        @Override
        public boolean buttonStop() {
            return AppPrefs.buttonStop(context);
        }
    }

    private static final class HandlerScheduler implements ProtectionController.Scheduler {
        private final Handler handler;

        HandlerScheduler(Handler handler) {
            this.handler = handler;
        }

        @Override
        public void schedule(Runnable task, long delayMs) {
            handler.postDelayed(task, delayMs);
        }

        @Override
        public void cancel(Runnable task) {
            handler.removeCallbacks(task);
        }

        @Override
        public long now() {
            return SystemClock.elapsedRealtime();
        }
    }
}
