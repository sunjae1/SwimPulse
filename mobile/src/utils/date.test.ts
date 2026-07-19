import {formatDateTime, fromInputDateTime, toInputDateTime} from './date';

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
});
