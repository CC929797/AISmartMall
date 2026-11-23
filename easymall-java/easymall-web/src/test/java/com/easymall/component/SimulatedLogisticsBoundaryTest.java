package com.easymall.component;

import com.easymall.entity.config.AppConfig;
import com.easymall.service.Impl.OrderInfoServiceImpl;
import com.easymall.service.Impl.OrderLogisticsInfoServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** 仅用于测试，验证关闭模拟物流时，业务入口在访问数据库前直接结束。 */
class SimulatedLogisticsBoundaryTest {
    @Test void disabledSimulationStopsBeforeAccessingAnyDependency() {
        AppConfig config = new AppConfig();
        ReflectionTestUtils.setField(config, "logisticsMode", "disabled");
        var service = new OrderLogisticsInfoServiceImpl();
        ReflectionTestUtils.setField(service, "appConfig", config);
        service.mockOrderLogistics("fixture-order", 1);
        var orders = new OrderInfoServiceImpl();
        ReflectionTestUtils.setField(orders, "appConfig", config);
        orders.confirmSimulatedOrder("fixture-order");
    }
}
