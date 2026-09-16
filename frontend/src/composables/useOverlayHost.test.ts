import { afterEach, describe, expect, it, vi } from 'vitest';
import { activateOverlay, calculateOverlayPosition, deactivateOverlay, isActiveOverlay } from './useOverlayHost';

const anchor = { top: 100, right: 180, bottom: 140, left: 80, width: 100, height: 40 };
const viewport = { top: 0, left: 0, width: 320, height: 300 };

describe('root overlay positioning', () => {
  afterEach(() => deactivateOverlay(testId));
  const testId = Symbol('test-overlay');

  it('flips to the side with room and keeps an eight pixel viewport margin', () => {
    const position = calculateOverlayPosition({
      anchor: { ...anchor, top: 240, bottom: 280 },
      overlayWidth: 180,
      overlayHeight: 120,
      viewport,
      placement: 'bottom-start',
    });
    expect(position.side).toBe('top');
    expect(position.top).toBe(112);
    expect(position.left).toBe(80);
    expect(position.maxHeight).toBe(224);
  });

  it('shifts oversized and right-aligned surfaces inside the visual viewport', () => {
    const position = calculateOverlayPosition({
      anchor: { ...anchor, left: 290, right: 330 },
      overlayWidth: 500,
      overlayHeight: 240,
      viewport: { top: 20, left: 10, width: 320, height: 220 },
      placement: 'bottom-end',
    });
    expect(position.left).toBe(18);
    expect(position.maxWidth).toBe(304);
    expect(position.top).toBeGreaterThanOrEqual(28);
    expect(position.top + position.maxHeight).toBeLessThanOrEqual(232);
  });

  it('closes the previous overlay before activating another', () => {
    const firstId = Symbol('first');
    const close = vi.fn();
    activateOverlay(firstId, close);
    activateOverlay(testId, vi.fn());
    expect(close).toHaveBeenCalledWith('superseded');
    deactivateOverlay(firstId);
    expect(isActiveOverlay(testId)).toBe(true);
  });
});
