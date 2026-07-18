package com.swimpulse.admin;

import com.swimpulse.common.BadRequestException;
import com.swimpulse.common.NotFoundException;
import com.swimpulse.event.RegistrationEvent;
import com.swimpulse.notice.PoolNoticeSource;
import com.swimpulse.notice.PoolNoticeSourceRepository;
import com.swimpulse.notice.NoticeSourceStatus;
import com.swimpulse.notification.NotificationService;
import com.swimpulse.pool.Pool;
import com.swimpulse.pool.PoolRepository;
import com.swimpulse.pool.PoolResponse;
import com.swimpulse.subscription.Subscription;
import com.swimpulse.subscription.SubscriptionRepository;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminPoolHomepageCorrectionService {
	private static final String DEFAULT_REASON = "관리자가 수영장 홈페이지 출처를 변경했습니다.";

	private final PoolRepository poolRepository;
	private final PoolNoticeSourceRepository sourceRepository;
	private final SubscriptionRepository subscriptionRepository;
	private final NotificationService notificationService;

	public AdminPoolHomepageCorrectionService(
			PoolRepository poolRepository,
			PoolNoticeSourceRepository sourceRepository,
			SubscriptionRepository subscriptionRepository,
			NotificationService notificationService
	) {
		this.poolRepository = poolRepository;
		this.sourceRepository = sourceRepository;
		this.subscriptionRepository = subscriptionRepository;
		this.notificationService = notificationService;
	}

	@Transactional
	public AdminPoolHomepageCorrectionResponse correct(Long poolId, AdminPoolHomepageCorrectionRequest request) {
		Pool pool = poolRepository.findById(poolId)
				.orElseThrow(() -> new NotFoundException("Pool not found: " + poolId));
		String name = request.name().trim();
		String homepageUrl = normalizeHomepageUrl(request.homepageUrl());
		String reason = normalizeReason(request.reason());
		if (name.equals(pool.getName()) && homepageUrl.equals(pool.getHomepageUrl())) {
			throw new BadRequestException("시설명 또는 홈페이지 주소를 변경한 뒤 다시 시도해주세요.");
		}

		List<PoolNoticeSource> sources = sourceRepository.findByPoolOrderByIdAsc(pool);
		int inactivatedSources = 0;
		for (PoolNoticeSource source : sources) {
			if (source.getStatus() == NoticeSourceStatus.INACTIVE) {
				continue;
			}
			source.markInactive();
			inactivatedSources++;
		}

		Pool.HomepageCorrection correction = pool.correctHomepage(name, homepageUrl);
		Instant now = Instant.now();
		List<Subscription> subscriptions = subscriptionRepository.findByPool_Id(poolId).stream()
				.filter(subscription -> subscription.getEvent() != null)
				.filter(subscription -> subscription.getEvent().getRegistrationEndsAt().isAfter(now))
				.toList();
		Set<RegistrationEvent> affectedEvents = new LinkedHashSet<>();
		for (Subscription subscription : subscriptions) {
			subscription.requireReview(reason);
			affectedEvents.add(subscription.getEvent());
		}
		affectedEvents.forEach(event -> event.requireSourceReview(reason));

		int cancelled = notificationService.cancelQueuedForSubscriptions(subscriptions);
		int reviewNotifications = notificationService.createSourceReviewNotifications(
				subscriptions,
				correction.currentRevision()
		);

		return new AdminPoolHomepageCorrectionResponse(
				PoolResponse.from(pool),
				correction.previousName(),
				correction.previousHomepageUrl(),
				correction.previousRevision(),
				correction.currentRevision(),
				inactivatedSources,
				affectedEvents.size(),
				subscriptions.size(),
				cancelled,
				reviewNotifications
		);
	}

	private String normalizeHomepageUrl(String value) {
		String url = value.trim();
		try {
			URI uri = new URI(url);
			if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
					|| uri.getHost() == null) {
				throw new BadRequestException("홈페이지 주소는 http 또는 https 전체 주소로 입력해주세요.");
			}
			return url;
		} catch (URISyntaxException exception) {
			throw new BadRequestException("올바른 홈페이지 주소를 입력해주세요.");
		}
	}

	private String normalizeReason(String reason) {
		return reason == null || reason.isBlank() ? DEFAULT_REASON : reason.trim();
	}
}
