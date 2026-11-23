package io.github.xdstorage.properties;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * PROPTEST: the SEED-BASED model — every entity is a pure function of its integer seed
 * (the jqwik Arbitrary shrinks the seed; the failing seed reproduces exactly), and the
 * field-by-field comparator is the integrity gate (a property that cannot prove it
 * loaded the right data proves nothing — the house rule).
 *
 * <p>The value ranges avoid the known text-format edges ON PURPOSE (disclosed): doubles are
 * finite (NaN/Infinity have no YAML scalar form), map KEYS are non-null (the documented
 * behavior: null map keys are dropped on clone and skipped on read — pinned separately in
 * Этап 2). Strings, on the contrary, deliberately CONTAIN what the format used to lose
 * (YAMLSTR, docs/tasks/2.2.0/40): line breaks, tabs, backslashes and the four characters
 * {@code null} — as field values, list elements, map keys and map values.
 */
public final class PropModel {

    private PropModel() {
    }

    public static PropWide wide(final int seed) {
        final PropWide e = new PropWide();
        populate(e, seed);
        return e;
    }

    public static PropWideBTree wideBTree(final int seed) {
        final PropWideBTree e = new PropWideBTree();
        populate(e, seed);
        return e;
    }

    public static PropSingle single(final int seed) {
        final PropSingle e = new PropSingle();
        populate(e, seed);
        return e;
    }

    /** Fills the shared shape from the seed (identical values for identical seeds). */
    private static void populate(final PropWideLike e, final int seed) {
        final Random rnd = new Random(seed * 7919L + 13L);
        e.setId("e" + seed);
        e.setName(string(rnd, "name"));
        e.setAmount(rnd.nextLong());
        e.setCount(rnd.nextInt());
        e.setRatio(finiteDouble(rnd));
        e.setActive(rnd.nextBoolean());
        e.setCreated(new Date(Math.abs(rnd.nextLong() % 4_000_000_000_000L)));
        e.setDecimal(new BigDecimal(String.valueOf(rnd.nextInt(1_000_000)) + "." + Math.abs(rnd.nextInt(1_000_000))));
        e.setTags(listOfStrings(rnd, rnd.nextInt(5)));
        e.setMeta(mapOfStrings(rnd, rnd.nextInt(4)));
        e.setLabels(stringArray(rnd, rnd.nextInt(4)));
        if (rnd.nextBoolean()) {
            e.setNested(new PropNested(string(rnd, "nested"), rnd.nextLong(), longList(rnd, rnd.nextInt(4))));
        }
        // else: the nested field stays null — the honest-null round trip
    }

    /** Line breaks, a tab and a backslash are part of the alphabet on purpose (YAMLSTR, task 40). */
    private static final String ALPHABET = "abcXYZ019 _-.,:;!?()[]{}'\"+=%@#$ъЙЖёФЫв \n\r\t\\";

