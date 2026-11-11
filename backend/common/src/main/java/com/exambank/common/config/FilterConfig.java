package com.exambank.common.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
public class FilterConfig {

    @Bean
    public FilterRegistrationBean<ContentModerationFilter> contentModerationFilter() {
        FilterRegistrationBean<ContentModerationFilter> bean = new FilterRegistrationBean<>();
        bean.setFilter(new ContentModerationFilter());
        bean.addUrlPatterns("/api/*");
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return bean;
    }

    @Bean
    public FilterRegistrationBean<IpTrackingFilter> ipTrackingFilter() {
        FilterRegistrationBean<IpTrackingFilter> bean = new FilterRegistrationBean<>();
        bean.setFilter(new IpTrackingFilter());
        bean.addUrlPatterns("/*");
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE - 10);
        return bean;
    }
}
