import { Readable } from 'node:stream';
import type { IncomingMessage } from 'node:http';
import { describe, expect, it } from 'vitest';
import { readJsonBody } from './localBoundary';

function bodyRequest(chunks: Buffer[]): IncomingMessage {
  return Object.assign(Readable.from(chunks), { headers: { 'content-type': 'application/json' } }) as IncomingMessage;
}
describe('local JSON body decoding', () => {
  it('preserves Unicode split across network chunks and counts original bytes', async () => {
    const data = Buffer.from('{"value":"校园测试"}', 'utf8');
    const split = data.indexOf(Buffer.from('校')) + 1;
    await expect(readJsonBody(bodyRequest([data.subarray(0, split), data.subarray(split)])))
      .resolves.toEqual({ value: '校园测试' });
    await expect(readJsonBody(bodyRequest([data]), data.length - 1)).rejects.toMatchObject({ status: 413 });
  });
  it('rejects malformed UTF-8 instead of saving a replacement character', async () => {
    await expect(readJsonBody(bodyRequest([Buffer.from([123, 34, 120, 34, 58, 34, 0xff, 34, 125])])))
      .rejects.toMatchObject({ status: 400 });
  });
});
