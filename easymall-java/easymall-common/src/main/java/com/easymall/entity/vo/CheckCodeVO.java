package com.easymall.entity.vo;

public class CheckCodeVO {
    private String checkCodeKey;
    private String CheckCode;

    public CheckCodeVO() {
    }

    public CheckCodeVO(String checkCodeKey, String checkCode) {
        this.checkCodeKey = checkCodeKey;
        CheckCode = checkCode;
    }

    public String getCheckCodeKey() {
        return checkCodeKey;
    }

    public void setCheckCodeKey(String checkCodeKey) {
        this.checkCodeKey = checkCodeKey;
    }

    public String getCheckCode() {
        return CheckCode;
    }

    public void setCheckCode(String checkCode) {
        CheckCode = checkCode;
    }
}
