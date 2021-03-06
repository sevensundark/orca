/*
 * Copyright 2015 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.orca.clouddriver.tasks.cluster

import groovy.transform.Canonical
import groovy.util.logging.Slf4j
import com.netflix.frigga.Names
import com.netflix.spinnaker.orca.DefaultTaskResult
import com.netflix.spinnaker.orca.ExecutionStatus
import com.netflix.spinnaker.orca.RetryableTask
import com.netflix.spinnaker.orca.TaskResult
import com.netflix.spinnaker.orca.batch.StageBuilder
import com.netflix.spinnaker.orca.clouddriver.KatoService
import com.netflix.spinnaker.orca.clouddriver.pipeline.servergroup.CloneServerGroupStage
import com.netflix.spinnaker.orca.clouddriver.pipeline.servergroup.CreateServerGroupStage
import com.netflix.spinnaker.orca.clouddriver.pipeline.servergroup.support.Location
import com.netflix.spinnaker.orca.clouddriver.pipeline.servergroup.support.TargetServerGroup
import com.netflix.spinnaker.orca.clouddriver.tasks.AbstractCloudProviderAwareTask
import com.netflix.spinnaker.orca.clouddriver.utils.OortHelper
import com.netflix.spinnaker.orca.kato.pipeline.CopyLastAsgStage
import com.netflix.spinnaker.orca.kato.pipeline.DeployStage
import com.netflix.spinnaker.orca.pipeline.model.Stage
import org.springframework.beans.factory.annotation.Autowired

/**
 * A task that operates on some subset of the ServerGroups in a cluster.
 */
@Slf4j
abstract class AbstractClusterWideClouddriverTask extends AbstractCloudProviderAwareTask implements RetryableTask {

  @Override
  public long getBackoffPeriod() {
    5000
  }

  @Override
  public long getTimeout() {
    90000
  }

  abstract String getClouddriverOperation()

  String getNotificationType() {
    return getClouddriverOperation().toLowerCase()
  }

  @Autowired OortHelper oortHelper
  @Autowired KatoService katoService

  @Canonical
  static class ClusterSelection {
    String cluster
    String cloudProvider = 'aws'
    String credentials

    @Override
    String toString() {
      "Cluster $cloudProvider/$credentials/$cluster"
    }
  }

  protected TaskResult missingClusterResult(Stage stage, ClusterSelection clusterSelection) {
    throw new IllegalStateException("No Cluster details found for $clusterSelection")
  }

  protected TaskResult emptyClusterResult(Stage stage, ClusterSelection clusterSelection, Map cluster) {
    throw new IllegalStateException("No ServerGroups found in cluster $clusterSelection")
  }


  @Override
  TaskResult execute(Stage stage) {
    def clusterSelection = stage.mapTo(ClusterSelection)
    def names = Names.parseName(clusterSelection.cluster)

    Optional<Map> cluster = oortHelper.getCluster(names.app,
                                                  clusterSelection.credentials,
                                                  clusterSelection.cluster,
                                                  clusterSelection.cloudProvider)
    if (!cluster.isPresent()) {
      if (stage.context.continueIfClusterNotFound) {
        return DefaultTaskResult.SUCCEEDED;
      }
      return missingClusterResult(stage, clusterSelection)
    }

    List<Map> serverGroups = cluster.get().serverGroups

    if (!serverGroups) {
      if (stage.context.continueIfClusterNotFound) {
        return DefaultTaskResult.SUCCEEDED;
      }
      return emptyClusterResult(stage, clusterSelection, cluster.get())
    }

    def locations =
      stage.context.namespaces
      ? stage.context.namespaces.collect { new Location(type: Location.Type.NAMESPACE, value: it) }
      : stage.context.regions
        ? stage.context.regions.collect { new Location(type: Location.Type.REGION, value: it) }
        : stage.context.zones
          ? stage.context.zones.collect { new Location(type: Location.Type.ZONE, value: it) }
          : stage.context.namespace
            ? [new Location(type: Location.Type.NAMESPACE, value: stage.context.namespace)]
            : stage.context.region
              ? [new Location(type: Location.Type.REGION, value: stage.context.region)]
              : []

    Location.Type exactLocationType = locations?.getAt(0)?.type

    Map<Location, List<TargetServerGroup>> targetServerGroupsByLocation = serverGroups.collect {
      new TargetServerGroup(it)
    }.groupBy { it.getLocation(exactLocationType) }

    List<TargetServerGroup> filteredServerGroups = locations.findResults { Location l ->
      def tsgs = targetServerGroupsByLocation[l]
      if (!tsgs) {
        return null
      }
      filterServerGroups(stage, clusterSelection.credentials, l, tsgs) ?: null
    }.flatten()

    List<Map<String, Map>> katoOps = filteredServerGroups.collect(this.&buildOperationPayloads.curry(clusterSelection)).flatten()

    if (!katoOps) {
      return DefaultTaskResult.SUCCEEDED
    }

    // "deploy.server.groups" is keyed by region, and all TSGs will have this value.
    def locationGroups = filteredServerGroups.groupBy {
      it.region
    }.collectEntries { String region, List<TargetServerGroup> serverGroup ->
      [(region): serverGroup.collect { it.name }]
    }

    def taskId = katoService.requestOperations(clusterSelection.cloudProvider, katoOps).toBlocking().first()
    new DefaultTaskResult(ExecutionStatus.SUCCEEDED, [
      "notification.type"   : getNotificationType(),
      "deploy.account.name" : clusterSelection.credentials,
      "kato.last.task.id"   : taskId,
      "deploy.server.groups": locationGroups
    ])
  }

