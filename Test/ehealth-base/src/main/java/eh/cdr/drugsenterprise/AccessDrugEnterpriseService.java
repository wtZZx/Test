package eh.cdr.drugsenterprise;

import ctd.controller.exception.ControllerException;
import ctd.dictionary.DictionaryController;
import ctd.persistence.DAOFactory;
import eh.cdr.bean.DrugEnterpriseResult;
import eh.cdr.dao.DrugsEnterpriseDAO;
import eh.cdr.thread.RecipeBusiThreadPool;
import eh.cdr.thread.UpdateDrugsEpCallable;
import eh.entity.cdr.DrugsEnterprise;
import eh.entity.cdr.RecipeOrder;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 通用药企对接服务(国药协议)
 * company: ngarihealth
 * author: 0184/yu_yun
 * date:2016/10/19.
 */
public abstract class AccessDrugEnterpriseService {

    private static final Logger logger = LoggerFactory.getLogger(AccessDrugEnterpriseService.class);

    /**
     * 单个线程处理药企药品数量
     */
    protected static final int ONCETIME_DEAL_NUM = 100;

    public void updateAccessTokenById(Integer code , Integer depId){
        if(-2 == code) {
            updateAccessToken(Arrays.asList(depId));
        }
    }

    /**
     * 获取药企AccessToken
     * @param drugsEnterpriseIds
     * @return
     */
    public String updateAccessToken(List<Integer> drugsEnterpriseIds){
        if(null != drugsEnterpriseIds && !drugsEnterpriseIds.isEmpty()){
            List<UpdateDrugsEpCallable> callables = new ArrayList<>(0);
            for (int i = 0; i < drugsEnterpriseIds.size() ; i++) {
                callables.add(new UpdateDrugsEpCallable(drugsEnterpriseIds.get(i)));
            }

            if(!callables.isEmpty()){
                try {
                    new RecipeBusiThreadPool(callables).execute();
                } catch (InterruptedException e) {
                    logger.error("updateAccessToken 线程池异常");
                }
            }
        }

        return null;
    }

    /**
     * 生成完整地址
     * @param order 订单
     * @return
     */
    public String getCompleteAddress(RecipeOrder order){
        StringBuilder address = new StringBuilder();
        if(null != order) {
            this.getAddressDic(address, order.getAddress1());
            this.getAddressDic(address, order.getAddress2());
            this.getAddressDic(address, order.getAddress3());
            address.append(StringUtils.isEmpty(order.getAddress4())?"":order.getAddress4());
        }
        return address.toString();
    }

    public void getAddressDic(StringBuilder address, String area){
        if(StringUtils.isNotEmpty(area)){
            try {
                address.append(DictionaryController.instance().get("eh.base.dictionary.AddrArea").getText(area));
            } catch (ControllerException e) {
                logger.error("getAddressDic 获取地址数据类型失败*****area:"+area);
            }
        }
    }

    /**
     * 格式化Double
     * @param d
     * @return
     */
    protected String getFormatDouble(Double d){
        return BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
    }

    /**
     * 获取对应药企对象
     * @return
     */
    protected DrugsEnterprise getDrugsEnterpriseByAccount(){
        return DAOFactory.getDAO(DrugsEnterpriseDAO.class).getByAccount(this.getDrugEnterpriseAccount());
    }

    /**
     * 某一列表分成多段
     * @param osize
     * @return
     */
    protected int splitGroupSize(int osize){
       return (int) Math.ceil(osize / Double.parseDouble(String.valueOf(ONCETIME_DEAL_NUM)));
    }

    /**
     * 推送处方
     * @param recipeIds 处方ID集合
     * @return
     */
    public abstract DrugEnterpriseResult pushRecipeInfo(List<Integer> recipeIds);

    /**
     * 库存检验
     * @param recipeId 处方ID
     * @param drugsEnterprise 药企
     * @return
     */
    public abstract DrugEnterpriseResult scanStock(Integer recipeId, DrugsEnterprise drugsEnterprise);

    /**
     * 定时同步药企库存
     * @return
     */
    public abstract DrugEnterpriseResult syncEnterpriseDrug(DrugsEnterprise drugsEnterprise, List<Integer> drugIdList);

    /**
     * 药师审核通过通知消息
     * @param recipeId 处方ID
     * @param checkFlag 审核结果 1:审核通过 0:审核失败
     * @return
     */
    public abstract DrugEnterpriseResult pushCheckResult(Integer recipeId, Integer checkFlag);

    /**
     * 查找供应商
     * @param recipeId
     * @return
     */
    public abstract DrugEnterpriseResult findSupportDep(List<Integer> recipeIds);

    /**
     * 获取药企帐号字段
     * @return
     */
    public abstract String getDrugEnterpriseAccount();
}
