// SPDX-License-Identifier: GPL-2.0-or-later

package org.dolphinemu.dolphinemu.ui.main

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup.MarginLayoutParams
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout.OnRefreshListener
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.tabs.TabLayout
import org.dolphinemu.dolphinemu.R
import org.dolphinemu.dolphinemu.adapters.PlatformPagerAdapter
import org.dolphinemu.dolphinemu.databinding.ActivityMainBinding
import org.dolphinemu.dolphinemu.features.settings.model.BooleanSetting
import org.dolphinemu.dolphinemu.features.settings.model.IntSetting
import org.dolphinemu.dolphinemu.features.settings.model.NativeConfig
import org.dolphinemu.dolphinemu.features.settings.ui.MenuTag
import org.dolphinemu.dolphinemu.features.settings.ui.SettingsActivity
import org.dolphinemu.dolphinemu.fragments.GridOptionDialogFragment
import org.dolphinemu.dolphinemu.services.GameFileCacheManager
import org.dolphinemu.dolphinemu.ui.platform.PlatformGamesView
import org.dolphinemu.dolphinemu.ui.platform.PlatformTab
import org.dolphinemu.dolphinemu.utils.AfterDirectoryInitializationRunner
import org.dolphinemu.dolphinemu.utils.DirectoryInitialization
import org.dolphinemu.dolphinemu.utils.InsetsHelper
import org.dolphinemu.dolphinemu.utils.PermissionsHandler
import org.dolphinemu.dolphinemu.utils.StartupHandler
import org.dolphinemu.dolphinemu.utils.ThemeHelper
import org.dolphinemu.dolphinemu.utils.WiiUtils

class MainActivity : AppCompatActivity(), MainView, OnRefreshListener, ThemeProvider {
    override var themeId = 0

    private val presenter = MainPresenter(this, this)

    private lateinit var binding: ActivityMainBinding

    private lateinit var menu: Menu

    private var storagePermissionDialogShown = false
    private var waitingForStoragePermission = false

    companion object {
        private const val KEY_STORAGE_DIALOG_SHOWN = "storagePermissionDialogShown"
        private const val KEY_WAITING_FOR_STORAGE_PERMISSION = "waitingForStoragePermission"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen().setKeepOnScreenCondition { !DirectoryInitialization.areDolphinDirectoriesReady() }

        ThemeHelper.setTheme(this)
        enableEdgeToEdge()

        super.onCreate(savedInstanceState)

        storagePermissionDialogShown = savedInstanceState?.getBoolean(KEY_STORAGE_DIALOG_SHOWN) ?: false
        waitingForStoragePermission = savedInstanceState?.getBoolean(KEY_WAITING_FOR_STORAGE_PERMISSION) ?: false

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setInsets()
        ThemeHelper.enableStatusBarScrollTint(this, binding.appbarMain)

        binding.toolbarMain.setTitle(R.string.app_name)
        setSupportActionBar(binding.toolbarMain)

        // Set up the FAB.
        binding.buttonAddDirectory.setOnClickListener {
            AfterDirectoryInitializationRunner().runWithLifecycle(this) {
                presenter.launchFileListActivity()
            }
        }
        binding.appbarMain.addOnOffsetChangedListener { appBarLayout: AppBarLayout, verticalOffset: Int ->
            if (verticalOffset == 0) {
                binding.buttonAddDirectory.extend()
            } else if (appBarLayout.totalScrollRange == -verticalOffset) {
                binding.buttonAddDirectory.shrink()
            }
        }

        presenter.onCreate()

        // Stuff in this block only happens when this activity is newly created (i.e. not a rotation)
        if (savedInstanceState == null) {
            StartupHandler.HandleInit(this)
            AfterDirectoryInitializationRunner().runWithLifecycle(this) {
                ThemeHelper.setCorrectTheme(this)
            }
        }
        if (!DirectoryInitialization.isWaitingForWriteAccess(this)) {
            AfterDirectoryInitializationRunner()
                .runWithLifecycle(this) { setPlatformTabsAndStartGameFileCacheService() }
        }
    }

