export type EventStatus = "UPCOMING" | "OPEN" | "CLOSED";

export type NotificationStatus = "QUEUED" | "SENDING" | "SENT" | "FAILED";

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
  distanceMeters: number | null;
  homepageUrl: string | null;
};

export type PoolLocationCandidate = LocationSearchCandidate & {
  alreadyExists: boolean;
  matchedPoolId: number | null;
};

export type GeocodedLocation = {
  address: string;
  latitude: number;
  longitude: number;
};

export type NoticeExtractionStatus = "EXTRACTED" | "LINK_ONLY" | "FAILED";
export type NoticeOcrStatus = "NOT_REQUIRED" | "PENDING" | "PROCESSING" | "COMPLETED" | "NO_PERIOD" | "FAILED";

export type NoticeRegistrationPeriod = {
  id: number | null;
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
  ocrStatus?: NoticeOcrStatus;
  ocrRequestedAt?: string | null;
  ocrStartedAt?: string | null;
  ocrCompletedAt?: string | null;
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
  sharedResult?: boolean;
  waitedForActiveScan?: boolean;
  latestCheckFailed?: boolean;
};

export type RegistrationEvent = {
  id: number;
  noticeRegistrationPeriodId: number | null;
  noticeUrl: string | null;
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
  role: "USER" | "ADMIN";
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
  noticeUrl: string | null;
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

export type NotificationPage = {
  content: InAppNotification[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  first: boolean;
  last: boolean;
  unreadElements: number;
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

export type AdminMetricCount = {
  name: string;
  count: number;
};

export type AdminDashboard = {
  generatedAt: string;
  overview: {
    users: number;
    pools: number;
    subscriptions: number;
    events: number;
    activeDevices: number;
  };
  notifications: {
    queueLength: number;
    total: number;
    staleSending: number;
    byStatus: AdminMetricCount[];
  };
  deliveryStats: AdminNotificationDeliveryStats;
  notices: {
    totalNotices: number;
    pendingPeriodMigration: number;
    failedPeriodMigration: number;
    sourcesByStatus: AdminMetricCount[];
    noticesByExtractionStatus: AdminMetricCount[];
    noticesByOcrStatus: AdminMetricCount[];
  };
  workers: {
    notificationBatchSize: number;
    notificationDelayMs: number;
    notificationStaleSendingTimeoutMs: number;
    eventSchedulerPoolSize: number;
    eventSchedulerDelayMs: number;
  };
  topSubscribedPools: Array<{
    poolId: number;
    poolName: string;
    subscriptionCount: number;
  }>;
  topSubscribedDistricts: AdminDistrictRanking[];
  pendingPoolAddRequests: PoolAddRequest[];
  poolAddRequests: PoolAddRequest[];
  failedNotifications: InAppNotification[];
  recentActionLogs: AdminActionLog[];
};

export type AdminNotificationDeliveryStats = {
  queued: number;
  sending: number;
  sent: number;
  failed: number;
  successRate: number;
  failureRate: number;
};

export type AdminOperationsDashboard = {
  generatedAt: string;
  notifications: AdminDashboard["notifications"];
  deliveryStats: AdminNotificationDeliveryStats;
  workers: AdminDashboard["workers"];
  failedNotifications: InAppNotification[];
  recentActionLogs: AdminActionLog[];
};

export type AdminServiceDashboard = {
  generatedAt: string;
  overview: AdminDashboard["overview"];
  notices: AdminDashboard["notices"];
  topSubscribedPools: AdminDashboard["topSubscribedPools"];
  topSubscribedDistricts: AdminDistrictRanking[];
  pendingPoolAddRequests: PoolAddRequest[];
  poolAddRequests: PoolAddRequest[];
};

export type AdminDistrictRanking = {
  district: string;
  subscriptionCount: number;
};

export type PoolAddRequestStatus = "PENDING" | "APPROVED" | "REJECTED" | "MERGED";

export type PoolAddRequest = {
  id: number;
  requestedByUserId: number;
  requestedByEmail: string;
  title: string;
  category: string | null;
  address: string | null;
  roadAddress: string | null;
  homepageUrl: string | null;
  latitude: number | null;
  longitude: number | null;
  status: PoolAddRequestStatus;
  approvedPoolId: number | null;
  approvedPoolName: string | null;
  adminNote: string | null;
  createdAt: string;
  reviewedAt: string | null;
  reviewedByAdminId: number | null;
};

export type AdminActionResponse = {
  action: string;
  affected: number;
  message: string;
};

export type AdminActionResultStatus = "SUCCESS" | "FAILED";

export type AdminActionLog = {
  id: number;
  adminUserId: number | null;
  adminEmail: string | null;
  actionType: string;
  targetType: string;
  targetId: number | null;
  resultStatus: AdminActionResultStatus;
  message: string | null;
  createdAt: string;
};
