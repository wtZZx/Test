package eh.cdr.drugsenterprise;

import com.google.common.collect.ImmutableMap;
import ctd.controller.exception.ControllerException;
import ctd.dictionary.DictionaryController;
import ctd.persistence.DAOFactory;
import ctd.util.AppContextHolder;
import ctd.util.JSONUtils;
import eh.base.dao.DepartmentDAO;
import eh.base.dao.DoctorDAO;
import eh.base.dao.DrugListDAO;
import eh.base.dao.OrganDAO;
import eh.cdr.bean.DepDetailBean;
import eh.cdr.bean.DepStyleBean;
import eh.cdr.bean.DrugEnterpriseResult;
import eh.cdr.constant.DrugEnterpriseConstant;
import eh.cdr.constant.RecipeConstant;
import eh.cdr.constant.RecipeStatusConstant;
import eh.cdr.dao.RecipeDAO;
import eh.cdr.dao.RecipeDetailDAO;
import eh.cdr.dao.RecipeOrderDAO;
import eh.cdr.service.RecipeLogService;
import eh.cdr.service.RecipeOrderService;
import eh.entity.base.DrugList;
import eh.entity.base.Organ;
import eh.entity.cdr.DrugsEnterprise;
import eh.entity.cdr.Recipe;
import eh.entity.cdr.RecipeOrder;
import eh.entity.cdr.Recipedetail;
import eh.entity.mpi.Patient;
import eh.mpi.dao.PatientDAO;
import eh.utils.DateConversion;
import eh.utils.MapValueUtil;
import eh.utils.params.ParamUtils;
import eh.utils.params.ParameterConstant;
import org.apache.axis.Constants;
import org.apache.axis.client.Call;
import org.apache.axis.client.Service;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.namespace.QName;
import javax.xml.rpc.ParameterMode;
import javax.xml.rpc.ServiceException;
import java.net.URL;
import java.util.*;

/**
 * 钥世圈对接服务
 * company: ngarihealth
 * author: 0184/yu_yun
 * date:2017/3/7.
 */
public class YsqRemoteService extends AccessDrugEnterpriseService {

    private static final Logger logger = LoggerFactory.getLogger(YsqRemoteService.class);

    private static String NAME_SPACE = "http://tempuri.org/";

    private static String RESULT_SUCCESS = "00";

    public static final String YSQ_SPLIT = "-";

