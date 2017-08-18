package eh.cdr.service;

import ctd.persistence.DAOFactory;
import ctd.util.AppContextHolder;
import ctd.util.JSONUtils;
import ctd.util.annotation.RpcBean;
import ctd.util.annotation.RpcService;
import eh.base.constant.SystemConstant;
import eh.cdr.bean.DepDetailBean;
import eh.cdr.bean.DepListBean;
import eh.cdr.bean.DrugEnterpriseResult;
import eh.cdr.bean.RecipeResultBean;
import eh.cdr.constant.DrugEnterpriseConstant;
import eh.cdr.constant.RecipeConstant;
import eh.cdr.dao.RecipeDAO;
import eh.cdr.dao.RecipeDetailDAO;
import eh.cdr.drugsenterprise.RemoteDrugEnterpriseService;
import eh.entity.cdr.DrugsEnterprise;
import eh.entity.cdr.Recipe;
import eh.entity.cdr.Recipedetail;
import eh.utils.params.ParamUtils;
import eh.utils.params.ParameterConstant;
import org.apache.commons.collections.CollectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 患者端服务
 * company: ngarihealth
 * author: 0184/yu_yun
 * date:2017/6/30.
 */
@RpcBean
public class RecipePatientService extends RecipeBaseService{

    /** logger */
    private static final Logger logger = LoggerFactory.getLogger(RecipePatientService.class);

    /**
     * 获取供应商列表
     * @param findDetail 1:表示获取详情，0：表示判断是否需要展示供应商具体列表
     * @param recipeId
     */
    @RpcService
    public RecipeResultBean findSupportDepList(int findDetail, List<Integer> recipeIds){
        RecipeDAO recipeDAO = DAOFactory.getDAO(RecipeDAO.class);
        RecipeDetailDAO detailDAO = DAOFactory.getDAO(RecipeDetailDAO.class);
        RecipeService recipeService = AppContextHolder.getBean("eh.recipeService", RecipeService.class);
        RemoteDrugEnterpriseService remoteDrugService = AppContextHolder.getBean("eh.remoteDrugService", RemoteDrugEnterpriseService.class);

        RecipeResultBean resultBean = RecipeResultBean.getSuccess();
        List<Recipe> recipeList = recipeDAO.findByRecipeIds(recipeIds);
        if(CollectionUtils.isEmpty(recipeList)){
            resultBean.setCode(RecipeResultBean.FAIL);
            resultBean.setMsg("处方不存在");
            return resultBean;
        }

        DepListBean depListBean = new DepListBean();
        Integer organId = recipeList.get(0).getClinicOrgan();
        BigDecimal totalMoney = BigDecimal.ZERO;
        for(Recipe recipe : recipeList) {
            if(!recipe.getClinicOrgan().equals(organId)){
                resultBean.setCode(RecipeResultBean.FAIL);
                resultBean.setMsg("选择处方的机构不一致，请重新选择");
                return resultBean;
            }

            totalMoney = totalMoney.add(recipe.getTotalMoney());
        }

        List<DrugsEnterprise> depList = recipeService.findSupportDepList(recipeIds,organId,null,false,null);
        logger.info("findSupportDepList recipeIds={}, 匹配到药企数量[{}]", JSONUtils.toString(recipeIds), depList.size());
        if(CollectionUtils.isNotEmpty(depList)){
            //设置默认值
            depListBean.setSigle(true);
            //只需要查询是否存在多个供应商
            if(0 == findDetail && depList.size() > 1){
                depListBean.setSigle(false);
                resultBean.setObject(depListBean);
                return resultBean;
            }

            //判断是否需要展示供应商详情列表，如果遇上钥世圈的药企，则都展示供应商列表
            List<DepDetailBean> depDetailList = new ArrayList<>();
            for(DrugsEnterprise dep : depList){
                //钥世圈需要从接口获取支持药店列表
                if(DrugEnterpriseConstant.COMPANY_YSQ.equals(dep.getAccount())){
                    //需要从接口获取药店列表
                    DrugEnterpriseResult drugEnterpriseResult = remoteDrugService.findSupportDep(recipeIds,dep);
                    if(DrugEnterpriseResult.SUCCESS.equals(drugEnterpriseResult.getCode())) {
                        Object listObj = drugEnterpriseResult.getObject();
                        if(null != listObj && listObj instanceof List){
                            List<DepDetailBean> ysqList = (List)listObj;
                            for(DepDetailBean _subDep : ysqList){
                                _subDep.setDepId(dep.getId());
                            }
                            depDetailList.addAll(ysqList);
                        }
                        //设置样式
                        resultBean.setStyle(drugEnterpriseResult.getStyle());
                    }
                }else{
                    parseDrugsEnterprise(dep, totalMoney, depDetailList);
                }

                //只是查询的话减少处理量
                if(0 == findDetail && depDetailList.size() > 1){
                    depListBean.setSigle(false);
                    break;
                }
            }

            //有可能钥世圈支持配送，实际从接口处没有获取到药店
            if(CollectionUtils.isEmpty(depDetailList)){
                resultBean.setCode(RecipeResultBean.FAIL);
                resultBean.setMsg("很抱歉，当前库存不足无法购买，请联系客服：" +
                        ParamUtils.getParam(ParameterConstant.KEY_CUSTOMER_TEL, SystemConstant.CUSTOMER_TEL));
                return resultBean;
            }

            depListBean.setList(depDetailList);
            resultBean.setObject(depListBean);
            //只需要查询是否存在多个供应商， 就不需要设置其他额外信息
            if(0 == findDetail){
                return resultBean;
            }

            if(depDetailList.size() > 1){
                depListBean.setSigle(false);
            }

            //该详情数据包含了所有处方的详情，可能存在同一种药品数据
            List<Recipedetail> details = detailDAO.findByRecipeIds(recipeIds);
            List<Recipedetail> backDetails = new ArrayList<>(details.size());
            Map<Integer, Double> drugIdCountRel = new HashMap<>();
            Recipedetail backDetail;
            for(Recipedetail recipedetail : details){
                Integer drugId = recipedetail.getDrugId();
                if(drugIdCountRel.containsKey(drugId)){
                    drugIdCountRel.put(drugId, drugIdCountRel.get(recipedetail.getDrugId())+recipedetail.getUseTotalDose());
                }else{
                    backDetail = new Recipedetail();
                    backDetail.setDrugId(recipedetail.getDrugId());
                    backDetail.setDrugName(recipedetail.getDrugName());
                    backDetail.setDrugUnit(recipedetail.getDrugUnit());
                    backDetail.setDrugSpec(recipedetail.getDrugSpec());
                    backDetail.setUseDoseUnit(recipedetail.getUseDoseUnit());
                    backDetails.add(backDetail);
                    drugIdCountRel.put(drugId, recipedetail.getUseTotalDose());
                }
            }
            //重置药品数量
            for(Recipedetail recipedetail : backDetails){
                recipedetail.setUseTotalDose(drugIdCountRel.get(recipedetail.getDrugId()));
            }
            depListBean.setDetails(backDetails);
            //患者处方取药方式提示
            if(recipeIds.size() <= 1) {
                depListBean.setRecipeGetModeTip(recipeService.getRecipeGetModeTip(recipeList.get(0)));
            }
        }else{
            resultBean.setCode(RecipeResultBean.FAIL);
            resultBean.setMsg("很抱歉，当前库存不足无法购买，请联系客服：" +
                    ParamUtils.getParam(ParameterConstant.KEY_CUSTOMER_TEL, SystemConstant.CUSTOMER_TEL));
        }

        return resultBean;
    }

