package com.swimpulse.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RedisSingleFlightServiceTests {
	@Mock
	private RedisLockService redisLockService;

	@Mock
	private RedisJsonCacheService redisCache;

	@Mock
	private Supplier<List<String>> loader;

	@Mock
	private Consumer<List<String>> cacheWriter;

	@Test
	void lockWinnerLoadsAndWritesCache() {
		RedisSingleFlightService service = new RedisSingleFlightService(redisLockService, redisCache);
		RedisLockService.LockToken token = new RedisLockService.LockToken("lock", "token");
		when(redisCache.getList("test-cache", "cache-key", String.class)).thenReturn(Optional.empty());
		when(redisLockService.acquire(any(), eq(Duration.ofMillis(3000)))).thenReturn(Optional.of(token));
		when(redisCache.peekList("test-cache", "cache-key", String.class)).thenReturn(Optional.empty());
		when(loader.get()).thenReturn(List.of("loaded"));

		List<String> result = service.getListOrLoad(
				"test-cache",
				"cache-key",
				String.class,
				Duration.ofMillis(3000),
				Duration.ofMillis(100),
				Duration.ofMillis(1),
				loader,
				cacheWriter
		);

		assertEquals(List.of("loaded"), result);
		verify(cacheWriter).accept(List.of("loaded"));
		verify(redisLockService).release(token);
	}

	@Test
	void lockLoserWaitsForSharedCacheValue() {
		RedisSingleFlightService service = new RedisSingleFlightService(redisLockService, redisCache);
		when(redisCache.getList("test-cache", "cache-key", String.class)).thenReturn(Optional.empty());
		when(redisLockService.acquire(any(), eq(Duration.ofMillis(3000)))).thenReturn(Optional.empty());
		when(redisCache.peekList("test-cache", "cache-key", String.class)).thenReturn(Optional.of(List.of("shared")));

		List<String> result = service.getListOrLoad(
				"test-cache",
				"cache-key",
				String.class,
				Duration.ofMillis(3000),
				Duration.ofMillis(100),
				Duration.ofMillis(1),
				loader,
				cacheWriter
		);

		assertEquals(List.of("shared"), result);
		verify(loader, never()).get();
		verify(cacheWriter, never()).accept(any());
	}
}
