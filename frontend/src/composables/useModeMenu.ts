import { ref } from 'vue';

export function useModeMenu(options: {
  disabled: () => boolean;
  current: () => 'build' | 'plan';
  emit: (mode: 'build' | 'plan') => void;
  focusChoice: (index: number) => void;
  focusTrigger: () => void;
}) {
  const open = ref(false);
  function show(focusIndex = 0): void {
    if (options.disabled()) return;
    open.value = true;
    options.focusChoice(focusIndex);
  }
  function close(focusTrigger = false): void {
    open.value = false;
    if (focusTrigger) options.focusTrigger();
  }
  function choose(mode: 'build' | 'plan'): void {
    if (options.disabled()) return;
    close(true);
    if (mode !== options.current()) options.emit(mode);
  }
  function triggerKeydown(event: KeyboardEvent): void {
    if (event.key === 'Escape' && open.value) { event.preventDefault(); event.stopPropagation(); close(true); return; }
    if (['ArrowDown', 'ArrowUp'].includes(event.key)) {
      event.preventDefault(); show(event.key === 'ArrowDown' ? 0 : 1);
    }
  }
  function menuKeydown(event: KeyboardEvent, index: number): void {
    if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); close(true); return; }
    if (!['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) return;
    event.preventDefault();
    const target = event.key === 'Home' ? 0 : event.key === 'End' ? 1 : (index + (event.key === 'ArrowDown' ? 1 : -1) + 2) % 2;
    options.focusChoice(target);
  }
  return { open, show, close, choose, triggerKeydown, menuKeydown };
}
