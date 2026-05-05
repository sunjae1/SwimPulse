"use client";

import {
  Bell,
  CalendarClock,
  CheckCircle2,
  CircleAlert,
  LogIn,
  LogOut,
  MapPin,
  Mail,
  Plus,
  RefreshCw,
  Send,
  Smartphone,
  TimerReset,
  UserCircle,
  Waves,
} from "lucide-react";
import { FormEvent, useEffect, useMemo, useState } from "react";
import {
  ApiRequestError,
  createEvent,
  createSubscription,
  deleteSubscription,
  getCurrentDeviceRegistration,
  getEvents,
  getMe,
  getNotifications,
  getSubscriptions,
  logout,
  markNotificationRead,
  registerDeviceToken,
  sendTestNotification,
  unregisterCurrentDevice,
} from "@/lib/api";
import { eventStatusLabel, formatDateTime, formatTimeLeft, notificationStatusLabel } from "@/lib/format";
import { requestWebPushToken } from "@/lib/web-push";
import type {
  AppUser,
  DashboardInitialData,
  EventStatus,
  InAppNotification,
  Pool,
  RegistrationEvent,
  Subscription,
} from "@/lib/types";

type DashboardClientProps = {
  initialData: DashboardInitialData;
};

type EventForm = {
  poolId: string;
  title: string;
  startsAt: string;
  endsAt: string;
};

const statusOptions: Array<EventStatus | "ALL"> = ["ALL", "UPCOMING", "OPEN", "CLOSED"];

