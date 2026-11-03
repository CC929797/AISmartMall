package com.easymall.files;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/** 图片上传的服务端边界，可通过外部配置收紧。 */
@Getter @Setter @Validated @Component
@ConfigurationProperties(prefix = "security.images")
public class ImagePolicy {
    @Min(1) @Max(5242880) private int maxBytes = 5 * 1024 * 1024;
    @Min(1) @Max(4096) private int maxDimension = 4096;
    @Min(1) @Max(12000000) private long maxPixels = 12_000_000;
    @Min(1) @Max(100) private int requestsPerMinute = 20;
    @Min(1) private long dailyBytes = 100L * 1024 * 1024;
}
