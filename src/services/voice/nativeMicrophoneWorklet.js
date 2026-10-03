// Voice uses a 100 ms ring; recording preserves batched PCM across renderer jitter.
class NativeMicrophoneProcessor extends AudioWorkletProcessor {
  constructor(options) {
    super();
    this.recording = options?.processorOptions?.recording === true;
    // At most 19 outstanding packets plus one incoming batch of 10 packets.
    this.buffer = new Float32Array(this.recording ? 28800 : 4800);
    this.primed = !this.recording;
    this.read = 0;
    this.length = 0;
    this.consumed = 0;
    this.rendered = 0;
    this.underrun = 0;
    this.port.onmessage = ({ data }) => {
      if (!(data instanceof ArrayBuffer) || data.byteLength < 3840 || data.byteLength % 3840 !== 0 || data.byteLength > (this.recording ? 38400 : 3840)) return;
      const samples = new Float32Array(data);
      for (const value of samples) {
        if (this.length === this.buffer.length) {
          this.read = (this.read + 1) % this.buffer.length;
          this.length--;
        }
        this.buffer[(this.read + this.length++) % this.buffer.length] = Number.isFinite(value) ? Math.max(-1, Math.min(1, value)) : 0;
      }
    };
  }
  process(_inputs, outputs) {
    const output = outputs[0]?.[0];
    // Recording tolerates a fixed jitter buffer; voice chat keeps its low latency.
    // Do not start the audio clock on a single 20 ms IPC packet then repeatedly
    // underrun while 1080p/60 screen frames occupy the renderer thread.
    if (!this.primed && this.length >= 14400) this.primed = true;
    if (!this.primed) { output?.fill(0); return true; }
    if (output) for (let index = 0; index < output.length; index++) {
      if (!this.length) this.underrun++;
      output[index] = this.length ? this.buffer[this.read] : 0;
      if (this.length) { this.read = (this.read + 1) % this.buffer.length; this.length--; this.consumed++; }
    }
    while (this.consumed >= 960) { this.consumed -= 960; this.port.postMessage('consumed'); }
    this.rendered += output?.length || 0;
    if (this.recording && this.rendered >= 48000) {
      this.port.postMessage({ underrun: this.underrun, buffered: this.length });
      this.rendered = 0; this.underrun = 0;
    }
    return true;
  }
}
registerProcessor('mctier-native-microphone', NativeMicrophoneProcessor);
