package com.easymall.controller;

import com.easymall.commonent.RedisComponent;
import com.easymall.entity.constants.Constants;
import com.easymall.entity.dto.request.AdminUserListRequest;
import com.easymall.entity.po.UserInfo;
import com.easymall.entity.query.UserInfoQuery;
import com.easymall.entity.vo.PaginationResultVO;
import com.easymall.entity.vo.UserAdminVO;
import com.easymall.interceptor.AppInterceptor;
import com.easymall.service.UserInfoService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Response contract plus the existing real admin interceptor; infrastructure is mocked.
 */
@ExtendWith(MockitoExtension.class)
class UserControllerSecurityTest {

    @Mock
    private UserInfoService userInfoService;

    @Mock
    private RedisComponent redisComponent;

    @InjectMocks
    private UserController controller;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void returnsDedicatedAdminRowsAndPreservesPagination() {
        when(userInfoService.findListByPage(any(UserInfoQuery.class)))
                .thenReturn(page(List.of(user())));

        PaginationResultVO<?> result = (PaginationResultVO<?>) controller.loadUser(new AdminUserListRequest()).getData();

        // Avoid printing an entity that could include synthetic sensitive values.
        assertThat(result.getList().get(0) instanceof UserAdminVO)
                .as("Admin user rows must be an explicit response model")
                .isTrue();
        assertThat(result.getTotalCount()).isEqualTo(42);
        assertThat(result.getPageSize()).isEqualTo(20);
        assertThat(result.getPageNo()).isEqualTo(2);
        assertThat(result.getPageTotal()).isEqualTo(3);
    }

    @Test
    void httpResponseContainsOnlyTheFieldsUsedByTheAdminPage() throws Exception {
        when(userInfoService.findListByPage(any(UserInfoQuery.class)))
                .thenReturn(page(List.of(user())));

        String body = mockMvc.perform(get("/user/loadUser")
                        .param("pageNo", "2")
                        .param("nickNameFuzzy", "fixture")
                        .param("status", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.totalCount").value(42))
                .andExpect(jsonPath("$.data.pageSize").value(20))
                .andExpect(jsonPath("$.data.pageNo").value(2))
                .andExpect(jsonPath("$.data.pageTotal").value(3))
                .andExpect(jsonPath("$.data.list[0].userId").value("FIXTURE001"))
                .andExpect(jsonPath("$.data.list[0].joinTime").value("2026-10-25 08:00:00"))
                .andExpect(jsonPath("$.data.list[0].lastLoginTime").value("2026-10-25 09:00:00"))
                .andReturn().getResponse().getContentAsString();

        JsonNode row = new ObjectMapper().readTree(body).path("data").path("list").get(0);
        List<String> fields = new ArrayList<>();
        row.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("userId", "nickName", "avatar", "email", "sex",
                "joinTime", "lastLoginTime", "lastLoginIp", "status");
        assertThat(body.contains("synthetic-password-never-respond"))
                .as("Password values must not be serialized").isFalse();
        assertThat(body.contains("synthetic-model-key-never-respond"))
                .as("API key values must not be serialized").isFalse();

        ArgumentCaptor<UserInfoQuery> query = ArgumentCaptor.forClass(UserInfoQuery.class);
        verify(userInfoService).findListByPage(query.capture());
        assertThat(query.getValue().getNickNameFuzzy()).isEqualTo("fixture");
        assertThat(query.getValue().getStatus()).isEqualTo(1);
        assertThat(query.getValue().getOrderBy()).isEqualTo("u.join_time desc");
    }

    @Test
    void emptyAdminPageRetainsItsMetadataAndAnEmptyList() throws Exception {
        when(userInfoService.findListByPage(any(UserInfoQuery.class)))
                .thenReturn(new PaginationResultVO<>(0, 20, 1, 0, List.of()));

        mockMvc.perform(get("/user/loadUser").param("pageNo", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(0))
                .andExpect(jsonPath("$.data.pageSize").value(20))
                .andExpect(jsonPath("$.data.pageNo").value(1))
                .andExpect(jsonPath("$.data.pageTotal").value(0))
                .andExpect(jsonPath("$.data.list").isEmpty());
    }

    @Test
    void realAdminInterceptorRejectsAnonymousAndWebOnlyTokensBeforeTheService() throws Exception {
        MockMvc protectedMvc = protectedMvc();

        protectedMvc.perform(get("/user/loadUser"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.code").value(901));
        protectedMvc.perform(get("/user/loadUser").header(Constants.TOKEN_WEB, "fixture-web-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.code").value(901));

        verifyNoInteractions(userInfoService, redisComponent);
    }

    @Test
    void realAdminInterceptorAllowsOnlyTheAdminSessionAndKeepsTheSafeContract() throws Exception {
        when(redisComponent.getLoginInfo4Admin("fixture-admin-token")).thenReturn("fixture-admin");
        when(userInfoService.findListByPage(any(UserInfoQuery.class)))
                .thenReturn(page(List.of(user())));

        String body = protectedMvc().perform(get("/user/loadUser")
                        .header(Constants.TOKEN_ADMIN, "fixture-admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.list[0].userId").value("FIXTURE001"))
                .andReturn().getResponse().getContentAsString();

        JsonNode row = new ObjectMapper().readTree(body).path("data").path("list").get(0);
        List<String> fields = new ArrayList<>();
        row.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("userId", "nickName", "avatar", "email", "sex",
                "joinTime", "lastLoginTime", "lastLoginIp", "status");
        verify(redisComponent).getLoginInfo4Admin("fixture-admin-token");
        verify(userInfoService).findListByPage(any(UserInfoQuery.class));
    }

    private MockMvc protectedMvc() {
        AppInterceptor interceptor = new AppInterceptor();
        ReflectionTestUtils.setField(interceptor, "redisComponent", redisComponent);
        return MockMvcBuilders.standaloneSetup(controller)
                .addInterceptors(interceptor)
                .setControllerAdvice(new AGlobalExceptionHandlerController())
                .build();
    }

    private static PaginationResultVO<UserInfo> page(List<UserInfo> users) {
        return new PaginationResultVO<>(42, 20, 2, 3, users);
    }

    private static UserInfo user() {
        UserInfo user = new UserInfo();
        user.setUserId("FIXTURE001");
        user.setNickName("fixture");
        user.setAvatar("fixture/avatar.png");
        user.setEmail("fixture@example.invalid");
        user.setSex(2);
        user.setJoinTime(Date.from(Instant.parse("2026-10-25T00:00:00Z")));
        user.setLastLoginTime(Date.from(Instant.parse("2026-10-25T01:00:00Z")));
        user.setLastLoginIp("192.0.2.10");
        user.setStatus(1);
        user.setPassword("synthetic-password-never-respond");
        user.setApiKey("synthetic-model-key-never-respond");
        return user;
    }
}
