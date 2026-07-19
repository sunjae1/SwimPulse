import type {EventStatus, RegistrationEvent, Subscription} from '../api/types';

const MINUTE_MS = 60 * 1000;
export const SEOUL_TIME_ZONE = 'Asia/Seoul';

export function formatDateTime(value: string | null | undefined) {
  if (!value) {
    return '-';
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return '-';
  }
  return date.toLocaleString('ko-KR', {
    timeZone: SEOUL_TIME_ZONE,
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  });
}

export function formatShortPeriod(startsAt: string, endsAt: string) {
  return `${formatDateTime(startsAt)} - ${formatDateTime(endsAt)}`;
}

export function eventStatusLabel(status: EventStatus) {
  if (status === 'OPEN') {
    return '접수 중';
  }
  if (status === 'CLOSED') {
    return '마감';
  }
  return '예정';
}

export function isEventClosed(event: RegistrationEvent | null | undefined) {
  if (!event) {
    return true;
  }
  return event.status === 'CLOSED' || new Date(event.registrationEndsAt).getTime() < Date.now();
}

export function subscriptionTitle(subscription: Subscription) {
  return subscription.event?.title || subscription.pool.name;
}

export function subscriptionKeyFromEvent(event: RegistrationEvent) {
  return `${event.poolId}:${event.noticeRegistrationPeriodId ?? 'manual'}:${event.registrationStartsAt}:${event.registrationEndsAt}`;
}

export function subscriptionKey(subscription: Subscription) {
  if (!subscription.event) {
    return `subscription:${subscription.id}`;
  }
  return subscriptionKeyFromEvent(subscription.event);
}

export function isOcrInProgress(status: string | undefined) {
  return status === 'PENDING' || status === 'PROCESSING';
}

export function isPastPeriod(startsAt: string, endsAt: string) {
  return new Date(endsAt).getTime() < Date.now() - MINUTE_MS;
}

export function shiftPeriodToCurrentMonth(startsAt: string, endsAt: string) {
  const start = new Date(startsAt);
  const end = new Date(endsAt);
  const now = new Date();
  const shiftedStart = new Date(start);
  const shiftedEnd = new Date(end);
  shiftedStart.setFullYear(now.getFullYear(), now.getMonth(), start.getDate());
  shiftedEnd.setFullYear(now.getFullYear(), now.getMonth(), end.getDate());

  if (shiftedEnd.getTime() <= shiftedStart.getTime()) {
    shiftedEnd.setMonth(shiftedStart.getMonth() + 1);
  }

  return {
    registrationStartsAt: shiftedStart.toISOString(),
    registrationEndsAt: shiftedEnd.toISOString(),
  };
}

export function toInputDateTime(value: string) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return '';
  }
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: SEOUL_TIME_ZONE,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).formatToParts(date);
  const part = (type: string) => parts.find(item => item.type === type)?.value ?? '00';
  return `${part('year')}-${part('month')}-${part('day')} ${part('hour')}:${part('minute')}`;
}

export function fromInputDateTime(value: string) {
  const normalized = value.trim().replace(/\./g, '-').replace('T', ' ');
  const match = normalized.match(/^(\d{4})-(\d{1,2})-(\d{1,2})\s+(\d{1,2}):(\d{2})$/);
  if (!match) {
    return null;
  }

  const [, yearText, monthText, dayText, hourText, minuteText] = match;
  const year = Number(yearText);
  const month = Number(monthText);
  const day = Number(dayText);
  const hour = Number(hourText);
  const minute = Number(minuteText);
  if (
    month < 1 ||
    month > 12 ||
    day < 1 ||
    day > 31 ||
    hour < 0 ||
    hour > 23 ||
    minute < 0 ||
    minute > 59
  ) {
    return null;
  }

  const date = new Date(Date.UTC(year, month - 1, day, hour - 9, minute));
  if (Number.isNaN(date.getTime())) {
    return null;
  }
  if (toInputDateTime(date.toISOString()) !== `${yearText}-${monthText.padStart(2, '0')}-${dayText.padStart(2, '0')} ${hourText.padStart(2, '0')}:${minuteText}`) {
    return null;
  }
  return date.toISOString();
}
