package com.easymall.controller;

import com.easymall.commonent.RedisComponent;
import com.easymall.entity.enums.ResponseCodeEnum;
import com.easymall.entity.enums.UserStatusEnum;
import com.easymall.entity.po.UserInfo;
import com.easymall.entity.query.UserInfoQuery;
import com.easymall.entity.vo.ResponseVO;
import com.easymall.exception.BusinessException;
import com.easymall.service.UserInfoService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/user")
public class UserController extends ABaseController{
    @Resource
    private UserInfoService userInfoService;
    @Resource
    private RedisComponent redisComponent;

    /**
     * 查看所有用户信息
     * @param userInfoQuery 用户信息查询条件
     */
    @RequestMapping("/loadUser")
    public ResponseVO loadUser(UserInfoQuery userInfoQuery) {
        userInfoQuery.setOrderBy("u.join_time desc");
        return getSuccessResponseVO(userInfoService.findListByPage(userInfoQuery));
    }

    /**
     * 改变用户状态(如果是禁用用户，则强制下线用户)
     * @param userId
     * @param status
     * @return
     */
    @RequestMapping("/changeStatus")
    public ResponseVO changeStatus(String userId, Integer status) {
        UserStatusEnum userStatusEnum = UserStatusEnum.getByStatus(status);

        if (userStatusEnum == null) {
            throw new BusinessException(ResponseCodeEnum.CODE_600);
        }

        UserInfo userInfo = new UserInfo();
        userInfo.setStatus(status);
        this.userInfoService.updateUserInfoByUserId(userInfo, userId);

        // 如果用户被禁用，则强制用户下线
        if (UserStatusEnum.DISABLE == userStatusEnum) {
            redisComponent.forceLogout(userId);
        }

        return getSuccessResponseVO(null);
    }
}
