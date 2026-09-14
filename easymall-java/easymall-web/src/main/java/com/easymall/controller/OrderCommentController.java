package com.easymall.controller;

import com.easymall.entity.vo.PaginationResultVO;
import com.easymall.entity.vo.ResponseVO;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/order/comment")
public class OrderCommentController extends ABaseController{

    @RequestMapping("/loadComment")
    public ResponseVO loadComment(){
        return getSuccessResponseVO(new PaginationResultVO<>());
    }
}
