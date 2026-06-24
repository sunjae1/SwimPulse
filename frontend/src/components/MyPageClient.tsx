"use client";

import Link from "next/link";
import {
  Bell,
  CalendarClock,
  ChevronDown,
  CheckCircle2,
  ChevronLeft,
  ChevronRight,
  ExternalLink,
  LogIn,
  LogOut,
  Mail,
  RefreshCw,
  Smartphone,
  Trash2,
  UserCircle,
  Waves,
} from "lucide-react";
import type { LucideIcon } from "lucide-react";
import { FormEvent, useEffect, useState } from "react";
import { AppNavigation } from "@/components/AppNavigation";
import {
  ApiRequestError,
  deleteSubscription,
  getMyPage,
  getNotificationPage,
  logout,
  markNotificationRead,
  updateSubscriptionPeriod,
} from "@/lib/api";
import {
  eventStatusLabel,
  formatDateTime,
  formatFullDate,
  notificationStatusLabel,
} from "@/lib/format";
import type {
  EventStatus,
  InAppNotification,
  MyPageData,
  NotificationPage,
  NotificationStatus,
  Subscription,
} from "@/lib/types";

type SubscriptionEditForm = {
  subscriptionId: number;
  poolName: string;
  title: string;
  startsAt: string;
  endsAt: string;
};

type SubscriptionDateField = "startsAt" | "endsAt";

const MY_PAGE_NOTIFICATION_PAGE_SIZE = 10;

