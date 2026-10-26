package com.xiso.extractor;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;

/**
 * Reads an original Xbox disc image (XDVDFS / "xiso"). Pure Java, no Android classes,
 * so it can be tested on a PC. Same layout rules as extract-xiso: plain xiso and the
 * redump partition offsets (XGD1, XGD2, XGD3) are detected automatically.
 */
public final class XisoReader {

    public static final int SECTOR = 2048;
    private static final long HEADER_OFFSET = 0x10000L;
    private static final byte[] MAGIC = "MICROSOFT*XBOX*MEDIA".getBytes(StandardCharsets.US_ASCII);
    // plain image, XGD2 ("global"), XGD3, XGD1
    private static final long[] DISC_OFFSETS = {0L, 0x0FD90000L, 0x02080000L, 0x18300000L};
    private static final int ATTR_DIR = 0x10;
    private static final int MAX_DIR_BYTES = 32 * 1024 * 1024;

    public static final class Entry {
        public final String path;      // relative, '/' separated, no leading slash
        public final boolean dir;
        public final long size;
        final long position;           // absolute byte position in the image (files only)

        Entry(String path, boolean dir, long size, long position) {
            this.path = path;
            this.dir = dir;
            this.size = size;
            this.position = position;
        }

        /** File or folder name without its parent path. */
        public String name() {
            return path.substring(path.lastIndexOf('/') + 1);
        }

        /** Parent folder path ("" for the root). */
        public String parent() {
            int i = path.lastIndexOf('/');
            return i < 0 ? "" : path.substring(0, i);
        }

        /** Absolute byte position of the data in the image file. */
        public long position() {
            return position;
        }
    }

    public interface Cancel {
        boolean isCancelled();
    }

    public interface Progress {
        void onBytes(long bytes);
    }

    private final FileChannel ch;
    private final long fileSize;
    private final long discOffset;
    private final long rootSector;
    private final long rootSize;

    public XisoReader(FileChannel channel) throws IOException {
        this.ch = channel;
        this.fileSize = channel.size();

        long found = -1;
        byte[] head = new byte[28];
        for (long off : DISC_OFFSETS) {
            long pos = HEADER_OFFSET + off;
            if (pos + head.length > fileSize) continue;
            readFully(head, 0, head.length, pos);
            if (Arrays.equals(Arrays.copyOf(head, MAGIC.length), MAGIC)) {
                found = off;
                break;
            }
        }
        if (found < 0) throw new IOException("Bukan file ISO Xbox yang valid (header tidak ditemukan)");
        discOffset = found;

        ByteBuffer bb = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
        rootSector = bb.getInt(20) & 0xFFFFFFFFL;
        rootSize = bb.getInt(24) & 0xFFFFFFFFL;
        if (rootSector == 0 && rootSize == 0) throw new IOException("ISO ini tidak berisi file");
    }

    /** Offset of the game partition inside the image (0 for a plain xiso). */
    public long discOffset() {
        return discOffset;
    }

    /** Lists every directory and file. Parents always come before their children. */
    public List<Entry> list(boolean skipSystemUpdate) throws IOException {
        List<Entry> out = new ArrayList<>();
        ArrayDeque<Object[]> pending = new ArrayDeque<>();   // {prefix, sector, size}
        pending.add(new Object[]{"", rootSector, rootSize});

        while (!pending.isEmpty()) {
            Object[] d = pending.poll();
            String prefix = (String) d[0];
            long sector = (Long) d[1];
            long size = (Long) d[2];
            if (size == 0) continue;
            if (size > MAX_DIR_BYTES) throw new IOException("Tabel direktori terlalu besar, ISO rusak?");
            long pos = sector * SECTOR + discOffset;
            if (pos + size > fileSize) throw new IOException("Direktori di luar batas file, ISO terpotong atau rusak");

            byte[] dir = new byte[(int) size];
            readFully(dir, 0, dir.length, pos);
            ByteBuffer bb = ByteBuffer.wrap(dir).order(ByteOrder.LITTLE_ENDIAN);

            // the directory is a binary tree; offsets are in dwords, 0 means "no child"
            ArrayDeque<Integer> todo = new ArrayDeque<>();
            Set<Integer> seen = new HashSet<>();
            todo.add(0);
            while (!todo.isEmpty()) {
                int off = todo.poll();
                long byteOff = (long) off * 4;
                if (byteOff + 14 > dir.length || !seen.add(off)) continue;
                int p = (int) byteOff;
                int left = bb.getShort(p) & 0xFFFF;
                int right = bb.getShort(p + 2) & 0xFFFF;
                if (left == 0xFFFF && right == 0xFFFF) continue;       // padding: empty directory
                long start = bb.getInt(p + 4) & 0xFFFFFFFFL;
                long len = bb.getInt(p + 8) & 0xFFFFFFFFL;
                int attr = dir[p + 12] & 0xFF;
                int nameLen = dir[p + 13] & 0xFF;
                if (p + 14 + nameLen > dir.length) throw new IOException("Entri direktori rusak");
                String name = new String(dir, p + 14, nameLen, StandardCharsets.ISO_8859_1);

                if (left != 0) todo.add(left);
                if (right != 0) todo.add(right);

                if (name.isEmpty() || name.equals(".") || name.equals("..")
                        || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) {
                    continue;   // never let an image write outside the target folder
                }
                if (skipSystemUpdate && prefix.isEmpty() && name.equals("$SystemUpdate")) continue;

                String path = prefix + name;
                if ((attr & ATTR_DIR) != 0) {
                    out.add(new Entry(path, true, 0, 0));
                    pending.add(new Object[]{path + "/", start, len});
                } else {
                    long fpos = start * SECTOR + discOffset;
                    if (len > 0 && fpos + len > fileSize) {
                        throw new IOException("File '" + path + "' melewati akhir ISO (ISO terpotong?)");
                    }
                    out.add(new Entry(path, false, len, fpos));
                }
            }
        }

        Collections.sort(out, new Comparator<Entry>() {
            @Override public int compare(Entry a, Entry b) {
                return a.path.compareToIgnoreCase(b.path);
            }
        });
        return out;
    }

    public static long totalBytes(List<Entry> entries) {
        long t = 0;
        for (Entry e : entries) if (!e.dir) t += e.size;
        return t;
    }

    /** Reads up to len bytes of a file starting at off (for previews). */
    public byte[] read(Entry e, long off, int len) throws IOException {
        if (off >= e.size) return new byte[0];
        int n = (int) Math.min((long) len, e.size - off);
        byte[] b = new byte[n];
        readFully(b, 0, n, e.position + off);
        return b;
    }

    /** Copies one file's data to out. */
    public void extract(Entry e, OutputStream out, byte[] buf, Progress progress, Cancel cancel) throws IOException {
        long done = 0;
        while (done < e.size) {
            if (cancel != null && cancel.isCancelled()) throw new CancellationException();
            int n = (int) Math.min(buf.length, e.size - done);
            readFully(buf, 0, n, e.position + done);
            out.write(buf, 0, n);
            done += n;
            if (progress != null) progress.onBytes(n);
        }
    }

    private void readFully(byte[] b, int off, int len, long pos) throws IOException {
        ByteBuffer bb = ByteBuffer.wrap(b, off, len);
        while (bb.hasRemaining()) {
            int r = ch.read(bb, pos + bb.position() - off);
            if (r < 0) throw new IOException("Akhir file tak terduga (ISO terpotong?)");
        }
    }
}
