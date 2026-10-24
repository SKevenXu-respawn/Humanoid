package com.skevenxu.humanoid.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** 共享 Gson：支持 java.time 类型，供 MVC 转换器和工具复用。 */
@Configuration
public class GsonConfig {

    private static final DateTimeFormatter OUT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final DateTimeFormatter IN_T =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final DateTimeFormatter IN_SPACE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter IN_SPACE_SHORT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    @Bean
    public Gson gson() {
        return new GsonBuilder()
                .registerTypeAdapter(LocalDateTime.class,
                        (JsonSerializer<LocalDateTime>) (value, type, context) ->
                                new JsonPrimitive(value.format(OUT)))
                .registerTypeAdapter(LocalDateTime.class,
                        (JsonDeserializer<LocalDateTime>) (json, type, context) ->
                                parseDateTime(json.getAsString()))
                .registerTypeAdapter(LocalDate.class,
                        (JsonSerializer<LocalDate>) (value, type, context) ->
                                new JsonPrimitive(value.toString()))
                .registerTypeAdapter(LocalDate.class,
                        (JsonDeserializer<LocalDate>) (json, type, context) ->
                                LocalDate.parse(json.getAsString()))
                .create();
    }

    private static LocalDateTime parseDateTime(String text) {
        String value = text.trim();
        for (DateTimeFormatter formatter : new DateTimeFormatter[]{
                IN_T, IN_SPACE, IN_SPACE_SHORT}) {
            try {
                return LocalDateTime.parse(value, formatter);
            } catch (Exception ignored) {
                // try next
            }
        }
        throw new IllegalArgumentException("无法解析时间: " + text);
    }
}
