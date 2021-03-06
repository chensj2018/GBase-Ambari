/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ambari.server.state.cluster;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;

import javax.annotation.Nullable;
import javax.persistence.EntityManager;
import javax.persistence.RollbackException;

import org.apache.ambari.server.AmbariException;
import org.apache.ambari.server.ConfigGroupNotFoundException;
import org.apache.ambari.server.DuplicateResourceException;
import org.apache.ambari.server.ObjectNotFoundException;
import org.apache.ambari.server.ParentObjectNotFoundException;
import org.apache.ambari.server.RoleCommand;
import org.apache.ambari.server.ServiceComponentHostNotFoundException;
import org.apache.ambari.server.ServiceComponentNotFoundException;
import org.apache.ambari.server.ServiceNotFoundException;
import org.apache.ambari.server.actionmanager.HostRoleStatus;
import org.apache.ambari.server.agent.ExecutionCommand.KeyNames;
import org.apache.ambari.server.api.services.AmbariMetaInfo;
import org.apache.ambari.server.configuration.Configuration;
import org.apache.ambari.server.controller.AmbariSessionManager;
import org.apache.ambari.server.controller.ClusterResponse;
import org.apache.ambari.server.controller.ConfigurationResponse;
import org.apache.ambari.server.controller.MaintenanceStateHelper;
import org.apache.ambari.server.controller.RootServiceResponseFactory.Services;
import org.apache.ambari.server.controller.ServiceConfigVersionResponse;
import org.apache.ambari.server.controller.internal.UpgradeResourceProvider;
import org.apache.ambari.server.events.AmbariEvent.AmbariEventType;
import org.apache.ambari.server.events.ClusterConfigChangedEvent;
import org.apache.ambari.server.events.ClusterEvent;
import org.apache.ambari.server.events.jpa.EntityManagerCacheInvalidationEvent;
import org.apache.ambari.server.events.jpa.JPAEvent;
import org.apache.ambari.server.events.publishers.AmbariEventPublisher;
import org.apache.ambari.server.events.publishers.JPAEventPublisher;
import org.apache.ambari.server.logging.LockFactory;
import org.apache.ambari.server.metadata.RoleCommandOrder;
import org.apache.ambari.server.metadata.RoleCommandOrderProvider;
import org.apache.ambari.server.orm.RequiresSession;
import org.apache.ambari.server.orm.cache.HostConfigMapping;
import org.apache.ambari.server.orm.dao.AlertDefinitionDAO;
import org.apache.ambari.server.orm.dao.AlertDispatchDAO;
import org.apache.ambari.server.orm.dao.ClusterDAO;
import org.apache.ambari.server.orm.dao.ClusterStateDAO;
import org.apache.ambari.server.orm.dao.ClusterVersionDAO;
import org.apache.ambari.server.orm.dao.HostConfigMappingDAO;
import org.apache.ambari.server.orm.dao.HostDAO;
import org.apache.ambari.server.orm.dao.HostRoleCommandDAO;
import org.apache.ambari.server.orm.dao.HostVersionDAO;
import org.apache.ambari.server.orm.dao.RepositoryVersionDAO;
import org.apache.ambari.server.orm.dao.ServiceConfigDAO;
import org.apache.ambari.server.orm.dao.StackDAO;
import org.apache.ambari.server.orm.dao.TopologyRequestDAO;
import org.apache.ambari.server.orm.dao.UpgradeDAO;
import org.apache.ambari.server.orm.entities.ClusterConfigEntity;
import org.apache.ambari.server.orm.entities.ClusterConfigMappingEntity;
import org.apache.ambari.server.orm.entities.ClusterEntity;
import org.apache.ambari.server.orm.entities.ClusterServiceEntity;
import org.apache.ambari.server.orm.entities.ClusterStateEntity;
import org.apache.ambari.server.orm.entities.ClusterVersionEntity;
import org.apache.ambari.server.orm.entities.ConfigGroupEntity;
import org.apache.ambari.server.orm.entities.HostComponentStateEntity;
import org.apache.ambari.server.orm.entities.HostEntity;
import org.apache.ambari.server.orm.entities.HostRoleCommandEntity;
import org.apache.ambari.server.orm.entities.HostVersionEntity;
import org.apache.ambari.server.orm.entities.PermissionEntity;
import org.apache.ambari.server.orm.entities.PrivilegeEntity;
import org.apache.ambari.server.orm.entities.RepositoryVersionEntity;
import org.apache.ambari.server.orm.entities.RequestScheduleEntity;
import org.apache.ambari.server.orm.entities.ResourceEntity;
import org.apache.ambari.server.orm.entities.ServiceConfigEntity;
import org.apache.ambari.server.orm.entities.StackEntity;
import org.apache.ambari.server.orm.entities.TopologyRequestEntity;
import org.apache.ambari.server.orm.entities.UpgradeEntity;
import org.apache.ambari.server.orm.entities.UpgradeGroupEntity;
import org.apache.ambari.server.orm.entities.UpgradeItemEntity;
import org.apache.ambari.server.security.authorization.AuthorizationException;
import org.apache.ambari.server.security.authorization.AuthorizationHelper;
import org.apache.ambari.server.serveraction.upgrades.UpdateDesiredStackAction;
import org.apache.ambari.server.state.Cluster;
import org.apache.ambari.server.state.ClusterHealthReport;
import org.apache.ambari.server.state.Clusters;
import org.apache.ambari.server.state.ComponentInfo;
import org.apache.ambari.server.state.Config;
import org.apache.ambari.server.state.ConfigFactory;
import org.apache.ambari.server.state.ConfigHelper;
import org.apache.ambari.server.state.DesiredConfig;
import org.apache.ambari.server.state.Host;
import org.apache.ambari.server.state.HostHealthStatus;
import org.apache.ambari.server.state.HostState;
import org.apache.ambari.server.state.MaintenanceState;
import org.apache.ambari.server.state.PropertyInfo;
import org.apache.ambari.server.state.RepositoryVersionState;
import org.apache.ambari.server.state.SecurityType;
import org.apache.ambari.server.state.Service;
import org.apache.ambari.server.state.ServiceComponent;
import org.apache.ambari.server.state.ServiceComponentHost;
import org.apache.ambari.server.state.ServiceComponentHostEvent;
import org.apache.ambari.server.state.ServiceComponentHostEventType;
import org.apache.ambari.server.state.ServiceFactory;
import org.apache.ambari.server.state.ServiceInfo;
import org.apache.ambari.server.state.StackId;
import org.apache.ambari.server.state.StackInfo;
import org.apache.ambari.server.state.State;
import org.apache.ambari.server.state.UpgradeContext;
import org.apache.ambari.server.state.UpgradeContextFactory;
import org.apache.ambari.server.state.configgroup.ConfigGroup;
import org.apache.ambari.server.state.configgroup.ConfigGroupFactory;
import org.apache.ambari.server.state.fsm.InvalidStateTransitionException;
import org.apache.ambari.server.state.scheduler.RequestExecution;
import org.apache.ambari.server.state.scheduler.RequestExecutionFactory;
import org.apache.ambari.server.state.stack.upgrade.Direction;
import org.apache.ambari.server.state.stack.upgrade.UpgradeType;
import org.apache.ambari.server.state.svccomphost.ServiceComponentHostSummary;
import org.apache.ambari.server.topology.TopologyRequest;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Functions;
import com.google.common.base.Predicate;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import com.google.common.eventbus.Subscribe;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.assistedinject.Assisted;
import com.google.inject.persist.Transactional;

public class ClusterImpl implements Cluster {

  private static final Logger LOG = LoggerFactory.getLogger(ClusterImpl.class);
  private static final Logger configChangeLog = LoggerFactory.getLogger("configchange");

  /**
   * Prefix for cluster session attributes name.
   */
  private static final String CLUSTER_SESSION_ATTRIBUTES_PREFIX = "cluster_session_attributes:";
  private static final Set<RepositoryVersionState> ALLOWED_REPOSITORY_STATES =
      EnumSet.of(RepositoryVersionState.INIT, RepositoryVersionState.INSTALLING,
          RepositoryVersionState.INSTALLED);

  @Inject
  private Clusters clusters;

  private StackId desiredStackVersion;

  private final ConcurrentSkipListMap<String, Service> services = new ConcurrentSkipListMap<>();

  /**
   * [ Config Type -> [ Config Version Tag -> Config ] ]
   */
  private final ConcurrentMap<String, ConcurrentMap<String, Config>> allConfigs = new ConcurrentHashMap<>();

  /**
   * [ ServiceName -> [ ServiceComponentName -> [ HostName -> [ ... ] ] ] ]
   */
  private final ConcurrentMap<String, ConcurrentMap<String, ConcurrentMap<String, ServiceComponentHost>>> serviceComponentHosts = new ConcurrentHashMap<>();

  /**
   * [ HostName -> [ ... ] ]
   */
  private final ConcurrentMap<String, List<ServiceComponentHost>> serviceComponentHostsByHost = new ConcurrentHashMap<>();

  /**
   * Map of existing config groups
   */
  private final Map<Long, ConfigGroup> clusterConfigGroups = new ConcurrentHashMap<>();

  /**
   * Map of Request schedules for this cluster
   */
  private final Map<Long, RequestExecution> requestExecutions = new ConcurrentHashMap<>();

  private final ReadWriteLock clusterGlobalLock;

  // This is a lock for operations that do not need to be cluster global
  private final Lock hostTransitionStateWriteLock;

  /**
   * The unique ID of the {@link @ClusterEntity}.
   */
  private final long clusterId;

  private String clusterName;

  @Inject
  private ClusterDAO clusterDAO;

  @Inject
  private ClusterStateDAO clusterStateDAO;

  @Inject
  private ClusterVersionDAO clusterVersionDAO;

  @Inject
  private HostRoleCommandDAO hostRoleCommandDAO;

  @Inject
  private HostDAO hostDAO;

  @Inject
  private HostVersionDAO hostVersionDAO;

  @Inject
  private ServiceFactory serviceFactory;

  @Inject
  private ConfigFactory configFactory;

  @Inject
  private LockFactory lockFactory;

  @Inject
  private HostConfigMappingDAO hostConfigMappingDAO;

  @Inject
  private ConfigGroupFactory configGroupFactory;

  @Inject
  private RequestExecutionFactory requestExecutionFactory;

  @Inject
  private ConfigHelper configHelper;

  @Inject
  private MaintenanceStateHelper maintenanceStateHelper;

  @Inject
  private AmbariMetaInfo ambariMetaInfo;

  @Inject
  private ServiceConfigDAO serviceConfigDAO;

  @Inject
  private AlertDefinitionDAO alertDefinitionDAO;

  @Inject
  private AlertDispatchDAO alertDispatchDAO;

  @Inject
  private UpgradeDAO upgradeDAO;

  @Inject
  private RepositoryVersionDAO repositoryVersionDAO;

  @Inject
  private Configuration configuration;

  @Inject
  private AmbariSessionManager sessionManager;

  @Inject
  private TopologyRequestDAO topologyRequestDAO;

  /**
   * Data access object used for looking up stacks from the database.
   */
  @Inject
  private StackDAO stackDAO;

  private volatile Multimap<String, String> serviceConfigTypes;

  /**
   * Used to publish events relating to cluster CRUD operations and to receive
   * information about cluster operations.
   */
  private AmbariEventPublisher eventPublisher;

  /**
   * Used for broadcasting {@link JPAEvent}s.
   */
  @Inject
  private JPAEventPublisher jpaEventPublisher;

  /**
   * Used for getting instances of {@link RoleCommand} for this cluster.
   */
  @Inject
  private RoleCommandOrderProvider roleCommandOrderProvider;

  /**
   * Used to create instances of {@link UpgradeContext} with injected
   * dependencies. The {@link UpgradeContext} is used to populate the command
   * with upgrade information on the command/role maps if the upgrade is
   * suspended.
   */
  @Inject
  private UpgradeContextFactory upgradeContextFactory;

  /**
   * A simple cache for looking up {@code cluster-env} properties for a cluster.
   * This map is changed whenever {{cluster-env}} is changed and we receive a
   * {@link ClusterConfigChangedEvent}.
   */
  private Map<String, String> m_clusterPropertyCache = new ConcurrentHashMap<>();

  /**
   * A simple cache of the effective cluster version. This is mainly used during
   * upgrades to prevent calculating the value repeatedly. calculation of this
   * during an upgrade is not very quick or clean, it's good to cache it.
   */
  private final Map<Long, Long> upgradeEffectiveVersionCache = new ConcurrentHashMap<>();

  @Inject
  public ClusterImpl(@Assisted ClusterEntity clusterEntity, Injector injector,
      AmbariEventPublisher eventPublisher)
      throws AmbariException {

    clusterId = clusterEntity.getClusterId();
    clusterName = clusterEntity.getClusterName();

    injector.injectMembers(this);

    clusterGlobalLock = lockFactory.newReadWriteLock("clusterGlobalLock");
    hostTransitionStateWriteLock = lockFactory.newLock("hostTransitionStateLock");

    loadStackVersion();
    loadServices();
    loadServiceHostComponents();

    // cache configurations before loading configuration groups
    cacheConfigurations();
    loadConfigGroups();

    loadRequestExecutions();

    if (desiredStackVersion != null && !StringUtils.isEmpty(desiredStackVersion.getStackName()) && !
      StringUtils.isEmpty(desiredStackVersion.getStackVersion())) {
      loadServiceConfigTypes();
    }

    // register to receive stuff
    eventPublisher.register(this);
    this.eventPublisher = eventPublisher;
  }

  private void loadServiceConfigTypes() throws AmbariException {
    try {
      serviceConfigTypes = collectServiceConfigTypesMapping();
    } catch (AmbariException e) {
      LOG.error("Cannot load stack info:", e);
      throw e;
    }
    LOG.info("Service config types loaded: {}", serviceConfigTypes);
  }

  /**
   * Construct config type to service name mapping
   * @throws AmbariException when stack or its part not found
   */
  private Multimap<String, String> collectServiceConfigTypesMapping() throws AmbariException {
    Multimap<String, String> serviceConfigTypes = HashMultimap.create();

    Map<String, ServiceInfo> serviceInfoMap = null;
    try {
      serviceInfoMap = ambariMetaInfo.getServices(desiredStackVersion.getStackName(), desiredStackVersion.getStackVersion());
    } catch (ParentObjectNotFoundException e) {
      LOG.error("Service config versioning disabled due to exception: ", e);
      return serviceConfigTypes;
    }
    for (Entry<String, ServiceInfo> entry : serviceInfoMap.entrySet()) {
      String serviceName = entry.getKey();
      ServiceInfo serviceInfo = entry.getValue();
      Set<String> configTypes = serviceInfo.getConfigTypeAttributes().keySet();
      for (String configType : configTypes) {
        serviceConfigTypes.put(serviceName, configType);
      }
    }

    return serviceConfigTypes;
  }

  /**
   * Make sure we load all the service host components.
   * We need this for live status checks.
   */
  private void loadServiceHostComponents() {
    for (Entry<String, Service> serviceKV : services.entrySet()) {
      /* get all the service component hosts **/
      Service service = serviceKV.getValue();
      if (!serviceComponentHosts.containsKey(service.getName())) {
        serviceComponentHosts.put(service.getName(),
            new ConcurrentHashMap<String, ConcurrentMap<String, ServiceComponentHost>>());
      }

      for (Entry<String, ServiceComponent> svcComponent : service.getServiceComponents().entrySet()) {
        ServiceComponent comp = svcComponent.getValue();
        String componentName = svcComponent.getKey();
        if (!serviceComponentHosts.get(service.getName()).containsKey(componentName)) {
          serviceComponentHosts.get(service.getName()).put(componentName,
              new ConcurrentHashMap<String, ServiceComponentHost>());
        }

        /** Get Service Host Components **/
        for (Entry<String, ServiceComponentHost> svchost : comp.getServiceComponentHosts().entrySet()) {
          String hostname = svchost.getKey();
          ServiceComponentHost svcHostComponent = svchost.getValue();
          if (!serviceComponentHostsByHost.containsKey(hostname)) {
            serviceComponentHostsByHost.put(hostname,
                new CopyOnWriteArrayList<ServiceComponentHost>());
          }

          List<ServiceComponentHost> compList = serviceComponentHostsByHost.get(hostname);
          compList.add(svcHostComponent);

          if (!serviceComponentHosts.get(service.getName()).get(componentName).containsKey(
              hostname)) {
            serviceComponentHosts.get(service.getName()).get(componentName).put(hostname,
                svcHostComponent);
          }
        }
      }
    }
  }