    private void parseDrugsEnterprise(DrugsEnterprise dep, BigDecimal totalMoney, List<DepDetailBean> depDetailList){
        DepDetailBean depDetailBean = new DepDetailBean();
        depDetailBean.setDepId(dep.getId());
        depDetailBean.setDepName(dep.getName());
        depDetailBean.setRecipeFee(totalMoney);
        Integer supportMode = dep.getPayModeSupport();
        String giveModeText = "";
        List<Integer> payModeList = new ArrayList<>();
        if(1 == supportMode){
            payModeList.add(RecipeConstant.PAYMODE_ONLINE);
            giveModeText = "配送到家";
            //无法配送时间文案提示
            depDetailBean.setUnSendTitle(ParamUtils.getParam(ParameterConstant.KEY_RECIPE_UNSEND_TIP));
        }else if(2 == supportMode){
            payModeList.add(RecipeConstant.PAYMODE_COD);
            giveModeText = "配送到家";
        }else if(3 == supportMode){
            payModeList.add(RecipeConstant.PAYMODE_TFDS);
        }else if(8 == supportMode){
            payModeList.add(RecipeConstant.PAYMODE_COD);
            payModeList.add(RecipeConstant.PAYMODE_TFDS);
        }else if(9 == supportMode){
            payModeList.add(RecipeConstant.PAYMODE_ONLINE);
            payModeList.add(RecipeConstant.PAYMODE_COD);
            payModeList.add(RecipeConstant.PAYMODE_TFDS);
            //无法配送时间文案提示
            depDetailBean.setUnSendTitle(ParamUtils.getParam(ParameterConstant.KEY_RECIPE_UNSEND_TIP));
        }
        depDetailBean.setPayModeList(payModeList);
        depDetailBean.setGiveModeText(giveModeText);
        depDetailList.add(depDetailBean);
    }
}
