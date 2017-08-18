package eh.cdr.service;

import com.alibaba.fastjson.JSONObject;
import com.google.common.base.Function;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import ctd.persistence.DAOFactory;
import ctd.persistence.exception.DAOException;
import ctd.util.AppContextHolder;
import ctd.util.JSONUtils;
import ctd.util.annotation.RpcService;
import eh.base.constant.BussTypeConstant;
import eh.base.constant.SystemConstant;
import eh.base.dao.HisServiceConfigDAO;
import eh.base.dao.OrganConfigDAO;
import eh.bus.service.common.CurrentUserInfo;
import eh.bus.service.payment.DaBaiMedicalInsuranceService;
import eh.cdr.bean.*;
import eh.cdr.constant.DrugEnterpriseConstant;
import eh.cdr.constant.OrderStatusConstant;
import eh.cdr.constant.RecipeConstant;
import eh.cdr.constant.RecipeStatusConstant;
import eh.cdr.dao.RecipeDAO;
import eh.cdr.dao.RecipeDetailDAO;
import eh.cdr.dao.RecipeOrderDAO;
import eh.cdr.drugsenterprise.CommonRemoteService;
import eh.cdr.drugsenterprise.RemoteDrugEnterpriseService;
import eh.cdr.drugsenterprise.YsqRemoteService;
import eh.controller.PayController;
import eh.coupon.constant.CouponBusTypeEnum;
import eh.coupon.service.CouponService;
import eh.entity.base.HisServiceConfig;
import eh.entity.bus.pay.BusTypeEnum;
import eh.entity.cdr.DrugDistributionPrice;
import eh.entity.cdr.Recipe;
import eh.entity.cdr.RecipeOrder;
import eh.entity.mpi.Address;
import eh.entity.mpi.Patient;
import eh.mpi.dao.AddressDAO;
import eh.mpi.dao.PatientDAO;
import eh.util.CdrUtil;
import eh.utils.MapValueUtil;
import eh.utils.ValidateUtil;
import eh.utils.params.ParamUtils;
import eh.utils.params.ParameterConstant;
import eh.wxpay.constant.PayConstant;
import eh.wxpay.util.PayUtil;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.map.HashedMap;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * 处方订单管理
 * company: ngarihealth
 * author: 0184/yu_yun
 * date:2017/2/13.
 */
public class RecipeOrderService extends RecipeBaseService{

    private static final Logger logger = LoggerFactory.getLogger(RecipeOrderService.class);


    /**
     * 处方结算时创建临时订单
     * @param recipeIds
     * @param extInfo
     * @return
     */
    public RecipeOrder createBlankOrder(List<Integer> recipeIds, Map<String,String> extInfo){
        OrderCreateResult result = createOrder(recipeIds,extInfo,0);
        RecipeOrder order = null;
        if(null != result && RecipeResultBean.SUCCESS.equals(result.getCode()) &&
                null != result.getObject() && result.getObject() instanceof RecipeOrder){
            order = (RecipeOrder)result.getObject();
        }
        return order;
    }

    /**
     * 订单创建
     * @param recipeIds 合并处方单ID
     * @param extInfo
     *
        {"operMpiId":"当前操作者编码","addressId":"当前选中地址","payway":"支付方式（payway）","payMode":"处方支付方式",
        "decoctionFlag":"1(1：代煎，0：不代煎)", "gfFeeFlag":"1(1：表示需要制作费，0：不需要)", “depId”:"指定药企ID",
        "expressFee":"快递费","gysCode":"药店编码","sendMethod":"送货方式","payMethod":"支付方式"}

        ps: decoctionFlag是中药处方时设置为1，gfFeeFlag是膏方时设置为1
        gysCode, sendMethod, payMethod 字段为钥世圈字段，会在findSupportDepList接口中给出
        payMode 如果钥世圈有供应商是多种方式支持，就传0
     *
     * @param toDbFlag 0不存入数据库 ， 1存入数据库
     * @return
     */
    @RpcService
    public OrderCreateResult createOrder(List<Integer> recipeIds, Map<String,String> extInfo, Integer toDbFlag){
        logger.info("createOrder param: dbflag={}, ids={}, extInfo={}", toDbFlag, JSONUtils.toString(recipeIds), JSONUtils.toString(extInfo));
        OrderCreateResult result = new OrderCreateResult(RecipeResultBean.SUCCESS);
        if(CollectionUtils.isEmpty(recipeIds)){
            result.setCode(RecipeResultBean.FAIL);
            result.setMsg("缺少处方ID参数");
            return result;
        }

        RecipeOrderDAO orderDAO = DAOFactory.getDAO(RecipeOrderDAO.class);
        RecipeDAO recipeDAO = DAOFactory.getDAO(RecipeDAO.class);

        RecipeOrder order = new RecipeOrder();
        Integer payMode = MapValueUtil.getInteger(extInfo,"payMode");
        if(null == payMode){
            result.setCode(RecipeResultBean.FAIL);
            result.setMsg("缺少支付方式");
            return result;
        }

        //设置订单初始数据
        order.setRecipeIdList(JSONUtils.toString(recipeIds));
        String payway = MapValueUtil.getString(extInfo,"payway");
        if(1 == toDbFlag && (StringUtils.isEmpty(payway))){
            logger.error("支付信息不全 extInfo={}",JSONUtils.toString(extInfo));
            result.setCode(RecipeResultBean.FAIL);
            result.setMsg("支付信息不全");
            return result;
        }
        order.setWxPayWay(payway);
        List<Recipe> recipeList = recipeDAO.findByRecipeIds(recipeIds);
        if(CollectionUtils.isEmpty(recipeList)){
            logger.error("处方对象不存在 ids={}",JSONUtils.toString(recipeIds));
            result.setCode(RecipeResultBean.FAIL);
            result.setMsg("处方不存在");
            return result;
        }

        //指定了药企的话需要传该字段
        Integer depId = MapValueUtil.getInteger(extInfo,"depId");
        order.setEnterpriseId(depId);
        RecipePayModeSupportBean payModeSupport = setPayModeSupport(order,payMode);
        //校验处方列表是否都能进行配送
        if(RecipeResultBean.SUCCESS.equals(result.getCode())) {
            //获取需要删除的处方对象(可能已处理或者库存不足等情况的处方)
            List<Recipe> needDelList = validateRecipeList(result,recipeList,order,payMode,payModeSupport);
            //null特殊处理，表示该处方的订单已生成，可以直接支付
            if(null == needDelList){
                RecipeOrder dbOrder = orderDAO.getByOrderCode(result.getOrderCode());
                setCreateOrderResult(result,dbOrder,payModeSupport,toDbFlag);
                return result;
            }
            //过滤无法合并的处方单
            if(CollectionUtils.isNotEmpty(needDelList)){
                logger.info("createOrder delList size={} ",needDelList.size());
                recipeList.removeAll(needDelList);
                if(CollectionUtils.isEmpty(recipeList)){
                    logger.error("createOrder 需要合并的处方单size为0.");
                    result.setCode(RecipeResultBean.FAIL);
                    //当前只处理了单个处方的返回 TODO
                    result.setMsg(result.getError());
                }
            }
        }

        if(RecipeResultBean.SUCCESS.equals(result.getCode())) {
            Recipe firstRecipe = recipeList.get(0);
            //设置订单各种费用和配送地址
            setOrderFee(result,order,recipeIds,recipeList,payModeSupport,extInfo,toDbFlag);
            if(RecipeResultBean.SUCCESS.equals(result.getCode()) && 1 == toDbFlag) {
                boolean saveFlag = saveOrderToDB(order, recipeList, payMode, result, recipeDAO, orderDAO);
                if(saveFlag){
                    if(payModeSupport.isSupportMedicalInsureance()) {
                        //医保支付处理
                        //往大白发送处方信息
                        String backInfo = applyMedicalInsurancePay(order,recipeList);
                        if(StringUtils.isEmpty(backInfo)){
                            result.setMsg(ParamUtils.getParam(ParameterConstant.KEY_RECIPE_MEDICALPAY_TIP));
                        }else{
                            result.setCode(RecipeResultBean.FAIL);
                            result.setMsg("医保支付返回,"+backInfo);
                        }
                    }else if(payModeSupport.isSupportCOD() || payModeSupport.isSupportTFDS() || payModeSupport.isSupportComplex()) {
                        //货到付款 | 药店取药 处理
                        if(0 == firstRecipe.getFromflag()){
                            //医院HIS过来的处方不需要推送药企处理

                        }else if(1 == firstRecipe.getFromflag()) {
                            //平台处方先发送处方数据
                            sendRecipeAfterCreateOrder(recipeList, result, extInfo);
                        }
                    }
                }
            }
        }

        setCreateOrderResult(result,order,payModeSupport,toDbFlag);
        return result;
    }

