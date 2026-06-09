package com.swimpulse.event;

import com.swimpulse.common.NotFoundException;
import com.swimpulse.pool.PoolRepository;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RegistrationEventResolver {
	private static final Logger log = LoggerFactory.getLogger(RegistrationEventResolver.class);

	private final RegistrationEventRepository eventRepository;
	private final RegistrationEventInsertService insertService;
	private final PoolRepository poolRepository;

	public RegistrationEventResolver(
			RegistrationEventRepository eventRepository,
			RegistrationEventInsertService insertService,
			PoolRepository poolRepository
	) {
		this.eventRepository = eventRepository;
		this.insertService = insertService;
		this.poolRepository = poolRepository;
	}

	@Transactional(readOnly = true)
	public RegistrationEvent getOrCreate(Long poolId, String title, Instant registrationStartsAt, Instant registrationEndsAt) {
		ensurePoolExists(poolId);
		return findExisting(poolId, title, registrationStartsAt, registrationEndsAt)
				.orElseGet(() -> insertOrReuse(poolId, title, registrationStartsAt, registrationEndsAt));
	}

	private RegistrationEvent insertOrReuse(Long poolId, String title, Instant registrationStartsAt, Instant registrationEndsAt) {
		try {
			return insertService.insert(poolId, title, registrationStartsAt, registrationEndsAt);
		} catch (DataIntegrityViolationException exception) {
			log.info("Concurrent registration event insert detected. Reusing existing row. poolId={} title={} startsAt={} endsAt={}",
					poolId, title, registrationStartsAt, registrationEndsAt);
			return findExisting(poolId, title, registrationStartsAt, registrationEndsAt)
					.orElseThrow(() -> exception);
		}
	}

	private Optional<RegistrationEvent> findExisting(
			Long poolId,
			String title,
			Instant registrationStartsAt,
			Instant registrationEndsAt
	) {
		return eventRepository.findByPool_IdAndTitleAndRegistrationStartsAtAndRegistrationEndsAt(
				poolId,
				title,
				registrationStartsAt,
				registrationEndsAt
		);
	}

	private void ensurePoolExists(Long poolId) {
		if (!poolRepository.existsById(poolId)) {
			throw new NotFoundException("Pool not found: " + poolId);
		}
	}
}
