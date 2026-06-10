package com.swimpulse.event;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RegistrationEventRepository extends JpaRepository<RegistrationEvent, Long> {
	long countByNoticeRegistrationPeriodIsNotNull();

	List<RegistrationEvent> findAllByOrderByRegistrationStartsAtAsc();

	List<RegistrationEvent> findByPool_IdOrderByRegistrationStartsAtAsc(Long poolId);

	Optional<RegistrationEvent> findByPool_IdAndTitleAndRegistrationStartsAtAndRegistrationEndsAt(
			Long poolId,
			String title,
			Instant registrationStartsAt,
			Instant registrationEndsAt
	);

	Optional<RegistrationEvent> findByNoticeRegistrationPeriod_Id(Long noticeRegistrationPeriodId);

	List<RegistrationEvent> findByStatusOrderByRegistrationStartsAtAsc(EventStatus status);

	List<RegistrationEvent> findByStatusInOrderByRegistrationStartsAtAsc(Collection<EventStatus> statuses);
}
