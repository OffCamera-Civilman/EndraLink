package com.endralink.app.storage;

import org.junit.Test;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

/** Synthetic sector fixtures exercise filesystem boundaries without calculator hardware. */
public class Fat16VolumeTest {
    private static final int TOTAL = 8192, ROOT = 65, DATA = 67;
    private static final class Disk implements Fat16Volume.SectorReader, Fat16Volume.SectorWriter {
        final Map<Long, byte[]> sectors = new HashMap<>();
        long size = TOTAL;
        public long sectorCount() { return size; }
        public byte[] readSector(long lba) throws IOException {
            if (lba < 0 || lba >= size) throw new IOException("Fixture out of bounds");
            return sectors.getOrDefault(lba, new byte[512]);
        }
        public void writeSector(long lba, byte[] data) throws IOException {
            if (lba < 0 || lba >= size || data == null || data.length != 512) throw new IOException("Fixture write out of bounds");
            sectors.put(lba, Arrays.copyOf(data, data.length));
        }
        byte[] at(long lba) { return sectors.computeIfAbsent(lba, k -> new byte[512]); }
    }
    private static void le(byte[] b, int p, long value, int count) {
        for (int i=0;i<count;i++) b[p+i]=(byte)(value >> (8*i));
    }
    private static Disk disk() {
        Disk d = new Disk();
        byte[] b=d.at(0);
        b[0]=(byte)0xeb;
        le(b,11,512,2);b[13]=1;le(b,14,1,2);b[16]=2;le(b,17,32,2);
        le(b,19,TOTAL,2);b[21]=(byte)0xf8;le(b,22,32,2);
        b[510]=0x55;b[511]=(byte)0xaa;
        System.arraycopy("ENDRALINK  ".getBytes(StandardCharsets.US_ASCII),0,b,43,11);
        return d;
    }
    private static void entry(byte[] b,int p,String raw,int attr,int cluster,int size) {
        System.arraycopy(raw.getBytes(StandardCharsets.US_ASCII),0,b,p,11);
        b[p+11]=(byte)attr;le(b,p+26,cluster,2);le(b,p+28,size,4);
    }
    private static void failIO(IoCall c) throws Exception {
        try { c.run();fail("Expected IOException"); } catch(IOException expected) { }
    }
    private interface IoCall { void run() throws Exception; }

