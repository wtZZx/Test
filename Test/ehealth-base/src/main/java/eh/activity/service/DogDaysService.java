package eh.activity.service;

import ctd.util.BeanUtils;
import ctd.util.JSONUtils;
import  org.apache.commons.lang3.StringUtils;
import com.google.common.collect.Maps;
import ctd.dictionary.DictionaryController;
import ctd.persistence.DAOFactory;
import ctd.util.annotation.RpcService;
import ctd.util.converter.ConversionUtils;
import eh.base.constant.DoctorWhiteConstant;
import eh.base.dao.DoctorWhiteListDAO;
import eh.base.dao.EmploymentDAO;
import eh.base.dao.OrganDAO;
import eh.base.dao.QueryDoctorListDAO;
import eh.entity.base.Doctor;
import eh.entity.base.Employment;
import eh.entity.base.Organ;
import eh.utils.params.ParamUtils;
import eh.utils.params.ParameterConstant;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.util.*;

/**
 * 三伏天活动相关接口
 */
public class DogDaysService {
    private static final Log logger = LogFactory.getLog(DogDaysService.class);

    /**
     * 获取三伏天活动医生列表
     * @param organId 机构id
     * @param start
     * @param limit
     * @return
     */
    @RpcService
    public List<Map<String,Object>> findDogDaysDoctors(Integer organId,Integer start,Integer limit){
        QueryDoctorListDAO dao=DAOFactory.getDAO(QueryDoctorListDAO.class);
        EmploymentDAO empDao=DAOFactory.getDAO(EmploymentDAO.class);
        OrganDAO organDao=DAOFactory.getDAO(OrganDAO.class);

        List<Integer> organIds=new ArrayList<>();
        Organ organ=organDao.get(organId);
        if(organ!=null && !StringUtils.isEmpty(organ.getManageUnit())){
            organIds=organDao.findEffOrganIdsByManageUnit(organ.getManageUnit()+"%");
        }

        logger.info("三伏天活动-管理机构" + organId+ "下含机构："+ JSONUtils.toString(organIds));

        Integer dogDaysType= DoctorWhiteConstant.WHITELIST_TYPE_ACTIVITY_DOGDAYS;

        List<Doctor> doctorList=new ArrayList<>();
        if(!organIds.isEmpty()){
            doctorList=dao.findWhiteDoctorListByOrganId(organIds,dogDaysType,start,limit);
        }

        Map<String,Map<String, Map<String,Map<String,Object>>>> returnMap=Maps.newLinkedHashMap();
        for (Doctor doc:doctorList){
            Integer doctorId=doc.getDoctorId();
            Employment emp=empDao.getPrimaryEmpByDoctorId(doctorId);
            if(emp==null){
                continue;
            }

            Integer docOrganId=doc.getOrgan()==null?0:doc.getOrgan();
            String  organShortName=organDao.getShortNameById(docOrganId);
            String organName=organDao.getShortNameById(organId);

            Map<String, Map<String,Map<String,Object>>> map=Maps.newLinkedHashMap();
            if(returnMap.containsKey(organName)){
                map=returnMap.get(organName);
            }

            String deptName="";
            try{
                deptName=DictionaryController.instance()
                        .get("eh.base.dictionary.Depart")
                        .getText(emp.getDepartment()==null?0:emp.getDepartment());
            }catch (Exception e){
                continue;
            }

            //广西妇幼参加活动的4个医生，在不同的院区创建了两个账户，但是前端只需要展示一个医生信息
            if(map.containsKey(deptName)){
                Map<String,Map<String,Object>> listMap=map.get(deptName);
                getDocList(listMap,doc,organShortName);
            }else {
                Map<String,Map<String,Object>> listMap=Maps.newLinkedHashMap();
                getDocList(listMap,doc,organShortName);
                map.put(deptName,listMap);
            }

            returnMap.put(organName,map);
        }


        return converDocMapToList(organId,returnMap);
    }

