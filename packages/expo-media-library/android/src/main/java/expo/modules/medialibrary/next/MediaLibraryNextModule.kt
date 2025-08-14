package expo.modules.medialibrary.next

import android.net.Uri
import expo.modules.kotlin.Promise
import expo.modules.kotlin.apifeatures.EitherType
import expo.modules.kotlin.exception.Exceptions
import expo.modules.kotlin.functions.Coroutine
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import expo.modules.kotlin.types.Either
import expo.modules.kotlin.types.toKClass
import expo.modules.medialibrary.GranularPermission
import expo.modules.medialibrary.contracts.DeleteContract
import expo.modules.medialibrary.contracts.WriteContract
import expo.modules.medialibrary.next.objects.Album
import expo.modules.medialibrary.next.objects.Asset
import expo.modules.medialibrary.next.objects.factories.AlbumFactory
import expo.modules.medialibrary.next.objects.factories.AssetFactory
import expo.modules.medialibrary.next.permissions.PermissionManager

class MediaLibraryNextModule : Module() {
  private val context
    get() = appContext.reactContext ?: throw Exceptions.ReactContextLost()
  private val permissionManager by lazy {
    PermissionManager(appContext)
  }

  override fun definition() = ModuleDefinition {
    Name("ExpoMediaLibraryNext")

    Class(Asset::class) {
      Constructor { contentUri: Uri ->
        Asset(contentUri, context)
      }

      Property("contentUri") { self: Asset ->
        permissionManager.requireSystemPermissions(false)
        self.contentUri
      }

      AsyncFunction("getCreationTime") Coroutine { self: Asset ->
        permissionManager.requireSystemPermissions(false)
        self.getCreationTime()
      }

      AsyncFunction("getDuration") Coroutine { self: Asset ->
        permissionManager.requireSystemPermissions(false)
        self.getDuration()
      }

      AsyncFunction("getFilename") Coroutine { self: Asset ->
        permissionManager.requireSystemPermissions(false)
        self.getFilename()
      }

      AsyncFunction("getHeight") Coroutine { self: Asset ->
        permissionManager.requireSystemPermissions(false)
        self.getHeight()
      }

      AsyncFunction("getMediaType") Coroutine { self: Asset ->
        permissionManager.requireSystemPermissions(false)
        self.getMediaType()
      }

      AsyncFunction("getModificationTime") Coroutine { self: Asset ->
        permissionManager.requireSystemPermissions(false)
        self.getModificationTime()
      }

      AsyncFunction("getUri") Coroutine { self: Asset ->
        permissionManager.requireSystemPermissions(false)
        self.getUri()
      }

      AsyncFunction("getWidth") Coroutine { self: Asset ->
        permissionManager.requireSystemPermissions(false)
        self.getWidth()
      }

      AsyncFunction("delete") Coroutine { self: Asset ->
        permissionManager.requireSystemPermissions(true)
        permissionManager.requestMediaLibraryActionPermission(arrayOf(self.contentUri))
        self.delete()
      }
    }

    Class(Album::class) {
      Constructor { id: Long ->
        Album(id, context)
      }

      Property("id") { self: Album ->
        permissionManager.requireSystemPermissions(false)
        self.id
      }

      AsyncFunction("getTitle") Coroutine { self: Album ->
        permissionManager.requireSystemPermissions(false)
        self.getTitle()
      }

      AsyncFunction("add") Coroutine { self: Album, asset: Asset ->
        permissionManager.requireSystemPermissions(true)
        permissionManager.requestMediaLibraryActionPermission(arrayOf(asset.contentUri))
        self.add(asset)
      }

      AsyncFunction("getAssets") Coroutine { self: Album ->
        permissionManager.requireSystemPermissions(false)
        self.assets
      }

      AsyncFunction("delete") Coroutine { self: Album ->
        permissionManager.requireSystemPermissions(true)
        self.delete()
      }
    }

    AsyncFunction("createAsset") Coroutine { filePath: String, album: Album? ->
      permissionManager.requireSystemPermissions(true)
      val assetFactory = AssetFactory(context)
      return@Coroutine assetFactory.create(filePath, album?.getRelativePath())
    }

    @OptIn(EitherType::class)
    AsyncFunction("createAlbum") Coroutine { name: String, assetsRefs: Either<List<Asset>, List<String>>, move: Boolean ->
      permissionManager.requireSystemPermissions(true)
      val albumFactory = AlbumFactory(context)
      return@Coroutine if (assetsRefs.`is`(toKClass<List<Asset>>())) {
        assetsRefs.get(toKClass<List<Asset>>()).let {
          return@let albumFactory.createFromAssets(name, it, move)
        }
      } else if (assetsRefs.`is`(toKClass<List<String>>())) {
        assetsRefs.get(toKClass<List<String>>()).let {
          return@let albumFactory.createFromFilePaths(name, it, AssetFactory(context))
        }
      } else {
        null
      }
    }

    AsyncFunction("deleteManyAlbums") Coroutine { albums: List<Album> ->
      permissionManager.requireSystemPermissions(true)
      albums.forEach { album -> album.delete() }
    }

    AsyncFunction("deleteManyAssets") Coroutine { assets: List<Asset> ->
      permissionManager.requireSystemPermissions(true)
      val uris = assets.map { it.contentUri }.toTypedArray()
      permissionManager.requestMediaLibraryActionPermission(uris, needsDeletePermission = true)
      assets.forEach { asset -> asset.delete() }
    }

    AsyncFunction("requestPermissionsAsync") { writeOnly: Boolean, permissions: List<GranularPermission>?, promise: Promise ->
      permissionManager.requestPermissions(writeOnly, permissions, promise)
    }

    AsyncFunction("getPermissionsAsync") { writeOnly: Boolean, permissions: List<GranularPermission>?, promise: Promise ->
      permissionManager.getPermissions(writeOnly, permissions, promise)
    }

    RegisterActivityContracts {
      permissionManager.deleteLauncher =
        registerForActivityResult(DeleteContract(this@MediaLibraryNextModule))
      permissionManager.writeLauncher =
        registerForActivityResult(WriteContract(this@MediaLibraryNextModule))
    }
  }
}
