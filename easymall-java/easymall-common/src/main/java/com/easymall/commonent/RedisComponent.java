package com.easymall.commonent;

import com.easymall.entity.constants.Constants;
import com.easymall.entity.dto.TokenUserInfoDTO;
import com.easymall.entity.po.SysCategory;
import com.easymall.redis.RedisUtils;
import com.easymall.utils.StringTools;
import jakarta.annotation.Resource;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
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
}