  private void loadServices() {
    ClusterEntity clusterEntity = getClusterEntity();
    if (CollectionUtils.isEmpty(clusterEntity.getClusterServiceEntities())) {
      return;
    }

    for (ClusterServiceEntity serviceEntity : clusterEntity.getClusterServiceEntities()) {
      StackId stackId = getCurrentStackVersion();
      try {
        if (ambariMetaInfo.getService(stackId.getStackName(),
          stackId.getStackVersion(), serviceEntity.getServiceName()) != null) {
          services.put(serviceEntity.getServiceName(),
            serviceFactory.createExisting(this, serviceEntity));
        }
      } catch (AmbariException e) {
        LOG.error(String.format(
          "Can not get service info: stackName=%s, stackVersion=%s, serviceName=%s",
          stackId.getStackName(), stackId.getStackVersion(),
          serviceEntity.getServiceName()));
      }
    }
  }

  private void loadConfigGroups() {
    ClusterEntity clusterEntity = getClusterEntity();
    if (!clusterEntity.getConfigGroupEntities().isEmpty()) {
      for (ConfigGroupEntity configGroupEntity : clusterEntity.getConfigGroupEntities()) {
        clusterConfigGroups.put(configGroupEntity.getGroupId(),
            configGroupFactory.createExisting(this, configGroupEntity));
      }
    }
  }

  private void loadRequestExecutions() {
    ClusterEntity clusterEntity = getClusterEntity();
    if (!clusterEntity.getRequestScheduleEntities().isEmpty()) {
      for (RequestScheduleEntity scheduleEntity : clusterEntity.getRequestScheduleEntities()) {
        requestExecutions.put(scheduleEntity.getScheduleId(),
            requestExecutionFactory.createExisting(this, scheduleEntity));
      }
    }
  }

  @Override
  public void addConfigGroup(ConfigGroup configGroup) throws AmbariException {
    String hostList = "";
    if(LOG.isDebugEnabled()) {
      if (configGroup.getHosts() != null) {
        for (Host host : configGroup.getHosts().values()) {
          hostList += host.getHostName() + ", ";
        }
      }
    }

    LOG.debug("Adding a new Config group" + ", clusterName = "
      + getClusterName() + ", groupName = " + configGroup.getName()
      + ", tag = " + configGroup.getTag() + " with hosts " + hostList);

    if (clusterConfigGroups.containsKey(configGroup.getId())) {
      // The loadConfigGroups will load all groups to memory
      LOG.debug("Config group already exists" + ", clusterName = "
          + getClusterName() + ", groupName = " + configGroup.getName()
          + ", groupId = " + configGroup.getId() + ", tag = "
          + configGroup.getTag());
    } else {
      clusterConfigGroups.put(configGroup.getId(), configGroup);
    }
  }

  @Override
  public Map<Long, ConfigGroup> getConfigGroups() {
    return Collections.unmodifiableMap(clusterConfigGroups);
  }

  @Override
  public Map<Long, ConfigGroup> getConfigGroupsByHostname(String hostname)
    throws AmbariException {
    Map<Long, ConfigGroup> configGroups = new HashMap<>();

    for (Entry<Long, ConfigGroup> groupEntry : clusterConfigGroups.entrySet()) {
      Long id = groupEntry.getKey();
      ConfigGroup group = groupEntry.getValue();
      for (Host host : group.getHosts().values()) {
        if (StringUtils.equals(hostname, host.getHostName())) {
          configGroups.put(id, group);
          break;
        }
      }
    }
    return configGroups;
  }

  @Override
  public void addRequestExecution(RequestExecution requestExecution) throws AmbariException {
    LOG.info("Adding a new request schedule" + ", clusterName = " + getClusterName() + ", id = "
        + requestExecution.getId() + ", description = " + requestExecution.getDescription());

    if (requestExecutions.containsKey(requestExecution.getId())) {
      LOG.debug(
          "Request schedule already exists" + ", clusterName = " + getClusterName() + ", id = "
              + requestExecution.getId() + ", description = " + requestExecution.getDescription());
    } else {
      requestExecutions.put(requestExecution.getId(), requestExecution);
    }
  }

  @Override
  public Map<Long, RequestExecution> getAllRequestExecutions() {
    return Collections.unmodifiableMap(requestExecutions);
  }

  @Override
  public void deleteRequestExecution(Long id) throws AmbariException {
    RequestExecution requestExecution = requestExecutions.get(id);
    if (requestExecution == null) {
      throw new AmbariException("Request schedule does not exists, " + "id = " + id);
    }
    LOG.info("Deleting request schedule" + ", clusterName = " + getClusterName() + ", id = "
        + requestExecution.getId() + ", description = " + requestExecution.getDescription());

    requestExecution.delete();
    requestExecutions.remove(id);
  }

  @Override
  public void deleteConfigGroup(Long id) throws AmbariException, AuthorizationException {
    ConfigGroup configGroup = clusterConfigGroups.get(id);
    if (configGroup == null) {
      throw new ConfigGroupNotFoundException(getClusterName(), id.toString());
    }

    LOG.debug("Deleting Config group" + ", clusterName = " + getClusterName()
        + ", groupName = " + configGroup.getName() + ", groupId = "
        + configGroup.getId() + ", tag = " + configGroup.getTag());

    configGroup.delete();
    clusterConfigGroups.remove(id);
  }

  public ServiceComponentHost getServiceComponentHost(String serviceName,
      String serviceComponentName, String hostname) throws AmbariException {
    if (!serviceComponentHosts.containsKey(serviceName)
        || !serviceComponentHosts.get(serviceName).containsKey(
            serviceComponentName)
        || !serviceComponentHosts.get(serviceName).get(serviceComponentName).containsKey(
            hostname)) {
      throw new ServiceComponentHostNotFoundException(getClusterName(),
          serviceName, serviceComponentName, hostname);
    }
    return serviceComponentHosts.get(serviceName).get(serviceComponentName).get(
      hostname);
  }

  @Override
  public String getClusterName() {
    return clusterName;
  }

  @Override
  public void setClusterName(String clusterName) {
    String oldName = null;
    ClusterEntity clusterEntity = getClusterEntity();
    oldName = clusterEntity.getClusterName();
    clusterEntity.setClusterName(clusterName);

    // RollbackException possibility if UNIQUE constraint violated
    clusterEntity = clusterDAO.merge(clusterEntity);
    clusters.updateClusterName(oldName, clusterName);
    this.clusterName = clusterName;

    // if the name changed, fire an event
    if (!StringUtils.equals(oldName, clusterName)) {
      ClusterEvent clusterNameChangedEvent = new ClusterEvent(AmbariEventType.CLUSTER_RENAME, clusterId);
      eventPublisher.publish(clusterNameChangedEvent);
    }
  }

  @Override
  public Long getResourceId() {
    ClusterEntity clusterEntity = getClusterEntity();

    ResourceEntity resourceEntity = clusterEntity.getResource();
    if (resourceEntity == null) {
      LOG.warn(
          "There is no resource associated with this cluster:\n\tCluster Name: {}\n\tCluster ID: {}",
          getClusterName(), getClusterId());
      return null;
    } else {
      return resourceEntity.getId();
    }
  }

  @Override
  @Transactional
  public void addServiceComponentHosts(Collection<ServiceComponentHost> serviceComponentHosts) throws AmbariException {
    for (ServiceComponentHost serviceComponentHost : serviceComponentHosts) {
      Service service = getService(serviceComponentHost.getServiceName());
      ServiceComponent serviceComponent = service.getServiceComponent(serviceComponentHost.getServiceComponentName());
      serviceComponent.addServiceComponentHost(serviceComponentHost);
    }
  }

  public void addServiceComponentHost(ServiceComponentHost svcCompHost)
      throws AmbariException {
    if (LOG.isDebugEnabled()) {
      LOG.debug("Trying to add component {} of service {} on {} to the cache",
          svcCompHost.getServiceComponentName(), svcCompHost.getServiceName(),
          svcCompHost.getHostName());
    }

    final String hostname = svcCompHost.getHostName();
    final String serviceName = svcCompHost.getServiceName();
    final String componentName = svcCompHost.getServiceComponentName();

    Set<Cluster> cs = clusters.getClustersForHost(hostname);

    boolean clusterFound = false;
    Iterator<Cluster> iter = cs.iterator();
    while (iter.hasNext()) {
      Cluster c = iter.next();
      if (c.getClusterId() == getClusterId()) {
        clusterFound = true;
        break;
      }
    }

    if (!clusterFound) {
      throw new AmbariException("Host does not belong this cluster"
          + ", hostname=" + hostname + ", clusterName=" + getClusterName()
          + ", clusterId=" + getClusterId());
    }

    if (!serviceComponentHosts.containsKey(serviceName)) {
      serviceComponentHosts.put(serviceName,
          new ConcurrentHashMap<String, ConcurrentMap<String, ServiceComponentHost>>());
    }

    if (!serviceComponentHosts.get(serviceName).containsKey(componentName)) {
      serviceComponentHosts.get(serviceName).put(componentName,
          new ConcurrentHashMap<String, ServiceComponentHost>());
    }

    if (serviceComponentHosts.get(serviceName).get(componentName).containsKey(
        hostname)) {
      throw new AmbariException("Duplicate entry for ServiceComponentHost"
          + ", serviceName=" + serviceName + ", serviceComponentName"
          + componentName + ", hostname= " + hostname);
    }

    if (!serviceComponentHostsByHost.containsKey(hostname)) {
      serviceComponentHostsByHost.put(hostname,
          new CopyOnWriteArrayList<ServiceComponentHost>());
    }

    if (LOG.isDebugEnabled()) {
      LOG.debug("Adding a new ServiceComponentHost" + ", clusterName="
          + getClusterName() + ", clusterId=" + getClusterId()
          + ", serviceName=" + serviceName + ", serviceComponentName"
          + componentName + ", hostname= " + hostname);
    }

    serviceComponentHosts.get(serviceName).get(componentName).put(hostname,
      svcCompHost);

    serviceComponentHostsByHost.get(hostname).add(svcCompHost);
  }

  @Override
  public void removeServiceComponentHost(ServiceComponentHost svcCompHost)
    throws AmbariException {
    if (LOG.isDebugEnabled()) {
      LOG.debug(
          "Trying to remove component {} of service {} on {} from the cache",
          svcCompHost.getServiceComponentName(), svcCompHost.getServiceName(),
          svcCompHost.getHostName());
    }

    final String hostname = svcCompHost.getHostName();
    final String serviceName = svcCompHost.getServiceName();
    final String componentName = svcCompHost.getServiceComponentName();
    Set<Cluster> cs = clusters.getClustersForHost(hostname);

    boolean clusterFound = false;
    Iterator<Cluster> iter = cs.iterator();
    while (iter.hasNext()) {
      Cluster c = iter.next();
      if (c.getClusterId() == getClusterId()) {
        clusterFound = true;
        break;
      }
    }

    if (!clusterFound) {
      throw new AmbariException("Host does not belong this cluster"
          + ", hostname=" + hostname + ", clusterName=" + getClusterName()
          + ", clusterId=" + getClusterId());
    }

    if (!serviceComponentHosts.containsKey(serviceName)
        || !serviceComponentHosts.get(serviceName).containsKey(componentName)
        || !serviceComponentHosts.get(serviceName).get(componentName).containsKey(
            hostname)) {
      throw new AmbariException("Invalid entry for ServiceComponentHost"
          + ", serviceName=" + serviceName + ", serviceComponentName"
          + componentName + ", hostname= " + hostname);
    }

    if (!serviceComponentHostsByHost.containsKey(hostname)) {
      throw new AmbariException("Invalid host entry for ServiceComponentHost"
          + ", serviceName=" + serviceName + ", serviceComponentName"
          + componentName + ", hostname= " + hostname);
    }

    ServiceComponentHost schToRemove = null;
    for (ServiceComponentHost sch : serviceComponentHostsByHost.get(hostname)) {
      if (sch.getServiceName().equals(serviceName)
          && sch.getServiceComponentName().equals(componentName)
          && sch.getHostName().equals(hostname)) {
        schToRemove = sch;
        break;
      }
    }

    if (schToRemove == null) {
      LOG.warn("Unavailable in per host cache. ServiceComponentHost"
        + ", serviceName=" + serviceName
        + ", serviceComponentName" + componentName
        + ", hostname= " + hostname);
    }

    if (LOG.isDebugEnabled()) {
      LOG.debug("Removing a ServiceComponentHost" + ", clusterName="
          + getClusterName() + ", clusterId=" + getClusterId()
          + ", serviceName=" + serviceName + ", serviceComponentName"
          + componentName + ", hostname= " + hostname);
    }

    serviceComponentHosts.get(serviceName).get(componentName).remove(hostname);
    if (schToRemove != null) {
      serviceComponentHostsByHost.get(hostname).remove(schToRemove);
    }
  }

  @Override
  public long getClusterId() {
    // Add cluster creates the managed entity before creating the Cluster
    // instance so id would not be null.
    return clusterId;
  }

  @Override
  public List<ServiceComponentHost> getServiceComponentHosts(String hostname) {
    List<ServiceComponentHost> serviceComponentHosts = serviceComponentHostsByHost.get(hostname);
    if (null != serviceComponentHosts) {
      return new CopyOnWriteArrayList<>(serviceComponentHosts);
    }

    return new ArrayList<>();
  }

  @Override
  public Map<String, Set<String>> getServiceComponentHostMap(Set<String> hostNames, Set<String> serviceNames) {
    Map<String, Set<String>> componentHostMap = new HashMap<>();

    Collection<Host> hosts = getHosts();

    if(hosts != null) {
      for (Host host : hosts) {
        String hostname = host.getHostName();

        // If this host is not filtered out, continue processing
        if ((hostNames == null) || hostNames.contains(hostname)) {
          List<ServiceComponentHost> serviceComponentHosts = getServiceComponentHosts(hostname);

          if (serviceComponentHosts != null) {
            for (ServiceComponentHost sch : serviceComponentHosts) {
              // If the service for this ServiceComponentHost is not filtered out, continue processing
              if ((serviceNames == null) || serviceNames.contains(sch.getServiceName())) {
                String component = sch.getServiceComponentName();
                Set<String> componentHosts = componentHostMap.get(component);

                if (componentHosts == null) {
                  componentHosts = new HashSet<>();
                  componentHostMap.put(component, componentHosts);
                }

                componentHosts.add(hostname);
              }
            }
          }
        }
      }
    }

    return componentHostMap;
  }

  @Override
  public List<ServiceComponentHost> getServiceComponentHosts(String serviceName, String componentName) {
    ArrayList<ServiceComponentHost> foundItems = new ArrayList<>();

    ConcurrentMap<String, ConcurrentMap<String, ServiceComponentHost>> foundByService = serviceComponentHosts.get(
        serviceName);
    if (foundByService != null) {
      if (componentName == null) {
        for (Map<String, ServiceComponentHost> foundByComponent : foundByService.values()) {
          foundItems.addAll(foundByComponent.values());
        }
      } else if (foundByService.containsKey(componentName)) {
        foundItems.addAll(foundByService.get(componentName).values());
      }
    }

    return foundItems;
  }

  @Override
  public void addService(Service service) {
    if (LOG.isDebugEnabled()) {
      LOG.debug("Adding a new Service" + ", clusterName=" + getClusterName() + ", clusterId="
          + getClusterId() + ", serviceName=" + service.getName());
    }
    services.put(service.getName(), service);
  }

  @Override
  public Service addService(String serviceName) throws AmbariException {
    if (services.containsKey(serviceName)) {
      throw new AmbariException("Service already exists" + ", clusterName=" + getClusterName()
          + ", clusterId=" + getClusterId() + ", serviceName=" + serviceName);
    }

    Service service = serviceFactory.createNew(this, serviceName);
    addService(service);

    return service;
  }

  @Override
  public Service getService(String serviceName) throws AmbariException {
    Service service = services.get(serviceName);
    if (null == service) {
      throw new ServiceNotFoundException(getClusterName(), serviceName);
    }

    return service;
  }

  @Override
  public Map<String, Service> getServices() {
    return new HashMap<>(services);
  }

