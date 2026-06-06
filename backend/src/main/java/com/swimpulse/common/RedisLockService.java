package com.swimpulse.common;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
public class RedisLockService {
	private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>();

	static {
		RELEASE_SCRIPT.setScriptText("""
				if redis.call('get', KEYS[1]) == ARGV[1] then
					return redis.call('del', KEYS[1])
				end
				return 0
				""");
		RELEASE_SCRIPT.setResultType(Long.class);
	}

	private final StringRedisTemplate redisTemplate;

	public RedisLockService(StringRedisTemplate redisTemplate) {
		this.redisTemplate = redisTemplate;
	}

	public Optional<LockToken> acquire(String key, Duration ttl) {
		String token = UUID.randomUUID().toString();
		Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, token, ttl);
		if (!Boolean.TRUE.equals(acquired)) {
			return Optional.empty();
		}
		return Optional.of(new LockToken(key, token));
	}

	public void release(LockToken lockToken) {
		if (lockToken == null) {
			return;
		}
		redisTemplate.execute(RELEASE_SCRIPT, List.of(lockToken.key()), lockToken.token());
	}

	public record LockToken(String key, String token) {
	}
}
