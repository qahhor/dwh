package com.greenwhite.dwh.instance.mf.service;

import com.greenwhite.dwh.core.error.ErrorCode;
import com.greenwhite.dwh.instance.common.error.ApiException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Inspects a bounded prefix and then pushes it back, so upload remains streaming
 * and memory use does not grow with the file size.
 */
@Component
public class FileContentInspector {

    private static final int PREFIX_BYTES = 8 * 1024;
    private static final String BINARY = "application/octet-stream";
    private static final Set<String> ZIP_MIME_TYPES = Set.of(
            "application/zip",
            "application/x-zip-compressed",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/vnd.oasis.opendocument.text",
            "application/vnd.oasis.opendocument.spreadsheet",
            "application/vnd.oasis.opendocument.presentation"
    );
    private static final Map<String, String> STRICT_SIGNATURE_TYPES = Map.of(
            "application/pdf", "application/pdf",
            "image/png", "image/png",
            "image/jpeg", "image/jpeg",
            "image/gif", "image/gif"
    );

    public Inspection inspect(String declaredMimeType, InputStream source) {
        if (source == null) {
            throw ApiException.badRequest(ErrorCode.FILE_CORRUPTED, "Содержимое файла отсутствует");
        }

        PushbackInputStream replayable = new PushbackInputStream(source, PREFIX_BYTES);
        byte[] prefix;
        try {
            prefix = replayable.readNBytes(PREFIX_BYTES);
            replayable.unread(prefix);
        } catch (IOException exception) {
            throw ApiException.badRequest(ErrorCode.FILE_CORRUPTED, "Не удалось прочитать содержимое файла");
        }

        if (isExecutable(prefix)) {
            throw ApiException.badRequest(
                    ErrorCode.FILE_TYPE_FORBIDDEN,
                    "Обнаружена сигнатура исполняемого файла; загрузка запрещена");
        }

        String declared = normalize(declaredMimeType);
        String detected = detectSafeType(prefix);
        validateDeclaredType(declared, detected);
        String verified = "application/zip".equals(detected) && ZIP_MIME_TYPES.contains(declared)
                ? declared
                : detected != null ? detected : declared;
        return new Inspection(replayable, verified);
    }

    private static void validateDeclaredType(String declared, String detected) {
        String expectedSignature = STRICT_SIGNATURE_TYPES.get(declared);
        if (expectedSignature != null && !expectedSignature.equals(detected)) {
            throw mimeMismatch(declared, detected);
        }
        if (ZIP_MIME_TYPES.contains(declared) && !"application/zip".equals(detected)) {
            throw mimeMismatch(declared, detected);
        }
    }

    private static ApiException mimeMismatch(String declared, String detected) {
        String actual = detected != null ? detected : "неизвестный тип";
        return ApiException.badRequest(
                ErrorCode.FILE_TYPE_FORBIDDEN,
                "Заявленный MIME " + declared + " не соответствует содержимому файла (" + actual + ")");
    }

    private static String normalize(String mimeType) {
        if (mimeType == null || mimeType.isBlank()) {
            return BINARY;
        }
        int parameters = mimeType.indexOf(';');
        String value = parameters >= 0 ? mimeType.substring(0, parameters) : mimeType;
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static String detectSafeType(byte[] bytes) {
        if (startsWith(bytes, "%PDF-")) return "application/pdf";
        if (startsWith(bytes, 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)) return "image/png";
        if (startsWith(bytes, 0xff, 0xd8, 0xff)) return "image/jpeg";
        if (startsWith(bytes, "GIF87a") || startsWith(bytes, "GIF89a")) return "image/gif";
        if (startsWith(bytes, 0x50, 0x4b, 0x03, 0x04)
                || startsWith(bytes, 0x50, 0x4b, 0x05, 0x06)
                || startsWith(bytes, 0x50, 0x4b, 0x07, 0x08)) {
            return "application/zip";
        }
        return null;
    }

    private static boolean isExecutable(byte[] bytes) {
        return startsWith(bytes, 0x4d, 0x5a)
                || startsWith(bytes, 0x7f, 0x45, 0x4c, 0x46)
                || startsWith(bytes, 0x23, 0x21)
                || startsWith(bytes, 0xfe, 0xed, 0xfa, 0xce)
                || startsWith(bytes, 0xfe, 0xed, 0xfa, 0xcf)
                || startsWith(bytes, 0xce, 0xfa, 0xed, 0xfe)
                || startsWith(bytes, 0xcf, 0xfa, 0xed, 0xfe)
                || startsWith(bytes, 0xca, 0xfe, 0xba, 0xbe);
    }

    private static boolean startsWith(byte[] bytes, String value) {
        return startsWith(bytes, value.chars().toArray());
    }

    private static boolean startsWith(byte[] bytes, int... signature) {
        if (bytes.length < signature.length) return false;
        for (int i = 0; i < signature.length; i++) {
            if ((bytes[i] & 0xff) != signature[i]) return false;
        }
        return true;
    }

    public record Inspection(InputStream content, String verifiedMimeType) {}
}
