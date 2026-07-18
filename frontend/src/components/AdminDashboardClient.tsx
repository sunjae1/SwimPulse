"use client";

import {
  Activity,
  AlertTriangle,
  BarChart3,
  Bell,
  CheckCircle2,
  Clock3,
  Database,
  FileSearch,
  ListChecks,
  Lock,
  MapPin,
  RefreshCw,
  RotateCcw,
  ShieldCheck,
  Trash2,
  Users,
  Waves,
} from "lucide-react";
import type { LucideIcon } from "lucide-react";
import type { ReactNode } from "react";
import { useEffect, useRef, useState } from "react";
import { AppNavigation } from "@/components/AppNavigation";
import {
  adminApprovePoolAddRequest,
  adminCorrectPoolHomepage,
  adminPostprocessPoolAddRequestHomepage,
  adminPostprocessPoolAddRequestImage,
  adminPostprocessPoolAddRequestNotices,
  adminRejectPoolAddRequest,
  adminRequeueFailedNotification,
  adminRequeueStaleNotifications,
  ApiRequestError,
  getAdminActionLogs,
  getAdminDashboard,
  getAdminOperationsDashboard,
  getAdminServiceDashboard,
  getPools,
} from "@/lib/api";
import { formatDateTime } from "@/lib/format";
import type { AdminActionLog, AdminActionResultStatus, AdminDashboard, AdminMetricCount, InAppNotification, Pool, PoolAddRequest } from "@/lib/types";

const OPERATIONS_POLL_MS = 5000;
const SERVICE_POLL_MS = 60000;

const statusLabels: Record<string, string> = {
  QUEUED: "대기",
  SENDING: "발송 중",
  SENT: "성공",
  FAILED: "실패",
  CANCELLED: "취소",
  CANDIDATE: "후보",
  VERIFIED: "검증됨",
  INACTIVE: "비활성",
  EXTRACTED: "기간 추출",
  LINK_ONLY: "링크만",
  NOT_REQUIRED: "불필요",
  PENDING: "대기",
  PROCESSING: "처리 중",
  COMPLETED: "완료",
  NO_PERIOD: "기간 없음",
  APPROVED: "승인됨",
  REJECTED: "반려",
  MERGED: "병합",
};

const actionTypeLabels: Record<string, string> = {
  REQUEUE_FAILED_NOTIFICATION: "실패 알림 재큐잉",
  REQUEUE_STALE_NOTIFICATIONS: "stale 알림 재큐잉",
  APPROVE_POOL_ADD_REQUEST: "시설 요청 승인",
  REJECT_POOL_ADD_REQUEST: "시설 요청 반려",
  POSTPROCESS_POOL_ADD_REQUEST: "시설 후처리",
  POSTPROCESS_POOL_ADD_REQUEST_HOMEPAGE: "홈페이지 재검증",
  POSTPROCESS_POOL_ADD_REQUEST_IMAGE: "이미지 보강",
  POSTPROCESS_POOL_ADD_REQUEST_NOTICES: "공지 스캔",
  CORRECT_POOL_HOMEPAGE: "수영장 홈페이지 교정",
};

const actionTypeOptions = Object.entries(actionTypeLabels).map(([value, label]) => ({
  value,
  label,
}));

type PendingAdminAction =
  | { kind: "requeue-stale"; title: string; description: string; confirmLabel: string; danger?: boolean }
  | { kind: "requeue-failed"; notification: InAppNotification; title: string; description: string; confirmLabel: string; danger?: boolean }
  | { kind: "approve-request"; request: PoolAddRequest; title: string; description: string; confirmLabel: string; danger?: boolean }
  | { kind: "reject-request"; request: PoolAddRequest; title: string; description: string; confirmLabel: string; danger?: boolean }
  | { kind: "postprocess-request"; request: PoolAddRequest; title: string; description: string; confirmLabel: string; danger?: boolean }
  | { kind: "correct-pool-homepage"; pool: Pool; name: string; homepageUrl: string; reason: string; title: string; description: string; confirmLabel: string; danger?: boolean };

type PostprocessStepKey = "homepage" | "image" | "notices";

type PostprocessProgress = {
  requestId: number;
  title: string;
  current: PostprocessStepKey | null;
  completed: PostprocessStepKey[];
  failed: PostprocessStepKey | null;
};

const postprocessStepLabels: Record<PostprocessStepKey, string> = {
  homepage: "공지사항 후보 재검증",
  image: "대표 이미지 보강",
  notices: "상세 공지 스캔",
};

const postprocessSteps: PostprocessStepKey[] = ["homepage", "image", "notices"];

