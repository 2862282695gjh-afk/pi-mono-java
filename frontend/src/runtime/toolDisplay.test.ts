import { describe, expect, it } from 'vitest';
import { MISSING_TOOL_NAME, toolDisplayIdentity } from './toolDisplay';

describe('tool display identity', () => {
  it('prefers the trimmed event snapshot and preserves an ordinary technical name', () => {
    expect(toolDisplayIdentity({ toolName: ' Read_File-v2 ', displayName: '  读取配置  ', arguments: {} })).toEqual({
      label: '读取配置', displayName: '读取配置', technicalName: ' Read_File-v2 ',
    });
  });

  it('uses the wrapped Mate tool as the technical fallback without trusting model displayName arguments', () => {
    expect(toolDisplayIdentity({
      toolName: 'CallMateTool',
      arguments: { tool: 'query_classroom-v2', args: { displayName: '伪造参数' }, displayName: '伪造名称' },
    })).toEqual({ label: 'query_classroom-v2', technicalName: 'query_classroom-v2' });
  });

  it('falls back for invalid names and does not use the outer Mate wrapper', () => {
    expect(toolDisplayIdentity({ toolName: 'CallMateTool', displayName: '  ', arguments: { tool: null } })).toEqual({
      label: MISSING_TOOL_NAME,
    });
    expect(toolDisplayIdentity({ toolName: 'Read', displayName: 42, arguments: {} })).toEqual({
      label: 'Read', technicalName: 'Read',
    });
  });

  it('allows duplicate display names while retaining distinct technical identities', () => {
    const first = toolDisplayIdentity({ toolName: 'CallMateTool', displayName: '查询', arguments: { tool: 'query_room' } });
    const second = toolDisplayIdentity({ toolName: 'CallMateTool', displayName: '查询', arguments: { tool: 'query_device' } });
    expect(first.label).toBe(second.label);
    expect(first.technicalName).not.toBe(second.technicalName);
  });
});
