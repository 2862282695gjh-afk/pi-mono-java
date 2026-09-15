export type OverlayCloseReason = 'anchor-lost' | 'escape' | 'focusout' | 'outside' | 'superseded';
export type OverlayPlacement = 'top-start' | 'top-end' | 'bottom-start' | 'bottom-end';

interface RectLike {
  top: number;
  right: number;
  bottom: number;
  left: number;
  width: number;
  height: number;
}

interface ViewportRect {
  top: number;
  left: number;
  width: number;
  height: number;
}

export interface OverlayPosition {
  top: number;
  left: number;
  maxHeight: number;
  maxWidth: number;
  side: 'top' | 'bottom';
}

const VIEWPORT_MARGIN = 8;
let activeOverlay: { id: symbol; close: (reason: OverlayCloseReason) => void } | undefined;

export function activateOverlay(id: symbol, close: (reason: OverlayCloseReason) => void): void {
  if (activeOverlay?.id !== id) activeOverlay?.close('superseded');
  activeOverlay = { id, close };
}

export function deactivateOverlay(id: symbol): void {
  if (activeOverlay?.id === id) activeOverlay = undefined;
}

export function isActiveOverlay(id: symbol): boolean {
  return activeOverlay?.id === id;
}

export function calculateOverlayPosition(options: {
  anchor: RectLike;
  overlayWidth: number;
  overlayHeight: number;
  viewport: ViewportRect;
  placement: OverlayPlacement;
  gap?: number;
  margin?: number;
}): OverlayPosition {
  const { anchor, viewport, placement } = options;
  const gap = options.gap ?? VIEWPORT_MARGIN;
  const margin = options.margin ?? VIEWPORT_MARGIN;
  const viewportRight = viewport.left + viewport.width;
  const viewportBottom = viewport.top + viewport.height;
  const maxWidth = Math.max(0, viewport.width - margin * 2);
  const renderedWidth = Math.min(options.overlayWidth, maxWidth);
  const availableTop = Math.max(0, anchor.top - viewport.top - gap - margin);
  const availableBottom = Math.max(0, viewportBottom - anchor.bottom - gap - margin);
  const preferredSide = placement.startsWith('top') ? 'top' : 'bottom';
  const side = preferredSide === 'top'
    ? options.overlayHeight > availableTop && availableBottom > availableTop ? 'bottom' : 'top'
    : options.overlayHeight > availableBottom && availableTop > availableBottom ? 'top' : 'bottom';
  const maxHeight = side === 'top' ? availableTop : availableBottom;
  const renderedHeight = Math.min(options.overlayHeight, maxHeight);
  const unclampedTop = side === 'top' ? anchor.top - gap - renderedHeight : anchor.bottom + gap;
  const top = clamp(unclampedTop, viewport.top + margin, viewportBottom - margin - renderedHeight);
  const alignsEnd = placement.endsWith('end');
  const unclampedLeft = alignsEnd ? anchor.right - renderedWidth : anchor.left;
  const left = clamp(unclampedLeft, viewport.left + margin, viewportRight - margin - renderedWidth);
  return { top, left, maxHeight, maxWidth, side };
}

function clamp(value: number, minimum: number, maximum: number): number {
  return Math.min(Math.max(value, minimum), Math.max(minimum, maximum));
}
