package com.easymall;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

@Component
@Slf4j
public class InitRun implements ApplicationRunner {
    @Resource
    private DataSource dataSource;

    @Resource
    private RedisTemplate<String,Object> redisTemplate;


    @Override
    public void run(ApplicationArguments args) throws Exception {
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            redisTemplate.getConnectionFactory().getConnection().isClosed();
            log.info("mcp服务器启动成功,可以开始愉快聊天了");
        } catch (SQLException e) {
            System.out.println("数据配置错误");
        }catch (Exception e){
            log.error("系统启动异常",e);
        }finally {
            if (connection != null) {
                connection.close();
            }
        }
    }
}
