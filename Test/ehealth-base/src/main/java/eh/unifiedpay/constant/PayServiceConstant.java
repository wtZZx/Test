package eh.unifiedpay.constant;

/**
 * Created by IntelliJ IDEA.
 * Description:
 * User: xiangyf
 * Date: 2017-04-24 11:16.
 */
public class PayServiceConstant {
    public static final String ORDER_PAY  = "order.pay";
    public static final String ORDER_QUERY = "order.query";
    public static final String ORDER_CLOSE = "order.close";
    public static final String ORDER_REFUND = "order.refund";
    public static final String ORDER_REFUND_QUERY = "order.refund.query";
    public static final String ORDER_CANCEL = "order.cancel";

    public static final Integer ALIPAY = 1;//支付宝
    public static final Integer WXPAY = 2;//微信
    public static final Integer CMBPAY = 3;//一网通

    public static final Integer FALL = 1;
}
