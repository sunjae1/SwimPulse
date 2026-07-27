import {
  formatDateTime,
  formatMonthDescription,
  fromInputDateTime,
  isPastPeriod,
  shiftPeriodToNextAvailableMonth,
  toInputDateTime,
} from './date';

describe('mobile date utilities', () => {
  it('formats UTC API timestamps in Asia/Seoul time', () => {
    expect(formatDateTime('2026-07-05T15:01:00Z')).toBe('07. 06. 오전 12:01');
  });

  it('converts a Seoul date-time input to a UTC instant', () => {
    expect(fromInputDateTime('2026-07-06 00:01')).toBe('2026-07-05T15:01:00.000Z');
  });

  it('converts a UTC instant back to the same Seoul date-time input', () => {
    expect(toInputDateTime('2026-07-05T15:01:00Z')).toBe('2026-07-06 00:01');
  });

  it('shifts a closed period in the current month to the same dates next month', () => {
    const shifted = shiftPeriodToNextAvailableMonth(
      '2026-07-19T15:00:00Z',
      '2026-07-24T14:59:59Z',
      new Date('2026-07-27T15:30:00Z'),
    );

    expect(shifted).toEqual({
      registrationStartsAt: '2026-08-19T15:00:00.000Z',
      registrationEndsAt: '2026-08-24T14:59:59.000Z',
    });
    expect(formatMonthDescription(shifted.registrationStartsAt, new Date('2026-07-27T15:30:00Z')))
      .toBe('8월(다음 달)');
  });

  it('skips the current month when shifted dates from a previous month are already closed', () => {
    const shifted = shiftPeriodToNextAvailableMonth(
      '2026-06-19T15:00:00Z',
      '2026-06-24T14:59:59Z',
      new Date('2026-07-27T15:30:00Z'),
    );

    expect(shifted.registrationStartsAt).toBe('2026-08-19T15:00:00.000Z');
    expect(shifted.registrationEndsAt).toBe('2026-08-24T14:59:59.000Z');
  });

  it('uses the current month when shifted dates from a previous month are still upcoming', () => {
    const shifted = shiftPeriodToNextAvailableMonth(
      '2026-06-28T15:00:00Z',
      '2026-06-30T14:59:59Z',
      new Date('2026-07-27T15:30:00Z'),
    );

    expect(shifted.registrationStartsAt).toBe('2026-07-28T15:00:00.000Z');
    expect(shifted.registrationEndsAt).toBe('2026-07-30T14:59:59.000Z');
  });

  it('treats a period as closed as soon as its end time is reached', () => {
    jest.useFakeTimers().setSystemTime(new Date('2026-07-28T00:00:00Z'));

    expect(isPastPeriod('2026-07-20T00:00:00Z', '2026-07-28T00:00:00Z')).toBe(true);
    expect(isPastPeriod('2026-07-20T00:00:00Z', '2026-07-28T00:00:01Z')).toBe(false);

    jest.useRealTimers();
  });
});