export function MyPageClient() {
  const [data, setData] = useState<MyPageData | null>(null);
  const [notificationPage, setNotificationPage] = useState<NotificationPage | null>(null);
  const [authorized, setAuthorized] = useState(true);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [reloadKey, setReloadKey] = useState(0);
  const [editingSubscription, setEditingSubscription] = useState<SubscriptionEditForm | null>(null);
  const [deletingSubscription, setDeletingSubscription] = useState<Subscription | null>(null);
  const [savingSubscriptionId, setSavingSubscriptionId] = useState<number | null>(null);
  const [removingSubscriptionId, setRemovingSubscriptionId] = useState<number | null>(null);
  const [editValidationShakeKey, setEditValidationShakeKey] = useState(0);
  const [editInvalidDateField, setEditInvalidDateField] = useState<SubscriptionDateField | null>(null);
  const [editValidationMessage, setEditValidationMessage] = useState<string | null>(null);
  const [lastEditedSubscriptionDateField, setLastEditedSubscriptionDateField] =
    useState<SubscriptionDateField>("endsAt");
  const [showClosedSubscriptions, setShowClosedSubscriptions] = useState(false);
  const [readingNotificationId, setReadingNotificationId] = useState<number | null>(null);
  const [notificationPageLoading, setNotificationPageLoading] = useState(false);

  useEffect(() => {
    let cancelled = false;

    async function loadMyPage() {
      setLoading(true);
      setErrorMessage(null);
      try {
        const result = await getMyPage();
        if (cancelled) {
          return;
        }
        setData(result);
        setNotificationPage(createInitialNotificationPage(result));
        setAuthorized(true);
      } catch (error) {
        if (cancelled) {
          return;
        }
        if (error instanceof ApiRequestError && error.status === 401) {
          setData(null);
          setNotificationPage(null);
          setAuthorized(false);
          return;
        }
        setData(null);
        setNotificationPage(null);
        setAuthorized(true);
        setErrorMessage(getErrorMessage(error, "마이 페이지 정보를 불러오지 못했습니다."));
      } finally {
        if (!cancelled) {
          setLoading(false);
        }
      }
    }

    void loadMyPage();

    return () => {
      cancelled = true;
    };
  }, [reloadKey]);

  function loginWithGoogle() {
    window.location.href = "/oauth2/authorization/google";
  }

  async function logoutUser() {
    setBusy(true);
    setNotice(null);
    try {
      await logout();
      setData(null);
      setAuthorized(false);
      setNotice("로그아웃했습니다.");
    } catch {
      setNotice("로그아웃 요청을 처리하지 못했습니다.");
    } finally {
      setBusy(false);
    }
  }

  function reloadPage() {
    setNotice(null);
    setReloadKey((current) => current + 1);
  }

  function openSubscriptionEditor(subscription: Subscription) {
    if (!subscription.event) {
      setNotice("기간 정보가 없는 구독은 수정할 수 없습니다.");
      return;
    }

    const startsAt = toDateTimeLocalValue(new Date(subscription.event.registrationStartsAt));
    const endsAt = toDateTimeLocalValue(new Date(subscription.event.registrationEndsAt));

    setNotice(null);
    setEditInvalidDateField(null);
    setEditValidationMessage(null);
    setLastEditedSubscriptionDateField("endsAt");
    setEditingSubscription({
      subscriptionId: subscription.id,
      poolName: subscription.event.poolName ?? subscription.pool.name,
      title: subscription.event.title,
      startsAt,
      endsAt,
    });
  }

  function closeSubscriptionEditor() {
    if (savingSubscriptionId !== null) {
      return;
    }
    setEditInvalidDateField(null);
    setEditValidationMessage(null);
    setEditingSubscription(null);
  }

  function openSubscriptionDeleteConfirm(subscription: Subscription) {
    if (!subscription.event) {
      setNotice("이 구독은 연결된 모집 기간이 없어 해제할 수 없습니다.");
      return;
    }

    setNotice(null);
    setDeletingSubscription(subscription);
  }

  function closeSubscriptionDeleteConfirm() {
    if (removingSubscriptionId !== null) {
      return;
    }
    setDeletingSubscription(null);
  }

  async function submitSubscriptionEdit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!editingSubscription) {
      return;
    }

    const title = editingSubscription.title.trim();
    const startsAt = new Date(editingSubscription.startsAt);
    const endsAt = new Date(editingSubscription.endsAt);

    if (!title) {
      setEditValidationMessage("구독명을 입력해 주세요.");
      return;
    }
    if (Number.isNaN(startsAt.getTime()) || Number.isNaN(endsAt.getTime())) {
      rejectSubscriptionEdit(
        "시작 시각과 종료 시각을 모두 입력해 주세요.",
        Number.isNaN(startsAt.getTime()) ? "startsAt" : "endsAt",
      );
      return;
    }
    if (startsAt.getTime() >= endsAt.getTime()) {
      rejectSubscriptionEdit(
        "종료 시각은 시작 시각보다 뒤여야 합니다.",
        lastEditedSubscriptionDateField,
      );
      return;
    }
    if (endsAt.getTime() <= currentTimeMillis()) {
      rejectSubscriptionEdit("이미 종료된 기간으로는 수정할 수 없습니다.", "endsAt");
      return;
    }

    setSavingSubscriptionId(editingSubscription.subscriptionId);
    setNotice(null);
    setEditValidationMessage(null);
    try {
      await updateSubscriptionPeriod(editingSubscription.subscriptionId, {
        title,
        registrationStartsAt: startsAt.toISOString(),
        registrationEndsAt: endsAt.toISOString(),
      });
      setEditingSubscription(null);
      setNotice("구독 기간을 수정했습니다. 이제 수정한 기간 기준으로 알림이 동작합니다.");
      setReloadKey((current) => current + 1);
    } catch (error) {
      setNotice(getErrorMessage(error, "구독 기간 수정에 실패했습니다."));
    } finally {
      setSavingSubscriptionId(null);
    }
  }

  async function readNotification(notificationId: number) {
    if (!data) {
      return;
    }

    const target =
      notificationPage?.content.find((notification) => notification.id === notificationId) ??
      data.notifications.find((notification) => notification.id === notificationId);
    if (!target || target.readAt) {
      return;
    }

    setReadingNotificationId(notificationId);
    setNotice(null);
    try {
      const updated = await markNotificationRead(notificationId);
      const wasUnread = target.readAt == null;
      setData((current) => (current ? applyNotificationUpdate(current, updated, wasUnread) : current));
      setNotificationPage((current) => (current ? applyNotificationPageUpdate(current, updated, wasUnread) : current));
      setNotice("알림을 읽음 처리했습니다.");
    } catch (error) {
      setNotice(getErrorMessage(error, "알림 읽음 처리에 실패했습니다."));
    } finally {
      setReadingNotificationId(null);
    }
  }

  function rejectSubscriptionEdit(message: string, invalidField: SubscriptionDateField) {
    setEditValidationMessage(message);
    setEditInvalidDateField(invalidField);
    setEditValidationShakeKey((current) => current + 1);
  }

  async function removeSubscription(subscription: Subscription) {
    if (!subscription.event) {
      setNotice("이 구독은 연결된 모집 기간이 없어 해제할 수 없습니다.");
      return;
    }

    setRemovingSubscriptionId(subscription.id);
    setNotice(null);
    try {
      await deleteSubscription(subscription.event.id);
      setData((current) => (current ? removeSubscriptionFromMyPage(current, subscription) : current));
      setEditingSubscription((current) =>
        current?.subscriptionId === subscription.id ? null : current,
      );
      setDeletingSubscription(null);
      setNotice("구독을 해제했습니다.");
    } catch (error) {
      setNotice(getErrorMessage(error, "구독 해제에 실패했습니다."));
    } finally {
      setRemovingSubscriptionId(null);
    }
  }

  async function loadNotificationPage(page: number) {
    if (page < 0 || notificationPageLoading) {
      return;
    }

    setNotificationPageLoading(true);
    setNotice(null);
    try {
      const nextPage = await getNotificationPage(page, MY_PAGE_NOTIFICATION_PAGE_SIZE);
      setNotificationPage(nextPage);
      setData((current) => (current ? applyNotificationPageMetrics(current, nextPage) : current));
    } catch (error) {
      setNotice(getErrorMessage(error, "알림 페이지를 불러오지 못했습니다."));
    } finally {
      setNotificationPageLoading(false);
    }
  }

  const userName = data?.user.displayName ?? null;
  const visibleNotifications = notificationPage?.content ?? [];
  const notificationTotalPages = Math.max(notificationPage?.totalPages ?? 0, 1);
  const notificationPageNumber = notificationPage?.page ?? 0;
  const activeSubscriptions = data?.subscriptions.filter((subscription) => subscription.event?.status !== "CLOSED") ?? [];
  const closedSubscriptions = data?.subscriptions.filter((subscription) => subscription.event?.status === "CLOSED") ?? [];

  return (
    <main className="min-h-screen bg-[#edf7ff] text-[#102033]">
      <div className="sticky top-0 z-30 border-b border-[#c8def0] bg-white/92 shadow-sm backdrop-blur">
        <div className="mx-auto flex max-w-7xl flex-col gap-4 px-5 py-4 lg:flex-row lg:items-center lg:justify-between">
          <div className="flex flex-col gap-3 md:flex-row md:items-center md:gap-5">
            <div className="flex items-center gap-3">
              <div className="swim-pulse-dot grid size-10 place-items-center rounded-lg bg-[#0369a1] text-white shadow-sm">
                <Waves size={22} aria-hidden />
              </div>
              <div>
                <p className="text-sm font-semibold text-[#0369a1]">SwimPulse</p>
                <h1 className="text-xl font-semibold text-[#102033]">마이 페이지</h1>
              </div>
            </div>
            <AppNavigation />
          </div>
          <div className="flex flex-wrap items-center gap-2">
            {loading ? (
              <span className="rounded-lg bg-[#e0f2fe] px-3 py-2 text-sm font-semibold text-[#0369a1]">
                정보를 불러오는 중
              </span>
            ) : null}
            {userName ? (
              <span className="hidden max-w-44 truncate rounded-lg border border-[#c8def0] bg-white px-3 py-2 text-sm font-semibold text-[#28516f] md:inline-flex">
                {userName}
              </span>
            ) : null}
            {data ? (
              <button
                className="swim-action inline-flex h-10 items-center justify-center gap-2 rounded-lg border border-[#c8def0] bg-white px-4 text-sm font-semibold text-[#28516f] hover:border-[#ef4444] hover:text-[#dc2626] disabled:opacity-50"
                onClick={logoutUser}
                disabled={busy}
                type="button"
              >
                <LogOut size={16} aria-hidden />
                로그아웃
              </button>
            ) : !loading && !authorized ? (
              <button
                className="swim-action inline-flex h-10 items-center justify-center gap-2 rounded-lg bg-[#075985] px-4 text-sm font-semibold text-white hover:bg-[#0c4a6e]"
                onClick={loginWithGoogle}
                type="button"
              >
                <LogIn size={16} aria-hidden />
                Google 로그인
              </button>
            ) : null}
          </div>
        </div>
      </div>

      {notice ? <PageNotice message={notice} tone="info" /> : null}
      {errorMessage ? <PageNotice message={errorMessage} tone="error" /> : null}

      <div className="swim-workspace-shell border-t border-[#c8def0]">
      <div className="mx-auto max-w-7xl px-5 py-8">
        {loading ? (
          <LoadingState />
        ) : !authorized ? (
          <LoginRequiredState onLogin={loginWithGoogle} />
        ) : errorMessage ? (
          <ErrorState onRetry={reloadPage} />
        ) : data ? (
          <div className="grid gap-5 lg:grid-cols-[minmax(0,1.35fr)_360px]">
            <div className="swim-rise space-y-5">
              <section className="swim-card-motion overflow-hidden rounded-[28px] border border-[#c8def0] bg-white px-6 py-6 shadow-[0_18px_45px_rgba(3,105,161,0.08)]">
                <p className="text-xs font-semibold uppercase tracking-[0.24em] text-[#0369a1]">
                  My Page Snapshot
                </p>
                <div className="mt-4 flex flex-col gap-4 sm:flex-row sm:items-start sm:justify-between">
                  <div className="flex items-center gap-4">
                    <div className="grid size-16 place-items-center rounded-2xl bg-[#e0f2fe] text-2xl font-semibold text-[#0369a1]">
                      {initialLetter(data.user.displayName)}
                    </div>
                    <div className="min-w-0">
                      <h2 className="truncate text-2xl font-semibold">{data.user.displayName}</h2>
                      <p className="truncate text-sm text-[#4b6f8b]">{data.user.email}</p>
                      <p className="mt-1 text-sm text-[#28516f]">
                        가입일 {formatFullDate(data.user.createdAt)}
                      </p>
                    </div>
                  </div>
                  <Link
                    href="/"
                    className="swim-action inline-flex h-10 items-center justify-center rounded-lg border border-[#b8d7ec] bg-white px-4 text-sm font-semibold text-[#28516f] hover:border-[#0284c7] hover:text-[#0369a1]"
                  >
                    대시보드 보기
                  </Link>
                </div>
                <div className="mt-6 grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
                  <MetricCard
                    icon={CheckCircle2}
                    label="내 구독"
                    value={`${data.metrics.subscriptionCount}개`}
                    tone="blue"
                  />
                  <MetricCard
                    icon={CalendarClock}
                    label="예정된 모집"
                    value={`${data.metrics.upcomingSubscriptionCount}개`}
                    tone="amber"
                  />
                  <MetricCard
                    icon={Bell}
                    label="안 읽은 알림"
                    value={`${data.metrics.unreadNotificationCount}개`}
                    tone="cyan"
                  />
                  <MetricCard
                    icon={Smartphone}
                    label="활성 PUSH 기기"
                    value={`${data.metrics.activeDeviceCount}대`}
                    tone="blue"
                  />
                </div>
              </section>

              <section className="swim-card-motion rounded-lg border border-[#c8def0] bg-white shadow-sm">
                <div className="flex items-center justify-between gap-3 border-b border-[#d9eaf6] px-5 py-4">
                  <div>
                    <h2 className="text-lg font-semibold">내 구독</h2>
                    <p className="text-sm text-[#4b6f8b]">
                      수영장 모집 기간 구독 현황입니다. 크롤링 파싱이 어긋났다면 기간을 직접 수정할 수 있습니다.
                    </p>
                  </div>
                  <span className="rounded-md bg-[#e0f2fe] px-2 py-1 text-xs font-semibold text-[#0369a1]">
                    {data.metrics.subscriptionCount}
                  </span>
                </div>
                <div className="space-y-3 px-5 py-5">
                  {data.subscriptions.length === 0 ? (
                    <EmptyMessage message="아직 구독한 모집 기간이 없습니다. 대시보드에서 관심 수영장 모집을 구독해 보세요." />
                  ) : activeSubscriptions.length === 0 ? (
                    <EmptyMessage message="진행 중이거나 예정된 구독이 없습니다. 마감된 구독은 아래 접힌 영역에서 확인할 수 있습니다." />
                  ) : (
                    activeSubscriptions.map((subscription) => (
                      <SubscriptionCard
                        key={subscription.id}
                        subscription={subscription}
                        onEdit={openSubscriptionEditor}
                        onDelete={openSubscriptionDeleteConfirm}
                        editBusy={savingSubscriptionId === subscription.id}
                        deleteBusy={removingSubscriptionId === subscription.id}
                      />
                    ))
                  )}
                  {closedSubscriptions.length > 0 ? (
                    <div className="rounded-2xl border border-[#d9eaf6] bg-[#f8fbfd]">
                      <button
                        className="flex w-full items-center justify-between gap-3 px-4 py-4 text-left text-sm font-semibold text-[#28516f]"
                        onClick={() => setShowClosedSubscriptions((current) => !current)}
                        type="button"
                      >
                        <span>마감된 구독 {closedSubscriptions.length}개</span>
                        {showClosedSubscriptions ? (
                          <ChevronDown size={17} aria-hidden />
                        ) : (
                          <ChevronRight size={17} aria-hidden />
                        )}
                      </button>
                      {showClosedSubscriptions ? (
                        <div className="space-y-3 border-t border-[#d9eaf6] px-4 py-4">
                          {closedSubscriptions.map((subscription) => (
                            <SubscriptionCard
                              key={subscription.id}
                              subscription={subscription}
                              onEdit={openSubscriptionEditor}
                              onDelete={openSubscriptionDeleteConfirm}
                              editBusy={savingSubscriptionId === subscription.id}
                              deleteBusy={removingSubscriptionId === subscription.id}
                            />
                          ))}
                        </div>
                      ) : null}
                    </div>
                  ) : null}
                </div>
              </section>

              <section className="swim-card-motion rounded-lg border border-[#c8def0] bg-white shadow-sm">
                <div className="flex items-center justify-between gap-3 border-b border-[#d9eaf6] px-5 py-4">
                  <div>
                    <h2 className="text-lg font-semibold">알림 목록</h2>
                    <p className="text-sm text-[#4b6f8b]">
                      페이지 단위로 받은 알림과 읽음 상태를 확인할 수 있습니다.
                    </p>
                  </div>
                  <div className="flex flex-col items-end gap-1">
                    <span className="rounded-md bg-[#fff0ed] px-2 py-1 text-xs font-semibold text-[#bf4b3e]">
                      안 읽음 {data.metrics.unreadNotificationCount}
                    </span>
                    <span className="text-xs font-semibold text-[#4b6f8b]">
                      전체 {data.metrics.notificationCount}개
                    </span>
                  </div>
                </div>
                <div className="space-y-3 px-5 py-5">
                  {visibleNotifications.length === 0 ? (
                    <EmptyMessage message="아직 받은 알림이 없습니다. 구독한 모집 시작 또는 리마인더가 생기면 여기에 쌓입니다." />
                  ) : (
                    visibleNotifications.map((notification) => (
                      <NotificationCard
                        key={notification.id}
                        notification={notification}
                        busy={readingNotificationId === notification.id}
                        onMarkRead={readNotification}
                      />
                    ))
                  )}
                  <div className="flex flex-col gap-3 border-t border-[#d9eaf6] pt-4 sm:flex-row sm:items-center sm:justify-between">
                    <p className="text-sm font-semibold text-[#4b6f8b]">
                      {notificationPageNumber + 1} / {notificationTotalPages} 페이지
                    </p>
                    <div className="flex items-center gap-2">
                      <button
                        className="swim-action inline-flex h-10 items-center justify-center gap-2 rounded-lg border border-[#c8def0] bg-white px-3 text-sm font-semibold text-[#28516f] hover:border-[#0284c7] hover:text-[#0369a1] disabled:cursor-not-allowed disabled:opacity-45"
                        onClick={() => loadNotificationPage(notificationPageNumber - 1)}
                        disabled={notificationPageLoading || !notificationPage || notificationPage.first}
                        type="button"
                      >
                        <ChevronLeft size={16} aria-hidden />
                        이전
                      </button>
                      <button
                        className="swim-action inline-flex h-10 items-center justify-center gap-2 rounded-lg border border-[#c8def0] bg-white px-3 text-sm font-semibold text-[#28516f] hover:border-[#0284c7] hover:text-[#0369a1] disabled:cursor-not-allowed disabled:opacity-45"
                        onClick={() => loadNotificationPage(notificationPageNumber + 1)}
                        disabled={notificationPageLoading || !notificationPage || notificationPage.last}
                        type="button"
                      >
                        다음
                        <ChevronRight size={16} aria-hidden />
                      </button>
                    </div>
                  </div>
                </div>
              </section>
            </div>

            <aside className="swim-rise swim-rise-delay-1 space-y-5">
              <section className="swim-card-motion rounded-lg border border-[#c8def0] bg-white shadow-sm">
                <div className="border-b border-[#d9eaf6] px-5 py-4">
                  <h2 className="text-lg font-semibold">계정 요약</h2>
                </div>
                <div className="space-y-4 px-5 py-5 text-sm">
                  <SummaryRow icon={Mail} label="이메일" value={data.user.email} />
                  <SummaryRow icon={UserCircle} label="사용자 ID" value={data.user.id.toString()} />
                  <SummaryRow
                    icon={Bell}
                    label="알림 사용"
                    value={data.user.notificationEnabled ? "사용 중" : "꺼짐"}
                  />
                  <SummaryRow
                    icon={Smartphone}
                    label="푸시 토큰"
                    value={data.user.fcmTokenRegistered ? "등록됨" : "미등록"}
                  />
                  <SummaryRow
                    icon={CalendarClock}
                    label="최근 로그인"
                    value={data.user.lastLoginAt ? formatDateTime(data.user.lastLoginAt) : "-"}
                  />
                </div>
              </section>

              <section className="swim-card-motion overflow-hidden rounded-[24px] border border-[#075985] bg-[linear-gradient(135deg,#102033_0%,#075985_100%)] text-white shadow-[0_18px_45px_rgba(3,105,161,0.18)]">
                <div className="space-y-4 px-5 py-5">
                  <div>
                    <p className="text-xs font-semibold uppercase tracking-[0.28em] text-[#b7e2d8]">
                      Account Health
                    </p>
                    <h2 className="mt-2 text-xl font-semibold">개인 알림 준비 상태</h2>
                  </div>
                  <div className="grid gap-3">
                    <HealthPill
                      label="푸시 준비"
                      value={data.user.fcmTokenRegistered ? "완료" : "미설정"}
                    />
                    <HealthPill
                      label="활성 기기"
                      value={`${data.metrics.activeDeviceCount}대`}
                    />
                    <HealthPill
                      label="읽지 않은 알림"
                      value={`${data.metrics.unreadNotificationCount}개`}
                    />
                  </div>
                  <p className="text-sm leading-6 text-[#dbece7]">
                    대시보드에서 웹 푸시를 등록하고 수영장 모집 기간을 구독하면, 이 페이지에서 개인 현황을 한눈에 볼 수 있습니다.
                  </p>
                </div>
              </section>
            </aside>
          </div>
        ) : null}
      </div>
      </div>

      {editingSubscription ? (
        <SubscriptionEditModal
          form={editingSubscription}
          busy={savingSubscriptionId === editingSubscription.subscriptionId}
          onChange={setEditingSubscription}
          onClose={closeSubscriptionEditor}
          onSubmit={submitSubscriptionEdit}
          validationShakeKey={editValidationShakeKey}
          invalidDateField={editInvalidDateField}
          validationMessage={editValidationMessage}
          onDateFieldChange={(field) => {
            setLastEditedSubscriptionDateField(field);
            setEditInvalidDateField(null);
            setEditValidationMessage(null);
          }}
        />
      ) : null}
      {deletingSubscription ? (
        <SubscriptionDeleteConfirmModal
          subscription={deletingSubscription}
          busy={removingSubscriptionId === deletingSubscription.id}
          onClose={closeSubscriptionDeleteConfirm}
          onConfirm={removeSubscription}
        />
      ) : null}
    </main>
  );
}