export function DashboardClient({ initialData }: DashboardClientProps) {
  const [apiReachable, setApiReachable] = useState(initialData.apiReachable);
  const [pools] = useState<Pool[]>(initialData.pools);
  const [events, setEvents] = useState<RegistrationEvent[]>(initialData.events);
  const [user, setUser] = useState<AppUser | null>(null);
  const [currentDeviceRegistered, setCurrentDeviceRegistered] = useState(false);
  const [subscriptions, setSubscriptions] = useState<Subscription[]>([]);
  const [notifications, setNotifications] = useState<InAppNotification[]>([]);
  const [statusFilter, setStatusFilter] = useState<EventStatus | "ALL">("ALL");
  const [pendingPoolId, setPendingPoolId] = useState<number | null>(null);
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [pushGuideOpen, setPushGuideOpen] = useState(false);
  const [form, setForm] = useState<EventForm>(() => {
    const start = new Date(Date.now() + 30 * 60 * 1000);
    const end = new Date(Date.now() + 90 * 60 * 1000);
    return {
      poolId: initialData.pools[0]?.id.toString() ?? "",
      title: "신규 회원 접수",
      startsAt: toDateTimeLocalValue(start),
      endsAt: toDateTimeLocalValue(end),
    };
  });

  const subscribedPoolIds = useMemo(() => new Set(subscriptions.map((item) => item.pool.id)), [subscriptions]);
  const filteredEvents = useMemo(
    () => events.filter((event) => statusFilter === "ALL" || event.status === statusFilter),
    [events, statusFilter],
  );
  const openEvents = events.filter((event) => event.status === "OPEN").length;
  const upcomingEvents = events.filter((event) => event.status === "UPCOMING").length;
  const unreadNotifications = notifications.filter((item) => !item.readAt).length;

  useEffect(() => {
    let cancelled = false;

    async function loadUserData() {
      try {
        const currentUser = await getMe();
        if (cancelled) {
          return;
        }
        setUser(currentUser);
        const [freshSubscriptions, freshNotifications, freshEvents] = await Promise.all([
          getSubscriptions(),
          getNotifications(),
          getEvents(),
        ]);
        const currentDevice = await getCurrentDeviceRegistration(getOrCreateDeviceId());
        if (!cancelled) {
          setSubscriptions(freshSubscriptions);
          setNotifications(freshNotifications);
          setEvents(freshEvents);
          setCurrentDeviceRegistered(currentDevice.registered);
          setApiReachable(true);
          const searchParams = new URLSearchParams(window.location.search);
          if (searchParams.get("login") === "success") {
            setNotice(`${currentUser.displayName} 계정으로 로그인됐습니다.`);
            window.history.replaceState(null, "", window.location.pathname);
          }
        }
      } catch (error) {
        if (!cancelled) {
          if (error instanceof ApiRequestError && error.status === 401) {
            setUser(null);
            setCurrentDeviceRegistered(false);
            setSubscriptions([]);
            setNotifications([]);
            setApiReachable(true);
            return;
          }
          setApiReachable(false);
        }
      }
    }

    loadUserData();

    return () => {
      cancelled = true;
    };
  }, []);

  async function refreshAll() {
    if (!user) {
      setNotice("Google 로그인 후 개인 알림을 불러올 수 있습니다.");
      return;
    }

    setBusy(true);
    setNotice(null);
    try {
      const [freshSubscriptions, freshNotifications, freshEvents] = await Promise.all([
        getSubscriptions(),
        getNotifications(),
        getEvents(),
      ]);
      const currentDevice = await getCurrentDeviceRegistration(getOrCreateDeviceId());
      setSubscriptions(freshSubscriptions);
      setNotifications(freshNotifications);
      setEvents(freshEvents);
      setCurrentDeviceRegistered(currentDevice.registered);
      setApiReachable(true);
      setNotice("최신 상태로 갱신됐습니다.");
    } catch {
      setApiReachable(false);
      setNotice("백엔드 API에 연결하지 못했습니다.");
    } finally {
      setBusy(false);
    }
  }

  function loginWithGoogle() {
    window.location.href = "/oauth2/authorization/google";
  }

  async function logoutUser() {
    setBusy(true);
    setNotice(null);
    try {
      await logout();
      setUser(null);
      setCurrentDeviceRegistered(false);
      setSubscriptions([]);
      setNotifications([]);
      setNotice("로그아웃됐습니다.");
    } catch {
      setNotice("로그아웃 요청을 처리하지 못했습니다.");
    } finally {
      setBusy(false);
    }
  }

  async function toggleSubscription(poolId: number) {
    if (!user) {
      setNotice("Google 로그인 후 구독할 수 있습니다.");
      return;
    }

    setPendingPoolId(poolId);
    setNotice(null);
    try {
      if (subscribedPoolIds.has(poolId)) {
        await deleteSubscription(poolId);
      } else {
        await createSubscription(poolId);
      }
      setSubscriptions(await getSubscriptions());
      setNotice("구독 상태가 반영됐습니다.");
    } catch {
      setNotice("구독 요청을 처리하지 못했습니다.");
    } finally {
      setPendingPoolId(null);
    }
  }

  async function enablePush() {
    if (!user) {
      setNotice("Google 로그인 후 웹 푸시를 등록할 수 있습니다.");
      return;
    }

    setBusy(true);
    setNotice(null);
    try {
      await registerCurrentDeviceForPush(user);
      setNotice("웹 푸시 토큰이 등록됐습니다.");
    } catch (error) {
      setNotice(getErrorMessage(error, "웹 푸시 토큰 등록에 실패했습니다."));
    } finally {
      setBusy(false);
    }
  }

  async function sendPushTest() {
    if (!user) {
      setNotice("Google 로그인 후 테스트 알림을 보낼 수 있습니다.");
      return;
    }

    setNotice(null);
    if (!currentDeviceRegistered) {
      setPushGuideOpen(true);
      return;
    }

    setBusy(true);
    try {
      const queued = await sendTestNotification();
      setNotifications((items) => [queued, ...items]);
      setNotice("테스트 알림을 Redis 큐에 넣었습니다. 실제 브라우저 푸시는 Firebase 설정이 연결되어 있어야 도착합니다.");
      window.setTimeout(() => {
        refreshAll();
      }, 1500);
    } catch (error) {
      if (isPushTokenMissingError(error)) {
        setUser({ ...user, fcmTokenRegistered: false });
        setCurrentDeviceRegistered(false);
        setPushGuideOpen(true);
        return;
      }
      setNotice(getErrorMessage(error, "테스트 알림 전송 요청에 실패했습니다."));
    } finally {
      setBusy(false);
    }
  }

  async function registerCurrentDeviceForPush(currentUser: AppUser) {
    const token = await requestWebPushToken();
    await registerDeviceToken(getOrCreateDeviceId(), token);
    setUser({ ...currentUser, fcmTokenRegistered: true, notificationEnabled: true });
    setCurrentDeviceRegistered(true);
  }

  async function unregisterPushForCurrentDevice() {
    if (!user) {
      return;
    }

    setBusy(true);
    setNotice(null);
    try {
      await unregisterCurrentDevice(getOrCreateDeviceId());
      setCurrentDeviceRegistered(false);
      setUser({ ...user, fcmTokenRegistered: false });
      setNotice("현재 기기의 PUSH 등록을 해제했습니다.");
    } catch (error) {
      if (error instanceof ApiRequestError && error.status === 404) {
        setCurrentDeviceRegistered(false);
        setUser({ ...user, fcmTokenRegistered: false });
        setNotice("현재 기기는 이미 PUSH 미등록 상태입니다.");
        return;
      }
      setNotice(getErrorMessage(error, "PUSH 등록 해제에 실패했습니다."));
    } finally {
      setBusy(false);
    }
  }

  async function readNotification(notificationId: number) {
    if (!user) {
      return;
    }

    try {
      const updated = await markNotificationRead(notificationId);
      setNotifications((items) => items.map((item) => (item.id === updated.id ? updated : item)));
    } catch {
      setNotice("알림 읽음 처리를 완료하지 못했습니다.");
    }
  }

  async function submitEvent(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!user) {
      setNotice("Google 로그인 후 이벤트를 등록할 수 있습니다.");
      return;
    }

    setBusy(true);
    setNotice(null);
    try {
      const created = await createEvent({
        poolId: Number(form.poolId),
        title: form.title,
        registrationStartsAt: new Date(form.startsAt).toISOString(),
        registrationEndsAt: new Date(form.endsAt).toISOString(),
      });
      setEvents((items) => [...items, created].sort((a, b) => a.registrationStartsAt.localeCompare(b.registrationStartsAt)));
      setNotice("접수 이벤트가 등록됐습니다.");
    } catch {
      setNotice("이벤트 등록에 실패했습니다.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="min-h-screen bg-[#f7f8f4] text-[#17201d]">
      <div className="border-b border-[#d8ddd5] bg-white">
        <div className="mx-auto flex max-w-7xl items-center justify-between px-5 py-4">
          <div className="flex items-center gap-3">
            <div className="grid size-10 place-items-center rounded-lg bg-[#0f766e] text-white">
              <Waves size={22} aria-hidden />
            </div>
            <div>
              <p className="text-sm font-semibold text-[#0f766e]">SwimPulse</p>
              <h1 className="text-xl font-semibold">수영장 등록 타이밍 알림</h1>
            </div>
          </div>
          <div className="flex items-center gap-2">
            <StatusPill active={apiReachable} />
            {user ? (
              <span className="hidden max-w-44 truncate rounded-lg border border-[#d8ddd5] bg-white px-3 py-2 text-sm font-semibold text-[#31413b] md:inline-flex">
                {user.displayName}
              </span>
            ) : null}
            <button
              className="grid size-10 place-items-center rounded-lg border border-[#cdd5cf] bg-white text-[#31413b] transition hover:border-[#0f766e] hover:text-[#0f766e] disabled:opacity-50"
              onClick={refreshAll}
              disabled={busy || !user}
              title="새로고침"
              type="button"
            >
              <RefreshCw size={18} aria-hidden />
            </button>
            <button
              className="grid size-10 place-items-center rounded-lg bg-[#bf4b3e] text-white transition hover:bg-[#a33f35] disabled:opacity-50"
              onClick={enablePush}
              disabled={busy || !user}
              title="웹 푸시 등록"
              type="button"
            >
              <Bell size={18} aria-hidden />
            </button>
            {user ? (
              <button
                className="grid size-10 place-items-center rounded-lg border border-[#cdd5cf] bg-white text-[#31413b] transition hover:border-[#bf4b3e] hover:text-[#bf4b3e] disabled:opacity-50"
                onClick={logoutUser}
                disabled={busy}
                title="로그아웃"
                type="button"
              >
                <LogOut size={18} aria-hidden />
              </button>
            ) : (
              <button
                className="inline-flex h-10 items-center justify-center gap-2 rounded-lg bg-[#17201d] px-4 text-sm font-semibold text-white transition hover:bg-[#31413b] disabled:opacity-50"
                onClick={loginWithGoogle}
                disabled={busy}
                type="button"
              >
                <LogIn size={17} aria-hidden />
                Google 로그인
              </button>
            )}
          </div>
        </div>
      </div>
      <NoticeToast message={notice} />

      <div className="mx-auto grid max-w-7xl grid-cols-1 gap-5 px-5 py-6 lg:grid-cols-[1fr_360px]">
        <section className="space-y-5">
          <div className="grid gap-3 sm:grid-cols-3">
            <Metric icon={CalendarClock} label="예정 이벤트" value={upcomingEvents.toString()} tone="teal" />
            <Metric icon={TimerReset} label="진행 중" value={openEvents.toString()} tone="amber" />
            <Metric icon={Bell} label="안 읽은 알림" value={unreadNotifications.toString()} tone="coral" />
          </div>

          {!user ? (
            <div className="flex flex-col gap-3 rounded-lg border border-[#d8ddd5] bg-white px-4 py-4 sm:flex-row sm:items-center sm:justify-between">
              <div>
                <h2 className="text-lg font-semibold">Google 로그인 필요</h2>
                <p className="text-sm text-[#66746d]">구독, 앱 내 알림, 웹 푸시는 로그인한 사용자 기준으로 저장됩니다.</p>
              </div>
              <button
                className="inline-flex h-10 items-center justify-center gap-2 rounded-lg bg-[#17201d] px-4 text-sm font-semibold text-white transition hover:bg-[#31413b]"
                onClick={loginWithGoogle}
                type="button"
              >
                <LogIn size={17} aria-hidden />
                Google 로그인
              </button>
            </div>
          ) : null}

          <section className="rounded-lg border border-[#d8ddd5] bg-white">
            <div className="flex flex-col gap-3 border-b border-[#e3e7e1] px-4 py-4 sm:flex-row sm:items-center sm:justify-between">
              <div>
                <h2 className="text-lg font-semibold">수영장 목록</h2>
                <p className="text-sm text-[#66746d]">{user ? `${user.displayName} 기준` : "로그인 후 구독할 수 있습니다"}</p>
              </div>
              <div className="flex flex-wrap gap-2">
                {statusOptions.map((status) => (
                  <button
                    key={status}
                    className={`h-9 rounded-lg px-3 text-sm font-medium transition ${
                      statusFilter === status
                        ? "bg-[#17201d] text-white"
                        : "border border-[#d8ddd5] bg-white text-[#31413b] hover:border-[#0f766e]"
                    }`}
                    onClick={() => setStatusFilter(status)}
                    type="button"
                  >
                    {status === "ALL" ? "전체" : eventStatusLabel(status)}
                  </button>
                ))}
              </div>
            </div>

            <div className="grid gap-0 divide-y divide-[#e3e7e1]">
              {pools.map((pool) => (
                <article key={pool.id} className="grid gap-4 px-4 py-4 md:grid-cols-[1fr_auto]">
                  <div className="space-y-2">
                    <div className="flex flex-wrap items-center gap-2">
                      <h3 className="font-semibold">{pool.name}</h3>
                      <span className="rounded-md bg-[#edf7f5] px-2 py-1 text-xs font-semibold text-[#0f766e]">
                        {pool.district}
                      </span>
                    </div>
                    <p className="flex items-center gap-1 text-sm text-[#66746d]">
                      <MapPin size={15} aria-hidden />
                      {pool.address}
                    </p>
                    <p className="text-sm text-[#47564f]">{pool.description}</p>
                  </div>
                  <button
                    className={`inline-flex h-10 items-center justify-center gap-2 rounded-lg px-4 text-sm font-semibold transition disabled:opacity-50 ${
                      subscribedPoolIds.has(pool.id)
                        ? "border border-[#0f766e] bg-white text-[#0f766e] hover:bg-[#edf7f5]"
                        : "bg-[#0f766e] text-white hover:bg-[#0b5f59]"
                    }`}
                    onClick={() => toggleSubscription(pool.id)}
                    disabled={pendingPoolId === pool.id || !user}
                    type="button"
                  >
                    {subscribedPoolIds.has(pool.id) ? <CheckCircle2 size={17} aria-hidden /> : <Plus size={17} aria-hidden />}
                    {subscribedPoolIds.has(pool.id) ? "구독 중" : "구독"}
                  </button>
                </article>
              ))}
            </div>
          </section>

          <section className="rounded-lg border border-[#d8ddd5] bg-white">
            <div className="border-b border-[#e3e7e1] px-4 py-4">
              <h2 className="text-lg font-semibold">접수 이벤트</h2>
            </div>
            <div className="divide-y divide-[#e3e7e1]">
              {filteredEvents.map((event) => (
                <article key={event.id} className="grid gap-3 px-4 py-4 md:grid-cols-[1fr_auto]">
                  <div className="space-y-2">
                    <div className="flex flex-wrap items-center gap-2">
                      <StatusBadge status={event.status} />
                      <h3 className="font-semibold">{event.title}</h3>
                    </div>
                    <p className="text-sm text-[#66746d]">{event.poolName}</p>
                    <p className="text-sm text-[#31413b]">
                      {formatDateTime(event.registrationStartsAt)} - {formatDateTime(event.registrationEndsAt)}
                    </p>
                  </div>
                  <div className="flex items-center text-right text-sm font-semibold text-[#bf4b3e] md:justify-end">
                    {event.status === "CLOSED" ? "마감" : formatTimeLeft(event.registrationStartsAt)}
                  </div>
                </article>
              ))}
            </div>
          </section>
        </section>

        <aside className="space-y-5">
          <AccountPanel
            user={user}
            subscriptionsCount={subscriptions.length}
            currentDeviceRegistered={currentDeviceRegistered}
            onLogin={loginWithGoogle}
            onLogout={logoutUser}
            onUnregisterDevice={unregisterPushForCurrentDevice}
            busy={busy}
          />

          <section className="rounded-lg border border-[#d8ddd5] bg-white">
            <div className="border-b border-[#e3e7e1] px-4 py-4">
              <h2 className="text-lg font-semibold">수동 이벤트 등록</h2>
            </div>
            <form className="space-y-4 px-4 py-4" onSubmit={submitEvent}>
              <label className="grid gap-1 text-sm font-medium">
                수영장
                <select
                  className="h-11 rounded-lg border border-[#cdd5cf] bg-white px-3 text-sm outline-none focus:border-[#0f766e]"
                  value={form.poolId}
                  onChange={(event) => setForm((current) => ({ ...current, poolId: event.target.value }))}
                  required
                >
                  {pools.map((pool) => (
                    <option key={pool.id} value={pool.id}>
                      {pool.name}
                    </option>
                  ))}
                </select>
              </label>
              <label className="grid gap-1 text-sm font-medium">
                이벤트명
                <input
                  className="h-11 rounded-lg border border-[#cdd5cf] px-3 text-sm outline-none focus:border-[#0f766e]"
                  value={form.title}
                  onChange={(event) => setForm((current) => ({ ...current, title: event.target.value }))}
                  required
                />
              </label>
              <label className="grid gap-1 text-sm font-medium">
                접수 시작
                <input
                  className="h-11 rounded-lg border border-[#cdd5cf] px-3 text-sm outline-none focus:border-[#0f766e]"
                  type="datetime-local"
                  value={form.startsAt}
                  onChange={(event) => setForm((current) => ({ ...current, startsAt: event.target.value }))}
                  required
                />
              </label>
              <label className="grid gap-1 text-sm font-medium">
                접수 종료
                <input
                  className="h-11 rounded-lg border border-[#cdd5cf] px-3 text-sm outline-none focus:border-[#0f766e]"
                  type="datetime-local"
                  value={form.endsAt}
                  onChange={(event) => setForm((current) => ({ ...current, endsAt: event.target.value }))}
                  required
                />
              </label>
              <button
                className="inline-flex h-11 w-full items-center justify-center gap-2 rounded-lg bg-[#17201d] px-4 text-sm font-semibold text-white transition hover:bg-[#31413b] disabled:opacity-50"
                disabled={busy || !user}
                type="submit"
              >
                <Send size={17} aria-hidden />
                등록
              </button>
            </form>
          </section>

          <section className="rounded-lg border border-[#d8ddd5] bg-white">
            <div className="flex items-center justify-between gap-3 border-b border-[#e3e7e1] px-4 py-4">
              <h2 className="text-lg font-semibold">앱 내 알림</h2>
              <div className="flex items-center gap-2">
                <span className="rounded-md bg-[#fff2e2] px-2 py-1 text-xs font-semibold text-[#946123]">
                  {notifications.length}
                </span>
                <button
                  className="grid size-9 place-items-center rounded-lg border border-[#cdd5cf] bg-white text-[#31413b] transition hover:border-[#0f766e] hover:text-[#0f766e] disabled:opacity-50"
                  onClick={sendPushTest}
                  disabled={busy || !user}
                  title="테스트 푸시 전송"
                  type="button"
                >
                  <Send size={16} aria-hidden />
                </button>
              </div>
            </div>
            <div className="max-h-[460px] divide-y divide-[#e3e7e1] overflow-auto">
              {notifications.length === 0 ? (
                <p className="px-4 py-8 text-sm text-[#66746d]">아직 저장된 알림이 없습니다.</p>
              ) : (
                notifications.map((item) => (
                  <button
                    key={item.id}
                    className="block w-full px-4 py-4 text-left transition hover:bg-[#f7f8f4]"
                    onClick={() => readNotification(item.id)}
                    type="button"
                  >
                    <div className="mb-2 flex items-center justify-between gap-2">
                      <span className={`text-sm font-semibold ${item.readAt ? "text-[#66746d]" : "text-[#17201d]"}`}>
                        {item.title}
                      </span>
                      <span className="rounded-md border border-[#d8ddd5] px-2 py-1 text-xs text-[#66746d]">
                        {notificationStatusLabel(item.status)}
                      </span>
                    </div>
                    <p className="text-sm text-[#47564f]">{item.message}</p>
                    <p className="mt-2 text-xs text-[#7c8982]">{formatDateTime(item.createdAt)}</p>
                  </button>
                ))
              )}
            </div>
          </section>
        </aside>
      </div>
      {pushGuideOpen ? <PushGuideModal onClose={() => setPushGuideOpen(false)} /> : null}
    </main>
  );
}

function NoticeToast({ message }: { message: string | null }) {
  if (!message) {
    return null;
  }

  return (
    <div className="pointer-events-none fixed inset-x-0 top-20 z-40 flex justify-center px-4" aria-live="polite">
      <div className="flex max-w-xl items-center gap-2 rounded-lg border border-[#d8ddd5] bg-white px-4 py-3 text-sm text-[#31413b] shadow-lg">
        <CircleAlert className="shrink-0" size={17} aria-hidden />
        <span className="min-w-0">{message}</span>
      </div>
    </div>
  );
}

function AccountPanel({
  user,
  subscriptionsCount,
  currentDeviceRegistered,
  onLogin,
  onLogout,
  onUnregisterDevice,
  busy,
}: {
  user: AppUser | null;
  subscriptionsCount: number;
  currentDeviceRegistered: boolean;
  onLogin: () => void;
  onLogout: () => void;
  onUnregisterDevice: () => void;
  busy: boolean;
}) {
  if (!user) {
    return (
      <section className="rounded-lg border border-[#d8ddd5] bg-white">
        <div className="border-b border-[#e3e7e1] px-4 py-4">
          <h2 className="text-lg font-semibold">계정</h2>
        </div>
        <div className="space-y-4 px-4 py-4">
          <div className="flex items-center gap-3">
            <div className="grid size-12 place-items-center rounded-lg bg-[#f0f1ef] text-[#66746d]">
              <UserCircle size={26} aria-hidden />
            </div>
            <div>
              <p className="font-semibold">로그인 안 됨</p>
              <p className="text-sm text-[#66746d]">Google 계정으로 시작하세요.</p>
            </div>
          </div>
          <button
            className="inline-flex h-10 w-full items-center justify-center gap-2 rounded-lg bg-[#17201d] px-4 text-sm font-semibold text-white transition hover:bg-[#31413b] disabled:opacity-50"
            onClick={onLogin}
            disabled={busy}
            type="button"
          >
            <LogIn size={17} aria-hidden />
            Google 로그인
          </button>
        </div>
      </section>
    );
  }

  return (
    <section className="rounded-lg border border-[#d8ddd5] bg-white">
      <div className="flex items-center justify-between gap-3 border-b border-[#e3e7e1] px-4 py-4">
        <h2 className="text-lg font-semibold">계정</h2>
        <button
          className="grid size-9 place-items-center rounded-lg border border-[#cdd5cf] bg-white text-[#31413b] transition hover:border-[#bf4b3e] hover:text-[#bf4b3e] disabled:opacity-50"
          onClick={onLogout}
          disabled={busy}
          title="로그아웃"
          type="button"
        >
          <LogOut size={16} aria-hidden />
        </button>
      </div>
      <div className="space-y-4 px-4 py-4">
        <div className="flex items-center gap-3">
          <div className="grid size-12 place-items-center rounded-lg bg-[#edf7f5] text-lg font-semibold text-[#0f766e]">
            {initialLetter(user.displayName)}
          </div>
          <div className="min-w-0">
            <p className="truncate font-semibold">{user.displayName}</p>
            <p className="truncate text-sm text-[#66746d]">Google 로그인</p>
          </div>
        </div>
        <div className="space-y-3 text-sm">
          <AccountRow icon={Mail} label="이메일" value={user.email} />
          <AccountRow icon={UserCircle} label="사용자 ID" value={user.id.toString()} />
          <AccountRow icon={CheckCircle2} label="구독" value={`${subscriptionsCount}개`} />
          <AccountRow icon={Smartphone} label="현재 기기 PUSH" value={currentDeviceRegistered ? "등록됨" : "미등록"} />
          <AccountRow icon={CalendarClock} label="최근 로그인" value={user.lastLoginAt ? formatDateTime(user.lastLoginAt) : "-"} />
        </div>
        <button
          className="inline-flex h-10 w-full items-center justify-center gap-2 rounded-lg border border-[#cdd5cf] bg-white px-4 text-sm font-semibold text-[#31413b] transition hover:border-[#bf4b3e] hover:text-[#bf4b3e] disabled:opacity-50"
          onClick={onUnregisterDevice}
          disabled={busy || !currentDeviceRegistered}
          type="button"
        >
          <Smartphone size={16} aria-hidden />
          현재 기기 PUSH 해제
        </button>
      </div>
    </section>
  );
}

function PushGuideModal({ onClose }: { onClose: () => void }) {
  return (
    <div className="fixed inset-0 z-50 grid place-items-center bg-black/35 px-4" role="dialog" aria-modal="true">
      <div className="w-full max-w-sm rounded-lg border border-[#d8ddd5] bg-white shadow-xl">
        <div className="border-b border-[#e3e7e1] px-5 py-4">
          <h2 className="text-lg font-semibold">PUSH 등록 필요</h2>
        </div>
        <div className="space-y-4 px-5 py-5">
          <p className="text-sm leading-6 text-[#31413b]">먼저 우측 상단에 종 버튼을 클릭하여 PUSH를 등록하세요.</p>
          <button
            className="inline-flex h-10 w-full items-center justify-center rounded-lg bg-[#17201d] px-4 text-sm font-semibold text-white transition hover:bg-[#31413b]"
            onClick={onClose}
            type="button"
          >
            확인
          </button>
        </div>
      </div>
    </div>
  );
}

function AccountRow({
  icon: Icon,
  label,
  value,
}: {
  icon: typeof Mail;
  label: string;
  value: string;
}) {
  return (
    <div className="flex items-start gap-2">
      <Icon className="mt-0.5 shrink-0 text-[#66746d]" size={15} aria-hidden />
      <div className="min-w-0">
        <p className="text-xs font-semibold text-[#66746d]">{label}</p>
        <p className="break-words text-[#31413b]">{value}</p>
      </div>
    </div>
  );
}

function Metric({
  icon: Icon,
  label,
  value,
  tone,
}: {
  icon: typeof CalendarClock;
  label: string;
  value: string;
  tone: "teal" | "amber" | "coral";
}) {
  const toneClass = {
    teal: "bg-[#edf7f5] text-[#0f766e]",
    amber: "bg-[#fff2e2] text-[#946123]",
    coral: "bg-[#fff0ed] text-[#bf4b3e]",
  }[tone];

  return (
    <div className="rounded-lg border border-[#d8ddd5] bg-white px-4 py-4">
      <div className={`mb-5 grid size-10 place-items-center rounded-lg ${toneClass}`}>
        <Icon size={19} aria-hidden />
      </div>
      <p className="text-sm text-[#66746d]">{label}</p>
      <p className="mt-1 text-3xl font-semibold">{value}</p>
    </div>
  );
}

function StatusPill({ active }: { active: boolean }) {
  return (
    <span
      className={`hidden rounded-lg px-3 py-2 text-sm font-semibold sm:inline-flex ${
        active ? "bg-[#edf7f5] text-[#0f766e]" : "bg-[#fff0ed] text-[#bf4b3e]"
      }`}
    >
      {active ? "API 연결" : "샘플 데이터"}
    </span>
  );
}

function StatusBadge({ status }: { status: EventStatus }) {
  const className = {
    UPCOMING: "bg-[#edf7f5] text-[#0f766e]",
    OPEN: "bg-[#fff2e2] text-[#946123]",
    CLOSED: "bg-[#f0f1ef] text-[#66746d]",
  }[status];

  return <span className={`rounded-md px-2 py-1 text-xs font-semibold ${className}`}>{eventStatusLabel(status)}</span>;
}

function toDateTimeLocalValue(date: Date) {
  const offset = date.getTimezoneOffset();
  return new Date(date.getTime() - offset * 60000).toISOString().slice(0, 16);
}

function getErrorMessage(error: unknown, fallback: string) {
  if (error instanceof Error && error.message) {
    return error.message;
  }
  return fallback;
}

function isPushTokenMissingError(error: unknown) {
  return error instanceof ApiRequestError && error.status === 400 && error.message.includes("Register web push");
}

function getOrCreateDeviceId() {
  const key = "swimpulse_device_id";
  const current = window.localStorage.getItem(key);
  if (current) {
    return current;
  }

  const next =
    typeof window.crypto?.randomUUID === "function"
      ? window.crypto.randomUUID()
      : `device-${Date.now()}-${Math.random().toString(16).slice(2)}`;
  window.localStorage.setItem(key, next);
  return next;
}

function initialLetter(value: string) {
  return value.trim().slice(0, 1).toUpperCase() || "U";
}
