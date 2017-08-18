package eh.cdr.dao;

import ctd.account.UserRoleToken;
import ctd.dictionary.DictionaryController;
import ctd.mvc.upload.FileMetaRecord;
import ctd.mvc.upload.FileService;
import ctd.mvc.upload.exception.FileRegistryException;
import ctd.mvc.upload.exception.FileRepositoryException;
import ctd.persistence.DAOFactory;
import ctd.persistence.annotation.DAOMethod;
import ctd.persistence.annotation.DAOParam;
import ctd.persistence.bean.QueryResult;
import ctd.persistence.exception.DAOException;
import ctd.persistence.support.hibernate.HibernateSupportDelegateDAO;
import ctd.persistence.support.hibernate.template.AbstractHibernateStatelessResultAction;
import ctd.persistence.support.hibernate.template.HibernateSessionTemplate;
import ctd.persistence.support.hibernate.template.HibernateStatelessResultAction;
import ctd.util.BeanUtils;
import ctd.util.JSONUtils;
import ctd.util.annotation.RpcService;
import eh.base.constant.ConditionOperator;
import eh.base.constant.ErrorCode;
import eh.base.constant.SqlOperInfo;
import eh.base.dao.*;
import eh.cdr.bean.PatientRecipeBean;
import eh.cdr.constant.RecipeConstant;
import eh.cdr.constant.RecipeStatusConstant;
import eh.cdr.service.RecipeListService;
import eh.entity.base.Department;
import eh.entity.base.Doctor;
import eh.entity.base.Employment;
import eh.entity.base.UserRoles;
import eh.entity.cdr.Recipe;
import eh.entity.cdr.RecipeRollingInfo;
import eh.entity.cdr.Recipedetail;
import eh.entity.mpi.Patient;
import eh.mpi.dao.PatientDAO;
import eh.task.executor.SaveRecipeExecutor;
import eh.utils.DateConversion;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.dom4j.Element;
import org.hibernate.Query;
import org.hibernate.StatelessSession;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.util.*;


public abstract class RecipeDAO extends HibernateSupportDelegateDAO<Recipe> {

	private static final Log logger = LogFactory.getLog(RecipeDAO.class);

	public RecipeDAO() {
		super();
		this.setEntityName(Recipe.class.getName());
		this.setKeyField("recipeId");
	}

	@RpcService
	@DAOMethod
	public abstract Recipe getByRecipeId(int recipeId);

    @DAOMethod(sql = "from Recipe where orderCode=:orderCode")
    public abstract List<Recipe> findRecipeListByOrderCode(@DAOParam("orderCode") String orderCode);

    @DAOMethod(sql = "select recipeId from Recipe where orderCode=:orderCode")
    public abstract List<Integer> findRecipeIdsByOrderCode(@DAOParam("orderCode") String orderCode);

    @DAOMethod (sql = "from Recipe where recipeId in :recipeIds")
    public abstract List<Recipe> findByRecipeIds(@DAOParam("recipeIds") List<Integer> recipeIds);

    @DAOMethod
    public abstract Recipe getByOutTradeNo(String tradeNo);

    @DAOMethod(sql = "select status from Recipe where recipeId=:recipeId")
    public abstract Integer getStatusByRecipeId(@DAOParam("recipeId") Integer recipeId);

    @DAOMethod(sql = "select clinicOrgan from Recipe where recipeId=:recipeId")
    public abstract Integer getOrganIdByRecipeId(@DAOParam("recipeId") Integer recipeId);

    @DAOMethod
    public abstract List<Recipe> findByPayFlag(Integer payFlag);

    @DAOMethod
    public abstract Recipe getByRecipeCodeAndClinicOrgan(String recipeCode, Integer clinicOrgan);

    @DAOMethod
    public abstract Recipe getByOriginRecipeCodeAndOriginClinicOrgan(String originRecipeCode, Integer originClinicOrgan);

    @DAOMethod(sql = "from Recipe where doctor=:doctorId and recipeId<:recipeId and status!=10 order by createDate desc ")
    public abstract List<Recipe> findRecipesForDoctor(@DAOParam("doctorId") Integer doctorId, @DAOParam("recipeId") Integer recipeId,
                                                      @DAOParam(pageStart = true) int start, @DAOParam(pageLimit = true) int limit);

    @DAOMethod(sql = "update Recipe set orderCode=:orderCode where recipeId in :recipeIds")
    public abstract void updateOrderCodeByRecipeIds(@DAOParam("recipeIds") List<Integer> recipeIds, @DAOParam("orderCode") String orderCode);


    @DAOMethod(sql = "update Recipe set orderCode=null where orderCode=:orderCode")
    public abstract void updateOrderCodeToNullByOrderCode(@DAOParam("orderCode") String orderCode);

    public List<Doctor> findDoctorByCount(final String startDt, final String endDt,
                                          final List<Integer> organs, final int start,final int limit)
    {
        HibernateStatelessResultAction<List<Doctor>> action = new AbstractHibernateStatelessResultAction<List<Doctor>>()
        {
            @Override
            public void execute(StatelessSession ss) throws Exception
            {
                StringBuilder hql = new StringBuilder();
                hql.append("select d from Recipe r, Doctor d where");
                if (null != organs && !organs.isEmpty())
                {
                    hql.append(" r.clinicOrgan in (:organs) and ");
                }
                hql.append(" r.doctor=d.doctorId and r.signDate between '"+startDt+"' and '"+endDt+"' and r.status=6 " +
                "and (d.testPersonnel is null or d.testPersonnel=0) GROUP BY r.doctor ORDER BY count(r.doctor) desc");

                Query q = ss.createQuery(hql.toString());

                if (null != organs && !organs.isEmpty())
                {
                    q.setParameterList("organs",organs);
                }

                q.setMaxResults(limit);
                q.setFirstResult(start);

                setResult(q.list());
            }
        };

        HibernateSessionTemplate.instance().executeReadOnly(action);
        return action.getResult();
    }

