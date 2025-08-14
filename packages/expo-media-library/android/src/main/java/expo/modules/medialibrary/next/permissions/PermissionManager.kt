package expo.modules.medialibrary.next.permissions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Binder
import android.os.Build
import expo.modules.interfaces.permissions.Permissions.askForPermissionsWithPermissionsManager
import expo.modules.interfaces.permissions.Permissions.getPermissionsWithPermissionsManager
import expo.modules.kotlin.AppContext
import expo.modules.kotlin.Promise
import expo.modules.kotlin.activityresult.AppContextActivityResultLauncher
import expo.modules.kotlin.exception.Exceptions
import expo.modules.medialibrary.ERROR_NO_PERMISSIONS_MESSAGE
import expo.modules.medialibrary.ERROR_NO_WRITE_PERMISSION_MESSAGE
import expo.modules.medialibrary.ERROR_USER_DID_NOT_GRANT_WRITE_PERMISSIONS_MESSAGE
import android.Manifest.permission.ACCESS_MEDIA_LOCATION
import android.Manifest.permission.READ_EXTERNAL_STORAGE
import android.Manifest.permission.READ_MEDIA_AUDIO
import android.Manifest.permission.READ_MEDIA_IMAGES
import android.Manifest.permission.READ_MEDIA_VIDEO
import android.Manifest.permission.WRITE_EXTERNAL_STORAGE
import android.annotation.SuppressLint
import androidx.annotation.RequiresApi
import expo.modules.medialibrary.GranularPermission
import expo.modules.medialibrary.MediaLibraryPermissionPromiseWrapper
import expo.modules.medialibrary.MediaLibraryUtils
import expo.modules.medialibrary.PermissionsException
import expo.modules.medialibrary.R
import expo.modules.medialibrary.contracts.DeleteContractInput
import expo.modules.medialibrary.contracts.WriteContractInput
import java.lang.ref.WeakReference

class PermissionManager(val appContext: AppContext) {
  private val context: Context
    get() = appContext.reactContext ?: throw Exceptions.ReactContextLost()

  lateinit var deleteLauncher: AppContextActivityResultLauncher<DeleteContractInput, Boolean>
  lateinit var writeLauncher: AppContextActivityResultLauncher<WriteContractInput, Boolean>

  private val isExpoGo by lazy {
    context.resources.getString(R.string.is_expo_go).toBoolean()
  }

  fun requireSystemPermissions(isWritePermissionRequired: Boolean) {
    val missingPermissionsCondition =
      if (isWritePermissionRequired) isMissingWritePermission else isMissingPermissions
    if (missingPermissionsCondition) {
      val missingPermissionsMessage =
        if (isWritePermissionRequired) ERROR_NO_WRITE_PERMISSION_MESSAGE else ERROR_NO_PERMISSIONS_MESSAGE
      throw PermissionsException(missingPermissionsMessage)
    }
  }

