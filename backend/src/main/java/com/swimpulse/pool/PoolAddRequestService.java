package com.swimpulse.pool;

import com.swimpulse.admin.AdminPoolPostprocessResponse;
import com.swimpulse.common.BadRequestException;
import com.swimpulse.common.NotFoundException;
import com.swimpulse.notice.NoticeCrawlerService;
import com.swimpulse.notice.NoticeScanResponse;
import com.swimpulse.user.AppUser;
import com.swimpulse.user.AppUserRepository;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PoolAddRequestService {
	private static final int DEFAULT_PENDING_LIMIT = 10;

	private final PoolAddRequestRepository requestRepository;
	private final AppUserRepository userRepository;
	private final PoolService poolService;
	private final NoticeCrawlerService noticeCrawlerService;

	public PoolAddRequestService(
			PoolAddRequestRepository requestRepository,
			AppUserRepository userRepository,
			PoolService poolService,
			NoticeCrawlerService noticeCrawlerService
	) {
		this.requestRepository = requestRepository;
		this.userRepository = userRepository;
		this.poolService = poolService;
		this.noticeCrawlerService = noticeCrawlerService;
	}

	@Transactional
	public PoolAddRequestResponse create(Long userId, CreatePoolFromLocationCandidateRequest request) {
		AppUser user = userRepository.findById(userId)
				.orElseThrow(() -> new NotFoundException("User not found: " + userId));
		PoolAddRequest saved = requestRepository.save(new PoolAddRequest(user, request));
		return PoolAddRequestResponse.from(saved);
	}

	@Transactional(readOnly = true)
	public List<PoolAddRequestResponse> findRecent(Integer limit) {
		return requestRepository.findAllByOrderByCreatedAtDescIdDesc(PageRequest.of(0, normalizeLimit(limit)))
				.stream()
				.map(PoolAddRequestResponse::from)
				.toList();
	}

	@Transactional(readOnly = true)
	public List<PoolAddRequestResponse> findPending(Integer limit) {
		return requestRepository.findByStatusOrderByCreatedAtDescIdDesc(
						PoolAddRequestStatus.PENDING,
						PageRequest.of(0, normalizeLimit(limit))
				)
				.stream()
				.map(PoolAddRequestResponse::from)
				.toList();
	}

	@Transactional
	public PoolAddRequestResponse approve(Long requestId, Long adminUserId) {
		PoolAddRequest request = findRequest(requestId);
		if (request.getStatus() != PoolAddRequestStatus.PENDING) {
			return PoolAddRequestResponse.from(request);
		}
		AppUser admin = findAdmin(adminUserId);
		Pool pool = poolService.createPoolFromLocationCandidatePool(request.toLocationCandidateRequest());
		boolean newlyApprovedPool = request.getApprovedPool() == null || !request.getApprovedPool().getId().equals(pool.getId());
		if (newlyApprovedPool) {
			request.approve(pool, admin);
		}
		return PoolAddRequestResponse.from(request);
	}

	@Transactional
	public PoolAddRequestResponse reject(Long requestId, Long adminUserId, String reason) {
		PoolAddRequest request = findRequest(requestId);
		if (request.getStatus() != PoolAddRequestStatus.PENDING) {
			return PoolAddRequestResponse.from(request);
		}
		request.reject(findAdmin(adminUserId), reason == null || reason.isBlank() ? "관리자 반려" : reason);
		return PoolAddRequestResponse.from(request);
	}

	@Transactional
	public AdminPoolPostprocessResponse postprocess(Long requestId) {
		PoolAddRequest request = findRequest(requestId);
		Pool approvedPool = request.getApprovedPool();
		if (approvedPool == null) {
			throw new BadRequestException("Approved pool does not exist for request: " + requestId);
		}
		HomepageEnrichmentResult homepage = poolService.reverifyHomepage(approvedPool.getId());
		PoolImageEnrichmentResult image = poolService.enrichPoolImage(approvedPool.getId());
		NoticeScanResponse notices = noticeCrawlerService.scan(approvedPool.getId());
		return new AdminPoolPostprocessResponse(PoolAddRequestResponse.from(request), homepage, image, notices);
	}

	@Transactional
	public HomepageEnrichmentResult postprocessHomepage(Long requestId) {
		Pool approvedPool = findApprovedPool(requestId);
		return poolService.reverifyHomepage(approvedPool.getId());
	}

	@Transactional
	public PoolImageEnrichmentResult postprocessImage(Long requestId) {
		Pool approvedPool = findApprovedPool(requestId);
		return poolService.enrichPoolImage(approvedPool.getId());
	}

	@Transactional
	public NoticeScanResponse postprocessNotices(Long requestId) {
		Pool approvedPool = findApprovedPool(requestId);
		return noticeCrawlerService.scan(approvedPool.getId());
	}

	@Transactional(readOnly = true)
	public long countByStatus(PoolAddRequestStatus status) {
		return requestRepository.countByStatus(status);
	}

	private Pool findApprovedPool(Long requestId) {
		PoolAddRequest request = findRequest(requestId);
		Pool approvedPool = request.getApprovedPool();
		if (approvedPool == null) {
			throw new BadRequestException("Approved pool does not exist for request: " + requestId);
		}
		return approvedPool;
	}

	private PoolAddRequest findRequest(Long requestId) {
		return requestRepository.findById(requestId)
				.orElseThrow(() -> new NotFoundException("Pool add request not found: " + requestId));
	}

	private AppUser findAdmin(Long adminUserId) {
		return userRepository.findById(adminUserId)
				.orElseThrow(() -> new NotFoundException("Admin user not found: " + adminUserId));
	}

	private int normalizeLimit(Integer limit) {
		if (limit == null) {
			return DEFAULT_PENDING_LIMIT;
		}
		return Math.max(1, Math.min(limit, 50));
	}
}