    public List<RecipeRollingInfo> findLastesRecipeList(final String startDt, final String endDt,
                                                              final List<Integer> organs, final int start,final int limit)
    {
        HibernateStatelessResultAction<List<RecipeRollingInfo>> action = new AbstractHibernateStatelessResultAction<List<RecipeRollingInfo>>()
        {
            @Override
            public void execute(StatelessSession ss) throws Exception
            {
                StringBuilder hql = new StringBuilder();
                hql.append("select new eh.entity.cdr.RecipeRollingInfo(r.clinicOrgan,r.depart,r.doctor,r.mpiid) from Recipe r,Doctor d where " +
                        "r.doctor = d.doctorId and ");
                if (null != organs && !organs.isEmpty())
                {
                    hql.append(" r.clinicOrgan in (:organs) and ");
                }
                hql.append("r.signDate between '"+startDt+"' and '"+endDt+"' and (d.testPersonnel is null or d.testPersonnel=0) " +
                        "order by r.recipeId desc ");

                Query q = ss.createQuery(hql.toString());

                if (null != organs && !organs.isEmpty())
                {
                    q.setParameterList("organs",organs);
                }

                q.setMaxResults(limit);
                q.setFirstResult(start);

                setResult(q.list());
            }
        };

        HibernateSessionTemplate.instance().executeReadOnly(action);
        return action.getResult();
    }
    /**
	 * 处方单列表保存
	 *
	 * @desc 保存的是从his导入过来的处方数据
	 * @author LF
	 * @param recipe
	 * @return
	 */
	public Recipe saveRecipe(Recipe recipe) {
		logger.info("处方单列表保存:" + JSONUtils.toString(recipe));
		return save(recipe);
	}

    public List<Integer> findPendingRecipes(final List<String> allMpiIds, final Integer status,
                                            final int start, final int limit){
        HibernateStatelessResultAction<List<Integer>> action = new AbstractHibernateStatelessResultAction<List<Integer>>()
        {
            @Override
            public void execute(StatelessSession ss) throws Exception
            {
                StringBuilder hql = new StringBuilder();
                hql.append("select r.recipeId from cdr_recipe r left join cdr_recipeorder o on r.orderCode=o.orderCode where r.status=:status " +
                        "and r.chooseFlag=0 and (o.effective is null or o.effective=0 ) and r.mpiid in :mpiIds order by r.signDate desc");
                Query q = ss.createSQLQuery(hql.toString());
                q.setParameterList("mpiIds", allMpiIds);
                q.setParameter("status", status);
                q.setMaxResults(limit);
                q.setFirstResult(start);
                setResult(q.list());
            }
        };

        HibernateSessionTemplate.instance().executeReadOnly(action);
        return action.getResult();
    }


    public List<PatientRecipeBean> findOtherRecipesForPatient(final List<String > mpiIdList, final List<Integer> notInRecipeIds,
                                                              final int start, final int limit){
        HibernateStatelessResultAction<List<PatientRecipeBean>> action = new AbstractHibernateStatelessResultAction<List<PatientRecipeBean>>() {
            @Override
            public void execute(StatelessSession ss) throws Exception {
                StringBuilder hql = new StringBuilder();
                hql.append("select s.type,s.recordCode,s.recordId,s.mpiId,s.diseaseName,s.status,s.fee," +
                        "s.recordDate,s.couponId,s.medicalPayFlag,s.recipeType from (");
                hql.append("SELECT "+ RecipeListService.LIST_TYPE_RECIPE+" as type,null as couponId, t.MedicalPayFlag as medicalPayFlag, t.RecipeID as recordCode,t.RecipeID as recordId," +
                        "t.MPIID as mpiId,t.OrganDiseaseName as diseaseName,t.Status,t.TotalMoney as fee," +
                        "t.SignDate as recordDate,t.RecipeType as recipeType FROM cdr_recipe t " +
                        "left join cdr_recipeorder k on t.OrderCode=k.OrderCode ");
                hql.append("WHERE t.MPIID IN (:mpiIdList) and (k.Effective is null or k.Effective = 0) ")
                    .append("and (t.ChooseFlag=1 or (t.ChooseFlag=0 and t.Status="+RecipeStatusConstant.CHECK_PASS+")) " );
                if(CollectionUtils.isNotEmpty(notInRecipeIds)){
                    hql.append("and t.RecipeID not in (:notInRecipeIds) ");
                }
                hql.append("UNION ALL ");
                hql.append("SELECT "+RecipeListService.LIST_TYPE_ORDER+" as type,o.CouponId as couponId, 0 as medicalPayFlag, " +
                        "o.OrderCode as recordCode,o.OrderId as recordId,o.MpiId as mpiId,'' as diseaseName," +
                        "o.Status,o.ActualPrice as fee,o.CreateTime as recordDate,0 as recipeType FROM cdr_recipeorder o " +
                        "WHERE o.MpiId IN (:mpiIdList) and o.Effective = 1 ");
                hql.append(") s ORDER BY s.recordDate desc");
                Query q = ss.createSQLQuery(hql.toString());
                q.setParameterList("mpiIdList", mpiIdList);
                if(CollectionUtils.isNotEmpty(notInRecipeIds)) {
                    q.setParameterList("notInRecipeIds", notInRecipeIds);
                }
                q.setMaxResults(limit);
                q.setFirstResult(start);
                List<Object[]> result = q.list();
                List<PatientRecipeBean> backList = new ArrayList<>(limit);
                if(CollectionUtils.isNotEmpty(result)){
                    PatientRecipeBean patientRecipeBean;
                    for(Object[] objs : result){
                        patientRecipeBean = new PatientRecipeBean();
                        patientRecipeBean.setRecordType(objs[0].toString());
                        patientRecipeBean.setRecordCode(objs[1].toString());
                        patientRecipeBean.setRecordId(Integer.parseInt(objs[2].toString()));
                        patientRecipeBean.setMpiId(objs[3].toString());
                        patientRecipeBean.setOrganDiseaseName(objs[4].toString());
                        patientRecipeBean.setStatusCode(Integer.parseInt(objs[5].toString()));
                        patientRecipeBean.setTotalMoney(new BigDecimal(objs[6].toString()));
                        patientRecipeBean.setSignDate((Date) objs[7]);
                        if(null != objs[8]) {
                            patientRecipeBean.setCouponId(Integer.parseInt(objs[8].toString()));
                        }
                        if(null != objs[9]){
                            patientRecipeBean.setMedicalPayFlag(Integer.parseInt(objs[9].toString()));
                        }
                        patientRecipeBean.setRecipeType(Integer.parseInt(objs[10].toString()));

                        backList.add(patientRecipeBean);
                    }
                }

                setResult(backList);
            }
        };
        HibernateSessionTemplate.instance().executeReadOnly(action);
        return action.getResult();
    }

