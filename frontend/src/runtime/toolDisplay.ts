export const MISSING_TOOL_NAME = '业务工具（名称缺失）';

export interface ToolDisplaySource {
  toolName?: unknown;
  displayName?: unknown;
  arguments?: Record<string, unknown>;
}

export interface ToolDisplayIdentity {
  label: string;
  displayName?: string;
  technicalName?: string;
}

function validDisplayName(value: unknown): string | undefined {
  if (typeof value !== 'string') return undefined;
  return value.trim() || undefined;
}

function validTechnicalName(value: unknown): string | undefined {
  if (typeof value !== 'string' || !value.trim()) return undefined;
  return value;
}

/** 只消费调用快照与执行身份，不从模型参数的同名字段或结果推测展示名。 */
export function toolDisplayIdentity(source: ToolDisplaySource): ToolDisplayIdentity {
  const displayName = validDisplayName(source.displayName);
  const technicalName = source.toolName === 'CallMateTool'
    ? validTechnicalName(source.arguments?.tool)
    : validTechnicalName(source.toolName);
  return {
    label: displayName ?? technicalName ?? MISSING_TOOL_NAME,
    ...(displayName ? { displayName } : {}),
    ...(technicalName ? { technicalName } : {}),
  };
}
