import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import ts from 'typescript';

const code = ts.transpileModule(fs.readFileSync('src/services/voice/nativeMicrophone.ts', 'utf8'), {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
}).outputText;
const flush = async () => { for (let i = 0; i < 40; i++) await Promise.resolve(); };
const levelContext = vm.createContext({ exports: {} });
vm.runInContext(ts.transpileModule(fs.readFileSync('src/services/voice/microphoneLevel.ts', 'utf8'), {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
}).outputText, levelContext);
function setup({ native = true, moduleFailure = false, startFailure = null, resumeMode = 'running', timers = { setTimeout, clearTimeout } } = {}) {
  const calls = [], reads = [], nodes = [], contexts = [], browser = [];
  const window = new EventTarget();
  class Track extends EventTarget {
    readyState = 'live';
    stop() { this.readyState = 'ended'; }
    getSettings() { return {}; }
  }
  const track = new Track();
  const stream = { getAudioTracks: () => [track], getTracks: () => [track] };
  class Context {
    sampleRate = 48000;
    state = 'suspended';
    constructor() { contexts.push(this); }
    audioWorklet = { addModule: async () => { if (moduleFailure) throw new Error('worklet'); } };
    createMediaStreamDestination() { return { stream }; }
    async resume() {
      if (resumeMode === 'reject') throw new Error('resume denied');
      if (resumeMode === 'hang') return new Promise(() => {});
      if (resumeMode === 'running') this.state = 'running';
    }
    async close() { this.state = 'closed'; }
  }
  class Worklet {
    port = { messages: [], postMessage: bytes => this.port.messages.push(bytes), close: () => { this.closed = true; } };
    constructor() { nodes.push(this); }
    connect() {}
    disconnect() {}
  }
  const api = { isTauri: () => true, invoke: async (command, args) => {
    calls.push([command, args]);
    if (command === 'native_microphone_supported') return native;
    if (command === 'native_microphone_devices') return [{ deviceId: 'wasapi:mic', kind: 'audioinput', label: 'Mic' }];
    if (command === 'native_microphone_start') {
      if (startFailure) throw startFailure;
      return { id: 'capture', deviceId: 'wasapi:mic', sampleRate: 48000 };
    }
    if (command === 'native_microphone_read') return new Promise((resolve, reject) => reads.push({ resolve, reject }));
  } };
  const context = vm.createContext({ exports: {}, require: path => path.includes('microphoneLevel') ? levelContext.exports : path.includes('worklet') || path.includes('Worklet') ? { default: 'worklet.js' } : api,
    AudioContext: Context, AudioWorkletNode: Worklet, ArrayBuffer, DOMException, Event, window, console, ...timers,
    navigator: { mediaDevices: { getUserMedia: async options => { browser.push(options); return stream; } } },
  });
  vm.runInContext(code, context);
  return { api: context.exports, calls, reads, nodes, contexts, browser, track, window };
}
test('native enumeration never starts recording; Windows capture never asks the browser', async () => {
  const f = setup();
  const devices = await f.api.microphoneDevices();
  assert.equal(devices[0].deviceId, 'wasapi:mic');
  assert.ok(!f.calls.some(([name]) => name === 'native_microphone_start'));
  const stream = await f.api.openMicrophone('wasapi:mic', false);
  assert.equal(f.browser.length, 0);
  assert.equal(f.calls.find(([name]) => name === 'native_microphone_start')[1].systemProcessing, false);
  assert.equal(stream.getAudioTracks()[0].getSettings().sampleRate, 48000);
  stream.getTracks()[0].stop(); stream.getTracks()[0].stop(); await flush();
  assert.equal(f.calls.filter(([name]) => name === 'native_microphone_stop').length, 1);
  assert.equal(f.contexts[0].state, 'closed');
  assert.equal(f.nodes[0].closed, true);
});
test('device failure allows a fresh later attempt without browser fallback', async () => {
  const f = setup({ startFailure: 'Windows privacy blocked' });
  await assert.rejects(f.api.openMicrophone(), /Windows privacy blocked/);
  await assert.rejects(f.api.openMicrophone(), /Windows privacy blocked/);
  assert.equal(f.calls.filter(([name]) => name === 'native_microphone_start').length, 2);
  assert.equal(f.browser.length, 0);
});

