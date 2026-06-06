package com.swimpulse.notification;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserDeviceRepository extends JpaRepository<UserDevice, Long> {
	Optional<UserDevice> findByUser_IdAndDeviceId(Long userId, String deviceId);

	List<UserDevice> findByUser_IdAndEnabledTrue(Long userId);

	boolean existsByUser_IdAndEnabledTrue(Long userId);

	long countByUser_IdAndEnabledTrue(Long userId);
}
