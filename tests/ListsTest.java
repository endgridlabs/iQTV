package com.endgrid.iqtv;

import java.util.Arrays;
import java.util.List;

/**
 * Tests for the plain logic EndGrid iQTV+'s preferences and picks rely on (Lists): no Android needed, run on a
 * computer with tools/test.sh. A failing check prints what it expected and exits with an error.
 */
public final class ListsTest {
    private static int failures, checks;

    public static void main(String[] args) {
        splitAndJoin();
        moving();
        titles();
        ranking();
        System.out.println(checks + " checks, " + failures + " failed");
        if (failures > 0) System.exit(1);
    }

    private static void splitAndJoin() {
        eq(Arrays.asList("ta", "en"), Lists.split(" ta, en,,ta "), "split trims, drops empty parts and repeats");
        eq(Arrays.asList(), Lists.split(""), "split of nothing is empty");
        eq(Arrays.asList(), Lists.split(null), "split of null is empty");
        eq("ta,en", Lists.join(Arrays.asList("ta", "en")), "join");
        eq(Lists.split("a,b,c"), Lists.split(Lists.join(Lists.split("a,b,c"))), "split(join(x)) == x");
    }

    private static void moving() {
        List<String> rows = Arrays.asList("continue", "recent", "featured");
        eq(Arrays.asList("recent", "continue", "featured"), Lists.move(rows, "recent", -1), "move up");
        eq(Arrays.asList("continue", "featured", "recent"), Lists.move(rows, "recent", 1), "move down");
        eq(rows, Lists.move(rows, "continue", -1), "the first can't move up");
        eq(rows, Lists.move(rows, "featured", 1), "the last can't move down");
        eq(rows, Lists.move(rows, "missing", 1), "an unknown item changes nothing");
    }

    private static void titles() {
        eq("ponniyin selvan part ii", Lists.norm("Ponniyin Selvan: Part II"), "norm drops punctuation and case");
        eq("vikram", Lists.norm("  VIKRAM!! "), "norm trims");
        yes(Lists.sameTitle("Suzhal", "Suzhal - The Vortex"), "a series played under its short name");
        yes(Lists.sameTitle("Jailer", "jailer"), "case doesn't matter");
        no(Lists.sameTitle("Leo", "Leo Das"), "names under 5 letters must match whole (Leo ≠ Leo Das)");
        // The known trade-off: a name of 5+ letters matches longer names that start with it as a word.
        yes(Lists.sameTitle("Vikram Vedha", "Vikram"), "5+ letters: a word-boundary prefix matches");
        no(Lists.sameTitle("The Resolution", "Resolution"), "not a prefix at a word boundary");
        no(Lists.sameTitle("", "Anything"), "nothing matches nothing");
        yes(Lists.sameTitle("Kaithi 2", "Kaithi 2"), "numbers count");
    }

    private static void ranking() {
        List<String> langs = Arrays.asList("ta", "en");
        eq(0, Lists.rank(langs, "ta"), "first preference");
        eq(1, Lists.rank(langs, "en"), "second preference");
        eq(2, Lists.rank(langs, "hi"), "unknown after all known");
    }

    private static void eq(Object expected, Object actual, String what) {
        checks++;
        if (!expected.equals(actual)) {
            failures++;
            System.out.println("FAIL " + what + ": expected " + expected + ", got " + actual);
        }
    }

    private static void yes(boolean v, String what) { eq(true, v, what); }
    private static void no(boolean v, String what) { eq(false, v, what); }
}
