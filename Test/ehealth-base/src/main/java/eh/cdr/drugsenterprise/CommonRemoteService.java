package eh.cdr.drugsenterprise;

import ctd.controller.exception.ControllerException;
import ctd.dictionary.DictionaryController;
import ctd.persistence.DAOFactory;
import ctd.util.JSONUtils;
import eh.base.dao.DoctorDAO;
import eh.base.dao.DrugListDAO;
import eh.base.dao.OrganDAO;
import eh.base.dao.SaleDrugListDAO;
import eh.cdr.bean.DepDetailBean;
import eh.cdr.bean.DrugEnterpriseResult;
import eh.cdr.dao.RecipeDAO;
import eh.cdr.dao.RecipeDetailDAO;
import eh.cdr.dao.RecipeOrderDAO;
import eh.cdr.service.RecipeLogService;
import eh.cdr.thread.CommonSyncDrugCallable;
import eh.cdr.thread.PushRecipToEpCallable;
import eh.cdr.thread.RecipeBusiThreadPool;
import eh.entity.base.DrugList;
import eh.entity.base.Organ;
import eh.entity.cdr.DrugsEnterprise;
import eh.entity.cdr.Recipe;
import eh.entity.cdr.RecipeOrder;
import eh.entity.cdr.Recipedetail;
import eh.entity.mpi.Patient;
import eh.mpi.dao.HealthCardDAO;
import eh.mpi.dao.PatientDAO;
import eh.util.HttpHelper;
import eh.utils.MapValueUtil;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;

/**
 * 通用药企对接服务实现(国药协议)
 * company: ngarihealth
 * author: 0184/yu_yun
 * date:2017/3/7.
 */
public class CommonRemoteService extends AccessDrugEnterpriseService {

    private static final Logger logger = LoggerFactory.getLogger(CommonRemoteService.class);

    private static Integer RESULT_SUCCESS = 1;