    override fun onResume() {
        ThemeHelper.setCorrectTheme(this)

        super.onResume()
        if (waitingForStoragePermission &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            Environment.isExternalStorageManager()
        ) {
            waitingForStoragePermission = false
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
                ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK) }
            if (launchIntent != null) startActivity(launchIntent)
            android.os.Process.killProcess(android.os.Process.myPid())
            return
        }
        checkStoragePermission()
        if (DirectoryInitialization.shouldStart(this)) {
            DirectoryInitialization.start(this)
            AfterDirectoryInitializationRunner()
                .runWithLifecycle(this) { setPlatformTabsAndStartGameFileCacheService() }
        }
        AfterDirectoryInitializationRunner().runWithLifecycle(this) { checkMigration() }

        presenter.onResume()
    }

    fun checkMigration() {
        // Don't stack on top of the analytics prompt. AnalyticsDialog calls this again as soon
        // as it's dismissed, so migration still shows right away rather than waiting for the
        // next onResume (e.g. the user backgrounding and returning to the app).
        if (!BooleanSetting.MAIN_ANALYTICS_PERMISSION_ASKED.boolean) return

        when (DirectoryInitialization.getMigrationState(this)) {
            DirectoryInitialization.MigrationState.NONE -> return
            DirectoryInitialization.MigrationState.CLEAN -> {
                AlertDialog.Builder(this)
                    .setTitle(R.string.migrate_user_data_title)
                    .setMessage(R.string.migrate_user_data_message)
                    .setPositiveButton(R.string.migrate_user_data_yes) { _, _ -> startMigration() }
                    .setNegativeButton(R.string.migrate_user_data_no) { _, _ ->
                        DirectoryInitialization.markMigrationOffered(this)
                    }
                    .setCancelable(false)
                    .show()
            }
            DirectoryInitialization.MigrationState.CONFLICT -> {
                AlertDialog.Builder(this)
                    .setTitle(R.string.migrate_conflict_title)
                    .setMessage(R.string.migrate_conflict_message)
                    .setPositiveButton(R.string.migrate_conflict_replace) { _, _ -> startMigration() }
                    .setNegativeButton(R.string.migrate_conflict_keep) { _, _ ->
                        DirectoryInitialization.markMigrationOffered(this)
                    }
                    .setCancelable(false)
                    .show()
            }
        }
    }

    private fun startMigration() {
        val pad = resources.getDimensionPixelSize(R.dimen.spacing_large)

        val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
        }
        val progressText = TextView(this).apply {
            setText(R.string.migrate_user_data_progress)
            setPadding(0, pad / 2, 0, 0)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad / 2)
            addView(progressBar)
            addView(progressText)
        }

        val progressDialog = AlertDialog.Builder(this)
            .setTitle(R.string.migrate_user_data_in_progress)
            .setView(container)
            .setCancelable(false)
            .show()

        DirectoryInitialization.copyUserDataToNewLocation(
            this,
            onProgress = { copied, total ->
                runOnUiThread {
                    progressBar.isIndeterminate = false
                    progressBar.max = total
                    progressBar.progress = copied
                    progressText.text = getString(R.string.migrate_user_data_progress_file, copied, total)
                }
            }
        ) { result ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                progressDialog.dismiss()
                when (result) {
                    DirectoryInitialization.MigrationResult.SUCCESS -> {
                        DirectoryInitialization.markMigrationOffered(this)
                        AlertDialog.Builder(this)
                            .setTitle(R.string.migrate_user_data_success_title)
                            .setMessage(R.string.migrate_user_data_success_message)
                            .setPositiveButton(R.string.storage_restart_button) { _, _ ->
                                val launchIntent = packageManager
                                    .getLaunchIntentForPackage(packageName)
                                    ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK) }
                                if (launchIntent != null) startActivity(launchIntent)
                                android.os.Process.killProcess(android.os.Process.myPid())
                            }
                            .setCancelable(false)
                            .show()
                    }
                    DirectoryInitialization.MigrationResult.NOT_ENOUGH_SPACE -> {
                        Toast.makeText(this, R.string.migrate_user_data_failed_space, Toast.LENGTH_LONG).show()
                    }
                    DirectoryInitialization.MigrationResult.SD_CARD_UNAVAILABLE -> {
                        Toast.makeText(this, R.string.migrate_user_data_failed_sdcard, Toast.LENGTH_LONG).show()
                    }
                    DirectoryInitialization.MigrationResult.FAILED -> {
                        Toast.makeText(this, R.string.migrate_user_data_failed, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun checkStoragePermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        if (DirectoryInitialization.getStorageMode(this) == DirectoryInitialization.USER_DIR_MODE_SCOPED) return
        if (Environment.isExternalStorageManager()) return
        if (storagePermissionDialogShown) return

        storagePermissionDialogShown = true
        AlertDialog.Builder(this)
            .setTitle(R.string.storage_permission_title)
            .setMessage(R.string.storage_permission_needed)
            .setPositiveButton(R.string.storage_permission_grant) { _, _ ->
                waitingForStoragePermission = true
                val intent = Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            }
            .setNegativeButton(R.string.storage_permission_use_scoped) { _, _ ->
                DirectoryInitialization.setStorageMode(this, DirectoryInitialization.USER_DIR_MODE_SCOPED)
            }
            .setCancelable(false)
            .show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_STORAGE_DIALOG_SHOWN, storagePermissionDialogShown)
        outState.putBoolean(KEY_WAITING_FOR_STORAGE_PERMISSION, waitingForStoragePermission)
    }

    override fun onStop() {
        super.onStop()
        if (isChangingConfigurations) {
            MainPresenter.skipRescanningLibrary()
        } else if (DirectoryInitialization.areDolphinDirectoriesReady()) {
            // If the currently selected platform tab changed, save it to disk
            NativeConfig.save(NativeConfig.LAYER_BASE)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_game_grid, menu)
        this.menu = menu
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        AfterDirectoryInitializationRunner().runWithLifecycle(this) {
            if (WiiUtils.isSystemMenuInstalled()) {
                val resId =
                    if (WiiUtils.isSystemMenuvWii()) R.string.grid_menu_load_vwii_system_menu_installed else R.string.grid_menu_load_wii_system_menu_installed

                // If this callback ends up running after another call to onCreateOptionsMenu,
                // we need to use the new Menu passed to the latest call of onCreateOptionsMenu.
                // Therefore, we use a field here instead of the onPrepareOptionsMenu argument.
                this.menu.findItem(R.id.menu_load_wii_system_menu).title =
                    getString(resId, WiiUtils.getSystemMenuVersion())
            }
        }
        return super.onPrepareOptionsMenu(menu)
    }

    /**
     * MainView
     */
    override fun setVersionString(version: String) {
        binding.toolbarMain.subtitle = version
    }

    override fun launchSettingsActivity(menuTag: MenuTag?) {
        SettingsActivity.launch(this, menuTag)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PermissionsHandler.REQUEST_CODE_WRITE_PERMISSION) {
            if (grantResults[0] == PackageManager.PERMISSION_DENIED) {
                PermissionsHandler.setWritePermissionDenied()
            }

            DirectoryInitialization.start(this)
            AfterDirectoryInitializationRunner()
                .runWithLifecycle(this) { setPlatformTabsAndStartGameFileCacheService() }
        }
    }

    /**
     * Called by the framework whenever any actionbar/toolbar icon is clicked.
     *
     * @param item The icon that was clicked on.
     * @return True if the event was handled, false to bubble it up to the OS.
     */
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return presenter.handleOptionSelection(item.itemId, this)
    }

    /**
     * Called when the user requests a refresh by swiping down.
     */
    override fun onRefresh() {
        setRefreshing(true)
        GameFileCacheManager.startRescan()
    }

    /**
     * Shows or hides the loading indicator.
     */
    override fun setRefreshing(refreshing: Boolean) =
        forEachPlatformGamesView { view: PlatformGamesView -> view.setRefreshing(refreshing) }

    /**
     * To be called when the game file cache is updated.
     */
    override fun showGames() =
        forEachPlatformGamesView { obj: PlatformGamesView -> obj.showGames() }

    override fun reloadGrid() {
        forEachPlatformGamesView { obj: PlatformGamesView -> obj.refetchMetadata() }
    }

    override fun showGridOptions() =
        GridOptionDialogFragment().show(supportFragmentManager, "gridOptions")

    private fun forEachPlatformGamesView(action: (PlatformGamesView) -> Unit) {
        for (platformTab in PlatformTab.values()) {
            val fragment = getPlatformGamesView(platformTab)
            if (fragment != null) {
                action(fragment)
            }
        }
    }

    private fun getPlatformGamesView(platformTab: PlatformTab): PlatformGamesView? {
        val fragmentTag =
            "android:switcher:" + binding.pagerPlatforms.id + ":" + platformTab.toInt()
        return supportFragmentManager.findFragmentByTag(fragmentTag) as PlatformGamesView?
    }

    // Don't call this before DirectoryInitialization completes.
    private fun setPlatformTabsAndStartGameFileCacheService() {
        val platformPagerAdapter = PlatformPagerAdapter(
            supportFragmentManager, this
        )
        binding.pagerPlatforms.adapter = platformPagerAdapter
        binding.pagerPlatforms.offscreenPageLimit = platformPagerAdapter.count
        binding.tabsPlatforms.setupWithViewPager(binding.pagerPlatforms)
        binding.tabsPlatforms.addOnTabSelectedListener(
            object : TabLayout.ViewPagerOnTabSelectedListener(binding.pagerPlatforms) {
                override fun onTabSelected(tab: TabLayout.Tab) {
                    super.onTabSelected(tab)
                    IntSetting.MAIN_LAST_PLATFORM_TAB.setInt(
                        NativeConfig.LAYER_BASE,
                        tab.position
                    )
                }
            })

        for (i in PlatformPagerAdapter.TAB_ICONS.indices) {
            binding.tabsPlatforms.getTabAt(i)?.setIcon(PlatformPagerAdapter.TAB_ICONS[i])
        }

        binding.pagerPlatforms.currentItem = IntSetting.MAIN_LAST_PLATFORM_TAB.int

        showGames()
        GameFileCacheManager.startLoad()
    }

    override fun setTheme(themeId: Int) {
        super.setTheme(themeId)
        this.themeId = themeId
    }

    private fun setInsets() =
        ViewCompat.setOnApplyWindowInsetsListener(binding.appbarMain) { _: View?, windowInsets: WindowInsetsCompat ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())

            InsetsHelper.insetAppBar(insets, binding.appbarMain)

            val mlpFab = binding.buttonAddDirectory.layoutParams as MarginLayoutParams
            val fabPadding = resources.getDimensionPixelSize(R.dimen.spacing_large)
            mlpFab.leftMargin = insets.left + fabPadding
            mlpFab.bottomMargin = insets.bottom + fabPadding
            mlpFab.rightMargin = insets.right + fabPadding
            binding.buttonAddDirectory.layoutParams = mlpFab

            binding.pagerPlatforms.setPadding(insets.left, 0, insets.right, 0)

            InsetsHelper.applyNavbarWorkaround(insets.bottom, binding.workaroundView)

            windowInsets
        }
}
