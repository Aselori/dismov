package com.dismov.safetyrecorder;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/**
 * Lee las palabras que conoce el modelo de Vosk. El modelo pequeño no trae words.txt:
 * la lista está embebida como tabla de símbolos en graph/Gr.fst (formato binario de OpenFst).
 * Solo se lee la cabecera y la tabla, no el grafo completo.
 */
public final class ModelVocabulary {

    private static final int FST_MAGIC = 2125659606;
    private static final int SYMBOL_TABLE_MAGIC = 2125658996;
    private static final int FLAG_HAS_INPUT_SYMBOLS = 1;
    private static final int MAX_STRING_BYTES = 1 << 16;

    private ModelVocabulary() {
    }

    public static Set<String> read(File grFst) throws IOException {
        try (InputStream in = new FileInputStream(grFst)) {
            return read(in);
        }
    }

    static Set<String> read(InputStream raw) throws IOException {
        LittleEndianReader in = new LittleEndianReader(new BufferedInputStream(raw));
        if (in.readInt() != FST_MAGIC) {
            throw new IOException("Gr.fst no tiene el formato esperado");
        }
        in.readString(); // tipo de FST
        in.readString(); // tipo de arco
        in.readInt();    // versión
        int flags = in.readInt();
        in.readLong();   // propiedades
        in.readLong();   // estado inicial
        in.readLong();   // número de estados
        in.readLong();   // número de arcos
        if ((flags & FLAG_HAS_INPUT_SYMBOLS) == 0) {
            throw new IOException("Gr.fst no incluye la tabla de palabras");
        }
        if (in.readInt() != SYMBOL_TABLE_MAGIC) {
            throw new IOException("Tabla de palabras con formato inesperado");
        }
        in.readString(); // nombre de la tabla
        in.readLong();   // siguiente clave disponible
        long size = in.readLong();
        Set<String> words = new HashSet<>((int) Math.min(size * 2, Integer.MAX_VALUE));
        for (long i = 0; i < size; i++) {
            words.add(in.readString());
            in.readLong(); // clave numérica de la palabra
        }
        return words;
    }

    private static final class LittleEndianReader {
        private final DataInputStream in;

        LittleEndianReader(InputStream in) {
            this.in = new DataInputStream(in);
        }

        int readInt() throws IOException {
            return Integer.reverseBytes(in.readInt());
        }

        long readLong() throws IOException {
            return Long.reverseBytes(in.readLong());
        }

        String readString() throws IOException {
            int length = readInt();
            if (length < 0 || length > MAX_STRING_BYTES) {
                throw new EOFException("Longitud de texto inválida: " + length);
            }
            byte[] bytes = new byte[length];
            in.readFully(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }
}
