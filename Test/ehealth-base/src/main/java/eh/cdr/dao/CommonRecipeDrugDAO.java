package eh.cdr.dao;

import ctd.persistence.annotation.DAOMethod;
import ctd.persistence.annotation.DAOParam;
import ctd.persistence.exception.DAOException;
import ctd.persistence.support.hibernate.HibernateSupportDelegateDAO;
import ctd.persistence.support.hibernate.template.AbstractHibernateStatelessResultAction;
import ctd.persistence.support.hibernate.template.HibernateSessionTemplate;
import ctd.persistence.support.hibernate.template.HibernateStatelessResultAction;
import eh.entity.cdr.CommonRecipeDrug;
import org.hibernate.StatelessSession;
import java.util.Date;
import java.util.List;

/**
 * Created by jiangtingfeng on 2017/5/23.
 */
public abstract class CommonRecipeDrugDAO extends HibernateSupportDelegateDAO<CommonRecipeDrug> {

    public CommonRecipeDrugDAO() {
        super();
        this.setEntityName(CommonRecipeDrug.class.getName());
        this.setKeyField("Id");
    }

    @DAOMethod(sql = "from CommonRecipeDrug where commonRecipeId=:commonRecipeId")
    public abstract List<CommonRecipeDrug> findByCommonRecipeId(@DAOParam("commonRecipeId") Integer commonRecipeId);

    @DAOMethod(sql = "delete from CommonRecipeDrug where commonRecipeId=:commonRecipeId")
    public abstract void deleteByCommonRecipeId(@DAOParam("commonRecipeId") Integer commonRecipeId);

    /**
     * 插入常用方药品列表，使用事务方式
     * @param drugList
     * @param commonRecipeId
     * @param now
     * @throws DAOException
     */
    public void addCommonRecipeDrugList(final List<CommonRecipeDrug> drugList ,final Integer commonRecipeId ,final Date now)
            throws DAOException
    {
        HibernateStatelessResultAction action = new AbstractHibernateStatelessResultAction() {
            @Override
            public void execute(StatelessSession ss) throws Exception
            {

                for (CommonRecipeDrug commonRecipeDrug : drugList)
                {
                    commonRecipeDrug.setCreateDt(now);
                    commonRecipeDrug.setCommonRecipeId(commonRecipeId);
                    ss.insert(commonRecipeDrug);
                }
            }
        };
        HibernateSessionTemplate.instance().executeTrans(action);

    }

}
