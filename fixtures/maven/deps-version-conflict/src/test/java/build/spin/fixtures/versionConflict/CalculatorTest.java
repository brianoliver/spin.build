package build.spin.fixtures.versionConflict;

import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CalculatorTest {

    @Test
    void add() {
        assertEquals(5, new Calculator().add(2, 3));
    }

    @Test
    void nearestWinsGuavaVersionIsResolved() {
        // Maven's nearest-wins mediation resolves this pom's two direct guava declarations
        // to 33.2.1-jre (confirmed via `mvn dependency:tree`); a build tool that mediates
        // differently (e.g. highest-wins) would silently put a different jar on the classpath.
        final String jarLocation =
            ImmutableList.class.getProtectionDomain().getCodeSource().getLocation().toString();
        assertTrue(jarLocation.contains("guava-33.2.1-jre.jar"),
            "expected guava-33.2.1-jre.jar on the classpath, was: " + jarLocation);
    }
}
