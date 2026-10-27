package com.dismov.safetyrecorder;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Preferencias de la app: la frase secreta y si el usuario dejó la protección activada. */
public final class PhraseStore {

    private static final String PREFS = "settings";
    private static final String KEY_PHRASE = "phrase";
    private static final String KEY_WORDS = "phrase_words";
    private static final String KEY_GRAMMAR = "phrase_grammar";
    private static final String KEY_PROTECTION_WANTED = "protection_wanted";

    private PhraseStore() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static void save(Context context, String phrase, PhraseMatcher.Validation validation) {
        prefs(context).edit()
                .putString(KEY_PHRASE, phrase.trim())
                .putString(KEY_WORDS, String.join(" ", validation.words))
                .putString(KEY_GRAMMAR, validation.grammar)
                .apply();
    }

    public static boolean hasPhrase(Context context) {
        return prefs(context).getString(KEY_GRAMMAR, null) != null;
    }

    /** La frase tal como la escribió el usuario. */
    public static String phrase(Context context) {
        return prefs(context).getString(KEY_PHRASE, "");
    }

    public static List<String> words(Context context) {
        String joined = prefs(context).getString(KEY_WORDS, "");
        return joined.isEmpty() ? Collections.emptyList() : Arrays.asList(joined.split(" "));
    }

    public static String grammar(Context context) {
        return prefs(context).getString(KEY_GRAMMAR, null);
    }

    /** Se guarda en disco para retomar la protección si el sistema reinicia el servicio. */
    public static void setProtectionWanted(Context context, boolean wanted) {
        prefs(context).edit().putBoolean(KEY_PROTECTION_WANTED, wanted).apply();
    }

    public static boolean isProtectionWanted(Context context) {
        return prefs(context).getBoolean(KEY_PROTECTION_WANTED, false);
    }
}