    /**
     * 设置支付方式处理
     * @param order
     * @param payMode
     * @return
     */
    private RecipePayModeSupportBean setPayModeSupport(RecipeOrder order, Integer payMode){
        RecipePayModeSupportBean payModeSupport = new RecipePayModeSupportBean();
        if(RecipeConstant.PAYMODE_MEDICAL_INSURANCE.equals(payMode)){
            payModeSupport.setSupportMedicalInsureance(true);
            //在收到医快付支付消息返回前，该订单处于无效状态
            order.setEffective(0);
        }else if(RecipeConstant.PAYMODE_COD.equals(payMode)){
            //在收到用户确认userConfirm消息返回前，该订单处于无效状态
            payModeSupport.setSupportCOD(true);
            order.setEffective(0);
        }else if(RecipeConstant.PAYMODE_TFDS.equals(payMode)){
            //在收到用户确认userConfirm消息返回前，该订单处于无效状态
            payModeSupport.setSupportTFDS(true);
            order.setEffective(0);
        }else if(RecipeConstant.PAYMODE_COMPLEX.equals(payMode)){
            payModeSupport.setSupportComplex(true);
            order.setEffective(0);
        }else{
            payModeSupport.setSupportOnlinePay(true);
            order.setEffective(1);
        }

        return payModeSupport;
    }

    /**
     * 校验处方数据
     * @param result
     * @param recipeList
     * @param order
     * @param payMode
     * @param payModeSupport
     * @param payModeSupport
     * @return
     */
    private List<Recipe> validateRecipeList(OrderCreateResult result, List<Recipe> recipeList, RecipeOrder order,
                                            Integer payMode, RecipePayModeSupportBean payModeSupport){
        RecipeOrderDAO orderDAO = DAOFactory.getDAO(RecipeOrderDAO.class);
        RecipeDAO recipeDAO = DAOFactory.getDAO(RecipeDAO.class);
        RecipeService recipeService = AppContextHolder.getBean("eh.recipeService", RecipeService.class);

        List<Recipe> needDelList = new ArrayList<>(10);
        //TODO 多处方处理仍需要重构
        for(Recipe recipe : recipeList){
            //判断处方状态是否还能进行支付
            if(!Integer.valueOf(RecipeStatusConstant.CHECK_PASS).equals(recipe.getStatus())){
                logger.error("处方id="+recipe.getRecipeId()+"不是待处理状态。");
                if(RecipeStatusConstant.REVOKE == recipe.getStatus()){
                    result.setError("由于医生已撤销，该处方单已失效，请联系医生");
                }else {
                    result.setError("处方单已处理");
                }
                needDelList.add(recipe);
                continue;
            }

            //平台处方才需要校验配送药企
            if(1 == recipe.getFromflag()) {
                Integer depId = recipeService.supportDistributionExt(recipe.getRecipeId(), recipe.getClinicOrgan(),
                        order.getEnterpriseId(), payMode);
                if (null == depId) {
                    logger.error("处方id=" + recipe.getRecipeId() + "无法配送。");
                    result.setError("很抱歉，当前库存不足无法结算，请联系客服：" +
                            ParamUtils.getParam(ParameterConstant.KEY_CUSTOMER_TEL, SystemConstant.CUSTOMER_TEL));
                    //不能配送需要从处方列表剔除
                    needDelList.add(recipe);
                    continue;
                }else{
                    order.setEnterpriseId(depId);
                }
            }

            //查询是否存在已在其他订单里的处方
            boolean flag = orderDAO.isEffectiveOrder(recipe.getOrderCode(), payMode);
            if(flag){
                if(payModeSupport.isSupportMedicalInsureance()){
                    logger.error("处方id=" + recipe.getRecipeId() + "已经发送给医保。");
                    //再重复发送一次医快付消息
                    RecipeOrder dbOrder = orderDAO.getByOrderCode(recipe.getOrderCode());
                    String backInfo = applyMedicalInsurancePay(dbOrder,recipeList);
                    if(StringUtils.isEmpty(backInfo)){
                        result.setError(ParamUtils.getParam(ParameterConstant.KEY_RECIPE_MEDICALPAY_TIP));
                    }else{
                        result.setError("医保支付返回:"+backInfo);
                    }
                    //订单有效需要从处方列表剔除
                    needDelList.add(recipe);
                }else {
                    logger.error("处方id=" + recipe.getRecipeId() + "订单已存在。");
//                    result.setError("处方单已结算");
                    //已存在订单，则直接返回
                    //TODO 此处只考虑了单个处方支付时 重复点击支付的情况，多处方情况应当校验订单内包含的处方是否与当前合并支付的处方一致
                    result.setOrderCode(recipe.getOrderCode());
                    needDelList = null;
                    break;
                }
            } else{
                //如果该处方单没有关联订单，又是点击医保支付，则需要通过商户号查询原先的订单号关联之前的订单
                if(payModeSupport.isSupportMedicalInsureance()){
                    String _outTradeNo = recipe.getOutTradeNo();
                    if(StringUtils.isNotEmpty(_outTradeNo)) {
                        RecipeOrder _dbOrder = orderDAO.getByOutTradeNo(_outTradeNo);
                        if(null != _dbOrder) {
                            recipeDAO.updateOrderCodeByRecipeIds(Collections.singletonList(recipe.getRecipeId()),_dbOrder.getOrderCode());
                            String backInfo = applyMedicalInsurancePay(_dbOrder, recipeList);
                            if (StringUtils.isEmpty(backInfo)) {
                                result.setError(ParamUtils.getParam(ParameterConstant.KEY_RECIPE_MEDICALPAY_TIP));
                            } else {
                                result.setError("医保支付返回:" + backInfo);
                            }
                            //订单有效需要从处方列表剔除
                            needDelList.add(recipe);
                        }
                    }
                }
            }

        }

        return needDelList;
    }

