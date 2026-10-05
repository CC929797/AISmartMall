package com.easymall.service.Impl;

import java.util.List;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Service;

import com.easymall.entity.enums.PageSize;
import com.easymall.entity.query.StatisticsInfoQuery;
import com.easymall.entity.po.StatisticsInfo;
import com.easymall.entity.vo.PaginationResultVO;
import com.easymall.entity.query.SimplePage;
import com.easymall.mappers.StatisticsInfoMapper;
import com.easymall.service.StatisticsInfoService;
import com.easymall.utils.StringTools;


/**
 * 数据统计结果 业务接口实现
 */
@Service("statisticsInfoService")
public class StatisticsInfoServiceImpl implements StatisticsInfoService {

	@Resource
	private StatisticsInfoMapper<StatisticsInfo, StatisticsInfoQuery> statisticsInfoMapper;

	/**
	 * 根据条件查询列表
	 */
	@Override
	public List<StatisticsInfo> findListByParam(StatisticsInfoQuery param) {
		return this.statisticsInfoMapper.selectList(param);
	}

	/**
	 * 根据条件查询列表
	 */
	@Override
	public Integer findCountByParam(StatisticsInfoQuery param) {
		return this.statisticsInfoMapper.selectCount(param);
	}

	/**
	 * 分页查询方法
	 */
	@Override
	public PaginationResultVO<StatisticsInfo> findListByPage(StatisticsInfoQuery param) {
		int count = this.findCountByParam(param);
		int pageSize = param.getPageSize() == null ? PageSize.SIZE15.getSize() : param.getPageSize();

		SimplePage page = new SimplePage(param.getPageNo(), count, pageSize);
		param.setSimplePage(page);
		List<StatisticsInfo> list = this.findListByParam(param);
		PaginationResultVO<StatisticsInfo> result = new PaginationResultVO(count, page.getPageSize(), page.getPageNo(), page.getPageTotal(), list);
		return result;
	}

	/**
	 * 新增
	 */
	@Override
	public Integer add(StatisticsInfo bean) {
		return this.statisticsInfoMapper.insert(bean);
	}

	/**
	 * 批量新增
	 */
	@Override
	public Integer addBatch(List<StatisticsInfo> listBean) {
		if (listBean == null || listBean.isEmpty()) {
			return 0;
		}
		return this.statisticsInfoMapper.insertBatch(listBean);
	}

	/**
	 * 批量新增或者修改
	 */
	@Override
	public Integer addOrUpdateBatch(List<StatisticsInfo> listBean) {
		if (listBean == null || listBean.isEmpty()) {
			return 0;
		}
		return this.statisticsInfoMapper.insertOrUpdateBatch(listBean);
	}

	/**
	 * 多条件更新
	 */
	@Override
	public Integer updateByParam(StatisticsInfo bean, StatisticsInfoQuery param) {
		StringTools.checkParam(param);
		return this.statisticsInfoMapper.updateByParam(bean, param);
	}

	/**
	 * 多条件删除
	 */
	@Override
	public Integer deleteByParam(StatisticsInfoQuery param) {
		StringTools.checkParam(param);
		return this.statisticsInfoMapper.deleteByParam(param);
	}

	/**
	 * 根据StatisticsDateAndDataType获取对象
	 */
	@Override
	public StatisticsInfo getStatisticsInfoByStatisticsDateAndDataType(String statisticsDate, Integer dataType) {
		return this.statisticsInfoMapper.selectByStatisticsDateAndDataType(statisticsDate, dataType);
	}

	/**
	 * 根据StatisticsDateAndDataType修改
	 */
	@Override
	public Integer updateStatisticsInfoByStatisticsDateAndDataType(StatisticsInfo bean, String statisticsDate, Integer dataType) {
		return this.statisticsInfoMapper.updateByStatisticsDateAndDataType(bean, statisticsDate, dataType);
	}

	/**
	 * 根据StatisticsDateAndDataType删除
	 */
	@Override
	public Integer deleteStatisticsInfoByStatisticsDateAndDataType(String statisticsDate, Integer dataType) {
		return this.statisticsInfoMapper.deleteByStatisticsDateAndDataType(statisticsDate, dataType);
	}
}