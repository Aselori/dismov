package com.dismov.safetyrecorder;

import android.content.Context;
import android.content.Intent;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Lista de grabaciones: reproducir, compartir y borrar. */
class RecordingsAdapter extends RecyclerView.Adapter<RecordingsAdapter.Holder> {

    private static final String TAG = "RecordingsAdapter";

    private final Context context;
    private final Runnable onChanged;
    private final List<File> files = new ArrayList<>();
    /** Duraciones ya calculadas (leer la duración de un AAC largo tarda). */
    private final Map<String, Long> durations = new HashMap<>();
    private final ExecutorService background = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private MediaPlayer player;
    private File playing;

    RecordingsAdapter(Context context, Runnable onChanged) {
        this.context = context;
        this.onChanged = onChanged;
    }

    void submit(List<File> newFiles) {
        if (playing != null && !newFiles.contains(playing)) {
            stopPlayback();
        }
        files.clear();
        files.addAll(newFiles);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_recording, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        File file = files.get(position);
        h.title.setText(DateUtils.formatDateTime(context, file.lastModified(),
                DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_TIME | DateUtils.FORMAT_ABBREV_MONTH));
        h.subtitle.setText(subtitle(file));

        boolean isPlaying = file.equals(playing);
        h.play.setImageResource(isPlaying ? R.drawable.ic_stop : R.drawable.ic_play);
        h.play.setContentDescription(context.getString(isPlaying ? R.string.recording_stop : R.string.recording_play));
        h.play.setOnClickListener(v -> togglePlayback(file));
        h.share.setOnClickListener(v -> share(file));
        h.delete.setOnClickListener(v -> confirmDelete(file));
    }

    @Override
    public int getItemCount() {
        return files.size();
    }

    private String subtitle(File file) {
        String size = Formatter.formatShortFileSize(context, file.length());
        Long duration = durations.get(file.getPath());
        if (duration == null) {
            loadDuration(file);
            return size;
        }
        return duration < 0 ? size : DateUtils.formatElapsedTime(duration / 1000) + " · " + size;
    }

    private void loadDuration(File file) {
        durations.put(file.getPath(), -1L);
        background.execute(() -> {
            long ms = -1;
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                retriever.setDataSource(file.getPath());
                String value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                if (value != null) {
                    ms = Long.parseLong(value);
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "Sin duración para " + file, e);
            } finally {
                try {
                    retriever.release();
                } catch (IOException ignored) {
                    // Nada que liberar
                }
            }
            long result = ms;
            main.post(() -> {
                durations.put(file.getPath(), result);
                int index = files.indexOf(file);
                if (index >= 0) {
                    notifyItemChanged(index);
                }
            });
        });
    }

    private void togglePlayback(File file) {
        boolean same = file.equals(playing);
        stopPlayback();
        if (same) {
            return;
        }
        MediaPlayer p = new MediaPlayer();
        try {
            p.setDataSource(file.getPath());
            p.prepare();
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "No se puede reproducir " + file, e);
            p.release();
            Toast.makeText(context, R.string.recording_cannot_play, Toast.LENGTH_SHORT).show();
            return;
        }
        p.setOnCompletionListener(mp -> stopPlayback());
        p.start();
        player = p;
        playing = file;
        notifyFile(file);
    }

    void stopPlayback() {
        if (player != null) {
            player.release();
            player = null;
        }
        File was = playing;
        playing = null;
        if (was != null) {
            notifyFile(was);
        }
    }

    private void notifyFile(File file) {
        int index = files.indexOf(file);
        if (index >= 0) {
            notifyItemChanged(index);
        }
    }

    private void share(File file) {
        Uri uri = FileProvider.getUriForFile(context, context.getPackageName() + ".files", file);
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType("audio/aac")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        context.startActivity(Intent.createChooser(send, context.getString(R.string.recording_share)));
    }

    private void confirmDelete(File file) {
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.recording_delete_title)
                .setMessage(R.string.recording_delete_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.recording_delete, (d, w) -> {
                    if (file.equals(playing)) {
                        stopPlayback();
                    }
                    if (!file.delete()) {
                        Toast.makeText(context, R.string.recording_cannot_delete, Toast.LENGTH_SHORT).show();
                    }
                    durations.remove(file.getPath());
                    onChanged.run();
                })
                .show();
    }

    static class Holder extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView subtitle;
        final ImageButton play;
        final ImageButton share;
        final ImageButton delete;

        Holder(View v) {
            super(v);
            title = v.findViewById(R.id.recording_title);
            subtitle = v.findViewById(R.id.recording_subtitle);
            play = v.findViewById(R.id.recording_play);
            share = v.findViewById(R.id.recording_share);
            delete = v.findViewById(R.id.recording_delete);
        }
    }
}
