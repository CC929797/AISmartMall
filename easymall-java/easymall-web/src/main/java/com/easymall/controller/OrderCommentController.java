package com.easymall.controller;

import com.easymall.annotation.GlobalInterceptor;
import com.easymall.entity.enums.CommentStatusEnum;
import com.easymall.entity.enums.ResponseCodeEnum;
import com.easymall.entity.po.OrderComment;
import com.easymall.entity.query.OrderCommentQuery;
import com.easymall.entity.vo.PaginationResultVO;
import com.easymall.entity.vo.ResponseVO;
import com.easymall.exception.BusinessException;
import com.easymall.service.OrderCommentService;
import com.easymall.validation.AiCommentInputs;
import com.easymall.validation.InputLimits;
import com.easymall.validation.InputValidator;
import jakarta.annotation.Resource;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/order/comment")
public class OrderCommentController extends ABaseController{
    @Resource
    private OrderCommentService orderCommentService;

    /**
     * 在商品下方展示评论
     * @param productId 商品ID
     * @param pageNo 页码
     */
    @RequestMapping("/loadComment")
    public ResponseVO loadComment(@NotBlank @Size(max = 15) String productId,
                                  @Min(1) @Max(InputLimits.MAX_PAGE_NO) Integer pageNo){
        InputValidator.requireText(productId, 15);
        InputValidator.optionalPageNo(pageNo);
        OrderCommentQuery orderCommentQuery = new OrderCommentQuery();
        orderCommentQuery.setProductId(productId);
        orderCommentQuery.setPageNo(pageNo);
        orderCommentQuery.setOrderBy("comment_time desc");
        orderCommentQuery.setStatus(CommentStatusEnum.NORMAL.getStatus());
        orderCommentQuery.setQueryUserInfo(true);

        PaginationResultVO<OrderComment> resultVO = this.orderCommentService.findListByPage(orderCommentQuery);

        return getSuccessResponseVO(resultVO);
    }

    /**
     * 提交评价
     * @param orderId 订单ID
     * @param commentContent 评价内容
     * @param commentImages 评价图片
     * @param star 评价星级
     */
    @RequestMapping("/postComment")
    @GlobalInterceptor(checkLogin = true)
    public ResponseVO postComment(@NotBlank @Size(max = 32) String orderId,
                                  @NotBlank @Size(max = InputLimits.MAX_COMMENT_LENGTH) String commentContent,
                                  @Size(max = 300) String commentImages,
                                  @NotNull @Max(5) @Min(1) Integer star){
        String userId = getTokenUserInfo().getUserId();
        AiCommentInputs.requireComment(userId, orderId, commentContent, commentImages, star);
        orderCommentService.postComment(userId,orderId,commentContent,commentImages,star);
        return getSuccessResponseVO(null);

    }

    /**
     * 追评时获取要追评的订单评价
     */
    @RequestMapping("/getComment")
    @GlobalInterceptor(checkLogin = true)
    public ResponseVO getComment(@NotBlank @Size(max = 32) String orderId){
        InputValidator.requireText(orderId, 32);
        OrderComment orderComment = orderCommentService.getOrderCommentByOrderId(orderId);
        if(orderComment == null || !getTokenUserInfo().getUserId().equals(orderComment.getUserId())){
            throw new BusinessException(ResponseCodeEnum.CODE_600);
        }
        return getSuccessResponseVO(orderComment);

    }

    /**
     * 追加评价
     * @param orderId 订单ID
     * @param reCommentContent 评价内容
     * @param reCommentImages 评价图片
     */
    @RequestMapping("/postReComment")
    @GlobalInterceptor(checkLogin = true)
    public ResponseVO postReComment(@NotBlank @Size(max = 32) String orderId,
                                  @NotBlank @Size(max = InputLimits.MAX_COMMENT_LENGTH) String reCommentContent,
                                  @Size(max = 300) String reCommentImages){
        String userId = getTokenUserInfo().getUserId();
        AiCommentInputs.requireReComment(userId, orderId, reCommentContent, reCommentImages);
        orderCommentService.postReComment(userId,orderId,reCommentContent,reCommentImages);
        return getSuccessResponseVO(null);
    }

    /**
     * 查询我的评价
     * @param pageNo 页码
     * @return 查询结果
     */
    @RequestMapping("/loadMyComment")
    @GlobalInterceptor(checkLogin = true)
    public ResponseVO loadMyComment(@Min(1) @Max(InputLimits.MAX_PAGE_NO) Integer pageNo){
        InputValidator.optionalPageNo(pageNo);
        OrderCommentQuery orderCommentQuery = new OrderCommentQuery();
        orderCommentQuery.setUserId(getTokenUserInfo().getUserId());
        orderCommentQuery.setPageNo(pageNo);
        orderCommentQuery.setOrderBy("comment_time desc");
        orderCommentQuery.setStatus(CommentStatusEnum.NORMAL.getStatus());
        orderCommentQuery.setQueryProduct(true);
        PaginationResultVO<OrderComment> resultVO = this.orderCommentService.findListByPage(orderCommentQuery);

        return getSuccessResponseVO(resultVO);
    }

    /**
     * 删除我的评价
     * @param orderId 订单ID
     */
    @RequestMapping("/delMyComment")
    @GlobalInterceptor(checkLogin = true)
    public ResponseVO delMyComment(@NotBlank @Size(max = 32) String orderId){
        InputValidator.requireText(orderId, 32);
        OrderComment orderComment = new OrderComment();
        orderComment.setStatus(CommentStatusEnum.DEL.getStatus());

        OrderCommentQuery orderCommentQuery = new OrderCommentQuery();
        orderCommentQuery.setOrderId(orderId);
        orderCommentQuery.setUserId(getTokenUserInfo().getUserId());
        if (orderCommentService.updateByParam(orderComment, orderCommentQuery) != 1) {
            throw new BusinessException(ResponseCodeEnum.CODE_600);
        }

        return getSuccessResponseVO(null);
    }
}
