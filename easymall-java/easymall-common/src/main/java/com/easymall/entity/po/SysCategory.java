package com.easymall.entity.po;

import java.io.Serializable;


/**
 * 
 */
public class SysCategory implements Serializable {


	/**
	 * 
	 */
	private String categoryId;

	/**
	 * 
	 */
	private String categoryName;

	/**
	 * 
	 */
	private String pCategoryId;

	/**
	 * 
	 */
	private Integer sort;


	public void setCategoryId(String categoryId){
		this.categoryId = categoryId;
	}

	public String getCategoryId(){
		return this.categoryId;
	}

	public void setCategoryName(String categoryName){
		this.categoryName = categoryName;
	}

	public String getCategoryName(){
		return this.categoryName;
	}

	public void setpCategoryId(String pCategoryId){
		this.pCategoryId = pCategoryId;
	}

	public String getpCategoryId(){
		return this.pCategoryId;
	}

	public void setSort(Integer sort){
		this.sort = sort;
	}

	public Integer getSort(){
		return this.sort;
	}

	@Override
	public String toString (){
		return "categoryId:"+(categoryId == null ? "空" : categoryId)+"，categoryName:"+(categoryName == null ? "空" : categoryName)+"，pCategoryId:"+(pCategoryId == null ? "空" : pCategoryId)+"，sort:"+(sort == null ? "空" : sort);
	}
}
