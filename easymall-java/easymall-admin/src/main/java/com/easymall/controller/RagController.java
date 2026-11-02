package com.easymall.controller;

import com.easymall.entity.po.RagQuestion;
import com.easymall.entity.dto.request.AdminRagListRequest;
import com.easymall.entity.query.RagQuestionQuery;
import com.easymall.entity.vo.ResponseVO;
import com.easymall.service.RagQuestionService;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/rag")
public class RagController extends ABaseController{
    @Resource
    private RagQuestionService ragQuestionService;

    /**
     * 根据条件查询rag问题列表
     * @param request 页面允许提交的知识库筛选和分页条件
     * @return
     */
    @RequestMapping("/loadRagQuestion")
    public ResponseVO loadRagQuestion(@Valid AdminRagListRequest request) {
        RagQuestionQuery questionQuery = request.toQuery();
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
