/*-
 * #%L
 * Spin Java Module Tests
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
/**
 * Compiles cleanly on its own, but triggers javac's deprecation {@code Note:} -- alongside
 * {@link Broken}'s compile error in the same javac invocation, so a test can assert that a
 * warning is streamed live and a real error is not, without needing two separate fixtures.
 *
 * @since Sep-2026
 */
public class UsesDeprecated {
    static java.util.Date example() {
        return new java.util.Date(124, 0, 1);
    }
}
