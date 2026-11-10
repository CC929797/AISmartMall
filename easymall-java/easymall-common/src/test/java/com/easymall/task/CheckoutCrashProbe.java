package com.easymall.task;

import com.easymall.order.CheckoutInput;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 【仅用于测试验证，不参与正式业务运行】模拟下单进程在业务提交前/提交后突然硬退出。 */
public final class CheckoutCrashProbe {
    public static void main(String[] args) throws Exception {
        CheckoutMySqlFixture f = new CheckoutMySqlFixture();
        var order = f.order(1); order.setAddressId(args[2]); order.setRequestId(args[5]);
        order.setOrderList(java.util.List.of(CheckoutMySqlFixture.item(args[3], args[4], 1)));
        if ("before".equals(args[0])) {
            f.requestService.place(args[1], CheckoutInput.normalize(order), payId -> { Runtime.getRuntime().halt(24); return null; });
        } else {
            when(f.payment.getPayUrl(any(), anyString(), anyString(), any())).thenAnswer(call -> { Runtime.getRuntime().halt(25); return null; });
            f.service.postOrder(args[1], order);
        }
    }
    private CheckoutCrashProbe() { }
}
