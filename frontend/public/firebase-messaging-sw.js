self.addEventListener("push", (event) => {
  let payload = {};

  try {
    payload = event.data ? event.data.json() : {};
  } catch {
    payload = {};
  }

  const notification = payload.notification ?? {};
  const data = payload.data ?? {};
  const title = notification.title ?? data.title ?? "SwimPulse";
  const body = notification.body ?? data.body ?? "새 알림이 도착했습니다.";

  event.waitUntil(
    self.registration.showNotification(title, {
      body,
      data,
      icon: "/window.svg",
      badge: "/window.svg",
    }),
  );
});

self.addEventListener("notificationclick", (event) => {
  event.notification.close();

  const notificationId = event.notification.data?.notificationId;
  const targetUrl = notificationId ? `/?notificationId=${notificationId}` : "/";

  event.waitUntil(
    clients.matchAll({ type: "window", includeUncontrolled: true }).then((clientList) => {
      for (const client of clientList) {
        if ("focus" in client) {
          client.navigate(targetUrl);
          return client.focus();
        }
      }
      return clients.openWindow(targetUrl);
    }),
  );
});
