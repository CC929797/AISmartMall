package com.easymall.commonent;

import com.easymall.entity.constants.Constants;
import com.easymall.entity.dto.LogisticsSendDTO;
import com.easymall.entity.dto.RagDataDTO;
import com.easymall.entity.dto.TokenUserInfoDTO;
import com.easymall.entity.po.SysCategory;
import com.easymall.entity.enums.ResponseCodeEnum;
import com.easymall.exception.BusinessException;
import com.easymall.redis.RedisUtils;
import com.easymall.security.SessionRevokedEvent;
import com.easymall.utils.JsonUtils;
import com.easymall.utils.StringTools;
import jakarta.annotation.Resource;
import jakarta.validation.constraints.NotEmpty;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBlockingDeque;
import org.redisson.api.RedissonClient;
import org.redisson.codec.JsonJacksonCodec;
import org.redisson.client.codec.StringCodec;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Component
@Slf4j
public class RedisComponent {

    @Resource
    private RedisUtils redisUtils;
    @Resource
    private RedissonClient redissonClient;

    /**
     * 保存验证码到Redis
     * @param code
     * @return
     */
    public String saveCheckCode(String code) {
        String checkCodeKey = UUID.randomUUID().toString();
        redisUtils.setex(Constants.REDIS_KEY_CHECK_CODE + checkCodeKey, code, 60 * 10);
        return checkCodeKey;
    }

    /**
     * 根据key获取验证码
     */
    public String getCheckCode(@NotEmpty String checkCodeKey) {
        return (String) redisUtils.get(Constants.REDIS_KEY_CHECK_CODE + checkCodeKey);
    }

    /**
     * 登录和注册只使用原子消费，普通读取仅供测试检查是否已消费。
     */
    public String consumeCheckCode(String checkCodeKey) {
        return (String) redisUtils.getAndDelete(Constants.REDIS_KEY_CHECK_CODE + checkCodeKey);
    }

    /**
     * 验证码用过一次后，删除Redis当中的验证码
     */
    public void deleteCheckCode(String checkCodeKey) {
        redisUtils.delete(Constants.REDIS_KEY_CHECK_CODE + checkCodeKey);
    }


    /**
     * 保存Token到Redis
     * @param account
     * @return
     */
    public String saveTokenInfoAdmin(String account) {
        String token = UUID.randomUUID().toString();
        if (!redisUtils.setex(Constants.REDIS_KEY_TOKEN_ADMIN + token,account,Constants.REDIS_KEY_EXPIRES_DAY)) {
            throw new BusinessException(ResponseCodeEnum.CODE_500);
        }
        return token;
    }

    /**
     * 退出登录时删除Token
     * @param token
     */
    public void cleanTokenInfoAdmin(String token) {
        if (!StringTools.isEmpty(token)) {
            redisUtils.delete(Constants.REDIS_KEY_TOKEN_ADMIN + token);
        }
    }

    /**
     * 从 Redis 当中获取 token
     * @param token
     * @return
     */
    public String getLoginInfo4Admin(String token) {
        Object account = redisUtils.get(Constants.REDIS_KEY_TOKEN_ADMIN + token);
        return account instanceof String ? (String) account : null;
    }

    /**
     * Redis当中存入分类缓存
     */
    public void saveCategory(List<SysCategory> categoryList) {
        redisUtils.set(Constants.REDIS_KEY_CATEGORY_LIST,categoryList);
    }

    /**
     * 从 Redis 当中获取分类缓存
     */
    public List<SysCategory> getCategoryList() {
        List<SysCategory> categoryList = (List<SysCategory>) redisUtils.get(Constants.REDIS_KEY_CATEGORY_LIST);
        return categoryList == null ? new ArrayList<>() : categoryList;
    }

    /**
     * 把用户登录后的token信息存入Redis
     * @param tokenUserInfoDTO
     */
    public void saveTokenInfo(TokenUserInfoDTO tokenUserInfoDTO) {
        if (tokenUserInfoDTO == null || StringTools.isEmpty(tokenUserInfoDTO.getUserId())) {
            throw new BusinessException(ResponseCodeEnum.CODE_901);
        }
        //先清除旧的token
        cleanUserInfo(tokenUserInfoDTO.getUserId());
        // 生成token
        String token = UUID.randomUUID().toString().replace("-", "");
        tokenUserInfoDTO.setToken(token);
        // 把token存入redis当中，键为userId,以便在后续禁用用户或者想要查询用户token时可以直接删除或者获取
        // 把token的信息tokenUserInfoDTO存入Redis当中
        boolean saved = redisUtils.setex(Constants.REDIS_KEY_TOKEN_WEB + token,tokenUserInfoDTO,Constants.REDIS_KEY_EXPIRES_DAY * 7);
        boolean indexed = saved && redisUtils.setex(Constants.REDIS_KEY_TOKEN_USERID_WEB + tokenUserInfoDTO.getUserId(),token,Constants.REDIS_KEY_EXPIRES_DAY * 7);
        if (!indexed) {
            redisUtils.delete(Constants.REDIS_KEY_TOKEN_WEB + token);
            redisUtils.compareAndDelete(Constants.REDIS_KEY_TOKEN_USERID_WEB + tokenUserInfoDTO.getUserId(), token);
            throw new BusinessException(ResponseCodeEnum.CODE_500);
        }
    }

