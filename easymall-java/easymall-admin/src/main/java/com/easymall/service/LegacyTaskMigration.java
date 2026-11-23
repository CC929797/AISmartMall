package com.easymall.service;

import com.easymall.entity.constants.Constants;
import com.easymall.entity.dto.RagDataDTO;
import com.easymall.exception.BusinessException;
import jakarta.annotation.Resource;
import org.redisson.api.RedissonClient;
import org.redisson.codec.JsonJacksonCodec;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;

/**
 * 管理端正式使用的一次性迁移入口，负责把旧 Redis 队列里的存量任务转入 MySQL。
 * 应在旧消费者停机后使用，每次只处理有限数量的记录。转存失败时，待处理记录留在原队列。
 */
@Service
public class LegacyTaskMigration {
    @Resource
    private RedissonClient redissonClient;
    @Resource
    private RedisTemplate<String, String> redisTemplate;
    @Resource
    private LegacyTaskTransfer transfer;

    public Map<String, Integer> migrate(int batch) {
        // 多个管理员同时点击时，只允许一个请求迁移，避免两个请求读取并移除同一个队头。
        var lock = redissonClient.getLock(Constants.REDIS_QUEUE_RAG_DATA + ":11b-migration");
        if (!lock.tryLock()) {
            throw new BusinessException("已有管理员正在迁移旧任务");
        }
        try {
            var rag = redissonClient.<RagDataDTO>getBlockingDeque(Constants.REDIS_QUEUE_RAG_DATA, JsonJacksonCodec.INSTANCE);
            int count = 0;
            while (count < batch) {
                // peekFirst 只查看队头。转存事务成功返回后，pollFirst 才会移除这条记录。
                RagDataDTO task = rag.peekFirst();
                if (task == null) {
                    break;
                }
                transfer.transferRag(task.getType(), task.getDataId());
                rag.pollFirst();
                count++;
            }
            return Map.of("rag", count,
                    "logistics", migrateShipments(Constants.REDIS_KEY_ORDER_LOGISTICS_QUEUE, false, batch),
                    "confirm", migrateShipments(Constants.REDIS_KEY_ORDER_DELAY_QUEUE_CONFIRM, true, batch));
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /** 两类旧履约队列都是有序集合，成员是订单号，分值是原计划执行时间的毫秒值。 */
    private int migrateShipments(String key, boolean autoConfirm, int limit) {
        var zset = redisTemplate.opsForZSet();
        var entries = zset.rangeWithScores(key, 0, limit - 1);
        if (entries == null) {
            return 0;
        }
        int transferred = 0;
        for (var entry : entries) {
            if (entry.getValue() == null || entry.getScore() == null) {
                throw new BusinessException("旧物流队列含无效记录");
            }
            // 保留原计划时间，按秒向上取整避免提前执行。
            long delay = Math.max(0, (long) Math.ceil((entry.getScore() - System.currentTimeMillis()) / 1000));
            transfer.transferShipment(entry.getValue(), autoConfirm, Duration.ofSeconds(delay));
            // 转存方法可能判定任务已完成。成功返回后，才确认这条 Redis 记录可以移除。
            zset.remove(key, entry.getValue());
            transferred++;
        }
        return transferred;
    }
}
