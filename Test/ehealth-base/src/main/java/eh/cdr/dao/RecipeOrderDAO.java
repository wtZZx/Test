package eh.cdr.dao;

import ctd.persistence.annotation.DAOMethod;
import ctd.persistence.annotation.DAOParam;
import ctd.persistence.support.hibernate.HibernateSupportDelegateDAO;
import ctd.persistence.support.hibernate.template.AbstractHibernateStatelessResultAction;
import ctd.persistence.support.hibernate.template.HibernateSessionTemplate;
import ctd.persistence.support.hibernate.template.HibernateStatelessResultAction;
import eh.cdr.constant.RecipeConstant;
import eh.entity.cdr.RecipeOrder;
import org.apache.commons.lang3.StringUtils;
import org.hibernate.Query;
import org.hibernate.StatelessSession;

import java.util.List;
import java.util.Map;

/**
 * company: ngarihealth
 * author: 0184/yu_yun
 * date:2017/2/13.
 */
public abstract class RecipeOrderDAO extends HibernateSupportDelegateDAO<RecipeOrder> {

    public RecipeOrderDAO() {
        super();
        this.setEntityName(RecipeOrder.class.getName());
        this.setKeyField("orderId");
    }

    /**
     * 根据编号获取有效订单
     * @param orderCode
     * @return
     */
    @DAOMethod(sql = "from RecipeOrder where orderCode=:orderCode")
    public abstract RecipeOrder getByOrderCode(@DAOParam("orderCode") String orderCode);

    @DAOMethod
    public abstract RecipeOrder getByOutTradeNo(String tradeNo);

    @DAOMethod(sql = "select order.orderCode from RecipeOrder order, Recipe recipe where order.orderCode=recipe.orderCode and order.effective=1 and recipe.recipeId=:recipeId")
    public abstract String getOrderCodeByRecipeId(@DAOParam("recipeId") Integer recipeId);

    @DAOMethod(sql = "select order.orderCode from RecipeOrder order, Recipe recipe where order.orderCode=recipe.orderCode and recipe.recipeId=:recipeId")
    public abstract String getOrderCodeByRecipeIdWithoutCheck(@DAOParam("recipeId") Integer recipeId);

    @DAOMethod(sql = "select order from RecipeOrder order, Recipe recipe where order.orderCode=recipe.orderCode and order.effective=1 and recipe.recipeId=:recipeId")
    public abstract RecipeOrder getOrderByRecipeId(@DAOParam("recipeId") Integer recipeId);

    @DAOMethod(sql = "select order.enterpriseId from RecipeOrder order, Recipe recipe where order.orderCode=recipe.orderCode and recipe.recipeId=:recipeId")
    public abstract Integer getEnterpriseIdByRecipeId(@DAOParam("recipeId") Integer recipeId);

    @DAOMethod
    public abstract List<RecipeOrder> findByPayFlag(Integer payFlag);

    /**
     * 订单是否有效
     * @param orderCode
     * @return
     */
    public boolean isEffectiveOrder(final String orderCode, final Integer payMode){
        if(StringUtils.isEmpty(orderCode)){
            return false;
        }

        HibernateStatelessResultAction<Boolean> action = new AbstractHibernateStatelessResultAction<Boolean>() {
            @Override
            public void execute(StatelessSession ss) throws Exception {
                StringBuilder hql = new StringBuilder("select count(1) from RecipeOrder where orderCode=:orderCode ");
                //医保支付会生成一个无效的临时订单，但是医快付不允许重复发送同一个处方的信息
                if(null == payMode || !RecipeConstant.PAYMODE_MEDICAL_INSURANCE.equals(payMode)){
                    hql.append(" and effective=1 ");
                }
                Query q = ss.createQuery(hql.toString());
                q.setParameter("orderCode", orderCode);

                long count = (Long) q.uniqueResult();
                setResult(count>0);
            }
        };
        HibernateSessionTemplate.instance().executeReadOnly(action);
        return action.getResult();
    }

    /**
     *更新订单自定义字段
     * @param orderCode
     * @param changeAttr
     * @return
     */
    public Boolean updateByOrdeCode(final String orderCode, final Map<String, ?> changeAttr){
        if(null == changeAttr || changeAttr.isEmpty()){
            return true;
        }

        HibernateStatelessResultAction<Boolean> action = new AbstractHibernateStatelessResultAction<Boolean>() {
            @Override
            public void execute(StatelessSession ss) throws Exception {
                StringBuilder hql = new StringBuilder("update RecipeOrder set ");
                StringBuilder keyHql = new StringBuilder();
                for(String key : changeAttr.keySet()){
                    keyHql.append(","+key+"=:"+key);
                }
                hql.append(keyHql.toString().substring(1)).append(" where orderCode=:orderCode");
                Query q = ss.createQuery(hql.toString());

                q.setParameter("orderCode", orderCode);
                for(String key : changeAttr.keySet()){
                    q.setParameter(key, changeAttr.get(key));
                }

                int flag = q.executeUpdate();
                setResult(flag == 1);
            }
        };
        HibernateSessionTemplate.instance().execute(action);
        return action.getResult();
    }

}
