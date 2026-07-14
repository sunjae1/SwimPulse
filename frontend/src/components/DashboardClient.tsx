"use client";

import {
  Bell,
  CalendarClock,
  CheckCircle2,
  CircleAlert,
  ExternalLink,
  FileSearch,
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
  authUrl,
  createEvent,
  createPoolFromLocationCandidate,
  createSubscription,
  deleteSubscription,
  geocodeLocation,
  getCurrentDeviceRegistration,
  getEvents,
  getMe,
  getNearbyPools,
  getNotification,
  getNotificationPage,
  getSubscriptions,
  getPoolLocationCandidates,
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
  NotificationPage,
  NoticeScanResponse,
  PoolNotice,
  Pool,
  PoolLocationCandidate,
  RegistrationEvent,
  Subscription,
} from "@/lib/types";

type DashboardClientProps = {
  initialData: DashboardInitialData;
  initialNotificationId?: string | null;
  initialLoginSuccess?: boolean;
  initialLoginError?: boolean;
};

type EventForm = {
  poolId: string;
  title: string;
  startsAt: string;
  endsAt: string;
};

type PastPeriodPrompt = {
  notice: PoolNotice;
  originalPeriod: NoticeRegistrationPeriod;
  shiftedPeriod: NoticeRegistrationPeriod;
};

const statusOptions: Array<EventStatus | "ALL"> = ["ALL", "UPCOMING", "OPEN", "CLOSED"];
const NOTICE_AUTO_DISMISS_MS = 5000;

