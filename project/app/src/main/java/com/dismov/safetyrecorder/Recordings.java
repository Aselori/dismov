package com.dismov.safetyrecorder;

import android.content.Context;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Archivos de grabación, guardados en el almacenamiento privado de la app. */
public final class Recordings {

    static final String EXTENSION = ".aac";
    private static final String DIR = "recordings";

    private Recordings() {
    }

    public static File directory(Context context) {
        File dir = new File(context.getFilesDir(), DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("No se pudo crear " + dir);
        }
        return dir;
    }

    static File newFile(Context context) {
        String stamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(new Date());
        return new File(directory(context), "grabacion_" + stamp + EXTENSION);
    }

    /** Más recientes primero, sin la grabación en curso. */
    public static List<File> list(Context context) {
        File active = ProtectionState.recordingFile();
        File[] files = directory(context).listFiles(
                f -> f.getName().endsWith(EXTENSION) && !f.equals(active));
        List<File> result = new ArrayList<>(files == null ? List.of() : Arrays.asList(files));
        result.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return result;
    }
}
