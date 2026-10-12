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

import java.util.LinkedHashMap;
import java.util.Map;

import prerna.reactor.AbstractReactor;
import prerna.reactor.automation.AutomationConstants;
import prerna.reactor.automation.AutomationRuntime;
import prerna.reactor.automation.project.AutomationProjectService;
import prerna.reactor.automation.run.AutomationRunStore;
import prerna.sablecc2.om.PixelDataType;
import prerna.sablecc2.om.PixelOperationType;
import prerna.sablecc2.om.ReactorKeysEnum;
import prerna.sablecc2.om.nounmeta.NounMetadata;

public class RollbackAutomationReactor extends AbstractReactor {

    private static final String RUN_ID_KEY = "runId";

    public RollbackAutomationReactor() {
        this.keysToGet = new String[] { ReactorKeysEnum.PROJECT.getKey(), RUN_ID_KEY };
        this.keyRequired = new int[] { 1, 1 };
    }

    @Override
    public NounMetadata execute() {
        organizeKeys();
        String projectId = this.keyValue.get(this.keysToGet[0]);
        String runId = this.keyValue.get(this.keysToGet[1]);

        projectId = AutomationProjectService.getEditableAutomationProject(this.insight.getUser(), projectId)
                .getProjectId();

        // Scope by PROJECT_ID so a user with edit access to one project cannot roll
        // back using a run that belongs to a different project.
        Map<String, Object> runDetail = AutomationRunStore.getRunDetail(runId);
        if (runDetail == null || !projectId.equals(runDetail.get(AutomationConstants.PROJECT_ID))) {
            throw new IllegalArgumentException("Run not found: " + runId);
        }

        String runDefinition = (String) runDetail.get(AutomationConstants.DEFINITION_SNAPSHOT);
        if (runDefinition == null || runDefinition.isBlank()) {
            throw new IllegalArgumentException("Run has no definition snapshot to roll back to: " + runId);
        }

        Map<String, String> nodeSources = AutomationRunStore.getRunNodeSources(runId);

        AutomationDefinitionService.DefinitionFiles files = AutomationProjectService.saveDefinition(projectId,
                runDefinition, nodeSources, null, this.insight.getUser());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("saved", true);
        // Add the saved definition files to the result
        result.put(AutomationConstants.DOC_NODE_SOURCES, files.nodeSources());
        // Add the calculated revision to the result
        result.put(AutomationConstants.RESULT_REVISION,
                AutomationDefinitionService.calculateRevision(files.definition(), files.nodeSources()));
        AutomationDefinitionValidator.ValidatedDefinition validated = AutomationDefinitionValidator
                .parseAndValidateForAuthoring(files.definition());
        result.put(AutomationConstants.DOC_GLOBALS, AutomationRuntime.declaredGlobals(validated));
        result.put(AutomationConstants.RESULT_SCOPE_VARIABLES,
                AutomationScopeCatalog.variablesByNode(validated));
        return new NounMetadata(result, PixelDataType.MAP, PixelOperationType.OPERATION);
    }

    @Override
    public String getReactorDescription() {
        return "Rolls back an automation's node configuration to the snapshot captured at a previous run.";
    }

}
