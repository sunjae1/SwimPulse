package com.swimpulse.subscription;

import com.swimpulse.common.NotFoundException;
import com.swimpulse.pool.Pool;
import com.swimpulse.pool.PoolRepository;
import com.swimpulse.user.AppUser;
import com.swimpulse.user.AppUserRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SubscriptionService {
	private final SubscriptionRepository subscriptionRepository;
	private final AppUserRepository userRepository;
	private final PoolRepository poolRepository;

	public SubscriptionService(
			SubscriptionRepository subscriptionRepository,
			AppUserRepository userRepository,
			PoolRepository poolRepository
	) {
		this.subscriptionRepository = subscriptionRepository;
		this.userRepository = userRepository;
		this.poolRepository = poolRepository;
	}

	@Transactional(readOnly = true)
	public List<SubscriptionResponse> findByUser(Long userId) {
		ensureUserExists(userId);
		return subscriptionRepository.findByUser_IdOrderByCreatedAtDesc(userId)
				.stream()
				.map(SubscriptionResponse::from)
				.toList();
	}

	@Transactional
	public SubscriptionResponse subscribe(Long userId, CreateSubscriptionRequest request) {
		return subscriptionRepository.findByUser_IdAndPool_Id(userId, request.poolId())
				.map(SubscriptionResponse::from)
				.orElseGet(() -> {
					AppUser user = userRepository.findById(userId)
							.orElseThrow(() -> new NotFoundException("User not found: " + userId));
					Pool pool = poolRepository.findById(request.poolId())
							.orElseThrow(() -> new NotFoundException("Pool not found: " + request.poolId()));
					return SubscriptionResponse.from(subscriptionRepository.save(new Subscription(user, pool)));
				});
	}

	@Transactional
	public void unsubscribe(Long userId, Long poolId) {
		Subscription subscription = subscriptionRepository.findByUser_IdAndPool_Id(userId, poolId)
				.orElseThrow(() -> new NotFoundException("Subscription not found."));
		subscriptionRepository.delete(subscription);
	}

	private void ensureUserExists(Long userId) {
		if (!userRepository.existsById(userId)) {
			throw new NotFoundException("User not found: " + userId);
		}
	}
}
