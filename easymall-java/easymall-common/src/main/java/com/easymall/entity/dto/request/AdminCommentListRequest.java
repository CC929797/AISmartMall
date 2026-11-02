package com.easymall.entity.dto.request;

import com.easymall.entity.query.OrderCommentQuery;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;

/** 评论列表只接受页面实际使用的昵称、商品名、分页及排序条件。 */
@Getter @Setter
public class AdminCommentListRequest extends PageListRequest {
    @Size(max = 100)
    private String nickNameFuzzy;
    @Size(max = 200)
    private String productNameFuzzy;

    public OrderCommentQuery toQuery() {
        OrderCommentQuery query = prepare(new OrderCommentQuery(), "OrderCommentMapper", "o.comment_time desc",
                Map.of("commentTime", "o.comment_time", "star", "o.star"));
        query.setNickNameFuzzy(nickNameFuzzy);
        query.setProductNameFuzzy(productNameFuzzy);
        query.setQueryProduct(true);
        query.setQueryUserInfo(true);
        return query;
    }
}
