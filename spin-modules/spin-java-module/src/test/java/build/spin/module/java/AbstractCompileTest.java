package build.spin.module.java;

/*-
 * #%L
 * Spin Java Module
 * %%
 * Copyright (C) 2026 Workday, Inc.
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import build.base.foundation.Capture;
import build.base.foundation.UniformResource;
import build.base.telemetry.Telemetry;
import build.base.telemetry.TelemetryRecorder;
import build.base.telemetry.Warning;
import build.spin.common.telemetry.TelemetryPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AbstractCompileTest {

    @TempDir
    Path tempDir;

    @Test
    void emptySourceResult_createsTargetDirectory() throws Exception {
        final Path target = tempDir.resolve("classes");

        AbstractCompile.emptySourceResult(target);

        assertThat(target).isDirectory();
    }

    @Test
    void emptySourceResult_returnsPathSetContainingTarget() throws Exception {
        final Path target = tempDir.resolve("classes");

        final var result = AbstractCompile.emptySourceResult(target);

        assertThat(result).containsExactly(target);
    }

    @Test
    void emptySourceResult_targetAlreadyExists_doesNotThrow() throws Exception {
        final Path target = tempDir.resolve("classes");
        Files.createDirectories(target);

        assertThat(AbstractCompile.emptySourceResult(target)).containsExactly(target);
    }

    // --- flushError ---
    //
    // javac reports source paths absolutely; flushError strips the project root and routes the
    // trailing line to warn vs error. Errors are appended to the captured failure output only, not
    // streamed live via recorder.error() -- the captured output is embedded verbatim in the eventual
    // ProcessFailedException, so streaming here too would print every diagnostic twice. It clears the
    // Capture unconditionally, so a second call for the same flush is a no-op.

    @Test
    void flushError_pendingErrorLine_appendsToCapturedWithoutStreaming() {
        final List<Telemetry> emitted = new ArrayList<>();
        final Capture<String> error = Capture.of(tempDir.resolve("Foo.java") + ": error: cannot find symbol");
        final ErrorCapture captured = new ErrorCapture();

        AbstractCompile.flushError(error, captured, tempDir, capturingRecorder(emitted));

        assertThat(emitted).isEmpty();
        assertThat(captured.output()).isEqualTo("Foo.java: error: cannot find symbol");
        assertThat(error.isPresent()).isFalse();
    }

    @Test
    void flushError_pendingWarningLine_logsWarnAndDoesNotAppendToCaptured() {
        final List<Telemetry> emitted = new ArrayList<>();
        final Capture<String> error = Capture.of("Foo.java: warning: [deprecation] Bar in Baz has been deprecated");
        final ErrorCapture captured = new ErrorCapture();

        AbstractCompile.flushError(error, captured, tempDir, capturingRecorder(emitted));

        assertThat(emitted).hasSize(1);
        assertThat(emitted.get(0)).isInstanceOf(Warning.class);
        assertThat(captured.output()).isEmpty();
    }

    @Test
    void flushError_noPendingLine_doesNothing() {
        final List<Telemetry> emitted = new ArrayList<>();

        AbstractCompile.flushError(Capture.empty(), new ErrorCapture(), tempDir, capturingRecorder(emitted));

        assertThat(emitted).isEmpty();
    }

    @Test
    void flushError_calledTwiceForSameFlush_onlyAppendsOnce() {
        final List<Telemetry> emitted = new ArrayList<>();
        final Capture<String> error = Capture.of("Foo.java: error: boom");
        final ErrorCapture captured = new ErrorCapture();

        AbstractCompile.flushError(error, captured, tempDir, capturingRecorder(emitted));
        AbstractCompile.flushError(error, captured, tempDir, capturingRecorder(emitted));

        assertThat(emitted).isEmpty();
        assertThat(captured.output()).isEqualTo("Foo.java: error: boom");
    }

    private static TelemetryRecorder capturingRecorder(final List<Telemetry> emitted) {
        return new TelemetryPublisher(
            UniformResource.createURI("test", "AbstractCompileTest"),
            emitted::add);
    }
}
