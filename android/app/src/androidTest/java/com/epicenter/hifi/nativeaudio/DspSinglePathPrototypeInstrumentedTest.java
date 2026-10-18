package com.epicenter.hifi.nativeaudio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Proof boundary: our player's PCM enters EpicenterAudioProcessor once and the
 * one resulting PCM buffer is what DefaultAudioSink writes to its AudioTrack.
 * This deliberately does not claim to intercept another app's AudioFlinger session.
 */
@RunWith(AndroidJUnit4.class)
public class DspSinglePathPrototypeInstrumentedTest {
  @Test
  public void ownPlaybackSessionProducesOneProcessedBufferWithNoParallelDryCopy() throws Exception {
    final int rate = 48_000;
    final int frames = rate / 100; // one 10 ms processor block
    final int bytes = frames * 2 * 2;
    EpicenterAudioProcessor processor = new EpicenterAudioProcessor();
    try {
      processor.configure(new AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_16BIT));
      processor.flush();
      processor.setEpicenterMode(true);
      processor.setEpicenterParams(100f, 45f, 50f, 100f, 100f);
      processor.setEpicenterEnabled(true);

      ByteBuffer dry = ByteBuffer.allocateDirect(bytes).order(ByteOrder.LITTLE_ENDIAN);
      for (int frame = 0; frame < frames; frame++) {
        short sample = (short) (5000 * Math.sin(2 * Math.PI * 55 * frame / rate));
        dry.putShort(sample).putShort(sample);
      }
      dry.flip();

      processor.queueInput(dry);
      ByteBuffer output = processor.getOutput().order(ByteOrder.LITTLE_ENDIAN);

      assertEquals("The sink receives one PCM block, not dry + wet blocks", bytes, output.remaining());
      assertEquals("Exactly one DSP pass was applied", 1L, processor.getProcessedBufferCount());
      boolean differsFromDry = false;
      for (int i = 0; i < bytes; i += 2) {
        if (output.getShort(i) != dry.getShort(i)) {
          differsFromDry = true;
          break;
        }
      }
      assertTrue("The sole output buffer is processed, not the untouched source", differsFromDry);
    } finally {
      processor.reset();
    }
  }
}
