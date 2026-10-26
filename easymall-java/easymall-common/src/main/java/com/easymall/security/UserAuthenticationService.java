package com.easymall.security;

import com.easymall.commonent.RedisComponent;
import com.easymall.entity.dto.TokenUserInfoDTO;
import com.easymall.entity.enums.UserStatusEnum;
import com.easymall.entity.po.UserInfo;
import com.easymall.service.UserInfoService;
import com.easymall.utils.StringTools;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

/**
 * HTTP 与 WebSocket 共用的身份解析入口；存储异常继续向上抛出，不能因此放行请求。
 **/
@Component
public class UserAuthenticationService {
    @Resource
    private RedisComponent redisComponent;
    @Resource
    private UserInfoService userInfoService;

    /**
     * 通过 token 获取当前用户信息
     * @param token
     * @return
     */
    public CurrentUser resolve(String token) {
        if (StringTools.isEmpty(token)) {
            return null;
        }
        TokenUserInfoDTO session = redisComponent.getTokenInfo(token);
        if (session == null || StringTools.isEmpty(session.getUserId()) || !token.equals(session.getToken())) {
            return null;
        }
        // 并发登录或 Token 替换可能留下旧会话键，必须确认它仍是该用户的当前会话。
        if (!token.equals(redisComponent.getCurrentToken(session.getUserId()))) {
            return null;
        }
        UserInfo user = userInfoService.getUserInfoByUserId(session.getUserId());
        if (user == null || !session.getUserId().equals(user.getUserId())
                || !UserStatusEnum.ENABLE.getStatus().equals(user.getStatus())) {
            redisComponent.cleanToken(token);
            return null;
        }
        return new CurrentUser(user.getUserId(), user.getNickName(), user.getAvatar(), CurrentUser.Role.USER);
    }
}
