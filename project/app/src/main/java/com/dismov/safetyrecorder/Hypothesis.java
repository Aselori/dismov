package com.dismov.safetyrecorder;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

/** Resultado de Vosk: texto reconocido y confianza media de sus palabras. */
final class Hypothesis {

    private static final String TAG = "Hypothesis";

    /**
     * Confianza media mínima de las palabras reconocidas. Valor inicial conservador:
     * se ajusta en la validación con el teléfono (en bolsillo, con ruido).
     */
    static final double MIN_CONFIDENCE = 0.5;

    final String text;
    final double confidence;

    private Hypothesis(String text, double confidence) {
        this.text = text;
        this.confidence = confidence;
    }

    /** ¿Es la frase secreta, con suficiente confianza para empezar a grabar? */
    boolean triggers(List<String> phraseWords) {
        return confidence >= MIN_CONFIDENCE && PhraseMatcher.matches(text, phraseWords);
    }

    static Hypothesis parse(String json) {
        try {
            JSONObject o = new JSONObject(json);
            String text = o.optString("text", "").replace("[unk]", "").trim();
            JSONArray words = o.optJSONArray("result");
            if (words == null || words.length() == 0) {
                return new Hypothesis(text, 1.0);
            }
            double sum = 0;
            int count = 0;
            for (int i = 0; i < words.length(); i++) {
                JSONObject w = words.getJSONObject(i);
                if (!"[unk]".equals(w.optString("word"))) {
                    sum += w.optDouble("conf", 1.0);
                    count++;
                }
            }
            return new Hypothesis(text, count == 0 ? 0 : sum / count);
        } catch (JSONException e) {
            Log.w(TAG, "Resultado de Vosk ilegible: " + json, e);
            return new Hypothesis("", 0);
        }
    }
}
