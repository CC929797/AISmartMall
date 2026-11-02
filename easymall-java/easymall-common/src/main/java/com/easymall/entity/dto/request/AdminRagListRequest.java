package com.easymall.entity.dto.request;

import com.easymall.entity.query.RagQuestionQuery;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;

/** 知识库列表查询不允许请求绑定答案、内部排序及分页对象。 */
@Getter @Setter
public class AdminRagListRequest extends PageListRequest {
    @Size(max = 200)
    private String questionFuzzy;

    public RagQuestionQuery toQuery() {
        RagQuestionQuery query = prepare(new RagQuestionQuery(), "RagQuestionMapper", "r.question_id desc",
                Map.of("questionId", "r.question_id", "createTime", "r.create_time"));
        query.setQuestionFuzzy(questionFuzzy);
        return query;
    }
}
