package com.easymall.controller;

import com.easymall.commonent.RedisComponent;
import com.easymall.entity.query.UserInfoQuery;
import com.easymall.entity.vo.PaginationResultVO;
import com.easymall.service.UserInfoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/** 列表请求只绑定页面允许的筛选条件，不能直接绑定完整持久化查询对象。 */
class AdminListRequestBoundaryTest {
    private UserInfoService users;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        users = mock(UserInfoService.class);
        UserController controller = new UserController();
        ReflectionTestUtils.setField(controller, "userInfoService", users);
        ReflectionTestUtils.setField(controller, "redisComponent", mock(RedisComponent.class));
        when(users.findListByPage(any())).thenReturn(new PaginationResultVO<>(0, 20, 1, 0, List.of()));
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void credentialAndInternalQueryFieldsCannotReachTheUserService() throws Exception {
        mvc.perform(post("/user/loadUser").param("pageNo", "1").param("pageSize", "20")
                        .param("nickNameFuzzy", "fixture").param("status", "1")
                        .param("password", "synthetic-filter").param("apiKey", "synthetic-filter")
                        .param("userIdFuzzy", "unexpected-internal-filter"))
                .andExpect(jsonPath("$.code").value(200));
        ArgumentCaptor<UserInfoQuery> query = ArgumentCaptor.forClass(UserInfoQuery.class);
        verify(users).findListByPage(query.capture());
        assertThat(query.getValue().getPassword()).isNull();
        assertThat(query.getValue().getApiKey()).isNull();
        assertThat(query.getValue().getUserIdFuzzy()).isNull();
        assertThat(query.getValue().getNickNameFuzzy()).isEqualTo("fixture");
        assertThat(query.getValue().getStatus()).isEqualTo(1);
    }
}