    @Override
    public DrugEnterpriseResult pushRecipeInfo(List<Integer> recipeIds) {
        DrugEnterpriseResult result = DrugEnterpriseResult.getSuccess();
        if (CollectionUtils.isEmpty(recipeIds)) {
            result.setMsg("处方ID参数为空");
            result.setCode(DrugEnterpriseResult.FAIL);
            return result;
        }

        RecipeDAO recipeDAO = DAOFactory.getDAO(RecipeDAO.class);
        PatientDAO patientDAO = DAOFactory.getDAO(PatientDAO.class);
        DoctorDAO doctorDAO = DAOFactory.getDAO(DoctorDAO.class);
        OrganDAO organDAO = DAOFactory.getDAO(OrganDAO.class);
        DrugListDAO drugListDAO = DAOFactory.getDAO(DrugListDAO.class);
        HealthCardDAO healthCardDAO = DAOFactory.getDAO(HealthCardDAO.class);
        RecipeDetailDAO recipeDetailDAO = DAOFactory.getDAO(RecipeDetailDAO.class);
        RecipeOrderDAO orderDAO = DAOFactory.getDAO(RecipeOrderDAO.class);

        //药企与处方数据关系
        Map<Integer, List<Map<String, Object>>> enterpriseRecipesMap = new HashMap<>();
        //方便更新处方推送状态标志位
        Map<Integer, Set<Integer>> enterpriseRecipeIdsMap = new HashMap<>();
        //药品相关数据,key为druglist表drugId,value为药品数据，如果推送处方的数据里存在药企没有的数据，则需要将该数据发给药企
        Map<Integer, Map<String, Object>> drugsMap = new HashMap<>();

        Recipe recipe;
        RecipeOrder order;
        Patient patient;
        Organ organ;
        Map<String, Object> recipeMap;
        List<Map<String, Object>> recipeDetailList;
        for (Integer recipeId : recipeIds) {
            recipe = recipeDAO.getByRecipeId(recipeId);
            if (null == recipe) {
                logger.error("pushRecipInfo ID为" + recipeId + "的处方不存在");
                continue;
            }

            if (StringUtils.isEmpty(recipe.getOrderCode())) {
                logger.error("pushRecipInfo recipeId={}, 不存在订单编号.", recipeId);
                continue;
            }

            order = orderDAO.getByOrderCode(recipe.getOrderCode());
            if (null == order) {
                logger.error("pushRecipInfo code为" + recipe.getOrderCode() + "的订单不存在");
                continue;
            }

            Integer enterpriseId = order.getEnterpriseId();
            if (null == enterpriseId) {
                logger.error("pushRecipInfo 该订单推送药企ID为null，订单编号:" + order.getOrderCode());
                continue;
            }

            try {
                patient = patientDAO.get(recipe.getMpiid());
            } catch (Exception e) {
                patient = null;
            }
            if (null == patient) {
                logger.error("pushRecipInfo ID为" + recipe.getMpiid() + "的患者不存在");
                continue;
            }

            try {
                organ = organDAO.getByOrganId(recipe.getClinicOrgan());
            } catch (Exception e) {
                organ = null;
            }
            if (null == organ) {
                logger.error("pushRecipInfo ID为" + recipe.getClinicOrgan() + "的机构不存在");
                continue;
            }

            recipeMap = new HashMap<>();
            recipeDetailList = new ArrayList<>();

            if (null == enterpriseRecipesMap.get(enterpriseId)) {
                enterpriseRecipesMap.put(enterpriseId, new ArrayList<Map<String, Object>>(0));
                enterpriseRecipeIdsMap.put(enterpriseId, new HashSet<Integer>(0));
            }

            enterpriseRecipesMap.get(enterpriseId).add(recipeMap);
            enterpriseRecipeIdsMap.get(enterpriseId).add(recipeId);

            //组装recipe数据
            recipeMap.put("recipeid", recipe.getRecipeId());
            recipeMap.put("recipecode", recipe.getRecipeCode());
            recipeMap.put("recipetype", recipe.getRecipeType());
            //此处接口那边不是中药方需要置为1
            recipeMap.put("copynum", recipe.getCopyNum());
            recipeMap.put("createdate", recipe.getSignDate());
            recipeMap.put("requestdate", new Date());
            recipeMap.put("patientid", recipe.getMpiid());
            recipeMap.put("patientname", patient.getPatientName());
            recipeMap.put("patientsex", Integer.parseInt(patient.getPatientSex()));
            recipeMap.put("nric", patient.getIdcard());
            recipeMap.put("medicarecard", healthCardDAO.getMedicareCardId(recipe.getMpiid(), Integer.valueOf(recipe.getClinicOrgan())));

            //地址信息在订单表里
            recipeMap.put("receiver", order.getReceiver());
            recipeMap.put("tel", order.getRecMobile());
            recipeMap.put("zipcode", order.getZipCode());
            recipeMap.put("address", getCompleteAddress(order));
            recipeMap.put("transvalue", order.getExpressFee().doubleValue());

            recipeMap.put("paymode", recipe.getPayMode());
            //医院(药店)信息
            recipeMap.put("cstid", recipe.getClinicOrgan());
            recipeMap.put("cstname", organ.getName());
            recipeMap.put("csttype", "00");
            recipeMap.put("cstaddress", organ.getAddress());
            recipeMap.put("hospitalid", recipe.getClinicOrgan());
            recipeMap.put("doctorid", recipe.getDoctor());
            recipeMap.put("doctorname", doctorDAO.getNameById(recipe.getDoctor()));
            recipeMap.put("signfile", (null == recipe.getSignFile()) ? null : recipe.getSignFile());
            recipeMap.put("dtl", recipeDetailList);

            //处方详情数据
            List<Recipedetail> recipedetail = recipeDetailDAO.findByRecipeId(recipe.getRecipeId());
            Map<String, Object> detailMap;
            Map<String, Object> drugMap;
            DrugList drug;
            for (Recipedetail detail : recipedetail) {
                detailMap = new HashMap<>();
                detailMap.put("dtlid", detail.getRecipeDetailId());
                detailMap.put("spec", detail.getDrugSpec());
                detailMap.put("prc", detail.getSalePrice());
                detailMap.put("usedose", detail.getUseDose());
                detailMap.put("usedoseunit", detail.getUseDoseUnit());
                String userRate = detail.getUsingRate();
                if (StringUtils.isNotEmpty(userRate)) {
                    try {
                        detailMap.put("usingratename", DictionaryController.instance().get("eh.cdr.dictionary.UsingRate").getText(userRate));
                    } catch (ControllerException e) {
                        logger.error("pushRecipInfo 获取用药频次类型失败*****usingRate:" + userRate);
                        detailMap.put("usingratename", "每日三次");
                    }
                } else {
                    logger.error("pushRecipInfo usingRate为null");
                    detailMap.put("usingratename", "每日三次");
                }
                String usePathways = detail.getUsePathways();
                if (StringUtils.isNotEmpty(usePathways)) {
                    try {
                        detailMap.put("usepathwaysname", DictionaryController.instance().get("eh.cdr.dictionary.UsePathways").getText(usePathways));
                    } catch (ControllerException e) {
                        logger.error("pushRecipInfo 获取用药途径类型失败*****usePathways:" + usePathways);
                        detailMap.put("usepathwaysname", "口服");
                    }
                } else {
                    logger.error("pushRecipInfo usePathways为null");
                    detailMap.put("usepathwaysname", "口服");
                }
                detailMap.put("usedays", detail.getUseDays());
                detailMap.put("msunitno", detail.getDrugUnit());
                detailMap.put("qty", detail.getUseTotalDose());
                detailMap.put("goodsid", detail.getDrugId());

                //处理药品数据
                if (!drugsMap.containsKey(detail.getDrugId())) {
                    drugMap = new HashMap<>();
                    drug = drugListDAO.getById(detail.getDrugId());
                    if (null != drug) {
                        drugMap.put("producer", drug.getProducer());
                        drugMap.put("gname", drug.getDrugName());
                        drugMap.put("goodsid", drug.getDrugId());
                        drugMap.put("msunitno", detail.getDrugUnit());
                        drugMap.put("spec", detail.getDrugSpec());
                        drugMap.put("drugname", drug.getSaleName());
                        //件包装
//                        drugMap.put("packnum", 1);
                        //产地
//                        drugMap.put("prdarea", "");

                        drugsMap.put(drug.getDrugId(), drugMap);
                    }
                }

                recipeDetailList.add(detailMap);
            }
        }

        //推送给药企处方，按照药企ID来推送
        if (!enterpriseRecipesMap.isEmpty()) {
            List<PushRecipToEpCallable> callables = new ArrayList<>(0);
            for (Integer enterpriseId : enterpriseRecipesMap.keySet()) {
                callables.add(new PushRecipToEpCallable(enterpriseId, enterpriseRecipesMap.get(enterpriseId),
                        enterpriseRecipeIdsMap.get(enterpriseId), drugsMap));
            }

            if (CollectionUtils.isNotEmpty(callables)) {
                try {
                    new RecipeBusiThreadPool(callables).execute();
                } catch (InterruptedException e) {
                    logger.error("pushRecipInfo 线程池异常");
                }
            }
        }

        return DrugEnterpriseResult.getSuccess();
    }