    /**
     * 设置处方费用
     * @param result
     * @param order
     * @param recipeIds
     * @param recipeList
     * @param payModeSupport
     * @param extInfo
     * @param toDbFlag
     */
    private void setOrderFee(OrderCreateResult result, RecipeOrder order, List<Integer> recipeIds,
                             List<Recipe> recipeList, RecipePayModeSupportBean payModeSupport,
                             Map<String,String> extInfo, Integer toDbFlag){
        OrganConfigDAO organConfigDAO = DAOFactory.getDAO(OrganConfigDAO.class);
        RecipeDetailDAO recipeDetailDAO = DAOFactory.getDAO(RecipeDetailDAO.class);

        String operMpiId = MapValueUtil.getString(extInfo,"operMpiId");//当前操作人的编码，用于获取地址列表信息等
        Recipe firstRecipe = recipeList.get(0);
        //TODO 暂时还是设置成处方单的患者，不然用户历史处方列表不好查找
        order.setMpiId(firstRecipe.getMpiid());
        order.setOrganId(firstRecipe.getClinicOrgan());
        order.setOrderCode(this.getOrderCode(order.getMpiId()));
        order.setStatus(OrderStatusConstant.READY_PAY);

        //设置挂号费
        if(payModeSupport.isSupportMedicalInsureance() || payModeSupport.isSupportCOD()
                || payModeSupport.isSupportTFDS() || payModeSupport.isSupportComplex()){
            order.setRegisterFee(BigDecimal.ZERO);
        }else {
            BigDecimal registerFee = organConfigDAO.getRecipeRegisterByOrganId(firstRecipe.getClinicOrgan());
            if (null != registerFee) {
                order.setRegisterFee(registerFee);
            } else {
                order.setRegisterFee(new BigDecimal(ParamUtils.getParam(ParameterConstant.KEY_RECIPE_REGISTER_FEE, "0")));
            }
        }
        //设置处方总费用
        BigDecimal recipeFee = BigDecimal.ZERO;
        for(Recipe recipe : recipeList){
            if(null != recipe){
                if(null != recipe.getTotalMoney()) {
                    recipeFee = recipeFee.add(recipe.getTotalMoney());
                }
            }
        }
        order.setRecipeFee(recipeFee);

        //中药表示待煎费，膏方代表制作费
        BigDecimal otherFee = BigDecimal.ZERO;
        //设置订单代煎费
        Integer totalCopyNum = 0;
        boolean needCalDecFee = false;
        Integer decoctionFlag = MapValueUtil.getInteger(extInfo, "decoctionFlag");
        //1表示待煎
        if(Integer.valueOf(1).equals(decoctionFlag)){
            //待煎单价(代煎费 -1不支持代煎 大于等于0时为代煎费)
            BigDecimal recipeDecoctionPrice = organConfigDAO.getDecoctionPriceByOrganId(order.getOrganId());
            //根据机构获取代煎费
            order.setDecoctionUnitPrice(null!=recipeDecoctionPrice?recipeDecoctionPrice:BigDecimal.valueOf(-1));
            needCalDecFee = (order.getDecoctionUnitPrice().compareTo(BigDecimal.ZERO)==1)?true:false;
        }

        //设置膏方制作费
        Integer gfFeeFlag = MapValueUtil.getInteger(extInfo, "gfFeeFlag");
        //1表示膏方制作费
        if(Integer.valueOf(1).equals(gfFeeFlag)){
            //制作单价
            BigDecimal gfFeeUnitPrice = organConfigDAO.getRecipeCreamPriceByOrganId(order.getOrganId());
            if(null == gfFeeUnitPrice){gfFeeUnitPrice = BigDecimal.ZERO;}
            order.setDecoctionUnitPrice(gfFeeUnitPrice);
            //存在制作单价且大于0
            if(gfFeeUnitPrice.compareTo(BigDecimal.ZERO)==1){
                Double totalDose = recipeDetailDAO.getUseTotalDoseByRecipeIds(recipeIds);
                otherFee = gfFeeUnitPrice.multiply(BigDecimal.valueOf(totalDose));
            }
        }

        for(Recipe recipe : recipeList){
            if(RecipeConstant.RECIPETYPE_TCM.equals(recipe.getRecipeType())){
                totalCopyNum = totalCopyNum + recipe.getCopyNum();
                if(needCalDecFee) {
                    //代煎费等于剂数乘以代煎单价
                    otherFee = otherFee.add(order.getDecoctionUnitPrice().multiply(BigDecimal.valueOf(recipe.getCopyNum())));
                }
            }
        }
        order.setCopyNum(totalCopyNum);
        order.setDecoctionFee(otherFee);
        //药店取药不需要地址信息
        if(payModeSupport.isSupportTFDS()){
            order.setAddressCanSend(true);
            order.setExpressFee(BigDecimal.ZERO);
        }else{
            //设置运费
            AddressDAO addressDAO = DAOFactory.getDAO(AddressDAO.class);
            String operAddressId = MapValueUtil.getString(extInfo,"addressId");
            Address address = null;
            if(StringUtils.isNotEmpty(operAddressId)){
                address = addressDAO.get(Integer.parseInt(operAddressId));
            }else{
                address = addressDAO.getLastAddressByMpiId(operMpiId);
            }

            order.setAddressCanSend(false);//此字段前端已不使用
            order.setExpressFee(BigDecimal.ZERO);
            if(null != address){
                //可以在参数里传递快递费
                String paramExpressFee = MapValueUtil.getString(extInfo,"expressFee");
                //保存地址,费用信息
                BigDecimal expressFee = null;
                if(payModeSupport.isSupportMedicalInsureance()){
                    expressFee = BigDecimal.ZERO;
                }else {
                    if(StringUtils.isNotEmpty(paramExpressFee)){
                        expressFee = new BigDecimal(paramExpressFee);
                    }else {
                        expressFee = getExpressFee(order.getEnterpriseId(), address.getAddress3());
                    }
                }

                order.setExpressFee(expressFee);
                order.setAddress(address);
                order.setReceiver(address.getReceiver());
                order.setRecMobile(address.getRecMobile());
                order.setRecTel(address.getRecTel());
                order.setZipCode(address.getZipCode());
                order.setAddressID(address.getAddressId());
                order.setAddress1(address.getAddress1());
                order.setAddress2(address.getAddress2());
                order.setAddress3(address.getAddress3());
                order.setAddress4(address.getAddress4());

                try {
                    //校验地址是否可以配送
                    EnterpriseAddressService enterpriseAddressService = AppContextHolder.getBean("enterpriseAddressService", EnterpriseAddressService.class);
                    int flag = enterpriseAddressService.allAddressCanSendForOrder(order.getEnterpriseId(),address.getAddress1(),address.getAddress2(),address.getAddress3());
                    if(0 == flag){
                        order.setAddressCanSend(true);
                    }else{
                        if(1 == toDbFlag && (payModeSupport.isSupportMedicalInsureance() || payModeSupport.isSupportOnlinePay())) {
                            //只有需要真正保存订单时才提示
                            result.setCode(RecipeResultBean.FAIL);
                            result.setMsg("该地址无法配送");
                        }
                    }
                } catch (Exception e) {
                    result.setCode(RecipeResultBean.FAIL);
                    result.setMsg(e.getMessage());
                }
            }else{
                //只有需要真正保存订单时才提示
                if(1 == toDbFlag){
                    result.setCode(RecipeResultBean.NO_ADDRESS);
                    result.setMsg("没有配送地址");
                }
            }
        }
        order.setTotalFee(countOrderTotalFee(order));
        order.setActualPrice(order.getTotalFee().doubleValue());
    }

