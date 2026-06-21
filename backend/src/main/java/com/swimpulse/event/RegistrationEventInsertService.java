package com.swimpulse.event;

import com.swimpulse.pool.Pool;
import com.swimpulse.notice.NoticeRegistrationPeriodEntity;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RegistrationEventInsertService {
	private static final Logger log = LoggerFactory.getLogger(RegistrationEventInsertService.class);

	private final RegistrationEventRepository eventRepository;
	private final EntityManager entityManager;

	public RegistrationEventInsertService(RegistrationEventRepository eventRepository, EntityManager entityManager) {
		this.eventRepository = eventRepository;
		this.entityManager = entityManager;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public RegistrationEvent insert(Long poolId, String title, Instant registrationStartsAt, Instant registrationEndsAt) {
		Pool pool = entityManager.find(Pool.class, poolId);
		RegistrationEvent saved = eventRepository.saveAndFlush(new RegistrationEvent(
				pool,
				title,
				registrationStartsAt,
				registrationEndsAt,
				calculateStatus(registrationStartsAt, registrationEndsAt, Instant.now())
		));
		log.info("Registration event created. eventId={} poolId={} status={} startsAt={} endsAt={}",
				saved.getId(), poolId, saved.getStatus(), saved.getRegistrationStartsAt(), saved.getRegistrationEndsAt());
		return saved;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
	public Optional<RegistrationEvent> findExisting(Long poolId, String title, Instant registrationStartsAt, Instant registrationEndsAt) {
		return eventRepository.findByPool_IdAndTitleAndRegistrationStartsAtAndRegistrationEndsAt(
				poolId,
				title,
				registrationStartsAt,
				registrationEndsAt
		);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
	public Optional<RegistrationEvent> findByNoticeRegistrationPeriodId(Long noticeRegistrationPeriodId) {
		return eventRepository.findByNoticeRegistrationPeriod_Id(noticeRegistrationPeriodId);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public RegistrationEvent insertForNoticePeriod(
			Long noticeRegistrationPeriodId,
			Long poolId,
			String title,
			Instant registrationStartsAt,
			Instant registrationEndsAt
	) {
		Pool pool = entityManager.find(Pool.class, poolId);
		NoticeRegistrationPeriodEntity periodReference =
				entityManager.getReference(NoticeRegistrationPeriodEntity.class, noticeRegistrationPeriodId);
		RegistrationEvent saved = eventRepository.saveAndFlush(new RegistrationEvent(
				pool,
				periodReference,
				title,
				registrationStartsAt,
				registrationEndsAt,
				calculateStatus(registrationStartsAt, registrationEndsAt, Instant.now())
		));
		log.info("Registration event created from notice period. eventId={} periodId={} poolId={} status={}",
				saved.getId(), noticeRegistrationPeriodId, poolId, saved.getStatus());
		return saved;
	}

	private EventStatus calculateStatus(Instant startsAt, Instant endsAt, Instant now) {
		if (now.isBefore(startsAt)) {
			return EventStatus.UPCOMING;
		}
		if (now.isBefore(endsAt)) {
			return EventStatus.OPEN;
		}
		return EventStatus.CLOSED;
	}
}
