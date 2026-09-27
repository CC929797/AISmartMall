package com.easymall.controller;

import com.easymall.commonent.RedisComponent;
import com.easymall.entity.dto.LogisticsSendDTO;
import com.easymall.entity.vo.ResponseVO;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/setting")
public class SettingController extends ABaseController{
    @Resource
    private RedisComponent redisComponent;

    /**
     * 保存物流信息到Redis
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
}