    @Override
    public DrugEnterpriseResult pushRecipeInfo(List<Integer> recipeIds) {
        DrugEnterpriseResult result = DrugEnterpriseResult.getSuccess();
        if (CollectionUtils.isEmpty(recipeIds)) {
            result.setMsg("处方ID参数为空");
            result.setCode(DrugEnterpriseResult.FAIL);
            return result;
        }

        //获取相应药企信息
        DrugsEnterprise drugsEnterprise = getDrugsEnterpriseByAccount();
        if(null == drugsEnterprise){
            result.setMsg("不存在account="+this.getDrugEnterpriseAccount()+"的药企");
            result.setCode(DrugEnterpriseResult.FAIL);
            return result;
        }

        RecipeDAO recipeDAO = DAOFactory.getDAO(RecipeDAO.class);
        String drugEpName = drugsEnterprise.getName();
        //最终发给药企的json数据
        Map<String, Object> sendInfo = new HashMap<>(1);
        sendInfo.put("EXEC_ORD","0");//同时生成订单 0不生成 1生成
        List<Map<String,Object>> recipeInfoList = getYsqRecipeInfo(recipeIds, true);
        if(recipeInfoList.isEmpty()){
            result.setMsg("钥世圈推送处方数量为0");
            result.setCode(DrugEnterpriseResult.FAIL);
            return result;
        }
        sendInfo.put("TITLES",recipeInfoList);
        String sendInfoStr = JSONUtils.toString(sendInfo);
        String methodName = "AcceptPrescription";
        logger.info("发送[{}][{}]内容：{}",drugEpName,methodName,sendInfoStr);

        //发送药企信息
        sendAndDealResult(drugsEnterprise,methodName,sendInfoStr,result);

        RecipeOrderService orderService = AppContextHolder.getBean("eh.recipeOrderService", RecipeOrderService.class);
        RecipeOrderDAO recipeOrderDAO = DAOFactory.getDAO(RecipeOrderDAO.class);

        if(DrugEnterpriseResult.SUCCESS.equals(result.getCode())) {
            recipeDAO.updatePushFlagByRecipeId(recipeIds);
            for (Integer recipeId : recipeIds) {
                orderService.updateOrderInfo(recipeOrderDAO.getOrderCodeByRecipeIdWithoutCheck(recipeId), ImmutableMap.of("pushFlag", 1,"depSn",result.getDepSn()),null);
                RecipeLogService.saveRecipeLog(recipeId, RecipeStatusConstant.CHECK_PASS, RecipeStatusConstant.CHECK_PASS, "药企推送成功:"+drugsEnterprise.getName());
            }
        }else{
            for (Integer recipeId : recipeIds) {
                orderService.updateOrderInfo(recipeOrderDAO.getOrderCodeByRecipeIdWithoutCheck(recipeId), ImmutableMap.of("pushFlag", -1),null);
                RecipeLogService.saveRecipeLog(recipeId, RecipeStatusConstant.CHECK_PASS, RecipeStatusConstant.CHECK_PASS, "推送处方失败,药企："+drugsEnterprise.getName()+",错误："+result.getMsg());
            }
            //TODO 当前钥世圈没有在线支付的情况
            result.setMsg("推送处方失败，"+result.getMsg());
            result.setCode(DrugEnterpriseResult.FAIL);
        }

        return result;
    }

    @Override
    public DrugEnterpriseResult scanStock(Integer recipeId, DrugsEnterprise drugsEnterprise) {
        logger.info("YsqRemoteService scanStock not implement.");
        return DrugEnterpriseResult.getSuccess();
    }

    @Override
    public DrugEnterpriseResult pushCheckResult(Integer recipeId, Integer checkFlag) {
        DrugEnterpriseResult result = DrugEnterpriseResult.getSuccess();
        if (null == recipeId) {
            result.setMsg("处方ID参数为空");
            result.setCode(DrugEnterpriseResult.FAIL);
            return result;
        }

        //获取相应药企信息
        DrugsEnterprise drugsEnterprise = getDrugsEnterpriseByAccount();
        if(null == drugsEnterprise){
            result.setMsg("不存在account="+this.getDrugEnterpriseAccount()+"的药企");
            result.setCode(DrugEnterpriseResult.FAIL);
            return result;
        }

        RecipeDAO recipeDAO = DAOFactory.getDAO(RecipeDAO.class);
        OrganDAO organDAO = DAOFactory.getDAO(OrganDAO.class);

        //最终发给药企的json数据
        Map<String, Object> sendInfo = new HashMap<>(1);
        //处方的json数据
        Map<String, String> recipeMap = new HashMap<>(1);
        List<Map<String,String>> recipeInfoList = new ArrayList<>(1);
        recipeInfoList.add(recipeMap);
        sendInfo.put("TITLES",recipeInfoList);

        String drugEpName = drugsEnterprise.getName();
        Recipe recipe = recipeDAO.get(recipeId);
        if(null != recipe){
            Organ organ;
            try {
                organ = organDAO.getByOrganId(recipe.getClinicOrgan());
            } catch (Exception e) {
                organ = null;
            }
            if (null == organ) {
                result.setMsg("机构不存在");
                result.setCode(DrugEnterpriseResult.FAIL);
                return result;
            }

            recipeMap.put("HOSCODE",organ.getOrganId().toString());
            recipeMap.put("HOSNAME",organ.getName());
            recipeMap.put("INBILLNO",recipe.getClinicOrgan()+YSQ_SPLIT+recipe.getRecipeCode());//医院处方号  医院机构?处方编号
            recipeMap.put("PDFID", (null != recipe.getChemistSignFile()?  //处方pdf文件Id   有药师签名则推送药师签名的pdf  无则推送医生签名的pdf
                    recipe.getChemistSignFile().toString() : recipe.getSignFile().toString()));
            recipeMap.put("FLAG",(1 == checkFlag)?"true":"false");
        }else{
            result.setMsg("处方不存在");
            result.setCode(DrugEnterpriseResult.FAIL);
            return result;
        }

        String sendInfoStr = JSONUtils.toString(sendInfo);
        String methodName = "PrescriptionReview";
        logger.info("发送[{}][{}]内容：{}",drugEpName,methodName,sendInfoStr);

        //发送药企信息
        sendAndDealResult(drugsEnterprise,methodName,sendInfoStr,result);
        RecipeLogService.saveRecipeLog(recipeId, recipe.getStatus(), recipe.getStatus(), result.getMsg());

        return result;
    }

