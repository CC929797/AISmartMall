package com.easymall.controller;

import com.easymall.commonent.RedisComponent;
import com.easymall.entity.config.AppConfig;
import com.easymall.entity.constants.Constants;
import com.easymall.entity.vo.CheckCodeVO;
import com.easymall.entity.vo.ResponseVO;
import com.easymall.exception.BusinessException;
import com.easymall.utils.StringTools;
import com.wf.captcha.ArithmeticCaptcha;
import jakarta.annotation.Resource;
import jakarta.validation.constraints.NotEmpty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/account")
@Slf4j
public class AccountController extends ABaseController {
    @Resource
    private RedisComponent redisComponent;
    @Resource
    private AppConfig appConfig;

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

    /**
     * 登录
     * @param account 账号
     * @param password 密码
     * @param checkCode 验证码
     * @param checkCodeKey 验证码key
     * @return Token
     */
    @RequestMapping("/login")
    public ResponseVO login(@NotEmpty String account,
                        @NotEmpty String password,
                        @NotEmpty String checkCode,
                        @NotEmpty String checkCodeKey) {
        try {
            //校验验证码
            if(!checkCode.equalsIgnoreCase(redisComponent.getCheckCode(checkCodeKey))){
                throw new BusinessException("验证码错误");
            }

            if(!account.equalsIgnoreCase(appConfig.getAdminAccount()) || !password.equalsIgnoreCase(StringTools.encodeByMD5(appConfig.getAdminPassword()))) {
                throw new BusinessException("账号或密码错误");
            }
            //生成Token,并把用户名保存到Redis
            String token = redisComponent.saveTokenInfoAdmin(account);
            return getSuccessResponseVO(token);
        } finally {
            //删除验证码
            redisComponent.deleteCheckCode(checkCodeKey);
        }
    }

    @RequestMapping("/logout")
    public void logout(@RequestHeader(Constants.TOKEN_ADMIN) String token) {
        redisComponent.cleanTokenInfoAdmin(token);
    }
}
