import type {
  AppUser,
  DashboardInitialData,
  DeviceRegistration,
  GeocodedLocation,
  InAppNotification,
  LocationSearchCandidate,
  NearbyPool,
  NoticeScanResponse,
  Pool,
  RegistrationEvent,
  Subscription,
} from "@/lib/types";

const BROWSER_API_BASE_URL = process.env.NEXT_PUBLIC_API_BASE_URL || "";
const SERVER_API_BASE_URL = process.env.BACKEND_INTERNAL_API_BASE_URL || "http://localhost:8080";

function apiUrl(path: string) {
  const baseUrl = typeof window === "undefined" ? SERVER_API_BASE_URL : BROWSER_API_BASE_URL;
  return `${baseUrl}${path}`;
}

const fallbackPools: Pool[] = [
  {
    id: 1,
    name: "강남스포츠문화센터 수영장",
    district: "강남구",
    description: "새벽반과 저녁반 경쟁률이 높은 공공 수영장입니다.",
    completionYear: 2016,
    indoorOutdoorTypeName: "실내",
    ownerAgencyName: "강남구",
    managementAgencyName: "시설관리공단",
    operatingOrganizationName: "시설관리공단",
    contactNumber: "02-0000-0000",
    standardPoolLengthMeters: 25,
    standardPoolLaneCount: 6,
    postalCode: "06362",
    lotNumberAddress: "서울특별시 강남구 수서동 718",
    roadNameAddress: "서울 강남구 밤고개로1길 52",
    homepageUrl: "https://www.gangnam.go.kr",
    homepageSource: null,
    homepageStatus: "UNVERIFIED",
    homepageVerifiedAt: null,
    homepageCandidateTitle: null,
    homepageCandidateAddress: null,
    homepageCandidateLink: null,
    imageUrl: null,
    latitude: null,
    longitude: null,
    geocodeStatus: "PENDING",
  },
  {
    id: 2,
    name: "마포구민체육센터 수영장",
    district: "마포구",
    description: "월초 접수 알림 수요가 많은 구민 체육시설입니다.",
    completionYear: 2004,
    indoorOutdoorTypeName: "실내",
    ownerAgencyName: "마포구",
    managementAgencyName: "마포구시설관리공단",
    operatingOrganizationName: "마포구시설관리공단",
    contactNumber: "02-0000-0000",
    standardPoolLengthMeters: 25,
    standardPoolLaneCount: 6,
    postalCode: "03926",
    lotNumberAddress: "서울특별시 마포구 망원동 450-3",
    roadNameAddress: "서울 마포구 월드컵로25길 190",
    homepageUrl: "https://www.mapo.go.kr",
    homepageSource: null,
    homepageStatus: "UNVERIFIED",
    homepageVerifiedAt: null,
    homepageCandidateTitle: null,
    homepageCandidateAddress: null,
    homepageCandidateLink: null,
    imageUrl: null,
    latitude: null,
    longitude: null,
    geocodeStatus: "PENDING",
  },
  {
    id: 3,
    name: "성동구립 용답체육센터",
    district: "성동구",
    description: "직장인반과 어린이반 모집 공지가 자주 갱신됩니다.",
    completionYear: 2012,
    indoorOutdoorTypeName: "실내",
    ownerAgencyName: "성동구",
    managementAgencyName: "성동구도시관리공단",
    operatingOrganizationName: "성동구도시관리공단",
    contactNumber: "02-0000-0000",
    standardPoolLengthMeters: 25,
    standardPoolLaneCount: 6,
    postalCode: "04808",
    lotNumberAddress: "서울특별시 성동구 용답동 182-4",
    roadNameAddress: "서울 성동구 천호대로78길 15-48",
    homepageUrl: "https://www.sd.go.kr",
    homepageSource: null,
    homepageStatus: "UNVERIFIED",
    homepageVerifiedAt: null,
    homepageCandidateTitle: null,
    homepageCandidateAddress: null,
    homepageCandidateLink: null,
    imageUrl: null,
    latitude: null,
    longitude: null,
    geocodeStatus: "PENDING",
  },
];

const now = Date.now();

const fallbackEvents: RegistrationEvent[] = [
  {
    id: 1,
    poolId: 1,
    poolName: "강남스포츠문화센터 수영장",
    title: "5월 신규회원 새벽반 접수",
    registrationStartsAt: new Date(now + 8 * 60 * 1000).toISOString(),
    registrationEndsAt: new Date(now + 2 * 60 * 60 * 1000).toISOString(),
    status: "UPCOMING",
    reminderQueued: false,
    startQueued: false,
  },
  {
    id: 2,
    poolId: 2,
    poolName: "마포구민체육센터 수영장",
    title: "5월 구민 우선 접수",
    registrationStartsAt: new Date(now + 24 * 60 * 60 * 1000).toISOString(),
    registrationEndsAt: new Date(now + 27 * 60 * 60 * 1000).toISOString(),
    status: "UPCOMING",
    reminderQueued: false,
    startQueued: false,
  },
  {
    id: 3,
    poolId: 3,
    poolName: "성동구립 용답체육센터",
    title: "평일 저녁반 잔여석 접수",
    registrationStartsAt: new Date(now - 30 * 60 * 1000).toISOString(),
    registrationEndsAt: new Date(now + 90 * 60 * 1000).toISOString(),
    status: "OPEN",
    reminderQueued: true,
    startQueued: true,
  },
];

