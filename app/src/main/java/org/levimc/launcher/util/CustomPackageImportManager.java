package org.levimc.launcher.util;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.levimc.launcher.R;
import org.levimc.launcher.core.versions.GameVersion;
import org.levimc.launcher.core.versions.VersionManager;
import org.levimc.launcher.ui.dialogs.CustomAlertDialog;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
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
    private CustomAlertDialog currentPickerDialog;

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
        performImport(uri, packageName);
    }

    /**
     * Entry point for the "pick an installed app" flow: shows a searchable list of
     * currently installed, launchable apps and imports the selected app's own APK
     * (which is itself a valid zip archive) the same way a manually-picked .zip is.
     */
    public void showInstalledAppsPicker() {
        View contentView = LayoutInflater.from(activity).inflate(R.layout.dialog_installed_apps_picker, null, false);
        ProgressBar progress = contentView.findViewById(R.id.apps_loading_progress);
        TextView emptyText = contentView.findViewById(R.id.apps_empty_text);
        RecyclerView recyclerView = contentView.findViewById(R.id.installed_apps_recycler_view);
        EditText searchInput = contentView.findViewById(R.id.app_search_input);

        recyclerView.setLayoutManager(new LinearLayoutManager(activity));
        InstalledAppAdapter adapter = new InstalledAppAdapter(app -> {
            currentPickerDialog.dismiss();
            importFromInstalledApp(app);
        });
        recyclerView.setAdapter(adapter);

        currentPickerDialog = new CustomAlertDialog(activity)
                .setTitleText(activity.getString(R.string.custom_package_pick_app_title))
                .setCustomView(contentView)
                .setNegativeButton(activity.getString(R.string.exit), v -> {});
        currentPickerDialog.show();

        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                adapter.filter(s.toString());
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        Executors.newSingleThreadExecutor().execute(() -> {
            List<InstalledAppInfo> apps = loadLaunchableApps();
            activity.runOnUiThread(() -> {
                progress.setVisibility(View.GONE);
                if (apps.isEmpty()) {
                    emptyText.setVisibility(View.VISIBLE);
                } else {
                    recyclerView.setVisibility(View.VISIBLE);
                    adapter.setApps(apps);
                }
            });
        });
    }

    private List<InstalledAppInfo> loadLaunchableApps() {
        PackageManager pm = activity.getPackageManager();
        List<ApplicationInfo> installed = pm.getInstalledApplications(PackageManager.GET_META_DATA);
        List<InstalledAppInfo> result = new ArrayList<>();
        String ownPackage = activity.getPackageName();
        for (ApplicationInfo appInfo : installed) {
            if (appInfo.packageName.equals(ownPackage)) continue;
            if (pm.getLaunchIntentForPackage(appInfo.packageName) == null) continue;
            String label;
            Drawable icon;
            try {
                label = String.valueOf(pm.getApplicationLabel(appInfo));
                icon = pm.getApplicationIcon(appInfo);
            } catch (Exception e) {
                continue;
            }
            result.add(new InstalledAppInfo(appInfo, label, icon));
        }
        result.sort((a, b) -> a.label.compareToIgnoreCase(b.label));
        return result;
    }

    private void importFromInstalledApp(InstalledAppInfo app) {
        String sourceDir = app.appInfo.sourceDir;
        if (sourceDir == null) {
            notifyError(activity.getString(R.string.custom_package_extract_failed, "no source APK"));
            return;
        }
        String safeName = app.label.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (safeName.isEmpty()) safeName = app.appInfo.packageName;
        Uri uri = Uri.fromFile(new File(sourceDir));
        performImport(uri, safeName);
    }

    private void performImport(Uri sourceUri, String packageName) {
        GameVersion selected = VersionManager.get(activity).getSelectedVersion();
        if (selected == null || selected.versionDir == null) {
            notifyError(activity.getString(R.string.custom_package_no_version));
            return;
        }

        File destRoot = new File(selected.versionDir, CUSTOM_PACKAGES_DIR);
        File destDir = new File(destRoot, packageName);

        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                extractZip(sourceUri, destDir);
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

    private static final class InstalledAppInfo {
        final ApplicationInfo appInfo;
        final String label;
        final Drawable icon;

        InstalledAppInfo(ApplicationInfo appInfo, String label, Drawable icon) {
            this.appInfo = appInfo;
            this.label = label;
            this.icon = icon;
        }
    }

    private interface OnAppPickedListener {
        void onAppPicked(InstalledAppInfo app);
    }

    private static final class InstalledAppAdapter extends RecyclerView.Adapter<InstalledAppAdapter.ViewHolder> {
        private final List<InstalledAppInfo> allApps = new ArrayList<>();
        private final List<InstalledAppInfo> shownApps = new ArrayList<>();
        private final OnAppPickedListener listener;

        InstalledAppAdapter(OnAppPickedListener listener) {
            this.listener = listener;
        }

        void setApps(List<InstalledAppInfo> apps) {
            allApps.clear();
            allApps.addAll(apps);
            shownApps.clear();
            shownApps.addAll(apps);
            notifyDataSetChanged();
        }

        void filter(String query) {
            shownApps.clear();
            if (query == null || query.trim().isEmpty()) {
                shownApps.addAll(allApps);
            } else {
                String lower = query.toLowerCase();
                for (InstalledAppInfo app : allApps) {
                    if (app.label.toLowerCase().contains(lower)
                            || app.appInfo.packageName.toLowerCase().contains(lower)) {
                        shownApps.add(app);
                    }
                }
            }
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_installed_app, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            InstalledAppInfo app = shownApps.get(position);
            holder.label.setText(app.label);
            holder.packageName.setText(app.appInfo.packageName);
            holder.icon.setImageDrawable(app.icon);
            holder.itemView.setOnClickListener(v -> {
                if (listener != null) listener.onAppPicked(app);
            });
        }

        @Override
        public int getItemCount() {
            return shownApps.size();
        }

        static class ViewHolder extends RecyclerView.ViewHolder {
            final ImageView icon;
            final TextView label;
            final TextView packageName;

            ViewHolder(@NonNull View itemView) {
                super(itemView);
                icon = itemView.findViewById(R.id.app_icon);
                label = itemView.findViewById(R.id.app_label);
                packageName = itemView.findViewById(R.id.app_package_name);
            }
        }
    }
}
