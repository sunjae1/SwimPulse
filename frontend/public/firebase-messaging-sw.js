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
  const targetUrl = notificationId ? `/?notificationId=${notificationId}` : "/";
  const title = notification.title ?? data.title ?? titleForType(type);
  const body = notification.body ?? data.body ?? "새 알림이 도착했습니다.";
  const badgeText = type === "REGISTRATION_REMINDER" ? "곧 시작" : "접수 시작";
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

  const targetUrl = event.notification.data?.url ?? "/";

  event.waitUntil(
    clients.matchAll({ type: "window", includeUncontrolled: true }).then(async (clientList) => {
      for (const client of clientList) {
        if ("navigate" in client && "focus" in client) {
          const navigatedClient = await client.navigate(targetUrl);
          return (navigatedClient ?? client).focus();
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
    default:
      return "SwimPulse 알림";
  }
}