  @Override
  public StackId getDesiredStackVersion() {
    return desiredStackVersion;
  }

  @Override
  public void setDesiredStackVersion(StackId stackId) throws AmbariException {
    setDesiredStackVersion(stackId, false);
  }

  @Override
  public void setDesiredStackVersion(StackId stackId, boolean cascade) throws AmbariException {
    clusterGlobalLock.writeLock().lock();
    try {
      if (LOG.isDebugEnabled()) {
        LOG.debug("Changing DesiredStackVersion of Cluster" + ", clusterName="
            + getClusterName() + ", clusterId=" + getClusterId()
            + ", currentDesiredStackVersion=" + desiredStackVersion
            + ", newDesiredStackVersion=" + stackId);
      }

      desiredStackVersion = stackId;
      StackEntity stackEntity = stackDAO.find(stackId.getStackName(), stackId.getStackVersion());

      ClusterEntity clusterEntity = getClusterEntity();

      clusterEntity.setDesiredStack(stackEntity);
      clusterEntity = clusterDAO.merge(clusterEntity);

      if (cascade) {
        for (Service service : getServices().values()) {
          service.setDesiredStackVersion(stackId);

          for (ServiceComponent sc : service.getServiceComponents().values()) {
            sc.setDesiredStackVersion(stackId);

            for (ServiceComponentHost sch : sc.getServiceComponentHosts().values()) {
              sch.setDesiredStackVersion(stackId);
            }
          }
        }
      }
      loadServiceConfigTypes();
    } finally {
      clusterGlobalLock.writeLock().unlock();
    }
  }

  @Override
  public StackId getCurrentStackVersion() {
    ClusterEntity clusterEntity = getClusterEntity();

    ClusterStateEntity clusterStateEntity = clusterEntity.getClusterStateEntity();
    if (clusterStateEntity != null) {
      StackEntity currentStackEntity = clusterStateEntity.getCurrentStack();
      return new StackId(currentStackEntity);
    }

    return null;
  }

  @Override
  public State getProvisioningState() {
    State provisioningState = null;
    ClusterEntity clusterEntity = getClusterEntity();
    provisioningState = clusterEntity.getProvisioningState();

    if (null == provisioningState) {
      provisioningState = State.INIT;
    }

    return provisioningState;
  }

  @Override
  public void setProvisioningState(State provisioningState) {
    ClusterEntity clusterEntity = getClusterEntity();
    clusterEntity.setProvisioningState(provisioningState);
    clusterEntity = clusterDAO.merge(clusterEntity);
  }

  @Override
  public SecurityType getSecurityType() {
    SecurityType securityType = null;
    ClusterEntity clusterEntity = getClusterEntity();
    securityType = clusterEntity.getSecurityType();

    if (null == securityType) {
      securityType = SecurityType.NONE;
    }

    return securityType;
  }

  @Override
  public void setSecurityType(SecurityType securityType) {
    ClusterEntity clusterEntity = getClusterEntity();
    clusterEntity.setSecurityType(securityType);
    clusterEntity = clusterDAO.merge(clusterEntity);
  }

