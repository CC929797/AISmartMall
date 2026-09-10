package com.easymall.controller;

import com.easymall.entity.vo.ResponseVO;
import com.easymall.service.ProductInfoService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/account")
public class AccountController extends ABaseController{

    @RequestMapping("/autoLogin")
    public ResponseVO autoLogin(){
        return getSuccessResponseVO(null);
    }
}
