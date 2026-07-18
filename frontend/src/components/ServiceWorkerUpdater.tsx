"use client";

import { useEffect } from "react";

export function ServiceWorkerUpdater() {
  useEffect(() => {
    if (!("serviceWorker" in navigator)) {
      return;
    }

    void navigator.serviceWorker
      .register("/firebase-messaging-sw.js")
      .then((registration) => registration.update())
      .catch(() => {
        // Push registration has its own user-facing error handling in the dashboard.
      });
  }, []);

  return null;
}
