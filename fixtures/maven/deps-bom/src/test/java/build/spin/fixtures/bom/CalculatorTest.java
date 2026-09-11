package build.spin.fixtures.bom;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CalculatorTest {

    @Test
    void add() {
        assertEquals(5, new Calculator().add(2, 3));
    }

    @Test
    void bomManagedDepsResolveToTheVersionPinnedByTheBom() {
        // jackson-databind and jackson-core are declared without <version> tags; both must resolve
        // to 2.17.2, the version pinned by the imported jackson-bom (confirmed via
        // `mvn dependency:tree`). A build tool that skips BOM processing and falls back to some
        // other default/latest version would put a different jar on the classpath.
        assertTrue(jarLocationOf(ObjectMapper.class).contains("jackson-databind-2.17.2.jar"));
        assertTrue(jarLocationOf(JsonFactory.class).contains("jackson-core-2.17.2.jar"));
    }

    private static String jarLocationOf(final Class<?> type) {
        return type.getProtectionDomain().getCodeSource().getLocation().toString();
    }
}
