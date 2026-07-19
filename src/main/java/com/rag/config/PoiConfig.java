package com.rag.config;

import jakarta.annotation.PostConstruct;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.util.IOUtils;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PoiConfig {
    @PostConstruct
    public void configPoi() {
        IOUtils.setByteArrayMaxOverride(200_000_000);
        ZipSecureFile.setMinInflateRatio(0.001);
        ZipSecureFile.setMaxEntrySize(500L * 1024 * 1024);
        ZipSecureFile.setMaxTextSize(200L * 1024 * 1024);
    }
}
