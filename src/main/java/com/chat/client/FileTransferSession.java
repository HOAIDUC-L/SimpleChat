package com.chat.client;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Paths;

/**
 * Quản lý phiên nhận file nhị phân của Client:
 * - Bảo vệ chống lỗ hổng Path Traversal.
 * - Tự động đổi tên nếu file bị trùng lặp, không ghi đè dữ liệu người dùng.
 * - Ghi dữ liệu luồng byte tuần tự và tính toán tiến trình phần trăm.
 */
public class FileTransferSession {

    private final String fileId;
    private final String sender;
    private final String originalFileName;
    private final long totalBytes;
    private final File targetFile;
    private final FileOutputStream outputStream;
    private long bytesReceived = 0L;
    private boolean isFinished = false;

    public FileTransferSession(String fileId, String sender, String rawFileName, long totalBytes, File saveDirectory) throws IOException {
        this.fileId = fileId;
        this.sender = sender;
        this.totalBytes = totalBytes;

        // 1. Chống lỗ hổng Path Traversal: Loại bỏ triệt để đường dẫn tương đối (../../) từ Client gửi
        String safeName = sanitizeFileName(rawFileName);
        this.originalFileName = safeName;

        // 2. Chống ghi đè file: Nếu file đã tồn tại, tự động tạo tên mới filename (1).ext
        this.targetFile = resolveNonConflictingFile(saveDirectory, safeName);

        // Mở luồng ghi nhị phân
        this.outputStream = new FileOutputStream(this.targetFile);
    }

    /**
     * Làm sạch tên file từ client để ngăn chặn Path Traversal.
     */
    public static String sanitizeFileName(String rawFileName) {
        if (rawFileName == null || rawFileName.trim().isEmpty()) {
            return "downloaded_file";
        }
        // Lấy đúng phần tên file, loại bỏ toàn bộ thư mục cha (/hoặc \)
        String name = Paths.get(rawFileName).getFileName().toString();
        // Loại bỏ các ký tự đặc biệt nguy hiểm trên hệ điều hành
        name = name.replaceAll("[/\\\\?%*:|\"<>]", "_").trim();
        return name.isEmpty() ? "downloaded_file" : name;
    }

    /**
     * Đảm bảo không ghi đè file cũ nếu trùng tên: tạo "tenfile (1).ext", "tenfile (2).ext"
     */
    private File resolveNonConflictingFile(File dir, String fileName) {
        File file = new File(dir, fileName);
        if (!file.exists()) {
            return file;
        }

        String baseName = fileName;
        String extension = "";
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex > 0) {
            baseName = fileName.substring(0, dotIndex);
            extension = fileName.substring(dotIndex);
        }

        int count = 1;
        while (file.exists()) {
            file = new File(dir, baseName + " (" + count + ")" + extension);
            count++;
        }
        return file;
    }

    /**
     * Ghi một chunk nhị phân vào file.
     */
    public synchronized void writeChunk(byte[] data) throws IOException {
        if (isFinished) {
            throw new IOException("Phiên nhận file đã kết thúc, không thể ghi thêm.");
        }
        outputStream.write(data);
        bytesReceived += data.length;
    }

    /**
     * Đóng file và hoàn thành phiên nhận.
     */
    public synchronized void finish() throws IOException {
        if (!isFinished) {
            outputStream.flush();
            outputStream.close();
            isFinished = true;
        }
    }

    /**
     * Đóng file và xóa dữ liệu dang dở nếu xảy ra lỗi giữa chừng.
     */
    public synchronized void abort() {
        try {
            outputStream.close();
        } catch (IOException ignored) {
        }
        isFinished = true;
        if (targetFile.exists() && bytesReceived < totalBytes) {
            targetFile.delete(); // Xóa file rác tải dở
        }
    }

    public String getFileId() {
        return fileId;
    }

    public String getSender() {
        return sender;
    }

    public String getOriginalFileName() {
        return originalFileName;
    }

    public long getTotalBytes() {
        return totalBytes;
    }

    public long getBytesReceived() {
        return bytesReceived;
    }

    public File getTargetFile() {
        return targetFile;
    }
}
