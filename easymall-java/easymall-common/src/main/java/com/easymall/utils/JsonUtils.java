package com.easymall.utils;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.easymall.entity.enums.ResponseCodeEnum;
import com.easymall.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class JsonUtils {
    private static final Logger logger = LoggerFactory.getLogger(JsonUtils.class);

    public static String convertObj2Json(Object obj) {
        return JSON.toJSONString(obj, new JSONWriter.Feature[]{JSONWriter.Feature.WriteMapNullValue});
    }

    public static <T> T convertJson2Obj(String json, Class<T> classz) {
        try {
            return JSONObject.parseObject(json, classz);
        } catch (Exception e) {
            // 解析器消息和异常信息中可能包含原始的 JSON 负载
            logger.error("JSON 对象转换失败: targetType={}, inputLength={}, exceptionType={}",
                    classz == null ? "null" : classz.getName(),
                    json == null ? 0 : json.length(), e.getClass().getName());
            throw new BusinessException(ResponseCodeEnum.CODE_603);
        }
    }

    public static <T> List<T> convertJsonArray2List(String json, Class<T> classz) {
        try {
            return JSONArray.parseArray(json, classz);
        } catch (Exception e) {
            logger.error("JSON 数组转换失败: targetType={}, inputLength={}, exceptionType={}",
                    classz == null ? "null" : classz.getName(),
                    json == null ? 0 : json.length(), e.getClass().getName());
            throw new BusinessException(ResponseCodeEnum.CODE_603);
        }
    }
}
