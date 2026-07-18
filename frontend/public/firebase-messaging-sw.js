self.addEventListener("install", (event) => {
  event.waitUntil(self.skipWaiting());
});

self.addEventListener("activate", (event) => {
  event.waitUntil(self.clients.claim());
});

self.addEventListener("push", (event) => {
  let payload = {};

  try {
    payload = event.data ? event.data.json() : {};
  } catch {
    payload = {};
  }

  const notification = payload.notification ?? {};
  const data = payload.data ?? {};
  const type = data.type ?? "GENERAL";
  const notificationId = data.notificationId;
  const eventId = data.eventId;
  const poolId = data.poolId;
  const noticeUrl = data.noticeUrl;
  const subscriptionId = data.subscriptionId;
  const targetUrl = type === "SOURCE_REVIEW_REQUIRED" && subscriptionId
    ? `/my-page?subscriptionId=${subscriptionId}`
    : notificationId ? `/?notificationId=${notificationId}` : "/";
  const title = notification.title ?? data.title ?? titleForType(type);
  const body = notification.body ?? data.body ?? "새 알림이 도착했습니다.";
  const badgeText = type === "SOURCE_REVIEW_REQUIRED"
    ? "확인 필요"
    : type === "REGISTRATION_REMINDER" ? "곧 시작" : "접수 시작";
  const actions = [
    {
      action: "open",
      title: "알림 보기",
    },
  ];

  if (noticeUrl) {
    actions.push({
      action: "source",
      title: "원문 보기",
    });
  }

  event.waitUntil(
    self.registration.showNotification(title, {
      body: `${badgeText} · ${body}`,
      icon: "/swimpulse-notification.png",
      badge: "/swimpulse-badge.png",
      tag: notificationId ? `swimpulse-notification-${notificationId}` : `swimpulse-event-${eventId ?? Date.now()}`,
      renotify: Boolean(notificationId),
      timestamp: Date.now(),
      data: {
        ...data,
        notificationId,
        eventId,
        poolId,
        noticeUrl,
        url: targetUrl,
      },
      actions,
      vibrate: [80, 40, 80],
    }),
  );
});

self.addEventListener("notificationclick", (event) => {
  event.notification.close();

  if (event.action === "source" && event.notification.data?.noticeUrl) {
    event.waitUntil(clients.openWindow(event.notification.data.noticeUrl));
    return;
  }

  const targetPath = event.notification.data?.url ?? "/";
  const targetUrl = new URL(targetPath, self.location.origin).href;

  event.waitUntil(
    clients.matchAll({ type: "window", includeUncontrolled: true }).then(async (clientList) => {
      const sameOriginClient = clientList.find((client) => {
        try {
          return new URL(client.url).origin === self.location.origin;
        } catch {
          return false;
        }
      });

      if (sameOriginClient && "navigate" in sameOriginClient && "focus" in sameOriginClient) {
        try {
          const navigatedClient = await sameOriginClient.navigate(targetUrl);
          return (navigatedClient ?? sameOriginClient).focus();
        } catch {
          // A stale/uncontrolled tab can reject navigation. Open a fresh target instead.
        }
      }
      return clients.openWindow(targetUrl);
    }),
  );
});

function titleForType(type) {
  switch (type) {
    case "REGISTRATION_REMINDER":
      return "접수 시작이 곧 다가옵니다";
    case "REGISTRATION_OPEN":
      return "지금 접수가 시작됐습니다";
    case "SOURCE_REVIEW_REQUIRED":
      return "수영장 홈페이지 정보가 변경되었습니다";
    default:
      return "SwimPulse 알림";
  }
}