    private void setCreateOrderResult(OrderCreateResult result, RecipeOrder order, RecipePayModeSupportBean payModeSupport,
                                      Integer toDbFlag){
        if(payModeSupport.isSupportMedicalInsureance()) {
            result.setCouponType(null);
        }else if(payModeSupport.isSupportCOD()){
            result.setCouponType(null);
        }else if(payModeSupport.isSupportTFDS()){
            result.setCouponType(null);
        }else if(payModeSupport.isSupportComplex()){
            result.setCouponType(null);
        }else{
            result.setCouponType(CouponBusTypeEnum.COUPON_BUSTYPE_RECIPE_HOME_PAYONLINE.getId());
        }
        result.setObject(order);
        if(RecipeResultBean.SUCCESS.equals(result.getCode()) && 1==toDbFlag && null != order.getOrderId()) {
            result.setOrderCode(order.getOrderCode());
            result.setBusId(order.getOrderId());
        }

        logger.info("createOrder finish. result={}",JSONUtils.toString(result));
    }

    /**
     * 创建订单后发送处方给药企及后续处理
     * @param recipeList
     * @param result
     * @return
     */
    private OrderCreateResult sendRecipeAfterCreateOrder(List<Recipe> recipeList, OrderCreateResult result, Map<String,String> extInfo){
        //TODO 暂时支持单处方处理
        Recipe firstRecipe = recipeList.get(0);
        RemoteDrugEnterpriseService service = AppContextHolder.getBean("eh.remoteDrugService", RemoteDrugEnterpriseService.class);
        DrugEnterpriseResult _result = service.pushSingleRecipeInfo(firstRecipe.getRecipeId());
        if (RecipeResultBean.SUCCESS.equals(_result.getCode())) {
            //钥世圈要求需要跳转页面，组装url
            if (DrugEnterpriseConstant.COMPANY_YSQ.equals(_result.getDrugsEnterprise().getAccount())) {
                String ysqUrl = ParamUtils.getParam(ParameterConstant.KEY_YSQ_SKIP_URL);
                if (StringUtils.isNotEmpty(ysqUrl)) {
                    PatientDAO patientDAO = DAOFactory.getDAO(PatientDAO.class);
                    Patient patient = null;
                    if (StringUtils.isNotEmpty(firstRecipe.getMpiid())) {
                        patient = patientDAO.getPatientByMpiId(firstRecipe.getMpiid());
                    }
                    if (null == patient) {
                        result.setCode(RecipeResultBean.FAIL);
                        result.setMsg("患者不存在");
                    } else {
                        String appid = "";
                        if (null != CurrentUserInfo.getSimpleWxAccount()) {
                            appid = CurrentUserInfo.getSimpleWxAccount().getAppId();
                        }

                        /*Map<String, Object> ysqParamMap = new HashedMap();
                        ysqParamMap.put("mobile", patient.getMobile());
                        ysqParamMap.put("name", patient.getPatientName());
                        List<Map<String, String>> ysqCards = new ArrayList<>(1);
                        Map<String, String> ysqSubParamMap = new HashedMap();
                        ysqSubParamMap.put("card_name", patient.getPatientName());
                        ysqSubParamMap.put("id_card", "");
                        ysqSubParamMap.put("card_mobile", patient.getMobile());
                        ysqSubParamMap.put("card_gender", "");
                        ysqSubParamMap.put("card_no", "");

                        ysqCards.add(ysqSubParamMap);
                        ysqParamMap.put("cards", ysqCards);*/

                        ysqUrl = ysqUrl + "PreTitle/Details?appid=" + appid + "&inbillno=" +
                                firstRecipe.getClinicOrgan() + YsqRemoteService.YSQ_SPLIT + firstRecipe.getRecipeCode()
                                +"&gysCode="+MapValueUtil.getString(extInfo, "gysCode")
                                +"&sendMethod="+MapValueUtil.getString(extInfo, "sendMethod")+"&payMethod="+MapValueUtil.getString(extInfo, "payMethod");
                        result.setMsg(ysqUrl);
                    }
                }
            }
        }else{
            result.setCode(RecipeResultBean.FAIL);
            result.setMsg("推送药企失败");
            result.setError(_result.getMsg());
        }

        return result;
    }

