import {getAccessToken} from '../auth/tokenStore';
import type {
  AppUser,
  InAppNotification,
  LocationSearchCandidate,
  MobileLoginResponse,
  MyPageData,
  NearbyPool,
  NotificationPage,
  NoticeScanResponse,
  Pool,
  PoolAddRequest,
  PoolLocationCandidate,
  RegistrationEvent,
  Subscription,
} from './types';

export const API_BASE_URL = 'http://10.0.2.2:8080';

export class ApiError extends Error {
  status: number;

  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

type RequestOptions = RequestInit & {
  auth?: boolean;
};

const USER_FRIENDLY_API_MESSAGES = new Map<string, string>([
  [
    'Subscribe to a registration period before sending a test notification.',
    '테스트 알림을 보내기 전에 원하는 수영장 공지를 확인하고 모집 기간을 구독해주세요.',
  ],
  [
    'Register web push before sending a test notification.',
    '테스트 알림을 보내려면 먼저 푸시 알림을 등록해주세요.',
  ],
  [
    'Register web push before sending test notifications.',
    '테스트 알림을 보내려면 먼저 푸시 알림을 등록해주세요.',
  ],
]);

function userFriendlyErrorMessage(status: number, statusText: string, body: string) {
  const rawMessage = extractErrorMessage(body) || `${status} ${statusText}`;
  const mappedMessage = USER_FRIENDLY_API_MESSAGES.get(rawMessage);

  if (mappedMessage) {
    return mappedMessage;
  }

  if (/[가-힣]/.test(rawMessage) && !rawMessage.startsWith('{')) {
    return rawMessage;
  }

  if (status === 401) {
    return '로그인이 필요합니다.';
  }
  if (status === 403) {
    return '요청 권한이 없습니다.';
  }
  if (status === 404) {
    return '요청한 정보를 찾을 수 없습니다.';
  }
  if (status >= 500) {
    return '서버에서 문제가 발생했습니다. 잠시 후 다시 시도해주세요.';
  }

  return '요청을 처리하지 못했습니다. 입력값을 확인해주세요.';
}

function extractErrorMessage(body: string) {
  if (!body) {
    return '';
  }

  try {
    const parsed = JSON.parse(body) as {message?: unknown};
    if (typeof parsed.message === 'string' && parsed.message.trim()) {
      return parsed.message.trim();
    }
  } catch {
    // Plain text error responses are handled below.
  }

  return body.trim();
}

async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const headers = new Headers(options.headers);
  headers.set('Content-Type', 'application/json');
  headers.set('ngrok-skip-browser-warning', 'true');

  if (options.auth !== false) {
    const accessToken = await getAccessToken();
    if (accessToken) {
      headers.set('Authorization', `Bearer ${accessToken}`);
    }
  }

  const response = await fetch(`${API_BASE_URL}${path}`, {
    ...options,
    headers,
  });

  if (!response.ok) {
    const body = await response.text();
    throw new ApiError(response.status, userFriendlyErrorMessage(response.status, response.statusText, body));
  }

  if (response.status === 204) {
    return undefined as T;
  }

  const body = await response.text();
  return body ? (JSON.parse(body) as T) : (undefined as T);
}

export async function health(): Promise<{status: string}> {
  return request('/actuator/health', {auth: false});
}

export async function mobileGoogleLogin(idToken: string): Promise<MobileLoginResponse> {
  return request('/api/auth/mobile/google', {
    auth: false,
    method: 'POST',
    body: JSON.stringify({idToken}),
  });
}

export async function getMe(): Promise<AppUser> {
  return request('/api/me');
}

export async function getPools(): Promise<Pool[]> {
  return request('/api/pools', {auth: false});
}

export async function getEvents(): Promise<RegistrationEvent[]> {
  return request('/api/events', {auth: false});
}

