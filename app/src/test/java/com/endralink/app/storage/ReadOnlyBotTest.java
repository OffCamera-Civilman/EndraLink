package com.endralink.app.storage;

import org.junit.Test;
import java.io.IOException;
import java.nio.*;
import java.util.*;
import static org.junit.Assert.*;

/** Fake USB pipe verifies wire framing, error handling and the no-write policy. */
public class ReadOnlyBotTest {
    private static class Pipe implements ReadOnlyBot.BulkPipe {
        byte[] cbw;
        Queue<byte[]> incoming = new ArrayDeque<>();
        boolean badTag, badSignature, phaseError, shortData, badResidue, shortCommand, failReady;
        List<Integer> commands=new ArrayList<>();
        public int send(byte[] data,int offset,int length) {
            cbw=Arrays.copyOfRange(data,offset,offset+length);
            ByteBuffer b=ByteBuffer.wrap(cbw).order(ByteOrder.LITTLE_ENDIAN);
            assertEquals(31,length);assertEquals(0x43425355,b.getInt());
            int tag=b.getInt(), size=b.getInt(), op=cbw[15]&255;
            assertEquals(0x80,cbw[12]&255);commands.add(op);
            boolean failed=failReady&&op==0; if(failed)failReady=false;
            if(size>0) {
                byte[] payload=new byte[shortData?size-1:size];
                if(op==0x25)ByteBuffer.wrap(payload).putInt(8191).putInt(512);
                if(op==3)payload[2]=6;
                incoming.add(payload);
            }
            ByteBuffer status=ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN);
            status.putInt(badSignature?0:0x53425355).putInt(badTag?tag+1:tag)
                .putInt(badResidue?1:0).put((byte)(phaseError?2:failed?1:0));
            incoming.add(status.array());
            return shortCommand?30:31;
        }
        public int receive(byte[] data,int offset,int length) {
            byte[] source=incoming.remove();
            int n=Math.min(length,source.length);System.arraycopy(source,0,data,offset,n);return n;
        }
        public void clearInputHalt() {}
    }
    private interface Call { void run() throws Exception; }
    private static void failIO(Call c) throws Exception { try{c.run();fail("Expected IOException");}catch(IOException expected){} }

    @Test public void readsCapacityAndSectorWithCorrectCdb() throws Exception {
        Pipe p=new Pipe();ReadOnlyBot b=new ReadOnlyBot(p);b.initialize();
        assertEquals(8192,b.sectorCount());assertEquals(512,b.readSector(77).length);
        assertEquals(Arrays.asList(0,0x25,0x28),p.commands);
        assertEquals(77,ByteBuffer.wrap(p.cbw).getInt(17));assertEquals(1,p.cbw[23]);
    }
    @Test public void consumesUnitAttention() throws Exception {
        Pipe p=new Pipe();p.failReady=true;ReadOnlyBot b=new ReadOnlyBot(p);b.initialize();
        assertEquals(Arrays.asList(0,3,0,0x25),p.commands);
    }
    @Test public void refusesWritesBeforeSendingAnything() throws Exception {
        Pipe p=new Pipe();ReadOnlyBot b=new ReadOnlyBot(p);
        failIO(()->b.command(new byte[]{0x2a,0,0,0,0,0,0,0,1,0},512));
        assertTrue(p.commands.isEmpty());
    }
    @Test public void refusesOutOfRangeRead() throws Exception {
        Pipe p=new Pipe();ReadOnlyBot b=new ReadOnlyBot(p);b.initialize();
        failIO(()->b.readSector(8192));failIO(()->b.readSector(-1));assertEquals(2,p.commands.size());
    }
    @Test public void rejectsBadTagAndPoisonsSession() throws Exception {
        Pipe p=new Pipe();p.badTag=true;ReadOnlyBot b=new ReadOnlyBot(p);
        failIO(b::initialize);p.badTag=false;failIO(b::initialize);assertEquals(1,p.commands.size());
    }
    @Test public void rejectsBadSignature() throws Exception {
        Pipe p=new Pipe();p.badSignature=true;failIO(()->new ReadOnlyBot(p).initialize());
    }
    @Test public void rejectsPhaseError() throws Exception {
        Pipe p=new Pipe();p.phaseError=true;failIO(()->new ReadOnlyBot(p).initialize());
    }
    @Test public void rejectsShortCommand() throws Exception {
        Pipe p=new Pipe();p.shortCommand=true;failIO(()->new ReadOnlyBot(p).initialize());
    }
    @Test public void rejectsDataResidue() throws Exception {
        Pipe p=new Pipe();ReadOnlyBot b=new ReadOnlyBot(p);b.initialize();p.badResidue=true;
        failIO(()->b.readSector(0));
    }
    @Test public void rejectsShortData() throws Exception {
        Pipe p=new Pipe();ReadOnlyBot b=new ReadOnlyBot(p);b.initialize();p.shortData=true;
        failIO(()->b.readSector(0));
    }
}
