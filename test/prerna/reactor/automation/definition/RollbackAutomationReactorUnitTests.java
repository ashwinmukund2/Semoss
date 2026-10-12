/*******************************************************************************
 * Copyright 2015 Defense Health Agency (DHA)
 *
 * If your use of this software does not include any GPLv2 components:
 * 	Licensed under the Apache License, Version 2.0 (the "License");
 * 	you may not use this file except in compliance with the License.
 * 	You may obtain a copy of the License at
 *
 * 	  http://www.apache.org/licenses/LICENSE-2.0
 *
 * 	Unless required by applicable law or agreed to in writing, software
 * 	distributed under the License is distributed on an "AS IS" BASIS,
 * 	WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * 	See the License for the specific language governing permissions and
 * 	limitations under the License.
 * ----------------------------------------------------------------------------
 * If your use of this software includes any GPLv2 components:
 * 	This program is free software; you can redistribute it and/or
 * 	modify it under the terms of the GNU General Public License
 * 	as published by the Free Software Foundation; either version 2
 * 	of the License, or (at your option) any later version.
 *
 * 	This program is distributed in the hope that it will be useful,
 * 	but WITHOUT ANY WARRANTY; without even the implied warranty of
 * 	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * 	GNU General Public License for more details.
 *******************************************************************************/
package prerna.reactor.automation.definition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import prerna.auth.User;
import prerna.om.Insight;
import prerna.project.api.IProject;
import prerna.reactor.automation.AutomationConstants;
import prerna.reactor.automation.project.AutomationProjectService;
import prerna.reactor.automation.run.AutomationRunStore;
import prerna.reactor.automation.utils.AutomationRuntimeUtils;
import prerna.sablecc2.om.ReactorKeysEnum;
import prerna.sablecc2.om.nounmeta.NounMetadata;

/**
 * Template for reactor unit tests: mock {@link Insight}/{@link User}, feed
 * inputs directly into {@code keyValue} to skip Pixel parsing, and mock the
 * static utility classes the reactor calls so no real database is needed.
 */
class RollbackAutomationReactorUnitTests {

    private static final String RUN_ID_KEY = "runId";

    private RollbackAutomationReactor reactor;
    private Insight insight;
    private User user;

    @BeforeEach
    void setup() {
        reactor = new RollbackAutomationReactor();
        insight = mock(Insight.class);
        user = mock(User.class);
        reactor.setInsight(insight);
        when(insight.getUser()).thenReturn(user);
    }

