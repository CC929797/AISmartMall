package com.easymall.entity.enums;

import java.util.Arrays;
import java.util.Optional;

public enum DefaultTypeEnum {

    NOT_DEFAULT(0, "非默认"),
    DEFAULT(1, "默认");

    private Integer type;
    private String desc;

    DefaultTypeEnum(Integer type, String desc) {
        this.type = type;
        this.desc = desc;
    }

    /**
     * 根据 type 获取对应的枚举对象
     * @param type 类型值
     * @return 匹配的枚举，未匹配返回 null
     */
    public static DefaultTypeEnum getByStatus(Integer type) {
        Optional<DefaultTypeEnum> result =
                Arrays.stream(DefaultTypeEnum.values())
                        .filter(value -> value.getType().equals(type)).findFirst();
        return result == null ? null : result.get();
    }

    public Integer getType() {
        return type;
    }

    public void setType(Integer type) {
        this.type = type;
    }

    public String getDesc() {
        return desc;
    }

    public void setDesc(String desc) {
        this.desc = desc;
    }
}