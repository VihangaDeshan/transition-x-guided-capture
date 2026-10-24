// SPDX-License-Identifier: Apache-2.0
// Pose trail mini-map: projects floor-plane points (x, z in metres) into a square box.

export type Pt = [number, number];

export interface Projected {
  points: Pt[];
  current: Pt | null;
  start: Pt | null;
  metresPerPixel: number;
}

/**
 * Fits the trail (and the current position) into a size x size box with padding, keeping the
 * aspect ratio and a minimum extent so a short trail is not blown up to fill the box.
 * Screen y grows downward; ARCore z grows toward the viewer, so z maps directly to y.
 */
export function projectTrail(trail: Pt[], current: Pt | null, size: number, padding = 10, minExtentM = 2): Projected {
  const all = current ? [...trail, current] : trail;
  if (all.length === 0) return { points: [], current: null, start: null, metresPerPixel: 0 };
  let minX = Infinity, maxX = -Infinity, minZ = Infinity, maxZ = -Infinity;
  for (const [x, z] of all) {
    if (x < minX) minX = x;
    if (x > maxX) maxX = x;
    if (z < minZ) minZ = z;
    if (z > maxZ) maxZ = z;
  }
  const extent = Math.max(maxX - minX, maxZ - minZ, minExtentM);
  const cx = (minX + maxX) / 2;
  const cz = (minZ + maxZ) / 2;
  const usable = size - 2 * padding;
  const scale = usable / extent;
  const map = ([x, z]: Pt): Pt => [size / 2 + (x - cx) * scale, size / 2 + (z - cz) * scale];
  return {
    points: trail.map(map),
    current: current ? map(current) : null,
    start: trail.length ? map(trail[0]) : current ? map(current) : null,
    metresPerPixel: 1 / scale,
  };
}

/** Keeps the trail bounded for rendering: drops every other point once over [max]. */
export function appendTrail(trail: Pt[], add: Pt[], max = 600): Pt[] {
  if (add.length === 0) return trail;
  let next = trail.concat(add);
  while (next.length > max) next = next.filter((_, i) => i % 2 === 0 || i === next.length - 1);
  return next;
}
