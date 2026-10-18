package com.epicenter.hifi.dsp;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public class EpicenterDSPNativeInstrumentedTest {
    @Test
    public void runSelfTest_returnsTrue() {
        assertTrue(EpicenterDSPNative.runSelfTest());
    }

    @Test
    public void bassOnlyStemKeepsGeneratedSubAndRejectsVoiceBandForBothEngines() {
        for (boolean headphones : new boolean[] { false, true }) {
            EpicenterDSPNative dsp = new EpicenterDSPNative(48000, 2);
            try {
                dsp.setEnabled(true);
                dsp.setHeadphonesMode(headphones);
                dsp.setParams(90f, 45f, 50f, 100f, 100f);
                dsp.setBassOnlyControls(80f, 0.8f, false, 0f);
                double lowEnergy = 0.0;
                double voiceCorrelation = 0.0;
                int measured = 0;
                final int frames = 480;
                for (int block = 0; block < 250; block++) {
                    float[] pcm = new float[frames * 2];
                    for (int frame = 0; frame < frames; frame++) {
                        int n = block * frames + frame;
                        float input = (float) (0.20 * Math.sin(2.0 * Math.PI * 55.0 * n / 48000.0)
                            + 0.10 * Math.sin(2.0 * Math.PI * 1000.0 * n / 48000.0));
                        pcm[frame * 2] = pcm[frame * 2 + 1] = input;
                    }
                    dsp.processBassOnlyBuffer(pcm, 2, pcm.length);
                    if (block < 20) continue; // omit the oscillator and filter startup transient
                    for (int frame = 0; frame < frames; frame++) {
                        int n = block * frames + frame;
                        float output = pcm[frame * 2];
                        assertTrue("wet output must stay finite and unclipped", Float.isFinite(output) && Math.abs(output) <= 1.0f);
                        lowEnergy += output * output;
                        voiceCorrelation += output * Math.sin(2.0 * Math.PI * 1000.0 * n / 48000.0);
                        measured++;
                    }
                }
                double rms = Math.sqrt(lowEnergy / measured);
                double voiceAmplitude = Math.abs(voiceCorrelation) * 2.0 / measured;
                assertTrue("generated stem should contain sub energy; headphones=" + headphones, rms > 1.0e-5);
                assertTrue("1 kHz voice-band leakage should be strongly suppressed; headphones=" + headphones,
                    voiceAmplitude < 0.002);
            } finally { dsp.release(); }
        }
    }

    @Test
    public void releaseCanOverlapProcessingAndControlCallsWithoutDeletingLivePointer() throws Exception {
        for (int round = 0; round < 16; round++) {
            EpicenterDSPNative dsp = new EpicenterDSPNative(192000, 2);
            long releaseCount = EpicenterDSPNative.getReleaseCount();
            CountDownLatch started = new CountDownLatch(1);
            AtomicReference<Throwable> error = new AtomicReference<>();
            Thread audio = new Thread(() -> {
                try {
                    float[] buffer = new float[8192];
                    started.countDown();
                    for (int i = 0; i < 100; i++) dsp.processFloatBuffer(buffer, 2, buffer.length);
                } catch (Throwable failure) { error.set(failure); }
            });
            audio.start();
            started.await();
            dsp.setEnabled(true);
            dsp.setHeadphonesMode(true);
            dsp.setParams(75f, 45f, 50f, 100f, 100f);
            dsp.release();
            dsp.release();
            dsp.reset();
            dsp.setParams(10f, 45f, 50f, 100f, 100f);
            audio.join(5000);
            assertTrue("Processing thread must finish", !audio.isAlive());
            assertNull(error.get());
            assertEquals(releaseCount + 1, EpicenterDSPNative.getReleaseCount());
        }
    }
}
