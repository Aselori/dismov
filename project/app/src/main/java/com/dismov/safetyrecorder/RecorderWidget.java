package com.dismov.safetyrecorder;

import android.Manifest;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.view.View;
import android.widget.RemoteViews;

import androidx.core.content.ContextCompat;

import com.dismov.safetyrecorder.ProtectionState.Status;

/**
 * Widget de la pantalla de inicio: muestra el estado y permite activar la protección,
 * grabar con un toque o detener. Si falta configurar algo, sus botones abren la app.
 */
public class RecorderWidget extends AppWidgetProvider {

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        manager.updateAppWidget(ids, build(context));
    }

    public static void refreshAll(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, RecorderWidget.class));
        if (ids.length > 0) {
            manager.updateAppWidget(ids, build(context));
        }
    }

    private static RemoteViews build(Context context) {
        RemoteViews v = new RemoteViews(context.getPackageName(), R.layout.widget_recorder);
        PendingIntent openApp = PendingIntent.getActivity(context, 0,
                new Intent(context, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        v.setOnClickPendingIntent(R.id.widget_info, openApp);

        boolean micGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
        Status status = ProtectionState.status();

        if (status == Status.RECORDING) {
            v.setTextViewText(R.id.widget_status, context.getString(R.string.state_recording));
            v.setViewVisibility(R.id.widget_chronometer, View.VISIBLE);
            v.setChronometer(R.id.widget_chronometer, ProtectionState.recordingStartElapsed(), null, true);
            v.setViewVisibility(R.id.widget_detail, View.GONE);
            v.setViewVisibility(R.id.widget_protection, View.GONE);
            setButton(context, v, R.id.widget_record, context.getString(R.string.action_stop),
                    R.drawable.widget_button_stop, ProtectionService.pendingIntent(context, ProtectionService.ACTION_STOP));
            return v;
        }

        v.setChronometer(R.id.widget_chronometer, 0, null, false);
        v.setViewVisibility(R.id.widget_chronometer, View.GONE);
        v.setViewVisibility(R.id.widget_detail, View.VISIBLE);
        v.setViewVisibility(R.id.widget_protection, View.VISIBLE);

        // Sin permiso de micrófono el servicio no puede arrancar: los botones abren la app
        PendingIntent record = micGranted
                ? ProtectionService.pendingIntent(context, ProtectionService.ACTION_RECORD) : openApp;
        setButton(context, v, R.id.widget_record, context.getString(R.string.action_record),
                R.drawable.widget_button, record);

        if (!micGranted || !AppPrefs.canProtect(context)) {
            v.setTextViewText(R.id.widget_status, context.getString(R.string.widget_setup_title));
            v.setTextViewText(R.id.widget_detail, context.getString(R.string.widget_setup_detail));
            setButton(context, v, R.id.widget_protection, context.getString(R.string.widget_action_open),
                    R.drawable.widget_button_primary, openApp);
            return v;
        }

        boolean active = status != Status.OFF;
        int title;
        int detail;
        switch (status) {
            case LISTENING:
                title = R.string.state_listening;
                detail = R.string.widget_listening_detail;
                break;
            case ARMED:
                title = R.string.state_listening;
                detail = R.string.widget_armed_detail;
                break;
            case STARTING:
                title = R.string.state_starting;
                detail = R.string.widget_starting_detail;
                break;
            case MIC_BUSY:
                title = R.string.state_mic_busy;
                detail = R.string.widget_busy_detail;
                break;
            default:
                title = R.string.state_off;
                detail = R.string.widget_off_detail;
        }
        v.setTextViewText(R.id.widget_status, context.getString(title));
        v.setTextViewText(R.id.widget_detail, context.getString(detail));
        setButton(context, v, R.id.widget_protection,
                context.getString(active ? R.string.widget_action_disable : R.string.widget_action_enable),
                active ? R.drawable.widget_button : R.drawable.widget_button_primary,
                ProtectionService.pendingIntent(context,
                        active ? ProtectionService.ACTION_DISABLE : ProtectionService.ACTION_ENABLE));
        return v;
    }

    private static void setButton(Context context, RemoteViews v, int id, String text, int background,
                                  PendingIntent action) {
        v.setTextViewText(id, text);
        v.setInt(id, "setBackgroundResource", background);
        // El botón principal es claro: su texto va oscuro para que se lea
        v.setTextColor(id, context.getColor(background == R.drawable.widget_button_primary
                ? R.color.widget_on_primary : R.color.widget_text));
        v.setOnClickPendingIntent(id, action);
    }
}