    @Override
    public DrugEnterpriseResult findSupportDep(List<Integer> recipeIds) {
        DrugEnterpriseResult result = DrugEnterpriseResult.getSuccess();
        if (CollectionUtils.isEmpty(recipeIds)) {
            result.setMsg("处方ID集合为空");
            result.setCode(DrugEnterpriseResult.FAIL);
            return result;
        }

        //获取相应药企信息
        DrugsEnterprise drugsEnterprise = getDrugsEnterpriseByAccount();
        if(null == drugsEnterprise){
            result.setMsg("药企未找到");
            result.setCode(DrugEnterpriseResult.FAIL);
            logger.error("findSupportDep 药企未找到. account=[{}]", this.getDrugEnterpriseAccount());
            return result;
        }

        String drugEpName = drugsEnterprise.getName();
        //最终发给药企的json数据
        Map<String, Object> sendInfo = new HashMap<>(1);
        sendInfo.put("EXEC_ORD","0");//同时生成订单 0不生成 1生成
        List<Map<String,Object>> recipeInfoList = getYsqRecipeInfo(recipeIds, false);
        if(recipeInfoList.isEmpty()){
            result.setMsg("生成处方数量为0");
            result.setCode(DrugEnterpriseResult.FAIL);
            logger.error("findSupportDep 生成处方数量为0. recipeIds={}, depId=[{}]", JSONUtils.toString(recipeIds), drugsEnterprise.getId());
            return result;
        }
        sendInfo.put("TITLES",recipeInfoList);
        String sendInfoStr = JSONUtils.toString(sendInfo);
        String methodName = "PrescriptionGYSLists";
        logger.info("发送[{}][{}]内容：{}",drugEpName,methodName,sendInfoStr);

        //发送药企信息
        sendAndDealResult(drugsEnterprise,methodName,sendInfoStr,result);
        return result;
    }

    @Override
    public DrugEnterpriseResult syncEnterpriseDrug(DrugsEnterprise drugsEnterprise, List<Integer> drugIdList) {
        logger.info("YsqRemoteService syncDrugTask not implement.");
        return DrugEnterpriseResult.getSuccess();
    }

    @Override
    public String getDrugEnterpriseAccount() {
        return DrugEnterpriseConstant.COMPANY_YSQ;
    }

