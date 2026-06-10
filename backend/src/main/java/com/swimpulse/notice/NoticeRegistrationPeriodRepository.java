package com.swimpulse.notice;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NoticeRegistrationPeriodRepository extends JpaRepository<NoticeRegistrationPeriodEntity, Long> {
	long countByStatus(NoticeRegistrationPeriodStatus status);

	List<NoticeRegistrationPeriodEntity> findByNotice_IdOrderByStartsAtAscIdAsc(Long noticeId);

	List<NoticeRegistrationPeriodEntity> findByNotice_IdAndStatusOrderByStartsAtAscIdAsc(
			Long noticeId,
			NoticeRegistrationPeriodStatus status
	);

	Optional<NoticeRegistrationPeriodEntity> findByIdAndStatus(
			Long id,
			NoticeRegistrationPeriodStatus status
	);

	Optional<NoticeRegistrationPeriodEntity> findByNotice_IdAndNormalizedLabelAndStartsAtAndEndsAt(
			Long noticeId,
			String normalizedLabel,
			Instant startsAt,
			Instant endsAt
	);
}