    public void cleanUserInfo(String userId) {
        String token = getCurrentToken(userId);
        if (StringTools.isEmpty(token)) {
            return;
        }

        redisUtils.delete(Constants.REDIS_KEY_TOKEN_WEB + token);
        redisUtils.compareAndDelete(Constants.REDIS_KEY_TOKEN_USERID_WEB + userId, token);
        publishSessionRevoked(userId, token);
    }

    /**
     * 强制退出登录
     * @param userId
     */
    public void forceLogout(String userId) {
        String token = getCurrentToken(userId);
        if (!StringTools.isEmpty(token)) {
            redisUtils.delete(Constants.REDIS_KEY_TOKEN_WEB + token);
            redisUtils.compareAndDelete(Constants.REDIS_KEY_TOKEN_USERID_WEB + userId, token);
        }
        // 即使用户到 Token 的反向映射已过期，其他节点仍可能保留该用户的 WebSocket 连接。
        publishSessionRevoked(userId, null);
    }

    /**
     * 用户退出登录时清除该用户所有的token信息
     * @param token
     */
    public void cleanToken(String token) {
        if (StringTools.isEmpty(token)) {
            return;
        }

        TokenUserInfoDTO tokenUserInfoDTO = getTokenInfo(token);
        redisUtils.delete(Constants.REDIS_KEY_TOKEN_WEB + token);

        if (tokenUserInfoDTO != null) {
            redisUtils.compareAndDelete(Constants.REDIS_KEY_TOKEN_USERID_WEB + tokenUserInfoDTO.getUserId(), token);
            publishSessionRevoked(tokenUserInfoDTO.getUserId(), token);
        }
    }

    /**
     * 修改个人信息时更改Redis当中存储的个人信息
     * @param tokenUserInfoDTO
     */
    public void updateTokenInfo(TokenUserInfoDTO tokenUserInfoDTO) {
        refreshTokenInfo(tokenUserInfoDTO);
    }

    /** 仅续期仍有效的当前会话，防止已经开始执行的旧请求在退出后重新创建会话。 */
    public boolean refreshTokenInfo(TokenUserInfoDTO tokenUserInfoDTO) {
        if (tokenUserInfoDTO == null || StringTools.isEmpty(tokenUserInfoDTO.getUserId())
                || StringTools.isEmpty(tokenUserInfoDTO.getToken())) {
            return false;
        }
        return redisUtils.replaceSessionIfCurrent(Constants.REDIS_KEY_TOKEN_WEB + tokenUserInfoDTO.getToken(),
                Constants.REDIS_KEY_TOKEN_USERID_WEB + tokenUserInfoDTO.getUserId(), tokenUserInfoDTO,
                tokenUserInfoDTO.getToken(), Constants.REDIS_KEY_EXPIRES_DAY * 7);
    }

    /**
     * 根据token获取到登录信息(包含token)
     * @param token
     * @return
     */
    public TokenUserInfoDTO getTokenInfo(String token) {
        if (StringTools.isEmpty(token)) {
            return null;
        }
        Object session = redisUtils.get(Constants.REDIS_KEY_TOKEN_WEB + token);
        return session instanceof TokenUserInfoDTO ? (TokenUserInfoDTO) session : null;
    }

    /** 根据用户反向映射获取当前 Token，用于识别被新登录替换的旧会话。 */
    public String getCurrentToken(String userId) {
        if (StringTools.isEmpty(userId)) {
            return null;
        }
        Object token = redisUtils.get(Constants.REDIS_KEY_TOKEN_USERID_WEB + userId);
        return token instanceof String ? (String) token : null;
    }

