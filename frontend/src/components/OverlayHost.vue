<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue';
import {
  activateOverlay,
  calculateOverlayPosition,
  deactivateOverlay,
  isActiveOverlay,
  type OverlayCloseReason,
  type OverlayPlacement,
} from '../composables/useOverlayHost';

defineOptions({ inheritAttrs: false });
const props = withDefaults(defineProps<{
  open: boolean;
  anchor: HTMLElement | null;
  placement?: OverlayPlacement;
  gap?: number;
  matchAnchorWidth?: boolean;
  initialFocus?: 'first' | 'last' | 'selected' | 'surface' | 'none';
}>(), {
  placement: 'bottom-start',
  gap: 8,
  matchAnchorWidth: false,
  initialFocus: 'first',
});
const emit = defineEmits<{ 'request-close': [reason: OverlayCloseReason] }>();
const surface = ref<HTMLElement | null>(null);
const style = reactive<Record<string, string>>({ visibility: 'hidden' });
const overlayId = Symbol('overlay-host');
let closeRequested = false;
let frame = 0;
let resizeObserver: ResizeObserver | undefined;

function requestClose(reason: OverlayCloseReason): void {
  if (!props.open || closeRequested) return;
  closeRequested = true;
  deactivateOverlay(overlayId);
  emit('request-close', reason);
}

function viewportRect(): { top: number; left: number; width: number; height: number } {
  const viewport = window.visualViewport;
  return viewport
    ? { top: viewport.offsetTop, left: viewport.offsetLeft, width: viewport.width, height: viewport.height }
    : { top: 0, left: 0, width: window.innerWidth, height: window.innerHeight };
}

function updatePosition(): void {
  frame = 0;
  const anchor = props.anchor;
  const element = surface.value;
  if (!props.open || !element || !isActiveOverlay(overlayId)) return;
  if (!anchor?.isConnected) { requestClose('anchor-lost'); return; }
  const anchorRect = anchor.getBoundingClientRect();
  if (anchorRect.width === 0 && anchorRect.height === 0) { requestClose('anchor-lost'); return; }
  const viewport = viewportRect();
  const viewportRight = viewport.left + viewport.width;
  const viewportBottom = viewport.top + viewport.height;
  if (anchorRect.right <= viewport.left || anchorRect.left >= viewportRight
    || anchorRect.bottom <= viewport.top || anchorRect.top >= viewportBottom) {
    requestClose('anchor-lost');
    return;
  }
  const elementRect = element.getBoundingClientRect();
  const position = calculateOverlayPosition({
    anchor: anchorRect,
    overlayWidth: props.matchAnchorWidth ? anchorRect.width : elementRect.width,
    overlayHeight: element.scrollHeight || elementRect.height,
    viewport,
    placement: props.placement,
    gap: props.gap,
  });
  style.position = 'fixed';
  style.top = position.top + 'px';
  style.left = position.left + 'px';
  style.maxHeight = position.maxHeight + 'px';
  style.maxWidth = position.maxWidth + 'px';
  style.width = props.matchAnchorWidth ? Math.min(anchorRect.width, position.maxWidth) + 'px' : '';
  style.visibility = 'visible';
  element.dataset.side = position.side;
}

function schedulePosition(): void {
  if (frame || !props.open) return;
  frame = window.requestAnimationFrame(updatePosition);
}

function interactiveItems(): HTMLElement[] {
  if (!surface.value) return [];
  return Array.from(surface.value.querySelectorAll<HTMLElement>(
    '[role="menuitem"]:not([disabled]), [role="menuitemradio"]:not([disabled]), [role="option"]:not([aria-disabled="true"]), button:not([disabled])',
  )).filter((element) => element.tabIndex >= 0 && element.offsetParent !== null);
}

function focusInitial(): void {
  if (props.initialFocus === 'none' || !isActiveOverlay(overlayId)) return;
  const element = surface.value;
  if (!element) return;
  const items = interactiveItems();
  const selected = items.find((item) => item.getAttribute('aria-checked') === 'true'
    || item.getAttribute('aria-selected') === 'true');
  const target = props.initialFocus === 'surface' ? element
    : props.initialFocus === 'last' ? items.at(-1)
      : props.initialFocus === 'selected' ? selected ?? items[0] : items[0];
  (target ?? element).focus({ preventScroll: true });
}

