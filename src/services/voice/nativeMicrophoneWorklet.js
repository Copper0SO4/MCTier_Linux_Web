// Fixed 100 ms ring: never replay stale speech after a slow or backgrounded UI.
class NativeMicrophoneProcessor extends AudioWorkletProcessor {
  constructor() {
    super();
    this.buffer = new Float32Array(4800);
    this.read = 0;
    this.length = 0;
    this.consumed = 0;
    this.port.onmessage = ({ data }) => {
      if (!(data instanceof ArrayBuffer) || data.byteLength !== 3840) return;
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
    if (output) for (let index = 0; index < output.length; index++) {
      output[index] = this.length ? this.buffer[this.read] : 0;
      if (this.length) { this.read = (this.read + 1) % this.buffer.length; this.length--; this.consumed++; }
    }
    if (this.consumed >= 960) { this.consumed -= 960; this.port.postMessage('consumed'); }
    return true;
  }
}
registerProcessor('mctier-native-microphone', NativeMicrophoneProcessor);
