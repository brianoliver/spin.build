/*-
 * #%L
 * Spin Jar Module
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

module build.spin.module.jar {
    requires build.spin.common;
    requires transitive build.spin.module.java;
    requires transitive build.spin.module.modulesystem;

    requires build.base.archiving;
    requires build.base.foundation;
    requires build.base.io;
    requires build.base.option;
    requires build.base.version;
    requires build.codemodel.dependency.injection;
    requires build.codemodel.jdk;
    requires build.spin;
    requires build.spin.module.clean;
    requires build.spin.module.gpg;
    requires jakarta.inject;

    opens build.spin.module.jar to build.codemodel.dependency.injection;

    exports build.spin.module.jar;

    provides build.spin.Extension.MetaClass with
        build.spin.module.jar.JarPlugin.MetaClass;

}
