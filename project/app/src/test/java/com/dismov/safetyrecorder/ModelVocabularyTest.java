package com.dismov.safetyrecorder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class ModelVocabularyTest {

    @Test
    public void readsSymbolTableFromSyntheticFst() throws IOException {
        byte[] fst = fakeFst("<eps>", "[unk]", "ayúdame", "año");
        Set<String> words = ModelVocabulary.read(new ByteArrayInputStream(fst));
        assertEquals(new HashSet<>(Arrays.asList("<eps>", "[unk]", "ayúdame", "año")), words);
    }

    @Test(expected = IOException.class)
    public void rejectsFileThatIsNotAnFst() throws IOException {
        ModelVocabulary.read(new ByteArrayInputStream(new byte[64]));
    }

    /** Con el modelo real descargado (scripts/download-model.sh). Se omite si no está. */
    @Test
    public void readsRealModelVocabulary() throws IOException {
        File grFst = new File("src/main/assets/model-es/graph/Gr.fst");
        assumeTrue("Modelo no descargado", grFst.exists());
        Set<String> words = ModelVocabulary.read(grFst);
        assertTrue(words.size() > 90_000);
        assertTrue(words.containsAll(Arrays.asList("ayúdame", "ayudame", "socorro", "auxilio", "año")));
    }

    private static byte[] fakeFst(String... symbols) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeInt(out, 2125659606);
        writeString(out, "ngram");
        writeString(out, "standard");
        writeInt(out, 1);           // versión
        writeInt(out, 3);           // flags: tablas de entrada y salida
        for (int i = 0; i < 4; i++) {
            writeLong(out, 0);      // propiedades, inicio, estados, arcos
        }
        writeInt(out, 2125658996);
        writeString(out, "words.txt");
        writeLong(out, symbols.length);
        writeLong(out, symbols.length);
        for (int i = 0; i < symbols.length; i++) {
            writeString(out, symbols[i]);
            writeLong(out, i);
        }
        return out.toByteArray();
    }

    private static void writeInt(ByteArrayOutputStream out, int value) throws IOException {
        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array());
    }

    private static void writeLong(ByteArrayOutputStream out, long value) throws IOException {
        out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array());
    }

    private static void writeString(ByteArrayOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeInt(out, bytes.length);
        out.write(bytes);
    }
}
