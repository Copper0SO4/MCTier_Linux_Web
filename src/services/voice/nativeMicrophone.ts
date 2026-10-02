import { invoke, isTauri } from '@tauri-apps/api/core';
import workletUrl from './nativeMicrophoneWorklet.js?url&no-inline';
import { pcmRms } from './microphoneLevel';

const nativeLevels = new WeakMap<MediaStream, number>();
/** Undefined for browser streams; native values come directly from WASAPI PCM. */
export function nativeMicrophoneLevel(stream: MediaStream): number | undefined {
  return nativeLevels.get(stream);
}

export type MicrophoneDevice = Pick<MediaDeviceInfo, 'deviceId' | 'kind' | 'label'> & { isDefault?: boolean; isCommunications?: boolean };
let supported: Promise<boolean> | undefined;
export async function resumeAudioContext(context: AudioContext): Promise<void> {
  let timer: ReturnType<typeof setTimeout> | undefined;
  try {
    await Promise.race([context.resume(), new Promise<never>((_, reject) => {
      timer = setTimeout(() => reject(new Error('音频引擎未能启动，请重新开启麦克风或试音')), 3000);
    })]);
    if (context.state !== 'running') throw new Error(`音频引擎状态异常: ${context.state}`);
  } finally { clearTimeout(timer); }
}
function diagnostic(stage: string, detail: string): void {
  void invoke('report_audio_diagnostic', { stage, detail }).catch(() => {});
}
export function nativeMicrophoneSupported(): Promise<boolean> {
  if (!isTauri()) return Promise.resolve(false);
  return supported ??= invoke<boolean>('native_microphone_supported').catch(error => {
    supported = undefined;
    throw error;
  });
}
export async function microphoneDevices(): Promise<MicrophoneDevice[]> {
  if (await nativeMicrophoneSupported()) return invoke<MicrophoneDevice[]>('native_microphone_devices');
  return (await navigator.mediaDevices.enumerateDevices()).filter(device => device.kind === 'audioinput');
}

export async function openMicrophone(deviceId = '', systemProcessing = true): Promise<MediaStream> {
  if (!(await nativeMicrophoneSupported())) {
    return navigator.mediaDevices.getUserMedia({ audio: {
      ...(deviceId ? { deviceId: { ideal: deviceId } } : {}),
      echoCancellation: true, noiseSuppression: systemProcessing, autoGainControl: true,
    }, video: false });
  }
  // Windows never falls back to browser capture, including on native errors.
  const info = await invoke<{ id: string; deviceId: string; sampleRate: number }>('native_microphone_start', { deviceId, systemProcessing }).catch(error => {
    diagnostic('capture-error', String(error));
    if (String(error).startsWith('MIC_NOT_FOUND:')) throw new DOMException(String(error).slice(14), 'NotFoundError');
    throw new Error(String(error));
  });
  let context: AudioContext | undefined;
  let node: AudioWorkletNode | undefined;
  let track: MediaStreamTrack | undefined;
  let stopTrack: (() => void) | undefined;
  let nativeStream: MediaStream | undefined;
  let stopped = false;
  let inFlight = 0;
  let wake: (() => void) | undefined;
  const stop = (notify = false) => {
    if (stopped) return;
    stopped = true;
    if (nativeStream) nativeLevels.set(nativeStream, 0);
    wake?.();
    window.removeEventListener('beforeunload', unload);
    stopTrack?.();
    node?.disconnect();
    node?.port.close();
    void context?.close().catch(() => {});
    void invoke('native_microphone_stop', { id: info.id }).catch(() => {});
    if (notify) track?.dispatchEvent(new Event('ended'));
  };
  const unload = () => stop();
  try {
    window.addEventListener('beforeunload', unload, { once: true });
    if (info.sampleRate !== 48000) throw new Error('Unsupported microphone sample rate');
    context = new AudioContext({ sampleRate: info.sampleRate, latencyHint: 'interactive' });
    if (context.sampleRate !== info.sampleRate) throw new Error('Audio output sample rate mismatch');
    await context.audioWorklet.addModule(workletUrl);
    if (stopped) throw new Error('Microphone initialization cancelled');
    node = new AudioWorkletNode(context, 'mctier-native-microphone', { numberOfInputs: 0, numberOfOutputs: 1, outputChannelCount: [1] });
    node.port.onmessage = () => { inFlight = Math.max(0, inFlight - 1); wake?.(); wake = undefined; };
    const destination = context.createMediaStreamDestination();
    nativeStream = destination.stream;
    nativeLevels.set(nativeStream, 0);
    destination.channelCount = 1;
    node.connect(destination);
    track = destination.stream.getAudioTracks()[0];
    stopTrack = track.stop.bind(track);
    track.stop = () => stop();
    const settings = track.getSettings.bind(track);
    track.getSettings = () => ({ ...settings(), deviceId: info.deviceId, sampleRate: info.sampleRate, channelCount: 1 });
    await resumeAudioContext(context);
    if (stopped) throw new Error('Microphone initialization cancelled');
    const pump = async () => {
      let acknowledged = false;
      while (!stopped) {
        try {
          // Bound the MessagePort queue as well as the native and worklet ring buffers.
          if (inFlight >= 3) await new Promise<void>(resolve => { wake = resolve; });
          if (stopped) return;
          const bytes = await invoke<ArrayBuffer>('native_microphone_read', { id: info.id });
          if (stopped) return;
          if (!(bytes instanceof ArrayBuffer) || bytes.byteLength !== 960 * 4) throw new Error('Invalid microphone packet');
          nativeLevels.set(destination.stream, pcmRms(new Float32Array(bytes)));
          if (!acknowledged) { diagnostic('capture-ready', `input=${info.deviceId}, context=${context!.state}, sampleRate=${info.sampleRate}`); acknowledged = true; }
          inFlight++;
          node!.port.postMessage(bytes, [bytes]);
        } catch (error) {
          if (!stopped) { diagnostic('capture-error', String(error)); console.warn('Native microphone stopped', error); stop(true); }
        }
      }
    };
    node.onprocessorerror = () => { diagnostic('capture-error', 'AudioWorklet processor failed'); stop(true); };
    void pump();
    return destination.stream;
  } catch (error) { diagnostic('capture-error', String(error)); stop(); throw error; }
}
