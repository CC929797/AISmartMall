package com.easymall.controller;

import com.easymall.entity.po.RagQuestion;
import com.easymall.entity.query.RagQuestionQuery;
import com.easymall.entity.vo.ResponseVO;
import com.easymall.service.RagQuestionService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/rag")
public class RagController extends ABaseController{
    @Resource
    private RagQuestionService ragQuestionService;

    /**
     * 根据条件查询rag问题列表
     * @param questionQuery
     * @return
     */
    @RequestMapping("/loadRagQuestion")
    public ResponseVO loadRagQuestion(RagQuestionQuery questionQuery) {
        questionQuery.setOrderBy("r.question_id desc");
        return getSuccessResponseVO(ragQuestionService.findListByPage(questionQuery));
    }

    /**
     * 新增/修改rag问题
     * @param ragQuestion
     * @return
     */
    @RequestMapping("/saveRagQuestion")
    public ResponseVO saveRagQuestion(RagQuestion ragQuestion) {
        ragQuestionService.saveRagQuestion(ragQuestion);
        return getSuccessResponseVO(null);
    }

    /**
     * 删除rag问题
     * @param questionId 问题Id
     */
    @RequestMapping("/delRagQuestion")
    public ResponseVO delRagQuestion(Integer questionId) {
        ragQuestionService.delRagQuestion(questionId);
        return getSuccessResponseVO(null);
    }
}
