package com.easymall.service.Impl;

import com.alipay.api.AlipayClient;
import com.alipay.api.AlipayConfig;
import com.alipay.api.AlipayResponse;
import com.alipay.api.DefaultAlipayClient;
import com.alipay.api.domain.*;
import com.alipay.api.internal.util.AlipaySignature;
import com.alipay.api.request.*;
import com.alipay.api.response.*;
import com.easymall.entity.config.AppConfig;
import com.easymall.entity.dto.PayInfoDTO;
import com.easymall.entity.dto.PayOrderNotifyDTO;
import com.easymall.entity.enums.PayChannelEnum;
import com.easymall.exception.BusinessException;
import com.easymall.service.PayChannel;
import com.easymall.refund.RefundChannelResult;
import com.easymall.order.PaymentCloseResult;
import com.easymall.order.PaymentQueryResult;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service("payChannel4Alipay")
@org.springframework.context.annotation.Profile("!demo")
public class PayChannel4Alipay implements PayChannel {
    static {
        //设置SSL协议版本
        System.setProperty("https.protocols", "TLSv1.2");
        System.setProperty("jdk.tls.client.protocols", "TLSv1.2");
    }

    private static final String TRADE_STATE_SUCCESS = "TRADE_SUCCESS";

    private static final String TRADE_NOT_EXIST = "ACQ.TRADE_NOT_EXIST";
    private static final DateTimeFormatter ALIPAY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.of("Asia/Shanghai"));

    private static final Set<String> REFUND_REJECTIONS = Set.of(
            "ACQ.INVALID_PARAMETER", "ACQ.SELLER_BALANCE_NOT_ENOUGH",
            "ACQ.REFUND_AMT_NOT_EQUAL_TOTAL", "ACQ.REASON_TRADE_REFUND_FEE_ERR",
            "ACQ.TRADE_NOT_ALLOW_REFUND", "ACQ.TRADE_HAS_FINISHED");

    @Resource
    private AppConfig appConfig;
    @Resource
    private com.easymall.security.PaymentGatewayPolicy paymentGatewayPolicy;

    /**
     * 获取支付信息
     * @param payChannelEnum 支付渠道枚举
     * @param payOrderId 订单支付Id
     * @param subject 标题
     * @param amount 金额
     */
    @Override
    public PayInfoDTO getPayUrl(PayChannelEnum payChannelEnum, String payOrderId, String subject, BigDecimal amount,
                               Instant expiresAt) {
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
            // 同一支付单的每一份表单都使用商城的固定截止时间。再次获取表单不会延长付款窗口。
            model.setTimeExpire(ALIPAY_TIME.format(expiresAt));

            request.setBizModel(model);

            //实际开发设置异步通知回调，以便实时获取订单支付结果
            // 原配置中的xxx.xxxx.com只是占位域名；只有显式配置实际公网回调地址才发送。
            if (appConfig.getAlipayNotifyUrl() != null && !appConfig.getAlipayNotifyUrl().isBlank()) {
                request.setNotifyUrl(appConfig.getAlipayNotifyUrl());
            }

            String payInfo = null;
            switch (payChannelEnum) {
                case ALIPAY_PC :
                    model.setProductCode("FAST_INSTANT_TRADE_PAY");
                    //获取到支付结果
                    AlipayTradePagePayResponse response = alipayClient.pageExecute(request);
                    if (!response.isSuccess()) {
                        throw new BusinessException("获取信息失败");
                    }
                    payInfo = response.getBody();
                    break;
            }
            return new PayInfoDTO(payInfo, payOrderId, amount);
        }catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("支付宝获取支付信息失败，异常类型={}", e.getClass().getSimpleName());
            throw new BusinessException("支付宝获取支付信息失败");
        }
    }

    private AlipayConfig getAlipayConfig() {
        // 验证支付宝服务器地址
        paymentGatewayPolicy.validate(appConfig.getAlipayServerUrl());
        AlipayConfig alipayConfig = new AlipayConfig();
        alipayConfig.setServerUrl(appConfig.getAlipayServerUrl());
        alipayConfig.setAppId(appConfig.getAlipayAppid());
        alipayConfig.setPrivateKey(appConfig.getAlipayAppPrivateKey());

        alipayConfig.setAlipayPublicCertPath(appConfig.getProjectFolder() + appConfig.getAlipayPublicCertPath());
        alipayConfig.setRootCertPath(appConfig.getProjectFolder() + appConfig.getAlipayRootCertPath());
        alipayConfig.setAppCertPath(appConfig.getProjectFolder() + appConfig.getAlipayAppCertPath());

        alipayConfig.setCharset("UTF8");
        alipayConfig.setSignType("RSA2");
        return alipayConfig;
    }

    /**
     * 异步通知
     * @param requestParams 请求参数
     * @param jsonBody json对象
     */
    @Override
    public PayOrderNotifyDTO payNotify(Map<String, String> requestParams, String jsonBody) {

        try {
            // 支付通知使用支付宝公钥证书验签；应用证书只用于请求身份，不能用于验证支付宝签名。
            Boolean signCheckResult = AlipaySignature.rsaCertCheckV1(new java.util.HashMap<>(requestParams),
                    appConfig.getProjectFolder() + appConfig.getAlipayPublicCertPath(), "UTF-8", "RSA2");
            if (!signCheckResult) {
                throw new BusinessException("支付宝回调验签失败");
            }
        } catch (Exception e) {
            throw new BusinessException("支付宝回调验签失败");
        }

        if (!appConfig.getAlipayAppid().equals(requestParams.get("app_id"))) {
            throw new BusinessException("支付宝回调应用不匹配");
        }
        // app_id 标识应用，seller_id 标识收款账户。两者均需与本机配置匹配。
        String sellerId = appConfig.getAlipaySellerId();
        if (sellerId == null || sellerId.isBlank() || !sellerId.equals(requestParams.get("seller_id"))) {
            throw new BusinessException("支付宝回调收款方不匹配或未配置");
        }

        String payOrderId = requestParams.get("out_trade_no");
        String channelOrderId = requestParams.get("trade_no");
        String status = String.valueOf(requestParams.get("trade_status"));

        if (!TRADE_STATE_SUCCESS.equals(status) && !"TRADE_FINISHED".equals(status)) {
            return null;
        }
        if (invalidOrderId(payOrderId) || invalidOrderId(channelOrderId)) {
            throw new BusinessException("支付宝回调订单号无效");
        }
        PayOrderNotifyDTO result = new PayOrderNotifyDTO(payOrderId,channelOrderId);
        result.setAmount(channelAmount(requestParams.get("total_amount")));
        result.setTradeStatus(status);
        result.setAppId(requestParams.get("app_id"));
        result.setSellerId(sellerId);
        return result;
    }

    /**
     * 查询
     * @param payOrderId
     * @return
     */
    @Override
    public PaymentQueryResult queryPayment(String payOrderId) {

        try {
            AlipayClient alipayClient = new DefaultAlipayClient(getAlipayConfig());

            //构造调用的接口
            AlipayTradeQueryRequest request = new AlipayTradeQueryRequest();
            AlipayTradeQueryModel model = new AlipayTradeQueryModel();
            model.setOutTradeNo(payOrderId);

            request.setBizModel(model);

            AlipayTradeQueryResponse response = alipayClient.certificateExecute(request);
            if (!response.isSuccess()) {
                if (tradeNotFound(response, payOrderId, response.getOutTradeNo())) {
                    return PaymentQueryResult.of(PaymentQueryResult.Status.NOT_FOUND);
                }
                log.warn("支付宝查单未成功，code={}, subCode={}", safeCode(response.getCode()), safeCode(response.getSubCode()));
                return PaymentQueryResult.of(PaymentQueryResult.Status.UNKNOWN);
            }
            // 使用渠道返回的商户订单号校验关联关系，再构造本地付款凭证。
            if (!payOrderId.equals(response.getOutTradeNo())) {
                return PaymentQueryResult.of(PaymentQueryResult.Status.UNKNOWN);
            }
            String status = response.getTradeStatus();
            if ((TRADE_STATE_SUCCESS.equals(status) || "TRADE_FINISHED".equals(status))
                    && !invalidOrderId(response.getTradeNo())) {
                PayOrderNotifyDTO result = new PayOrderNotifyDTO(response.getOutTradeNo(), response.getTradeNo());
                result.setAmount(channelAmount(response.getTotalAmount()));
                result.setTradeStatus(status);
                result.setAppId(appConfig.getAlipayAppid());
                return PaymentQueryResult.paid(result);
            }
            if ("WAIT_BUYER_PAY".equals(status)) {
                return PaymentQueryResult.of(PaymentQueryResult.Status.WAITING);
            }
            // TRADE_CLOSED 也可能由已付款交易全额退款产生。仅有明确的零实付证据才释放库存。
            if ("TRADE_CLOSED".equals(status) && response.getSendPayDate() == null
                    && response.getBuyerPayAmount() != null && new BigDecimal(response.getBuyerPayAmount()).signum() == 0
                    && response.getReceiptAmount() != null && new BigDecimal(response.getReceiptAmount()).signum() == 0) {
                return PaymentQueryResult.of(PaymentQueryResult.Status.CLOSED_UNPAID);
            }
        } catch (Exception e) {
            log.warn("查询支付宝订单结果待确认，异常类型={}", e.getClass().getSimpleName());
        }
        return PaymentQueryResult.of(PaymentQueryResult.Status.UNKNOWN);
    }

    private static boolean invalidOrderId(String value) {
        return value == null || !value.matches("[A-Za-z0-9_-]{1,64}");
    }

    private static BigDecimal channelAmount(String value) {
        try {
            BigDecimal amount = new BigDecimal(value);
            if (amount.signum() <= 0 || amount.scale() > 2) throw new IllegalArgumentException();
            return amount;
        } catch (RuntimeException invalid) { throw new BusinessException("支付宝支付金额无效"); }
    }

    private static String safeCode(String value) {
        return value != null && value.matches("[A-Za-z0-9_.-]{1,80}") ? value : "UNKNOWN";
    }

    /**
     * 退款
     * @param sourcePayOrderId 要退款的订单支付Id
     * @param payOrderId 退款时生成的Id
     * @param refundAmount 退款金额
     */
    @Override
    public RefundChannelResult refund(String sourcePayOrderId, String payOrderId, BigDecimal refundAmount) {
        try {
            AlipayClient alipayClient = new DefaultAlipayClient(getAlipayConfig());

            //构造调用的接口
            AlipayTradeRefundRequest request = new AlipayTradeRefundRequest();
            AlipayTradeRefundModel model = new AlipayTradeRefundModel();
            model.setOutTradeNo(sourcePayOrderId);
            model.setOutRequestNo(payOrderId);
            model.setRefundAmount(refundAmount.toPlainString());

            request.setBizModel(model);

            AlipayTradeRefundResponse response = alipayClient.certificateExecute(request);
            if (response.isSuccess() && "Y".equals(response.getFundChange())
                    && sourcePayOrderId.equals(response.getOutTradeNo())) {
                return RefundChannelResult.SUCCESS;
            }
            if ("40004".equals(response.getCode()) && response.getSubCode() != null
                    && REFUND_REJECTIONS.contains(response.getSubCode())) {
                return RefundChannelResult.REJECTED;
            }
            log.warn("退款结果待确认，code={}, subCode={}", safeCode(response.getCode()), safeCode(response.getSubCode()));
        } catch (Exception unavailable) {
            log.warn("退款调用结果待确认，异常类型={}", unavailable.getClass().getSimpleName());
        }
        return RefundChannelResult.UNKNOWN;
    }

    /**
     * 查询退款
     * @param sourcePayOrderId 原订单支付Id
     * @param refundRequestNo 退款请求号
     * @param amount 退款金额
     * @return 退款结果
     */
    @Override
    public RefundChannelResult queryRefund(String sourcePayOrderId, String refundRequestNo, BigDecimal amount) {
        try {
            AlipayClient client = new DefaultAlipayClient(getAlipayConfig());
            AlipayTradeFastpayRefundQueryModel model = new AlipayTradeFastpayRefundQueryModel();
            model.setOutTradeNo(sourcePayOrderId);
            model.setOutRequestNo(refundRequestNo);
            AlipayTradeFastpayRefundQueryRequest request = new AlipayTradeFastpayRefundQueryRequest();
            request.setBizModel(model);
            AlipayTradeFastpayRefundQueryResponse response = client.certificateExecute(request);
            if (!response.isSuccess() || !sourcePayOrderId.equals(response.getOutTradeNo())) {
                return RefundChannelResult.UNKNOWN;
            }
            if ("REFUND_SUCCESS".equals(response.getRefundStatus()) && refundRequestNo.equals(response.getOutRequestNo())
                    && amount.compareTo(new BigDecimal(response.getRefundAmount())) == 0) {
                return RefundChannelResult.SUCCESS;
            }
            // 查到交易但没有对应退款明细时，只允许使用原渠道号继续。
            if (response.getRefundStatus() == null && response.getRefundAmount() == null
                    && (response.getOutRequestNo() == null || refundRequestNo.equals(response.getOutRequestNo()))) {
                return RefundChannelResult.NOT_FOUND;
            }
        } catch (Exception unavailable) {
            log.warn("退款查询结果待确认，异常类型={}", unavailable.getClass().getSimpleName());
        }
        return RefundChannelResult.UNKNOWN;
    }

    /** 关闭指定支付单。只有支付宝明确返回交易不存在，才能参与过期表单的补偿判断。 */
    @Override
    public PaymentCloseResult closeOrder(String payOrderId) {
        try {
            AlipayClient alipayClient = new DefaultAlipayClient(getAlipayConfig());

            //构造调用的接口
            AlipayTradeCloseRequest request = new AlipayTradeCloseRequest();
            AlipayTradeCloseModel model = new AlipayTradeCloseModel();
            model.setOutTradeNo(payOrderId);

            request.setBizModel(model);

            AlipayTradeCloseResponse response = alipayClient.certificateExecute(request);
            if (response.isSuccess() && payOrderId.equals(response.getOutTradeNo())) {
                return PaymentCloseResult.CLOSED;
            }
            if (tradeNotFound(response, payOrderId, response.getOutTradeNo())) {
                return PaymentCloseResult.NOT_FOUND;
            }
        } catch (Exception e) {
            log.warn("支付宝关单结果待确认，异常类型={}", e.getClass().getSimpleName());
        }
        return PaymentCloseResult.UNKNOWN;
    }

    @Override
    public boolean supportsClosingExpiredMissingTrades() {
        // 目前在沙箱启用此补偿，真实支付仍按渠道终态收尾。
        return paymentGatewayPolicy.isSandbox();
    }

    private static boolean tradeNotFound(AlipayResponse response, String payOrderId, String responseOrderId) {
        // 40004 是通用业务失败码，只有子错误码明确为交易不存在，才算查无此单。
        // 支付宝错误应答可以省略商户单号。如果回传了单号，必须与本次请求匹配。
        return "40004".equals(response.getCode()) && TRADE_NOT_EXIST.equals(response.getSubCode())
                && (responseOrderId == null || responseOrderId.isBlank() || payOrderId.equals(responseOrderId));
    }
}