    /**
     * 发送药企信息处理返回结果
     * @param drugsEnterprise
     * @param method
     * @param sendInfoStr
     * @param result
     */
    private void sendAndDealResult(DrugsEnterprise drugsEnterprise, String method, String sendInfoStr, DrugEnterpriseResult result){
        String drugEpName = drugsEnterprise.getName();
        String resultJson = null;
        try {
            Call call = getCall(drugsEnterprise, method);
            if(null != call) {
                call.addParameter(new QName(NAME_SPACE, "AppKey"), Constants.XSD_STRING, ParameterMode.IN);
                call.addParameter(new QName(NAME_SPACE, "AppSecret"), Constants.XSD_STRING, ParameterMode.IN);
                call.addParameter(new QName(NAME_SPACE, "PrescriptionInfo"), Constants.XSD_STRING, ParameterMode.IN);
                call.setReturnType(Constants.XSD_STRING);

                Object[] param = {drugsEnterprise.getUserId(), drugsEnterprise.getPassword(), sendInfoStr};
                Object resultObj = call.invoke(param);
                if(null != resultObj && resultObj instanceof String){
                    resultJson = resultObj.toString();
                    logger.info("调用[{}][{}]结果返回={}",drugEpName, method, resultJson);
                }else{
                    logger.error("调用[{}][{}]结果返回为空",drugEpName, method);
                    result.setMsg(drugEpName + "接口返回结果为空");
                    result.setCode(DrugEnterpriseResult.FAIL);
                }
            }
        } catch (Exception e) {
            resultJson = null;
            logger.error(drugEpName + " invoke method[{}] error. error={}", method, e.getMessage());
            result.setMsg(drugEpName + "接口调用出错");
            result.setCode(DrugEnterpriseResult.FAIL);
        }

        if(StringUtils.isNotEmpty(resultJson)){
            Map resultMap = JSONUtils.parse(resultJson, Map.class);
            String resCode = MapValueUtil.getString(resultMap,"CODE");
            //重复发送订单也当作成功处理
            if(RESULT_SUCCESS.equals(resCode) || ("10".equals(resCode) && "AcceptPrescription".equals(method))){
                //成功
                if("AcceptPrescription".equals(method)) {
                    result.setDepSn(MapValueUtil.getString(resultMap, "SN"));
                    result.setMsg("调用["+drugEpName+"]["+method+"]成功.sn="+result.getDepSn());
                }else if("PrescriptionGYSLists".equals(method)){
                    //供应商列表处理
                    List<Map<String, Object>> depList = MapValueUtil.getList(resultMap, "LIST");
                    if(CollectionUtils.isNotEmpty(depList)){
                        List<DepDetailBean> detailList = new ArrayList<>();
                        DepDetailBean detailBean;
                        for(Map<String, Object> dep : depList){
                            detailBean = new DepDetailBean();
                            detailBean.setDepName(MapValueUtil.getString(dep,"GYSNAME"));
                            detailBean.setRecipeFee(MapValueUtil.getBigDecimal(dep,"TOTALACCOUNT"));
                            detailBean.setExpressFee(MapValueUtil.getBigDecimal(dep,"PEISONGACCOUNT"));
                            detailBean.setGysCode(MapValueUtil.getString(dep,"GYSCODE"));
                            String sendMethod = MapValueUtil.getString(dep,"SENDMETHOD");
                            String giveModeText = "";
                            if(StringUtils.isNotEmpty(sendMethod)){
                                if("0".equals(sendMethod)){
                                    detailBean.setPayModeList(Arrays.asList(RecipeConstant.PAYMODE_COD));
                                    giveModeText = "配送到家";
                                }else if("1".equals(sendMethod)){
                                    detailBean.setPayModeList(Arrays.asList(RecipeConstant.PAYMODE_TFDS));
                                }else if("2".equals(sendMethod)){
                                    detailBean.setPayModeList(Arrays.asList(RecipeConstant.PAYMODE_COD, RecipeConstant.PAYMODE_TFDS));
                                }
                            }else{
                                detailBean.setPayModeList(Arrays.asList(RecipeConstant.PAYMODE_COD, RecipeConstant.PAYMODE_TFDS));
                            }
                            detailBean.setGiveModeText(giveModeText);
                            detailBean.setSendMethod(sendMethod);
                            detailBean.setPayMethod(MapValueUtil.getString(dep,"PAYMETHOD"));
                            detailList.add(detailBean);
                        }
                        result.setObject(detailList);
                        result.setMsg("调用["+drugEpName+"]["+method+"]成功，返回供应商数量:"+detailList.size());

                        //设置样式
                        DepStyleBean styleBean = new DepStyleBean();
                        styleBean.setPriceSize(MapValueUtil.getString(resultMap, "Price_Size"));
                        styleBean.setPriceColor(MapValueUtil.getString(resultMap, "Price_Color"));
                        styleBean.setPriceFont(MapValueUtil.getString(resultMap, "Price_Font"));

                        styleBean.setSupplierSize(MapValueUtil.getString(resultMap, "Supplier_Size"));
                        styleBean.setSupplierColor(MapValueUtil.getString(resultMap, "Supplier_Color"));
                        styleBean.setSupplierFont(MapValueUtil.getString(resultMap, "Supplier_Font"));
                        styleBean.setSupplierWeight(MapValueUtil.getString(resultMap, "Supplier_Weight"));

                        styleBean.setDdzfSize(MapValueUtil.getString(resultMap, "DDZF_Size"));
                        styleBean.setDdzfColor(MapValueUtil.getString(resultMap, "DDZF_Color"));
                        styleBean.setDdzfFont(MapValueUtil.getString(resultMap, "DDZF_Font"));

                        styleBean.setHdfkSize(MapValueUtil.getString(resultMap, "HDFK_Size"));
                        styleBean.setHdfkColor(MapValueUtil.getString(resultMap, "HDFK_Color"));
                        styleBean.setHdfkFont(MapValueUtil.getString(resultMap, "HDFK_Font"));

                        styleBean.setPsdjSize(MapValueUtil.getString(resultMap, "PSDJ_Size"));
                        styleBean.setPsdjColor(MapValueUtil.getString(resultMap, "PSDJ_Color"));
                        styleBean.setPsdjFont(MapValueUtil.getString(resultMap, "PSDJ_Font"));
                        result.setStyle(styleBean);
                    }
                }else{
                    result.setMsg("调用["+drugEpName+"]["+method+"]成功");
                }
            }else{
                result.setMsg("调用["+drugEpName+"]["+method+"]失败.error:"+ MapValueUtil.getString(resultMap,"MSG"));
                result.setCode(DrugEnterpriseResult.FAIL);
            }
        }else{
            result.setMsg(drugEpName + "接口调用返回为空");
            result.setCode(DrugEnterpriseResult.FAIL);
        }
    }

