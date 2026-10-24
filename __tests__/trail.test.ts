import { appendTrail, projectTrail } from '../src/utils/trail';
import { formatBytes, formatDuration, formatMetres } from '../src/utils/format';

describe('pose trail projection', () => {
  it('keeps every point inside the box with padding', () => {
    const trail: [number, number][] = [[0, 0], [3, 1], [-2, 4], [1, -5]];
    const p = projectTrail(trail, [0.5, 0.5], 120, 10);
    for (const [x, y] of [...p.points, p.current!]) {
      expect(x).toBeGreaterThanOrEqual(10 - 1e-9);
      expect(x).toBeLessThanOrEqual(110 + 1e-9);
      expect(y).toBeGreaterThanOrEqual(10 - 1e-9);
      expect(y).toBeLessThanOrEqual(110 + 1e-9);
    }
    expect(p.start).toEqual(p.points[0]);
  });

  it('does not blow a tiny trail up to fill the box (minimum 2 m extent)', () => {
    const p = projectTrail([[0, 0], [0.1, 0]], null, 120, 10, 2);
    expect(p.metresPerPixel).toBeCloseTo(2 / 100);
    const dx = p.points[1][0] - p.points[0][0];
    expect(dx).toBeCloseTo(5); // 0.1 m at 50 px/m
  });

  it('handles an empty trail', () => {
    expect(projectTrail([], null, 100).points).toHaveLength(0);
  });

  it('bounds the trail length but keeps the latest point', () => {
    let t: [number, number][] = [];
    for (let i = 0; i < 2000; i++) t = appendTrail(t, [[i, 0]], 500);
    expect(t.length).toBeLessThanOrEqual(500);
    expect(t[t.length - 1]).toEqual([1999, 0]);
    expect(t[0]).toEqual([0, 0]);
  });
});

describe('formatting', () => {
  it('formats durations, metres and bytes', () => {
    expect(formatDuration(0)).toBe('0:00');
    expect(formatDuration(65_400)).toBe('1:05');
    expect(formatDuration(600_000)).toBe('10:00');
    expect(formatMetres(6.04)).toBe('6.0 m');
    expect(formatBytes(3 * 1024 ** 3)).toBe('3.0 GB');
    expect(formatBytes(-1)).toBe('—');
  });
});
