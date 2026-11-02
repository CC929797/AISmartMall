package com.easymall.entity.query;

import com.easymall.validation.InputValidator;


public class BaseParam {
	private SimplePage simplePage;
	private Integer pageNo;
	private Integer pageSize;
	private String orderBy;
	public SimplePage getSimplePage() {
		return simplePage;
	}

	public void setSimplePage(SimplePage simplePage) {
		this.simplePage = simplePage;
	}

	public Integer getPageNo() {
		return pageNo;
	}

	public void setPageNo(Integer pageNo) {
		InputValidator.optionalPageNo(pageNo);
		this.pageNo = pageNo;
	}

	public Integer getPageSize() {
		return pageSize;
	}

	public void setPageSize(Integer pageSize) {
		InputValidator.optionalPageSize(pageSize);
		this.pageSize = pageSize;
	}

	public void setOrderBy(String orderBy){
		this.orderBy = orderBy;
	}

	public String getOrderBy(){
		return this.orderBy;
	}
}
