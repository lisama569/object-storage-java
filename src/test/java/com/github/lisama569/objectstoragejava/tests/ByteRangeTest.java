package com.github.lisama569.objectstoragejava.tests;

import com.github.lisama569.objectstoragejava.exception.InvalidRangeException;
import com.github.lisama569.objectstoragejava.service.ByteRange;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Range-header parsing: the three accepted forms and distinguishable failures. */
class ByteRangeTest {

    private static final long SIZE = 100; // bytes 0..99

    @Test
    void parsesAllThreeAcceptedForms() {
        // start-end
        ByteRange both = ByteRange.parse("bytes=10-20", SIZE);
        assertEquals(10, both.start());
        assertEquals(20, both.end());
        assertEquals(11, both.length());

        // open-ended: start-
        ByteRange open = ByteRange.parse("bytes=90-", SIZE);
        assertEquals(90, open.start());
        assertEquals(99, open.end());

        // suffix: -N (last N bytes)
        ByteRange suffix = ByteRange.parse("bytes=-25", SIZE);
        assertEquals(75, suffix.start());
        assertEquals(99, suffix.end());

        // suffix larger than the object simply starts at 0
        ByteRange suffixBig = ByteRange.parse("bytes=-1000", SIZE);
        assertEquals(0, suffixBig.start());
        assertEquals(99, suffixBig.end());

        // whitespace tolerated; missing header means full object
        assertEquals(5, ByteRange.parse(" bytes= 5 - 9 ", SIZE).length());
        assertNull(ByteRange.parse(null, SIZE));
        assertNull(ByteRange.parse("  ", SIZE));

        System.out.printf("[range] forms accepted: %s / %s / %s%n", both, open, suffix);
    }

    @Test
    void rejectsMalformedRanges() {
        String[] bad = {
                "items=0-9",       // wrong unit
                "bytes=0-9,10-19", // multiple ranges unsupported
                "bytes=",          // empty spec
                "bytes=0",         // missing dash
                "bytes=abc-9",     // non-numeric
                "bytes=-",         // no bounds at all
                "bytes=9-5",       // end before start
                "bytes=--1",       // negative-ish
        };
        for (String header : bad) {
            InvalidRangeException ex = assertThrows(InvalidRangeException.class,
                    () -> ByteRange.parse(header, SIZE), "must reject: " + header);
            assertTrue(ex.getMessage().contains("Malformed"), "syntax error distinguished, got: " + ex.getMessage());
        }
        System.out.println("[range] malformed headers -> invalid_range (malformed)");
    }

    @Test
    void rejectsUnsatisfiableRangesInsteadOfReturningEmptyOrWholeObject() {
        InvalidRangeException a = assertThrows(InvalidRangeException.class,
                () -> ByteRange.parse("bytes=100-200", SIZE));
        assertTrue(a.getMessage().contains("not satisfiable"), a.getMessage());

        InvalidRangeException b = assertThrows(InvalidRangeException.class,
                () -> ByteRange.parse("bytes=50-100", SIZE));
        assertTrue(b.getMessage().contains("not satisfiable"), b.getMessage());

        InvalidRangeException c = assertThrows(InvalidRangeException.class,
                () -> ByteRange.parse("bytes=-0", SIZE));
        assertTrue(c.getMessage().contains("not satisfiable"), c.getMessage());

        InvalidRangeException d = assertThrows(InvalidRangeException.class,
                () -> ByteRange.parse("bytes=0-", 0));
        assertTrue(d.getMessage().contains("empty"), d.getMessage());

        System.out.println("[range] out-of-bounds/empty -> invalid_range (not satisfiable); never empty, never whole object");
    }
}
