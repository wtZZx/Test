package eh.cdr.service;

import ctd.persistence.DAOFactory;
import ctd.util.annotation.RpcService;
import eh.cdr.dao.PathologicalDrugDAO;
import eh.entity.base.DrugList;
import eh.entity.cdr.PathologicalDrug;
import jersey.repackaged.com.google.common.collect.Lists;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * company: ngarihealth
 * author: 0184/yu_yun
 * date:2017/5/26.
 */
public class PathologicalDrugService {
    /** logger */
    private static final Logger logger = LoggerFactory.getLogger(PathologicalDrugService.class);

    @RpcService
    public List<DrugList> findDrugList(PathologicalDrug pDrug, int start, int limit){
        if(null == pDrug || null == pDrug.getPathologicalType()){
            return Lists.newArrayList();
        }
        PathologicalDrugDAO pathologicalDrugDAO = DAOFactory.getDAO(PathologicalDrugDAO.class);
        return pathologicalDrugDAO.findDrugList(pDrug.getPathologicalType(), start, limit);
    }

}
