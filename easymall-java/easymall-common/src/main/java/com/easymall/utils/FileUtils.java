package com.easymall.utils;

import com.easymall.entity.config.AppConfig;
import com.easymall.entity.constants.Constants;
import com.easymall.entity.enums.DateTimePatternEnum;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.util.Date;

@Component
public class FileUtils {
    @Resource
    private AppConfig appConfig;

    public String uploadImage(MultipartFile file,Boolean createThumbnail) throws Exception {
        String folderName = DateUtil.format(new Date(), DateTimePatternEnum.YYYYMM.getPattern() + "/");
        String folderPath = appConfig.getProjectFolder() + Constants.FILE_FOLDER_FILE + folderName;
        File folder = new File(folderPath);
        if(!folder.exists()){
            folder.mkdirs();
        }

        String fileName = StringTools.getRandomString(Constants.LENGTH_30);
        String suffix = StringTools.getFileSuffix(file.getOriginalFilename());
        String resultFileName = fileName + suffix;
        file.transferTo(new File(folderPath + resultFileName));

        if(createThumbnail != null && createThumbnail){
            String thumbnail = fileName + Constants.IMAGE_THUMBNAIL_SUFFIX + suffix;
            createImageThumbnail(folderPath + resultFileName, folderPath + thumbnail);

        }
        return folderName + resultFileName;
    }

    private void createImageThumbnail(String filePath,String thumbnailPath) {
        final String CMD_CREATE_IMAGE_THUMBNAIL = "ffmpeg -i \"%s\" -vf scale=200:-1 \"%s\"";
        String cmd = String.format(CMD_CREATE_IMAGE_THUMBNAIL, filePath, thumbnailPath);
        ProcessUtils.executeCommand(cmd, true);
    }
}
