package com.easymall.controller;

import com.easymall.commonent.RedisComponent;
import com.easymall.entity.dto.LogisticsSendDTO;
import com.easymall.entity.enums.PromptTypeEnum;
import com.easymall.entity.vo.ResponseVO;
import com.easymall.utils.StringTools;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/setting")
public class SettingController extends ABaseController{
    @Resource
    private RedisComponent redisComponent;

    /**
     * 保存发货人物流信息到Redis
     * @param logisticsSendDTO 物流信息
     */
    @RequestMapping("/saveLogistics")
    public ResponseVO saveLogistics(LogisticsSendDTO logisticsSendDTO) {
        redisComponent.saveLogistics(logisticsSendDTO);
        return getSuccessResponseVO(null);
    }

    /**
     * 获取物流信息
     */
    @RequestMapping("/getLogistics")
    public ResponseVO getLogistics() {
        return getSuccessResponseVO(redisComponent.getLogisticsInfo());
    }

    /**
     * 从提示词枚举类获取提示词列表
     * @return
     */
    @RequestMapping("/loadPromptList")
    public ResponseVO loadPromptList() {
        return getSuccessResponseVO(PromptTypeEnum.getPrompts());
    }

    /**
     * 根据key(意图)获取提示词
     * @param key 意图
     * @return
     */
    @RequestMapping("/getPromptDetail")
    public ResponseVO getPromptDetail(String key){
        String prompt = redisComponent.getPrompt(key);
        if(StringTools.isEmpty(prompt)){
            prompt = PromptTypeEnum.getByKey(key).getPrompt();
        }
        return getSuccessResponseVO(prompt);
    }

    /**
     * 保存提示词
     * @param key
     * @param value
     * @return
     */
    @RequestMapping("/savePrompt")
    public ResponseVO savePrompt(String key,String value) {
        redisComponent.savePrompt(key, value);
        return getSuccessResponseVO(null);
    }

    /**
     * 清空提示词
     * @param key
     * @return
     */
    @RequestMapping("/cleanPrompt")
    public ResponseVO cleanPrompt(String key) {
        redisComponent.cleanPrompt(key);
        return getSuccessResponseVO(null);
    }

}
