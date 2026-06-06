"use client";

import {
  Bell,
  CalendarClock,
  CheckCircle2,
  CircleAlert,
  ExternalLink,
  FileSearch,
  ImageIcon,
  List,
  LocateFixed,
  LogIn,
  LogOut,
  MapPin,
  Mail,
  Plus,
  RefreshCw,
  Search,
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
  createPoolFromLocationCandidate,
  createSubscription,
  deleteSubscription,
  geocodeLocation,
  getCurrentDeviceRegistration,
  getEvents,
  getMe,
  getNearbyPools,
  getNotifications,
  getSubscriptions,
  logout,
  markNotificationRead,
  registerDeviceToken,
  reverseGeocodeLocation,
  scanPoolNotices,
  searchLocations,
  sendTestNotification,
  unregisterCurrentDevice,
} from "@/lib/api";
import { AppNavigation } from "@/components/AppNavigation";
import { eventStatusLabel, formatDate, formatDateTime, formatTimeLeft, notificationStatusLabel } from "@/lib/format";
import { requestWebPushToken } from "@/lib/web-push";
import type {
  AppUser,
  DashboardInitialData,
  EventStatus,
  InAppNotification,
  LocationSearchCandidate,
  NearbyPool,
  NoticeRegistrationPeriod,
  NoticeScanResponse,
  PoolNotice,
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
  const [allPools, setAllPools] = useState<Pool[]>(initialData.pools);
  const [pools, setPools] = useState<Pool[]>(initialData.pools);
  const [nearbyMode, setNearbyMode] = useState(false);
  const [nearbyDistances, setNearbyDistances] = useState<Record<number, number>>({});
  const [currentLocation, setCurrentLocation] = useState<{ latitude: number; longitude: number } | null>(null);
  const [nearbyOriginLabel, setNearbyOriginLabel] = useState<string | null>(null);
  const [locationQuery, setLocationQuery] = useState("");
  const [locationCandidates, setLocationCandidates] = useState<LocationSearchCandidate[]>([]);
  const [facilityCandidates, setFacilityCandidates] = useState<LocationSearchCandidate[]>([]);
  const [locationSearchBusy, setLocationSearchBusy] = useState(false);
  const [candidateToAdd, setCandidateToAdd] = useState<LocationSearchCandidate | null>(null);
  const [noticeScanResult, setNoticeScanResult] = useState<NoticeScanResponse | null>(null);
  const [events, setEvents] = useState<RegistrationEvent[]>(initialData.events);
  const [user, setUser] = useState<AppUser | null>(null);
  const [currentDeviceRegistered, setCurrentDeviceRegistered] = useState(false);
  const [subscriptions, setSubscriptions] = useState<Subscription[]>([]);
  const [notifications, setNotifications] = useState<InAppNotification[]>([]);
  const [statusFilter, setStatusFilter] = useState<EventStatus | "ALL">("ALL");
  const [noticeSubscriptionMode, setNoticeSubscriptionMode] = useState(false);
  const [pendingSubscriptionKey, setPendingSubscriptionKey] = useState<string | null>(null);
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

  const subscribedEventKeys = useMemo(
    () =>
      new Set(
        subscriptions
          .map((item) => (item.event ? subscriptionKeyFromEvent(item.event) : null))
          .filter((key): key is string => Boolean(key)),
      ),
    [subscriptions],
  );
  const subscribedPeriodPoolIds = useMemo(
    () => new Set(subscriptions.map((item) => item.event?.poolId).filter((poolId): poolId is number => poolId !== undefined)),
    [subscriptions],
  );
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

  async function loadNearbyPools() {
    if (!("geolocation" in navigator)) {
      setNotice("이 브라우저에서는 위치 정보를 사용할 수 없습니다.");
      return;
    }

    setBusy(true);
    setNotice(null);
    try {
      const position = await getCurrentPosition();
      const location = {
        latitude: position.coords.latitude,
        longitude: position.coords.longitude,
      };
      const [nearby, reverseGeocoded] = await Promise.all([
        getNearbyPools(location.latitude, location.longitude, 10),
        reverseGeocodeLocation(location.latitude, location.longitude).catch(() => null),
      ]);
      const originLabel = reverseGeocoded?.address ?? "현재 위치";
      setPools(nearby.map((item) => item.pool));
      setNearbyDistances(toDistanceMap(nearby));
      setCurrentLocation(location);
      setNearbyOriginLabel(originLabel);
      setNearbyMode(true);
      setApiReachable(true);
      setNotice(`${originLabel} 기준 가까운 수영장 10개를 불러왔습니다.`);
    } catch (error) {
      setNotice(getGeolocationErrorMessage(error));
    } finally {
      setBusy(false);
    }
  }

  function resetPoolList() {
    setPools(allPools);
    setNearbyDistances({});
    setCurrentLocation(null);
    setNearbyOriginLabel(null);
    setFacilityCandidates([]);
    setNearbyMode(false);
    setNotice("전체 수영장 목록으로 돌아왔습니다.");
  }

  async function submitLocationSearch(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const query = locationQuery.trim();
    if (!query) {
      setNotice("검색할 장소나 주소를 입력하세요.");
      return;
    }

    setLocationSearchBusy(true);
    setNotice(null);
    try {
      const candidates = await searchLocations(query, 5);
      setLocationCandidates(candidates);
      setFacilityCandidates([]);
      setApiReachable(true);
      setNotice(candidates.length > 0 ? "검색 후보를 불러왔습니다." : "검색 후보가 없습니다.");
    } catch (error) {
      setNotice(getErrorMessage(error, "장소 검색에 실패했습니다."));
    } finally {
      setLocationSearchBusy(false);
    }
  }

  async function selectLocationCandidate(candidate: LocationSearchCandidate) {
    const address = candidate.roadAddress ?? candidate.address;
    if (!address) {
      setNotice("선택한 후보에 사용할 주소가 없습니다.");
      return;
    }

    setBusy(true);
    setNotice(null);
    try {
      const geocoded = await geocodeLocation(address);
      const selectedLocation = {
        latitude: geocoded.latitude,
        longitude: geocoded.longitude,
      };
      const [nearby, facilities] = await Promise.all([
        getNearbyPools(geocoded.latitude, geocoded.longitude, 10),
        searchLocations(buildFacilitySearchQuery(geocoded.address), 10, selectedLocation),
      ]);
      setLocationQuery(candidate.title);
      setLocationCandidates([]);
      setFacilityCandidates(facilities.filter((item) => !item.alreadyExists));
      setPools(nearby.map((item) => item.pool));
      setNearbyDistances(toDistanceMap(nearby));
      setCurrentLocation(selectedLocation);
      setNearbyOriginLabel(candidate.title);
      setNearbyMode(true);
      setApiReachable(true);
      setNotice(`${candidate.title} 기준 가까운 수영장과 추가 후보를 불러왔습니다.`);
    } catch (error) {
      setNotice(getErrorMessage(error, "선택한 위치 기준 가까운 수영장을 찾지 못했습니다."));
    } finally {
      setBusy(false);
    }
  }

  async function addLocationCandidate() {
    if (!candidateToAdd) {
      return;
    }
    if (!user) {
      setNotice("Google 로그인 후 시설을 추가할 수 있습니다.");
      setCandidateToAdd(null);
      return;
    }

    setBusy(true);
    setNotice(null);
    try {
      const created = await createPoolFromLocationCandidate(candidateToAdd);
      setAllPools((items) => upsertPool(items, created));
      setPools((items) => upsertPool(items, created));
      setLocationCandidates((items) =>
        items.map((item) =>
          item.title === candidateToAdd.title
            ? { ...item, alreadyExists: true, matchedPoolId: created.id, latitude: created.latitude, longitude: created.longitude }
            : item,
        ),
      );
      setFacilityCandidates((items) => items.filter((item) => item.title !== candidateToAdd.title));
      setNotice(`${created.name} 시설을 DB에 추가했습니다.`);
      setCandidateToAdd(null);
    } catch (error) {
      setNotice(getErrorMessage(error, "시설 추가에 실패했습니다."));
    } finally {
      setBusy(false);
    }
  }

  async function scanNotices(pool: Pool, subscriptionMode = false) {
    if (!user) {
      setNotice("Google 로그인 후 공지를 확인할 수 있습니다.");
      return;
    }
    setBusy(true);
    setNotice(null);
    try {
      const result = await scanPoolNotices(pool.id);
      setNoticeScanResult(result);
      setNoticeSubscriptionMode(subscriptionMode);
      setNotice(subscriptionMode ? "구독할 모집 기간을 선택하세요." : `${pool.name} 공지를 확인했습니다.`);
    } catch (error) {
      setNotice(getErrorMessage(error, "공지 확인에 실패했습니다."));
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

  async function subscribeToNoticePeriod(notice: PoolNotice, period: NoticeRegistrationPeriod) {
    if (!user) {
      setNotice("Google 로그인 후 구독할 수 있습니다.");
      return;
    }
    const title = buildSubscriptionTitle(notice, period);
    const key = subscriptionKey(notice.poolId, title, period.startsAt, period.endsAt);
    setPendingSubscriptionKey(key);
    setNotice(null);
    try {
      await createSubscription({
        poolId: notice.poolId,
        title,
        registrationStartsAt: period.startsAt,
        registrationEndsAt: period.endsAt,
      });
      const [freshSubscriptions, freshEvents] = await Promise.all([getSubscriptions(), getEvents()]);
      setSubscriptions(freshSubscriptions);
      setEvents(freshEvents);
      setNotice("선택한 모집 기간 알림을 구독했습니다.");
    } catch (error) {
      setNotice(getErrorMessage(error, "구독 요청을 처리하지 못했습니다."));
    } finally {
      setPendingSubscriptionKey(null);
    }
  }

  async function unsubscribeFromNoticePeriod(subscription: Subscription) {
    if (!subscription.event) {
      return;
    }
    const key = subscriptionKeyFromEvent(subscription.event);
    setPendingSubscriptionKey(key);
    setNotice(null);
    try {
      await deleteSubscription(subscription.event.id);
      setSubscriptions(await getSubscriptions());
      setNotice("선택한 모집 기간 구독을 해제했습니다.");
    } catch (error) {
      setNotice(getErrorMessage(error, "구독 해제를 처리하지 못했습니다."));
    } finally {
      setPendingSubscriptionKey(null);
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
        <div className="mx-auto flex max-w-7xl flex-col gap-4 px-5 py-4 lg:flex-row lg:items-center lg:justify-between">
          <div className="flex flex-col gap-3 md:flex-row md:items-center md:gap-5">
            <div className="flex items-center gap-3">
              <div className="grid size-10 place-items-center rounded-lg bg-[#0f766e] text-white">
                <Waves size={22} aria-hidden />
              </div>
              <div>
                <p className="text-sm font-semibold text-[#0f766e]">SwimPulse</p>
                <h1 className="text-xl font-semibold">수영장 등록 타이밍 알림</h1>
              </div>
            </div>
            <AppNavigation />
          </div>
          <div className="flex flex-wrap items-center gap-2">
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
                <p className="text-sm text-[#66746d]">
                  {nearbyMode && currentLocation
                    ? `${nearbyOriginLabel ?? "선택 위치"} 기준 가까운 10개`
                    : user
                      ? `${user.displayName} 기준`
                      : "로그인 후 구독할 수 있습니다"}
                </p>
              </div>
              <div className="flex flex-wrap gap-2">
                <button
                  className={`inline-flex h-9 items-center justify-center gap-2 rounded-lg px-3 text-sm font-medium transition disabled:opacity-50 ${
                    nearbyMode
                      ? "bg-[#17201d] text-white"
                      : "border border-[#d8ddd5] bg-white text-[#31413b] hover:border-[#0f766e]"
                  }`}
                  onClick={loadNearbyPools}
                  disabled={busy}
                  type="button"
                >
                  <LocateFixed size={16} aria-hidden />
                  가까운 순
                </button>
                {nearbyMode ? (
                  <button
                    className="inline-flex h-9 items-center justify-center gap-2 rounded-lg border border-[#d8ddd5] bg-white px-3 text-sm font-medium text-[#31413b] transition hover:border-[#0f766e]"
                    onClick={resetPoolList}
                    type="button"
                  >
                    <List size={16} aria-hidden />
                    전체
                  </button>
                ) : null}
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

            <div className="border-b border-[#e3e7e1] px-4 py-4">
              <form className="flex flex-col gap-2 sm:flex-row" onSubmit={submitLocationSearch}>
                <label className="sr-only" htmlFor="location-search">
                  위치 검색
                </label>
                <input
                  id="location-search"
                  className="h-10 min-w-0 flex-1 rounded-lg border border-[#cdd5cf] px-3 text-sm outline-none focus:border-[#0f766e]"
                  value={locationQuery}
                  onChange={(event) => {
                    setLocationQuery(event.target.value);
                    setLocationCandidates([]);
                    setFacilityCandidates([]);
                  }}
                  placeholder="화성남부국민체육센터"
                />
                <button
                  className="inline-flex h-10 items-center justify-center gap-2 rounded-lg bg-[#17201d] px-4 text-sm font-semibold text-white transition hover:bg-[#31413b] disabled:opacity-50"
                  disabled={locationSearchBusy}
                  type="submit"
                >
                  <Search size={16} aria-hidden />
                  검색
                </button>
              </form>
              {locationCandidates.length > 0 ? (
                <div className="mt-3 grid gap-2">
                  {locationCandidates.map((candidate, index) => {
                    const address = candidate.roadAddress ?? candidate.address ?? "주소 없음";
                    return (
                      <div
                        key={`${candidate.title}-${address}-${index}`}
                        className="rounded-lg border border-[#d8ddd5] bg-white px-3 py-3"
                      >
                        <button className="grid w-full gap-1 text-left" onClick={() => selectLocationCandidate(candidate)} disabled={busy} type="button">
                          <span className="flex flex-wrap items-center gap-2 text-sm font-semibold text-[#17201d]">
                            {candidate.title}
                            <span className="rounded-md bg-[#edf7f5] px-2 py-1 text-xs font-semibold text-[#0f766e]">
                              기준 위치
                            </span>
                          </span>
                          <span className="text-xs text-[#66746d]">{address}</span>
                          {candidate.category ? <span className="text-xs text-[#0f766e]">{candidate.category}</span> : null}
                        </button>
                      </div>
                    );
                  })}
                </div>
              ) : null}
              {facilityCandidates.length > 0 ? (
                <div className="mt-4 space-y-3">
                  <div>
                    <h3 className="text-sm font-semibold">선택 위치 기준 추가 후보</h3>
                    <p className="text-xs text-[#66746d]">DB에 아직 없는 체육센터 후보입니다.</p>
                  </div>
                  <div className="grid gap-2">
                    {facilityCandidates.map((candidate, index) => {
                      const address = candidate.roadAddress ?? candidate.address ?? "주소 없음";
                      return (
                        <div
                          key={`facility-${candidate.title}-${address}-${index}`}
                          className="grid gap-3 rounded-lg border border-[#d8ddd5] bg-[#fbfcf8] px-3 py-3 sm:grid-cols-[1fr_auto]"
                        >
                          <button className="grid gap-1 text-left" onClick={() => selectLocationCandidate(candidate)} disabled={busy} type="button">
                            <span className="flex flex-wrap items-center gap-2 text-sm font-semibold text-[#17201d]">
                              {candidate.title}
                              <span className="rounded-md bg-[#fff2e2] px-2 py-1 text-xs font-semibold text-[#946123]">
                                추가 가능
                              </span>
                              {candidate.distanceMeters !== null ? (
                                <span className="rounded-md bg-[#f0f1ef] px-2 py-1 text-xs text-[#66746d]">
                                  {formatDistance(candidate.distanceMeters)}
                                </span>
                              ) : null}
                            </span>
                            <span className="text-xs text-[#66746d]">{address}</span>
                            {candidate.category ? <span className="text-xs text-[#0f766e]">{candidate.category}</span> : null}
                          </button>
                          <button
                            className="inline-flex h-9 items-center justify-center gap-2 rounded-lg bg-[#0f766e] px-3 text-sm font-semibold text-white transition hover:bg-[#0b5f59] disabled:opacity-50"
                            onClick={() => setCandidateToAdd(candidate)}
                            disabled={busy || !user}
                            type="button"
                          >
                            <Plus size={15} aria-hidden />
                            이 시설 추가
                          </button>
                        </div>
                      );
                    })}
                  </div>
                </div>
              ) : null}
            </div>

            <div className="grid gap-0 divide-y divide-[#e3e7e1]">
              {pools.map((pool) => (
                <article key={pool.id} className="grid gap-4 px-4 py-4 md:grid-cols-[112px_1fr_auto]">
                  <PoolImage pool={pool} />
                  <div className="space-y-2">
                    <div className="flex flex-wrap items-center gap-2">
                      <h3 className="font-semibold">{pool.name}</h3>
                      {pool.district ? (
                        <span className="rounded-md bg-[#edf7f5] px-2 py-1 text-xs font-semibold text-[#0f766e]">
                          {pool.district}
                        </span>
                      ) : null}
                      {nearbyDistances[pool.id] !== undefined ? (
                        <span className="rounded-md bg-[#fff2e2] px-2 py-1 text-xs font-semibold text-[#946123]">
                          {formatDistance(nearbyDistances[pool.id])}
                        </span>
                      ) : null}
                    </div>
                    {pool.roadNameAddress ?? pool.lotNumberAddress ? (
                      <p className="flex items-center gap-1 text-sm text-[#66746d]">
                        <MapPin size={15} aria-hidden />
                        {pool.roadNameAddress ?? pool.lotNumberAddress}
                      </p>
                    ) : null}
                    <div className="flex flex-wrap gap-2 text-xs font-medium text-[#66746d]">
                      {pool.indoorOutdoorTypeName ? <span>{pool.indoorOutdoorTypeName}</span> : null}
                      {pool.standardPoolLengthMeters ? <span>{pool.standardPoolLengthMeters}m</span> : null}
                      {pool.standardPoolLaneCount ? <span>{pool.standardPoolLaneCount}레인</span> : null}
                      {pool.completionYear ? <span>{pool.completionYear}년 준공</span> : null}
                    </div>
                    {pool.description ? <p className="text-sm text-[#47564f]">{pool.description}</p> : null}
                    <div className="flex flex-wrap gap-2">
                      {pool.homepageUrl ? (
                        <a
                          className="inline-flex h-8 items-center gap-1 rounded-lg border border-[#d8ddd5] px-3 text-xs font-semibold text-[#31413b] transition hover:border-[#0f766e] hover:text-[#0f766e]"
                          href={pool.homepageUrl}
                          target="_blank"
                          rel="noreferrer"
                        >
                          <ExternalLink size={14} aria-hidden />
                          홈페이지
                        </a>
                      ) : null}
                      <button
                        className="inline-flex h-8 items-center gap-1 rounded-lg border border-[#d8ddd5] px-3 text-xs font-semibold text-[#31413b] transition hover:border-[#0f766e] hover:text-[#0f766e] disabled:opacity-50"
                        onClick={() => scanNotices(pool)}
                        disabled={busy || !user || !pool.homepageUrl}
                        title={!pool.homepageUrl ? "홈페이지를 찾을 수 없습니다." : "공지 확인"}
                        type="button"
                      >
                        <FileSearch size={14} aria-hidden />
                        공지 확인
                      </button>
                    </div>
                    {!pool.homepageUrl ? (
                      <p className="flex items-center gap-1 text-xs font-medium text-[#bf4b3e]">
                        <CircleAlert size={14} aria-hidden />
                        홈페이지를 찾을 수 없습니다.
                      </p>
                    ) : null}
                  </div>
                  <div className="flex items-start md:justify-end">
                    <button
                      className={`inline-flex h-10 items-center justify-center gap-2 rounded-lg px-4 text-sm font-semibold transition disabled:opacity-50 ${
                        subscribedPeriodPoolIds.has(pool.id)
                          ? "border border-[#0f766e] bg-white text-[#0f766e] hover:bg-[#edf7f5]"
                          : "bg-[#0f766e] text-white hover:bg-[#0b5f59]"
                      }`}
                      onClick={() => scanNotices(pool, true)}
                      disabled={busy || !user || !pool.homepageUrl}
                      title={!pool.homepageUrl ? "홈페이지를 찾을 수 없습니다." : "알림 구독"}
                      type="button"
                    >
                      {subscribedPeriodPoolIds.has(pool.id) ? <CheckCircle2 size={17} aria-hidden /> : <Plus size={17} aria-hidden />}
                      {subscribedPeriodPoolIds.has(pool.id) ? "기간 구독 중" : "알림 구독"}
                    </button>
                  </div>
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
            subscriptions={subscriptions}
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
                  {allPools.map((pool) => (
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
      {candidateToAdd ? (
        <CandidateConfirmModal
          candidate={candidateToAdd}
          onConfirm={addLocationCandidate}
          onClose={() => setCandidateToAdd(null)}
          busy={busy}
        />
      ) : null}
      {noticeScanResult ? (
        <NoticeResultModal
          result={noticeScanResult}
          onClose={() => {
            setNoticeScanResult(null);
            setNoticeSubscriptionMode(false);
          }}
          subscriptionMode={noticeSubscriptionMode}
          subscriptions={subscriptions}
          subscribedEventKeys={subscribedEventKeys}
          pendingSubscriptionKey={pendingSubscriptionKey}
          onSubscribe={subscribeToNoticePeriod}
          onUnsubscribe={unsubscribeFromNoticePeriod}
        />
      ) : null}
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

function PoolImage({ pool }: { pool: Pool }) {
  const label = pool.indoorOutdoorTypeName === "실외" ? "실외 수영장" : "실내 수영장";

  if (pool.imageUrl) {
    return (
      <div
        className="h-24 rounded-lg bg-[#edf7f5] bg-cover bg-center md:h-28"
        aria-label={`${pool.name} 대표 이미지`}
        role="img"
        style={{ backgroundImage: `url(${pool.imageUrl})` }}
      />
    );
  }

  return (
    <div className="grid h-24 place-items-center rounded-lg border border-[#d8ddd5] bg-[#edf7f5] text-[#0f766e] md:h-28">
      <div className="grid gap-1 text-center text-xs font-semibold">
        <ImageIcon className="mx-auto" size={20} aria-hidden />
        <span>{label}</span>
      </div>
    </div>
  );
}

function AccountPanel({
  user,
  subscriptions,
  currentDeviceRegistered,
  onLogin,
  onLogout,
  onUnregisterDevice,
  busy,
}: {
  user: AppUser | null;
  subscriptions: Subscription[];
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
          <AccountRow icon={CheckCircle2} label="구독" value={`${subscriptions.length}개`} />
          <AccountRow icon={Smartphone} label="현재 기기 PUSH" value={currentDeviceRegistered ? "등록됨" : "미등록"} />
          <AccountRow icon={CalendarClock} label="최근 로그인" value={user.lastLoginAt ? formatDateTime(user.lastLoginAt) : "-"} />
        </div>
        <div className="space-y-3 border-t border-[#e3e7e1] pt-4">
          <div className="flex items-center justify-between gap-2">
            <h3 className="text-sm font-semibold text-[#31413b]">내 구독</h3>
            <span className="rounded-md bg-[#edf7f5] px-2 py-1 text-xs font-semibold text-[#0f766e]">
              {subscriptions.length}
            </span>
          </div>
          {subscriptions.length === 0 ? (
            <p className="rounded-lg border border-dashed border-[#cdd5cf] px-3 py-4 text-sm text-[#66746d]">
              아직 구독한 모집 기간이 없습니다.
            </p>
          ) : (
            <div className="max-h-[340px] divide-y divide-[#e3e7e1] overflow-auto rounded-lg border border-[#e3e7e1]">
              {subscriptions.map((subscription) => (
                <SubscriptionSummary key={subscription.id} subscription={subscription} />
              ))}
            </div>
          )}
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

function CandidateConfirmModal({
  candidate,
  onConfirm,
  onClose,
  busy,
}: {
  candidate: LocationSearchCandidate;
  onConfirm: () => void;
  onClose: () => void;
  busy: boolean;
}) {
  const address = candidate.roadAddress ?? candidate.address ?? "주소 없음";

  return (
    <div className="fixed inset-0 z-50 grid place-items-center bg-black/35 px-4" role="dialog" aria-modal="true">
      <div className="w-full max-w-md rounded-lg border border-[#d8ddd5] bg-white shadow-xl">
        <div className="border-b border-[#e3e7e1] px-5 py-4">
          <h2 className="text-lg font-semibold">시설 추가 확인</h2>
        </div>
        <div className="space-y-4 px-5 py-5">
          <div className="space-y-2">
            <p className="font-semibold">{candidate.title}</p>
            <p className="text-sm text-[#66746d]">{address}</p>
            {candidate.link ? (
              <p className="break-all text-xs text-[#0f766e]">{candidate.link}</p>
            ) : (
              <p className="text-xs text-[#946123]">홈페이지 정보 없음</p>
            )}
          </div>
          <div className="grid gap-2 sm:grid-cols-2">
            <button
              className="inline-flex h-10 items-center justify-center rounded-lg border border-[#cdd5cf] bg-white px-4 text-sm font-semibold text-[#31413b] transition hover:border-[#0f766e]"
              onClick={onClose}
              disabled={busy}
              type="button"
            >
              취소
            </button>
            <button
              className="inline-flex h-10 items-center justify-center rounded-lg bg-[#0f766e] px-4 text-sm font-semibold text-white transition hover:bg-[#0b5f59] disabled:opacity-50"
              onClick={onConfirm}
              disabled={busy}
              type="button"
            >
              DB에 추가
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}

function NoticeResultModal({
  result,
  onClose,
  subscriptionMode,
  subscriptions,
  subscribedEventKeys,
  pendingSubscriptionKey,
  onSubscribe,
  onUnsubscribe,
}: {
  result: NoticeScanResponse;
  onClose: () => void;
  subscriptionMode: boolean;
  subscriptions: Subscription[];
  subscribedEventKeys: Set<string>;
  pendingSubscriptionKey: string | null;
  onSubscribe: (notice: PoolNotice, period: NoticeRegistrationPeriod) => void;
  onUnsubscribe: (subscription: Subscription) => void;
}) {
  const trace = result.trace ?? [];

  return (
    <div className="fixed inset-0 z-50 grid place-items-center bg-black/35 px-4" role="dialog" aria-modal="true">
      <div className="max-h-[80vh] w-full max-w-2xl overflow-hidden rounded-lg border border-[#d8ddd5] bg-white shadow-xl">
        <div className="flex items-center justify-between gap-3 border-b border-[#e3e7e1] px-5 py-4">
          <div>
            <h2 className="text-lg font-semibold">{subscriptionMode ? "모집 기간 선택" : "공지 확인 결과"}</h2>
            <p className="text-sm text-[#66746d]">
              {subscriptionMode ? "알림 받을 기간을 하나 선택하세요." : result.poolName}
            </p>
          </div>
          <button
            className="inline-flex h-9 items-center justify-center rounded-lg border border-[#cdd5cf] px-3 text-sm font-semibold text-[#31413b] transition hover:border-[#0f766e]"
            onClick={onClose}
            type="button"
          >
            닫기
          </button>
        </div>
        <div className="max-h-[64vh] divide-y divide-[#e3e7e1] overflow-auto">
          {result.notices.length === 0 ? (
            <div className="space-y-2 px-5 py-8">
              <p className="text-sm font-semibold text-[#31413b]">확인된 공지 후보가 없습니다.</p>
              <p className="text-sm text-[#66746d]">{result.message}</p>
            </div>
          ) : (
            result.notices.map((notice) => {
              const registrationPeriods =
                notice.registrationPeriods?.length || !notice.registrationStartsAt || !notice.registrationEndsAt
                  ? notice.registrationPeriods ?? []
                  : [
                      {
                        label: "대표 기간",
                        startsAt: notice.registrationStartsAt,
                        endsAt: notice.registrationEndsAt,
                        periodText: null,
                        source: "legacy",
                      },
                    ];

              return (
                <article key={notice.id} className="space-y-3 px-5 py-4">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className={`rounded-md px-2 py-1 text-xs font-semibold ${noticeStatusClass(notice.extractionStatus)}`}>
                      {noticeStatusLabel(notice.extractionStatus)}
                    </span>
                    <h3 className="font-semibold">{notice.title}</h3>
                  </div>
                  {registrationPeriods.length > 0 ? (
                    <div className="space-y-2">
                      <p className="text-sm font-semibold text-[#31413b]">모집 기간</p>
                      <div className="space-y-1.5">
                        {registrationPeriods.map((period, index) => (
                          <PeriodSelectionRow
                            key={`${period.startsAt}-${period.endsAt}-${period.label ?? index}`}
                            notice={notice}
                            period={period}
                            index={index}
                            subscribedEventKeys={subscribedEventKeys}
                            subscriptions={subscriptions}
                            pendingSubscriptionKey={pendingSubscriptionKey}
                            onSubscribe={onSubscribe}
                            onUnsubscribe={onUnsubscribe}
                          />
                        ))}
                      </div>
                    </div>
                  ) : (
                    <p className="text-sm text-[#66746d]">
                      {subscriptionMode
                        ? "구독할 수 있는 구조화 기간이 없습니다. 원문 링크를 확인하세요."
                        : "구독할 수 있는 구조화 기간이 없습니다. 원문 링크를 확인하세요."}
                    </p>
                  )}
                  {notice.reason ? <p className="text-xs text-[#7c8982]">{notice.reason}</p> : null}
                  <a
                    className="inline-flex items-center gap-1 break-all text-sm font-semibold text-[#0f766e]"
                    href={notice.url}
                    target="_blank"
                    rel="noreferrer"
                  >
                    <ExternalLink size={14} aria-hidden />
                    원문 보기
                  </a>
                </article>
              );
            })
          )}
          {trace.length > 0 ? (
            <section className="space-y-3 bg-[#f7f8f4] px-5 py-4">
              <h3 className="text-sm font-semibold text-[#31413b]">크롤링 경로</h3>
              <ol className="space-y-2 text-xs leading-5 text-[#66746d]">
                {trace.map((item, index) => (
                  <li key={`${index}-${item}`} className="break-words">
                    {index + 1}. {item}
                  </li>
                ))}
              </ol>
            </section>
          ) : null}
        </div>
      </div>
    </div>
  );
}

function PeriodSelectionRow({
  notice,
  period,
  index,
  subscriptions,
  subscribedEventKeys,
  pendingSubscriptionKey,
  onSubscribe,
  onUnsubscribe,
}: {
  notice: PoolNotice;
  period: NoticeRegistrationPeriod;
  index: number;
  subscriptions: Subscription[];
  subscribedEventKeys: Set<string>;
  pendingSubscriptionKey: string | null;
  onSubscribe: (notice: PoolNotice, period: NoticeRegistrationPeriod) => void;
  onUnsubscribe: (subscription: Subscription) => void;
}) {
  const title = buildSubscriptionTitle(notice, period);
  const key = subscriptionKey(notice.poolId, title, period.startsAt, period.endsAt);
  const subscription = subscriptions.find((item) => item.event && subscriptionKeyFromEvent(item.event) === key);
  const subscribed = subscribedEventKeys.has(key);
  const pending = pendingSubscriptionKey === key;

  return (
    <div className="grid gap-3 rounded-md border border-[#e3e7e1] bg-[#fafbf8] px-3 py-2 sm:grid-cols-[1fr_auto] sm:items-center">
      <div className="min-w-0">
        <div className="flex flex-wrap items-center gap-x-2 gap-y-1 text-sm text-[#31413b]">
          <span className="font-semibold">{period.label ?? `기간 ${index + 1}`}</span>
          <span>
            {formatDate(period.startsAt)} - {formatDate(period.endsAt)}
          </span>
        </div>
        {period.periodText ? <p className="mt-1 text-xs text-[#7c8982]">원문 {period.periodText}</p> : null}
      </div>
      <button
        className={`inline-flex h-9 items-center justify-center gap-2 rounded-lg px-3 text-sm font-semibold transition disabled:opacity-50 ${
          subscribed
            ? "border border-[#0f766e] bg-white text-[#0f766e] hover:bg-[#edf7f5]"
            : "bg-[#0f766e] text-white hover:bg-[#0b5f59]"
        }`}
        onClick={() => {
          if (subscription) {
            onUnsubscribe(subscription);
          } else {
            onSubscribe(notice, period);
          }
        }}
        disabled={pending}
        type="button"
      >
        {subscribed ? <CheckCircle2 size={16} aria-hidden /> : <Plus size={16} aria-hidden />}
        {subscribed ? "구독 해제" : "이 기간 구독"}
      </button>
    </div>
  );
}

function SubscriptionSummary({ subscription }: { subscription: Subscription }) {
  const event = subscription.event;
  const poolName = event?.poolName ?? subscription.pool.name;

  return (
    <article className="space-y-2 bg-white px-3 py-3">
      <div className="flex flex-wrap items-center gap-2">
        {event ? <StatusBadge status={event.status} /> : null}
        <p className="min-w-0 flex-1 truncate text-sm font-semibold text-[#17201d]">{poolName}</p>
      </div>
      <div className="space-y-1">
        <p className="line-clamp-2 text-sm font-medium text-[#31413b]">{event?.title ?? "기간 정보 없음"}</p>
        {event ? (
          <p className="text-xs leading-5 text-[#66746d]">
            {formatDateTime(event.registrationStartsAt)} - {formatDateTime(event.registrationEndsAt)}
          </p>
        ) : (
          <p className="text-xs leading-5 text-[#bf4b3e]">다시 공지 확인 후 기간을 선택하세요.</p>
        )}
        <p className="text-xs text-[#7c8982]">구독일 {formatDateTime(subscription.createdAt)}</p>
      </div>
    </article>
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

function getCurrentPosition() {
  return new Promise<GeolocationPosition>((resolve, reject) => {
    navigator.geolocation.getCurrentPosition(resolve, reject, {
      enableHighAccuracy: false,
      maximumAge: 60_000,
      timeout: 10_000,
    });
  });
}

function toDistanceMap(items: NearbyPool[]) {
  return items.reduce<Record<number, number>>((accumulator, item) => {
    accumulator[item.pool.id] = item.distanceMeters;
    return accumulator;
  }, {});
}

function upsertPool(items: Pool[], pool: Pool) {
  const exists = items.some((item) => item.id === pool.id);
  if (exists) {
    return items.map((item) => (item.id === pool.id ? pool : item));
  }
  return [pool, ...items].sort((a, b) => a.name.localeCompare(b.name, "ko"));
}

function buildSubscriptionTitle(notice: PoolNotice, period: NoticeRegistrationPeriod) {
  const label = period.label?.trim() || "모집 기간";
  const title = `${label} - ${notice.title}`.replace(/\s+/g, " ").trim();
  return title.length <= 120 ? title : title.slice(0, 120);
}

function subscriptionKeyFromEvent(event: RegistrationEvent) {
  return subscriptionKey(event.poolId, event.title, event.registrationStartsAt, event.registrationEndsAt);
}

function subscriptionKey(poolId: number, title: string, startsAt: string, endsAt: string) {
  return `${poolId}|${title}|${startsAt}|${endsAt}`;
}

function formatDistance(distanceMeters: number) {
  if (distanceMeters < 1000) {
    return `${Math.round(distanceMeters)}m`;
  }
  return `${(distanceMeters / 1000).toFixed(1)}km`;
}

function buildFacilitySearchQuery(address: string) {
  const tokens = address.trim().split(/\s+/).filter(Boolean);
  const regionTokens = tokens.slice(0, 3).filter((token) => !/[0-9]/.test(token));
  if (regionTokens.length === 0) {
    return "체육센터";
  }
  return `${regionTokens.join(" ")} 체육센터`;
}

function getGeolocationErrorMessage(error: unknown) {
  if (isGeolocationPositionError(error) && error.code === error.PERMISSION_DENIED) {
    return "위치 권한이 거부되어 가까운 수영장을 찾을 수 없습니다.";
  }
  if (error instanceof ApiRequestError) {
    return getErrorMessage(error, "가까운 수영장 조회에 실패했습니다.");
  }
  return "현재 위치를 가져오지 못했습니다.";
}

function isGeolocationPositionError(error: unknown): error is GeolocationPositionError {
  return typeof error === "object" && error !== null && "code" in error && "message" in error;
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

function noticeStatusLabel(status: NoticeScanResponse["notices"][number]["extractionStatus"]) {
  const labels = {
    EXTRACTED: "모집 기간 추출",
    LINK_ONLY: "링크 확인 필요",
    FAILED: "수집 실패",
  };
  return labels[status];
}

function noticeStatusClass(status: NoticeScanResponse["notices"][number]["extractionStatus"]) {
  const classes = {
    EXTRACTED: "bg-[#edf7f5] text-[#0f766e]",
    LINK_ONLY: "bg-[#fff2e2] text-[#946123]",
    FAILED: "bg-[#fff0ed] text-[#bf4b3e]",
  };
  return classes[status];
}
