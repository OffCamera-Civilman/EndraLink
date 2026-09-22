package com.endralink.app.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** USB Bulk-Only transport restricted to read/diagnostic SCSI commands on LUN 0. */
public final class ReadOnlyBot implements Fat16Volume.SectorReader {
    public interface BulkPipe {
        int send(byte[] data, int offset, int length) throws IOException;
        int receive(byte[] data, int offset, int length) throws IOException;
        void clearInputHalt() throws IOException;
    }
    private final BulkPipe pipe;
    private int tag;
    private long sectors;
    private boolean broken;

    public ReadOnlyBot(BulkPipe pipe) { this.pipe = pipe; }

    /** Consume unit-attention/not-ready sense with bounded retries, then read capacity. */
    public void initialize() throws IOException {
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                command(new byte[]{0,0,0,0,0,0}, 0);
                break;
            } catch (CommandFailed e) {
                byte[] sense = command(new byte[]{3,0,0,0,18,0}, 18);
                int key = sense[2] & 15;
                if (attempt == 2 || (key != 6 && key != 2)) throw new IOException("Calculator not ready (SCSI sense " + key + "). Reconnect in USB Flash mode.");
            }
        }
        byte[] capacity = command(new byte[]{0x25,0,0,0,0,0,0,0,0,0}, 8);
        ByteBuffer b = ByteBuffer.wrap(capacity).order(ByteOrder.BIG_ENDIAN);
        long last = Integer.toUnsignedLong(b.getInt()), size = Integer.toUnsignedLong(b.getInt());
        if (last == 0xffffffffL || size != 512) throw new IOException("Unsupported capacity or sector size: " + size + " bytes. This build requires 512-byte sectors.");
        sectors = last + 1;
    }

    @Override public long sectorCount() { return sectors; }

    @Override public byte[] readSector(long lba) throws IOException {
        if (lba < 0 || lba >= sectors) throw new IOException("Read outside USB device.");
        byte[] cdb = new byte[10];
        cdb[0] = 0x28; // READ(10); there is deliberately no WRITE command.
        ByteBuffer.wrap(cdb).order(ByteOrder.BIG_ENDIAN).putInt(2, (int)lba);
        cdb[8] = 1;
        return command(cdb, 512);
    }

    /** Package-private for transport fixtures. Reject all opcodes except TUR, sense, capacity, READ(10). */
    byte[] command(byte[] cdb, int length) throws IOException {
        if (broken) throw new IOException("USB protocol lost synchronization. Disconnect and reconnect.");
        int op = cdb[0] & 255;
        if (!(op == 0 || op == 3 || op == 0x25 || op == 0x28) || length < 0 || length > 512)
            throw new IOException("Command blocked by read-only policy.");
        int requestTag = ++tag;
        ByteBuffer cbw = ByteBuffer.allocate(31).order(ByteOrder.LITTLE_ENDIAN);
        cbw.putInt(0x43425355).putInt(requestTag).putInt(length).put((byte)0x80).put((byte)0).put((byte)cdb.length).put(cdb);
        try {
            if (pipe.send(cbw.array(), 0, 31) != 31) throw new IOException("Incomplete USB command.");
            byte[] result = new byte[length];
            int count = 0;
            while (count < length) {
                int n = pipe.receive(result, count, length - count);
                if (n < 0) { pipe.clearInputHalt(); break; }
                if (n > length - count) throw new IOException("Invalid USB data length.");
                if (n == 0) break;
                count += n;
                // A short data packet terminates the data phase.
                if (count < length) break;
            }
            byte[] status = new byte[13];
            int n = pipe.receive(status, 0, 13);
            if (n < 0) { pipe.clearInputHalt(); n = pipe.receive(status, 0, 13); }
            if (n != 13) throw new IOException("Invalid USB command-status length.");
            ByteBuffer csw = ByteBuffer.wrap(status).order(ByteOrder.LITTLE_ENDIAN);
            if (csw.getInt() != 0x53425355 || csw.getInt() != requestTag) throw new IOException("USB command-status signature/tag mismatch.");
            long residue = Integer.toUnsignedLong(csw.getInt());
            int state = csw.get() & 255;
            if (residue > length || state > 2) throw new IOException("Invalid USB command status.");
            if (state == 2) throw new IOException("USB phase error. Reconnect the calculator.");
            if (state == 1) throw new CommandFailed();
            if (residue != 0 || count != length) throw new IOException("Incomplete USB data response.");
            return result;
        } catch (CommandFailed e) {
            throw e;
        } catch (IOException e) {
            broken = true;
            throw e;
        }
    }

    private static final class CommandFailed extends IOException {
        private static final long serialVersionUID = 1L;
        CommandFailed() { super("Calculator rejected a read-only command."); }
    }
}
