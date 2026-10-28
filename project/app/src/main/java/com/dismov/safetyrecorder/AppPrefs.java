package com.dismov.safetyrecorder;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Preferencias de la app: la frase secreta, los métodos de activación y si la protección quedó activada. */
public final class AppPrefs {

    private static final String PREFS = "settings";
    private static final String KEY_PHRASE = "phrase";
    private static final String KEY_WORDS = "phrase_words";
    private static final String KEY_GRAMMAR = "phrase_grammar";
    private static final String KEY_PROTECTION_WANTED = "protection_wanted";
    private static final String KEY_VOICE_START = "voice_start";
    private static final String KEY_VOICE_STOP = "voice_stop";
    private static final String KEY_BUTTON_START = "button_start";
    private static final String KEY_BUTTON_STOP = "button_stop";

    private AppPrefs() {
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

    // ---- Métodos de activación: por defecto ambos inician y ninguno detiene ----

    public static boolean voiceStart(Context context) {
        return prefs(context).getBoolean(KEY_VOICE_START, true);
    }

    public static boolean voiceStop(Context context) {
        return prefs(context).getBoolean(KEY_VOICE_STOP, false);
    }

    public static boolean buttonStart(Context context) {
        return prefs(context).getBoolean(KEY_BUTTON_START, true);
    }

    public static boolean buttonStop(Context context) {
        return prefs(context).getBoolean(KEY_BUTTON_STOP, false);
    }

    public static void setTriggers(Context context, boolean voiceStart, boolean voiceStop,
                                   boolean buttonStart, boolean buttonStop) {
        prefs(context).edit()
                .putBoolean(KEY_VOICE_START, voiceStart)
                .putBoolean(KEY_VOICE_STOP, voiceStart && voiceStop)
                .putBoolean(KEY_BUTTON_START, buttonStart)
                .putBoolean(KEY_BUTTON_STOP, buttonStart && buttonStop)
                .apply();
    }

    /** ¿Hay al menos un método que pueda iniciar la grabación? */
    public static boolean canProtect(Context context) {
        return (voiceStart(context) && hasPhrase(context)) || buttonStart(context);
    }
}