	/**
	 * 解析电子处方单列表
	 *
	 * @author LF
	 * @param element
	 * @return
	 */
	@RpcService
	public Recipe parseSingleRecipe(Element element, Integer organId) {
		String recipeCode = element.elementText("RecipeCode");
		Integer recipeType = Integer.valueOf(element.elementText("RecipeType"));
		// 医生转化
		String clinicDoctor = element.elementText("ClinicDoctor");
		EmploymentDAO employmentDAO = DAOFactory.getDAO(EmploymentDAO.class);
		Integer doctorId = null;
		if (!StringUtils.isEmpty(clinicDoctor)) {
			List<Employment> employments = employmentDAO
					.findByJobNumberAndOrganId(clinicDoctor, organId);
			Employment employment = employments.get(0);
			if (employment != null) {
				doctorId = employment.getDoctorId();
			}
		}
		// 科室转化服务
		String clinicDepartID = element.elementText("ClinicDepart");
		DepartmentDAO departmentDAO = DAOFactory.getDAO(DepartmentDAO.class);
		Integer deptId = null;
		if (!StringUtils.isEmpty(clinicDepartID)) {
			Department d = departmentDAO.getByCodeAndOrgan(clinicDepartID,
					organId);
			if (d != null) {
				deptId = d.getDeptId();
			}
		}
		String createDateE = element.elementText("CreateDate");
		String copyNumE = element.elementText("CopyNum");
		String totalMoneyE = element.elementText("TotalMoney");
		String payDateE = element.elementText("PayDate");
		String payListIdE = element.elementText("PayListID");
		String giveDateE = element.elementText("GiveDate");
		String giveOrgan = element.elementText("GiveOrgan");

		Recipe recipe = new Recipe();
		recipe.setRecipeCode(recipeCode);
		recipe.setRecipeType(recipeType);
		recipe.setDoctor(doctorId);
		recipe.setDepart(deptId);
		if (!StringUtils.isEmpty(createDateE)) {
			recipe.setCreateDate(DateConversion.getCurrentDate(createDateE,
					"yyyy-MM-dd HH:mm:ss"));
		}
		if (!StringUtils.isEmpty(copyNumE)) {
			recipe.setCopyNum(Integer.valueOf(copyNumE));
		} else {
			recipe.setCopyNum(0);
		}
		if (!StringUtils.isEmpty(totalMoneyE)) {
			recipe.setTotalMoney(new BigDecimal(Double.valueOf(totalMoneyE)));
		} else {
			recipe.setTotalMoney(new BigDecimal(0d));
		}
		if (!StringUtils.isEmpty(payDateE)) {
			recipe.setPayDate(DateConversion.getCurrentDate(payDateE,
					"yyyy-MM-dd HH:mm:ss"));
		}
		if (!StringUtils.isEmpty(payListIdE)) {
			recipe.setPayListId(Integer.valueOf(payListIdE));
		} else {
			recipe.setPayListId(0);
		}
		if (!StringUtils.isEmpty(giveDateE)) {
			recipe.setGiveDate(DateConversion.getCurrentDate(giveDateE,
					"yyyy-MM-dd HH:mm:ss"));
		}
		recipe.setGiveOrgan(Integer.valueOf(giveOrgan));

		return recipe;
	}

    /**
     * 获取处方总数
     * @param doctorId
     * @param recipeStatus
     * @param conditionOper
     * @param containDel  true 包含删除的记录  false  去除删除的处方记录
     * @return
     */
    public int getCountByDoctorIdAndStatus(final int doctorId, final List<Integer> recipeStatus,
                                           final String conditionOper, final boolean containDel){
        HibernateStatelessResultAction<Long> action = new AbstractHibernateStatelessResultAction<Long>() {
            public void execute(StatelessSession ss) throws DAOException {
                String hql = getBaseHqlByConditions(false,recipeStatus,conditionOper,containDel);
                if(StringUtils.isNotEmpty(hql)) {
                    Query q = ss.createQuery(hql.toString());
                    q.setParameter("doctorId", doctorId);
                    setResult((Long) q.uniqueResult());
                }else{
                    setResult(0l);
                }
            }
        };

        HibernateSessionTemplate.instance().executeReadOnly(action);
        return Integer.parseInt(action.getResult().toString());
    }

    /**
     * 获取不同状态的处方
     * @param doctorId
     * @param recipeStatus 0新开处方 1复核确认（待审核） 2审核完成 3已支付 4开始配送（开始配药） 5配送完成（发药完成） -1审核未通过 9取消
     * @param conditionOper hql where操作符
     * @param startIndex 分页起始下标
     * @param limit 分页限制条数
     * @param mark 0 新处方  1 历史处方
     * @return
     */
    public List<Recipe> findByDoctorIdAndStatus(final int doctorId, final List<Integer> recipeStatus, final String conditionOper,
                                                final boolean containDel, final int startIndex, final int limit, final int mark){

        HibernateStatelessResultAction<List<Recipe>> action = new AbstractHibernateStatelessResultAction<List<Recipe>>() {
            public void execute(StatelessSession ss) throws DAOException {
                StringBuilder hql = new StringBuilder();
                String _hql = getBaseHqlByConditions(true,recipeStatus,conditionOper,containDel);
                String orderHql;
                if (0 == mark) {
                    orderHql = "lastModify desc";
                } else {
                    orderHql = "signDate desc";
                }
                hql.append(_hql + " order by " + orderHql);
                Query q = ss.createQuery(hql.toString());
                q.setParameter("doctorId", doctorId);
                q.setFirstResult(startIndex);
                q.setMaxResults(limit);
                setResult(q.list());
            }
        };

        HibernateSessionTemplate.instance().executeReadOnly(action);
        return action.getResult();
    }

