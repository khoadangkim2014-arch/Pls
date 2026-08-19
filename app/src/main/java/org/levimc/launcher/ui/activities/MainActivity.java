package org.levimc.launcher.ui.activities;

import static org.levimc.launcher.core.minecraft.MinecraftProcessRestarterKt.ACTION_MAIN_ACTIVITY_FIRST_DRAWN;
import static org.levimc.launcher.core.minecraft.MinecraftProcessRestarterKt.EXTRA_CLOSE_RESTART_ACTIVITY_ON_FIRST_DRAW;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.SystemClock;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import net.raphimc.minecraftauth.bedrock.BedrockAuthManager;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import org.levimc.launcher.R;
import org.levimc.launcher.core.auth.MsftAccountStore;
import org.levimc.launcher.core.auth.MsftAuthManager;
import org.levimc.launcher.core.content.ContentManager;
import org.levimc.launcher.core.minecraft.LaunchTrace;
import org.levimc.launcher.core.minecraft.MinecraftImportIntents;
import org.levimc.launcher.core.minecraft.MinecraftLauncher;
import org.levimc.launcher.core.mods.FileHandler;
import org.levimc.launcher.core.mods.Mod;
import org.levimc.launcher.core.mods.inbuilt.cosmos.CosmosResponsesGit;
import org.levimc.launcher.core.versions.GameVersion;
import org.levimc.launcher.core.versions.VersionManager;
import org.levimc.launcher.databinding.ActivityMainBinding;
import org.levimc.launcher.settings.FeatureSettings;
import org.levimc.launcher.ui.animation.DynamicAnim;
import org.levimc.launcher.ui.dialogs.CustomAlertDialog;
import org.levimc.launcher.ui.dialogs.LibsRepairDialog;
import org.levimc.launcher.ui.views.MainViewModel;
import org.levimc.launcher.ui.views.MainViewModelFactory;
import org.levimc.launcher.util.AccountTextUtils;
import org.levimc.launcher.util.ApkImportManager;
import org.levimc.launcher.util.DialogUtils;
import org.levimc.launcher.util.GithubReleaseUpdater;
import org.levimc.launcher.util.LanguageManager;
import org.levimc.launcher.util.LauncherStorage;
import org.levimc.launcher.util.PermissionsHandler;
import org.levimc.launcher.util.PersonalizationManager;
import org.levimc.launcher.util.ResourcepackHandler;
import org.levimc.launcher.util.StorageMigrationManager;
import org.levimc.launcher.util.StorageMigrationService;
import org.levimc.launcher.util.UIHelper;

