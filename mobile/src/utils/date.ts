import type {EventStatus, RegistrationEvent, Subscription} from '../api/types';

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
  const end = new Date(endsAt);
  return !Number.isNaN(end.getTime()) && end.getTime() <= Date.now();
}

export function shiftPeriodToNextAvailableMonth(
  startsAt: string,
  endsAt: string,
  now = new Date(),
) {
  const start = new Date(startsAt);
  const end = new Date(endsAt);
  if (Number.isNaN(start.getTime()) || Number.isNaN(end.getTime())) {
    throw new Error('모집 기간 날짜 형식이 올바르지 않습니다.');
  }

  const current = seoulDateTimeParts(now);
  const sourceStart = seoulDateTimeParts(start);
  const sourceEnd = seoulDateTimeParts(end);
  const sourceMonthOffset = Math.max(
    0,
    (sourceEnd.year - sourceStart.year) * 12 + sourceEnd.month - sourceStart.month,
  );
  const sourceStartsBeforeCurrentMonth =
    sourceStart.year < current.year ||
    (sourceStart.year === current.year && sourceStart.month < current.month);
  let targetStartMonth = normalizeYearMonth(
    current.year,
    current.month + (sourceStartsBeforeCurrentMonth ? 0 : 1),
  );

  for (let attempt = 0; attempt < 2; attempt += 1) {
    const targetEndMonth = normalizeYearMonth(
      targetStartMonth.year,
      targetStartMonth.month + sourceMonthOffset,
    );
    const shiftedStart = seoulDateTimeToIso(
      targetStartMonth.year,
      targetStartMonth.month,
      Math.min(sourceStart.day, daysInMonth(targetStartMonth.year, targetStartMonth.month)),
      sourceStart.hour,
      sourceStart.minute,
      sourceStart.second,
    );
    const shiftedEnd = seoulDateTimeToIso(
      targetEndMonth.year,
      targetEndMonth.month,
      Math.min(sourceEnd.day, daysInMonth(targetEndMonth.year, targetEndMonth.month)),
      sourceEnd.hour,
      sourceEnd.minute,
      sourceEnd.second,
    );

    if (new Date(shiftedEnd).getTime() > now.getTime()) {
      return {
        registrationStartsAt: shiftedStart,
        registrationEndsAt: shiftedEnd,
      };
    }
    targetStartMonth = normalizeYearMonth(targetStartMonth.year, targetStartMonth.month + 1);
  }

  throw new Error('미래 사용자 지정 모집 기간을 계산하지 못했습니다.');
}

export function formatMonthLabel(value: string, now = new Date()) {
  const target = seoulDateTimeParts(new Date(value));
  const current = seoulDateTimeParts(now);
  return target.year === current.year ? `${target.month}월` : `${target.year}년 ${target.month}월`;
}

export function formatMonthDescription(value: string, now = new Date()) {
  const target = seoulDateTimeParts(new Date(value));
  const current = seoulDateTimeParts(now);
  const monthOffset = (target.year - current.year) * 12 + target.month - current.month;
  const relative = monthOffset === 0 ? '이번 달' : monthOffset === 1 ? '다음 달' : `${monthOffset}개월 후`;
  return `${formatMonthLabel(value, now)}(${relative})`;
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

function seoulDateTimeParts(date: Date) {
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone: SEOUL_TIME_ZONE,
    year: 'numeric',
    month: 'numeric',
    day: 'numeric',
    hour: 'numeric',
    minute: 'numeric',
    second: 'numeric',
    hourCycle: 'h23',
  }).formatToParts(date);
  const value = (type: string) => Number(parts.find(part => part.type === type)?.value ?? 0);
  return {
    year: value('year'),
    month: value('month'),
    day: value('day'),
    hour: value('hour'),
    minute: value('minute'),
    second: value('second'),
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

function seoulDateTimeToIso(
  year: number,
  month: number,
  day: number,
  hour: number,
  minute: number,
  second: number,
) {
  return new Date(Date.UTC(year, month - 1, day, hour - 9, minute, second)).toISOString();
}