test('native test meter reports quiet PCM without a second audio graph, and clears on stop', async () => {
  const f = setup();
  const stream = await f.api.openMicrophone();
  assert.equal(f.api.nativeMicrophoneLevel(stream), 0);
  const quiet = new Float32Array(960).fill(0.0013);
  // Previous linear display rounded this genuine signal to zero.
  assert.equal(Math.round(0.0013 * 300), 0);
  f.reads[0].resolve(quiet.buffer); await flush();
  assert.ok(f.api.nativeMicrophoneLevel(stream) > 0.0012);
  assert.ok(levelContext.exports.microphoneLevelPercent(f.api.nativeMicrophoneLevel(stream)) >= 3);
  f.reads[1].resolve(new Float32Array(960).fill(0.1).buffer); await flush();
  assert.ok(levelContext.exports.microphoneLevelPercent(f.api.nativeMicrophoneLevel(stream)) >= 66);
  stream.getTracks()[0].stop();
  assert.equal(f.api.nativeMicrophoneLevel(stream), 0);
  assert.equal(f.contexts.length, 1);
  const { pcmRms, microphoneLevelPercent } = levelContext.exports;
  assert.equal(microphoneLevelPercent(pcmRms(new Float32Array(960))), 0);
  assert.equal(microphoneLevelPercent(NaN), 0);
  assert.equal(microphoneLevelPercent(5), 100);
});
test('worklet initialization failure releases the native device', async () => {
  const f = setup({ moduleFailure: true });
  await assert.rejects(f.api.openMicrophone(), /worklet/);
  assert.equal(f.calls.filter(([name]) => name === 'native_microphone_stop').length, 1);
  assert.equal(f.contexts[0].state, 'closed');
});

test('suspended, rejected and timed out audio engines release capture instead of returning a silent stream', async () => {
  for (const resumeMode of ['suspended', 'reject', 'hang']) {
    const callbacks = new Map(); let next = 0;
    const f = setup({ resumeMode, timers: {
      setTimeout(callback) { callbacks.set(++next, callback); return next; },
      clearTimeout(id) { callbacks.delete(id); },
    } });
    const pending = assert.rejects(f.api.openMicrophone(), /音频引擎|resume denied/);
    await flush();
    if (resumeMode === 'hang') for (const callback of callbacks.values()) callback();
    await pending;
    assert.equal(f.contexts[0].state, 'closed');
    assert.equal(f.calls.filter(([name]) => name === 'native_microphone_stop').length, 1);
    assert.equal(f.reads.length, 0);
    assert.equal(callbacks.size, 0);
  }
});
test('queued PCM stays bounded until the audio thread consumes it', async () => {
  const f = setup(); await f.api.openMicrophone();
  for (let i = 0; i < 3; i++) { f.reads[i].resolve(new ArrayBuffer(3840)); await flush(); }
  assert.equal(f.reads.length, 3);
  assert.equal(f.nodes[0].port.messages.length, 3);
  f.nodes[0].port.onmessage(); await flush();
  assert.equal(f.reads.length, 4);
  f.window.dispatchEvent(new Event('beforeunload')); await flush();
  f.reads[3].resolve(new ArrayBuffer(3840)); await flush();
  assert.equal(f.nodes[0].port.messages.length, 3);
  assert.equal(f.track.readyState, 'ended');
});
test('malformed packets or unplugging ends the track once and releases capture', async () => {
  for (const malformed of [false, true]) {
    const f = setup(); await f.api.openMicrophone(); let ended = 0;
    f.track.addEventListener('ended', () => ended++);
    if (malformed) f.reads[0].resolve(new ArrayBuffer(7)); else f.reads[0].reject(new Error('unplugged'));
    await flush();
    assert.equal(ended, 1); assert.equal(f.track.readyState, 'ended');
    assert.equal(f.calls.filter(([name]) => name === 'native_microphone_stop').length, 1);
  }
});
test('other desktop backends retain their browser media path', async () => {
  const f = setup({ native: false }); await f.api.openMicrophone('linux-mic');
  assert.equal(f.browser[0].audio.deviceId.ideal, 'linux-mic');
  assert.ok(!f.calls.some(([name]) => name === 'native_microphone_start'));
});

test('audio worklet renders PCM, sanitizes invalid samples, bounds latency and emits silence on underrun', () => {
  let Processor;
  class Base { port = { postMessage() {} }; }
  const context = vm.createContext({ AudioWorkletProcessor: Base, Float32Array, ArrayBuffer,
    registerProcessor: (_, type) => { Processor = type; } });
  vm.runInContext(fs.readFileSync('src/services/voice/nativeMicrophoneWorklet.js', 'utf8'), context);
  const p = new Processor();
  const samples = new Float32Array(960).fill(0.25); samples[0] = NaN; samples[1] = 2;
  p.port.onmessage({ data: samples.buffer });
  const output = new Float32Array(960); p.process([], [[output]]);
  assert.equal(output[0], 0); assert.equal(output[1], 1); assert.equal(output[2], 0.25);
  p.process([], [[output]]); assert.ok(output.every(value => value === 0));
  for (let i = 0; i < 20; i++) p.port.onmessage({ data: samples.buffer });
  assert.equal(p.length, 4800);
});