    /**
     * 当用户退出登录时，会话撤销通知
     * @param userId
     * @param token
     */
    private void publishSessionRevoked(String userId, String token) {
        try {
            redissonClient.getTopic(SessionRevokedEvent.TOPIC, StringCodec.INSTANCE)
                    .publish(JsonUtils.convertObj2Json(new SessionRevokedEvent(userId, token)));
        } catch (RuntimeException e) {
            // 会话已成功撤销；通知失败时，Web 节点仍会在每次私有消息推送前重新校验。
            log.warn("会话撤销通知失败：异常类型={}", e.getClass().getSimpleName());
        }
    }

    /**
     * 添加订单到延时队列
     * @param queueName
     * @param delayMin
     * @param orderId
     */
    public void addOrder2DelayQueue(String queueName,Integer delayMin,String orderId) {
        long expireTime = System.currentTimeMillis() + delayMin * 60 * 1000;
        redisUtils.zsetAdd(queueName,orderId,expireTime);
    }

    /**
     * 获取到延时订单
     * @param queueName
     * @return
     */
    public Set<String> getTimeOutOrder(String queueName) {
        return redisUtils.zsetRangeByScore(queueName, 0, System.currentTimeMillis());
    }

    /**
     * 删除到延时订单
     * @param queueName
     * @return
     */
    public Long removeTimeOutOrder(String queueName,String orderId) {
        return redisUtils.zsetAddRemove(queueName,orderId);
    }

    /**
     * 保存物流信息到 Redis
     * @param logisticsSendDTO 物流信息
     */
    public void saveLogistics(LogisticsSendDTO logisticsSendDTO) {
        redisUtils.set(Constants.REDIS_KEY_SETTING_LOGISTICS,logisticsSendDTO);
    }

    /**
     * 获取物流信息
     */
    public LogisticsSendDTO getLogisticsInfo() {
        return (LogisticsSendDTO) redisUtils.get(Constants.REDIS_KEY_SETTING_LOGISTICS);
    }

    /**
     * 添加订单到物流队列
     * @param delaySeconds
     * @param orderId
     */
    public void addOrder2LogisticsQueue(Integer delaySeconds,String orderId) {
        long expireTime = System.currentTimeMillis() + delaySeconds * 1000;
        redisUtils.zsetAdd(Constants.REDIS_KEY_ORDER_LOGISTICS_QUEUE,orderId,expireTime);
    }

    /**
     * 获取到发货延时订单
     */
    public Set<String> getTimeOutOrder4Logistics() {
        return redisUtils.zsetRangeByScore(Constants.REDIS_KEY_ORDER_LOGISTICS_QUEUE, 0, System.currentTimeMillis());
    }

    /**
     * 删除Redis队列发货延时订单
     * @param orderId
     * @return
     */
    public Long removeTimeOutOrder4Logistics(String orderId) {
        return redisUtils.zsetAddRemove(Constants.REDIS_KEY_ORDER_LOGISTICS_QUEUE,orderId);
    }

    /**
     * AI
     * 发送到向量数据库消息队列
     * @param queueName
     * @param data
     */
    public void sendMessage(String queueName,RagDataDTO data) {
        RBlockingDeque<RagDataDTO> queue = redissonClient.getBlockingDeque(queueName, JsonJacksonCodec.INSTANCE);
        try {
            queue.put(data);
        }catch (Exception e) {
            log.error("发送消息失败，异常类型={}", e.getClass().getSimpleName());
        }
    }

    /**
     * 获取redis当中存储的提示语
     * @param key 提示语的key
     */
    public String getPrompt(String key) {
        return (String) redisUtils.get(Constants.REDIS_KEY_PROMPT + key);
    }

    /**
     * 保存提示语到redis
     * @param key 提示语的key
     * @param value 提示语的值
     */
    public void savePrompt(String key,String value) {
        redisUtils.set(Constants.REDIS_KEY_PROMPT + key,value);
    }

    /**
     * 删除redis当中存储的提示语
     * @param key 提示语的key
     */
    public void cleanPrompt(String key) {
        redisUtils.delete(Constants.REDIS_KEY_PROMPT + key);
    }

    /**
     * 取消消息
     * @param userId
     * @param messageId
     */
    public void cancelMessage(String userId,Integer messageId) {
        redisUtils.setex(Constants.REDIS_KEY_CANCEL_AGENT_MESSAGE + userId + messageId, messageId, Constants.REDIS_KEY_EXPIRES_ONE_MIN);
    }

    /**
     * 判断是否取消消息
     * @param userId
     * @param messageId
     * @return
     */
    public Boolean hasCancelMessage(String userId, Integer messageId) {
        return redisUtils.get(Constants.REDIS_KEY_CANCEL_AGENT_MESSAGE + userId + messageId) != null;
    }
}
