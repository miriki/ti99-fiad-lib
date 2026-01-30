package com.miriki.ti99.fiad.io;

import java.nio.charset.StandardCharsets;

import com.miriki.ti99.dskimg.domain.FileDescriptorRecord;
import com.miriki.ti99.dskimg.domain.Ti99File;

public class TiFilesHeader {

    private final String fileName;
    private final String fileType;
    private final int recordLength;
    private final int flags;

    public TiFilesHeader(String fileName, String fileType, int recordLength, int flags) {
        this.fileName = fileName;
        this.fileType = fileType;
        this.recordLength = recordLength;
        this.flags = flags;
    }

    public boolean isProgram()   { return (flags & 0x01) != 0; }
    public boolean isInternal()  { return (flags & 0x02) != 0; }
    public boolean isProtected() { return (flags & 0x08) != 0; }
    public boolean isModified()  { return (flags & 0x10) != 0; }
    public boolean isEmulated()  { return (flags & 0x20) != 0; }
    public boolean isVariable()  { return (flags & 0x80) != 0; }
    public boolean isFixed()     { return !isVariable(); }
    public boolean isDisplay()   { return !isProgram() && !isInternal(); }
    
    public String getFileName() { return fileName; }
    public String getFileType() { return fileType; }
    public int getRecordLength() { return recordLength; }
    public int getFlags() { return flags; }

    // ---------------------------------------------------------
    // Header-Erkennung
    // ---------------------------------------------------------
    public static boolean isTiFilesHeader(byte[] raw) {
        if (raw.length < 128) return false;

        // Byte 0 = 0x07
        if ((raw[0] & 0xFF) != 0x07) return false;

        // Byte 1–7 = "TIFILES"
        String magic = new String(raw, 1, 7, StandardCharsets.US_ASCII);
        return magic.equals("TIFILES");
    }

    // ---------------------------------------------------------
    // Header-Parsen
    // ---------------------------------------------------------
    public static TiFilesHeader parse(byte[] raw) {
        if (!isTiFilesHeader(raw)) {
            throw new IllegalArgumentException("Kein gültiger TIFILES-Header");
        }

        // Filename: Offset 0x10–0x19 (10 Bytes)
        String name = new String(raw, 0x10, 10, StandardCharsets.US_ASCII).trim();

        // Flags: Offset 0x0A
        int flags = raw[0x0A] & 0xFF;

        // Record Length: Offset 0x0D
        int recLen = raw[0x0D] & 0xFF;

        String type = decodeFileType(flags);

        return new TiFilesHeader(name, type, recLen, flags);
    }

    // ---------------------------------------------------------
    // Header-Erzeugen
    // ---------------------------------------------------------
    public static byte[] build(Ti99File file, FileDescriptorRecord fdr) {

        byte[] header = new byte[128];

        // Magic
        header[0] = 0x07;
        System.arraycopy("TIFILES".getBytes(StandardCharsets.US_ASCII), 0, header, 1, 7);

        // Used sectors (big endian)
        int used = fdr.getUsedSectors();
        header[8]  = (byte) ((used >> 8) & 0xFF);
        header[9]  = (byte) (used & 0xFF);

        // Flags
        header[10] = (byte) file.getFlags();

        // Records per sector (big endian)
        int rps = file.getRecordsPerSector();
        header[11] = (byte) ((rps >> 8) & 0xFF);
        header[12] = (byte) (rps & 0xFF);

        // Logical record length
        header[13] = (byte) file.getRecordLength();

        // EOF offset (big endian)
        int eof = fdr.getEofOffset();
        header[14] = (byte) ((eof >> 8) & 0xFF);
        header[15] = (byte) (eof & 0xFF);

        // Filename (10 chars, padded)
        byte[] name = file.getFileName()
                .toUpperCase()
                .getBytes(StandardCharsets.US_ASCII);

        for (int i = 0; i < 10; i++) {
            header[16 + i] = (i < name.length) ? name[i] : (byte) ' ';
        }

        // Rest bleibt 0
        return header;
    }
    
    // ---------------------------------------------------------
    // Typ-Dekodierung gemäß TIFILES-Flags
    // ---------------------------------------------------------
    private static String decodeFileType(int flags) {
        boolean isVar = (flags & 0x80) != 0;
        boolean isInt = (flags & 0x02) != 0;
        boolean isProgram = (flags & 0x01) != 0;

        if (isProgram) return "PROGRAM";
        if (isInt) return isVar ? "INT/VAR" : "INT/FIX";
        return isVar ? "DIS/VAR" : "DIS/FIX";
    }
}