function LoadingState() {
  return (
    <div className="swim-rise space-y-5">
      <div className="h-56 animate-pulse rounded-[28px] border border-[#c8def0] bg-white" />
      <div className="grid gap-5 lg:grid-cols-2">
        <div className="h-80 animate-pulse rounded-lg border border-[#c8def0] bg-white" />
        <div className="h-80 animate-pulse rounded-lg border border-[#c8def0] bg-white" />
      </div>
    </div>
  );
}

function LoginRequiredState({ onLogin }: { onLogin: () => void }) {
  return (
    <section className="swim-rise swim-card-motion mx-auto max-w-2xl rounded-[28px] border border-[#c8def0] bg-white px-6 py-8 text-center shadow-[0_18px_45px_rgba(3,105,161,0.08)]">
      <div className="mx-auto grid size-16 place-items-center rounded-2xl bg-[#e0f2fe] text-[#0369a1]">
        <UserCircle size={32} aria-hidden />
      </div>
      <h2 className="mt-5 text-2xl font-semibold">로그인 후 마이 페이지를 볼 수 있습니다</h2>
      <p className="mt-3 text-sm leading-6 text-[#4b6f8b]">
        구독 현황, 최근 알림, PUSH 기기 상태는 로그인한 사용자 기준으로 저장됩니다.
      </p>
      <div className="mt-6 flex flex-col items-center justify-center gap-3 sm:flex-row">
        <button
          className="swim-action inline-flex h-11 items-center justify-center gap-2 rounded-lg bg-[#075985] px-5 text-sm font-semibold text-white hover:bg-[#0c4a6e]"
          onClick={onLogin}
          type="button"
        >
          <LogIn size={17} aria-hidden />
          Google 로그인
        </button>
        <Link
          href="/"
          className="swim-action inline-flex h-11 items-center justify-center rounded-lg border border-[#b8d7ec] bg-white px-5 text-sm font-semibold text-[#28516f] hover:border-[#0284c7] hover:text-[#0369a1]"
        >
          대시보드로 돌아가기
        </Link>
      </div>
    </section>
  );
}

