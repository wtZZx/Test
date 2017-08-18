package eh.base.dao;

import ctd.persistence.annotation.DAOMethod;
import ctd.persistence.annotation.DAOParam;
import ctd.persistence.bean.QueryResult;
import ctd.persistence.exception.DAOException;
import ctd.persistence.support.hibernate.HibernateSupportDelegateDAO;
import ctd.persistence.support.hibernate.template.AbstractHibernateStatelessResultAction;
import ctd.persistence.support.hibernate.template.HibernateSessionTemplate;
import ctd.persistence.support.hibernate.template.HibernateStatelessResultAction;
import ctd.persistence.support.impl.dictionary.DBDictionaryItemLoader;
import ctd.util.annotation.RpcService;
import eh.entity.base.DrugList;
import eh.entity.base.SaleDrugList;
import eh.entity.bus.DrugListAndSaleDrugList;
import org.apache.commons.lang3.StringUtils;
import org.hibernate.Query;
import org.hibernate.StatelessSession;
import org.springframework.util.ObjectUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public abstract class SaleDrugListDAO extends HibernateSupportDelegateDAO<SaleDrugList>
        implements DBDictionaryItemLoader<SaleDrugList> {
    public SaleDrugListDAO() {
        super();
        this.setEntityName(SaleDrugList.class.getName());
        this.setKeyField("organDrugId");
    }


    @DAOMethod(sql = "select count(id) from SaleDrugList where status=1 and organId=:organId and drugId in :drugId")
    public abstract Long getCountByOrganIdAndDrugIds(@DAOParam("organId") int organId, @DAOParam("drugId") List<Integer> drugId);

    /**
     * 设置某些药品为无效
     *
     * @param organId
     * @param drugId
     */
    @DAOMethod(sql = "update SaleDrugList set status=0 where organId=:organId and drugId in :drugId")
    public abstract void updateInvalidByOrganIdAndDrugIds(@DAOParam("organId") int organId, @DAOParam("drugId") List<Integer> drugId);

    /**
     * 设置某些药品为有效
     *
     * @param organId
     * @param drugId
     */
    @DAOMethod(sql = "update SaleDrugList set status=1 where organId=:organId and drugId in :drugId")
    public abstract void updateEffectiveByOrganIdAndDrugIds(@DAOParam("organId") int organId, @DAOParam("drugId") List<Integer> drugId);

    /**
     * 需要同步的药品id，不区分status
     * @return
     */
    @DAOMethod(sql = "select drugId from SaleDrugList where organId=:organId group by drugId",limit = 0)
    public abstract List<Integer> findSynchroDrug(@DAOParam("organId") int organId);

    @RpcService
    @DAOMethod(sql = "from SaleDrugList where drugId=:drugId and organId=:organId ")
    public abstract SaleDrugList getByDrugIdAndOrganId(@DAOParam("drugId") int drugId, @DAOParam("organId") int organId);

    @DAOMethod(sql = "select drugId from SaleDrugList where organId=:organId and status=1", limit = 0)
    public abstract List<Integer> findDrugIdByOrganId(@DAOParam("organId") int organId);

    /**
     * 机构药品查询
     *
     * @param organId   机构
     * @param drugClass 药品分类
     * @param keyword   查询关键字:药品序号 or 药品名称 or 生产厂家 or 商品名称 or 批准文号
     * @param start
     * @param limit
     * @return
     * @author houxr
     */
    public QueryResult<DrugListAndSaleDrugList> querySaleDrugListByOrganIdAndKeyword(final Integer organId,
                                                                                     final String drugClass,
                                                                                     final String keyword, final Integer status,
                                                                                     final int start, final int limit) {
        HibernateStatelessResultAction<QueryResult<DrugListAndSaleDrugList>> action =
                new AbstractHibernateStatelessResultAction<QueryResult<DrugListAndSaleDrugList>>() {
                    @SuppressWarnings("unchecked")
                    public void execute(StatelessSession ss) throws DAOException {
                        StringBuilder hql = new StringBuilder(" from DrugList d where 1=1 ");
                        if (!StringUtils.isEmpty(drugClass)) {
                            hql.append(" and d.drugClass like :drugClass");
                        }
                        Integer drugId = null;
                        if (!StringUtils.isEmpty(keyword)) {
                            try {
                                drugId = Integer.valueOf(keyword);
                            } catch (Throwable throwable) {
                                drugId = null;
                            }
                            hql.append(" and (");
                            hql.append(" d.drugName like :keyword or d.producer like :keyword or d.saleName like :keyword or d.approvalNumber like :keyword ");
                            if (drugId != null) {
                                hql.append(" or d.drugId =:drugId");
                            }
                            hql.append(")");
                        }
                        if (ObjectUtils.nullSafeEquals(status, 0)) {
                            hql.append(" and d.drugId in (select o.drugId from SaleDrugList o where o.status = 0 and o.organId =:organId)");
                        } else if (ObjectUtils.nullSafeEquals(status, 1)) {
                            hql.append(" and d.drugId in (select o.drugId from SaleDrugList o where o.status = 1 and o.organId =:organId)");
                        } else if (ObjectUtils.nullSafeEquals(status, -1)) {
                            hql.append(" and d.drugId not in (select o.drugId from SaleDrugList o where o.organId =:organId) ");
                        } else if (ObjectUtils.nullSafeEquals(status, 9)) {
                            hql.append(" and d.drugId in (select o.drugId from SaleDrugList o where o.status in (0, 1) and o.organId =:organId)");
                        }
                        hql.append(" and d.status=1 order by d.drugId desc");
                        Query countQuery = ss.createQuery("select count(*) " + hql.toString());
                        if (!StringUtils.isEmpty(drugClass)) {
                            countQuery.setParameter("drugClass", drugClass + "%");
                        }
                        if (ObjectUtils.nullSafeEquals(status, 0)
                                || ObjectUtils.nullSafeEquals(status, 1)
                                || ObjectUtils.nullSafeEquals(status, -1)
                                || ObjectUtils.nullSafeEquals(status, 9)) {
                            countQuery.setParameter("organId", organId);
                        }
                        if (drugId != null) {
                            countQuery.setParameter("drugId", drugId);
                        }
                        if (!StringUtils.isEmpty(keyword)) {
                            countQuery.setParameter("keyword", "%" + keyword + "%");
                        }
                        Long total = (Long) countQuery.uniqueResult();

                        Query query = ss.createQuery("select d " + hql.toString());
                        if (!StringUtils.isEmpty(drugClass)) {
                            query.setParameter("drugClass", drugClass + "%");
                        }
                        if (ObjectUtils.nullSafeEquals(status, 0)
                                || ObjectUtils.nullSafeEquals(status, 1)
                                || ObjectUtils.nullSafeEquals(status, -1)
                                || ObjectUtils.nullSafeEquals(status, 9)) {
                            query.setParameter("organId", organId);
                        }
                        if (drugId != null) {
                            query.setParameter("drugId", drugId);
                        }
                        if (!StringUtils.isEmpty(keyword)) {
                            query.setParameter("keyword", "%" + keyword + "%");
                        }
                        query.setFirstResult(start);
                        query.setMaxResults(limit);
                        List<DrugList> list = query.list();
                        List<DrugListAndSaleDrugList> result = new ArrayList<DrugListAndSaleDrugList>();
                        for (DrugList drug : list) {
                            SaleDrugList saleDrugList = getByDrugIdAndOrganId(drug.getDrugId(), organId);
                            result.add(new DrugListAndSaleDrugList(drug, saleDrugList));
                        }
                        setResult(new QueryResult<DrugListAndSaleDrugList>(total, query.getFirstResult(), query.getMaxResults(), result));
                    }
                };
        HibernateSessionTemplate.instance().executeReadOnly(action);
        return action.getResult();
    }

    /**
     * 更新药品库存
     * @param drugId
     * @param depId
     * @param Inventory
     * @return
     */
    public boolean updateDrugInventory(final Integer drugId, final Integer depId, final BigDecimal inventory){
        HibernateStatelessResultAction<Boolean> action =
                new AbstractHibernateStatelessResultAction<Boolean>() {
                    public void execute(StatelessSession ss) throws DAOException {
                        StringBuilder hql = new StringBuilder(" update SaleDrugList set lastModify=current_timestamp()," +
                                "status=:status, inventory=:inventory where organId=:depId and drugId=:drugId ");
                        Query q = ss.createQuery(hql.toString());
                        q.setParameter("inventory", inventory);
                        int status = 0;
                        if(inventory.compareTo(BigDecimal.ZERO) > 0){
                            status = 1;
                        }
                        q.setParameter("status", status);
                        q.setParameter("drugId", drugId);
                        q.setParameter("depId", depId);

                        q.executeUpdate();
                        setResult(true);
                    }
                };

        HibernateSessionTemplate.instance().execute(action);
        return action.getResult();
    }
}