export function DashboardClient({
  initialData,
  initialNotificationId = null,
  initialLoginSuccess = false,
  initialLoginError = false,
}: DashboardClientProps) {
  const [apiReachable, setApiReachable] = useState(initialData.apiReachable);
  const [allPools] = useState<Pool[]>(initialData.pools);
  const [pools, setPools] = useState<Pool[]>(initialData.pools);
  const [nearbyMode, setNearbyMode] = useState(false);
  const [nearbyDistances, setNearbyDistances] = useState<Record<number, number>>({});
  const [currentLocation, setCurrentLocation] = useState<{ latitude: number; longitude: number } | null>(null);
  const [nearbyOriginLabel, setNearbyOriginLabel] = useState<string | null>(null);
  const [locationQuery, setLocationQuery] = useState("");
  const [locationCandidates, setLocationCandidates] = useState<LocationSearchCandidate[]>([]);
  const [facilityCandidates, setFacilityCandidates] = useState<PoolLocationCandidate[]>([]);
  const [facilityCandidatesOpen, setFacilityCandidatesOpen] = useState(false);
  const [locationSearchBusy, setLocationSearchBusy] = useState(false);
  const [facilityCandidatesBusyLabel, setFacilityCandidatesBusyLabel] = useState<string | null>(null);
  const [candidateToAdd, setCandidateToAdd] = useState<PoolLocationCandidate | null>(null);
  const [noticeScanResult, setNoticeScanResult] = useState<NoticeScanResponse | null>(null);
  const [events, setEvents] = useState<RegistrationEvent[]>(initialData.events);
  const [user, setUser] = useState<AppUser | null>(null);
  const [currentDeviceRegistered, setCurrentDeviceRegistered] = useState(false);
  const [subscriptions, setSubscriptions] = useState<Subscription[]>([]);
  const [notifications, setNotifications] = useState<InAppNotification[]>([]);
  const [notificationTotalCount, setNotificationTotalCount] = useState(0);
  const [unreadNotificationTotalCount, setUnreadNotificationTotalCount] = useState(0);
  const [notificationsLoaded, setNotificationsLoaded] = useState(false);
  const [statusFilter, setStatusFilter] = useState<EventStatus | "ALL">("ALL");
  const [noticeSubscriptionMode, setNoticeSubscriptionMode] = useState(false);
  const [pendingSubscriptionKey, setPendingSubscriptionKey] = useState<string | null>(null);
  const [pastPeriodPrompt, setPastPeriodPrompt] = useState<PastPeriodPrompt | null>(null);
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [pendingNoticeScanPoolIds, setPendingNoticeScanPoolIds] = useState<number[]>([]);
  const [pushGuideOpen, setPushGuideOpen] = useState(false);
  const [pushNotificationModal, setPushNotificationModal] = useState<InAppNotification | null>(null);
  const [pushNotificationClosing, setPushNotificationClosing] = useState(false);
  const [pendingNotificationLaunchId, setPendingNotificationLaunchId] = useState<string | null>(initialNotificationId);
  const [pendingLoginSuccessNotice, setPendingLoginSuccessNotice] = useState(initialLoginSuccess);
  const [pendingLoginErrorNotice, setPendingLoginErrorNotice] = useState(initialLoginError);
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
  const unreadNotifications = unreadNotificationTotalCount;

  function applyNotificationPage(page: NotificationPage) {
    setNotifications(page.content);
    setNotificationTotalCount(page.totalElements);
    setUnreadNotificationTotalCount(page.unreadElements);
    setNotificationsLoaded(true);
  }

  function clearSearchParam(key: string) {
    const nextParams = new URLSearchParams(window.location.search);
    nextParams.delete(key);
    const nextQuery = nextParams.toString();
    window.history.replaceState(null, "", nextQuery ? `${window.location.pathname}?${nextQuery}` : window.location.pathname);
  }

  useEffect(() => {
    if (!initialNotificationId) {
      return;
    }
    const timeoutId = window.setTimeout(() => {
      setPendingNotificationLaunchId(initialNotificationId);
    }, 0);
    return () => {
      window.clearTimeout(timeoutId);
    };
  }, [initialNotificationId]);

  useEffect(() => {
    let cancelled = false;

    async function loadUserData() {
      try {
        const currentUser = await getMe();
        if (cancelled) {
          return;
        }
        setUser(currentUser);
        const [freshSubscriptions, freshNotificationPage, freshEvents] = await Promise.all([
          getSubscriptions(),
          getNotificationPage(),
          getEvents(),
        ]);
        const currentDevice = await getCurrentDeviceRegistration(getOrCreateDeviceId());
        if (!cancelled) {
          setSubscriptions(freshSubscriptions);
          applyNotificationPage(freshNotificationPage);
          setEvents(freshEvents);
          setCurrentDeviceRegistered(currentDevice.registered);
          setApiReachable(true);
        }
      } catch (error) {
        if (!cancelled) {
          if (error instanceof ApiRequestError && error.status === 401) {
            setUser(null);
            setCurrentDeviceRegistered(false);
            setSubscriptions([]);
            setNotifications([]);
            setNotificationTotalCount(0);
            setUnreadNotificationTotalCount(0);
            setNotificationsLoaded(true);
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
      const [freshSubscriptions, freshNotificationPage, freshEvents] = await Promise.all([
        getSubscriptions(),
        getNotificationPage(),
        getEvents(),
      ]);
      const currentDevice = await getCurrentDeviceRegistration(getOrCreateDeviceId());
      setSubscriptions(freshSubscriptions);
      applyNotificationPage(freshNotificationPage);
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
    setFacilityCandidatesOpen(false);
    setFacilityCandidatesBusyLabel(null);
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
      setFacilityCandidatesOpen(false);
      setFacilityCandidatesBusyLabel(null);
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
    setFacilityCandidates([]);
    setFacilityCandidatesOpen(false);
    setFacilityCandidatesBusyLabel(candidate.title);
    setNotice(null);
    try {
      const geocoded = await geocodeLocation(address);
      const selectedLocation = {
        latitude: geocoded.latitude,
        longitude: geocoded.longitude,
      };
      const [nearby, facilities] = await Promise.all([
        getNearbyPools(geocoded.latitude, geocoded.longitude, 10),
        getPoolLocationCandidates(geocoded.latitude, geocoded.longitude, 5000, "체육센터", 10),
      ]);
      setLocationQuery(candidate.title);
      setLocationCandidates([]);
      setFacilityCandidates(facilities.filter((item) => !item.alreadyExists));
      setFacilityCandidatesOpen(false);
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
      setFacilityCandidatesBusyLabel(null);
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
      setFacilityCandidates((items) => items.filter((item) => item.title !== candidateToAdd.title));
      setNotice(`${created.title} 시설 추가 요청을 관리자에게 보냈습니다. 승인 후 수영장 목록에 반영됩니다.`);
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
    if (pendingNoticeScanPoolIds.includes(pool.id)) {
      setNotice(
        subscriptionMode
          ? `${pool.name} 모집 기간을 이미 확인 중입니다. 완료되면 같은 결과를 바로 보여드립니다.`
          : `${pool.name} 공지를 이미 확인 중입니다. 완료되면 같은 결과를 바로 보여드립니다.`,
      );
      return;
    }

    setPendingNoticeScanPoolIds((items) => [...items, pool.id]);
    setNotice(
      subscriptionMode
        ? `${pool.name} 모집 기간을 확인 중입니다. 다른 사용자가 먼저 같은 수영장을 확인 중이면 완료 후 결과를 함께 보여드립니다.`
        : `${pool.name} 공지를 확인 중입니다. 다른 사용자가 먼저 같은 수영장을 확인 중이면 완료 후 결과를 함께 보여드립니다.`,
    );
    try {
      const result = await scanPoolNotices(pool.id);
      setNoticeScanResult(result);
      setNoticeSubscriptionMode(subscriptionMode);
      if (result.latestCheckFailed) {
        setNotice(result.message);
      } else if (subscriptionMode) {
        setNotice(
          result.sharedResult
            ? "다른 사용자가 먼저 확인한 최신 공지 결과를 함께 불러왔습니다. 구독할 모집 기간을 선택하세요."
            : "구독할 모집 기간을 선택하세요.",
        );
      } else {
        setNotice(
          result.sharedResult
            ? `${pool.name} 공지를 다른 사용자 요청 결과와 함께 바로 불러왔습니다.`
            : `${pool.name} 공지를 확인했습니다.`,
        );
      }
    } catch (error) {
      setNotice(getErrorMessage(error, "공지 확인에 실패했습니다."));
    } finally {
      setPendingNoticeScanPoolIds((items) => items.filter((item) => item !== pool.id));
    }
  }

  function loginWithGoogle() {
    window.location.href = authUrl("/oauth2/authorization/google");
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
      setNotificationTotalCount(0);
      setUnreadNotificationTotalCount(0);
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
    if (isPastMonthPeriod(period)) {
      setNotice("지난 달 모집 기간입니다.");
      setPastPeriodPrompt({
        notice,
        originalPeriod: period,
        shiftedPeriod: shiftPeriodToCurrentMonth(period),
      });
      return;
    }
    await createNoticePeriodSubscription(notice, period, period.id, buildSubscriptionTitle(notice, period));
  }

  async function confirmCurrentMonthSubscription() {
    if (!pastPeriodPrompt) {
      return;
    }
    const { notice: targetNotice, shiftedPeriod } = pastPeriodPrompt;
    const subscribed = await createNoticePeriodSubscription(
      targetNotice,
      shiftedPeriod,
      null,
      buildEstimatedSubscriptionTitle(targetNotice, shiftedPeriod),
    );
    if (subscribed) {
      setPastPeriodPrompt(null);
    }
  }

  async function createNoticePeriodSubscription(
    targetNotice: PoolNotice,
    targetPeriod: NoticeRegistrationPeriod,
    noticeRegistrationPeriodId: number | null,
    title: string,
  ) {
    const key = subscriptionKey(targetNotice.poolId, title, targetPeriod.startsAt, targetPeriod.endsAt);
    setPendingSubscriptionKey(key);
    setNotice(null);
    try {
      await createSubscription({
        poolId: targetNotice.poolId,
        title,
        registrationStartsAt: targetPeriod.startsAt,
        registrationEndsAt: targetPeriod.endsAt,
        noticeRegistrationPeriodId,
        noticeUrl: targetNotice.url,
      });
      const [freshSubscriptions, freshEvents] = await Promise.all([getSubscriptions(), getEvents()]);
      setSubscriptions(freshSubscriptions);
      setEvents(freshEvents);
      setNotice(
        noticeRegistrationPeriodId === null
          ? "이번 달 같은 날짜를 예상 모집 기간으로 구독했습니다."
          : "선택한 모집 기간 알림을 구독했습니다.",
      );
      return true;
    } catch (error) {
      setNotice(getErrorMessage(error, "구독 요청을 처리하지 못했습니다."));
      return false;
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
      await registerCurrentDeviceForPush(user);
      const queued = await sendTestNotification();
      setNotifications((items) => [queued, ...items].slice(0, 20));
      setNotificationTotalCount((count) => count + 1);
      setUnreadNotificationTotalCount((count) => count + 1);
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
      const wasUnread = notifications.some((item) => item.id === updated.id && !item.readAt);
      setNotifications((items) => items.map((item) => (item.id === updated.id ? updated : item)));
      if (wasUnread && updated.readAt) {
        setUnreadNotificationTotalCount((count) => Math.max(0, count - 1));
      }
    } catch {
      setNotice("알림 읽음 처리를 완료하지 못했습니다.");
    }
  }

  async function closePushNotificationModal() {
    const target = pushNotificationModal;
    setPushNotificationClosing(true);

    try {
      if (target && !target.readAt && user) {
        const updated = await markNotificationRead(target.id);
        const wasUnread = notifications.some((item) => item.id === updated.id && !item.readAt);
        setNotifications((items) => items.map((item) => (item.id === updated.id ? updated : item)));
        if (wasUnread && updated.readAt) {
          setUnreadNotificationTotalCount((count) => Math.max(0, count - 1));
        }
      }
    } catch {
      setNotice("알림 읽음 처리를 완료하지 못했습니다.");
    } finally {
      setPushNotificationModal(null);
      setPushNotificationClosing(false);
      if (pendingNotificationLaunchId) {
        clearSearchParam("notificationId");
      }
      setPendingNotificationLaunchId(null);
    }
  }

  useEffect(() => {
    if (!pendingLoginSuccessNotice || !user) {
      return;
    }
    clearSearchParam("login");
    const timeoutId = window.setTimeout(() => {
      setNotice(`${user.displayName} 계정으로 로그인됐습니다.`);
      setPendingLoginSuccessNotice(false);
    }, 0);
    return () => {
      window.clearTimeout(timeoutId);
    };
  }, [pendingLoginSuccessNotice, user]);

  useEffect(() => {
    if (!pendingLoginErrorNotice) {
      return;
    }
    clearSearchParam("login");
    const timeoutId = window.setTimeout(() => {
      setNotice("Google 로그인에 실패했습니다. ngrok 주소로 접속했는지 확인한 뒤 다시 시도하세요.");
      setPendingLoginErrorNotice(false);
    }, 0);
    return () => {
      window.clearTimeout(timeoutId);
    };
  }, [pendingLoginErrorNotice]);

  useEffect(() => {
    if (!notice) {
      return;
    }

    const timeoutId = window.setTimeout(() => {
      setNotice((current) => (current === notice ? null : current));
    }, NOTICE_AUTO_DISMISS_MS);

    return () => {
      window.clearTimeout(timeoutId);
    };
  }, [notice]);

  useEffect(() => {
    if (!pendingNotificationLaunchId) {
      return;
    }
    if (!notificationsLoaded) {
      return;
    }

    const notificationId = Number(pendingNotificationLaunchId);
    if (!Number.isInteger(notificationId) || notificationId <= 0) {
      clearSearchParam("notificationId");
      window.setTimeout(() => {
        setPendingNotificationLaunchId(null);
      }, 0);
      return;
    }

    if (!user) {
      clearSearchParam("notificationId");
      window.setTimeout(() => {
        setNotice("로그인 후 알림 내용을 확인할 수 있습니다.");
        setPendingNotificationLaunchId(null);
      }, 0);
      return;
    }

    let cancelled = false;

    async function openNotificationFromLaunch() {
      const target = notifications.find((item) => item.id === notificationId);
      if (target) {
        window.setTimeout(() => {
          if (!cancelled) {
            setPushNotificationModal(target);
          }
        }, 0);
        return;
      }

      try {
        const fetched = await getNotification(notificationId);
        if (cancelled) {
          return;
        }
        setNotifications((items) => (items.some((item) => item.id === fetched.id) ? items : [fetched, ...items]));
        setPushNotificationModal(fetched);
      } catch {
        if (cancelled) {
          return;
        }
        clearSearchParam("notificationId");
        window.setTimeout(() => {
          setNotice("해당 알림을 찾지 못했습니다.");
          setPendingNotificationLaunchId(null);
        }, 0);
      }
    }

    openNotificationFromLaunch();

    return () => {
      cancelled = true;
    };
  }, [notifications, notificationsLoaded, pendingNotificationLaunchId, user]);

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
    <main className="min-h-screen bg-[#edf7ff] text-[#102033]">
      <div className="sticky top-0 z-30 border-b border-[#c8def0] bg-white/92 shadow-sm backdrop-blur">
        <div className="mx-auto flex max-w-7xl flex-col gap-4 px-5 py-4 lg:flex-row lg:items-center lg:justify-between">
          <div className="flex flex-col gap-3 md:flex-row md:items-center md:gap-5">
            <div className="flex items-center gap-3">
              <div className="grid size-10 place-items-center rounded-lg bg-[#0369a1] text-white shadow-sm swim-pulse-dot">
                <Waves size={22} aria-hidden />
              </div>
              <div>
                <p className="text-sm font-semibold text-[#0369a1]">SwimPulse</p>
                <h1 className="text-xl font-semibold text-[#102033]">수영장 등록 타이밍 알림</h1>
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
              className="swim-action grid size-10 place-items-center rounded-lg border border-[#c8def0] bg-white text-[#28516f] hover:border-[#0284c7] hover:text-[#0369a1] disabled:opacity-50"
              onClick={refreshAll}
              disabled={busy || !user}
              title="새로고침"
              type="button"
            >
              <RefreshCw size={18} aria-hidden />
            </button>
            <button
              className="swim-action grid size-10 place-items-center rounded-lg bg-[#0284c7] text-white hover:bg-[#0369a1] disabled:opacity-50"
              onClick={enablePush}
              disabled={busy || !user}
              title="웹 푸시 등록"
              type="button"
            >
              <Bell size={18} aria-hidden />
            </button>
            {user ? (
              <button
                className="swim-action grid size-10 place-items-center rounded-lg border border-[#c8def0] bg-white text-[#28516f] hover:border-[#ef4444] hover:text-[#dc2626] disabled:opacity-50"
                onClick={logoutUser}
                disabled={busy}
                title="로그아웃"
                type="button"
              >
                <LogOut size={18} aria-hidden />
              </button>
            ) : (
              <button
                className="swim-action inline-flex h-10 items-center justify-center gap-2 rounded-lg bg-[#075985] px-4 text-sm font-semibold text-white hover:bg-[#0c4a6e] disabled:opacity-50"
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

      <WelcomeHero
        user={user}
        upcomingEvents={upcomingEvents}
        unreadNotifications={unreadNotifications}
        onLogin={loginWithGoogle}
        onEnablePush={enablePush}
        busy={busy}
      />
      <ServiceIntroPages user={user} onLogin={loginWithGoogle} busy={busy} />

      <div id="pool-workspace" className="swim-workspace-shell border-t border-[#c8def0]">
      <div className="mx-auto grid max-w-7xl grid-cols-1 gap-5 px-5 py-8 lg:grid-cols-[1fr_360px]">
        <section className="swim-rise space-y-5">
          <div className="grid gap-3 sm:grid-cols-3">
            <Metric icon={CalendarClock} label="예정 이벤트" value={upcomingEvents.toString()} tone="blue" />
            <Metric icon={TimerReset} label="진행 중" value={openEvents.toString()} tone="amber" />
            <Metric icon={Bell} label="안 읽은 알림" value={unreadNotifications.toString()} tone="cyan" />
          </div>

          {!user ? (
            <div className="swim-card-motion flex flex-col gap-3 rounded-lg border border-[#c8def0] bg-white px-4 py-4 shadow-sm sm:flex-row sm:items-center sm:justify-between">
              <div>
                <h2 className="text-lg font-semibold text-[#102033]">Google 로그인 필요</h2>
                <p className="text-sm text-[#4b6f8b]">구독, 앱 내 알림, 웹 푸시는 로그인한 사용자 기준으로 저장됩니다.</p>
              </div>
              <button
                className="swim-action inline-flex h-10 items-center justify-center gap-2 rounded-lg bg-[#075985] px-4 text-sm font-semibold text-white hover:bg-[#0c4a6e]"
                onClick={loginWithGoogle}
                type="button"
              >
                <LogIn size={17} aria-hidden />
                Google 로그인
              </button>
            </div>
          ) : null}

          <section className="swim-card-motion rounded-lg border border-[#c8def0] bg-white shadow-sm">
            <div className="flex flex-col gap-3 border-b border-[#d9eaf6] px-4 py-4 sm:flex-row sm:items-center sm:justify-between">
              <div>
                <h2 className="text-lg font-semibold text-[#102033]">수영장 목록</h2>
                <p className="text-sm text-[#4b6f8b]">
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
                      ? "bg-[#075985] text-white"
                      : "border border-[#c8def0] bg-white text-[#28516f] hover:border-[#0284c7]"
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
                    className="swim-action inline-flex h-9 items-center justify-center gap-2 rounded-lg border border-[#c8def0] bg-white px-3 text-sm font-medium text-[#28516f] hover:border-[#0284c7]"
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
                        ? "bg-[#075985] text-white"
                        : "border border-[#c8def0] bg-white text-[#28516f] hover:border-[#0284c7]"
                    }`}
                    onClick={() => setStatusFilter(status)}
                    type="button"
                  >
                    {status === "ALL" ? "전체" : eventStatusLabel(status)}
                  </button>
                ))}
              </div>
            </div>

            <div className="border-b border-[#d9eaf6] px-4 py-4">
              <form className="flex flex-col gap-2 sm:flex-row" onSubmit={submitLocationSearch}>
                <label className="sr-only" htmlFor="location-search">
                  위치 검색
                </label>
                <input
                  id="location-search"
                  className="h-10 min-w-0 flex-1 rounded-lg border border-[#b8d7ec] px-3 text-sm outline-none transition focus:border-[#0284c7] focus:ring-3 focus:ring-[#bae6fd]"
                  value={locationQuery}
                  onChange={(event) => {
                    setLocationQuery(event.target.value);
                    setLocationCandidates([]);
                    setFacilityCandidates([]);
                    setFacilityCandidatesOpen(false);
                    setFacilityCandidatesBusyLabel(null);
                  }}
                  placeholder="화성남부국민체육센터"
                />
                <button
                  className="swim-action inline-flex h-10 items-center justify-center gap-2 rounded-lg bg-[#075985] px-4 text-sm font-semibold text-white hover:bg-[#0c4a6e] disabled:opacity-50"
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
                        className="swim-row-motion rounded-lg border border-[#c8def0] bg-white px-3 py-3"
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
              {facilityCandidatesBusyLabel ? (
                <div className="mt-4 flex items-center gap-2 rounded-lg border border-[#c8def0] bg-[#f6fbff] px-3 py-3 text-sm font-semibold text-[#28516f]">
                  <RefreshCw size={16} className="animate-spin text-[#0284c7]" aria-hidden />
                  {facilityCandidatesBusyLabel} 기준 수영장 후보 찾는 중
                </div>
              ) : null}
              {facilityCandidates.length > 0 ? (
                <div className="mt-4 space-y-3">
                  <button
                    className="swim-action inline-flex min-h-10 w-full items-center justify-center gap-2 rounded-lg border border-[#b8d7ec] bg-white px-3 text-sm font-semibold text-[#28516f] hover:border-[#0284c7]"
                    onClick={() => setFacilityCandidatesOpen((open) => !open)}
                    type="button"
                  >
                    <List size={16} aria-hidden />
                    {(nearbyOriginLabel ?? "선택 위치")} 기준 수영장 후보 {facilityCandidatesOpen ? "숨기기" : "보기"}
                  </button>
                  {facilityCandidatesOpen ? (
                    <div className="space-y-3">
                      <p className="text-xs text-[#66746d]">DB에 아직 없는 체육센터 후보입니다.</p>
                      <div className="grid gap-2">
                        {facilityCandidates.map((candidate, index) => {
                          const address = candidate.roadAddress ?? candidate.address ?? "주소 없음";
                          return (
                            <div
                              key={`facility-${candidate.title}-${address}-${index}`}
                              className="swim-row-motion grid gap-3 rounded-lg border border-[#c8def0] bg-[#f6fbff] px-3 py-3 sm:grid-cols-[1fr_auto]"
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
                                className="swim-action inline-flex h-9 items-center justify-center gap-2 rounded-lg bg-[#0284c7] px-3 text-sm font-semibold text-white hover:bg-[#0369a1] disabled:opacity-50"
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
              ) : null}
            </div>

            <div className="grid gap-0 divide-y divide-[#d9eaf6]">
              {pools.map((pool) => (
                <article key={pool.id} className="swim-row-motion grid gap-4 px-4 py-4 md:grid-cols-[112px_1fr_auto]">
                  <PoolImage pool={pool} />
                  <div className="space-y-2">
                    <div className="flex flex-wrap items-center gap-2">
                      <h3 className="font-semibold">{pool.name}</h3>
                      {pool.district ? (
                        <span className="rounded-md bg-[#e0f2fe] px-2 py-1 text-xs font-semibold text-[#0369a1]">
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
                      <p className="flex items-center gap-1 text-sm text-[#4b6f8b]">
                        <MapPin size={15} aria-hidden />
                        {pool.roadNameAddress ?? pool.lotNumberAddress}
                      </p>
                    ) : null}
                    <div className="flex flex-wrap gap-2 text-xs font-medium text-[#4b6f8b]">
                      {pool.indoorOutdoorTypeName ? <span>{pool.indoorOutdoorTypeName}</span> : null}
                      {pool.standardPoolLengthMeters ? <span>{pool.standardPoolLengthMeters}m</span> : null}
                      {pool.standardPoolLaneCount ? <span>{pool.standardPoolLaneCount}레인</span> : null}
                      {pool.completionYear ? <span>{pool.completionYear}년 준공</span> : null}
                    </div>
                    {pool.description ? <p className="text-sm text-[#355b78]">{pool.description}</p> : null}
                    <div className="flex flex-wrap gap-2">
                      {pool.homepageUrl ? (
                        <a
                          className="swim-action inline-flex h-8 items-center gap-1 rounded-lg border border-[#c8def0] px-3 text-xs font-semibold text-[#28516f] hover:border-[#0284c7] hover:text-[#0369a1]"
                          href={pool.homepageUrl}
                          target="_blank"
                          rel="noreferrer"
                        >
                          <ExternalLink size={14} aria-hidden />
                          홈페이지
                        </a>
                      ) : null}
                      <button
                        className="swim-action inline-flex h-8 items-center gap-1 rounded-lg border border-[#c8def0] px-3 text-xs font-semibold text-[#28516f] hover:border-[#0284c7] hover:text-[#0369a1] disabled:opacity-50"
                        onClick={() => scanNotices(pool)}
                        disabled={!user || !pool.homepageUrl}
                        title={!pool.homepageUrl ? "홈페이지를 찾을 수 없습니다." : "공지 확인"}
                        type="button"
                      >
                        <FileSearch size={14} aria-hidden />
                        {pendingNoticeScanPoolIds.includes(pool.id) ? "공지 확인 중..." : "공지 확인"}
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
                      className={`swim-action inline-flex h-10 items-center justify-center gap-2 rounded-lg px-4 text-sm font-semibold disabled:opacity-50 ${
                        subscribedPeriodPoolIds.has(pool.id)
                          ? "border border-[#0284c7] bg-white text-[#0369a1] hover:bg-[#e0f2fe]"
                          : "bg-[#0284c7] text-white hover:bg-[#0369a1]"
                      }`}
                      onClick={() => scanNotices(pool, true)}
                      disabled={!user || !pool.homepageUrl}
                      title={!pool.homepageUrl ? "홈페이지를 찾을 수 없습니다." : "알림 구독"}
                      type="button"
                    >
                      {subscribedPeriodPoolIds.has(pool.id) ? <CheckCircle2 size={17} aria-hidden /> : <Plus size={17} aria-hidden />}
                      {pendingNoticeScanPoolIds.includes(pool.id)
                        ? "결과 기다리는 중..."
                        : subscribedPeriodPoolIds.has(pool.id)
                          ? "기간 구독 중"
                          : "알림 구독"}
                    </button>
                  </div>
                </article>
              ))}
            </div>
          </section>

          <section className="swim-card-motion rounded-lg border border-[#c8def0] bg-white shadow-sm">
            <div className="border-b border-[#d9eaf6] px-4 py-4">
              <h2 className="text-lg font-semibold">접수 이벤트</h2>
            </div>
            <div className="divide-y divide-[#d9eaf6]">
              {filteredEvents.map((event) => (
                <article key={event.id} className="swim-row-motion grid gap-3 px-4 py-4 md:grid-cols-[1fr_auto]">
                  <div className="space-y-2">
                    <div className="flex flex-wrap items-center gap-2">
                      <StatusBadge status={event.status} />
                      <h3 className="font-semibold">{event.title}</h3>
                    </div>
                    <p className="text-sm text-[#4b6f8b]">{event.poolName}</p>
                    <p className="text-sm text-[#28516f]">
                      {formatDateTime(event.registrationStartsAt)} - {formatDateTime(event.registrationEndsAt)}
                    </p>
                  </div>
                  <EventTimeLeft event={event} />
                </article>
              ))}
            </div>
          </section>
        </section>

        <aside className="swim-rise swim-rise-delay-1 space-y-5">
          <AccountPanel
            user={user}
            subscriptions={subscriptions}
            currentDeviceRegistered={currentDeviceRegistered}
            onLogin={loginWithGoogle}
            onLogout={logoutUser}
            onUnregisterDevice={unregisterPushForCurrentDevice}
            busy={busy}
          />

          <section className="swim-card-motion rounded-lg border border-[#c8def0] bg-white shadow-sm">
            <div className="border-b border-[#d9eaf6] px-4 py-4">
              <h2 className="text-lg font-semibold">수동 이벤트 등록</h2>
            </div>
            <form className="space-y-4 px-4 py-4" onSubmit={submitEvent}>
              <label className="grid gap-1 text-sm font-medium">
                수영장
                <select
                  className="h-11 rounded-lg border border-[#b8d7ec] bg-white px-3 text-sm outline-none transition focus:border-[#0284c7] focus:ring-3 focus:ring-[#bae6fd]"
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
                  className="h-11 rounded-lg border border-[#b8d7ec] px-3 text-sm outline-none transition focus:border-[#0284c7] focus:ring-3 focus:ring-[#bae6fd]"
                  value={form.title}
                  onChange={(event) => setForm((current) => ({ ...current, title: event.target.value }))}
                  required
                />
              </label>
              <label className="grid gap-1 text-sm font-medium">
                접수 시작
                <input
                  className="h-11 rounded-lg border border-[#b8d7ec] px-3 text-sm outline-none transition focus:border-[#0284c7] focus:ring-3 focus:ring-[#bae6fd]"
                  type="datetime-local"
                  value={form.startsAt}
                  onChange={(event) => setForm((current) => ({ ...current, startsAt: event.target.value }))}
                  required
                />
              </label>
              <label className="grid gap-1 text-sm font-medium">
                접수 종료
                <input
                  className="h-11 rounded-lg border border-[#b8d7ec] px-3 text-sm outline-none transition focus:border-[#0284c7] focus:ring-3 focus:ring-[#bae6fd]"
                  type="datetime-local"
                  value={form.endsAt}
                  onChange={(event) => setForm((current) => ({ ...current, endsAt: event.target.value }))}
                  required
                />
              </label>
              <button
                className="swim-action inline-flex h-11 w-full items-center justify-center gap-2 rounded-lg bg-[#075985] px-4 text-sm font-semibold text-white hover:bg-[#0c4a6e] disabled:opacity-50"
                disabled={busy || !user}
                type="submit"
              >
                <Send size={17} aria-hidden />
                등록
              </button>
            </form>
          </section>

          <section className="swim-card-motion rounded-lg border border-[#c8def0] bg-white shadow-sm">
            <div className="flex items-center justify-between gap-3 border-b border-[#d9eaf6] px-4 py-4">
              <h2 className="text-lg font-semibold">앱 내 알림</h2>
              <div className="flex items-center gap-2">
                <span className="rounded-md bg-[#fff2e2] px-2 py-1 text-xs font-semibold text-[#946123]">
                  {notificationTotalCount}
                </span>
                <button
                  className="swim-action grid size-9 place-items-center rounded-lg border border-[#b8d7ec] bg-white text-[#28516f] hover:border-[#0284c7] hover:text-[#0369a1] disabled:opacity-50"
                  onClick={sendPushTest}
                  disabled={busy || !user}
                  title="테스트 푸시 전송"
                  type="button"
                >
                  <Send size={16} aria-hidden />
                </button>
              </div>
            </div>
            <div className="max-h-[460px] divide-y divide-[#d9eaf6] overflow-auto">
              {notifications.length === 0 ? (
                <p className="px-4 py-8 text-sm text-[#66746d]">아직 저장된 알림이 없습니다.</p>
              ) : (
                <>
                  {notifications.map((item) => (
                    <button
                      key={item.id}
                      className="swim-row-motion block w-full px-4 py-4 text-left"
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
                      <p className="text-sm text-[#355b78]">{item.message}</p>
                      <p className="mt-2 text-xs text-[#6d879b]">{formatDateTime(item.createdAt)}</p>
                    </button>
                  ))}
                  {notificationTotalCount > notifications.length ? (
                    <p className="px-4 py-3 text-sm text-[#4b6f8b]">
                      최근 {notifications.length}개만 표시 중입니다. 전체 알림 수는 {notificationTotalCount}개입니다.
                    </p>
                  ) : null}
                </>
              )}
            </div>
          </section>
        </aside>
      </div>
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
          onResultUpdate={setNoticeScanResult}
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
      {pastPeriodPrompt ? (
        <PastPeriodSubscriptionModal
          prompt={pastPeriodPrompt}
          busy={
            pendingSubscriptionKey ===
            subscriptionKey(
              pastPeriodPrompt.notice.poolId,
              buildEstimatedSubscriptionTitle(pastPeriodPrompt.notice, pastPeriodPrompt.shiftedPeriod),
              pastPeriodPrompt.shiftedPeriod.startsAt,
              pastPeriodPrompt.shiftedPeriod.endsAt,
            )
          }
          onConfirm={confirmCurrentMonthSubscription}
          onClose={() => setPastPeriodPrompt(null)}
        />
      ) : null}
      {pushNotificationModal ? (
        <PushNotificationModal
          notification={pushNotificationModal}
          busy={pushNotificationClosing}
          onClose={closePushNotificationModal}
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

function WelcomeHero({
  user,
  upcomingEvents,
  unreadNotifications,
  onLogin,
  onEnablePush,
  busy,
}: {
  user: AppUser | null;
  upcomingEvents: number;
  unreadNotifications: number;
  onLogin: () => void;
  onEnablePush: () => void;
  busy: boolean;
}) {
  return (
    <section className="swim-animated-surface overflow-hidden border-b border-[#b8d7ec]">
      <div className="mx-auto grid max-w-7xl gap-8 px-5 py-8 lg:grid-cols-[1.08fr_0.92fr] lg:items-center lg:py-14">
        <div className="swim-rise space-y-6">
          <div className="inline-flex items-center gap-2 rounded-full border border-[#9fc9e5] bg-white/85 px-3 py-1 text-xs font-bold uppercase text-[#0369a1]">
            <span className="size-2 rounded-full bg-[#0284c7]" aria-hidden />
            SwimPulse Alert
          </div>
          <div className="max-w-3xl space-y-4">
            <h2 className="text-3xl font-black text-[#102033] sm:text-5xl">
              수영장 모집 공지,
              <span className="block text-[#0369a1]">접수 시작 전에<br></br> 먼저 챙겨드릴게요.</span>
            </h2>
            <p className="max-w-2xl text-base leading-7 text-[#355b78] sm:text-lg">
              공공 수영장 모집 공지는 시설마다 올라오는 위치와 형식이 다릅니다.
              SwimPulse는 공식 홈페이지 공지를 확인하고, 모집 기간을 정리해 알림으로 이어줍니다.
            </p>
          </div>
          <div className="flex flex-col gap-2 sm:flex-row">
            {user ? (
              <button
                className="swim-action inline-flex h-12 items-center justify-center gap-2 rounded-xl bg-[#075985] px-5 text-sm font-bold text-white hover:bg-[#0c4a6e] disabled:opacity-50"
                onClick={onEnablePush}
                disabled={busy}
                type="button"
              >
                <Bell size={17} aria-hidden />
                푸시 알림 켜기
              </button>
            ) : (
              <button
                className="swim-action inline-flex h-12 items-center justify-center gap-2 rounded-xl bg-[#075985] px-5 text-sm font-bold text-white hover:bg-[#0c4a6e] disabled:opacity-50"
                onClick={onLogin}
                disabled={busy}
                type="button"
              >
                <LogIn size={17} aria-hidden />
                Google로 시작하기
              </button>
            )}
            <a
              className="swim-action inline-flex h-12 items-center justify-center gap-2 rounded-xl border border-[#9fc9e5] bg-white/85 px-5 text-sm font-bold text-[#28516f] hover:border-[#0284c7] hover:text-[#0369a1]"
              href="#service-flow"
            >
              <Search size={17} aria-hidden />
              서비스 둘러보기
            </a>
          </div>
        </div>

        <div className="swim-rise swim-rise-delay-1 swim-card-motion rounded-3xl border border-white/75 bg-white/80 p-4 shadow-[0_24px_70px_rgba(3,105,161,0.18)]">
          <div
            className="swim-hero-visual mb-4 h-48 rounded-2xl bg-[#bde8ff] bg-cover bg-center"
            aria-label="SwimPulse 수영장 알림 대표 이미지"
            role="img"
            style={{ backgroundImage: "url(/swimpulse-pool-shark.png)" }}
          />
          <div className="grid gap-3">
            <GuideStep
              icon={Search}
              title="1. 위치나 수영장 검색"
              description="내 주변 수영장과 아직 DB에 없는 후보 시설까지 함께 확인합니다."
            />
            <GuideStep
              icon={FileSearch}
              title="2. 공지 확인"
              description="공식 홈페이지 공지를 읽고 모집 기간을 자동으로 정리합니다."
            />
            <GuideStep
              icon={Bell}
              title="3. 기간 구독"
              description="접수 시작 전 알림을 받고, 마이페이지에서 읽음/구독 상태를 관리합니다."
            />
          </div>
          <div className="mt-4 grid grid-cols-2 gap-3">
            <div className="rounded-2xl bg-[#0369a1] px-4 py-4 text-white">
              <p className="text-xs font-semibold opacity-80">예정 이벤트</p>
              <p className="mt-1 text-2xl font-black">{upcomingEvents}</p>
            </div>
            <div className="rounded-2xl bg-[#e0f2fe] px-4 py-4 text-[#075985]">
              <p className="text-xs font-semibold opacity-80">안 읽은 알림</p>
              <p className="mt-1 text-2xl font-black">{unreadNotifications}</p>
            </div>
          </div>
        </div>
      </div>
    </section>
  );
}

function ServiceIntroPages({
  user,
  onLogin,
  busy,
}: {
  user: AppUser | null;
  onLogin: () => void;
  busy: boolean;
}) {
  return (
    <section id="service-flow" className="bg-white">
      <div className="mx-auto grid max-w-7xl gap-6 px-5 py-8 lg:grid-cols-2 lg:py-12">
        <article className="swim-rise swim-card-motion min-h-[360px] rounded-lg border border-[#c8def0] bg-[#f6fbff] px-5 py-6 shadow-sm sm:px-7 sm:py-8">
          <div className="mb-5 grid size-12 place-items-center rounded-xl bg-[#dff2ff] text-[#0369a1]">
            <FileSearch size={23} aria-hidden />
          </div>
          <p className="text-sm font-bold text-[#0369a1]">INTRO 01</p>
          <h2 className="mt-2 text-2xl font-black text-[#102033] sm:text-3xl">
            모집 공지는 있는데, 접수 타이밍은 흩어져 있습니다.
          </h2>
          <p className="mt-4 leading-7 text-[#355b78]">
            어떤 시설은 공지사항 게시판에, 어떤 시설은 강좌 안내 페이지에 모집 정보를 올립니다.
            이미지 공지나 표 형식도 섞여서 사용자가 매번 직접 확인하기 어렵습니다.
          </p>
          <div className="mt-6 grid gap-3 text-sm text-[#28516f]">
            <IntroPoint icon={Search} text="위치 기준으로 주변 수영장을 찾습니다." />
            <IntroPoint icon={FileSearch} text="공식 홈페이지 공지에서 모집 기간을 정리합니다." />
            <IntroPoint icon={Bell} text="원하는 기간을 구독하면 알림으로 이어집니다." />
          </div>
        </article>

        <article className="swim-rise swim-rise-delay-1 swim-card-motion min-h-[360px] rounded-lg border border-[#c8def0] bg-[#102033] px-5 py-6 text-white shadow-sm sm:px-7 sm:py-8">
          <div className="mb-5 grid size-12 place-items-center rounded-xl bg-white/12 text-[#7dd3fc]">
            <Waves size={24} aria-hidden />
          </div>
          <p className="text-sm font-bold text-[#7dd3fc]">INTRO 02</p>
          <h2 className="mt-2 text-2xl font-black sm:text-3xl">
            찾기, 확인, 구독까지 한 화면에서 이어집니다.
          </h2>
          <p className="mt-4 leading-7 text-[#c8def0]">
            아래 작업 영역에서 수영장을 검색하고, 공지 확인 버튼으로 모집 기간을 추출한 뒤,
            필요한 기간만 구독하세요. 구독한 이벤트는 마이페이지와 알림 큐로 관리됩니다.
          </p>
          <div className="mt-6 flex flex-col gap-2 sm:flex-row">
            <a
              className="swim-action inline-flex h-11 items-center justify-center gap-2 rounded-lg bg-[#38bdf8] px-4 text-sm font-bold text-[#082f49] hover:bg-[#7dd3fc]"
              href="#pool-workspace"
            >
              <Waves size={17} aria-hidden />
              수영장 목록으로 이동
            </a>
            {!user ? (
              <button
                className="swim-action inline-flex h-11 items-center justify-center gap-2 rounded-lg border border-white/30 px-4 text-sm font-bold text-white hover:bg-white/10 disabled:opacity-50"
                onClick={onLogin}
                disabled={busy}
                type="button"
              >
                <LogIn size={17} aria-hidden />
                로그인하고 구독하기
              </button>
            ) : null}
          </div>
        </article>
      </div>
    </section>
  );
}

function IntroPoint({ icon: Icon, text }: { icon: typeof Search; text: string }) {
  return (
    <div className="swim-row-motion flex items-center gap-3 rounded-lg border border-[#d9eaf6] bg-white px-3 py-3">
      <span className="grid size-9 shrink-0 place-items-center rounded-lg bg-[#e0f2fe] text-[#0369a1]">
        <Icon size={17} aria-hidden />
      </span>
      <span className="font-semibold">{text}</span>
    </div>
  );
}

function GuideStep({
  icon: Icon,
  title,
  description,
}: {
  icon: typeof Search;
  title: string;
  description: string;
}) {
  return (
    <div className="swim-card-motion grid grid-cols-[44px_1fr] gap-3 rounded-2xl border border-[#d9eaf6] bg-white px-4 py-4">
      <div className="grid size-11 place-items-center rounded-xl bg-[#e0f2fe] text-[#0369a1]">
        <Icon size={20} aria-hidden />
      </div>
      <div>
        <h3 className="font-bold text-[#102033]">{title}</h3>
        <p className="mt-1 text-sm leading-6 text-[#4b6f8b]">{description}</p>
      </div>
    </div>
  );
}

function PoolImage({ pool }: { pool: Pool }) {
  const [failedImageUrl, setFailedImageUrl] = useState<string | null>(null);
  const imageUrl = pool.imageUrl && pool.imageUrl !== failedImageUrl ? pool.imageUrl : null;

  if (imageUrl && !isWeakPoolImageUrl(imageUrl)) {
    if (isDefaultPoolImage(imageUrl)) {
      return (
        <div
          className="h-24 rounded-lg bg-[#ddf5f4] bg-cover bg-center md:h-28"
          aria-label={`${pool.name} 기본 대표 이미지`}
          role="img"
          style={{ backgroundImage: `url(${defaultPoolImageUrl(imageUrl)})` }}
        />
      );
    }
    if (isIconLikePoolImage(imageUrl)) {
      return <DefaultPoolImage poolName={pool.name} />;
    }
    return (
      <div className="h-24 overflow-hidden rounded-lg bg-[#edf7f5] md:h-28">
        {/* eslint-disable-next-line @next/next/no-img-element */}
        <img
          className="h-full w-full object-cover"
          src={imageUrl}
          alt=""
          aria-hidden
          onError={() => setFailedImageUrl(imageUrl)}
        />
      </div>
    );
  }

  return <DefaultPoolImage poolName={pool.name} />;
}

function DefaultPoolImage({ poolName }: { poolName: string }) {
  return (
    <div
      className="h-24 rounded-lg bg-[#ddf5f4] bg-cover bg-center md:h-28"
      aria-label={`${poolName} 기본 대표 이미지`}
      role="img"
      style={{ backgroundImage: "url(/swimpulse-pool-shark.png)" }}
    />
  );
}

function defaultPoolImageUrl(imageUrl: string) {
  return imageUrl.toLowerCase().endsWith(".svg") ? "/swimpulse-pool-shark.png" : imageUrl;
}

function isDefaultPoolImage(imageUrl: string) {
  return imageUrl.toLowerCase().includes("swimpulse-pool-shark");
}

function isWeakPoolImageUrl(imageUrl: string) {
  const normalized = imageUrl.toLowerCase();
  return (
    normalized.includes("cdninstagram.com") ||
    normalized.includes("static.cdninstagram.com") ||
    normalized.includes("ssl.pstatic.net/static/blog/icon")
  );
}

function isIconLikePoolImage(imageUrl: string) {
  const normalized = imageUrl.toLowerCase();
  return (
    normalized.includes("favicon") ||
    normalized.includes("apple-touch-icon") ||
    normalized.includes("icon") ||
    normalized.endsWith(".ico") ||
    normalized.endsWith(".svg")
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
      <section className="swim-card-motion rounded-lg border border-[#c8def0] bg-white shadow-sm">
        <div className="border-b border-[#d9eaf6] px-4 py-4">
          <h2 className="text-lg font-semibold">계정</h2>
        </div>
        <div className="space-y-4 px-4 py-4">
          <div className="flex items-center gap-3">
            <div className="grid size-12 place-items-center rounded-lg bg-[#e0f2fe] text-[#0369a1]">
              <UserCircle size={26} aria-hidden />
            </div>
            <div>
              <p className="font-semibold">로그인 안 됨</p>
              <p className="text-sm text-[#4b6f8b]">Google 계정으로 시작하세요.</p>
            </div>
          </div>
          <button
            className="swim-action inline-flex h-10 w-full items-center justify-center gap-2 rounded-lg bg-[#075985] px-4 text-sm font-semibold text-white hover:bg-[#0c4a6e] disabled:opacity-50"
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
    <section className="swim-card-motion rounded-lg border border-[#c8def0] bg-white shadow-sm">
      <div className="flex items-center justify-between gap-3 border-b border-[#d9eaf6] px-4 py-4">
        <h2 className="text-lg font-semibold">계정</h2>
        <button
          className="swim-action grid size-9 place-items-center rounded-lg border border-[#b8d7ec] bg-white text-[#28516f] hover:border-[#ef4444] hover:text-[#dc2626] disabled:opacity-50"
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
          <div className="grid size-12 place-items-center rounded-lg bg-[#e0f2fe] text-lg font-semibold text-[#0369a1]">
            {initialLetter(user.displayName)}
          </div>
          <div className="min-w-0">
            <p className="truncate font-semibold">{user.displayName}</p>
            <p className="truncate text-sm text-[#4b6f8b]">Google 로그인</p>
          </div>
        </div>
        <div className="space-y-3 text-sm">
          <AccountRow icon={Mail} label="이메일" value={user.email} />
          <AccountRow icon={UserCircle} label="사용자 ID" value={user.id.toString()} />
          <AccountRow icon={CheckCircle2} label="구독" value={`${subscriptions.length}개`} />
          <AccountRow icon={Smartphone} label="현재 기기 PUSH" value={currentDeviceRegistered ? "등록됨" : "미등록"} />
          <AccountRow icon={CalendarClock} label="최근 로그인" value={user.lastLoginAt ? formatDateTime(user.lastLoginAt) : "-"} />
        </div>
        <div className="space-y-3 border-t border-[#d9eaf6] pt-4">
          <div className="flex items-center justify-between gap-2">
            <h3 className="text-sm font-semibold text-[#28516f]">내 구독</h3>
            <span className="rounded-md bg-[#e0f2fe] px-2 py-1 text-xs font-semibold text-[#0369a1]">
              {subscriptions.length}
            </span>
          </div>
          {subscriptions.length === 0 ? (
            <p className="rounded-lg border border-dashed border-[#b8d7ec] bg-[#f6fbff] px-3 py-4 text-sm text-[#4b6f8b]">
              아직 구독한 모집 기간이 없습니다.
            </p>
          ) : (
            <div className="max-h-[340px] divide-y divide-[#d9eaf6] overflow-auto rounded-lg border border-[#d9eaf6]">
              {subscriptions.map((subscription) => (
                <SubscriptionSummary key={subscription.id} subscription={subscription} />
              ))}
            </div>
          )}
        </div>
        <button
          className="swim-action inline-flex h-10 w-full items-center justify-center gap-2 rounded-lg border border-[#b8d7ec] bg-white px-4 text-sm font-semibold text-[#28516f] hover:border-[#ef4444] hover:text-[#dc2626] disabled:opacity-50"
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

function PushNotificationModal({
  notification,
  busy,
  onClose,
}: {
  notification: InAppNotification;
  busy: boolean;
  onClose: () => void;
}) {
  return (
    <div
      className="fixed inset-0 z-50 grid place-items-center bg-black/45 px-4"
      role="dialog"
      aria-modal="true"
      aria-labelledby="push-notification-title"
      onClick={onClose}
    >
      <div
        className="w-full max-w-lg rounded-[28px] border border-[#d8ddd5] bg-white p-0 shadow-2xl"
        onClick={(event) => event.stopPropagation()}
      >
        <div className="space-y-5 px-6 py-6 sm:px-7 sm:py-7">
          <div className="flex items-center justify-between gap-3">
            <span className="rounded-full bg-[#edf7f5] px-3 py-1 text-xs font-semibold text-[#0f766e]">PUSH 알림</span>
            <span
              className={`rounded-full px-3 py-1 text-xs font-semibold ${
                notification.readAt ? "bg-[#f0f1ef] text-[#66746d]" : "bg-[#fff0ed] text-[#bf4b3e]"
              }`}
            >
              {notification.readAt ? "읽음" : "안 읽음"}
            </span>
          </div>
          <div className="space-y-3">
            <h2 id="push-notification-title" className="text-2xl font-semibold tracking-tight text-[#17201d]">
              {notification.title}
            </h2>
            <p className="text-base leading-7 text-[#31413b]">{notification.message}</p>
          </div>
          <div className="rounded-2xl bg-[#f7f8f4] px-4 py-4 text-sm text-[#47564f]">
            <p className="font-semibold text-[#17201d]">{notification.poolName}</p>
            <p className="mt-1">{notification.eventTitle}</p>
            <p className="mt-3 text-xs text-[#7c8982]">도착 {formatDateTime(notification.createdAt)}</p>
          </div>
          <div className="grid gap-2 sm:grid-cols-2">
            {notification.noticeUrl ? (
              <a
                className="inline-flex h-12 w-full items-center justify-center gap-2 rounded-2xl border border-[#d8ddd5] bg-white px-4 text-sm font-semibold text-[#17201d] transition hover:border-[#17201d]"
                href={notification.noticeUrl}
                target="_blank"
                rel="noreferrer"
              >
                원문 보기
                <ExternalLink className="h-4 w-4" aria-hidden="true" />
              </a>
            ) : null}
            <button
              className="inline-flex h-12 w-full items-center justify-center rounded-2xl bg-[#17201d] px-4 text-sm font-semibold text-white transition hover:bg-[#31413b] disabled:opacity-50"
              onClick={onClose}
              disabled={busy}
              type="button"
            >
              {busy ? "읽음 처리 중..." : "확인"}
            </button>
          </div>
          <p className="text-center text-xs text-[#7c8982]">바깥 영역을 눌러도 닫히며 읽음 처리됩니다.</p>
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
  candidate: PoolLocationCandidate;
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
              추가 요청
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}

function PastPeriodSubscriptionModal({
  prompt,
  busy,
  onConfirm,
  onClose,
}: {
  prompt: PastPeriodPrompt;
  busy: boolean;
  onConfirm: () => void;
  onClose: () => void;
}) {
  return (
    <div className="fixed inset-0 z-[60] grid place-items-center bg-black/45 px-4" role="dialog" aria-modal="true">
      <div className="w-full max-w-md rounded-lg border border-[#d8ddd5] bg-white shadow-xl">
        <div className="flex items-center gap-3 border-b border-[#e3e7e1] px-5 py-4">
          <div className="grid size-10 shrink-0 place-items-center rounded-lg bg-[#fff2e2] text-[#946123]">
            <CalendarClock size={20} aria-hidden />
          </div>
          <div>
            <h2 className="text-lg font-semibold">지난 달 모집 기간입니다.</h2>
            <p className="text-sm text-[#66746d]">{prompt.notice.poolName}</p>
          </div>
        </div>
        <div className="space-y-4 px-5 py-5">
          <p className="text-sm font-semibold leading-6 text-[#31413b]">
            이번 달 모집일과 다를 수 있습니다! <br></br>이번 달 같은 날에 알림을 보낼까요?
          </p>
          <div className="grid gap-2 rounded-md border border-[#e3e7e1] bg-[#fafbf8] px-3 py-3 text-sm">
            <div className="flex items-center justify-between gap-3">
              <span className="text-[#7c8982]">지난 모집 기간</span>
              <span className="font-medium text-[#47564f]">
                {formatDate(prompt.originalPeriod.startsAt)} - {formatDate(prompt.originalPeriod.endsAt)}
              </span>
            </div>
            <div className="flex items-center justify-between gap-3">
              <span className="text-[#7c8982]">예상 알림 기간</span>
              <span className="font-semibold text-[#0f766e]">
                {formatDate(prompt.shiftedPeriod.startsAt)} - {formatDate(prompt.shiftedPeriod.endsAt)}
              </span>
            </div>
          </div>
          <p className="text-xs leading-5 text-[#946123]">
            공지 원문의 일자를 현재 월로 옮긴 예상 기간이며, 실제 모집 공지와 다를 수 있습니다.
          </p>
          <div className="grid gap-2 sm:grid-cols-2">
            <button
              className="inline-flex h-10 items-center justify-center rounded-lg border border-[#cdd5cf] bg-white px-4 text-sm font-semibold text-[#31413b] transition hover:border-[#0f766e] disabled:opacity-50"
              onClick={onClose}
              disabled={busy}
              type="button"
            >
              취소
            </button>
            <button
              className="inline-flex h-10 items-center justify-center gap-2 rounded-lg bg-[#0f766e] px-4 text-sm font-semibold text-white transition hover:bg-[#0b5f59] disabled:opacity-50"
              onClick={onConfirm}
              disabled={busy}
              type="button"
            >
              <Bell size={16} aria-hidden />
              {busy ? "구독 중..." : "이 날짜로 구독"}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}

function NoticeResultModal({
  result,
  onResultUpdate,
  onClose,
  subscriptionMode,
  subscriptions,
  subscribedEventKeys,
  pendingSubscriptionKey,
  onSubscribe,
  onUnsubscribe,
}: {
  result: NoticeScanResponse;
  onResultUpdate: (result: NoticeScanResponse) => void;
  onClose: () => void;
  subscriptionMode: boolean;
  subscriptions: Subscription[];
  subscribedEventKeys: Set<string>;
  pendingSubscriptionKey: string | null;
  onSubscribe: (notice: PoolNotice, period: NoticeRegistrationPeriod) => void;
  onUnsubscribe: (subscription: Subscription) => void;
}) {
  const trace = result.trace ?? [];
  const hasOcrInProgress = result.notices.some((notice) => isOcrInProgress(notice.ocrStatus));
  const [ocrPollingTimedOutPoolId, setOcrPollingTimedOutPoolId] = useState<number | null>(null);
  const ocrPollingTimedOut = ocrPollingTimedOutPoolId === result.poolId;

  useEffect(() => {
    if (!hasOcrInProgress) {
      return;
    }

    let cancelled = false;
    let elapsedMs = 0;
    const maxPollingMs = 30_000;
    const pollIntervalMs = 2_500;

    const timerId = window.setInterval(async () => {
      elapsedMs += pollIntervalMs;
      if (elapsedMs > maxPollingMs) {
        setOcrPollingTimedOutPoolId(result.poolId);
        window.clearInterval(timerId);
        return;
      }
      try {
        const updated = await scanPoolNotices(result.poolId);
        if (!cancelled) {
          onResultUpdate(updated);
        }
      } catch {
        // Keep the current result visible. The next tick can retry until timeout.
      }
    }, pollIntervalMs);

    return () => {
      cancelled = true;
      window.clearInterval(timerId);
    };
  }, [hasOcrInProgress, onResultUpdate, result.poolId]);

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
          {result.latestCheckFailed ? (
            <div className="border-l-4 border-[#c2410c] bg-[#fff7ed] px-5 py-3 text-sm font-medium text-[#9a3412]">
              {result.message}
            </div>
          ) : null}
          {hasOcrInProgress ? (
            <div className="border-l-4 border-[#0f766e] bg-[#eefaf5] px-5 py-3 text-sm font-medium text-[#12645d]">
              {ocrPollingTimedOut
                ? "이미지 공지 분석이 지연되고 있습니다. 원문을 확인하거나 잠시 후 다시 시도해 주세요."
                : "이미지 공지를 분석 중입니다. 완료되면 이 창에서 자동으로 갱신됩니다."}
            </div>
          ) : null}
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
                        id: null,
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
                    {notice.ocrStatus && notice.ocrStatus !== "NOT_REQUIRED" ? (
                      <span className={`rounded-md px-2 py-1 text-xs font-semibold ${noticeOcrStatusClass(notice.ocrStatus)}`}>
                        {noticeOcrStatusLabel(notice.ocrStatus)}
                      </span>
                    ) : null}
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
                    <p className="text-sm text-[#66746d]">{noticeOcrEmptyPeriodMessage(notice.ocrStatus, subscriptionMode)}</p>
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
  subscriptions,
  subscribedEventKeys,
  pendingSubscriptionKey,
  onSubscribe,
  onUnsubscribe,
}: {
  notice: PoolNotice;
  period: NoticeRegistrationPeriod;
  subscriptions: Subscription[];
  subscribedEventKeys: Set<string>;
  pendingSubscriptionKey: string | null;
  onSubscribe: (notice: PoolNotice, period: NoticeRegistrationPeriod) => void;
  onUnsubscribe: (subscription: Subscription) => void;
}) {
  const title = buildSubscriptionTitle(notice, period);
  const key = subscriptionKey(notice.poolId, title, period.startsAt, period.endsAt);
  const shiftedPeriod = isPastMonthPeriod(period) ? shiftPeriodToCurrentMonth(period) : null;
  const shiftedTitle = shiftedPeriod ? buildEstimatedSubscriptionTitle(notice, shiftedPeriod) : null;
  const shiftedKey =
    shiftedPeriod && shiftedTitle
      ? subscriptionKey(notice.poolId, shiftedTitle, shiftedPeriod.startsAt, shiftedPeriod.endsAt)
      : null;
  const subscription = subscriptions.find(
    (item) =>
      item.event &&
      (subscriptionKeyFromEvent(item.event) === key ||
        (shiftedKey !== null && subscriptionKeyFromEvent(item.event) === shiftedKey)),
  );
  const subscribed = subscribedEventKeys.has(key) || (shiftedKey !== null && subscribedEventKeys.has(shiftedKey));
  const pending = pendingSubscriptionKey === key || (shiftedKey !== null && pendingSubscriptionKey === shiftedKey);
  const periodLabel = period.label?.trim() || "모집 기간";

  return (
    <div className="grid gap-3 rounded-md border border-[#e3e7e1] bg-[#fafbf8] px-3 py-2 sm:grid-cols-[1fr_auto] sm:items-center">
      <div className="min-w-0">
        <div className="flex flex-wrap items-center gap-x-2 gap-y-1 text-sm text-[#31413b]">
          <span className="font-semibold">{periodLabel}</span>
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
    <article className="swim-row-motion space-y-2 bg-white px-3 py-3">
      <div className="flex flex-wrap items-center gap-2">
        {event ? <StatusBadge status={event.status} /> : null}
        <p className="min-w-0 flex-1 truncate text-sm font-semibold text-[#102033]">{poolName}</p>
      </div>
      <div className="space-y-1">
        <p className="line-clamp-2 text-sm font-medium text-[#28516f]">{event?.title ?? "기간 정보 없음"}</p>
        {event ? (
          <p className="text-xs leading-5 text-[#4b6f8b]">
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
      <Icon className="mt-0.5 shrink-0 text-[#4b6f8b]" size={15} aria-hidden />
      <div className="min-w-0">
        <p className="text-xs font-semibold text-[#4b6f8b]">{label}</p>
        <p className="break-words text-[#28516f]">{value}</p>
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
  tone: "blue" | "amber" | "cyan";
}) {
  const toneClass = {
    blue: "bg-[#e0f2fe] text-[#0369a1]",
    amber: "bg-[#fff2e2] text-[#946123]",
    cyan: "bg-[#ecfeff] text-[#0e7490]",
  }[tone];

  return (
    <div className="swim-card-motion rounded-lg border border-[#c8def0] bg-white px-4 py-4 shadow-sm">
      <div className={`mb-5 grid size-10 place-items-center rounded-lg ${toneClass}`}>
        <Icon size={19} aria-hidden />
      </div>
      <p className="text-sm text-[#4b6f8b]">{label}</p>
      <p className="mt-1 text-3xl font-semibold text-[#102033]">{value}</p>
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

function EventTimeLeft({ event }: { event: RegistrationEvent }) {
  return (
    <div className="flex items-center text-right text-sm font-semibold text-[#0284c7] md:justify-end" suppressHydrationWarning>
      {event.status === "CLOSED" ? "마감" : formatTimeLeft(event.registrationStartsAt)}
    </div>
  );
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

function buildSubscriptionTitle(notice: PoolNotice, period: NoticeRegistrationPeriod) {
  const label = period.label?.trim() || "모집 기간";
  const title = `${label} - ${notice.title}`.replace(/\s+/g, " ").trim();
  return title.length <= 120 ? title : title.slice(0, 120);
}

function buildEstimatedSubscriptionTitle(notice: PoolNotice, period: NoticeRegistrationPeriod) {
  const baseTitle = buildSubscriptionTitle(notice, period);
  const suffix = " (이번 달 예상)";
  return `${baseTitle.slice(0, 120 - suffix.length)}${suffix}`;
}

function isPastMonthPeriod(period: NoticeRegistrationPeriod) {
  const current = seoulDateParts(new Date());
  const periodEnd = seoulDateParts(new Date(period.endsAt));
  return periodEnd.year < current.year || (periodEnd.year === current.year && periodEnd.month < current.month);
}

function shiftPeriodToCurrentMonth(period: NoticeRegistrationPeriod): NoticeRegistrationPeriod {
  const current = seoulDateParts(new Date());
  const sourceStart = seoulDateParts(new Date(period.startsAt));
  const sourceEnd = seoulDateParts(new Date(period.endsAt));
  const dayMatches = Array.from((period.periodText ?? "").matchAll(/(\d{1,2})\s*일/g)).map((match) =>
    Number(match[1]),
  );
  const startDay = dayMatches[0] ?? sourceStart.day;
  const sourceCrossesMonth =
    sourceEnd.year > sourceStart.year ||
    (sourceEnd.year === sourceStart.year && sourceEnd.month > sourceStart.month) ||
    /익월/.test(period.periodText ?? "");
  const targetStart = normalizeYearMonth(current.year, current.month);
  const targetEnd = normalizeYearMonth(current.year, current.month + (sourceCrossesMonth ? 1 : 0));
  const endDay = /말일/.test(period.periodText ?? "")
    ? daysInMonth(targetEnd.year, targetEnd.month)
    : dayMatches[1] ?? sourceEnd.day;
  const safeStartDay = Math.min(startDay, daysInMonth(targetStart.year, targetStart.month));
  const safeEndDay = Math.min(endDay, daysInMonth(targetEnd.year, targetEnd.month));

  return {
    ...period,
    id: null,
    startsAt: seoulDateToIso(targetStart.year, targetStart.month, safeStartDay, false),
    endsAt: seoulDateToIso(targetEnd.year, targetEnd.month, safeEndDay, true),
  };
}

function seoulDateParts(date: Date) {
  const parts = new Intl.DateTimeFormat("en-US", {
    timeZone: "Asia/Seoul",
    year: "numeric",
    month: "numeric",
    day: "numeric",
  }).formatToParts(date);
  const values = Object.fromEntries(parts.map((part) => [part.type, part.value]));
  return {
    year: Number(values.year),
    month: Number(values.month),
    day: Number(values.day),
  };
}

function normalizeYearMonth(year: number, month: number) {
  const normalized = new Date(Date.UTC(year, month - 1, 1));
  return {
    year: normalized.getUTCFullYear(),
    month: normalized.getUTCMonth() + 1,
  };
}

function daysInMonth(year: number, month: number) {
  return new Date(Date.UTC(year, month, 0)).getUTCDate();
}

function seoulDateToIso(year: number, month: number, day: number, endOfDay: boolean) {
  const localMilliseconds = Date.UTC(
    year,
    month - 1,
    day,
    endOfDay ? 23 : 0,
    endOfDay ? 59 : 0,
    endOfDay ? 59 : 0,
  );
  return new Date(localMilliseconds - 9 * 60 * 60 * 1000).toISOString();
}

function subscriptionKeyFromEvent(event: RegistrationEvent) {
  return subscriptionKey(event.poolId, event.title, event.registrationStartsAt, event.registrationEndsAt);
}

function subscriptionKey(poolId: number, title: string, startsAt: string, endsAt: string) {
  return `${poolId}|${title}|${normalizeInstantKey(startsAt)}|${normalizeInstantKey(endsAt)}`;
}

function normalizeInstantKey(value: string) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toISOString();
}

function formatDistance(distanceMeters: number) {
  if (distanceMeters < 1000) {
    return `${Math.round(distanceMeters)}m`;
  }
  return `${(distanceMeters / 1000).toFixed(1)}km`;
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

function isOcrInProgress(status: PoolNotice["ocrStatus"]) {
  return status === "PENDING" || status === "PROCESSING";
}

function noticeOcrStatusLabel(status: NonNullable<PoolNotice["ocrStatus"]>) {
  const labels = {
    NOT_REQUIRED: "이미지 분석 불필요",
    PENDING: "이미지 분석 대기",
    PROCESSING: "이미지 분석 중",
    COMPLETED: "이미지 분석 완료",
    NO_PERIOD: "이미지 기간 없음",
    FAILED: "이미지 분석 실패",
  };
  return labels[status];
}

function noticeOcrStatusClass(status: NonNullable<PoolNotice["ocrStatus"]>) {
  const classes = {
    NOT_REQUIRED: "bg-[#edf7f5] text-[#0f766e]",
    PENDING: "bg-[#eefaf5] text-[#12645d]",
    PROCESSING: "bg-[#e8f3ff] text-[#1d4f8f]",
    COMPLETED: "bg-[#edf7f5] text-[#0f766e]",
    NO_PERIOD: "bg-[#fff2e2] text-[#946123]",
    FAILED: "bg-[#fff0ed] text-[#bf4b3e]",
  };
  return classes[status];
}

function noticeOcrEmptyPeriodMessage(status: PoolNotice["ocrStatus"], subscriptionMode: boolean) {
  if (status === "PENDING" || status === "PROCESSING") {
    return "이미지 공지를 분석 중입니다. 완료되면 이 창에서 자동으로 갱신됩니다.";
  }
  if (status === "NO_PERIOD") {
    return "이미지 공지를 분석했지만 모집 기간을 찾지 못했습니다. 원문을 확인해 주세요.";
  }
  if (status === "FAILED") {
    return "이미지 공지 분석에 실패했습니다. 원문을 확인해 주세요.";
  }
  return subscriptionMode
    ? "구독할 수 있는 구조화 기간이 없습니다. 원문 링크를 확인하세요."
    : "구독할 수 있는 구조화 기간이 없습니다. 원문 링크를 확인하세요.";
}