function ErrorState({ onRetry }: { onRetry: () => void }) {
  return (
    <section className="swim-rise swim-card-motion mx-auto max-w-2xl rounded-[28px] border border-[#c8def0] bg-white px-6 py-8 text-center shadow-[0_18px_45px_rgba(3,105,161,0.08)]">
      <h2 className="text-2xl font-semibold">마이 페이지를 불러오지 못했습니다</h2>
      <p className="mt-3 text-sm leading-6 text-[#4b6f8b]">
        잠시 후 다시 시도하거나 대시보드에서 백엔드 연결 상태를 확인해 주세요.
      </p>
      <div className="mt-6 flex flex-col items-center justify-center gap-3 sm:flex-row">
        <button
          className="swim-action inline-flex h-11 items-center justify-center gap-2 rounded-lg bg-[#075985] px-5 text-sm font-semibold text-white hover:bg-[#0c4a6e]"
          onClick={onRetry}
          type="button"
        >
          <RefreshCw size={16} aria-hidden />
          다시 불러오기
        </button>
        <Link
          href="/"
          className="swim-action inline-flex h-11 items-center justify-center rounded-lg border border-[#b8d7ec] bg-white px-5 text-sm font-semibold text-[#28516f] hover:border-[#0284c7] hover:text-[#0369a1]"
        >
          대시보드 보기
        </Link>
      </div>
    </section>
  );
}

