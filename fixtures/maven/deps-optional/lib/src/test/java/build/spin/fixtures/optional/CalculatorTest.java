package build.spin.fixtures.optional;

import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class CalculatorTest {

    @Test
    void add() {
        assertEquals(5, new Calculator().add(2, 3));
    }

    @Test
    void optionalDepIsAvailableInThisProject() {
        // optional=true only prevents the dep being inherited by consumers;
        // it is still fully available in this project at compile and test time
        assertNotNull(ImmutableList.of(1, 2, 3));
    }
}
