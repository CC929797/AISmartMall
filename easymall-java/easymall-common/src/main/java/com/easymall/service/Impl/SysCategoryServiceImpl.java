package com.easymall.service.Impl;

import java.util.ArrayList;
import java.util.List;

import com.easymall.entity.constants.Constants;
import jakarta.annotation.Resource;

import org.springframework.stereotype.Service;

import com.easymall.entity.enums.PageSize;
import com.easymall.entity.query.SysCategoryQuery;
import com.easymall.entity.po.SysCategory;
import com.easymall.entity.vo.PaginationResultVO;
import com.easymall.entity.query.SimplePage;
import com.easymall.mappers.SysCategoryMapper;
import com.easymall.service.SysCategoryService;
import com.easymall.utils.StringTools;


/**
 *  业务接口实现
 */
@Service("sysCategoryService")
public class SysCategoryServiceImpl implements SysCategoryService {

	@Resource
	private SysCategoryMapper<SysCategory, SysCategoryQuery> sysCategoryMapper;

	/**
	 * 根据条件查询列表
	 */
	@Override
	public List<SysCategory> findListByParam(SysCategoryQuery param) {

		List<SysCategory> sysCategoryList = this.sysCategoryMapper.selectList(param);
		if (sysCategoryList != null && param.getConvert2Tree()) {
			sysCategoryList = convertLine2Tree(sysCategoryList, Constants.ZERO_STR);
		}

		return sysCategoryList;
	}

	// 递归生成竖式结构
	private List<SysCategory> convertLine2Tree(List<SysCategory> dataList, String pid) {
		List<SysCategory> children = new ArrayList<>();
		for (SysCategory m : dataList) {
			if (m.getCategoryId() != null && m.getpCategoryId() != null && m.getpCategoryId().equals(pid)){
				m.setChildren(convertLine2Tree(dataList, m.getCategoryId()));
				children.add(m);
			}
		}
		return children;
	}

	/**
	 * 根据条件查询列表
	 */
	@Override
	public Integer findCountByParam(SysCategoryQuery param) {
		return this.sysCategoryMapper.selectCount(param);
	}

	/**
	 * 分页查询方法
	 */
	@Override
	public PaginationResultVO<SysCategory> findListByPage(SysCategoryQuery param) {
		int count = this.findCountByParam(param);
		int pageSize = param.getPageSize() == null ? PageSize.SIZE15.getSize() : param.getPageSize();

		SimplePage page = new SimplePage(param.getPageNo(), count, pageSize);
		param.setSimplePage(page);
		List<SysCategory> list = this.findListByParam(param);
		PaginationResultVO<SysCategory> result = new PaginationResultVO(count, page.getPageSize(), page.getPageNo(), page.getPageTotal(), list);
		return result;
	}

	/**
	 * 新增
	 */
	@Override
	public Integer add(SysCategory bean) {
		return this.sysCategoryMapper.insert(bean);
	}

	/**
	 * 批量新增
	 */
	@Override
	public Integer addBatch(List<SysCategory> listBean) {
		if (listBean == null || listBean.isEmpty()) {
			return 0;
		}
		return this.sysCategoryMapper.insertBatch(listBean);
	}

	/**
	 * 批量新增或者修改
	 */
	@Override
	public Integer addOrUpdateBatch(List<SysCategory> listBean) {
		if (listBean == null || listBean.isEmpty()) {
			return 0;
		}
		return this.sysCategoryMapper.insertOrUpdateBatch(listBean);
	}

	/**
	 * 多条件更新
	 */
	@Override
	public Integer updateByParam(SysCategory bean, SysCategoryQuery param) {
		StringTools.checkParam(param);
		return this.sysCategoryMapper.updateByParam(bean, param);
	}

	/**
	 * 多条件删除
	 */
	@Override
	public Integer deleteByParam(SysCategoryQuery param) {
		StringTools.checkParam(param);
		return this.sysCategoryMapper.deleteByParam(param);
	}

	/**
	 * 根据CategoryId获取对象
	 */
	@Override
	public SysCategory getSysCategoryByCategoryId(String categoryId) {
		return this.sysCategoryMapper.selectByCategoryId(categoryId);
	}

	/**
	 * 根据CategoryId修改
	 */
	@Override
	public Integer updateSysCategoryByCategoryId(SysCategory bean, String categoryId) {
		return this.sysCategoryMapper.updateByCategoryId(bean, categoryId);
	}

	/**
	 * 根据CategoryId删除
	 */
	@Override
	public Integer deleteSysCategoryByCategoryId(String categoryId) {
		return this.sysCategoryMapper.deleteByCategoryId(categoryId);
	}

	/**
	 * 新增或修改分类(如果是新增分类，则要设置分类的排序，设置为最高的权重)
	 * @param bean
	 */
	@Override
	public void saveCategory(SysCategory bean) {
		if(bean.getCategoryId() == null){
			bean.setCategoryId(StringTools.getRandomNumber(Constants.LENGTH_5));
			Integer maxSort = this.sysCategoryMapper.selectMaxSort(bean.getCategoryId());
			bean.setSort(maxSort + 1);
			this.sysCategoryMapper.insert(bean);
		}else {
			this.sysCategoryMapper.updateByCategoryId(bean,bean.getCategoryId());
		}
		//TODO: 存入缓存
	}

	/**
	 * 删除分类(删除分类的时候把该分类下的子分类一起删除)
	 */
	@Override
	public void delCategory(String categoryId) {
		SysCategoryQuery sysCategoryQuery = new SysCategoryQuery();
		sysCategoryQuery.setCategoryOrPCategory(categoryId);
		this.sysCategoryMapper.deleteByParam(sysCategoryQuery);
		//TODO: 将分类存进缓存
	}

	/**
	 * 更改分类排序
	 * @param categoryIds
	 */
	@Override
	public void changeSort(String categoryIds) {
		String[] categoryIdArr = categoryIds.split(",");
		List<SysCategory> sysCategoryList  = new ArrayList<>();
		Integer sort = 1;
		for (String categoryId : categoryIdArr) {
			SysCategory sysCategory = new SysCategory();
			sysCategory.setCategoryId(categoryId);
			sysCategory.setSort(sort++);
			sysCategoryList.add(sysCategory);
		}
		this.sysCategoryMapper.updateSortBatch(sysCategoryList);

	}


}