function PageNotice({
  message,
  tone,
}: {
  message: string;
  tone: "info" | "error";
}) {
  return (
    <div className="border-b border-[#c8def0] bg-white">
      <div className="mx-auto max-w-7xl px-5 py-3">
        <p
          className={`rounded-lg px-4 py-3 text-sm font-medium ${
            tone === "info"
              ? "bg-[#e0f2fe] text-[#0369a1]"
              : "bg-[#fff0ed] text-[#bf4b3e]"
          }`}
        >
          {message}
        </p>
      </div>
    </div>
  );
}

function MetricCard({
  icon: Icon,
  label,
  value,
  tone,
}: {
  icon: LucideIcon;
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
    <div className="swim-card-motion rounded-2xl border border-[#d9eaf6] bg-[#f6fbff] px-4 py-4">
      <div className={`mb-4 grid size-10 place-items-center rounded-xl ${toneClass}`}>
        <Icon size={18} aria-hidden />
      </div>
      <p className="text-sm text-[#4b6f8b]">{label}</p>
      <p className="mt-1 text-2xl font-semibold text-[#102033]">{value}</p>
    </div>
  );
}

function SummaryRow({
  icon: Icon,
  label,
  value,
}: {
  icon: LucideIcon;
  label: string;
  value: string;
}) {
  return (
    <div className="flex items-start gap-3">
      <Icon className="mt-0.5 shrink-0 text-[#4b6f8b]" size={16} aria-hidden />
      <div className="min-w-0">
        <p className="text-xs font-semibold text-[#4b6f8b]">{label}</p>
        <p className="break-words text-[#28516f]">{value}</p>
      </div>
    </div>
  );
}

function HealthPill({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex items-center justify-between gap-3 rounded-xl border border-white/15 bg-white/8 px-3 py-3">
      <span className="text-sm text-[#dbece7]">{label}</span>
      <span className="text-sm font-semibold">{value}</span>
    </div>
  );
}

