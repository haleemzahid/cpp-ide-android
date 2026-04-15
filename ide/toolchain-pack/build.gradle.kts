/**
 * Install-time asset pack containing the Termux toolchain blob
 * (`termux.zip`). Splitting this out of the base APK keeps the
 * base under Google Play's 150 MB hard limit so we can publish
 * via Play Store at all.
 *
 * `install-time` means the pack is delivered as a split APK
 * alongside the base APK at install time. Assets from install-time
 * packs are merged into the app's standard asset namespace, so
 * `context.assets.open("termux.zip")` still resolves correctly
 * with no Kotlin code change in [TermuxToolchain].
 */
plugins {
    // The asset-pack plugin is already on the build classpath via AGP,
    // so we apply it by id without a version (the plugin DSL rejects
    // double-versioning). It's NOT a separate maven coordinate to add.
    id("com.android.asset-pack")
}

assetPack {
    packName.set("toolchain_pack")
    dynamicDelivery {
        deliveryType.set("install-time")
    }
}