    private List<Map<String,Object>> getYsqRecipeInfo(List<Integer> recipeIds, boolean sendRecipe){
        RecipeDAO recipeDAO = DAOFactory.getDAO(RecipeDAO.class);
        PatientDAO patientDAO = DAOFactory.getDAO(PatientDAO.class);
        DoctorDAO doctorDAO = DAOFactory.getDAO(DoctorDAO.class);
        DepartmentDAO departmentDAO = DAOFactory.getDAO(DepartmentDAO.class);
        OrganDAO organDAO = DAOFactory.getDAO(OrganDAO.class);
        DrugListDAO drugListDAO = DAOFactory.getDAO(DrugListDAO.class);
        RecipeDetailDAO recipeDetailDAO = DAOFactory.getDAO(RecipeDetailDAO.class);
        RecipeOrderDAO orderDAO = DAOFactory.getDAO(RecipeOrderDAO.class);

        Recipe recipe;
        RecipeOrder order = null;
        Patient patient;
        Organ organ;
        //药品信息MAP，减少DB查询
        Map<Integer,DrugList> drugListMap = new HashMap<>(10);
        List<Map<String,Object>> recipeInfoList = new ArrayList<>(recipeIds.size());
        //每个处方的json数据
        Map<String, Object> recipeMap;
        for (Integer recipeId : recipeIds) {
            recipe = recipeDAO.getByRecipeId(recipeId);
            if (null == recipe) {
                logger.error("getYsqRecipeInfo ID为" + recipeId + "的处方不存在");
                continue;
            }

            if(sendRecipe) {
                if (StringUtils.isEmpty(recipe.getOrderCode())) {
                    logger.error("getYsqRecipeInfo recipeId={}, 不存在订单编号.", recipeId);
                    continue;
                }

                order = orderDAO.getByOrderCode(recipe.getOrderCode());
                if (null == order) {
                    logger.error("getYsqRecipeInfo code为" + recipe.getOrderCode() + "的订单不存在");
                    continue;
                }
            }

            try {
                patient = patientDAO.get(recipe.getMpiid());
            } catch (Exception e) {
                patient = null;
            }
            if (null == patient) {
                logger.error("getYsqRecipeInfo ID为" + recipe.getMpiid() + "的患者不存在");
                continue;
            }

            try {
                organ = organDAO.getByOrganId(recipe.getClinicOrgan());
            } catch (Exception e) {
                organ = null;
            }
            if (null == organ) {
                logger.error("getYsqRecipeInfo ID为" + recipe.getClinicOrgan() + "的机构不存在");
                continue;
            }

            recipeMap = new HashMap<>();
            if(sendRecipe) {
                //取药方式
                if (RecipeConstant.PAYMODE_COD.equals(recipe.getPayMode())) {
                    recipeMap.put("METHOD", "0");//1：自提；0：送货上门
                } else if (RecipeConstant.PAYMODE_TFDS.equals(recipe.getPayMode())) {
                    recipeMap.put("METHOD", "1");
                } else {
                    //支持所有方式
                    recipeMap.put("METHOD", "");
                }
            }else{
                recipeMap.put("METHOD", "");
            }

            recipeMap.put("HOSCODE",organ.getOrganId().toString());
            recipeMap.put("HOSNAME",organ.getName());
            recipeMap.put("PRESCRIPTDATE", DateConversion.getDateFormatter(recipe.getSignDate(), DateConversion.DEFAULT_DATE_TIME));
            recipeMap.put("INBILLNO",recipe.getClinicOrgan()+YSQ_SPLIT+recipe.getRecipeCode());//医院处方号  医院机构?处方编号
            recipeMap.put("PATNAME",patient.getPatientName());

            //性别处理
            String sex = patient.getPatientSex();
            if (StringUtils.isNotEmpty(sex)) {
                try {
                    recipeMap.put("SEX", DictionaryController.instance().get("eh.base.dictionary.Gender").getText(sex));
                } catch (ControllerException e) {
                    logger.error("getYsqRecipeInfo 获取性别类型失败*****sex:" + sex);
                    recipeMap.put("SEX", "男");
                }
            } else {
                logger.error("getYsqRecipeInfo sex为空");
                recipeMap.put("SEX", "男");
            }

            //周岁处理
            Date birthday = patient.getBirthday();
            if(null != birthday) {
                recipeMap.put("AGE", Integer.toString(DateConversion.getAge(birthday)));
            }

            recipeMap.put("IDENTIFICATION","");//身份信息使用原始身份证号，暂定空
            recipeMap.put("TELPHONE", patient.getMobile());
            if(sendRecipe) {
                recipeMap.put("PATIENTSENDADDR", getCompleteAddress(order));
                recipeMap.put("RECEIVENAME", order.getReceiver());
                recipeMap.put("RECEIVETEL", order.getRecMobile());
                recipeMap.put("ACCAMOUNT", order.getRecipeFee().toString());
            }else{
                recipeMap.put("PATIENTSENDADDR", "");
                recipeMap.put("RECEIVENAME", patient.getPatientName());
                recipeMap.put("RECEIVETEL", patient.getMobile());
                recipeMap.put("ACCAMOUNT", recipe.getTotalMoney().toString());
            }
            recipeMap.put("ALLERGY", "");
            recipeMap.put("REMARK", StringUtils.defaultString(recipe.getMemo(),""));
            recipeMap.put("DEPT", departmentDAO.getNameById(recipe.getDepart()));
            recipeMap.put("DOCTORCODE", recipe.getDoctor().toString());
            recipeMap.put("DOCTOR", doctorDAO.getNameById(recipe.getDoctor()));
            //处理过期时间
            String validateDays = ParamUtils.getParam(ParameterConstant.KEY_RECIPE_VALIDDATE_DAYS, "14");
            Date validate = DateConversion.getDateAftXDays(recipe.getSignDate(),Integer.parseInt(validateDays));
            recipeMap.put("VALIDDATE", DateConversion.getDateFormatter(validate, DateConversion.DEFAULT_DATE_TIME));
            recipeMap.put("DIAGNOSIS", recipe.getOrganDiseaseName());
            recipeMap.put("YIBAOBILL","1");//医保处方 0：是；1：否

            List<Map<String, String>> recipeDetailList = new ArrayList<>();
            recipeMap.put("DETAILS",recipeDetailList);

            //处方详情数据
            List<Recipedetail> recipedetail = recipeDetailDAO.findByRecipeId(recipe.getRecipeId());
            if(CollectionUtils.isNotEmpty(recipedetail)){
                Map<String, String> detailMap;
                DrugList drug;
                for (Recipedetail detail : recipedetail) {
                    detailMap = new HashMap<>();
                    Integer drugId = detail.getDrugId();
                    drug = drugListMap.get(drugId);
                    if(null == drug){
                        drug = drugListDAO.get(drugId);
                        drugListMap.put(drugId,drug);
                    }

                    detailMap.put("GOODS", drugId.toString());
                    detailMap.put("NAME", drug.getSaleName());
                    detailMap.put("GNAME", drug.getDrugName());
                    detailMap.put("SPEC", drug.getDrugSpec());
                    detailMap.put("PRODUCER", drug.getProducer());
                    detailMap.put("MSUNITNO", drug.getUnit());
                    detailMap.put("BILLQTY", getFormatDouble(detail.getUseTotalDose()));
                    detailMap.put("PRC", detail.getSalePrice().toString());
                    detailMap.put("YIBAO", "1");//医保药 0：是；1：否

                    //药品使用
                    detailMap.put("DOSAGE", "");
                    detailMap.put("DOSAGENAME", getFormatDouble(detail.getUseDose())+detail.getUseDoseUnit());

                    String userRate = detail.getUsingRate();
                    detailMap.put("DISEASE", userRate);
                    if (StringUtils.isNotEmpty(userRate)) {
                        try {
                            detailMap.put("DISEASENAME", DictionaryController.instance().get("eh.cdr.dictionary.UsingRate").getText(userRate));
                        } catch (ControllerException e) {
                            logger.error("getYsqRecipeInfo 获取用药频次类型失败*****usingRate:" + userRate);
                            detailMap.put("DISEASENAME", "每日三次");
                        }
                    } else {
                        logger.error("getYsqRecipeInfo usingRate为null");
                        detailMap.put("DISEASENAME", "每日三次");
                    }

                    String usePathways = detail.getUsePathways();
                    detailMap.put("DISEASE1", usePathways);
                    if (StringUtils.isNotEmpty(usePathways)) {
                        try {
                            detailMap.put("DISEASENAME1", DictionaryController.instance().get("eh.cdr.dictionary.UsePathways").getText(usePathways));
                        } catch (ControllerException e) {
                            logger.error("getYsqRecipeInfo 获取用药途径类型失败*****usePathways:" + usePathways);
                            detailMap.put("DISEASENAME1", "口服");
                        }
                    } else {
                        logger.error("getYsqRecipeInfo usePathways为null");
                        detailMap.put("DISEASENAME1", "口服");
                    }

                    recipeDetailList.add(detailMap);
                }
            }

            recipeInfoList.add(recipeMap);
        }

        return recipeInfoList;
    }

    /**
     * 获取wsdl调用客户端
     * @param drugsEnterprise
     * @param method
     * @return
     */
    protected Call getCall(DrugsEnterprise drugsEnterprise, String method) throws Exception{
        String wsdlUrl = drugsEnterprise.getBusinessUrl();
        String nameSpaceUri = NAME_SPACE+method;
        Service s = new Service();
        Call call = null;
        try {
            call = (Call) s.createCall();
        } catch (ServiceException e) {
            logger.error("create call error. error={}",e.getMessage());
        }
        if(null != call) {
            call.setTimeout(20000);//单位毫秒
            call.setTargetEndpointAddress(new URL(wsdlUrl));
            call.setOperationName(new QName(NAME_SPACE, method));
            call.setSOAPActionURI(nameSpaceUri);
        }

        return call;
    }
}