export async function getNearbyPools(latitude: number, longitude: number, limit = 10): Promise<NearbyPool[]> {
  const params = new URLSearchParams({
    latitude: latitude.toString(),
    longitude: longitude.toString(),
    limit: limit.toString(),
  });
  return request(`/api/pools/nearby?${params.toString()}`, {auth: false});
}

export async function searchLocations(
  query: string,
  display = 5,
  location?: {latitude: number; longitude: number} | null,
): Promise<LocationSearchCandidate[]> {
  const params = new URLSearchParams({query, display: display.toString()});
  if (location) {
    params.set('latitude', location.latitude.toString());
    params.set('longitude', location.longitude.toString());
  }
  return request(`/api/locations/search?${params.toString()}`, {auth: false});
}

export async function getPoolLocationCandidates(
  latitude: number,
  longitude: number,
  radius = 5000,
  query = '수영장',
  display = 10,
): Promise<PoolLocationCandidate[]> {
  const params = new URLSearchParams({
    latitude: latitude.toString(),
    longitude: longitude.toString(),
    radius: radius.toString(),
    query,
    display: display.toString(),
  });
  return request(`/api/pools/location-candidates?${params.toString()}`, {auth: false});
}

export async function createPoolFromLocationCandidate(candidate: PoolLocationCandidate): Promise<PoolAddRequest> {
  return request('/api/pools/from-location-candidate', {
    method: 'POST',
    body: JSON.stringify({
      title: candidate.title,
      category: candidate.category,
      address: candidate.address,
      roadAddress: candidate.roadAddress,
      link: candidate.link,
      latitude: candidate.latitude,
      longitude: candidate.longitude,
    }),
  });
}

export async function scanPoolNotices(poolId: number): Promise<NoticeScanResponse> {
  return request(`/api/pools/${poolId}/notices/scan`, {method: 'POST'});
}

export async function getSubscriptions(): Promise<Subscription[]> {
  return request('/api/subscriptions');
}

export async function createSubscription(input: {
  poolId: number;
  title: string;
  registrationStartsAt: string;
  registrationEndsAt: string;
  noticeRegistrationPeriodId: number | null;
  noticeUrl?: string | null;
}): Promise<Subscription> {
  return request('/api/subscriptions', {
    method: 'POST',
    body: JSON.stringify(input),
  });
}

export async function updateSubscriptionPeriod(
  subscriptionId: number,
  input: {title: string; registrationStartsAt: string; registrationEndsAt: string},
): Promise<Subscription> {
  return request(`/api/subscriptions/${subscriptionId}`, {
    method: 'PATCH',
    body: JSON.stringify(input),
  });
}

export async function deleteSubscription(eventId: number): Promise<void> {
  await request(`/api/subscriptions?eventId=${eventId}`, {method: 'DELETE'});
}

export async function getMyPage(): Promise<MyPageData> {
  return request('/api/my-page');
}

export async function getNotificationPage(page = 0, size = 20): Promise<NotificationPage> {
  return request(`/api/notifications?page=${page}&size=${size}`);
}

export async function getNotification(notificationId: number): Promise<InAppNotification> {
  return request(`/api/notifications/${notificationId}`);
}

export async function markNotificationRead(notificationId: number): Promise<InAppNotification> {
  return request(`/api/notifications/${notificationId}/read`, {method: 'PATCH'});
}

export async function registerDeviceToken(deviceId: string, fcmToken: string, platform: 'ANDROID' | 'IOS'): Promise<void> {
  await request('/api/notifications/device-tokens', {
    method: 'POST',
    body: JSON.stringify({deviceId, fcmToken, platform}),
  });
}

export async function unregisterCurrentDevice(deviceId: string): Promise<void> {
  await request(`/api/notifications/device-tokens/current?deviceId=${encodeURIComponent(deviceId)}`, {method: 'DELETE'});
}

export async function sendTestNotification(): Promise<InAppNotification> {
  return request('/api/notifications/test', {method: 'POST'});
}
