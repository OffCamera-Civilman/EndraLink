package com.endralink.app.storage;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.*;

/** Bounded, read-only FAT16 directory reader. No sector-write API exists. */
public final class Fat16Volume {
    public interface SectorReader {
        long sectorCount();
        byte[] readSector(long lba) throws IOException;
    }

    public static final class Entry {
        public final String name;
        public final boolean directory;
        public final long size;
        public final int cluster;
        Entry(String name, boolean directory, long size, int cluster) {
            this.name = name;
            this.directory = directory;
            this.size = size;
            this.cluster = cluster;
        }
    }

    private final SectorReader reader;
    private final long start, total, fatStart, rootStart, dataStart;
    private final int sectorsPerCluster, rootEntries, rootSectors, clusters;
    private long cachedFatSector = -1;
    private byte[] cachedFat;
    public final String label;
    public final long capacityBytes;

    /** Accept an unpartitioned volume or exactly one primary FAT16 MBR partition. */
    public Fat16Volume(SectorReader reader) throws IOException {
        this.reader = reader;
        byte[] first = sector(0);
        long base = 0, limit = reader.sectorCount();
        byte[] boot = first;
        if (!looksLikeBoot(first)) {
            signature(first);
            int matches = 0;
            for (int i = 0; i < 4; i++) {
                int p = 446 + i * 16, type = u8(first, p + 4);
                if (type == 4 || type == 6 || type == 14) {
                    base = u32(first, p + 8);
                    limit = u32(first, p + 12);
                    matches++;
                }
            }
            if (matches != 1) throw new IOException("Expected one FAT16 partition; found " + matches + ". FAT12/FAT32/exFAT are not supported in this build.");
            if (base < 1 || limit < 1 || base + limit > reader.sectorCount()) throw new IOException("Invalid partition bounds.");
            boot = sector(base);
        }
        signature(boot);
        if (!looksLikeBoot(boot)) throw new IOException("Unsupported or invalid FAT boot sector (512-byte FAT16 required).");
        start = base;
        sectorsPerCluster = u8(boot, 13);
        int reserved = u16(boot, 14), fats = u8(boot, 16), fatSectors = u16(boot, 22);
        rootEntries = u16(boot, 17);
        total = u16(boot, 19) != 0 ? u16(boot, 19) : u32(boot, 32);
        rootSectors = (rootEntries * 32 + 511) / 512;
        long overhead = reserved + (long) fats * fatSectors + rootSectors;
        if (total <= overhead || total > limit || start + total > reader.sectorCount()) throw new IOException("FAT volume exceeds device or partition bounds.");
        long count = (total - overhead) / sectorsPerCluster;
        if (count < 4085 || count >= 65525) throw new IOException("This volume is not FAT16 (cluster count " + count + ").");
        if ((long) fatSectors * 512 < (count + 2) * 2) throw new IOException("FAT allocation table is too short.");
        clusters = (int) count;
        fatStart = start + reserved;
        rootStart = fatStart + (long) fats * fatSectors;
        dataStart = rootStart + rootSectors;
        label = new String(boot, 43, 11, Charset.forName("IBM437")).trim();
        capacityBytes = total * 512;
    }

