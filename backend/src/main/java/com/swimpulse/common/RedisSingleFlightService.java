package com.swimpulse.common;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.stereotype.Service;

@Service
public class RedisSingleFlightService {
	private static final Logger log = LoggerFactory.getLogger(RedisSingleFlightService.class);
	private static final String LOCK_KEY_PREFIX = "swimpulse:locks:cache-single-flight:v1:";

	private final RedisLockService redisLockService;
	private final RedisJsonCacheService redisCache;

	public RedisSingleFlightService(RedisLockService redisLockService, RedisJsonCacheService redisCache) {
		this.redisLockService = redisLockService;
		this.redisCache = redisCache;
	}

	public <T> T getOrLoad(
			String cacheName,
			String cacheKey,
			Class<T> type,
			Duration lockTtl,
			Duration waitTimeout,
			Duration pollInterval,
			Supplier<T> loader,
			Consumer<T> cacheWriter
	) {
		Optional<T> cached = redisCache.get(cacheName, cacheKey, type);
		if (cached.isPresent()) {
			return cached.get();
		}
		return loadWithSingleFlight(
				cacheName,
				cacheKey,
				() -> redisCache.peek(cacheName, cacheKey, type),
				lockTtl,
				waitTimeout,
				pollInterval,
				loader,
				cacheWriter
		);
	}

	public <T> java.util.List<T> getListOrLoad(
			String cacheName,
			String cacheKey,
			Class<T> elementType,
			Duration lockTtl,
			Duration waitTimeout,
			Duration pollInterval,
			Supplier<java.util.List<T>> loader,
			Consumer<java.util.List<T>> cacheWriter
	) {
		Optional<java.util.List<T>> cached = redisCache.getList(cacheName, cacheKey, elementType);
		if (cached.isPresent()) {
			return cached.get();
		}
		return loadWithSingleFlight(
				cacheName,
				cacheKey,
				() -> redisCache.peekList(cacheName, cacheKey, elementType),
				lockTtl,
				waitTimeout,
				pollInterval,
				loader,
				cacheWriter
		);
	}

	public <T> T loadAfterMiss(
			String cacheName,
			String cacheKey,
			Class<T> type,
			Duration lockTtl,
			Duration waitTimeout,
			Duration pollInterval,
			Supplier<T> loader,
			Consumer<T> cacheWriter
	) {
		return loadWithSingleFlight(
				cacheName,
				cacheKey,
				() -> redisCache.peek(cacheName, cacheKey, type),
				lockTtl,
				waitTimeout,
				pollInterval,
				loader,
				cacheWriter
		);
	}

	private <T> T loadWithSingleFlight(
			String cacheName,
			String cacheKey,
			Supplier<Optional<T>> cacheReader,
			Duration lockTtl,
			Duration waitTimeout,
			Duration pollInterval,
			Supplier<T> loader,
			Consumer<T> cacheWriter
	) {
		String lockKey = LOCK_KEY_PREFIX + redisCache.hash(cacheKey);
		Optional<RedisLockService.LockToken> lockToken = acquire(lockKey, lockTtl);
		if (lockToken.isPresent()) {
			try {
				Optional<T> cached = cacheReader.get();
				if (cached.isPresent()) {
					log.debug("Cache single-flight reused value after lock acquisition. cache={} cacheKey={}",
							cacheName, cacheKey);
					return cached.get();
				}
				T value = loader.get();
				cacheWriter.accept(value);
				return value;
			} finally {
				release(lockToken.get());
			}
		}

		Optional<T> shared = waitForSharedValue(cacheName, cacheKey, cacheReader, waitTimeout, pollInterval);
		if (shared.isPresent()) {
			return shared.get();
		}

		log.debug("Cache single-flight wait timed out. Loading directly. cache={} cacheKey={}", cacheName, cacheKey);
		T value = loader.get();
		cacheWriter.accept(value);
		return value;
	}

	private Optional<RedisLockService.LockToken> acquire(String lockKey, Duration lockTtl) {
		try {
			return redisLockService.acquire(lockKey, lockTtl);
		} catch (RedisConnectionFailureException exception) {
			log.debug("Cache single-flight lock skipped because Redis is unavailable. key={} message={}",
					lockKey, exception.getMessage());
			return Optional.empty();
		} catch (RuntimeException exception) {
			log.warn("Cache single-flight lock failed. key={} message={}", lockKey, exception.getMessage());
			return Optional.empty();
		}
	}

	private void release(RedisLockService.LockToken lockToken) {
		try {
			redisLockService.release(lockToken);
		} catch (RedisConnectionFailureException exception) {
			log.debug("Cache single-flight lock release skipped because Redis is unavailable. key={} message={}",
					lockToken.key(), exception.getMessage());
		} catch (RuntimeException exception) {
			log.warn("Cache single-flight lock release failed. key={} message={}", lockToken.key(), exception.getMessage());
		}
	}

	private <T> Optional<T> waitForSharedValue(
			String cacheName,
			String cacheKey,
			Supplier<Optional<T>> cacheReader,
			Duration waitTimeout,
			Duration pollInterval
	) {
		Instant deadline = Instant.now().plus(waitTimeout);
		while (Instant.now().isBefore(deadline)) {
			sleep(pollInterval);
			Optional<T> cached = cacheReader.get();
			if (cached.isPresent()) {
				log.debug("Cache single-flight shared value received. cache={} cacheKey={}", cacheName, cacheKey);
				return cached;
			}
		}
		return Optional.empty();
	}

	private void sleep(Duration duration) {
		try {
			Thread.sleep(Math.max(1L, duration.toMillis()));
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
		}
	}
}
