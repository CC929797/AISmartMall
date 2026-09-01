package com.easymall.controller;

import com.easymall.entity.config.AppConfig;
import com.easymall.entity.constants.Constants;
import com.easymall.entity.vo.ResponseVO;
import com.easymall.utils.FileUtils;
import com.easymall.utils.StringTools;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;

@Validated
@RestController
@Slf4j
@RequestMapping("/file")
public class FileController extends ABaseController {
    @Resource
    private AppConfig appConfig;
    @Resource
    private FileUtils fileUtils;

    @RequestMapping("/uploadImage")
    public ResponseVO uploadImage(@NotNull MultipartFile file, Boolean createThumbnail) throws Exception  {
        String filePath = fileUtils.uploadImage(file, createThumbnail);
        return getSuccessResponseVO(filePath);
    }


    @RequestMapping("/getResource")
    public void getResource(HttpServletResponse response,@NotEmpty String sourceName) throws Exception {
        String suffix = StringTools.getFileSuffix(sourceName);
        response.setContentType("image/" + suffix.replace(".", ""));
        response.setHeader("Cache-Control", "max-age=25920000");
        readFile(response, sourceName);
    }
    protected void readFile(HttpServletResponse response,@NotEmpty String filePath){
        if (!StringTools.pathIsOk(filePath)) {
            return;
        }

        File file = new File(appConfig.getProjectFolder() + Constants.FILE_FOLDER_FILE + filePath);
        if(!file.exists()){
            log.info("文件不存在");
            return;
        }
        try(OutputStream out = response.getOutputStream(); FileInputStream in = new FileInputStream(file)) {
            byte[] bytes = new byte[1024];
            int len = 0;
            while ((len = in.read(bytes)) != -1){
                out.write(bytes,0 ,len);
            }
            out.flush();
        }catch (Exception e) {
            log.error("文件读取异常", e);
        }

    }
}
