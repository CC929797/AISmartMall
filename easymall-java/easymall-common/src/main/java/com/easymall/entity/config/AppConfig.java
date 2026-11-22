package com.easymall.entity.config;

import com.easymall.utils.StringTools;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration("appConfig")
public class AppConfig {
    /**
     * websocket 端口
     */
    @Value("${ws.port:}")
    private Integer wsPort;

    @Value("${admin.account:}")
    private String adminAccount;

    @Value("${admin.password-hash:}")
    private String adminPasswordHash;

    @Value("${project.folder:}")
    private String projectFolder;

    @Value("${project.domain:}")
    private String projectDomain;

    //支付宝应用私钥
    @Value("${alipay.appPrivateKey:}")
    private String alipayAppPrivateKey;

    @Value("${alipay.appid:}")
    private String alipayAppid;

    @Value("${alipay.appCertPath:}")
    private String alipayAppCertPath;

    @Value("${alipay.alipayPublicCertPath:}")
    private String alipayPublicCertPath;

    @Value("${alipay.alipayRootCertPath:}")
    private String alipayRootCertPath;


    @Value("${alipay.serverUrl:}")
    private String alipayServerUrl;

    /** 正式业务：可选的公网异步通知地址；本地未配置时依靠主动查单，不发送占位域名。 */
    @Value("${alipay.notify-url:}")
    private String alipayNotifyUrl;

    public String getAlipayNotifyUrl() {
        return alipayNotifyUrl;
    }

    /** 回调中预期的支付宝收款方 PID，通过本机配置或环境变量提供。 */
    @Value("${alipay.seller-id:}")
    private String alipaySellerId;

    public String getAlipaySellerId() {
        return alipaySellerId;
    }

    //订单延时
    @Value("${order.expire.minute:5}")
    private Integer orderExpireMinute;

    //自动确认收货
    @Value("${order.confirm.minute:15}")
    private Integer orderConfirmMinute;

    @Value("${project.auto-checkpay}")
    private Boolean autoCheckPay;

    // 物流模式由当前环境的配置决定。显式设为 simulated 时，才会启用模拟发货、轨迹和自动确认收货。
    @Value("${logistics.mode:}")
    private String logisticsMode;

    public String getLogisticsMode() {
        return logisticsMode;
    }

    public boolean isSimulatedLogistics() {
        return "simulated".equals(getLogisticsMode());
    }

    public Integer getWsPort() {
        return wsPort;
    }

    public Integer getOrderConfirmMinute() {
        return orderConfirmMinute;
    }

    public Boolean getAutoCheckPay() {
        return autoCheckPay;
    }

    public String getProjectDomain() {
        return projectDomain;
    }

    public Integer getOrderExpireMinute() {
        return orderExpireMinute;
    }

    public String getAlipayAppPrivateKey() {
        return alipayAppPrivateKey;
    }

    public String getAlipayAppid() {
        return alipayAppid;
    }

    public String getAlipayAppCertPath() {
        return alipayAppCertPath;
    }

    public String getAlipayPublicCertPath() {
        return alipayPublicCertPath;
    }

    public String getAlipayRootCertPath() {
        return alipayRootCertPath;
    }

    public String getAlipayServerUrl() {
        return alipayServerUrl;
    }

    public String getProjectFolder() {
        if (!StringTools.isEmpty(projectFolder) && !projectFolder.endsWith("/")) {
            projectFolder += "/";
        }
        return projectFolder;
    }

    public String getAdminAccount() {
        return adminAccount;
    }

    public String getAdminPasswordHash() {
        return adminPasswordHash;
    }
}
