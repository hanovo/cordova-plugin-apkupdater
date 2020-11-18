package de.kolbasa.apkupdater;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Objects;

import org.junit.jupiter.api.*;

import de.kolbasa.apkupdater.downloader.exceptions.AlreadyRunningException;
import de.kolbasa.apkupdater.downloader.progress.DownloadProgress;
import de.kolbasa.apkupdater.downloader.progress.UnzipProgress;
import de.kolbasa.apkupdater.downloader.exceptions.WrongChecksumException;
import de.kolbasa.apkupdater.downloader.manifest.Manifest;
import de.kolbasa.apkupdater.downloader.update.UpdateDownloadEvent;
import de.kolbasa.apkupdater.downloader.update.tools.ChecksumGenerator;

import static org.junit.jupiter.api.Assertions.*;

class ApkUpdaterTests {

    private static final String REMOTE = "https://github.com/kolbasa/cordova-plugin-apkupdater/raw/master/tests/updates";
    private static final String LOCAL = "../../../plugins/cordova-plugin-apkupdater/tests/updates";

    private static final String VERSION = "1.0.0";
    private static final String MANIFEST = "manifest.json";
    private static final String TEMP_DIR = "apk-updater";

    private static final String UPDATE = "/update-compressed/" + VERSION;
    private static final String UPDATE_WITH_CORRUPTION = UPDATE + "-corrupted-chunk";
    private static final String UPDATE_WITH_MISSING_CHUNK = UPDATE + "-missing-chunk";

    private static final String REMOTE_UPDATE = REMOTE + UPDATE;
    private static final String REMOTE_UPDATE_WITH_CORRUPTION = REMOTE + UPDATE_WITH_CORRUPTION;
    private static final String REMOTE_UPDATE_WITH_MISSING_CHUNK = REMOTE + UPDATE_WITH_MISSING_CHUNK;

    private static final String LOCAL_UPDATE = LOCAL + UPDATE;
    private static final String LOCAL_UPDATE_WITH_CORRUPTION = LOCAL + UPDATE_WITH_CORRUPTION;

    private static final String APK = LOCAL + "/update/1.0.0/example.apk";
    private static final String APK_WITH_CORRUPTION = LOCAL + "/update/1.0.0-corrupted-apk/example.apk";

    private static final String APK_NAME = "example.apk";
    private static final String APK_MD5_HASH = "35d9fd2d688156e45b89707f650a61ac";

    private static final String PART_01 = "update.zip.001";
    private static final String PART_02 = "update.zip.002";
    private static final String PART_03 = "update.zip.003";

    private static final String PART_01_MD5_HASH = "996f77b52e6f5ab5f0c8c93a20fb4904";
    private static final String PART_02_MD5_HASH = "785a751e2d4ea60716023bde476a814b";
    private static final String PART_03_MD5_HASH = "53629f6fb0e6788f0d3113bb54b5cfd4";

    private static final String TIMEOUT_IP = "http://example.com:81/";

    private static final int DOWNLOAD_INTERVAL_IN_MS = 1000;
    private static final int MAX_DOWNLOAD_TIME = DOWNLOAD_INTERVAL_IN_MS - 100;

    private static class Events {
        private final ArrayList<UpdateDownloadEvent> events = new ArrayList<>();
        private final ArrayList<Exception> exceptions = new ArrayList<>();
        private final ArrayList<UnzipProgress> unzipProgress = new ArrayList<>();
        private final ArrayList<DownloadProgress> downloadProgress = new ArrayList<>();

        ArrayList<DownloadProgress> getDownloadProgress() {
            return downloadProgress;
        }

        ArrayList<UnzipProgress> getUnzipProgress() {
            return unzipProgress;
        }

        ArrayList<Exception> getExceptions() {
            return exceptions;
        }

        ArrayList<UpdateDownloadEvent> getEvents() {
            return events;
        }
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    private static void copyFile(File source, File destination) throws IOException {
        if (source.isDirectory()) {
            if (!destination.exists()) {
                destination.mkdir();
            }
            for (String file : Objects.requireNonNull(source.list())) {
                copyFile(new File(source, file), new File(destination, file));
            }
        } else {
            Files.copy(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    private static void deleteDirectory(File directory) {
        if (directory.exists()) {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        deleteDirectory(file);
                    } else {
                        file.delete();
                    }
                }
            }
        }
        directory.delete();
    }

