package com.easymall.entity.po;

import com.easymall.entity.vo.PaginationResultVO;
import com.easymall.entity.vo.ResponseVO;
import com.easymall.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Defense in depth if an entity reaches an HTTP response or a diagnostic JSON path.
 * Assertions deliberately avoid printing credential values, even for these fixtures.
 */
class UserInfoSerializationTest {

    private static final String FIXTURE_USER_ID = "SECUSER001";
    private static final String FIXTURE_PASSWORD = "synthetic-password-not-a-real-credential";
    private static final String FIXTURE_API_KEY = "synthetic-api-key-not-a-real-credential";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ParameterizedTest
    @EnumSource(Serializer.class)
    void credentialsAreAbsentFromUserJson(Serializer serializer) throws Exception {
        assertSafeJson(serializer.serialize(user()));
    }

    @ParameterizedTest
    @EnumSource(Serializer.class)
    void credentialsStayAbsentInsidePagedResponseEnvelopes(Serializer serializer) throws Exception {
        PaginationResultVO<UserInfo> page = new PaginationResultVO<>(1, 15, 1, 1, List.of(user()));
        ResponseVO<PaginationResultVO<UserInfo>> response = new ResponseVO<>();
        response.setStatus("success");
        response.setCode(200);
        response.setData(page);

        String json = serializer.serialize(response);
        assertSafeJson(json);
        assertThat(MAPPER.readTree(json).path("data").path("totalCount").asInt()).isEqualTo(1);
    }

    @Test
    void credentialsAreAbsentFromEntityToString() {
        String diagnostic = user().toString();

        assertNoCredentialValues(diagnostic);
        assertThat(diagnostic.contains(FIXTURE_USER_ID)).as("safe entity identity remains available").isTrue();
    }

    @ParameterizedTest
    @EnumSource(Serializer.class)
    void internalCredentialReadingAndDeserializationRemainCompatible(Serializer serializer) throws Exception {
        String fixture = "{\"userId\":\"" + FIXTURE_USER_ID + "\",\"password\":\"" + FIXTURE_PASSWORD
                + "\",\"apiKey\":\"" + FIXTURE_API_KEY + "\"}";
        UserInfo parsed = serializer.deserialize(fixture);

        assertThat(FIXTURE_PASSWORD.equals(parsed.getPassword()))
                .as("internal password accessor is preserved").isTrue();
        assertThat(FIXTURE_API_KEY.equals(parsed.getApiKey()))
                .as("internal API key accessor is preserved").isTrue();
    }

    private static UserInfo user() {
        UserInfo user = new UserInfo();
        user.setUserId(FIXTURE_USER_ID);
        user.setNickName("Synthetic security fixture");
        user.setEmail("serialization@example.invalid");
        user.setPassword(FIXTURE_PASSWORD);
        user.setApiKey(FIXTURE_API_KEY);
        return user;
    }

    private static void assertSafeJson(String json) throws Exception {
        JsonNode root = MAPPER.readTree(json);
        assertThat(root.findValues("password").isEmpty()).as("password field is not serialized").isTrue();
        assertThat(root.findValues("apiKey").isEmpty()).as("API key field is not serialized").isTrue();
        assertThat(root.findValues("userId").stream().anyMatch(value -> FIXTURE_USER_ID.equals(value.asText())))
                .as("safe user fields are still serialized").isTrue();
        assertNoCredentialValues(json);
    }

    private static void assertNoCredentialValues(String output) {
        assertThat(output.contains(FIXTURE_PASSWORD)).as("password value must not escape").isFalse();
        assertThat(output.contains(FIXTURE_API_KEY)).as("API key value must not escape").isFalse();
    }

    enum Serializer {
        JACKSON {
            @Override
            String serialize(Object value) throws Exception {
                return MAPPER.writeValueAsString(value);
            }

            @Override
            UserInfo deserialize(String json) throws Exception {
                return MAPPER.readValue(json, UserInfo.class);
            }
        },
        PROJECT_FASTJSON {
            @Override
            String serialize(Object value) {
                return JsonUtils.convertObj2Json(value);
            }

            @Override
            UserInfo deserialize(String json) {
                return JsonUtils.convertJson2Obj(json, UserInfo.class);
            }
        };

        abstract String serialize(Object value) throws Exception;

        abstract UserInfo deserialize(String json) throws Exception;
    }
}
