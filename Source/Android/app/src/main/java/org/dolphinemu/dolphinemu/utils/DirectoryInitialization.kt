/*
 * Copyright 2014 Dolphin Emulator Project
 * SPDX-License-Identifier: GPL-2.0-or-later
 */

package org.dolphinemu.dolphinemu.utils

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.preference.PreferenceManager
import org.dolphinemu.dolphinemu.NativeLibrary
import org.dolphinemu.dolphinemu.R
import org.dolphinemu.dolphinemu.features.settings.model.BooleanSetting
import org.dolphinemu.dolphinemu.features.settings.model.IntSetting
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * A class that spawns its own thread in order perform initialization.
 *
 * The initialization steps include:
 * - Extracting the Sys directory from the APK so it can be accessed using regular file APIs
 * - Letting the native code know where on external storage it should place the User directory
 * - Running the native code's init steps (which include things like populating the User directory)
 */
object DirectoryInitialization {
    const val PREF_USER_DIR_MODE = "userDirectoryMode"
    const val USER_DIR_MODE_SCOPED = 0
    const val USER_DIR_MODE_INTERNAL = 1
    const val USER_DIR_MODE_SDCARD = 2

    private const val PREF_MIGRATION_OFFERED = "userDataMigrationOffered"
    private const val PREF_PREVIOUS_USER_DIR_MODE = "previousUserDirMode"

    private val directoryState = MutableLiveData(DirectoryInitializationState.NOT_YET_INITIALIZED)

    @Volatile
    private var areDirectoriesAvailable = false

    private lateinit var userPath: String
    private lateinit var driverPath: String
    private var usingLegacyUserDirectory = false
    private var userDirectoryWasPreExisting = false

    enum class DirectoryInitializationState {
        NOT_YET_INITIALIZED, INITIALIZING, DOLPHIN_DIRECTORIES_INITIALIZED
    }

    @JvmStatic
    fun start(context: Context) {
        if (directoryState.value != DirectoryInitializationState.NOT_YET_INITIALIZED) {
            return
        }

        directoryState.value = DirectoryInitializationState.INITIALIZING

        // Can take a few seconds to run, so don't block UI thread.
        thread { init(context) }
    }

    private fun init(context: Context) {
        if (directoryState.value == DirectoryInitializationState.DOLPHIN_DIRECTORIES_INITIALIZED) {
            return
        }

        if (!setDolphinUserDirectory(context)) {
            ContextCompat.getMainExecutor(context).execute {
                Toast.makeText(context, R.string.external_storage_not_mounted, Toast.LENGTH_LONG)
                    .show()
                exitProcess(1)
            }
            return
        }

        // Record before Initialize() creates default folders, so migration can distinguish
        // a pre-existing user directory from one Dolphin just scaffolded fresh
        userDirectoryWasPreExisting = File(userPath).let { it.exists() && it.listFiles()?.isNotEmpty() == true }

        extractSysDirectory(context)
        NativeLibrary.Initialize()

        areDirectoriesAvailable = true

        checkThemeSettings(context)

        directoryState.postValue(DirectoryInitializationState.DOLPHIN_DIRECTORIES_INITIALIZED)
    }

    private fun getLegacyUserDirectoryPath(): File? {
        val externalPath = Environment.getExternalStorageDirectory() ?: return null
        return File(externalPath, "dolphin-emu")
    }

    @JvmStatic
    fun hasSdCard(context: Context): Boolean = getSdCardRoot(context) != null

    @JvmStatic
    fun getStorageMode(context: Context): Int {
        return PreferenceManager.getDefaultSharedPreferences(context)
            .getInt(PREF_USER_DIR_MODE, USER_DIR_MODE_SCOPED)
    }

    @JvmStatic
    fun setStorageMode(context: Context, mode: Int) {
        val current = getStorageMode(context)
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putInt(PREF_USER_DIR_MODE, mode)
            .putInt(PREF_PREVIOUS_USER_DIR_MODE, current)
            .remove(PREF_MIGRATION_OFFERED)
            .apply()
    }

    private fun getPathForMode(context: Context, mode: Int): File? = when (mode) {
        USER_DIR_MODE_INTERNAL -> getLegacyUserDirectoryPath()
        USER_DIR_MODE_SDCARD   -> getSdCardRoot(context)?.let { File(it, "dolphin-emu") }
        else                   -> context.getExternalFilesDir(null)
    }

    enum class MigrationState { NONE, CLEAN, CONFLICT }