    /** List the fixed root (cluster 0) or a bounded subdirectory cluster chain. */
    public List<Entry> list(int firstCluster) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        if (firstCluster == 0) {
            for (int i = 0; i < rootSectors; i++) bytes.write(sector(rootStart + i));
        } else {
            Set<Integer> visited = new HashSet<>();
            int cluster = firstCluster;
            while (true) {
                validCluster(cluster);
                if (!visited.add(cluster)) throw new IOException("Directory cluster loop detected.");
                if ((long) bytes.size() + sectorsPerCluster * 512L > 4 * 1024 * 1024) throw new IOException("Directory exceeds the 4 MiB safety limit.");
                long lba = dataStart + (long) (cluster - 2) * sectorsPerCluster;
                for (int i = 0; i < sectorsPerCluster; i++) bytes.write(sector(lba + i));
                int next = fatValue(cluster);
                if (next >= 0xfff8) break;
                validCluster(next);
                cluster = next;
            }
        }
        byte[] data = bytes.toByteArray();
        int length = firstCluster == 0 ? rootEntries * 32 : data.length;
        List<Entry> entries = new ArrayList<>();
        String[] longParts = null;
        int nextOrdinal = 0, longChecksum = -1;
        for (int offset = 0; offset + 32 <= length; offset += 32) {
            int first = u8(data, offset), attr = u8(data, offset + 11);
            if (first == 0) break;
            if (first == 0xe5) { longParts = null; continue; }
            if (attr == 0x0f) {
                int ordinal = first & 0x1f;
                if ((first & 0x40) != 0) {
                    longParts = ordinal >= 1 && ordinal <= 20 ? new String[ordinal] : null;
                    nextOrdinal = ordinal;
                    longChecksum = u8(data, offset + 13);
                }
                if (longParts == null || ordinal != nextOrdinal || ordinal < 1 ||
                        u8(data, offset + 12) != 0 || u16(data, offset + 26) != 0 ||
                        u8(data, offset + 13) != longChecksum || (first & 0xa0) != 0) {
                    longParts = null;
                } else {
                    StringBuilder part = new StringBuilder();
                    int[] positions = {1,3,5,7,9,14,16,18,20,22,24,28,30};
                    for (int p : positions) {
                        int ch = u16(data, offset + p);
                        if (ch == 0 || ch == 0xffff) break;
                        part.append((char) ch);
                    }
                    longParts[ordinal - 1] = part.toString();
                    nextOrdinal--;
                }
                continue;
            }
            String name = shortName(data, offset);
            if (longParts != null && nextOrdinal == 0 && checksum(data, offset) == longChecksum) {
                String candidate = String.join("", longParts);
                if (!candidate.isEmpty() && candidate.length() <= 255 && candidate.indexOf('/') < 0 && candidate.indexOf('\\') < 0) name = candidate;
            }
            longParts = null;
            if ((attr & 8) != 0 || name.equals(".") || name.equals("..")) continue;
            boolean directory = (attr & 16) != 0;
            int cluster = u16(data, offset + 26);
            if (directory) validCluster(cluster);
            entries.add(new Entry(name, directory, u32(data, offset + 28), cluster));
            if (entries.size() > 4096) throw new IOException("Directory exceeds 4096 displayed entries.");
        }
        entries.sort(Comparator.comparing((Entry e) -> !e.directory).thenComparing(e -> e.name, String.CASE_INSENSITIVE_ORDER));
        return entries;
    }

    private int fatValue(int cluster) throws IOException {
        long lba = fatStart + cluster * 2L / 512;
        if (lba != cachedFatSector) { cachedFat = sector(lba); cachedFatSector = lba; }
        return u16(cachedFat, cluster * 2 % 512);
    }

    private void validCluster(int cluster) throws IOException {
        if (cluster < 2 || cluster >= 0xfff0 || cluster > clusters + 1) throw new IOException("Invalid, free, reserved, or bad FAT16 cluster: " + cluster);
    }

    private byte[] sector(long lba) throws IOException {
        if (lba < 0 || lba >= reader.sectorCount()) throw new IOException("Sector outside device.");
        byte[] data = reader.readSector(lba);
        if (data == null || data.length != 512) throw new IOException("Incomplete sector read.");
        return data;
    }

    private static boolean looksLikeBoot(byte[] b) {
        int spc = u8(b, 13);
        return (u8(b, 0) == 0xeb || u8(b, 0) == 0xe9) && u16(b, 11) == 512 &&
                spc >= 1 && spc <= 128 && (spc & (spc - 1)) == 0 &&
                u16(b, 14) > 0 && u8(b, 16) >= 1 && u8(b, 16) <= 2 &&
                u16(b, 17) > 0 && u16(b, 22) > 0;
    }

    private static void signature(byte[] b) throws IOException {
        if (u8(b, 510) != 0x55 || u8(b, 511) != 0xaa) throw new IOException("Missing FAT/MBR boot signature.");
    }

    private static String shortName(byte[] data, int offset) {
        byte[] raw = Arrays.copyOfRange(data, offset, offset + 11);
        if ((raw[0] & 255) == 5) raw[0] = (byte) 0xe5;
        String base = new String(raw, 0, 8, Charset.forName("IBM437")).trim();
        String ext = new String(raw, 8, 3, Charset.forName("IBM437")).trim();
        int flags = u8(data, offset + 12);
        if ((flags & 8) != 0) base = base.toLowerCase(Locale.ROOT);
        if ((flags & 16) != 0) ext = ext.toLowerCase(Locale.ROOT);
        return ext.isEmpty() ? base : base + "." + ext;
    }

    private static int checksum(byte[] data, int offset) {
        int sum = 0;
        for (int i = 0; i < 11; i++) sum = (((sum & 1) << 7) + (sum >> 1) + u8(data, offset + i)) & 255;
        return sum;
    }
    private static int u8(byte[] b, int p) { return b[p] & 255; }
    private static int u16(byte[] b, int p) { return u8(b,p) | (u8(b,p+1) << 8); }
    private static long u32(byte[] b, int p) { return (long)u16(b,p) | ((long)u16(b,p+2) << 16); }
}
