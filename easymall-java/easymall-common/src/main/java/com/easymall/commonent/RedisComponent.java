package com.easymall.commonent;

import com.easymall.entity.constants.Constants;
import com.easymall.redis.RedisUtils;
import jakarta.annotation.Resource;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.stereotype.Component;

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
        redisUtils.setex(Constants.REDIS_KEY_TOKEN_ADMIN + token,account,Constants.REDIS_KEY_EXPIRES_ONE_DAY);
        return token;
    }

    /**
     * 退出登录时删除Token
     * @param token
     */
    public void cleanTokenInfoAdmin(String token) {
        redisUtils.delete(Constants.REDIS_KEY_TOKEN_ADMIN + token);
    }
}
