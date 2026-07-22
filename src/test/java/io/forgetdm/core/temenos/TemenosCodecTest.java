package io.forgetdm.core.temenos;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TemenosCodecTest {

    private static final String VM = String.valueOf(TemenosCodec.VM);
    private static final String SVM = String.valueOf(TemenosCodec.SVM);
    private static final String FM = String.valueOf(TemenosCodec.FM);

    @Test void detectsMarks() {
        assertFalse(TemenosCodec.hasMarkers("John"));
        assertFalse(TemenosCodec.hasMarkers(""));
        assertFalse(TemenosCodec.hasMarkers(null));
        assertTrue(TemenosCodec.hasMarkers("John" + VM + "Alexander"));
        assertTrue(TemenosCodec.hasMarkers("a" + SVM + "b"));
    }

    @Test void mapLeavesPreservesMarksAndCount() {
        // RFP §3.2.1 example: NAME.1=John, NAME.2=Alexander scrambled, delimiters re-injected exactly.
        String in = "John" + VM + "Alexander";
        String out = TemenosCodec.mapLeaves(in, String::toUpperCase);
        assertEquals("JOHN" + VM + "ALEXANDER", out);
        assertEquals(2, TemenosCodec.valueCount(out));
        // The single VM mark is still there, in the same place — no sub-value shift.
        assertEquals(1, countChar(out, TemenosCodec.VM));
    }

    @Test void emptySegmentsArePreservedNotDropped() {
        String in = "A" + VM + VM + "B"; // three values: A, <empty>, B
        String out = TemenosCodec.mapLeaves(in, s -> s + "!");
        assertEquals("A!" + VM + VM + "B!", out);
        assertEquals(3, TemenosCodec.valueCount(in));
    }

    @Test void nestedSubValuesRoundTripAndTransformPerLeaf() {
        // one value with two sub-values, plus a second value
        String in = "x" + SVM + "y" + VM + "z";
        String out = TemenosCodec.mapLeaves(in, String::toUpperCase);
        assertEquals("X" + SVM + "Y" + VM + "Z", out);
        assertEquals(1, countChar(out, TemenosCodec.SVM));
        assertEquals(1, countChar(out, TemenosCodec.VM));
    }

    @Test void fieldMarkHierarchyPreserved() {
        String in = "a" + VM + "b" + FM + "c" + SVM + "d";
        String out = TemenosCodec.mapLeaves(in, String::toUpperCase);
        assertEquals("A" + VM + "B" + FM + "C" + SVM + "D", out);
        assertEquals(1, countChar(out, TemenosCodec.FM));
        assertEquals(1, countChar(out, TemenosCodec.VM));
        assertEquals(1, countChar(out, TemenosCodec.SVM));
    }

    @Test void parseAndFormatRoundTrip() {
        String in = "a" + SVM + "b" + VM + "c" + FM + "d";
        List<List<List<String>>> parsed = TemenosCodec.parse(in);
        assertEquals(2, parsed.size());                 // two fields
        assertEquals(2, parsed.get(0).size());          // field 0 has two values
        assertEquals(List.of("a", "b"), parsed.get(0).get(0)); // value 0 has two sub-values
        assertEquals(in, TemenosCodec.format(parsed));  // exact round-trip
    }

    @Test void noMarksMeansSingleLeaf() {
        assertEquals("HELLO", TemenosCodec.mapLeaves("hello", String::toUpperCase));
        assertEquals(1, TemenosCodec.valueCount("hello"));
    }

    private static int countChar(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == c) n++;
        return n;
    }
}
