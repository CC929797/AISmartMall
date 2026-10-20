package com.easymall.service.Impl;

import java.util.Date;
import java.util.List;

import com.easymall.entity.enums.AgentMessageStatusEnum;
import jakarta.annotation.Resource;

import org.springframework.stereotype.Service;

import com.easymall.entity.enums.PageSize;
import com.easymall.entity.query.AgentMessageQuery;
import com.easymall.entity.po.AgentMessage;
import com.easymall.entity.vo.PaginationResultVO;
import com.easymall.entity.query.SimplePage;
import com.easymall.mappers.AgentMessageMapper;
import com.easymall.service.AgentMessageService;
import com.easymall.utils.StringTools;


/**
 *  业务接口实现
 */
@Service("agentMessageService")
public class AgentMessageServiceImpl implements AgentMessageService {

	@Resource
	private AgentMessageMapper<AgentMessage, AgentMessageQuery> agentMessageMapper;

	/**
	 * 根据条件查询列表
	 */
	@Override
	public List<AgentMessage> findListByParam(AgentMessageQuery param) {
		return this.agentMessageMapper.selectList(param);
	}

	/**
	 * 根据条件查询列表
	 */
	@Override
	public Integer findCountByParam(AgentMessageQuery param) {
		return this.agentMessageMapper.selectCount(param);
	}

	/**
	 * 分页查询方法
	 */
	@Override
	public PaginationResultVO<AgentMessage> findListByPage(AgentMessageQuery param) {
		int count = this.findCountByParam(param);
		int pageSize = param.getPageSize() == null ? PageSize.SIZE15.getSize() : param.getPageSize();

		SimplePage page = new SimplePage(param.getPageNo(), count, pageSize);
		param.setSimplePage(page);
		List<AgentMessage> list = this.findListByParam(param);
		PaginationResultVO<AgentMessage> result = new PaginationResultVO(count, page.getPageSize(), page.getPageNo(), page.getPageTotal(), list);
		return result;
	}

	/**
	 * 新增
	 */
	@Override
	public Integer add(AgentMessage bean) {
		return this.agentMessageMapper.insert(bean);
	}

	/**
	 * 批量新增
	 */
	@Override
	public Integer addBatch(List<AgentMessage> listBean) {
		if (listBean == null || listBean.isEmpty()) {
			return 0;
		}
		return this.agentMessageMapper.insertBatch(listBean);
	}

	/**
	 * 批量新增或者修改
	 */
	@Override
	public Integer addOrUpdateBatch(List<AgentMessage> listBean) {
		if (listBean == null || listBean.isEmpty()) {
			return 0;
		}
		return this.agentMessageMapper.insertOrUpdateBatch(listBean);
	}

	/**
	 * 多条件更新
	 */
	@Override
	public Integer updateByParam(AgentMessage bean, AgentMessageQuery param) {
		StringTools.checkParam(param);
		return this.agentMessageMapper.updateByParam(bean, param);
	}

	/**
	 * 多条件删除
	 */
	@Override
	public Integer deleteByParam(AgentMessageQuery param) {
		StringTools.checkParam(param);
		return this.agentMessageMapper.deleteByParam(param);
	}

	/**
	 * 根据MessageId获取对象
	 */
	@Override
	public AgentMessage getAgentMessageByMessageId(Integer messageId) {
		return this.agentMessageMapper.selectByMessageId(messageId);
	}

	/**
	 * 根据MessageId修改
	 */
	@Override
	public Integer updateAgentMessageByMessageId(AgentMessage bean, Integer messageId) {
		return this.agentMessageMapper.updateByMessageId(bean, messageId);
	}

	/**
	 * 根据MessageId删除
	 */
	@Override
	public Integer deleteAgentMessageByMessageId(Integer messageId) {
		return this.agentMessageMapper.deleteByMessageId(messageId);
	}

	/**
	 * 保存消息
	 * @param userId 用户ID
	 * @param userMessage 用户消息
	 */
	@Override
	public AgentMessage saveMessage(String userId, String userMessage) {
		AgentMessage agentMessage = new AgentMessage();
		agentMessage.setUserMessage(userMessage);
		agentMessage.setUserId(userId);
		agentMessage.setSendTime(new Date());
		agentMessage.setStatus(AgentMessageStatusEnum.NORMAL.getStatus());
		this.agentMessageMapper.insert(agentMessage);

		return agentMessage;
	}

	/**
	 * 处理消息完毕(更新消息状态)
	 * @param messageId 消息ID
	 * @param bizType 业务类型
	 * @param assistantMessage 机器人回复消息
	 * @param bizData 业务数据
	 */
	@Override
	public void completeMessage(Integer messageId, String bizType, String assistantMessage, String bizData) {
		AgentMessage agentMessage = new AgentMessage();
		agentMessage.setAssistantMessage(assistantMessage);
		agentMessage.setBizData(bizData);
		agentMessage.setBizType(bizType);
		agentMessage.setStatus(AgentMessageStatusEnum.COMPLETE.getStatus());

		AgentMessageQuery query = new AgentMessageQuery();
		query.setMessageId(messageId);
		query.setStatus(AgentMessageStatusEnum.NORMAL.getStatus());
		this.agentMessageMapper.updateByParam(agentMessage, query);
	}
}