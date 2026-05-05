package com.swimpulse.user;

import com.swimpulse.common.NotFoundException;
import com.swimpulse.notification.UserDeviceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {
	private final AppUserRepository userRepository;
	private final UserDeviceRepository userDeviceRepository;

	public UserService(AppUserRepository userRepository, UserDeviceRepository userDeviceRepository) {
		this.userRepository = userRepository;
		this.userDeviceRepository = userDeviceRepository;
	}

	@Transactional(readOnly = true)
	public UserResponse findUser(Long userId) {
		return userRepository.findById(userId)
				.map(user -> UserResponse.from(user, userDeviceRepository.existsByUser_IdAndEnabledTrue(user.getId())))
				.orElseThrow(() -> new NotFoundException("User not found: " + userId));
	}
}