    /**
     * 根据条件获取基本hql
     * @param getDetail
     * @param recipeStatus
     * @param conditionOper
     * @param containDel
     * @return
     */
    private String getBaseHqlByConditions(boolean getDetail, List<Integer> recipeStatus,
                                          String conditionOper, boolean containDel){
        StringBuilder hql = new StringBuilder();
        if(!getDetail){
            hql.append("select count(id)");
        }
        hql.append(" From Recipe where doctor=:doctorId and fromflag=1 ");
        hql.append(getStatusHql(recipeStatus,conditionOper,null));
        if(!containDel){
            hql.append(" and status!=" + RecipeStatusConstant.DELETE);
        }

        return hql.toString();
    }

    private String getStatusHql(List<Integer> recipeStatus, String conditionOper, String orHql){
        StringBuilder statusHql = new StringBuilder();
        if(null != recipeStatus){
            if(1 == recipeStatus.size()){
                statusHql.append(" and (status"+conditionOper+recipeStatus.get(0));
            }else if (recipeStatus.size() > 1){
                StringBuilder _statusHql = new StringBuilder();
                for(Integer stat : recipeStatus){
                    _statusHql.append(","+stat);
                }
                if(_statusHql.length() > 0){
                    statusHql.append(" and (status "+ ConditionOperator.IN+" ("+_statusHql.substring(1)+")");
                }
            }

            if(StringUtils.isNotEmpty(orHql)){
                statusHql.append(" or "+orHql);
            }

            statusHql.append(" ) ");
        }

        return statusHql.toString();
    }

    /**
     *保存或修改处方
     * @param recipe
     * @param recipedetails
     * @param update  true 修改，false 保存
     */
	public Integer updateOrSaveRecipeAndDetail(final Recipe recipe, final List<Recipedetail> recipedetails,final boolean update) {
		HibernateStatelessResultAction<Integer> action = new AbstractHibernateStatelessResultAction<Integer>() {
			public void execute(StatelessSession ss) throws DAOException {
                Recipe dbRecipe;
                if(update) {
                    dbRecipe = update(recipe);
                }else{
                    dbRecipe = save(recipe);
                }

                RecipeDetailDAO recipeDetailDAO = DAOFactory.getDAO(RecipeDetailDAO.class);
                for(Recipedetail detail :recipedetails){
                    if(!update){
                        detail.setRecipeId(dbRecipe.getRecipeId());
                    }
                    recipeDetailDAO.save(detail);
                }

                setResult(dbRecipe.getRecipeId());
			}
		};
		HibernateSessionTemplate.instance().executeTrans(action);

        return action.getResult();
	}

    /**
     * 根据处方ID修改处方状态，最后更新时间和自定义需要修改的字段
     * @param status
     * @param recipeId
     * @param changeAttr   需要级联修改的其他字段
     * @return
     */
	public Boolean updateRecipeInfoByRecipeId(final int recipeId, final int status, final Map<String, ?> changeAttr){
        HibernateStatelessResultAction<Boolean> action = new AbstractHibernateStatelessResultAction<Boolean>() {
            @Override
            public void execute(StatelessSession ss) throws Exception {
                StringBuilder hql = new StringBuilder("update Recipe set status=:status ");
                if(null != changeAttr && !changeAttr.isEmpty()){
                    for(String key : changeAttr.keySet()){
                        hql.append("," + key+"=:"+key);
                    }
                }

                hql.append(" where recipeId=:recipeId");
                Query q = ss.createQuery(hql.toString());
                q.setParameter("status", status);
                q.setParameter("recipeId", recipeId);
                if(null != changeAttr && !changeAttr.isEmpty()){
                    for(String key : changeAttr.keySet()){
                        q.setParameter(key, changeAttr.get(key));
                    }
                }

                int flag = q.executeUpdate();
                setResult(flag == 1);
            }
        };
        HibernateSessionTemplate.instance().execute(action);
        return action.getResult();
    }