    @Test public void createsFileWithoutOverwritingAndLinksBothFats() throws Exception {
        Disk d=disk();Fat16Volume v=new Fat16Volume(d);
        byte[] payload=new byte[700];for(int i=0;i<payload.length;i++)payload[i]=(byte)i;
        v.writeFile(0,"HELLO.G3A",payload,false);
        List<Fat16Volume.Entry> entries=v.list(0);
        assertEquals(1,entries.size());assertEquals("HELLO.G3A",entries.get(0).name);assertEquals(700,entries.get(0).size);
        int first=entries.get(0).cluster;assertTrue(first>=2);
        int next=(d.at(1)[first*2]&255)|((d.at(1)[first*2+1]&255)<<8);
        assertTrue(next>=2);
        assertEquals(0xffff,(d.at(1)[next*2]&255)|((d.at(1)[next*2+1]&255)<<8));
        assertEquals(d.at(1)[first*2],d.at(33)[first*2]);
        failIO(()->v.writeFile(0,"hello.g3a",new byte[]{1},false));
    }
    @Test public void preservesOrdinaryAndroidFileNamesWithLfn() throws Exception {
        Disk d=disk();Fat16Volume v=new Fat16Volume(d);
        String stored=v.writeFile(0,"gba new.txt",new byte[]{1,2,3},false);
        assertEquals("gba new.txt",stored);
        assertEquals("gba new.txt",v.list(0).get(0).name);
    }
    @Test public void preservesLongNamesAndOverwritesOnlyWhenAllowed() throws Exception {
        Disk d=disk();Fat16Volume v=new Fat16Volume(d);
        String name="this-name-is-too-long.g3a";
        assertEquals(name,v.writeFile(0,name,new byte[]{1,2,3},false));
        failIO(()->v.writeFile(0,name,new byte[]{9,8},false));
        assertEquals(name,v.writeFile(0,name,new byte[]{9,8},true));
        List<Fat16Volume.Entry> e=v.list(0);
        assertEquals(1,e.size());assertEquals(name,e.get(0).name);assertEquals(2,e.get(0).size);
    }
    @Test public void reportsFreeSpaceAndConsumesItOnWrite() throws Exception {
        Disk d=disk();Fat16Volume v=new Fat16Volume(d);
        long before=v.freeBytes();
        v.writeFile(0,"SPACE.TXT",new byte[700],false);
        long after=v.freeBytes();
        assertEquals(1024,before-after);
    }
    @Test public void createsAndReusesLongNamedDirectory() throws Exception {
        Disk d=disk();Fat16Volume v=new Fat16Volume(d);
        int cluster=v.ensureDirectory(0,"My Games Folder");
        assertTrue(cluster>=2);
        List<Fat16Volume.Entry> root=v.list(0);
        assertEquals(1,root.size());assertTrue(root.get(0).directory);assertEquals("My Games Folder",root.get(0).name);
        assertEquals(cluster,v.ensureDirectory(0,"My Games Folder"));
        v.writeFile(cluster,"game one.g3a",new byte[]{1,2,3},false);
        assertEquals("game one.g3a",v.list(cluster).get(0).name);
    }
    @Test public void directoryNameCannotReplaceAFile() throws Exception {
        Disk d=disk();Fat16Volume v=new Fat16Volume(d);
        v.writeFile(0,"Games",new byte[]{1},false);
        failIO(()->v.ensureDirectory(0,"Games"));
    }
    @Test public void emptyRootAndLabel() throws Exception {
        Fat16Volume v=new Fat16Volume(disk());
        assertEquals("ENDRALINK",v.label);assertEquals(TOTAL*512L,v.capacityBytes);assertTrue(v.list(0).isEmpty());
    }
    @Test public void rootFilesAndFoldersSort() throws Exception {
        Disk d=disk();entry(d.at(ROOT),0,"TEST    G3A",32,2,1200);entry(d.at(ROOT),32,"PROGRAMS   ",16,3,0);
        List<Fat16Volume.Entry> e=new Fat16Volume(d).list(0);
        assertTrue(e.get(0).directory);assertEquals("PROGRAMS",e.get(0).name);
        assertEquals("TEST.G3A",e.get(1).name);assertEquals(1200,e.get(1).size);
    }
    @Test public void deletedAndVolumeLabelsSkipped() throws Exception {
        Disk d=disk();entry(d.at(ROOT),0,"DELETED TXT",32,2,0);d.at(ROOT)[0]=(byte)0xe5;
        entry(d.at(ROOT),32,"VOLUME     ",8,0,0);assertTrue(new Fat16Volume(d).list(0).isEmpty());
    }
    @Test public void subdirectoryRead() throws Exception {
        Disk d=disk();le(d.at(1),4,0xffff,2);
        entry(d.at(DATA),0,".          ",16,2,0);entry(d.at(DATA),32,"..         ",16,0,0);
        entry(d.at(DATA),64,"HELLO   PY ",32,3,14);
        assertEquals("HELLO.PY",new Fat16Volume(d).list(2).get(0).name);
    }
    @Test public void followsFragmentedChain() throws Exception {
        Disk d=disk();le(d.at(1),4,7,2);le(d.at(1),14,0xffff,2);
        for(int p=0;p<512;p+=32)d.at(DATA)[p]=(byte)0xe5;
        entry(d.at(DATA+5),0,"SECOND  TXT",32,8,8);
        assertEquals("SECOND.TXT",new Fat16Volume(d).list(2).get(0).name);
    }
    @Test public void rejectsClusterLoop() throws Exception {
        Disk d=disk();le(d.at(1),4,2,2);failIO(()->new Fat16Volume(d).list(2));
    }
    @Test public void rejectsBadCluster() throws Exception {
        Disk d=disk();le(d.at(1),4,0xfff7,2);failIO(()->new Fat16Volume(d).list(2));
    }
    @Test public void rejectsFreeClusterInChain() throws Exception {
        failIO(()->new Fat16Volume(disk()).list(2));
    }
    @Test public void rejectsOutsideCluster() throws Exception {
        failIO(()->new Fat16Volume(disk()).list(9000));
    }
    @Test public void readsMbrPartition() throws Exception {
        Disk d=disk(), partition=new Disk();partition.size=TOTAL+10;
        for(Map.Entry<Long,byte[]> e:d.sectors.entrySet())partition.sectors.put(e.getKey()+10,e.getValue());
        byte[] m=partition.at(0);m[510]=0x55;m[511]=(byte)0xaa;m[450]=6;
        le(m,454,10,4);le(m,458,TOTAL,4);
        assertTrue(new Fat16Volume(partition).list(0).isEmpty());
    }
    @Test public void rejectsMissingSignature() throws Exception {
        Disk d=disk();d.at(0)[510]=0;failIO(()->new Fat16Volume(d));
    }
    @Test public void rejectsFat12() throws Exception {
        Disk d=disk();le(d.at(0),19,2000,2);failIO(()->new Fat16Volume(d));
    }
    @Test public void rejectsVolumePastDevice() throws Exception {
        Disk d=disk();d.size=TOTAL-1;failIO(()->new Fat16Volume(d));
    }
    @Test public void rejectsShortFat() throws Exception {
        Disk d=disk();le(d.at(0),22,1,2);failIO(()->new Fat16Volume(d));
    }
    @Test public void rejectsShortSector() throws Exception {
        Disk d=disk();d.sectors.put(0L,new byte[64]);failIO(()->new Fat16Volume(d));
    }
    @Test public void honorsLowercaseShortNameFlags() throws Exception {
        Disk d=disk();entry(d.at(ROOT),0,"HELLO   PY ",32,3,14);d.at(ROOT)[12]=24;
        assertEquals("hello.py",new Fat16Volume(d).list(0).get(0).name);
    }
    @Test public void recognizesValidLongNameAndRejectsBadChecksum() throws Exception {
        Disk d=disk();byte[] b=d.at(ROOT);
        entry(b,32,"MYFILE~1TXT",32,2,22);
        int sum=0;for(int i=32;i<43;i++)sum=(((sum&1)<<7)+(sum>>1)+(b[i]&255))&255;
        b[0]=0x41;b[11]=15;b[13]=(byte)sum;
        int[] pos={1,3,5,7,9,14,16,18,20,22,24,28,30};
        String name="My file.txt";
        for(int i=0;i<pos.length;i++)le(b,pos[i],i<name.length()?name.charAt(i):i==name.length()?0:0xffff,2);
        assertEquals(name,new Fat16Volume(d).list(0).get(0).name);
        b[13]++;
        assertEquals("MYFILE~1.TXT",new Fat16Volume(d).list(0).get(0).name);
    }
}
