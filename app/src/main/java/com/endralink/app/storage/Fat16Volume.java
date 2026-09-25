package com.endralink.app.storage;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.*;

/** Bounded FAT16 reader with conservative create-only file writes. */
public final class Fat16Volume {
    public interface SectorReader {
        long sectorCount();
        byte[] readSector(long lba) throws IOException;
    }
    public interface SectorWriter {
        void writeSector(long lba, byte[] data) throws IOException;
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

    private static final class Slot {
        final long lba;
        final int offset;
        Slot(long lba, int offset) { this.lba = lba; this.offset = offset; }
    }
    private static final class Existing {
        final String name;
        final boolean directory;
        final int cluster;
        final List<Slot> slots;
        Existing(String name, boolean directory, int cluster, List<Slot> slots) {
            this.name = name; this.directory = directory; this.cluster = cluster; this.slots = slots;
        }
    }

    private final SectorReader reader;
    private final SectorWriter writer;
    private final long start, total, fatStart, rootStart, dataStart;
    private final int sectorsPerCluster, rootEntries, rootSectors, clusters, fats, fatSectors;
    private long cachedFatSector = -1;
    private byte[] cachedFat;
    public final String label;
    public final long capacityBytes;

    /** Accept an unpartitioned volume or exactly one primary FAT16 MBR partition. */
    public Fat16Volume(SectorReader reader) throws IOException {
        this.reader = reader;
        this.writer = reader instanceof SectorWriter ? (SectorWriter) reader : null;
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
        int reserved = u16(boot, 14);
        fats = u8(boot, 16);
        fatSectors = u16(boot, 22);
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
            for (int cluster : directoryChain(firstCluster)) {
                long lba = clusterLba(cluster);
                if ((long) bytes.size() + sectorsPerCluster * 512L > 4 * 1024 * 1024) {
                    throw new IOException("Directory exceeds the 4 MiB safety limit.");
                }
                for (int i = 0; i < sectorsPerCluster; i++) bytes.write(sector(lba + i));
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

    /** Return currently free data bytes by scanning the FAT. */
    public long freeBytes() throws IOException {
        long free = 0;
        for (int cluster = 2; cluster <= clusters + 1; cluster++) {
            if (fatValue(cluster) == 0) free++;
        }
        return free * sectorsPerCluster * 512L;
    }

    /** True when this directory already contains the exact displayed file name, case-insensitively. */
    public boolean containsName(int directoryCluster, String fileName) throws IOException {
        return findExisting(directoryCluster, fileName) != null;
    }

    /**
     * Create or explicitly overwrite a file while preserving the selected filename through FAT long-name entries.
     * File data is written first, FAT copies second, directory metadata last.
     */
    public synchronized String writeFile(int directoryCluster, String fileName, byte[] data, boolean overwrite) throws IOException {
        if (writer == null) throw new IOException("This storage session does not permit writes.");
        if (data == null) throw new IOException("No file data supplied.");
        if (data.length > 64 * 1024 * 1024) throw new IOException("Selected file exceeds the 64 MiB transfer safety limit.");
        String preservedName = validateLongName(fileName);

        Existing existing = findExisting(directoryCluster, preservedName);
        if (existing != null && existing.directory) throw new IOException("A folder with that name already exists.");
        if (existing != null && !overwrite) throw new IOException("A file named " + preservedName + " already exists.");

        Set<String> aliases = shortAliases(directoryCluster);
        if (existing != null) {
            // Its current short entry may be reused without counting as a collision.
            byte[] oldShort = sector(existing.slots.get(existing.slots.size() - 1).lba);
            aliases.remove(rawAliasKey(oldShort, existing.slots.get(existing.slots.size() - 1).offset));
        }
        byte[] shortRaw = chooseShortAlias(preservedName, aliases);
        boolean needsLfn = !displayShortName(shortRaw).equals(preservedName);
        int lfnCount = needsLfn ? (preservedName.length() + 12) / 13 : 0;
        int neededSlots = lfnCount + 1;

        List<Slot> targetSlots;
        if (existing != null && existing.slots.size() >= neededSlots) {
            targetSlots = new ArrayList<>(existing.slots.subList(existing.slots.size() - neededSlots, existing.slots.size()));
        } else {
            targetSlots = findContiguousFreeSlots(directoryCluster, neededSlots);
        }

        int clusterBytes = sectorsPerCluster * 512;
        int neededClusters = data.length == 0 ? 0 : (data.length + clusterBytes - 1) / clusterBytes;
        List<Integer> allocated = findFreeClusters(neededClusters);

        int position = 0;
        for (int cluster : allocated) {
            long lba = clusterLba(cluster);
            for (int s = 0; s < sectorsPerCluster; s++) {
                byte[] block = new byte[512];
                int count = Math.min(512, Math.max(0, data.length - position));
                if (count > 0) System.arraycopy(data, position, block, 0, count);
                writer.writeSector(lba + s, block);
                position += count;
            }
        }

        boolean fatLinked = false;
        try {
            for (int i = 0; i < allocated.size(); i++) {
                int next = i + 1 < allocated.size() ? allocated.get(i + 1) : 0xffff;
                writeFatValue(allocated.get(i), next);
            }
            fatLinked = true;

            List<byte[]> records = buildDirectoryRecords(preservedName, shortRaw,
                allocated.isEmpty() ? 0 : allocated.get(0), data.length);
            for (int i = 0; i < records.size(); i++) writeSlot(targetSlots.get(i), records.get(i));

            if (existing != null) {
                Set<String> reused = new HashSet<>();
                for (Slot s : targetSlots) reused.add(s.lba + ":" + s.offset);
                for (Slot s : existing.slots) {
                    if (!reused.contains(s.lba + ":" + s.offset)) markDeleted(s);
                }
                if (existing.cluster >= 2) freeClusterChain(existing.cluster);
            }
            return preservedName;
        } catch (IOException e) {
            if (fatLinked) bestEffortFree(allocated);
            throw e;
        }
    }

    private List<Integer> findFreeClusters(int needed) throws IOException {
        List<Integer> result = new ArrayList<>();
        if (needed == 0) return result;
        for (int cluster = 2; cluster <= clusters + 1 && result.size() < needed; cluster++) {
            if (fatValue(cluster) == 0) result.add(cluster);
        }
        if (result.size() != needed) throw new IOException("Not enough free calculator storage for this file.");
        return result;
    }

    private List<Slot> directorySlots(int firstCluster) throws IOException {
        List<Slot> result = new ArrayList<>();
        if (firstCluster == 0) {
            int remaining = rootEntries;
            for (int s = 0; s < rootSectors && remaining > 0; s++) {
                int slots = Math.min(16, remaining);
                for (int i = 0; i < slots; i++) result.add(new Slot(rootStart + s, i * 32));
                remaining -= slots;
            }
        } else {
            for (int cluster : directoryChain(firstCluster)) {
                long lba = clusterLba(cluster);
                for (int s = 0; s < sectorsPerCluster; s++)
                    for (int i = 0; i < 16; i++) result.add(new Slot(lba + s, i * 32));
            }
        }
        return result;
    }

    private List<Slot> findContiguousFreeSlots(int firstCluster, int count) throws IOException {
        List<Slot> slots = directorySlots(firstCluster);
        List<Slot> run = new ArrayList<>();
        for (Slot slot : slots) {
            byte[] block = sector(slot.lba);
            int first = u8(block, slot.offset);
            if (first == 0 || first == 0xe5) {
                run.add(slot);
                if (run.size() == count) return new ArrayList<>(run);
            } else run.clear();
        }
        throw new IOException("This calculator folder does not have enough contiguous directory space for that filename.");
    }

    private Existing findExisting(int firstCluster, String target) throws IOException {
        List<Slot> slots = directorySlots(firstCluster);
        List<Slot> pending = new ArrayList<>();
        String[] longParts = null;
        int nextOrdinal = 0, longChecksum = -1;

        for (Slot slot : slots) {
            byte[] block = sector(slot.lba);
            int o = slot.offset, first = u8(block,o), attr = u8(block,o+11);
            if (first == 0) break;
            if (first == 0xe5) { pending.clear(); longParts=null; continue; }
            if (attr == 0x0f) {
                pending.add(slot);
                int ordinal = first & 0x1f;
                if ((first & 0x40) != 0) {
                    longParts = ordinal >= 1 && ordinal <= 20 ? new String[ordinal] : null;
                    nextOrdinal = ordinal;
                    longChecksum = u8(block,o+13);
                }
                if (longParts == null || ordinal != nextOrdinal || ordinal < 1 ||
                    u8(block,o+12) != 0 || u16(block,o+26) != 0 || u8(block,o+13) != longChecksum) {
                    longParts = null;
                } else {
                    StringBuilder part = new StringBuilder();
                    int[] positions = {1,3,5,7,9,14,16,18,20,22,24,28,30};
                    for (int p : positions) {
                        int ch=u16(block,o+p);
                        if(ch==0||ch==0xffff) break;
                        part.append((char)ch);
                    }
                    longParts[ordinal-1]=part.toString();
                    nextOrdinal--;
                }
                continue;
            }

            String name=shortName(block,o);
            if(longParts!=null && nextOrdinal==0 && checksum(block,o)==longChecksum) {
                String candidate=String.join("",longParts);
                if(!candidate.isEmpty()) name=candidate;
            }
            boolean directory=(attr&16)!=0;
            int cluster=u16(block,o+26);
            List<Slot> group=new ArrayList<>(pending); group.add(slot);
            pending.clear(); longParts=null;
            if((attr&8)==0 && !name.equals(".") && !name.equals("..") && name.equalsIgnoreCase(target))
                return new Existing(name,directory,cluster,group);
        }
        return null;
    }

    private Set<String> shortAliases(int firstCluster) throws IOException {
        Set<String> result=new HashSet<>();
        for(Slot slot:directorySlots(firstCluster)) {
            byte[] block=sector(slot.lba); int o=slot.offset, first=u8(block,o), attr=u8(block,o+11);
            if(first==0) break;
            if(first!=0xe5 && attr!=0x0f) result.add(rawAliasKey(block,o));
        }
        return result;
    }

    private static String rawAliasKey(byte[] block,int offset) {
        return new String(block,offset,11,Charset.forName("ISO-8859-1"));
    }

    private static String validateLongName(String name) throws IOException {
        if(name==null) throw new IOException("Selected file has no name.");
        String n=name.trim();
        if(n.isEmpty()||n.equals(".")||n.equals("..")||n.length()>255) throw new IOException("Invalid file name.");
        for(int i=0;i<n.length();i++) {
            char ch=n.charAt(i);
            if(ch<32 || "\\/:*?\"<>|".indexOf(ch)>=0) throw new IOException("File name contains a character FAT cannot store.");
        }
        if(n.endsWith(".")||n.endsWith(" ")) throw new IOException("File name cannot end with a dot or space.");
        return n;
    }

    private static byte[] chooseShortAlias(String name, Set<String> used) throws IOException {
        int dot=name.lastIndexOf('.');
        String base=dot>0?name.substring(0,dot):name;
        String ext=dot>0&&dot<name.length()-1?name.substring(dot+1):"";
        String cleanBase=sanitizeShortPart(base,8);
        String cleanExt=sanitizeShortPart(ext,3);
        if(cleanBase.isEmpty()) cleanBase="FILE";

        byte[] direct=packAlias(cleanBase,cleanExt);
        if(isStrict83(name) && !used.contains(new String(direct,Charset.forName("ISO-8859-1")))) return direct;

        String stem=sanitizeShortPart(base,6);
        if(stem.isEmpty()) stem="FILE";
        for(int n=1;n<=999999;n++) {
            String suffix="~"+n;
            int keep=Math.max(1,8-suffix.length());
            String candidate=sanitizeShortPart(base,keep);
            if(candidate.isEmpty()) candidate="FILE".substring(0,Math.min(keep,4));
            byte[] raw=packAlias(candidate+suffix,cleanExt);
            if(!used.contains(new String(raw,Charset.forName("ISO-8859-1")))) return raw;
        }
        throw new IOException("Could not create a unique FAT short-name alias.");
    }

    private static boolean isStrict83(String name) {
        int dot=name.lastIndexOf('.');
        String base=dot>0?name.substring(0,dot):name;
        String ext=dot>0&&dot<name.length()-1?name.substring(dot+1):"";
        if(base.length()<1||base.length()>8||ext.length()>3) return false;
        return name.equals(name.toUpperCase(Locale.ROOT)) &&
            sanitizeShortPart(base,8).equals(base) && sanitizeShortPart(ext,3).equals(ext);
    }

    private static byte[] packAlias(String base,String ext) {
        byte[] raw=new byte[11]; Arrays.fill(raw,(byte)' ');
        byte[] b=base.getBytes(Charset.forName("US-ASCII"));
        byte[] e=ext.getBytes(Charset.forName("US-ASCII"));
        System.arraycopy(b,0,raw,0,Math.min(8,b.length));
        System.arraycopy(e,0,raw,8,Math.min(3,e.length));
        return raw;
    }

    private static List<byte[]> buildDirectoryRecords(String longName, byte[] shortRaw, int cluster, long size) {
        List<byte[]> result=new ArrayList<>();
        boolean needsLfn=!displayShortName(shortRaw).equals(longName);
        if(needsLfn) {
            int count=(longName.length()+12)/13;
            int sum=shortChecksum(shortRaw);
            for(int ordinal=count;ordinal>=1;ordinal--) {
                byte[] rec=new byte[32]; Arrays.fill(rec,(byte)0xff);
                rec[0]=(byte)(ordinal | (ordinal==count?0x40:0));
                rec[11]=0x0f; rec[12]=0; rec[13]=(byte)sum; rec[26]=0; rec[27]=0;
                int[] positions={1,3,5,7,9,14,16,18,20,22,24,28,30};
                int start=(ordinal-1)*13;
                for(int i=0;i<13;i++) {
                    int idx=start+i;
                    int value=idx<longName.length()?longName.charAt(idx):(idx==longName.length()?0:0xffff);
                    put16(rec,positions[i],value);
                }
                result.add(rec);
            }
        }
        byte[] shortRec=new byte[32];
        System.arraycopy(shortRaw,0,shortRec,0,11);
        shortRec[11]=0x20;
        put16(shortRec,26,cluster); put32(shortRec,28,size);
        result.add(shortRec);
        return result;
    }

    private void writeSlot(Slot slot, byte[] record) throws IOException {
        byte[] block=sector(slot.lba);
        System.arraycopy(record,0,block,slot.offset,32);
        writer.writeSector(slot.lba,block);
    }

    private void markDeleted(Slot slot) throws IOException {
        byte[] block=sector(slot.lba);
        block[slot.offset]=(byte)0xe5;
        writer.writeSector(slot.lba,block);
    }

    private void freeClusterChain(int firstCluster) throws IOException {
        Set<Integer> seen=new HashSet<>();
        int cluster=firstCluster;
        while(cluster>=2 && cluster<0xfff8) {
            validCluster(cluster);
            if(!seen.add(cluster)) throw new IOException("Existing file cluster loop detected.");
            int next=fatValue(cluster);
            writeFatValue(cluster,0);
            if(next>=0xfff8) break;
            cluster=next;
        }
    }

    private List<Integer> directoryChain(int firstCluster) throws IOException {
        List<Integer> chain = new ArrayList<>();
        Set<Integer> visited = new HashSet<>();
        int cluster = firstCluster;
        while (true) {
            validCluster(cluster);
            if (!visited.add(cluster)) throw new IOException("Directory cluster loop detected.");
            chain.add(cluster);
            if ((long) chain.size() * sectorsPerCluster * 512L > 4 * 1024 * 1024) {
                throw new IOException("Directory exceeds the 4 MiB safety limit.");
            }
            int next = fatValue(cluster);
            if (next >= 0xfff8) break;
            validCluster(next);
            cluster = next;
        }
        return chain;
    }

    private long clusterLba(int cluster) throws IOException {
        validCluster(cluster);
        return dataStart + (long)(cluster - 2) * sectorsPerCluster;
    }

    private int fatValue(int cluster) throws IOException {
        long lba = fatStart + cluster * 2L / 512;
        if (lba != cachedFatSector) {
            cachedFat = sector(lba);
            cachedFatSector = lba;
        }
        return u16(cachedFat, cluster * 2 % 512);
    }

    private void writeFatValue(int cluster, int value) throws IOException {
        int sectorIndex = cluster * 2 / 512;
        int offset = cluster * 2 % 512;
        for (int copy = 0; copy < fats; copy++) {
            long lba = fatStart + (long)copy * fatSectors + sectorIndex;
            byte[] block = sector(lba);
            put16(block, offset, value);
            writer.writeSector(lba, block);
        }
        cachedFatSector = -1;
        cachedFat = null;
    }

    private void bestEffortFree(List<Integer> allocated) {
        for (int cluster : allocated) {
            try { writeFatValue(cluster, 0); } catch (IOException ignored) { return; }
        }
    }

    private void validCluster(int cluster) throws IOException {
        if (cluster < 2 || cluster >= 0xfff0 || cluster > clusters + 1) {
            throw new IOException("Invalid, free, reserved, or bad FAT16 cluster: " + cluster);
        }
    }

    private byte[] sector(long lba) throws IOException {
        if (lba < 0 || lba >= reader.sectorCount()) throw new IOException("Sector outside device.");
        byte[] data = reader.readSector(lba);
        if (data == null || data.length != 512) throw new IOException("Incomplete sector read.");
        return Arrays.copyOf(data, data.length);
    }

    private static String sanitizeShortPart(String input, int limit) {
        if (input == null || input.isEmpty()) return "";
        String allowed = "$%'-_@~\u0060!(){}^#&";
        String upper = input.toUpperCase(Locale.ROOT);
        StringBuilder out = new StringBuilder();
        boolean lastUnderscore = false;
        for (int i = 0; i < upper.length() && out.length() < limit; i++) {
            char ch = upper.charAt(i);
            char mapped = (ch <= 127 && (Character.isLetterOrDigit(ch) || allowed.indexOf(ch) >= 0)) ? ch : '_';
            if (mapped == '_' && lastUnderscore) continue;
            out.append(mapped);
            lastUnderscore = mapped == '_';
        }
        while (out.length() > 0 && out.charAt(out.length() - 1) == '_') out.setLength(out.length() - 1);
        return out.toString();
    }

    private static String displayShortName(byte[] raw) {
        String base = new String(raw, 0, 8, Charset.forName("US-ASCII")).trim();
        String ext = new String(raw, 8, 3, Charset.forName("US-ASCII")).trim();
        return ext.isEmpty() ? base : base + "." + ext;
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
        if ((raw[0] & 255) == 5) raw[0] = (byte)0xe5;
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
    private static int shortChecksum(byte[] raw) {
        int sum=0;
        for(int i=0;i<11;i++) sum=(((sum&1)<<7)+(sum>>1)+(raw[i]&255))&255;
        return sum;
    }

    private static void put16(byte[] b, int p, long value) {
        b[p] = (byte)value;
        b[p + 1] = (byte)(value >> 8);
    }

    private static void put32(byte[] b, int p, long value) {
        put16(b, p, value);
        put16(b, p + 2, value >> 16);
    }

    private static int u8(byte[] b, int p) { return b[p] & 255; }
    private static int u16(byte[] b, int p) { return u8(b,p) | (u8(b,p+1) << 8); }
    private static long u32(byte[] b, int p) { return (long)u16(b,p) | ((long)u16(b,p+2) << 16); }
}
