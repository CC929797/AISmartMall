package com.easymall.task;

import com.easymall.task.persistence.BusinessTaskMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * 【仅用于测试验证，不参与正式业务运行】为MySQL集成测试和崩溃测试子进程准备共享资源。
 * Fixture意为“测试准备环境”：建立连接池、Mapper、事务管理器并生成合成任务。
 * 必须显式启用，固定连接13306/easymall_test；仅在缺表时创建任务表和合成标记表。
 * 不连接开发库，不清表、不删行；正式业务应使用Spring注入的BusinessTaskService。
 */
final class TaskMySqlFixture implements AutoCloseable {
    static final String URL = "jdbc:mysql://127.0.0.1:13306/easymall_test?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&connectTimeout=2000&socketTimeout=5000";
    final HikariDataSource dataSource;
    final JdbcTemplate jdbc;
    final TransactionTemplate transaction;
    final BusinessTaskService service;
    final BusinessTaskProperties properties = new BusinessTaskProperties();
    final Path envFile;
    /** 仅在测试进程内读取和传递隔离凭据，不打印，也不放进子进程命令参数。 */
    final String password;

    /** 先校验显式开关和私密配置路径，再建立固定隔离库连接，防止测试误连日常库。 */
    TaskMySqlFixture() throws Exception {
        if (!"true".equals(System.getProperty("task.mysql.integration"))) throw new IllegalStateException("Explicit task.mysql.integration required");
        envFile = Path.of(System.getProperty("task.mysql.env-file", "")).toAbsolutePath().normalize();
        if (!envFile.toString().replace('\\', '/').endsWith("/runtime/phase0/baseline.env")) {
            throw new IllegalStateException("Only the baseline private env file is allowed");
        }
        password = Files.readAllLines(envFile, StandardCharsets.UTF_8).stream()
                .filter(line -> line.startsWith("BASELINE_DB_PASSWORD=")).map(line -> line.substring("BASELINE_DB_PASSWORD=".length()).strip()).findFirst().orElseThrow();
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(URL); config.setUsername("baseline"); config.setPassword(password);
        config.setMaximumPoolSize(12); config.setMinimumIdle(0); config.setConnectionTimeout(3000);
        dataSource = new HikariDataSource(config);
        jdbc = new JdbcTemplate(dataSource);
        if (!"easymall_test".equals(jdbc.queryForObject("select database()", String.class))) throw new IllegalStateException("Unexpected database");
        if (jdbc.queryForObject("select count(*) from information_schema.tables where table_schema=database() and table_name='business_task'", Integer.class) == 0) {
            Path migration = envFile.getParent().getParent().getParent().resolve("easymall-java/db/migration/V20261107_01__business_task.sql");
            try (var connection = dataSource.getConnection()) { ScriptUtils.executeSqlScript(connection, new FileSystemResource(migration)); }
        }
        if (jdbc.queryForObject("select count(*) from information_schema.statistics where table_schema=database() and table_name='business_task' and index_name='uk_task_event' and non_unique=0", Integer.class) != 3) {
            throw new IllegalStateException("Task event unique constraint missing");
        }
        // 仅测试使用的“业务行”替身，用来验证业务行与任务行共同提交/回滚，不是新增商城业务表。
        jdbc.execute("CREATE TABLE IF NOT EXISTS business_task_test_marker (marker_id VARCHAR(64) PRIMARY KEY, marker_value INT NOT NULL) ENGINE=InnoDB COMMENT='11A synthetic transaction fixture'");
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        var mybatis = new org.apache.ibatis.session.Configuration(); mybatis.setMapUnderscoreToCamelCase(true);
        factory.setConfiguration(mybatis);
        factory.setMapperLocations(new ClassPathResource("com/easymall/task/persistence/BusinessTaskMapper.xml"));
        var mapper = new SqlSessionTemplate(factory.getObject()).getMapper(BusinessTaskMapper.class);
        var manager = new DataSourceTransactionManager(dataSource);
        transaction = new TransactionTemplate(manager);
        // 测试专用短时间参数，缩短等待；这些值不改变正式配置的默认值。
        properties.setInitialBackoffMillis(50); properties.setMaxBackoffMillis(500);
        properties.getWorker().setPollIntervalMillis(50); properties.getWorker().setLeaseSeconds(3);
        properties.getWorker().setHeartbeatMillis(200); properties.getWorker().setShutdownGraceMillis(2000);
        service = new BusinessTaskService(mapper, manager, properties);
    }

    /** 在真实事务中提交一条合成任务，不产生订单、库存或外部服务副作用。 */
    BusinessTask submit(String type, String key) {
        return transaction.execute(status -> service.enqueue(new TaskSubmission(type, key, 1, "{\"synthetic\":true}", Duration.ZERO)));
    }
    /** 每个测试生成独立类型，避免消费上次保留的合成记录，不需要清库。 */
    String uniqueType() { return "IT11A_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase(); }
    int count(String type) { return jdbc.queryForObject("select count(*) from business_task where task_type=?", Integer.class, type); }
    List<BusinessTask> claim(String type, int count) { return service.claimBatch(java.util.Set.of(type), count, 3); }
    /** 仅释放本测试的连接池，保留数据库任务和合成证据。 */
    @Override public void close() { dataSource.close(); }
}
