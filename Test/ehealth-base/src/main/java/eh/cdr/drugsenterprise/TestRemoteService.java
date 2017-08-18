package eh.cdr.drugsenterprise;

import eh.cdr.bean.DepDetailBean;
import eh.cdr.bean.DrugEnterpriseResult;
import eh.entity.cdr.DrugsEnterprise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;

/**
 * 测试药企，测试某些场景下药企不支持的功能，如 中药库存校验等
 * company: ngarihealth
 * author: 0184/yu_yun
 * date:2017/6/22.
 */
public class TestRemoteService extends AccessDrugEnterpriseService{

    /** logger */
    private static final Logger logger = LoggerFactory.getLogger(TestRemoteService.class);

    @Override
    public DrugEnterpriseResult pushRecipeInfo(List<Integer> recipeIds) {
        logger.info("TestRemoteService pushRecipeInfo not implement.");
        return DrugEnterpriseResult.getSuccess();
    }

    @Override
    public DrugEnterpriseResult scanStock(Integer recipeId, DrugsEnterprise drugsEnterprise) {
        logger.info("TestRemoteService pushRecipeInfo not implement.");
        return DrugEnterpriseResult.getSuccess();
    }

    @Override
    public DrugEnterpriseResult syncEnterpriseDrug(DrugsEnterprise drugsEnterprise, List<Integer> drugIdList) {
        logger.info("TestRemoteService pushRecipeInfo not implement.");
        return DrugEnterpriseResult.getSuccess();
    }

    @Override
    public DrugEnterpriseResult pushCheckResult(Integer recipeId, Integer checkFlag) {
        logger.info("TestRemoteService pushRecipeInfo not implement.");
        return DrugEnterpriseResult.getSuccess();
    }

    @Override
    public DrugEnterpriseResult findSupportDep(List<Integer> recipeIds) {
        logger.info("TestRemoteService findSupportDep not implement.");
        return null;
    }

    @Override
    public String getDrugEnterpriseAccount() {
        return "test";
    }
}
