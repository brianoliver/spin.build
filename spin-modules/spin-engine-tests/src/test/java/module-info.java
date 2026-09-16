/*-
 * #%L
 * Spin Engine Tests
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
module build.spin.engine.tests.test {
    requires build.spin;
    requires build.spin.common;
    requires build.spin.testing;
    requires build.spin.module.configuration;
    requires build.spin.module.clean;
    requires org.junit.jupiter.api;
    requires org.assertj.core;

    opens build.spin.engine.tests to org.junit.platform.commons, build.codemodel.dependency.injection;

    exports build.spin.engine.tests to build.codemodel.dependency.injection, build.spin;

    provides build.spin.Extension.MetaClass with
        build.spin.engine.tests.CyclicTestPlugin.MetaClass,
        build.spin.engine.tests.PreProcessTestPlugin.MetaClass,
        build.spin.engine.tests.FailFastTestPlugin.MetaClass,
        build.spin.engine.tests.ExecutionSlotBoundTestPlugin.MetaClass,
        build.spin.engine.tests.AfterTestPlugin.MetaClass,
        build.spin.engine.tests.AfterDependencyTestPlugin.MetaClass,
        build.spin.engine.tests.BeforeDependencyTestPlugin.MetaClass,
        build.spin.engine.tests.CodependencyRaceTestPlugin.MetaClass,
        build.spin.engine.tests.PreProcessCodependencyForcingDependencyTestPlugin.MetaClass,
        build.spin.engine.tests.PostProcessCodependencyForcingDependencyTestPlugin.MetaClass,
        build.spin.engine.tests.NestedCodependencyForcingDependencyTestPlugin.MetaClass,
        build.spin.engine.tests.CodependencyInstantiationCountTestPlugin.MetaClass,
        build.spin.engine.tests.CodependencyOrderTestPlugin.MetaClass,
        build.spin.engine.tests.NestedCodependencyTestPlugin.MetaClass,
        build.spin.engine.tests.WorkspaceDetectionTestPlugin.MetaClass,
        build.spin.engine.tests.FederatedTestPlugin.MetaClass,
        build.spin.engine.tests.ConfigurationChildProjectTestPlugin.MetaClass;
}
