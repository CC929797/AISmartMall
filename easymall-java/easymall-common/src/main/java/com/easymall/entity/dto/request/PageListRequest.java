package com.easymall.entity.dto.request;

import com.easymall.entity.query.BaseParam;
import com.easymall.validation.InputLimits;
import com.easymall.validation.InputValidator;
import com.easymall.validation.sort.SqlSortWhitelist;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;

/** 页面可提交的分页和排序参数，不暴露内部 orderBy 或 simplePage。 */
@Getter
@Setter
public class PageListRequest {
    @Min(1) @Max(InputLimits.MAX_PAGE_NO)
    private Integer pageNo;
    @Min(1) @Max(InputLimits.MAX_PAGE_SIZE)
    private Integer pageSize;
    @Size(max = 30)
    private String sortField;
    @Size(max = 4)
    private String sortDirection;

    protected <T extends BaseParam> T prepare(T query, String mapper, String defaultOrder,
                                            Map<String, String> sortFields) {
        InputValidator.validateBean(this);
        query.setPageNo(pageNo);
        query.setPageSize(pageSize);
        query.setOrderBy(SqlSortWhitelist.requested(mapper, sortField, sortDirection, defaultOrder, sortFields));
        return query;
    }
}
