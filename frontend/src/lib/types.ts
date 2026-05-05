export type EventStatus = "UPCOMING" | "OPEN" | "CLOSED";

export type NotificationStatus = "QUEUED" | "SENT" | "FAILED";

export type NotificationType = "REGISTRATION_REMINDER" | "REGISTRATION_OPEN";

export type Pool = {
  id: number;
  name: string;
  address: string;
  district: string;
  websiteUrl: string;
  description: string;
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

export type DashboardInitialData = {
  pools: Pool[];
  events: RegistrationEvent[];
  apiReachable: boolean;
};
