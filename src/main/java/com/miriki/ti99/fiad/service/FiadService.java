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
// import com.miriki.ti99.dskimg.Ti99DiskImage;
import com.miriki.ti99.dskimg.impl.Ti99DiskImageImpl;
import com.miriki.ti99.dskimg.Ti99DiskImage;
import com.miriki.ti99.dskimg.domain.DiskFormatPreset;
import com.miriki.ti99.dskimg.domain.FileDescriptorRecord;
import com.miriki.ti99.dskimg.domain.Ti99File;
import com.miriki.ti99.dskimg.domain.Ti99FileSystem;
import com.miriki.ti99.dskimg.domain.Ti99Image;
// import com.miriki.ti99.dskimg.domain.enums.FileType;
import com.miriki.ti99.dskimg.domain.enums.RecordFormat;
import com.miriki.ti99.dskimg.domain.io.FileNameIO;
import com.miriki.ti99.dskimg.domain.io.Ti99FileIO;
import com.miriki.ti99.dskimg.fs.FileWriter;
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

        // 1. Name des FIAD-Verzeichnisses extrahieren
        String baseName = fiadDir.getFileName().toString();

        // 2. DSK-Dateiname daraus ableiten
        Path dskPath = fiadDir.resolveSibling(baseName + ".dsk");

        // 3. Image erzeugen
        DiskFormatPreset preset = DiskFormatPreset.TI_DSDD;
        String volumeName = FileNameIO.toTiVolumeName(fiadDir.getFileName().toString());
        Ti99Image image = Ti99Image.createEmpty(preset, volumeName);

        // 4. Volume-Name setzen
        // image.getVolume().setName(baseName);

        // 5. Dateien importieren
        try (Stream<Path> stream = Files.list(fiadDir)) {
            stream.filter(Files::isRegularFile).forEach(file -> {
                try {
                    importTiFile(image, file);
                } catch (Exception ex) {
                    log.warn("Konnte Datei nicht importieren: {}", file, ex);
                }
            });
        }

        // 6. Image schreiben
        Files.write(dskPath, image.getRawData());
        log.trace("FIAD → DSK erzeugt: {}", dskPath);

        return dskPath;
    }

    public void importTiFile(Ti99Image image, Path hostFile) throws Exception {
        byte[] raw = Files.readAllBytes(hostFile);

        Ti99FileSystem fs = Ti99DiskImageImpl.loadFileSystem(image);

        if (raw.length >= 128 && TiFilesHeader.isTiFilesHeader(raw)) {
            TiFilesHeader header = TiFilesHeader.parse(raw);
            byte[] content = Arrays.copyOfRange(raw, 128, raw.length);

            Ti99File ti = new Ti99File();
            ti.setFileName(header.getFileName());
            ti.setContent(content);
            ti.setRecordLength(header.getRecordLength());
            ti.setFlags(header.getFlags());
            ti.setFormat(header.isVariable() ? RecordFormat.VAR : RecordFormat.FIX);
            ti.setFileTypeLabel(header.getFileType());

            /*
            FileDescriptorRecord fdr = new FileDescriptorRecord();
            Ti99FileIO.writeFile(fs, fdr, ti);
            */
            ti.pack();
            FileWriter.createFile(fs, ti);
            Ti99DiskImageImpl.saveFileSystem(image, fs);

        } else {
            importAsProgram(image, hostFile);
        }
    }

    public void importAsProgram(Ti99Image image, Path hostFile) throws Exception {
        byte[] content = Files.readAllBytes(hostFile);

        Ti99FileSystem fs = Ti99DiskImageImpl.loadFileSystem(image);

        Ti99File ti = new Ti99File();
        String rawName = hostFile.getFileName().toString(); // .replaceAll("\\..*$", "");
        // if (baseName.length() > 10) baseName = baseName.substring(0, 10);
        String baseName = FileNameIO.toTiFileName(rawName);

        ti.setFileName(baseName);
        ti.setContent(content);
        ti.setRecordLength(0);
        ti.setFlags(0);
        ti.setFormat(RecordFormat.FIX);
        ti.setFileTypeLabel("PROGRAM");

        /*
        FileDescriptorRecord fdr = new FileDescriptorRecord();
        Ti99FileIO.writeFile(fs, fdr, ti);
        */
        FileWriter.createFile(fs, ti);
        Ti99DiskImageImpl.saveFileSystem(image, fs);
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
        Ti99File file = Ti99FileIO.readFile(fs, fdr);

        // 5) Header erzeugen
        byte[] header = TiFilesHeader.build(file, fdr);

        // 6) Header + Content zusammenführen
        byte[] out = new byte[header.length + content.length];
        System.arraycopy(header, 0, out, 0, header.length);
        System.arraycopy(content, 0, out, header.length, content.length);

        return out;
    }
}
