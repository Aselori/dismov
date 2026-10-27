package com.dismov.safetyrecorder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

public class PhraseMatcherTest {

    private static final Map<String, List<String>> VOCABULARY = PhraseMatcher.indexVocabulary(new HashSet<>(
            Arrays.asList("<eps>", "[unk]", "ayúdame", "ayudame", "por", "favor", "año", "ano", "nuevo", "luna")));

    @Test
    public void normalizeRemovesAccentsButKeepsEnye() {
        assertEquals("ayudame aguero ano", PhraseMatcher.normalize("AYÚDAME Agüero Ano"));
        assertEquals("año", PhraseMatcher.normalize("Año"));
    }

    @Test
    public void wordsSplitsOnPunctuationAndSpaces() {
        assertEquals(Arrays.asList("ayúdame", "por", "favor"), PhraseMatcher.words("  ¡Ayúdame, por   favor!  "));
    }

    @Test
    public void validPhraseUsesEveryVocabularySpelling() {
        PhraseMatcher.Validation v = PhraseMatcher.validate("Ayudame por favor", VOCABULARY);
        assertTrue(v.isValid());
        assertEquals(Arrays.asList("ayudame", "por", "favor"), v.words);
        assertEquals("[\"ayudame por favor\", \"ayúdame por favor\", \"[unk]\"]", v.grammar);
    }

    @Test
    public void enyeIsNotConfusedWithN() {
        PhraseMatcher.Validation v = PhraseMatcher.validate("año nuevo", VOCABULARY);
        assertEquals("[\"año nuevo\", \"[unk]\"]", v.grammar);
    }

    @Test
    public void rejectsTooShortTooLongAndUnknownWords() {
        assertEquals(PhraseMatcher.Validation.Problem.TOO_SHORT, PhraseMatcher.validate("luna", VOCABULARY).problem);
        assertEquals(PhraseMatcher.Validation.Problem.TOO_SHORT, PhraseMatcher.validate("  ", VOCABULARY).problem);
        assertEquals(PhraseMatcher.Validation.Problem.TOO_LONG,
                PhraseMatcher.validate("luna luna luna luna luna luna luna", VOCABULARY).problem);

        PhraseMatcher.Validation v = PhraseMatcher.validate("luna xyzzy plof", VOCABULARY);
        assertEquals(PhraseMatcher.Validation.Problem.UNKNOWN_WORDS, v.problem);
        assertEquals(Arrays.asList("xyzzy", "plof"), v.unknownWords);
    }

    @Test
    public void matchesFullPhraseInOrderIgnoringAccentsAndNoise() {
        List<String> phrase = Arrays.asList("ayudame", "por", "favor");
        assertTrue(PhraseMatcher.matches("ayúdame por favor", phrase));
        assertTrue(PhraseMatcher.matches("[unk] ayudame por favor [unk]", phrase));
    }

    @Test
    public void doesNotMatchPartialReorderedOrInterruptedPhrase() {
        List<String> phrase = Arrays.asList("ayudame", "por", "favor");
        assertFalse(PhraseMatcher.matches("por favor", phrase));
        assertFalse(PhraseMatcher.matches("por favor ayúdame", phrase));
        assertFalse(PhraseMatcher.matches("ayúdame [unk] por favor", phrase));
        assertFalse(PhraseMatcher.matches("", phrase));
        assertFalse(PhraseMatcher.matches("ayúdame por favor", List.of()));
    }

    @Test
    public void grammarIsCappedWhenManySpellingsCombine() {
        List<List<String>> forms = Arrays.asList(
                Arrays.asList("a", "á"), Arrays.asList("b", "b2"), Arrays.asList("c", "c2"),
                Arrays.asList("d", "d2"), Arrays.asList("e", "e2"));
        String grammar = PhraseMatcher.grammarJson(forms);
        assertEquals(16 + 1, grammar.split("\", \"").length);
    }
}
