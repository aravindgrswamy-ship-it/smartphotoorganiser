# Smart Gallery Organiser

Android app that organises your photo library, entirely on your phone.

- **Find photos that contain text** (screenshots, documents, receipts, signs) and search inside that text
- **Find photos by person**: faces are grouped automatically; tap a person to see all their photos, hold to name them
- **Delete one photo or many**: hold a photo to start selecting, tap a folder header to select the whole folder
- **Sort by** date, size, name or folder (folders can be ranked by how much space they use)
- Filter by folder, "Has text", "Has faces", or search by words

Nothing is uploaded: text and face recognition run on the device.
Photos are scanned in the background, newest first, only while the app is open.
Each photo is analysed once, then remembered.

## Build the APK with GitHub (no installs)
1. Create a new GitHub repository and upload everything in this folder
   (the `.github/workflows/build-apk.yml` file must end up in the repository).
2. Open the **Actions** tab and wait for "Build APK" to finish (about 5-8 minutes).
3. Download the `smart-gallery-apk` artifact, unzip it, install the APK on your phone.

The workflow downloads the 13 MB face-recognition model (MobileFaceNet from the
InsightFace project, released for non-commercial research use) during the build,
so it is not part of this folder.

## Where things live
- `Indexer.java`: background scanner (text + faces), `FaceEngine.java`: face detection and fingerprints
- `Clusterer.java` / `ClusterMath.java`: groups faces into people (`Indexer.DEFAULT_THRESHOLD`)
- `Db.java`: remembers scan results; `Store.java`: in-memory copy for the screens
- `MainActivity.java`: gallery, filters, selection; `ViewerActivity.java`: full-screen photo
- `DeleteHelper.java`: deletion on every Android version (one confirmation for a whole batch on Android 11+)
