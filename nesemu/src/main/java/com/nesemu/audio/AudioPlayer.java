package com.nesemu.audio;

import javax.sound.sampled.*;
import com.nesemu.apu.Apu2A03;

/**
 * Simple JavaSound player that drains float samples [0,1] from Apu2A03,
 * converts to 16-bit PCM signed mono, and writes to a SourceDataLine.
 */
public class AudioPlayer implements Runnable {
    private final Apu2A03 apu;
    private final int sampleRate;
    private volatile boolean running = false;
    private Thread thread;
    private SourceDataLine line;

    public AudioPlayer(Apu2A03 apu, int sampleRate) {
        this.apu = apu;
        this.sampleRate = sampleRate;
    }

    public synchronized void start() {
        if (running)
            return;
        try {
            AudioFormat fmt = new AudioFormat(sampleRate, 16, 1, true, false);
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, fmt);
            line = (SourceDataLine) AudioSystem.getLine(info);
            line.open(fmt, 4096);
            line.start();
            running = true;
            thread = new Thread(this, "NES-Audio");
            thread.setDaemon(true);
            thread.start();
        } catch (Exception e) {
            System.err.println("Audio init failed: " + e.getMessage());
            running = false;
        }
    }

    public synchronized void stop() {
        running = false;
        if (thread != null) {
            try {
                thread.join(200);
            } catch (InterruptedException ignore) {
            }
            thread = null;
        }
        if (line != null) {
            try {
                line.drain();
            } catch (Exception ignore) {
            }
            try {
                line.stop();
            } catch (Exception ignore) {
            }
            try {
                line.close();
            } catch (Exception ignore) {
            }
            line = null;
        }
    }

    @Override
    public void run() {
        byte[] out = new byte[2048]; // bytes
        while (running) {
            int available = apu.getPendingSampleCount();
            if (available <= 0) {
                try {
                    Thread.sleep(2);
                } catch (InterruptedException ignore) {
                }
                continue;
            }
            int frames = Math.min(available, out.length / 2);
            int idx = 0;
            for (int i = 0; i < frames; i++) {
                float f = apu.readSample(); // 0..1
                // convert to signed 16-bit mono centered around 0
                int s = (int) Math.round((f - 0.5f) * 2.0f * 32767.0);
                if (s < -32768)
                    s = -32768;
                if (s > 32767)
                    s = 32767;
                out[idx++] = (byte) (s & 0xFF);
                out[idx++] = (byte) ((s >>> 8) & 0xFF);
            }
            if (idx > 0 && line != null) {
                line.write(out, 0, idx);
            }
        }
    }
}