function SubscriptionCard({
  subscription,
  onEdit,
  onDelete,
  editBusy,
  deleteBusy,
}: {
  subscription: Subscription;
  onEdit: (subscription: Subscription) => void;
  onDelete: (subscription: Subscription) => void;
  editBusy: boolean;
  deleteBusy: boolean;
}) {
  const event = subscription.event;
  const poolName = event?.poolName ?? subscription.pool.name;
  const busy = editBusy || deleteBusy;
  const canEdit = event && event.status !== "CLOSED";

  return (
    <article className="swim-row-motion rounded-2xl border border-[#d9eaf6] bg-[#f6fbff] px-4 py-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="space-y-2">
          <div className="flex flex-wrap items-center gap-2">
            {event ? <EventStatusBadge status={event.status} /> : null}
            <p className="text-sm font-semibold text-[#102033]">{poolName}</p>
          </div>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          {canEdit ? (
            <button
              className="swim-action inline-flex h-9 items-center justify-center rounded-lg border border-[#b8d7ec] bg-white px-3 text-sm font-semibold text-[#28516f] hover:border-[#0284c7] hover:text-[#0369a1] disabled:opacity-50"
              onClick={() => onEdit(subscription)}
              disabled={busy}
              type="button"
            >
              기간 수정
            </button>
          ) : null}
          <button
            className="swim-action inline-flex h-9 items-center justify-center gap-2 rounded-lg border border-[#fecaca] bg-white px-3 text-sm font-semibold text-[#b91c1c] hover:border-[#ef4444] hover:bg-[#fff1f2] disabled:opacity-50"
            onClick={() => onDelete(subscription)}
            disabled={busy || !event}
            type="button"
          >
            <Trash2 size={15} aria-hidden />
            {deleteBusy ? "해제 중..." : "구독 해제"}
          </button>
        </div>
      </div>
      <p className="mt-3 text-base font-semibold text-[#28516f]">
        {event?.title ?? "기간 정보가 없는 구독"}
      </p>
      {event ? (
        <p className="mt-2 text-sm leading-6 text-[#4b6f8b]">
          {formatDateTime(event.registrationStartsAt)} - {formatDateTime(event.registrationEndsAt)}
        </p>
      ) : null}
      {event?.noticeUrl ? (
        <a
          className="swim-action mt-3 inline-flex h-9 items-center justify-center gap-2 rounded-lg border border-[#b8d7ec] bg-white px-3 text-sm font-semibold text-[#28516f] hover:border-[#0284c7] hover:text-[#0369a1]"
          href={event.noticeUrl}
          target="_blank"
          rel="noreferrer"
        >
          원문 보기
          <ExternalLink size={15} aria-hidden />
        </a>
      ) : null}
      <p className="mt-2 text-xs text-[#7c8982]">구독 생성 {formatDateTime(subscription.createdAt)}</p>
    </article>
  );
}

