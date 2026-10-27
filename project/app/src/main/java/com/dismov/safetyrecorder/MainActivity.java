package com.dismov.safetyrecorder;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Chronometer;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.dismov.safetyrecorder.ProtectionState.Status;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity implements ProtectionState.Listener {

    private TextView statusTitle;
    private Chronometer statusChronometer;
    private TextView statusDetail;
    private MaterialButton protectionButton;
    private MaterialButton recordButton;
    private ViewGroup issues;
    private TextInputLayout phraseLayout;
    private TextInputEditText phraseInput;
    private MaterialButton saveButton;
    private MaterialButton testButton;
    private TextView testResult;
    private TextView recordingsEmpty;
    private RecordingsAdapter adapter;

    private Map<String, List<String>> vocabulary;
    private PhraseTester tester;
    private Status lastStatus;
    private int defaultTitleColor;

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                if (Boolean.FALSE.equals(result.get(Manifest.permission.RECORD_AUDIO))
                        && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
                    // El usuario eligió "no volver a preguntar": solo se puede dar desde Ajustes
                    Toast.makeText(this, R.string.permission_go_settings, Toast.LENGTH_LONG).show();
                    openAppSettings();
                }
                render();
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        applyInsets();

        statusTitle = findViewById(R.id.status_title);
        statusChronometer = findViewById(R.id.status_chronometer);
        statusDetail = findViewById(R.id.status_detail);
        defaultTitleColor = statusTitle.getCurrentTextColor();
        protectionButton = findViewById(R.id.button_protection);
        recordButton = findViewById(R.id.button_record);
        issues = findViewById(R.id.issues);
        phraseLayout = findViewById(R.id.phrase_layout);
        phraseInput = findViewById(R.id.phrase_input);
        saveButton = findViewById(R.id.button_save);
        testButton = findViewById(R.id.button_test);
        testResult = findViewById(R.id.test_result);
        recordingsEmpty = findViewById(R.id.recordings_empty);

        RecyclerView list = findViewById(R.id.recordings_list);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new RecordingsAdapter(this, this::refreshRecordings);
        list.setAdapter(adapter);

        protectionButton.setOnClickListener(v -> toggleProtection());
        recordButton.setOnClickListener(v -> toggleRecording());
        saveButton.setOnClickListener(v -> savePhrase());
        testButton.setOnClickListener(v -> toggleTest());

        phraseInput.setText(PhraseStore.phrase(this));
        setPhraseControlsEnabled(false);
        saveButton.setText(R.string.phrase_preparing);
        VoiceModel.vocabulary(this, new VoiceModel.Callback<Map<String, List<String>>>() {
            @Override
            public void onReady(Map<String, List<String>> index) {
                vocabulary = index;
                saveButton.setText(R.string.phrase_save);
                setPhraseControlsEnabled(true);
            }

            @Override
            public void onError(Exception e) {
                saveButton.setText(R.string.phrase_save);
                phraseLayout.setError(getString(R.string.problem_model));
            }
        });

        if (savedInstanceState == null && !hasMicPermission()) {
            requestPermissions();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        ProtectionState.addListener(this);
        refreshRecordings();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Los diálogos del sistema (permisos, batería) solo pausan la actividad: aquí se vuelve a revisar
        render();
    }

    @Override
    protected void onStop() {
        ProtectionState.removeListener(this);
        stopTest(null);
        adapter.stopPlayback();
        super.onStop();
    }

    @Override
    public void onStateChanged() {
        Status previous = lastStatus;
        render();
        if (previous == Status.RECORDING && lastStatus != Status.RECORDING) {
            refreshRecordings();
        }
    }

    private void applyInsets() {
        View root = findViewById(R.id.root);
        int base = root.getPaddingTop();
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout() | WindowInsetsCompat.Type.ime());
            v.setPadding(base + bars.left, base + bars.top, base + bars.right, base + bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
    }

    // ---- Estado ----

    private void render() {
        Status status = ProtectionState.status();
        lastStatus = status;
        boolean hasPhrase = PhraseStore.hasPhrase(this);
        boolean recording = status == Status.RECORDING;

        statusChronometer.setVisibility(recording ? View.VISIBLE : View.GONE);
        statusTitle.setTextColor(recording ? ContextCompat.getColor(this, R.color.recording) : defaultTitleColor);
        if (recording) {
            statusTitle.setText(R.string.state_recording);
            statusChronometer.setBase(ProtectionState.recordingStartElapsed());
            statusChronometer.start();
            statusDetail.setText(ProtectionState.isProtectionOn()
                    ? R.string.detail_recording_then_listen : R.string.detail_recording);
        } else {
            statusChronometer.stop();
            switch (status) {
                case STARTING:
                    statusTitle.setText(R.string.state_starting);
                    statusDetail.setText(R.string.detail_starting);
                    break;
                case LISTENING:
                    statusTitle.setText(R.string.state_listening);
                    statusDetail.setText(getString(R.string.detail_listening, PhraseStore.phrase(this)));
                    break;
                case MIC_BUSY:
                    statusTitle.setText(R.string.state_mic_busy);
                    statusDetail.setText(R.string.detail_mic_busy);
                    break;
                default:
                    statusTitle.setText(R.string.state_off);
                    statusDetail.setText(hasPhrase ? R.string.detail_off : R.string.detail_no_phrase);
            }
        }

        boolean protectionActive = ProtectionState.isProtectionOn() || (status != Status.OFF && !recording);
        protectionButton.setText(protectionActive ? R.string.action_disable_protection : R.string.action_enable_protection);
        protectionButton.setEnabled(hasPhrase || protectionActive);
        recordButton.setText(recording ? R.string.action_stop : R.string.action_record);

        renderIssues();
    }

    private void toggleProtection() {
        if (!hasMicPermission()) {
            requestPermissions();
            return;
        }
        boolean active = ProtectionState.isProtectionOn()
                || (ProtectionState.status() != Status.OFF && ProtectionState.status() != Status.RECORDING);
        if (!active) {
            stopTest(null);
        }
        ProtectionService.send(this, active ? ProtectionService.ACTION_DISABLE : ProtectionService.ACTION_ENABLE);
    }

    private void toggleRecording() {
        if (!hasMicPermission()) {
            requestPermissions();
            return;
        }
        stopTest(null);
        boolean recording = ProtectionState.status() == Status.RECORDING;
        ProtectionService.send(this, recording ? ProtectionService.ACTION_STOP : ProtectionService.ACTION_RECORD);
    }

    // ---- Pendientes: solo se muestra lo que falta ----

    private void renderIssues() {
        issues.removeAllViews();
        if (!hasMicPermission()) {
            addIssue(R.string.issue_mic, R.string.issue_action_allow, v -> requestPermissions());
        }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            addIssue(R.string.issue_notifications, R.string.issue_action_allow, v -> requestPermissions());
        }
        PowerManager pm = getSystemService(PowerManager.class);
        if (!pm.isIgnoringBatteryOptimizations(getPackageName())) {
            addIssue(R.string.issue_battery, R.string.issue_action_adjust, v -> openBatterySettings());
        }
        issues.setVisibility(issues.getChildCount() == 0 ? View.GONE : View.VISIBLE);
    }

    private void addIssue(int text, int action, View.OnClickListener onClick) {
        View row = LayoutInflater.from(this).inflate(R.layout.item_issue, issues, false);
        ((TextView) row.findViewById(R.id.issue_text)).setText(text);
        MaterialButton button = row.findViewById(R.id.issue_button);
        button.setText(action);
        button.setOnClickListener(onClick);
        issues.addView(row);
    }

    private boolean hasMicPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermissions() {
        List<String> permissions = new ArrayList<>();
        permissions.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 33) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        permissionLauncher.launch(permissions.toArray(new String[0]));
    }

    private void openAppSettings() {
        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", getPackageName(), null)));
    }

    private void openBatterySettings() {
        // Muestra el diálogo del sistema "¿Permitir que la app se ejecute siempre en segundo plano?"
        @SuppressLint("BatteryLife") // es el propósito de la app: proteger con la pantalla apagada
        Intent request = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.fromParts("package", getPackageName(), null));
        try {
            startActivity(request);
        } catch (ActivityNotFoundException e) {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }

    // ---- Frase secreta ----

    private void setPhraseControlsEnabled(boolean enabled) {
        saveButton.setEnabled(enabled);
        testButton.setEnabled(enabled);
    }

    /** Valida lo escrito; muestra el error en el campo y devuelve null si no sirve. */
    private PhraseMatcher.Validation validateInput() {
        String phrase = phraseInput.getText() == null ? "" : phraseInput.getText().toString();
        PhraseMatcher.Validation result = PhraseMatcher.validate(phrase, vocabulary);
        switch (result.problem) {
            case TOO_SHORT:
                phraseLayout.setError(getString(R.string.phrase_error_short, PhraseMatcher.MIN_WORDS));
                return null;
            case TOO_LONG:
                phraseLayout.setError(getString(R.string.phrase_error_long, PhraseMatcher.MAX_WORDS));
                return null;
            case UNKNOWN_WORDS:
                phraseLayout.setError(getString(R.string.phrase_error_unknown,
                        String.join(", ", result.unknownWords)));
                return null;
            default:
                phraseLayout.setError(null);
                return result;
        }
    }

    private void savePhrase() {
        PhraseMatcher.Validation result = validateInput();
        if (result == null) {
            return;
        }
        String phrase = phraseInput.getText().toString();
        PhraseStore.save(this, phrase, result);
        Toast.makeText(this, R.string.phrase_saved, Toast.LENGTH_SHORT).show();
        if (ProtectionState.isProtectionOn()) {
            // Reinicia la escucha con la frase nueva
            ProtectionService.send(this, ProtectionService.ACTION_ENABLE);
        }
        render();
    }

    private void toggleTest() {
        if (tester != null) {
            stopTest(null);
            return;
        }
        if (!hasMicPermission()) {
            requestPermissions();
            return;
        }
        if (ProtectionState.status() != Status.OFF) {
            showTestResult(getString(R.string.test_mic_in_use));
            return;
        }
        PhraseMatcher.Validation phrase = validateInput();
        if (phrase == null) {
            return;
        }
        tester = new PhraseTester(this, phrase, new PhraseTester.Listener() {
            @Override
            public void onHeard(String text, double confidence, boolean recognized) {
                if (recognized) {
                    stopTest(getString(R.string.test_recognized, text));
                } else {
                    showTestResult(getString(R.string.test_heard, text,
                            Math.round(confidence * 100)));
                }
            }

            @Override
            public void onFinished(int messageRes) {
                stopTest(getString(messageRes));
            }
        });
        testButton.setText(R.string.test_stop);
        showTestResult(getString(R.string.test_listening));
        tester.start();
    }

    private void stopTest(String message) {
        if (tester != null) {
            tester.stop();
            tester = null;
        }
        testButton.setText(R.string.phrase_test);
        if (message != null) {
            showTestResult(message);
        }
    }

    private void showTestResult(String text) {
        testResult.setVisibility(View.VISIBLE);
        testResult.setText(text);
    }

    // ---- Grabaciones ----

    private void refreshRecordings() {
        adapter.submit(Recordings.list(this));
        recordingsEmpty.setVisibility(adapter.getItemCount() == 0 ? View.VISIBLE : View.GONE);
    }
}
