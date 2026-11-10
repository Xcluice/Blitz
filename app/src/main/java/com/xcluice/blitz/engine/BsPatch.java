package com.xcluice.blitz.engine;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.zip.InflaterInputStream;

/** Applies a "BLZ1" patch (bsdiff blocks in one zlib stream) to an old file. */
public final class BsPatch {
    private BsPatch() {}

    private static long le64(byte[] b, int o) {
        long v = 0;
        for (int i = 7; i >= 0; i--) v = (v << 8) | (b[o + i] & 0xFF);
        return v;
    }

    private static long offtin(byte[] b, int o) {
        long y = b[o + 7] & 0x7F;
        for (int i = 6; i >= 0; i--) y = (y << 8) | (b[o + i] & 0xFF);
        return (b[o + 7] & 0x80) != 0 ? -y : y;
    }

    public static void apply(File oldFile, File patch, File out) throws IOException {
        byte[] old;
        try (RandomAccessFile r = new RandomAccessFile(oldFile, "r")) {
            if (r.length() > 200L * 1024 * 1024) throw new IOException("old file too large");
            old = new byte[(int) r.length()];
            r.readFully(old);
        }
        try (InputStream raw = new FileInputStream(patch)) {
            DataInputStream in = new DataInputStream(raw);
            byte[] head = new byte[36];
            in.readFully(head);
            if (head[0] != 'B' || head[1] != 'L' || head[2] != 'Z' || head[3] != '1') {
                throw new IOException("bad patch");
            }
            long newSize = le64(head, 4);
            long ctrlLen = le64(head, 12);
            long diffLen = le64(head, 20);
            long extraLen = le64(head, 28);
            long cap = 200L * 1024 * 1024;
            if (newSize < 0 || newSize > cap || ctrlLen < 0 || ctrlLen > cap
                    || diffLen < 0 || diffLen > cap || extraLen < 0 || extraLen > cap) {
                throw new IOException("bad patch sizes");
            }
            DataInputStream z = new DataInputStream(new InflaterInputStream(in));
            byte[] ctrl = new byte[(int) ctrlLen];
            byte[] diff = new byte[(int) diffLen];
            byte[] extra = new byte[(int) extraLen];
            z.readFully(ctrl);
            z.readFully(diff);
            z.readFully(extra);

            byte[] nw = new byte[(int) newSize];
            int newPos = 0, oldPos = 0, cp = 0, dp = 0, ep = 0;
            while (newPos < newSize) {
                if (cp + 24 > ctrl.length) throw new IOException("ctrl overrun");
                long x = offtin(ctrl, cp);
                long y = offtin(ctrl, cp + 8);
                long s = offtin(ctrl, cp + 16);
                cp += 24;
                if (x < 0 || y < 0 || newPos + x > newSize || dp + x > diff.length) {
                    throw new IOException("corrupt patch");
                }
                for (int i = 0; i < x; i++) {
                    int b = diff[dp + i];
                    int oi = oldPos + i;
                    if (oi >= 0 && oi < old.length) b += old[oi];
                    nw[newPos + i] = (byte) b;
                }
                newPos += (int) x;
                oldPos += (int) x;
                dp += (int) x;
                if (newPos + y > newSize || ep + y > extra.length) throw new IOException("corrupt patch");
                System.arraycopy(extra, ep, nw, newPos, (int) y);
                newPos += (int) y;
                ep += (int) y;
                oldPos += (int) s;
            }
            try (FileOutputStream fo = new FileOutputStream(out)) {
                fo.write(nw);
            }
        }
    }
}