  /**
   * Get the ClusterVersionEntity object whose state is CURRENT.
   * @return
   */
  @Override
  public ClusterVersionEntity getCurrentClusterVersion() {
    Collection<ClusterVersionEntity> clusterVersionEntities = getClusterEntity().getClusterVersionEntities();
    for (ClusterVersionEntity clusterVersionEntity : clusterVersionEntities) {
      if (clusterVersionEntity.getState() == RepositoryVersionState.CURRENT) {
        return clusterVersionEntity;
      }
    }
    return null;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ClusterVersionEntity getEffectiveClusterVersion() throws AmbariException {
    UpgradeEntity upgradeEntity = getUpgradeInProgress();
    if (upgradeEntity == null) {
      return getCurrentClusterVersion();
    }

    // see if this is in the cache first, and only walk the upgrade if it's not
    Long upgradeId = upgradeEntity.getId();
    Long effectiveClusterVersionId = upgradeEffectiveVersionCache.get(upgradeId);
    if (null == effectiveClusterVersionId) {
      boolean updateCache = true;
      final ClusterVersionEntity effectiveClusterVersion;

      if (upgradeEntity.getUpgradeType() != UpgradeType.NON_ROLLING) {
        RepositoryVersionEntity repositoryVersion = upgradeEntity.getToRepositoryVersion();
        effectiveClusterVersion = clusterVersionDAO.findByClusterAndStackAndVersion(
            clusterName, repositoryVersion.getStackId(), repositoryVersion.getVersion());
      } else {
        if (upgradeEntity.getDirection() == Direction.UPGRADE) {
          HostRoleStatus stackActionStatus = getExpressUpgradeDesiredStackStatus(upgradeEntity);
          boolean pastChangingStack = stackActionStatus == HostRoleStatus.COMPLETED;
          updateCache = stackActionStatus != HostRoleStatus.IN_PROGRESS;

          RepositoryVersionEntity repositoryVersion = pastChangingStack
              ? upgradeEntity.getToRepositoryVersion() : upgradeEntity.getFromRepositoryVersion();

          effectiveClusterVersion = clusterVersionDAO.findByClusterAndStackAndVersion(clusterName,
              repositoryVersion.getStackId(), repositoryVersion.getVersion());
        } else {
          // Should be the lower value during a Downgrade.
          RepositoryVersionEntity repositoryVersion = upgradeEntity.getToRepositoryVersion();
          effectiveClusterVersion = clusterVersionDAO.findByClusterAndStackAndVersion(clusterName,
              repositoryVersion.getStackId(), repositoryVersion.getVersion());
        }
      }

      if (null != effectiveClusterVersion) {
        effectiveClusterVersionId = effectiveClusterVersion.getId();
      }

      // cache for later use, but only if the action is completed
      if (null != effectiveClusterVersion && updateCache) {
        upgradeEffectiveVersionCache.put(upgradeId, effectiveClusterVersionId);
      }
    }

    if (effectiveClusterVersionId == null) {
      throw new AmbariException(
          "Unable to determine which version to use during Stack Upgrade, effectiveVersion is null.");
    }

    // return cluster version which is "effective" given the upgrade state
    return clusterVersionDAO.findByPK(effectiveClusterVersionId);
  }

  /**
   * Gets the status of the {@link UpdateDesiredStackAction} for an express
   * upgrade.
   *
   * @param upgrade
   *          Stack Upgrade
   * @return the status of the command
   */
  private HostRoleStatus getExpressUpgradeDesiredStackStatus(UpgradeEntity upgrade) {
    for (UpgradeGroupEntity group : upgrade.getUpgradeGroups()) {
      if (group.getName().equalsIgnoreCase(UpgradeResourceProvider.CONST_UPGRADE_GROUP_NAME)) {
        for (UpgradeItemEntity item : group.getItems()) {
          List<Long> taskIds = hostRoleCommandDAO.findTaskIdsByStage(upgrade.getRequestId(), item.getStageId());
          List<HostRoleCommandEntity> commands = hostRoleCommandDAO.findByPKs(taskIds);
          for (HostRoleCommandEntity command : commands) {
            if (command.getCustomCommandName() != null &&
                command.getCustomCommandName().equalsIgnoreCase(
                    UpgradeResourceProvider.CONST_CUSTOM_COMMAND_NAME)) {
              return command.getStatus();
            }
          }
        }
        return HostRoleStatus.PENDING;
      }
    }
    return HostRoleStatus.PENDING;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void invalidateUpgradeEffectiveVersion() {
    upgradeEffectiveVersionCache.clear();
  }

  /**
   * Get all of the ClusterVersionEntity objects for the cluster.
   * @return
   */
  @Override
  public Collection<ClusterVersionEntity> getAllClusterVersions() {
    return clusterVersionDAO.findByCluster(getClusterName());
  }

  /**
   * During the Finalize Action, want to transition all Host Versions from INSTALLED to CURRENT, and the last CURRENT one to INSTALLED.
   * @param hostNames Collection of host names
   * @param currentClusterVersion Entity that contains the cluster's current stack (with its name and version)
   * @param desiredState Desired state must be {@link RepositoryVersionState#CURRENT}
   * @throws AmbariException
   */
  @Override
  public void mapHostVersions(Set<String> hostNames, ClusterVersionEntity currentClusterVersion, RepositoryVersionState desiredState) throws AmbariException {
    if (currentClusterVersion == null) {
      throw new AmbariException("Could not find current stack version of cluster " + getClusterName());
    }

    final Set<RepositoryVersionState> validStates = Sets.newHashSet(RepositoryVersionState.CURRENT);

    if (!validStates.contains(desiredState)) {
      throw new AmbariException("The state must be one of [" + StringUtils.join(validStates, ", ") + "]");
    }

    clusterGlobalLock.writeLock().lock();
    try {
      StackEntity repoVersionStackEntity = currentClusterVersion.getRepositoryVersion().getStack();
      StackId repoVersionStackId = new StackId(repoVersionStackEntity);

      Map<String, HostVersionEntity> existingHostToHostVersionEntity = new HashMap<>();
      List<HostVersionEntity> existingHostVersionEntities = hostVersionDAO.findByClusterStackAndVersion(
        getClusterName(), repoVersionStackId,
        currentClusterVersion.getRepositoryVersion().getVersion());

      if (existingHostVersionEntities != null) {
        for (HostVersionEntity entity : existingHostVersionEntities) {
          existingHostToHostVersionEntity.put(entity.getHostName(), entity);
        }
      }

      Sets.SetView<String> intersection = Sets.intersection(
        existingHostToHostVersionEntity.keySet(), hostNames);

      for (String hostname : hostNames) {
        List<HostVersionEntity> currentHostVersions = hostVersionDAO.findByClusterHostAndState(
            getClusterName(), hostname, RepositoryVersionState.CURRENT);
        HostVersionEntity currentHostVersionEntity = (currentHostVersions != null && currentHostVersions.size() == 1) ? currentHostVersions.get(0)
            : null;

          // Notice that if any hosts already have the desired stack and version, regardless of the state, we try
          // to be robust and only insert records for the missing hosts.
          if (!intersection.contains(hostname)) {
            // According to the business logic, we don't create objects in a CURRENT state.
            HostEntity hostEntity = hostDAO.findByName(hostname);
            HostVersionEntity hostVersionEntity = new HostVersionEntity(hostEntity, currentClusterVersion.getRepositoryVersion(), desiredState);
            hostVersionDAO.create(hostVersionEntity);
          } else {
            HostVersionEntity hostVersionEntity = existingHostToHostVersionEntity.get(hostname);
            if (hostVersionEntity.getState() != desiredState) {
              hostVersionEntity.setState(desiredState);
            hostVersionEntity = hostVersionDAO.merge(hostVersionEntity);
            }

          // Maintain the invariant that only one HostVersionEntity is allowed
          // to have a state of CURRENT.
          if (currentHostVersionEntity != null
              && !currentHostVersionEntity.getRepositoryVersion().equals(
                  hostVersionEntity.getRepositoryVersion())
              && desiredState == RepositoryVersionState.CURRENT
              && currentHostVersionEntity.getState() == RepositoryVersionState.CURRENT) {
            currentHostVersionEntity.setState(RepositoryVersionState.INSTALLED);
            hostVersionDAO.merge(currentHostVersionEntity);
          }
        }
      }
    } finally {
      clusterGlobalLock.writeLock().unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void transitionHosts(ClusterVersionEntity sourceClusterVersion,
      RepositoryVersionState state) throws AmbariException {

    if (sourceClusterVersion == null) {
      throw new AmbariException("Could not find current stack version of cluster " + getClusterName());
    }

    if (state != sourceClusterVersion.getState()) {
      throw new AmbariException("Unable to transition cluster hosts into " + state
          + ". The only valid state is " + sourceClusterVersion.getState());
    }

    Map<String, Host> hosts = clusters.getHostsForCluster(getClusterName());
    Set<String> existingHostsWithClusterStackAndVersion = new HashSet<>();
    HashMap<String, HostVersionEntity> existingHostStackVersions = new HashMap<>();

    clusterGlobalLock.writeLock().lock();
    try {
      StackEntity repoVersionStackEntity = sourceClusterVersion.getRepositoryVersion().getStack();
      StackId repoVersionStackId = new StackId(repoVersionStackEntity);

      List<HostVersionEntity> existingHostVersionEntities = hostVersionDAO.findByClusterStackAndVersion(
          getClusterName(), repoVersionStackId,
          sourceClusterVersion.getRepositoryVersion().getVersion());

      // for each host that already has a stack and version, keep track of them
      for (HostVersionEntity entity : existingHostVersionEntities) {
        String hostName = entity.getHostName();
        existingHostsWithClusterStackAndVersion.add(hostName);
        existingHostStackVersions.put(hostName, entity);
      }

      // find any hosts that do not have the stack/repo version already
      Sets.SetView<String> hostsMissingRepoVersion = Sets.difference(
        hosts.keySet(), existingHostsWithClusterStackAndVersion);

      createOrUpdateHostVersionToState(sourceClusterVersion, hosts,
          existingHostStackVersions, hostsMissingRepoVersion, state);
    } finally {
      clusterGlobalLock.writeLock().unlock();
    }
  }

  /**
   * Moved out to a separate method due to performance reasons
   * Iterates over all hosts and creates or transitions existing host versions
   * to a given state. If host version for desired stack/version does not exist,
   * host version is created and initialized to a given state. Otherwise, existing
   * host version state is updated
   * Hosts in maintenance mode are auto skipped.
   *
   * @param sourceClusterVersion cluster version to be queried for a stack
   *                             name/version info when creating a new host version
   * @param hosts list of all hosts
   * @param existingHostStackVersions map of existing host versions to be updated
   * @param hostsMissingRepoVersion set of hostnames of hosts that have no desired host version
   * @param newState target host version state for transition
   */
  @Transactional
  void createOrUpdateHostVersionToState(ClusterVersionEntity sourceClusterVersion,
      Map<String, Host> hosts, HashMap<String, HostVersionEntity> existingHostStackVersions,
      Sets.SetView<String> hostsMissingRepoVersion, RepositoryVersionState newState) {

    for (String hostname : hosts.keySet()) {
      // start off with the requested new state for each host
      RepositoryVersionState repositoryVersionState = newState;

      // if the host is in maintenance mode, that's an explicit marker which
      // indicates that it should not be transitioned to INSTALLING; instead
      // they will be transitioned to OUT_OF_SYNC
      Host host = hosts.get(hostname);
      if (host.getMaintenanceState(getClusterId()) != MaintenanceState.OFF) {
        repositoryVersionState = RepositoryVersionState.OUT_OF_SYNC;
      }

      if (hostsMissingRepoVersion.contains(hostname)) {
        // Create new host stack version
        HostEntity hostEntity = hostDAO.findByName(hostname);
        HostVersionEntity hostVersionEntity = new HostVersionEntity(hostEntity,
            sourceClusterVersion.getRepositoryVersion(), repositoryVersionState);

        LOG.info("Creating host version for {}, state={}, repo={} (repo_id={})",
            hostVersionEntity.getHostName(), hostVersionEntity.getState(),
            hostVersionEntity.getRepositoryVersion().getVersion(), hostVersionEntity.getRepositoryVersion().getId());

        hostVersionDAO.create(hostVersionEntity);
      } else {
        // Update existing host stack version
        HostVersionEntity hostVersionEntity = existingHostStackVersions.get(hostname);
        hostVersionEntity.setState(repositoryVersionState);
        hostVersionEntity = hostVersionDAO.merge(hostVersionEntity);
      }
    }
  }

  /**
   * Calculate the effective Cluster Version State based on the state of its hosts.
   *
   * CURRENT: all hosts are CURRENT
   * INSTALLED: all hosts in INSTALLED
   * INSTALL_FAILED: at least one host in INSTALL_FAILED
   * INSTALLING: all hosts in INSTALLING -or- INSTALLING and NOT_REQUIRED. Notice that if one host is CURRENT and another is INSTALLING, then the
   * effective version will be OUT_OF_SYNC.
   * OUT_OF_SYNC: otherwise
   * @param stateToHosts Map from state to the collection of hosts with that state
   * @return Return the effective Cluster Version State
   */
  private RepositoryVersionState getEffectiveState(Map<RepositoryVersionState, Set<String>> stateToHosts) {
    if (stateToHosts == null || stateToHosts.size() < 1) {
      return null;
    }

    int totalHosts = 0;
    for (Set<String> hosts : stateToHosts.values()) {
      totalHosts += hosts.size();
    }

    if (stateToHosts.containsKey(RepositoryVersionState.CURRENT) && stateToHosts.get(RepositoryVersionState.CURRENT).size() == totalHosts) {
      return RepositoryVersionState.CURRENT;
    }
    if (stateToHosts.containsKey(RepositoryVersionState.INSTALLED) && stateToHosts.get(RepositoryVersionState.INSTALLED).size() == totalHosts) {
      return RepositoryVersionState.INSTALLED;
    }
    if (stateToHosts.containsKey(RepositoryVersionState.INSTALL_FAILED) &&
      !stateToHosts.get(RepositoryVersionState.INSTALL_FAILED).isEmpty()) {
      // Installation failed on some host(s). But
      // cluster version state should transition to Install Failed only after
      // all hosts have finished installation. Otherwise, UI will misbehave
      // (hide progress dialog before installation is finished)
      if (! stateToHosts.containsKey(RepositoryVersionState.INSTALLING) ||
        stateToHosts.get(RepositoryVersionState.INSTALLING).isEmpty()) {
        return RepositoryVersionState.INSTALL_FAILED;
      }
    }

    int totalInstalling = stateToHosts.containsKey(RepositoryVersionState.INSTALLING) ? stateToHosts.get(RepositoryVersionState.INSTALLING).size() : 0;
    int totalInstalled = stateToHosts.containsKey(RepositoryVersionState.INSTALLED) ? stateToHosts.get(RepositoryVersionState.INSTALLED).size() : 0;
    int totalNotRequired = stateToHosts.containsKey(RepositoryVersionState.NOT_REQUIRED) ? stateToHosts.get(RepositoryVersionState.NOT_REQUIRED).size() : 0;
    int totalInstallFailed = stateToHosts.containsKey(RepositoryVersionState.INSTALL_FAILED) ? stateToHosts.get(RepositoryVersionState.INSTALL_FAILED).size() : 0;

    if (totalInstalling + totalInstalled + totalInstallFailed == totalHosts) {
      return RepositoryVersionState.INSTALLING;
    }

    if (totalNotRequired > 0) {

      // !!! if all we have is NOT_REQUIRED and something else, the return should be the something else
      if (2 == stateToHosts.size()) {

        Map<RepositoryVersionState, Set<String>> map = Maps.filterKeys(stateToHosts,
            new com.google.common.base.Predicate<RepositoryVersionState>() {
              @Override
              public boolean apply(RepositoryVersionState repoState) {
                return repoState != RepositoryVersionState.NOT_REQUIRED;
              }
            });

        // !!! better be true
        if (1 == map.size()) {
          return map.keySet().iterator().next();
        } else {
          LOG.warn("The mix of NON_REQUIRED hosts is unexpected: {}", stateToHosts);
          return RepositoryVersionState.OUT_OF_SYNC;
        }
      }

      // if any hosts are still installing, then cluster is INSTALLING
      if (totalInstalling > 0) {
        return RepositoryVersionState.INSTALLING;
      }

      // if any hosts are install_failed, then cluster is INSTALL_FAILED
      if (totalInstallFailed > 0) {
        return RepositoryVersionState.INSTALL_FAILED;
      }

      // should be covered by the 2-state check above
      if (totalInstalled > 0) {
        return RepositoryVersionState.INSTALLED;
      }

      // rare
      if (totalNotRequired == totalHosts) {
        return RepositoryVersionState.NOT_REQUIRED;
      }

    }

    // Also returns when have a mix of CURRENT and INSTALLING|INSTALLED
    LOG.warn("Have a mix of CURRENT and INSTALLING|INSTALLED host versions, " +
      "returning OUT_OF_SYNC as cluster version. Host version states: {}", stateToHosts);
    return RepositoryVersionState.OUT_OF_SYNC;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void recalculateClusterVersionState(RepositoryVersionEntity repositoryVersion) throws AmbariException {
    if (repositoryVersion == null) {
      return;
    }

    StackId stackId = repositoryVersion.getStackId();
    String version = repositoryVersion.getVersion();

    Map<String, Host> hosts = clusters.getHostsForCluster(getClusterName());
    clusterGlobalLock.writeLock().lock();

    try {
      // Part 1, bootstrap cluster version if necessary.

      ClusterVersionEntity clusterVersion = clusterVersionDAO.findByClusterAndStackAndVersion(
          getClusterName(), stackId, version);

      boolean performingInitialBootstrap = false;
      if (clusterVersion == null) {
        if (clusterVersionDAO.findByCluster(getClusterName()).isEmpty()) {
          // During an Ambari Upgrade from 1.7.0 -> 2.0.0, the Cluster Version
          // will not exist, so bootstrap it.
          // This can still fail if the Repository Version has not yet been created,
          // which can happen if the first HostComponentState to trigger this method
          // cannot advertise a version.
          performingInitialBootstrap = true;
          createClusterVersionInternal(
              stackId,
              version,
              AuthorizationHelper.getAuthenticatedName(configuration.getAnonymousAuditName()),
              RepositoryVersionState.INSTALLING);
          clusterVersion = clusterVersionDAO.findByClusterAndStackAndVersion(
              getClusterName(), stackId, version);

          if (clusterVersion == null) {
            LOG.warn(String.format(
                "Could not create a cluster version for cluster %s and stack %s using repo version %s",
                getClusterName(), stackId.getStackId(), repositoryVersion));
            return;
          }
        } else {
          LOG.warn(String.format(
              "Repository version %s not found for cluster %s",
              repositoryVersion, getClusterName()));
          return;
        }
      }

      // Ignore if cluster version is CURRENT or UPGRADE_FAILED
      if (clusterVersion.getState() != RepositoryVersionState.INSTALL_FAILED &&
              clusterVersion.getState() != RepositoryVersionState.OUT_OF_SYNC &&
              clusterVersion.getState() != RepositoryVersionState.INSTALLING &&
              clusterVersion.getState() != RepositoryVersionState.INSTALLED &&
              clusterVersion.getState() != RepositoryVersionState.INIT) {
        // anything else is not supported as of now
        return;
      }

      // Part 2, check for transitions.
      Set<String> hostsWithoutHostVersion = new HashSet<>();
      Map<RepositoryVersionState, Set<String>> stateToHosts = new HashMap<>();

      //hack until better hostversion integration into in-memory cluster structure

      List<HostVersionEntity> hostVersionEntities =
              hostVersionDAO.findByClusterStackAndVersion(getClusterName(), stackId, version);

      Set<String> hostsWithState = new HashSet<>();
      Set<String> hostsInMaintenanceState = new HashSet<>();
      for (HostVersionEntity hostVersionEntity : hostVersionEntities) {
        String hostname = hostVersionEntity.getHostEntity().getHostName();
        Host host = hosts.get(hostname);
        if(host != null && host.getMaintenanceState(getClusterId()) == MaintenanceState.ON) {
          hostsInMaintenanceState.add(hostname);
          continue;
        }
        hostsWithState.add(hostname);
        RepositoryVersionState hostState = hostVersionEntity.getState();

        if (stateToHosts.containsKey(hostState)) {
          stateToHosts.get(hostState).add(hostname);
        } else {
          Set<String> hostsInState = new HashSet<>();
          hostsInState.add(hostname);
          stateToHosts.put(hostState, hostsInState);
        }
      }

      hostsWithoutHostVersion.addAll(hosts.keySet());
      hostsWithoutHostVersion.removeAll(hostsWithState);
      hostsWithoutHostVersion.removeAll(hostsInMaintenanceState);

      // Ensure that all of the hosts without a Host Version only have
      // Components that do not advertise a version.
      // Otherwise, operations are still in progress.
      for (String hostname : hostsWithoutHostVersion) {
        HostEntity hostEntity = hostDAO.findByName(hostname);

        // During initial bootstrap, unhealthy hosts are ignored
        // so we boostrap the CURRENT version anyway
        if (performingInitialBootstrap &&
                hostEntity.getHostStateEntity().getCurrentState() != HostState.HEALTHY) {
          continue;
        }

        final Collection<HostComponentStateEntity> allHostComponents = hostEntity.getHostComponentStateEntities();

        for (HostComponentStateEntity hostComponentStateEntity : allHostComponents) {
          if (hostComponentStateEntity.getVersion().equalsIgnoreCase(
              State.UNKNOWN.toString())) {
            // Some Components cannot advertise a version. E.g., ZKF, AMBARI_METRICS,
            // Kerberos
            ComponentInfo compInfo = ambariMetaInfo.getComponent(
                stackId.getStackName(), stackId.getStackVersion(),
                hostComponentStateEntity.getServiceName(),
                hostComponentStateEntity.getComponentName());

            if (compInfo.isVersionAdvertised()) {
              LOG.debug("Skipping transitioning the cluster version because host "
                  + hostname + " does not have a version yet.");
              return;
            }
          }
        }
      }

      RepositoryVersionState effectiveClusterVersionState = getEffectiveState(stateToHosts);

      if (effectiveClusterVersionState != null
          && effectiveClusterVersionState != clusterVersion.getState()) {
        // Any mismatch will be caught while transitioning, and raise an
        // exception.
        try {
          transitionClusterVersion(stackId, version,
              effectiveClusterVersionState);
        } catch (AmbariException e) {
          ;
        }
      }
    } finally {
      clusterGlobalLock.writeLock().unlock();
    }
  }

  /**
   * Transition the Host Version across states.
   * @param host Host object
   * @param repositoryVersion Repository Version with stack and version information
   * @param stack Stack information
   * @throws AmbariException
   */
  @Override
  @Transactional
  public HostVersionEntity transitionHostVersionState(HostEntity host, final RepositoryVersionEntity repositoryVersion, final StackId stack) throws AmbariException {
    StackEntity repoVersionStackEntity = repositoryVersion.getStack();
    StackId repoVersionStackId = new StackId(repoVersionStackEntity);

    HostVersionEntity hostVersionEntity = hostVersionDAO.findByClusterStackVersionAndHost(
      getClusterId(), repoVersionStackId, repositoryVersion.getVersion(),
      host.getHostId());

    hostTransitionStateWriteLock.lock();
    try {
      // Create one if it doesn't already exist. It will be possible to make further transitions below.
      boolean performingInitialBootstrap = false;
      if (hostVersionEntity == null) {
        if (hostVersionDAO.findByClusterAndHost(getClusterName(), host.getHostName()).isEmpty()) {
          // That is an initial bootstrap
          performingInitialBootstrap = true;
        }
        hostVersionEntity = new HostVersionEntity(host, repositoryVersion, RepositoryVersionState.INSTALLING);

        LOG.info("Creating host version for {}, state={}, repo={} (repo_id={})",
            hostVersionEntity.getHostName(), hostVersionEntity.getState(),
            hostVersionEntity.getRepositoryVersion().getVersion(), hostVersionEntity.getRepositoryVersion().getId());

        hostVersionDAO.create(hostVersionEntity);
      }

      HostVersionEntity currentVersionEntity = hostVersionDAO.findByHostAndStateCurrent(getClusterId(), host.getHostId());
      boolean isCurrentPresent = (currentVersionEntity != null);
      final ServiceComponentHostSummary hostSummary = new ServiceComponentHostSummary(ambariMetaInfo, host, stack);

      if (!isCurrentPresent) {
        // Transition from UPGRADING -> CURRENT. This is allowed because Host Version Entity is bootstrapped in an UPGRADING state.
        // Alternatively, transition to CURRENT during initial bootstrap if at least one host component advertised a version
        if (hostSummary.isUpgradeFinished() || performingInitialBootstrap) {
          hostVersionEntity.setState(RepositoryVersionState.CURRENT);
          hostVersionEntity = hostVersionDAO.merge(hostVersionEntity);
        }
      } else {
        // Handle transitions during a Stack Upgrade
        if (hostSummary.isUpgradeFinished() && hostVersionEntity.getState().equals(RepositoryVersionState.INSTALLED)) {
          currentVersionEntity.setState(RepositoryVersionState.INSTALLED);
          hostVersionEntity.setState(RepositoryVersionState.CURRENT);

          hostVersionDAO.merge(currentVersionEntity);
          hostVersionEntity = hostVersionDAO.merge(hostVersionEntity);
        }
      }
    } finally {
      hostTransitionStateWriteLock.unlock();
    }
    return hostVersionEntity;
  }

  @Override
  public void recalculateAllClusterVersionStates() throws AmbariException {
    clusterGlobalLock.writeLock().lock();
    try {
      List<ClusterVersionEntity> clusterVersionEntities = clusterVersionDAO.findByCluster(getClusterName());
      StackId currentStackId = getCurrentStackVersion();
      for (ClusterVersionEntity clusterVersionEntity : clusterVersionEntities) {
        RepositoryVersionEntity repositoryVersionEntity = clusterVersionEntity.getRepositoryVersion();
        StackId repoVersionStackId = repositoryVersionEntity.getStackId();

        if (repoVersionStackId.equals(currentStackId)
            && clusterVersionEntity.getState() != RepositoryVersionState.CURRENT) {
          recalculateClusterVersionState(clusterVersionEntity.getRepositoryVersion());
        }
      }
    } finally {
      clusterGlobalLock.writeLock().unlock();
    }
  }

  @Override
  public void createClusterVersion(StackId stackId, String version,
      String userName, RepositoryVersionState state) throws AmbariException {
    clusterGlobalLock.writeLock().lock();
    try {
      createClusterVersionInternal(stackId, version, userName, state);
    } finally {
      clusterGlobalLock.writeLock().unlock();
    }
  }

  /**
   * See {@link #createClusterVersion}
   *
   * This method is intended to be called only when cluster lock is already acquired.
   */
  private void createClusterVersionInternal(StackId stackId, String version,
      String userName, RepositoryVersionState state) throws AmbariException {
    if (!ALLOWED_REPOSITORY_STATES.contains(state)) {
      throw new AmbariException("The allowed state for a new cluster version must be within " + ALLOWED_REPOSITORY_STATES);
    }

    ClusterVersionEntity existing = clusterVersionDAO.findByClusterAndStackAndVersion(
      getClusterName(), stackId, version);
    if (existing != null) {
      throw new DuplicateResourceException(
          "Duplicate item, a cluster version with stack=" + stackId
              + ", version=" +
          version + " for cluster " + getClusterName() + " already exists");
    }

    RepositoryVersionEntity repositoryVersionEntity = repositoryVersionDAO.findByStackAndVersion(
      stackId, version);
    if (repositoryVersionEntity == null) {
      LOG.warn("Could not find repository version for stack=" + stackId
          + ", version=" + version);
      return;
    }

    ClusterEntity clusterEntity = getClusterEntity();
    ClusterVersionEntity clusterVersionEntity = new ClusterVersionEntity(
        clusterEntity, repositoryVersionEntity, state,
      System.currentTimeMillis(), System.currentTimeMillis(), userName);
    clusterVersionDAO.create(clusterVersionEntity);
    clusterEntity.getClusterVersionEntities().add(clusterVersionEntity);
    clusterEntity = clusterDAO.merge(clusterEntity);
  }

  /**
   * Transition an existing cluster version from one state to another. The
   * following are some of the steps that are taken when transitioning between
   * specific states:
   * <ul>
   * <li>INSTALLING/INSTALLED --> CURRENT</lki>: Set the current stack to the
   * desired stack, ensure all hosts with the desired stack are CURRENT as well.
   * </ul>
   * <li>INSTALLING/INSTALLED --> CURRENT</lki>: Set the current stack to the
   * desired stack. </ul>
   *
   * @param stackId
   *          Stack ID
   * @param version
   *          Stack version
   * @param state
   *          Desired state
   * @throws AmbariException
   */
  @Override
  @Transactional
  public void transitionClusterVersion(StackId stackId, String version,
      RepositoryVersionState state) throws AmbariException {
    Set<RepositoryVersionState> allowedStates = new HashSet<>();
    clusterGlobalLock.writeLock().lock();
    try {
      ClusterEntity clusterEntity = getClusterEntity();
      ClusterVersionEntity existingClusterVersion = clusterVersionDAO.findByClusterAndStackAndVersion(
          getClusterName(), stackId, version);

      if (existingClusterVersion == null) {
        throw new AmbariException("Existing cluster version not found for cluster="
            + getClusterName() + ", stack=" + stackId + ", version=" + version);
      }

      // NOOP
      if (existingClusterVersion.getState() == state) {
        return;
      }

      switch (existingClusterVersion.getState()) {
        case CURRENT:
          // If CURRENT state is changed here cluster will not have CURRENT
          // state.
          // CURRENT state will be changed to INSTALLED when another CURRENT
          // state is added.
          // allowedStates.add(RepositoryVersionState.INSTALLED);
          break;
        case INSTALLING:
          allowedStates.add(RepositoryVersionState.INSTALLED);
          allowedStates.add(RepositoryVersionState.INSTALL_FAILED);
          allowedStates.add(RepositoryVersionState.OUT_OF_SYNC);
          if (clusterVersionDAO.findByClusterAndStateCurrent(getClusterName()) == null) {
            allowedStates.add(RepositoryVersionState.CURRENT);
          }
          break;
        case INSTALL_FAILED:
          allowedStates.add(RepositoryVersionState.INSTALLING);
          break;
        case INSTALLED:
          allowedStates.add(RepositoryVersionState.INSTALLING);
          allowedStates.add(RepositoryVersionState.OUT_OF_SYNC);
          allowedStates.add(RepositoryVersionState.CURRENT);
          break;
        case OUT_OF_SYNC:
          allowedStates.add(RepositoryVersionState.INSTALLING);
          break;
        case INIT:
          allowedStates.add(RepositoryVersionState.CURRENT);
          break;
      }

      if (!allowedStates.contains(state)) {
        throw new AmbariException("Invalid cluster version transition from "
            + existingClusterVersion.getState() + " to " + state);
      }

      // There must be at most one cluster version whose state is CURRENT at
      // all times.
      if (state == RepositoryVersionState.CURRENT) {
        ClusterVersionEntity currentVersion = clusterVersionDAO.findByClusterAndStateCurrent(
            getClusterName());
        if (currentVersion != null) {
          currentVersion.setState(RepositoryVersionState.INSTALLED);
          currentVersion = clusterVersionDAO.merge(currentVersion);
        }
      }

      existingClusterVersion.setState(state);
      existingClusterVersion.setEndTime(System.currentTimeMillis());
      existingClusterVersion = clusterVersionDAO.merge(existingClusterVersion);

      if (state == RepositoryVersionState.CURRENT) {
        for (HostEntity hostEntity : clusterEntity.getHostEntities()) {
          if (hostHasReportables(existingClusterVersion.getRepositoryVersion(), hostEntity)) {
            continue;
          }

          Collection<HostVersionEntity> versions = hostVersionDAO.findByHost(
              hostEntity.getHostName());

          HostVersionEntity target = null;
          if (null != versions) {
            // Set anything that was previously marked CURRENT as INSTALLED, and
            // the matching version as CURRENT
            for (HostVersionEntity entity : versions) {
              if (entity.getRepositoryVersion().getId().equals(
                  existingClusterVersion.getRepositoryVersion().getId())) {
                target = entity;
                target.setState(state);
                target = hostVersionDAO.merge(target);
              } else if (entity.getState() == RepositoryVersionState.CURRENT) {
                entity.setState(RepositoryVersionState.INSTALLED);
                entity = hostVersionDAO.merge(entity);
              }
            }
          }

          if (null == target) {
            // If no matching version was found, create one with the desired
            // state
            HostVersionEntity hve = new HostVersionEntity(hostEntity,
                existingClusterVersion.getRepositoryVersion(), state);

            LOG.info("Creating host version for {}, state={}, repo={} (repo_id={})",
                hve.getHostName(), hve.getState(), hve.getRepositoryVersion().getVersion(),
                hve.getRepositoryVersion().getId());

            hostVersionDAO.create(hve);
          }
        }

        // when setting the cluster's state to current, we must also
        // bring the desired stack and current stack in line with each other
        StackEntity desiredStackEntity = clusterEntity.getDesiredStack();
        StackId desiredStackId = new StackId(desiredStackEntity);

        // if the desired stack ID doesn't match the target when setting the
        // cluster to CURRENT, then there's a problem
        if (!desiredStackId.equals(stackId)) {
          String message = MessageFormat.format(
              "The desired stack ID {0} must match {1} when transitioning the cluster''s state to {2}",
              desiredStackId, stackId, RepositoryVersionState.CURRENT);

          throw new AmbariException(message);
        }

        setCurrentStackVersion(stackId);
      }
    } catch (RollbackException e) {
      String message = MessageFormat.format(
        "Unable to transition stack {0} at version {1} for cluster {2} to state {3}",
        stackId, version, getClusterName(), state);

      LOG.warn(message);
      throw new AmbariException(message, e);

    } finally {
      clusterGlobalLock.writeLock().unlock();
    }

  }

  /**
   * Checks if the host has any components reporting version information.
   * @param repoVersion the repo version
   * @param host        the host entity
   * @return {@code true} if the host has any component that report version
   * @throws AmbariException
   */
  private boolean hostHasReportables(RepositoryVersionEntity repoVersion, HostEntity host)
      throws AmbariException {

    for (HostComponentStateEntity hcse : host.getHostComponentStateEntities()) {
      ComponentInfo ci = ambariMetaInfo.getComponent(
          repoVersion.getStackName(),
          repoVersion.getStackVersion(),
          hcse.getServiceName(),
          hcse.getComponentName());

      if (ci.isVersionAdvertised()) {
        return true;
      }
    }
    return false;
  }

  @Override
  @Transactional
  public void setCurrentStackVersion(StackId stackId) throws AmbariException {
    clusterGlobalLock.writeLock().lock();
    try {
      StackEntity stackEntity = stackDAO.find(stackId.getStackName(),
        stackId.getStackVersion());

      ClusterEntity clusterEntity = getClusterEntity();
      ClusterStateEntity clusterStateEntity = clusterStateDAO.findByPK(
          clusterEntity.getClusterId());
      if (clusterStateEntity == null) {
        clusterStateEntity = new ClusterStateEntity();
        clusterStateEntity.setClusterId(clusterEntity.getClusterId());
        clusterStateEntity.setCurrentStack(stackEntity);
        clusterStateEntity.setClusterEntity(clusterEntity);
        clusterStateDAO.create(clusterStateEntity);
        clusterStateEntity = clusterStateDAO.merge(clusterStateEntity);
        clusterEntity.setClusterStateEntity(clusterStateEntity);
        clusterEntity = clusterDAO.merge(clusterEntity);
      } else {
        clusterStateEntity.setCurrentStack(stackEntity);
        clusterStateEntity = clusterStateDAO.merge(clusterStateEntity);
        clusterEntity = clusterDAO.merge(clusterEntity);
      }
    } catch (RollbackException e) {
      LOG.warn("Unable to set version " + stackId + " for cluster "
          + getClusterName());
      throw new AmbariException("Unable to set" + " version=" + stackId
          + " for cluster " + getClusterName(), e);
    } finally {
      clusterGlobalLock.writeLock().unlock();
    }
  }

  @Override
  public Map<String, Config> getConfigsByType(String configType) {
    clusterGlobalLock.readLock().lock();
    try {
      if (!allConfigs.containsKey(configType)) {
        return null;
      }

      return Collections.unmodifiableMap(allConfigs.get(configType));
    } finally {
      clusterGlobalLock.readLock().unlock();
    }
  }

  @Override
  public Config getConfig(String configType, String versionTag) {
    clusterGlobalLock.readLock().lock();
    try {
      if (!allConfigs.containsKey(configType)
          || !allConfigs.get(configType).containsKey(versionTag)) {
        return null;
      }
      return allConfigs.get(configType).get(versionTag);
    } finally {
      clusterGlobalLock.readLock().unlock();
    }
  }

  @Override
  public Config getConfigByVersion(String configType, Long configVersion) {
    clusterGlobalLock.readLock().lock();
    try {
      if (!allConfigs.containsKey(configType)) {
        return null;
      }

      for (Map.Entry<String, Config> entry : allConfigs.get(configType).entrySet()) {
        if (entry.getValue().getVersion().equals(configVersion)) {
          return entry.getValue();
        }
      }

      return null;
    } finally {
      clusterGlobalLock.readLock().unlock();
    }
  }

  @Override
  public void addConfig(Config config) {
    if (config.getType() == null || config.getType().isEmpty()) {
      throw new IllegalArgumentException("Config type cannot be empty");
    }

    clusterGlobalLock.writeLock().lock();
    try {
      if (!allConfigs.containsKey(config.getType())) {
        allConfigs.put(config.getType(), new ConcurrentHashMap<String, Config>());
      }

      allConfigs.get(config.getType()).put(config.getTag(), config);
    } finally {
      clusterGlobalLock.writeLock().unlock();
    }
  }

  @Override
  public Collection<Config> getAllConfigs() {
    clusterGlobalLock.readLock().lock();
    try {
      List<Config> list = new ArrayList<>();
      for (Entry<String, ConcurrentMap<String, Config>> entry : allConfigs.entrySet()) {
        for (Config config : entry.getValue().values()) {
          list.add(config);
        }
      }
      return Collections.unmodifiableList(list);
    } finally {
      clusterGlobalLock.readLock().unlock();
    }
  }

  @Override
  public ClusterResponse convertToResponse()
    throws AmbariException {
    String clusterName = getClusterName();
    Map<String, Host> hosts = clusters.getHostsForCluster(clusterName);

    return new ClusterResponse(getClusterId(), clusterName,
        getProvisioningState(), getSecurityType(), hosts.keySet(),
        hosts.size(), getDesiredStackVersion().getStackId(),
        getClusterHealthReport(hosts));
  }

  @Override
  public void debugDump(StringBuilder sb) {
    sb.append("Cluster={ clusterName=").append(getClusterName()).append(", clusterId=").append(
        getClusterId()).append(", desiredStackVersion=").append(
            desiredStackVersion.getStackId()).append(", services=[ ");
    boolean first = true;
    for (Service s : services.values()) {
      if (!first) {
        sb.append(" , ");
      }
      first = false;
      sb.append("\n    ");
      s.debugDump(sb);
      sb.append(' ');
    }
    sb.append(" ] }");
    lockFactory.debugDump(sb);
  }

  @Override
  @Transactional
  public void refresh() {
    clusterGlobalLock.writeLock().lock();
    try {
      ClusterEntity clusterEntity = getClusterEntity();
      clusterDAO.refresh(clusterEntity);
    } finally {
      clusterGlobalLock.writeLock().unlock();
    }
  }

  @Override
  @Transactional
  public void deleteAllServices() throws AmbariException {
    clusterGlobalLock.writeLock().lock();
    try {
      LOG.info("Deleting all services for cluster" + ", clusterName="
        + getClusterName());
      for (Service service : services.values()) {
        if (!service.canBeRemoved()) {
          throw new AmbariException(
              "Found non removable service when trying to"
                  + " all services from cluster" + ", clusterName="
                  + getClusterName() + ", serviceName=" + service.getName());
        }
      }

      for (Service service : services.values()) {
        deleteService(service);
      }
      services.clear();
    } finally {
      clusterGlobalLock.writeLock().unlock();
    }
  }

  @Override
  public void deleteService(String serviceName)
    throws AmbariException {
    clusterGlobalLock.writeLock().lock();
    try {
      Service service = getService(serviceName);
      LOG.info("Deleting service for cluster" + ", clusterName="
          + getClusterName() + ", serviceName=" + service.getName());
      // FIXME check dependencies from meta layer
      if (!service.canBeRemoved()) {
        throw new AmbariException("Could not delete service from cluster"
          + ", clusterName=" + getClusterName()
          + ", serviceName=" + service.getName());
      }
      deleteService(service);
      services.remove(serviceName);

    } finally {
      clusterGlobalLock.writeLock().unlock();
    }
  }

  /**
   * Deletes the specified service also removes references to it from {@link this.serviceComponentHosts}
   * and references to ServiceComponentHost objects that belong to the service from {@link this.serviceComponentHostsByHost}
   * <p>
   *   Note: This method must be called only with write lock acquired.
   * </p>
   * @param service the service to be deleted
   * @throws AmbariException
   * @see   ServiceComponentHost
   */
  private void deleteService(Service service) throws AmbariException {
    final String serviceName = service.getName();

    service.delete();

    serviceComponentHosts.remove(serviceName);

    for (List<ServiceComponentHost> serviceComponents: serviceComponentHostsByHost.values()){
      Iterables.removeIf(serviceComponents, new Predicate<ServiceComponentHost>() {
        @Override
        public boolean apply(ServiceComponentHost serviceComponentHost) {
          return serviceComponentHost.getServiceName().equals(serviceName);
        }
      });
    }
  }

  @Override
  public boolean canBeRemoved() {
    clusterGlobalLock.readLock().lock();
    try {
      boolean safeToRemove = true;
      for (Service service : services.values()) {
        if (!service.canBeRemoved()) {
          safeToRemove = false;
          LOG.warn("Found non removable service" + ", clusterName="
              + getClusterName() + ", serviceName=" + service.getName());
        }
      }
      return safeToRemove;
    } finally {
      clusterGlobalLock.readLock().unlock();
    }
  }

  @Override
  @Transactional
  public void delete() throws AmbariException {
    clusterGlobalLock.writeLock().lock();
    try {
      refresh();
      deleteAllServices();

      refresh(); // update one-to-many clusterServiceEntities
      removeEntities();
      allConfigs.clear();
    } finally {
      clusterGlobalLock.writeLock().unlock();
    }
  }

  @Transactional
  protected void removeEntities() throws AmbariException {
    long clusterId = getClusterId();
    alertDefinitionDAO.removeAll(clusterId);
    alertDispatchDAO.removeAllGroups(clusterId);
    upgradeDAO.removeAll(clusterId);
    topologyRequestDAO.removeAll(clusterId);
    clusterDAO.removeByPK(clusterId);
  }

  @Override
  public ServiceConfigVersionResponse addDesiredConfig(String user, Set<Config> configs) {
    return addDesiredConfig(user, configs, null);
  }

  @Override
  public ServiceConfigVersionResponse addDesiredConfig(String user, Set<Config> configs, String serviceConfigVersionNote) {
    if (null == user) {
      throw new NullPointerException("User must be specified.");
    }

    clusterGlobalLock.writeLock().lock();
    try {
      if (configs == null) {
        return null;
      }

      Iterator<Config> configIterator = configs.iterator();

      while (configIterator.hasNext()) {
        Config config = configIterator.next();
        if (config == null) {
          configIterator.remove();
          continue;
        }
        Config currentDesired = getDesiredConfigByType(config.getType());

        // do not set if it is already the current
        if (null != currentDesired
            && currentDesired.getTag().equals(config.getTag())) {
          configIterator.remove();
        }
      }

      ServiceConfigVersionResponse serviceConfigVersionResponse = applyConfigs(
          configs, user, serviceConfigVersionNote);

      return serviceConfigVersionResponse;
    } finally {
      clusterGlobalLock.writeLock().unlock();
    }
  }

  /**
   * Gets all versions of the desired configurations for the cluster.
   * @return a map of type-to-configuration information.
   */
  @Override
  public Map<String, Set<DesiredConfig>> getAllDesiredConfigVersions() {
    return getDesiredConfigs(true);
  }


  @Override
  public Map<String, DesiredConfig> getDesiredConfigs() {
    Map<String, Set<DesiredConfig>> activeConfigsByType = getDesiredConfigs(false);
    return Maps.transformEntries(
        activeConfigsByType,
        new Maps.EntryTransformer<String, Set<DesiredConfig>, DesiredConfig>() {
          @Override
          public DesiredConfig transformEntry(@Nullable String key, @Nullable Set<DesiredConfig> value) {
            return value.iterator().next();
          }
        });
  }

  /**
   * Gets desired configurations for the cluster.
   * @param allVersions specifies if all versions of the desired configurations to be returned
   *                    or only the active ones. It is expected that there is one and only one active
   *                    desired configuration per config type.
   * @return a map of type-to-configuration information.
   */
  private Map<String, Set<DesiredConfig>> getDesiredConfigs(boolean allVersions) {
    clusterGlobalLock.readLock().lock();
    try {
      Map<String, Set<DesiredConfig>> map = new HashMap<>();
      Collection<String> types = new HashSet<>();
      Collection<ClusterConfigMappingEntity> entities = getClusterEntity().getConfigMappingEntities();

      for (ClusterConfigMappingEntity e : entities) {
        if (allVersions || e.isSelected() > 0) {
          DesiredConfig c = new DesiredConfig();
          c.setServiceName(null);
          c.setTag(e.getTag());
          c.setUser(e.getUser());
          if(!allConfigs.containsKey(e.getType())) {
            LOG.error("Config inconsistency exists:" +
                " unknown configType=" + e.getType());
            continue;
          }

          Map<String, Config> configMap = allConfigs.get(e.getType());
          if(!configMap.containsKey(e.getTag())) {
            LOG.debug("Config inconsistency exists for typeName=" +
                    e.getType() +
                    ", unknown versionTag=" + e.getTag());
            continue;
          }

          Config config = configMap.get(e.getTag());
          c.setVersion(config.getVersion());

          Set<DesiredConfig> configs = map.get(e.getType());
          if (configs == null) {
            configs = new HashSet<>();
          }

          configs.add(c);

          map.put(e.getType(), configs);
          types.add(e.getType());
        }
      }

      // TODO AMBARI-10679, need efficient caching from hostId to hostName...
      Map<Long, String> hostIdToName = new HashMap<>();

      if (!map.isEmpty()) {
        Map<String, List<HostConfigMapping>> hostMappingsByType =
          hostConfigMappingDAO.findSelectedHostsByTypes(clusterId, types);

        for (Entry<String, Set<DesiredConfig>> entry : map.entrySet()) {
          List<DesiredConfig.HostOverride> hostOverrides = new ArrayList<>();
          for (HostConfigMapping mappingEntity : hostMappingsByType.get(entry.getKey())) {

            if (!hostIdToName.containsKey(mappingEntity.getHostId())) {
              HostEntity hostEntity = hostDAO.findById(mappingEntity.getHostId());
              hostIdToName.put(mappingEntity.getHostId(), hostEntity.getHostName());
            }

            hostOverrides.add(new DesiredConfig.HostOverride(
                hostIdToName.get(mappingEntity.getHostId()), mappingEntity.getVersion()));
          }

          for (DesiredConfig c: entry.getValue()) {
            c.setHostOverrides(hostOverrides);
          }
        }
      }

      return map;
    } finally {
      clusterGlobalLock.readLock().unlock();
    }
  }



  @Override
  public ServiceConfigVersionResponse createServiceConfigVersion(
      String serviceName, String user, String note, ConfigGroup configGroup) {

    // Create next service config version
    ServiceConfigEntity serviceConfigEntity = new ServiceConfigEntity();

    clusterGlobalLock.writeLock().lock();
    try {
      ClusterEntity clusterEntity = getClusterEntity();
      // set config group
      if (configGroup != null) {
        serviceConfigEntity.setGroupId(configGroup.getId());
        Collection<Config> configs = configGroup.getConfigurations().values();
        List<ClusterConfigEntity> configEntities = new ArrayList<>(
            configs.size());
        for (Config config : configs) {
          configEntities.add(
              clusterDAO.findConfig(getClusterId(), config.getType(), config.getTag()));
        }

        serviceConfigEntity.setClusterConfigEntities(configEntities);
      } else {
        List<ClusterConfigEntity> configEntities = getClusterConfigEntitiesByService(serviceName);
        serviceConfigEntity.setClusterConfigEntities(configEntities);
      }


      long nextServiceConfigVersion = serviceConfigDAO.findNextServiceConfigVersion(clusterId,
          serviceName);

      serviceConfigEntity.setServiceName(serviceName);
      serviceConfigEntity.setClusterEntity(clusterEntity);
      serviceConfigEntity.setVersion(nextServiceConfigVersion);
      serviceConfigEntity.setUser(user);
      serviceConfigEntity.setNote(note);
      serviceConfigEntity.setStack(clusterEntity.getDesiredStack());

      serviceConfigDAO.create(serviceConfigEntity);
      if (configGroup != null) {
        serviceConfigEntity.setHostIds(new ArrayList<>(configGroup.getHosts().keySet()));
        serviceConfigEntity = serviceConfigDAO.merge(serviceConfigEntity);
      }
    } finally {
      clusterGlobalLock.writeLock().unlock();
    }

    String configGroupName = configGroup == null ? ServiceConfigVersionResponse.DEFAULT_CONFIG_GROUP_NAME : configGroup.getName();
    configChangeLog.info("(configchange) Creating config version. cluster: '{}', changed by: '{}', " +
            "service_name: '{}', config_group: '{}', config_group_id: '{}', version: '{}', create_timestamp: '{}', note: '{}'",
        getClusterName(), user, serviceName, configGroupName,
        configGroup == null ? "null" : configGroup.getId(), serviceConfigEntity.getVersion(), serviceConfigEntity.getCreateTimestamp(),
        serviceConfigEntity.getNote());

    ServiceConfigVersionResponse response = new ServiceConfigVersionResponse(
        serviceConfigEntity, configGroupName);

    return response;
  }

  @Override
  public String getServiceForConfigTypes(Collection<String> configTypes) {
    //debug
    LOG.info("Looking for service for config types {}", configTypes);
    String serviceName = null;
    for (String configType : configTypes) {
      for (Entry<String, String> entry : serviceConfigTypes.entries()) {
        if (StringUtils.equals(entry.getValue(), configType)) {
          if (serviceName != null) {
            if (entry.getKey()!=null && !StringUtils.equals(serviceName, entry.getKey())) {
              throw new IllegalArgumentException(String.format("Config type %s belongs to %s service, " +
                "but also qualified for %s", configType, serviceName, entry.getKey()));
            }
          } else {
            serviceName = entry.getKey();
          }
        }
      }
    }
    LOG.info("Service {} returning", serviceName);
    return serviceName;
  }

  @Override
  public String getServiceByConfigType(String configType) {
    for (Entry<String, String> entry : serviceConfigTypes.entries()) {
      String serviceName = entry.getKey();
      String type = entry.getValue();
      if (StringUtils.equals(type, configType)) {
        return serviceName;
      }
    }
    return null;
  }

  @Override
  public ServiceConfigVersionResponse setServiceConfigVersion(String serviceName, Long version, String user, String note) throws AmbariException {
    if (null == user) {
      throw new NullPointerException("User must be specified.");
    }

    clusterGlobalLock.writeLock().lock();
    try {
      ServiceConfigVersionResponse serviceConfigVersionResponse = applyServiceConfigVersion(
          serviceName, version, user, note);
      return serviceConfigVersionResponse;
    } finally {
      clusterGlobalLock.writeLock().unlock();
    }
  }

  @Override
  public Map<String, Collection<ServiceConfigVersionResponse>> getActiveServiceConfigVersions() {
    clusterGlobalLock.readLock().lock();
    try {
      Map<String, Collection<ServiceConfigVersionResponse>> map = new HashMap<>();

      Set<ServiceConfigVersionResponse> responses = getActiveServiceConfigVersionSet();
      for (ServiceConfigVersionResponse response : responses) {
        if (map.get(response.getServiceName()) == null) {
          map.put(response.getServiceName(),
              new ArrayList<ServiceConfigVersionResponse>());
        }
        map.get(response.getServiceName()).add(response);
      }
      return map;
    } finally {
      clusterGlobalLock.readLock().unlock();
    }
  }

  @Override
  public List<ServiceConfigVersionResponse> getServiceConfigVersions() {
    clusterGlobalLock.readLock().lock();
    try {
      List<ServiceConfigVersionResponse> serviceConfigVersionResponses = new ArrayList<>();

      List<ServiceConfigEntity> serviceConfigs = serviceConfigDAO.getServiceConfigs(getClusterId());

      // Gather for each service in each config group the active service config response  as we
      // iterate through all service config responses
      Map<String, Map<String, ServiceConfigVersionResponse>> activeServiceConfigResponses = new HashMap<>();

      for (ServiceConfigEntity serviceConfigEntity : serviceConfigs) {
        ServiceConfigVersionResponse serviceConfigVersionResponse = convertToServiceConfigVersionResponse(serviceConfigEntity);

        Map<String, ServiceConfigVersionResponse> activeServiceConfigResponseGroups = activeServiceConfigResponses.get(serviceConfigVersionResponse.getServiceName());

        if (activeServiceConfigResponseGroups == null) {
          Map<String, ServiceConfigVersionResponse> serviceConfigGroups = new HashMap<>();
          activeServiceConfigResponses.put(serviceConfigVersionResponse.getServiceName(), serviceConfigGroups);

          activeServiceConfigResponseGroups = serviceConfigGroups;
        }

        // the active config within a group
        ServiceConfigVersionResponse activeServiceConfigResponse = activeServiceConfigResponseGroups.get(serviceConfigVersionResponse.getGroupName());

        if (activeServiceConfigResponse == null && !ServiceConfigVersionResponse.DELETED_CONFIG_GROUP_NAME.equals(serviceConfigVersionResponse.getGroupName())) {
          // service config version with deleted group should always be marked is not current
          activeServiceConfigResponseGroups.put(serviceConfigVersionResponse.getGroupName(), serviceConfigVersionResponse);
          activeServiceConfigResponse = serviceConfigVersionResponse;
        }
        if (serviceConfigEntity.getGroupId() == null) {
          if (serviceConfigVersionResponse.getCreateTime() > activeServiceConfigResponse.getCreateTime()) {
            activeServiceConfigResponseGroups.put(serviceConfigVersionResponse.getGroupName(), serviceConfigVersionResponse);
          }
        }
        else if (clusterConfigGroups != null && clusterConfigGroups.containsKey(serviceConfigEntity.getGroupId())){
          if (serviceConfigVersionResponse.getVersion() > activeServiceConfigResponse.getVersion()) {
            activeServiceConfigResponseGroups.put(serviceConfigVersionResponse.getGroupName(), serviceConfigVersionResponse);
          }
        }

        serviceConfigVersionResponse.setIsCurrent(false);
        serviceConfigVersionResponses.add(getServiceConfigVersionResponseWithConfig(serviceConfigVersionResponse, serviceConfigEntity));
      }

      for (Map<String, ServiceConfigVersionResponse> serviceConfigVersionResponseGroup: activeServiceConfigResponses.values()) {
        for (ServiceConfigVersionResponse serviceConfigVersionResponse : serviceConfigVersionResponseGroup.values()) {
          serviceConfigVersionResponse.setIsCurrent(true);
        }
      }

      return serviceConfigVersionResponses;
    } finally {
      clusterGlobalLock.readLock().unlock();
    }
  }

  private Set<ServiceConfigVersionResponse> getActiveServiceConfigVersionSet() {
    Set<ServiceConfigVersionResponse> responses = new HashSet<>();
    List<ServiceConfigEntity> activeServiceConfigVersions = getActiveServiceConfigVersionEntities();

    for (ServiceConfigEntity lastServiceConfig : activeServiceConfigVersions) {
      ServiceConfigVersionResponse response = convertToServiceConfigVersionResponse(lastServiceConfig);
      response.setIsCurrent(true); //mark these as current, as they are
      responses.add(response);
    }
    return responses;
  }

  private List<ServiceConfigEntity> getActiveServiceConfigVersionEntities() {

    List<ServiceConfigEntity> activeServiceConfigVersions = new ArrayList<>();
    //for services
    activeServiceConfigVersions.addAll(serviceConfigDAO.getLastServiceConfigs(getClusterId()));
    //for config groups
    if (clusterConfigGroups != null) {
      activeServiceConfigVersions.addAll(
        serviceConfigDAO.getLastServiceConfigVersionsForGroups(clusterConfigGroups.keySet()));
    }

    return activeServiceConfigVersions;
  }

  @Override
  public List<ServiceConfigVersionResponse> getActiveServiceConfigVersionResponse(String serviceName) {
    clusterGlobalLock.readLock().lock();
    try {
      List<ServiceConfigEntity> activeServiceConfigVersionEntities = new ArrayList<>();
      List<ServiceConfigVersionResponse> activeServiceConfigVersionResponses = new ArrayList<>();
      activeServiceConfigVersionEntities.addAll(serviceConfigDAO.getLastServiceConfigsForService(getClusterId(), serviceName));
      for (ServiceConfigEntity serviceConfigEntity : activeServiceConfigVersionEntities) {
        ServiceConfigVersionResponse serviceConfigVersionResponse = getServiceConfigVersionResponseWithConfig(convertToServiceConfigVersionResponse(serviceConfigEntity), serviceConfigEntity);
        serviceConfigVersionResponse.setIsCurrent(true);
        activeServiceConfigVersionResponses.add(serviceConfigVersionResponse);
      }
      return activeServiceConfigVersionResponses;
    } finally {
      clusterGlobalLock.readLock().unlock();
    }
  }

  /**
   * Adds Configuration data to the serviceConfigVersionResponse
   * @param serviceConfigVersionResponse
   * @param serviceConfigEntity
   * @return serviceConfigVersionResponse
   */
  private ServiceConfigVersionResponse getServiceConfigVersionResponseWithConfig(ServiceConfigVersionResponse serviceConfigVersionResponse, ServiceConfigEntity serviceConfigEntity) {
    serviceConfigVersionResponse.setConfigurations(new ArrayList<ConfigurationResponse>());
    List<ClusterConfigEntity> clusterConfigEntities = serviceConfigEntity.getClusterConfigEntities();
    for (ClusterConfigEntity clusterConfigEntity : clusterConfigEntities) {
      Config config = allConfigs.get(clusterConfigEntity.getType()).get(
          clusterConfigEntity.getTag());

      serviceConfigVersionResponse.getConfigurations().add(
          new ConfigurationResponse(getClusterName(), config));
    }
    return serviceConfigVersionResponse;
  }


  @RequiresSession
  ServiceConfigVersionResponse getActiveServiceConfigVersion(String serviceName) {
    ServiceConfigEntity lastServiceConfig = serviceConfigDAO.getLastServiceConfig(getClusterId(), serviceName);
    if (lastServiceConfig == null) {
      LOG.debug("No service config version found for service {}", serviceName);
      return null;
    }
    return convertToServiceConfigVersionResponse(lastServiceConfig);
  }

  @RequiresSession
  ServiceConfigVersionResponse convertToServiceConfigVersionResponse(ServiceConfigEntity serviceConfigEntity) {
    Long groupId = serviceConfigEntity.getGroupId();

    String groupName;
    if (groupId != null) {
      ConfigGroup configGroup = null;
      if (clusterConfigGroups != null) {
        configGroup = clusterConfigGroups.get(groupId);
      }

      if (configGroup != null) {
        groupName = configGroup.getName();
      } else {
        groupName = ServiceConfigVersionResponse.DELETED_CONFIG_GROUP_NAME;
      }
    } else {
      groupName = ServiceConfigVersionResponse.DEFAULT_CONFIG_GROUP_NAME;
    }

    ServiceConfigVersionResponse serviceConfigVersionResponse = new ServiceConfigVersionResponse(
        serviceConfigEntity, groupName);

    return serviceConfigVersionResponse;
  }

  @Transactional
  ServiceConfigVersionResponse applyServiceConfigVersion(String serviceName, Long serviceConfigVersion, String user,
                                 String serviceConfigVersionNote) throws AmbariException {
    ServiceConfigEntity serviceConfigEntity = serviceConfigDAO.findByServiceAndVersion(serviceName, serviceConfigVersion);
    if (serviceConfigEntity == null) {
      throw new ObjectNotFoundException("Service config version with serviceName={} and version={} not found");
    }

    //disable all configs related to service
    if (serviceConfigEntity.getGroupId() == null) {
      Collection<String> configTypes = serviceConfigTypes.get(serviceName);
      List<ClusterConfigMappingEntity> mappingEntities =
          clusterDAO.getSelectedConfigMappingByTypes(getClusterId(), new ArrayList<>(configTypes));
      for (ClusterConfigMappingEntity entity : mappingEntities) {
        entity.setSelected(0);
        clusterDAO.mergeConfigMapping(entity);
      }

      for (ClusterConfigEntity configEntity : serviceConfigEntity.getClusterConfigEntities()) {
        selectConfig(configEntity.getType(), configEntity.getTag(), user);
      }
    } else {
      Long configGroupId = serviceConfigEntity.getGroupId();
      ConfigGroup configGroup = clusterConfigGroups.get(configGroupId);
      if (configGroup != null) {
        Map<String, Config> groupDesiredConfigs = new HashMap<>();
        for (ClusterConfigEntity entity : serviceConfigEntity.getClusterConfigEntities()) {
          Config config = allConfigs.get(entity.getType()).get(entity.getTag());
          groupDesiredConfigs.put(config.getType(), config);
        }
        configGroup.setConfigurations(groupDesiredConfigs);

        Map<Long, Host> groupDesiredHosts = new HashMap<>();
        if (serviceConfigEntity.getHostIds() != null) {
          for (Long hostId : serviceConfigEntity.getHostIds()) {
            Host host = clusters.getHostById(hostId);
            if (host != null) {
              groupDesiredHosts.put(hostId, host);
            } else {
              LOG.warn("Host with id {} doesn't exist anymore, skipping", hostId);
            }
          }
        }
        configGroup.setHosts(groupDesiredHosts);
      } else {
        throw new IllegalArgumentException("Config group {} doesn't exist");
      }
    }

    ClusterEntity clusterEntity = getClusterEntity();
    long nextServiceConfigVersion = serviceConfigDAO.findNextServiceConfigVersion(
        clusterEntity.getClusterId(), serviceName);

    ServiceConfigEntity serviceConfigEntityClone = new ServiceConfigEntity();
    serviceConfigEntityClone.setCreateTimestamp(System.currentTimeMillis());
    serviceConfigEntityClone.setUser(user);
    serviceConfigEntityClone.setServiceName(serviceName);
    serviceConfigEntityClone.setClusterEntity(clusterEntity);
    serviceConfigEntityClone.setStack(serviceConfigEntity.getStack());
    serviceConfigEntityClone.setClusterConfigEntities(serviceConfigEntity.getClusterConfigEntities());
    serviceConfigEntityClone.setClusterId(serviceConfigEntity.getClusterId());
    serviceConfigEntityClone.setHostIds(serviceConfigEntity.getHostIds());
    serviceConfigEntityClone.setGroupId(serviceConfigEntity.getGroupId());
    serviceConfigEntityClone.setNote(serviceConfigVersionNote);
    serviceConfigEntityClone.setVersion(nextServiceConfigVersion);

    serviceConfigDAO.create(serviceConfigEntityClone);

    return convertToServiceConfigVersionResponse(serviceConfigEntityClone);
  }

  @Transactional
  void selectConfig(String type, String tag, String user) {
    Collection<ClusterConfigMappingEntity> entities =
      clusterDAO.getLatestClusterConfigMappingsEntityByType(getClusterId(), type);

    //disable previous config
    for (ClusterConfigMappingEntity e : entities) {
      e.setSelected(0);
      clusterDAO.mergeConfigMapping(e);
    }

    ClusterEntity clusterEntity = getClusterEntity();
    ClusterConfigMappingEntity entity = new ClusterConfigMappingEntity();
    entity.setClusterEntity(clusterEntity);
    entity.setClusterId(clusterEntity.getClusterId());
    entity.setCreateTimestamp(System.currentTimeMillis());
    entity.setSelected(1);
    entity.setUser(user);
    entity.setType(type);
    entity.setTag(tag);
    clusterDAO.persistConfigMapping(entity);

    clusterEntity.getConfigMappingEntities().add(entity);
    clusterDAO.merge(clusterEntity);
  }

  @Transactional
  ServiceConfigVersionResponse applyConfigs(Set<Config> configs, String user, String serviceConfigVersionNote) {

    String serviceName = null;
    for (Config config : configs) {
      for (Entry<String, String> entry : serviceConfigTypes.entries()) {
        if (StringUtils.equals(entry.getValue(), config.getType())) {
          if (serviceName == null) {
            serviceName = entry.getKey();
            break;
          } else if (!serviceName.equals(entry.getKey())) {
            String error = String.format("Updating configs for multiple services by a " +
                "single API request isn't supported. Conflicting services %s and %s for %s",
                                         serviceName, entry.getKey(), config.getType());
            IllegalArgumentException exception = new IllegalArgumentException(error);
            LOG.error(error + ", config version not created for {}", serviceName);
            throw exception;
          } else {
            break;
          }
        }
      }
    }

    for (Config config: configs) {
      selectConfig(config.getType(), config.getTag(), user);
    }

    if (serviceName == null) {
      ArrayList<String> configTypes = new ArrayList<>();
      for (Config config: configs) {
        configTypes.add(config.getType());
      }
      LOG.error("No service found for config types '{}', service config version not created", configTypes);
      return null;
    } else {
      return createServiceConfigVersion(serviceName, user, serviceConfigVersionNote);
    }

  }

  private ServiceConfigVersionResponse createServiceConfigVersion(String serviceName, String user,
                                                                  String serviceConfigVersionNote) {
    //create next service config version
    return createServiceConfigVersion(serviceName, user, serviceConfigVersionNote, null);
  }

  private List<ClusterConfigEntity> getClusterConfigEntitiesByService(String serviceName) {
    Collection<String> configTypes = serviceConfigTypes.get(serviceName);
    return clusterDAO.getLatestClusterConfigsByTypes(getClusterId(), new ArrayList<>(configTypes));
  }

  @Override
  public Config getDesiredConfigByType(String configType) {
    List<ClusterConfigMappingEntity> entities = clusterDAO.getLatestClusterConfigMappingsEntityByType(getClusterId(), configType);
    if (!entities.isEmpty()) {
      return getConfig(configType, entities.get(0).getTag());
    }

    return null;
  }

  @Override
  public boolean isConfigTypeExists(String configType) {
    for (ClusterConfigMappingEntity e : clusterDAO.getClusterConfigMappingEntitiesByCluster(
        getClusterId())) {
      if (e.getType().equals(configType)) {
        return true;
      }
    }

    return false;
  }

  @Override
  public Map<Long, Map<String, DesiredConfig>> getHostsDesiredConfigs(Collection<Long> hostIds) {

    if (hostIds == null || hostIds.isEmpty()) {
      return Collections.emptyMap();
    }

    Set<HostConfigMapping> mappingEntities =
        hostConfigMappingDAO.findSelectedByHosts(hostIds);

    Map<Long, Map<String, DesiredConfig>> desiredConfigsByHost = new HashMap<>();

    for (Long hostId : hostIds) {
      desiredConfigsByHost.put(hostId, new HashMap<String, DesiredConfig>());
    }

    for (HostConfigMapping mappingEntity : mappingEntities) {
      DesiredConfig desiredConfig = new DesiredConfig();
      desiredConfig.setTag(mappingEntity.getVersion());
      desiredConfig.setServiceName(mappingEntity.getServiceName());
      desiredConfig.setUser(mappingEntity.getUser());

      desiredConfigsByHost.get(mappingEntity.getHostId()).put(mappingEntity.getType(), desiredConfig);
    }

    return desiredConfigsByHost;
  }

  @Override
  public Map<Long, Map<String, DesiredConfig>> getAllHostsDesiredConfigs() {

    Collection<Long> hostIds;
    try {
      hostIds = clusters.getHostIdsForCluster(clusterName).keySet();
    } catch (AmbariException ignored) {
      return Collections.emptyMap();
    }

    return getHostsDesiredConfigs(hostIds);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Long getNextConfigVersion(String type) {
    return clusterDAO.findNextConfigVersion(clusterId, type);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Map<ServiceComponentHostEvent, String> processServiceComponentHostEvents(ListMultimap<String, ServiceComponentHostEvent> eventMap) {
    clusterGlobalLock.readLock().lock();

    try {
      return processServiceComponentHostEventsInSingleTransaction(eventMap);
    } finally {
      clusterGlobalLock.readLock().unlock();
    }
  }

  /**
   * Bulk handle service component host events, wrapping all handling in a
   * single transaction. This allows
   * {@link #processServiceComponentHostEvents(ListMultimap)} to lock around the
   * AoP {@link Transactional} annotation so that the lock is not released
   * before the transaction is committed.
   *
   * @param eventMap
   * @return the events which failed to be processed.
   */
  @Transactional
  protected Map<ServiceComponentHostEvent, String> processServiceComponentHostEventsInSingleTransaction(
      ListMultimap<String, ServiceComponentHostEvent> eventMap) {
    Map<ServiceComponentHostEvent, String> failedEvents = new HashMap<>();

    for (Entry<String, ServiceComponentHostEvent> entry : eventMap.entries()) {
      String serviceName = entry.getKey();
      ServiceComponentHostEvent event = entry.getValue();
      String serviceComponentName = event.getServiceComponentName();

      // server-side events either don't have a service name or are AMBARI;
      // either way they are not handled by this method since it expects a
      // real service and component
      if (StringUtils.isBlank(serviceName) || Services.AMBARI.name().equals(serviceName)) {
        continue;
      }

      if (StringUtils.isBlank(serviceComponentName)) {
        continue;
      }

      try {
        Service service = getService(serviceName);
        ServiceComponent serviceComponent = service.getServiceComponent(serviceComponentName);
        ServiceComponentHost serviceComponentHost = serviceComponent.getServiceComponentHost(
            event.getHostName());
        serviceComponentHost.handleEvent(event);
      } catch (ServiceNotFoundException e) {
        String message = String.format(
            "ServiceComponentHost lookup exception. Service not found for Service: %s. Error: %s",
            serviceName, e.getMessage());
        LOG.error(message);
        failedEvents.put(event, message);
      } catch (ServiceComponentNotFoundException e) {
        String message = String.format(
            "ServiceComponentHost lookup exception. Service Component not found for Service: %s, Component: %s. Error: %s",
            serviceName, serviceComponentName, e.getMessage());
        LOG.error(message);
        failedEvents.put(event, message);
      } catch (ServiceComponentHostNotFoundException e) {
        String message = String.format(
            "ServiceComponentHost lookup exception. Service Component Host not found for Service: %s, Component: %s, Host: %s. Error: %s",
            serviceName, serviceComponentName, event.getHostName(), e.getMessage());
        LOG.error(message);
        failedEvents.put(event, message);
      } catch (AmbariException e) {
        String message = String.format("ServiceComponentHost lookup exception %s", e.getMessage());
        LOG.error(message);
        failedEvents.put(event, message);
      } catch (InvalidStateTransitionException e) {
        LOG.error("Invalid transition ", e);

        boolean isFailure = true;

        Enum<?> currentState = e.getCurrentState();
        Enum<?> failedEvent = e.getEvent();

        // skip adding this as a failed event, to work around stack ordering
        // issues with Hive
        if (currentState == State.STARTED &&
            failedEvent == ServiceComponentHostEventType.HOST_SVCCOMP_START){
          isFailure = false;
          LOG.warn(
              "The start request for {} is invalid since the component is already started. Ignoring the request.",
              serviceComponentName);
        }

        // unknown hosts should be able to be put back in progress and let the
        // action scheduler fail it; don't abort the entire stage just because
        // this happens
        if (currentState == State.UNKNOWN
            && failedEvent == ServiceComponentHostEventType.HOST_SVCCOMP_OP_IN_PROGRESS) {
          isFailure = false;
          LOG.warn("The host {} is in an unknown state; attempting to put {} back in progress.",
              event.getHostName(),
              serviceComponentName);
        }

        // fail the event, causing it to automatically abort
        if (isFailure) {
          failedEvents.put(event, String.format("Invalid transition. %s", e.getMessage()));
        }
      }
    }

    return failedEvents;
  }

  /**
   * @param serviceName name of the service
   * @param componentName name of the component
   * @return the set of hosts for the provided service and component
   */
  @Override
  public Set<String> getHosts(String serviceName, String componentName) {
    Map<String, Service> clusterServices = getServices();

    if (!clusterServices.containsKey(serviceName)) {
      return Collections.emptySet();
    }

    Service service = clusterServices.get(serviceName);
    Map<String, ServiceComponent> components = service.getServiceComponents();

    if (!components.containsKey(componentName) ||
            components.get(componentName).getServiceComponentHosts().size() == 0) {
      return Collections.emptySet();
    }

    return components.get(componentName).getServiceComponentHosts().keySet();
  }

  @Override
  public Host getHost(final String hostName) {
    if (StringUtils.isEmpty(hostName)) {
      return null;
    }

    Collection<Host> hosts = getHosts();
    if(hosts != null) {
      for (Host host : hosts) {
        String hostString = host.getHostName();
        if(hostName.equalsIgnoreCase(hostString)) {
          return host;
        }
      }
    }
    return null;
  }

  @Override
  public Collection<Host> getHosts() {
    Map<String, Host> hosts;

    try {
      //todo: why the hell does this method throw AmbariException???
      //todo: this is ridiculous that I need to get hosts for this cluster from Clusters!!!
      //todo: should I getHosts using the same logic as the other getHosts call?  At least that doesn't throw AmbariException.
      hosts =  clusters.getHostsForCluster(clusterName);
    } catch (AmbariException e) {
      //todo: in what conditions is AmbariException thrown?
      throw new RuntimeException("Unable to get hosts for cluster: " + clusterName, e);
    }
    return hosts == null ? Collections.<Host>emptyList() : hosts.values();
  }

  private ClusterHealthReport getClusterHealthReport(
      Map<String, Host> clusterHosts) throws AmbariException {

    int staleConfigsHosts = 0;
    int maintenanceStateHosts = 0;

    int healthyStateHosts = 0;
    int unhealthyStateHosts = 0;
    int initStateHosts = 0;
    int healthyStatusHosts = 0;

    int unhealthyStatusHosts = 0;
    int unknownStatusHosts = 0;
    int alertStatusHosts = 0;
    int heartbeatLostStateHosts = 0;

    // look this up once so it can be reused in the loop for every SCH
    Map<String, DesiredConfig> desiredConfigs = getDesiredConfigs();

    Collection<Host> hosts = clusterHosts.values();
    Iterator<Host> iterator = hosts.iterator();
    while (iterator.hasNext()) {
      Host host = iterator.next();
      String hostName = host.getHostName();

      switch (host.getState()) {
        case HEALTHY:
          healthyStateHosts++;
          break;
        case UNHEALTHY:
          unhealthyStateHosts++;
          break;
        case INIT:
          initStateHosts++;
          break;
        case HEARTBEAT_LOST:
          heartbeatLostStateHosts++;
          break;
      }

      switch (HostHealthStatus.HealthStatus.valueOf(host.getStatus())) {
        case HEALTHY:
          healthyStatusHosts++;
          break;
        case UNHEALTHY:
          unhealthyStatusHosts++;
          break;
        case UNKNOWN:
          unknownStatusHosts++;
          break;
        case ALERT:
          alertStatusHosts++;
          break;
      }

      boolean staleConfig = false;
      boolean maintenanceState = false;

      if (serviceComponentHostsByHost.containsKey(hostName)) {
        for (ServiceComponentHost sch : serviceComponentHostsByHost.get(hostName)) {
          staleConfig = staleConfig || configHelper.isStaleConfigs(sch, desiredConfigs);
          maintenanceState = maintenanceState ||
            maintenanceStateHelper.getEffectiveState(sch) != MaintenanceState.OFF;
        }
      }

      if (staleConfig) {
        staleConfigsHosts++;
      }
      if (maintenanceState) {
        maintenanceStateHosts++;
      }
    }

    ClusterHealthReport chr = new ClusterHealthReport();
    chr.setAlertStatusHosts(alertStatusHosts);
    chr.setHealthyStateHosts(healthyStateHosts);
    chr.setUnknownStatusHosts(unknownStatusHosts);
    chr.setUnhealthyStatusHosts(unhealthyStatusHosts);
    chr.setUnhealthyStateHosts(unhealthyStateHosts);
    chr.setStaleConfigsHosts(staleConfigsHosts);
    chr.setMaintenanceStateHosts(maintenanceStateHosts);
    chr.setInitStateHosts(initStateHosts);
    chr.setHeartbeatLostStateHosts(heartbeatLostStateHosts);
    chr.setHealthyStatusHosts(healthyStatusHosts);

    return chr;
  }

  @Override
  public boolean checkPermission(PrivilegeEntity privilegeEntity, boolean readOnly) {
    ClusterEntity clusterEntity = getClusterEntity();
    ResourceEntity resourceEntity = clusterEntity.getResource();
    if (resourceEntity != null) {
      Integer permissionId = privilegeEntity.getPermission().getId();
      // CLUSTER.USER or CLUSTER.ADMINISTRATOR for the given cluster resource.
      if (privilegeEntity.getResource().equals(resourceEntity)) {
        if ((readOnly && permissionId.equals(PermissionEntity.CLUSTER_USER_PERMISSION))
            || permissionId.equals(PermissionEntity.CLUSTER_ADMINISTRATOR_PERMISSION)) {
          return true;
        }
      }
    }
    return false;
  }

  @Override
  public void addSessionAttributes(Map<String, Object> attributes) {
    if (attributes != null && !attributes.isEmpty()) {
      Map<String, Object>  sessionAttributes = new HashMap<>(getSessionAttributes());
      sessionAttributes.putAll(attributes);
      setSessionAttributes(attributes);
    }
  }

  @Override
  public void setSessionAttribute(String key, Object value){
    if (key != null && !key.isEmpty()) {
      Map<String, Object> sessionAttributes = new HashMap<>(getSessionAttributes());
      sessionAttributes.put(key, value);
      setSessionAttributes(sessionAttributes);
    }
  }

  @Override
  public void removeSessionAttribute(String key) {
    if (key != null && !key.isEmpty()) {
      Map<String, Object> sessionAttributes = new HashMap<>(getSessionAttributes());
      sessionAttributes.remove(key);
      setSessionAttributes(sessionAttributes);
    }
  }

  @Override
  public Map<String, Object> getSessionAttributes() {
    Map<String, Object>  attributes =
        (Map<String, Object>) getSessionManager().getAttribute(getClusterSessionAttributeName());

    return attributes == null ? Collections.<String, Object>emptyMap() : attributes;
  }

  /**
   * Get the associated session manager.
   *
   * @return the session manager
   */
  protected AmbariSessionManager getSessionManager() {
    return sessionManager;
  }

  /**
   * Set the map of session attributes for this cluster.
   * <p/>
   * This is a private method so that it may be used as a utility for add and update operations.
   *
   * @param sessionAttributes the map of session attributes for this cluster; never null
   */
  private void setSessionAttributes(Map<String, Object> sessionAttributes) {
    getSessionManager().setAttribute(getClusterSessionAttributeName(), sessionAttributes);
  }

  /**
   * Generates and returns the cluster-specific attribute name to use to set and get cluster-specific
   * session attributes.
   *
   * @return the name of the cluster-specific session attribute
   */
  private String getClusterSessionAttributeName() {
    return CLUSTER_SESSION_ATTRIBUTES_PREFIX + getClusterName();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  @Transactional
  public void applyLatestConfigurations(StackId stackId) {
    clusterGlobalLock.writeLock().lock();

    try {
      ClusterEntity clusterEntity = getClusterEntity();
      Collection<ClusterConfigMappingEntity> configMappingEntities = clusterEntity.getConfigMappingEntities();

      // hash them for easier retrieval later - these are the same entity
      // instances which exist on the cluster entity, so modification of the CCM
      // entity here will affect the cluster CCM entities as well
      ImmutableMap<Object, ClusterConfigMappingEntity> ccmMap = Maps.uniqueIndex(configMappingEntities, Functions.identity());

      // disable all configs
      for (ClusterConfigMappingEntity e : configMappingEntities) {
        LOG.debug("{} with tag {} is unselected", e.getType(), e.getTag());
        e.setSelected(0);
      }

      // work through the in-memory list, finding only the most recent mapping per type
      Collection<ClusterConfigMappingEntity> latestConfigMappingByStack = getLatestConfigMappingsForStack(
          clusterEntity.getClusterId(), stackId);

      for( ClusterConfigMappingEntity latestConfigMapping : latestConfigMappingByStack ){
        ClusterConfigMappingEntity mapping = ccmMap.get(latestConfigMapping);
        mapping.setSelected(1);

        LOG.info("Settting {} with version tag {} created on {} to selected for stack {}",
            mapping.getType(), mapping.getTag(), new Date(mapping.getCreateTimestamp()),
            stackId.toString());
      }

      // since the entities which were modified came from the cluster entity's
      // list to begin with, we can just save them right back - no need for a
      // new collection since the CCM entity instances were modified directly
      clusterEntity.setConfigMappingEntities(configMappingEntities);
      clusterEntity = clusterDAO.merge(clusterEntity);
      clusterDAO.mergeConfigMappings(configMappingEntities);

      cacheConfigurations();
    } finally {
      clusterGlobalLock.writeLock().unlock();
    }

    LOG.info(
        "Applied latest configurations for {} on stack {}. The desired configurations are now {}",
        getClusterName(), stackId, getDesiredConfigs());

    // publish an event to instruct entity managers to clear cached instances of
    // ClusterEntity immediately - it takes EclipseLink about 1000ms to update
    // the L1 caches of other threads and the action scheduler could act upon
    // stale data
    EntityManagerCacheInvalidationEvent event = new EntityManagerCacheInvalidationEvent();
    jpaEventPublisher.publish(event);
  }

  /**
   * Retrieves all of the configuration mappings (selected and unselected) for
   * the specified stack and then iterates through them, returning the most
   * recent mapping for every type/tag combination.
   * <p/>
   * Because of how configuration revert works, mappings can be created for the
   * same type/tag combinations. The only difference being that the timestamp
   * reflects when each mapping was created.
   * <p/>
   * JPQL cannot be used directly here easily because some databases cannot
   * support the necessary grouping and IN clause. For example: <br/>
   *
   * <pre>
   * SELECT mapping FROM clusterconfigmappingentity mapping
   *   WHERE (mapping.typename, mapping.createtimestamp) IN
   *     (SELECT latest.typename, MAX(latest.createtimestamp)
   *      FROM clusterconfigmappingentity latest
   *      GROUP BY latest.typename)
   * </pre>
   *
   * @param clusterId
   *          the cluster ID
   * @param stackId
   *          the stack to retrieve the mappings for (not {@code null}).
   * @return the most recent mapping (selected or unselected) for the specified
   *         stack for every type.
   */
  public Collection<ClusterConfigMappingEntity> getLatestConfigMappingsForStack(long clusterId,
      StackId stackId) {

    // get all mappings for the specified stack (which could include
    // duplicates since a config revert creates a duplicate mapping with a
    // different timestamp)
    List<ClusterConfigMappingEntity> clusterConfigMappingsForStack = clusterDAO.getClusterConfigMappingsByStack(
        clusterId, stackId);

    Map<String, ClusterConfigMappingEntity> latestMappingsByType = new HashMap<>();
    for (ClusterConfigMappingEntity mapping : clusterConfigMappingsForStack) {
      String type = mapping.getType();

      if (!latestMappingsByType.containsKey(type)) {
        latestMappingsByType.put(type, mapping);
        continue;
      }

      ClusterConfigMappingEntity entityStored = latestMappingsByType.get(type);
      Long timestampStored = entityStored.getCreateTimestamp();
      Long timestamp = mapping.getCreateTimestamp();
      if (timestamp > timestampStored) {
        latestMappingsByType.put(type, mapping);
      }
    }

    return latestMappingsByType.values();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Map<PropertyInfo.PropertyType, Set<String>> getConfigPropertiesTypes(String configType){
    try {
      StackId stackId = getCurrentStackVersion();
      StackInfo stackInfo = ambariMetaInfo.getStack(stackId.getStackName(), stackId.getStackVersion());
      return stackInfo.getConfigPropertiesTypes(configType);
    } catch (AmbariException e) {

    }
    return new HashMap<>();
  }

  /**
   * Removes all configurations associated with the specified stack. The caller
   * should make sure the cluster global write lock is acquired.
   *
   * @param stackId
   * @see Cluster#getClusterGlobalLock()
   */
  @Transactional
  void removeAllConfigsForStack(StackId stackId) {
    ClusterEntity clusterEntity = getClusterEntity();

    // make sure the entity isn't stale in the current unit of work.
    clusterDAO.refresh(clusterEntity);

    long clusterId = clusterEntity.getClusterId();

    // this will keep track of cluster config mappings that need removal
    // since there is no relationship between configs and their mappings, we
    // have to do it manually
    List<ClusterConfigEntity> removedClusterConfigs = new ArrayList<>(50);
    Collection<ClusterConfigEntity> clusterConfigEntities = clusterEntity.getClusterConfigEntities();

    List<ServiceConfigEntity> serviceConfigs = serviceConfigDAO.getAllServiceConfigsForClusterAndStack(
      clusterId, stackId);

    // remove all service configurations and associated configs
    Collection<ServiceConfigEntity> serviceConfigEntities = clusterEntity.getServiceConfigEntities();

    for (ServiceConfigEntity serviceConfig : serviceConfigs) {
      for (ClusterConfigEntity configEntity : serviceConfig.getClusterConfigEntities()) {
        clusterConfigEntities.remove(configEntity);
        clusterDAO.removeConfig(configEntity);
        removedClusterConfigs.add(configEntity);
      }

      serviceConfig.getClusterConfigEntities().clear();
      serviceConfigDAO.remove(serviceConfig);
      serviceConfigEntities.remove(serviceConfig);
    }

    // remove any leftover cluster configurations that don't have a service
    // configuration (like cluster-env)
    List<ClusterConfigEntity> clusterConfigs = clusterDAO.getAllConfigurations(
      clusterId, stackId);

    for (ClusterConfigEntity clusterConfig : clusterConfigs) {
      clusterConfigEntities.remove(clusterConfig);
      clusterDAO.removeConfig(clusterConfig);
      removedClusterConfigs.add(clusterConfig);
    }

    clusterEntity.setClusterConfigEntities(clusterConfigEntities);
    clusterEntity = clusterDAO.merge(clusterEntity);

    // remove config mappings
    Collection<ClusterConfigMappingEntity> configMappingEntities =
        clusterDAO.getClusterConfigMappingEntitiesByCluster(getClusterId());

    for (ClusterConfigEntity removedClusterConfig : removedClusterConfigs) {
      String removedClusterConfigType = removedClusterConfig.getType();
      String removedClusterConfigTag = removedClusterConfig.getTag();

      Iterator<ClusterConfigMappingEntity> clusterConfigMappingIterator = configMappingEntities.iterator();
      while (clusterConfigMappingIterator.hasNext()) {
        ClusterConfigMappingEntity clusterConfigMapping = clusterConfigMappingIterator.next();
        String mappingType = clusterConfigMapping.getType();
        String mappingTag = clusterConfigMapping.getTag();

        if (removedClusterConfigTag.equals(mappingTag)
          && removedClusterConfigType.equals(mappingType)) {
          clusterConfigMappingIterator.remove();
          clusterDAO.removeConfigMapping(clusterConfigMapping);
        }
      }
    }

    clusterEntity.setConfigMappingEntities(configMappingEntities);
    clusterEntity = clusterDAO.merge(clusterEntity);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void removeConfigurations(StackId stackId) {
    clusterGlobalLock.writeLock().lock();
    try {
      removeAllConfigsForStack(stackId);
      cacheConfigurations();
    } finally {
      clusterGlobalLock.writeLock().unlock();
    }
  }

  /**
   * Caches all of the {@link ClusterConfigEntity}s in {@link #allConfigs}.
   */
  private void cacheConfigurations() {
    clusterGlobalLock.writeLock().lock();
    try {
      ClusterEntity clusterEntity = getClusterEntity();
      allConfigs.clear();

      if (!clusterEntity.getClusterConfigEntities().isEmpty()) {
        for (ClusterConfigEntity entity : clusterEntity.getClusterConfigEntities()) {

          if (!allConfigs.containsKey(entity.getType())) {
            allConfigs.put(entity.getType(), new ConcurrentHashMap<String, Config>());
          }

          Config config = configFactory.createExisting(this, entity);

          allConfigs.get(entity.getType()).put(entity.getTag(), config);
        }
      }
    } finally {
      clusterGlobalLock.writeLock().unlock();
    }
  }

  private void loadStackVersion() {
    desiredStackVersion = new StackId(getClusterEntity().getDesiredStack());

    if (!StringUtils.isEmpty(desiredStackVersion.getStackName())
        && !StringUtils.isEmpty(desiredStackVersion.getStackVersion())) {
      try {
        loadServiceConfigTypes();
      } catch (AmbariException e) {
        // TODO recheck wrapping exception here, required for lazy loading after
        // invalidation
        throw new RuntimeException(e);
      }
    }
  }

  /**
   * Returns whether this cluster was provisioned by a Blueprint or not.
   * @return true if the cluster was deployed with a Blueprint otherwise false.
   */
  @Override
  public boolean isBluePrintDeployed() {

    List<TopologyRequestEntity> topologyRequests = topologyRequestDAO.findByClusterId(getClusterId());

    // Iterate through the topology requests associated with this cluster and look for PROVISION request
    for (TopologyRequestEntity topologyRequest: topologyRequests) {
      TopologyRequest.Type requestAction = TopologyRequest.Type.valueOf(topologyRequest.getAction());
      if (requestAction == TopologyRequest.Type.PROVISION) {
        return true;
      }
    }

    return false;
  }

  /**
   * Gets the {@link ClusterEntity} for this {@link Cluster} from the
   * {@link EntityManager} cache.
   *
   * @return
   */
  private ClusterEntity getClusterEntity() {
    return clusterDAO.findById(clusterId);
  }

  /**
  * Returns the number of hosts that form the cluster.
  *
  *  @return number of hosts that form the cluster
  */
  @Override
  public int getClusterSize() {
    return clusters.getClusterSize(clusterName);
  }


  /**
   * {@inheritDoc}
   */
  @Override
  public UpgradeEntity getUpgradeInProgress() {
    ClusterEntity clusterEntity = getClusterEntity();
    return clusterEntity.getUpgradeEntity();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  @Transactional
  public void setUpgradeEntity(UpgradeEntity upgradeEntity) throws AmbariException {
    try {
      ClusterEntity clusterEntity = getClusterEntity();
      clusterEntity.setUpgradeEntity(upgradeEntity);
      clusterDAO.merge(clusterEntity);
    } catch (RollbackException e) {
      throw new AmbariException("Unable to update the associated upgrade with the cluster", e);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean isUpgradeSuspended() {
    UpgradeEntity upgrade = getUpgradeInProgress();
    if (null != upgrade) {
      return upgrade.isSuspended();
    }

    return false;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String getClusterProperty(String propertyName, String defaultValue) {
    String cachedValue = m_clusterPropertyCache.get(propertyName);
    if (null != cachedValue) {
      return cachedValue;
    }

    // start with the default
    cachedValue = defaultValue;

    Config clusterEnv = getDesiredConfigByType(ConfigHelper.CLUSTER_ENV);
    if (null != clusterEnv) {
      Map<String, String> clusterEnvProperties = clusterEnv.getProperties();
      if (clusterEnvProperties.containsKey(propertyName)) {
        String value = clusterEnvProperties.get(propertyName);
        if (null != value) {
          cachedValue = value;
        }
      }
    }

    // cache the value and return it
    m_clusterPropertyCache.put(propertyName, cachedValue);
    return cachedValue;
  }

  /**
   * Gets whether the specified cluster property is already cached.
   *
   * @param propertyName
   *          the property to check.
   * @return {@code true} if the property is cached.
   */
  boolean isClusterPropertyCached(String propertyName) {
    return m_clusterPropertyCache.containsKey(propertyName);
  }

  /**
   * Handles {@link ClusterConfigChangedEvent} which means that the
   * {{cluster-env}} may have changed.
   *
   * @param event
   *          the change event.
   */
  @Subscribe
  public void handleClusterEnvConfigChangedEvent(ClusterConfigChangedEvent event) {
    if (!StringUtils.equals(event.getConfigType(), ConfigHelper.CLUSTER_ENV)) {
      return;
    }

    m_clusterPropertyCache.clear();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public RoleCommandOrder getRoleCommandOrder() {
    return roleCommandOrderProvider.getRoleCommandOrder(this);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void addSuspendedUpgradeParameters(Map<String, String> commandParams,
      Map<String, String> roleParams) {

    // build some command params from the upgrade, including direction,
    // type, version, etc
    UpgradeEntity suspendedUpgrade = getUpgradeInProgress();
    if( null == suspendedUpgrade ){
      LOG.warn(
          "An upgrade is not currently suspended. The command and role parameters will not be modified.");

      return;
    }

    UpgradeContext upgradeContext = upgradeContextFactory.create(this, suspendedUpgrade);
    commandParams.putAll(upgradeContext.getInitializedCommandParameters());

    // suspended goes in role params
    roleParams.put(KeyNames.UPGRADE_SUSPENDED, Boolean.TRUE.toString().toLowerCase());
  }
}
