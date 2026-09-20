package build.spin;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link Project}.
 */
class ProjectTests {

    /**
     * Verifies that {@link Project#qualifiedName()} joins the {@link Workspace}'s {@link Project#name()}
     * with this {@link Project}'s {@link Project#path()} relative to the {@link Workspace}'s own
     * {@link Project#path()}, using {@code /} regardless of the platform path separator.
     */
    @Test
    void qualifiedNameJoinsWorkspaceNameWithRelativePath() {
        final Workspace workspace = mock(Workspace.class);
        when(workspace.name()).thenReturn("jeffrey");
        when(workspace.path()).thenReturn(Path.of("/repo"));

        final Project project = mock(Project.class, CALLS_REAL_METHODS);
        when(project.workspace()).thenReturn(workspace);
        when(project.path()).thenReturn(Path.of("/repo/shared/ui/version"));

        assertThat(project.qualifiedName()).isEqualTo("jeffrey/shared/ui/version");
    }

    /**
     * Verifies that {@link Project#qualifiedName()} for the {@link Workspace} itself (relative path is
     * empty) is just the {@link Workspace}'s {@link Project#name()}, with no trailing separator.
     */
    @Test
    void qualifiedNameOfTheWorkspaceItselfIsJustItsName() {
        final Workspace workspace = mock(Workspace.class, CALLS_REAL_METHODS);
        when(workspace.name()).thenReturn("jeffrey");
        when(workspace.path()).thenReturn(Path.of("/repo"));
        when(workspace.workspace()).thenReturn(workspace);

        assertThat(workspace.qualifiedName()).isEqualTo("jeffrey");
    }
}
