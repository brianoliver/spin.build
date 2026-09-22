package build.spin.module.maven;

/*-
 * #%L
 * Spin Maven Module
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

import java.util.Base64;
import java.util.Optional;

/**
 * A remote Maven repository resolved from {@code ~/.m2/settings.xml} (or the Maven Central
 * default), with any {@code <mirror>} substitution already applied.
 */
record RemoteRepo(String id, String url, Optional<String> authHeader, Optional<String> snapshotUpdatePolicy,
                  Optional<String> releaseUpdatePolicy) {

    static RemoteRepo of(final String id, final String url) {
        return new RemoteRepo(id, url, Optional.empty(), Optional.empty(), Optional.empty());
    }

    static RemoteRepo of(final String id, final String url, final String updatePolicy) {
        return new RemoteRepo(id, url, Optional.empty(), Optional.ofNullable(updatePolicy), Optional.empty());
    }

    static RemoteRepo of(final String id, final String url,
                         final String username, final String password) {
        return of(id, url, null, username, password);
    }

    static RemoteRepo of(final String id, final String url, final String updatePolicy,
                         final String username, final String password) {
        return of(id, url, updatePolicy, null, username, password);
    }

    static RemoteRepo of(final String id, final String url, final String snapshotUpdatePolicy,
                         final String releaseUpdatePolicy, final String username, final String password) {
        final String encoded = Base64.getEncoder()
            .encodeToString((username + ":" + password).getBytes());
        return new RemoteRepo(id, url, Optional.of("Basic " + encoded),
            Optional.ofNullable(snapshotUpdatePolicy), Optional.ofNullable(releaseUpdatePolicy));
    }
}
