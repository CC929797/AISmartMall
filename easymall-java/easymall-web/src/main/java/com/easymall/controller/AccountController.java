package com.easymall.controller;

import com.easymall.annotation.GlobalInterceptor;
import com.easymall.commonent.RedisComponent;
import com.easymall.entity.constants.Constants;
import com.easymall.entity.dto.TokenUserInfoDTO;
import com.easymall.entity.po.UserInfo;
import com.easymall.entity.vo.CheckCodeVO;
import com.easymall.entity.vo.ResponseVO;
import com.easymall.entity.vo.UserInfoVO;
import com.easymall.exception.BusinessException;
import com.easymall.service.UserInfoService;
import com.easymall.utils.CopyTools;
import com.wf.captcha.ArithmeticCaptcha;
import jakarta.annotation.Resource;
import jakarta.validation.constraints.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.annotations.Param;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/account")
@Slf4j
@Validated
public class AccountController extends ABaseController{
    @Resource
    private RedisComponent redisComponent;
    @Resource
    private UserInfoService userInfoService;

    /**
     * 自动登录
     */
    @RequestMapping("/autoLogin")
    public ResponseVO autoLogin(){
        TokenUserInfoDTO tokenUserInfoDTO = getTokenUserInfo();
        if(tokenUserInfoDTO == null){
            return getSuccessResponseVO(null);
        }
        redisComponent.saveTokenInfo(tokenUserInfoDTO);
        return getSuccessResponseVO(tokenUserInfoDTO);
    }

    /**
     * 生成验证码图片
     * @return 验证码图片base64字符串和验证码key
     */
    @RequestMapping("/checkCode")
    public ResponseVO checkCode() {
        ArithmeticCaptcha captcha = new ArithmeticCaptcha(100, 42);
        String code = captcha.text();
        String checkCodeBase64 = captcha.toBase64();
        String checkCodeKey = redisComponent.saveCheckCode(code);
        CheckCodeVO checkCodeVO = new CheckCodeVO(checkCodeKey, checkCodeBase64);
        log.info("图片里的内容: {}", code);

        return getSuccessResponseVO(checkCodeVO);
    }

    @RequestMapping("/register")
    public ResponseVO register(@NotEmpty @Email @Size(max = 150) String email,
                               @NotEmpty @Size(max = 20) String nickName ,
                               @NotEmpty @Pattern(regexp = Constants.REGEX_PASSWORD) String registerPassword,
                               @NotEmpty String checkCodeKey,
                               @NotEmpty String checkCode){


        try{
            if(!checkCode.equalsIgnoreCase(redisComponent.getCheckCode(checkCodeKey))){
                throw new BusinessException("图片验证码不正确");
            }
            userInfoService.register(email, nickName, registerPassword);
            return getSuccessResponseVO(null);
        }finally {
            // 无论是注册成功还是失败都会删除旧的验证码的Redis中的key，重新生成验证码和验证码的key
            redisComponent.deleteCheckCode(checkCodeKey);
        }
    }

    @RequestMapping("/login")
    public ResponseVO login(@NotEmpty @Email @Size(max = 150) String email,
                            @NotEmpty String password,
                            @NotEmpty String checkCodeKey,
                            @NotEmpty String checkCode){


        try{
            if(!checkCode.equalsIgnoreCase(redisComponent.getCheckCode(checkCodeKey))){
                throw new BusinessException("图片验证码不正确");
            }

            //获取登录的ip
            String ip = getIpAddr();
            TokenUserInfoDTO tokenUserInfoDTO = userInfoService.login(email, password, ip);
            return getSuccessResponseVO(tokenUserInfoDTO);
        }finally {
            // 无论是登录成功还是失败都会删除旧的验证码的Redis中的key，重新生成验证码和验证码的key
            redisComponent.deleteCheckCode(checkCodeKey);
        }
    }

    /**
     * 退出登录
     * @param token
     * @return
     */
    @RequestMapping("/logout")
    public ResponseVO logout(@RequestHeader("token") String token){
        redisComponent.cleanToken(token);
        return getSuccessResponseVO(null);
    }

    /**
     * 修改密码
     * @param oldPassword
     * @param password
     * @return
     */
    @RequestMapping("/updatePassword")
    @GlobalInterceptor(checkLogin = true)
    public ResponseVO updatePassword(@NotEmpty String oldPassword,@NotEmpty String password){
        userInfoService.updatePassword(getTokenUserInfo().getUserId(), oldPassword, password);
        return getSuccessResponseVO(null);
    }

    /**
     * 获取用户信息
     */
    @RequestMapping("/getUserInfo")
    @GlobalInterceptor(checkLogin = true)
    public ResponseVO getUserInfo(){
        UserInfo userInfo = userInfoService.getUserInfoByUserId(getTokenUserInfo().getUserId());
        return getSuccessResponseVO(CopyTools.copy(userInfo, UserInfoVO.class));
    }

    @RequestMapping("/updateUserInfo")
    @GlobalInterceptor(checkLogin = true)
    public ResponseVO updateUserInfo(@NotEmpty String avatar,
                                     @NotEmpty @Size(max = 20) String nickName,
                                     @NotNull Integer sex){
        TokenUserInfoDTO tokenUserInfoDTO =  getTokenUserInfo();
        //数据库更新后的用户信息
        UserInfo updateInfo = new UserInfo();
        updateInfo.setAvatar(avatar);
        updateInfo.setNickName(nickName);
        updateInfo.setSex(sex);
        this.userInfoService.updateUserInfoByUserId(updateInfo, tokenUserInfoDTO.getUserId());

        //Redis更新新的用户信息
        tokenUserInfoDTO.setNickName(nickName);
        tokenUserInfoDTO.setAvatar(avatar);

        redisComponent.updateTokenInfo(tokenUserInfoDTO);
        return getSuccessResponseVO(tokenUserInfoDTO);
    }
}
