package com.easymall.entity.config;

import com.easymall.utils.StringTools;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration("appConfig")
public class AppConfig {

    @Value("${admin.account:}")
    private String adminAccount;

    @Value("${admin.password:}")
    private String adminPassword;

    @Value("${project.folder:}")
    private String projectFolder;

    public String getProjectFolder() {
        if(!StringTools.isEmpty(projectFolder) && !projectFolder.endsWith("/")) {
            projectFolder += "/";
        }
        return projectFolder;
    }

    public String getAdminAccount() {
        return adminAccount;
    }

    public String getAdminPassword() {
        return adminPassword;
    }
}