type RequestOptions = RequestInit & {
  fallback?: boolean;
};

export class ApiRequestError extends Error {
  status: number;

  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const response = await fetch(apiUrl(path), {
    ...options,
    cache: options.cache ?? "no-store",
    credentials: "include",
    headers: {
      "Content-Type": "application/json",
      "ngrok-skip-browser-warning": "true",
      ...options.headers,
    },
  });

  if (!response.ok) {
    const body = await response.text();
    throw new ApiRequestError(response.status, body || `${response.status} ${response.statusText}`);
  }

  if (response.status === 204) {
    return undefined as T;
  }

  const body = await response.text();
  if (!body) {
    return undefined as T;
  }

  return JSON.parse(body) as T;
}

export async function getInitialDashboardData(): Promise<DashboardInitialData> {
  try {
    const [pools, events] = await Promise.all([
      request<Pool[]>("/api/pools"),
      request<RegistrationEvent[]>("/api/events"),
    ]);
    return { pools, events, apiReachable: true };
  } catch {
    return { pools: fallbackPools, events: fallbackEvents, apiReachable: false };
  }
}

export async function getMe(): Promise<AppUser> {
  return request<AppUser>("/api/me");
}

export async function logout(): Promise<void> {
  await request<void>("/api/auth/logout", {
    method: "POST",
  });
}

export async function getEvents(): Promise<RegistrationEvent[]> {
  return request<RegistrationEvent[]>("/api/events");
}

export async function getNearbyPools(latitude: number, longitude: number, limit = 10): Promise<NearbyPool[]> {
  const params = new URLSearchParams({
    latitude: latitude.toString(),
    longitude: longitude.toString(),
    limit: limit.toString(),
  });
  return request<NearbyPool[]>(`/api/pools/nearby?${params.toString()}`);
}

export async function searchLocations(
  query: string,
  display = 5,
  location?: { latitude: number; longitude: number } | null,
): Promise<LocationSearchCandidate[]> {
  const params = new URLSearchParams({
    query,
    display: display.toString(),
  });
  if (location) {
    params.set("latitude", location.latitude.toString());
    params.set("longitude", location.longitude.toString());
  }
  return request<LocationSearchCandidate[]>(`/api/locations/search?${params.toString()}`);
}

export async function geocodeLocation(address: string): Promise<GeocodedLocation> {
  const params = new URLSearchParams({ address });
  return request<GeocodedLocation>(`/api/locations/geocode?${params.toString()}`);
}

export async function reverseGeocodeLocation(latitude: number, longitude: number): Promise<GeocodedLocation> {
  const params = new URLSearchParams({
    latitude: latitude.toString(),
    longitude: longitude.toString(),
  });
  return request<GeocodedLocation>(`/api/locations/reverse-geocode?${params.toString()}`);
}

export async function createPoolFromLocationCandidate(candidate: LocationSearchCandidate): Promise<Pool> {
  return request<Pool>("/api/pools/from-location-candidate", {
    method: "POST",
    body: JSON.stringify({
      title: candidate.title,
      address: candidate.address,
      roadAddress: candidate.roadAddress,
      link: candidate.link,
      latitude: candidate.latitude,
      longitude: candidate.longitude,
    }),
  });
}

export async function scanPoolNotices(poolId: number): Promise<NoticeScanResponse> {
  return request<NoticeScanResponse>(`/api/pools/${poolId}/notices/scan`, {
    method: "POST",
  });
}

export async function getSubscriptions(): Promise<Subscription[]> {
  return request<Subscription[]>("/api/subscriptions");
}

export async function createSubscription(input: {
  poolId: number;
  title: string;
  registrationStartsAt: string;
  registrationEndsAt: string;
}): Promise<Subscription> {
  return request<Subscription>("/api/subscriptions", {
    method: "POST",
    body: JSON.stringify(input),
  });
}

export async function deleteSubscription(eventId: number): Promise<void> {
  await request<void>(`/api/subscriptions?eventId=${eventId}`, {
    method: "DELETE",
  });
}

export async function getNotifications(): Promise<InAppNotification[]> {
  return request<InAppNotification[]>("/api/notifications");
}

export async function markNotificationRead(notificationId: number): Promise<InAppNotification> {
  return request<InAppNotification>(`/api/notifications/${notificationId}/read`, {
    method: "PATCH",
  });
}

export async function registerDeviceToken(deviceId: string, fcmToken: string): Promise<void> {
  await request<void>("/api/notifications/device-tokens", {
    method: "POST",
    body: JSON.stringify({ deviceId, fcmToken }),
  });
}

export async function getCurrentDeviceRegistration(deviceId: string): Promise<DeviceRegistration> {
  return request<DeviceRegistration>(`/api/notifications/device-tokens/current?deviceId=${encodeURIComponent(deviceId)}`);
}

export async function unregisterCurrentDevice(deviceId: string): Promise<void> {
  await request<void>(`/api/notifications/device-tokens/current?deviceId=${encodeURIComponent(deviceId)}`, {
    method: "DELETE",
  });
}

export async function sendTestNotification(): Promise<InAppNotification> {
  return request<InAppNotification>("/api/notifications/test", {
    method: "POST",
  });
}

export async function createEvent(input: {
  poolId: number;
  title: string;
  registrationStartsAt: string;
  registrationEndsAt: string;
}): Promise<RegistrationEvent> {
  return request<RegistrationEvent>("/api/events", {
    method: "POST",
    body: JSON.stringify(input),
  });
}