    private static String createDownloadDirectory() {
        String path = null;
        try {
            path = Files.createTempDirectory(TEMP_DIR + ".").toString();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return path;
    }

    private void copyUpdateToDevice(String apkPath) throws IOException {
        // noinspection ResultOfMethodCallIgnored
        new File(downloadDirectory, APK_MD5_HASH).mkdir();
        copyFile(new File(apkPath), new File(updateDirectory, APK_NAME));
    }

    private File copyUpdateChunksToDevice(String hash) throws IOException {
        File dir = new File(downloadDirectory, hash);
        // noinspection ResultOfMethodCallIgnored
        dir.mkdir();
        copyFile(new File(LOCAL_UPDATE), dir);
        copyFile(new File(LOCAL_UPDATE, MANIFEST), new File(downloadDirectory, MANIFEST));
        return dir;
    }

    private Events observe(UpdateManager updater) {
        Events events = new Events();

        updater.addObserver((o, arg) -> {
            if (arg instanceof DownloadProgress) {
                events.getDownloadProgress().add(new DownloadProgress((DownloadProgress) arg));
            }

            if (arg instanceof UnzipProgress) {
                events.getUnzipProgress().add(new UnzipProgress((UnzipProgress) arg));
            }

            if (arg instanceof Exception) {
                events.getExceptions().add((Exception) arg);
            }

            if (arg instanceof UpdateDownloadEvent) {
                events.getEvents().add((UpdateDownloadEvent) arg);
            }
        });

        return events;
    }

    private int waitForFile(String part) throws InterruptedException {
        for (int i = 0; i < MAX_DOWNLOAD_TIME / 5; i++) {
            Thread.sleep(5);
            File chunk = new File(updateDirectory, part);
            File lock = new File(updateDirectory, part + ".lock");

            if (chunk.exists() && chunk.length() > 0 && !lock.exists()) {
                Thread.sleep(25);
                return i * 5 + 25;
            }
        }
        return MAX_DOWNLOAD_TIME;
    }

    private void waitForException(ArrayList<Exception> list) throws InterruptedException {
        int initialCount = list.size();
        int waitTime = 1500;

        for (int i = 0; i < waitTime / 5; i++) {
            Thread.sleep(5);
            if (list.size() > initialCount) {
                return;
            }
        }
    }

    private DownloadProgress findDownloadProgressEvent(ArrayList<DownloadProgress> events, int part) {
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i).getChunksDownloaded() == part) {
                return events.get(i);
            }
        }
        return null;
    }

    private String downloadDirectory;
    private String updateDirectory;

    @BeforeEach
    void setUp() {
        this.downloadDirectory = createDownloadDirectory();
        this.updateDirectory = this.downloadDirectory + File.separator + APK_MD5_HASH;
    }

    @AfterEach
    void tearDown() {
        deleteDirectory(new File(this.downloadDirectory));
    }

    @Nested
    @DisplayName("Reset")
    class Reset {

        @Test
        @DisplayName("Delete manifest file")
        void deleteManifest() throws Exception {
            UpdateManager updater = new UpdateManager(REMOTE_UPDATE, downloadDirectory);
            copyUpdateChunksToDevice(APK_MD5_HASH);

            assertTrue(new File(downloadDirectory, MANIFEST).exists());

            updater.removeUpdates();

            assertFalse(new File(downloadDirectory, MANIFEST).exists());
        }

        @Test
        @DisplayName("Delete update files")
        void deleteUpdateFiles() throws Exception {
            UpdateManager updater = new UpdateManager(REMOTE_UPDATE, downloadDirectory);
            copyUpdateChunksToDevice(APK_MD5_HASH);

            assertTrue(new File(updateDirectory, PART_01).exists());
            assertTrue(new File(updateDirectory, PART_02).exists());
            assertTrue(new File(updateDirectory, PART_03).exists());

            updater.removeUpdates();

            assertFalse(new File(updateDirectory, PART_01).exists());
            assertFalse(new File(updateDirectory, PART_02).exists());
            assertFalse(new File(updateDirectory, PART_03).exists());
        }

        @Test
        @DisplayName("Delete extracted update")
        void deleteExtractedUpdate() throws Exception {
            UpdateManager updater = new UpdateManager(REMOTE_UPDATE, downloadDirectory);
            copyUpdateToDevice(APK);

            assertTrue(new File(updateDirectory, APK_NAME).exists());

            updater.removeUpdates();

            assertFalse(new File(updateDirectory, APK_NAME).exists());
        }

    }

    @Nested
    @DisplayName("Background download")
    class BackgroundDownload {

        @Test
        @DisplayName("Download all chunks")
        void delayedDownload() throws Exception {
            UpdateManager updater = new UpdateManager(REMOTE_UPDATE, downloadDirectory);
            updater.check();

            updater.downloadInBackground(DOWNLOAD_INTERVAL_IN_MS);

            assertFalse(new File(updateDirectory, PART_01).exists());
            assertFalse(new File(updateDirectory, PART_02).exists());
            assertFalse(new File(updateDirectory, PART_03).exists());

            int shift = 0;

            Thread.sleep(DOWNLOAD_INTERVAL_IN_MS - shift);
            shift = waitForFile(PART_01);

            assertTrue(new File(updateDirectory, PART_01).exists());
            assertFalse(new File(updateDirectory, PART_02).exists());
            assertFalse(new File(updateDirectory, PART_03).exists());

            Thread.sleep(DOWNLOAD_INTERVAL_IN_MS - shift);
            shift = waitForFile(PART_02);

            assertTrue(new File(updateDirectory, PART_01).exists());
            assertTrue(new File(updateDirectory, PART_02).exists());
            assertFalse(new File(updateDirectory, PART_03).exists());

            Thread.sleep(DOWNLOAD_INTERVAL_IN_MS - shift);
            waitForFile(PART_03);

            assertTrue(new File(updateDirectory, PART_01).exists());
            assertTrue(new File(updateDirectory, PART_02).exists());
            assertTrue(new File(updateDirectory, PART_03).exists());
        }

        @Test
        @DisplayName("Extract update")
        void extractUpdate() throws Exception {
            UpdateManager updater = new UpdateManager(REMOTE_UPDATE, downloadDirectory);
            updater.check();

            updater.downloadInBackground(DOWNLOAD_INTERVAL_IN_MS);

            int shift = 0;

            Thread.sleep(DOWNLOAD_INTERVAL_IN_MS - shift);
            shift = waitForFile(PART_01);
            assertFalse(new File(updateDirectory, APK_NAME).exists());

            Thread.sleep(DOWNLOAD_INTERVAL_IN_MS - shift);
            shift = waitForFile(PART_02);
            assertFalse(new File(updateDirectory, APK_NAME).exists());

            Thread.sleep(DOWNLOAD_INTERVAL_IN_MS - shift);
            waitForFile(PART_03);

            waitForFile(APK_NAME);
            assertTrue(new File(updateDirectory, APK_NAME).exists());

        }

        @Test
        @DisplayName("Remove corrupted chunk")
        void removeCorruptedChunk() throws Exception {

            UpdateManager updater = new UpdateManager(REMOTE_UPDATE, downloadDirectory);

            updater.check();
            updater.downloadInBackground(DOWNLOAD_INTERVAL_IN_MS);

            int shift = 0;

            Thread.sleep(DOWNLOAD_INTERVAL_IN_MS - shift);
            assertTrue(updater.isDownloading());
            shift = waitForFile(PART_01);
            assertTrue(new File(updateDirectory, PART_01).exists());
            assertFalse(new File(updateDirectory, PART_02).exists());

            // Corrupting first chunk
            copyFile(new File(LOCAL_UPDATE_WITH_CORRUPTION, PART_01), new File(updateDirectory, PART_01));

            // Replacing corrupted chunk
            Thread.sleep(DOWNLOAD_INTERVAL_IN_MS - shift);
            // TODO: better solve with checksum
            shift = 100;
            Thread.sleep(shift);
            assertTrue(new File(updateDirectory, PART_01).exists());
            assertFalse(new File(updateDirectory, PART_02).exists());

            Thread.sleep(DOWNLOAD_INTERVAL_IN_MS - shift);
            waitForFile(PART_02);
            assertTrue(updater.isDownloading());
            assertTrue(new File(updateDirectory, PART_01).exists());
            assertTrue(new File(updateDirectory, PART_02).exists());
            assertFalse(new File(updateDirectory, PART_03).exists());

            updater.stop();
        }

        @Nested
        class Broadcasting {
            @Test
            @DisplayName("Update ready")
            void broadcastFinishedDownload() throws Exception {
                UpdateManager updater = new UpdateManager(REMOTE_UPDATE, downloadDirectory);
                ArrayList<UpdateDownloadEvent> events = observe(updater).getEvents();

                Manifest manifest = updater.check();
                updater.downloadInBackground(DOWNLOAD_INTERVAL_IN_MS);
                events.clear(); // we don't need the start event

                int shift = 0;

                Thread.sleep(DOWNLOAD_INTERVAL_IN_MS - shift);
                shift = waitForFile(PART_01);
                assertEquals(0, events.size());
                assertNull(manifest.getUpdateFile());

                Thread.sleep(DOWNLOAD_INTERVAL_IN_MS - shift);
                shift = waitForFile(PART_02);
                assertEquals(0, events.size());
                assertNull(manifest.getUpdateFile());

                Thread.sleep(DOWNLOAD_INTERVAL_IN_MS - shift);
                waitForFile(PART_03);
                waitForFile(APK_NAME);
                assertEquals(2, events.size());

                assertEquals(UpdateDownloadEvent.UPDATE_READY, events.get(0));
                assertEquals(UpdateDownloadEvent.STOPPED, events.get(1));

                assertNotNull(manifest.getUpdateFile());
            }

            @Test
            @DisplayName("AbstractProgress")
            void progress() throws Exception {
                UpdateManager updater = new UpdateManager(REMOTE_UPDATE, downloadDirectory);

                ArrayList<DownloadProgress> events = observe(updater).getDownloadProgress();

                updater.check();
                updater.downloadInBackground(DOWNLOAD_INTERVAL_IN_MS);

                int shift = 0;

                Thread.sleep(DOWNLOAD_INTERVAL_IN_MS - shift);
                shift = waitForFile(PART_01);
                assertTrue(events.size() >= 2);

                DownloadProgress progress;

                progress = events.get(0);
                assertEquals(0, progress.getChunksDownloaded());
                assertEquals(0, progress.getPercent());

                progress = findDownloadProgressEvent(events, 1);
                assertNotNull(progress);
                assertEquals(42.74f, progress.getPercent());


                Thread.sleep(DOWNLOAD_INTERVAL_IN_MS - shift);
                shift = waitForFile(PART_02);

                progress = findDownloadProgressEvent(events, 2);
                assertNotNull(progress);
                assertEquals(85.47f, progress.getPercent());

                Thread.sleep(DOWNLOAD_INTERVAL_IN_MS - shift);
                waitForFile(PART_03);

                progress = events.get(events.size() - 1);
                assertEquals(100, progress.getPercent());
                assertEquals(3, progress.getChunksDownloaded());
            }

        }

        @Nested
        class Exceptions {
            @Test
            @DisplayName("UpdateChunk missing")
            void chunkMissing() throws Exception {
                UpdateManager updater = new UpdateManager(REMOTE_UPDATE_WITH_MISSING_CHUNK, downloadDirectory);
                ArrayList<Exception> exceptions = observe(updater).getExceptions();

                updater.check();
                updater.downloadInBackground(DOWNLOAD_INTERVAL_IN_MS);

                int shift = 0;

                Thread.sleep(DOWNLOAD_INTERVAL_IN_MS - shift);
                shift = waitForFile(PART_01);
                assertTrue(updater.isDownloading());
                assertTrue(new File(updateDirectory, PART_01).exists());
                assertEquals(0, exceptions.size());

                Thread.sleep(DOWNLOAD_INTERVAL_IN_MS - shift);
                waitForFile(PART_02);
                assertEquals(1, exceptions.size());
                assertTrue(exceptions.get(0) instanceof FileNotFoundException);
                assertTrue(Objects.requireNonNull(exceptions.get(0).getMessage()).endsWith(PART_02));
            }

            @Test
            @DisplayName("Wrong checksum")
            void wrongChecksum() throws Exception {
                UpdateManager updater = new UpdateManager(REMOTE_UPDATE_WITH_CORRUPTION, downloadDirectory);
                ArrayList<Exception> exceptions = observe(updater).getExceptions();

                updater.check();
                updater.downloadInBackground(DOWNLOAD_INTERVAL_IN_MS);

                int shift = 0;

                Thread.sleep(DOWNLOAD_INTERVAL_IN_MS - shift);
                shift = waitForFile(PART_01);
                assertEquals(0, exceptions.size());

                Thread.sleep(DOWNLOAD_INTERVAL_IN_MS - shift);
                waitForFile(PART_02);
                assertEquals(1, exceptions.size());
                assertTrue(exceptions.get(0) instanceof WrongChecksumException);

                updater.stop();
            }

            @Test
            @DisplayName("Stop when the checksum is wrong three times in a row.")
            void stopOnThirdChecksumFail() throws Exception {
                UpdateManager updater = new UpdateManager(REMOTE_UPDATE_WITH_CORRUPTION, downloadDirectory);
                ArrayList<Exception> exceptions = observe(updater).getExceptions();

                updater.check();
                updater.downloadInBackground(DOWNLOAD_INTERVAL_IN_MS);

                int shift = 0;

                Thread.sleep(DOWNLOAD_INTERVAL_IN_MS - shift);
                shift = waitForFile(PART_01);
                assertTrue(updater.isDownloading());
                assertEquals(0, exceptions.size());

                Thread.sleep(DOWNLOAD_INTERVAL_IN_MS - shift);
                waitForFile(PART_02);
                assertEquals(1, exceptions.size());
                assertTrue(exceptions.get(0) instanceof WrongChecksumException);
                assertTrue(updater.isDownloading());

                exceptions.clear();
                waitForException(exceptions);
                assertEquals(1, exceptions.size());
                assertTrue(exceptions.get(0) instanceof WrongChecksumException);
                assertTrue(updater.isDownloading());

                exceptions.clear();
                waitForException(exceptions);
                assertEquals(1, exceptions.size());
                assertTrue(exceptions.get(0) instanceof WrongChecksumException);
                assertFalse(updater.isDownloading()); // should stop

                updater.stop();
            }

            @Test
            @DisplayName("Ignore second 'backgroundDownload'-call")
            void ignoreSecondDownloadRequest() throws Exception {
                UpdateManager updater = new UpdateManager(REMOTE_UPDATE, downloadDirectory);
                updater.check();

                updater.downloadInBackground(DOWNLOAD_INTERVAL_IN_MS);

                int shift = 0;

                Thread.sleep(DOWNLOAD_INTERVAL_IN_MS - shift);
                shift = waitForFile(PART_01);

                assertTrue(new File(updateDirectory, PART_01).exists());
                assertFalse(new File(updateDirectory, PART_02).exists());

                assertThrows(AlreadyRunningException.class, () ->
                        updater.downloadInBackground(DOWNLOAD_INTERVAL_IN_MS));

                Thread.sleep(DOWNLOAD_INTERVAL_IN_MS - shift);
                waitForFile(PART_02);

                assertTrue(new File(updateDirectory, PART_01).exists());
                assertTrue(new File(updateDirectory, PART_02).exists());
                assertFalse(new File(updateDirectory, PART_03).exists());

                updater.stop();
            }
        }

    }

    @Nested
    @DisplayName("Download")
    class Download {

        @Test
        @DisplayName("Download all chunks")
        void downloadAllChunks() throws Exception {
            UpdateManager updater = new UpdateManager(REMOTE_UPDATE, downloadDirectory);
            updater.check();
            updater.download();
            assertTrue(new File(updateDirectory, PART_01).exists());
            assertTrue(new File(updateDirectory, PART_02).exists());
            assertTrue(new File(updateDirectory, PART_03).exists());
        }

        @Test
        @DisplayName("Extract update")
        void extractUpdate() throws Exception {
            UpdateManager updater = new UpdateManager(REMOTE_UPDATE, downloadDirectory);
            updater.check();
            updater.download();
            assertTrue(new File(updateDirectory, APK_NAME).exists());
        }

        @Test
        @DisplayName("Extract only if download complete")
        void extractButNotDownload() throws Exception {
            copyUpdateChunksToDevice(APK_MD5_HASH);
            UpdateManager updater = new UpdateManager(REMOTE_UPDATE, downloadDirectory);

            Events events = observe(updater);
            ArrayList<UnzipProgress> unzipProgress = events.getUnzipProgress();
            ArrayList<DownloadProgress> downloadProgress = events.getDownloadProgress();

            updater.check();
            updater.download();

            assertEquals(0, downloadProgress.size());
            assertTrue(unzipProgress.size() > 0);
            assertTrue(new File(updateDirectory, APK_NAME).exists());
        }

        @Nested
        class Removing {
            @Test
            @DisplayName("Manually removing")
            void clearingUpdates() throws Exception {
                copyUpdateChunksToDevice(APK_MD5_HASH);

                assertTrue(new File(downloadDirectory, MANIFEST).exists());
                assertTrue(new File(downloadDirectory, APK_MD5_HASH).exists());

                UpdateManager updater = new UpdateManager(REMOTE_UPDATE, downloadDirectory);
                updater.removeUpdates();

                assertFalse(new File(downloadDirectory, MANIFEST).exists());
                assertFalse(new File(downloadDirectory, APK_MD5_HASH).exists());
            }

            @Test
            @DisplayName("Old update files")
            void removeOldUpdate() throws Exception {
                // old update
                File dir = copyUpdateChunksToDevice("0.9.0");
                assertTrue(dir.exists());

                UpdateManager updater = new UpdateManager(REMOTE_UPDATE, downloadDirectory);

                assertFalse(new File(updateDirectory).exists());

                updater.check();
                updater.download();

                assertFalse(dir.exists());
                assertTrue(new File(updateDirectory).exists());
            }
        }

        @Nested
        class Paths {
            @Test
            @DisplayName("Create new update directory")
            void createNewUpdateDirectory() throws Exception {
                assertFalse(new File(updateDirectory).exists());
                UpdateManager updater = new UpdateManager(REMOTE_UPDATE, downloadDirectory);
                updater.check();
                updater.download();
                assertTrue(new File(updateDirectory).exists());
            }

            @Test
            @DisplayName("Manifest should return correct update path")
            void updateFile() throws Exception {
                UpdateManager updater = new UpdateManager(REMOTE_UPDATE, downloadDirectory);

                Manifest manifest = updater.check();
                assertNull(manifest.getUpdateFile());
                updater.download();
                assertEquals(manifest.getUpdateFile(), new File(updateDirectory, APK_NAME));
            }
        }

        @Nested
        class Skipping {
            @Test
            @DisplayName("Extraction if already extracted")
            void apkAlreadyExtracted() throws Exception {
                UpdateManager updater = new UpdateManager(REMOTE_UPDATE, downloadDirectory);

                assertNull(updater.check().getUpdateFile());

                ArrayList<UnzipProgress> events = observe(updater).getUnzipProgress();

                // noinspection ResultOfMethodCallIgnored
                new File(downloadDirectory, APK_MD5_HASH).mkdir();
                copyFile(new File(APK), new File(updateDirectory, APK_NAME));

                updater.download();

                assertEquals(0, events.size());
            }

            @Test
            @DisplayName("Already downloaded chunks")
            void shouldSkipAlreadyDownloaded() throws Exception {
                // noinspection ResultOfMethodCallIgnored
                new File(updateDirectory).mkdir();
                File part = new File(updateDirectory, PART_01);
                copyFile(new File(LOCAL + UPDATE, PART_01), part);

                UpdateManager updater = new UpdateManager(REMOTE_UPDATE, downloadDirectory);
                updater.check();

                ArrayList<DownloadProgress> events = observe(updater).getDownloadProgress();

                part = new File(updateDirectory, PART_01);
                assertEquals(ChecksumGenerator.getFileChecksum(part), PART_01_MD5_HASH);

                part = new File(updateDirectory, PART_02);
                assertFalse(part.exists());

                part = new File(updateDirectory, PART_03);
                assertFalse(part.exists());

                updater.download();

                part = new File(updateDirectory, PART_01);
                assertEquals(ChecksumGenerator.getFileChecksum(part), PART_01_MD5_HASH);

                part = new File(updateDirectory, PART_02);
                assertEquals(ChecksumGenerator.getFileChecksum(part), PART_02_MD5_HASH);

                part = new File(updateDirectory, PART_03);
                assertEquals(ChecksumGenerator.getFileChecksum(part), PART_03_MD5_HASH);

                DownloadProgress progress;
                // 2 downloaded chunk events + 1 start event
                // plus x intermediate progress events
                assertTrue(events.size() >= 3);

                progress = events.get(0);
                assertEquals(1, progress.getChunksDownloaded());
                assertEquals(42.74f, progress.getPercent());

                progress = findDownloadProgressEvent(events, 2);
                assertNotNull(progress);
                assertEquals(85.47f, progress.getPercent());

                progress = events.get(events.size() - 1);
                assertEquals(100, progress.getPercent());
                assertEquals(3, progress.getChunksDownloaded());
            }
        }

        @Nested
        class Corruption {

            @Test
            @DisplayName("Replace apk")
            void replaceApk() throws Exception {
                UpdateManager updater = new UpdateManager(REMOTE_UPDATE, downloadDirectory);
                updater.check();

                ArrayList<UnzipProgress> events = observe(updater).getUnzipProgress();

                // noinspection ResultOfMethodCallIgnored
                new File(downloadDirectory, APK_MD5_HASH).mkdir();
                copyFile(new File(APK_WITH_CORRUPTION), new File(updateDirectory, APK_NAME));

                updater.download();

                assertTrue(events.size() > 0);
            }

            @Test
            @DisplayName("Replace corrupted chunk")
            void replaceChunk() throws Exception {
                UpdateManager updater = new UpdateManager(REMOTE_UPDATE, downloadDirectory);
                updater.check();

                File corruptedPart = new File(updateDirectory, PART_01);

                // noinspection ResultOfMethodCallIgnored
                new File(updateDirectory).mkdir();
                copyFile(new File(LOCAL_UPDATE, PART_03), corruptedPart);

                assertTrue(corruptedPart.exists());
                assertNotEquals(ChecksumGenerator.getFileChecksum(corruptedPart), PART_01_MD5_HASH);

                updater.download();

                assertEquals(ChecksumGenerator.getFileChecksum(corruptedPart), PART_01_MD5_HASH);
                assertEquals(ChecksumGenerator.getFileChecksum(new File(LOCAL_UPDATE, PART_02)), PART_02_MD5_HASH);
                assertEquals(ChecksumGenerator.getFileChecksum(new File(LOCAL_UPDATE, PART_03)), PART_03_MD5_HASH);
            }

        }

        @Nested
        class Broadcasting {

            @Test
            @DisplayName("Extraction")
            void extraction() throws Exception {
                UpdateManager updater = new UpdateManager(REMOTE_UPDATE, downloadDirectory);
                updater.check();

                ArrayList<UnzipProgress> events = observe(updater).getUnzipProgress();
                updater.download();

                UnzipProgress progress;
                // 1 start event + 1 end event + 1 progress event
                // plus x intermediate progress events
                assertTrue(events.size() >= 3);

                progress = events.get(0);
                assertEquals(0, progress.getPercent());

                progress = events.get(1);
                assertTrue(progress.getPercent() > 0);
                assertTrue(progress.getPercent() < 100);

                progress = events.get(events.size() - 1);
                assertEquals(100, progress.getPercent());
            }

            @Test
            @DisplayName("Downloading")
            void downloading() throws Exception {
                UpdateManager updater = new UpdateManager(REMOTE_UPDATE, downloadDirectory);
                updater.check();

                ArrayList<DownloadProgress> events = observe(updater).getDownloadProgress();
                updater.download();

                DownloadProgress progress;
                // 3 downloaded chunk events + 1 start event
                // plus x intermediate progress events
                assertTrue(events.size() >= 4);

                progress = events.get(0);
                assertEquals(0, progress.getChunksDownloaded());
                assertEquals(0, progress.getPercent());

                progress = findDownloadProgressEvent(events, 1);
                assertNotNull(progress);
                assertEquals(42.74f, progress.getPercent());

                progress = findDownloadProgressEvent(events, 2);
                assertNotNull(progress);
                assertEquals(85.47f, progress.getPercent());

                progress = events.get(events.size() - 1);
                assertEquals(100, progress.getPercent());
                assertEquals(3, progress.getChunksDownloaded());
            }

            @Test
            @DisplayName("Update ready")
            void updateReady() throws Exception {
                UpdateManager updater = new UpdateManager(REMOTE_UPDATE, downloadDirectory);
                updater.check();

                ArrayList<UpdateDownloadEvent> events = observe(updater).getEvents();
                updater.download();
                assertTrue(events.contains(UpdateDownloadEvent.UPDATE_READY));
            }

            @Test
            @DisplayName("Finished download")
            void finishedDownload() throws Exception {
                UpdateManager updater = new UpdateManager(REMOTE_UPDATE, downloadDirectory);
                updater.check();

                ArrayList<UpdateDownloadEvent> events = observe(updater).getEvents();
                updater.download();
                assertTrue(events.contains(UpdateDownloadEvent.STOPPED));
            }

            @Test
            @DisplayName("Individual downloaded chunks")
            void individualDownloadedChunks() throws Exception {
                UpdateManager updater = new UpdateManager(REMOTE_UPDATE, downloadDirectory);
                updater.check();

                ArrayList<DownloadProgress> events = observe(updater).getDownloadProgress();

                updater.download();

                assertEquals(4, events.size());
                assertEquals(3, events.get(0).getChunks());

                assertEquals(0, events.get(0).getChunksDownloaded());
                assertEquals(1, events.get(1).getChunksDownloaded());
                assertEquals(2, events.get(2).getChunksDownloaded());
                assertEquals(3, events.get(3).getChunksDownloaded());
            }

        }

        @Nested
        class Exceptions {
            @Test
            @DisplayName("UpdateChunk has wrong checksum")
            void wrongChecksum() throws Exception {
                UpdateManager updater = new UpdateManager(REMOTE_UPDATE_WITH_CORRUPTION, downloadDirectory);
                updater.check();
                assertThrows(WrongChecksumException.class, updater::download);

                assertTrue(new File(updateDirectory, PART_01).exists());
                assertFalse(new File(updateDirectory, PART_02).exists());
                assertFalse(new File(updateDirectory, PART_03).exists());
            }

            @Test
            @DisplayName("UpdateChunk is missing on server")
            void partMissingOnServer() throws Exception {
                UpdateManager updater = new UpdateManager(REMOTE_UPDATE_WITH_MISSING_CHUNK, downloadDirectory);
                updater.check();

                assertThrows(FileNotFoundException.class, updater::download);

                assertTrue(new File(updateDirectory, PART_01).exists());
                assertFalse(new File(updateDirectory, PART_02).exists());
                assertFalse(new File(updateDirectory, PART_03).exists());
            }
        }

    }

    @Nested
    @DisplayName("Looking for new update")
    class ManifestLogic {

        @Test
        @DisplayName("Writing manifest file to file system - url to parent directory")
        void writing() throws Exception {
            new UpdateManager(REMOTE_UPDATE, downloadDirectory).check();
            assertTrue(new File(downloadDirectory, MANIFEST).exists());
        }

        @Test
        @DisplayName("Writing manifest file to file system - url to manifest file")
        void writingWithDirectLink() throws Exception {
            new UpdateManager(REMOTE_UPDATE + "/" + MANIFEST, downloadDirectory).check();
            assertTrue(new File(downloadDirectory, MANIFEST).exists());
        }

        @Test
        @DisplayName("Reading manifest file")
        void reading() throws Exception {
            Manifest manifest = new UpdateManager(REMOTE_UPDATE, downloadDirectory).check();
            assertNotNull(manifest);
        }

        @Test
        @DisplayName("Reading version in manifest")
        void readingVersion() throws Exception {
            Manifest manifest = new UpdateManager(REMOTE_UPDATE, downloadDirectory).check();
            assertEquals(VERSION, manifest.getVersion());
        }

        @Test
        @DisplayName("Mark update as ready if already downloaded")
        void markIfAlreadyDownloaded() throws Exception {
            copyUpdateToDevice(APK);
            Manifest manifest = new UpdateManager(REMOTE_UPDATE, downloadDirectory).check();
            assertNotNull(manifest.getUpdateFile());
        }

        @Test
        @DisplayName("Do not mark as ready if update corrupted")
        void doNotMarkAsReadyIfApkCorrupted() throws Exception {
            copyUpdateToDevice(APK_WITH_CORRUPTION);
            Manifest manifest = new UpdateManager(REMOTE_UPDATE, downloadDirectory).check();
            assertNull(manifest.getUpdateFile());
        }

        @Test
        @DisplayName("Do not mark as ready if only chunks are downloaded")
        void doNotMarkAsReadyIfOnlyChunksAreDownloaded() throws Exception {
            copyUpdateChunksToDevice(APK_MD5_HASH);
            Manifest manifest = new UpdateManager(REMOTE_UPDATE, downloadDirectory).check();
            assertNull(manifest.getUpdateFile());
        }

        @Nested
        class Exceptions {
            @Test
            @DisplayName("Manifest file is missing on server")
            void missing() {
                UpdateManager updater = new UpdateManager(REMOTE, downloadDirectory);
                assertThrows(java.io.FileNotFoundException.class, updater::check);
            }

            @Test
            @DisplayName("Socket timeout")
            void socketTimeout() {
                UpdateManager updater = new UpdateManager(TIMEOUT_IP, downloadDirectory, 100);
                assertThrows(java.net.SocketTimeoutException.class, updater::check);
            }
        }
    }
}