  protected Map buildOperationPayload(ClusterSelection clusterSelection, TargetServerGroup serverGroup) {
    serverGroup.toClouddriverOperationPayload(clusterSelection.credentials)
  }

  protected List<Map> buildOperationPayloads(ClusterSelection clusterSelection, TargetServerGroup serverGroup) {
    [[(getClouddriverOperation()): buildOperationPayload(clusterSelection, serverGroup)]]
  }

  protected List<TargetServerGroup> filterActiveGroups(boolean includeActive, List<TargetServerGroup> serverGroups) {
    if (includeActive) {
      return serverGroups
    }
    return serverGroups.findAll { !isActive(it) }
  }

  protected List<TargetServerGroup> filterParentDeploys(Stage stage,
                                                        String account,
                                                        Location location,
                                                        List<TargetServerGroup> clusterServerGroups) {
    def parentDeployedServerGroups = parentDeployedServerGroups(stage, account, location, clusterServerGroups)
    return filterParentAndNewerThanParentDeploys(parentDeployedServerGroups, clusterServerGroups)
  }

  List<TargetServerGroup> filterServerGroups(Stage stage,
                                             String account,
                                             Location location,
                                             List<TargetServerGroup> serverGroups) {
    if (!serverGroups) {
      return []
    }

    if (!serverGroups.every { it.getLocation(location.type) == location }) {
      throw new IllegalStateException("all server groups must be in the same location, found ${serverGroups*.getLocation()}")
    }

    return filterParentDeploys(stage, account, location, serverGroups)
  }

  static List<TargetServerGroup> parentDeployedServerGroups(Stage stage,
                                                            String account,
                                                            Location location,
                                                            List<TargetServerGroup> clusterServerGroups) {
    //if we are a synthetic stage child of a deploy, don't operate on what we just deployed
    final Set<String> deployStageTypes = [
      DeployStage.PIPELINE_CONFIG_TYPE,
      CopyLastAsgStage.PIPELINE_CONFIG_TYPE,
      CloneServerGroupStage.PIPELINE_CONFIG_TYPE,
      CreateServerGroupStage.PIPELINE_CONFIG_TYPE,
    ]
    List<TargetServerGroup> deployedServerGroups = []

    stage.ancestors({ Stage ancestorStage, StageBuilder stageBuilder ->
      // Stage type is the context.type value when the stage is running as a child stage of a parallel deploy, or
      // the stage.type attribute when it is running directly as part of an Orchestration or Pipeline
      (deployStageTypes.contains(ancestorStage.type) || deployStageTypes.contains(ancestorStage.context.type)) && ancestorStage.context.'deploy.account.name' == account
    })*.stage.each { Stage parentDeployStage ->
      Map<String, String> dsgs = (parentDeployStage.context.'deploy.server.groups' ?: [:]) as Map
      switch (location.type) {
        case Location.Type.ZONE:
          deployedServerGroups.addAll(clusterServerGroups.findAll {
            it.zones.contains(location.value) && dsgs[it.region]?.contains(it.name)
          })
          break;
        case Location.Type.REGION:
          deployedServerGroups.addAll(clusterServerGroups.findAll {
            it.region == location.value && dsgs[location.value]?.contains(it.name)
          })
          break;
      }
    }

    return deployedServerGroups
  }

  static List<TargetServerGroup> filterParentAndNewerThanParentDeploys(List<TargetServerGroup> parentDeployedServerGroups,
                                                                       List<TargetServerGroup> clusterServerGroups) {
    def recentlyDeployedServerGroups = []
    if (parentDeployedServerGroups) {
      Long minCreatedTime = (parentDeployedServerGroups*.createdTime as Collection<Long>).min()
      recentlyDeployedServerGroups = clusterServerGroups.findAll { it.createdTime > minCreatedTime }
    }

    log.info("Preserving recently deployed server groups (${recentlyDeployedServerGroups*.name.join(", ")})")
    return clusterServerGroups - recentlyDeployedServerGroups - parentDeployedServerGroups
  }

  static boolean isActive(TargetServerGroup serverGroup) {
    return serverGroup.disabled == false || serverGroup.instances.any { it.healthState == 'Up' }
  }

  static class IsActive implements Comparator<TargetServerGroup> {
    @Override
    int compare(TargetServerGroup o1, TargetServerGroup o2) {
      isActive(o2) <=> isActive(o1)
    }
  }

  static class InstanceCount implements Comparator<TargetServerGroup> {
    @Override
    int compare(TargetServerGroup o1, TargetServerGroup o2) {
      o2.instances.size() <=> o1.instances.size()
    }
  }

  static class CreatedTime implements Comparator<TargetServerGroup> {
    @Override
    int compare(TargetServerGroup o1, TargetServerGroup o2) {
      o2.createdTime <=> o1.createdTime
    }
  }

  static class CompositeComparator implements Comparator<TargetServerGroup> {
    private final List<Comparator<TargetServerGroup>> comparators

    CompositeComparator(List<Comparator<TargetServerGroup>> comparators) {
      this.comparators = comparators
    }

    @Override
    int compare(TargetServerGroup o1, TargetServerGroup o2) {
      comparators.findResult { it.compare(o1, o2) ?: null } ?: 0
    }
  }
}
