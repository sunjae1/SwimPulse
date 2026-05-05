const firebaseConfig = {
  apiKey: process.env.NEXT_PUBLIC_FIREBASE_API_KEY,
  authDomain: process.env.NEXT_PUBLIC_FIREBASE_AUTH_DOMAIN,
  projectId: process.env.NEXT_PUBLIC_FIREBASE_PROJECT_ID,
  messagingSenderId: process.env.NEXT_PUBLIC_FIREBASE_MESSAGING_SENDER_ID,
  appId: process.env.NEXT_PUBLIC_FIREBASE_APP_ID,
};

function hasFirebaseConfig() {
  return Object.values(firebaseConfig).every(Boolean) && Boolean(process.env.NEXT_PUBLIC_FIREBASE_VAPID_KEY);
}

export async function requestWebPushToken() {
  if (!("serviceWorker" in navigator)) {
    throw new Error("Service Worker is not supported in this browser.");
  }

  if (!("Notification" in window)) {
    throw new Error("Notification is not supported in this browser.");
  }

  if (Notification.permission === "denied") {
    throw new Error("브라우저 알림 권한이 차단되어 있습니다. 주소창 왼쪽 사이트 설정에서 알림을 허용으로 바꾼 뒤 다시 시도하세요.");
  }

  const permission = Notification.permission === "granted" ? "granted" : await Notification.requestPermission();
  if (permission !== "granted") {
    throw new Error("알림 권한을 허용해야 PUSH를 등록할 수 있습니다. 권한 팝업에서 허용을 선택하세요.");
  }

  const registration = await navigator.serviceWorker.register("/firebase-messaging-sw.js");

  if (!hasFirebaseConfig()) {
    throw new Error("Firebase 웹 푸시 환경변수가 비어 있습니다. frontend/.env.local에 NEXT_PUBLIC_FIREBASE_* 값을 설정하세요.");
  }

  const [{ initializeApp }, { getMessaging, getToken }] = await Promise.all([
    import("firebase/app"),
    import("firebase/messaging"),
  ]);

  const app = initializeApp({
    apiKey: firebaseConfig.apiKey,
    authDomain: firebaseConfig.authDomain,
    projectId: firebaseConfig.projectId,
    messagingSenderId: firebaseConfig.messagingSenderId,
    appId: firebaseConfig.appId,
  });

  const messaging = getMessaging(app);
  const token = await getToken(messaging, {
    vapidKey: process.env.NEXT_PUBLIC_FIREBASE_VAPID_KEY,
    serviceWorkerRegistration: registration,
  });

  if (!token) {
    throw new Error("FCM token was not issued.");
  }

  return token;
}
