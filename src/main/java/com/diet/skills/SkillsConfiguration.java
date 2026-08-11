package com.diet.skills;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Skills Registry 装配（M5 #50）：启动时从 classpath 的 skills/*.yaml 加载并校验，
 * 任何非法 manifest（Schema 不可解析、工具越界、重复 name）都会使启动失败。
 */
@Configuration
public class SkillsConfiguration {

    @Bean
    public SkillsRegistry skillsRegistry() {
        return SkillResources.loadClasspathSkills();
    }
}