import java.io.File;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends BaseActivity {
    private ActivityMainBinding binding;
    private MinecraftLauncher minecraftLauncher;
    private LanguageManager languageManager;
    private PermissionsHandler permissionsHandler;
    private FileHandler fileHandler;
    private ApkImportManager apkImportManager;
    private MainViewModel viewModel;
    private VersionManager versionManager;
    private StorageMigrationManager storageMigrationManager;

    private ActivityResultLauncher<Intent> permissionResultLauncher;
    private ActivityResultLauncher<Intent> apkImportResultLauncher;
    private ActivityResultLauncher<String> notificationPermissionLauncher;
    private ActivityResultLauncher<Intent> accountLoginLauncher;

    private LinearLayout modsListContainer;
    private ContentManager contentManager;
    private TextView worldsCountText, resourcePacksCountText, behaviorPacksCountText;

    private com.microsoft.xbox.idp.toolkit.CircleImageView accountAvatar;
    private View accountAvatarContainer;
    private ProgressBar avatarProgress;
    private Button signInButton;

    private final OkHttpClient avatarClient = new OkHttpClient();
    private final ExecutorService accountExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService storageMigrationExecutor = Executors.newSingleThreadExecutor();

    private org.levimc.launcher.ui.dialogs.LoadingDialog accountLoadingDialog;
    private boolean migrationPromptShown, migrationPromptCheckInFlight, postMigrationInitialized;
    private StorageMigrationService storageMigrationService;
    private boolean storageMigrationBound;
    private LibsRepairDialog storageMigrationDialog;
    private StorageMigrationService.MigrationState lastMigrationState;

    private final StorageMigrationService.MigrationListener storageMigrationListener =
            state -> runOnUiThread(() -> handleStorageMigrationState(state));

    private final ServiceConnection storageMigrationConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            storageMigrationService = ((StorageMigrationService.LocalBinder) service).getService();
            storageMigrationBound = true;
            storageMigrationService.addListener(storageMigrationListener);
            handleStorageMigrationState(storageMigrationService.getCurrentState());
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            if (storageMigrationService != null) storageMigrationService.removeListener(storageMigrationListener);
            storageMigrationService = null;
            storageMigrationBound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        closeLauncherRestartAfterFirstDraw();
        setupNavBar();
        setupManagersAndHandlers();

        new GithubReleaseUpdater(this, "Bedrock-Cosmos", "PocketCosmosLevi", permissionResultLauncher).checkUpdateOnLaunch();
        new CosmosResponsesGit(this).checkUpdateOnLaunch();

        showEulaIfNeeded();
        setupOnBackPressedCallback();
        initAccountHeader();

        binding.getRoot().post(this::showStorageMigrationPromptAfterEula);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleVersionDependentIntent();
    }

    private void closeLauncherRestartAfterFirstDraw() {
        Intent intent = getIntent();
        if (intent == null || !intent.getBooleanExtra(EXTRA_CLOSE_RESTART_ACTIVITY_ON_FIRST_DRAW, false)) return;
        intent.removeExtra(EXTRA_CLOSE_RESTART_ACTIVITY_ON_FIRST_DRAW);
        setIntent(intent);

        final View root = binding.getRoot();
        root.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                if (root.getViewTreeObserver().isAlive()) root.getViewTreeObserver().removeOnPreDrawListener(this);
                root.post(() -> {
                    hideSystemUI();
                    sendBroadcast(new Intent(ACTION_MAIN_ACTIVITY_FIRST_DRAWN).setPackage(getPackageName()));
                });
                return true;
            }
        });
    }

    private void initAccountHeader() {
        signInButton = findViewById(R.id.nav_sign_in_button);
        accountAvatar = findViewById(R.id.nav_account_avatar);
        accountAvatarContainer = findViewById(R.id.nav_account_avatar_container);
        avatarProgress = findViewById(R.id.nav_avatar_progress);

        if (signInButton != null) {
            signInButton.setOnClickListener(v -> accountLoginLauncher.launch(new Intent(this, MsftLoginActivity.class)));
            DynamicAnim.applyPressScale(signInButton);
        }
        if (accountAvatarContainer != null) {
            accountAvatarContainer.setOnClickListener(this::showAccountSwitchPopup);
            DynamicAnim.applyPressScale(accountAvatarContainer);
        }
        refreshAccountHeaderUI();
    }

    private MsftAccountStore.MsftAccount getActiveAccount() {
        List<MsftAccountStore.MsftAccount> list = MsftAccountStore.list(this);
        for (MsftAccountStore.MsftAccount a : list) if (a.active) return a;
        return null;
    }

    private void setupOnBackPressedCallback() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                CustomAlertDialog exitDialog = new CustomAlertDialog(MainActivity.this);
                exitDialog.setTitleText(getString(R.string.dialog_title_exit_app))
                        .setMessage(getString(R.string.dialog_message_exit_app))
                        .setPositiveButton(getString(R.string.dialog_positive_exit), v -> {
                            exitDialog.dismissImmediately();
                            finishAffinity();
                        })
                        .setNegativeButton(getString(R.string.dialog_negative_cancel), null)
                        .show();
            }
        });
    }

    private void refreshAccountHeaderUI() {
        MsftAccountStore.MsftAccount active = getActiveAccount();
        if (active == null) {
            if (signInButton != null) signInButton.setVisibility(View.VISIBLE);
            if (accountAvatarContainer != null) accountAvatarContainer.setVisibility(View.GONE);
            if (accountAvatar != null) accountAvatar.setImageDrawable(null);
            if (avatarProgress != null) avatarProgress.setVisibility(View.GONE);
        } else {
            if (signInButton != null) signInButton.setVisibility(View.GONE);
            if (accountAvatarContainer != null) accountAvatarContainer.setVisibility(View.VISIBLE);
            loadXboxAvatar(active);
        }
    }

    private void loadXboxAvatar(MsftAccountStore.MsftAccount active) {
        if (accountAvatar == null) return;
        String url = AccountTextUtils.sanitizeUrl(active != null ? active.xboxAvatarUrl : null);
        if (url == null) {
            if (avatarProgress != null) avatarProgress.setVisibility(View.GONE);
            accountAvatar.setImageDrawable(null);
            return;
        }

        Object currentUrl = accountAvatar.getTag(R.id.nav_account_avatar);
        if (url.equals(currentUrl) && accountAvatar.getDrawable() != null) {
            if (avatarProgress != null) avatarProgress.setVisibility(View.GONE);
            return;
        }

        Bitmap cached = AccountTextUtils.getCachedAvatar(url);
        if (cached != null) {
            accountAvatar.setTag(R.id.nav_account_avatar, url);
            accountAvatar.setImageBitmap(cached);
            if (avatarProgress != null) avatarProgress.setVisibility(View.GONE);
            return;
        }

        accountAvatar.setTag(R.id.nav_account_avatar, url);
        accountAvatar.setImageDrawable(null);
        if (avatarProgress != null) avatarProgress.setVisibility(View.VISIBLE);

        try {
            accountExecutor.execute(() -> {
                try (Response imgResp = avatarClient.newCall(new Request.Builder().url(url).build()).execute()) {
                    Bitmap bmp = (imgResp.isSuccessful() && imgResp.body() != null) ? BitmapFactory.decodeStream(imgResp.body().byteStream()) : null;
                    runOnUiThread(() -> {
                        if (!url.equals(accountAvatar.getTag(R.id.nav_account_avatar))) return;
                        if (bmp != null) {
                            AccountTextUtils.cacheAvatar(url, bmp);
                            accountAvatar.setImageBitmap(bmp);
                        }
                        if (avatarProgress != null) avatarProgress.setVisibility(View.GONE);
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> {
                        if (avatarProgress != null) avatarProgress.setVisibility(View.GONE);
                    });
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // Activity is being torn down; safe to ignore.
        }
    }

    private void showAccountSwitchPopup(View anchor) {
        List<MsftAccountStore.MsftAccount> list = MsftAccountStore.list(this);
        View content = LayoutInflater.from(this).inflate(R.layout.popup_account_switch, null);

        RecyclerView recyclerAccounts = content.findViewById(R.id.recycler_accounts);
        TextView manageAction = content.findViewById(R.id.manage_action);
        com.microsoft.xbox.idp.toolkit.CircleImageView headerAvatar = content.findViewById(R.id.header_avatar);
        TextView headerName = content.findViewById(R.id.header_name);

        MsftAccountStore.MsftAccount active = getActiveAccount();
        headerName.setText(AccountTextUtils.displayNameOrNotSigned(this, active));
        if (accountAvatar != null && accountAvatar.getDrawable() != null) {
            headerAvatar.setImageDrawable(accountAvatar.getDrawable());
        }

        PopupWindow popup = new PopupWindow(content, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);

        List<MsftAccountStore.MsftAccount> displayList = new ArrayList<>();
        for (MsftAccountStore.MsftAccount a : list) {
            if (active == null || !TextUtils.equals(a.id, active.id)) displayList.add(a);
        }

        recyclerAccounts.setLayoutManager(new LinearLayoutManager(this));
        recyclerAccounts.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @NonNull
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                TextView row = new TextView(parent.getContext());
                row.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                row.setPadding(32, 24, 24, 24);
                return new RecyclerView.ViewHolder(row) {};
            }

            @Override
            public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
                MsftAccountStore.MsftAccount account = displayList.get(position);
                TextView tv = (TextView) holder.itemView;
                tv.setText(AccountTextUtils.titleOrUnknown(account));
                tv.setOnClickListener(v -> {
                    popup.dismiss();
                    MsftAccountStore.setActive(MainActivity.this, account.id);
                    refreshAccountHeaderUI();
                });
            }

            @Override
            public int getItemCount() { return displayList.size(); }
        });

        manageAction.setOnClickListener(v -> {
            popup.dismiss();
            startActivity(new Intent(this, AccountsActivity.class));
        });

        popup.showAsDropDown(anchor, 0, 0, Gravity.END);
    }

    private void setupManagersAndHandlers() {
        languageManager = new LanguageManager(this);
        languageManager.applySavedLanguage();
        storageMigrationManager = new StorageMigrationManager(this);

        permissionResultLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                result -> { if (permissionsHandler != null) permissionsHandler.onActivityResult(result.getResultCode(), result.getData()); });

        notificationPermissionLauncher = registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {});

        apkImportResultLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                result -> { if (apkImportManager != null) apkImportManager.handleActivityResult(result.getResultCode(), result.getData()); });

        accountLoginLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == RESULT_OK && result.getData() != null
                    && result.getData().getBooleanExtra(MsftLoginActivity.EXTRA_LOGIN_COMPLETED, false)) {
                String name = result.getData().getStringExtra(MsftLoginActivity.EXTRA_LOGIN_NAME);
                Toast.makeText(this, getString(R.string.ms_login_success, name != null ? name : ""), Toast.LENGTH_SHORT).show();
            }
            refreshAccountHeaderUI();
        });

        permissionsHandler = PermissionsHandler.getInstance();
        permissionsHandler.setActivity(this, permissionResultLauncher);
        initListeners();
    }

    private void initializeAfterMigrationGate() {
        if (postMigrationInitialized || isFinishing() || isDestroyed()) return;
        postMigrationInitialized = true;

        minecraftLauncher = new MinecraftLauncher(this);
        viewModel = new ViewModelProvider(this, new MainViewModelFactory(getApplication())).get(MainViewModel.class);
        apkImportManager = new ApkImportManager(this, viewModel);

        initModsSection();
        initContentManagementSection();
        initMiscellaneousSection();
        initializeVersionManager();
    }

    private void initializeVersionManager() {
        binding.launchButton.setEnabled(false);
        versionManager = VersionManager.getIfInitialized();
        if (versionManager != null) {
            onVersionManagerReady();
            return;
        }
        VersionManager.initializeAsync(this, manager -> runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            versionManager = manager;
            onVersionManagerReady();
        }));
    }

    private void onVersionManagerReady() {
        if (versionManager == null || binding == null) return;
        fileHandler = new FileHandler(this, viewModel, versionManager);
        setTextMinecraftVersion();
        updateViewModelVersion();
        repairNeededVersions();
        binding.launchButton.setEnabled(true);
        handleVersionDependentIntent();
        refreshContentCounts();
    }

    private void handleVersionDependentIntent() {
        if (versionManager == null || fileHandler == null) return;
        if (!forwardIncomingMinecraftResourceToRunningGame()) {
            checkResourcepack();
            handleIncomingFiles();
        }
        handleMinecraftUriLaunch();
    }

    private void initModsSection() {
        if (viewModel == null) return;
        modsListContainer = binding.modsListContainer;

        binding.manageModsButton.setOnClickListener(v -> startActivity(new Intent(this, ModsFullscreenActivity.class)));
        DynamicAnim.applyPressScale(binding.manageModsButton);

        viewModel.getModsLiveData().observe(this, this::updateModsUI);
    }

    private void updateViewModelVersion() {
        if (viewModel == null || versionManager == null) return;
        GameVersion selectedVersion = versionManager.getSelectedVersion();
        if (selectedVersion != null) viewModel.setCurrentVersion(selectedVersion);
    }

    private void checkResourcepack() {
        new ResourcepackHandler(this, minecraftLauncher, Executors.newSingleThreadExecutor()).checkIntentForResourcepack();
    }

    private void repairNeededVersions() {
        GameVersion selectedVersion = versionManager != null ? versionManager.getSelectedVersion() : null;
        if (selectedVersion != null && selectedVersion.needsRepair) {
            VersionManager.attemptRepairLibs(this, selectedVersion);
        }
    }

    private void showStorageMigrationPromptIfNeeded() {
        if (postMigrationInitialized || migrationPromptShown || migrationPromptCheckInFlight || storageMigrationManager == null) return;
        if (isFinishing() || isDestroyed()) return;
        if (StorageMigrationService.isMigrationRunning(this)) {
            resumeStorageMigrationService();
            return;
        }
        migrationPromptCheckInFlight = true;
        try {
            storageMigrationExecutor.execute(() -> {
                boolean shouldOffer = false;
                try { shouldOffer = storageMigrationManager.shouldOfferMigration(); } catch (Exception ignored) {}
                boolean finalShouldOffer = shouldOffer;
                runOnUiThread(() -> {
                    migrationPromptCheckInFlight = false;
                    if (isFinishing() || isDestroyed()) return;
                    if (!finalShouldOffer) {
                        initializeAfterMigrationGate();
                    } else {
                        showStorageMigrationPromptDialog();
                    }
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // Activity is being torn down and its executor was already shut down
            // (e.g. a posted callback fired after onDestroy()); safe to ignore.
            migrationPromptCheckInFlight = false;
        }
    }

    private void showStorageMigrationPromptDialog() {
        migrationPromptShown = true;
        new CustomAlertDialog(this)
                .setTitleText(getString(R.string.storage_migration_title))
                .setMessage(getString(R.string.storage_migration_message, LauncherStorage.getTargetAppRootDisplayPath(this)))
                .setPositiveButton(getString(R.string.storage_migration_start), v -> startStorageMigrationService())
                .setNegativeButton(getString(R.string.exit), v -> finishAffinity())
                .show();
    }

    private void showStorageMigrationPromptAfterEula() {
        if (getSharedPreferences("LauncherPrefs", MODE_PRIVATE).getBoolean("eula_accepted", false)) {
            showStorageMigrationPromptIfNeeded();
        }
    }

    private void startStorageMigrationService() {
        if (isFinishing()) return;
        showStorageMigrationDialog();
        StorageMigrationService.startMigration(this);
        bindStorageMigrationService();
    }

    private void resumeStorageMigrationService() {
        if (isFinishing()) return;
        showStorageMigrationDialog();
        StorageMigrationService.startMigration(this);
        bindStorageMigrationService();
    }

    private void bindStorageMigrationService() {
        if (storageMigrationBound || !StorageMigrationService.isMigrationRunning(this)) return;
        bindService(new Intent(this, StorageMigrationService.class), storageMigrationConnection, Context.BIND_AUTO_CREATE);
    }

    private void unbindStorageMigrationService() {
        if (!storageMigrationBound) return;
        if (storageMigrationService != null) storageMigrationService.removeListener(storageMigrationListener);
        unbindService(storageMigrationConnection);
        storageMigrationBound = false;
        storageMigrationService = null;
    }

    private void showStorageMigrationDialog() {
        if (isFinishing() || (storageMigrationDialog != null && storageMigrationDialog.isShowing())) return;
        storageMigrationDialog = new LibsRepairDialog(this);
        storageMigrationDialog.setCanceledOnTouchOutside(false);
        storageMigrationDialog.show();
    }

    private void handleStorageMigrationState(StorageMigrationService.MigrationState state) {
        if (state == null || isFinishing()) return;
        lastMigrationState = state;
        if (state.isFinished()) {
            dismissStorageMigrationDialog(() -> {
                initializeAfterMigrationGate();
                refreshContentCounts();
            });
        }
    }

    private void dismissStorageMigrationDialog(Runnable afterDismiss) {
        if (storageMigrationDialog != null && storageMigrationDialog.isShowing()) {
            storageMigrationDialog.dismiss();
        }
        storageMigrationDialog = null;
        if (afterDismiss != null) afterDismiss.run();
    }

    private void showEulaIfNeeded() {
        SharedPreferences prefs = getSharedPreferences("LauncherPrefs", MODE_PRIVATE);
        if (!prefs.getBoolean("eula_accepted", false)) {
            new CustomAlertDialog(this)
                    .setTitleText(getString(R.string.eula_title))
                    .setMessage(getString(R.string.eula_message))
                    .setPositiveButton(getString(R.string.eula_agree), v -> {
                        prefs.edit().putBoolean("eula_accepted", true).apply();
                        showStorageMigrationPromptIfNeeded();
                    })
                    .setNegativeButton(getString(R.string.eula_exit), v -> finishAffinity())
                    .show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshAccountHeaderUI();
        if (StorageMigrationService.isMigrationRunning(this)) {
            resumeStorageMigrationService();
            return;
        }
        if (!postMigrationInitialized) {
            showStorageMigrationPromptAfterEula();
            return;
        }
        if (versionManager != null) {
            setTextMinecraftVersion();
            viewModel.refreshMods();
            refreshContentCounts();
        }
    }

    @Override
    protected void onStop() {
        unbindStorageMigrationService();
        super.onStop();
    }

    private void initListeners() {
        binding.launchButton.setEnabled(false);
        binding.launchButton.setOnClickListener(v -> launchGame());
        DynamicAnim.applyPressScale(binding.launchButton);

        binding.selectVersionButton.setOnClickListener(v -> showVersionSelectDialog());
        DynamicAnim.applyPressScale(binding.selectVersionButton);

        FeatureSettings.init(getApplicationContext());
        showRandomTip();
    }

    private void showRandomTip() {
        String[] tips = getResources().getStringArray(R.array.launcher_tips);
        if (tips.length == 0 || binding.tipText == null) return;
        binding.tipText.setText(tips[new Random().nextInt(tips.length)]);
    }

    private void initContentManagementSection() {
        worldsCountText = binding.contentWorldsCount;
        resourcePacksCountText = binding.contentResourcePacksCount;
        behaviorPacksCountText = binding.contentBehaviorPacksCount;

        contentManager = ContentManager.getInstance(this);
        contentManager.getWorldsLiveData().observe(this, w -> { if (worldsCountText != null) worldsCountText.setText(String.valueOf(w != null ? w.size() : 0)); });
        contentManager.getResourcePacksLiveData().observe(this, p -> { if (resourcePacksCountText != null) resourcePacksCountText.setText(String.valueOf(p != null ? p.size() : 0)); });
        contentManager.getBehaviorPacksLiveData().observe(this, p -> { if (behaviorPacksCountText != null) behaviorPacksCountText.setText(String.valueOf(p != null ? p.size() : 0)); });

        binding.contentViewAll.setOnClickListener(v -> startActivity(new Intent(this, ContentManagementActivity.class)));
        binding.contentWorldsRow.setOnClickListener(v -> openContentList(ContentListActivity.TYPE_WORLDS));
        binding.contentResourcePacksRow.setOnClickListener(v -> openContentList(ContentListActivity.TYPE_RESOURCE_PACKS));
        binding.contentBehaviorPacksRow.setOnClickListener(v -> openContentList(ContentListActivity.TYPE_BEHAVIOR_PACKS));
    }

    private void refreshContentCounts() {
        if (versionManager == null || contentManager == null) return;
        GameVersion currentVersion = versionManager.getSelectedVersion();
        if (currentVersion == null) return;

        File baseDir = LauncherStorage.getContentGameDataDir(this, currentVersion.getStorageProfileId(), FeatureSettings.StorageType.INTERNAL);
        contentManager.setStorageDirectories(
                new File(baseDir, "minecraftWorlds"),
                new File(baseDir, "resource_packs"),
                new File(baseDir, "behavior_packs"),
                new File(baseDir, "skin_packs"),
                new File(baseDir, "Screenshots"),
                new File(baseDir, "minecraftpe")
        );
    }

    private void openContentList(int contentType) {
        if (versionManager == null || versionManager.getSelectedVersion() == null) return;
        Intent intent = new Intent(this, ContentListActivity.class);
        intent.putExtra(ContentListActivity.EXTRA_CONTENT_TYPE, contentType);
        startActivity(intent);
    }

    private void initMiscellaneousSection() {
        binding.miscCurseforgeRow.setOnClickListener(v -> startActivity(new Intent(this, CurseForgeActivity.class)));
        binding.miscCosmosRow.setOnClickListener(v -> startActivity(new Intent(this, CosmosActivity.class)));
        binding.miscAccountsRow.setOnClickListener(v -> startActivity(new Intent(this, AccountsActivity.class)));
        binding.miscQuickLaunchRow.setOnClickListener(v -> startActivity(new Intent(this, QuickLaunchActivity.class)));
    }

    private void launchGame() {
        if (versionManager == null) return;
        GameVersion version = versionManager.getSelectedVersion();

        if (version == null) {
            new CustomAlertDialog(this)
                    .setTitleText(getString(R.string.dialog_title_no_version))
                    .setMessage(getString(R.string.dialog_message_no_version))
                    .setPositiveButton(getString(R.string.dialog_positive_ok), null)
                    .show();
            return;
        }

        binding.launchButton.setEnabled(false);
        LaunchTrace trace = LaunchTrace.create(null);

        try {
            Intent launchIntent = createMinecraftLaunchIntent();
            launchIntent.putExtra(LaunchTrace.EXTRA_SESSION_ID, trace.getSessionId());
            launchIntent.putExtra(LaunchTrace.EXTRA_STARTED_ELAPSED_MS, SystemClock.elapsedRealtime() - trace.elapsedMs());

            minecraftLauncher.launch(launchIntent, version, new MinecraftLauncher.LaunchCallback() {
                @Override
                public void onLaunchStarted() {}

                @Override
                public void onLaunchFailed(Exception e) {
                    runOnUiThread(() -> { if (binding != null) binding.launchButton.setEnabled(true); });
                }
            });
        } catch (Exception e) {
            binding.launchButton.setEnabled(true);
        }
    }

    private Intent createMinecraftLaunchIntent() {
        Intent launchIntent = new Intent();
        Intent sourceIntent = getIntent();
        if (sourceIntent != null) {
            if (sourceIntent.hasExtra("MINECRAFT_URI")) launchIntent.putExtra("MINECRAFT_URI", sourceIntent.getStringExtra("MINECRAFT_URI"));
            if (sourceIntent.hasExtra("MINECRAFT_URI_ACTION")) launchIntent.putExtra("MINECRAFT_URI_ACTION", sourceIntent.getStringExtra("MINECRAFT_URI_ACTION"));
        }
        return launchIntent;
    }

    private void showVersionSelectDialog() {
        if (versionManager == null) return;
        List<GameVersion> allVersions = new ArrayList<>();
        if (versionManager.getInstalledVersions() != null) allVersions.addAll(versionManager.getInstalledVersions());
        if (versionManager.getCustomVersions() != null) allVersions.addAll(versionManager.getCustomVersions());

        View popupView = LayoutInflater.from(this).inflate(R.layout.popup_instance_selector, null);
        PopupWindow popup = new PopupWindow(popupView, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));

        RecyclerView recycler = popupView.findViewById(R.id.recycler_instances);
        recycler.setLayoutManager(new LinearLayoutManager(this));

        InstancePopupAdapter adapter = new InstancePopupAdapter(allVersions, versionManager.getSelectedVersion());
        recycler.setAdapter(adapter);

        adapter.setOnItemClickListener(version -> {
            versionManager.selectVersion(version);
            viewModel.setCurrentVersion(version);
            setTextMinecraftVersion();
            popup.dismiss();
        });

        popup.showAsDropDown(binding.selectVersionButton);
    }

    private static class InstancePopupAdapter extends RecyclerView.Adapter<InstancePopupAdapter.VH> {
        private final List<GameVersion> versions;
        private final GameVersion selected;
        private OnItemClickListener listener;

        interface OnItemClickListener { void onClick(GameVersion version); }
        void setOnItemClickListener(OnItemClickListener l) { this.listener = l; }

        InstancePopupAdapter(List<GameVersion> versions, GameVersion selected) {
            this.versions = versions;
            this.selected = selected;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_instance_popup, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            GameVersion v = versions.get(position);
            holder.name.setText(v.displayName != null ? v.displayName : v.directoryName);
            holder.itemView.setOnClickListener(view -> { if (listener != null) listener.onClick(v); });
        }

        @Override public int getItemCount() { return versions.size(); }

        static class VH extends RecyclerView.ViewHolder {
            TextView name;
            VH(View v) { super(v); name = v.findViewById(R.id.instance_name); }
        }
    }

    public void setTextMinecraftVersion() {
        if (binding == null) return;
        if (versionManager == null || versionManager.getSelectedVersion() == null) {
            binding.textMinecraftVersion.setText(getString(R.string.not_found_version));
            return;
        }
        binding.textMinecraftVersion.setText(versionManager.getSelectedVersion().directoryName);
    }

    private void handleIncomingFiles() {
        if (fileHandler == null) return;
        fileHandler.processIncomingFilesWithConfirmation(getIntent(), new FileHandler.FileOperationCallback() {
            @Override
            public void onSuccess(int processedFiles) { UIHelper.showToast(MainActivity.this, getString(R.string.files_processed, processedFiles)); }
            @Override
            public void onError(String errorMessage) { if (errorMessage != null) UIHelper.showToast(MainActivity.this, errorMessage); }
            @Override
            public void onProgressUpdate(int progress) { if (binding != null) binding.progressLoader.setProgress(progress); }
        }, false);
    }

    private boolean forwardIncomingMinecraftResourceToRunningGame() {
        Intent intent = getIntent();
        return MinecraftImportIntents.isMinecraftResourceIntent(this, intent) && MinecraftImportIntents.forwardToRunningMinecraft(this, intent);
    }

    private void handleMinecraftUriLaunch() {
        Intent intent = getIntent();
        if (intent != null && intent.getBooleanExtra("LAUNCH_WITH_URI", false)) {
            intent.removeExtra("LAUNCH_WITH_URI");
            setIntent(intent);
            binding.getRoot().post(this::launchGame);
        }
    }

    private void updateModsUI(List<Mod> mods) {
        if (binding == null || modsListContainer == null) return;
        modsListContainer.removeAllViews();
        if (mods == null) return;

        for (Mod mod : mods) {
            if (mod.isEnabled()) {
                TextView tv = new TextView(this);
                tv.setText(mod.getDisplayName());
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                modsListContainer.addView(tv);
            }
        }
    }

    private void setupNavBar() {
        View tab = findViewById(R.id.nav_tab_launch);
        if (tab != null) tab.setOnClickListener(v -> {});
    }

    @Override
    protected void onDestroy() {
        unbindStorageMigrationService();
        dismissStorageMigrationDialog(null);
        storageMigrationExecutor.shutdownNow();
        accountExecutor.shutdownNow();
        super.onDestroy();
    }
}
