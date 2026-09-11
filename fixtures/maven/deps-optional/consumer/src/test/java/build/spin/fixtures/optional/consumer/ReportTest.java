package build.spin.fixtures.optional.consumer;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReportTest {

    @Test
    void total() {
        assertEquals(5, new Report().total(2, 3));
    }

    @Test
    void guavaDidNotPropagateFromTheOptionalDependency() {
        // lib declares guava as <optional>true</optional>; consumers of lib must not inherit it.
        assertThrows(ClassNotFoundException.class,
            () -> Class.forName("com.google.common.collect.ImmutableList"));
    }
}
