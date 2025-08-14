import Foundation
import Photos
import UniformTypeIdentifiers
import ExpoModulesCore

class Asset: SharedObject {
  let id: String
  let context: AppContext
  var phAsset: PHAsset?

  init(id: String, context: AppContext) {
    self.id = id
    self.context = context
  }

  func getHeight() async throws -> Int {
    guard let height = try await property({ phAsset?.pixelHeight }) else {
      throw FailedToGetPropertyException("height")
    }
    return height
  }

  func getWidth() async throws -> Int {
    guard let width = try await property({ phAsset?.pixelWidth }) else {
      throw FailedToGetPropertyException("width")
    }
    return width
  }

  func getDuration() async throws -> Double? {
    guard let duration = try await property({ phAsset?.duration }) else {
      throw FailedToGetPropertyException("duration")
    }
    return duration != 0 ? duration : nil
  }

  func getFilename() async throws -> String {
    guard let filename = try await property({ phAsset?.value(forKey: "filename") as? String }) else {
      throw FailedToGetPropertyException("filename")
    }
    return filename
  }

  func getCreationTime() async throws -> Int? {
    if let date = try await property({ phAsset?.creationDate }) {
      return Int(date.timeIntervalSince1970)
    }
    return nil
  }

  func getModificationTime() async throws -> Int? {
    if let date = try await property({ phAsset?.modificationDate }) {
      return Int(date.timeIntervalSince1970)
    }
    return nil
  }

  func getMediaType() async throws -> Int {
    guard let mediaType = try await property({ phAsset?.mediaType.rawValue }) else {
      throw FailedToGetPropertyException("mediaType")
    }
    return mediaType
  }

  func getUri() async throws -> String {
    let phAsset = try await guardFetchAssetIfNeeded()
    switch try await getMediaType() {
    case PHAssetMediaType.image.rawValue:
      let contentEditingInput = try await phAsset.requestContentEditingInput()
      guard let url = contentEditingInput.fullSizeImageURL else {
        throw FailedToGetPropertyException("uri")
      }
      return url.absoluteString

    case PHAssetMediaType.video.rawValue:
      let options = PHVideoRequestOptions()
      options.version = .original
      guard let avAsset = try await PHImageManager.default()
        .requestAVAsset(forVideo: phAsset, options: options) as? AVURLAsset else {
        throw FailedToGetPropertyException("uri")
      }
      return avAsset.url.absoluteString

    default:
      throw FailedToGetPropertyException("uri")
    }
  }

  func delete() async throws {
    try await fetchAssetIfNeeded()
    guard let assetToDelete = phAsset else {
      throw FailedToDeleteAssetException("PHAsset is nil")
    }
    try await AssetRepository.shared.delete(by: [assetToDelete])
  }

  private func guardFetchAssetIfNeeded() async throws -> PHAsset {
    if let phAsset {
      return phAsset
    }

    let options = PHFetchOptions()
    options.includeHiddenAssets = true
    options.includeAllBurstAssets = true
    options.fetchLimit = 1

    guard let fetchedAsset = PHAsset.fetchAssets(
      withLocalIdentifiers: [self.id],
      options: options
    ).firstObject else {
      throw Exception()
    }
    self.phAsset = fetchedAsset
    return fetchedAsset
  }

  private func fetchAssetIfNeeded() async throws {
    if phAsset != nil {
      return
    }

    let options = PHFetchOptions()
    options.includeHiddenAssets = true
    options.includeAllBurstAssets = true
    options.fetchLimit = 1

    guard let fetchedAsset = PHAsset.fetchAssets(
      withLocalIdentifiers: [self.id],
      options: options
    ).firstObject else {
      throw Exception()
    }
    self.phAsset = fetchedAsset
  }

  static func from(filePath: URL, context: AppContext) async throws -> Asset {
    guard FileManager.default.fileExists(atPath: filePath.path) else {
      throw FailedToCreateAssetException("File does not exist at path: \(filePath.path)")
    }
    let id = try await AssetRepository.shared.add(from: filePath)
    return Asset(id: id, context: context)
  }

  static func assetType(for localUri: URL) -> PHAssetMediaType {
    guard let type = UTType(filenameExtension: localUri.pathExtension) else {
      return .unknown
    }

    if type.conforms(to: .image) {
      return .image
    }
    if type.conforms(to: .movie) || type.conforms(to: .video) {
      return .video
    }
    if type.conforms(to: .audio) {
      return .audio
    }

    return .unknown
  }

  private func property<T>(
    _ getValue: () -> T?,
    function: String = #function
  ) async throws -> T? {
    try await fetchAssetIfNeeded()
    return getValue()
  }
}
