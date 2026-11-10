package com.easymall.controller.notify;

import com.easymall.entity.dto.PayOrderNotifyDTO;
import com.easymall.service.OrderInfoService;
import com.easymall.service.PayChannel;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.HashMap;
import java.util.Map;

/** 正式业务：接收支付宝服务端支付通知；验签及金额核对通过后才更新订单，失败允许渠道重试。 */
@RestController
@RequestMapping("/notify")
public class AlipayNotifyController {
    private static final Logger log = LoggerFactory.getLogger(AlipayNotifyController.class);
    @Resource(name = "payChannel4Alipay") private PayChannel alipay;
    @Resource private OrderInfoService orderInfoService;

    @PostMapping(value = "/alipayNotify", produces = MediaType.TEXT_PLAIN_VALUE)
    public String alipayNotify(HttpServletRequest request) {
        try {
            Map<String, String> params = new HashMap<>();
            for (var entry : request.getParameterMap().entrySet()) {
                if (entry.getValue().length != 1) return "failure";
                params.put(entry.getKey(), entry.getValue()[0]);
            }
            PayOrderNotifyDTO payment = alipay.payNotify(params, null);
            if (payment != null) orderInfoService.payOrderSuccess(payment);
            return "success";
        } catch (RuntimeException invalid) {
            log.warn("支付宝通知处理失败，exceptionType={}", invalid.getClass().getSimpleName());
            return "failure";
        }
    }
}
