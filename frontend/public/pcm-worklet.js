class PcmWorkletProcessor extends AudioWorkletProcessor {
  constructor(options) {
    super()
    this.targetSampleRate = options.processorOptions.targetSampleRate || 16000
    this.frameDurationMs = options.processorOptions.frameDurationMs || 100
    this.sampleRatio = sampleRate / this.targetSampleRate
    this.cursor = 0
    this.samples = []
    this.frameSampleCount = Math.round((this.targetSampleRate * this.frameDurationMs) / 1000)
  }

  process(inputs) {
    const input = inputs[0]
    const channel = input && input[0]

    if (!channel) {
      return true
    }

    let cursor = this.cursor

    while (cursor < channel.length) {
      this.samples.push(channel[Math.floor(cursor)])
      cursor += this.sampleRatio
    }

    this.cursor = cursor - channel.length

    while (this.samples.length >= this.frameSampleCount) {
      const frame = this.samples.splice(0, this.frameSampleCount)
      const pcm = new Int16Array(frame.length)

      for (let index = 0; index < frame.length; index += 1) {
        const sample = Math.max(-1, Math.min(1, frame[index]))
        pcm[index] = sample < 0 ? sample * 0x8000 : sample * 0x7fff
      }

      this.port.postMessage(pcm.buffer, [pcm.buffer])
    }

    return true
  }
}

registerProcessor('pcm-worklet-processor', PcmWorkletProcessor)
