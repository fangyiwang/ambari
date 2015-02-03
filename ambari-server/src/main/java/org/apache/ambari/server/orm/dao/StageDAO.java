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

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.persistence.EntityManager;
import javax.persistence.TypedQuery;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Order;
import javax.persistence.metamodel.SingularAttribute;

import org.apache.ambari.server.actionmanager.HostRoleStatus;
import org.apache.ambari.server.api.query.JpaPredicateVisitor;
import org.apache.ambari.server.api.query.JpaSortBuilder;
import org.apache.ambari.server.controller.spi.Predicate;
import org.apache.ambari.server.controller.spi.Request;
import org.apache.ambari.server.controller.utilities.PredicateHelper;
import org.apache.ambari.server.orm.RequiresSession;
import org.apache.ambari.server.orm.entities.StageEntity;
import org.apache.ambari.server.orm.entities.StageEntityPK;
import org.apache.ambari.server.orm.entities.StageEntity_;
import org.apache.ambari.server.utils.StageUtils;
import org.eclipse.persistence.config.HintValues;
import org.eclipse.persistence.config.QueryHints;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import com.google.inject.persist.Transactional;

@Singleton
public class StageDAO {

  @Inject
  Provider<EntityManager> entityManagerProvider;
  @Inject
  DaoUtils daoUtils;

  @RequiresSession
  public StageEntity findByPK(StageEntityPK stageEntityPK) {
    return entityManagerProvider.get().find(StageEntity.class, stageEntityPK);
  }

  @RequiresSession
  public List<StageEntity> findAll() {
    return daoUtils.selectAll(entityManagerProvider.get(), StageEntity.class);
  }

  @RequiresSession
  public long getLastRequestId() {
    TypedQuery<Long> query = entityManagerProvider.get().createQuery("SELECT max(stage.requestId) FROM StageEntity stage", Long.class);
    Long result = daoUtils.selectSingle(query);
    if (result != null) {
      return result;
    } else {
      return 0;
    }
  }

  @RequiresSession
  public StageEntity findByActionId(String actionId) {
    long[] ids = StageUtils.getRequestStage(actionId);
    StageEntityPK pk = new StageEntityPK();
    pk.setRequestId(ids[0]);
    pk.setStageId(ids[1]);
    return findByPK(pk);
  }

  @RequiresSession
  public List<StageEntity> findByRequestId(long requestId) {
    TypedQuery<StageEntity> query = entityManagerProvider.get().createQuery("SELECT stage " +
        "FROM StageEntity stage " +
        "WHERE stage.requestId=?1 " +
        "ORDER BY stage.stageId", StageEntity.class);
    return daoUtils.selectList(query, requestId);
  }

  /**
   * Get the list of stage entities for the given request id and stage ids.
   *
   * @param requestId  the request ids
   * @param stageIds   the set of stage ids
   *
   * @return the set of entities for the given ids
   */
  public List<StageEntity> findByStageIds(Long requestId, Set<Long> stageIds) {
    List<StageEntity> stageEntities = new LinkedList<StageEntity>();

    for (StageEntity stage : findByRequestId(requestId)) {
      if (stageIds.contains(stage.getStageId())) {
        stageEntities.add(stage);
      }
    }
    return stageEntities;
  }

  @RequiresSession
  public List<StageEntity> findByCommandStatuses(
      Collection<HostRoleStatus> statuses) {
    TypedQuery<StageEntity> query = entityManagerProvider.get().createNamedQuery(
        "StageEntity.findByCommandStatuses", StageEntity.class);

    query.setParameter("statuses", statuses);
    return daoUtils.selectList(query);
  }

