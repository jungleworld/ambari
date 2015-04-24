/*
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

package org.apache.ambari.server.orm.dao;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import javax.persistence.EntityManager;
import javax.persistence.Tuple;
import javax.persistence.TypedQuery;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Root;

import org.apache.ambari.server.orm.RequiresSession;
import org.apache.ambari.server.orm.entities.ServiceConfigEntity;
import org.apache.ambari.server.orm.entities.StackEntity;
import org.apache.ambari.server.state.StackId;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import com.google.inject.persist.Transactional;

@Singleton
public class ServiceConfigDAO {
  @Inject
  private Provider<EntityManager> entityManagerProvider;

  @Inject
  private StackDAO stackDAO;

  @Inject
  private DaoUtils daoUtils;

  @RequiresSession
  public ServiceConfigEntity find(Long serviceConfigId) {
    return entityManagerProvider.get().find(ServiceConfigEntity.class, serviceConfigId);
  }

  @RequiresSession
  public ServiceConfigEntity findByServiceAndVersion(String serviceName, Long version) {
    TypedQuery<ServiceConfigEntity> query = entityManagerProvider.get().
        createQuery("SELECT scv FROM ServiceConfigEntity scv " +
            "WHERE scv.serviceName=?1 AND scv.version=?2", ServiceConfigEntity.class);
    return daoUtils.selectOne(query, serviceName, version);
  }

  @RequiresSession
  public List<ServiceConfigEntity> getLastServiceConfigVersionsForGroups(Collection<Long> configGroupIds) {
    if (configGroupIds == null || configGroupIds.isEmpty()) {
      return Collections.emptyList();
    }
    CriteriaBuilder cb = entityManagerProvider.get().getCriteriaBuilder();
    CriteriaQuery<Tuple> cq = cb.createTupleQuery();
    Root<ServiceConfigEntity> groupVersion = cq.from(ServiceConfigEntity.class);


    cq.multiselect(groupVersion.get("groupId").alias("groupId"), cb.max(groupVersion.<Long>get("version")).alias("lastVersion"));
    cq.where(groupVersion.get("groupId").in(configGroupIds));
    cq.groupBy(groupVersion.get("groupId"));
    List<Tuple> tuples = daoUtils.selectList(entityManagerProvider.get().createQuery(cq));
    List<ServiceConfigEntity> result = new ArrayList<ServiceConfigEntity>();
    //subquery look to be very poor, no bulk select then, cache should help here as result size is naturally limited
    for (Tuple tuple : tuples) {
      CriteriaQuery<ServiceConfigEntity> sce = cb.createQuery(ServiceConfigEntity.class);
      Root<ServiceConfigEntity> sceRoot = sce.from(ServiceConfigEntity.class);

      sce.where(cb.and(cb.equal(sceRoot.get("groupId"), tuple.get("groupId")),
        cb.equal(sceRoot.get("version"), tuple.get("lastVersion"))));
      sce.select(sceRoot);
      result.add(daoUtils.selectSingle(entityManagerProvider.get().createQuery(sce)));
    }

    return result;
  }



  @RequiresSession
  public List<Long> getServiceConfigVersionsByConfig(Long clusterId, String configType, Long configVersion) {
    TypedQuery<Long> query = entityManagerProvider.get().createQuery("SELECT scv.version " +
        "FROM ServiceConfigEntity scv JOIN scv.clusterConfigEntities cc " +
        "WHERE cc.clusterId=?1 AND cc.type = ?2 AND cc.version = ?3", Long.class);
    return daoUtils.selectList(query, clusterId, configType, configVersion);
  }

  @RequiresSession
  public List<ServiceConfigEntity> getLastServiceConfigs(Long clusterId) {
    TypedQuery<ServiceConfigEntity> query = entityManagerProvider.get().
      createQuery("SELECT scv FROM ServiceConfigEntity scv " +
        "WHERE scv.clusterId = ?1 AND scv.createTimestamp = (" +
        "SELECT MAX(scv2.createTimestamp) FROM ServiceConfigEntity scv2 " +
        "WHERE scv2.serviceName = scv.serviceName AND scv2.clusterId = ?1 AND scv2.groupId IS NULL)",
        ServiceConfigEntity.class);

    return daoUtils.selectList(query, clusterId);
  }

  /**
   * Get all service configurations for the specified cluster and stack. This
   * will return different versions of the same configuration (HDFS v1 and v2)
   * if they exist.
   *
   * @param clusterId
   *          the cluster (not {@code null}).
   * @param stackId
   *          the stack (not {@code null}).
   * @return all service configurations for the cluster and stack.
   */
  @RequiresSession
  public List<ServiceConfigEntity> getAllServiceConfigs(Long clusterId,
      StackId stackId) {

    StackEntity stackEntity = stackDAO.find(stackId.getStackName(),
        stackId.getStackVersion());

    TypedQuery<ServiceConfigEntity> query = entityManagerProvider.get().createNamedQuery(
        "ServiceConfigEntity.findAllServiceConfigsByStack",
        ServiceConfigEntity.class);

    query.setParameter("clusterId", clusterId);
    query.setParameter("stack", stackEntity);

    return daoUtils.selectList(query);
  }

  /**
   * Gets the latest service configurations for the specified cluster and stack.
   *
   * @param clusterId
   *          the cluster (not {@code null}).
   * @param stackId
   *          the stack (not {@code null}).
   * @return the latest service configurations for the cluster and stack.
   */
  @RequiresSession
  public List<ServiceConfigEntity> getLatestServiceConfigs(Long clusterId,
      StackId stackId) {

    StackEntity stackEntity = stackDAO.find(stackId.getStackName(),
        stackId.getStackVersion());

    TypedQuery<ServiceConfigEntity> query = entityManagerProvider.get().createNamedQuery(
        "ServiceConfigEntity.findLatestServiceConfigsByStack",
        ServiceConfigEntity.class);

    query.setParameter("clusterId", clusterId);
    query.setParameter("stack", stackEntity);

    return daoUtils.selectList(query);
  }

  @RequiresSession
  public ServiceConfigEntity getLastServiceConfig(Long clusterId, String serviceName) {
    TypedQuery<ServiceConfigEntity> query = entityManagerProvider.get().
        createQuery("SELECT scv FROM ServiceConfigEntity scv " +
          "WHERE scv.clusterId = ?1 AND scv.serviceName = ?2 " +
          "ORDER BY scv.createTimestamp DESC",
          ServiceConfigEntity.class);

    return daoUtils.selectOne(query, clusterId, serviceName);
  }

  @RequiresSession
  public ServiceConfigEntity findMaxVersion(Long clusterId, String serviceName) {
    TypedQuery<ServiceConfigEntity> query = entityManagerProvider.get().createQuery("SELECT scv FROM ServiceConfigEntity scv " +
      "WHERE scv.clusterId=?1 AND scv.serviceName=?2 AND scv.version = (" +
      "SELECT max(scv2.version) FROM ServiceConfigEntity scv2 " +
      "WHERE scv2.clusterId=?1 AND scv2.serviceName=?2)", ServiceConfigEntity.class);

    return daoUtils.selectSingle(query, clusterId, serviceName);
  }

  @RequiresSession
  public List<ServiceConfigEntity> getServiceConfigs(Long clusterId) {
    TypedQuery<ServiceConfigEntity> query = entityManagerProvider.get()
      .createQuery("SELECT scv FROM ServiceConfigEntity scv " +
        "WHERE scv.clusterId=?1 " +
        "ORDER BY scv.createTimestamp DESC", ServiceConfigEntity.class);

    return daoUtils.selectList(query, clusterId);
  }

  /**
   * Gets the next version that will be created when persisting a new
   * {@link ServiceConfigEntity}.
   *
   * @param clusterId
   *          the cluster that the service is a part of.
   * @param serviceName
   *          the name of the service (not {@code null}).
   * @return the maximum version value + 1
   */
  @RequiresSession
  public Long findNextServiceConfigVersion(long clusterId, String serviceName) {
    TypedQuery<Long> query = entityManagerProvider.get().createNamedQuery(
        "ServiceConfigEntity.findNextServiceConfigVersion", Long.class);

    query.setParameter("clusterId", clusterId);
    query.setParameter("serviceName", serviceName);

    return daoUtils.selectSingle(query);
  }

  @Transactional
  public void create(ServiceConfigEntity serviceConfigEntity) {
    entityManagerProvider.get().persist(serviceConfigEntity);
  }

  @Transactional
  public ServiceConfigEntity merge(ServiceConfigEntity serviceConfigEntity) {
    return entityManagerProvider.get().merge(serviceConfigEntity);
  }

  @Transactional
  public void remove(ServiceConfigEntity serviceConfigEntity) {
    entityManagerProvider.get().remove(merge(serviceConfigEntity));
  }
}
