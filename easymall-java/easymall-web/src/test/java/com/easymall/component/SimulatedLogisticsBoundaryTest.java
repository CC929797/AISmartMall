package com.easymall.component;

import com.easymall.entity.config.AppConfig;
import com.easymall.service.Impl.OrderLogisticsInfoServiceImpl;
import com.easymall.task.PayOrderTask;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SimulatedLogisticsBoundaryTest {
    @Test void disabledSimulationStopsBeforeAccessingAnyDependency() {
        AppConfig config = mock(AppConfig.class);
        when(config.getLogisticsMode()).thenReturn("disabled");
        var task = new PayOrderTask();
        ReflectionTestUtils.setField(task, "appConfig", config);
        task.consumeLogistics();
        task.consumeDeliveryOrder();
        var service = new OrderLogisticsInfoServiceImpl();
        ReflectionTestUtils.setField(service, "appConfig", config);
        service.mockOrderLogistics("fixture-order");
    }
    @Test void wrongOrMissingSimulationModeDoesNotStartWorkers() {
        AppConfig config = mock(AppConfig.class);
        when(config.getLogisticsMode()).thenReturn("typo");
        var task = new PayOrderTask(); ReflectionTestUtils.setField(task, "appConfig", config);
        task.consumeLogistics(); task.consumeDeliveryOrder();
    }
}
