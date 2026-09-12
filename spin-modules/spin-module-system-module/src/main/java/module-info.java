/*-
 * #%L
 * Spin Module System Module
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

module build.spin.module.modulesystem {
    requires transitive build.base.version;
    requires build.base.foundation;
    requires build.base.io;
    requires build.base.parsing;
    requires build.base.telemetry;
    requires java.xml;
    requires build.codemodel.dependency.injection;
    requires build.codemodel.jdk;
    requires build.spin;
    requires build.spin.common;
    requires jakarta.inject;

    opens build.spin.module.modulesystem to build.codemodel.dependency.injection;
    opens build.spin.module.modulesystem.maven to build.codemodel.dependency.injection;
    opens build.spin.module.modulesystem.properties to build.codemodel.dependency.injection;

    exports build.spin.module.modulesystem;
    exports build.spin.module.modulesystem.pom;
    exports build.spin.module.modulesystem.maven;
    exports build.spin.module.modulesystem.properties;

    provides build.spin.Extension.MetaClass with
        build.spin.module.modulesystem.EmptyModuleCatalog.MetaClass,
        build.spin.module.modulesystem.EmptyModuleVersioning.MetaClass,
        build.spin.module.modulesystem.maven.PomBasedModuleCatalog.MetaClass,
        build.spin.module.modulesystem.maven.PomBasedCheckstyleArguments.MetaClass,
        build.spin.module.modulesystem.maven.PomBasedCompilerArguments.MetaClass,
        build.spin.module.modulesystem.maven.PomBasedJavadocArguments.MetaClass,
        build.spin.module.modulesystem.maven.PomBasedModuleVersioning.MetaClass,
        build.spin.module.modulesystem.maven.PomBasedTestArguments.MetaClass,
        build.spin.module.modulesystem.maven.PomBasedTestModuleDescriptor.MetaClass,
        build.spin.module.modulesystem.properties.PropertiesModuleCatalog.MetaClass,
        build.spin.module.modulesystem.properties.PropertiesModuleVersioning.MetaClass;

}
