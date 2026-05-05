package com.swimpulse.event;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RegistrationEventRepository extends JpaRepository<RegistrationEvent, Long> {
	List<RegistrationEvent> findAllByOrderByRegistrationStartsAtAsc();

	List<RegistrationEvent> findByPool_IdOrderByRegistrationStartsAtAsc(Long poolId);

	List<RegistrationEvent> findByStatusOrderByRegistrationStartsAtAsc(EventStatus status);

	List<RegistrationEvent> findByStatusInOrderByRegistrationStartsAtAsc(Collection<EventStatus> statuses);
}
