package com.easymall.service.Impl;

import java.util.Date;
import java.util.List;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Service;

import com.easymall.entity.enums.PageSize;
import com.easymall.entity.query.RagQuestionQuery;
import com.easymall.entity.po.RagQuestion;
import com.easymall.entity.vo.PaginationResultVO;
import com.easymall.entity.query.SimplePage;
import com.easymall.mappers.RagQuestionMapper;
import com.easymall.service.RagQuestionService;
import com.easymall.utils.StringTools;


/**
 * rag问题 业务接口实现
 */
@Service("ragQuestionService")
public class RagQuestionServiceImpl implements RagQuestionService {

	@Resource
	private RagQuestionMapper<RagQuestion, RagQuestionQuery> ragQuestionMapper;

	/**
	 * 根据条件查询列表
	 */
	@Override
	public List<RagQuestion> findListByParam(RagQuestionQuery param) {
		return this.ragQuestionMapper.selectList(param);
	}

	/**
	 * 根据条件查询列表
	 */
	@Override
	public Integer findCountByParam(RagQuestionQuery param) {
		return this.ragQuestionMapper.selectCount(param);
	}

	/**
	 * 分页查询方法
	 */
	@Override
	public PaginationResultVO<RagQuestion> findListByPage(RagQuestionQuery param) {
		int count = this.findCountByParam(param);
		int pageSize = param.getPageSize() == null ? PageSize.SIZE15.getSize() : param.getPageSize();

		SimplePage page = new SimplePage(param.getPageNo(), count, pageSize);
		param.setSimplePage(page);
		List<RagQuestion> list = this.findListByParam(param);
		PaginationResultVO<RagQuestion> result = new PaginationResultVO(count, page.getPageSize(), page.getPageNo(), page.getPageTotal(), list);
		return result;
	}

	/**
	 * 新增
	 */
	@Override
	public Integer add(RagQuestion bean) {
		return this.ragQuestionMapper.insert(bean);
	}

	/**
	 * 批量新增
	 */
	@Override
	public Integer addBatch(List<RagQuestion> listBean) {
		if (listBean == null || listBean.isEmpty()) {
			return 0;
		}
		return this.ragQuestionMapper.insertBatch(listBean);
	}

	/**
	 * 批量新增或者修改
	 */
	@Override
	public Integer addOrUpdateBatch(List<RagQuestion> listBean) {
		if (listBean == null || listBean.isEmpty()) {
			return 0;
		}
		return this.ragQuestionMapper.insertOrUpdateBatch(listBean);
	}

	/**
	 * 多条件更新
	 */
	@Override
	public Integer updateByParam(RagQuestion bean, RagQuestionQuery param) {
		StringTools.checkParam(param);
		return this.ragQuestionMapper.updateByParam(bean, param);
	}

	/**
	 * 多条件删除
	 */
	@Override
	public Integer deleteByParam(RagQuestionQuery param) {
		StringTools.checkParam(param);
		return this.ragQuestionMapper.deleteByParam(param);
	}

	/**
	 * 根据QuestionId获取对象
	 */
	@Override
	public RagQuestion getRagQuestionByQuestionId(Integer questionId) {
		return this.ragQuestionMapper.selectByQuestionId(questionId);
	}

	/**
	 * 根据QuestionId修改
	 */
	@Override
	public Integer updateRagQuestionByQuestionId(RagQuestion bean, Integer questionId) {
		return this.ragQuestionMapper.updateByQuestionId(bean, questionId);
	}

	/**
	 * 根据QuestionId删除
	 */
	@Override
	public Integer deleteRagQuestionByQuestionId(Integer questionId) {
		return this.ragQuestionMapper.deleteByQuestionId(questionId);
	}

	/**
	 * 新增/修改Rag问题
	 * @param ragQuestion
	 */
	@Override
	public void saveRagQuestion(RagQuestion ragQuestion) {
		//判断是新增还是修改
		if (ragQuestion.getQuestionId() == null) {
			//新增
			ragQuestion.setCreateTime(new Date());
			this.add(ragQuestion);
		}else {
			this.ragQuestionMapper.updateByQuestionId(ragQuestion,ragQuestion.getQuestionId());
		}

		//TODO: 存入向量数据库
	}

	/**
	 * 删除Rag问题
	 * @param questionId
	 */
	@Override
	public void delRagQuestion(Integer questionId) {
		this.deleteRagQuestionByQuestionId(questionId);

		//TODO: 删除向量数据库
	}
}