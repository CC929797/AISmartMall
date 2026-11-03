package com.easymall.controller;

import com.easymall.entity.vo.ResponseVO;
import com.easymall.utils.FileUtils;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 上传由默认鉴权保护，公开读取仅提供受控目录中的图片。 */
@Validated
@RestController
@RequestMapping("/file")
public class FileController extends ABaseController {
    @Resource private FileUtils fileUtils;

    @PostMapping("/uploadImage")
    public ResponseVO uploadImage(@NotNull MultipartFile file, Boolean createThumbnail) throws Exception {
        return getSuccessResponseVO(fileUtils.uploadImage(file, createThumbnail));
    }

    @RequestMapping("/getResource")
    public void getResource(HttpServletResponse response, @NotEmpty String sourceName) throws Exception {
        fileUtils.readImage(response, sourceName);
    }
}