package build.spin.fixtures.optional;

import com.google.common.collect.ImmutableList;

public class Calculator {

    public int add(int a, int b) {
        return a + b;
    }

    public int multiply(int a, int b) {
        return a * b;
    }

    public int sum(int... values) {
        int total = 0;
        for (final int value : ImmutableList.copyOf(box(values))) {
            total += value;
        }
        return total;
    }

    private static Integer[] box(final int[] values) {
        final Integer[] boxed = new Integer[values.length];
        for (int i = 0; i < values.length; i++) {
            boxed[i] = values[i];
        }
        return boxed;
    }
}
