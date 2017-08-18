package eh.cdr.thread;

import ctd.persistence.DAOFactory;
import ctd.util.JSONUtils;
import eh.cdr.dao.DrugsEnterpriseDAO;
import eh.entity.cdr.DrugsEnterprise;
import eh.util.HttpHelper;
import eh.utils.MapValueUtil;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * 更新药企token Callable
 * company: ngarihealth
 * author: 0184/yu_yun
 * date:2016/6/15.
 */
public class UpdateDrugsEpCallable implements Callable<String>{

    private Logger logger = LoggerFactory.getLogger(UpdateDrugsEpCallable.class);

    private Integer _drugsEnterpriseId;

    public UpdateDrugsEpCallable(Integer drugsEnterpriseId){
        this._drugsEnterpriseId = drugsEnterpriseId;
    }

    @Override
    public String call() throws Exception {

        if(null == this._drugsEnterpriseId){
            return null;
        }

        String logPrefix = "UpdateDrugsEpCallable 更新药企token功能，药企ID:"+this._drugsEnterpriseId+"***";

        DrugsEnterpriseDAO drugsEnterpriseDAO = DAOFactory.getDAO(DrugsEnterpriseDAO.class);
        DrugsEnterprise drugsEnterprise = drugsEnterpriseDAO.getById(this._drugsEnterpriseId);

        if(null != drugsEnterprise && StringUtils.isNotEmpty(drugsEnterprise.getAuthenUrl())) {
            Map<String, Object> map = new HashMap<>();
            map.put("userid", drugsEnterprise.getUserId());
            map.put("password", drugsEnterprise.getPassword());

            try {
                String backMsg = HttpHelper.doPost(drugsEnterprise.getAuthenUrl(), JSONUtils.toString(map));
                logger.info(logPrefix+"药企返回："+backMsg);
                if(StringUtils.isNotEmpty(backMsg)) {
                    Map backMap = JSONUtils.parse(backMsg, Map.class);
                    // code 1成功
                    if (1 == MapValueUtil.getInteger(backMap,"code")) {
                        //成功
                        String token = MapValueUtil.getString(backMap, "access_token");
                        logger.debug(logPrefix+"token:" + token);
                        if (StringUtils.isNotEmpty(token)) {
                            drugsEnterpriseDAO.updateTokenById(this._drugsEnterpriseId, token);
                        }
                    } else {
                        logger.error(logPrefix+"更新失败,msg:" + MapValueUtil.getString(backMap, "message"));
                    }
                }
            } catch (IOException e) {
                logger.error(logPrefix+"更新失败:"+e.getMessage());
            }
        }else{
            logger.error(logPrefix+"药企 AuthenUrl为空");
        }

        return null;
    }
}
