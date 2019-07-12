/*
 * Copyright 2018 Pivotal, Inc.
 *
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
 */

package com.netflix.spinnaker.clouddriver.cloudfoundry.deploy.ops;

import com.netflix.spinnaker.clouddriver.cloudfoundry.client.CloudFoundryClient;
import com.netflix.spinnaker.clouddriver.cloudfoundry.client.model.v3.ProcessStats;
import com.netflix.spinnaker.clouddriver.cloudfoundry.deploy.description.ScaleCloudFoundryServerGroupDescription;
import com.netflix.spinnaker.clouddriver.data.task.Task;
import com.netflix.spinnaker.clouddriver.data.task.TaskRepository;
import com.netflix.spinnaker.clouddriver.helpers.OperationPoller;
import com.netflix.spinnaker.clouddriver.model.ServerGroup;
import com.netflix.spinnaker.clouddriver.orchestration.AtomicOperation;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Optional;

import static com.netflix.spinnaker.clouddriver.cloudfoundry.deploy.ops.CloudFoundryOperationUtils.describeProcessState;

@RequiredArgsConstructor
public class ScaleCloudFoundryServerGroupAtomicOperation implements AtomicOperation<Void> {
  private static final String PHASE = "RESIZE_SERVER_GROUP";

  private final OperationPoller operationPoller;
  private final ScaleCloudFoundryServerGroupDescription description;

  private static Task getTask() {
    return TaskRepository.threadLocalTask.get();
  }

  @Override
  public Void operate(List priorOutputs) {
    getTask().updateStatus(PHASE, "Resizing '" + description.getServerGroupName() + "'");

    final CloudFoundryClient client = description.getClient();

    ServerGroup.Capacity capacity = description.getCapacity();
    client.getApplications().scaleApplication(
      description.getServerGroupId(),
      capacity == null ? null : capacity.getDesired(),
      description.getMemory(),
      description.getDiskQuota());

    ProcessStats.State state = operationPoller.waitForOperation(
      () -> client.getApplications().getProcessState(description.getServerGroupId()),
      inProgressState -> (
        inProgressState == ProcessStats.State.RUNNING
          || inProgressState == ProcessStats.State.CRASHED
          || inProgressState == ProcessStats.State.DOWN),
      null, getTask(), description.getServerGroupName(), PHASE);

    if (state == ProcessStats.State.RUNNING ||
      (state == ProcessStats.State.DOWN && description.getCapacity().getDesired() == 0)) {
      getTask().updateStatus(PHASE, "Resized '" + description.getServerGroupName() + "'");
    } else {
      getTask().updateStatus(PHASE, "Failed to start '" + description.getServerGroupName() + "' which instead " + describeProcessState(state));
      getTask().fail();
    }

    return null;
  }
}