package com.easymall.entity.dto.request;

import com.easymall.entity.query.UserInfoQuery;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;

/** 后台用户列表只允许昵称模糊查询、状态、分页和明确的排序选项。 */
@Getter @Setter
public class AdminUserListRequest extends PageListRequest {
    @Size(max = 100)
    private String nickNameFuzzy;
    @Min(0) @Max(1)
    private Integer status;

    public UserInfoQuery toQuery() {
        UserInfoQuery query = prepare(new UserInfoQuery(), "UserInfoMapper", "u.join_time desc",
                Map.of("joinTime", "u.join_time", "lastLoginTime", "u.last_login_time",
                        "nickName", "u.nick_name", "status", "u.status"));
        query.setNickNameFuzzy(nickNameFuzzy);
        query.setStatus(status);
        return query;
    }
}