  suspend fun requestMediaLibraryActionPermission(
    uris: Array<Uri>,
    needsDeletePermission: Boolean = false
  ) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
      return
    }

    val urisWithoutPermission = uris.filterNot { uri ->
      hasWritePermissionForUri(uri)
    }

    if (urisWithoutPermission.isEmpty()) {
      return
    }

    val granted = if (needsDeletePermission) {
      deleteLauncher.launch(DeleteContractInput(uris = urisWithoutPermission))
    } else {
      writeLauncher.launch(WriteContractInput(uris = urisWithoutPermission))
    }

    if (!granted) {
      throw PermissionsException(ERROR_USER_DID_NOT_GRANT_WRITE_PERMISSIONS_MESSAGE)
    }
  }

  fun requestPermissions(writeOnly: Boolean, permissions: List<GranularPermission>?, promise: Promise) {
    val granularPermissions = permissions ?: allowedPermissionsList
    maybeThrowIfExpoGo(granularPermissions)
    askForPermissionsWithPermissionsManager(
      appContext.permissions,
      MediaLibraryPermissionPromiseWrapper(granularPermissions, promise, WeakReference(context)),
      *getManifestPermissions(writeOnly, granularPermissions)
    )
  }

  @SuppressLint("InlinedApi")
  private fun getManifestPermissions(
    writeOnly: Boolean,
    granularPermissions: List<GranularPermission>
  ): Array<String> {
    // ACCESS_MEDIA_LOCATION should not be requested if it's absent in android-manifest
    // If only audio permission is requested, we don't need to request media location permissions
    val shouldAddMediaLocationAccess =
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
        MediaLibraryUtils.hasManifestPermission(context, ACCESS_MEDIA_LOCATION) &&
        !(
          Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            granularPermissions.count() == 1 && granularPermissions.contains(
              GranularPermission.AUDIO
            )
          )

    val shouldAddWriteExternalStorage =
      Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU &&
        MediaLibraryUtils.hasManifestPermission(context, WRITE_EXTERNAL_STORAGE)

    val shouldAddGranularPermissions = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    val shouldIncludeGranular = shouldAddGranularPermissions && !writeOnly

    return listOfNotNull(
      WRITE_EXTERNAL_STORAGE.takeIf { shouldAddWriteExternalStorage },
      READ_EXTERNAL_STORAGE.takeIf { !writeOnly && !shouldAddGranularPermissions },
      ACCESS_MEDIA_LOCATION.takeIf { shouldAddMediaLocationAccess },
      *getGranularPermissions(shouldIncludeGranular, granularPermissions)
    ).toTypedArray()
  }

  @SuppressLint("InlinedApi")
  private fun getGranularPermissions(
    shouldIncludeGranular: Boolean,
    granularPermissions: List<GranularPermission>
  ): Array<String> {
    if (shouldIncludeGranular) {
      assertGranularPermissionIntegrity(context, granularPermissions)
      return listOfNotNull(
        READ_MEDIA_IMAGES.takeIf { granularPermissions.contains(GranularPermission.PHOTO) },
        READ_MEDIA_VIDEO.takeIf { granularPermissions.contains(GranularPermission.VIDEO) },
        READ_MEDIA_AUDIO.takeIf { granularPermissions.contains(GranularPermission.AUDIO) }
      ).toTypedArray()
    }
    return arrayOf()
  }

  @RequiresApi(Build.VERSION_CODES.TIRAMISU)
  private fun assertGranularPermissionIntegrity(context: Context, granularPermissions: List<GranularPermission>) {
    for (permission in granularPermissions) {
      if (!MediaLibraryUtils.hasManifestPermission(context, permission.toManifestPermission())) {
        throw PermissionsException("You have requested the $permission permission, but it is not declared in AndroidManifest. Update expo-media-library config plugin to include the permission before requesting it.")
      }
    }
  }

  fun getPermissions(writeOnly: Boolean, permissions: List<GranularPermission>?, promise: Promise) {
    val granularPermissions = permissions ?: allowedPermissionsList
    maybeThrowIfExpoGo(granularPermissions)
    getPermissionsWithPermissionsManager(
      appContext.permissions,
      MediaLibraryPermissionPromiseWrapper(granularPermissions, promise, WeakReference(context)),
      *getManifestPermissions(writeOnly, granularPermissions)
    )
  }

  private fun getManifestDeclaredPermissions(context: Context, granularPermissions: List<GranularPermission>): List<GranularPermission> {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      return granularPermissions.filter { MediaLibraryUtils.hasManifestPermission(context, it.toManifestPermission()) }
    }
    return granularPermissions
  }

  private val allowedPermissionsList by lazy {
    if (isExpoGo) {
      listOf(GranularPermission.AUDIO)
    } else {
      getManifestDeclaredPermissions(context, listOf(GranularPermission.PHOTO, GranularPermission.VIDEO, GranularPermission.AUDIO))
    }
  }

  private fun maybeThrowIfExpoGo(permissions: List<GranularPermission>) {
    if (isExpoGo) {
      if (permissions.contains(GranularPermission.PHOTO) || permissions.contains(GranularPermission.VIDEO)) {
        throw PermissionsException("Due to changes in Androids permission requirements, Expo Go can no longer provide full access to the media library. To test the full functionality of this module, you can create a development build")
      }
    }
  }

  private fun hasReadPermissions(): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      val permissions = allowedPermissionsList.map { it.toManifestPermission() }.toMutableList()
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        permissions.add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
      }

      // Android will only return albums that the user allowed access to.
      permissions.map { permission ->
        appContext.permissions
          ?.hasGrantedPermissions(permission) ?: false
      }.any { it }.not()
    } else {
      val permissions = arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
      appContext.permissions
        ?.hasGrantedPermissions(*permissions)
        ?.not() ?: false
    }
  }

  private fun hasWritePermissions() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    false
  } else {
    appContext.permissions
      ?.hasGrantedPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE)
      ?.not() ?: false
  }

  private val isMissingPermissions: Boolean
    get() = hasReadPermissions()

  private val isMissingWritePermission: Boolean
    get() = hasWritePermissions()

  private fun hasWritePermissionForUri(uri: Uri): Boolean {
    return context.checkUriPermission(
      uri,
      Binder.getCallingPid(),
      Binder.getCallingUid(),
      Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    ) == PackageManager.PERMISSION_GRANTED
  }
}