    /**
     * 保存订单
     * @param order
     * @param recipeList
     * @param result
     * @param recipeDAO
     * @param orderDAO
     * @return
     */
    private boolean saveOrderToDB(RecipeOrder order, List<Recipe> recipeList, Integer payMode,
                                  OrderCreateResult result,RecipeDAO recipeDAO, RecipeOrderDAO orderDAO){
        List<Integer> recipeIds = FluentIterable.from(recipeList).transform(new Function<Recipe, Integer>() {
            @Override
            public Integer apply(Recipe recipe) {
                return recipe.getRecipeId();
            }
        }).toList();
        boolean saveFlag = true;
        try {
            createOrderToDB(order, recipeIds, orderDAO, recipeDAO);
        } catch (DAOException e) {
            logger.error("createOrder orderCode={}, error={}. ", order.getOrderCode(), e.getMessage());
            saveFlag = false;
        } finally {
            //如果小概率造成orderCode重复，则修改并重试
            if (!saveFlag) {
                try {
                    order.setOrderCode(getOrderCode(order.getMpiId()));
                    createOrderToDB(order, recipeIds, orderDAO, recipeDAO);
                    saveFlag = true;
                } catch (DAOException e) {
                    logger.error("createOrder again orderCode={}, error={}. ", order.getOrderCode(), e.getMessage());
                    saveFlag = false;
                    result.setCode(RecipeResultBean.FAIL);
                    result.setMsg("保存订单系统错误");
                }
            }
        }

        if(saveFlag){
            //支付处理
            Map<String, Object> recipeInfo = new HashMap<>();
            if(RecipeConstant.PAYMODE_COMPLEX.equals(payMode)){
                recipeInfo.put("payMode", null);
            }else {
                recipeInfo.put("payMode", payMode);
            }
            recipeInfo.put("payFlag", PayConstant.PAY_FLAG_NOT_PAY);
            recipeInfo.put("enterpriseId", order.getEnterpriseId());
            //更新处方信息
            this.updateRecipeInfo(false,result,recipeIds,recipeInfo);
        }

        return saveFlag;
    }

    /**
     * 医保支付请求数据
     * @param order
     * @param recipeList
     * @return
     */
    private String applyMedicalInsurancePay(RecipeOrder order, List<Recipe> recipeList){
        logger.info("applyMedicalInsurancePayForRecipe start, params: orderId[{}],recipeIds={}", order.getOrderId(), order.getRecipeIdList());
        if(CollectionUtils.isEmpty(recipeList)){
            return "处方对象为空";
        }

        String backInfo = "";
        boolean flag = true;
        try{
            Recipe recipe = recipeList.get(0);
            if(null != recipe) {
                flag = judgeIsSupportMedicalInsurance(recipe.getMpiid(),recipe.getClinicOrgan());
                if(flag) {
                    RecipeDAO recipeDAO = DAOFactory.getDAO(RecipeDAO.class);
                    Patient patient = DAOFactory.getDAO(PatientDAO.class).get(recipe.getMpiid());
                    String outTradeNo = (StringUtils.isEmpty(order.getOutTradeNo()))?BusTypeEnum.RECIPE.getApplyNo():order.getOutTradeNo();
                    HisServiceConfig hisServiceConfig = DAOFactory.getDAO(HisServiceConfigDAO.class).getByOrganId(recipe.getClinicOrgan());
                    Map<String, Object> httpRequestParam = Maps.newHashMap();
                    httpRequestParam.put("mrn", recipe.getPatientID());
                    httpRequestParam.put("id_card_no", patient.getIdcard());
                    httpRequestParam.put("cfhs", new String[]{recipe.getRecipeCode()});
                    httpRequestParam.put("hospital_code", hisServiceConfig.getYkfPlatHospitalCode());
                    httpRequestParam.put("partner_trade_no", outTradeNo);
                    httpRequestParam.put("callback_url", PayUtil.getNotify_domain() + PayController.DA_BAI_ASYNC_NOTIFY_URL);
                    httpRequestParam.put("need_app_notify", "1");
                    httpRequestParam.put("is_show_result", "1");
                    DaBaiMedicalInsuranceService medicalInsuranceService = AppContextHolder.getBean("medicalInsuranceService", DaBaiMedicalInsuranceService.class);
                    DaBaiMedicalInsuranceService.PayResult pr = medicalInsuranceService.applyDaBaiPay(httpRequestParam);

                    Map<String, Object> orderInfo = new HashMap<>();
                    orderInfo.put("outTradeNo", outTradeNo);
                    if ("0".equals(pr.getCode())) {
                        String tradeNo = pr.getData().getTrade_no();
                        orderInfo.put("tradeNo", tradeNo);
                        RecipeResultBean resultBean = this.updateOrderInfo(order.getOrderCode(), orderInfo, null);
                        if(RecipeResultBean.FAIL.equals(resultBean.getCode())){
                            backInfo = "订单更新失败";
                        }
                        if(StringUtils.isEmpty(backInfo)){
                            //订单更新成功，更新处方单字段，用于 医保支付-自费支付-医保支付 过程时寻找之前的订单
                            boolean recipeSave = recipeDAO.updateRecipeInfoByRecipeId(recipe.getRecipeId(), ImmutableMap.of("outTradeNo", outTradeNo));
                            if(!recipeSave){
                                backInfo = "处方单更新失败";
                            }
                        }
                    }else if("50011".equals(pr.getCode())){
                        // 50011  合作者交易号：[xxx]已存在
                        backInfo = "";
                    }else{
                        backInfo = pr.getMessage();
                    }
                }else{
                    backInfo = "医院配置无法进行医保支付";
                }
            }else{
                backInfo = "处方对象不存在";
            }
        }catch (Exception e){
            logger.error("applyMedicalInsurancePay error, orderId[{}], errorMessage[{}], stackTrace[{}]", order.getOrderId(), e.getMessage(), JSONObject.toJSONString(e.getStackTrace()));
            backInfo = "医保接口异常";
        }
        return backInfo;
    }

    /**
     * 判断能否进行医保支付
     * @param mpiId
     * @param organId
     * @return
     */
    private boolean judgeIsSupportMedicalInsurance(String mpiId, Integer organId) {
        HisServiceConfig hisServiceConfig = DAOFactory.getDAO(HisServiceConfigDAO.class).getByOrganId(organId);
        Patient patient = DAOFactory.getDAO(PatientDAO.class).get(mpiId);
        if(ValidateUtil.blankString(patient.getPatientType()) || String.valueOf(PayConstant.PAY_TYPE_SELF_FINANCED).equals(patient.getPatientType())){
            logger.info("judgeIsSupportMedicalInsurance patient not support, mpiId[{}]", patient.getMpiId());
            return false;
        }
        if(ValidateUtil.isNotTrue(hisServiceConfig.getSupportMedicalInsurance())) {
            logger.info("judgeIsSupportMedicalInsurance hisServiceConfig not support, id[{}]", hisServiceConfig.getId());
            return false;
        }
        return true;
    }

