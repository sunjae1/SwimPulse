package com.swimpulse.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.swimpulse.pool.Pool;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RegistrationEventInsertServiceTests {
	@Mock
	private RegistrationEventRepository eventRepository;

	@Mock
	private EntityManager entityManager;

	private RegistrationEventInsertService service;

	@BeforeEach
	void setUp() {
		service = new RegistrationEventInsertService(eventRepository, entityManager);
	}

	@Test
	void insertUsesLoadedPoolInsteadOfDetachedLazyProxy() {
		Pool pool = new Pool("갈매멀티스포츠센터 수영장", "구리시", "테스트");
		when(entityManager.find(Pool.class, 132L)).thenReturn(pool);
		when(eventRepository.saveAndFlush(any(RegistrationEvent.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));

		RegistrationEvent event = service.insert(
				132L,
				"신규 회원",
				Instant.parse("2026-07-01T00:00:00Z"),
				Instant.parse("2026-07-03T00:00:00Z")
		);

		assertEquals("갈매멀티스포츠센터 수영장", event.getPool().getName());
		verify(entityManager).find(Pool.class, 132L);
		verify(entityManager, never()).getReference(Pool.class, 132L);
	}
}
