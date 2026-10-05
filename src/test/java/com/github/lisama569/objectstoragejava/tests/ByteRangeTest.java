package com.github.lisama569.objectstoragejava.tests;

import com.github.lisama569.objectstoragejava.exception.InvalidRangeException;
import com.github.lisama569.objectstoragejava.exception.RangeNotSatisfiableException;
import com.github.lisama569.objectstoragejava.service.ByteRange;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Range parsing contract:
 * the three common single-range spellings are accepted; malformed headers are distinguishable
 * (400 invalid_range) from well-formed but unsatisfiable ranges (416 range_not_satisfiable).
 */
class ByteRangeTest {

    @Test
    void parsesStartEndForm() {
        ByteRange r = ByteRange.parse("bytes=2-5", 100);
        assertEquals(2, r.start());
        assertEquals(5, r.end());
        assertEquals(4, r.length());
        System.out.println("[range] bytes=2-5 of 100 -> " + r + " length=" + r.length());
    }

    @Test
    void clampsEndBeyondSizeInsteadOfFailing() {
        ByteRange r = ByteRange.parse("bytes=90-500", 100);
        assertEquals(90, r.start());
        assertEquals(99, r.end());
        System.out.println("[range] bytes=90-500 of 100 clamped -> " + r);
    }

    @Test
    void parsesOpenEndedStartOnlyForm() {
        ByteRange r = ByteRange.parse("bytes=7-", 10);
        assertEquals(7, r.start());
        assertEquals(9, r.end());
        assertEquals(3, r.length());
        System.out.println("[range] bytes=7- of 10 -> " + r);
    }

    @Test
    void parsesSuffixFromEndForm() {
        ByteRange r = ByteRange.parse("bytes=-4", 10);
        assertEquals(6, r.start());
        assertEquals(9, r.end());
        assertEquals(4, r.length());
        System.out.println("[range] bytes=-4 (last 4 of 10) -> " + r);
    }

    @Test
    void suffixLargerThanObjectServesWholeObject() {
        ByteRange r = ByteRange.parse("bytes=-1000", 5);
        assertEquals(0, r.start());
        assertEquals(4, r.end());
        assertEquals(5, r.length());
        System.out.println("[range] bytes=-1000 of 5 -> whole object " + r);
    }

    @Test
    void startOffsetBeyondSizeIsUnsatisfiable416() {
        RangeNotSatisfiableException ex = assertThrows(RangeNotSatisfiableException.class,
                () -> ByteRange.parse("bytes=100-200", 100));
        assertTrue(ex.getMessage().contains("not satisfiable"));
        System.out.println("[range-reject] bytes=100-200 of 100 -> 416: " + ex.getMessage());
    }

    @Test
    void anyRangeOnEmptyObjectIsUnsatisfiable416() {
        assertThrows(RangeNotSatisfiableException.class, () -> ByteRange.parse("bytes=0-0", 0));
        assertThrows(RangeNotSatisfiableException.class, () -> ByteRange.parse("bytes=-1", 0));
        System.out.println("[range-reject] ranges on size=0 -> 416 range_not_satisfiable");
    }

    @Test
    void malformedRangeHeadersAreInvalid400() {
        String[] bad = {
                null, "", "   ", "items=0-3", "bytes=abc-def", "bytes=-",
                "bytes=5-3", "bytes=-0", "bytes=0-1,2-3", "bytes=10"
        };
        for (String header : bad) {
            InvalidRangeException ex = assertThrows(InvalidRangeException.class,
                    () -> ByteRange.parse(header, 100), "header must be rejected: '" + header + "'");
            System.out.println("[range-reject] '" + header + "' -> 400 invalid_range: " + ex.getMessage());
        }
    }

    @Test
    void openEndedZeroStartIsValid() {
        ByteRange r = ByteRange.parse("bytes=0-", 100);
        assertEquals(0, r.start());
        assertEquals(99, r.end());
        System.out.println("[range] bytes=0- of 100 -> " + r);
    }
}
