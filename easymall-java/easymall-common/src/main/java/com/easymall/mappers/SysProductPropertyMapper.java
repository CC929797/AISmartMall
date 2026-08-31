package com.easymall.mappers;

import org.apache.ibatis.annotations.Param;

/**
 *  数据库操作接口
 */
public interface SysProductPropertyMapper<T,P> extends BaseMapper<T,P> {

	/**
	 * 根据PropertyId更新
	 */
	 Integer updateByPropertyId(@Param("bean") T t,@Param("propertyId") String propertyId);


	/**
	 * 根据PropertyId删除
	 */
	 Integer deleteByPropertyId(@Param("propertyId") String propertyId);


	/**
	 * 根据PropertyId获取对象
	 */
	 T selectByPropertyId(@Param("propertyId") String propertyId);

	/**
	 * 根据此属性的父分类下的属性排序获取最大排序
	 * @param categoryId
	 * @return
	 */
	Integer selectMaxSort(@Param("categoryId") String categoryId);


}
