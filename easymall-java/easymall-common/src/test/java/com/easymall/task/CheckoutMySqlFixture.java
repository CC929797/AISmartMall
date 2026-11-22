package com.easymall.task;

import com.easymall.commonent.RedisComponent;
import com.easymall.commonent.SpringContext;
import com.easymall.entity.config.AppConfig;
import com.easymall.entity.dto.*;
import com.easymall.mappers.*;
import com.easymall.order.OrderRequestService;
import com.easymall.service.Impl.OrderInfoServiceImpl;
import com.easymall.service.PayChannel;
import com.easymall.utils.StringTools;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import static org.mockito.Mockito.*;

/**
 * 【仅用于测试验证，不参与正式业务运行】复用11A固定隔离库连接，装配真实订单Mapper和事务服务。
 * 每次创建独立合成商品/SKU/地址；Redis和支付均为替身，不能访问真实网关，不删除任何记录。
 */
@SuppressWarnings({"rawtypes", "unchecked"})
final class CheckoutMySqlFixture implements AutoCloseable {
    final TaskMySqlFixture db = new TaskMySqlFixture();
    final String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
    final String user = "U8" + suffix, product = "P8" + suffix, property = "V8" + suffix, address = "A8" + suffix;
    final String skuHash = StringTools.encodeByMD5(property);
    final OrderInfoServiceImpl service = new OrderInfoServiceImpl();
    final PayChannel payment = mock(PayChannel.class);
    final RedisComponent redis = mock(RedisComponent.class);
    final OrderRequestMapper requests;
    final SqlSessionTemplate session;
    final OrderRequestService requestService;
    final ProductSkuMapper skus;
    final OrderItemMapper items;
    final Object previousContext;
    final GenericApplicationContext paymentContext = new GenericApplicationContext();

    CheckoutMySqlFixture() throws Exception {
        if (!"true".equals(System.getProperty("order.mysql.integration"))) throw new IllegalStateException("Explicit order.mysql.integration required");
        if (db.jdbc.queryForObject("select count(*) from information_schema.tables where table_schema=database() and table_name='order_request'", Integer.class) == 0) {
            try (var connection = db.dataSource.getConnection()) {
                ScriptUtils.executeSqlScript(connection, new FileSystemResource(db.envFile.getParent().getParent().getParent().resolve("easymall-java/db/migration/V20261107_02__order_request.sql")));
            }
        }
        if (db.jdbc.queryForObject("select count(*) from information_schema.statistics where table_schema=database() and table_name='order_request' and index_name='PRIMARY'", Integer.class) != 2) throw new IllegalStateException("Request unique identity missing");
        // 测试使用与开发库相同的迁移脚本，在固定隔离库检查是否已执行。
        if (db.jdbc.queryForObject("select count(*) from information_schema.columns where table_schema=database() and table_name='order_info' and column_name='payment_close_check_at'", Integer.class) == 0) {
            var migration = db.envFile.getParent().getParent().getParent()
                    .resolve("easymall-java/db/migration/V20261115_01__payment_close.sql");
            try (var connection = db.dataSource.getConnection()) {
                ScriptUtils.executeSqlScript(connection, new FileSystemResource(migration));
            }
        }
        var factory = new SqlSessionFactoryBean(); factory.setDataSource(db.dataSource);
        var config = new org.apache.ibatis.session.Configuration(); config.setMapUnderscoreToCamelCase(true); factory.setConfiguration(config);
        factory.setMapperLocations(new PathMatchingResourcePatternResolver().getResources("classpath*:com/easymall/mappers/*.xml"));
        session = new SqlSessionTemplate(factory.getObject());
        requests = session.getMapper(OrderRequestMapper.class);
        requestService = new OrderRequestService(requests, new DataSourceTransactionManager(db.dataSource), 3);
        skus = session.getMapper(ProductSkuMapper.class); items = session.getMapper(OrderItemMapper.class);
        for (Class<?> mapper : List.of(OrderInfoMapper.class, OrderItemMapper.class, UserAddressMapper.class, ProductInfoMapper.class,
                ProductPropertyValueMapper.class, ProductSkuMapper.class, ProductCartMapper.class, OrderLogisticsInfoMapper.class)) {
            String field = Character.toLowerCase(mapper.getSimpleName().charAt(0)) + mapper.getSimpleName().substring(1);
            ReflectionTestUtils.setField(service, field, session.getMapper(mapper));
        }
        ReflectionTestUtils.setField(service, "orderRequestService", requestService); ReflectionTestUtils.setField(service, "businessTaskService", db.service);
        ReflectionTestUtils.setField(service, "redisComponent", redis);
        AppConfig app = mock(AppConfig.class); when(app.getOrderExpireMinute()).thenReturn(30); ReflectionTestUtils.setField(service, "appConfig", app);
        when(payment.getPayUrl(any(), anyString(), anyString(), any(), any())).thenAnswer(call -> new PayInfoDTO("<html>synthetic-no-payment</html>", call.getArgument(1), call.getArgument(3)));
        previousContext = ReflectionTestUtils.getField(SpringContext.class, "applicationContext");
        paymentContext.registerBean("payChannel4Alipay", PayChannel.class, () -> payment); paymentContext.refresh();
        ReflectionTestUtils.setField(SpringContext.class, "applicationContext", paymentContext);
        addProduct(product, property, 5);
        db.jdbc.update("insert into user_address(address_id,user_id,address,addressee,phone) values (?,?,?,?,?)", address, user, "步骤08合成地址", "合成收货人", "13000000000");
    }

    void addProduct(String id, String value, int stock) {
        db.jdbc.update("insert into product_info(product_id,product_name,cover,status,create_time,min_price,max_price) values (?,?,?,1,UTC_TIMESTAMP(),12.34,12.34)", id, "步骤08合成商品", "step08-synthetic.png");
        db.jdbc.update("insert into product_property_value(product_id,property_id,property_name,property_value_id,property_value,sort) values (?, 'COLOR','颜色',?,'蓝色',1)", id, value);
        db.jdbc.update("insert into product_sku(product_id,property_value_id_hash,property_value_ids,price,stock,sort) values (?,?,?,12.34,?,1)", id, StringTools.encodeByMD5(value), value, stock);
    }
    PostOrderDTO order(int count) {
        PostOrderDTO order = new PostOrderDTO(); order.setRequestId(UUID.randomUUID().toString()); order.setAddressId(address);
        order.setPayMethod("alipay_pc"); order.setOrderFrom(0); order.setOrderList(List.of(item(product, property, count))); return order;
    }
    static PostOrderItemDTO item(String product, String property, int count) {
        PostOrderItemDTO item = new PostOrderItemDTO(); item.setProductId(product); item.setPropertyValueIds(property); item.setBuyCount(count); item.setRemark(""); return item;
    }
    int stock() { return db.jdbc.queryForObject("select stock from product_sku where product_id=? and property_value_id_hash=?", Integer.class, product, skuHash); }
    int orderCount() { return db.jdbc.queryForObject("select count(*) from order_info where user_id=?", Integer.class, user); }
    String orderId(String payId) { return db.jdbc.queryForObject("select min(order_id) from order_info where user_id=? and pay_order_id=?", String.class, user, payId); }
    int eventCount(String type, String payId) { return db.jdbc.queryForObject("select count(*) from business_task where task_type=? and biz_key=?", Integer.class, type, payId); }
    @Override public void close() {
        ReflectionTestUtils.setField(SpringContext.class, "applicationContext", previousContext); paymentContext.close(); db.close();
    }
}