    /**
     * 根据订单编号取消订单
     * @param orderCode
     * @param status
     * @return
     */
    @RpcService
    public RecipeResultBean cancelOrderByCode(String orderCode, Integer status){
        RecipeResultBean result = RecipeResultBean.getSuccess();
        if(StringUtils.isEmpty(orderCode) || null == status){
            result.setCode(RecipeResultBean.FAIL);
            result.setError("缺少参数");
        }

        if(RecipeResultBean.SUCCESS.equals(result.getCode())) {
            result = cancelOrder(DAOFactory.getDAO(RecipeOrderDAO.class).getByOrderCode(orderCode), status);
        }

        return result;
    }

    /**
     * 根据处方单号取消订单
     * @param recipeId
     * @param status
     * @return
     */
    @RpcService
    public RecipeResultBean cancelOrderByRecipeId(Integer recipeId, Integer status){
        RecipeResultBean result = RecipeResultBean.getSuccess();
        if(null == recipeId || null == status){
            result.setCode(RecipeResultBean.FAIL);
            result.setError("缺少参数");
        }

        if(RecipeResultBean.SUCCESS.equals(result.getCode())) {
            result = cancelOrder(DAOFactory.getDAO(RecipeOrderDAO.class).getOrderByRecipeId(recipeId), status);
        }

        return result;
    }

    /**
     *
     * @param orderId
     * @param status
     * @return
     */
    @RpcService
    public RecipeResultBean cancelOrderById(Integer orderId, Integer status){
        RecipeResultBean result = RecipeResultBean.getSuccess();
        if(null == orderId || null == status){
            result.setCode(RecipeResultBean.FAIL);
            result.setError("缺少参数");
        }

        if(RecipeResultBean.SUCCESS.equals(result.getCode())) {
            result = cancelOrder(DAOFactory.getDAO(RecipeOrderDAO.class).get(orderId), status);
        }

        return result;
    }

    /**
     * 取消订单
     * @param order
     * @return
     */
    public RecipeResultBean cancelOrder(RecipeOrder order, Integer status){
        RecipeResultBean result = RecipeResultBean.getSuccess();
        if(null == order || null == status){
            result.setCode(RecipeResultBean.FAIL);
            result.setError("缺少参数");
        }

        if(RecipeResultBean.SUCCESS.equals(result.getCode())){
            CouponService couponService = AppContextHolder.getBean("couponService", CouponService.class);

            Map<String,Object> orderAttrMap = new HashMap<>();
            orderAttrMap.put("effective", 0);
            orderAttrMap.put("status", status);
//            orderAttrMap.put("finishTime", Calendar.getInstance().getTime());

            if(null != order) {
                //解锁优惠券
                if (isUsefulCoupon(order.getCouponId())) {
                    try {
                        couponService.unlockCouponById(order.getCouponId());
                        orderAttrMap.put("couponId", null);
                    } catch (Exception e) {
                        logger.error("cancelOrder unlock coupon error. couponId={}, error={}", order.getCouponId(), e.getMessage());
                    }
                }
                this.updateOrderInfo(order.getOrderCode(),orderAttrMap,result);

                if(status.equals(OrderStatusConstant.CANCEL_MANUAL)){
                    //订单手动取消，处方单可以进行重新支付
                    //更新处方的orderCode
                    RecipeDAO recipeDAO = DAOFactory.getDAO(RecipeDAO.class);
                    recipeDAO.updateOrderCodeToNullByOrderCode(order.getOrderCode());
                }
            }
        }

        return result;
    }

    @RpcService
    public RecipeResultBean getOrderDetailById(Integer orderId){
        RecipeResultBean result = RecipeResultBean.getSuccess();
        if(null == orderId){
            result.setCode(RecipeResultBean.FAIL);
            result.setMsg("缺少参数");
        }

        RecipeOrderDAO orderDAO = DAOFactory.getDAO(RecipeOrderDAO.class);
        RecipeDAO recipeDAO = DAOFactory.getDAO(RecipeDAO.class);
        PatientDAO patientDAO = DAOFactory.getDAO(PatientDAO.class);
        RecipeService recipeService = AppContextHolder.getBean("eh.recipeService", RecipeService.class);
        RemoteDrugEnterpriseService remoteDrugEnterpriseService = AppContextHolder.getBean("eh.remoteDrugService", RemoteDrugEnterpriseService.class);
        CommonRemoteService commonRemoteService = AppContextHolder.getBean("commonRemoteService", CommonRemoteService.class);

        RecipeOrder order = orderDAO.get(orderId);
        if(null != order) {
            List<PatientRecipeBean> patientRecipeBeanList = new ArrayList<>(10);
            List<Recipe> recipeList = null;
            if(1 == order.getEffective()) {
                recipeList = recipeDAO.findRecipeListByOrderCode(order.getOrderCode());
                if(CollectionUtils.isEmpty(recipeList) && StringUtils.isNotEmpty(order.getRecipeIdList())) {
                    //如果没有数据，则使用RecipeIdList字段
                    List<Integer> recipeIdList = JSONUtils.parse(order.getRecipeIdList(),List.class);
                    if(CollectionUtils.isNotEmpty(recipeIdList)){
                        recipeList = recipeDAO.findByRecipeIds(recipeIdList);
                    }
                }
            }else{
                //如果是已取消的单子，则需要从order表的RecipeIdList字段取历史处方单
                if(StringUtils.isNotEmpty(order.getRecipeIdList())){
                    List<Integer> recipeIdList = JSONUtils.parse(order.getRecipeIdList(), List.class);
                    if (CollectionUtils.isNotEmpty(recipeIdList)) {
                        recipeList = recipeDAO.findByRecipeIds(recipeIdList);
                    }
                }
            }

            Map<Integer,String> enterpriseAccountMap = new HashMap<>();
            if(CollectionUtils.isNotEmpty(recipeList)){
                //设置地址，先取处方单address4的值，没有则取订单地址
                if(StringUtils.isNotEmpty(recipeList.get(0).getAddress4())){
                    order.setCompleteAddress(recipeList.get(0).getAddress4());
                }else{
                    order.setCompleteAddress(commonRemoteService.getCompleteAddress(order));
                }

                RecipeDetailDAO detailDAO = DAOFactory.getDAO(RecipeDetailDAO.class);
                PatientRecipeBean prb;
                for(Recipe recipe : recipeList){
                    prb = new PatientRecipeBean();
                    prb.setRecipeId(recipe.getRecipeId());
                    prb.setOrganDiseaseName(recipe.getOrganDiseaseName());
                    prb.setMpiId(recipe.getMpiid());
                    prb.setSignDate(recipe.getSignDate());
                    prb.setPatientName(patientDAO.getNameByMpiId(recipe.getMpiid()));
                    prb.setStatusCode(recipe.getStatus());
                    prb.setPayMode(recipe.getPayMode());
                    prb.setRecipeType(recipe.getRecipeType());
                    //药品详情
                    prb.setRecipeDetail(detailDAO.findByRecipeId(recipe.getRecipeId()));
                    if (RecipeStatusConstant.CHECK_PASS == recipe.getStatus()
                            && OrderStatusConstant.READY_PAY.equals(order.getStatus())) {
                        prb.setRecipeSurplusHours(recipeService.getRecipeSurplusHours(recipe.getSignDate()));
                    }
                    patientRecipeBeanList.add(prb);

                    if(1 == order.getEffective()){
                        String account = enterpriseAccountMap.get(order.getEnterpriseId());
                        if(StringUtils.isEmpty(account)){
                            account = remoteDrugEnterpriseService.getDepAccount(order.getEnterpriseId());
                            enterpriseAccountMap.put(order.getEnterpriseId(),account);
                        }
                        //钥世圈处理
                        if(DrugEnterpriseConstant.COMPANY_YSQ.equals(account)){
                            //前端在code=1的情况下会去判断msg是否为空，不为空则进行页面跳转，所以此处成功情况下msg不要赋值
                            result.setMsg(remoteDrugEnterpriseService.getYsqOrderInfoUrl(recipe));
                        }
                    }
                }
            }
            order.setList(patientRecipeBeanList);
            result.setObject(order);

            // 支付完成后跳转到订单详情页需要加挂号费服务费可配置
            result.setExt(CdrUtil.getParamFromOgainConfig(order));
        }else{
            result.setCode(RecipeResultBean.FAIL);
            result.setMsg("不存在ID为"+orderId+"的订单");
        }

        // 订单详情页需要将页面属性字段做成可配置的
        result.setExt(CdrUtil.getParamFromOgainConfig(order));
        return result;
    }

