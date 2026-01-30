package com.miriki.ti99.fiad.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// import com.miriki.ti99.dskimg.api.Ti99ImageService;
// import com.miriki.ti99.dskimg.api.impl.Ti99ImageServiceImpl;
import com.miriki.ti99.dskimg.Ti99DiskImage;
import com.miriki.ti99.dskimg.impl.Ti99DiskImageImpl;

import com.miriki.ti99.dskimg.domain.DiskFormat;
import com.miriki.ti99.dskimg.domain.DiskFormatPreset;
import com.miriki.ti99.dskimg.domain.FileDescriptorRecord;
import com.miriki.ti99.dskimg.domain.Ti99File;
import com.miriki.ti99.dskimg.domain.Ti99FileSystem;
import com.miriki.ti99.dskimg.domain.Ti99Image;
import com.miriki.ti99.dskimg.domain.enums.FileType;
import com.miriki.ti99.dskimg.domain.enums.RecordFormat;
import com.miriki.ti99.dskimg.domain.io.ImageFormatter;

import com.miriki.ti99.fiad.io.TiFilesHeader;

public class FiadService {

    private static final Logger log = LoggerFactory.getLogger(FiadService.class);

    public boolean isFiad(Path absPath) {
        return absPath != null && Files.isDirectory(absPath);
    }

    public void prepareFiad(Path absPath, List<Path> tempDsks) throws IOException {
        if (isFiad(absPath)) {
            Path dsk = createDskFromFiad(absPath);
            tempDsks.add(dsk);
        }
    }

    public Path createDskFromFiad(Path fiadDir) throws IOException {
        Path dskPath = fiadDir.resolveSibling(fiadDir.getFileName().toString() + ".dsk");

        DiskFormatPreset preset = DiskFormatPreset.TI_DSDD;
        DiskFormat format = preset.getFormat();

        Ti99Image image = new Ti99Image(format);
        ImageFormatter.initialize(image);

        try (Stream<Path> stream = Files.list(fiadDir)) {
            stream.filter(Files::isRegularFile).forEach(file -> {
                try {
                    importTiFile(image, file);
                } catch (Exception ex) {
                    log.warn("Konnte Datei nicht importieren: {}", file, ex);
                }
            });
        }

        Files.write(dskPath, image.getRawData());
        log.trace("FIAD → DSK erzeugt: {}", dskPath);
        return dskPath;
    }

    public void importTiFile(Ti99Image image, Path hostFile) throws Exception {
        byte[] raw = Files.readAllBytes(hostFile);

        if (raw.length >= 128 && TiFilesHeader.isTiFilesHeader(raw)) {
            TiFilesHeader header = TiFilesHeader.parse(raw);
            byte[] content = Arrays.copyOfRange(raw, 128, raw.length);

            Ti99File ti = new Ti99File();
            ti.setFileName(header.getFileName());
            // ti.setType(Ti99FileIO.parseFileType(header.getFileType()));
            if (header.getRecordLength() == 0) {
                ti.setType(FileType.PGM);
                ti.setFormat(RecordFormat.VAR);
            } else {
                ti.setType(header.isInternal() ? FileType.INT : FileType.DIS);
                ti.setFormat(header.isVariable() ? RecordFormat.VAR : RecordFormat.FIX);
            }
            ti.setRecordLength(header.getRecordLength());
            ti.setFlags(header.getFlags());
            ti.setContent(content);

            log.info("TIFILES erkannt: {} → {} (Type={}, RecLen={}, Flags={})",
                    hostFile.getFileName(),
                    ti.getFileName(),
                    ti.getType(),
                    ti.getRecordLength(),
                    ti.getFlags());

            // FileImporter.importFile(image, ti);
            // Ti99ImageService svc = new Ti99ImageServiceImpl(image);
            // svc.importFile(ti);
            Ti99DiskImage disk = new Ti99DiskImageImpl(image);
            disk.writeFile(ti.getFileName(), ti.getContent(), ti.getType(), ti.getFormat(), ti.getRecordLength());   // oder writeFile(byte[]) je nach API
        } else {
            log.info("Kein TIFILES-Header: {} → Import als PROGRAM", hostFile.getFileName());
            importAsProgram(image, hostFile);
        }
    }

    public void importAsProgram(Ti99Image image, Path hostFile) throws Exception {
        byte[] content = Files.readAllBytes(hostFile);

        Ti99File tiFile = new Ti99File();
        String baseName = hostFile.getFileName().toString().replaceAll("\\..*$", "").toUpperCase();
        if (baseName.length() > 10) baseName = baseName.substring(0, 10);

        tiFile.setFileName(baseName);
        tiFile.setType(FileType.PGM);
        tiFile.setFormat(RecordFormat.VAR);
        tiFile.setRecordLength(0);
        tiFile.setContent(content);

        // FileImporter.importFile(image, tiFile);
        // Ti99ImageService svc = new Ti99ImageServiceImpl(image);
        // svc.importFile(tiFile);
        Ti99DiskImage disk = new Ti99DiskImageImpl(image);
        disk.writeFile(tiFile.getFileName(), tiFile.getContent(), tiFile.getType(), tiFile.getFormat(), tiFile.getRecordLength());   // oder writeFile(byte[]) je nach API
    }

    public byte[] exportTiFile(Ti99DiskImage disk, String fileName) throws IOException {

        // 1) Content lesen (ohne Header)
        byte[] content = disk.readFile(fileName);

        // 2) Filesystem laden
        Ti99Image image = ((Ti99DiskImageImpl) disk).getDomainImage();
        Ti99FileSystem  fs = Ti99DiskImageImpl.loadFileSystem(image);

        // 3) FDR suchen
        FileDescriptorRecord  fdr = fs.getFiles().stream()
                .filter(f -> fileName.equalsIgnoreCase(f.getFileName()))
                .findFirst()
                .orElseThrow(() -> new IOException("File not found: " + fileName));

        // 4) Ti99File erzeugen
        Ti99File file = com.miriki.ti99.dskimg.domain.io.Ti99FileIO.readFile(fs, fdr);

        // 5) Header erzeugen
        byte[] header = TiFilesHeader.build(file, fdr);

        // 6) Header + Content zusammenführen
        byte[] out = new byte[header.length + content.length];
        System.arraycopy(header, 0, out, 0, header.length);
        System.arraycopy(content, 0, out, header.length, content.length);

        return out;
    }
}