    @JvmStatic
    fun getMigrationState(context: Context): MigrationState {
        if (!areDirectoriesAvailable) return MigrationState.NONE

        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (prefs.getBoolean(PREF_MIGRATION_OFFERED, false)) return MigrationState.NONE

        val prevMode = prefs.getInt(PREF_PREVIOUS_USER_DIR_MODE, -1)
        if (prevMode == -1) return MigrationState.NONE

        val currentMode = getStorageMode(context)
        if (prevMode == currentMode) return MigrationState.NONE

        val sourceDir = getPathForMode(context, prevMode) ?: return MigrationState.NONE
        if (!sourceDir.exists() || sourceDir.listFiles()?.isEmpty() != false) return MigrationState.NONE

        val destDir = File(userPath)
        // If paths are the same the app fell back to scoped due to missing permission — not a real migration
        if (sourceDir.canonicalPath == destDir.canonicalPath) return MigrationState.NONE

        // Use the pre-init snapshot so Dolphin's own folder scaffolding doesn't look like a conflict
        return if (userDirectoryWasPreExisting) MigrationState.CONFLICT else MigrationState.CLEAN
    }

    @JvmStatic
    fun markMigrationOffered(context: Context) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(PREF_MIGRATION_OFFERED, true).apply()
    }

    @JvmStatic
    fun copyUserDataToNewLocation(
        context: Context,
        onProgress: (copied: Int, total: Int) -> Unit,
        onComplete: (Boolean) -> Unit
    ) {
        if (!areDirectoriesAvailable) { onComplete(false); return }

        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val prevMode = prefs.getInt(PREF_PREVIOUS_USER_DIR_MODE, -1)
        val source = getPathForMode(context, prevMode) ?: run { onComplete(false); return }
        val dest = File(userPath)
        if (source.absolutePath == dest.absolutePath) { onComplete(true); return }

        thread {
            try {
                // Clear destination first so no stale files remain after copy
                val clearFailed = dest.listFiles()?.any { !it.deleteRecursively() } == true
                if (clearFailed) {
                    Log.error("[DirectoryInitialization] Migration aborted — could not clear destination")
                    onComplete(false)
                    return@thread
                }

                val files = source.walk().filter { it.isFile }.toList()
                val total = files.size
                onProgress(0, total)

                files.forEachIndexed { index, srcFile ->
                    val destFile = dest.resolve(srcFile.relativeTo(source))
                    destFile.parentFile?.mkdirs()
                    srcFile.copyTo(destFile, overwrite = true)
                    onProgress(index + 1, total)
                }

                if (verifyMigration(source, dest)) {
                    // For Internal/SD Card we own the dolphin-emu folder entirely — delete it.
                    // For Scoped, Android manages the directory itself so only empty it.
                    if (prevMode == USER_DIR_MODE_INTERNAL || prevMode == USER_DIR_MODE_SDCARD) {
                        source.deleteRecursively()
                    } else {
                        source.listFiles()?.forEach { it.deleteRecursively() }
                    }
                    onComplete(true)
                } else {
                    Log.error("[DirectoryInitialization] Migration verification failed — source kept")
                    onComplete(false)
                }
            } catch (e: Exception) {
                Log.error("[DirectoryInitialization] Migration failed: ${e.message}")
                onComplete(false)
            }
        }
    }

    private fun verifyMigration(source: File, dest: File): Boolean {
        source.walk().filter { it.isFile }.forEach { srcFile ->
            val destFile = dest.resolve(srcFile.relativeTo(source))
            if (!destFile.exists() || destFile.length() != srcFile.length()) return false
        }
        return true
    }

    private fun hasLegacyStorageAccess(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            PermissionsHandler.hasWriteAccess(context)
        }
    }

    private fun getSdCardRoot(context: Context): File? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val sm = context.getSystemService(StorageManager::class.java)
            return sm.storageVolumes
                .firstOrNull { it.isRemovable && it.state == Environment.MEDIA_MOUNTED }
                ?.directory
        }
        // Pre-R: strip Android/data/<pkg>/files (4 levels) from the scoped SD card path
        val dirs = ContextCompat.getExternalFilesDirs(context, null)
        var sdScoped = dirs.getOrNull(1) ?: return null
        repeat(4) { sdScoped = sdScoped.parentFile ?: return null }
        return sdScoped
    }

    @JvmStatic
    fun getUserDirectoryPath(context: Context?): File? {
        if (context == null) return null
        if (Environment.getExternalStorageState() != Environment.MEDIA_MOUNTED) return null

        return when (getStorageMode(context)) {
            USER_DIR_MODE_INTERNAL -> {
                if (hasLegacyStorageAccess(context)) {
                    usingLegacyUserDirectory = true
                    getLegacyUserDirectoryPath()
                } else {
                    usingLegacyUserDirectory = false
                    context.getExternalFilesDir(null)
                }
            }
            USER_DIR_MODE_SDCARD -> {
                val sdRoot = getSdCardRoot(context)
                if (sdRoot != null && hasLegacyStorageAccess(context)) {
                    usingLegacyUserDirectory = true
                    File(sdRoot, "dolphin-emu")
                } else {
                    usingLegacyUserDirectory = false
                    context.getExternalFilesDir(null)
                }
            }
            else -> { // USER_DIR_MODE_SCOPED
                usingLegacyUserDirectory = false
                context.getExternalFilesDir(null)
            }
        }
    }

    private fun setDolphinUserDirectory(context: Context): Boolean {
        val path = getUserDirectoryPath(context) ?: return false

        userPath = path.absolutePath

        Log.debug("[DirectoryInitialization] User Dir: $userPath")
        NativeLibrary.SetUserDirectory(userPath)

        var cacheDir = context.externalCacheDir
        if (cacheDir == null) {
            // In some custom ROMs getExternalCacheDir might return null for some reason. If that
            // is the case, fallback to getCacheDir which seems to work just fine.
            cacheDir = context.cacheDir ?: return false
        }

        Log.debug("[DirectoryInitialization] Cache Dir: ${cacheDir.path}")
        NativeLibrary.SetCacheDirectory(cacheDir.path)

        return true
    }

    private fun extractSysDirectory(context: Context) {
        val sysDirectory = File(context.filesDir, "Sys")

        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val revision = NativeLibrary.GetGitRevision()
        if (preferences.getString("sysDirectoryVersion", "") != revision) {
            // There is no extracted Sys directory, or there is a Sys directory from another
            // version of Dolphin that might contain outdated files. Let's (re-)extract Sys.
            deleteDirectoryRecursively(sysDirectory)
            copyAssetFolder("Sys", sysDirectory, context)

            preferences.edit {
                putString("sysDirectoryVersion", revision)
            }
        }

        // Let the native code know where the Sys directory is.
        SetSysDirectory(sysDirectory.path)

        val driverDirectory = File(context.filesDir, "GPUDrivers")
        driverDirectory.mkdirs()
        val driverExtractedDir = File(driverDirectory, "Extracted")
        driverExtractedDir.mkdirs()
        val driverTmpDir = File(driverDirectory, "Tmp")
        driverTmpDir.mkdirs()
        val driverFileRedirectDir = File(driverDirectory, "FileRedirect")
        driverFileRedirectDir.mkdirs()

        SetGpuDriverDirectories(driverDirectory.path, context.applicationInfo.nativeLibraryDir)
        driverPath = driverExtractedDir.absolutePath
    }

    private fun deleteDirectoryRecursively(file: File) {
        if (file.isDirectory) {
            val files = file.listFiles() ?: return
            for (child in files) {
                deleteDirectoryRecursively(child)
            }
        }

        if (!file.delete()) {
            Log.error("[DirectoryInitialization] Failed to delete ${file.absolutePath}")
        }
    }

    @JvmStatic
    fun shouldStart(context: Context): Boolean {
        return getDolphinDirectoriesState().value == DirectoryInitializationState.NOT_YET_INITIALIZED && !isWaitingForWriteAccess(
            context
        )
    }

    @JvmStatic
    fun areDolphinDirectoriesReady(): Boolean {
        return directoryState.value == DirectoryInitializationState.DOLPHIN_DIRECTORIES_INITIALIZED
    }

    @JvmStatic
    fun getDolphinDirectoriesState(): LiveData<DirectoryInitializationState> {
        return directoryState
    }

    @JvmStatic
    fun getUserDirectory(): String {
        if (!areDirectoriesAvailable) {
            throw IllegalStateException(
                "DirectoryInitialization must run before accessing the user directory!"
            )
        }

        return userPath
    }

    @JvmStatic
    fun getExtractedDriverDirectory(): String {
        if (!areDirectoriesAvailable) {
            throw IllegalStateException(
                "DirectoryInitialization must run before accessing the driver directory!"
            )
        }

        return driverPath
    }

    @JvmStatic
    fun getGameListCache(): File {
        return File(NativeLibrary.GetCacheDirectory(), "gamelist.cache")
    }

    private fun copyAsset(asset: String, output: File, context: Context) {
        Log.verbose("[DirectoryInitialization] Copying File $asset to $output")

        try {
            context.assets.open(asset).use { input ->
                FileOutputStream(output).use { outputStream ->
                    copyFile(input, outputStream)
                }
            }
        } catch (e: IOException) {
            Log.error("[DirectoryInitialization] Failed to copy asset file: $asset${e.message}")
        }
    }

    private fun copyAssetFolder(assetFolder: String, outputFolder: File, context: Context) {
        Log.verbose("[DirectoryInitialization] Copying Folder $assetFolder to $outputFolder")

        try {
            val assetList = context.assets.list(assetFolder) ?: return

            var createdFolder = false
            for (file in assetList) {
                if (!createdFolder) {
                    if (!outputFolder.mkdir()) {
                        Log.error(
                            "[DirectoryInitialization] Failed to create folder " + outputFolder.absolutePath
                        )
                    }
                    createdFolder = true
                }

                val childAsset = assetFolder + File.separator + file
                val childOutput = File(outputFolder, file)
                copyAssetFolder(childAsset, childOutput, context)
                copyAsset(childAsset, childOutput, context)
            }
        } catch (e: IOException) {
            Log.error(
                "[DirectoryInitialization] Failed to copy asset folder: $assetFolder${e.message}"
            )
        }
    }

    @Throws(IOException::class)
    private fun copyFile(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(1024)
        var read: Int

        while (input.read(buffer).also { read = it } != -1) {
            output.write(buffer, 0, read)
        }
    }

    @JvmStatic
    fun preferOldFolderPicker(context: Context): Boolean {
        // As of January 2021, ACTION_OPEN_DOCUMENT_TREE seems to be broken on the Nvidia Shield TV
        // (the activity can't be navigated correctly with a gamepad). We can use the old folder
        // picker for the time being - Android 11 hasn't been released for this device. We have an
        // explicit check for Android 11 below in hopes that Nvidia will fix this before releasing
        // Android 11.
        //
        // No Android TV device other than the Nvidia Shield TV is known to have an implementation
        // of ACTION_OPEN_DOCUMENT or ACTION_OPEN_DOCUMENT_TREE that even launches, but
        // "fortunately", no Android TV device other than the Shield TV is known to be able to run
        // Dolphin (either due to the 64-bit requirement or due to the GLES 3.0 requirement), so
        // we can ignore this problem.
        //
        // All phones which are running a compatible version of Android support ACTION_OPEN_DOCUMENT
        // and ACTION_OPEN_DOCUMENT_TREE, as this is required by the mobile Android CTS (unlike
        // Android TV).

        return Build.VERSION.SDK_INT < Build.VERSION_CODES.R && PermissionsHandler.isExternalStorageLegacy() && TvUtil.isLeanback(
            context
        )
    }

    @JvmStatic
    fun isUsingLegacyUserDirectory(): Boolean {
        return usingLegacyUserDirectory
    }

    @JvmStatic
    fun isWaitingForWriteAccess(context: Context): Boolean {
        // This first check is only for performance, not correctness
        if (directoryState.value != DirectoryInitializationState.NOT_YET_INITIALIZED) {
            return false
        }

        val mode = getStorageMode(context)
        if (mode == USER_DIR_MODE_SCOPED) return false

        // On Android 11+, MANAGE_EXTERNAL_STORAGE is resolved at launch; we don't block here.
        // On older Android, block until WRITE_EXTERNAL_STORAGE is granted.
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.R && !PermissionsHandler.hasWriteAccess(context)
    }

    private fun checkThemeSettings(context: Context) {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        if (IntSetting.MAIN_INTERFACE_THEME.int != preferences.getInt(
                ThemeHelper.CURRENT_THEME, ThemeHelper.DEFAULT
            )
        ) {
            preferences.edit {
                putInt(ThemeHelper.CURRENT_THEME, IntSetting.MAIN_INTERFACE_THEME.int)
            }
        }

        if (IntSetting.MAIN_INTERFACE_THEME_MODE.int != preferences.getInt(
                ThemeHelper.CURRENT_THEME_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            )
        ) {
            preferences.edit {
                putInt(ThemeHelper.CURRENT_THEME_MODE, IntSetting.MAIN_INTERFACE_THEME_MODE.int)
            }
        }

        if (BooleanSetting.MAIN_USE_BLACK_BACKGROUNDS.boolean != preferences.getBoolean(
                ThemeHelper.USE_BLACK_BACKGROUNDS, false
            )
        ) {
            preferences.edit {
                putBoolean(
                    ThemeHelper.USE_BLACK_BACKGROUNDS,
                    BooleanSetting.MAIN_USE_BLACK_BACKGROUNDS.boolean
                )
            }
        }
    }

    @Suppress("FunctionName")
    @JvmStatic
    private external fun SetSysDirectory(path: String)

    @Suppress("FunctionName")
    @JvmStatic
    private external fun SetGpuDriverDirectories(path: String, libPath: String)
}