    /**
     *更新处方自定义字段
     * @param recipeId
     * @param changeAttr
     * @return
     */
    public Boolean updateRecipeInfoByRecipeId(final int recipeId, final Map<String, ?> changeAttr){
        if(null == changeAttr || changeAttr.isEmpty()){
            return true;
        }

        HibernateStatelessResultAction<Boolean> action = new AbstractHibernateStatelessResultAction<Boolean>() {
            @Override
            public void execute(StatelessSession ss) throws Exception {
                StringBuilder hql = new StringBuilder("update Recipe set ");
                StringBuilder keyHql = new StringBuilder();
                for(String key : changeAttr.keySet()){
                    keyHql.append(","+key+"=:"+key);
                }
                hql.append(keyHql.toString().substring(1)).append(" where recipeId=:recipeId");
                Query q = ss.createQuery(hql.toString());

                q.setParameter("recipeId", recipeId);
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

    /**
     * 根据条件查询sql
     * @param searchAttr
     * @return
     */
    public List<Recipe> findRecipeListWithConditions(final List<SqlOperInfo> searchAttr){
        if(CollectionUtils.isEmpty(searchAttr)){
            return null;
        }

        HibernateStatelessResultAction<List<Recipe>> action = new AbstractHibernateStatelessResultAction<List<Recipe>>() {
            @Override
            public void execute(StatelessSession ss) throws Exception {
                StringBuilder hql = new StringBuilder("from Recipe where 1=1 ");
                for(SqlOperInfo info : searchAttr){
                    hql.append(" and "+info.getHqlCondition());
                }
                Query q = ss.createQuery(hql.toString());
                for(SqlOperInfo info : searchAttr){
                    if(ConditionOperator.BETWEEN.equals(info.getOper())){
                        q.setParameter(info.getKey()+SqlOperInfo.BETWEEN_START, info.getValue());
                        q.setParameter(info.getKey()+SqlOperInfo.BETWEEN_END, info.getExtValue());
                    }else {
                        q.setParameter(info.getKey(), info.getValue());
                    }

                }
                q.setMaxResults(20);

                setResult(q.list());
            }
        };
        HibernateSessionTemplate.instance().executeReadOnly(action);
        return action.getResult();
    }

    /**
     * 批量修改处方提醒标志位
     * @param recipeIds
     */
    @DAOMethod(sql = "update Recipe set remindFlag=1 where recipeId in :recipeIds")
    public abstract void updateRemindFlagByRecipeId(@DAOParam("recipeIds") List<Integer> recipeIds);

    /**
     * 批量修改处方推送药企标志位
     * @param recipeIds
     */
    @DAOMethod(sql = "update Recipe set pushFlag=1 where recipeId in :recipeIds")
    public abstract void updatePushFlagByRecipeId(@DAOParam("recipeIds") List<Integer> recipeIds);

    /**
     * 根据需要变更的状态获取处方ID集合
     * @param cancelStatus
     * @return
     */
    public List<Recipe> getRecipeListForCancelRecipe(final int cancelStatus, final String startDt, final String endDt){
        HibernateStatelessResultAction<List<Recipe>> action = new AbstractHibernateStatelessResultAction<List<Recipe>>() {
            @Override
            public void execute(StatelessSession ss) throws Exception {
                StringBuilder hql = new StringBuilder("from Recipe where signDate between '"+startDt+"' and '"+endDt+"' ");
                if(cancelStatus == RecipeStatusConstant.NO_PAY) {
                    //超过3天未支付
                    hql.append(" and status=" + RecipeStatusConstant.CHECK_PASS
                            +" and payFlag=0 and payMode="+RecipeConstant.PAYMODE_ONLINE+" and orderCode is not null ");
                }else if(cancelStatus == RecipeStatusConstant.NO_OPERATOR){
                    //超过3天未操作
                    hql.append(" and status=" + RecipeStatusConstant.CHECK_PASS
                            +" and chooseFlag=0 ");
                }
                Query q = ss.createQuery(hql.toString());
                setResult(q.list());
            }
        };

        HibernateSessionTemplate.instance().executeReadOnly(action);
        return action.getResult();
    }

    /**
     *处方失效前一天提醒
     * @param cancelStatus
     * @return
     */
    public List<Recipe> getRecipeListForRemind(final int cancelStatus, final String startDt, final String endDt){
        HibernateStatelessResultAction<List<Recipe>> action = new AbstractHibernateStatelessResultAction<List<Recipe>>() {
            @Override
            public void execute(StatelessSession ss) throws Exception {
                StringBuilder hql = new StringBuilder("select new Recipe(recipeId,signDate) from Recipe where signDate between " +
                        "'"+startDt+"' and '"+endDt+"' ");
                if(cancelStatus == RecipeStatusConstant.PATIENT_NO_OPERATOR) {
                    //未操作
                    hql.append(" and status="+RecipeStatusConstant.CHECK_PASS +
                            " and remindFlag=0 and chooseFlag=0 ");
                }else if(cancelStatus == RecipeStatusConstant.PATIENT_NO_PAY){
                    //选择了医院取药-到院支付
                    hql.append(" and status="+RecipeStatusConstant.CHECK_PASS +
                            " and payMode="+RecipeConstant.PAYMODE_TO_HOS+" and remindFlag=0 and chooseFlag=1 and payFlag=0 " );
                }else if(cancelStatus == RecipeStatusConstant.PATIENT_NODRUG_REMIND){
                    //选择了到店取药
                    hql.append(" and status="+RecipeStatusConstant.CHECK_PASS_YS +
                            " and payMode="+RecipeConstant.PAYMODE_TFDS+" and remindFlag=0 and chooseFlag=1 ");
                }
                Query q = ss.createQuery(hql.toString());
                setResult(q.list());
            }
        };

        HibernateSessionTemplate.instance().executeReadOnly(action);
        return action.getResult();
    }

    /**
     * 获取3天以内医院取药的处方单，需要定时从HIS获取状态
     * @return
     */
    public List<Recipe> getRecipeStatusFromHis(final String startDt, final String endDt){
        HibernateStatelessResultAction<List<Recipe>> action = new AbstractHibernateStatelessResultAction<List<Recipe>>() {
            @Override
            public void execute(StatelessSession ss) throws Exception {
                StringBuilder hql = new StringBuilder("from Recipe where " +
                        " (status="+ RecipeStatusConstant.HAVE_PAY+" or (status="+RecipeStatusConstant.CHECK_PASS+
                        " and signDate between '"+startDt+"' and '"+endDt+"' ))");
                Query q = ss.createQuery(hql.toString());
                setResult(q.list());
            }
        };

        HibernateSessionTemplate.instance().executeReadOnly(action);
        return action.getResult();
    }

	/**
	 * zhongzx
	 * 查询药师审核平台待审核、已审核、或者所有的处方单的总条数
	 * @param organ
	 * @param flag 标志位
     * @return
     */
	public Long getRecipeCountByFlag(final Set<Integer> organ, final int flag){
		HibernateStatelessResultAction<Long> action = new AbstractHibernateStatelessResultAction<Long>() {
			@Override
			public void execute(StatelessSession ss) throws Exception {
				StringBuilder hql = new StringBuilder();
				//0是待药师审核
				if(flag == 0){
					hql.append("select count(*) from Recipe where clinicOrgan in (:organ) and status = 8 ");
				}
				//1是已审核
				else if(flag == 1){
					hql.append("select count(*) from Recipe where clinicOrgan in (:organ) and checkDateYs is not null ");
				}
				//2是全部
				else if(flag == 2){
					hql.append("select count(*) from Recipe where clinicOrgan in (:organ) and (status = 8 or checkDateYs is not null) ");
				}else{
                    throw new DAOException(ErrorCode.SERVICE_ERROR, "flag is invalid");
                }
				Query q = ss.createQuery(hql.toString());
				q.setParameterList("organ", organ);
				setResult((Long)q.uniqueResult());
			}
		};
		HibernateSessionTemplate.instance().executeReadOnly(action);
		return action.getResult();
	}

	/**
	 * zhongzx
	 * 查询药师审核平台待审核、审核通过、审核未通过、或者所有的处方单
	 * @param organ
	 * @param flag 标志位 0-待审核 1-审核通过 2-审核未通过 3-全部
	 * @param start
	 * @param limit
	 * @return
	 */
	public List<Recipe> findRecipeByFlag(final Set<Integer> organ, final int flag, final int start, final int limit){
		HibernateStatelessResultAction<List<Recipe>> action = new AbstractHibernateStatelessResultAction<List<Recipe>>() {
			@Override
			public void execute(StatelessSession ss) throws Exception {
				StringBuilder hql = new StringBuilder();
				//0是待药师审核
				if(flag == 0){
					hql.append("from Recipe where clinicOrgan in (:organ) and status = "+RecipeStatusConstant.READY_CHECK_YS);
				}
				//1是审核通过
				else if(flag == 1){
					hql.append("select distinct r from Recipe r,RecipeCheck rc where r.recipeId = rc.recipeId and r.clinicOrgan in (:organ)" +
                            "and (rc.checkStatus = 1 or (rc.checkStatus=0 and r.supplementaryMemo is not null)) ");
				}
                //2是审核未通过
                else if(flag == 2){
                    hql.append("select distinct r from Recipe r,RecipeCheck rc where r.recipeId = rc.recipeId and r.clinicOrgan in (:organ)" +
                            "and rc.checkStatus = 0 and r.supplementaryMemo is null ");
                }
				//2是全部
				else if(flag == 3){
					hql.append("from Recipe where clinicOrgan in (:organ) and (status = 8 or checkDateYs is not null) ");
				}else{
                    throw new DAOException(ErrorCode.SERVICE_ERROR, "flag is invalid");
                }
				hql.append("order by signDate desc");
				Query q = ss.createQuery(hql.toString());
				q.setParameterList("organ", organ);
				q.setFirstResult(start);
				q.setMaxResults(limit);
				setResult(q.list());
			}
		};
		HibernateSessionTemplate.instance().executeReadOnly(action);
		return action.getResult();
	}

    /**
     * 上传电子签名文件
     * @param data
     * @param fileName
     * @return
     * @throws FileNotFoundException
     * @throws FileRepositoryException
     * @throws FileRegistryException
     */
    public Integer uploadRecipeFile(byte[] data, String fileName) {
        if(null == data){
            throw new DAOException(ErrorCode.SERVICE_ERROR, "byte[] 为空");
        }
        OutputStream fileOutputStream = null;
        File file = null;
        try{
            //先生成本地文件
            file = new File(fileName);
            fileOutputStream = new FileOutputStream(file);
            if (data.length > 0) {
                fileOutputStream.write(data, 0, data.length);
                fileOutputStream.flush();
            }

            FileMetaRecord meta = new FileMetaRecord();
            UserRoleToken token = UserRoleToken.getCurrent();
            if(null == token){
                throw new DAOException(ErrorCode.SERVICE_ERROR, "userRoleToken is null");
            }
            meta.setManageUnit(token.getManageUnit());
            meta.setOwner(token.getUserId());
            meta.setLastModify(new Date());
            meta.setUploadTime(new Date());
            meta.setMode(0);
            meta.setCatalog("other-doc"); // 测试
            meta.setContentType("application/pdf");
            meta.setFileName(fileName);
            meta.setFileSize(file.length());
            FileService.instance().upload(meta, file);
            file.delete();
            return meta.getFileId();
        }catch (Exception e){
            logger.error("uploadFile exception:"+e.getMessage());
        } finally {
            try{
                fileOutputStream.close();
            } catch (Exception e){
                logger.error("uploadFile exception:"+e.getMessage());
            }

        }
        return null;
    }

    /**
     * 上传电子签名文件
     * @param data
     * @param fileName
     * @return
     * @throws FileNotFoundException
     * @throws FileRepositoryException
     * @throws FileRegistryException
     */
    public Integer uploadRecipeFileForHisCallBack(byte[] data, String fileName, String userId) {
        if(null == data){
            throw new DAOException(ErrorCode.SERVICE_ERROR, "byte[] 为空");
        }
        OutputStream fileOutputStream = null;
        File file = null;
        try{
            //先生成本地文件
            file = new File(fileName);
            fileOutputStream = new FileOutputStream(file);
            if (data.length > 0) {
                fileOutputStream.write(data, 0, data.length);
                fileOutputStream.flush();
            }

            FileMetaRecord meta = new FileMetaRecord();
            UserRolesDAO userRolesDAO = DAOFactory.getDAO(UserRolesDAO.class);
            UserRoles role = userRolesDAO.getByUserIdAndRoleId(userId, "doctor");
            if(null == role){
                throw new DAOException(ErrorCode.SERVICE_ERROR, "role is null");
            }
            meta.setManageUnit(role.getManageUnit());
            meta.setOwner(userId);
            meta.setLastModify(new Date());
            meta.setUploadTime(new Date());
            meta.setMode(0);
            meta.setCatalog("other-doc"); // 测试
            meta.setContentType("application/pdf");
            meta.setFileName(fileName);
            meta.setFileSize(file.length());
            FileService.instance().upload(meta, file);
            file.delete();
            return meta.getFileId();
        }catch (Exception e){
            logger.error("uploadFile exception:"+e.getMessage());
        } finally {
            try{
                fileOutputStream.close();
            } catch (Exception e){
                logger.error("uploadFile exception:"+e.getMessage());
            }
        }
        return null;
    }

    /**
     * chuwei
     * 查询药师审核平台待审核的处方单
     * @param organ
     * @return
     */
    public Boolean checkIsExistUncheckedRecipe(final int organ){
        HibernateStatelessResultAction<Boolean> action = new AbstractHibernateStatelessResultAction<Boolean>() {
            @Override
            public void execute(StatelessSession ss) throws Exception {
                String hql = "select count(*) from Recipe where clinicOrgan=:organ and status = 8";
                Query q = ss.createQuery(hql);
                q.setParameter("organ", organ);

                Long count = (Long) q.uniqueResult();
                if (count > 0)
                    setResult(true);
                else
                    setResult(false);
            }
        };
        HibernateSessionTemplate.instance().executeReadOnly(action);
        return action.getResult();
    }

    /**
     * app端 显示未处理业务的条数
     * zhongzx
     * @param doctorId
     * @return
     */
    public Long getUncheckedRecipeNum(Integer doctorId){
        DoctorDAO doctorDAO = DAOFactory.getDAO(DoctorDAO.class);
        AuditPrescriptionOrganDAO auditDao = DAOFactory.getDAO(AuditPrescriptionOrganDAO.class);
        Doctor doctor = doctorDAO.getByDoctorId(doctorId);

        if(null != doctor){
            Set<Integer> organs = new HashSet<>();
            organs.add(doctor.getOrgan());
            List<Integer> organIds = auditDao.findOrganIdsByDoctorId(doctorId);
            organs.addAll(organIds);
            //flag = 0 查询待药师审核的条数
            return getRecipeCountByFlag(organs, 0);
        }
        return 0L;
    }


    private void validateOptionForStatistics(Integer status, Integer doctor, String mpiid, Date bDate, Date eDate, Integer dateType,
                                             final int start, final int limit)
    {
        if(dateType==null){
            throw new DAOException(DAOException.VALUE_NEEDED,"dateType is required");
        }
        if (bDate == null) {
            throw new DAOException(DAOException.VALUE_NEEDED, "统计开始时间不能为空");
        }
        if (eDate == null) {
            throw new DAOException(DAOException.VALUE_NEEDED, "统计结束时间不能为空");
        }

    }

    private StringBuilder generateHQLforStatistics(Integer organId,
            Integer status, Integer doctor, String mpiid, Date bDate, Date eDate, Integer dateType,
            final int start, final int limit) {
        final StringBuilder hql= new StringBuilder(" from Recipe where 1=1 ");
        if(organId!=null){
           hql.append(" and clinicOrgan ="+organId);
        }
        switch (dateType){
            case 0://开方时间
                hql.append(" and DATE(createDate)>=DATE(:startTime)"
                        + " and DATE(createDate)<=DATE(:endTime) ");
                break;
            case 1://审核时间
                hql.append(" and DATE(checkDate)>=DATE(:startTime)"
                        + " and DATE(checkDate)<=DATE(:endTime) ");
                break;
        }
        if(status!=null){
            hql.append(" and status =").append(status);
        }
        if(doctor!=null){
            hql.append(" and doctor=").append(doctor);
        }
        if(mpiid!=null && !StringUtils.isEmpty(mpiid.trim())){
            hql.append(" and mpiid ='").append(mpiid).append("' ");
        }

        return hql;
    }

    /**
     * 查询处方列表
     * @param status 处方状态
     * @param doctor 开方医生
     * @param mpiid 患者主键
     * @param bDate 开始时间
     * @param eDate 结束时间
     * @param dateType 时间类型（0：开方时间，1：审核时间）
     * @param start 分页开始index
     * @param limit 分页长度
     * @return QueryResult<Map>
     */
    public QueryResult<Map> findRecipesByInfo(final Integer organId,final Integer status, final Integer doctor,final String mpiid, final Date bDate, final Date eDate, final Integer dateType,
                                              final int start, final int limit){
        this.validateOptionForStatistics(status,doctor,mpiid,bDate,eDate,dateType,start,limit);
        final StringBuilder preparedHql = this.generateHQLforStatistics(organId,status,doctor,mpiid,bDate,eDate,dateType,start,limit);

        HibernateStatelessResultAction<QueryResult<Map>> action =
                new AbstractHibernateStatelessResultAction<QueryResult<Map>>() {
                    public void execute(StatelessSession ss) throws DAOException {
                        StringBuilder sbHql = preparedHql;
                        Query countQuery = ss.createQuery("select count(*) " + sbHql.toString());
                        countQuery.setDate("startTime", bDate);
                        countQuery.setDate("endTime", eDate);
                        Long total = (Long) countQuery.uniqueResult();
                        Query query = ss.createQuery(sbHql.append(" order by recipeId DESC").toString());
                        query.setDate("startTime", bDate);
                        query.setDate("endTime", eDate);
                        query.setFirstResult(start);
                        query.setMaxResults(limit);
                        List<Recipe> recipeList = query.list();
                        List<Map> maps = new ArrayList<Map>();
                        if(recipeList!=null){
                            PatientDAO patientDAO = DAOFactory.getDAO(PatientDAO.class);
                            RecipeDetailDAO recipeDetailDAO = DAOFactory.getDAO(RecipeDetailDAO.class);
                            for (Recipe recipe:recipeList){
                                Map<String,Object> map = new HashMap<String, Object>();
                                BeanUtils.map(recipe,map);
                                map.put("detailCount",recipeDetailDAO.getCountByRecipeId(recipe.getRecipeId()));
                                map.put("patient",patientDAO.get(recipe.getMpiid()));
                                maps.add(map);
                            }
                        }
                        setResult(new QueryResult<Map>(total, query.getFirstResult(), query.getMaxResults(), maps));
                    }
                };
        HibernateSessionTemplate.instance().executeReadOnly(action);
        return action.getResult();
    }

    /**
     * 根据状态统计
     * @param status 处方状态
     * @param doctor 开方医生
     * @param mpiid 患者主键
     * @param bDate 开始时间
     * @param eDate 结束时间
     * @param dateType 时间类型（0：开方时间，1：审核时间）
     * @param start 分页开始index
     * @param limit 分页长度
     * @return HashMap<String, Integer>
     */
    public HashMap<String, Integer> getStatisticsByStatus(final Integer organId,final Integer status, final Integer doctor, final String mpiid, final Date bDate, final Date eDate, final Integer dateType,
                                                          final int start, final int limit){
        this.validateOptionForStatistics(status,doctor,mpiid,bDate,eDate,dateType,start,limit);
        final StringBuilder preparedHql = this.generateHQLforStatistics(organId,status,doctor,mpiid,bDate,eDate,dateType,start,limit);
        HibernateStatelessResultAction<HashMap<String, Integer>> action = new AbstractHibernateStatelessResultAction<HashMap<String, Integer>>() {
            @SuppressWarnings("unchecked")
            @Override
            public void execute(StatelessSession ss) throws Exception {
                long total = 0;
                StringBuilder hql = preparedHql;
                hql.append(" group by status ");
                Query query = ss.createQuery("select status, count(recipeId) as count " + hql.toString());
                query.setDate("startTime", bDate);
                query.setDate("endTime", eDate);
                List<Object[]> tfList = query.list();
                HashMap<String, Integer> mapStatistics = new HashMap<String, Integer>();
                if (tfList.size() >0) {
                    for (Object[] hps : tfList) {
                        if(hps[0] != null && !StringUtils.isEmpty(hps[0].toString()))
                        {
                            String status = hps[0].toString();
                            String statusName = DictionaryController.instance()
                                    .get("eh.cdr.dictionary.RecipeStatus").getText(status);
                            mapStatistics.put(statusName, Integer.parseInt(hps[1].toString()));
                        }
                    }
                }
                setResult(mapStatistics);
            }
        };
        HibernateSessionTemplate.instance().executeReadOnly(action);
        return action.getResult();
    }


    /**
     * 患者的来自his的处方单
     *
     * @param fromFlag    来源0his 1平台
     * @param mpiId       患者
     * @param clinicOrgan 开方机构
     * @param recipeType  处方类型:1西药 2中成药
     * @param recipeCode  处方号码
     * @return
     */
    @DAOMethod(sql = "SELECT COUNT(*) FROM Recipe where fromFlag=:fromFlag and mpiId=:mpiId and ClinicOrgan=:clinicOrgan and recipeType =:recipeType and recipeCode=:recipeCode ")
    public abstract Long getRecipeCountByMpi(@DAOParam("fromFlag") Integer fromFlag,
                                             @DAOParam("mpiId") String mpiId,
                                             @DAOParam("clinicOrgan") Integer clinicOrgan,
                                             @DAOParam("recipeType") Integer recipeType,
                                             @DAOParam("recipeCode") String recipeCode);

    /**
     * 判断来自his的处方单是否存在
     *
     * @param recipe 处方Object
     * @return
     */
    public Boolean mpiExistRecipeByMpiAndFromFlag(Recipe recipe) {
        return this.getRecipeCountByMpi(0, recipe.getMpiid(),
                recipe.getClinicOrgan(), recipe.getRecipeType(), recipe.getRecipeCode()) > 0;
    }

    /**
     * 患者mpiId修改更新信息
     * @param newPat
     * @param oldMpiId
     * @return
     */
    public Integer updatePatientInfoForRecipe(final Patient newPat, final String oldMpiId){
        HibernateStatelessResultAction<Integer> action = new AbstractHibernateStatelessResultAction<Integer>() {
            @Override
            public void execute(StatelessSession ss) throws Exception {
                StringBuilder hql = new StringBuilder("update Recipe set mpiid=:mpiid where " +
                        "mpiid=:oldMpiid");

                Query q = ss.createQuery(hql.toString());
                q.setParameter("mpiid", newPat.getMpiId());
                q.setParameter("oldMpiid", oldMpiId);
                Integer recipeCount = q.executeUpdate();

                hql = new StringBuilder("update RecipeOrder set mpiId=:mpiid where " +
                        "mpiId=:oldMpiid");
                q = ss.createQuery(hql.toString());
                q.setParameter("mpiid", newPat.getMpiId());
                q.setParameter("oldMpiid", oldMpiId);
                q.executeUpdate();

                setResult(recipeCount);
            }
        };
        HibernateSessionTemplate.instance().executeTrans(action);
        return action.getResult();
    }

    /**
     * 查询过期的药师审核不通过，需要医生二次确认的处方
     * @param startDt
     * @param endDt
     * @return
     */
    public List<Recipe> findCheckNotPassNeedDealList(final String startDt,final String endDt)
    {
        HibernateStatelessResultAction<List<Recipe>> action = new AbstractHibernateStatelessResultAction<List<Recipe>>() {
            @Override
            public void execute(StatelessSession ss) throws Exception {
                StringBuilder hql = new StringBuilder();
                hql.append("select r from Recipe r, RecipeOrder o where r.orderCode=o.orderCode "+
                        " and r.checkDateYs between '"+startDt+"' and '"+endDt+"' and r.status="+RecipeStatusConstant.CHECK_NOT_PASS_YS+
                        " and o.effective=1 ");
                Query q = ss.createQuery(hql.toString());
                setResult(q.list());
            }
        };
        HibernateSessionTemplate.instance().executeReadOnly(action);

        return action.getResult();
    }

    /**
     * 查询过期的沒有確認收貨的處方單
     * @param startDt
     * @param endDt
     * @return
     */
    public List<Recipe> findNotConfirmReceiptList(final String startDt,final String endDt)
    {
        HibernateStatelessResultAction<List<Recipe>> action = new AbstractHibernateStatelessResultAction<List<Recipe>>() {
            @Override
            public void execute(StatelessSession ss) throws Exception {
                StringBuilder hql = new StringBuilder();
                hql.append("select r from Recipe r where r.payMode="+RecipeConstant.PAYMODE_COD +
                    " and r.sendDate between '"+startDt+"' and '"+endDt+"' and r.status="+RecipeStatusConstant.IN_SEND);
                Query q = ss.createQuery(hql.toString());
                setResult(q.list());
            }
        };
        HibernateSessionTemplate.instance().executeReadOnly(action);

        return action.getResult();
    }

}
