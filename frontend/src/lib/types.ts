export type EventStatus = "UPCOMING" | "OPEN" | "CLOSED";

export type NotificationStatus = "QUEUED" | "SENT" | "FAILED";

export type NotificationType = "REGISTRATION_REMINDER" | "REGISTRATION_OPEN";

export type GeocodeStatus = "PENDING" | "SUCCESS" | "FAILED";

export type HomepageSource = "NAVER_LOCAL_SEARCH" | "USER_LOCATION_CANDIDATE" | "MANUAL" | "PUBLIC_DATA" | "UNKNOWN";

export type HomepageVerificationStatus = "UNVERIFIED" | "VERIFIED" | "AUTO_UPDATED" | "NEEDS_REVIEW" | "FAILED";

export type Pool = {
  id: number;
  name: string;
  district: string | null;
  description: string | null;
  completionYear: number | null;
  indoorOutdoorTypeName: string | null;
  ownerAgencyName: string | null;
  managementAgencyName: string | null;
  operatingOrganizationName: string | null;
  contactNumber: string | null;
  standardPoolLengthMeters: number | null;
  standardPoolLaneCount: number | null;
  postalCode: string | null;
  lotNumberAddress: string | null;
  roadNameAddress: string | null;
  homepageUrl: string | null;
  homepageSource: HomepageSource | null;
  homepageStatus: HomepageVerificationStatus;
  homepageVerifiedAt: string | null;
  homepageCandidateTitle: string | null;
  homepageCandidateAddress: string | null;
  homepageCandidateLink: string | null;
  imageUrl: string | null;
  latitude: number | null;
  longitude: number | null;
  geocodeStatus: GeocodeStatus;
};

export type NearbyPool = {
  pool: Pool;
  distanceMeters: number;
};

export type LocationSearchCandidate = {
  title: string;
  category: string | null;
  address: string | null;
  roadAddress: string | null;
  link: string | null;
  latitude: number | null;
  longitude: number | null;
  alreadyExists: boolean;
  matchedPoolId: number | null;
  distanceMeters: number | null;
  homepageUrl: string | null;
};

export type GeocodedLocation = {
  address: string;
  latitude: number;
  longitude: number;
};

export type NoticeExtractionStatus = "EXTRACTED" | "LINK_ONLY" | "FAILED";

export type NoticeRegistrationPeriod = {
  label: string | null;
  startsAt: string;
  endsAt: string;
  periodText: string | null;
  source: string | null;
};

export type PoolNotice = {
  id: number;
  poolId: number;
  poolName: string;
  title: string;
  url: string;
  publishedAt: string | null;
  extractionStatus: NoticeExtractionStatus;
  confidence: number | null;
  registrationStartsAt: string | null;
  registrationEndsAt: string | null;
  registrationPeriods?: NoticeRegistrationPeriod[];
  reason: string | null;
};

export type NoticeScanResponse = {
  poolId: number;
  poolName: string;
  homepageUrl: string;
  scannedLinks: number;
  notices: PoolNotice[];
  message: string;
  trace: string[];
};

export type RegistrationEvent = {
  id: number;
  poolId: number;
  poolName: string;
  title: string;
  registrationStartsAt: string;
  registrationEndsAt: string;
  status: EventStatus;
  reminderQueued: boolean;
  startQueued: boolean;
};

export type AppUser = {
  id: number;
  email: string;
  displayName: string;
  profileImageUrl: string | null;
  notificationEnabled: boolean;
  fcmTokenRegistered: boolean;
  createdAt: string;
  lastLoginAt: string | null;
};

export type Subscription = {
  id: number;
  userId: number;
  pool: Pool;
  event: RegistrationEvent | null;
  createdAt: string;
};

export type InAppNotification = {
  id: number;
  userId: number;
  poolId: number;
  poolName: string;
  eventId: number;
  eventTitle: string;
  type: NotificationType;
  status: NotificationStatus;
  title: string;
  message: string;
  failureReason: string | null;
  attempts: number;
  createdAt: string;
  sentAt: string | null;
  readAt: string | null;
};

export type DeviceRegistration = {
  deviceId: string;
  registered: boolean;
  lastSeenAt: string | null;
};

export type MyPageMetrics = {
  subscriptionCount: number;
  upcomingSubscriptionCount: number;
  openSubscriptionCount: number;
  notificationCount: number;
  unreadNotificationCount: number;
  activeDeviceCount: number;
};

export type MyPageData = {
  user: AppUser;
  metrics: MyPageMetrics;
  subscriptions: Subscription[];
  notifications: InAppNotification[];
};

export type DashboardInitialData = {
  pools: Pool[];
  events: RegistrationEvent[];
  apiReachable: boolean;
};
