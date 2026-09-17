package com.example.hello;

import java.util.Map;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/redis")
public class RedisController {
    private final StringRedisTemplate redis;

    public RedisController(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @PostMapping
    public Map<String, String> set(@RequestBody(required = false) Kv req) {
        String key = (req == null || req.key() == null || req.key().isBlank()) ? "hello" : req.key();
        String value = (req == null || req.value() == null || req.value().isBlank()) ? "hello-from-msi" : req.value();
        redis.opsForValue().set(key, value);
        return Map.of("key", key, "value", value);
    }

    @GetMapping("/{key}")
    public Map<String, String> get(@PathVariable String key) {
        String value = redis.opsForValue().get(key);
        return Map.of("key", key, "value", value == null ? "(nil)" : value);
    }

    public record Kv(String key, String value) {}
}
