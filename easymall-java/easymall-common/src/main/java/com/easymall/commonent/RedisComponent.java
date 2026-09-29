package com.easymall.commonent;

import com.easymall.entity.constants.Constants;
import com.easymall.entity.dto.LogisticsSendDTO;
import com.easymall.entity.dto.TokenUserInfoDTO;
import com.easymall.entity.po.SysCategory;
import com.easymall.redis.RedisUtils;
import com.easymall.utils.StringTools;
import jakarta.annotation.Resource;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Component
public class RedisComponent {

    @Resource
    private RedisUtils redisUtils;

    /**
     * 保存验证码到Redis
     * @param code
     * @return
     */
    public String saveCheckCode(String code){
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
        redisUtils.setex(Constants.REDIS_KEY_TOKEN_ADMIN + token,account,Constants.REDIS_KEY_EXPIRES_DAY);
        return token;
    }

    /**
     * 退出登录时删除Token
     * @param token
     */
    public void cleanTokenInfoAdmin(String token) {
        redisUtils.delete(Constants.REDIS_KEY_TOKEN_ADMIN + token);
    }

    /**
     * 从 Redis 当中获取 token
     * @param token
     * @return
     */
    public String getLoginInfo4Admin(String token) {
        return (String) redisUtils.get(Constants.REDIS_KEY_TOKEN_ADMIN + token);
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
        //先清除旧的token
        cleanUserInfo(tokenUserInfoDTO.getUserId());
        // 生成token
        String token = UUID.randomUUID().toString().replace("-", "");
        tokenUserInfoDTO.setToken(token);
        // 把token存入redis当中，键为userId,以便在后续禁用用户或者想要查询用户token时可以直接删除或者获取
        // 把token的信息tokenUserInfoDTO存入Redis当中
        redisUtils.setex(Constants.REDIS_KEY_TOKEN_WEB + token,tokenUserInfoDTO,Constants.REDIS_KEY_EXPIRES_DAY * 7);
        redisUtils.setex(Constants.REDIS_KEY_TOKEN_USERID_WEB + tokenUserInfoDTO.getUserId(),token,Constants.REDIS_KEY_EXPIRES_DAY * 7);
    }

    public void cleanUserInfo(String userId) {
        String token =  (String) redisUtils.get(Constants.REDIS_KEY_TOKEN_USERID_WEB + userId);
        if (StringTools.isEmpty(token)) {
            return;
        }

        redisUtils.delete(Constants.REDIS_KEY_TOKEN_WEB + token);
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
            redisUtils.delete(Constants.REDIS_KEY_TOKEN_USERID_WEB + tokenUserInfoDTO.getUserId());
        }
    }

    /**
     * 根据token获取到登录信息(包含token)
     * @param token
     * @return
     */
    public TokenUserInfoDTO getTokenInfo(String token) {
        return (TokenUserInfoDTO) redisUtils.get(Constants.REDIS_KEY_TOKEN_WEB + token);
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
    public void addOrder2LogisticsQueue(Integer delaySeconds,String orderId){
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
}