    @Override
    public DrugEnterpriseResult scanStock(Integer recipeId, DrugsEnterprise drugsEnterprise) {
        DrugEnterpriseResult result = DrugEnterpriseResult.getSuccess();
        if (null == recipeId) {
            result.setCode(DrugEnterpriseResult.FAIL);
            result.setMsg("处方ID为空");
            return result;
        }

        RecipeDAO recipeDAO = DAOFactory.getDAO(RecipeDAO.class);
        RecipeDetailDAO recipeDetailDAO = DAOFactory.getDAO(RecipeDetailDAO.class);
        OrganDAO organDAO = DAOFactory.getDAO(OrganDAO.class);

        String drugEpName = drugsEnterprise.getName();
        String method = "scanStock";
        Recipe recipe = recipeDAO.getByRecipeId(recipeId);
        List<Recipedetail> detailList = recipeDetailDAO.findByRecipeId(recipeId);
        if (null != recipe && CollectionUtils.isNotEmpty(detailList)) {
            Map<String, Object> sendMap = new HashMap<>();
            Map<String, Object> recipeInfo = new HashMap<>();
            List<Map<String, Object>> detailInfoList = new ArrayList<>(10);

            recipeInfo.put("recipeid", recipeId);
            if (null != recipe.getClinicOrgan()) {
                recipeInfo.put("cstid", recipe.getClinicOrgan());
                recipeInfo.put("cstname", organDAO.getNameById(recipe.getClinicOrgan()));
            }
            recipeInfo.put("dtl", detailInfoList);
            Map<String, Object> detailInfo;
            for (Recipedetail detail : detailList) {
                detailInfo = new HashMap<>();
                detailInfo.put("dtlid", detail.getRecipeDetailId());
                detailInfo.put("goodsid", detail.getDrugId());
                detailInfo.put("qty", detail.getUseTotalDose());
                detailInfoList.add(detailInfo);
            }

            sendMap.put("access_token", drugsEnterprise.getToken());
            sendMap.put("action", method);
            sendMap.put("data", recipeInfo);

            String sendInfoStr = JSONUtils.toString(sendMap);
            logger.info("发送[{}][{}]内容：{}",drugEpName,method,sendInfoStr);

            String backMsg = null;
            try {
                backMsg = HttpHelper.doPost(drugsEnterprise.getBusinessUrl(), sendInfoStr);
                if(StringUtils.isEmpty(backMsg)){
                    logger.error("调用[{}][{}]结果返回为空",drugEpName, method);
                    result.setMsg(drugEpName + "接口返回结果为空");
                    result.setCode(DrugEnterpriseResult.FAIL);
                }else {
                    logger.info("调用[{}][{}]结果返回={}", drugEpName, method, backMsg);
                }
            } catch (IOException e) {
                backMsg = null;
                logger.error(drugEpName + " invoke method[{}] error. error={}", method, e.getMessage());
                result.setMsg(drugEpName + "接口["+method+"]调用出错");
                result.setCode(DrugEnterpriseResult.FAIL);
            }

            if (StringUtils.isNotEmpty(backMsg)) {
                Map backMap = JSONUtils.parse(backMsg, Map.class);
                Integer code = MapValueUtil.getInteger(backMap, "code");
                // code 1成功
                if (RESULT_SUCCESS.equals(code)) {
                    result.setMsg("调用["+drugEpName+"]["+method+"]结果返回成功.");
                } else {
                    updateAccessTokenById(code, drugsEnterprise.getId());

                    StringBuilder logInfo = new StringBuilder();
                    logInfo.append("调用["+drugEpName+"]["+method+"]结果返回失败.error:"+ MapValueUtil.getString(backMap,"message")+"*");
                    Object goodsidObj = backMap.get("goodsid");
                    if (null != goodsidObj && goodsidObj instanceof List) {
                        List<Integer> errorIds = (List<Integer>) goodsidObj;
                        if (CollectionUtils.isNotEmpty(errorIds)) {
                            //将药企对该药品可配送的记录置为无效
                            SaleDrugListDAO saleDrugListDAO = DAOFactory.getDAO(SaleDrugListDAO.class);
                            saleDrugListDAO.updateInvalidByOrganIdAndDrugIds(drugsEnterprise.getId(), errorIds);
                            logInfo.append("goodsid:" + JSONUtils.toString(errorIds));
                        }
                    }

                    logger.error(logInfo.toString());
                    result.setCode(DrugEnterpriseResult.FAIL);
                    result.setMsg(logInfo.toString());
                }
            }else{
                result.setCode(DrugEnterpriseResult.FAIL);
                result.setMsg("调用["+drugEpName+"]["+method+"]结果返回为空.");
            }

            RecipeLogService.saveRecipeLog(recipeId, recipe.getStatus(), recipe.getStatus(), result.getMsg());
        }else{
            result.setCode(DrugEnterpriseResult.FAIL);
            result.setMsg("处方没有详细药品数据");
        }

        return result;
    }

