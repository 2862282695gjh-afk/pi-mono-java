import { computed, ref } from 'vue';

export function useDockDisclosures() {
  const commandExpanded = ref(true);
  const progressExpanded = ref(true);
  const paletteOpen = ref(false);
  const commandVisible = computed(() => commandExpanded.value && !paletteOpen.value);
  const progressVisible = computed(() => progressExpanded.value && !paletteOpen.value);
  let rootId = '';
  function setPaletteOpen(open: boolean): void { paletteOpen.value = open; }
  function observeRoot(nextRootId?: string): void {
    if (!nextRootId || nextRootId === rootId) return;
    rootId = nextRootId;
    progressExpanded.value = true;
  }
  function observeTerminal(terminal: boolean): void { if (terminal) progressExpanded.value = false; }
  function reset(): void {
    commandExpanded.value = true;
    progressExpanded.value = true;
    paletteOpen.value = false;
    rootId = '';
  }
  return { commandExpanded, progressExpanded, paletteOpen, commandVisible, progressVisible, setPaletteOpen, observeRoot,
    observeTerminal, reset };
}