  @RequiresSession
  public Map<Long, String> findRequestContext(List<Long> requestIds) {
    Map<Long, String> resultMap = new HashMap<Long, String>();
    if (requestIds != null && !requestIds.isEmpty()) {
      TypedQuery<StageEntity> query = entityManagerProvider.get()
        .createQuery("SELECT stage FROM StageEntity stage WHERE " +
          "stage.requestId IN (SELECT DISTINCT s.requestId FROM StageEntity s " +
          "WHERE s.requestId IN ?1)", StageEntity.class);
      List<StageEntity> result = daoUtils.selectList(query, requestIds);
      if (result != null && !result.isEmpty()) {
        for (StageEntity entity : result) {
          resultMap.put(entity.getRequestId(), entity.getRequestContext());
        }
      }
    }
    return resultMap;
  }

  @RequiresSession
  public String findRequestContext(long requestId) {
    TypedQuery<String> query = entityManagerProvider.get().createQuery(
      "SELECT stage.requestContext " + "FROM StageEntity stage " +
        "WHERE stage.requestId=?1", String.class);
    String result =  daoUtils.selectOne(query, requestId);
    if (result != null) {
      return result;
    }
    else {
      return ""; // Since it is defined as empty string in the StageEntity
    }
  }

  @Transactional
  public void create(StageEntity stageEntity) {
    entityManagerProvider.get().persist(stageEntity);
  }

  @Transactional
  public StageEntity merge(StageEntity stageEntity) {
    return entityManagerProvider.get().merge(stageEntity);
  }

  @Transactional
  public void remove(StageEntity stageEntity) {
    entityManagerProvider.get().remove(merge(stageEntity));
  }

  @Transactional
  public void removeByPK(StageEntityPK stageEntityPK) {
    remove(findByPK(stageEntityPK));
  }

  /**
   * Finds all {@link org.apache.ambari.server.orm.entities.StageEntity} that match the provided
   * {@link org.apache.ambari.server.controller.spi.Predicate}. This method will make JPA do the heavy lifting
   * of providing a slice of the result set.
   *
   * @param request
   * @return
   */
  @Transactional
  public List<StageEntity> findAll(Request request, Predicate predicate) {
    EntityManager entityManager = entityManagerProvider.get();

    // convert the Ambari predicate into a JPA predicate
    StagePredicateVisitor visitor = new StagePredicateVisitor();
    PredicateHelper.visit(predicate, visitor);

    CriteriaQuery<StageEntity> query = visitor.getCriteriaQuery();
    javax.persistence.criteria.Predicate jpaPredicate = visitor.getJpaPredicate();

    if (jpaPredicate != null) {
      query.where(jpaPredicate);
    }

    // sorting
    JpaSortBuilder<StageEntity> sortBuilder = new JpaSortBuilder<StageEntity>();
    List<Order> sortOrders = sortBuilder.buildSortOrders(request.getSortRequest(), visitor);
    query.orderBy(sortOrders);

    TypedQuery<StageEntity> typedQuery = entityManager.createQuery(query);

    // !!! https://bugs.eclipse.org/bugs/show_bug.cgi?id=398067
    // ensure that an associated entity with a JOIN is not stale; this causes
    // the associated StageEntity to be stale
    typedQuery.setHint(QueryHints.REFRESH, HintValues.TRUE);

    return daoUtils.selectList(typedQuery);
  }

  /**
   * The {@link org.apache.ambari.server.orm.dao.StageDAO.StagePredicateVisitor} is used to convert an Ambari
   * {@link org.apache.ambari.server.controller.spi.Predicate} into a JPA {@link javax.persistence.criteria.Predicate}.
   */
  private final class StagePredicateVisitor extends
      JpaPredicateVisitor<StageEntity> {

    /**
     * Constructor.
     *
     */
    public StagePredicateVisitor() {
      super(entityManagerProvider.get(), StageEntity.class);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Class<StageEntity> getEntityClass() {
      return StageEntity.class;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<? extends SingularAttribute<?, ?>> getPredicateMapping(
        String propertyId) {
      return StageEntity_.getPredicateMapping().get(propertyId);
    }
  }
}
