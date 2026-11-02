package com.easymall.entity.query;
import com.easymall.entity.enums.PageSize;
import com.easymall.validation.InputLimits;
import com.easymall.validation.InputValidator;


public class SimplePage {
	private int pageNo;
	private int countTotal;
	private int pageSize;
	private int pageTotal;
	private int start;
	private int end;

	public SimplePage() {
	}

	public SimplePage(Integer pageNo, int countTotal, int pageSize) {
		if (pageNo != null && pageNo > 0) { InputValidator.optionalPageNo(pageNo); }
		if (pageSize > 0) { InputValidator.optionalPageSize(pageSize); }
		if (null == pageNo) {
			pageNo = 0;
		}
		this.pageNo = pageNo;
		this.countTotal = countTotal;
		this.pageSize = pageSize;
		action();
	}

	public SimplePage(int start, int end) {
		InputValidator.requireRange(start, 0, InputLimits.MAX_PAGE_NO * InputLimits.MAX_PAGE_SIZE);
		InputValidator.requireRange(end, 1, InputLimits.MAX_PAGE_SIZE);
		this.start = start;
		this.end = end;
	}

	public void action() {
		if (this.pageSize <= 0) {
			this.pageSize = PageSize.SIZE20.getSize();
		}
		if (this.countTotal > 0) {
			this.pageTotal = this.countTotal % this.pageSize == 0 ? this.countTotal / this.pageSize
					: this.countTotal / this.pageSize + 1;
		} else {
			pageTotal = 1;
		}

		if (pageNo <= 1) {
			pageNo = 1;
		}
		if (pageNo > pageTotal) {
			pageNo = pageTotal;
		}
		this.start = (pageNo - 1) * pageSize;
		this.end = this.pageSize;
	}

	public int getStart() {
		return start;
	}

	public int getEnd() {
		return end;
	}

	public int getPageTotal() {
		return pageTotal;
	}

	public int getPageNo() {
		return pageNo;
	}

	public void setPageNo(int pageNo) {
		InputValidator.optionalPageNo(pageNo);
		this.pageNo = pageNo;
	}

	public void setPageTotal(int pageTotal) {
		this.pageTotal = pageTotal;
	}

	public int getCountTotal() {
		return countTotal;
	}

	public int getPageSize() {
		return pageSize;
	}

	public void setStart(int start) {
		InputValidator.requireRange(start, 0, InputLimits.MAX_PAGE_NO * InputLimits.MAX_PAGE_SIZE);
		this.start = start;
	}

	public void setEnd(int end) {
		InputValidator.requireRange(end, 1, InputLimits.MAX_PAGE_SIZE);
		this.end = end;
	}

	public void setCountTotal(int countTotal) {
		this.countTotal = countTotal;
		this.action();
	}

	public void setPageSize(int pageSize) {
		InputValidator.optionalPageSize(pageSize);
		this.pageSize = pageSize;
	}

}
