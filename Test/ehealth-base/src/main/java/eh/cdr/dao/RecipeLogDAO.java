package eh.cdr.dao;

import ctd.persistence.annotation.DAOMethod;
import ctd.persistence.support.hibernate.HibernateSupportDelegateDAO;
import eh.entity.cdr.RecipeLog;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.util.List;

/**
 * 处方流程记录
 * company: ngarihealth
 * author: 0184/yu_yun
 * date:2016/4/29.
 */
public abstract class RecipeLogDAO extends HibernateSupportDelegateDAO<RecipeLog> {

    private static final Log logger = LogFactory.getLog(RecipeLogDAO.class);

    public RecipeLogDAO() {
        super();
        this.setEntityName(RecipeLog.class.getName());
        this.setKeyField("id");
    }

    public boolean saveRecipeLog(RecipeLog log){
        save(log);
        return true;
    }

    @DAOMethod(orderBy = " id asc")
    public abstract List<RecipeLog> findByRecipeId(Integer recipeId);

}