    /**
     * 获取订单详情
     * @param orderCoe
     * @return
     */
    @RpcService
    public RecipeResultBean getOrderDetail(String orderCoe){
        RecipeOrderDAO orderDAO = DAOFactory.getDAO(RecipeOrderDAO.class);
        RecipeOrder order = orderDAO.getByOrderCode(orderCoe);
        RecipeResultBean result = this.getOrderDetailById(order.getOrderId());
        return result;
    }

    /**
     * 获取运费
     * @param enterpriseId
     * @param address
     * @return
     */
    private BigDecimal getExpressFee(Integer enterpriseId, String address){
        if(null == enterpriseId || StringUtils.isEmpty(address)){
            return BigDecimal.ZERO;
        }
        DrugDistributionPriceService priceService = AppContextHolder.getBean("eh.drugDistributionPriceService", DrugDistributionPriceService.class);
        DrugDistributionPrice expressFee = priceService.getDistributionPriceByEnterpriseIdAndAddrArea(enterpriseId,address);
        if(null != expressFee){
           return expressFee.getDistributionPrice();
        }

        return BigDecimal.ZERO;
    }

    /**
     * 订单支付完成后调用 (包括支付完成和退款都会调用)
     * @param orderCode
     * @param payFlag
     * @return
     */
    @RpcService
    public RecipeResultBean finishOrderPay(String orderCode, int payFlag, Integer payMode){
        if(null == payMode){
            payMode = RecipeConstant.PAYMODE_ONLINE;
        }
        return finishOrderPayImpl(orderCode,payFlag,payMode);
    }

    /**
     * 线下支付模式调用
     * @param orderCode
     * @return
     */
    @RpcService
    public RecipeResultBean finishOrderPayWithoutPay(String orderCode, Integer payMode){
       return finishOrderPayImpl(orderCode,PayConstant.PAY_FLAG_NOT_PAY,payMode);
    }

    public RecipeResultBean finishOrderPayImpl(String orderCode, int payFlag, Integer payMode){
        logger.info("finishOrderPayImpl is get! orderCode={}", orderCode);
        RecipeResultBean result = RecipeResultBean.getSuccess();

        if(RecipeResultBean.SUCCESS.equals(result.getCode())){
            Map<String, Object> attrMap = new HashMap<>();
            attrMap.put("payFlag", payFlag);
            if(PayConstant.PAY_FLAG_PAY_SUCCESS == payFlag) {
                attrMap.put("payTime", Calendar.getInstance().getTime());
                attrMap.put("status", OrderStatusConstant.READY_SEND);
                attrMap.put("effective", 1);
            }else if(PayConstant.PAY_FLAG_NOT_PAY == payFlag){
                if(RecipeConstant.PAYMODE_COD.equals(payMode) || RecipeConstant.PAYMODE_TFDS.equals(payMode)) {
                    attrMap.put("effective", 1);
                    attrMap.put("status", OrderStatusConstant.READY_CHECK);
                }
            }
            this.updateOrderInfo(orderCode,attrMap,result);
        }

        //处理处方单相关
        if(RecipeResultBean.SUCCESS.equals(result.getCode())){
            RecipeDAO recipeDAO = DAOFactory.getDAO(RecipeDAO.class);

            Map<String, Object> recipeInfo = new HashMap<>();
            recipeInfo.put("payFlag", payFlag);
            recipeInfo.put("payMode", payMode);
            List<Integer> recipeIds = recipeDAO.findRecipeIdsByOrderCode(orderCode);
            this.updateRecipeInfo(true,result,recipeIds,recipeInfo);
        }

        return result;
    }

    /**
     * 完成订单
     * @param orderCode
     * @param payMode
     * @return
     */
    @RpcService
    public RecipeResultBean finishOrder(String orderCode, Integer payMode, Map<String, Object> orderAttr){
        RecipeResultBean result = RecipeResultBean.getSuccess();
        if(StringUtils.isEmpty(orderCode)){
            result.setCode(RecipeResultBean.FAIL);
            result.setError("缺少参数");
        }

        if(RecipeResultBean.SUCCESS.equals(result.getCode())){
            Map<String, Object> attrMap = new HashMap<>();
            attrMap.put("effective", 1);
            attrMap.put("payFlag", PayConstant.PAY_FLAG_PAY_SUCCESS);
            if(RecipeConstant.PAYMODE_COD.equals(payMode) || RecipeConstant.PAYMODE_TFDS.equals(payMode)){
                attrMap.put("payTime", Calendar.getInstance().getTime());
            }
            attrMap.put("finishTime", Calendar.getInstance().getTime());
            attrMap.put("status", OrderStatusConstant.FINISH);
            if(null != orderAttr) {
                attrMap.putAll(orderAttr);
            }
            this.updateOrderInfo(orderCode,attrMap,result);
        }

        return result;
    }