function SubscriptionEditModal({
  form,
  busy,
  onChange,
  onClose,
  onSubmit,
  validationShakeKey,
  invalidDateField,
  validationMessage,
  onDateFieldChange,
}: {
  form: SubscriptionEditForm;
  busy: boolean;
  onChange: (next: SubscriptionEditForm) => void;
  onClose: () => void;
  onSubmit: (event: FormEvent<HTMLFormElement>) => void;
  validationShakeKey: number;
  invalidDateField: SubscriptionDateField | null;
  validationMessage: string | null;
  onDateFieldChange: (field: SubscriptionDateField) => void;
}) {
  function dateInputClass(field: SubscriptionDateField) {
    const invalid = invalidDateField === field;
    return invalid
      ? "swim-shake w-full rounded-xl border border-[#ef4444] bg-[#fff1f2] px-3 py-3 text-sm text-[#17201d] outline-none transition focus:border-[#dc2626] focus:ring-2 focus:ring-[#fecaca]"
      : "w-full rounded-xl border border-[#cdd5cf] bg-white px-3 py-3 text-sm text-[#17201d] outline-none transition focus:border-[#0f766e]";
  }

  return (
    <div className="fixed inset-0 z-50 grid place-items-center bg-black/35 px-4" role="dialog" aria-modal="true">
      <div className="w-full max-w-lg rounded-[28px] border border-[#d8ddd5] bg-white shadow-[0_20px_60px_rgba(23,32,29,0.18)]">
        <div className="flex items-start justify-between gap-4 border-b border-[#e3e7e1] px-5 py-5">
          <div>
            <p className="text-xs font-semibold uppercase tracking-[0.24em] text-[#0f766e]">Edit Subscription</p>
            <h2 className="mt-2 text-xl font-semibold">구독 기간 수정</h2>
            <p className="mt-2 text-sm leading-6 text-[#66746d]">
              {form.poolName} 구독만 수정됩니다. 다른 사용자의 구독 기간은 바뀌지 않습니다.
            </p>
          </div>
          <button
            className="inline-flex h-9 min-w-14 shrink-0 items-center justify-center whitespace-nowrap rounded-lg border border-[#cdd5cf] px-3 text-sm font-semibold text-[#31413b] transition hover:border-[#0f766e] hover:text-[#0f766e] disabled:opacity-50"
            onClick={onClose}
            disabled={busy}
            type="button"
          >
            닫기
          </button>
        </div>
        <form className="space-y-5 px-5 py-5" onSubmit={onSubmit}>
          <label className="block space-y-2">
            <span className="text-sm font-semibold text-[#31413b]">구독명</span>
            <input
              className="w-full rounded-xl border border-[#cdd5cf] bg-white px-3 py-3 text-sm text-[#17201d] outline-none transition focus:border-[#0f766e]"
              value={form.title}
              onChange={(event) => onChange({ ...form, title: event.target.value })}
              disabled={busy}
              maxLength={120}
            />
          </label>
          <div className="grid gap-4 sm:grid-cols-2">
            <label className="block space-y-2">
              <span className="text-sm font-semibold text-[#31413b]">시작 시각</span>
              <input
                key={`startsAt-${invalidDateField === "startsAt" ? validationShakeKey : 0}`}
                className={dateInputClass("startsAt")}
                type="datetime-local"
                value={form.startsAt}
                onChange={(event) => {
                  onDateFieldChange("startsAt");
                  onChange({ ...form, startsAt: event.target.value });
                }}
                disabled={busy}
                aria-invalid={invalidDateField === "startsAt"}
              />
            </label>
            <label className="block space-y-2">
              <span className="text-sm font-semibold text-[#31413b]">종료 시각</span>
              <input
                key={`endsAt-${invalidDateField === "endsAt" ? validationShakeKey : 0}`}
                className={dateInputClass("endsAt")}
                type="datetime-local"
                value={form.endsAt}
                onChange={(event) => {
                  onDateFieldChange("endsAt");
                  onChange({ ...form, endsAt: event.target.value });
                }}
                disabled={busy}
                aria-invalid={invalidDateField === "endsAt"}
              />
            </label>
          </div>
          {invalidDateField ? (
            <div className="rounded-xl border border-[#fecaca] bg-[#fff1f2] px-3 py-3 text-sm text-[#b91c1c]">
              <p className="font-semibold">올바른 날짜를 입력하세요.</p>
              {validationMessage ? <p className="mt-1 leading-5">{validationMessage}</p> : null}
            </div>
          ) : null}
          <p className="rounded-2xl bg-[#f7f8f4] px-4 py-4 text-sm leading-6 text-[#66746d]">
            공지 파싱 결과가 잘못됐을 때 직접 기간을 보정할 수 있습니다. 저장 후에는 수정한 기간 기준으로 리마인더와 시작 알림이 계산됩니다.
          </p>
          <div className="flex flex-col-reverse gap-3 sm:flex-row sm:justify-end">
            <button
              className="inline-flex h-11 items-center justify-center rounded-lg border border-[#cdd5cf] bg-white px-5 text-sm font-semibold text-[#31413b] transition hover:border-[#0f766e] hover:text-[#0f766e] disabled:opacity-50"
              onClick={onClose}
              disabled={busy}
              type="button"
            >
              취소
            </button>
            <button
              className="inline-flex h-11 items-center justify-center rounded-lg bg-[#0f766e] px-5 text-sm font-semibold text-white transition hover:bg-[#0b5f59] disabled:opacity-50"
              disabled={busy}
              type="submit"
            >
              {busy ? "저장 중..." : "기간 저장"}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}

function SubscriptionDeleteConfirmModal({
  subscription,
  busy,
  onClose,
  onConfirm,
}: {
  subscription: Subscription;
  busy: boolean;
  onClose: () => void;
  onConfirm: (subscription: Subscription) => void;
}) {
  const event = subscription.event;
  const poolName = event?.poolName ?? subscription.pool.name;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-[#102033]/45 px-4 py-6 backdrop-blur-sm">
      <div className="w-full max-w-xl overflow-hidden rounded-[28px] border border-[#c8def0] bg-white shadow-[0_24px_80px_rgba(15,23,42,0.22)]">
        <div className="flex items-start justify-between gap-4 border-b border-[#d9eaf6] px-5 py-5">
          <div>
            <p className="text-xs font-semibold uppercase tracking-[0.22em] text-[#b91c1c]">
              Unsubscribe
            </p>
            <h2 className="mt-2 text-xl font-semibold text-[#102033]">이 구독을 해제할까요?</h2>
            <p className="mt-2 text-sm leading-6 text-[#4b6f8b]">
              해제하면 이 모집 기간에 대한 리마인더와 시작 알림을 더 이상 받지 않습니다.
            </p>
          </div>
          <button
            className="inline-flex h-9 min-w-14 shrink-0 items-center justify-center whitespace-nowrap rounded-lg border border-[#c8def0] px-3 text-sm font-semibold text-[#28516f] hover:border-[#0284c7] hover:text-[#0369a1] disabled:opacity-50"
            onClick={onClose}
            disabled={busy}
            type="button"
          >
            닫기
          </button>
        </div>
        <div className="space-y-4 px-5 py-5">
          <div className="rounded-2xl border border-[#d9eaf6] bg-[#f6fbff] px-4 py-4">
            <div className="flex flex-wrap items-center gap-2">
              {event ? <EventStatusBadge status={event.status} /> : null}
              <p className="text-sm font-semibold text-[#102033]">{poolName}</p>
            </div>
            <p className="mt-3 text-base font-semibold text-[#28516f]">
              {event?.title ?? "기간 정보가 없는 구독"}
            </p>
            {event ? (
              <p className="mt-2 text-sm leading-6 text-[#4b6f8b]">
                {formatDateTime(event.registrationStartsAt)} - {formatDateTime(event.registrationEndsAt)}
              </p>
            ) : null}
            <p className="mt-2 text-xs text-[#7c8982]">
              구독 생성 {formatDateTime(subscription.createdAt)}
            </p>
          </div>
          <div className="flex flex-col-reverse gap-3 sm:flex-row sm:justify-end">
            <button
              className="inline-flex h-11 items-center justify-center rounded-lg border border-[#c8def0] bg-white px-5 text-sm font-semibold text-[#28516f] hover:border-[#0284c7] hover:text-[#0369a1] disabled:opacity-50"
              onClick={onClose}
              disabled={busy}
              type="button"
            >
              취소
            </button>
            <button
              className="inline-flex h-11 items-center justify-center gap-2 rounded-lg bg-[#b91c1c] px-5 text-sm font-semibold text-white hover:bg-[#991b1b] disabled:opacity-50"
              onClick={() => onConfirm(subscription)}
              disabled={busy || !event}
              type="button"
            >
              <Trash2 size={16} aria-hidden />
              {busy ? "해제 중..." : "구독 해제"}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}

function NotificationCard({
  notification,
  busy,
  onMarkRead,
}: {
  notification: InAppNotification;
  busy: boolean;
  onMarkRead: (notificationId: number) => void;
}) {
  return (
    <article className="rounded-2xl border border-[#e3e7e1] bg-[#fafbf8] px-4 py-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="flex flex-wrap items-center gap-2">
          <NotificationStatusBadge status={notification.status} />
        <span
          className={`rounded-full px-2.5 py-1 text-xs font-semibold ${
            notification.readAt
              ? "bg-[#f0f1ef] text-[#66746d]"
              : "bg-[#fff0ed] text-[#bf4b3e]"
          }`}
        >
          {notification.readAt ? "읽음" : "안 읽음"}
        </span>
        </div>
        <button
          className="inline-flex h-9 items-center justify-center rounded-lg border border-[#cdd5cf] bg-white px-3 text-sm font-semibold text-[#31413b] transition hover:border-[#0f766e] hover:text-[#0f766e] disabled:cursor-not-allowed disabled:opacity-50"
          onClick={() => onMarkRead(notification.id)}
          disabled={busy || Boolean(notification.readAt)}
          type="button"
        >
          {notification.readAt ? "읽음 완료" : busy ? "처리 중..." : "읽음 처리"}
        </button>
      </div>
      <p className="mt-3 text-base font-semibold text-[#17201d]">{notification.title}</p>
      <p className="mt-2 text-sm leading-6 text-[#31413b]">{notification.message}</p>
      <div className="mt-3 space-y-1 text-xs text-[#66746d]">
        <p>
          {notification.poolName} · {notification.eventTitle}
        </p>
        <p>생성 {formatDateTime(notification.createdAt)}</p>
        {notification.sentAt ? <p>전송 {formatDateTime(notification.sentAt)}</p> : null}
      </div>
      {notification.noticeUrl ? (
        <a
          className="mt-3 inline-flex h-9 items-center justify-center gap-2 rounded-lg border border-[#cdd5cf] bg-white px-3 text-sm font-semibold text-[#31413b] transition hover:border-[#0f766e] hover:text-[#0f766e]"
          href={notification.noticeUrl}
          target="_blank"
          rel="noreferrer"
        >
          원문 보기
          <ExternalLink size={15} aria-hidden />
        </a>
      ) : null}
    </article>
  );
}

function EventStatusBadge({ status }: { status: EventStatus }) {
  const className = {
    UPCOMING: "bg-[#edf7f5] text-[#0f766e]",
    OPEN: "bg-[#fff2e2] text-[#946123]",
    CLOSED: "bg-[#f0f1ef] text-[#66746d]",
  }[status];

  return (
    <span className={`rounded-full px-2.5 py-1 text-xs font-semibold ${className}`}>
      {eventStatusLabel(status)}
    </span>
  );
}

function NotificationStatusBadge({ status }: { status: NotificationStatus }) {
  const className = {
    QUEUED: "bg-[#fff2e2] text-[#946123]",
    SENDING: "bg-[#e8f5fb] text-[#075985]",
    SENT: "bg-[#edf7f5] text-[#0f766e]",
    FAILED: "bg-[#fff0ed] text-[#bf4b3e]",
  }[status];

  return (
    <span className={`rounded-full px-2.5 py-1 text-xs font-semibold ${className}`}>
      {notificationStatusLabel(status)}
    </span>
  );
}

function EmptyMessage({ message }: { message: string }) {
  return (
    <p className="rounded-2xl border border-dashed border-[#cdd5cf] px-4 py-5 text-sm leading-6 text-[#66746d]">
      {message}
    </p>
  );
}

function initialLetter(value: string) {
  return value.trim().slice(0, 1).toUpperCase() || "U";
}

function toDateTimeLocalValue(date: Date) {
  const offset = date.getTimezoneOffset();
  return new Date(date.getTime() - offset * 60000).toISOString().slice(0, 16);
}

function currentTimeMillis() {
  return Date.now();
}

function createInitialNotificationPage(data: MyPageData): NotificationPage {
  const totalElements = data.metrics.notificationCount;
  const totalPages = Math.ceil(totalElements / MY_PAGE_NOTIFICATION_PAGE_SIZE);

  return {
    content: data.notifications.slice(0, MY_PAGE_NOTIFICATION_PAGE_SIZE),
    page: 0,
    size: MY_PAGE_NOTIFICATION_PAGE_SIZE,
    totalElements,
    totalPages,
    first: true,
    last: totalPages <= 1,
    unreadElements: data.metrics.unreadNotificationCount,
  };
}

function applyNotificationPageMetrics(data: MyPageData, page: NotificationPage): MyPageData {
  return {
    ...data,
    metrics: {
      ...data.metrics,
      notificationCount: page.totalElements,
      unreadNotificationCount: page.unreadElements,
    },
  };
}

function applyNotificationUpdate(data: MyPageData, updated: InAppNotification, wasUnread: boolean): MyPageData {
  const notifications = data.notifications.map((notification) =>
    notification.id === updated.id ? updated : notification,
  );
  const unreadNotificationCount =
    wasUnread && updated.readAt != null
      ? Math.max(0, data.metrics.unreadNotificationCount - 1)
      : data.metrics.unreadNotificationCount;

  return {
    ...data,
    notifications,
    metrics: {
      ...data.metrics,
      unreadNotificationCount,
    },
  };
}

function applyNotificationPageUpdate(
  page: NotificationPage,
  updated: InAppNotification,
  wasUnread: boolean,
): NotificationPage {
  const content = page.content.map((notification) =>
    notification.id === updated.id ? updated : notification,
  );
  const unreadElements =
    wasUnread && updated.readAt != null ? Math.max(0, page.unreadElements - 1) : page.unreadElements;

  return {
    ...page,
    content,
    unreadElements,
  };
}

function removeSubscriptionFromMyPage(data: MyPageData, removed: Subscription): MyPageData {
  const subscriptions = data.subscriptions.filter((subscription) => subscription.id !== removed.id);
  const removedStatus = removed.event?.status;
  const upcomingSubscriptionCount =
    removedStatus === "UPCOMING"
      ? Math.max(0, data.metrics.upcomingSubscriptionCount - 1)
      : data.metrics.upcomingSubscriptionCount;
  const openSubscriptionCount =
    removedStatus === "OPEN"
      ? Math.max(0, data.metrics.openSubscriptionCount - 1)
      : data.metrics.openSubscriptionCount;

  return {
    ...data,
    subscriptions,
    metrics: {
      ...data.metrics,
      subscriptionCount: Math.max(0, data.metrics.subscriptionCount - 1),
      upcomingSubscriptionCount,
      openSubscriptionCount,
    },
  };
}

function getErrorMessage(error: unknown, fallback: string) {
  if (error instanceof Error && error.message) {
    return error.message;
  }
  return fallback;
}
