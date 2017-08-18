package eh.cdr.service;

import ctd.persistence.DAOFactory;
import ctd.persistence.bean.QueryResult;
import ctd.persistence.exception.DAOException;
import ctd.util.BeanUtils;
import ctd.util.JSONUtils;
import ctd.util.annotation.RpcService;
import eh.base.constant.ErrorCode;
import eh.cdr.dao.DrugsEnterpriseDAO;
import eh.entity.cdr.DrugsEnterprise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;


/**
 * 药企相关接口
 * company: ngarihealth
 * author: 0184/yu_yun
 * date:2016/6/2.
 */
public class DrugsEnterpriseService {

    /** logger */
    private static final Logger logger = LoggerFactory.getLogger(DrugsEnterpriseService.class);

    /**
     * 有效药企查询 status为1
     *
     * @param status 药企状态
     * @return
     */
    @RpcService
    public List<DrugsEnterprise> findDrugsEnterpriseByStatus(final Integer status) {
        DrugsEnterpriseDAO drugsEnterpriseDAO = DAOFactory.getDAO(DrugsEnterpriseDAO.class);
        return drugsEnterpriseDAO.findAllDrugsEnterpriseByStatus(status);
    }

    /**
     * 新建药企
     *
     * @param drugsEnterprise
     * @return
     * @author houxr 2016-09-11
     */
    @RpcService
    public DrugsEnterprise addDrugsEnterprise(final DrugsEnterprise drugsEnterprise) {
        if (null == drugsEnterprise) {
            throw new DAOException(ErrorCode.SERVICE_ERROR, "DrugsEnterprise is null");
        }
        DrugsEnterpriseDAO drugsEnterpriseDAO = DAOFactory.getDAO(DrugsEnterpriseDAO.class);
        List<DrugsEnterprise> drugsEnterpriseList = drugsEnterpriseDAO.findAllDrugsEnterpriseByName(drugsEnterprise.getName());
        if (drugsEnterpriseList.size() != 0) {
            throw new DAOException(ErrorCode.SERVICE_ERROR, "DrugsEnterprise exist!");
        }
        DrugsEnterprise newDrugsEnterprise = drugsEnterpriseDAO.save(drugsEnterprise);
        return newDrugsEnterprise;
    }


    /**
     * 更新药企
     *
     * @param drugsEnterprise
     * @return
     * @author houxr 2016-09-11
     */
    @RpcService
    public DrugsEnterprise updateDrugsEnterprise(final DrugsEnterprise drugsEnterprise) {
        if (null == drugsEnterprise) {
            throw new DAOException(ErrorCode.SERVICE_ERROR, "DrugsEnterprise is null");
        }
        logger.info(JSONUtils.toString(drugsEnterprise));
        DrugsEnterpriseDAO drugsEnterpriseDAO = DAOFactory.getDAO(DrugsEnterpriseDAO.class);
        DrugsEnterprise target = drugsEnterpriseDAO.get(drugsEnterprise.getId());
        if (null == target) {
            throw new DAOException(DAOException.ENTITIY_NOT_FOUND, "DrugsEnterprise not exist!");
        }
        BeanUtils.map(drugsEnterprise, target);
        target = drugsEnterpriseDAO.update(target);
        return target;
    }

    /**
     * 根据药企名称分页查询药企
     *
     * @param name  药企名称
     * @param start 分页起始位置
     * @param limit 每页条数
     * @return
     * @author houxr 2016-09-11
     */
    @RpcService
    public QueryResult<DrugsEnterprise> queryDrugsEnterpriseByStartAndLimit(final String name, final int start, final int limit) {
        DrugsEnterpriseDAO drugsEnterpriseDAO = DAOFactory.getDAO(DrugsEnterpriseDAO.class);
        return drugsEnterpriseDAO.queryDrugsEnterpriseResultByStartAndLimit(name, start, limit);
    }

    @RpcService
    public List<DrugsEnterprise> findByOrganId(Integer organId){
        DrugsEnterpriseDAO drugsEnterpriseDAO = DAOFactory.getDAO(DrugsEnterpriseDAO.class);
        return drugsEnterpriseDAO.findByOrganId(organId);
    }
}
