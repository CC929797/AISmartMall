package com.easymall.controller;

import java.util.List;

import com.easymall.entity.query.SysCategoryQuery;
import com.easymall.entity.po.SysCategory;
import com.easymall.entity.vo.ResponseVO;
import com.easymall.service.SysCategoryService;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;

/**
 *  Controller
 */
@RestController("sysCategoryController")
@RequestMapping("/sysCategory")
public class SysCategoryController extends ABaseController{

	@Resource
	private SysCategoryService sysCategoryService;
	/**
	 * 根据条件分页查询
	 */
	@RequestMapping("/loadCategory")
	public ResponseVO loadDataList(){

		SysCategoryQuery query = new SysCategoryQuery();
		query.setConvert2Tree(true);
		query.setOrderBy("s.sort asc");

		return getSuccessResponseVO(sysCategoryService.findListByParam(query));
	}

	/**
	 * 新增
	 */
	@RequestMapping("/saveCategory")
	public ResponseVO saveCategory(SysCategory bean) {
		sysCategoryService.saveCategory(bean);
		return getSuccessResponseVO(null);
	}

	/**
	 * 批量新增
	 */
	@RequestMapping("/addBatch")
	public ResponseVO addBatch(@RequestBody List<SysCategory> listBean) {
		sysCategoryService.addBatch(listBean);
		return getSuccessResponseVO(null);
	}

	/**
	 * 批量新增/修改
	 */
	@RequestMapping("/addOrUpdateBatch")
	public ResponseVO addOrUpdateBatch(@RequestBody List<SysCategory> listBean) {
		sysCategoryService.addBatch(listBean);
		return getSuccessResponseVO(null);
	}

	/**
	 * 根据CategoryId查询对象
	 */
	@RequestMapping("/getSysCategoryByCategoryId")
	public ResponseVO getSysCategoryByCategoryId(String categoryId) {
		return getSuccessResponseVO(sysCategoryService.getSysCategoryByCategoryId(categoryId));
	}

	/**
	 * 根据CategoryId修改对象
	 */
	@RequestMapping("/updateSysCategoryByCategoryId")
	public ResponseVO updateSysCategoryByCategoryId(SysCategory bean,String categoryId) {
		sysCategoryService.updateSysCategoryByCategoryId(bean,categoryId);
		return getSuccessResponseVO(null);
	}

	/**
	 * 根据CategoryId删除
	 */
	@RequestMapping("/delCategory")
	public ResponseVO delCategory(String categoryId) {
		sysCategoryService.delCategory(categoryId);
		return getSuccessResponseVO(null);
	}

	/**
	 * 修改分类排序
	 * @param categoryIds
	 * @return
	 */
	@RequestMapping("/changeCategorySort")
	public ResponseVO changeCategorySort(String categoryIds) {
		sysCategoryService.changeSort(categoryIds);
		return getSuccessResponseVO(null);
	}
}