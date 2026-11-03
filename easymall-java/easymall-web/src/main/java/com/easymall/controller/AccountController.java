package com.easymall.controller;

import com.easymall.annotation.GlobalInterceptor;
import com.easymall.commonent.RedisComponent;
import com.easymall.entity.constants.Constants;
import com.easymall.entity.enums.ResponseCodeEnum;
import com.easymall.entity.dto.TokenUserInfoDTO;
import com.easymall.entity.po.UserInfo;
import com.easymall.entity.vo.CheckCodeVO;
import com.easymall.entity.vo.ResponseVO;
import com.easymall.entity.vo.UserInfoVO;
import com.easymall.exception.BusinessException;
import com.easymall.service.UserInfoService;
import com.easymall.security.LoginAbuseProtection;
import org.springframework.web.bind.annotation.PostMapping;
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
    @Resource
    private LoginAbuseProtection loginAbuseProtection;

    /**
     * 自动登录
     */
    @RequestMapping("/autoLogin")
    public ResponseVO autoLogin() {
        TokenUserInfoDTO tokenUserInfoDTO = getTokenUserInfo();
        if (tokenUserInfoDTO == null) {
            return getSuccessResponseVO(null);
        }
        // 只续期仍有效的当前会话；认证后若已退出或被封禁，不能通过旧请求重建登录态。
        if (!redisComponent.refreshTokenInfo(tokenUserInfoDTO)) {
            throw new BusinessException(ResponseCodeEnum.CODE_901);
        }
        return getSuccessResponseVO(tokenUserInfoDTO);
    }

    /**
     * 生成验证码图片
     * @return 验证码图片base64字符串和验证码key
     */
    @RequestMapping("/checkCode")
    public ResponseVO checkCode() {
        //验证码限速(根据验证码发送的次数进行限速 60秒内最多20次)
        loginAbuseProtection.checkCaptchaIssue("web", getIpAddr());
        ArithmeticCaptcha captcha = new ArithmeticCaptcha(100, 42);
        String code = captcha.text();
        String checkCodeBase64 = captcha.toBase64();
        String checkCodeKey = redisComponent.saveCheckCode(code);
        CheckCodeVO checkCodeVO = new CheckCodeVO(checkCodeKey, checkCodeBase64);
        // 验证码答案只保存到 Redis，不写入日志。

        return getSuccessResponseVO(checkCodeVO);
    }

    @PostMapping("/register")
    public ResponseVO register(@NotEmpty @Email @Size(max = 150) String email,
                               @NotEmpty @Size(max = 20) String nickName ,
                               @NotEmpty @Pattern(regexp = Constants.REGEX_PASSWORD) String registerPassword,
                               @NotEmpty @Size(max = 128) String checkCodeKey,
                               @NotEmpty @Size(max = 8) String checkCode) {

        //注册限速(根据注册的次数进行限速 10分钟内最多10次)
        loginAbuseProtection.checkRegistration(getIpAddr());
        try {
            if (!checkCode.equalsIgnoreCase(redisComponent.consumeCheckCode(checkCodeKey))) {
                throw new BusinessException("图片验证码不正确");
            }
            userInfoService.register(email, nickName, registerPassword);
            return getSuccessResponseVO(null);
        }finally {
            // 无论是注册成功还是失败都会删除旧的验证码的Redis中的key，重新生成验证码和验证码的key
            redisComponent.deleteCheckCode(checkCodeKey);
        }
    }

    @PostMapping("/login")
    public ResponseVO login(@NotEmpty @Email @Size(max = 150) String email,
                            @NotEmpty @Size(max = 256) String password,
                            @NotEmpty @Size(max = 128) String checkCodeKey,
                            @NotEmpty @Size(max = 8) String checkCode) {


        String ip = getIpAddr();
        //登录源限速(根据登录的次数进行限速 60秒内最多30次)
        loginAbuseProtection.checkLoginSource("web", ip);
        // 已存在账号以数据库身份计数，避免邮箱大小写或排序规则等价写法绕过限制。
        UserInfo rateUser = userInfoService.getUserInfoByEmail(email);
        String rateAccount = rateUser == null ? "email:" + email : "user:" + rateUser.getUserId();
        loginAbuseProtection.checkAccountFailures("web", rateAccount);
        try {
            if (!checkCode.equalsIgnoreCase(redisComponent.consumeCheckCode(checkCodeKey))) {
                throw new BusinessException("图片验证码不正确");
            }

            //获取登录的ip
            TokenUserInfoDTO tokenUserInfoDTO = userInfoService.login(email, password, ip);
            loginAbuseProtection.succeeded("web", rateAccount);
            return getSuccessResponseVO(tokenUserInfoDTO);
        } catch (BusinessException e) {
            loginAbuseProtection.failed("web", rateAccount);
            throw e;
        } finally {
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
    public ResponseVO logout(@RequestHeader("token") String token) {
        redisComponent.cleanToken(token);
        return getSuccessResponseVO(null);
    }

    /**
     * 修改密码
     * @param oldPassword
     * @param password
     * @return
     */
    @PostMapping("/updatePassword")
    @GlobalInterceptor(checkLogin = true)
    public ResponseVO updatePassword(@NotEmpty @Size(max = 256) String oldPassword,
                                    @NotEmpty @Pattern(regexp = Constants.REGEX_PASSWORD) String password) {
        //修改密码限速(根据修改密码的次数进行限速 10分钟内最多10次)
        loginAbuseProtection.checkPasswordChange(getTokenUserInfo().getUserId());
        userInfoService.updatePassword(getTokenUserInfo().getUserId(), oldPassword, password);
        return getSuccessResponseVO(null);
    }

    /**
     * 获取用户信息
     */
    @RequestMapping("/getUserInfo")
    @GlobalInterceptor(checkLogin = true)
    public ResponseVO getUserInfo() {
        UserInfo userInfo = userInfoService.getUserInfoByUserId(getTokenUserInfo().getUserId());
        return getSuccessResponseVO(CopyTools.copy(userInfo, UserInfoVO.class));
    }

    @RequestMapping("/updateUserInfo")
    @GlobalInterceptor(checkLogin = true)
    public ResponseVO updateUserInfo(@NotEmpty String avatar,
                                     @NotEmpty @Size(max = 20) String nickName,
                                     @NotNull Integer sex) {
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
