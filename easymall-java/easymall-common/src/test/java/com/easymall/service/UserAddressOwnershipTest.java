package com.easymall.service;

import com.easymall.entity.enums.DefaultTypeEnum;
import com.easymall.entity.po.UserAddress;
import com.easymall.entity.query.UserAddressQuery;
import com.easymall.exception.BusinessException;
import com.easymall.mappers.UserAddressMapper;
import com.easymall.service.Impl.UserAddressServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 操作他人的地址时，必须在清除当前用户的默认地址之前拒绝请求。 */
class UserAddressOwnershipTest {
    @SuppressWarnings("unchecked")
    private final UserAddressMapper<UserAddress, UserAddressQuery> mapper = mock(UserAddressMapper.class);
    private final UserAddressServiceImpl service = new UserAddressServiceImpl();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "userAddressMapper", mapper);
    }

    @Test
    void editingBsAddressAsARejectsBeforeAnyWrite() {
        UserAddress stored = address("B", "B-ADDRESS");
        when(mapper.selectByAddressId("B-ADDRESS")).thenReturn(stored);
        UserAddress submitted = address("A", "B-ADDRESS");
        submitted.setDefaultType(DefaultTypeEnum.DEFAULT.getType());

        assertThatThrownBy(() -> service.saveAddress(submitted)).isInstanceOf(BusinessException.class);

        verify(mapper, never()).updateByParam(any(), any());
        verify(mapper, never()).insert(any());
    }

    @Test
    void settingBsAddressAsAsDefaultRejectsBeforeAnyWrite() {
        when(mapper.selectByAddressId("B-ADDRESS")).thenReturn(address("B", "B-ADDRESS"));

        assertThatThrownBy(() -> service.updateDefaultAddress("B-ADDRESS", "A"))
                .isInstanceOf(BusinessException.class);

        verify(mapper, never()).updateByParam(any(), any());
    }

    @Test
    void aMissingAddressIsRejectedBeforeChangingDefaults() {
        assertThatThrownBy(() -> service.updateDefaultAddress("MISSING", "A"))
                .isInstanceOf(BusinessException.class);
        verify(mapper, never()).updateByParam(any(), any());
    }

    @Test
    void anEmptyCallerCannotStartAnAddressWrite() {
        assertThatThrownBy(() -> service.saveAddress(address(null, null)))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(mapper);
    }

    @Test
    void aCanEditAsOwnAddressAndBothWritesRemainScopedToA() {
        when(mapper.selectByAddressId("A-ADDRESS")).thenReturn(address("A", "A-ADDRESS"));
        when(mapper.updateByParam(any(), any())).thenReturn(1);
        UserAddress submitted = address("A", "A-ADDRESS");
        submitted.setDefaultType(DefaultTypeEnum.DEFAULT.getType());

        service.saveAddress(submitted);

        ArgumentCaptor<UserAddressQuery> query = ArgumentCaptor.forClass(UserAddressQuery.class);
        verify(mapper, times(2)).updateByParam(any(), query.capture());
        assertThat(query.getAllValues()).allSatisfy(value -> assertThat(value.getUserId()).isEqualTo("A"));
        assertThat(query.getAllValues().get(1).getAddressId()).isEqualTo("A-ADDRESS");
    }

    @Test
    void aCanAddAnAddressWithoutReadingOrChangingBsAddresses() {
        UserAddress submitted = address("A", null);
        submitted.setDefaultType(DefaultTypeEnum.NOT_DEFAULT.getType());

        service.saveAddress(submitted);

        verify(mapper).insert(submitted);
        verify(mapper, never()).selectByAddressId(any());
        verify(mapper, never()).updateByParam(any(), any());
        assertThat(submitted.getUserId()).isEqualTo("A");
        assertThat(submitted.getAddressId()).isNotBlank();
    }

    private static UserAddress address(String userId, String addressId) {
        UserAddress address = new UserAddress();
        address.setUserId(userId);
        address.setAddressId(addressId);
        return address;
    }
}
