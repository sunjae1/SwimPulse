package com.swimpulse.common;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class RedisJsonCacheService {
	private static final Logger log = LoggerFactory.getLogger(RedisJsonCacheService.class);

	private final StringRedisTemplate redisTemplate;
	private final ObjectMapper objectMapper;
	private final MeterRegistry meterRegistry;

	public RedisJsonCacheService(
			StringRedisTemplate redisTemplate,
			ObjectMapper objectMapper,
			MeterRegistry meterRegistry
	) {
		this.redisTemplate = redisTemplate;
		this.objectMapper = objectMapper;
		this.meterRegistry = meterRegistry;
	}

	public <T> Optional<T> get(String cacheName, String key, Class<T> type) {
		try {
			String json = redisTemplate.opsForValue().get(key);
			if (json == null) {
				record(cacheName, "miss");
				return Optional.empty();
			}
			record(cacheName, "hit");
			return Optional.of(objectMapper.readValue(json, type));
		} catch (RedisConnectionFailureException exception) {
			record(cacheName, "redis_error");
			log.debug("Redis cache read skipped. cache={} key={} message={}", cacheName, key, exception.getMessage());
			return Optional.empty();
		} catch (Exception exception) {
			record(cacheName, "error");
			log.warn("Redis cache read failed. cache={} key={} message={}", cacheName, key, exception.getMessage());
			return Optional.empty();
		}
	}

	public <T> Optional<List<T>> getList(String cacheName, String key, Class<T> elementType) {
		try {
			String json = redisTemplate.opsForValue().get(key);
			if (json == null) {
				record(cacheName, "miss");
				return Optional.empty();
			}
			JavaType type = objectMapper.getTypeFactory().constructCollectionType(List.class, elementType);
			record(cacheName, "hit");
			return Optional.of(objectMapper.readValue(json, type));
		} catch (RedisConnectionFailureException exception) {
			record(cacheName, "redis_error");
			log.debug("Redis cache read skipped. cache={} key={} message={}", cacheName, key, exception.getMessage());
			return Optional.empty();
		} catch (Exception exception) {
			record(cacheName, "error");
			log.warn("Redis cache read failed. cache={} key={} message={}", cacheName, key, exception.getMessage());
			return Optional.empty();
		}
	}

	public void put(String cacheName, String key, Object value, Duration ttl) {
		try {
			redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(value), ttl);
			record(cacheName, "put");
		} catch (RedisConnectionFailureException exception) {
			record(cacheName, "redis_error");
			log.debug("Redis cache write skipped. cache={} key={} message={}", cacheName, key, exception.getMessage());
		} catch (Exception exception) {
			record(cacheName, "error");
			log.warn("Redis cache write failed. cache={} key={} message={}", cacheName, key, exception.getMessage());
		}
	}

	public String hash(String value) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hashed = digest.digest(value.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(hashed);
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is not available", exception);
		}
	}

	private void record(String cacheName, String result) {
		Counter.builder("swimpulse.cache.access")
				.description("SwimPulse Redis cache access result")
				.tag("cache", cacheName)
				.tag("result", result)
				.register(meterRegistry)
				.increment();
	}
}