    private static String string(final Random rnd, final String prefix) {
        // YAMLSTR (docs/tasks/2.2.0/40): the literal "null" — it used to read back as a null
        final int pick = rnd.nextInt(40);
        if (pick == 0) {
            return "null";
        }
        final int len = rnd.nextInt(40);
        final StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(ALPHABET.charAt(rnd.nextInt(ALPHABET.length())));
        }
        return sb.length() == 0 ? prefix : sb.toString();
    }

    private static double finiteDouble(final Random rnd) {
        return (rnd.nextDouble() - 0.5) * Math.pow(10, rnd.nextInt(20) - 5);
    }

    private static List<String> listOfStrings(final Random rnd, final int size) {
        final List<String> list = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            list.add(rnd.nextInt(6) == 0 ? null : string(rnd, "tag")); // null elements are legal
        }
        return list;
    }

    private static List<Long> longList(final Random rnd, final int size) {
        final List<Long> list = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            list.add(rnd.nextLong());
        }
        return list;
    }

    /** Map keys stay non-null (the null-KEY edge is a documented drop, pinned separately). */
    private static Map<String, String> mapOfStrings(final Random rnd, final int size) {
        final Map<String, String> map = new HashMap<>();
        for (int i = 0; i < size; i++) {
            map.put("k" + i + string(rnd, ""), rnd.nextInt(6) == 0 ? null : string(rnd, "v"));
        }
        if (size > 0) {
            // YAMLSTR (docs/tasks/2.2.0/40): the STRING "null" as a key — the entry used to be dropped
            map.put("null", string(rnd, "v"));
        }
        return map;
    }

    private static String[] stringArray(final Random rnd, final int size) {
        final String[] arr = new String[size];
        for (int i = 0; i < size; i++) {
            arr[i] = string(rnd, "lbl");
        }
        return arr;
    }

    /** The field-by-field integrity gate: both sides are built from the same seed. */
    public static void assertFieldEquals(final PropWideLike expected, final PropWideLike actual, final String what) {
        final List<String> problems = new ArrayList<>();
        if (!eq(expected.getId(), actual.getId())) {
            problems.add("id: " + expected.getId() + " vs " + actual.getId());
        }
        if (!eq(expected.getName(), actual.getName())) {
            problems.add("name: '" + expected.getName() + "' vs '" + actual.getName() + "'");
        }
        if (!eq(expected.getAmount(), actual.getAmount())) {
            problems.add("amount: " + expected.getAmount() + " vs " + actual.getAmount());
        }
        if (!eq(expected.getCount(), actual.getCount())) {
            problems.add("count: " + expected.getCount() + " vs " + actual.getCount());
        }
        if (!eq(expected.getRatio(), actual.getRatio())) {
            problems.add("ratio: " + expected.getRatio() + " vs " + actual.getRatio());
        }
        if (!eq(expected.getActive(), actual.getActive())) {
            problems.add("active: " + expected.getActive() + " vs " + actual.getActive());
        }
        if (!eq(expected.getCreated(), actual.getCreated())) {
            problems.add("created: " + expected.getCreated() + " vs " + actual.getCreated());
        }
        if (!eq(expected.getDecimal(), actual.getDecimal())) {
            problems.add("decimal: " + expected.getDecimal() + " vs " + actual.getDecimal());
        }
        if (!eq(expected.getTags(), actual.getTags())) {
            problems.add("tags: " + expected.getTags() + " vs " + actual.getTags());
        }
        if (!eq(expected.getMeta(), actual.getMeta())) {
            problems.add("meta: " + expected.getMeta() + " vs " + actual.getMeta());
        }
        if (!eq(expected.getLabels(), actual.getLabels())) {
            problems.add("labels: " + java.util.Arrays.toString(expected.getLabels()) + " vs "
                    + java.util.Arrays.toString(actual.getLabels()));
        }
        if (expected.getNested() == null) {
            if (actual.getNested() != null) {
                problems.add("nested: expected null, got " + actual.getNested().getTitle());
            }
        } else if (actual.getNested() == null) {
            problems.add("nested: expected " + expected.getNested().getTitle() + ", got null");
        } else {
            if (!eq(expected.getNested().getTitle(), actual.getNested().getTitle())) {
                problems.add("nested.title: " + expected.getNested().getTitle() + " vs " + actual.getNested()
                        .getTitle());
            }
            if (!eq(expected.getNested().getWeight(), actual.getNested().getWeight())) {
                problems.add("nested.weight: " + expected.getNested().getWeight() + " vs " + actual.getNested()
                        .getWeight());
            }
            if (!eq(expected.getNested().getFlags(), actual.getNested().getFlags())) {
                problems.add("nested.flags: " + expected.getNested().getFlags() + " vs " + actual.getNested()
                        .getFlags());
            }
        }
        if (!problems.isEmpty()) {
            throw new AssertionError(what + " diverges on seed " + expected.getId() + ": " + problems);
        }
    }

    private static boolean eq(final Object a, final Object b) {
        if (a == null || b == null) {
            return a == b; // both null = equal
        }
        if (a instanceof java.util.List && b instanceof java.util.List) {
            return listEquals((java.util.List<?>) a, (java.util.List<?>) b);
        }
        if (a instanceof Object[] && b instanceof Object[]) {
            return java.util.Arrays.equals((Object[]) a, (Object[]) b);
        }
        return a.equals(b);
    }

    private static boolean listEquals(final List<?> a, final List<?> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            final Object x = a.get(i);
            final Object y = b.get(i);
            if (x == null || y == null) {
                if (x != y) {
                    return false;
                }
            } else if (!x.equals(y)) {
                return false;
            }
        }
        return true;
    }
}
