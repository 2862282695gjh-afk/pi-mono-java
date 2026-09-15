export async function copyPlanMarkdown(source: string, clipboard: Pick<Clipboard, 'writeText'> | undefined =
  typeof navigator === 'undefined' ? undefined : navigator.clipboard) {
  if (!clipboard?.writeText) return '复制失败，请展开方案后手动复制。';
  try {
    await clipboard.writeText(source);
    return '方案已复制';
  } catch { return '复制失败，请展开方案后手动复制。'; }
}