function onPointerDown(event: PointerEvent): void {
  if (!props.open || !isActiveOverlay(overlayId)) return;
  const path = event.composedPath();
  if (!path.includes(surface.value as EventTarget) && !path.includes(props.anchor as EventTarget)) requestClose('outside');
}

function onFocusIn(event: FocusEvent): void {
  if (!props.open || !isActiveOverlay(overlayId)) return;
  const target = event.target;
  if (!(target instanceof Node) || surface.value?.contains(target) || props.anchor?.contains(target)) return;
  requestClose('focusout');
}

function onDocumentKeydown(event: KeyboardEvent): void {
  if (!props.open || !isActiveOverlay(overlayId) || event.key !== 'Escape'
    || event.isComposing || event.keyCode === 229) return;
  event.preventDefault();
  event.stopPropagation();
  requestClose('escape');
}

function onSurfaceKeydown(event: KeyboardEvent): void {
  if (!['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) return;
  const items = interactiveItems();
  if (!items.length) return;
  event.preventDefault();
  const current = items.indexOf(document.activeElement as HTMLElement);
  const last = items.length - 1;
  const next = event.key === 'Home' ? 0 : event.key === 'End' ? last
    : event.key === 'ArrowDown' ? (current + 1 + items.length) % items.length
      : (current - 1 + items.length) % items.length;
  items[next].focus({ preventScroll: true });
}

function observePosition(): void {
  resizeObserver?.disconnect();
  if (typeof ResizeObserver === 'undefined') return;
  resizeObserver = new ResizeObserver(schedulePosition);
  if (props.anchor) resizeObserver.observe(props.anchor);
  if (surface.value) resizeObserver.observe(surface.value);
}

watch(() => props.open, async (open) => {
  closeRequested = false;
  if (!open) {
    deactivateOverlay(overlayId);
    resizeObserver?.disconnect();
    if (frame) window.cancelAnimationFrame(frame);
    frame = 0;
    return;
  }
  activateOverlay(overlayId, requestClose);
  style.visibility = 'hidden';
  await nextTick();
  updatePosition();
  observePosition();
  focusInitial();
}, { flush: 'post' });

watch(() => [props.anchor, props.placement, props.matchAnchorWidth], () => schedulePosition(), { flush: 'post' });

onMounted(() => {
  document.addEventListener('pointerdown', onPointerDown, true);
  document.addEventListener('focusin', onFocusIn, true);
  document.addEventListener('keydown', onDocumentKeydown, true);
  window.addEventListener('resize', schedulePosition);
  window.addEventListener('scroll', schedulePosition, true);
  window.visualViewport?.addEventListener('resize', schedulePosition);
  window.visualViewport?.addEventListener('scroll', schedulePosition);
  if (props.open) {
    activateOverlay(overlayId, requestClose);
    void nextTick(() => { updatePosition(); observePosition(); focusInitial(); });
  }
});

onBeforeUnmount(() => {
  deactivateOverlay(overlayId);
  resizeObserver?.disconnect();
  if (frame) window.cancelAnimationFrame(frame);
  document.removeEventListener('pointerdown', onPointerDown, true);
  document.removeEventListener('focusin', onFocusIn, true);
  document.removeEventListener('keydown', onDocumentKeydown, true);
  window.removeEventListener('resize', schedulePosition);
  window.removeEventListener('scroll', schedulePosition, true);
  window.visualViewport?.removeEventListener('resize', schedulePosition);
  window.visualViewport?.removeEventListener('scroll', schedulePosition);
});
</script>

<template>
  <Teleport to="body">
    <div v-if="open" ref="surface" v-bind="$attrs" class="overlay-surface" :style="style" tabindex="-1"
      @keydown="onSurfaceKeydown">
      <slot />
    </div>
  </Teleport>
</template>
