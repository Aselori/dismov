package com.dismov.safetyrecorder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reglas de la frase secreta: validación contra el vocabulario del modelo,
 * gramática para Vosk y comparación con lo que se reconoció.
 * No depende de Android para poder probarse con JUnit.
 */
public final class PhraseMatcher {

    public static final int MIN_WORDS = 2;
    public static final int MAX_WORDS = 6;

    /**
     * Confianza media mínima de las palabras reconocidas para empezar a grabar.
     * Valor inicial; se ajusta con la evaluación de audio (aciertos y falsas activaciones).
     */
    public static final double MIN_CONFIDENCE = 0.5;
    private static final int MAX_GRAMMAR_PHRASES = 16;
    private static final Locale SPANISH = Locale.forLanguageTag("es");

    private PhraseMatcher() {
    }

    /** Minúsculas y sin acentos (conserva la ñ), para comparar sin depender de cómo se escribió. */
    public static String normalize(String text) {
        String lower = text.toLowerCase(SPANISH);
        StringBuilder out = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            switch (c) {
                case 'á': out.append('a'); break;
                case 'é': out.append('e'); break;
                case 'í': out.append('i'); break;
                case 'ó': out.append('o'); break;
                case 'ú':
                case 'ü': out.append('u'); break;
                default: out.append(c);
            }
        }
        return out.toString();
    }

    /** Separa en palabras: todo lo que no sea letra cuenta como separador. */
    public static List<String> words(String text) {
        List<String> result = new ArrayList<>();
        for (String w : text.toLowerCase(SPANISH).split("\\P{L}+")) {
            if (!w.isEmpty()) {
                result.add(w);
            }
        }
        return result;
    }

    /** Índice "palabra normalizada" -> formas que existen en el vocabulario (con y sin acento). */
    public static Map<String, List<String>> indexVocabulary(Set<String> vocabulary) {
        Map<String, List<String>> index = new HashMap<>();
        for (String w : vocabulary) {
            index.computeIfAbsent(normalize(w), k -> new ArrayList<>()).add(w);
        }
        return index;
    }

    public static Validation validate(String phrase, Map<String, List<String>> vocabularyIndex) {
        List<String> typed = words(phrase);
        if (typed.size() < MIN_WORDS) {
            return Validation.error(Validation.Problem.TOO_SHORT, Collections.emptyList());
        }
        if (typed.size() > MAX_WORDS) {
            return Validation.error(Validation.Problem.TOO_LONG, Collections.emptyList());
        }
        List<String> unknown = new ArrayList<>();
        List<List<String>> forms = new ArrayList<>();
        List<String> normalized = new ArrayList<>();
        for (String w : typed) {
            String key = normalize(w);
            List<String> known = vocabularyIndex.get(key);
            if (known == null) {
                unknown.add(w);
            } else {
                List<String> sorted = new ArrayList<>(known);
                Collections.sort(sorted);
                forms.add(sorted);
                normalized.add(key);
            }
        }
        if (!unknown.isEmpty()) {
            return Validation.error(Validation.Problem.UNKNOWN_WORDS, unknown);
        }
        return Validation.ok(normalized, grammarJson(forms));
    }

    /**
     * Gramática de Vosk: la frase en todas sus variantes de escritura más "[unk]",
     * para que cualquier otra cosa que se diga no se fuerce a parecerse a la frase.
     */
    static String grammarJson(List<List<String>> forms) {
        List<String> phrases = new ArrayList<>();
        phrases.add("");
        for (List<String> options : forms) {
            List<String> next = new ArrayList<>();
            for (String prefix : phrases) {
                for (String option : options) {
                    if (next.size() < MAX_GRAMMAR_PHRASES) {
                        next.add(prefix.isEmpty() ? option : prefix + " " + option);
                    }
                }
            }
            phrases = next;
        }
        StringBuilder json = new StringBuilder("[");
        for (String p : phrases) {
            json.append('"').append(p.replace("\\", "\\\\").replace("\"", "\\\"")).append("\", ");
        }
        return json.append("\"[unk]\"]").toString();
    }

    /** ¿Lo reconocido es la frase secreta, con confianza suficiente para empezar a grabar? */
    public static boolean triggers(String recognized, double confidence, List<String> phraseWords) {
        return confidence >= MIN_CONFIDENCE && matches(recognized, phraseWords);
    }

    /** ¿Lo reconocido contiene la frase completa, en orden y sin palabras intercaladas? */
    public static boolean matches(String recognized, List<String> phraseWords) {
        if (phraseWords.isEmpty()) {
            return false;
        }
        List<String> heard = new ArrayList<>();
        for (String w : words(recognized)) {
            heard.add(normalize(w));
        }
        return Collections.indexOfSubList(heard, phraseWords) >= 0;
    }

    public static final class Validation {
        public enum Problem { NONE, TOO_SHORT, TOO_LONG, UNKNOWN_WORDS }

        public final Problem problem;
        public final List<String> unknownWords;
        /** Palabras normalizadas de la frase, para {@link #matches}. */
        public final List<String> words;
        public final String grammar;

        private Validation(Problem problem, List<String> unknownWords, List<String> words, String grammar) {
            this.problem = problem;
            this.unknownWords = unknownWords;
            this.words = words;
            this.grammar = grammar;
        }

        static Validation ok(List<String> words, String grammar) {
            return new Validation(Problem.NONE, Collections.emptyList(), words, grammar);
        }

        static Validation error(Problem problem, List<String> unknownWords) {
            return new Validation(problem, unknownWords, Collections.emptyList(), null);
        }

        public boolean isValid() {
            return problem == Problem.NONE;
        }
    }
}
