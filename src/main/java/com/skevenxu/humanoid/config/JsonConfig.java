package com.skevenxu.humanoid.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.GsonHttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * Spring AI 的依赖会间接引入 Jackson，默认 Jackson 转换器排在 Gson 前面，
 * 这里把 Gson 转换器提到最前，确保接口使用 Gson 收发 JSON。
 */
@Configuration
public class JsonConfig implements WebMvcConfigurer {

    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        converters.removeIf(GsonHttpMessageConverter.class::isInstance);
        converters.add(0, new GsonHttpMessageConverter(new GsonBuilder().create()));
    }
}