    @Test
    void missingProjectIdThrows() {
        reactor.keyValue.put(RUN_ID_KEY, "run-1");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, reactor::execute);
        assertEquals("Required input(s) missing: project", error.getMessage());
    }

    @Test
    void missingRunIdThrows() {
        reactor.keyValue.put(ReactorKeysEnum.PROJECT.getKey(), "project-1");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, reactor::execute);
        assertEquals("Required input(s) missing: runId", error.getMessage());
    }

    @Test
    void accessDeniedPropagatesWhenUserLacksEditPermission() {
        reactor.keyValue.put(ReactorKeysEnum.PROJECT.getKey(), "project-1");
        reactor.keyValue.put(RUN_ID_KEY, "run-1");

        try (var projectUtils = mockStatic(AutomationProjectService.class)) {
            projectUtils.when(() -> AutomationProjectService.getEditableAutomationProject(user, "project-1"))
                    .thenThrow(new IllegalArgumentException("User does not have edit access to project project-1"));

            IllegalArgumentException error = assertThrows(IllegalArgumentException.class, reactor::execute);
            assertEquals("User does not have edit access to project project-1", error.getMessage());
        }
    }

    @Test
    void runFromAnotherProjectIsRejected() {
        reactor.keyValue.put(ReactorKeysEnum.PROJECT.getKey(), "project-1");
        reactor.keyValue.put(RUN_ID_KEY, "run-1");

        IProject project = mock(IProject.class);
        when(project.getProjectId()).thenReturn("project-1");

        try (var projectUtils = mockStatic(AutomationProjectService.class);
                var dbUtils = mockStatic(AutomationRunStore.class)) {
            projectUtils.when(() -> AutomationProjectService.getEditableAutomationProject(user, "project-1"))
                    .thenReturn(project);
            dbUtils.when(() -> AutomationRunStore.getRunDetail("run-1"))
                    .thenReturn(Map.of(AutomationConstants.PROJECT_ID, "different-project"));

            IllegalArgumentException error = assertThrows(IllegalArgumentException.class, reactor::execute);
            assertEquals("Run not found: run-1", error.getMessage());
        }
    }

    @Test
    void runWithoutDefinitionSnapshotThrows() {
        reactor.keyValue.put(ReactorKeysEnum.PROJECT.getKey(), "project-1");
        reactor.keyValue.put(RUN_ID_KEY, "run-1");

        IProject project = mock(IProject.class);
        when(project.getProjectId()).thenReturn("project-1");

        try (var projectUtils = mockStatic(AutomationProjectService.class);
                var dbUtils = mockStatic(AutomationRunStore.class)) {
            projectUtils.when(() -> AutomationProjectService.getEditableAutomationProject(user, "project-1"))
                    .thenReturn(project);
            dbUtils.when(() -> AutomationRunStore.getRunDetail("run-1"))
                    .thenReturn(Map.of(AutomationConstants.PROJECT_ID, "project-1"));

            IllegalArgumentException error = assertThrows(IllegalArgumentException.class, reactor::execute);
            assertEquals("Run has no definition snapshot to roll back to: run-1", error.getMessage());
        }
    }

    @Test
    void rollsBackAndSavesTheSnapshotDefinitionWhenAuthorized() {
        reactor.keyValue.put(ReactorKeysEnum.PROJECT.getKey(), "project-1");
        reactor.keyValue.put(RUN_ID_KEY, "run-1");

        IProject project = mock(IProject.class);
        when(project.getProjectId()).thenReturn("project-1");

        String runDefinition = minimalDefinition();
        Map<String, Object> runDetail = new LinkedHashMap<>();
        runDetail.put(AutomationConstants.PROJECT_ID, "project-1");
        runDetail.put(AutomationConstants.DEFINITION_SNAPSHOT, runDefinition);

        Map<String, String> nodeSources = Map.of("start", "def run(scope):\n    pass\n");
        AutomationDefinitionService.DefinitionFiles savedFiles = new AutomationDefinitionService.DefinitionFiles(
                runDefinition, nodeSources);

        try (var projectUtils = mockStatic(AutomationProjectService.class);
                var dbUtils = mockStatic(AutomationRunStore.class)) {
            projectUtils.when(() -> AutomationProjectService.getEditableAutomationProject(user, "project-1"))
                    .thenReturn(project);
            dbUtils.when(() -> AutomationRunStore.getRunDetail("run-1")).thenReturn(runDetail);
            dbUtils.when(() -> AutomationRunStore.getRunNodeSources("run-1")).thenReturn(nodeSources);
            projectUtils.when(() -> AutomationProjectService.saveDefinition("project-1", runDefinition, nodeSources,
                    null, user)).thenReturn(savedFiles);

            NounMetadata result = reactor.execute();

            @SuppressWarnings("unchecked")
            Map<String, Object> value = (Map<String, Object>) result.getValue();
            assertEquals(Boolean.TRUE, value.get("saved"));
            assertEquals(nodeSources, value.get(AutomationConstants.DOC_NODE_SOURCES));
            assertEquals(
                    AutomationDefinitionService.calculateRevision(runDefinition, nodeSources),
                    value.get(AutomationConstants.RESULT_REVISION));
        }
    }

    private static String minimalDefinition() {
        Map<String, Object> startNode = new LinkedHashMap<>();
        startNode.put(AutomationConstants.NODE_FIELD_ID, "start");
        startNode.put(AutomationConstants.NODE_FIELD_TYPE, AutomationConstants.NODE_START);
        startNode.put(AutomationConstants.NODE_FIELD_LABEL, "Start");
        startNode.put(AutomationConstants.NODE_FIELD_CODE_MODE, AutomationConstants.NODE_CODE_MODE_GENERATED);
        startNode.put(AutomationConstants.NODE_FIELD_CONFIG, Map.of());

        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put(AutomationConstants.DOC_NODES, java.util.List.of(startNode));
        graph.put(AutomationConstants.DOC_EDGES, java.util.List.of());

        Map<String, Object> definition = new LinkedHashMap<>();
        definition.put(AutomationConstants.DOC_FORMAT_VERSION, AutomationConstants.PYTHON_DOC_CURRENT_VERSION);
        definition.put(AutomationConstants.DOC_DESCRIPTION, "");
        definition.put(AutomationConstants.DOC_TRIGGER_BINDINGS,
                java.util.List.of(Map.of("id", "manual", "type", "manual")));
        definition.put(AutomationConstants.DOC_GRAPH, graph);
        return AutomationRuntimeUtils.GSON.toJson(definition);
    }
}