    /**
     * 从微信模板消息跳转时 先获取一下是否需要跳转第三方地址
     * @return
     */
    @RpcService
    public String getThirdUrl(Integer recipeId){
        String thirdUrl = "";
        if(null == recipeId){
            return thirdUrl;
        }
        RecipeDAO recipeDAO = DAOFactory.getDAO(RecipeDAO.class);
        RecipeOrderDAO recipeOrderDAO = DAOFactory.getDAO(RecipeOrderDAO.class);
        RemoteDrugEnterpriseService remoteDrugEnterpriseService = AppContextHolder.getBean("eh.remoteDrugService", RemoteDrugEnterpriseService.class);

        Recipe recipe = recipeDAO.get(recipeId);
        if(null != recipe){
            RecipeOrder order = recipeOrderDAO.getOrderByRecipeId(recipeId);
            if(null == order){
                return thirdUrl;
            }

            //钥世圈处理
            if(DrugEnterpriseConstant.COMPANY_YSQ.equals(remoteDrugEnterpriseService.getDepAccount(order.getEnterpriseId()))){
                thirdUrl = remoteDrugEnterpriseService.getYsqOrderInfoUrl(recipe);
            }
        }
        return thirdUrl;
    }

    /**
     * 根据处方单ID获取订单编号
     * @param recipeId
     * @return
     */
    public String getOrderCodeByRecipeId(Integer recipeId){
        if(null == recipeId){
            return null;
        }

        RecipeOrderDAO recipeOrderDAO = DAOFactory.getDAO(RecipeOrderDAO.class);
        return recipeOrderDAO.getOrderCodeByRecipeId(recipeId);
    }

    /**
     * 业务类型1位+时间戳后10位+随机码4位
     * @return
     */
    private String getOrderCode(String mpiId){
        StringBuilder orderCode = new StringBuilder();
        orderCode.append(BussTypeConstant.RECIPE);
        String time = Long.toString(Calendar.getInstance().getTimeInMillis());
        orderCode.append(time.substring(time.length()-10));
        orderCode.append(new Random().nextInt(9000)+1000);
        return orderCode.toString();
    }

    /**
     * 获取处方总额(未优惠前)
     * @param order
     * @return
     */
    public BigDecimal countOrderTotalFee(RecipeOrder order){
        return countOrderTotalFeeWithCoupon(null,order);
    }

    /**
     * 获取带优惠金额的处方总额
     * @param recipeFee  优惠后的处方金额
     * @param order
     * @return
     */
    public BigDecimal countOrderTotalFeeWithCoupon(BigDecimal recipeFee, RecipeOrder order){
        BigDecimal full = BigDecimal.ZERO;

        //处方费用
        if(null != recipeFee){
            full = full.add(recipeFee);
        }else{
            full = full.add(order.getRecipeFee());
        }

        //配送费
        if(null != order.getExpressFee()){
            full = full.add(order.getExpressFee());
        }

        //挂号费
        if(null != order.getRegisterFee()){
            full = full.add(order.getRegisterFee());
        }

        //代煎费
        if(null != order.getDecoctionFee()){
            full = full.add(order.getDecoctionFee());
        }

        return full.divide(BigDecimal.ONE, 3, RoundingMode.UP);
    }

    /**
     * 保存订单并修改处方所属订单
     * @param order
     * @param recipeList
     * @param orderDAO
     * @param recipeDAO
     * @throws DAOException
     */
    private Integer createOrderToDB(RecipeOrder order, List<Integer> recipeIds,
                                    RecipeOrderDAO orderDAO, RecipeDAO recipeDAO) throws DAOException{
        order = orderDAO.save(order);
        if(null != order.getOrderId()){
            recipeDAO.updateOrderCodeByRecipeIds(recipeIds,order.getOrderCode());
        }

        return order.getOrderId();
    }

    /**
     * 更新订单信息
     * @param orderCode
     * @param attrMap
     * @param result
     * @return
     */
    public RecipeResultBean updateOrderInfo(String orderCode, Map<String,?> attrMap, RecipeResultBean result){
        if(null == result){
            result = RecipeResultBean.getSuccess();
        }

        if(StringUtils.isEmpty(orderCode) || null == attrMap){
            result.setCode(RecipeResultBean.FAIL);
            result.setError("缺少参数");
            return result;
        }

        RecipeOrderDAO orderDAO = DAOFactory.getDAO(RecipeOrderDAO.class);

        try {
            boolean flag = orderDAO.updateByOrdeCode(orderCode, attrMap);
            if(flag){
//                result.setMsg(SystemConstant.SUCCESS);
            }else {
                result.setCode(RecipeResultBean.FAIL);
                result.setError("订单更新失败");
            }
        } catch (Exception e) {
            result.setCode(RecipeResultBean.FAIL);
            result.setError("订单更新失败,"+e.getMessage());
        }

        return result;
    }

    /**
     * 根据订单编号更新处方信息
     * @param saveFlag
     * @param orderCode
     * @param result
     * @param recipeInfo
     * @return
     */
    private RecipeResultBean updateRecipeInfo(boolean saveFlag, RecipeResultBean result,
                                              List<Integer> recipeIds, Map<String, Object> recipeInfo){
        if(null == result){
            result = RecipeResultBean.getSuccess();
        }
        if(CollectionUtils.isNotEmpty(recipeIds)) {
            RecipeService recipeService = AppContextHolder.getBean("eh.recipeService", RecipeService.class);
            RecipeResultBean _resultBean;
            for(Integer recipeId : recipeIds) {
                Integer payFlag = MapValueUtil.getInteger(recipeInfo,"payFlag");
                if(Integer.valueOf(PayConstant.PAY_FLAG_PAY_SUCCESS).equals(payFlag)
                        || Integer.valueOf(PayConstant.PAY_FLAG_NOT_PAY).equals(payFlag)) {
                    //支付成功调用
                    _resultBean = recipeService.updateRecipePayResultImplForOrder(saveFlag, recipeId, payFlag, recipeInfo);
                    if (RecipeResultBean.FAIL.equals(_resultBean.getCode())) {
                        result.setCode(RecipeResultBean.FAIL);
                        result.setError(_resultBean.getError());
                        break;
                    }
                }
            }
        }else{
            logger.error("updateRecipeInfo param recipeIds size is zero. result={}", JSONUtils.toString(result));
        }

        return result;
    }

    /**
     * 判断优惠券是否有用
     * @param couponId
     * @return
     */
    public boolean isUsefulCoupon(Integer couponId){
        if(null != couponId && couponId > 0){
            return true;
        }

        return false;
    }

}