    @Override
    public DrugEnterpriseResult syncEnterpriseDrug(DrugsEnterprise drugsEnterprise, List<Integer> drugIdList) {
        List<CommonSyncDrugCallable> callAbles = new ArrayList<>(10);
        int size = splitGroupSize(drugIdList.size());

        for(int i=0;i<size;i++){
            int start = i*ONCETIME_DEAL_NUM;
            int end = start+ONCETIME_DEAL_NUM;
            if(end > drugIdList.size()){
                end = drugIdList.size();
            }

            callAbles.add(new CommonSyncDrugCallable(drugsEnterprise,drugIdList.subList(start,end)));
        }

        if(CollectionUtils.isNotEmpty(callAbles)){
            try {
                new RecipeBusiThreadPool(callAbles).execute();
            } catch (InterruptedException e) {
                logger.error("syncDrug 线程池异常");
            }
        }

        return DrugEnterpriseResult.getSuccess();
    }

    @Override
    public DrugEnterpriseResult pushCheckResult(Integer recipeId, Integer checkFlag) {
        //TODO
        logger.info("CommonRemoteService pushCheckResult not implement.");
        return DrugEnterpriseResult.getSuccess();
    }

    @Override
    public DrugEnterpriseResult findSupportDep(List<Integer> recipeIds) {
        //TODO
        logger.info("CommonRemoteService findSupportDep not implement.");
        return null;
    }

    @Override
    public String getDrugEnterpriseAccount() {
        return "common";
    }
}
