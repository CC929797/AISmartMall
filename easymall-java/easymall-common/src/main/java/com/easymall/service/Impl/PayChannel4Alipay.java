package com.easymall.service.Impl;

import com.alipay.api.AlipayClient;
import com.alipay.api.AlipayConfig;
import com.alipay.api.DefaultAlipayClient;
import com.alipay.api.domain.AlipayTradePagePayModel;
import com.alipay.api.domain.AlipayTradePayModel;
import com.alipay.api.domain.AlipayTradeWapPayModel;
import com.alipay.api.request.AlipayTradePagePayRequest;
import com.alipay.api.request.AlipayTradePayRequest;
import com.alipay.api.request.AlipayTradeWapPayRequest;
import com.alipay.api.response.AlipayTradePagePayResponse;
import com.alipay.api.response.AlipayTradePayResponse;
import com.alipay.api.response.AlipayTradeWapPayResponse;
import com.easymall.entity.config.AppConfig;
import com.easymall.entity.dto.PayInfoDTO;
import com.easymall.entity.dto.PayOrderNotifyDTO;
import com.easymall.entity.enums.DateTimePatternEnum;
import com.easymall.entity.enums.PayChannelEnum;
import com.easymall.exception.BusinessException;
import com.easymall.service.PayChannel;
import com.easymall.utils.DateUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Map;

@Slf4j
@Service("payChannel4Alipay")
public class PayChannel4Alipay implements PayChannel {
    static {
        //设置SSL协议版本
        System.setProperty("https.protocols", "TLSv1.2");
        System.setProperty("jdk.tls.client.protocols", "TLSv1.2");
    }

    private static final String TRADE_SUCCESS = "TRADE_SUCCESS";

    private static final String TRADE_NOT_EXIST = "ACQ.TRADE_NOT_EXIST";

    private static final String NOTIFY_URL = "/api/notify/alipayNotify";

    @Resource
    private AppConfig appConfig;

    /**
     * 获取支付信息
     * @param payChannelEnum 支付渠道枚举
     * @param payOrderId 订单支付Id
     * @param subject 标题
     * @param amount 金额
     */
    @Override
    public PayInfoDTO getPayUrl(PayChannelEnum payChannelEnum, String payOrderId, String subject, BigDecimal amount) {
        try {
            //初始化SDK
            AlipayClient alipayClient = new DefaultAlipayClient(getAlipayConfig());

            // 构造请求参数以调用接口
            AlipayTradePagePayRequest request = new AlipayTradePagePayRequest();
            AlipayTradePagePayModel model = new AlipayTradePagePayModel();
            //设置商户订单号
            model.setOutTradeNo(payOrderId);
            //设置订单总金额
            model.setTotalAmount(amount.toString());
            //设置订单标题
            model.setSubject(subject);
            //设置订单过期时间
            model.setTimeExpire(DateUtil.getMinAfter(appConfig.getOrderExpireMinute(), DateTimePatternEnum.YYYY_MM_DD_HH_MM_SS.getPattern()));

            request.setBizModel(model);

            //实际开发设置异步通知回调，以便实时获取订单支付结果
            request.setNotifyUrl(appConfig.getProjectDomain() + NOTIFY_URL);

            String payInfo = null;
            switch (payChannelEnum) {
                case ALIPAY_PC :
                    model.setProductCode("FAST_INSTANT_TRADE_PAY");
                    //获取到支付结果
                    AlipayTradePagePayResponse response = alipayClient.pageExecute(request);
                    if(!response.isSuccess()){
                        throw new BusinessException("获取信息失败");
                    }
                    payInfo = response.getBody();
                    break;
            }
            return new PayInfoDTO(payInfo, payOrderId, amount);
        }catch (BusinessException e){
            throw e;
        } catch (Exception e) {
            log.error("支付宝获取支付信息失败",e);
            throw new BusinessException("支付宝获取支付信息失败");
        }
    }

    private AlipayConfig getAlipayConfig(){
        AlipayConfig alipayConfig = new AlipayConfig();
        alipayConfig.setServerUrl(appConfig.getAlipayServerUrl());
        alipayConfig.setAppId(appConfig.getAlipayAppid());
        alipayConfig.setPrivateKey(appConfig.getAlipayAppPrivateKey());

        alipayConfig.setAlipayPublicCertPath(appConfig.getProjectFolder() + appConfig.getAlipayPublicCertPath());
        alipayConfig.setRootCertPath(appConfig.getProjectFolder() + appConfig.getAlipayRootCertPath());
        alipayConfig.setAppCertPath(appConfig.getProjectFolder() + appConfig.getAlipayAppCertPath());

        alipayConfig.setCharset("UTF-8");
        alipayConfig.setSignType("RSA2");
        return alipayConfig;
    }

    @Override
    public PayOrderNotifyDTO payNotify(Map<String, String> requestParams, String jsonBody) {
        return null;
    }

    @Override
    public PayOrderNotifyDTO queryOrder(String payOrderId) {
        return null;
    }

    @Override
    public void refund(String sourcePayOrderId, String payOrderId, BigDecimal refundAmount) {

    }

    @Override
    public void closeOrder(String payOrderId) {

    }
}
