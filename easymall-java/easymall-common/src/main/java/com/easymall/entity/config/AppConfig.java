package com.easymall.entity.config;

import com.easymall.utils.StringTools;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration("appConfig")
public class AppConfig {

    @Value("${admin.account:}")
    private String adminAccount;

    @Value("${admin.password:}")
    private String adminPassword;

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

    //订单超时
    @Value("${order.expire.minute:5}")
    private Integer orderExpireMinute;

    @Value("${project.auto-checkpay}")
    private Boolean autoCheckPay;

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
        if(!StringTools.isEmpty(projectFolder) && !projectFolder.endsWith("/")) {
            projectFolder += "/";
        }
        return projectFolder;
    }

    public String getAdminAccount() {
        return adminAccount;
    }

    public String getAdminPassword() {
        return adminPassword;
    }
}
