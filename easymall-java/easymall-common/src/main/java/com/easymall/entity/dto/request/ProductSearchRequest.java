package com.easymall.entity.dto.request;

import com.easymall.entity.enums.ResponseCodeEnum;
import com.easymall.exception.BusinessException;
import com.easymall.validation.InputLimits;
import com.easymall.validation.InputValidator;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/** 保持现有搜索表单参数名，明确排序枚举、价格及 ES 分页窗口边界。 */
@Getter @Setter
public class ProductSearchRequest {
    @NotBlank @Size(max = 100)
    private String keyWords;
    @DecimalMin("0")
    private BigDecimal priceFrom;
    @DecimalMin("0")
    private BigDecimal priceTo;
    @Pattern(regexp = "(?:asc|desc)?")
    private String sortType;
    @Pattern(regexp = "(?:composite|sale|price)?")
    private String sortField;
    @Min(1) @Max(InputLimits.MAX_PAGE_NO)
    private Integer pageNo;

    public void validate() {
        InputValidator.validateBean(this);
        // 默认 ES 结果窗口为一万条，超出时明确拒绝，不能被异常捕获伪装成空结果。
        long page = pageNo == null ? 1L : pageNo;
        if (page * 15 > 10_000 || (priceFrom != null && priceTo != null && priceFrom.compareTo(priceTo) > 0)) {
            throw new BusinessException(ResponseCodeEnum.CODE_600);
        }
    }
}