    private void getDocList(Map<String,Map<String,Object>> listMap,Doctor doc,String organShortName){
        String docName=doc.getName();
        String organName=organShortName==null?"":StringUtils.substringBetween(organShortName,"（","）");
        organName=organName==null?StringUtils.substringBetween(organShortName,"(",")"):organName;

        //列表中已含有医生信息
        if(listMap.containsKey(docName)){
            Map<String,Object> oldDoc=listMap.get(docName);

            List<Map<String,Object>> extInfoList=(List)oldDoc.get("extInfoList");
            if(extInfoList==null){
                extInfoList=new ArrayList<>();
            }

            Map<String,Object> extInfo=Maps.newHashMap();
            extInfo.put("extDoctorId",doc.getDoctorId());
            extInfo.put("extOrganName",StringUtils.isEmpty(organName)?organShortName:organName);
            extInfoList.add(extInfo);

        }else {
            Map<String,Object> docMap=getPartDocInfo(doc);

            List<Map<String,Object>> extInfoList=new ArrayList<>();
            Map<String,Object> extInfo=Maps.newHashMap();
            extInfo.put("extDoctorId",doc.getDoctorId());
            extInfo.put("extOrganName",StringUtils.isEmpty(organName)?organShortName:organName);
            extInfoList.add(extInfo);

            docMap.put("extInfoList",extInfoList);
            listMap.put(docName,docMap);
        }
    }

    private List<Map<String,Object>> converDocMapToList(Integer organId,Map<String,Map<String, Map<String,Map<String,Object>>>> map){
        List<Map<String,Object>> returnList=new ArrayList<>();

        Iterator<String> its = map.keySet().iterator();
        while (its.hasNext()) {
            String key = its.next();
            Map<String,Object> organList= Maps.newLinkedHashMap();
            organList.put("organId",organId);
            organList.put("organName",key);

            List<Map<String,Object>> deptList=new ArrayList<>();

            //组装每家医院的数据
            Map<String, Map<String,Map<String,Object>>> deptMap=map.get(key);
            Iterator<String> deptMapIts = deptMap.keySet().iterator();
            while (deptMapIts.hasNext()) {
                Map<String,Object> docMap=Maps.newLinkedHashMap();

                String deptName = deptMapIts.next();
                docMap.put("deptName",deptName);

                Map<String,Map<String,Object>> docListMap= deptMap.get(deptName);
                List<Map<String,Object>> docList=new ArrayList<>();

                Iterator<String> docListMapIts = docListMap.keySet().iterator();
                while (docListMapIts.hasNext()) {
                    Map<String,Object> docInfo=docListMap.get(docListMapIts.next());
                    docList.add(docInfo);
                }
                docMap.put("docList",docList);

                deptList.add(docMap);
            }

            organList.put("deptList",deptList);

            returnList.add(organList);
        }

        return returnList;
    }

    /**
     * 判断医生是否参加活动
     * @param doctorId
     * @return true参加三伏天活动 false 不参加三伏天活动
     */
    public Boolean getDogDaysFlag(Integer doctorId){
        Boolean dogDaysFlag=false;
        String activityEndTime= ParamUtils.getParam(ParameterConstant.KEY_ACTIVITY_DOGDAYS_ENDTIME,"2017-08-21 00:00:00");
        Date endTime= ConversionUtils.convert(activityEndTime, Date.class);
        Date now=new Date();

        //已过活动结束时间
        if(now.after(endTime)){
            return dogDaysFlag;
        }

        //是否在冬病夏治白名单中
        Integer dogDaysType= DoctorWhiteConstant.WHITELIST_TYPE_ACTIVITY_DOGDAYS;
        DoctorWhiteListDAO whiteDao=DAOFactory.getDAO(DoctorWhiteListDAO.class);
        if(whiteDao.getByTypeAndDoctorId(dogDaysType,doctorId)!=null){
            dogDaysFlag=true;
        }

        return dogDaysFlag;
    }

    /**
     * 截取医生的部分信息
     * @param doc
     * @return
     */
    public Map<String,Object> getPartDocInfo(Doctor doc){
        Doctor doctor=new Doctor();
        doctor.setDoctorId(doc.getDoctorId());
        doctor.setName(doc.getName());
        doctor.setVirtualDoctor(doc.getVirtualDoctor()==null?false:doc.getVirtualDoctor());
        doctor.setDomain(doc.getDomain());
        doctor.setProTitle(doc.getProTitle());

        Map<String,Object> map= Maps.newHashMap();
        BeanUtils.map(doctor,map);
        return map;
    }

}
