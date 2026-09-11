package build.spin.fixtures.optional.consumer;

import build.spin.fixtures.optional.Calculator;

public class Report {

    public int total(int a, int b) {
        return new Calculator().add(a, b);
    }
}
