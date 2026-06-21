package com.swimpulse.event;

import com.swimpulse.common.NotFoundException;
import com.swimpulse.common.BadRequestException;
import com.swimpulse.notice.NoticeRegistrationPeriodEntity;
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

	public RegistrationEvent getOrCreate(Long poolId, String title, Instant registrationStartsAt, Instant registrationEndsAt) {
		ensurePoolExists(poolId);
		return findExisting(poolId, title, registrationStartsAt, registrationEndsAt)
				.orElseGet(() -> insertOrReuse(poolId, title, registrationStartsAt, registrationEndsAt));
	}

	@Transactional
	public RegistrationEvent getOrCreateForNoticePeriod(
			NoticeRegistrationPeriodEntity period,
			String title
	) {
		Long periodId = period.getId();
		Long poolId = period.getNotice().getPool().getId();
		return eventRepository.findByNoticeRegistrationPeriod_Id(periodId)
				.orElseGet(() -> findExisting(
						poolId,
						title,
						period.getStartsAt(),
						period.getEndsAt()
				)
						.map(existing -> assignPeriod(existing, period))
						.orElseGet(() -> insertOrReuse(period, poolId, title)));
	}

	private RegistrationEvent insertOrReuse(Long poolId, String title, Instant registrationStartsAt, Instant registrationEndsAt) {
		try {
			return insertService.insert(poolId, title, registrationStartsAt, registrationEndsAt);
		} catch (DataIntegrityViolationException exception) {
			log.info("Concurrent registration event insert detected. Reusing existing row. poolId={} title={} startsAt={} endsAt={}",
					poolId, title, registrationStartsAt, registrationEndsAt);
			return insertService.findExisting(poolId, title, registrationStartsAt, registrationEndsAt)
					.orElseThrow(() -> exception);
		}
	}

	private RegistrationEvent insertOrReuse(
			NoticeRegistrationPeriodEntity period,
			Long poolId,
			String title
	) {
		try {
			return insertService.insertForNoticePeriod(
					period.getId(),
					poolId,
					title,
					period.getStartsAt(),
					period.getEndsAt()
			);
		} catch (DataIntegrityViolationException exception) {
			log.info("Concurrent notice period event insert detected. Reusing existing row. periodId={} poolId={}",
					period.getId(), poolId);
			return insertService.findByNoticeRegistrationPeriodId(period.getId())
					.or(() -> insertService.findExisting(poolId, title, period.getStartsAt(), period.getEndsAt()))
					.map(existing -> assignPeriod(existing, period))
					.orElseThrow(() -> exception);
		}
	}

	private RegistrationEvent assignPeriod(
			RegistrationEvent event,
			NoticeRegistrationPeriodEntity period
	) {
		if (event.getNoticeRegistrationPeriod() == null) {
			event.assignNoticeRegistrationPeriod(period);
			return event;
		}
		if (event.getNoticeRegistrationPeriod().getId().equals(period.getId())) {
			return event;
		}
		throw new BadRequestException("Registration event is already linked to another notice period.");
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
