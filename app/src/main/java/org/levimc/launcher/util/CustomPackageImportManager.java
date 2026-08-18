package org.levimc.launcher.util;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.widget.Toast;

import org.levimc.launcher.R;
import org.levimc.launcher.core.versions.GameVersion;
import org.levimc.launcher.core.versions.VersionManager;
import org.levimc.launcher.ui.dialogs.CustomAlertDialog;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Enumeration;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Imports a custom package archive (.zip) and extracts it into a
 * "custom_packages/&lt;archive name&gt;" folder inside the currently selected game
 * version's directory. Useful for resource/texture/config bundles that don't fit
 * the standard .mcpack/.mcaddon/mod pipeline.
 */
public class CustomPackageImportManager {
    public static final String CUSTOM_PACKAGES_DIR = "custom_packages";

    private final Activity activity;

    public interface OnImportCompleteListener {
        void onImportComplete(String packageName);
        void onImportError(String message);
    }

    private OnImportCompleteListener listener;

    public CustomPackageImportManager(Activity activity) {
        this.activity = activity;
    }

    public void setOnImportCompleteListener(OnImportCompleteListener listener) {
        this.listener = listener;
    }

    public void handleActivityResult(int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        handlePackageImport(uri);
    }

    public void handlePackageImport(Uri uri) {
        String fileName = getFileName(uri);
        if (fileName == null || !fileName.toLowerCase().endsWith(".zip")) {
            new CustomAlertDialog(activity)
                    .setTitleText(activity.getString(R.string.illegal_apk_title))
                    .setMessage(activity.getString(R.string.custom_package_not_zip))
                    .setPositiveButton(activity.getString(R.string.exit), v -> {})
                    .show();
            return;
        }

        GameVersion selected = VersionManager.get(activity).getSelectedVersion();
        if (selected == null || selected.versionDir == null) {
            notifyError(activity.getString(R.string.custom_package_no_version));
            return;
        }

        String packageName = fileName.substring(0, fileName.length() - 4);
        File destRoot = new File(selected.versionDir, CUSTOM_PACKAGES_DIR);
        File destDir = new File(destRoot, packageName);

        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                extractZip(uri, destDir);
                activity.runOnUiThread(() -> {
                    Toast.makeText(activity,
                            activity.getString(R.string.custom_package_installed, packageName),
                            Toast.LENGTH_LONG).show();
                    if (listener != null) listener.onImportComplete(packageName);
                });
            } catch (IOException e) {
                deleteRecursive(destDir);
                activity.runOnUiThread(() -> notifyError(
                        activity.getString(R.string.custom_package_extract_failed, e.getMessage())));
            }
        });
    }

    private void notifyError(String message) {
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
        if (listener != null) listener.onImportError(message);
    }

    private void extractZip(Uri sourceUri, File destDir) throws IOException {
        if (destDir.exists()) deleteRecursive(destDir);
        if (!destDir.mkdirs()) {
            throw new IOException("Could not create destination directory: " + destDir);
        }

        File tempZip = File.createTempFile("custom_pkg_", ".zip", activity.getCacheDir());
        try {
            try (InputStream in = activity.getContentResolver().openInputStream(sourceUri);
                 OutputStream out = new FileOutputStream(tempZip)) {
                if (in == null) throw new IOException("Could not open package for reading");
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
            }

            try (ZipFile zipFile = new ZipFile(tempZip)) {
                String destCanonicalPath = destDir.getCanonicalPath() + File.separator;
                Enumeration<? extends ZipEntry> entries = zipFile.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    File outFile = new File(destDir, entry.getName());
                    String outCanonicalPath = outFile.getCanonicalPath();
                    // Guard against zip-slip: reject entries that escape the destination dir.
                    if (!outCanonicalPath.startsWith(destCanonicalPath)) {
                        continue;
                    }
                    if (entry.isDirectory()) {
                        outFile.mkdirs();
                        continue;
                    }
                    File parent = outFile.getParentFile();
                    if (parent != null && !parent.exists()) parent.mkdirs();
                    try (InputStream entryIn = zipFile.getInputStream(entry);
                         OutputStream entryOut = new FileOutputStream(outFile)) {
                        byte[] buffer = new byte[8192];
                        int read;
                        while ((read = entryIn.read(buffer)) != -1) {
                            entryOut.write(buffer, 0, read);
                        }
                    }
                }
            }
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tempZip.delete();
        }
    }

    private void deleteRecursive(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursive(child);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    private String getFileName(Uri uri) {
        String result = null;
        if ("content".equals(uri.getScheme())) {
            try (Cursor cursor = activity.getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (nameIndex != -1) {
                        result = cursor.getString(nameIndex);
                    }
                }
            }
        }
        if (result == null) {
            result = uri.getLastPathSegment();
        }
        return result;
    }
}
