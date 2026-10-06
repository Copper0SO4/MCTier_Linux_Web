import test from 'node:test';
import assert from 'node:assert/strict';
import { build } from 'esbuild';
const result = await build({entryPoints:['MCTier-Linux-Web/web/boundedResponse.ts'],bundle:true,format:'esm',write:false});
const {boundedBlob,TransferScope} = await import(`data:text/javascript,${encodeURIComponent(result.outputFiles[0].text)}`);
test('bounded reads reject unknown-length overflow, invalid metadata and truncation', async () => {
  assert.equal(await (await boundedBlob(new Response('abc'), 3, 3)).text(), 'abc');
  await assert.rejects(boundedBlob(new Response('abcd'), 3, 3), /超出限制/);
  await assert.rejects(boundedBlob(new Response('ab'), 3, 3), /不一致/);
  await assert.rejects(boundedBlob(new Response('abc'), 3, NaN), /无效/);
  await assert.rejects(boundedBlob(new Response('abc',{headers:{'content-length':'4'}}), 3), /超出限制/);
  let cancelled = false;
  const stream = new ReadableStream({pull(controller){controller.enqueue(new Uint8Array(4));},cancel(){cancelled=true;}});
  await assert.rejects(boundedBlob(new Response(stream), 3), /超出限制/);
  assert.equal(cancelled, true);
});
test('closing a transfer scope cancels every concurrent request and permits a new scope', () => {
  const transfers = new TransferScope();
  const first = transfers.begin(), second = transfers.begin();
  transfers.cancel();
  assert.equal(first.signal.aborted,true); assert.equal(second.signal.aborted,true);
  const completed=transfers.begin(); transfers.finish(completed);
  const current=transfers.begin(); transfers.cancel();
  assert.equal(completed.signal.aborted,false); assert.equal(current.signal.aborted,true);
});
