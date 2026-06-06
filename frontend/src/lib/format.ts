import type { EventStatus, NotificationStatus } from "@/lib/types";

export function formatDateTime(value: string) {
  return new Intl.DateTimeFormat("ko-KR", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  }).format(new Date(value));
}

export function formatDate(value: string) {
  return new Intl.DateTimeFormat("ko-KR", {
    month: "2-digit",
    day: "2-digit",
  }).format(new Date(value));
}

export function formatFullDate(value: string) {
  return new Intl.DateTimeFormat("ko-KR", {
    year: "numeric",
    month: "long",
    day: "numeric",
  }).format(new Date(value));
}

export function formatTimeLeft(value: string) {
  const diff = new Date(value).getTime() - Date.now();
  if (diff <= 0) {
    return "진행 중";
  }

  const minutes = Math.ceil(diff / 60000);
  if (minutes < 60) {
    return `${minutes}분 후`;
  }

  const hours = Math.floor(minutes / 60);
  const rest = minutes % 60;
  if (hours < 24) {
    return rest === 0 ? `${hours}시간 후` : `${hours}시간 ${rest}분 후`;
  }

  return `${Math.ceil(hours / 24)}일 후`;
}

export function eventStatusLabel(status: EventStatus) {
  return {
    UPCOMING: "예정",
    OPEN: "시작",
    CLOSED: "종료",
  }[status];
}

export function notificationStatusLabel(status: NotificationStatus) {
  return {
    QUEUED: "대기",
    SENT: "전송",
    FAILED: "실패",
  }[status];
}
