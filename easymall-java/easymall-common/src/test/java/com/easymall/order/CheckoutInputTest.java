package com.easymall.order;

import com.easymall.entity.dto.PostOrderDTO;
import com.easymall.entity.dto.PostOrderItemDTO;
import com.easymall.exception.BusinessException;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

/** 【仅用于测试验证，不参与正式业务运行】请求规范化、合并、摘要与规模边界，无数据库连接。 */
class CheckoutInputTest {
    @Test void duplicateSkuMergesWithoutChangingOriginalRequest() {
        PostOrderDTO original = order(item("P1", "S1", 2, ""), item("P1", "S1", 3, null));
        CheckoutInput input = CheckoutInput.normalize(original);
        assertThat(input.order().getOrderList()).singleElement().satisfies(item -> assertThat(item.getBuyCount()).isEqualTo(5));
        assertThat(original.getOrderList()).extracting(PostOrderItemDTO::getBuyCount).containsExactly(2, 3);
        assertThat(input.toString()).doesNotContain("ADDRESS", "S1");
    }
    @Test void orderAndPartitionDoNotChangeCanonicalDigest() {
        assertThat(CheckoutInput.normalize(order(item("P1", "S1", 2, ""), item("P2", "S2", 1, ""), item("P1", "S1", 3, ""))).digest())
                .isEqualTo(CheckoutInput.normalize(order(item("P2", "S2", 1, ""), item("P1", "S1", 5, ""))).digest());
    }
    @Test void purchaseContentChangesDigestButRequestIdIsNotPartOfContent() {
        PostOrderDTO base = order(item("P1", "S1", 1, "")); String digest = CheckoutInput.normalize(base).digest();
        base.setRequestId("ANOTHER-REQUEST-0001"); assertThat(CheckoutInput.normalize(base).digest()).isEqualTo(digest);
        base.setAddressId("ADDRESS2"); assertThat(CheckoutInput.normalize(base).digest()).isNotEqualTo(digest);
    }
    @Test void mergedQuantityCannotExceedLimit() {
        assertThatThrownBy(() -> CheckoutInput.normalize(order(item("P1", "S1", 99, ""), item("P1", "S1", 1, ""))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("99");
    }
    @Test void incompatibleRemarksAreNotSilentlyDiscarded() {
        assertThatThrownBy(() -> CheckoutInput.normalize(order(item("P1", "S1", 1, "甲"), item("P1", "S1", 1, "乙"))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("备注");
    }
    @Test void requestIdAndIdentifiersAreValidated() {
        PostOrderDTO whitespace = order(item(" P1", "S1", 1, ""));
        assertThatThrownBy(() -> CheckoutInput.normalize(whitespace)).isInstanceOf(BusinessException.class);
        PostOrderDTO invalid = order(item("P1", "S1", 1, "")); invalid.setRequestId(null);
        assertThatThrownBy(() -> CheckoutInput.normalize(invalid)).isInstanceOf(BusinessException.class);
    }
    static PostOrderDTO order(PostOrderItemDTO... items) {
        PostOrderDTO request = new PostOrderDTO(); request.setRequestId("UNIT-CHECKOUT-REQUEST-1");
        request.setAddressId("ADDRESS1"); request.setPayMethod("alipay_pc"); request.setOrderFrom(0); request.setOrderList(List.of(items)); return request;
    }
    static PostOrderItemDTO item(String product, String sku, int count, String remark) {
        PostOrderItemDTO item = new PostOrderItemDTO(); item.setProductId(product); item.setPropertyValueIds(sku); item.setBuyCount(count); item.setRemark(remark); return item;
    }
}
