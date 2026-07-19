import messaging from '@react-native-firebase/messaging';
import type {FirebaseMessagingTypes} from '@react-native-firebase/messaging';
import {PermissionsAndroid, Platform} from 'react-native';
import {registerDeviceToken, sendTestNotification, unregisterCurrentDevice} from '../api/client';
import {getOrCreateDeviceId} from '../auth/tokenStore';

export type PushRegistrationResult = {
  registered: boolean;
  message: string;
};

export type ReceivedPushMessage = {
  notificationId?: string;
  subscriptionId?: string;
  type?: string;
  title: string;
  body: string;
  registrationStartsAt?: string;
  noticeUrl?: string;
  currentHomepageUrl?: string;
};

async function requestAndroidPostNotificationPermission() {
  if (Platform.OS !== 'android' || Platform.Version < 33) {
    return true;
  }

  const result = await PermissionsAndroid.request(
    PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS,
  );
  return result === PermissionsAndroid.RESULTS.GRANTED;
}

export async function registerPushToken(): Promise<PushRegistrationResult> {
  try {
    const notificationGranted = await requestAndroidPostNotificationPermission();
    if (!notificationGranted) {
      return {registered: false, message: '알림 권한이 거부되었습니다.'};
    }

    const messagingClient = messaging();
    await messagingClient.registerDeviceForRemoteMessages();
    await messagingClient.requestPermission();
    const fcmToken = await messagingClient.getToken();
    const deviceId = await getOrCreateDeviceId();
    await registerDeviceToken(deviceId, fcmToken, Platform.OS === 'ios' ? 'IOS' : 'ANDROID');
    return {registered: true, message: '푸시 기기를 등록했습니다.'};
  } catch (error) {
    return {
      registered: false,
      message: `푸시 등록 실패: ${String(error)}`,
    };
  }
}

export async function unregisterPushToken(): Promise<PushRegistrationResult> {
  try {
    const deviceId = await getOrCreateDeviceId();
    await unregisterCurrentDevice(deviceId);
    return {registered: false, message: '현재 기기 등록을 해제했습니다.'};
  } catch (error) {
    return {
      registered: false,
      message: `푸시 해제 실패: ${String(error)}`,
    };
  }
}

export async function sendMobileTestNotification() {
  return sendTestNotification();
}

export function subscribeToForegroundPushMessages(
  onMessage: (message: ReceivedPushMessage) => void,
) {
  return messaging().onMessage(remoteMessage => {
    onMessage(toReceivedPushMessage(remoteMessage));
  });
}

export function subscribeToOpenedPushMessages(
  onMessage: (message: ReceivedPushMessage) => void,
) {
  return messaging().onNotificationOpenedApp(remoteMessage => {
    onMessage(toReceivedPushMessage(remoteMessage));
  });
}

export async function getInitialPushMessage(): Promise<ReceivedPushMessage | null> {
  const remoteMessage = await messaging().getInitialNotification();
  return remoteMessage ? toReceivedPushMessage(remoteMessage) : null;
}

function toReceivedPushMessage(
  remoteMessage: FirebaseMessagingTypes.RemoteMessage,
): ReceivedPushMessage {
  const dataNotificationId = stringValue(remoteMessage.data?.notificationId);
  const dataTitle = stringValue(remoteMessage.data?.title);
  const dataBody = stringValue(remoteMessage.data?.body);
  const dataRegistrationStartsAt = stringValue(remoteMessage.data?.registrationStartsAt);
  const dataNoticeUrl = stringValue(remoteMessage.data?.noticeUrl);
  const dataCurrentHomepageUrl = stringValue(remoteMessage.data?.currentHomepageUrl);

  return {
    notificationId: dataNotificationId,
    subscriptionId: stringValue(remoteMessage.data?.subscriptionId),
    type: stringValue(remoteMessage.data?.type),
    title: remoteMessage.notification?.title ?? dataTitle ?? 'SwimPulse 알림',
    body: remoteMessage.notification?.body ?? dataBody ?? '새 알림이 도착했습니다.',
    registrationStartsAt: dataRegistrationStartsAt,
    noticeUrl: dataNoticeUrl,
    currentHomepageUrl: dataCurrentHomepageUrl,
  };
}

function stringValue(value: string | object | undefined): string | undefined {
  return typeof value === 'string' && value.length > 0 ? value : undefined;
}
