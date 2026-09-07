/*-
 * #%L
 * Spin Application
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

module build.spin.application {
    requires transitive build.spin;
    requires transitive build.spin.common;
    requires transitive build.spin.engine;
    requires transitive build.spin.module.checkstyle;
    requires transitive build.spin.module.clean;
    requires transitive build.spin.module.configuration;
    requires transitive build.spin.module.git;
    requires transitive build.spin.module.gpg;
    requires transitive build.spin.module.jar;
    requires transitive build.spin.module.java;
    requires transitive build.spin.module.junit;
    requires transitive build.spin.module.maven;
    requires transitive build.spin.module.modulesystem;
    requires transitive build.spin.module.reporting;

    requires build.base.commandline;
    requires build.base.configuration;
    requires build.base.foundation;
    requires build.base.option;
    requires build.base.table;

    exports build.spin.application;

}