export function AdminDashboardClient() {
  const [dashboard, setDashboard] = useState<AdminDashboard | null>(null);
  const [pools, setPools] = useState<Pool[]>([]);
  const [operationsGeneratedAt, setOperationsGeneratedAt] = useState<string | null>(null);
  const [serviceGeneratedAt, setServiceGeneratedAt] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [operationsRefreshing, setOperationsRefreshing] = useState(false);
  const [serviceRefreshing, setServiceRefreshing] = useState(false);
  const [actionBusy, setActionBusy] = useState(false);
  const [pendingAction, setPendingAction] = useState<PendingAdminAction | null>(null);
  const [postprocessProgress, setPostprocessProgress] = useState<PostprocessProgress | null>(null);
  const [actionLogs, setActionLogs] = useState<AdminActionLog[]>([]);
  const [actionLogResultFilter, setActionLogResultFilter] = useState<AdminActionResultStatus | "ALL">("ALL");
  const [actionLogTypeFilter, setActionLogTypeFilter] = useState("");
  const actionLogResultFilterRef = useRef<AdminActionResultStatus | "ALL">("ALL");
  const actionLogTypeFilterRef = useRef("");
  const [actionLogsRefreshing, setActionLogsRefreshing] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  useEffect(() => {
    actionLogResultFilterRef.current = actionLogResultFilter;
    actionLogTypeFilterRef.current = actionLogTypeFilter;
  }, [actionLogResultFilter, actionLogTypeFilter]);

  function getActionLogQuery() {
    const resultFilter = actionLogResultFilterRef.current;
    const actionType = actionLogTypeFilterRef.current.trim();
    return {
      resultStatus: resultFilter === "ALL" ? undefined : resultFilter,
      actionType: actionType || undefined,
      limit: 20,
    };
  }

  async function fetchFilteredActionLogs() {
    return getAdminActionLogs(getActionLogQuery());
  }

  async function loadDashboard(mode: "initial" | "refresh" = "initial") {
    if (mode === "refresh") {
      setRefreshing(true);
    } else {
      setLoading(true);
    }
    setErrorMessage(null);
    try {
      const [result, filteredLogs, poolList] = await Promise.all([
        getAdminDashboard(),
        fetchFilteredActionLogs(),
        getPools(),
      ]);
      setDashboard({
        ...result,
        recentActionLogs: filteredLogs,
      });
      setActionLogs(filteredLogs);
      setPools(poolList);
      setOperationsGeneratedAt(result.generatedAt);
      setServiceGeneratedAt(result.generatedAt);
    } catch (error) {
      setDashboard(null);
      setErrorMessage(adminErrorMessage(error));
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }

  async function refreshOperations() {
    setOperationsRefreshing(true);
    try {
      const [result, filteredLogs] = await Promise.all([
        getAdminOperationsDashboard(),
        fetchFilteredActionLogs(),
      ]);
      setDashboard((current) => current
        ? {
            ...current,
            notifications: result.notifications,
            deliveryStats: result.deliveryStats,
            workers: result.workers,
            failedNotifications: result.failedNotifications,
            recentActionLogs: filteredLogs,
          }
        : current);
      setActionLogs(filteredLogs);
      setOperationsGeneratedAt(result.generatedAt);
    } catch (error) {
      setErrorMessage(adminErrorMessage(error));
    } finally {
      setOperationsRefreshing(false);
    }
  }

  async function refreshService() {
    setServiceRefreshing(true);
    try {
      const result = await getAdminServiceDashboard();
      setDashboard((current) => current
        ? {
            ...current,
            overview: result.overview,
            notices: result.notices,
            topSubscribedPools: result.topSubscribedPools,
            topSubscribedDistricts: result.topSubscribedDistricts,
            pendingPoolAddRequests: result.pendingPoolAddRequests,
            poolAddRequests: result.poolAddRequests,
          }
        : current);
      setServiceGeneratedAt(result.generatedAt);
    } catch (error) {
      setErrorMessage(adminErrorMessage(error));
    } finally {
      setServiceRefreshing(false);
    }
  }

  async function refreshActionLogs(showLoading = true) {
    if (showLoading) {
      setActionLogsRefreshing(true);
    }
    try {
      const result = await fetchFilteredActionLogs();
      setActionLogs(result);
    } catch (error) {
      setErrorMessage(adminErrorMessage(error));
    } finally {
      if (showLoading) {
        setActionLogsRefreshing(false);
      }
    }
  }

  async function confirmAction(note?: string) {
    if (!pendingAction) {
      return;
    }
    const action = pendingAction;
    setActionBusy(true);
    setNotice(null);
    try {
      if (action.kind === "postprocess-request") {
        setPendingAction(null);
        setActionBusy(false);
        setNotice(`${action.request.title} 후처리를 시작했습니다.`);
        await runPostprocessWithProgress(action.request);
        setNotice(`${action.request.title} 후처리를 완료했습니다.`);
        await Promise.all([refreshOperations(), refreshService()]);
        return;
      }
      if (action.kind === "requeue-stale") {
        const result = await adminRequeueStaleNotifications(50);
        setNotice(result.message);
        await refreshOperations();
      }
      if (action.kind === "requeue-failed") {
        const result = await adminRequeueFailedNotification(action.notification.id);
        setNotice(`알림 #${result.id}을 재큐잉했습니다.`);
        await refreshOperations();
      }
      if (action.kind === "approve-request") {
        const result = await adminApprovePoolAddRequest(action.request.id);
        setNotice(`${result.title} 요청을 승인했습니다.`);
        await refreshService();
      }
      if (action.kind === "reject-request") {
        const result = await adminRejectPoolAddRequest(action.request.id, note?.trim() || "관리자 반려");
        setNotice(`${result.title} 요청을 반려했습니다.`);
        await refreshService();
      }
      if (action.kind === "correct-pool-homepage") {
        const result = await adminCorrectPoolHomepage(action.pool.id, {
          name: action.name,
          homepageUrl: action.homepageUrl,
          reason: action.reason,
        });
        setPools(await getPools());
        setNotice(`${result.pool.name} 홈페이지를 revision ${result.homepageRevision}로 교정했습니다. 검토 대상 구독 ${result.reviewRequiredSubscriptions}건, 취소 알림 ${result.cancelledNotifications}건. 다음 공지 확인에서 새 홈페이지 기준으로 경로를 탐색합니다.`);
        await Promise.all([refreshOperations(), refreshService(), refreshActionLogs(false)]);
      }
      setPendingAction(null);
    } catch (error) {
      setNotice(getActionErrorMessage(error));
    } finally {
      setActionBusy(false);
    }
  }

  async function runPostprocessWithProgress(request: PoolAddRequest) {
    setPostprocessProgress({
      requestId: request.id,
      title: request.title,
      current: "homepage",
      completed: [],
      failed: null,
    });
    try {
      await runPostprocessStep(request, "homepage", adminPostprocessPoolAddRequestHomepage);
      await runPostprocessStep(request, "image", adminPostprocessPoolAddRequestImage);
      await runPostprocessStep(request, "notices", adminPostprocessPoolAddRequestNotices);
      setPostprocessProgress((current) => current
        ? { ...current, current: null, failed: null }
        : current);
    } catch (error) {
      throw error;
    }
  }

  async function runPostprocessStep(
    request: PoolAddRequest,
    step: PostprocessStepKey,
    task: (requestId: number) => Promise<unknown>,
  ) {
    setPostprocessProgress((current) => current
      ? { ...current, current: step, failed: null }
      : current);
    try {
      await task(request.id);
      setPostprocessProgress((current) => current
        ? { ...current, completed: [...current.completed, step] }
        : current);
    } catch (error) {
      setPostprocessProgress((current) => current
        ? { ...current, failed: step }
        : current);
      throw error;
    }
  }

  useEffect(() => {
    const startId = window.setTimeout(() => {
      void loadDashboard();
    }, 0);
    const operationsIntervalId = window.setInterval(() => {
      void refreshOperations();
    }, OPERATIONS_POLL_MS);
    const serviceIntervalId = window.setInterval(() => {
      void refreshService();
    }, SERVICE_POLL_MS);
    return () => {
      window.clearTimeout(startId);
      window.clearInterval(operationsIntervalId);
      window.clearInterval(serviceIntervalId);
    };
    // Polling callbacks read latest action-log filters through refs, so the intervals stay stable.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <main className="min-h-screen bg-[#f4f9fb] text-[#102337]">
      <section className="border-b border-[#cfe1ee] bg-white/90 px-5 py-4 shadow-sm backdrop-blur">
        <div className="mx-auto flex w-full max-w-7xl flex-col gap-4 md:flex-row md:items-center md:justify-between">
          <div className="flex items-center gap-3">
            <div className="flex size-11 items-center justify-center rounded-2xl bg-[#075985] text-white shadow-sm">
              <ShieldCheck size={23} />
            </div>
            <div>
              <p className="text-xs font-bold uppercase tracking-[0.22em] text-[#0284c7]">Admin Dashboard</p>
              <h1 className="text-2xl font-bold text-[#0f2f43]">SwimPulse 운영 대시보드</h1>
            </div>
          </div>
          <AppNavigation showAdmin />
        </div>
      </section>

      <section className="mx-auto flex w-full max-w-7xl flex-col gap-5 px-5 py-6">
        <div className="flex flex-col gap-3 rounded-2xl border border-[#cfe1ee] bg-white p-5 shadow-sm md:flex-row md:items-center md:justify-between">
          <div>
            <p className="text-sm font-semibold text-[#0f766e]">운영 자동 갱신</p>
            <p className="mt-1 text-sm text-[#5f7484]">
              queue/worker/알림은 5초마다, 공지/OCR/랭킹/시설 요청은 60초마다 갱신합니다. 수동 새로고침은 전체를 다시 조회합니다.
            </p>
          </div>
          <button
            type="button"
            onClick={() => void loadDashboard("refresh")}
            disabled={refreshing}
            className="inline-flex h-10 items-center justify-center gap-2 rounded-xl border border-[#9cc7df] bg-[#e8f5fb] px-4 text-sm font-bold text-[#075985] transition hover:bg-[#d7edf8] disabled:cursor-not-allowed disabled:opacity-60"
          >
            <RefreshCw size={16} className={refreshing ? "animate-spin" : ""} />
            전체 새로고침
          </button>
        </div>

        {notice ? (
          <div className="rounded-2xl border border-[#bfdbfe] bg-[#eff6ff] px-4 py-3 text-sm font-semibold text-[#075985]">
            {notice}
          </div>
        ) : null}

        {dashboard ? (
          <PoolHomepageCorrectionPanel
            pools={pools}
            busy={actionBusy}
            onSubmit={(input) => setPendingAction({
              kind: "correct-pool-homepage",
              ...input,
              title: `${input.pool.name} 홈페이지 교정`,
              description: "기존 공지 경로를 비활성화하고 영향받는 구독을 검토 상태로 전환합니다. 아직 발송되지 않은 접수 알림은 취소됩니다.",
              confirmLabel: "교정 및 재검증",
              danger: true,
            })}
          />
        ) : null}

        {loading ? (
          <StatusPanel icon={Activity} title="관리자 데이터를 불러오는 중" description="잠시만 기다려 주세요." />
        ) : errorMessage ? (
          <StatusPanel icon={Lock} title="관리자 대시보드를 볼 수 없습니다" description={errorMessage} danger />
        ) : dashboard ? (
            <DashboardContent
              dashboard={dashboard}
              operationsGeneratedAt={operationsGeneratedAt}
              serviceGeneratedAt={serviceGeneratedAt}
              postprocessProgress={postprocessProgress}
              operationsRefreshing={operationsRefreshing}
              serviceRefreshing={serviceRefreshing}
              actionLogs={actionLogs}
              actionLogResultFilter={actionLogResultFilter}
              actionLogTypeFilter={actionLogTypeFilter}
              actionLogsRefreshing={actionLogsRefreshing}
              onActionLogResultFilterChange={setActionLogResultFilter}
              onActionLogTypeFilterChange={setActionLogTypeFilter}
              onRefreshActionLogs={refreshActionLogs}
              onRefreshOperations={refreshOperations}
              onRefreshService={refreshService}
              onAction={setPendingAction}
            />
        ) : null}
      </section>

      {pendingAction ? (
        <AdminActionModal
          action={pendingAction}
          busy={actionBusy}
          onConfirm={(note) => void confirmAction(note)}
          onClose={() => setPendingAction(null)}
        />
      ) : null}
    </main>
  );
}

function DashboardContent({
  dashboard,
  operationsGeneratedAt,
  serviceGeneratedAt,
  postprocessProgress,
  operationsRefreshing,
  serviceRefreshing,
  actionLogs,
  actionLogResultFilter,
  actionLogTypeFilter,
  actionLogsRefreshing,
  onActionLogResultFilterChange,
  onActionLogTypeFilterChange,
  onRefreshActionLogs,
  onRefreshOperations,
  onRefreshService,
  onAction,
}: {
  dashboard: AdminDashboard;
  operationsGeneratedAt: string | null;
  serviceGeneratedAt: string | null;
  postprocessProgress: PostprocessProgress | null;
  operationsRefreshing: boolean;
  serviceRefreshing: boolean;
  actionLogs: AdminActionLog[];
  actionLogResultFilter: AdminActionResultStatus | "ALL";
  actionLogTypeFilter: string;
  actionLogsRefreshing: boolean;
  onActionLogResultFilterChange: (value: AdminActionResultStatus | "ALL") => void;
  onActionLogTypeFilterChange: (value: string) => void;
  onRefreshActionLogs: () => Promise<void>;
  onRefreshOperations: () => Promise<void>;
  onRefreshService: () => Promise<void>;
  onAction: (action: PendingAdminAction) => void;
}) {
  const failedNotifications = metricValue(dashboard.notifications.byStatus, "FAILED");
  const queuedNotifications = metricValue(dashboard.notifications.byStatus, "QUEUED");
  const failedSources = metricValue(dashboard.notices.sourcesByStatus, "FAILED");
  const pendingOcr = metricValue(dashboard.notices.noticesByOcrStatus, "PENDING");
  const processingOcr = metricValue(dashboard.notices.noticesByOcrStatus, "PROCESSING");
  const totalNoticeSources = metricTotal(dashboard.notices.sourcesByStatus);
  const totalExtractionNotices = metricTotal(dashboard.notices.noticesByExtractionStatus);

  return (
    <>
      <section className="grid gap-4 md:grid-cols-2 xl:grid-cols-5">
        <MetricCard icon={Users} label="사용자" value={dashboard.overview.users} tone="blue" />
        <MetricCard icon={Waves} label="수영장" value={dashboard.overview.pools} tone="teal" />
        <MetricCard icon={Bell} label="구독" value={dashboard.overview.subscriptions} tone="blue" />
        <MetricCard icon={Activity} label="이벤트" value={dashboard.overview.events} tone="teal" />
        <MetricCard icon={Database} label="활성 기기" value={dashboard.overview.activeDevices} tone="blue" />
      </section>

      <AdminSectionHeader
        title="운영 상태"
        description="알림 queue, worker, 발송 상태처럼 짧은 주기로 변하는 운영 지표입니다."
        tone="blue"
        interval="5초 polling"
      />

      <section className="grid gap-4 xl:grid-cols-[1.2fr_0.8fr]">
        <Panel
          title="알림 Queue 상태"
          description={`마지막 queue/worker 집계: ${operationsGeneratedAt ? formatDateTime(operationsGeneratedAt) : "-"}`}
          icon={Bell}
          action={(
            <div className="flex items-center gap-2">
              <PanelRefreshButton label="운영 새로고침" busy={operationsRefreshing} onClick={onRefreshOperations} tone="blue" />
              {dashboard.notifications.staleSending > 0 ? (
                <button
                  type="button"
                  className="inline-flex h-9 items-center justify-center gap-2 rounded-xl bg-[#dc2626] px-3 text-xs font-bold text-white transition hover:bg-[#b91c1c]"
                  onClick={() => onAction({
                    kind: "requeue-stale",
                    title: "stale SENDING 재큐잉",
                    description: "오래 SENDING 상태로 멈춘 알림을 최대 50건까지 다시 queue에 넣습니다.",
                    confirmLabel: "50건까지 재큐잉",
                    danger: true,
                  })}
                >
                  <RotateCcw size={14} />
                  stale 재큐잉
                </button>
              ) : null}
            </div>
          )}
        >
          <div className="grid gap-3 md:grid-cols-4">
            <CompactMetric label="Redis queue" value={dashboard.notifications.queueLength} strong />
            <CompactMetric label="전체 알림" value={dashboard.notifications.total} />
            <CompactMetric label="대기 알림" value={queuedNotifications} />
            <CompactMetric label="stale SENDING" value={dashboard.notifications.staleSending} danger={dashboard.notifications.staleSending > 0} />
          </div>
          <MetricTable rows={dashboard.notifications.byStatus} />
        </Panel>

        <Panel
          title="알림 발송 통계"
          description="사용자에게 실제 발송된 결과 기준"
          icon={CheckCircle2}
          action={<PanelRefreshButton label="운영 새로고침" busy={operationsRefreshing} onClick={onRefreshOperations} tone="blue" />}
        >
          <div className="grid gap-3 md:grid-cols-2">
            <CompactMetric label="성공" value={dashboard.deliveryStats.sent} />
            <CompactMetric label="실패" value={dashboard.deliveryStats.failed} danger={dashboard.deliveryStats.failed > 0} />
            <CompactMetric label="성공률" value={Math.round(dashboard.deliveryStats.successRate * 1000) / 10} suffix="%" />
            <CompactMetric label="실패율" value={Math.round(dashboard.deliveryStats.failureRate * 1000) / 10} suffix="%" danger={dashboard.deliveryStats.failureRate > 0.05} />
          </div>
        </Panel>
      </section>

      <section className="grid gap-4 xl:grid-cols-[0.9fr_1.1fr]">
        <Panel
          title="Worker 설정"
          description="현재 application properties 기준"
          icon={Activity}
          action={<PanelRefreshButton label="운영 새로고침" busy={operationsRefreshing} onClick={onRefreshOperations} tone="blue" />}
        >
          <dl className="grid gap-3 text-sm">
            <Definition label="알림 batch size" value={`${dashboard.workers.notificationBatchSize}개`} />
            <Definition label="알림 worker delay" value={`${dashboard.workers.notificationDelayMs}ms`} />
            <Definition label="stale sending 기준" value={`${dashboard.workers.notificationStaleSendingTimeoutMs}ms`} />
            <Definition label="event scheduler pool" value={`${dashboard.workers.eventSchedulerPoolSize}`} />
            <Definition label="event scheduler delay" value={`${dashboard.workers.eventSchedulerDelayMs}ms`} />
          </dl>
        </Panel>

        <Panel
          title="FAILED 알림 최근 10건"
          description="실패 알림은 단건 재큐잉만 허용합니다"
          icon={AlertTriangle}
          action={<PanelRefreshButton label="운영 새로고침" busy={operationsRefreshing} onClick={onRefreshOperations} tone="blue" />}
        >
          {dashboard.failedNotifications.length === 0 ? (
            <p className="text-sm text-[#6b7f8d]">실패 알림이 없습니다.</p>
          ) : (
            <div className="space-y-2">
              {dashboard.failedNotifications.map((notification) => (
                <div key={notification.id} className="grid gap-3 rounded-xl border border-[#f3d4d4] bg-[#fff8f8] p-3 md:grid-cols-[1fr_auto] md:items-center">
                  <div className="min-w-0">
                    <p className="truncate text-sm font-bold text-[#7f1d1d]">#{notification.id} {notification.poolName}</p>
                    <p className="mt-1 line-clamp-2 text-xs text-[#8a5c5c]">{notification.failureReason ?? notification.title}</p>
                  </div>
                  <button
                    type="button"
                    className="inline-flex h-9 items-center justify-center gap-2 rounded-xl border border-[#fecaca] bg-white px-3 text-xs font-bold text-[#b91c1c] transition hover:bg-[#fff1f2]"
                    onClick={() => onAction({
                      kind: "requeue-failed",
                      notification,
                      title: `알림 #${notification.id} 재큐잉`,
                      description: "FAILED 알림 1건을 다시 QUEUED로 바꾸고 Redis queue에 넣습니다.",
                      confirmLabel: "재큐잉",
                      danger: true,
                    })}
                  >
                    <RotateCcw size={14} />
                    재큐잉
                  </button>
                </div>
              ))}
            </div>
          )}
        </Panel>
      </section>

      <section className="grid gap-4">
        <Panel
          title="관리자 작업 로그"
          description="최근 운영 액션 성공/실패 이력입니다. 필터는 로그 API만 다시 조회합니다."
          icon={Lock}
          action={<PanelRefreshButton label="로그 새로고침" busy={actionLogsRefreshing} onClick={onRefreshActionLogs} tone="blue" />}
        >
          <AdminActionLogFilters
            resultFilter={actionLogResultFilter}
            actionTypeFilter={actionLogTypeFilter}
            onResultFilterChange={onActionLogResultFilterChange}
            onActionTypeFilterChange={onActionLogTypeFilterChange}
            onApply={onRefreshActionLogs}
            busy={actionLogsRefreshing}
          />
          <AdminActionLogTable logs={actionLogs} />
        </Panel>
      </section>

      <AdminSectionHeader
        title="서비스 데이터"
        description="공지 source, OCR, 랭킹, 시설 추가 요청처럼 서비스 품질을 판단하는 집계입니다."
        tone="teal"
        interval="60초 polling"
      />

      <section className="grid gap-4 xl:grid-cols-3">
        <Panel
          title="공지 Source"
          description={`공지/OCR/랭킹 집계: ${serviceGeneratedAt ? formatDateTime(serviceGeneratedAt) : "-"}`}
          icon={FileSearch}
          action={<PanelRefreshButton label="서비스 새로고침" busy={serviceRefreshing} onClick={onRefreshService} tone="teal" />}
        >
          <div className="mb-3 grid gap-3 md:grid-cols-2">
            <CompactMetric label="FAILED source" value={failedSources} danger={failedSources > 0} />
            <CompactMetric label="전체 source" value={totalNoticeSources} />
          </div>
          <MetricTable rows={dashboard.notices.sourcesByStatus} />
        </Panel>

        <Panel
          title="공지 추출"
          description="pool_notices extraction status"
          icon={ListChecks}
          action={<PanelRefreshButton label="서비스 새로고침" busy={serviceRefreshing} onClick={onRefreshService} tone="teal" />}
        >
          <div className="mb-3 grid gap-3 md:grid-cols-2">
            <CompactMetric label="전체 상세 공지" value={dashboard.notices.totalNotices} />
            <CompactMetric label="추출 상태 합계" value={totalExtractionNotices} />
          </div>
          <MetricTable rows={dashboard.notices.noticesByExtractionStatus} />
        </Panel>

        <Panel
          title="OCR 상태"
          description="이미지 공지 백그라운드 분석"
          icon={AlertTriangle}
          action={<PanelRefreshButton label="서비스 새로고침" busy={serviceRefreshing} onClick={onRefreshService} tone="teal" />}
        >
          <div className="mb-3 grid gap-3 md:grid-cols-2">
            <CompactMetric label="PENDING" value={pendingOcr} danger={pendingOcr > 0} />
            <CompactMetric label="PROCESSING" value={processingOcr} />
          </div>
          <MetricTable rows={dashboard.notices.noticesByOcrStatus} />
        </Panel>
      </section>

      <section className="grid gap-4 xl:grid-cols-2">
        <RankingPanel title="인기 수영장" refreshAction={<PanelRefreshButton label="서비스 새로고침" busy={serviceRefreshing} onClick={onRefreshService} tone="teal" />} rows={dashboard.topSubscribedPools.map((pool) => ({
          id: pool.poolId,
          label: pool.poolName,
          count: pool.subscriptionCount,
        }))} />
        <RankingPanel title="지역별 인기 수영장" refreshAction={<PanelRefreshButton label="서비스 새로고침" busy={serviceRefreshing} onClick={onRefreshService} tone="teal" />} rows={dashboard.topSubscribedDistricts.map((district, index) => ({
          id: index,
          label: district.district,
          count: district.subscriptionCount,
        }))} icon={MapPin} />
      </section>

      <section className="grid gap-4 xl:grid-cols-[1.2fr_0.8fr]">
        <Panel
          title="시설 추가 요청"
          description="사용자 요청을 검토하고 승인 후 후처리합니다"
          icon={Waves}
          action={<PanelRefreshButton label="서비스 새로고침" busy={serviceRefreshing} onClick={onRefreshService} tone="teal" />}
        >
          {postprocessProgress ? (
            <div className="mb-4">
              <PostprocessProgressPanel progress={postprocessProgress} compact />
            </div>
          ) : null}
          {dashboard.poolAddRequests.length === 0 ? (
            <p className="text-sm text-[#6b7f8d]">최근 시설 추가 요청이 없습니다.</p>
          ) : (
            <div className="space-y-3">
              {dashboard.poolAddRequests.map((request) => (
                <PoolAddRequestRow key={request.id} request={request} onAction={onAction} />
              ))}
            </div>
          )}
        </Panel>

        <Panel title="운영 체크포인트" description="자동 갱신 화면에서 우선 확인할 항목" icon={ShieldCheck}>
          <ul className="space-y-3 text-sm text-[#486173]">
            <ChecklistItem danger={dashboard.notifications.queueLength > 0}>
              Redis queue가 계속 증가하면 worker 처리량 또는 FCM 발송 지연을 확인합니다.
            </ChecklistItem>
            <ChecklistItem danger={dashboard.notifications.staleSending > 0}>
              stale SENDING이 있으면 worker 중단 또는 처리 중 예외 가능성을 확인합니다.
            </ChecklistItem>
            <ChecklistItem danger={failedNotifications > 0}>
              FAILED 알림은 토큰 없음, FCM 실패, 서버 설정 문제를 분리해서 봅니다.
            </ChecklistItem>
            <ChecklistItem danger={failedSources > 0}>
              FAILED notice source가 늘면 해당 pool의 공지 경로를 재검증해야 합니다.
            </ChecklistItem>
          </ul>
        </Panel>
      </section>
    </>
  );
}

function PoolAddRequestRow({
  request,
  onAction,
}: {
  request: PoolAddRequest;
  onAction: (action: PendingAdminAction) => void;
}) {
  const address = request.roadAddress ?? request.address ?? "주소 없음";
  return (
    <div className="rounded-xl border border-[#d9e8f1] bg-[#f8fbfd] p-3">
      <div className="flex flex-col gap-2 md:flex-row md:items-start md:justify-between">
        <div className="min-w-0">
          <div className="flex flex-wrap items-center gap-2">
            <p className="truncate text-sm font-bold text-[#17344a]">#{request.id} {request.title}</p>
            <span className={`rounded-full px-2 py-1 text-xs font-bold ${request.status === "PENDING" ? "bg-[#fff2e2] text-[#946123]" : request.status === "APPROVED" ? "bg-[#e4f7f3] text-[#0f766e]" : "bg-[#edf2f7] text-[#486173]"}`}>
              {statusLabels[request.status]}
            </span>
          </div>
          <p className="mt-1 text-xs text-[#5f7484]">{address}</p>
          <p className="mt-1 text-xs text-[#6b7f8d]">요청자: {request.requestedByEmail}</p>
          {request.adminNote ? (
            <p className="mt-2 rounded-lg border border-[#fed7aa] bg-[#fff7ed] px-3 py-2 text-xs font-semibold text-[#9a3412]">
              관리자 메모: {request.adminNote}
            </p>
          ) : null}
        </div>
        <div className="flex flex-wrap gap-2">
          {request.status === "PENDING" ? (
            <>
              <button
                type="button"
                className="inline-flex h-9 items-center justify-center gap-2 rounded-xl bg-[#0f766e] px-3 text-xs font-bold text-white hover:bg-[#0b5f59]"
                onClick={() => onAction({
                  kind: "approve-request",
                  request,
                  title: `${request.title} 승인`,
                  description: "요청 후보를 실제 pools DB에 추가하거나 기존 시설과 매칭합니다.",
                  confirmLabel: "승인",
                })}
              >
                <CheckCircle2 size={14} />
                승인
              </button>
              <button
                type="button"
                className="inline-flex h-9 items-center justify-center gap-2 rounded-xl border border-[#fecaca] bg-white px-3 text-xs font-bold text-[#b91c1c] hover:bg-[#fff1f2]"
                onClick={() => onAction({
                  kind: "reject-request",
                  request,
                  title: `${request.title} 반려`,
                  description: "이 시설 추가 요청을 반려합니다. 반려 사유는 관리자 메모로 저장됩니다.",
                  confirmLabel: "반려",
                  danger: true,
                })}
              >
                <Trash2 size={14} />
                반려
              </button>
            </>
          ) : null}
          {request.approvedPoolId ? (
            <button
              type="button"
              className="inline-flex h-9 items-center justify-center gap-2 rounded-xl border border-[#9cc7df] bg-white px-3 text-xs font-bold text-[#075985] hover:bg-[#eff8fc]"
              onClick={() => onAction({
                kind: "postprocess-request",
                request,
                title: `${request.title} 후처리`,
                description: "공지사항 후보 재검증, 대표 이미지 보강, 상세 공지 스캔을 실행합니다. 외부 사이트 호출이 포함됩니다.",
                confirmLabel: "후처리 실행",
              })}
            >
              <RefreshCw size={14} />
              승인 후 후처리
            </button>
          ) : null}
        </div>
      </div>
    </div>
  );
}

function AdminActionLogTable({ logs }: { logs: AdminActionLog[] }) {
  if (logs.length === 0) {
    return <p className="text-sm text-[#6b7f8d]">아직 관리자 작업 로그가 없습니다.</p>;
  }
  return (
    <div className="overflow-hidden rounded-xl border border-[#d9e8f1]">
      <table className="w-full border-collapse text-sm">
        <thead className="bg-[#f8fbfd] text-left text-xs uppercase tracking-[0.12em] text-[#6b7f8d]">
          <tr>
            <th className="px-3 py-2">결과</th>
            <th className="px-3 py-2">작업</th>
            <th className="px-3 py-2">대상</th>
            <th className="px-3 py-2">관리자</th>
            <th className="px-3 py-2">시각</th>
            <th className="px-3 py-2">메시지</th>
          </tr>
        </thead>
        <tbody>
          {logs.map((log) => (
            <tr key={log.id} className="border-t border-[#e5eef4]">
              <td className="px-3 py-2">
                <span className={`inline-flex rounded-full px-2 py-1 text-xs font-bold ${
                  log.resultStatus === "SUCCESS"
                    ? "bg-[#dcfce7] text-[#15803d]"
                    : "bg-[#fee2e2] text-[#b91c1c]"
                }`}>
                  {log.resultStatus === "SUCCESS" ? "성공" : "실패"}
                </span>
              </td>
              <td className="px-3 py-2 font-semibold text-[#17344a]">
                {actionTypeLabels[log.actionType] ?? log.actionType}
              </td>
              <td className="px-3 py-2 text-[#486173]">
                {log.targetType}{log.targetId ? ` #${log.targetId}` : ""}
              </td>
              <td className="px-3 py-2 text-[#486173]">{log.adminEmail ?? "-"}</td>
              <td className="px-3 py-2 text-[#486173]">{formatDateTime(log.createdAt)}</td>
              <td className="max-w-[260px] truncate px-3 py-2 text-[#6b7f8d]" title={log.message ?? ""}>
                {log.message ?? "-"}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function AdminActionLogFilters({
  resultFilter,
  actionTypeFilter,
  busy,
  onResultFilterChange,
  onActionTypeFilterChange,
  onApply,
}: {
  resultFilter: AdminActionResultStatus | "ALL";
  actionTypeFilter: string;
  busy: boolean;
  onResultFilterChange: (value: AdminActionResultStatus | "ALL") => void;
  onActionTypeFilterChange: (value: string) => void;
  onApply: () => Promise<void>;
}) {
  return (
    <div className="mb-3 grid gap-2 rounded-xl border border-[#d9e8f1] bg-[#f8fbfd] p-3 md:grid-cols-[180px_1fr_auto] md:items-end">
      <label className="text-xs font-bold uppercase tracking-[0.12em] text-[#6b7f8d]">
        결과
        <select
          value={resultFilter}
          onChange={(event) => onResultFilterChange(event.target.value as AdminActionResultStatus | "ALL")}
          className="mt-1 h-10 w-full rounded-xl border border-[#cfe1ee] bg-white px-3 text-sm font-semibold normal-case tracking-normal text-[#17344a] outline-none"
        >
          <option value="ALL">전체</option>
          <option value="SUCCESS">성공</option>
          <option value="FAILED">실패</option>
        </select>
      </label>
      <label className="text-xs font-bold uppercase tracking-[0.12em] text-[#6b7f8d]">
        작업
        <select
          value={actionTypeFilter}
          onChange={(event) => onActionTypeFilterChange(event.target.value)}
          className="mt-1 h-10 w-full rounded-xl border border-[#cfe1ee] bg-white px-3 text-sm font-semibold normal-case tracking-normal text-[#17344a] outline-none"
        >
          <option value="">전체 작업</option>
          {actionTypeOptions.map((option) => (
            <option key={option.value} value={option.value}>
              {option.label}
            </option>
          ))}
        </select>
      </label>
      <button
        type="button"
        onClick={() => void onApply()}
        disabled={busy}
        className="inline-flex h-10 items-center justify-center gap-2 rounded-xl bg-[#075985] px-4 text-sm font-bold text-white transition hover:bg-[#06486f] disabled:opacity-60"
      >
        <RefreshCw size={15} className={busy ? "animate-spin" : ""} />
        필터 적용
      </button>
    </div>
  );
}

function PostprocessProgressPanel({ progress, compact = false }: { progress: PostprocessProgress; compact?: boolean }) {
  return (
    <section className={compact ? "rounded-xl border border-[#bae6fd] bg-[#f0f9ff] p-4" : "rounded-2xl border border-[#bae6fd] bg-white p-5 shadow-sm"}>
      <div className="flex flex-col gap-2 md:flex-row md:items-center md:justify-between">
        <div>
          <p className="text-xs font-bold uppercase tracking-[0.18em] text-[#0284c7]">Postprocess Progress</p>
          <h2 className="mt-1 text-lg font-bold text-[#0f2f43]">#{progress.requestId} {progress.title}</h2>
        </div>
        <span className={`inline-flex w-fit items-center rounded-full px-3 py-1 text-xs font-bold ${
          progress.failed
            ? "bg-[#fee2e2] text-[#b91c1c]"
            : progress.current
              ? "bg-[#e0f2fe] text-[#075985]"
              : "bg-[#dcfce7] text-[#15803d]"
        }`}>
          {progress.failed
            ? `${postprocessStepLabels[progress.failed]} 실패`
            : progress.current
              ? `${postprocessStepLabels[progress.current]} 진행 중`
              : "후처리 완료"}
        </span>
      </div>
      <div className="mt-4 grid gap-3 md:grid-cols-3">
        {postprocessSteps.map((step) => {
          const completed = progress.completed.includes(step);
          const active = progress.current === step && !progress.failed;
          const failed = progress.failed === step;
          return (
            <div
              key={step}
              className={`rounded-xl border p-3 ${
                failed
                  ? "border-[#fecaca] bg-[#fff1f2]"
                  : completed
                    ? "border-[#bbf7d0] bg-[#f0fdf4]"
                    : active
                      ? "border-[#bae6fd] bg-[#f0f9ff]"
                      : "border-[#d9e8f1] bg-[#f8fbfd]"
              }`}
            >
              <div className="flex items-center gap-2">
                <span className={`flex size-7 items-center justify-center rounded-full ${
                  failed
                    ? "bg-[#dc2626] text-white"
                    : completed
                      ? "bg-[#16a34a] text-white"
                      : active
                        ? "bg-[#0284c7] text-white"
                        : "bg-[#e2edf3] text-[#5f7484]"
                }`}>
                  {failed ? (
                    <AlertTriangle size={15} />
                  ) : completed ? (
                    <CheckCircle2 size={15} />
                  ) : active ? (
                    <RefreshCw size={15} className="animate-spin" />
                  ) : (
                    <Clock3 size={15} />
                  )}
                </span>
                <div>
                  <p className="text-sm font-bold text-[#17344a]">{postprocessStepLabels[step]}</p>
                  <p className="text-xs text-[#6b7f8d]">
                    {failed ? "실패" : completed ? "완료" : active ? "진행 중" : "대기"}
                  </p>
                </div>
              </div>
            </div>
          );
        })}
      </div>
      {progress.failed ? (
        <p className="mt-3 text-sm font-semibold text-[#b91c1c]">
          실패한 단계에서 멈췄습니다. 원인을 확인한 뒤 같은 후처리 버튼을 다시 실행할 수 있습니다.
        </p>
      ) : null}
    </section>
  );
}

function RankingPanel({
  title,
  rows,
  icon = BarChart3,
  refreshAction,
}: {
  title: string;
  rows: Array<{ id: number; label: string; count: number }>;
  icon?: LucideIcon;
  refreshAction?: ReactNode;
}) {
  return (
    <Panel title={title} description="구독 수 기준 상위 10개" icon={icon} action={refreshAction}>
      {rows.length === 0 ? (
        <p className="text-sm text-[#6b7f8d]">아직 구독 데이터가 없습니다.</p>
      ) : (
        <ol className="space-y-2">
          {rows.map((row, index) => (
            <li
              key={`${title}-${row.id}`}
              className="flex items-center justify-between rounded-xl border border-[#d9e8f1] bg-[#f8fbfd] px-3 py-2"
            >
              <div className="min-w-0">
                <span className="mr-2 inline-flex size-6 items-center justify-center rounded-full bg-[#dff3f1] text-xs font-bold text-[#0f766e]">
                  {index + 1}
                </span>
                <span className="truncate text-sm font-semibold text-[#17344a]">{row.label}</span>
              </div>
              <span className="shrink-0 text-sm font-bold text-[#075985]">{row.count.toLocaleString()}개</span>
            </li>
          ))}
        </ol>
      )}
    </Panel>
  );
}

function AdminSectionHeader({
  title,
  description,
  interval,
  tone,
}: {
  title: string;
  description: string;
  interval: string;
  tone: "blue" | "teal";
}) {
  const toneClass = tone === "blue"
    ? "border-[#b7d7e8] bg-[#e8f5fb] text-[#075985]"
    : "border-[#b8e3dc] bg-[#e4f7f3] text-[#0f766e]";
  return (
    <section className={`rounded-2xl border px-5 py-4 ${toneClass}`}>
      <div className="flex flex-col gap-2 md:flex-row md:items-center md:justify-between">
        <div>
          <p className="text-xs font-bold uppercase tracking-[0.18em] opacity-80">{interval}</p>
          <h2 className="mt-1 text-xl font-extrabold">{title}</h2>
          <p className="mt-1 text-sm opacity-85">{description}</p>
        </div>
      </div>
    </section>
  );
}

function PanelRefreshButton({
  label,
  busy,
  onClick,
  tone,
}: {
  label: string;
  busy: boolean;
  onClick: () => Promise<void>;
  tone: "blue" | "teal";
}) {
  const toneClass = tone === "blue"
    ? "text-[#075985] hover:border-[#8fc4dd] hover:bg-[#eff8fc]"
    : "text-[#0f766e] hover:border-[#8fcfbd] hover:bg-[#effcf9]";
  return (
    <button
      type="button"
      aria-label={label}
      title={label}
      onClick={() => void onClick()}
      disabled={busy}
      className={`inline-flex size-9 items-center justify-center rounded-xl border border-[#cfe1ee] bg-white transition disabled:cursor-not-allowed disabled:opacity-60 ${toneClass}`}
    >
      <RefreshCw size={15} className={busy ? "animate-spin" : ""} />
    </button>
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
  value: number;
  tone: "blue" | "teal";
}) {
  const toneClass = tone === "blue" ? "bg-[#e8f5fb] text-[#075985]" : "bg-[#e4f7f3] text-[#0f766e]";
  return (
    <article className="rounded-2xl border border-[#cfe1ee] bg-white p-5 shadow-sm">
      <div className={`mb-4 flex size-10 items-center justify-center rounded-xl ${toneClass}`}>
        <Icon size={20} />
      </div>
      <p className="text-sm font-semibold text-[#5f7484]">{label}</p>
      <p className="mt-1 text-3xl font-bold text-[#102337]">{value.toLocaleString()}</p>
    </article>
  );
}

function Panel({
  title,
  description,
  icon: Icon,
  action,
  children,
}: {
  title: string;
  description: string;
  icon: LucideIcon;
  action?: ReactNode;
  children: ReactNode;
}) {
  return (
    <section className="rounded-2xl border border-[#cfe1ee] bg-white p-5 shadow-sm">
      <div className="mb-4 flex items-start justify-between gap-3">
        <div className="flex items-start gap-3">
          <div className="flex size-10 shrink-0 items-center justify-center rounded-xl bg-[#eff8fc] text-[#075985]">
            <Icon size={20} />
          </div>
          <div>
            <h2 className="text-lg font-bold text-[#102337]">{title}</h2>
            <p className="text-sm text-[#6b7f8d]">{description}</p>
          </div>
        </div>
        {action}
      </div>
      {children}
    </section>
  );
}

function MetricTable({ rows }: { rows: AdminMetricCount[] }) {
  return (
    <div className="mt-3 overflow-hidden rounded-xl border border-[#d9e8f1]">
      <table className="w-full border-collapse text-sm">
        <tbody>
          {rows.map((row) => (
            <tr key={row.name} className="border-b border-[#e5eef4] last:border-b-0">
              <th className="bg-[#f8fbfd] px-3 py-2 text-left font-semibold text-[#486173]">
                {statusLabels[row.name] ?? row.name}
              </th>
              <td className="px-3 py-2 text-right font-bold text-[#102337]">{row.count.toLocaleString()}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function CompactMetric({
  label,
  value,
  suffix = "",
  strong = false,
  danger = false,
}: {
  label: string;
  value: number;
  suffix?: string;
  strong?: boolean;
  danger?: boolean;
}) {
  return (
    <div className={`rounded-xl border px-3 py-3 ${danger ? "border-[#fecaca] bg-[#fff7f7]" : "border-[#d9e8f1] bg-[#f8fbfd]"}`}>
      <p className="text-xs font-semibold text-[#6b7f8d]">{label}</p>
      <p className={`mt-1 text-2xl font-bold ${danger ? "text-[#dc2626]" : strong ? "text-[#075985]" : "text-[#102337]"}`}>
        {value.toLocaleString()}{suffix}
      </p>
    </div>
  );
}

function Definition({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex items-center justify-between gap-4 rounded-xl bg-[#f8fbfd] px-3 py-2">
      <dt className="font-semibold text-[#5f7484]">{label}</dt>
      <dd className="font-bold text-[#102337]">{value}</dd>
    </div>
  );
}

function ChecklistItem({ children, danger }: { children: ReactNode; danger?: boolean }) {
  return (
    <li className="flex gap-3 rounded-xl border border-[#d9e8f1] bg-[#f8fbfd] p-3">
      <span className={`mt-1 size-2 rounded-full ${danger ? "bg-[#dc2626]" : "bg-[#0f766e]"}`} />
      <span>{children}</span>
    </li>
  );
}

function PoolHomepageCorrectionPanel({
  pools,
  busy,
  onSubmit,
}: {
  pools: Pool[];
  busy: boolean;
  onSubmit: (input: { pool: Pool; name: string; homepageUrl: string; reason: string }) => void;
}) {
  const [poolId, setPoolId] = useState("");
  const [name, setName] = useState("");
  const [homepageUrl, setHomepageUrl] = useState("");
  const [reason, setReason] = useState("잘못 연결된 홈페이지 출처를 올바른 시설 홈페이지로 교정했습니다.");
  const selectedPool = pools.find((pool) => pool.id.toString() === poolId) ?? pools[0] ?? null;
  const resolvedName = name || selectedPool?.name || "";
  const resolvedHomepageUrl = homepageUrl || selectedPool?.homepageUrl || "";

  function selectPool(nextPoolId: string) {
    const pool = pools.find((candidate) => candidate.id.toString() === nextPoolId);
    setPoolId(nextPoolId);
    setName(pool?.name ?? "");
    setHomepageUrl(pool?.homepageUrl ?? "");
  }

  return (
    <section className="rounded-2xl border border-[#f0caca] bg-white p-5 shadow-sm">
      <div className="flex items-start gap-3">
        <div className="flex size-10 shrink-0 items-center justify-center rounded-xl bg-[#fff1f2] text-[#be123c]">
          <AlertTriangle size={20} />
        </div>
        <div>
          <h2 className="text-lg font-bold text-[#102337]">수영장 홈페이지 교정</h2>
          <p className="mt-1 text-sm text-[#5f7484]">잘못 연결된 시설명·홈페이지를 수정하고 기존 출처와 영향 구독을 안전하게 검토 상태로 전환합니다.</p>
        </div>
      </div>
      <div className="mt-4 grid gap-3 lg:grid-cols-2">
        <label className="text-sm font-bold text-[#17344a]">
          수영장
          <select value={selectedPool?.id.toString() ?? ""} onChange={(event) => selectPool(event.target.value)} className="mt-2 h-11 w-full rounded-xl border border-[#d8e5ee] bg-[#f8fbfd] px-3 font-medium outline-none focus:border-[#0f766e]">
            {pools.map((pool) => <option key={pool.id} value={pool.id}>#{pool.id} {pool.name}</option>)}
          </select>
        </label>
        <label className="text-sm font-bold text-[#17344a]">
          시설명
          <input value={resolvedName} onChange={(event) => setName(event.target.value)} maxLength={255} className="mt-2 h-11 w-full rounded-xl border border-[#d8e5ee] bg-[#f8fbfd] px-3 font-medium outline-none focus:border-[#0f766e]" />
        </label>
        <label className="text-sm font-bold text-[#17344a] lg:col-span-2">
          새 홈페이지 전체 주소
          <input type="url" value={resolvedHomepageUrl} onChange={(event) => setHomepageUrl(event.target.value)} maxLength={255} placeholder="https://..." className="mt-2 h-11 w-full rounded-xl border border-[#d8e5ee] bg-[#f8fbfd] px-3 font-medium outline-none focus:border-[#0f766e]" />
        </label>
        <label className="text-sm font-bold text-[#17344a] lg:col-span-2">
          사용자 안내 사유
          <textarea value={reason} onChange={(event) => setReason(event.target.value)} rows={2} maxLength={500} className="mt-2 w-full resize-none rounded-xl border border-[#d8e5ee] bg-[#f8fbfd] px-3 py-2 font-medium outline-none focus:border-[#0f766e]" />
        </label>
      </div>
      <div className="mt-4 flex flex-col gap-3 border-t border-[#edf2f5] pt-4 sm:flex-row sm:items-center sm:justify-between">
        <p className="text-xs text-[#6b7f8d]">현재 revision {selectedPool?.homepageRevision ?? "-"}. 실행 후 이전 공지는 fallback에서 제외됩니다.</p>
        <button
          type="button"
          disabled={busy || !selectedPool || !resolvedName.trim() || !resolvedHomepageUrl.trim()}
          onClick={() => selectedPool && onSubmit({ pool: selectedPool, name: resolvedName.trim(), homepageUrl: resolvedHomepageUrl.trim(), reason: reason.trim() })}
          className="inline-flex h-11 items-center justify-center gap-2 rounded-xl bg-[#be123c] px-4 text-sm font-bold text-white transition hover:bg-[#9f1239] disabled:cursor-not-allowed disabled:opacity-50"
        >
          <RefreshCw size={16} />
          교정 검토
        </button>
      </div>
    </section>
  );
}

function StatusPanel({
  icon: Icon,
  title,
  description,
  danger = false,
}: {
  icon: LucideIcon;
  title: string;
  description: string;
  danger?: boolean;
}) {
  return (
    <section className="rounded-2xl border border-[#cfe1ee] bg-white p-8 text-center shadow-sm">
      <div className={`mx-auto mb-4 flex size-12 items-center justify-center rounded-2xl ${danger ? "bg-[#fff1f2] text-[#be123c]" : "bg-[#e8f5fb] text-[#075985]"}`}>
        <Icon size={24} />
      </div>
      <h2 className="text-xl font-bold text-[#102337]">{title}</h2>
      <p className="mt-2 text-sm text-[#6b7f8d]">{description}</p>
    </section>
  );
}

function AdminActionModal({
  action,
  busy,
  onConfirm,
  onClose,
}: {
  action: PendingAdminAction;
  busy: boolean;
  onConfirm: (note?: string) => void;
  onClose: () => void;
}) {
  const [note, setNote] = useState(action.kind === "reject-request" ? (action.request.adminNote ?? "") : "");
  const needsNote = action.kind === "reject-request";

  return (
    <div className="fixed inset-0 z-50 grid place-items-center bg-black/35 px-4" role="dialog" aria-modal="true">
      <div className="w-full max-w-md rounded-2xl border border-[#d8e5ee] bg-white shadow-xl">
        <div className="flex items-start gap-3 border-b border-[#e3edf3] px-5 py-4">
          <div className={`flex size-10 shrink-0 items-center justify-center rounded-xl ${action.danger ? "bg-[#fff1f2] text-[#be123c]" : "bg-[#e8f5fb] text-[#075985]"}`}>
            {action.danger ? <AlertTriangle size={20} /> : <Clock3 size={20} />}
          </div>
          <div>
            <h2 className="text-lg font-bold text-[#102337]">{action.title}</h2>
            <p className="mt-1 text-sm text-[#5f7484]">{action.description}</p>
          </div>
        </div>
        {needsNote ? (
          <div className="border-b border-[#e3edf3] px-5 py-4">
            <label className="text-sm font-bold text-[#17344a]" htmlFor="admin-reject-note">
              관리자 메모
            </label>
            <textarea
              id="admin-reject-note"
              value={note}
              onChange={(event) => setNote(event.target.value)}
              rows={4}
              maxLength={1000}
              placeholder="반려 사유를 입력하세요. 예: 이미 등록된 시설입니다."
              className="mt-2 w-full resize-none rounded-xl border border-[#d8e5ee] bg-[#f8fbfd] px-3 py-2 text-sm text-[#102337] outline-none transition focus:border-[#0f766e] focus:bg-white"
              disabled={busy}
            />
            <p className="mt-1 text-right text-xs text-[#6b7f8d]">{note.length}/1000</p>
          </div>
        ) : null}
        <div className="grid gap-2 px-5 py-5 sm:grid-cols-2">
          <button
            type="button"
            className="inline-flex h-11 items-center justify-center rounded-xl border border-[#cdd5cf] bg-white px-4 text-sm font-semibold text-[#31413b] transition hover:border-[#0f766e]"
            onClick={onClose}
            disabled={busy}
          >
            취소
          </button>
          <button
            type="button"
            className={`inline-flex h-11 items-center justify-center rounded-xl px-4 text-sm font-bold text-white transition disabled:opacity-60 ${action.danger ? "bg-[#dc2626] hover:bg-[#b91c1c]" : "bg-[#0f766e] hover:bg-[#0b5f59]"}`}
            onClick={() => onConfirm(needsNote ? note : undefined)}
            disabled={busy}
          >
            {busy ? "처리 중..." : action.confirmLabel}
          </button>
        </div>
      </div>
    </div>
  );
}

function metricValue(rows: AdminMetricCount[], name: string) {
  return rows.find((row) => row.name === name)?.count ?? 0;
}

function metricTotal(rows: AdminMetricCount[]) {
  return rows.reduce((total, row) => total + row.count, 0);
}

function adminErrorMessage(error: unknown) {
  if (error instanceof ApiRequestError) {
    if (error.status === 401) {
      return "로그인이 필요합니다. Google 로그인 후 다시 접근하세요.";
    }
    if (error.status === 403) {
      return "ADMIN 권한이 없습니다. app_users.role을 ADMIN으로 변경한 뒤 다시 로그인해야 합니다.";
    }
  }
  return "관리자 데이터를 불러오지 못했습니다.";
}

function getActionErrorMessage(error: unknown) {
  if (error instanceof ApiRequestError) {
    return error.message || `${error.status} 요청 실패`;
  }
  return "관리자 작업을 처리하지 못했습니다.";
}
